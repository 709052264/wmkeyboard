package com.wasimaster.wmkeyboard.core.voice.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speech out: reads text aloud with a downloaded MMS voice, offline. The
 * speaker buttons and Read back ask it first and fall back to the phone's own
 * synthesiser when it has no voice for the language ([speak] returns false).
 *
 * Sentences are synthesised one after another and played as each is ready, so
 * the first is heard while the rest are still being made. One instance per
 * owner; [release] when the owner goes.
 */
class SpeechOut(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    /** The downloaded voice that reads [locale], or null when there is none or nothing to run it. */
    fun voiceFor(locale: Locale): MmsVoice? {
        if (!NativeConsent.has(NativeConsent.MMS) || !MmsSpeech.runtimeShipped) return null
        val voice = MmsVoices.forLanguage(appContext, locale.toLanguageTag()) ?: return null
        return voice.takeIf { MmsVoices.isDownloaded(appContext.filesDir, it) }
    }

    /**
     * Reads [text] in [locale] at [rate] (1 = the voice's pace), stopping
     * whatever this instance was reading. False, with nothing started, when
     * no downloaded voice reads [locale]. [onDone] fires once, on a
     * background thread, when the reading ends, fails or is stopped.
     */
    fun speak(text: String, locale: Locale, rate: Float, onDone: (() -> Unit)? = null): Boolean {
        val voice = voiceFor(locale) ?: return false
        stop()
        val pieces = MmsText.pieces(text)
        if (pieces.isEmpty()) {
            onDone?.invoke()
            return true
        }
        job = scope.launch {
            try {
                play(voice, pieces, locale, rate)
            } finally {
                closeTrack()
                onDone?.invoke()
            }
        }
        return true
    }

    private suspend fun play(voice: MmsVoice, pieces: List<String>, locale: Locale, rate: Float) {
        val filesDir = appContext.filesDir
        var written = 0L
        var out: AudioTrack? = null
        for (piece in pieces) {
            val samples = withContext(MmsSpeech.dispatcher) {
                MmsSpeech.synthesize(filesDir, voice, piece, locale, rate)
            } ?: continue
            if (!currentCoroutineContext().isActive) return
            val sink = out ?: openTrack(MmsSpeech.sampleRate)?.also { out = it } ?: return
            // A short breath between sentences; VITS ends each one abruptly.
            val gap = if (written > 0) FloatArray(MmsSpeech.sampleRate * GAP_MS / 1000) else FloatArray(0)
            if (gap.isNotEmpty()) written += write(sink, gap)
            written += write(sink, samples)
        }
        val sink = out ?: return
        // Wait for the last samples to leave the speaker before saying done.
        while (currentCoroutineContext().isActive && (sink.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written) {
            delay(POLL_MS)
        }
    }

    private fun write(sink: AudioTrack, samples: FloatArray): Long {
        var at = 0
        while (at < samples.size) {
            val n = sink.write(samples, at, samples.size - at, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            at += n
        }
        return at.toLong()
    }

    private fun openTrack(sampleRate: Int): AudioTrack? = runCatching {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.play()
                track = it
            }
    }.getOrNull()

    private fun closeTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    /** Stops the reading in progress, if any; its [speak] callback still fires. */
    fun stop() {
        job?.cancel()
        job = null
        // Unblocks a write waiting on a full buffer, so the job can end now.
        track?.let { runCatching { it.pause() } }
    }

    /** Stops and frees this instance's player. The open voice stays for the next owner. */
    fun release() {
        stop()
        closeTrack()
    }

    private companion object {
        const val GAP_MS = 180
        const val POLL_MS = 40L
    }
}
