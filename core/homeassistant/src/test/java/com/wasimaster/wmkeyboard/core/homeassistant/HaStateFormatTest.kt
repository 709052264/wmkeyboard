package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HaStateFormatTest {

    private val f = HaStateFormat(HaWords.English, Locale.US)

    private fun e(id: String, state: String, attrs: JsonObject = JsonObject(emptyMap())) = HaEntity(id, state, attrs)

    @Test
    fun `light with brightness`() {
        assertEquals("On · 50 %", f.state(e("light.a", "on", buildJsonObject { put("brightness", 128) })))
        assertEquals("Off", f.state(e("light.a", "off")))
        assertEquals("Unavailable", f.state(e("light.a", "unavailable")))
    }

    @Test
    fun `sensor with unit and precision`() {
        assertEquals("21.5 °C", f.state(e("sensor.t", "21.5", buildJsonObject { put("unit_of_measurement", "°C") })))
        assertEquals("1,234 kWh", f.state(e("sensor.e", "1234.4", buildJsonObject { put("unit_of_measurement", "kWh") })))
        assertEquals("42 %", f.state(e("sensor.h", "42", buildJsonObject { put("unit_of_measurement", "%") })))
        assertEquals("0.07", f.number(0.0712))
        assertEquals("Charging", f.state(e("sensor.s", "charging")))
    }

    @Test
    fun `cover with position`() {
        assertEquals("Open · 80 %", f.state(e("cover.b", "open", buildJsonObject { put("current_position", 80) })))
        assertEquals("Open", f.state(e("cover.b", "open", buildJsonObject { put("current_position", 100) })))
        assertEquals("Closing", f.state(e("cover.b", "closing")))
    }

    @Test
    fun `climate shows action and target`() {
        val c = e(
            "climate.l", "heat",
            buildJsonObject {
                put("hvac_action", "heating")
                put("temperature", 22.0)
                put("current_temperature", 21.4)
            },
        )
        assertEquals("Heating · 22 °", f.state(c))
        assertEquals("Heat/cool · 20–24 °C", f.state(e("climate.l", "heat_cool", buildJsonObject { put("target_temp_low", 20.0); put("target_temp_high", 24.0); put("temperature_unit", "°C") })))
    }

    @Test
    fun `media with title`() {
        assertEquals("Playing · Song", f.state(e("media_player.m", "playing", buildJsonObject { put("media_title", "Song") })))
        assertEquals("Off", f.state(e("media_player.m", "off", buildJsonObject { put("media_title", "Song") })))
    }

    @Test
    fun `binary sensors read by class`() {
        assertEquals("Open", f.state(e("binary_sensor.d", "on", buildJsonObject { put("device_class", "door") })))
        assertEquals("Clear", f.state(e("binary_sensor.m", "off", buildJsonObject { put("device_class", "motion") })))
        assertEquals("On", f.state(e("binary_sensor.x", "on")))
    }

    @Test
    fun `alarm lock vacuum weather person`() {
        assertEquals("Armed away", f.state(e("alarm_control_panel.a", "armed_away")))
        assertEquals("Unlocked", f.state(e("lock.a", "unlocked")))
        assertEquals("Cleaning · 60 %", f.state(e("vacuum.v", "cleaning", buildJsonObject { put("battery_level", 60) })))
        assertEquals("Partly cloudy · 18 °C", f.state(e("weather.h", "partlycloudy", buildJsonObject { put("temperature", 18.0); put("temperature_unit", "°C") })))
        assertEquals("Home", f.state(e("person.me", "home")))
        assertEquals("Away", f.state(e("person.me", "not_home")))
    }

    @Test
    fun `insert formats`() {
        val t = e("sensor.t", "21.5", buildJsonObject { put("unit_of_measurement", "°C"); put("friendly_name", "Outside") })
        assertEquals("21.5", f.insert(t, HaInsertFormat.VALUE))
        assertEquals("21.5 °C", f.insert(t, HaInsertFormat.VALUE_UNIT))
        assertEquals("Outside: 21.5 °C", f.insert(t, HaInsertFormat.NAME_VALUE))
        assertEquals("On · 50 %", f.insert(e("light.a", "on", buildJsonObject { put("brightness", 128) }), HaInsertFormat.VALUE))
    }

    @Test
    fun `number respects step`() {
        assertEquals("3.5", f.state(e("input_number.n", "3.5", buildJsonObject { put("step", 0.5) })))
        assertEquals("Fan only", f.state(e("climate.c", "fan_only")))
        assertEquals("Scene", f.state(e("scene.s", "scening")))
        assertEquals("Idle", f.state(e("camera.c", "idle")))
        assertEquals("Lights", f.value(e("select.s", "Lights", buildJsonObject { putJsonArray("options") { add("Lights") } })))
    }
}
