package com.wasimaster.wmkeyboard.ime.ui

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.core.theme.FrostNoise
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.theme.KeyShapeKind
import com.wasimaster.wmkeyboard.core.theme.WallpaperBackdrop
import com.wasimaster.wmkeyboard.core.theme.WallpaperBand
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Frosted glass on the keyboard (see `FrostSpec` for the model).
 *
 * Two mechanisms live here. The backdrop is the theme's background image,
 * baked once into a blurred copy that every glass surface samples at its own
 * place on the board: a key draws the slice of the blurred picture that lies
 * under it, then its own translucent colour, then a rim of light and a little
 * grain. Nothing is blurred per frame — the bake is one bitmap in the
 * background cache, and a surface's draw is an image shader with a matrix.
 *
 * The window is the other: on Android 12 and later the keyboard's window asks
 * the system to blur the app behind it, and that is the system compositor's
 * work rather than the keyboard's. [WindowFrostHost] is the seam to the
 * window, implemented by the service and faked by the settings app's preview.
 */

/**
 * The baked backdrop and where the board it belongs to is, in the compose
 * root's coordinates. One per [KeyboardThemeProvider]; the surfaces read it
 * inside their draw cache, so a bake landing or the board moving re-prepares
 * their paint and nothing recomposes.
 */
class FrostBackdrop {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var boardBounds by mutableStateOf(Rect.Zero)
}

/** Provided beside [LocalKbTheme]; a host with no board hands out an empty one. */
val LocalFrostBackdrop = staticCompositionLocalOf { FrostBackdrop() }

/**
 * Keeps [LocalFrostBackdrop]'s bitmap current for what the glass stands on —
 * the board image at [imagePath] or the wallpaper's band, as [kind] says — at
 * the size the board is drawn. Called by the board, which is the one place
 * that knows which picture is up and how big it is.
 *
 * An animated image that is playing has no single frame to bake, so the glass
 * over it is its tint alone; reduce motion stills the image, and then its
 * first frame is baked like any other.
 */
@Composable
internal fun FrostBackdropLoader(
    kb: KbTheme,
    imagePath: String?,
    kind: BackdropKind?,
    targetW: Int,
    targetH: Int,
) {
    val backdrop = LocalFrostBackdrop.current
    val frost = kb.frost
    val playing = kb.backgroundAnimated && !kb.reduceMotion
    val context = LocalContext.current
    // A wallpaper change on Android 12+ arrives as a configuration change
    // (the dynamic colours move with it), which is what re-bakes the band.
    val configuration = LocalConfiguration.current
    // The bake's inputs are snapped at the crossfade midpoint (see lerpFrost),
    // so a theme switch asks for one new bake, not one per frame.
    val blur = frost?.backdropBlur ?: 0f
    val saturation = frost?.saturation ?: 1f
    val brightness = frost?.brightness ?: 0f
    val band = frost?.wallpaperBand
    val dark = kb.dark
    // The theme's own colours, for a phone that publishes no wallpaper colours.
    val fallback = listOf(kb.board, kb.accent, kb.key).map { it.toArgb().toLong() and 0xFFFFFFFFL }
    LaunchedEffect(
        backdrop, kind, imagePath, playing, blur, saturation, brightness, band, dark, targetW, targetH,
        configuration,
    ) {
        backdrop.bitmap = when {
            frost == null || kind == null -> null
            kind == BackdropKind.IMAGE -> if (imagePath == null || playing) {
                null
            } else {
                BackgroundBitmapCache
                    .loadFrosted(imagePath, blur, saturation, brightness, targetW, targetH)
                    ?.asImageBitmap()
            }
            else -> WallpaperBackdrop
                .loadFrosted(context, band ?: WallpaperBand.BOTTOM, blur, saturation, brightness, targetW, targetH, dark, fallback)
                ?.asImageBitmap()
        }
    }
}

/** Records where the board is, for the surfaces to sample the backdrop against. */
internal fun Modifier.frostBoardBounds(backdrop: FrostBackdrop): Modifier =
    onGloballyPositioned { backdrop.boardBounds = it.boundsInRoot() }

