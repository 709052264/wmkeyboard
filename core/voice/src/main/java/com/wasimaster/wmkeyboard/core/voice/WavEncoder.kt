package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs [WhisperRecorder]'s float samples into a 16-bit PCM WAV file, the one
 * audio format every transcription server accepts without a decoder of its own
 * (speaches, whisper.cpp's server, OpenAI, Groq). A full 30-second clip at
 * 16 kHz mono is just under a megabyte; [OpusEncoder] makes the same clip
 * about 75 KB, and this is what a server that cannot read that gets.
 */
object WavEncoder {

    private const val HEADER_BYTES = 44

    /** The size of the file [encode] makes from [count] samples. */
    fun sizeOf(count: Int): Long = HEADER_BYTES + count * 2L

    fun encode(samples: FloatArray, sampleRate: Int = WhisperMel.SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataBytes)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16) // PCM fmt chunk size
        out.putShort(1) // PCM
        out.putShort(1) // mono
        out.putInt(sampleRate)
        out.putInt(sampleRate * 2) // byte rate
        out.putShort(2) // block align
        out.putShort(16) // bits per sample
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataBytes)
        for (s in samples) {
            out.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        return out.array()
    }
}
