package com.wasimaster.wmkeyboard.core.send

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * RFC 8188's two vectors, then the shapes the vectors do not cover: record
 * boundaries, several records, the empty file, and damage.
 */
class EceTest {

    private val walrus = "I am the walrus".toByteArray()

    private fun decrypt(cipher: ByteArray, ikm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Ece.decrypt(cipher.inputStream(), ikm, out)
        return out.toByteArray()
    }

    @Test
    fun `RFC 8188 section 3-1 decrypts`() {
        val ikm = Base64Url.decode("yqdlZ-tYemfogSmv7Ws5PQ")
        val cipher = Base64Url.decode("I1BsxtFttlv3u_Oo94xnmwAAEAAA-NAVub2qFgBEuQKRapoZu-IxkIva3MEB1PD-ly8Thjg")
        assertArrayEquals(walrus, decrypt(cipher, ikm))
    }

    @Test
    fun `RFC 8188 section 3-1 encrypts byte for byte with the vector's salt`() {
        val ikm = Base64Url.decode("yqdlZ-tYemfogSmv7Ws5PQ")
        val salt = Base64Url.decode("I1BsxtFttlv3u_Oo94xnmw")
        val cipher = Ece.encrypt(walrus, ikm, rs = 4096, salt = salt)
        assertEquals("I1BsxtFttlv3u_Oo94xnmwAAEAAA-NAVub2qFgBEuQKRapoZu-IxkIva3MEB1PD-ly8Thjg", Base64Url.encode(cipher))
    }

    @Test
    fun `RFC 8188 section 3-2 decrypts across two records and a key id`() {
        val ikm = Base64Url.decode("BO3ZVPxUlnLORbVGMpbT1Q")
        val cipher = Base64Url.decode("uNCkWiNYzKTnBN9ji3-qWAAAABkCYTHOG8chz_gnvgOqdGYovxyjuqRyJFjEDyoF1Fvkj6hQPdPHI51OEUKEpgz3SsLWIqS_uA")
        assertArrayEquals(walrus, decrypt(cipher, ikm))
    }

    @Test
    fun `encrypted size counts the header and one delimiter plus tag per record`() {
        val rs = 25
        assertEquals(21L + 17, Ece.encryptedSize(0, rs))
        assertEquals(21L + 1 + 17, Ece.encryptedSize(1, rs))
        assertEquals(21L + 8 + 17, Ece.encryptedSize(8, rs))
        assertEquals(21L + 9 + 2 * 17, Ece.encryptedSize(9, rs))
        assertEquals(21L + 15 + 2 * 17, Ece.encryptedSize(15, rs))
        assertEquals(21L + 1_000_000 + 16 * 17, Ece.encryptedSize(1_000_000))
    }

    @Test
    fun `round trips at every record boundary`() {
        val ikm = ByteArray(16) { it.toByte() }
        val rs = 64
        val chunk = rs - 17
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 1, 10_000)) {
            val plain = ByteArray(size) { (it * 31 + 7).toByte() }
            val cipher = Ece.encrypt(plain, ikm, rs)
            assertEquals("size $size", Ece.encryptedSize(size.toLong(), rs), cipher.size.toLong())
            assertArrayEquals("size $size", plain, decrypt(cipher, ikm))
        }
    }

    @Test
    fun `the stream form yields the same bytes however it is read`() {
        val ikm = ByteArray(16) { 3 }
        val plain = ByteArray(200_000) { (it % 251).toByte() }
        val salt = ByteArray(16) { 9 }
        val whole = Ece.encrypt(plain, ikm, salt = salt)
        val stream = EceEncryptingInputStream(plain.inputStream(), ikm, salt = salt)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1234)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        assertArrayEquals(whole, out.toByteArray())
        assertArrayEquals(plain, decrypt(whole, ikm))
    }

    @Test
    fun `a bare header is an empty file, as Send writes it`() {
        val ikm = ByteArray(16)
        val header = Ece.header(ByteArray(16) { 1 }, Ece.RECORD_SIZE)
        assertArrayEquals(ByteArray(0), decrypt(header, ikm))
    }

    @Test
    fun `a flipped byte is a wrong key, a cut stream is corrupt`() {
        val ikm = ByteArray(16) { 5 }
        val cipher = Ece.encrypt(walrus, ikm, rs = 64)
        val flipped = cipher.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
        assertEquals(SendError.WRONG_KEY, assertThrows(SendException::class.java) { decrypt(flipped, ikm) }.error)
        val wrongKey = ByteArray(16) { 6 }
        assertEquals(SendError.WRONG_KEY, assertThrows(SendException::class.java) { decrypt(cipher, wrongKey) }.error)
        assertEquals(SendError.CORRUPT, assertThrows(SendException::class.java) { decrypt(cipher.copyOf(10), ikm) }.error)
    }

    @Test
    fun `a middle record claiming to be last is refused`() {
        val ikm = ByteArray(16) { 8 }
        val rs = 64
        val plain = ByteArray(2 * (rs - 17)) { 1 }
        val cipher = Ece.encrypt(plain, ikm, rs)
        // Drop the second record: the first now ends the stream but carries delimiter 1.
        val cut = cipher.copyOf(21 + rs)
        assertEquals(SendError.CORRUPT, assertThrows(SendException::class.java) { decrypt(cut, ikm) }.error)
    }
}
