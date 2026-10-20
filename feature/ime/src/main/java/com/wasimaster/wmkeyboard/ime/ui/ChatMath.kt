package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.wasimaster.wmkeyboard.core.tools.LatexMath
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Kind
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Mark
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Node
import kotlin.math.max
import kotlin.math.min

/**
 * LaTeX drawn in two dimensions (#811): fractions stacked, roots with their
 * bar, limits over and under a sum, matrices in brackets that grow with them.
 *
 * Built in-house rather than on a library: the TeX renderers for Android are
 * GPL, which this MIT project cannot ship, and a WebView with KaTeX is far too
 * heavy for a keyboard. The rules are TeX's, simplified: four sizes (display,
 * text, script, scriptscript), its atom spacing, and its fraction and script
 * shifts, in ems of the size in force.
 *
 * A formula is laid out once into a tree of [MathBox]es and drawn on a canvas.
 * Glyphs are measured by Compose's own [TextMeasurer], so they fall back
 * through the system fonts like any other text; only the vertical extents of a
 * glyph's ink are estimated ([inkAscent]), because Compose reports a line's
 * height and not a glyph's, and a fraction laid out on line heights stands a
 * full line too tall.
 */
internal class MathTypesetter(
    private val measurer: TextMeasurer,
    private val density: Density,
    /** The body text's size, in px. */
    private val em: Float,
) {
    private val cache = object : LinkedHashMap<String, MathBox>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MathBox>?) = size > CACHE_SIZE
    }

    /**
     * [latex] laid out, display style when [display], else text style as it
     * would sit in a sentence. Remembered: an answer recomposes on every
     * streamed token, and a formula that has not changed is not laid out again.
     */
    fun layout(latex: String, display: Boolean): MathBox {
        val key = (if (display) "D" else "T") + latex
        cache[key]?.let { return it }
        val laid = runCatching { node(LatexMath.parse(latex), if (display) DISPLAY else TEXT) }
            .getOrElse { glyphs(latex, TEXT, italic = false, bold = false, mono = true) }
        // `\!` takes width away; a formula of nothing else must not come back
        // narrower than nothing, which no canvas or placeholder can be sized to.
        val box = if (laid.width >= 0f) laid else MathBox(0f, laid.ascent, laid.descent, listOf(MathPart(laid, 0f, 0f)))
        cache[key] = box
        return box
    }

    private fun size(level: Int) = em * SIZE[level]

    private fun node(n: Node, level: Int, leadingOrd: Boolean = false): MathBox = when (n) {
        is LatexMath.Atom -> atom(n, level)
        is LatexMath.Row -> row(n.items, level, leadingOrd)
        is LatexMath.Space -> MathBox(n.em * size(level), 0f, 0f)
        is LatexMath.Frac -> frac(n, level)
        is LatexMath.Root -> root(n, level)
        is LatexMath.Scripts -> scripts(n, level)
        is LatexMath.Accent -> accent(n, level)
        is LatexMath.Fenced -> fenced(n.left, node(n.body, level), n.right, level)
        is LatexMath.Grid -> grid(n, level)
    }

    // ------------------------------------------------------------------ glyphs

    private fun atom(a: LatexMath.Atom, level: Int): MathBox {
        if (!a.large) return glyphs(a.text, level, a.italic, a.bold)
        val integral = a.text.any { it in INTEGRALS }
        val scale = when {
            level == DISPLAY -> if (integral) 1.75f else 1.45f
            level == TEXT -> if (integral) 1.2f else 1.1f
            else -> 1f
        }
        val s = size(level) * scale
        val layout = measure(a.text, s, italic = false, bold = false, mono = false)
        // Big operators sit centred on the maths axis, whatever their size.
        val inkUp = s * (if (integral) 0.88f else 0.76f)
        val inkDown = s * (if (integral) 0.30f else 0.22f)
        val axis = axis(level)
        val half = (inkUp + inkDown) / 2
        val lift = axis - (inkUp - inkDown) / 2
        return MathBox(
            layout.size.width.toFloat(),
            axis + half,
            half - axis,
            italic = integral,
        ) { x, y, color -> drawText(layout, color, Offset(x, y - lift - layout.firstBaseline)) }
    }

    private fun glyphs(text: String, level: Int, italic: Boolean, bold: Boolean, mono: Boolean = false): MathBox {
        if (text.isEmpty()) return MathBox(0f, 0f, 0f)
        val s = size(level)
        val layout = measure(text, s, italic, bold, mono)
        var up = 0f
        var down = 0f
        for (c in text) {
            up = max(up, inkAscent(c))
            down = max(down, inkDescent(c))
        }
        return MathBox(layout.size.width.toFloat(), up * s, down * s, italic = italic) { x, y, color ->
            drawText(layout, color, Offset(x, y - layout.firstBaseline))
        }
    }

    private fun measure(text: String, px: Float, italic: Boolean, bold: Boolean, mono: Boolean): TextLayoutResult =
        measurer.measure(
            AnnotatedString(text),
            TextStyle(
                fontSize = with(density) { px.toSp() },
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Serif,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
            softWrap = false,
            maxLines = 1,
            density = density,
        )

    // --------------------------------------------------------------------- rows

    private fun kindOf(n: Node): Kind? = when (n) {
        is LatexMath.Atom -> n.kind
        is LatexMath.Space -> null
        is LatexMath.Scripts -> (n.base as? LatexMath.Atom)?.kind?.takeIf { it == Kind.OP || it == Kind.REL } ?: Kind.ORD
        is LatexMath.Row -> if (n.items.isEmpty()) null else Kind.ORD
        else -> Kind.ORD
    }

    private fun row(items: List<Node>, level: Int, leadingOrd: Boolean): MathBox {
        if (items.isEmpty()) return MathBox(0f, 0f, 0f)
        val kinds = items.map(::kindOf).toMutableList()
        // TeX's rule: a binary operator with nothing to its left, or with
        // another operator there, is a sign (`-x`, `= -1`).
        var prev: Kind? = if (leadingOrd) Kind.ORD else null
        for (k in kinds.indices) {
            val kind = kinds[k] ?: continue
            if (kind == Kind.BIN && (prev == null || prev in NOT_BEFORE_BIN)) kinds[k] = Kind.ORD
            prev = kinds[k]
        }
        for (k in kinds.indices.reversed()) {
            if (kinds[k] != Kind.BIN) continue
            val after = (k + 1 until kinds.size).firstNotNullOfOrNull { kinds[it] }
            if (after == null || after == Kind.REL || after == Kind.CLOSE || after == Kind.PUNCT) kinds[k] = Kind.ORD
        }
        val parts = ArrayList<MathPart>(items.size)
        var x = 0f
        var up = 0f
        var down = 0f
        prev = if (leadingOrd) Kind.ORD else null
        val unit = size(level) / 18f
        for ((k, item) in items.withIndex()) {
            val kind = kinds[k]
            if (kind != null) {
                x += spacing(prev, kind, level) * unit
                prev = kind
            }
            val box = node(item, level)
            parts += MathPart(box, x, 0f)
            x += box.width
            up = max(up, box.ascent)
            down = max(down, box.descent)
        }
        return MathBox(x, up, down, parts, italic = parts.last().box.italic)
    }

    /** TeX's inter-atom spacing, in mu (an eighteenth of an em). */
    private fun spacing(a: Kind?, b: Kind, level: Int): Int {
        if (a == null) return 0
        val tight = level >= SCRIPT
        return when {
            a == Kind.OPEN || b == Kind.CLOSE || b == Kind.PUNCT -> 0
            a == Kind.REL && b == Kind.REL -> 0
            a == Kind.BIN || b == Kind.BIN -> if (tight) 0 else 4
            a == Kind.REL || b == Kind.REL -> if (tight) 0 else 5
            a == Kind.PUNCT -> if (tight) 0 else 3
            a == Kind.OP && b == Kind.OPEN -> 0
            a == Kind.OP || b == Kind.OP -> 3
            else -> 0
        }
    }

    // ---------------------------------------------------------------- fractions

    private fun frac(f: LatexMath.Frac, outer: Int): MathBox {
        // `\dfrac` sets itself in display style wherever it is; `\tfrac` in text style.
        val level = when (f.display) {
            true -> DISPLAY
            false -> max(outer, TEXT)
            null -> outer
        }
        val inner = FRACTION_LEVEL[level]
        val num = node(f.num, inner)
        val den = node(f.den, inner)
        val s = size(level)
        val rule = rule(level)
        val axis = axis(level)
        val gap = if (level == DISPLAY) rule * 3 else rule * 1.5f
        val display = level == DISPLAY
        val numShift = max(s * (if (display) 0.677f else 0.394f), axis + rule / 2 + gap + num.descent)
        val denShift = max(s * (if (display) 0.686f else 0.345f), den.ascent + gap + rule / 2 - axis)
        val pad = s * 0.1f
        val width = max(num.width, den.width) + 2 * pad
        val parts = listOf(
            MathPart(num, (width - num.width) / 2, -numShift),
            MathPart(den, (width - den.width) / 2, denShift),
        )
        return MathBox(width, numShift + num.ascent, denShift + den.descent, parts) { x, y, color ->
            if (f.rule) drawLine(color, Offset(x + pad * 0.5f, y - axis), Offset(x + width - pad * 0.5f, y - axis), rule)
        }
    }

    // -------------------------------------------------------------------- roots

    private fun root(r: LatexMath.Root, level: Int): MathBox {
        val body = node(r.body, level)
        val s = size(level)
        val rule = rule(level)
        val gap = rule + s * (if (level == DISPLAY) 0.15f else 0.08f)
        val bodyUp = max(body.ascent, s * 0.46f)
        val bodyDown = max(body.descent, s * 0.02f)
        val top = bodyUp + gap + rule
        val height = top + bodyDown + rule
        val surd = s * 0.42f + height * 0.12f
        val index = r.index?.let { node(it, SCRIPTSCRIPT) }?.takeIf { it.width > 0f }
        // A root's index tucks into the crook of the sign.
        val lead = index?.let { max(0f, it.width - surd * 0.45f) } ?: 0f
        val width = lead + surd + body.width + s * 0.08f
        val indexShift = height * 0.55f - bodyDown
        val parts = buildList {
            add(MathPart(body, lead + surd, 0f))
            if (index != null) add(MathPart(index, lead + surd * 0.55f - index.width, -indexShift))
        }
        val ascent = max(top + rule / 2, index?.let { indexShift + it.ascent } ?: 0f)
        return MathBox(width, ascent, bodyDown + rule, parts) { x, y, color ->
            val left = x + lead
            val yTop = y - top
            val yBottom = y + bodyDown
            val path = Path().apply {
                moveTo(left, yBottom - height * 0.42f)
                lineTo(left + surd * 0.22f, yBottom - height * 0.5f)
                lineTo(left + surd * 0.55f, yBottom)
                lineTo(left + surd, yTop)
                lineTo(x + width, yTop)
            }
            drawPath(path, color, style = Stroke(rule * 1.1f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
    }

    // ------------------------------------------------------------------ scripts

    private fun scripts(n: LatexMath.Scripts, level: Int): MathBox {
        val base = node(n.base, level)
        val baseAtom = n.base as? LatexMath.Atom
        val limits = n.limits ?: (level == DISPLAY && baseAtom != null && baseAtom.limits)
        val inner = SCRIPT_LEVEL[level]
        // A prime is its own raised glyph: at script size it shrinks to a speck
        // that reads as an accent, so it keeps the base's size and height.
        val primes = (n.sup as? LatexMath.Atom)?.text?.let { t -> t.isNotEmpty() && t.all { it == '′' } } == true
        val sup = n.sup?.let { node(it, if (primes) level else inner) }
        val sub = n.sub?.let { node(it, inner) }
        val s = size(level)
        if (limits) {
            val gap = s * 0.12f
            val width = max(base.width, max(sup?.width ?: 0f, sub?.width ?: 0f))
            val parts = ArrayList<MathPart>(3)
            parts += MathPart(base, (width - base.width) / 2, 0f)
            var up = base.ascent
            var down = base.descent
            if (sup != null) {
                val shift = base.ascent + gap + sup.descent
                parts += MathPart(sup, (width - sup.width) / 2, -shift)
                up = shift + sup.ascent
            }
            if (sub != null) {
                val shift = base.descent + gap + sub.ascent
                parts += MathPart(sub, (width - sub.width) / 2, shift)
                down = shift + sub.descent
            }
            return MathBox(width, up, down, parts)
        }
        val small = size(inner)
        var up = 0f
        var down = 0f
        if (sup != null && primes) {
            up = 0f
        } else if (sup != null) {
            up = max(s * (if (level == DISPLAY) 0.413f else 0.363f), base.ascent - small * 0.32f)
            up = max(up, sup.descent + s * 0.11f)
        }
        if (sub != null) {
            down = max(s * (if (sup != null) 0.247f else 0.15f), base.descent + small * 0.2f)
            down = max(down, sub.ascent - s * 0.36f)
        }
        if (sup != null && sub != null) {
            // Keep the two apart: TeX wants four rule widths between them.
            val clash = rule(level) * 4 - ((up - sup.descent) - (sub.ascent - down))
            if (clash > 0) down += clash
        }
        val kern = if (base.italic) s * 0.06f else 0f
        val parts = ArrayList<MathPart>(3)
        parts += MathPart(base, 0f, 0f)
        if (sup != null) parts += MathPart(sup, base.width + kern, -up)
        if (sub != null) parts += MathPart(sub, base.width - kern * 0.5f, down)
        val width = base.width + max((sup?.width ?: 0f) + kern, sub?.width ?: 0f) + s * 0.04f
        return MathBox(
            width,
            max(base.ascent, sup?.let { up + it.ascent } ?: 0f),
            max(base.descent, sub?.let { down + it.descent } ?: 0f),
            parts,
        )
    }

    // ------------------------------------------------------------------ accents

    private fun accent(a: LatexMath.Accent, level: Int): MathBox {
        val body = node(a.body, level)
        val s = size(level)
        val rule = rule(level)
        val gap = s * 0.08f
        val single = a.body is LatexMath.Atom && (a.body as LatexMath.Atom).text.length == 1
        // An accent over a slanted letter leans with it.
        val skew = if (single && body.italic) s * 0.06f else 0f
        val markHeight = when (a.mark) {
            Mark.OVERLINE, Mark.BAR, Mark.UNDERLINE -> rule
            Mark.VEC -> s * 0.16f
            Mark.DOT, Mark.DDOT -> s * 0.09f
            else -> s * 0.13f
        }
        val bodyUp = max(body.ascent, s * 0.46f)
        val width = max(body.width, if (a.mark == Mark.VEC) s * 0.5f else s * 0.3f)
        val bodyX = (width - body.width) / 2
        val parts = listOf(MathPart(body, bodyX, 0f))
        if (a.mark == Mark.UNDERLINE) {
            val drop = body.descent + gap + rule
            return MathBox(width, body.ascent, drop + rule / 2, parts) { x, y, color ->
                drawLine(color, Offset(x, y + drop), Offset(x + width, y + drop), rule)
            }
        }
        val bottom = bodyUp + gap
        return MathBox(width, bottom + markHeight + rule, body.descent, parts) { x, y, color ->
            val cx = x + width / 2 + skew
            val base = y - bottom
            val top = base - markHeight
            val w = when (a.mark) {
                Mark.OVERLINE -> width
                Mark.BAR -> if (single) s * 0.36f else width
                Mark.VEC -> max(s * 0.42f, if (single) 0f else width)
                Mark.HAT, Mark.CHECK, Mark.TILDE -> if (single) s * 0.36f else min(width, s * 1.4f).coerceAtLeast(s * 0.36f)
                else -> s * 0.3f
            }
            val left = cx - w / 2
            val right = cx + w / 2
            val stroke = Stroke(rule, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (a.mark) {
                Mark.OVERLINE, Mark.BAR -> drawLine(color, Offset(left, base - rule / 2), Offset(right, base - rule / 2), rule)
                Mark.HAT -> drawPath(Path().apply { moveTo(left, base); lineTo(cx, top); lineTo(right, base) }, color, style = stroke)
                Mark.CHECK -> drawPath(Path().apply { moveTo(left, top); lineTo(cx, base); lineTo(right, top) }, color, style = stroke)
                Mark.VEC -> {
                    val mid = (base + top) / 2
                    drawLine(color, Offset(left, mid), Offset(right, mid), rule, StrokeCap.Round)
                    drawPath(
                        Path().apply {
                            moveTo(right - s * 0.12f, top)
                            lineTo(right, mid)
                            lineTo(right - s * 0.12f, base)
                        },
                        color,
                        style = stroke,
                    )
                }
                Mark.TILDE -> drawPath(
                    Path().apply {
                        moveTo(left, base)
                        cubicTo(left + w * 0.3f, top - markHeight * 0.4f, right - w * 0.3f, base + markHeight * 0.4f, right, top)
                    },
                    color,
                    style = stroke,
                )
                Mark.DOT -> drawCircle(color, s * 0.045f, Offset(cx, (base + top) / 2))
                Mark.DDOT -> {
                    drawCircle(color, s * 0.045f, Offset(cx - s * 0.1f, (base + top) / 2))
                    drawCircle(color, s * 0.045f, Offset(cx + s * 0.1f, (base + top) / 2))
                }
                Mark.BREVE -> drawPath(
                    Path().apply {
                        moveTo(left, top)
                        quadraticTo(cx, base + markHeight * 0.6f, right, top)
                    },
                    color,
                    style = stroke,
                )
                Mark.ACUTE -> drawLine(color, Offset(cx - s * 0.06f, base), Offset(cx + s * 0.08f, top), rule * 1.2f, StrokeCap.Round)
                Mark.GRAVE -> drawLine(color, Offset(cx + s * 0.06f, base), Offset(cx - s * 0.08f, top), rule * 1.2f, StrokeCap.Round)
                Mark.UNDERLINE -> Unit
            }
        }
    }

    // --------------------------------------------------------------- delimiters

    /**
     * [body] between [left] and [right], each as tall as the body around the
     * axis. Up to a little over a line the font's own glyph is used; past that
     * the delimiter is drawn, since a stretched glyph turns hairline-thin.
     */
    private fun fenced(left: String, body: MathBox, right: String, level: Int): MathBox {
        val s = size(level)
        val axis = axis(level)
        val reach = max(body.ascent - axis, body.descent + axis) + s * 0.08f
        val l = delimiter(left, reach, level)
        val r = delimiter(right, reach, level)
        val parts = listOf(MathPart(l, 0f, 0f), MathPart(body, l.width, 0f), MathPart(r, l.width + body.width, 0f))
        return MathBox(
            l.width + body.width + r.width,
            max(body.ascent, max(l.ascent, r.ascent)),
            max(body.descent, max(l.descent, r.descent)),
            parts,
        )
    }

    private fun delimiter(d: String, reach: Float, level: Int): MathBox {
        val s = size(level)
        if (d.isEmpty()) return MathBox(s * 0.12f, 0f, 0f)
        val axis = axis(level)
        if (reach <= s * 0.62f || d !in DRAWN_DELIMITERS) return glyphs(d, level, italic = false, bold = false)
        val rule = rule(level)
        val h = reach * 2
        val w = when (d) {
            "|" -> s * 0.28f
            "‖" -> s * 0.42f
            "{", "}" -> s * 0.42f + h * 0.03f
            else -> s * 0.36f + h * 0.03f
        }
        val stroke = rule * 1.25f
        return MathBox(w, axis + reach, reach - axis) { x, y, color ->
            val top = y - axis - reach + stroke
            val bottom = y - axis + reach - stroke
            val mid = y - axis
            val inset = w * 0.22f
            val a = x + inset
            val b = x + w - inset
            val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (d) {
                "(" -> drawPath(Path().apply { moveTo(b, top); quadraticTo(a - w * 0.2f, mid, b, bottom) }, color, style = style)
                ")" -> drawPath(Path().apply { moveTo(a, top); quadraticTo(b + w * 0.2f, mid, a, bottom) }, color, style = style)
                "[" -> drawPath(Path().apply { moveTo(b, top); lineTo(a, top); lineTo(a, bottom); lineTo(b, bottom) }, color, style = style)
                "]" -> drawPath(Path().apply { moveTo(a, top); lineTo(b, top); lineTo(b, bottom); lineTo(a, bottom) }, color, style = style)
                "⌊" -> drawPath(Path().apply { moveTo(a, top); lineTo(a, bottom); lineTo(b, bottom) }, color, style = style)
                "⌋" -> drawPath(Path().apply { moveTo(b, top); lineTo(b, bottom); lineTo(a, bottom) }, color, style = style)
                "⌈" -> drawPath(Path().apply { moveTo(b, top); lineTo(a, top); lineTo(a, bottom) }, color, style = style)
                "⌉" -> drawPath(Path().apply { moveTo(a, top); lineTo(b, top); lineTo(b, bottom) }, color, style = style)
                "|" -> drawLine(color, Offset(x + w / 2, top), Offset(x + w / 2, bottom), stroke)
                "‖" -> {
                    drawLine(color, Offset(x + w * 0.35f, top), Offset(x + w * 0.35f, bottom), stroke)
                    drawLine(color, Offset(x + w * 0.65f, top), Offset(x + w * 0.65f, bottom), stroke)
                }
                "⟨" -> drawPath(Path().apply { moveTo(b, top); lineTo(a, mid); lineTo(b, bottom) }, color, style = style)
                "⟩" -> drawPath(Path().apply { moveTo(a, top); lineTo(b, mid); lineTo(a, bottom) }, color, style = style)
                "{", "}" -> {
                    val (outer, inner) = if (d == "{") b to a else a to b
                    val c = (outer + inner) / 2
                    drawPath(
                        Path().apply {
                            moveTo(outer, top)
                            quadraticTo(c, top, c, top + (mid - top) * 0.3f)
                            lineTo(c, mid - (mid - top) * 0.22f)
                            quadraticTo(c, mid, inner, mid)
                            quadraticTo(c, mid, c, mid + (bottom - mid) * 0.22f)
                            lineTo(c, bottom - (bottom - mid) * 0.3f)
                            quadraticTo(c, bottom, outer, bottom)
                        },
                        color,
                        style = style,
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------------- grids

    private fun grid(g: LatexMath.Grid, level: Int): MathBox {
        val inner = if (g.display) DISPLAY else max(level, TEXT)
        val s = size(level)
        val cells = g.rows.map { row ->
            row.mapIndexed { c, cell -> node(cell, inner, leadingOrd = g.paired && c % 2 == 1) }
        }
        val columns = cells.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return fenced(g.left, MathBox(0f, 0f, 0f), g.right, level)
        val widths = FloatArray(columns)
        for (row in cells) row.forEachIndexed { c, box -> widths[c] = max(widths[c], box.width) }
        // Every row at least a strut tall, so short rows keep an even rhythm.
        val ups = cells.map { row -> max(s * 0.72f, row.maxOfOrNull { it.ascent } ?: 0f) }
        val downs = cells.map { row -> max(s * 0.28f, row.maxOfOrNull { it.descent } ?: 0f) }
        val rowGap = s * (if (g.display) 0.4f else 0.3f)
        fun gapAfter(c: Int): Float = when {
            g.paired -> if (c % 2 == 0) 0f else s * 1.6f
            g.left == "{" && g.right.isEmpty() -> s * 1.0f
            else -> s * 0.9f
        }
        val xs = FloatArray(columns)
        var x = 0f
        for (c in 0 until columns) {
            xs[c] = x
            x += widths[c] + if (c < columns - 1) gapAfter(c) else 0f
        }
        val width = x
        val height = ups.sum() + downs.sum() + rowGap * (cells.size - 1)
        val axis = axis(level)
        val ascent = height / 2 + axis
        val parts = ArrayList<MathPart>()
        var y = -ascent
        cells.forEachIndexed { r, row ->
            y += ups[r]
            row.forEachIndexed { c, box ->
                val align = when {
                    g.paired -> if (c % 2 == 0) LatexMath.Align.END else LatexMath.Align.START
                    else -> g.align
                }
                val dx = when (align) {
                    LatexMath.Align.START -> 0f
                    LatexMath.Align.CENTER -> (widths[c] - box.width) / 2
                    LatexMath.Align.END -> widths[c] - box.width
                }
                parts += MathPart(box, xs[c] + dx, y)
            }
            y += downs[r] + rowGap
        }
        val body = MathBox(width, ascent, height - ascent, parts)
        if (g.left.isEmpty() && g.right.isEmpty()) return body
        val pad = s * 0.12f
        return fenced(g.left, MathBox(width + 2 * pad, body.ascent, body.descent, listOf(MathPart(body, pad, 0f))), g.right, level)
    }

    // -------------------------------------------------------------------- metrics

    /** TeX's maths axis, where fraction bars and the middle of `=` sit. */
    private fun axis(level: Int) = size(level) * 0.25f

    private fun rule(level: Int) = max(1f, size(level) * 0.05f)

    companion object {
        const val DISPLAY = 0
        const val TEXT = 1
        const val SCRIPT = 2
        const val SCRIPTSCRIPT = 3

        private val SIZE = floatArrayOf(1f, 1f, 0.7f, 0.5f)
        private val FRACTION_LEVEL = intArrayOf(TEXT, SCRIPT, SCRIPTSCRIPT, SCRIPTSCRIPT)
        private val SCRIPT_LEVEL = intArrayOf(SCRIPT, SCRIPT, SCRIPTSCRIPT, SCRIPTSCRIPT)
        private val NOT_BEFORE_BIN = setOf(Kind.BIN, Kind.REL, Kind.OPEN, Kind.PUNCT, Kind.OP)
        private const val INTEGRALS = "∫∬∭∮∯"
        private val DRAWN_DELIMITERS = setOf("(", ")", "[", "]", "{", "}", "|", "‖", "⟨", "⟩", "⌊", "⌋", "⌈", "⌉")
        private const val CACHE_SIZE = 96

        private const val X_HEIGHT = "acemnorsuvwxzıȷαεικνοπστυωϵ∘⋅•∞∝"
        private const val DESCENDERS = "gjpqyβγζημξρφχψςϕϱ"
        private const val TALL = "()[]{}|‖⟨⟩⌊⌋⌈⌉/\\"
        private const val OPERATORS = "+−=×÷±∓<>≤≥≠≈∼≃≅≡∝→←↔⇒⇐⇔⟶⟵⟹⟸⟺↦∈∉∋⊂⊃⊆⊇∪∩∧∨⊕⊗⋆∗:"

        /**
         * How far a character's ink rises above the baseline, in ems. An
         * estimate by shape class: what matters is that `x` reads as short and
         * `(` as tall, so a fraction over `x` sits lower than one over `b`.
         */
        fun inkAscent(c: Char): Float = when {
            c in X_HEIGHT -> 0.46f
            c in TALL -> 0.76f
            c in OPERATORS -> 0.6f
            c == ',' || c == '.' -> 0.12f
            c == '′' -> 0.78f
            c == ' ' -> 0f
            else -> 0.71f
        }

        /** How far a character's ink drops below the baseline, in ems. */
        fun inkDescent(c: Char): Float = when {
            c in DESCENDERS -> 0.22f
            c in TALL -> 0.25f
            c == ',' || c == ';' -> 0.18f
            c == 'f' -> 0.2f // italic f hangs below the line
            else -> 0f
        }
    }
}

/** One laid-out piece. [ascent] and [descent] are ink extents from its baseline, in px. */
internal class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val parts: List<MathPart> = emptyList(),
    /** Its last glyph slants, so a superscript after it moves right a little. */
    val italic: Boolean = false,
    val paint: (DrawScope.(x: Float, y: Float, color: Color) -> Unit)? = null,
) {
    /** Draws with the left edge at [x] and the baseline at [y]. */
    fun draw(scope: DrawScope, x: Float, y: Float, color: Color) {
        paint?.invoke(scope, x, y, color)
        for (p in parts) p.box.draw(scope, x + p.dx, y + p.dy, color)
    }
}

/** [box] placed [dx] right of its parent's left edge and [dy] below its baseline. */
internal class MathPart(val box: MathBox, val dx: Float, val dy: Float)

/**
 * The typesetter for one reader at [fontSize], with the measurement inline
 * formulas need to sit on the line: how far above the baseline a placeholder
 * centred on the text sits ([textCenter]).
 */
internal class MathScope(val typesetter: MathTypesetter, val em: Float, val textCenter: Float)

@Composable
internal fun rememberMathScope(fontSize: TextUnit): MathScope {
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val density = LocalDensity.current
    val body = LocalTextStyle.current
    return remember(measurer, density, fontSize, body.fontFamily) {
        val em = with(density) { fontSize.toPx() }
        // A placeholder aligned TextCenter is centred halfway between the
        // font's ascent and descent, which is where this line's middle is.
        val probe = measurer.measure(
            AnnotatedString("x"),
            body.copy(fontSize = fontSize, lineHeight = TextUnit.Unspecified),
            density = density,
        )
        val center = probe.firstBaseline - (probe.getLineTop(0) + probe.getLineBottom(0)) / 2
        MathScope(MathTypesetter(measurer, density, em), em, center)
    }
}

/**
 * The inline formulas of [text] as [InlineTextContent], keyed by the ids
 * [ChatMarkdown] gives them, for a `Text`'s `inlineContent`.
 *
 * The placeholder is sized in ems of the surrounding text, so it scales with
 * it exactly (an sp size would not, under Android 14's non-linear font
 * scaling), and is centred on the text's middle with room above and below
 * so the formula's own baseline lands on the line's.
 */
internal fun MathScope.inlineContent(formulas: List<String>, color: Color): Map<String, InlineTextContent> {
    if (formulas.isEmpty()) return emptyMap()
    val map = HashMap<String, InlineTextContent>(formulas.size)
    formulas.forEachIndexed { k, latex ->
        val box = typesetter.layout(latex, display = false)
        val half = placeholderHalf(box)
        val width = box.width + em * 0.1f
        map[formulaId(k)] = InlineTextContent(
            Placeholder((width / em).em, (half * 2 / em).em, PlaceholderVerticalAlign.TextCenter),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // The placeholder is in ems of the text it sits in, which may be
                // a heading's or a footnote's rather than the body size the
                // formula was laid out at: scale to whatever it was given.
                val k = size.height / (half * 2)
                scale(k, k, pivot = Offset.Zero) {
                    box.draw(this, em * 0.05f, half + textCenter, color)
                }
            }
        }
    }
    return map
}

internal fun formulaId(index: Int) = "math:$index"

/** Half a placeholder's height: room for [box] above and below the text's middle. */
private fun MathScope.placeholderHalf(box: MathBox) = max(box.ascent - textCenter, box.descent + textCenter) + em * 0.06f

/**
 * The tallest of [formulas] as it sits in a line, in ems of the text around
 * it. A `Text` with a fixed line height keeps every line at that height,
 * placeholders included, so a root over `b²` would reach into the line above;
 * a paragraph holding one taller than its lines lets them grow instead.
 */
internal fun MathScope.inlineHeightEm(formulas: List<String>): Float =
    formulas.maxOfOrNull { placeholderHalf(typesetter.layout(it, display = false)) * 2 / em } ?: 0f

/**
 * A display formula on its own line, centred, scrolling sideways when it is
 * wider than the reader. TalkBack reads its plain text.
 */
@Composable
internal fun MathBlock(latex: String, color: Color, scope: MathScope, modifier: Modifier = Modifier) {
    val box = remember(latex, scope) { scope.typesetter.layout(latex, display = true) }
    val description = remember(latex) { runCatching { LatexMath.plain(latex) }.getOrDefault(latex) }
    val density = LocalDensity.current
    val pad = scope.em * 0.2f
    val size = with(density) { Size(box.width + pad * 2, box.ascent + box.descent + pad * 2) }
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.scrollHint(scroll, color).horizontalScroll(scroll).padding(bottom = SCROLL_HINT_ROOM)) {
            Canvas(
                Modifier
                    .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                    .semantics { contentDescription = description },
            ) {
                box.draw(this, pad, pad + box.ascent, color)
            }
        }
    }
}
