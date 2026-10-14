package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel

/** Downloads above this size ask before using mobile data. */
private const val ET_METERED_CONFIRM_BYTES = 150_000_000L

/**
 * The models of one ExecuTorch engine: Asr4All's three sizes (#289), or
 * Parakeet's and Moonshine's one each. Download, progress, delete. There is
 * no setting for which one dictates: the largest one on the device does, so
 * keeping a model is choosing it, and the screen marks the one in use. Shown
 * only while [engine] is the engine and the device can run it.
 */
@Composable
internal fun EtModelManager(engine: EtEngine, settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val states by EtDownloadManager.states.collectAsState()
    var meteredAsk by remember { mutableStateOf<EtModel?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { EtDownloadManager.refresh(filesDir) }
    val models = remember(engine) { EtCatalog.forEngine(engine) }

    fun requestDownload(model: EtModel) {
        when (downloadDecisionNow(context, settings.value)) {
            // Data saving is not holding it, but Parakeet is most of a
            // gigabyte: over mobile data that still deserves a word, as the
            // Whisper screen gives its big models.
            MeteredDecision.ALLOWED ->
                if (isMeteredNow(context) && model.bytes >= ET_METERED_CONFIRM_BYTES) {
                    meteredAsk = model
                } else {
                    EtDownloadManager.start(filesDir, model)
                }
            MeteredDecision.ASK -> meteredAsk = model
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    // Read from the states, so the mark moves the moment a download lands.
    val active = models
        .filter { states[it.id] is Status.Downloaded }
        .maxByOrNull { it.millions }

    // Play: the runtime itself is an on-demand part, shared with whisper.cpp.
    SpeechModuleBanner(settings)

    SettingsGroup(
        stringResource(
            when (engine) {
                EtEngine.ASR4ALL -> R.string.voice_asr4all_group
                EtEngine.PARAKEET -> R.string.voice_parakeet_group
                EtEngine.MOONSHINE -> R.string.voice_moonshine_group
            },
        ),
        info = stringResource(
            when (engine) {
                EtEngine.ASR4ALL -> R.string.voice_asr4all_model_info
                EtEngine.PARAKEET -> R.string.voice_parakeet_model_info
                EtEngine.MOONSHINE -> R.string.voice_moonshine_model_info
            },
        ),
    ) {
        for (model in models) {
            item {
                val status = states[model.id] ?: Status.NotDownloaded
                // A model's own name, which is not translated.
                val title = model.label
                Column(modifier = Modifier.fillMaxWidth()) {
                    WmRow(
                        title = title,
                        subtitle = when (status) {
                            Status.Downloaded -> stringResource(
                                if (model == active) R.string.voice_et_model_in_use
                                else R.string.voice_et_model_kept,
                            )
                            is Status.Paused -> stringResource(R.string.models_paused_progress, formatBytes(status.bytes))
                            else -> stringResource(R.string.voice_et_model_missing, formatBytes(model.bytes))
                        },
                        trailing = {
                            when (status) {
                                Status.Downloaded -> IconButton(
                                    onClick = { EtDownloadManager.delete(filesDir, model) },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.models_delete_model_desc, title),
                                    )
                                }
                                is Status.Downloading -> IconButton(onClick = { EtDownloadManager.cancel() }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.models_cancel_download_desc),
                                    )
                                }
                                is Status.Paused -> TextButton(
                                    onClick = { requestDownload(model) },
                                    enabled = !EtDownloadManager.isBusy,
                                ) { Text(stringResource(R.string.models_resume_action)) }
                                Status.NotDownloaded, is Status.Failed -> TextButton(
                                    onClick = { requestDownload(model) },
                                    enabled = !EtDownloadManager.isBusy,
                                ) {
                                    Text(
                                        stringResource(
                                            if (status is Status.Failed) CommonR.string.common_retry
                                            else CommonR.string.common_download,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    when (status) {
                        is Status.Downloading -> Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.models_download_progress,
                                    formatBytes(status.bytes),
                                    formatBytes(status.total),
                                    status.bytes * 100 / status.total,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        is Status.Failed -> CaptionText(
                            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
                            else stringResource(status.messageRes, status.messageArg),
                            error = true,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
    meteredAsk?.let { model ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = null
                EtDownloadManager.start(filesDir, model)
            },
            onDismiss = { meteredAsk = null },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}
