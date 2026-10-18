package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One pending reminder. [at] is milliseconds since the epoch. */
@Serializable
data class PluginReminder(
    val id: String = "",
    val at: Long = 0L,
    val text: String = "",
)

/**
 * `wm.reminders`: the times one plugin asked to be shown a line of text.
 *
 * Only the list lives here. Showing a reminder is the app's job, not the
 * plugin's: when the time comes [PluginReminderAlarms] reads this file, posts
 * the notification itself and takes the reminder off the list. No plugin code
 * runs for that, so a reminder cannot become a way to run in the background.
 *
 * Unlike [PluginStorage], nothing is cached. Two readers share the file in one
 * process, the running plugin and the alarm that fires while it is open, and a
 * copy held by either would undo the other's last write. The file is small
 * (at most [MAX_REMINDERS] lines of [MAX_TEXT_LENGTH] characters), so reading
 * it on each call costs nothing worth saving. A null [file] keeps the list in
 * memory, which is what the plugin editor's preview uses: a draft can call the
 * API, and nothing it sets ever reaches the shade.
 *
 * [onChange] runs after every write that changed the list, so the host can
 * move its one wake-up to the new soonest time.
 */
class PluginReminders(
    private val file: File?,
    private val onChange: () -> Unit = {},
) {

    private val memory = ArrayList<PluginReminder>()

    companion object {
        const val MAX_REMINDERS = 64
        const val MAX_ID_LENGTH = 64
        const val MAX_TEXT_LENGTH = 200

        /** Ten years. Far enough for any due date, near enough that the arithmetic stays exact. */
        const val MAX_AHEAD_MILLIS = 10L * 366 * 24 * 60 * 60 * 1000

        /** Every instance over any file takes this lock, so a read and a write in two places never cross. */
        private val lock = Any()

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    /**
     * Sets or replaces the reminder [id], to show [text] at [atMillis]. Returns
     * why it was refused, in words for the script, or null when it went through.
     */
    @Suppress("ReturnCount")
    fun set(id: String, atMillis: Long, text: String, now: Long = System.currentTimeMillis()): String? {
        if (id.isEmpty()) return "a reminder id can't be empty"
        if (id.length > MAX_ID_LENGTH) return "reminder ids can't be longer than $MAX_ID_LENGTH characters"
        // Cleaned the way a plugin's name is, because it is drawn in the shade
        // under that name: no control characters, no direction overrides.
        val shown = PluginManifestCodec.displayText(text, MAX_TEXT_LENGTH)
        if (shown.isEmpty()) return "a reminder needs some text to show"
        if (atMillis <= now) return "that time has already passed"
        if (atMillis - now > MAX_AHEAD_MILLIS) return "a reminder can't be more than ten years ahead"
        synchronized(lock) {
            val list = read()
            val existing = list.indexOfFirst { it.id == id }
            if (existing < 0 && list.size >= MAX_REMINDERS) {
                return "this plugin already has the maximum of $MAX_REMINDERS reminders"
            }
            val reminder = PluginReminder(id, atMillis, shown)
            if (existing >= 0) list[existing] = reminder else list.add(reminder)
            write(list)
        }
        onChange()
        return null
    }

    /** Takes the reminder [id] off the list. True when there was one. */
    fun cancel(id: String): Boolean {
        val removed = synchronized(lock) {
            val list = read()
            list.removeAll { it.id == id }.also { if (it) write(list) }
        }
        if (removed) onChange()
        return removed
    }

    /** Every pending reminder, soonest first. */
    fun list(): List<PluginReminder> = synchronized(lock) { read() }.sortedBy { it.at }

    /** The soonest pending time, or null when there is none. */
    fun next(): Long? = list().firstOrNull()?.at

    /** Cancels every reminder. */
    fun clear() {
        val had = synchronized(lock) {
            val list = read()
            list.isNotEmpty().also { if (it) write(ArrayList()) }
        }
        if (had) onChange()
    }

    /**
     * Takes every reminder due at [now] off the list and returns them, soonest
     * first. The caller shows them; a reminder taken here is never taken again.
     * Does not call [onChange]: the caller is the alarm, which re-arms anyway.
     */
    fun takeDue(now: Long): List<PluginReminder> = synchronized(lock) {
        val list = read()
        val due = list.filter { it.at <= now }
        if (due.isNotEmpty()) {
            list.removeAll(due.toSet())
            write(list)
        }
        due.sortedBy { it.at }
    }

    private fun read(): ArrayList<PluginReminder> {
        val source = file ?: return ArrayList(memory)
        if (!source.isFile) return ArrayList()
        val decoded = runCatching { json.decodeFromString<List<PluginReminder>>(source.readText()) }
            .getOrDefault(emptyList())
        // A file edited by hand, or written by a later version, is read as far
        // as it makes sense and no further.
        return decoded
            .filter { it.id.isNotEmpty() && it.id.length <= MAX_ID_LENGTH && it.text.length <= MAX_TEXT_LENGTH }
            .distinctBy { it.id }
            .take(MAX_REMINDERS)
            .toCollection(ArrayList())
    }

    private fun write(list: List<PluginReminder>) {
        val target = file
        if (target == null) {
            memory.clear()
            memory.addAll(list)
            return
        }
        runCatching {
            if (list.isEmpty()) {
                target.delete()
                return@runCatching
            }
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeText(json.encodeToString(list))
            if (!part.renameTo(target)) {
                target.delete()
                part.renameTo(target)
            }
        }
    }
}
