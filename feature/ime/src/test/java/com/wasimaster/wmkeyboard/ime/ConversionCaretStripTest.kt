package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.input.composer.CjkContext
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.CjkNgrams
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The conversion strip with nothing composing follows the field (#710, #711).
 *
 * The 联想 a commit puts up has to survive the commit's own selection echo,
 * which used to empty the strip before it could be tapped; and backspace taking
 * a converted word back out offers that reading's other conversions.
 */
@RunWith(RobolectricTestRunner::class)
class ConversionCaretStripTest {

    @Before
    fun loadPinyinTables() {
        PinyinSyllables.valid = setOf("wo", "ta")
        CjkDictionaries.pinyin = ConversionDictionary.parse(
            sequenceOf("wo\t我\t100", "ta\t他\t100", "ta\t她\t90", "ta\t它\t80"),
        )
        CjkDictionaries.pinyinNgrams = CjkNgrams.parse(
            sequenceOf("我\t1000", "们\t500", "我\t们\t400"),
        )
        CjkConfig.pinyinPrediction = true
        CjkContext.before = ""
    }

    @After
    fun resetPinyinTables() {
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        CjkContext.before = ""
    }

    private fun pinyinKeyboard(): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                glideReady = false,
                // 联想 obey the field's no-suggestions flag like the rest of the strip.
                fieldNoSuggestions = false,
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                ),
            ).copy(composer = PinyinComposer),
        )
        return service to editor
    }

    private fun typeAndPick(service: GlideKeyboard, reading: String, pick: String) {
        for (c in reading) service.onKey(Key(label = c.toString()))
        settle { pick in service.uiState.value.suggestions }
        service.onCandidateTapped(pick, service.uiState.value.suggestions.indexOf(pick))
    }

    @Test
    fun `the follow-ups a commit puts up survive its echo`() {
        val (service, editor) = pinyinKeyboard()
        typeAndPick(service, "wo", "我")
        assertEquals("我", editor.text.toString())
        assertTrue(service.uiState.value.suggestions.toString(), "们" in service.uiState.value.suggestions)

        service.onUpdateSelection(0, 0, 1, 1, -1, -1)
        settle { false }

        assertTrue(service.uiState.value.suggestions.toString(), "们" in service.uiState.value.suggestions)
    }

    @Test
    fun `backspace through a converted word offers its other conversions`() {
        val (service, editor) = pinyinKeyboard()
        typeAndPick(service, "ta", "他")
        service.onKey(Key(label = "⌫", action = KeyAction.Delete))
        settle { "她" in service.uiState.value.suggestions }

        assertEquals("", editor.text.toString())
        assertEquals(listOf("他", "她", "它"), service.uiState.value.suggestions)

        service.onCandidateTapped("她", 1)
        assertEquals("她", editor.text.toString())
    }
}
