package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.config.BuildConfig

/**
 * Features the Google Play build leaves out because Play will not accept the
 * permission or component behind them. app/src/play/AndroidManifest.xml
 * removes the declarations; this is the settings half, so no row offers a
 * grant the installed APK cannot hold. The keyboard side needs no gate: both
 * features already check the grant at runtime and stand aside without it.
 *
 * Both came out of Play's review of version code 26 (2026-09-28).
 */
object ChannelFeatures {
    /**
     * Screenshots in the clipboard, behind READ_MEDIA_IMAGES (and
     * READ_EXTERNAL_STORAGE below API 33). Play ruled the watcher "infrequent
     * access" that the photo picker should serve, which it cannot: there is no
     * moment of picking, only a MediaStore observer.
     */
    val SCREENSHOT_CLIPS: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * TalkBack gesture pass-through, behind TouchPassthroughService. Without
     * it, Gestures mode is Explore by another name, so the mode goes too.
     */
    val GESTURE_PASSTHROUGH: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The clock tool's ring service, a foreground service of type
     * systemExempted that plays an alarm while it rings. Play keeps that type
     * for apps whose core job is an alarm clock, and a keyboard is not one.
     * Without it an alarm rings through the insistent alarm notification
     * channel instead: the system's alarm sound, no per-alarm sound or
     * fade-in, and the ring screen still comes up as a full-screen intent.
     */
    val CLOCK_RING_SERVICE: Boolean = !BuildConfig.ENABLE_PLAY_STORE
}
