package com.wasimaster.wmkeyboard.core.tools

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.tools.R
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The physical keyboard's tool shortcuts: a *leader* press arms a picker, and
 * the next bare letter opens a tool. Everything here is pure — the only Android
 * types are `KeyEvent`'s integer constants and `R` string ids, which are plain
 * integers, so no method on the android.jar stub is ever called and the whole
 * engine runs in a plain JVM test.
 *
 * The IME owns the key events; this file owns what they mean.
 */

// ---------------------------------------------------------------- chords ----

/**
 * A physical key plus the exact modifiers that must be held with it. Exact:
 * Ctrl+Shift+K is a different chord from Ctrl+K, so arming never fires on a
 * near miss the user meant for the app.
 */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val hasModifier: Boolean get() = ctrl || alt || shift || meta
}

/**
 * Meta bits that say something about a *lock* rather than a held key. Caps Lock
 * being on must not stop a chord from matching.
 */
private const val LOCK_BITS = KeyEvent.META_CAPS_LOCK_ON or
    KeyEvent.META_NUM_LOCK_ON or
    KeyEvent.META_SCROLL_LOCK_ON or
    KeyEvent.META_FUNCTION_ON or
    KeyEvent.META_SYM_ON

fun KeyChord.matches(keyCode: Int, metaState: Int): Boolean {
    if (keyCode != this.keyCode) return false
    val state = metaState and LOCK_BITS.inv()
    return ctrl == (state and KeyEvent.META_CTRL_ON != 0) &&
        alt == (state and KeyEvent.META_ALT_ON != 0) &&
        shift == (state and KeyEvent.META_SHIFT_ON != 0) &&
        meta == (state and KeyEvent.META_META_ON != 0)
}

/**
 * Keys with a name of their own. Letters and digits are handled by arithmetic
 * below; this is the rest of what a leader chord may sensibly use.
 *
 * Deliberately a local table rather than `KeyEvent.keyCodeToString` — that is a
 * method (unavailable in unit tests) and its output has drifted across API
 * levels, which would silently invalidate stored preferences.
 */
