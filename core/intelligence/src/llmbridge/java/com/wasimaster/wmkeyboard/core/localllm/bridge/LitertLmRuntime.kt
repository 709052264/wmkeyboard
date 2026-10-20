package com.wasimaster.wmkeyboard.core.localllm.bridge

import android.content.Context
import android.os.Build
import android.os.Debug
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.wasimaster.wmkeyboard.core.localllm.ChatReplay
import com.wasimaster.wmkeyboard.core.localllm.LlmRuntime
import com.wasimaster.wmkeyboard.core.localllm.VisionUnsupportedException
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock

/**
 * The one class that touches the LiteRT-LM SDK, reached only by reflection
 * from `LocalLlmEngine` — see [LlmRuntime] for why, and the keep rule in
 * `app/proguard-rules.pro` for how it survives R8. Compiled into
 * `:core:intelligence` for sideload builds and into the on-demand
 * `:feature:llm` module for Play builds; this source file is shared by both.
 *
 * Engine initialization takes seconds and pins the model weights in memory,
 * so one engine is cached across requests and only rebuilt when the model
 * file or backend changes. The facade caches the single instance of this
 * class, so instance state here has the same lifetime the old singleton had.
 */
class LitertLmRuntime : LlmRuntime {

    private val lock = ReentrantLock()
    private var engine: Engine? = null
    private var loadedKey: EngineKey? = null

    /**
     * Bumped every time the cached engine is torn down. A [Chat] remembers
     * the epoch its conversation was built under and rebuilds — replaying its
     * transcript — when the number has moved on, which is how a chat survives
     * the IME's trim-memory release of the shared engine.
     */
    private var engineEpoch = 0

    /**
     * Conversations that are done but not yet closed. Closed lazily right
     * before the next conversation is created (or on [release]) instead of
     * immediately after their generation finishes — closing the native
     * conversation the instant the completion callback returns raced the
     * runtime's own finalization and crashed the IME process.
     */
    private val retiredConversations = ArrayList<Conversation>()

    /**
     * Conversations a live [Chat] still holds. Closed on [releaseLocked] —
     * before the engine, in dependency order — after which the epoch tells
     * their session to rebuild.
     */
    private val sessionConversations = ArrayList<Conversation>()

    /**
     * [gpuKey]s of models that failed on the GPU — go straight to CPU. Kept on
     * disk as well ([GPU_FAILED_FILE]): the IME process is restarted all day,
     * and a list that died with it sent every new process back to the GPU to
     * fail the same way, stranding another few GB each time (CPH2481, Mali-G57:
     * 2.2 GB of GL memory held by an idle keyboard after one failure).
     */
    private val gpuFallback = mutableSetOf<String>()

    /** Whether [gpuFallback] has been read back from disk in this process. */
    private var gpuVerdictsLoaded = false

    /**
     * The [gpuKey] whose GPU engine has not answered yet, mirrored in
     * [GPU_ATTEMPT_FILE]. A native crash in the GPU driver kills the process
     * before any catch runs, so the only trace it leaves is that file still
     * being there when the next process loads the verdicts.
     */
    private var gpuUnproven: String? = null

    /**
     * Process graphics memory in KB before the first GPU engine was built, or
     * -1 before that. The yardstick for [strandedGpuMemoryKb].
     */
    @Volatile
    private var gpuBaselineKb = -1L

    /** A GPU engine has been torn down, or failed to build, in this process. */
    @Volatile
    private var gpuReleased = false

    /** A GPU engine is the loaded one right now. */
    @Volatile
    private var gpuLoaded = false

    /**
     * Model paths that could not take a picture (#234): no vision encoder in
     * the file, or one this device could not start. Remembered so the next
     * screenshot goes straight to the caller's fallback instead of paying a
     * seconds-long engine rebuild to fail the same way again.
     */
    private val visionUnsupported = mutableSetOf<String>()

    /**
     * Model paths that have already answered with a picture attached. Until a
     * model is in here, a failure during an image run is read as "cannot see"
     * rather than as running out of memory — a text-only file can accept the
     * vision backend at init and only trip over the picture itself.
     */
    private val visionProven = mutableSetOf<String>()

