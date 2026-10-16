package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan is read twice — once to draw a badge, once to act on a keypress — so
 * the thing worth pinning down is that the two halves agree, and that no key is
 * ever promised to two different buttons. Both are invisible failures on a
 * device: the badge says `⇧F` and some other cell opens.
 */
class HardwareHintsTest {

    private val toolbar = listOf(
        ToolbarTool.EMOJI,
        ToolbarTool.CLIPBOARD,
        ToolbarTool.AI,
    )

    // ----------------------------------------------------------- toolbar ----

    @Test
    fun `the toolbox launcher is the first button, so it takes digit one`() {
        val plan = buildHintPlan(toolbarTools = toolbar)
        assertEquals(HintAction.OpenToolbox, plan.action('1', shift = false))
        assertEquals(HintAction.OpenTool(ToolbarTool.EMOJI), plan.action('2', shift = false))
        assertEquals(HintAction.OpenTool(ToolbarTool.CLIPBOARD), plan.action('3', shift = false))
        assertEquals(HintAction.OpenTool(ToolbarTool.AI), plan.action('4', shift = false))
        assertEquals("1", plan.label(HintSurface.TOOLBAR, 0))
        assertEquals("2", plan.label(HintSurface.TOOLBAR, 1))
    }

    @Test
    fun `the chord setting only changes how the badge is spelled`() {
        val plan = buildHintPlan(toolbarTools = toolbar, digitChord = true)
        assertEquals("Ctrl+1", plan.label(HintSurface.TOOLBAR, 0))
        // The bare digit still fires it: the chord is an extra route, not a swap.
        assertEquals(HintAction.OpenToolbox, plan.action('1', shift = false))
    }

    @Test
    fun `the tenth button gets zero and the eleventh gets nothing`() {
        val many = List(12) { ToolbarTool.EMOJI }
        val plan = buildHintPlan(toolbarTools = many)
        assertEquals("0", plan.label(HintSurface.TOOLBAR, 9))
        assertNull(plan.label(HintSurface.TOOLBAR, 10))
    }

    @Test
    fun `leader digits belong to the suggestions when the user says so`() {
        val plan = buildHintPlan(
            toolbarTools = toolbar,
            suggestions = 3,
            digitChord = true,
            leaderDigitsPickSuggestions = true,
        )
        assertEquals(HintAction.PickSuggestion(0), plan.action('1', shift = false))
        // The toolbar keeps its badges because the chord still works.
        assertEquals("Ctrl+2", plan.label(HintSurface.TOOLBAR, 1))
    }

    @Test
    fun `a toolbar tool with no route at all draws no badge`() {
        val plan = buildHintPlan(
            toolbarTools = toolbar,
            digitChord = false,
            leaderDigitsPickSuggestions = true,
        )
        assertNull(plan.label(HintSurface.TOOLBAR, 0))
    }

    // ------------------------------------------------------------ letters ----

    @Test
    fun `bound tool letters fire whether or not the tool is on screen`() {
        val plan = buildHintPlan(toolLetters = DefaultToolLetters)
        assertEquals(HintAction.OpenTool(ToolbarTool.EMOJI), plan.action('E', shift = false))
        assertNull(plan.label(HintSurface.TOOLBOX, 0))
    }

    @Test
    fun `the reserved letters are never handed out`() {
        val plan = buildHintPlan(
            toolLetters = DefaultToolLetters + mapOf(ToolboxLetter to ToolbarTool.AI),
        )
        assertNull(plan.action(ToolboxLetter, shift = false))
        assertNull(plan.action(CheatSheetLetter, shift = false))
    }

    // ------------------------------------------------------------ toolbox ----

    @Test
    fun `an open toolbox labels bound tools with their letter`() {
        val plan = buildHintPlan(
            toolboxTools = listOf(ToolbarTool.EMOJI, ToolbarTool.CLIPBOARD),
            toolLetters = DefaultToolLetters,
        )
        assertEquals("E", plan.label(HintSurface.TOOLBOX, 0))
        assertEquals("C", plan.label(HintSurface.TOOLBOX, 1))
    }

