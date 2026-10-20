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
 * Microsoft's ONNX Runtime. Two libraries, both behind the `onnx` gate
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

    // Recognizer: a streaming transducer. Paths are the model's files; 0 when
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

    @JvmStatic external fun nativeRecognizerFree(recognizer: Long)

    @JvmStatic external fun nativeStream(recognizer: Long): Long

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

    // Online punctuation: CNN-BiLSTM over BPE pieces, punctuation and capitals.
    @JvmStatic external fun nativePunctuation(model: String, vocab: String): Long

    @JvmStatic external fun nativePunctuationFree(punctuation: Long)

    /** [text] (UTF-8) punctuated and cased, UTF-8. */
    @JvmStatic external fun nativePunctuate(punctuation: Long, text: ByteArray): ByteArray
}
