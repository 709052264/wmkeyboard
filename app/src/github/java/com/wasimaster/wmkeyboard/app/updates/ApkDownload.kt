package com.wasimaster.wmkeyboard.app.updates

import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.security.MessageDigest

/** Why a download stopped short, in the terms the card has to explain. */
internal class DownloadFailure(
    val reason: UpdateFailure,
    val keepPartial: Boolean,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Where an update is kept between downloading it and installing it.
 *
 * `noBackupFilesDir` rather than the cache directory, which is where every
 * other download in this app goes. Android empties the cache under storage
 * pressure, and storage pressure is exactly the condition a seventy-megabyte
 * APK creates, so a cached update could vanish in the seconds between
 * finishing and the user pressing Install. The price is that this app has to
 * clean up after itself, which is what [ApkStaging.sweep] is for, and that the
 * file is worth showing on the storage screen.
 */
internal object ApkStaging {

    private const val DIR = "updates"

    fun dir(context: Context): File = File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    fun apkFile(context: Context, candidate: UpdateCandidate): File =
        File(dir(context), candidate.assetName)

    fun partFile(context: Context, candidate: UpdateCandidate): File =
        File(dir(context), candidate.assetName + ".part")

    /**
     * Where [candidate]'s dex metadata is kept, or null when this device has
     * no use for one (see [ReleaseAssets.dexMetadataName]).
     */
    fun dexMetadataFile(context: Context, candidate: UpdateCandidate): File? =
        ReleaseAssets.dexMetadataName(candidate.assetName, Build.VERSION.SDK_INT)
            ?.let { File(dir(context), it) }

    fun listCache(context: Context): File = File(dir(context), "releases.json")

    /**
     * Deletes anything staged that this install can no longer use: a download
     * of an older version, one of the version already running in the same
     * languages build, and any file whose name is not one of ours. The other
     * languages build of the running version is kept: that is the move to
     * every language, waiting to be installed. Runs once per process, before
     * anything else looks at the directory.
     */
    fun sweep(context: Context, installedVersionCode: Int, installedLanguages: String) {
        val keep = listCache(context).name
        dir(context).listFiles()?.forEach { file ->
            if (file.name == keep) return@forEach
            // A paused download's resume map goes with its `.part`.
            val name = file.name.removeSuffix(".ranges").removeSuffix(".part")
            val parsed = ReleaseAssets.parseAssetName(name) ?: ReleaseAssets.parseDexMetadataName(name)
            val used = parsed != null && (
                parsed.versionCode > installedVersionCode ||
                    (parsed.versionCode == installedVersionCode && parsed.languages != installedLanguages)
                )
            if (!used) file.delete()
        }
    }
}

/**
 * Downloads one release asset through [FileDownloader]: parallel ranges into
 * the `.part`, each piece resumed where it stopped. GitHub redirects a release
 * URL to a signed CDN address whose signature expires; the downloader asks
 * the release URL again when that address starts refusing.
 */
internal object ApkDownload {

    private const val BUFFER_BYTES = 256 * 1024

    /**
     * Room needed before starting: the file, plus as much again because
     * Android stages its own copy of it during the install, plus a margin so
     * finishing does not leave the device with nothing left.
     */
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /** Throws when there is not enough room for [sizeBytes] and the install after it. */
    fun requireSpace(dir: File, sizeBytes: Long, alreadyHave: Long) {
        val need = (sizeBytes - alreadyHave).coerceAtLeast(0L) + sizeBytes + SPACE_MARGIN_BYTES
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrNull() ?: return
        if (free < need) throw DownloadFailure(UpdateFailure.NO_SPACE, keepPartial = true)
    }

    /**
     * Fetches [candidate] into [part], continuing whatever is already there.
     *
     * [onProgress] is called with bytes on disk and the expected total a few
     * times a second, which is often enough to look continuous and rare
     * enough not to recompose the card on every buffer.
     */
    suspend fun transfer(
        candidate: UpdateCandidate,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        // The release says how big the file is, so a different length is not
        // the file the release described.
        val size = candidate.sizeBytes.takeIf { it > 0 }
        try {
            FileDownloader.fetch(candidate.url, part, size, NetSource.UPDATES, background = false) { written ->
                onProgress(written, maxOf(size ?: 0L, written))
            }
        } catch (e: DownloadException) {
            throw when (e) {
                is DownloadException.Http -> DownloadFailure(UpdateFailure.NETWORK, keepPartial = false, e)
                is DownloadException.WrongSize, is DownloadException.Mismatch ->
                    DownloadFailure(UpdateFailure.CORRUPT, keepPartial = false, e)
                is DownloadException.Interrupted, is DownloadException.Blocked ->
                    DownloadFailure(UpdateFailure.NETWORK, keepPartial = true, e)
            }
        }
    }

    /**
     * Checks [file] against the length and checksum the release published.
     *
     * The checksum is the reason an update is allowed to install at all, so a
     * release that offers none is not installed on trust: the caller falls back
     * to the checksums file the release workflow attaches, and refuses when
     * even that is missing.
     */
    fun verify(file: File, candidate: UpdateCandidate, sha256: String): Boolean {
        if (candidate.sizeBytes > 0 && file.length() != candidate.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }.getOrElse { return false }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        // The signing-certificate pin in [ApkInstall] still holds when this is let
        // through, so an APK someone else signed is never installed either way.
        return IntegrityChecks.accept(actual == sha256.lowercase(), candidate.assetName)
    }
}
