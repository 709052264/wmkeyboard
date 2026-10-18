package com.wasimaster.wmkeyboard.core.smarthome.hue

import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.HomeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HueTest {

    private fun data(vararg items: String) = """{"errors":[],"data":[${items.joinToString(",")}]}"""

    private val device = """{"id":"dev1","type":"device","metadata":{"name":"Desk lamp","archetype":"table_shade"},"product_data":{"product_name":"Hue color lamp"},"services":[{"rid":"l1","rtype":"light"}]}"""
    private val motionDevice = """{"id":"dev2","type":"device","metadata":{"name":"Hall sensor"},"services":[{"rid":"m1","rtype":"motion"},{"rid":"t1","rtype":"temperature"},{"rid":"p1","rtype":"device_power"}]}"""
    private val room = """{"id":"r1","type":"room","metadata":{"name":"Study","archetype":"office"},"children":[{"rid":"dev1","rtype":"device"},{"rid":"dev2","rtype":"device"}],"services":[{"rid":"g1","rtype":"grouped_light"}]}"""
    private val light = """{"id":"l1","type":"light","owner":{"rid":"dev1","rtype":"device"},"on":{"on":true},"dimming":{"brightness":40.0,"min_dim_level":0.2},
        "color_temperature":{"mirek":370,"mirek_valid":false,"mirek_schema":{"mirek_minimum":153,"mirek_maximum":500}},
        "color":{"xy":{"x":0.675,"y":0.322},"gamut_type":"C"},"effects":{"effect_values":["no_effect","candle","fire"],"status":"no_effect"}}"""
    private val grouped = """{"id":"g1","type":"grouped_light","owner":{"rid":"r1","rtype":"room"},"on":{"on":true},"dimming":{"brightness":40.0}}"""
    private val scene = """{"id":"s1","type":"scene","metadata":{"name":"Relax"},"group":{"rid":"r1","rtype":"room"},"status":{"active":"inactive"}}"""
    private val motion = """{"id":"m1","type":"motion","owner":{"rid":"dev2","rtype":"device"},"enabled":true,"motion":{"motion":true,"motion_report":{"changed":"2026-10-19T10:00:00Z","motion":true}}}"""
    private val temperature = """{"id":"t1","type":"temperature","owner":{"rid":"dev2","rtype":"device"},"enabled":true,"temperature":{"temperature":21.46,"temperature_valid":true}}"""
    private val power = """{"id":"p1","type":"device_power","owner":{"rid":"dev2","rtype":"device"},"power_state":{"battery_level":80,"battery_state":"normal"}}"""

    private fun model(): HueModel {
        var m = HueModel()
        fun add(type: String, json: String) {
            val o = HomeJson.parseToJsonElement(json).jsonObject
            m = m.with(type, o["id"]!!.toString().trim('"'), o)
        }
        add("device", device)
        add("device", motionDevice)
        add("room", room)
        add("light", light)
        add("grouped_light", grouped)
        add("scene", scene)
        add("motion", motion)
        add("temperature", temperature)
        add("device_power", power)
        return m
    }

    @Test
    fun `lights, a room group, a scene and sensors map across`() {
        val m = model()
        val entities = HueMapping.entities(m, 1.0)
        val lamp = entities.getValue("light.hue_l1")
        assertEquals("Desk lamp", lamp.friendlyName)
        assertEquals("on", lamp.state)
        assertEquals(40, lamp.brightnessPct)
        assertEquals(listOf("hs", "color_temp"), lamp.supportedColorModes)
        assertEquals("hs", lamp.colorMode)
        // xy (0.675, 0.322) is Hue's deep red.
        val (h, s) = lamp.hsColor!!
        assertTrue("hue $h", h < 25.0 || h > 350.0)
        assertTrue("sat $s", s > 90.0)
        assertNull(lamp.colorTempKelvin)
        assertEquals(2000, lamp.minColorTempKelvin)
        assertEquals(6535, lamp.maxColorTempKelvin)
        assertEquals(listOf("candle", "fire"), lamp.effectList)

        val group = entities.getValue("light.hue_g1")
        assertEquals("Study lights", group.friendlyName)
        assertEquals("Relax", entities.getValue("scene.hue_s1").friendlyName.substringAfter("· "))
        assertEquals("on", entities.getValue("binary_sensor.hue_m1").state)
        assertEquals("motion", entities.getValue("binary_sensor.hue_m1").deviceClass)
        assertEquals("21.5", entities.getValue("sensor.hue_t1").state)
        assertEquals("80", entities.getValue("sensor.hue_p1").state)
        assertEquals("Hall sensor battery", entities.getValue("sensor.hue_p1").friendlyName)

        val reg = HueMapping.registry(m)
        assertEquals("Study", reg.areaOf("light.hue_l1")!!.name)
        assertEquals("Study", reg.areaOf("light.hue_g1")!!.name)
        assertEquals("Study", reg.areaOf("scene.hue_s1")!!.name)
        assertEquals("Study", reg.areaOf("sensor.hue_t1")!!.name)
    }

    @Test
    fun `a partial event merges over the resource`() {
        val m = model()
        val update = HomeJson.parseToJsonElement("""{"id":"l1","type":"light","on":{"on":false},"dimming":{"brightness":10.0}}""").jsonObject
        val merged = HueMapping.merged(m.lights.getValue("l1"), update)
        val next = m.with("light", "l1", merged)
        val lamp = HueMapping.entities(next, 1.0).getValue("light.hue_l1")
        assertEquals("off", lamp.state)
        assertEquals(10, lamp.brightnessPct)
        assertEquals(listOf("hs", "color_temp"), lamp.supportedColorModes)
    }

    @Test
    fun `service calls become PUT bodies`() {
        val lamp = HueMapping.entities(model(), 1.0).getValue("light.hue_l1")
        val dim = HomeJson.parseToJsonElement(HueMapping.lightBody(HaActions.setBrightnessPct(lamp.entityId, 55), lamp)!!).jsonObject
        assertEquals("true", dim["on"]!!.jsonObject["on"].toString())
        assertEquals("55.0", dim["dimming"]!!.jsonObject["brightness"].toString())
        val warm = HomeJson.parseToJsonElement(HueMapping.lightBody(HaActions.setColorTempKelvin(lamp.entityId, 2700), lamp)!!).jsonObject
        assertEquals("370", warm["color_temperature"]!!.jsonObject["mirek"].toString())
        val red = HomeJson.parseToJsonElement(HueMapping.lightBody(HaActions.setHsColor(lamp.entityId, 0.0, 100.0), lamp)!!).jsonObject
        val xy = red["color"]!!.jsonObject["xy"]!!.jsonObject
        assertTrue(xy["x"].toString().toDouble() > 0.6)
        assertEquals("false", HomeJson.parseToJsonElement(HueMapping.lightBody(HaActions.toggle(lamp.entityId), lamp)!!).jsonObject["on"]!!.jsonObject["on"].toString())
        assertNull(HueMapping.lightBody(HaActions.lock(lamp.entityId), lamp))
    }

    @Test
    fun `colour conversions round trip`() {
        for ((h, s) in listOf(0.0 to 100.0, 120.0 to 100.0, 240.0 to 100.0, 60.0 to 50.0)) {
            val (x, y) = HueColor.hsToXy(h, s)
            val (h2, s2) = HueColor.xyToHs(x, y)
            val dh = minOf(Math.abs(h - h2), 360 - Math.abs(h - h2))
            assertTrue("hue $h -> $h2", dh < 8.0)
            assertTrue("sat $s -> $s2", Math.abs(s - s2) < 12.0)
        }
        assertEquals(2702, HueColor.mirekToKelvin(370))
        assertEquals(153, HueColor.kelvinToMirek(9000))
        assertEquals(500, HueColor.kelvinToMirek(1000))
    }

    @Test
    fun `the backend loads the bridge, follows the stream, and sends a PUT`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("hue-application-key") != "KEY") return MockResponse().setResponseCode(403).setBody("""{"errors":[{"description":"unauthorized user"}]}""")
                val path = request.path.orEmpty()
                return when {
                    path == "/clip/v2/resource/bridge" -> MockResponse().setBody(data("""{"id":"b","type":"bridge","bridge_id":"001788fffe000001"}"""))
                    path == "/clip/v2/resource/device" -> MockResponse().setBody(data(device, motionDevice))
                    path == "/clip/v2/resource/room" -> MockResponse().setBody(data(room))
                    path == "/clip/v2/resource/light" -> MockResponse().setBody(data(light))
                    path == "/clip/v2/resource/grouped_light" -> MockResponse().setBody(data(grouped))
                    path == "/clip/v2/resource/scene" -> MockResponse().setBody(data(scene))
                    path == "/clip/v2/resource/motion" -> MockResponse().setBody(data(motion))
                    path == "/clip/v2/resource/temperature" -> MockResponse().setBody(data(temperature))
                    path == "/clip/v2/resource/device_power" -> MockResponse().setBody(data(power))
                    path == "/clip/v2/resource/contact" -> MockResponse().setResponseCode(404)
                    path == "/clip/v2/resource/light/l1" && request.method == "PUT" -> MockResponse().setBody("""{"data":[{"rid":"l1","rtype":"light"}],"errors":[]}""")
                    path == "/eventstream/clip/v2" -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("id: 1\ndata: [{\"creationtime\":\"2026-10-19T10:00:00Z\",\"id\":\"e1\",\"type\":\"update\",\"data\":[{\"id\":\"l1\",\"type\":\"light\",\"on\":{\"on\":false}}]}]\n\n")
                        .setBodyDelay(200, TimeUnit.MILLISECONDS)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("hue:01", HomeKind.HUE, url = server.url("/").toString().trimEnd('/'), apiKey = "KEY")
        val backend = HueBackend("hue:01", account, OkHttpClient(), scope)
        try {
            runBlocking {
                withTimeout(10_000) {
                    backend.start()
                    backend.phase.first { it == HaPhase.LIVE }
                    val entities = backend.entities.first { it.isNotEmpty() }
                    assertEquals("on", entities.getValue("light.hue_l1").state)
                    assertEquals("hue:01", entities.getValue("light.hue_l1").home)
                    assertEquals("001788fffe000001", backend.instance.value!!.uuid)
                    assertEquals("Study", backend.registry.value.areaOf("light.hue_l1")!!.name)
                    // The stream says the lamp went off.
                    backend.entities.first { it["light.hue_l1"]?.state == "off" }
                    backend.callService(HaActions.setBrightnessPct("light.hue_l1", 25))
                }
            }
            val puts = (0 until server.requestCount).map { server.takeRequest() }.filter { it.method == "PUT" }
            assertEquals("/clip/v2/resource/light/l1", puts.first().path)
            assertTrue(puts.first().body.readUtf8().contains("\"brightness\":25.0"))
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `pairing answers null until the button is pressed`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{"error":{"type":101,"address":"","description":"link button not pressed"}}]"""))
        server.enqueue(MockResponse().setBody("""[{"success":{"username":"APPKEY","clientkey":"CK"}}]"""))
        server.enqueue(MockResponse().setBody("""{"name":"Hue","bridgeid":"001788FFFE000001","apiversion":"1.65.0"}"""))
        server.start()
        try {
            val http = HomeHttp(OkHttpClient())
            val base = server.url("/").toString().trimEnd('/')
            runBlocking {
                assertNull(HueApi.pair(http, base))
                assertEquals("APPKEY", HueApi.pair(http, base))
                val info = HueApi.info(http, base)!!
                assertEquals("001788fffe000001", info.id)
                assertEquals("Hue", info.name)
            }
            assertEquals("/api", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
        assertEquals("https://192.168.1.9", HueUrl.base("192.168.1.9/"))
        assertEquals("192.168.1.9", HueUrl.host("https://192.168.1.9:443/x"))
        assertNull(HueUrl.base("ftp://x"))
    }
}
