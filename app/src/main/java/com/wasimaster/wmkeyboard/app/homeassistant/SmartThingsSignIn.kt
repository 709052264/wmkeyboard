package com.wasimaster.wmkeyboard.app.homeassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HaConnection
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.providerCredential
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttpException
import com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsAuth
import com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsBackend
import com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsMapping
import com.wasimaster.wmkeyboard.ime.homeassistant.HomeBackends
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The SmartThings sign-in, the way [HaSignIn] does Home Assistant's: the
 * browser opens Samsung's login, the hosted `/st/` page hands the code back
 * through `wmkeyboard://st-auth`, and the tokens land on the row's extra.
 * The build's OAuth client does the exchange; without one, only a pasted
 * personal access token works, and SmartThings expires those after a day.
 */
object SmartThingsSignIn {

    sealed interface Status {
        data object Working : Status
        data class SignedIn(val locationName: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    fun clearStatus() {
        _status.value = null
    }

    val available: Boolean get() = HomeBackends.smartThingsSignIn

    private fun http(): HomeHttp = HomeBackends.http(NetSource.SMARTTHINGS)

    fun start(context: Context, repository: SettingsRepository, scope: CoroutineScope, credential: ProviderCredential, isNew: Boolean): Boolean {
        if (!available) return false
        val state = SmartThingsAuth.newState(credential.id)
        val stored = credential.copy(extra = credential.extra + (HomeKeys.AUTH_STATE to state))
        scope.launch {
            repository.saveProviders(ProviderEdits.saveCredential(repository.settings.first(), stored, if (isNew) ProviderTool.SMART_HOME else null))
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SmartThingsAuth.authorizeUrl(BuildConfig.SMARTTHINGS_CLIENT_ID, state))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    fun finish(context: Context, scope: CoroutineScope, callback: SmartThingsAuth.Callback) {
        val app = context.applicationContext
        _status.value = Status.Working
        scope.launch {
            val repository = SettingsRepository(app)
            val id = SmartThingsAuth.credentialOf(callback.state)
            val credential = repository.settings.first().providerCredential(id)
            val expected = credential?.extra?.get(HomeKeys.AUTH_STATE).orEmpty()
            if (credential == null || expected.isEmpty() || SmartThingsAuth.nonceOf(expected) != SmartThingsAuth.nonceOf(callback.state)) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_state_mismatch))
                return@launch
            }
            try {
                val http = http()
                val tokens = SmartThingsAuth.exchange(http, BuildConfig.SMARTTHINGS_CLIENT_ID, BuildConfig.SMARTTHINGS_CLIENT_SECRET, callback.code)
                val location = runCatching { locationName(http, tokens.accessToken) }.getOrNull()
                repository.updateProviderCredential(id) { c ->
                    c.copy(apiKey = "", extra = HaConnection.withTokens(c.extra, tokens) + (location?.let { mapOf(HomeKeys.INSTANCE_NAME to it) } ?: emptyMap()))
                }
                repository.setHaEnabled(true)
                _status.value = Status.SignedIn(location ?: "SmartThings")
            } catch (e: HomeHttpException) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_refused, e.status))
            } catch (e: IOException) {
                _status.value = Status.Failed(e.message ?: app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_unreachable))
            }
        }
    }

    /** A pasted personal access token: checked once against the account's locations, then saved as the row's key. */
    suspend fun useToken(repository: SettingsRepository, credential: ProviderCredential, token: String, isNew: Boolean): Result<String> = runCatching {
        val http = http()
        val location = locationName(http, token.trim()) ?: "SmartThings"
        val stored = credential.copy(apiKey = token.trim(), extra = HaConnection.withoutTokens(credential.extra) + (HomeKeys.INSTANCE_NAME to location))
        repository.saveProviders(ProviderEdits.saveCredential(repository.settings.first(), stored, if (isNew) ProviderTool.SMART_HOME else null))
        repository.setHaEnabled(true)
        location
    }

    suspend fun signOut(repository: SettingsRepository, credential: ProviderCredential) {
        repository.updateProviderCredential(credential.id) { c -> c.copy(apiKey = "", extra = HaConnection.withoutTokens(c.extra)) }
    }

    /** The account's first location, which is what the home is called. */
    private suspend fun locationName(http: HomeHttp, token: String): String? {
        val tokens = HaTokens.longLived(token)
        val body = http.get("${SmartThingsBackend.API}/locations", mapOf("Authorization" to "Bearer ${tokens.accessToken}", "Accept" to "application/json")).require()
        return SmartThingsMapping.parseNamed(body, "locationId", "name").firstOrNull()?.second
    }
}
