package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** One service call, the same whether it goes over the socket or REST. */
data class HaServiceCall(
    val domain: String,
    val service: String,
    val entityId: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    /** The REST body: `service_data` with `entity_id` folded in. */
    fun restBody(): JsonObject = buildJsonObject {
        entityId?.let { put("entity_id", it) }
        data.forEach { (k, v) -> put(k, v) }
    }
}

/** What kind of thing an entity is, which decides its tile and its controls. */
enum class HaKind {
    LIGHT, SWITCH, FAN, COVER, CLIMATE, MEDIA, LOCK, ALARM, VACUUM, HUMIDIFIER, WATER_HEATER, SIREN, VALVE,
    NUMBER, SELECT, TEXT, BUTTON, SCENE, SCRIPT, AUTOMATION, CAMERA, SENSOR, BINARY_SENSOR, WEATHER, PERSON, OTHER,
}

/** What one tap on a tile does. */
enum class HaPrimary { TOGGLE, RUN, PRESS, EXPAND, INSERT, NONE }

/** Feature bits, as Home Assistant's `supported_features` carries them. */
@Suppress("detekt.MagicNumber")
object HaFeatures {
    object Cover {
        const val OPEN = 1
        const val CLOSE = 2
        const val SET_POSITION = 4
        const val STOP = 8
        const val OPEN_TILT = 16
        const val CLOSE_TILT = 32
        const val STOP_TILT = 64
        const val SET_TILT_POSITION = 128
    }

    object Climate {
        const val TARGET_TEMPERATURE = 1
        const val TARGET_TEMPERATURE_RANGE = 2
        const val TARGET_HUMIDITY = 4
        const val FAN_MODE = 8
        const val PRESET_MODE = 16
        const val SWING_MODE = 32
        const val TURN_OFF = 128
        const val TURN_ON = 256
    }

    object Fan {
        const val SET_SPEED = 1
        const val OSCILLATE = 2
        const val DIRECTION = 4
        const val PRESET_MODE = 8
    }

    object Media {
        const val PAUSE = 1
        const val SEEK = 2
        const val VOLUME_SET = 4
        const val VOLUME_MUTE = 8
        const val PREVIOUS_TRACK = 16
        const val NEXT_TRACK = 32
        const val TURN_ON = 128
        const val TURN_OFF = 256
        const val VOLUME_STEP = 1024
        const val SELECT_SOURCE = 2048
        const val STOP = 4096
        const val PLAY = 16384
    }

    object Lock {
        const val OPEN = 1
    }

    object Alarm {
        const val ARM_HOME = 1
        const val ARM_AWAY = 2
        const val ARM_NIGHT = 4
        const val TRIGGER = 8
        const val ARM_CUSTOM_BYPASS = 16
        const val ARM_VACATION = 32
    }

    object Vacuum {
        const val TURN_ON = 1
        const val TURN_OFF = 2
        const val PAUSE = 4
        const val STOP = 8
        const val RETURN_HOME = 16
        const val FAN_SPEED = 32
        const val BATTERY = 64
        const val LOCATE = 512
        const val START = 8192
    }

    object Humidifier {
        const val MODES = 1
    }

    object WaterHeater {
        const val TARGET_TEMPERATURE = 1
        const val OPERATION_MODE = 2
        const val ON_OFF = 8
    }

    object Valve {
        const val OPEN = 1
        const val CLOSE = 2
        const val SET_POSITION = 4
        const val STOP = 8
    }
}

/** The domain table: what each entity is, what a tap does, which ones ask before acting. */
object HaDomains {

