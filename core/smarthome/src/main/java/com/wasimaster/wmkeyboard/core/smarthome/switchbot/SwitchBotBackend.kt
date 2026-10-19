package com.wasimaster.wmkeyboard.core.smarthome.switchbot

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
import com.wasimaster.wmkeyboard.core.smarthome.HomeJson
import com.wasimaster.wmkeyboard.core.smarthome.int
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.slug
import com.wasimaster.wmkeyboard.core.smarthome.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A refusal SwitchBot put in a 200 reply: a `statusCode` other than 100. */
class SwitchBotException(val code: Int?, message: String) : IOException(if (code == null) message else "$code: $message") {
    /** The device, or the hub it talks through, is out of reach. */
    val offline: Boolean get() = code == DEVICE_OFFLINE || code == HUB_OFFLINE

    companion object {
        const val DEVICE_OFFLINE = 161
        const val HUB_OFFLINE = 171
    }
}

/** SwitchBot's v1.1 signature: Base64 of HMAC-SHA256 over token, time and nonce, keyed with the secret. */
object SwitchBotSign {
    fun sign(token: String, secret: String, t: Long, nonce: String): String =
        (token + t + nonce).encodeUtf8().hmacSha256(secret.encodeUtf8()).base64()
}

/**
 * SwitchBot as a [HomeBackend]: the cloud API v1.1 with the token and secret
 * key from the SwitchBot app's developer options. Polled while the backend
 * runs: the API's webhook needs a public server. Physical devices, the
 * infrared remotes a hub learnt (drawn as switches that remember what the
 * keyboard last sent), and manual scenes. SwitchBot has no rooms.
 *
 * The API allows 10,000 calls a day per account, and a status is one call
 * per device, so the poll is once a minute and skips what reports nothing.
 */
