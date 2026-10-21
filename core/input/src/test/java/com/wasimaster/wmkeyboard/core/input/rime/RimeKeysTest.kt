package com.wasimaster.wmkeyboard.core.input.rime

import org.junit.Assert.assertEquals
import org.junit.Test

class RimeKeysTest {

    @Test
    fun `pinyin passes through lowercased, apostrophe and all`() {
        val input = RimeKeys.translate(RimeMethod.PINYIN, "Xi'an")
        assertEquals("xi'an", input.input)
        assertEquals(2, input.bufferChars(2))
        assertEquals(5, input.bufferChars(5))
    }

    @Test
    fun `bopomofo goes onto the Dachen keys RIME's schema reads`() {
        // ㄋㄧˇㄏㄠˇ, 你好
        assertEquals("su3cl3", RimeKeys.translate(RimeMethod.ZHUYIN, "ㄋㄧˇㄏㄠˇ").input)
    }

    @Test
    fun `cangjie radicals become their letters`() {
        assertEquals("onf", RimeKeys.translate(RimeMethod.CANGJIE, "人弓火").input)
        assertEquals("onf", RimeKeys.translate(RimeMethod.CANGJIE, "onf").input)
    }

    @Test
    fun `strokes become h s p n z, and the wildcard is dropped`() {
        val input = RimeKeys.translate(RimeMethod.STROKE, "一丨*丿")
        assertEquals("hsp", input.input)
        // The dropped wildcard rides with the stroke before it.
        assertEquals(3, input.bufferChars(2))
        assertEquals(4, input.bufferChars(3))
    }

    @Test
    fun `jyutping tone digits are dropped and counted with their syllable`() {
        val input = RimeKeys.translate(RimeMethod.JYUTPING, "nei5hou2")
        assertEquals("neihou", input.input)
        // Picking the candidate for `nei` consumes `nei5`.
        assertEquals(4, input.bufferChars(3))
        assertEquals(8, input.bufferChars(6))
    }

    @Test
    fun `a picked T9 syllable is set off from the digits after it`() {
        // xi picked from the column, then 26 typed: RIME reads xi26 as xiao.
        val input = RimeKeys.translate(RimeMethod.T9_PINYIN, "xi26")
        assertEquals("xi'26", input.input)
        // The added mark goes with the syllable before it.
        assertEquals(2, input.bufferChars(2))
        assertEquals(2, input.bufferChars(3))
        assertEquals(4, input.bufferChars(5))
        // Already separated, or digits only: nothing added.
        assertEquals("xi'an", RimeKeys.translate(RimeMethod.T9_PINYIN, "xi'an").input)
        assertEquals("xi'26", RimeKeys.translate(RimeMethod.T9_PINYIN, "xi'26").input)
        assertEquals("9426", RimeKeys.translate(RimeMethod.T9_PINYIN, "9426").input)
        assertEquals("xi'an'9", RimeKeys.translate(RimeMethod.T9_PINYIN, "xi'an9").input)
    }

    @Test
    fun `bufferChars clamps outside the input`() {
        val input = RimeKeys.translate(RimeMethod.PINYIN, "ni")
        assertEquals(0, input.bufferChars(-1))
        assertEquals(2, input.bufferChars(10))
    }
}
