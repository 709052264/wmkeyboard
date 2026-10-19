package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import com.google.home.HomeDevice
import com.google.home.Room
import com.google.home.Trait
import com.google.home.google.ExtendedFanControl
import com.google.home.matter.standard.BooleanState
import com.google.home.matter.standard.ColorControl
import com.google.home.matter.standard.ColorControlTrait
import com.google.home.matter.standard.DoorLock
import com.google.home.matter.standard.DoorLockTrait
import com.google.home.matter.standard.FanControl
import com.google.home.matter.standard.FanControlTrait
import com.google.home.matter.standard.IlluminanceMeasurement
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.MediaPlayback
import com.google.home.matter.standard.MediaPlaybackTrait
import com.google.home.matter.standard.OccupancySensing
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.RelativeHumidityMeasurement
import com.google.home.matter.standard.TemperatureMeasurement
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatTrait
import com.google.home.matter.standard.WindowCovering
import com.wasimaster.wmkeyboard.core.homeassistant.HaArea
import com.wasimaster.wmkeyboard.core.homeassistant.HaDevice
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntityEntry
import com.wasimaster.wmkeyboard.core.homeassistant.HaFeatures
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Matter traits, as the Home APIs expose them, onto Home Assistant's
 * domains and attribute names; and a service call back onto the traits'
 * suspend commands. The SDK's trait objects are live, so [Live] keeps the
 * latest set per entity for the commands to call.
 */
@Suppress("detekt.TooManyFunctions")
object GoogleHomeMapping {

    /** What a command needs: the device and its current trait objects. */
    class Live(val deviceId: String, val name: String, val traits: Set<Trait>) {
        inline fun <reified T : Trait> trait(): T? = traits.firstOrNull { it is T } as? T
    }

    private fun slug(raw: String): String = raw.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_')

    private fun id(domain: String, deviceId: String, suffix: String = ""): String = "$domain.gh_" + slug(deviceId) + suffix

    fun registry(rooms: List<Room>, live: List<Live>, deviceRooms: Map<String, String?>): HaRegistry = HaRegistry(
        areas = rooms.map { HaArea(it.id.id, it.name) },
        devices = live.map { it.deviceId }.distinct().map { HaDevice(it, deviceRooms[it], null) },
        entries = emptyList(),
    ).let { r ->
        // Every entity of a device sits in the device's room.
        r.copy(entries = entitiesOf(live).map { (entityId, deviceId) -> HaEntityEntry(entityId, areaId = deviceRooms[deviceId], deviceId = deviceId) })
    }

    private fun entitiesOf(live: List<Live>): List<Pair<String, String>> = live.flatMap { l ->
        kinds(l.traits, emptySet()).map { (domain, suffix) -> id(domain, l.deviceId, suffix) to l.deviceId }
    }

    /** The domains a trait set amounts to, with the id suffix of each. */
    private fun kinds(traits: Set<Trait>, types: Set<String>): List<Pair<String, String>> = buildList {
        val light = types.any { it.contains("Light", ignoreCase = true) && !it.contains("Switch") && !it.contains("Sensor") } ||
            traits.any { it is LevelControl || it is ColorControl }
        when {
            traits.any { it is Thermostat } -> add("climate" to "")
            traits.any { it is DoorLock } -> add("lock" to "")
            traits.any { it is WindowCovering } -> add("cover" to "")
            traits.any { it is FanControl } -> add("fan" to "")
            traits.any { it is MediaPlayback } -> add("media_player" to "")
            traits.any { it is OnOff } -> add((if (light) "light" else "switch") to "")
        }
        if (traits.any { it is TemperatureMeasurement } && traits.none { it is Thermostat }) add("sensor" to "_temperature")
        if (traits.any { it is RelativeHumidityMeasurement }) add("sensor" to "_humidity")
        if (traits.any { it is IlluminanceMeasurement }) add("sensor" to "_illuminance")
        if (traits.any { it is BooleanState }) add("binary_sensor" to "_contact")
        if (traits.any { it is OccupancySensing }) add("binary_sensor" to "_occupancy")
    }

