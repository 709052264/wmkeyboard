package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Link detection and the file/folder/link clip kinds. */
class ClipKindsTest {

    // ---- link detection ----

    @Test fun recognisesBareUrls() {
        assertEquals("https://example.com/a?b=1", ClipLinks.asUrl("https://example.com/a?b=1"))
        assertEquals("http://example.com", ClipLinks.asUrl("  http://example.com  "))
    }

    @Test fun schemelessWwwGetsHttps() {
        assertEquals("https://www.example.com/x", ClipLinks.asUrl("www.example.com/x"))
    }

    @Test fun rejectsProseAndBareWords() {
        assertNull(ClipLinks.asUrl("see https://example.com for details"))
        assertNull(ClipLinks.asUrl("hello world"))
        assertNull(ClipLinks.asUrl(""))
        // File names and abbreviations are not addresses (#612).
        assertNull(ClipLinks.asUrl("notes.txt"))
        assertNull(ClipLinks.asUrl("main.py"))
        assertNull(ClipLinks.asUrl("Node.js"))
        assertNull(ClipLinks.asUrl("e.g."))
        assertNull(ClipLinks.asUrl("someone@example.com"))
    }

    @Test fun addressesWithoutASchemeAreLinks() {
        // #612: x.com, youtu.be and the like, with https:// put in front.
        assertEquals("https://example.com", ClipLinks.asUrl("example.com"))
        assertEquals("https://x.com", ClipLinks.asUrl("x.com"))
        assertEquals("https://youtu.be/Z7pkvfRl5S8", ClipLinks.asUrl("youtu.be/Z7pkvfRl5S8"))
        assertEquals("https://wmkeyboard.pages.dev/tools/", ClipLinks.asUrl("wmkeyboard.pages.dev/tools/"))
        assertEquals("https://m.youtube.com/watch?v=1", ClipLinks.asUrl("m.youtube.com/watch?v=1"))
    }

    @Test fun hostDropsWww() {
        assertEquals("example.com", ClipLinks.host("https://www.example.com/path"))
        assertEquals("", ClipLinks.host("not a url"))
    }

    // ---- kinds ----

    @Test fun copiedUrlBecomesLinkClip() {
        val store = ClipboardStore(null)
        val link = store.add("https://example.com", now = 1000)!!
        val plain = store.add("just text", now = 2000)!!
        assertEquals(ClipKind.LINK, link.kind)
        assertEquals(ClipKind.TEXT, plain.kind)
    }

    @Test fun htmlClipWinsOverLinkDetection() {
        val store = ClipboardStore(null)
        val item = store.addHtml("https://example.com", "<a href='x'>x</a>", now = 1000)!!
        assertEquals(ClipKind.HTML, item.kind)
    }

    @Test fun linksStayAvailableToSnippetClipVariable() {
        val store = ClipboardStore(null)
        store.add("https://example.com", now = 1000)
        assertEquals("https://example.com", store.latestText(now = 2000))
    }

    @Test fun fileAndFolderClips() {
        val store = ClipboardStore(null)
        val file = store.addUri(
            "content://docs/1", "report.pdf", "application/pdf",
            isDirectory = false, size = 2048, now = 1000,
        )!!
        val folder = store.addUri(
            "content://docs/tree", "Photos", "vnd.android.document/directory",
            isDirectory = true, size = 999, now = 2000,
        )!!
        assertEquals(ClipKind.FILE, file.kind)
        assertEquals(2048L, file.fileSize)
        assertEquals(ClipKind.FOLDER, folder.kind)
        // Folder sizes are meaningless — a provider's number is discarded.
        assertEquals(-1L, folder.fileSize)
        // Neither is textual, so neither can satisfy {clip}.
        assertNull(store.latestText(now = 3000))
    }

    @Test fun recopyingAFileMovesItToTheTop() {
        val store = ClipboardStore(null)
        store.addUri("content://docs/1", "a.pdf", "application/pdf", false, 1, now = 1000)
        store.addUri("content://docs/2", "b.pdf", "application/pdf", false, 1, now = 2000)
        store.addUri("content://docs/1", "a.pdf", "application/pdf", false, 1, now = 3000)
        val names = store.items(now = 4000).map { it.fileName }
        assertEquals(listOf("a.pdf", "b.pdf"), names)
    }

    @Test fun searchMatchesFileNamesButNotBinaryKinds() {
        // search() prunes against the real clock, so these must not look expired.
        val store = ClipboardStore(null)
        val now = System.currentTimeMillis()
        store.addUri("content://docs/1", "quarterly report.pdf", "application/pdf", false, 1, now = now)
        store.add("unrelated", now = now)
        assertEquals(listOf("quarterly report.pdf"), store.search("REPORT").map { it.fileName })
    }

    // ---- link previews ----

    @Test fun previewIsAttachedAndClearable() {
        val store = ClipboardStore(null)
        val link = store.add("https://example.com", now = 1000)!!
        assertEquals(listOf(link.id), store.linksNeedingPreview(now = 1500).map { it.id })

        store.setLinkPreview(link.id, LinkPreview(title = "Example Domain"))
        assertEquals("Example Domain", store.items(now = 1500).first().linkPreview?.title)
        assertEquals(emptyList<Long>(), store.linksNeedingPreview(now = 1500).map { it.id })

        store.clearLinkPreviews()
        assertNull(store.items(now = 1500).first().linkPreview)
        assertNotNull(store.linksNeedingPreview(now = 1500).firstOrNull())
    }
}
