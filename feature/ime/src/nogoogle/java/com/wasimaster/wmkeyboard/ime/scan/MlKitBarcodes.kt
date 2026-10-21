package com.wasimaster.wmkeyboard.ime.scan

/**
 * F-Droid stand-in: no ML Kit in this build, so the QR scanner always reads
 * with zxing-cpp ([ZxingBarcodeReader]).
 */
internal object MlKitBarcodes {
    fun reader(): BarcodeReader? = null
}
