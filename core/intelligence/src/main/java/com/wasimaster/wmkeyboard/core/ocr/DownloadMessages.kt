package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.intelligence.R

/**
 * What a model download's screen says for [this] failure, as the string and
 * its one argument ("" for none). Every store in this module words its
 * download failures the same way: the scanner's packs came first, hence the
 * string names.
 */
internal fun DownloadException.screenText(): Pair<Int, String> = when (this) {
    is DownloadException.Http -> R.string.core_intel_ocr_download_error_http to status.toString()
    is DownloadException.Interrupted -> R.string.core_intel_ocr_download_error_interrupted to ""
    is DownloadException.Blocked -> CommonR.string.common_error_download_held to ""
    is DownloadException.WrongSize, is DownloadException.Mismatch -> R.string.core_intel_ocr_download_error_mismatch to ""
}
