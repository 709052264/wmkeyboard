package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.ime.docscan.PageScanScreen
import java.io.File

/**
 * The built-in document scanner (OpenCV, no Play services): what the scanner
 * tool opens where Google's scanner is missing (F-Droid, phones without Play
 * services, a failed Google scanner) or switched off in its settings. A full
 * screen of its own, because a keyboard cannot run the camera at this size
 * or take a photo from the picker. The pages go through [DocScanPages]
 * exactly as Google's scanner's do, and the keyboard sends them once the app
 * behind gets its field back.
 *
 * Same launch shape as DocScanActivity: own task affinity so finishing
 * returns to that app rather than to a settings task from recents.
 */
class PageScanActivity : ComponentActivity() {

    companion object {
        /** The data saver's answer for downloads, as a [MeteredDecision] name. */
        private const val EXTRA_DOWNLOADS = "com.wasimaster.wmkeyboard.docscan.DOWNLOADS"

        fun intent(context: Context, downloads: MeteredDecision): Intent =
            Intent(context, PageScanActivity::class.java)
                .putExtra(EXTRA_DOWNLOADS, downloads.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DocScanPages.prune(this)
        // Started without the keyboard's word (DocScanActivity's fallback),
        // the download asks first, as data saver's "ask" would.
        val downloads = intent?.getStringExtra(EXTRA_DOWNLOADS)
            ?.let { runCatching { MeteredDecision.valueOf(it) }.getOrNull() }
            ?: MeteredDecision.ASK
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PageScanScreen(
                    downloads = downloads,
                    onDone = ::finishWith,
                    onCancel = ::finish,
                    // The F-Droid download opt-in, which the library is waiting for.
                    onOpenSettings = {
                        startActivity(
                            MainActivityContract.intent(this)
                                .putExtra(MainActivityContract.EXTRA_OPEN_SETTING, "privacy_native_downloads_title"),
                        )
                        finish()
                    },
                )
            }
        }
    }

    private fun finishWith(pages: List<File>) {
        if (pages.isNotEmpty()) DocScanPages.offer(pages)
        finish()
    }
}
