package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * Which key opens what, and which label goes under which button.
 *
 * The old physical-keyboard hint was one strip of bare letters floated over the
 * top bar: it named every binding and pointed at none of them. This file
 * replaces it with a plan that pairs each on-screen thing with the key that
 * fires it, so the hint can be drawn *under the button it belongs to*.
 *
 * The plan is built once from the visible state and read by two callers — the
 * renderer, for the labels, and the service, for the dispatch. One function, so
 * the badge under a key can never disagree with what the key does.
 *
 * Pure, like [HardwareShortcuts]: no Android types at all, so it runs in a plain
 * JVM test. The settings enums live in `:core:settings`, which depends on this
 * module, so the two mode questions arrive as booleans instead.
 */

// ------------------------------------------------------------------ keys ----

/**
 * One physical keypress in picker mode, after the layout has been decoded.
 *
 * [key] is already uppercased and comes from `pickerLetter`, which reads the
 * character printed on the keycap rather than the keycode. [shift] is read
 * separately off the event: `pickerLetter` decodes with no meta state (so a
 * binding can never depend on Shift or AltGr), which is exactly what makes
 * Shift usable as a tier of its own here.
 */
data class HintStroke(val key: Char, val shift: Boolean = false)

/** What a stroke does. */
sealed interface HintAction {
    data class OpenTool(val tool: ToolbarTool) : HintAction

    /** The toolbox launcher, which is a fixed button rather than one of the tools. */
    data object OpenToolbox : HintAction

    /** Index into the symbol row's characters. */
    data class InsertSymbol(val index: Int) : HintAction

    /** Index into the emoji row's emoji. */
    data class InsertEmoji(val index: Int) : HintAction

    data class PickSuggestion(val index: Int) : HintAction
}

/**
 * The on-screen rows a hint can be drawn under.
 *
 * [TOOLBAR] indexes the *buttons as drawn*, so its slot 0 is the fixed toolbox
 * launcher and slot 1 onward are the pinned tools. Numbering the tools alone
 * would put a 1 under the second button on the bar, which is exactly the kind of
 * off-by-one a badge is supposed to remove. The back chevron is skipped: it only
 * exists while a panel is open, so a digit on it would renumber the whole bar
 * every time one opened, and Escape already closes panels.
 */
enum class HintSurface { TOOLBAR, TOOLBOX, SYMBOL_ROW, EMOJI_ROW, SUGGESTION }

/** One drawable button: which row it is in, and where along that row. */
data class HintTarget(val surface: HintSurface, val index: Int)

/**
 * Modifier glyphs, for the compact spelling. Written as escapes rather than
 * literals: the badge is two or three characters wide, and a mangled encoding
 * somewhere in the build would be invisible until it reached a device.
 */
const val ShiftHintPrefix: String = "\u21E7"

const val CtrlHintPrefix: String = "\u2303"

const val AltHintPrefix: String = "\u2325"

/**
 * How a modifier reads on a badge.
 *
 * Words by default. The glyphs are Mac keycap conventions that a Windows or
 * Linux keyboard never prints, so on the wrong keyboard they are a puzzle rather
 * than a hint, while "Ctrl" and "Shift" are written on the key itself.
 * [Symbols] is there for anyone who would rather the badges took less room.
 */
data class HintModifiers(val ctrl: String, val shift: String, val alt: String) {
    companion object {
        val Words = HintModifiers(ctrl = "Ctrl+", shift = "Shift+", alt = "Alt+")

        val Symbols = HintModifiers(
            ctrl = CtrlHintPrefix,
            shift = ShiftHintPrefix,
            alt = AltHintPrefix,
        )

        fun of(spellOut: Boolean): HintModifiers = if (spellOut) Words else Symbols
    }
}

/**
 * The second tier for the symbol row: Shift plus a letter, in keyboard order, so
 * a row of badges reads left to right across the physical keys.
 *
 * A second tier is not a luxury. All 25 non-reserved letters are already spoken
 * for by [DefaultToolLetters] and there are far more tools than letters, so
 * "give everything a key" is arithmetically impossible on one tier.
 */
val SymbolHintKeys: List<Char> = ("QWERTYUIOP" + "ASDFGHJKL" + "ZXCVBNM").toList()

/**
 * The second tier for the emoji row: Shift plus a digit, counting from the left
 * exactly as the toolbar's Ctrl+digit does. One counting rule for two rows of
 * evenly spaced cells is less to remember than two.
 *
 * Disjoint from [SymbolHintKeys] by construction - letters against digits - so
 * neither row can renumber the other, however long its content grows.
 */
