package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.voice.futoswipe.FutoSwipe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mapping a real keyboard onto the frame FUTO Swipe's models were trained in,
 * and the layout check that falls out of the fit.
 */
class SwipeFrameTest {

    private fun keyMap(grid: GlideGrid, keyWidth: Float = 60f) =
        GlideKeyMap.of(grid.keyCenters(keyWidth), keyWidth)

    @Test
    fun theShippedQwertyFits() {
        assertNotNull(SwipeFrame.fit(keyMap(GlideGrid.of(BuiltInLayouts.QWERTY))))
    }

    @Test
    fun otherLatinLayoutsDoNot() {
        for (spec in listOf(BuiltInLayouts.AZERTY, BuiltInLayouts.DVORAK, BuiltInLayouts.COLEMAK, BuiltInLayouts.WORKMAN)) {
            assertNull(spec.name, SwipeFrame.fit(keyMap(GlideGrid.of(spec))))
        }
    }

    @Test
    fun aGridMissingALetterDoesNotFit() {
        val keys = (0 until 25).map { i ->
            KeyCenter('a'.code + i, SwipeFrame.KEY_X[i] * 400f, SwipeFrame.KEY_Y[i] * 150f)
        }
        assertNull(SwipeFrame.fit(GlideKeyMap.of(keys, 40f)))
    }

    @Test
    fun keyCentresLandOnTheFramesOwn() {
        // A keyboard 400 by 150 px with 40 px keys and a 12 px top margin.
        val keys = (0 until 26).map { i ->
            KeyCenter('a'.code + i, SwipeFrame.KEY_X[i] * 400f, 12f + SwipeFrame.KEY_Y[i] * 150f)
        }
        val frame = SwipeFrame.fit(GlideKeyMap.of(keys, 40f))!!
        for (i in 0 until 26) {
            assertEquals(SwipeFrame.KEY_X[i], frame.mapX(keys[i].x / 40f), 1e-4f)
            assertEquals(SwipeFrame.KEY_Y[i], frame.mapY(keys[i].y / 40f), 1e-4f)
        }
    }

    @Test
    fun aStrokeIsReadOnASixtyHertzClock() {
        val keys = (0 until 26).map { i ->
            KeyCenter('a'.code + i, SwipeFrame.KEY_X[i] * 400f, SwipeFrame.KEY_Y[i] * 150f)
        }
        val frame = SwipeFrame.fit(GlideKeyMap.of(keys, 40f))!!
        // Half the time spent still on the first key, then a quick move: the
        // even clock keeps half the samples on the pause.
        val path = listOf(
            GesturePoint(40f, 75f, 0L),
            GesturePoint(40f, 75f, 500L),
            GesturePoint(360f, 75f, 1000L),
        )
        val features = frame.features(path, 40f)!!
        assertEquals(2 * SwipeFrame.POINTS, features.size)
        val still = (0 until SwipeFrame.POINTS).count { features[it] < 0.11f }
        assertEquals(SwipeFrame.POINTS / 2.0, still.toDouble(), 2.0)
        assertEquals(0.9f, features[SwipeFrame.POINTS - 1], 1e-3f)
        assertEquals(0.5f, features[SwipeFrame.POINTS], 1e-3f)
    }

    @Test
    fun theModelAndTheFrameAgreeWhereTheKeysAre() {
        // FutoSwipe in :core:voice feeds the encoder its own copy of the
        // centres, since it cannot see this module.
        assertArrayEquals(SwipeFrame.KEY_X, FutoSwipe.KEY_X, 0f)
        assertArrayEquals(SwipeFrame.KEY_Y, FutoSwipe.KEY_Y, 0f)
        assertEquals(SwipeFrame.POINTS, FutoSwipe.POINTS)
        assertEquals(SwipeEmissions.CLASSES, FutoSwipe.CLASSES)
    }
}
