package com.wasimaster.wmkeyboard.core.gesture

/**
 * [DuetBeam]'s sizes: how many gestures a word may have, how many swipes may
 * be open at once, how finely each is resampled, how many states a decode
 * may hold. The state store moved to Rust with the decoder (`wm-engine`'s
 * `glide::duet`); an instance carries nothing, and [DuetBeam] ignores it.
 */
class DuetWorkspace {

    companion object {
        /** Gestures one word may be built from. A token is one bit of an Int. */
        const val MAX_TOKENS = 24

        /** Swipes that can be open at once: one per thumb. */
        const val SLOTS = 2

        /**
         * Most samples one swipe is resampled to; short swipes take fewer.
         * Not [GlideWorkspace.SAMPLE_POINTS]: that went to 64 for long single
         * strokes, and a duet's swipes are pieces of a word, never measured
         * at the finer spacing.
         */
        const val MAX_SAMPLES = 48

        /** Frontier cap: ~1.5 MB of columns once grown into. A backstop, as in [GlideWorkspace]. */
        const val MAX_STATES = 4096
    }
}
