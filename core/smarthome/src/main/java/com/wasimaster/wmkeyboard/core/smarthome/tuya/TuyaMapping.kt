package com.wasimaster.wmkeyboard.core.smarthome.tuya

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
import com.wasimaster.wmkeyboard.core.smarthome.parseJson
import com.wasimaster.wmkeyboard.core.smarthome.prim
import com.wasimaster.wmkeyboard.core.smarthome.slug
import com.wasimaster.wmkeyboard.core.smarthome.str
import com.wasimaster.wmkeyboard.core.smarthome.trimNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a data point accepts: an integer range with its decimal [scale]
 * (a raw 235 at scale 1 is 23.5), or the values of an enum.
 */
data class TuyaSpec(
    val type: String,
    val min: Double? = null,
    val max: Double? = null,
    val scale: Int = 0,
    val step: Double = 1.0,
    val unit: String? = null,
    val range: List<String> = emptyList(),
)

/**
 * One device of the linked app account. [status] is each data point's code
 * to its raw value; [specs] what the specification says of each code, and
 * [writable] the codes a command may set.
 */
data class TuyaDevice(
    val id: String,
    val name: String,
    val category: String,
    val online: Boolean,
    val uid: String? = null,
    /** Epoch seconds of the last report, when the list gives one. */
    val updated: Double? = null,
    val status: Map<String, JsonElement> = emptyMap(),
    val specs: Map<String, TuyaSpec> = emptyMap(),
    val writable: Set<String> = emptySet(),
) {
    val slug: String get() = "tuya_" + slug(id)

    fun has(code: String): Boolean = code in status

    fun bool(code: String): Boolean? = status[code].prim()?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    fun text(code: String): String? = status[code].prim()?.contentOrNull

    fun raw(code: String): Double? = status[code].prim()?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    /** The value of an integer data point with its scale applied; [defaultScale] when the specification is not known. */
    fun scaled(code: String, defaultScale: Int = 0): Double? {
        val v = raw(code) ?: return null
        val scale = specs[code]?.scale ?: defaultScale
        return if (scale == 0) v else v / 10.0.pow(scale)
    }

    /** A JSON value that Tuya may send as an object or as a string holding one (`colour_data`). */
    fun json(code: String): JsonObject? {
        val v = status[code] ?: return null
        return v.obj() ?: v.prim()?.contentOrNull?.let { parseJson(it).obj() }
    }

    fun firstOf(vararg codes: String): String? = codes.firstOrNull { has(it) }
}

/** One command of `POST /v1.0/devices/{id}/commands`. */
data class TuyaCommand(val code: String, val value: JsonElement)

data class TuyaScene(val id: String, val name: String, val homeId: String)

/**
 * Tuya's standard instruction set onto Home Assistant's domains, and a
 * service call back onto data point commands. Devices are told apart by
 * their category code (`dj` a light, `cz` a socket, `cl` a curtain…) and,
 * where the category does not say, by the data points they report.
 *
 * One device can be several entities: a fan with a light is a fan and a
 * light, a power strip one switch per socket, a thermometer a temperature,
 * a humidity and a battery.
 */
@Suppress("detekt.TooManyFunctions", "detekt.LargeClass")
object TuyaMapping {

    // ---- Parsing ---------------------------------------------------------------------

    /** `GET /v1.0/iot-01/associated-users/devices`: the devices, whether the page has more, and the key of the next. */
    fun parseDevicePage(body: String): Triple<List<TuyaDevice>, Boolean, String?> {
        val result = HomeJson.parseToJsonElement(body).obj()?.obj("result") ?: return Triple(emptyList(), false, null)
        val list = (result.objects("devices").ifEmpty { result.objects("list") }).mapNotNull(::parseDevice)
        return Triple(list, result.bool("has_more") == true, result.str("last_row_key"))
    }

    fun parseDevice(o: JsonObject): TuyaDevice? {
        val id = o.str("id") ?: return null
        return TuyaDevice(
            id = id,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: o.str("product_name") ?: id,
            category = o.str("category").orEmpty(),
            online = o.bool("online") ?: true,
            uid = o.str("uid"),
            updated = o.num("update_time"),
            status = parseStatusList(o.arr("status")?.mapNotNull { it.obj() }.orEmpty()),
        )
    }