/**
 * The slice of the backdrop under one surface, ready to draw: an image
 * shader whose matrix lays the board-sized bitmap over the board and moves
 * it back by the surface's own origin. Built in a draw cache's block, so the
 * draw pass is one `drawOutline`.
 */
class GlassPaint private constructor(private val outline: Outline, private val brush: Brush) {

    fun draw(scope: DrawScope) = with(scope) { drawOutline(outline, brush) }

    companion object {
        /**
         * Null until the backdrop has been baked and the board placed — the
         * surface then draws its tint alone, which is what it drew before.
         *
         * A liquid pane wraps the slice in [LiquidLens] where the phone can
         * run one; [size] and [lensRadiusPx] describe the pane the lens bends
         * around, as a rounded rectangle whatever the outline really is.
         */
        fun of(
            backdrop: FrostBackdrop,
            outline: Outline,
            originInRoot: Offset,
            frost: KbFrost? = null,
            size: Size = Size.Zero,
            lensRadiusPx: Float = 0f,
        ): GlassPaint? {
            val bitmap = backdrop.bitmap ?: return null
            val board = backdrop.boardBounds
            if (board.isEmpty || bitmap.width == 0 || bitmap.height == 0) return null
            // Centre-crop, the way the board itself draws the image.
            val scale = max(board.width / bitmap.width, board.height / bitmap.height)
            val dx = board.left + (board.width - bitmap.width * scale) / 2f - originInRoot.x
            val dy = board.top + (board.height - bitmap.height * scale) / 2f - originInRoot.y
            val slice = ImageShader(bitmap, TileMode.Clamp, TileMode.Clamp)
            slice.setLocalMatrix(
                android.graphics.Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                },
            )
            val shader = if (frost != null && frost.liquid && LiquidLens.available && size.minDimension > 0f) {
                LiquidLens.shader(slice, size, lensRadiusPx, frost)
            } else {
                slice
            }
            return GlassPaint(outline, ShaderBrush(shader))
        }
    }
}

/**
 * The per-pixel half of liquid glass: a runtime shader that samples the
 * backdrop slice through a thick lens. Near the edge of the pane the sample
 * is pulled toward the middle (a lens magnifies what it covers), red and blue
 * are pulled a little differently (the colours split where glass bends
 * light), and a band of light is added where the edge faces the light. The
 * middle of the pane samples straight through, so a wide key still reads as
 * glass rather than as a funhouse mirror.
 *
 * The pane is treated as a rounded rectangle of the surface's size and
 * [lensRadiusPx]; the real outline still clips the draw, so a pill or a
 * squircle gets a lens that follows its box, which at key sizes is close
 * enough. Android 13 and later only: `RuntimeShader` is the first way to run
 * a pixel program from a Paint.
 */
object LiquidLens {
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** How wide the bent band at the edge is, as a share of the pane's short side. */
    private const val EDGE_SHARE = 0.32f

    /** Light from the upper left, the direction the rim is lit from. */
    private const val LIGHT_X = -0.55f
    private const val LIGHT_Y = -0.835f

    /** Peak brightness of the specular band at full refraction. */
    private const val SPECULAR = 0.38f

