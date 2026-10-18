package com.wasimaster.wmkeyboard.core.kdeconnect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.truncate

/**
 * How to turn what the PC has been sent into what the keyboard's line now says:
 * press Backspace [backspaces] times, then type [insert].
 *
 * This is what lets the *whole* keyboard type on the PC rather than just its
 * letter keys. A glide, a suggestion pick, an autocorrection all rewrite the
 * word in the keyboard's own buffer; the diff replays that on the far side
 * without the PC ever knowing a word was involved. It works because the line
 * only ever changes at its end (the caret is fixed there), so the difference
 * between two states is always "a tail removed, a tail added".
 */
data class RemoteEdit(val backspaces: Int, val insert: String) {
    val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()
}

object RemoteTextDiff {
    /**
     * Backspaces are counted in **code points**: one press on a desktop removes
     * one code point in nearly every toolkit, so an emoji outside the BMP is one
     * press, not the two its UTF-16 length would suggest. The common prefix is
     * likewise never allowed to end inside a surrogate pair.
     */
    fun between(sent: String, now: String): RemoteEdit {
        if (sent == now) return RemoteEdit(0, "")
        var common = 0
        val limit = minOf(sent.length, now.length)
        while (common < limit && sent[common] == now[common]) common++
        // A shared high surrogate followed by differing low ones: the pair
        // differs as a whole, so the prefix ends before it.
        val splitsPair = common > 0 && Character.isHighSurrogate(sent[common - 1]) &&
            (startsWithLowSurrogate(sent, common) || startsWithLowSurrogate(now, common))
        if (splitsPair) common--
        val removed = sent.substring(common)
        return RemoteEdit(removed.codePointCount(0, removed.length), now.substring(common))
    }

    private fun startsWithLowSurrogate(text: String, at: Int): Boolean =
        at < text.length && Character.isLowSurrogate(text[at])
}

/**
 * Finger travel to pointer travel. Nothing about speed or feel is on the wire —
 * the desktop moves the pointer by exactly the numbers it is given — so the
 * curve is ours: slow, careful movement is scaled *down* for precision, a fast
 * flick is scaled up so the pointer can cross a monitor in one swipe.
 *
 * Input is in dp, not pixels, so the same swipe means the same thing on every
 * phone.
 *
 * One instance lives for one pad and is [reset] at every touch-down, because
 * [apply] keeps two kinds of state across frames:
 * - **the speed is smoothed.** Touch frames arrive 4 ms apart, then 12, then 8;
 *   a speed read off one frame swings with that cadence, and so would the gain,
 *   so a steady drag came out as a pointer lurching faster and slower.
 * - **the fraction left over is carried.** An X11 desktop truncates a delta to
 *   whole pixels, so a slow drag sending 0.6 a frame moved nothing at all and
 *   then jumped once the finger sped up. Only whole pixels go out; the rest
 *   waits for the next frame.
 */
class PointerAcceleration(
    /** The user's multiplier, 1 = default. */
    private val sensitivity: Float,
    private val accelerate: Boolean,
) {
    private var speed = Double.NaN
    private var carryX = 0.0
    private var carryY = 0.0

    /** A new touch: no speed yet, nothing owed from the last one. */
    fun reset() {
        speed = Double.NaN
        carryX = 0.0
        carryY = 0.0
    }

    /** The gain for a movement of [distanceDp] over [elapsedMs], with no history. */
    fun gain(distanceDp: Double, elapsedMs: Long): Double = gainAt(distanceDp / elapsedMs.coerceIn(1, MAX_FRAME_MS))

    private fun gainAt(speedDpPerMs: Double): Double {
        val base = BASE_GAIN * sensitivity
        if (!accelerate) return base
        val curve = (SLOW_GAIN + (speedDpPerMs / KNEE_DP_PER_MS) * (1.0 - SLOW_GAIN) * 2.0).coerceIn(SLOW_GAIN, MAX_BOOST)
        return base * curve
    }

    /** Whole desktop pixels to send for this frame's finger travel; often (0, 0). */
    fun apply(dxDp: Double, dyDp: Double, elapsedMs: Long): Pair<Double, Double> {
        val sample = hypot(dxDp, dyDp) / elapsedMs.coerceIn(1, MAX_FRAME_MS)
        speed = if (speed.isNaN()) sample else speed + (sample - speed) * SPEED_SMOOTHING
        val g = gainAt(speed)
        carryX += dxDp * g
        carryY += dyDp * g
        val outX = truncate(carryX)
        val outY = truncate(carryY)
        carryX -= outX
        carryY -= outY
        return outX to outY
    }

    companion object {
        /** Desktop pixels per dp of finger travel at sensitivity 1, unaccelerated. */
        const val BASE_GAIN = 1.8
        const val SLOW_GAIN = 0.55
        const val MAX_BOOST = 3.4

        /** The speed at which the curve crosses 1×: about a relaxed drag. */
        const val KNEE_DP_PER_MS = 0.55
        const val MAX_FRAME_MS = 64L

        /** How far each frame pulls the smoothed speed towards its own: a few frames to settle. */
        const val SPEED_SMOOTHING = 0.35
    }
}

/**
 * Two-finger travel to scroll packets.
 *
 * The desktops disagree about what a scroll packet *means*: under Wayland its
 * magnitude is a distance, under X11 every packet is one wheel click whatever
 * its magnitude. Sending a packet per touch frame therefore scrolls a page per
 * centimetre on X11. So travel is accumulated and released one [stepDp] at a
 * time — a click per step on X11, a step's worth of distance on Wayland — and
 * both scroll at a sane rate.
 */
class ScrollAccumulator(
    private val stepDp: Double = 10.0,
    /** The user's multiplier, 1 = default. */
    private val speed: Float = 1f,
    /** Content follows the fingers, as on the phone itself. */
    private val natural: Boolean = true,
) {
    private var x = 0.0
    private var y = 0.0

    fun reset() {
        x = 0.0
        y = 0.0
    }

    /**
     * Adds finger travel (dp, screen coordinates: down and right positive) and
     * returns the `dx`/`dy` to send, or null while the step is not reached.
     */
    fun add(dxDp: Double, dyDp: Double): Pair<Double, Double>? {
        x += dxDp
        y += dyDp
        if (abs(x) < stepDp && abs(y) < stepDp) return null
        // kdeconnect's wire convention is a mouse wheel's: positive dy scrolls
        // *up*. Fingers moving down the glass (positive) should, naturally,
        // drag the content down with them — which is scrolling up.
        val sign = if (natural) 1.0 else -1.0
        val out = (x * speed * sign * WIRE_UNITS_PER_DP) to (y * speed * sign * WIRE_UNITS_PER_DP)
        reset()
        return out
    }

    companion object {
        const val WIRE_UNITS_PER_DP = 1.6
    }
}
