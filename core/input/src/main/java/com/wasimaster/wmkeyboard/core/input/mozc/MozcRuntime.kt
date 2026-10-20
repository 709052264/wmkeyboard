package com.wasimaster.wmkeyboard.core.input.mozc

import android.content.Context
import android.os.UserManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Mozc's one instance in this process: whether it can run, opening it on
 * the downloaded dictionary, and the converter every [MozcComposer] asks.
 *
 * The keyboard asks from the main thread on every keystroke and must never
 * wait, so every converter call takes [lock] with `tryLock`: while Mozc is
 * opening or closing, a composer gets null back and converts with the
 * built-in composer instead. Opening (it maps a dictionary of some tens of
 * megabytes) runs on [scope].
 *
 * What Mozc learns lives in `filesDir/mozc/user`, credential-protected like
 * the other learned words, so before the first unlock Mozc does not start
 * and Japanese types on the built-in engine.
 */
object MozcRuntime {

    sealed interface Status {
        /** Not asked yet, or Mozc is not picked. */
        data object Off : Status

        /** Before the first unlock: the dictionary and the learning are behind the credential. */
        data object Locked : Status

        /** The library is not on the device yet ([com.wasimaster.wmkeyboard.core.modules.FeatureModules.mozc]). */
        data object NeedsLibrary : Status

        /** The dictionary pack ([MozcData.pack]) is not downloaded. */
        data object NeedsData : Status

        data object Starting : Status

        /** Converting, on dictionary [dataVersion]. */
        data class Ready(val dataVersion: String) : Status

        /** Mozc would not open the dictionary; [detail] is its own English. */
        data class Failed(val detail: String) : Status
    }

    /** One answer: candidate texts, their ids and consumed reading characters, and the segments. */
    internal class Menu(
        val texts: List<String>,
        val ids: IntArray,
        val consumed: IntArray,
        /** Conversion only: each segment's reading and best text. */
        val segments: List<Pair<String, String>>,
        val focus: Int,
    )

    private const val TAG = "MozcRuntime"

    /** How long after a pick the learnt history is written out, so a burst of picks writes once. */
    private const val SYNC_DELAY_MS = 5_000L

    private val mutableStatus = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val lock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var filesDir: File? = null
    @Volatile private var started = false
    @Volatile private var startJob: Job? = null
    @Volatile private var syncJob: Job? = null
    @Volatile private var looseMarks = true

    /** Bumped when what Mozc would answer changes: opening, a learnt pick, a cleared history. */
    @Volatile
    var generation: Int = 0
        private set

    /** Whether composers may convert through Mozc right now. */
    val ready: Boolean get() = started

    /** Whether this build has the library at all, fetched or not; for the licences screen. */
    val libraryShipped: Boolean get() = MozcNative.library.shipped

    fun userDir(filesDir: File) = File(filesDir, "mozc/user")

