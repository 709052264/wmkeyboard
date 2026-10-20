package com.wasimaster.wmkeyboard.core.voice

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures microphone audio for offline Whisper: 16 kHz mono PCM into a fixed
 * float buffer, 30 seconds (Whisper's window) unless told otherwise. Reports a smoothed input level for
 * the panel's pulse ring and fires [onMaxReached] when the buffer fills so the
 * service can transcribe and, in continuous mode, start the next utterance.
 * [onLost] fires instead when the microphone stops delivering mid-clip — the
 * audio server died, or the capture was torn down under it — and what was
 * recorded up to then is still there for [stop] to return.
 *
 * The caller must hold RECORD_AUDIO before [start]. [stop] is idempotent and
 * returns exactly the samples captured so far; it blocks briefly while the
 * reader thread winds down, so call it off the main thread.
 */
class WhisperRecorder(
    private val onLevel: (Float) -> Unit,
    private val onMaxReached: () -> Unit,
    private val onLost: () -> Unit = {},
    /**
     * How long the microphone may hear nothing, after it has heard speech,
     * before the clip ends by itself (#500). 0, the default, records until
     * [stop] or the window filling, as before. Measured in captured samples
     * rather than wall time, so a stalled reader cannot end a clip early.
     */
    private val silenceStopMs: Int = 0,
    /** The clip ended on a pause; the samples so far are there for [stop]. */
    private val onSilence: () -> Unit = {},
    /**
     * The microphone to record from (#581), or null for the one Android picks.
     * A preference, not a demand: if it goes away mid-clip Android carries on
     * with its own choice rather than ending the capture.
     */
    private val preferredDevice: AudioDeviceInfo? = null,
    /**
     * How long one clip may run. Whisper's window is 30 seconds, and a LiteRT
     * graph reads that and no more; whisper.cpp walks a longer clip window by
     * window, so its models get more room before [onMaxReached].
     */
    maxSeconds: Int = WhisperMel.CHUNK_SECONDS,
    /**
     * Noise reduction in front of whatever hears the clip ([VoiceDenoise]), or
     * null to keep the microphone's own audio. Owned from here on: the reader
     * thread uses it, adds what it still holds to the clip when the capture
     * ends, and closes it. The pulse ring still follows the raw microphone,
     * so it shows that the microphone hears; the silence stop and the clip
     * get the cleaned audio.
     */
    private val denoise: VoiceDenoise.Stream? = null,
) {
    private val maxSamples = WhisperMel.SAMPLE_RATE * maxSeconds
    private val buffer = FloatArray(maxSamples)

    @Volatile private var count = 0
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRecording: Boolean get() = running

    /** The capture's audio session, for [MicBlockWatcher]; 0 when not recording. */
    val audioSessionId: Int get() = record?.audioSessionId ?: 0

    /** The microphone the capture is actually coming from, once it has started; null before or if Android won't say. */
    val routedDevice: AudioDeviceInfo? get() = runCatching { record?.routedDevice }.getOrNull()

    /** How much has been captured so far, in samples. */
    val sampleCount: Int get() = count

    /**
     * A copy of the samples captured from [from] up to [to] (clamped to what is
     * there), for an engine that decodes while the clip is still recording.
     * Safe from any thread: samples below [sampleCount] are never written again.
     */
    fun samples(from: Int, to: Int): FloatArray = buffer.copyOfRange(from, minOf(to, count).coerceAtLeast(from))

    /** Whole seconds of room left in the clip, rounded up: 1 until the very end. */
    val secondsLeft: Int
        get() = (maxSamples - count + WhisperMel.SAMPLE_RATE - 1) / WhisperMel.SAMPLE_RATE

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            WhisperMel.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return failed()
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WhisperMel.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (_: Throwable) {
            return failed()
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return failed()
        }
        if (preferredDevice != null) runCatching { r.setPreferredDevice(preferredDevice) }
        record = r
        count = 0
        running = true
        runCatching { r.startRecording() }.onFailure {
            running = false
            runCatching { r.release() }
            record = null
            return failed()
        }
        thread = Thread { readLoop(r) }.apply { start() }
        return true
    }

    /** A capture that never started: the denoiser it was handed is done with too. */
    private fun failed(): Boolean {
        denoise?.close()
        return false
    }

    private fun readLoop(r: AudioRecord) {
        try {
            capture(r)
        } finally {
            denoise?.close()
        }
    }

    /**
     * The denoiser's last samples (DPDFNet trails the microphone by 40 ms) onto
     * the end of the clip, as far as there is room. Runs on the reader thread
     * once capture has stopped and before anything hears that it ended, so
     * [stop] and the end-of-clip callbacks see the whole clip.
     */
    private fun flushDenoiser() {
        val tail = runCatching { denoise?.finish() }.getOrNull() ?: return
        val room = min(tail.size, maxSamples - count)
        if (room <= 0) return
        tail.copyInto(buffer, count, 0, room)
        count += room
    }

    private fun capture(r: AudioRecord) {
        // A reader that loses the CPU to background work drops samples once
        // the capture buffer overruns, and a gap in the audio is a wrong word.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val chunk = ShortArray(1600) // ~100 ms
        val raw = FloatArray(chunk.size)
        // Silence stop (#500): armed by the first chunk loud enough to be
        // speech, so the quiet before the user starts talking never ends the
        // clip; then a run of quiet chunks of the asked length does.
        val quietLimit = silenceStopMs.toLong() * WhisperMel.SAMPLE_RATE / 1000
        var heard = false
        var quiet = 0L
        while (running) {
            val n = r.read(chunk, 0, chunk.size)
            if (n < 0) {
                // An error code, not a short read, and it does not clear by
                // itself: asking again returns at once with the same answer,
                // so carrying on would spin here until somebody called stop.
                running = false
                flushDenoiser()
                onLost()
                return
            }
            if (n == 0) continue
            var rawSq = 0.0
            for (i in 0 until n) {
                val s = chunk[i] / 32768f
                raw[i] = s
                rawSq += (s * s).toDouble()
            }
            onLevel((sqrt(rawSq / n).toFloat() * 6f).coerceIn(0f, 1f))
            // Denoised audio comes back in whole 10 ms blocks, a moment
            // behind; a call can hand back nothing yet.
            val clean = denoise?.process(raw, n) ?: raw
            val got = if (denoise != null) clean.size else n
            var sumSq = 0.0
            val room = min(got, maxSamples - count)
            for (i in 0 until room) {
                val s = clean[i]
                buffer[count + i] = s
                sumSq += (s * s).toDouble()
            }
            count += room
            if (room > 0 && quietLimit > 0) {
                val rms = sqrt(sumSq / room).toFloat()
                if (rms >= SPEECH_RMS) {
                    heard = true
                    quiet = 0
                } else if (heard) {
                    quiet += room
                    if (quiet >= quietLimit) {
                        running = false
                        flushDenoiser()
                        onSilence()
                        return
                    }
                }
            }
            if (count >= maxSamples) {
                running = false
                onMaxReached()
                return
            }
        }
        // Stopped from outside: [stop] waits for this before it copies the clip.
        flushDenoiser()
    }

    private companion object {
        /**
         * The RMS a 100 ms chunk must reach to count as speech for the
         * silence stop. Speech into a phone held normally reads 0.03 and up
         * on this scale; a quiet room's floor is under 0.005, and the pulse
         * ring draws this as a tenth of its height. Fixed rather than adapted
         * to the room: a floor that learns a fan's hum would also learn a
         * speaker who trails off.
         */
        const val SPEECH_RMS = 0.015f
    }

    /** Stops capture and returns the samples recorded so far. Idempotent. */
    fun stop(): FloatArray {
        running = false
        thread?.let { runCatching { it.join(600) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        return buffer.copyOf(count)
    }
}
