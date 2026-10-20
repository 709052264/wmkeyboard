package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import java.io.File

/**
 * Lite-flavor stub — the LiteRT-LM runtime is a full-build feature, so this
 * object only keeps the shared callers (IME service) compiling. The
 * On-device provider is hidden from lite settings, making this unreachable
 * in practice.
 *
 * llama.cpp needs nothing from Google, so a GGUF model runs here as in the
 * full build ([LlamaCpp]); only LiteRT-LM files are refused.
 */
object LocalLlmEngine {

    const val AVAILABLE = false

    fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)? = null,
    ): String {
        if (LlamaCpp.handles(modelFile)) {
            return LlamaCpp.runtime(context).generate(context, modelFile, backend, contextTokens, system, user, onPartial)
        }
        error("On-device AI is not available in the lite build")
    }

    /**
     * Throws [VisionUnsupportedException] rather than `error()`: the reply tool
     * catches that type to fall back to reading the picture another way, and
     * an IllegalStateException would get past it.
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
    ): String {
        if (LlamaCpp.handles(modelFile)) {
            return LlamaCpp.runtime(context)
                .generateWithImages(context, modelFile, backend, contextTokens, system, user, images, onPartial)
        }
        throw VisionUnsupportedException("On-device AI is not available in the lite build")
    }

    fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): ChatSession {
        if (LlamaCpp.handles(modelFile)) {
            return ChatSession(LlamaCpp.runtime(context).chatSession(context, modelFile, backend, contextTokens, system))
        }
        error("On-device AI is not available in the lite build")
    }

    /** Mirror of the full-flavor session so shared callers compile; only llama.cpp backs it here. */
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

    fun release() = LlamaCpp.release()

    fun stop() = LlamaCpp.stop()

    fun gpuMemoryMayBeStranded(): Boolean = false

    fun strandedGpuMemoryKb(): Long = 0L
}
