package com.wasimaster.wmkeyboard.core.smarthome

import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Attributes for an adapter's [HaEntity], built the way Home Assistant names
 * them. A null value is left out, so a mapping can pass whatever it read.
 */
internal class HomeAttrs(name: String) {
    val map = LinkedHashMap<String, JsonElement>().apply { put("friendly_name", JsonPrimitive(name)) }

    fun put(key: String, v: Any?) {
        when (v) {
            null -> Unit
            is String -> map[key] = JsonPrimitive(v)
            is Number -> map[key] = JsonPrimitive(v)
            is Boolean -> map[key] = JsonPrimitive(v)
            is JsonElement -> map[key] = v
            is List<*> -> map[key] = JsonArray(v.map { x -> if (x is Number) JsonPrimitive(x) else JsonPrimitive(x.toString()) })
            else -> map[key] = JsonPrimitive(v.toString())
        }
    }
}

/** One entity of an adapter: [changed] in epoch seconds stands for both of HA's timestamps. */
internal fun homeEntity(entityId: String, name: String, state: String, changed: Double, attrs: HomeAttrs.() -> Unit = {}): HaEntity =
    HaEntity(
        entityId = entityId,
        state = state,
        attributes = JsonObject(HomeAttrs(name).apply(attrs).map),
        lastChanged = changed,
        lastUpdated = changed,
    )

/** A number as a state string: `21` for a whole one, `21.5` otherwise. */
internal fun trimNumber(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