    @Test
    fun `tools with no letter are given one from the second tier`() {
        // Neither of these is in DefaultToolLetters.
        val unlettered = listOf(ToolbarTool.WEATHER, ToolbarTool.COMPASS)
        val plan = buildHintPlan(toolboxTools = unlettered, toolLetters = DefaultToolLetters)
        assertEquals(HintModifiers.Words.shift + ExtendedHintKeys[0], plan.label(HintSurface.TOOLBOX, 0))
        assertEquals(HintModifiers.Words.shift + ExtendedHintKeys[1], plan.label(HintSurface.TOOLBOX, 1))
        assertEquals(
            HintAction.OpenTool(ToolbarTool.WEATHER),
            plan.action(ExtendedHintKeys[0], shift = true),
        )
    }

    @Test
    fun `an open toolbox owns the second tier so the rows do not move`() {
        val plan = buildHintPlan(
            toolboxTools = listOf(ToolbarTool.WEATHER),
            toolLetters = DefaultToolLetters,
            symbolCells = 8,
            emojiCells = 8,
        )
        assertNull(plan.label(HintSurface.SYMBOL_ROW, 0))
        assertNull(plan.label(HintSurface.EMOJI_ROW, 0))
    }

    // --------------------------------------------------------------- rows ----

    @Test
    fun `the emoji row counts in digits, like the toolbar`() {
        val plan = buildHintPlan(emojiCells = 3)
        assertEquals("Shift+1", plan.label(HintSurface.EMOJI_ROW, 0))
        assertEquals("Shift+3", plan.label(HintSurface.EMOJI_ROW, 2))
    }

    @Test
    fun `the row pools are disjoint, so one row cannot renumber the other`() {
        val few = buildHintPlan(symbolCells = 4, emojiCells = 6)
        val many = buildHintPlan(symbolCells = 22, emojiCells = 6)
        for (index in 0 until 6) {
            assertEquals(
                many.label(HintSurface.EMOJI_ROW, index),
                few.label(HintSurface.EMOJI_ROW, index),
            )
        }
        assertTrue(SymbolHintKeys.none { it in EmojiHintKeys })
    }

    @Test
    fun `row cells map to their own index`() {
        val plan = buildHintPlan(symbolCells = 3, emojiCells = 3)
        assertEquals(HintAction.InsertSymbol(2), plan.action(SymbolHintKeys[2], shift = true))
        assertEquals(HintAction.InsertEmoji(2), plan.action(EmojiHintKeys[2], shift = true))
    }

    @Test
    fun `rows past their pool get no key rather than someone else's`() {
        val plan = buildHintPlan(symbolCells = 40, emojiCells = 40)
        assertNull(plan.label(HintSurface.SYMBOL_ROW, SymbolHintKeys.size))
        assertNull(plan.label(HintSurface.EMOJI_ROW, EmojiHintKeys.size))
    }

    // ------------------------------------------------------------- labels ----

    @Test
    fun `modifiers spell themselves out by default`() {
        val plan = buildHintPlan(
            toolbarTools = toolbar,
            symbolCells = 1,
            suggestions = 1,
            digitChord = true,
            suggestionAltDigits = true,
        )
        assertEquals("Ctrl+1", plan.label(HintSurface.TOOLBAR, 0))
        assertEquals("Shift+Q", plan.label(HintSurface.SYMBOL_ROW, 0))
        assertEquals("Alt+1", plan.label(HintSurface.SUGGESTION, 0))
    }

    @Test
    fun `the compact spelling swaps the words for glyphs and nothing else`() {
        val args = { m: HintModifiers ->
            buildHintPlan(
                toolbarTools = toolbar,
                symbolCells = 1,
                digitChord = true,
                modifiers = m,
            )
        }
        val words = args(HintModifiers.Words)
        val symbols = args(HintModifiers.Symbols)
        assertEquals(CtrlHintPrefix + "1", symbols.label(HintSurface.TOOLBAR, 0))
        assertEquals(words.strokes, symbols.strokes)
    }

