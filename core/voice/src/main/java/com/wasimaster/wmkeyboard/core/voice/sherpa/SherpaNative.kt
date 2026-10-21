package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/**
 * sherpa-onnx, through `native/sherpa-onnx-jni`: k2-fsa's speech toolkit over
 * Microsoft's ONNX Runtime, plus the VITS runner that reads text aloud with
 * Meta's MMS voices ([com.wasimaster.wmkeyboard.core.voice.speech.MmsSpeech])
 * and the token classifier that punctuates dictation. Two libraries, both behind the `onnx` gate
 * (`:feature:onnx` on Play): `libonnxruntime.so`, Microsoft's own build from
 * Maven Central, and `libwmsherpa.so`, this project's bridge, which names the
 * runtime as a dependency and so must come second. The runtime is about
 * 33 MB on arm64; nothing fetches it until someone picks the Sherpa engine.
 *
 * Handles are plain longs, freed by their owners. Nothing here is
 * thread-safe, so every call runs on [dispatcher], one thread.
 */
object SherpaNative {

    private val library = GatedLibrary({ FeatureModules.onnx }) {
        runCatching {
            FeatureModules.loadLibrary("onnxruntime")
            FeatureModules.loadLibrary("wmsherpa")
        }.isSuccess
    }

    /** Whether both libraries are loaded on this device and build. */
    val available: Boolean get() = library.available

    /** Whether this build has them, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** Where the `onnx` module stands, for the settings screen's banner. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.onnx.state

    /** Whether the module is here; true wherever the APK carries the libraries. */
    val moduleReady: Boolean get() = FeatureModules.onnx.installed

    /** Asks for the module. Idempotent. */
    fun requestModule() = FeatureModules.onnx.requestInstall()

    /** The one thread every handle is used on. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    // Streaming recognizer: a transducer. Paths are the model's files; 0 when
    // they will not load. [trailingSilence] is the pause, in seconds, after
    // which the recognizer's own endpoint fires.
    @JvmStatic external fun nativeRecognizer(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        threads: Int,
        trailingSilence: Float,
    ): Long

    /**
     * The same transducer by modified beam search, the only search that
     * follows a stream's hotwords ([nativeHotwordStream]). [modelingUnit] is
     * "bpe" or "cjkchar+bpe", nothing else (sherpa-onnx exits on any other);
     * hotwords are cut into pieces over [bpeVocab] ([SherpaHotwords.vocab]),
     * each matching piece worth [hotwordsScore]. Not in a bridge built before
     * hotwords: that one throws [UnsatisfiedLinkError].
     */
    @JvmStatic external fun nativeHotwordRecognizer(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        threads: Int,
        trailingSilence: Float,
        modelingUnit: String,
        bpeVocab: String,
        hotwordsScore: Float,
    ): Long

    @JvmStatic external fun nativeRecognizerFree(recognizer: Long)

    @JvmStatic external fun nativeStream(recognizer: Long): Long

    /** A stream listening for [hotwords]: UTF-8, one phrase a line ([SherpaHotwords.lines]). */
    @JvmStatic external fun nativeHotwordStream(recognizer: Long, hotwords: ByteArray): Long

    @JvmStatic external fun nativeStreamFree(stream: Long)

    @JvmStatic external fun nativeFeed(recognizer: Long, stream: Long, samples: FloatArray, count: Int)

    @JvmStatic external fun nativeFinish(recognizer: Long, stream: Long)

    /** The transcript so far, UTF-8. */
    @JvmStatic external fun nativeText(recognizer: Long, stream: Long): ByteArray

    @JvmStatic external fun nativeEndpoint(recognizer: Long, stream: Long): Boolean

    // Silero VAD. Feeding returns whether speech is going on now.
    @JvmStatic external fun nativeVad(model: String, threshold: Float, minSilence: Float, minSpeech: Float): Long

    @JvmStatic external fun nativeVadFree(vad: Long)

    @JvmStatic external fun nativeVadFeed(vad: Long, samples: FloatArray, count: Int): Boolean

    @JvmStatic external fun nativeVadReset(vad: Long)

