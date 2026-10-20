package com.wasimaster.wmkeyboard.core.localllm

import android.app.ActivityManager
import android.content.Context
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The entry point `LocalLlmEngine` routes GGUF files through: llama.cpp, the
 * second on-device runtime. LiteRT-LM keeps every `.litertlm` and `.task`
 * file, and stays the default: a model runs here only when it is a GGUF,
 * which the user chose by picking or importing one.
 */
object LlamaCpp {

    /** Whether [modelFile] is a GGUF, so this runtime's to run. */
    fun handles(modelFile: File): Boolean = GgufFile.runsOnLlamaCpp(modelFile)

    /** Whether this build has llama.cpp, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = LlamaNative.shipped

    /** The library's delivery state, for a settings banner. */
    val moduleState get() = FeatureModules.llama.state

    fun requestModule() = FeatureModules.llama.requestInstall()

    private val instance by lazy { LlamaCppRuntime() }

    @Volatile
    private var started = false

    /**
     * The runtime, or why it cannot run yet as a user-presentable
     * [IOException]: the library is still on its way (a Play split, or a
     * GitHub build's download), it needs the download opt-in first, or this
     * build has none.
     */
    fun runtime(context: Context): LlmRuntime {
        val gate = FeatureModules.llama
        if (!gate.installed) {
            val state = gate.state.value
            if (state is ModuleState.Missing && state.consent != null) {
                throw IOException(context.getString(R.string.core_intel_llm_llama_consent_needed))
            }
            gate.requestInstall()
            throw IOException(context.getString(R.string.core_intel_llm_module_downloading))
        }
        if (!LlamaNative.available) {
            throw IOException(context.getString(R.string.core_intel_llm_llama_missing))
        }
        started = true
        return instance
    }

    /** Frees the loaded model, if any. Non-blocking, like LiteRT-LM's. */
    fun release() {
        if (started) instance.release()
    }

    /** Ends the running answer early: the text so far is what the caller gets back. */
    fun stop() {
        if (started) instance.stop()
    }

    /**
     * The scoring session for prediction experiments: token log-probabilities
     * of a text under a GGUF model, nothing sampled. Internal on purpose; no
     * feature uses it yet. Blocking, IO dispatcher only, and it shares the
     * runtime's lock and memory checks with generation.
     */
    internal fun score(context: Context, modelFile: File, text: String, contextTokens: Int = 1024): List<LlamaTokenScore> =
        (runtime(context) as LlamaCppRuntime).score(context, modelFile, text, contextTokens)
}

/**
 * llama.cpp behind [LlmRuntime]. One model and one context are kept loaded
 * between requests, keyed by the file and the window size; a request for
 * another file or size tears them down first. Chats and one-off requests
 * share the context: a request whose prompt begins the way the cache does
 * decodes only the rest, so turn two of a chat is warm, and switching between
 * a chat and a keyboard action costs one prompt's prefill.
 *
 * Unlike the LiteRT-LM runtime, an answer here can be stopped part way
 * ([stop]): the caller gets the text so far, and the cache keeps exactly it.
 *
 * Only the CPU: Compute = GPU or NPU applies to LiteRT-LM models, and a GGUF
 * runs on the CPU whatever it says. No pictures: [generateWithImages] says so
 * with [VisionUnsupportedException], which callers fall back from.
 */
internal class LlamaCppRuntime : LlmRuntime {

    private val lock = ReentrantLock()
    private var model: LlamaModel? = null
    private var session: LlamaSession? = null
    private var loadedKey: String? = null

    /** The session answering right now, for [stop]. */
    @Volatile
    private var running: LlamaSession? = null

