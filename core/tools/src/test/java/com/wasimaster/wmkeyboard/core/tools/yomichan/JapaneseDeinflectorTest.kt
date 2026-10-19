package com.wasimaster.wmkeyboard.core.tools.yomichan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseDeinflectorTest {

    /** Whether [word] deinflects to [base] as a word listed with [rules]. */
    private fun reaches(word: String, base: String, rules: String): Boolean =
        JapaneseDeinflector.candidates(word).any { it.text == base && JapaneseDeinflector.matchesRules(it, rules) }

    @Test
    fun `polite past of an ichidan verb`() = assertTrue(reaches("食べました", "食べる", "v1"))

    @Test
    fun `negative past of a godan verb`() = assertTrue(reaches("行かなかった", "行く", "v5k-s"))

    @Test
    fun `negative of an adjective`() = assertTrue(reaches("高くない", "高い", "adj-i"))

    @Test
    fun `masu form of suru`() = assertTrue(reaches("します", "する", "vs-i"))

    @Test
    fun `past of kuru in kanji and in kana`() {
        assertTrue(reaches("来た", "来る", "vk"))
        assertTrue(reaches("きた", "くる", "vk"))
    }

    @Test
    fun `te iru progressive`() {
        assertTrue(reaches("読んでいる", "読む", "v5m"))
        assertTrue(reaches("読んでいた", "読む", "v5m"))
    }

    @Test
    fun `iku has its own te form`() = assertTrue(reaches("行って", "行く", "v5k-s"))

    @Test
    fun `a suru noun drops its suru`() = assertTrue(reaches("勉強しました", "勉強", "n vs"))

    @Test
    fun `potential and passive chain on as ichidan verbs`() {
        assertTrue(reaches("書けない", "書く", "v5k"))
        assertTrue(reaches("食べられなかった", "食べる", "v1"))
    }

    @Test
    fun `the word as found matches anything, a deinflected one only its class`() {
        val asFound = JapaneseDeinflector.candidates("見た").first()
        assertEquals("見た", asFound.text)
        assertTrue(JapaneseDeinflector.matchesRules(asFound, ""))
        assertTrue(reaches("見た", "見る", "v1"))
        assertFalse(reaches("見た", "見る", "adj-i"))
    }

    @Test
    fun `an in-between form is never a headword`() {
        // 食べます is a candidate of 食べました, but no entry is listed as a ます form.
        val masu = JapaneseDeinflector.candidates("食べました").first { it.text == "食べます" }
        assertFalse(JapaneseDeinflector.matchesRules(masu, "v1"))
    }

    @Test
    fun `rule tokens map to kinds`() {
        assertEquals(JapaneseDeinflector.Kind.V5, JapaneseDeinflector.kindOf("v5r"))
        assertEquals(JapaneseDeinflector.Kind.VS, JapaneseDeinflector.kindOf("vs-i"))
        assertEquals(JapaneseDeinflector.Kind.V1, JapaneseDeinflector.kindOf("v1-s"))
        assertEquals(null, JapaneseDeinflector.kindOf("n"))
    }

    @Test
    fun `latin words are left alone`() {
        assertEquals(listOf("running"), JapaneseDeinflector.candidates("running").map { it.text })
    }
}
