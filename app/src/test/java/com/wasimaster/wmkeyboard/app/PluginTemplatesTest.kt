package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDiagnostics
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDocument
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaHostShape
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaSeverity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The templates a new plugin starts from, held to the demo plugins they copy. */
class PluginTemplatesTest {

    private val directory: File = listOf(
        File("src/main/assets/${PluginTemplate.ASSET_DIR}"),
        File("app/src/main/assets/${PluginTemplate.ASSET_DIR}"),
    ).first { it.isDirectory }

    private fun script(template: PluginTemplate): String =
        template.asset?.let { File(directory, it).readText() } ?: BLANK_PLUGIN_SCRIPT

    private fun demo(file: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("plugins/$file")) { "no demo $file" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `every template file is its demo plugin, character for character`() {
        for (template in PluginTemplate.entries) {
            val file = template.asset ?: continue
            assertEquals(file, demo(file), script(template))
        }
    }

    @Test
    fun `every file in the templates folder is listed`() {
        assertEquals(PluginTemplate.entries.mapNotNull { it.asset }.toSet(), directory.list().orEmpty().toSet())
    }

    @Test
    fun `a template declares each permission exactly when it uses it`() {
        for (template in PluginTemplate.entries) {
            assertEquals(template.name, "wm.storage" in script(template), PluginPermission.Storage in template.permissions)
            assertEquals(template.name, "wm.reminders" in script(template), PluginPermission.Reminders in template.permissions)
        }
    }

    @Test
    fun `no template draws an error or a warning from the checks`() {
        val problems = PluginTemplate.entries.flatMap { template ->
            val host = LuaHostShape(
                storage = PluginPermission.Storage in template.permissions,
                reminders = PluginPermission.Reminders in template.permissions,
            )
            LuaDiagnostics.of(LuaDocument.of(script(template)), host)
                .filter { it.severity != LuaSeverity.INFO }
                .map { "${template.name} ${it.code} ${it.arg1.orEmpty()}" }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
