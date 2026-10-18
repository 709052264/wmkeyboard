package com.wasimaster.wmkeyboard.core.homeassistant

import java.text.NumberFormat
import java.util.Locale

/** The words a state line needs, so the keyboard can translate them and the tests can read English. */
enum class HaWord {
    ON, OFF, OPEN, CLOSED, OPENING, CLOSING, STOPPED, LOCKED, UNLOCKED, LOCKING, UNLOCKING, JAMMED,
    PLAYING, PAUSED, IDLE, STANDBY, BUFFERING, HEATING, COOLING, DRYING, FAN, HEAT, COOL, HEAT_COOL, AUTO, DRY, FAN_ONLY,
    ARMED_HOME, ARMED_AWAY, ARMED_NIGHT, ARMED_VACATION, ARMED_CUSTOM, DISARMED, ARMING, DISARMING, PENDING, TRIGGERED,
    CLEANING, DOCKED, RETURNING, ERROR, HOME, AWAY, UNAVAILABLE, UNKNOWN, DETECTED, CLEAR, SCENE, SCRIPT, RUNNING,
    ECO, PERFORMANCE, GAS, ELECTRIC, HEAT_PUMP, HIGH_DEMAND, OFF_MODE, ACTIVE, BUTTON, MUTED,
    CLEAR_NIGHT, CLOUDY, FOG, HAIL, LIGHTNING, LIGHTNING_RAINY, PARTLY_CLOUDY, POURING, RAINY, SNOWY, SNOWY_RAINY, SUNNY, WINDY, EXCEPTIONAL,
}

