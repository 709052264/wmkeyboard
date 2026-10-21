package com.wasimaster.wmkeyboard.core.input.rime

import android.content.Context
import android.os.UserManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * librime's one instance in this process: whether it can run, compiling
 * ("deploying") the installed schemas, and the single session every
 * [RimeComposer] types through.
 *
 * The keyboard asks from the main thread on every keystroke and must never
 * wait, so every session call takes [lock] with `tryLock`: while a deploy
 * holds it, or the library is not loaded, a composer gets null back and
 * converts with its built-in twin instead. Deploying and starting run on
 * [scope], off the main thread.
 *
 * The session mirrors the keyboard's buffer rather than the keys pressed:
 * the keyboard's contract hands a composer the whole reading each time
 * ([com.wasimaster.wmkeyboard.core.input.composer.Composer.candidates]), so
 * the session is given that reading, and kept, while the keyboard works
 * through it pick by pick. A pick that consumes a prefix leaves RIME's own
 * composition one segment further on, which is how its user dictionary
 * learns the whole phrase when the last piece is picked.
 */
object RimeRuntime {

    sealed interface Status {
        /** Nothing installed, or not asked yet. */
        data object Off : Status

        /** Before the first unlock: RIME's files are behind the credential. */
        data object Locked : Status

        /** The library is not on the device yet ([FeatureModules.rime]). */
        data object NeedsLibrary : Status

        /** Packs changed since the last deploy, or there never was one. */
        data object NeedsDeploy : Status

        /** Compiling [current], the [done]th of [total]. */
        data class Deploying(val done: Int, val total: Int, val current: String?) : Status

        data class Ready(val schemas: List<RimeSchema>) : Status

        /** The last deploy or start failed; [detail] is the system's English. */
        data class Failed(val detail: String) : Status
    }

    data class RimeSchema(val id: String, val name: String)

    /**
     * A menu, with how much of the asked-for input each candidate covers, in
     * input characters, and each candidate's comment: the spelling it was
     * found under where the schema hints spellings (`xi an`), else empty.
     */
    internal class Menu(val texts: List<String>, val consumed: IntArray, val comments: List<String>)

    private const val TAG = "RimeRuntime"

    private val mutableStatus = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val lock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var filesDir: File? = null
    @Volatile private var version: String = ""
    @Volatile private var started = false
    @Volatile private var refreshJob: Job? = null
    @Volatile private var deployJob: Job? = null

    /** The compiled schemas' ids, in deploy order. */
    @Volatile
    var schemaIds: List<String> = emptyList()
        private set

    /** Bumped when what the session would answer changes: a deploy, a learnt pick. */
    @Volatile
    var generation: Int = 0
        private set

    // The session and its mirror of the keyboard's buffer; guarded by [lock].
    private var session = 0L
    private var currentSchema: String? = null
    private var currentOptions: RimeOptions? = null
    private var mirrorInput = ""
    private var mirrorChosen = 0

    /** Whether composers may type through RIME right now. */
    val ready: Boolean get() = started

    /** Whether this build has librime at all, fetched or not; for the licences screen. */
    val libraryShipped: Boolean get() = RimeNative.library.shipped

    val deploying: Boolean get() = deployJob?.isActive == true

