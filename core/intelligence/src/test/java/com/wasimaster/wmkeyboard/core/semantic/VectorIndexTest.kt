package com.wasimaster.wmkeyboard.core.semantic

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorIndexTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun vectorsAreStoredAtUnitLengthSoTheScoreIsTheCosine() {
        val index = VectorIndex(3)
        index.put("a", 1, floatArrayOf(3f, 0f, 0f))
        index.put("b", 1, floatArrayOf(1f, 1f, 0f))
        val hits = index.score(EmbeddingInput.normalize(floatArrayOf(1f, 0f, 0f)))
        assertEquals(listOf("a", "b"), hits.map { it.key })
        assertEquals(1f, hits[0].score, 1e-6f)
        assertEquals(0.7071f, hits[1].score, 1e-4f)
    }

    @Test
    fun equalScoresAreOrderedByKey() {
        val index = VectorIndex(2)
        index.put("b", 1, floatArrayOf(1f, 0f))
        index.put("a", 1, floatArrayOf(1f, 0f))
        assertEquals(listOf("a", "b"), index.score(floatArrayOf(1f, 0f)).map { it.key })
    }

    @Test
    fun roundTripsThroughAFile() {
        val index = VectorIndex(4)
        index.put("x", 42, floatArrayOf(1f, 2f, 3f, 4f))
        index.put("ünïcode 🐈", -7, floatArrayOf(0f, 0f, 1f, 0f))
        val file = File(folder.root, "sub/x.vec")
        index.write(file)
        val back = VectorIndex.read(file, 4)
        assertEquals(setOf("x", "ünïcode 🐈"), back.keys)
        assertEquals(42L, back.stampOf("x"))
        assertEquals(-7L, back.stampOf("ünïcode 🐈"))
        assertArrayEquals(index.vectorOf("x"), back.vectorOf("x"), 0f)
        assertTrue(!File(folder.root, "sub/x.vec.tmp").exists())
    }

    @Test
    fun aMissingWrongOrBrokenFileReadsAsEmpty() {
        assertEquals(0, VectorIndex.read(File(folder.root, "none.vec")).size)
        val index = VectorIndex(4)
        index.put("x", 1, floatArrayOf(1f, 0f, 0f, 0f))
        val file = File(folder.root, "four.vec")
        index.write(file)
        assertEquals(0, VectorIndex.read(file, 8).size)
        file.writeBytes(file.readBytes().copyOf(20))
        assertEquals(0, VectorIndex.read(file, 4).size)
    }

    @Test
    fun retainAllDropsWhatIsGone() {
        val index = VectorIndex(2)
        index.put("a", 1, floatArrayOf(1f, 0f))
        index.put("b", 1, floatArrayOf(0f, 1f))
        assertEquals(1, index.retainAll(setOf("a")))
        assertNull(index.stampOf("b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aVectorOfTheWrongWidthIsRefused() {
        VectorIndex(3).put("a", 1, floatArrayOf(1f, 0f))
    }
}
