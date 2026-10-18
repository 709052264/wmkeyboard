package com.wasimaster.wmkeyboard.core.smarthome.smartthings

import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaFeatures
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

/** One SmartThings device as the list and its status describe it. */
data class StDevice(
    val id: String,
    val label: String,
    val locationId: String?,
    val roomId: String?,
    /** Capability ids of the `main` component. */
    val capabilities: Set<String>,
    /** The manufacturer's category of the main component (`Light`, `SmartPlug`, `Thermostat`…), when given. */
    val category: String?,
    /** `components.main` of the status: capability id to attribute objects. */
    val status: JsonObject = JsonObject(emptyMap()),
) {
    /** `switch.st_<id>`-style entity ids share this slug. */
    val slug: String get() = "st_" + slug(id)
}

/** One command for `POST /devices/{id}/commands`. */
data class StCommand(val capability: String, val command: String, val arguments: List<JsonElement> = emptyList(), val component: String = "main") {
    fun json(): JsonObject = buildJsonObject {
        put("component", component)
        put("capability", capability)
        put("command", command)
        putJsonArray("arguments") { arguments.forEach { add(it) } }
    }
}

/**
 * SmartThings capabilities onto Home Assistant's domains, and a service call
 * back onto capability commands. Pure: JSON in, entities out; the backend
 * does the fetching.
 *
 * One device can be several entities: a bulb is a light, a multipurpose
 * sensor is a contact sensor, a temperature sensor and a battery.
 */
@Suppress("detekt.TooManyFunctions", "detekt.LargeClass")
object SmartThingsMapping {

    // ---- Parsing the API's lists ----------------------------------------------

    fun parseDevices(body: String): List<StDevice> {
        val root = HomeJson.parseToJsonElement(body).obj() ?: return emptyList()
        return root.objects("items").mapNotNull(::parseDevice)
    }

    fun parseDevice(o: JsonObject): StDevice? {
        val id = o.str("deviceId") ?: return null
        val main = o.objects("components").firstOrNull { it.str("id") == "main" } ?: o.objects("components").firstOrNull()
        val caps = main?.objects("capabilities")?.mapNotNull { it.str("id") }?.toSet().orEmpty()
        val category = main?.objects("categories")?.firstOrNull()?.str("name")
        val status = o.obj("status")?.obj("components")?.obj("main") ?: JsonObject(emptyMap())
        return StDevice(
            id = id,
            label = o.str("label")?.takeIf { it.isNotBlank() } ?: o.str("name") ?: id,
            locationId = o.str("locationId"),
            roomId = o.str("roomId"),
            capabilities = caps,
            category = category,
            status = status,
        )
    }

    /** `GET /devices/{id}/status`: the main component's capabilities. */
    fun parseStatus(body: String): JsonObject =
        HomeJson.parseToJsonElement(body).obj()?.obj("components")?.obj("main") ?: JsonObject(emptyMap())

    /** `GET /locations` and `GET /locations/{id}/rooms` both list `items` with an id and a name. */
    fun parseNamed(body: String, idKey: String, nameKey: String): List<Pair<String, String>> {
        val root = HomeJson.parseToJsonElement(body).obj() ?: return emptyList()
        return root.objects("items").mapNotNull { o -> o.str(idKey)?.let { id -> id to (o.str(nameKey) ?: id) } }
    }

    data class StScene(val id: String, val name: String, val locationId: String?)

    fun parseScenes(body: String): List<StScene> {
        val root = HomeJson.parseToJsonElement(body).obj() ?: return emptyList()
        return root.objects("items").mapNotNull { o -> o.str("sceneId")?.let { StScene(it, o.str("sceneName") ?: it, o.str("locationId")) } }
    }

    // ---- Devices to entities ---------------------------------------------------

    private fun StDevice.value(capability: String, attribute: String): JsonElement? = status.obj(capability)?.obj(attribute)?.get("value")
    private fun StDevice.text(capability: String, attribute: String): String? = value(capability, attribute).prim()?.contentOrNull
    private fun StDevice.number(capability: String, attribute: String): Double? = value(capability, attribute).prim()?.doubleOrNull
    private fun StDevice.unit(capability: String, attribute: String): String? = status.obj(capability)?.obj(attribute)?.str("unit")
    private fun StDevice.changed(capability: String, attribute: String): Double? =
        status.obj(capability)?.obj(attribute)?.str("timestamp")?.let(HaTime::parseIso)
    private fun StDevice.has(capability: String): Boolean = capability in capabilities

