package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * Turns whatever a plugin's `render()` returned into a [RenderedUi].
 *
 * Runs on the plugin thread, inside the render budget, and treats its input as
 * hostile in the ordinary way — the script is a stranger's code and may return
 * nonsense, cycles, or a hundred megabytes of nested tables. Every limit here
 * exists because the alternative is the keyboard hanging or growing without
 * bound while drawing someone else's UI.
 *
 * Unlike the manifest codec, this one *does* repair rather than refuse. A
 * manifest describes what a plugin is allowed to do, so half-understanding it is
 * dangerous; a widget tree only describes what it wants drawn, so dropping the
 * one node that made no sense and reporting it beats showing the user an empty
 * panel because of a typo in a caption.
 */
object PluginUiCodec {

    /** Nodes in one tree. A panel that needs more than this is not a keyboard panel. */
    const val MAX_NODES = 256

    /** Nesting depth, which also breaks any cycle a script builds. */
    const val MAX_DEPTH = 12

    /** Characters in any one piece of text. */
    const val MAX_TEXT = 2048

    /** Characters across the whole tree. */
    const val MAX_TOTAL_TEXT = 64 * 1024

    const val MAX_TABS = 8

    private const val MAX_REPAIRS = 8
    private const val MAX_ID = 64
    private const val MAX_SPACER = 64
    private const val DEFAULT_STEP = 15
    private const val MAX_STEP = 60

    /** 0001-01-01 and 9999-12-31, UTC, in seconds. */
    private const val MIN_SECONDS = -62_135_596_800.0
    private const val MAX_SECONDS = 253_402_300_799.0

    fun fromLua(value: LuaValue): RenderedUi {
        val state = Walk()
        val root = state.children(value, depth = 0)
        return RenderedUi(root, state.repairs.toList(), state.codes.toList())
    }

    private class Walk {
        val repairs = ArrayList<PluginText>()

        /** Parallel to [repairs]: the kind of each, for the plugin editor. */
        val codes = ArrayList<PluginRepair>()
        var nodes = 0
        var totalText = 0

        fun repair(code: PluginRepair, message: PluginText) {
            if (repairs.size < MAX_REPAIRS && message !in repairs) {
                repairs.add(message)
                codes.add(code)
            }
        }

        /**
         * Reads the array part of [value] as a list of widgets. Accepts a single
         * widget table too, since `return ui.label{...}` is the obvious thing to
         * write and refusing it would be pedantry.
         */
        fun children(value: LuaValue, depth: Int): List<PluginWidget> {
            if (!value.istable()) return emptyList()
            val table = value.checktable()
            if (!table.get("type").isnil()) {
                return listOfNotNull(widget(table, depth))
            }
            val out = ArrayList<PluginWidget>()
            var i = 1
            while (i <= table.length()) {
                val child = table.get(i)
                if (child.istable()) {
                    widget(child.checktable(), depth)?.let { out.add(it) }
                } else if (!child.isnil()) {
                    repair(PluginRepair.NOT_A_WIDGET, PluginText.of(R.string.core_plugins_ui_repair_not_a_widget))
                }
                i++
            }
            return out
        }

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun widget(table: LuaTable, depth: Int): PluginWidget? {
            if (depth > MAX_DEPTH) {
                repair(PluginRepair.TOO_DEEP, PluginText.of(R.string.core_plugins_ui_repair_too_deep))
                return null
            }
            if (++nodes > MAX_NODES) {
                repair(PluginRepair.TOO_MANY_WIDGETS, PluginText.of(R.string.core_plugins_ui_repair_too_many_widgets, MAX_NODES))
                return null
            }
            return when (val type = table.get("type").optjstring("")) {
                "column" -> PluginWidget.Column(children(table.get("children"), depth + 1))
                "row" -> PluginWidget.Row(children(table.get("children"), depth + 1))
                "label" -> PluginWidget.Label(text(table, "text"), style(table))
                "output" -> PluginWidget.Output(
                    id = id(table),
                    text = text(table, "text"),
                    mono = table.get("mono").toboolean(),
                    insertable = table.get("insertable").optboolean(true),
                    copyable = table.get("copyable").optboolean(true),
                )

                "button" -> PluginWidget.Button(
                    id = id(table),
                    text = text(table, "text"),
                    primary = table.get("style").optjstring("") == "primary",
                    enabled = table.get("enabled").optboolean(true),
                )

                "toggle" -> PluginWidget.Toggle(
                    id = id(table),
                    label = text(table, "label"),
                    checked = table.get("checked").toboolean(),
                )

                "input" -> PluginWidget.Input(
                    id = id(table),
                    label = text(table, "label"),
                    placeholder = text(table, "placeholder"),
                )

                "date" -> PluginWidget.DatePicker(
                    id = id(table),
                    label = text(table, "label"),
                    value = seconds(table.get("value")),
                    time = table.get("time").toboolean(),
                    step = table.get("step").let { if (it.isnumber()) it.toint() else DEFAULT_STEP }
                        .coerceIn(1, MAX_STEP),
                )

                "spacer" -> PluginWidget.Spacer(
                    table.get("height").optint(8).coerceIn(0, MAX_SPACER),
                )

                "divider" -> PluginWidget.Divider
                "progress" -> PluginWidget.Progress
                "tabs" -> tabs(table, depth)
                else -> {
                    if (type.isEmpty()) {
                        repair(PluginRepair.NO_TYPE, PluginText.of(R.string.core_plugins_ui_repair_no_type))
                    } else {
                        repair(
                            PluginRepair.UNKNOWN_TYPE,
                            PluginText.of(R.string.core_plugins_ui_repair_unknown_type, type.take(24)),
                        )
                    }
                    null
                }
            }
        }

