package com.wasimaster.wmkeyboard.app.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier ids are a contract with two other places that cannot see this
 * file: the products created by hand in Play Console, and the server's
 * `PRODUCT` pattern in docs/functions/api/play-tip.ts, which turns away any
 * id that does not match it. A renamed tier would sell fine and then never
 * be credited.
 */
class TipTierTest {

    @Test
    fun everyIdMatchesWhatTheServerAccepts() {
        val server = Regex("^tip_\\d{1,3}$")
        TipTier.entries.forEach { assertTrue(it.productId, server.matches(it.productId)) }
    }

    @Test
    fun idsAreUniqueAndRoundTrip() {
        assertEquals(TipTier.entries.size, TipTier.entries.map { it.productId }.toSet().size)
        TipTier.entries.forEach { assertEquals(it, TipTier.of(it.productId)) }
        assertNull(TipTier.of("tip_unknown"))
    }

    @Test
    fun tiersRunFromSmallToLarge() {
        val prices = TipTier.entries.map { it.usd.toDouble() }
        assertEquals(prices.sorted(), prices)
    }
}
