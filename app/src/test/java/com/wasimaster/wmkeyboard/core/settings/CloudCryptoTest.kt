package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.ICloudSrp
import com.wasimaster.wmkeyboard.core.settings.sink.MegaCrypto
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.XdrReader
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.XdrWriter
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonPgp
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonSrp
import kotlin.io.encoding.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-ported crypto of the MEGA, iCloud Drive and Proton Drive sinks.
 *
 * The expected values come from the code each port follows, run with the same
 * inputs: go-mega (MEGA), rclone's iclouddrive/api/srp.go (iCloud) and
 * Proton's go-srp (whose own test vectors the bcrypt cases are). go-srp's
 * server also accepted the proofs [ProtonSrp] made for its test modulus,
 * which is not repeatable here without Go.
 */
class CloudCryptoTest {

    @Test
    fun `MEGA password key, user hash and name match go-mega`() {
        val key = MegaCrypto.prepareKey("correct horse battery")
        assertEquals("zIL6_CnFYrZf2lHSZcCBew", MegaCrypto.b64encode(key))
        assertEquals("tJF4Tp-IoVg", MegaCrypto.stringHash("someone@example.com", key))
        val name = MegaCrypto.encryptName("0123456789abcdef".toByteArray(), "wmkeyboard-auto-x.json")
        assertEquals("rMxvZWwJjY9RcwNZk0gk5bhZqr-nKk1YQCQhJc8VpxEB3ztoww2tBLqQLZF0klYF", name)
        assertEquals("wmkeyboard-auto-x.json", MegaCrypto.decryptName("0123456789abcdef".toByteArray(), name))
    }

    @Test
    fun `MEGA file key and MAC match go-mega, and a changed byte fails`() {
        val data = ByteArray(1_500_000) { (it * 13).toByte() }
        val sealed = MegaCrypto.seal(data, intArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals("AAAABAAAAARBp6oiaXbN2wAAAAUAAAAGQaeqIWl2zd8", MegaCrypto.b64encode(MegaCrypto.toBytes(sealed.nodeKey)))
        assertArrayEquals(data, MegaCrypto.open(sealed.ciphertext, sealed.nodeKey))
        val changed = sealed.ciphertext.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertNull(MegaCrypto.open(changed, sealed.nodeKey))
    }

    @Test
    fun `MEGA chunk boundaries grow to a megabyte`() {
        val chunks = MegaCrypto.chunks(6_000_000)
        assertEquals(131_072L, chunks[0].second)
        assertEquals(262_144L, chunks[1].second)
        assertEquals(1_048_576L, chunks[8].second)
        assertEquals(6_000_000L, chunks.sumOf { it.second })
    }

    @Test
    fun `iCloud password stretching and SRP proofs match rclone`() {
        val salt = "test_salt_value!".toByteArray()
        assertEquals("i/2cgo7v6v1Ugs0abOEzUVQ0izEN2gCSvZNoiZkxh8M=", Base64.encode(ICloudSrp.derivePassword("test_password", salt, 1000, "s2k")!!))
        assertEquals("ihYILE9JmatxDYSaoUjSA/J3DhTkb8khfNS1waSMLcY=", Base64.encode(ICloudSrp.derivePassword("test_password", salt, 1000, "s2k_fo")!!))
        assertNull(ICloudSrp.derivePassword("p", salt, 1, "unknown"))
    }

    @Test
    fun `Proton bcrypt matches go-srp's vectors`() {
        assertEquals(
            "\$2y\$10\$PTTsDBs/mlLnSk6VmtFgheNSiK/lSwtJsrBLLDK3kZYI7193nInqy",
            ProtonSrp.bcrypt("test!!!".toByteArray(), "PTTsDBs/mlLnSk6VmtFghe"),
        )
        assertEquals(
            "\$2y\$10\$/.3KXCwRnsrxURMGxN7.R.GLpVq0zyBbI9wgS0wB2U/g2btx1RYoy",
            ProtonSrp.bcrypt("test!!!".toByteArray(), "/.3KXCwRnsrxURMGxN7.R."),
        )
        assertEquals(31, ProtonSrp.saltedKeyPass("pw".toByteArray(), ByteArray(16) { it.toByte() }).length)
    }

    @Test
    fun `Proton refuses a modulus that is not a safe prime`() {
        val signed = "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n" + Base64.encode(ByteArray(256) { 7 }) +
            "\n-----BEGIN PGP SIGNATURE-----\n\nx\n-----END PGP SIGNATURE-----"
        val modulus = ProtonSrp.modulusOf(signed)
        assertNotNull(modulus)
        assertNull(ProtonSrp.proofs("pw".toByteArray(), ByteArray(10), modulus!!, ByteArray(256) { 3 }))
    }

    @Test
    fun `Proton keys, names and blocks round trip`() {
        val pass = ProtonPgp.newPassphrase()
        val key = ProtonPgp.unlock(ProtonPgp.generateKey(pass), pass.toByteArray())!!
        assertNull(ProtonPgp.unlock(key.armored, "wrong".toByteArray()))
        val parent = ProtonPgp.unlock(ProtonPgp.generateKey("p"), "p".toByteArray())!!
        val name = ProtonPgp.encrypt(parent, "wmkeyboard-auto-x.json".toByteArray(), signer = key)
        assertEquals("wmkeyboard-auto-x.json", String(ProtonPgp.decryptArmored(name, listOf(key, parent))))
        val xattr = ProtonPgp.encrypt(key, "{}".toByteArray(), signer = key, compress = true)
        assertEquals("{}", String(ProtonPgp.decryptArmored(xattr, listOf(key))))

        val session = ProtonPgp.newSessionKey()
        val packet = ProtonPgp.keyPacket(key, session)
        assertArrayEquals(session, ProtonPgp.sessionKeyOf(packet, key))
        for (size in intArrayOf(0, 191, 192, 8383, 8384, 300_000)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val block = ProtonPgp.encryptWithSessionKey(session, data)
            assertArrayEquals(data, ProtonPgp.decrypt(packet + block, listOf(key)))
            val changed = block.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
            assertTrue(runCatching { ProtonPgp.decrypt(packet + changed, listOf(key)) }.isFailure)
        }
        assertTrue(ProtonPgp.signArmored(key, session).startsWith("-----BEGIN PGP SIGNATURE-----"))
        assertEquals(64, ProtonPgp.nameHash("k".toByteArray(), "n").length)
    }

    @Test
    fun `XDR pads to four bytes and reads back`() {
        val bytes = XdrWriter().int(-2).long(1L shl 40).bool(true).string("abcde").opaque(byteArrayOf(1, 2)).bytes()
        assertEquals(4 + 8 + 4 + (4 + 8) + (4 + 4), bytes.size)
        val r = XdrReader(bytes)
        assertEquals(-2, r.int())
        assertEquals(1L shl 40, r.long())
        assertTrue(r.bool())
        assertEquals("abcde", r.string())
        assertArrayEquals(byteArrayOf(1, 2), r.opaque())
    }
}
