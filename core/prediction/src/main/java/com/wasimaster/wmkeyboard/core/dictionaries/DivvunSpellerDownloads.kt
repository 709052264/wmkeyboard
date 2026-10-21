package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.DivvunBackend
import com.wasimaster.wmkeyboard.core.prediction.DivvunRuntime
import java.io.File
import java.io.IOException
import java.io.InputStream
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches a language's [DivvunSpellerCatalog] speller, or takes one the user
 * imports, into `dict/<langId>/speller.bhfst` (or `.zhfst`), where
 * [DivvunRuntime] finds it.
 *
 * Download: the package streams to a `.part` beside the speller while its
 * SHA-256 is taken, is refused unless that matches the pin, and is unpacked by
 * the library into a second `.part`, which must open as a speller before it
 * is renamed into place. Presence is validity, as with the word lists; the
 * package is deleted once unpacked. Never automatic: a speller is tens of
 * megabytes on the phone, so it is fetched only when the user asks, from the
 * language's Dictionaries group. Unpacking needs the library, so the row
 * offers the download only once the spell-checking module is on the device.
 */
object DivvunSpellerDownloads {

    sealed interface Status {
        data object NotInstalled : Status

        /** Transferring ([bytes] of [totalBytes]) or, at the end, unpacking. */
        data class Downloading(val bytes: Long, val totalBytes: Long) : Status

        /** On the device: [version] when it came from the catalog, null when the user imported it. */
        data class Installed(val sizeBytes: Long, val version: String?) : Status

        data object Failed : Status
    }

    /** What an import of the user's own file came to. */
    enum class ImportResult {
        INSTALLED,

        /** Not a `.bhfst`, a `.zhfst` or a GiellaLT `.pkt.tar.zst`. */
        NOT_A_SPELLER,

        /** The right kind of file, which the library would not open. */
        BROKEN,

        /** The spelling library is not on the device, so nothing can be checked or unpacked. */
        NO_LIBRARY,
    }
    private const val VERSION_NAME = "speller.version"
    private const val PACKAGE_PART = "speller.pkt.part"
    private const val IMPORT_PART = "speller.import.part"

