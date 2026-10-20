package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import android.content.Context
import androidx.activity.ComponentActivity
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A build without the Home APIs SDK (a checkout that lacks it, or F-Droid's):
 * a Google Home row can never be added, and one restored from a backup stays
 * unavailable. Same shape as the real one, so the settings app and the hub
 * compile either way.
 */
object GoogleHomeSupport {
    const val AVAILABLE = false

    /** Never anything to fetch: there is no Google Home in this build. */
    val moduleState: StateFlow<ModuleState> = MutableStateFlow(ModuleState.Installed)

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
