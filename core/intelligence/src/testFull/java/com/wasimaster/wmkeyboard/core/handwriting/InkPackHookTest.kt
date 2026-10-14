package com.wasimaster.wmkeyboard.core.handwriting

import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline handwriting imports (#666) work by rewriting three calls inside ML
 * Kit's shaded downloader at build time (InkOfflineImportsTransform in
 * build-logic). The transform matches on what the library calls, not on its
 * obfuscated names, so a library update that drops one of those calls does
 * not fail the build: it silently stops the import working. This reads the
 * library's own classes and fails instead.
 */
class InkPackHookTest {

    private val classes: List<ByteArray> by lazy {
        val location = File(DigitalInkRecognition::class.java.protectionDomain!!.codeSource!!.location.toURI())
        if (location.isDirectory) {
            File(location, PACKAGE_PATH).listFiles().orEmpty().filter { it.name.endsWith(".class") }.map { it.readBytes() }
        } else {
            ZipFile(location).use { jar ->
                jar.entries().toList()
                    .filter { it.name.startsWith(PACKAGE_PATH) && it.name.endsWith(".class") }
                    .map { entry -> jar.getInputStream(entry).use { it.readBytes() } }
            }
        }
    }

    private fun anyClassHas(vararg needles: String): Boolean = classes.any { bytes ->
        val text = String(bytes, Charsets.ISO_8859_1)
        needles.all { it in text }
    }

    @Test
    fun theDownloaderPackageIsWhereTheTransformLooks() {
        assertTrue("no classes under $PACKAGE_PATH", classes.isNotEmpty())
    }

    @Test
    fun itBuildsAnOkHttpClient() {
        assertTrue(anyClassHas("okhttp3/OkHttpClient\$Builder", "()Lokhttp3/OkHttpClient;"))
    }

    @Test
    fun itChecksPermissionsThroughContextCompat() {
        assertTrue(anyClassHas("androidx/core/content/ContextCompat", "checkSelfPermission", "android.permission.INTERNET"))
    }

    @Test
    fun itLogsTheNoNetworkVerdictTheTransformKeysOn() {
        assertTrue(anyClassHas("No current network, connectivity cannot be satisfied."))
    }

    private companion object {
        const val PACKAGE_PATH = "com/google/android/gms/internal/mlkit_vision_digital_ink/"
    }
}