private val NamedKeys: Map<String, Int> = buildMap {
    put("SPACE", KeyEvent.KEYCODE_SPACE)
    put("ENTER", KeyEvent.KEYCODE_ENTER)
    put("ESC", KeyEvent.KEYCODE_ESCAPE)
    put("TAB", KeyEvent.KEYCODE_TAB)
    put("BACKSPACE", KeyEvent.KEYCODE_DEL)
    put("DELETE", KeyEvent.KEYCODE_FORWARD_DEL)
    put("INSERT", KeyEvent.KEYCODE_INSERT)
    put("SLASH", KeyEvent.KEYCODE_SLASH)
    put("BACKSLASH", KeyEvent.KEYCODE_BACKSLASH)
    put("SEMICOLON", KeyEvent.KEYCODE_SEMICOLON)
    put("APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE)
    put("COMMA", KeyEvent.KEYCODE_COMMA)
    put("PERIOD", KeyEvent.KEYCODE_PERIOD)
    put("GRAVE", KeyEvent.KEYCODE_GRAVE)
    put("MINUS", KeyEvent.KEYCODE_MINUS)
    put("EQUALS", KeyEvent.KEYCODE_EQUALS)
    put("LEFTBRACKET", KeyEvent.KEYCODE_LEFT_BRACKET)
    put("RIGHTBRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET)
    put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
    put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
    put("UP", KeyEvent.KEYCODE_DPAD_UP)
    put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
    put("HOME", KeyEvent.KEYCODE_MOVE_HOME)
    put("END", KeyEvent.KEYCODE_MOVE_END)
    put("PAGEUP", KeyEvent.KEYCODE_PAGE_UP)
    put("PAGEDOWN", KeyEvent.KEYCODE_PAGE_DOWN)
    for (n in 1..12) put("F$n", KeyEvent.KEYCODE_F1 + (n - 1))
}

private val NamedKeyCodes: Map<Int, String> = NamedKeys.entries.associate { (k, v) -> v to k }

/** The stored/displayed name of a key, or null when it has none. */
fun keyName(keyCode: Int): String? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
    else -> NamedKeyCodes[keyCode]
}

private fun keyCodeOf(name: String): Int? {
    val upper = name.uppercase()
    if (upper.length == 1) {
        val c = upper[0]
        if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
        if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
    }
    return NamedKeys[upper]
}

/** `"ctrl+shift+K"` — modifiers lowercase in a fixed order, then the key name. */
fun formatChord(chord: KeyChord): String? {
    val name = keyName(chord.keyCode) ?: return null
    return buildString {
        if (chord.ctrl) append("ctrl+")
        if (chord.alt) append("alt+")
        if (chord.shift) append("shift+")
        if (chord.meta) append("meta+")
        append(name)
    }
}

fun parseChord(raw: String): KeyChord? {
    val parts = raw.trim().split('+')
    // An empty segment ("ctrl+", "ctrl++K") is malformed rather than lenient:
    // silently accepting it would let two different strings mean one chord and
    // break the round-trip the stored preference relies on.
    if (parts.any { it.isBlank() }) return null
    var ctrl = false
    var alt = false
    var shift = false
    var meta = false
    for (i in 0 until parts.size - 1) {
        when (parts[i].trim().lowercase()) {
            "ctrl", "control" -> ctrl = true
            "alt", "option" -> alt = true
            "shift" -> shift = true
            "meta", "cmd", "super", "search" -> meta = true
            else -> return null
        }
    }
    val keyCode = keyCodeOf(parts.last().trim()) ?: return null
    return KeyChord(keyCode, ctrl, alt, shift, meta)
}

/** A human label for a chord: `Ctrl+Shift+K`. */
fun describeChord(chord: KeyChord): String {
    val name = keyName(chord.keyCode) ?: return "?"
    return buildString {
        if (chord.ctrl) append("Ctrl+")
        if (chord.alt) append("Alt+")
        if (chord.shift) append("Shift+")
        if (chord.meta) append("Meta+")
        append(name)
    }
}

// ---------------------------------------------------------------- leader ----

/**
 * A modifier that can be double-tapped as the leader. Left and right are the
 * same modifier — nobody thinks of them as different keys, and a keyboard that
 * only has one of them must still work.
 */
enum class TapModifier(val label: String, val keyCodes: List<Int>) {
    CTRL("Ctrl", listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT)),
    ALT("Alt", listOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)),
    SHIFT("Shift", listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT)),
}

/** What arms the tool picker. */
sealed interface LeaderTrigger {
    data class Chord(val chord: KeyChord) : LeaderTrigger

    /** Two taps of a bare modifier with nothing pressed in between. */
    data class DoubleTap(val modifier: TapModifier) : LeaderTrigger
}

/**
 * Double-tapping Ctrl by default: it collides with nothing (a bare modifier
 * produces no character and is never a shortcut on its own), it needs no chord
 * to memorise, and it is still passed through to the app either way.
 */
val DefaultLeader: LeaderTrigger = LeaderTrigger.DoubleTap(TapModifier.CTRL)

/** How long the second tap of a double-tap may arrive after the first. */
const val DoubleTapWindowMs: Long = 400

/** How long the armed picker waits for its letter before giving up. */
const val DefaultPickerTimeoutMs: Int = 3000

fun formatLeader(leader: LeaderTrigger): String = when (leader) {
    is LeaderTrigger.DoubleTap -> "doubletap:" + leader.modifier.name.lowercase()
    is LeaderTrigger.Chord -> formatChord(leader.chord) ?: formatLeader(DefaultLeader)
}

/** Null on anything unparseable, so a corrupt preference falls back to the default. */
fun parseLeader(raw: String): LeaderTrigger? {
    val text = raw.trim()
    if (text.startsWith("doubletap:")) {
        val name = text.removePrefix("doubletap:").uppercase()
        val modifier = TapModifier.entries.firstOrNull { it.name == name } ?: return null
        return LeaderTrigger.DoubleTap(modifier)
    }
    return parseChord(text)?.let(LeaderTrigger::Chord)
}

/**
 * The leader spelled for a human, as the UI needs it.
 *
 * A chord names itself ("Ctrl+Shift+K"), so it arrives as plain [text] with a
 * [templateRes] of 0. A double tap needs words around the modifier name, so
 * [templateRes] holds that wording and [text] is its one argument. The UI does
 * the formatting, which keeps this file free of an Android context.
 */
