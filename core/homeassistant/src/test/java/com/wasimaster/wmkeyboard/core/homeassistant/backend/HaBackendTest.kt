package com.wasimaster.wmkeyboard.core.homeassistant.backend

import com.wasimaster.wmkeyboard.core.homeassistant.FakeHa
import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaTokens
import com.wasimaster.wmkeyboard.core.homeassistant.JvmHaHttp
import com.wasimaster.wmkeyboard.core.homeassistant.OkHttpTransport
import com.wasimaster.wmkeyboard.core.homeassistant.PhaseLog
import com.wasimaster.wmkeyboard.core.homeassistant.withinTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home Assistant as a backend: the socket, the registry and instance after auth, the home stamp, and credential changes. */
class HaBackendTest {

    private fun account(url: String, key: String = "h1") = HomeAccount(
        key = key,
        kind = HomeKind.HOME_ASSISTANT,
        url = url,
        extra = mapOf(HomeKeys.ACCESS_TOKEN to "GOOD", HomeKeys.EXPIRES_AT to Long.MAX_VALUE.toString()),
    )

    @Test
    fun `connects, stamps the home, fetches the registry and runs a call`() {
        val server = MockWebServer()
        val fake = FakeHa()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = HaBackend("h1", account(server.url("/").toString()), OkHttpTransport(OkHttpClient()), JvmHaHttp(), scope)
        val log = PhaseLog(scope, backend.phase)
        try {
            withinTimeout(log) {
                backend.start()
                backend.phase.first { it == HaPhase.LIVE }
                val entities = backend.entities.first { it.size == 2 }
                assertEquals("h1", entities["light.kitchen"]!!.home)
                assertEquals("h1|light.kitchen", HomeRef.of(entities["light.kitchen"]!!))
                backend.registry.first { it.areas.isNotEmpty() }
                assertEquals("Kitchen", backend.registry.value.areaOf("light.kitchen")!!.name)
                backend.callService(HaActions.turnOn("light.kitchen"))
                val after = backend.entities.first { it["light.kitchen"]?.state == "on" }
                assertEquals("h1", after["light.kitchen"]!!.home)
                assertTrue(backend.isLive)
                assertEquals(server.url("/").toString().trimEnd('/') + "/history?entity_id=light.kitchen", backend.webUrl("light.kitchen"))
            }
            // The same credential again keeps the connection; a different token restarts and clears.
            assertFalse(backend.update(account(server.url("/").toString())))
            assertTrue(backend.entities.value.isNotEmpty())
            assertTrue(backend.update(account(server.url("/").toString()).copy(extra = mapOf(HomeKeys.ACCESS_TOKEN to "OTHER"))))
            assertTrue(backend.entities.value.isEmpty())
        } finally {
            backend.stop()
            scope.cancel()
            runCatching { fake.socket?.close(1000, "") }
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `nothing signed in never starts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = HaBackend("h1", HomeAccount("h1", HomeKind.HOME_ASSISTANT, url = "127.0.0.1:1"), OkHttpTransport(OkHttpClient()), JvmHaHttp(), scope)
        backend.start()
        assertEquals(HaPhase.OFFLINE, backend.phase.value)
        assertFalse(backend.isLive)
        scope.cancel()
    }

    @Test
    fun `the credential reads across`() {
        val a = HomeAccount(
            "h", HomeKind.HOME_ASSISTANT, url = "homeassistant.local:8123",
            extra = mapOf(HomeKeys.EXTERNAL_URL to "https://x.ui.nabu.casa", HomeKeys.PREFER_INTERNAL to "false", HomeKeys.ACCESS_TOKEN to "t", HomeKeys.REFRESH_TOKEN to "r", HomeKeys.EXPIRES_AT to "5"),
        )
        assertEquals(listOf("https://x.ui.nabu.casa", "http://homeassistant.local:8123"), HaConnection.candidates(a).map { it.base })
        assertEquals(HaTokens("t", "r", 5L), HaConnection.tokens(a))
        assertTrue(a.ready)
        val pasted = a.copy(apiKey = "LONG", extra = emptyMap())
        assertTrue(HaConnection.tokens(pasted)!!.longLived)
        assertNull(HaConnection.tokens(a.copy(extra = HaConnection.withoutTokens(a.extra))))
        assertFalse(a.copy(extra = HaConnection.withoutTokens(a.extra)).signedIn)
        val fresh = HaConnection.withTokens(a.extra + (HomeKeys.AUTH_STATE to "x"), HaTokens("t2", "r2", 9L))
        assertEquals("t2", fresh[HomeKeys.ACCESS_TOKEN])
        assertNull(fresh[HomeKeys.AUTH_STATE])
        assertEquals("https://x.ui.nabu.casa", fresh[HomeKeys.EXTERNAL_URL])
    }

    @Test
    fun `refs split and join`() {
        assertEquals("light.k", HomeRef.of("", "light.k"))
        assertEquals("h|light.k", HomeRef.of("h", "light.k"))
        assertEquals("h", HomeRef.home("h|light.k"))
        assertEquals("", HomeRef.home("light.k"))
        assertEquals("light.k", HomeRef.native("h|light.k"))
        assertEquals("light.k", HomeRef.native("light.k"))
        assertEquals("home_assistant:ab12", HomeRef.home("home_assistant:ab12|sensor.t"))
    }
}
