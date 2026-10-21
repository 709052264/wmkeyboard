package com.wasimaster.wmkeyboard.ime.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.FormatAlignLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AlignHorizontalCenter
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AlignHorizontalRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowOutward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixNormal
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BlurOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbTwilight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Brush
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Contrast
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Crop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Draw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FilterBAndW
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Grain
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GridOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Highlight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.InvertColors
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LightMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Opacity
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectLarge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Rotate90DegreesCw
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Thermostat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Texture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CenterFocusStrong
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.media.gallery.edit.AdjustKnob
import com.wasimaster.wmkeyboard.core.media.gallery.edit.BrushKind
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropAspect
import com.wasimaster.wmkeyboard.core.media.gallery.edit.CropMath
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEditRenderer
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoFilter
import com.wasimaster.wmkeyboard.core.media.gallery.edit.TextAlign
import com.wasimaster.wmkeyboard.core.media.gallery.edit.TextStyle
import com.wasimaster.wmkeyboard.core.upscale.UpscaleModels
import com.wasimaster.wmkeyboard.core.upscale.UpscaleTiles
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.gallery.EditorTool
import com.wasimaster.wmkeyboard.ime.gallery.EditorUi
import com.wasimaster.wmkeyboard.ime.gallery.GalleryAction
import com.wasimaster.wmkeyboard.ime.gallery.GalleryCallbacks
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The rail of the editor's tools. */
@Composable
internal fun ToolRail(tool: EditorTool, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        EditorTool.entries.forEach { t ->
            val on = t == tool
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { act(GalleryAction.Tool(t)) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(toolIcon(t), contentDescription = null, tint = if (on) kb.accent else OnPhoto, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(toolLabel(t)),
                    color = if (on) kb.accent else OnPhoto.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun toolIcon(t: EditorTool): ImageVector = when (t) {
    EditorTool.CROP -> Icons.Outlined.Crop
    EditorTool.DRAW -> Icons.Outlined.Draw
    EditorTool.TEXT -> Icons.Outlined.TextFields
    EditorTool.STICKER -> Icons.Outlined.EmojiEmotions
    EditorTool.FILTER -> Icons.Outlined.FilterBAndW
    EditorTool.ADJUST -> Icons.Outlined.Tune
    EditorTool.UPSCALE -> Icons.Outlined.PhotoSizeSelectLarge
}

private fun toolLabel(t: EditorTool): Int = when (t) {
    EditorTool.CROP -> R.string.ime_gallery_tool_crop
    EditorTool.DRAW -> R.string.ime_gallery_tool_draw
    EditorTool.TEXT -> R.string.ime_gallery_tool_text
    EditorTool.STICKER -> R.string.ime_gallery_tool_stickers
    EditorTool.FILTER -> R.string.ime_gallery_tool_filters
    EditorTool.ADJUST -> R.string.ime_gallery_tool_adjust
    EditorTool.UPSCALE -> R.string.ime_gallery_tool_upscale
}

/** The open tool's own controls, over the rail. */
@Composable
internal fun EditorControls(
    editor: EditorUi,
    edit: PhotoEdit,
    pictureRatio: Float,
    source: Bitmap?,
    /** The photo's own width and height, before any upscale: what the Upscale tool offers factors for. */
    photoSize: Pair<Int, Int>,
    callbacks: GalleryCallbacks,
    onBrushSizing: (held: Boolean) -> Unit,
) {
    val act = callbacks.onAction
    Column(Modifier.fillMaxWidth().height(CONTROLS_HEIGHT), verticalArrangement = Arrangement.Center) {
        when (editor.tool) {
            EditorTool.CROP -> CropControls(editor, edit, pictureRatio, act)
            EditorTool.DRAW -> DrawControls(editor, act, onBrushSizing)
            EditorTool.TEXT -> TextControls(editor, edit, act)
            EditorTool.STICKER -> StickerControls(callbacks)
            EditorTool.FILTER -> FilterControls(edit, source, act)
            EditorTool.ADJUST -> AdjustControls(editor, edit, act)
            EditorTool.UPSCALE -> UpscaleControls(editor, edit, photoSize, act)
        }
    }
}

private val CONTROLS_HEIGHT = 112.dp

@Composable
private fun DarkChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) kb.accent else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) onAccent(kb.accent) else OnPhoto
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(5.dp))
        }
        if (label.isNotEmpty()) Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

// ---- Crop -----------------------------------------------------------------

