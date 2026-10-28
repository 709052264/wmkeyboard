package com.wasimaster.wmkeyboard.core.settings.sink.proton

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.generators.OpenBSDBCrypt

/**
 * Proton's SRP-6a and password hashing, ported from go-srp, which Proton's
 * own clients use.
 *
 * Unlike textbook SRP everything is little-endian, the hash is
 * [expandHash] (four SHA-512s, 2048 bits), and the password goes through
 * bcrypt with a salt that has "proton" appended before it is stretched.
 */
object ProtonSrp {

    private const val BITS = 2048
    private const val BYTES = BITS / 8
    private const val BCRYPT_COST = 10
    private val random = SecureRandom()
    private val TWO = BigInteger.valueOf(2)

    /** The client's half of a sign-in: what goes to the server, and the proof the server should send back. */
    class Proofs(val clientEphemeral: ByteArray, val clientProof: ByteArray, val expectedServerProof: ByteArray)

    /** SHA-512 four times, each over the data and one counter byte, concatenated. */
    fun expandHash(data: ByteArray): ByteArray {
        val out = ByteArray(256)
        for (i in 0 until 4) {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(data)
            digest.update(i.toByte())
            System.arraycopy(digest.digest(), 0, out, i * 64, 64)
        }
        return out
    }

    private const val DOT_SLASH = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** bcrypt's own base64: its alphabet, no padding. */
    fun dotSlash(bytes: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(DOT_SLASH[b0 ushr 2])
            out.append(DOT_SLASH[((b0 and 3) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(DOT_SLASH[((b1 and 0xf) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) out.append(DOT_SLASH[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }

    private fun fromDotSlash(text: String, bytes: Int): ByteArray {
        val out = ByteArray(bytes)
        var bits = 0
        var acc = 0
        var at = 0
        for (c in text) {
            acc = (acc shl 6) or DOT_SLASH.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (at < bytes) out[at++] = (acc ushr bits).toByte()
            }
        }
        return out
    }

    /**
     * bcrypt with Go's interface: `$2y$10$` and the salt as bcrypt's 22
     * characters, the whole 60-character string back. Bouncy Castle takes
     * the salt as its 16 bytes. Passwords past 72 bytes are cut, as bcrypt
     * does.
     */
    fun bcrypt(password: ByteArray, encodedSalt: String): String {
        val salt = fromDotSlash(encodedSalt.take(22), 16)
        val key = if (password.size > 72) password.copyOf(72) else password
        return OpenBSDBCrypt.generate("2y", key, salt, BCRYPT_COST)
    }

    /** The key password: bcrypt over the key salt, the hash part alone (the last 31 characters). */
    fun saltedKeyPass(password: ByteArray, keySalt: ByteArray): String = bcrypt(password, dotSlash(keySalt)).takeLast(31)

    /** The SRP x, for auth versions 3 and 4, which every current account is. */
    fun hashPassword(password: ByteArray, salt: ByteArray, modulus: ByteArray): ByteArray {
        val crypted = bcrypt(password, dotSlash(salt + "proton".toByteArray()))
        return expandHash(crypted.toByteArray(Charsets.US_ASCII) + modulus)
    }

    /** Little-endian bytes to a number. */
    fun toInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    /** A number to [BYTES] little-endian bytes. */
    fun fromInt(n: BigInteger): ByteArray {
        val big = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val out = ByteArray(BYTES)
        for (i in big.indices) out[i] = big[big.size - 1 - i]
        return out
    }

    /**
     * The modulus text out of its clear-signed message. The signature itself
     * is not checked here; [proofs] instead checks the number is a safe prime
     * of the right shape, which is what the signature protects.
     */
    fun modulusOf(signed: String): ByteArray? {
        val lines = signed.replace("\r", "").lines()
        val start = lines.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 } ?: return null
        val end = lines.indexOfFirst { it.startsWith("-----BEGIN PGP SIGNATURE") }.takeIf { it > start } ?: return null
        val text = lines.subList(start + 1, end).joinToString("").trim()
        return runCatching { kotlin.io.encoding.Base64.decode(text) }.getOrNull()
    }

    /** Whether [n] is a modulus go-srp would accept: 2048 bits, 3 mod 8, a safe prime with 2 as generator. */
    fun validModulus(n: BigInteger): Boolean {
        if (n.bitLength() != BITS) return false
        if (!n.testBit(0) || !n.testBit(1) || n.testBit(2)) return false
        val half = n.shiftRight(1)
        if (!half.isProbablePrime(10)) return false
        return TWO.modPow(half, n) == n.subtract(BigInteger.ONE)
    }

    /**
     * The client proofs for a sign-in, or null when the server's numbers are
     * not acceptable. [secret] is for the tests; leave it out.
     */
    fun proofs(
        password: ByteArray,
        salt: ByteArray,
        modulusBytes: ByteArray,
        serverEphemeral: ByteArray,
        secret: BigInteger? = null,
    ): Proofs? {
        val n = toInt(modulusBytes)
        val b = toInt(serverEphemeral)
        val nMinusOne = n.subtract(BigInteger.ONE)
        if (!validModulus(n)) return null
        if (b <= BigInteger.ONE || b >= nMinusOne) return null

        val g = TWO
        val k = toInt(expandHash(fromInt(g) + fromInt(n))).mod(n)
        if (k <= BigInteger.ONE || k >= nMinusOne) return null
        val x = toInt(hashPassword(password, salt, modulusBytes))
        val bBytes = fromInt(b)

        fun pick(): BigInteger {
            val lower = BigInteger.valueOf(BITS * 2L)
            while (true) {
                val candidate = BigInteger(BITS, random).mod(nMinusOne)
                if (candidate > lower) return candidate
            }
        }
        var a = secret ?: pick()
        var aBytes = fromInt(g.modPow(a, n))
        var u = toInt(expandHash(aBytes + bBytes))
        while (u.signum() == 0 && secret == null) {
            a = pick()
            aBytes = fromInt(g.modPow(a, n))
            u = toInt(expandHash(aBytes + bBytes))
        }
        if (u.signum() == 0) return null

        val base = b.subtract(k.multiply(g.modPow(x, n)).mod(n)).mod(n)
        val exponent = u.multiply(x).mod(nMinusOne).add(a).mod(nMinusOne)
        val shared = fromInt(base.modPow(exponent, n))
        val clientProof = expandHash(aBytes + bBytes + shared)
        val serverProof = expandHash(aBytes + clientProof + shared)
        return Proofs(aBytes, clientProof, serverProof)
    }
}
