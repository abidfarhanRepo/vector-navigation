package dev.vector.android.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.vector.android.VectorTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Corner radii and elevation.
 *
 * ## The radius scale
 *
 * The reference audit measured Corner's cards at an arc spanning ~109 px at 3×,
 * i.e. roughly **30–36 dp** — very large, continuous corners (audit §4). The
 * brief asks for "large continuous/squircle-like rounding (approximately
 * 16–24 dp depending on size)". Vector therefore uses 16–28 dp, in the brief's
 * band rather than the reference's larger one, because Vector's surfaces are
 * often *dense* (a lane strip, a speed dial, a step row) and a 32 dp corner on a
 * 56 dp row leaves no straight edge at all.
 *
 * The rule of thumb encoded in the named roles: **a surface's radius is a
 * function of its size**, and a control that is a pill is a pill.
 *
 * ## Continuous corners, not circular ones
 *
 * [Shapes.continuous] builds a superellipse rather than four circular arcs. A
 * circular corner has a curvature discontinuity where the arc meets the
 * straight edge, which is visible on a large surface as a subtle "pinch". The
 * reference's cards do not have it, and neither do Vector's large surfaces now.
 * Small controls keep plain [RoundedCornerShape] — the difference is not
 * resolvable below about 12 dp and a real circle is cheaper to rasterise.
 */
data class VectorShapes(
    /** A flat surface that is not a card: a full-bleed band, a scrim. */
    val none: Shape,
    /** Small controls: badges, tiny tiles, input wells. */
    val xs: Shape,
    /** Buttons, chips at their largest, small cards. */
    val sm: Shape,
    /** The default card. */
    val md: Shape,
    /** A large card, an image tile in a grid. */
    val lg: Shape,
    /** A hero card, an inset panel. */
    val xl: Shape,
    /** A bottom sheet, a full-width modal surface. */
    val sheet: Shape,
    /** The largest surface: an onboarding card, a dialog. */
    val xxl: Shape,
    /** A pill: any control whose height it is half of. */
    val pill: Shape,
    /**
     * The continuous-corner builder for large surfaces.
     *
     * Exposed as a function rather than baked into the roles above because the
     * path depends on the surface's pixel size, which the role cannot know.
     * `VectorCard` and `VectorSheet` call it; a screen should not need to.
     */
    val continuous: (Dp) -> Shape,
)

internal val VectorShapesLight: VectorShapes = VectorShapes(
    none = RoundedCornerShape(VectorTokens.Radius.r0),
    xs = RoundedCornerShape(VectorTokens.Radius.r8),
    sm = RoundedCornerShape(VectorTokens.Radius.r12),
    md = RoundedCornerShape(VectorTokens.Radius.r16),
    lg = RoundedCornerShape(VectorTokens.Radius.r20),
    xl = RoundedCornerShape(VectorTokens.Radius.r24),
    sheet = RoundedCornerShape(VectorTokens.Radius.r28),
    xxl = RoundedCornerShape(VectorTokens.Radius.r32),
    pill = RoundedCornerShape(percent = 50),
    continuous = { r -> SquircleShape(r) },
)

/**
 * A superellipse with corner radius [radius].
 *
 * `|x/a|^n + |y/b|^n = 1` with `n = 4`. At `n = 2` this is an ellipse and the
 * shape degenerates to a plain rounded rectangle; at `n = 4` the corner leaves
 * the straight edge with matching curvature, which is the "continuous" look.
 * `n = 5` (Apple's approximation) is slightly softer still; 4 was chosen
 * because at Vector's radii the difference is under a pixel and 4 keeps the
 * corner tighter to the box, which matters when two tiles sit 4 dp apart.
 *
 * Each corner is sampled with 10 segments. That is enough for a smooth arc at
 * any density this app runs at (the chord error at r = 28 dp, 3× density is
 * ~0.06 px) and keeps the path allocation small — this runs once per size
 * change, not per frame, because the built path is cached.
 */