fun interface HaWords {
    fun word(w: HaWord): String

    companion object {
        /** English, for the tests and for a host without strings of its own. */
        val English: HaWords = HaWords { w ->
            when (w) {
                HaWord.HEAT_COOL -> "Heat/cool"
                HaWord.FAN_ONLY -> "Fan only"
                HaWord.ARMED_HOME -> "Armed home"
                HaWord.ARMED_AWAY -> "Armed away"
                HaWord.ARMED_NIGHT -> "Armed night"
                HaWord.ARMED_VACATION -> "Armed vacation"
                HaWord.ARMED_CUSTOM -> "Armed custom"
                HaWord.HEAT_PUMP -> "Heat pump"
                HaWord.HIGH_DEMAND -> "High demand"
                HaWord.OFF_MODE -> "Off"
                HaWord.CLEAR_NIGHT -> "Clear night"
                HaWord.LIGHTNING_RAINY -> "Thunderstorms"
                HaWord.PARTLY_CLOUDY -> "Partly cloudy"
                HaWord.SNOWY_RAINY -> "Sleet"
                else -> w.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }
    }
}

/** What a sensor tile types into the field. */
enum class HaInsertFormat(val id: String) {
    VALUE("value"),
    VALUE_UNIT("value_unit"),
    NAME_VALUE("name_value"),
    ;

    companion object {
        fun of(id: String?): HaInsertFormat = entries.firstOrNull { it.id == id } ?: VALUE_UNIT
    }
}

/**
 * The human line for a state: `On · 60 %`, `21.5 °C`, `Open`, `Playing · Title`.
 * Pure strings in, pure strings out; [words] supplies the translated vocabulary.
 */
class HaStateFormat(
    private val words: HaWords = HaWords.English,
    private val locale: Locale = Locale.getDefault(),
) {
    private fun w(word: HaWord) = words.word(word)

    private val numberFormat: NumberFormat = NumberFormat.getInstance(locale).apply {
        maximumFractionDigits = MAX_FRACTION
        isGroupingUsed = true
    }

    /** A number with sensible precision: `21.5`, `1,234`, `0.07`. */
    fun number(value: Double, step: Double? = null): String {
        val fmt = numberFormat.clone() as NumberFormat
        fmt.maximumFractionDigits = when {
            step != null && step < 1.0 -> decimalsOf(step)
            value == value.toLong().toDouble() -> 0
            kotlin.math.abs(value) >= BIG -> 0
            kotlin.math.abs(value) >= MEDIUM -> 1
            else -> MAX_FRACTION
        }
        return fmt.format(value)
    }

    /** `21.5 °C`, `42 %`, `1,234 kWh`: a space before the unit except for degrees and percent in some locales. */
    fun withUnit(value: String, unit: String?): String = when {
        unit.isNullOrBlank() -> value
        unit.startsWith("°") -> "$value $unit"
        unit == "%" -> "$value %"
        else -> "$value $unit"
    }

    /** The main state line of a tile. */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun state(e: HaEntity): String {
        if (e.isUnavailable) return w(HaWord.UNAVAILABLE)
        if (e.isUnknown && HaDomains.kindOf(e) != HaKind.SENSOR) return w(HaWord.UNKNOWN)
        return when (HaDomains.kindOf(e)) {
            HaKind.LIGHT -> if (e.isOn) e.brightnessPct?.let { "${w(HaWord.ON)} · ${withUnit(it.toString(), "%")}" } ?: w(HaWord.ON) else w(HaWord.OFF)
            HaKind.SWITCH, HaKind.AUTOMATION, HaKind.SIREN -> onOff(e.state)
            HaKind.FAN -> if (e.isOn) e.percentage?.let { "${w(HaWord.ON)} · ${withUnit(it.toString(), "%")}" } ?: w(HaWord.ON) else w(HaWord.OFF)
            HaKind.COVER, HaKind.VALVE -> coverState(e)
            HaKind.CLIMATE -> climateState(e)
            HaKind.MEDIA -> mediaState(e)
            HaKind.LOCK -> lockState(e.state)
            HaKind.ALARM -> alarmState(e.state)
            HaKind.VACUUM -> vacuumState(e)
            HaKind.HUMIDIFIER -> if (e.isOn) e.targetHumidity?.let { "${w(HaWord.ON)} · ${withUnit(number(it), "%")}" } ?: w(HaWord.ON) else w(HaWord.OFF)
            HaKind.WATER_HEATER -> waterHeaterState(e)
            HaKind.NUMBER -> e.numeric?.let { withUnit(number(it, e.step), e.unit) } ?: e.state
            HaKind.SELECT, HaKind.TEXT -> e.state
            HaKind.BUTTON -> w(HaWord.BUTTON)
            HaKind.SCENE -> w(HaWord.SCENE)
            HaKind.SCRIPT -> if (e.isOn) w(HaWord.RUNNING) else w(HaWord.SCRIPT)
            HaKind.CAMERA -> cameraState(e.state)
            HaKind.SENSOR -> sensorState(e)
            HaKind.BINARY_SENSOR -> binaryState(e)
            HaKind.WEATHER -> weatherState(e)
            HaKind.PERSON -> personState(e.state)
            HaKind.OTHER -> if (e.state == "on" || e.state == "off") onOff(e.state) else e.numeric?.let { withUnit(number(it), e.unit) } ?: e.state
        }
    }

    /** The value a sensor tile shows large: the number, or the word. */
    fun value(e: HaEntity): String = when (HaDomains.kindOf(e)) {
        HaKind.SENSOR -> if (e.isUnavailable) w(HaWord.UNAVAILABLE) else e.numeric?.let { number(it) } ?: e.state.replaceFirstChar { c -> c.uppercase() }
        HaKind.WEATHER -> e.weatherTemperature?.let { number(it) } ?: state(e)
        else -> state(e)
    }

    /** What a tile types when tapped. */
    fun insert(e: HaEntity, format: HaInsertFormat, name: String = e.friendlyName): String {
        val valueText = when (HaDomains.kindOf(e)) {
            HaKind.SENSOR -> e.numeric?.let { number(it) } ?: e.state
            HaKind.WEATHER -> e.weatherTemperature?.let { number(it) } ?: e.state
            else -> null
        }
        val unit = if (HaDomains.kindOf(e) == HaKind.WEATHER) e.temperatureUnit ?: "°" else e.unit
        return when (format) {
            HaInsertFormat.VALUE -> valueText ?: state(e)
            HaInsertFormat.VALUE_UNIT -> valueText?.let { withUnit(it, unit) } ?: state(e)
            HaInsertFormat.NAME_VALUE -> "$name: " + (valueText?.let { withUnit(it, unit) } ?: state(e))
        }
    }

    private fun onOff(state: String) = if (state == "on") w(HaWord.ON) else w(HaWord.OFF)

    private fun coverState(e: HaEntity): String {
        val word = when (e.state) {
            "open" -> w(HaWord.OPEN)
            "closed" -> w(HaWord.CLOSED)
            "opening" -> w(HaWord.OPENING)
            "closing" -> w(HaWord.CLOSING)
            else -> e.state
        }
        val pos = e.currentPosition
        return if (pos != null && pos in 1 until PERCENT) "$word · ${withUnit(pos.toString(), "%")}" else word
    }

    private fun climateState(e: HaEntity): String {
        val mode = when (e.state) {
            "off" -> w(HaWord.OFF)
            "heat" -> w(HaWord.HEAT)
            "cool" -> w(HaWord.COOL)
            "heat_cool" -> w(HaWord.HEAT_COOL)
            "auto" -> w(HaWord.AUTO)
            "dry" -> w(HaWord.DRY)
            "fan_only" -> w(HaWord.FAN_ONLY)
            else -> e.state
        }
        val action = when (e.hvacAction) {
            "heating" -> w(HaWord.HEATING)
            "cooling" -> w(HaWord.COOLING)
            "drying" -> w(HaWord.DRYING)
            "fan" -> w(HaWord.FAN)
            else -> null
        }
        val unit = e.temperatureUnit ?: "°"
        val target = e.targetTemperature?.let { withUnit(number(it, e.targetTempStep), unit) }
            ?: e.targetTempLow?.let { lo -> e.targetTempHigh?.let { hi -> "${number(lo)}–${withUnit(number(hi), unit)}" } }
        return listOfNotNull(action ?: mode, target).joinToString(" · ")
    }

    private fun mediaState(e: HaEntity): String {
        val word = when (e.state) {
            "playing" -> w(HaWord.PLAYING)
            "paused" -> w(HaWord.PAUSED)
            "idle" -> w(HaWord.IDLE)
            "standby" -> w(HaWord.STANDBY)
            "buffering" -> w(HaWord.BUFFERING)
            "off" -> w(HaWord.OFF)
            "on" -> w(HaWord.ON)
            else -> e.state
        }
        val title = e.mediaTitle?.takeIf { it.isNotBlank() }
        return if (title != null && e.state in setOf("playing", "paused", "buffering")) "$word · $title" else word
    }

    private fun lockState(state: String) = when (state) {
        "locked" -> w(HaWord.LOCKED)
        "unlocked" -> w(HaWord.UNLOCKED)
        "locking" -> w(HaWord.LOCKING)
        "unlocking" -> w(HaWord.UNLOCKING)
        "jammed" -> w(HaWord.JAMMED)
        "open" -> w(HaWord.OPEN)
        else -> state
    }

    private fun alarmState(state: String) = when (state) {
        "disarmed" -> w(HaWord.DISARMED)
        "armed_home" -> w(HaWord.ARMED_HOME)
        "armed_away" -> w(HaWord.ARMED_AWAY)
        "armed_night" -> w(HaWord.ARMED_NIGHT)
        "armed_vacation" -> w(HaWord.ARMED_VACATION)
        "armed_custom_bypass" -> w(HaWord.ARMED_CUSTOM)
        "arming" -> w(HaWord.ARMING)
        "disarming" -> w(HaWord.DISARMING)
        "pending" -> w(HaWord.PENDING)
        "triggered" -> w(HaWord.TRIGGERED)
        else -> state
    }

    private fun vacuumState(e: HaEntity): String {
        val word = when (e.state) {
            "cleaning" -> w(HaWord.CLEANING)
            "docked" -> w(HaWord.DOCKED)
            "returning" -> w(HaWord.RETURNING)
            "paused" -> w(HaWord.PAUSED)
            "idle" -> w(HaWord.IDLE)
            "error" -> w(HaWord.ERROR)
            "on" -> w(HaWord.ON)
            "off" -> w(HaWord.OFF)
            else -> e.state
        }
        return e.batteryLevel?.let { "$word · ${withUnit(it.toString(), "%")}" } ?: word
    }

    private fun waterHeaterState(e: HaEntity): String {
        val mode = when (e.state) {
            "eco" -> w(HaWord.ECO)
            "electric" -> w(HaWord.ELECTRIC)
            "performance" -> w(HaWord.PERFORMANCE)
            "high_demand" -> w(HaWord.HIGH_DEMAND)
            "heat_pump" -> w(HaWord.HEAT_PUMP)
            "gas" -> w(HaWord.GAS)
            "off" -> w(HaWord.OFF)
            else -> e.state
        }
        val target = e.targetTemperature?.let { withUnit(number(it), e.temperatureUnit ?: "°") }
        return listOfNotNull(mode, target).joinToString(" · ")
    }

    private fun cameraState(state: String) = when (state) {
        "idle" -> w(HaWord.IDLE)
        "recording" -> w(HaWord.ACTIVE)
        "streaming" -> w(HaWord.ACTIVE)
        else -> state
    }

    private fun sensorState(e: HaEntity): String {
        val n = e.numeric ?: return e.state.replaceFirstChar { it.uppercase() }
        return withUnit(number(n), e.unit)
    }

    /** Binary sensors read by device class: a door is open or closed, motion is detected or clear. */
    private fun binaryState(e: HaEntity): String {
        val on = e.isOn
        return when (e.deviceClass) {
            "door", "window", "garage_door", "opening" -> if (on) w(HaWord.OPEN) else w(HaWord.CLOSED)
            "lock" -> if (on) w(HaWord.UNLOCKED) else w(HaWord.LOCKED)
            "presence" -> if (on) w(HaWord.HOME) else w(HaWord.AWAY)
            "motion", "occupancy", "moving", "vibration", "sound", "smoke", "gas", "moisture", "problem", "safety", "tamper", "carbon_monoxide",
            -> if (on) w(HaWord.DETECTED) else w(HaWord.CLEAR)
            "running" -> if (on) w(HaWord.RUNNING) else w(HaWord.IDLE)
            else -> onOff(e.state)
        }
    }

    private fun weatherState(e: HaEntity): String {
        val condition = when (e.state) {
            "clear-night" -> w(HaWord.CLEAR_NIGHT)
            "cloudy" -> w(HaWord.CLOUDY)
            "fog" -> w(HaWord.FOG)
            "hail" -> w(HaWord.HAIL)
            "lightning" -> w(HaWord.LIGHTNING)
            "lightning-rainy" -> w(HaWord.LIGHTNING_RAINY)
            "partlycloudy" -> w(HaWord.PARTLY_CLOUDY)
            "pouring" -> w(HaWord.POURING)
            "rainy" -> w(HaWord.RAINY)
            "snowy" -> w(HaWord.SNOWY)
            "snowy-rainy" -> w(HaWord.SNOWY_RAINY)
            "sunny" -> w(HaWord.SUNNY)
            "windy", "windy-variant" -> w(HaWord.WINDY)
            "exceptional" -> w(HaWord.EXCEPTIONAL)
            else -> e.state
        }
        val temp = e.weatherTemperature?.let { withUnit(number(it), e.temperatureUnit ?: "°") }
        return listOfNotNull(condition, temp).joinToString(" · ")
    }

    private fun personState(state: String) = when (state) {
        "home" -> w(HaWord.HOME)
        "not_home" -> w(HaWord.AWAY)
        else -> state.replaceFirstChar { it.uppercase() }
    }

    private fun decimalsOf(step: Double): Int {
        var s = step
        var d = 0
        while (s != Math.floor(s) && d < MAX_FRACTION) {
            s *= DECIMAL
            d++
        }
        return d
    }

    companion object {
        private const val MAX_FRACTION = 2
        private const val PERCENT = 100
        private const val BIG = 1000.0
        private const val MEDIUM = 100.0
        private const val DECIMAL = 10.0
    }
}
