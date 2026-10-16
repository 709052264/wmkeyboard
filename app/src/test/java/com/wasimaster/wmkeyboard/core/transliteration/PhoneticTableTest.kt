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

    @Test fun anEmptyTableIsEmpty() {
        assertTrue(PhoneticTables.index("hi", PhoneticTables.build("hi", emptyList())).isEmpty)
        assertTrue(PhoneticTable.combine(emptyList()).isEmpty)
    }
}
