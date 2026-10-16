package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import com.wasimaster.wmkeyboard.core.tools.AiToolHost
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.Translation
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateResult
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import kotlinx.coroutines.runBlocking

/**
 * What the keyboard module lends the AI's tools (#698), for the keyboard's AI
 * panel and for both chats alike.
 *
 * [hints] are the languages the user types in, the active one first: what the
 * on-device translator falls back on when a few words are not enough to tell
 * the language by.
 */
class KeyboardAiToolHost(
    private val context: Context,
    private val hints: List<String> = emptyList(),
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
}
