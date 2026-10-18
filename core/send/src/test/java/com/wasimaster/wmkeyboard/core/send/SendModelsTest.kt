package com.wasimaster.wmkeyboard.core.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SendModelsTest {

    private val key = "yqdlZ-tYemfogSmv7Ws5PQ"

    @Test
    fun `a share link parses in its canonical and sloppy forms`() {
        val canonical = SendLink.parse("https://send.vis.ee/download/0123456789ab/#$key")!!
        assertEquals("https://send.vis.ee", canonical.base)
        assertEquals("0123456789ab", canonical.id)
        assertEquals("https://send.vis.ee/download/0123456789ab/#$key", canonical.url)

        assertEquals(canonical, SendLink.parse("https://send.vis.ee/download/0123456789AB#$key"))
        assertEquals(canonical, SendLink.parse("  https://send.vis.ee/download/0123456789ab/#$key \n"))
        assertEquals("http://x.org:8080/send", SendLink.parse("http://x.org:8080/send/download/0123456789abcdef/#$key")!!.base)
    }

    @Test
    fun `what is not a share link`() {
        assertNull(SendLink.parse("https://send.vis.ee/download/0123456789ab/"))
        assertNull(SendLink.parse("https://send.vis.ee/download/012345678/#$key"))
        assertNull(SendLink.parse("https://send.vis.ee/download/0123456789ab/#${key}AA"))
        assertNull(SendLink.parse("https://send.vis.ee/download/0123456789ab/#short"))
        assertNull(SendLink.parse("https://example.com/files/0123456789ab/#$key"))
        assertNull(SendLink.parse("ftp://send.vis.ee/download/0123456789ab/#$key"))
    }

    @Test
    fun `a link is found inside a message`() {
        val found = SendLink.find("here you go: https://send.vis.ee/download/0123456789ab/#$key, open it today")
        assertNotNull(found)
        assertEquals("0123456789ab", found!!.id)
        assertNull(SendLink.find("nothing here"))
    }

    @Test
    fun `server config reads the limits and falls back per field`() {
        val config = SendServerConfig.fromJson(
            """{"LIMITS":{"MAX_FILE_SIZE":1073741824,"MAX_DOWNLOADS":10,"MAX_EXPIRE_SECONDS":259200,"MAX_FILES_PER_ARCHIVE":64},
               "DEFAULTS":{"DOWNLOADS":1,"DOWNLOAD_COUNTS":[1,2,3,4,5,20,50,100],"EXPIRE_TIMES_SECONDS":[300,3600,86400,604800],"EXPIRE_SECONDS":86400},
               "WEB_UI":{"CUSTOM_FOOTER_TEXT":"hi"}}""",
        )
        assertEquals(1073741824L, config.maxFileSize)
        assertEquals(listOf(1, 2, 3, 4, 5), config.downloadChoices)
        assertEquals(listOf(300L, 3600L, 86400L), config.expireChoices)
        assertEquals("hi", config.footer)
        assertEquals(SendServerConfig.DEFAULT.maxDownloads, SendServerConfig.fromJson("{}").maxDownloads)
    }

    @Test
    fun `instance addresses normalise to a base URL`() {
        assertEquals("https://send.vis.ee", SendInstances.normaliseBase("send.vis.ee"))
        assertEquals("https://send.vis.ee", SendInstances.normaliseBase("https://send.vis.ee/"))
        assertEquals("https://send.vis.ee", SendInstances.normaliseBase("https://Send.vis.ee/download/0123456789ab/#$key"))
        assertEquals("http://192.168.1.5:1443/send", SendInstances.normaliseBase("http://192.168.1.5:1443/send/"))
        assertNull(SendInstances.normaliseBase(""))
        assertNull(SendInstances.normaliseBase("just words"))
        assertEquals("send.vis.ee", SendInstances.displayHost("https://send.vis.ee/"))
        assertTrue(SendInstances.catalogue.first().reference)
        assertEquals(SendInstances.DEFAULT_URL, SendInstances.catalogue.first().url)
        assertTrue(SendInstances.roomFor(9L * 1024 * 1024 * 1024).all { it.maxBytes >= 9L * 1024 * 1024 * 1024 })
    }

    @Test
    fun `a direct code round trips in both forms`() {
        val code = SendDirectCode(
            ssid = "DIRECT-ab WM Keyboard",
            passphrase = "p&ss w=rd",
            host = "192.168.49.1",
            port = 1716,
            deviceId = "abc_123",
            fingerprint = "AA:BB",
            token = "0123456789abcdef0123",
            name = "Pixel 8 - WM Keyboard",
            band = "5",
        )
        assertEquals(code, SendDirectCode.parse(code.encode()))
        assertEquals(code, SendDirectCode.parse(code.encodeWeb("https://wmkeyboard.app/")))
        assertNull(SendDirectCode.parse("wmkeyboard://pair?h=1&p=2&i=3&t=0123456789abcdef"))
        assertNull(SendDirectCode.parse(code.encode().replace("&t=0123456789abcdef0123", "&t=short")))
    }

    @Test
    fun `the store keeps uploads until they expire`() {
        val dir = File.createTempFile("send", "store").apply { delete(); mkdirs() }
        try {
            val store = SendStore(dir)
            val fresh = upload("a", createdAt = 1_000, expiresAt = 10_000)
            val stale = upload("b", createdAt = 500, expiresAt = 2_000)
            store.add(fresh)
            store.add(stale)
            assertEquals(listOf(fresh, stale), store.uploads(nowMs = 1_500))
            assertEquals(listOf(fresh), store.uploads(nowMs = 5_000))
            store.remove(fresh)
            assertEquals(emptyList<SendUpload>(), store.uploads(nowMs = 5_000))

            store.addReceived(SendReceived("x", 1, OCTET_STREAM, "content://x", "send.vis.ee", 3))
            store.addReceived(SendReceived("x again", 1, OCTET_STREAM, "content://x", "send.vis.ee", 4))
            assertEquals(1, store.received().size)
            assertEquals("x again", store.received().single().name)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun upload(id: String, createdAt: Long, expiresAt: Long) = SendUpload(
        id = id, url = "https://send.vis.ee/download/$id/#$key", ownerToken = "t", instance = "https://send.vis.ee",
        name = "f", size = 1, type = OCTET_STREAM, files = emptyList(), createdAt = createdAt, expiresAt = expiresAt,
        downloadLimit = 1, password = false,
    )
}
