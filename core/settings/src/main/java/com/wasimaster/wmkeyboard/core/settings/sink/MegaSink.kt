package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.MegaConfig
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A [BackupSink] over a folder in MEGA, speaking MEGA's own API the way its
 * apps do: every file is encrypted on the phone with a key of its own, and
 * MEGA only ever holds ciphertext and keys wrapped in the account's master
 * key. Ported from the open MEGA clients (go-mega, which rclone uses, and
 * megatools); MEGA publishes the protocol only as its SDK's source.
 *
 * - **Sign-in** happens once, on the settings screen ([MegaLogin]); the sink
 *   only has the session id and the master key it produced.
 * - **Upload**: AES-128-CTR over the file under a random key, a CBC-MAC per
 *   chunk folded into the file's MAC, then a `p` call that makes the node
 *   with its name encrypted under the same key. MEGA lets one folder hold
 *   two files of one name, so a write removes the older copies after.
 * - **Download** decrypts and checks the MAC, so a damaged file fails here
 *   rather than as a bundle that does not parse.
 * - **Delete** is permanent, not to the rubbish bin: MEGA counts the bin
 *   against the quota, and rotation would fill it.
 */
class MegaSink(private val config: MegaConfig) : BackupSink {

    override val id: String get() = ID

    private val api by lazy { MegaApi(config.sessionId) }

    private val master: ByteArray by lazy {
        runCatching { MegaCrypto.b64decode(config.masterKey) }.getOrNull()?.takeIf { it.size == MegaCrypto.KEY_BYTES }
            ?: throw BackupSinkException(SinkError.PERMISSION_LOST)
    }

    /** One node of the account's tree, decrypted. */
    private class Node(
        val handle: String,
        val parent: String,
        val type: Int,
        val name: String,
        val size: Long,
        val modifiedMs: Long,
        /** The whole decrypted key: 4 words for a folder, 8 for a file. */
        val key: IntArray,
    )

    @Volatile
    private var folderHandle: String? = null

    private fun nodes(): List<Node> {
        val answer = api.call(buildJsonObject { put("a", JsonPrimitive("f")); put("c", JsonPrimitive(1)) }).jsonObject
        return answer["f"]?.jsonArray.orEmpty().mapNotNull { decode(it.jsonObject) }
    }