data class LeaderLabel(@StringRes val templateRes: Int, val text: String)

fun leaderLabel(leader: LeaderTrigger): LeaderLabel = when (leader) {
    is LeaderTrigger.DoubleTap ->
        LeaderLabel(R.string.core_tools_shortcut_leader_double_tap, leader.modifier.label)
    is LeaderTrigger.Chord -> LeaderLabel(0, describeChord(leader.chord))
}

/**
 * Recognises two taps of one modifier with nothing pressed in between.
 *
 * It fires on the second key *up*, not the second down: by then the modifier is
 * released, so the letter that follows arrives bare instead of looking like a
 * Ctrl chord. Nothing here consumes anything — a bare Ctrl still reaches the
 * app whether or not it completes a tap.
 */
class DoubleTapDetector(private val modifier: TapModifier, private val windowMs: Long = DoubleTapWindowMs) {

    /** When the press in progress went down, or -1 for "no clean press". */
    private var downAt: Long = -1

    /** When the previous completed tap was released, or -1 for "no tap yet". */
    private var lastTapUpAt: Long = -1

    /** The press in progress started inside the window after a first tap. */
    private var completing = false

    /** Feed every physical key event. True on the release that completes a double tap. */
    fun onEvent(keyCode: Int, down: Boolean, repeat: Int, eventTime: Long): Boolean {
        if (keyCode !in modifier.keyCodes) {
            // Any other key breaks the chain: a double tap means two taps and
            // *nothing else*, so Ctrl-tap, Ctrl+C, Ctrl-tap never arms.
            reset()
            return false
        }
        if (down) {
            if (repeat > 0) {
                // Held down long enough to auto-repeat. That is a held modifier,
                // not a tap, and it cannot become one without being released.
                downAt = -1
                completing = false
                return false
            }
            completing = lastTapUpAt >= 0 && eventTime - lastTapUpAt <= windowMs
            downAt = eventTime
            return false
        }
        val clean = downAt >= 0
        downAt = -1
        if (!clean) {
            reset()
            return false
        }
        if (completing) {
            // Reset rather than keep the release time, so a third tap starts a
            // fresh pair instead of firing again.
            reset()
            return true
        }
        lastTapUpAt = eventTime
        return false
    }

    fun reset() {
        downAt = -1
        lastTapUpAt = -1
        completing = false
    }
}

// ------------------------------------------------------------- pick mode ----

/**
 * Whether [chord] may turn pick mode on and off (#701). Unlike the leader, a bare
 * key is allowed when it types nothing: F1 … F12 and Insert are what most
 * people reach for, and a key that never produces text cannot swallow any.
 */
fun pickModeChordAllowed(chord: KeyChord): Boolean =
    formatChord(chord) != null &&
        (chord.hasModifier || chord.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
            chord.keyCode == KeyEvent.KEYCODE_INSERT)

/** The stored pick-mode key, or null when there is none or it cannot be read. */
fun parsePickModeKey(raw: String): LeaderTrigger? {
    if (raw.isBlank()) return null
    return when (val trigger = parseLeader(raw)) {
        is LeaderTrigger.Chord -> trigger.takeIf { pickModeChordAllowed(it.chord) }
        else -> trigger
    }
}

/** What a number key does while pick mode is on. */
sealed interface PickModeKey {
    /** Select the suggestion in this display slot, counted from the left. */
    data class Pick(val slot: Int) : PickModeKey

    /** Type the digit itself, which is what Shift is for in pick mode. */
    data class TypeDigit(val digit: Char) : PickModeKey

    /** Type the symbol the layout prints on the key, which Shift+Alt reaches. */
    data object TypeSymbol : PickModeKey
}

/**
 * The meaning of [keyCode] under [metaState] while pick mode is on, or null to
 * treat the key as normal.
 *
 * Only the number row: the numeric keypad already types numbers and is what a
 * pick-mode user has left for them. Ctrl and Meta disqualify the key so the
 * app's own chords still reach it, and Alt alone is left to the Alt-digit
 * suggestion mode. Lock bits are ignored — Caps Lock on must not stop a pick.
 */
