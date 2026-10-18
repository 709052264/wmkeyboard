package com.wasimaster.wmkeyboard.ime.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ZoomOutMap
import com.wasimaster.wmkeyboard.core.media.gallery.edit.BrushKind
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropHandle
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropMath
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropRect
import com.wasimaster.wmkeyboard.core.media.gallery.edit.NPoint
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEditRenderer
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.EditorTool
import com.wasimaster.wmkeyboard.ime.gallery.EditorUi
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.core.media.gallery.edit.Stroke as EditStroke
import java.util.Locale
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The picture under edit and every gesture on it. [turned] is the size of
 * the turned picture the edit's fractions are of; [showWhole] draws all of
 * it (the crop tool, the before view) rather than only the cropped window.
 */
@Composable
internal fun EditorCanvas(
    bitmap: Bitmap,
    turned: IntSize,
    edit: PhotoEdit,
    editor: EditorUi,
    dragging: Long?,
    onDragging: (Long?) -> Unit,
    stickers: PreviewStickers,
    showWhole: Boolean,
    sizingBrush: Boolean,
    act: (GalleryAction) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val live = remember { mutableStateListOf<NPoint>() }
    var cropping by remember { mutableStateOf(false) }
    var overTrash by remember { mutableStateOf(false) }
    val latestEdit by rememberUpdatedState(edit)
    val latestEditor by rememberUpdatedState(editor)

    val region = if (showWhole) CropRect.FULL else edit.crop
    val pad = with(density) { 16.dp.toPx() }
    val srcW = bitmap.width * region.width
    val srcH = bitmap.height * region.height
    val fit = if (box.width == 0 || srcW <= 0f || srcH <= 0f) {
        1f
    } else {
        min((box.width - 2 * pad) / srcW, (box.height - 2 * pad) / srcH)
    }

    // Two fingers zoom and pan the view, in every tool: a view change only,
    // never part of the edit. A new window onto the picture (the crop tool
    // opened or left, a crop applied) starts back at the fit; a hold for the
    // original does not.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val cropTool = editor.tool == EditorTool.CROP
    LaunchedEffect(cropTool, if (cropTool) CropRect.FULL else edit.crop) {
        zoom = 1f
        pan = Offset.Zero
    }
    val dw = srcW * fit * zoom
    val dh = srcH * fit * zoom
    val dst = Rect(Offset((box.width - dw) / 2f + pan.x, (box.height - dh) / 2f + pan.y), Size(dw, dh))
    val latestDst by rememberUpdatedState(dst)
    val latestRegion by rememberUpdatedState(region)
    val tw = turned.width.coerceAtLeast(1)
    val th = turned.height.coerceAtLeast(1)
    val short = min(tw, th).toFloat()
    // Screen pixels per pixel of the turned picture.
    val perPx = if (srcW > 0f) dw / (tw * region.width) else 1f

    fun toNorm(p: Offset): NPoint {
        val d = latestDst
        val r = latestRegion
        return NPoint(
            r.left + (p.x - d.left) / d.width.coerceAtLeast(1f) * r.width,
            r.top + (p.y - d.top) / d.height.coerceAtLeast(1f) * r.height,
        )
    }

    fun toScreen(n: NPoint): Offset {
        val d = latestDst
        val r = latestRegion
        return Offset(d.left + (n.x - r.left) / r.width * d.width, d.top + (n.y - r.top) / r.height * d.height)
    }

    /** A finger is on a text or sticker: a second finger then scales that, not the view. */
    var layerHeld by remember { mutableStateOf(false) }

    // Read from the gesture's coroutine, which outlives the composition it began in.
    val fitW by rememberUpdatedState(srcW * fit)
    val fitH by rememberUpdatedState(srcH * fit)

    /** Keeps the picture from being panned off: at most to its own edge, and centred while it fits. */
    fun clampPan(p: Offset, z: Float): Offset {
        val w = fitW * z
        val h = fitH * z
        val maxX = max(0f, (w - (box.width - 2 * pad)) / 2f)
        val maxY = max(0f, (h - (box.height - 2 * pad)) / 2f)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    val zoomGesture = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                // The first pass, ahead of the tool's own handler: once two
                // fingers are down they are the view's, and the tool sees its
                // stroke or drag cancelled.
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.changes.count { it.pressed } < 2) continue
                if (layerHeld && latestEditor.tool.let { it == EditorTool.TEXT || it == EditorTool.STICKER }) continue
                val change = e.calculateZoom()
                val move = e.calculatePan()
                val centroid = e.calculateCentroid(useCurrent = false)
                val next = (zoom * change).coerceIn(1f, MAX_EDITOR_ZOOM)
                val k = next / zoom
                // Zoomed about the fingers rather than the middle.
                val mid = Offset(box.width / 2f, box.height / 2f)
                val about = if (centroid.isUnspecified) mid else centroid
                val offset = (pan + mid - about) * k - (mid - about) + move
                zoom = next
                pan = clampPan(offset, next)
                e.changes.forEach { it.consume() }
            } while (e.changes.any { it.pressed })
        }
    }

    val trashRadius = with(density) { 30.dp.toPx() }
    fun trashCenter() = Offset(box.width / 2f, box.height - with(density) { 48.dp.toPx() })

    /** The text or sticker under [p], topmost first, tested in its own turned frame. */
    fun layerAt(p: Offset): Long? {
        val e = latestEdit
        val slack = with(density) { 12.dp.toPx() }
        fun inside(boxPx: android.graphics.RectF, rotation: Float): Boolean {
            val c = toScreen(NPoint(boxPx.centerX() / tw, boxPx.centerY() / th))
            val a = Math.toRadians(-rotation.toDouble())
            val dx = p.x - c.x
            val dy = p.y - c.y
            val rx = (dx * cos(a) - dy * sin(a)).toFloat()
            val ry = (dx * sin(a) + dy * cos(a)).toFloat()
            val hw = boxPx.width() / 2f * perPx + slack
            val hh = boxPx.height() / 2f * perPx + slack
            return rx in -hw..hw && ry in -hh..hh
        }
        e.texts.asReversed().firstOrNull { inside(PhotoEditRenderer.textBox(it, tw, th), it.rotation) }?.let { return it.id }
        e.stickers.asReversed().firstOrNull { s ->
            val bmp = stickers.bitmap(s)
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
            inside(PhotoEditRenderer.stickerBox(s, tw, th, aspect), s.rotation)
        }?.let { return it.id }
        return null
    }

    val gestures: Modifier = when (editor.tool) {
        EditorTool.DRAW -> Modifier.pointerInput(tw, th, box, region) {
            val minStep = with(density) { 1.5.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown()
                live.clear()
                live.add(toNorm(down.position))
                var last = down.position
                var cancelled = false
                do {
                    val e = awaitPointerEvent()
                    if (e.changes.count { it.pressed } > 1) {
                        cancelled = true
                        break
                    }
                    e.changes.forEach { c ->
                        if (c.positionChanged()) {
                            if ((c.position - last).getDistance() >= minStep) {
                                live.add(toNorm(c.position))
                                last = c.position
                            }
                            c.consume()
                        }
                    }
                } while (e.changes.any { it.pressed })
                val points = live.toList()
                live.clear()
                if (!cancelled && points.isNotEmpty()) {
                    val ed = latestEditor
                    val stroke = EditStroke(ed.brush, ed.color, ed.brushSize, points)
                    act(GalleryAction.EditCommit(latestEdit.copy(strokes = latestEdit.strokes + stroke)))
                }
            }
        }
        EditorTool.CROP -> Modifier.pointerInput(tw, th, box, region) {
            val grab = with(density) { 28.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = latestEdit
                val c = start.crop
                val frame = Rect(toScreen(NPoint(c.left, c.top)), toScreen(NPoint(c.right, c.bottom)))
                val handle = handleAt(down.position, frame, grab) ?: return@awaitEachGesture
                down.consume()
                cropping = true
                var total = Offset.Zero
                val pictureRatio = tw.toFloat() / th
                val ratio = latestEditor.aspect.ratio(pictureRatio)
                var latest = start
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    // A second finger is a zoom: the frame stays where it got to.
                    if (e.changes.count { it.pressed } > 1) continue
                    total += ch.positionChange()
                    ch.consume()
                    val d = latestDst
                    val next = CropMath.drag(c, handle, total.x / d.width, total.y / d.height, ratio, pictureRatio)
                    latest = start.copy(crop = next)
                    act(GalleryAction.EditPreview(latest))
                }
                cropping = false
                act(GalleryAction.EditCommit(latest))
            }
        }
        EditorTool.TEXT, EditorTool.STICKER -> Modifier.pointerInput(tw, th, box, region) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown()
                val hitId = layerAt(down.position)
                layerHeld = hitId != null
                val start = latestEdit
                if (hitId == null) {
                    // An empty spot: a tap there writes a new text, or lets go of the pick.
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if ((ch.position - down.position).getDistance() > slop) moved = true
                        if (!ch.pressed) break
                    }
                    if (!moved) {
                        val n = toNorm(down.position)
                        if (latestEditor.tool == EditorTool.TEXT && latestEditor.selectedLayer == null) {
                            act(GalleryAction.AddText(n.x, n.y))
                        } else {
                            act(GalleryAction.SelectLayer(null))
                        }
                    }
                    return@awaitEachGesture
                }
                down.consume()
                val wasSelected = latestEditor.selectedLayer == hitId
                act(GalleryAction.SelectLayer(hitId))
                var moved = false
                var current = start
                var pointer = down.position
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.none { it.pressed }) break
                    val pan = e.calculatePan()
                    val zoom = e.calculateZoom()
                    val turn = e.calculateRotation()
                    e.changes.firstOrNull { it.id == down.id }?.let { pointer = it.position }
                    if (!moved && ((pointer - down.position).getDistance() > slop || zoom != 1f || turn != 0f)) {
                        moved = true
                        onDragging(hitId)
                    }
                    if (!moved) continue
                    e.changes.forEach(PointerInputChange::consume)
                    val d = latestDst
                    val r = latestRegion
                    val dx = pan.x / d.width * r.width
                    val dy = pan.y / d.height * r.height
                    current = moveLayer(current, hitId, dx, dy, zoom, turn)
                    val nowOver = (pointer - trashCenter()).getDistance() < trashRadius * 1.6f
                    if (nowOver != overTrash) {
                        overTrash = nowOver
                        if (nowOver) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    act(GalleryAction.EditPreview(current))
                }
                onDragging(null)
                layerHeld = false
                when {
                    !moved -> if (wasSelected && current.texts.any { it.id == hitId }) act(GalleryAction.TypeLayer(hitId))
                    overTrash -> {
                        act(GalleryAction.EditPreview(start))
                        act(GalleryAction.DeleteLayer(hitId))
                    }
                    else -> act(GalleryAction.EditCommit(current))
                }
                overTrash = false
            }
        }
        // Filters and Adjust: a hold anywhere shows the original.
        else -> Modifier.pointerInput(tw, th, box, region) {
            awaitEachGesture {
                awaitFirstDown()
                act(GalleryAction.Compare(true))
                var comparing = true
                do {
                    val e = awaitPointerEvent()
                    // A second finger is a zoom, not a hold.
                    if (comparing && e.changes.count { it.pressed } > 1) {
                        comparing = false
                        act(GalleryAction.Compare(false))
                    }
                } while (e.changes.any { it.pressed })
                if (comparing) act(GalleryAction.Compare(false))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { box = it }
                .then(zoomGesture)
                .then(gestures),
        ) {
            if (box.width == 0) return@Canvas
            drawImage(
                image = image,
                srcOffset = IntOffset((region.left * bitmap.width).toInt(), (region.top * bitmap.height).toInt()),
                srcSize = IntSize(srcW.toInt().coerceAtLeast(1), srcH.toInt().coerceAtLeast(1)),
                dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
                dstSize = IntSize(dst.width.toInt().coerceAtLeast(1), dst.height.toInt().coerceAtLeast(1)),
            )
            if (editor.comparing) return@Canvas
            when (editor.tool) {
                EditorTool.CROP -> drawCrop(edit.crop, ::toScreen, dst, cropping)
                EditorTool.DRAW -> {
                    drawLive(live, editor, ::toScreen, editor.brushSize * short * perPx)
                    if (sizingBrush) drawBrushPreview(editor, dst.center, editor.brushSize * short * perPx)
                }
                EditorTool.TEXT, EditorTool.STICKER -> {
                    // The layer under the finger, live, in the turned picture's own frame.
                    val moving = dragging
                    if (moving != null) {
                        drawIntoCanvas { c ->
                            val nc = c.nativeCanvas
                            nc.save()
                            nc.clipRect(dst.left, dst.top, dst.right, dst.bottom)
                            nc.translate(dst.left, dst.top)
                            nc.scale(perPx, perPx)
                            nc.translate(-region.left * tw, -region.top * th)
                            edit.texts.firstOrNull { it.id == moving }?.let { PhotoEditRenderer.drawText(nc, it, tw, th) }
                            edit.stickers.firstOrNull { it.id == moving }?.let {
                                PhotoEditRenderer.drawSticker(nc, it, tw, th, short, stickers)
                            }
                            nc.restore()
                        }
                    }
                    editor.selectedLayer?.let { id -> drawSelection(id, edit, tw, th, perPx, ::toScreen, stickers) }
                }
                else -> Unit
            }
        }
        if (zoom > 1f + ZOOM_EPSILON && dragging == null) {
            ZoomChip(zoom, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                zoom = 1f
                pan = Offset.Zero
            }
        }
        if (dragging != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .size(60.dp)
                    .scale(if (overTrash) 1.2f else 1f)
                    .clip(CircleShape)
                    .background(if (overTrash) Color(0xFFE53935) else Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Delete, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(26.dp)) }
        }
    }
}

