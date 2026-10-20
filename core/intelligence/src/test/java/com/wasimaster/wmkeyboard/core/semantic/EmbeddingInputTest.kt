package com.wasimaster.wmkeyboard.core.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingInputTest {

    @Test
    fun picturesKeepTheirShapeInWholePooledBlocks() {
        // A COCO photo, 640×480, as LiteRT-LM sizes it for 630 patches.
        assertArrayEquals(intArrayOf(432, 336), EmbeddingInput.imageSize(640, 480))
        // A small square sticker is scaled up to fill the patches.
        val sticker = EmbeddingInput.imageSize(64, 64)
        assertEquals(sticker[0], sticker[1])
        assertTrue(sticker[0] / 16 * (sticker[1] / 16) <= EmbeddingInput.MAX_PATCHES)
        // A strip too thin for one block gets one block of height.
        val strip = EmbeddingInput.imageSize(4000, 10)
        assertEquals(48, strip[1])
        assertTrue(strip[0] / 16 * 3 <= EmbeddingInput.MAX_PATCHES)
    }

    @Test
    fun patchesAreRowMajorRgbAndTransparencyIsWhite() {
        val width = 32
        val height = 16
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        pixels[0] = 0xFFFF0000.toInt()
        pixels[16] = 0x00000000 // transparent, the first pixel of patch 2
        val patches = FloatArray(EmbeddingInput.MAX_PATCHES * EmbeddingInput.PATCH_VALUES)
        val positions = IntArray(EmbeddingInput.MAX_PATCHES * 2)
        val count = EmbeddingInput.patches(pixels, width, height, patches, positions)
        assertEquals(2, count)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), patches.copyOfRange(0, 3), 1e-6f)
        val second = EmbeddingInput.PATCH_VALUES
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), patches.copyOfRange(second, second + 3), 1e-6f)
        assertArrayEquals(intArrayOf(0, 0, 1, 0, -1, -1), positions.copyOfRange(0, 6))
    }

    @Test
    fun theShortestSignatureThatFitsIsUsed() {
        assertEquals(128, EmbeddingInput.sequenceLength(3))
        assertEquals(128, EmbeddingInput.sequenceLength(128))
        assertEquals(256, EmbeddingInput.sequenceLength(129))
        assertEquals(512, EmbeddingInput.sequenceLength(9000))
    }

    @Test
    fun normalizeGivesUnitLength() {
        val v = EmbeddingInput.normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), v, 1e-6f)
    }
}
