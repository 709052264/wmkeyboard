package com.wasimaster.wmkeyboard.ime.aichat

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a model writes back as a chat's title, and the name the chat gets from it (#882). */
class AiChatTitleTest {

    @Test
    fun `quotes, a full stop and a label come off`() {
        assertEquals("Paris trip plans", AiChatController.cleanTitle("Title: \"Paris trip plans.\""))
    }

    @Test
    fun `reasoning and lines after the first are dropped`() {
        assertEquals("ঢাকার আবহাওয়া", AiChatController.cleanTitle("<think>Bengali question</think>\nঢাকার আবহাওয়া\nExplanation"))
    }

    @Test
    fun `nothing usable is empty`() {
        assertEquals("", AiChatController.cleanTitle("<think>still going"))
    }
}
