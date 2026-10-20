package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.prediction.DivvunRuntime
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pins in [DivvunSpellerCatalog] are well formed and point at languages the keyboard types. */
class DivvunSpellerCatalogTest {

    @Test
    fun everyEntryIsPinned() {
        assertEquals(10, DivvunSpellerCatalog.entries.size)
        for (e in DivvunSpellerCatalog.entries) {
            assertTrue(e.languageId, e.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(e.url, e.url.startsWith("https://github.com/giellalt/lang-${e.giellaCode}/releases/download/speller-${e.giellaCode}/v${e.version}/"))
            assertTrue(e.url, e.url.endsWith("_noarch-mobile.pkt.tar.zst"))
            // A dev-latest release is rebuilt on every commit and loses the old file.
            assertTrue(e.url, "dev-latest" !in e.url)
            assertTrue(e.member, e.member.endsWith(".bhfst") && "-x-" !in e.member)
            assertTrue(e.licence.isNotEmpty())
            assertTrue(e.packedBytes in 1 until e.unpackedBytes)
        }
        assertEquals(
            DivvunSpellerCatalog.entries.size,
            DivvunSpellerCatalog.entries.map { it.languageId }.distinct().size,
        )
    }

    @Test
    fun catalogueAndPublishedNameKeyboardLanguages() {
        val known = LanguageRegistry.all.mapTo(HashSet()) { it.id }
        for (id in DivvunSpellerCatalog.entries.map { it.languageId } + DivvunSpellerCatalog.published) {
            assertTrue("no keyboard language $id", id in known)
        }
        for (e in DivvunSpellerCatalog.entries) assertTrue(e.languageId in DivvunSpellerCatalog.published)
        assertTrue(DivvunSpellerCatalog.offers("se"))
        assertTrue(!DivvunSpellerCatalog.offers("en"))
        assertNull(DivvunSpellerCatalog.forLanguage("ga"))
    }

    @Test
    fun theSpellerFileIsWhicheverKindIsThere() {
        val dir = Files.createTempDirectory("divvun").toFile()
        try {
            assertNull(DivvunRuntime.spellerFile(dir, "se"))
            assertEquals("", DivvunRuntime.signatureOf(dir, "se"))
            DivvunRuntime.zhfstFile(dir, "se").apply { parentFile!!.mkdirs() }.writeText("x")
            assertEquals("speller.zhfst", DivvunRuntime.spellerFile(dir, "se")!!.name)
            assertTrue(DivvunRuntime.signatureOf(dir, "se").isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
