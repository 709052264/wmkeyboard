package com.wasimaster.wmkeyboard.app.updates

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The release notes of the version that is installed, as opposed to the one on
 * offer, which is [AppUpdater.releaseNotes]'s job.
 *
 * The "Updated to" notification says "Tap to see what changed" on every
 * channel, Play and F-Droid included, where no updater has notes to hand. So
 * these come from the APK itself first: the build copies
 * `release-notes/<versionName>.md` into the assets (`bundleReleaseNotes` in
 * `app/build.gradle.kts`). A build cut before its notes were written has no
 * asset, and then the same file is read from the version's tag in the repo.
 */
internal object InstalledReleaseNotes {

    /** Where `bundleReleaseNotes` puts this version's notes in the assets. */
    const val ASSET = "release-notes.md"

    /** This version's notes, or an empty string when there are none anywhere. */
    suspend fun load(context: Context): String = withContext(Dispatchers.IO) {
        bundled(context) ?: fetched() ?: ""
    }

    private fun bundled(context: Context): String? = runCatching {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()?.trim()?.take(GithubReleases.MAX_NOTES_CHARS)?.takeIf { it.isNotBlank() }

    private fun fetched(): String? = runCatching {
        val url = GithubReleases.releaseNotesUrl("v" + BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME)
        ToolHttp.get(url, source = NetSource.UPDATES, route = NetLog.pathOf(url), background = false)
    }.getOrNull()?.trim()?.take(GithubReleases.MAX_NOTES_CHARS)?.takeIf { it.isNotBlank() }
}

/** [ReleaseNotesDialog] for the installed version: a bar while they load, then the notes. */
@Composable
internal fun InstalledReleaseNotesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notes by produceState<String?>(null) { value = InstalledReleaseNotes.load(context.applicationContext) }
    ReleaseNotesDialog(version = BuildConfig.VERSION_NAME, notes = notes, onDismiss = onDismiss)
}
