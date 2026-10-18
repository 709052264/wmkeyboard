package com.wasimaster.wmkeyboard.core.ocr

import java.io.File

/**
 * PaddleOCR's PP-OCRv5 mobile models, the scanner's reader for Chinese and
 * Japanese wherever ML Kit's recognizers for them are missing (#757):
 * sideload builds, and Play builds on phones without Play services.
 *
 * One recognizer covers Simplified and Traditional Chinese, Japanese kana and
 * kanji, and English, so the whole set is a single pack, [PACK], in
 * [OcrPacks]: a text detector, a line recognizer and the recognizer's
 * character list. None of it is in the APK; it runs on the LiteRT
 * interpreter Whisper already brings (see [PaddleRuntime]).
 *
 * The graphs are the LiteRT conversions published with iFleey's
 * PPOCRv5-Android (Apache-2.0, as are PaddlePaddle's weights), pinned to one
 * commit and checked against their SHA-256, because a graph that loads but is
 * not the expected one would read garbage rather than fail. Their inputs are
 * fixed: a 640 pixel square for the detector and a 48 by 320 strip for the
 * recognizer, which [PaddleOcrPipeline] fits every photo and line to.
 */
object PaddleOcrModel {

    /** The pack name, in [OcrPacks] and [OcrLanguages] alongside Tesseract's. */
    const val PACK = "ppocrv5"

    /** The PPOCRv5-Android commit the downloads are pinned to. */
    const val SOURCE_COMMIT = "dd9d9478a7c9d33bb3cee11c406a8d7316d787dd"

    /**
     * The three files, under the names they are published with, so a copy
     * fetched by hand imports as it is (see [partOf]).
     */
    enum class Part(val fileName: String, val bytes: Long, val sha256: String) {
        DETECTOR("ocr_det_fp16.tflite", 2_444_316L, "f02bb7693e98dacd08075ac911dbdfdb0184b5f582cabb0c49ca41c5120fe189"),
        RECOGNIZER("ocr_rec_fp16.tflite", 8_324_208L, "559b9f822a19887bbf18a9607619406710c0aeec90577117fb620d5cf6e0a55d"),
        CHARACTERS("keys_v5.txt", 74_012L, "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b"),
    }

    /** All three files together, for progress and the storage check. */
    val totalBytes: Long = Part.entries.sumOf { it.bytes }

    fun url(part: Part): String =
        "https://raw.githubusercontent.com/iFleey/PPOCRv5-Android/$SOURCE_COMMIT/app/src/main/assets/models/${part.fileName}"

    fun dir(filesDir: File): File = File(filesDir, "ocr/paddle")

    fun file(filesDir: File, part: Part): File = File(dir(filesDir), part.fileName)

    /** Whole only with all three files: each lands complete or not at all. */
    fun isDownloaded(filesDir: File): Boolean = Part.entries.all { file(filesDir, it).isFile }

    /** The part a file named [name] is, or null for any other file. */
    fun partOf(name: String): Part? = Part.entries.firstOrNull { it.fileName == name }
}
