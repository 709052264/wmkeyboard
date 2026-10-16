package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.wasimaster.wmkeyboard.core.reply.ReplyShots
import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.settings.ScannerSettings
import com.wasimaster.wmkeyboard.core.tools.VisionOcrClient
import com.wasimaster.wmkeyboard.core.tools.AiToolHost
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.Translation
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateResult
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.ime.AppCatalog
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
 * them itself. [scanner] carries the scan text tool's online engine.
 */
class KeyboardAiToolHost(
    private val context: Context,
    private val hints: List<String> = emptyList(),
    private val kdeConnect: KdeConnectSettings? = null,
    private val images: List<String> = emptyList(),
    private val seesImages: Boolean = false,
    private val scanner: ScannerSettings? = null,
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

    override val imageCount: Int get() = images.size

    override val modelSeesImages: Boolean get() = seesImages

    override fun imageJpeg(number: Int): ByteArray? =
        images.getOrNull(number - 1)?.let { AiChatImages.jpeg(context, it) }

    /** The scan text tool's online engine, when the user set one up. */
    private val onlineReader: ScannerSettings?
        get() = scanner?.takeIf {
            it.ocrEngine == OcrEngine.ONLINE && it.ocrOnlineUrl.isNotBlank() && it.ocrOnlineModel.isNotBlank()
        }

    override val canReadImageText: Boolean get() = onlineReader != null || ChatImageOcr.available

    /**
     * The scan text tool's online engine when that is what the user picked,
     * else ML Kit on the phone.
     */
    override fun readImageText(number: Int): Result<String> = runCatching {
        val name = images.getOrNull(number - 1) ?: throw NoSuchElementException("picture $number")
        val online = onlineReader
        if (online != null) {
            val jpeg = AiChatImages.jpeg(context, name) ?: error("the picture is gone")
            return@runCatching VisionOcrClient.read(online.ocrOnlineUrl, online.ocrOnlineModel, online.ocrOnlineKey, jpeg).trim()
        }
        val file = AiChatImages.file(context, name) ?: error("the picture is gone")
        val bitmap = ReplyShots.bitmap(file) ?: error("the picture could not be read")
        runBlocking { ChatImageOcr.read(bitmap) }
    }

    private companion object {
        /** How long a send waits for a computer to connect, as a share does. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** How long the link stays held after a send, for the text to go out. */
        const val RELEASE_DELAY_MS = 5_000L
    }
}