    /** `GET /v1.0/devices/{id}/status`: a list of `{code, value}`. */
    fun parseStatus(body: String): Map<String, JsonElement> =
        parseStatusList(HomeJson.parseToJsonElement(body).obj()?.objects("result").orEmpty())

    private fun parseStatusList(items: List<JsonObject>): Map<String, JsonElement> =
        items.mapNotNull { s -> s.str("code")?.let { code -> s["value"]?.let { code to it } } }.toMap()

    /** `GET /v1.0/devices/{id}/specifications`: every code's spec, and which codes are commands. */
    fun parseSpecification(body: String): Pair<Map<String, TuyaSpec>, Set<String>> {
        val result = HomeJson.parseToJsonElement(body).obj()?.obj("result") ?: return emptyMap<String, TuyaSpec>() to emptySet()
        fun read(list: List<JsonObject>): Map<String, TuyaSpec> = list.mapNotNull { f ->
            val code = f.str("code") ?: return@mapNotNull null
            val values = f.str("values")?.let { parseJson(it).obj() } ?: f.obj("values")
            code to TuyaSpec(
                type = f.str("type").orEmpty(),
                min = values?.num("min"),
                max = values?.num("max"),
                scale = values?.num("scale")?.toInt() ?: 0,
                step = values?.num("step") ?: 1.0,
                unit = values?.str("unit")?.takeIf { it.isNotBlank() },
                range = values?.arr("range")?.mapNotNull { it.prim()?.contentOrNull }.orEmpty(),
            )
        }.toMap()
        val functions = read(result.objects("functions"))
        return (read(result.objects("status")) + functions) to functions.keys
    }

    /** `GET /v1.0/users/{uid}/homes`: id and name of each home. */
    fun parseHomes(body: String): List<Pair<String, String>> =
        HomeJson.parseToJsonElement(body).obj()?.objects("result").orEmpty().mapNotNull { h ->
            val id = h.str("home_id") ?: h.num("home_id")?.toLong()?.toString() ?: return@mapNotNull null
            id to (h.str("name") ?: id)
        }

    /** `GET /v1.0/homes/{home_id}/rooms`: id and name of each room. */
    fun parseRooms(body: String): List<Pair<String, String>> =
        HomeJson.parseToJsonElement(body).obj()?.obj("result")?.objects("rooms").orEmpty().mapNotNull { r ->
            val id = r.str("room_id") ?: r.num("room_id")?.toLong()?.toString() ?: return@mapNotNull null
            id to (r.str("name") ?: id)
        }

    /** `GET /v1.0/homes/{home_id}/rooms/{room_id}/devices`: the ids of the room's devices. */
    fun parseRoomDevices(body: String): List<String> =
        HomeJson.parseToJsonElement(body).obj()?.objects("result").orEmpty().mapNotNull { it.str("id") }

    /** `GET /v1.0/homes/{home_id}/scenes`: the tap-to-run scenes. */
    fun parseScenes(body: String, homeId: String): List<TuyaScene> =
        HomeJson.parseToJsonElement(body).obj()?.objects("result").orEmpty().mapNotNull { s ->
            val id = s.str("scene_id") ?: return@mapNotNull null
            TuyaScene(id, s.str("name") ?: id, homeId)
        }

    // ---- Devices to entities ----------------------------------------------------------

    private fun TuyaDevice.isLight(): Boolean = category in LIGHTS || firstOf(*LIGHT_SWITCHES) != null
    private fun TuyaDevice.isFan(): Boolean = category in FANS || firstOf(*FAN_SPEEDS) != null
    private fun TuyaDevice.isClimate(): Boolean = category in CLIMATES && has(TEMP_SET)
    private fun TuyaDevice.isCover(): Boolean = category in COVERS || category == GARAGE || (has(CONTROL) && specs[CONTROL]?.range?.contains("open") == true)

    /** The code the light's on and off sit on; a plain `switch` only on a light that is not also a fan. */
    private fun TuyaDevice.lightSwitch(): String? =
        if (!isLight()) null else firstOf(*LIGHT_SWITCHES) ?: if (category in FANS) null else firstOf(SWITCH)

    private fun TuyaDevice.fanSwitch(): String? =
        if (!isFan()) null else firstOf(FAN_SWITCH) ?: if (lightSwitch() == SWITCH) null else firstOf(SWITCH)

