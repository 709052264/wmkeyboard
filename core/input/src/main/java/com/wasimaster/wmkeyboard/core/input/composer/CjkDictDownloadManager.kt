package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.input.R
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.security.MessageDigest
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
 * Downloads [CjkDictCatalog] packs into [CjkDictStore]'s layout, resumably.
 *
 * Modeled on `LocalLlmDownloadManager` (process singleton, own IO scope,
 * [FileDownloader]'s resumable `.part`) but for small files, so it adds a SHA-256 verify before
 * the finished file is moved into place — a corrupt conversion table would
 * silently mistranslate, which is worse than falling back to the bundled base.
 * No Hugging Face token or gating: packs are on plain public hosting.
 */
object CjkDictDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** [total] is -1 while unknown (no Content-Length yet). */
        data class Downloading(val bytes: Long, val total: Long) : DownloadStatus

        /** Cancelled or interrupted with a `.part` on disk — resumable. */
        data class Paused(val bytes: Long, val total: Long) : DownloadStatus
        data object Downloaded : DownloadStatus

        /**
         * [messageRes] is the text to show. It is a resource id, not a string,
         * so the settings UI resolves it in the language the user reads.
         * [messageArg] fills the one format argument of the strings that have
         * one, and is null for the strings that have none.
         */
        data class Failed(
            val reason: FailReason,
            @StringRes val messageRes: Int,
            val messageArg: String? = null,
        ) : DownloadStatus
    }

    enum class FailReason { NETWORK, NO_SPACE, CHECKSUM, UNAVAILABLE, OTHER }

    private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeId: String? = null

    /**
     * Packs asked for by [startAll] while another was downloading, in the
     * order asked. One file at a time stays the rule — the packs share one
     * `.part` discipline and one progress row each — so the rest wait here
     * and go one after another as each finishes.
     */
    private val waiting = ConcurrentLinkedQueue<Pair<File, CjkDictPack>>()

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Pack id → status for every catalog entry touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    val isBusy: Boolean get() = activeJob?.isActive == true

    /** Seeds [states] from disk; call when the pack manager UI appears. */
    fun refresh(filesDir: File) {
        _states.update { current ->
            CjkDictCatalog.allPacks.associate { pack ->
                val active = current[pack.id]
                pack.id to when {
                    pack.id == activeId && isBusy && active != null -> active
                    waiting.any { it.second.id == pack.id } && active != null -> active
                    CjkDictStore.isDownloaded(filesDir, pack) -> DownloadStatus.Downloaded
                    CjkDictStore.partFile(filesDir, pack).isFile -> DownloadStatus.Paused(
                        FileDownloader.bytesDone(CjkDictStore.partFile(filesDir, pack)), pack.sizeBytes,
                    )
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    /**
     * Fetches every one of [packs] not already on the device, one after
     * another: what switching on a language or a layout that converts asks
     * for, where [start] alone would drop all but the first on the floor.
     * A queued pack reads as a download at its first byte, so its row and its
     * notification say it is on its way rather than that nothing happened.
     */
    fun startAll(filesDir: File, packs: Collection<CjkDictPack>) {
        for (pack in packs) {
            if (!pack.available || CjkDictStore.isDownloaded(filesDir, pack)) continue
            if (pack.id == activeId || waiting.any { it.second.id == pack.id }) continue
            if (isBusy) {
                waiting.add(filesDir to pack)
                set(pack.id, DownloadStatus.Downloading(FileDownloader.bytesDone(CjkDictStore.partFile(filesDir, pack)), pack.sizeBytes))
            } else {
                start(filesDir, pack)
            }
        }
    }

    /** The next queued pack, once the one before it has settled either way. */
    private fun startNext() {
        val (filesDir, pack) = waiting.poll() ?: return
        if (CjkDictStore.isDownloaded(filesDir, pack)) {
            set(pack.id, DownloadStatus.Downloaded)
            startNext()
        } else {
            start(filesDir, pack)
        }
    }

    /** Empties the queue, each pack back to whatever is on disk for it. */
    private fun dropWaiting() {
        while (true) {
            val (filesDir, pack) = waiting.poll() ?: return
            val part = CjkDictStore.partFile(filesDir, pack)
            set(pack.id, if (part.isFile) DownloadStatus.Paused(FileDownloader.bytesDone(part), pack.sizeBytes) else DownloadStatus.NotDownloaded)
        }
    }

    fun start(filesDir: File, pack: CjkDictPack) {
        if (isBusy) return
        if (!pack.available) {
            set(
                pack.id,
                DownloadStatus.Failed(
                    FailReason.UNAVAILABLE,
                    R.string.core_input_cjk_download_error_unavailable,
                ),
            )
            return
        }
        activeId = pack.id
        val part = CjkDictStore.partFile(filesDir, pack)
        set(pack.id, DownloadStatus.Downloading(FileDownloader.bytesDone(part), pack.sizeBytes))
        val job = scope.launch {
            try {
                downloadInto(pack, part)
                verifyChecksum(pack, part)
                check(part.renameTo(CjkDictStore.packFile(filesDir, pack))) {
                    "could not move the finished download into place"
                }
                CjkDictStore.dropLegacy(filesDir, pack)
                set(pack.id, DownloadStatus.Downloaded)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (part.isFile) set(pack.id, DownloadStatus.Paused(FileDownloader.bytesDone(part), pack.sizeBytes))
                throw e
            } catch (e: FailedException) {
                // A checksum failure means the .part is garbage — drop it so a
                // retry starts clean instead of resuming a poisoned file.
                if (e.reason == FailReason.CHECKSUM) FileDownloader.discard(part)
                set(pack.id, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
            } catch (e: Exception) {
                // The system writes its own message in English only, so it goes
                // in as the argument of a translated sentence, never on its own.
                val detail = e.message.orEmpty()
                set(
                    pack.id,
                    if (detail.isBlank()) {
                        DownloadStatus.Failed(
                            FailReason.NETWORK,
                            R.string.core_input_cjk_download_error_network,
                        )
                    } else {
                        DownloadStatus.Failed(
                            FailReason.NETWORK,
                            R.string.core_input_cjk_download_error_network_detail,
                            detail,
                        )
                    },
                )
            } finally {
                activeId = null
            }
        }
        activeJob = job
        // Runs once the job is complete, so [isBusy] already says no. A cancel
        // is the user stopping the download, and with it the queue.
        job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) dropWaiting() else startNext()
        }
    }

    /**
     * Installs [pack] from a copy the user fetched some other way. The
     * checksum a download must pass is the one this must pass too, so a
     * mistyped or truncated file is refused rather than put in front of the
     * composer. Runs on the caller's thread.
     */
    suspend fun install(filesDir: File, pack: CjkDictPack, file: File) {
        check(!isBusy) { "a download is running" }
        val part = CjkDictStore.partFile(filesDir, pack)
        part.parentFile?.mkdirs()
        try {
            file.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            verifyChecksum(pack, part)
            val final = CjkDictStore.packFile(filesDir, pack)
            final.delete()
            check(part.renameTo(final)) { "could not move the dictionary into place" }
            CjkDictStore.dropLegacy(filesDir, pack)
            set(pack.id, DownloadStatus.Downloaded)
        } finally {
            FileDownloader.discard(part)
        }
    }

    fun cancel() {
        if (!isBusy) dropWaiting()
        activeJob?.cancel()
    }

    /**
     * Stops [pack]: its download if it is the one running (which, like
     * [cancel], also empties the queue behind it), or only its place in the
     * queue otherwise, leaving the running download alone. The partial file
     * stays for a later resume.
     */
    fun cancel(pack: CjkDictPack) {
        if (pack.id == activeId) {
            activeJob?.cancel()
            return
        }
        if (waiting.removeIf { it.second.id == pack.id }) {
            set(pack.id, DownloadStatus.NotDownloaded)
        }
    }

    fun delete(filesDir: File, pack: CjkDictPack) {
        waiting.removeIf { it.second.id == pack.id }
        if (pack.id == activeId) cancel()
        CjkDictStore.delete(filesDir, pack)
        set(pack.id, DownloadStatus.NotDownloaded)
    }

    private fun set(id: String, status: DownloadStatus) {
        _states.update { it + (id to status) }
    }

    /**
     * A failure with a message the user can read. The message travels as a
     * resource id so the settings UI resolves it, not this object, which has
     * no context and no locale of its own.
     */
    private class FailedException(
        val reason: FailReason,
        @StringRes val messageRes: Int,
        val messageArg: String? = null,
    ) : IOException(reason.name)

    private suspend fun downloadInto(pack: CjkDictPack, part: File) {
        part.parentFile?.mkdirs()
        val resumeFrom = FileDownloader.bytesDone(part).coerceAtMost(pack.sizeBytes)

        val free = StatFs(part.parentFile!!.path).availableBytes
        if (free < pack.sizeBytes - resumeFrom + SPACE_MARGIN_BYTES) {
            throw FailedException(
                FailReason.NO_SPACE,
                R.string.core_input_cjk_download_error_no_space,
            )
        }

        // FileDownloader asks for the bytes as they are on the server
        // (Accept-Encoding: identity): the packs are served gzipped, and a
        // range counted in decoded bytes would land mid-stream. The server's
        // length is the pack's; its SHA-256 decides after.
        try {
            FileDownloader.fetch(pack.url, part, null, NetSource.DOWNLOAD_CJK, maxBytes = pack.sizeBytes) { written ->
                set(pack.id, DownloadStatus.Downloading(written, pack.sizeBytes))
            }
        } catch (e: DownloadException) {
            throw when (e) {
                is DownloadException.Http -> FailedException(
                    FailReason.OTHER,
                    R.string.core_input_cjk_download_error_http,
                    e.status.toString(),
                )
                is DownloadException.Interrupted ->
                    FailedException(FailReason.NETWORK, R.string.core_input_cjk_download_error_truncated)
                is DownloadException.Blocked ->
                    FailedException(FailReason.NETWORK, CommonR.string.common_error_download_held)
                is DownloadException.WrongSize, is DownloadException.Mismatch ->
                    FailedException(FailReason.CHECKSUM, R.string.core_input_cjk_download_error_checksum)
            }
        }
    }

    /** SHA-256 the finished `.part`; a mismatch is a hard failure. */
    private suspend fun verifyChecksum(pack: CjkDictPack, part: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!IntegrityChecks.accept(actual.equals(pack.sha256, ignoreCase = true), pack.path)) {
            throw FailedException(
                FailReason.CHECKSUM,
                R.string.core_input_cjk_download_error_checksum,
            )
        }
    }
}
