package com.wasimaster.wmkeyboard.core.send

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `aes128gcm` content encoding of RFC 8188, as Send applies it to a file.
 *
 * A header of salt, record size and key id, then records of `rs` bytes: each is
 * AES-128-GCM over up to `rs - 17` bytes of content followed by a delimiter
 * (0x01, or 0x02 on the last record) and any zero padding. The key and the
 * nonce base come from HKDF over the link's secret and the header's salt; the
 * record's nonce is the base with the record number XORed into its tail.
 *
 * Both directions stream: a file is never held in memory, and the upload can
 * begin before the file has been read. Send's own client writes a 64 KiB
 * record size and no key id; the reader honours whatever the header says,
 * so the RFC's own vectors (one of which carries a key id) decrypt too.
 */
object Ece {
    const val RECORD_SIZE = 64 * 1024
    const val TAG_LENGTH = 16
    const val KEY_LENGTH = 16
    const val NONCE_LENGTH = 12
    const val SALT_LENGTH = 16

    /** Salt, record size, key id length: what every header has before an (optional) key id. */
    const val HEADER_LENGTH = SALT_LENGTH + 4 + 1

    private const val DELIMITER = 1.toByte()
    private const val LAST_DELIMITER = 2.toByte()
    private val KEY_INFO = "Content-Encoding: aes128gcm\u0000".toByteArray(Charsets.US_ASCII)
    private val NONCE_INFO = "Content-Encoding: nonce\u0000".toByteArray(Charsets.US_ASCII)

    /** Bytes [plainSize] bytes of content occupy once encoded, with Send's header (no key id). */
    fun encryptedSize(plainSize: Long, rs: Int = RECORD_SIZE): Long {
        require(rs > TAG_LENGTH + 1)
        if (plainSize <= 0) return HEADER_LENGTH + (TAG_LENGTH + 1).toLong()
        val perRecord = (rs - TAG_LENGTH - 1).toLong()
        val records = (plainSize + perRecord - 1) / perRecord
        return HEADER_LENGTH + plainSize + records * (TAG_LENGTH + 1)
    }

    internal fun deriveKey(ikm: ByteArray, salt: ByteArray): ByteArray = Hkdf.derive(ikm, salt, KEY_INFO, KEY_LENGTH)

    internal fun deriveNonceBase(ikm: ByteArray, salt: ByteArray): ByteArray = Hkdf.derive(ikm, salt, NONCE_INFO, NONCE_LENGTH)

    internal fun nonce(base: ByteArray, seq: Long): ByteArray {
        require(seq in 0..0xffffffffL) { "record $seq: the sequence number has 32 bits" }
        val out = base.copyOf()
        val tail = NONCE_LENGTH - 4
        val current = ((out[tail].toLong() and 0xff) shl 24) or ((out[tail + 1].toLong() and 0xff) shl 16) or
            ((out[tail + 2].toLong() and 0xff) shl 8) or (out[tail + 3].toLong() and 0xff)
        val mixed = current xor seq
        out[tail] = (mixed ushr 24).toByte()
        out[tail + 1] = (mixed ushr 16).toByte()
        out[tail + 2] = (mixed ushr 8).toByte()
        out[tail + 3] = mixed.toByte()
        return out
    }

    internal fun header(salt: ByteArray, rs: Int, keyId: ByteArray = ByteArray(0)): ByteArray {
        require(salt.size == SALT_LENGTH)
        require(keyId.size < 256)
        return salt + byteArrayOf((rs ushr 24).toByte(), (rs ushr 16).toByte(), (rs ushr 8).toByte(), rs.toByte()) +
            byteArrayOf(keyId.size.toByte()) + keyId
    }

