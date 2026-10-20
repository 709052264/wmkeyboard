package com.wasimaster.wmkeyboard.core.gesture

/**
 * [GlideBeam]'s sizes: how finely a stroke is resampled, how large a learned
 * shape is, how many states a decode may hold.
 *
 * The pooled state store itself, the alignment columns and the heap, moved
 * to Rust with the decoder (`wm-engine`'s `glide::workspace`, one per decoding
 * thread), and the Kotlin decoder that used this class kept it in the test
 * sources as the reference. An instance carries nothing. Callers that pool
 * one per thread can keep passing it, and [GlideBeam] ignores it.
 */
class GlideWorkspace {

    companion object {
        /**
         * Path samples the drawn stroke is resampled to. The alignment is
         * O(SAMPLE_POINTS) per trie edge and a column is 4 bytes a sample, so
         * this is the decoder's one real size dial. This is the most a
         * stroke gets; most get [SHORT_SAMPLES].
         *
         * 48 for everything left a long word two and a half samples a key:
         * with a finger's rounded corners, "internationalization" decoded
         * from 5 of 30 noisy strokes, the walk spending its budget on short
         * readings before a twenty-letter one fit. At 64 it decodes from 26.
         * 64 for everything also cost about 40% more CPU on every stroke,
         * so only a stroke of [LONG_STROKE] key widths or more gets it.
         */
        const val SAMPLE_POINTS = 64

        /** Samples a stroke shorter than [LONG_STROKE] is resampled to. */
        const val SHORT_SAMPLES = 48

        /** Stroke length, in key widths, from which a stroke gets [SAMPLE_POINTS] samples. */
        const val LONG_STROKE = 40f

        /**
         * Samples a *shape* has, apart from the alignment's: the learned
         * shapes are saved at this size, and a stored shape of another size
         * is skipped, so changing it would forget every shape a user has
         * taught. The shape channel resamples the stroke down to it.
         */
        const val SHAPE_POINTS = 48

        /**
         * Frontier cap, worth ~2 MB of columns once the pool has grown into
         * it (MAX_STATES × SAMPLE_POINTS × 4 bytes). Best-first order means the
         * states that matter are expanded first, so hitting this is a backstop
         * rather than a bound anyone should reach.
         *
         * It was 3072, and on a long word it was reached every time: a German
         * stroke of fifteen letters or more saturated the pool on every one of
         * 300 synthetic strokes, and the word drawn was never pushed (issue
         * #714). At 8192 none of them do, and the walk ends on its pop budget
         * or its floor instead, in the same time per decode.
         */
        const val MAX_STATES = 8192

        /** Longest key sequence a candidate's ideal path can have. */
        const val MAX_IDEAL_POINTS = 32

        /** Stands in for "unreachable" in a column without overflowing on add. */
        const val UNREACHABLE = Float.MAX_VALUE / 4f

        /** Sentinel for the root, which consumed no trie edge. */
        val NO_LABEL: Char = 0.toChar()
    }
}