    fun kindOf(domain: String): HaKind = when (domain) {
        "light" -> HaKind.LIGHT
        "switch", "input_boolean" -> HaKind.SWITCH
        "fan" -> HaKind.FAN
        "cover" -> HaKind.COVER
        "climate" -> HaKind.CLIMATE
        "media_player" -> HaKind.MEDIA
        "lock" -> HaKind.LOCK
        "alarm_control_panel" -> HaKind.ALARM
        "vacuum" -> HaKind.VACUUM
        "humidifier" -> HaKind.HUMIDIFIER
        "water_heater" -> HaKind.WATER_HEATER
        "siren" -> HaKind.SIREN
        "valve" -> HaKind.VALVE
        "number", "input_number" -> HaKind.NUMBER
        "select", "input_select" -> HaKind.SELECT
        "text", "input_text" -> HaKind.TEXT
        "button", "input_button" -> HaKind.BUTTON
        "scene" -> HaKind.SCENE
        "script" -> HaKind.SCRIPT
        "automation" -> HaKind.AUTOMATION
        "camera" -> HaKind.CAMERA
        "sensor" -> HaKind.SENSOR
        "binary_sensor" -> HaKind.BINARY_SENSOR
        "weather" -> HaKind.WEATHER
        "person", "device_tracker" -> HaKind.PERSON
        else -> HaKind.OTHER
    }

    fun kindOf(entity: HaEntity): HaKind = kindOf(entity.domain)

    /** The domains whose entities a `toggle` service flips. */
    fun isToggleable(entity: HaEntity): Boolean = when (kindOf(entity)) {
        HaKind.LIGHT, HaKind.SWITCH, HaKind.FAN, HaKind.AUTOMATION, HaKind.HUMIDIFIER, HaKind.SIREN -> true
        HaKind.MEDIA -> entity.supports(HaFeatures.Media.TURN_ON) && entity.supports(HaFeatures.Media.TURN_OFF)
        HaKind.CLIMATE -> entity.supports(HaFeatures.Climate.TURN_ON) && entity.supports(HaFeatures.Climate.TURN_OFF)
        HaKind.WATER_HEATER -> entity.supports(HaFeatures.WaterHeater.ON_OFF)
        else -> false
    }

    /** Whether the tile shows a switch: toggleable, and the tap flips it. */
    fun hasSwitch(entity: HaEntity): Boolean = when (kindOf(entity)) {
        HaKind.LIGHT, HaKind.SWITCH, HaKind.FAN, HaKind.AUTOMATION, HaKind.HUMIDIFIER, HaKind.SIREN -> true
        else -> false
    }

    /** What one tap on the tile does. */
    fun primary(entity: HaEntity): HaPrimary = when (kindOf(entity)) {
        HaKind.LIGHT, HaKind.SWITCH, HaKind.FAN, HaKind.AUTOMATION, HaKind.HUMIDIFIER, HaKind.SIREN -> HaPrimary.TOGGLE
        HaKind.SCENE, HaKind.SCRIPT -> HaPrimary.RUN
        HaKind.BUTTON -> HaPrimary.PRESS
        HaKind.COVER, HaKind.CLIMATE, HaKind.MEDIA, HaKind.LOCK, HaKind.ALARM, HaKind.VACUUM,
        HaKind.WATER_HEATER, HaKind.VALVE, HaKind.NUMBER, HaKind.SELECT, HaKind.TEXT, HaKind.CAMERA,
        -> HaPrimary.EXPAND
        HaKind.SENSOR, HaKind.BINARY_SENSOR, HaKind.WEATHER, HaKind.PERSON -> HaPrimary.INSERT
        HaKind.OTHER -> if (entity.usable && entity.state in setOf("on", "off")) HaPrimary.TOGGLE else HaPrimary.INSERT
    }

    /** Whether a hold opens a control card with more than the tile offers. */
    fun canExpand(entity: HaEntity): Boolean = when (kindOf(entity)) {
        HaKind.LIGHT -> entity.supportsBrightness || entity.supportsColorTemp || entity.supportsColor || entity.effectList.isNotEmpty()
        HaKind.FAN -> entity.supports(HaFeatures.Fan.SET_SPEED) || entity.supports(HaFeatures.Fan.OSCILLATE) || entity.presetModes.isNotEmpty()
        HaKind.SWITCH, HaKind.AUTOMATION, HaKind.SCENE, HaKind.SCRIPT, HaKind.BUTTON, HaKind.SIREN -> false
        HaKind.SENSOR, HaKind.BINARY_SENSOR, HaKind.WEATHER, HaKind.PERSON, HaKind.OTHER -> true
        else -> true
    }

