package com.wasimaster.wmkeyboard.app

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import com.wasimaster.wmkeyboard.core.settings.activeThemeSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.settings.BoardCorner
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.theme.keyShapeFor
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.keysHaveFaces
import kotlin.math.roundToInt

// The pinned keyboard preview (SETTINGS_REDESIGN.md §6): a small keyboard
// drawn from the real settings and the active keyboard theme, pinned under the
// bar of the screens whose rows change how the keyboard looks, so a slider
// moves the thing it sets instead of a number beside it.
//
// One Canvas rather than a key per composable: forty boxes laid out again on
// every slider tick would cost more than drawing forty rectangles, and the
// gesture demo needs the key centres anyway. The wizard's `MiniKeyboardPreview`
// stays as it is — it is about the *shape* of a board in the app's own colours,
// and this one is about the user's board in the user's colours.

/** Which part of the keyboard the screen under the preview is about. */
internal enum class PreviewFocus {
    /** Layout & size: the board's geometry, nothing lit. */
    SIZE,

    /** Appearance › Keys: corner radius, label size, and the corner hints drawn. */
    KEYS,

    /** Key press › Key popup: one key pressed, with its popup bubble. */
    POPUP,

    /** Rows & bars: every bar in its place in the stack. */
    ROWS,

    /** Emoji › Emoji row: the row, or the strip's emoji button, lit. */
    EMOJI_ROW,

    /** The gesture demo: the strip is left empty for the demo to write in. */
    GESTURES,
}

/** The pinned preview's frame: fixed, so a slider under it never moves the page. */
internal val PinnedPreviewHeight: Dp = 124.dp

/**
 * The most a keyboard dp may grow to on screen in a pinned preview. The default
 * board (strip, digit row, four key rows) at this scale is the frame's height,
 * so there is a little room to grow before a taller board shrinks to fit.
 */
private const val PinnedMaxScale = 0.46f

/**
 * The live keyboard under a settings screen's bar, from [RegisterPinned].
 *
 * The one whole-object watch a screen may hold (`live-settings-rows`): every
 * write recomposes this composable and only it, and what it recomposes is one
 * theme resolve and one draw. On a landscape phone the frame would take the
 * row that is left, so there it folds behind a chip and opens on request.
 */
