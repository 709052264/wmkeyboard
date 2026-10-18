package com.wasimaster.wmkeyboard.core.smarthome.smartthings

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartThingsTest {

    private val bulb = """
        {"deviceId":"7b3c-1","name":"bulb","label":"Desk lamp","locationId":"loc1","roomId":"room1",
         "components":[{"id":"main","label":"main",
           "capabilities":[{"id":"switch","version":1},{"id":"switchLevel","version":1},{"id":"colorTemperature","version":1},{"id":"temperatureMeasurement","version":1}],
           "categories":[{"name":"Light","categoryType":"manufacturer"}]}],
         "status":{"components":{"main":{
           "switch":{"switch":{"value":"on","timestamp":"2026-10-19T10:00:00.000Z"}},
           "switchLevel":{"level":{"value":50,"unit":"%"}},
           "colorTemperature":{"colorTemperature":{"value":3000,"unit":"K"}},
           "temperatureMeasurement":{"temperature":{"value":21.5,"unit":"C"}}}}}}
    """.trimIndent()

    private val thermostat = """
        {"deviceId":"t-1","label":"Hall","locationId":"loc1","roomId":"room2",
         "components":[{"id":"main","capabilities":[{"id":"thermostatMode"},{"id":"thermostatHeatingSetpoint"},{"id":"thermostatCoolingSetpoint"},{"id":"temperatureMeasurement"},{"id":"thermostatOperatingState"}]}],
         "status":{"components":{"main":{
           "thermostatMode":{"thermostatMode":{"value":"auto"},"supportedThermostatModes":{"value":["off","heat","cool","auto"]}},
           "thermostatHeatingSetpoint":{"heatingSetpoint":{"value":19,"unit":"C"}},
           "thermostatCoolingSetpoint":{"coolingSetpoint":{"value":24,"unit":"C"}},
           "thermostatOperatingState":{"thermostatOperatingState":{"value":"heating"}},
           "temperatureMeasurement":{"temperature":{"value":20.5,"unit":"C"}}}}}}
    """.trimIndent()

    private val shade = """
        {"deviceId":"s-1","label":"Blind","locationId":"loc1","roomId":"room1",
         "components":[{"id":"main","capabilities":[{"id":"windowShade"},{"id":"windowShadeLevel"},{"id":"battery"}]}],
         "status":{"components":{"main":{"windowShade":{"windowShade":{"value":"open"}},"windowShadeLevel":{"shadeLevel":{"value":70}},"battery":{"battery":{"value":88,"unit":"%"}}}}}}
    """.trimIndent()

    @Test
    fun `a bulb is a light with its colour temperature, and a thermometer beside it`() {
        val d = SmartThingsMapping.parseDevice(HomeJson.parseToJsonElement(bulb).jsonObject)!!
        assertTrue(SmartThingsMapping.run { d.isLight })
        val entities = SmartThingsMapping.entities(d, 5.0).associateBy { it.entityId }
        val light = entities.getValue("light.st_7b3c_1")
        assertEquals("on", light.state)
        assertEquals("Desk lamp", light.friendlyName)
        assertEquals(50, light.brightnessPct)
        assertEquals(3000, light.colorTempKelvin)
        assertEquals(listOf("color_temp"), light.supportedColorModes)
        assertTrue(light.supportsColorTemp)
        assertEquals(1_792_404_000.0, light.lastChanged, 0.0)
        val temp = entities.getValue("sensor.st_7b3c_1_temperature")
        assertEquals("21.5", temp.state)
        assertEquals("°C", temp.unit)
        assertEquals("temperature", temp.deviceClass)
    }

    @Test
    fun `a thermostat in auto carries a range and the action`() {
        val d = SmartThingsMapping.parseDevice(HomeJson.parseToJsonElement(thermostat).jsonObject)!!
        val climate = SmartThingsMapping.entities(d, 1.0).single()
        assertEquals("climate.st_t_1", climate.entityId)
        assertEquals("heat_cool", climate.state)
        assertEquals(listOf("off", "heat", "cool", "heat_cool"), climate.hvacModes)
        assertEquals(19.0, climate.targetTempLow!!, 0.0)
        assertEquals(24.0, climate.targetTempHigh!!, 0.0)
        assertNull(climate.targetTemperature)
        assertEquals(20.5, climate.currentTemperature!!, 0.0)
        assertEquals("heating", climate.hvacAction)
        assertEquals("°C", climate.temperatureUnit)
        val cmds = SmartThingsMapping.commands(HaActions.setHvacMode(climate.entityId, "heat_cool"), d, climate)
        assertEquals("auto", cmds.single().arguments.single().jsonPrimitive.content)
        val range = SmartThingsMapping.commands(HaActions.setTemperatureRange(climate.entityId, 18.0, 25.0), d, climate)
        assertEquals(listOf("setHeatingSetpoint", "setCoolingSetpoint"), range.map { it.command })
    }

    @Test
    fun `a shade is a cover with its level and battery, and commands read back`() {
        val d = SmartThingsMapping.parseDevice(HomeJson.parseToJsonElement(shade).jsonObject)!!
        val entities = SmartThingsMapping.entities(d, 1.0).associateBy { it.entityId }
        val cover = entities.getValue("cover.st_s_1")
        assertEquals("open", cover.state)
        assertEquals(70, cover.currentPosition)
        assertEquals("shade", cover.deviceClass)
        assertEquals("88", entities.getValue("sensor.st_s_1_battery").state)
        val pos = SmartThingsMapping.commands(HaActions.setCoverPosition(cover.entityId, 30), d, cover).single()
        assertEquals("windowShadeLevel", pos.capability)
        assertEquals("setShadeLevel", pos.command)
        assertEquals("30", pos.arguments.single().jsonPrimitive.content)
        assertEquals("pause", SmartThingsMapping.commands(HaActions.stopCover(cover.entityId), d, cover).single().command)
    }

    @Test
    fun `light commands become capability commands`() {
        val d = SmartThingsMapping.parseDevice(HomeJson.parseToJsonElement(bulb).jsonObject)!!
        val light = SmartThingsMapping.entities(d, 1.0).first()
        val dim = SmartThingsMapping.commands(HaActions.setBrightnessPct(light.entityId, 40), d, light)
        assertEquals(listOf("switch" to "on", "switchLevel" to "setLevel"), dim.map { it.capability to it.command })
        val body = HomeJson.parseToJsonElement(SmartThingsMapping.commandsBody(dim)).jsonObject["commands"]!!.jsonArray
        assertEquals("40", body[1].jsonObject["arguments"]!!.jsonArray[0].jsonPrimitive.content)
        val toggle = SmartThingsMapping.commands(HaActions.toggle(light.entityId), d, light).single()
        assertEquals("off", toggle.command)
        assertTrue(SmartThingsMapping.commands(HaActions.lock(light.entityId), d, light).isEmpty())
    }

    @Test
    fun `the backend loads rooms, devices and scenes, polls, and sends a command`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.getHeader("Authorization") != "Bearer PAT" -> MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                    path == "/locations" -> MockResponse().setBody("""{"items":[{"locationId":"loc1","name":"Flat"}]}""")
                    path == "/locations/loc1/rooms" -> MockResponse().setBody("""{"items":[{"roomId":"room1","name":"Study"},{"roomId":"room2","name":"Hall"}]}""")
                    path == "/devices?includeStatus=true" -> MockResponse().setBody("""{"items":[$bulb,$shade]}""")
                    path == "/scenes" -> MockResponse().setBody("""{"items":[{"sceneId":"sc1","sceneName":"Evening","locationId":"loc1"}]}""")
                    path == "/devices/7b3c-1/commands" -> MockResponse().setBody("""{"results":[{"id":"x","status":"ACCEPTED"}]}""")
                    path == "/devices/7b3c-1/status" -> MockResponse().setBody("""{"components":{"main":{"switch":{"switch":{"value":"off"}},"switchLevel":{"level":{"value":50}}}}}""")
                    path == "/scenes/sc1/execute" -> MockResponse().setBody("""{"status":"success"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("smartthings", HomeKind.SMARTTHINGS, apiKey = "PAT")
        val backend = SmartThingsBackend("smartthings", account, HomeHttp(OkHttpClient()), scope, pollMs = 200, base = server.url("/").toString().trimEnd('/'))
        try {
            runBlocking {
                withTimeout(10_000) {
                    backend.start()
                    backend.phase.first { it == HaPhase.REST_FALLBACK }
                    val entities = backend.entities.first { it.isNotEmpty() }
                    assertEquals("smartthings", entities.getValue("light.st_7b3c_1").home)
                    assertEquals("Evening", entities.getValue("scene.st_sc1").friendlyName)
                    assertEquals("Flat", backend.instance.value!!.name)
                    assertEquals("Study", backend.registry.value.areaOf("light.st_7b3c_1")!!.name)
                    assertEquals("Hall", backend.registry.value.areas.first { it.areaId == "room2" }.name)

                    backend.callService(HaActions.turnOff("light.st_7b3c_1"))
                    assertEquals("off", backend.entities.first { it["light.st_7b3c_1"]?.state == "off" }.getValue("light.st_7b3c_1").state)
                    backend.callService(HaActions.runScene("scene.st_sc1"))
                }
            }
            val commands = (0 until server.requestCount).map { server.takeRequest() }.filter { it.path == "/devices/7b3c-1/commands" }
            assertEquals(1, commands.size)
            assertTrue(commands.single().body.readUtf8().contains("\"command\":\"off\""))
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `a refused token stops with TOKEN_REJECTED`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("smartthings", HomeKind.SMARTTHINGS, extra = mapOf(HomeKeys.ACCESS_TOKEN to "dead", HomeKeys.EXPIRES_AT to Long.MAX_VALUE.toString()))
        val backend = SmartThingsBackend("smartthings", account, HomeHttp(OkHttpClient()), scope, base = server.url("/").toString().trimEnd('/'))
        try {
            runBlocking { withTimeout(10_000) { backend.start(); backend.phase.first { it == HaPhase.TOKEN_REJECTED } } }
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `the sign-in state and the callback round trip`() {
        val state = SmartThingsAuth.newState("smartthings")
        assertEquals("smartthings", SmartThingsAuth.credentialOf(state))
        val url = SmartThingsAuth.authorizeUrl("cid", state)
        assertTrue(url.startsWith("https://api.smartthings.com/oauth/authorize?client_id=cid&response_type=code&redirect_uri=https%3A%2F%2Fwmkeyboard.pages.dev%2Fst%2F"))
        assertTrue(url.contains("scope=r%3Adevices%3A*+x%3Adevices%3A*"))
        val cb = SmartThingsAuth.parseCallback("wmkeyboard://st-auth?code=abc&state=" + java.net.URLEncoder.encode(state, "UTF-8"))!!
        assertEquals("abc", cb.code)
        assertEquals(state, cb.state)
        assertNull(SmartThingsAuth.parseCallback("wmkeyboard://ha-auth?code=abc&state=x"))
    }
}
