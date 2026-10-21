package com.wasimaster.wmkeyboard.core.download

import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one download loop behind every pinned file the app fetches: models,
 * dictionary and language packs, the project's own native libraries.
 *
 * A file is fetched as several ranged requests at once into one `.part`,
 * preallocated to its full size, the pieces handed out from a queue so a slow
 * connection holds up only its own small piece. GitHub, which hosts most of
 * these, holds each connection to 0.3 to 0.8 MB/s from Bangladesh; twelve side
 * by side reached about 5 MB/s on the phone and the Mac alike (measured
 * 2026-10-21). Maven Central fills the line on one connection and gains
 * nothing from more, but loses nothing either.
 *
 * Which byte ranges are already on disk is kept beside the `.part` in a small
 * `.ranges` file, so a stopped download continues every piece where it was,
 * not from the start of each. A `.part` left by the older one-stream loops
 * (no `.ranges`) counts as that many bytes from the start. A server that
 * answers a range request with the whole file is read as one stream instead.
 *
 * Every request is a [NetLog] row under the caller's [NetSource], and [gate]
 * is asked before each one, so data saving can stop a download that finds
 * itself on a metered link halfway through. Nothing is verified here except
 * the length; [fetchVerified] adds the SHA-256 and the move into place.
 */
object FileDownloader {

    /** Side by side per file. On GitHub 8 gave 3.4 MB/s, 12 gave 5.2, 16 swung between 3.9 and 8.4. */
    const val CONNECTIONS = 12

    private const val MIN_PIECE = 1L shl 20
    private const val MAX_PIECE = 32L shl 20

    /** Pieces per connection, so a slow connection holds up only a small last piece. */
    private const val PIECES_PER_CONNECTION = 4
    private const val ATTEMPTS = 3
    private const val BUFFER = 64 * 1024
    private const val TICK_MS = 250L
    private const val SAVE_EVERY_TICKS = 4
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    const val USER_AGENT = "WMKeyboard model downloader"

    /**
     * Asked before every request. Published by the app from its data saving
     * settings; the default lets everything through, which is also what a
     * plain JVM test sees.
     */
    @Volatile
    var gate: DownloadGate = DownloadGate { true }

    /** The resume map beside [part]. */
    fun rangesOf(part: File): File = File(part.parentFile, "${part.name}.ranges")

    /**
     * The bytes of [part] already fetched: what its resume map says, or its
     * length when it has none. A `.part` is preallocated, so its length alone
     * says nothing about progress; anything that shows a paused download or
     * sizes the space still needed asks this.
     */
    fun bytesDone(part: File): Long {
        if (!part.isFile) return 0L
        val plan = RangePlan.read(rangesOf(part)) ?: return part.length()
        return if (plan.total == part.length()) plan.done else 0L
    }

