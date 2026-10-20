package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import android.content.Context
import androidx.activity.ComponentActivity
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One consent result. [stamp] makes each result its own value: picking the
 * same home again would otherwise build an equal grant, which a StateFlow
 * drops, and the editor waiting on it would never hear back.
 */
data class GoogleHomeGrant(val structureId: String, val structureName: String, val account: String = "", val stamp: Long = 0)

/**
 * The Google Home APIs as the rest of the app sees them, whichever way a
 * build carries the SDK: compiled in (`src/googlehome` with
 * `src/googlehomedirect`), in the on-demand `:feature:googlehome` split on
 * Play (`src/googlehomeplay` reaches [BRIDGE_CLASS] by reflection), or not
 * at all (the stubs). No SDK type may appear here: on Play the base APK
 * compiles against this interface without the SDK.
 */
interface GoogleHomeRuntime {
    /** The structure granted in this process, null while none is. */
    val grants: StateFlow<GoogleHomeGrant?>

    fun create(context: Context, account: HomeAccount, scope: CoroutineScope): HomeBackend?

    /** Must run in an Activity's `onCreate`, before any [requestAccess]. */
    fun register(activity: ComponentActivity)

    /** Opens Google's account and structure picker; [force] shows it again even with a grant in place. */
    fun requestAccess(activity: ComponentActivity, force: Boolean)

    companion object {
        /** The bridge class in `src/googlehome`, kept for reflection by :app's R8 rules. */
        const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.ime.homeassistant.googlehome.GoogleHomeBridge"
    }
}
