package com.wasimaster.wmkeyboard.core.settings

/**
 * The Dictionary tool's translations (#764): the looked-up word in other
 * languages, under its definitions. Nested for the reason [TrackpadSettings]
 * gives; each field still persists under its own DataStore key.
 */
data class DictionaryTranslationSettings(
    /** Show the word's translations under the entry. */
    val enabled: Boolean = true,
    /**
     * Comma-separated Wiktionary language codes, in order; "" means "the
     * enabled keyboard languages other than English".
     */
    val langs: String = "",
) {
    /** The codes in [langs], or empty when it is unset. */
    val langList: List<String>
        get() = langs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