    override fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)?,
    ): String {
        lock.lock()
        try {
            val activeEngine = obtainEngineOrThrow(context, modelFile, backend, contextTokens)
            try {
                val conversation = newConversation(activeEngine, system)
                // Done after this call; close it on the next drain.
                retiredConversations.add(conversation)
                return runMessage(conversation, user, onPartial).also { confirmGpu() }
            } catch (e: Throwable) {
                throw generationFailure(context, modelFile, e)
            }
        } finally {
            lock.unlock()
        }
    }

    override fun generateWithImages(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        images: List<ByteArray>,
        onPartial: ((String) -> Unit)?,
    ): String {
        if (images.isEmpty()) {
            return generate(context, modelFile, backend, contextTokens, system, user, onPartial)
        }
        lock.lock()
        try {
            if (modelFile.path in visionUnsupported) throw noVision(context)
            val activeEngine = try {
                obtainEngine(context, modelFile, backend, contextTokens, vision = true)
            } catch (e: Throwable) {
                releaseLocked()
                visionUnsupported += modelFile.path
                throw noVision(context, e)
            }
            try {
                val conversation = newConversation(activeEngine, system)
                retiredConversations.add(conversation)
                // Text first, then the pictures: the order the runtime's own
                // multimodal examples use, and the one Gemma's template expects.
                val contents = Contents.of(
                    listOf<Content>(Content.Text(user)) +
                        images.take(MAX_IMAGES).map { Content.ImageBytes(it) },
                )
                val answer = runContents(conversation, contents, onPartial)
                visionProven += modelFile.path
                confirmGpu()
                return answer
            } catch (e: Throwable) {
                if (modelFile.path !in visionProven) {
                    releaseLocked()
                    visionUnsupported += modelFile.path
                    throw noVision(context, e)
                }
                throw generationFailure(context, modelFile, e)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun noVision(context: Context, cause: Throwable? = null) =
        VisionUnsupportedException(context.getString(R.string.core_intel_llm_engine_error_vision), cause)

    override fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): LlmRuntime.Chat =
        // The session outlives any screen (a static controller holds it), so
        // never let it hold the caller's Activity.
        Chat(context.applicationContext, modelFile, backend, contextTokens, system)

    /**
     * One live chat. All methods that touch the native runtime take the same
     * global lock as [generate], so a chat turn and a keyboard AI action
     * queue behind each other rather than racing. Blocking — call
     * [sendMessage] on an IO dispatcher only.
     */
    private inner class Chat(
        private val context: Context,
        private val modelFile: File,
        private val backend: LocalLlmBackend,
        private val contextTokens: Int,
        private val system: String,
    ) : LlmRuntime.Chat {

        private var conversation: Conversation? = null
        private var conversationEpoch = -1
        private val history = ArrayList<ChatReplay.Turn>()

        // Volatile: close() can set it without the lock when a generation is
        // holding it.
        @Volatile
        private var closed = false

        /**
         * Sends one user message and blocks until the full answer is ready,
         * forwarding accumulated text to [onPartial] as it streams (from the
         * native callback thread). When the shared engine was torn down since
         * the last turn — the IME trims memory whenever the keyboard hides —
         * the conversation is rebuilt and the transcript replayed first.
         *
         * Throws [IOException] with a user-presentable message on failure;
         * the session itself stays usable and rebuilds on the next call.
         */
        override fun sendMessage(user: String, onPartial: ((String) -> Unit)?): String {
            lock.lock()
            try {
                check(!closed) { "Session is closed" }
                val activeEngine =
                    obtainEngineOrThrow(context, modelFile, backend, contextTokens)
                try {
                    var message = user
                    var active = conversation
                    if (active == null || conversationEpoch != engineEpoch) {
                        active = newConversation(activeEngine, system)
                        sessionConversations.add(active)
                        conversation = active
                        conversationEpoch = engineEpoch
                        message = ChatReplay.replayMessage(history, user)
                    }
                    val raw = runMessage(active, message, onPartial)
                    confirmGpu()
                    history.add(ChatReplay.Turn(fromUser = true, text = user))
                    history.add(ChatReplay.Turn(fromUser = false, text = stripThink(raw)))
                    return raw
                } catch (e: Throwable) {
                    // releaseLocked inside closed this conversation with the
                    // engine; the epoch check rebuilds it on the next call.
                    conversation = null
                    throw generationFailure(context, modelFile, e)
                }
            } finally {
                lock.unlock()
            }
        }

        /**
         * Pre-fills the replay mirror for a conversation resumed from disk,
         * so the first message replays the saved transcript. Only effective
         * before the first [sendMessage]; a live conversation already holds
         * its own context.
         */
        override fun seed(transcript: List<ChatReplay.Turn>) {
            lock.lock()
            try {
                if (conversation == null && history.isEmpty()) history.addAll(transcript)
            } finally {
                lock.unlock()
            }
        }

        /**
         * Records an answer the caller cut short with Stop. The native call
         * ran to completion behind the scenes — a blocking generation cannot
         * be interrupted — so the conversation's KV cache holds the full
         * text; mirror it, not the partial, or a replay would diverge from
         * what the model remembers.
         */
        override fun recordStoppedTurn(user: String, fullAnswer: String) {
            lock.lock()
            try {
                if (closed) return
                history.add(ChatReplay.Turn(fromUser = true, text = user))
                history.add(ChatReplay.Turn(fromUser = false, text = stripThink(fullAnswer)))
            } finally {
                lock.unlock()
            }
        }

        /**
         * Retires the session. Non-blocking best effort: when a generation
         * holds the lock the conversation is left for the next drain or
         * [release] to close — never closed immediately, see
         * [retiredConversations].
         */
        override fun close() {
            if (!lock.tryLock()) {
                closed = true
                return
            }
            try {
                closed = true
                conversation?.let {
                    sessionConversations.remove(it)
                    retiredConversations.add(it)
                }
                conversation = null
            } finally {
                lock.unlock()
            }
        }

        /**
         * The transcript without reasoning, for the replay mirror. Keeps to
         * the last close tag so both explicit `<think>…</think>` blocks and
         * the close-tag-only shape reasoning templates emit strip the same
         * way. Local copy: this module sits below the one that owns
         * AiThinking.
         */
        private fun stripThink(raw: String): String {
            val close = raw.lastIndexOf("</think>")
            return if (close >= 0) raw.substring(close + "</think>".length).trim()
            else raw.trim()
        }
    }

    /** [obtainEngine] with the load failure mapped for the caller. Lock held. */
    private fun obtainEngineOrThrow(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
    ): Engine = try {
        obtainEngine(context, modelFile, backend, contextTokens)
    } catch (e: Throwable) {
        releaseLocked()
        throw IOException(context.getString(R.string.core_intel_llm_engine_error_load), e)
    }

    /** Drains the retired conversations, then opens a new one. Lock held. */
    private fun newConversation(activeEngine: Engine, system: String): Conversation {
        drainRetired()
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
        )
        return activeEngine.createConversation(config)
    }

    /**
     * Sends one message on [conversation] and blocks for the full answer.
     * Streaming goes via the raw MessageCallback overload, NOT the Flow one:
     * litertlm 0.14.0's Flow bridge was compiled against a newer
     * kotlinx-coroutines and its internal SendChannel.close call throws
     * NoSuchMethodError on this project's coroutines — on litertlm's own
     * callback thread, where nothing can catch it, killing the IME the
     * moment a response finished.
     */
    private fun runMessage(
        conversation: Conversation,
        text: String,
        onPartial: ((String) -> Unit)?,
    ): String {
        if (onPartial == null) return textOf(conversation.sendMessage(text))
        return streamed(onPartial) { callback -> conversation.sendMessageAsync(text, callback) }
    }

    /** [runMessage] for a multimodal turn; the same callback route, never the Flow one. */
    private fun runContents(
        conversation: Conversation,
        contents: Contents,
        onPartial: ((String) -> Unit)?,
    ): String {
        if (onPartial == null) return textOf(conversation.sendMessage(contents))
        return streamed(onPartial) { callback -> conversation.sendMessageAsync(contents, callback) }
    }

    /** Runs [send] with a callback that accumulates text, and blocks until it is done. */
    private fun streamed(
        onPartial: (String) -> Unit,
        send: (MessageCallback) -> Unit,
    ): String {
        val out = StringBuilder()
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        send(
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Never let an exception escape into native code.
                    runCatching {
                        out.append(textOf(message))
                        onPartial(out.toString())
                    }
                }

                override fun onDone() {
                    done.countDown()
                }

                override fun onError(throwable: Throwable) {
                    failure = throwable
                    done.countDown()
                }
            },
        )
        done.await()
        failure?.let { throw it }
        return out.toString()
    }

    /**
     * Maps a native generation failure to a user-presentable [IOException].
     * Native failures (OOM included) can leave the engine wedged, so the
     * whole cache is torn down. Lock held.
     */
    private fun generationFailure(
        context: Context,
        modelFile: File,
        e: Throwable,
    ): IOException {
        val wasGpu = loadedKey?.backend == "gpu"
        releaseLocked()
        if (wasGpu) {
            // GPU init succeeded but generation blew up — usually GPU
            // memory, which is far tighter than system RAM. Blacklist
            // this model on GPU so the retry runs on CPU without the
            // user having to work out that the backend was the problem.
            markGpuFailed(context, modelFile)
            return IOException(
                context.getString(R.string.core_intel_llm_engine_error_gpu),
                e,
            )
        }
        return IOException(
            context.getString(R.string.core_intel_llm_engine_error_memory),
            e,
        )
    }

    /**
     * Frees the cached engine. Non-blocking: when a generation is running the
     * weights are in use anyway, so the release is skipped rather than making
     * the caller (IME main thread on trim-memory) wait.
     */
    override fun release() {
        if (!lock.tryLock()) return
        try {
            releaseLocked()
        } finally {
            lock.unlock()
        }
    }

    override fun gpuMemoryMayBeStranded(): Boolean = gpuReleased && !gpuLoaded

    /**
     * Graphics memory this process holds above what it held before its first
     * GPU engine, while no GPU engine is loaded. Deleting the engine does not
     * give it back on every driver: on Mali the OpenCL context LiteRT made
     * outlives the engine with its buffers in it, and only the process ending
     * returns them. Reads smaps and memtrack, tens of ms — off the main thread.
     */
    override fun strandedGpuMemoryKb(): Long {
        if (!gpuMemoryMayBeStranded() || gpuBaselineKb < 0) return 0L
        return (graphicsKb() - gpuBaselineKb).coerceAtLeast(0L)
    }

    private fun graphicsKb(): Long = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.getMemoryStat("summary.graphics")?.toLongOrNull()
    }.getOrNull() ?: 0L

    /**
     * Identifies a model on this phone as it is now. A system update (a new
     * GPU driver), an app update (a new runtime) or a redownloaded file each
     * deserve a fresh try on the GPU, so they are part of the key.
     */
    private fun gpuKey(context: Context, modelFile: File): String =
        "${gpuKeyPrefix(context)}${modelFile.length()}|${modelFile.path}"

    /** The part of a [gpuKey] that is the same for every model until an update. */
    private fun gpuKeyPrefix(context: Context): String {
        val installed = installStamp ?: runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L).also { installStamp = it }
        return "${Build.FINGERPRINT}|$installed|"
    }

    /** When this install was last updated; fixed for the life of the process. */
    private var installStamp: Long? = null

    /**
     * Reads the GPU verdicts back once per process, turning an attempt the
     * last process never finished into a failure. Verdicts from before the
     * last system or app update are dropped, which is what gives the GPU its
     * fresh try. Lock held.
     */
    private fun loadGpuVerdicts(context: Context) {
        if (gpuVerdictsLoaded) return
        gpuVerdictsLoaded = true
        val dir = context.noBackupFilesDir
        val current = gpuKeyPrefix(context)
        val stored = runCatching { File(dir, GPU_FAILED_FILE).readLines() }.getOrNull()
            ?.filter { it.isNotBlank() }
            .orEmpty()
        gpuFallback += stored.filter { it.startsWith(current) }
        val attempt = File(dir, GPU_ATTEMPT_FILE)
        val crashed = runCatching { attempt.readText().trim() }.getOrNull()
        if (!crashed.isNullOrEmpty() && crashed.startsWith(current)) gpuFallback += crashed
        if (gpuFallback.size != stored.size || !stored.containsAll(gpuFallback)) saveGpuVerdicts(context)
        runCatching { attempt.delete() }
    }

    private fun saveGpuVerdicts(context: Context) {
        runCatching {
            File(context.noBackupFilesDir, GPU_FAILED_FILE)
                .writeText(gpuFallback.joinToString("\n"))
        }
    }

    /** This model does not run on this GPU; remembered across processes. Lock held. */
    private fun markGpuFailed(context: Context, modelFile: File) {
        gpuFallback += gpuKey(context, modelFile)
        saveGpuVerdicts(context)
        gpuUnproven = null
        runCatching { File(context.noBackupFilesDir, GPU_ATTEMPT_FILE).delete() }
    }

    /**
     * The loaded GPU engine has answered, or was let go of in an orderly way:
     * either way the process survived it, so the next one has nothing to
     * blame on the GPU. Lock held.
     */
    private fun confirmGpu() {
        if (gpuUnproven == null) return
        gpuUnproven = null
        attemptFile?.let { runCatching { it.delete() } }
    }

    /** [GPU_ATTEMPT_FILE] in this app's no-backup dir, once a context has been seen. */
    private var attemptFile: File? = null

    private fun obtainEngine(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        vision: Boolean = false,
    ): Engine {
        loadGpuVerdicts(context)
        val gpuId = if (backend == LocalLlmBackend.GPU) gpuKey(context, modelFile) else null
        val wantGpu = gpuId != null && gpuId !in gpuFallback
        // The context size is part of the key, not just the config: it is baked
        // into the loaded engine, so a cached one built at the old size would
        // quietly ignore a change until the process restarted.
        val key = EngineKey(modelFile.path, if (wantGpu) "gpu" else "cpu", contextTokens, vision)
        // An engine with its vision encoder answers text too, so a text call
        // keeps it rather than paying a rebuild to drop the encoder; only a
        // picture on a text-only engine forces one.
        engine?.let { loaded ->
            val current = loadedKey
            if (current == key || (current != null && current.vision && current.copy(vision = false) == key.copy(vision = false))) {
                return loaded
            }
        }
        releaseLocked()

        if (wantGpu) {
            if (gpuBaselineKb < 0) gpuBaselineKb = graphicsKb()
            // Written before the native code runs: if the driver takes the
            // process down, the file is all the next one has to go on.
            val attempt = File(context.noBackupFilesDir, GPU_ATTEMPT_FILE)
            attemptFile = attempt
            gpuUnproven = gpuId
            runCatching { attempt.writeText(gpuId!!) }
            try {
                return buildEngine(context, modelFile, Backend.GPU(), contextTokens, vision).also {
                    engine = it
                    loadedKey = key
                    gpuLoaded = true
                }
            } catch (_: Throwable) {
                // Whatever it allocated before failing may be stranded too.
                gpuReleased = true
                markGpuFailed(context, modelFile)
            }
        }
        return buildEngine(context, modelFile, Backend.CPU(), contextTokens, vision).also {
            engine = it
            loadedKey = key.copy(backend = "cpu")
        }
    }

    private fun buildEngine(
        context: Context,
        modelFile: File,
        backend: Backend,
        contextTokens: Int,
        vision: Boolean,
    ): Engine {
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }
        val engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                // The vision encoder runs on the same kind of backend as the
                // text model: a GPU the text model could not use is no better
                // for the encoder. Left unset for text, so a text-only file
                // never meets a backend for a section it does not have.
                visionBackend = if (vision) sameKind(backend) else null,
                maxNumImages = if (vision) MAX_IMAGES else null,
                cacheDir = cache.path,
                // Null leaves the window to the model. This is the whole window,
                // prompt and answer together — litertlm has no per-response
                // ceiling to set, so this is the only lever there is.
                maxNumTokens = contextTokens.takeIf { it > 0 },
            ),
        )
        engine.initialize()
        return engine
    }

    /** A fresh backend of the same kind; one Backend instance is not shared between sections. */
    private fun sameKind(backend: Backend): Backend =
        if (backend is Backend.GPU) Backend.GPU() else Backend.CPU()

    /** Identifies a loaded engine: the inputs that built it. */
    private data class EngineKey(
        val modelPath: String,
        val backend: String,
        val contextTokens: Int,
        /** Built with the vision encoder (#234). */
        val vision: Boolean = false,
    )

    private companion object {
        /**
         * Pictures one turn may carry. A reply is usually one screenshot,
         * sometimes two of a long thread; every picture is hundreds of tokens
         * of a small context window, so more would crowd out the answer.
         */
        const val MAX_IMAGES = 3

        /** One [gpuKey] per line: models that failed on this phone's GPU. */
        const val GPU_FAILED_FILE = "litertlm-gpu-failed"

        /** The [gpuKey] of a GPU engine that has not answered yet. */
        const val GPU_ATTEMPT_FILE = "litertlm-gpu-attempt"
    }

    /** Closes conversations that are done. Lock held. */
    private fun drainRetired() {
        for (conversation in retiredConversations) {
            runCatching { conversation.close() }
        }
        retiredConversations.clear()
    }

    private fun releaseLocked() {
        drainRetired()
        // Sessions' conversations close before their engine, in dependency
        // order; the epoch bump tells each session to rebuild from its
        // transcript on its next message.
        for (conversation in sessionConversations) {
            runCatching { conversation.close() }
        }
        sessionConversations.clear()
        runCatching { engine?.close() }
        if (loadedKey?.backend == "gpu") {
            gpuReleased = true
            // Torn down by this code, not by a crash. A failure that led here
            // is recorded by the caller straight after.
            confirmGpu()
        }
        gpuLoaded = false
        engine = null
        loadedKey = null
        engineEpoch++
    }

    private fun textOf(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
}