@Composable
internal fun PinnedKeyboardPreview(settings: LiveSettings, focus: PreviewFocus) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var openInLandscape by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (landscape) {
            FilterChip(
                selected = openInLandscape,
                onClick = { openInLandscape = !openInLandscape },
                label = { Text(stringResource(R.string.preview_chip_label)) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (!landscape || openInLandscape) {
            val all = settings.watch { it }
            KeyboardThemeProvider(all) {
                KeyboardMiniature(
                    settings = all,
                    kb = LocalKbTheme.current,
                    focus = focus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    maxHeight = PinnedPreviewHeight,
                    maxScale = PinnedMaxScale,
                )
            }
        }
    }
}

// ---- geometry ----

/** What a key on the miniature stands for; the gesture demo finds keys by it. */
internal enum class MiniKeyKind { LETTER, DIGIT, SHIFT, BACKSPACE, SYMBOLS, GLOBE, SPACE, PERIOD, ENTER }

/** One key of the miniature, in canvas pixels. */
internal class MiniKey(val label: String, val rect: Rect, val kind: MiniKeyKind)

/** A bar above or below the keys. */
internal enum class MiniBandKind { STRIP, TOOLS, EMOJI, SYMBOL, FANCY, DICTIONARY, MACROS, STICKERS, RECENT }

/** One bar of the miniature; [dim] for a row that only arrives on a tap or a selection. */
internal class MiniBand(val kind: MiniBandKind, val rect: Rect, val dim: Boolean)

/**
 * Where everything on the miniature is, in canvas pixels. [scale] is pixels
 * per keyboard dp, so a width the keyboard sets in dp is drawn at `dp * scale`.
 */
internal class MiniatureGeometry(
    val scale: Float,
    val board: Rect,
    val keys: List<MiniKey>,
    val bands: List<MiniBand>,
) {
    fun key(label: String): MiniKey? = keys.firstOrNull { it.kind == MiniKeyKind.LETTER && it.label == label }
    fun key(kind: MiniKeyKind): MiniKey? = keys.firstOrNull { it.kind == kind }
    val strip: MiniBand? get() = bands.firstOrNull { it.kind == MiniBandKind.STRIP }
}

/** The letter rows every miniature draws. */
private val MiniRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/** Heights the keyboard does not keep in a setting, in keyboard dp. */
private const val StripHeightDp = 44f
private const val BandHeightDp = 40f
private const val BoardPadDp = 3f
private const val KeyGapDp = 4f

/** What the keyboard pads the bottom by while the setting is unset ([KeyboardSettings.bottomPaddingDp]). */
private const val AutoBottomPaddingDp = 16f

/** Type sizes at scale 1, in sp. */
private const val KeyLabelSp = 20f
private const val HintSp = 11f
private const val PopupLabelSp = 26f
private const val StripTextSp = 15f
private const val BandGlyphSp = 16f

/**
 * The bars the miniature stacks around its keys, top to bottom, with the keys
 * marked by a null kind. Under [PreviewFocus.ROWS] every row the Rows screen
 * lists as on is here, the ones that only come and go drawn dim; under any
 * other focus only what is on screen at rest.
 */
private fun miniatureBands(settings: KeyboardSettings, focus: PreviewFocus): List<Pair<MiniBandKind?, Boolean>> {
    val out = ArrayList<Pair<MiniBandKind?, Boolean>>(settings.barOrder.size)
    for (row in settings.barOrder) {
        if (row == BarRow.KEYBOARD) {
            out += null to false
            continue
        }
        val kind = when (row) {
            BarRow.TOPBAR -> MiniBandKind.STRIP
            BarRow.TOOLS -> MiniBandKind.TOOLS
            BarRow.EMOJI -> MiniBandKind.EMOJI
            BarRow.SYMBOL -> MiniBandKind.SYMBOL
            BarRow.FANCY -> MiniBandKind.FANCY
            BarRow.DICTIONARY -> MiniBandKind.DICTIONARY
            BarRow.MACROS -> MiniBandKind.MACROS
            BarRow.STICKERS -> MiniBandKind.STICKERS
            BarRow.RECENT -> MiniBandKind.RECENT
            BarRow.KEYBOARD -> continue
        }
        if (!barRowShown(row, settings)) continue
        // Drawn in its slot with nothing pressed and nothing selected.
        val atRest = when (row) {
            BarRow.TOOLS -> settings.toolbarBehavior.placement == ToolbarPlacement.ALWAYS_ROW
            BarRow.MACROS, BarRow.STICKERS -> false
            BarRow.RECENT -> settings.rows.recentWordPlacement == RecentWordPlacement.OWN_ROW
            else -> true
        }
        if (atRest) {
            out += kind to false
        } else if (focus == PreviewFocus.ROWS) {
            out += kind to true
        }
    }
    return out
}

/**
 * Lays the board out inside [canvas]: the bars, the digit row when it is on,
 * three letter rows and the bottom row, at the largest scale that fits both
 * the frame's height and its width, never above [maxScale]. The board is as
 * wide as the width setting says, where the alignment says, on a screen
 * [screenWidthDp] wide.
 */
private fun miniatureGeometry(
    settings: KeyboardSettings,
    focus: PreviewFocus,
    screenWidthDp: Float,
    canvas: Size,
    density: Float,
    maxScale: Float,
): MiniatureGeometry {
    val stack = miniatureBands(settings, focus)
    val above = stack.takeWhile { it.first != null }.map { it.first!! to it.second }
    val below = stack.dropWhile { it.first != null }.drop(1).map { it.first!! to it.second }
    fun bandHeight(kind: MiniBandKind) = if (kind == MiniBandKind.STRIP) StripHeightDp else BandHeightDp

    val behavior = settings.layoutBehavior
    val gapDp = KeyGapDp * settings.keyGapScale
    val keyHDp = settings.keyHeightDp.toFloat()
    val bottomRowHDp = if (behavior.bottomRowHeightDp > 0) behavior.bottomRowHeightDp.toFloat() else keyHDp
    val numberRowHDp = if (settings.numberRow) settings.numberRowHeightDp.toFloat() else 0f
    val keysHDp = numberRowHDp + (if (settings.numberRow) gapDp else 0f) + 3 * keyHDp + 3 * gapDp + bottomRowHDp
    val bottomPadDp = (settings.bottomPaddingDp?.toFloat() ?: AutoBottomPaddingDp)
    val totalHDp = above.sumOf { bandHeight(it.first).toDouble() }.toFloat() +
        below.sumOf { bandHeight(it.first).toDouble() }.toFloat() +
        keysHDp + 2 * BoardPadDp + bottomPadDp

    val scaleDp = minOf(maxScale, (canvas.height / density) / totalHDp, (canvas.width / density) / screenWidthDp)
    val s = scaleDp * density
    val screenW = screenWidthDp * s
    val screenLeft = (canvas.width - screenW) / 2f
    val boardW = screenW * settings.keyboardWidthPercent / 100f
    val boardLeft = when (settings.keyboardAlignment) {
        KeyboardAlignment.LEFT -> screenLeft
        KeyboardAlignment.CENTER -> screenLeft + (screenW - boardW) / 2f
        KeyboardAlignment.RIGHT -> screenLeft + screenW - boardW
    }
    val boardRight = boardLeft + boardW
    val totalH = totalHDp * s
    val top = (canvas.height - totalH) / 2f
    val board = Rect(boardLeft, top, boardRight, top + totalH)

    val bands = ArrayList<MiniBand>(above.size + below.size)
    var y = top
    for ((kind, dim) in above) {
        val h = bandHeight(kind) * s
        bands += MiniBand(kind, Rect(boardLeft, y, boardRight, y + h), dim)
        y += h
    }
    y += BoardPadDp * s

    val keys = ArrayList<MiniKey>(48)
    val gap = gapDp * s
    val innerLeft = boardLeft + BoardPadDp * s + boardW * behavior.sidePadLeftScale
    val innerRight = boardRight - BoardPadDp * s - boardW * behavior.sidePadRightScale
    val unit = ((innerRight - innerLeft) - 9 * gap) / 10f
    fun row(height: Float, startX: Float, cells: List<Triple<String, MiniKeyKind, Float>>) {
        var x = startX
        for ((label, kind, width) in cells) {
            keys += MiniKey(label, Rect(x, y, x + width, y + height), kind)
            x += width + gap
        }
        y += height + gap
    }
    if (settings.numberRow) {
        row(numberRowHDp * s, innerLeft, "1234567890".map { Triple(it.toString(), MiniKeyKind.DIGIT, unit) })
    }
    row(keyHDp * s, innerLeft, MiniRows[0].map { Triple(it.toString(), MiniKeyKind.LETTER, unit) })
    row(keyHDp * s, innerLeft + (unit + gap) / 2f, MiniRows[1].map { Triple(it.toString(), MiniKeyKind.LETTER, unit) })
    val wide = 1.5f * unit + 0.5f * gap
    row(
        keyHDp * s,
        innerLeft,
        listOf(Triple("", MiniKeyKind.SHIFT, wide)) +
            MiniRows[2].map { Triple(it.toString(), MiniKeyKind.LETTER, unit) } +
            Triple("", MiniKeyKind.BACKSPACE, wide),
    )
    row(
        bottomRowHDp * s,
        innerLeft,
        listOf(
            Triple("", MiniKeyKind.SYMBOLS, wide),
            Triple("", MiniKeyKind.GLOBE, unit),
            Triple("", MiniKeyKind.SPACE, 5 * unit + 4 * gap),
            Triple("", MiniKeyKind.PERIOD, unit),
            Triple("", MiniKeyKind.ENTER, wide),
        ),
    )
    // The last row's trailing gap is the board's own padding.
    y += BoardPadDp * s - gap
    for ((kind, dim) in below) {
        val h = bandHeight(kind) * s
        bands += MiniBand(kind, Rect(boardLeft, y, boardRight, y + h), dim)
        y += h
    }
    return MiniatureGeometry(s, board, keys, bands)
}

// ---- drawing ----

/** The glyphs the schematic bars carry. One glyph per draw call: emoji fonts rarely have a space. */
private val BandEmoji = listOf("😀", "😂", "❤️", "👍", "🔥", "🙏", "🎉", "😍")
private val BandSymbols = listOf("@", "#", "$", "%", "&", "(", ")", "/")
private val BandFancy = listOf("𝐀𝐚", "𝘈𝘢", "𝒜𝒶", "Ⓐⓐ")
private val StripWords = listOf("I", "the", "to")

/** The key the popup preview presses, and the hint drawn on the letters under [PreviewFocus.KEYS]. */
private const val PressedLetter = "e"
private const val HintSample = "1"

/**
 * A small keyboard drawn from [settings] in the colours of [kb]: the bars in
 * their order, the digit row when it is on, the letters at the set height,
 * gap, width and corner radius, the bottom padding under them. [focus] picks
 * what else is drawn, and [overlay] draws over it all with the geometry in
 * hand — the gesture demo's stroke. The canvas is [maxHeight] tall whatever
 * the board needs, so changing the board never moves the page under it.
 */
@Composable
internal fun KeyboardMiniature(
    settings: KeyboardSettings,
    kb: KbTheme,
    focus: PreviewFocus,
    modifier: Modifier = Modifier,
    maxHeight: Dp = PinnedPreviewHeight,
    maxScale: Float = PinnedMaxScale,
    overlay: (DrawScope.(MiniatureGeometry) -> Unit)? = null,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // A phone's portrait width, whichever way it is held: the board the
    // sliders describe is the one that comes up with the phone upright.
    val screenWidthDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp).toFloat()
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val keyFontId = settings.keyFontId
    val family = remember(keyFontId, settings.customFontName) { KeyboardFonts.family(context, keyFontId) }
    val description = stringResource(R.string.preview_keyboard_desc)
    Spacer(
        modifier
            .height(maxHeight)
            .semantics { contentDescription = description }
            .drawWithCache {
                val geometry = miniatureGeometry(settings, focus, screenWidthDp, size, density, maxScale)
                val painter = MiniaturePainter(settings, kb, focus, geometry, measurer, family, layoutDirection, density)
                onDrawBehind {
                    painter.draw(this)
                    overlay?.invoke(this, geometry)
                }
            },
    )
}

