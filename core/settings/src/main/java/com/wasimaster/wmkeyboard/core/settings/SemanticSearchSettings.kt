package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** How much of search by meaning is on, which is also how much of the model is downloaded. */
enum class SemanticSearchMode {
    /** Keyword search only, as before. Nothing is downloaded. */
    OFF,

    /** Clips, notes, snippets and emoji found by meaning: the text model, about 152 MB. */
    TEXT,

    /** Stickers and saved GIFs found by what they show as well: about 221 MB more. */
    TEXT_AND_PICTURES,
}

/**
 * Search by meaning: the keyboard's search boxes also find what a query
 * means, in any language, with EmbeddingGemma 2 run on the device. Off by
 * default, since the model is a large download. One nested bag for the
 * reason [GallerySettings] gives, with flat `semantic_*` DataStore keys read
 * and written by [SemanticSearchPrefs].
 */
data class SemanticSearchSettings(
    val mode: SemanticSearchMode = SemanticSearchMode.OFF,
    /**
     * With pictures on: own stickers that have no keywords get some from
     * what they show, so they are offered while typing too. Kept apart from
     * the user's own keywords and never written into a pack.
     */
    val tagStickers: Boolean = true,
) {
    val enabled: Boolean get() = mode != SemanticSearchMode.OFF
    val pictures: Boolean get() = mode == SemanticSearchMode.TEXT_AND_PICTURES
}

object SemanticSearchPrefs {
    private val MODE = stringPreferencesKey("semantic_mode")
    private val TAG_STICKERS = booleanPreferencesKey("semantic_tag_stickers")

    fun read(p: Preferences, d: SemanticSearchSettings): SemanticSearchSettings = SemanticSearchSettings(
        mode = p[MODE]?.let { name -> SemanticSearchMode.entries.firstOrNull { it.name == name } } ?: d.mode,
        tagStickers = p[TAG_STICKERS] ?: d.tagStickers,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: SemanticSearchSettings, new: SemanticSearchSettings) {
        if (new.mode != old.mode) p[MODE] = new.mode.name
        if (new.tagStickers != old.tagStickers) p[TAG_STICKERS] = new.tagStickers
    }
}
