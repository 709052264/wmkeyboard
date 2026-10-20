package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/** The provider list drags only its turned-on rows; the others keep their places. */
class ProviderListOrderTest {

    private fun chain(vararg rows: Pair<String, Boolean>) =
        ProviderChain(entries = rows.map { (id, on) -> ProviderEntry(id, enabled = on) })

    @Test
    fun `turned-on rows take the new order in their own places`() {
        val before = chain("a" to true, "b" to false, "c" to true, "d" to true)
        val after = before.withOnOrder(listOf("d", "a", "c"))
        assertEquals(listOf("d", "b", "a", "c"), after.entries.map { it.typeId })
        assertEquals(listOf(true, false, true, true), after.entries.map { it.enabled })
    }

    @Test
    fun `an order naming nothing listed changes nothing`() {
        val before = chain("a" to true, "b" to true)
        assertEquals(before, before.withOnOrder(listOf("x")))
    }

    @Test
    fun `a second server's key names its kind`() {
        assertEquals(ProviderTypes.LIBRETRANSLATE.id, providerKindOf("libretranslate:0a1b2c3d"))
        assertEquals(ProviderTypes.WHISPER.id, providerKindOf("whisper"))
    }
}
