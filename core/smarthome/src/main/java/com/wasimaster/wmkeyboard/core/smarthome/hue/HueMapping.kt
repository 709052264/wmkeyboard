package com.wasimaster.wmkeyboard.core.smarthome.hue

import com.wasimaster.wmkeyboard.core.homeassistant.HaArea
import com.wasimaster.wmkeyboard.core.homeassistant.HaDevice
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntityEntry
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaTime
import com.wasimaster.wmkeyboard.core.smarthome.HomeJson
import com.wasimaster.wmkeyboard.core.smarthome.arr
import com.wasimaster.wmkeyboard.core.smarthome.bool
import com.wasimaster.wmkeyboard.core.smarthome.int
import com.wasimaster.wmkeyboard.core.smarthome.num
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.objects
import com.wasimaster.wmkeyboard.core.smarthome.prim
import com.wasimaster.wmkeyboard.core.smarthome.slug
import com.wasimaster.wmkeyboard.core.smarthome.str
import com.wasimaster.wmkeyboard.core.smarthome.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Everything the bridge lists, joined: lights to their devices, devices to
 * rooms, scenes to their rooms, sensors to their devices. CLIP v2 resources
 * carry an `owner` (the device) and rooms carry `children` (devices), which
 * is how an entity finds its area.
 */
class HueModel(
    val lights: Map<String, JsonObject> = emptyMap(),
    val groupedLights: Map<String, JsonObject> = emptyMap(),
    val devices: Map<String, JsonObject> = emptyMap(),
    val rooms: Map<String, JsonObject> = emptyMap(),
    val scenes: Map<String, JsonObject> = emptyMap(),
    val sensors: Map<String, JsonObject> = emptyMap(),
) {
    /** Device id to room id, from every room's children. */
    val roomOfDevice: Map<String, String> = buildMap {
        for ((roomId, room) in rooms) {
            room.objects("children").forEach { c -> if (c.str("rtype") == "device") c.str("rid")?.let { put(it, roomId) } }
        }
    }

    /** The room a grouped light controls, from its owner. */
    fun roomOfGroupedLight(id: String): String? = groupedLights[id]?.obj("owner")?.takeIf { it.str("rtype") == "room" }?.str("rid")

    fun ownerDevice(resource: JsonObject): String? = resource.obj("owner")?.takeIf { it.str("rtype") == "device" }?.str("rid")

    /** With [resource] replaced or added in whichever map holds its type. */
    fun with(type: String, id: String, resource: JsonObject): HueModel = when (type) {
        "light" -> HueModel(lights + (id to resource), groupedLights, devices, rooms, scenes, sensors)
        "grouped_light" -> HueModel(lights, groupedLights + (id to resource), devices, rooms, scenes, sensors)
        "device" -> HueModel(lights, groupedLights, devices + (id to resource), rooms, scenes, sensors)
        "room" -> HueModel(lights, groupedLights, devices, rooms + (id to resource), scenes, sensors)
        "scene" -> HueModel(lights, groupedLights, devices, rooms, scenes + (id to resource), sensors)
        in HueMapping.SENSOR_TYPES -> HueModel(lights, groupedLights, devices, rooms, scenes, sensors + (id to resource))
        else -> this
    }

    fun without(type: String, id: String): HueModel = when (type) {
        "light" -> HueModel(lights - id, groupedLights, devices, rooms, scenes, sensors)
        "grouped_light" -> HueModel(lights, groupedLights - id, devices, rooms, scenes, sensors)
        "device" -> HueModel(lights, groupedLights, devices - id, rooms, scenes, sensors)
        "room" -> HueModel(lights, groupedLights, devices, rooms - id, scenes, sensors)
        "scene" -> HueModel(lights, groupedLights, devices, rooms, scenes - id, sensors)
        in HueMapping.SENSOR_TYPES -> HueModel(lights, groupedLights, devices, rooms, scenes, sensors - id)
        else -> this
    }

    /** The resource an event names, so a partial update can be merged over it. */
    fun find(type: String, id: String): JsonObject? = when (type) {
        "light" -> lights[id]
        "grouped_light" -> groupedLights[id]
        "device" -> devices[id]
        "room" -> rooms[id]
        "scene" -> scenes[id]
        else -> sensors[id]
    }
}

