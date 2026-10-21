package com.wasimaster.wmkeyboard.core.media

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import java.io.OutputStream

/**
 * Writes an animated GIF with a transparent background, one frame at a time.
 *
 * GIF is what is left when a field takes no WebP: every app that takes any
 * picture at all takes a GIF. It is a poor home for a sticker (256 colours a
 * frame, and a pixel is either there or not, so soft edges go hard), which is
 * why this is only ever the fallback.
 *
 * Each frame is a finished picture of the whole canvas as ARGB pixels. It gets
 * a palette of its own, up to 255 colours chosen by median cut plus one index
 * kept for "nothing here", is cropped to the part that has anything in it, and
 * asks for the canvas to be cleared before the next frame, so no frame leans
 * on the one before. Pixels under half opacity become transparent.
 *
 * The encoding is Rust (`wm_engine::gif` in libwm_engine_jni), a frame per
 * call; the Kotlin encoder it replaced is the test sources' reference, and
 * `GifParityTest` holds the two to the same bytes. No android.graphics in
 * here: the caller hands in `IntArray`s, and the encoder is tested on the JVM
 * against the JDK's own GIF reader.
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {

    private var started = false
    private var finished = false

    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "canvas $width x $height" }
        NativeEngine.requireLoaded()
    }

    /** Adds one frame of `width * height` ARGB pixels, shown for [durationMs]. */
    fun addFrame(argb: IntArray, durationMs: Int) {
        require(argb.size == width * height) { "frame of ${argb.size} pixels on a $width x $height canvas" }
        check(!finished) { "already finished" }
        if (!started) writeHeader()
        out.write(GifNative.frame(argb, width, height, durationMs))
    }

    /** Writes the trailer. A file with no frames is still a valid, empty GIF. */
    fun finish() {
        if (finished) return
        if (!started) writeHeader()
        out.write(TRAILER)
        out.flush()
        finished = true
    }

    private fun writeHeader() {
        started = true
        out.write(GifNative.header(width, height))
    }

    private companion object {
        const val TRAILER = 0x3B
        const val MAX_DIMENSION = 65_535
    }
}

/** JNI surface of `native/wm-engine-jni/src/gif.rs`; [GifEncoder] loads the library first. */
internal object GifNative {
    /** The file's header and its loop-for-ever block. */
    external fun header(width: Int, height: Int): ByteArray

    /** One frame, encoded: graphic control, image descriptor, local palette and compressed pixels. */
    external fun frame(argb: IntArray, width: Int, height: Int, durationMs: Int): ByteArray
}