    // language=AGSL
    private const val SOURCE = """
        uniform shader backdrop;
        uniform float2 size;
        uniform float radius;
        uniform float edge;
        uniform float refraction;
        uniform float dispersion;
        uniform float specular;
        uniform float2 light;

        float roundRect(float2 p, float2 half, float r) {
            float2 q = abs(p) - half + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        half4 main(float2 p) {
            float2 c = size * 0.5;
            float2 local = p - c;
            float inside = -roundRect(local, c, radius);
            float t = 1.0 - clamp(inside / edge, 0.0, 1.0);
            float bend = t * t;
            float e = 1.0;
            float2 n = normalize(float2(
                roundRect(local + float2(e, 0.0), c, radius) - roundRect(local - float2(e, 0.0), c, radius),
                roundRect(local + float2(0.0, e), c, radius) - roundRect(local - float2(0.0, e), c, radius)
            ) + float2(0.0001, 0.0));
            float2 shift = -n * bend * refraction * edge * 0.7;
            float split = dispersion * bend * edge * 0.12;
            float4 col;
            col.r = float4(backdrop.eval(p + shift + n * split)).r;
            col.g = float4(backdrop.eval(p + shift)).g;
            col.b = float4(backdrop.eval(p + shift - n * split)).b;
            col.a = 1.0;
            float lit = max(dot(n, light), 0.0);
            col.rgb += float3(bend * bend * lit * specular);
            return half4(col);
        }
    """

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun shader(slice: android.graphics.Shader, size: Size, lensRadiusPx: Float, frost: KbFrost): android.graphics.Shader {
        val short = size.minDimension
        return android.graphics.RuntimeShader(SOURCE).apply {
            setInputShader("backdrop", slice)
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("radius", lensRadiusPx.coerceIn(0f, short / 2f))
            setFloatUniform("edge", (short * EDGE_SHARE).coerceAtLeast(1f))
            setFloatUniform("refraction", frost.refraction)
            setFloatUniform("dispersion", frost.dispersion)
            setFloatUniform("specular", SPECULAR * frost.refraction.coerceAtLeast(0.25f))
            setFloatUniform("light", LIGHT_X, LIGHT_Y)
        }
    }
}

/**
 * The corner radius the lens bends around, for a pane of [kind] drawn at
 * [size]: the shapes that round themselves off their box (pill, circle, arch)
 * are half the short side, a square is none, and the rest read the radius
 * slider as the outline does.
 */
fun lensRadiusPx(kind: KeyShapeKind, radiusDp: Int, size: Size, density: Density): Float = when (kind) {
    KeyShapeKind.SHARP -> 0f
    KeyShapeKind.PILL, KeyShapeKind.CIRCLE, KeyShapeKind.ARCH -> size.minDimension / 2f
    else -> with(density) { radiusDp.dp.toPx() }.coerceAtMost(size.minDimension / 2f)
}

/** Width of the rim of light around a glass surface. */
private const val RIM_WIDTH_DP = 1f

/**
 * The rim: a hairline stroke just inside the outline, lit along the top-left
 * edge and shadowed along the bottom-right, fading through nothing across the
 * middle so a long bar is not ringed in a flat line. Stroked on an outline
 * one stroke-width smaller and drawn offset by half of it, so the whole line
 * lands inside the surface rather than straddling its edge.
 */
class RimPaint private constructor(
    private val outline: Outline,
    private val brush: Brush,
    private val stroke: Stroke,
    private val inset: Float,
) {
    fun draw(scope: DrawScope) = with(scope) {
        translate(inset, inset) { drawOutline(outline, brush, style = stroke) }
    }

    companion object {
        fun of(
            frost: KbFrost,
            shape: Shape,
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): RimPaint? {
            if (frost.rim <= 0f) return null
            val width = with(density) { RIM_WIDTH_DP.dp.toPx() }
            val inner = Size(size.width - width, size.height - width)
            if (inner.width <= 0f || inner.height <= 0f) return null
            val light = frost.rimLight.copy(alpha = frost.rimLight.alpha * frost.rim)
            val shadow = frost.rimShadow.copy(alpha = frost.rimShadow.alpha * frost.rim)
            val brush = Brush.linearGradient(
                0f to light,
                0.45f to light.copy(alpha = light.alpha * MID_RIM_ALPHA),
                0.55f to shadow.copy(alpha = shadow.alpha * MID_RIM_ALPHA),
                1f to shadow,
                start = Offset.Zero,
                end = Offset(inner.width, inner.height),
            )
            return RimPaint(shape.createOutline(inner, layoutDirection, density), brush, Stroke(width), width / 2f)
        }

        /** How much of the rim survives across the middle of the diagonal. */
        private const val MID_RIM_ALPHA = 0.15f
    }
}

