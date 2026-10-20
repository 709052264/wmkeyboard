package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.app.updates.GithubReleases
import com.wasimaster.wmkeyboard.core.modules.LanguagePackResources
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetching and checking interface language packs ([LanguagePackResources])
 * for the standard GitHub build: one resource-only APK per language, attached
 * to the GitHub release of exactly this version.
 *
 * A pack is accepted only when it is signed with this app's own certificate
 * and was cut from this version's build, so a file from elsewhere, or from
 * another release, is never loaded.
 */
internal object LanguagePackDownloads {

    /** Whether this build fetches its interface languages (the standard GitHub build, Android 11+). */
    val active: Boolean get() = BuildConfig.LANGUAGE_PACKS && LanguagePackResources.supported

    private const val TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 16L * 1024 * 1024

    /** English is the APK's own: no pack. */
    fun isEnglish(tag: String): Boolean = LanguagePackResources.qualifierOf(tag).substringBefore('-') == "en"

    /** The release asset holding [tag]'s pack. */
    fun url(tag: String): String {
        val version = com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_NAME
        val code = com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_CODE
        val name = "wmkeyboard-$version-vc$code-lang-${LanguagePackResources.qualifierOf(tag)}.apk"
        return "https://github.com/${GithubReleases.REPO}/releases/download/v$version/$name"
    }

    /** The published file name of a pack, which an import is recognised by. */
    private val PACK_NAME = Regex("""^wmkeyboard-.+-vc\d+-lang-([a-z]{2,3}(?:-r[A-Z]{2})?)\.apk$""")

    fun isPackName(name: String): Boolean = PACK_NAME.matches(name)

    /**
     * Makes sure [tag]'s pack is on the phone and loaded. True when it is
     * (or English, which needs none), false when it could not be fetched or
     * did not check out. Blocking network work runs on IO.
     */
    suspend fun ensure(context: Context, tag: String?): Boolean {
        if (!active || tag == null) return true
        if (isEnglish(tag) || LanguagePackResources.has(context, tag)) return true
        return withContext(Dispatchers.IO) {
            val target = LanguagePackResources.fileFor(context, tag)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "${target.name}.part")
            val ok = runCatching { download(url(tag), part) }.isSuccess && install(context, part, target)
            part.delete()
            ok
        }
    }

    /** Installs an imported pack when it is one of this version's, signed by us. */
    fun import(context: Context, file: File): String? {
        if (!LanguagePackResources.supported) return null
        val qualifier = PACK_NAME.matchEntire(file.name)?.groupValues?.get(1) ?: return null
        val target = File(LanguagePackResources.dir(context), "$qualifier.apk")
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, "${target.name}.part")
        file.copyTo(staged, overwrite = true)
        return qualifier.takeIf { install(context, staged, target) }.also { staged.delete() }
    }

    private fun download(url: String, part: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        NetLog.call(NetSource.DOWNLOAD_LANGUAGE, "GET", url, route = NetLog.pathOf(url)).track { call ->
            try {
                call.status = connection.responseCode
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
                call.countIn(connection.inputStream).use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("$url is larger than a language pack")
                            out.write(buffer, 0, n)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Checks [staged] and moves it to [target], reloading the packs. */
    private fun install(context: Context, staged: File, target: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !trusted(context, staged)) return false
        target.delete()
        if (!staged.renameTo(target)) return false
        LanguagePackResources.reload(context)
        return true
    }

    /**
     * Signed with this app's certificate, and cut from this version: the
     * pack's versionCode is the build's (with the ABI digit of the universal
     * APK it came from), the same one this install's carries.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun trusted(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val pack = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (pack.packageName != context.packageName) return false
        if (pack.longVersionCode / VERSION_DIGIT != com.wasimaster.wmkeyboard.config.BuildConfig.VERSION_CODE.toLong()) return false
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val ownSigners = own.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val packSigners = pack.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        return ownSigners.isNotEmpty() && packSigners == ownSigners
    }

    /** The manifest's versionCode is the release's times ten plus an ABI digit (abiDigits in app/build.gradle.kts). */
    private const val VERSION_DIGIT = 10L
}
