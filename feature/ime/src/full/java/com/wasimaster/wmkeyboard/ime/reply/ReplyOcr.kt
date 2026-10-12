package com.wasimaster.wmkeyboard.ime.reply

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.tools.ReplyTurn
import kotlinx.coroutines.tasks.await

/**
 * Reads a chat screenshot into messages, for a model that cannot see (#234).
 *
 * The provider the user has chosen may take pictures, in which case it reads
 * the screenshot itself and this is never called. When it cannot — an
 * on-device model, Brave, DeepSeek, or a user who asked for it to stay on the
 * device — the words still have to get there somehow, and this is that route.
 *
 * ## Who said what
 *
 * OCR gives back lines and boxes, and the thing that actually says who wrote a
 * message is which side of the screen its bubble sits on. So the side is the
 * guess: a line whose box sits past [MINE_FROM] of the width is the user's own.
 * It is only a guess — a centred system notice, a wide bubble, a tablet layout
 * all defeat it — so the panel draws the label on every line and a tap flips
 * it. Being able to fix it is the feature; being right most of the time is the
 * convenience.
 */
object ReplyOcr {

    /** The full build has ML Kit's Latin recognizer bundled. */
    val available: Boolean get() = BuildConfig.ENABLE_ML_KIT_SCANNERS

    /**
     * Where a line has to start before it reads as the user's own message.
     *
     * Past the middle rather than at it: a long message from the other person
     * wraps across most of the width, and its box would otherwise straddle the
     * centre and flip sides between screenshots.
     */
    private const val MINE_FROM = 0.52f

    /** Lines shorter than this are timestamps, read receipts and reactions. */
    private const val MIN_CHARS = 2

    /**
     * [bitmap] read as a conversation, oldest first.
     *
     * Returns an empty list when nothing legible was found, which the caller
     * reports as "no text found" rather than as a failure — a photo of a sunset
     * is not an error, it is just not a conversation.
     */
    suspend fun read(bitmap: Bitmap): List<ReplyTurn> {
        if (!available) return emptyList()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val width = bitmap.width.takeIf { it > 0 } ?: return emptyList()
            result.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line ->
                    val text = line.text.trim()
                    if (text.length < MIN_CHARS) return@mapNotNull null
                    val box = line.boundingBox
                    val mine = box != null && box.left.toFloat() / width >= MINE_FROM
                    // Sorted by where it sits down the page, because ML Kit
                    // returns blocks in its own reading order and a chat is
                    // only a conversation in the order it happened.
                    Triple(box?.top ?: 0, mine, text)
                }
                .sortedBy { it.first }
                .map { ReplyTurn(fromMe = it.second, text = it.third) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { recognizer.close() }
        }
    }
}
