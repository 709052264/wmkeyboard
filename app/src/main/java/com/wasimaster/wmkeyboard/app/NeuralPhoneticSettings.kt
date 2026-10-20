package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitDownloads
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitModels

/**
 * Neural suggestions on a phonetic language's page: the switch, and under it
 * where IndicXlit's download stands ([NeuralPhoneticModelRow]). Turning the
 * switch on starts the download, through data saving's say like every model
 * download. One model serves every language, so the row reads the same on
 * each of their pages.
 */
@Composable
internal fun NeuralPhoneticToggle(
    langId: String,
    languageName: String,
    settings: LiveSettings,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val download = rememberXlitDownload(settings)
    ToggleSetting(
        R.string.languages_neural_phonetic_title,
        stringResource(R.string.languages_neural_phonetic_subtitle, formatBytes(IndicXlitModels.BYTES)),
        on,
        info = stringResource(R.string.languages_neural_phonetic_info, languageName),
        default = SettingsDefaults.suggestionStrip.neuralPhoneticFor(langId),
    ) { checked ->
        if (checked && !IndicXlitModels.isDownloaded(context.noBackupFilesDir)) download()
        onChange(checked)
    }
}

/**
 * Starts IndicXlit's download as data saving allows: at once, after the
 * metered-connection question, or not at all with the reason shown. The
 * dialogs are drawn where this is called.
 */
@Composable
private fun rememberXlitDownload(settings: LiveSettings): () -> Unit {
    val context = LocalContext.current
    val decide = rememberDownloadDecision(settings)
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.languages_neural_phonetic_metered_body, formatBytes(IndicXlitModels.BYTES)),
            onConfirm = {
                metered = false
                IndicXlitDownloads.start(context)
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
    return {
        when (decide()) {
            MeteredDecision.ALLOWED -> IndicXlitDownloads.start(context)
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
}

/** Where the download stands; a tap fetches it, or fetches it again after a failure. */
@Composable
internal fun NeuralPhoneticModelRow(settings: LiveSettings) {
    val context = LocalContext.current
    val download = rememberXlitDownload(settings)
    val status by IndicXlitDownloads.status.collectAsState()
    val module by FeatureModules.onnx.state.collectAsState()
    LaunchedEffect(Unit) { IndicXlitDownloads.refresh(context) }
    val size = formatBytes(IndicXlitModels.BYTES)
    val value = when (val s = status) {
        is IndicXlitDownloads.Status.Downloaded -> stringResource(R.string.languages_neural_phonetic_model_ready, size)
        is IndicXlitDownloads.Status.Downloading ->
            if (s.total > 0) {
                stringResource(R.string.languages_neural_phonetic_model_downloading, formatBytes(s.bytes), formatBytes(s.total))
            } else {
                stringResource(CommonR.string.common_downloading)
            }
        is IndicXlitDownloads.Status.Failed ->
            if ((module as? ModuleState.Missing)?.consent != null) {
                stringResource(R.string.models_native_consent_needed_info)
            } else if (s.messageArg.isEmpty()) {
                stringResource(s.messageRes)
            } else {
                stringResource(s.messageRes, s.messageArg)
            }
        IndicXlitDownloads.Status.NotDownloaded -> stringResource(R.string.languages_neural_phonetic_model_missing, size)
    }
    NavRow(R.string.languages_neural_phonetic_model_title, subtitle = value) {
        if (status is IndicXlitDownloads.Status.NotDownloaded || status is IndicXlitDownloads.Status.Failed) download()
    }
}
