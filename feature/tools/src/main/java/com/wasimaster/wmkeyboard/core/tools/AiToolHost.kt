package com.wasimaster.wmkeyboard.core.tools

/**
 * What a surface lends the AI's tools that this module cannot reach on its own
 * (#698): the parts of a tool that live in the keyboard module, or that only
 * make sense where there is a screen to act on.
 *
 * Every member has a default meaning "not here", so a surface implements only
 * what it has, and a tool whose piece is missing is simply not offered.
 */
interface AiToolHost {

    /** Whether [translateOnDevice] can do anything in this build. */
    val canTranslateOnDevice: Boolean get() = false

    /**
     * [text] translated on the phone, or a failure saying why not: the pair's
     * models are not downloaded, the engine's module is missing. Blocking.
     */
    fun translateOnDevice(text: String, target: String, source: String): Result<Translation> =
        Result.failure(UnsupportedOperationException("no on-device translator here"))
}
