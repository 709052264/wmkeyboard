package com.wasimaster.wmkeyboard.core.tools.yomichan

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowStatFs

/**
 * Importing Yomichan / Yomitan dictionaries and looking words up in them
 * (#765). Every fixture is written here from the published format, never
 * taken from a real dictionary.
 */
@RunWith(RobolectricTestRunner::class)
class YomichanDictionariesTest {

    private lateinit var filesDir: File
    private lateinit var work: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        filesDir = File(context.filesDir, "yomitest").apply { deleteRecursively(); mkdirs() }
        work = File(context.cacheDir, "yomitest").apply { deleteRecursively(); mkdirs() }
        ShadowStatFs.registerStats(File(filesDir, YomichanDictionaries.DIR_NAME).apply { mkdirs() }, 1_000_000, 1_000_000, 1_000_000)
        YomichanDictionaries.attach(filesDir)
    }

    @After
    fun tearDown() {
        YomichanDictionaries.installed().forEach { YomichanDictionaries.delete(it.info.id) }
        YomichanDictionaries.releaseDatabases()
    }

    private fun zip(name: String, files: Map<String, String>): File {
        val zip = File(work, "$name.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((path, text) in files) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
            }
        }
        return zip
    }

    private val japanese = mapOf(
        "index.json" to """{"title":"Test JA","revision":"r1","format":3,"sourceLanguage":"ja","targetLanguage":"en","attribution":"CC BY-SA"}""",
        "term_bank_1.json" to """[
            ["食べる","たべる","v1","v1",100,["to eat"],1,""],
            ["高い","たかい","adj-i","adj-i",90,["high","expensive"],2,""],
            ["行く","いく","v5","v5k-s",80,[{"type":"structured-content","content":{"tag":"ul","content":[{"tag":"li","content":"to go"},{"tag":"li","content":["to ",{"tag":"ruby","content":["向",{"tag":"rt","content":"む"}]},"かう"]}]}}],3,""],
            ["食べ物","たべもの","n","",70,["food"],4,""]
        ]""",
        "term_meta_bank_1.json" to """[
            ["食べる","pitch",{"reading":"たべる","pitches":[{"position":2}]}],
            ["食べる","freq",{"reading":"たべる","frequency":{"value":512,"displayValue":"512㋕"}}],
            ["高い","freq",1200]
        ]""",
        "tag_bank_1.json" to """[["v1","partOfSpeech",0,"Ichidan verb",0],["adj-i","partOfSpeech",0,"adjective (keiyoushi)",0]]""",
    )

    @Test
    fun `a dictionary zip is recognised and imported`() {
        val file = zip("ja", japanese)
        assertTrue(ZipFile(file).use { YomichanDictionaries.isDictionary(it) })
        val info = YomichanDictionaries.import(filesDir, file)
        assertEquals("Test JA", info.title)
        assertEquals(4, info.terms)
        val installed = YomichanDictionaries.installed().single()
        assertTrue(installed.enabled)
        assertEquals("ja", installed.info.sourceLanguage)
    }

    @Test
    fun `an inflected word finds its entry with reading, pitch and frequency`() {
        YomichanDictionaries.import(filesDir, zip("ja", japanese))
        val entry = YomichanDictionaries.lookup("食べました").single()
        assertEquals("食べる", entry.word)
        assertEquals("たべる", entry.reading)
        assertEquals(listOf(2), entry.pitch)
        assertEquals("512㋕", entry.frequency)
        assertEquals("Test JA", entry.origin)
        assertEquals("Ichidan verb", entry.meanings.single().partOfSpeech)
        assertEquals("to eat", entry.meanings.single().definitions.single().text)
    }

    @Test
    fun `structured content flattens to lines`() {
        YomichanDictionaries.import(filesDir, zip("ja", japanese))
        val lines = YomichanDictionaries.lookup("行かなかった").single().meanings.single().definitions.map { it.text }
        assertEquals(listOf("• to go", "• to 向(む)かう"), lines)
    }

    @Test
    fun `a reading finds the word written in kanji`() {
        YomichanDictionaries.import(filesDir, zip("ja", japanese))
        assertEquals("高い", YomichanDictionaries.lookup("たかくない").single().word)
    }

    @Test
    fun `the longest known stretch wins`() {
        YomichanDictionaries.import(filesDir, zip("ja", japanese))
        val food = YomichanDictionaries.longestMatch("食べ物が好き", 0)!!
        assertEquals(0 to 3, food.start to food.end)
        assertEquals("食べ物", food.entries.first().word)
        val eat = YomichanDictionaries.longestMatch("パンを食べました。", 3)!!
        assertEquals(3 to 8, eat.start to eat.end)
        assertEquals("食べる", eat.entries.first().word)
        assertNull(YomichanDictionaries.longestMatch("パン", 0))
    }

    @Test
    fun `switching a dictionary off hides it and deleting removes it`() {
        val info = YomichanDictionaries.import(filesDir, zip("ja", japanese))
        YomichanDictionaries.setEnabled(info.id, false)
        assertFalse(YomichanDictionaries.hasEnabled)
        assertTrue(YomichanDictionaries.lookup("食べる").isEmpty())
        YomichanDictionaries.setEnabled(info.id, true)
        YomichanDictionaries.delete(info.id)
        assertTrue(YomichanDictionaries.installed().isEmpty())
        assertFalse(File(File(filesDir, YomichanDictionaries.DIR_NAME), info.id).exists())
    }

    @Test
    fun `order is kept and a latin dictionary is asked in any case`() {
        YomichanDictionaries.import(filesDir, zip("ja", japanese))
        val spanish = mapOf(
            "index.json" to """{"title":"Test ES","revision":"1","format":3,"sourceLanguage":"es","targetLanguage":"en"}""",
            "term_bank_1.json" to """[["Comer","","v","",0,["to eat"],1,""]]""",
        )
        val es = YomichanDictionaries.import(filesDir, zip("es", spanish))
        assertEquals(listOf("test-ja", "test-es"), YomichanDictionaries.installed().map { it.info.id })
        YomichanDictionaries.reorder(listOf(es.id))
        assertEquals(listOf("test-es", "test-ja"), YomichanDictionaries.installed().map { it.info.id })
        assertEquals("Comer", YomichanDictionaries.lookup("comer").single().word)
    }

    @Test
    fun `importing a newer revision replaces the old one`() {
        YomichanDictionaries.import(filesDir, zip("ja", japanese))
        val newer = japanese + ("index.json" to """{"title":"Test JA","revision":"r2","format":3}""")
        YomichanDictionaries.import(filesDir, zip("ja2", newer))
        assertEquals("r2", YomichanDictionaries.installed().single().info.revision)
    }

    @Test
    fun `format 1 spreads the glossary over the row`() {
        val old = mapOf(
            "index.json" to """{"title":"Old","version":1}""",
            "term_bank_1.json" to """[["猫","ねこ","n","",5,"cat","feline"]]""",
        )
        YomichanDictionaries.import(filesDir, zip("old", old))
        assertEquals(listOf("cat", "feline"), YomichanDictionaries.lookup("猫").single().meanings.single().definitions.map { it.text })
    }

    @Test
    fun `a zip without an index or term bank is not a dictionary`() {
        val other = zip("other", mapOf("manifest.json" to "{}"))
        assertFalse(ZipFile(other).use { YomichanDictionaries.isDictionary(it) })
    }

    @Test
    fun `frequency and pitch rows for another reading are ignored`() {
        val data: JsonElement = Json.parseToJsonElement("""{"reading":"ほか","frequency":3}""")
        assertNull(YomichanFormat.frequency(data, "た"))
        assertEquals("3", YomichanFormat.frequency(data, "ほか"))
        assertEquals("たべꜜる [2]", PitchNotation.format("たべる", 2))
        assertEquals("きゃく [0]", PitchNotation.format("きゃく", 0))
        assertEquals("きゃꜜく [1]", PitchNotation.format("きゃく", 1))
    }
}
