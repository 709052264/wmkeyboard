package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docs search answer, as the keyboard's AI tool reads it. */
class WmDocsTest {

    private val body = """
        {"query":"sougou","results":[
          {"title":"Chinese, Japanese & Korean","heading":"Dictionaries made for fcitx5, Rime or Sogou",
           "section":"Languages","url":"https://wmkeyboard.pages.dev/languages/cjk/#dictionaries-made-for-fcitx5-rime-or-sogou",
           "markdown":"https://wmkeyboard.pages.dev/languages/cjk.md","score":98.5,"text":"Take the `.dict.yaml`."},
          {"title":"No text","heading":"","url":"https://wmkeyboard.pages.dev/x/","text":""},
          "not an object"
        ]}
    """.trimIndent()

    @Test
    fun `hits keep title, heading, link and text, and malformed ones are dropped`() {
        val hits = WmDocs.parse(body)
        assertEquals(1, hits.size)
        assertEquals("Chinese, Japanese & Korean › Dictionaries made for fcitx5, Rime or Sogou", hits[0].label)
        assertEquals("Take the `.dict.yaml`.", hits[0].text)
    }

    @Test
    fun `an answer that is not the search shape is no hits, not a crash`() {
        assertEquals(emptyList<WmDocs.Hit>(), WmDocs.parse("<html>502</html>"))
        assertEquals(emptyList<WmDocs.Hit>(), WmDocs.parse("""{"error":"Pass the question as ?q="}"""))
    }

    @Test
    fun `the model gets each section under its link, and is told to cite it`() {
        val text = WmDocs.format("sougou", WmDocs.parse(body))
        assertTrue(text.contains("https://wmkeyboard.pages.dev/languages/cjk/#dictionaries-made-for-fcitx5-rime-or-sogou"))
        assertTrue(text.contains("give the user the link"))
        assertTrue(WmDocs.format("zzz", emptyList()).contains("nothing matching \"zzz\""))
    }

    @Test
    fun `the docs tool is offered first and reads its query as the target`() {
        assertEquals(AiTools.WM_DOCS, AiTools.builtIns.first().name)
        assertEquals("rime", AiTools.targetOf(AiToolCall("", AiTools.WM_DOCS, """{"query":"rime"}""")))
    }
}
