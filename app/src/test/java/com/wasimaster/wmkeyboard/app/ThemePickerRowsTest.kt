package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the theme picker list.
 *
 * The rule this holds is not cosmetic. A theme with no family used to be
 * emitted wherever it came in the list, so the standalone built-ins that follow
 * a family read as looks of that family, and the user's own themes ran on from
 * the built-ins with nothing to mark the join. Both are wrong answers to
 * "which of these belong together", which is the only question the list asks.
 */
class ThemePickerRowsTest {

    private fun theme(id: String, name: String, variants: List<ThemeSpec> = emptyList()) =
        ThemeSpec(id = id, name = name, variants = variants)

    private fun rows(
        builtIns: List<ThemeSpec>,
        customs: List<ThemeSpec> = emptyList(),
        defaultInBuiltIns: Boolean = false,
    ) = themePickerRows(
        builtIns = builtIns,
        customs = customs,
        defaultName = "Default",
        builtInLabel = "Built-in themes",
        customLabel = "Your themes",
        familyName = { it.familyName ?: it.name },
        name = { it.name },
        defaultInBuiltIns = defaultInBuiltIns,
    )

    @Test
    fun `no theme without a family follows a family heading`() {
        // The shipped list is the case that broke: it interleaves families and
        // standalone themes in the order they were added.
        var lastWasFamily = false
        for (row in rows(BuiltInThemes)) {
            when (row) {
                is ThemePickerRow.Family -> lastWasFamily = true
                is ThemePickerRow.Section -> lastWasFamily = false
                is ThemePickerRow.Choice -> {
                    if (lastWasFamily) {
                        assertTrue(
                            "${row.name} sits under a family heading but is not one of its looks",
                            row.indented,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every look of a family is indented and every standalone theme is not`() {
        val family = theme("f", "Family", listOf(theme("f_v0", "Look")))
        val rows = rows(listOf(theme("single", "Single"), family))
        val indented = rows.filterIsInstance<ThemePickerRow.Choice>()
            .filter { it.indented }.map { it.id }
        assertEquals(listOf("f", "f_v0"), indented)
    }

    @Test
    fun `standalone themes come before the families in their section`() {
        val family = theme("f", "Family", listOf(theme("f_v0", "Look")))
        val rows = rows(listOf(family, theme("single", "Single")))
        val ids = rows.filterIsInstance<ThemePickerRow.Choice>().map { it.id }
        assertEquals(listOf(DEFAULT_THEME_ID, "single", "f", "f_v0"), ids)
    }

    @Test
    fun `the user's own themes sit under a heading of their own`() {
        val rows = rows(
            builtIns = listOf(theme("b", "Built in")),
            customs = listOf(theme("custom_2", "Zebra"), theme("custom_1", "Apple")),
        )
        val sections = rows.filterIsInstance<ThemePickerRow.Section>().map { it.label }
        assertEquals(listOf("Built-in themes", "Your themes"), sections)
        // Sorted by name, not by the order they happen to be stored in.
        val customIds = rows.filterIsInstance<ThemePickerRow.Choice>()
            .map { it.id }.filter { it.startsWith("custom_") }
        assertEquals(listOf("custom_1", "custom_2"), customIds)
    }

    @Test
    fun `an empty section gets no heading`() {
        val labels = rows(listOf(theme("b", "Built in")))
            .filterIsInstance<ThemePickerRow.Section>().map { it.label }
        assertEquals(listOf("Built-in themes"), labels)
    }

    @Test
    fun `the default theme leads the list and belongs to no section`() {
        val first = rows(BuiltInThemes).first()
        assertTrue(first is ThemePickerRow.Choice)
        assertEquals(DEFAULT_THEME_ID, (first as ThemePickerRow.Choice).id)
        assertFalse(first.indented)
    }

    @Test
    fun `a family heading names every look under it`() {
        val family = theme("f", "Family", listOf(theme("f_v0", "One"), theme("f_v1", "Two")))
        val heading = rows(listOf(family)).filterIsInstance<ThemePickerRow.Family>().single()
        assertEquals(listOf("f", "f_v0", "f_v1"), heading.memberIds)
    }

    @Test
    fun `every shipped theme is selectable exactly once`() {
        val ids = rows(BuiltInThemes).filterIsInstance<ThemePickerRow.Choice>().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val shipped = BuiltInThemes.flatMap { listOf(it) + it.variants }.map { it.id }
        assertTrue(ids.containsAll(shipped))
    }

    @Test
    fun `under an Inherit row the default theme is the first built-in, not a peer of Inherit`() {
        // Issue #806: "Default (system)" right under "Inherit" read as a second
        // way to follow the theme that is set, and pinned a panel to Material You.
        val rows = rows(listOf(theme("b", "Built in")), defaultInBuiltIns = true)
        assertTrue(rows.first() is ThemePickerRow.Section)
        val choices = rows.filterIsInstance<ThemePickerRow.Choice>().map { it.id }
        assertEquals(listOf(DEFAULT_THEME_ID, "b"), choices)
        assertEquals(1, rows.indexOfFirst { it is ThemePickerRow.Choice && it.id == DEFAULT_THEME_ID })
    }
}
