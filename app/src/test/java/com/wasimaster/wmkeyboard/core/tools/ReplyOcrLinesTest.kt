package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screenshot lines into messages (#234). The layouts are the shape of a real
 * Messenger screenshot: 1000 wide, 2000 tall, lines 40 high.
 */
class ReplyOcrLinesTest {

    private fun line(text: String, left: Int, top: Int, right: Int = left + 500) =
        ReplyOcrLines.Line(text, left, top, right, top + 40)

    @Test
    fun `times, presence and placeholders are chrome`() {
        listOf(
            "3:51", "3:51 PM", "Mon 3:51 PM", "Yesterday at 10:02", "Today", "Oct 12, 2026",
            "12 Oct", "13/10/2026", "Active now", "Active 5 minutes ago", "Seen", "Delivered",
            "Read by Sam", "Edited", "Sam is typing…", "last seen today at 3:51", "Message", "74",
        ).forEach { assertTrue(it, ReplyOcrLines.isChrome(it)) }
    }

    @Test
    fun `words that only start like chrome are messages`() {
        listOf(
            "meet 5:30", "Room 12", "Read the book", "Seen it already lol", "may 5 work?",
            "O portese r ami debo ghum", "Active today?",
        ).forEach { assertFalse(it, ReplyOcrLines.isChrome(it)) }
    }

    @Test
    fun `a wrapped bubble is one message`() {
        val turns = ReplyOcrLines.messages(
            listOf(
                line("Sobai vabbe amio porte chole", 160, 450),
                line("gesi", 160, 498),
            ),
            width = 1000,
            height = 2000,
        )
        assertEquals(listOf(ReplyTurn(false, "Sobai vabbe amio porte chole gesi")), turns)
    }

    @Test
    fun `two bubbles stay two`() {
        val turns = ReplyOcrLines.messages(
            listOf(
                line("O portese r ami debo ghum", 160, 270),
                line("Sobai vabbe amio porte chole", 160, 450),
            ),
            width = 1000,
            height = 2000,
        )
        assertEquals(2, turns.size)
    }

    @Test
    fun `right side is mine and sides never join`() {
        val turns = ReplyOcrLines.messages(
            listOf(
                line("hi", 160, 300),
                line("hello there", 600, 345, 950),
            ),
            width = 1000,
            height = 2000,
        )
        assertEquals(listOf(ReplyTurn(false, "hi"), ReplyTurn(true, "hello there")), turns)
    }

    @Test
    fun `the keyboard and the app bar are cut off`() {
        val turns = ReplyOcrLines.messages(
            listOf(
                line("mitti pakhi", 260, 120),
                line("O portese r ami debo ghum", 160, 270),
                line("AI reply", 120, 820),
                line("WM Keyboard needs permission", 20, 1000),
            ),
            width = 1000,
            height = 2000,
            frame = ReplyOcrLines.ReplyOcrFrame(topCut = 0.11f, bottomCut = 0.38f),
        )
        assertEquals(listOf(ReplyTurn(false, "O portese r ami debo ghum")), turns)
    }

    @Test
    fun `lines arrive in reading order whatever order OCR gave them`() {
        val turns = ReplyOcrLines.messages(
            listOf(line("second", 160, 600), line("first", 160, 300)),
            width = 1000,
            height = 2000,
        )
        assertEquals(listOf("first", "second"), turns.map { it.text })
    }

    // ---- the app's own notices inside a thread ----

    @Test
    fun `reply and reaction banners are chrome`() {
        for (line in listOf(
            "Replied to you", "You replied to Sam", "replied to your story",
            "Sam reacted 😂 to your message", "Liked a message", "Sam mentioned you",
        )) assertTrue(line, ReplyOcrLines.isChrome(line))
    }

    @Test
    fun `deleted stubs, attachments and calls are chrome`() {
        for (line in listOf(
            "This message was deleted", "You deleted this message", "Photo", "Sticker",
            "Voice message 0:12", "Missed voice call", "Video call 3:21", "Tap to view",
            "2 unread messages", "Messages and calls are end-to-end encrypted. Tap to learn more.",
            "Sam pinned a message", "Sam left the group", "Swipe to reply",
        )) assertTrue(line, ReplyOcrLines.isChrome(line))
    }

    @Test
    fun `messages that only start like a notice are kept`() {
        for (line in listOf(
            "I left early", "call me when you're free", "Photo looks great, where was it taken?",
            "replied to your mom yet? she was asking about sunday dinner and the whole plan",
        )) assertFalse(line, ReplyOcrLines.isChrome(line))
    }

    // ---- unclear messages ----

    @Test
    fun `a message with no known word is unclear`() {
        val known = setOf("see", "you", "tomorrow")
        assertTrue(ReplyText.looksUnclear("xqzv bnmt", known::contains))
        assertFalse(ReplyText.looksUnclear("see you tmrw", known::contains))
    }

    @Test
    fun `emoji, numbers and other scripts are never unclear`() {
        val none = { _: String -> false }
        assertFalse(ReplyText.looksUnclear("😂😂 12:30", none))
        assertFalse(ReplyText.looksUnclear("আজকে আসবি?", none))
        assertFalse(ReplyText.looksUnclear("ok u", none))
    }
}