    /**
     * Points the runtime at this install and opens Mozc off the main thread
     * when it can: storage unlocked, library here, dictionary downloaded.
     * Cheap to call again.
     */
    fun prepare(context: Context) {
        val app = context.applicationContext
        val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) {
            mutableStatus.value = Status.Locked
            return
        }
        if (filesDir == null) filesDir = app.filesDir
        if (started || startJob?.isActive == true) return
        startJob = scope.launch { start() }
    }

    /** Re-reads the disk, after the dictionary was downloaded, imported or deleted. */
    fun recheck(context: Context) {
        val dir = context.applicationContext.filesDir
        if (started && MozcData.file(dir) == null) {
            scope.launch { stop(Status.NeedsData) }
            return
        }
        prepare(context)
    }

    /** Closes Mozc, saving its history; the composers fall back to the built-in one. */
    fun shutDown() {
        scope.launch { stop(Status.Off) }
    }

    private fun start() {
        val dir = filesDir ?: return
        val data = MozcData.file(dir)
        if (data == null) {
            mutableStatus.value = Status.NeedsData
            return
        }
        if (!MozcNative.library.available) {
            mutableStatus.value = Status.NeedsLibrary
            return
        }
        mutableStatus.value = Status.Starting
        lock.lock()
        try {
            if (started) return
            val user = userDir(dir).apply { mkdirs() }
            // A library from another build of the bridge links but lacks a
            // symbol; that is a failed start, not a crash of the keyboard.
            val error = try {
                MozcNative.nativeStart(data.path, user.path)
            } catch (e: UnsatisfiedLinkError) {
                e.message ?: "libwmmozc does not match this version"
            }
            if (error != null) {
                DebugLog.w(TAG, "start failed: $error")
                mutableStatus.value = Status.Failed(error)
                return
            }
            MozcNative.nativeSetKanaModifierInsensitive(looseMarks)
            started = true
            generation++
            mutableStatus.value = Status.Ready(MozcNative.nativeDataVersion())
        } finally {
            lock.unlock()
        }
        ConversionEngines.changed()
    }

    private fun stop(status: Status) {
        lock.lock()
        try {
            if (started) {
                started = false
                MozcNative.nativeStop()
                generation++
            }
        } finally {
            lock.unlock()
        }
        mutableStatus.value = status
        ConversionEngines.changed()
    }

    /** "Match kana without marks", which Mozc has its own version of. */
    fun setLooseMarks(on: Boolean) {
        if (looseMarks == on) return
        looseMarks = on
        if (withLock { MozcNative.nativeSetKanaModifierInsensitive(on) } != null) generation++
    }

    internal fun suggest(reading: String, preceding: String, limit: Int): Menu? = withLock {
        val info = IntArray(3 + 2 * limit)
        MozcNative.nativeSuggest(reading, preceding, limit, info)?.let { unpack(it, info) }
    }

    internal fun convert(reading: String, preceding: String, ops: IntArray, limit: Int): Menu? = withLock {
        val info = IntArray(3 + 2 * limit)
        MozcNative.nativeConvert(reading, preceding, ops, limit, info)?.let { unpack(it, info) }
    }

    internal fun predict(preceding: String, limit: Int): Menu? = withLock {
        val info = IntArray(3 + 2 * limit)
        MozcNative.nativePredict(preceding, limit, info)?.let { unpack(it, info) }
    }

    internal fun commitSuggestion(reading: String, preceding: String, id: Int) =
        learnt { MozcNative.nativeCommitSuggestion(reading, preceding, id) }

    internal fun commitConversion(reading: String, preceding: String, ops: IntArray, id: Int) =
        learnt { MozcNative.nativeCommitConversion(reading, preceding, ops, id) }

    internal fun commitPrediction(preceding: String, id: Int) =
        learnt { MozcNative.nativeCommitPrediction(preceding, id) }

    /** The caret moved or the field changed: what was committed before is no context any more. */
    fun resetContext() {
        withLock { MozcNative.nativeResetContext() }
    }

    /** Forgets everything Mozc learnt, on disk too. */
    fun clearHistory(context: Context) {
        val dir = context.applicationContext.filesDir
        scope.launch {
            lock.lock()
            try {
                if (started) MozcNative.nativeClearHistory() else userDir(dir).deleteRecursively()
                generation++
            } finally {
                lock.unlock()
            }
        }
    }

    /** Bytes of learnt history on disk, for the storage screen. */
    fun historySize(filesDir: File): Long =
        userDir(filesDir).walkBottomUp().filter(File::isFile).sumOf(File::length)

    private fun learnt(commit: () -> Int) {
        val result = withLock(commit) ?: return
        if (result < 0) return
        generation++
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(SYNC_DELAY_MS)
            lock.lock()
            try {
                if (started) MozcNative.nativeSync()
            } finally {
                lock.unlock()
            }
        }
    }

    private inline fun <T> withLock(block: () -> T): T? {
        if (!started || !lock.tryLock()) return null
        try {
            if (!started) return null
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun unpack(strings: Array<String>, info: IntArray): Menu {
        val count = info[0].coerceIn(0, strings.size)
        val segmentCount = info[2].coerceAtLeast(0)
        val segments = (0 until segmentCount).mapNotNull { i ->
            val at = count + 2 * i
            if (at + 1 < strings.size) strings[at] to strings[at + 1] else null
        }
        return Menu(
            texts = strings.take(count),
            ids = IntArray(count) { info[3 + 2 * it] },
            consumed = IntArray(count) { info[4 + 2 * it] },
            segments = segments,
            focus = info[1],
        )
    }
}