    /** Every entity [device] amounts to, named after it. */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun entities(device: TuyaDevice, nowSeconds: Double = HaTime.nowSeconds()): List<HaEntity> = buildList {
        val d = device
        val changed = d.updated ?: nowSeconds
        fun state(s: String): String = if (d.online) s else "unavailable"
        fun entity(domain: String, state: String, suffix: String = "", name: String = d.name, attrs: HomeAttrs.() -> Unit = {}) =
            add(homeEntity("$domain.${d.slug}$suffix", name, state(state), changed, attrs))
        val claimed = HashSet<String>()

        if (d.isLight()) lightEntity(d)?.let { (state, attrs, codes) ->
            claimed += codes
            entity("light", state, attrs = attrs)
        }
        if (d.isFan()) {
            val sw = d.fanSwitch()
            val speed = d.firstOf(*FAN_SPEEDS)
            val on = sw?.let { d.bool(it) } ?: ((d.raw(speed ?: "") ?: 0.0) > 0)
            claimed += listOfNotNull(sw, speed)
            entity("fan", if (on) "on" else "off") {
                val pct = speed?.let { fanPercent(d, it) }
                put("percentage", if (on) pct else 0)
                val steps = speed?.let { fanSteps(d, it) }
                if (steps != null) put("percentage_step", PERCENT.toDouble() / steps)
                put("supported_features", if (speed != null) HaFeatures.Fan.SET_SPEED else 0)
            }
        }
        if (d.isClimate()) {
            val sw = d.firstOf(SWITCH)
            val modes = climateModes(d)
            val mode = d.text(MODE)?.let(::haHvacMode)
            val on = sw?.let { d.bool(it) } ?: true
            val hvac = if (!on) "off" else mode ?: modes.firstOrNull { it != "off" } ?: "heat"
            val target = d.specs[TEMP_SET]
            claimed += listOfNotNull(sw, MODE, TEMP_SET, TEMP_CURRENT)
            var features = HaFeatures.Climate.TARGET_TEMPERATURE
            if (sw != null) features = features or HaFeatures.Climate.TURN_ON or HaFeatures.Climate.TURN_OFF
            entity("climate", hvac) {
                put("hvac_modes", modes)
                put("current_temperature", d.scaled(TEMP_CURRENT))
                put("temperature", d.scaled(TEMP_SET))
                put("min_temp", target?.min?.let { it / TEN.pow(target.scale) } ?: MIN_TEMP)
                put("max_temp", target?.max?.let { it / TEN.pow(target.scale) } ?: MAX_TEMP)
                put("target_temp_step", target?.let { it.step / TEN.pow(it.scale) } ?: 1.0)
                put("temperature_unit", if (d.text(TEMP_UNIT) == "f") "°F" else "°C")
                put("supported_features", features)
            }
        }
        if (d.isCover()) {
            if (d.category == GARAGE) {
                val sw = d.firstOf(SWITCH_1, SWITCH) ?: ""
                val open = d.bool(DOOR_CONTACT) ?: d.bool(sw) ?: false
                claimed += listOf(sw, DOOR_CONTACT)
                entity("cover", if (open) "open" else "closed") {
                    put("device_class", "garage")
                    put("supported_features", HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE)
                }
            } else {
                val positionCode = d.firstOf(PERCENT_STATE, PERCENT_CONTROL)
                val position = positionCode?.let { d.raw(it) }?.roundToInt()
                val moving = d.text(WORK_STATE)
                val state = when {
                    moving == "opening" || moving == "closing" -> moving
                    position == null -> "unknown"
                    position <= 0 -> "closed"
                    else -> "open"
                }
                var features = HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE
                if (d.specs[CONTROL]?.range?.contains("stop") != false) features = features or HaFeatures.Cover.STOP
                if (d.has(PERCENT_CONTROL) || PERCENT_CONTROL in d.writable) features = features or HaFeatures.Cover.SET_POSITION
                claimed += listOf(CONTROL, PERCENT_STATE, PERCENT_CONTROL, WORK_STATE)
                entity("cover", state) {
                    put("device_class", "curtain")
                    put("current_position", position)
                    put("supported_features", features)
                }
            }
        }

        // What is left that switches: a plug, each socket of a strip, a wall switch's gangs, a USB port.
        val switches = d.status.keys.filter { (it == SWITCH || SWITCH_N.matches(it) || USB_N.matches(it)) && it !in claimed && d.bool(it) != null }.sorted()
        for (code in switches) {
            val one = switches.size == 1
            val label = when {
                one -> d.name
                USB_N.matches(code) -> "${d.name} USB ${code.removePrefix("switch_usb")}"
                code == SWITCH -> d.name
                else -> "${d.name} ${code.removePrefix("switch_")}"
            }
            entity("switch", if (d.bool(code) == true) "on" else "off", if (one) "" else "_" + slug(code), label) {
                put("device_class", if (d.category in OUTLETS) "outlet" else "switch")
            }
        }

        // Sensors ride along whatever the device mainly is.
        fun measure(code: String, deviceClass: String, suffix: String, unit: String?, defaultScale: Int = 0, stateClass: String = "measurement") {
            if (code in claimed) return
            val v = d.scaled(code, defaultScale) ?: return
            claimed += code
            entity("sensor", trimNumber(v), suffix) {
                put("device_class", deviceClass)
                put("state_class", stateClass)
                put("unit_of_measurement", unit)
            }
        }
        fun binary(code: String, deviceClass: String, suffix: String, on: (TuyaDevice) -> Boolean?) {
            if (code in claimed || !d.has(code)) return
            val value = on(d) ?: return
            claimed += code
            entity("binary_sensor", if (value) "on" else "off", suffix) { put("device_class", deviceClass) }
        }
        val climate = d.isClimate()
        if (!climate) {
            measure(VA_TEMPERATURE, "temperature", "_temperature", "°C", defaultScale = 1)
            measure(TEMP_CURRENT, "temperature", "_temperature", "°C")
        }
        measure(VA_HUMIDITY, "humidity", "_humidity", "%")
        measure(HUMIDITY_VALUE, "humidity", "_humidity", "%")
        measure(BATTERY_PERCENTAGE, "battery", "_battery", "%")
        measure(VA_BATTERY, "battery", "_battery", "%")
        measure(CUR_POWER, "power", "_power", "W", defaultScale = 1)
        measure(CUR_VOLTAGE, "voltage", "_voltage", "V", defaultScale = 1)
        measure(CUR_CURRENT, "current", "_current", "mA")
        measure(ADD_ELE, "energy", "_energy", "kWh", defaultScale = 3, stateClass = "total_increasing")
        measure(CO2_VALUE, "carbon_dioxide", "_co2", "ppm")
        measure(PM25_VALUE, "pm25", "_pm25", "µg/m³")
        if (d.category == LUX_SENSOR) measure(BRIGHT_VALUE, "illuminance", "_illuminance", "lx")
        binary(DOOR_CONTACT, "opening", "_contact") { it.bool(DOOR_CONTACT) }
        binary(PIR, "motion", "_motion") { it.text(PIR)?.let { v -> v == "pir" } }
        binary(PRESENCE, "occupancy", "_presence") { it.text(PRESENCE)?.let { v -> v == "presence" } }
        binary(WATER, "moisture", "_water") { it.text(WATER)?.let { v -> v == "alarm" || v == "1" } }
        binary(SMOKE_STATUS, "smoke", "_smoke") { it.text(SMOKE_STATUS)?.let { v -> v == "alarm" || v == "1" } }
        binary(SMOKE_STATE, "smoke", "_smoke") { it.text(SMOKE_STATE)?.let { v -> v == "alarm" || v == "1" } }
        binary(GAS_STATUS, "gas", "_gas") { it.text(GAS_STATUS)?.let { v -> v == "alarm" || v == "1" } }
        binary(GAS_STATE, "gas", "_gas") { it.text(GAS_STATE)?.let { v -> v == "alarm" || v == "1" } }
    }

