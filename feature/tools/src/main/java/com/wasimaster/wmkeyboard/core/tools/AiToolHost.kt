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

    /** Whether [openApp] can launch anything from this surface. */
    val canOpenApps: Boolean get() = false

    /**
     * Opens the installed app best matching [name], and gives back its label.
     * A [NoSuchElementException] means no app matched. Blocking.
     */
    fun openApp(name: String): Result<String> =
        Result.failure(UnsupportedOperationException("no app launcher here"))

    /** Whether [sendToPc] has KDE Connect to send through. */
    val canSendToPc: Boolean get() = false

    /**
     * Sends [text] to the user's connected computer over KDE Connect, and
     * gives back the computer's name. Blocking: it waits for the link.
     */
    fun sendToPc(text: String): Result<String> =
        Result.failure(UnsupportedOperationException("no KDE Connect here"))

    /** How many pictures the user has sent in this conversation (#698). */
    val imageCount: Int get() = 0

    /** Whether the model reads pictures itself, which makes [readImageText] needless. */
    val modelSeesImages: Boolean get() = false

    /** Picture [number], counted from 1, as JPEG bytes; null when it is gone. */
    fun imageJpeg(number: Int): ByteArray? = null

    /** Whether [readImageText] has a reader in this build and setup. */
    val canReadImageText: Boolean get() = false

    /** The text in picture [number], counted from 1; empty when it has none. Blocking. */
    fun readImageText(number: Int): Result<String> =
        Result.failure(UnsupportedOperationException("no picture reader here"))
}
