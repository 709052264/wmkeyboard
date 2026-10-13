package com.wasimaster.wmkeyboard.app.lock

import com.wasimaster.wmkeyboard.core.settings.AppLockMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSecretTest {

    // A low count: the test is about the format and the comparison, not the cost.
    private fun hash(secret: String) = AppLockSecret.hash(secret, iterations = 1_000)

    @Test
    fun `the right secret verifies and a wrong one does not`() {
        val stored = hash("2580")
        assertTrue(AppLockSecret.verify("2580", stored))
        assertFalse(AppLockSecret.verify("2581", stored))
        assertFalse(AppLockSecret.verify("", stored))
    }

    @Test
    fun `the secret itself is never in the stored form`() {
        val stored = hash("correct horse")
        assertFalse(stored.contains("correct horse"))
        assertTrue(stored.startsWith("pbkdf2-sha256$1000$"))
    }

    @Test
    fun `every hash is salted`() {
        assertNotEquals(hash("1234"), hash("1234"))
    }

    @Test
    fun `an unreadable stored value verifies nothing rather than throwing`() {
        for (bad in listOf("", "1234", "md5$1$00$00", "pbkdf2-sha256\$x\$00\$00", "pbkdf2-sha256\$1\$zz\$00", "pbkdf2-sha256$1$0$00")) {
            assertFalse(bad, AppLockSecret.verify("1234", bad))
        }
    }

    @Test
    fun `what each method accepts`() {
        assertTrue(AppLockSecret.isAcceptable(AppLockMethod.PIN, "0000"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.PIN, "123"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.PIN, "12a4"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.PIN, "1".repeat(17)))
        assertTrue(AppLockSecret.isAcceptable(AppLockMethod.PASSWORD, "abcd"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.PASSWORD, "    "))
        assertTrue(AppLockSecret.isAcceptable(AppLockMethod.PATTERN, "0124"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.PATTERN, "012"))
        assertFalse("a dot twice", AppLockSecret.isAcceptable(AppLockMethod.PATTERN, "01210"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.PATTERN, "0129"))
        assertFalse(AppLockSecret.isAcceptable(AppLockMethod.BIOMETRIC, "1234"))
    }

    @Test
    fun `a pattern line joins the dot it passes over`() {
        assertEquals(1, PatternCodec.between(0, 2))
        assertEquals(4, PatternCodec.between(0, 8))
        assertEquals(4, PatternCodec.between(2, 6))
        assertEquals(3, PatternCodec.between(0, 6))
        assertNull("neighbours", PatternCodec.between(0, 1))
        assertNull("a knight's move", PatternCodec.between(0, 5))
    }

    @Test
    fun `wrong answers wait longer and longer, up to a cap`() {
        for (n in 0 until AppLockThrottle.FREE_TRIES) assertEquals(0L, AppLockThrottle.delayFor(n))
        assertEquals(30_000L, AppLockThrottle.delayFor(5))
        assertEquals(60_000L, AppLockThrottle.delayFor(6))
        assertEquals(15 * 60_000L, AppLockThrottle.delayFor(40))
    }
}
