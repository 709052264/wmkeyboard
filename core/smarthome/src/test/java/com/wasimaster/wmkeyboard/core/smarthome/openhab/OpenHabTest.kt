package com.wasimaster.wmkeyboard.core.smarthome.openhab

import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenHabTest {

    private val items = """
        [
          {"name":"gGround","label":"Ground floor","type":"Group","state":"NULL","tags":["GroundFloor"],"groupNames":[]},
          {"name":"gKitchen","label":"Kitchen","type":"Group","state":"NULL","tags":["Kitchen"],"groupNames":["gGround"]},
          {"name":"gLamp","label":"Lamp","type":"Group","state":"NULL","tags":["Lightbulb"],"groupNames":["gKitchen"]},
          {"name":"Lamp_Dim","label":"Kitchen lamp","type":"Dimmer","state":"60","tags":["Switch","Light"],"groupNames":["gLamp"],"category":"light"},
          {"name":"Strip_Color","label":"LED strip","type":"Color","state":"120,100,50","tags":["Light"],"groupNames":["gKitchen"]},
          {"name":"Plug","label":"Coffee plug","type":"Switch","state":"OFF","tags":["Switch","Power"],"groupNames":["gKitchen"]},
          {"name":"Blind","label":"Kitchen blind","type":"Rollershutter","state":"30","tags":["Blinds"],"groupNames":["gKitchen"]},
          {"name":"Door","label":"Back door","type":"Contact","state":"OPEN","tags":["OpenState","Door"],"groupNames":["gKitchen"]},
          {"name":"Temp","label":"Kitchen temperature","type":"Number:Temperature","state":"21.5 °C","tags":["Measurement","Temperature"],"groupNames":["gKitchen"],"stateDescription":{"pattern":"%.1f %unit%","readOnly":true}},
          {"name":"Setpoint","label":"Target","type":"Number","state":"20","tags":["Setpoint","Temperature"],"groupNames":["gKitchen"],"stateDescription":{"pattern":"%.1f °C","readOnly":false,"minimum":5,"maximum":30,"step":0.5}},
          {"name":"Mode","label":"Mode","type":"String","state":"eco","tags":["Control"],"groupNames":[],"stateDescription":{"readOnly":false,"options":[{"value":"eco","label":"Eco"},{"value":"comfort","label":"Comfort"}]}},
          {"name":"Player","label":"Radio","type":"Player","state":"PLAY","tags":[],"groupNames":[]},
          {"name":"FrontLock","label":"Front lock","type":"Switch","state":"ON","tags":["Lock"],"groupNames":[]}
        ]
    """.trimIndent()

    @Test
    fun `items map by type and tag, rooms come from the semantic model`() {
        val list = OpenHabMapping.parseItems(items)
        val entities = OpenHabMapping.entities(list, 1.0)
        val lamp = entities.getValue("light.oh_lamp_dim")
        assertEquals("on", lamp.state)
        assertEquals(60, lamp.brightnessPct)
        val strip = entities.getValue("light.oh_strip_color")
        assertEquals(120.0 to 100.0, strip.hsColor)
        assertEquals(50, strip.brightnessPct)
        assertEquals("off", entities.getValue("switch.oh_plug").state)
        val blind = entities.getValue("cover.oh_blind")
        assertEquals("open", blind.state)
        assertEquals(70, blind.currentPosition)
        assertEquals("on", entities.getValue("binary_sensor.oh_door").state)
        assertEquals("opening", entities.getValue("binary_sensor.oh_door").deviceClass)
        val temp = entities.getValue("sensor.oh_temp")
        assertEquals("21.5", temp.state)
        assertEquals("°C", temp.unit)
        assertEquals("temperature", temp.deviceClass)
        val setpoint = entities.getValue("number.oh_setpoint")
        assertEquals("20", setpoint.state)
        assertEquals(0.5, setpoint.step!!, 0.0)
        assertEquals("°C", setpoint.unit)
        assertEquals(listOf("eco", "comfort"), entities.getValue("select.oh_mode").options)
        assertEquals("playing", entities.getValue("media_player.oh_player").state)
        assertEquals("locked", entities.getValue("lock.oh_frontlock").state)
        assertNull(entities["switch.oh_gkitchen"])

        val reg = OpenHabMapping.registry(list)
        assertEquals("Kitchen", reg.areaOf("light.oh_lamp_dim")!!.name)
        assertEquals("Kitchen", reg.areaOf("cover.oh_blind")!!.name)
        assertNull(reg.areaOf("media_player.oh_player"))
        assertEquals("Ground floor", reg.floors.single().name)
        assertEquals("gGround", reg.areas.single().floorId)
    }

    @Test
    fun `service calls become item commands`() {
        val list = OpenHabMapping.parseItems(items)
        val entities = OpenHabMapping.entities(list, 1.0)
        fun cmd(id: String, call: com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall) =
            OpenHabMapping.command(call, OpenHabMapping.itemOf(list, id)!!, entities[id])
        assertEquals("ON", cmd("light.oh_lamp_dim", HaActions.turnOn("light.oh_lamp_dim")))
        assertEquals("35", cmd("light.oh_lamp_dim", HaActions.setBrightnessPct("light.oh_lamp_dim", 35)))
        assertEquals("0,100,50", cmd("light.oh_strip_color", HaActions.setHsColor("light.oh_strip_color", 0.0, 100.0)))
        assertEquals("ON", cmd("switch.oh_plug", HaActions.toggle("switch.oh_plug")))
        assertEquals("UP", cmd("cover.oh_blind", HaActions.openCover("cover.oh_blind")))
        assertEquals("80", cmd("cover.oh_blind", HaActions.setCoverPosition("cover.oh_blind", 20)))
        assertEquals("22.5", cmd("number.oh_setpoint", HaActions.setValue("number.oh_setpoint", 22.5)))
        assertEquals("comfort", cmd("select.oh_mode", HaActions.selectOption("select.oh_mode", "comfort")))
        assertEquals("PAUSE", cmd("media_player.oh_player", HaActions.mediaPlayPause("media_player.oh_player")))
        assertEquals("OFF", cmd("lock.oh_frontlock", HaActions.unlock("lock.oh_frontlock")))
        assertNull(cmd("switch.oh_plug", HaActions.openCover("switch.oh_plug")))
        assertNull(cmd("cover.oh_blind", HaActions.setValue("cover.oh_blind", 1.0)))
        assertEquals("Lamp_Dim" to "0", OpenHabMapping.stateOfEvent("openhab/items/Lamp_Dim/statechanged", """{"type":"Percent","value":"0","oldType":"Percent","oldValue":"60"}"""))
    }

    @Test
    fun `the backend lists, listens, and posts a command`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer TOK") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/rest/items?") -> MockResponse().setBody(items)
                    path == "/rest/" -> MockResponse().setBody("""{"version":"5","runtimeInfo":{"version":"5.1.0"}}""")
                    path.startsWith("/rest/events") -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("event: message\ndata: {\"topic\":\"openhab/items/Lamp_Dim/statechanged\",\"payload\":\"{\\\"type\\\":\\\"Percent\\\",\\\"value\\\":\\\"0\\\"}\",\"type\":\"ItemStateChangedEvent\"}\n\n")
                        .setBodyDelay(200, TimeUnit.MILLISECONDS)
                    path == "/rest/items/Lamp_Dim" && request.method == "POST" -> MockResponse().setResponseCode(200)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val account = HomeAccount("openhab:01", HomeKind.OPENHAB, url = server.url("/").toString().trimEnd('/'), apiKey = "TOK")
        val backend = OpenHabBackend("openhab:01", account, HomeHttp(OkHttpClient()), scope)
        try {
            runBlocking {
                withTimeout(10_000) {
                    backend.start()
                    backend.phase.first { it == HaPhase.LIVE }
                    val entities = backend.entities.first { it.isNotEmpty() }
                    assertEquals("openhab:01", entities.getValue("light.oh_lamp_dim").home)
                    assertEquals("5", backend.instance.value!!.version)
                    backend.entities.first { it["light.oh_lamp_dim"]?.state == "off" }
                    backend.callService(HaActions.setBrightnessPct("light.oh_lamp_dim", 80))
                }
            }
            val posts = (0 until server.requestCount).map { server.takeRequest() }.filter { it.method == "POST" }
            assertEquals("/rest/items/Lamp_Dim", posts.first().path)
            assertEquals("80", posts.first().body.readUtf8())
            assertTrue(posts.first().getHeader("Content-Type")!!.startsWith("text/plain"))
        } finally {
            backend.stop()
            scope.cancel()
            server.shutdown()
        }
    }
}