    private fun decode(item: JsonObject): Node? {
        val type = item["t"]?.jsonPrimitive?.intOrNull ?: return null
        val handle = item["h"]?.jsonPrimitive?.contentOrNull ?: return null
        val parent = item["p"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type == TYPE_ROOT) return Node(handle, parent, type, "", 0L, 0L, IntArray(0))
        if (type != TYPE_FILE && type != TYPE_FOLDER) return null
        // "owner:key", or several of those split by '/' for shared items.
        // Only the user's own keys are wrapped in the master key.
        val owner = item["u"]?.jsonPrimitive?.contentOrNull ?: return null
        val wrapped = item["k"]?.jsonPrimitive?.contentOrNull?.split('/')
            ?.firstOrNull { it.substringBefore(':') == owner }?.substringAfter(':') ?: return null
        val raw = runCatching { MegaCrypto.ecbDecrypt(master, MegaCrypto.b64decode(wrapped)) }.getOrNull() ?: return null
        val key = MegaCrypto.toWords(raw)
        if (type == TYPE_FILE && key.size < FILE_KEY_WORDS) return null
        val name = item["a"]?.jsonPrimitive?.contentOrNull
            ?.let { MegaCrypto.decryptName(MegaCrypto.toBytes(MegaCrypto.nodeKey(key)), it) } ?: return null
        return Node(
            handle = handle,
            parent = parent,
            type = type,
            name = name,
            size = item["s"]?.jsonPrimitive?.longOrNull ?: -1L,
            modifiedMs = (item["ts"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L,
            key = key,
        )
    }

    /** The backup folder's handle, made level by level where missing, and the tree as it stood. */
    private fun folder(): Pair<String, List<Node>> {
        val all = nodes()
        folderHandle?.let { return it to all }
        var at = all.firstOrNull { it.type == TYPE_ROOT }?.handle ?: throw BackupSinkException(SinkError.TARGET_MISSING)
        for (part in config.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            at = all.firstOrNull { it.type == TYPE_FOLDER && it.parent == at && it.name == part }?.handle
                ?: makeFolder(at, part)
        }
        folderHandle = at
        return at to all
    }

    private fun makeFolder(parent: String, name: String): String {
        val key = MegaCrypto.random(MegaCrypto.KEY_BYTES)
        val answer = api.call(
            buildJsonObject {
                put("a", JsonPrimitive("p"))
                put("t", JsonPrimitive(parent))
                put(
                    "n",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("h", JsonPrimitive("xxxxxxxx"))
                                put("t", JsonPrimitive(TYPE_FOLDER))
                                put("a", JsonPrimitive(MegaCrypto.encryptName(key, name)))
                                put("k", JsonPrimitive(MegaCrypto.b64encode(MegaCrypto.ecbEncrypt(master, key))))
                            },
                        ),
                    ),
                )
                put("i", JsonPrimitive(MegaCrypto.requestId()))
            },
        ).jsonObject
        return answer["f"]?.jsonArray?.firstOrNull()?.jsonObject?.get("h")?.jsonPrimitive?.contentOrNull
            ?: throw BackupSinkException(SinkError.IO)
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
            folder()
            Unit
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val plain = ByteArrayOutputStream().also(body).toByteArray()
            val (parent, before) = folder()
            val target = api.call(
                buildJsonObject {
                    put("a", JsonPrimitive("u"))
                    put("s", JsonPrimitive(plain.size))
                    put("ssl", JsonPrimitive(2))
                },
            ).jsonObject["p"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO)

            val sealed = MegaCrypto.seal(plain)
            BackupLog.d("mega write $name ${plain.size} B")
            var completion = ""
            for ((at, length) in MegaCrypto.chunks(plain.size.toLong())) {
                val part = sealed.ciphertext.copyOfRange(at.toInt(), (at + length).toInt())
                completion = api.upload("$target/$at", part)
            }
            if (completion.isEmpty()) throw BackupSinkException(SinkError.IO)

            val made = api.call(
                buildJsonObject {
                    put("a", JsonPrimitive("p"))
                    put("t", JsonPrimitive(parent))
                    put(
                        "n",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("h", JsonPrimitive(completion))
                                    put("t", JsonPrimitive(TYPE_FILE))
                                    put("a", JsonPrimitive(MegaCrypto.encryptName(MegaCrypto.toBytes(sealed.fileKey.copyOf(4)), name)))
                                    put("k", JsonPrimitive(MegaCrypto.b64encode(MegaCrypto.ecbEncrypt(master, MegaCrypto.toBytes(sealed.nodeKey)))))
                                },
                            ),
                        ),
                    )
                    put("i", JsonPrimitive(MegaCrypto.requestId()))
                },
            ).jsonObject
            val handle = made["f"]?.jsonArray?.firstOrNull()?.jsonObject?.get("h")?.jsonPrimitive?.contentOrNull
                ?: throw BackupSinkException(SinkError.IO)
            // The older copies of this name, now that the new one is in.
            for (old in before.filter { it.parent == parent && it.type == TYPE_FILE && it.name == name && it.handle != handle }) {
                runCatching { remove(old.handle) }
            }
            SinkEntry(handle, name, plain.size.toLong(), System.currentTimeMillis())
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val (parent, all) = folder()
            all.filter { it.parent == parent && it.type == TYPE_FILE && AutoBackupNaming.isListed(it.name) }
                .map { SinkEntry(it.handle, it.name, it.size, it.modifiedMs) }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            val node = nodes().firstOrNull { it.handle == entry.id && it.type == TYPE_FILE }
                ?: throw BackupSinkException(SinkError.TARGET_MISSING)
            val answer = api.call(
                buildJsonObject {
                    put("a", JsonPrimitive("g"))
                    put("g", JsonPrimitive(1))
                    put("n", JsonPrimitive(node.handle))
                    put("ssl", JsonPrimitive(2))
                },
            ).jsonObject
            val url = answer["g"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO)
            val cipher = api.download(url)
            MegaCrypto.open(cipher, node.key)?.inputStream()
                ?: throw BackupSinkException(SinkError.IO).also { BackupLog.w("mega: MAC mismatch on ${entry.name}") }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable { remove(entry.id) }
    }

    private fun remove(handle: String) {
        try {
            api.call(
                buildJsonObject {
                    put("a", JsonPrimitive("d"))
                    put("n", JsonPrimitive(handle))
                    put("i", JsonPrimitive(MegaCrypto.requestId()))
                },
            )
        } catch (gone: BackupSinkException) {
            if (gone.reason != SinkError.TARGET_MISSING) throw gone
        }
    }

    companion object {
        const val ID = "mega"

        private const val TYPE_FILE = 0
        private const val TYPE_FOLDER = 1
        private const val TYPE_ROOT = 2
        private const val FILE_KEY_WORDS = 8

        /**
         * What a MEGA error number means to the user: -15 an expired session,
         * -16 a blocked account, -11 a refused access, -26 a 2FA code wanted;
         * -9 a missing node; -17 the account over its quota. Public for the
         * tests.
         */
        fun errorOf(code: Int): SinkError = when (code) {
            -11, -15, -16, -26 -> SinkError.PERMISSION_LOST
            -9 -> SinkError.TARGET_MISSING
            -17 -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }
    }
}

