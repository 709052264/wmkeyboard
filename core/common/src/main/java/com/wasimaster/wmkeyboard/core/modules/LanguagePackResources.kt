package com.wasimaster.wmkeyboard.core.modules

import android.content.Context
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import java.io.File

/**
 * Interface languages delivered on demand: the standard GitHub build carries
 * English strings only, and each other language arrives as a small
 * resource-only APK cut from the all-languages build of the same version
 * (`.github/scripts/language-packs.sh`). Android 11 added [ResourcesLoader],
 * which lets an app add such an APK's resources to its own, so a pack is
 * looked up exactly like a translation the APK shipped with. Below Android 11
 * there is no way in, and those phones see English.
 *
 * One loader holds every pack on the phone, and every Resources it is
 * attached to (the application's, each activity's, the keyboard service's)
 * sees a newly installed pack at once: [reload] swaps the loader's providers.
 *
 * Packs belong to one version: resource IDs change between versions, so a
 * pack is kept under that version's directory and a newer app ignores and
 * removes the older ones.
 */
object LanguagePackResources {
    private const val TAG = "LanguagePacks"

    @Volatile private var loader: Any? = null

    /** Where this version's packs live: `noBackupFilesDir/langpacks/<versionCode>`. */
    fun dir(context: Context): File = File(File(context.noBackupFilesDir, "langpacks"), BuildConfig.VERSION_CODE.toString())

    /** The resource qualifier a language tag's pack is named by: `zh-CN` -> `zh-rCN`, `fr` -> `fr`. */
    fun qualifierOf(tag: String): String {
        val parts = tag.replace('_', '-').split('-')
        val language = parts[0].lowercase()
        val region = parts.getOrNull(1)?.takeIf { it.length == 2 }?.uppercase()
        return if (region == null) language else "$language-r$region"
    }

    fun fileFor(context: Context, tag: String): File = File(dir(context), "${qualifierOf(tag)}.apk")

    fun has(context: Context, tag: String): Boolean = fileFor(context, tag).isFile

    /** Whether this build and this phone use packs at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Loads every pack of this version into the shared loader and drops
     * other versions' packs. Call once at process start, before [attach].
     */
    fun install(context: Context) {
        if (!supported) return
        File(context.noBackupFilesDir, "langpacks").listFiles()?.forEach { version ->
            if (version.name != BuildConfig.VERSION_CODE.toString()) version.deleteRecursively()
        }
        reload(context)
    }

    /** Re-reads the packs on disk into the loader, after one was added. */
    fun reload(context: Context) {
        if (!supported) return
        runCatching { setProviders(context) }.onFailure { DebugLog.w(TAG, "loading language packs failed: $it") }
    }

    /** Adds the packs to [resources], once. Harmless before any pack exists. */
    fun attach(resources: Resources) {
        if (!supported) return
        runCatching { attachLoader(resources) }.onFailure { DebugLog.w(TAG, "attaching language packs failed: $it") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun shared(): ResourcesLoader = (loader as? ResourcesLoader) ?: synchronized(this) {
        (loader as? ResourcesLoader) ?: ResourcesLoader().also { loader = it }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun setProviders(context: Context) {
        val packs = dir(context).listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy { it.name }
        val providers = packs.mapNotNull { pack ->
            runCatching {
                ParcelFileDescriptor.open(pack, ParcelFileDescriptor.MODE_READ_ONLY).use { ResourcesProvider.loadFromApk(it) }
            }.onFailure { DebugLog.w(TAG, "${pack.name} would not load: $it") }.getOrNull()
        }
        shared().setProviders(providers)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun attachLoader(resources: Resources) {
        // A loader already in the list is left where it is.
        resources.addLoaders(shared())
    }
}
