package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Block
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Span
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * LaTeX inside an answer or a note (#811): where [AiMarkdownBlocks] finds it,
 * that it leaves everything else as it was, and what [AiMarkdown.strip] puts
 * in a text field instead.
 */
class AiMarkdownMathTest {

    private fun paragraph(text: String): AiMarkdownBlocks.Inline =
        (AiMarkdownBlocks.parse(text).single() as Block.Paragraph).inline

    // ---------------------------------------------------------------- inline

    @Test
    fun `inline maths in a sentence`() {
        val inline = paragraph("The area is \$\\pi r^2\$.")
        assertEquals("The area is πr2.", inline.text)
        val math = inline.spans.single { it.style == Style.MATH }
        assertEquals(12, math.start)
        assertEquals(15, math.end)
        assertTrue(Span(14, 15, Style.SUPERSCRIPT) in inline.spans)
    }

    @Test
    fun `parenthesis delimiters`() {
        assertEquals("see x2 now", paragraph("see \\(x^2\\) now").text)
    }

    @Test
    fun `backslash escapes inside maths are maths, not markdown`() {
        // `\,` is an escapable character to markdown, a thin space to TeX.
        assertEquals("α β", paragraph("\$\\alpha\\,\\beta\$").text)
        assertEquals("{x}", paragraph("\$\\{x\\}\$").text)
        assertEquals("a_b", paragraph("\$a\\_b\$").text)
        assertEquals("50%", paragraph("\$50\\%\$").text)
    }

    @Test
    fun `markdown escapes outside maths still work`() {
        val inline = paragraph("\\*not italic\\* and \$x^2\$")
        assertEquals("*not italic* and x2", inline.text)
        assertTrue(inline.spans.none { it.style == Style.ITALIC && it.end <= 12 })
    }

    @Test
    fun `underscores and stars inside maths are not emphasis`() {
        val inline = paragraph("Here \$a_1 * b_1 * c\$ is it")
        assertTrue(inline.spans.none { it.style == Style.ITALIC && inline.text.substring(it.start, it.end).contains("∗") })
        assertTrue(inline.text.contains("∗"))
        val under = paragraph("so \$x_i\$ and \$y_j\$ both")
        assertEquals("so xi and yj both", under.text)
        assertEquals(2, under.spans.count { it.style == Style.SUBSCRIPT })
    }

    @Test
    fun `bold around maths`() {
        val inline = paragraph("**\$x^2\$**")
        assertEquals("x2", inline.text)
        assertTrue(Span(0, 2, Style.BOLD) in inline.spans)
        assertTrue(Span(1, 2, Style.SUPERSCRIPT) in inline.spans)
    }

    @Test
    fun `code spans keep their dollars`() {
        val inline = paragraph("run `echo \$x\$` now")
        assertEquals("run echo \$x\$ now", inline.text)
        assertTrue(inline.spans.none { it.style == Style.MATH })
        assertTrue(inline.spans.any { it.style == Style.CODE })
        val shell = paragraph("`\$HOME` and `\$PATH`")
        assertEquals("\$HOME and \$PATH", shell.text)
        assertTrue(shell.spans.none { it.style == Style.MATH })
    }

    @Test
    fun `money stays text`() {
        for (text in listOf(
            "It costs \$5 and \$10.",
            "between \$5-\$10 a day",
            "Save \$1,000 or \$2,000 a year",
            "a \$ sign and another \$ sign",
        )) {
            val inline = paragraph(text)
            assertEquals(text, inline.text)
            assertTrue(text, inline.spans.isEmpty())
        }
    }

    @Test
    fun `an escaped dollar stays a dollar`() {
        val inline = paragraph("costs \\\$5 and \\\$10")
        assertEquals("costs \$5 and \$10", inline.text)
        assertTrue(inline.spans.isEmpty())
    }

    @Test
    fun `a two-dimensional formula carries its source`() {
        val inline = paragraph("Half is \$\\frac{a}{b}\$ ok")
        assertEquals("Half is a/b ok", inline.text)
        assertEquals(Span(8, 11, Style.FORMULA, "\\frac{a}{b}"), inline.spans.single { it.style == Style.FORMULA })
    }

    @Test
    fun `several formulas in one sentence`() {
        val inline = paragraph("If \$a=1\$ and \$\\frac{b}{2}\$ then \$c^2\$.")
        assertEquals("If a = 1 and b/2 then c2.", inline.text)
        assertEquals(1, inline.spans.count { it.style == Style.FORMULA })
        assertEquals(3, inline.spans.count { it.style == Style.MATH })
    }

    @Test
    fun `maths in a link label`() {
        val inline = paragraph("[the \$x^2\$ rule](https://example.com)")
        assertEquals("the x2 rule", inline.text)
        assertTrue(inline.spans.any { it.style == Style.LINK && it.start == 0 && it.end == 11 })
    }

    @Test
    fun `maths in list items, quotes, headings and tables`() {
        val blocks = AiMarkdownBlocks.parse(
            "# The \$x^2\$ case\n- first \$a_i\$\n> quote \$b\$\n\n| a | b |\n|---|---|\n| \$x^2\$ | \$\\frac{1}{x}\$ |",
        )
        assertEquals("The x2 case", (blocks[0] as Block.Heading).inline.text)
        assertEquals("first ai", (blocks[1] as Block.Bullet).inline.text)
        assertEquals("quote b", (blocks[2] as Block.Quote).inline.text)
        val table = blocks[3] as Block.Table
        assertEquals("x2", table.rows[0][0].text)
        assertTrue(table.rows[0][1].spans.any { it.style == Style.FORMULA })
    }

    @Test
    fun `a half-streamed formula is plain text until it closes`() {
        assertEquals("so \$x^", paragraph("so \$x^").text)
        assertEquals("so x2", paragraph("so \$x^2\$").text)
    }

    // --------------------------------------------------------------- display

    @Test
    fun `a display block between paragraphs`() {
        val blocks = AiMarkdownBlocks.parse("Before\n\$\$\n\\frac{a}{b}\n\$\$\nAfter")
        assertEquals(3, blocks.size)
        assertEquals("Before", (blocks[0] as Block.Paragraph).inline.text)
        assertEquals(Block.Math("\\frac{a}{b}", true, line = 1), blocks[1])
        assertEquals(Block.Paragraph(AiMarkdownBlocks.Inline("After"), line = 4), blocks[2])
    }

    @Test
    fun `a display block on one line breaks a paragraph`() {
        val blocks = AiMarkdownBlocks.parse("We have\n\$\$x^2 + y^2 = z^2\$\$\nas wanted.")
        assertEquals(3, blocks.size)
        assertEquals(Block.Math("x^2 + y^2 = z^2", true, line = 1), blocks[1])
    }

    @Test
    fun `square bracket display`() {
        val blocks = AiMarkdownBlocks.parse("\\[\n E = mc^2\n\\]")
        assertEquals(listOf(Block.Math("E = mc^2", true, line = 0)), blocks)
    }

    @Test
    fun `a bare environment is a display block`() {
        val text = "\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}"
        val blocks = AiMarkdownBlocks.parse(text)
        assertEquals(listOf(Block.Math(text, true, line = 0)), blocks)
    }

    @Test
    fun `a math fence is a display block, a latex fence is code`() {
        assertEquals(listOf(Block.Math("x^2", true, line = 0)), AiMarkdownBlocks.parse("```math\nx^2\n```"))
        assertTrue(AiMarkdownBlocks.parse("```latex\n\\frac{a}{b}\n```").single() is Block.Code)
        assertTrue(AiMarkdownBlocks.parse("```tex\nx\n```").single() is Block.Code)
    }

    @Test
    fun `dollars inside a code fence stay code`() {
        val code = AiMarkdownBlocks.parse("```\n\$\$x\$\$\n\$y\$\n```").single() as Block.Code
        assertEquals("\$\$x\$\$\n\$y\$", code.code)
    }

    @Test
    fun `an unclosed display block streams`() {
        val blocks = AiMarkdownBlocks.parse("Look:\n\$\$\\frac{a")
        assertEquals(Block.Math("\\frac{a", false, line = 1), blocks.last())
    }

    @Test
    fun `an unclosed display block ends at a blank line`() {
        val blocks = AiMarkdownBlocks.parse("\$\$\nx\n\nNormal **text** here.")
        assertEquals(Block.Math("x", false, line = 0), blocks[0])
        assertEquals("Normal text here.", (blocks[1] as Block.Paragraph).inline.text)
    }

    @Test
    fun `line numbers after a display block are still right`() {
        val blocks = AiMarkdownBlocks.parse("\$\$\na\n\$\$\n- [ ] task")
        val task = blocks.last() as Block.Task
        assertEquals(3, task.line)
    }

    @Test
    fun `display blocks in a full answer`() {
        val answer = """
            |## Quadratic formula
            |
            |The roots of ${'$'}ax^2 + bx + c = 0${'$'} are
            |
            |${'$'}${'$'}
            |x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
            |${'$'}${'$'}
            |
            |where:
            |- ${'$'}a \neq 0${'$'}
            |- the discriminant is ${'$'}\Delta = b^2 - 4ac${'$'}
            |
            |```python
            |print("${'$'}5")
            |```
        """.trimMargin()
        val blocks = AiMarkdownBlocks.parse(answer)
        assertEquals(
            listOf(Block.Heading::class, Block.Paragraph::class, Block.Math::class, Block.Paragraph::class, Block.Bullet::class, Block.Bullet::class, Block.Code::class),
            blocks.map { it::class },
        )
        assertEquals("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", (blocks[2] as Block.Math).latex)
        assertEquals("print(\"\$5\")", (blocks[6] as Block.Code).code)
        assertEquals("a ≠ 0", (blocks[4] as Block.Bullet).inline.text)
    }

    // ----------------------------------------------------- nothing else moved

    @Test
    fun `text without maths parses exactly as before`() {
        // The pre-#811 parse, for answers that never had a formula in them.
        val samples = listOf(
            "Hello there.\nStill the same paragraph.",
            "## Title\n- first\n  - nested\n3. third\n> said\n---",
            "Use **bold**, *slant* and `code`.",
            "| a | b |\n|---|---|\n| 1 | 2 |",
            "[link](https://x.dev) and <https://y.dev>",
            "Footnote[^1].\n\n[^1]: The note.",
            "~~gone~~ \\*not italic\\* <del>x</del> <b>y</b>",
            "Price: 5 USD, no maths here; just 2 * 3 * 4.",
        )
        for (text in samples) {
            val blocks = AiMarkdownBlocks.parse(text)
            assertTrue(text, blocks.none { it is Block.Math })
            val spans = blocks.flatMap { b ->
                when (b) {
                    is Block.Paragraph -> b.inline.spans
                    is Block.Heading -> b.inline.spans
                    is Block.Bullet -> b.inline.spans
                    is Block.Quote -> b.inline.spans
                    else -> emptyList()
                }
            }
            assertTrue(text, spans.none { it.style == Style.MATH || it.style == Style.FORMULA })
        }
    }

    @Test
    fun `every prefix of an answer with maths parses`() {
        val answer = "Let \$f(x) = \\frac{1}{x}\$.\n\n\$\$\n\\int_1^e \\frac{dx}{x} = 1\n\$\$\n\nAnd \\(\\sqrt{2}\\) **is** irrational; " +
            "costs \$5.\n\\begin{pmatrix}\n1 & 0 \\\\\n0 & 1\n\\end{pmatrix}\n- \$a_i\$ done"
        for (end in 0..answer.length) {
            val text = answer.substring(0, end)
            try {
                for (block in AiMarkdownBlocks.parse(text)) checkSpans(block)
                AiMarkdown.strip(text)
                AiMarkdown.hasMarkdown(text)
            } catch (e: Throwable) {
                throw AssertionError("prefix $end <$text> threw", e)
            }
        }
    }

    @Test
    fun `random markdown with maths never throws`() {
        val pieces = listOf(
            "\$", "\$\$", "\\(", "\\)", "\\[", "\\]", "x^2", "\\frac{a}{b}", "**", "*", "_", "`", "```", "\n",
            "\n\n", "- ", "> ", "# ", "| a | b |\n|---|---|\n", "\\", "{", "}", "[x](y)", "\\begin{align}",
            "\\end{align}", "&", "\\\\", " ", "5", "word", "\\{", "\\\$", "```math\n", "~~",
        )
        val random = Random(8110)
        repeat(5_000) {
            val text = buildString { repeat(random.nextInt(0, 30)) { append(pieces[random.nextInt(pieces.size)]) } }
            try {
                for (block in AiMarkdownBlocks.parse(text)) checkSpans(block)
                AiMarkdown.strip(text)
            } catch (e: Throwable) {
                throw AssertionError("<$text> threw", e)
            }
        }
    }

    private fun checkSpans(block: Block) {
        val inlines = when (block) {
            is Block.Paragraph -> listOf(block.inline)
            is Block.Heading -> listOf(block.inline)
            is Block.Bullet -> listOf(block.inline)
            is Block.Task -> listOf(block.inline)
            is Block.Quote -> listOf(block.inline)
            is Block.Footnote -> listOf(block.inline)
            is Block.Table -> block.header + block.rows.flatten()
            else -> emptyList()
        }
        for (inline in inlines) {
            for (span in inline.spans) {
                assertTrue("$span in <${inline.text}>", span.start >= 0 && span.end <= inline.text.length && span.start <= span.end)
                if (span.style == Style.FORMULA) assertTrue(span.url != null)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `a long answer full of maths parses quickly`() {
        val chunk = "Then \$x_i^2 + \\frac{a}{b}\$ and \$\\sum_{k=1}^n k\$ hold, costing \$5.\n\n\$\$\n\\int_0^1 f(x)\\,dx\n\$\$\n\n"
        val answer = chunk.repeat(500)
        val start = System.nanoTime()
        val blocks = AiMarkdownBlocks.parse(answer)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(1000, blocks.size)
        assertTrue("took $ms ms", ms < 5_000)
    }

    // ------------------------------------------------------------------ strip

    @Test
    fun `maths counts as markdown for the strip toggle`() {
        assertTrue(AiMarkdown.hasMarkdown("The answer is \$x^2\$."))
        assertTrue(AiMarkdown.hasMarkdown("\$\$\nx\n\$\$"))
        assertTrue(AiMarkdown.hasMarkdown("so \\(x\\) is"))
        assertFalse(AiMarkdown.hasMarkdown("It costs \$5 and \$10."))
    }

    @Test
    fun `strip writes maths out in Unicode`() {
        assertEquals("Area πr² here", AiMarkdown.strip("Area \$\\pi r^2\$ here"))
        assertEquals("½", AiMarkdown.strip("\$\$\n\\frac{1}{2}\n\$\$"))
        assertEquals("x² + y² = z²", AiMarkdown.strip("\$\$x^2 + y^2 = z^2\$\$"))
        assertEquals("see x² now", AiMarkdown.strip("see \\(x^2\\) now"))
        assertEquals("E = mc²", AiMarkdown.strip("\\[\nE = mc^2\n\\]"))
    }

    @Test
    fun `strip keeps maths away from the emphasis rules`() {
        assertEquals("bold and a ∗ b ∗ c", AiMarkdown.strip("**bold** and \$a*b*c\$"))
        assertEquals("xᵢ and yⱼ", AiMarkdown.strip("\$x_i\$ and \$y_j\$"))
    }

    @Test
    fun `strip puts an aligned block on lines`() {
        assertEquals(
            "Proof:\na = b + c\n= d\nDone.",
            AiMarkdown.strip("Proof:\n\\begin{aligned}\na &= b + c \\\\\n&= d\n\\end{aligned}\nDone."),
        )
    }

    @Test
    fun `strip leaves money and code alone`() {
        assertEquals("It costs \$5 and \$10.", AiMarkdown.strip("It costs \$5 and \$10."))
        assertEquals("echo \$HOME", AiMarkdown.strip("```\necho \$HOME\n```"))
    }

    @Test
    fun `strip of an answer with every kind`() {
        val answer = "## Result\n\nWe get \$\\frac{a}{b}\$:\n\n\$\$\n\\sqrt{x+1}\n\$\$\n\n- **one** \$\\alpha\$"
        assertEquals("Result\n\nWe get a/b:\n\n√(x+1)\n\n• one α", AiMarkdown.strip(answer))
    }
}