    /**
     * The bytes under [dir] as a progress bar counts them: finished files
     * whole, each `.part` by [bytesDone], resume maps not at all.
     */
    fun bytesUnder(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { file ->
            when {
                file.name.endsWith(".part") -> bytesDone(file)
                file.name.endsWith(".ranges") || file.name.endsWith(".ranges.tmp") -> 0L
                else -> file.length()
            }
        }

    /** Deletes [part] and its resume map. */
    fun discard(part: File) {
        part.delete()
        rangesOf(part).delete()
    }

    /**
     * Fetches [url] into [part], [size] bytes long, or as long as the server
     * says when [size] is null. Returns the length. [onBytes] hears the bytes
     * on disk a few times a second and once at the end, from a worker thread.
     *
     * [headers] go with every request to [url] itself, never to the address
     * it redirects to: a token for the host the user gave it to, not for its
     * storage provider.
     *
     * Throws a [DownloadException] for a failure the caller can name, any
     * other [IOException] for the network, and [CancellationException] when
     * cancelled; each leaves the `.part` and its map for the next try, except
     * a length that cannot be the pinned file, which starts over.
     */
    suspend fun fetch(
        url: String,
        part: File,
        size: Long?,
        source: NetSource,
        connections: Int = CONNECTIONS,
        headers: Map<String, String> = emptyMap(),
        onBytes: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        require(size == null || size >= 0) { "negative size" }
        part.parentFile?.mkdirs()
        val job = Job(url, part, size, source, connections.coerceAtLeast(1), headers, onBytes)
        try {
            job.run()
        } catch (_: NoRangesException) {
            // Ranges worked before and do not now (another mirror, a proxy):
            // what is on disk cannot be trusted to line up, so begin again.
            discard(part)
            job.runOneStream(job.open(null, url))
        }
    }

    /**
     * [fetch] into [part], then the SHA-256 against [sha256] (lower or upper
     * case), then [part] renamed to [target]. A mismatch deletes the bytes and
     * throws [DownloadException.Mismatch]; with checks turned off in settings
     * it is logged and let through, as [IntegrityChecks] says. [what] names the
     * file in that log. [part] defaults to `<target>.part`.
     */
    suspend fun fetchVerified(
        url: String,
        target: File,
        size: Long?,
        sha256: String,
        source: NetSource,
        part: File = File(target.parentFile, "${target.name}.part"),
        what: String = target.name,
        connections: Int = CONNECTIONS,
        headers: Map<String, String> = emptyMap(),
        onBytes: (Long) -> Unit = {},
    ) {
        fetch(url, part, size, source, connections, headers, onBytes)
        val matches = withContext(Dispatchers.IO) { sha256(part).equals(sha256, ignoreCase = true) }
        if (!IntegrityChecks.accept(matches, what)) {
            // Wrong bytes cannot be resumed into right ones.
            discard(part)
            throw DownloadException.Mismatch()
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("could not move ${target.name} into place")
        rangesOf(part).delete()
    }

    /** The SHA-256 of [file], lower-case hex. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pieceSize(total: Long, connections: Int): Long =
        (total / (connections * PIECES_PER_CONNECTION)).coerceIn(MIN_PIECE, MAX_PIECE)

    /** A range request answered with the whole file, after earlier ones were not. */
    private class NoRangesException : IOException("the server stopped answering range requests")

    private class Job(
        val url: String,
        val part: File,
        val size: Long?,
        val source: NetSource,
        val connections: Int,
        val headers: Map<String, String>,
        val onBytes: (Long) -> Unit,
    ) {
        private val ranges = rangesOf(part)
        private val done = AtomicLong()

        /** Where a range request goes: the address [url] redirected to, once known. */
        @Volatile
        private var resolved: String = url

        suspend fun run(): Long {
            val plan = resume()
            if (plan != null) {
                if (plan.complete) return plan.total.also { onBytes(it) }
                fetchPieces(plan, first = null)
                return plan.total
            }
            // Fresh, or a `.part` from the one-stream loops whose bytes are
            // the file's start: the first piece's request also says whether
            // ranges work and, when [size] is null, how long the file is.
            val prefix = legacyPrefix()
            val firstLength = size?.let { pieceSize(it, connections) } ?: MIN_PIECE
            val first = open(prefix until prefix + firstLength, url)
            when (val status = first.connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    if (prefix > 0) discard(part)
                    return runOneStream(first)
                }
                HttpURLConnection.HTTP_PARTIAL -> Unit
                else -> {
                    first.close(null)
                    // Asked for bytes past the end: the old `.part` was not this file's start.
                    if (status == HTTP_RANGE_NOT_SATISFIABLE && prefix > 0) {
                        discard(part)
                        return run()
                    }
                    throw DownloadException.Http(status)
                }
            }
            val total = try {
                if (startOf(first.connection)?.let { it != prefix } == true) throw NoRangesException()
                totalOf(first.connection) ?: size ?: throw IOException("no length for $url")
            } catch (t: Throwable) {
                first.close(t)
                throw t
            }
            if (size != null && total != size) {
                first.close(null)
                discard(part)
                throw DownloadException.WrongSize(size, total)
            }
            resolved = first.connection.url.toString()
            val fresh = RangePlan.split(total, prefix, firstLength, pieceSize(total, connections))
            RandomAccessFile(part, "rw").use { it.setLength(total) }
            fresh.write(ranges)
            fetchPieces(fresh, first)
            return total
        }

        /**
         * The plan on disk, or null to start from the first request. Clears a
         * map that does not add up. A `.part` with no map that is already
         * [size] long (the one-stream loops stopped between the last byte and
         * the rename) counts as done, and the hash decides.
         */
        private fun resume(): RangePlan? {
            if (ranges.exists()) {
                val saved = RangePlan.read(ranges)
                if (saved != null && part.isFile && part.length() == saved.total && (size == null || saved.total == size)) {
                    return saved
                }
                discard(part)
                return null
            }
            if (size != null && size > 0 && part.isFile && part.length() == size) {
                return RangePlan(size, listOf(RangePlan.Piece(0, size, size)))
            }
            return null
        }

        /** The bytes of a `.part` the one-stream loops left, which are the file's start; 0 for none. */
        private fun legacyPrefix(): Long {
            if (ranges.exists() || !part.isFile) return 0L
            val length = part.length()
            if (length == 0L || (size != null && length >= size)) {
                part.delete()
                return 0L
            }
            return length
        }

        /**
         * Fetches every unfinished piece of [plan] on up to [connections]
         * workers. [first], when given, is the open response for the first
         * piece not yet on disk.
         */
        private suspend fun fetchPieces(plan: RangePlan, first: Opened?) {
            done.set(plan.done)
            val head = plan.pieces.first { !it.complete }
            val queue = ArrayDeque(plan.pieces.filter { !it.complete && (first == null || it !== head) })
            RandomAccessFile(part, "rw").use { file ->
                val channel = file.channel
                try {
                    coroutineScope {
                        val ticker = launch { tick(plan, channel) }
                        coroutineScope {
                            if (first != null) {
                                launch {
                                    try {
                                        streamPiece(head, channel, first)
                                    } catch (e: IOException) {
                                        if (!retryable(e)) throw e
                                    }
                                    fetchPiece(head, channel)
                                }
                            }
                            val workers = (connections - if (first != null) 1 else 0).coerceAtLeast(1)
                            repeat(workers) {
                                launch {
                                    while (true) {
                                        val piece = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                                        fetchPiece(piece, channel)
                                    }
                                }
                            }
                        }
                        ticker.cancel()
                    }
                } finally {
                    // Whatever happened, what is on disk is recorded for the next try.
                    runCatching { channel.force(false) }
                    if (!plan.complete) runCatching { plan.write(ranges) }
                }
            }
            if (!plan.complete) throw DownloadException.Interrupted()
            ranges.delete()
            onBytes(plan.total)
        }

        private suspend fun tick(plan: RangePlan, channel: FileChannel) {
            var ticks = 0
            while (true) {
                delay(TICK_MS)
                onBytes(done.get())
                if (++ticks % SAVE_EVERY_TICKS == 0) {
                    // The map may only claim bytes that reached the file.
                    channel.force(false)
                    plan.write(ranges)
                }
            }
        }

        /** One piece to its end, retrying a dropped connection from where it stopped. */
        private suspend fun fetchPiece(piece: RangePlan.Piece, channel: FileChannel) {
            var attempt = 0
            while (!piece.complete) {
                val address = resolved
                try {
                    streamPiece(piece, channel, open(piece.next..piece.last, address))
                } catch (e: IOException) {
                    // A redirect target can expire (GitHub's are signed for
                    // an hour): ask the original address again, once, at once.
                    if (address != url && e is DownloadException.Http && e.status in 400..499) {
                        resolved = url
                        continue
                    }
                    attempt++
                    if (!retryable(e) || attempt >= ATTEMPTS) throw e
                    delay(1_000L shl attempt)
                }
            }
        }

        private fun retryable(e: IOException): Boolean = when (e) {
            is DownloadException.Http -> e.status >= 500
            is DownloadException.Interrupted -> true
            is DownloadException -> false
            is NoRangesException -> false
            is UnknownHostException -> false
            else -> true
        }

        private suspend fun streamPiece(piece: RangePlan.Piece, channel: FileChannel, opened: Opened) {
            val connection = opened.connection
            try {
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    HttpURLConnection.HTTP_OK -> throw NoRangesException()
                    else -> throw DownloadException.Http(status)
                }
                val from = startOf(connection)
                if (from != null && from != piece.next) throw NoRangesException()
                if (opened.requested == url) resolved = connection.url.toString()
                coroutineScope { copy(opened, piece, channel) }
                opened.close(null)
            } catch (t: Throwable) {
                opened.close(t)
                throw t
            }
        }

        /** Copies the body into [piece]'s bytes, disconnecting at once if the download is cancelled. */
        private suspend fun CoroutineScope.copy(opened: Opened, piece: RangePlan.Piece, channel: FileChannel) {
            // A read blocked in the socket does not see cancellation; closing
            // the connection from here ends it.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    opened.connection.disconnect()
                }
            }
            try {
                val input: InputStream = opened.call.countIn(opened.connection.inputStream)
                input.use {
                    val buffer = ByteArray(BUFFER)
                    while (!piece.complete) {
                        coroutineContext.ensureActive()
                        val want = minOf(buffer.size.toLong(), piece.end - piece.next).toInt()
                        val read = it.read(buffer, 0, want)
                        if (read < 0) throw DownloadException.Interrupted()
                        val wrap = ByteBuffer.wrap(buffer, 0, read)
                        var at = piece.next
                        while (wrap.hasRemaining()) at += channel.write(wrap, at)
                        piece.advance(read.toLong())
                        done.addAndGet(read.toLong())
                    }
                }
            } finally {
                watcher.cancel()
            }
        }

        /**
         * The whole file as one stream from [first], which already answered:
         * for a server without ranges. Nothing can be resumed from such a
         * server, so the `.part` is written from the start each time.
         */
        suspend fun runOneStream(first: Opened): Long {
            val connection = first.connection
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw DownloadException.Http(connection.responseCode)
                }
                val announced = connection.contentLengthLong.takeIf { it >= 0 }
                if (size != null && announced != null && announced != size) {
                    throw DownloadException.WrongSize(size, announced)
                }
                ranges.delete()
                var written = 0L
                coroutineScope {
                    val watcher = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            connection.disconnect()
                        }
                    }
                    try {
                        first.call.countIn(connection.inputStream).use { input ->
                            RandomAccessFile(part, "rw").use { out ->
                                out.setLength(0)
                                val buffer = ByteArray(BUFFER)
                                var last = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    // Longer than pinned is not the pinned file; stop paying for it.
                                    if (size != null && written + read > size) {
                                        throw DownloadException.WrongSize(size, written + read)
                                    }
                                    out.write(buffer, 0, read)
                                    written += read
                                    val now = System.currentTimeMillis()
                                    if (now - last >= TICK_MS) {
                                        last = now
                                        onBytes(written)
                                    }
                                }
                            }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
                val expected = size ?: announced
                if (expected != null && written != expected) throw DownloadException.Interrupted()
                onBytes(written)
                first.close(null)
                return written
            } catch (t: Throwable) {
                if (t is DownloadException.WrongSize) discard(part)
                first.close(t)
                throw t
            }
        }

        /** A request on its way and its log row. */
        class Opened(val connection: HttpURLConnection, val call: NetCall, val requested: String) {
            fun close(t: Throwable?) {
                if (t != null && t !is CancellationException) call.fail(t)
                runCatching { call.status = connection.responseCode }
                connection.disconnect()
                call.end()
            }
        }

        /** Opens [address], for [range] when given; the status line is read before this returns. */
        fun open(range: LongRange?, address: String): Opened {
            if (!gate.allows(source)) throw DownloadException.Blocked()
            val call = NetLog.call(source, "GET", url, route = NetLog.pathOf(url))
            val connection = try {
                (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    // Offsets in a range are offsets into the file as stored.
                    setRequestProperty("Accept-Encoding", "identity")
                    if (range != null) setRequestProperty("Range", "bytes=${range.first}-${range.last}")
                    if (address == this@Job.url) headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
            } catch (t: Throwable) {
                call.fail(t)
                call.end()
                throw t
            }
            try {
                call.status = connection.responseCode
            } catch (t: Throwable) {
                call.fail(t)
                call.end()
                connection.disconnect()
                throw t
            }
            return Opened(connection, call, address)
        }

        /** The full length from a 206's `Content-Range: bytes a-b/total`. */
        private fun totalOf(connection: HttpURLConnection): Long? =
            connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/', "")
                ?.trim()
                ?.toLongOrNull()

        private fun startOf(connection: HttpURLConnection): Long? =
            connection.getHeaderField("Content-Range")
                ?.removePrefix("bytes")
                ?.trim()
                ?.substringBefore('-')
                ?.toLongOrNull()
    }
}

/** Asked before each request of a download; false stops it with [DownloadException.Blocked]. */
fun interface DownloadGate {
    fun allows(source: NetSource): Boolean
}

/** A download failure with a reason a screen can name. */
sealed class DownloadException(message: String) : IOException(message) {

    /** The server answered [status]. */
    class Http(val status: Int) : DownloadException("HTTP $status")

    /** The connection ended before the bytes did. What arrived is kept. */
    class Interrupted : DownloadException("the download stopped before the end")

    /** The server's file is not as long as the pinned one, so it is not the pinned file. */
    class WrongSize(val expected: Long, val actual: Long) : DownloadException("expected $expected bytes, got $actual")

    /** The bytes did not hash to the pin. They were deleted. */
    class Mismatch : DownloadException("the file did not match its checksum")

    /** Data saving stopped it before a request. What arrived is kept. */
    class Blocked : DownloadException("held back by data saving")
}