fun pickModeKeyFor(keyCode: Int, metaState: Int): PickModeKey? {
    if (keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return null
    val state = metaState and LOCK_BITS.inv()
    if (state and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0) return null
    val shift = state and KeyEvent.META_SHIFT_ON != 0
    val alt = state and KeyEvent.META_ALT_ON != 0
    val digit = '0' + (keyCode - KeyEvent.KEYCODE_0)
    return when {
        shift && alt -> PickModeKey.TypeSymbol
        shift -> PickModeKey.TypeDigit(digit)
        alt -> null
        // Zero is the tenth slot, the way the toolbar counts it.
        else -> PickModeKey.Pick(ToolbarHintDigits.indexOf(digit))
    }
}

/**
 * The toolbar button F1 … F10 presses, counting from the toolbox launcher the
 * way `Ctrl`+1 does, or -1 for any other key. F11 and F12 are left alone:
 * there are only ten digit slots, and F11 is the browser's full-screen key.
 */
fun functionKeyToolbarSlot(keyCode: Int, metaState: Int): Int {
    if (keyCode !in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F10) return -1
    val state = metaState and LOCK_BITS.inv()
    val held = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON
    if (state and held != 0) return -1
    return keyCode - KeyEvent.KEYCODE_F1
}

/** How an F-key reads on a badge, for toolbar slot [slot]. */
fun functionKeyLabel(slot: Int): String = "F" + (slot + 1)

// ---------------------------------------------------------------- picker ----

/**
 * The letter an armed picker should act on, or null when the key names no
 * letter at all (arrows, function and media keys).
 *
 * [unicodeChar] is `KeyEvent.getUnicodeChar(0)` — decoded with *no* meta state,
 * so Shift and AltGr can never change which tool a letter opens. Reading the
 * character rather than the keycode means the picker follows what is printed on
 * the user's keycaps: on AZERTY the physical Q key says "a", and `A` is what the
 * cheat sheet will have shown them.
 *
 * `?` and `/` both normalise to `'?'` — they are the same physical key, and
 * asking for help should not depend on whether Shift was held.
 */
fun pickerLetter(unicodeChar: Int, keyCode: Int): Char? {
    // Anything outside the Unicode range is a dead key (the combining-accent
    // flag lives in the high bit) rather than a character.
    if (unicodeChar in 1..0x10FFFF) {
        val ch = unicodeChar.toChar()
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') return ch.uppercaseChar()
        if (ch == '?' || ch == '/') return '?'
    }
    // No usable character: fall back to the QWERTY position of the keycode,
    // which is what a Cyrillic- or Greek-only physical keymap needs.
    return when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'A' + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_SLASH -> '?'
        else -> null
    }
}

/** Opens the toolbox — every tool, including the ones with no letter of their own. */
const val ToolboxLetter: Char = 'T'

/** Expands the picker hint into the full cheat sheet. */
const val CheatSheetLetter: Char = '?'

/** Letters the service handles itself, so the user can never bind a tool over them. */
val ReservedLetters: Set<Char> = setOf(ToolboxLetter, CheatSheetLetter)

/**
 * The letter each tool answers to out of the box: the tool's own initial
 * wherever that was free, taken in rough order of how often people reach for
 * it. Tools with no letter are still one keypress away through the toolbox.
 */
val DefaultToolLetters: Map<Char, ToolbarTool> = mapOf(
    'E' to ToolbarTool.EMOJI,
    'C' to ToolbarTool.CLIPBOARD,
    'S' to ToolbarTool.SNIPPETS,
    'X' to ToolbarTool.TEXT_EDIT,
    'G' to ToolbarTool.GIF,
    'K' to ToolbarTool.STICKER,
    'V' to ToolbarTool.VOICE,
    'R' to ToolbarTool.TRANSLATE,
    'D' to ToolbarTool.DICTIONARY,
    'W' to ToolbarTool.WEB_SEARCH,
    'I' to ToolbarTool.IMAGE_SEARCH,
    'A' to ToolbarTool.AI,
    'N' to ToolbarTool.NUMPAD,
    'Y' to ToolbarTool.SYMBOLS,
    'L' to ToolbarTool.CALCULATOR,
    'U' to ToolbarTool.UNIT_CONVERT,
    'M' to ToolbarTool.MEDIA_CONTROL,
    'P' to ToolbarTool.PASSWORD_GEN,
    'Q' to ToolbarTool.QR_GEN,
    'H' to ToolbarTool.HIDE_KEYBOARD,
    'O' to ToolbarTool.SETTINGS,
    'Z' to ToolbarTool.UNDO,
    // B for battery: P is already the password generator.
    'B' to ToolbarTool.POWER_SAVING,
    // J for "jump to an app": A is the AI tool and P the password generator.
    'J' to ToolbarTool.APP_LAUNCHER,
    'F' to ToolbarTool.FANCY,
)

