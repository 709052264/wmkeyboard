package com.wasimaster.wmkeyboard.core.homeassistant.backend

import com.wasimaster.wmkeyboard.core.homeassistant.HaAssistReply
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The platforms a home can be on. [typeId] is the provider type id the
 * settings store (`ProviderTypes` in `:core:tools` spells the same strings);
 * never rename one.
 */
enum class HomeKind(val typeId: String) {
    HOME_ASSISTANT("home_assistant"),
    GOOGLE_HOME("google_home"),
    SMARTTHINGS("smartthings"),
    HUE("hue"),
    OPENHAB("openhab"),
    TUYA("tuya"),
    SWITCHBOT("switchbot"),
    ;

    companion object {
        fun of(typeId: String): HomeKind? = entries.firstOrNull { it.typeId == typeId }
    }
}

/** What a backend can do beyond listing and switching things. */
enum class HomeFeature {
    /** A typed sentence goes to the platform's own assistant ([HomeBackend.assist]). */
    ASSIST,

    /** A camera still ([HomeBackend.snapshot]). */
    SNAPSHOT,

    /** The platform has a web page or app to open an entity in ([HomeBackend.webUrl]). */
    OPEN_IN_APP,

    /** State arrives as it changes; without this the backend polls while watched. */
    LIVE,
}

/**
 * The keys of a home's `extra` map on its provider credential: what the four
 * plain fields (name, address, key, model) cannot hold. Strings, because the
 * credential store is a string map and a backup must read across versions.
 */
object HomeKeys {
    /** A sign-in's access token (Home Assistant, SmartThings). A pasted long-lived token is the credential's `apiKey` instead. */
    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"

    /** Epoch millis when [ACCESS_TOKEN] stops working. */
    const val EXPIRES_AT = "expires_at"

    /** Home Assistant: the address that works from anywhere, beside the credential's own (home) address. */
    const val EXTERNAL_URL = "external_url"

    /** Home Assistant: `"false"` to try the remote address first. */
    const val PREFER_INTERNAL = "prefer_internal"

    /** What the server calls itself and its stable id, learnt on connect. */
    const val INSTANCE_NAME = "instance_name"
    const val INSTANCE_ID = "instance_id"

    /** The `state` of a browser sign-in under way. */
    const val AUTH_STATE = "auth_state"

    /** Hue: the bridge id and the SHA-256 of the certificate pinned at pairing. */
    const val BRIDGE_ID = "bridge_id"
    const val CERT_SHA256 = "cert_sha256"

    /** Google Home: the structure the user granted, the account, and `"1"` once consent landed. */
    const val STRUCTURE_ID = "structure_id"
    const val ACCOUNT = "account"
    const val GRANTED = "granted"

    /** Tuya: the cloud project's Access ID. Its Access Secret is the credential's `apiKey`, its data centre the address. */
    const val CLIENT_ID = "client_id"

    /** SwitchBot: the secret key that signs each request. The token is the credential's `apiKey`. */
    const val SECRET = "secret"
}

/**
 * One home as the settings describe it: a provider credential of a smart
 * home kind, resolved. [key] is the list row's key, which is what every
 * entity of the home is prefixed with ([HomeRef]).
 */
data class HomeAccount(
    val key: String,
    val kind: HomeKind,
    val name: String = "",
    val url: String = "",
    val apiKey: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    fun extra(key: String): String? = extra[key]?.takeIf { it.isNotBlank() }

    /** Something to connect to. */
    val hasServer: Boolean
        get() = when (kind) {
            HomeKind.GOOGLE_HOME, HomeKind.SMARTTHINGS, HomeKind.SWITCHBOT -> true
            HomeKind.HOME_ASSISTANT -> url.isNotBlank() || extra(HomeKeys.EXTERNAL_URL) != null
            HomeKind.HUE, HomeKind.OPENHAB, HomeKind.TUYA -> url.isNotBlank()
        }

    /** Signed in, paired, or granted, as the kind requires. */
    val signedIn: Boolean
        get() = when (kind) {
            HomeKind.HOME_ASSISTANT, HomeKind.SMARTTHINGS -> apiKey.isNotBlank() || extra(HomeKeys.ACCESS_TOKEN) != null
            HomeKind.GOOGLE_HOME -> extra(HomeKeys.GRANTED) == "1"
            HomeKind.HUE -> apiKey.isNotBlank()
            HomeKind.OPENHAB -> true
            HomeKind.TUYA -> apiKey.isNotBlank() && extra(HomeKeys.CLIENT_ID) != null
            HomeKind.SWITCHBOT -> apiKey.isNotBlank() && extra(HomeKeys.SECRET) != null
        }

    val ready: Boolean get() = hasServer && signedIn

    /** The name shown for the home: the user's, else what the server said, else the kind's. */
    val displayName: String get() = name.ifBlank { extra(HomeKeys.INSTANCE_NAME).orEmpty() }
}

