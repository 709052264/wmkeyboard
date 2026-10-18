package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The wire, against a mock server that behaves as `timvisee/send` does: the
 * WebSocket upload handshake and end marker, the nonce dance on the
 * authenticated routes, and the archive split on the way down.
 */
class SendClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SendClient
    private lateinit var base: String

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
        client = SendClient(OkHttpClient())
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private val keys = SendKeys.fromB64("yqdlZ-tYemfogSmv7Ws5PQ")!!

    /** A fake Send upload endpoint: answers the hello, swallows the bytes, says ok after the end marker. */
    private inner class UploadEndpoint(private val id: String = "0123456789ab", private val error: Int? = null) : WebSocketListener() {
        var hello: String? = null
        val received = ByteArrayOutputStream()
        val done = CountDownLatch(1)

        override fun onMessage(webSocket: WebSocket, text: String) {
            hello = text
            if (error != null) {
                webSocket.send("""{"error":$error}""")
                webSocket.close(1000, null)
            } else {
                webSocket.send("""{"url":"$base/download/$id/","ownerToken":"owner-token","id":"$id"}""")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size == 1 && bytes[0] == 0.toByte()) {
                webSocket.send("""{"ok":true}""")
                webSocket.close(1000, null)
                done.countDown()
            } else {
                received.write(bytes.toByteArray())
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            done.countDown()
        }
    }

    @Test
    fun `an upload encrypts, streams, finishes and sets the password`() {
        val endpoint = UploadEndpoint()
        server.enqueue(MockResponse().withWebSocketUpgrade(endpoint))
        server.enqueue(MockResponse().setResponseCode(200))

        val a = "hello ".toByteArray()
        val b = ByteArray(70_000) { (it % 13).toByte() }
        val files = listOf(
            SendFile("a.txt", a.size.toLong(), "text/plain") { a.inputStream() },
            SendFile("b.bin", b.size.toLong()) { b.inputStream() },
        )
        var lastProgress = 0L to 0L
        val upload = client.upload(
            base, files, SendUploadOptions(timeLimitSeconds = 3600, downloadLimit = 3, password = "pw"),
            keys = keys, nowMs = 1_000,
            progress = { sent, total -> lastProgress = sent to total },
        )

        assertTrue(endpoint.done.await(5, TimeUnit.SECONDS))
        val hello = Json.parseToJsonElement(endpoint.hello!!).jsonObject
        assertEquals("send-v1 ${SendKeys(keys.secret).authKeyB64}", hello.string("authorization"))
        assertEquals(3600L, hello.long("timeLimit"))
        assertEquals(3, hello.int("dlimit"))
        val metadata = SendKeys(keys.secret).decryptMetadata(hello.string("fileMetadata")!!)
        assertTrue(metadata.isArchive)
        assertEquals(listOf("a.txt", "b.bin"), metadata.files.map { it.name })

        val plain = ByteArrayOutputStream()
        Ece.decrypt(endpoint.received.toByteArray().inputStream(), keys.secret, plain)
        assertArrayEquals(a + b, plain.toByteArray())
        assertEquals(Ece.encryptedSize((a.size + b.size).toLong()), lastProgress.first)
        assertEquals(lastProgress.first, lastProgress.second)

        assertEquals("0123456789ab", upload.id)
        assertEquals("$base/download/0123456789ab/#${keys.secretB64}", upload.url)
        assertEquals("owner-token", upload.ownerToken)
        assertEquals(1_000 + 3600 * 1000L, upload.expiresAt)
        assertTrue(upload.password)

        server.takeRequest() // the socket upgrade
        val password = server.takeRequest()
        assertEquals("/api/password/0123456789ab", password.path)
        val body = Json.parseToJsonElement(password.body.readUtf8()).jsonObject
        assertEquals("owner-token", body.string("owner_token"))
        val expected = SendKeys(keys.secret).apply { setPassword("pw", upload.url) }.authKeyB64
        assertEquals(expected, body.string("auth"))
    }

    @Test
    fun `the server's error code names the failure`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(UploadEndpoint(error = 413)))
        val failure = assertThrows(SendException::class.java) {
            client.upload(base, listOf(SendFile("a", 1) { byteArrayOf(1).inputStream() }), SendUploadOptions(), keys = keys)
        }
        assertEquals(SendError.TOO_BIG, failure.error)
        assertEquals(413, failure.status)
    }

    @Test
    fun `receiving signs each nonce, retries a stale one once, and splits an archive`() {
        val files = listOf(SendFileEntry("one.txt", 5, "text/plain"), SendFileEntry("two.bin", 7), SendFileEntry("empty", 0))
        val metadata = SendMetadata.forFiles(files)
        val content = "12345".toByteArray() + ByteArray(7) { 9 }
        val cipher = Ece.encrypt(content, keys.secret)
        val link = SendLink(base, "0123456789ab", keys.secret)
        val nonce1 = Base64Url.encode(ByteArray(16) { 1 })
        val nonce2 = Base64Url.encode(ByteArray(16) { 2 })
        val nonce3 = Base64Url.encode(ByteArray(16) { 3 })

        server.enqueue(MockResponse().setBody("""{"requiresPassword":false}""").addHeader("WWW-Authenticate", "send-v1 $nonce1"))
        // A stale nonce: the server answers 401 with the one it wants signed.
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "send-v1 $nonce2"))
        server.enqueue(
            MockResponse()
                .setBody("""{"metadata":"${keys.encryptMetadata(metadata)}","finalDownload":true,"ttl":123456}""")
                .addHeader("WWW-Authenticate", "send-v1 $nonce3"),
        )
        server.enqueue(MockResponse().setBody(okio.Buffer().write(cipher)).addHeader("Content-Length", cipher.size.toString()))

        val session = client.open(link)
        val remote = session.metadata()
        assertEquals(metadata, remote.metadata)
        assertTrue(remote.finalDownload)
        assertEquals(123456L, remote.ttlMs)
        assertFalse(remote.requiresPassword)

        val sink = MemorySink()
        val progress = mutableListOf<Long>()
        val locations = session.download(remote.metadata, sink, progress = { done, _ -> progress += done })
        assertEquals(listOf("mem:one.txt", "mem:two.bin", "mem:empty"), locations)
        assertArrayEquals("12345".toByteArray(), sink.files["one.txt"]!!.toByteArray())
        assertArrayEquals(ByteArray(7) { 9 }, sink.files["two.bin"]!!.toByteArray())
        assertEquals(0, sink.files["empty"]!!.size())
        assertEquals(content.size.toLong(), progress.last())

        assertEquals("/api/exists/0123456789ab", server.takeRequest().path)
        val stale = server.takeRequest()
        assertEquals(keys.authHeader(nonce1), stale.getHeader("Authorization"))
        val retried = server.takeRequest()
        assertEquals("/api/metadata/0123456789ab", retried.path)
        assertEquals(keys.authHeader(nonce2), retried.getHeader("Authorization"))
        val download = server.takeRequest()
        assertEquals("/api/download/0123456789ab", download.path)
        assertEquals(keys.authHeader(nonce3), download.getHeader("Authorization"))
    }

    @Test
    fun `a wrong password is a 401 that does not rotate the nonce`() {
        val link = SendLink(base, "0123456789ab", keys.secret)
        val nonce = Base64Url.encode(ByteArray(16) { 4 })
        server.enqueue(MockResponse().setBody("""{"requiresPassword":true}""").addHeader("WWW-Authenticate", "send-v1 $nonce"))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "send-v1 $nonce"))
        val session = client.open(link, password = "nope")
        val failure = assertThrows(SendException::class.java) { session.metadata() }
        assertEquals(SendError.WRONG_PASSWORD, failure.error)
    }

    @Test
    fun `a gone file is not found and the owner calls carry the token`() {
        val link = SendLink(base, "0123456789ab", keys.secret)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(SendError.NOT_FOUND, assertThrows(SendException::class.java) { client.exists(link) }.error)

        server.enqueue(MockResponse().setBody("""{"dlimit":3,"dtotal":1,"ttl":5000}"""))
        val info = client.info(base, "0123456789ab", "owner-token")
        assertEquals(SendInfo(3, 1, 5000), info)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/info/0123456789ab", request.path)
        assertEquals("owner-token", Json.parseToJsonElement(request.body.readUtf8()).jsonObject.string("owner_token"))

        server.enqueue(MockResponse().setResponseCode(200))
        client.delete(base, "0123456789ab", "owner-token")
        assertEquals("/api/delete/0123456789ab", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(SendError.NOT_FOUND, assertThrows(SendException::class.java) { client.delete(base, "0123456789ab", "x") }.error)
    }

    @Test
    fun `config is read and an instance without one gets the defaults`() {
        server.enqueue(MockResponse().setBody("""{"LIMITS":{"MAX_FILE_SIZE":1000}}"""))
        assertEquals(1000L, client.config(base).maxFileSize)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(SendServerConfig.DEFAULT, client.config(base))
    }

    private class MemorySink : SendSink {
        val files = LinkedHashMap<String, ByteArrayOutputStream>()

        override fun create(name: String, size: Long, type: String): SendSinkFile {
            val out = ByteArrayOutputStream()
            files[name] = out
            return object : SendSinkFile {
                override val stream: OutputStream = out
                override fun finish(success: Boolean): String? = if (success) "mem:$name" else null.also { files.remove(name) }
            }
        }
    }
}
