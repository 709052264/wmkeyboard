package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.TavilyDepth
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ProviderProblem
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.translate.OfflineModelState
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The line under a provider on its list: built from the row's setup, the
 * kind's own settings and what is on the phone, as parts the screen puts
 * into words. These check the parts, which is where the rules are.
 */
class ProviderSummaryTest {

    private val settings = KeyboardSettings()

    private fun row(
        type: ProviderType,
        key: String = type.id,
        apiKey: String = "",
        url: String = "",
        model: String = "",
        name: String = "",
        enabled: Boolean = true,
        problem: ProviderProblem? = null,
    ) = ResolvedProvider(
        entry = ProviderEntry(type.id, key.takeIf { it != type.id }, enabled),
        type = type,
        credential = ProviderCredential(key, type.id, name = name, apiKey = apiKey, url = url, model = model),
        apiKey = apiKey,
        url = url,
        model = model,
        problem = problem,
    )

    private fun phrase(res: Int, vararg args: Any) = SummaryPart.Phrase(res, *args)

    // ---- setup

    @Test
    fun `a problem is the whole line`() {
        val summary = providerSummary(row(ProviderTypes.LIBRETRANSLATE, problem = ProviderProblem.NEEDS_SERVER), settings, null)
        assertEquals(listOf(phrase(R.string.providers_status_needs_server)), summary.parts)
        assertTrue(summary.problem)
    }

    @Test
    fun `a row that is off says so`() {
        val summary = providerSummary(row(ProviderTypes.DEEPL, apiKey = "abc", enabled = false), settings, null)
        assertEquals(listOf(phrase(R.string.providers_status_off)), summary.parts)
        assertFalse(summary.problem)
    }

    @Test
    fun `a server names its host`() {
        val summary = providerSummary(row(ProviderTypes.LIBRETRANSLATE, url = "https://tr.example.org/translate"), settings, null)
        assertEquals(listOf(phrase(R.string.providers_summary_server, "tr.example.org")), summary.parts)
    }

    @Test
    fun `an address typed without a scheme still gives its host`() {
        assertEquals("tr.example.org", hostOf("tr.example.org:5000/api"))
        assertEquals("not a url", hostOf("not a url"))
    }

    @Test
    fun `a key is set, ends in something, or is missing`() {
        assertEquals(
            listOf(phrase(R.string.providers_summary_key_ending, "1234")),
            providerSummary(row(ProviderTypes.DEEPL, apiKey = "abcdefgh1234"), settings, null).parts,
        )
        assertEquals(
            listOf(phrase(R.string.providers_summary_key_set)),
            providerSummary(row(ProviderTypes.DEEPL, apiKey = "abc"), settings, null).parts,
        )
        assertEquals(
            listOf(phrase(R.string.providers_summary_no_key)),
            providerSummary(row(ProviderTypes.DEEPL), settings, null).parts,
        )
        assertEquals(
            listOf(phrase(R.string.providers_summary_no_key_needed)),
            providerSummary(row(ProviderTypes.GIFCITIES), settings, null).parts,
        )
    }

    @Test
    fun `an AI service names its model and its key`() {
        assertEquals(
            listOf(phrase(R.string.providers_summary_model, "gpt-4o"), phrase(R.string.providers_summary_key_ending, "wxyz")),
            providerSummary(row(ProviderTypes.OPENAI, apiKey = "sk-abcdefwxyz", model = "gpt-4o"), settings, null).parts,
        )
        assertEquals(
            listOf(phrase(R.string.providers_summary_default_model), phrase(R.string.providers_summary_key_set)),
            providerSummary(row(ProviderTypes.OPENAI, apiKey = "sk-1"), settings, null).parts,
        )
    }

    @Test
    fun `a model server names where it is and the model`() {
        assertEquals(
            listOf(phrase(R.string.providers_summary_server, "192.168.1.20"), phrase(R.string.providers_summary_model, "llama3")),
            providerSummary(
                row(ProviderTypes.OLLAMA, key = "ollama:ab12", url = "http://192.168.1.20:11434", model = "llama3"),
                settings,
                null,
            ).parts,
        )
        assertEquals(
            listOf(phrase(R.string.providers_summary_server, "asr.example.org"), phrase(R.string.providers_summary_model, "large-v3")),
            providerSetupSummary(row(ProviderTypes.TRANSCRIPTION_SERVER, url = "asr.example.org", model = "large-v3")).parts,
        )
    }