    /** A light's state, its attributes, and the codes it took. */
    private fun lightEntity(d: TuyaDevice): Triple<String, HomeAttrs.() -> Unit, List<String>>? {
        val sw = d.lightSwitch() ?: return null
        val on = d.bool(sw) == true
        val bright = d.firstOf(*BRIGHTS)
        val temp = d.firstOf(*TEMPS)
        val colour = d.firstOf(*COLOURS)
        val mode = d.text(WORK_MODE)
        val hsv = colour?.let { hsv(d, it) }
        val modes = buildList {
            if (colour != null) add("hs")
            if (temp != null) add("color_temp")
            if (isEmpty() && bright != null) add("brightness")
            if (isEmpty()) add("onoff")
        }
        val inColour = colour != null && (mode == "colour" || (mode == null && temp == null && bright == null))
        val brightness = if (inColour) hsv?.third?.let { (it * BYTE_MAX).roundToInt() } else bright?.let { b -> fraction(d, b, defaultRange(b))?.let { (it * BYTE_MAX).roundToInt() } }
        val kelvin = temp?.let { t -> fraction(d, t, defaultRange(t))?.let { (MIN_KELVIN + it * (MAX_KELVIN - MIN_KELVIN)).roundToInt() } }
        val attrs: HomeAttrs.() -> Unit = {
            put("supported_color_modes", modes)
            put("color_mode", if (!on) null else if (inColour) "hs" else if ("color_temp" in modes) "color_temp" else modes.first())
            put("brightness", brightness?.coerceIn(1, BYTE_MAX))
            if (hsv != null) put("hs_color", listOf(hsv.first, hsv.second * PERCENT))
            put("color_temp_kelvin", kelvin)
            if (temp != null) {
                put("min_color_temp_kelvin", MIN_KELVIN)
                put("max_color_temp_kelvin", MAX_KELVIN)
            }
        }
        return Triple(if (on) "on" else "off", attrs, listOfNotNull(sw, bright, temp, colour, WORK_MODE))
    }

