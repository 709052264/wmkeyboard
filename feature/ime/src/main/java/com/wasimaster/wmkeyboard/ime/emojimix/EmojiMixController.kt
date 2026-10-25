package com.wasimaster.wmkeyboard.ime.emojimix

import android.content.Context
import com.wasimaster.wmkeyboard.core.emoji.mix.EmojiMixer
import com.wasimaster.wmkeyboard.core.emoji.mix.MixSeq
import com.wasimaster.wmkeyboard.core.media.MediaMime
import com.wasimaster.wmkeyboard.core.settings.EmojiMixOrder
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSettings
import com.wasimaster.wmkeyboard.core.settings.EmojiMixSource
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.StickerOffer
import com.wasimaster.wmkeyboard.ime.StickerOfferItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Emoji mixes on the keyboard: the offers raised by emoji in the text or
 * picked in the emoji panel, the Mixes panel, and sending a mix. The sources
 * are the user's; this only reads what [EmojiMixStore] holds and fetches the
 * images their addresses name.
 */
class EmojiMixController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** What the controller needs of the keyboard. */
    interface Host {
        val state: KeyboardUiState
        fun publish(change: (KeyboardUiState) -> KeyboardUiState)
        fun haptic()

        /** Whether previews may be fetched on this connection, data saving considered. */
        fun previewsAllowed(): Boolean

        /** Sends [file] (a PNG) into the field, as a sticker where [mode] asks and the app takes one. */
        fun send(file: File, mode: MediaSendMode)

        /** The cell [id] spins while its image comes down, and stops. */
        fun startFetch(id: String)
        fun endFetch()
        fun toast(message: Int)
        fun setFavourite(pairKey: String, starred: Boolean)
        fun addToStickers(file: File, keywords: List<String>)
        fun saveToGallery(file: File)
        fun openPanel()
    }

    val store = EmojiMixStore(context)

    @Volatile
    private var mixer: EmojiMixer = EmojiMixer.EMPTY
    private var sources: List<EmojiMixSource> = emptyList()
    private var loadedKey: Any? = null
    private var verifyJob: Job? = null

    /** Whether a mix can be offered at all right now. */
    val usable: Boolean get() = mixer.usable

    /**
     * Picks up a change to the sources or their files. Blocking (an index is
     * read from disk), so the keyboard calls it off the main thread.
     */
    fun reload(settings: EmojiMixSettings) {
        val key = settings.sources to settings.indexVersion
        if (key == loadedKey) return
        loadedKey = key
        sources = settings.sources
        mixer = runCatching { store.load(settings.sources) }.getOrDefault(EmojiMixer.EMPTY)
        host.publish { it.copy(emojiMix = it.emojiMix.copy(usable = mixer.usable, canList = mixer.canList)) }
        refreshFavourites(settings)
    }

    // ---- offers ----

    /**
     * Mixes for the emoji that end [text]: the last two together first, when
     * there are two and they mix, then the last one's mixes with the others.
     * Null when the text does not end in an emoji with a mix.
     */
    fun offerFor(text: String, inEmojiPanel: Boolean): StickerOffer? {
        val m = mixer
        if (!m.usable) return null
        val tail = MixSeq.trailingEmoji(text, 2)
        val last = tail.lastOrNull() ?: return null
        if (!m.supports(last)) return null
        val pair = tail.takeIf { it.size == 2 }?.let { m.mix(it[0], it[1]) }
        val previewsAllowed = host.previewsAllowed()
        val partners = if (m.canList) ordered(m.partners(last), last) else emptyList()
        val all = (listOfNotNull(pair) + partners)
            .distinctBy { it.id }
            .filter { it.verified }
            .filter { previewsAllowed || it.url.startsWith("file:") }
            .take(OFFER_LIMIT)
        if (all.isEmpty()) return null
        // With a pair the trigger is both emoji, so sending takes both back out.
        val trigger = if (pair != null && pair.verified) tail.joinToString("") else last
        return StickerOffer(
            trigger = trigger,
            stickers = all.map { StickerOfferItem(it.asGifItem(), trigger.length) },
            inEmojiPanel = inEmojiPanel,
            mix = true,
        )
    }

    /** The emoji used lately first, when the settings ask for it; the source's order otherwise. */
    private fun ordered(mixes: List<EmojiMixer.Mix>, emoji: String): List<EmojiMixer.Mix> {
        val settings = host.state.settings.emoji.mix
        if (settings.order != EmojiMixOrder.YOURS_FIRST) return mixes
        val state = host.state
        val mine = (state.emojiRecents + state.emojiFrequents).map(MixSeq::canonicalOf).distinct()
        if (mine.isEmpty()) return mixes
        val rank = mine.withIndex().associate { it.value to it.index }
        val self = MixSeq.canonicalOf(emoji)
        return mixes.sortedBy { mix ->
            val other = MixSeq.canonicalOf(mix.left).takeIf { it != self } ?: MixSeq.canonicalOf(mix.right)
            rank[other] ?: Int.MAX_VALUE
        }
    }

    /** A mix offered on the strip or the panel's row was picked: fetch and send it. */
    fun sendOffered(item: GifItem) {
        val (left, right) = pairOf(item) ?: return
        val mix = mixer.mix(left, right) ?: return
        send(mix)
    }

    // ---- the panel ----

    /** The Mixes panel opened: start from [left], or from the emoji last used. */
    fun onOpen(left: String? = null) {
        val m = mixer
        val state = host.state
        val emoji = pickable(m, state)
        val start = left?.takeIf { m.supports(it) }
            ?: state.emojiMix.left.takeIf { it.isNotEmpty() && m.supports(it) }
            ?: state.emojiRecents.firstOrNull { m.supports(it) }
            ?: emoji.firstOrNull().orEmpty()
        host.publish {
            it.copy(
                emojiMix = it.emojiMix.copy(
                    usable = m.usable,
                    canList = m.canList,
                    emoji = emoji,
                    previewsHeld = !host.previewsAllowed(),
                ),
            )
        }
        setPair(start, if (left != null) "" else state.emojiMix.right, slot = if (start.isEmpty()) 0 else 1)
        refreshFavourites(state.settings.emoji.mix)
    }

    /** The emoji for the picker: what the indexes mix, or with only a mixer, the catalogue's. */
    private fun pickable(m: EmojiMixer, state: KeyboardUiState): List<String> {
        if (m.canList) return m.supported()
        return (state.emojiRecents + state.emojiCatalog.filter { it.parent.isNullOrEmpty() }.map { it.emoji })
            .distinct()
            .take(CATALOG_PICKER_LIMIT)
    }

    /** An emoji picked in the panel's picker: it fills the slot the panel is on. */
    fun onPickEmoji(emoji: String) {
        host.haptic()
        val ui = host.state.emojiMix
        if (ui.slot == 0 || ui.left.isEmpty()) setPair(emoji, ui.right, slot = 1) else setPair(ui.left, emoji, slot = 1)
    }

    /** One of the two slots tapped: the next pick goes there. */
    fun onSlot(slot: Int) {
        host.haptic()
        host.publish { it.copy(emojiMix = it.emojiMix.copy(slot = slot.coerceIn(0, 1))) }
    }

    fun onSwap() {
        host.haptic()
        val ui = host.state.emojiMix
        if (ui.right.isEmpty()) return
        setPair(ui.right, ui.left, ui.slot)
    }

    /** A random emoji and one of its mixes, the way a shuffle button in such a panel works. */
    fun onShuffle() {
        host.haptic()
        val m = mixer
        val emoji = host.state.emojiMix.emoji
        if (emoji.isEmpty()) return
        val left = emoji.random()
        val self = MixSeq.canonicalOf(left)
        val partner = m.partners(left).randomOrNull()?.let { if (MixSeq.canonicalOf(it.left) == self) it.right else it.left }
        setPair(left, partner ?: emoji.random(), slot = 1)
    }

    /** A mix in the panel tapped: send it. */
    fun onSendCell(cell: EmojiMixCell) {
        val mix = mixer.mix(cell.left, cell.right) ?: EmojiMixer.Mix(cell.left, cell.right, cell.url, cell.verified, "")
        send(mix)
    }

    fun onFavourite(cell: EmojiMixCell, starred: Boolean) {
        host.haptic()
        host.setFavourite(cell.pairKey, starred)
    }

    fun onAddToStickers(cell: EmojiMixCell) = withFile(cell) { file ->
        host.addToStickers(file, listOf(cell.left, cell.right, cell.left + cell.right))
    }

    fun onSaveToGallery(cell: EmojiMixCell) = withFile(cell) { file -> host.saveToGallery(file) }

    /** The favourites as cells, from the stored pairs; a pair no source has any more is skipped. */
    fun refreshFavourites(settings: EmojiMixSettings) {
        val m = mixer
        val cells = settings.favourites.mapNotNull { key ->
            val (l, r) = key.split('_').takeIf { it.size == 2 } ?: return@mapNotNull null
            val left = MixSeq.emoji(l) ?: return@mapNotNull null
            val right = MixSeq.emoji(r) ?: return@mapNotNull null
            m.mix(left, right)?.asCell()
        }
        host.publish { it.copy(emojiMix = it.emojiMix.copy(favourites = cells)) }
    }

    private fun setPair(left: String, right: String, slot: Int) {
        val m = mixer
        val result = if (left.isNotEmpty() && right.isNotEmpty()) m.mix(left, right) else null
        val mixes = if (left.isNotEmpty() && m.canList) ordered(m.partners(left), left) else emptyList()
        host.publish {
            it.copy(
                emojiMix = it.emojiMix.copy(
                    left = left,
                    right = right,
                    slot = slot,
                    result = result?.takeIf { r -> r.verified }?.asCell(),
                    noMix = left.isNotEmpty() && right.isNotEmpty() && result == null,
                    mixes = mixes.map { mix -> mix.asCell() },
                    loading = result != null && !result.verified,
                ),
            )
        }
        // A mixer no index backs: the pair is only real if its image is not the placeholder.
        verifyJob?.cancel()
        val unverified = listOfNotNull(result?.takeIf { !it.verified }) +
            if (!m.canList && left.isNotEmpty()) mixerGuesses(left) else emptyList()
        if (unverified.isEmpty() || !host.previewsAllowed()) {
            if (result != null && !result.verified) host.publish { it.copy(emojiMix = it.emojiMix.copy(loading = false)) }
            return
        }
        verifyJob = scope.launch {
            val checked = withContext(Dispatchers.IO) {
                unverified.map { mix -> async { mix to store.fetch(mix, sourceOf(mix)) } }.awaitAll()
            }
            val real = checked.mapNotNull { (mix, file) -> file?.let { mix.asCell().copy(url = it.toURI().toString(), verified = true) } }
            host.publish { state ->
                val ui = state.emojiMix
                if (ui.left != left || ui.right != right) return@publish state
                val pairCell = result?.let { r -> real.firstOrNull { it.id == r.id } }
                state.copy(
                    emojiMix = ui.copy(
                        result = pairCell ?: ui.result,
                        noMix = result != null && pairCell == null,
                        mixes = if (m.canList) ui.mixes else real.filter { it.id != result?.id },
                        loading = false,
                    ),
                )
            }
        }
    }

    /** With a mixer and no index: [left] with the emoji used lately, each to be checked. */
    private fun mixerGuesses(left: String): List<EmojiMixer.Mix> {
        val state = host.state
        return (state.emojiRecents + state.emojiFrequents)
            .distinct()
            .filter { MixSeq.canonicalOf(it) != MixSeq.canonicalOf(left) }
            .take(MIXER_GUESSES)
            .mapNotNull { mixer.mix(left, it) }
    }

    // ---- sending ----

    private fun send(mix: EmojiMixer.Mix) {
        val state = host.state
        if (state.mediaDownloadingId != null) return
        if (!state.acceptsRichMedia) {
            host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_emoji_mix_field_refuses_toast)
            return
        }
        host.haptic()
        host.startFetch(mix.id)
        scope.launch {
            val file = withContext(Dispatchers.IO) { store.fetch(mix, sourceOf(mix)) }
            host.endFetch()
            if (file == null) {
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_emoji_mix_fetch_failed_toast)
                return@launch
            }
            host.send(file, host.state.settings.emoji.mix.sendMode)
        }
    }

    private fun withFile(cell: EmojiMixCell, then: (File) -> Unit) {
        val mix = mixer.mix(cell.left, cell.right) ?: return
        if (host.state.mediaDownloadingId != null) return
        host.haptic()
        host.startFetch(mix.id)
        scope.launch {
            val file = withContext(Dispatchers.IO) { store.fetch(mix, sourceOf(mix)) }
            host.endFetch()
            if (file == null) {
                host.toast(com.wasimaster.wmkeyboard.ime.R.string.ime_emoji_mix_fetch_failed_toast)
            } else {
                then(file)
            }
        }
    }

    private fun sourceOf(mix: EmojiMixer.Mix): EmojiMixSource? = sources.firstOrNull { it.id == mix.sourceId }

    private fun EmojiMixer.Mix.asCell() = EmojiMixCell(id, left, right, url, verified)

    private fun EmojiMixer.Mix.asGifItem() = GifItem(
        id = id,
        previewUrl = url,
        fullUrl = url,
        mime = MediaMime.PNG,
        aspectRatio = 1f,
        source = GifSource.LOCAL,
        title = left + right,
    )

    /** The two emoji an offered item is the mix of, from its title. */
    private fun pairOf(item: GifItem): Pair<String, String>? {
        val tail = MixSeq.trailingEmoji(item.title, 2)
        return if (tail.size == 2) tail[0] to tail[1] else null
    }

    private companion object {
        const val OFFER_LIMIT = 40
        const val CATALOG_PICKER_LIMIT = 600
        const val MIXER_GUESSES = 12
    }
}