    @Test
    fun `a front end names its engine beside its host`() {
        val engine = ProviderTypes.MOZHI.credential.engines.first()
        assertEquals(
            listOf(phrase(R.string.providers_summary_server, "mozhi.example.org"), SummaryPart.Literal(engine.label)),
            providerSummary(row(ProviderTypes.MOZHI, key = "mozhi:1", url = "https://mozhi.example.org"), settings, null).parts,
        )
    }

    // ---- the kinds' own settings

    @Test
    fun `DeepL says Write is on only when it can work`() {
        val write = settings.copy(translate = settings.translate.copy(deepl = settings.translate.deepl.copy(write = true)))
        val deepl = row(ProviderTypes.DEEPL, apiKey = "abc")
        // Write is switched on, but no DeepL credential is saved for it to use.
        assertEquals(listOf(phrase(R.string.providers_summary_key_set)), providerSummary(deepl, write, null).parts)
        val withKey = write.copy(
            providers = write.providers.copy(
                credentials = listOf(ProviderCredential(ProviderTypes.DEEPL.id, ProviderTypes.DEEPL.id, apiKey = "abc")),
            ),
        )
        assertEquals(
            listOf(phrase(R.string.providers_summary_key_set), phrase(R.string.providers_summary_deepl_write)),
            providerSummary(deepl, withKey, null).parts,
        )
    }

    @Test
    fun `Tavily says when it searches deeper`() {
        val advanced = settings.copy(webSearch = settings.webSearch.copy(tavilyDepth = TavilyDepth.ADVANCED))
        assertEquals(
            listOf(phrase(R.string.providers_summary_key_set), phrase(R.string.tooldetail_tavily_depth_advanced)),
            providerSummary(row(ProviderTypes.TAVILY, apiKey = "tv"), advanced, null).parts,
        )
    }

    @Test
    fun `the phone's recognizer says where it listens`() {
        val recognizer = row(ProviderTypes.SYSTEM_RECOGNIZER)
        val online = settings.copy(whisper = settings.whisper.copy(systemOnDevice = false))
        // On the phone first is the default.
        assertEquals(listOf(phrase(R.string.providers_summary_system_on_device)), providerSummary(recognizer, settings, null).parts)
        assertEquals(listOf(phrase(R.string.providers_summary_system_online)), providerSummary(recognizer, online, null).parts)
    }

    @Test
    fun `Harper says which English`() {
        val british = settings.copy(grammarDialect = GrammarDialect.BRITISH)
        assertEquals(listOf(phrase(GrammarDialect.BRITISH.labelRes)), providerSummary(row(ProviderTypes.HARPER), british, null).parts)
    }

    @Test
    fun `an on-device kind with nothing read yet says it runs on the phone`() {
        assertEquals(
            listOf(phrase(R.string.providers_summary_on_device)),
            providerSummary(row(ProviderTypes.WHISPER), settings, null).parts,
        )
    }

    // ---- what is on the phone

    @Test
    fun `no model at all is a problem`() {
        val summary = providerSummary(row(ProviderTypes.WHISPER), settings, whisperFacts("", emptyMap()))
        assertEquals(listOf(phrase(R.string.providers_summary_no_model)), summary.parts)
        assertTrue(summary.problem)
    }

    @Test
    fun `Whisper names the model it uses and its size`() {
        val model = WhisperCatalog.models.first()
        val facts = whisperFacts("", mapOf(model.id to WhisperDownloadManager.DownloadStatus.Downloaded))
        assertEquals(ProviderFacts.Model(model.displayName, model.sizeBytes, ModelState.Ready), facts)
        assertEquals(
            listOf(phrase(R.string.providers_summary_model, model.displayName), SummaryPart.Size(model.sizeBytes)),
            providerSummary(row(ProviderTypes.WHISPER), settings, facts).parts,
        )
    }

