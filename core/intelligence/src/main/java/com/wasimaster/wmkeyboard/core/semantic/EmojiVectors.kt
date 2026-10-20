package com.wasimaster.wmkeyboard.core.semantic

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * The emoji catalogue's vectors, embedded once off the device
 * (`scripts/embed_emoji_catalog.py` in the data repository) and downloaded
 * with the text model: some 1,700 names would otherwise keep a phone's CPU
 * busy for ten minutes. Read into a [VectorIndex] keyed by the emoji.
 *
 * `WMEV`, int32 version, int32 count, int32 width, the emoji as length-
 * prefixed UTF-8, then the vectors as float16. Little-endian.
 */
object EmojiVectors {

    private const val MAGIC = 0x56454D57 // "WMEV" read little-endian

    /** The index, or an empty one when [file] is missing or not this format. */
    fun read(file: File): VectorIndex {
        val index = VectorIndex()
        if (!file.isFile) return index
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (le32(input) != MAGIC || le32(input) != 1) return index
                val count = le32(input)
                val width = le32(input)
                if (width != index.dimensions || count !in 0..100_000) return index
                val keys = Array(count) {
                    val size = le16(input)
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                }
                val vector = FloatArray(width)
                for (key in keys) {
                    for (k in 0 until width) vector[k] = halfToFloat(le16(input))
                    index.put(key, 0L, vector)
                }
            }
        } catch (_: EOFException) {
            index.clear()
        } catch (_: IOException) {
            index.clear()
        }
        return index
    }

    private fun le32(input: DataInputStream): Int = Integer.reverseBytes(input.readInt())

    private fun le16(input: DataInputStream): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return (high shl 8) or low
    }

    /** IEEE 754 binary16 to float, without android.util.Half (API 26). */
    internal fun halfToFloat(bits: Int): Float {
        val sign = (bits ushr 15) and 1
        val exponent = (bits ushr 10) and 0x1F
        val mantissa = bits and 0x3FF
        val value = when (exponent) {
            0 -> mantissa / 1024f * POW_MINUS_14
            0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> Float.fromBits(((exponent - 15 + 127) shl 23) or (mantissa shl 13))
        }
        return if (sign == 1) -value else value
    }

    private const val POW_MINUS_14 = 1f / 16384f
}
