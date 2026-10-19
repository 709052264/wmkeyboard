package com.wasimaster.wmkeyboard.core.smarthome.hue

import com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.smarthome.BaseBackend
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.objects
import com.wasimaster.wmkeyboard.core.smarthome.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * A Philips Hue bridge as a [HomeBackend]: CLIP v2 over the bridge's own
 * TLS, pinned to the certificate pairing saw, and its event stream for live
 * state. Lights, room group lights, scenes, and the motion, contact,
 * temperature, light level and battery resources of Hue sensors.
 */
class HueBackend(
    key: String,
    initial: HomeAccount,
    baseClient: OkHttpClient,
    scope: CoroutineScope,
    /** Told the certificate seen on a connection with no pin yet, to store it. */
    private val onCertificate: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : BaseBackend(key, HomeKind.HUE, initial, scope) {

    override val features: Set<HomeFeature> = setOf(HomeFeature.LIVE)

    private val base: String get() = HueUrl.base(account.url) ?: ""

    @Volatile private var pin: String? = initial.extra(HomeKeys.CERT_SHA256)

    private val http = HomeHttp(
        HueTls.client(baseClient, pin) { seen ->
            if (pin == null) {
                pin = seen
                onCertificate(seen)
            }
        },
    )
    private val api = HueApi(http, { base }, { account.apiKey })

    @Volatile private var model = HueModel()

    override fun sameConnection(a: HomeAccount, b: HomeAccount): Boolean = a.url == b.url && a.apiKey == b.apiKey

    override suspend fun session() {
        if (base.isEmpty()) throw IOException("no bridge address")
        loadAll()
        live(HaPhase.LIVE)
        api.listen(::onEvent)
    }

    private suspend fun loadAll() {
        api.bridge()?.let { b -> setInstance(HaInstance(name = b.str("bridge_id")?.let { "Hue bridge" } ?: "Hue bridge", uuid = b.str("bridge_id"))) }
        var m = HueModel()
        for (type in HueMapping.LOADED_TYPES) {
            val list = runCatching { api.resources(type) }.getOrElse { e ->
                // A bridge too old for a resource type answers 404; the lights are what matter.
                if (type == "light" || type == "device" || type == "room") throw e
                emptyList()
            }
            for (r in list) {
                val id = r.str("id") ?: continue
                m = m.with(type, id, if (type in HueMapping.SENSOR_TYPES) withType(r, type) else r)
            }
        }
        model = m
        setRegistry(HueMapping.registry(m))
        publish(HueMapping.entities(m, now() / MS_PER_SECOND.toDouble()))
    }

    /** Sensor resources are kept with their type, since one map holds them all. */
    private fun withType(r: JsonObject, type: String): JsonObject = if (r.str("type") == type) r else JsonObject(r + ("type" to kotlinx.serialization.json.JsonPrimitive(type)))

    /** One event of the stream: `update`, `add` or `delete`, each with a `data` array of partial resources. */
    private fun onEvent(event: JsonObject) {
        val kind = event.str("type") ?: return
        var m = model
        var structural = false
        for (r in event.objects("data")) {
            val id = r.str("id") ?: continue
            val type = r.str("type") ?: continue
            when (kind) {
                "delete" -> {
                    m = m.without(type, id)
                    structural = true
                }
                "add" -> {
                    m = m.with(type, id, r)
                    structural = true
                }
                else -> {
                    val current = m.find(type, id) ?: continue
                    m = m.with(type, id, HueMapping.merged(current, r))
                    if (type == "room" || type == "device") structural = true
                }
            }
        }
        if (m === model) return
        model = m
        if (structural) setRegistry(HueMapping.registry(m))
        publish(HueMapping.entities(m, now() / MS_PER_SECOND.toDouble()))
    }

    override suspend fun callService(call: HaServiceCall) {
        val id = call.entityId ?: throw HaCommandException("no_target", "no entity")
        val (type, rid) = HueMapping.resourceOf(model, id) ?: throw HaCommandException("not_found", id)
        when (type) {
            "light", "grouped_light" -> {
                val body = HueMapping.lightBody(call, entities.value[id]) ?: throw HaCommandException("unsupported", "${call.domain}.${call.service}")
                api.put(type, rid, body)
            }
            "scene" -> {
                if (call.service != "turn_on") throw HaCommandException("unsupported", call.service)
                api.put("scene", rid, HueMapping.sceneBody())
            }
            else -> throw HaCommandException("unsupported", "${call.domain}.${call.service}")
        }
    }

    override suspend fun states(): Map<String, HaEntity> {
        loadAll()
        return entities.value
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
    }
}
