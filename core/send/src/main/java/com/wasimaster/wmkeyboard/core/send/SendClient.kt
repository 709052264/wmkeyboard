package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Send API, blocking: call it off the main thread. Every method throws
 * [SendException]; plain I/O failures come back as `OFFLINE`.
 *
 * [http] is the caller's client, so the Android side can add its internet
 * gate and network-log interceptors; nothing here depends on them. WebSocket
 * reads ignore the client's read timeout by OkHttp's design, which is what a
 * long upload wants.
 */
class SendClient(private val http: OkHttpClient) {

    /** What the instance allows. A server without `/config` (very old) answers with the stock limits. */
    fun config(base: String): SendServerConfig {
        val request = Request.Builder().url("${base.trimEnd('/')}/config").get().build()
        return call(request) { response ->
            when {
                response.code == 404 -> SendServerConfig.DEFAULT
                !response.isSuccessful -> throw SendException(SendError.SERVER, response.code)
                else -> SendServerConfig.fromJson(response.body?.string().orEmpty())
            }
        }
    }

    // ---- receiving ----

    /**
     * Whether the file is still there and whether it wants a password. The
     * nonce in the reply is the one to sign for the metadata call.
     */
    fun exists(link: SendLink): Exists {
        val request = Request.Builder().url("${link.base}/api/exists/${link.id}").get().build()
        return call(request) { response ->
            when {
                response.code == 404 -> throw SendException(SendError.NOT_FOUND, 404)
                !response.isSuccessful -> throw SendException(SendError.SERVER, response.code)
                else -> {
                    val body = response.body?.string().orEmpty()
                    val requires = runCatching { Json.parseToJsonElement(body).jsonObject.bool("requiresPassword") }
                        .getOrNull() ?: false
                    Exists(requires, nonceOf(response))
                }
            }
        }
    }

    data class Exists(val requiresPassword: Boolean, val nonce: String?)

    /**
     * Begins a download: a session that signs each request with the link's
     * keys (password-stretched when [password] is given) and keeps the
     * rotating nonce. Call [exists] first to learn whether a password is
     * needed; a missing or wrong one surfaces as `WRONG_PASSWORD` here.
     */
    fun open(link: SendLink, password: String? = null, nonce: String? = null): Session {
        val keys = link.keys
        if (password != null) keys.setPassword(password, link.url)
        return Session(link, keys, nonce ?: exists(link).nonce, password != null)
    }

