package com.wasimaster.wmkeyboard.core.input.imported

/** One value in an m17n database file: the four things a plist can hold. */
sealed interface MimValue {

    data class Sym(val name: String) : MimValue

    data class Num(val value: Int) : MimValue

    data class Text(val value: String) : MimValue

    data class Lst(val items: List<MimValue>) : MimValue
}

/** The symbol name of a value, or null when it is not a symbol. */
internal val MimValue.symbol: String? get() = (this as? MimValue.Sym)?.name

internal val MimValue.items: List<MimValue>? get() = (this as? MimValue.Lst)?.items

/**
 * The reader for the m17n database's file format, a Lisp-like list syntax.
 *
 * Written from the m17n-db documentation and the behaviour of its files, not
 * copied from m17n-lib (LGPL; this app is MIT). The rules that matter, all of
 * which the shipped `.mim` files rely on:
 *
 * - `;` starts a comment that runs to the end of the line.
 * - `"…"` is text. `\x41` and `क` are code points, a backslash before a
 *   newline joins the lines, and `\n`, `\t`, `\e` and the rest are the usual
 *   escapes; any other escaped character is itself.
 * - A token starting with a digit, `-` and a digit, `0x`, `#x` or `?` is a
 *   number. `?க` is that character's code point, `?\\` a backslash.
 * - Anything else up to a space, a paren or a quote is a symbol, so
 *   `(_"text")` is the symbol `_` and then a string.
 */
object MimReader {

    /** Thrown for a file that is not well formed: an unclosed list or string. */
    class SyntaxError(message: String) : Exception(message)

    /** Every top-level form of [text], in order. */
    fun read(text: String): List<MimValue> = Parser(text).readAll()

    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0

        fun readAll(): List<MimValue> {
            val out = ArrayList<MimValue>()
            while (true) {
                skipBlank()
                if (i >= s.length) return out
                if (s[i] == ')') {
                    // A stray closing paren at the top level. m17n's reader
                    // stops there; skipping it reads the rest of the file.
                    i++
                    continue
                }
                out += element()
            }
        }

        private fun skipBlank() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c <= ' ' -> i++
                    c == ';' -> {
                        while (i < s.length && s[i] != '\n') i++
                    }
                    else -> return
                }
            }
        }

        private fun element(): MimValue {
            val c = s[i]
            return when {
                c == '(' -> list()
                c == '"' -> text()
                c == '?' -> character()
                c.isDigit() -> number()
                c == '-' && i + 1 < s.length && s[i + 1].isDigit() -> number()
                c == '#' && i + 1 < s.length && s[i + 1] == 'x' -> {
                    i += 2
                    MimValue.Num(hex())
                }
                else -> symbol()
            }
        }

        private fun list(): MimValue.Lst {
            val start = i
            i++ // (
            if (++depth > MAX_DEPTH) throw SyntaxError("Lists nested too deep at offset $start")
            val items = ArrayList<MimValue>()
            while (true) {
                skipBlank()
                if (i >= s.length) throw SyntaxError("Unclosed list at offset $start")
                if (s[i] == ')') {
                    i++
                    depth--
                    return MimValue.Lst(items)
                }
                items += element()
                if (items.size > MAX_LIST) throw SyntaxError("List too long at offset $start")
            }
        }

        private fun text(): MimValue.Text {
            val start = i
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw SyntaxError("Unclosed string at offset $start")
                val c = s[i++]
                when (c) {
                    '"' -> return MimValue.Text(out.toString())
                    '\\' -> {
                        if (i >= s.length) throw SyntaxError("Unclosed string at offset $start")
                        val e = s[i++]
                        when (e) {
                            '\n' -> Unit
                            'x', 'u' -> {
                                out.appendCodePoint(hex())
                                // m17n lets one space end the number.
                                if (i < s.length && s[i] == ' ') i++
                            }
                            else -> out.append(escape(e))
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun character(): MimValue.Num {
            i++ // ?
            if (i >= s.length) return MimValue.Num(0)
            var cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\\'.code) {
                if (i >= s.length) return MimValue.Num('\\'.code)
                cp = s.codePointAt(i)
                i += Character.charCount(cp)
                if (cp < ASCII_LIMIT) cp = escape(cp.toChar()).code
            }
            return MimValue.Num(cp)
        }

        private fun number(): MimValue {
            val start = i
            val negative = s[i] == '-'
            if (negative) i++
            if (!negative && s[i] == '0' && i + 1 < s.length && s[i + 1] == 'x') {
                i += 2
                return MimValue.Num(hex())
            }
            var n = 0L
            while (i < s.length && s[i].isDigit()) {
                n = (n * 10 + (s[i] - '0')).coerceAtMost(Int.MAX_VALUE.toLong())
                i++
            }
            // `1qa` is the number 1 and then the symbol `qa`, as in m17n.
            if (i == start) return symbol()
            return MimValue.Num(if (negative) -n.toInt() else n.toInt())
        }

        private fun hex(): Int {
            var n = 0L
            while (i < s.length) {
                val d = Character.digit(s[i], HEX)
                if (d < 0) break
                n = ((n shl 4) or d.toLong()).coerceAtMost(Int.MAX_VALUE.toLong())
                i++
            }
            return n.toInt()
        }

        private fun symbol(): MimValue.Sym {
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c <= ' ' || c == '(' || c == ')' || c == '"') break
                i++
                if (c == '\\' && i < s.length) {
                    out.append(escape(s[i++]))
                } else {
                    out.append(c)
                }
            }
            return MimValue.Sym(out.toString())
        }

        private fun escape(c: Char): Char = when (c) {
            'e' -> ESCAPE
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'a' -> '\u0007'
            else -> c
        }
    }

    private const val HEX = 16
    private const val ASCII_LIMIT = 128
    private const val ESCAPE = '\u001B'

    /** A list longer than any real file has, so a hostile one cannot exhaust memory one list at a time. */
    private const val MAX_LIST = 200_000

    /** Real files nest about eight deep; this keeps a hostile one off the end of the stack. */
    private const val MAX_DEPTH = 256
}
