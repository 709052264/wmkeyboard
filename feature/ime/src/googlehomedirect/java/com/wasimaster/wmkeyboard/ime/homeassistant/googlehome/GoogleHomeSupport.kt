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
 * A build off Play that carries the Home APIs SDK: [GoogleHomeSdk]
 * compiled in, nothing to fetch. Same shape as the stubs and the Play facade.
 */
object GoogleHomeSupport {
    const val AVAILABLE = true

    val moduleState: StateFlow<ModuleState> = MutableStateFlow(ModuleState.Installed)

    val grants: StateFlow<GoogleHomeGrant?> get() = GoogleHomeSdk.grants

    fun create(context: Context, account: HomeAccount, scope: CoroutineScope): HomeBackend? =
        GoogleHomeSdk.create(context, account, scope)

    fun register(activity: ComponentActivity) = GoogleHomeSdk.register(activity)

    fun requestAccess(activity: ComponentActivity, force: Boolean) = GoogleHomeSdk.requestAccess(activity, force)
}