    @Test
    fun `a chosen Whisper model still on its way says how far it is`() {
        val model = WhisperCatalog.models.first()
        val facts = whisperFacts(model.id, mapOf(model.id to WhisperDownloadManager.DownloadStatus.Downloading(50, 200)))
        assertEquals(
            listOf(phrase(R.string.providers_summary_model, model.displayName), phrase(R.string.providers_summary_downloading, 25)),
            factsSummary(facts).parts,
        )
        assertFalse(factsSummary(facts).problem)
    }

    @Test
    fun `a chosen Whisper model that is not on the phone is a problem`() {
        val (chosen, other) = WhisperCatalog.models.take(2)
        val facts = whisperFacts(chosen.id, mapOf(other.id to WhisperDownloadManager.DownloadStatus.Downloaded))
        assertEquals(ProviderFacts.Model(chosen.displayName, chosen.sizeBytes, ModelState.Missing, others = 1), facts)
        val summary = factsSummary(facts)
        assertEquals(
            listOf(phrase(R.string.providers_summary_model, chosen.displayName), phrase(R.string.providers_summary_not_downloaded)),
            summary.parts,
        )
        assertTrue(summary.problem)
    }

    @Test
    fun `an engine with no choice names its biggest model and counts the rest`() {
        val models = EtCatalog.forEngine(EtEngine.MOONSHINE).filterNot { it.punctuation }
        assumeTrue("needs two Moonshine models", models.size >= 2)
        val states = models.associate { it.id to EtDownloadManager.Status.Downloaded }
        val best = models.maxBy { it.millions }
        assertEquals(
            listOf(
                phrase(R.string.providers_summary_model, best.label),
                SummaryPart.Size(best.bytes),
                SummaryPart.Counted(R.plurals.providers_summary_more_models, models.size - 1),
            ),
            factsSummary(etFacts(EtEngine.MOONSHINE, states)).parts,
        )
        assertEquals(ProviderFacts.NoModel, etFacts(EtEngine.MOONSHINE, emptyMap()))
    }

    @Test
    fun `a model the user brought is named by its file`() {
        val facts = localLlmFacts("custom:my-model.gguf", emptyMap<String, LocalLlmDownloadManager.DownloadStatus>())
        assertEquals(listOf(phrase(R.string.providers_summary_model, "my-model.gguf")), factsSummary(facts).parts)
    }

    @Test
    fun `a translator counts its languages, English aside`() {
        val mlKit = mapOf(
            "en" to OfflineModelState.Downloaded,
            "de" to OfflineModelState.Downloaded,
            "fr" to OfflineModelState.Missing(),
        )
        assertEquals(ProviderFacts.Installed(1, InstalledUnit.LANGUAGES), translateLanguages(mlKit))
        // Both directions of one language count once; the engine's own library is no language.
        val bergamot = mapOf(
            "bergamot:en-de" to OfflineModelState.Downloaded,
            "bergamot:de-en" to OfflineModelState.Downloaded,
            "bergamot:engine" to OfflineModelState.Downloaded,
            "bergamot:en-fr" to OfflineModelState.Downloading(),
        )
        assertEquals(ProviderFacts.Installed(1, InstalledUnit.LANGUAGES), translateLanguages(bergamot, "bergamot:"))
    }

    @Test
    fun `a count says how many, or that there are none`() {
        assertEquals(
            listOf(SummaryPart.Counted(R.plurals.providers_summary_packs, 3)),
            factsSummary(ProviderFacts.Installed(3, InstalledUnit.PACKS)).parts,
        )
        val none = factsSummary(ProviderFacts.Installed(0, InstalledUnit.LANGUAGES))
        assertEquals(listOf(phrase(R.string.providers_summary_no_languages)), none.parts)
        assertFalse(none.problem)
    }

    @Test
    fun `a percentage needs a known size`() {
        assertEquals(25, percentOf(50, 200))
        assertEquals(100, percentOf(300, 200))
        assertEquals(null, percentOf(50, -1))
    }
}
