package com.wasimaster.wmkeyboard.app.homeassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wasimaster.wmkeyboard.core.homeassistant.HaAuth
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.HaRest
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.HaUrl
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.homeassistant.ToolHaHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The browser sign-in, both ends: [start] opens Home Assistant's own login
 * page with a fresh `state`, and [finish] takes the code the browser hands
 * back, checks the `state` against the one saved, and trades the code for
 * tokens. The keyboard never sees the user's password; the browser does.
 *
 * [status] is what the tool page shows while this runs and right after.
 */
object HaSignIn {

    sealed interface Status {
        data object Working : Status
        data class SignedIn(val instanceName: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    fun clearStatus() {
        _status.value = null
    }

    /** Opens the browser on the server's authorize page. Returns false when nothing could open it. */
    fun start(context: Context, repository: SettingsRepository, scope: CoroutineScope, base: HaUrl): Boolean {
        val state = HaAuth.newState(base)
        scope.launch { repository.setHaAuthState(state) }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(HaAuth.authorizeUrl(base, state))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    /** The redirect came back: `wmkeyboard://ha-auth?code=…&state=…`. */
    fun finish(context: Context, scope: CoroutineScope, callback: HaAuth.Callback) {
        val app = context.applicationContext
        _status.value = Status.Working
        scope.launch {
            val repository = SettingsRepository(app)
            val settings = repository.settings.first().homeAssistant
            val expected = settings.authState
            if (expected.isEmpty() || HaAuth.nonceOf(expected) != HaAuth.nonceOf(callback.state)) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_state_mismatch))
                return@launch
            }
            val base = HaAuth.baseOf(callback.state) ?: HaUrl.parse(settings.internalUrl) ?: HaUrl.parse(settings.externalUrl)
            if (base == null) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_no_server))
                return@launch
            }
            val http = ToolHaHttp(app.cacheDir)
            try {
                val tokens = withContext(Dispatchers.IO) { HaAuth.exchange(http, base, callback.code) }
                repository.setHaTokens(tokens.accessToken, tokens.refreshToken.orEmpty(), tokens.expiresAtMs, longLived = false)
                repository.setHaEnabled(true)
                val name = learnInstance(repository, http, base, tokens)
                _status.value = Status.SignedIn(name)
            } catch (e: HaHttpException) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_refused, e.status))
            } catch (e: IOException) {
                _status.value = Status.Failed(e.message ?: app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_unreachable))
            }
        }
    }

    /** A pasted long-lived token: checked against the server once, then kept. */
    suspend fun useToken(context: Context, repository: SettingsRepository, base: HaUrl, token: String): Result<String> {
        val app = context.applicationContext
        val http = ToolHaHttp(app.cacheDir)
        val tokens = HaTokens.longLived(token)
        return runCatching {
            withContext(Dispatchers.IO) { HaRest(http, { base }, { tokens.accessToken }).ping() }
            repository.setHaTokens(tokens.accessToken, "", 0L, longLived = true)
            repository.setHaEnabled(true)
            learnInstance(repository, http, base, tokens)
        }
    }

    private suspend fun learnInstance(repository: SettingsRepository, http: ToolHaHttp, base: HaUrl, tokens: HaTokens): String {
        val instance = runCatching { withContext(Dispatchers.IO) { HaRest(http, { base }, { tokens.accessToken }).config() } }.getOrNull()
        val name = instance?.name ?: "Home"
        repository.setHaInstance(name, instance?.uuid.orEmpty())
        return name
    }
}
