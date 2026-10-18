package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaActionsTest {

    private fun entity(id: String, state: String, attrs: JsonObject = JsonObject(emptyMap())) = HaEntity(id, state, attrs)

    @Test
    fun `brightness call carries percent and entity`() {
        val c = HaActions.setBrightnessPct("light.a", 140)
        assertEquals("light", c.domain)
        assertEquals("turn_on", c.service)
        assertEquals("light.a", c.entityId)
        assertEquals("""{"entity_id":"light.a","brightness_pct":100}""", HaJson.encodeToString(JsonObject.serializer(), c.restBody()))
    }

    @Test
    fun `toggle picks the entity domain or homeassistant`() {
        assertEquals("switch", HaActions.toggle("switch.x").domain)
        assertEquals("input_boolean", HaActions.toggle("input_boolean.x").domain)
        assertEquals("homeassistant", HaActions.toggle("group.x").domain)
    }

    @Test
    fun `number select and text use their own domains`() {
        assertEquals("input_number", HaActions.setValue("input_number.x", 3.0).domain)
        assertEquals("number", HaActions.setValue("number.x", 3.0).domain)
        assertEquals("select_option", HaActions.selectOption("input_select.x", "a").service)
        assertEquals("input_button", HaActions.press("input_button.x").domain)
    }

    @Test
    fun `codes travel only when given`() {
        assertTrue(HaActions.lock("lock.a").data.isEmpty())
        assertEquals("\"1234\"", HaActions.unlock("lock.a", "1234").data["code"].toString())
        assertEquals("alarm_arm_away", HaActions.arm("alarm_control_panel.h", HaActions.ArmMode.AWAY).service)
    }

    @Test
    fun `arm modes from feature bits`() {
        val panel = entity("alarm_control_panel.h", "disarmed", buildJsonObject { put("supported_features", 1 or 2 or 4) })
        assertEquals(listOf(HaActions.ArmMode.HOME, HaActions.ArmMode.AWAY, HaActions.ArmMode.NIGHT), HaActions.armModes(panel))
    }

    @Test
    fun `primary tap per kind`() {
        assertEquals(HaPrimary.TOGGLE, HaDomains.primary(entity("light.a", "on")))
        assertEquals(HaPrimary.RUN, HaDomains.primary(entity("scene.movie", "scening")))
        assertEquals(HaPrimary.PRESS, HaDomains.primary(entity("button.reboot", "unknown")))
        assertEquals(HaPrimary.EXPAND, HaDomains.primary(entity("climate.living", "heat")))
        assertEquals(HaPrimary.INSERT, HaDomains.primary(entity("sensor.temp", "21")))
        assertEquals(HaPrimary.EXPAND, HaDomains.primary(entity("lock.door", "locked")))
    }

    @Test
    fun `risky things`() {
        assertTrue(HaDomains.isRisky(entity("lock.front", "locked")))
        assertTrue(HaDomains.isRisky(entity("cover.garage", "closed", buildJsonObject { put("device_class", "garage") })))
        assertFalse(HaDomains.isRisky(entity("cover.blind", "closed", buildJsonObject { put("device_class", "blind") })))
        assertFalse(HaDomains.isRisky(entity("light.a", "on")))
    }

    @Test
    fun `expected state after a call`() {
        val light = entity("light.a", "on")
        assertEquals("off", HaActions.expectedState(light, HaActions.toggle("light.a")))
        assertEquals("locked", HaActions.expectedState(entity("lock.a", "unlocked"), HaActions.lock("lock.a")))
        assertEquals("armed_night", HaActions.expectedState(entity("alarm_control_panel.a", "disarmed"), HaActions.arm("alarm_control_panel.a", HaActions.ArmMode.NIGHT)))
        assertNull(HaActions.expectedState(entity("scene.a", "scening"), HaActions.runScene("scene.a")))
        assertEquals("paused", HaActions.expectedState(entity("media_player.a", "playing"), HaActions.mediaPlayPause("media_player.a")))
    }

    @Test
    fun `primary call for a scene is scene turn_on`() {
        val c = HaActions.primaryCall(entity("scene.movie", "scening"))!!
        assertEquals("scene", c.domain)
        assertEquals("turn_on", c.service)
        assertNull(HaActions.primaryCall(entity("sensor.t", "1")))
    }

    @Test
    fun `hs colour is an array`() {
        val c = HaActions.setHsColor("light.a", 120.0, 50.0)
        val expected = buildJsonObject { putJsonArray("hs_color") { add(120.0); add(50.0) } }
        assertEquals(expected["hs_color"].toString(), c.data["hs_color"].toString())
    }

    @Test
    fun `fan at zero turns off`() {
        assertEquals("turn_off", HaActions.setFanPercentage("fan.a", 0).service)
        assertEquals("set_percentage", HaActions.setFanPercentage("fan.a", 30).service)
    }

    @Test
    fun `unlisted domains`() {
        assertFalse(HaDomains.isListed("sun"))
        assertFalse(HaDomains.isListed("update"))
        assertTrue(HaDomains.isListed("light"))
    }
}
