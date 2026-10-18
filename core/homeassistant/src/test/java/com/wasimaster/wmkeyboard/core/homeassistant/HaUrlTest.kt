package com.wasimaster.wmkeyboard.core.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaUrlTest {

    @Test
    fun `bare LAN name is http on 8123`() {
        val u = HaUrl.parse("homeassistant.local")!!
        assertEquals("http://homeassistant.local:8123", u.base)
        assertEquals("ws://homeassistant.local:8123/api/websocket", u.websocket)
        assertEquals("http://homeassistant.local:8123/api/states", u.api("states"))
    }

    @Test
    fun `ip with port keeps port`() {
        assertEquals("http://192.168.1.5:8124", HaUrl.parse("192.168.1.5:8124")!!.base)
        assertEquals("http://192.168.1.5:8123", HaUrl.parse("192.168.1.5")!!.base)
    }

    @Test
    fun `public domain is https on 443`() {
        val u = HaUrl.parse("abc123.ui.nabu.casa")!!
        assertEquals("https://abc123.ui.nabu.casa", u.base)
        assertEquals("wss://abc123.ui.nabu.casa/api/websocket", u.websocket)
        assertTrue(u.secure)
    }

    @Test
    fun `pasted page address is cut to the server`() {
        assertEquals("http://homeassistant.local:8123", HaUrl.parse("http://homeassistant.local:8123/lovelace/0?edit=1")!!.base)
        assertEquals("https://ha.example.com", HaUrl.parse("HTTPS://ha.example.com/")!!.base)
    }

    @Test
    fun `explicit https with custom port`() {
        assertEquals("https://10.0.0.2:8123", HaUrl.parse("https://10.0.0.2:8123")!!.base)
    }

    @Test
    fun `ipv6 literal`() {
        val u = HaUrl.parse("http://[fe80::1]:8123")!!
        assertEquals("http://[fe80::1]:8123", u.base)
    }

    @Test
    fun `rubbish is null`() {
        assertNull(HaUrl.parse(""))
        assertNull(HaUrl.parse("ftp://x"))
        assertNull(HaUrl.parse("user@host"))
        assertNull(HaUrl.parse("host:99999"))
        assertNull(HaUrl.parse("ho st"))
    }

    @Test
    fun `same server ignores how it was typed`() {
        assertTrue(HaUrl.parse("http://HA.local:8123")!!.sameServer(HaUrl.parse("ha.local")))
    }
}
