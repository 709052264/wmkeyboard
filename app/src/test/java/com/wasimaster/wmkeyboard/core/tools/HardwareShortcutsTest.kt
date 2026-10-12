package com.wasimaster.wmkeyboard.core.tools

import android.view.KeyEvent
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.tools.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form is a user preference that has to survive upgrades, and the
 * matcher runs on every physical keystroke — both are worth pinning down here
 * rather than on a device with a Bluetooth keyboard in hand.
 */
class HardwareShortcutsTest {

    private val ctrlShiftK = KeyChord(KeyEvent.KEYCODE_K, ctrl = true, shift = true)

    // ------------------------------------------------------------ chords ----

    @Test
    fun `chords round-trip through their stored form`() {
        for (chord in listOf(
            ctrlShiftK,
            KeyChord(KeyEvent.KEYCODE_SPACE, ctrl = true, alt = true),
            KeyChord(KeyEvent.KEYCODE_F5, meta = true),
            KeyChord(KeyEvent.KEYCODE_SLASH, ctrl = true),
            KeyChord(KeyEvent.KEYCODE_7, alt = true, shift = true),
        )) {
            val encoded = formatChord(chord)!!
            assertEquals(encoded, chord, parseChord(encoded))
        }
    }

    @Test
    fun `parsing is case and alias insensitive`() {
        assertEquals(ctrlShiftK, parseChord("CTRL+SHIFT+k"))
        assertEquals(ctrlShiftK, parseChord("control+shift+K"))
        assertEquals(
            KeyChord(KeyEvent.KEYCODE_K, meta = true),
            parseChord("cmd+K"),
        )
    }

    @Test
    fun `junk parses to null instead of throwing`() {
        for (raw in listOf("", "   ", "+", "ctrl+", "ctrl+alt+ZZZ", "hyper+K", "ctrl++K")) {
            assertNull(raw, parseChord(raw))
        }
    }