/**
 * Draws one [MiniatureGeometry]. Built in the draw cache, so the shapes and the
 * measured labels survive between frames; the overlay alone changes per frame.
 */
private class MiniaturePainter(
    private val settings: KeyboardSettings,
    private val kb: KbTheme,
    private val focus: PreviewFocus,
    private val geometry: MiniatureGeometry,
    private val measurer: TextMeasurer,
    private val family: FontFamily?,
    private val direction: LayoutDirection,
    private val density: Float,
) {
    private val s = geometry.scale

    /**
     * Keyboard sp to the sp of a `TextStyle`: [s] already carries the density,
     * and `.sp` applies it again, so type is scaled by the dp ratio alone.
     */
    private val sp = s / density
    private val keyShape: Shape = keyShapeFor(kb.keyShapeKind, (kb.keyRadiusDp * s).roundToInt())
    private val labelStyle = TextStyle(fontSize = (KeyLabelSp * settings.fontScale * sp).sp, fontFamily = family)
    private val hintStyle = TextStyle(fontSize = (HintSp * settings.layoutBehavior.hintFontScale * sp).sp, fontFamily = family)
    private val stripStyle = TextStyle(fontSize = (StripTextSp * sp).sp)
    private val glyphStyle = TextStyle(fontSize = (BandGlyphSp * sp).sp)

    fun draw(scope: DrawScope) = with(scope) {
        drawBoard()
        for (band in geometry.bands) drawBand(band)
        for (key in geometry.keys) drawKey(key)
        if (focus == PreviewFocus.POPUP) drawPopup()
    }

    private fun DrawScope.drawBoard() {
        val behavior = settings.layoutBehavior
        val top = behavior.boardCornerTopDp * s
        val bottom = behavior.boardCornerBottomDp * s
        val corners = behavior.boardCorners
        val board = geometry.board
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    board,
                    topLeft = CornerRadius(if (BoardCorner.TOP_LEFT in corners) top else 0f),
                    topRight = CornerRadius(if (BoardCorner.TOP_RIGHT in corners) top else 0f),
                    bottomRight = CornerRadius(if (BoardCorner.BOTTOM_RIGHT in corners) bottom else 0f),
                    bottomLeft = CornerRadius(if (BoardCorner.BOTTOM_LEFT in corners) bottom else 0f),
                ),
            )
        }
        drawPath(path, kb.board)
    }

    private fun DrawScope.drawKey(key: MiniKey) {
        val pressed = focus == PreviewFocus.POPUP && key.kind == MiniKeyKind.LETTER && key.label == PressedLetter
        val face = when {
            pressed -> kb.pressedKey
            key.kind == MiniKeyKind.LETTER || key.kind == MiniKeyKind.DIGIT || key.kind == MiniKeyKind.SPACE -> kb.key
            key.kind == MiniKeyKind.ENTER -> kb.enterKey
            else -> kb.modifierKey
        }
        if (kb.keysHaveFaces || pressed) {
            val outline = keyShape.createOutline(key.rect.size, direction, this)
            translate(key.rect.left, key.rect.top) {
                drawOutline(outline, face)
                val border = kb.keyBorder
                if (border != null && kb.keyBorderWidthDp > 0f) {
                    drawOutline(outline, border, style = Stroke((kb.keyBorderWidthDp * s).coerceAtLeast(0.5f)))
                }
            }
        }
        if (key.label.isEmpty()) {
            if (key.kind == MiniKeyKind.SPACE) drawSpaceMark(key)
            return
        }
        val text = when (key.kind) {
            MiniKeyKind.ENTER -> kb.enterKeyText
            MiniKeyKind.LETTER, MiniKeyKind.DIGIT -> kb.keyText
            else -> kb.modifierKeyText
        }
        val label = measurer.measure(key.label, labelStyle, maxLines = 1)
        drawCentred(label, key.rect, text)
        if (focus == PreviewFocus.KEYS && key.kind == MiniKeyKind.LETTER && settings.longPressHints) {
            val hint = measurer.measure(HintSample, hintStyle, maxLines = 1)
            val inset = 1.5f * s
            val offset = settings.layoutBehavior.hintOffsetDp * s
            drawText(
                hint,
                color = kb.hintText ?: kb.keyText.copy(alpha = 0.6f),
                topLeft = Offset(key.rect.right - inset - hint.size.width, key.rect.top + inset + offset),
            )
        }
    }

    /** The short bar the spacebar carries in place of a label. */
    private fun DrawScope.drawSpaceMark(key: MiniKey) {
        val w = key.rect.width * 0.3f
        val h = (1.5f * s).coerceAtLeast(1f)
        drawRoundRect(
            kb.keyText.copy(alpha = 0.35f),
            topLeft = Offset(key.rect.center.x - w / 2f, key.rect.center.y - h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2f),
        )
    }

    private fun DrawScope.drawBand(band: MiniBand) {
        val rect = band.rect
        val alpha = if (band.dim) 0.4f else 1f
        val fill = when (band.kind) {
            MiniBandKind.STRIP -> kb.suggestionBar
            MiniBandKind.TOOLS -> kb.toolbar
            else -> null
        }
        if (fill != null) drawRect(fill.copy(alpha = fill.alpha * alpha), rect.topLeft, rect.size)
        val lit = focus == PreviewFocus.EMOJI_ROW && band.kind == MiniBandKind.EMOJI
        val padX = 8f * s
        val gapX = 6f * s
        when (band.kind) {
            MiniBandKind.STRIP -> if (focus != PreviewFocus.GESTURES) drawStrip(rect, alpha)
            MiniBandKind.TOOLS -> drawDots(rect, 6, padX, gapX, kb.toolCircle.copy(alpha = alpha))
            MiniBandKind.EMOJI -> {
                val count = settings.emoji.barCount.coerceIn(1, BandEmoji.size * 2)
                drawGlyphs(rect, List(count) { BandEmoji[it % BandEmoji.size] }, padX, gapX, kb.suggestionText, alpha)
            }
            MiniBandKind.SYMBOL -> drawGlyphs(rect, BandSymbols, padX, gapX, kb.keyText, alpha)
            MiniBandKind.FANCY -> drawGlyphs(rect, BandFancy, padX, gapX, kb.suggestionText, alpha)
            MiniBandKind.DICTIONARY -> drawPills(
                rect,
                listOf(30f to kb.chipActive, 26f to kb.chipActive, 34f to kb.chip),
                padX,
                gapX,
                alpha,
            )
            MiniBandKind.MACROS -> drawPills(rect, List(3) { 22f to kb.chip }, padX, gapX, alpha)
            MiniBandKind.STICKERS -> drawPills(rect, List(4) { 24f to kb.chip }, padX, gapX, alpha)
            MiniBandKind.RECENT -> drawPills(rect, listOf(28f to kb.chip, 22f to kb.chipActive, 28f to kb.chip), padX, gapX, alpha)
        }
        if (lit) {
            drawRect(kb.accent, rect.topLeft, rect.size, style = Stroke((1.5f * s).coerceAtLeast(1f)))
        }
    }

    /** The suggestion strip: a few words, and the toolbar's circles when the tools share it. */
    private fun DrawScope.drawStrip(rect: Rect, alpha: Float) {
        var x = rect.left + 10f * s
        for (word in StripWords) {
            val layout = measurer.measure(word, stripStyle, maxLines = 1)
            drawText(layout, kb.suggestionText.copy(alpha = alpha), Offset(x, rect.center.y - layout.size.height / 2f))
            x += layout.size.width + 16f * s
        }
        val toolbar = settings.toolbarBehavior
        if (!toolbar.enabled || toolbar.placement != ToolbarPlacement.STRIP) return
        val emojiButton = settings.emojiBarMode == EmojiBarMode.BUTTON
        val d = (StripHeightDp - 14f) * s
        var cx = rect.right - 10f * s - d / 2f
        repeat(3) { index ->
            val lit = emojiButton && index == 0 && focus == PreviewFocus.EMOJI_ROW
            drawCircle(
                (if (lit) kb.toolCircleActive else kb.toolCircle).copy(alpha = alpha),
                radius = d / 2f,
                center = Offset(cx, rect.center.y),
            )
            if (emojiButton && index == 0) {
                val glyph = measurer.measure(BandEmoji[0], glyphStyle, maxLines = 1)
                drawCentred(glyph, Rect(Offset(cx - d / 2f, rect.center.y - d / 2f), Size(d, d)), kb.toolbarIcon)
            }
            cx -= d + 6f * s
        }
    }

    private fun DrawScope.drawDots(rect: Rect, count: Int, padX: Float, gapX: Float, color: Color) {
        val d = (rect.height * 0.55f)
        var cx = rect.left + padX + d / 2f
        repeat(count) {
            if (cx + d / 2f > rect.right - padX) return
            drawCircle(color, radius = d / 2f, center = Offset(cx, rect.center.y))
            cx += d + gapX
        }
    }

    private fun DrawScope.drawPills(rect: Rect, pills: List<Pair<Float, Color>>, padX: Float, gapX: Float, alpha: Float) {
        val h = rect.height * 0.4f
        var x = rect.left + padX
        for ((widthDp, color) in pills) {
            val w = widthDp * s
            if (x + w > rect.right - padX) return
            drawRoundRect(
                color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(x, rect.center.y - h / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(h / 2f),
            )
            x += w + gapX
        }
    }

    private fun DrawScope.drawGlyphs(rect: Rect, glyphs: List<String>, padX: Float, gapX: Float, color: Color, alpha: Float) {
        var x = rect.left + padX
        for (glyph in glyphs) {
            val layout = measurer.measure(glyph, glyphStyle, maxLines = 1)
            if (x + layout.size.width > rect.right - padX) return
            drawText(layout, color.copy(alpha = color.alpha * alpha), Offset(x, rect.center.y - layout.size.height / 2f))
            x += layout.size.width + gapX
        }
    }

    /** The bubble a press raises over [PressedLetter], placed and sized by the popup settings. */
    private fun DrawScope.drawPopup() {
        val popup = settings.popup
        if (!popup.enabled) return
        val key = geometry.key(PressedLetter) ?: return
        val onKey = kb.popupOnKey ?: popup.onKey
        val height = popup.heightDp * s
        val rect = if (onKey) {
            val w = key.rect.width * 1.3f
            Rect(key.rect.center.x - w / 2f, key.rect.bottom - height, key.rect.center.x + w / 2f, key.rect.bottom)
        } else {
            val w = key.rect.width * 1.4f
            val cx = key.rect.center.x + popup.floatingOffsetXDp * s
            val bottom = key.rect.top - popup.floatingOffsetYDp * s
            Rect(cx - w / 2f, bottom - height, cx + w / 2f, bottom)
        }
        val shape = keyShapeFor(popup.shape, (popup.cornerRadiusDp * s).roundToInt())
        val outline = shape.createOutline(rect.size, direction, this)
        val fill = popup.backgroundColor?.let { Color(it.toInt()) } ?: kb.popup
        val text = popup.textColor?.let { Color(it.toInt()) } ?: kb.popupText
        translate(rect.left, rect.top) {
            if (kb.popupElevation > 0.dp) {
                translate(0f, 1.5f * s) { drawOutline(outline, Color.Black.copy(alpha = 0.18f)) }
            }
            drawOutline(outline, fill)
            val border = kb.popupBorder
            if (border != null && kb.popupBorderWidthDp > 0f) {
                drawOutline(outline, border, style = Stroke((kb.popupBorderWidthDp * s).coerceAtLeast(0.5f)))
            }
        }
        val style = TextStyle(fontSize = (PopupLabelSp * popup.fontScale * sp).sp, fontFamily = family)
        val label = measurer.measure(PressedLetter, style, maxLines = 1)
        // The on-key bubble's label sits in its upper part, clear of the finger.
        val anchor = if (onKey) Rect(rect.left, rect.top, rect.right, rect.top + minOf(rect.height, key.rect.height * 1.2f)) else rect
        drawCentred(label, anchor, text)
    }
}