    internal fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH * 8, nonce))
        }

    /**
     * Encrypts [plain] in full to [out]: the whole header, then every record.
     * The streaming form is [EceEncryptingInputStream]; this one is for tests
     * and for small buffers such as a text snippet.
     */
    fun encrypt(plain: ByteArray, ikm: ByteArray, rs: Int = RECORD_SIZE, salt: ByteArray = randomSalt()): ByteArray =
        EceEncryptingInputStream(plain.inputStream(), ikm, rs, salt).readBytes()

    /**
     * Decrypts the whole of [input] to [out], reporting content bytes written
     * through [onProgress]. Fails with [SendException] (`WRONG_KEY`) on a tag
     * mismatch or bad padding, and `CORRUPT` on a truncated stream.
     */
    fun decrypt(input: InputStream, ikm: ByteArray, out: OutputStream, onProgress: (Long) -> Unit = {}) {
        val header = ByteArray(HEADER_LENGTH)
        readFully(input, header, 0, HEADER_LENGTH) ?: throw SendException(SendError.CORRUPT, detail = "no header")
        val salt = header.copyOfRange(0, SALT_LENGTH)
        val rs = ((header[16].toInt() and 0xff) shl 24) or ((header[17].toInt() and 0xff) shl 16) or
            ((header[18].toInt() and 0xff) shl 8) or (header[19].toInt() and 0xff)
        val idLength = header[20].toInt() and 0xff
        if (rs <= TAG_LENGTH + 1) throw SendException(SendError.CORRUPT, detail = "record size $rs")
        if (idLength > 0) {
            val skip = ByteArray(idLength)
            readFully(input, skip, 0, idLength) ?: throw SendException(SendError.CORRUPT, detail = "short key id")
        }
        val key = deriveKey(ikm, salt)
        val nonceBase = deriveNonceBase(ikm, salt)
        val record = ByteArray(rs)
        var seq = 0L
        var written = 0L
        // One record of lookahead: the last record is the one after which the
        // stream ends, and it is allowed to be shorter than `rs`.
        var size = readUpTo(input, record, rs)
        if (size == 0) return // Send writes a bare header for an empty file.
        while (true) {
            val next = ByteArray(rs)
            val nextSize = if (size < rs) 0 else readUpTo(input, next, rs)
            val last = nextSize == 0
            if (size <= TAG_LENGTH) throw SendException(SendError.CORRUPT, detail = "record $seq is $size bytes")
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, nonce(nonceBase, seq)).doFinal(record, 0, size)
            } catch (e: GeneralSecurityException) {
                throw SendException(SendError.WRONG_KEY, cause = e)
            }
            val content = unpad(plain, last)
            out.write(content)
            written += content.size
            onProgress(written)
            if (last) break
            System.arraycopy(next, 0, record, 0, rs)
            size = nextSize
            seq++
        }
    }

    private fun unpad(plain: ByteArray, last: Boolean): ByteArray {
        var i = plain.size - 1
        while (i >= 0 && plain[i] == 0.toByte()) i--
        if (i < 0) throw SendException(SendError.CORRUPT, detail = "no delimiter")
        val expected = if (last) LAST_DELIMITER else DELIMITER
        if (plain[i] != expected) throw SendException(SendError.CORRUPT, detail = "delimiter ${plain[i]} on ${if (last) "last" else "a middle"} record")
        return plain.copyOfRange(0, i)
    }

    internal fun randomSalt(): ByteArray = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }

    /** Reads exactly [length] bytes or returns null at a clean end before the first; throws on a partial read. */
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Unit? {
        var filled = 0
        while (filled < length) {
            val n = input.read(buffer, offset + filled, length - filled)
            if (n < 0) {
                if (filled == 0) return null
                throw SendException(SendError.CORRUPT, detail = "stream ended inside the header")
            }
            filled += n
        }
        return Unit
    }

    /** Reads until [buffer] holds [max] bytes or the stream ends; returns how many. */
    internal fun readUpTo(input: InputStream, buffer: ByteArray, max: Int): Int {
        var filled = 0
        while (filled < max) {
            val n = input.read(buffer, filled, max - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }
}

/**
 * [source] encrypted as it is read: the header first, then one record per
 * `rs - 17` bytes of content. The last record is known only once the source
 * ends, so one byte of lookahead decides which delimiter a record gets.
 */
class EceEncryptingInputStream(
    private val source: InputStream,
    ikm: ByteArray,
    private val rs: Int = Ece.RECORD_SIZE,
    salt: ByteArray = Ece.randomSalt(),
) : InputStream() {
    init {
        require(rs > Ece.TAG_LENGTH + 1) { "record size $rs" }
    }

    private val key = Ece.deriveKey(ikm, salt)
    private val nonceBase = Ece.deriveNonceBase(ikm, salt)
    private val chunk = rs - Ece.TAG_LENGTH - 1

    private var buffer: ByteArray = Ece.header(salt, rs)
    private var position = 0
    private var seq = 0L
    private var finished = false

    /** The byte read ahead of the current chunk, or -1 when the source has ended. */
    private var lookahead: Int = -2

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= buffer.size && !fill()) return -1
        val n = minOf(len, buffer.size - position)
        System.arraycopy(buffer, position, b, off, n)
        position += n
        return n
    }

    /** Encrypts the next record into [buffer]; false once the last one has gone out. */
    private fun fill(): Boolean {
        if (finished) return false
        val plain = ByteArray(chunk + 1)
        var filled = 0
        if (lookahead >= 0) {
            plain[0] = lookahead.toByte()
            filled = 1
        }
        if (lookahead != -1) filled += readInto(plain, filled, chunk - filled)
        val last: Boolean
        if (filled < chunk) {
            last = true
        } else {
            lookahead = source.read()
            last = lookahead < 0
        }
        plain[filled] = if (last) 2 else 1
        val cipher = Ece.cipher(Cipher.ENCRYPT_MODE, key, Ece.nonce(nonceBase, seq))
        buffer = cipher.doFinal(plain, 0, filled + 1)
        position = 0
        seq++
        finished = last
        return true
    }

    private fun readInto(target: ByteArray, offset: Int, max: Int): Int {
        var filled = 0
        while (filled < max) {
            val n = source.read(target, offset + filled, max - filled)
            if (n < 0) {
                lookahead = -1
                break
            }
            filled += n
        }
        return filled
    }

    @Throws(IOException::class)
    override fun close() {
        source.close()
    }
}
