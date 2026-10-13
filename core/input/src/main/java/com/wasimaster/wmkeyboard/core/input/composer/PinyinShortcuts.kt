package com.wasimaster.wmkeyboard.core.input.composer

import java.util.Calendar

/**
 * Candidates that are not in any dictionary, typed through Pinyin the way
 * Sogou, fcitx and ibus-libpinyin take them:
 *
 *  - `rq` (日期) offers today's date, `sj` (时间) the time and `xq` (星期) the
 *    weekday, in the forms Chinese text writes them, one place behind the
 *    reading's own best candidate;
 *  - `v` and then a number, `v1234.5`, offers it in Chinese numerals: 一千二百
 *    三十四点五, the financial 壹仟贰佰叁拾肆点伍, digit by digit, and the
 *    amount in words (壹仟贰佰叁拾肆元整). No pinyin syllable starts with `v`,
 *    so nothing typed as pinyin is taken.
 */
object PinyinShortcuts {

    private const val LOWER_DIGITS = "零一二三四五六七八九"
    private const val UPPER_DIGITS = "零壹贰叁肆伍陆柒捌玖"
    private const val SPOKEN_DIGITS = "〇一二三四五六七八九"
    private val LOWER_UNITS = arrayOf("", "十", "百", "千")
    private val UPPER_UNITS = arrayOf("", "拾", "佰", "仟")
    private val GROUPS = arrayOf("", "万", "亿", "万亿")
    private val WEEKDAYS = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /** Longest integer part spelled: the four groups up to 万亿. */
    private const val MAX_DIGITS = 16

    /** Whether [buffer] is a `v` number, or a `v` still waiting for its digits. */
    fun isNumber(buffer: String): Boolean =
        buffer.length >= 2 && buffer[0] == 'v' && buffer.substring(1).all { it.isDigit() || it == '.' } &&
            buffer.count { it == '.' } <= 1 && buffer[1] != '.'

    /** Whether [c] goes on into [buffer] as part of a `v` number. */
    fun continuesNumber(buffer: CharSequence, c: Char): Boolean {
        if (buffer.isEmpty() || buffer[0] != 'v') return false
        if (!buffer.drop(1).all { it.isDigit() || it == '.' }) return false
        return c.isDigit() || c == '.' && buffer.length >= 2 && '.' !in buffer
    }

    /** The numerals for a [isNumber] buffer, or empty. */
    fun numbers(buffer: String): List<String> {
        if (!isNumber(buffer)) return emptyList()
        val number = buffer.substring(1).trimEnd('.')
        val integer = number.substringBefore('.')
        val fraction = number.substringAfter('.', "")
        if (integer.length > MAX_DIGITS) return listOf(number)
        val out = ArrayList<String>()
        fun withFraction(whole: String, digits: String) =
            if (fraction.isEmpty()) whole else whole + "点" + fraction.map { digits[it - '0'] }.joinToString("")
        out.add(withFraction(lower(integer), LOWER_DIGITS))
        out.add(withFraction(spell(integer, UPPER_DIGITS, UPPER_UNITS), UPPER_DIGITS))
        out.add(number.map { if (it == '.') '点' else SPOKEN_DIGITS[it - '0'] }.joinToString(""))
        if (fraction.isEmpty()) out.add(spell(integer, UPPER_DIGITS, UPPER_UNITS) + "元整")
        out.add(number)
        return out.distinct()
    }

    /** The date, time or weekday a two-letter [buffer] asks for, or empty. */
    fun expand(buffer: String, now: Calendar = Calendar.getInstance()): List<String> {
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val weekday = WEEKDAYS[now.get(Calendar.DAY_OF_WEEK) - 1]
        return when (buffer) {
            "rq" -> listOf(
                "${year}年${month}月${day}日",
                year.toString().map { SPOKEN_DIGITS[it - '0'] }.joinToString("") + "年" + lower(month.toString()) + "月" + lower(day.toString()) + "日",
                "%04d-%02d-%02d".format(year, month, day),
                "${month}月${day}日",
            )
            "sj" -> {
                val half = when {
                    hour < 6 -> "凌晨"
                    hour < 12 -> "上午"
                    hour < 13 -> "中午"
                    hour < 18 -> "下午"
                    else -> "晚上"
                }
                val twelve = if (hour % 12 == 0) 12 else hour % 12
                listOf("%02d:%02d".format(hour, minute), "${hour}点${minute}分", "$half${twelve}点${minute}分")
            }
            "xq" -> listOf("星期$weekday", "周$weekday", "礼拜$weekday")
            else -> emptyList()
        }
    }

    /** [integer] in everyday numerals: 十二, not 一十二. */
    fun lower(integer: String): String {
        val spelled = spell(integer, LOWER_DIGITS, LOWER_UNITS)
        return if (spelled.startsWith("一十")) spelled.substring(1) else spelled
    }

    /**
     * [integer] spelled in groups of four with [digits] and [units]: 零 once
     * for any run of zeros between non-zero digits, and none at the end.
     */
    private fun spell(integer: String, digits: String, units: Array<String>): String {
        val n = integer.trimStart('0')
        if (n.isEmpty()) return digits[0].toString()
        val groups = n.reversed().chunked(4).map { it.reversed().padStart(4, '0') }
        val out = StringBuilder()
        var gap = false
        for (gi in groups.indices.reversed()) {
            val g = groups[gi]
            if (g == "0000") {
                if (out.isNotEmpty()) gap = true
                continue
            }
            if (out.isNotEmpty() && (gap || g[0] == '0')) out.append(digits[0])
            gap = false
            var started = false
            var zero = false
            for (k in 0 until 4) {
                val d = g[k] - '0'
                if (d == 0) {
                    if (started) zero = true
                    continue
                }
                if (zero) out.append(digits[0])
                zero = false
                started = true
                out.append(digits[d]).append(units[3 - k])
            }
            out.append(GROUPS[gi])
        }
        return out.toString()
    }
}
