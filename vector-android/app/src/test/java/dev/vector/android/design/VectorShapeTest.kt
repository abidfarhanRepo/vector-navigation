package dev.vector.android.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The corner geometry: the radius scale, the pill, and the continuous corner.
 *
 * ## Why this is measured rather than asserted from the declarations
 *
 * `RoundedCornerShape(20.dp)` is a claim; the outline a `Shape` hands the
 * renderer is the fact. They can disagree in ways a screenshot does not show —
 * a corner that clamps to the wrong axis on a non-square tile, a squircle whose
 * radius is applied in dp where it should be px, a path that closes outside its
 * own box at a large radius and clips the content it was supposed to hold.
 *
 * The continuous corner is the interesting one. It is a *superellipse*
 * (`|x/a|^n + |y/b|^n = 1`, `n = 4`, ten segments per corner), not four circular
 * arcs, and the observable difference from a circular corner is where it sits at
 * the corner diagonal: a plain rounded rectangle passes through `r` from the
 * corner centre, and this curve passes through `1.19 r` — fuller, because its
 * curvature matches the straight edge where they meet instead of turning
 * abruptly. So the last test samples the real path with [PathMeasure] and
 * compares it against the circular arc at the same angle. That is the whole
 * contract of the shape, and it is not visible in its source.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE, so `Path` and `PathMeasure` are real. Robolectric's legacy graphics
