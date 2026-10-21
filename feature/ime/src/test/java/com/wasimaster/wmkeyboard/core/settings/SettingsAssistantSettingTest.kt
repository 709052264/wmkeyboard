package com.wasimaster.wmkeyboard.core.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * "Offer the Settings assistant" (#830, test coverage #878) through storage:
 * on until it is set, and both values come back as written. What the switch
 * hides is `SettingsAssistantGateTest` in :app.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsAssistantSettingTest {

    private fun repository() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `an unset switch reads as on`() = runBlocking {
        assertTrue(repository().settings.first().appUi.settingsAssistant)
    }

    @Test
    fun `off and back on round-trip through storage`() = runBlocking {
        val repository = repository()
        val before = repository.settings.first()
        repository.setSettingsAssistant(false)
        val off = repository.settings.first()
        assertFalse(off.appUi.settingsAssistant)
        // Turning it off touches nothing else: providers and keys stay as set.
        assertEquals(before.ai, off.ai)
        assertEquals(before.appUi.copy(settingsAssistant = false), off.appUi)
        repository.setSettingsAssistant(true)
        assertTrue(repository.settings.first().appUi.settingsAssistant)
    }
}