    /** Actions worth a hold-to-confirm: a wrong tap opens the house or wakes the street. */
    fun isRisky(entity: HaEntity): Boolean = when (kindOf(entity)) {
        HaKind.LOCK, HaKind.ALARM, HaKind.SIREN, HaKind.VALVE -> true
        HaKind.COVER -> entity.deviceClass in RISKY_COVERS
        else -> false
    }

    /** Read-only kinds: the tile shows a value and the tap inserts it. */
    fun isReadOnly(entity: HaEntity): Boolean = when (kindOf(entity)) {
        HaKind.SENSOR, HaKind.BINARY_SENSOR, HaKind.WEATHER, HaKind.PERSON -> true
        HaKind.OTHER -> entity.state !in setOf("on", "off")
        else -> false
    }

    /** The domains the panel never lists: the sun, update entities, zones and the like. */
    fun isListed(domain: String): Boolean = domain !in UNLISTED

    private val RISKY_COVERS = setOf("garage", "gate", "door")
    private val UNLISTED = setOf(
        "sun", "zone", "update", "persistent_notification", "tts", "stt", "conversation", "event", "todo",
        "calendar", "image", "tag", "schedule", "input_datetime", "datetime", "date", "time", "counter", "timer",
        "remote", "notify", "assist_satellite", "wake_word", "ai_task", "lawn_mower", "group", "backup",
    )
}

/** Every service call the panel makes, built here so the controller and the tests agree on the bytes. */
@Suppress("detekt.TooManyFunctions")
object HaActions {

    private fun call(domain: String, service: String, id: String, data: JsonObject = JsonObject(emptyMap())) =
        HaServiceCall(domain, service, id, data)

    private fun domainOf(id: String) = id.substringBefore('.')

