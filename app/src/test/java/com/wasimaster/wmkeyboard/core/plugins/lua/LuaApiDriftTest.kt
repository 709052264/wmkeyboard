package com.wasimaster.wmkeyboard.core.plugins.lua

import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginBudget
import com.wasimaster.wmkeyboard.core.plugins.PluginForbiddenNames
import com.wasimaster.wmkeyboard.core.plugins.PluginHostApi
import com.wasimaster.wmkeyboard.core.plugins.PluginLimit
import com.wasimaster.wmkeyboard.core.plugins.PluginLog
import com.wasimaster.wmkeyboard.core.plugins.PluginPrelude
import com.wasimaster.wmkeyboard.core.plugins.PluginReminders
import com.wasimaster.wmkeyboard.core.plugins.PluginRepair
import com.wasimaster.wmkeyboard.core.plugins.PluginSandbox
import com.wasimaster.wmkeyboard.core.plugins.PluginStorage
import com.wasimaster.wmkeyboard.core.plugins.PluginUiCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import java.io.File

/**
 * Holds [LuaApi] to the sandbox a plugin really runs in, so the editor never
 * completes a name that is not there or misses one that is.
 */
class LuaApiDriftTest {

    private fun sandbox(): Globals {
        val budget = PluginBudget()
        val globals = PluginSandbox.create(budget) {}
        val plugin = InstalledPlugin(
            id = "com.example.drift",
            name = "Drift",
            version = "1",
            permissions = listOf("storage", "reminders"),
        )
        val api = PluginHostApi(plugin, PluginLog(null), PluginStorage(null), { _, _ -> }, { false }, reminders = PluginReminders(null))
        globals.set("wm", api.table())
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        return globals
    }

    private fun run(globals: Globals, source: String): LuaValue = PluginSandbox.compile(globals, source).call()

    private fun keysOf(globals: Globals, table: String): Set<String> {
        val joined = run(
            globals,
            "local names = {} for k in pairs($table) do names[#names + 1] = tostring(k) end return table.concat(names, ',')",
        ).tojstring()
        return if (joined.isEmpty()) emptySet() else joined.split(',').toSet()
    }

    @Test
    fun `every table in the sandbox holds exactly the names the API lists`() {
        val globals = sandbox()
        val tables = listOf("_G" to "", "string" to "string", "table" to "table", "math" to "math", "bit32" to "bit32",
            "os" to "os", "ui" to "ui", "wm" to "wm", "wm.json" to "wm.json", "wm.ui" to "wm.ui", "wm.storage" to "wm.storage",
            "wm.reminders" to "wm.reminders")
        val drift = tables.mapNotNull { (lua, parent) ->
            val actual = keysOf(globals, lua)
            val listed = LuaApi.children(parent).map { it.name }.toSet()
            val missing = actual - listed
            val extra = listed - actual
            if (missing.isEmpty() && extra.isEmpty()) null else "$lua: in the sandbox but not listed $missing, listed but not in the sandbox $extra"
        }
        assertEquals(emptyList<String>(), drift)
    }

    @Test
    fun `every name the API calls nil is nil`() {
        val globals = sandbox()
        assertEquals("the check itself works", LuaValue.TRUE, run(globals, "return string ~= nil"))
        for (name in LuaApi.nilled.keys) assertEquals(name, LuaValue.TRUE, run(globals, "return $name == nil"))
    }

    @Test
    fun `no name the API says never exists exists`() {
        val globals = sandbox()
        for (name in LuaApi.never) assertEquals("wm.$name", LuaValue.TRUE, run(globals, "return wm.$name == nil"))
    }

    @Test
    fun `the names that never exist are the ones the security test forbids`() {
        assertEquals(PluginForbiddenNames.WM.map { it.removePrefix("wm.") }.toSet(), LuaApi.never)
    }

    @Test
    fun `every ui constructor copies each field it lists`() {
        val globals = sandbox()
        for (shape in LuaApi.uiShapes.values) {
            for (field in shape.fields) {
                val copied = run(globals, "local w = ui.${shape.constructor}({ $field = 'sentinel' }) return w.$field == 'sentinel'")
                assertEquals("ui.${shape.constructor} drops $field", LuaValue.TRUE, copied)
            }
        }
    }

    @Test
    fun `every widget a constructor builds from its full field list draws with no repair`() {
        val globals = sandbox()
        val sample = mapOf(
            "id" to "'x'", "text" to "'t'", "mono" to "false", "insertable" to "true", "copyable" to "true",
            "enabled" to "true", "label" to "'l'", "checked" to "false", "placeholder" to "'p'", "height" to "8",
            "value" to "os.time()", "time" to "true", "step" to "15",
        )
        for (shape in LuaApi.uiShapes.values.filter { it.widgetType != null && it.constructor != "tabs" }) {
            val fields = shape.fields.joinToString(", ") { field ->
                val value = shape.values[field]?.first()?.let { "'$it'" } ?: sample.getValue(field)
                "$field = $value"
            }
            val ui = PluginUiCodec.fromLua(run(globals, "return ui.${shape.constructor}({ $fields })"))
            assertTrue("ui.${shape.constructor}: ${ui.repairs}", ui.repairs.isEmpty())
            assertEquals(1, ui.root.size)
        }
        val tabs = PluginUiCodec.fromLua(run(globals, "return ui.tabs { id = 't', ui.page { title = 'P', ui.label { text = 'x' } } }"))
        assertTrue(tabs.repairs.toString(), tabs.repairs.isEmpty())
    }

    @Test
    fun `the widget types are exactly the ones the renderer knows`() {
        val globals = sandbox()
        for (type in LuaApi.widgetTypes) {
            val ui = PluginUiCodec.fromLua(run(globals, "return { type = '$type' }"))
            assertTrue("$type is unknown to the renderer", PluginRepair.UNKNOWN_TYPE !in ui.repairCodes)
        }
        val bogus = PluginUiCodec.fromLua(run(globals, "return { type = 'sparkle' }"))
        assertTrue("the check itself works", PluginRepair.UNKNOWN_TYPE in bogus.repairCodes)
    }

    @Test
    fun `every name has a paragraph and every paragraph has a name`() {
        val paths = LuaApi.entries.map { it.path }.toSet()
        assertEquals("names with no paragraph", emptySet<String>(), paths - LuaApiDocs.paths)
        assertEquals("paragraphs for names that do not exist", emptySet<String>(), LuaApiDocs.paths - paths)
        assertTrue(LuaApi.entries.all { !LuaApiDocs.of(it.path).isNullOrBlank() })
    }

    @Test
    fun `the published API reference names nothing the API does not have`() {
        val reference = listOf(
            File("../docs/src/content/docs/plugins/api-reference.mdx"),
            File("docs/src/content/docs/plugins/api-reference.mdx"),
        ).firstOrNull { it.isFile } ?: error("cannot find api-reference.mdx from ${File("").absolutePath}")
        val named = Regex("""\b(wm(?:\.[a-z_]+)+|ui\.[a-z_]+|os\.[a-z_]+)""").findAll(reference.readText())
            .map { it.value.trimEnd('.') }
            .toSet()
        assertTrue("the reference scan found nothing", named.size > 10)
        val unknown = named.filterNot { name ->
            LuaApi.find(name) != null || (name.startsWith("wm.") && name.removePrefix("wm.") in LuaApi.never)
        }
        assertEquals("names in api-reference.mdx that LuaApi does not have", emptyList<String>(), unknown.sorted())
    }
}
