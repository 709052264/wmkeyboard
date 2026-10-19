package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri

/**
 * The `EXTRA_STREAM` URIs another app's share is allowed to hand this app.
 *
 * A share runs with this app's own file access, not the sender's. A
 * `file:///data/data/<this package>/…` URI, or a `content://` URI on one of
 * this app's own providers, would read the keyboard's private files (settings,
 * API keys, clipboard history) and send them wherever the share goes, at the
 * request of an app that could never read them itself (#702). So only
 * `content://` URIs from some other app's provider pass; those are readable
 * exactly when that app granted them.
 */
internal object SharedUris {
    fun foreign(context: Context, uris: List<Uri>): List<Uri> = uris.filter { isForeign(context, it) }

    private fun isForeign(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority?.substringAfterLast('@') ?: return false
        // Another app's provider may be invisible to this one (package
        // visibility), which answers null; it is still not ours.
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        return owner != context.packageName
    }
}
