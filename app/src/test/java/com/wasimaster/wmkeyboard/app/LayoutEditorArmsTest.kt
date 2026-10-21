package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.FlickArm
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.flickArm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the layout editor does with flick arms when a key's action changes,
 * and what it says under the flick pad (#838, test coverage #878).
 *
 * #838 point 4 was decided this way, not the way the issue first asked: a
 * layout saved before 0.6.2 with a letter's arms left on an action key has
 * typed them since that release, so they stay live on load (turning them off
 * again would be a second silent change), and the editor now drops a key's
 * text arms when its action changes, so no new layout gets one.
 */
class LayoutEditorArmsTest {

    private val letter = Key("e", flick = mapOf(FlickDirection.UP to "3"), flickShift = mapOf(FlickDirection.UP to "#"))

    private val actions = listOf(KeyAction.Shift, KeyAction.Enter, KeyAction.Delete, KeyAction.ForwardDelete)

    @Test
    fun `a letter turned into an action key leaves its arms behind`() {
        for (action in actions) {
            val edited = letter.withEditedAction(action, changed = true)
            assertEquals(action, edited.action)
            assertEquals("$action", emptyMap<FlickDirection, String>(), edited.flick)
            assertEquals("$action", emptyMap<FlickDirection, String>(), edited.flickShift)
            assertNull("$action", edited.flickArm(FlickDirection.UP))
            assertEquals("$action takes the action's own label", "", edited.label)
        }
    }

    @Test
    fun `an arm given after the action change is kept`() {
        for (action in actions) {
            val edited = letter.withEditedAction(action, changed = true)
                .copy(flick = mapOf(FlickDirection.UP to "?"))
            assertEquals("$action", FlickArm.Text("?"), edited.flickArm(FlickDirection.UP))
            // Picking the same action again is not a change, and keeps it.
            assertEquals("$action", edited.flick, edited.withEditedAction(action, changed = false).flick)
        }
    }

    @Test
    fun `a key that still types text keeps its arms and label`() {
        val edited = letter.withEditedAction(KeyAction.Text, changed = true)
        assertEquals(letter.flick, edited.flick)
        assertEquals(letter.flickShift, edited.flickShift)
        assertEquals("e", edited.label)
    }

    @Test
    fun `an old layout's arm on an action key is live once loaded, as it has been since 0_6_2`() {
        val stale = Key("⇧", action = KeyAction.Shift, flick = mapOf(FlickDirection.UP to "3"))
        val spec = LayoutSpec(
            id = "custom_old", name = "Old", langId = "en",
            layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(stale, Key("e"))))),
        )
        val loaded = LayoutCodec.decode(LayoutCodec.encode(spec))!!.layer(LayoutLayer.LETTERS)!!.rows.first().first()
        assertEquals(FlickArm.Text("3"), loaded.flickArm(FlickDirection.UP))
        // Edited to another action, it goes.
        assertNull(loaded.withEditedAction(KeyAction.Enter, changed = true).flickArm(FlickDirection.UP))
    }

    @Test
    fun `the flick pad says what an arm takes over`() {
        val modifierDrag = R.string.layout_editor_flick_takes_modifier_drag
        val layerPeek = R.string.layout_editor_flick_takes_layer_peek
        for (action in listOf(KeyAction.Shift, KeyAction.Mod(ModifierKey.CTRL), KeyAction.Mod(ModifierKey.ALT), KeyAction.Mod(ModifierKey.META))) {
            assertEquals("$action", modifierDrag, flickTakesOverNote(Key("", action = action)))
        }
        for (action in listOf(KeyAction.Symbols, KeyAction.Letters)) {
            assertEquals("$action", layerPeek, flickTakesOverNote(Key("", action = action)))
        }
        for (action in listOf(KeyAction.Text, KeyAction.Enter, KeyAction.Delete)) {
            assertEquals("$action", 0, flickTakesOverNote(Key("x", action = action)))
        }
    }
}