/** How far the zoom shows and a tap on it fits the picture again. */
@Composable
private fun ZoomChip(zoom: Float, modifier: Modifier, onReset: () -> Unit) {
    val label = stringResource(R.string.ime_gallery_editor_fit)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onReset)
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ZoomOutMap, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            String.format(Locale.getDefault(), "%.1f×", zoom),
            color = OnPhoto,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** How far two fingers can bring the picture in, past the fit. */
private const val MAX_EDITOR_ZOOM = 8f

/** Which part of the crop [frame] a finger at [p] takes hold of, or null for outside it. */
private fun handleAt(p: Offset, frame: Rect, grab: Float): CropHandle? {
    val nearL = abs(p.x - frame.left) < grab
    val nearR = abs(p.x - frame.right) < grab
    val nearT = abs(p.y - frame.top) < grab
    val nearB = abs(p.y - frame.bottom) < grab
    val inX = p.x > frame.left - grab && p.x < frame.right + grab
    val inY = p.y > frame.top - grab && p.y < frame.bottom + grab
    return when {
        nearL && nearT -> CropHandle.TOP_LEFT
        nearR && nearT -> CropHandle.TOP_RIGHT
        nearL && nearB -> CropHandle.BOTTOM_LEFT
        nearR && nearB -> CropHandle.BOTTOM_RIGHT
        nearT && inX -> CropHandle.TOP
        nearB && inX -> CropHandle.BOTTOM
        nearL && inY -> CropHandle.LEFT
        nearR && inY -> CropHandle.RIGHT
        frame.contains(p) -> CropHandle.MOVE
        else -> null
    }
}

