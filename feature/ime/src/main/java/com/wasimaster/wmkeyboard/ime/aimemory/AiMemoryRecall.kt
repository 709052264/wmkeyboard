package com.wasimaster.wmkeyboard.ime.aimemory

import android.content.Context
import com.wasimaster.wmkeyboard.core.aimemory.AiMemory
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryStore
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryText
import com.wasimaster.wmkeyboard.core.semantic.EmbeddingInput
import com.wasimaster.wmkeyboard.core.semantic.Embedder
import com.wasimaster.wmkeyboard.core.semantic.Embeddings
import com.wasimaster.wmkeyboard.core.semantic.MeaningRank
import com.wasimaster.wmkeyboard.core.semantic.MeaningSync
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.AiMemoryAccess
import com.wasimaster.wmkeyboard.ime.semantic.MeaningSearch
import com.wasimaster.wmkeyboard.ime.semantic.MeaningSpace
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who gets the AI memory, and which of it: the one place the keyboard's AI
 * actions, its chat and the app's chat ask before a run.
 *
 * A run is handed memory only when the memory is on, the field is not
 * incognito and the chat is not a private one, and the model is on the phone
 * or the user has also allowed cloud models. Only a chat may change it; see
 * [access].
 *
 * Few memories go to a run whole. Past [AiMemoryText.ALL_COUNT] the run gets
 * the ones closest to what it is about: by meaning when search by meaning is
 * on and its model is on the phone, by shared words otherwise, then the most
 * recently changed. The meaning index for memories sits with the others in
 * `noBackupFilesDir/semantic/index`, and is brought up to date here, a few
 * dozen facts at most per run, since a fact changes one at a time.
 */
object AiMemoryRecall {

    /** The memories one run is handed, and how many there are in all. */
    data class Recall(val picked: List<AiMemory>, val total: Int) {
        val isEmpty: Boolean get() = picked.isEmpty()
    }

    /**
     * Whether a run may use the memory at all. [onDevice] is the model's
     * place, [incognito] an incognito field or a private chat.
     */
    fun allowed(settings: KeyboardSettings, onDevice: Boolean, incognito: Boolean): Boolean {
        val memory = settings.ai.memory
        return memory.enabled && !incognito && (onDevice || memory.cloud)
    }

    /** The shared store, when it can be read now; null before the first unlock or with a damaged file. */
    fun store(context: Context): AiMemoryStore? = AiMemoryStore.shared(context)?.takeIf { it.readable }

    /**
     * What a chat lends its tool runner to save and delete with, or null
     * when it may not ([allowed]). Saves are filed under [conversationId].
     */
    fun access(
        context: Context,
        settings: KeyboardSettings,
        onDevice: Boolean,
        incognito: Boolean,
        conversationId: Long,
    ): AiMemoryAccess? {
        if (!allowed(settings, onDevice, incognito)) return null
        val store = store(context) ?: return null
        return AiMemoryAccess(store, AiMemoryStore.SOURCE_CHAT + conversationId)
    }

    /**
     * The memories to hand a run about [query]. Blocking: it may embed the
     * query and any fact not yet indexed, at most [MEANING_TIMEOUT_MS] of it,
     * then falls back on words. Empty when the run may not have memory.
     */
    fun recall(
        context: Context,
        settings: KeyboardSettings,
        onDevice: Boolean,
        incognito: Boolean,
        query: String,
    ): Recall {
        if (!allowed(settings, onDevice, incognito)) return Recall(emptyList(), 0)
        val memories = store(context)?.items().orEmpty()
        if (memories.isEmpty()) return Recall(emptyList(), 0)
        if (AiMemoryText.fitsWhole(memories)) return Recall(AiMemoryText.pick(memories, emptyList()), memories.size)
        val byMeaning = if (meaningReady(context, settings)) byMeaning(context, memories, query) else emptyList()
        val byWords = AiMemoryText.rankByWords(memories, query)
        val matches = (byMeaning + byWords).distinctBy { it.id }
        return Recall(AiMemoryText.pick(memories, matches), memories.size)
    }

    /**
     * [recall] as a system prompt's tail: the facts, and for a chat that may
     * save ([withTools]) the rules for saving. Empty when there is nothing to
     * say.
     */
    fun promptTail(recall: Recall, forChat: Boolean, withTools: Boolean): String {
        val block = AiMemoryText.promptBlock(recall.picked, recall.total, forChat)
        return if (withTools) block + AiMemoryText.CHAT_RULES else block
    }

    /**
     * Deletes the meaning index of the memories: after the user deleted them
     * all, so no vector of a deleted fact stays on the phone.
     */
    fun dropIndex(context: Context) {
        synchronized(indexLock) { runCatching { MeaningSearch.indexFile(context, MeaningSpace.MEMORIES).delete() } }
    }

    private fun meaningReady(context: Context, settings: KeyboardSettings): Boolean =
        settings.semanticSearch.enabled && SemanticModels.SUPPORTED && Embeddings.available(context)

    private val indexLock = Any()

    /**
     * [memories] closest in meaning to [query], best first; empty on any
     * failure. Each of the two waits, the query and the catching-up of the
     * index, gives up after [MEANING_TIMEOUT_MS]; what the index took in by
     * then is kept for the next run.
     */
    private fun byMeaning(context: Context, memories: List<AiMemory>, query: String): List<AiMemory> {
        val q = query.trim().take(MAX_QUERY_CHARS)
        if (q.length < MIN_QUERY) return emptyList()
        val byId = memories.associateBy { it.id.toString() }
        return runCatching {
            val vector = runBlocking { withTimeoutOrNull(MEANING_TIMEOUT_MS) { Embeddings.query(context, q) } }
                ?: return emptyList()
            val hits = synchronized(indexLock) {
                // Two runs bringing one file up to date at once would each
                // write half of it, hence the lock around all of it.
                val file = MeaningSearch.indexFile(context, MeaningSpace.MEMORIES)
                val index = VectorIndex.read(file)
                val items = memories.map { MeaningSync.Item(it.id.toString(), MeaningSearch.stampOf(it.text), it.text) }
                val embedder = Embedder { inputs -> Embeddings.texts(context, inputs, EmbeddingInput.Role.DOCUMENT) }
                runBlocking {
                    withTimeoutOrNull(MEANING_TIMEOUT_MS) {
                        MeaningSync.sync(index, items, embedder, batch = BATCH, budget = BUDGET)
                    }
                }
                runCatching {
                    file.parentFile?.mkdirs()
                    index.write(file)
                }
                index.search(vector, TUNING) { it in byId }
            }
            hits.mapNotNull { byId[it.key] }
        }.getOrNull().orEmpty()
    }

    /** Facts are short and alike, so the bar is a little lower than a clipboard search's. */
    private val TUNING = MeaningRank.TEXT.copy(floor = 0.55f, smallSetBar = 0.6f, limit = AiMemoryText.MATCHES)

    private const val MEANING_TIMEOUT_MS = 2_500L
    private const val BATCH = 16
    private const val BUDGET = 48
    private const val MIN_QUERY = 2

    /** The part of a long field or message the query is made of. */
    private const val MAX_QUERY_CHARS = 1_000
}
