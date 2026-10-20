package com.wasimaster.wmkeyboard.core.localllm

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import java.io.File

/**
 * JNI surface of llama.cpp (libwmllama.so, built from native/llamacpp-jni),
 * the second on-device runtime beside LiteRT-LM: it runs GGUF files, any of
 * them, where LiteRT-LM runs only `.litertlm` and `.task` bundles.
 *
 * Optional at runtime: GitHub builds fetch the library the first time a GGUF
 * model is used, Play builds carry it in the on-demand `:feature:llama` split,
 * and a build or install without it reads [available] as false.
 *
 * Handles are native pointers. A model handle may be shared by sessions; a
 * session handle must not be used from two threads at once, except
 * [nativeAbort]. [LlamaCppRuntime] keeps to both. Text crosses as UTF-8 bytes;
 * see native/llamacpp-jni/README.md for the whole contract.
 */
internal object LlamaNative {

    const val LIBRARY = "wmllama"
    const val LIBRARY_V82 = "wmllama_v82"

    /** [nativeStopReason]: the model ended its answer. */
    const val STOP_END = 0
    const val STOP_CONTEXT_FULL = 1
    const val STOP_ABORTED = 2
    const val STOP_ERROR = 3

    /** Negative results of [nativePromptChat] and [nativePromptRaw]. */
    const val PROMPT_TOO_LONG = -1
    const val PROMPT_ABORTED = -2
    const val PROMPT_DECODE_FAILED = -3
    const val PROMPT_TEMPLATE_FAILED = -4

    private val library = GatedLibrary({ FeatureModules.llama }, ::load)

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    /**
     * The ARMv8.2 build (dot product, fp16) where every core has those
     * instructions, else the baseline one. Same rule as WhisperCpp: a
     * big.LITTLE part with one older core would die on an illegal
     * instruction the moment a thread landed there.
     */
    private fun load(): Boolean {
        if (hasArmV82Features() && runCatching { FeatureModules.loadLibrary(LIBRARY_V82) }.isSuccess) return true
        return runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    private fun hasArmV82Features(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .map { it.substringAfter(':').trim().split(' ').toSet() }
        features.isNotEmpty() && features.all { it.containsAll(listOf("asimddp", "asimdhp", "fphp")) }
    }.getOrDefault(false)

    /** llama.cpp's own line about the instruction sets it was built with. */
    external fun nativeSystemInfo(): String

    /** A model over [path] (mmapped when [useMmap]), or 0 when it will not load. */
    external fun nativeModelOpen(path: String, useMmap: Boolean): Long

    external fun nativeModelClose(model: Long)

    /**
     * `[params, bytes, trainedContext, layers, heads, kvHeads, embedding,
     * slidingWindow, hasTemplate, vocabulary]`; see [LlamaModelInfo].
     */
    external fun nativeModelInfo(model: Long): LongArray

    /** llama.cpp's one-line description of the model, UTF-8. */
    external fun nativeModelDescription(model: Long): ByteArray

    /** A context of [contextTokens] over [model], or 0 when it cannot be allocated. */
    external fun nativeSessionOpen(model: Long, contextTokens: Int, threads: Int, batch: Int): Long

    external fun nativeSessionClose(session: Long)

    /** Temperature 0 is greedy; top-k 0, top-p 1 and min-p 0 are off; a negative seed is random. */
    external fun nativeSetSampler(session: Long, temperature: Float, topK: Int, topP: Float, minP: Float, seed: Int)

    /**
     * Renders [roles] and [contents] (UTF-8) with the model's chat template,
     * ending on the assistant's turn, and loads it, reusing whatever prefix
     * the session already holds. The prompt's length in tokens, or one of the
     * `PROMPT_` codes.
     */
    external fun nativePromptChat(session: Long, roles: Array<String>, contents: Array<ByteArray>, enableThinking: Boolean): Int

    /** [nativePromptChat] for raw text with no template. */
    external fun nativePromptRaw(session: Long, text: ByteArray, addSpecial: Boolean): Int

    /** How many tokens the last prompt kept from the session's cache. */
    external fun nativeLastReused(session: Long): Int

    /**
     * The next piece of the answer as UTF-8, possibly empty while a character
     * is incomplete; null once it is over, for the reason [nativeStopReason]
     * gives.
     */
    external fun nativeNext(session: Long): ByteArray?

    external fun nativeStopReason(session: Long): Int

    /** Stops a prompt load or an answer at its next step. Any thread. */
    external fun nativeAbort(session: Long)

    external fun nativeContextSize(session: Long): Int

    external fun nativeTokensUsed(session: Long): Int

    external fun nativeTokenize(model: Long, text: ByteArray, addSpecial: Boolean): IntArray

    external fun nativeTokenPiece(model: Long, token: Int): ByteArray

    /**
     * Each of [tokens]' log-probabilities given those before it, in nats;
     * element 0 is NaN. Replaces what the session's cache held. Null on
     * failure or when aborted.
     */
    external fun nativeScore(session: Long, tokens: IntArray): FloatArray?
}

/** What [LlamaNative.nativeModelInfo] reports, named. */
internal data class LlamaModelInfo(
    val params: Long,
    val bytes: Long,
    val trainedContext: Int,
    val layers: Int,
    val heads: Int,
    val kvHeads: Int,
    val embedding: Int,
    val slidingWindow: Int,
    val hasTemplate: Boolean,
    val vocabulary: Int,
) {
    /**
     * Bytes of KV cache one token of context costs: keys and values, f16, for
     * every layer. An upper bound for sliding-window models, whose windowed
     * layers keep fewer.
     */
    val kvBytesPerToken: Long
        get() {
            if (heads <= 0) return 0
            val headSize = embedding / heads
            return layers.toLong() * 2 * kvHeads * headSize * 2
        }

    companion object {
        fun of(values: LongArray) = LlamaModelInfo(
            params = values[0],
            bytes = values[1],
            trainedContext = values[2].toInt(),
            layers = values[3].toInt(),
            heads = values[4].toInt(),
            kvHeads = values[5].toInt(),
            embedding = values[6].toInt(),
            slidingWindow = values[7].toInt(),
            hasTemplate = values[8] != 0L,
            vocabulary = values[9].toInt(),
        )
    }
}
