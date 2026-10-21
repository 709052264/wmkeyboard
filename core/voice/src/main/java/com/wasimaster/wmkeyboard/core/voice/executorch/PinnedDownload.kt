package com.wasimaster.wmkeyboard.core.voice.executorch

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.IOException

/**
 * One file of a model, fetched from a pinned URL through [FileDownloader]:
 * parallel ranges into a `.part` beside the target that resumes piece by
 * piece, and the move into place only once the bytes hash to what the catalog
 * says. [EtDownloadManager] and the speech-out voices
 * ([com.wasimaster.wmkeyboard.core.voice.speech.MmsVoiceDownloads]) share it.
 */
internal object PinnedDownload {

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
        try {
            FileDownloader.fetchVerified(url, target, bytes, sha256, source, what = path, onBytes = onProgress)
        } catch (e: DownloadException) {
            throw failure(e)
        }
    }

    /** The screen's words for [e]. */
    fun failure(e: DownloadException): FailedException = when (e) {
        is DownloadException.Http -> FailedException(R.string.core_voice_download_http_error, e.status.toString())
        is DownloadException.Interrupted -> FailedException(R.string.core_voice_download_interrupted_error)
        is DownloadException.Blocked -> FailedException(CommonR.string.common_error_download_held)
        is DownloadException.WrongSize, is DownloadException.Mismatch ->
            FailedException(R.string.core_voice_whistle_checksum_error)
    }
}
