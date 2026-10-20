package com.wasimaster.wmkeyboard.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.ime.R
import java.io.File

/**
 * Invisible trampoline for the doc-scan tool: IMEs cannot launch
 * activity-result flows, and ML Kit's document scanner is one (a
 * full-screen Google-provided activity with edge detection, crop and
 * filters). This activity starts the scanner, copies the resulting page
 * JPEGs into the app's private storage and parks them in [pendingPages];
 * the keyboard commits them into the editor when it regains the input
 * connection ([onStartInputView] fires as the target app refocuses).
 */
class DocScanActivity : ComponentActivity() {

    companion object {
        /** Scanned pages waiting for the IME to commit, oldest first. */
        private val pending = ArrayDeque<File>()

        /** Hands over (and clears) whatever the last scan produced. */
        fun consumePendingPages(): List<File> = synchronized(pending) {
            val pages = pending.toList()
            pending.clear()
            pages
        }

        private fun offer(pages: List<File>) = synchronized(pending) {
            pending.clear()
            pending.addAll(pages)
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pages = scan?.pages.orEmpty().mapIndexedNotNull { index, page ->
            runCatching {
                val file = File(scanDir(), "SCAN_${System.currentTimeMillis()}_$index.jpg")
                contentResolver.requireInputStream(page.imageUri).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }.getOrNull()
        }
        if (pages.isNotEmpty()) offer(pages)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Old scans only pile up if the process died before the IME could
        // commit them; keep the directory from growing unbounded.
        scanDir().listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(8)
            ?.forEach { it.delete() }

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                // The scanner module lives in Google Play services and can
                // be missing (or still downloading) on some devices.
                Toast.makeText(
                    this,
                    R.string.ime_docscan_unavailable_error,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
    }

    private fun scanDir(): File = File(filesDir, "docscan").apply { mkdirs() }
}
