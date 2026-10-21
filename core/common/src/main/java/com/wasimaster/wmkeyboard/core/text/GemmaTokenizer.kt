package com.wasimaster.wmkeyboard.core.text

import java.io.File
import java.util.PriorityQueue

/**
 * EmbeddingGemma 2's tokenizer: the SentencePiece BPE model Gemma uses
 * (262,144 pieces, byte fallback, no normalisation, no dummy prefix), read
 * from the `.model` file itself and run the way SentencePiece's own
 * `bpe_model.cc` runs it, so a text comes out as the same ids the model was
 * trained on. Dictation's punctuation model (mmBERT, Gemma 2's 256,000
 * pieces) uses it too, a word at a time (`core.voice.punctuation` in
 * `:core:voice`).
 *
 * The algorithm, in SentencePiece's words: spaces become `▁`; the text is
 * cut into characters, except that a user-defined piece (`<|image>`, runs of
 * newlines, HTML tags…) found at a position is taken whole and never merged;
 * then the adjacent pair whose joined text is a piece with the highest score
 * is merged, leftmost first on a tie, until no pair is a piece; a symbol that
 * is no piece at all becomes its UTF-8 bytes, `<0x41>` and so on.
 *
 * Kept small on purpose, since it lives as long as the engine: the pieces sit
 * in one [CharArray] with an open-addressing table over it, rather than a
 * quarter of a million strings in a map.
 */
