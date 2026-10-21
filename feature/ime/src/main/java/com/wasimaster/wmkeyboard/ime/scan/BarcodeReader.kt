package com.wasimaster.wmkeyboard.ime.scan

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.camera.core.ImageProxy
import com.wasimaster.wmkeyboard.core.barcode.BarcodeEngine
import com.wasimaster.wmkeyboard.core.barcode.BarcodeKind
import com.wasimaster.wmkeyboard.core.barcode.ZxingNative
import com.wasimaster.wmkeyboard.core.barcode.ZxingReader
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A code the QR scanner read: the text it holds ([rawValue], as ML Kit names it) and what kind of code it is. */
internal class ScannedBarcode(val rawValue: String, val kind: BarcodeKind)

/**
 * What the QR scanner reads codes with: ML Kit's barcode reader where the
 * build has it and it can run ([MlKitBarcodes]), zxing-cpp everywhere else
 * and wherever the user picked it ([ZxingBarcodeReader]). Both hand back the
 * same [ScannedBarcode], so the rest of the panel (insert, copy, open a link,
 * pair with a computer) does not know which read it.
 */
internal interface BarcodeReader : AutoCloseable {
    /** The module that holds this reader's library; the panel offers its download until [ready]. */
    val gate: ModuleGate

    /** Whether the reader can run now: its library is here and loads. */
    fun ready(): Boolean

    /**
     * Reads one camera frame and closes it. [done] gets the first code with
     * any text, or null, exactly once, on whatever thread the read ends on.
     */
    fun readFrame(frame: ImageProxy, done: (ScannedBarcode?) -> Unit)

    /** The first code in a saved picture, or null. */
    suspend fun readPicture(picture: Bitmap): ScannedBarcode?
}

/** The reader the QR scanner uses, given the "Read codes with zxing-cpp" setting. */
internal fun barcodeReader(pickedZxing: Boolean): BarcodeReader =
    (if (BarcodeEngine.useZxing(pickedZxing)) null else MlKitBarcodes.reader()) ?: ZxingBarcodeReader()

/**
 * zxing-cpp's readers ([ZxingReader]). A frame is read straight from its Y
 * plane, one JNI crossing and no copy, on the analysis thread it arrives on.
 */
internal class ZxingBarcodeReader : BarcodeReader {
    override val gate: ModuleGate get() = FeatureModules.zxing

    // Light-on-dark codes (a dark-mode screen) are tried on every other frame.
    private var frames = 0

    override fun ready(): Boolean = ZxingNative.available

    override fun readFrame(frame: ImageProxy, done: (ScannedBarcode?) -> Unit) {
        val code = frame.use {
            val luma = it.planes[0]
            ZxingReader.readFrame(luma.buffer, it.width, it.height, luma.rowStride, invert = frames++ % 2 == 1)
        }
        done(code?.let { ScannedBarcode(it.text, it.kind) })
    }

    override suspend fun readPicture(picture: Bitmap): ScannedBarcode? = withContext(Dispatchers.Default) {
        // getPixels throws on a hardware bitmap; a copy reads anywhere.
        val pixels = if (picture.config == Bitmap.Config.ARGB_8888) {
            picture
        } else {
            picture.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
        }
        val argb = IntArray(pixels.width * pixels.height)
        pixels.getPixels(argb, 0, pixels.width, 0, 0, pixels.width, pixels.height)
        ZxingReader.readPixels(argb, pixels.width, pixels.height)?.let { ScannedBarcode(it.text, it.kind) }
    }

    override fun close() = Unit
}

/** The result card's name for a code's kind. */
@StringRes
internal fun BarcodeKind.labelRes(): Int = when (this) {
    BarcodeKind.QR_CODE -> R.string.ime_scanner_format_qr_code
    BarcodeKind.AZTEC -> R.string.ime_scanner_format_aztec
    BarcodeKind.CODABAR -> R.string.ime_scanner_format_codabar
    BarcodeKind.CODE_39 -> R.string.ime_scanner_format_code_39
    BarcodeKind.CODE_93 -> R.string.ime_scanner_format_code_93
    BarcodeKind.CODE_128 -> R.string.ime_scanner_format_code_128
    BarcodeKind.DATA_MATRIX -> R.string.ime_scanner_format_data_matrix
    BarcodeKind.EAN_8 -> R.string.ime_scanner_format_ean_8
    BarcodeKind.EAN_13 -> R.string.ime_scanner_format_ean_13
    BarcodeKind.ITF -> R.string.ime_scanner_format_itf
    BarcodeKind.PDF417 -> R.string.ime_scanner_format_pdf417
    BarcodeKind.UPC_A -> R.string.ime_scanner_format_upc_a
    BarcodeKind.UPC_E -> R.string.ime_scanner_format_upc_e
    BarcodeKind.OTHER -> R.string.ime_scanner_format_generic
}
