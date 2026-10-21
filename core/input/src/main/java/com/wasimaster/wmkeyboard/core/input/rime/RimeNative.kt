package com.wasimaster.wmkeyboard.core.input.rime

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * libwmrime.so: librime 1.17.0 and its dependencies behind a small JNI
 * bridge (native/librime-jni). On Play it lives in `:feature:rime`, on the
 * GitHub build and F-Droid it is delivered on demand, so nothing here may be
 * called before [library] says it is loaded.
 *
 * Byte offsets come back as librime counts them, in the UTF-8 of the input;
 * [RimeRuntime] maps them to characters.
 */
internal object RimeNative {

    val library = GatedLibrary({ FeatureModules.rime }) {
        runCatching { FeatureModules.loadLibrary("wmrime") }.isSuccess
    }

    /** Starts librime on deployed data; the user directory gets its installation id. */
    @JvmStatic external fun nativeStart(sharedDir: String, userDir: String, version: String): Boolean

    @JvmStatic external fun nativeStop()

    /** Stops the service and prepares the deployer; [nativeDeploySchema] per schema follows. */
    @JvmStatic external fun nativeBeginDeploy(sharedDir: String, userDir: String, version: String)

    @JvmStatic external fun nativeDeploySchema(schemaFile: String): Boolean

    @JvmStatic external fun nativeDeployConfig(fileName: String, versionKey: String): Boolean

    /** `id\tname` for each schema of the deployed schema list. */
    @JvmStatic external fun nativeSchemaList(): Array<String>

    @JvmStatic external fun nativeCreateSession(): Long

    @JvmStatic external fun nativeDestroySession(session: Long)

    @JvmStatic external fun nativeSelectSchema(session: Long, schemaId: String): Boolean

    @JvmStatic external fun nativeSetOption(session: Long, option: String, value: Boolean)

    /** Replaces the session's input, unconverted. */
    @JvmStatic external fun nativeSetInput(session: Long, input: String): Boolean

    @JvmStatic external fun nativeInput(session: Long): String

    /**
     * Up to [limit] candidates of the segment being composed. [spans] holds
     * limit + 1 ints: `spans[0]` is the byte offset the segment starts at,
     * `spans[i + 1]` the byte offset candidate i ends at. [comments], when
     * given, holds limit strings and gets each candidate's comment: the
     * spelling it was found under, in a schema that hints spellings (the T9
     * one writes `xi an` for 西安 typed as `94'26`).
     */
    @JvmStatic external fun nativeMenu(session: Long, limit: Int, spans: IntArray, comments: Array<String?>?): Array<String>?

    /**
     * Picks candidate [index] of the segment being composed: null when there
     * is no such candidate, the committed text when the pick finished the
     * input (the user dictionary has learnt it), empty when it did not.
     */
    @JvmStatic external fun nativeSelect(session: Long, index: Int): String?

    @JvmStatic external fun nativeClear(session: Long)

    @JvmStatic external fun nativeVersion(): String
}