    @Test
    fun `matching is exact on every modifier`() {
        assertTrue(ctrlShiftK.matches(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        // One modifier too many.
        assertFalse(
            ctrlShiftK.matches(
                KeyEvent.KEYCODE_K,
                KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON,
            ),
        )
        // One too few.
        assertFalse(ctrlShiftK.matches(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON))
        // Right modifiers, wrong key.
        assertFalse(ctrlShiftK.matches(KeyEvent.KEYCODE_J, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun `lock keys do not stop a chord matching`() {
        val state = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or
            KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON
        assertTrue(ctrlShiftK.matches(KeyEvent.KEYCODE_K, state))
    }

    // ------------------------------------------------------------ leader ----

    @Test
    fun `leaders round-trip in both forms`() {
        for (leader in listOf(
            DefaultLeader,
            LeaderTrigger.DoubleTap(TapModifier.ALT),
            LeaderTrigger.DoubleTap(TapModifier.SHIFT),
            LeaderTrigger.Chord(ctrlShiftK),
        )) {
            assertEquals(leader, parseLeader(formatLeader(leader)))
        }
    }

    @Test
    fun `the default leader is a double tap of ctrl`() {
        assertEquals("doubletap:ctrl", formatLeader(DefaultLeader))
        // The sentence around the modifier lives in strings.xml now, so this
        // pins the template plus the argument that fills it rather than the
        // assembled English — the wording can be reworded without breaking it.
        assertEquals(
            LeaderLabel(R.string.core_tools_shortcut_leader_double_tap, "Ctrl"),
            leaderLabel(DefaultLeader),
        )
    }

    @Test
    fun `an unparseable leader is null so the caller can fall back`() {
        for (raw in listOf("doubletap:", "doubletap:meta", "doubletap:ctrl+alt", "nonsense")) {
            assertNull(raw, parseLeader(raw))
        }
    }

    // -------------------------------------------------- double-tap detector ----

    private fun tap(detector: DoubleTapDetector, at: Long, key: Int = KeyEvent.KEYCODE_CTRL_LEFT): Boolean {
        detector.onEvent(key, down = true, repeat = 0, eventTime = at)
        return detector.onEvent(key, down = false, repeat = 0, eventTime = at + 20)
    }

    @Test
    fun `two clean taps arm the picker`() {
        val detector = DoubleTapDetector(TapModifier.CTRL)
        assertFalse(tap(detector, at = 0))
        assertTrue(tap(detector, at = 100))
    }

    @Test
    fun `left and right are the same modifier`() {
        val detector = DoubleTapDetector(TapModifier.CTRL)
        tap(detector, at = 0, key = KeyEvent.KEYCODE_CTRL_LEFT)
        assertTrue(tap(detector, at = 100, key = KeyEvent.KEYCODE_CTRL_RIGHT))
    }

    @Test
    fun `a key pressed in between breaks the chain`() {
        val detector = DoubleTapDetector(TapModifier.CTRL)
        tap(detector, at = 0)
        // Ctrl+C between the taps: this is someone using shortcuts, not arming.
        detector.onEvent(KeyEvent.KEYCODE_C, down = true, repeat = 0, eventTime = 50)
        detector.onEvent(KeyEvent.KEYCODE_C, down = false, repeat = 0, eventTime = 60)
        assertFalse(tap(detector, at = 100))
    }

    @Test
    fun `a held modifier is not a tap`() {
        val detector = DoubleTapDetector(TapModifier.CTRL)
        tap(detector, at = 0)
        detector.onEvent(KeyEvent.KEYCODE_CTRL_LEFT, down = true, repeat = 0, eventTime = 100)
        detector.onEvent(KeyEvent.KEYCODE_CTRL_LEFT, down = true, repeat = 1, eventTime = 150)
        assertFalse(detector.onEvent(KeyEvent.KEYCODE_CTRL_LEFT, down = false, repeat = 0, eventTime = 400))
    }

    @Test
    fun `a second tap outside the window starts over`() {
        val detector = DoubleTapDetector(TapModifier.CTRL, windowMs = 400)
        tap(detector, at = 0)
        assertFalse(tap(detector, at = 1000))
        // …but the late tap counts as a fresh first one.
        assertTrue(tap(detector, at = 1100))
    }

    @Test
    fun `three taps arm once, not twice`() {
        val detector = DoubleTapDetector(TapModifier.CTRL)
        assertFalse(tap(detector, at = 0))
        assertTrue(tap(detector, at = 100))
        assertFalse(tap(detector, at = 200))
    }

    @Test
    fun `another modifier in between breaks the chain`() {
        val detector = DoubleTapDetector(TapModifier.CTRL)
        tap(detector, at = 0)
        detector.onEvent(KeyEvent.KEYCODE_SHIFT_LEFT, down = true, repeat = 0, eventTime = 40)
        detector.onEvent(KeyEvent.KEYCODE_SHIFT_LEFT, down = false, repeat = 0, eventTime = 60)
        assertFalse(tap(detector, at = 100))
    }

    // ------------------------------------------------------ picker letter ----

    @Test
    fun `a printable character wins over the keycode`() {
        // AZERTY: the key at the QWERTY Q position produces 'a'.
        assertEquals('A', pickerLetter('a'.code, KeyEvent.KEYCODE_Q))
        assertEquals('E', pickerLetter('E'.code, KeyEvent.KEYCODE_E))
        assertEquals('7', pickerLetter('7'.code, KeyEvent.KEYCODE_7))
    }

    @Test
    fun `no usable character falls back to the qwerty position`() {
        assertEquals('A', pickerLetter(0, KeyEvent.KEYCODE_A))
        assertEquals('3', pickerLetter(0, KeyEvent.KEYCODE_3))
        // A dead key arrives with the combining-accent flag set in the high bit.
        assertEquals('B', pickerLetter(0x80000000.toInt() or 'e'.code, KeyEvent.KEYCODE_B))
    }

    @Test
    fun `help is the same key whether or not shift was held`() {
        assertEquals('?', pickerLetter('/'.code, KeyEvent.KEYCODE_SLASH))
        assertEquals('?', pickerLetter('?'.code, KeyEvent.KEYCODE_SLASH))
        assertEquals('?', pickerLetter(0, KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun `keys that name no letter are null`() {
        for (keyCode in listOf(
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER,
        )) {
            assertNull(keyCode.toString(), pickerLetter(0, keyCode))
        }
    }

    // -------------------------------------------------------- the defaults ----

    @Test
    fun `no default letter opens two tools and none is reserved`() {
        assertEquals(DefaultToolLetters.size, DefaultToolLetters.values.toSet().size)
        assertTrue(DefaultToolLetters.keys.none { it in ReservedLetters })
        assertTrue(DefaultToolLetters.keys.all { it.isUpperCase() || it.isDigit() })
    }

    // -------------------------------------------------------------- codec ----

    @Test
    fun `the letter map round-trips`() {
        val decoded = decodeToolLetters(encodeToolLetters(DefaultToolLetters))
        assertEquals(DefaultToolLetters.filterValues(::isSupportedTool), decoded)
    }

    @Test
    fun `encoding is stable and lowercase`() {
        val map = mapOf('E' to ToolbarTool.EMOJI, 'C' to ToolbarTool.CLIPBOARD)
        assertEquals("c=CLIPBOARD;e=EMOJI", encodeToolLetters(map))
    }

    @Test
    fun `malformed entries are dropped, not fatal`() {
        val decoded = decodeToolLetters("e=EMOJI;x;=EMOJI;ab=EMOJI;q=;z=NOT_A_TOOL;t=CLIPBOARD")
        // Only the first entry survives: 't' is reserved for the toolbox.
        assertEquals(mapOf('E' to ToolbarTool.EMOJI), decoded)
    }

    @Test
    fun `an empty string decodes to an empty map`() {
        assertEquals(emptyMap<Char, ToolbarTool>(), decodeToolLetters(""))
    }

    @Test
    fun `resolving drops the tools the user switched off`() {
        val stored = mapOf('E' to ToolbarTool.EMOJI, 'C' to ToolbarTool.CLIPBOARD)
        val resolved = resolvedToolLetters(stored, enabled = setOf(ToolbarTool.EMOJI))
        assertEquals(mapOf('E' to ToolbarTool.EMOJI), resolved)
    }

    // --------------------------------------------------------- validation ----

    @Test
    fun `a bare leader is flagged`() {
        val problems = validate(
            LeaderTrigger.Chord(KeyChord(KeyEvent.KEYCODE_K)),
            emptyMap(),
            ToolbarTool.entries.toSet(),
        )
        assertTrue(problems.any { it is ShortcutProblem.BareLeader })
    }

    @Test
    fun `a leader the app owns is flagged`() {
        val problems = validate(
            LeaderTrigger.Chord(KeyChord(KeyEvent.KEYCODE_C, ctrl = true)),
            emptyMap(),
            ToolbarTool.entries.toSet(),
        )
        assertTrue(problems.any { it is ShortcutProblem.ReservedLeader })
    }

    @Test
    fun `duplicate and disabled bindings are flagged`() {
        val letters = mapOf(
            'E' to ToolbarTool.EMOJI,
            'J' to ToolbarTool.EMOJI,
            'C' to ToolbarTool.CLIPBOARD,
        )
        val problems = validate(DefaultLeader, letters, enabled = setOf(ToolbarTool.EMOJI))
        assertEquals(
            listOf('E', 'J'),
            problems.filterIsInstance<ShortcutProblem.DuplicateTool>().single().letters,
        )
        assertEquals(
            ToolbarTool.CLIPBOARD,
            problems.filterIsInstance<ShortcutProblem.DisabledTool>().single().tool,
        )
    }

    @Test
    fun `the default setup is clean`() {
        assertEquals(
            emptyList<ShortcutProblem>(),
            validate(DefaultLeader, DefaultToolLetters, ToolbarTool.entries.toSet()),
        )
    }

    // -------------------------------------------------------- cheat sheet ----

    @Test
    fun `the cheat sheet lists enabled tools and the built-in actions`() {
        val rows = cheatSheetRows(DefaultToolLetters, enabled = setOf(ToolbarTool.EMOJI))
        val tools = rows.filter { it.group == CheatGroup.TOOLS }
        assertEquals(listOf(ToolbarTool.EMOJI), tools.map { it.tool })
        assertTrue(rows.any { it.trigger == ToolboxLetter.toString() })
        assertTrue(rows.any { it.trigger == CheatSheetLetter.toString() })
    }

    @Test
    fun `the language chord row appears only when the chord is on`() {
        val off = cheatSheetRows(DefaultToolLetters, enabled = setOf(ToolbarTool.EMOJI))
        val on = cheatSheetRows(
            DefaultToolLetters,
            enabled = setOf(ToolbarTool.EMOJI),
            languageSwitchChord = true,
        )
        assertTrue(off.none { it.trigger.endsWith("Space") })
        assertTrue(on.any { it.trigger.endsWith("Space") })
    }

    @Test
    fun `the keys toggle row appears only over the toolbar-only view`() {
        val off = cheatSheetRows(DefaultToolLetters, enabled = setOf(ToolbarTool.EMOJI))
        val on = cheatSheetRows(DefaultToolLetters, enabled = setOf(ToolbarTool.EMOJI), keysToggle = true)
        assertTrue(off.none { it.trigger == "Space" })
        assertTrue(on.any { it.trigger == "Space" && it.group == CheatGroup.ACTIONS })
    }
}
