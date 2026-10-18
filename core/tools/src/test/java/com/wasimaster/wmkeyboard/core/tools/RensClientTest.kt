package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rens (#727) has no API, so the client reads the page the instance answers
 * with. The pages here are cut down from ren.maid.zone's answers, 2026-10-19.
 */
class RensClientTest {

    @Test
    fun `the OCR page's text is read and unescaped`() {
        val page = """<div class="output"><span>Extracted text:</span><div class="scroll-inside"><pre>Good morning &amp; welcome
&lt;Open&gt; the door &#39;now&#39;</pre></div></div><br><hr><br><h2>OCR</h2>"""
        assertEquals("Good morning & welcome\n<Open> the door 'now'", RensClient.parseText(page))
    }

    @Test
    fun `a page without a result is no text, not empty text`() {
        assertNull(RensClient.parseText("""<h2>OCR</h2><form method="post" action="/?ocr">"""))
        assertNull(RensClient.parseText("""<span>Extracted text:</span><div class="scroll-inside"><pre>   </pre></div>"""))
    }

    @Test
    fun `the translated picture is found and fetched from the same instance only`() {
        val page = """<div class="output"><span>Translated image:</span><img id="translated_image" """ +
            """src="/proxy?url=https%3A%2F%2Flens.usercontent.google.com%2Fimage%3Fvsrid%3Dabc%26sl%3Dauto%26tl%3Des"></div>"""
        val src = RensClient.parseImage(page)!!
        assertEquals("/proxy?url=https%3A%2F%2Flens.usercontent.google.com%2Fimage%3Fvsrid%3Dabc%26sl%3Dauto%26tl%3Des", src)
        assertEquals("https://ren.example/proxy?url=https%3A%2F%2Flens.usercontent.google.com%2Fimage%3Fvsrid%3Dabc%26sl%3Dauto%26tl%3Des",
            RensClient.imageUrl("https://ren.example", src))
        assertNull(RensClient.imageUrl("https://ren.example", "https://elsewhere.example/x.jpg"))
        assertNull(RensClient.parseImage("<h2>Translate</h2>"))
    }

    @Test
    fun `entities outside unicode are left as written`() {
        assertEquals("&#x110000;", RensClient.unescape("&#x110000;"))
        assertEquals("é", RensClient.unescape("&#233;"))
    }
}
