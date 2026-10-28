package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.WebFetchClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Save to notes" in another app's share sheet (#997). Shared text becomes a
 * note as it is. A shared link becomes a read-later note: the page's title and
 * its text with the markup taken off, under the link, tagged so they gather in
 * one place; the Read back tool can then read it aloud. A page that cannot be
 * fetched (no connection, the build with no internet, a page that is all
 * script) is still saved, as the link alone.
 *
 * No screen of its own: a toast says what happened, and the work carries on
 * past [finish] in a scope of the process, since a page can take a few
 * seconds and the user has already gone back to what they were reading.
 */
class NoteShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        finish()
        if (text.isEmpty()) return
        val app = applicationContext
        scope.launch {
            val saved = runCatching { save(app, text, subject) }
                .onFailure { DebugLog.e(TAG, "saving a shared note failed", it) }
                .getOrNull()
            withContext(Dispatchers.Main) {
                val message = when (saved) {
                    null -> R.string.notes_share_failed
                    Saved.PAGE -> R.string.notes_share_saved_page
                    Saved.LINK_ONLY -> R.string.notes_share_saved_link
                    Saved.TEXT -> R.string.notes_share_saved
                }
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private enum class Saved { TEXT, PAGE, LINK_ONLY }

    private suspend fun save(context: Context, text: String, subject: String): Saved {
        val url = linkOf(text)
        val store = NoteStore(File(context.filesDir, NoteStore.FILE_PATH))
        val result = if (url == null) {
            store.add(body = text, title = subject.takeIf { it != text }.orEmpty())
            Saved.TEXT
        } else {
            val page = runCatching { WebFetchClient.fetch(url, maxChars = PAGE_CHARS, source = NetSource.NOTES) }.getOrNull()
            val tag = context.getString(R.string.notes_share_read_later_tag)
            store.addTag(tag)
            val title = page?.title?.takeIf { it.isNotBlank() } ?: subject
            val body = if (page != null && page.text.isNotBlank()) "$url\n\n${page.text}" else url
            store.add(body = body, title = title, tags = listOf(tag))
            if (page != null && page.text.isNotBlank()) Saved.PAGE else Saved.LINK_ONLY
        }
        store.save()
        // The keyboard holds the notes in memory and would save over this one.
        SettingsRepository(context).bumpNotesVersion()
        return result
    }

    companion object {
        private const val TAG = "NoteShare"

        /** Enough for a long article; the rest of a page past this is cut. */
        private const val PAGE_CHARS = 200_000

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The share is a link and nothing else: a browser's "Share page", or a link a chat app passed on. */
        internal fun linkOf(text: String): String? {
            val single = text.trim()
            if (single.any { it.isWhitespace() }) return null
            return single.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        }

        private fun alias(context: Context) = ComponentName(context, "com.wasimaster.wmkeyboard.app.NoteShareAlias")

        /** Whether "Save to notes" is in the share sheet. On unless turned off. */
        fun inShareSheet(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(alias(context)) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        fun setInShareSheet(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                alias(context),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
