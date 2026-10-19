package com.wasimaster.wmkeyboard.core.smarthome.switchbot

import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaFeatures
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaTime
import com.wasimaster.wmkeyboard.core.smarthome.HomeAttrs
import com.wasimaster.wmkeyboard.core.smarthome.HomeJson
import com.wasimaster.wmkeyboard.core.smarthome.arr
import com.wasimaster.wmkeyboard.core.smarthome.bool
import com.wasimaster.wmkeyboard.core.smarthome.homeEntity
import com.wasimaster.wmkeyboard.core.smarthome.num
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.objects
import com.wasimaster.wmkeyboard.core.smarthome.prim
import com.wasimaster.wmkeyboard.core.smarthome.slug
import com.wasimaster.wmkeyboard.core.smarthome.str
import com.wasimaster.wmkeyboard.core.smarthome.trimNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * One SwitchBot device: a physical one from `deviceList`, or a virtual
 * infrared remote from `infraredRemoteList` ([remote] true, [type] its
 * `remoteType`). [status] is the body of its status call, empty until asked
 * and always empty for a remote, which reports nothing.
 */
data class SbDevice(
    val id: String,
    val name: String,
    val type: String,
    val remote: Boolean = false,
    val cloud: Boolean = true,
    val status: JsonObject = JsonObject(emptyMap()),
    /** The device or its hub answered "offline" when asked. */
    val offline: Boolean = false,
) {
    val slug: String get() = "sb_" + slug(id)

    fun text(key: String): String? = status[key].prim()?.contentOrNull
    fun number(key: String): Double? = status[key].prim()?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    fun flag(key: String): Boolean? = status.bool(key)
    fun has(key: String): Boolean = key in status

    /** `power` in any case, or a relay's 1/0 `switchStatus`. */
    val on: Boolean
        get() = text("power")?.equals("on", ignoreCase = true) ?: number("switchStatus")?.let { it > 0 } ?: false
}

/** One command for `POST /v1.1/devices/{id}/commands`. */
data class SbCommand(val command: String, val parameter: JsonElement = JsonPrimitive("default"), val commandType: String = "command") {
    fun json(): String = HomeJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("command", command)
            put("parameter", parameter)
            put("commandType", commandType)
        },
    )
}

data class SbScene(val id: String, val name: String)

/**
 * SwitchBot's device types onto Home Assistant's domains, and a service call
 * back onto SwitchBot's commands. The API names a device by its `deviceType`
 * (`Color Bulb`, `Curtain3`, `Plug Mini (EU)`…), so the mapping goes by name
 * first and by the fields its status reports second.
 */
@Suppress("detekt.TooManyFunctions")
object SwitchBotMapping {

    // ---- Parsing ---------------------------------------------------------------------

    /** `GET /v1.1/devices`: physical devices, then infrared remotes. */
    fun parseDevices(body: String): List<SbDevice> {
        val b = HomeJson.parseToJsonElement(body).obj()?.obj("body") ?: return emptyList()
        val physical = b.objects("deviceList").mapNotNull { o ->
            val id = o.str("deviceId") ?: return@mapNotNull null
            SbDevice(id, o.str("deviceName") ?: id, o.str("deviceType").orEmpty(), cloud = o.bool("enableCloudService") ?: true)
        }
        val remotes = b.objects("infraredRemoteList").mapNotNull { o ->
            val id = o.str("deviceId") ?: return@mapNotNull null
            SbDevice(id, o.str("deviceName") ?: id, o.str("remoteType").orEmpty(), remote = true)
        }
        return physical + remotes
    }

    /** `GET /v1.1/devices/{id}/status`: the body object. */
    fun parseStatus(body: String): JsonObject = HomeJson.parseToJsonElement(body).obj()?.obj("body") ?: JsonObject(emptyMap())

    /** `GET /v1.1/scenes`: the manual scenes. */
    fun parseScenes(body: String): List<SbScene> =
        HomeJson.parseToJsonElement(body).obj()?.arr("body")?.mapNotNull { it.obj() }.orEmpty().mapNotNull { s ->
            s.str("sceneId")?.let { SbScene(it, s.str("sceneName") ?: it) }
        }

    /** Whether a status call has anything to tell for [device]: hubs without sensors, cameras, keypads and remotes have nothing. */
    fun reportsStatus(device: SbDevice): Boolean =
        !device.remote && device.cloud && device.type !in SILENT && !device.type.startsWith("Hub Mini") && !device.type.contains("Cam") && !device.type.startsWith("Keypad")

