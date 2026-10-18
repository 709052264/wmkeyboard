package com.wasimaster.wmkeyboard.core.plugins.lua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The gate on every diagnostic: the demo plugins are the code the documentation
 * teaches from, so a check that calls anything in them an error or a warning is
 * the check that is wrong.
 */
class LuaDemoCorpusTest {

    private fun demo(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("plugins/$name.lua")) { "missing demo $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `no demo plugin has an error or a warning`() {
        val problems = DEMOS.flatMap { (name, host) ->
            val source = demo(name)
            val document = LuaDocument.of(source)
            assertNotNull("$name must be analysed", document.analysis)
            LuaDiagnostics.of(document, host)
                .filter { it.severity != LuaSeverity.INFO }
                .map { "$name:${lineOf(source, it.span.start)} ${it.code} ${it.arg1.orEmpty()} ${it.arg2.orEmpty()}".trim() }
        }
        assertEquals(emptyList<String>(), problems)
    }

    private fun lineOf(source: String, offset: Int): Int = source.substring(0, offset).count { it == '\n' } + 1

    private companion object {
        /** Each demo and the permissions its manifest declares. */
        val DEMOS = listOf(
            "cipher-tool" to LuaHostShape(storage = false),
            "ui-kitchen-sink" to LuaHostShape(storage = false),
            "todo-list" to LuaHostShape(storage = true, reminders = true),
            "text-tools" to LuaHostShape(storage = false),
            "math-mode" to LuaHostShape(storage = true),
        )
    }
}