class SwitchBotBackend(
    key: String,
    initial: HomeAccount,
    private val http: HomeHttp,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val base: String = API,
) : BaseBackend(key, HomeKind.SWITCHBOT, initial, scope) {

    override val features: Set<HomeFeature> = emptySet()

    /** Entity id to the device it came from. */
    private val devices = ConcurrentHashMap<String, SbDevice>()

    /** The devices by id with their last status, in list order. */
    private val byId = LinkedHashMap<String, SbDevice>()
    private val scenes = ConcurrentHashMap<String, SbScene>()

    /** What the keyboard last sent each infrared remote. */
    private val assumed = ConcurrentHashMap<String, Boolean>()

    override fun sameConnection(a: HomeAccount, b: HomeAccount): Boolean = a.apiKey == b.apiKey && a.extra(HomeKeys.SECRET) == b.extra(HomeKeys.SECRET)

    private fun headers(): Map<String, String> {
        val token = account.apiKey.trim()
        val secret = account.extra(HomeKeys.SECRET).orEmpty().trim()
        val t = now()
        val nonce = UUID.randomUUID().toString()
        return mapOf(
            "Authorization" to token,
            "sign" to SwitchBotSign.sign(token, secret, t, nonce),
            "t" to t.toString(),
            "nonce" to nonce,
        )
    }

    /** The reply of one call, `statusCode` 100; a 401 (a bad token, or the day's calls used up) throws through [com.wasimaster.wmkeyboard.core.smarthome.HomeReply.require]. */
    private fun checked(text: String): String {
        val root = HomeJson.parseToJsonElement(text).obj() ?: throw IllegalStateException("not JSON")
        val code = root.int("statusCode")
        if (code != OK) throw SwitchBotException(code, root.str("message") ?: "refused")
        return text
    }

    private suspend fun get(path: String): String = checked(http.get("$base$path", headers()).require())

    private suspend fun post(path: String, json: String): String = checked(http.postJson("$base$path", json, headers()).require())

    // ---- Session -------------------------------------------------------------------

    override suspend fun session() {
        loadAll()
        live(HaPhase.REST_FALLBACK)
        pollForever(pollMs) { loadStatuses() }
    }

    private suspend fun loadAll() {
        val list = SwitchBotMapping.parseDevices(get("/devices"))
        setInstance(HaInstance(name = "SwitchBot"))
        val found = runCatching { SwitchBotMapping.parseScenes(get("/scenes")) }.getOrDefault(emptyList())
        scenes.clear()
        found.forEach { scenes["scene.sb_" + slug(it.id)] = it }
        synchronized(byId) {
            val previous = HashMap(byId)
            byId.clear()
            list.forEach { d -> byId[d.id] = previous[d.id]?.let { d.copy(status = it.status, offline = it.offline) } ?: d }
        }
        loadStatuses()
    }

    private suspend fun loadStatuses() {
        val list = synchronized(byId) { byId.values.toList() }
        val fresh = list.map { d ->
            if (!SwitchBotMapping.reportsStatus(d)) return@map d
            try {
                d.copy(status = SwitchBotMapping.parseStatus(get("/devices/${d.id}/status")), offline = false)
            } catch (e: SwitchBotException) {
                if (e.offline) d.copy(offline = true) else d
            }
        }
        synchronized(byId) { fresh.forEach { byId[it.id] = it } }
        publishDevices(fresh)
    }

    private fun publishDevices(list: List<SbDevice>) {
        val seconds = now() / MS_PER_SECOND.toDouble()
        val entities = LinkedHashMap<String, HaEntity>()
        val entries = ArrayList<HaEntityEntry>()
        devices.clear()
        for (d in list) {
            for (e in SwitchBotMapping.entities(d, seconds, assumed)) {
                entities[e.entityId] = e
                devices[e.entityId] = d
                entries += HaEntityEntry(e.entityId, deviceId = d.id)
            }
        }
        for ((id, scene) in scenes) {
            entities[id] = SwitchBotMapping.sceneEntity(scene, seconds)
            entries += HaEntityEntry(id)
        }
        setRegistry(HaRegistry(devices = list.map { HaDevice(it.id, null, it.name) }, entries = entries))
        publish(entities)
    }

    // ---- Commands ----------------------------------------------------------------------

    override suspend fun callService(call: HaServiceCall) {
        val id = call.entityId ?: throw HaCommandException("no_target", "no entity")
        if (call.domain == "scene") {
            val scene = scenes[id] ?: throw HaCommandException("not_found", id)
            post("/scenes/${scene.id}/execute", "{}")
            return
        }
        val device = devices[id] ?: throw HaCommandException("not_found", id)
        val commands = SwitchBotMapping.commands(call, id, device, entities.value[id])
        if (commands.isEmpty()) throw HaCommandException("unsupported", "${call.domain}.${call.service}")
        for (c in commands) post("/devices/${device.id}/commands", c.json())
        if (device.remote) {
            SwitchBotMapping.assumedAfter(commands)?.let { assumed[device.id] = it }
            publishDevices(synchronized(byId) { byId.values.toList() })
            return
        }
        // A Bluetooth device answers through its hub; give it a moment, then one status confirms the tile.
        delay(CONFIRM_DELAY_MS)
        runCatching {
            val fresh = device.copy(status = SwitchBotMapping.parseStatus(get("/devices/${device.id}/status")), offline = false)
            val all = synchronized(byId) {
                byId[fresh.id] = fresh
                byId.values.toList()
            }
            publishDevices(all)
        }
    }

    /** One device list: the token and secret are accepted. */
    internal suspend fun check() {
        get("/devices")
    }

    override suspend fun states(): Map<String, HaEntity> {
        if (devices.isEmpty()) loadAll() else loadStatuses()
        return entities.value
    }

    companion object {
        /** The cheapest proof the settings' Test can ask for. */
        suspend fun probe(http: HomeHttp, account: HomeAccount) = SwitchBotBackend(account.key, account, http, CoroutineScope(Job())).check()

        const val API = "https://api.switch-bot.com/v1.1"
        const val POLL_MS = 60_000L
        private const val OK = 100
        private const val CONFIRM_DELAY_MS = 2_000L
        private const val MS_PER_SECOND = 1000L
    }
}
