package com.wasimaster.wmkeyboard.core.smarthome.switchbot

import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SwitchBotTest {

    private val list = """
        {"statusCode":100,"message":"success","body":{
          "deviceList":[
            {"deviceId":"BULB1","deviceName":"Desk lamp","deviceType":"Color Bulb","enableCloudService":true,"hubDeviceId":"000000000000"},
            {"deviceId":"CUR1","deviceName":"Curtain","deviceType":"Curtain3","enableCloudService":true,"hubDeviceId":"HUB"},
            {"deviceId":"MET1","deviceName":"Hall","deviceType":"Meter","enableCloudService":true,"hubDeviceId":"HUB"},
            {"deviceId":"HUB","deviceName":"Hub","deviceType":"Hub Mini","hubDeviceId":"000000000000"}],
          "infraredRemoteList":[{"deviceId":"02-TV","deviceName":"TV","remoteType":"TV","hubDeviceId":"HUB"}]}}
    """.trimIndent()

    private fun status(body: String) = """{"statusCode":100,"message":"success","body":$body}"""

    private fun device(type: String, statusBody: String) =
        SbDevice("ID1", "Thing", type, status = SwitchBotMapping.parseStatus(status(statusBody)))

    @Test
    fun `a colour bulb is a light with its brightness and colour`() {
        val light = SwitchBotMapping.entities(device("Color Bulb", """{"power":"on","brightness":50,"color":"255:0:0","colorTemperature":3000}"""), 5.0).single()
        assertEquals("light.sb_id1", light.entityId)
        assertEquals("on", light.state)
        assertEquals(50, light.brightnessPct)
        assertEquals(0.0 to 100.0, light.hsColor)
        assertEquals(3000, light.colorTempKelvin)
        assertEquals(listOf("hs", "color_temp"), light.supportedColorModes)
    }

    @Test
    fun `light calls become SwitchBot commands`() {
        val d = device("Color Bulb", """{"power":"off","brightness":50,"color":"255:0:0"}""")
        val light = SwitchBotMapping.entities(d, 5.0).single()
        val dim = SwitchBotMapping.commands(HaActions.setBrightnessPct(light.entityId, 30), light.entityId, d, light)
        assertEquals(listOf("turnOn", "setBrightness"), dim.map { it.command })
        assertEquals("""{"command":"setBrightness","parameter":30,"commandType":"command"}""", dim.last().json())
        val green = SwitchBotMapping.commands(HaActions.setHsColor(light.entityId, 120.0, 100.0), light.entityId, d, light)
        assertEquals("""{"command":"setColor","parameter":"0:255:0","commandType":"command"}""", green.last().json())
    }

    @Test
    fun `a curtain counts its position from closed, the other way round from SwitchBot`() {
        val d = device("Curtain3", """{"slidePosition":0,"moving":false,"battery":80,"calibrate":true}""")
        val entities = SwitchBotMapping.entities(d, 5.0).associateBy { it.entityId }
        val cover = entities.getValue("cover.sb_id1")
        assertEquals("open", cover.state)
        assertEquals(100, cover.currentPosition)
        assertEquals("80", entities.getValue("sensor.sb_id1_battery").state)
        val to40 = SwitchBotMapping.commands(HaActions.setCoverPosition(cover.entityId, 40), cover.entityId, d, cover)
        assertEquals("""{"command":"setPosition","parameter":"0,ff,60","commandType":"command"}""", to40.single().json())
        assertEquals("pause", SwitchBotMapping.commands(HaActions.stopCover(cover.entityId), cover.entityId, d, cover).single().command)
    }

    @Test
    fun `sensors ride along a meter, a contact sensor and a leak detector`() {
        val meter = SwitchBotMapping.entities(device("Meter", """{"temperature":26.1,"humidity":52,"battery":100}"""), 5.0).associateBy { it.entityId }
        assertEquals("26.1", meter.getValue("sensor.sb_id1_temperature").state)
        assertEquals("52", meter.getValue("sensor.sb_id1_humidity").state)
        val contact = SwitchBotMapping.entities(device("Contact Sensor", """{"openState":"timeOutNotClose","moveDetected":false}"""), 5.0).associateBy { it.entityId }
        assertEquals("on", contact.getValue("binary_sensor.sb_id1_contact").state)
        assertEquals("off", contact.getValue("binary_sensor.sb_id1_motion").state)
        val leak = SwitchBotMapping.entities(device("Water Detector", """{"status":1,"battery":90}"""), 5.0).associateBy { it.entityId }
        assertEquals("on", leak.getValue("binary_sensor.sb_id1_water").state)
    }

    @Test
    fun `a two-channel relay is two switches, each its own channel`() {
        val d = device("Relay Switch 2PM", """{"switch1Status":1,"switch2Status":0}""")
        val entities = SwitchBotMapping.entities(d, 5.0).associateBy { it.entityId }
        assertEquals("on", entities.getValue("switch.sb_id1_1").state)
        assertEquals("off", entities.getValue("switch.sb_id1_2").state)
        val on2 = SwitchBotMapping.commands(HaActions.turnOn("switch.sb_id1_2"), "switch.sb_id1_2", d, entities["switch.sb_id1_2"])
        assertEquals("""{"command":"turnOn","parameter":"2","commandType":"command"}""", on2.single().json())
    }

    @Test
    fun `the signature is Base64 of the HMAC, as SwitchBot's own examples make it`() {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
        val expected = Base64.getEncoder().encodeToString(mac.doFinal("token1661927531000nonce".toByteArray()))
        assertEquals(expected, SwitchBotSign.sign("token", "secret", 1_661_927_531_000, "nonce"))
    }

    @Test
    fun `a session lists devices and remotes, polls what reports, and commands a remote`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/devices" -> MockResponse().setBody(list)
                "/scenes" -> MockResponse().setBody(status("""[{"sceneId":"S1","sceneName":"Movie night"}]"""))
                "/devices/BULB1/status" -> MockResponse().setBody(status("""{"power":"on","brightness":80}"""))
                "/devices/CUR1/status" -> MockResponse().setBody("""{"statusCode":161,"message":"device offline","body":{}}""")
                "/devices/MET1/status" -> MockResponse().setBody(status("""{"temperature":21.0,"humidity":40}"""))
                "/devices/02-TV/commands" -> MockResponse().setBody(status("{}"))
                "/scenes/S1/execute" -> MockResponse().setBody(status("{}"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("switchbot:01", HomeKind.SWITCHBOT, apiKey = "token", extra = mapOf(HomeKeys.SECRET to "secret"))
        val backend = SwitchBotBackend("switchbot:01", account, HomeHttp(OkHttpClient()), scope, pollMs = 60_000, base = server.url("/").toString().trimEnd('/'))
        try {
            runBlocking {
                withTimeout(10_000) {
                    backend.start()
                    backend.phase.first { it == HaPhase.REST_FALLBACK }
                    val entities = backend.entities.value
                    assertEquals("on", entities.getValue("light.sb_bulb1").state)
                    assertEquals("unavailable", entities.getValue("cover.sb_cur1").state)
                    assertEquals("21", entities.getValue("sensor.sb_met1_temperature").state)
                    assertEquals("off", entities.getValue("switch.sb_02_tv").state)
                    assertEquals("Movie night", entities.getValue("scene.sb_s1").friendlyName)

                    backend.callService(HaActions.turnOn("switch.sb_02_tv"))
                    assertEquals("on", backend.entities.value.getValue("switch.sb_02_tv").state)
                    backend.callService(HaActions.runScene("scene.sb_s1"))
                }
            }
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            // The hub and the remote report nothing, so nobody asks them.
            assertFalse(requests.any { it.requestUrl!!.encodedPath == "/devices/HUB/status" || it.requestUrl!!.encodedPath == "/devices/02-TV/status" })
            val command = requests.single { it.requestUrl!!.encodedPath == "/devices/02-TV/commands" }
            assertEquals("token", command.getHeader("Authorization"))
            assertTrue(command.getHeader("sign")!!.isNotBlank())
            assertEquals("""{"command":"turnOn","parameter":"default","commandType":"command"}""", command.body.readUtf8())
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `a refused token stops with TOKEN_REJECTED`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("switchbot:01", HomeKind.SWITCHBOT, apiKey = "bad", extra = mapOf(HomeKeys.SECRET to "s"))
        val backend = SwitchBotBackend("switchbot:01", account, HomeHttp(OkHttpClient()), scope, base = server.url("/").toString().trimEnd('/'))
        try {
            runBlocking { withTimeout(10_000) { backend.start(); backend.phase.first { it == HaPhase.TOKEN_REJECTED } } }
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }
}