    /**
     * Points the runtime at this install and checks, off the main thread,
     * whether RIME can start: storage unlocked, library here, a deploy that
     * matches what is installed. Starts it when it can. Cheap to call again.
     */
    fun prepare(context: Context) {
        val app = context.applicationContext
        val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) {
            mutableStatus.value = Status.Locked
            return
        }
        if (filesDir == null) {
            filesDir = app.filesDir
            version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        }
        if (started || deploying || refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    /** Re-reads the disk, after a download, a delete or an import. */
    fun recheck(context: Context) {
        if (deploying) return
        prepare(context)
        if (started) refreshJob = scope.launch { refresh() }
    }

    private fun refresh() {
        val dir = filesDir ?: return
        val desired = RimeStore.desiredToken(dir)
        if (desired.isEmpty()) {
            stop(Status.Off)
            return
        }
        val deployed = RimeStore.deployed(dir)
        if (deployed == null || deployed.first != desired) {
            stop(Status.NeedsDeploy)
            return
        }
        if (started) return
        if (!RimeNative.library.available) {
            mutableStatus.value = Status.NeedsLibrary
            return
        }
        lock.lock()
        try {
            startLocked(dir, deployed.second)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Compiles every installed schema, reporting each through [status], then
     * starts the session on the result. The library must be here: the caller
     * fetches it first. Runs on [scope]; the returned job finishes when it does.
     */
    fun deploy(context: Context): Job {
        deployJob?.takeIf { it.isActive }?.let { return it }
        prepare(context)
        val job = scope.launch { deployNow() }
        deployJob = job
        return job
    }

    private fun deployNow() {
        val dir = filesDir ?: return
        if (!RimeNative.library.available) {
            mutableStatus.value = Status.NeedsLibrary
            return
        }
        mutableStatus.value = Status.Deploying(0, 0, null)
        lock.lock()
        try {
            stopLocked()
            ConversionEngines.changed()
            RimeStore.clearDeploy(dir)
            RimeStore.syncShared(dir)
            val token = RimeStore.desiredToken(dir)
            if (token.isEmpty()) {
                mutableStatus.value = Status.Off
                return
            }
            val files = RimeStore.schemaFiles(dir)
            val shared = RimeStore.sharedDir(dir).path
            val user = RimeStore.userDir(dir).apply { mkdirs() }.path
            val ids = files.mapNotNull(RimeStore::schemaId).distinct()
            RimeStore.writeSchemaList(dir, ids)
            RimeNative.nativeBeginDeploy(shared, user, version)
            RimeNative.nativeDeployConfig("default.yaml", "config_version")
            val compiled = ArrayList<String>()
            files.forEachIndexed { index, file ->
                mutableStatus.value = Status.Deploying(index, files.size, RimeStore.schemaName(file) ?: file.name)
                val id = RimeStore.schemaId(file)
                if (RimeNative.nativeDeploySchema(file.path) && id != null && id !in compiled) {
                    compiled += id
                } else {
                    DebugLog.w(TAG, "schema ${file.name} did not compile")
                }
            }
            mutableStatus.value = Status.Deploying(files.size, files.size, null)
            // The list names only what compiled, so the schema list and the
            // pickers never offer one that cannot load.
            if (compiled != ids) {
                RimeStore.writeSchemaList(dir, compiled)
                RimeNative.nativeDeployConfig("default.yaml", "config_version")
            }
            RimeStore.recordDeploy(dir, token, compiled)
            startLocked(dir, compiled)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            DebugLog.w(TAG, "deploy failed: ${e.javaClass.simpleName}: ${e.message}")
            mutableStatus.value = Status.Failed(e.message.orEmpty())
        } finally {
            lock.unlock()
        }
    }

    private fun startLocked(dir: File, compiled: List<String>) {
        if (started) return
        if (!RimeNative.nativeStart(RimeStore.sharedDir(dir).path, RimeStore.userDir(dir).path, version)) {
            mutableStatus.value = Status.Failed("librime did not start")
            return
        }
        session = RimeNative.nativeCreateSession()
        currentSchema = null
        currentOptions = null
        mirrorInput = ""
        mirrorChosen = 0
        val names = RimeNative.nativeSchemaList().associate { it.substringBefore('\t') to it.substringAfter('\t', "") }
        schemaIds = compiled
        generation++
        started = true
        mutableStatus.value = Status.Ready(compiled.map { RimeSchema(it, names[it].orEmpty().ifBlank { it }) })
        ConversionEngines.changed()
    }

    /** Stops librime and drops the session; the composers fall back to the built-in ones. */
    private fun stop(status: Status) {
        lock.lock()
        try {
            stopLocked()
        } finally {
            lock.unlock()
        }
        mutableStatus.value = status
        ConversionEngines.changed()
    }

    private fun stopLocked() {
        if (!started) return
        started = false
        if (session != 0L) RimeNative.nativeDestroySession(session)
        session = 0L
        RimeNative.nativeStop()
    }

    /** Deletes every pack's unpacked files, the imports, the compiled schemas and the user dictionaries. */
    fun deleteAll(context: Context) {
        val dir = context.applicationContext.filesDir
        scope.launch {
            stop(Status.Off)
            RimeStore.deleteAll(dir)
        }
    }

    /**
     * The candidates of [input] under [schema], or null when RIME cannot
     * answer right now (deploying, not started, the schema gone).
     */
    internal fun menu(schema: String, options: RimeOptions, input: String, limit: Int): Menu? =
        withSession { session ->
            val offset = syncLocked(session, schema, options, input) ?: return@withSession null
            val spans = IntArray(limit + 1)
            val comments = arrayOfNulls<String>(limit)
            val texts = RimeNative.nativeMenu(session, limit, spans, comments) ?: return@withSession null
            val chars = byteToChar(mirrorInput)
            val consumed = IntArray(texts.size) { i ->
                (chars(spans[i + 1]) - offset).coerceIn(1, input.length.coerceAtLeast(1))
            }
            Menu(texts.toList(), consumed, List(texts.size) { i -> comments[i].orEmpty() })
        }

    /**
     * Picks candidate [index] of [input]'s menu, which covered [consumed]
     * input characters, so RIME learns it. Keeps the session on the rest of
     * the input when the pick did not finish it.
     */
    internal fun pick(schema: String, options: RimeOptions, input: String, index: Int, consumed: Int) {
        withSession { session ->
            val offset = syncLocked(session, schema, options, input) ?: return@withSession
            val committed = RimeNative.nativeSelect(session, index) ?: return@withSession
            if (committed.isNotEmpty() || offset + consumed >= mirrorInput.length) {
                mirrorInput = ""
                mirrorChosen = 0
            } else {
                mirrorChosen = offset + consumed
            }
            generation++
        }
    }

    private inline fun <T> withSession(block: (Long) -> T): T? {
        if (!started || !lock.tryLock()) return null
        try {
            if (!started || session == 0L) return null
            return block(session)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Brings the session to [input]: the same schema and options, and either
     * the rest of the input it is already working through or [input] afresh.
     * Returns where [input] starts in the session's input, in characters, or
     * null when the schema would not load.
     */
    private fun syncLocked(session: Long, schema: String, options: RimeOptions, input: String): Int? {
        if (currentSchema != schema) {
            if (!RimeNative.nativeSelectSchema(session, schema)) {
                currentSchema = null
                return null
            }
            currentSchema = schema
            currentOptions = null
            mirrorInput = ""
        }
        if (currentOptions != options) {
            RimeNative.nativeSetOption(session, "ascii_mode", false)
            for ((name, value) in options.values) RimeNative.nativeSetOption(session, name, value)
            currentOptions = options
            mirrorInput = ""
        }
        if (mirrorInput.isNotEmpty() && mirrorChosen <= mirrorInput.length && mirrorInput.substring(mirrorChosen) == input) {
            return mirrorChosen
        }
        RimeNative.nativeSetInput(session, input)
        mirrorInput = input
        mirrorChosen = 0
        return 0
    }

    /** Maps a UTF-8 byte offset into [text] to a character offset. */
    private fun byteToChar(text: String): (Int) -> Int {
        if (text.all { it.code < 0x80 }) return { it.coerceIn(0, text.length) }
        val offsets = IntArray(text.length + 1)
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            bytes += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            for (k in 1..width) offsets[i + k] = bytes
            i += width
        }
        return { byte -> offsets.indexOfFirst { it >= byte }.let { if (it < 0) text.length else it } }
    }
}

/**
 * The switches the keyboard sets on every schema, by RIME option name. They
 * drive RIME's Simplified and Traditional filters, which work inside its menu,
 * before any candidate's span is measured, so a converted candidate still
 * covers exactly the input it did. A schema without a given switch ignores it.
 */
internal data class RimeOptions(val values: Map<String, Boolean>)
