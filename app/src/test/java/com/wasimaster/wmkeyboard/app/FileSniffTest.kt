package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising a file by its contents, for every format the app imports
 * somewhere. As in [FileImportSniffTest], the negative cases matter as much
 * as the positive ones: anything matched here is offered for import.
 */
class FileSniffTest {

    private fun bytes(vararg values: Int) = ByteArray(FileSniff.HEAD_BYTES).also { out ->
        values.forEachIndexed { i, v -> out[i] = v.toByte() }
    }

    private fun ascii(text: String, length: Int = FileSniff.HEAD_BYTES) =
        text.toByteArray(Charsets.ISO_8859_1).copyOf(length)

    // ---- binary headers ----

    @Test
    fun `fonts are told by their sfnt tag`() {
        assertTrue(FileSniff.isFont(bytes(0x00, 0x01, 0x00, 0x00)))
        assertTrue(FileSniff.isFont(ascii("OTTO")))
        assertTrue(FileSniff.isFont(ascii("ttcf")))
        // A web font is not one Android can load.
        assertFalse(FileSniff.isFont(ascii("wOFF")))
        assertFalse(FileSniff.isFont(ascii("PK\u0003\u0004")))
    }

    @Test
    fun `a font with a colour table is an emoji font`() {
        // sfnt version, numTables = 2, then the directory from byte 12.
        val head = ascii("\u0000\u0001\u0000\u0000\u0000\u0002\u0000\u0000\u0000\u0000\u0000\u0000" +
            "cmap" + "\u0000".repeat(12) + "CBDT" + "\u0000".repeat(12))
        assertTrue(FileSniff.isColourFont(head))
        val plain = ascii("\u0000\u0001\u0000\u0000\u0000\u0001\u0000\u0000\u0000\u0000\u0000\u0000cmap")
        assertFalse(FileSniff.isColourFont(plain))
    }

    @Test
    fun `sounds are told by their header, and a bare frame sync only by name`() {
        assertTrue(FileSniff.isSound(ascii("ID3\u0004"), "click"))
        assertTrue(FileSniff.isSound(ascii("OggS"), ""))
        assertTrue(FileSniff.isSound(ascii("RIFF\u0000\u0000\u0000\u0000WAVE"), ""))
        assertTrue(FileSniff.isSound(bytes(0xFF, 0xFB), "tap.mp3"))
        assertFalse(FileSniff.isSound(bytes(0xFF, 0xFB), "data.bin"))
        assertFalse(FileSniff.isSound(ascii("RIFF\u0000\u0000\u0000\u0000AVI "), ""))
    }

    @Test
    fun `Emotic's archives are an SQLite database or a tar`() {
        assertTrue(FileSniff.isSqlite(ascii("SQLite format 3\u0000")))
        val tar = ByteArray(FileSniff.HEAD_BYTES).also { "ustar".toByteArray().copyInto(it, 257) }
        assertTrue(FileSniff.isTar(tar))
        assertFalse(FileSniff.isTar(ascii("hello")))
    }

    // ---- text ----

    @Test
    fun `emoji keyword rows are a pack, a stray emoji is not`() {
        val pack = "🎂\tcake,birthday\n😀\tgrin,happy\tgrinning face\n🐱\tcat\n"
        assertTrue(FileSniff.looksLikeEmojiKeywords(pack))
        assertFalse(FileSniff.looksLikeEmojiKeywords("🎂 party tonight\nbring cake\n"))
        assertFalse(FileSniff.looksLikeEmojiKeywords("name\tvalue\na\t1\nb\t2\n"))
        assertTrue(WMFileTypes.textKindFor(pack, "file_42") is WMFileTypes.Opened.EmojiKeywords)
    }

    @Test
    fun `a FlorisBoard layout needs its key types or codes`() {
        assertTrue(FileSniff.looksLikeFlorisLayout("""[[{"${'$'}":"text_key","code":113,"label":"q"}]]"""))
        assertTrue(FileSniff.looksLikeFlorisLayout("""[[{"code":113,"label":"q"}]]"""))
        assertFalse(FileSniff.looksLikeFlorisLayout("""[[1,2],[3,4]]"""))
        assertFalse(FileSniff.looksLikeFlorisLayout("""{"shopping":["milk"]}"""))
    }

    @Test
    fun `input method tables are told by their own markers`() {
        assertTrue(FileSniff.looksLikeInputMethod("%gen_inp\n%chardef begin\na 日\n%chardef end", "x"))
        assertTrue(FileSniff.looksLikeInputMethod("(input-method t foo)", "x"))
        assertTrue(FileSniff.looksLikeInputMethod("", "luna_pinyin.dict.yaml"))
        assertTrue(FileSniff.looksLikeInputMethod("schema:\n  schema_id: x\nengine:\n", "x"))
        assertFalse(FileSniff.looksLikeInputMethod("name: QWERTY\nrows:\n", "x.yaml"))
    }

    @Test
    fun `a HeliBoard theme is recognised without a name`() {
        val heli = """{"name":"Midnight","moreColors":0,"colors":{"background":[-16777216,false]}}"""
        assertTrue(WMFileTypes.textKindFor(heli, "") is WMFileTypes.Opened.HeliThemeFile)
    }

    @Test
    fun `an AOSP combined list is a dictionary`() {
        val combined = "dictionary=main:de,locale=de\n word=der,f=222\n word=die,f=220\n"
        val opened = WMFileTypes.textKindFor(combined, "main_de.combined")
        assertTrue(opened is WMFileTypes.Opened.Dictionaries)
        assertEquals(2, (opened as WMFileTypes.Opened.Dictionaries).found.sumOf { it.words.size })
    }

    @Test
    fun `a double pinyin scheme names itself`() {
        val scheme = "方案名称=自然码\nzh=v\nch=i\nsh=u\nai=l\n"
        val opened = WMFileTypes.textKindFor(scheme, "scheme.ini")
        assertTrue(opened is WMFileTypes.Opened.DoublePinyinScheme)
        assertEquals("自然码", (opened as WMFileTypes.Opened.DoublePinyinScheme).name)
    }

    @Test
    fun `the kaomoji export is told by its tag, a plain list is not`() {
        val export = """{"format":"wmkeyboard-kaomoji","version":1,"items":[{"text":"(^_^)"}]}"""
        assertTrue(WMFileTypes.textKindFor(export, "kaomoji.json") is WMFileTypes.Opened.KaomojiFile)
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("(^_^)\n(T_T)\n", "faces.txt"))
    }
}
