package com.wasimaster.wmkeyboard.core.settings.sink.proton

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator

/**
 * The OpenPGP Proton Drive needs, on Bouncy Castle's lightweight API.
 *
 * Proton Drive keeps everything as OpenPGP: each file and folder has a key of
 * its own whose passphrase is encrypted to its parent's key, names are
 * messages encrypted to the parent and signed by the user's address key, and
 * file content is one session key's worth of encrypted blocks. What is here
 * is that much and no more, in the shapes Proton's own SDK produces: v4 keys
 * on Curve25519 (EdDSA for signing, ECDH for encryption), SEIPD version 1,
 * AES-256.
 *
 * Signatures are made everywhere Proton asks for one, and not checked on
 * what comes back: the sink only reads what it wrote itself, over TLS, and a
 * name that does not decrypt is skipped either way.
 */
object ProtonPgp {

    private val random = SecureRandom()
    private val fingerprints = BcKeyFingerprintCalculator()
    private val digests = BcPGPDigestCalculatorProvider()

    /** An unlocked private key: what decrypts with it, what signs with it, and what encrypts to it. */
    class Key internal constructor(
        val ring: PGPSecretKeyRing,
        private val privates: Map<Long, PGPPrivateKey>,
    ) {
        /** The key that signs: the primary, which is EdDSA (or RSA on old accounts). */
        val signing: PGPPrivateKey get() = privates[ring.publicKey.keyID] ?: privates.values.first()

        val signingPublic: PGPPublicKey get() = ring.getPublicKey(signing.keyID)

        /** The key messages to this one are encrypted to: the newest encryption-capable one. */
        val encryption: PGPPublicKey
            get() = ring.publicKeys.asSequence().lastOrNull { it.isEncryptionKey } ?: ring.publicKey

        internal fun privateFor(keyId: Long): PGPPrivateKey? = privates[keyId]

        val armored: String get() = armor(ring.encoded)
    }

    /** Unlocks an armored private key with its passphrase, or null when it does not open. */
    fun unlock(armored: String, passphrase: ByteArray): Key? = runCatching {
        val ring = PGPSecretKeyRing(unarmor(armored), fingerprints)
        val decryptor = BcPBESecretKeyDecryptorBuilder(digests).build(String(passphrase, Charsets.UTF_8).toCharArray())
        val privates = HashMap<Long, PGPPrivateKey>()
        for (secret in ring.secretKeys) {
            if (secret.isPrivateKeyEmpty) continue
            runCatching { secret.extractPrivateKey(decryptor) }.getOrNull()?.let { privates[secret.keyID] = it }
        }
        if (privates.isEmpty()) null else Key(ring, privates)
    }.getOrNull()

