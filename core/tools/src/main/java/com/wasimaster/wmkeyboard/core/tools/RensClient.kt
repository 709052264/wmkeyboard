package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.net.URI

/**
 * Rens (git.maid.zone/stuff/rens, #727): a free front end to Google Lens. The
 * instance talks to Google, strips the photo's metadata and renames it, so
 * Google sees the instance and never the phone.
 *
 * Rens has no API, only its web page's two forms, so this client posts what
 * the forms post and reads the page that comes back: the text in its `<pre>`
 * for OCR, the translated picture's address for translation. Checked against
 * ren.maid.zone on 2026-10-19. A page without the part asked for is a
 * failure, never an empty answer, so the next provider is asked.
 *
 * Blocking; call on an IO dispatcher.
 */
object RensClient {

    /** The text in [jpeg], lines kept. */
    fun readText(jpeg: ByteArray, endpoint: String): String {
        val base = FrontEndUrls.base(endpoint)
        val page = ToolHttp.postMultipart(
            url = "$base/?ocr",
            fields = emptyList(),
            file = photo(jpeg),
            source = NetSource.OCR_ONLINE,
            route = "/?ocr",
        )
        return parseText(page) ?: throw FrontEndEmptyException()
    }

    /**
     * [jpeg] with its text translated into [target], written to [out]. Rens
     * answers with a page naming the picture on its own proxy, which is
     * fetched from the same instance.
     */
    fun translate(jpeg: ByteArray, endpoint: String, target: String, out: File, source: String = TranslateClient.AUTO) {
        val base = FrontEndUrls.base(endpoint)
        val page = ToolHttp.postMultipart(
            url = "$base/?translate",
            fields = listOf("sl" to source.ifBlank { TranslateClient.AUTO }, "tl" to target),
            file = photo(jpeg),
            source = NetSource.PHOTO_SEARCH,
            route = "/?translate",
        )
        val address = parseImage(page)?.let { imageUrl(base, it) } ?: throw FrontEndEmptyException()
        ToolHttp.download(
            address,
            out,
            timeoutMs = 60_000,
            maxBytes = MAX_IMAGE_BYTES,
            source = NetSource.PHOTO_SEARCH,
            route = "/proxy",
        )
    }

    /**
     * The Test button's check: the instance's page answers and has Rens's
     * OCR form. Sends no photo. Throws when it is not a Rens server.
     */
    fun ping(endpoint: String) {
        val base = FrontEndUrls.base(endpoint)
        val page = ToolHttp.get("$base/", source = NetSource.OCR_ONLINE, route = "/")
        if (!page.contains("action=\"/?ocr\"")) throw FrontEndEmptyException()
    }

    private fun photo(jpeg: ByteArray) = ToolHttp.FilePart("image", "photo.jpg", "image/jpeg", jpeg)

    /** What follows "Extracted text:", or null when the page has none (the form came back empty). */
    internal fun parseText(page: String): String? {
        val label = page.indexOf("Extracted text:")
        if (label < 0) return null
        val open = page.indexOf("<pre>", label)
        val close = page.indexOf("</pre>", open + 1)
        if (open < 0 || close < 0) return null
        return unescape(page.substring(open + "<pre>".length, close)).trim().ifEmpty { null }
    }

    /** The translated picture's `src`, as written: a path on the instance's proxy. */
    internal fun parseImage(page: String): String? {
        val tag = Regex("""id="translated_image"[^>]*?\ssrc="([^"]+)"""").find(page)
            ?: Regex("""src="([^"]+)"[^>]*?id="translated_image"""").find(page)
        return tag?.groupValues?.get(1)?.let(::unescape)?.ifBlank { null }
    }

    /**
     * The picture's address on [base]. Only the instance itself is fetched
     * from: an answer naming another host would send the phone somewhere the
     * user did not choose.
     */
    internal fun imageUrl(base: String, src: String): String? {
        val resolved = runCatching { URI("$base/").resolve(src) }.getOrNull() ?: return null
        val home = runCatching { URI(base) }.getOrNull() ?: return null
        return resolved.toString().takeIf { resolved.host == home.host && resolved.scheme == home.scheme }
    }

    /** The entities Go's html/template writes. */
    internal fun unescape(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name == "amp" -> "&"
            name == "lt" -> "<"
            name == "gt" -> ">"
            name == "quot" -> "\""
            name == "apos" -> "'"
            name.startsWith("#x") -> codePoint(name.drop(2).toIntOrNull(16)) ?: m.value
            else -> codePoint(name.drop(1).toIntOrNull()) ?: m.value
        }
    }

    private fun codePoint(value: Int?): String? =
        value?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }

    /** A translated photo is about the size of the one sent; this is a ceiling, not a guess. */
    private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
}
