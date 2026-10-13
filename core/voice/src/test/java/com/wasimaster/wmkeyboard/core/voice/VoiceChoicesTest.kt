package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChoicesTest {

    private val multilingual = WhisperCatalog.models.first { it.langCodes.isEmpty() }
    private val englishOnly = WhisperCatalog.models.first { it.fixedLang == "en" }
    private val parakeet = EtCatalog.forEngine(EtEngine.PARAKEET).first()
    private val moonshine = EtCatalog.forEngine(EtEngine.MOONSHINE).first()

    private fun choices(languageId: String, whistle: Boolean = true, server: Boolean = true) =
        VoiceChoices.forLanguage(
            languageId = languageId,
            whistleReady = whistle,
            whisperModels = listOf(multilingual, englishOnly),
            etModels = listOf(parakeet, moonshine),
            serverConfigured = server,
        )

    @Test fun englishGetsEveryEngineInOrder() {
        val engines = choices("en").map { it.engine }.distinct()
        assertEquals(listOf("system", "whistle", "whisper", "parakeet", "moonshine", "server"), engines)
        assertTrue(choices("en").any { it.modelId == englishOnly.id })
    }

    @Test fun onlyWhatServesTheLanguageIsListed() {
        val bangla = choices("bn")
        assertEquals(listOf("system", "whisper:${multilingual.id}", "server"), bangla.map { it.key })
        assertFalse(bangla.any { it.modelId == englishOnly.id })
    }

    @Test fun noServerAddressNoServer() {
        assertFalse(choices("de", server = false).any { it.engine == "server" })
        assertFalse(choices("de", whistle = false).any { it.engine == "whistle" })
        assertTrue(choices("de").any { it.engine == "parakeet" })
    }
}
