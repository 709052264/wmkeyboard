package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.ime.allUpPauseMs
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
 * The three all-thumbs-up rows that follow the swiped-word pause until they are
 * set (#841), read through storage the way the keyboard reads them.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbPauseSettingsTest {

    private fun repository() = SettingsRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `an unset one-letter pause holds no letter above a lowered tapped-word pause`() = runBlocking {
        val repository = repository()
        repository.setGestureTwoThumbPauseMs(350)
        repository.setGestureTwoThumbTapPauseMs(200)
        val gesture = repository.settings.first().gesture
        assertEquals(200L, allUpPauseMs(gesture, swiped = false, letters = 1))
        assertEquals(200L, allUpPauseMs(gesture, swiped = false, letters = 2))
        assertEquals(350L, allUpPauseMs(gesture, swiped = true, letters = 1))
    }

    @Test
    fun `a one-letter pause that is set is still a floor`() = runBlocking {
        val repository = repository()
        repository.setGestureTwoThumbPauseMs(300)
        repository.setGestureTwoThumbTapPauseMs(300)
        repository.setGestureTwoThumbOneLetterPauseMs(800)
        val gesture = repository.settings.first().gesture
        assertEquals(800L, allUpPauseMs(gesture, swiped = false, letters = 1))
        assertEquals(300L, allUpPauseMs(gesture, swiped = false, letters = 2))
    }

    @Test
    fun `a reset row follows the swiped-word pause again`() = runBlocking {
        val repository = repository()
        repository.setGestureTwoThumbPauseMs(500)
        repository.setGestureTwoThumbTapPauseMs(200)
        repository.setGestureTwoThumbOneLetterPauseMs(900)
        repository.setGestureTwoThumbRetractWindowMs(100)
        var gesture = repository.settings.first().gesture
        assertTrue(gesture.twoThumbTapPauseSet && gesture.twoThumbOneLetterPauseSet && gesture.twoThumbRetractWindowSet)
        repository.resetGestureTwoThumbTapPauseMs()
        repository.resetGestureTwoThumbOneLetterPauseMs()
        repository.resetGestureTwoThumbRetractWindowMs()
        gesture = repository.settings.first().gesture
        assertEquals(500, gesture.twoThumbTapPauseMs)
        assertEquals(500, gesture.twoThumbOneLetterPauseMs)
        assertEquals(1000, gesture.twoThumbRetractWindowMs)
        assertFalse(gesture.twoThumbTapPauseSet || gesture.twoThumbOneLetterPauseSet || gesture.twoThumbRetractWindowSet)
        repository.setGestureTwoThumbPauseMs(600)
        gesture = repository.settings.first().gesture
        assertEquals(600, gesture.twoThumbTapPauseMs)
        assertEquals(600, gesture.twoThumbOneLetterPauseMs)
        assertEquals(1200, gesture.twoThumbRetractWindowMs)
    }

    @Test
    fun `moving only the swiped-word pause sets none of the three`() = runBlocking {
        val repository = repository()
        repository.setGestureTwoThumbPauseMs(500)
        val gesture = repository.settings.first().gesture
        assertFalse(gesture.twoThumbTapPauseSet || gesture.twoThumbOneLetterPauseSet || gesture.twoThumbRetractWindowSet)
    }
}
