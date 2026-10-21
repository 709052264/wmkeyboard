package com.wasimaster.wmkeyboard.core.download

import java.io.File

/**
 * How a file is cut into pieces for [FileDownloader], and how far each piece
 * has come. Saved as the `.ranges` file beside the `.part`:
 *
 * ```
 * wmdl 1 <total>
 * <start> <end> <next>
 * ```
 *
 * one line per piece, [Piece.end] exclusive, [Piece.next] the first byte not
 * yet on disk. A file that does not parse is no plan, and the download starts
 * over: it costs one restart, never a wrong file, since the hash still decides.
 */
internal class RangePlan(val total: Long, val pieces: List<Piece>) {

    class Piece(val start: Long, val end: Long, next: Long) {
        @Volatile
        var next: Long = next
            private set

        val last: Long get() = end - 1
        val complete: Boolean get() = next >= end

        /** Only the worker that holds the piece calls this. */
        fun advance(bytes: Long) {
            next += bytes
        }
    }

    val done: Long get() = pieces.sumOf { it.next - it.start }

    /** A copy of where every piece is now, for a writer that must not see later progress. */
    fun snapshot(): RangePlan = RangePlan(total, pieces.map { Piece(it.start, it.end, it.next) })
    val complete: Boolean get() = pieces.all { it.complete }

    /** Writes the plan through a temporary file, so a reader never sees half of it. */
    fun write(file: File) {
        val text = buildString {
            append(MAGIC).append(' ').append(VERSION).append(' ').append(total).append('\n')
            for (piece in pieces) {
                append(piece.start).append(' ').append(piece.end).append(' ').append(piece.next).append('\n')
            }
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private const val MAGIC = "wmdl"
        private const val VERSION = 1

        fun read(file: File): RangePlan? {
            val lines = runCatching { file.readLines() }.getOrNull() ?: return null
            val head = lines.firstOrNull()?.split(' ') ?: return null
            if (head.size != 3 || head[0] != MAGIC || head[1] != VERSION.toString()) return null
            val total = head[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val pieces = lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val f = line.split(' ').mapNotNull { it.toLongOrNull() }
                if (f.size != 3) return null
                val (start, end, next) = f
                if (start < 0 || end <= start || end > total || next < start || next > end) return null
                Piece(start, end, next)
            }
            // The pieces must tile the file exactly, or bytes would go unfetched.
            var at = 0L
            for (piece in pieces.sortedBy { it.start }) {
                if (piece.start != at) return null
                at = piece.end
            }
            if (at != total) return null
            return RangePlan(total, pieces.sortedBy { it.start })
        }

        /**
         * [total] bytes whose first [prefix] are already on disk, the rest in
         * pieces of [pieceSize], the first of them [firstLength] long.
         */
        fun split(total: Long, prefix: Long, firstLength: Long, pieceSize: Long): RangePlan {
            val pieces = ArrayList<Piece>()
            val have = prefix.coerceIn(0, total)
            if (have > 0) pieces += Piece(0, have, have)
            val firstEnd = minOf(total, have + firstLength)
            if (firstEnd > have) pieces += Piece(have, firstEnd, have)
            pieces += cut(firstEnd, total, pieceSize)
            return RangePlan(total, pieces)
        }

        private fun cut(from: Long, to: Long, pieceSize: Long): List<Piece> {
            val pieces = ArrayList<Piece>()
            var start = from
            while (start < to) {
                val end = minOf(to, start + pieceSize)
                pieces += Piece(start, end, start)
                start = end
            }
            return pieces
        }
    }
}
