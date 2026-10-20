package com.wasimaster.wmkeyboard.core.imagegen

import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
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
import java.util.zip.ZipInputStream
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
 * The sticker generator's downloads: a catalog model's pinned files, the
 * picture filter beside the first one, and the runtime module first wherever
 * it is delivered on demand, so the screen shows one bar for all of it.
 *
 * Each file goes to a `.part` beside it, resumes with a range request where
 * the server allows one, and gets its name only after its SHA-256 matched.
 * An NPU build arrives as Qualcomm's zip, checked whole and then unpacked;
 * the zip is deleted once its files are out.
 */
object ImageGenDownloads {

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Downloaded : Status
        data class Failed(@StringRes val messageRes: Int, val messageArg: String = "") : Status
    }

    private const val SPACE_MARGIN_BYTES = 128L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"
    private const val BUFFER_BYTES = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** By catalog model id. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    fun refresh(context: Context) {
        val dir = context.noBackupFilesDir
        _states.update { current ->
            ImageGenModels.Catalog.entries.associate { model ->
                val running = synchronized(jobs) { jobs[model.id]?.isActive == true }
                model.id to when {
                    running && current[model.id] != null -> current.getValue(model.id)
                    ready(dir, model) -> Status.Downloaded
                    else -> current[model.id] as? Status.Failed ?: Status.NotDownloaded
                }
            }
        }
    }

    private fun ready(dir: File, model: ImageGenModels.Catalog) =
        ImageGenModels.isDownloaded(dir, model) && ImageGenModels.safetyDownloaded(dir)

    /** Whether [model] and its runtime are both here. Never downloads anything. */
    fun ready(context: Context, model: ImageGenModels.Catalog): Boolean =
        model.runsHere && FeatureModules.imagegen.installed && ready(context.noBackupFilesDir, model)

    /** Downloads [model] (and the picture filter, and the runtime module). Idempotent. */
    fun start(context: Context, model: ImageGenModels.Catalog) {
        if (!model.runsHere) return
        val dir = context.applicationContext.noBackupFilesDir
        synchronized(jobs) {
            if (jobs[model.id]?.isActive == true) return
            val parts = missingParts(dir, model)
            val total = parts.sumOf { it.second.bytes }
            set(model.id, Status.Downloading(0, total))
            FeatureModules.imagegen.requestInstall()
            if (model.runtime == ImageRuntime.QNN) FeatureModules.npu.requestInstall()
            jobs[model.id] = scope.launch {
                try {
                    val here = FeatureModules.imagegen.awaitInstalled { bytes, all ->
                        if (all > 0) set(model.id, Status.Downloading(bytes, all + total))
                    }
                    if (!here) throw FailedException(R.string.core_intel_imagegen_module_error)
                    download(dir, model, parts)
                    set(model.id, Status.Downloaded)
                } catch (e: CancellationException) {
                    set(model.id, Status.NotDownloaded)
                    throw e
                } catch (e: FailedException) {
                    set(model.id, Status.Failed(e.messageRes, e.messageArg))
                } catch (_: Exception) {
                    set(model.id, Status.Failed(CommonR.string.common_error_network))
                }
            }
        }
    }

    fun cancel(model: ImageGenModels.Catalog) {
        synchronized(jobs) { jobs[model.id]?.cancel() }
    }

    /** Deletes [model]'s files; the picture filter goes with the last model. */
    fun delete(context: Context, model: ImageGenModels.Catalog) {
        cancel(model)
        val dir = context.noBackupFilesDir
        ImageGenModels.dir(dir, model).deleteRecursively()
        val anyLeft = ImageGenModels.Catalog.entries.any { it != model && ImageGenModels.isDownloaded(dir, it) } ||
            ImageGenImport.installed(context).isNotEmpty()
        if (!anyLeft) ImageGenModels.safetyFile(dir).delete()
        set(model.id, Status.NotDownloaded)
    }

    /** Fetches only the picture filter, for an imported model. */
    suspend fun ensureSafety(context: Context) {
        val dir = context.noBackupFilesDir
        if (ImageGenModels.safetyDownloaded(dir)) return
        val target = ImageGenModels.safetyFile(dir)
        target.parentFile?.mkdirs()
        fetchVerified(ImageGenModels.SAFETY, target) { }
    }

    private fun set(id: String, status: Status) {
        _states.update { it + (id to status) }
    }

    private class FailedException(@StringRes val messageRes: Int, val messageArg: String = "") : IOException()

    /** What is still missing, each with where it goes. */
    private fun missingParts(dir: File, model: ImageGenModels.Catalog): List<Pair<File, ImageGenModels.Part>> {
        val out = ArrayList<Pair<File, ImageGenModels.Part>>()
        for (part in model.parts) {
            val f = ImageGenModels.file(dir, model, part)
            if (!(f.isFile && f.length() == part.bytes)) out.add(f to part)
        }
        model.npuBuild()?.let { build ->
            val unpacked = File(ImageGenModels.dir(dir, model), build.soc)
            if (!File(unpacked, ImageGenModels.STAMP).isFile) out.add(File(ImageGenModels.dir(dir, model), build.zip.fileName) to build.zip)
        }
        if (!ImageGenModels.safetyDownloaded(dir)) out.add(ImageGenModels.safetyFile(dir) to ImageGenModels.SAFETY)
        return out
    }

    private suspend fun download(dir: File, model: ImageGenModels.Catalog, parts: List<Pair<File, ImageGenModels.Part>>) {
        val folder = ImageGenModels.dir(dir, model).apply { mkdirs() }
        val total = parts.sumOf { it.second.bytes }
        // An NPU zip unpacks to about 1.6 times its size, beside it, before the zip goes.
        val unpack = model.npuBuild()?.zip?.bytes?.let { (it * 1.6).toLong() } ?: 0L
        val staged = parts.sumOf { (f, p) -> File(f.path + ".part").length().coerceAtMost(p.bytes) }
        val needed = total - staged + unpack + SPACE_MARGIN_BYTES
        if (StatFs(folder.path).availableBytes < needed) {
            throw FailedException(R.string.core_intel_ocr_download_error_no_space, (needed / 1e6).roundToInt().toString())
        }
        var done = 0L
        for ((target, part) in parts) {
            target.parentFile?.mkdirs()
            fetchVerified(part, target) { written -> set(model.id, Status.Downloading(done + written, total)) }
            done += part.bytes
            if (part.fileName.endsWith(".zip")) {
                val build = model.npuBuild() ?: continue
                unzip(target, File(folder, build.soc), build.entries)
                target.delete()
            }
        }
    }

    private suspend fun fetchVerified(part: ImageGenModels.Part, target: File, onBytes: (Long) -> Unit) {
        val url = ImageGenModels.url(part) ?: throw FailedException(R.string.core_intel_semantic_no_source)
        val staged = File(target.path + ".part")
        fetch(url, part.bytes, staged, onBytes)
        if (sha256(staged) != part.sha256) {
            staged.delete()
            throw FailedException(R.string.core_intel_ocr_download_error_mismatch)
        }
        target.delete()
        if (!staged.renameTo(target)) throw IOException("could not move ${part.fileName} into place")
    }

    /** Unpacks [names] from Qualcomm's zip (one folder inside it) into [into], then stamps it complete. */
    private suspend fun unzip(zip: File, into: File, names: List<String>) {
        val staging = File(into.parentFile, into.name + ".part")
        staging.deleteRecursively()
        staging.mkdirs()
        ZipInputStream(zip.inputStream().buffered(BUFFER_BYTES)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in names) continue
                File(staging, name).outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!names.all { File(staging, it).isFile }) {
            staging.deleteRecursively()
            throw FailedException(R.string.core_intel_ocr_download_error_mismatch)
        }
        File(staging, ImageGenModels.STAMP).writeText(zip.name)
        into.deleteRecursively()
        if (!staging.renameTo(into)) throw IOException("could not move ${into.name} into place")
    }

    /** Fetches [url] into [target], resuming a partial one, never past [size]. */
    private suspend fun fetch(url: String, size: Long, target: File, onBytes: (Long) -> Unit) {
        val resumeFrom = target.length().takeIf { it in 1 until size } ?: 0L
        if (resumeFrom == 0L) target.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        val call = NetLog.call(NetSource.DOWNLOAD_IMAGEGEN, "GET", url, route = NetLog.pathOf(url))
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

    /** This phone's NPU chip, for the screen: null when the NPU model is not offered. */
    val npuChip: String? get() = ImageGenModels.Catalog.SD15_NPU.npuBuild()?.soc
}
