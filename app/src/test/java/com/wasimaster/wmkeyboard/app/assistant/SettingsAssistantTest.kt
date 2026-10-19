package com.wasimaster.wmkeyboard.app.assistant

import com.wasimaster.wmkeyboard.app.R
import com.wasimaster.wmkeyboard.app.SettingsSearchEntry
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.AiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings assistant's contract (#788): it can only open rows that exist,
 * only propose values a setting takes, and a proposal is applied once, while
 * the setting still holds what it held, and undone only while nobody has
 * changed it since.
 */
class SettingsAssistantTest {

    private val longPress = AssistantSetting.byName("long_press_delay")!!
    private val autocorrect = AssistantSetting.byName("autocorrect")!!

    private val index = listOf(
        SettingsSearchEntry(
            title = "Long press delay", subtitle = "How long a key is held", screenPath = listOf("Key press"),
            route = "keypress", titleRes = R.string.keypress_long_press_delay_title,
        ),
        SettingsSearchEntry(
            title = "Autocorrect", subtitle = "Fix typos", screenPath = listOf("Typing"),
            route = "typing", titleRes = R.string.typing_autocorrect_title,
        ),
    )

    private var settings = KeyboardSettings()
    private val opened = ArrayList<SettingsSearchEntry>()
    private val proposed = ArrayList<Triple<AssistantSetting, String, String>>()

    private val tools = AssistantTools(
        index = index,
        settings = { settings },
        host = object : AssistantTools.Host {
            override fun open(entry: SettingsSearchEntry) {
                opened += entry
            }

            override fun propose(setting: AssistantSetting, from: String, to: String): Int {
                proposed += Triple(setting, from, to)
                return proposed.size
            }
        },
    )

    private fun call(name: String, args: String) = tools.run(AiToolCall("c", name, args))

    @Test
    fun `search finds real rows and says which can be changed`() {
        val result = call(AssistantTools.SEARCH, """{"query":"long press"}""")
        assertTrue(result.content, "Long press delay" in result.content)
        assertTrue(result.content, "changeable as long_press_delay" in result.content)
    }

    @Test
    fun `a made-up row is refused and nothing opens`() {
        val result = call(AssistantTools.OPEN, """{"id":"settings/secret#Nope"}""")
        assertTrue(result.error.isNotEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a row from search opens`() {
        call(AssistantTools.OPEN, """{"id":"${index.first().key}"}""")
        assertEquals(listOf(index.first()), opened)
    }

    @Test
    fun `a value the setting does not take is not proposed`() {
        for (value in listOf("5000", "fast", "")) {
            val result = call(AssistantTools.PROPOSE, """{"name":"long_press_delay","value":"$value"}""")
            assertTrue(value, result.error.isNotEmpty())
        }
        assertTrue(proposed.isEmpty())
    }

    @Test
    fun `a setting off the list cannot be proposed`() {
        val result = call(AssistantTools.PROPOSE, """{"name":"openai_key","value":"x"}""")
        assertTrue(result.error.isNotEmpty())
        assertTrue(proposed.isEmpty())
    }

    @Test
    fun `a proposal records what the setting held and changes nothing`() {
        call(AssistantTools.PROPOSE, """{"name":"long_press_delay","value":"450 ms"}""")
        assertEquals(Triple(longPress, "300", "450"), proposed.single())
        assertEquals(300, settings.longPressDelayMs)
    }

    @Test
    fun `nothing the tools can read is a secret`() {
        settings = settings.copy(ai = settings.ai.copy(openAiKey = "sk-very-secret"))
        val all = AssistantSetting.all.joinToString { call(AssistantTools.GET, """{"name":"${it.name}"}""").content } +
            call(AssistantTools.SEARCH, """{"query":"key"}""").content
        assertFalse(all, "sk-very-secret" in all)
    }

    @Test
    fun `apply needs the setting to still hold what it held`() {
        val proposal = AssistantProposal(1, longPress, from = "300", to = "450")
        assertNull(AssistantProposals.applyRefusal(proposal, settings))
        settings = settings.copy(longPressDelayMs = 350)
        assertEquals(AssistantRefusal.CHANGED_SINCE, AssistantProposals.applyRefusal(proposal, settings))
    }

    @Test
    fun `a proposal applies once`() {
        val applied = AssistantProposal(1, longPress, "300", "450", AssistantProposal.State.APPLIED)
        assertEquals(AssistantRefusal.NOT_PROPOSED, AssistantProposals.applyRefusal(applied, settings))
    }

    @Test
    fun `undo never writes over a change made since`() {
        val applied = AssistantProposal(1, autocorrect, "true", "false", AssistantProposal.State.APPLIED)
        settings = settings.copy(correction = settings.correction.copy(enabled = false))
        assertNull(AssistantProposals.undoRefusal(applied, settings))
        settings = settings.copy(correction = settings.correction.copy(enabled = true))
        assertEquals(AssistantRefusal.CHANGED_SINCE, AssistantProposals.undoRefusal(applied, settings))
    }

    @Test
    fun `every setting reads back the value it would write`() {
        for (setting in AssistantSetting.all) {
            val value = setting.read(KeyboardSettings())
            assertEquals(setting.name, value, setting.parse(value))
        }
    }
}
