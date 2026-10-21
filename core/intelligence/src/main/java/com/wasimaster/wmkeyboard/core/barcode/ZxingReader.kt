package com.wasimaster.wmkeyboard.core.barcode

import java.nio.ByteBuffer

/**
 * What kind of code a scanner read, as the QR scanner names it. Both readers
 * map to it: ML Kit's `Barcode.FORMAT_*` and zxing-cpp's formats, of which
 * zxing-cpp knows more (those land on [OTHER] or on their family, Micro QR on
 * [QR_CODE], ISBN on [EAN_13]).
 */
enum class BarcodeKind {
    QR_CODE, AZTEC, CODABAR, CODE_39, CODE_93, CODE_128, DATA_MATRIX,
    EAN_8, EAN_13, ITF, PDF417, UPC_A, UPC_E, OTHER,
}

/**
 * One code zxing-cpp read: [text] as the symbol spells it (what ML Kit calls
 * the raw value) and [format], zxing-cpp's `BarcodeFormat` value: the
 * ISO/IEC 15424 symbology letter in the low byte, the variant in the next.
 */
class ZxingCode(val text: String, val format: Int) {
    val kind: BarcodeKind get() = kindOf(format)

    override fun toString() = "ZxingCode(${(format and 0xFF).toChar()}${(format shr 8 and 0xFF).toChar()}, $text)"

    companion object {
        /** [BarcodeKind] of a zxing-cpp format value. */
        fun kindOf(format: Int): BarcodeKind {
            val variant = (format shr 8 and 0xFF).toChar()
            return when ((format and 0xFF).toChar()) {
                'Q' -> BarcodeKind.QR_CODE
                'z' -> BarcodeKind.AZTEC
                'F' -> BarcodeKind.CODABAR
                'A' -> BarcodeKind.CODE_39
                'G' -> BarcodeKind.CODE_93
                'C' -> BarcodeKind.CODE_128
                'd' -> BarcodeKind.DATA_MATRIX
                'I' -> BarcodeKind.ITF
                'L' -> BarcodeKind.PDF417
                'E' -> when (variant) {
                    '8' -> BarcodeKind.EAN_8
                    '1', 'i' -> BarcodeKind.EAN_13
                    'a' -> BarcodeKind.UPC_A
                    'e' -> BarcodeKind.UPC_E
                    else -> BarcodeKind.OTHER
                }
                else -> BarcodeKind.OTHER
            }
        }
    }
}

/**
 * zxing-cpp's readers for the QR scanner, over [ZxingNative]. Every format
 * zxing-cpp reads is looked for; the first code with any text wins, the way
 * the scanner takes ML Kit's first. Each call needs [available] and returns
 * null when the library is not there, the read found nothing or it failed.
 */
object ZxingReader {
    val available: Boolean get() = ZxingNative.available

    /**
     * The first code in a camera frame's Y plane ([luma], direct, rows
     * [rowStride] apart). A live frame is read at the quick setting; [invert]
     * also tries it as light on dark, which costs a second pass only when the
     * first finds nothing, so the scanner asks for it on every other frame.
     */
    fun readFrame(luma: ByteBuffer, width: Int, height: Int, rowStride: Int, invert: Boolean): ZxingCode? {
        if (!available) return null
        val formats = IntArray(MAX_SYMBOLS)
        val texts = runCatching {
            ZxingNative.nativeReadLuma(
                luma, width, height, rowStride,
                if (invert) ZxingNative.TRY_INVERT else 0,
                MAX_SYMBOLS, formats,
            )
        }.getOrNull()
        return first(texts, formats)
    }

    /**
     * The first code in a picture's [argb] pixels (as `Bitmap.getPixels` fills
     * them), read as hard as zxing-cpp can: a picture is read once, not sixty
     * times a second.
     */
    fun readPixels(argb: IntArray, width: Int, height: Int): ZxingCode? {
        if (!available) return null
        val formats = IntArray(MAX_SYMBOLS)
        val texts = runCatching {
            ZxingNative.nativeReadPixels(
                argb, width, height,
                ZxingNative.TRY_HARDER or ZxingNative.TRY_INVERT,
                MAX_SYMBOLS, formats,
            )
        }.getOrNull()
        return first(texts, formats)
    }

    private fun first(texts: Array<ByteArray>?, formats: IntArray): ZxingCode? {
        texts ?: return null
        for (i in texts.indices) {
            val text = texts[i].toString(Charsets.UTF_8)
            if (text.isNotEmpty()) return ZxingCode(text, formats.getOrElse(i) { 0 })
        }
        return null
    }

    /**
     * How many codes one read may return. More than one, so a code whose text
     * is empty does not hide a second one beside it; few, since the scanner
     * keeps only the first.
     */
    private const val MAX_SYMBOLS = 4
}
