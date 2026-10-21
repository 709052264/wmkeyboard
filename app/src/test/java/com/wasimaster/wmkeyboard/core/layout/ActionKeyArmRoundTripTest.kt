package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Text arms on Enter and Shift through everything a layout goes through
 * outside the editor (#789, test coverage for #838 and #878): exported to a
 * file and imported back (the import runs the repair pass), duplicated, and
 * stored. A left-to-right and a right-to-left board alike.
 */
class ActionKeyArmRoundTripTest {

    private val shift = Key("⇧", action = KeyAction.Shift, flick = mapOf(FlickDirection.UP to "!"))
    private val enter = Key("⏎", action = KeyAction.Enter, flick = mapOf(FlickDirection.UP to "?", FlickDirection.LEFT to ";"))

    private fun board(id: String, langId: String, letters: String) = LayoutSpec(
        id = id, name = id, langId = langId,
        layers = mapOf(
            LayoutLayer.LETTERS.key to LayerSpec(
                listOf(
                    letters.map { Key(it.toString()) },
                    listOf(shift) + letters.take(5).map { Key(it.uppercase()) } + Key("⌫", action = KeyAction.Delete),
                    listOf(Key("?123", action = KeyAction.Symbols), Key(" ", action = KeyAction.Space), enter),
                ),
            ),
        ),
    )

    private fun armsOf(spec: LayoutSpec): Map<KeyAction, Map<FlickDirection, String>> =
        spec.layer(LayoutLayer.LETTERS)!!.rows.flatten()
            .filter { it.action == KeyAction.Shift || it.action == KeyAction.Enter }
            .associate { it.action to it.flick }

    private val expected = mapOf(KeyAction.Shift to shift.flick, KeyAction.Enter to enter.flick)

    @Test
    fun `export, import, duplicate and storage keep the arms on Enter and Shift`() {
        for (spec in listOf(board("custom_ltr", "en", "qwertyuiop"), board("custom_rtl", "ar", "ضصثقفغعهخح"))) {
            val imported = LayoutFile.decode(LayoutFile.encode(spec, appVersion = 1, appVersionName = "test"))!!
            assertEquals("${spec.id} import", expected, armsOf(imported.layout))
            val duplicate = imported.layout.copy(id = "${spec.id}_copy", name = "${spec.name} copy")
            assertEquals("${spec.id} duplicate", expected, armsOf(duplicate))
            val stored = LayoutCodec.decode(LayoutCodec.encode(duplicate))!!
            assertEquals("${spec.id} stored", expected, armsOf(stored))
            assertEquals("${spec.id} repaired again", expected, armsOf(stored.repair().spec))
            // And they are still live arms after all of it.
            val keys = stored.layer(LayoutLayer.LETTERS)!!.rows.flatten()
            assertEquals(FlickArm.Text("?"), keys.first { it.action == KeyAction.Enter }.flickArm(FlickDirection.UP))
            assertEquals(FlickArm.Text("!"), keys.first { it.action == KeyAction.Shift }.flickArm(FlickDirection.UP))
        }
    }
}
