package com.wasimaster.wmkeyboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import java.io.File

/**
 * F-Droid stand-in for the doc-scan trampoline. The tool is hidden in that
 * build (no ML Kit), so this never launches a scanner — it exists so
 * the manifest entry and the IME's page pickup keep compiling.
 */
class DocScanActivity : ComponentActivity() {

    companion object {
        /** F-Droid's build never produces scanned pages. */
        fun consumePendingPages(): List<File> = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
