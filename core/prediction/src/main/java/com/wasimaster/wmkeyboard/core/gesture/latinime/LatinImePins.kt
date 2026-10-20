package com.wasimaster.wmkeyboard.core.gesture.latinime

/**
 * The builds of Google's gesture typing library (`libjni_latinime.so`) this
 * app agrees to load: one per ABI, by SHA-256. The same builds HeliBoard pins,
 * which is what makes them a known quantity: that keyboard has loaded exactly
 * these bytes against exactly the JNI interface in
 * `com.android.inputmethod.latin` for years.
 *
 * The library is Google's and proprietary. This project never ships it,
 * hosts it or downloads it: the user brings the file (it comes out of a GApps
 * package, where it is called `libjni_latinimegoogle.so`), and a file is
 * accepted only when its hash is one of these. A pin is a promise about
 * these bytes, so a new pin is a code change, never a download.
 */
object LatinImePins {

    /** The name the library is stored and loaded under. */
    const val FILE_NAME = "libjni_latinime.so"

    /** The name a GApps package carries it under. */
    const val GAPPS_FILE_NAME = "libjni_latinimegoogle.so"

    /** ABI to the SHA-256 of the one build accepted for it, lowercase hex. */
    val byAbi: Map<String, String> = mapOf(
        "arm64-v8a" to "b1049983e6ac5cfc6d1c66e38959751044fad213dff0637a6cf1d2a2703e754f",
        "armeabi-v7a" to "442a2a8bfcb25489564bc9433a916fa4dc0dba9000fe6f6f03f5939b985091e6",
        "x86_64" to "c882e12e6d48dd946e0b644c66868a720bd11ac3fecf152000e21a3d5abd59c9",
        "x86" to "bd946d126c957b5a6dea3bafa07fa36a27950b30e2b684dffc60746d0a1c7ad8",
    )

    /** Whether [name] is a name the library is published under, so an import can be routed by name. */
    fun isLibraryName(name: String): Boolean = name == FILE_NAME || name == GAPPS_FILE_NAME

    /** The ABI [sha256] is the pinned build for, or null when it is none of them. */
    fun abiOf(sha256: String): String? {
        val wanted = sha256.lowercase()
        return byAbi.entries.firstOrNull { it.value == wanted }?.key
    }
}