@Composable
private fun CropControls(editor: EditorUi, edit: PhotoEdit, pictureRatio: Float, act: (GalleryAction) -> Unit) {
    ChipRow {
        CropAspect.entries.forEach { a ->
            DarkChip(stringResource(aspectLabel(a)), selected = editor.aspect == a) {
                act(GalleryAction.Aspect(a))
                val ratio = a.ratio(pictureRatio)
                if (ratio != null) act(GalleryAction.EditCommit(edit.copy(aspect = a, crop = CropMath.fit(ratio, pictureRatio, edit.crop))))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoButton(Icons.Outlined.Rotate90DegreesCw, stringResource(R.string.ime_gallery_crop_rotate), size = 36.dp) {
            act(GalleryAction.EditCommit(edit.turned()))
        }
        Spacer(Modifier.width(6.dp))
        PhotoButton(Icons.Outlined.Flip, stringResource(R.string.ime_gallery_crop_mirror), size = 36.dp, active = edit.mirrored) {
            act(GalleryAction.EditCommit(edit.mirroredOnce()))
        }
        Spacer(Modifier.width(10.dp))
        GallerySlider(
            value = edit.straighten,
            range = -PhotoEdit.MAX_STRAIGHTEN..PhotoEdit.MAX_STRAIGHTEN,
            twoWay = true,
            description = stringResource(R.string.ime_gallery_crop_straighten),
            modifier = Modifier.weight(1f),
            onChange = { act(GalleryAction.EditPreview(edit.copy(straighten = it))) },
            onDone = { act(GalleryAction.EditCommit(edit.copy(straighten = it))) },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.ime_gallery_degrees, edit.straighten.roundToInt()),
            color = OnPhoto,
            fontSize = 12.sp,
            modifier = Modifier.width(40.dp),
        )
    }
}

private fun aspectLabel(a: CropAspect): Int = when (a) {
    CropAspect.FREE -> R.string.ime_gallery_aspect_free
    CropAspect.ORIGINAL -> R.string.ime_gallery_aspect_original
    CropAspect.SQUARE -> R.string.ime_gallery_aspect_square
    CropAspect.R4_3 -> R.string.ime_gallery_aspect_4_3
    CropAspect.R3_4 -> R.string.ime_gallery_aspect_3_4
    CropAspect.R16_9 -> R.string.ime_gallery_aspect_16_9
    CropAspect.R9_16 -> R.string.ime_gallery_aspect_9_16
    CropAspect.R3_2 -> R.string.ime_gallery_aspect_3_2
    CropAspect.R2_3 -> R.string.ime_gallery_aspect_2_3
}

// ---- Draw -------------------------------------------------------------------

@Composable
private fun DrawControls(editor: EditorUi, act: (GalleryAction) -> Unit, onSizing: (held: Boolean) -> Unit) {
    ChipRow {
        BrushKind.entries.forEach { b ->
            DarkChip(stringResource(brushLabel(b)), selected = editor.brush == b, icon = brushIcon(b)) { act(GalleryAction.Brush(b)) }
        }
    }
    val colourless = editor.brush == BrushKind.ERASER || editor.brush == BrushKind.BLUR || editor.brush == BrushKind.PIXELATE
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!colourless) {
            ColorRow(EditorUi.PALETTE, editor.color, Modifier.weight(1f)) { act(GalleryAction.BrushColor(it)) }
        } else {
            Spacer(Modifier.weight(1f))
        }
        GallerySlider(
            value = editor.brushSize,
            range = MIN_BRUSH..MAX_BRUSH,
            description = stringResource(R.string.ime_gallery_brush_size),
            modifier = Modifier.width(110.dp).padding(end = 10.dp),
            onChange = {
                onSizing(true)
                act(GalleryAction.BrushSize(it))
            },
            onDone = {
                onSizing(false)
                act(GalleryAction.BrushSize(it))
            },
        )
    }
}

private const val MIN_BRUSH = 0.004f
private const val MAX_BRUSH = 0.05f

private fun brushIcon(b: BrushKind): ImageVector = when (b) {
    BrushKind.PEN -> Icons.Outlined.Brush
    BrushKind.MARKER -> Icons.Outlined.Highlight
    BrushKind.NEON -> Icons.Outlined.AutoAwesome
    BrushKind.ARROW -> Icons.Outlined.ArrowOutward
    BrushKind.ERASER -> Icons.Outlined.AutoFixNormal
    BrushKind.BLUR -> Icons.Outlined.BlurOn
    BrushKind.PIXELATE -> Icons.Outlined.GridOn
}

