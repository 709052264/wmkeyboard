package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.PCloudConfig
import com.wasimaster.wmkeyboard.core.settings.PCloudRegion
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A [BackupSink] over a folder in pCloud, through pCloud's own HTTP API.
 *
 * pCloud also has a WebDAV door, which the WebDAV location's preset reaches,
 * but that one takes the account password on every request and has no answer
 * for an account with two-step verification. This signs in once
 * ([PCloudLogin]) and keeps the token that hands back.
 *
 * Every call answers HTTP 200 with a `result` number in the body: 0 is
 * success, anything else an error, which [resultError] reads.
 */
class PCloudSink(private val config: PCloudConfig) : BackupSink {

    override val id: String get() = ID

    private val client by lazy { BackupHttp.client() }

    private val folder: String = "/" + config.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

    private fun pathOf(name: String) = if (folder == "/") "/$name" else "$folder/$name"

    private fun url(method: String, vararg query: Pair<String, String>): HttpUrl =
        HttpUrl.Builder().scheme("https").host(config.region.apiHost).addPathSegment(method)
            .addQueryParameter("auth", config.auth)
            .apply { for ((k, v) in query) addQueryParameter(k, v) }
            .build()

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
            ensureFolder()
        }
    }

    private fun ensureFolder() {
        var at = ""
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            at += "/$part"
            call(Request.Builder().url(url("createfolderifnotexists", "path" to at)).get().build())
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            ensureFolder()
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody(OCTET_STREAM))
                .build()
            // nopartial: a cut-off upload leaves nothing behind rather than a
            // short file under the real name. A file already of this name is
            // replaced, which is what renameifexists left off does.
            val answer = call(
                Request.Builder()
                    .url(url("uploadfile", "path" to folder, "filename" to name, "nopartial" to "1"))
                    .post(form).build(),
            )
            answer["metadata"]?.jsonArray?.firstOrNull()?.jsonObject?.let(::entryOf)
                ?: SinkEntry(pathOf(name), name, bytes.size.toLong(), 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val answer = call(Request.Builder().url(url("listfolder", "path" to folder)).get().build())
            answer["metadata"]?.jsonObject?.get("contents")?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["isfolder"]?.jsonPrimitive?.booleanOrNull != true }
                .mapNotNull(::entryOf)
                .filter { AutoBackupNaming.isListed(it.name) }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            val link = call(Request.Builder().url(url("getfilelink", "path" to pathOf(entry.name))).get().build())
            val host = link["hosts"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            val path = link["path"]?.jsonPrimitive?.contentOrNull
            if (host == null || path == null) throw BackupSinkException(SinkError.IO)
            val response = try {
                client.newCall(Request.Builder().url("https://$host$path").get().build()).execute()
            } catch (failure: Throwable) {
                throw BackupSinkException(SinkError.IO, failure)
            }
            response.use {
                if (!it.isSuccessful) throw BackupSinkException(SinkError.IO)
                (it.body?.bytes() ?: ByteArray(0)).inputStream()
            }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            try {
                call(Request.Builder().url(url("deletefile", "path" to pathOf(entry.name))).get().build())
            } catch (gone: BackupSinkException) {
                if (gone.reason != SinkError.TARGET_MISSING) throw gone
            }
            Unit
        }
    }

    private fun entryOf(item: JsonObject): SinkEntry? {
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return SinkEntry(
            id = pathOf(name),
            name = name,
            sizeBytes = item["size"]?.jsonPrimitive?.longOrNull ?: -1L,
            modifiedAtMs = item["modified"]?.jsonPrimitive?.contentOrNull?.let(::parseRfc1123) ?: 0L,
        )
    }

    private fun call(request: Request): JsonObject = PCloudApi.call(client, request, "pcloud ${request.url.encodedPath}")

    companion object {
        const val ID = "pcloud"

        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()

        /** pCloud's times are `Thu, 21 Mar 2013 18:31:37 +0000`. */
        fun parseRfc1123(value: String): Long = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(value)?.time ?: 0L
        }.getOrDefault(0L)

        /**
         * What a pCloud `result` means to the user. 1000 is "log in
         * required", 2000 "log in failed", 2094 an invalid token and 2297 a
         * two-step code wanted; 2005 and 2009 are a missing folder and file;
         * 2008 is a full account. Public for the tests.
         */
        fun resultError(result: Int): SinkError = when (result) {
            1000, 2000, 2094, 2095, 2297, 2064 -> SinkError.PERMISSION_LOST
            2005, 2009, 2002 -> SinkError.TARGET_MISSING
            2008 -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }
    }
}

