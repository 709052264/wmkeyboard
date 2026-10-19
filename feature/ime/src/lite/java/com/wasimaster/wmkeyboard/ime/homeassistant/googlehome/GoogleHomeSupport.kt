package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import android.content.Context
import androidx.activity.ComponentActivity
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The structure Google let the app into, as the consent flow reported it. */
data class GoogleHomeGrant(val structureId: String, val structureName: String, val account: String = "", val stamp: Long = 0)

/**
 * The lite flavour carries no Home APIs: a Google Home row can never be
 * added, and one restored from a backup stays unavailable. Same shape as the
 * full flavour's, so the settings app and the hub compile either way.
 */
object GoogleHomeSupport {
    const val AVAILABLE = false

    /** The structure granted in this process, null while none is; the editor waits on it after [requestAccess]. */
    val grants: StateFlow<GoogleHomeGrant?> = MutableStateFlow(null)

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, account: HomeAccount, scope: CoroutineScope): HomeBackend? = null

    /** Must run in an Activity's `onCreate`, before any [requestAccess]. */
    @Suppress("UNUSED_PARAMETER")
    fun register(activity: ComponentActivity) = Unit

    /** Opens Google's account and structure picker; [force] shows it again even with a grant in place. */
    @Suppress("UNUSED_PARAMETER")
    fun requestAccess(activity: ComponentActivity, force: Boolean) = Unit
}
