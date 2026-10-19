package com.wasimaster.wmkeyboard.ime.homeassistant

import android.content.Context
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.OkHttpTransport
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HaBackend
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueBackend
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueTls
import com.wasimaster.wmkeyboard.core.smarthome.openhab.OpenHabBackend
import com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsBackend
import com.wasimaster.wmkeyboard.core.smarthome.switchbot.SwitchBotBackend
import com.wasimaster.wmkeyboard.core.smarthome.tuya.TuyaBackend
import com.wasimaster.wmkeyboard.ime.homeassistant.googlehome.GoogleHomeSupport
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One [HomeBackend] per kind of home, with the keyboard's own HTTP under it:
 * every request through the internet gate and into the network activity log
 * under its platform's name, with the API route and never a query.
 */
object HomeBackends {

    fun create(
        context: Context,
        account: HomeAccount,
        scope: CoroutineScope,
        onTokens: suspend (HaTokens) -> Unit,
        onCertificate: (String) -> Unit,
    ): HomeBackend? = when (account.kind) {
        HomeKind.HOME_ASSISTANT -> HaBackend(
            key = account.key,
            initial = account,
            transport = OkHttpTransport(client(NetSource.HOME_ASSISTANT, readTimeoutS = 0)),
            http = ToolHaHttp(context.cacheDir),
            scope = scope,
            onTokens = onTokens,
        )
        HomeKind.SMARTTHINGS -> SmartThingsBackend(
            key = account.key,
            initial = account,
            http = HomeHttp(client(NetSource.SMARTTHINGS)),
            scope = scope,
            clientId = BuildConfig.SMARTTHINGS_CLIENT_ID,
            clientSecret = BuildConfig.SMARTTHINGS_CLIENT_SECRET,
            onTokens = onTokens,
        )
        HomeKind.HUE -> HueBackend(
            key = account.key,
            initial = account,
            baseClient = client(NetSource.HUE),
            scope = scope,
            onCertificate = onCertificate,
        )
        HomeKind.OPENHAB -> OpenHabBackend(
            key = account.key,
            initial = account,
            http = HomeHttp(client(NetSource.OPENHAB)),
            scope = scope,
        )
        HomeKind.TUYA -> TuyaBackend(
            key = account.key,
            initial = account,
            http = HomeHttp(client(NetSource.TUYA)),
            scope = scope,
        )
        HomeKind.SWITCHBOT -> SwitchBotBackend(
            key = account.key,
            initial = account,
            http = HomeHttp(client(NetSource.SWITCHBOT)),
            scope = scope,
        )
        HomeKind.GOOGLE_HOME -> GoogleHomeSupport.create(context, account, scope)
    }

    /** The build carries the SmartThings OAuth client, so the browser sign-in can be offered. */
    val smartThingsSignIn: Boolean get() = BuildConfig.SMARTTHINGS_CLIENT_ID.isNotBlank() && BuildConfig.SMARTTHINGS_CLIENT_SECRET.isNotBlank()

    /** The blocking requests of a sign-in or a probe, for [source]: the settings app never names OkHttp itself. */
    fun http(source: NetSource): HomeHttp = HomeHttp(client(source))

    /** The same against a Hue bridge, pinned to [certSha256] when one is known; [onSeen] is told the certificate otherwise. */
    fun hueHttp(certSha256: String?, onSeen: (String) -> Unit = {}): HomeHttp = HomeHttp(HueTls.client(client(NetSource.HUE), certSha256, onSeen))

    /** A client for [source]: the gate, the log, and a read timeout of [readTimeoutS] seconds (0 for a stream or a socket). */
    fun client(source: NetSource, readTimeoutS: Long = READ_TIMEOUT_S): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(InternetGate)
        .addNetworkInterceptor(NetLogInterceptor(source, route = { it.encodedPath }))
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(readTimeoutS, TimeUnit.SECONDS)
        .build()

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 30L
}
