package com.wasimaster.wmkeyboard.core.barcode

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.StateFlow

/**
 * JNI surface of zxing-cpp's readers (libwmzxing.so, built from
 * native/zxingcpp-jni), the QR and barcode reader that needs no ML Kit: the
 * F-Droid build's, a Play build's on a phone without Play services, and
 * anyone's who picks it. Optional at runtime: GitHub and F-Droid builds fetch
 * it the first time the scanner opens, Play carries it in the on-demand
 * `:feature:zxing` split, and until then [available] is false and the panel
 * offers the download.
 *
 * Each read returns every code's text as UTF-8 bytes, null when there was
 * none, and fills `formats` with each code's zxing-cpp `BarcodeFormat`
 * value; [ZxingReader] decodes both. Nothing is kept between calls, so any
 * thread may call.
 */
object ZxingNative {
    const val LIBRARY = "wmzxing"

    /** `effort` bits; see wm_zxing_jni.cpp. */
    const val TRY_HARDER = 1
    const val TRY_INVERT = 2

    private val library = GatedLibrary({ FeatureModules.zxing }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    val moduleState: StateFlow<ModuleState> get() = FeatureModules.zxing.state

    fun requestModule() = FeatureModules.zxing.requestInstall()

    /**
     * Codes in a camera frame's luminance plane: [width] by [height] bytes,
     * rows [rowStride] apart, in a direct [buffer] (CameraX's Y plane as it is).
     */
    external fun nativeReadLuma(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        effort: Int,
        maxSymbols: Int,
        formats: IntArray?,
    ): Array<ByteArray>?

    /** Codes in a picture's pixels, [width] by [height] ARGB ints as `Bitmap.getPixels` fills them. */
    external fun nativeReadPixels(
        argb: IntArray,
        width: Int,
        height: Int,
        effort: Int,
        maxSymbols: Int,
        formats: IntArray?,
    ): Array<ByteArray>?
}
