package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Add punctuation and capitals, for the offline engines that write bare
 * words (Sherpa, Omnilingual). On, it shows the model's download row where
 * the build has ONNX Runtime; without the model, English dictation still
 * gets Sherpa's English punctuation model if that is downloaded.
 */
@Composable
internal fun VoicePunctuationSetting(repository: SettingsRepository, settings: LiveSettings, scope: CoroutineScope) {
    val on = settings.watch { it.whisper.punctuate }
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleSetting(
            R.string.voice_auto_punctuation_title,
            stringResource(R.string.voice_auto_punctuation_subtitle),
            on,
            info = stringResource(R.string.voice_auto_punctuation_info),
            default = SettingsDefaults.whisper.punctuate,
        ) { scope.launch { repository.setVoicePunctuate(it) } }
        if (on) {
            if (DictationPunctuation.runtimeShipped) {
                OnnxModuleBanner(settings)
                PunctuationModelRow(settings)
            } else {
                CaptionText(stringResource(R.string.voice_auto_punctuation_not_in_build))
            }
        }
    }
}

/** The multilingual punctuation model: download, progress, delete. */
@Composable
private fun PunctuationModelRow(settings: LiveSettings) {
    val filesDir = LocalContext.current.filesDir
    val status by DictationPunctuation.status.collectAsState()
    LaunchedEffect(Unit) { DictationPunctuation.refresh(filesDir) }
    OnnxModelRow(
        settings = settings,
        title = stringResource(R.string.voice_auto_punctuation_model_title),
        status = status,
        ready = stringResource(R.string.voice_auto_punctuation_model_ready, formatBytes(DictationPunctuation.BYTES)),
        missing = stringResource(R.string.voice_auto_punctuation_model_missing, formatBytes(DictationPunctuation.BYTES)),
        bytes = DictationPunctuation.BYTES,
        onDownload = { DictationPunctuation.download(filesDir) },
        onCancel = DictationPunctuation::cancel,
        onDelete = { DictationPunctuation.delete(filesDir) },
    )
}
