package com.wasimaster.wmkeyboard.ime.scan

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.wasimaster.wmkeyboard.core.barcode.BarcodeKind
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.ime.ocr.MlKitRecognizers
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** ML Kit's barcode reader for the QR scanner; F-Droid's stand-in has none. */
internal object MlKitBarcodes {
    /** A new reader; the caller closes it. */
    fun reader(): BarcodeReader? = MlKitBarcodeReader()
}

private class MlKitBarcodeReader : BarcodeReader {
    private val scanner = BarcodeScanning.getClient()

    override val gate: ModuleGate get() = FeatureModules.scanner

    override fun ready(): Boolean = MlKitRecognizers.barcodeReady()

    override fun readFrame(frame: ImageProxy, done: (ScannedBarcode?) -> Unit) {
        // toBitmap + rotation instead of the mediaImage path: one copy per
        // analysed frame, but no experimental CameraX API.
        val bitmap = frame.toBitmap()
        val rotation = frame.imageInfo.rotationDegrees
        frame.close()
        scanner.process(InputImage.fromBitmap(bitmap, rotation))
            .addOnCompleteListener { task -> done(if (task.isSuccessful) first(task.result) else null) }
    }

    override suspend fun readPicture(picture: Bitmap): ScannedBarcode? = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(picture, 0))
            .addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) first(task.result) else null) }
    }

    override fun close() = scanner.close()

    private fun first(barcodes: List<Barcode>): ScannedBarcode? =
        barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.let { ScannedBarcode(it.rawValue.orEmpty(), kindOf(it.format)) }

    private fun kindOf(format: Int): BarcodeKind = when (format) {
        Barcode.FORMAT_QR_CODE -> BarcodeKind.QR_CODE
        Barcode.FORMAT_AZTEC -> BarcodeKind.AZTEC
        Barcode.FORMAT_CODABAR -> BarcodeKind.CODABAR
        Barcode.FORMAT_CODE_39 -> BarcodeKind.CODE_39
        Barcode.FORMAT_CODE_93 -> BarcodeKind.CODE_93
        Barcode.FORMAT_CODE_128 -> BarcodeKind.CODE_128
        Barcode.FORMAT_DATA_MATRIX -> BarcodeKind.DATA_MATRIX
        Barcode.FORMAT_EAN_8 -> BarcodeKind.EAN_8
        Barcode.FORMAT_EAN_13 -> BarcodeKind.EAN_13
        Barcode.FORMAT_ITF -> BarcodeKind.ITF
        Barcode.FORMAT_PDF417 -> BarcodeKind.PDF417
        Barcode.FORMAT_UPC_A -> BarcodeKind.UPC_A
        Barcode.FORMAT_UPC_E -> BarcodeKind.UPC_E
        else -> BarcodeKind.OTHER
    }
}
