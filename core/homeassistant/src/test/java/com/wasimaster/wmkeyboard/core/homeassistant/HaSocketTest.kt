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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The socket against a scripted server: a tiny Home Assistant that speaks the auth phase and a few commands. */
class HaSocketTest {

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
            // The server's side of the socket is still open; closing it first
            // keeps MockWebServer's shutdown from waiting out its five seconds.
            runCatching { fake.socket?.close(1000, "") }
            runCatching { server.shutdown() }
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
            events = object : HaSocket.Events {
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
            runCatching { server.shutdown() }
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
