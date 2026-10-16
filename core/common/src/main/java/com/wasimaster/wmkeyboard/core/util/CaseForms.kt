package com.wasimaster.wmkeyboard.core.util

/**
 * This string in capitals without changing its spelling or its length.
 *
 * [String.uppercase] follows Unicode's full case mapping, which turns `ß` into
 * `SS` and a few other letters (`ŉ`, `ǰ`, the `ﬀ` ligatures) into two or three.
 * That is right for display and wrong for a re-case: "Spaß" went to "SPASS",
 * the next step down gave "Spass", and the word the user selected never came
 * back (#715). Here `ß` becomes the capital sharp s `ẞ` (U+1E9E), which
 * [lowercaseKeepingLength] maps straight back, and any other letter whose full
 * mapping would change the length is mapped on its own, which leaves it as it
 * is when it has no single-letter capital.
 */
fun String.uppercaseKeepingLength(): String {
    val sharp = replace('ß', 'ẞ')
    val upper = sharp.uppercase()
    return if (upper.length == length) upper else mapCodePoints(sharp) { Character.toUpperCase(it) }
}

/**
 * This string in small letters without changing its spelling or its length.
 *
 * The full mapping is kept whenever it keeps the length, so a Greek word still
 * ends on a final `ς`. The one letter that grows is the dotted capital `İ`,
 * which becomes `i` plus a combining dot; that string is mapped letter by
 * letter instead.
 */
fun String.lowercaseKeepingLength(): String {
    val lower = lowercase()
    return if (lower.length == length) lower else mapCodePoints(this) { Character.toLowerCase(it) }
}

private inline fun mapCodePoints(s: String, map: (Int) -> Int): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val mapped = map(cp)
        // A mapping that would cross between one and two chars keeps the original.
        out.appendCodePoint(if (Character.charCount(mapped) == Character.charCount(cp)) mapped else cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}