/**
 * Hue's CLIP v2 resources onto Home Assistant's domains, and a service call
 * back onto a `PUT`. Pure, like the SmartThings one.
 */
@Suppress("detekt.TooManyFunctions")
object HueMapping {

    val SENSOR_TYPES = setOf("motion", "temperature", "light_level", "device_power", "contact")

    /** The resource types a session loads, in the order it asks. */
    val LOADED_TYPES = listOf("device", "room", "light", "grouped_light", "scene") + SENSOR_TYPES

    fun entityId(type: String, id: String): String = when (type) {
        "light", "grouped_light" -> "light.hue_" + slug(id)
        "scene" -> "scene.hue_" + slug(id)
        "motion", "contact" -> "binary_sensor.hue_" + slug(id)
        else -> "sensor.hue_" + slug(id)
    }

    /** `(type, id)` of the resource an entity id came from, by looking the model up. */
    fun resourceOf(model: HueModel, entityId: String): Pair<String, String>? {
        val raw = entityId.substringAfter("hue_", "")
        fun match(map: Map<String, JsonObject>) = map.keys.firstOrNull { slug(it) == raw }
        match(model.lights)?.let { return "light" to it }
        match(model.groupedLights)?.let { return "grouped_light" to it }
        match(model.scenes)?.let { return "scene" to it }
        match(model.sensors)?.let { id -> return (model.sensors[id]?.str("type") ?: "sensor") to id }
        return null
    }

    fun registry(model: HueModel): HaRegistry {
        val areas = model.rooms.map { (id, r) -> HaArea(id, r.obj("metadata")?.str("name") ?: "Room") }
        val devices = model.devices.map { (id, d) -> HaDevice(id, model.roomOfDevice[id], d.obj("metadata")?.str("name")) }
        val entries = buildList {
            for ((id, l) in model.lights) add(HaEntityEntry(entityId("light", id), deviceId = model.ownerDevice(l)))
            for ((id, _) in model.groupedLights) add(HaEntityEntry(entityId("grouped_light", id), areaId = model.roomOfGroupedLight(id)))
            for ((id, s) in model.scenes) add(HaEntityEntry(entityId("scene", id), areaId = s.obj("group")?.takeIf { it.str("rtype") == "room" }?.str("rid")))
            for ((id, s) in model.sensors) add(HaEntityEntry(entityId(s.str("type") ?: "sensor", id), deviceId = model.ownerDevice(s)))
        }
        return HaRegistry(areas = areas, devices = devices, entries = entries)
    }

    /** Every entity of the model. */
    fun entities(model: HueModel, nowSeconds: Double = HaTime.nowSeconds()): Map<String, HaEntity> = buildMap {
        for ((id, l) in model.lights) light(model, "light", id, l, nowSeconds)?.let { put(it.entityId, it) }
        for ((id, g) in model.groupedLights) {
            // Only rooms get a group light; zones and the whole-house group would double every lamp.
            if (model.roomOfGroupedLight(id) == null) continue
            light(model, "grouped_light", id, g, nowSeconds)?.let { put(it.entityId, it) }
        }
        for ((id, s) in model.scenes) scene(model, id, s, nowSeconds).let { put(it.entityId, it) }
        for ((id, s) in model.sensors) sensor(model, id, s, nowSeconds)?.let { put(it.entityId, it) }
    }

    private fun nameOf(model: HueModel, resource: JsonObject, fallback: String): String =
        resource.obj("metadata")?.str("name")
            ?: model.ownerDevice(resource)?.let { model.devices[it]?.obj("metadata")?.str("name") }
            ?: fallback

