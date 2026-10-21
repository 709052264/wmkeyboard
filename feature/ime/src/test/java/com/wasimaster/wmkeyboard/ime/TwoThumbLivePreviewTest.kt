package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The live preview of a two-thumb word (#849, #871): every stroke still being
 * drawn is in it, from the first two of a new word on.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbLivePreviewTest {

    /** What a preview of [points] would read: the word's gestures, or null for the stroke alone. */
    private fun WMKeyboardService.previewTokens(points: List<GesturePoint>): List<*>? =
        WMKeyboardService::class.java.declaredMethods.first { it.name == "duetPreviewTokens" }
            .apply { isAccessible = true }
            .invoke(this, uiState.value, points, grid, GLIDE_KEY_WIDTH) as List<*>?

    @Test
    fun `two overlapping strokes of a new word are both in its preview`() {
        val (service, _) = keyboard(settings())
        val first = straightStroke(0)
        // The other thumb lands a moment later, while the first is still down.
        val second = straightStroke(1).map { it.copy(t = it.t + 5) }
        service.onGesturePreview(first, grid, GLIDE_KEY_WIDTH, GlideCase.None)
        service.onGesturePreview(second, grid, GLIDE_KEY_WIDTH, GlideCase.None)
        assertEquals(2, service.previewTokens(second)?.size)
    }

    @Test
    fun `one stroke of a new word is previewed on its own`() {
        val (service, _) = keyboard(settings())
        val only = straightStroke(0)
        service.onGesturePreview(only, grid, GLIDE_KEY_WIDTH, GlideCase.None)
        assertEquals(null, service.previewTokens(only))
    }
}
