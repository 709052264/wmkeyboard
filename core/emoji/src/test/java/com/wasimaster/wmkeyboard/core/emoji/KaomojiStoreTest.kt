package com.wasimaster.wmkeyboard.core.emoji

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KaomojiStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    @Test
    fun `seed merges shared moods and keeps both families`() {
        val seed = KaomojiLibrary.builtIn()
        assertEquals(1, seed.tags.count { it == "Happy" })
        assertTrue("Playful" in seed.tags)
        assertTrue(seed.items.any { it.text == ":-)" && it.kind == KaomojiKind.EMOTICON && it.hasTag("Happy") })
        assertTrue(seed.items.any { it.text == "(◕‿◕)" && it.kind == KaomojiKind.KAOMOJI })
        assertEquals(seed.items.size, seed.items.map { it.text }.toSet().size)
        // The Emoticons tab, split off, lists only moods that hold an emoticon.
        assertFalse("Table flip" in seed.visibleTags(KaomojiKind.EMOTICON))
    }

    @Test
    fun `untouched seed writes nothing`() {
        val file = File(tmp.root, KaomojiStore.FILE_PATH)
        val store = KaomojiStore(file)
        store.save()
        assertFalse(file.exists())
        store.recordUse("(◕‿◕)", now = t0)
        store.save()
        assertTrue(file.exists())
        val again = KaomojiStore(file)
        assertFalse(again.isSeed)
        assertEquals("(◕‿◕)", again.library().recents(null).first().text)
    }

    @Test
    fun `add dedupes by text and guesses kind`() {
        val store = KaomojiStore(null)
        val a = store.add("  (ﾉ◕ヮ◕)ﾉ  ", listOf("Mine"), now = t0)!!
        assertEquals("(ﾉ◕ヮ◕)ﾉ", a.text)
        assertEquals(KaomojiKind.KAOMOJI, a.kind)
        val b = store.add("(ﾉ◕ヮ◕)ﾉ", listOf("mine", "Party"), now = t0)!!
        assertEquals(a.id, b.id)
        assertEquals(listOf("Mine", "Party"), b.tags)
        assertEquals(KaomojiKind.EMOTICON, store.add(":^)", now = t0)!!.kind)
        assertNull(store.add("   "))
        assertTrue("Mine" in store.library().tags)
    }

    @Test
    fun `update onto another face folds them together`() {
        val store = KaomojiStore(null)
        val a = store.add("AAA_face", listOf("x"), now = t0)!!
        val b = store.add("BBB_face", listOf("y"), now = t0)!!
        store.setFavourite(a.id, true)
        val merged = store.update(a.id, "BBB_face", listOf("z"), KaomojiKind.KAOMOJI)!!
        assertEquals(b.id, merged.id)
        assertTrue(merged.favourite)
        assertEquals(listOf("y", "z"), merged.tags)
        assertNull(store.item(a.id))
    }

    @Test
    fun `reorder of a view keeps outsiders in place`() {
        val store = KaomojiStore(null)
        store.merge(listOf(KaomojiImport("a", listOf("t")), KaomojiImport("b"), KaomojiImport("c", listOf("t")), KaomojiImport("d")), replace = true)
        val ids = store.library().items.associate { it.text to it.id }
        store.reorder(listOf(ids.getValue("c"), ids.getValue("a")))
        assertEquals(listOf("c", "b", "a", "d"), store.library().items.map { it.text })
    }

    @Test
    fun `tag rename merges and remove can take the faces`() {
        val store = KaomojiStore(null)
        store.merge(listOf(KaomojiImport("a", listOf("Old")), KaomojiImport("b", listOf("New"))), replace = true)
        assertTrue(store.renameTag("old", "NEW"))
        assertEquals(listOf("New"), store.library().tags)
        assertTrue(store.library().items.all { it.tags == listOf("New") })
        store.removeTag("New", deleteItems = true)
        assertTrue(store.library().items.isEmpty())
    }

    @Test
    fun `search ranks tag hits ahead of text hits`() {
        val store = KaomojiStore(null)
        store.merge(listOf(KaomojiImport("cat face catz"), KaomojiImport("x", listOf("Cat"))), replace = true)
        assertEquals(listOf("x", "cat face catz"), store.library().search("cat", null).map { it.text })
        assertEquals(listOf("x"), store.library().suggestionsFor("CAT").map { it.text })
        assertTrue(store.library().suggestionsFor("ca").isEmpty())
    }

    @Test
    fun `reset returns to the seed and deletes the file`() {
        val file = File(tmp.root, KaomojiStore.FILE_PATH)
        val store = KaomojiStore(file)
        store.add("mine", now = t0)
        store.save()
        assertTrue(file.exists())
        store.resetToDefaults()
        assertFalse(file.exists())
        assertTrue(store.isSeed)
        assertEquals(KaomojiLibrary.builtIn().items.size, store.size())
    }

    @Test
    fun `reload picks up another process's write`() {
        val file = File(tmp.root, KaomojiStore.FILE_PATH)
        val keyboard = KaomojiStore(file)
        val settings = KaomojiStore(file)
        settings.add("from settings", now = t0)
        settings.save()
        assertTrue(keyboard.reloadIfChanged())
        assertNotNull(keyboard.library().items.firstOrNull { it.text == "from settings" })
        assertFalse(keyboard.reloadIfChanged())
    }

    @Test
    fun `files round trip and plain text imports`() {
        val store = KaomojiStore(null)
        store.merge(listOf(KaomojiImport("(^_^)", listOf("Happy"), favourite = true)), replace = true)
        val decoded = KaomojiFiles.decode(KaomojiFiles.encode(store.library()))!!
        assertEquals("(^_^)", decoded.items.single().text)
        assertTrue(decoded.items.single().favourite)
        assertEquals(listOf("Happy"), decoded.tags)

        val text = KaomojiFiles.decode("(¬‿¬)\tsly, cool\n\n¯\\_(ツ)_/¯\n")!!
        assertEquals(listOf("sly", "cool"), text.items[0].tags)
        assertEquals("¯\\_(ツ)_/¯", text.items[1].text)
        assertEquals(listOf("a", "b"), KaomojiFiles.decode("[\"a\", \"b\"]")!!.items.map { it.text })
    }

    @Test
    fun `tar reader yields files and skips the rest`() {
        val tar = ByteArrayOutputStream()
        writeEntry(tar, "emoticdb.sqlite", "db".toByteArray())
        writeEntry(tar, "media/images/a.png", ByteArray(700) { 7 })
        tar.write(ByteArray(1024))
        val seen = LinkedHashMap<String, Int>()
        TarStream(ByteArrayInputStream(tar.toByteArray())).forEach { name, _, body ->
            seen[name] = body.readBytes().size
        }
        assertEquals(mapOf("emoticdb.sqlite" to 2, "media/images/a.png" to 700), seen)
    }

    @Test
    fun `unsafe names are refused`() {
        assertNull(EmoticArchive.safeName(".."))
        assertEquals("evil.png", EmoticArchive.safeName("../../evil.png"))
        assertNull(EmoticArchive.safeName("a\u0000b"))
    }

    private fun writeEntry(out: ByteArrayOutputStream, name: String, data: ByteArray) {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000644".toByteArray().copyInto(header, 100)
        String.format("%011o", data.size).toByteArray().copyInto(header, 124)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        out.write(header)
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }
}
