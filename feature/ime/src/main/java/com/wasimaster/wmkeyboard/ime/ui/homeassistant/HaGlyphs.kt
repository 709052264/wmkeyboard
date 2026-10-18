package com.wasimaster.wmkeyboard.ime.ui.homeassistant

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.core.homeassistant.HaGlyph
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AcUnit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Air
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatteryStd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bedtime
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Blinds
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CleaningServices
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Curtains
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DetectorSmoke
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeviceThermostat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeviceUnknown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DirectionsCar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DirectionsRun
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DoorFront
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FloorLamp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fluorescent
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Foggy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Garage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GarageDoor
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GasMeter
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HumidityPercentage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Light
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lightbulb
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LightOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocalFireDepartment
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LockOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ModeFan
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NotificationsActive
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Numbers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Outlet
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Person
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pets
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PottedPlant
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PowerSettingsNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Radio
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Rainy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RemoveModerator
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RollerShades
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sensors
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Siren
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartButton
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartToy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speaker
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Terminal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thermostat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thunderstorm
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Valve
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Videocam
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WaterDrop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WaterHeater
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WeatherSnowy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Window

/** The drawing for each glyph the engine can name. Exhaustive: a new glyph is a compile error here first. */
internal fun HaGlyph.vector(): ImageVector = when (this) {
    HaGlyph.LIGHTBULB -> Icons.Outlined.Lightbulb
    HaGlyph.LIGHTBULB_OFF -> Icons.Outlined.LightOff
    HaGlyph.LAMP -> Icons.Outlined.FloorLamp
    HaGlyph.LED_STRIP -> Icons.Outlined.Fluorescent
    HaGlyph.CEILING_LIGHT -> Icons.Outlined.Light
    HaGlyph.POWER -> Icons.Outlined.PowerSettingsNew
    HaGlyph.OUTLET -> Icons.Outlined.Outlet
    HaGlyph.FAN -> Icons.Outlined.ModeFan
    HaGlyph.BLINDS -> Icons.Outlined.Blinds
    HaGlyph.CURTAINS -> Icons.Outlined.Curtains
    HaGlyph.SHUTTER -> Icons.Outlined.RollerShades
    HaGlyph.GARAGE -> Icons.Outlined.Garage
    HaGlyph.GARAGE_OPEN -> Icons.Outlined.GarageDoor
    HaGlyph.DOOR -> Icons.Outlined.DoorFront
    HaGlyph.GATE -> Icons.Outlined.Gate
    HaGlyph.WINDOW -> Icons.Outlined.Window
    HaGlyph.LOCK -> Icons.Outlined.Lock
    HaGlyph.LOCK_OPEN -> Icons.Outlined.LockOpen
    HaGlyph.THERMOSTAT -> Icons.Outlined.Thermostat
    HaGlyph.THERMOMETER -> Icons.Outlined.DeviceThermostat
    HaGlyph.HUMIDITY -> Icons.Outlined.HumidityPercentage
    HaGlyph.AIR -> Icons.Outlined.Air
    HaGlyph.RADIATOR -> Icons.Outlined.Thermostat
    HaGlyph.SNOWFLAKE -> Icons.Outlined.AcUnit
    HaGlyph.FIRE -> Icons.Outlined.LocalFireDepartment
    HaGlyph.SPEAKER -> Icons.Outlined.Speaker
    HaGlyph.TV -> Icons.Outlined.Tv
    HaGlyph.CAMERA -> Icons.Outlined.Videocam
    HaGlyph.VACUUM -> Icons.Outlined.CleaningServices
    HaGlyph.WATER_HEATER -> Icons.Outlined.WaterHeater
    HaGlyph.HUMIDIFIER -> Icons.Outlined.HumidityPercentage
    HaGlyph.SIREN -> Icons.Outlined.Siren
    HaGlyph.VALVE -> Icons.Outlined.Valve
    HaGlyph.SENSOR -> Icons.Outlined.Sensors
    HaGlyph.BOLT -> Icons.Outlined.Bolt
    HaGlyph.BATTERY -> Icons.Outlined.BatteryStd
    HaGlyph.WIFI -> Icons.Outlined.Wifi
    HaGlyph.MOTION -> Icons.Outlined.DirectionsRun
    HaGlyph.SMOKE -> Icons.Outlined.DetectorSmoke
    HaGlyph.WATER -> Icons.Outlined.WaterDrop
    HaGlyph.GAS -> Icons.Outlined.GasMeter
    HaGlyph.SCENE -> Icons.Outlined.Palette
    HaGlyph.SCRIPT -> Icons.Outlined.Terminal
    HaGlyph.AUTOMATION -> Icons.Outlined.SmartToy
    HaGlyph.BUTTON -> Icons.Outlined.SmartButton
    HaGlyph.TOGGLE -> Icons.Outlined.ToggleOn
    HaGlyph.NUMBER -> Icons.Outlined.Numbers
    HaGlyph.SELECT -> Icons.Outlined.FormatListBulleted
    HaGlyph.TEXT -> Icons.Outlined.TextFields
    HaGlyph.WEATHER_SUNNY -> Icons.Outlined.WbSunny
    HaGlyph.WEATHER_NIGHT -> Icons.Outlined.Bedtime
    HaGlyph.WEATHER_CLOUDY -> Icons.Outlined.Cloud
    HaGlyph.WEATHER_RAINY -> Icons.Outlined.Rainy
    HaGlyph.WEATHER_SNOWY -> Icons.Outlined.WeatherSnowy
    HaGlyph.WEATHER_FOG -> Icons.Outlined.Foggy
    HaGlyph.WEATHER_WINDY -> Icons.Outlined.Air
    HaGlyph.WEATHER_LIGHTNING -> Icons.Outlined.Thunderstorm
    HaGlyph.PERSON -> Icons.Outlined.Person
    HaGlyph.HOME -> Icons.Outlined.Home
    HaGlyph.SHIELD -> Icons.Outlined.Shield
    HaGlyph.SHIELD_OFF -> Icons.Outlined.RemoveModerator
    HaGlyph.BELL -> Icons.Outlined.NotificationsActive
    HaGlyph.MUSIC -> Icons.Outlined.MusicNote
    HaGlyph.PLAY -> Icons.Outlined.PlayArrow
    HaGlyph.CAR -> Icons.Outlined.DirectionsCar
    HaGlyph.PLANT -> Icons.Outlined.PottedPlant
    HaGlyph.PET -> Icons.Outlined.Pets
    HaGlyph.UNKNOWN -> Icons.Outlined.DeviceUnknown
}

/** A radio glyph, kept imported for the media source chips. */
internal val RadioGlyph: ImageVector get() = Icons.Outlined.Radio