        private fun tabs(table: LuaTable, depth: Int): PluginWidget? {
            val pagesValue = table.get("pages")
            if (!pagesValue.istable()) {
                repair(PluginRepair.TABS_NO_PAGES, PluginText.of(R.string.core_plugins_ui_repair_tabs_no_pages))
                return null
            }
            val pagesTable = pagesValue.checktable()
            val pages = ArrayList<PluginWidget.Tabs.Page>()
            var i = 1
            while (i <= pagesTable.length() && pages.size < MAX_TABS) {
                val page = pagesTable.get(i)
                if (page.istable()) {
                    val pageTable = page.checktable()
                    pages.add(
                        PluginWidget.Tabs.Page(
                            // Left empty when the plugin gave no title: the
                            // panel names the tab after its position, in the
                            // language the user reads.
                            title = text(pageTable, "title"),
                            children = children(pageTable.get("children"), depth + 1),
                        ),
                    )
                }
                i++
            }
            if (pagesTable.length() > MAX_TABS) {
                repair(PluginRepair.TOO_MANY_TABS, PluginText.of(R.string.core_plugins_ui_repair_too_many_tabs, MAX_TABS))
            }
            if (pages.isEmpty()) {
                repair(PluginRepair.TABS_NO_PAGES, PluginText.of(R.string.core_plugins_ui_repair_tabs_no_pages))
                return null
            }
            return PluginWidget.Tabs(id(table), pages)
        }

        private fun style(table: LuaTable): PluginLabelStyle =
            when (table.get("style").optjstring("")) {
                "title" -> PluginLabelStyle.TITLE
                "caption" -> PluginLabelStyle.CAPTION
                else -> PluginLabelStyle.BODY
            }

        private fun id(table: LuaTable): String = table.get("id").optjstring("").take(MAX_ID)

        /**
         * A time in whole seconds, or null for none. Anything that is not a
         * number, or is outside the years 1 to 9999, is no date rather than a
         * date the formatter cannot draw.
         */
        private fun seconds(value: LuaValue): Long? {
            if (!value.isnumber()) return null
            val number = value.todouble()
            if (!number.isFinite() || number < MIN_SECONDS || number > MAX_SECONDS) return null
            return kotlin.math.floor(number).toLong()
        }

        /**
         * Reads one string field, capped twice: once so a single node cannot be
         * a novel, and once against the whole-tree budget so a thousand nodes
         * cannot each be almost a novel.
         */
        private fun text(table: LuaTable, key: String): String {
            val raw = table.get(key)
            val value = when {
                raw.isstring() -> raw.tojstring()
                raw.isnumber() -> raw.tojstring()
                raw.isboolean() -> raw.tojstring()
                else -> return ""
            }
            val remaining = (MAX_TOTAL_TEXT - totalText).coerceAtLeast(0)
            if (remaining == 0) {
                repair(PluginRepair.TEXT_BUDGET, PluginText.of(R.string.core_plugins_ui_repair_text_budget))
                return ""
            }
            val capped = value.take(minOf(MAX_TEXT, remaining))
            if (capped.length < value.length) {
                repair(PluginRepair.TEXT_SHORTENED, PluginText.of(R.string.core_plugins_ui_repair_text_shortened))
            }
            totalText += capped.length
            return capped
        }
    }
}
