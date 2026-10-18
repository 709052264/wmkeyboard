package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDiagnosticCode
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaHostShape
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/** The Lua diagnostics and their words, held together. */
class LuaDiagnosticTextTest {

    private val strings: Map<String, String> by lazy {
        val file = listOf(
            File("src/main/res/values/strings_lua_editor.xml"),
            File("app/src/main/res/values/strings_lua_editor.xml"),
        ).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }

    private fun key(code: LuaDiagnosticCode) = "lua_diag_" + code.name.lowercase()

    @Test
    fun `every code has its own string, named after it`() {
        for (code in LuaDiagnosticCode.entries) {
            assertEquals(code.name, R.string::class.java.getField(key(code)).getInt(null), code.messageRes)
        }
        assertEquals(LuaDiagnosticCode.entries.map(::key).toSet(), strings.keys)
    }

    @Test
    fun `every string asks for exactly the arguments its code fills`() {
        for (code in LuaDiagnosticCode.entries) {
            val text = strings.getValue(key(code))
            val count = when (code) {
                in TWO_ARGUMENTS -> 2
                in NO_ARGUMENTS -> 0
                else -> 1
            }
            assertEquals("${code.name} and %1\$s", count >= 1, "%1\$s" in text)
            assertEquals("${code.name} and %2\$s", count == 2, "%2\$s" in text)
        }
    }

    @Test
    fun `no string uses a dash for a pause`() {
        for ((name, text) in strings) assertFalse(name, '\u2013' in text || '\u2014' in text)
    }

    @Test
    fun `a Lua problem is drawn under the same text, at its severity, with its arguments`() {
        val source = "print(zzq)\nfunction render() return ui.label { text = \"x\" } end"
        val found = LuaCode.diagnostics(source, LuaHostShape(storage = false))
            .single { it.messageRes == R.string.lua_diag_undefined_global }
        assertEquals(TextRange(6, 9), found.range)
        assertEquals(CodeSeverity.WARNING, found.severity)
        assertEquals("zzq", found.arg1)
    }

    private companion object {
        val TWO_ARGUMENTS = setOf(
            LuaDiagnosticCode.WRONG_CLOSER, LuaDiagnosticCode.UNCLOSED_BLOCK, LuaDiagnosticCode.SYNTAX_EXPECTED,
            LuaDiagnosticCode.MEMBER_TYPO, LuaDiagnosticCode.LUA51_NAME, LuaDiagnosticCode.EVENT_TYPE_TYPO,
            LuaDiagnosticCode.UNKNOWN_UI_FIELD, LuaDiagnosticCode.UI_FIELD_TYPO, LuaDiagnosticCode.LABEL_STYLE_TYPO,
            LuaDiagnosticCode.GLOBAL_TYPO,
        )
        val NO_ARGUMENTS = setOf(
            LuaDiagnosticCode.UNCLOSED_STRING, LuaDiagnosticCode.UNCLOSED_LONG_STRING, LuaDiagnosticCode.UNCLOSED_COMMENT,
            LuaDiagnosticCode.SYNTAX_AT_END, LuaDiagnosticCode.STRING_DUMP, LuaDiagnosticCode.STORAGE_NOT_DECLARED,
            LuaDiagnosticCode.REMINDERS_NOT_DECLARED,
            LuaDiagnosticCode.FRONTIER_PATTERN, LuaDiagnosticCode.MISSING_RENDER, LuaDiagnosticCode.RENDER_RETURNS_NOTHING,
            LuaDiagnosticCode.RENDER_PARAMETERS, LuaDiagnosticCode.ON_EVENT_PARAMETERS, LuaDiagnosticCode.TOO_MANY_TABS,
            LuaDiagnosticCode.LOOP_NEVER_ENDS,
        )
    }
}
