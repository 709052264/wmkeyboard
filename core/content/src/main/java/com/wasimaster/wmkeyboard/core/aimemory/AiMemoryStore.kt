package com.wasimaster.wmkeyboard.core.aimemory

import android.content.Context
import android.os.UserManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing the AI remembers about the user: a short fact in plain words.
 *
 * [source] says where it came from: [AiMemoryStore.SOURCE_MANUAL] for one the
 * user typed, or `chat:<conversation id>` for one a model saved in a chat.
 * [category] is optional and free text ("work", "food"); the list screen
 * shows it and the prompt carries it in brackets.
 */
@Serializable
data class AiMemory(
    val id: Long,
    val text: String,
    /** Wall clock, milliseconds. */
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val source: String = "",
    val category: String = "",
)

/** What one attempt to keep a fact did. */
sealed interface AiMemoryOutcome {
    /** Kept as a new memory. */
    data class Added(val memory: AiMemory) : AiMemoryOutcome

    /** Replaced [before], keeping its id: the same fact, said again or said better. */
    data class Updated(val memory: AiMemory, val before: AiMemory) : AiMemoryOutcome

    /** Already kept as [memory]; nothing changed. */
    data class Duplicate(val memory: AiMemory) : AiMemoryOutcome

    /** Not kept, for [reason]. */
    data class Refused(val reason: Reason, val secret: AiMemoryText.SecretKind? = null) : AiMemoryOutcome

    enum class Reason { EMPTY, TOO_LONG, SECRET, FULL, NOT_FOUND, UNREADABLE }
}

/**
 * The AI's memory of the user, on this phone only: a JSON file in
 * credential-encrypted app storage, so nothing in it can be read before the
 * phone is unlocked after a restart.
 *
 * Both chats, the keyboard's AI actions and the settings screen share one
 * instance per process through [shared], the way the chats share theirs. It
 * leaves the phone in one way only: a backup that the user has told to carry
 * AI memory. It never goes into sync.
 *
 * A file that is there but cannot be read (the phone is still locked, or the
 * file is damaged) makes the store [readable] false, and then [save] writes
 * nothing: an empty list saved over a list that could not be read would
 * delete every memory.
 *
 * A null [storageFile] holds everything in memory, which is what tests pass.
 */
