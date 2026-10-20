package com.wasimaster.wmkeyboard.core.input.mozc

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * libwmmozc.so: Google's Mozc converter behind a small JNI bridge
 * (native/mozc-jni). On Play it lives in `:feature:mozc`, on the GitHub
 * build and F-Droid it is delivered on demand, so nothing here may be called
 * before [library] says it is loaded. Its dictionary is not in the library:
 * [nativeStart] opens the `mozc.data` pack the user downloaded.
 *
 * Readings go in as hiragana, with any romaji not yet turned into kana left
 * at the end. Lengths come back in characters of that reading.
 *
 * The menu calls fill `info` and return the texts (see mozc_jni.cc):
 * `info[0]` candidates, `info[1]` the focused segment, `info[2]` segments,
 * then per candidate its id and the reading characters it consumes. The
 * returned array holds the candidate texts, then each segment's reading and
 * best text. Null when Mozc had nothing.
 */
internal object MozcNative {

    val library = GatedLibrary({ FeatureModules.mozc }) {
        runCatching { FeatureModules.loadLibrary("wmmozc") }.isSuccess
    }

    /** Opens Mozc on [dataFile] with its learning in [userDir]; null, or why it did not. */
    @JvmStatic external fun nativeStart(dataFile: String, userDir: String): String?

    /** Saves what was learnt and closes Mozc. */
    @JvmStatic external fun nativeStop()

    @JvmStatic external fun nativeDataVersion(): String

    @JvmStatic external fun nativeSetKanaModifierInsensitive(on: Boolean)

    @JvmStatic external fun nativeSuggest(reading: String, preceding: String, limit: Int, info: IntArray): Array<String>?

    /** [reading] by segments after [ops] (`MozcOps`), with the focused segment's candidates. */
    @JvmStatic external fun nativeConvert(
        reading: String,
        preceding: String,
        ops: IntArray,
        limit: Int,
        info: IntArray,
    ): Array<String>?

    /** What could follow [preceding] with nothing typed. */
    @JvmStatic external fun nativePredict(preceding: String, limit: Int, info: IntArray): Array<String>?

    /** Learns candidate [id] of [nativeSuggest]'s answer; the reading characters it took, or -1. */
    @JvmStatic external fun nativeCommitSuggestion(reading: String, preceding: String, id: Int): Int

    @JvmStatic external fun nativeCommitConversion(reading: String, preceding: String, ops: IntArray, id: Int): Int

    @JvmStatic external fun nativeCommitPrediction(preceding: String, id: Int): Int

    @JvmStatic external fun nativeResetContext()

    @JvmStatic external fun nativeSync(): Boolean

    @JvmStatic external fun nativeClearHistory(): Boolean
}

/** The segment operations [MozcNative.nativeConvert] replays (mozc_bridge.h `Op`). */
internal object MozcOps {
    const val EXPAND = 1
    const val SHRINK = -1
    const val FOCUS_RIGHT = 2
    const val FOCUS_LEFT = -2
}
