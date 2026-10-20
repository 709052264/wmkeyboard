package com.wasimaster.wmkeyboard.core.tools

/**
 * LaTeX math, the part of it models write (#811): `$x^2$` in a sentence,
 * `$$\frac{a}{b}$$` on a line of its own, and the usual environments
 * (`pmatrix`, `cases`, `aligned`).
 *
 * [parse] reads the source into a tree of [Node]s. Drawing it is the caller's:
 * a chat bubble lays a display formula out in two dimensions, and everything
 * that cannot ([inline] for a run of text, [plain] for a text field) writes it
 * out on one line with Unicode, `x²` and `√(x+1)`.
 *
 * The parser never fails. An answer arrives as a stream, so half a formula is
 * the normal case: an unclosed group closes at the end, a command missing its
 * argument gets an empty one, and an unknown command is drawn as its own name
 * so nothing the model wrote disappears.
 *
 * No Compose here, so it runs on the JVM under test.
 */
object LatexMath {

    /** TeX's atom classes, as far as spacing needs them. */
    enum class Kind { ORD, OP, BIN, REL, OPEN, CLOSE, PUNCT }

    sealed interface Node

    /**
     * One run of glyphs. [italic] is TeX's variable style: a single Latin or
     * lowercase Greek letter. [large] is a big operator (`\sum`, `\int`), and
     * [limits] puts its scripts over and under it in a display formula.
     */
    data class Atom(
        val text: String,
        val kind: Kind = Kind.ORD,
        val italic: Boolean = false,
        val bold: Boolean = false,
        val large: Boolean = false,
        val limits: Boolean = false,
    ) : Node

    data class Row(val items: List<Node>) : Node

    /** [rule] false is a `\binom`'s stack, with no line between. */
    data class Frac(val num: Node, val den: Node, val rule: Boolean = true) : Node

    data class Root(val body: Node, val index: Node? = null) : Node

    /**
     * [limits] forces the scripts over and under ([over] for `\overset`), or
     * beside; null leaves it to the base.
     */
    data class Scripts(val base: Node, val sup: Node?, val sub: Node?, val limits: Boolean? = null) : Node

    enum class Mark { HAT, BAR, VEC, TILDE, DOT, DDOT, CHECK, BREVE, ACUTE, GRAVE, OVERLINE, UNDERLINE }

    data class Accent(val body: Node, val mark: Mark) : Node

    /** `\left( … \right)`. An empty side is `\left.`, no delimiter at all. */
    data class Fenced(val left: String, val body: Node, val right: String) : Node

    enum class Align { START, CENTER, END }

    /**
     * A matrix, `cases` or `aligned`. [align] is per column, repeating;
     * [paired] is `aligned`'s rule that columns go right, left, right, left,
     * with no gap inside a pair, so `x &= 1` lines up on its `=`.
     */
    data class Grid(
        val rows: List<List<Node>>,
        val left: String = "",
        val right: String = "",
        val align: Align = Align.CENTER,
        val paired: Boolean = false,
        /** Cells are display style (`aligned`, `dcases`) rather than text style. */
        val display: Boolean = false,
    ) : Node

    /** Explicit space, in em. Negative for `\!`. */
    data class Space(val em: Float) : Node

    // ------------------------------------------------------------------ finding

    /**
     * Inline math in a run of text: `$…$`, `\(…\)`, and `$$…$$` when
     * it sits inside a sentence. Group 1, 2 or 3 is the source. `\[…\]` is
     * display only ([displayOpen]): inside a sentence it is far more often an
     * escaped `[1]` than a formula.
     *
     * A lone `$` is money more often than math, so the pandoc rule decides: the
     * opening `$` has a non-space after it, the closing one a non-space before
     * it and no digit after it. `$5 and $10` stays text, `$x$` is math.
     */
    val INLINE = Regex(
        """\$\$(.+?)\$\$|\\\((.+?)\\\)|(?<![\\$])\$(?![\s$])((?:\\.|[^$\\\n])+?)(?<![\s\\])\$(?![\d$])""",
    )

    /** The source of an [INLINE] match. */
    fun source(match: MatchResult): String =
        (1..3).firstNotNullOf { match.groups[it]?.value }

    /** True when [text] holds anything [INLINE] or a `$$` line would read as math. */
    fun contains(text: String): Boolean =
        INLINE.containsMatchIn(text) || text.lineSequence().any { displayOpen(it) != null }

    /**
     * What opens a display formula on a line of its own, and what closes it:
     * `$$`, `\[`, or `\begin{equation}` and its kin. Null for any other line.
     */
    fun displayOpen(line: String): String? {
        val t = line.trim()
        return when {
            t.startsWith("$$") -> "$$"
            t.startsWith("\\[") -> "\\]"
            else -> DISPLAY_ENV.matchEntire(t)?.let { "\\end{${it.groupValues[1]}}" }
        }
    }

