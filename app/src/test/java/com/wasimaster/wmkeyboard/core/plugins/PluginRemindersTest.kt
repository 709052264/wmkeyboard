package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRemindersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `reminders round trip through the file and two readers agree`() {
        val file = File(temp.newFolder("plugin"), "reminders.json")
        var changes = 0
        val writer = PluginReminders(file) { changes++ }
        assertNull(writer.set("a", now + 2 * hour, "Second", now))
        assertNull(writer.set("b", now + hour, "First", now))

        // A second instance over the same file, as the alarm has, sees both,
        // soonest first, and its write is seen by the first.
        val alarm = PluginReminders(file)
        assertEquals(listOf("b", "a"), alarm.list().map { it.id })
        assertEquals(listOf("b"), alarm.takeDue(now + hour).map { it.id })
        assertEquals(listOf("a"), writer.list().map { it.id })
        assertEquals(now + 2 * hour, writer.next())
        assertEquals(2, changes)
    }

    @Test
    fun `setting an id again replaces it`() {
        val list = PluginReminders(null)
        list.set("a", now + hour, "One", now)
        list.set("a", now + 2 * hour, "Two", now)
        assertEquals(listOf(PluginReminder("a", now + 2 * hour, "Two")), list.list())
    }

    @Test
    fun `refusals say why and change nothing`() {
        val list = PluginReminders(null)
        assertNotNull(list.set("", now + hour, "x", now))
        assertNotNull(list.set("x".repeat(65), now + hour, "x", now))
        assertNotNull(list.set("a", now, "x", now))
        assertNotNull(list.set("a", now + hour, " ‮ ", now))
        assertNotNull(list.set("a", Long.MAX_VALUE, "x", now))
        assertTrue(list.list().isEmpty())
    }

    @Test
    fun `text is cleaned and cut the way a plugin name is`() {
        val list = PluginReminders(null)
        list.set("a", now + hour, "‮gnp.exe " + "y".repeat(300), now)
        val text = list.list().single().text
        assertFalse(text.contains('‮'))
        assertTrue(text.length <= PluginReminders.MAX_TEXT_LENGTH)
    }

    @Test
    fun `there is a cap on how many a plugin keeps`() {
        val list = PluginReminders(null)
        repeat(PluginReminders.MAX_REMINDERS) { assertNull(list.set("r$it", now + hour, "x", now)) }
        assertNotNull(list.set("one more", now + hour, "x", now))
        // Replacing one that exists is still allowed at the cap.
        assertNull(list.set("r0", now + 2 * hour, "y", now))
    }

    @Test
    fun `cancel and clear remove, and the file goes when the list is empty`() {
        val file = File(temp.newFolder("gone"), "reminders.json")
        val list = PluginReminders(file)
        list.set("a", now + hour, "x", now)
        list.set("b", now + hour, "y", now)
        assertTrue(list.cancel("a"))
        assertFalse(list.cancel("a"))
        list.clear()
        assertTrue(list.list().isEmpty())
        assertFalse(file.exists())
    }
}