private fun brushLabel(b: BrushKind): Int = when (b) {
    BrushKind.PEN -> R.string.ime_gallery_brush_pen
    BrushKind.MARKER -> R.string.ime_gallery_brush_marker
    BrushKind.NEON -> R.string.ime_gallery_brush_neon
    BrushKind.ARROW -> R.string.ime_gallery_brush_arrow
    BrushKind.ERASER -> R.string.ime_gallery_brush_eraser
    BrushKind.BLUR -> R.string.ime_gallery_brush_blur
    BrushKind.PIXELATE -> R.string.ime_gallery_brush_pixelate
}

// ---- Text -------------------------------------------------------------------

@Composable
private fun TextControls(editor: EditorUi, edit: PhotoEdit, act: (GalleryAction) -> Unit) {
    val selected = editor.selectedLayer?.takeIf { id -> edit.texts.any { it.id == id } }
    ChipRow {
        DarkChip(stringResource(R.string.ime_gallery_text_add), selected = false, icon = Icons.Outlined.Add) { act(GalleryAction.AddText()) }
        TextStyle.entries.forEach { s ->
            DarkChip(stringResource(textStyleLabel(s)), selected = editor.textStyle == s) { act(GalleryAction.LayerStyle(s)) }
        }
        val nextAlign = TextAlign.entries[(editor.textAlign.ordinal + 1) % TextAlign.entries.size]
        DarkChip("", selected = false, icon = alignIcon(editor.textAlign)) { act(GalleryAction.LayerAlign(nextAlign)) }
        if (selected != null) {
            DarkChip(stringResource(R.string.ime_gallery_text_edit), selected = false, icon = Icons.Outlined.Edit) { act(GalleryAction.TypeLayer(selected)) }
            DarkChip(stringResource(R.string.ime_gallery_layer_delete), selected = false, icon = Icons.Outlined.Delete) { act(GalleryAction.DeleteLayer(selected)) }
        }
    }
    ColorRow(EditorUi.PALETTE, editor.color, Modifier.fillMaxWidth()) { act(GalleryAction.BrushColor(it)) }
}

private fun textStyleLabel(s: TextStyle): Int = when (s) {
    TextStyle.PLAIN -> R.string.ime_gallery_text_plain
    TextStyle.OUTLINE -> R.string.ime_gallery_text_outline
    TextStyle.BOX -> R.string.ime_gallery_text_box
    TextStyle.GLASS -> R.string.ime_gallery_text_glass
}

private fun alignIcon(a: TextAlign): ImageVector = when (a) {
    TextAlign.START -> Icons.AutoMirrored.Outlined.FormatAlignLeft
    TextAlign.CENTER -> Icons.Outlined.AlignHorizontalCenter
    TextAlign.END -> Icons.Outlined.AlignHorizontalRight
}

// ---- Stickers ---------------------------------------------------------------

@Composable
private fun StickerControls(callbacks: GalleryCallbacks) {
    val act = callbacks.onAction
    val mine = remember { callbacks.stickers() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        items(EDITOR_EMOJI) { e ->
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { act(GalleryAction.AddSticker(null, e)) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 26.sp) }
        }
    }
    if (mine.isEmpty()) {
        Text(
            stringResource(R.string.ime_gallery_stickers_none),
            color = OnPhoto.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            items(mine, key = { it.id }) { s ->
                val thumb by produceState<Bitmap?>(null, s.path) {
                    value = withContext(Dispatchers.IO) { decodeStickerFile(s.path, 144) }
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { act(GalleryAction.AddSticker(s.path, null)) },
                    contentAlignment = Alignment.Center,
                ) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(42.dp)) }
                }
            }
        }
    }
}

/** The emoji the sticker tab offers before the user's own packs: the ones people put on photos. */
private val EDITOR_EMOJI = listOf(
    "😂", "❤️", "😍", "🔥", "👍", "😭", "🥰", "😎", "🤣", "✨", "🎉", "💯", "😮", "🙏", "👀", "😡",
    "🥳", "😴", "🤔", "💀", "👑", "⭐", "🌈", "☀️", "🌙", "🍕", "🎂", "🐶", "🐱", "🌸", "⚽", "➡️",
)

// ---- Filters ----------------------------------------------------------------

