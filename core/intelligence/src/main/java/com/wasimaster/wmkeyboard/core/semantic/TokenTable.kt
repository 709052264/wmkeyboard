package com.wasimaster.wmkeyboard.core.semantic

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * EmbeddingGemma 2's token embeddings, read straight from
 * `eg2-tokens.bin` (see [SemanticModels]) with no interpreter: one row per
 * token, 512 signed int4 values two to a byte, low nibble first, times the
 * row's scale and the model's multiplier (√512). That is the whole of the
 * bundle's embedder graph, and it reproduces the graph's output exactly.
 *
 * The file is mapped, not read: a text touches a few dozen of its 262,144
 * rows, and the rest of the 68 MB never has to leave the disk.
 */
class TokenTable private constructor(
    private val file: RandomAccessFile,
    private val buffer: ByteBuffer,
    val vocabulary: Int,
    val width: Int,
    private val multiplier: Float,
) : Closeable {

    private val rowBytes = width / 2
    private val tableStart = HEADER_BYTES + vocabulary * Float.SIZE_BYTES

    /** Writes token [id]'s embedding into [out] from [offset]. Ids outside the vocabulary read as row 0, as the graph's do. */
    fun row(id: Int, out: FloatArray, offset: Int) {
        val token = if (id in 0 until vocabulary) id else 0
        val scale = buffer.getFloat(HEADER_BYTES + token * Float.SIZE_BYTES) * multiplier
        var at = tableStart + token * rowBytes
        var o = offset
        repeat(rowBytes) {
            val b = buffer.get(at++).toInt()
            out[o++] = signed4(b and 0x0F) * scale
            out[o++] = signed4((b ushr 4) and 0x0F) * scale
        }
    }

    override fun close() = file.close()

    companion object {
        private const val HEADER_BYTES = 16
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())

        private fun signed4(nibble: Int): Int = if (nibble > 7) nibble - 16 else nibble

        fun open(file: File): TokenTable {
            val raf = RandomAccessFile(file, "r")
            try {
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
                for (k in MAGIC.indices) require(map.get(k) == MAGIC[k]) { "not a token table" }
                val vocabulary = map.getInt(4)
                val width = map.getInt(8)
                val multiplier = map.getFloat(12)
                require(width % 2 == 0 && raf.length() == HEADER_BYTES + vocabulary * 4L + vocabulary.toLong() * width / 2) {
                    "token table is ${raf.length()} bytes, not what its header says"
                }
                return TokenTable(raf, map, vocabulary, width, multiplier)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }
    }
}
