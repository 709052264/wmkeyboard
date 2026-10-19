package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The spacebar's speed (#752) is off the key on the first frame drawn for a
 * password or incognito field, before the service has had a keystroke to clear
 * the number with: the gate reads the frame's own state, the way
 * [KeyboardScreen] provides it.
 */
@RunWith(RobolectricTestRunner::class)
class SpacebarLiveWpmPrivacyTest {

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

    private var state by mutableStateOf(KeyboardUiState())

    @Before
    fun aReading() {
        LiveWpmReadout.wpm.intValue = 42
    }

    @After
    fun clearReadout() {
        LiveWpmReadout.wpm.intValue = 0
    }

    private fun start() = compose.setContent {
        CompositionLocalProvider(LocalLiveWpmPrivate provides rememberLiveWpmPrivate(state)) {
            SpacebarLiveWpm(Color.Black, 1f)
        }
    }

    private fun readout() = compose.onNodeWithText("42 wpm")

    /** Moves [state] to [next] and draws exactly one frame of it. */
    private fun oneFrameOf(next: KeyboardUiState) {
        compose.mainClock.autoAdvance = false
        state = next
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `an ordinary field shows the speed`() {
        start()
        readout().assertExists()
    }

    @Test
    fun `a password field hides it on its first frame`() {
        start()
        readout().assertExists()
        oneFrameOf(state.copy(secureField = true))
        readout().assertDoesNotExist()
        assertEquals("the frame hid it, not the service", 42, LiveWpmReadout.wpm.intValue)
    }

    @Test
    fun `incognito hides it on its first frame`() {
        start()
        readout().assertExists()
        oneFrameOf(state.copy(settings = KeyboardSettings(incognito = true)))
        readout().assertDoesNotExist()
    }

    @Test
    fun `a field that asks for incognito hides it on its first frame`() {
        start()
        readout().assertExists()
        oneFrameOf(state.copy(fieldIncognito = true))
        readout().assertDoesNotExist()
    }
}