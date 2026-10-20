package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.settings.GlideEngine
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.futoswipe.FutoSwipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Which decoder reads a glide: the built-in one, or FUTO Swipe's neural models
 * for English on QWERTY. Out of line because the gestures screen's body is
 * past its length budget, and because picking FUTO Swipe is a small flow of
 * its own: the weights licence, the 3.9 MB of models, and the ExecuTorch
 * runtime where the build fetches it.
 *
 * The licence asks for a visible notice that the product is powered by
 * "FUTO Swipe". This row says so whenever that engine is picked, and the
 * About screen credits it.
 */
internal fun SettingsGroupScope.glideEngineRows(
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    item { GlideEngineRow(settings, repository, scope) }
}

@Composable
private fun GlideEngineRow(settings: LiveSettings, repository: SettingsRepository, scope: CoroutineScope) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val engine = settings.watch { it.gesture.engine }
    val status by FutoSwipe.status.collectAsState()
    LaunchedEffect(Unit) { FutoSwipe.refresh(filesDir) }
    var asking by remember { mutableStateOf(false) }
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }

    fun fetch() {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> {
                FutoSwipe.start(filesDir)
                FeatureModules.executorch.requestInstall()
            }
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    fun choose(pick: GlideEngine) {
        scope.launch { repository.setGestureEngine(pick) }
        if (pick == GlideEngine.FUTO_SWIPE && status != FutoSwipe.Status.Downloaded) fetch()
    }

    val subtitle = when {
        engine == GlideEngine.BUILT_IN -> stringResource(R.string.typing_glide_engine_subtitle)
        status is FutoSwipe.Status.Downloading -> (status as FutoSwipe.Status.Downloading).let {
            stringResource(
                R.string.typing_glide_engine_downloading,
                Formatter.formatShortFileSize(context, it.bytes),
                Formatter.formatShortFileSize(context, it.total),
            )
        }
        status == FutoSwipe.Status.Downloaded -> stringResource(R.string.typing_glide_engine_ready)
        else -> stringResource(R.string.typing_glide_engine_missing)
    }
    ChoiceSetting(
        R.string.typing_glide_engine_title,
        subtitle = subtitle,
        info = stringResource(R.string.typing_glide_engine_info),
        options = GlideEngine.entries.map { it to stringResource(it.labelRes) },
        selected = engine,
        default = SettingsDefaults.gesture.engine,
    ) { pick ->
        if (pick == GlideEngine.FUTO_SWIPE && !NativeConsent.has(NativeConsent.FUTO)) {
            asking = true
        } else {
            choose(pick)
        }
    }
    if (engine == GlideEngine.FUTO_SWIPE) {
        // The notice the weights licence asks for, wherever the engine is on.
        CaptionText(stringResource(R.string.typing_glide_engine_powered_by))
        when (val s = status) {
            is FutoSwipe.Status.Failed -> StateBanner(
                if (s.messageArg.isEmpty()) stringResource(s.messageRes) else stringResource(s.messageRes, s.messageArg),
                action = stringResource(CommonR.string.common_retry),
                tone = BannerTone.WARNING,
                onAction = ::fetch,
            )
            FutoSwipe.Status.NotDownloaded -> StateBanner(
                stringResource(R.string.typing_glide_engine_missing),
                action = stringResource(CommonR.string.common_download),
                onAction = ::fetch,
            )
            else -> Unit
        }
        // Where the build fetches the runtime rather than carrying it.
        SpeechModuleBanner(settings, FeatureModules.executorch)
    }
    if (asking) {
        FutoSwipeLicenceDialog { accepted ->
            asking = false
            if (accepted) choose(GlideEngine.FUTO_SWIPE)
        }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                metered = false
                FutoSwipe.start(filesDir)
                FeatureModules.executorch.requestInstall()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/**
 * The FUTO Model Weights License, before the phone fetches the models. They
 * come straight from FUTO's own Hugging Face repository, so the user receives
 * them under FUTO's terms rather than from this project, and agrees here.
 * [onDone] is called with whether they accepted.
 */
@Composable
private fun FutoSwipeLicenceDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.typing_glide_engine_licence_title)) },
        text = {
            Text(
                stringResource(
                    R.string.typing_glide_engine_licence_body,
                    Formatter.formatShortFileSize(context, FutoSwipe.bytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(NativeConsent.FUTO, true)
                onDone(true)
            }) { Text(stringResource(R.string.typing_glide_engine_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[NativeConsent.FUTO]?.let { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }) { Text(stringResource(R.string.typing_glide_engine_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}
