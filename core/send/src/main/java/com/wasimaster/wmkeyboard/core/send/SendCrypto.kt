package com.wasimaster.wmkeyboard.core.send

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Base64 as Send writes it in a link: the URL alphabet, no padding. Decoding
 * takes both alphabets and any padding, since a link may have been through a
 * chat app that "fixed" it.
 */
object Base64Url {
    fun encode(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        val cleaned = text.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return java.util.Base64.getUrlDecoder().decode(padded)
    }
}

/** HMAC-SHA256, the one primitive everything below is built from. */
internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    // An empty key is legal for HMAC but not for SecretKeySpec; HKDF's
    // "no salt" case pads to the hash length instead, so this never sees one.
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/**
 * HKDF (RFC 5869) over SHA-256. Written out rather than taken from a provider
 * because Android has no HKDF API below 34 and this is eleven lines.
 */
object Hkdf {
    private const val HASH_LENGTH = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LENGTH)) { "HKDF length $length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - filled)
            System.arraycopy(previous, 0, out, filled, take)
            filled += take
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}

/**
 * PBKDF2 with HMAC-SHA256 (RFC 8018). Hand-rolled for the same reason: the
 * provider's `PBKDF2WithHmacSHA256` arrives at API 26 and this floor is 24.
 * Send uses it with 100 iterations, which is a key-stretching gesture rather
 * than a defence; the secret in the link is what protects the file.
 */
object Pbkdf2 {
    fun sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations > 0 && length > 0)
        val out = ByteArray(length)
        var filled = 0
        var block = 1
        while (filled < length) {
            val counter = byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte(),
            )
            var u = hmacSha256(password, salt + counter)
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = hmacSha256(password, u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, take)
            filled += take
            block++
        }
        return out
    }
}

/**
 * The keys one share link stands for.
 *
 * Everything derives from the 16-byte [secret] that travels in the link's
 * fragment and never reaches the server: a metadata key for the envelope that
 * names the file, an authentication key the server challenges with a nonce
 * (replaced by a password-derived one when the owner sets a password), and the
 * content key, which [Ece] derives per upload with its own salt.
 */
class SendKeys(val secret: ByteArray) {
    init {
        require(secret.size == SECRET_LENGTH) { "a Send secret is $SECRET_LENGTH bytes, got ${secret.size}" }
    }

    val secretB64: String get() = Base64Url.encode(secret)

    val metadataKey: ByteArray = Hkdf.derive(secret, ByteArray(0), METADATA_INFO, METADATA_KEY_LENGTH)

    /** 64 bytes: WebCrypto's default HMAC key length, which is what Send derives. */
    var authKey: ByteArray = Hkdf.derive(secret, ByteArray(0), AUTH_INFO, AUTH_KEY_LENGTH)
        private set

    val authKeyB64: String get() = Base64Url.encode(authKey)

    /**
     * Replaces the auth key with one stretched from [password]. The salt is
     * the *whole* share URL, fragment included, exactly as the web client
     * sees it in its address bar — so the canonical form from [SendLink.url]
     * has to be used on both sides.
     */
    fun setPassword(password: String, shareUrl: String) {
        authKey = Pbkdf2.sha256(
            password.toByteArray(Charsets.UTF_8),
            shareUrl.toByteArray(Charsets.UTF_8),
            PASSWORD_ITERATIONS,
            AUTH_KEY_LENGTH,
        )
    }

    fun setAuthKey(bytes: ByteArray) {
        authKey = bytes
    }

    /** `Authorization: send-v1 <HMAC(authKey, nonce)>` for the nonce the server last handed out. */
    fun authHeader(nonceB64: String): String =
        "send-v1 " + Base64Url.encode(hmacSha256(authKey, Base64Url.decode(nonceB64)))

    /** The metadata envelope: AES-128-GCM under the metadata key, a zero IV (one message per key), 128-bit tag. */
    fun encryptMetadata(metadata: SendMetadata): String {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(metadataKey, "AES"), GCMParameterSpec(TAG_BITS, ByteArray(GCM_IV_LENGTH)))
        return Base64Url.encode(cipher.doFinal(metadata.toJson().toByteArray(Charsets.UTF_8)))
    }

    /** Throws [SendException] (`WRONG_KEY`) when the link's secret does not fit the server's envelope. */
    fun decryptMetadata(b64: String): SendMetadata {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(metadataKey, "AES"), GCMParameterSpec(TAG_BITS, ByteArray(GCM_IV_LENGTH)))
        val plain = try {
            cipher.doFinal(Base64Url.decode(b64))
        } catch (e: GeneralSecurityException) {
            throw SendException(SendError.WRONG_KEY, cause = e)
        } catch (e: IllegalArgumentException) {
            throw SendException(SendError.WRONG_KEY, cause = e)
        }
        return SendMetadata.fromJson(String(plain, Charsets.UTF_8))
    }

    companion object {
        const val SECRET_LENGTH = 16
        private const val METADATA_KEY_LENGTH = 16
        private const val AUTH_KEY_LENGTH = 64
        private const val PASSWORD_ITERATIONS = 100
        private const val GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val TAG_BITS = 128
        private val METADATA_INFO = "metadata".toByteArray(Charsets.US_ASCII)
        private val AUTH_INFO = "authentication".toByteArray(Charsets.US_ASCII)

        fun random(): SendKeys = SendKeys(ByteArray(SECRET_LENGTH).also { SecureRandom().nextBytes(it) })

        /** Null for a fragment that is not a 16-byte secret. */
        fun fromB64(text: String): SendKeys? =
            runCatching { Base64Url.decode(text) }.getOrNull()?.takeIf { it.size == SECRET_LENGTH }?.let(::SendKeys)
    }
}
