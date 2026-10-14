package com.wasimaster.wmkeyboard.core.clock

/**
 * The Pomodoro rhythm: focus, a short break, focus, a short break, and every
 * [longEvery] focus phases a long break instead of a short one.
 */
data class PomodoroPlan(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    val longEvery: Int = 4,
) {
    fun minutesOf(phase: PomodoroPhase): Int = when (phase) {
        PomodoroPhase.FOCUS -> focusMinutes
        PomodoroPhase.SHORT_BREAK -> shortBreakMinutes
        PomodoroPhase.LONG_BREAK -> longBreakMinutes
    }

    /** The phase after [run] finishes. */
    fun next(run: PomodoroRun): PomodoroRun = when (run.phase) {
        PomodoroPhase.FOCUS -> {
            val done = run.completed + 1
            val long = longEvery > 0 && done % longEvery == 0
            PomodoroRun(if (long) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK, done)
        }
        PomodoroPhase.SHORT_BREAK, PomodoroPhase.LONG_BREAK -> PomodoroRun(PomodoroPhase.FOCUS, run.completed)
    }

    /** Where in the current set of [longEvery] the run is, for the dots under the timer. */
    fun dotsFilled(run: PomodoroRun): Int =
        if (longEvery <= 0) 0 else run.completed % longEvery
}
