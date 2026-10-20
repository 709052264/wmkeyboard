package com.wasimaster.wmkeyboard.core.aimemory

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiMemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.newFolder(), AiMemoryStore.FILE_PATH)

    @Test
    fun `a new fact is added with an id and its source`() {
        val store = AiMemoryStore(null)
        val outcome = store.remember("Lives in Dhaka", now = 100, source = "chat:4", category = "home")
        outcome as AiMemoryOutcome.Added
        assertEquals(1L, outcome.memory.id)
        assertEquals("chat:4", outcome.memory.source)
        assertEquals("home", outcome.memory.category)
        assertEquals(listOf("Lives in Dhaka"), store.items().map { it.text })
    }

    @Test
    fun `the same fact is not kept twice`() {
        val store = AiMemoryStore(null)
        store.remember("Lives in Dhaka", now = 100)
        val again = store.remember("lives in dhaka.", now = 200)
        assertTrue(again is AiMemoryOutcome.Duplicate)
        assertEquals(1, store.size())
    }

    @Test
    fun `a fact that says more replaces the old wording under its id`() {
        val store = AiMemoryStore(null)
        store.remember("Likes tea", now = 100)
        val outcome = store.remember("Likes green tea", now = 200)
        outcome as AiMemoryOutcome.Updated
        assertEquals(1L, outcome.memory.id)
        assertEquals("Likes tea", outcome.before.text)
        assertEquals(200L, outcome.memory.updatedAt)
        assertEquals(100L, outcome.memory.createdAt)
        assertEquals(listOf("Likes green tea"), store.items().map { it.text })
    }

    @Test
    fun `a replace by id changes that memory, and a missing id is refused`() {
        val store = AiMemoryStore(null)
        store.remember("Lives in Dhaka", now = 100)
        val moved = store.remember("Lives in Chattogram", now = 200, replaceId = 1)
        assertTrue(moved is AiMemoryOutcome.Updated)
        assertEquals("Lives in Chattogram", store.get(1)?.text)
        val missing = store.remember("Lives in Sylhet", now = 300, replaceId = 9)
        assertEquals(AiMemoryOutcome.Reason.NOT_FOUND, (missing as AiMemoryOutcome.Refused).reason)
    }

    @Test
    fun `secrets, empty and overlong facts are refused`() {
        val store = AiMemoryStore(null)
        assertEquals(
            AiMemoryOutcome.Reason.SECRET,
            (store.remember("My PIN is 4821", now = 1) as AiMemoryOutcome.Refused).reason,
        )
        assertEquals(AiMemoryOutcome.Reason.EMPTY, (store.remember("   ", now = 1) as AiMemoryOutcome.Refused).reason)
        assertEquals(
            AiMemoryOutcome.Reason.TOO_LONG,
            (store.remember("x".repeat(AiMemoryText.MAX_CHARS + 1), now = 1) as AiMemoryOutcome.Refused).reason,
        )
        assertEquals(0, store.size())
    }

    @Test
    fun `a user's edit is filtered too, and never merges`() {
        val store = AiMemoryStore(null)
        store.remember("Likes tea", now = 1)
        store.remember("Works at a school", now = 2)
        val secret = store.edit(2, "Card 4111 1111 1111 1111", "", now = 3)
        assertEquals(AiMemoryOutcome.Reason.SECRET, (secret as AiMemoryOutcome.Refused).reason)
        // Saying what memory 1 says keeps two memories: an edit is the user's word.
        val edited = store.edit(2, "Likes tea a lot", "food", now = 4)
        assertTrue(edited is AiMemoryOutcome.Updated)
        assertEquals(2, store.size())
        assertEquals("food", store.get(2)?.category)
    }

    @Test
    fun `line breaks become spaces`() {
        val store = AiMemoryStore(null)
        store.remember("Lives in\n  Dhaka", now = 1)
        assertEquals("Lives in Dhaka", store.items().single().text)
    }

    @Test
    fun `forget, matching and restore`() {
        val store = AiMemoryStore(null)
        store.remember("Lives in Dhaka", now = 1)
        store.remember("Allergic to peanuts", now = 2)
        assertEquals(listOf(2L), store.matching("peanuts").map { it.id })
        val gone = store.forget(2)
        assertEquals("Allergic to peanuts", gone?.text)
        assertNull(store.forget(2))
        store.restore(requireNotNull(gone))
        assertEquals(2, store.size())
        // A restored id is never handed out again.
        val next = store.remember("Has a dog", now = 3) as AiMemoryOutcome.Added
        assertEquals(3L, next.memory.id)
    }

    @Test
    fun `the list survives a save and a reload`() {
        val target = file()
        val first = AiMemoryStore(target)
        first.remember("Lives in Dhaka", now = 1, category = "home")
        first.save()
        val second = AiMemoryStore(target)
        assertEquals(listOf("Lives in Dhaka"), second.items().map { it.text })
        assertEquals("home", second.items().single().category)
        assertTrue(second.readable)
    }

    @Test
    fun `a file that cannot be read is never written over`() {
        val target = file()
        target.parentFile?.mkdirs()
        target.writeText("{ not json")
        val store = AiMemoryStore(target)
        assertFalse(store.readable)
        val outcome = store.remember("Lives in Dhaka", now = 1)
        assertEquals(AiMemoryOutcome.Reason.UNREADABLE, (outcome as AiMemoryOutcome.Refused).reason)
        store.save()
        assertEquals("{ not json", target.readText())
    }

    @Test
    fun `the store refuses past its cap`() {
        val store = AiMemoryStore(null)
        // One word each, all different, so none is taken for another.
        for (i in 1..AiMemoryStore.MAX_ITEMS) store.remember("fact$i", now = i.toLong())
        assertEquals(AiMemoryStore.MAX_ITEMS, store.size())
        val outcome = store.remember("One more unrelated thing entirely", now = 9_999)
        assertEquals(AiMemoryOutcome.Reason.FULL, (outcome as AiMemoryOutcome.Refused).reason)
    }

    @Test
    fun `revision moves on every change`() {
        val store = AiMemoryStore(null)
        val before = store.revision.value
        store.remember("Lives in Dhaka", now = 1)
        assertTrue(store.revision.value > before)
    }
}
