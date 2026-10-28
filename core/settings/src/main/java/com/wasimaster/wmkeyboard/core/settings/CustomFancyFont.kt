package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.script.FancyStyle
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A Fancy Text font the user made: a name and the alphabets they pasted for
 * it. Stored as typed, not as the letter maps, so the editor opens on exactly
 * what was saved and a fix to how alphabets are read reaches old fonts too.
 * [FancyStyles.fromAlphabets] turns one into a style the strip, the keys and
 * the selection actions use like a built-in one.
 *
 * [id] starts with [FancyStyles.CUSTOM_PREFIX]. [upper] and [digits] may be
 * empty: capitals then take the small forms and digits stay plain.
 */
@Serializable
data class CustomFancyFont(
    val id: String,
    val name: String,
    val lower: String,
    val upper: String = "",
    val digits: String = "",
    val reversed: Boolean = false,
)

/** The style the keyboard types for this font. */
fun CustomFancyFont.toFancyStyle(): FancyStyle =
    FancyStyles.fromAlphabets(id, name, lower, upper, digits, reversed)

/**
 * The most fonts kept. The strip scrolls, but past a few dozen nobody finds
 * the one they want in it any more, and every font is a row of chips.
 */
const val MAX_CUSTOM_FANCY_FONTS = 50

/**
 * The longest alphabet field kept, in UTF-16 units. 26 letters written as
 * astral pairs with a mark or two on each, or as short spaced items, fit with
 * room to spare; a pasted paragraph does not.
 */
const val MAX_FANCY_ALPHABET_LENGTH = 400

private val customFancyFontSerializer = ListSerializer(CustomFancyFont.serializer())

private val customFancyFontJson = Json { ignoreUnknownKeys = true }

internal fun encodeCustomFancyFonts(list: List<CustomFancyFont>): String =
    customFancyFontJson.encodeToString(customFancyFontSerializer, list)

/**
 * The stored list, cleaned like [cleanCustomFancyFonts]. A malformed blob
 * reads as no fonts rather than failing the whole settings read.
 */
internal fun decodeCustomFancyFonts(raw: String?): List<CustomFancyFont> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { customFancyFontJson.decodeFromString(customFancyFontSerializer, raw) }
        .getOrDefault(emptyList())
        .let(::cleanCustomFancyFonts)
}

/**
 * Fonts without a name, an id of the right shape or any small letters are
 * dropped, one entry per id (the first wins), capped at
 * [MAX_CUSTOM_FANCY_FONTS], every alphabet cut to [MAX_FANCY_ALPHABET_LENGTH].
 */
internal fun cleanCustomFancyFonts(list: List<CustomFancyFont>): List<CustomFancyFont> =
    list.asSequence()
        .map {
            it.copy(
                name = it.name.trim(),
                lower = it.lower.trim().take(MAX_FANCY_ALPHABET_LENGTH),
                upper = it.upper.trim().take(MAX_FANCY_ALPHABET_LENGTH),
                digits = it.digits.trim().take(MAX_FANCY_ALPHABET_LENGTH),
            )
        }
        .filter { it.id.startsWith(FancyStyles.CUSTOM_PREFIX) && it.name.isNotEmpty() && it.lower.isNotEmpty() }
        .distinctBy { it.id }
        .take(MAX_CUSTOM_FANCY_FONTS)
        .toList()
