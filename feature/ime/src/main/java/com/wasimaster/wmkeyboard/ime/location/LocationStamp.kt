package com.wasimaster.wmkeyboard.ime.location

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.wasimaster.wmkeyboard.core.location.LocationFormatter
import com.wasimaster.wmkeyboard.core.location.LocationShare
import com.wasimaster.wmkeyboard.core.location.PlusCode
import kotlin.math.min

/**
 * The band the camera burns into a photo when the location stamp is on
 * (#350): the place on one line, the coordinates, the time and the Plus code
 * under it, white on a dark fade along the bottom edge.
 *
 * In the pixels rather than in EXIF, because every chat app strips EXIF and
 * none of them shows it; a stamp survives any app, a screenshot and a
 * forward.
 */
internal object LocationStamp {

    /** The two lines for [share]: the place, then the numbers. */
    fun lines(share: LocationShare): Pair<String, String> {
        val point = share.precision.apply(share.point)
        val coords = LocationFormatter.coordinates(point, share.precision)
        val title = listOfNotNull(share.placeName, share.address).distinct().joinToString(", ").ifEmpty { coords }
        val detail = listOfNotNull(
            coords.takeIf { it != title },
            share.timeText.ifEmpty { null },
            PlusCode.encode(point, share.precision.plusCodeLength),
        ).joinToString("  ·  ")
        return title to detail
    }

    /** [source] with the band drawn on; [source] is recycled when a copy had to be made. */
    fun draw(source: Bitmap, title: String, detail: String): Bitmap {
        val out = if (source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true).also { if (it != null && it != source) source.recycle() }
                ?: return source
        }
        val canvas = Canvas(out)
        val width = out.width.toFloat()
        val height = out.height.toFloat()
        val base = min(width, height)
        val titleSize = base / 22f
        val detailSize = base / 34f
        val pad = base / 32f
        val band = pad * 2.4f + titleSize * 1.2f + detailSize * 1.4f

        val fade = Paint().apply {
            shader = LinearGradient(
                0f, height - band * 1.5f, 0f, height,
                0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, height - band * 1.5f, width, height, fade)

        val shadow = base / 220f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(shadow, 0f, shadow / 2, 0x99000000.toInt())
        }
        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textSize = detailSize
            setShadowLayer(shadow, 0f, shadow / 2, 0x99000000.toInt())
        }
        val room = width - pad * 2
        val titleText = TextUtils.ellipsize("📍 $title", titlePaint, room, TextUtils.TruncateAt.END).toString()
        val detailText = TextUtils.ellipsize(detail, detailPaint, room, TextUtils.TruncateAt.END).toString()
        val detailBaseline = height - pad
        val titleBaseline = detailBaseline - detailSize * 1.4f
        canvas.drawText(titleText, pad, titleBaseline, titlePaint)
        canvas.drawText(detailText, pad, detailBaseline, detailPaint)
        return out
    }
}