/** A MEGA error answer, by number. */
internal class MegaError(val code: Int) : Exception("mega error $code")

/**
 * MEGA's command channel: a JSON array of one command POSTed to `/cs`, a JSON
 * array of one answer back, where a bare negative number is an error. Busy
 * (-3) is retried; HTTP 402 is a proof-of-work challenge ([MegaCrypto.hashcash]).
 */
internal class MegaApi(private val sessionId: String?) {

    private val client: OkHttpClient by lazy { BackupHttp.client() }
    private val json = Json { ignoreUnknownKeys = true }
    private var sequence = SecureRandom().nextInt(Int.MAX_VALUE / 2).toLong()

    /** The answer to [command], or a [BackupSinkException] for an error. */
    fun call(command: JsonObject): JsonElement = try {
        callRaw(command)
    } catch (error: MegaError) {
        throw BackupSinkException(MegaSink.errorOf(error.code))
    }

    /** As [call], but a MEGA error is thrown as [MegaError], for the sign-in to read. */
    fun callRaw(command: JsonObject): JsonElement {
        val body = JsonArray(listOf(command)).toString()
        var wait = RETRY_START_MS
        repeat(RETRIES) {
            val url = buildString {
                append(API).append("/cs?id=").append(sequence++)
                if (!sessionId.isNullOrEmpty()) append("&sid=").append(sessionId)
            }
            var request = Request.Builder().url(url).post(body.toRequestBody(JSON_TYPE)).build()
            var response = execute(request)
            if (response.code == HTTP_PAYMENT_REQUIRED) {
                val challenge = response.header("X-Hashcash")
                response.close()
                val answer = challenge?.let { MegaCrypto.hashcash(it) } ?: throw BackupSinkException(SinkError.IO)
                request = request.newBuilder().header("X-Hashcash", answer).build()
                response = execute(request)
            }
            val text = response.use {
                if (!it.isSuccessful) {
                    BackupLog.w("mega api -> HTTP ${it.code}")
                    throw BackupSinkException(SinkError.IO)
                }
                it.body?.string().orEmpty()
            }
            val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: throw BackupSinkException(SinkError.IO)
            // A whole-request error is a bare number; a per-command one is
            // the number as the array's only element.
            val answer = (parsed as? JsonArray)?.firstOrNull() ?: parsed
            val code = (answer as? JsonPrimitive)?.intOrNull
            if (code != null && code < 0) {
                if (code == EAGAIN || code == ERATELIMIT) {
                    Thread.sleep(wait)
                    wait *= 2
                    return@repeat
                }
                BackupLog.w("mega api ${command["a"]} -> $code")
                throw MegaError(code)
            }
            return answer
        }
        throw BackupSinkException(SinkError.IO)
    }

