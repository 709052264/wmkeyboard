package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SpaceBar
import com.wasimaster.wmkeyboard.core.settings.BackspaceSwipeUnit
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import kotlin.math.hypot
import kotlin.math.roundToInt

// The gesture demo on Typing › Gestures (SETTINGS_REDESIGN.md §6): a stroke
// drawn over the keyboard miniature again and again, with the trail the user's
// own width, length, opacity and theme colour, so the three sliders that set it
// show what they set. A segmented switch picks the gesture: a glided word, a
// spacebar swipe (moving the cursor or changing language, whichever the
// spacebar settings say), or the backspace swipe.

/** The gesture the demo is drawing. */
internal enum class GestureDemoKind { GLIDE, SPACEBAR, BACKSPACE }

/** One full loop: a pause, the stroke, the trail fading, a pause. */
private const val LoopMs = 3200f

/** Where in the loop the finger lands and lifts, as fractions of [LoopMs]. */
private const val StrokeStart = 0.12f
private const val StrokeEnd = 0.68f

/** The frame Reduce motion holds: mid-stroke, trail drawn, word half typed. */
private const val StillProgress = 0.42f

/** The demo's board is drawn larger than the pinned one: it has the page to itself. */
private val GestureDemoHeight: Dp = 150.dp
private const val GestureDemoMaxScale = 0.58f

/** How far along the stroke the tail thins to, as a share of the head's width. */
private const val TrailTailShare = 0.3f

/** Samples per spline segment between two keys. */
private const val SplineSteps = 12

/** Type sizes at scale 1, in sp. */
private const val DemoTextSp = 15f
private const val SpaceLabelSp = 13f

/** Alpha of a stroke whose gesture the settings have switched off. */
private const val OffAlpha = 0.35f

/**
 * The words the demo types and edits. From resources, so a translator can pick
 * a word their readers know — on the condition the glide word stays in the
 * lowercase Latin letters the miniature has keys for.
 */
private class DemoTexts(val word: String, val line: String, val languageA: String, val languageB: String)

/**
 * The animated gesture demo: a switch over the three gestures, and the user's
 * keyboard with the chosen stroke looping over it.
 *
 * The loop runs on frame callbacks while the demo has any area in the window
 * and stops the moment it scrolls off, so a page of sixty rows does not spend
 * frames on a picture nobody can see. Under Reduce motion there is no loop at
 * all: one frame from the middle of the stroke, with the trail on it.
 */
@Composable
internal fun GestureDemo(settings: LiveSettings) {
    var kind by rememberSaveable { mutableStateOf(GestureDemoKind.GLIDE) }
    val reduceMotion = LocalReduceMotion.current
    var onScreen by remember { mutableStateOf(false) }
    val progress = remember { mutableFloatStateOf(StillProgress) }
    LaunchedEffect(onScreen, reduceMotion, kind) {
        if (reduceMotion || !onScreen) {
            progress.floatValue = StillProgress
            return@LaunchedEffect
        }
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                progress.floatValue = (((now - start) / 1_000_000f) % LoopMs) / LoopMs
            }
        }
    }
    // The one whole-object watch a keyboard preview may hold.
    val all = settings.watch { it }
    val texts = DemoTexts(
        word = stringResource(R.string.preview_sample_word).lowercase(),
        line = stringResource(R.string.preview_sample_text),
        languageA = stringResource(R.string.preview_language_a),
        languageB = stringResource(R.string.preview_language_b),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ChoiceControl(
            options = listOf(
                GestureDemoKind.GLIDE to stringResource(R.string.preview_gesture_glide),
                GestureDemoKind.SPACEBAR to stringResource(R.string.preview_gesture_spacebar),
                GestureDemoKind.BACKSPACE to stringResource(R.string.preview_gesture_backspace),
            ),
            selected = kind,
            label = stringResource(R.string.preview_gesture_label),
            detail = { which ->
                ChoiceDetail(
                    icon = when (which) {
                        GestureDemoKind.GLIDE -> Icons.Outlined.Gesture
                        GestureDemoKind.SPACEBAR -> Icons.Outlined.SpaceBar
                        GestureDemoKind.BACKSPACE -> Icons.AutoMirrored.Outlined.Backspace
                    },
                )
            },
        ) { kind = it }
        KeyboardThemeProvider(all) {
            val kb = LocalKbTheme.current
            val measurer = rememberTextMeasurer(cacheSize = 32)
            KeyboardMiniature(
                settings = all,
                kb = kb,
                focus = PreviewFocus.GESTURES,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        val shown = bounds.width > 0f && bounds.height > 0f
                        if (shown != onScreen) onScreen = shown
                    },
                maxHeight = GestureDemoHeight,
                maxScale = GestureDemoMaxScale,
            ) { geometry ->
                // Read in the draw phase: a new frame redraws this and nothing else.
                drawGesture(kind, all, kb, texts, measurer, geometry, progress.floatValue)
            }
        }
    }
}