/** [edit] with layer [id] moved by fractions, scaled and turned. */
private fun moveLayer(edit: PhotoEdit, id: Long, dx: Float, dy: Float, zoom: Float, turn: Float): PhotoEdit {
    fun c(p: NPoint) = NPoint((p.x + dx).coerceIn(-0.2f, 1.2f), (p.y + dy).coerceIn(-0.2f, 1.2f))
    return edit.copy(
        texts = edit.texts.map {
            if (it.id != id) it else it.copy(center = c(it.center), size = (it.size * zoom).coerceIn(0.02f, 0.6f), rotation = it.rotation + turn)
        },
        stickers = edit.stickers.map {
            if (it.id != id) it else it.copy(center = c(it.center), size = (it.size * zoom).coerceIn(0.05f, 2f), rotation = it.rotation + turn)
        },
    )
}

private fun DrawScope.drawCrop(crop: CropRect, toScreen: (NPoint) -> Offset, picture: Rect, adjusting: Boolean) {
    val tl = toScreen(NPoint(crop.left, crop.top))
    val br = toScreen(NPoint(crop.right, crop.bottom))
    val frame = Rect(tl, br)
    val shade = Color.Black.copy(alpha = 0.55f)
    drawRect(shade, Offset(picture.left, picture.top), Size(picture.width, frame.top - picture.top))
    drawRect(shade, Offset(picture.left, frame.bottom), Size(picture.width, picture.bottom - frame.bottom))
    drawRect(shade, Offset(picture.left, frame.top), Size(frame.left - picture.left, frame.height))
    drawRect(shade, Offset(frame.right, frame.top), Size(picture.right - frame.right, frame.height))
    drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(1.5.dp.toPx()))
    if (adjusting) {
        // Thirds while the frame moves, for lining up a horizon or a face.
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3f
            val y = frame.top + frame.height * i / 3f
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, frame.top), Offset(x, frame.bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(frame.left, y), Offset(frame.right, y), 1.dp.toPx())
        }
    }
    val len = 18.dp.toPx()
    val w = 4.dp.toPx()
    listOf(frame.topLeft to Offset(1f, 1f), frame.topRight to Offset(-1f, 1f), frame.bottomLeft to Offset(1f, -1f), frame.bottomRight to Offset(-1f, -1f))
        .forEach { (corner, dir) ->
            drawLine(Color.White, corner, corner + Offset(len * dir.x, 0f), w, StrokeCap.Round)
            drawLine(Color.White, corner, corner + Offset(0f, len * dir.y), w, StrokeCap.Round)
        }
}

