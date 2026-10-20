package com.wasimaster.wmkeyboard.core.send

import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * One real round trip against a public instance, skipped unless asked for:
 *
 *     ./gradlew :core:send:testDebugUnitTest -Psend.interop=1 [-Psend.interop.url=https://…]
 *
 * It uploads a kilobyte with a one-download limit and a password, reads the
 * envelope back, downloads and decrypts it, and deletes what is left. The
 * default instance is the maintainer's; be sparing with it.
 */
class SendInteropTest {

    @Test
    fun `upload, read back, download, delete`() {
        assumeTrue(System.getProperty("send.interop") == "1")
        val base = System.getProperty("send.interop.url").orEmpty().ifBlank { SendInstances.DEFAULT_URL }
        val client = SendClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build())
        val config = client.config(base)
        println("config: $config")

        val content = ByteArray(1024) { (it % 7).toByte() }
        val upload = client.upload(
            base,
            listOf(SendFile("wmk-interop.bin", content.size.toLong()) { content.inputStream() }),
            SendUploadOptions(timeLimitSeconds = config.expireChoices.first(), downloadLimit = 1, password = "wmk"),
        )
        println("uploaded: ${upload.url}")
        val link = upload.link!!
        try {
            val exists = client.exists(link)
            assertEquals(true, exists.requiresPassword)
            val session = client.open(link, password = "wmk", nonce = exists.nonce)
            val remote = session.metadata()
            assertEquals("wmk-interop.bin", remote.metadata.name)
            assertEquals(content.size.toLong(), remote.metadata.size)
            val out = ByteArrayOutputStream()
            session.download(remote.metadata, object : SendSink {
                override fun create(name: String, size: Long, type: String) = object : SendSinkFile {
                    override val stream: OutputStream = out
                    override fun finish(success: Boolean): String = "memory"
                }
            })
            assertArrayEquals(content, out.toByteArray())
        } finally {
            runCatching { client.delete(upload.instance, upload.id, upload.ownerToken) }
        }
    }
}
