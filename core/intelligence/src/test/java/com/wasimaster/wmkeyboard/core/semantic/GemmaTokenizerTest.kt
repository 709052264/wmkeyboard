package com.wasimaster.wmkeyboard.core.semantic

import com.wasimaster.wmkeyboard.core.text.GemmaTokenizer
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The BPE rules on a model small enough to follow by hand. The real model
 * (262,144 pieces) was checked against SentencePiece itself on 620 texts in
 * a dozen scripts, emoji and HTML included, with identical ids for all.
 */
class GemmaTokenizerTest {

    private val pieces = listOf(
        "<pad>" to (0f to GemmaTokenizer.TYPE_CONTROL),
        "<eos>" to (0f to GemmaTokenizer.TYPE_CONTROL),
        "<bos>" to (0f to GemmaTokenizer.TYPE_CONTROL),
        "<unk>" to (0f to GemmaTokenizer.TYPE_UNKNOWN),
        "<|image>" to (0f to GemmaTokenizer.TYPE_USER_DEFINED),
        "\n\n" to (0f to GemmaTokenizer.TYPE_USER_DEFINED),
        "▁" to (-1f to GemmaTokenizer.TYPE_NORMAL),
        "h" to (-2f to GemmaTokenizer.TYPE_NORMAL),
        "e" to (-2f to GemmaTokenizer.TYPE_NORMAL),
        "l" to (-2f to GemmaTokenizer.TYPE_NORMAL),
        "o" to (-2f to GemmaTokenizer.TYPE_NORMAL),
        "w" to (-2f to GemmaTokenizer.TYPE_NORMAL),
        "ll" to (-3f to GemmaTokenizer.TYPE_NORMAL),
        "he" to (-4f to GemmaTokenizer.TYPE_NORMAL),
        "hell" to (-5f to GemmaTokenizer.TYPE_NORMAL),
        "hello" to (-6f to GemmaTokenizer.TYPE_NORMAL),
        "▁w" to (-7f to GemmaTokenizer.TYPE_NORMAL),
        "\n" to (-8f to GemmaTokenizer.TYPE_NORMAL),
    ) + (0 until 256).map { "<0x%02X>".format(it) to (0f to GemmaTokenizer.TYPE_BYTE) }

    private val tokenizer = GemmaTokenizer.read(model(pieces))

    private fun id(piece: String) = tokenizer.idOf(piece)

    @Test
    fun specialIdsComeFromTheModel() {
        assertEquals(2, tokenizer.bosId)
        assertEquals(1, tokenizer.eosId)
        assertEquals(3, tokenizer.unkId)
    }

    @Test
    fun theBestScoringPairMergesFirst() {
        // ll (-3) before he (-4), then hell, then hello.
        assertArrayEquals(intArrayOf(id("hello")), tokenizer.encode("hello"))
    }

    @Test
    fun spacesBecomeTheMetaSymbolAndMergeOnlyIntoPieces() {
        assertArrayEquals(intArrayOf(id("hello"), id("▁w"), id("o")), tokenizer.encode("hello wo"))
    }

    @Test
    fun userDefinedPiecesAreTakenWholeAndNeverMerged() {
        assertArrayEquals(intArrayOf(id("<|image>"), id("he")), tokenizer.encode("<|image>he"))
        assertArrayEquals(intArrayOf(id("\n\n"), id("\n")), tokenizer.encode("\n\n\n"))
    }

    @Test
    fun anUnknownCharacterFallsBackToItsBytes() {
        // ব is E0 A6 AC in UTF-8.
        assertArrayEquals(intArrayOf(id("<0xE0>"), id("<0xA6>"), id("<0xAC>")), tokenizer.encode("ব"))
        // An emoji outside the BMP is one character, four bytes.
        assertEquals(4, tokenizer.encode("🐈").size)
    }

    @Test
    fun theEmptyTextHasNoTokens() {
        assertEquals(0, tokenizer.encode("").size)
    }

    @Test
    fun embeddingInputAddsBosAndEosAndKeepsEosWhenCut() {
        val ids = EmbeddingInput.tokens(tokenizer, "hello ".repeat(400), EmbeddingInput.Role.QUERY)
        assertEquals(EmbeddingInput.MAX_QUERY_TOKENS, ids.size)
        assertEquals(tokenizer.bosId, ids.first())
        assertEquals(tokenizer.eosId, ids.last())
    }

    companion object {
        /** A SentencePiece ModelProto holding [pieces], as protobuf. */
        fun model(pieces: List<Pair<String, Pair<Float, Byte>>>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((piece, rest) in pieces) {
                val inner = ByteArrayOutputStream()
                val text = piece.toByteArray(Charsets.UTF_8)
                inner.write(0x0A)
                varint(inner, text.size)
                inner.write(text)
                inner.write(0x15)
                val bits = rest.first.toRawBits()
                for (k in 0 until 4) inner.write((bits ushr (8 * k)) and 0xFF)
                inner.write(0x18)
                varint(inner, rest.second.toInt())
                val bytes = inner.toByteArray()
                out.write(0x0A)
                varint(out, bytes.size)
                out.write(bytes)
            }
            // A trainer spec the reader must skip.
            out.write(0x12)
            out.write(2)
            out.write(0x08)
            out.write(0x02)
            return out.toByteArray()
        }

        private fun varint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while (v >= 0x80) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
        }
    }
}
