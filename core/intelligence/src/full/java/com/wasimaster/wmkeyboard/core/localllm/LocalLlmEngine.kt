package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException

/**
 * Full-flavor facade over the LiteRT-LM runtime. The runtime implementation
 * ([LlmModule.BRIDGE_CLASS]) is reached by reflection because its home
 * differs per channel — embedded in this module for sideload builds, in the
 * on-demand `:feature:llm` split for Play builds; see [LlmRuntime].
 *
 * On a Play install where the split is not yet present, the first call kicks
 * off the download and throws a user-presentable [IOException], exactly the
 * error shape callers already render — so "AI part is still downloading" and
 * "model ran out of memory" travel the same path. The lite flavor replaces
 * this object with a stub that throws.
 */
object LocalLlmEngine {

    const val AVAILABLE = true

    @Volatile
    private var runtime: LlmRuntime? = null

    /**
     * The loaded runtime, or the reason there is none yet as an
     * [IOException]. Every failure path requests what is missing, so a
     * retry after the download finishes just works — without a restart:
     * SplitCompat (installed at startup in Play builds) lets this process
     * load the split's classes and native libraries as soon as the install
     * completes.
     */
    private fun runtime(context: Context): LlmRuntime {
        runtime?.let { return it }
        if (!LlmModule.gate.installed()) {
            LlmModule.gate.requestInstall()
            throw IOException(context.getString(R.string.core_intel_llm_module_downloading))
        }
        return try {
            val impl = Class.forName(LlmModule.BRIDGE_CLASS, true, context.classLoader)
                .getDeclaredConstructor()
                .newInstance() as LlmRuntime
            runtime = impl
            impl
        } catch (e: Throwable) {
            // Installed but not loadable — a split installed moments ago on a
            // device where the classloader will only see it after a restart.
            throw IOException(context.getString(R.string.core_intel_llm_module_restart), e)
        }
    }

    /**
     * Runs one system+user exchange on the local model, blocking until the
     * full response is ready. Call on an IO dispatcher only. When [onPartial]
     * is set, the accumulated response text is forwarded as it streams.
     *
     * Throws [IOException] with a user-presentable message on any failure.
     */
    fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)? = null,
    ): String = runtime(context)
        .generate(context, modelFile, backend, contextTokens, system, user, onPartial)

    /**
     * [generate] with pictures on the user turn (#234: a chat screenshot for
     * the reply tool). Builds the engine with its vision encoder, which only
     * multimodal models carry; a model without one fails with
     * [VisionUnsupportedException], a subclass of [IOException], so a caller
     * that has another way to read the picture can tell the two failures
     * apart and fall back rather than show an error.
     */
    fun generateWithImages(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        images: List<ByteArray>,
        onPartial: ((String) -> Unit)? = null,
    ): String = runtime(context)
        .generateWithImages(context, modelFile, backend, contextTokens, system, user, images, onPartial)

    /**
     * Opens a chat whose context lives across [ChatSession.sendMessage] calls
     * — real multi-turn on the conversation's KV cache, unlike [generate],
     * which starts over every call. Creating the session needs the runtime,
     * so on a Play install this is the call that can throw the
     * still-downloading [IOException]; the engine and the native conversation
     * are built on the first message.
     */
    fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): ChatSession = ChatSession(
        runtime(context).chatSession(context, modelFile, backend, contextTokens, system),
    )

    /** Wrapper keeping the public session type stable across channels. */
    class ChatSession internal constructor(
        private val delegate: LlmRuntime.Chat,
    ) {

        fun sendMessage(user: String, onPartial: ((String) -> Unit)? = null): String =
            delegate.sendMessage(user, onPartial)

        fun seed(transcript: List<ChatReplay.Turn>) = delegate.seed(transcript)

        fun recordStoppedTurn(user: String, fullAnswer: String) =
            delegate.recordStoppedTurn(user, fullAnswer)

        fun close() = delegate.close()
    }

    /**
     * Frees the cached engine. Non-blocking; safe when the runtime was never
     * loaded (nothing to free) — the IME calls this on every trim-memory.
     */
    fun release() {
        runtime?.release()
    }
}
