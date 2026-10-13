package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A SentencePiece-style vocabulary, decode only: token ids back to text.
 * Pieces use `▁` for a space before a word and `<0xNN>` for a raw byte (byte
 * fallback); control tokens decode to nothing. Parakeet ships a SentencePiece
 * `tokenizer.model`, Moonshine a Hugging Face `tokenizer.json` holding the
 * same kind of vocabulary; both load into this.
 */
class PieceVocab(private val pieces: Array<String?>, private val skip: Set<Int>) {

    val size: Int get() = pieces.size

    /**
     * [ids] as text. Bytes are gathered first and decoded once, so a character
     * spread over several byte pieces comes out whole. The space a leading `▁`
     * stands for is dropped, as the tokenizers' own decoders do.
     */
    fun decode(ids: List<Int>): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            if (id in skip) continue
            val piece = pieces.getOrNull(id) ?: continue
            val byte = BYTE_PIECE.matchEntire(piece)
            if (byte != null) {
                out.write(byte.groupValues[1].toInt(16))
            } else {
                out.write(piece.replace('▁', ' ').toByteArray(Charsets.UTF_8))
            }
        }
        return out.toString("UTF-8").removePrefix(" ")
    }

    companion object {
        private val BYTE_PIECE = Regex("<0x([0-9A-Fa-f]{2})>")

        /** SentencePiece's piece types that are text; the rest are control, unknown or unused. */
        private const val TYPE_NORMAL = 1
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_BYTE = 6

        /**
         * Reads a SentencePiece `ModelProto`: field 1 is each piece, a message
         * whose field 1 is the piece's text and field 3 its type. Nothing else
         * in the file is needed to decode.
         */
        fun fromSentencePiece(bytes: ByteArray): PieceVocab {
            val pieces = ArrayList<String?>()
            val skip = HashSet<Int>()
            val top = ProtoReader(bytes, 0, bytes.size)
            while (top.hasMore()) {
                val (field, wire) = top.tag()
                if (field == 1 && wire == 2) {
                    val (start, end) = top.lengthDelimited()
                    val m = ProtoReader(bytes, start, end)
                    var text: String? = null
                    var type = TYPE_NORMAL
                    while (m.hasMore()) {
                        val (f, w) = m.tag()
                        when {
                            f == 1 && w == 2 -> {
                                val (s, e) = m.lengthDelimited()
                                text = String(bytes, s, e - s, Charsets.UTF_8)
                            }
                            f == 3 && w == 0 -> type = m.varint().toInt()
                            else -> m.skip(w)
                        }
                    }
                    if (type != TYPE_NORMAL && type != TYPE_USER_DEFINED && type != TYPE_BYTE) skip += pieces.size
                    pieces += text
                } else {
                    top.skip(wire)
                }
            }
            return PieceVocab(pieces.toTypedArray(), skip)
        }

        /**
         * Reads a Hugging Face `tokenizer.json` whose model is a BPE vocabulary
         * of SentencePiece pieces (`model.vocab`, piece to id), with
         * `added_tokens` that are special skipped.
         */
        fun fromTokenizerJson(text: String): PieceVocab {
            val root = Json.parseToJsonElement(text).jsonObject
            val vocab = root.getValue("model").jsonObject.getValue("vocab").jsonObject
            val added = root["added_tokens"]?.jsonArray.orEmpty()
            var max = vocab.values.maxOfOrNull { it.jsonPrimitive.int } ?: -1
            for (a in added) max = maxOf(max, a.jsonObject.getValue("id").jsonPrimitive.int)
            val pieces = arrayOfNulls<String>(max + 1)
            for ((piece, id) in vocab) pieces[id.jsonPrimitive.int] = piece
            val skip = HashSet<Int>()
            for (a in added) {
                val o = a.jsonObject
                val id = o.getValue("id").jsonPrimitive.int
                pieces[id] = o.getValue("content").jsonPrimitive.content
                if (o["special"]?.jsonPrimitive?.booleanOrNull == true) skip += id
            }
            return PieceVocab(pieces, skip)
        }
    }

    /** Just enough protobuf wire format to walk a SentencePiece model. */
    private class ProtoReader(private val b: ByteArray, private var at: Int, private val end: Int) {
        fun hasMore() = at < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[at++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        fun lengthDelimited(): Pair<Int, Int> {
            val len = varint().toInt()
            val start = at
            at += len
            return start to at
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> at += 8
                2 -> lengthDelimited()
                5 -> at += 4
                else -> error("unsupported protobuf wire type $wire")
            }
        }
    }
}