    // Silero as a segmenter: it keeps each phrase between pauses of at least
    // [minSilence] seconds, and past [maxSpeech] seconds of talk it settles
    // for a much shorter pause. Fed with [nativeVadFeed]; the phrases come out
    // oldest first, null when there is none.
    @JvmStatic external fun nativeSegmenter(
        model: String,
        threshold: Float,
        minSilence: Float,
        minSpeech: Float,
        maxSpeech: Float,
    ): Long

    @JvmStatic external fun nativeVadSegment(vad: Long): FloatArray?

    /** The clip is over: speech still going on becomes a last phrase. */
    @JvmStatic external fun nativeVadFlush(vad: Long)

    // Meta's Omnilingual ASR CTC model on ONNX Runtime, a whole phrase at a time.
    @JvmStatic external fun nativeOmnilingual(model: String, tokens: String, threads: Int): Long

    @JvmStatic external fun nativeOmnilingualFree(model: Long)

    /**
     * The first [count] samples of a phrase (16 kHz mono, in [-1, 1]) as text,
     * UTF-8. [allowed] flags the token ids that may come out (see
     * [OmnilingualScriptMask]); null lets the model write any of them.
     */
    @JvmStatic external fun nativeOmnilingualDecode(
        model: Long,
        samples: FloatArray,
        count: Int,
        allowed: BooleanArray?,
    ): ByteArray

    // Online punctuation: CNN-BiLSTM over BPE pieces, punctuation and capitals.
    @JvmStatic external fun nativePunctuation(model: String, vocab: String): Long

    @JvmStatic external fun nativePunctuationFree(punctuation: Long)

    /** [text] (UTF-8) punctuated and cased, UTF-8. */
    @JvmStatic external fun nativePunctuate(punctuation: Long, text: ByteArray): ByteArray

    // A token classifier on ONNX Runtime: `input_ids` (and an all-ones
    // `attention_mask` when the model takes one) in, a label per token out.
    // Dictation's multilingual punctuation model
    // ([com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation]).
    @JvmStatic external fun nativeTagger(model: String, threads: Int): Long

    @JvmStatic external fun nativeTaggerFree(tagger: Long)

    /** The label (argmax) of each of the first [count] of [ids], specials included; null when the run fails. */
    @JvmStatic external fun nativeTag(tagger: Long, ids: IntArray, count: Int): IntArray?

    // Speech out: one VITS voice (Meta's MMS-TTS). The text is turned into
    // token ids on the Kotlin side; this runs the network.
    @JvmStatic external fun nativeVits(model: String, threads: Int): Long

    @JvmStatic external fun nativeVitsFree(vits: Long)

    /** The model's metadata entry [key] (`sample_rate`, `add_blank`), UTF-8; empty when absent. */
    @JvmStatic external fun nativeVitsMeta(vits: Long, key: String): ByteArray

    /**
     * One sentence's samples in [-1, 1] at the model's rate, or null when the
     * run fails. The scales are VITS's own: sampling noise, duration (1 / speed)
     * and duration noise.
     */
    @JvmStatic external fun nativeVitsRun(
        vits: Long,
        ids: LongArray,
        noiseScale: Float,
        lengthScale: Float,
        noiseScaleW: Float,
    ): FloatArray?

    // Speech enhancement: a streaming DPDFNet denoiser (the Strong level of
    // Reduce background noise, [com.wasimaster.wmkeyboard.core.voice.StrongDenoise]).
    // A handle belongs to the one capture thread using it, not [dispatcher];
    // it shares nothing with the recognizer. Output trails input by [delay]
    // samples, which the bridge drops at the start and brings out at
    // [nativeDenoiseFinish]; [keep] of the input is mixed back under it.
    @JvmStatic external fun nativeDenoiser(model: String, threads: Int, delay: Int, keep: Float): Long

    @JvmStatic external fun nativeDenoiserFree(denoiser: Long)

    /** [count] samples of [samples] (16 kHz, [-1, 1]) in; what is ready out, denoised. */
    @JvmStatic external fun nativeDenoise(denoiser: Long, samples: FloatArray, count: Int): FloatArray

    /** The clip's last samples, and the denoiser reset for the next clip. */
    @JvmStatic external fun nativeDenoiseFinish(denoiser: Long): FloatArray
}
