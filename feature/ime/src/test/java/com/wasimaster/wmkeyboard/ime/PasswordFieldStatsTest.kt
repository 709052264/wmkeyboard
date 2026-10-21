package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Password fields kept out of About › Statistics and the tap heatmap (#829,
 * test coverage #878).
 *
 * Every counter the Statistics screen reads is compared whole: the day rows,
 * the lifetime totals with their histograms, and every heatmap cell. Typing
 * goes through the service's own key path, and a tap reaches the heatmap the
 * way KeyboardScreen sends one, through `onTouchKeys` and `onKeyTouch`.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordFieldStatsTest {

    private val counting = KeyboardSettings(learnFromTyping = false, typingStatsEnabled = true)
    private val grid = glideKeyGrid()

    private fun keyboard(state: KeyboardUiState): GlideKeyboard = glideKeyboard(state).first

    private fun stats(service: WMKeyboardService): TypingStats =
        WMKeyboardService::class.java.getDeclaredField("typingStats").let {
            it.isAccessible = true
            it.get(service) as TypingStats
        }

    /** Everything the Statistics screen draws, as one comparable value. */
    private fun counters(service: WMKeyboardService): List<Any> = stats(service).let {
        listOf(it.dayEntries(), it.lifetime(), it.heatmaps())
    }

    private fun pass(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** `zzzz qqqq`, a backspace and a space, a key at a time, with time between them. */
    private fun WMKeyboardService.typeWords() {
        for (c in "zzzz qqqq") {
            onKey(if (c == ' ') SPACE else Key(label = c.toString()))
            pass()
        }
        onKey(BACKSPACE)
        pass()
        onKey(SPACE)
        pass()
    }

    /** Letter downs, as KeyboardScreen reports them: the board once, then each touch. */
    private fun WMKeyboardService.tapKeys() {
        call(this, "onTouchKeys", grid)
        for (center in grid.filter { it.codePoint == 'z'.code || it.codePoint == 'q'.code }) {
            repeat(4) { call(this, "onKeyTouch", center.x, center.y, 0L) }
        }
    }

    private fun call(service: WMKeyboardService, name: String, vararg args: Any) {
        val method = WMKeyboardService::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(service, *args)
    }

    private fun recorded(before: List<Any>, after: List<Any>): Boolean = before != after

    @Test
    fun `fixture - a plain field is counted and mapped`() {
        val service = keyboard(glideReadyState(settings = counting))
        val before = counters(service)
        service.typeWords()
        service.tapKeys()
        val totals = stats(service).lifetime()
        assertTrue("chars: $totals", totals.chars > 0)
        assertTrue("words: $totals", totals.words > 0)
        assertTrue("backspaces: $totals", totals.backspaces > 0)
        assertTrue("taps", stats(service).heatmaps().sumOf { it.taps } > 0)
        assertTrue(recorded(before, counters(service)))
    }

    @Test
    fun `typing in a password field changes no counter`() {
        val service = keyboard(glideReadyState(settings = counting, secureField = true))
        val before = counters(service)
        service.typeWords()
        assertEquals(before, counters(service))
    }

    @Test
    fun `a key press in a password field changes no heatmap cell`() {
        val service = keyboard(glideReadyState(settings = counting, secureField = true))
        val before = stats(service).heatmaps()
        service.tapKeys()
        assertEquals(before, stats(service).heatmaps())
    }

    @Test
    fun `leaving the password field for a plain one counts again`() {
        val service = keyboard(glideReadyState(settings = counting, secureField = true))
        service.typeWords()
        service.tapKeys()
        val private = counters(service)
        service.onStartInput(EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }, false)
        assertEquals(false, service.uiState.value.secureField)
        service.typeWords()
        service.tapKeys()
        assertNotEquals("typing after the password field is recorded", private, counters(service))
        assertTrue(stats(service).lifetime().chars > 0)
        assertTrue(stats(service).heatmaps().sumOf { it.taps } > 0)
    }

    @Test
    fun `outside a password field the old rules still decide`() {
        // Statistics off: nothing.
        keyboard(glideReadyState(settings = counting.copy(typingStatsEnabled = false))).let { service ->
            val before = counters(service)
            service.typeWords()
            service.tapKeys()
            assertEquals("Statistics off", before, counters(service))
        }
        // Incognito that pauses learning: nothing.
        keyboard(glideReadyState(settings = counting.copy(incognito = true, incognitoPausesLearning = true))).let { service ->
            val before = counters(service)
            service.typeWords()
            service.tapKeys()
            assertEquals("incognito pausing learning", before, counters(service))
        }
        // Incognito that does not pause learning: counted as ever.
        keyboard(glideReadyState(settings = counting.copy(incognito = true, incognitoPausesLearning = false))).let { service ->
            val before = counters(service)
            service.typeWords()
            service.tapKeys()
            assertTrue("incognito not pausing learning", recorded(before, counters(service)))
        }
    }

    private companion object {
        val SPACE = Key(label = " ", action = KeyAction.Space)
        val BACKSPACE = Key(label = "⌫", action = KeyAction.Delete)
    }
}
