package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Test

/** `{location}` and `{place:…}` in snippets (#350). */
class SnippetLocationVariableTest {

    private val context = SnippetStore.Companion.Context(
        location = { format -> "HERE[${format ?: "default"}]" },
        place = { name, format -> if (name.equals("home", ignoreCase = true)) "HOME[${format ?: "default"}]" else null },
    )

    @Test
    fun `location and place resolve through the context`() {
        assertEquals(
            "I'm at HERE[default], see HERE[link]. Home is HOME[address].",
            SnippetStore.expand("I'm at {location}, see {location:link}. Home is {place:Home:address}.", context = context),
        )
    }

    @Test
    fun `an unknown place and a missing resolver leave nothing behind`() {
        assertEquals("at ", SnippetStore.expand("at {place:Moon}", context = context))
        assertEquals("at ", SnippetStore.expand("at {location}"))
    }
}
