package com.wasimaster.wmkeyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Where the keyboard's own copies go (#392).
 *
 * Normally that is the system clipboard. While incognito keeps the clipboard
 * private ([isPrivate], set by the service from `incognitoPrivateClipboard`,
 * or always with the clipboard's private copies on, #612), a copy stays here
 * instead, in memory, and the keyboard's paste keys paste it back. Nothing the
 * keyboard copies reaches the system clipboard, where every other app could
 * read it. Leaving the private mode forgets the clip. Outside incognito the
 * service also files it in the clipboard history, through [onPrivateCopy].
 *
 * An object rather than service state because panels copy too (the password
 * generator, the scanner), and they only have a [Context].
 */
object KeyboardClipboard {
    /** Whether copies stay inside the keyboard. Turning it off forgets [held]. */
    @Volatile
    var isPrivate: Boolean = false
        set(value) {
            field = value
            if (!value) held = null
        }

    /** The last private copy, or null. Never set while [isPrivate] is off. */
    @Volatile
    var held: String? = null
        private set

    /**
     * Told of every private copy, so the service can keep it in the history
     * the system clipboard's listener never hears about. Null outside a
     * running service.
     */
    @Volatile
    var onPrivateCopy: ((String) -> Unit)? = null

    /** Copies [text]: into [held] while private, else onto the system clipboard. */
    fun copy(context: Context, text: CharSequence, label: String = "") {
        if (isPrivate) {
            val kept = text.toString()
            held = kept
            onPrivateCopy?.invoke(kept)
            return
        }
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }
}