    /**
     * A new node key, as Proton's apps make them: EdDSA primary, ECDH subkey,
     * user id "Drive key", locked with [passphrase]. Armored.
     */
    fun generateKey(passphrase: String, now: Date = Date()): String {
        val signGen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
        val encGen = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }
        val primary = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, signGen.generateKeyPair(), now)
        val sub = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDH, encGen.generateKeyPair(), now)
        val primaryFlags = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128))
            setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA512))
        }.generate()
        val subFlags = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        }.generate()
        val encryptor = BcPBESecretKeyEncryptorBuilder(
            SymmetricKeyAlgorithmTags.AES_256,
            digests.get(HashAlgorithmTags.SHA256),
            S2K_COUNT,
        ).setSecureRandom(random).build(passphrase.toCharArray())
        val generator = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            primary,
            "Drive key",
            digests.get(HashAlgorithmTags.SHA1),
            primaryFlags,
            null,
            BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.EDDSA_LEGACY, HashAlgorithmTags.SHA256),
            encryptor,
        )
        generator.addSubKey(sub, subFlags, null)
        return armor(generator.generateSecretKeyRing().encoded)
    }

    /** 32 random bytes in base64: a node key's passphrase, as the SDK makes it. */
    fun newPassphrase(): String = kotlin.io.encoding.Base64.encode(ByteArray(32).also { random.nextBytes(it) })

    fun newSessionKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    /** A public-key encrypted session key packet for an AES-256 [sessionKey], to [to]. Binary. */
    fun keyPacket(to: Key, sessionKey: ByteArray): ByteArray {
        val method = BcPublicKeyKeyEncryptionMethodGenerator(to.encryption).setSecureRandom(random)
        val packet = method.generate(BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256), sessionKey)
        return ByteArrayOutputStream().also { out -> BCPGOutputStream(out).use { packet.encode(it) } }.toByteArray()
    }

    /**
     * [data] as a literal data packet, encrypted under [sessionKey] as a
     * SEIPD v1 packet (AES-256 CFB, MDC), with no key packet in front: a
     * Drive block. Done by hand, because Bouncy Castle only encrypts under a
     * session key it makes itself.
     */
    fun encryptWithSessionKey(sessionKey: ByteArray, data: ByteArray): ByteArray {
        val literal = ByteArrayOutputStream().also { out ->
            PGPLiteralDataGenerator().open(out, PGPLiteralData.BINARY, "", data.size.toLong(), Date(0)).use { it.write(data) }
        }.toByteArray()
        val prefix = ByteArray(AES_BLOCK).also { random.nextBytes(it) }
        val plain = ByteArrayOutputStream().apply {
            write(prefix)
            write(prefix, AES_BLOCK - 2, 2)
            write(literal)
            write(MDC_HEADER)
        }.toByteArray()
        val mdc = MessageDigest.getInstance("SHA-1").digest(plain)
        val cipher = Cipher.getInstance("AES/CFB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(ByteArray(AES_BLOCK)))
        }
        val body = ByteArrayOutputStream().apply {
            write(SEIPD_VERSION)
            write(cipher.update(plain))
            write(cipher.doFinal(mdc))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0xC0 or SEIPD_TAG)
            writeNewLength(this, body.size)
            write(body)
        }.toByteArray()
    }

    private fun writeNewLength(out: ByteArrayOutputStream, length: Int) {
        when {
            length < 192 -> out.write(length)
            length < 8384 -> {
                val v = length - 192
                out.write((v shr 8) + 192)
                out.write(v and 0xff)
            }
            else -> {
                out.write(0xff)
                out.write(length ushr 24)
                out.write(length ushr 16)
                out.write(length ushr 8)
                out.write(length)
            }
        }
    }

    /**
     * Encrypts [data] to [to]; signed inside the message by [signer] when given
     * (a name, an XAttr), and compressed first when [compress]. Armored.
     */
    fun encrypt(to: Key, data: ByteArray, signer: Key? = null, compress: Boolean = false): String {
        val out = ByteArrayOutputStream()
        val generator = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setSecureRandom(random),
        )
        generator.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(to.encryption).setSecureRandom(random))
        generator.open(out, ByteArray(BUFFER)).use { encrypted ->
            val compressor = if (compress) PGPCompressedDataGenerator(CompressionAlgorithmTags.ZLIB) else null
            val inner = compressor?.open(encrypted) ?: encrypted
            val signature = signer?.let { key ->
                PGPSignatureGenerator(BcPGPContentSignerBuilder(key.signingPublic.algorithm, HashAlgorithmTags.SHA256), key.signingPublic)
                    .apply { init(PGPSignature.BINARY_DOCUMENT, key.signing) }
            }
            signature?.generateOnePassVersion(false)?.encode(inner)
            PGPLiteralDataGenerator().open(inner, PGPLiteralData.BINARY, "", data.size.toLong(), Date()).use { it.write(data) }
            signature?.apply { update(data) }?.generate()?.encode(inner)
            compressor?.close()
        }
        return armor(out.toByteArray())
    }

    /** A detached signature over [data] by [signer], binary. */
    fun sign(signer: Key, data: ByteArray): ByteArray {
        val generator = PGPSignatureGenerator(BcPGPContentSignerBuilder(signer.signingPublic.algorithm, HashAlgorithmTags.SHA256), signer.signingPublic)
        generator.init(PGPSignature.BINARY_DOCUMENT, signer.signing)
        generator.update(data)
        return generator.generate().encoded
    }

    fun signArmored(signer: Key, data: ByteArray): String = armor(sign(signer, data))

    /**
     * Decrypts a message, armored or binary, with whichever of [keys] it was
     * encrypted to, and checks its integrity packet. Signatures inside are
     * skipped over, not checked.
     */
    fun decrypt(message: ByteArray, keys: List<Key>): ByteArray {
        val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(message)))
        var first = factory.nextObject()
        while (first != null && first !is PGPEncryptedDataList) first = factory.nextObject()
        val list = first as? PGPEncryptedDataList ?: throw IllegalArgumentException("not an encrypted message")
        for (data in list) {
            val encrypted = data as? PGPPublicKeyEncryptedData ?: continue
            for (key in keys) {
                val private = key.privateFor(encrypted.keyID)
                    ?: (if (encrypted.keyID == 0L) key.privateFor(key.encryption.keyID) else null)
                    ?: continue
                val plain = runCatching { encrypted.getDataStream(BcPublicKeyDataDecryptorFactory(private)) }.getOrNull() ?: continue
                val data = literal(plain)
                // The MDC is only checked when asked, after the whole stream
                // has been read: a message cut short or changed fails here.
                plain.readBytes()
                if (!encrypted.isIntegrityProtected || !encrypted.verify()) throw IllegalArgumentException("integrity check failed")
                return data
            }
        }
        throw IllegalArgumentException("no key for this message")
    }

    /**
     * The session key a key packet carries, for [key]. Bouncy Castle reads a
     * key packet only as part of a message, so a throwaway data packet
     * follows it here; only the key packet is ever decrypted.
     */
    fun sessionKeyOf(keyPacket: ByteArray, key: Key): ByteArray? = runCatching {
        val message = keyPacket + encryptWithSessionKey(ByteArray(32), ByteArray(0))
        val factory = BcPGPObjectFactory(ByteArrayInputStream(message))
        val list = factory.nextObject() as? PGPEncryptedDataList ?: return null
        list.firstNotNullOfOrNull { data ->
            val encrypted = data as? PGPPublicKeyEncryptedData ?: return@firstNotNullOfOrNull null
            val private = key.privateFor(encrypted.keyID) ?: key.privateFor(key.encryption.keyID) ?: return@firstNotNullOfOrNull null
            runCatching { encrypted.getSessionKey(BcPublicKeyDataDecryptorFactory(private)).key }.getOrNull()
        }
    }.getOrNull()

    /** HMAC-SHA256 of [name] under a folder's hash key, in hex: a node's lookup hash. */
    fun nameHash(hashKey: ByteArray, name: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hashKey, "HmacSHA256"))
        return mac.doFinal(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun decryptArmored(message: String, keys: List<Key>): ByteArray = decrypt(message.toByteArray(Charsets.US_ASCII), keys)

    /** The literal data in a decrypted stream, through compression and past signatures. */
    private fun literal(stream: InputStream): ByteArray {
        var factory = BcPGPObjectFactory(stream)
        while (true) {
            when (val next = factory.nextObject() ?: throw IllegalArgumentException("no literal data")) {
                is PGPCompressedData -> factory = BcPGPObjectFactory(next.dataStream)
                is PGPLiteralData -> return next.inputStream.readBytes()
                is PGPOnePassSignatureList, is PGPSignatureList -> Unit
                else -> Unit
            }
        }
    }

    fun unarmor(text: String): ByteArray =
        ArmoredInputStream(ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII))).use { it.readBytes() }

    /** Armors OpenPGP packets; the header (message, signature, private key) follows the first packet. */
    fun armor(bytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream.builder().clearHeaders().build(out).use { it.write(bytes) }
        return out.toString(Charsets.US_ASCII.name())
    }

    private const val AES_BLOCK = 16
    private const val SEIPD_TAG = 18
    private const val SEIPD_VERSION = 1
    private const val BUFFER = 1 shl 16
    private const val S2K_COUNT = 0x60
    private val MDC_HEADER = byteArrayOf(0xD3.toByte(), 0x14)
}
