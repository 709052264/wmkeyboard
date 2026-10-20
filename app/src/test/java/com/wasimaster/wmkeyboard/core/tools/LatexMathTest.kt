package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Span
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Style
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Accent
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Atom
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Fenced
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Frac
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Grid
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Kind
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Mark
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Node
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Root
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Row
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Scripts
import com.wasimaster.wmkeyboard.core.tools.LatexMath.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * LaTeX maths (#811): the parser, the one-line writers, and the rules that
 * find a formula in an answer. The drawing is `MathTypesetterTest` in
 * `:feature:ime`; the markdown wiring is [AiMarkdownMathTest].
 */
class LatexMathTest {

    // ------------------------------------------------------------- the tree

    @Test
    fun `a fraction holds its two parts`() {
        assertEquals(Frac(Atom("a", italic = true), Atom("b", italic = true)), LatexMath.parse("\\frac{a}{b}"))
    }

    @Test
    fun `a fraction takes single tokens as its arguments`() {
        assertEquals(Frac(Atom("1"), Atom("2")), LatexMath.parse("\\frac12"))
        assertEquals(Frac(Atom("α", italic = true), Atom("β", italic = true)), LatexMath.parse("\\frac\\alpha\\beta"))
    }

    @Test
    fun `dfrac tfrac and cfrac are fractions too`() {
        for (cmd in listOf("dfrac", "tfrac", "cfrac")) {
            assertTrue(cmd, LatexMath.parse("\\$cmd{x}{y}") is Frac)
        }
    }

    @Test
    fun `a binomial is a ruleless fraction in brackets`() {
        val node = LatexMath.parse("\\binom{n}{k}") as Fenced
        assertEquals("(", node.left)
        assertEquals(")", node.right)
        assertFalse((node.body as Frac).rule)
    }

    @Test
    fun `a root reads its index`() {
        assertEquals(Root(Atom("2")), LatexMath.parse("\\sqrt{2}"))
        assertEquals(Root(Atom("x", italic = true), Atom("3")), LatexMath.parse("\\sqrt[3]{x}"))
    }

    @Test
    fun `superscript and subscript land on one base in either order`() {
        val a = LatexMath.parse("x_i^2") as Scripts
        val b = LatexMath.parse("x^2_i") as Scripts
        assertEquals(a, b)
        assertEquals(Atom("x", italic = true), a.base)
        assertEquals(Atom("2"), a.sup)
        assertEquals(Atom("i", italic = true), a.sub)
    }

    @Test
    fun `a script with nothing before it gets an empty base`() {
        val node = LatexMath.parse("^2") as Scripts
        assertEquals(LatexMath.EMPTY, node.base)
    }

    @Test
    fun `a doubled superscript does not lose the second one`() {
        // TeX refuses `x^2^3`; drawing both beats dropping one.
        assertEquals("x²³", LatexMath.plain("x^2^3"))
    }

    @Test
    fun `primes are a superscript and share it with a power`() {
        assertEquals(Scripts(Atom("f", italic = true), Atom("′"), null), LatexMath.parse("f'"))
        assertEquals(Scripts(Atom("f", italic = true), Atom("′′"), null), LatexMath.parse("f''"))
        assertEquals("x′²", LatexMath.plain("x'^2"))
        // Written out, the index comes first; drawn, the two stack.
        assertEquals("xᵢ′", LatexMath.plain("x'_i"))
    }

    @Test
    fun `letters are italic, digits and capitals of Greek are not`() {
        assertEquals(Atom("x", italic = true), LatexMath.parse("x"))
        assertEquals(Atom("12"), LatexMath.parse("12"))
        assertEquals(Atom("α", italic = true), LatexMath.parse("\\alpha"))
        assertEquals(Atom("Γ"), LatexMath.parse("\\Gamma"))
        assertEquals(Atom("σ", italic = true), LatexMath.parse("\\sigma"))
        assertEquals(Atom("Ω"), LatexMath.parse("\\Omega"))
    }

    @Test
    fun `the whole Greek alphabet lands on the right letters`() {
        val names = "alpha beta gamma delta zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon chi psi omega"
        val want = "αβγδζηθικλμνξοπρστυχψω"
        assertEquals(want, names.split(' ').joinToString("") { LatexMath.plain("\\$it") })
        val caps = names.split(' ').joinToString("") { LatexMath.plain("\\" + it.replaceFirstChar(Char::uppercaseChar)) }
        assertEquals("ΑΒΓΔΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΧΨΩ", caps)
        assertEquals("ϵεϕφϑ", listOf("epsilon", "varepsilon", "phi", "varphi", "vartheta").joinToString("") { LatexMath.plain("\\$it") })
    }

    @Test
    fun `a run of one face is one atom`() {
        assertEquals(Atom("abc", italic = true), LatexMath.parse("abc"))
        assertEquals(Atom("3.14"), LatexMath.parse("3.14"))
        // A digit and a letter are drawn differently, so they stay apart.
        assertEquals(Row(listOf(Atom("2"), Atom("x", italic = true))), LatexMath.parse("2x"))
    }

    @Test
    fun `operators carry their spacing class`() {
        assertEquals(Kind.BIN, (LatexMath.parse("+") as Atom).kind)
        assertEquals("−", (LatexMath.parse("-") as Atom).text)
        assertEquals(Kind.REL, (LatexMath.parse("=") as Atom).kind)
        assertEquals(Kind.REL, (LatexMath.parse("\\leq") as Atom).kind)
        assertEquals(Kind.BIN, (LatexMath.parse("\\times") as Atom).kind)
        assertEquals(Kind.OPEN, (LatexMath.parse("(") as Atom).kind)
        assertEquals(Kind.CLOSE, (LatexMath.parse(")") as Atom).kind)
        assertEquals(Kind.PUNCT, (LatexMath.parse(",") as Atom).kind)
    }

    @Test
    fun `big operators and named functions`() {
        val sum = LatexMath.parse("\\sum") as Atom
        assertTrue(sum.large)
        assertTrue(sum.limits)
        val int = LatexMath.parse("\\int") as Atom
        assertTrue(int.large)
        assertFalse(int.limits)
        val sin = LatexMath.parse("\\sin") as Atom
        assertEquals(Kind.OP, sin.kind)
        assertFalse(sin.large)
        assertTrue((LatexMath.parse("\\lim") as Atom).limits)
    }

    @Test
    fun `limits and nolimits override the default`() {
        assertTrue((LatexMath.parse("\\int\\limits") as Atom).limits)
        assertFalse((LatexMath.parse("\\sum\\nolimits") as Atom).limits)
        val scripts = LatexMath.parse("\\int\\limits_0^1") as Scripts
        assertTrue((scripts.base as Atom).limits)
    }

    @Test
    fun `left and right make one fenced group`() {
        val node = LatexMath.parse("\\left( x \\right)") as Fenced
        assertEquals("(", node.left)
        assertEquals(")", node.right)
        assertEquals("", (LatexMath.parse("\\left. x \\right|") as Fenced).left)
        assertEquals("{", (LatexMath.parse("\\left\\{ x \\right.") as Fenced).left)
        assertEquals("⟨", (LatexMath.parse("\\left\\langle x \\right\\rangle") as Fenced).left)
        assertEquals("‖", (LatexMath.parse("\\left\\| x \\right\\|") as Fenced).right)
    }

    @Test
    fun `a left that never meets its right still closes`() {
        val node = LatexMath.parse("\\left( x + 1") as Fenced
        assertEquals("(", node.left)
        assertEquals("", node.right)
    }

    @Test
    fun `matrices of every bracket kind`() {
        val cases = mapOf(
            "matrix" to ("" to ""), "pmatrix" to ("(" to ")"), "bmatrix" to ("[" to "]"),
            "Bmatrix" to ("{" to "}"), "vmatrix" to ("|" to "|"), "Vmatrix" to ("‖" to "‖"),
        )
        for ((env, delims) in cases) {
            val g = LatexMath.parse("\\begin{$env} a & b \\\\ c & d \\end{$env}") as Grid
            assertEquals(env, 2, g.rows.size)
            assertEquals(env, 2, g.rows[0].size)
            assertEquals(env, delims.first, g.left)
            assertEquals(env, delims.second, g.right)
        }
    }

    @Test
    fun `cases is a brace on the left with cells set left`() {
        val g = LatexMath.parse("\\begin{cases} x & x \\geq 0 \\\\ -x & x < 0 \\end{cases}") as Grid
        assertEquals("{", g.left)
        assertEquals("", g.right)
        assertEquals(LatexMath.Align.START, g.align)
        assertEquals(2, g.rows.size)
    }

    @Test
    fun `aligned pairs its columns`() {
        val g = LatexMath.parse("\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}") as Grid
        assertTrue(g.paired)
        assertTrue(g.display)
        assertEquals(2, g.rows.size)
    }

    @Test
    fun `a trailing row break leaves no empty row`() {
        val g = LatexMath.parse("\\begin{pmatrix} 1 \\\\ 2 \\\\ \\end{pmatrix}") as Grid
        assertEquals(2, g.rows.size)
    }

    @Test
    fun `row spacing in brackets is skipped, not drawn`() {
        val g = LatexMath.parse("\\begin{matrix} 1 \\\\[4pt] 2 \\end{matrix}") as Grid
        assertEquals(listOf(listOf<Node>(Atom("1")), listOf<Node>(Atom("2"))), g.rows)
    }

    @Test
    fun `array skips its column spec`() {
        val g = LatexMath.parse("\\begin{array}{cc} 1 & 2 \\end{array}") as Grid
        assertEquals(listOf(listOf<Node>(Atom("1"), Atom("2"))), g.rows)
    }

    @Test
    fun `a matrix inside a matrix`() {
        val g = LatexMath.parse("\\begin{pmatrix} \\begin{matrix} a \\end{matrix} & b \\end{pmatrix}") as Grid
        assertTrue(g.rows[0][0] is Grid)
    }

    @Test
    fun `bare rows at the top stack like gather`() {
        val g = LatexMath.parse("a = 1 \\\\ b = 2") as Grid
        assertFalse(g.paired)
        assertEquals(2, g.rows.size)
        val aligned = LatexMath.parse("a &= 1 \\\\ b &= 2") as Grid
        assertTrue(aligned.paired)
    }

    @Test
    fun `equation and a one-cell align are just their content`() {
        assertEquals(LatexMath.parse("x=1"), LatexMath.parse("\\begin{equation} x=1 \\end{equation}"))
        assertEquals(LatexMath.parse("x=1"), LatexMath.parse("\\begin{equation*} x=1 \\end{equation*}"))
        assertEquals(LatexMath.parse("x=1"), LatexMath.parse("\\begin{align} x=1 \\end{align}"))
    }

    @Test
    fun `labels tags and colours leave nothing behind`() {
        assertEquals(LatexMath.parse("x=1"), LatexMath.parse("x=1 \\label{eq:one} \\tag{1}"))
        assertEquals(LatexMath.parse("x=1"), LatexMath.parse("x=1 \\nonumber"))
        assertEquals(LatexMath.parse("x"), LatexMath.parse("\\textcolor{red}{x}"))
        assertEquals(LatexMath.parse("x"), LatexMath.parse("\\color{red} x"))
        assertEquals(LatexMath.parse("x"), LatexMath.parse("\\displaystyle x"))
        assertEquals(LatexMath.parse("x"), LatexMath.parse("\\boxed{x}"))
    }

    @Test
    fun `text keeps its spaces and is upright`() {
        assertEquals(Atom(" if "), LatexMath.parse("\\text{ if }"))
        assertEquals(Atom("a {b} c"), LatexMath.parse("\\text{a {b} c}"))
        assertEquals(Atom("and", italic = true), LatexMath.parse("\\textit{and}"))
        assertEquals(Atom("Var", Kind.OP), LatexMath.parse("\\operatorname{Var}"))
    }

    @Test
    fun `maths alphabets`() {
        assertEquals("ℝℕℤℚℂ", LatexMath.plain("\\mathbb{R}\\mathbb{N}\\mathbb{Z}\\mathbb{Q}\\mathbb{C}"))
        assertEquals("\uD835\uDD38", LatexMath.plain("\\mathbb{A}"))
        assertEquals("ℒℋ", LatexMath.plain("\\mathcal{L}\\mathcal{H}"))
        assertEquals("ℭ", LatexMath.plain("\\mathfrak{C}"))
        assertEquals(Atom("v", bold = true), LatexMath.parse("\\mathbf{v}"))
        assertEquals(Atom("v", italic = true, bold = true), LatexMath.parse("\\boldsymbol{v}"))
        assertEquals(Atom("d"), LatexMath.parse("\\mathrm{d}"))
        // Digits have no blackboard letter here; they stay digits.
        assertEquals("1", LatexMath.plain("\\mathbb{1}"))
    }

    @Test
    fun `an alphabet reaches inside a fraction`() {
        val f = LatexMath.parse("\\mathbf{\\frac{a}{b}}") as Frac
        assertTrue((f.num as Atom).bold)
        assertTrue((f.den as Atom).bold)
    }

    @Test
    fun `accents`() {
        assertEquals(Accent(Atom("x", italic = true), Mark.HAT), LatexMath.parse("\\hat{x}"))
        assertEquals(Accent(Atom("v", italic = true), Mark.VEC), LatexMath.parse("\\vec v"))
        assertEquals(Mark.OVERLINE, (LatexMath.parse("\\overline{AB}") as Accent).mark)
        assertEquals(Mark.TILDE, (LatexMath.parse("\\widetilde{xy}") as Accent).mark)
    }

    @Test
    fun `not before a relation picks its negated glyph`() {
        assertEquals(Atom("≠", Kind.REL), LatexMath.parse("\\not="))
        assertEquals(Atom("∉", Kind.REL), LatexMath.parse("\\not\\in"))
        assertEquals(Atom("≢", Kind.REL), LatexMath.parse("\\not\\equiv"))
        // No glyph of its own: a combining stroke through it.
        assertEquals("⊢\u0338", (LatexMath.parse("\\not\\vdash") as Atom).text)
    }

    @Test
    fun `explicit spaces`() {
        assertEquals(Space(1f), LatexMath.parse("\\quad"))
        assertEquals(Space(2f), LatexMath.parse("\\qquad"))
        assertEquals(Space(3 / 18f), LatexMath.parse("\\,"))
        assertEquals(Space(-3 / 18f), LatexMath.parse("\\!"))
    }

    @Test
    fun `escaped characters are themselves`() {
        assertEquals("{x}", LatexMath.plain("\\{x\\}"))
        assertEquals("50%", LatexMath.plain("50\\%"))
        assertEquals("$5", LatexMath.plain("\\$5"))
        assertEquals("a_b", LatexMath.plain("a\\_b"))
        assertEquals("#", LatexMath.plain("\\#"))
        assertEquals("&", LatexMath.plain("\\&"))
    }

    @Test
    fun `an unknown command is drawn as its own name`() {
        assertTrue(LatexMath.plain("\\foo{x}").startsWith("\\foo"))
        assertTrue(LatexMath.plain("\\foo{x}").contains("x"))
    }

    @Test
    fun `overset underset and stacked arrows force limits`() {
        val over = LatexMath.parse("\\overset{?}{=}") as Scripts
        assertEquals(true, over.limits)
        assertEquals(Atom("?", Kind.CLOSE), over.sup)
        assertEquals(true, (LatexMath.parse("\\underset{n}{\\max}") as Scripts).limits)
        val arrow = LatexMath.parse("\\xrightarrow{f}") as Scripts
        assertEquals(Atom("⟶", Kind.REL), arrow.base)
        assertEquals(true, (LatexMath.parse("\\stackrel{def}{=}") as Scripts).limits)
    }

    // ------------------------------------------------------- one line, plain

    @Test
    fun `plain text of everyday formulas`() {
        val cases = listOf(
            "x^2" to "x²",
            "x_i" to "xᵢ",
            "x_{ij}" to "xᵢⱼ",
            "x^{n+1}" to "xⁿ⁺¹",
            "a_{n}^{2}" to "aₙ²",
            "a^{-1}" to "a⁻¹",
            "2^{10}" to "2¹⁰",
            "e^{i\\pi}" to "e^(iπ)",
            "x_{\\alpha}" to "x_α",
            "x_{i,j}" to "x_(i,j)",
            "e = mc^2" to "e = mc²",
            "E=mc^2" to "E = mc²",
            "\\alpha + \\beta = \\gamma" to "α + β = γ",
            "a \\leq b" to "a ≤ b",
            "a \\neq b" to "a ≠ b",
            "a \\not= b" to "a ≠ b",
            "x \\in \\mathbb{N}" to "x ∈ ℕ",
            "\\mathbb{R}^n" to "ℝⁿ",
            "a \\cdot b" to "a ⋅ b",
            "a \\times b" to "a × b",
            "x = -1" to "x = −1",
            "-x" to "−x",
            "\\pm 1" to "±1",
            "\\frac{1}{2}" to "½",
            "\\frac12" to "½",
            "\\dfrac{3}{4}" to "¾",
            "\\frac{a}{b}" to "a/b",
            "\\frac{\\pi}{2}" to "π/2",
            "\\frac{1}{x}" to "1/x",
            "\\frac{a+b}{c}" to "(a+b)/c",
            "\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}" to "(−b±√(b²−4ac))/(2a)",
            "\\sqrt{2}" to "√2",
            "\\sqrt{x+1}" to "√(x+1)",
            "\\sqrt[3]{x}" to "∛x",
            "\\sqrt[4]{x}" to "∜x",
            "\\sqrt[n]{x}" to "ⁿ√x",
            "\\binom{n}{k}" to "C(n, k)",
            "\\left( \\frac{a}{b} \\right)" to "(a/b)",
            "\\{1, 2\\}" to "{1, 2}",
            "\\{x \\mid x > 0\\}" to "{x ∣ x > 0}",
            "f(x) = x^2" to "f(x) = x²",
            "f'(x)" to "f′(x)",
            "f''(x)" to "f′′(x)",
            "\\vec{v}" to "v\u20D7",
            "\\hat{x}" to "x\u0302",
            "\\bar{x}" to "x\u0304",
            "\\overline{AB}" to "A\u0305B\u0305",
            "\\text{if } x > 0" to "if x > 0",
            "10\\%" to "10%",
            "\\infty" to "∞",
            "a \\to b" to "a → b",
            "A \\implies B" to "A ⟹ B",
            "\\forall x \\exists y" to "∀x∃y",
            "\\nabla \\cdot \\vec{F}" to "∇ ⋅ F\u20D7",
            "\\partial_x f" to "∂ₓf",
            "a \\equiv b \\pmod{n}" to "a ≡ b (mod n)",
            "a \\bmod b" to "a mod b",
            "\\langle x, y \\rangle" to "⟨x, y⟩",
            "\\lfloor x \\rfloor" to "⌊x⌋",
            "|x|" to "|x|",
            "\\|x\\|" to "‖x‖",
            "1, 2, \\ldots, n" to "1, 2, …, n",
            "x \\approx 3.14" to "x ≈ 3.14",
            "\\emptyset" to "∅",
            "A \\cup B \\cap C" to "A ∪ B ∩ C",
            "A \\subseteq B" to "A ⊆ B",
            "n!" to "n!",
            "\\neg p \\land q" to "¬p ∧ q",
            "\\hbar\\omega" to "ℏω",
        )
        val failures = cases.mapNotNull { (latex, want) ->
            val got = LatexMath.plain(latex)
            if (got == want) null else "$latex: want <$want> got <$got>"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `functions keep the space before their argument`() {
        val cases = listOf(
            "\\sin x" to "sin x",
            "\\sin(x)" to "sin(x)",
            "\\sin\\left(x\\right)" to "sin(x)",
            "2\\sin x" to "2 sin x",
            "\\sin^2 x + \\cos^2 x = 1" to "sin² x + cos² x = 1",
            "2\\sin^2 x" to "2 sin² x",
            "\\log_2 n" to "log₂ n",
            "\\ln x" to "ln x",
            "\\max(a, b)" to "max(a, b)",
            "\\sin\\frac{\\pi}{2}" to "sin π/2",
            "\\sin \\theta \\cos \\theta" to "sin θ cos θ",
            "\\lim_{x \\to 0} \\frac{\\sin x}{x}" to "lim_(x→0) (sin x)/x",
            "\\lim_{n \\to \\infty} a_n" to "lim_(n→∞) aₙ",
            "\\det A" to "det A",
        )
        val failures = cases.mapNotNull { (latex, want) ->
            val got = LatexMath.plain(latex)
            if (got == want) null else "$latex: want <$want> got <$got>"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `big operators with their limits`() {
        val cases = listOf(
            "\\sum_{i=1}^{n} i" to "∑ᵢ₌₁ⁿ i",
            "\\sum_{i=1}^n i^2" to "∑ᵢ₌₁ⁿ i²",
            "\\prod_{k} a_k" to "∏ₖ aₖ",
            "\\int_0^1 x\\,dx" to "∫₀¹ x dx",
            "\\int_{-\\infty}^{\\infty} e^{-x^2} dx" to "∫_(−∞)^∞ e^(−x²)dx",
            "\\oint_C f" to "∮_C f",
        )
        val failures = cases.mapNotNull { (latex, want) ->
            val got = LatexMath.plain(latex)
            if (got == want) null else "$latex: want <$want> got <$got>"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `grids on one line and on many`() {
        assertEquals("(a, b; c, d)", LatexMath.plain("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"))
        assertEquals("[1, 0; 0, 1]", LatexMath.plain("\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}"))
        assertEquals(
            "f(x) = {x, x ≥ 0; −x, x < 0",
            LatexMath.plain("f(x) = \\begin{cases} x & x \\geq 0 \\\\ -x & x < 0 \\end{cases}"),
        )
        val aligned = "\\begin{aligned} a &= b + c \\\\ &= d \\end{aligned}"
        assertEquals("a = b + c; = d", LatexMath.plain(aligned))
        assertEquals("a = b + c\n= d", LatexMath.plain(aligned, lines = true))
        // Brackets keep a matrix on one line even when lines are allowed.
        assertEquals("(a; b)", LatexMath.plain("\\begin{pmatrix} a \\\\ b \\end{pmatrix}", lines = true))
    }

    @Test
    fun `plain never leaves spaces at the ends`() {
        for (latex in listOf(" x ", "\\quad x \\quad", "= 1", "x =", ", x")) {
            val got = LatexMath.plain(latex)
            assertEquals(latex, got.trim(), got)
        }
    }

    // ------------------------------------------------------------ one line, spans

    @Test
    fun `inline spans for a power`() {
        val inline = LatexMath.inline("x^2")
        assertEquals("x2", inline.text)
        assertTrue(Span(0, 1, Style.ITALIC) in inline.spans)
        assertTrue(Span(1, 2, Style.SUPERSCRIPT) in inline.spans)
        assertTrue(Span(0, 2, Style.MATH) in inline.spans)
    }

    @Test
    fun `inline spans for a subscript and spacing`() {
        val inline = LatexMath.inline("a_i + b")
        assertEquals("ai + b", inline.text)
        assertTrue(Span(1, 2, Style.SUBSCRIPT) in inline.spans)
    }

    @Test
    fun `a nested script nests its spans`() {
        val inline = LatexMath.inline("e^{x^2}")
        assertEquals("ex2", inline.text)
        assertTrue(Span(1, 3, Style.SUPERSCRIPT) in inline.spans)
        assertTrue(Span(2, 3, Style.SUPERSCRIPT) in inline.spans)
    }

    @Test
    fun `bold letters carry a bold span`() {
        assertTrue(Span(0, 1, Style.BOLD) in LatexMath.inline("\\mathbf{v}").spans)
    }

    @Test
    fun `a two-dimensional formula comes back as one formula span`() {
        val inline = LatexMath.inline("\\frac{a}{b}")
        assertEquals("a/b", inline.text)
        assertEquals(Span(0, 3, Style.FORMULA, "\\frac{a}{b}"), inline.spans.single { it.style == Style.FORMULA })
        assertTrue(inline.spans.any { it.style == Style.MATH })
        // Nothing else: the drawing takes the whole run.
        assertEquals(2, inline.spans.size)
    }

    @Test
    fun `half needs no drawing`() {
        val inline = LatexMath.inline("\\frac12")
        assertEquals("½", inline.text)
        assertTrue(inline.spans.none { it.style == Style.FORMULA })
    }

    @Test
    fun `an empty formula is empty`() {
        assertEquals("", LatexMath.inline("").text)
        assertTrue(LatexMath.inline("").spans.isEmpty())
        assertEquals("", LatexMath.inline("\\,").text)
        assertEquals("", LatexMath.plain("   "))
    }

    @Test
    fun `which formulas need drawing`() {
        val box = listOf(
            "\\frac{a}{b}", "\\frac{x+1}{2}", "\\sqrt{x+1}", "\\sqrt[5]{x}", "\\hat{xy}", "\\overline{AB}",
            "\\overset{a}{=}", "\\binom{n}{k}", "\\begin{pmatrix}1\\end{pmatrix}", "e^{\\frac{1}{x}}",
            "\\left( \\frac{a}{b} \\right)", "x + \\frac{a}{b}", "\\sqrt{\\frac12}",
        )
        val line = listOf(
            "x^2", "\\frac12", "\\sqrt{2}", "\\sqrt x", "\\sqrt[3]{x}", "\\hat{x}", "\\vec v", "\\alpha_i",
            "\\left( x \\right)", "a + b = c", "\\sum_{i=1}^n i", "\\sin x", "x_{i,j}^{2}", "\\text{if }",
        )
        for (latex in box) assertTrue(latex, LatexMath.needsBox(LatexMath.parse(latex)))
        for (latex in line) assertFalse(latex, LatexMath.needsBox(LatexMath.parse(latex)))
    }

    // ------------------------------------------------------------- finding it

    private fun found(text: String) = LatexMath.INLINE.findAll(text).map(LatexMath::source).toList()

    @Test
    fun `inline delimiters`() {
        assertEquals(listOf("x"), found("\$x\$"))
        assertEquals(listOf("x^2"), found("the \$x^2\$ term"))
        assertEquals(listOf("a", "b"), found("\$a\$ and \$b\$"))
        assertEquals(listOf("x"), found("(\$x\$)"))
        assertEquals(listOf("x"), found("\$x\$."))
        assertEquals(listOf("x^2"), found("so \\(x^2\\) holds"))
        assertEquals(listOf("x"), found("inline \$\$x\$\$ too"))
        assertEquals(listOf("\\frac{a}{b}"), found("is \$\\frac{a}{b}\$ here"))
    }

    @Test
    fun `money is not maths`() {
        val prose = listOf(
            "It costs \$5 and \$10.",
            "between \$5-\$10 a day",
            "US\$5 and US\$10",
            "\$100,000 and \$200,000",
            "pay \$5, or \$6",
            "\$5",
            "a \$ sign",
            "\$ x \$",
            "\$x \$",
            "\$ x\$",
            "\\\$x\\\$",
            "50\$ or 60\$",
            "\$\$",
            "\$\$\$\$",
        )
        for (text in prose) assertEquals(text, emptyList<String>(), found(text))
    }

    @Test
    fun `a formula after money is still found`() {
        assertEquals(listOf("x"), found("cost \$5 vs \$x\$"))
    }

    @Test
    fun `square brackets in a sentence are not maths`() {
        assertEquals(emptyList<String>(), found("see \\[1\\] and \\[2\\]"))
    }

    @Test
    fun `an inline formula never crosses a line`() {
        assertEquals(emptyList<String>(), found("\$a\nb\$"))
    }

    @Test
    fun `an escaped dollar inside a formula`() {
        assertEquals(listOf("\\\$5"), found("price \$\\\$5\$ now"))
    }

    @Test
    fun `contains finds both kinds`() {
        assertTrue(LatexMath.contains("say \$x\$"))
        assertTrue(LatexMath.contains("intro\n\$\$\nx\n\$\$"))
        assertTrue(LatexMath.contains("\\begin{align}\na&=b\n\\end{align}"))
        assertFalse(LatexMath.contains("It costs \$5 and \$10."))
        assertFalse(LatexMath.contains("plain words"))
    }

    // -------------------------------------------------------------- display

    private fun display(vararg lines: String) = LatexMath.display(lines.toList(), 0)

    @Test
    fun `display on one line`() {
        assertEquals(LatexMath.Display("x^2", 1, true), display("\$\$x^2\$\$"))
        assertEquals(LatexMath.Display("x^2", 1, true), display("  \$\$ x^2 \$\$  "))
        assertEquals(LatexMath.Display("x", 1, true), display("\$\$x\$\$."))
        assertEquals(LatexMath.Display("a", 1, true), display("\\[a\\]"))
    }

    @Test
    fun `display over several lines`() {
        assertEquals(LatexMath.Display("x^2", 3, true), display("\$\$", "x^2", "\$\$"))
        assertEquals(LatexMath.Display("x\n+1", 2, true), display("\$\$x", "+1\$\$"))
        assertEquals(LatexMath.Display("a", 3, true), display("\\[", "a", "\\]"))
        assertEquals(
            LatexMath.Display("\\begin{align}\na &= b\n\\end{align}", 3, true),
            display("\\begin{align}", "a &= b", "\\end{align}"),
        )
        assertEquals(
            LatexMath.Display("\\begin{pmatrix} 1 \\end{pmatrix}", 1, true),
            display("\\begin{pmatrix} 1 \\end{pmatrix}"),
        )
    }

    @Test
    fun `display that has not closed stops at a blank line`() {
        assertEquals(LatexMath.Display("x", 2, false), display("\$\$", "x", "", "more prose"))
        assertEquals(LatexMath.Display("\\frac{a", 1, false), display("\$\$\\frac{a"))
        assertEquals(LatexMath.Display("", 1, false), display("\$\$"))
    }

    @Test
    fun `not a display`() {
        assertNull(display("\$\$a\$\$ and \$\$b\$\$"))
        assertNull(display("\\[1\\] Smith et al."))
        assertNull(display("\$5 is cheap"))
        assertNull(display("text \$\$x\$\$"))
        assertNull(display("\\begin{itemize}"))
        assertNull(display("plain"))
    }

    @Test
    fun `display starts where it is asked to`() {
        val lines = listOf("intro", "\$\$", "y", "\$\$", "after")
        assertNull(LatexMath.display(lines, 0))
        assertEquals(LatexMath.Display("y", 4, true), LatexMath.display(lines, 1))
    }

    // ------------------------------------------------------------- robustness

    private val corpus = listOf(
        "x^2", "x_{ij}^{n+1}", "\\frac{a}{b}", "\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", "\\sqrt[3]{x^3+y^3}",
        "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}", "\\int_0^\\infty e^{-x} \\, dx = 1",
        "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1", "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
        "f(x) = \\begin{cases} x^2 & x \\ge 0 \\\\ -x & \\text{otherwise} \\end{cases}",
        "\\begin{aligned} (a+b)^2 &= a^2 + 2ab + b^2 \\\\ &= c \\end{aligned}",
        "\\left[ \\frac{1}{2} \\right]", "\\binom{n}{k} = \\frac{n!}{k!(n-k)!}", "\\vec{F} = m\\vec{a}",
        "\\mathbb{E}[X] = \\sum_x x P(X=x)", "\\nabla \\times \\vec{B} = \\mu_0 \\vec{J}", "e^{i\\pi} + 1 = 0",
        "\\overset{\\text{def}}{=}", "\\xrightarrow[below]{above}", "\\not\\subset", "\\hat{\\theta}_{MLE}",
        "\\overline{z_1 z_2} = \\overline{z_1}\\,\\overline{z_2}", "\\| x \\|_2", "\\{ x \\in \\mathbb{R} : x > 0 \\}",
        "a \\equiv b \\pmod{m}", "\\det\\begin{vmatrix} 1 & 2 \\\\ 3 & 4 \\end{vmatrix} = -2",
        "P(A \\mid B) = \\frac{P(B \\mid A) P(A)}{P(B)}", "\\frac{\\partial^2 u}{\\partial t^2} = c^2 \\nabla^2 u",
        "\\sqrt{\\sqrt{\\sqrt{x}}}", "\\frac{1}{1+\\frac{1}{1+\\frac{1}{x}}}",
    )

    /** Every prefix of every formula, as a stream delivers them: none may throw, every span in range. */
    @Test
    fun `every half-written formula still reads`() {
        for (latex in corpus) {
            for (end in 0..latex.length) {
                check(latex.substring(0, end))
            }
        }
    }

    private fun check(latex: String) {
        try {
            val node = LatexMath.parse(latex)
            LatexMath.needsBox(node)
            LatexMath.plain(latex)
            LatexMath.plain(latex, lines = true)
            val inline = LatexMath.inline(latex)
            for (span in inline.spans) {
                assertTrue("$latex: $span in <${inline.text}>", span.start in 0..inline.text.length)
                assertTrue("$latex: $span in <${inline.text}>", span.end in span.start..inline.text.length)
                assertTrue("$latex: empty $span", span.end > span.start)
            }
        } catch (e: Throwable) {
            throw AssertionError("<$latex> threw", e)
        }
    }

    @Test
    fun `random token soup never throws`() {
        val tokens = listOf(
            "\\frac", "\\sqrt", "{", "}", "^", "_", "\\left(", "\\right)", "\\left.", "\\right", "&", "\\\\",
            "\\begin{pmatrix}", "\\end{pmatrix}", "\\begin{cases}", "\\end{cases}", "\\begin{aligned}",
            "\\end{aligned}", "\\begin{array}", "x", "2", "+", "-", "=", "\\alpha", "\\text{a b}", "\\text{",
            "[", "]", "\\", "$", "'", "\\hat", "\\sum", "\\int", "\\limits", "\\not", "\\mathbb", "\\mathbf",
            "\\overset", "\\binom", " ", "\\,", "\\!", "\\quad", "\\{", "\\}", "\\|", "\\%", "\\pmod", "\\sin",
            "\\unknown", "\uD835\uDC00", "\uD835", "\uDC00", "\u0000", "~", "\\begin", "\\end", "\\label{",
            "\\color{red}", "\\textcolor", "\\middle|", "\\big(", "\\xrightarrow", "\\sqrt[", "\\\\[2pt]",
        )
        val random = Random(811)
        repeat(20_000) {
            val latex = buildString { repeat(random.nextInt(0, 40)) { append(tokens[random.nextInt(tokens.size)]) } }
            check(latex)
        }
    }

    @Test
    fun `random characters never throw`() {
        val random = Random(1811)
        repeat(20_000) {
            val latex = buildString {
                repeat(random.nextInt(0, 30)) {
                    append(
                        when (random.nextInt(4)) {
                            0 -> "\\{}^_&\$'[]()".random(random)
                            1 -> random.nextInt(0x20, 0x7F).toChar()
                            2 -> random.nextInt(0, 0xFFFF).toChar()
                            else -> 'a' + random.nextInt(26)
                        },
                    )
                }
            }
            check(latex)
        }
    }

    @Test(timeout = 20_000)
    fun `deep nesting neither overflows nor stalls`() {
        val hostile = listOf(
            "{".repeat(100_000) + "x",
            "}".repeat(100_000),
            "\\frac".repeat(20_000),
            "\\frac{".repeat(20_000),
            "\\sqrt".repeat(50_000),
            "\\left(".repeat(20_000),
            "\\right)".repeat(20_000),
            "x^".repeat(50_000),
            "^".repeat(50_000),
            "x'".repeat(20_000),
            "\\begin{pmatrix}".repeat(5_000),
            "\\end{pmatrix}".repeat(5_000),
            "&".repeat(50_000),
            "\\\\".repeat(50_000),
            "\\hat".repeat(50_000),
            "\\mathbb{".repeat(20_000),
            "\\not".repeat(50_000),
            "\\text{".repeat(20_000),
            "a".repeat(200_000),
            "\\".repeat(100_000),
            "\\sqrt[".repeat(20_000),
            "\\overset{".repeat(20_000),
        )
        val slow = ArrayList<String>()
        for (latex in hostile) {
            val start = System.nanoTime()
            try {
                LatexMath.plain(latex)
                LatexMath.inline(latex)
                LatexMath.needsBox(LatexMath.parse(latex))
            } catch (e: Throwable) {
                throw AssertionError("${latex.take(20)}… threw", e)
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            println("${latex.take(16)}… (${latex.length} chars): $ms ms")
            // Linear work on at most 200,000 characters: anything slower than
            // this is a quadratic path, not a slow machine.
            if (ms > 3_000) slow += "${latex.take(16)}… $ms ms"
        }
        assertTrue(slow.joinToString(), slow.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `nesting at the limit is linear, not exponential`() {
        // Each level looks at its parts' text before writing them; written
        // naively that is 2^depth work at 40 levels.
        val fractions = "\\frac{".repeat(60) + "x" + "}{y}".repeat(60)
        val roots = "\\sqrt{".repeat(60) + "x" + "}".repeat(60)
        val powers = "x^{".repeat(60) + "y" + "}".repeat(60)
        val mixed = "\\frac{\\sqrt{x^{".repeat(30) + "z" + "}}}{2}".repeat(30)
        for (latex in listOf(fractions, roots, powers, mixed)) {
            LatexMath.plain(latex)
            LatexMath.inline(latex)
        }
    }

    @Test(timeout = 10_000)
    fun `a long answer worth of formulas is quick`() {
        val start = System.nanoTime()
        repeat(200) { for (latex in corpus) LatexMath.inline(latex) }
        val ms = (System.nanoTime() - start) / 1_000_000
        // 6,000 formulas. A generous bound: this is a guard against a
        // pathological regression, not a benchmark.
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun `the parse of a formula is stable`() {
        for (latex in corpus) {
            assertEquals(latex, LatexMath.parse(latex), LatexMath.parse(latex))
            assertNotNull(LatexMath.plain(latex))
        }
    }
}
