package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.SymbolChord
import com.wasimaster.wmkeyboard.core.layout.drawnLabel

/**
 * How long `?123` has to have been down before a tap on another key is a chord
 * with it (issue #754). A typist who taps `?123` and rolls straight onto the
 * next key lands that finger a few dozen milliseconds in; a held `?123` waiting
 * for its partner has been down far longer.
 */
internal const val CHORD_MIN_HOLD_MS = 120L

/**
 * How long `?123` is held still before the bound keys show their chords. Past
 * a tap, so tapping `?123` to switch layer flashes nothing over the letters.
 */
internal const val CHORD_LABEL_DELAY_MS = 250L

/**
 * What a tap on [key] runs while `?123` is held (issue #754), or null when it
 * types as it always did: no trigger down, the trigger down too briefly to be
 * held ([CHORD_MIN_HOLD_MS]), or no chord on that key.
 */
internal fun resolveSymbolChord(
    triggerHeld: Boolean,
    heldMs: Long,
    key: Key,
    chords: Map<String, KeyAlternate>,
): KeyAlternate? {
    if (!triggerHeld || heldMs < CHORD_MIN_HOLD_MS) return null
    val token = SymbolChord.tokenOf(key) ?: return null
    return chords[token]
}

/**
 * The `?123` chords as they happen (issue #754): one finger holds `?123` while
 * another taps a key. Each key handles only its own pointers, so neither key
 * sees the other finger; this is where they meet. `?123`'s handler writes the
 * trigger, the tapped key asks [tap] before it types, and the grid hands in the
 * chords in force and how to run one.
 *
 * Plain fields rather than state, like [AlternatesGate]: written and read from
 * pointer handlers on the main thread. Only [labels] is drawn.
 */
internal class ChordGate {
    /** Chords in force, by the tapped key's character; empty when chords are off. Written by the grid. */
    var chords: Map<String, KeyAlternate> = emptyMap()

    /** Runs a chord's binding. Written by the grid, which has the sinks a key's action goes to. */
    var run: (KeyAlternate) -> Unit = {}

    private var trigger: PointerId? = null
    private var triggerDownAt = 0L

    /** A chord ran during this hold, so letting go of `?123` switches no layer. */
    private var fired = false

    /**
     * Another finger landed while `?123` was down: its pending hold (alternates,
     * the number pad) is off for this press, as a chord is under way.
     */
    var partnerLanded = false
        private set

    /** The chord each bound key shows on its face while `?123` is held still, or null. */
    val labels = mutableStateOf<Map<String, String>?>(null)

    val active: Boolean get() = chords.isNotEmpty()

    fun isTrigger(id: PointerId): Boolean = trigger == id

    /** `?123` went down: it may be a chord's trigger, when chords are on. */
    fun triggerDown(id: PointerId) {
        if (!active) return
        trigger = id
        triggerDownAt = SystemClock.uptimeMillis()
        fired = false
        partnerLanded = false
    }

    /** `?123` has been held long enough to show what it would do; nothing if it is no longer down. */
    fun showLabels(id: PointerId) {
        if (trigger != id || fired) return
        labels.value = chords.mapValues { (_, binding) -> binding.drawnLabel() }
    }

    /**
     * `?123` lifted, or a gesture took it (a layer peek, #108). True when a
     * chord ran during the hold, and the lift is not the tap that switches
     * layer.
     */
    fun triggerUp(id: PointerId): Boolean {
        if (trigger != id) return false
        val ran = fired
        trigger = null
        fired = false
        partnerLanded = false
        labels.value = null
        return ran
    }

    /** A finger landed on some other key. */
    fun otherDown() {
        if (trigger != null) partnerLanded = true
    }

    /**
     * A tap on [key] is about to type. True when it was a chord instead, which
     * has run, and the key types nothing.
     */
    fun tap(key: Key): Boolean {
        if (key.action != KeyAction.Text) return false
        val binding = resolveSymbolChord(
            triggerHeld = trigger != null,
            heldMs = SystemClock.uptimeMillis() - triggerDownAt,
            key = key,
            chords = chords,
        ) ?: return false
        fired = true
        // The faces have said what they had to; the action is now running.
        labels.value = null
        run(binding)
        return true
    }
}

/** See [ChordGate]; provided once at the root, beside [LocalRestGate]. */
internal val LocalChordGate = staticCompositionLocalOf { ChordGate() }

/**
 * The chord [key] runs, over its face while `?123` is held still (issue #754).
 * A composable of its own so only it reads the labels: holding `?123`
 * recomposes these and leaves the keys under them alone.
 */
@Composable
internal fun BoxScope.ChordFaceLabel(key: Key, background: Color, text: Color) {
    val labels = LocalChordGate.current.labels.value ?: return
    val token = SymbolChord.tokenOf(key) ?: return
    val label = labels[token] ?: return
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 2.dp, start = 2.dp, end = 2.dp)
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp),
    ) {
        Text(label, color = text, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