    /** Hue in degrees, saturation and value as fractions, from `colour_data` or `colour_data_v2`. */
    private fun hsv(d: TuyaDevice, code: String): Triple<Double, Double, Double>? {
        val o = d.json(code) ?: return null
        val sMax = if (code == COLOUR_V2) V2_MAX else V1_MAX
        return Triple(o.num("h") ?: 0.0, (o.num("s") ?: 0.0) / sMax, (o.num("v") ?: 0.0) / sMax)
    }

    private fun defaultRange(code: String): Pair<Double, Double> = when (code) {
        BRIGHT_V2 -> V2_MIN_BRIGHT to V2_MAX
        TEMP_V2 -> 0.0 to V2_MAX
        TEMP_V1 -> 0.0 to V1_MAX
        else -> V1_MIN_BRIGHT to V1_MAX
    }

    /** Where [code]'s raw value sits in its range, 0 to 1. */
    private fun fraction(d: TuyaDevice, code: String, fallback: Pair<Double, Double>): Double? {
        val v = d.raw(code) ?: return null
        val spec = d.specs[code]
        val min = spec?.min ?: fallback.first
        val max = spec?.max ?: fallback.second
        if (max <= min) return null
        return ((v - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    /** The raw value [fraction] of the way through [code]'s range, on its step. */
    private fun rawAt(d: TuyaDevice, code: String, fraction: Double, fallback: Pair<Double, Double>): Int {
        val spec = d.specs[code]
        val min = spec?.min ?: fallback.first
        val max = spec?.max ?: fallback.second
        return (min + fraction.coerceIn(0.0, 1.0) * (max - min)).roundToInt()
    }

    private fun fanSteps(d: TuyaDevice, code: String): Int? {
        val spec = d.specs[code]
        if (spec != null && spec.range.isNotEmpty()) return spec.range.size
        if (code == FAN_SPEED_PERCENT) return null
        val max = spec?.max ?: return null
        val min = spec.min ?: 1.0
        return (max - min + 1).toInt().takeIf { it in 2..MAX_FAN_STEPS }
    }

    private fun fanPercent(d: TuyaDevice, code: String): Int? {
        val spec = d.specs[code]
        if (spec != null && spec.range.isNotEmpty()) {
            val i = spec.range.indexOf(d.text(code))
            return if (i < 0) null else ((i + 1) * PERCENT.toDouble() / spec.range.size).roundToInt()
        }
        val v = d.raw(code) ?: return null
        val min = spec?.min ?: 1.0
        val max = spec?.max ?: if (code == FAN_SPEED_PERCENT) PERCENT.toDouble() else return null
        if (max <= min) return null
        return (((v - min + 1) / (max - min + 1)) * PERCENT).roundToInt().coerceIn(0, PERCENT)
    }

    private fun climateModes(d: TuyaDevice): List<String> {
        val fromEnum = d.specs[MODE]?.range.orEmpty().mapNotNull(::haHvacMode).distinct()
        val base = fromEnum.ifEmpty { listOf(if (d.category == AIR_CONDITIONER) "cool" else "heat") }
        return (if (d.has(SWITCH)) listOf("off") + base else base).distinct()
    }

    /** A Tuya mode as a Home Assistant HVAC mode, or null for a schedule or preset that has none. */
    private fun haHvacMode(tuya: String): String? = when (tuya) {
        "cold", "cool", "cooling" -> "cool"
        "hot", "heat", "heating", "manual", "comfortable" -> "heat"
        "wind", "fan" -> "fan_only"
        "wet", "dehumidification", "dry" -> "dry"
        "auto" -> "auto"
        else -> null
    }

    /** A scene as a `scene.` entity; Tuya keeps no state for one. */
    fun sceneEntity(scene: TuyaScene, nowSeconds: Double = HaTime.nowSeconds()): HaEntity =
        homeEntity("scene.tuya_" + slug(scene.id), scene.name, "unknown", nowSeconds)

    // ---- Service calls to commands ----------------------------------------------------

    /**
     * The commands a service call on [entityId] means for [device], given its
     * current entity (a toggle needs the state). Empty when the call has no
     * equivalent, which the backend reports as a refusal.
     */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod", "detekt.ReturnCount")
    fun commands(call: HaServiceCall, entityId: String, device: TuyaDevice, current: HaEntity?): List<TuyaCommand> {
        val d = device
        val data = call.data
        fun cmd(code: String, value: Any): TuyaCommand = TuyaCommand(
            code,
            when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            },
        )
        val domain = entityId.substringBefore('.')
        val isOn = current?.state == "on"
        when (domain) {
            "switch" -> {
                val code = switchCode(entityId, d) ?: return emptyList()
                return when (call.service) {
                    "turn_on" -> listOf(cmd(code, true))
                    "turn_off" -> listOf(cmd(code, false))
                    "toggle" -> listOf(cmd(code, !isOn))
                    else -> emptyList()
                }
            }
            "light" -> {
                val sw = d.lightSwitch() ?: return emptyList()
                return when (call.service) {
                    "turn_off" -> listOf(cmd(sw, false))
                    "toggle" -> listOf(cmd(sw, !isOn))
                    "turn_on" -> buildList {
                        add(cmd(sw, true))
                        val colour = d.firstOf(*COLOURS)
                        val bright = d.firstOf(*BRIGHTS)
                        val temp = d.firstOf(*TEMPS)
                        val pct = data.num("brightness_pct") ?: data.num("brightness")?.let { it * PERCENT / BYTE_MAX }
                        val hs = data.arr("hs_color")?.let { a -> a.getOrNull(0).prim()?.doubleOrNull?.let { h -> a.getOrNull(1).prim()?.doubleOrNull?.let { s -> h to s } } }
                        val kelvin = data.num("color_temp_kelvin")
                        val inColour = d.text(WORK_MODE) == "colour"
                        when {
                            hs != null && colour != null -> {
                                val max = if (colour == COLOUR_V2) V2_MAX else V1_MAX
                                val v = (pct?.div(PERCENT)) ?: hsv(d, colour)?.third?.takeIf { it > 0 } ?: 1.0
                                if (d.has(WORK_MODE)) add(cmd(WORK_MODE, "colour"))
                                add(TuyaCommand(colour, JsonPrimitive(colourJson(hs.first, hs.second / PERCENT, v, max))))
                            }
                            pct != null && inColour && colour != null -> {
                                val max = if (colour == COLOUR_V2) V2_MAX else V1_MAX
                                val (h, s, _) = hsv(d, colour) ?: Triple(0.0, 0.0, 0.0)
                                add(TuyaCommand(colour, JsonPrimitive(colourJson(h, s, pct / PERCENT, max))))
                            }
                            pct != null && bright != null -> add(cmd(bright, rawAt(d, bright, pct / PERCENT, defaultRange(bright))))
                        }
                        if (kelvin != null && temp != null) {
                            if (d.has(WORK_MODE)) add(cmd(WORK_MODE, "white"))
                            add(cmd(temp, rawAt(d, temp, (kelvin - MIN_KELVIN) / (MAX_KELVIN - MIN_KELVIN), defaultRange(temp))))
                        }
                    }
                    else -> emptyList()
                }
            }
            "fan" -> {
                val sw = d.fanSwitch()
                val speed = d.firstOf(*FAN_SPEEDS)
                fun speedCommand(pct: Double): TuyaCommand? {
                    speed ?: return null
                    val spec = d.specs[speed]
                    if (spec != null && spec.range.isNotEmpty()) {
                        val i = ((pct / PERCENT) * spec.range.size).roundToInt().coerceIn(1, spec.range.size) - 1
                        return cmd(speed, spec.range[i])
                    }
                    val min = spec?.min ?: 1.0
                    val max = spec?.max ?: PERCENT.toDouble()
                    return cmd(speed, (min + (pct / PERCENT) * (max - min)).roundToInt().coerceIn(min.toInt(), max.toInt()))
                }
                return when (call.service) {
                    "turn_on" -> listOfNotNull(sw?.let { cmd(it, true) }, data.num("percentage")?.let(::speedCommand))
                    "turn_off" -> listOfNotNull(sw?.let { cmd(it, false) })
                    "toggle" -> listOfNotNull(sw?.let { cmd(it, !isOn) })
                    "set_percentage" -> data.num("percentage")?.let { pct ->
                        if (pct <= 0) listOfNotNull(sw?.let { cmd(it, false) }) else listOfNotNull(sw?.let { cmd(it, true) }, speedCommand(pct))
                    }.orEmpty()
                    else -> emptyList()
                }
            }
            "climate" -> {
                val sw = d.firstOf(SWITCH)
                return when (call.service) {
                    "set_temperature" -> data.num("temperature")?.let { t ->
                        val scale = d.specs[TEMP_SET]?.scale ?: 0
                        listOf(cmd(TEMP_SET, (t * TEN.pow(scale)).roundToInt()))
                    }.orEmpty()
                    "set_hvac_mode" -> {
                        val mode = data.str("hvac_mode") ?: return emptyList()
                        if (mode == "off") return listOfNotNull(sw?.let { cmd(it, false) })
                        val tuya = d.specs[MODE]?.range.orEmpty().firstOrNull { haHvacMode(it) == mode }
                        listOfNotNull(sw?.let { cmd(it, true) }, tuya?.let { cmd(MODE, it) })
                    }
                    "turn_on" -> listOfNotNull(sw?.let { cmd(it, true) })
                    "turn_off" -> listOfNotNull(sw?.let { cmd(it, false) })
                    else -> emptyList()
                }
            }
            "cover" -> {
                if (d.category == GARAGE) {
                    val sw = d.firstOf(SWITCH_1, SWITCH) ?: return emptyList()
                    return when (call.service) {
                        "open_cover" -> listOf(cmd(sw, true))
                        "close_cover" -> listOf(cmd(sw, false))
                        else -> emptyList()
                    }
                }
                return when (call.service) {
                    "open_cover" -> listOf(cmd(CONTROL, "open"))
                    "close_cover" -> listOf(cmd(CONTROL, "close"))
                    "stop_cover" -> listOf(cmd(CONTROL, "stop"))
                    "set_cover_position" -> data.num("position")?.let { listOf(cmd(PERCENT_CONTROL, it.roundToInt().coerceIn(0, PERCENT))) }.orEmpty()
                    else -> emptyList()
                }
            }
            else -> return emptyList()
        }
    }

    /** The data point a `switch.` entity stands for: the one switch, or the code its suffix names. */
    private fun switchCode(entityId: String, d: TuyaDevice): String? {
        val objectId = entityId.substringAfter('.')
        if (objectId == d.slug) {
            return d.status.keys.firstOrNull { (it == SWITCH || SWITCH_N.matches(it) || USB_N.matches(it)) && d.bool(it) != null && it != d.lightSwitch() && it != d.fanSwitch() }
        }
        val suffix = objectId.removePrefix(d.slug + "_")
        return d.status.keys.firstOrNull { slug(it) == suffix }
    }

    private fun colourJson(h: Double, s: Double, v: Double, max: Double): String =
        HomeJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("h", h.roundToInt().coerceIn(0, HUE_MAX))
                put("s", (s.coerceIn(0.0, 1.0) * max).roundToInt())
                put("v", (v.coerceIn(0.0, 1.0) * max).roundToInt())
            },
        )

