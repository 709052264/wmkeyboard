package com.wasimaster.wmkeyboard.core.media

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Copies an image the keyboard produced (camera capture, document scan,
 * generated QR code, an edited photo) into the shared gallery under
 * Pictures/WM Keyboard, or beside the photo it came from, and writes an
 * edited photo back over its original.
 *
 * The two storage eras need different handling:
 *
 * - API 29+: the store owns the file layout, so RELATIVE_PATH picks the
 *   album and IS_PENDING hides the row until every byte has landed — a
 *   half-written image never shows up in the gallery. No permission needed,
 *   an app may always write its own media.
 * - API 24-28: no scoped storage. We build the path ourselves, write
 *   through the row's DATA column, and then hand the file to the media
 *   scanner, which is the only thing that makes it visible to gallery apps.
 *   This era needs WRITE_EXTERNAL_STORAGE at runtime.
 */
object GallerySaver {

    /** Album folder inside the device's Pictures directory. */
    private const val ALBUM = "WM Keyboard"

    /** Whether a save can go ahead right now, permission-wise. */
    fun canSave(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Writes [file] into the gallery. Returns the new media URI, or null if
     * the save failed (no permission, no space, store rejected the insert).
     * Safe to call off the main thread; callers should.
     */
    fun save(context: Context, file: File, mimeType: String, displayName: String, folder: String? = null): Uri? {
        if (!canSave(context)) return null
        if (!file.exists() || file.length() == 0L) return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // [folder] is a wish ("DCIM/Camera/", beside the photo it came
            // from); the store may refuse it, and then the album takes it.
            folder?.let { saveScoped(context, file, mimeType, displayName, it) }
                ?: saveScoped(context, file, mimeType, displayName, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        } else {
            saveLegacy(context, file, mimeType, displayName)
        }
    }

    /** How a write over a library photo went. */
    sealed interface Overwrite {
        data object Done : Overwrite

        /** Android wants the user's yes first: launch [sender], then [write] again. */
        data class NeedsConsent(val sender: IntentSender) : Overwrite

        /** Below Android 10, writing another app's photo is the storage permission's. */
        data object NeedsPermission : Overwrite
        data object Failed : Overwrite
    }

    /**
     * Writes [file] over the library photo at [uri]. A photo this app did not
     * make takes the user's consent first, which only an activity can ask for:
     * the answer says so, with what to launch.
     */
    fun overwrite(context: Context, uri: Uri, file: File): Overwrite {
        if (!file.exists() || file.length() == 0L) return Overwrite.Failed
        val first = runCatching { write(context, uri, file) }
        if (first.isSuccess) return Overwrite.Done
        val error = first.exceptionOrNull()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                Overwrite.NeedsConsent(MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender)
            }.getOrDefault(Overwrite.Failed)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
                Overwrite.NeedsConsent(error.userAction.actionIntent.intentSender)
            !canSave(context) -> Overwrite.NeedsPermission
            else -> Overwrite.Failed
        }
    }

    /**
     * The bytes of [file] written over [uri]. The stream is opened before
     * anything is cut, so a refused write leaves the photo as it was; the
     * store rescans the file once it is closed.
     */
    fun write(context: Context, uri: Uri, file: File) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("openOutputStream returned null")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    // Guarded by the SDK_INT check in save(); the annotation states that
    // contract so the API-level check can see it too (VOLUME_EXTERNAL_PRIMARY
    // and the scoped-storage insert are Q+).
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(
        context: Context,
        file: File,
        mimeType: String,
        displayName: String,
        folder: String,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        // VOLUME_EXTERNAL_PRIMARY: on Q+ this is the only volume an app is
        // allowed to insert into.
        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null")
        }.isSuccess

        if (!written) {
            // Leaving IS_PENDING=1 behind would consume storage in a row
            // nothing can see until the system reaps it (~7 days).
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    private fun saveLegacy(
        context: Context,
        file: File,
        mimeType: String,
        displayName: String,
    ): Uri? {
        val album = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM,
        )
        if (!album.exists() && !album.mkdirs()) return null

        val target = uniqueFile(album, displayName)
        val copied = runCatching { file.copyTo(target, overwrite = false) }.isSuccess
        if (!copied) return null

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, target.name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            @Suppress("DEPRECATION")
            put(MediaStore.Images.Media.DATA, target.absolutePath)
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()

        // insert() alone does not guarantee the file is indexed pre-Q; the
        // scanner is what actually surfaces it in gallery apps.
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mimeType),
                null,
            )
        }
        return uri
    }

    /** Pre-Q we place the file ourselves, so avoid clobbering an old one. */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var candidate = File(dir, displayName)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$suffix")
            n++
        }
        return candidate
    }
}