// ---- the stroke ----

/** A sampled stroke: points along it, the distance to each, and where each key centre falls. */
private class StrokePath(val points: List<Offset>, val distances: FloatArray, val waypointDistances: FloatArray) {
    val length: Float get() = distances.last()
}

/**
 * A Catmull-Rom spline through [waypoints], sampled [SplineSteps] times per
 * segment: a finger's path between keys is a curve, and a polyline through the
 * centres reads as a wiring diagram.
 */
private fun strokeThrough(waypoints: List<Offset>): StrokePath {
    val n = waypoints.size
    if (n < 2) {
        val p = waypoints.firstOrNull() ?: Offset.Zero
        return StrokePath(listOf(p, p), floatArrayOf(0f, 0f), FloatArray(n))
    }
    val points = ArrayList<Offset>((n - 1) * SplineSteps + 1)
    val waypointIndex = IntArray(n)
    for (i in 0 until n - 1) {
        val p0 = waypoints[maxOf(i - 1, 0)]
        val p1 = waypoints[i]
        val p2 = waypoints[i + 1]
        val p3 = waypoints[minOf(i + 2, n - 1)]
        waypointIndex[i] = points.size
        for (k in 0 until SplineSteps) {
            val t = k / SplineSteps.toFloat()
            val t2 = t * t
            val t3 = t2 * t
            val x = 0.5f * (
                2f * p1.x + (-p0.x + p2.x) * t +
                    (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                    (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3
                )
            val y = 0.5f * (
                2f * p1.y + (-p0.y + p2.y) * t +
                    (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                    (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
                )
            points += Offset(x, y)
        }
    }
    waypointIndex[n - 1] = points.size
    points += waypoints.last()
    val distances = FloatArray(points.size)
    for (i in 1 until points.size) {
        distances[i] = distances[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    return StrokePath(points, distances, FloatArray(n) { distances[waypointIndex[it]] })
}

/** The keys a glide over [word] passes, with a small loop on a doubled letter, as the decoder expects one. */
private fun glideWaypoints(geometry: MiniatureGeometry, word: String): List<Offset> {
    val out = ArrayList<Offset>()
    var previous: Char? = null
    for (letter in word) {
        val key = geometry.key(letter.toString()) ?: continue
        val c = key.rect.center
        if (letter == previous) {
            val r = key.rect.height * 0.35f
            out += Offset(c.x + r, c.y - r * 0.4f)
            out += Offset(c.x, c.y - r)
            out += Offset(c.x - r, c.y)
            out += Offset(c.x, c.y + r * 0.6f)
        }
        out += c
        previous = letter
    }
    return out
}

/** The spacebar swipe: along the bar, lifting a little in the middle as a thumb does. */
private fun spacebarWaypoints(geometry: MiniatureGeometry): List<Offset> {
    val space = geometry.key(MiniKeyKind.SPACE)?.rect ?: return emptyList()
    val inset = space.width * 0.12f
    return listOf(
        Offset(space.left + inset, space.center.y),
        Offset(space.center.x, space.center.y - space.height * 0.15f),
        Offset(space.right - inset, space.center.y),
    )
}

/** The backspace swipe: off the key and leftwards over the letters beside it. */
private fun backspaceWaypoints(geometry: MiniatureGeometry): List<Offset> {
    val backspace = geometry.key(MiniKeyKind.BACKSPACE)?.rect ?: return emptyList()
    val unit = geometry.key("m")?.rect?.width ?: backspace.width / 1.5f
    val c = backspace.center
    return listOf(c, Offset(c.x - unit * 1.4f, c.y - backspace.height * 0.1f), Offset(c.x - unit * 2.6f, c.y))
}

/**
 * Draws one frame of the demo at [progress] through the loop: the stroke's
 * trail with the user's settings on it, the touch under the finger, and what
 * the gesture does to the text on the strip or the label on the spacebar.
 */
private fun DrawScope.drawGesture(
    kind: GestureDemoKind,
    settings: KeyboardSettings,
    kb: KbTheme,
    texts: DemoTexts,
    measurer: TextMeasurer,
    geometry: MiniatureGeometry,
    progress: Float,
) {
    val s = geometry.scale
    val sp = s / density
    val waypoints = when (kind) {
        GestureDemoKind.GLIDE -> glideWaypoints(geometry, texts.word)
        GestureDemoKind.SPACEBAR -> spacebarWaypoints(geometry)
        GestureDemoKind.BACKSPACE -> backspaceWaypoints(geometry)
    }
    if (waypoints.size < 2) return
    val gestureOn = when (kind) {
        GestureDemoKind.GLIDE -> settings.gestureTyping && settings.letterSwipeAction == LetterSwipeAction.TYPE_WORDS
        GestureDemoKind.SPACEBAR -> spaceSwipeShown(settings) != SpaceSwipeAction.NONE
        GestureDemoKind.BACKSPACE -> settings.backspaceSwipeDelete
    }
    val path = strokeThrough(waypoints)
    val strokeMs = (StrokeEnd - StrokeStart) * LoopMs
    // How far along the stroke the finger is (0..1), and how long ago it lifted.
    val down = progress in StrokeStart..StrokeEnd
    val u = ((progress - StrokeStart) / (StrokeEnd - StrokeStart)).coerceIn(0f, 1f)
    val sinceLift = if (progress > StrokeEnd) (progress - StrokeEnd) * LoopMs else 0f
    if (progress < StrokeStart) {
        drawEffects(kind, settings, kb, texts, measurer, geometry, path, 0f, false, gestureOn, sp)
        return
    }

    // The text the gesture acts on is drawn under the trail, as it is on the keyboard.
    drawEffects(kind, settings, kb, texts, measurer, geometry, path, u, down, gestureOn, sp)

    // The trail: the comet the keyboard draws, head under the finger, tail
    // thinning and fading as each sample ages past the trail length.
    val head = (settings.gesture.trailWidthDp * s).coerceAtLeast(1f)
    val tail = head * TrailTailShare
    val trailMs = settings.gesture.trailDurationMs.toFloat().coerceAtLeast(1f)
    val opacity = settings.gesture.trailOpacity * (if (gestureOn) 1f else OffAlpha)
    if (opacity > 0f) {
        val headDistance = u * path.length
        var previousAlive: Offset? = null
        var previousLife = 0f
        for (i in path.points.indices) {
            val d = path.distances[i]
            if (d > headDistance) break
            val age = (headDistance - d) / path.length * strokeMs + sinceLift
            val life = 1f - age / trailMs
            val point = path.points[i]
            if (life <= 0f) {
                previousAlive = null
                continue
            }
            val from = previousAlive
            if (from != null) {
                val mid = (life + previousLife) / 2f
                drawLine(
                    color = kb.gestureTrail.copy(alpha = (opacity * mid).coerceIn(0f, 1f)),
                    start = from,
                    end = point,
                    strokeWidth = tail + (head - tail) * mid,
                    cap = StrokeCap.Round,
                )
            }
            previousAlive = point
            previousLife = life
        }
        // The finger itself, while it is down: the keyboard draws no finger,
        // but a demo with nothing pressing the keys reads as a scribble.
        if (down) {
            val finger = pointAt(path, headDistance)
            drawCircle(kb.keyText.copy(alpha = 0.14f), radius = 10f * s, center = finger)
        }
    }

    // The pill with the word so far, above the finger (gesture.wordPreview).
    if (kind == GestureDemoKind.GLIDE && down && gestureOn && settings.gesture.wordPreview) {
        val typed = lettersPassed(path, u, texts.word)
        if (typed.isNotEmpty()) {
            val finger = pointAt(path, u * path.length)
            val style = TextStyle(fontSize = (settings.gesture.wordPreviewFontSp * sp).sp)
            val layout = measurer.measure(typed, style, maxLines = 1)
            val padX = 10f * s
            val padY = 5f * s
            val w = layout.size.width + 2 * padX
            val h = layout.size.height + 2 * padY
            val board = geometry.board
            val cx = (finger.x + settings.gesture.wordPreviewOffsetXDp * s).coerceIn(board.left + w / 2f, board.right - w / 2f)
            val cy = (finger.y - settings.gesture.wordPreviewOffsetYDp * s).coerceAtLeast(board.top + h / 2f)
            val rect = Rect(Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
            drawRoundRect(
                settings.gesture.wordPreviewBackground?.let { Color(it.toInt()) } ?: kb.popup,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(h / 2f),
            )
            drawCentred(layout, rect, settings.gesture.wordPreviewTextColor?.let { Color(it.toInt()) } ?: kb.popupText)
        }
    }
}

/** The point [distance] along [path], interpolated between its samples. */
private fun pointAt(path: StrokePath, distance: Float): Offset {
    val d = distance.coerceIn(0f, path.length)
    var i = 1
    while (i < path.distances.size - 1 && path.distances[i] < d) i++
    val d0 = path.distances[i - 1]
    val d1 = path.distances[i]
    val t = if (d1 > d0) ((d - d0) / (d1 - d0)).coerceIn(0f, 1f) else 0f
    val a = path.points[i - 1]
    val b = path.points[i]
    return Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
}

/** The letters of [word] whose keys the finger has reached at [u] along the stroke. */
private fun lettersPassed(path: StrokePath, u: Float, word: String): String {
    val headDistance = u * path.length
    // Waypoints include the loop points on a doubled letter, so count letters
    // by walking the word and the waypoints together.
    var waypoint = 0
    var previous: Char? = null
    val out = StringBuilder()
    for (letter in word) {
        if (letter == previous) waypoint += 4
        if (waypoint >= path.waypointDistances.size) break
        if (path.waypointDistances[waypoint] <= headDistance + 0.5f) out.append(letter) else break
        waypoint++
        previous = letter
    }
    return out.toString()
}

/**
 * The spacebar action the demo shows: the short swipe's when it moves the
 * cursor or changes language, else the long swipe's, else whatever the short
 * swipe does — [SpaceSwipeAction.NONE] draws the stroke alone, dimmed.
 */
private fun spaceSwipeShown(settings: KeyboardSettings): SpaceSwipeAction {
    val short = settings.spaceShortSwipe
    val long = settings.spaceLongSwipe
    return when {
        short == SpaceSwipeAction.CURSOR || short == SpaceSwipeAction.LANGUAGE -> short
        long == SpaceSwipeAction.CURSOR || long == SpaceSwipeAction.LANGUAGE -> long
        settings.textEditing.spaceSwipeUpTouchpad -> SpaceSwipeAction.CURSOR
        else -> short
    }
}

/**
 * What the gesture does, drawn on the strip and the spacebar: the glided word
 * arriving letter by letter, the caret moving or the language name changing,
 * the last word selected and then gone.
 */
private fun DrawScope.drawEffects(
    kind: GestureDemoKind,
    settings: KeyboardSettings,
    kb: KbTheme,
    texts: DemoTexts,
    measurer: TextMeasurer,
    geometry: MiniatureGeometry,
    path: StrokePath,
    u: Float,
    down: Boolean,
    gestureOn: Boolean,
    sp: Float,
) {
    val s = geometry.scale
    val strip = geometry.strip?.rect
    val style = TextStyle(fontSize = (DemoTextSp * sp).sp)
    val textColor = kb.suggestionText
    val left = (strip?.left ?: geometry.board.left) + 10f * s
    fun lineAt(text: String): Pair<TextLayoutResult, Offset>? {
        if (strip == null || text.isEmpty()) return null
        val layout = measurer.measure(text, style, maxLines = 1)
        val top = Offset(left, strip.center.y - layout.size.height / 2f)
        drawText(layout, textColor, top)
        return layout to top
    }
    when (kind) {
        GestureDemoKind.GLIDE -> {
            // The strip carries the word as it is decoded, and keeps it once typed.
            if (!gestureOn) return
            val typed = if (down) lettersPassed(path, u, texts.word) else if (u >= 1f) texts.word else ""
            lineAt(typed)
        }
        GestureDemoKind.SPACEBAR -> {
            val action = spaceSwipeShown(settings)
            val space = geometry.key(MiniKeyKind.SPACE)?.rect ?: return
            if (down) {
                drawRoundRect(
                    kb.pressedKey.copy(alpha = 0.6f),
                    topLeft = space.topLeft,
                    size = space.size,
                    cornerRadius = CornerRadius(kb.keyRadiusDp * s),
                )
            }
            when (action) {
                SpaceSwipeAction.CURSOR -> {
                    val (layout, top) = lineAt(texts.line) ?: return
                    // The caret starts after the first word and rides the swipe to the end.
                    val firstWord = texts.line.indexOf(' ').takeIf { it >= 0 } ?: texts.line.length
                    val index = (firstWord + ((texts.line.length - firstWord) * u).roundToInt()).coerceIn(0, texts.line.length)
                    val x = top.x + layout.getHorizontalPosition(index, usePrimaryDirection = true)
                    drawLine(
                        kb.accent,
                        start = Offset(x, top.y),
                        end = Offset(x, top.y + layout.size.height),
                        strokeWidth = (1.5f * s).coerceAtLeast(1f),
                        cap = StrokeCap.Round,
                    )
                }
                SpaceSwipeAction.LANGUAGE -> {
                    lineAt(texts.line)
                    // The name on the bar slides out to the left as the next slides in.
                    val f = ((u - 0.35f) / 0.3f).coerceIn(0f, 1f)
                    val labelStyle = TextStyle(fontSize = (SpaceLabelSp * sp).sp)
                    val a = measurer.measure(texts.languageA, labelStyle, maxLines = 1)
                    val b = measurer.measure(texts.languageB, labelStyle, maxLines = 1)
                    val shift = space.width * 0.35f
                    val cy = space.center.y
                    drawText(a, kb.keyText.copy(alpha = 1f - f), Offset(space.center.x - a.size.width / 2f - f * shift, cy - a.size.height / 2f))
                    drawText(b, kb.keyText.copy(alpha = f), Offset(space.center.x - b.size.width / 2f + (1f - f) * shift, cy - b.size.height / 2f))
                }
                else -> lineAt(texts.line)
            }
        }
        GestureDemoKind.BACKSPACE -> {
            if (!gestureOn) {
                lineAt(texts.line)
                return
            }
            val line = texts.line
            val lastWord = line.substringAfterLast(' ')
            val n = lastWord.length
            // How much of the last word the swipe has covered so far: all of it
            // at once by the word, one letter at a time by the character.
            val reached = when (settings.textEditing.backspaceSwipeUnit) {
                BackspaceSwipeUnit.WORD -> if (u >= 0.4f) n else 0
                BackspaceSwipeUnit.CHARACTER -> ((u / 0.9f) * n).toInt().coerceIn(0, n)
            }
            val preview = settings.textEditing.backspaceSwipePreview
            if (down && preview) {
                // Selected first, deleted on the lift.
                val (layout, top) = lineAt(line) ?: return
                if (reached > 0) {
                    val start = line.length - reached
                    val x0 = top.x + layout.getHorizontalPosition(start, usePrimaryDirection = true)
                    val x1 = top.x + layout.getHorizontalPosition(line.length, usePrimaryDirection = true)
                    drawRect(kb.accent.copy(alpha = 0.35f), Offset(x0, top.y), Size(x1 - x0, layout.size.height.toFloat()))
                }
            } else {
                val gone = if (down) reached else if (u >= 1f) n else 0
                lineAt(line.dropLast(gone).trimEnd())
            }
        }
    }
}