    private val idleTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "llama-idle").apply { isDaemon = true }
    }
    private var idleRelease: ScheduledFuture<*>? = null

    override fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)?,
    ): String {
        val messages = buildList {
            if (system.isNotBlank()) add(LlamaMessage(LlamaMessage.SYSTEM, system))
            add(LlamaMessage(LlamaMessage.USER, user))
        }
        return withSession(context, modelFile, contextTokens) { active ->
            answer(context, active, messages, onPartial).text
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
        if (images.isEmpty()) return generate(context, modelFile, backend, contextTokens, system, user, onPartial)
        // This build of llama.cpp has no vision projector support (mtmd).
        throw VisionUnsupportedException(context.getString(R.string.core_intel_llm_engine_error_vision))
    }

    override fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): LlmRuntime.Chat = Chat(context.applicationContext, modelFile, contextTokens, system)

    override fun release() {
        if (!lock.tryLock()) return
        try {
            releaseLocked()
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        running?.abort()
    }

    override fun gpuMemoryMayBeStranded(): Boolean = false

    override fun strandedGpuMemoryKb(): Long = 0L

    fun score(context: Context, modelFile: File, text: String, contextTokens: Int): List<LlamaTokenScore> =
        withSession(context, modelFile, contextTokens) { active ->
            running = active
            try {
                active.score(text) ?: throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory))
            } finally {
                running = null
            }
        }

    /**
     * A chat whose messages are rendered with the model's template on every
     * turn. The KV cache already holds the earlier turns whenever the context
     * was not unloaded or used by something else in between, and llama.cpp
     * then decodes only the new message; otherwise the whole transcript is
     * decoded again, as the real conversation rather than a summary of it.
     */
    private inner class Chat(
        private val context: Context,
        private val modelFile: File,
        private val contextTokens: Int,
        private val system: String,
    ) : LlmRuntime.Chat {

        private val history = ArrayList<ChatReplay.Turn>()

        @Volatile
        private var closed = false

        override fun sendMessage(user: String, onPartial: ((String) -> Unit)?): String {
            check(!closed) { "Session is closed" }
            return withSession(context, modelFile, contextTokens) { active ->
                val answer = answerFitting(active, user, onPartial)
                history.add(ChatReplay.Turn(fromUser = true, text = user))
                history.add(ChatReplay.Turn(fromUser = false, text = answer))
                answer
            }
        }

        /**
         * Drops the oldest exchanges until the conversation fits the window
         * with room to answer; the system prompt and the new message always
         * stay.
         */
        private fun answerFitting(active: LlamaSession, user: String, onPartial: ((String) -> Unit)?): String {
            var skip = 0
            while (true) {
                val messages = buildList {
                    if (system.isNotBlank()) add(LlamaMessage(LlamaMessage.SYSTEM, system))
                    history.drop(skip).forEach { turn ->
                        add(LlamaMessage(if (turn.fromUser) LlamaMessage.USER else LlamaMessage.ASSISTANT, turn.text))
                    }
                    add(LlamaMessage(LlamaMessage.USER, user))
                }
                try {
                    return answer(context, active, messages, onPartial).text
                } catch (e: LlamaPromptTooLong) {
                    if (skip >= history.size) throw e
                    skip = (skip + 2).coerceAtMost(history.size)
                }
            }
        }

        override fun seed(transcript: List<ChatReplay.Turn>) {
            lock.lock()
            try {
                if (history.isEmpty()) history.addAll(transcript)
            } finally {
                lock.unlock()
            }
        }

        /**
         * An answer Stop cut short. [sendMessage] has already recorded what
         * the model wrote, which is exactly what the cache holds, so this only
         * corrects the record when the caller's text differs.
         */
        override fun recordStoppedTurn(user: String, fullAnswer: String) {
            lock.lock()
            try {
                if (closed) return
                val last = history.lastOrNull()
                val before = history.getOrNull(history.size - 2)
                if (last != null && !last.fromUser && before?.fromUser == true && before.text == user) {
                    history[history.size - 1] = ChatReplay.Turn(fromUser = false, text = fullAnswer)
                } else {
                    history.add(ChatReplay.Turn(fromUser = true, text = user))
                    history.add(ChatReplay.Turn(fromUser = false, text = fullAnswer))
                }
            } finally {
                lock.unlock()
            }
        }

        override fun close() {
            closed = true
        }
    }

    /** Thrown inside [answer] so a chat can drop old turns and retry. */
    private class LlamaPromptTooLong(message: String) : IOException(message)

    /**
     * Loads [messages] and samples the answer. The window left after the
     * prompt is the answer's limit, capped so a model that never ends its
     * turn stops by itself. Lock held.
     */
    private fun answer(
        context: Context,
        active: LlamaSession,
        messages: List<LlamaMessage>,
        onPartial: ((String) -> Unit)?,
    ): LlamaAnswer {
        running = active
        try {
            val promptTokens = try {
                active.promptChat(messages)
            } catch (e: LlamaPromptException) {
                when (e.code) {
                    LlamaNative.PROMPT_TOO_LONG ->
                        throw LlamaPromptTooLong(context.getString(R.string.core_intel_llm_engine_error_too_long))
                    // Stopped while reading the prompt: no answer yet.
                    LlamaNative.PROMPT_ABORTED -> return LlamaAnswer("", LlamaStop.ABORTED, 0)
                    LlamaNative.PROMPT_TEMPLATE_FAILED ->
                        throw IOException(context.getString(R.string.core_intel_llm_engine_error_template), e)
                    else -> throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory), e)
                }
            }
            val room = active.contextSize - promptTokens
            val result = active.generate(minOf(room, MAX_ANSWER_TOKENS), onPartial)
            if (result.stop == LlamaStop.ERROR && result.text.isEmpty()) {
                throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory))
            }
            return result
        } finally {
            running = null
        }
    }

    /**
     * Runs [block] on the session for [modelFile] at [contextTokens], loading
     * it first when another (or none) is loaded, then schedules the idle
     * unload. Failures to load tear everything down and come back as
     * user-presentable [IOException]s.
     */
    private fun <T> withSession(context: Context, modelFile: File, contextTokens: Int, block: (LlamaSession) -> T): T {
        lock.lock()
        try {
            idleRelease?.cancel(false)
            val active = obtainSession(context, modelFile, contextTokens)
            try {
                return block(active)
            } catch (e: IOException) {
                throw e
            } catch (e: Throwable) {
                releaseLocked()
                throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory), e)
            }
        } finally {
            idleRelease = idleTimer.schedule({ release() }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
            lock.unlock()
        }
    }

    /** The loaded session when it matches, else a fresh one after the memory check. Lock held. */
    private fun obtainSession(context: Context, modelFile: File, contextTokens: Int): LlamaSession {
        if (!modelFile.isFile) throw IOException(context.getString(R.string.core_intel_llm_engine_error_load))
        val wanted = if (contextTokens > 0) contextTokens else DEFAULT_CONTEXT
        val key = "${modelFile.path}|${modelFile.length()}|${modelFile.lastModified()}|$wanted"
        session?.takeIf { loadedKey == key }?.let { return it }
        releaseLocked()

        val memory = memoryInfo(context)
        val fileBytes = modelFile.length()
        // The weights alone have to fit before anything is worth opening.
        if (fileBytes + OVERHEAD_BYTES > memory.ceiling) throw notEnoughMemory(context, fileBytes + OVERHEAD_BYTES, memory)

        val opened = LlamaModel.open(modelFile)
            ?: throw IOException(context.getString(R.string.core_intel_llm_engine_error_load))
        try {
            val trained = opened.info.trainedContext.takeIf { it > 0 } ?: wanted
            var contextSize = minOf(wanted, trained)
            // The cache grows with the window: shrink it to what fits now,
            // never below a window a short request still works in.
            val perToken = opened.info.kvBytesPerToken.coerceAtLeast(1)
            while (contextSize > MIN_CONTEXT && fileBytes + perToken * contextSize + OVERHEAD_BYTES > memory.available) {
                contextSize = (contextSize / 2).coerceAtLeast(MIN_CONTEXT)
            }
            val needed = fileBytes + perToken * contextSize + OVERHEAD_BYTES
            if (needed > memory.ceiling) throw notEnoughMemory(context, needed, memory)
            val created = LlamaSession(opened, contextSize, LlamaThreads.forDevice())
            model = opened
            session = created
            loadedKey = key
            return created
        } catch (e: Throwable) {
            session?.close()
            session = null
            opened.close()
            model = null
            loadedKey = null
            // The memory check's own message says more than the generic one.
            if (e is LowMemoryException) throw e
            throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory), e)
        }
    }

    /**
     * Free memory now ([available]) and the most a model may ever take on this
     * phone ([ceiling]). Above the ceiling a load is refused outright; between
     * the two the context shrinks first, and what is left is Android's to
     * find by closing cached apps.
     */
    private class Memory(val available: Long, val total: Long) {
        val ceiling: Long get() = maxOf(available, total * 6 / 10)
    }

    private fun memoryInfo(context: Context): Memory {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return Memory(info.availMem, info.totalMem)
    }

    private class LowMemoryException(message: String) : IOException(message)

    private fun notEnoughMemory(context: Context, needed: Long, memory: Memory) = LowMemoryException(
        context.getString(
            R.string.core_intel_llm_engine_error_low_memory,
            LocalLlmDownloadManager.formatBytes(needed),
            LocalLlmDownloadManager.formatBytes(memory.available),
        ),
    )

    private fun releaseLocked() {
        session?.close()
        session = null
        model?.close()
        model = null
        loadedKey = null
    }

    companion object {
        /** The window when the setting asks for none. */
        const val DEFAULT_CONTEXT = 4096

        /** The smallest window the memory check shrinks to. */
        const val MIN_CONTEXT = 1024

        /** The longest answer, so a model that never ends its turn still stops. */
        const val MAX_ANSWER_TOKENS = 4096

        /** Compute buffers, the logits and llama.cpp's own bookkeeping, roughly. */
        const val OVERHEAD_BYTES = 160L * 1024 * 1024

        /**
         * A loaded model that nothing has used for this long is unloaded. The
         * keyboard releases its own earlier when it hides; this covers the
         * settings app's chat and assistant, which nothing else releases.
         */
        const val IDLE_RELEASE_MS = 3 * 60 * 1000L
    }
}