    fun commandsBody(commands: List<TuyaCommand>): String =
        HomeJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "commands",
                    buildJsonArray {
                        commands.forEach { c -> add(buildJsonObject { put("code", c.code); put("value", c.value) }) }
                    },
                )
            },
        )

    // ---- Vocabulary ----------------------------------------------------------------------

    private val LIGHTS = setOf("dj", "dd", "xdd", "fwd", "dc", "tgq", "tyndj", "gyd", "sxd", "fsd")
    private val FANS = setOf("fs", "fsd", "fskg")
    private val CLIMATES = setOf("wk", "wkf", "kt", "qn", "rs")
    private val COVERS = setOf("cl", "clkg", "mc")
    private val OUTLETS = setOf("cz", "pc")
    private const val GARAGE = "ckmkzq"
    private const val AIR_CONDITIONER = "kt"
    private const val LUX_SENSOR = "ldcg"

    private const val SWITCH = "switch"
    private const val SWITCH_1 = "switch_1"
    private const val FAN_SWITCH = "switch_fan"
    private val LIGHT_SWITCHES = arrayOf("switch_led", "switch_led_1")
    private val SWITCH_N = Regex("switch_\\d+")
    private val USB_N = Regex("switch_usb\\d+")
    private const val BRIGHT_V2 = "bright_value_v2"
    private const val BRIGHT_VALUE = "bright_value"
    private val BRIGHTS = arrayOf(BRIGHT_V2, BRIGHT_VALUE, "bright_value_1")
    private const val TEMP_V2 = "temp_value_v2"
    private const val TEMP_V1 = "temp_value"
    private val TEMPS = arrayOf(TEMP_V2, TEMP_V1)
    private const val COLOUR_V2 = "colour_data_v2"
    private val COLOURS = arrayOf(COLOUR_V2, "colour_data")
    private const val WORK_MODE = "work_mode"
    private const val FAN_SPEED_PERCENT = "fan_speed_percent"
    private val FAN_SPEEDS = arrayOf(FAN_SPEED_PERCENT, "fan_speed")
    private const val MODE = "mode"
    private const val TEMP_SET = "temp_set"
    private const val TEMP_CURRENT = "temp_current"
    private const val TEMP_UNIT = "temp_unit_convert"
    private const val CONTROL = "control"
    private const val PERCENT_CONTROL = "percent_control"
    private const val PERCENT_STATE = "percent_state"
    private const val WORK_STATE = "work_state"
    private const val DOOR_CONTACT = "doorcontact_state"
    private const val VA_TEMPERATURE = "va_temperature"
    private const val VA_HUMIDITY = "va_humidity"
    private const val HUMIDITY_VALUE = "humidity_value"
    private const val BATTERY_PERCENTAGE = "battery_percentage"
    private const val VA_BATTERY = "va_battery"
    private const val CUR_POWER = "cur_power"
    private const val CUR_VOLTAGE = "cur_voltage"
    private const val CUR_CURRENT = "cur_current"
    private const val ADD_ELE = "add_ele"
    private const val CO2_VALUE = "co2_value"
    private const val PM25_VALUE = "pm25_value"
    private const val PIR = "pir"
    private const val PRESENCE = "presence_state"
    private const val WATER = "watersensor_state"
    private const val SMOKE_STATUS = "smoke_sensor_status"
    private const val SMOKE_STATE = "smoke_sensor_state"
    private const val GAS_STATUS = "gas_sensor_status"
    private const val GAS_STATE = "gas_sensor_state"

    private const val TEN = 10.0
    private const val PERCENT = 100
    private const val BYTE_MAX = 255
    private const val HUE_MAX = 360
    private const val V2_MAX = 1000.0
    private const val V1_MAX = 255.0
    private const val V2_MIN_BRIGHT = 10.0
    private const val V1_MIN_BRIGHT = 25.0
    private const val MIN_KELVIN = 2700.0
    private const val MAX_KELVIN = 6500.0
    private const val MIN_TEMP = 5.0
    private const val MAX_TEMP = 35.0
    private const val MAX_FAN_STEPS = 12
}