    /**
     * A display formula read from [lines], starting at [start]. [end] is the
     * line after it. Null when the line does not open one, or when it opens
     * and closes one with more text after it (`$$a$$ and $$b$$`), which is
     * inline math in a sentence.
     *
     * A formula that has not closed yet ([closed] false) runs to a blank line
     * or the end: TeX allows no blank line inside one, and a stray `$$` must
     * not swallow the rest of an answer.
     */
    fun display(lines: List<String>, start: Int): Display? {
        val first = lines[start].trim()
        val closer = displayOpen(first) ?: return null
        val env = closer.startsWith("\\end")
        val rest = if (env) first else first.substring(2)
        // An environment's own `\begin` holds no closer, so looking from 0 is safe.
        val at = rest.indexOf(closer)
        if (at >= 0) {
            val after = rest.substring(at + closer.length)
            if (after.any { !it.isWhitespace() && it !in TRAILING }) return null
            val latex = if (env) rest.substring(0, at + closer.length) else rest.substring(0, at)
            return Display(latex.trim(), start + 1, true)
        }
        val body = StringBuilder(rest)
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) return Display(body.toString().trim(), i, false)
            val c = line.indexOf(closer)
            if (c >= 0) {
                body.append('\n').append(if (env) line.substring(0, c + closer.length) else line.substring(0, c))
                return Display(body.toString().trim(), i + 1, true)
            }
            body.append('\n').append(line)
            i++
        }
        return Display(body.toString().trim(), lines.size, false)
    }

    data class Display(val latex: String, val end: Int, val closed: Boolean)

    /** Punctuation that may follow a display formula on its closing line, and is dropped. */
    private const val TRAILING = ".,;:"

    private val DISPLAY_ENV =Regex("""\\begin\{(equation\*?|align\*?|aligned|gather\*?|gathered|multline\*?|eqnarray\*?|cases|[pbBvV]?matrix|split)}.*""")

    // ------------------------------------------------------------------ parsing

    fun parse(latex: String): Node = Parser(latex).top()

    private class Tok(val text: String, val command: Boolean)

    private class Parser(val s: String) {
        var i = 0

        /** How deep the parser has recursed; see [MAX_DEPTH]. */
        var depth = 0

        fun top(): Node {
            val g = grid(TOP_END)
            return when {
                g.size == 1 && g[0].size == 1 -> g[0][0]
                // `a \\ b` with no `&`: lines stacked and centred, as `gather` does.
                g.all { it.size == 1 } -> Grid(g, display = true)
                else -> Grid(g, align = Align.START, paired = true, display = true)
            }
        }

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun peek(): Tok? {
            skipSpace()
            if (i >= s.length) return null
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                if (n.isLetter()) {
                    var j = i + 1
                    while (j < s.length && s[j].isLetter()) j++
                    // `\align*` style names keep their star.
                    return Tok(s.substring(i + 1, j), true)
                }
                return Tok(n.toString(), true)
            }
            val cp = s.codePointAt(i)
            return Tok(String(Character.toChars(cp)), false)
        }

        fun next(): Tok? {
            val t = peek() ?: return null
            i += if (t.command) t.text.length + 1 else t.text.length
            return t
        }

        private fun isEnd(t: Tok, end: Set<String>): Boolean {
            val key = if (t.command) "\\" + t.text else t.text
            return key in end
        }

        /** Rows of cells, split on `\\` and `&`, until a token in [end]. */
        fun grid(end: Set<String>): List<List<Node>> {
            val rows = ArrayList<List<Node>>()
            var row = ArrayList<Node>()
            while (true) {
                row += expr(end + CELL_END)
                val t = peek()
                if (t == null || isEnd(t, end)) break
                next()
                when {
                    t.command && t.text == "\\" || t.command && t.text == "cr" -> {
                        rows += row
                        row = ArrayList()
                        // `\\[4pt]` is extra space under the row; it is not drawn.
                        if (i < s.length && s[i] == '[') skipBracket()
                    }
                    else -> Unit // `&`: the next cell
                }
            }
            rows += row
            // A trailing `\\` leaves an empty last row that nobody meant.
            if (rows.size > 1 && rows.last().all { it == EMPTY }) rows.removeAt(rows.lastIndex)
            return rows
        }

        private fun skipBracket() {
            var depth = 0
            while (i < s.length) {
                val c = s[i++]
                if (c == '[') depth++
                if (c == ']' && --depth == 0) return
            }
        }

        /** A run of atoms until a token in [end], which is left unread. */
        fun expr(end: Set<String>): Node {
            if (depth >= MAX_DEPTH) return rest()
            depth++
            try {
                return exprBody(end)
            } finally {
                depth--
            }
        }

        /**
         * Everything left, as it is written: what a formula nested past
         * [MAX_DEPTH] becomes, so `\frac\frac\frac…` cannot overflow the stack.
         */
        private fun rest(): Node {
            val tail = s.substring(i)
            i = s.length
            return if (tail.isBlank()) EMPTY else Atom(tail.trim())
        }

        private fun exprBody(end: Set<String>): Node {
            val items = ArrayList<Node>()
            while (true) {
                val t = peek() ?: break
                if (isEnd(t, end)) break
                if (!t.command && (t.text == "^" || t.text == "_")) {
                    next()
                    val arg = argument()
                    attach(items, sup = t.text == "^", arg)
                    continue
                }
                if (!t.command && t.text == "'") {
                    next()
                    var primes = "′"
                    while (i < s.length && s[i] == '\'') {
                        i++
                        primes += "′"
                    }
                    attach(items, sup = true, Atom(primes))
                    continue
                }
                if (t.command && (t.text == "limits" || t.text == "nolimits")) {
                    next()
                    val last = items.lastOrNull()
                    if (last is Atom) items[items.lastIndex] = last.copy(limits = t.text == "limits")
                    if (last is Scripts) items[items.lastIndex] = last.copy(limits = t.text == "limits")
                    continue
                }
                if (!t.command && t.text == "}") {
                    // A stray closing brace: skip it rather than stop.
                    next()
                    continue
                }
                next()
                atom(t)?.let { items += it }
            }
            return row(items)
        }

        private fun attach(items: MutableList<Node>, sup: Boolean, arg: Node) {
            val last = items.lastOrNull()
            if (last is Scripts) {
                val primes = last.sup
                val merged = when {
                    sup && last.sup == null -> last.copy(sup = arg)
                    !sup && last.sub == null -> last.copy(sub = arg)
                    // `x'^2`: the primes and the power share one superscript.
                    sup && primes is Atom && primes.text.all { it == '′' } -> last.copy(sup = row(listOf(primes, arg)))
                    else -> null
                }
                if (merged != null) {
                    items[items.lastIndex] = merged
                    return
                }
            }
            // A script with nothing to sit on (`^2` first, or `x^2^3`) gets an empty base.
            val base = if (last != null && last !is Scripts && last !is Space) items.removeAt(items.lastIndex) else EMPTY
            items += if (sup) Scripts(base, arg, null) else Scripts(base, null, arg)
        }

        /** One argument: a `{group}`, or a single token. */
        fun argument(): Node {
            if (depth >= MAX_DEPTH) return rest()
            depth++
            try {
                return argumentBody()
            } finally {
                depth--
            }
        }

        private fun argumentBody(): Node {
            val t = peek() ?: return EMPTY
            if (!t.command && t.text == "{") {
                next()
                return group()
            }
            if (!t.command && t.text == "}") return EMPTY
            next()
            return atom(t) ?: EMPTY
        }

        /** After a `{`: up to and past its `}`. */
        fun group(): Node {
            val body = expr(setOf("}"))
            if (peek()?.let { !it.command && it.text == "}" } == true) next()
            return body
        }

        /** A `{…}` taken as it is written, for `\text` and environment names. */
        fun rawGroup(): String {
            skipSpace()
            if (i >= s.length || s[i] != '{') {
                val t = next() ?: return ""
                return t.text
            }
            i++
            val start = i
            var depth = 1
            while (i < s.length) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                    '\\' -> i++
                }
                i++
            }
            val out = s.substring(start, i.coerceAtMost(s.length))
            if (i < s.length) i++
            return out
        }

        /** `[…]` straight after a command, for `\sqrt[3]{x}`. */
        private fun optional(): Node? {
            skipSpace()
            if (i >= s.length || s[i] != '[') return null
            i++
            val body = expr(setOf("]"))
            if (i < s.length && s[i] == ']') i++
            return body
        }

        /** A delimiter after `\left`, `\right` or `\big`. */
        private fun delimiter(): String {
            val t = next() ?: return ""
            if (!t.command) return if (t.text == ".") "" else t.text
            return when (t.text) {
                "{", "lbrace" -> "{"
                "}", "rbrace" -> "}"
                "|", "Vert" -> "‖"
                "vert" -> "|"
                "langle" -> "⟨"
                "rangle" -> "⟩"
                "lfloor" -> "⌊"
                "rfloor" -> "⌋"
                "lceil" -> "⌈"
                "rceil" -> "⌉"
                "lbrack" -> "["
                "rbrack" -> "]"
                "uparrow" -> "↑"
                "downarrow" -> "↓"
                else -> SYMBOLS[t.text]?.first ?: ""
            }
        }

        fun atom(t: Tok): Node? {
            if (!t.command) return char(t.text)
            val name = t.text
            GREEK[name]?.let { return Atom(it, italic = name[0].isLowerCase()) }
            SYMBOLS[name]?.let { (text, kind) -> return Atom(text, kind) }
            LARGE[name]?.let { (text, limits) -> return Atom(text, Kind.OP, large = true, limits = limits) }
            if (name in FUNCTIONS) return Atom(name, Kind.OP, limits = name in LIMIT_FUNCTIONS)
            SPACES[name]?.let { return Space(it) }
            ACCENTS[name]?.let { return Accent(argument(), it) }
            STYLES[name]?.let { return restyle(argument(), it) }
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> Frac(argument(), argument())
                "binom", "dbinom", "tbinom" -> Fenced("(", Frac(argument(), argument(), rule = false), ")")
                "choose" -> Atom("choose", Kind.BIN)
                "sqrt" -> {
                    val index = optional()
                    Root(argument(), index)
                }
                "text", "textrm", "textnormal", "mbox", "hbox", "textup", "textsf", "texttt" -> Atom(rawGroup())
                "textit", "emph" -> Atom(rawGroup(), italic = true)
                "textbf" -> Atom(rawGroup(), bold = true)
                "operatorname" -> Atom(rawGroup(), Kind.OP)
                "left" -> {
                    val left = delimiter()
                    val body = expr(setOf("\\right"))
                    if (peek()?.let { it.command && it.text == "right" } == true) next()
                    Fenced(left, body, delimiter())
                }
                "right" -> {
                    delimiter()
                    null
                }
                "middle" -> Atom(delimiter(), Kind.REL)
                "big", "Big", "bigg", "Bigg" -> Atom(delimiter())
                "bigl", "Bigl", "biggl", "Biggl" -> Atom(delimiter(), Kind.OPEN)
                "bigr", "Bigr", "biggr", "Biggr" -> Atom(delimiter(), Kind.CLOSE)
                "bigm", "Bigm" -> Atom(delimiter(), Kind.REL)
                "overset", "stackrel" -> {
                    val over = argument()
                    Scripts(argument(), over, null, limits = true)
                }
                "underset" -> {
                    val under = argument()
                    Scripts(argument(), null, under, limits = true)
                }
                "overbrace" -> Scripts(Accent(argument(), Mark.OVERLINE), null, null, limits = true)
                "underbrace" -> Scripts(Accent(argument(), Mark.UNDERLINE), null, null, limits = true)
                "xrightarrow", "xleftarrow" -> {
                    optional()
                    Scripts(Atom(if (name == "xrightarrow") "⟶" else "⟵", Kind.REL), argument(), null, limits = true)
                }
                "not" -> {
                    // Guarded like an argument: `\not\not\not…` recurses through here.
                    if (depth >= MAX_DEPTH) return rest()
                    depth++
                    try {
                        when (val n = peek()?.let { next(); atom(it) }) {
                            is Atom -> n.copy(text = NEGATED[n.text] ?: (n.text + "\u0338"))
                            else -> n
                        }
                    } finally {
                        depth--
                    }
                }
                "pmod" -> Row(listOf(Space(0.5f), Atom("(", Kind.OPEN), Atom("mod", Kind.OP), Space(0.3f), argument(), Atom(")", Kind.CLOSE)))
                "bmod", "mod" -> Atom("mod", Kind.BIN)
                "boxed", "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "mathop" ->
                    if (name == "boxed" || name == "mathop") argument() else null
                "color" -> {
                    rawGroup()
                    null
                }
                "textcolor", "colorbox" -> {
                    rawGroup()
                    argument()
                }
                "label", "tag", "ref", "eqref" -> {
                    rawGroup()
                    null
                }
                "phantom", "hphantom", "vphantom" -> {
                    argument()
                    null
                }
                "nonumber", "notag" -> null
                "begin" -> environment(rawGroup())
                "end" -> {
                    rawGroup()
                    null
                }
                // `\\` outside a grid is caught by [grid]; inside a group it is a stray.
                "\\" -> null
                else -> if (name.length == 1) {
                    // `\{`, `\%`, `\$`, `\#`, `\_`, `\&`: the character itself.
                    char(name)
                } else {
                    Atom("\\" + name)
                }
            }
        }

        private fun environment(name: String): Node {
            val bare = name.removeSuffix("*")
            if (bare == "array" || bare == "tabular" || bare == "alignat") rawGroup()
            val rows = grid(setOf("\\end"))
            if (peek()?.let { it.command && it.text == "end" } == true) {
                next()
                rawGroup()
            }
            return when (bare) {
                "matrix", "smallmatrix" -> Grid(rows)
                "pmatrix" -> Grid(rows, "(", ")")
                "bmatrix" -> Grid(rows, "[", "]")
                "Bmatrix" -> Grid(rows, "{", "}")
                "vmatrix" -> Grid(rows, "|", "|")
                "Vmatrix" -> Grid(rows, "‖", "‖")
                "cases" -> Grid(rows, "{", "", Align.START)
                "dcases" -> Grid(rows, "{", "", Align.START, display = true)
                "rcases" -> Grid(rows, "", "}", Align.START)
                "array", "tabular" -> Grid(rows)
                "gather", "gathered", "multline", "equation" ->
                    if (rows.size == 1 && rows[0].size == 1) rows[0][0] else Grid(rows, display = true)
                else -> // align, aligned, split, eqnarray, alignat and friends
                    if (rows.size == 1 && rows[0].size == 1) rows[0][0] else Grid(rows, align = Align.START, paired = true, display = true)
            }
        }

        private fun char(c: String): Node? {
            val ch = c[0]
            return when {
                c == "{" -> group()
                c == "~" -> Space(0.33f)
                c.length > 1 -> Atom(c) // outside the BMP: a maths letter already
                ch.isLetter() -> Atom(c, italic = ch in 'a'..'z' || ch in 'A'..'Z')
                ch.isDigit() || ch == '.' -> Atom(c)
                else -> {
                    val (text, kind) = CHARS[ch] ?: (c to Kind.ORD)
                    Atom(text, kind)
                }
            }
        }

        private fun restyle(node: Node, style: String): Node = when (node) {
            is Atom -> {
                if (style == "rm") {
                    node.copy(italic = false)
                } else if (style == "bf") {
                    node.copy(bold = true, italic = false)
                } else if (style == "bsym") {
                    node.copy(bold = true)
                } else if (style == "it") {
                    node.copy(italic = true)
                } else {
                    node.copy(text = alphabet(node.text, style), italic = false)
                }
            }
            is Row -> Row(node.items.map { restyle(it, style) })
            is Scripts -> node.copy(base = restyle(node.base, style))
            is Accent -> node.copy(body = restyle(node.body, style))
            is Frac -> node.copy(num = restyle(node.num, style), den = restyle(node.den, style))
            is Fenced -> node.copy(body = restyle(node.body, style))
            is Root -> node.copy(body = restyle(node.body, style))
            is Grid, is Space -> node
        }
    }

    /** [text] in a maths alphabet, keeping the letters that have their own BMP code point. */
    private fun alphabet(text: String, style: String): String {
        val (upper, lower, exceptions) = ALPHABETS[style] ?: return text
        val out = StringBuilder(text.length * 2)
        for (c in text) {
            val exception = exceptions[c]
            when {
                exception != null -> out.append(exception)
                c in 'A'..'Z' -> out.appendCodePoint(upper + (c - 'A'))
                c in 'a'..'z' && lower >= 0 -> out.appendCodePoint(lower + (c - 'a'))
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun row(items: List<Node>): Node {
        // Neighbouring runs of the same kind and face are one run: fewer pieces
        // to measure, and a number stays one number. Each run is built once,
        // not copied a letter at a time, so a long one stays linear.
        val merged = ArrayList<Node>()
        var run: Atom? = null
        val text = StringBuilder()
        fun close() {
            val r = run ?: return
            merged += if (text.length == r.text.length) r else r.copy(text = text.toString())
            run = null
            text.setLength(0)
        }
        for (n in items) {
            if (n is Atom && n.kind == Kind.ORD && !n.large && ' ' !in n.text) {
                val r = run
                if (r != null && r.italic == n.italic && r.bold == n.bold) {
                    text.append(n.text)
                    continue
                }
                close()
                run = n
                text.append(n.text)
                continue
            }
            close()
            merged += n
        }
        close()
        return when (merged.size) {
            0 -> EMPTY
            1 -> merged[0]
            else -> Row(merged)
        }
    }

    val EMPTY: Node = Row(emptyList())

    /**
     * Groups and arguments nest no deeper than this. A real formula never
     * comes close; ten thousand `{` or `\sqrt\sqrt…` would otherwise overflow
     * the stack, here or in whatever draws the tree.
     */
    private const val MAX_DEPTH = 40

    private val TOP_END = emptySet<String>()
    private val CELL_END = setOf("&", "\\\\", "\\cr")

    // ------------------------------------------------------------- one line

    /**
     * [latex] for a formula inside a sentence, as one of two things.
     *
     * A formula that reads on one line (`x^2`, `\alpha_i`, `\sqrt{2}`) is text
     * with spans: superscripts and subscripts as [AiMarkdownBlocks.Style.SUPERSCRIPT]
     * and `SUBSCRIPT`, variables italic, the whole run [AiMarkdownBlocks.Style.MATH].
     * That is the cheap path, and it wraps and selects like the words around it.
     *
     * One that needs two dimensions ([needsBox]: a fraction, a root over a sum,
     * a matrix) comes back as its [plain] text under one
     * [AiMarkdownBlocks.Style.FORMULA] span whose `url` is the source. A reader
     * that can draw the formula puts it in that span's place; one that cannot
     * still has the plain text, which is also what a copy of it takes.
     */
    fun inline(latex: String): AiMarkdownBlocks.Inline {
        val node = parse(latex)
        if (needsBox(node)) {
            val w = Writer(spans = false)
            w.node(node, top = true)
            val text = w.out.toString().trim()
            if (text.isEmpty()) return AiMarkdownBlocks.Inline("")
            return AiMarkdownBlocks.Inline(
                text,
                listOf(
                    AiMarkdownBlocks.Span(0, text.length, AiMarkdownBlocks.Style.FORMULA, latex),
                    AiMarkdownBlocks.Span(0, text.length, AiMarkdownBlocks.Style.MATH),
                ),
            )
        }
        val w = Writer(spans = true)
        w.node(node, top = true)
        val text = w.out.toString().trim()
        val shift = w.out.indexOf(text).coerceAtLeast(0)
        val spans = w.spansOut.mapNotNull {
            val s = (it.start - shift).coerceIn(0, text.length)
            val e = (it.end - shift).coerceIn(0, text.length)
            if (e > s) it.copy(start = s, end = e) else null
        }
        if (text.isEmpty()) return AiMarkdownBlocks.Inline("")
        return AiMarkdownBlocks.Inline(text, spans + AiMarkdownBlocks.Span(0, text.length, AiMarkdownBlocks.Style.MATH))
    }

    /**
     * True when [node] cannot be written on one line without losing its shape:
     * a fraction other than `½` and its kin, a root over more than one symbol,
     * an accent over more than one letter, limits over and under, a matrix.
     */
    fun needsBox(node: Node): Boolean = when (node) {
        is Atom, is Space -> false
        is Row -> node.items.any(::needsBox)
        is Frac -> vulgar(node) == null
        is Root -> node.body !is Atom || needsBox(node.body) || node.index?.let { flatIndex(it) !in setOf("", "2", "3", "4") } == true
        is Scripts -> node.limits == true || needsBox(node.base) ||
            node.sup?.let(::needsBox) == true || node.sub?.let(::needsBox) == true
        is Accent -> node.body !is Atom || node.body.text.codePointCount(0, node.body.text.length) != 1
        is Fenced -> (node.body is Frac && !node.body.rule) || needsBox(node.body)
        is Grid -> true
    }

    private fun flatIndex(n: Node): String = (n as? Atom)?.text ?: if (n == EMPTY) "" else "?"

    /** `½` for `\frac12`, null for any fraction with no glyph of its own. */
    private fun vulgar(f: Frac): String? {
        if (!f.rule) return null
        val num = (f.num as? Atom)?.text ?: return null
        val den = (f.den as? Atom)?.text ?: return null
        return VULGAR["$num/$den"]
    }

    /**
     * [latex] as plain text, for a field that draws no spans: `x²`, `x_(ij)`,
     * `√(x+1)`, `(a+b)/c`. Rows of a matrix or an `aligned` block go on lines
     * of their own when [lines] is set.
     */
    fun plain(latex: String, lines: Boolean = false): String {
        val w = Writer(spans = false, lines = lines)
        w.node(parse(latex), top = true)
        return w.out.toString().trim()
    }

    private class Writer(
        val spans: Boolean,
        val lines: Boolean = false,
        /**
         * Each node's flat text, by identity. Shared with every nested writer:
         * a fraction looks at its parts' text before writing them, and without
         * this a formula nested d deep would be written out 2^d times.
         */
        private val flats: java.util.IdentityHashMap<Node, String> = java.util.IdentityHashMap(),
    ) {
        val out = StringBuilder()
        val spansOut = ArrayList<AiMarkdownBlocks.Span>()
        private var prev: Kind? = null

        /** The last thing written was a function name: `\sin x` needs its space back. */
        private var afterName = false

        fun node(n: Node, top: Boolean = false) {
            // A name's argument keeps its space (`sin ½`, `sin x²`), and a name
            // carrying a power keeps the space before it (`2 sin² x`); a bracket
            // straight after a name does not (`sin(x)`).
            val gap = when (n) {
                is Atom, is Space, is Row, is Fenced -> false
                is Scripts -> afterName || prev == Kind.OP || (n.base.isName() && (prev == Kind.ORD || prev == Kind.CLOSE))
                else -> afterName || prev == Kind.OP
            }
            if (gap && out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
            when (n) {
                is Atom -> atom(n, top)
                is Row -> n.items.forEach { node(it, top) }
                is Space -> {
                    // `\,` between `x` and `dx` is worth a space; `\!` takes nothing away.
                    if (n.em >= 0.15f && out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
                }
                is Frac -> frac(n)
                is Root -> root(n)
                is Scripts -> scripts(n)
                is Accent -> accent(n)
                is Fenced -> {
                    if (n.body is Frac && !n.body.rule) {
                        out.append("C(")
                        sub { node(n.body.num) }
                        out.append(", ")
                        sub { node(n.body.den) }
                        out.append(')')
                    } else {
                        out.append(n.left)
                        sub { node(n.body, top) }
                        out.append(n.right)
                    }
                    prev = Kind.CLOSE
                    afterName = false
                }
                is Grid -> grid(n)
            }
        }

        /** [block] written as a nested formula: spacing starts afresh inside. */
        private inline fun sub(block: () -> Unit) {
            val saved = prev
            val savedName = afterName
            prev = null
            afterName = false
            block()
            prev = saved
            afterName = savedName
        }

        private fun atom(a: Atom, top: Boolean) {
            var kind = a.kind
            // A minus with nothing to subtract from is a sign, not an operator.
            if (kind == Kind.BIN && (prev == null || prev == Kind.BIN || prev == Kind.REL || prev == Kind.OPEN || prev == Kind.PUNCT || prev == Kind.OP)) {
                kind = Kind.ORD
            }
            val spaced = top && (kind == Kind.BIN || kind == Kind.REL)
            // Not at the start of a line either: an `aligned` row may open with its `=`.
            if (spaced && out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) out.append(' ')
            val name = kind == Kind.OP && !a.large
            // A name or a big operator keeps a space before what it acts on:
            // `sin x`, `∑ᵢ i`; a bracket straight after one does not.
            val gap = (name && (prev == Kind.ORD || prev == Kind.CLOSE)) || ((afterName || prev == Kind.OP) && (kind == Kind.ORD || name))
            if (gap && out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
            val start = out.length
            out.append(a.text)
            if (spans) {
                if (a.italic) spansOut += AiMarkdownBlocks.Span(start, out.length, AiMarkdownBlocks.Style.ITALIC)
                if (a.bold) spansOut += AiMarkdownBlocks.Span(start, out.length, AiMarkdownBlocks.Style.BOLD)
            }
            if (spaced || (top && kind == Kind.PUNCT && a.text != ".")) out.append(' ')
            prev = kind
            afterName = name
        }

        private fun frac(f: Frac) {
            val num = flat(f.num)
            val den = flat(f.den)
            VULGAR["$num/$den"]?.let {
                out.append(it)
                prev = Kind.ORD
                afterName = false
                return
            }
            wrapped(f.num, num)
            out.append(if (f.rule) "/" else " ")
            wrapped(f.den, den)
            prev = Kind.ORD
            afterName = false
        }

        /** [n] alone, in brackets when it is more than one term. */
        private fun wrapped(n: Node, flat: String) {
            val simple = simple(n) || (flat.length <= 1)
            if (!simple) out.append('(')
            sub { node(n) }
            if (!simple) out.append(')')
        }

        private fun root(r: Root) {
            val index = r.index?.let { flat(it) }
            when (index) {
                null, "", "2" -> out.append('√')
                "3" -> out.append('∛')
                "4" -> out.append('∜')
                else -> {
                    script(r.index ?: EMPTY, sup = true)
                    out.append('√')
                }
            }
            val simple = simple(r.body)
            if (!simple) out.append('(')
            sub { node(r.body) }
            if (!simple) out.append(')')
            prev = Kind.ORD
            afterName = false
        }

        private fun scripts(s: Scripts) {
            val base = s.base
            sub { node(base) }
            s.sub?.let { script(it, sup = false) }
            s.sup?.let { script(it, sup = true) }
            prev = (base as? Atom)?.kind?.takeIf { it == Kind.OP || it == Kind.REL } ?: Kind.ORD
            // `\log_2 n` and `\sin^2 x` are still a name waiting for its argument.
            afterName = base is Atom && base.kind == Kind.OP && !base.large
        }

        private fun script(n: Node, sup: Boolean) {
            val flat = flat(n)
            if (spans) {
                val start = out.length
                sub { node(n) }
                if (out.length > start) {
                    spansOut += AiMarkdownBlocks.Span(start, out.length, if (sup) AiMarkdownBlocks.Style.SUPERSCRIPT else AiMarkdownBlocks.Style.SUBSCRIPT)
                }
                return
            }
            val map = if (sup) SUP else SUB
            if (flat.isNotEmpty() && flat.all { it in map }) {
                flat.forEach { out.append(map.getValue(it)) }
                return
            }
            out.append(if (sup) '^' else '_')
            if (flat.length == 1) out.append(flat) else out.append('(').append(flat).append(')')
        }

        private fun accent(a: Accent) {
            val start = out.length
            sub { node(a.body) }
            val mark = COMBINING.getValue(a.mark)
            if (a.mark == Mark.OVERLINE || a.mark == Mark.UNDERLINE) {
                // Over every character, as a line over the whole run would be.
                val run = out.substring(start)
                out.setLength(start)
                for (c in run) out.append(c).append(mark)
            } else {
                out.append(mark)
            }
            prev = Kind.ORD
            afterName = false
        }

        private fun grid(g: Grid) {
            out.append(g.left)
            g.rows.forEachIndexed { r, row ->
                if (r > 0) out.append(if (lines && g.left.isEmpty() && g.right.isEmpty()) "\n" else "; ")
                row.forEachIndexed { c, cell ->
                    // `aligned` cells meet at their `=`; the others are a list.
                    if (c > 0) out.append(if (g.paired) "" else ", ")
                    sub { node(cell, top = true) }
                    while (out.endsWith(" ")) out.setLength(out.length - 1)
                }
            }
            out.append(g.right)
            prev = Kind.CLOSE
            afterName = false
        }

        private fun Node.isName() = this is Atom && kind == Kind.OP && !large

        /** [n] written out with no spans, to look at. */
        private fun flat(n: Node): String = flats.getOrPut(n) {
            val w = Writer(spans = false, flats = flats)
            w.node(n)
            w.out.toString()
        }

        private fun simple(n: Node): Boolean = when (n) {
            is Atom -> n.kind == Kind.ORD
            is Scripts -> simple(n.base)
            is Root, is Fenced, is Accent, is Grid -> true
            is Row -> n.items.isEmpty()
            else -> false
        }
    }

    // ------------------------------------------------------------------ tables

    private val GREEK: Map<String, String> = buildMap {
        val names = listOf(
            "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa",
            "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon", "phi",
            "chi", "psi", "omega",
        )
        val first = 0x3B1
        names.forEachIndexed { k, name ->
            // Final sigma sits between rho and sigma, so everything from sigma on is one further.
            val cp = first + k + if (k >= 17) 1 else 0
            put(name, String(Character.toChars(cp)))
            put(name.replaceFirstChar { it.uppercaseChar() }, String(Character.toChars(cp - 0x20)))
        }
        put("epsilon", "ϵ")
        put("phi", "ϕ")
        put("varepsilon", "ε")
        put("vartheta", "ϑ")
        put("varpi", "ϖ")
        put("varrho", "ϱ")
        put("varsigma", "ς")
        put("varphi", "φ")
        put("varkappa", "ϰ")
        put("digamma", "ϝ")
    }

    private val SYMBOLS: Map<String, Pair<String, Kind>> = buildMap {
        fun k(kind: Kind, vararg pairs: Pair<String, String>) {
            for ((name, text) in pairs) put(name, text to kind)
        }
        k(
            Kind.ORD,
            "infty" to "∞", "hbar" to "ℏ", "ell" to "ℓ", "aleph" to "ℵ", "beth" to "ℶ", "nabla" to "∇",
            "partial" to "∂", "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃",
            "nexists" to "∄", "neg" to "¬", "lnot" to "¬", "top" to "⊤", "bot" to "⊥", "angle" to "∠",
            "triangle" to "△", "square" to "□", "Box" to "□", "blacksquare" to "■", "diamond" to "◇",
            "Re" to "ℜ", "Im" to "ℑ", "wp" to "℘", "prime" to "′", "degree" to "°", "circ" to "∘",
            "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
            "dag" to "†", "ddag" to "‡", "S" to "§", "P" to "¶", "checkmark" to "✓", "qed" to "∎",
            "imath" to "ı", "jmath" to "ȷ", "Finv" to "Ⅎ", "mho" to "℧", "eth" to "ð",
            "infin" to "∞", "clubsuit" to "♣", "diamondsuit" to "♢", "heartsuit" to "♡", "spadesuit" to "♠",
            "flat" to "♭", "natural" to "♮", "sharp" to "♯", "backslash" to "\\", "vert" to "|", "Vert" to "‖",
            "|" to "‖", "lbrace" to "{", "rbrace" to "}", "{" to "{", "}" to "}",
            "%" to "%", "$" to "$", "#" to "#", "&" to "&", "_" to "_",
        )
        k(Kind.OPEN, "langle" to "⟨", "lfloor" to "⌊", "lceil" to "⌈", "lvert" to "|", "lVert" to "‖")
        k(Kind.CLOSE, "rangle" to "⟩", "rfloor" to "⌋", "rceil" to "⌉", "rvert" to "|", "rVert" to "‖")
        k(
            Kind.BIN,
            "times" to "×", "cdot" to "⋅", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗",
            "star" to "⋆", "bullet" to "•", "cup" to "∪", "cap" to "∩", "setminus" to "∖", "oplus" to "⊕",
            "ominus" to "⊖", "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙", "wedge" to "∧", "land" to "∧",
            "vee" to "∨", "lor" to "∨", "sqcup" to "⊔", "sqcap" to "⊓", "uplus" to "⊎", "amalg" to "⨿",
            "dagger" to "†", "ddagger" to "‡", "wr" to "≀", "bigtriangleup" to "△", "bigtriangledown" to "▽",
            "triangleleft" to "◁", "triangleright" to "▷", "lhd" to "⊲", "rhd" to "⊳", "cdotp" to "⋅",
        )
        k(
            Kind.REL,
            "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
            "leqslant" to "⩽", "geqslant" to "⩾", "ll" to "≪", "gg" to "≫", "lll" to "⋘", "ggg" to "⋙",
            "approx" to "≈", "equiv" to "≡", "cong" to "≅", "sim" to "∼", "simeq" to "≃", "propto" to "∝",
            "asymp" to "≍", "doteq" to "≐", "prec" to "≺", "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰",
            "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆",
            "supseteq" to "⊇", "subsetneq" to "⊊", "supsetneq" to "⊋", "sqsubseteq" to "⊑", "sqsupseteq" to "⊒",
            "perp" to "⊥", "parallel" to "∥", "nparallel" to "∦", "mid" to "∣", "nmid" to "∤", "vdash" to "⊢",
            "dashv" to "⊣", "models" to "⊨", "vDash" to "⊨", "smile" to "⌣", "frown" to "⌢", "bowtie" to "⋈",
            "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔",
            "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⟹",
            "impliedby" to "⟸", "iff" to "⟺", "longrightarrow" to "⟶", "longleftarrow" to "⟵",
            "longleftrightarrow" to "⟷", "Longrightarrow" to "⟹", "Longleftarrow" to "⟸",
            "Longleftrightarrow" to "⟺", "mapsto" to "↦", "longmapsto" to "⟼", "hookrightarrow" to "↪",
            "hookleftarrow" to "↩", "uparrow" to "↑", "downarrow" to "↓", "updownarrow" to "↕",
            "Uparrow" to "⇑", "Downarrow" to "⇓", "nearrow" to "↗", "searrow" to "↘", "swarrow" to "↙",
            "nwarrow" to "↖", "rightleftharpoons" to "⇌", "leftrightarrows" to "⇆", "rightharpoonup" to "⇀",
            "leftharpoonup" to "↼", "nleq" to "≰", "ngeq" to "≱", "nless" to "≮", "ngtr" to "≯",
            "nsim" to "≁", "ncong" to "≇", "nsubseteq" to "⊈", "nsupseteq" to "⊉", "lesssim" to "≲",
            "gtrsim" to "≳", "approxeq" to "≊", "triangleq" to "≜", "coloneqq" to "≔", "eqqcolon" to "≕",
            "therefore" to "∴", "because" to "∵", "colon" to ":", "owns" to "∋", "leadsto" to "⇝",
        )
        k(Kind.PUNCT, "ldotp" to ".")
    }

    /** `\not` in front of these has a glyph of its own. */
    private val NEGATED = mapOf(
        "=" to "≠", "∈" to "∉", "⊂" to "⊄", "⊃" to "⊅", "⊆" to "⊈", "⊇" to "⊉", "≡" to "≢",
        "<" to "≮", ">" to "≯", "≤" to "≰", "≥" to "≱", "∼" to "≁", "≅" to "≇", "≈" to "≉", "∃" to "∄",
        "∣" to "∤", "∥" to "∦",
    )

    /** Big operators, and whether their scripts go over and under in display style. */
    private val LARGE: Map<String, Pair<String, Boolean>> = mapOf(
        "sum" to ("∑" to true), "prod" to ("∏" to true), "coprod" to ("∐" to true),
        "bigcup" to ("⋃" to true), "bigcap" to ("⋂" to true), "bigoplus" to ("⨁" to true),
        "bigotimes" to ("⨂" to true), "bigodot" to ("⨀" to true), "bigvee" to ("⋁" to true),
        "bigwedge" to ("⋀" to true), "bigsqcup" to ("⨆" to true), "biguplus" to ("⨄" to true),
        "int" to ("∫" to false), "iint" to ("∬" to false), "iiint" to ("∭" to false),
        "oint" to ("∮" to false), "oiint" to ("∯" to false), "intop" to ("∫" to true), "smallint" to ("∫" to false),
    )

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "sec", "csc", "cot", "arcsin", "arccos", "arctan", "arcsec", "arccsc",
        "arccot", "sinh", "cosh", "tanh", "coth", "sech", "csch", "ln", "log", "lg", "exp", "lim",
        "limsup", "liminf", "max", "min", "sup", "inf", "det", "dim", "ker", "gcd", "lcm", "arg",
        "deg", "hom", "Pr", "sgn", "tr", "rank", "argmax", "argmin", "injlim", "projlim", "Tr", "id",
    )

    private val LIMIT_FUNCTIONS = setOf(
        "lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "gcd", "Pr", "argmax", "argmin",
        "injlim", "projlim",
    )

    private val SPACES = mapOf(
        "," to 3 / 18f, "thinspace" to 3 / 18f, ":" to 4 / 18f, ">" to 4 / 18f, "medspace" to 4 / 18f,
        ";" to 5 / 18f, "thickspace" to 5 / 18f, "!" to -3 / 18f, "negthinspace" to -3 / 18f,
        " " to 0.33f, "quad" to 1f, "qquad" to 2f, "enspace" to 0.5f,
    )

    private val ACCENTS = mapOf(
        "hat" to Mark.HAT, "widehat" to Mark.HAT, "bar" to Mark.BAR, "vec" to Mark.VEC,
        "overrightarrow" to Mark.VEC, "tilde" to Mark.TILDE, "widetilde" to Mark.TILDE, "dot" to Mark.DOT,
        "ddot" to Mark.DDOT, "check" to Mark.CHECK, "breve" to Mark.BREVE, "acute" to Mark.ACUTE,
        "grave" to Mark.GRAVE, "overline" to Mark.OVERLINE, "underline" to Mark.UNDERLINE,
    )

    private val COMBINING = mapOf(
        Mark.HAT to '\u0302', Mark.BAR to '\u0304', Mark.VEC to '\u20D7', Mark.TILDE to '\u0303',
        Mark.DOT to '\u0307', Mark.DDOT to '\u0308', Mark.CHECK to '\u030C', Mark.BREVE to '\u0306',
        Mark.ACUTE to '\u0301', Mark.GRAVE to '\u0300', Mark.OVERLINE to '\u0305', Mark.UNDERLINE to '\u0332',
    )

    private val STYLES = mapOf(
        "mathrm" to "rm", "rm" to "rm", "mathup" to "rm", "mathbf" to "bf", "bf" to "bf",
        "boldsymbol" to "bsym", "bm" to "bsym", "pmb" to "bsym", "mathit" to "it",
        "mathbb" to "bb", "Bbb" to "bb", "mathcal" to "cal", "mathscr" to "cal", "mathfrak" to "frak",
        "mathsf" to "sf", "mathtt" to "tt",
    )

    /** First code point of the capitals, of the small letters (-1: none), and the BMP exceptions. */
    private val ALPHABETS: Map<String, Triple<Int, Int, Map<Char, Char>>> = mapOf(
        "bb" to Triple(
            0x1D538, 0x1D552,
            mapOf('C' to 'ℂ', 'H' to 'ℍ', 'N' to 'ℕ', 'P' to 'ℙ', 'Q' to 'ℚ', 'R' to 'ℝ', 'Z' to 'ℤ'),
        ),
        "cal" to Triple(
            0x1D49C, 0x1D4B6,
            mapOf(
                'B' to 'ℬ', 'E' to 'ℰ', 'F' to 'ℱ', 'H' to 'ℋ', 'I' to 'ℐ', 'L' to 'ℒ', 'M' to 'ℳ',
                'R' to 'ℛ', 'e' to 'ℯ', 'g' to 'ℊ', 'o' to 'ℴ',
            ),
        ),
        "frak" to Triple(0x1D504, 0x1D51E, mapOf('C' to 'ℭ', 'H' to 'ℌ', 'I' to 'ℑ', 'R' to 'ℜ', 'Z' to 'ℨ')),
        "sf" to Triple(0x1D5A0, 0x1D5BA, emptyMap()),
        "tt" to Triple(0x1D670, 0x1D68A, emptyMap()),
    )

    /** Plain characters and what they are in maths: `-` is a real minus sign. */
    private val CHARS: Map<Char, Pair<String, Kind>> = mapOf(
        '+' to ("+" to Kind.BIN), '-' to ("−" to Kind.BIN), '*' to ("∗" to Kind.BIN),
        '=' to ("=" to Kind.REL), '<' to ("<" to Kind.REL), '>' to (">" to Kind.REL), ':' to (":" to Kind.REL),
        ',' to ("," to Kind.PUNCT), ';' to (";" to Kind.PUNCT),
        '(' to ("(" to Kind.OPEN), '[' to ("[" to Kind.OPEN),
        ')' to (")" to Kind.CLOSE), ']' to ("]" to Kind.CLOSE), '!' to ("!" to Kind.CLOSE), '?' to ("?" to Kind.CLOSE),
        '/' to ("/" to Kind.ORD), '|' to ("|" to Kind.ORD),
        '×' to ("×" to Kind.BIN), '÷' to ("÷" to Kind.BIN), '±' to ("±" to Kind.BIN), '·' to ("⋅" to Kind.BIN),
        '≤' to ("≤" to Kind.REL), '≥' to ("≥" to Kind.REL), '≠' to ("≠" to Kind.REL), '≈' to ("≈" to Kind.REL),
        '→' to ("→" to Kind.REL), '∈' to ("∈" to Kind.REL),
    )

    private val VULGAR = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾", "1/5" to "⅕", "2/5" to "⅖",
        "3/5" to "⅗", "4/5" to "⅘", "1/6" to "⅙", "5/6" to "⅚", "1/7" to "⅐", "1/8" to "⅛", "3/8" to "⅜",
        "5/8" to "⅝", "7/8" to "⅞", "1/9" to "⅑", "1/10" to "⅒",
    )

    private val SUP: Map<Char, Char> = pairs(
        "0123456789+−-=()abcdefghijklmnoprstuvwxyzABDEGHIJKLMNOPRTUVWβγδεθιφχ′",
        "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁻⁼⁽⁾ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵝᵞᵟᵋᶿᶥᵠᵡ′",
    )

    private val SUB: Map<Char, Char> = pairs(
        "0123456789+−-=()aehijklmnoprstuvxβγρφχ",
        "₀₁₂₃₄₅₆₇₈₉₊₋₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓᵦᵧᵨᵩᵪ",
    )

    private fun pairs(from: String, to: String): Map<Char, Char> {
        require(from.length == to.length)
        return from.indices.associate { from[it] to to[it] }
    }
}