/**
 * The composite id the panel, the favourites, the hidden set and the
 * snippets use once more than one home can be listed: `homeKey|entity_id`.
 * A blank home key leaves the entity id as it is, so a single home never
 * shows a prefix anywhere. Entity ids never contain the separator: Home
 * Assistant's are `domain.object_id`, and every adapter builds its own the
 * same way.
 */
object HomeRef {
    const val SEPARATOR = '|'

    fun of(home: String, entityId: String): String = if (home.isEmpty()) entityId else home + SEPARATOR + entityId

    fun of(entity: HaEntity): String = of(entity.home, entity.entityId)

    /** The home key of [ref], blank when it carries none. */
    fun home(ref: String): String = if (ref.indexOf(SEPARATOR) < 0) "" else ref.substringBeforeLast(SEPARATOR)

    /** The entity id of [ref], prefix dropped. */
    fun native(ref: String): String = ref.substringAfterLast(SEPARATOR)

    fun isRef(s: String): Boolean = s.indexOf(SEPARATOR) >= 0
}

/**
 * One connected home, whatever the platform. The keyboard reads every home
 * through this and nothing else: Home Assistant's own engine implements it
 * directly, and every other platform is an adapter that maps its devices onto
 * [HaEntity] (an HA domain, HA attribute names, [com.wasimaster.wmkeyboard.core.homeassistant.HaFeatures] bits)
 * and a [HaServiceCall] back onto its own commands.
 *
 * Entity ids in [entities] and in calls are the platform's own, without the
 * [HomeRef] prefix; the hub adds and strips it. [start] and [stop] bracket the
 * connection; the flows stay readable after [stop] so a cached panel can draw.
 * [update] hands in the credential as the settings now have it, for a token
 * that was refreshed or an address that changed.
 */
interface HomeBackend {
    val key: String
    val kind: HomeKind
    val features: Set<HomeFeature>

    val phase: StateFlow<HaPhase>
    val entities: StateFlow<Map<String, HaEntity>>

    /** The entity ids each update touched, for the tiles' pulse. */
    val changes: SharedFlow<Set<String>>
    val registry: StateFlow<HaRegistry>
    val instance: StateFlow<HaInstance?>

    /** The last failure worth showing, or null. */
    val error: StateFlow<String?>

    fun start()
    fun stop()

    /** Drop and reconnect now: a Retry button, the network came back. */
    fun refresh()

    /** Forget every entity: the home was removed or re-signed. */
    fun clear()

    /** The credential changed under the backend. Returns whether it now points at another server or sign-in. */
    fun update(account: HomeAccount): Boolean

    /** Runs [call]; throws an [java.io.IOException] (a `HaCommandException` for a refusal) when it could not. */
    suspend fun callService(call: HaServiceCall)

    /** Every state once, fresh from the platform, for a caller that cannot wait for the stream. */
    suspend fun states(): Map<String, HaEntity>

    suspend fun assist(text: String, language: String?, conversationId: String?): HaAssistReply =
        throw UnsupportedOperationException("no assistant")

    suspend fun snapshot(entityId: String): ByteArray = throw UnsupportedOperationException("no camera")

    /** A web address for [entityId], or the home itself when null; null when the platform has none. */
    fun webUrl(entityId: String?): String? = null
}
