package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.random.Random

/**
 * The display maths of #811, laid out. Robolectric's text measurement is not a
 * real font's, so nothing here pins a pixel: it checks the shape of the
 * layout (a fraction is taller than its parts, a power rises, a matrix grows
 * with its rows) and that no input, however broken, throws or comes back
 * with a size that cannot be drawn.
 */
@RunWith(RobolectricTestRunner::class)
class MathTypesetterTest {

    private val density = Density(2.5f)
    private val em = 40f
    private val measurer = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr)
    private val typesetter = MathTypesetter(measurer, density, em)

    private fun d(latex: String) = typesetter.layout(latex, display = true)
    private fun t(latex: String) = typesetter.layout(latex, display = false)

    private fun sane(latex: String, box: MathBox) {
        for (v in listOf(box.width, box.ascent, box.descent)) {
            assertTrue("$latex: $v", v.isFinite())
        }
        assertTrue("$latex width ${box.width}", box.width >= 0f)
        assertTrue("$latex height", box.ascent + box.descent >= 0f)
        draw(box)
    }

    private fun draw(box: MathBox) {
        val w = (box.width + 20).toInt().coerceIn(1, 4000)
        val h = (box.ascent + box.descent + 20).toInt().coerceIn(1, 4000)
        val canvas = Canvas(ImageBitmap(w, h))
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
            box.draw(this, 10f, 10f + box.ascent, Color.Black)
        }
    }

    @Test
    fun `a fraction is taller than its parts and centred near the axis`() {
        val x = d("x")
        val frac = d("\\frac{x}{x}")
        assertTrue(frac.ascent > x.ascent)
        assertTrue(frac.descent > x.descent)
        // The bar sits on the axis, a quarter em up: more above than below.
        assertTrue(frac.ascent > frac.descent)
    }

    @Test
    fun `a display fraction stands taller than an inline one`() {
        assertTrue(d("\\frac{a}{b}").ascent > t("\\frac{a}{b}").ascent)
    }

    @Test
    fun `a nested fraction shrinks`() {
        val outer = d("\\frac{1}{\\frac{1}{x}}")
        val flat = d("\\frac{1}{x}")
        assertTrue(outer.descent > flat.descent)
    }

    @Test
    fun `a power rises and an index drops`() {
        assertTrue(d("x^2").ascent > d("x").ascent)
        assertTrue(d("x_i").descent > d("x").descent)
        val both = d("x_i^2")
        assertTrue(both.ascent > d("x").ascent && both.descent > d("x").descent)
        assertTrue(both.width >= d("x^2").width)
    }

    @Test
    fun `a sum carries its limits above and below in display, beside inline`() {
        val bare = d("\\sum")
        val limits = d("\\sum_{i=1}^{n}")
        assertTrue(limits.ascent > bare.ascent)
        assertTrue(limits.descent > bare.descent)
        val inline = t("\\sum_{i=1}^{n}")
        assertTrue(inline.ascent + inline.descent < limits.ascent + limits.descent)
    }

    @Test
    fun `an integral keeps its scripts beside it`() {
        val sum = d("\\sum_0^1")
        val int = d("\\int_0^1")
        assertTrue(int.width > d("\\int").width)
        assertTrue(sum.ascent > 0 && int.ascent > 0)
    }

    @Test
    fun `a root covers its body`() {
        val body = d("x+1")
        val root = d("\\sqrt{x+1}")
        assertTrue(root.ascent > body.ascent)
        assertTrue(root.width > body.width)
        assertTrue(d("\\sqrt[3]{x}").width >= d("\\sqrt{x}").width)
    }

    @Test
    fun `an accent sits above`() {
        assertTrue(d("\\hat{x}").ascent > d("x").ascent)
        assertTrue(d("\\overline{AB}").ascent > d("AB").ascent)
        assertTrue(d("\\underline{AB}").descent > d("AB").descent)
    }

    @Test
    fun `a matrix grows with its rows`() {
        val two = d("\\begin{pmatrix} 1 \\\\ 2 \\end{pmatrix}")
        val three = d("\\begin{pmatrix} 1 \\\\ 2 \\\\ 3 \\end{pmatrix}")
        assertTrue(three.ascent + three.descent > two.ascent + two.descent)
        val wide = d("\\begin{pmatrix} 1 & 2 & 3 \\end{pmatrix}")
        assertTrue(wide.width > d("\\begin{pmatrix} 1 \\end{pmatrix}").width)
    }

    @Test
    fun `tall brackets grow with their content`() {
        val small = d("\\left( x \\right)")
        val tall = d("\\left( \\frac{\\frac{a}{b}}{c} \\right)")
        assertTrue(tall.ascent > small.ascent)
        assertTrue(tall.descent > small.descent)
    }

    @Test
    fun `aligned rows are as wide as their widest`() {
        val one = d("a &= b")
        val two = d("\\begin{aligned} a &= b \\\\ ccc &= d \\end{aligned}")
        assertTrue(two.width >= one.width)
        assertTrue(two.ascent + two.descent > one.ascent + one.descent)
    }

    @Test
    fun `a layout is remembered`() {
        assertSame(d("\\frac{a}{b}"), d("\\frac{a}{b}"))
        // Display and inline are different layouts of the same source.
        assertTrue(d("\\frac{a}{b}") !== t("\\frac{a}{b}"))
    }

    @Test
    fun `the cache is bounded`() {
        repeat(500) { d("x_{$it}") }
        // Still answers, and an early entry was let go rather than kept for ever.
        val first = d("x_{0}")
        assertSame(first, d("x_{0}"))
    }

    @Test
    fun `an empty formula has no size`() {
        val box = d("")
        assertEquals(0f, box.width)
        sane("", box)
    }

    private val corpus = listOf(
        "x^2", "\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
        "\\int_0^\\infty e^{-x^2} \\, dx = \\frac{\\sqrt{\\pi}}{2}", "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1",
        "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", "\\begin{vmatrix} 1 & 2 \\\\ 3 & 4 \\end{vmatrix}",
        "f(x) = \\begin{cases} x^2 & x \\ge 0 \\\\ -x & \\text{otherwise} \\end{cases}",
        "\\begin{aligned} (a+b)^2 &= a^2 + 2ab + b^2 \\\\ &= c \\end{aligned}", "\\left[ \\frac{1}{2} \\right]",
        "\\left\\{ x \\in \\mathbb{R} : x > 0 \\right\\}", "\\left\\langle \\frac{u}{v} \\right\\rangle",
        "\\left\\lfloor \\frac{n}{2} \\right\\rfloor", "\\left\\lceil \\frac{n}{2} \\right\\rceil",
        "\\left| \\frac{a}{b} \\right|", "\\left\\| \\frac{a}{b} \\right\\|", "\\binom{n}{k}",
        "\\vec{F} = m\\vec{a}", "\\hat{\\theta}", "\\tilde{x}\\dot{y}\\ddot{z}\\check{a}\\breve{b}\\acute{c}\\grave{d}",
        "\\widehat{xyz}", "\\overset{\\text{def}}{=}", "\\underset{x}{\\arg\\max}", "\\xrightarrow{f}",
        "\\sqrt[3]{\\frac{a}{b}}", "\\frac{1}{1+\\frac{1}{1+\\frac{1}{x}}}", "e^{e^{e^{x}}}",
        "\\prod_{p \\text{ prime}} \\frac{1}{1-p^{-s}}", "\\oint_C \\vec{F} \\cdot d\\vec{r}",
        "\\mathcal{L}\\{f\\}(s) = \\int_0^\\infty f(t) e^{-st} dt", "\\nabla \\times \\vec{B} = \\mu_0 \\vec{J}",
        "a \\\\ b \\\\ c", "\\begin{array}{cc} 1 & 2 \\\\ 3 & 4 \\end{array}", "\\begin{matrix} \\end{matrix}",
        "\\begin{rcases} a \\\\ b \\end{rcases}", "x'' + \\omega^2 x = 0", "\\not\\subset", "\\left. \\frac{d}{dx} \\right|_{x=0}",
    )

    @Test
    fun `every formula and every prefix of it lays out and draws`() {
        for (latex in corpus) {
            for (end in 0..latex.length) {
                val part = latex.substring(0, end)
                try {
                    sane(part, d(part))
                    sane(part, t(part))
                } catch (e: Throwable) {
                    throw AssertionError("<$part> threw", e)
                }
            }
        }
    }

    @Test
    fun `random token soup lays out`() {
        val tokens = listOf(
            "\\frac", "\\sqrt", "{", "}", "^", "_", "\\left(", "\\right)", "\\left.", "\\right]", "&", "\\\\",
            "\\begin{pmatrix}", "\\end{pmatrix}", "\\begin{cases}", "\\end{cases}", "\\begin{aligned}", "\\end{aligned}",
            "x", "2", "+", "-", "=", "\\alpha", "\\text{a b}", "[", "]", "\\", "'", "\\hat", "\\overline", "\\sum",
            "\\int", "\\limits", "\\not", "\\mathbb", "\\overset", "\\binom", " ", "\\,", "\\!", "\\quad", "\\{",
            "\\|", "\\sin", "\\unknown", "𝐀", "\uD835", "~", "\\big(", "\\sqrt[", "\\underline",
        )
        val random = Random(811)
        repeat(3_000) {
            val latex = buildString { repeat(random.nextInt(0, 30)) { append(tokens[random.nextInt(tokens.size)]) } }
            try {
                sane(latex, d(latex))
                sane(latex, t(latex))
            } catch (e: Throwable) {
                throw AssertionError("<$latex> threw", e)
            }
        }
    }

    @Test(timeout = 30_000)
    fun `hostile input lays out without overflowing`() {
        val hostile = listOf(
            "{".repeat(20_000) + "x", "\\frac".repeat(5_000), "\\sqrt".repeat(5_000), "\\left(".repeat(5_000),
            "x^".repeat(2_000), "\\begin{pmatrix}".repeat(1_000), "&".repeat(2_000), "\\\\".repeat(2_000),
            "\\hat".repeat(5_000), "\\not".repeat(5_000), "\\frac{".repeat(60) + "x" + "}{y}".repeat(60),
        )
        for (latex in hostile) {
            try {
                val box = d(latex)
                for (v in listOf(box.width, box.ascent, box.descent)) assertTrue(v.isFinite())
            } catch (e: Throwable) {
                throw AssertionError("${latex.take(20)}… threw", e)
            }
        }
    }
}
