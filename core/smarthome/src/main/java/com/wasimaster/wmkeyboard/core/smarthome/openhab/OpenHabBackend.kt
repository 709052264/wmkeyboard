package com.wasimaster.wmkeyboard.core.smarthome.openhab

import com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.smarthome.BaseBackend
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.parseJson
import com.wasimaster.wmkeyboard.core.smarthome.str
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * openHAB as a [HomeBackend]: `/rest/items` once, then the server-sent
 * events of item state changes. The API token goes as a bearer; blank
 * means the server lets LAN clients in without one.
 */
class OpenHabBackend(
    key: String,
    initial: HomeAccount,
    private val http: HomeHttp,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : BaseBackend(key, HomeKind.OPENHAB, initial, scope) {

    override val features: Set<HomeFeature> = setOf(HomeFeature.LIVE, HomeFeature.OPEN_IN_APP)

    private val items = ConcurrentHashMap<String, OhItem>()

    private fun base(): String {
        val s = account.url.trim().trimEnd('/')
        if (s.isEmpty()) throw IOException("no server address")
        return if (s.contains("://")) s else "http://$s"
    }

    private fun headers(): Map<String, String> = HomeHttp.bearer(account.apiKey) + ("Accept" to "application/json")

    /** Thrown inside the stream's callback when the item list changed, so the session reloads it. */
    private class Reload : IOException("items changed")

    override suspend fun session() {
        while (true) {
            loadAll()
            live(HaPhase.LIVE)
            try {
                listen()
                return
            } catch (_: Reload) {
                // Round again: the list is stale.
            }
        }
    }

    private suspend fun listen() {
        http.listen("${base()}/rest/events?topics=openhab/items/*/statechanged,openhab/items/*/added,openhab/items/*/removed", headers()) { ev ->
            val o = parseJson(ev.data).obj() ?: return@listen
            val topic = o.str("topic") ?: return@listen
            val payload = o.str("payload") ?: return@listen
            when {
                topic.endsWith("/statechanged") -> OpenHabMapping.stateOfEvent(topic, payload)?.let { (name, value) ->
                    val item = items[name] ?: return@let
                    val next = item.copy(state = value)
                    items[name] = next
                    OpenHabMapping.entity(next, now() / MS_PER_SECOND.toDouble())?.let { merge(mapOf(it.entityId to it)) }
                }
                topic.endsWith("/added") || topic.endsWith("/removed") -> throw Reload()
            }
        }
    }

    private suspend fun loadAll() {
        val list = OpenHabMapping.parseItems(http.get("${base()}/rest/items?recursive=false", headers()).require())
        items.clear()
        list.forEach { items[it.name] = it }
        runCatching { parseJson(http.get("${base()}/rest/", headers()).require()).obj() }.getOrNull()?.let { root ->
            setInstance(HaInstance(name = "openHAB", version = root.str("version") ?: root.obj("runtimeInfo")?.str("version")))
        }
        setRegistry(OpenHabMapping.registry(list))
        publish(OpenHabMapping.entities(list, now() / MS_PER_SECOND.toDouble()))
    }

    override suspend fun callService(call: HaServiceCall) {
        val id = call.entityId ?: throw HaCommandException("no_target", "no entity")
        val item = OpenHabMapping.itemOf(items.values, id) ?: throw HaCommandException("not_found", id)
        val command = OpenHabMapping.command(call, item, entities.value[id]) ?: throw HaCommandException("unsupported", "${call.domain}.${call.service}")
        http.postText("${base()}/rest/items/${item.name}", command, headers()).require()
    }

    override suspend fun states(): Map<String, HaEntity> {
        loadAll()
        return entities.value
    }

    override fun webUrl(entityId: String?): String? = runCatching { base() }.getOrNull()

    private companion object {
        const val MS_PER_SECOND = 1000L
    }
}
