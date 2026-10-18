package com.wasimaster.wmkeyboard.core.plugins.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LuaCompletion] at a caret marked `|`, which is no Lua operator in 5.2. */
class LuaCompletionTest {

    private fun complete(marked: String, explicit: Boolean = false, analysis: LuaAnalysis? = null): LuaCompletions? {
        val caret = marked.indexOf('|')
        require(caret >= 0) { "no caret in $marked" }
        val document = LuaDocument.of(marked.removeRange(caret, caret + 1))
        return LuaCompletion.at(document, caret, LuaHostShape(storage = false), explicit, analysis ?: document.analysis)
    }

    private fun labels(marked: String, explicit: Boolean = false): List<String> =
        complete(marked, explicit)?.items?.map { it.label }.orEmpty()

    private fun item(marked: String, label: String, explicit: Boolean = false): LuaCompletionItem =
        requireNotNull(complete(marked, explicit)?.items?.firstOrNull { it.label == label }) { "no $label at $marked: ${labels(marked, explicit)}" }

    @Test
    fun `a dot after wm lists exactly its members`() {
        assertEquals(
            setOf("api_version", "json", "log", "plugin_id", "plugin_version", "reminders", "storage", "ui"),
            labels("wm.|").toSet(),
        )
        assertEquals(setOf("set", "cancel", "list"), labels("wm.reminders.|").toSet())
        assertEquals(setOf("get", "set", "remove", "keys"), labels("wm.storage.|").toSet())
        assertEquals(setOf("decode", "encode"), labels("wm.json.|").toSet())
    }

    @Test
    fun `a partly typed member narrows the list and replaces the whole word`() {
        val found = requireNotNull(complete("string.fo|rm"))
        assertEquals(LuaSpan(7, 11), found.replace)
        assertEquals(listOf("format"), found.items.map { it.label })
        assertEquals("format()" to 7, found.items.single().insert to found.items.single().caret)
    }

    @Test
    fun `an API item says how it is called and where it is explained`() {
        val floor = item("print(math.fl|)", "floor")
        assertEquals("floor(x)", floor.detail)
        assertEquals("math.floor", floor.docPath)
        assertEquals("floor()" to 6, floor.insert to floor.caret)
        val clock = item("print(os.cl|)", "clock")
        assertEquals("clock()" to 7, clock.insert to clock.caret)
    }

    @Test
    fun `a local named like a library hides the library`() {
        assertEquals(emptyList<String>(), labels("local wm = {}\nwm.|"))
    }

    @Test
    fun `the on_event parameter has the fields of an event`() {
        assertTrue(labels("function on_event(ev)\n  if ev.| then end\nend").containsAll(listOf("type", "id", "value", "index")))
    }

    @Test
    fun `any other name offers the fields the file uses on it`() {
        assertEquals(
            setOf("count", "name", "total"),
            labels("local state = { count = 0, name = \"x\" }\nstate.total = 1\nprint(state.|)").toSet(),
        )
    }

    @Test
    fun `a colon offers string methods`() {
        val upper = item("local s = \"x\"\nprint(s:up|)", "upper")
        assertEquals("upper()" to 6, upper.insert to upper.caret)
    }

    @Test
    fun `inside a widget table, the fields not yet written`() {
        assertNull(complete("return ui.button { id = \"go\", |}"))
        assertEquals(setOf("text", "style", "enabled"), labels("return ui.button { id = \"go\", |}", explicit = true).toSet())
        val text = item("return ui.button { id = \"go\", t|}", "text")
        assertEquals("text = \"\"" to 8, text.insert to text.caret)
    }

    @Test
    fun `a container offers the widgets it can hold, and tabs only pages`() {
        val label = item("return ui.column { lab|", "label")
        assertEquals("ui.label { text = \"\" }", label.insert)
        assertEquals(label.insert.indexOf("\"\"") + 1, label.caret)
        assertEquals(listOf("page"), labels("return ui.tabs { id = \"t\", pa|"))
        assertFalse("page" in labels("return ui.column { |", explicit = true))
    }

    @Test
    fun `a widget constructor after ui dot opens in its first gap`() {
        val button = item("return ui.bu|", "button")
        assertEquals("button { id = \"\", text = \"\" }", button.insert)
        assertEquals(button.insert.indexOf("\"\"") + 1, button.caret)
        assertEquals("divider()", item("return ui.di|", "divider").insert)
    }

    @Test
    fun `a string knows what it is for`() {
        val styles = requireNotNull(complete("return ui.label { text = \"x\", style = \"|\" }"))
        assertEquals(listOf("title", "body", "caption"), styles.items.map { it.label })
        assertEquals(0, styles.replace.length)
        assertEquals(listOf("primary"), labels("return ui.button { id = \"b\", style = \"|\" }"))
        assertEquals(listOf("click"), labels("function on_event(e)\n  if e.type == \"cl|\" then end\nend"))
        assertEquals(
            listOf("go"),
            labels("function render() return ui.button { id = \"go\", text = \"x\" } end\nfunction on_event(e) if e.id == \"|\" then end end"),
        )
    }

    @Test
    fun `nothing inside a comment or a long string`() {
        assertNull(complete("-- wm.|"))
        assertNull(complete("x = [[ wm.| ]]"))
    }

    @Test
    fun `locals come before the API and keywords`() {
        val found = labels("local counter = 1\nlocal function compute() end\nco|")
        assertEquals(setOf("compute", "counter"), found.take(2).toSet())
        assertTrue(found.indexOf("collectgarbage") > 1)
    }

    @Test
    fun `an empty word opens nothing unless asked`() {
        assertNull(complete("x = |"))
        assertTrue("print" in labels("x = |", explicit = true))
    }

    @Test
    fun `a snippet takes the indentation of its line`() {
        val loop = item("function render()\n  fo|\nend", "for i = 1, n")
        assertEquals("for i = 1,  do\n    \n  end", loop.insert)
        assertEquals(11, loop.caret)
    }

    @Test
    fun `render and on_event are offered only while the file lacks them`() {
        val bare = labels("fun|")
        assertTrue(bare.containsAll(listOf("function render()", "function on_event(e)")))
        val withRender = labels("function render() return ui.label { text = \"x\" } end\nfun|")
        assertTrue("function on_event(e)" in withRender)
        assertFalse("function render()" in withRender)
        assertEquals(listOf("render"), labels("function r|"))
        assertEquals(listOf("function"), labels("local f|"))
    }

    @Test
    fun `an analysis a few keystrokes old still gives the locals`() {
        val old = requireNotNull(LuaDocument.of("local total = 1\nprint(to)").analysis)
        assertTrue("total" in complete("local total = 1\nprint(to|", analysis = old)?.items?.map { it.label }.orEmpty())
    }

    @Test
    fun `the list stops at the cap`() {
        val source = (1..80).joinToString("\n") { "local v$it = 1" } + "\nprint(v|)"
        assertEquals(LuaCompletion.MAX_ITEMS, labels(source).size)
    }
}
