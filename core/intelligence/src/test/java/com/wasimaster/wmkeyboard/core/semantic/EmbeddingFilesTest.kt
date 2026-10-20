package com.wasimaster.wmkeyboard.core.semantic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The two file formats the repackaging script writes, read back here. */
class EmbeddingFilesTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun tokenRowsAreSignedNibblesLowFirstTimesScaleAndMultiplier() {
        // Vocabulary 2, width 4: row 0 = [1, -1, 7, -8], row 1 = [0, 2, -3, 3].
        val buffer = ByteBuffer.allocate(16 + 2 * 4 + 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("WMTE".toByteArray())
        buffer.putInt(2).putInt(4).putFloat(2f)
        buffer.putFloat(0.5f).putFloat(0.25f)
        buffer.put(nibbles(1, -1)).put(nibbles(7, -8))
        buffer.put(nibbles(0, 2)).put(nibbles(-3, 3))
        val file = File(folder.root, "t.bin").apply { writeBytes(buffer.array()) }
        TokenTable.open(file).use { table ->
            val out = FloatArray(8)
            table.row(0, out, 0)
            table.row(1, out, 4)
            assertArrayEquals(floatArrayOf(1f, -1f, 7f, -8f, 0f, 1f, -1.5f, 1.5f), out, 1e-6f)
            // Outside the vocabulary reads as row 0, as the model's graph does.
            val outside = FloatArray(4)
            table.row(99, outside, 0)
            assertArrayEquals(floatArrayOf(1f, -1f, 7f, -8f), outside, 1e-6f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTruncatedTableIsRefused() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("WMTE".toByteArray()).putInt(2).putInt(4).putFloat(1f)
        TokenTable.open(File(folder.root, "short.bin").apply { writeBytes(buffer.array()) }).close()
    }

    @Test
    fun halfFloatsDecode() {
        assertEquals(1f, EmojiVectors.halfToFloat(0x3C00), 0f)
        assertEquals(-2f, EmojiVectors.halfToFloat(0xC000), 0f)
        assertEquals(0.5f, EmojiVectors.halfToFloat(0x3800), 0f)
        assertEquals(0f, EmojiVectors.halfToFloat(0x0000), 0f)
        assertEquals(5.9604645e-8f, EmojiVectors.halfToFloat(0x0001), 1e-12f)
    }

    @Test
    fun emojiVectorsReadIntoAnIndex() {
        val emoji = listOf("🐈", "❤️")
        val width = EmbeddingInput.DIMENSIONS
        val names = emoji.map { it.toByteArray(Charsets.UTF_8) }
        val buffer = ByteBuffer.allocate(16 + names.sumOf { 2 + it.size } + emoji.size * width * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("WMEV".toByteArray()).putInt(1).putInt(emoji.size).putInt(width)
        for (name in names) buffer.putShort(name.size.toShort()).put(name)
        for (k in emoji.indices) for (d in 0 until width) buffer.putShort(if (d == k) 0x3C00.toShort() else 0)
        val file = File(folder.root, "e.bin").apply { writeBytes(buffer.array()) }
        val index = EmojiVectors.read(file)
        assertEquals(2, index.size)
        val query = FloatArray(width).also { it[1] = 1f }
        assertEquals("❤️", index.score(query).first().key)
    }

    private fun nibbles(low: Int, high: Int): Byte = ((low and 0x0F) or ((high and 0x0F) shl 4)).toByte()
}