/** `"c=CLIPBOARD;e=EMOJI"`, sorted so the stored string is stable. */
fun encodeToolLetters(map: Map<Char, ToolbarTool>): String =
    map.entries.sortedBy { it.key }.joinToString(";") { (letter, tool) ->
        "${letter.lowercaseChar()}=${tool.name}"
    }

/**
 * Drops anything it cannot make sense of — an unknown enum name from a newer
 * release, a tool this build flavour doesn't ship, a malformed entry. A corrupt
 * preference costs the user a binding, never a crash on the keystroke path.
 */
fun decodeToolLetters(raw: String): Map<Char, ToolbarTool> =
    raw.split(';').mapNotNull { entry ->
        // The letter is exactly one character, which is also what makes ';' and
        // '=' safe separators — a bindable letter is only ever A–Z or 0–9.
        if (entry.length < 3 || entry[1] != '=') return@mapNotNull null
        val letter = entry[0].uppercaseChar()
        if (letter in ReservedLetters) return@mapNotNull null
        val tool = runCatching { ToolbarTool.valueOf(entry.substring(2)) }.getOrNull()
            ?.takeIf(::isSupportedTool) ?: return@mapNotNull null
        letter to tool
    }.toMap()

/** The bindings that can actually fire right now: supported by the build, and enabled by the user. */
fun resolvedToolLetters(
    stored: Map<Char, ToolbarTool>,
    enabled: Collection<ToolbarTool>,
): Map<Char, ToolbarTool> =
    stored.filterKeys { it !in ReservedLetters }
        .filterValues { isSupportedTool(it) && it in enabled }

// ------------------------------------------------------------ validation ----

/**
 * Chords the focused app is entitled to. A leader that lands on one of these
 * would quietly break copy and paste, so the settings screen warns about it.
 * The list mirrors the outbound half of the same contract — the soft keyboard's
 * own `sendShortcut` reroutes exactly these.
 */
val ReservedChords: Set<KeyChord> = buildSet {
    for (key in listOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y,
        KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_F,
    )) {
        add(KeyChord(key, ctrl = true))
    }
    add(KeyChord(KeyEvent.KEYCODE_SPACE, ctrl = true))
    add(KeyChord(KeyEvent.KEYCODE_TAB, ctrl = true))
    add(KeyChord(KeyEvent.KEYCODE_TAB, alt = true))
}

sealed interface ShortcutProblem {
    /** The leader is a chord the focused app is likely to want. */
    data class ReservedLeader(val chord: KeyChord) : ShortcutProblem

    /** The leader has no modifier, so it would swallow ordinary typing. */
    data class BareLeader(val chord: KeyChord) : ShortcutProblem

    /** Bound to a tool the user has switched off; it can never fire. */
    data class DisabledTool(val letter: Char, val tool: ToolbarTool) : ShortcutProblem

    /** Two letters open the same tool. Harmless, but almost always a mistake. */
    data class DuplicateTool(val tool: ToolbarTool, val letters: List<Char>) : ShortcutProblem
}

fun validate(
    leader: LeaderTrigger,
    letters: Map<Char, ToolbarTool>,
    enabled: Collection<ToolbarTool>,
): List<ShortcutProblem> = buildList {
    if (leader is LeaderTrigger.Chord) {
        if (!leader.chord.hasModifier) add(ShortcutProblem.BareLeader(leader.chord))
        if (leader.chord in ReservedChords) add(ShortcutProblem.ReservedLeader(leader.chord))
    }
    for ((letter, tool) in letters.entries.sortedBy { it.key }) {
        if (isSupportedTool(tool) && tool !in enabled) {
            add(ShortcutProblem.DisabledTool(letter, tool))
        }
    }
    letters.entries.groupBy({ it.value }, { it.key })
        .filterValues { it.size > 1 }
        .forEach { (tool, keys) -> add(ShortcutProblem.DuplicateTool(tool, keys.sorted())) }
}

