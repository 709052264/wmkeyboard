package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.VoikkoBackend
import com.wasimaster.wmkeyboard.core.prediction.VoikkoRuntime
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fetches a language's [VoikkoDictionaryCatalog] dictionary, or takes one the
 * user imports, into `dict/<langId>/voikko`, where [VoikkoRuntime] finds it.
 *
 * Download: the zip streams to a `.part` while its SHA-256 is taken, is
 * refused unless that matches the pin, and is unpacked into a `.part` folder,
 * which must load in libvoikko before it replaces the old one. Import takes
 * any zip holding a `5/mor-<variant>/` folder and checks it the same way.
 * Both need the library, so the language's row offers them only once the
 * spell-checking module is on the device. Never automatic.
 */
object VoikkoDictionaryDownloads {

    sealed interface Status {
        data object NotInstalled : Status

        data class Downloading(val bytes: Long, val totalBytes: Long) : Status

        /** On the device: [version] when it came from the catalog, null when the user imported it. */
        data class Installed(val sizeBytes: Long, val version: String?) : Status

        data object Failed : Status
    }

    /** What an import of the user's own file came to. */
    enum class ImportResult {
        INSTALLED,

        /** Not a zip, or a zip with no `5/mor-<variant>/index.txt` in it. */
        NOT_A_DICTIONARY,

        /** The right shape, but libvoikko would not load it. */
        BROKEN,

        /** The spelling library is not on the device, so nothing can be checked. */
        NO_LIBRARY,
    }
    private const val VERSION_NAME = "voikko.version"

    /** Ten times the Finnish dictionary: room for a bigger variant, not for a zip bomb. */
    private const val MAX_UNPACKED_BYTES = 64L shl 20