/** Draws [layout] centred in [rect]. */
internal fun DrawScope.drawCentred(layout: TextLayoutResult, rect: Rect, color: Color) {
    drawText(
        layout,
        color = color,
        topLeft = Offset(rect.center.x - layout.size.width / 2f, rect.center.y - layout.size.height / 2f),
    )
}

// ---- the two rows that show their value ----

/**
 * The active theme's board, key and accent colours as three small discs, on
 * the Keyboard themes row. A stored theme gives its own colours; the Default
 * theme follows the app's scheme, which is where it takes its colours from on
 * the keyboard too. An auto pair that changes over by the clock or the sun is
 * read as the system's half: the exact slot lives behind the keyboard's own
 * clock, and a swatch a minute early is not worth a second one.
 */
@Composable
internal fun ThemeSwatchStrip(settings: LiveSettings) {
    val systemDark = isSystemInDarkTheme()
    val spec = settings.watch { it.activeThemeSpec(systemDark) }
    val scheme = MaterialTheme.colorScheme
    val colors = if (spec == null) {
        listOf(scheme.surfaceContainerLow, scheme.surfaceContainerHighest, scheme.primary)
    } else {
        listOf(Color(spec.boardBackground.toInt()), Color(spec.keyBackground.toInt()), Color(spec.accent.toInt()))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SwatchOverlap)) {
        for (color in colors) {
            Box(
                Modifier
                    .size(SwatchSize)
                    .background(color, CircleShape)
                    .border(1.dp, scheme.outlineVariant, CircleShape),
            )
        }
    }
}

private val SwatchSize = 16.dp
private val SwatchOverlap = (-4).dp

/** "Aa" in the keyboard's font, on the Keyboard font row: the system face when no font is picked. */
@Composable
internal fun FontSample(settings: LiveSettings) {
    val context = LocalContext.current
    val id = settings.watch { it.keyFontId }
    val customName = settings.watch { it.customFontName }
    val family = remember(id, customName) { KeyboardFonts.family(context, id) }
    Text(
        stringResource(R.string.preview_font_sample),
        fontFamily = family,
        fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}
