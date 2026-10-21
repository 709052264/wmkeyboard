package com.wasimaster.wmkeyboard.core.xlit

import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
 * The download of [IndicXlitModels]' pack, ONNX Runtime first wherever it is
 * delivered on demand (Play's `:feature:onnx`, or the pinned library on a
 * GitHub or F-Droid build), so the settings row shows one bar for both.
 *
 * Each file goes to a `.part` beside it, resumes with a range request where
 * the server allows one, and gets its name only after its SHA-256 matched.
 * Process-wide: the language screens and the keyboard read the same [status].
 */
object IndicXlitDownloads {

    sealed interface Status {
        data object NotDownloaded : Status

        /** [total] is every byte still to fetch when it started, the runtime's included. */
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status

        /** [messageRes] is the text to show; [messageArg] its one argument, or "". */
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"
    private const val BUFFER_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)

    val status: StateFlow<Status> = _status.asStateFlow()

    /** Seeds [status] from disk; call when a screen that shows it appears. */
    fun refresh(context: Context) {
        val running = synchronized(this) { job?.isActive == true }
        if (running) return
        _status.value = when {
            IndicXlitModels.isDownloaded(context.noBackupFilesDir) -> Status.Downloaded
            else -> _status.value as? Status.Failed ?: Status.NotDownloaded
        }
    }

    /**
     * Every byte a download would fetch now: the model files still missing,
     * plus ONNX Runtime and its bridge when they are delivered on demand and
     * not here yet. On a first download the runtime is most of it, so this is
     * what the settings row and the mobile-data question quote, not the
     * model's own size. A Play split reports no size before it starts and
     * counts as nothing here.
     */
    fun bytesToFetch(context: Context): Long =
        missingModelBytes(context.applicationContext.noBackupFilesDir) + runtimeBytesToFetch()

    private fun missingModelBytes(dir: File): Long = IndicXlitModels.Part.entries
        .filter { IndicXlitModels.file(dir, it).let { f -> !f.isFile || f.length() != it.bytes } }
        .sumOf { it.bytes }

    private fun runtimeBytesToFetch(): Long =
        if (FeatureModules.onnx.installed) 0L else (FeatureModules.onnx as? DownloadedModuleGate)?.missingBytes ?: 0L

    /** Whether the model and what runs it are both here. Never downloads anything. */
    fun ready(context: Context): Boolean =
        FeatureModules.onnx.installed && IndicXlitModels.isDownloaded(context.noBackupFilesDir)

    /** Downloads the pack and the runtime. Idempotent. */
    fun start(context: Context) {
        val dir = context.applicationContext.noBackupFilesDir
        synchronized(this) {
            if (job?.isActive == true) return
            val missing = IndicXlitModels.Part.entries.filter {
                IndicXlitModels.file(dir, it).let { f -> !f.isFile || f.length() != it.bytes }
            }
            val modelBytes = missing.sumOf { it.bytes }
            // The runtime comes first and the model after it, under one bar:
            // the model's bytes are counted on top of the runtime's, so the
            // bar never runs back to the start when the second part begins.
            var runtimeBytes = runtimeBytesToFetch()
            _status.value = Status.Downloading(0, runtimeBytes + modelBytes)
            FeatureModules.onnx.requestInstall()
            job = scope.launch {
                try {
                    val moduleHere = FeatureModules.onnx.awaitInstalled { bytes, total ->
                        if (total > 0) {
                            runtimeBytes = total
                            _status.value = Status.Downloading(bytes, total + modelBytes)
                        }
                    }
                    if (!moduleHere) throw FailedException(R.string.core_intel_xlit_module_error)
                    download(dir, missing, alreadyFetched = runtimeBytes)
                    _status.value = Status.Downloaded
                } catch (e: CancellationException) {
                    _status.value = Status.NotDownloaded
                    throw e
                } catch (e: FailedException) {
                    _status.value = Status.Failed(e.messageRes, e.messageArg)
                } catch (_: Exception) {
                    _status.value = Status.Failed(CommonR.string.common_error_network)
                }
            }
        }
    }

    /** Deletes the pack, for the storage screen; the keyboard drops its readings with it. */
    fun delete(context: Context) {
        synchronized(this) { job?.cancel() }
        val dir = context.noBackupFilesDir
        for (part in IndicXlitModels.Part.entries) {
            IndicXlitModels.file(dir, part).delete()
            IndicXlitModels.partFile(dir, part).delete()
        }
        _status.value = Status.NotDownloaded
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    /** Fetches [parts], reporting them after the [alreadyFetched] bytes of the runtime. */
    private suspend fun download(dir: File, parts: List<IndicXlitModels.Part>, alreadyFetched: Long) {
        val folder = IndicXlitModels.dir(dir).apply { mkdirs() }
        val total = parts.sumOf { it.bytes }
        val staged = parts.sumOf { IndicXlitModels.partFile(dir, it).length().coerceAtMost(it.bytes) }
        if (StatFs(folder.path).availableBytes < total - staged + SPACE_MARGIN_BYTES) {
            val neededMb = ((total - staged + SPACE_MARGIN_BYTES) / 1e6).roundToInt()
            throw FailedException(R.string.core_intel_ocr_download_error_no_space, neededMb.toString())
        }
        var done = alreadyFetched
        val grandTotal = alreadyFetched + total
        for (part in parts) {
            val url = IndicXlitModels.url(part) ?: throw FailedException(R.string.core_intel_semantic_no_source)
            val partFile = IndicXlitModels.partFile(dir, part)
            fetch(url, part.bytes, partFile) { written -> _status.value = Status.Downloading(done + written, grandTotal) }
            if (sha256(partFile) != part.sha256) {
                partFile.delete()
                throw FailedException(R.string.core_intel_ocr_download_error_mismatch)
            }
            val target = IndicXlitModels.file(dir, part)
            target.delete()
            if (!partFile.renameTo(target)) throw IOException("could not move ${part.fileName} into place")
            done += part.bytes
        }
    }

    /** Fetches [url] into [target], resuming a partial one, never past [size]. */
    private suspend fun fetch(url: String, size: Long, target: File, onBytes: (Long) -> Unit) {
        val resumeFrom = target.length().takeIf { it in 1 until size } ?: 0L
        if (resumeFrom == 0L) target.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        val call = NetLog.call(NetSource.DOWNLOAD_XLIT, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            val status = connection.responseCode
            call.status = status
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw FailedException(R.string.core_intel_ocr_download_error_http, status.toString())
            }
            val from = if (status == HttpURLConnection.HTTP_PARTIAL) resumeFrom else 0L
            var written = from
            var lastUpdate = 0L
            call.countIn(connection.inputStream).use { input ->
                RandomAccessFile(target, "rw").use { out ->
                    out.setLength(from)
                    out.seek(from)
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        // Longer than pinned is not the pinned file; stop paying for it.
                        if (written + read > size) {
                            target.delete()
                            throw FailedException(R.string.core_intel_ocr_download_error_mismatch)
                        }
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            onBytes(written)
                        }
                    }
                }
            }
            if (written != size) throw FailedException(R.string.core_intel_ocr_download_error_interrupted)
        } catch (t: Throwable) {
            call.fail(t)
            throw t
        } finally {
            connection.disconnect()
            call.end()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
