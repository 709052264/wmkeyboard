package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.wasimaster.wmkeyboard.core.tools.LatexMath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * A chat answer with LaTeX in it (#811), drawn by [ChatMarkdown] as it
 * streams in. Every frame of the stream is composed, measured and drawn,
 * because a half-written formula is what the reader sees most of the time
 * and the place a layout bug would crash the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
class ChatMarkdownMathTest {

    private val compose = createComposeRule()

    // The rule launches a ComponentActivity, which this library module's test
    // manifest does not declare, so it is registered before the launch.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val app = RuntimeEnvironment.getApplication()
                    shadowOf(app.packageManager)
                        .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                }
            },
        )
        .around(compose)

    private var text by mutableStateOf("")

    private fun start() {
        compose.setContent {
            ChatMarkdown(text, ChatMarkdownColors(text = Color.Black, dim = Color.Gray, codeBackground = Color.LightGray))
        }
    }

    private val quadratic = "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"

    private val answer = "## Roots\n\nFor \$ax^2 + bx + c = 0\$ with \$a \\neq 0\$, the roots are\n\n" +
        "\$\$\n$quadratic\n\$\$\n\n" +
        "so a half is \$\\frac{1}{2}\$ and a ratio is \$\\frac{p}{q}\$; it costs \$5, or \$10.\n\n" +
        "- \$\\sum_{i=1}^n i\$\n- \$\\begin{pmatrix} 1 & 0 \\\\ 0 & 1 \\end{pmatrix}\$\n\n" +
        "| x | \$f(x)\$ |\n|---|---|\n| 1 | \$\\sqrt{x+1}\$ |\n\n" +
        "\\begin{aligned}\na &= b \\\\\n&= c\n\\end{aligned}\n\n" +
        "```math\n\\int_0^1 x\\,dx\n```"

    @Test
    fun `a finished answer draws its display formulas`() {
        text = answer
        start()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(LatexMath.plain(quadratic)).assertExists()
        compose.onNodeWithContentDescription(LatexMath.plain("\\int_0^1 x\\,dx")).assertExists()
        // Money in the same sentence stays words.
        compose.onNodeWithText("it costs \$5, or \$10.", substring = true).assertExists()
    }

    @Test
    fun `an inline formula keeps its plain text for selection and copy`() {
        text = "A ratio is \$\\frac{p}{q}\$ here."
        start()
        compose.waitForIdle()
        compose.onNodeWithText("A ratio is p/q here.").assertExists()
    }

    @Test
    fun `an answer with no maths draws as it did`() {
        text = "Plain **words** and `code`, costing \$5 or \$6."
        start()
        compose.waitForIdle()
        compose.onNodeWithText("Plain words and code, costing \$5 or \$6.").assertExists()
    }

    @Test
    fun `every frame of a streamed answer composes and draws`() {
        start()
        // Two characters a frame: roughly how a fast model streams.
        var end = 0
        while (end < answer.length) {
            end = (end + 2).coerceAtMost(answer.length)
            text = answer.substring(0, end)
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription(LatexMath.plain(quadratic)).assertExists()
    }

    @Test
    fun `a stray opening of a display block does not swallow the answer`() {
        text = "\$\$\nx\n\nThe rest is **prose**."
        start()
        compose.waitForIdle()
        compose.onNodeWithText("The rest is prose.").assertExists()
        assertEquals(1, compose.onAllNodesWithContentDescription("x").fetchSemanticsNodes().size)
    }

    @Test
    fun `broken latex never takes the answer down`() {
        val broken = listOf(
            "\$\$\\frac{\$\$", "\$\$\\left(\$\$", "\$\$\\begin{pmatrix}\$\$", "\$\\sqrt[\$", "\$\$}}}}{{{\$\$",
            "\$\$" + "\\frac".repeat(2_000) + "\$\$", "\$\$" + "{".repeat(5_000) + "\$\$", "\$\$\\\\\\\\&&\$\$",
        )
        start()
        for (b in broken) {
            text = "Before\n\n$b\n\nAfter"
            compose.waitForIdle()
            compose.onNodeWithText("After").assertExists()
        }
    }
}
