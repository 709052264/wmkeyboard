package com.wasimaster.wmkeyboard.core.settings

/**
 * The floating button (#755): one small round button drawn over every app,
 * whose tap, double tap and press and hold each run a [FloatingAction], and
 * whose quick menu fans more of them out around it.
 *
 * It began as two buttons, the keyboard button (#374) and the AI button
 * (#697), and someone who wanted both had two bubbles on screen. Now there is
 * one, and either of those is just a choice of what its tap does.
 *
 * Grouped for the reason every family here is (see [CameraSettings]): one
 * top-level slot for the whole feature. The DataStore keys stay flat
 * (`summon_button`, `summon_button_x`, `summon_button_y`, `floating_button_*`).
 * A user who had only the AI button on comes back with the button on and its
 * tap asking the AI, at the AI button's old place, until they change the tap.
 */
data class SummonButtonSettings(
    /**
     * Draw the button. Needs Android's "Display over other apps" grant as
     * well; with the switch on and no grant, nothing is drawn and the settings
     * page says what is missing.
     */
    val enabled: Boolean = false,
    /**
     * Where the button rests, as fractions of the room the screen leaves it
     * (0 = left edge, 1 = right edge). Only ever written by dragging it, and a
     * drag always ends against one side, so in practice this is 0 or 1.
     */
    val xFraction: Float = 1f,
    /** The same for the vertical axis (0 = top, 1 = bottom). */
    val yFraction: Float = 0.6f,
    /** What a tap does. The keyboard by default, which is what the button was first for. */
    val tap: FloatingAction = FloatingAction.Keyboard,
    /**
     * What a double tap does. Off by default: while it is bound, a single tap
     * waits out the double-tap window before it runs, and that wait is felt.
     */
    val doubleTap: FloatingAction = FloatingAction.None,
    /** What a press and hold does. The quick menu, which a slide can pick from without letting go. */
    val hold: FloatingAction = FloatingAction.Menu,
    /** The quick menu's slots, in the order they fan out; at most [MAX_FLOATING_MENU_ITEMS]. */
    val menu: List<FloatingAction> = DefaultFloatingMenu,
    /** Under each quick menu slot, its name. */
    val menuLabels: Boolean = true,
    /** The button's diameter in dp; see [FloatingButtonSizeRange]. */
    val sizeDp: Int = 52,
    val style: FloatingButtonStyle = FloatingButtonStyle.AUTO,
    /** How solid the button is while it is being used, 0.3 to 1. */
    val opacity: Float = 0.92f,
    /**
     * Seconds untouched before the button fades to [idleOpacity] (and tucks
     * itself into the edge with [tuck]); 0 never fades. See [FloatingButtonIdleRange].
     */
    val idleFadeSeconds: Int = 3,
    /** How solid the button is once it has faded, 0.15 to 1. */
    val idleOpacity: Float = 0.45f,
    /** A faded button slides half off the side it rests on, out of the way of the app. */
    val tuck: Boolean = false,
    /**
     * Stay on screen while the keyboard is up, lifted clear of it. Off, the
     * button goes when the keyboard comes and returns when it leaves.
     */
    val whileTyping: Boolean = true,
    /** Leave landscape alone: games and videos, mostly. */
    val hideInLandscape: Boolean = false,
    /** A tick on a tap, a firmer one when the menu opens or a slot is picked. */
    val haptics: Boolean = true,
)

/** Whether any of the button's gestures, or its quick menu, asks the AI. */
fun SummonButtonSettings.usesAskAi(): Boolean =
    FloatingAction.AskAi in listOf(tap, doubleTap, hold) || FloatingAction.AskAi in opensMenuItems()

/** Whether any of the button's gestures, or its quick menu, reads the screen: Ask AI, or Look up (#765). */
fun SummonButtonSettings.readsScreen(): Boolean {
    val bound = listOf(tap, doubleTap, hold) + opensMenuItems()
    return FloatingAction.AskAi in bound || FloatingAction.LookUp in bound
}

/** The quick menu's slots when some gesture opens it, else nothing. */
fun SummonButtonSettings.opensMenuItems(): List<FloatingAction> =
    if (FloatingAction.Menu in listOf(tap, doubleTap, hold)) menu else emptyList()
