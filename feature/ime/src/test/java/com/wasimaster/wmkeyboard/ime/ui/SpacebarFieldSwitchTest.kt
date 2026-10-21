package com.wasimaster.wmkeyboard.ime.ui

import android.content.ComponentName
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.WMKeyboardService
import com.wasimaster.wmkeyboard.ime.glideKeyboard
import com.wasimaster.wmkeyboard.ime.glideReadyState
import com.wasimaster.wmkeyboard.ime.plantWindowStores
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
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
 * #796 AC4 through the service (#827, test coverage #878): a normal field with
 * a speed on the space bar, then a password field brought up with
 * `onStartInputView`. The first state the service emits for the new field is
 * drawn as the keyboard would draw it, with the readout as it stood when that
 * state went out, and the space bar shows no speed.
 */
@RunWith(RobolectricTestRunner::class)
class SpacebarFieldSwitchTest {

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

    private var state by mutableStateOf(KeyboardUiState())

    @Before
    @After
    fun clearReadout() {
        LiveWpmReadout.wpm.intValue = 0
    }

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun WMKeyboardService.words(n: Int) = repeat(n) {
        "abcd".forEach { c ->
            onText(c.toString())
            pass(KEY_MS)
        }
        onKey(Key(label = " ", action = KeyAction.Space))
        pass(KEY_MS)
    }

    private fun plant(service: WMKeyboardService, name: String, value: Any) {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    @Test
    fun `the first state for a password field draws no speed`() {
        val settings = KeyboardSettings(learnFromTyping = false, spacebarLiveWpm = true)
        val service = glideKeyboard(glideReadyState(settings = settings)).first
        // What startInputView reaches that only onCreate builds.
        plantWindowStores(service)
        plant(service, "clipboardStore", ClipboardStore(null))
        plant(service, "settingsRepository", SettingsRepository(RuntimeEnvironment.getApplication()))

        service.words(2)
        val reading = LiveWpmReadout.wpm.intValue
        assertTrue("fixture: two words make a reading", reading > 0)
        state = service.uiState.value
        compose.setContent {
            CompositionLocalProvider(LocalLiveWpmPrivate provides rememberLiveWpmPrivate(state)) {
                SpacebarLiveWpm(Color.Black, 1f)
            }
        }
        compose.onNodeWithText("$reading wpm").assertExists()

        val emitted = ArrayList<Pair<KeyboardUiState, Int>>()
        val watch = CoroutineScope(Dispatchers.Unconfined).launch {
            service.uiState.drop(1).collect { emitted += it to LiveWpmReadout.wpm.intValue }
        }
        val password = EditorInfo().also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        service.onStartInputView(password, false)
        watch.cancel()

        assertTrue("the switch emitted no state", emitted.isNotEmpty())
        val (first, readout) = emitted.first()
        assertTrue("the first state is already private: $first", first.secureField && first.liveWpmPrivate)

        compose.mainClock.autoAdvance = false
        LiveWpmReadout.wpm.intValue = readout
        state = first
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        if (readout > 0) compose.onNodeWithText("$readout wpm").assertDoesNotExist()
        compose.onNodeWithText("$reading wpm").assertDoesNotExist()
    }

    private companion object {
        const val KEY_MS = 250L
    }
}