/** How many threads llama.cpp gets: the phone's big cores. */
internal object LlamaThreads {

    @Volatile
    private var cached = 0

    fun forDevice(): Int {
        cached.takeIf { it > 0 }?.let { return it }
        return pick(
            cores = Runtime.getRuntime().availableProcessors(),
            capacities = readPerCore("cpu_capacity"),
            maxFreqs = readPerCore("cpufreq/cpuinfo_max_freq"),
        ).also { cached = it }
    }

    /**
     * Big cores by the kernel's own capacity figure where it publishes one
     * (1024 is the fastest core): the cores at half of the top or more. A
     * thread on a little core holds every other thread up at each step's
     * barrier, so leaving them out is faster, not just kinder. Without
     * capacities, the cores above the slowest cluster's clock, or half the
     * cores when every one runs at the same clock. Between 1 and 8.
     */
    fun pick(cores: Int, capacities: List<Long>, maxFreqs: List<Long>): Int {
        val big = when {
            capacities.size == cores && capacities.max() > 0 -> {
                val top = capacities.max()
                capacities.count { it * 2 >= top }
            }
            maxFreqs.size == cores && maxFreqs.distinct().size > 1 -> {
                val slowest = maxFreqs.min()
                maxFreqs.count { it > slowest }
            }
            else -> (cores + 1) / 2
        }
        return big.coerceIn(1, 8)
    }

    private fun readPerCore(path: String): List<Long> {
        val cores = Runtime.getRuntime().availableProcessors()
        return (0 until cores).mapNotNull { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/$path").readText().trim().toLong() }.getOrNull()
        }
    }
}