val EmojiHintKeys: List<Char> = "1234567890".toList()

/** The toolbox drains both pools, letters first; it is the widest consumer. */
val ExtendedHintKeys: List<Char> = SymbolHintKeys + EmojiHintKeys

/** Toolbar positions get a digit each: 1-9, then 0 for the tenth. */
val ToolbarHintDigits: List<Char> = "1234567890".toList()

/**
 * Which engine-ranked suggestion sits in each display slot, left to right.
 *
 * The strip has a setting that puts the top candidate in the *middle* with the
 * runner-up to its left, so the second-ranked word is drawn first. The badges
 * count slots the way the eye reads them, 1 2 3 from the left, which means the
 * key has to know which rank it is really committing. Mirrors the reordering in
 * `LatinSuggestionChips`; the conversion strip never reorders, so it passes
 * [centerPrimary] false.
 */
fun suggestionSlotOrder(count: Int, centerPrimary: Boolean): List<Int> {
    val order = List(count) { it }
    // Fewer than two and there is nothing to swap; the strip's own guard.
    if (!centerPrimary || count < 2) return order
    return listOf(1, 0) + order.drop(2)
}

/**
 * The bar's buttons in drawn order, as far as the digits reach: the toolbox
 * launcher, then the pinned tools.
 *
 * Its own function because two callers need the same answer and they arrive by
 * different routes — [buildHintPlan] for the badges and for the leader's bare
 * digits, and the `Ctrl`+digit chord, which fires even in the mode where the
 * bare digits have been given to the suggestions.
 */
fun toolbarHintButtons(toolbarTools: List<ToolbarTool>): List<HintAction> =
    (listOf(HintAction.OpenToolbox) + toolbarTools.map(HintAction::OpenTool))
        .take(ToolbarHintDigits.size)

// ------------------------------------------------------------------ plan ----

/**
 * The labels to draw and the strokes to act on.
 *
 * [labels] is keyed by what is on screen; a target missing from it has no key
 * right now and simply draws no badge. [strokes] is keyed by what the user
 * presses, and holds more than [labels] does: every bound tool letter works
 * whether or not that tool happens to be visible.
 */
data class HintPlan(
    val labels: Map<HintTarget, String> = emptyMap(),
    val strokes: Map<HintStroke, HintAction> = emptyMap(),
) {
    fun label(surface: HintSurface, index: Int): String? = labels[HintTarget(surface, index)]

    fun action(key: Char, shift: Boolean): HintAction? = strokes[HintStroke(key, shift)]
}

/**
 * Works out every hint for the keyboard as it currently stands.
 *
 * The rules, in the order they claim keys:
 *
 * - **Toolbar** takes the digits, in the order the icons are drawn, starting
 *   with the toolbox launcher. Whether a bare digit fires them depends on
 *   [leaderDigitsPickSuggestions] — one of the two has to own the digits, and
 *   the suggestion setting is the user's own answer to which.
 * - **Tool letters** are the user's own map and never move. They fire from the
 *   picker whether or not the tool is on screen.
 * - **The toolbox**, when open, labels each tool with its letter, and hands the
 *   ones with no letter a key from [ExtendedHintKeys]. That is the "generate one
 *   for the tools that have none" rule.
 * - **The symbol and emoji rows** take fixed slices of [ExtendedHintKeys] — but
 *   only while the toolbox is closed. An open toolbox is the thing the user is
 *   looking at, and letting it share the tier would move the rows' keys around
 *   under them every time a panel opened.
 *
 * @param toolbarTools the pinned tools, in the order they are drawn (already
 *   reversed for an RTL bar, so the digits follow the eye). The toolbox launcher
 *   is prepended here, not by the caller.
 * @param toolboxTools the tools the toolbox is showing, or empty when it is shut.
 * @param toolLetters the resolved letter map (see `resolvedToolLetters`).
 * @param symbolCells how many symbol-row cells are on screen.
 * @param emojiCells how many emoji-row cells are on screen.
 * @param suggestions how many suggestion-strip slots are on screen.
 * @param digitChord whether `Ctrl`+digit is on, which decides only how the
 *   toolbar badge is *spelled* — the chord itself is dispatched outside the picker.
 * @param leaderDigitsPickSuggestions true in the leader-digit suggestion mode.
 * @param suggestionAltDigits true in the Alt-digit suggestion mode.
 * @param pickMode pick mode is on (#701), so the bare digits select
 *   suggestions with no leader at all, whatever the suggestion mode says.
 * @param functionKeyToolbar F1 … F10 press the toolbar buttons right now, so
 *   the toolbar badges are spelled as those keys.
 * @param modifiers which hint tiers to build.
 */
