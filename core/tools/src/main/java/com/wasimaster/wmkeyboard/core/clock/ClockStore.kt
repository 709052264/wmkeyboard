package com.wasimaster.wmkeyboard.core.clock

import android.content.Context
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The clock tool's data, one per process.
 *
 * One object rather than one per screen because the keyboard, the settings
 * app, the alarm receiver and the ring service all live in this process and
 * all change the same alarms: a single [state] means a snooze pressed in the
 * shade shows on the keyboard's Alarms tab the moment it happens, with no
 * reload and no race between two copies.
 *
 * The file sits in **device-protected** storage. An alarm set for 6:00 has to
 * ring at 6:00 after a night-time reboot, before anyone has unlocked the phone,
 * and credential-protected storage is locked until then. Nothing here is
 * private enough to need the stronger lock: times, labels, durations.
 *
 * Writes are small (a few kilobytes even with a full history) and go through
 * [SnapshotFile], atomic, on the caller's thread. Every caller is either a
 * click or a broadcast, never a frame.
 */
class ClockStore internal constructor(private val file: File?) {

    private val snapshot = file?.let(::SnapshotFile)
    private val _state = MutableStateFlow(load())

    val state: StateFlow<ClockData> = _state

    val data: ClockData get() = _state.value

    /**
     * Applies [change] and saves. Returns the new value. Synchronized so two
     * receivers firing at once cannot both read the old value and drop one
     * another's change.
     */
    @Synchronized
    fun update(change: (ClockData) -> ClockData): ClockData {
        val before = _state.value
        val after = change(before)
        if (after == before) return before
        _state.value = after
        val ticket = snapshot?.ticket() ?: return after
        snapshot.write(ticket) { json.encodeToString(ClockData.serializer(), after) }
        return after
    }

    /** Replaces everything, for a backup being restored. */
    fun replace(data: ClockData) {
        update { data }
    }

    private fun load(): ClockData {
        val f = file ?: return ClockData()
        if (!f.exists()) return ClockData()
        return runCatching { json.decodeFromString(ClockData.serializer(), f.readText()) }
            .getOrElse { ClockData() }
    }

    companion object {
        const val FILE_PATH = "clock/clock.json"

        @Volatile
        private var instance: ClockStore? = null

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        /** The process's store. Cheap after the first call. */
        fun get(context: Context): ClockStore =
            instance ?: synchronized(this) {
                instance ?: ClockStore(fileFor(context)).also { instance = it }
            }

        /**
         * Whether the store has anything worth waking up for, read without
         * building it: the re-arm at process start must cost nothing for the
         * many users who never set an alarm.
         */
        fun exists(context: Context): Boolean = instance != null || fileFor(context).exists()

        internal fun fileFor(context: Context): File =
            File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, FILE_PATH)

        /** For tests: a store with no file behind it. */
        fun inMemory(): ClockStore = ClockStore(null)
    }
}