    /** `5/mor-<variant>/<file>`, anywhere in the zip (a folder around it is fine). */
    private val ENTRY = Regex("""(?:.*/)?${VoikkoRuntime.FORMAT_DIR}/(mor-[A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a language id once its dictionary is installed or removed, for the keyboard to reopen it. */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Language id -> status, for every language asked about so far. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    private fun set(langId: String, status: Status) {
        _states.update { it + (langId to status) }
    }

    /** What is on disk for [langId], unless a download of it is under way. */
    fun refresh(filesDir: File, langId: String) {
        _states.update { current ->
            val live = current[langId]
            val status = when {
                live is Status.Downloading -> live
                else -> installed(filesDir, langId) ?: if (live == Status.Failed) live else Status.NotInstalled
            }
            current + (langId to status)
        }
    }

    private fun installed(filesDir: File, langId: String): Status.Installed? {
        val dir = VoikkoRuntime.dictionaryDir(filesDir, langId)
        val files = VoikkoRuntime.dictionaryFiles(dir).ifEmpty { return null }
        val version = runCatching { File(dir, VERSION_NAME).readText().trim() }.getOrNull()?.ifEmpty { null }
        return Status.Installed(files.sumOf { it.length() }, version)
    }

    private fun partDir(filesDir: File, langId: String) =
        VoikkoRuntime.dictionaryDir(filesDir, langId).let { File(it.parentFile, it.name + ".part") }

    private fun zipPart(filesDir: File, langId: String) =
        VoikkoRuntime.dictionaryDir(filesDir, langId).let { File(it.parentFile, it.name + ".zip.part") }

    /** Fetches [langId]'s catalogued dictionary, replacing whatever it had. */
    fun start(filesDir: File, langId: String) {
        val entry = VoikkoDictionaryCatalog.forLanguage(langId) ?: return
        synchronized(jobs) {
            if (jobs[langId]?.isActive == true) return
            set(langId, Status.Downloading(0L, entry.packedBytes))
            jobs[langId] = scope.launch {
                try {
                    download(filesDir, entry, VoikkoRuntime.backend)
                    set(langId, installed(filesDir, langId) ?: Status.Failed)
                    _completions.tryEmit(langId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    set(langId, Status.Failed)
                } finally {
                    cleanUp(filesDir, langId)
                    if (_states.value[langId] is Status.Downloading) {
                        set(langId, installed(filesDir, langId) ?: Status.NotInstalled)
                    }
                }
            }
        }
    }

    fun cancel(langId: String) {
        synchronized(jobs) { jobs[langId]?.cancel() }
    }

    /** Removes [langId]'s dictionary, downloaded or imported. */
    fun delete(filesDir: File, langId: String) {
        cancel(langId)
        scope.launch {
            VoikkoRuntime.dictionaryDir(filesDir, langId).deleteRecursively()
            cleanUp(filesDir, langId)
            set(langId, Status.NotInstalled)
            _completions.tryEmit(langId)
        }
    }

    private fun cleanUp(filesDir: File, langId: String) {
        FileDownloader.discard(zipPart(filesDir, langId))
        partDir(filesDir, langId).deleteRecursively()
    }

    private suspend fun download(filesDir: File, entry: VoikkoDictionaryEntry, backend: VoikkoBackend) {
        if (!VoikkoRuntime.moduleReady || !VoikkoRuntime.available) throw IOException("no Voikko library")
        val langId = entry.languageId
        val packed = zipPart(filesDir, langId).apply { parentFile?.mkdirs() }
        val url = entry.url
        // The server's length is the archive's; the pin caps it and the hash decides.
        FileDownloader.fetch(url, packed, null, NetSource.DOWNLOAD_WORDLIST, maxBytes = entry.packedBytes) {
            set(langId, Status.Downloading(it, entry.packedBytes))
        }
        val got = FileDownloader.sha256(packed)
        if (got != entry.sha256) throw IOException("SHA-256 mismatch for $url: $got")
        val part = partDir(filesDir, langId)
        packed.inputStream().use { if (!unpack(it, part)) throw IOException("$url holds no dictionary") }
        packed.delete()
        File(part, VERSION_NAME).writeText(entry.version)
        if (!install(filesDir, langId, part, backend)) throw IOException("$url does not load")
    }

    /**
     * Imports a dictionary the user picked: a zip with a `5/mor-<variant>/`
     * folder in it, the layout libvoikko reads and voikko-fi's
     * `make vvfst-install` writes. Replaces [langId]'s dictionary when it
     * loads. Blocks; call it off the main thread. [input] is closed.
     */
    fun import(filesDir: File, langId: String, input: InputStream): ImportResult {
        if (!VoikkoRuntime.moduleReady || !VoikkoRuntime.available) {
            input.close()
            return ImportResult.NO_LIBRARY
        }
        val part = partDir(filesDir, langId)
        try {
            val unpacked = input.use { unpack(it, part) }
            if (!unpacked) return ImportResult.NOT_A_DICTIONARY
            if (!install(filesDir, langId, part, VoikkoRuntime.backend)) return ImportResult.BROKEN
            set(langId, installed(filesDir, langId) ?: Status.NotInstalled)
            _completions.tryEmit(langId)
            return ImportResult.INSTALLED
        } catch (_: ZipException) {
            return ImportResult.NOT_A_DICTIONARY
        } catch (_: IOException) {
            return ImportResult.BROKEN
        } finally {
            cleanUp(filesDir, langId)
        }
    }

    /**
     * Unpacks the dictionary files of the zip in [input] into [target]
     * (emptied first), and nothing else: every other entry is skipped, so no
     * name in the zip can write outside it. False when it held no
     * `index.txt`.
     */
    private fun unpack(input: InputStream, target: File): Boolean {
        target.deleteRecursively()
        var total = 0L
        var indexes = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val match = ENTRY.matchEntire(entry.name)
                if (entry.isDirectory || match == null) continue
                val (variant, name) = match.destructured
                val file = File(File(File(target, VoikkoRuntime.FORMAT_DIR), variant), name)
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED_BYTES) throw IOException("dictionary larger than $MAX_UNPACKED_BYTES bytes")
                        out.write(buffer, 0, n)
                    }
                }
                if (name == "index.txt") indexes++
            }
        }
        return indexes > 0
    }

    /**
     * Moves [part] into place once libvoikko loads it there. The old folder
     * is set aside, not deleted, until the new one has loaded: a broken file
     * must not cost the user the dictionary that worked.
     */
    private fun install(filesDir: File, langId: String, part: File, backend: VoikkoBackend): Boolean {
        if (!VoikkoRuntime.opens(backend, part, langId)) return false
        val target = VoikkoRuntime.dictionaryDir(filesDir, langId)
        val aside = File(target.parentFile, target.name + ".old")
        aside.deleteRecursively()
        if (target.exists() && !target.renameTo(aside)) return false
        if (!part.renameTo(target)) {
            aside.renameTo(target)
            return false
        }
        aside.deleteRecursively()
        return true
    }
}
