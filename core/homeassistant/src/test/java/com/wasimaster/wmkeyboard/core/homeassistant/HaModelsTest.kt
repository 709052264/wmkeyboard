package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaModelsTest {

    private fun obj(text: String): JsonObject = HaJson.parseToJsonElement(text).jsonObject

    @Test
    fun `rest state parses with iso times`() {
        val e = HaEntity.fromRest(
            obj(
                """{"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen","brightness":128,
                  "supported_color_modes":["color_temp","hs"],"color_temp_kelvin":3000},
                  "last_changed":"2026-10-18T10:00:00.500000+00:00","last_updated":"2026-10-18T12:00:00+02:00"}""",
            ),
        )!!
        assertEquals("light", e.domain)
        assertEquals("Kitchen", e.friendlyName)
        assertEquals(50, e.brightnessPct)
        assertTrue(e.supportsBrightness)
        assertTrue(e.supportsColorTemp)
        assertTrue(e.supportsColor)
        assertEquals(1792317600.5, e.lastChanged, 0.001)
        assertEquals(1792317600.0, e.lastUpdated, 0.001)
    }

    @Test
    fun `friendly name falls back to object id`() {
        val e = HaEntity("sensor.living_room_temperature", "21.5")
        assertEquals("Living room temperature", e.friendlyName)
        assertEquals(21.5, e.numeric!!, 0.0)
    }

    @Test
    fun `compressed add and change`() {
        val added = HaEntity.fromCompressed("switch.fan", obj("""{"s":"off","a":{"friendly_name":"Fan"},"lc":100.0}"""))
        assertEquals("off", added.state)
        assertEquals(100.0, added.lastUpdated, 0.0)
        val changed = added.applyCompressedChange(obj("""{"+":{"s":"on","lc":200.0,"a":{"icon":"mdi:fan"}},"-":{"a":["friendly_name"]}}"""))
        assertEquals("on", changed.state)
        assertEquals("mdi:fan", changed.icon)
        assertNull(changed.attributes["friendly_name"])
        assertEquals(200.0, changed.lastChanged, 0.0)
        assertEquals(200.0, changed.lastUpdated, 0.0)
        val attrOnly = changed.applyCompressedChange(obj("""{"+":{"lu":250.0,"a":{"percentage":40}}}"""))
        assertEquals("on", attrOnly.state)
        assertEquals(200.0, attrOnly.lastChanged, 0.0)
        assertEquals(250.0, attrOnly.lastUpdated, 0.0)
        assertEquals(40, attrOnly.percentage)
    }

    @Test
    fun `iso time variants`() {
        assertEquals(0.0, HaTime.parseIso("1970-01-01T00:00:00+00:00")!!, 0.0)
        assertEquals(3600.0, HaTime.parseIso("1970-01-01T00:00:00-01:00")!!, 0.0)
        assertEquals(0.25, HaTime.parseIso("1970-01-01T00:00:00.25Z")!!, 0.0)
        assertNull(HaTime.parseIso("yesterday"))
    }

    @Test
    fun `registry joins device area and hides diagnostics`() {
        val reg = HaRegistry.parse(
            areas = HaJson.parseToJsonElement("""[{"area_id":"kitchen","name":"Kitchen","floor_id":"ground"},{"area_id":"attic","name":"Attic","floor_id":"top"}]""") as kotlinx.serialization.json.JsonArray,
            floors = HaJson.parseToJsonElement("""[{"floor_id":"top","name":"Top","level":2},{"floor_id":"ground","name":"Ground","level":0}]""") as kotlinx.serialization.json.JsonArray,
            devices = HaJson.parseToJsonElement("""[{"id":"dev1","area_id":"kitchen","name":"Hue bridge"}]""") as kotlinx.serialization.json.JsonArray,
            entries = HaJson.parseToJsonElement(
                """[{"entity_id":"light.a","device_id":"dev1","name":"Big light"},
                    {"entity_id":"sensor.a_signal","device_id":"dev1","entity_category":"diagnostic"},
                    {"entity_id":"light.b","area_id":"attic","hidden_by":"user"}]""",
            ) as kotlinx.serialization.json.JsonArray,
        )
        assertEquals("kitchen", reg.areaIdOf("light.a"))
        assertEquals("attic", reg.areaIdOf("light.b"))
        assertNull(reg.areaIdOf("light.none"))
        assertTrue(reg.isHidden("sensor.a_signal"))
        assertTrue(reg.isHidden("light.b"))
        assertFalse(reg.isHidden("light.a"))
        assertEquals("Big light", reg.nameOf(HaEntity("light.a", "on")))
        assertEquals(listOf("Kitchen", "Attic"), reg.orderedAreas().map { it.name })
    }

    @Test
    fun `instance from config`() {
        val i = HaInstance.parse(obj("""{"location_name":"Casa","version":"2026.10.1","unit_system":{"temperature":"°C"},"language":"en"}"""))
        assertEquals("Casa", i.name)
        assertEquals("2026.10.1", i.version)
        assertEquals("°C", i.temperatureUnit)
    }
}
