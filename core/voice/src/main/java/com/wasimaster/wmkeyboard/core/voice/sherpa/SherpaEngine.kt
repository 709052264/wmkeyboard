package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import java.io.File
import java.util.Locale

/**
 * Offline Sherpa dictation: a streaming Zipformer transducer per language,
 * decoded while the user speaks, so the words reach the field as they are
 * said. Silero VAD, carried beside every model, hears the end of a phrase
 * sooner and more surely than a loudness threshold, and the English
 * punctuation model, once downloaded, punctuates and capitalises the finished
 * phrase.
 *
 * One model stays open between dictations, since opening one takes about as
 * long as a short phrase does to say, until another takes its place. Every
 * call here runs on [SherpaNative.dispatcher].
 */
object SherpaEngine {

    /** Decoder threads: two keeps up with speech on any phone that has two cores to spare. */
    private const val THREADS = 2

    /** Silero's speech threshold; its own default. */
    private const val VAD_THRESHOLD = 0.5f

    /** Shorter than this is a click or a cough, not speech. */
    private const val VAD_MIN_SPEECH_SECONDS = 0.25f

    /** The pause that ends a phrase when the user has not set one. */
    private const val DEFAULT_ENDPOINT_MS = 1_200

    private var recognizer = 0L
    private var recognizerDir: String? = null
    private var punctuation = 0L
    private var punctuationDir: String? = null

    /**
     * Streams not yet closed. Each one uses the recognizer and punctuation
     * handles, so [release] leaves them alone until this is back to 0: a
     * memory trim between the microphone closing and the phrase finishing
     * must not free what the finish is about to read.
     */
    private var liveStreams = 0

    /** Whether a model is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /** Opens [model] ahead of a phrase. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        recognizerFor(filesDir, model)
    }

    /**
     * A fresh phrase on [model]. [endpointMs] is the pause that ends it, or 0
     * for none (the user stops it). Throws when the model will not load.
     */
    fun newStream(filesDir: File, model: EtModel, endpointMs: Int): SherpaStream {
        val rec = recognizerFor(filesDir, model)
        val stream = SherpaNative.nativeStream(rec)
        check(stream != 0L) { "sherpa-onnx could not start a stream" }
        val vadFile = EtStore.file(filesDir, model, "silero_vad.onnx")
        val vad = if (endpointMs > 0 && vadFile.isFile) {
            SherpaNative.nativeVad(vadFile.path, VAD_THRESHOLD, endpointMs / 1000f, VAD_MIN_SPEECH_SECONDS)
        } else {
            0L
        }
        liveStreams++
        return SherpaStream(rec, stream, vad, punctuationFor(filesDir, model), endpointMs > 0) { liveStreams-- }
    }

    /** Closes whatever is open, unless a stream still uses it. On [SherpaNative.dispatcher] only. */
    fun release() {
        if (liveStreams > 0) return
        if (recognizer != 0L) SherpaNative.nativeRecognizerFree(recognizer)
        if (punctuation != 0L) SherpaNative.nativePunctuationFree(punctuation)
        recognizer = 0L
        recognizerDir = null
        punctuation = 0L
        punctuationDir = null
        isOpen = false
    }

    private fun recognizerFor(filesDir: File, model: EtModel): Long {
        require(model.engine == EtEngine.SHERPA && !model.punctuation)
        val dir = EtStore.dir(filesDir, model)
        if (recognizer != 0L && recognizerDir == dir.path) return recognizer
        check(SherpaNative.available) {
            if (SherpaNative.moduleReady) "ONNX Runtime is not in this build"
            else "ONNX Runtime is still downloading"
        }
        // Another model's phrase still open would be left pointing at a freed
        // recognizer; the service closes one before it starts the next.
        check(liveStreams == 0) { "a Sherpa phrase is still open" }
        if (recognizer != 0L) SherpaNative.nativeRecognizerFree(recognizer)
        recognizer = 0L
        recognizerDir = null
        // Omnilingual's gigabyte goes back first.
        OmnilingualEngine.release()
        fun part(prefix: String) = model.files.first { it.name.startsWith(prefix) }.let { EtStore.file(filesDir, model, it) }.path
        val handle = SherpaNative.nativeRecognizer(
            part("encoder"),
            part("decoder"),
            part("joiner"),
            part("tokens"),
            THREADS,
            DEFAULT_ENDPOINT_MS / 1000f,
        )
        check(handle != 0L) { "${model.label} did not load" }
        recognizer = handle
        recognizerDir = dir.path
        isOpen = true
        return handle
    }

