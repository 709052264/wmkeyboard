package com.wasimaster.wmkeyboard.core.emoji

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * The user's kaomoji library, persisted as JSON in app-private storage and
 * shared by the keyboard and the settings app the way the notes are: each
 * re-reads the file when it opens its screen ([reloadIfChanged]) and saves
 * after each change, with [SnapshotFile] keeping the writes atomic and in
 * order.
 *
 * With no file yet the library is the compiled-in catalog
 * ([KaomojiLibrary.builtIn]). Nothing is written until the user changes
 * something or taps a face, so an untouched install keeps following the
 * catalog a newer build ships.
 */
class KaomojiStore(private val storageFile: File?) {

    @Serializable
    internal data class Snapshot(
        val version: Int = 1,
        val seedRevision: Int = SEED_REVISION,
        val items: List<Kaomoji> = emptyList(),
        val tags: List<String> = emptyList(),
        val hiddenTags: List<String> = emptyList(),
    )

    private val items = ArrayList<Kaomoji>()
    private val tags = ArrayList<String>()
    private val hiddenTags = ArrayList<String>()
    private var nextId = 1L
    private val snapshotFile = storageFile?.let(::SnapshotFile)
    private var seenStamp = 0L

    /** True while the library is still the built-in seed and has never been written. */
    @Volatile
    var isSeed: Boolean = true
        private set

    init {
        reload()
    }

    // ---- reading ---------------------------------------------------------

    @Synchronized
    fun library(): KaomojiLibrary = KaomojiLibrary(items.toList(), tags.toList(), hiddenTags.toSet())

    @Synchronized
    fun item(id: Long): Kaomoji? = items.firstOrNull { it.id == id }

    @Synchronized
    fun size(): Int = items.size

    // ---- writing ---------------------------------------------------------

    /**
     * Adds a face, or returns the entry that already holds exactly [text] with
     * [tags] added to its own. Null for blank text or a full library. [kind]
     * null guesses from the text ([KaomojiKind.of]).
     */
    @Synchronized
    fun add(
        text: String,
        tags: List<String> = emptyList(),
        kind: KaomojiKind? = null,
        now: Long = System.currentTimeMillis(),
    ): Kaomoji? {
        val clean = cleanText(text) ?: return null
        val index = items.indexOfFirst { it.text == clean }
        if (index >= 0) {
            val merged = items[index].copy(tags = normalizeTags(items[index].tags + tags))
            items[index] = merged
            isSeed = false
            return merged
        }
        if (items.size >= MAX_ITEMS) return null
        val added = Kaomoji(
            id = freshId(now),
            text = clean,
            tags = normalizeTags(tags),
            kind = kind ?: KaomojiKind.of(clean),
        )
        items.add(added)
        isSeed = false
        return added
    }