    /** POSTs one encrypted chunk; the last one's answer is the completion handle. */
    fun upload(url: String, bytes: ByteArray): String {
        val response = execute(Request.Builder().url(url).post(bytes.toRequestBody(OCTET_STREAM)).build())
        val text = response.use {
            if (!it.isSuccessful) throw BackupSinkException(SinkError.IO)
            it.body?.string().orEmpty()
        }
        text.trim().toIntOrNull()?.let { if (it < 0) throw BackupSinkException(MegaSink.errorOf(it)) }
        return text.trim()
    }

    fun download(url: String): ByteArray {
        val response = execute(Request.Builder().url(url).get().build())
        return response.use {
            if (!it.isSuccessful) throw BackupSinkException(SinkError.IO)
            it.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun execute(request: Request) = try {
        client.newCall(request).execute()
    } catch (failure: Throwable) {
        if (failure is BackupSinkException) throw failure
        BackupLog.w("mega ${request.url.encodedPath} failed to connect", failure)
        throw BackupSinkException(SinkError.IO, failure)
    }

    companion object {
        const val API = "https://g.api.mega.co.nz"
        private const val EAGAIN = -3
        private const val ERATELIMIT = -4
        private const val RETRIES = 6
        private const val RETRY_START_MS = 500L
        private const val HTTP_PAYMENT_REQUIRED = 402
        private val JSON_TYPE = "application/json".toMediaTypeOrNull()
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}

/**
 * Signing in to MEGA from the settings screen, with the password and the 2FA
 * code when the account has one. What it hands back is the session id and the
 * master key, and the password is forgotten.
 */
object MegaLogin {

    sealed interface Outcome {
        data class SignedIn(val sessionId: String, val masterKey: String) : Outcome

        /** The account has two-factor sign-in: ask for the code, then sign in again with it. */
        data object NeedsCode : Outcome

        data class Failed(val error: SinkError) : Outcome
    }

    suspend fun signIn(email: String, password: String, code: String = ""): Outcome = withContext(Dispatchers.IO) {
        try {
            val user = email.trim().lowercase()
            val api = MegaApi(null)
            val pre = api.callRaw(buildJsonObject { put("a", JsonPrimitive("us0")); put("user", JsonPrimitive(user)) }).jsonObject
            val version = pre["v"]?.jsonPrimitive?.intOrNull ?: 1
            val passKey: ByteArray
            val handle: String
            if (version >= 2) {
                val salt = pre["s"]?.jsonPrimitive?.contentOrNull?.let(MegaCrypto::b64decode)
                    ?: return@withContext Outcome.Failed(SinkError.IO)
                val derived = BackupCrypto.pbkdf2(password.toCharArray(), salt, V2_ROUNDS, 32, "HmacSHA512")
                passKey = derived.copyOfRange(0, 16)
                handle = MegaCrypto.b64encode(derived.copyOfRange(16, 32))
            } else {
                passKey = MegaCrypto.prepareKey(password)
                handle = MegaCrypto.stringHash(user, passKey)
            }
            val answer = api.callRaw(
                buildJsonObject {
                    put("a", JsonPrimitive("us"))
                    put("user", JsonPrimitive(user))
                    put("uh", JsonPrimitive(handle))
                    if (version >= 2) put("sek", JsonPrimitive(MegaCrypto.b64encode(MegaCrypto.random(16))))
                    if (code.isNotBlank()) put("mfa", JsonPrimitive(code.filter { it.isDigit() }))
                },
            ).jsonObject
            val master = answer["k"]?.jsonPrimitive?.contentOrNull
                ?.let { MegaCrypto.ecbDecrypt(passKey, MegaCrypto.b64decode(it)) }
                ?: return@withContext Outcome.Failed(SinkError.IO)
            val privateKey = answer["privk"]?.jsonPrimitive?.contentOrNull
            val csid = answer["csid"]?.jsonPrimitive?.contentOrNull
            if (privateKey == null || csid == null) return@withContext Outcome.Failed(SinkError.IO)
            val sid = MegaCrypto.decryptSessionId(privateKey, csid, master)
            Outcome.SignedIn(sid, MegaCrypto.b64encode(master))
        } catch (error: MegaError) {
            when (error.code) {
                MFA_REQUIRED -> Outcome.NeedsCode
                else -> Outcome.Failed(MegaSink.errorOf(error.code).takeIf { it != SinkError.TARGET_MISSING } ?: SinkError.PERMISSION_LOST)
            }
        } catch (failure: BackupSinkException) {
            Outcome.Failed(failure.reason)
        } catch (failure: Exception) {
            BackupLog.w("mega sign-in failed", failure)
            Outcome.Failed(SinkError.IO)
        }
    }

    private const val V2_ROUNDS = 100_000
    private const val MFA_REQUIRED = -26
}

/**
 * MEGA's crypto, as its clients do it. Keys are often handled as arrays of
 * big-endian 32-bit words ("a32"), which is where the XOR games in the node
 * keys come from. Public functions are public for the tests.
 */
object MegaCrypto {

    const val KEY_BYTES = 16

    private val random = SecureRandom()
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun b64encode(bytes: ByteArray): String = urlSafe.encode(bytes)

    fun b64decode(text: String): ByteArray = urlSafe.decode(text.trim())

    fun random(bytes: Int): ByteArray = ByteArray(bytes).also { random.nextBytes(it) }

    fun requestId(): String = b64encode(random(8)).take(10)

    fun toWords(bytes: ByteArray): IntArray {
        val padded = if (bytes.size % 4 == 0) bytes else bytes.copyOf(bytes.size + (4 - bytes.size % 4))
        val buffer = ByteBuffer.wrap(padded)
        return IntArray(padded.size / 4) { buffer.int }
    }

    fun toBytes(words: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(words.size * 4)
        for (w in words) buffer.putInt(w)
        return buffer.array()
    }

    /** A file's AES key from its 8-word node key: the two halves XORed. A folder's is its key. */
    fun nodeKey(key: IntArray): IntArray =
        if (key.size >= 8) intArrayOf(key[0] xor key[4], key[1] xor key[5], key[2] xor key[6], key[3] xor key[7]) else key.copyOf(4)

    private fun aes(mode: Int, transformation: String, key: ByteArray, iv: ByteArray? = null): Cipher =
        Cipher.getInstance(transformation).apply {
            if (iv == null) init(mode, SecretKeySpec(key, "AES")) else init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

    fun ecbEncrypt(key: ByteArray, data: ByteArray): ByteArray = aes(Cipher.ENCRYPT_MODE, "AES/ECB/NoPadding", key).doFinal(data)

    fun ecbDecrypt(key: ByteArray, data: ByteArray): ByteArray = aes(Cipher.DECRYPT_MODE, "AES/ECB/NoPadding", key).doFinal(data)

    private fun pad16(data: ByteArray): ByteArray =
        if (data.size % 16 == 0 && data.isNotEmpty()) data else data.copyOf(((data.size + 15) / 16).coerceAtLeast(1) * 16)

    /** A node's name, as `MEGA{"n":"…"}` padded with zeros and AES-CBC encrypted under a zero IV. */
    fun encryptName(key: ByteArray, name: String): String {
        val attr = "MEGA" + buildJsonObject { put("n", JsonPrimitive(name)) }.toString()
        val plain = pad16(attr.toByteArray(Charsets.UTF_8))
        return b64encode(aes(Cipher.ENCRYPT_MODE, "AES/CBC/NoPadding", key, ByteArray(16)).doFinal(plain))
    }

    fun decryptName(key: ByteArray, attr: String): String? = runCatching {
        val plain = aes(Cipher.DECRYPT_MODE, "AES/CBC/NoPadding", key, ByteArray(16)).doFinal(b64decode(attr))
        val text = plain.toString(Charsets.UTF_8)
        if (!text.startsWith("MEGA")) return null
        val body = text.substring(4).trimEnd('\u0000')
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end < start) return null
        Json.parseToJsonElement(body.substring(start, end + 1)).jsonObject["n"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** The v1 password key: 65,536 rounds of AES over a fixed seed, keyed by the password's blocks. */
    fun prepareKey(password: String): ByteArray {
        val words = toWords(password.toByteArray(Charsets.UTF_8))
        val ciphers = (words.indices step 4).map { j ->
            val key = IntArray(4) { k -> if (j + k < words.size) words[j + k] else 0 }
            aes(Cipher.ENCRYPT_MODE, "AES/ECB/NoPadding", toBytes(key))
        }
        var block = toBytes(intArrayOf(0x93C467E3.toInt(), 0x7DB0C7A4, 0xD1BE3F81.toInt(), 0x0152CB56))
        repeat(V1_ROUNDS) { for (c in ciphers) block = c.doFinal(block) }
        return block
    }

    /** The v1 user handle: the email folded into one block, then 16,384 rounds of AES. */
    fun stringHash(text: String, key: ByteArray): String {
        val words = toWords(text.toByteArray(Charsets.UTF_8))
        val h = IntArray(4)
        for ((i, v) in words.withIndex()) h[i and 3] = h[i and 3] xor v
        val cipher = aes(Cipher.ENCRYPT_MODE, "AES/ECB/NoPadding", key)
        var block = toBytes(h)
        repeat(STRING_HASH_ROUNDS) { block = cipher.doFinal(block) }
        val out = toWords(block)
        return b64encode(toBytes(intArrayOf(out[0], out[2])))
    }

    /** One MPI: a 16-bit bit length, then the bytes. Returns the number and where the next starts. */
    private fun mpi(bytes: ByteArray, at: Int): Pair<BigInteger, Int> {
        val bits = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
        val length = (bits + 7) / 8
        return BigInteger(1, bytes.copyOfRange(at + 2, at + 2 + length)) to at + 2 + length
    }

    /** The session id: `csid` decrypted with the account's RSA key, which the master key unwraps. */
    fun decryptSessionId(privateKey: String, csid: String, master: ByteArray): String {
        val key = ecbDecrypt(master, pad16(b64decode(privateKey)))
        val (p, afterP) = mpi(key, 0)
        val (q, afterQ) = mpi(key, afterP)
        val (d, _) = mpi(key, afterQ)
        val (m, _) = mpi(b64decode(csid), 0)
        var plain = m.modPow(d, p.multiply(q)).toByteArray()
        if (plain.size > 1 && plain[0] == 0.toByte()) plain = plain.copyOfRange(1, plain.size)
        return b64encode(plain.copyOf(SID_BYTES))
    }

    /** MEGA's chunk boundaries: 128 KiB, 256, … up to 1 MiB, then 1 MiB each. The MACs follow them. */
    fun chunks(size: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var at = 0L
        var left = size
        var i = 1
        while (left > 0) {
            val chunk = minOf(if (i <= 8) i * 131_072L else 1_048_576L, left)
            out += at to chunk
            at += chunk
            left -= chunk
            i++
        }
        return out
    }

    /** What [seal] produced: the ciphertext and both forms of the key. */
    class Sealed(val ciphertext: ByteArray, val fileKey: IntArray, val nodeKey: IntArray)

    /**
     * Encrypts a file for upload: a random key and nonce, AES-CTR over the
     * bytes, and the MAC. [Sealed.nodeKey] is the 8-word key MEGA stores
     * (wrapped), which carries the nonce and the MAC with it.
     */
    fun seal(plain: ByteArray, key: IntArray = toWords(random(24))): Sealed {
        val aesKey = toBytes(key.copyOf(4))
        val ciphertext = aes(Cipher.ENCRYPT_MODE, "AES/CTR/NoPadding", aesKey, toBytes(intArrayOf(key[4], key[5], 0, 0))).doFinal(plain)
        val mac = mac(plain, aesKey, key[4], key[5])
        val nodeKey = intArrayOf(
            key[0] xor key[4], key[1] xor key[5], key[2] xor mac[0], key[3] xor mac[1],
            key[4], key[5], mac[0], mac[1],
        )
        return Sealed(ciphertext, key, nodeKey)
    }

    /** Decrypts a downloaded file with its 8-word node key, or null when its MAC does not match. */
    fun open(cipher: ByteArray, key: IntArray): ByteArray? {
        val aesKey = toBytes(nodeKey(key))
        val plain = aes(Cipher.DECRYPT_MODE, "AES/CTR/NoPadding", aesKey, toBytes(intArrayOf(key[4], key[5], 0, 0))).doFinal(cipher)
        val mac = mac(plain, aesKey, key[4], key[5])
        return if (mac[0] == key[6] && mac[1] == key[7]) plain else null
    }

    /** The file MAC: a CBC-MAC per chunk under the nonce, the chunk MACs CBC-MACed again, folded to two words. */
    private fun mac(plain: ByteArray, aesKey: ByteArray, n0: Int, n1: Int): IntArray {
        val chunkIv = toBytes(intArrayOf(n0, n1, n0, n1))
        val outer = aes(Cipher.ENCRYPT_MODE, "AES/CBC/NoPadding", aesKey, ByteArray(16))
        var last = ByteArray(16)
        val list = chunks(plain.size.toLong()).ifEmpty { listOf(0L to 0L) }
        for ((at, length) in list) {
            val piece = pad16(plain.copyOfRange(at.toInt(), (at + length).toInt()))
            val inner = aes(Cipher.ENCRYPT_MODE, "AES/CBC/NoPadding", aesKey, chunkIv).doFinal(piece)
            last = outer.update(inner.copyOfRange(inner.size - 16, inner.size)) ?: last
        }
        val t = toWords(last)
        return intArrayOf(t[0] xor t[1], t[2] xor t[3])
    }

    /**
     * Answers MEGA's proof-of-work challenge (`X-Hashcash: 1:easiness:time:token`),
     * the 402 it sends a client it thinks is a bot: find a 4-byte prefix
     * whose SHA-256 over the token repeated 262,144 times starts below a
     * threshold. Gives up after [TIMEOUT_MS]; null then.
     */
    fun hashcash(header: String, timeoutMs: Long = HASHCASH_TIMEOUT_MS): String? {
        val parts = header.split(':')
        if (parts.size != 4 || parts[0] != "1") return null
        val easiness = parts[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val token = parts[3]
        val threshold = ((((easiness and 63) shl 1) + 1).toLong() shl ((easiness shr 6) * 7 + 3)) and 0xffffffffL
        val tokenBytes = runCatching { pad16(b64decode(token)) }.getOrNull() ?: return null
        val slot = 48
        val buffer = ByteArray(4 + HASHCASH_REPLICAS * slot)
        for (i in 0 until HASHCASH_REPLICAS) System.arraycopy(tokenBytes, 0, buffer, 4 + i * slot, minOf(tokenBytes.size, slot))
        val sha = MessageDigest.getInstance("SHA-256")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var j = 0
            while (j < 4) {
                buffer[j]++
                if (buffer[j] != 0.toByte()) break
                j++
            }
            if (j == 4) return null
            val hash = sha.digest(buffer)
            val value = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xffffffffL
            if (value <= threshold) return "1:$token:${b64encode(buffer.copyOf(4))}"
        }
        return null
    }

    private const val V1_ROUNDS = 65_536
    private const val STRING_HASH_ROUNDS = 16_384
    private const val SID_BYTES = 43
    private const val HASHCASH_REPLICAS = 262_144
    private const val HASHCASH_TIMEOUT_MS = 60_000L
}