    /** One receiving conversation with the server: the keys, and the nonce it last issued. */
    inner class Session internal constructor(
        val link: SendLink,
        val keys: SendKeys,
        @Volatile var nonce: String?,
        private val withPassword: Boolean,
    ) {
        /** The envelope and the file's remaining life. */
        fun metadata(): SendRemoteFile {
            val response = authorised { Request.Builder().url("${link.base}/api/metadata/${link.id}").get() }
            val body = response.use { it.body?.string().orEmpty() }
            val obj = runCatching { Json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw SendException(SendError.SERVER, detail = "metadata is not JSON") }
            val envelope = obj.string("metadata") ?: throw SendException(SendError.SERVER, detail = "no metadata field")
            return SendRemoteFile(
                link = link,
                metadata = keys.decryptMetadata(envelope),
                ttlMs = obj.long("ttl") ?: 0L,
                finalDownload = obj.bool("finalDownload") ?: false,
                requiresPassword = withPassword,
            )
        }

        /**
         * Downloads and decrypts into [sink], one file per manifest entry for
         * an archive. Returns the locations the sink reported, in manifest
         * order. [progress] is content bytes written against the total.
         * [cancelled] is polled between blocks.
         */
        fun download(
            metadata: SendMetadata,
            sink: SendSink,
            progress: (done: Long, total: Long) -> Unit = { _, _ -> },
            cancelled: () -> Boolean = { false },
        ): List<String> {
            val response = authorised { Request.Builder().url("${link.base}/api/download/${link.id}").get() }
            val writer = SinkWriter(sink, metadata.saved, cancelled)
            try {
                response.use { r ->
                    val body = r.body ?: throw SendException(SendError.SERVER, r.code, "empty download")
                    Ece.decrypt(body.byteStream(), keys.secret, writer) { done -> progress(done, metadata.size) }
                }
                return writer.finish(success = true)
            } catch (e: SendException) {
                writer.finish(success = false)
                throw e
            } catch (e: IOException) {
                writer.finish(success = false)
                throw SendException(if (cancelled()) SendError.CANCELLED else SendError.OFFLINE, cause = e)
            }
        }

        /** Sends with the current nonce; on 401 takes the server's nonce and tries once more. */
        private fun authorised(build: () -> Request.Builder): Response {
            var attempts = 0
            while (true) {
                val current = nonce ?: exists(link).nonce.also { nonce = it }
                    ?: throw SendException(SendError.SERVER, detail = "server issued no nonce")
                val request = build().header("Authorization", keys.authHeader(current)).build()
                val response = try {
                    http.newCall(request).execute()
                } catch (e: IOException) {
                    throw SendException(SendError.OFFLINE, cause = e)
                }
                nonceOf(response)?.let { nonce = it }
                when {
                    response.isSuccessful -> return response
                    response.code == 401 && attempts == 0 && nonce != null && nonce != current -> {
                        response.close()
                        attempts++
                    }
                    response.code == 401 -> {
                        response.close()
                        throw SendException(if (withPassword) SendError.WRONG_PASSWORD else SendError.WRONG_KEY, 401)
                    }
                    response.code == 404 -> {
                        response.close()
                        throw SendException(SendError.NOT_FOUND, 404)
                    }
                    else -> {
                        val code = response.code
                        response.close()
                        throw SendException(SendError.SERVER, code)
                    }
                }
            }
        }
    }

    // ---- sending ----

    /**
     * Encrypts and uploads [files] as one archive over the WebSocket, then sets
     * the password if asked. [progress] counts ciphertext bytes sent against
     * [Ece.encryptedSize] of the content, so it reaches the total exactly when
     * the last record has gone. [cancelled] is polled between frames.
     */
    fun upload(
        base: String,
        files: List<SendFile>,
        options: SendUploadOptions,
        keys: SendKeys = SendKeys.random(),
        nowMs: Long = System.currentTimeMillis(),
        progress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): SendUpload {
        require(files.isNotEmpty()) { "nothing to upload" }
        val root = base.trimEnd('/')
        val metadata = SendMetadata.forFiles(files.map { it.entry })
        val total = Ece.encryptedSize(metadata.size)
        val wsUrl = root.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/ws"

        val events = LinkedBlockingQueue<Any>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                events.put(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.put(Closed(code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.put(t)
            }
        }
        val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        try {
            val hello = buildJsonObject {
                put("fileMetadata", keys.encryptMetadata(metadata))
                put("authorization", "send-v1 ${keys.authKeyB64}")
                put("bearer", null as String?)
                put("timeLimit", options.timeLimitSeconds)
                put("dlimit", options.downloadLimit)
            }.toString()
            if (!ws.send(hello)) throw SendException(SendError.OFFLINE, detail = "socket refused the first message")
            val first = awaitReply(events, HANDSHAKE_TIMEOUT_MS, cancelled)
            val id = first.string("id") ?: throw SendException(SendError.SERVER, detail = "no id in reply")
            val ownerToken = first.string("ownerToken") ?: throw SendException(SendError.SERVER, detail = "no owner token")
            val serverUrl = first.string("url")

            val cipher = EceEncryptingInputStream(ConcatInputStream(files), keys.secret)
            val buffer = ByteArray(Ece.RECORD_SIZE)
            var sent = 0L
            cipher.use { stream ->
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    // The socket buffers what it is handed; keep two records in it, no more.
                    while (ws.queueSize() > 2L * Ece.RECORD_SIZE) {
                        if (cancelled()) throw SendException(SendError.CANCELLED)
                        events.poll()?.let { throw unexpected(it) }
                        Thread.sleep(BACKPRESSURE_SLEEP_MS)
                    }
                    if (cancelled()) throw SendException(SendError.CANCELLED)
                    if (!ws.send(buffer.toByteString(0, n))) throw SendException(SendError.OFFLINE, detail = "socket closed mid-upload")
                    sent += n
                    progress(sent, total)
                }
            }
            if (!ws.send(END_MARKER)) throw SendException(SendError.OFFLINE, detail = "socket closed before the end marker")
            awaitReply(events, FINISH_TIMEOUT_MS, cancelled)
            ws.close(1000, null)

            val link = serverUrl?.let { SendLink.parse("$it#${keys.secretB64}") } ?: SendLink(root, id, keys.secret)
            if (options.password != null) {
                keys.setPassword(options.password, link.url)
                setPassword(link.base, id, ownerToken, keys.authKeyB64)
            }
            return SendUpload(
                id = id,
                url = link.url,
                ownerToken = ownerToken,
                instance = link.base,
                name = metadata.name,
                size = metadata.size,
                type = metadata.type,
                files = metadata.files,
                createdAt = nowMs,
                expiresAt = nowMs + options.timeLimitSeconds * 1000,
                downloadLimit = options.downloadLimit,
                password = options.password != null,
            )
        } catch (e: SendException) {
            ws.cancel()
            throw e
        } catch (e: IOException) {
            ws.cancel()
            throw SendException(if (cancelled()) SendError.CANCELLED else SendError.OFFLINE, cause = e)
        } catch (e: InterruptedException) {
            ws.cancel()
            throw SendException(SendError.CANCELLED, cause = e)
        }
    }

