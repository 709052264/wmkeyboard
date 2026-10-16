package com.wasimaster.wmkeyboard.core.transliteration

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packed table the phonetic indexes keep their words in (#716): a file the
 * data repo writes has to answer exactly as the same table built in memory,
 * and the tier filter and the imported-list merge have to answer as one table
 * built over the words they stand for. Over the Hindi fold, which is pure
 * Kotlin.
 */
class PhoneticTableTest {

    private val words = listOf(
        "करना" to 900, "कम" to 800, "काम" to 700, "नहीं" to 5000, "है" to 9000,
        "हैं" to 4000, "में" to 8000, "मैं" to 3000, "मित्र" to 400, "और" to 9000,
        "हिंदी" to 500, "हिन्दी" to 400, "पता" to 900, "पत्ता" to 100,
    )
    private val inputs = listOf("karna", "kam", "kaam", "nahi", "hai", "mein", "mitra", "aur", "hindi", "pata", "patta", "k")

    private fun answers(index: PhoneticIndex): List<Any> =
        inputs.map { index.lookup(it) to index.matchStrength(it) } +
            words.map { index.frequencyOf(it.first) } + index.maxFrequency

    @Test fun aWrittenTableAnswersAsTheOneBuiltInMemory() {
        val file = File.createTempFile("phonetic", ".wmpi")
        try {
            file.outputStream().use { PhoneticTables.write("hi", words, it, sourceTag = "hi", sourceWords = words.size) }
            val mapped = PackedPhoneticTable.open(file)!!
            assertEquals("hi", mapped.sourceTag)
            assertEquals(words.size, mapped.sourceWords)
            assertTrue(PhoneticTables.fits("hi", mapped))
            assertEquals(answers(HindiPhoneticIndex(words)), answers(PhoneticTables.index("hi", mapped)))
        } finally {
            file.delete()
        }
    }

    @Test fun aCutShortFileReadsAsAbsent() {
        val file = File.createTempFile("phonetic", ".wmpi")
        try {
            file.outputStream().use { PhoneticTables.write("hi", words, it, sourceTag = "hi", sourceWords = words.size) }
            file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 8))
            assertNull(PackedPhoneticTable.open(file))
        } finally {
            file.delete()
        }
    }

    @Test fun aSmallerTierFilteredOutOfTheWholeListAnswersAsItsOwnTable() {
        val tier = words.filter { it.second >= 800 }
        val kept = tier.map { it.first }.toSet()
        val filtered = PhoneticTable.filter(PhoneticTables.build("hi", words)) { it in kept }
        assertEquals(answers(HindiPhoneticIndex(tier)), answers(PhoneticTables.index("hi", filtered)))
    }

    @Test fun importedWordsOnTopAnswerAsOneTableOverBoth() {
        val imported = listOf("कम" to 9500, "किताब" to 300, "करना" to 50)
        val combined = PhoneticTable.combine(listOf(PhoneticTables.build("hi", words), PhoneticTables.build("hi", imported)))
        assertEquals(answers(HindiPhoneticIndex(words + imported)), answers(PhoneticTables.index("hi", combined)))
    }

    private val bangla = listOf(
        "আমি" to 9000, "আম" to 1500, "আমার" to 7000, "আমরা" to 5000, "আছি" to 6900, "আসি" to 2300,
        "তাকে" to 4850, "থাকে" to 1750, "কথা" to 286, "কথাও" to 993, "ভালো" to 6500, "ভাল" to 1900,
    )

    /** Bangla's index also walks the table's words: Khipro's completions and desktop Avro's search. */
    private fun banglaAnswers(index: PhoneticIndex): List<Any> {
        val words = index.sortedWords!!
        return listOf("ami", "amar", "asi", "thake", "kotha", "kothao", "valo").map { index.lookup(it) to index.matchStrength(it) } +
            listOf("আ", "আম", "ক", "ভ").map { index.completions(it, 3) } +
            (0 until words.size).map { words.word(it) to words.rank(it) } +
            bangla.map { index.frequencyOf(it.first) } + index.maxFrequency
    }

    @Test fun banglaWrittenFilteredAndCombinedAnswerAsOneTable() {
        val file = File.createTempFile("phonetic", ".wmpi")
        try {
            file.outputStream().use { PhoneticTables.write("bn", bangla, it, sourceTag = "bn", sourceWords = bangla.size) }
            val mapped = PackedPhoneticTable.open(file)!!
            assertTrue(PhoneticTables.fits("bn", mapped))
            assertEquals(banglaAnswers(BengaliPhoneticIndex(bangla)), banglaAnswers(PhoneticTables.index("bn", mapped)))
            val tier = bangla.filter { it.second >= 2000 }
            val kept = tier.map { it.first }.toSet()
            assertEquals(
                banglaAnswers(BengaliPhoneticIndex(tier)),
                banglaAnswers(BengaliPhoneticIndex(PhoneticTable.filter(mapped) { it in kept })),
            )
            // An imported word already listed is one word, at its higher frequency.
            val imported = listOf("আমি" to 100, "আমড়া" to 400, "কথাও" to 5000)
            val merged = (bangla + imported).groupBy { it.first }.map { (word, rows) -> word to rows.maxOf { it.second } }
            val combined = BengaliPhoneticIndex(PhoneticTable.combine(listOf(mapped, PhoneticTables.build("bn", imported))))
            assertEquals(banglaAnswers(BengaliPhoneticIndex(merged)).drop(7), banglaAnswers(combined).drop(7))
        } finally {
            file.delete()
        }
    }

    @Test fun anEmptyTableIsEmpty() {
        assertTrue(PhoneticTables.index("hi", PhoneticTables.build("hi", emptyList())).isEmpty)
        assertTrue(PhoneticTable.combine(emptyList()).isEmpty)
    }
}
