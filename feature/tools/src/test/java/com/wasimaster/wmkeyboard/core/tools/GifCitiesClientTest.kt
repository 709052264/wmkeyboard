package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifCitiesClientTest {

    @Test
    fun `archive path becomes a raw Wayback file url`() {
        assertEquals(
            "https://web.archive.org/web/20091020083540im_/http://geocities.com/Heartland/cat.gif",
            GifCitiesClient.fileUrl("https://web.archive.org/", "20091020083540/http://geocities.com/Heartland/cat.gif"),
        )
        assertNull(GifCitiesClient.fileUrl("https://web.archive.org", "http://geocities.com/cat.gif"))
        assertNull(GifCitiesClient.fileUrl("https://web.archive.org", "nopath"))
    }

    @Test
    fun `results parse and tiny gifs are dropped`() {
        val body = """[
            {"url_text":"heartland meadows cat","gif":"20091020083540/http://geocities.com/a/cat.gif",
             "checksum":"OZYR","height":55,"width":100,"page":"https://web.archive.org/web/x"},
            {"url_text":"bullet","gif":"20040208063604/http://geocities.com/b/dot.gif",
             "checksum":"V7H4","height":21,"width":18}
        ]"""
        val items = GifCitiesClient.parse(body)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("gifcities_OZYR", item.id)
        assertEquals(GifSource.GIFCITIES, item.source)
        assertEquals("heartland meadows cat", item.title)
        assertEquals(item.previewUrl, item.fullUrl)
        assertTrue(item.fullUrl.contains("/web/20091020083540im_/http://geocities.com/a/cat.gif"))
        assertEquals(100f / 55f, item.aspectRatio, 0.001f)
    }

    @Test
    fun `a blank query answers null, which parses to nothing`() {
        assertTrue(GifCitiesClient.parse("null").isEmpty())
        assertTrue(GifCitiesClient.parse("not json").isEmpty())
    }
}
