package com.wasimaster.wmkeyboard.core.localllm

import java.io.IOException

/**
 * The on-device model cannot take pictures: it ships no vision encoder, or the
 * runtime could not start one on this device (#234).
 *
 * Its own type, and an [IOException] like every other engine failure, so a
 * caller that only shows errors keeps working while one with a second route —
 * the reply tool reads the screenshot with OCR instead — can catch exactly this
 * and fall back.
 */
class VisionUnsupportedException(message: String, cause: Throwable? = null) : IOException(message, cause)
