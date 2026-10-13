package com.wasimaster.wmkeyboard.app.lock

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.AppLockMethod
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app's own PIN, password or pattern: how it is checked, stored and judged.
 *
 * Only a salted PBKDF2 hash is ever written, as
 * `pbkdf2-sha256$<iterations>$<salt hex>$<hash hex>`. The iteration count rides
 * along so a later build can raise it without locking anyone out: old hashes
 * keep verifying at the count they were made with.
 *
 * As with the rest of the lock, this guards screens, not data. The hash is
 * slow on purpose so that a stolen backup does not turn a six-digit PIN into
 * an instant answer, but a four-digit one is still ten thousand tries away;
 * the strings on the configurator say so.
 *
 * Hex rather than Base64 because `java.util.Base64` starts at API 26 and this
 * app at 24, and `android.util.Base64` is a stub on the unit-test JVM.
 */
internal object AppLockSecret {

    const val MIN_PIN = 4
    const val MAX_PIN = 16
    const val MIN_PASSWORD = 4
    const val MIN_PATTERN = 4

    /**
     * About a fifth of a second on a mid-range phone. Every check runs off the
     * main thread, so this costs a short pause on the sheet and nothing else.
     */
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val SCHEME = "pbkdf2-sha256"

    /** Whether [input] is a well-formed answer for [method]. */
    fun isAcceptable(method: AppLockMethod, input: String): Boolean = when (method) {
        AppLockMethod.PIN -> input.length in MIN_PIN..MAX_PIN && input.all { it in '0'..'9' }
        AppLockMethod.PASSWORD -> input.length >= MIN_PASSWORD && input.isNotBlank()
        AppLockMethod.PATTERN -> PatternCodec.isValid(input)
        AppLockMethod.BIOMETRIC -> false
    }

    /** A fresh hash of [secret]. Slow; call off the main thread. */
    fun hash(secret: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return "$SCHEME\$$iterations\$${salt.toHex()}\$${derive(secret, salt, iterations).toHex()}"
    }

    /**
     * Whether [input] is the secret behind [stored]. Slow; call off the main
     * thread. A [stored] value this build cannot read answers false rather
     * than throwing — the sheet's "Forgot?" path is the way past it.
     */
    fun verify(input: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = parts[2].fromHex() ?: return false
        val expected = parts[3].fromHex() ?: return false
        // Constant time, so how long a wrong answer takes says nothing about
        // how close it was.
        return MessageDigest.isEqual(derive(input, salt, iterations), expected)
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0 || isEmpty()) return null
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        }
        return out
    }
}

/**
 * A pattern as the string the hash is taken over: the dots in the order they
 * were joined, each its index 0–8 on the 3×3 grid, row by row.
 */
internal object PatternCodec {

    fun encode(dots: List<Int>): String = dots.joinToString("")

    fun isValid(encoded: String): Boolean =
        encoded.length >= AppLockSecret.MIN_PATTERN &&
            encoded.all { it in '0'..'8' } &&
            encoded.toSet().size == encoded.length

    /**
     * The dot a straight line from [from] to [to] passes over, if it is still
     * free — the way every Android pattern lock joins 0 to 2 through 1. Only
     * the middle of a row, column or diagonal can be jumped.
     */
    fun between(from: Int, to: Int): Int? {
        val (r1, c1) = from / 3 to from % 3
        val (r2, c2) = to / 3 to to % 3
        if ((r1 + r2) % 2 != 0 || (c1 + c2) % 2 != 0) return null
        val mid = (r1 + r2) / 2 * 3 + (c1 + c2) / 2
        return mid.takeIf { it != from && it != to }
    }
}

/**
 * How long a wrong answer makes the next one wait.
 *
 * Five free tries, then thirty seconds, doubling with every further miss up
 * to fifteen minutes. Persisted, so swiping the app out of recents does not
 * hand back five free tries; a success clears it. Kept in its own private
 * preferences file rather than DataStore: it describes this install's recent
 * past, and must neither travel in a backup nor wake every settings flow.
 */
internal class AppLockThrottle(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Milliseconds until the next try is allowed, or 0. */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        val failures = prefs.getInt(KEY_FAILURES, 0)
        var last = prefs.getLong(KEY_LAST, 0L)
        // A clock wound back past the last miss must not end the wait early:
        // restart it from now instead.
        if (last > now) {
            last = now
            prefs.edit().putLong(KEY_LAST, now).apply()
        }
        return (last + delayFor(failures) - now).coerceAtLeast(0L)
    }

    /** Records a miss and returns the wait it starts, or 0. */
    fun fail(now: Long = System.currentTimeMillis()): Long {
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        prefs.edit().putInt(KEY_FAILURES, failures).putLong(KEY_LAST, now).apply()
        return delayFor(failures)
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE = "app_lock_attempts"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LAST = "last_failure_at"

        const val FREE_TRIES = 5
        private const val FIRST_WAIT_MS = 30_000L
        private const val MAX_WAIT_MS = 15 * 60_000L

        /** The wait after [failures] misses in a row. */
        fun delayFor(failures: Int): Long {
            if (failures < FREE_TRIES) return 0L
            val doublings = (failures - FREE_TRIES).coerceAtMost(10)
            return (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
        }
    }
}