    /** Every entity of [device] from its [traits], each with the [Live] a command needs. */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun entities(device: HomeDevice, types: Set<String>, traits: Set<Trait>, online: Boolean, nowSeconds: Double = HaTime.nowSeconds()): List<Pair<HaEntity, Live>> {
        val live = Live(device.id.id, device.name, traits)
        val name = device.name.ifBlank { "Device" }
        fun entity(domain: String, suffix: String, state: String, attrs: Map<String, JsonElement>): Pair<HaEntity, Live> = HaEntity(
            entityId = id(domain, device.id.id, suffix),
            state = if (!online) "unavailable" else state,
            attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive(name)) + attrs),
            lastChanged = nowSeconds,
            lastUpdated = nowSeconds,
        ) to live
        fun attrs(build: MutableMap<String, JsonElement>.() -> Unit): Map<String, JsonElement> = LinkedHashMap<String, JsonElement>().apply(build)
        fun MutableMap<String, JsonElement>.attr(key: String, v: Any?) {
            when (v) {
                null -> Unit
                is String -> put(key, JsonPrimitive(v))
                is Number -> put(key, JsonPrimitive(v))
                is Boolean -> put(key, JsonPrimitive(v))
                is List<*> -> put(key, JsonArray(v.map { x -> if (x is Number) JsonPrimitive(x) else JsonPrimitive(x.toString()) }))
                else -> put(key, JsonPrimitive(v.toString()))
            }
        }
        return kinds(traits, types).map { (domain, suffix) ->
            when (domain) {
                "climate" -> {
                    val t = live.trait<Thermostat>()
                    // An air conditioner carries OnOff beside its mode: off there is off whatever the mode says.
                    val mode = if (live.trait<OnOff>()?.onOff == false) "off" else haMode(t?.systemMode)
                    val cooler = types.any { it.contains("AirConditioner") }
                    val heat = t?.occupiedHeatingSetpoint?.let { it / CENTI }
                    val cool = t?.occupiedCoolingSetpoint?.let { it / CENTI }
                    val range = mode == "heat_cool" && heat != null && cool != null
                    entity(
                        domain, suffix, mode,
                        attrs {
                            attr("hvac_modes", if (cooler) listOf("off", "cool", "dry", "fan_only", "heat_cool") else listOf("off", "heat", "cool", "heat_cool"))
                            attr("current_temperature", t?.localTemperature?.let { it / CENTI })
                            attr("temperature", if (range) null else if (mode == "cool") cool ?: heat else heat ?: cool)
                            if (range) {
                                attr("target_temp_low", heat)
                                attr("target_temp_high", cool)
                            }
                            attr("temperature_unit", "°C")
                            attr("min_temp", MIN_TEMP_C)
                            attr("max_temp", MAX_TEMP_C)
                            attr("target_temp_step", HALF)
                            attr("supported_features", HaFeatures.Climate.TARGET_TEMPERATURE or (if (range) HaFeatures.Climate.TARGET_TEMPERATURE_RANGE else 0) or HaFeatures.Climate.TURN_ON or HaFeatures.Climate.TURN_OFF)
                        },
                    )
                }
                "lock" -> {
                    val state = when (live.trait<DoorLock>()?.lockState) {
                        DoorLockTrait.DlLockState.Locked -> "locked"
                        DoorLockTrait.DlLockState.Unlocked, DoorLockTrait.DlLockState.Unlatched -> "unlocked"
                        else -> "unknown"
                    }
                    entity(domain, suffix, state, attrs { attr("supported_features", 0) })
                }
                "cover" -> {
                    val w = live.trait<WindowCovering>()
                    // Matter counts lift from open (0) to closed (100 %); Home Assistant the other way.
                    val closedPct = w?.currentPositionLiftPercent100ths?.toInt()?.let { it / HUNDREDTHS }
                    val position = closedPct?.let { PERCENT - it }
                    entity(
                        domain, suffix,
                        when {
                            position == null -> "unknown"
                            position <= 0 -> "closed"
                            else -> "open"
                        },
                        attrs {
                            attr("device_class", "blind")
                            attr("current_position", position)
                            attr("supported_features", HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE or HaFeatures.Cover.STOP or HaFeatures.Cover.SET_POSITION)
                        },
                    )
                }
                "fan" -> {
                    val f = live.trait<FanControl>()
                    // A fan with a few speeds (speedMax) reports them there, and
                    // its percentSetting can sit at 0 while it runs: speeds first,
                    // then what it is doing, then what it was asked.
                    val speeds = f?.speedMax?.toInt()?.takeIf { it > 0 }
                    val speed = (f?.speedCurrent ?: f?.speedSetting)?.toInt()
                    val pct = when {
                        speeds != null && speed != null -> speed * PERCENT / speeds
                        else -> (f?.percentCurrent ?: f?.percentSetting)?.toInt() ?: when (f?.fanMode) {
                            FanControlTrait.FanModeEnum.Low -> FAN_LOW
                            FanControlTrait.FanModeEnum.Medium -> FAN_MEDIUM
                            FanControlTrait.FanModeEnum.High, FanControlTrait.FanModeEnum.On -> PERCENT
                            else -> 0
                        }
                    }
                    val on = live.trait<OnOff>()?.onOff ?: f?.fanMode?.let { it != FanControlTrait.FanModeEnum.Off } ?: (pct > 0)
                    val custom = live.trait<ExtendedFanControl>()
                    val presets = custom?.customFanModes?.speeds?.map { it.speedName }?.filter { it.isNotBlank() }.orEmpty()
                    entity(
                        domain, suffix, if (on) "on" else "off",
                        attrs {
                            attr("percentage", if (on) pct else 0)
                            attr("percentage_step", speeds?.let { PERCENT_D / it } ?: 1)
                            if (presets.isNotEmpty()) {
                                attr("preset_modes", presets)
                                attr("preset_mode", custom?.customFanMode?.takeIf { on && it in presets })
                            }
                            attr("supported_features", HaFeatures.Fan.SET_SPEED or (if (presets.isNotEmpty()) HaFeatures.Fan.PRESET_MODE else 0))
                        },
                    )
                }
                "media_player" -> {
                    val m = live.trait<MediaPlayback>()
                    val on = live.trait<OnOff>()?.onOff ?: true
                    val state = when {
                        !on -> "off"
                        m?.currentState == MediaPlaybackTrait.PlaybackStateEnum.Playing -> "playing"
                        m?.currentState == MediaPlaybackTrait.PlaybackStateEnum.Paused -> "paused"
                        else -> "idle"
                    }
                    var features = HaFeatures.Media.PLAY or HaFeatures.Media.PAUSE or HaFeatures.Media.STOP or HaFeatures.Media.NEXT_TRACK or HaFeatures.Media.PREVIOUS_TRACK
                    if (live.trait<LevelControl>() != null) features = features or HaFeatures.Media.VOLUME_SET
                    if (live.trait<OnOff>() != null) features = features or HaFeatures.Media.TURN_ON or HaFeatures.Media.TURN_OFF
                    entity(
                        domain, suffix, state,
                        attrs {
                            attr("volume_level", live.trait<LevelControl>()?.currentLevel?.let { it.toInt() / BYTE_MAX_D })
                            attr("supported_features", features)
                        },
                    )
                }
                "light", "switch" -> {
                    val on = live.trait<OnOff>()?.onOff == true
                    val level = live.trait<LevelControl>()
                    val color = live.trait<ColorControl>()
                    val modes = buildList {
                        if (color?.currentHue != null) add("hs")
                        if (color?.colorTemperatureMireds != null) add("color_temp")
                        if (isEmpty() && level != null) add("brightness")
                        if (isEmpty()) add("onoff")
                    }
                    entity(
                        domain, suffix, if (on) "on" else "off",
                        attrs {
                            if (domain == "light") {
                                attr("supported_color_modes", modes)
                                if (on) attr("color_mode", if (color?.colorMode == ColorControlTrait.ColorModeEnum.ColorTemperatureMireds && "color_temp" in modes) "color_temp" else modes.first())
                                attr("brightness", level?.currentLevel?.let { (it.toInt() * BYTE_MAX) / MATTER_LEVEL_MAX })
                                val hue = color?.currentHue?.toInt()
                                val sat = color?.currentSaturation?.toInt()
                                if (hue != null && sat != null) attr("hs_color", listOf(hue * DEGREES / MATTER_BYTE_MAX, sat * PERCENT_D / MATTER_BYTE_MAX))
                                color?.colorTemperatureMireds?.toInt()?.takeIf { it > 0 }?.let { attr("color_temp_kelvin", MIREK_KELVIN / it) }
                                color?.colorTempPhysicalMaxMireds?.toInt()?.takeIf { it > 0 }?.let { attr("min_color_temp_kelvin", MIREK_KELVIN / it) }
                                color?.colorTempPhysicalMinMireds?.toInt()?.takeIf { it > 0 }?.let { attr("max_color_temp_kelvin", MIREK_KELVIN / it) }
                            } else {
                                attr("device_class", if (types.any { it.contains("PluginUnit") }) "outlet" else "switch")
                            }
                        },
                    )
                }
                "sensor" -> if (suffix == "_humidity") {
                    val v = live.trait<RelativeHumidityMeasurement>()?.measuredValue?.toInt()?.let { it / CENTI }
                    entity(
                        domain, suffix, v?.roundToInt()?.toString() ?: "unknown",
                        attrs {
                            attr("device_class", "humidity")
                            attr("unit_of_measurement", "%")
                            attr("state_class", "measurement")
                        },
                    )
                } else if (suffix == "_temperature") {
                    val v = live.trait<TemperatureMeasurement>()?.measuredValue?.let { it / CENTI }
                    entity(
                        domain, suffix, v?.let { ((it * TENTH).roundToInt() / TENTH).toString() } ?: "unknown",
                        attrs {
                            attr("device_class", "temperature")
                            attr("unit_of_measurement", "°C")
                            attr("state_class", "measurement")
                        },
                    )
                } else {
                    val raw = live.trait<IlluminanceMeasurement>()?.measuredValue?.toInt()
                    val lux = raw?.let { 10.0.pow((it - 1) / LUX_SCALE) }
                    entity(
                        domain, suffix, lux?.roundToInt()?.toString() ?: "unknown",
                        attrs {
                            attr("device_class", "illuminance")
                            attr("unit_of_measurement", "lx")
                            attr("state_class", "measurement")
                        },
                    )
                }
                else -> if (suffix == "_contact") {
                    // BooleanState true is "closed" for a contact sensor.
                    val closed = live.trait<BooleanState>()?.stateValue
                    entity(domain, suffix, if (closed == null) "unknown" else if (closed) "off" else "on", attrs { attr("device_class", "opening") })
                } else {
                    val occupied = live.trait<OccupancySensing>()?.occupancy?.occupied
                    entity(domain, suffix, if (occupied == null) "unknown" else if (occupied) "on" else "off", attrs { attr("device_class", "occupancy") })
                }
            }
        }
    }

    private fun haMode(mode: ThermostatTrait.SystemModeEnum?): String = when (mode) {
        null -> "unknown"
        ThermostatTrait.SystemModeEnum.Off -> "off"
        ThermostatTrait.SystemModeEnum.Heat, ThermostatTrait.SystemModeEnum.EmergencyHeat -> "heat"
        ThermostatTrait.SystemModeEnum.Cool, ThermostatTrait.SystemModeEnum.Precooling -> "cool"
        ThermostatTrait.SystemModeEnum.Auto -> "heat_cool"
        ThermostatTrait.SystemModeEnum.FanOnly -> "fan_only"
        ThermostatTrait.SystemModeEnum.Dry -> "dry"
        else -> "auto"
    }

    private fun matterMode(ha: String): ThermostatTrait.SystemModeEnum = when (ha) {
        "off" -> ThermostatTrait.SystemModeEnum.Off
        "heat" -> ThermostatTrait.SystemModeEnum.Heat
        "cool" -> ThermostatTrait.SystemModeEnum.Cool
        "fan_only" -> ThermostatTrait.SystemModeEnum.FanOnly
        "dry" -> ThermostatTrait.SystemModeEnum.Dry
        else -> ThermostatTrait.SystemModeEnum.Auto
    }

    // ---- Service calls to trait commands ---------------------------------------------

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    /** Runs [call] on [live]'s traits. False when the call has no equivalent. Throws the SDK's failure. */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod", "detekt.ReturnCount")
    suspend fun command(call: HaServiceCall, live: Live, current: HaEntity?): Boolean {
        val data = call.data
        val onOff = live.trait<OnOff>()
        val level = live.trait<LevelControl>()
        val color = live.trait<ColorControl>()
        val isOn = current?.state == "on" || current?.state == "playing" || current?.state == "paused"
        when (call.domain) {
            "light", "switch", "fan", "media_player", "homeassistant" -> when (call.service) {
                "turn_on" -> {
                    var done = false
                    val pct = data.num("brightness_pct") ?: data.num("brightness")?.let { it * PERCENT_D / BYTE_MAX }
                    if (pct != null && level != null) {
                        level.moveToLevelWithOnOff(
                            level = (pct * MATTER_LEVEL_MAX / PERCENT_D).roundToInt().coerceIn(1, MATTER_LEVEL_MAX).toUByte(),
                            transitionTime = null,
                            optionsMask = LevelControlTrait.OptionsBitmap(),
                            optionsOverride = LevelControlTrait.OptionsBitmap(),
                        )
                        done = true
                    } else if (onOff != null) {
                        onOff.on()
                        done = true
                    }
                    data.arr("hs_color")?.let { hs ->
                        val h = hs.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@let
                        val s = hs.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@let
                        if (color != null) {
                            color.moveToHueAndSaturation(
                                hue = (h * MATTER_BYTE_MAX / DEGREES).roundToInt().coerceIn(0, MATTER_BYTE_MAX).toUByte(),
                                saturation = (s * MATTER_BYTE_MAX / PERCENT_D).roundToInt().coerceIn(0, MATTER_BYTE_MAX).toUByte(),
                                transitionTime = 0u,
                                optionsMask = ColorControlTrait.OptionsBitmap(),
                                optionsOverride = ColorControlTrait.OptionsBitmap(),
                            )
                            done = true
                        }
                    }
                    data.num("color_temp_kelvin")?.let { k ->
                        if (color != null && k > 0) {
                            color.moveToColorTemperature(
                                colorTemperatureMireds = (MIREK_KELVIN / k).roundToInt().coerceIn(1, MIREK_MAX).toUShort(),
                                transitionTime = 0u,
                                optionsMask = ColorControlTrait.OptionsBitmap(),
                                optionsOverride = ColorControlTrait.OptionsBitmap(),
                            )
                            done = true
                        }
                    }
                    data.num("percentage")?.let { p ->
                        live.trait<FanControl>()?.let { f ->
                            setFanSpeed(f, p.roundToInt().coerceIn(0, PERCENT))
                            done = true
                        }
                    }
                    // A fan with no OnOff of its own comes on through its mode.
                    if (!done && call.domain == "fan") {
                        live.trait<FanControl>()?.let { f ->
                            f.update { setFanMode(FanControlTrait.FanModeEnum.On) }
                            done = true
                        }
                    }
                    return done
                }
                "turn_off" -> {
                    if (onOff != null) {
                        onOff.off()
                        return true
                    }
                    live.trait<FanControl>()?.let { f ->
                        f.update { setFanMode(FanControlTrait.FanModeEnum.Off) }
                        return true
                    }
                    return false
                }
                "toggle" -> {
                    if (onOff != null) {
                        if (isOn) onOff.off() else onOff.on()
                        return true
                    }
                    // A fan with no OnOff of its own: its mode is the switch.
                    val f = live.trait<FanControl>() ?: return false
                    f.update { setFanMode(if (isOn) FanControlTrait.FanModeEnum.Off else FanControlTrait.FanModeEnum.On) }
                    return true
                }
                "set_percentage" -> {
                    val f = live.trait<FanControl>() ?: return false
                    val p = data.num("percentage")?.roundToInt()?.coerceIn(0, PERCENT) ?: return false
                    when {
                        p == 0 && onOff != null -> onOff.off()
                        p == 0 -> f.update { setFanMode(FanControlTrait.FanModeEnum.Off) }
                        else -> {
                            if (onOff != null && !isOn) onOff.on()
                            setFanSpeed(f, p)
                        }
                    }
                    return true
                }
                "set_preset_mode" -> {
                    val x = live.trait<ExtendedFanControl>() ?: return false
                    val name = data.str("preset_mode") ?: return false
                    if (onOff != null && !isOn) onOff.on()
                    x.update { setCustomFanMode(name) }
                    return true
                }
                "media_play" -> return live.trait<MediaPlayback>()?.play() != null
                "media_pause" -> return live.trait<MediaPlayback>()?.pause() != null
                "media_play_pause" -> {
                    val m = live.trait<MediaPlayback>() ?: return false
                    if (current?.state == "playing") m.pause() else m.play()
                    return true
                }
                "media_stop" -> return live.trait<MediaPlayback>()?.stop() != null
                "media_next_track" -> return live.trait<MediaPlayback>()?.next() != null
                "media_previous_track" -> return live.trait<MediaPlayback>()?.previous() != null
                "volume_set" -> {
                    val v = data.num("volume_level") ?: return false
                    val l = level ?: return false
                    l.moveToLevelWithOnOff(
                        level = (v * MATTER_LEVEL_MAX).roundToInt().coerceIn(0, MATTER_LEVEL_MAX).toUByte(),
                        transitionTime = null,
                        optionsMask = LevelControlTrait.OptionsBitmap(),
                        optionsOverride = LevelControlTrait.OptionsBitmap(),
                    )
                    return true
                }
                else -> return false
            }
            "cover" -> {
                val w = live.trait<WindowCovering>() ?: return false
                when (call.service) {
                    "open_cover" -> w.upOrOpen()
                    "close_cover" -> w.downOrClose()
                    "stop_cover" -> w.stopMotion()
                    "set_cover_position" -> {
                        val pos = data.num("position") ?: return false
                        w.goToLiftPercentage(((PERCENT_D - pos.coerceIn(0.0, PERCENT_D)) * HUNDREDTHS).roundToInt().coerceIn(0, PERCENT * HUNDREDTHS).toUShort())
                    }
                    else -> return false
                }
                return true
            }
            "lock" -> {
                val l = live.trait<DoorLock>() ?: return false
                val code = data.str("code")?.takeIf { it.isNotEmpty() }?.toByteArray()
                when (call.service) {
                    "lock" -> if (code != null) l.lockDoor { pinCode = code } else l.lockDoor()
                    "unlock", "open" -> if (code != null) l.unlockDoor { pinCode = code } else l.unlockDoor()
                    else -> return false
                }
                return true
            }
            "climate" -> {
                val t = live.trait<Thermostat>() ?: return false
                when (call.service) {
                    "set_temperature" -> {
                        val mode = current?.state
                        data.num("temperature")?.let { v ->
                            val centi = (v * CENTI).roundToInt().toShort()
                            if (mode == "cool") t.update { setOccupiedCoolingSetpoint(centi) } else t.update { setOccupiedHeatingSetpoint(centi) }
                        }
                        data.num("target_temp_low")?.let { v -> t.update { setOccupiedHeatingSetpoint((v * CENTI).roundToInt().toShort()) } }
                        data.num("target_temp_high")?.let { v -> t.update { setOccupiedCoolingSetpoint((v * CENTI).roundToInt().toShort()) } }
                    }
                    "set_hvac_mode" -> {
                        val mode = data.str("hvac_mode") ?: return false
                        when {
                            mode == "off" && onOff != null -> onOff.off()
                            else -> {
                                if (onOff != null && current?.state == "off") onOff.on()
                                t.update { setSystemMode(matterMode(mode)) }
                            }
                        }
                    }
                    "turn_off" -> if (onOff != null) onOff.off() else t.update { setSystemMode(ThermostatTrait.SystemModeEnum.Off) }
                    "turn_on" -> if (onOff != null) onOff.on() else t.update { setSystemMode(ThermostatTrait.SystemModeEnum.Auto) }
                    else -> return false
                }
                return true
            }
            else -> return false
        }
    }

    /**
     * A fan with speeds takes the nearest one, so five speeds land on 20 %
     * steps; one without takes the percentage. Some bridges list speeds and
     * then refuse the speed attribute, so that falls back to the percentage.
     */
    private suspend fun setFanSpeed(f: FanControl, pct: Int) {
        val max = f.speedMax?.toInt()?.takeIf { it > 0 }
        if (max != null) {
            val speed = ((pct * max + PERCENT / 2) / PERCENT).coerceIn(1, max)
            try {
                f.update { setSpeedSetting(speed.toUByte()) }
                return
            } catch (_: com.google.home.HomeException) {
                // Fall through to the percentage.
            }
        }
        f.update { setPercentSetting(pct.toUByte()) }
    }

    private const val CENTI = 100.0
    private const val HUNDREDTHS = 100
    private const val PERCENT = 100
    private const val PERCENT_D = 100.0
    private const val BYTE_MAX = 255
    private const val BYTE_MAX_D = 255.0
    private const val MATTER_LEVEL_MAX = 254
    private const val MATTER_BYTE_MAX = 254
    private const val DEGREES = 360.0
    private const val MIREK_KELVIN = 1_000_000.0
    private const val MIREK_MAX = 65_279
    private const val TENTH = 10.0
    private const val HALF = 0.5
    private const val MIN_TEMP_C = 7.0
    private const val MAX_TEMP_C = 35.0
    private const val FAN_LOW = 33
    private const val FAN_MEDIUM = 66
    private const val LUX_SCALE = 10_000.0

    /** For a reader of the mapping: an entity's live traits are reachable from the backend only. */
    internal fun JsonElement.asArray(): JsonArray = jsonArray
}