class GemmaTokenizer private constructor(
    private val chars: CharArray,
    private val starts: IntArray,
    private val scores: FloatArray,
    private val types: ByteArray,
    private val table: IntArray,
) {

    val size: Int get() = scores.size

    val bosId: Int = idOf("<bos>")
    val eosId: Int = idOf("<eos>")
    val unkId: Int = idOf("<unk>").takeIf { it >= 0 } ?: 0

    private val byteIds = IntArray(BYTES) { idOf("<0x%02X>".format(it)) }

    /** The longest user-defined piece, and the characters one can start with. */
    private val maxUserLength: Int
    private val userStarts = HashSet<Char>()

    init {
        var longest = 0
        for (id in 0 until size) {
            if (types[id] != TYPE_USER_DEFINED) continue
            val length = starts[id + 1] - starts[id]
            if (length == 0) continue
            longest = maxOf(longest, length)
            userStarts += chars[starts[id]]
        }
        maxUserLength = longest
    }

    /** The id of the piece spelled [piece], or -1. */
    fun idOf(piece: String): Int = find(piece, 0, piece.length)

    /** The text of piece [id], `▁` and all. */
    fun piece(id: Int): String = String(chars, starts[id], starts[id + 1] - starts[id])

    /** [text] as piece ids, without `<bos>` or `<eos>`. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normal = text.replace(' ', SPACE)
        val n = normal.length
        // The symbols, as ranges of [normal] in a doubly linked list.
        val start = IntArray(n)
        val length = IntArray(n)
        val prev = IntArray(n)
        val next = IntArray(n)
        val frozen = BooleanArray(n)
        var count = 0
        var at = 0
        while (at < n) {
            var size = userMatch(normal, at)
            if (size > 0) {
                frozen[count] = true
            } else {
                size = if (Character.isHighSurrogate(normal[at]) && at + 1 < n && Character.isLowSurrogate(normal[at + 1])) 2 else 1
            }
            start[count] = at
            length[count] = size
            prev[count] = count - 1
            next[count] = -1
            if (count > 0) next[count - 1] = count
            count++
            at += size
        }

        val agenda = PriorityQueue<Pair>(maxOf(count, 1)) { a, b ->
            when {
                a.score != b.score -> b.score.compareTo(a.score)
                else -> a.left.compareTo(b.left)
            }
        }

        fun maybeAdd(left: Int, right: Int) {
            if (left < 0 || right < 0 || frozen[left] || frozen[right]) return
            val size = length[left] + length[right]
            val id = find(normal, start[left], size)
            if (id < 0 || !mergeable(types[id])) return
            agenda.add(Pair(left, right, size, scores[id]))
        }

        for (i in 0 until count - 1) maybeAdd(i, i + 1)
        while (agenda.isNotEmpty()) {
            val top = agenda.poll() ?: break
            // Stale: one side was merged into something else since.
            if (length[top.left] == 0 || length[top.right] == 0 || length[top.left] + length[top.right] != top.size) continue
            length[top.left] += length[top.right]
            next[top.left] = next[top.right]
            if (next[top.right] >= 0) prev[next[top.right]] = top.left
            length[top.right] = 0
            maybeAdd(prev[top.left], top.left)
            maybeAdd(top.left, next[top.left])
        }

        val out = IntArrayList(count)
        var symbol = if (count > 0) 0 else -1
        while (symbol >= 0) {
            val id = find(normal, start[symbol], length[symbol])
            if (id >= 0) {
                out.add(id)
            } else {
                for (byte in normal.substring(start[symbol], start[symbol] + length[symbol]).toByteArray(Charsets.UTF_8)) {
                    val byteId = byteIds[byte.toInt() and 0xFF]
                    out.add(if (byteId >= 0) byteId else unkId)
                }
            }
            symbol = next[symbol]
        }
        return out.toArray()
    }

    /** The length of the longest user-defined piece at [at], or 0. */
    private fun userMatch(text: String, at: Int): Int {
        if (maxUserLength == 0 || text[at] !in userStarts) return 0
        var size = minOf(maxUserLength, text.length - at)
        while (size > 0) {
            val id = find(text, at, size)
            if (id >= 0 && types[id] == TYPE_USER_DEFINED) return size
            size--
        }
        return 0
    }

    private fun find(text: CharSequence, from: Int, size: Int): Int {
        val mask = table.size - 1
        var slot = hash(text, from, size) and mask
        while (true) {
            val id = table[slot]
            if (id < 0) return -1
            if (equalsPiece(id, text, from, size)) return id
            slot = (slot + 1) and mask
        }
    }

    private fun equalsPiece(id: Int, text: CharSequence, from: Int, size: Int): Boolean {
        val begin = starts[id]
        if (starts[id + 1] - begin != size) return false
        for (k in 0 until size) if (chars[begin + k] != text[from + k]) return false
        return true
    }

    private class Pair(val left: Int, val right: Int, val size: Int, val score: Float)

    private class IntArrayList(capacity: Int) {
        private var data = IntArray(maxOf(capacity, 4))
        private var size = 0
        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }
        fun toArray(): IntArray = data.copyOf(size)
    }

    companion object {
        private const val SPACE = '▁'
        private const val BYTES = 256

        // SentencePiece's ModelProto.SentencePiece.Type.
        const val TYPE_NORMAL: Byte = 1
        const val TYPE_UNKNOWN: Byte = 2
        const val TYPE_CONTROL: Byte = 3
        const val TYPE_USER_DEFINED: Byte = 4
        const val TYPE_UNUSED: Byte = 5
        const val TYPE_BYTE: Byte = 6

        /** What SentencePiece keeps in `pieces_`, the only pieces a merge can make. */
        private fun mergeable(type: Byte) =
            type == TYPE_NORMAL || type == TYPE_USER_DEFINED || type == TYPE_UNUSED

        private fun hash(text: CharSequence, from: Int, size: Int): Int {
            var h = -0x7ee3623b // FNV-1a offset basis
            for (k in from until from + size) {
                h = (h xor text[k].code) * 0x01000193
            }
            return h xor (h ushr 16)
        }

        fun read(file: File): GemmaTokenizer = read(file.readBytes())

        /**
         * Reads a SentencePiece `ModelProto`. Only its pieces matter here: the
         * normaliser of this model is the identity and its trainer settings
         * are what this class hard-codes.
         */
        fun read(bytes: ByteArray): GemmaTokenizer {
            val pieces = ArrayList<String>(1 shl 18)
            val scoreList = ArrayList<Float>(1 shl 18)
            val typeList = ArrayList<Byte>(1 shl 18)
            val reader = ProtoReader(bytes, 0, bytes.size)
            while (reader.more()) {
                val key = reader.varint().toInt()
                if (key ushr 3 == 1 && key and 7 == 2) {
                    val size = reader.length()
                    val end = reader.at + size
                    val inner = ProtoReader(bytes, reader.at, end)
                    var piece = ""
                    var score = 0f
                    var type = TYPE_NORMAL
                    while (inner.more()) {
                        val field = inner.varint().toInt()
                        when {
                            field ushr 3 == 1 && field and 7 == 2 -> piece = inner.string()
                            field ushr 3 == 2 && field and 7 == 5 -> score = Float.fromBits(inner.fixed32())
                            field ushr 3 == 3 && field and 7 == 0 -> type = inner.varint().toInt().toByte()
                            else -> inner.skip(field and 7)
                        }
                    }
                    reader.at = end
                    pieces += piece
                    scoreList += score
                    typeList += type
                } else {
                    reader.skip(key and 7)
                }
            }
            require(pieces.isNotEmpty()) { "no pieces in the tokenizer model" }
            val total = pieces.sumOf { it.length }
            val chars = CharArray(total)
            val starts = IntArray(pieces.size + 1)
            var at = 0
            for ((id, piece) in pieces.withIndex()) {
                starts[id] = at
                piece.toCharArray(chars, at)
                at += piece.length
            }
            starts[pieces.size] = at
            var capacity = 1
            while (capacity < pieces.size * 2) capacity = capacity shl 1
            val table = IntArray(capacity) { -1 }
            val mask = capacity - 1
            val text = String(chars)
            for (id in pieces.indices) {
                var slot = hash(text, starts[id], starts[id + 1] - starts[id]) and mask
                // A duplicate keeps its first id, as SentencePiece's map does.
                var duplicate = false
                while (table[slot] >= 0) {
                    val other = table[slot]
                    if (starts[other + 1] - starts[other] == starts[id + 1] - starts[id] &&
                        text.regionMatches(starts[other], text, starts[id], starts[id + 1] - starts[id])
                    ) {
                        duplicate = true
                        break
                    }
                    slot = (slot + 1) and mask
                }
                if (!duplicate) table[slot] = id
            }
            return GemmaTokenizer(
                chars,
                starts,
                scoreList.toFloatArray(),
                typeList.toByteArray(),
                table,
            )
        }
    }

    /** Just enough protobuf wire format for a SentencePiece model. */
    private class ProtoReader(private val bytes: ByteArray, var at: Int, private val end: Int) {
        fun more() = at < end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = bytes[at++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun length(): Int = varint().toInt()

        fun fixed32(): Int {
            val v = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return v
        }

        fun string(): String {
            val size = length()
            val s = String(bytes, at, size, Charsets.UTF_8)
            at += size
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> at += 8
                2 -> {
                    // Read first: `at += length()` would add to the old `at`.
                    val size = length()
                    at += size
                }
                5 -> at += 4
                else -> throw IllegalArgumentException("unsupported wire type $wireType")
            }
        }
    }
}
