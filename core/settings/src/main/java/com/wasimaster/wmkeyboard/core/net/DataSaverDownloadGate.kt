package com.wasimaster.wmkeyboard.core.net

import android.content.Context
import com.wasimaster.wmkeyboard.core.download.DownloadGate
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.DataSaverSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredPolicy

/**
 * Data saving's say over a download already running. The question before it
 * starts is asked by the screen (Allow, Ask each time, Turn off); this is for
 * the connection changing underneath it, say Wi-Fi dropping to mobile data
 * halfway through a 600 MB model. With the **Downloads** row on Turn off and
 * data saving now on, the next request stops and what arrived is kept for when
 * the user is back on a connection data saving leaves alone.
 *
 * Ask each time lets a running download go on: the user answered for it when
 * it started, or it started where nobody needed asking.
 */
object DataSaverDownloadGate : DownloadGate {

    /** Published by the settings repository with the rest of what it mirrors process-wide. */
    @Volatile
    var settings: DataSaverSettings = DataSaverSettings()

    @Volatile
    private var context: Context? = null

    /** Makes this [FileDownloader]'s gate. Called from the application, once per process. */
    fun attach(context: Context) {
        this.context = context.applicationContext
        FileDownloader.gate = this
    }

    override fun allows(source: NetSource): Boolean {
        val now = settings
        if (now.downloads != MeteredPolicy.BLOCK) return true
        val context = context ?: return true
        return !now.appliesTo(NetworkWatcher.readNow(context))
    }
}
