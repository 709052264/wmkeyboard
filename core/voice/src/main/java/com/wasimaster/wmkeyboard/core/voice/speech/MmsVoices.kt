package com.wasimaster.wmkeyboard.core.voice.speech

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import android.content.Context
import java.io.File
import java.util.Locale
import java.util.MissingResourceException

/**
 * One of Meta's MMS-TTS voices: a VITS model for one language, [code] being
 * the ISO 639-3 code Meta named it by. The weights are CC-BY-NC-4.0; the
 * phone fetches them itself, after the user agrees
 * ([com.wasimaster.wmkeyboard.core.modules.NativeConsent.MMS]).
 */
class MmsVoice(
    val code: String,
    val modelBytes: Long,
    val modelSha256: String,
    val tokensBytes: Long,
    val tokensSha256: String,
) {
    val bytes: Long get() = modelBytes + tokensBytes

    fun modelUrl(): String = "${MmsVoices.BASE}/$code/model.onnx"
    fun tokensUrl(): String = "${MmsVoices.BASE}/$code/tokens.txt"
}

/**
 * Every MMS voice the app can download, read from `assets/speech/mms_tts.tsv`
 * (written by `native/sherpa-onnx-jni/mms_tts_catalog.py`): about 1,100
 * languages, each pinned to one revision of the ONNX conversion with its
 * sizes and SHA-256. Voices that need Meta's romaniser (uroman) are not in it.
 */
object MmsVoices {

    /** Hugging Face repo and revision every voice comes from. */
    const val REPO = "willwade/mms-tts-multilingual-models-onnx"
    const val REVISION = "4f49fc254f11ddb0e35fbf25b6e48b04463a6779"
    internal const val BASE = "https://huggingface.co/$REPO/resolve/$REVISION"

    /** Folder under `filesDir`, apart from the dictation engines' folders. */
    private const val DIR = "mms-tts"

    /**
     * Keyboard language codes whose MMS voice goes by another code: a
     * macrolanguage Meta recorded as one of its members, or ISO 639-1/-2
     * spellings [Locale.getISO3Language] does not turn into Meta's.
     */
    private val aliases = mapOf(
        "ms" to "zlm", "msa" to "zlm", "may" to "zlm", "zsm" to "zlm",
        "sw" to "swh", "swa" to "swh",
        "or" to "ory", "ori" to "ory",
        "qu" to "quz", "que" to "quz",
        "ay" to "ayr", "aym" to "ayr",
        "fil" to "tgl",
        "arb" to "ara",
        "pes" to "fas", "per" to "fas",
        "lvs" to "lav",
        "als" to "sqi", "alb" to "sqi",
        "gaz" to "orm",
        "plt" to "mlg",
        "gre" to "ell",
    )

    @Volatile private var loaded: Map<String, MmsVoice>? = null

    /** The catalog, read once. */
    fun all(context: Context): Map<String, MmsVoice> {
        loaded?.let { return it }
        val parsed = runCatching {
            context.applicationContext.assets.open("speech/mms_tts.tsv").bufferedReader().use { parse(it.readText()) }
        }.getOrDefault(emptyMap())
        loaded = parsed
        return parsed
    }

    internal fun parse(text: String): Map<String, MmsVoice> {
        val out = LinkedHashMap<String, MmsVoice>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 5) continue
            val modelBytes = f[1].toLongOrNull() ?: continue
            val tokensBytes = f[3].toLongOrNull() ?: continue
            out[f[0]] = MmsVoice(f[0], modelBytes, f[2], tokensBytes, f[4])
        }
        return out
    }

    /**
     * The code Meta's voice for [languageTag] goes by: a keyboard language id
     * (`bn`, `en_GB`, `swc`) or a BCP 47 tag, reduced to its language and
     * spelled the way [aliases] and ISO 639-3 say. Null when the tag has no
     * language part.
     */
    fun codeFor(languageTag: String): String? {
        val base = languageTag.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        if (base.isEmpty()) return null
        aliases[base]?.let { return it }
        if (base.length == 3) return base
        val iso3 = try {
            Locale(base).isO3Language
        } catch (_: MissingResourceException) {
            return null
        }
        return aliases[iso3] ?: iso3.ifEmpty { null }
    }

    /** The voice for [languageTag], whether or not it is downloaded; null when Meta has none. */
    fun forLanguage(context: Context, languageTag: String): MmsVoice? =
        codeFor(languageTag)?.let { all(context)[it] }

    fun dir(filesDir: File, voice: MmsVoice): File = File(File(filesDir, DIR), voice.code)
    fun modelFile(filesDir: File, voice: MmsVoice): File = File(dir(filesDir, voice), "model.onnx")
    fun tokensFile(filesDir: File, voice: MmsVoice): File = File(dir(filesDir, voice), "tokens.txt")

    fun isDownloaded(filesDir: File, voice: MmsVoice): Boolean =
        modelFile(filesDir, voice).length() == voice.modelBytes && tokensFile(filesDir, voice).length() == voice.tokensBytes

    /** The voices on the device, by code. Lists one folder; cheap enough to ask per tap. */
    fun downloaded(context: Context): List<MmsVoice> {
        val root = File(context.filesDir, DIR)
        val catalog = all(context)
        return root.list().orEmpty().mapNotNull { catalog[it] }.filter { isDownloaded(context.filesDir, it) }
    }

    fun bytesOnDisk(filesDir: File, voice: MmsVoice): Long =
        FileDownloader.bytesUnder(dir(filesDir, voice))

    /** Every voice's folder, for the storage screen. */
    fun rootDir(filesDir: File): File = File(filesDir, DIR)
}
