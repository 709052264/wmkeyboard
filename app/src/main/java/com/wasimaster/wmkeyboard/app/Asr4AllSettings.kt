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
import com.wasimaster.wmkeyboard.core.voice.asr4all.Asr4AllDownloadManager
import com.wasimaster.wmkeyboard.core.voice.asr4all.Asr4AllDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.asr4all.Asr4AllSize

/**
 * The three asr4all sizes (#289): download, progress, delete. There is no
 * setting for which one dictates: the largest one on the device does, so
 * keeping a size is choosing it, and the screen marks the one in use. Shown
 * only while asr4all is the engine and the device can run it.
 */
@Composable
internal fun Asr4AllModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val states by Asr4AllDownloadManager.states.collectAsState()
    var meteredAsk by remember { mutableStateOf<Asr4AllSize?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Asr4AllDownloadManager.refresh(filesDir) }

    fun requestDownload(size: Asr4AllSize) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> Asr4AllDownloadManager.start(filesDir, size)
            MeteredDecision.ASK -> meteredAsk = size
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    // Read from the states, so the mark moves the moment a download lands.
    val active = Asr4AllSize.entries
        .filter { states[it] is Status.Downloaded }
        .maxByOrNull { it.millions }

    SettingsGroup(
        stringResource(R.string.voice_asr4all_group),
        info = stringResource(R.string.voice_asr4all_model_info),
    ) {
        for (size in Asr4AllSize.entries) {
            item {
                val status = states[size] ?: Status.NotDownloaded
                val title = stringResource(
                    when (size) {
                        Asr4AllSize.SMALL -> R.string.voice_asr4all_small
                        Asr4AllSize.MEDIUM -> R.string.voice_asr4all_medium
                        Asr4AllSize.LARGE -> R.string.voice_asr4all_large
                    },
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    WmRow(
                        title = title,
                        subtitle = when (status) {
                            Status.Downloaded -> stringResource(
                                if (size == active) R.string.voice_asr4all_in_use
                                else R.string.voice_asr4all_kept,
                            )
                            is Status.Paused -> stringResource(R.string.models_paused_progress, formatBytes(status.bytes))
                            else -> stringResource(R.string.voice_asr4all_missing, formatBytes(size.bytes))
                        },
                        trailing = {
                            when (status) {
                                Status.Downloaded -> IconButton(
                                    onClick = { Asr4AllDownloadManager.delete(filesDir, size) },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.models_delete_model_desc, title),
                                    )
                                }
                                is Status.Downloading -> IconButton(onClick = { Asr4AllDownloadManager.cancel() }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.models_cancel_download_desc),
                                    )
                                }
                                is Status.Paused -> TextButton(
                                    onClick = { requestDownload(size) },
                                    enabled = !Asr4AllDownloadManager.isBusy,
                                ) { Text(stringResource(R.string.models_resume_action)) }
                                Status.NotDownloaded, is Status.Failed -> TextButton(
                                    onClick = { requestDownload(size) },
                                    enabled = !Asr4AllDownloadManager.isBusy,
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
    meteredAsk?.let { size ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = null
                Asr4AllDownloadManager.start(filesDir, size)
            },
            onDismiss = { meteredAsk = null },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}