/** The one HTTP call every pCloud request is: 200 always, a `result` in the body. */
internal object PCloudApi {

    private val json = Json { ignoreUnknownKeys = true }

    /** Signals a non-zero `result` together with the body that carried it. */
    class Failure(val result: Int, val body: JsonObject) : Exception("pcloud result $result")

    fun call(client: okhttp3.OkHttpClient, request: Request, what: String, raw: Boolean = false): JsonObject {
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            if (failure is BackupSinkException) throw failure
            BackupLog.w("$what failed to connect", failure)
            throw BackupSinkException(SinkError.IO, failure)
        }
        val body = response.use {
            if (!it.isSuccessful) {
                BackupLog.w("$what -> HTTP ${it.code}")
                throw BackupSinkException(SinkError.IO)
            }
            it.body?.string().orEmpty()
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)
        val result = root["result"]?.jsonPrimitive?.intOrNull ?: 0
        if (result != 0) {
            BackupLog.w("$what -> result $result")
            if (raw) throw Failure(result, root)
            throw BackupSinkException(PCloudSink.resultError(result))
        }
        return root
    }
}

/**
 * Signing in to pCloud, from the settings screen: the account password once,
 * and the two-step code when the account has one. What comes back is the
 * token [PCloudConfig.auth] keeps.
 */
object PCloudLogin {

    sealed interface Outcome {
        data class SignedIn(val auth: String) : Outcome

        /** The account has two-step verification. [token] goes back with the code. */
        data class NeedsCode(val token: String) : Outcome

        /** The account lives in the other region; sign in there instead. */
        data class OtherRegion(val region: PCloudRegion) : Outcome

        data class Failed(val error: SinkError) : Outcome
    }

    private val client by lazy { BackupHttp.client() }

    suspend fun signIn(region: PCloudRegion, email: String, password: String): Outcome = withContext(Dispatchers.IO) {
        // POST, so the password is in the body and never in an address that
        // something might log.
        val form = FormBody.Builder()
            .add("getauth", "1")
            .add("logout", "1")
            .add("username", email.trim())
            .add("password", password)
            .add("device", "WM Keyboard")
            .build()
        answer(region) { Request.Builder().url("https://${region.apiHost}/userinfo").post(form).build() }
    }

    suspend fun submitCode(region: PCloudRegion, token: String, code: String): Outcome = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("token", token)
            .add("code", code.filter { !it.isWhitespace() })
            .add("trustdevice", "1")
            .build()
        answer(region) { Request.Builder().url("https://${region.apiHost}/tfa_login").post(form).build() }
    }

    private inline fun answer(region: PCloudRegion, request: () -> Request): Outcome = try {
        val root = PCloudApi.call(client, request(), "pcloud login", raw = true)
        root["auth"]?.jsonPrimitive?.contentOrNull?.let { Outcome.SignedIn(it) } ?: Outcome.Failed(SinkError.IO)
    } catch (failure: PCloudApi.Failure) {
        when (failure.result) {
            TFA_REQUIRED -> failure.body["token"]?.jsonPrimitive?.contentOrNull
                ?.let { Outcome.NeedsCode(it) } ?: Outcome.Failed(SinkError.IO)
            // "This user is in another location": the answer names the API
            // host of the region the account is in.
            OTHER_LOCATION -> {
                val host = failure.body["location"]?.jsonObject?.get("api")?.jsonPrimitive?.contentOrNull
                Outcome.OtherRegion(PCloudRegion.entries.firstOrNull { it.apiHost == host } ?: PCloudRegion.entries.first { it != region })
            }
            else -> Outcome.Failed(PCloudSink.resultError(failure.result))
        }
    } catch (failure: BackupSinkException) {
        Outcome.Failed(failure.reason)
    }

    private const val TFA_REQUIRED = 2297
    private const val OTHER_LOCATION = 2321
}
