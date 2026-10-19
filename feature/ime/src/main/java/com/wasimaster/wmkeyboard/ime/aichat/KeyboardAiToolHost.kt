package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.wasimaster.wmkeyboard.core.reply.ReplyShots
import com.wasimaster.wmkeyboard.core.ocr.MlKitScript
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaDomains
import com.wasimaster.wmkeyboard.core.homeassistant.HaStateFormat
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.settings.HomeAssistantSettings
import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.settings.ScannerSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.tools.OnlineOcr
import com.wasimaster.wmkeyboard.core.tools.AiToolHost
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.Translation
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateResult
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.homeassistant.SmartHomeHub
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * What the keyboard module lends the AI's tools (#698), for the keyboard's AI
 * panel and for both chats alike.
 *
 * [hints] are the languages the user types in, the active one first: what the
 * on-device translator falls back on when a few words are not enough to tell
 * the language by. [images] are the chat's pictures in order, by the names
 * [AiChatImages] keeps them under, and [seesImages] says the model is sent
 * them itself. [scanner] carries the scan text tool's settings and
 * [ocrReaders] its online readers, when Online is its engine.
 */
class KeyboardAiToolHost(
    private val context: Context,
    private val hints: List<String> = emptyList(),
    private val kdeConnect: KdeConnectSettings? = null,
    /** The smart home tool's preferences and its listed homes; both empty when the tool is off. */
    private val homePrefs: HomeAssistantSettings? = null,
    private val homes: List<HomeAccount> = emptyList(),
    private val images: List<String> = emptyList(),
    private val seesImages: Boolean = false,
    private val scanner: ScannerSettings? = null,
    private val ocrReaders: List<ResolvedProvider> = emptyList(),
) : AiToolHost {

    override val canTranslateOnDevice: Boolean get() = OnDeviceTranslator.AVAILABLE

    override fun translateOnDevice(text: String, target: String, source: String): Result<Translation> {
        val result = runBlocking {
            OnDeviceTranslator.translate(
                context = context.applicationContext,
                text = text,
                target = target,
                sourceOverride = source.takeUnless { it == TranslateClient.AUTO }.orEmpty(),
                hints = hints,
            )
        }
        // The words are the model's to read, so they say what the user would
        // have to do, in plain terms, rather than naming a result type.
        return when (result) {
            is OfflineTranslateResult.Success -> Result.success(Translation(text = result.text, detectedSource = result.source))
            is OfflineTranslateResult.NeedsModels -> failure(
                "the on-device language models for ${result.missing.joinToString(", ")} are not downloaded",
            )
            is OfflineTranslateResult.Unsupported -> failure("the on-device translator has no model for ${result.language}")
            OfflineTranslateResult.Undetermined -> failure("the on-device translator could not tell the text's language")
            OfflineTranslateResult.ModuleMissing -> failure("the on-device translator is not installed")
            is OfflineTranslateResult.Failed -> Result.failure(result.cause)
        }
    }

    private fun failure(message: String): Result<Translation> = Result.failure(IllegalStateException(message))

    override val canOpenApps: Boolean get() = true

    /**
     * The launcher tool's list of apps, best match first: a label equal to
     * [name], then one that starts with it, then any that contains it.
     */
    override fun openApp(name: String): Result<String> = runCatching {
        val pm = context.packageManager
        val wanted = name.trim()
        val matches = AppCatalog.filterApps(runBlocking { AppCatalog.loadApps(pm) }, wanted)
        val app = matches.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: matches.firstOrNull { it.label.startsWith(wanted, ignoreCase = true) }
            ?: matches.firstOrNull()
            ?: throw NoSuchElementException(wanted)
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: Intent().setComponent(app.component)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        app.label
    }

    override val canSendToPc: Boolean get() = kdeConnect?.enabled == true

    /**
     * Holds the link up the way a share from another app does, sends to the
     * first computer that connects, and lets the link go a little later,
     * once the text is on its way.
     */
    override fun sendToPc(text: String): Result<String> = runCatching {
        val settings = requireNotNull(kdeConnect) { "KDE Connect is off" }
        runBlocking(Dispatchers.Main) {
            KdeConnectHub.attach(context)
            KdeConnectHub.applySettings(settings)
            KdeConnectHub.hold(KdeConnectHub.Reason.SHARE)
            try {
                val device = KdeConnectHub.awaitConnected(CONNECT_TIMEOUT_MS).firstOrNull()
                    ?: error("no paired computer is connected")
                KdeConnectHub.send(context.applicationContext, device.id, emptyList(), text)
                device.name
            } finally {
                Handler(Looper.getMainLooper()).postDelayed(
                    { KdeConnectHub.release(KdeConnectHub.Reason.SHARE) },
                    RELEASE_DELAY_MS,
                )
            }
        }
    }

    override val canControlHome: Boolean get() = homePrefs?.enabled == true && homes.isNotEmpty()

    /** Holds the hub up under the AI's reason, asks the first home with an assistant, and lets go a little later. */
    override fun homeControl(request: String): Result<String> = runCatching {
        val settings = requireNotNull(homePrefs) { "The smart home tool is off" }
        runBlocking {
            holdHome(settings)
            try {
                val reply = SmartHomeHub.assist(request, hints.firstOrNull()?.substringBefore('-'), null)
                if (reply.isError) error(reply.speech.ifBlank { "Home Assistant could not do that" })
                reply.speech.ifBlank { "Done." }
            } finally {
                releaseHomeLater()
            }
        }
    }

    /** Every home's states, each line with the home's name first when there are several. */
    override fun homeStates(query: String?): Result<String> = runCatching {
        val settings = requireNotNull(homePrefs) { "The smart home tool is off" }
        runBlocking {
            holdHome(settings)
            try {
                val states = SmartHomeHub.ensureStates()
                val hub = SmartHomeHub.state.value
                val several = hub.homes.size > 1
                val q = query?.trim()?.lowercase().orEmpty()
                val format = HaStateFormat()
                val rows = states.values.asSequence()
                    .filter { HaDomains.isListed(it.domain) && !hub.registryOf(it).isHidden(it.entityId) }
                    .filter { e ->
                        val registry = hub.registryOf(e)
                        if (q.isEmpty()) {
                            e.state !in QUIET_STATES
                        } else {
                            registry.nameOf(e).lowercase().contains(q) || e.entityId.contains(q) ||
                                registry.areaOf(e.entityId)?.name?.lowercase()?.contains(q) == true ||
                                hub.home(e.home)?.name?.lowercase()?.contains(q) == true
                        }
                    }
                    .sortedBy { hub.registryOf(it).nameOf(it) }
                    .take(MAX_STATE_ROWS)
                    .map { e ->
                        val registry = hub.registryOf(e)
                        val home = hub.home(e.home)?.name?.takeIf { several && it.isNotBlank() }
                        listOfNotNull(home, registry.areaOf(e.entityId)?.name, registry.nameOf(e)).joinToString(" / ") + ": " + format.state(e) + " (" + e.entityId + ")"
                    }
                    .toList()
                if (rows.isEmpty()) "Nothing matched." else rows.joinToString("\n")
            } finally {
                releaseHomeLater()
            }
        }
    }

    private suspend fun holdHome(settings: HomeAssistantSettings) = kotlinx.coroutines.withContext(Dispatchers.Main) {
        SmartHomeHub.attach(context)
        SmartHomeHub.batch {
            SmartHomeHub.apply(settings, homes)
            SmartHomeHub.hold(SmartHomeHub.Reason.AI)
        }
    }

    private fun releaseHomeLater() {
        Handler(Looper.getMainLooper()).postDelayed({ SmartHomeHub.release(SmartHomeHub.Reason.AI) }, RELEASE_DELAY_MS)
    }

    override val imageCount: Int get() = images.size

    override val modelSeesImages: Boolean get() = seesImages

    override fun imageJpeg(number: Int): ByteArray? =
        images.getOrNull(number - 1)?.let { AiChatImages.jpeg(context, it) }

    /** The scan text tool's online readers, when that is its engine and one is set up. */
    private val onlineReaders: List<ResolvedProvider>
        get() = ocrReaders.takeIf { scanner?.ocrEngine == OcrEngine.ONLINE }.orEmpty()

    override val canReadImageText: Boolean get() = onlineReaders.isNotEmpty() || ChatImageOcr.available

    /**
     * The scan text tool's online engine when that is what the user picked,
     * else ML Kit on the phone.
     */
    override fun readImageText(number: Int): Result<String> = runCatching {
        val name = images.getOrNull(number - 1) ?: throw NoSuchElementException("picture $number")
        val online = onlineReaders
        if (online.isNotEmpty()) {
            val jpeg = AiChatImages.jpeg(context, name) ?: error("the picture is gone")
            return@runCatching OnlineOcr.read(online, jpeg).trim()
        }
        val file = AiChatImages.file(context, name) ?: error("the picture is gone")
        val bitmap = ReplyShots.bitmap(file) ?: error("the picture could not be read")
        // The language the user types in, active first, picks the recognizer.
        val script = hints.firstOrNull()?.let { OcrLanguages.mlKitRecognizer(LanguageRegistry.byId(it)) }
        runBlocking { ChatImageOcr.read(bitmap, script ?: MlKitScript.LATIN) }
    }

    private companion object {
        /** How long a send waits for a computer to connect, as a share does. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** How long the link stays held after a send, for the text to go out. */
        const val RELEASE_DELAY_MS = 5_000L
        private const val MAX_STATE_ROWS = 60
        private val QUIET_STATES = setOf("off", "unavailable", "unknown", "idle", "closed", "locked", "standby", "not_home", "docked", "disarmed")
    }
}