// ----------------------------------------------------------- macOS mode ----

/**
 * What a Mac chord means. Every one of these already has an implementation on
 * the IME side (the clipboard keys, the text-edit actions); the mode is a
 * routing table, not a second editor.
 */
enum class MacAction {
    COPY, PASTE, CUT, SELECT_ALL, UNDO, REDO,
    LINE_START, LINE_END, DOC_START, DOC_END,
    WORD_LEFT, WORD_RIGHT,
    DELETE_WORD, DELETE_TO_LINE_START,
}

/** A resolved Mac chord: what to do, and whether Shift made it a selection. */
data class MacBinding(val action: MacAction, val selecting: Boolean = false)

/**
 * The Mac chord for a keypress, or null to leave the key alone.
 *
 * Command is Android's META modifier — a Mac keyboard paired over Bluetooth
 * sends its Cmd keys as `META_META_ON` — and Option is ALT. Ctrl is deliberately
 * a disqualifier rather than an alias: on a Mac, Ctrl is its own modifier with
 * its own (Emacs-flavoured) meanings, and an app that already handles Ctrl+C
 * must keep getting it.
 *
 * Only the chords a Mac user would miss are claimed. Cmd with any other letter,
 * and Option with anything but the arrows or Backspace, falls straight through
 * to the focused app, which is where Cmd+F, Cmd+S and the rest belong.
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
fun macBindingFor(keyCode: Int, metaState: Int): MacBinding? {
    if (metaState and KeyEvent.META_CTRL_ON != 0) return null
    val cmd = metaState and KeyEvent.META_META_ON != 0
    val option = metaState and KeyEvent.META_ALT_ON != 0
    val shift = metaState and KeyEvent.META_SHIFT_ON != 0
    // Cmd+Opt together is a third modifier again (window and dev-tool chords).
    if (cmd == option) return null
    if (cmd) {
        return when (keyCode) {
            // Shift is what tells undo from redo, so Z reads it; the plain
            // editing chords do not, because Cmd+Shift+C is the app's.
            KeyEvent.KEYCODE_Z -> MacBinding(if (shift) MacAction.REDO else MacAction.UNDO)
            KeyEvent.KEYCODE_C -> if (shift) null else MacBinding(MacAction.COPY)
            KeyEvent.KEYCODE_V -> if (shift) null else MacBinding(MacAction.PASTE)
            KeyEvent.KEYCODE_X -> if (shift) null else MacBinding(MacAction.CUT)
            KeyEvent.KEYCODE_A -> if (shift) null else MacBinding(MacAction.SELECT_ALL)
            KeyEvent.KEYCODE_DPAD_LEFT -> MacBinding(MacAction.LINE_START, shift)
            KeyEvent.KEYCODE_DPAD_RIGHT -> MacBinding(MacAction.LINE_END, shift)
            KeyEvent.KEYCODE_DPAD_UP -> MacBinding(MacAction.DOC_START, shift)
            KeyEvent.KEYCODE_DPAD_DOWN -> MacBinding(MacAction.DOC_END, shift)
            KeyEvent.KEYCODE_DEL -> MacBinding(MacAction.DELETE_TO_LINE_START)
            else -> null
        }
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> MacBinding(MacAction.WORD_LEFT, shift)
        KeyEvent.KEYCODE_DPAD_RIGHT -> MacBinding(MacAction.WORD_RIGHT, shift)
        KeyEvent.KEYCODE_DEL -> MacBinding(MacAction.DELETE_WORD)
        else -> null
    }
}

// ------------------------------------------------------------ cheat sheet ----

/** Which block of the cheat sheet a row belongs to. */
enum class CheatGroup { TOOLS, ACTIONS }

/**
 * One line of the cheat sheet. [tool] carries the label duty when it is set —
 * the UI resolves it with the same `toolLabel` the toolbar uses, so the legend
 * can never disagree with the button. [labelRes] carries it otherwise, and is
 * 0 on a row that has a [tool].
 */
data class CheatRow(
    val trigger: String,
    val tool: ToolbarTool? = null,
    @StringRes val labelRes: Int = 0,
    val group: CheatGroup = CheatGroup.TOOLS,
)