    /**
     * Rewrites the entry [id] with new text, tags and kind; usage and the star
     * are kept. Changing the text to one another entry holds folds this entry
     * into that one. Null when [id] is gone or the text is blank.
     */
    @Synchronized
    fun update(id: Long, text: String, tags: List<String>, kind: KaomojiKind): Kaomoji? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val clean = cleanText(text) ?: return null
        val other = items.indexOfFirst { it.text == clean && it.id != id }
        val next = items[index].copy(text = clean, tags = normalizeTags(tags), kind = kind)
        isSeed = false
        if (other >= 0) {
            val target = items[other]
            items[other] = target.copy(
                tags = normalizeTags(target.tags + next.tags),
                favourite = target.favourite || next.favourite,
                uses = target.uses + next.uses,
                lastUsedAt = maxOf(target.lastUsedAt, next.lastUsedAt),
            )
            items.removeAt(index)
            return items.first { it.id == target.id }
        }
        items[index] = next
        return next
    }

    @Synchronized
    fun delete(ids: Collection<Long>): Int {
        val set = ids.toHashSet()
        val before = items.size
        items.removeAll { it.id in set }
        if (items.size != before) isSeed = false
        return before - items.size
    }

    fun delete(id: Long): Boolean = delete(listOf(id)) > 0

    @Synchronized
    fun setFavourite(id: Long, favourite: Boolean): Kaomoji? = change(id) { it.copy(favourite = favourite) }

    /**
     * A face was inserted: count it for the Recent tab and the suggestion
     * order. By text, because that is what a tap carries; a face not in the
     * library (an emoji-search hit since deleted) is ignored.
     */
    @Synchronized
    fun recordUse(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return false
        items[index] = items[index].let { it.copy(uses = it.uses + 1, lastUsedAt = now) }
        isSeed = false
        return true
    }

    /** Forgets every use, emptying the Recent tab; favourites and order stay. */
    @Synchronized
    fun clearRecents() {
        for (i in items.indices) items[i] = items[i].copy(uses = 0, lastUsedAt = 0L)
        isSeed = false
    }

    /**
     * Puts the entries of one filtered view into [order] (their ids, as the
     * user dragged them), leaving everything outside the view where it was:
     * the view's entries keep the slots of the global list they held and
     * only trade places among themselves. Ids not in the library are ignored.
     */
    @Synchronized
    fun reorder(order: List<Long>) {
        val wanted = order.distinct().filter { id -> items.any { it.id == id } }
        if (wanted.size < 2) return
        val set = wanted.toHashSet()
        val slots = items.indices.filter { items[it].id in set }
        val byId = items.filter { it.id in set }.associateBy { it.id }
        slots.forEachIndexed { n, slot -> items[slot] = byId.getValue(wanted[n]) }
        isSeed = false
    }

    // ---- tags ------------------------------------------------------------

    /** Adds an empty tag to the end of the strip; the existing one when the name is taken. */
    @Synchronized
    fun addTag(name: String): String? {
        val clean = cleanTag(name) ?: return null
        tags.firstOrNull { it.equals(clean, ignoreCase = true) }?.let { return it }
        if (tags.size >= MAX_TAGS) return null
        tags.add(clean)
        isSeed = false
        return clean
    }

    /**
     * Renames a tag on every entry. A name another tag already has merges the
     * two, keeping the target's place in the strip.
     */
    @Synchronized
    fun renameTag(from: String, to: String): Boolean {
        val source = tags.firstOrNull { it.equals(from, ignoreCase = true) } ?: return false
        val clean = cleanTag(to) ?: return false
        val target = tags.firstOrNull { it.equals(clean, ignoreCase = true) && !it.equals(source, ignoreCase = true) }
        val finalName = target ?: clean
        if (target != null) {
            tags.removeAll { it.equals(source, ignoreCase = true) }
        } else {
            tags[tags.indexOf(source)] = finalName
        }
        val wasHidden = hiddenTags.removeAll { it.equals(source, ignoreCase = true) }
        if (wasHidden && target == null) hiddenTags.add(finalName)
        for (i in items.indices) {
            val item = items[i]
            if (!item.hasTag(source)) continue
            items[i] = item.copy(tags = dedupe(item.tags.map { if (it.equals(source, ignoreCase = true)) finalName else it }))
        }
        isSeed = false
        return true
    }

    /**
     * Drops a tag from the strip and from every entry. With [deleteItems] the
     * entries that carried it go too, which is how a whole imported set is
     * removed in one step.
     */
    @Synchronized
    fun removeTag(name: String, deleteItems: Boolean = false): Int {
        tags.removeAll { it.equals(name, ignoreCase = true) }
        hiddenTags.removeAll { it.equals(name, ignoreCase = true) }
        var touched = 0
        val it = items.listIterator()
        while (it.hasNext()) {
            val item = it.next()
            if (!item.hasTag(name)) continue
            touched++
            if (deleteItems) it.remove() else it.set(item.copy(tags = item.tags.filterNot { t -> t.equals(name, ignoreCase = true) }))
        }
        isSeed = false
        return touched
    }

    /** The strip's order: names in [order] first as given, any others after in their old order. */
    @Synchronized
    fun reorderTags(order: List<String>) {
        val placed = order.mapNotNull { name -> tags.firstOrNull { it.equals(name, ignoreCase = true) } }.distinct()
        val rest = tags.filter { it !in placed }
        tags.clear()
        tags.addAll(placed + rest)
        isSeed = false
    }

    @Synchronized
    fun setTagHidden(name: String, hidden: Boolean) {
        hiddenTags.removeAll { it.equals(name, ignoreCase = true) }
        if (hidden) tags.firstOrNull { it.equals(name, ignoreCase = true) }?.let(hiddenTags::add)
        isSeed = false
    }

    /** Back to the compiled-in catalog: the file is deleted, every edit and use forgotten. */
    @Synchronized
    fun resetToDefaults() {
        snapshotFile?.delete()
        loadSeed()
        seenStamp = 0L
    }

    /**
     * Brings imported faces in. Each is matched by its exact text: a new one is
     * appended, a known one gains the incoming tags. [tagOrder] places tags the
     * library does not have yet at the end of the strip in the source's order.
     * [replace] empties the library first. Returns how many were new.
     */
    @Synchronized
    fun merge(incoming: List<KaomojiImport>, tagOrder: List<String> = emptyList(), replace: Boolean = false, now: Long = System.currentTimeMillis()): Int {
        if (replace) {
            items.clear()
            tags.clear()
            hiddenTags.clear()
        }
        for (tag in tagOrder) addTag(tag)
        var added = 0
        for (entry in incoming) {
            val before = items.size
            val result = add(entry.text, entry.tags, entry.kind, now) ?: continue
            if (items.size > before) {
                added++
                if (entry.favourite) change(result.id) { it.copy(favourite = true) }
            }
        }
        isSeed = false
        return added
    }

    // ---- persistence -----------------------------------------------------

    /**
     * Writes the file, unless the library is still the untouched seed. Same
     * shape as the notes' save: snapshot under the lock, encode and write
     * outside it, so any thread may call it.
     */
    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (isSeed) return
            file.ticket() to Snapshot(1, SEED_REVISION, items.toList(), tags.toList(), hiddenTags.toList())
        }
        if (file.write(ticket) { json.encodeToString(Snapshot.serializer(), snapshot) }) {
            synchronized(this) { seenStamp = file.file.lastModified() }
        }
    }

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        val file = storageFile
        val snapshot = if (file != null && file.exists()) {
            runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }.getOrNull()
        } else {
            null
        }
        if (snapshot == null) {
            loadSeed()
        } else {
            items.clear()
            tags.clear()
            hiddenTags.clear()
            tags.addAll(dedupe(snapshot.tags.mapNotNull(::cleanTag)))
            hiddenTags.addAll(snapshot.hiddenTags)
            // Tags an entry names but the list lost are put back on the strip.
            snapshot.items.forEach { item -> item.tags.forEach { addTagQuietly(it) } }
            items.addAll(snapshot.items.distinctBy { it.id }.filter { it.text.isNotEmpty() })
            isSeed = false
        }
        seenStamp = file?.takeIf { it.exists() }?.lastModified() ?: 0L
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }

    /** True when another process wrote the file since this one last read or wrote it; then it reloads. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val stamp = storageFile?.takeIf { it.exists() }?.lastModified() ?: 0L
        if (stamp == seenStamp) return false
        reload()
        return true
    }

    // ---- helpers ---------------------------------------------------------

    private fun loadSeed() {
        val seed = KaomojiLibrary.builtIn()
        items.clear()
        tags.clear()
        hiddenTags.clear()
        items.addAll(seed.items)
        tags.addAll(seed.tags)
        isSeed = true
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private fun change(id: Long, transform: (Kaomoji) -> Kaomoji): Kaomoji? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        items[index] = transform(items[index])
        isSeed = false
        return items[index]
    }

    /**
     * Time-based, as the snippets' and notes' ids are, so two phones' libraries
     * merged by a sync do not collide on small counters.
     */
    private fun freshId(now: Long): Long {
        val id = maxOf(nextId, now * ID_SPREAD + kotlin.random.Random.nextLong(ID_SPREAD))
        nextId = id + 1
        return id
    }

    /** Tags cleaned, each once ignoring case, capped, in the strip's casing; new names join the strip. */
    private fun normalizeTags(raw: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (name in raw) {
            val clean = cleanTag(name) ?: continue
            if (!seen.add(clean.lowercase(Locale.ROOT))) continue
            out.add(addTagQuietly(clean) ?: continue)
            if (out.size >= MAX_TAGS_PER_ITEM) break
        }
        return out
    }

    private fun addTagQuietly(name: String): String? {
        val clean = cleanTag(name) ?: return null
        tags.firstOrNull { it.equals(clean, ignoreCase = true) }?.let { return it }
        if (tags.size >= MAX_TAGS) return null
        tags.add(clean)
        return clean
    }

    private fun dedupe(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.filter { seen.add(it.lowercase(Locale.ROOT)) }
    }

    companion object {
        const val FILE_PATH = "kaomoji/kaomoji.json"

        /** Bumped when the built-in catalog changes in a way a written library should hear about. */
        const val SEED_REVISION = 1

        const val MAX_ITEMS = 5000
        const val MAX_TAGS = 300
        const val MAX_TAGS_PER_ITEM = 16
        const val MAX_TEXT_LENGTH = 200
        const val MAX_TAG_LENGTH = 40

        private const val ID_SPREAD = 1000L

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = false
        }

        private val LINE_BREAKS = Regex("""[\r\n]+""")

        /**
         * A face as it is stored: one line, ends trimmed, capped. Inner spaces
         * are kept, since `( ˘ω˘ )` without them is a different face.
         */
        fun cleanText(text: String): String? =
            text.replace(LINE_BREAKS, " ").trim().take(MAX_TEXT_LENGTH).takeIf { it.isNotEmpty() }

        /** A tag name as stored, or null when nothing is left of it. */
        fun cleanTag(name: String): String? =
            name.filterNot { it.isISOControl() }.trim().trimStart('#').replace(",", " ").trim()
                .take(MAX_TAG_LENGTH).trim().takeIf { it.isNotEmpty() }
    }
}

/** One face arriving from a file: the import formats all come down to this. */
data class KaomojiImport(
    val text: String,
    val tags: List<String> = emptyList(),
    val kind: KaomojiKind? = null,
    val favourite: Boolean = false,
)
