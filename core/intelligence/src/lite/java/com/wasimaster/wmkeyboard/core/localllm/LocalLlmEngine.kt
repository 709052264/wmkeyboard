package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import java.io.File

/**
 * Lite-flavor stub — the LiteRT-LM runtime is a full-build feature, so this
 * object only keeps the shared callers (IME service) compiling. The
 * On-device provider is hidden from lite settings, making this unreachable
 * in practice.
 */
object LocalLlmEngine {

    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)? = null,
    ): String = error("On-device AI is not available in the lite build")

    /**
     * Throws [VisionUnsupportedException] rather than `error()`: the reply tool
     * catches that type to fall back to reading the picture another way, and
     * an IllegalStateException would get past it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun generateWithImages(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        images: List<ByteArray>,
        onPartial: ((String) -> Unit)? = null,
    ): String = throw VisionUnsupportedException("On-device AI is not available in the lite build")

    @Suppress("UNUSED_PARAMETER")
    fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): ChatSession = error("On-device AI is not available in the lite build")

    /** Mirror of the full-flavor session so shared callers compile. */
    class ChatSession private constructor() {

        @Suppress("UNUSED_PARAMETER")
        fun sendMessage(user: String, onPartial: ((String) -> Unit)? = null): String =
            error("On-device AI is not available in the lite build")

        @Suppress("UNUSED_PARAMETER")
        fun seed(transcript: List<ChatReplay.Turn>) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun recordStoppedTurn(user: String, fullAnswer: String) = Unit

        fun close() = Unit
    }

    fun release() = Unit
}