/** The stroke under the finger, before it is laid down and drawn by the renderer. */
private fun DrawScope.drawLive(points: List<NPoint>, editor: EditorUi, toScreen: (NPoint) -> Offset, width: Float) {
    if (points.isEmpty()) return
    val path = Path()
    val first = toScreen(points.first())
    path.moveTo(first.x, first.y)
    for (i in 1 until points.size) {
        val p = toScreen(points[i - 1])
        val q = toScreen(points[i])
        path.quadraticTo(p.x, p.y, (p.x + q.x) / 2f, (p.y + q.y) / 2f)
    }
    val last = toScreen(points.last())
    path.lineTo(last.x, last.y)
    val color = Color(editor.color)
    val w = max(1f, width)
    when (editor.brush) {
        BrushKind.MARKER -> drawPath(path, color.copy(alpha = 0.45f), style = Stroke(w * PhotoEditRenderer.MARKER_WIDTH, cap = StrokeCap.Square, join = StrokeJoin.Round))
        BrushKind.NEON -> {
            drawPath(path, color.copy(alpha = 0.35f), style = Stroke(w * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color, style = Stroke(w * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color.White, style = Stroke(w * 0.38f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        BrushKind.ERASER -> drawPath(
            path,
            Color.White.copy(alpha = 0.5f),
            style = Stroke(w * PhotoEditRenderer.ERASER_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
        )
        BrushKind.BLUR, BrushKind.PIXELATE -> drawPath(
            path,
            Color.Gray.copy(alpha = 0.55f),
            style = Stroke(w * PhotoEditRenderer.HIDE_WIDTH, cap = if (editor.brush == BrushKind.PIXELATE) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round),
        )
        BrushKind.PEN, BrushKind.ARROW -> drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The brush at its true size on the picture, while its slider is in hand:
 * a dot of the stroke's own width, in its colour, with a ring around it so
 * it reads on any photo.
 */
private fun DrawScope.drawBrushPreview(editor: EditorUi, center: Offset, width: Float) {
    val scale = when (editor.brush) {
        BrushKind.MARKER -> PhotoEditRenderer.MARKER_WIDTH
        BrushKind.NEON -> 2.4f
        BrushKind.ERASER -> PhotoEditRenderer.ERASER_WIDTH
        BrushKind.BLUR, BrushKind.PIXELATE -> PhotoEditRenderer.HIDE_WIDTH
        BrushKind.PEN, BrushKind.ARROW -> 1f
    }
    val r = max(1f, width) * scale / 2f
    val fill = when (editor.brush) {
        BrushKind.ERASER -> Color.White.copy(alpha = 0.35f)
        BrushKind.BLUR, BrushKind.PIXELATE -> Color.Gray.copy(alpha = 0.55f)
        BrushKind.MARKER -> Color(editor.color).copy(alpha = 0.45f)
        else -> Color(editor.color)
    }
    drawCircle(fill, r, center)
    drawCircle(Color.Black.copy(alpha = 0.45f), r + 1.5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
    drawCircle(Color.White, r + 0.5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
}

/** A dashed frame around the picked text or sticker, turned with it. */
private fun DrawScope.drawSelection(
    id: Long,
    edit: PhotoEdit,
    tw: Int,
    th: Int,
    perPx: Float,
    toScreen: (NPoint) -> Offset,
    stickers: PreviewStickers,
) {
    val text = edit.texts.firstOrNull { it.id == id }
    val sticker = edit.stickers.firstOrNull { it.id == id }
    val (rect, rotation) = when {
        text != null -> PhotoEditRenderer.textBox(text, tw, th) to text.rotation
        sticker != null -> {
            val bmp = stickers.bitmap(sticker)
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
            PhotoEditRenderer.stickerBox(sticker, tw, th, aspect) to sticker.rotation
        }
        else -> return
    }
    val c = toScreen(NPoint(rect.centerX() / tw, rect.centerY() / th))
    val w = rect.width() * perPx + 12.dp.toPx()
    val h = rect.height() * perPx + 12.dp.toPx()
    rotate(rotation, pivot = c) {
        drawRoundRect(
            Color.White,
            topLeft = Offset(c.x - w / 2f, c.y - h / 2f),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        )
    }
}