@Suppress("LongParameterList")
fun buildHintPlan(
    toolbarTools: List<ToolbarTool> = emptyList(),
    toolboxTools: List<ToolbarTool> = emptyList(),
    toolLetters: Map<Char, ToolbarTool> = emptyMap(),
    symbolCells: Int = 0,
    emojiCells: Int = 0,
    suggestions: Int = 0,
    digitChord: Boolean = false,
    leaderDigitsPickSuggestions: Boolean = false,
    suggestionAltDigits: Boolean = false,
    pickMode: Boolean = false,
    functionKeyToolbar: Boolean = false,
    modifiers: HintModifiers = HintModifiers.Words,
): HintPlan {
    val labels = mutableMapOf<HintTarget, String>()
    val strokes = mutableMapOf<HintStroke, HintAction>()

    // -- toolbar: the digits, launcher first ----------------------------
    toolbarHintButtons(toolbarTools).forEachIndexed { index, action ->
        val digit = ToolbarHintDigits[index]
        if (!leaderDigitsPickSuggestions) strokes[HintStroke(digit)] = action
        // Spelled as the chord when the chord is on: that is the shortcut the
        // user can press at any time, and the bare digit only exists for the
        // few seconds after the leader. A button with no route at all (chord
        // off *and* the digits given to suggestions) gets no badge.
        val label = when {
            // The one key that needs no modifier and no leader wins the badge.
            functionKeyToolbar -> functionKeyLabel(index)
            digitChord -> modifiers.ctrl + digit
            !leaderDigitsPickSuggestions -> digit.toString()
            else -> null
        }
        if (label != null) labels[HintTarget(HintSurface.TOOLBAR, index)] = label
    }

    // -- tool letters: bound whether or not the tool is on screen --------
    for ((letter, tool) in toolLetters) {
        if (letter in ReservedLetters) continue
        strokes[HintStroke(letter)] = HintAction.OpenTool(tool)
    }

    // -- the second tier -------------------------------------------------
    val letterOf = toolLetters.entries.associate { (letter, tool) -> tool to letter }
    if (toolboxTools.isNotEmpty()) {
        var next = 0
        toolboxTools.forEachIndexed { index, tool ->
            val bound = letterOf[tool]
            val label = if (bound != null) {
                bound.toString()
            } else {
                val key = ExtendedHintKeys.getOrNull(next++) ?: return@forEachIndexed
                strokes[HintStroke(key, shift = true)] = HintAction.OpenTool(tool)
                modifiers.shift + key
            }
            labels[HintTarget(HintSurface.TOOLBOX, index)] = label
        }
    } else {
        for (index in 0 until minOf(symbolCells, SymbolHintKeys.size)) {
            val key = SymbolHintKeys[index]
            strokes[HintStroke(key, shift = true)] = HintAction.InsertSymbol(index)
            labels[HintTarget(HintSurface.SYMBOL_ROW, index)] = modifiers.shift + key
        }
        for (index in 0 until minOf(emojiCells, EmojiHintKeys.size)) {
            val key = EmojiHintKeys[index]
            strokes[HintStroke(key, shift = true)] = HintAction.InsertEmoji(index)
            labels[HintTarget(HintSurface.EMOJI_ROW, index)] = modifiers.shift + key
        }
    }

    // -- suggestions -----------------------------------------------------
    // Alt+digit needs no leader, so its badge is a standing label rather than
    // something the picker reveals; the leader-digit mode is the opposite, and
    // registers a stroke instead.
    //
    // Both are keyed by *display slot* — a position on the strip, counted from
    // the left, not an engine rank. With the top candidate centred those differ,
    // and the caller resolves one to the other in exactly one place
    // (`pickSuggestion`). Conflating them is how a badge reading 1 commits the
    // middle word.
    if (suggestionAltDigits || leaderDigitsPickSuggestions || pickMode) {
        val slots = minOf(suggestions, ToolbarHintDigits.size)
        for (slot in 0 until slots) {
            val digit = ToolbarHintDigits[slot]
            if (leaderDigitsPickSuggestions) {
                strokes[HintStroke(digit)] = HintAction.PickSuggestion(slot)
            }
            labels[HintTarget(HintSurface.SUGGESTION, slot)] =
                if (suggestionAltDigits && !pickMode) modifiers.alt + digit else digit.toString()
        }
    }

    return HintPlan(labels = labels, strokes = strokes)
}
