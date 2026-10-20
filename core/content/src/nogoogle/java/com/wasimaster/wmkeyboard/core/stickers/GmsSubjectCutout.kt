package com.wasimaster.wmkeyboard.core.stickers

import android.content.Context
import android.graphics.Bitmap

/**
 * F-Droid stand-in for ML Kit's subject segmentation: that build carries no
 * Play services library, so there is never a model to report or fetch.
 */
internal object GmsSubjectCutout {

    @Suppress("UnusedParameter")
    suspend fun modelReady(context: Context): Boolean = false

    @Suppress("UnusedParameter")
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit = {}): Boolean = false

    @Suppress("UnusedParameter")
    suspend fun cutOut(image: Bitmap): SubjectCutout.Result = SubjectCutout.Result.Unsupported
}