    // -------------------------------------------------- suggestion order ----

    @Test
    fun `slots count left to right even with the primary centred`() {
        // The strip draws rank 1 first, then rank 0: slot 0 must commit rank 1.
        assertEquals(listOf(1, 0, 2), suggestionSlotOrder(3, centerPrimary = true))
        assertEquals(listOf(0, 1, 2), suggestionSlotOrder(3, centerPrimary = false))
    }

    @Test
    fun `a lone candidate has nothing to swap`() {
        assertEquals(listOf(0), suggestionSlotOrder(1, centerPrimary = true))
        assertEquals(emptyList<Int>(), suggestionSlotOrder(0, centerPrimary = true))
    }

    @Test
    fun `the plan numbers slots, leaving the caller to resolve the rank`() {
        val plan = buildHintPlan(suggestions = 3, leaderDigitsPickSuggestions = true)
        assertEquals(HintAction.PickSuggestion(0), plan.action('1', shift = false))
        assertEquals("1", plan.label(HintSurface.SUGGESTION, 0))
    }

    // -------------------------------------------------------- suggestions ----

    @Test
    fun `alt digits are a standing label with no picker stroke`() {
        val plan = buildHintPlan(suggestions = 3, suggestionAltDigits = true)
        assertEquals("Alt+1", plan.label(HintSurface.SUGGESTION, 0))
        // Alt+digit is dispatched outside the picker, so the bare digits stay
        // with the toolbar. (Digit 1 is the toolbox launcher even on an empty bar.)
        assertTrue(plan.strokes.values.none { it is HintAction.PickSuggestion })
        assertEquals(HintAction.OpenToolbox, plan.action('1', shift = false))
    }

    @Test
    fun `switching the suggestions off drops their labels`() {
        val plan = buildHintPlan(suggestions = 3)
        assertNull(plan.label(HintSurface.SUGGESTION, 0))
    }

    // ---------------------------------------------------------- integrity ----

    @Test
    fun `nothing is promised to two different buttons`() {
        val plan = buildHintPlan(
            toolbarTools = toolbar,
            toolboxTools = listOf(ToolbarTool.WEATHER, ToolbarTool.COMPASS, ToolbarTool.LEVEL),
            toolLetters = DefaultToolLetters,
            suggestions = 3,
            digitChord = true,
            suggestionAltDigits = true,
        )
        // Four digits (launcher plus three tools), every default letter, three
        // second-tier keys. A map would hide a clash by overwriting, so count
        // what survived.
        assertEquals(4 + DefaultToolLetters.size + 3, plan.strokes.size)
    }

    @Test
    fun `shift is a tier of its own, never an alias of the bare key`() {
        val plan = buildHintPlan(
            toolboxTools = listOf(ToolbarTool.WEATHER),
            toolLetters = DefaultToolLetters,
        )
        val key = ExtendedHintKeys[0]
        assertNotEquals(plan.action(key, shift = true), plan.action(key, shift = false))
        assertTrue(plan.action(key, shift = false) != null)
    }

    @Test
    fun `pick mode badges the suggestions with bare digits, even in the Alt mode`() {
        val plan = buildHintPlan(suggestions = 3, suggestionAltDigits = true, pickMode = true)
        assertEquals("1", plan.label(HintSurface.SUGGESTION, 0))
        assertEquals("3", plan.label(HintSurface.SUGGESTION, 2))
        // Without pick mode or a suggestion mode, no badge promises anything.
        assertNull(buildHintPlan(suggestions = 3).label(HintSurface.SUGGESTION, 0))
    }

    @Test
    fun `the toolbar badges spell the F-keys while they press the buttons`() {
        val plan = buildHintPlan(
            toolbarTools = listOf(ToolbarTool.EMOJI),
            digitChord = true,
            functionKeyToolbar = true,
        )
        assertEquals("F1", plan.label(HintSurface.TOOLBAR, 0))
        assertEquals("F2", plan.label(HintSurface.TOOLBAR, 1))
    }
}