    // ---- Kinds -------------------------------------------------------------------------

    private enum class Kind { LIGHT, SWITCH, SWITCH_2, CURTAIN, ROLLER, TILT, GARAGE, LOCK, FAN, PURIFIER, RADIATOR, REMOTE, SENSOR }

    private fun kindOf(d: SbDevice): Kind {
        val t = d.type
        return when {
            d.remote -> Kind.REMOTE
            t == "Curtain" || t == "Curtain3" || t == "Curtain 3" -> Kind.CURTAIN
            t == "Roller Shade" -> Kind.ROLLER
            t == "Blind Tilt" -> Kind.TILT
            t == "Garage Door Opener" -> Kind.GARAGE
            t == "Relay Switch 2PM" -> Kind.SWITCH_2
            t == "Smart Radiator Thermostat" -> Kind.RADIATOR
            t.contains("Lock") -> Kind.LOCK
            t.contains("Air Purifier") -> Kind.PURIFIER
            t.contains("Fan") -> Kind.FAN
            t.contains("Light") || t.contains("Lamp") || t.contains("Bulb") -> Kind.LIGHT
            t == "Bot" || t.startsWith("Plug") || t.startsWith("Relay Switch") || t == "Humidifier" || t.startsWith("Evaporative Humidifier") -> Kind.SWITCH
            else -> Kind.SENSOR
        }
    }

    // ---- Devices to entities ---------------------------------------------------------

    /**
     * Every entity [device] amounts to. [assumed] is what the keyboard last
     * sent each remote, since a remote reports no state of its own.
     */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun entities(device: SbDevice, nowSeconds: Double = HaTime.nowSeconds(), assumed: Map<String, Boolean> = emptyMap()): List<HaEntity> = buildList {
        val d = device
        fun state(s: String) = if (d.offline) "unavailable" else s
        fun entity(domain: String, state: String, suffix: String = "", name: String = d.name, attrs: HomeAttrs.() -> Unit = {}) =
            add(homeEntity("$domain.${d.slug}$suffix", name, state(state), nowSeconds, attrs))
        // Until the first status arrives a device draws as unknown rather than off.
        val known = d.status.isNotEmpty()
        fun onOff(on: Boolean) = if (!known) "unknown" else if (on) "on" else "off"

        when (kindOf(d)) {
            Kind.LIGHT -> {
                val modes = buildList {
                    if (d.has("color")) add("hs")
                    if (d.has("colorTemperature")) add("color_temp")
                    if (isEmpty() && d.has("brightness")) add("brightness")
                    if (isEmpty()) add(if (known) "onoff" else "brightness")
                }
                val rgb = d.text("color")?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == RGB }
                entity("light", onOff(d.on)) {
                    put("supported_color_modes", modes)
                    put("color_mode", if (!d.on) null else modes.first())
                    put("brightness", d.number("brightness")?.let { (it.coerceIn(1.0, PERCENT.toDouble()) * BYTE_MAX / PERCENT).roundToInt() })
                    if (rgb != null) put("hs_color", rgbToHs(rgb[0], rgb[1], rgb[2]).toList())
                    put("color_temp_kelvin", d.number("colorTemperature")?.toInt())
                    if ("color_temp" in modes) {
                        put("min_color_temp_kelvin", MIN_KELVIN)
                        put("max_color_temp_kelvin", MAX_KELVIN)
                    }
                }
            }
            Kind.SWITCH -> entity("switch", onOff(d.on)) { put("device_class", if (d.type.startsWith("Plug")) "outlet" else "switch") }
            Kind.SWITCH_2 -> for (n in 1..2) {
                entity("switch", onOff((d.number("switch${n}Status") ?: 0.0) > 0), "_$n", "${d.name} $n")
            }
            Kind.CURTAIN, Kind.ROLLER -> {
                // SwitchBot counts 0 as open and 100 as closed; Home Assistant the other way round.
                val position = d.number("slidePosition")?.let { PERCENT - it.roundToInt() }
                val moving = d.flag("moving") == true
                var features = HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE or HaFeatures.Cover.SET_POSITION
                if (kindOf(d) == Kind.CURTAIN) features = features or HaFeatures.Cover.STOP
                entity("cover", coverState(position, moving)) {
                    put("device_class", if (kindOf(d) == Kind.CURTAIN) "curtain" else "shade")
                    put("current_position", position)
                    put("supported_features", features)
                }
            }
            Kind.TILT -> {
                val position = d.number("slidePosition")?.roundToInt()
                entity("cover", coverState(position, d.flag("moving") == true)) {
                    put("device_class", "blind")
                    put("current_position", position)
                    put("supported_features", HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE or HaFeatures.Cover.SET_POSITION)
                }
            }
            Kind.GARAGE -> {
                val raw = d.number("doorStatus")
                entity("cover", if (raw == null) "unknown" else if (raw == 0.0) "open" else "closed") {
                    put("device_class", "garage")
                    put("supported_features", HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE)
                }
            }
            Kind.LOCK -> {
                val raw = d.text("lockState")?.lowercase()
                val state = when (raw) {
                    null -> "unknown"
                    "locked" -> "locked"
                    "jammed" -> "jammed"
                    else -> "unlocked"
                }
                entity("lock", state)
                d.text("doorState")?.let { door ->
                    entity("binary_sensor", if (door.equals("opened", ignoreCase = true) || door.equals("open", ignoreCase = true)) "on" else "off", "_door") {
                        put("device_class", "door")
                    }
                }
            }
            Kind.FAN -> {
                val speed = d.number("fanSpeed")?.roundToInt()
                entity("fan", onOff(d.on)) {
                    put("percentage", if (d.on) speed else 0)
                    put("supported_features", HaFeatures.Fan.SET_SPEED)
                }
            }
            Kind.PURIFIER -> entity("fan", onOff(d.on)) { put("supported_features", 0) }
            Kind.RADIATOR -> {
                val mode = d.number("mode")?.toInt()
                entity("climate", if (!known) "unknown" else if (mode == RADIATOR_OFF) "off" else "heat") {
                    put("hvac_modes", listOf("off", "heat"))
                    put("current_temperature", d.number("temperature"))
                    put("min_temp", RADIATOR_MIN)
                    put("max_temp", RADIATOR_MAX)
                    put("target_temp_step", 1.0)
                    put("temperature_unit", "°C")
                    put("supported_features", HaFeatures.Climate.TARGET_TEMPERATURE or HaFeatures.Climate.TURN_ON or HaFeatures.Climate.TURN_OFF)
                }
            }
            Kind.REMOTE -> if (d.type != "Others" && !d.type.startsWith("DIY")) {
                entity("switch", if (assumed[d.id] == true) "on" else "off") { put("assumed_state", true) }
            }
            Kind.SENSOR -> Unit
        }