    val StDevice.isLight: Boolean
        get() = has(SWITCH) && (has(SWITCH_LEVEL) || has(COLOR_CONTROL) || has(COLOR_TEMPERATURE) || category.equals("Light", ignoreCase = true))

    /** Every entity [device] amounts to, named after it. */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun entities(device: StDevice, nowSeconds: Double = HaTime.nowSeconds()): List<HaEntity> = buildList {
        val d = device
        val name = d.label
        fun entity(domain: String, state: String, suffix: String = "", changed: Double? = null, attrs: Map<String, JsonElement> = emptyMap()): HaEntity {
            val lc = changed ?: nowSeconds
            return HaEntity(
                entityId = "$domain.${d.slug}$suffix",
                state = state,
                attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name)) + attrs),
                lastChanged = lc,
                lastUpdated = lc,
            )
        }
        fun attrs(build: MutableMap<String, JsonElement>.() -> Unit): Map<String, JsonElement> = LinkedHashMap<String, JsonElement>().apply(build)
        fun MutableMap<String, JsonElement>.attr(key: String, v: Any?) {
            when (v) {
                null -> Unit
                is String -> put(key, JsonPrimitive(v))
                is Number -> put(key, JsonPrimitive(v))
                is Boolean -> put(key, JsonPrimitive(v))
                is JsonElement -> put(key, v)
                is List<*> -> put(key, JsonArray(v.map { x -> if (x is Number) JsonPrimitive(x) else JsonPrimitive(x.toString()) }))
                else -> put(key, JsonPrimitive(v.toString()))
            }
        }

        val offline = d.text(HEALTH, "healthStatus") == "OFFLINE"
        fun stateOrUnavailable(s: String?): String = if (offline) "unavailable" else s ?: "unknown"

        when {
            d.has(THERMOSTAT_MODE) || d.has(THERMOSTAT_HEAT) || d.has(THERMOSTAT_COOL) -> {
                val mode = d.text(THERMOSTAT_MODE, "thermostatMode")
                val hvac = haHvacMode(mode)
                val modes = d.value(THERMOSTAT_MODE, "supportedThermostatModes").arr()?.mapNotNull { it.prim()?.contentOrNull }?.map(::haHvacMode)?.distinct().orEmpty()
                val heat = d.number(THERMOSTAT_HEAT, "heatingSetpoint")
                val cool = d.number(THERMOSTAT_COOL, "coolingSetpoint")
                val unit = d.unit(TEMPERATURE, "temperature") ?: d.unit(THERMOSTAT_HEAT, "heatingSetpoint") ?: "C"
                val range = hvac == "heat_cool" && heat != null && cool != null
                var features = HaFeatures.Climate.TARGET_TEMPERATURE
                if (range) features = features or HaFeatures.Climate.TARGET_TEMPERATURE_RANGE
                if (d.has(THERMOSTAT_FAN)) features = features or HaFeatures.Climate.FAN_MODE
                if ("off" in modes) features = features or HaFeatures.Climate.TURN_OFF or HaFeatures.Climate.TURN_ON
                add(
                    entity(
                        "climate", stateOrUnavailable(hvac), changed = d.changed(THERMOSTAT_MODE, "thermostatMode"),
                        attrs = attrs {
                            attr("hvac_modes", modes.ifEmpty { listOf("off", "heat", "cool", "heat_cool") })
                            attr("current_temperature", d.number(TEMPERATURE, "temperature"))
                            attr("temperature", if (range) null else if (hvac == "cool") cool ?: heat else heat ?: cool)
                            if (range) {
                                attr("target_temp_low", heat)
                                attr("target_temp_high", cool)
                            }
                            attr("hvac_action", haHvacAction(d.text(THERMOSTAT_STATE, "thermostatOperatingState")))
                            attr("fan_modes", d.value(THERMOSTAT_FAN, "supportedThermostatFanModes").arr()?.mapNotNull { it.prim()?.contentOrNull })
                            attr("fan_mode", d.text(THERMOSTAT_FAN, "thermostatFanMode"))
                            attr("temperature_unit", if (unit.contains('F')) "°F" else "°C")
                            attr("min_temp", if (unit.contains('F')) MIN_TEMP_F else MIN_TEMP_C)
                            attr("max_temp", if (unit.contains('F')) MAX_TEMP_F else MAX_TEMP_C)
                            attr("target_temp_step", if (unit.contains('F')) 1.0 else HALF)
                            attr("supported_features", features)
                        },
                    ),
                )
            }
            d.isLight -> {
                val on = d.text(SWITCH, "switch") == "on"
                val level = d.number(SWITCH_LEVEL, "level")
                val modes = buildList {
                    if (d.has(COLOR_CONTROL)) add("hs")
                    if (d.has(COLOR_TEMPERATURE)) add("color_temp")
                    if (isEmpty() && d.has(SWITCH_LEVEL)) add("brightness")
                    if (isEmpty()) add("onoff")
                }
                val hue = d.number(COLOR_CONTROL, "hue")
                val sat = d.number(COLOR_CONTROL, "saturation")
                val kelvin = d.number(COLOR_TEMPERATURE, "colorTemperature")?.toInt()
                val range = d.value(COLOR_TEMPERATURE, "colorTemperatureRange").obj()
                add(
                    entity(
                        "light", stateOrUnavailable(if (on) "on" else "off"), changed = d.changed(SWITCH, "switch"),
                        attrs = attrs {
                            attr("supported_color_modes", modes)
                            attr("color_mode", if (!on) null else if (kelvin != null && "color_temp" in modes && (hue == null || sat == 0.0)) "color_temp" else modes.first())
                            attr("brightness", level?.let { pctToByte(it) })
                            if (hue != null && sat != null) attr("hs_color", listOf(hue * HUE_SCALE, sat))
                            attr("color_temp_kelvin", kelvin)
                            attr("min_color_temp_kelvin", range?.int("minimum") ?: MIN_KELVIN)
                            attr("max_color_temp_kelvin", range?.int("maximum") ?: MAX_KELVIN)
                        },
                    ),
                )
            }
            d.has(WINDOW_SHADE) || d.has(DOOR_CONTROL) || d.has(GARAGE_DOOR) -> {
                val shade = d.has(WINDOW_SHADE)
                val cap = if (shade) WINDOW_SHADE else if (d.has(GARAGE_DOOR)) GARAGE_DOOR else DOOR_CONTROL
                val attr = if (shade) "windowShade" else "door"
                val raw = d.text(cap, attr)
                val state = when (raw) {
                    "open", "opening", "closing", "closed" -> raw
                    "partially open" -> "open"
                    else -> "unknown"
                }
                val level = d.number(SHADE_LEVEL, "shadeLevel")
                var features = HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE
                if (shade) features = features or HaFeatures.Cover.STOP
                if (level != null) features = features or HaFeatures.Cover.SET_POSITION
                add(
                    entity(
                        "cover", stateOrUnavailable(state), changed = d.changed(cap, attr),
                        attrs = attrs {
                            attr("device_class", if (shade) "shade" else if (cap == GARAGE_DOOR) "garage" else "door")
                            attr("current_position", level?.toInt() ?: if (state == "open") PERCENT else if (state == "closed") 0 else null)
                            attr("supported_features", features)
                        },
                    ),
                )
            }
            d.has(LOCK) -> add(
                entity(
                    "lock", stateOrUnavailable(if (d.text(LOCK, "lock") == "locked") "locked" else "unlocked"),
                    changed = d.changed(LOCK, "lock"),
                ),
            )
            d.has(VALVE) -> add(
                entity(
                    "valve", stateOrUnavailable(if (d.text(VALVE, "valve") == "open") "open" else "closed"),
                    changed = d.changed(VALVE, "valve"),
                    attrs = attrs { attr("supported_features", HaFeatures.Valve.OPEN or HaFeatures.Valve.CLOSE) },
                ),
            )
            d.has(FAN_SPEED) -> {
                val speed = d.number(FAN_SPEED, "fanSpeed")?.toInt() ?: 0
                val on = if (d.has(SWITCH)) d.text(SWITCH, "switch") == "on" else speed > 0
                add(
                    entity(
                        "fan", stateOrUnavailable(if (on) "on" else "off"), changed = d.changed(FAN_SPEED, "fanSpeed"),
                        attrs = attrs {
                            attr("percentage", if (on) speed * FAN_STEP_PCT else 0)
                            attr("percentage_step", FAN_STEP_PCT)
                            attr("supported_features", HaFeatures.Fan.SET_SPEED)
                        },
                    ),
                )
            }
            d.has(MEDIA_PLAYBACK) || d.has(AUDIO_VOLUME) -> {
                val status = d.text(MEDIA_PLAYBACK, "playbackStatus")
                val on = if (d.has(SWITCH)) d.text(SWITCH, "switch") == "on" else true
                val state = when {
                    !on -> "off"
                    status == "playing" || status == "fast forwarding" || status == "rewinding" -> "playing"
                    status == "paused" -> "paused"
                    status == "stopped" -> "idle"
                    else -> "on"
                }
                var features = 0
                if (d.has(MEDIA_PLAYBACK)) features = features or HaFeatures.Media.PLAY or HaFeatures.Media.PAUSE or HaFeatures.Media.STOP
                if (d.has(MEDIA_TRACK)) features = features or HaFeatures.Media.NEXT_TRACK or HaFeatures.Media.PREVIOUS_TRACK
                if (d.has(AUDIO_VOLUME)) features = features or HaFeatures.Media.VOLUME_SET or HaFeatures.Media.VOLUME_STEP
                if (d.has(AUDIO_MUTE)) features = features or HaFeatures.Media.VOLUME_MUTE
                if (d.has(MEDIA_INPUT)) features = features or HaFeatures.Media.SELECT_SOURCE
                if (d.has(SWITCH)) features = features or HaFeatures.Media.TURN_ON or HaFeatures.Media.TURN_OFF
                add(
                    entity(
                        "media_player", stateOrUnavailable(state), changed = d.changed(MEDIA_PLAYBACK, "playbackStatus"),
                        attrs = attrs {
                            attr("volume_level", d.number(AUDIO_VOLUME, "volume")?.let { it / PERCENT })
                            attr("is_volume_muted", d.text(AUDIO_MUTE, "mute") == "muted")
                            attr("source", d.text(MEDIA_INPUT, "inputSource"))
                            attr("source_list", d.value(MEDIA_INPUT, "supportedInputSources").arr()?.mapNotNull { it.prim()?.contentOrNull })
                            attr("media_title", d.value(AUDIO_TRACK, "audioTrackData").obj()?.str("title"))
                            attr("media_artist", d.value(AUDIO_TRACK, "audioTrackData").obj()?.str("artist"))
                            attr("supported_features", features)
                        },
                    ),
                )
            }
            d.has(SWITCH) -> add(
                entity(
                    "switch", stateOrUnavailable(if (d.text(SWITCH, "switch") == "on") "on" else "off"),
                    changed = d.changed(SWITCH, "switch"),
                    attrs = attrs { attr("device_class", if (d.category.equals("SmartPlug", ignoreCase = true)) "outlet" else "switch") },
                ),
            )
        }

        // Sensors ride along whatever the device mainly is.
        fun binary(cap: String, attr: String, onValue: String, deviceClass: String, suffix: String) {
            if (!d.has(cap)) return
            val raw = d.text(cap, attr) ?: return
            add(entity("binary_sensor", stateOrUnavailable(if (raw == onValue) "on" else "off"), suffix, d.changed(cap, attr), attrs { attr("device_class", deviceClass) }))
        }
        fun measure(cap: String, attr: String, deviceClass: String, suffix: String, unit: String? = null, stateClass: String = "measurement") {
            if (!d.has(cap)) return
            val raw = d.number(cap, attr) ?: return
            val u = unit ?: d.unit(cap, attr)
            add(
                entity(
                    "sensor", stateOrUnavailable(trimNumber(raw)), suffix, d.changed(cap, attr),
                    attrs {
                        attr("device_class", deviceClass)
                        attr("state_class", stateClass)
                        attr("unit_of_measurement", if (u == "C" || u == "F") "°$u" else u)
                    },
                ),
            )
        }
        binary(CONTACT, "contact", "open", "opening", "_contact")
        binary(MOTION, "motion", "active", "motion", "_motion")
        binary(WATER, "water", "wet", "moisture", "_water")
        binary(PRESENCE, "presence", "present", "presence", "_presence")
        binary(SMOKE, "smoke", "detected", "smoke", "_smoke")
        if (!d.has(THERMOSTAT_MODE) && !d.has(THERMOSTAT_HEAT)) measure(TEMPERATURE, "temperature", "temperature", "_temperature")
        measure(HUMIDITY, "humidity", "humidity", "_humidity", "%")
        measure(BATTERY, "battery", "battery", "_battery", "%")
        measure(ILLUMINANCE, "illuminance", "illuminance", "_illuminance", "lx")
        measure(POWER_METER, "power", "power", "_power", "W")
        measure(ENERGY_METER, "energy", "energy", "_energy", "kWh", stateClass = "total_increasing")
    }

    /** A scene as a `scene.` entity; SmartThings keeps no state for one. */
    fun sceneEntity(scene: StScene, nowSeconds: Double = HaTime.nowSeconds()): HaEntity = HaEntity(
        entityId = "scene.st_" + slug(scene.id),
        state = "unknown",
        attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(scene.name))),
        lastChanged = nowSeconds,
        lastUpdated = nowSeconds,
    )

    // ---- Service calls to commands -----------------------------------------------

    /**
     * The commands a service call means for [device], given its current
     * entity (a toggle needs the state). Empty when the call has no
     * equivalent, which the backend reports as a refusal.
     */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun commands(call: HaServiceCall, device: StDevice, current: HaEntity?): List<StCommand> {
        val d = device
        val data = call.data
        fun onOff(on: Boolean) = StCommand(SWITCH, if (on) "on" else "off")
        val isOn = current?.state == "on" || current?.state == "playing" || current?.state == "paused"
        // A call for a kind of thing the device is not: nothing to send.
        val fits = when (call.domain) {
            "cover" -> d.has(WINDOW_SHADE) || d.has(DOOR_CONTROL) || d.has(GARAGE_DOOR)
            "lock" -> d.has(LOCK)
            "valve" -> d.has(VALVE)
            "climate" -> d.has(THERMOSTAT_MODE) || d.has(THERMOSTAT_HEAT) || d.has(THERMOSTAT_COOL)
            "media_player" -> d.has(MEDIA_PLAYBACK) || d.has(AUDIO_VOLUME)
            "fan" -> d.has(FAN_SPEED) || d.has(SWITCH)
            "light", "switch", "homeassistant" -> d.has(SWITCH) || d.has(FAN_SPEED) || d.has(MEDIA_PLAYBACK)
            else -> false
        }
        if (!fits) return emptyList()
        return when (call.domain) {
            "light", "switch", "fan", "media_player", "homeassistant" -> when (call.service) {
                "turn_on" -> buildList {
                    if (d.has(SWITCH)) add(onOff(true))
                    data.num("brightness_pct")?.let { if (d.has(SWITCH_LEVEL)) add(StCommand(SWITCH_LEVEL, "setLevel", listOf(JsonPrimitive(it.roundToInt())))) }
                    data.num("brightness")?.let { if (d.has(SWITCH_LEVEL)) add(StCommand(SWITCH_LEVEL, "setLevel", listOf(JsonPrimitive(byteToPct(it))))) }
                    data.arr("hs_color")?.let { hs ->
                        val h = hs.getOrNull(0).prim()?.doubleOrNull ?: return@let
                        val s = hs.getOrNull(1).prim()?.doubleOrNull ?: return@let
                        if (d.has(COLOR_CONTROL)) {
                            add(StCommand(COLOR_CONTROL, "setColor", listOf(buildJsonObject { put("hue", h / HUE_SCALE); put("saturation", s) })))
                        }
                    }
                    data.num("color_temp_kelvin")?.let { if (d.has(COLOR_TEMPERATURE)) add(StCommand(COLOR_TEMPERATURE, "setColorTemperature", listOf(JsonPrimitive(it.roundToInt())))) }
                    data.num("percentage")?.let { if (d.has(FAN_SPEED)) add(StCommand(FAN_SPEED, "setFanSpeed", listOf(JsonPrimitive((it / FAN_STEP_PCT).roundToInt())))) }
                }
                "turn_off" -> if (d.has(SWITCH)) listOf(onOff(false)) else if (d.has(FAN_SPEED)) listOf(StCommand(FAN_SPEED, "setFanSpeed", listOf(JsonPrimitive(0)))) else emptyList()
                "toggle" -> if (d.has(SWITCH)) listOf(onOff(!isOn)) else emptyList()
                "set_percentage" -> data.num("percentage")?.let { pct ->
                    if (!d.has(FAN_SPEED)) return emptyList()
                    listOf(StCommand(FAN_SPEED, "setFanSpeed", listOf(JsonPrimitive((pct / FAN_STEP_PCT).roundToInt()))))
                }.orEmpty()
                "media_play" -> listOf(StCommand(MEDIA_PLAYBACK, "play"))
                "media_pause" -> listOf(StCommand(MEDIA_PLAYBACK, "pause"))
                "media_play_pause" -> listOf(StCommand(MEDIA_PLAYBACK, if (current?.state == "playing") "pause" else "play"))
                "media_stop" -> listOf(StCommand(MEDIA_PLAYBACK, "stop"))
                "media_next_track" -> listOf(StCommand(MEDIA_TRACK, "nextTrack"))
                "media_previous_track" -> listOf(StCommand(MEDIA_TRACK, "previousTrack"))
                "volume_set" -> data.num("volume_level")?.let { listOf(StCommand(AUDIO_VOLUME, "setVolume", listOf(JsonPrimitive((it * PERCENT).roundToInt())))) }.orEmpty()
                "volume_mute" -> listOf(StCommand(AUDIO_MUTE, if (data.bool("is_volume_muted") == true) "mute" else "unmute"))
                "select_source" -> data.str("source")?.let { listOf(StCommand(MEDIA_INPUT, "setInputSource", listOf(JsonPrimitive(it)))) }.orEmpty()
                else -> emptyList()
            }
            "cover" -> {
                val cap = if (d.has(WINDOW_SHADE)) WINDOW_SHADE else if (d.has(GARAGE_DOOR)) GARAGE_DOOR else DOOR_CONTROL
                when (call.service) {
                    "open_cover" -> listOf(StCommand(cap, "open"))
                    "close_cover" -> listOf(StCommand(cap, "close"))
                    "stop_cover" -> if (cap == WINDOW_SHADE) listOf(StCommand(cap, "pause")) else emptyList()
                    "set_cover_position" -> data.num("position")?.let { listOf(StCommand(SHADE_LEVEL, "setShadeLevel", listOf(JsonPrimitive(it.roundToInt())))) }.orEmpty()
                    else -> emptyList()
                }
            }
            "lock" -> when (call.service) {
                "lock" -> listOf(StCommand(LOCK, "lock"))
                "unlock", "open" -> listOf(StCommand(LOCK, "unlock"))
                else -> emptyList()
            }
            "valve" -> when (call.service) {
                "open_valve" -> listOf(StCommand(VALVE, "open"))
                "close_valve" -> listOf(StCommand(VALVE, "close"))
                else -> emptyList()
            }
            "climate" -> when (call.service) {
                "set_temperature" -> buildList {
                    val mode = current?.state
                    data.num("temperature")?.let { t ->
                        val heating = mode != "cool"
                        add(StCommand(if (heating) THERMOSTAT_HEAT else THERMOSTAT_COOL, if (heating) "setHeatingSetpoint" else "setCoolingSetpoint", listOf(JsonPrimitive(t))))
                    }
                    data.num("target_temp_low")?.let { add(StCommand(THERMOSTAT_HEAT, "setHeatingSetpoint", listOf(JsonPrimitive(it)))) }
                    data.num("target_temp_high")?.let { add(StCommand(THERMOSTAT_COOL, "setCoolingSetpoint", listOf(JsonPrimitive(it)))) }
                }
                "set_hvac_mode" -> data.str("hvac_mode")?.let { listOf(StCommand(THERMOSTAT_MODE, "setThermostatMode", listOf(JsonPrimitive(stHvacMode(it))))) }.orEmpty()
                "set_fan_mode" -> data.str("fan_mode")?.let { listOf(StCommand(THERMOSTAT_FAN, "setThermostatFanMode", listOf(JsonPrimitive(it)))) }.orEmpty()
                "turn_off" -> listOf(StCommand(THERMOSTAT_MODE, "setThermostatMode", listOf(JsonPrimitive("off"))))
                "turn_on" -> listOf(StCommand(THERMOSTAT_MODE, "setThermostatMode", listOf(JsonPrimitive("auto"))))
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    fun commandsBody(commands: List<StCommand>): String =
        HomeJson.encodeToString(JsonObject.serializer(), buildJsonObject { putJsonArray("commands") { commands.forEach { add(it.json()) } } })

    // ---- Vocabulary --------------------------------------------------------------

    private fun haHvacMode(st: String?): String = when (st) {
        null -> "unknown"
        "auto" -> "heat_cool"
        "emergency heat", "heat" -> "heat"
        "cool", "off", "dry", "fan_only" -> st
        "eco", "manual", "rush hour", "asleep", "away" -> "auto"
        else -> st.replace(' ', '_')
    }

    private fun stHvacMode(ha: String): String = when (ha) {
        "heat_cool" -> "auto"
        else -> ha
    }

    private fun haHvacAction(st: String?): String? = when (st) {
        null -> null
        "heating", "pending heat" -> "heating"
        "cooling", "pending cool" -> "cooling"
        "fan only" -> "fan"
        "idle" -> "idle"
        else -> st.replace(' ', '_')
    }

    private fun pctToByte(pct: Double): Int = ((pct.coerceIn(0.0, PERCENT.toDouble()) * BYTE_MAX) / PERCENT).roundToInt()
    private fun byteToPct(b: Double): Int = ((b.coerceIn(0.0, BYTE_MAX.toDouble()) * PERCENT) / BYTE_MAX).roundToInt()

    private fun trimNumber(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    const val SWITCH = "switch"
    const val SWITCH_LEVEL = "switchLevel"
    const val COLOR_CONTROL = "colorControl"
    const val COLOR_TEMPERATURE = "colorTemperature"
    const val FAN_SPEED = "fanSpeed"
    const val WINDOW_SHADE = "windowShade"
    const val SHADE_LEVEL = "windowShadeLevel"
    const val DOOR_CONTROL = "doorControl"
    const val GARAGE_DOOR = "garageDoorControl"
    const val LOCK = "lock"
    const val VALVE = "valve"
    const val THERMOSTAT_MODE = "thermostatMode"
    const val THERMOSTAT_HEAT = "thermostatHeatingSetpoint"
    const val THERMOSTAT_COOL = "thermostatCoolingSetpoint"
    const val THERMOSTAT_FAN = "thermostatFanMode"
    const val THERMOSTAT_STATE = "thermostatOperatingState"
    const val TEMPERATURE = "temperatureMeasurement"
    const val HUMIDITY = "relativeHumidityMeasurement"
    const val BATTERY = "battery"
    const val ILLUMINANCE = "illuminanceMeasurement"
    const val POWER_METER = "powerMeter"
    const val ENERGY_METER = "energyMeter"
    const val CONTACT = "contactSensor"
    const val MOTION = "motionSensor"
    const val WATER = "waterSensor"
    const val PRESENCE = "presenceSensor"
    const val SMOKE = "smokeDetector"
    const val MEDIA_PLAYBACK = "mediaPlayback"
    const val MEDIA_TRACK = "mediaTrackControl"
    const val AUDIO_VOLUME = "audioVolume"
    const val AUDIO_MUTE = "audioMute"
    const val AUDIO_TRACK = "audioTrackData"
    const val MEDIA_INPUT = "mediaInputSource"
    const val HEALTH = "healthCheck"

    private const val PERCENT = 100
    private const val BYTE_MAX = 255
    private const val HUE_SCALE = 3.6
    private const val FAN_STEP_PCT = 25
    private const val MIN_KELVIN = 2200
    private const val MAX_KELVIN = 6500
    private const val MIN_TEMP_C = 7.0
    private const val MAX_TEMP_C = 35.0
    private const val MIN_TEMP_F = 45.0
    private const val MAX_TEMP_F = 95.0
    private const val HALF = 0.5
}
