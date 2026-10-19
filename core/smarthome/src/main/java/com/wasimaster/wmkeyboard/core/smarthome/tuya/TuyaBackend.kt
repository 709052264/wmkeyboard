package com.wasimaster.wmkeyboard.core.smarthome.tuya

import com.wasimaster.wmkeyboard.core.homeassistant.HaArea
import com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException
import com.wasimaster.wmkeyboard.core.homeassistant.HaDevice
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntityEntry
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.smarthome.BaseBackend
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttpException
import com.wasimaster.wmkeyboard.core.smarthome.HomeJson
import com.wasimaster.wmkeyboard.core.smarthome.bool
import com.wasimaster.wmkeyboard.core.smarthome.int
import com.wasimaster.wmkeyboard.core.smarthome.num
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.slug
import com.wasimaster.wmkeyboard.core.smarthome.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A refusal Tuya put in a 200 reply: `success` false with its own code and message. */
class TuyaException(val code: Int?, message: String) : IOException(if (code == null) message else "$code: $message")

/**
 * Tuya's request signature (the one in use since mid-2021): an uppercase
 * HMAC-SHA256, keyed with the Access Secret, over the Access ID, the access
 * token once there is one, the time, and a string naming the request.
 */
object TuyaSign {
    /** The method, the body's SHA-256, no signed headers, and the path with its query sorted by key. */
    fun stringToSign(method: String, path: String, query: Map<String, String>, body: String): String {
        val url = if (query.isEmpty()) path else path + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        return method + "\n" + body.encodeUtf8().sha256().hex() + "\n" + "\n" + url
    }

    fun sign(clientId: String, secret: String, accessToken: String, t: Long, stringToSign: String): String =
        (clientId + accessToken + t + stringToSign).encodeUtf8().hmacSha256(secret.encodeUtf8()).hex().uppercase()
}

/** The data centres a Tuya project can live in, as the Tuya Developer Platform names them. */
object TuyaRegions {
    data class Region(val id: String, val label: String, val host: String) {
        val base: String get() = "https://$host"
    }

    val all: List<Region> = listOf(
        Region("eu", "Central Europe", "openapi.tuyaeu.com"),
        Region("eu_w", "Western Europe", "openapi-weaz.tuyaeu.com"),
        Region("us", "Western America", "openapi.tuyaus.com"),
        Region("us_e", "Eastern America", "openapi-ueaz.tuyaus.com"),
        Region("in", "India", "openapi.tuyain.com"),
        Region("sg", "Singapore", "openapi-sg.iotbing.com"),
        Region("cn", "China", "openapi.tuyacn.com"),
    )

    /** The address a credential holds, as a base URL without a trailing slash. */
    fun base(url: String): String {
        val t = url.trim().trimEnd('/')
        return if (t.contains("://")) t else "https://$t"
    }
}

/**
 * Tuya (the Smart Life and Tuya Smart apps) as a [HomeBackend]: the cloud
 * OpenAPI of the user's own Tuya Developer Platform project, with their app
 * account linked to it. Polled while the backend runs, since Tuya's push
 * channel is a Pulsar subscription a phone cannot hold. Every device of the
 * linked account, rooms as areas, tap-to-run scenes as `scene.` entities.
 *
 * The credential carries the project's data centre as its address, its
 * Access Secret as the key and its Access ID in the extra. The access token
 * lasts two hours and is fetched again whenever Tuya calls it invalid; it is
 * never stored.
 */
