package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The rows for native delivery on the Privacy screen's Downloads group, on a
 * build that has it ([NativeDelivery.active]): F-Droid's opt-in, and each
 * vendor licence this build's libraries need. Nothing on any other build.
 */
@Composable
internal fun FeatureLibrariesRows() {
    if (!NativeDelivery.active) return
    val agreements by NativeConsent.agreements.collectAsState()
    if (NativeConsent.downloadsNeedOptIn) {
        var confirmOn by remember { mutableStateOf(false) }
        val on = NativeConsent.DOWNLOADS in agreements
        ToggleSetting(
            R.string.privacy_native_downloads_title,
            stringResource(
                if (on) R.string.privacy_native_downloads_on_subtitle else R.string.privacy_native_downloads_off_subtitle,
            ),
            on,
            info = stringResource(R.string.privacy_native_downloads_info),
        ) { want ->
            if (want) confirmOn = true else NativeConsent.set(NativeConsent.DOWNLOADS, false)
        }
        if (confirmOn) {
            AlertDialog(
                onDismissRequest = { confirmOn = false },
                title = { Text(stringResource(R.string.privacy_native_downloads_confirm_title)) },
                text = { Text(stringResource(R.string.privacy_native_downloads_info)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmOn = false
                        NativeConsent.set(NativeConsent.DOWNLOADS, true)
                    }) { Text(stringResource(R.string.privacy_native_downloads_confirm_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmOn = false }) { Text(stringResource(CommonR.string.common_cancel)) }
                },
            )
        }
    }
    if (NativeDelivery.pins().any { it.licence == NativeConsent.QUALCOMM }) {
        var asking by remember { mutableStateOf(false) }
        val accepted = NativeConsent.QUALCOMM in agreements
        ToggleSetting(
            R.string.privacy_qualcomm_licence_title,
            stringResource(
                if (accepted) R.string.privacy_qualcomm_licence_accepted else R.string.privacy_qualcomm_licence_not_accepted,
            ),
            accepted,
        ) { want ->
            if (want) asking = true else NativeConsent.set(NativeConsent.QUALCOMM, false)
        }
        if (asking) QualcommLicenceDialog(onDone = { asking = false })
    }
}

/**
 * Qualcomm's licence, before the phone fetches the QNN runtime for the NPU.
 * The files come straight from Qualcomm's own artifact on Maven Central, so
 * the user receives them under Qualcomm's terms rather than from this
 * project, and says so here. [onDone] is called with whether they accepted;
 * accepting also starts the download of the shared libraries.
 */
@Composable
internal fun QualcommLicenceDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    // The shared libraries plus one chip's pair: what this phone fetches.
    val bytes = remember {
        val qualcomm = NativeDelivery.pins().filter { it.licence == NativeConsent.QUALCOMM }
        val shared = qualcomm.filter { it.gate == "npu" }.sumOf { it.size }
        val perChip = qualcomm.filter { it.gate != "npu" }.groupBy { it.gate }.values.maxOfOrNull { g -> g.sumOf { it.size } }
        shared + (perChip ?: 0L)
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.privacy_qualcomm_licence_dialog_title)) },
        text = {
            Text(stringResource(R.string.privacy_qualcomm_licence_dialog_body, Formatter.formatShortFileSize(context, bytes)))
        },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(NativeConsent.QUALCOMM, true)
                (NativeDelivery.gate("npu") as? DownloadedModuleGate)?.requestInstall()
                onDone(true)
            }) { Text(stringResource(R.string.privacy_qualcomm_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[NativeConsent.QUALCOMM]?.let { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) { Text(stringResource(R.string.privacy_qualcomm_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}
