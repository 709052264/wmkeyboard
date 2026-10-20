package com.wasimaster.wmkeyboard.core.modules

import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.coroutines.ensureActive
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Fetches one [NativePin] into its [NativeStore] staging file and installs
 * it. Every request goes through [NetLog] under [NetSource.DOWNLOAD_NATIVE],
 * so the network activity log shows each host and a build without the
 * internet permission stops here.
 */
object NativeDownloader {

    /** The data repository's release that holds the project's own libraries. */
    const val RELEASE_TAG = "natives"

    private const val TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024
    private const val USER_AGENT = "WMKeyboard native delivery"

    /** The release asset for a [NativeSource.Data] pin, or null when the data repository is not on GitHub. */
    fun dataUrl(pin: NativePin): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${pin.sha256}-${pin.name}"
    }

    /**
     * Downloads [pin] and installs it into [store]. [onBytes] gets this
     * file's running byte count. Throws on any failure; a data download keeps
     * its partial file for the next try, which resumes with a range request.
     */
    suspend fun fetch(pin: NativePin, store: NativeStore, onBytes: (Long) -> Unit) {
        val part = store.partOf(pin)
        part.parentFile?.mkdirs()
        when (val source = pin.source) {
            NativeSource.Data -> {
                val url = dataUrl(pin) ?: throw IOException("the data repository is not on GitHub")
                transfer(url, pin.size, part, onBytes)
            }
            is NativeSource.Maven -> {
                // The entry is read whole each time: it is a few MB out of an
                // artifact, and resuming inside a deflate stream is not possible.
                part.delete()
                val remote = HttpRangeSource(source.url)
                val entry = ZipRangeReader.find(remote, source.entry)
                    ?: throw IOException("${source.entry} is not in ${source.coordinates}")
                if (entry.size != pin.size) throw IOException("${source.entry} is ${entry.size} bytes, pinned ${pin.size}")
                FileOutputStream(part).use { out ->
                    ZipRangeReader.extract(remote, entry, out) { written ->
                        onBytes(written)
                    }
                }
            }
        }
        coroutineContext.ensureActive()
        if (!store.install(pin, part)) throw IOException("${pin.name} did not match its pinned checksum")
    }

    private suspend fun transfer(url: String, size: Long, part: java.io.File, onBytes: (Long) -> Unit) {
        val resumeFrom = part.length().takeIf { it in 1 until size } ?: 0L
        if (resumeFrom == 0L) part.delete()
        val connection = open(url)
        val call = NetLog.call(NetSource.DOWNLOAD_NATIVE, "GET", url, route = NetLog.pathOf(url))
        try {
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            val status = connection.responseCode
            call.status = status
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $status for $url")
            }
            val already = if (status == HttpURLConnection.HTTP_PARTIAL) resumeFrom else 0L
            call.countIn(connection.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.setLength(already)
                    output.seek(already)
                    val buffer = ByteArray(BUFFER)
                    var written = already
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        // Anything past the pinned size is not the pinned file.
                        if (written > size) {
                            part.delete()
                            throw IOException("$url is longer than pinned")
                        }
                        output.write(buffer, 0, n)
                        onBytes(written)
                    }
                }
            }
        } catch (t: Throwable) {
            call.fail(t)
            throw t
        } finally {
            connection.disconnect()
            call.end()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            // The byte offsets of a range are offsets into the file as stored.
            setRequestProperty("Accept-Encoding", "identity")
        }

    /** A remote file read with HTTP range requests, each one a [NetLog] row. */
    private class HttpRangeSource(private val url: String) : RangeSource {

        override val length: Long by lazy {
            val connection = open(url)
            val call = NetLog.call(NetSource.DOWNLOAD_NATIVE, "HEAD", url, route = NetLog.pathOf(url))
            try {
                connection.requestMethod = "HEAD"
                call.status = connection.responseCode
                if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode} for $url")
                connection.contentLengthLong.takeIf { it > 0 } ?: throw IOException("no length for $url")
            } catch (t: Throwable) {
                call.fail(t)
                throw t
            } finally {
                connection.disconnect()
                call.end()
            }
        }

        override fun read(offset: Long, count: Int): ByteArray =
            open(offset, count.toLong()).use { input ->
                val out = ByteArray(count)
                var at = 0
                while (at < count) {
                    val n = input.read(out, at, count - at)
                    if (n < 0) throw IOException("short read from $url")
                    at += n
                }
                out
            }

        override fun open(offset: Long, count: Long): InputStream {
            val connection = open(url)
            val call = NetLog.call(NetSource.DOWNLOAD_NATIVE, "GET", url, route = NetLog.pathOf(url))
            try {
                connection.setRequestProperty("Range", "bytes=$offset-${offset + count - 1}")
                call.status = connection.responseCode
                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("$url did not answer a range request (HTTP ${connection.responseCode})")
                }
            } catch (t: Throwable) {
                call.fail(t)
                call.end()
                connection.disconnect()
                throw t
            }
            return object : FilterInputStream(call.countIn(connection.inputStream)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                        call.end()
                    }
                }
            }
        }
    }
}
