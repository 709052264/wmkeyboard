package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.transliteration.PackedPhoneticTable
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticTables
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Phonetic indexes built ahead of time over the published word lists, and
 * fetched beside them (issue #716).
 *
 * A phonetic language's index ([PhoneticTables]) folds every word of its list
 * under one or more keys. Built on the phone that was a few hundred bytes of
 * heap a word, every time the keyboard started, and Urdu's list ran the
 * keyboard out of memory doing it. The data repo now files each published
 * list once, on a computer, as `data/<lang>/<publishedName>`; the keyboard
 * maps the file and folds only what the user imported themselves.
 *
 * Kept inflated at [DictionaryStore.phoneticFile], so it can be mapped. The
 * download is inflated into a `.part` and checked ([open] has to read it, and
 * it has to come from this build's fold) before it is renamed into place, so
 * presence means valid. A language whose fetch failed (no connection, a build
 * with no internet permission) is not asked again until the keyboard restarts;
 * until then its index is built on the phone, as before.
 */
object PhoneticIndexDownloads {

    private const val USER_AGENT = "WMKeyboard phonetic index downloader"

    /** Far above the largest table (Arabic's, a few tens of MB inflated); a response past it is not one. */
    private const val MAX_BYTES = 256L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a language id once its table has landed and its index should be built again. */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    /** Whether [languageId] has a prebuilt table at all. */
    fun supports(languageId: String): Boolean = PhoneticTables.supports(languageId)

    /** The name [languageId]'s table is published under, which an offline import is recognised by. */
    fun publishedName(languageId: String): String = PhoneticTables.publishedName(languageId)

    /** The language a file named [name] is the published table of, or null. */
    fun languageOfPublishedName(name: String): String? {
        val languageId = name.substringBefore("_phonetic_", missingDelimiterValue = "")
        return languageId.takeIf { it.isNotEmpty() && supports(it) && publishedName(it) == name }
    }

    /** [languageId]'s table, mapped, when one this build reads is on the device. */
    fun open(filesDir: File, languageId: String): PackedPhoneticTable? {
        if (!supports(languageId)) return null
        return PackedPhoneticTable.open(DictionaryStore.phoneticFile(filesDir, languageId))
            ?.takeIf { PhoneticTables.fits(languageId, it) }
    }

    fun isBusy(languageId: String): Boolean = jobs[languageId]?.isActive == true

    /**
     * Fetches [languageId]'s table unless one is here, one is on its way, or
     * the last try failed this process. A table from another fold revision,
     * left by an older version of the app, is fetched over.
     */
    fun start(filesDir: File, languageId: String) {
        if (!supports(languageId) || languageId in failed) return
        synchronized(this) {
            if (isBusy(languageId) || open(filesDir, languageId) != null) return
            jobs[languageId] = scope.launch {
                val ok = runCatching { download(filesDir, languageId) }.isSuccess
                if (ok) _completions.tryEmit(languageId) else failed += languageId
            }
        }
    }

    /**
     * Installs [source], a copy of the published file fetched elsewhere
     * (gzipped as the repo serves it, or inflated), with the same check a
     * download gets. Throws if it is not this build's table for [languageId].
     */
    fun install(filesDir: File, languageId: String, source: File) {
        check(supports(languageId)) { "$languageId has no phonetic index" }
        source.inputStream().buffered().use { raw ->
            raw.mark(2)
            val gzip = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            land(filesDir, languageId, if (gzip) GZIPInputStream(raw, 64 * 1024) else raw)
        }
        failed -= languageId
        _completions.tryEmit(languageId)
    }

    private fun download(filesDir: File, languageId: String) {
        val url = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$languageId/${publishedName(languageId)}")
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WORDLIST, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            GZIPInputStream(netCall.countIn(connection.inputStream), 64 * 1024).use { land(filesDir, languageId, it) }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            netCall.end()
            connection.disconnect()
        }
    }

    /** Writes [input] inflated to the `.part`, checks it, and renames it into place. */
    private fun land(filesDir: File, languageId: String, input: InputStream) {
        val target = DictionaryStore.phoneticFile(filesDir, languageId)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw IOException("larger than any phonetic index")
                    out.write(buffer, 0, read)
                }
            }
            val table = PackedPhoneticTable.open(part)
            check(table != null && PhoneticTables.fits(languageId, table)) { "not a phonetic index for $languageId" }
            // Renamed over, never written into: the keyboard may have the old
            // file mapped, and a mapped file cut short under its reader faults.
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
        } finally {
            part.delete()
        }
    }
}