    private val BOX_MAGIC = byteArrayOf(0xFF.toByte(), 'B'.code.toByte(), 'O'.code.toByte(), 'X'.code.toByte())
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One speller at a time: each is tens of megabytes of disk while it unpacks. */
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a language id once its speller is installed or removed, for the keyboard to reopen it. */
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
        val file = DivvunRuntime.spellerFile(filesDir, langId) ?: return null
        val version = runCatching { versionFile(filesDir, langId).readText().trim() }.getOrNull()?.ifEmpty { null }
        return Status.Installed(file.length(), version)
    }

    private fun versionFile(filesDir: File, langId: String) =
        File(DivvunRuntime.languageDir(filesDir, langId), VERSION_NAME)

    /** Fetches [langId]'s catalogued speller, replacing whatever speller it had. */
    fun start(filesDir: File, langId: String) {
        val entry = DivvunSpellerCatalog.forLanguage(langId) ?: return
        synchronized(jobs) {
            if (jobs[langId]?.isActive == true) return
            set(langId, Status.Downloading(0L, entry.packedBytes))
            jobs[langId] = scope.launch {
                try {
                    gate.withLock { download(filesDir, entry, DivvunRuntime.backend) }
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

    /** Removes [langId]'s speller, downloaded or imported. */
    fun delete(filesDir: File, langId: String) {
        cancel(langId)
        scope.launch {
            DivvunRuntime.bhfstFile(filesDir, langId).delete()
            DivvunRuntime.zhfstFile(filesDir, langId).delete()
            versionFile(filesDir, langId).delete()
            cleanUp(filesDir, langId)
            set(langId, Status.NotInstalled)
            _completions.tryEmit(langId)
        }
    }

    private fun cleanUp(filesDir: File, langId: String) {
        val dir = DivvunRuntime.languageDir(filesDir, langId)
        FileDownloader.discard(File(dir, PACKAGE_PART))
        File(dir, IMPORT_PART).delete()
        File(dir, "speller.bhfst.part").delete()
        File(dir, "speller.zhfst.part").delete()
    }

    private suspend fun download(filesDir: File, entry: DivvunSpellerEntry, backend: DivvunBackend) {
        if (!DivvunRuntime.moduleReady || !DivvunRuntime.available) throw IOException("no spelling library")
        val langId = entry.languageId
        val dir = DivvunRuntime.languageDir(filesDir, langId).apply { mkdirs() }
        val packed = File(dir, PACKAGE_PART)
        // The server's length is the package's; the pin caps it and the hash decides.
        FileDownloader.fetch(entry.url, packed, null, NetSource.DOWNLOAD_WORDLIST, maxBytes = entry.packedBytes) {
            set(langId, Status.Downloading(it, entry.packedBytes))
        }
        val got = FileDownloader.sha256(packed)
        if (got != entry.sha256) throw IOException("SHA-256 mismatch for ${entry.url}: $got")
        val target = DivvunRuntime.bhfstFile(filesDir, langId)
        val part = File(dir, target.name + ".part")
        backend.unpack(packed.path, entry.member, part.path)?.let { throw IOException(it) }
        packed.delete()
        // The .part has no extension the library knows, so it is checked under its real name.
        install(filesDir, langId, part, target, backend) ?: throw IOException("${entry.member} does not open")
        versionFile(filesDir, langId).writeText(entry.version)
    }

    /**
     * Imports a speller the user picked, recognised by its first bytes rather
     * than its name: a `.bhfst` (box archive), a `.zhfst` (zip) or a whole
     * GiellaLT `.pkt.tar.zst` package, the form GiellaLT's nightly releases
     * come in. Replaces [langId]'s speller when it opens. Blocks; call it
     * off the main thread. [input] is closed.
     */
    fun import(filesDir: File, langId: String, input: InputStream): ImportResult {
        if (!DivvunRuntime.moduleReady || !DivvunRuntime.available) {
            input.close()
            return ImportResult.NO_LIBRARY
        }
        val backend = DivvunRuntime.backend
        val dir = DivvunRuntime.languageDir(filesDir, langId).apply { mkdirs() }
        val copied = File(dir, IMPORT_PART)
        try {
            input.use { source -> copied.outputStream().use { source.copyTo(it, 1 shl 16) } }
            val head = ByteArray(4)
            copied.inputStream().use { it.read(head) }
            val (target, part) = when {
                head.contentEquals(BOX_MAGIC) ->
                    DivvunRuntime.bhfstFile(filesDir, langId).let { it to copied }
                head.contentEquals(ZIP_MAGIC) ->
                    DivvunRuntime.zhfstFile(filesDir, langId).let { it to copied }
                head.contentEquals(ZSTD_MAGIC) -> {
                    val target = DivvunRuntime.bhfstFile(filesDir, langId)
                    val unpacked = File(dir, target.name + ".part")
                    if (backend.unpack(copied.path, "", unpacked.path) != null) return ImportResult.BROKEN
                    copied.delete()
                    target to unpacked
                }
                else -> return ImportResult.NOT_A_SPELLER
            }
            install(filesDir, langId, part, target, backend) ?: return ImportResult.BROKEN
            versionFile(filesDir, langId).delete()
            set(langId, installed(filesDir, langId) ?: Status.NotInstalled)
            _completions.tryEmit(langId)
            return ImportResult.INSTALLED
        } catch (_: IOException) {
            return ImportResult.BROKEN
        } finally {
            cleanUp(filesDir, langId)
        }
    }

    /**
     * Moves [part] to [target] once the library opens it there, dropping the
     * other kind of archive [langId] had. The old speller is set aside, not
     * deleted, until the new one has opened: a broken file must not cost the
     * user the speller that worked. Null when the new one did not open.
     */
    private fun install(filesDir: File, langId: String, part: File, target: File, backend: DivvunBackend): File? {
        val previous = DivvunRuntime.spellerFile(filesDir, langId)
        val aside = previous?.let { File(it.parentFile, it.name + ".old") }
        if (previous != null && !previous.renameTo(aside!!)) return null
        if (!part.renameTo(target)) {
            aside?.renameTo(previous)
            return null
        }
        val handle = backend.open(target.path, (target.parentFile ?: filesDir).path)
        if (handle == 0L) {
            target.delete()
            aside?.renameTo(previous)
            return null
        }
        backend.close(handle)
        aside?.delete()
        // A .bhfst replacing a .zhfst, or the other way: only one may stand.
        listOf(DivvunRuntime.bhfstFile(filesDir, langId), DivvunRuntime.zhfstFile(filesDir, langId))
            .filter { it != target }
            .forEach { it.delete() }
        return target
    }
}
