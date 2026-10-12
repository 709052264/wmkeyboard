package com.wasimaster.wmkeyboard.core.voice.whistle

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Downloads the Whistle model into [WhistleModelStore]. One file, so one
 * status rather than [com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager]'s
 * map, otherwise the same spine: its own IO scope so progress outlives the
 * screen, a `.part` plus a Range request to resume, and the file is only moved
 * into place once its SHA-256 matches.
 */
object WhistleModelDownloadManager {

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status

        /** Cancelled or interrupted with a `.part` on disk: resumable. */
        data class Paused(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageArg] is the one argument [messageRes] takes, or "" for none. */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Seeds [status] from disk; call when the screen appears. */
    fun refresh(filesDir: File) {
        if (isBusy) return
        val partial = WhistleModelStore.partialFile(filesDir).length()
        _status.value = when {
            WhistleModelStore.isDownloaded(filesDir) -> Status.Downloaded
            partial > 0 -> Status.Paused(partial, WhistleModelStore.MODEL_BYTES)
            else -> Status.NotDownloaded
        }
    }

    fun start(filesDir: File) {
        if (isBusy || WhistleModelStore.isDownloaded(filesDir)) return
        val part = WhistleModelStore.partialFile(filesDir)
        _status.value = Status.Downloading(part.length(), WhistleModelStore.MODEL_BYTES)
        job = scope.launch {
            try {
                download(filesDir)
                _status.value = Status.Downloaded
            } catch (e: CancellationException) {
                // delete() cancels and then wipes; the unwinding job must not
                // bring a Paused back for a file that is gone.
                _status.value = if (part.length() > 0) {
                    Status.Paused(part.length(), WhistleModelStore.MODEL_BYTES)
                } else {
                    Status.NotDownloaded
                }
                throw e
            } catch (e: FailedException) {
                _status.value = Status.Failed(e.messageRes, e.messageArg)
            } catch (_: Exception) {
                _status.value = Status.Failed(CommonR.string.common_error_network)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(filesDir: File) {
        cancel()
        WhistleModelStore.delete(filesDir)
        _status.value = Status.NotDownloaded
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    private suspend fun download(filesDir: File) {
        val target = WhistleModelStore.modelFile(filesDir)
        val part = WhistleModelStore.partialFile(filesDir)
        target.parentFile?.mkdirs()
        val needed = WhistleModelStore.MODEL_BYTES - part.length()
        if (StatFs(target.parentFile!!.path).availableBytes < needed + SPACE_MARGIN_BYTES) {
            throw FailedException(
                R.string.core_voice_download_space_error,
                ((needed + SPACE_MARGIN_BYTES) / 1e6).roundToInt().toString(),
            )
        }
        var resumeFrom = part.length()
        val url = WhistleModelStore.MODEL_URL
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WHISPER, "GET", url, route = NetLog.pathOf(url))
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
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            _status.value = Status.Downloading(written, WhistleModelStore.MODEL_BYTES)
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
        if (part.length() < WhistleModelStore.MODEL_BYTES) {
            throw FailedException(R.string.core_voice_download_interrupted_error)
        }
        if (!WhistleModelStore.hasValidChecksum(part)) {
            // Wrong bytes cannot be resumed into right ones: start over next time.
            part.delete()
            throw FailedException(R.string.core_voice_whistle_checksum_error)
        }
        target.delete()
        check(part.renameTo(target)) { "could not move the finished download into place" }
    }
}