    private fun data(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

    // On, off, toggle: the entity's own domain, or homeassistant.* for a kind without its own service.
    fun toggle(id: String): HaServiceCall = call(toggleDomain(id), "toggle", id)
    fun turnOn(id: String): HaServiceCall = call(toggleDomain(id), "turn_on", id)
    fun turnOff(id: String): HaServiceCall = call(toggleDomain(id), "turn_off", id)
    private fun toggleDomain(id: String) = when (val d = domainOf(id)) {
        "light", "switch", "fan", "input_boolean", "automation", "humidifier", "siren", "media_player", "climate", "water_heater" -> d
        else -> "homeassistant"
    }

    // Light
    fun setBrightnessPct(id: String, pct: Int): HaServiceCall = call("light", "turn_on", id, data { put("brightness_pct", pct.coerceIn(0, PERCENT)) })
    fun setColorTempKelvin(id: String, kelvin: Int): HaServiceCall = call("light", "turn_on", id, data { put("color_temp_kelvin", kelvin) })
    fun setHsColor(id: String, hue: Double, saturation: Double): HaServiceCall =
        call("light", "turn_on", id, data { putJsonArray("hs_color") { add(hue); add(saturation) } })
    fun setRgbColor(id: String, r: Int, g: Int, b: Int): HaServiceCall =
        call("light", "turn_on", id, data { putJsonArray("rgb_color") { add(r); add(g); add(b) } })
    fun setEffect(id: String, effect: String): HaServiceCall = call("light", "turn_on", id, data { put("effect", effect) })

    // Cover
    fun openCover(id: String): HaServiceCall = call("cover", "open_cover", id)
    fun closeCover(id: String): HaServiceCall = call("cover", "close_cover", id)
    fun stopCover(id: String): HaServiceCall = call("cover", "stop_cover", id)
    fun setCoverPosition(id: String, pct: Int): HaServiceCall = call("cover", "set_cover_position", id, data { put("position", pct.coerceIn(0, PERCENT)) })
    fun setCoverTilt(id: String, pct: Int): HaServiceCall = call("cover", "set_cover_tilt_position", id, data { put("tilt_position", pct.coerceIn(0, PERCENT)) })

    // Fan
    fun setFanPercentage(id: String, pct: Int): HaServiceCall =
        if (pct <= 0) call("fan", "turn_off", id) else call("fan", "set_percentage", id, data { put("percentage", pct.coerceIn(1, PERCENT)) })
    fun oscillate(id: String, on: Boolean): HaServiceCall = call("fan", "oscillate", id, data { put("oscillating", on) })
    fun setFanDirection(id: String, direction: String): HaServiceCall = call("fan", "set_direction", id, data { put("direction", direction) })
    fun setFanPreset(id: String, preset: String): HaServiceCall = call("fan", "set_preset_mode", id, data { put("preset_mode", preset) })

    // Climate
    fun setTemperature(id: String, temperature: Double): HaServiceCall = call("climate", "set_temperature", id, data { put("temperature", temperature) })
    fun setTemperatureRange(id: String, low: Double, high: Double): HaServiceCall =
        call("climate", "set_temperature", id, data { put("target_temp_low", low); put("target_temp_high", high) })
    fun setHvacMode(id: String, mode: String): HaServiceCall = call("climate", "set_hvac_mode", id, data { put("hvac_mode", mode) })
    fun setPresetMode(id: String, mode: String): HaServiceCall = call("climate", "set_preset_mode", id, data { put("preset_mode", mode) })
    fun setFanMode(id: String, mode: String): HaServiceCall = call("climate", "set_fan_mode", id, data { put("fan_mode", mode) })

    // Media player
    fun mediaPlayPause(id: String): HaServiceCall = call("media_player", "media_play_pause", id)
    fun mediaPlay(id: String): HaServiceCall = call("media_player", "media_play", id)
    fun mediaPause(id: String): HaServiceCall = call("media_player", "media_pause", id)
    fun mediaStop(id: String): HaServiceCall = call("media_player", "media_stop", id)
    fun mediaNext(id: String): HaServiceCall = call("media_player", "media_next_track", id)
    fun mediaPrevious(id: String): HaServiceCall = call("media_player", "media_previous_track", id)
    fun setVolume(id: String, level: Double): HaServiceCall = call("media_player", "volume_set", id, data { put("volume_level", level.coerceIn(0.0, 1.0)) })
    fun mute(id: String, muted: Boolean): HaServiceCall = call("media_player", "volume_mute", id, data { put("is_volume_muted", muted) })
    fun selectSource(id: String, source: String): HaServiceCall = call("media_player", "select_source", id, data { put("source", source) })

    // Lock
    fun lock(id: String, code: String? = null): HaServiceCall = call("lock", "lock", id, codeData(code))
    fun unlock(id: String, code: String? = null): HaServiceCall = call("lock", "unlock", id, codeData(code))
    fun openLock(id: String, code: String? = null): HaServiceCall = call("lock", "open", id, codeData(code))

    // Alarm
    enum class ArmMode(val service: String, val state: String) {
        HOME("alarm_arm_home", "armed_home"),
        AWAY("alarm_arm_away", "armed_away"),
        NIGHT("alarm_arm_night", "armed_night"),
        VACATION("alarm_arm_vacation", "armed_vacation"),
        CUSTOM("alarm_arm_custom_bypass", "armed_custom_bypass"),
    }

    fun arm(id: String, mode: ArmMode, code: String? = null): HaServiceCall = call("alarm_control_panel", mode.service, id, codeData(code))
    fun disarm(id: String, code: String? = null): HaServiceCall = call("alarm_control_panel", "alarm_disarm", id, codeData(code))

    /** The arm modes an alarm panel offers, from its feature bits. */
    fun armModes(entity: HaEntity): List<ArmMode> = buildList {
        if (entity.supports(HaFeatures.Alarm.ARM_HOME)) add(ArmMode.HOME)
        if (entity.supports(HaFeatures.Alarm.ARM_AWAY)) add(ArmMode.AWAY)
        if (entity.supports(HaFeatures.Alarm.ARM_NIGHT)) add(ArmMode.NIGHT)
        if (entity.supports(HaFeatures.Alarm.ARM_VACATION)) add(ArmMode.VACATION)
        if (entity.supports(HaFeatures.Alarm.ARM_CUSTOM_BYPASS)) add(ArmMode.CUSTOM)
    }

    private fun codeData(code: String?): JsonObject = if (code.isNullOrEmpty()) JsonObject(emptyMap()) else data { put("code", code) }

    // Vacuum
    enum class VacuumCommand(val service: String) {
        START("start"), PAUSE("pause"), STOP("stop"), RETURN("return_to_base"), LOCATE("locate"),
    }

    fun vacuum(id: String, command: VacuumCommand): HaServiceCall = call("vacuum", command.service, id)
    fun setVacuumFanSpeed(id: String, speed: String): HaServiceCall = call("vacuum", "set_fan_speed", id, data { put("fan_speed", speed) })

    // Humidifier
    fun setHumidity(id: String, pct: Int): HaServiceCall = call("humidifier", "set_humidity", id, data { put("humidity", pct.coerceIn(0, PERCENT)) })
    fun setHumidifierMode(id: String, mode: String): HaServiceCall = call("humidifier", "set_mode", id, data { put("mode", mode) })

    // Water heater
    fun setWaterTemperature(id: String, temperature: Double): HaServiceCall = call("water_heater", "set_temperature", id, data { put("temperature", temperature) })
    fun setWaterMode(id: String, mode: String): HaServiceCall = call("water_heater", "set_operation_mode", id, data { put("operation_mode", mode) })

    // Valve
    fun openValve(id: String): HaServiceCall = call("valve", "open_valve", id)
    fun closeValve(id: String): HaServiceCall = call("valve", "close_valve", id)
    fun stopValve(id: String): HaServiceCall = call("valve", "stop_valve", id)
    fun setValvePosition(id: String, pct: Int): HaServiceCall = call("valve", "set_valve_position", id, data { put("position", pct.coerceIn(0, PERCENT)) })

    // Number, select, text: the service lives in the entity's own domain (number vs input_number).
    fun setValue(id: String, value: Double): HaServiceCall = call(domainOf(id), "set_value", id, data { put("value", value) })
    fun selectOption(id: String, option: String): HaServiceCall = call(domainOf(id), "select_option", id, data { put("option", option) })
    fun setText(id: String, text: String): HaServiceCall = call(domainOf(id), "set_value", id, data { put("value", text) })

    // Buttons, scenes, scripts, automations
    fun press(id: String): HaServiceCall = call(domainOf(id), "press", id)
    fun runScene(id: String): HaServiceCall = call("scene", "turn_on", id)
    fun runScript(id: String): HaServiceCall = call("script", "turn_on", id)
    fun triggerAutomation(id: String): HaServiceCall = call("automation", "trigger", id)

    /** The one-tap action for [entity], or null when a tap does not act (it inserts or expands). */
    fun primaryCall(entity: HaEntity): HaServiceCall? = when (HaDomains.primary(entity)) {
        HaPrimary.TOGGLE -> toggle(entity.entityId)
        HaPrimary.RUN -> if (entity.domain == "scene") runScene(entity.entityId) else runScript(entity.entityId)
        HaPrimary.PRESS -> press(entity.entityId)
        HaPrimary.EXPAND, HaPrimary.INSERT, HaPrimary.NONE -> null
    }

    /** What [call] will most likely make the state, so the tile can flip before the server answers. */
    fun expectedState(entity: HaEntity, call: HaServiceCall): String? = when (call.service) {
        "toggle" -> if (entity.isOn) "off" else "on"
        "turn_on" -> if (entity.domain == "scene" || entity.domain == "script") null else "on"
        "turn_off" -> "off"
        "lock" -> "locked"
        "unlock" -> "unlocked"
        "open_cover", "open_valve" -> "open"
        "close_cover", "close_valve" -> "closed"
        "alarm_disarm" -> "disarmed"
        "media_play" -> "playing"
        "media_pause" -> "paused"
        "media_play_pause" -> if (entity.state == "playing") "paused" else "playing"
        else -> ArmMode.entries.firstOrNull { it.service == call.service }?.state
    }

    private const val PERCENT = 100
}
