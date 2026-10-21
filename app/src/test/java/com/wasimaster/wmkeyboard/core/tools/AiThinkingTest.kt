package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiThinkingTest {

    @Test
    fun `plain output passes through`() {
        val split = AiThinking.split("Hello world")
        assertEquals("Hello world", split.output)
        assertFalse(split.thinking)
    }

    @Test
    fun `open think block means still reasoning`() {
        val split = AiThinking.split("<think>step 1, step 2")
        assertEquals("", split.output)
        assertTrue(split.thinking)
    }

    @Test
    fun `closed think block is removed from output`() {
        val split = AiThinking.split("<think>reasoning</think>The answer.")
        assertEquals("The answer.", split.output)
        assertFalse(split.thinking)
    }

    @Test
    fun `text before think block is kept`() {
        assertEquals("Before After", AiThinking.stripped("Before <think>x</think>After"))
    }

    @Test
    fun `stripped trims and handles think-only output`() {
        assertEquals("", AiThinking.stripped("<think>never closed"))
        assertEquals("Answer", AiThinking.stripped("<think>r</think>\n Answer \n"))
    }

    @Test
    fun `close without open treats the head as reasoning`() {
        // Qwen3-style: the template already opened the think block.
        val split = AiThinking.split("step 1, step 2</think>The answer.")
        assertEquals("The answer.", split.output)
        assertFalse(split.thinking)
        assertEquals("The answer.", AiThinking.stripped("reasoning</think>\nThe answer."))
    }

    @Test
    fun `implicit thinking hides bare reasoning until the close tag`() {
        val streaming = AiThinking.split("step 1, step 2", implicitThink = true)
        assertEquals("", streaming.output)
        assertTrue(streaming.thinking)
        // A finished response with no markers at all is a normal answer.
        assertEquals("Plain answer", AiThinking.stripped("Plain answer"))
    }

    @Test
    fun `reasoning is what sits inside the tags`() {
        assertEquals("let me see", AiThinking.reasoning("<think>let me see</think>The answer"))
        assertEquals("still going", AiThinking.reasoning("<think>still going"))
        assertEquals("", AiThinking.reasoning("<think>"))
        assertEquals("", AiThinking.reasoning("Plain answer"))
        assertEquals("", AiThinking.reasoning(""))
    }

    @Test
    fun `reasoning under an implicit opener`() {
        assertEquals("bare thoughts", AiThinking.reasoning("bare thoughts</think>Answer", implicitThink = true))
        assertEquals("bare thoughts", AiThinking.reasoning("bare thoughts", implicitThink = true))
        // A close with no opener is reasoning whether or not the template said so.
        assertEquals("thoughts", AiThinking.reasoning("thoughts</think>Answer"))
    }

    @Test
    fun `reasoning and output split the stream between them`() {
        // What the streaming bubble draws: the two never overlap and never lose text.
        val raw = "<think>work it out</think>| a | b |\n|---|---|\n| \$x\$ | 2 |"
        assertEquals("work it out", AiThinking.reasoning(raw))
        assertEquals("| a | b |\n|---|---|\n| \$x\$ | 2 |", AiThinking.split(raw).output)
        for (end in 0..raw.length) {
            val part = raw.substring(0, end)
            AiThinking.reasoning(part)
            AiThinking.reasoning(part, implicitThink = true)
        }
    }

    @Test
    fun `parts gathers every round's reasoning and leaves none in the answer`() {
        val parts = AiThinking.parts("<think>look it up</think>Searching.\n\n<think>found it</think>It is 5.")
        assertEquals("look it up\n\nfound it", parts.reasoning)
        assertEquals("Searching.\n\nIt is 5.", parts.output)
        assertFalse(parts.thinking)
    }

    @Test
    fun `parts reads a close with no opener as reasoning before it`() {
        val parts = AiThinking.parts("weighing it</think>Yes.")
        assertEquals("weighing it", parts.reasoning)
        assertEquals("Yes.", parts.output)
    }

    @Test
    fun `parts says an open passage is still thinking`() {
        val parts = AiThinking.parts("First.<think>now then")
        assertEquals("now then", parts.reasoning)
        assertEquals("First.", parts.output)
        assertTrue(parts.thinking)
    }

    @Test
    fun `parts under implicit think treats unmarked text as reasoning`() {
        val parts = AiThinking.parts("still going", implicitThink = true)
        assertEquals("still going", parts.reasoning)
        assertEquals("", parts.output)
        assertTrue(parts.thinking)
    }
}
