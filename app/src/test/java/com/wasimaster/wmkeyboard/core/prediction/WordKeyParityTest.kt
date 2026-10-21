package com.wasimaster.wmkeyboard.core.prediction

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.VersionInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Rust word key (`native/wm-engine/src/word_key.rs`, behind
 * [NativeWordKey]) against [WordKey.of], [WordKey.surface]
 * and [WordContext.isLearnableWord] as a phone runs them.
 *
 * A JVM test cannot run ART, and HotSpot's `String.lowercase` is not ART's:
 * OpenJDK decides a final sigma by word boundaries and carries Unicode 15.0
 * (JDK 21), where libcore's `CaseMapper` checks one character either side
 * and asks the platform ICU. So the reference here is [Art]: `CaseMapper.
 * toLowerCase` transcribed from libcore (libart/src/main/java/java/lang/
 * CaseMapper.java), with the ICU calls it makes answered by ICU4J, which
 * the tests take at ICU 78 (the version Robolectric pins), the same Unicode (17) as the
 * Rust side's ICU4X. Rust has to equal it on every code point, every
 * code point in a sigma's context, every lone surrogate, and every word of
 * the bundled lists (and, with `PARITY_DOWNLOADS`, the data repo's lists).
 *
 * What a real phone does differently is then a question of Unicode version
 * only, and [olderUnicodeFoldsDifferently] lists it: the characters whose
 * fold a phone's older ICU does not know. [hotSpotDiffersOnlyWhereExplained]
 * holds the JVM's own `WordKey.of` to the same account, so the tests that
 * run Kotlin on HotSpot are known to agree with Rust everywhere they reach.
 */
class WordKeyParityTest {

    /** ART's `String.toLowerCase(Locale.ROOT)` and the word key built on it. */
    private object Art {
        private val nfc = Normalizer2.getNFCInstance()

        private fun cased(c: Char): Boolean = when (UCharacter.getType(c.code)) {
            UCharacterCategory.UPPERCASE_LETTER.toInt(),
            UCharacterCategory.LOWERCASE_LETTER.toInt(),
            UCharacterCategory.TITLECASE_LETTER.toInt(),
            -> true
            else -> false
        }

        private fun isFinalSigma(s: String, index: Int): Boolean {
            if (index <= 0 || !cased(s[index - 1])) return false
            if (index + 1 >= s.length) return true
            return !cased(s[index + 1])
        }

        /** `CaseMapper.toLowerCase(Locale.ROOT, s)`. */
        fun lowercase(s: String): String {
            if (s.any { it == 'İ' || Character.isHighSurrogate(it) }) return UCharacter.toLowerCase(ULocale.ROOT, s)
            val out = CharArray(s.length)
            for (i in s.indices) {
                val ch = s[i]
                out[i] = when {
                    ch == 'Σ' && isFinalSigma(s, i) -> 'ς'
                    // Character.toLowerCase(char): ASCII inline, then u_tolower,
                    // narrowed back to a char.
                    else -> UCharacter.toLowerCase(ch.code).toChar()
                }
            }
            return String(out)
        }

        private fun normalize(s: String): String = if (s.all { it < '̀' }) s else nfc.normalize(s)

        fun of(s: String): String = normalize(lowercase(s))

        fun surface(s: String): String = normalize(s)

        private fun wordChar(c: Char): Boolean = UCharacter.isLetter(c.code) || when (UCharacter.getType(c.code)) {
            UCharacterCategory.NON_SPACING_MARK.toInt(),
            UCharacterCategory.COMBINING_SPACING_MARK.toInt(),
            UCharacterCategory.ENCLOSING_MARK.toInt(),
            -> true
            else -> false
        }

        /** `WordContext.isLearnableWord`, with ICU's categories. */
        fun learnable(word: String): Boolean {
            if (word.isEmpty()) return false
            fun letterOrDigit(c: Char) = wordChar(c) || UCharacter.isDigit(c.code)
            if (!letterOrDigit(word[0]) || !letterOrDigit(word[word.length - 1])) return false
            var afterJoiner = false
            var letters = 0
            for (c in word) {
                if (wordChar(c)) letters++
                afterJoiner = when {
                    letterOrDigit(c) -> false
                    c in WordContext.WORD_JOINERS && !afterJoiner -> true
                    else -> return false
                }
            }
            return letters > 0
        }
    }

    private class Mismatches(val what: String) {
        var checked = 0
        var differ = 0
        val found = ArrayList<String>()

        fun check(input: String, expected: Any, actual: Any) {
            checked++
            if (expected == actual) return
            differ++
            if (found.size < 40) found += "${hex(input)}: expected ${show(expected)}, got ${show(actual)}"
        }

        fun assertNone() {
            assertTrue("$what: $differ of $checked differ\n" + found.joinToString("\n"), differ == 0)
        }
    }

    private fun checkWord(s: String, keys: Mismatches, surfaces: Mismatches, gate: Mismatches) {
        keys.check(s, Art.of(s), NativeWordKey.of(s))
        surfaces.check(s, Art.surface(s), NativeWordKey.surface(s))
        gate.check(s, Art.learnable(s), NativeWordKey.isLearnable(s))
    }

    private fun everyCodePoint(): Sequence<String> =
        (0..0x10FFFF).asSequence().filter { it !in 0xD800..0xDFFF }.map { String(Character.toChars(it)) }

    @Test
    fun everyCodePointAlone() {
        val keys = Mismatches("key")
        val surfaces = Mismatches("surface")
        val gate = Mismatches("learnable")
        for (s in everyCodePoint()) checkWord(s, keys, surfaces, gate)
        // Every lone surrogate, alone and inside a word.
        for (u in 0xD800..0xDFFF) {
            val c = u.toChar()
            for (s in listOf("$c", "A${c}B", "Σ$c", "ΑΣ$c", "${c}İ")) checkWord(s, keys, surfaces, gate)
        }
        keys.assertNone()
        surfaces.assertNone()
        gate.assertNone()
    }

    /**
     * Every code point beside a capital sigma, where the two paths read the
     * context differently, and beside a letter, where it may compose. A
     * supplementary character sends the whole string down ICU's path, so each
     * of those is also a check of ICU4X's Final_Sigma against ICU's.
     */
    @Test
    fun everyCodePointInContext() {
        val keys = Mismatches("key")
        val surfaces = Mismatches("surface")
        val gate = Mismatches("learnable")
        for (c in everyCodePoint()) {
            for (s in listOf("Α${c}Σ", "ΑΣ$c", "ΑΣ${c}Α", "Σ${c}", "e$c", "${c}A", "İ${c}Σ", "ΑΣ${c}𐒰")) {
                checkWord(s, keys, surfaces, gate)
            }
        }
        keys.assertNone()
        surfaces.assertNone()
        gate.assertNone()
    }

    /** No BMP character lowercases out of the BMP, which ART's narrowing (and the port's) assumes. */
    @Test
    fun noBmpLetterLowercasesOutOfTheBmp() {
        for (u in 0..0xFFFF) {
            if (u in 0xD800..0xDFFF) continue
            assertTrue("U+%04X".format(u), UCharacter.toLowerCase(u) <= 0xFFFF)
        }
    }

    private fun lists(): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        for (name in listOf("en", "bn")) {
            val file = listOf(File("dictionaries-src/$name.txt"), File("app/dictionaries-src/$name.txt")).first { it.exists() }
            out += "$name (bundled)" to file.inputStream().use { DictionaryLoader.loadEntries(it) }.map { it.first }
        }
        val downloads = System.getenv("PARITY_DOWNLOADS")?.let(::File)?.takeIf { it.isDirectory } ?: return out
        val files = downloads.walkTopDown().maxDepth(2).filter { it.name.endsWith("_full.txt.gz") }.sortedBy { it.name }
        val cap = System.getenv("PARITY_DOWNLOADS_CAP")?.toIntOrNull() ?: 50_000
        for (file in files) {
            val words = ArrayList<String>()
            GZIPInputStream(file.inputStream()).use { input ->
                DictionaryLoader.forEachEntry(input) { word, _ -> if (words.size < cap) words += word }
            }
            out += file.name.removeSuffix("_full.txt.gz") to words
        }
        return out
    }

    /**
     * Every word of the lists in three spellings: as listed, capitalised and
     * in capitals (Kotlin's `uppercase`, which turns ß into SS and gives
     * Greek its capital sigma), against [Art], and against [WordKey.of] and
     * [WordContext.isLearnableWord] themselves on this JVM, where a
     * difference has to be one [hotSpotDiffersOnlyWhereExplained] accounts
     * for.
     *
     * The data repo's lists turn up one such case: polytonic Greek in
     * capitals. `ΤΗ͂Σ` (τῆς uppercased: no capital eta with perispomeni
     * exists, so the accent comes apart) folds to `τῆσ` on a phone, since
     * the unit before the Σ is the accent, not a cased letter; HotSpot and
     * ICU skip the accent and give `τῆς`. Rust gives the phone's answer.
     */
    @Test
    fun wordLists() {
        val report = StringBuilder()
        val unexplained = ArrayList<String>()
        for ((name, words) in lists()) {
            val keys = Mismatches("$name key")
            val surfaces = Mismatches("$name surface")
            val gate = Mismatches("$name learnable")
            var sigma = 0
            for (word in words) {
                for (s in listOf(word, word.replaceFirstChar { it.uppercase() }, word.uppercase()).distinct()) {
                    checkWord(s, keys, surfaces, gate)
                    val rust = NativeWordKey.of(s)
                    if (WordKey.of(s) != rust) {
                        if ('Σ' in s) sigma++ else if (unexplained.size < 20) unexplained += "$name ${hex(s)}"
                    }
                    if (WordContext.isLearnableWord(s) != NativeWordKey.isLearnable(s) && unexplained.size < 20) {
                        unexplained += "$name learnable ${hex(s)}"
                    }
                }
            }
            keys.assertNone()
            surfaces.assertNone()
            gate.assertNone()
            report.append("$name: ${words.size} words, ${keys.checked} spellings")
                .append(if (sigma == 0) "\n" else ", $sigma fold differently on HotSpot (final sigma)\n")
        }
        println(report)
        assertTrue("HotSpot differs where nothing explains it: $unexplained", unexplained.isEmpty())
    }

    /**
     * What a phone on an older Unicode folds differently from Rust (Unicode
     * 17): the characters it does not know, whose lowercase or category
     * Unicode added later. Printed, not asserted, for the plan's notes;
     * asserted only that every one of them is younger than the version
     * named, which is what makes "older Unicode" the whole explanation.
     */
    @Test
    fun olderUnicodeFoldsDifferently() {
        val versions = listOf("8.0" to "Android 7", "15.0" to "Android 14", "15.1" to "Android 15", "16.0" to "Android 16")
        for ((version, android) in versions) {
            val v = VersionInfo.getInstance(version)
            var lower = 0
            var gate = 0
            val sample = ArrayList<String>()
            for (s in everyCodePoint()) {
                val cp = s.codePointAt(0)
                if (UCharacter.getAge(cp) <= v) continue
                if (Art.of(s) != s) {
                    lower++
                    if (sample.size < 6) sample += hex(s)
                }
                if (Art.learnable(s)) gate++
            }
            println("Unicode $version ($android): $lower code points fold here and not there (${sample.joinToString()}), $gate learnable here only")
            assertTrue(lower >= 0)
        }
    }

    /**
     * The JVM's own fold against Rust on every code point and every sigma
     * context: they may differ only where HotSpot's Unicode (JDK 21: 15.0) is
     * older, or where OpenJDK's final-sigma rule (word boundaries) is not
     * libcore's. Anything else would be a fold the Kotlin tests see and the
     * phone does not.
     */
    @Test
    fun hotSpotDiffersOnlyWhereExplained() {
        val jdkUnicode = VersionInfo.getInstance("15.0")
        val unexplained = ArrayList<String>()
        var byAge = 0
        var bySigma = 0
        for (c in everyCodePoint()) {
            for (s in listOf(c, "Α${c}Σ", "ΑΣ$c", "ΑΣ${c}Α")) {
                val rust = NativeWordKey.of(s)
                if (WordKey.of(s) == rust) continue
                when {
                    UCharacter.getAge(c.codePointAt(0)) > jdkUnicode -> byAge++
                    'Σ' in s -> bySigma++
                    else -> if (unexplained.size < 20) unexplained += hex(s)
                }
            }
        }
        println("HotSpot vs Rust: $byAge newer than Unicode 15.0, $bySigma final-sigma rule")
        assertTrue("unexplained: $unexplained", unexplained.isEmpty())
    }

    private companion object {
        fun hex(s: String) = s.map { "%04X".format(it.code) }.joinToString(" ", "[", "]")

        fun show(v: Any) = if (v is String) hex(v) else v.toString()
    }
}