@Composable
private fun FilterControls(edit: PhotoEdit, source: Bitmap?, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    val small by produceState<Bitmap?>(null, source) {
        val s = source ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val (w, h) = com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep.targetSize(s.width, s.height, FILTER_THUMB)
            Bitmap.createScaledBitmap(s, w, h, true)
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().height(76.dp),
    ) {
        items(PhotoFilter.entries) { f ->
            val preview by produceState<Bitmap?>(null, small, f) {
                val s = small ?: return@produceState
                value = withContext(Dispatchers.Default) { PhotoEditRenderer.base(s, PhotoEdit(filter = f)) }
            }
            val on = edit.filter == f
            val label = stringResource(filterLabel(f))
            Column(
                Modifier
                    .clickable { act(GalleryAction.EditCommit(edit.copy(filter = f, filterAmount = 1f))) }
                    .semantics { contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(if (on) Modifier.border(2.dp, kb.accent, RoundedCornerShape(10.dp)) else Modifier)
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    preview?.let {
                        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
                    }
                }
                Text(label, color = if (on) kb.accent else OnPhoto, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    if (edit.filter != PhotoFilter.ORIGINAL) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GallerySlider(
                value = edit.filterAmount,
                range = 0f..1f,
                reset = 1f,
                description = stringResource(R.string.ime_gallery_filter_strength),
                modifier = Modifier.weight(1f),
                onChange = { act(GalleryAction.EditPreview(edit.copy(filterAmount = it))) },
                onDone = { act(GalleryAction.EditCommit(edit.copy(filterAmount = it))) },
            )
            Spacer(Modifier.width(8.dp))
            Text(signedPercent(edit.filterAmount, false), color = OnPhoto, fontSize = 12.sp, modifier = Modifier.width(32.dp))
        }
    }
}

private const val FILTER_THUMB = 160

private fun filterLabel(f: PhotoFilter): Int = when (f) {
    PhotoFilter.ORIGINAL -> R.string.ime_gallery_filter_original
    PhotoFilter.VIVID -> R.string.ime_gallery_filter_vivid
    PhotoFilter.WARM -> R.string.ime_gallery_filter_warm
    PhotoFilter.COOL -> R.string.ime_gallery_filter_cool
    PhotoFilter.MONO -> R.string.ime_gallery_filter_mono
    PhotoFilter.NOIR -> R.string.ime_gallery_filter_noir
    PhotoFilter.FADE -> R.string.ime_gallery_filter_fade
    PhotoFilter.SEPIA -> R.string.ime_gallery_filter_sepia
    PhotoFilter.CHROME -> R.string.ime_gallery_filter_chrome
    PhotoFilter.DRAMATIC -> R.string.ime_gallery_filter_dramatic
    PhotoFilter.MATTE -> R.string.ime_gallery_filter_matte
    PhotoFilter.RETRO -> R.string.ime_gallery_filter_retro
    PhotoFilter.LUSH -> R.string.ime_gallery_filter_lush
    PhotoFilter.DUSK -> R.string.ime_gallery_filter_dusk
}

// ---- Upscale ----------------------------------------------------------------

/**
 * The photo redrawn larger by the upscaler: which network (photos, or drawings
 * and text), then a factor, each shown with the size it gives. Only factors
 * that keep the photo within [MediaPrep.MAX_DECODE_EDGE] are offered. While
 * it runs, a bar and Cancel; once it is done, the size it has and a way back.
 */
@Composable
private fun UpscaleControls(editor: EditorUi, edit: PhotoEdit, photoSize: Pair<Int, Int>, act: (GalleryAction) -> Unit) {
    val (w, h) = photoSize
    val upscaling = editor.upscaling
    val applied = edit.upscale
    ChipRow {
        DarkChip(stringResource(R.string.ime_gallery_upscale_photo), selected = !editor.upscaleArt) {
            act(GalleryAction.UpscaleArt(false))
        }
        DarkChip(stringResource(R.string.ime_gallery_upscale_art), selected = editor.upscaleArt) {
            act(GalleryAction.UpscaleArt(true))
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            upscaling != null -> {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (upscaling.downloading) R.string.ime_gallery_upscale_downloading else R.string.ime_gallery_upscale_working),
                        color = OnPhoto,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (upscaling.fraction >= 0f) {
                        LinearProgressIndicator(progress = { upscaling.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                DarkChip(stringResource(CommonR.string.common_cancel), selected = false) { act(GalleryAction.CancelUpscale) }
            }
            applied != null -> {
                Text(
                    stringResource(R.string.ime_gallery_upscale_done, applied.factor, w * applied.factor, h * applied.factor),
                    color = OnPhoto,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                DarkChip(stringResource(R.string.ime_gallery_upscale_undo), selected = false) {
                    act(GalleryAction.EditCommit(edit.copy(upscale = null)))
                }
            }
            w <= 0 || h <= 0 || UpscaleTiles.largestFactor(w, h, MediaPrep.MAX_DECODE_EDGE) == 0 -> Text(
                stringResource(R.string.ime_gallery_upscale_too_large),
                color = OnPhoto.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
            else -> UpscaleModels.FACTORS.filter { maxOf(w, h) * it <= MediaPrep.MAX_DECODE_EDGE }.forEach { f ->
                DarkChip(
                    stringResource(R.string.ime_gallery_upscale_factor, f, w * f, h * f),
                    selected = false,
                    icon = Icons.Outlined.PhotoSizeSelectLarge,
                ) { act(GalleryAction.Upscale(f)) }
            }
        }
    }
    if (editor.upscaleFailed && upscaling == null) {
        Text(
            stringResource(R.string.ime_gallery_upscale_failed),
            color = OnPhoto.copy(alpha = 0.8f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

// ---- Adjust -----------------------------------------------------------------

@Composable
private fun AdjustControls(editor: EditorUi, edit: PhotoEdit, act: (GalleryAction) -> Unit) {
    val kb = LocalKbTheme.current
    ChipRow {
        AdjustKnob.entries.forEach { k ->
            val changed = edit.adjust.get(k) != 0f
            Box {
                DarkChip(stringResource(knobLabel(k)), selected = editor.knob == k, icon = knobIcon(k)) { act(GalleryAction.Knob(k)) }
                if (changed) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(kb.accent),
                    )
                }
            }
        }
    }
    val k = editor.knob
    val twoWay = k.min < 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        GallerySlider(
            value = edit.adjust.get(k),
            range = k.min..1f,
            twoWay = twoWay,
            description = stringResource(knobLabel(k)),
            modifier = Modifier.weight(1f),
            onChange = { act(GalleryAction.EditPreview(edit.copy(adjust = edit.adjust.with(k, it)))) },
            onDone = { act(GalleryAction.EditCommit(edit.copy(adjust = edit.adjust.with(k, it)))) },
        )
        Spacer(Modifier.width(8.dp))
        Text(signedPercent(edit.adjust.get(k), twoWay), color = OnPhoto, fontSize = 12.sp, modifier = Modifier.width(36.dp))
    }
}

private fun knobIcon(k: AdjustKnob): ImageVector = when (k) {
    AdjustKnob.BRIGHTNESS -> Icons.Outlined.LightMode
    AdjustKnob.CONTRAST -> Icons.Outlined.Contrast
    AdjustKnob.SATURATION -> Icons.Outlined.Palette
    AdjustKnob.WARMTH -> Icons.Outlined.Thermostat
    AdjustKnob.TINT -> Icons.Outlined.InvertColors
    AdjustKnob.EXPOSURE -> Icons.Outlined.WbSunny
    AdjustKnob.HIGHLIGHTS -> Icons.Outlined.WbTwilight
    AdjustKnob.SHADOWS -> Icons.Outlined.DarkMode
    AdjustKnob.FADE -> Icons.Outlined.Opacity
    AdjustKnob.VIGNETTE -> Icons.Outlined.CenterFocusStrong
    AdjustKnob.GRAIN -> Icons.Outlined.Grain
    AdjustKnob.SHARPEN -> Icons.Outlined.Texture
}

private fun knobLabel(k: AdjustKnob): Int = when (k) {
    AdjustKnob.BRIGHTNESS -> R.string.ime_gallery_adjust_brightness
    AdjustKnob.CONTRAST -> R.string.ime_gallery_adjust_contrast
    AdjustKnob.SATURATION -> R.string.ime_gallery_adjust_saturation
    AdjustKnob.WARMTH -> R.string.ime_gallery_adjust_warmth
    AdjustKnob.TINT -> R.string.ime_gallery_adjust_tint
    AdjustKnob.EXPOSURE -> R.string.ime_gallery_adjust_exposure
    AdjustKnob.HIGHLIGHTS -> R.string.ime_gallery_adjust_highlights
    AdjustKnob.SHADOWS -> R.string.ime_gallery_adjust_shadows
    AdjustKnob.FADE -> R.string.ime_gallery_adjust_fade
    AdjustKnob.VIGNETTE -> R.string.ime_gallery_adjust_vignette
    AdjustKnob.GRAIN -> R.string.ime_gallery_adjust_grain
    AdjustKnob.SHARPEN -> R.string.ime_gallery_adjust_sharpen
}
