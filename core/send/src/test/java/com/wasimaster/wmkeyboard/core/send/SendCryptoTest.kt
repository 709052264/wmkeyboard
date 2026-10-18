package com.wasimaster.wmkeyboard.core.send

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The primitives, pinned against published vectors and the JDK's own
 * implementations: a key derived one byte differently decrypts nothing, and
 * nothing else in the module would say why.
 */
class SendCryptoTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `HKDF with no salt uses a zero salt of the hash length`() {
        val ikm = ByteArray(16) { 7 }
        assertArrayEquals(Hkdf.derive(ikm, ByteArray(0), "x".toByteArray(), 16), Hkdf.derive(ikm, ByteArray(32), "x".toByteArray(), 16))
    }

    @Test
    fun `PBKDF2 agrees with the JDK`() {
        val password = "correct horse"
        val salt = "https://send.vis.ee/download/0123456789ab/#AAAAAAAAAAAAAAAAAAAAAA".toByteArray()
        val ours = Pbkdf2.sha256(password.toByteArray(), salt, 100, 64)
        val theirs = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 100, 64 * 8)).encoded
        assertArrayEquals(theirs, ours)
    }

    @Test
    fun `PBKDF2 spans several blocks when the length asks for it`() {
        val salt = "s".toByteArray()
        val ours = Pbkdf2.sha256("p".toByteArray(), salt, 3, 70)
        val theirs = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("p".toCharArray(), salt, 3, 70 * 8)).encoded
        assertArrayEquals(theirs, ours)
    }

    @Test
    fun `base64url strips padding and decodes either alphabet`() {
        val bytes = byteArrayOf(-5, -1, 0, 62, 63)
        val encoded = Base64Url.encode(bytes)
        assertTrue(encoded, !encoded.contains('=') && !encoded.contains('+') && !encoded.contains('/'))
        assertArrayEquals(bytes, Base64Url.decode(encoded))
        assertArrayEquals(bytes, Base64Url.decode(java.util.Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `a secret is sixteen bytes, nothing else`() {
        assertNotNull(SendKeys.fromB64("yqdlZ-tYemfogSmv7Ws5PQ"))
        assertNull(SendKeys.fromB64("yqdlZ-tYemfogSmv7Ws5PQAA"))
        assertNull(SendKeys.fromB64("not base64 at all!"))
    }

    @Test
    fun `metadata round trips and a wrong key is told apart from damage`() {
        val keys = SendKeys.fromB64("yqdlZ-tYemfogSmv7Ws5PQ")!!
        val metadata = SendMetadata.forFiles(listOf(SendFileEntry("a.txt", 3, "text/plain"), SendFileEntry("b.png", 10, "image/png")))
        val envelope = keys.encryptMetadata(metadata)
        val back = keys.decryptMetadata(envelope)
        assertEquals(metadata, back)
        assertTrue(back.isArchive)
        assertEquals(SendMetadata.ARCHIVE_NAME, back.name)

        val other = SendKeys.fromB64("BO3ZVPxUlnLORbVGMpbT1Q")!!
        val failure = assertThrows(SendException::class.java) { other.decryptMetadata(envelope) }
        assertEquals(SendError.WRONG_KEY, failure.error)
    }

    @Test
    fun `auth keys derive as Send derives them and the header signs the nonce`() {
        val keys = SendKeys.fromB64("yqdlZ-tYemfogSmv7Ws5PQ")!!
        assertEquals(64, keys.authKey.size)
        assertEquals(16, keys.metadataKey.size)
        val nonce = Base64Url.encode(ByteArray(16) { 1 })
        val header = keys.authHeader(nonce)
        assertTrue(header, header.startsWith("send-v1 "))
        val signature = Base64Url.decode(header.removePrefix("send-v1 "))
        assertArrayEquals(hmacSha256(keys.authKey, ByteArray(16) { 1 }), signature)

        val before = keys.authKey.copyOf()
        keys.setPassword("pw", "https://send.vis.ee/download/0123456789ab/#yqdlZ-tYemfogSmv7Ws5PQ")
        assertEquals(64, keys.authKey.size)
        assertTrue(!before.contentEquals(keys.authKey))
    }

    @Test
    fun `metadata JSON is read leniently`() {
        val old = SendMetadata.fromJson("""{"name":"photo%20of%20us.jpg","size":"1234","type":"image/jpeg"}""")
        assertEquals("photo of us.jpg", old.name)
        assertEquals(1234L, old.size)
        assertEquals(listOf(SendFileEntry("photo of us.jpg", 1234, "image/jpeg")), old.saved)

        val archive = SendMetadata.fromJson(
            """{"name":"Send-Archive.zip","size":13,"type":"send-archive","manifest":{"files":[{"name":"a","size":3,"type":"text/plain"},{"name":"b+c","size":10,"type":""}]}}""",
        )
        assertEquals(2, archive.saved.size)
        assertEquals("b+c", archive.saved[1].name)
        assertEquals(OCTET_STREAM, archive.saved[1].type)
    }
}
