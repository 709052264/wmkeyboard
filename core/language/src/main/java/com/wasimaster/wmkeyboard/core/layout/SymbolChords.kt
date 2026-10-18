package com.wasimaster.wmkeyboard.core.layout

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Chords on `?123` (issue #754): one finger holds `?123` still while another
 * taps a key, and that runs the action bound to the key instead of typing it.
 * A key held still is never part of a swipe, so this is the one way of putting
 * actions on letters that two-thumb typing leaves alone: flick arms stop a swipe
 * starting on the key (#736), and a hold fires under a resting thumb (#737).
 *
 * A chord is named by the key it taps, as the one character the key types,
 * lowercased: `l`, `p`, `1`. The trigger is `?123` alone for now; a second
 * trigger would get a prefix of its own in the stored map, and an older build
 * would drop those entries rather than misread them.
 */
object SymbolChord {
    /** Whether [token] can name a chord: one character, as [tokenOf] writes it. */
    fun isToken(token: String): Boolean =
        token.isNotEmpty() && token.codePointCount(0, token.length) == 1 &&
            !Character.isWhitespace(token.codePointAt(0)) && token == token.lowercase()

    /**
     * The chord a tap on [key] would be, or null for a key that types no single
     * character (a mode key, an action, a key typing a whole word).
     */
    fun tokenOf(key: Key): String? {
        if (key.action != KeyAction.Text) return null
        val text = (key.output ?: key.label).lowercase()
        return text.takeIf(::isToken)
    }
}

/**
 * What a fresh install binds: `?123`+`l` steps to the next language, the one
 * chord every two-thumb typist with a second language reaches for. Removable
 * like any other, by binding it to nothing.
 */
val DefaultSymbolChords: Map<String, KeyAlternate> = mapOf(
    "l" to KeyAlternate(KeyAction.LanguageSwitch),
)

/**
 * What a chord on [token] runs: the user's binding, else the default, else
 * nothing. A binding to [KeyAction.None] is the user having cleared a default,
 * and also answers null.
 */
fun Map<String, KeyAlternate>.chordOf(token: String): KeyAlternate? =
    (this[token] ?: DefaultSymbolChords[token])?.takeUnless { it.action == KeyAction.None }

/** Every chord in force, defaults included and cleared ones left out, by key. */
fun Map<String, KeyAlternate>.effectiveChords(): Map<String, KeyAlternate> =
    (DefaultSymbolChords.keys + keys).sorted()
        .mapNotNull { token -> chordOf(token)?.let { token to it } }
        .toMap()

/**
 * The stored form of the chords: one JSON object keyed by the tapped key's
 * character, each value a [KeyAlternate] in the layout format, so a chord can
 * run anything a key can, the way [FingerSwipeCodec] stores a swipe.
 *
 * A key this build cannot name, or an entry it cannot decode, costs that entry
 * and no more. An action from a newer build decodes to [KeyAction.Unknown] and
 * is dropped too.
 */
object SymbolChordCodec {
    fun decode(json: String?): Map<String, KeyAlternate> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (token, element) ->
            if (!SymbolChord.isToken(token)) return@mapNotNull null
            val binding = runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(element) }.getOrNull()
                ?: return@mapNotNull null
            if (binding.action is KeyAction.Unknown) null else token to binding
        }.toMap()
    }

    fun encode(map: Map<String, KeyAlternate>): String =
        JsonObject(
            map.entries
                .sortedBy { it.key }
                .associate { (token, binding) -> token to layoutJson.encodeToJsonElement(binding) },
        ).toString()
}
