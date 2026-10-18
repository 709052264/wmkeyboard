package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginTargets
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIdeDockTest {

    @Test
    fun `every widget of the last draw has an event to send, a turned-off button included`() {
        val targets = PluginTargets(
            buttons = listOf(PluginWidget.Button("go", "Go", primary = true, enabled = false)),
            toggles = listOf(PluginWidget.Toggle("caps", "Caps", checked = true)),
            inputs = listOf(PluginWidget.Input("msg", "Message", "")),
            tabs = listOf(PluginWidget.Tabs("modes", listOf(PluginWidget.Tabs.Page("A", emptyList()), PluginWidget.Tabs.Page("B", emptyList())))),
        )
        assertEquals(
            listOf(
                InjectableEvent(PluginEvent.Click("go"), widgetEnabled = false),
                InjectableEvent(PluginEvent.ToggleChanged("caps", false)),
                InjectableEvent(PluginEvent.TabSelected("modes", 0)),
                InjectableEvent(PluginEvent.TabSelected("modes", 1)),
            ),
            injectableEvents(targets),
        )
    }

    @Test
    fun `a date offers its next day and a clear, or a first date when it has none`() {
        val targets = PluginTargets(
            buttons = emptyList(),
            toggles = emptyList(),
            inputs = emptyList(),
            tabs = emptyList(),
            dates = listOf(
                PluginWidget.DatePicker("due", "", 1_790_000_000L, time = true, step = 15),
                PluginWidget.DatePicker("start", "", null, time = false, step = 15),
            ),
        )
        val events = injectableEvents(targets, now = 1_790_000_000_000L).map { it.event }
        assertEquals(4, events.size)
        assertEquals(PluginEvent.DateChanged("due", null), events[1])
        assertEquals(PluginEvent.DateChanged("start", null), events[3])
        assertTrue((events[0] as PluginEvent.DateChanged).value!! > 1_790_000_000L)
        assertTrue((events[2] as PluginEvent.DateChanged).value != null)
        assertEquals(PluginEvent.DateChanged("due", 42L), customEvent("date_changed", "due", "42"))
        assertEquals(PluginEvent.DateChanged("due", null), customEvent("date_changed", "due", ""))
    }

    @Test
    fun `budget shares stay between nothing and all of it`() {
        assertEquals(listOf(0f, 0.5f, 1f), budgetShares(listOf(0, 2_000_000, 9_000_000), 4_000_000))
        assertEquals(listOf(0f), budgetShares(listOf(5), 0))
    }

    @Test
    fun `typing runs the plugin only for new text that parses, with the switch on`() {
        assertTrue(shouldRunAsTyped(enabled = true, text = "b", lastRun = "a", parses = true))
        assertFalse(shouldRunAsTyped(enabled = true, text = "a", lastRun = "a", parses = true))
        assertFalse(shouldRunAsTyped(enabled = true, text = "b", lastRun = "a", parses = false))
        assertFalse(shouldRunAsTyped(enabled = false, text = "b", lastRun = "a", parses = true))
    }

    @Test
    fun `an event built by hand needs an id and reads its value by type`() {
        assertEquals(PluginEvent.Click("go"), customEvent("click", " go ", "ignored"))
        assertEquals(PluginEvent.ToggleChanged("caps", true), customEvent("toggle", "caps", "TRUE"))
        assertEquals(PluginEvent.InputChanged("msg", " hi "), customEvent("input_changed", "msg", " hi "))
        assertEquals(PluginEvent.TabSelected("modes", 2), customEvent("tab_selected", "modes", "2"))
        assertEquals(PluginEvent.TabSelected("modes", 0), customEvent("tab_selected", "modes", "two"))
        assertNull(customEvent("click", "  ", ""))
        assertNull(customEvent("press", "go", ""))
    }
}
