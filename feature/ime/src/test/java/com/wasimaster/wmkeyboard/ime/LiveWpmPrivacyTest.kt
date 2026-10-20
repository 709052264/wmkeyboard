package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.ui.LiveWpmReadout
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The spacebar's live speed (#752) kept out of password and incognito fields:
 * nothing typed there is timed or shown, and the words typed on one side of a
 * field change, or of a field turning private, never reach the other.
 *
 * The clock is Robolectric's, moved by hand: every key is [KEY_MS] after the
 * one before, so two four-letter words and their spaces read [TWO_WORDS] from
 * a meter with nothing else in it.
 */
@RunWith(RobolectricTestRunner::class)
class LiveWpmPrivacyTest {

    private val space = Key(label = " ", action = KeyAction.Space)
    private val on = KeyboardSettings(learnFromTyping = false, spacebarLiveWpm = true)

    @Before
    @After
    fun clearReadout() {
        LiveWpmReadout.wpm.intValue = 0
    }

    private fun keyboard(state: KeyboardUiState = glideReadyState(settings = on)): GlideKeyboard =
        glideKeyboard(state).first

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun shown() = LiveWpmReadout.wpm.intValue

    private fun WMKeyboardService.letters(text: String) = text.forEach {
        onText(it.toString())
        pass(KEY_MS)
    }

    /** Types [word] and a space, and returns what the spacebar read after the space. */
    private fun WMKeyboardService.word(word: String = "abcd"): Int {
        letters(word)
        onKey(space)
        val reading = shown()
        pass(KEY_MS)
        return reading
    }

    /** The two words that make a reading, on a meter that has seen nothing else. */
    private fun WMKeyboardService.freshPair() {
        assertEquals("one word is not a reading", 0, word())
        assertEquals("the second word reads as a fresh start", TWO_WORDS, word())
    }

    @Test
    fun `fixture - two words read the expected speed`() {
        keyboard().freshPair()
    }

    @Test
    fun `a password field neither shows nor keeps a speed`() {
        val hidden = glideReadyState(settings = on, secureField = true)
        val service = keyboard(hidden)
        repeat(3) { assertEquals(0, service.word()) }
        seedState(service, hidden.copy(secureField = false))
        service.freshPair()
    }

    @Test
    fun `incognito neither shows nor keeps a speed`() {
        val hidden = glideReadyState(settings = on.copy(incognito = true))
        val service = keyboard(hidden)
        repeat(3) { assertEquals(0, service.word()) }
        seedState(service, hidden.copy(settings = on))
        service.freshPair()
    }

    @Test
    fun `a field that asks for incognito neither shows nor keeps a speed`() {
        val hidden = glideReadyState(settings = on).copy(fieldIncognito = true)
        val service = keyboard(hidden)
        repeat(3) { assertEquals(0, service.word()) }
        seedState(service, hidden.copy(fieldIncognito = false))
        service.freshPair()
    }

    @Test
    fun `turning private takes the speed typed before it away, for good`() {
        val open = glideReadyState(settings = on)
        val service = keyboard(open)
        service.freshPair()
        seedState(service, open.copy(secureField = true))
        service.onText("a")
        assertEquals("the first private key clears it", 0, shown())
        pass(KEY_MS)
        assertEquals(0, service.word())
        seedState(service, open)
        service.freshPair()
    }

    @Test
    fun `a new field clears the speed and starts its own`() {
        val service = keyboard()
        service.freshPair()
        service.onStartInput(textField(), false)
        assertEquals(0, shown())
        service.freshPair()
    }

    @Test
    fun `the keyboard going away clears the speed and starts the meter over`() {
        // #827: the hide is where the closing field's speed is let go of (#796).
        val service = keyboard()
        plantWindowStores(service)
        service.freshPair()
        service.onFinishInputView(true)
        assertEquals("the readout goes with the window", 0, shown())
        pass(KEY_MS)
        assertEquals("and nothing puts it back", 0, shown())
        // A meter that kept the two words before the hide would read the
        // first word after it.
        service.freshPair()
    }

    @Test
    fun `the same field restarting keeps its speed`() {
        val service = keyboard()
        service.freshPair()
        service.onStartInput(textField(), true)
        assertEquals(TWO_WORDS, shown())
    }

    @Test
    fun `which fields count as private`() {
        // #827: read off the field the way a start reads it, with the keys
        // shown or hidden (#845).
        val private = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        val open = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
        )
        val service = keyboard()
        for ((types, expected) in listOf(private to true, open to false)) {
            for (type in types) {
                service.onStartInput(EditorInfo().also { it.inputType = type }, false)
                val state = service.uiState.value
                assertEquals("secureField for 0x${Integer.toHexString(type)}", expected, state.secureField)
                assertEquals("liveWpmPrivate for 0x${Integer.toHexString(type)}", expected, state.liveWpmPrivate)
            }
        }
    }

    @Test
    fun `a field that asks for no learning is private while auto-incognito is on`() {
        val service = keyboard(glideReadyState(settings = on.copy(autoIncognito = true)))
        val flagged = listOf(
            textField().also { it.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING },
            textField().also { it.privateImeOptions = "com.example.noPersonalizedLearning" },
        )
        for (field in flagged) {
            service.onStartInput(textField(), false)
            service.freshPair()
            service.onStartInput(field, false)
            assertEquals(true, service.uiState.value.fieldIncognito)
            repeat(3) { assertEquals(0, service.word()) }
        }
    }

    @Test
    fun `a password field reached with the keys hidden shows no speed`() {
        // #845: only onStartInput runs while a physical keyboard hides the keys.
        val service = keyboard()
        service.onStartInput(
            EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD },
            false,
        )
        repeat(3) { assertEquals(0, service.word()) }
    }

    private fun textField() = EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }

    private companion object {
        /** Slow enough that two words pass the meter's two-second minimum span (`MIN_SPAN_MS`). */
        const val KEY_MS = 250L

        /** Two words of five characters, space included, over 2,250 ms: 2 / (2.25 / 60) ≈ 53. */
        const val TWO_WORDS = 53
    }
}
