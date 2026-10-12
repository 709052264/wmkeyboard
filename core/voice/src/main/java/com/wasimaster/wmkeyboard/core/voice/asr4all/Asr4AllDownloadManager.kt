package com.wasimaster.wmkeyboard.core.voice.asr4all

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads asr4all models into [Asr4AllStore]: the same spine as the Whistle
 * and Whisper managers. Its own IO scope so progress outlives the screen, a
 * `.part` plus a Range request to resume, one model at a time, and each file
 * moved into place only once its SHA-256 matches.
 */
object Asr4AllDownloadManager {

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status

        /** Cancelled or interrupted with a `.part` on disk: resumable. */
        data class Paused(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageArg] is the one argument [messageRes] takes, or "" for none. */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var activeSize: Asr4AllSize? = null
    private val _states = MutableStateFlow<Map<Asr4AllSize, Status>>(emptyMap())

    /** Size → status, for every size. */
    val states: StateFlow<Map<Asr4AllSize, Status>> = _states.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Seeds [states] from disk; call when the screen appears. */
    fun refresh(filesDir: File) {
        _states.update { current ->
            Asr4AllSize.entries.associateWith { size ->
                val active = current[size]
                when {
                    size == activeSize && isBusy && active != null -> active
                    Asr4AllStore.isDownloaded(filesDir, size) -> Status.Downloaded
                    Asr4AllStore.bytesOnDisk(filesDir, size) > 0 ->
                        Status.Paused(Asr4AllStore.bytesOnDisk(filesDir, size), size.bytes)
                    else -> Status.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, size: Asr4AllSize) {
        if (isBusy || Asr4AllStore.isDownloaded(filesDir, size)) return
        activeSize = size
        set(size, Status.Downloading(Asr4AllStore.bytesOnDisk(filesDir, size), size.bytes))
        job = scope.launch {
            try {
                download(filesDir, size)
                set(size, Status.Downloaded)
            } catch (e: CancellationException) {
                // delete() cancels and then wipes; the unwinding job must not
                // bring a Paused back for files that are gone.
                val onDisk = Asr4AllStore.bytesOnDisk(filesDir, size)
                set(size, if (onDisk > 0) Status.Paused(onDisk, size.bytes) else Status.NotDownloaded)
                throw e
            } catch (e: FailedException) {
                set(size, Status.Failed(e.messageRes, e.messageArg))
            } catch (_: Exception) {
                set(size, Status.Failed(CommonR.string.common_error_network))
            } finally {
                activeSize = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(filesDir: File, size: Asr4AllSize) {
        if (size == activeSize) cancel()
        Asr4AllStore.delete(filesDir, size)
        set(size, Status.NotDownloaded)
    }

    private fun set(size: Asr4AllSize, status: Status) {
        _states.update { it + (size to status) }
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    private suspend fun download(filesDir: File, size: Asr4AllSize) {
        val dir = Asr4AllStore.dir(filesDir, size).apply { mkdirs() }
        val stillNeeded = size.files.sumOf { f ->
            val target = Asr4AllStore.file(filesDir, size, f)
            if (target.length() == f.bytes) 0L else f.bytes - File(dir, "${f.name}.part").length()
        }
        if (StatFs(dir.path).availableBytes < stillNeeded + SPACE_MARGIN_BYTES) {
            throw FailedException(
                R.string.core_voice_download_space_error,
                ((stillNeeded + SPACE_MARGIN_BYTES) / 1e6).roundToInt().toString(),
            )
        }
        var done = 0L
        // Metadata first: small, so a bad connection fails before the big file.
        for (f in size.files) {
            val target = Asr4AllStore.file(filesDir, size, f)
            if (target.isFile && target.length() == f.bytes) {
                done += f.bytes
                continue
            }
            downloadFile(size, f, target, done)
            done += f.bytes
        }
    }

    private suspend fun downloadFile(size: Asr4AllSize, f: Asr4AllFile, target: File, base: Long) {
        val part = File(target.parentFile, "${target.name}.part")
        var resumeFrom = part.length()
        val url = size.url(f)
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
                            set(size, Status.Downloading(base + written, size.bytes))
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
        if (part.length() < f.bytes) throw FailedException(R.string.core_voice_download_interrupted_error)
        if (!Asr4AllStore.sha256(part).equals(f.sha256, ignoreCase = true)) {
            // Wrong bytes cannot be resumed into right ones: start over next time.
            part.delete()
            throw FailedException(R.string.core_voice_whistle_checksum_error)
        }
        target.delete()
        check(part.renameTo(target)) { "could not move the finished download into place" }
    }
}
