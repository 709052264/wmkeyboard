package com.wasimaster.wmkeyboard.core.smarthome.tuya

import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuyaTest {

    private val bulbJson = """
        {"id":"bulb1","name":"Desk lamp","category":"dj","online":true,"uid":"u1","update_time":1792404000,
         "status":[{"code":"switch_led","value":true},{"code":"work_mode","value":"white"},
                   {"code":"bright_value_v2","value":500},{"code":"temp_value_v2","value":0},
                   {"code":"colour_data_v2","value":"{\"h\":120,\"s\":1000,\"v\":1000}"}]}
    """.trimIndent()

    private val bulbSpec = """
        {"success":true,"result":{"category":"dj",
          "functions":[{"code":"switch_led","type":"Boolean","values":"{}"},
                       {"code":"work_mode","type":"Enum","values":"{\"range\":[\"white\",\"colour\",\"scene\"]}"},
                       {"code":"bright_value_v2","type":"Integer","values":"{\"min\":10,\"max\":1000,\"scale\":0,\"step\":1}"},
                       {"code":"temp_value_v2","type":"Integer","values":"{\"min\":0,\"max\":1000,\"scale\":0,\"step\":1}"},
                       {"code":"colour_data_v2","type":"Json","values":"{}"}],
          "status":[]}}
    """.trimIndent()

    private fun device(json: String, spec: String? = null): TuyaDevice {
        val d = TuyaMapping.parseDevice(HomeJson.parseToJsonElement(json).jsonObject)!!
        if (spec == null) return d
        val (specs, writable) = TuyaMapping.parseSpecification(spec)
        return d.copy(specs = specs, writable = writable)
    }

    @Test
    fun `a white-mode bulb is a light at its brightness and colour temperature`() {
        val light = TuyaMapping.entities(device(bulbJson, bulbSpec), 5.0).single { it.entityId == "light.tuya_bulb1" }
        assertEquals("on", light.state)
        assertEquals("Desk lamp", light.friendlyName)
        assertEquals(49, light.brightnessPct)
        assertEquals(2700, light.colorTempKelvin)
        assertEquals(listOf("hs", "color_temp"), light.supportedColorModes)
        assertEquals("color_temp", light.attributes["color_mode"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(1_792_404_000.0, light.lastChanged, 0.0)
    }

    @Test
    fun `brightness, colour and colour temperature become data point commands`() {
        val d = device(bulbJson, bulbSpec)
        val light = TuyaMapping.entities(d, 5.0).single { it.entityId == "light.tuya_bulb1" }
        val full = TuyaMapping.commands(HaActions.setBrightnessPct("light.tuya_bulb1", 100), "light.tuya_bulb1", d, light)
        assertEquals(listOf("switch_led" to "true", "bright_value_v2" to "1000"), full.map { it.code to it.value.toString() })
        val red = TuyaMapping.commands(HaActions.setHsColor("light.tuya_bulb1", 0.0, 100.0), "light.tuya_bulb1", d, light)
        assertEquals(listOf("switch_led", "work_mode", "colour_data_v2"), red.map { it.code })
        val colour = HomeJson.parseToJsonElement((red.last().value as kotlinx.serialization.json.JsonPrimitive).content).jsonObject
        assertEquals("0", colour["h"].toString())
        assertEquals("1000", colour["s"].toString())
        val warm = TuyaMapping.commands(HaActions.setColorTempKelvin("light.tuya_bulb1", 6500), "light.tuya_bulb1", d, light)
        assertEquals(listOf("switch_led" to "true", "work_mode" to "\"white\"", "temp_value_v2" to "1000"), warm.map { it.code to it.value.toString() })
        val off = TuyaMapping.commands(HaActions.turnOff("light.tuya_bulb1"), "light.tuya_bulb1", d, light)
        assertEquals("""{"commands":[{"code":"switch_led","value":false}]}""", TuyaMapping.commandsBody(off))
    }

    @Test
    fun `a power strip is a switch per socket with its power beside it`() {
        val strip = device(
            """
            {"id":"strip","name":"Strip","category":"pc","online":true,
             "status":[{"code":"switch_1","value":true},{"code":"switch_2","value":false},{"code":"switch_usb1","value":true},{"code":"cur_power","value":123}]}
            """.trimIndent(),
        )
        val entities = TuyaMapping.entities(strip, 5.0).associateBy { it.entityId }
        assertEquals("on", entities.getValue("switch.tuya_strip_switch_1").state)
        assertEquals("Strip 2", entities.getValue("switch.tuya_strip_switch_2").friendlyName)
        assertEquals("Strip USB 1", entities.getValue("switch.tuya_strip_switch_usb1").friendlyName)
        assertEquals("outlet", entities.getValue("switch.tuya_strip_switch_1").deviceClass)
        assertEquals("12.3", entities.getValue("sensor.tuya_strip_power").state)
        val on = TuyaMapping.commands(HaActions.turnOn("switch.tuya_strip_switch_2"), "switch.tuya_strip_switch_2", strip, entities["switch.tuya_strip_switch_2"])
        assertEquals(listOf("switch_2" to "true"), on.map { it.code to it.value.toString() })
    }

    @Test
    fun `a single plug is one switch named after the device`() {
        val plug = device("""{"id":"p","name":"Kettle","category":"cz","online":false,"status":[{"code":"switch_1","value":true}]}""")
        val switch = TuyaMapping.entities(plug, 5.0).single()
        assertEquals("switch.tuya_p", switch.entityId)
        assertEquals("unavailable", switch.state)
        val off = TuyaMapping.commands(HaActions.turnOff("switch.tuya_p"), "switch.tuya_p", plug, switch)
        assertEquals(listOf("switch_1" to "false"), off.map { it.code to it.value.toString() })
    }

    @Test
    fun `a thermometer reads at scale one without a specification`() {
        val t = device(
            """{"id":"th","name":"Hall","category":"wsdcg","online":true,"status":[{"code":"va_temperature","value":235},{"code":"va_humidity","value":48},{"code":"battery_percentage","value":90}]}""",
        )
        val entities = TuyaMapping.entities(t, 5.0).associateBy { it.entityId }
        assertEquals("23.5", entities.getValue("sensor.tuya_th_temperature").state)
        assertEquals("°C", entities.getValue("sensor.tuya_th_temperature").unit)
        assertEquals("48", entities.getValue("sensor.tuya_th_humidity").state)
        assertEquals("90", entities.getValue("sensor.tuya_th_battery").state)
    }

    @Test
    fun `a curtain opens, stops and goes to a position`() {
        val c = device(
            """{"id":"cur","name":"Curtain","category":"cl","online":true,"status":[{"code":"control","value":"stop"},{"code":"percent_control","value":30}]}""",
            """{"success":true,"result":{"functions":[{"code":"control","type":"Enum","values":"{\"range\":[\"open\",\"stop\",\"close\"]}"},{"code":"percent_control","type":"Integer","values":"{\"min\":0,\"max\":100}"}],"status":[]}}""",
        )
        val cover = TuyaMapping.entities(c, 5.0).single()
        assertEquals("open", cover.state)
        assertEquals(30, cover.currentPosition)
        assertEquals(listOf("control" to "\"stop\""), TuyaMapping.commands(HaActions.stopCover("cover.tuya_cur"), "cover.tuya_cur", c, cover).map { it.code to it.value.toString() })
        assertEquals(listOf("percent_control" to "80"), TuyaMapping.commands(HaActions.setCoverPosition("cover.tuya_cur", 80), "cover.tuya_cur", c, cover).map { it.code to it.value.toString() })
    }

    @Test
    fun `the string to sign sorts the query and hashes the body`() {
        val s = TuyaSign.stringToSign("GET", "/v1.0/token", mapOf("grant_type" to "1", "a" to "b"), "")
        assertEquals("GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n/v1.0/token?a=b&grant_type=1", s)
        val sign = TuyaSign.sign("id", "secret", "", 1_588_925_778_000, s)
        assertTrue(sign.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun `a session lists devices, rooms and scenes, and a command is signed with the token`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                fun ok(result: String) = MockResponse().setBody("""{"success":true,"result":$result,"t":1}""")
                return when (path) {
                    "/v1.0/token" -> ok("""{"access_token":"tok","expire_time":7200,"uid":"dev"}""")
                    "/v1.0/iot-01/associated-users/devices" -> ok("""{"devices":[$bulbJson],"has_more":false}""")
                    "/v1.0/devices/bulb1/specifications" -> MockResponse().setBody(bulbSpec)
                    "/v1.0/users/u1/homes" -> ok("""[{"home_id":1,"name":"Flat"}]""")
                    "/v1.0/homes/1/rooms" -> ok("""{"rooms":[{"room_id":10,"name":"Study"}]}""")
                    "/v1.0/homes/1/rooms/10/devices" -> ok("""[{"id":"bulb1"}]""")
                    "/v1.0/homes/1/scenes" -> ok("""[{"scene_id":"sc1","name":"Evening"}]""")
                    "/v1.0/devices/bulb1/commands" -> ok("true")
                    "/v1.0/devices/bulb1/status" -> ok("""[{"code":"switch_led","value":false}]""")
                    "/v1.0/homes/1/scenes/sc1/trigger" -> ok("true")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("tuya:01", HomeKind.TUYA, url = server.url("/").toString(), apiKey = "secret", extra = mapOf(HomeKeys.CLIENT_ID to "cid"))
        val backend = TuyaBackend("tuya:01", account, HomeHttp(OkHttpClient()), scope, pollMs = 200)
        try {
            runBlocking {
                withTimeout(10_000) {
                    backend.start()
                    backend.phase.first { it == HaPhase.REST_FALLBACK }
                    val entities = backend.entities.first { it.isNotEmpty() }
                    assertEquals("tuya:01", entities.getValue("light.tuya_bulb1").home)
                    assertEquals("Evening", entities.getValue("scene.tuya_sc1").friendlyName)
                    assertEquals("Flat", backend.instance.value!!.name)
                    assertEquals("Study", backend.registry.value.areaOf("light.tuya_bulb1")!!.name)

                    backend.callService(HaActions.turnOff("light.tuya_bulb1"))
                    backend.entities.first { it["light.tuya_bulb1"]?.state == "off" }
                    backend.callService(HaActions.runScene("scene.tuya_sc1"))
                }
            }
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            val token = requests.first { it.requestUrl!!.encodedPath == "/v1.0/token" }
            assertEquals("cid", token.getHeader("client_id"))
            assertNull(token.getHeader("access_token"))
            val command = requests.single { it.requestUrl!!.encodedPath == "/v1.0/devices/bulb1/commands" }
            assertEquals("tok", command.getHeader("access_token"))
            assertEquals("HMAC-SHA256", command.getHeader("sign_method"))
            assertEquals("""{"commands":[{"code":"switch_led","value":false}]}""", command.body.readUtf8())
            // The specification is asked for once, however often the list is polled.
            assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/v1.0/devices/bulb1/specifications" })
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `a refused project stops with TOKEN_REJECTED`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"success":false,"code":1004,"msg":"sign invalid"}"""))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("tuya:01", HomeKind.TUYA, url = server.url("/").toString(), apiKey = "wrong", extra = mapOf(HomeKeys.CLIENT_ID to "cid"))
        val backend = TuyaBackend("tuya:01", account, HomeHttp(OkHttpClient()), scope)
        try {
            runBlocking { withTimeout(10_000) { backend.start(); backend.phase.first { it == HaPhase.TOKEN_REJECTED } } }
            assertEquals("HTTP 401", backend.error.value)
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `an account is ready with a data centre, an Access ID and an Access Secret`() {
        assertFalse(HomeAccount("t", HomeKind.TUYA, apiKey = "s", extra = mapOf(HomeKeys.CLIENT_ID to "c")).ready)
        assertFalse(HomeAccount("t", HomeKind.TUYA, url = TuyaRegions.all.first().base, apiKey = "s").ready)
        assertTrue(HomeAccount("t", HomeKind.TUYA, url = TuyaRegions.all.first().base, apiKey = "s", extra = mapOf(HomeKeys.CLIENT_ID to "c")).ready)
    }
}