    private class Closed(val code: Int)

    /** Waits for the server's next JSON message; an `error` field or anything else becomes an exception. */
    private fun awaitReply(events: LinkedBlockingQueue<Any>, timeoutMs: Long, cancelled: () -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (cancelled()) throw SendException(SendError.CANCELLED)
            val event = events.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (event == null) {
                if (System.currentTimeMillis() > deadline) throw SendException(SendError.OFFLINE, detail = "server did not answer")
                continue
            }
            if (event !is String) throw unexpected(event)
            val obj = runCatching { Json.parseToJsonElement(event).jsonObject }
                .getOrElse { throw SendException(SendError.SERVER, detail = "reply is not JSON") }
            obj.int("error")?.let { code ->
                throw when (code) {
                    400 -> SendException(SendError.BAD_LIMITS, code)
                    401 -> SendException(SendError.UNAUTHORIZED, code)
                    413 -> SendException(SendError.TOO_BIG, code)
                    404 -> SendException(SendError.NOT_FOUND, code)
                    else -> SendException(SendError.SERVER, code)
                }
            }
            return obj
        }
    }

    private fun unexpected(event: Any): SendException = when (event) {
        is Throwable -> SendException(SendError.OFFLINE, cause = event)
        is Closed -> SendException(SendError.SERVER, detail = "socket closed (${event.code})")
        else -> SendException(SendError.SERVER, detail = "unexpected $event")
    }

    // ---- owner calls ----

    fun setPassword(base: String, id: String, ownerToken: String, authKeyB64: String) {
        owner(base, "password", id, buildJsonObject { put("owner_token", ownerToken); put("auth", authKeyB64) })
    }

    fun setDownloadLimit(base: String, id: String, ownerToken: String, limit: Int) {
        owner(base, "params", id, buildJsonObject { put("owner_token", ownerToken); put("dlimit", limit) })
    }

    fun delete(base: String, id: String, ownerToken: String) {
        owner(base, "delete", id, buildJsonObject { put("owner_token", ownerToken) })
    }

    fun info(base: String, id: String, ownerToken: String): SendInfo {
        val body = owner(base, "info", id, buildJsonObject { put("owner_token", ownerToken) })
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw SendException(SendError.SERVER, detail = "info is not JSON") }
        return SendInfo(
            downloadLimit = obj.int("dlimit") ?: 0,
            downloads = obj.int("dtotal") ?: 0,
            ttlMs = obj.long("ttl") ?: 0L,
        )
    }

    private fun owner(base: String, action: String, id: String, body: JsonObject): String {
        val request = Request.Builder()
            .url("${base.trimEnd('/')}/api/$action/$id")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return call(request) { response ->
            when {
                response.isSuccessful -> response.body?.string().orEmpty()
                response.code == 404 -> throw SendException(SendError.NOT_FOUND, 404)
                response.code == 401 -> throw SendException(SendError.UNAUTHORIZED, 401)
                else -> throw SendException(SendError.SERVER, response.code)
            }
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw SendException(SendError.OFFLINE, cause = e)
        }
        return response.use(read)
    }

    private fun nonceOf(response: Response): String? =
        response.header("WWW-Authenticate")?.split(' ')?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private companion object {
        val JSON = "application/json".toMediaType()
        val END_MARKER: ByteString = ByteString.of(0)
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        /** The server stores the whole file before it says ok; a slow disk on a big file takes a while. */
        const val FINISH_TIMEOUT_MS = 180_000L
        const val POLL_MS = 100L
        const val BACKPRESSURE_SLEEP_MS = 5L
    }
}

