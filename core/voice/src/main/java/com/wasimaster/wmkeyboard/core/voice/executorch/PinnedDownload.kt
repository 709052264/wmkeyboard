package com.wasimaster.wmkeyboard.core.voice.executorch

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One file of a model, fetched from a pinned URL: a `.part` beside the target,
 * a Range request to resume it, and the move into place only once the bytes
 * hash to what the catalog says. [EtDownloadManager] and the speech-out
 * voices ([com.wasimaster.wmkeyboard.core.voice.speech.MmsVoiceDownloads])
 * share it.
 */
internal object PinnedDownload {

    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"

    /** A download that stopped for a reason the screen can say; [messageArg] is "" when [messageRes] takes none. */
    class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    /**
     * Fetches [url] into [target], [bytes] long with SHA-256 [sha256]; [path]
     * names it to the integrity log. [onProgress] hears the bytes of this file
     * on disk, a few times a second.
     */
    suspend fun fetch(
        url: String,
        target: File,
        bytes: Long,
        sha256: String,
        path: String,
        source: NetSource,
        onProgress: (Long) -> Unit,
    ) {
        val part = File(target.parentFile, "${target.name}.part")
        var resumeFrom = part.length()
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(source, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            netCall.status = connection.responseCode
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> resumeFrom = 0
                else -> throw FailedException(R.string.core_voice_download_http_error, code.toString())
            }
            var written = resumeFrom
            var lastUpdate = 0L
            netCall.countIn(connection.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(resumeFrom)
                    out.seek(resumeFrom)
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            onProgress(written)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
        if (part.length() < bytes) throw FailedException(R.string.core_voice_download_interrupted_error)
        if (!IntegrityChecks.accept(EtStore.sha256(part).equals(sha256, ignoreCase = true), path)) {
            // Wrong bytes cannot be resumed into right ones: start over next time.
            part.delete()
            throw FailedException(R.string.core_voice_whistle_checksum_error)
        }
        target.delete()
        check(part.renameTo(target)) { "could not move the finished download into place" }
    }
}
