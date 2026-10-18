package com.wasimaster.wmkeyboard.core.homeassistant

/**
 * The glyphs the keyboard ships for Home Assistant entities. A finite set, so
 * the keyboard's `when` over it is exhaustive and a new glyph is a compile
 * error until it has a drawing.
 */
enum class HaGlyph {
    LIGHTBULB, LIGHTBULB_OFF, LAMP, LED_STRIP, CEILING_LIGHT, POWER, OUTLET, FAN, BLINDS, CURTAINS, SHUTTER, GARAGE, GARAGE_OPEN, DOOR, GATE, WINDOW,
    LOCK, LOCK_OPEN, THERMOSTAT, THERMOMETER, HUMIDITY, AIR, RADIATOR, SNOWFLAKE, FIRE, SPEAKER, TV, CAMERA, VACUUM, WATER_HEATER, HUMIDIFIER,
    SIREN, VALVE, SENSOR, BOLT, BATTERY, WIFI, MOTION, SMOKE, WATER, GAS, SCENE, SCRIPT, AUTOMATION, BUTTON, TOGGLE, NUMBER, SELECT, TEXT,
    WEATHER_SUNNY, WEATHER_NIGHT, WEATHER_CLOUDY, WEATHER_RAINY, WEATHER_SNOWY, WEATHER_FOG, WEATHER_WINDY, WEATHER_LIGHTNING,
    PERSON, HOME, SHIELD, SHIELD_OFF, BELL, MUSIC, PLAY, CAR, PLANT, PET, UNKNOWN,
}

/** Which glyph an entity draws: its `mdi:` icon when we know it, else its domain and device class. */
object HaIcons {

    fun glyph(entity: HaEntity, registryIcon: String? = null): HaGlyph {
        val mdi = (registryIcon ?: entity.icon)?.removePrefix("mdi:")?.lowercase()
        if (mdi != null) MDI[mdi]?.let { return it }
        return byKind(entity)
    }