    @Suppress("detekt.CyclomaticComplexMethod")
    fun light(model: HueModel, type: String, id: String, l: JsonObject, nowSeconds: Double): HaEntity? {
        val on = l.obj("on")?.bool("on") ?: return null
        val name = if (type == "grouped_light") {
            (model.roomOfGroupedLight(id)?.let { model.rooms[it]?.obj("metadata")?.str("name") } ?: "Room") + " lights"
        } else {
            nameOf(model, l, "Light")
        }
        val dimming = l.obj("dimming")
        val ct = l.obj("color_temperature")
        val color = l.obj("color")
        val modes = buildList {
            if (color != null) add("hs")
            if (ct != null) add("color_temp")
            if (isEmpty() && dimming != null) add("brightness")
            if (isEmpty()) add("onoff")
        }
        val ctValid = ct?.bool("mirek_valid") == true
        val mirek = ct?.int("mirek")
        val schema = ct?.obj("mirek_schema")
        val effects = l.obj("effects")
        val attrs = LinkedHashMap<String, JsonElement>()
        attrs["friendly_name"] = JsonPrimitive(name)
        attrs["supported_color_modes"] = JsonArray(modes.map(::JsonPrimitive))
        if (on) attrs["color_mode"] = JsonPrimitive(if (ctValid && "color_temp" in modes) "color_temp" else modes.first())
        dimming?.num("brightness")?.let { attrs["brightness"] = JsonPrimitive(pctToByte(it)) }
        color?.obj("xy")?.let { xy ->
            val x = xy.num("x")
            val y = xy.num("y")
            if (x != null && y != null) {
                val (h, s) = HueColor.xyToHs(x, y)
                attrs["hs_color"] = JsonArray(listOf(JsonPrimitive((h * TENTH).roundToInt() / TENTH), JsonPrimitive((s * TENTH).roundToInt() / TENTH)))
                attrs["xy_color"] = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))
            }
        }
        if (ct != null) {
            if (ctValid && mirek != null) attrs["color_temp_kelvin"] = JsonPrimitive(HueColor.mirekToKelvin(mirek))
            attrs["min_color_temp_kelvin"] = JsonPrimitive(HueColor.mirekToKelvin(schema?.int("mirek_maximum") ?: MIREK_MAX))
            attrs["max_color_temp_kelvin"] = JsonPrimitive(HueColor.mirekToKelvin(schema?.int("mirek_minimum") ?: MIREK_MIN))
        }
        effects?.strings("effect_values")?.filter { it != "no_effect" }?.takeIf { it.isNotEmpty() }?.let { list ->
            attrs["effect_list"] = JsonArray(list.map(::JsonPrimitive))
            effects.str("status")?.takeIf { it != "no_effect" }?.let { attrs["effect"] = JsonPrimitive(it) }
        }
        return HaEntity(
            entityId = entityId(type, id),
            state = if (on) "on" else "off",
            attributes = JsonObject(attrs),
            lastChanged = nowSeconds,
            lastUpdated = nowSeconds,
        )
    }

    private fun scene(model: HueModel, id: String, s: JsonObject, nowSeconds: Double): HaEntity {
        val room = s.obj("group")?.takeIf { it.str("rtype") == "room" }?.str("rid")?.let { model.rooms[it]?.obj("metadata")?.str("name") }
        val name = s.obj("metadata")?.str("name") ?: "Scene"
        val active = s.obj("status")?.str("active")
        return HaEntity(
            entityId = entityId("scene", id),
            state = if (active == "static" || active == "dynamic_palette") "on" else "unknown",
            attributes = JsonObject(
                buildMap {
                    put("friendly_name", JsonPrimitive(if (room != null) "$room · $name" else name))
                    room?.let { put("room", JsonPrimitive(it)) }
                },
            ),
            lastChanged = nowSeconds,
            lastUpdated = nowSeconds,
        )
    }

    @Suppress("detekt.ReturnCount")
    private fun sensor(model: HueModel, id: String, s: JsonObject, nowSeconds: Double): HaEntity? {
        val type = s.str("type") ?: return null
        val device = nameOf(model, s, "Sensor")
        val enabled = s.bool("enabled") != false
        fun entity(domain: String, state: String, name: String, attrs: Map<String, JsonElement>): HaEntity = HaEntity(
            entityId = entityId(type, id),
            state = if (!enabled) "unavailable" else state,
            attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name)) + attrs),
            lastChanged = nowSeconds,
            lastUpdated = nowSeconds,
        )
        return when (type) {
            "motion" -> {
                val moving = s.obj("motion")?.let { it.obj("motion_report")?.bool("motion") ?: it.bool("motion") } ?: return null
                entity("binary_sensor", if (moving) "on" else "off", "$device motion", mapOf("device_class" to JsonPrimitive("motion")))
            }
            "contact" -> {
                val state = s.obj("contact_report")?.str("state") ?: return null
                entity("binary_sensor", if (state == "contact") "off" else "on", "$device contact", mapOf("device_class" to JsonPrimitive("opening")))
            }
            "temperature" -> {
                val t = s.obj("temperature")?.let { it.obj("temperature_report")?.num("temperature") ?: it.num("temperature") } ?: return null
                entity(
                    "sensor", ((t * TENTH).roundToInt() / TENTH).toString(), "$device temperature",
                    mapOf("device_class" to JsonPrimitive("temperature"), "unit_of_measurement" to JsonPrimitive("°C"), "state_class" to JsonPrimitive("measurement")),
                )
            }
            "light_level" -> {
                val level = s.obj("light")?.let { it.obj("light_level_report")?.num("light_level") ?: it.num("light_level") } ?: return null
                val lux = 10.0.pow((level - 1) / LUX_SCALE)
                entity(
                    "sensor", lux.roundToInt().toString(), "$device light level",
                    mapOf("device_class" to JsonPrimitive("illuminance"), "unit_of_measurement" to JsonPrimitive("lx"), "state_class" to JsonPrimitive("measurement")),
                )
            }
            "device_power" -> {
                val battery = s.obj("power_state")?.int("battery_level") ?: return null
                entity(
                    "sensor", battery.toString(), "$device battery",
                    mapOf("device_class" to JsonPrimitive("battery"), "unit_of_measurement" to JsonPrimitive("%"), "state_class" to JsonPrimitive("measurement")),
                )
            }
            else -> null
        }
    }

    /** A partial update event over the resource it names: CLIP sends only the fields that changed. */
    fun merged(current: JsonObject, update: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(current)
        for ((k, v) in update) {
            val old = out[k]
            out[k] = if (old is JsonObject && v is JsonObject) merged(old, v) else v
        }
        return JsonObject(out)
    }

    // ---- Service calls to PUT bodies --------------------------------------------

    /** The body `PUT /clip/v2/resource/light/{id}` takes for [call], or null when the call has no equivalent. */
    @Suppress("detekt.CyclomaticComplexMethod")
    fun lightBody(call: HaServiceCall, current: HaEntity?): String? {
        val data = call.data
        val body: JsonObject = when (call.service) {
            "turn_on" -> buildJsonObject {
                putJsonObject("on") { put("on", true) }
                data.num("brightness_pct")?.let { putJsonObject("dimming") { put("brightness", it.coerceIn(0.0, PERCENT_D)) } }
                data.num("brightness")?.let { putJsonObject("dimming") { put("brightness", byteToPct(it)) } }
                data.arr("hs_color")?.let { hs ->
                    val h = hs.getOrNull(0).prim()?.doubleOrNull ?: return@let
                    val s = hs.getOrNull(1).prim()?.doubleOrNull ?: return@let
                    val (x, y) = HueColor.hsToXy(h, s)
                    putJsonObject("color") { putJsonObject("xy") { put("x", x); put("y", y) } }
                }
                data.num("color_temp_kelvin")?.let { putJsonObject("color_temperature") { put("mirek", HueColor.kelvinToMirek(it.toInt())) } }
                data.str("effect")?.let { putJsonObject("effects") { put("effect", it) } }
            }
            "turn_off" -> buildJsonObject { putJsonObject("on") { put("on", false) } }
            "toggle" -> buildJsonObject { putJsonObject("on") { put("on", current?.state != "on") } }
            else -> return null
        }
        return HomeJson.encodeToString(JsonObject.serializer(), body)
    }

    fun sceneBody(): String = """{"recall":{"action":"active"}}"""

    private fun pctToByte(pct: Double): Int = ((pct.coerceIn(0.0, PERCENT_D) * BYTE_MAX) / PERCENT_D).roundToInt()
    private fun byteToPct(b: Double): Double = (b.coerceIn(0.0, BYTE_MAX.toDouble()) * PERCENT_D) / BYTE_MAX

    private const val PERCENT_D = 100.0
    private const val BYTE_MAX = 255
    private const val TENTH = 10.0
    private const val MIREK_MIN = 153
    private const val MIREK_MAX = 500
    private const val LUX_SCALE = 10_000.0
}
