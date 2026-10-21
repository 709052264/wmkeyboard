package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A string that takes format arguments goes through `String.format`, which
 * reads every `%` as the start of a format code. A literal percent sign has to
 * be written `%%` there: `56% of` reads as the code `% o`, and `25% in` as the
 * invalid `% i`, which throws when the string is drawn. That crashed the
 * Bengali and Hindi language page (11da0fc08), and lint has no check that
 * would have caught it, so this one reads every module's strings in every
 * language.
 */
class StringFormatSafetyTest {

    @Test
    fun `no formatted string has a bare percent sign`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val files = root.walk()
            .onEnter { it.name != "build" && it.name != "node_modules" && !it.name.startsWith(".") }
            .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
            .filter { it.parentFile.name.startsWith("values") && it.path.contains("/src/main/res/") }
            .toList()
        val offenders = files.flatMap { file ->
            ENTRY.findAll(file.readText()).mapNotNull { match ->
                val attributes = match.groupValues[1].ifEmpty { match.groupValues[3] }
                val text = match.groupValues[2].ifEmpty { match.groupValues[4] }
                val formatted = "formatted=\"false\"" !in attributes && FORMAT_ARGUMENT.containsMatchIn(text)
                if (formatted && '%' in VALID_CODE.replace(text, "")) {
                    val name = NAME.find(attributes)?.groupValues?.get(1) ?: "<plurals item>"
                    "${file.relativeTo(root)}: $name"
                } else {
                    null
                }
            }.toList()
        }
        assertEquals("write a literal percent sign as %% in these", emptyList<String>(), offenders)
    }

    private companion object {
        val ENTRY = Regex("""<string\b([^>]*)>(.*?)</string>|<item\b([^>]*)>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val NAME = Regex("""name="([^"]+)"""")

        /** What makes Android format the string at all. */
        val FORMAT_ARGUMENT = Regex("""%(\d+\$)?[-#+0,(]*\d*[sd]""")

        /**
         * The codes this project writes, and `%%`. A space flag is left out on
         * purpose: in prose `% ` is always a percent sign followed by a word.
         */
        val VALID_CODE = Regex("""%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdfxXc]|%%""")
    }
}