        // Sensors ride along whatever the device mainly is.
        fun measure(key: String, deviceClass: String, suffix: String, unit: String) {
            val v = d.number(key) ?: return
            entity("sensor", trimNumber(v), suffix) {
                put("device_class", deviceClass)
                put("state_class", "measurement")
                put("unit_of_measurement", unit)
            }
        }
        fun binary(deviceClass: String, suffix: String, on: Boolean?) {
            on ?: return
            entity("binary_sensor", if (on) "on" else "off", suffix) { put("device_class", deviceClass) }
        }
        if (kindOf(d) != Kind.RADIATOR) measure("temperature", "temperature", "_temperature", "°C")
        measure("humidity", "humidity", "_humidity", "%")
        measure("CO2", "carbon_dioxide", "_co2", "ppm")
        measure("battery", "battery", "_battery", "%")
        when {
            d.type.startsWith("Plug Mini") -> measure("weight", "power", "_power", "W")
            d.type == "Relay Switch 1PM" -> measure("power", "power", "_power", "W")
        }
        if (d.has("voltage")) measure("voltage", "voltage", "_voltage", "V")
        binary("motion", "_motion", d.flag("moveDetected"))
        binary("opening", "_contact", d.text("openState")?.let { !it.equals("close", ignoreCase = true) })
        binary("occupancy", "_presence", d.flag("detected") ?: d.flag("Detected"))
        if (d.type == "Water Detector" || d.type.contains("Leak")) binary("moisture", "_water", d.number("status")?.let { it > 0 })
    }

    private fun coverState(position: Int?, moving: Boolean): String = when {
        moving -> "opening"
        position == null -> "unknown"
        position <= 0 -> "closed"
        else -> "open"
    }

    fun sceneEntity(scene: SbScene, nowSeconds: Double = HaTime.nowSeconds()): HaEntity =
        homeEntity("scene.sb_" + slug(scene.id), scene.name, "unknown", nowSeconds)

    // ---- Service calls to commands --------------------------------------------------

    /**
     * The commands a service call on [entityId] means for [device], given its
     * current entity. Empty when the call has no equivalent, which the
     * backend reports as a refusal.
     */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod", "detekt.ReturnCount")
    fun commands(call: HaServiceCall, entityId: String, device: SbDevice, current: HaEntity?): List<SbCommand> {
        val d = device
        val data = call.data
        val isOn = current?.state == "on"
        fun c(command: String, parameter: Any? = null): SbCommand = SbCommand(
            command,
            when (parameter) {
                null -> JsonPrimitive("default")
                is Number -> JsonPrimitive(parameter)
                else -> JsonPrimitive(parameter.toString())
            },
        )
        fun power(on: Boolean): SbCommand = c(if (on) "turnOn" else "turnOff")
        return when (kindOf(d)) {
            Kind.SWITCH, Kind.PURIFIER, Kind.REMOTE -> when (call.service) {
                "turn_on" -> listOf(power(true))
                "turn_off" -> listOf(power(false))
                "toggle" -> listOf(power(!isOn))
                else -> emptyList()
            }
            Kind.SWITCH_2 -> {
                val channel = entityId.substringAfterLast('_')
                when (call.service) {
                    "turn_on" -> listOf(c("turnOn", channel))
                    "turn_off" -> listOf(c("turnOff", channel))
                    "toggle" -> listOf(c("toggle", channel))
                    else -> emptyList()
                }
            }
            Kind.LIGHT -> when (call.service) {
                "turn_off" -> listOf(power(false))
                "toggle" -> listOf(power(!isOn))
                "turn_on" -> buildList {
                    if (!isOn) add(power(true))
                    val pct = data.num("brightness_pct") ?: data.num("brightness")?.let { it * PERCENT / BYTE_MAX }
                    pct?.let { add(c("setBrightness", it.roundToInt().coerceIn(1, PERCENT))) }
                    data.arr("hs_color")?.let { a ->
                        val h = a.getOrNull(0).prim()?.doubleOrNull ?: return@let
                        val s = a.getOrNull(1).prim()?.doubleOrNull ?: return@let
                        val (r, g, b) = hsToRgb(h, s)
                        add(c("setColor", "$r:$g:$b"))
                    }
                    data.num("color_temp_kelvin")?.let { add(c("setColorTemperature", it.roundToInt().coerceIn(MIN_KELVIN, MAX_KELVIN))) }
                    if (isEmpty()) add(power(true))
                }
                else -> emptyList()
            }
            Kind.CURTAIN -> when (call.service) {
                "open_cover" -> listOf(c("turnOn"))
                "close_cover" -> listOf(c("turnOff"))
                "stop_cover" -> listOf(c("pause"))
                "set_cover_position" -> data.num("position")?.let { listOf(c("setPosition", "0,ff,${PERCENT - it.roundToInt().coerceIn(0, PERCENT)}")) }.orEmpty()
                else -> emptyList()
            }
            Kind.ROLLER -> when (call.service) {
                "open_cover" -> listOf(c("setPosition", 0))
                "close_cover" -> listOf(c("setPosition", PERCENT))
                "set_cover_position" -> data.num("position")?.let { listOf(c("setPosition", PERCENT - it.roundToInt().coerceIn(0, PERCENT))) }.orEmpty()
                else -> emptyList()
            }
            Kind.TILT -> when (call.service) {
                "open_cover" -> listOf(c("fullyOpen"))
                "close_cover" -> listOf(c(if (d.text("direction").equals("down", ignoreCase = true)) "closeDown" else "closeUp"))
                "set_cover_position" -> data.num("position")?.let { p ->
                    // The Blind Tilt takes even positions only.
                    val even = (p.roundToInt().coerceIn(0, PERCENT) / 2) * 2
                    val direction = if (d.text("direction").equals("down", ignoreCase = true)) "down" else "up"
                    listOf(c("setPosition", "$direction;$even"))
                }.orEmpty()
                else -> emptyList()
            }
            Kind.GARAGE -> when (call.service) {
                "open_cover" -> listOf(c("turnOn"))
                "close_cover" -> listOf(c("turnOff"))
                else -> emptyList()
            }
            Kind.LOCK -> when (call.service) {
                "lock" -> listOf(c("lock"))
                "unlock", "open" -> listOf(c("unlock"))
                else -> emptyList()
            }
            Kind.FAN -> when (call.service) {
                "turn_on" -> listOfNotNull(power(true), data.num("percentage")?.let { c("setWindSpeed", it.roundToInt().coerceIn(1, PERCENT)) })
                "turn_off" -> listOf(power(false))
                "toggle" -> listOf(power(!isOn))
                "set_percentage" -> data.num("percentage")?.let { pct ->
                    if (pct <= 0) listOf(power(false)) else listOf(power(true), c("setWindSpeed", pct.roundToInt().coerceIn(1, PERCENT)))
                }.orEmpty()
                else -> emptyList()
            }
            Kind.RADIATOR -> when (call.service) {
                "set_temperature" -> data.num("temperature")?.let {
                    listOf(c("setMode", RADIATOR_MANUAL), c("setManualModeTemperature", it.roundToInt().coerceIn(RADIATOR_MIN.toInt(), RADIATOR_MAX.toInt())))
                }.orEmpty()
                "set_hvac_mode" -> when (data.str("hvac_mode")) {
                    "off" -> listOf(power(false))
                    "heat" -> listOf(power(true))
                    else -> emptyList()
                }
                "turn_on" -> listOf(power(true))
                "turn_off" -> listOf(power(false))
                else -> emptyList()
            }
            Kind.SENSOR -> emptyList()
        }
    }

    /** The state a command leaves a remote in, for the switch drawn for it. */
    fun assumedAfter(commands: List<SbCommand>): Boolean? = when (commands.lastOrNull()?.command) {
        "turnOn" -> true
        "turnOff" -> false
        else -> null
    }

    // ---- Colour --------------------------------------------------------------------------

    /** Hue in degrees and saturation in percent, from 0–255 channels. */
    fun rgbToHs(r: Int, g: Int, b: Int): Pair<Double, Double> {
        val rf = r / BYTE_MAX.toDouble()
        val gf = g / BYTE_MAX.toDouble()
        val bf = b / BYTE_MAX.toDouble()
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        val hue = when {
            delta == 0.0 -> 0.0
            max == rf -> DEG_60 * (((gf - bf) / delta) % SIX)
            max == gf -> DEG_60 * (((bf - rf) / delta) + 2)
            else -> DEG_60 * (((rf - gf) / delta) + 4)
        }.let { if (it < 0) it + DEG_360 else it }
        val sat = if (max == 0.0) 0.0 else delta / max * PERCENT
        return (hue * 10).roundToInt() / 10.0 to (sat * 10).roundToInt() / 10.0
    }

    /** Full-value RGB for a hue in degrees and a saturation in percent. */
    fun hsToRgb(h: Double, s: Double): Triple<Int, Int, Int> {
        val sat = (s / PERCENT).coerceIn(0.0, 1.0)
        val hue = ((h % DEG_360) + DEG_360) % DEG_360
        val x = sat * (1 - kotlin.math.abs((hue / DEG_60) % 2 - 1))
        val (r, g, b) = when {
            hue < DEG_60 -> Triple(sat, x, 0.0)
            hue < 2 * DEG_60 -> Triple(x, sat, 0.0)
            hue < 3 * DEG_60 -> Triple(0.0, sat, x)
            hue < 4 * DEG_60 -> Triple(0.0, x, sat)
            hue < 5 * DEG_60 -> Triple(x, 0.0, sat)
            else -> Triple(sat, 0.0, x)
        }
        val m = 1 - sat
        fun ch(v: Double) = ((v + m) * BYTE_MAX).roundToInt().coerceIn(0, BYTE_MAX)
        return Triple(ch(r), ch(g), ch(b))
    }

    private val SILENT = setOf("Hub", "Hub Plus", "Remote", "Indoor Cam", "Pan/Tilt Cam")

    private const val PERCENT = 100
    private const val BYTE_MAX = 255
    private const val RGB = 3
    private const val MIN_KELVIN = 2700
    private const val MAX_KELVIN = 6500
    private const val RADIATOR_OFF = 2
    private const val RADIATOR_MANUAL = 1
    private const val RADIATOR_MIN = 4.0
    private const val RADIATOR_MAX = 35.0
    private const val DEG_60 = 60.0
    private const val DEG_360 = 360.0
    private const val SIX = 6
}
