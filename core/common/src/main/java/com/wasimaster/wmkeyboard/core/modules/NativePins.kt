package com.wasimaster.wmkeyboard.core.modules

/**
 * Where a delivered native library comes from.
 *
 * [Data] is a library this project builds: published on the data
 * repository's `natives` release as `<sha256>-<name>`, content-addressed, so a
 * file is never replaced and an app update whose library did not change
 * downloads nothing. [Maven] is someone else's library that may not be
 * mirrored (ML Kit, LiteRT, Qualcomm's QNN): the phone reads the one entry it
 * needs straight out of the published artifact on the vendor's own Maven
 * repository, with HTTP ranges, so this project never redistributes it.
 */
sealed interface NativeSource {
    data object Data : NativeSource

    /**
     * [repo] is the repository base with no trailing slash
     * (`https://dl.google.com/android/maven2`), [coordinates] is
     * `group:artifact:version`, [entry] the path inside the AAR
     * (`jni/arm64-v8a/libtranslate_jni.so`).
     */
    data class Maven(val repo: String, val coordinates: String, val entry: String) : NativeSource {
        /** The AAR's URL in the Maven layout. */
        val url: String
            get() {
                val (group, artifact, version) = coordinates.split(':').let { Triple(it[0], it[1], it[2]) }
                return "$repo/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.aar"
            }
    }
}

/**
 * One native library the build left out of the APK and pinned instead: the
 * file this ABI needs, its exact size and SHA-256, and the [gate] that
 * delivers it. The pins are written into the APK at build time, so they are
 * as trusted as the code; the host that serves the bytes is not trusted at
 * all, and an imported file is accepted when its hash is one of these.
 */
data class NativePin(
    val gate: String,
    val abi: String,
    val name: String,
    val sha256: String,
    val size: Long,
    val source: NativeSource,
    /** A licence the user accepts before the download, or null. See [NativeConsent]. */
    val licence: String? = null,
)

/**
 * The pin table, `assets/native/pins.tsv`, written by the build's
 * `nativePins` task. One library per line, tab-separated:
 *
 *     gate  abi  name  sha256  size  source  licence
 *
 * `source` is `data`, or `maven|<repo>|<group:artifact:version>|<entry>`;
 * `licence` is empty or an id [NativeConsent] knows. Lines starting with `#`
 * are comments. A malformed line is skipped rather than failing the table: a
 * pin that cannot be read is a library that is never offered, which the
 * gate reports as missing.
 */
object NativePins {
    const val ASSET = "native/pins.tsv"

    fun parse(text: String): List<NativePin> = text.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull(::parseLine)
        .toList()

    private const val FIELDS = 6

    private fun parseLine(line: String): NativePin? {
        val f = line.split('\t')
        if (f.size < FIELDS) return null
        val size = f[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val sha = f[3].lowercase().takeIf { it.length == SHA256_HEX && it.all(::isHex) } ?: return null
        val source = parseSource(f[5]) ?: return null
        return NativePin(
            gate = f[0],
            abi = f[1],
            name = f[2],
            sha256 = sha,
            size = size,
            source = source,
            licence = f.getOrNull(FIELDS)?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseSource(field: String): NativeSource? {
        if (field == "data") return NativeSource.Data
        val parts = field.split('|')
        if (parts.size != 4 || parts[0] != "maven") return null
        if (parts[2].split(':').size != 3 || !parts[1].startsWith("https://")) return null
        return NativeSource.Maven(parts[1].trimEnd('/'), parts[2], parts[3])
    }

    /** The line [parse] reads back; what the build writes. */
    fun format(pin: NativePin): String {
        val source = when (val s = pin.source) {
            NativeSource.Data -> "data"
            is NativeSource.Maven -> "maven|${s.repo}|${s.coordinates}|${s.entry}"
        }
        return listOf(pin.gate, pin.abi, pin.name, pin.sha256, pin.size.toString(), source, pin.licence.orEmpty())
            .joinToString("\t")
    }

    private const val SHA256_HEX = 64

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'
}