    /** The on/off variant for a two-state glyph. */
    fun styled(glyph: HaGlyph, entity: HaEntity): HaGlyph = when (glyph) {
        HaGlyph.GARAGE -> if (entity.state == "open" || entity.state == "opening") HaGlyph.GARAGE_OPEN else glyph
        HaGlyph.LOCK -> if (entity.state == "unlocked" || entity.state == "open") HaGlyph.LOCK_OPEN else glyph
        HaGlyph.SHIELD -> if (entity.state == "disarmed") HaGlyph.SHIELD_OFF else glyph
        else -> glyph
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun byKind(e: HaEntity): HaGlyph = when (HaDomains.kindOf(e)) {
        HaKind.LIGHT -> HaGlyph.LIGHTBULB
        HaKind.SWITCH -> if (e.deviceClass == "outlet") HaGlyph.OUTLET else if (e.domain == "input_boolean") HaGlyph.TOGGLE else HaGlyph.POWER
        HaKind.FAN -> HaGlyph.FAN
        HaKind.COVER -> when (e.deviceClass) {
            "garage" -> HaGlyph.GARAGE
            "gate" -> HaGlyph.GATE
            "door" -> HaGlyph.DOOR
            "curtain" -> HaGlyph.CURTAINS
            "shutter" -> HaGlyph.SHUTTER
            "window" -> HaGlyph.WINDOW
            else -> HaGlyph.BLINDS
        }
        HaKind.CLIMATE -> HaGlyph.THERMOSTAT
        HaKind.MEDIA -> when (e.deviceClass) {
            "tv" -> HaGlyph.TV
            "receiver" -> HaGlyph.SPEAKER
            else -> HaGlyph.SPEAKER
        }
        HaKind.LOCK -> HaGlyph.LOCK
        HaKind.ALARM -> HaGlyph.SHIELD
        HaKind.VACUUM -> HaGlyph.VACUUM
        HaKind.HUMIDIFIER -> HaGlyph.HUMIDIFIER
        HaKind.WATER_HEATER -> HaGlyph.WATER_HEATER
        HaKind.SIREN -> HaGlyph.SIREN
        HaKind.VALVE -> HaGlyph.VALVE
        HaKind.NUMBER -> HaGlyph.NUMBER
        HaKind.SELECT -> HaGlyph.SELECT
        HaKind.TEXT -> HaGlyph.TEXT
        HaKind.BUTTON -> HaGlyph.BUTTON
        HaKind.SCENE -> HaGlyph.SCENE
        HaKind.SCRIPT -> HaGlyph.SCRIPT
        HaKind.AUTOMATION -> HaGlyph.AUTOMATION
        HaKind.CAMERA -> HaGlyph.CAMERA
        HaKind.SENSOR -> sensorGlyph(e.deviceClass)
        HaKind.BINARY_SENSOR -> binaryGlyph(e.deviceClass)
        HaKind.WEATHER -> weatherGlyph(e.state)
        HaKind.PERSON -> HaGlyph.PERSON
        HaKind.OTHER -> HaGlyph.UNKNOWN
    }

    private fun sensorGlyph(deviceClass: String?): HaGlyph = when (deviceClass) {
        "temperature" -> HaGlyph.THERMOMETER
        "humidity", "moisture" -> HaGlyph.HUMIDITY
        "battery" -> HaGlyph.BATTERY
        "power", "energy", "voltage", "current", "apparent_power", "reactive_power", "power_factor", "frequency" -> HaGlyph.BOLT
        "signal_strength" -> HaGlyph.WIFI
        "illuminance" -> HaGlyph.WEATHER_SUNNY
        "gas", "carbon_dioxide", "carbon_monoxide", "volatile_organic_compounds", "nitrogen_dioxide", "ozone", "pm25", "pm10", "pm1", "aqi" -> HaGlyph.AIR
        "water", "volume", "precipitation", "precipitation_intensity" -> HaGlyph.WATER
        "pressure", "atmospheric_pressure" -> HaGlyph.SENSOR
        "wind_speed" -> HaGlyph.WEATHER_WINDY
        else -> HaGlyph.SENSOR
    }

    private fun binaryGlyph(deviceClass: String?): HaGlyph = when (deviceClass) {
        "door", "opening" -> HaGlyph.DOOR
        "garage_door" -> HaGlyph.GARAGE
        "window" -> HaGlyph.WINDOW
        "lock" -> HaGlyph.LOCK
        "motion", "occupancy", "moving", "vibration", "presence" -> HaGlyph.MOTION
        "smoke", "carbon_monoxide" -> HaGlyph.SMOKE
        "moisture" -> HaGlyph.WATER
        "gas" -> HaGlyph.GAS
        "battery", "battery_charging" -> HaGlyph.BATTERY
        "connectivity" -> HaGlyph.WIFI
        "power", "plug" -> HaGlyph.OUTLET
        "heat" -> HaGlyph.FIRE
        "cold" -> HaGlyph.SNOWFLAKE
        "light" -> HaGlyph.LIGHTBULB
        "sound" -> HaGlyph.BELL
        "safety", "problem", "tamper" -> HaGlyph.SHIELD
        else -> HaGlyph.SENSOR
    }

    fun weatherGlyph(condition: String): HaGlyph = when (condition) {
        "sunny" -> HaGlyph.WEATHER_SUNNY
        "clear-night" -> HaGlyph.WEATHER_NIGHT
        "cloudy", "partlycloudy" -> HaGlyph.WEATHER_CLOUDY
        "rainy", "pouring", "hail", "snowy-rainy" -> HaGlyph.WEATHER_RAINY
        "snowy" -> HaGlyph.WEATHER_SNOWY
        "fog" -> HaGlyph.WEATHER_FOG
        "windy", "windy-variant" -> HaGlyph.WEATHER_WINDY
        "lightning", "lightning-rainy", "exceptional" -> HaGlyph.WEATHER_LIGHTNING
        else -> HaGlyph.WEATHER_CLOUDY
    }

    /** The `mdi:` names Home Assistant hands out most, and what we draw for each. */
    private val MDI: Map<String, HaGlyph> = mapOf(
        "lightbulb" to HaGlyph.LIGHTBULB, "lightbulb-outline" to HaGlyph.LIGHTBULB, "lightbulb-on" to HaGlyph.LIGHTBULB,
        "lightbulb-off" to HaGlyph.LIGHTBULB_OFF, "lightbulb-group" to HaGlyph.LIGHTBULB, "light-switch" to HaGlyph.TOGGLE,
        "ceiling-light" to HaGlyph.CEILING_LIGHT, "lamp" to HaGlyph.LAMP, "floor-lamp" to HaGlyph.LAMP, "desk-lamp" to HaGlyph.LAMP,
        "led-strip" to HaGlyph.LED_STRIP, "led-strip-variant" to HaGlyph.LED_STRIP, "wall-sconce" to HaGlyph.LAMP, "chandelier" to HaGlyph.CEILING_LIGHT,
        "power" to HaGlyph.POWER, "power-plug" to HaGlyph.OUTLET, "power-socket" to HaGlyph.OUTLET, "power-socket-eu" to HaGlyph.OUTLET,
        "power-socket-us" to HaGlyph.OUTLET, "power-socket-uk" to HaGlyph.OUTLET, "power-socket-de" to HaGlyph.OUTLET, "toggle-switch" to HaGlyph.TOGGLE,
        "toggle-switch-off" to HaGlyph.TOGGLE, "toggle-switch-variant" to HaGlyph.TOGGLE,
        "fan" to HaGlyph.FAN, "fan-off" to HaGlyph.FAN, "ceiling-fan" to HaGlyph.FAN, "air-purifier" to HaGlyph.AIR, "air-filter" to HaGlyph.AIR,
        "blinds" to HaGlyph.BLINDS, "blinds-open" to HaGlyph.BLINDS, "blinds-horizontal" to HaGlyph.BLINDS, "roller-shade" to HaGlyph.SHUTTER,
        "window-shutter" to HaGlyph.SHUTTER, "window-shutter-open" to HaGlyph.SHUTTER, "curtains" to HaGlyph.CURTAINS, "curtains-closed" to HaGlyph.CURTAINS,
        "garage" to HaGlyph.GARAGE, "garage-open" to HaGlyph.GARAGE_OPEN, "garage-variant" to HaGlyph.GARAGE, "gate" to HaGlyph.GATE, "gate-open" to HaGlyph.GATE,
        "door" to HaGlyph.DOOR, "door-closed" to HaGlyph.DOOR, "door-open" to HaGlyph.DOOR, "door-sliding" to HaGlyph.DOOR,
        "window-open" to HaGlyph.WINDOW, "window-closed" to HaGlyph.WINDOW, "window-closed-variant" to HaGlyph.WINDOW,
        "lock" to HaGlyph.LOCK, "lock-open" to HaGlyph.LOCK_OPEN, "lock-open-variant" to HaGlyph.LOCK_OPEN, "lock-smart" to HaGlyph.LOCK,
        "thermostat" to HaGlyph.THERMOSTAT, "thermostat-box" to HaGlyph.THERMOSTAT, "thermometer" to HaGlyph.THERMOMETER, "home-thermometer" to HaGlyph.THERMOMETER,
        "water-percent" to HaGlyph.HUMIDITY, "water-thermometer" to HaGlyph.WATER_HEATER, "water-boiler" to HaGlyph.WATER_HEATER, "water" to HaGlyph.WATER,
        "air-humidifier" to HaGlyph.HUMIDIFIER, "air-conditioner" to HaGlyph.SNOWFLAKE, "snowflake" to HaGlyph.SNOWFLAKE, "fire" to HaGlyph.FIRE,
        "radiator" to HaGlyph.RADIATOR, "radiator-disabled" to HaGlyph.RADIATOR, "hvac" to HaGlyph.THERMOSTAT, "heat-pump" to HaGlyph.THERMOSTAT,
        "speaker" to HaGlyph.SPEAKER, "speaker-wireless" to HaGlyph.SPEAKER, "cast" to HaGlyph.TV, "cast-audio" to HaGlyph.SPEAKER,
        "television" to HaGlyph.TV, "television-classic" to HaGlyph.TV, "monitor" to HaGlyph.TV, "music" to HaGlyph.MUSIC, "play" to HaGlyph.PLAY,
        "cctv" to HaGlyph.CAMERA, "video" to HaGlyph.CAMERA, "camera" to HaGlyph.CAMERA, "doorbell" to HaGlyph.BELL, "doorbell-video" to HaGlyph.BELL, "bell" to HaGlyph.BELL,
        "robot-vacuum" to HaGlyph.VACUUM, "robot-vacuum-variant" to HaGlyph.VACUUM, "robot-mower" to HaGlyph.VACUUM,
        "alarm-light" to HaGlyph.SIREN, "bullhorn" to HaGlyph.SIREN, "pipe-valve" to HaGlyph.VALVE, "valve" to HaGlyph.VALVE, "valve-open" to HaGlyph.VALVE,
        "motion-sensor" to HaGlyph.MOTION, "motion-sensor-off" to HaGlyph.MOTION, "walk" to HaGlyph.MOTION, "run" to HaGlyph.MOTION, "home-outline" to HaGlyph.HOME,
        "smoke-detector" to HaGlyph.SMOKE, "smoke" to HaGlyph.SMOKE, "gas-cylinder" to HaGlyph.GAS, "molecule-co2" to HaGlyph.AIR, "molecule-co" to HaGlyph.GAS,
        "flash" to HaGlyph.BOLT, "lightning-bolt" to HaGlyph.BOLT, "transmission-tower" to HaGlyph.BOLT, "meter-electric" to HaGlyph.BOLT, "solar-power" to HaGlyph.WEATHER_SUNNY,
        "battery" to HaGlyph.BATTERY, "battery-charging" to HaGlyph.BATTERY, "wifi" to HaGlyph.WIFI, "signal" to HaGlyph.WIFI, "lan-connect" to HaGlyph.WIFI,
        "palette" to HaGlyph.SCENE, "palette-outline" to HaGlyph.SCENE, "movie" to HaGlyph.SCENE, "movie-open" to HaGlyph.SCENE, "weather-night" to HaGlyph.WEATHER_NIGHT,
        "script-text" to HaGlyph.SCRIPT, "script-text-outline" to HaGlyph.SCRIPT, "robot" to HaGlyph.AUTOMATION, "robot-outline" to HaGlyph.AUTOMATION,
        "gesture-tap-button" to HaGlyph.BUTTON, "button-pointer" to HaGlyph.BUTTON, "restart" to HaGlyph.BUTTON, "refresh" to HaGlyph.BUTTON,
        "numeric" to HaGlyph.NUMBER, "ray-vertex" to HaGlyph.NUMBER, "format-list-bulleted" to HaGlyph.SELECT, "form-dropdown" to HaGlyph.SELECT,
        "form-textbox" to HaGlyph.TEXT, "text" to HaGlyph.TEXT,
        "weather-sunny" to HaGlyph.WEATHER_SUNNY, "white-balance-sunny" to HaGlyph.WEATHER_SUNNY, "brightness-5" to HaGlyph.WEATHER_SUNNY,
        "weather-cloudy" to HaGlyph.WEATHER_CLOUDY, "weather-partly-cloudy" to HaGlyph.WEATHER_CLOUDY, "weather-rainy" to HaGlyph.WEATHER_RAINY,
        "weather-pouring" to HaGlyph.WEATHER_RAINY, "weather-snowy" to HaGlyph.WEATHER_SNOWY, "weather-fog" to HaGlyph.WEATHER_FOG,
        "weather-windy" to HaGlyph.WEATHER_WINDY, "weather-lightning" to HaGlyph.WEATHER_LIGHTNING, "weather-lightning-rainy" to HaGlyph.WEATHER_LIGHTNING,
        "account" to HaGlyph.PERSON, "account-circle" to HaGlyph.PERSON, "home" to HaGlyph.HOME, "home-account" to HaGlyph.HOME,
        "shield-home" to HaGlyph.SHIELD, "shield-lock" to HaGlyph.SHIELD, "shield-off" to HaGlyph.SHIELD_OFF, "shield" to HaGlyph.SHIELD, "security" to HaGlyph.SHIELD,
        "car" to HaGlyph.CAR, "car-electric" to HaGlyph.CAR, "ev-station" to HaGlyph.CAR, "flower" to HaGlyph.PLANT, "sprout" to HaGlyph.PLANT, "leaf" to HaGlyph.PLANT,
        "dog" to HaGlyph.PET, "cat" to HaGlyph.PET, "paw" to HaGlyph.PET,
    )
}
