package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.ICloudConfig
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over a folder in iCloud Drive, through the web API that
 * iCloud.com itself calls. Apple has no public iCloud Drive API for Android,
 * so this is that site's protocol, as rclone's `iclouddrive` backend and
 * pyicloud have worked it out; it can change without notice, and when it
 * does a backup fails with an IO error until the app is updated.
 *
 * Signing in is Apple's SRP-6a ([ICloudSrp]) with the password, then a code
 * from a trusted device the first time ([ICloudLogin]). The trust token that
 * hands back lets a later sign-in skip the code for about a month. A run
 * first tries the cookies the last one left, which iCloud honours for a day
 * or so, and signs in again with password and trust token when they are
 * stale. Accounts with Advanced Data Protection need "Access iCloud Data on
 * the Web" turned on, and the first sign-in then waits for the approval on
 * a trusted device.
 */
class ICloudSink(
    private val config: ICloudConfig,
    /**
     * Stores the cookies a sign-in ends with, so the next run can skip it, and
     * the trust token, which Apple may hand back renewed.
     */
    private val onSession: (cookies: String, trustToken: String) -> Unit = { _, _ -> },
) : BackupSink {

    override val id: String get() = ID

    private val session by lazy { ICloudSession(config.appleId, config.clientId.ifEmpty { ICloudSession.CLIENT_ID }, config.cookies, config.trustToken) }

    @Volatile
    private var services: ICloudSession.Services? = null

    @Volatile
    private var folderId: String? = null

    /** Signed in, by the stored cookies if they still work and by password and trust token if not. */
    private fun services(): ICloudSession.Services {
        services?.let { return it }
        if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
        val found = session.validate() ?: run {
            when (session.signIn(config.password)) {
                ICloudSession.SignIn.DONE -> Unit
                // The trust token has run out: only a person can type the code.
                ICloudSession.SignIn.NEEDS_CODE -> throw BackupSinkException(SinkError.PERMISSION_LOST)
                ICloudSession.SignIn.REFUSED -> throw BackupSinkException(SinkError.PERMISSION_LOST)
            }
            session.accountLogin() ?: throw BackupSinkException(SinkError.PERMISSION_LOST)
        }
        if (found.needsWebAccessApproval && !session.requestWebAccess(waitMs = 0L)) {
            throw BackupSinkException(SinkError.PERMISSION_LOST)
        }
        onSession(session.cookieString(), session.trustToken)
        return found.also { services = it }
    }

    /** One call with the session's cookies, signed in again once if iCloud says the session is gone. */
    private fun <T> drive(request: (ICloudSession.Services) -> Request, read: (Response) -> T): T {
        val first = services()
        val again = try {
            return session.send(request(first), "icloud", read)
        } catch (expired: ICloudSession.Expired) {
            services = null
            session.dropSession()
            services()
        }
        return try {
            session.send(request(again), "icloud", read)
        } catch (expired: ICloudSession.Expired) {
            throw BackupSinkException(SinkError.PERMISSION_LOST)
        }
    }

    private fun json(response: Response): JsonElement =
        runCatching { ICloudSession.json.parseToJsonElement(response.body?.string().orEmpty()) }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)

    private class Item(val id: String, val etag: String, val name: String, val folder: Boolean, val size: Long, val modifiedMs: Long)

    private fun children(folder: String): List<Item> {
        val body = JsonArray(
            listOf(
                buildJsonObject {
                    put("drivewsid", JsonPrimitive(folder))
                    put("partialData", JsonPrimitive(false))
                    put("includeHierarchy", JsonPrimitive(false))
                },
            ),
        )
        val answer = drive({ session.post("${it.drive}/retrieveItemDetailsInFolders", body) }, ::json)
        val items = answer.jsonArray.firstOrNull()?.jsonObject?.get("items")?.jsonArray.orEmpty()
        return items.mapNotNull { element ->
            val item = element.jsonObject
            val id = item["drivewsid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type = item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val extension = item["extension"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Item(
                id = id,
                etag = item["etag"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                // iCloud keeps the extension apart and the name in NFD.
                name = java.text.Normalizer.normalize(if (extension.isEmpty()) name else "$name.$extension", java.text.Normalizer.Form.NFC),
                folder = type == "FOLDER" || type == "APP_CONTAINER" || type == "APP_LIBRARY",
                size = item["size"]?.jsonPrimitive?.longOrNull ?: -1L,
                modifiedMs = item["dateModified"]?.jsonPrimitive?.contentOrNull?.let(S3Listing::parseIso8601) ?: 0L,
            )
        }
    }

    private fun folder(): String {
        folderId?.let { return it }
        var at = ROOT_ID
        for (part in config.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            at = children(at).firstOrNull { it.folder && it.name == part }?.id ?: makeFolder(at, part)
        }
        return at.also { folderId = it }
    }

    private fun makeFolder(parent: String, name: String): String {
        val body = buildJsonObject {
            put("destinationDrivewsId", JsonPrimitive(parent))
            put(
                "folders",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("clientId", JsonPrimitive("FOLDER::UNKNOWN_ZONE::TempId-${UUID.randomUUID()}"))
                            put("name", JsonPrimitive(name))
                        },
                    ),
                ),
            )
        }
        val answer = drive({ session.post("${it.drive}/createFolders", body) }, ::json).jsonObject
        val made = answer["folders"]?.jsonArray?.firstOrNull()?.jsonObject
        if (made?.get("status")?.jsonPrimitive?.contentOrNull?.let { it != "OK" } == true) throw BackupSinkException(SinkError.IO)
        return made?.get("drivewsid")?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO)
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
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
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            val parent = folder()
            val zone = zoneOf(parent)
            // iCloud would add a second file as "name 2" rather than replace
            // one, so the older copy goes to Recently Deleted first, as
            // rclone does it. Backups never share a name; sync files do.
            children(parent).filter { !it.folder && it.name == name }.forEach { trash(it) }

            val upload = drive({
                session.post(
                    "${it.docs}/ws/$zone/upload/web",
                    buildJsonObject {
                        put("filename", JsonPrimitive(name))
                        put("type", JsonPrimitive("FILE"))
                        put("size", JsonPrimitive(bytes.size.toString()))
                        put("content_type", JsonPrimitive(mimeType))
                    },
                )
            }, ::json).jsonArray.firstOrNull()?.jsonObject ?: throw BackupSinkException(SinkError.IO)
            val target = upload["url"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO)
            val document = upload["document_id"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO)

            val sent = drive({
                session.request(target).post(bytes.toRequestBody(mimeType.toMediaTypeOrNull())).build()
            }, ::json).jsonObject["singleFile"]?.jsonObject ?: throw BackupSinkException(SinkError.IO)

            val now = System.currentTimeMillis()
            val update = buildJsonObject {
                put("allow_conflict", JsonPrimitive(true))
                put("btime", JsonPrimitive(now))
                put("command", JsonPrimitive("add_file"))
                put("create_short_guid", JsonPrimitive(true))
                put(
                    "data",
                    buildJsonObject {
                        sent["receipt"]?.let { put("receipt", it) }
                        sent["referenceChecksum"]?.let { put("reference_signature", it) }
                        sent["fileChecksum"]?.let { put("signature", it) }
                        sent["size"]?.let { put("size", it) }
                        sent["wrappingKey"]?.let { put("wrapping_key", it) }
                    },
                )
                put("document_id", JsonPrimitive(document))
                put(
                    "file_flags",
                    buildJsonObject {
                        put("is_executable", JsonPrimitive(false))
                        put("is_hidden", JsonPrimitive(false))
                        put("is_writable", JsonPrimitive(true))
                    },
                )
                put("mtime", JsonPrimitive(now))
                put(
                    "path",
                    buildJsonObject {
                        put("path", JsonPrimitive(name))
                        put("starting_document_id", JsonPrimitive(parent.substringAfterLast("::")))
                    },
                )
            }
            drive({ session.post("${it.docs}/ws/$zone/update/documents", update) }) { }
            SinkEntry("FILE::$zone::$document", name, bytes.size.toLong(), now)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            children(folder()).filter { !it.folder && AutoBackupNaming.isListed(it.name) }
                .map { SinkEntry(it.id, it.name, it.size, it.modifiedMs) }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            val zone = zoneOf(entry.id)
            val document = entry.id.substringAfterLast("::")
            val token = drive({
                session.request("${it.docs}/ws/$zone/download/by_id?document_id=$document").get().build()
            }, ::json).jsonObject
            val url = (token["data_token"] ?: token["package_token"])?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?: throw BackupSinkException(SinkError.TARGET_MISSING)
            drive({ session.request(url).get().build() }) { it.body?.bytes() ?: ByteArray(0) }.inputStream()
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            val item = children(folder()).firstOrNull { it.id == entry.id } ?: return@runCancellable
            trash(item)
        }
    }

    private fun trash(item: Item) {
        val body = buildJsonObject {
            put(
                "items",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("drivewsid", JsonPrimitive(item.id))
                            put("etag", JsonPrimitive(item.etag))
                            put("clientId", JsonPrimitive(item.id))
                        },
                    ),
                ),
            )
        }
        drive({ session.post("${it.drive}/moveItemsToTrash", body) }) { }
    }

    private fun zoneOf(id: String): String = id.split("::").getOrNull(1)?.takeIf { it.isNotEmpty() } ?: DEFAULT_ZONE

    companion object {
        const val ID = "icloud"
        private const val DEFAULT_ZONE = "com.apple.CloudDocs"
        private const val ROOT_ID = "FOLDER::com.apple.CloudDocs::root"

        /** How long a trust token lasts, give or take, for the screen to say when a sign-in is due. */
        const val TRUST_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * The iCloud web sign-in and session: Apple's auth endpoints (`idmsa`), the
 * setup endpoint that hands out the service addresses, and the cookies that
 * carry the session between them. Shared by [ICloudSink] and [ICloudLogin].
 */
class ICloudSession(
    private val appleId: String,
    private val clientId: String,
    cookies: String,
    trustToken: String,
) {

    /** Where the Drive and Docs services are for this account, from `accountLogin` or `validate`. */
    data class Services(val drive: String, val docs: String, val needsWebAccessApproval: Boolean)

    enum class SignIn { DONE, NEEDS_CODE, REFUSED }

    /** iCloud said the session is gone (401, 421 or 423). */
    class Expired : Exception("icloud session expired")

    private val client by lazy { BackupHttp.client() }

    private val cookies = LinkedHashMap<String, String>().apply {
        for (pair in cookies.split(';')) {
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty() && pair.contains('=')) put(name, pair.substringAfter('=').trim())
        }
    }

    var trustToken: String = trustToken
        private set
    private var sessionToken = ""
    private var sessionId = ""
    private var scnt = ""
    private var accountCountry = ""
    private var authAttributes = ""
    private val frame = "auth-" + UUID.randomUUID().toString().lowercase()

    fun cookieString(): String = synchronized(cookies) { cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } }

    fun dropSession() = synchronized(cookies) { cookies.clear() }

    private fun absorb(response: Response) {
        synchronized(cookies) {
            for (header in response.headers("Set-Cookie")) {
                val cookie = Cookie.parse(response.request.url, header) ?: continue
                if (cookie.value.isEmpty() || cookie.expiresAt < System.currentTimeMillis()) cookies.remove(cookie.name)
                else cookies[cookie.name] = cookie.value
            }
        }
        response.header("X-Apple-ID-Account-Country")?.let { accountCountry = it }
        response.header("X-Apple-ID-Session-Id")?.let { sessionId = it }
        response.header("X-Apple-Session-Token")?.let { sessionToken = it }
        response.header("X-Apple-TwoSV-Trust-Token")?.let { trustToken = it }
        response.header("scnt")?.let { scnt = it }
        response.header("X-Apple-Auth-Attributes")?.let { authAttributes = it }
    }

    /** A request to an iCloud service, with the session's cookies. */
    fun request(url: String): Request.Builder = Request.Builder().url(url)
        .header("Origin", BASE)
        .header("Referer", "$BASE/")
        .header("User-Agent", USER_AGENT)
        .apply { cookieString().takeIf { it.isNotEmpty() }?.let { header("Cookie", it) } }

    fun post(url: String, body: JsonElement): Request =
        request(url).post(body.toString().toRequestBody(JSON_TYPE)).build()

    /** Runs [request]; a 2xx goes to [read], a dead session throws [Expired]. */
    fun <T> send(request: Request, what: String, read: (Response) -> T): T {
        val response = execute(request, what)
        response.use {
            absorb(it)
            if (it.isSuccessful) return read(it)
            BackupLog.w("$what ${request.url.encodedPath} -> HTTP ${it.code}")
            when (it.code) {
                HTTP_UNAUTHORIZED, HTTP_MISDIRECTED, HTTP_LOCKED -> throw Expired()
                HTTP_FORBIDDEN -> throw BackupSinkException(SinkError.PERMISSION_LOST)
                HTTP_NOT_FOUND -> throw BackupSinkException(SinkError.TARGET_MISSING)
                HTTP_INSUFFICIENT_STORAGE -> throw BackupSinkException(SinkError.OUT_OF_SPACE)
                else -> throw BackupSinkException(SinkError.IO)
            }
        }
    }

    private fun execute(request: Request, what: String): Response = try {
        client.newCall(request).execute()
    } catch (failure: Throwable) {
        BackupLog.w("$what failed to connect", failure)
        throw BackupSinkException(SinkError.IO, failure)
    }

    /** The headers every call to Apple's auth service carries, as the iCloud.com sign-in frame sends them. */
    private fun auth(path: String): Request.Builder = Request.Builder().url("$AUTH$path")
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .header("Origin", AUTH_ORIGIN)
        .header("Referer", "$AUTH_ORIGIN/")
        .header("X-Apple-Widget-Key", clientId)
        .header("X-Apple-OAuth-Client-Id", clientId)
        .header("X-Apple-OAuth-Client-Type", "firstPartyAuth")
        .header("X-Apple-OAuth-Redirect-URI", BASE)
        .header("X-Apple-OAuth-Require-Grant-Code", "true")
        .header("X-Apple-OAuth-Response-Mode", "web_message")
        .header("X-Apple-OAuth-Response-Type", "code")
        .header("X-Apple-OAuth-State", frame)
        .header("X-Apple-Frame-Id", frame)
        .header("X-Requested-With", "XMLHttpRequest")
        .header("X-Apple-Mandate-Security-Upgrade", "0")
        .header("X-Apple-I-Require-UE", "true")
        .header("X-Apple-I-FD-Client-Info", """{"U":"$USER_AGENT","L":"en-US","Z":"GMT+00:00","V":"1.1","F":""}""")
        .apply {
            if (authAttributes.isNotEmpty()) header("X-Apple-Auth-Attributes", authAttributes)
            if (scnt.isNotEmpty()) header("scnt", scnt)
            if (sessionId.isNotEmpty()) header("X-Apple-ID-Session-Id", sessionId)
        }

    private fun authCall(request: Request): Pair<Int, String> {
        val response = execute(request, "icloud auth")
        return response.use {
            absorb(it)
            it.code to (it.body?.string().orEmpty())
        }
    }

    /**
     * The password sign-in: SRP-6a with the trust token offered, so a
     * trusted session skips the code. [SignIn.NEEDS_CODE] when Apple wants
     * one (HTTP 409), [SignIn.REFUSED] for a wrong password.
     */
    fun signIn(password: String): SignIn {
        val account = appleId.trim().lowercase()
        val start = "$AUTH/authorize/signin".toHttpUrl().newBuilder()
            .addQueryParameter("frame_id", frame)
            .addQueryParameter("language", "en_US")
            .addQueryParameter("skVersion", "7")
            .addQueryParameter("iframeId", frame)
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", BASE)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("response_mode", "web_message")
            .addQueryParameter("state", frame)
            .addQueryParameter("authVersion", "latest")
            .build()
        authCall(Request.Builder().url(start).header("Accept", "*/*").header("User-Agent", USER_AGENT).get().build())

        val federate = buildJsonObject {
            put("accountName", JsonPrimitive(account))
            put("rememberMe", JsonPrimitive(true))
        }
        authCall(auth("/federate?isRememberMeEnabled=true").post(federate.toString().toRequestBody(JSON_TYPE)).build())

        val srp = ICloudSrp()
        val init = buildJsonObject {
            put("a", JsonPrimitive(Base64.encode(srp.publicA())))
            put("accountName", JsonPrimitive(account))
            put("protocols", JsonArray(listOf(JsonPrimitive("s2k"), JsonPrimitive("s2k_fo"))))
        }
        val (initCode, initBody) = authCall(auth("/signin/init").post(init.toString().toRequestBody(JSON_TYPE)).build())
        if (initCode !in 200..299) return if (initCode == HTTP_UNAUTHORIZED || initCode == HTTP_FORBIDDEN) SignIn.REFUSED else throw BackupSinkException(SinkError.IO)
        val challenge = runCatching { json.parseToJsonElement(initBody).jsonObject }.getOrNull() ?: throw BackupSinkException(SinkError.IO)
        val salt = Base64.decode(challenge["salt"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO))
        val b = Base64.decode(challenge["b"]?.jsonPrimitive?.contentOrNull ?: throw BackupSinkException(SinkError.IO))
        val iterations = challenge["iteration"]?.jsonPrimitive?.intOrNull ?: throw BackupSinkException(SinkError.IO)
        val protocol = challenge["protocol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val derived = ICloudSrp.derivePassword(password, salt, iterations, protocol) ?: throw BackupSinkException(SinkError.IO)
        val (m1, m2) = srp.proofs(account.toByteArray(), derived, salt, b) ?: throw BackupSinkException(SinkError.IO)

        val complete = buildJsonObject {
            put("accountName", JsonPrimitive(account))
            put("m1", JsonPrimitive(Base64.encode(m1)))
            put("m2", JsonPrimitive(Base64.encode(m2)))
            put("c", challenge["c"] ?: JsonPrimitive(""))
            put("rememberMe", JsonPrimitive(true))
            put("trustTokens", JsonArray(if (trustToken.isEmpty()) emptyList() else listOf(JsonPrimitive(trustToken))))
        }
        val (code, _) = authCall(auth("/signin/complete?isRememberMeEnabled=true").post(complete.toString().toRequestBody(JSON_TYPE)).build())
        return when (code) {
            in 200..299 -> SignIn.DONE
            HTTP_CONFLICT -> SignIn.NEEDS_CODE
            HTTP_PRECONDITION_FAILED -> {
                // An account Apple wants something "repaired" on (terms,
                // a security upgrade); the web sign-in acknowledges it.
                val (repaired, _) = authCall(auth("/repair/complete").post("{}".toRequestBody(JSON_TYPE)).build())
                if (repaired in 200..299) SignIn.DONE else SignIn.REFUSED
            }
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SignIn.REFUSED
            else -> throw BackupSinkException(SinkError.IO)
        }
    }

    /** Asks for the code on the trusted devices, or by text when there are none. True when one was sent by text. */
    fun requestCode(): Boolean {
        val (_, stateBody) = authCall(auth("").get().build())
        val state = runCatching { json.parseToJsonElement(stateBody).jsonObject }.getOrNull()
        val inner = state?.get("phoneNumberVerification")?.jsonObject ?: state
        val noDevices = inner?.get("noTrustedDevices")?.jsonPrimitive?.booleanOrNull == true
        val phone = inner?.get("trustedPhoneNumber")?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
            ?: inner?.get("trustedPhoneNumbers")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
        if (noDevices && phone != null) {
            val body = buildJsonObject {
                put("phoneNumber", buildJsonObject { put("id", JsonPrimitive(phone)) })
                put("mode", JsonPrimitive("sms"))
            }
            authCall(auth("/verify/phone").put(body.toString().toRequestBody(JSON_TYPE)).build())
            smsPhone = phone
            return true
        }
        authCall(auth("/verify/trusteddevice/securitycode").put(ByteArray(0).toRequestBody(null)).build())
        return false
    }

    private var smsPhone: Int? = null

    /** Checks the code, then trusts this session, which is what hands back the trust token. */
    fun submitCode(code: String): Boolean {
        val digits = code.filter { it.isDigit() }
        val phone = smsPhone
        val (path, body) = if (phone != null) {
            "/verify/phone/securitycode" to buildJsonObject {
                put("securityCode", buildJsonObject { put("code", JsonPrimitive(digits)) })
                put("phoneNumber", buildJsonObject { put("id", JsonPrimitive(phone)) })
                put("mode", JsonPrimitive("sms"))
            }
        } else {
            "/verify/trusteddevice/securitycode" to buildJsonObject {
                put("securityCode", buildJsonObject { put("code", JsonPrimitive(digits)) })
            }
        }
        val (status, _) = authCall(auth(path).post(body.toString().toRequestBody(JSON_TYPE)).build())
        // A 409 that carries a session token is Apple accepting the code.
        if (status !in 200..299 && !(status == HTTP_CONFLICT && sessionToken.isNotEmpty())) return false
        val (trusted, _) = authCall(auth("/2sv/trust").get().build())
        return trusted in 200..299
    }

    /** Trades the auth session for iCloud cookies and the service addresses. */
    fun accountLogin(): Services? {
        val body = buildJsonObject {
            put("accountCountryCode", JsonPrimitive(accountCountry))
            put("dsWebAuthToken", JsonPrimitive(sessionToken))
            put("extended_login", JsonPrimitive(true))
            put("trustToken", JsonPrimitive(trustToken))
        }
        return runCatching {
            send(request("$SETUP/accountLogin").post(body.toString().toRequestBody(JSON_TYPE)).build(), "icloud login") {
                services(it.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** The stored cookies' session, if iCloud still honours it. */
    fun validate(): Services? {
        if (cookieString().isEmpty()) return null
        return runCatching {
            send(request("$SETUP/validate").post(ByteArray(0).toRequestBody(JSON_TYPE)).build(), "icloud validate") {
                services(it.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun services(body: String): Services? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val web = root["webservices"]?.jsonObject ?: return null
        val drive = web["drivews"]?.jsonObject
        val docs = web["docws"]?.jsonObject
        val driveUrl = drive?.get("url")?.jsonPrimitive?.contentOrNull ?: return null
        val docsUrl = docs?.get("url")?.jsonPrimitive?.contentOrNull ?: return null
        val pcs = drive["pcsRequired"]?.jsonPrimitive?.booleanOrNull == true &&
            synchronized(cookies) { PCS_COOKIE !in cookies }
        return Services(driveUrl, docsUrl, pcs)
    }

    /**
     * With Advanced Data Protection, asks for the web access cookies, which
     * need an approval on a trusted device. Tries for [waitMs], every ten
     * seconds; a run at night tries once.
     */
    fun requestWebAccess(waitMs: Long): Boolean {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            val body = buildJsonObject {
                put("appName", JsonPrimitive("iclouddrive"))
                put("derivedFromUserAction", JsonPrimitive(true))
            }
            val status = runCatching {
                send(post("$SETUP/requestPCS", body), "icloud pcs") {
                    json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["status"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
            if (status == "success") return synchronized(cookies) { PCS_COOKIE in cookies }
            if (System.currentTimeMillis() + PCS_POLL_MS > until) return false
            Thread.sleep(PCS_POLL_MS)
        }
    }

    companion object {
        /** The iCloud.com web sign-in's own widget key, which every client of this API sends. */
        const val CLIENT_ID = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"

        internal val json = Json { ignoreUnknownKeys = true }

        private const val BASE = "https://www.icloud.com"
        private const val SETUP = "https://setup.icloud.com/setup/ws/1"
        private const val AUTH = "https://idmsa.apple.com/appleauth/auth"
        private const val AUTH_ORIGIN = "https://idmsa.apple.com"
        private const val PCS_COOKIE = "X-APPLE-WEBAUTH-PCS-Documents"
        private const val PCS_POLL_MS = 10_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_PRECONDITION_FAILED = 412
        private const val HTTP_MISDIRECTED = 421
        private const val HTTP_LOCKED = 423
        private const val HTTP_INSUFFICIENT_STORAGE = 507
        private val JSON_TYPE = "application/json".toMediaTypeOrNull()
    }
}

/**
 * Apple's SRP-6a: the 2048-bit group of RFC 5054 with SHA-256, and the
 * password stretched by PBKDF2 over its own SHA-256 (`s2k`), or over that
 * hash's hex (`s2k_fo`) for older accounts. x leaves the user name out, as
 * Apple's does. Public for the tests.
 */
class ICloudSrp(secret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {

    private val a = BigInteger(1, secret)
    private val bigA = G.modPow(a, N)

    fun publicA(): ByteArray = pad(bigA)

    /** M1 and M2 for the server's [serverB], or null when B is not a valid value. */
    fun proofs(user: ByteArray, derivedKey: ByteArray, salt: ByteArray, serverB: ByteArray): Pair<ByteArray, ByteArray>? {
        val b = BigInteger(1, serverB)
        if (b.signum() <= 0 || b >= N) return null
        val x = BigInteger(1, sha(salt, sha(":".toByteArray(), derivedKey)))
        val u = BigInteger(1, sha(pad(bigA), pad(b)))
        if (u.signum() == 0) return null
        val k = BigInteger(1, sha(N.toByteArray().unsigned(), pad(G)))
        val base = b.subtract(k.multiply(G.modPow(x, N))).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        val key = sha(pad(s))
        val hg = sha(pad(G))
        val hn = sha(N.toByteArray().unsigned())
        val xor = ByteArray(hg.size) { (hg[it].toInt() xor hn[it].toInt()).toByte() }
        val m1 = sha(xor, sha(user), salt, pad(bigA), pad(b), key)
        val m2 = sha(pad(bigA), m1, key)
        return m1 to m2
    }

    companion object {
        val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
                "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
                "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
                "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
                "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
                "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861602790" +
                "04E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8" +
                "E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F" +
                "9E4AFF73",
            16,
        )
        val G: BigInteger = BigInteger.valueOf(2)
        private const val N_BYTES = 256

        /** The stretched password, or null for a protocol this does not know. */
        fun derivePassword(password: String, salt: ByteArray, iterations: Int, protocol: String): ByteArray? {
            val hash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
            val input = when (protocol) {
                "s2k" -> hash
                "s2k_fo" -> hash.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
                else -> return null
            }
            // PBKDF2 keyed by raw bytes: BackupCrypto's takes chars and
            // UTF-8 encodes them, which would mangle the s2k hash's bytes.
            return pbkdf2Bytes(input, salt, iterations, 32)
        }

        private fun pbkdf2Bytes(key: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            val out = ByteArray(length)
            var block = 1
            var at = 0
            while (at < length) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val acc = u.copyOf()
                repeat(iterations - 1) {
                    u = mac.doFinal(u)
                    for (i in acc.indices) acc[i] = (acc[i].toInt() xor u[i].toInt()).toByte()
                }
                val n = minOf(acc.size, length - at)
                System.arraycopy(acc, 0, out, at, n)
                at += n
                block++
            }
            return out
        }

        private fun sha(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            for (p in parts) digest.update(p)
            return digest.digest()
        }

        private fun ByteArray.unsigned(): ByteArray = if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this

        private fun pad(n: BigInteger): ByteArray {
            val raw = n.toByteArray().unsigned()
            if (raw.size >= N_BYTES) return raw
            return ByteArray(N_BYTES - raw.size) + raw
        }
    }
}

/**
 * Signing in to iCloud from the settings screen. [start] does the password
 * half and asks Apple to send a code; [finish] takes the code. Between the
 * two the screen holds the [ICloudSession].
 */
object ICloudLogin {

    sealed interface Outcome {
        /** Signed in: what the location keeps. */
        data class SignedIn(val trustToken: String, val cookies: String) : Outcome

        /** A code is on its way: to the trusted devices, or by text when [bySms]. */
        data class NeedsCode(val session: ICloudSession, val bySms: Boolean) : Outcome

        /** With Advanced Data Protection: approve the sign-in on a trusted device, then try again. */
        data object NeedsApproval : Outcome

        data class Failed(val error: SinkError) : Outcome
    }

    suspend fun start(appleId: String, password: String, clientId: String): Outcome = withContext(Dispatchers.IO) {
        try {
            val session = ICloudSession(appleId, clientId, cookies = "", trustToken = "")
            when (session.signIn(password)) {
                ICloudSession.SignIn.REFUSED -> Outcome.Failed(SinkError.PERMISSION_LOST)
                ICloudSession.SignIn.NEEDS_CODE -> Outcome.NeedsCode(session, bySms = session.requestCode())
                ICloudSession.SignIn.DONE -> finishLogin(session)
            }
        } catch (failure: BackupSinkException) {
            Outcome.Failed(failure.reason)
        } catch (failure: Exception) {
            BackupLog.w("icloud sign-in failed", failure)
            Outcome.Failed(SinkError.IO)
        }
    }

    suspend fun finish(session: ICloudSession, code: String): Outcome = withContext(Dispatchers.IO) {
        try {
            if (!session.submitCode(code)) Outcome.Failed(SinkError.PERMISSION_LOST) else finishLogin(session)
        } catch (failure: BackupSinkException) {
            Outcome.Failed(failure.reason)
        } catch (failure: Exception) {
            BackupLog.w("icloud code failed", failure)
            Outcome.Failed(SinkError.IO)
        }
    }

    private fun finishLogin(session: ICloudSession): Outcome {
        val services = session.accountLogin() ?: return Outcome.Failed(SinkError.PERMISSION_LOST)
        if (services.needsWebAccessApproval && !session.requestWebAccess(waitMs = APPROVAL_WAIT_MS)) return Outcome.NeedsApproval
        if (session.trustToken.isEmpty()) return Outcome.Failed(SinkError.PERMISSION_LOST)
        return Outcome.SignedIn(session.trustToken, session.cookieString())
    }

    private const val APPROVAL_WAIT_MS = 2 * 60_000L
}
