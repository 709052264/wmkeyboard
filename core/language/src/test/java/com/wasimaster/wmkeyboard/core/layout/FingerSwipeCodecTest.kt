package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored form of the multi-finger swipe bindings (issue #647). */
class FingerSwipeCodecTest {

    private val twoUp = FingerSwipe(2, SwipeDirection.UP)
    private val threeLeft = FingerSwipe(3, SwipeDirection.LEFT)

    @Test
    fun `bindings survive the round trip`() {
        val map = mapOf(
            threeLeft to KeyAlternate(KeyAction.Tool(ToolbarTool.CLIPBOARD)),
            twoUp to KeyAlternate(KeyAction.Text, label = "Thanks!"),
            FingerSwipe(4, SwipeDirection.DOWN) to KeyAlternate(KeyAction.Edit(TextEditAction.COPY)),
        )
        assertEquals(map, FingerSwipeCodec.decode(FingerSwipeCodec.encode(map)))
    }

    @Test
    fun `an unbound slot falls back to its default, and None clears it`() {
        assertEquals(DefaultFingerSwipes[twoUp], emptyMap<FingerSwipe, KeyAlternate>().actionOf(twoUp))
        assertNull(mapOf(twoUp to KeyAlternate(KeyAction.None)).actionOf(twoUp))
        assertNull(emptyMap<FingerSwipe, KeyAlternate>().actionOf(threeLeft))
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """{"2U":{"action":{"type":"teleport"}},"9X":{"action":{"type":"space"}},""" +
            """"3L":{"action":{"type":"space"}},"4R":"junk"}"""
        assertEquals(mapOf(threeLeft to KeyAlternate(KeyAction.Space)), FingerSwipeCodec.decode(json))
    }

    @Test
    fun `garbage decodes to nothing`() {
        assertTrue(FingerSwipeCodec.decode("not json").isEmpty())
        assertTrue(FingerSwipeCodec.decode(null).isEmpty())
    }

    @Test
    fun `tokens parse back`() {
        for (swipe in FingerSwipe.all) assertEquals(swipe, FingerSwipe.parse(swipe.token))
        assertNull(FingerSwipe.parse("1U"))
        assertNull(FingerSwipe.parse("2Q"))
    }
}
