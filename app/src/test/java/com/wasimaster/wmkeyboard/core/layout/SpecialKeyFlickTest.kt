package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text flick arms on keys that do something other than type (#789): Enter's
 * flick up typing `?` types exactly that, and is not Enter.
 */
class SpecialKeyFlickTest {

    private val enter = Key("⏎", action = KeyAction.Enter, flick = mapOf(FlickDirection.UP to "?"))

    @Test
    fun `a text arm on Enter types its text and nothing else`() {
        val flicked = enter.flickKey(FlickDirection.UP)!!
        assertEquals(KeyAction.Text, flicked.action)
        assertEquals("?", flicked.label)
        assertNull(flicked.output)
    }

    @Test
    fun `the arm shows on the key and the tap stays Enter`() {
        assertTrue(enter.hasFlicks())
        assertEquals(FlickArm.Text("?"), enter.flickArm(FlickDirection.UP))
        assertEquals(KeyAction.Enter, enter.action)
    }

    @Test
    fun `the space bar takes both kinds of arm`() {
        // #863 gave the space bar arms, beside its swipes.
        val text = Key(" ", action = KeyAction.Space, flick = mapOf(FlickDirection.UP to "?"))
        val action = Key(" ", action = KeyAction.Space, flickActions = mapOf(FlickDirection.UP to KeyAlternate(KeyAction.Enter)))
        assertEquals(FlickArm.Text("?"), text.flickArm(FlickDirection.UP))
        assertTrue(action.flickArm(FlickDirection.UP) is FlickArm.Action)
    }

    @Test
    fun `the delete keys take both kinds of arm`() {
        // #769 let ⌫ and ⌦ take arms although their hold repeats.
        for (delete in listOf(KeyAction.Delete, KeyAction.ForwardDelete)) {
            val text = Key("", action = delete, flick = mapOf(FlickDirection.UP to "?"))
            val arm = KeyAlternate(KeyAction.Enter)
            val action = Key("", action = delete, flickActions = mapOf(FlickDirection.UP to arm))
            assertEquals(delete.toString(), FlickArm.Text("?"), text.flickArm(FlickDirection.UP))
            assertEquals(delete.toString(), FlickArm.Action(arm), action.flickArm(FlickDirection.UP))
        }
    }

    @Test
    fun `a text arm on Enter survives the file`() {
        val spec = LayoutSpec(
            id = "custom_flick", name = "Flick", langId = "en",
            layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(enter)))),
        )
        val decoded = LayoutCodec.decode(LayoutCodec.encode(spec))!!
        assertEquals(mapOf(FlickDirection.UP to "?"), decoded.layer(LayoutLayer.LETTERS)!!.rows.first().first().flick)
    }
}