/**
 * The grain: the shared noise tile repeated over the surface at the theme's
 * opacity. One shader for every surface on the board.
 */
class GrainPaint private constructor(private val outline: Outline, private val alpha: Float) {

    fun draw(scope: DrawScope) = with(scope) { drawOutline(outline, brush, alpha = alpha) }

    companion object {
        private val brush: Brush by lazy {
            ShaderBrush(ImageShader(FrostNoise.bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        }

        fun of(frost: KbFrost, outline: Outline): GrainPaint? =
            if (frost.grain <= 0f) null else GrainPaint(outline, frost.grain)
    }
}

/**
 * Dresses a surface as glass: the backdrop slice under whatever the surface
 * paints itself, and the rim and grain over it. Goes *before* the surface's
 * own `background` in the chain, so the tint composites over the glass and
 * the rim over the tint. [frost] null is the surface not being glass, and
 * costs the receiver returned as it is.
 *
 * A modifier node rather than `drawWithCache` so it can be used from any
 * modifier chain, composable or not. The node keeps its paints and rebuilds
 * them only when its size, its place on the board, or the backdrop changes;
 * the draw itself is three `drawOutline`s around the content.
 *
 * [sampleBackdrop] is false for a surface in a window of its own (a popup):
 * its root is not the keyboard's, so it cannot find its place on the board,
 * and it blurs what is behind it through the window instead — see
 * [PopupBlurBehind].
 */
fun Modifier.frostGlass(
    frost: KbFrost?,
    backdrop: FrostBackdrop,
    shape: Shape,
    sampleBackdrop: Boolean = true,
    /** What a liquid lens bends around; see [lensRadiusPx]. */
    lensKind: KeyShapeKind = KeyShapeKind.ROUNDED,
    lensRadiusDp: Int = 0,
): Modifier = if (frost == null) {
    this
} else {
    this.then(FrostGlassElement(frost, backdrop, shape, sampleBackdrop, lensKind, lensRadiusDp))
}

/** [frostGlass] for [surface], when the theme makes it glass; the backdrop comes from the composition. */
@Composable
fun Modifier.frostGlass(
    kb: KbTheme,
    shape: Shape,
    surface: FrostSurface,
    sampleBackdrop: Boolean = true,
): Modifier {
    val frost = kb.frost?.takeIf { it.has(surface) } ?: return this
    // The outline the lens bends around: each surface's own shape and radius.
    val (kind, radiusDp) = when (surface) {
        FrostSurface.KEYS, FrostSurface.MODIFIER_KEYS, FrostSurface.ENTER_KEY, FrostSurface.SPACE_BAR ->
            kb.keyShapeKind to kb.keyRadiusDp
        FrostSurface.CHIPS -> kb.chipShapeKind to kb.chipRadiusDp
        FrostSurface.CARDS -> kb.cardShapeKind to kb.chipRadiusDp
        FrostSurface.TOOLS -> kb.toolShapeKind to kb.toolRadiusDp
        FrostSurface.POPUPS -> kb.popupShapeKind to kb.popupRadiusDp
        FrostSurface.MENUS -> kb.menuShapeKind to kb.popupRadiusDp
        FrostSurface.BARS -> KeyShapeKind.SHARP to 0
    }
    return frostGlass(frost, LocalFrostBackdrop.current, shape, sampleBackdrop, kind, radiusDp)
}

private data class FrostGlassElement(
    val frost: KbFrost,
    val backdrop: FrostBackdrop,
    val shape: Shape,
    val sample: Boolean,
    val lensKind: KeyShapeKind,
    val lensRadiusDp: Int,
) : ModifierNodeElement<FrostGlassNode>() {
    override fun create() = FrostGlassNode(frost, backdrop, shape, sample, lensKind, lensRadiusDp)

    override fun update(node: FrostGlassNode) = node.update(frost, backdrop, shape, sample, lensKind, lensRadiusDp)

    override fun InspectorInfo.inspectableProperties() {
        name = "frostGlass"
    }
}

/** What the paints were last built for; any change rebuilds them. */
private data class GlassPaintKey(
    val size: Size,
    val origin: Offset,
    val bitmap: ImageBitmap?,
    val bounds: Rect,
    val layoutDirection: LayoutDirection,
)

private class FrostGlassNode(
    private var frost: KbFrost,
    private var backdrop: FrostBackdrop,
    private var shape: Shape,
    private var sample: Boolean,
    private var lensKind: KeyShapeKind,
    private var lensRadiusDp: Int,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, ObserverModifierNode {

    private var origin = Offset.Zero
    private var builtFor: GlassPaintKey? = null
    private var glass: GlassPaint? = null
    private var rim: RimPaint? = null
    private var grain: GrainPaint? = null

    fun update(
        frost: KbFrost,
        backdrop: FrostBackdrop,
        shape: Shape,
        sample: Boolean,
        lensKind: KeyShapeKind,
        lensRadiusDp: Int,
    ) {
        val rewatch = backdrop !== this.backdrop
        this.frost = frost
        this.backdrop = backdrop
        this.shape = shape
        this.sample = sample
        this.lensKind = lensKind
        this.lensRadiusDp = lensRadiusDp
        builtFor = null
        if (rewatch && isAttached) watchBackdrop()
        invalidateDraw()
    }

    override fun onAttach() {
        watchBackdrop()
    }

    /**
     * The backdrop's bitmap and bounds are snapshot state. Watched here, so a
     * bake landing redraws every pane without anything recomposing; the draw
     * below reads them again, which is what keeps the paints current.
     */
    private fun watchBackdrop() {
        observeReads {
            backdrop.bitmap
            backdrop.boardBounds
        }
    }

    override fun onObservedReadsChanged() {
        builtFor = null
        invalidateDraw()
        watchBackdrop()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        if (!sample) return
        val at = coordinates.positionInRoot()
        if (at != origin) {
            origin = at
            builtFor = null
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val bitmap = if (sample) backdrop.bitmap else null
        val bounds = if (sample) backdrop.boardBounds else Rect.Zero
        val key = GlassPaintKey(size, origin, bitmap, bounds, layoutDirection)
        if (key != builtFor) {
            val outline = shape.createOutline(size, layoutDirection, this)
            glass = if (bitmap != null) {
                GlassPaint.of(backdrop, outline, origin, frost, size, lensRadiusPx(lensKind, lensRadiusDp, size, this))
            } else {
                null
            }
            rim = RimPaint.of(frost, shape, size, layoutDirection, this)
            grain = GrainPaint.of(frost, outline)
            builtFor = key
        }
        glass?.draw(this)
        drawContent()
        rim?.draw(this)
        grain?.draw(this)
    }
}

/** The glass surface a key of this action belongs to; the same split the textures and colours use. */
internal fun frostSurfaceFor(action: com.wasimaster.wmkeyboard.core.layout.KeyAction): FrostSurface = when (action) {
    com.wasimaster.wmkeyboard.core.layout.KeyAction.Enter -> FrostSurface.ENTER_KEY
    com.wasimaster.wmkeyboard.core.layout.KeyAction.Space -> FrostSurface.SPACE_BAR
    com.wasimaster.wmkeyboard.core.layout.KeyAction.Text -> FrostSurface.KEYS
    else -> FrostSurface.MODIFIER_KEYS
}

// ---- the window ----

/**
 * How the keyboard asks the window it lives in to blur the app behind it.
 *
 * [blurAvailable] is whether that blur can run right now: Android 12 or later,
 * the phone's cross-window blur switched on (battery saver and some OEM
 * builds turn it off), and the keyboard's own power saving not in force. The
 * board watches it to draw itself at the theme's fallback opacity whenever
 * the answer is no, so the keys never sit on an unblurred app.
 *
 * The default does nothing and reports no blur, which is what a host with no
 * window of its own wants.
 */
interface WindowFrostHost {
    val blurAvailable: StateFlow<Boolean>

    /** Blur radius for the window's background, in pixels; 0 turns it off. */
    fun setBackgroundBlur(radiusPx: Int)
}

/** A host with no window to blur. */
object NoWindowFrost : WindowFrostHost {
    override val blurAvailable: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setBackgroundBlur(radiusPx: Int) = Unit
}

val LocalWindowFrost = staticCompositionLocalOf<WindowFrostHost> { NoWindowFrost }

/** True while the app behind the board is being blurred for [kb]. */
@Composable
fun windowFrostLive(kb: KbTheme): Boolean {
    val frost = kb.frost ?: return false
    if (!frost.windowBlur) return false
    val available by LocalWindowFrost.current.blurAvailable.collectAsState()
    return available
}

/**
 * Keeps the window's blur at what [kb] asks for while the docked board is on
 * screen, and turns it off on the way out. Only the docked frame calls this:
 * floating mode's window spans the whole screen, and a blur there would blur
 * the whole app.
 */
@Composable
fun WindowFrostEffect(kb: KbTheme) {
    val host = LocalWindowFrost.current
    val density = LocalDensity.current
    val frost = kb.frost
    val radiusPx = if (frost?.windowBlur == true) {
        with(density) { frost.windowBlurRadiusDp.dp.roundToPx() }
    } else {
        0
    }
    LaunchedEffect(host, radiusPx) { host.setBackgroundBlur(radiusPx) }
    DisposableEffect(host) {
        onDispose { host.setBackgroundBlur(0) }
    }
}

/**
 * Blurs what is behind a popup's own window — the keys under the alternates,
 * the board under a menu — on Android 12 and later, when the theme makes
 * [surface] glass and the phone can blur at all. Called at the top of the
 * popup's content, where [LocalView] is the popup's window view.
 *
 * Compose rebuilds the window's flags from its `PopupProperties` whenever
 * they change, so a popup that swaps properties while up must carry
 * `FLAG_BLUR_BEHIND` in both sets (see [alternatesPopupProperties]); the
 * radius set here survives, since nothing else writes it.
 */
@Composable
fun PopupBlurBehind(kb: KbTheme, surface: FrostSurface) {
    val frost = kb.frost?.takeIf { it.has(surface) } ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val available by LocalWindowFrost.current.blurAvailable.collectAsState()
    if (!available) return
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { frost.windowBlurRadiusDp.dp.roundToPx() }
    LaunchedEffect(view, radiusPx) {
        // The composition's view is the popup's inner compose view; the
        // window's own params sit on the ancestor the window manager holds.
        val window = generateSequence<View>(view) { it.parent as? View }
            .firstOrNull { it.layoutParams is WindowManager.LayoutParams }
            ?: return@LaunchedEffect
        val params = window.layoutParams as WindowManager.LayoutParams
        val flagged = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
        if (flagged && params.blurBehindRadius == radiusPx) return@LaunchedEffect
        params.blurBehindRadius = radiusPx
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        if (!window.isAttachedToWindow) return@LaunchedEffect
        val windowManager = window.context.getSystemService(WindowManager::class.java)
        runCatching { windowManager.updateViewLayout(window, params) }
    }
}

/**
 * Whether a popup for [surface] gets a blurred window behind it right now —
 * the same answer [PopupBlurBehind] acts on, for the places that build the
 * popup's properties before its content composes.
 */
@Composable
fun popupBlursBehind(kb: KbTheme, surface: FrostSurface): Boolean {
    val frost = kb.frost?.takeIf { it.has(surface) } ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val available by LocalWindowFrost.current.blurAvailable.collectAsState()
    return available && frost.windowBlurRadiusDp > 0
}

/**
 * The popup colour for [surface]: the theme's own where the window behind it
 * is blurred, and otherwise at least the theme's fallback opacity, so a
 * translucent sheet never sits on sharp keys.
 */
@Composable
fun KbTheme.popupFill(surface: FrostSurface): Color {
    val frost = frost?.takeIf { it.has(surface) } ?: return popup
    return if (popupBlursBehind(this, surface)) {
        popup
    } else {
        popup.copy(alpha = max(popup.alpha, frost.fallbackOpacity))
    }
}
