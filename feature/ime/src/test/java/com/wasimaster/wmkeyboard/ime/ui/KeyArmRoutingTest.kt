package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LayoutBehaviorSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
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
 * Where a swipe off a key goes once the key has arms (#789, test coverage for
 * #838 and #878), driven through [KeyButton]'s own gesture code with real
 * touch input.
 *
 * Arms belong to keys, not to the edges of the board (gestures.mdx: "Flicks
 * belong to keys"): a down arm on a bottom-row key, an up arm on a top-row key
 * and an outward arm on the key at either end of a row are each just that
 * key's arm, so each is a key here, swiped off the side it sits against. A
 * swipe shorter than the flick distance is a tap, a direction with no arm
 * does what the same key with no arms does, and directions are the screen's:
 * a right-to-left board does not mirror them.
 */
@RunWith(RobolectricTestRunner::class)
class KeyArmRoutingTest {

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

    private val palette = KeyPalette(
        key = Color.White, keyText = Color.Black, modifierKey = Color.Gray, modifierKeyText = Color.Black,
        enterKey = Color.Blue, enterKeyText = Color.White, pressedKey = Color.LightGray, accent = Color.Blue,
    )

    /** What the key sent: each key as `action:label`, each text as `text:…`, and a hide as `hide`. */
    private val sent = ArrayList<String>()

    private fun Key.sent(): String = "${action.javaClass.simpleName}:${output ?: label}"

    /** One key on the board, as drawn for one swipe; [n] makes each swipe a fresh key. */
    private class Board(
        val key: Key,
        val settings: KeyboardSettings,
        val direction: LayoutDirection,
        val n: Int,
    )

    private var board by mutableStateOf<Board?>(null)
    private var swipes = 0

    private fun start() {
        compose.setContent {
            val b = board ?: return@setContent
            val theme = remember(b.settings) {
                defaultKbTheme(lightColorScheme(), dark = false, amoled = false, settings = b.settings)
            }
            CompositionLocalProvider(
                LocalKbTheme provides theme,
                LocalLayoutDirection provides b.direction,
                LocalHideKeyboard provides { sent += "hide" },
            ) {
                // Room around the key, so a swipe off its edge is still over the window.
                Box(Modifier.size(300.dp)) {
                    key(b.n) {
                        Box(Modifier.padding(120.dp).size(60.dp).testTag("key")) {
                            KeyButton(
                                visual = keyVisual(b.key, KeyboardUiState(settings = b.settings), palette),
                                settings = b.settings,
                                modifier = Modifier.fillMaxSize(),
                                onKey = { sent += it.sent() },
                                onText = { sent += "text:$it" },
                                keyPreview = remember { KeyPreviewState() },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Swipes [key] by [dx], [dy] dp from its centre, in [STEPS] moves, and returns what it sent. */
    private fun swipe(
        key: Key,
        dx: Float,
        dy: Float,
        settings: KeyboardSettings = KeyboardSettings(),
        direction: LayoutDirection = LayoutDirection.Ltr,
    ): List<String> {
        if (swipes++ == 0) {
            board = Board(key, settings, direction, swipes)
            start()
        } else {
            board = Board(key, settings, direction, swipes)
        }
        compose.waitForIdle()
        sent.clear()
        val density = compose.density.density
        compose.onNodeWithTag("key").performTouchInput {
            down(center)
            repeat(STEPS) { moveBy(Offset(dx * density / STEPS, dy * density / STEPS)) }
            up()
        }
        compose.waitForIdle()
        return sent.toList()
    }

    private fun letter(c: String, vararg arms: Pair<FlickDirection, String>) = Key(c, flick = mapOf(*arms))

    private fun textArm(text: String) = "Text:$text"

    @Test
    fun `a down arm on a bottom-row key is that key's flick down`() {
        // The bottom row's letters (m on QWERTY) and its keys of their own.
        assertEquals(listOf(textArm(",")), swipe(letter("m", FlickDirection.DOWN to ","), 0f, FAR))
        val symbols = Key("?123", action = KeyAction.Symbols, flick = mapOf(FlickDirection.DOWN to "@"))
        assertEquals(listOf(textArm("@")), swipe(symbols, 0f, FAR))
    }

    @Test
    fun `an up arm on a top-row key is that key's flick up`() {
        assertEquals(listOf(textArm("1")), swipe(letter("q", FlickDirection.UP to "1"), 0f, -FAR))
    }

    @Test
    fun `an outward arm on each row-end key is that key's own`() {
        val cases = listOf(
            letter("q", FlickDirection.LEFT to "[") to -FAR,
            letter("p", FlickDirection.RIGHT to "]") to FAR,
            letter("a", FlickDirection.LEFT to "{") to -FAR,
            letter("l", FlickDirection.RIGHT to "}") to FAR,
            Key("⇧", action = KeyAction.Shift, flick = mapOf(FlickDirection.LEFT to "<")) to -FAR,
            Key("⌫", action = KeyAction.Delete, flick = mapOf(FlickDirection.RIGHT to ">")) to FAR,
            Key("?123", action = KeyAction.Symbols, flick = mapOf(FlickDirection.LEFT to "~")) to -FAR,
            Key("⏎", action = KeyAction.Enter, flick = mapOf(FlickDirection.RIGHT to "?")) to FAR,
        )
        for ((key, dx) in cases) {
            val arm = key.flick.values.single()
            assertEquals("${key.label} swiped ${if (dx < 0) "left" else "right"}", listOf(textArm(arm)), swipe(key, dx, 0f))
        }
    }

    @Test
    fun `a swipe shorter than the flick distance is a tap`() {
        val m = letter("m", FlickDirection.DOWN to ",")
        assertEquals(swipe(Key("m"), 0f, 0f), swipe(m, 0f, NEAR))
        val enter = Key("⏎", action = KeyAction.Enter, flick = mapOf(FlickDirection.RIGHT to "?"))
        assertEquals(listOf(Key("⏎", action = KeyAction.Enter).sent()), swipe(enter, NEAR, 0f))
    }

    @Test
    fun `a direction with no arm does what the key did with no arms`() {
        // Only an up arm: the swipe down is the key's own, as if it had none.
        val cases = listOf(
            letter("m", FlickDirection.UP to "!") to Key("m"),
            Key("⏎", action = KeyAction.Enter, flick = mapOf(FlickDirection.UP to "?")) to Key("⏎", action = KeyAction.Enter),
            Key("?123", action = KeyAction.Symbols, flick = mapOf(FlickDirection.UP to "@")) to
                Key("?123", action = KeyAction.Symbols),
        )
        for ((armed, plain) in cases) {
            assertEquals(plain.label, swipe(plain, 0f, FAR), swipe(armed, 0f, FAR))
        }
    }

    @Test
    fun `a down arm on the space bar runs instead of the swipe down to hide`() {
        // #863 gave the space bar arms: a direction with one belongs to it,
        // whether or not "Swipe down to hide" is on.
        val space = Key(" ", action = KeyAction.Space, flick = mapOf(FlickDirection.DOWN to "_"))
        val hideOff = KeyboardSettings()
        val hideOn = KeyboardSettings(layoutBehavior = LayoutBehaviorSettings(spaceSwipeDownHide = true))
        assertEquals(listOf(textArm("_")), swipe(space, 0f, PAST_HIDE, hideOff))
        assertEquals(listOf(textArm("_")), swipe(space, 0f, PAST_HIDE, hideOn))
        // Without the arm the swipe down hides, as before.
        assertEquals(listOf("hide"), swipe(Key(" ", action = KeyAction.Space), 0f, PAST_HIDE, hideOn))
    }

    @Test
    fun `a right-to-left board keeps the screen's directions`() {
        val key = letter("ب", FlickDirection.LEFT to "1", FlickDirection.RIGHT to "2")
        for (direction in LayoutDirection.entries) {
            assertEquals("$direction left", listOf(textArm("1")), swipe(key, -FAR, 0f, direction = direction))
            assertEquals("$direction right", listOf(textArm("2")), swipe(key, FAR, 0f, direction = direction))
        }
    }

    private companion object {
        /** Well past the default 22 dp flick distance and still short of a long drag. */
        const val FAR = 40f

        /** Past the space bar's 40 dp swipe down to hide. */
        const val PAST_HIDE = 60f

        /** Inside the flick distance. */
        const val NEAR = 8f

        const val STEPS = 5
    }
}
