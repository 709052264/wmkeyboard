package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The socket against a scripted server: a tiny Home Assistant that speaks the auth phase and a few commands. */
class HaSocketTest {

    /** The server side of the conversation. */
    private class FakeHa(private val token: String = "GOOD") : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.1"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val o = HaJson.parseToJsonElement(text).jsonObject
            val id = o.int("id")
            when (o.str("type")) {
                "auth" -> if (o.str("access_token") == token) {
                    webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.1"}""")
                } else {
                    webSocket.send("""{"type":"auth_invalid","message":"Invalid access token"}""")
                    webSocket.close(1000, "")
                }
                "subscribe_entities" -> {
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    webSocket.send(
                        """{"id":$id,"type":"event","event":{"a":{
                            "light.kitchen":{"s":"off","a":{"friendly_name":"Kitchen","brightness":null},"lc":1.0},
                            "sensor.temp":{"s":"21.5","a":{"unit_of_measurement":"°C"},"lc":1.0}}}}""",
                    )
                }
                "call_service" -> {
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":{"context":{"id":"ctx"}}}""")
                    // The house reacts: the light comes on at half brightness.
                    webSocket.send("""{"id":${subscription()},"type":"event","event":{"c":{"light.kitchen":{"+":{"s":"on","lc":2.0,"a":{"brightness":128}}}}}}""")
                }
                "config/area_registry/list" -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"area_id":"k","name":"Kitchen"}]}""")
                "config/floor_registry/list", "config/device_registry/list" ->
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":[]}""")
                "config/entity_registry/list" ->
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"entity_id":"light.kitchen","area_id":"k"}]}""")
                "ping" -> webSocket.send("""{"id":$id,"type":"pong"}""")
                "bad" -> webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"nope"}}""")
                else -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
            }
        }

        private fun subscription(): Int =
            received.map { HaJson.parseToJsonElement(it).jsonObject }.first { it.str("type") == "subscribe_entities" }.int("id")!!
    }

    private fun withServer(fake: FakeHa, block: suspend (HaSocket, FakeHa) -> Unit) {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = HaUrl.parse(server.url("/").toString())!!
        val socket = HaSocket(OkHttpTransport(OkHttpClient()), scope, { base }, { "GOOD" })
        try {
            runBlocking { withTimeout(10_000) { block(socket, fake) } }
        } finally {
            socket.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `authenticates subscribes and applies the stream`() = withServer(FakeHa()) { socket, fake ->
        socket.start()
        socket.phase.first { it == HaPhase.LIVE }
        assertEquals("2026.10.1", socket.haVersion)
        val entities = socket.entities.first { it.size == 2 }
        assertEquals("off", entities["light.kitchen"]!!.state)
        assertEquals("Kitchen", entities["light.kitchen"]!!.friendlyName)
        assertEquals(21.5, entities["sensor.temp"]!!.numeric!!, 0.0)

        socket.callService(HaActions.turnOn("light.kitchen"))
        val after = socket.entities.first { it["light.kitchen"]?.state == "on" }
        assertEquals(50, after["light.kitchen"]!!.brightnessPct)
        assertEquals(2.0, after["light.kitchen"]!!.lastChanged, 0.0)
        val sent = fake.received.map { HaJson.parseToJsonElement(it).jsonObject }.first { it.str("type") == "call_service" }
        assertEquals("light", sent.str("domain"))
        assertEquals("turn_on", sent.str("service"))
        assertEquals("light.kitchen", sent.obj("target")!!.str("entity_id"))

        val reg = socket.registry()
        assertEquals("k", reg.areaIdOf("light.kitchen"))
        assertEquals("Kitchen", reg.areaOf("light.kitchen")!!.name)

        val failure = runCatching { socket.send("bad") }.exceptionOrNull()
        assertTrue(failure is HaCommandException)
        assertEquals("unknown_command", (failure as HaCommandException).code)
    }

    @Test
    fun `a refused token stops and reports`() {
        val server = MockWebServer()
        val fake = FakeHa(token = "OTHER")
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var rejected = false
        val base = HaUrl.parse(server.url("/").toString())!!
        val socket = HaSocket(
            OkHttpTransport(OkHttpClient()), scope, { base }, { "GOOD" },
            object : HaSocket.Events {
                override fun onTokenRejected() {
                    rejected = true
                }
            },
        )
        try {
            runBlocking {
                withTimeout(10_000) {
                    socket.start()
                    socket.phase.first { it == HaPhase.TOKEN_REJECTED }
                }
            }
            assertTrue(rejected)
            assertFalse(socket.isLive)
        } finally {
            socket.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun `nothing listening is unreachable`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = HaSocket(OkHttpTransport(OkHttpClient()), scope, { HaUrl.parse("127.0.0.1:1") }, { "GOOD" })
        try {
            runBlocking {
                withTimeout(10_000) {
                    socket.start()
                    socket.phase.first { it == HaPhase.UNREACHABLE }
                }
            }
            val failed = runCatching { runBlocking { socket.send("ping") } }.exceptionOrNull()
            assertTrue(failed is HaNotConnectedException)
        } finally {
            socket.stop()
            scope.cancel()
        }
    }

    @Test
    fun `compressed removal and unknown change`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = HaSocket(OkHttpTransport(OkHttpClient()), scope, { null }, { null })
        socket.applyCompressed(HaJson.parseToJsonElement("""{"a":{"x.a":{"s":"1","lc":1.0},"x.b":{"s":"2","lc":1.0}}}""").jsonObject)
        assertEquals(2, socket.entities.value.size)
        socket.applyCompressed(HaJson.parseToJsonElement("""{"r":["x.a"],"c":{"x.c":{"+":{"s":"3","lc":1.0}}}}""").jsonObject)
        assertEquals(setOf("x.b", "x.c"), socket.entities.value.keys)
        scope.cancel()
    }
}