/**
 * The full legend, which is the answer for everything the on-screen badges
 * cannot show: a tool that is neither pinned nor in the open panel still has a
 * letter, and the reader has no other way to find it.
 *
 * @param letters the resolved letter map (see `resolvedToolLetters`).
 * @param enabled the tools currently enabled, which gates both the digits and
 *   the letters.
 * @param toolbarTools the pinned tools, in drawn order, so their digits read
 *   down the list in the same order the icons read across the bar.
 * @param digitChord whether `Ctrl`+digit is on, which decides how those digits
 *   are spelled here.
 * @param altSuggestionDigits true when the suggestions answer to `Alt`+digit
 *   rather than to a bare digit after the leader.
 * @param languageSwitchChord whether the language-switch row gets its own
 *   chord legend.
 * @param keysToggle whether the toolbar-only view is up, so Space after the
 *   leader brings the keys up or puts them away (#642).
 * @param pickModeKey the pick-mode key spelled for a human, or null when none
 *   is set (#701).
 * @param pickMode whether pick mode is on, so the bare digits pick.
 * @param functionKeys whether F1 … F10 press the toolbar buttons right now.
 */
fun cheatSheetRows(
    letters: Map<Char, ToolbarTool>,
    enabled: Collection<ToolbarTool>,
    toolbarTools: List<ToolbarTool> = emptyList(),
    digitChord: Boolean = false,
    altSuggestionDigits: Boolean = false,
    languageSwitchChord: Boolean = false,
    keysToggle: Boolean = false,
    pickModeKey: String? = null,
    pickMode: Boolean = false,
    functionKeys: Boolean = false,
): List<CheatRow> = buildList {
    fun digit(slot: Int): String {
        if (functionKeys) return functionKeyLabel(slot)
        val key = ToolbarHintDigits[slot].toString()
        return if (digitChord) CtrlHintPrefix + key else key
    }
    // Digit 1 is the toolbox launcher, exactly as the bar draws it, so the
    // numbering here matches the badges the user has already seen.
    toolbarTools.filter { isSupportedTool(it) && it in enabled }
        .take(ToolbarHintDigits.size - 1)
        .forEachIndexed { index, tool -> add(CheatRow(digit(index + 1), tool = tool)) }
    resolvedToolLetters(letters, enabled).entries.sortedBy { it.key }
        .forEach { (letter, tool) -> add(CheatRow(letter.toString(), tool = tool)) }
    add(
        CheatRow(
            if (toolbarTools.isEmpty()) {
                ToolboxLetter.toString()
            } else {
                ToolboxLetter.toString() + "  " + digit(0)
            },
            labelRes = R.string.core_tools_shortcut_cheat_all_tools,
            group = CheatGroup.ACTIONS,
        ),
    )
    add(
        CheatRow(
            ShiftHintPrefix + ExtendedHintKeys.first(),
            labelRes = R.string.core_tools_shortcut_cheat_second_tier,
            group = CheatGroup.ACTIONS,
        ),
    )
    add(
        CheatRow(
            if (altSuggestionDigits && !pickMode) AltHintPrefix + "1–9" else "1–9",
            labelRes = R.string.core_tools_shortcut_cheat_select_suggestion,
            group = CheatGroup.ACTIONS,
        ),
    )
    if (pickModeKey != null) {
        add(
            CheatRow(
                pickModeKey,
                labelRes = R.string.core_tools_shortcut_cheat_pick_mode,
                group = CheatGroup.ACTIONS,
            ),
        )
    }
    if (languageSwitchChord) {
        add(
            CheatRow(
                CtrlHintPrefix + "Space",
                labelRes = R.string.core_tools_shortcut_cheat_language_switch,
                group = CheatGroup.ACTIONS,
            ),
        )
    }
    if (keysToggle) {
        add(
            CheatRow(
                "Space",
                labelRes = R.string.core_tools_shortcut_cheat_toggle_keys,
                group = CheatGroup.ACTIONS,
            ),
        )
    }
    add(
        CheatRow(
            CheatSheetLetter.toString(),
            labelRes = R.string.core_tools_shortcut_cheat_this_list,
            group = CheatGroup.ACTIONS,
        ),
    )
    add(
        CheatRow(
            "Esc",
            labelRes = CommonR.string.common_cancel,
            group = CheatGroup.ACTIONS,
        ),
    )
}
