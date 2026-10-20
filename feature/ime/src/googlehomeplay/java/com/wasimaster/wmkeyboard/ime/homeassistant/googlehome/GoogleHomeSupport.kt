package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Play builds: the Home APIs SDK (about 22 MB of dex) is the on-demand
 * `:feature:googlehome` split, fetched the first time someone asks for
 * Google's consent screen. Until then this facade reports the module's
 * state and creates no backend; afterwards it reaches [GoogleHomeBridge] by
 * reflection, the way LocalLlmEngine reaches the LiteRT-LM bridge.
 *
 * Consent needs [register] in an Activity's `onCreate`, which the split's
 * classes were not there for when it arrived mid-session: [requestAccess]
 * recreates the activity once the module lands, so the next tap works.
 */
object GoogleHomeSupport {
    const val AVAILABLE = true

    val moduleState: StateFlow<ModuleState> get() = FeatureModules.googleHome.state

    private val mirrored = MutableStateFlow<GoogleHomeGrant?>(null)

    /** The bridge's grants once it is loaded; null until then. */
    val grants: StateFlow<GoogleHomeGrant?> = mirrored.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var runtime: GoogleHomeRuntime? = null

    private fun runtime(context: Context): GoogleHomeRuntime? {
        runtime?.let { return it }
        if (!FeatureModules.googleHome.installed) return null
        return synchronized(this) {
            runtime ?: runCatching {
                FeatureModules.load<GoogleHomeRuntime>(GoogleHomeRuntime.BRIDGE_CLASS, context.applicationContext.classLoader)
            }.getOrNull()?.also { loaded ->
                runtime = loaded
                scope.launch { loaded.grants.collect { mirrored.value = it } }
            }
        }
    }

    fun create(context: Context, account: HomeAccount, scope: CoroutineScope): HomeBackend? =
        runtime(context)?.create(context, account, scope)

    fun register(activity: ComponentActivity) {
        runtime(activity)?.register(activity)
    }

    fun requestAccess(activity: ComponentActivity, force: Boolean) {
        val loaded = runtime(activity)
        if (loaded != null) {
            loaded.requestAccess(activity, force)
            return
        }
        activity.lifecycleScope.launch {
            if (FeatureModules.googleHome.awaitInstalled()) activity.recreate()
        }
    }
}