    /** The punctuation model, for an English [model] when it is downloaded; else 0. */
    private fun punctuationFor(filesDir: File, model: EtModel): Long {
        val punct = EtCatalog.sherpaPunctuation
        if (!model.supports("en") || model.supports("zh")) return 0L
        if (!EtStore.isDownloaded(filesDir, punct)) return 0L
        val dir = EtStore.dir(filesDir, punct)
        if (punctuation != 0L && punctuationDir == dir.path) return punctuation
        if (punctuation != 0L) SherpaNative.nativePunctuationFree(punctuation)
        punctuation = SherpaNative.nativePunctuation(
            EtStore.file(filesDir, punct, "model.int8.onnx").path,
            EtStore.file(filesDir, punct, "bpe.vocab").path,
        )
        punctuationDir = if (punctuation != 0L) dir.path else null
        return punctuation
    }
}

/**
 * One phrase through a Sherpa recognizer. Its recognizer and punctuation
 * model belong to [SherpaEngine] and outlive it; its stream and VAD are its
 * own, freed by [close], which [finish] calls.
 */
class SherpaStream internal constructor(
    private val recognizer: Long,
    private var stream: Long,
    private var vad: Long,
    private val punctuation: Long,
    private val endpoints: Boolean,
    private val onClose: () -> Unit,
) : VoiceStream, AutoCloseable {

    private var heardSpeech = false
    private var speaking = false

    override fun feed(chunk: FloatArray, count: Int) {
        if (stream == 0L || count <= 0) return
        // JNI copies the array whole; a slice keeps a short count from
        // dragging the rest of a large one across.
        val samples = if (count == chunk.size) chunk else chunk.copyOf(count)
        SherpaNative.nativeFeed(recognizer, stream, samples, count)
        if (vad != 0L) {
            speaking = SherpaNative.nativeVadFeed(vad, samples, count)
            if (speaking) heardSpeech = true
        }
    }

    override fun text(): String = if (stream == 0L) "" else readable(SherpaNative.nativeText(recognizer, stream))

    override fun finish(): String {
        if (stream == 0L) return ""
        SherpaNative.nativeFinish(recognizer, stream)
        val raw = readable(SherpaNative.nativeText(recognizer, stream))
        close()
        if (raw.isEmpty() || punctuation == 0L) return raw
        return String(SherpaNative.nativePunctuate(punctuation, raw.toByteArray(Charsets.UTF_8)), Charsets.UTF_8).trim()
    }

    /**
     * Silero heard speech and then the pause; without Silero, the
     * recognizer's own rule (words, then silence) decides.
     */
    override val endpoint: Boolean
        get() = endpoints && stream != 0L && when {
            vad != 0L -> heardSpeech && !speaking
            else -> SherpaNative.nativeEndpoint(recognizer, stream)
        }

    override fun close() {
        if (stream == 0L) return
        SherpaNative.nativeStreamFree(stream)
        if (vad != 0L) SherpaNative.nativeVadFree(vad)
        stream = 0L
        vad = 0L
        onClose()
    }

    private companion object {
        /**
         * The models trained on audiobooks spell in capitals ("HELLO
         * WORLD"); the keyboard's casing, or the punctuation model, decides
         * the case instead. Text with any small letter is left alone.
         */
        fun readable(bytes: ByteArray): String {
            val text = String(bytes, Charsets.UTF_8).trim()
            val shouting = text.any { it.isUpperCase() } && text.none { it.isLowerCase() }
            return if (shouting) text.lowercase(Locale.ROOT) else text
        }
    }
}
