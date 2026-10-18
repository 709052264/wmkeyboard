package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline for the Send files tool's "Files" tile (#729): the
 * document picker is an activity-result flow, which an input method cannot
 * run. The same shape as [GalleryPickActivity], with its own queue so the two
 * tools never take each other's picks.
 *
 * Nothing is copied here. The picks are parked in [consume]'s queue and the
 * keyboard, in the same process and holding the same read grants, adds them
 * to its draft once the app behind it has the field back.
 */
class SendPickActivity : ComponentActivity() {

    /** One pick: a URI this process may read, and what the picker said about it. */
    data class Picked(val uri: Uri, val mime: String, val name: String, val size: Long)

    companion object {
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

        fun intent(context: Context): Intent =
            Intent(context, SendPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        deliver(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restarted activity (rotation, process restore) is already waiting
        // on the picker it started the first time.
        if (savedInstanceState != null) return
        if (runCatching { documents.launch(arrayOf("*/*")) }.isFailure) finish()
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
        return Picked(uri, mime, name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "file" }, size)
    }
}
