package com.wasimaster.wmkeyboard.app.homeassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wasimaster.wmkeyboard.core.homeassistant.HaAuth
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaRest
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.HaUrl
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HaConnection
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.providerCredential
import com.wasimaster.wmkeyboard.ime.homeassistant.ToolHaHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A home's credential as the backends read it; the row key is the credential id. */
internal fun ProviderCredential.homeAccount(): HomeAccount = HomeAccount(
    key = id,
    kind = HomeKind.of(typeId) ?: HomeKind.HOME_ASSISTANT,
    name = name,
    url = url,
    apiKey = apiKey,
    extra = extra,
)

/**
 * The Home Assistant browser sign-in, both ends, on a provider credential:
 * [start] saves the row with a fresh `state` and opens Home Assistant's own
 * login page; [finish] takes the code the browser hands back, checks the
 * `state` against the one saved on that row, and trades the code for tokens
 * written into the row's extra. The keyboard never sees the user's
 * password; the browser does.
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

    /** The home address of [credential], as typed, or null when nothing parses. */
    fun baseOf(credential: ProviderCredential): HaUrl? = HaConnection.candidates(credential.homeAccount()).firstOrNull()

    /**
     * Saves [credential] (adding it to the list when [isNew]) with a fresh
     * sign-in state, and opens the browser on the server's authorize page.
     * Returns false when nothing could open it.
     */
    fun start(context: Context, repository: SettingsRepository, scope: CoroutineScope, credential: ProviderCredential, isNew: Boolean): Boolean {
        val base = baseOf(credential) ?: return false
        val state = HaAuth.newState(base, credential.id)
        val stored = credential.copy(extra = credential.extra + (HomeKeys.AUTH_STATE to state))
        scope.launch {
            repository.saveProviders(ProviderEdits.saveCredential(repository.settings.first(), stored, if (isNew) ProviderTool.SMART_HOME else null))
        }
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
            val id = HaAuth.credentialOf(callback.state)
            val credential = repository.settings.first().providerCredential(id)
            val expected = credential?.extra?.get(HomeKeys.AUTH_STATE).orEmpty()
            if (credential == null || expected.isEmpty() || HaAuth.nonceOf(expected) != HaAuth.nonceOf(callback.state)) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_state_mismatch))
                return@launch
            }
            val base = HaAuth.baseOf(callback.state) ?: baseOf(credential)
            if (base == null) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_no_server))
                return@launch
            }
            val http = ToolHaHttp(app.cacheDir)
            try {
                val tokens = withContext(Dispatchers.IO) { HaAuth.exchange(http, base, callback.code) }
                val instance = runCatching { withContext(Dispatchers.IO) { HaRest(http, { base }, { tokens.accessToken }).config() } }.getOrNull()
                repository.updateProviderCredential(id) { c ->
                    c.copy(apiKey = "", extra = HaConnection.withTokens(c.extra, tokens) + learnt(instance))
                }
                repository.setHaEnabled(true)
                _status.value = Status.SignedIn(instance?.name ?: credential.name.ifBlank { "Home" })
            } catch (e: HaHttpException) {
                _status.value = Status.Failed(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_refused, e.status))
            } catch (e: IOException) {
                _status.value = Status.Failed(e.message ?: app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_unreachable))
            }
        }
    }

    /**
     * A pasted long-lived token: checked against the server once, then
     * saved as the row's key (added to the list when [isNew]). Returns the
     * server's name.
     */
    suspend fun useToken(context: Context, repository: SettingsRepository, credential: ProviderCredential, token: String, isNew: Boolean): Result<String> {
        val app = context.applicationContext
        val base = baseOf(credential) ?: return Result.failure(IOException(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_no_server)))
        val http = ToolHaHttp(app.cacheDir)
        val tokens = HaTokens.longLived(token)
        return runCatching {
            val rest = HaRest(http, { base }, { tokens.accessToken })
            withContext(Dispatchers.IO) { rest.ping() }
            val instance = runCatching { withContext(Dispatchers.IO) { rest.config() } }.getOrNull()
            val stored = credential.copy(apiKey = tokens.accessToken, extra = HaConnection.withoutTokens(credential.extra) + learnt(instance))
            repository.saveProviders(ProviderEdits.saveCredential(repository.settings.first(), stored, if (isNew) ProviderTool.SMART_HOME else null))
            repository.setHaEnabled(true)
            instance?.name ?: "Home"
        }
    }

    /** Asks the server its name and version with what the row holds now. */
    suspend fun test(context: Context, credential: ProviderCredential): Result<HaInstance> {
        val app = context.applicationContext
        val base = baseOf(credential) ?: return Result.failure(IOException(app.getString(com.wasimaster.wmkeyboard.R.string.ha_sign_in_no_server)))
        val token = HaConnection.tokens(credential.homeAccount())?.accessToken
            ?: return Result.failure(IOException(app.getString(com.wasimaster.wmkeyboard.R.string.ha_banner_not_signed_in)))
        return runCatching { withContext(Dispatchers.IO) { HaRest(ToolHaHttp(app.cacheDir), { base }, { token }).config() } }
    }

    /** Signing out: the browser key is revoked on the server, and the row keeps only its addresses. */
    suspend fun signOut(context: Context, repository: SettingsRepository, credential: ProviderCredential) {
        val app = context.applicationContext
        val tokens = HaConnection.tokens(credential.homeAccount())
        val base = baseOf(credential)
        if (base != null && tokens != null && !tokens.longLived && tokens.refreshToken != null) {
            runCatching { withContext(Dispatchers.IO) { HaAuth.revoke(ToolHaHttp(app.cacheDir), base, tokens) } }
        }
        repository.updateProviderCredential(credential.id) { c -> c.copy(apiKey = "", extra = HaConnection.withoutTokens(c.extra)) }
    }

    private fun learnt(instance: HaInstance?): Map<String, String> = buildMap {
        instance?.name?.takeIf { it.isNotBlank() }?.let { put(HomeKeys.INSTANCE_NAME, it) }
        instance?.uuid?.let { put(HomeKeys.INSTANCE_ID, it) }
    }
}
