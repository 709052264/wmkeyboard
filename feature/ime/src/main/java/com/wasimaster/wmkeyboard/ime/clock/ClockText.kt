package com.wasimaster.wmkeyboard.ime.clock

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import com.wasimaster.wmkeyboard.core.clock.Span
import java.util.Locale

/**
 * Durations in words, in the user's language: "7 hr, 12 min", "7 ч 12 мин",
 * "৭ ঘঃ ১২ মিঃ". ICU's own unit names and digits (API 24), so no string
 * resource has to guess at every language's plural rules for "hours".
 */
object ClockText {

    /**
     * [ms] as days, hours and minutes, the two largest that are not zero.
     * Under a minute reads as seconds when [seconds], otherwise as one minute,
     * so "rings in" never says "0 min".
     */
    fun duration(ms: Long, seconds: Boolean = false, locale: Locale = Locale.getDefault()): String {
        val s = Span.of(ms)
        val parts = buildList {
            if (s.days > 0) add(Measure(s.days, MeasureUnit.DAY))
            if (s.hours > 0) add(Measure(s.hours, MeasureUnit.HOUR))
            if (s.minutes > 0) add(Measure(s.minutes, MeasureUnit.MINUTE))
            if (seconds && s.seconds > 0) add(Measure(s.seconds, MeasureUnit.SECOND))
        }.take(2).ifEmpty {
            listOf(if (seconds) Measure(s.seconds, MeasureUnit.SECOND) else Measure(1, MeasureUnit.MINUTE))
        }
        return runCatching {
            MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(*parts.toTypedArray())
        }.getOrElse { parts.joinToString(" ") { "${it.number} ${it.unit.subtype}" } }
    }

    /** One amount of one unit, in words: "3 days", "12 weeks". */
    fun amount(n: Long, unit: MeasureUnit, locale: Locale = Locale.getDefault()): String =
        runCatching {
            MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(n, unit))
        }.getOrElse { "$n ${unit.subtype}" }
}
