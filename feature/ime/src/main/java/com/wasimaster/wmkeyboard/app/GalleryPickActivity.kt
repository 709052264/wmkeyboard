package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline for the gallery tool's system pickers (#708): an input
 * method cannot run an activity-result flow, and Android's photo picker and
 * document picker are both one.
 *
 * The photo picker is the whole tool in a Play build, which may not hold the
 * library permissions, and the "Browse all" button everywhere else. It needs
 * no permission: what it returns is a read grant on each item picked, held by
 * this app's process. The document picker is the panel's Files button, for
 * anything that is not a photo or a video.
 *
 * Nothing is copied here. The picks are parked in [consume]'s queue and the
 * keyboard, in the same process and holding the same grants, prepares and
 * sends them once the app behind it has the field back (the [DocScanActivity]
 * pattern), so this screen goes away the moment the picker does.
 */
class GalleryPickActivity : ComponentActivity() {

    /** One pick: a URI this process may read, and what the picker said about it. */
    data class Picked(
        val uri: Uri,
        val mime: String,
        val name: String,
        val size: Long,
    )

    companion object {
        private const val EXTRA_FILES = "files"
        private const val EXTRA_LIMIT = "limit"

        private val pending = ArrayDeque<Picked>()

        /** Hands over (and clears) what the last pick produced, oldest first. */
        fun consume(): List<Picked> = synchronized(pending) {
            val out = pending.toList()
            pending.clear()
            out
        }

        fun hasPending(): Boolean = synchronized(pending) { pending.isNotEmpty() }

        private fun offer(picks: List<Picked>) = synchronized(pending) {
            pending.clear()
            pending.addAll(picks)
        }

        /** [files] opens the document picker instead of the photo picker; [limit] caps a multi-pick. */
        fun intent(context: Context, files: Boolean, limit: Int): Intent =
            Intent(context, GalleryPickActivity::class.java)
                .putExtra(EXTRA_FILES, files)
                .putExtra(EXTRA_LIMIT, limit)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val single = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        deliver(listOfNotNull(uri))
    }

    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        deliver(uris)
    }

    // Made in onCreate rather than as a field: its item cap comes from the
    // intent, and registering before STARTED is all the API asks.
    private var multiple: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val limit = intent.getIntExtra(EXTRA_LIMIT, 20).coerceAtLeast(1).coerceAtMost(pickerLimit())
        if (limit > 1) {
            multiple = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(limit)) { deliver(it) }
        }
        super.onCreate(savedInstanceState)
        // A restarted activity (rotation, process restore) is already waiting
        // on the picker it started the first time.
        if (savedInstanceState != null) return
        val launched = runCatching {
            when {
                intent.getBooleanExtra(EXTRA_FILES, false) -> documents.launch(arrayOf("*/*"))
                multiple != null -> multiple?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                else -> single.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }.isSuccess
        if (!launched) finish()
    }

    /** The photo picker's own ceiling on a multi-pick, where it says one. */
    private fun pickerLimit(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { MediaStore.getPickImagesMaxLimit() }.getOrDefault(MAX_FALLBACK)
        } else {
            MAX_FALLBACK
        }

    private fun deliver(uris: List<Uri>) {
        val picks = uris.mapNotNull { uri -> runCatching { describe(uri) }.getOrNull() }
        if (picks.isNotEmpty()) offer(picks)
        finish()
    }

    private fun describe(uri: Uri): Picked {
        var name = ""
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n).orEmpty()
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return Picked(uri, mime, name, size)
    }
}

private const val MAX_FALLBACK = 100
