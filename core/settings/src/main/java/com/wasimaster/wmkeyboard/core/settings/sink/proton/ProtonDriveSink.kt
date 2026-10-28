package com.wasimaster.wmkeyboard.core.settings.sink.proton

import com.wasimaster.wmkeyboard.core.settings.ProtonConfig
import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupHttp
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over a folder in Proton Drive, end-to-end encrypted the way
 * Proton's own apps do it.
 *
 * Proton publishes its Drive SDK (TypeScript and C#) but no API for an app
 * like this to call, and the SDK is not yet open to third-party apps. This
 * follows that SDK's upload model and the API it calls (`drive/v2/volumes`),
 * with the sign-in from go-srp: [ProtonSrp] and [ProtonPgp] hold the crypto.
 * Proton has said it will retire older clients once the SDK is out and a
 * grace period passes; when that day comes this needs updating.
 *
 * The key chain, from the top: the session's salted key password unlocks
 * the user's keys, which unlock the address key; the address key unlocks the
 * main share's key; that unlocks the root folder's node key; each folder's
 * node key unlocks its children's, and a file's node key unlocks its content
 * session key. Every key, name and block this sink makes is signed with the
 * address key, as Proton's apps sign theirs.
 */
class ProtonDriveSink(
    private val config: ProtonConfig,
    /** Stores rotated tokens: Proton hands out a new refresh token on every refresh. */
    private val onTokens: (accessToken: String, refreshToken: String) -> Unit = { _, _ -> },
) : BackupSink {

    override val id: String get() = ID

    private val api by lazy { ProtonApi(config.uid, config.accessToken, config.refreshToken, onTokens) }

    /** What a run needs once it is signed in and unlocked. */
    private class Drive(
        val volume: String,
        val address: ProtonPgp.Key,
        val addressId: String,
        val email: String,
        val root: Folder,
    )

    /** A folder's id, its unlocked node key and its hash key. */
    private class Folder(val id: String, val key: ProtonPgp.Key, val hashKey: ByteArray)

    /** One child of a folder, its name decrypted. */
    private class Child(val id: String, val name: String, val folder: Boolean, val link: JsonObject)

    @Volatile
    private var drive: Drive? = null

    @Volatile
    private var target: Folder? = null

    private fun drive(): Drive {
        drive?.let { return it }
        if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
        val pass = config.saltedKeyPass.toByteArray(Charsets.US_ASCII)
        val userKeys = api.get("core/v4/users")["User"]?.jsonObject?.get("Keys")?.jsonArray.orEmpty()
            .mapNotNull { k -> k.jsonObject["PrivateKey"]?.jsonPrimitive?.contentOrNull?.let { ProtonPgp.unlock(it, pass) } }
        if (userKeys.isEmpty()) throw BackupSinkException(SinkError.PERMISSION_LOST).also { BackupLog.w("proton: no user key unlocks") }

        val myFiles = api.get("drive/v2/shares/my-files")
        val volume = myFiles["Volume"]?.jsonObject?.str("VolumeID") ?: throw BackupSinkException(SinkError.TARGET_MISSING)
        val share = myFiles["Share"]?.jsonObject ?: throw BackupSinkException(SinkError.TARGET_MISSING)
        val addressId = share.str("AddressID") ?: throw BackupSinkException(SinkError.IO)

        val addresses = api.get("core/v4/addresses")["Addresses"]?.jsonArray.orEmpty().map { it.jsonObject }
        val address = addresses.firstOrNull { it.str("ID") == addressId } ?: throw BackupSinkException(SinkError.IO)
        val addressKey = address["Keys"]?.jsonArray.orEmpty().map { it.jsonObject }
            .sortedByDescending { it["Primary"]?.jsonPrimitive?.intOrNull ?: 0 }
            .firstNotNullOfOrNull { key ->
                val armored = key.str("PrivateKey") ?: return@firstNotNullOfOrNull null
                // A key with a token is unlocked by the token, which the user
                // key decrypts; an older one by the key password itself.
                val secret = key.str("Token")?.let { runCatching { ProtonPgp.decryptArmored(it, userKeys) }.getOrNull() } ?: pass
                ProtonPgp.unlock(armored, secret)
            } ?: throw BackupSinkException(SinkError.PERMISSION_LOST).also { BackupLog.w("proton: no address key unlocks") }

        val shareKey = unlockNode(share.str("Key"), share.str("Passphrase"), listOf(addressKey))
        val rootLink = myFiles["Link"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
        val root = folderOf(rootLink, shareKey)
        return Drive(volume, addressKey, addressId, address.str("Email") ?: share.str("CreatorEmail").orEmpty(), root)
            .also { drive = it }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun unlockNode(armoredKey: String?, armoredPassphrase: String?, parents: List<ProtonPgp.Key>): ProtonPgp.Key {
        if (armoredKey == null || armoredPassphrase == null) throw BackupSinkException(SinkError.IO)
        val passphrase = runCatching { ProtonPgp.decryptArmored(armoredPassphrase, parents) }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)
        return ProtonPgp.unlock(armoredKey, passphrase) ?: throw BackupSinkException(SinkError.IO)
    }

    /** A folder from its link details (`{Link, Folder}`) and its parent's key. */
    private fun folderOf(details: JsonObject, parent: ProtonPgp.Key): Folder {
        val link = details["Link"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
        val key = unlockNode(link.str("NodeKey"), link.str("NodePassphrase"), listOf(parent))
        val hashKey = details["Folder"]?.jsonObject?.str("NodeHashKey")
            ?.let { runCatching { ProtonPgp.decryptArmored(it, listOf(key)) }.getOrNull() }
            ?: throw BackupSinkException(SinkError.IO)
        return Folder(link.str("LinkID") ?: throw BackupSinkException(SinkError.IO), key, hashKey)
    }

    private fun children(d: Drive, folder: Folder): List<Child> {
        val ids = ArrayList<String>()
        var anchor = ""
        while (true) {
            val page = api.get(
                "drive/v2/volumes/${d.volume}/folders/${folder.id}/children" + if (anchor.isEmpty()) "" else "?AnchorID=$anchor",
            )
            page["LinkIDs"]?.jsonArray.orEmpty().mapNotNullTo(ids) { it.jsonPrimitive.contentOrNull }
            val more = page["More"]?.jsonPrimitive?.booleanOrNull == true || page["More"]?.jsonPrimitive?.intOrNull == 1
            anchor = page.str("AnchorID").orEmpty()
            if (!more || anchor.isEmpty()) break
        }
        val out = ArrayList<Child>()
        for (batch in ids.chunked(LINKS_PER_CALL)) {
            val body = buildJsonObject { put("LinkIDs", JsonArray(batch.map(::JsonPrimitive))) }
            for (element in api.post("drive/v2/volumes/${d.volume}/links", body)["Links"]?.jsonArray.orEmpty()) {
                val details = element.jsonObject
                val link = details["Link"]?.jsonObject ?: continue
                if (link["State"]?.jsonPrimitive?.intOrNull != STATE_ACTIVE) continue
                val name = link.str("Name")?.let { runCatching { ProtonPgp.decryptArmored(it, listOf(folder.key)).decodeToString() }.getOrNull() }
                    ?: continue
                out += Child(link.str("LinkID") ?: continue, name, link["Type"]?.jsonPrimitive?.intOrNull == TYPE_FOLDER, details)
            }
        }
        return out
    }

    /** The backup folder, made level by level where missing. */
    private fun target(d: Drive): Folder {
        target?.let { return it }
        var at = d.root
        for (part in config.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            val existing = children(d, at).firstOrNull { it.folder && it.name == part }
            at = if (existing != null) folderOf(existing.link, at.key) else makeFolder(d, at, part)
        }
        return at.also { target = it }
    }

    private fun makeFolder(d: Drive, parent: Folder, name: String): Folder {
        val passphrase = ProtonPgp.newPassphrase()
        val key = ProtonPgp.unlock(ProtonPgp.generateKey(passphrase), passphrase.toByteArray())
            ?: throw BackupSinkException(SinkError.IO)
        val hashKey = ProtonPgp.newPassphrase().toByteArray(Charsets.US_ASCII)
        val body = buildJsonObject {
            put("ParentLinkID", JsonPrimitive(parent.id))
            put("NodeKey", JsonPrimitive(key.armored))
            put("NodeHashKey", JsonPrimitive(ProtonPgp.encrypt(key, hashKey, signer = key)))
            put("NodePassphrase", JsonPrimitive(ProtonPgp.encrypt(parent.key, passphrase.toByteArray())))
            put("NodePassphraseSignature", JsonPrimitive(ProtonPgp.signArmored(d.address, passphrase.toByteArray())))
            put("SignatureEmail", JsonPrimitive(d.email))
            put("Name", JsonPrimitive(ProtonPgp.encrypt(parent.key, name.toByteArray(), signer = d.address)))
            put("Hash", JsonPrimitive(ProtonPgp.nameHash(parent.hashKey, name)))
        }
        val id = api.post("drive/v2/volumes/${d.volume}/folders", body)["Folder"]?.jsonObject?.str("ID")
            ?: throw BackupSinkException(SinkError.IO)
        return Folder(id, key, hashKey)
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            target(drive())
            Unit
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            val d = drive()
            val folder = target(d)
            val existing = children(d, folder).firstOrNull { !it.folder && it.name == name }
            val draft = if (existing == null) newFile(d, folder, name, mimeType) else newRevision(d, folder, existing)
            BackupLog.d("proton write $name ${bytes.size} B (${if (existing == null) "new" else "revision"})")
            try {
                upload(d, draft, bytes)
            } catch (failure: Throwable) {
                runCatching {
                    if (existing == null) {
                        api.post("drive/v2/volumes/${d.volume}/delete_multiple", buildJsonObject { put("LinkIDs", JsonArray(listOf(JsonPrimitive(draft.linkId)))) })
                    } else {
                        api.delete("drive/v2/volumes/${d.volume}/files/${draft.linkId}/revisions/${draft.revisionId}")
                    }
                }
                throw failure
            }
            SinkEntry(draft.linkId, name, bytes.size.toLong(), System.currentTimeMillis())
        }
    }

    /** A draft revision being uploaded: where it is, and the keys its content goes under. */
    private class Draft(val linkId: String, val revisionId: String, val nodeKey: ProtonPgp.Key, val sessionKey: ByteArray)

    private fun newFile(d: Drive, folder: Folder, name: String, mimeType: String): Draft {
        val passphrase = ProtonPgp.newPassphrase()
        val key = ProtonPgp.unlock(ProtonPgp.generateKey(passphrase), passphrase.toByteArray())
            ?: throw BackupSinkException(SinkError.IO)
        val sessionKey = ProtonPgp.newSessionKey()
        val body = buildJsonObject {
            put("ParentLinkID", JsonPrimitive(folder.id))
            put("Name", JsonPrimitive(ProtonPgp.encrypt(folder.key, name.toByteArray(), signer = d.address)))
            put("Hash", JsonPrimitive(ProtonPgp.nameHash(folder.hashKey, name)))
            put("MIMEType", JsonPrimitive(mimeType))
            put("NodeKey", JsonPrimitive(key.armored))
            put("NodePassphrase", JsonPrimitive(ProtonPgp.encrypt(folder.key, passphrase.toByteArray())))
            put("NodePassphraseSignature", JsonPrimitive(ProtonPgp.signArmored(d.address, passphrase.toByteArray())))
            put("ContentKeyPacket", JsonPrimitive(Base64.encode(ProtonPgp.keyPacket(key, sessionKey))))
            put("ContentKeyPacketSignature", JsonPrimitive(ProtonPgp.signArmored(key, sessionKey)))
            put("SignatureAddress", JsonPrimitive(d.email))
        }
        val file = api.post("drive/v2/volumes/${d.volume}/files", body)["File"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
        return Draft(
            linkId = file.str("ID") ?: throw BackupSinkException(SinkError.IO),
            revisionId = file.str("RevisionID") ?: throw BackupSinkException(SinkError.IO),
            nodeKey = key,
            sessionKey = sessionKey,
        )
    }

    /** A new revision of a file already there, under its existing keys: how a sync file is replaced. */
    private fun newRevision(d: Drive, folder: Folder, file: Child): Draft {
        val link = file.link["Link"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
        val properties = file.link["File"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
        val key = unlockNode(link.str("NodeKey"), link.str("NodePassphrase"), listOf(folder.key))
        val packet = properties.str("ContentKeyPacket")?.let { Base64.decode(it) } ?: throw BackupSinkException(SinkError.IO)
        val sessionKey = ProtonPgp.sessionKeyOf(packet, key) ?: throw BackupSinkException(SinkError.IO)
        val current = properties["ActiveRevision"]?.jsonObject?.str("RevisionID")
        val body = buildJsonObject { current?.let { put("CurrentRevisionID", JsonPrimitive(it)) } }
        val revision = api.post("drive/v2/volumes/${d.volume}/files/${file.id}/revisions", body)["Revision"]?.jsonObject?.str("ID")
            ?: throw BackupSinkException(SinkError.IO)
        return Draft(file.id, revision, key, sessionKey)
    }

    /** Encrypts and uploads the blocks, then commits the revision with its manifest and extended attributes. */
    private fun upload(d: Drive, draft: Draft, bytes: ByteArray) {
        val verification = api.get("drive/v2/volumes/${d.volume}/links/${draft.linkId}/revisions/${draft.revisionId}/verification")
        val code = verification.str("VerificationCode")?.let { Base64.decode(it) } ?: throw BackupSinkException(SinkError.IO)

        val pieces = if (bytes.isEmpty()) emptyList() else (bytes.indices step BLOCK_SIZE).map { bytes.copyOfRange(it, minOf(it + BLOCK_SIZE, bytes.size)) }
        val manifest = ByteArrayOutputStream()
        val sha1 = MessageDigest.getInstance("SHA-1")
        for ((index, batch) in pieces.withIndex().chunked(BLOCKS_PER_REQUEST).withIndex()) {
            val encrypted = batch.map { (i, plain) ->
                sha1.update(plain)
                val cipher = ProtonPgp.encryptWithSessionKey(draft.sessionKey, plain)
                val signature = ProtonPgp.encrypt(draft.nodeKey, ProtonPgp.sign(d.address, plain))
                // The server's check that the block was made with the key it
                // holds: the code XORed with the ciphertext's first bytes.
                val token = ByteArray(code.size) { (code[it].toInt() xor (cipher.getOrNull(it)?.toInt() ?: 0)).toByte() }
                Triple(i + 1, cipher, signature to token)
            }
            val request = buildJsonObject {
                put("AddressID", JsonPrimitive(d.addressId))
                put("VolumeID", JsonPrimitive(d.volume))
                put("LinkID", JsonPrimitive(draft.linkId))
                put("RevisionID", JsonPrimitive(draft.revisionId))
                put(
                    "BlockList",
                    JsonArray(
                        encrypted.map { (i, _, extra) ->
                            buildJsonObject {
                                put("Index", JsonPrimitive(i))
                                put("EncSignature", JsonPrimitive(extra.first))
                                put("Verifier", buildJsonObject { put("Token", JsonPrimitive(Base64.encode(extra.second))) })
                            }
                        },
                    ),
                )
                put("ThumbnailList", JsonArray(emptyList()))
            }
            val links = api.post("drive/blocks", request)["UploadLinks"]?.jsonArray.orEmpty().map { it.jsonObject }
            for ((i, cipher, _) in encrypted) {
                val link = links.firstOrNull { it["Index"]?.jsonPrimitive?.intOrNull == i } ?: links.getOrNull(i - 1 - index * BLOCKS_PER_REQUEST)
                    ?: throw BackupSinkException(SinkError.IO)
                api.uploadBlock(link.str("BareURL") ?: throw BackupSinkException(SinkError.IO), link.str("Token").orEmpty(), cipher)
                manifest.write(MessageDigest.getInstance("SHA-256").digest(cipher))
            }
        }
        val xattr = buildJsonObject {
            put(
                "Common",
                buildJsonObject {
                    put("ModificationTime", JsonPrimitive(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())))
                    put("Size", JsonPrimitive(bytes.size))
                    put("BlockSizes", JsonArray(pieces.map { JsonPrimitive(it.size) }))
                    put("Digests", buildJsonObject { put("SHA1", JsonPrimitive(sha1.digest().joinToString("") { "%02x".format(it) })) })
                },
            )
        }
        val commit = buildJsonObject {
            put("ManifestSignature", JsonPrimitive(ProtonPgp.signArmored(d.address, manifest.toByteArray())))
            put("SignatureAddress", JsonPrimitive(d.email))
            put("XAttr", JsonPrimitive(ProtonPgp.encrypt(draft.nodeKey, xattr.toString().toByteArray(), signer = d.address, compress = true)))
            put("ChecksumVerified", JsonPrimitive(false))
            put("Photo", JsonNull)
        }
        api.put("drive/v2/volumes/${d.volume}/files/${draft.linkId}/revisions/${draft.revisionId}", commit)
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val d = drive()
            children(d, target(d)).filter { !it.folder && AutoBackupNaming.isListed(it.name) }.map { child ->
                val link = child.link["Link"]?.jsonObject
                val revision = child.link["File"]?.jsonObject?.get("ActiveRevision")?.takeIf { it !is JsonNull }?.jsonObject
                SinkEntry(
                    id = child.id,
                    name = child.name,
                    sizeBytes = -1L,
                    modifiedAtMs = ((revision?.get("CreateTime") ?: link?.get("ModifyTime"))?.jsonPrimitive?.longOrNull ?: 0L) * 1000L,
                )
            }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            val d = drive()
            val folder = target(d)
            val child = children(d, folder).firstOrNull { it.id == entry.id } ?: throw BackupSinkException(SinkError.TARGET_MISSING)
            val link = child.link["Link"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
            val file = child.link["File"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)
            val key = unlockNode(link.str("NodeKey"), link.str("NodePassphrase"), listOf(folder.key))
            val packet = file.str("ContentKeyPacket")?.let { Base64.decode(it) } ?: throw BackupSinkException(SinkError.IO)
            val revision = file["ActiveRevision"]?.jsonObject?.str("RevisionID") ?: throw BackupSinkException(SinkError.TARGET_MISSING)

            val out = ByteArrayOutputStream()
            var from = 1
            while (true) {
                val blocks = api.get("drive/v2/volumes/${d.volume}/files/${entry.id}/revisions/$revision?PageSize=$BLOCK_PAGE&FromBlockIndex=$from")["Revision"]
                    ?.jsonObject?.get("Blocks")?.jsonArray.orEmpty().map { it.jsonObject }.sortedBy { it["Index"]?.jsonPrimitive?.intOrNull ?: 0 }
                if (blocks.isEmpty()) break
                for (block in blocks) {
                    val cipher = api.downloadBlock(block.str("BareURL") ?: throw BackupSinkException(SinkError.IO), block.str("Token").orEmpty())
                    val hash = Base64.encode(MessageDigest.getInstance("SHA-256").digest(cipher))
                    if (block.str("Hash")?.let { it != hash } == true) throw BackupSinkException(SinkError.IO).also { BackupLog.w("proton: block hash mismatch") }
                    out.write(ProtonPgp.decrypt(packet + cipher, listOf(key)))
                }
                if (blocks.size < BLOCK_PAGE) break
                from = (blocks.last()["Index"]?.jsonPrimitive?.intOrNull ?: (from + blocks.size - 1)) + 1
            }
            out.toByteArray().inputStream()
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            val d = drive()
            val ids = buildJsonObject { put("LinkIDs", JsonArray(listOf(JsonPrimitive(entry.id)))) }
            // To the trash, then out of it: Proton counts the trash against
            // the quota, and rotation would fill it.
            api.post("drive/v2/volumes/${d.volume}/trash_multiple", ids)
            api.post("drive/v2/volumes/${d.volume}/trash/delete_multiple", ids)
            Unit
        }
    }

    companion object {
        const val ID = "proton"
        private const val BLOCK_SIZE = 4 * 1024 * 1024
        private const val BLOCKS_PER_REQUEST = 10
        private const val BLOCK_PAGE = 100
        private const val LINKS_PER_CALL = 100
        private const val STATE_ACTIVE = 1
        private const val TYPE_FOLDER = 1
    }
}

/**
 * Proton's API: JSON in and out, a `Code` of 1000 or 1001 for success, the
 * session in `x-pm-uid` and a bearer token. A 401 refreshes the token once.
 */
internal class ProtonApi(
    private var uid: String,
    private var access: String,
    private var refresh: String,
    private val onTokens: (String, String) -> Unit,
) {
    private val client by lazy { BackupHttp.client() }

    private fun request(path: String): Request.Builder = Request.Builder().url("$HOST/$path")
        .header("x-pm-appversion", APP_VERSION)
        .header("Accept", "application/vnd.protonmail.v1+json")
        .apply {
            if (uid.isNotEmpty()) header("x-pm-uid", uid)
            if (access.isNotEmpty()) header("Authorization", "Bearer $access")
        }

    fun get(path: String): JsonObject = call(path) { it.get() }

    fun post(path: String, body: JsonElement): JsonObject = call(path) { it.post(body.toString().toRequestBody(JSON_TYPE)) }

    fun put(path: String, body: JsonElement): JsonObject = call(path) { it.put(body.toString().toRequestBody(JSON_TYPE)) }

    fun delete(path: String): JsonObject = call(path) { it.delete() }

    private fun call(path: String, method: (Request.Builder) -> Request.Builder): JsonObject {
        val first = execute(method(request(path)).build())
        if (first.code != HTTP_UNAUTHORIZED || refresh.isEmpty()) return answer(first, path)
        first.close()
        refreshTokens()
        return answer(execute(method(request(path)).build()), path)
    }

    /** Trades the refresh token for new tokens; Proton rotates both. */
    private fun refreshTokens() {
        val body = buildJsonObject {
            put("UID", JsonPrimitive(uid))
            put("RefreshToken", JsonPrimitive(refresh))
            put("ResponseType", JsonPrimitive("token"))
            put("GrantType", JsonPrimitive("refresh_token"))
            put("RedirectURI", JsonPrimitive("https://protonmail.ch"))
            put("State", JsonPrimitive(ProtonPgp.newPassphrase().take(STATE_CHARS)))
        }
        val saved = access
        access = ""
        val root = try {
            answer(execute(request("auth/v4/refresh").post(body.toString().toRequestBody(JSON_TYPE)).build()), "auth/v4/refresh")
        } catch (failure: BackupSinkException) {
            access = saved
            // A refused refresh token is the user signing this app out, or
            // the session having been revoked: only a new sign-in helps.
            throw if (failure.reason == SinkError.IO) failure else BackupSinkException(SinkError.PERMISSION_LOST)
        }
        access = root["AccessToken"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.PERMISSION_LOST)
        refresh = root["RefreshToken"]?.jsonPrimitive?.contentOrNull ?: refresh
        onTokens(access, refresh)
    }

    fun uploadBlock(url: String, token: String, block: ByteArray) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("Block", "blob", block.toRequestBody(OCTET_STREAM))
            .build()
        val request = Request.Builder().url(url)
            .header("pm-storage-token", token)
            .header("x-pm-appversion", APP_VERSION)
            .post(form).build()
        answer(execute(request), "block upload")
    }

    fun downloadBlock(url: String, token: String): ByteArray {
        val request = Request.Builder().url(url)
            .header("pm-storage-token", token)
            .header("x-pm-appversion", APP_VERSION)
            .get().build()
        execute(request).use {
            if (!it.isSuccessful) throw BackupSinkException(statusError(it.code, 0, ""))
            return it.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (failure: Throwable) {
        BackupLog.w("proton ${request.url.encodedPath} failed to connect", failure)
        throw BackupSinkException(SinkError.IO, failure)
    }

    private fun answer(response: Response, what: String): JsonObject {
        val text = response.use { it.body?.string().orEmpty() }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val code = root["Code"]?.jsonPrimitive?.intOrNull ?: 0
        if (response.isSuccessful && (code == 0 || code == CODE_OK || code == CODE_MULTI)) return root
        val message = root["Error"]?.jsonPrimitive?.contentOrNull.orEmpty()
        BackupLog.w("proton $what -> HTTP ${response.code} code $code $message")
        throw ProtonError(code, statusError(response.code, code, message))
    }

    companion object {
        const val HOST = "https://drive-api.proton.me"

        /** What Proton asks third-party Drive clients to send: `external-drive-<project>@<version>`. */
        const val APP_VERSION = "external-drive-wmkeyboard@1.0.0"

        internal val json = Json { ignoreUnknownKeys = true }

        private const val HTTP_UNAUTHORIZED = 401
        private const val CODE_OK = 1000
        private const val CODE_MULTI = 1001
        private const val STATE_CHARS = 16
        private val JSON_TYPE = "application/json".toMediaTypeOrNull()
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()

        /**
         * What a Proton failure means to the user: 401 and 10013 a session
         * that is gone, 8002 a wrong password, 9001 a CAPTCHA wanted; 2501 a
         * missing link; 2003 or "storage" in the message a full account.
         * Public for the tests.
         */
        fun statusError(status: Int, code: Int, message: String): SinkError = when {
            code == 200001 || code == 2003 || message.contains("storage", ignoreCase = true) && message.contains("quota", ignoreCase = true) ||
                message.contains("insufficient", ignoreCase = true) -> SinkError.OUT_OF_SPACE
            status == 401 || code == 10013 || code == 8002 || code == 9001 || status == 403 -> SinkError.PERMISSION_LOST
            status == 404 || code == 2501 -> SinkError.TARGET_MISSING
            else -> SinkError.IO
        }
    }
}

/** A failure Proton reported, with its own code: the sign-in reads it, the sink only the [reason]. */
internal class ProtonError(val code: Int, reason: SinkError) : BackupSinkException(reason)

/**
 * Signing in to Proton from the settings screen: the account password, the
 * 2FA code when the account has one, and the second (mailbox) password on
 * an account that still has two. What is kept is the session and the salted
 * key password; the passwords are not.
 */
object ProtonLogin {

    sealed interface Outcome {
        data class SignedIn(val uid: String, val accessToken: String, val refreshToken: String, val saltedKeyPass: String) : Outcome

        /** The account has two-factor sign-in. [state] goes back with the code. */
        data class NeedsCode(val state: Pending) : Outcome

        /** The account has a separate mailbox password; sign in again with it. */
        data object NeedsMailboxPassword : Outcome

        /** Proton wants a CAPTCHA, which only its own sign-in page can show. */
        data object NeedsHumanVerification : Outcome

        data class Failed(val error: SinkError) : Outcome
    }

    /** A sign-in waiting for its 2FA code. */
    class Pending internal constructor(
        internal val uid: String,
        internal val access: String,
        internal val refresh: String,
        internal val keyPassword: ByteArray,
        internal val twoPasswords: Boolean,
    )

    suspend fun signIn(user: String, password: String, mailboxPassword: String = ""): Outcome = withContext(Dispatchers.IO) {
        guarded {
            val api = ProtonApi("", "", "", { _, _ -> })
            val name = user.trim()
            val info = api.post("auth/v4/info", buildJsonObject { put("Username", JsonPrimitive(name)) })
            val version = info["Version"]?.jsonPrimitive?.intOrNull ?: 0
            if (version < 3) return@guarded Outcome.Failed(SinkError.IO).also { BackupLog.w("proton: auth version $version not supported") }
            val modulus = info["Modulus"]?.jsonPrimitive?.contentOrNull?.let(ProtonSrp::modulusOf) ?: return@guarded Outcome.Failed(SinkError.IO)
            val salt = info["Salt"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) } ?: return@guarded Outcome.Failed(SinkError.IO)
            val serverB = info["ServerEphemeral"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) } ?: return@guarded Outcome.Failed(SinkError.IO)
            val session = info["SRPSession"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val proofs = ProtonSrp.proofs(password.toByteArray(Charsets.UTF_8), salt, modulus, serverB)
                ?: return@guarded Outcome.Failed(SinkError.UNSAFE)

            val auth = api.post(
                "auth/v4",
                buildJsonObject {
                    put("Username", JsonPrimitive(name))
                    put("ClientEphemeral", JsonPrimitive(Base64.encode(proofs.clientEphemeral)))
                    put("ClientProof", JsonPrimitive(Base64.encode(proofs.clientProof)))
                    put("SRPSession", JsonPrimitive(session))
                },
            )
            val serverProof = auth["ServerProof"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) }
            // The server proving it knew the verifier: without this match a
            // machine in the middle could have answered.
            if (serverProof == null || !serverProof.contentEquals(proofs.expectedServerProof)) return@guarded Outcome.Failed(SinkError.UNSAFE)

            val twoPasswords = auth["PasswordMode"]?.jsonPrimitive?.intOrNull == TWO_PASSWORD_MODE
            if (twoPasswords && mailboxPassword.isEmpty()) return@guarded Outcome.NeedsMailboxPassword
            val pending = Pending(
                uid = auth["UID"]?.jsonPrimitive?.contentOrNull ?: return@guarded Outcome.Failed(SinkError.IO),
                access = auth["AccessToken"]?.jsonPrimitive?.contentOrNull ?: return@guarded Outcome.Failed(SinkError.IO),
                refresh = auth["RefreshToken"]?.jsonPrimitive?.contentOrNull ?: return@guarded Outcome.Failed(SinkError.IO),
                keyPassword = (if (twoPasswords) mailboxPassword else password).toByteArray(Charsets.UTF_8),
                twoPasswords = twoPasswords,
            )
            val totp = (auth["2FA"]?.jsonObject?.get("Enabled")?.jsonPrimitive?.intOrNull ?: 0) and TOTP != 0
            if (totp) Outcome.NeedsCode(pending) else finish(pending)
        }
    }

    suspend fun submitCode(pending: Pending, code: String): Outcome = withContext(Dispatchers.IO) {
        guarded {
            val api = ProtonApi(pending.uid, pending.access, pending.refresh, { _, _ -> })
            api.post("auth/v4/2fa", buildJsonObject { put("TwoFactorCode", JsonPrimitive(code.filter { it.isDigit() })) })
            finish(pending)
        }
    }

    /** Works out the salted key password and proves it by unlocking a user key. */
    private fun finish(pending: Pending): Outcome {
        val api = ProtonApi(pending.uid, pending.access, pending.refresh, { _, _ -> })
        val keys = api.get("core/v4/users")["User"]?.jsonObject?.get("Keys")?.jsonArray.orEmpty().map { it.jsonObject }
        val primary = keys.firstOrNull { it["Primary"]?.jsonPrimitive?.intOrNull == 1 } ?: keys.firstOrNull()
            ?: return Outcome.Failed(SinkError.IO)
        val salts = api.get("core/v4/keys/salts")["KeySalts"]?.jsonArray.orEmpty().map { it.jsonObject }
        val salt = salts.firstOrNull { it["ID"]?.jsonPrimitive?.contentOrNull == primary["ID"]?.jsonPrimitive?.contentOrNull }
            ?.get("KeySalt")?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) }
            ?: return Outcome.Failed(SinkError.IO)
        val salted = ProtonSrp.saltedKeyPass(pending.keyPassword, salt)
        val armored = primary["PrivateKey"]?.jsonPrimitive?.contentOrNull ?: return Outcome.Failed(SinkError.IO)
        if (ProtonPgp.unlock(armored, salted.toByteArray(Charsets.US_ASCII)) == null) {
            return Outcome.Failed(SinkError.PERMISSION_LOST)
        }
        return Outcome.SignedIn(pending.uid, pending.access, pending.refresh, salted)
    }

    private inline fun guarded(block: () -> Outcome): Outcome = try {
        block()
    } catch (failure: ProtonError) {
        when (failure.code) {
            HUMAN_VERIFICATION -> Outcome.NeedsHumanVerification
            else -> Outcome.Failed(failure.reason)
        }
    } catch (failure: BackupSinkException) {
        Outcome.Failed(failure.reason)
    } catch (failure: Exception) {
        BackupLog.w("proton sign-in failed", failure)
        Outcome.Failed(SinkError.IO)
    }

    private const val TWO_PASSWORD_MODE = 2
    private const val TOTP = 1
    private const val HUMAN_VERIFICATION = 9001
}