/** The files one after another, each opened only when its turn comes. */
internal class ConcatInputStream(private val files: List<SendFile>) : InputStream() {
    private var index = -1
    private var current: InputStream? = null

    private fun next(): Boolean {
        current?.close()
        index++
        if (index >= files.size) {
            current = null
            return false
        }
        current = files[index].open()
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val stream = current ?: if (next()) current!! else return -1
            val n = stream.read(b, off, len)
            if (n > 0) return n
            if (n < 0 && !next()) return -1
        }
    }

    override fun close() {
        current?.close()
        current = null
        index = files.size
    }
}

/**
 * Plaintext out of [Ece.decrypt], dealt to one sink file after another by the
 * manifest's sizes. A file the sink refuses fails the whole download; a
 * cancelled download closes the current file as failed.
 */
internal class SinkWriter(
    private val sink: SendSink,
    private val entries: List<SendFileEntry>,
    private val cancelled: () -> Boolean,
) : OutputStream() {
    private var index = 0
    private var remaining = 0L
    private var current: SendSinkFile? = null
    private val locations = mutableListOf<String>()
    private var failed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var left = len
        while (left > 0) {
            if (cancelled()) throw SendException(SendError.CANCELLED)
            val file = current ?: openNext() ?: throw SendException(SendError.CORRUPT, detail = "more content than the manifest lists")
            val take = if (remaining <= 0L) left else minOf(left.toLong(), remaining).toInt()
            file.stream.write(b, offset, take)
            offset += take
            left -= take
            remaining -= take
            if (remaining == 0L && index < entries.size) closeCurrent(success = true)
        }
    }

    private fun openNext(): SendSinkFile? {
        if (index >= entries.size) return null
        val entry = entries[index++]
        val file = sink.create(entry.name, entry.size, entry.type)
            ?: throw SendException(SendError.SERVER, detail = "the sink refused ${entry.name}")
        current = file
        // A zero-size entry (an older upload without sizes) takes whatever is left.
        remaining = if (entry.size > 0) entry.size else -1L
        return file
    }

    private fun closeCurrent(success: Boolean) {
        val file = current ?: return
        current = null
        val location = runCatching { file.finish(success) }.getOrNull()
        if (success && location != null) locations += location else if (success) failed = true
    }

    /** Closes whatever is open and returns the saved locations; [success] false deletes the partial file. */
    fun finish(success: Boolean): List<String> {
        closeCurrent(success)
        if (success) {
            // Entries the stream never reached (empty files at the end) still get created, empty.
            while (index < entries.size) {
                val file = openNext() ?: break
                current = null
                runCatching { file.finish(true) }.getOrNull()?.let { locations += it } ?: run { failed = true }
            }
            if (failed) throw SendException(SendError.SERVER, detail = "a file could not be saved")
        }
        return locations.toList()
    }
}