class TuyaBackend(
    key: String,
    initial: HomeAccount,
    private val http: HomeHttp,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
) : BaseBackend(key, HomeKind.TUYA, initial, scope) {

    override val features: Set<HomeFeature> = emptySet()

    private data class Token(val access: String, val expiresAt: Long)

    @Volatile private var token: Token? = null

    /** Entity id to the device it came from, from the last list. */
    private val devices = ConcurrentHashMap<String, TuyaDevice>()
    private val scenes = ConcurrentHashMap<String, TuyaScene>()

    /** Specifications by device id: they do not change, so each is asked for once. */
    private val specs = ConcurrentHashMap<String, Pair<Map<String, TuyaSpec>, Set<String>>>()

    private var rooms: List<Pair<String, String>> = emptyList()
    private var roomOf: Map<String, String> = emptyMap()

    override fun update(account: HomeAccount): Boolean {
        val changed = super.update(account)
        if (changed) {
            token = null
            specs.clear()
        }
        return changed
    }

    override fun sameConnection(a: HomeAccount, b: HomeAccount): Boolean =
        a.url == b.url && a.apiKey == b.apiKey && a.extra(HomeKeys.CLIENT_ID) == b.extra(HomeKeys.CLIENT_ID)

    // ---- Requests ------------------------------------------------------------------

    private val base: String get() = TuyaRegions.base(account.url)
    private val clientId: String get() = account.extra(HomeKeys.CLIENT_ID).orEmpty().trim()
    private val secret: String get() = account.apiKey.trim()

    private suspend fun send(method: String, path: String, query: Map<String, String>, body: String?, accessToken: String): String {
        val t = now()
        val sign = TuyaSign.sign(clientId, secret, accessToken, t, TuyaSign.stringToSign(method, path, query, body.orEmpty()))
        val headers = buildMap {
            put("client_id", clientId)
            put("sign", sign)
            put("t", t.toString())
            put("sign_method", "HMAC-SHA256")
            put("lang", "en")
            if (accessToken.isNotEmpty()) put("access_token", accessToken)
        }
        val url = (base + path).toHttpUrlOrNull()?.newBuilder()?.apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }?.build()
            ?: throw IOException("not an address: $base")
        val reply = if (method == "POST") http.postJson(url.toString(), body.orEmpty(), headers) else http.get(url.toString(), headers)
        return reply.require()
    }

    /** A fresh token, or the one held while it has a minute left. A refused project is [HomeHttpException] 401, so the home shows as rejected. */
    private suspend fun accessToken(): String {
        token?.takeIf { it.expiresAt - TOKEN_MARGIN_MS > now() }?.let { return it.access }
        val root = HomeJson.parseToJsonElement(send("GET", TOKEN_PATH, mapOf("grant_type" to "1"), null, "")).obj()
            ?: throw IllegalStateException("no reply")
        if (root.bool("success") != true) throw HomeHttpException(HomeHttpException.UNAUTHORIZED, root.str("msg") ?: "token refused")
        val result = root.obj("result") ?: throw IllegalStateException("no token")
        val access = result.str("access_token") ?: throw IllegalStateException("no token")
        val seconds = result.num("expire_time") ?: result.num("expire") ?: DEFAULT_EXPIRY_S
        return Token(access, now() + (seconds * MS_PER_SECOND).toLong()).also { token = it }.access
    }

    /** One signed request; an invalid token is fetched again once. Returns the whole reply, `success` true. */
    private suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): String {
        repeat(2) { attempt ->
            val text = send(method, path, query, body, accessToken())
            val root = HomeJson.parseToJsonElement(text).obj() ?: throw IllegalStateException("not JSON")
            if (root.bool("success") == true) return text
            val code = root.int("code")
            if (code == TOKEN_INVALID && attempt == 0) {
                token = null
                return@repeat
            }
            throw TuyaException(code, root.str("msg") ?: "refused")
        }
        throw TuyaException(null, "token refused twice")
    }

    // ---- Session ----------------------------------------------------------------------

    override suspend fun session() {
        loadAll()
        live(HaPhase.REST_FALLBACK)
        pollForever(pollMs) { loadDevices() }
    }

    private suspend fun loadAll() {
        val list = listDevices()
        val uid = list.firstNotNullOfOrNull { it.uid }
        val homes = uid?.let { runCatching { TuyaMapping.parseHomes(request("GET", "/v1.0/users/$it/homes")) }.getOrNull() }.orEmpty()
        setInstance(HaInstance(name = homes.firstOrNull()?.second ?: "Tuya"))
        val foundRooms = ArrayList<Pair<String, String>>()
        val foundRoomOf = HashMap<String, String>()
        val foundScenes = HashMap<String, TuyaScene>()
        for ((homeId, _) in homes) {
            val homeRooms = runCatching { TuyaMapping.parseRooms(request("GET", "/v1.0/homes/$homeId/rooms")) }.getOrDefault(emptyList())
            for ((roomId, name) in homeRooms) {
                foundRooms += roomId to name
                runCatching { TuyaMapping.parseRoomDevices(request("GET", "/v1.0/homes/$homeId/rooms/$roomId/devices")) }
                    .getOrDefault(emptyList()).forEach { foundRoomOf[it] = roomId }
            }
            runCatching { TuyaMapping.parseScenes(request("GET", "/v1.0/homes/$homeId/scenes"), homeId) }
                .getOrDefault(emptyList()).forEach { foundScenes["scene.tuya_" + slug(it.id)] = it }
        }
        rooms = foundRooms
        roomOf = foundRoomOf
        scenes.clear()
        scenes.putAll(foundScenes)
        publishDevices(list)
    }

    private suspend fun loadDevices() = publishDevices(listDevices())

    /** Every page of the linked accounts' devices, each with its specification. */
    private suspend fun listDevices(): List<TuyaDevice> {
        val all = ArrayList<TuyaDevice>()
        var rowKey: String? = null
        var pages = 0
        do {
            val query = buildMap {
                put("size", PAGE_SIZE.toString())
                rowKey?.let { put("last_row_key", it) }
            }
            val (page, more, next) = TuyaMapping.parseDevicePage(request("GET", DEVICES_PATH, query))
            all += page
            rowKey = next
            pages++
        } while (more && next != null && pages < MAX_PAGES)
        return all.map { d ->
            // A device whose specification is refused keeps an empty one, so a poll does not ask again.
            val spec = specs[d.id] ?: (
                runCatching { TuyaMapping.parseSpecification(request("GET", "/v1.0/devices/${d.id}/specifications")) }.getOrNull()
                    ?: (emptyMap<String, TuyaSpec>() to emptySet())
                ).also { specs[d.id] = it }
            d.copy(specs = spec.first, writable = spec.second)
        }
    }

    private fun publishDevices(list: List<TuyaDevice>) {
        val seconds = now() / MS_PER_SECOND.toDouble()
        val entities = LinkedHashMap<String, HaEntity>()
        val entries = ArrayList<HaEntityEntry>()
        devices.clear()
        for (d in list) {
            for (e in TuyaMapping.entities(d, seconds)) {
                entities[e.entityId] = e
                devices[e.entityId] = d
                entries += HaEntityEntry(e.entityId, areaId = roomOf[d.id], deviceId = d.id)
            }
        }
        for ((id, scene) in scenes) {
            entities[id] = TuyaMapping.sceneEntity(scene, seconds)
            entries += HaEntityEntry(id)
        }
        setRegistry(
            HaRegistry(
                areas = rooms.map { (id, name) -> HaArea(id, name) },
                devices = list.map { HaDevice(it.id, roomOf[it.id], it.name) },
                entries = entries,
            ),
        )
        publish(entities)
    }

    // ---- Commands ------------------------------------------------------------------------

    override suspend fun callService(call: HaServiceCall) {
        val id = call.entityId ?: throw HaCommandException("no_target", "no entity")
        if (call.domain == "scene") {
            val scene = scenes[id] ?: throw HaCommandException("not_found", id)
            request("POST", "/v1.0/homes/${scene.homeId}/scenes/${scene.id}/trigger", body = "")
            return
        }
        val device = devices[id] ?: throw HaCommandException("not_found", id)
        val commands = TuyaMapping.commands(call, id, device, entities.value[id])
        if (commands.isEmpty()) throw HaCommandException("unsupported", "${call.domain}.${call.service}")
        request("POST", "/v1.0/devices/${device.id}/commands", body = TuyaMapping.commandsBody(commands))
        // The cloud takes a moment to hear back from the device; one quick look confirms the tile.
        delay(CONFIRM_DELAY_MS)
        runCatching {
            val fresh = device.copy(status = device.status + TuyaMapping.parseStatus(request("GET", "/v1.0/devices/${device.id}/status")))
            val seconds = now() / MS_PER_SECOND.toDouble()
            val updated = TuyaMapping.entities(fresh, seconds)
            updated.forEach { devices[it.entityId] = fresh }
            merge(updated.associateBy { it.entityId })
        }
    }

    /** The token, then one page of devices: the project's keys work and an app account is linked. */
    internal suspend fun check() {
        request("GET", DEVICES_PATH, mapOf("size" to "1"))
    }

    override suspend fun states(): Map<String, HaEntity> {
        if (devices.isEmpty()) loadAll() else loadDevices()
        return entities.value
    }

    companion object {
        /** The cheapest proof the settings' Test can ask for. */
        suspend fun probe(http: HomeHttp, account: HomeAccount) = TuyaBackend(account.key, account, http, CoroutineScope(Job())).check()

        /** Tuya's trial plan counts API calls by the month; twice a minute while the panel is open stays well inside it. */
        const val POLL_MS = 30_000L
        const val TOKEN_PATH = "/v1.0/token"
        const val DEVICES_PATH = "/v1.0/iot-01/associated-users/devices"

        /** Tuya's "token invalid": the token expired early or the project re-issued it. */
        private const val TOKEN_INVALID = 1010
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 20
        private const val TOKEN_MARGIN_MS = 60_000L
        private const val DEFAULT_EXPIRY_S = 7200.0
        private const val CONFIRM_DELAY_MS = 1_000L
        private const val MS_PER_SECOND = 1000L
    }
}
