package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUiCodecTest {

    /**
     * True when one of the repairs is [res]. The assertions name the resource
     * rather than the English, so rewording a message does not fail a test that
     * is about what the codec dropped.
     */
    private fun List<PluginText>.reports(res: Int): Boolean =
        any { it is PluginText.Resource && it.textRes == res }

    /** Runs a render body through the real prelude and the real sandbox. */
    private fun render(body: String): RenderedUi {
        val budget = PluginBudget()
        val globals = PluginSandbox.create(budget) { }
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        val value = PluginSandbox.compile(globals, "return (function() $body end)()").call()
        return PluginUiCodec.fromLua(value)
    }

    // ---- the vocabulary ---------------------------------------------------

    @Test
    fun `a date keeps whole seconds, its time switch and a step in range`() {
        val date = render("return ui.date { id = 'due', label = 'Due', value = 1790000000.7, time = true, step = 500 }")
            .root.single() as PluginWidget.DatePicker
        assertEquals(PluginWidget.DatePicker("due", "Due", 1_790_000_000L, time = true, step = 60), date)

        val none = render("return ui.date { id = 'due', value = 'tomorrow' }").root.single() as PluginWidget.DatePicker
        assertEquals(null, none.value)
        assertEquals(15, none.step)
        assertFalse(none.time)
        assertEquals(null, (render("return ui.date { id = 'd', value = 1e300 }").root.single() as PluginWidget.DatePicker).value)
    }

    @Test
    fun `every widget the prelude offers survives the round trip`() {
        val ui = render(
            """
            return ui.column {
              ui.label { text = "Title", style = "title" },
              ui.input { id = "msg", label = "Message", placeholder = "type here" },
              ui.row {
                ui.button { id = "go", text = "Encode", style = "primary" },
                ui.button { id = "stop", text = "Off", enabled = false },
              },
              ui.toggle { id = "caps", label = "Uppercase", checked = true },
              ui.output { id = "out", text = "result", mono = true },
              ui.divider(),
              ui.spacer { height = 12 },
              ui.progress(),
            }
            """.trimIndent(),
        )

        val column = ui.root.single() as PluginWidget.Column
        val label = column.children[0] as PluginWidget.Label
        assertEquals("Title", label.text)
        assertEquals(PluginLabelStyle.TITLE, label.style)

        val input = column.children[1] as PluginWidget.Input
        assertEquals("msg", input.id)
        assertEquals("type here", input.placeholder)

        val row = column.children[2] as PluginWidget.Row
        val primary = row.children[0] as PluginWidget.Button
        assertTrue(primary.primary)
        assertTrue(primary.enabled)
        assertFalse((row.children[1] as PluginWidget.Button).enabled)

        assertTrue((column.children[3] as PluginWidget.Toggle).checked)

        val output = column.children[4] as PluginWidget.Output
        assertEquals("result", output.text)
        assertTrue(output.mono)
        // Insert and Copy are on unless the plugin opts out: the host's Insert
        // button is the only route from a plugin to the user's text.
        assertTrue(output.insertable)
        assertTrue(output.copyable)

        assertEquals(PluginWidget.Divider, column.children[5])
        assertEquals(12, (column.children[6] as PluginWidget.Spacer).height)
        assertEquals(PluginWidget.Progress, column.children[7])
        assertTrue(ui.repairs.isEmpty())
    }

    @Test
    fun `tabs carry their pages`() {
        val ui = render(
            """
            return ui.tabs {
              id = "modes",
              ui.page { title = "Caesar", ui.label { text = "one" } },
              ui.page { title = "Vigenere", ui.label { text = "two" } },
            }
            """.trimIndent(),
        )
        val tabs = ui.root.single() as PluginWidget.Tabs
        assertEquals("modes", tabs.id)
        assertEquals(listOf("Caesar", "Vigenere"), tabs.pages.map { it.title })
        assertEquals("two", (tabs.pages[1].children.single() as PluginWidget.Label).text)
    }

    @Test
    fun `a single widget can be returned without wrapping it in a list`() {
        val ui = render("""return ui.label { text = "alone" }""")
        assertEquals("alone", (ui.root.single() as PluginWidget.Label).text)
    }

    @Test
    fun `input ids are collected for the host to keep buffers for`() {
        val ui = render(
            """
            return ui.column {
              ui.input { id = "a" },
              ui.tabs { id = "t", ui.page { title = "p", ui.input { id = "b" } } },
            }
            """.trimIndent(),
        )
        assertEquals(setOf("a", "b"), ui.inputIds())
    }

    // ---- hostile and broken trees -----------------------------------------

    @Test
    fun `an unknown widget type is dropped and reported`() {
        val ui = render("""return ui.column { { type = "webview", url = "http://evil" } }""")
        val column = ui.root.single() as PluginWidget.Column
        assertTrue(column.children.isEmpty())
        val repair = ui.repairs.single() as PluginText.Resource
        assertEquals(R.string.core_plugins_ui_repair_unknown_type, repair.textRes)
        assertEquals("webview", repair.arg1)
    }

    @Test
    fun `nothing at all renders as nothing at all`() {
        assertEquals(RenderedUi.EMPTY.root, render("return nil").root)
        assertEquals(RenderedUi.EMPTY.root, render("return 42").root)
    }

    @Test
    fun `a cyclic tree cannot recurse forever`() {
        val ui = render(
            """
            local node = { type = "column", children = {} }
            node.children[1] = node
            return node
            """.trimIndent(),
        )
        assertTrue(
            ui.repairs.reports(R.string.core_plugins_ui_repair_too_deep) ||
                ui.repairs.reports(R.string.core_plugins_ui_repair_too_many_widgets),
        )
    }

    @Test
    fun `a huge tree is truncated rather than drawn`() {
        val ui = render(
            """
            local kids = {}
            for i = 1, 5000 do kids[i] = ui.label { text = "row " .. i } end
            return ui.column(kids)
            """.trimIndent(),
        )
        val column = ui.root.single() as PluginWidget.Column
        assertTrue(column.children.size < PluginUiCodec.MAX_NODES)
        assertTrue(ui.repairs.reports(R.string.core_plugins_ui_repair_too_many_widgets))
    }

    @Test
    fun `one enormous string is shortened`() {
        val ui = render("""return ui.label { text = ("x"):rep(100000) }""")
        val label = ui.root.single() as PluginWidget.Label
        assertEquals(PluginUiCodec.MAX_TEXT, label.text.length)
        assertTrue(ui.repairs.reports(R.string.core_plugins_ui_repair_text_shortened))
    }

    @Test
    fun `many large strings are capped across the whole tree`() {
        val ui = render(
            """
            local kids = {}
            for i = 1, 200 do kids[i] = ui.label { text = ("y"):rep(2000) } end
            return ui.column(kids)
            """.trimIndent(),
        )
        val column = ui.root.single() as PluginWidget.Column
        val total = column.children.filterIsInstance<PluginWidget.Label>().sumOf { it.text.length }
        assertTrue("total text was $total", total <= PluginUiCodec.MAX_TOTAL_TEXT)
    }

    @Test
    fun `only the first few tabs are shown`() {
        val ui = render(
            """
            local pages = { id = "many" }
            for i = 1, 30 do pages[i] = ui.page { title = "t" .. i } end
            return ui.tabs(pages)
            """.trimIndent(),
        )
        val tabs = ui.root.single() as PluginWidget.Tabs
        assertEquals(PluginUiCodec.MAX_TABS, tabs.pages.size)
        assertTrue(ui.repairs.reports(R.string.core_plugins_ui_repair_too_many_tabs))
    }

    @Test
    fun `a tab strip with no pages is dropped`() {
        val ui = render("""return ui.tabs { id = "empty" }""")
        assertTrue(ui.root.isEmpty())
        assertTrue(ui.repairs.reports(R.string.core_plugins_ui_repair_tabs_no_pages))
    }

    @Test
    fun `a spacer cannot push the panel apart`() {
        val ui = render("return ui.spacer { height = 100000 }")
        assertTrue((ui.root.single() as PluginWidget.Spacer).height <= 64)
    }
}
