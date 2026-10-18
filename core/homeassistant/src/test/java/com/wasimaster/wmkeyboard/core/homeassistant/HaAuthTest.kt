package com.wasimaster.wmkeyboard.core.homeassistant

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAuthTest {

    @Test
    fun `authorize url names client redirect and state`() {
        val base = HaUrl.parse("homeassistant.local")!!
        val state = HaAuth.newState(base, "home_assistant:ab12")
        val url = HaAuth.authorizeUrl(base, state)
        assertTrue(url.startsWith("http://homeassistant.local:8123/auth/authorize?client_id=https%3A%2F%2Fwmkeyboard.pages.dev%2Fha%2F"))
        assertTrue(url.contains("&redirect_uri=wmkeyboard%3A%2F%2Fha-auth"))
        assertTrue(url.contains("&state="))
        assertEquals(base, HaAuth.baseOf(state))
        assertEquals("home_assistant:ab12", HaAuth.credentialOf(state))
        assertEquals(32, HaAuth.nonceOf(state).length)
    }

    @Test
    fun `callback parses and rejects other links`() {
        val cb = HaAuth.parseCallback("wmkeyboard://ha-auth?code=abc123&state=deadbeef%7Chome_assistant%7Chttp%3A%2F%2Fha.local%3A8123")!!
        assertEquals("abc123", cb.code)
        assertEquals("deadbeef|home_assistant|http://ha.local:8123", cb.state)
        assertEquals("home_assistant", HaAuth.credentialOf(cb.state))
        assertEquals("http://ha.local:8123", HaAuth.baseOf(cb.state)!!.base)
        assertNull(HaAuth.parseCallback("wmkeyboard://settings?route=x"))
        assertNull(HaAuth.parseCallback("wmkeyboard://ha-auth?state=only"))
        assertNull(HaAuth.parseCallback(null))
    }

    @Test
    fun `exchange and refresh against a server`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"access_token":"AT1","expires_in":1800,"refresh_token":"RT","token_type":"Bearer"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"AT2","expires_in":1800,"token_type":"Bearer"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.start()
        try {
            val base = HaUrl.parse(server.url("/").toString())!!
            val http = JvmHaHttp()
            val t1 = HaAuth.exchange(http, base, "CODE", nowMs = 1_000L)
            assertEquals("AT1", t1.accessToken)
            assertEquals("RT", t1.refreshToken)
            assertEquals(1_000L + 1800_000L, t1.expiresAtMs)
            assertFalse(t1.longLived)
            assertFalse(t1.expiresWithin(1_000L))
            assertTrue(t1.expiresWithin(1_000L + 1800_000L - 30_000L))
            val req1 = server.takeRequest()
            assertEquals("/auth/token", req1.path)
            val body1 = req1.body.readUtf8()
            assertTrue(body1.contains("grant_type=authorization_code"))
            assertTrue(body1.contains("code=CODE"))
            assertTrue(body1.contains("client_id=https%3A%2F%2Fwmkeyboard.pages.dev%2Fha%2F"))

            val t2 = HaAuth.refresh(http, base, t1, nowMs = 5_000L)
            assertEquals("AT2", t2.accessToken)
            assertEquals("RT", t2.refreshToken)
            assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token&refresh_token=RT"))

            val failed = runCatching { HaAuth.refresh(http, base, t1) }.exceptionOrNull()
            assertTrue(failed is HaHttpException)
            assertEquals(400, (failed as HaHttpException).status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `long lived token never refreshes`() {
        val t = HaTokens.longLived("  LL  ")
        assertEquals("LL", t.accessToken)
        assertFalse(t.expiresWithin(Long.MAX_VALUE - 1))
        assertFalse(t.canRefresh)
    }

    @Test
    fun `rest ping and token rejection`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"message":"API running."}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("401: Unauthorized"))
        server.start()
        try {
            val base = HaUrl.parse(server.url("/").toString())!!
            val rest = HaRest(JvmHaHttp(), { base }, { "TOKEN" })
            assertEquals("API running.", rest.ping())
            assertEquals("Bearer TOKEN", server.takeRequest().getHeader("Authorization"))
            val err = runCatching { rest.ping() }.exceptionOrNull() as HaHttpException
            assertTrue(err.tokenRejected)
        } finally {
            server.shutdown()
        }
    }
}
