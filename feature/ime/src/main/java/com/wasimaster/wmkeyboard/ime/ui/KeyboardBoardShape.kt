package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import androidx.compose.ui.unit.IntSize

/**
 * The shape the docked board last had on screen, one per orientation.
 *
 * The background image is centre-cropped to exactly this box, so it is the
 * shape the image cropper's "Keyboard" frame has to be. A fixed guess (it used
 * to be 2.4:1) is wrong on nearly every device: the board's height follows the
 * key height, the number row, the toolbar and the strip, and its width the
 * width setting and the screen.
 *
 * Measured, not computed: only the live board knows all of that. Kept in
 * device-protected storage so the keyboard can record it before the first
 * unlock, and so the settings app can read it while the keyboard's process is
 * gone.
 */
object KeyboardBoardShape {

    /** Width over height of the board in that orientation, or null before it has been seen. */
    fun aspect(context: Context, landscape: Boolean): Float? {
        val prefs = prefs(context)
        val w = prefs.getInt(widthKey(landscape), 0)
        val h = prefs.getInt(heightKey(landscape), 0)
        return if (w > 0 && h > 0) w.toFloat() / h else null
    }

    /**
     * Records the board's size. Only the keyboard's own window counts: the
     * theme editor hosts the same board in the settings app, at a size of its
     * own choosing, and must not overwrite what the real one measured.
     */
    fun record(context: Context, size: IntSize, landscape: Boolean) {
        if (size.width <= 0 || size.height <= 0) return
        if (!context.isInputMethod()) return
        val prefs = prefs(context)
        if (prefs.getInt(widthKey(landscape), 0) == size.width &&
            prefs.getInt(heightKey(landscape), 0) == size.height
        ) {
            return
        }
        prefs.edit()
            .putInt(widthKey(landscape), size.width)
            .putInt(heightKey(landscape), size.height)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext ?: context
        return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun Context.isInputMethod(): Boolean {
        var current: Context? = this
        while (current != null) {
            if (current is InputMethodService) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun widthKey(landscape: Boolean) = if (landscape) "land_w" else "port_w"
    private fun heightKey(landscape: Boolean) = if (landscape) "land_h" else "port_h"

    private const val PREFS = "keyboard_board_shape"
}
