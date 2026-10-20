package com.wasimaster.wmkeyboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import java.io.File

/**
 * F-Droid stand-in for the Google scanner's trampoline. This build has no ML
 * Kit, so the keyboard always opens the built-in [PageScanActivity] instead
 * and never this; it exists so the manifest entry and the keyboard's page
 * pickup keep compiling.
 */
class DocScanActivity : ComponentActivity() {

    companion object {
        /** Hands over (and clears) whatever the built-in scanner produced. */
        fun consumePendingPages(): List<File> = DocScanPages.consume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
