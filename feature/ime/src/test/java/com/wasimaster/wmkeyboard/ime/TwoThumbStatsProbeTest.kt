package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TwoThumbWordEnd
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import java.time.Duration
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What About › Statistics records for a two-thumb word, beside an ordinary
 * glide (#751). No dictionary loads here, so a straight stroke reads as its
 * literal keys, `qy`.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbStatsProbeTest {
    private val grid = glideKeyGrid()
    private val space = Key(" ", action = KeyAction.Space)

    private fun settings(twoThumb: Boolean, allUp: Boolean = false) = KeyboardSettings(
        learnFromTyping = false,
        gesture = GestureSettings(
            twoThumbGlide = twoThumb,
            twoThumbWordEnd = if (allUp) TwoThumbWordEnd.ALL_UP else TwoThumbWordEnd.SPACE,
            twoThumbPauseMs = PAUSE_MS.toInt(),
            twoThumbTapPauseMs = PAUSE_MS.toInt(),
            twoThumbOneLetterPauseMs = PAUSE_MS.toInt(),
            twoThumbRetractWindowMs = 0,
        ),
    )

    private class TextKeyboard(private val editor: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo =
            EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }
    }

    private fun keyboard(settings: KeyboardSettings): Pair<WMKeyboardService, RecordingEditor> {
        val editor = RecordingEditor(initial = "")
        val service = TextKeyboard(editor)
        plantPersonalStores(service)
        seedState(service, glideReadyState(settings = settings))
        return service to editor
    }

    private fun read(owner: Any, cls: Class<*>, name: String): Any? =
        cls.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(owner)
        }

    /** The counters the Statistics screen reads, as one line. */
    private fun counters(service: WMKeyboardService): String {
        val stats = read(service, WMKeyboardService::class.java, "typingStats") as TypingStats
        val totals = stats.lifetime()
        return "words=${totals.words} glideWords=${totals.glideWords} chars=${totals.chars} saved=${totals.keystrokesSaved}"
    }

    /** words and glideWords only: what a glided word is expected to add. */
    private fun wordsAndGlides(service: WMKeyboardService): String = counters(service).substringBefore(" chars")

    private fun busy(service: WMKeyboardService): Boolean =
        (read(service, WMKeyboardService::class.java, "duetJob") as Job?)?.isActive == true ||
            (read(service, WMKeyboardService::class.java, "gestureJob") as Job?)?.isActive == true

    private fun drain(service: WMKeyboardService) {
        settle { !busy(service) }
        repeat(20) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun swipe(service: WMKeyboardService, verdict: GlideVerdict = GlideVerdict.Leader()) =
        service.onGesture(straightStroke(0), grid, GLIDE_KEY_WIDTH, verdict)

    @Test
    fun `S1 control ordinary glide - a glided word counts as a word and a glide word`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        swipe(service, GlideVerdict.Word("hello"))
        drain(service)
        assertEquals("fixture: the glide types its word and space", "hello ", editor.text.toString())
        assertEquals("words=1 glideWords=1", wordsAndGlides(service))
    }

    @Test
    fun `S2 two-thumb space - a swiped word ended by space counts like a glided word`() {
        val (service, _) = keyboard(settings(twoThumb = true))
        swipe(service)
        drain(service)
        service.onKey(space)
        drain(service)
        assertEquals("words=1 glideWords=1", wordsAndGlides(service))
    }

    @Test
    fun `S3 two-thumb all-up - a swiped word ended by the pause counts like a glided word`() {
        val (service, _) = keyboard(settings(twoThumb = true, allUp = true))
        swipe(service)
        drain(service)
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PAUSE_MS + 50))
        drain(service)
        assertEquals("words=1 glideWords=1", wordsAndGlides(service))
    }

    @Test
    fun `S4 two-thumb space - a tap then a swipe counts as one word with a glide in it`() {
        val (service, editor) = keyboard(settings(twoThumb = true))
        service.onKey(Key("h"))
        drain(service)
        swipe(service)
        drain(service)
        service.onKey(space)
        drain(service)
        assertEquals("words=1 glideWords=1", wordsAndGlides(service))
        // The letters the tap and the swipe did not each type: "hqy" is three
        // letters from two gestures.
        val letters = editor.text.toString().trim().length
        assertEquals("saved=${letters - 2}", counters(service).substringAfter("chars=").substringAfter(' '))
    }

    private companion object {
        const val PAUSE_MS = 200L
    }
}
