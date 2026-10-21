package com.wasimaster.wmkeyboard.core.semantic

import com.wasimaster.wmkeyboard.core.text.GemmaTokenizer
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * What a text or a picture becomes before EmbeddingGemma 2's encoder sees it,
 * worked out the way LiteRT-LM's own embedding engine does it, so the
 * vectors agree with the model's published behaviour. Pure arithmetic: the
 * interpreter calls are [EmbeddingRuntime]'s.
 *
 * A text is `<bos>`, its tokens, `<eos>`, with a prefix that tells the model
 * what the text is for: a search typed by the user ([Role.QUERY]) or a thing
 * to be found ([Role.DOCUMENT]). A picture is `<bos>`, `<|image>`, the vision
 * tower's soft tokens, the end-of-image vector and `<eos>`, with no words at
 * all. Both go through the same encoder into the same space, so a typed query
 * is compared with texts and pictures alike.
 */
object EmbeddingInput {

    /** Width of a token embedding, and of the vision adapter's soft tokens. */
    const val WIDTH = 512

    /** Length of the vectors the encoder answers with. */
    const val DIMENSIONS = 768

    /** The encoder's signatures, `encoder_1x<n>`, short to long. Longer ones exist and are never loaded. */
    val SEQUENCE_LENGTHS = intArrayOf(128, 256, 512)

    /** Tokens a query keeps. A search box holds a few words. */
    const val MAX_QUERY_TOKENS = 128

    /**
     * Tokens a stored text keeps, its start. A long note is found by what it
     * opens with; the whole of it would cost a longer signature for every
     * note and buy little.
     */
    const val MAX_DOCUMENT_TOKENS = 256

    /** Characters read from a stored text before it is tokenized at all. */
    const val MAX_DOCUMENT_CHARS = 2_000

    enum class Role(val prefix: String) {
        QUERY("task: search result | query: "),
        DOCUMENT("title: none | text: "),
    }

    /**
     * [text] as the encoder's token ids, `<bos>` and `<eos>` included. Too
     * long and it is cut, keeping `<eos>` last, as LiteRT-LM truncates.
     */
    fun tokens(tokenizer: GemmaTokenizer, text: String, role: Role): IntArray {
        val body = text.trim().take(MAX_DOCUMENT_CHARS)
        val ids = tokenizer.encode(role.prefix + body)
        val max = if (role == Role.QUERY) MAX_QUERY_TOKENS else MAX_DOCUMENT_TOKENS
        val kept = minOf(ids.size, max - 2)
        val out = IntArray(kept + 2)
        out[0] = tokenizer.bosId
        System.arraycopy(ids, 0, out, 1, kept)
        out[kept + 1] = tokenizer.eosId
        return out
    }

    /** The shortest signature that holds [length] tokens, or the longest one when none does. */
    fun sequenceLength(length: Int): Int =
        SEQUENCE_LENGTHS.firstOrNull { it >= length } ?: SEQUENCE_LENGTHS.last()

    // ---- Pictures --------------------------------------------------------------

    /** Side of one patch, in pixels. */
    const val PATCH = 16

    /** Patches pooled into one soft token, per side. */
    const val POOL = 3

    /**
     * Soft tokens per picture: the vision tower's `vision_70` signature. The
     * other one, 140, costs twice the time and memory, and on the photos it
     * was checked with it ranked no better.
     */
    const val IMAGE_TOKENS = 70

    /** Patches per picture: [IMAGE_TOKENS] pooled [POOL]×[POOL]. */
    const val MAX_PATCHES = IMAGE_TOKENS * POOL * POOL

    /** Values per patch: 16×16 RGB. */
    const val PATCH_VALUES = PATCH * PATCH * 3

    /**
     * The size a [width]×[height] picture is scaled to: its own shape, as
     * close to [MAX_PATCHES] patches as whole pooled blocks allow
     * (LiteRT-LM's `GetAspectRatioPreservingSize`). Returns width and height.
     */
    fun imageSize(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "empty picture" }
        val target = MAX_PATCHES.toFloat() * PATCH * PATCH
        val factor = sqrt(target / (width.toFloat() * height))
        val side = POOL * PATCH
        var h = floor(factor * height / side).toInt() * side
        var w = floor(factor * width / side).toInt() * side
        val maxSide = (MAX_PATCHES / (POOL * POOL)) * side
        if (h == 0) {
            h = side
            w = minOf(floor(width.toFloat() / height).toInt() * side, maxSide)
        } else if (w == 0) {
            w = side
            h = minOf(floor(height.toFloat() / width).toInt() * side, maxSide)
        }
        return intArrayOf(maxOf(w, side), maxOf(h, side))
    }

    /**
     * Cuts [pixels] (ARGB, [width]×[height], already scaled to [imageSize])
     * into the vision tower's input: [patches] gets [PATCH_VALUES] floats in
     * 0..1 per patch, rows of patches left to right, top to bottom, each patch
     * row-major RGB; [positions] gets each patch's column and row, and -1 for
     * the unused slots after the last. Transparent pixels are laid on white,
     * since a sticker's empty corners are not black. Returns the patch count.
     */
    fun patches(pixels: IntArray, width: Int, height: Int, patches: FloatArray, positions: IntArray): Int {
        val columns = width / PATCH
        val rows = height / PATCH
        val count = minOf(columns * rows, MAX_PATCHES)
        patches.fill(0f)
        positions.fill(-1)
        var index = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (index >= count) return count
                positions[index * 2] = column
                positions[index * 2 + 1] = row
                var o = index * PATCH_VALUES
                for (y in 0 until PATCH) {
                    val line = (row * PATCH + y) * width + column * PATCH
                    for (x in 0 until PATCH) {
                        val argb = pixels[line + x]
                        val alpha = (argb ushr 24) / 255f
                        val white = 1f - alpha
                        patches[o++] = ((argb shr 16) and 0xFF) / 255f * alpha + white
                        patches[o++] = ((argb shr 8) and 0xFF) / 255f * alpha + white
                        patches[o++] = (argb and 0xFF) / 255f * alpha + white
                    }
                }
                index++
            }
        }
        return count
    }

    /** Scales [vector] to unit length in place, and returns it. */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v * v
        if (sum <= 0.0) return vector
        val scale = (1.0 / sqrt(sum)).toFloat()
        for (k in vector.indices) vector[k] *= scale
        return vector
    }
}
