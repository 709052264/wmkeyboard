package com.wasimaster.wmkeyboard.core.snippets.espanso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `Regex(...)` in the snippet code, checked for the one difference that
 * `java.util.regex` will not tell you about.
 *
 * Android compiles regular expressions with ICU, which **rejects** an unescaped
 * `}` or `]` outside a character class. The JVM accepts both and reads them as
 * literals. So a pattern like `\{random:(...)}` compiles on a laptop, passes
 * every unit test, and then throws `PatternSyntaxException` out of a static
 * initialiser the first time a phone touches the class — which surfaces as
 * `ExceptionInInitializerError` from somewhere unrelated-looking.
 *
 * That is what happened to the Espanso writer (issue #29), and no amount of
 * behaviour testing on the JVM could have caught it. This reads the sources
 * instead, because the mistake is in the *text* of the pattern.
 */
class EspansoRegexSourceTest {

    private val sources = listOf(
        "core/content/src/main/java/com/wasimaster/wmkeyboard/core/snippets/espanso",
        "core/content/src/main/java/com/wasimaster/wmkeyboard/core/snippets",
        // A single file: LaTeX patterns are full of braces, and this one
        // shipped a bare `}` that crashed every chat answer on a phone (#811).
        "core/tools/src/main/java/com/wasimaster/wmkeyboard/core/tools/LatexMath.kt",
    )

    /** Walks up from the test's working directory to the repository root. */
    private fun repoRoot(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun regexLiterals(): List<Pair<String, String>> {
        val root = repoRoot() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (path in sources) {
            val dir = File(root, path)
            val files = when {
                dir.isFile -> listOf(dir)
                dir.isDirectory -> dir.listFiles().orEmpty().toList()
                else -> continue
            }
            for (file in files) {
                if (file.extension != "kt") continue
                // Only the triple-quoted form, which is how every pattern here
                // is written; a quoted one would need its own unescaping.
                for (m in Regex("""Regex\("""" + "\"\"" + """([\s\S]*?)"""" + "\"\"" + """\)""")
                    .findAll(file.readText())) {
                    out += file.name to m.groupValues[1]
                }
            }
        }
        return out
    }

    /** True when [pattern] closes a brace or bracket that nothing opened. */
    private fun hasUnescapedClose(pattern: String): Boolean {
        var i = 0
        var inClass = false
        var depth = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++
                inClass && c == ']' -> inClass = false
                inClass -> Unit
                c == '[' -> inClass = true
                c == '{' -> depth++
                // A `}` closing a quantifier such as {1,9} is fine; a stray one
                // is what ICU refuses.
                c == '}' -> if (depth > 0) depth-- else return true
                c == ']' -> return true
            }
            i++
        }
        return false
    }

    @Test
    fun `no pattern relies on the JVM accepting a stray brace or bracket`() {
        val literals = regexLiterals()
        assertTrue("found no Regex literals to check", literals.isNotEmpty())
        val bad = literals.filter { hasUnescapedClose(it.second) }.map { "${it.first}: ${it.second}" }
        assertEquals(
            "these compile on the JVM but throw on Android; escape the close as \\} or \\]",
            emptyList<String>(),
            bad,
        )
    }

    @Test
    fun `the checker actually spots the shape that broke on the device`() {
        // The writer's own pattern, as it was written before issue #29.
        assertTrue(hasUnescapedClose("""\{random:([^}\n]{1,400})}"""))
        assertTrue(hasUnescapedClose("""\{\{\s*([A-Za-z0-9_.]{1,64})\s*}}"""))
        assertTrue(hasUnescapedClose("""\[([^\]\n]{0,200})]\(x\)"""))
        // And leaves the corrected ones alone, quantifiers and classes included.
        assertTrue(!hasUnescapedClose("""\{random:([^}\n]{1,400})\}"""))
        assertTrue(!hasUnescapedClose("""\{date([+-]\d{1,9})?(?::([^}\n]{1,40}))?\}"""))
        assertTrue(!hasUnescapedClose("""\{date[+\-:}]"""))
        assertTrue(!hasUnescapedClose("""[a-z0-9][a-z0-9-]{0,60}"""))
    }
}
