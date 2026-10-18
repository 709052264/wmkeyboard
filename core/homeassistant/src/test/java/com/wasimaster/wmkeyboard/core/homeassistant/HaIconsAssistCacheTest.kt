package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaIconsAssistCacheTest {

    private fun e(id: String, state: String, attrs: JsonObject = JsonObject(emptyMap())) = HaEntity(id, state, attrs)

    @Test
    fun `mdi icon wins over domain`() {
        assertEquals(HaGlyph.LAMP, HaIcons.glyph(e("light.a", "on", buildJsonObject { put("icon", "mdi:floor-lamp") })))
        assertEquals(HaGlyph.LIGHTBULB, HaIcons.glyph(e("light.a", "on")))
        assertEquals(HaGlyph.OUTLET, HaIcons.glyph(e("switch.a", "on", buildJsonObject { put("device_class", "outlet") })))
        assertEquals(HaGlyph.GARAGE, HaIcons.glyph(e("cover.g", "closed", buildJsonObject { put("device_class", "garage") })))
        assertEquals(HaGlyph.GARAGE_OPEN, HaIcons.styled(HaGlyph.GARAGE, e("cover.g", "open")))
        assertEquals(HaGlyph.LOCK_OPEN, HaIcons.styled(HaGlyph.LOCK, e("lock.g", "unlocked")))
        assertEquals(HaGlyph.THERMOMETER, HaIcons.glyph(e("sensor.t", "1", buildJsonObject { put("device_class", "temperature") })))
        assertEquals(HaGlyph.WEATHER_RAINY, HaIcons.glyph(e("weather.h", "rainy")))
        assertEquals(HaGlyph.UNKNOWN, HaIcons.glyph(e("foo.bar", "x")))
        assertEquals(HaGlyph.SCENE, HaIcons.glyph(e("scene.s", "x"), registryIcon = "mdi:palette"))
    }

    @Test
    fun `assist reply parses targets and speech`() {
        val reply = HaAssist.parse(
            HaJson.parseToJsonElement(
                """{"continue_conversation":false,"conversation_id":"abc","response":{"response_type":"action_done","language":"en",
                   "data":{"success":[{"name":"Kitchen","type":"area","id":"kitchen"},{"name":"Lamp","type":"entity","id":"light.lamp"}],"failed":[]},
                   "speech":{"plain":{"speech":"Turned on the lights","extra_data":null}}}}""",
            ).jsonObject,
        )
        assertEquals(HaAssistReply.ACTION_DONE, reply.type)
        assertEquals("Turned on the lights", reply.speech)
        assertEquals(listOf("light.lamp"), reply.successEntityIds)
        assertEquals("abc", reply.conversationId)
        assertFalse(reply.isError)
    }

    @Test
    fun `assist error reply`() {
        val reply = HaAssist.parse(
            HaJson.parseToJsonElement(
                """{"response":{"response_type":"error","data":{"code":"no_intent_match"},"speech":{"plain":{"speech":"Sorry, I couldn't understand that"}}},"conversation_id":null}""",
            ).jsonObject,
        )
        assertTrue(reply.isError)
        assertEquals("no_intent_match", reply.errorCode)
        assertNull(reply.conversationId)
    }

    @Test
    fun `assist request omits blanks`() {
        val r = HaAssist.request("hi", language = "", agentId = null, conversationId = "c1")
        assertEquals("""{"text":"hi","conversation_id":"c1"}""", HaJson.encodeToString(JsonObject.serializer(), r))
    }

    @Test
    fun `snapshot round trip`() {
        val snap = HaSnapshot(
            instance = HaInstance("Casa", "2026.10"),
            entities = listOf(e("light.a", "on", buildJsonObject { put("brightness", 10) })),
            registry = HaRegistry(areas = listOf(HaArea("k", "Kitchen")), entries = listOf(HaEntityEntry("light.a", areaId = "k"))),
            savedAtMs = 42L,
        )
        val back = HaCache.decode(HaCache.encode(snap))
        assertNotNull(back)
        assertEquals("Casa", back!!.instance?.name)
        assertEquals(10, back.entities.single().brightness)
        assertEquals("k", back.registry.areaIdOf("light.a"))
        assertEquals(42L, back.savedAtMs)
        assertNull(HaCache.decode("{not json"))
        assertNull(HaCache.decode("""{"version":99}"""))
    }

    @Test
    fun `found instance picks internal url`() {
        val f = HaFound.fromTxt(mapOf("location_name" to "Home", "internal_url" to "http://192.168.1.10:8123", "version" to "2026.10.1"), "192.168.1.10", 8123)
        assertEquals("http://192.168.1.10:8123", f.internal!!.base)
        assertNull(f.external)
        assertFalse(f.isLandingPage)
        val bare = HaFound.fromTxt(emptyMap(), "10.0.0.5", 8123)
        assertEquals("http://10.0.0.5:8123", bare.internal!!.base)
        assertEquals("Home", bare.name)
    }
}