internal class SquircleShape(
    private val radius: Dp,
    private val exponent: Float = 4f,
) : Shape {

    private var cachedSize: Size? = null
    private var cachedPath: Path? = null

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }.coerceAtMost(min(size.width, size.height) / 2f)
        if (r <= 0f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }
        cachedSize?.let { if (it == size) return Outline.Generic(cachedPath!!) }
        val path = buildPath(size, r)
        cachedSize = size
        cachedPath = path
        return Outline.Generic(path)
    }

    private fun buildPath(size: Size, r: Float): Path {
        val w = size.width
        val h = size.height
        val p = Path()
        // ## The bug this flag exists to prevent
        //
        // Each corner used to start with `moveTo`, which began a NEW SUBPATH at
        // every corner — so the outline was four disconnected quarter-arcs with
        // the straight edges never drawn, and `close()` closed only the last one.
        // As a FILL that paints four slivers; as a CLIP it erases the surface
        // entirely. The symptom was a card whose contents were laid out, hit-
        // testable and announced to a screen reader while nothing was painted at
        // all: the search results panel was invisible on the emulator with its
        // six rows present in the semantics tree, 377 px down the screen.
        //
        // One `moveTo` for the whole path, `lineTo` for every point after it —
        // which also draws the straight edges, because consecutive corners are
        // connected by a single line segment.
        var started = false
        fun corner(cx: Float, cy: Float, sx: Float, sy: Float, from: Float, to: Float) {
            val steps = 10
            for (i in 0..steps) {
                val t = from + (to - from) * i / steps
                val x = cx + sx * r * abs(cos(t)).pow(2f / exponent)
                val y = cy + sy * r * abs(sin(t)).pow(2f / exponent)
                if (!started) {
                    p.moveTo(x, y)
                    started = true
                } else {
                    p.lineTo(x, y)
                }
            }
        }
        val half = (PI / 2).toFloat()
        // Clockwise from the top edge's left end.
        corner(w - r, r, 1f, -1f, half, 0f)      // top-right
        corner(w - r, h - r, 1f, 1f, 0f, half)   // bottom-right
        corner(r, h - r, -1f, 1f, half, 0f)      // bottom-left
        corner(r, r, -1f, -1f, 0f, half)         // top-left
        p.close()
        return p
    }
}

/**
 * Elevation.
 *
 * The reference separates surfaces with a **soft, wide, very low-opacity**
 * shadow plus a hairline — not with a hard edge and not with contrast (audit
 * §4). Compose's [shadow] maps to Android's elevation, whose ambient/spot
 * colours are tunable, so the softness comes from the colours rather than from
 * a bespoke blur pass:
 *
 * * the **spot** colour is the darker of the two, because it is what a surface
 *   casts directly beneath itself;
 * * the **ambient** colour is much lighter and slightly warm, because it is
 *   what the surface scatters into the field around it.
 *
 * Both are translucent charcoal rather than pure black, so a card's shadow on
 * the warm cloud field stays warm. On the dark theme the shadows are stronger
 * and cooler: a dark surface on a dark field needs more separation than a light
 * one on a light field, which is the opposite of what an inversion would give.
 */
data class VectorElevation(
    val none: Dp,
    /** A resting card. */
    val card: Dp,
    /** A card that is pressed or hovered, and the active chip. */
    val raised: Dp,
    /** Floating chrome over the map: controls, the search field. */
    val floating: Dp,
    /** A bottom sheet, a dialog. */
    val sheet: Dp,
    val ambient: Color,
    val spot: Color,
)

internal val LightElevation = VectorElevation(
    none = 0.dp,
    card = 2.dp,
    raised = 4.dp,
    floating = 6.dp,
    sheet = 10.dp,
    ambient = Color(0x0F1A1C1E),
    spot = Color(0x1A1A1C1E),
)

internal val DarkElevation = VectorElevation(
    none = 0.dp,
    card = 3.dp,
    raised = 5.dp,
    floating = 8.dp,
    sheet = 12.dp,
    ambient = Color(0x33000000),
    spot = Color(0x4D000000),
)

/**
 * Apply an elevation level with Vector's shadow colours.
 *
 * A single helper so no call site has to remember the colours, and so the
 * shadow can be switched off in one place when a surface is being clipped by a
 * parent that would clip the shadow too.
 */
internal fun Modifier.vectorShadow(
    elevation: Dp,
    shape: Shape,
    colors: VectorElevation,
): Modifier = if (elevation <= 0.dp) this else shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = colors.ambient,
    spotColor = colors.spot,
)
