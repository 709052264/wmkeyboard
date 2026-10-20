package com.wasimaster.wmkeyboard.core.aimemory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMemoryTextTest {

    private fun memory(id: Long, text: String, at: Long = id, category: String = "") =
        AiMemory(id = id, text = text, createdAt = at, updatedAt = at, category = category)

    // ---- secrets ----

    @Test
    fun `ordinary facts are not secrets`() {
        for (fact in listOf(
            "Lives in Dhaka",
            "Prefers British spelling",
            "Uses a password manager called Bitwarden",
            "Works at a bank as a data analyst",
            "Phone number is +880 1712 345678",
            "Their area code is 0171",
            "Has a dog named Max",
            "Birthday is 12 March 1994",
            "Email is someone@example.com",
            "Writes in Bengali and English",
        )) {
            assertNull(fact, AiMemoryText.secretReason(fact))
        }
    }

    @Test
    fun `a named secret with its value is refused`() {
        assertEquals(AiMemoryText.SecretKind.NAMED, AiMemoryText.secretReason("My password is hunter22"))
        assertEquals(AiMemoryText.SecretKind.NAMED, AiMemoryText.secretReason("Bank PIN: 4821"))
        assertEquals(AiMemoryText.SecretKind.NAMED, AiMemoryText.secretReason("wifi passcode = coffee-time-99"))
        assertEquals(AiMemoryText.SecretKind.NAMED, AiMemoryText.secretReason("Their API key is abcdef"))
    }

    @Test
    fun `one-time codes are refused, bare or in words`() {
        assertEquals(AiMemoryText.SecretKind.CODE, AiMemoryText.secretReason("482913"))
        assertEquals(AiMemoryText.SecretKind.CODE, AiMemoryText.secretReason("The login code was 482913"))
        assertEquals(AiMemoryText.SecretKind.CODE, AiMemoryText.secretReason("OTP 5512 for the bank"))
    }

    @Test
    fun `card numbers are refused only when they pass Luhn`() {
        assertEquals(AiMemoryText.SecretKind.CARD, AiMemoryText.secretReason("Visa card 4111 1111 1111 1111"))
        assertEquals(AiMemoryText.SecretKind.CARD, AiMemoryText.secretReason("card: 5500-0000-0000-0004"))
        // One digit off: not a card number.
        assertNull(AiMemoryText.secretReason("Order number 4111 1111 1111 1112"))
        assertTrue(AiMemoryText.luhn("79927398713"))
        assertFalse(AiMemoryText.luhn("79927398710"))
    }

    @Test
    fun `keys in provider formats and generated tokens are refused`() {
        assertEquals(AiMemoryText.SecretKind.KEY, AiMemoryText.secretReason("Uses sk-abcdefghijklmnop1234 for OpenAI"))
        assertEquals(
            AiMemoryText.SecretKind.KEY,
            AiMemoryText.secretReason("token ghp_abcdefghijklmnopqrstuvwxyz0123456789"),
        )
        assertEquals(AiMemoryText.SecretKind.KEY, AiMemoryText.secretReason("Router login Xk9#mP2qL7vR"))
        // Mixed, but a link and an address are not keys.
        assertNull(AiMemoryText.secretReason("Blog at https://Example.com/Posts/2024-05"))
        assertNull(AiMemoryText.secretReason("Work mail First.Last2024@Company.com"))
    }

    // ---- sameness ----

    @Test
    fun `equal after normalising is the same fact`() {
        assertEquals(AiMemoryText.Match.SAME, AiMemoryText.compare("Lives in Dhaka.", "lives in  dhaka"))
    }

    @Test
    fun `a fact that says less is the same, one that says more is newer`() {
        assertEquals(AiMemoryText.Match.NEWER, AiMemoryText.compare("Likes tea", "Likes green tea"))
        assertEquals(AiMemoryText.Match.SAME, AiMemoryText.compare("User likes green tea in the morning", "Likes green tea"))
    }

    @Test
    fun `different facts that share words stay apart`() {
        assertEquals(AiMemoryText.Match.NONE, AiMemoryText.compare("Has a dog named Max", "Has a cat named Max"))
        assertEquals(AiMemoryText.Match.NONE, AiMemoryText.compare("Lives in Dhaka", "Lives in Paris"))
        assertEquals(AiMemoryText.Match.NONE, AiMemoryText.compare("Likes tea", "Works at a school"))
    }

    @Test
    fun `normalising keeps letters of every script`() {
        assertEquals("ঢাকায় থাকে", AiMemoryText.normalize("ঢাকায় থাকে।"))
        assertEquals(AiMemoryText.Match.SAME, AiMemoryText.compare("ঢাকায় থাকে", "ঢাকায়  থাকে।"))
    }

    // ---- picking ----

    @Test
    fun `words rank memories by what they share with the request`() {
        val memories = listOf(
            memory(1, "Lives in Dhaka"),
            memory(2, "Allergic to peanuts"),
            memory(3, "Travelling to Japan in May", category = "travel"),
        )
        val ranked = AiMemoryText.rankByWords(memories, "plan my travel to Japan")
        assertEquals(listOf(3L), ranked.map { it.id })
        assertTrue(AiMemoryText.rankByWords(memories, "the of a").isEmpty())
    }

    @Test
    fun `few memories go whole, oldest first`() {
        val memories = listOf(memory(2, "B", at = 20), memory(1, "A", at = 10))
        assertEquals(listOf(1L, 2L), AiMemoryText.pick(memories, emptyList()).map { it.id })
    }

    @Test
    fun `many memories go as matches first, then the newest, up to the cap`() {
        val memories = (1L..60L).map { memory(it, "Fact number $it", at = it) }
        val matches = listOf(memories[4], memories[9])
        val picked = AiMemoryText.pick(memories, matches)
        assertEquals(AiMemoryText.PICKED, picked.size)
        assertEquals(listOf(5L, 10L), picked.take(2).map { it.id })
        // Then the newest, without the matches twice.
        assertEquals(60L, picked[2].id)
        assertEquals(picked.size, picked.map { it.id }.toSet().size)
    }

    @Test
    fun `too much text also counts as many`() {
        val long = "x".repeat(AiMemoryText.MAX_CHARS)
        val memories = (1L..11L).map { memory(it, "$long $it") }
        assertFalse(AiMemoryText.fitsWhole(memories))
    }

    // ---- prompt ----

    @Test
    fun `nothing saved means nothing in the prompt`() {
        assertEquals("", AiMemoryText.promptBlock(emptyList(), 0, forChat = true))
    }

    @Test
    fun `a chat's block numbers each fact and carries its category`() {
        val block = AiMemoryText.promptBlock(
            listOf(memory(3, "Lives in Dhaka"), memory(7, "Likes tea", category = "food")),
            total = 2,
            forChat = true,
        )
        assertTrue(block.startsWith("\n\n"))
        assertTrue("- [3] Lives in Dhaka" in block)
        assertTrue("- [7] Likes tea (food)" in block)
        assertFalse("closest" in block)
    }

    @Test
    fun `an action's block has no numbers and says to keep the facts out of the text`() {
        val block = AiMemoryText.promptBlock(listOf(memory(3, "Lives in Dhaka")), total = 1, forChat = false)
        assertTrue("- Lives in Dhaka" in block)
        assertFalse("[3]" in block)
        assertTrue("do not add it to the text" in block)
    }

    @Test
    fun `a partial block says how many there are in all`() {
        val block = AiMemoryText.promptBlock(listOf(memory(1, "A")), total = 40, forChat = true)
        assertTrue("1 closest to this request, of 40" in block)
    }

    @Test
    fun `the rules forbid secrets and name both tools`() {
        val rules = AiMemoryText.CHAT_RULES
        assertTrue("remember" in rules && "forget" in rules)
        assertTrue("Never save a secret" in rules)
        assertNotNull(rules)
    }
}
