package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.script.ScriptId
import java.io.File
import java.lang.Character.UnicodeScript

/**
 * Which of the Omnilingual model's tokens a dictation may write. The model
 * has no language conditioning and spells by what it hears, so Hindi speech
 * regularly comes back in Urdu letters and English now and then in Arabic
 * ones. The native decoder takes the flags built here and lets only flagged
 * tokens win a CTC frame, which keeps the text to the script of the language
 * being dictated.
 *
 * Every token in the model's tokens.txt is one character, so a token is
 * judged by its character's Unicode script: the dictation's own scripts pass,
 * as do digits, spaces and punctuation. Two groups need more than the script
 * property, because Java has no Script_Extensions: the modifier letters
 * (ʻ ʼ ː) that Latin orthographies use as letters, and the combining marks
 * Unicode files under Inherited, which here are Latin's diacritics, Arabic's
 * harakat, the Vedic accents and the zero-width joiner. Symbols (●, ®, ✿)
 * never pass.
 */
object OmnilingualScriptMask {

    /** Ids 0 to 3 are `<s>` (the CTC blank), `<pad>`, `</s>` and `<unk>`. */
    private const val BLANK = 0

    private val EUROPEAN = setOf(UnicodeScript.LATIN, UnicodeScript.GREEK, UnicodeScript.CYRILLIC)

    private val INDIC = setOf(
        UnicodeScript.DEVANAGARI, UnicodeScript.BENGALI, UnicodeScript.GURMUKHI, UnicodeScript.GUJARATI,
        UnicodeScript.ORIYA, UnicodeScript.TAMIL, UnicodeScript.TELUGU, UnicodeScript.KANNADA,
        UnicodeScript.MALAYALAM,
    )

    /**
     * The Unicode scripts that text in [script] is written in, or null when
     * there is no telling (a notation, or a script this Java does not name),
     * and the model is then left to write what it likes.
     */
    fun unicodeScripts(script: ScriptId): Set<UnicodeScript>? = when (script) {
        ScriptId.JAPANESE -> setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN)
        ScriptId.IPA -> setOf(UnicodeScript.LATIN)
        ScriptId.CANADIAN_ABORIGINAL_SYLLABICS -> setOf(UnicodeScript.CANADIAN_ABORIGINAL)
        ScriptId.MUSIC, ScriptId.BRAILLE, ScriptId.PIQAD -> null
        else -> runCatching { UnicodeScript.valueOf(script.name) }.getOrNull()?.let(::setOf)
    }

    /**
     * The scripts a dictation in [scripts] (one per language it listens for)
     * may write: all of them, so a dictation in Hindi and English keeps both
     * Devanagari and Latin and shuts out only the rest. Null when any one of
     * them has no Unicode script to hold the model to.
     */
    fun unicodeScripts(scripts: Collection<ScriptId>): Set<UnicodeScript>? {
        if (scripts.isEmpty()) return null
        val out = HashSet<UnicodeScript>()
        for (script in scripts) out += unicodeScripts(script) ?: return null
        return out
    }

    /** tokens.txt as a list indexed by id: `<symbol> <id>` per line, where the symbol may be a space. */
    fun readTokens(file: File): List<String> {
        val byId = HashMap<Int, String>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val space = line.lastIndexOf(' ')
            if (space < 0) return@forEachLine
            val id = line.substring(space + 1).trim().toIntOrNull() ?: return@forEachLine
            byId[id] = line.substring(0, space)
        }
        val size = (byId.keys.maxOrNull() ?: -1) + 1
        return List(size) { byId[it].orEmpty() }
    }

    /**
     * One flag per token in [tokens] (indexed by id): whether text held to
     * [scripts] can contain it. Null when none of [scripts] has a single
     * letter among the tokens, since a mask would then leave the model
     * nothing to say; the model writes what it likes instead.
     */
    fun build(tokens: List<String>, scripts: Set<UnicodeScript>): BooleanArray? {
        var letters = 0
        val out = BooleanArray(tokens.size) { id ->
            val symbol = tokens[id]
            when {
                id == BLANK -> true
                symbol.isEmpty() || symbol.codePointCount(0, symbol.length) != 1 -> false
                else -> {
                    val cp = symbol.codePointAt(0)
                    val script = UnicodeScript.of(cp)
                    when (script) {
                        UnicodeScript.COMMON -> commonFits(cp, scripts)
                        UnicodeScript.INHERITED -> inheritedFits(cp, scripts)
                        in scripts -> true.also { letters++ }
                        else -> false
                    }
                }
            }
        }
        return if (letters == 0) null else out
    }

    /** Digits, spaces and punctuation anywhere; the modifier letters only in Latin. */
    private fun commonFits(cp: Int, scripts: Set<UnicodeScript>): Boolean = when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER,
        Character.SPACE_SEPARATOR,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.CONNECTOR_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        // Hawaiian's ʻokina, the glottal stop ʼ, the length mark ː.
        Character.MODIFIER_LETTER -> UnicodeScript.LATIN in scripts
        else -> false
    }

    /** A combining mark, by the script it belongs to. */
    private fun inheritedFits(cp: Int, scripts: Set<UnicodeScript>): Boolean = when (cp) {
        // Harakat, the hamza marks, the superscript alef.
        in 0x064B..0x065F, 0x0670 -> UnicodeScript.ARABIC in scripts
        // The Vedic accents.
        in 0x0951..0x0954, in 0x1CD0..0x1CFF -> scripts.any { it in INDIC }
        // The joiners shape conjuncts and ligatures; in a European script
        // they would only be invisible noise.
        0x200C, 0x200D -> scripts.any { it !in EUROPEAN }
        // The combining diacritical marks proper (U+0300.., U+1DC0..).
        else -> scripts.any { it in EUROPEAN }
    }
}
