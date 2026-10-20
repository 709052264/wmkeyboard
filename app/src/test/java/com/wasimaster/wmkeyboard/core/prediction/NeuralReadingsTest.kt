package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Where IndicXlit's readings join a phonetic strip: after the committed reading, or before an unknown one when leading. */
class NeuralReadingsTest {

    private val bangla = { w: String -> w.isNotEmpty() && w.all { it in 'ঀ'..'৿' } }
    private val listed = setOf("আমি", "ভালোবাসি", "আছো", "আম")

    private fun merge(strip: List<String>, readings: List<String>, max: Int = NeuralReadings.MAX) =
        NeuralReadings.merge(strip, readings, bangla, { it in listed }, max)

    @Test
    fun theCommittedReadingStaysFirst() {
        val merged = merge(listOf("ভালবাসী", "ভালবাস"), listOf("ভালোবাসী", "ভালোবাসি"))
        assertEquals("ভালবাসী", merged.first())
    }

    @Test
    fun listedReadingsComeBeforeTheRestInTheModelsOrder() {
        val merged = merge(listOf("আচো", "আচ"), listOf("আচো", "অচো", "আছো", "আঁচ"))
        assertEquals(listOf("আচো", "আছো", "অচো", "আঁচ", "আচ"), merged)
    }

    @Test
    fun atMostMaxReadingsAreAdded() {
        val merged = merge(listOf("ক"), listOf("খ", "গ", "ঘ", "ঙ", "চ"), max = 2)
        assertEquals(listOf("ক", "খ", "গ"), merged)
    }

    @Test
    fun aReadingAlreadyFurtherDownMovesUpOnce() {
        val merged = merge(listOf("আমি", "আম", "আমী"), listOf("আমী", "আমি"))
        assertEquals(listOf("আমি", "আমী", "আম"), merged)
    }

    @Test
    fun anEnglishHeadIsSkippedSoTheReadingsFollowTheNativeOne() {
        val merged = merge(listOf("hello", "হেল্লো", "হ্যালো"), listOf("হেলো"))
        assertEquals(listOf("hello", "হেল্লো", "হেলো", "হ্যালো"), merged)
    }

    @Test
    fun noNativeWordPutsThemAfterTheHead() {
        val merged = merge(listOf("hello", "help"), listOf("হেলো"))
        assertEquals(listOf("hello", "হেলো", "help"), merged)
    }

    @Test
    fun nothingToAddLeavesTheStripAlone() {
        val strip = listOf("আমি", "আম")
        assertSame(strip, merge(strip, emptyList()))
        assertSame(strip, merge(strip, listOf("আমি")))
    }

    @Test
    fun anEmptyStripTakesTheReadings() {
        assertEquals(listOf("আমি"), merge(emptyList(), listOf("আমি")))
    }

    private fun lead(strip: List<String>, readings: List<String>) =
        NeuralReadings.merge(strip, readings, bangla, { it in listed }, lead = true)

    @Test
    fun leadingPutsTheListedReadingFirstAndTheRulesReadingSecond() {
        val merged = lead(listOf("ভালবাসী", "ভালবাস"), listOf("ভালোবাসী", "ভালোবাসি"))
        assertEquals(listOf("ভালোবাসি", "ভালবাসী", "ভালোবাসী", "ভালবাস"), merged)
    }

    @Test
    fun aListedHeadIsNeverLedOver() {
        val merged = lead(listOf("আম", "আমী"), listOf("আমি"))
        assertEquals(listOf("আম", "আমি", "আমী"), merged)
    }

    @Test
    fun noListedReadingLeavesTheHeadFirst() {
        val merged = lead(listOf("ভালবাসী"), listOf("ভালোবাসী"))
        assertEquals(listOf("ভালবাসী", "ভালোবাসী"), merged)
    }

    @Test
    fun anEnglishHeadIsNotLedOver() {
        val merged = lead(listOf("hello", "হেল্লো"), listOf("আমি"))
        assertEquals(listOf("hello", "হেল্লো", "আমি"), merged)
    }

    @Test
    fun leaderAnswersTheCommitPathTheSameWay() {
        assertEquals("আমি", NeuralReadings.leader("আমী", listOf("আমী", "অমি", "আমি")) { it in listed })
        assertEquals(null, NeuralReadings.leader("আম", listOf("আমি")) { it in listed })
        assertEquals(null, NeuralReadings.leader("আমী", listOf("অমি")) { it in listed })
    }
}
