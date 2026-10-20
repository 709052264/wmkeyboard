package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.ShiftState
import com.wasimaster.wmkeyboard.ime.WordMenuFacts
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
 * Hold a strip word, then slide (#790), driven through the strip itself
 * (#824, #843): a lift saves the preview, a cancel saves nothing, a word on
 * the never-suggest list does not slide, and a strip that changes under the
 * finger leaves no badge behind.
 */
@RunWith(RobolectricTestRunner::class)
class RankSlideGestureTest {

    private val compose = createComposeRule()

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

    private var words by mutableStateOf(listOf("alpha", "beta", "gamma"))
    private val slides = mutableListOf<Triple<String, Int, Boolean>>()

    private fun start(blacklisted: Boolean = false) {
        val theme = defaultKbTheme(
            scheme = lightColorScheme(),
            dark = false,
            amoled = false,
            settings = KeyboardSettings(),
        )
        val hold = SuggestionHoldCallbacks(
            facts = { WordMenuFacts(blacklisted = blacklisted) },
            onRankSlide = { word, steps, block -> slides += Triple(word, steps, block) },
        )
        compose.setContent {
            CompositionLocalProvider(LocalKbTheme provides theme) {
                Row(Modifier.height(48.dp)) {
                    LatinSuggestionChips(
                        candidates = words,
                        enabled = true,
                        alpha = { 1f },
                        slotCount = 3,
                        textScale = 1f,
                        centerPrimaryEnabled = false,
                        shiftState = ShiftState.OFF,
                        onSuggestion = {},
                        suggestionHold = hold,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Holds `beta` past the long press and slides it [steps] steps, down for a negative count. */
    private fun holdAndSlide(steps: Int) {
        val node = compose.onNodeWithText("beta")
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS + 200)
        node.performTouchInput {
            val step = 24.dp.toPx() + 1f
            repeat(kotlin.math.abs(steps)) { moveBy(Offset(0f, if (steps < 0) step else -step)) }
        }
    }

    @Test
    fun `a lift far enough down never suggests the word, once`() {
        start()
        holdAndSlide(-7)
        compose.onNodeWithText("beta").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(Triple("beta", -7, true)), slides)
    }

    @Test
    fun `a lift two steps up raises the rank by two`() {
        start()
        holdAndSlide(2)
        compose.onNodeWithText("beta").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(Triple("beta", 2, false)), slides)
    }

    @Test
    fun `a cancelled slide saves nothing`() {
        start()
        holdAndSlide(-7)
        compose.onNodeWithText("beta").performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(emptyList<Triple<String, Int, Boolean>>(), slides)
    }

    @Test
    fun `a word on the never-suggest list does not slide`() {
        start(blacklisted = true)
        holdAndSlide(-7)
        compose.onNodeWithText("beta").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(emptyList<Triple<String, Int, Boolean>>(), slides)
    }

    @Test
    fun `a strip that changes under the finger leaves no badge and saves nothing`() {
        start()
        holdAndSlide(-7)
        words = listOf("alpha", "delta", "gamma")
        compose.waitForIdle()
        compose.onNodeWithText("Never suggest", substring = true).assertDoesNotExist()
        assertEquals(emptyList<Triple<String, Int, Boolean>>(), slides)
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
    }
}