// mode returns an empty path with zero bounds, which would let every geometry
// assertion below pass on a shape that draws nothing at all.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VectorShapeTest {

    private fun outline(shape: Shape, width: Float, height: Float, density: Float = 3f): Outline =
        shape.createOutline(Size(width, height), LayoutDirection.Ltr, Density(density))

    /** All four corner radii of a rounded outline, in px; zero for a plain rect. */
    private fun radii(outline: Outline): List<Float> = when (outline) {
        is Outline.Rounded -> listOf(
            outline.roundRect.topLeftCornerRadius.x,
            outline.roundRect.topRightCornerRadius.x,
            outline.roundRect.bottomRightCornerRadius.x,
            outline.roundRect.bottomLeftCornerRadius.x,
        )
        is Outline.Rectangle -> listOf(0f, 0f, 0f, 0f)
        else -> error("expected a rounded outline, got $outline")
    }

    private fun sized(w: Float, h: Float, r: Float) = "at ${w}x$h px with radius $r dp"

    // -----------------------------------------------------------------------
    // The scale
    // -----------------------------------------------------------------------

    /**
     * The scale is a scale.
     *
     * The bug this catches: two roles transposed while the tokens were being
     * written — `lg` and `xl` swapped is invisible in isolation and shows up as a
     * hero card that is rounder than the sheet above it. The list is checked for
     * completeness against the data class, so a radius role added later cannot
     * quietly sit outside the ordering.
     */
    @Test
    fun `the named radii grow monotonically`() {
        val shapes = listOf(
            "none" to VectorShapesLight.none,
            "xs" to VectorShapesLight.xs,
            "sm" to VectorShapesLight.sm,
            "md" to VectorShapesLight.md,
            "lg" to VectorShapesLight.lg,
            "xl" to VectorShapesLight.xl,
            "sheet" to VectorShapesLight.sheet,
            "xxl" to VectorShapesLight.xxl,
        )
        val declared = VectorShapes::class.java.methods
            .filter {
                it.parameterCount == 0 && it.returnType == Shape::class.java && it.name.startsWith("get")
            }
            .map { it.name.removePrefix("get").replaceFirstChar { c -> c.lowercase() } }
            .toSet()
        assertEquals(
            "radius roles declared by VectorShapes that this test does not order",
            declared - "pill",
            shapes.map { it.first }.toSet(),
        )
        val measured = shapes.map { (name, shape) ->
            name to radii(outline(shape, 400f, 400f)).distinct()
        }
        for ((name, r) in measured) {
            assertTrue("$name does not round all four corners equally: $r", r.size == 1)
        }
        for (i in 1 until measured.size) {
            val (previous, p) = measured[i - 1]
            val (current, c) = measured[i]
            assertTrue(
                "the radius scale is not monotonic: $previous = ${p.first()}px, $current = ${c.first()}px",
                c.first() > p.first(),
            )
        }
    }

    /**
     * A pill is half of its short side, at every size.
     *
     * The bug this catches: the pill token replaced by a fixed radius, or a
     * percentage applied to the wrong dimension — which turns a 48 dp chip into
     * a rectangle with soft corners and is obvious only at the sizes nobody
     * screenshots.
     */
    @Test
    fun `a pill is half of its short side`() {
        val cases = listOf(120f to 48f, 80f to 80f, 200f to 36f, 64f to 24f)
        for ((w, h) in cases) {
            val r = radii(outline(VectorShapesLight.pill, w, h))
            val expected = minOf(w, h) / 2f
            assertEquals(
                "a ${w}x$h pill should be a capsule with ${expected}px corners: corners $r",
                listOf(expected, expected, expected, expected),
                r,
            )
        }
    }

    // -----------------------------------------------------------------------
    // The continuous corner
    // -----------------------------------------------------------------------

    /**
     * The outline never leaves the box it was asked for.
     *
     * This is the assertion that makes the clamp observable. The builder
     * coerces the radius to half the short side, and without that coercion the
     * corner centres move outside the box and the sampled arc leaves it — a
     * `Path` that draws past its own bounds, which clips text on the tile above
     * it rather than rounding a corner. A radius *larger* than half the box is
     * included for exactly that reason, at three densities, because the
     * conversion from dp is where a clamp goes wrong.
     */
    @Test
    fun `a continuous corner stays inside its box at every size, radius and density`() {
        val cases = listOf(
            Triple(200f, 100f, 24f), Triple(96f, 96f, 28f), Triple(640f, 360f, 16f),
            // Larger than half the short side: must clamp, not invert.
            Triple(200f, 100f, 120f), Triple(96f, 96f, 100f), Triple(800f, 320f, 48f),
        )
        for (density in listOf(1f, 2.75f, 3.5f)) {
            for ((w, h, r) in cases) {
                val path = (outline(SquircleShape(r.dp), w, h, density) as? Outline.Generic)?.path
                    ?: error("a squircle must produce a path")
                val b = path.getBounds()
                val where = "${sized(w, h, r)} at ${density}x"
                val tolerance = 0.01f
                assertTrue(
                    "$where: bounds ${b.left},${b.top}..${b.right},${b.bottom} must match 0,0..$w,$h",
                    kotlin.math.abs(b.left) < tolerance && kotlin.math.abs(b.top) < tolerance &&
                        kotlin.math.abs(b.right - w) < tolerance && kotlin.math.abs(b.bottom - h) < tolerance,
                )
            }
        }
    }

    /**
     * A zero radius is a rectangle, not a degenerate path.
     *
     * The bug this catches: `continuous(0.dp)` — which a caller reaches for to
     * turn rounding off — building a path with zero-length arcs, i.e. a surface
     * that clips itself away to nothing.
     */
    @Test
    fun `a zero radius does not clip the surface`() {
        val outline = outline(SquircleShape(0.dp), 240f, 120f)
        assertTrue("a zero radius should draw a plain rectangle, got $outline", outline is Outline.Rectangle)
        val rect = (outline as Outline.Rectangle).rect
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(240f, rect.right, 0.01f)
        assertEquals(120f, rect.bottom, 0.01f)
    }

    /**
     * The corner is fuller than a circular arc, and it meets the edges as one.
     *
     * This is the only assertion in the file that reads the *curve*. The
     * superellipse and a circular corner share their tangent points — both
     * leave the straight edge at `r` from the corner centre — but along the
     * diagonal the superellipse is `1.19 r` from that centre where the circle is
     * `r`. That 19% is the continuous corner: it removes less material than a
     * circular arc of the same radius, and it is why a large card does not show
     * the visible "pinch" a circular corner has where the arc meets the edge.
     *
     * The bug this catches: the exponent collapsing to 2 (`n = 2` is the
     * ellipse — a plain rounded rectangle, and the measurement drops to exactly
     * `1.000 r`), a missing `pow(2/n)` in the parametrisation (which also lands
     * on a circle), or the corner sampled with its sign inverted so the curve
     * bulges outward past the box.
     *
     * Note the direction, because it is easy to get backwards: the curve is
     * *outside* the circular arc, not inside it. A continuous corner removes
     * less material than a circle of the same radius — "tighter to the box", in
     * `VectorShape.kt`'s own words — and the assertion is written in the
     * direction the shape actually goes.
     */
    /**
     * The outline is ONE closed contour, not four corner arcs.
     *
     * ## The defect this exists for
     *
     * Every corner used to begin with `moveTo`, which starts a new subpath — so
     * the outline was four disconnected quarter-arcs with the straight edges
     * never drawn. As a **clip** that erases the surface entirely: the search
     * results panel rendered nothing at all on the emulator while its six rows
     * were present in the semantics tree, 377 px down the screen and fully
     * hit-testable. As a **fill** it paints four slivers.
     *
     * The two tests above could not see it. They sample the path's POINTS, and
     * a degenerate path still has every point in the right place — the corner
     * arcs sit exactly where the superellipse puts them. What is missing is the
     * connection between them, which only a measure of the whole contour
     * reveals.
     *
     * So: the path's length must be at least the perimeter's straight edges plus
     * four quarter-arcs. The broken version measured about a third of that, and
     * the threshold is generous enough to survive any reasonable segment count.
     */
    @Test
    fun `a continuous corner is one closed contour, not four arcs`() {
        for (radiusDp in listOf(8f, 20f, 32f)) {
            val size = Size(300f, 200f)
            val density = Density(2f)
            val shape = VectorShapesLight.continuous(radiusDp.dp)
            val path = outlinePath(shape, size, density)
            val measure = PathMeasure().apply { setPath(path, false) }
            val r = radiusDp * density.density
            val straightEdges = 2f * (size.width - 2f * r) + 2f * (size.height - 2f * r)
            val quarterArcs = 4f * (0.9f * r)   // a superellipse quarter is ~0.9r long
            val expected = straightEdges + quarterArcs
            assertTrue(
                "r=$radiusDp dp: the outline measures ${"%.0f".format(measure.length)} px, " +
                    "but its straight edges alone are ${"%.0f".format(straightEdges)} px — " +
                    "the corners are not connected, so the path cannot clip or fill anything",
                measure.length >= expected * 0.9f,
            )
            // And it is closed, so a fill has no gap to leak through.
            // (`asAndroidPath`, not a local named `android` — that shadows the
            // package name and makes the next line unresolvable.)
            val androidPath = path.asAndroidPath()
            val androidMeasure = android.graphics.PathMeasure().apply { setPath(androidPath, false) }
            assertTrue(
                "r=$radiusDp dp: the outline is not a closed contour",
                androidMeasure.isClosed,
            )
        }
    }

    private fun outlinePath(shape: Shape, size: Size, density: Density): Path {
        val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
        assertTrue("a continuous corner must produce a path, not a rectangle", outline is Outline.Generic)
        return (outline as Outline.Generic).path
    }

    @Test
    fun `a continuous corner is fuller than a circular one and never square`() {
        val w = 200f
        val h = 100f
        val r = 24f
        val path = (outline(SquircleShape(r.dp), w, h, density = 1f) as Outline.Generic).path
        val profile = cornerProfile(path, w, r)

        val diagonal = profile.rhoAt(45f)
        assertTrue(
            "at the diagonal the corner is ${"%.3f".format(diagonal)} r from the corner centre; " +
                "a circular arc of the same radius would be 1.000 r, and a square corner 1.414 r",
            diagonal > 1.03f && diagonal < 1.25f,
        )
        for (end in listOf(0f, 90f)) {
            val reach = profile.rhoAt(end)
            assertTrue(
                "at ${end}deg the corner is ${"%.3f".format(reach)} r from the corner centre; it must " +
                    "meet the straight edge at the same point a circular arc does (1.000 r)",
                reach < 1.02f,
            )
        }
        assertTrue(
            "a sampled point reached ${"%.3f".format(profile.max)} r; past 1.30 r the corner is " +
                "closer to a square than to a continuous curve",
            profile.max < 1.30f,
        )
        // The control: the same measurement on the degenerate exponent (n = 2,
        // the ellipse the KDoc names) has to read a circle. Without this, a
        // profile that always returned the same number would pass the assertion
        // above for the wrong reason.
        val degenerate = (
            outline(SquircleShape(r.dp, exponent = 2f), w, h, density = 1f) as Outline.Generic
            ).path
        val circle = cornerProfile(degenerate, w, r).rhoAt(45f)
        assertTrue(
            "with n = 2 the corner must measure as a circular arc (1.000 r); it measured " +
                "${"%.3f".format(circle)}",
            circle in 0.98f..1.02f,
        )
    }

    /**
     * The distance from the top-right corner's centre for every point on the
     * produced path, as a multiple of the radius, keyed by the angle it sits at.
     */
    private class Profile(private val points: List<Pair<Float, Float>>) {
        /** The farthest sample within [degrees] of the requested angle. */
        fun rhoAt(degrees: Float): Float =
            points.filter { kotlin.math.abs(it.first - degrees) <= 2f }
                .maxOfOrNull { it.second }
                ?: error("no corner samples near ${degrees}deg")

        val max: Float get() = points.maxOf { it.second }
    }

    /**
     * Walks the produced path with [PathMeasure] and reports each point's
     * position relative to the top-right corner centre: the angle in degrees
     * (0 = along the straight edge, 90 = up the side) and the distance as a
     * multiple of the radius.
     */
    private fun cornerProfile(path: Path, width: Float, radius: Float): Profile {
        val measure = PathMeasure()
        measure.setPath(path, forceClosed = false)
        val length = measure.length
        val cx = width - radius
        val cy = radius
        val points = mutableListOf<Pair<Float, Float>>()
        val samples = 4000
        for (i in 0..samples) {
            val p = measure.getPosition(length * i / samples)
            val dx = p.x - cx
            val dy = cy - p.y
            // Only the quadrant the top-right corner owns, and only points close
            // enough to belong to the corner rather than to the straight edges.
            if (dx < 0f || dy < 0f) continue
            val rho = hypot(dx, dy)
            if (rho > radius * 1.35f) continue
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angle > 90f) continue
            points += angle to rho / radius
        }
        return Profile(points)
    }
}
