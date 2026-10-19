package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** How the Look up card sits over the app (#765). */
enum class LookupCardStyle {
    /** A short sheet from the bottom of the screen, the app still showing above it. */
    SHEET,

    /**
     * A small card beside the word, with the word marked, as Yomichan shows
     * it. Only a tapped word has a place on screen; a word from the text
     * selection menu still gets the sheet.
     */
    POPUP,
}

/** How a word goes to AnkiDroid from the card. */
enum class AnkiMode {
    /** Added straight to the deck when AnkiDroid allows it, else AnkiDroid's editor opens filled in. */
    AUTO,

    /** Only straight to the deck; the button asks for AnkiDroid's permission when it has none. */
    DIRECT,

    /** Always AnkiDroid's editor, filled in, to check and save there. */
    EDITOR,
}

/**
 * The Look up card (#765): a word in any app, looked up from the text
 * selection menu or by tapping it after the floating button's Look up. One
 * nested bag for the reason [GallerySettings] gives, with flat `lookup_*`
 * DataStore keys read and written by [LookupPrefs].
 */
data class LookupSettings(
    val cardStyle: LookupCardStyle = LookupCardStyle.SHEET,
    /** "Look up" in other apps' text selection menus. */
    val selectionMenu: Boolean = true,
    val ankiMode: AnkiMode = AnkiMode.AUTO,
    /** The AnkiDroid deck words go to; made on first use. */
    val ankiDeck: String = DEFAULT_ANKI_DECK,
) {
    companion object {
        const val DEFAULT_ANKI_DECK = "WM Keyboard"
    }
}

object LookupPrefs {
    private val CARD_STYLE = stringPreferencesKey("lookup_card_style")
    private val SELECTION_MENU = booleanPreferencesKey("lookup_selection_menu")
    private val ANKI_MODE = stringPreferencesKey("lookup_anki_mode")
    private val ANKI_DECK = stringPreferencesKey("lookup_anki_deck")

    private inline fun <reified E : Enum<E>> enumOf(name: String?): E? =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    fun read(p: Preferences, d: LookupSettings): LookupSettings = LookupSettings(
        cardStyle = enumOf<LookupCardStyle>(p[CARD_STYLE]) ?: d.cardStyle,
        selectionMenu = p[SELECTION_MENU] ?: d.selectionMenu,
        ankiMode = enumOf<AnkiMode>(p[ANKI_MODE]) ?: d.ankiMode,
        ankiDeck = p[ANKI_DECK]?.trim()?.takeIf { it.isNotEmpty() } ?: d.ankiDeck,
    )

    /** Writes only what differs from [old], so a one-field change is a one-key edit. */
    fun write(p: MutablePreferences, old: LookupSettings, new: LookupSettings) {
        if (new.cardStyle != old.cardStyle) p[CARD_STYLE] = new.cardStyle.name
        if (new.selectionMenu != old.selectionMenu) p[SELECTION_MENU] = new.selectionMenu
        if (new.ankiMode != old.ankiMode) p[ANKI_MODE] = new.ankiMode.name
        if (new.ankiDeck != old.ankiDeck) p[ANKI_DECK] = new.ankiDeck.trim()
    }
}
