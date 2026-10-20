package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import android.content.Context
import androidx.activity.ComponentActivity
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * [GoogleHomeSdk] behind the [GoogleHomeRuntime] seam: the class the Play
 * facade builds by reflection once the `:feature:googlehome` split is
 * installed ([GoogleHomeRuntime.BRIDGE_CLASS]). Builds that compile the SDK
 * in call [GoogleHomeSdk] straight from `src/googlehomedirect`.
 */
class GoogleHomeBridge : GoogleHomeRuntime {
    override val grants: StateFlow<GoogleHomeGrant?> get() = GoogleHomeSdk.grants

    override fun create(context: Context, account: HomeAccount, scope: CoroutineScope): HomeBackend? =
        GoogleHomeSdk.create(context, account, scope)

    override fun register(activity: ComponentActivity) = GoogleHomeSdk.register(activity)

    override fun requestAccess(activity: ComponentActivity, force: Boolean) = GoogleHomeSdk.requestAccess(activity, force)
}
