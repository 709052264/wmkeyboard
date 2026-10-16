package com.wasimaster.wmkeyboard.ime.aichat

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wasimaster.wmkeyboard.config.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the text in a picture sent in the AI chat (#698), for a model that
 * cannot see it. ML Kit's Latin recognizer on the phone, the same one the
 * scan text tool and the reply tool use.
 */
object ChatImageOcr {

    /** The full build has ML Kit's Latin recognizer bundled. */
    val available: Boolean get() = BuildConfig.ENABLE_ML_KIT_SCANNERS

    /** The text in [bitmap], line by line; empty when there is none. Throws when the read fails. */
    suspend fun read(bitmap: Bitmap): String {
        if (!available) return ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /** A Play Services task as a suspension, as [com.wasimaster.wmkeyboard.ime.reply.ReplyOcr] does. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel(CancellationException("ML Kit task cancelled")) }
    }
}