class AiMemoryStore(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(val memories: List<AiMemory> = emptyList())

    private val memories = ArrayList<AiMemory>()
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1L

    private val _revision = MutableStateFlow(0L)

    /** Moves on every change, so a screen showing the list knows to read it again. */
    val revision: StateFlow<Long> = _revision

    /** False when the file is there but could not be read; see the class note. */
    @Volatile
    var readable: Boolean = true
        private set

    init {
        reload()
    }

    /** Oldest first, the order they were saved in. */
    @Synchronized
    fun items(): List<AiMemory> = memories.sortedBy { it.createdAt }

    @Synchronized
    fun size(): Int = memories.size

    @Synchronized
    fun get(id: Long): AiMemory? = memories.firstOrNull { it.id == id }

    /**
     * Keeps [text] as a fact about the user, or says why not.
     *
     * With [replaceId] the fact replaces that memory, which is how a model
     * corrects one it was shown. Without it, a fact [AiMemoryText.compare]
     * finds already kept is not kept twice: one that says no more is a
     * [AiMemoryOutcome.Duplicate], one that says more replaces the old
     * wording under the old id. Secrets are refused here whatever the caller
     * was told; see [AiMemoryText.secretReason].
     */
    @Synchronized
    fun remember(
        text: String,
        now: Long,
        source: String = "",
        category: String = "",
        replaceId: Long? = null,
    ): AiMemoryOutcome {
        if (!readable) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.UNREADABLE)
        val fact = text.trim().replace(LINE_BREAKS, " ")
        val tag = category.trim().take(MAX_CATEGORY)
        if (fact.isEmpty()) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.EMPTY)
        if (fact.length > AiMemoryText.MAX_CHARS) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.TOO_LONG)
        AiMemoryText.secretReason(fact)?.let {
            return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.SECRET, it)
        }
        if (replaceId != null) {
            val at = memories.indexOfFirst { it.id == replaceId }
            if (at < 0) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.NOT_FOUND)
            return replace(at, fact, tag, now)
        }
        var newer = -1
        for ((i, kept) in memories.withIndex()) {
            when (AiMemoryText.compare(kept.text, fact)) {
                AiMemoryText.Match.SAME -> return AiMemoryOutcome.Duplicate(kept)
                AiMemoryText.Match.NEWER -> if (newer < 0) newer = i
                AiMemoryText.Match.NONE -> Unit
            }
        }
        if (newer >= 0) return replace(newer, fact, tag, now)
        if (memories.size >= MAX_ITEMS) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.FULL)
        val memory = AiMemory(id = nextId++, text = fact, createdAt = now, updatedAt = now, source = source, category = tag)
        memories += memory
        changed()
        return AiMemoryOutcome.Added(memory)
    }

    private fun replace(at: Int, fact: String, tag: String, now: Long): AiMemoryOutcome {
        val before = memories[at]
        if (before.text == fact && (tag.isEmpty() || tag == before.category)) return AiMemoryOutcome.Duplicate(before)
        val memory = before.copy(text = fact, category = tag.ifEmpty { before.category }, updatedAt = now)
        memories[at] = memory
        changed()
        return AiMemoryOutcome.Updated(memory, before)
    }

    /**
     * The user's own edit of memory [id]. Unlike [remember] it never merges
     * into another memory, and a blank [category] clears the category.
     */
    @Synchronized
    fun edit(id: Long, text: String, category: String, now: Long): AiMemoryOutcome {
        if (!readable) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.UNREADABLE)
        val at = memories.indexOfFirst { it.id == id }
        if (at < 0) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.NOT_FOUND)
        val fact = text.trim().replace(LINE_BREAKS, " ")
        if (fact.isEmpty()) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.EMPTY)
        if (fact.length > AiMemoryText.MAX_CHARS) return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.TOO_LONG)
        AiMemoryText.secretReason(fact)?.let {
            return AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.SECRET, it)
        }
        val before = memories[at]
        val memory = before.copy(text = fact, category = category.trim().take(MAX_CATEGORY), updatedAt = now)
        if (memory == before) return AiMemoryOutcome.Duplicate(before)
        memories[at] = memory
        changed()
        return AiMemoryOutcome.Updated(memory, before)
    }

    /** Removes memory [id] and returns it, or null when there was none. */
    @Synchronized
    fun forget(id: Long): AiMemory? {
        val at = memories.indexOfFirst { it.id == id }
        if (at < 0) return null
        val gone = memories.removeAt(at)
        changed()
        return gone
    }

    /**
     * The memories [query] names: those whose text holds it once both are
     * normalised, or failing any, those [AiMemoryText.compare] takes for the
     * same fact. How `forget` finds what to drop when the model gives words
     * rather than a number.
     */
    @Synchronized
    fun matching(query: String): List<AiMemory> {
        val wanted = AiMemoryText.normalize(query)
        if (wanted.isEmpty()) return emptyList()
        val holding = memories.filter { AiMemoryText.normalize(it.text).contains(wanted) }
        if (holding.isNotEmpty()) return holding.sortedBy { it.createdAt }
        return memories.filter { AiMemoryText.compare(it.text, query) != AiMemoryText.Match.NONE }.sortedBy { it.createdAt }
    }

    /**
     * Puts [memory] back exactly as it was, id and times included, replacing
     * whatever holds its id now. The undo of a forget or of an update.
     */
    @Synchronized
    fun restore(memory: AiMemory) {
        val at = memories.indexOfFirst { it.id == memory.id }
        if (at >= 0) memories[at] = memory else memories += memory
        nextId = maxOf(nextId, memory.id + 1)
        changed()
    }

    @Synchronized
    fun clear() {
        if (memories.isEmpty()) return
        memories.clear()
        changed()
    }

    /** Writes the list. Does nothing while the store is not [readable]. */
    @Synchronized
    fun save() {
        val file = storageFile ?: return
        if (!readable) return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(memories.toList())))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    /** Reads the file again, dropping what is held in memory. */
    @Synchronized
    fun reload() {
        memories.clear()
        readable = true
        val file = storageFile
        if (file != null && file.exists()) {
            runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }
                .onSuccess { memories.addAll(it.memories.distinctBy { m -> m.id }) }
                .onFailure { readable = false }
        }
        nextId = (memories.maxOfOrNull { it.id } ?: 0) + 1
        changed()
    }

    private fun changed() {
        _revision.value++
    }

    companion object {
        /** Memories kept at most. Past this a model's save is refused, and says so. */
        const val MAX_ITEMS = 500

        /** Longest category. */
        const val MAX_CATEGORY = 32

        /** Path under the app's private files directory. */
        const val FILE_PATH = "ai/memory.json"

        /** [AiMemory.source] of a memory the user added on the settings screen. */
        const val SOURCE_MANUAL = "manual"

        /** [AiMemory.source] prefix of one a model saved in a chat. */
        const val SOURCE_CHAT = "chat:"

        private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")

        @Volatile
        private var instance: AiMemoryStore? = null

        /**
         * The process's one store, over [FILE_PATH] in credential-encrypted
         * storage; null until the user has unlocked the phone since it
         * started, when that storage cannot be read. The keyboard runs before
         * then, and its AI simply has no memory until it can.
         */
        fun shared(context: Context): AiMemoryStore? {
            instance?.let { return it }
            val users = context.getSystemService(UserManager::class.java)
            if (users != null && !users.isUserUnlocked) return null
            return synchronized(this) {
                instance ?: AiMemoryStore(File(context.applicationContext.filesDir, FILE_PATH)).also { instance = it }
            }
        }

        /**
         * Makes the shared store read its file again, if it has been opened:
         * after a restore wrote the file.
         */
        fun reloadShared() {
            instance?.reload()
        }
    }
}
