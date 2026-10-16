package com.wasimaster.wmkeyboard.ime.aichat

/**
 * The keyboard's half of opening the Ask AI sheet (#697). The sheet is an
 * activity in `:app`, which this module cannot name as a class, so the
 * floating AI button starts it by class name with these extras.
 */
object AskAiLaunch {
    const val ACTIVITY = "com.wasimaster.wmkeyboard.app.AskAiActivity"

    /** The visible text of the app the button was pressed over. Absent when nothing could be read. */
    const val EXTRA_SCREEN_TEXT = "com.wasimaster.wmkeyboard.askai.SCREEN_TEXT"

    /** That app's package, so the sheet can name it. */
    const val EXTRA_SCREEN_PACKAGE = "com.wasimaster.wmkeyboard.askai.SCREEN_PACKAGE"

    /** Set by the floating AI button, so the sheet can say why no screen came along. */
    const val EXTRA_FROM_BUTTON = "com.wasimaster.wmkeyboard.askai.FROM_BUTTON"
}
