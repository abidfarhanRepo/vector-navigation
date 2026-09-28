package dev.vector.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.toArgb
import dev.vector.android.design.DarkColors
import dev.vector.android.design.DarkElevation
import dev.vector.android.design.LightColors
import dev.vector.android.design.LightElevation
import dev.vector.android.design.Ramp
import dev.vector.android.design.VectorColors
import dev.vector.android.design.VectorElevation

/**
 * The map's own icons: the vehicle, and the two ends of a route.
 *
 * ## Why the vehicle is not a circle any more
 *
 * It was two `circle` layers — a translucent halo and a blue dot with a white
 * ring. That is a **position** marker, and §9 asks for a **vehicle**:
 *
 * > The vehicle should feel anchored to the road rather than like a static
 * > icon floating above it.
 *
 * A dot could not be anchored to anything, because a dot has no orientation.
 * `drawPuck` has always been handed a bearing — `NavSession` emits
 * `Action.Puck(position, bearing)` on every frame and `drawPuck` wrote it into
 * the feature's properties — and **no layer ever read it**. The heading was
 * computed, transported, and discarded at the last step.
 *
 * Both references use a directional arrowhead or a car. Vector takes the
 * **arrowhead**, and this is a case where the reference products disagree and
 * the less pretty one is right: Google Maps draws a 3D car, which is charming
 * and whose orientation is ambiguous at a glance — a car seen from above is
 * nearly symmetric, so "which way is it pointing" takes a moment. Waze's
 * arrowhead answers that in peripheral vision, which is the only way a driver
 * looks at it.
 *
 * The disc under the chevron is not a return of the dot. A dot *is* its
 * position; the disc here is only the chevron's ground, which is what keeps a
 * pale arrow legible over a pale road, a dark park and a saturated ribbon of
 * route line at the same time. All of the orientation lives in the arrowhead.
 *
 * ## The marks and the design system
 *
 * Every colour in this file comes from `dev.vector.android.design`. These are
 * `android.graphics` bitmaps and cannot read `VectorTheme`, so they read the two
 * palettes `VectorTheme` is built from — [LightColors] and [DarkColors] — and
 * every mark the theme touches takes a `dark` flag to choose between them.
 *
 * What the theme moves is the **ground**: the drop shadow, the halo, the
 * hairline and the origin's ring, whose correct weight is a function of the
 * cartography underneath and of whether that cartography is a warm cloud field
 * or a near-black one. What it does not move is the **identity**: the cloud
 * plate, the charcoal outline and the brand cerulean are the same at noon and at
 * midnight, for the reason `MainActivity`'s marker constants already give — a
 * driver who has learned what a colour means must not have to relearn it at
 * dusk. A mark on a map is a physical object, like a road sign, and road signs
 * do not change colour at night either.
 *
 * ## Why these are bitmaps
 *
 * A MapLibre `symbol` layer needs a registered image, and `icon-rotate` on a
 * symbol is the only way to get a marker that turns with the vehicle in map
 * space (`icon-rotation-alignment: "map"`). Drawn here with
 * `android.graphics` rather than reusing [VectorIcons], which is Compose and
 * cannot render outside a composition — the shapes are deliberately close, but
 * these two icon sets have genuinely different hosts and pretending otherwise
 * would mean dragging a Compose renderer into the style layer.
 *
 * Sized in **dp against the real display density**, so the vehicle is the same
 * physical size on every phone. A pixel-sized marker would be a third as big
 * on a 3x panel.
 */
object VectorMarkers {

    /** Registered image ids. These are the strings the style refers to. */
    const val VEHICLE = "vehicle"
    const val ORIGIN = "origin"
    const val DESTINATION = "destination"
    const val CALLOUT_PILL = "callout-pill"

    /**
     * The image ids the warning glyphs are registered under.
     *
     * The SAME strings `Callouts.Icon` writes into a callout, on purpose: the
     * style's `icon-image` is `["get", "icon"]`, so an id that is written but
     * not registered renders nothing at all, silently. `CalloutStyleTest` walks
     * `Callouts.Icon.ALL` and fails if any value is missing from this list, which
     * is what makes that failure impossible rather than merely unlikely.
     */
    val WARN_IMAGES = listOf(
        dev.vector.geo.Callouts.Icon.SIGNAL,
        dev.vector.geo.Callouts.Icon.CAMERA_SPEED,
        dev.vector.geo.Callouts.Icon.CAMERA_RED_LIGHT,
    )

    /**
     * Every warning glyph, as `(image id, bitmap)`, in [WARN_IMAGES] order.
     *
     * The caller registers these with `MapLibreMap.setStyle`'s style: this
     * object draws bitmaps and knows nothing about MapLibre, and handing back a
     * list is what keeps the registration in one line at the call site —
     * four separate `addImage` calls is four places for the vocabulary and the
     * pictures to drift apart, and a warning whose image is never registered
     * renders as nothing at all.
     *
     * [dark] is the theme the style is being loaded for. `applyStyle` already
     * computes it (`ui.settings.resolvedTheme(systemInDark())`) and should pass
     * it; the default keeps the signature usable from anywhere else.
     */
    fun warningImages(
        metrics: DisplayMetrics,
        dark: Boolean = false,
    ): List<Pair<String, Bitmap>> = listOf(
        dev.vector.geo.Callouts.Icon.SIGNAL to warnSignal(metrics, dark),
        dev.vector.geo.Callouts.Icon.CAMERA_SPEED to warnCamera(metrics, LENS_SPEED, dark),
        dev.vector.geo.Callouts.Icon.CAMERA_RED_LIGHT to warnCamera(metrics, LENS_RED_LIGHT, dark),
    )

    // ---- the palette these marks are drawn with ---------------------------

    /**
     * The semantic roles for the theme being drawn.
     *
     * A function and not a `VectorTheme` read, because there is no composition
     * out here — these bitmaps are registered into a MapLibre style. The two
     * internal palettes are the same two objects the theme is built from, so a
     * role used here and a role used in a composable cannot drift.
     */
    private fun theme(dark: Boolean): VectorColors = if (dark) DarkColors else LightColors

    /**
     * The shadow colours for the theme being drawn.
     *
     * Separate from [theme] because a shadow is not a colour role: its correct
     * weight is a property of the ground it falls on, and the dark elevation
     * pair is deliberately heavier and cooler than the light one — a dark
     * surface on a dark field needs more separation than a light one on a light
     * field, which is the opposite of what an inversion would give.
     */
    private fun depth(dark: Boolean): VectorElevation = if (dark) DarkElevation else LightElevation

    /** [colour] at [alpha], for the translucent passes. */
    private fun veiled(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

    // The three values that describe the marks themselves. They do not move
    // with the theme — see the class KDoc — and they are the whole reason no
    // line below names a hex.

    /** The cloud plate: the badge, the chevron and the origin's core. */
    private val CLOUD = Ramp.cloud00.toArgb()

    /** Charcoal ink: outlines, the badge's nub. Never pure black. */
    private val CHARCOAL = Ramp.ink900.toArgb()

    /**
     * Brand cerulean, at its daylight weight.
     *
     * The *stable* brand value, used where the mark sits on the cloud plate: a
     * cloud plate is the same near-white in both themes, so the ink on it has to
     * hold its contrast in both. The daylight `primary` role is 4.9:1 against
     * it; the night role is designed for a dark surface and would be 2.9:1
     * there, under the 3:1 bar for a non-text mark. Marks that sit on the MAP
     * instead take the theme's `primary` role, which is lifted at night for a
     * darker ground.
     */
    private val BRAND = Ramp.cerulean600.toArgb()

    /**
     * The corner radius of [pill], in dp.
     *
     * Public because the caller has to tell MapLibre which part of the bitmap
     * may be stretched, and the answer is "everything except the two rounded
     * ends". Getting that wrong does not fail — it distorts the corners, which
     * looks like a rendering artefact rather than a mistake.
     *
     * 14 dp puts the box at 28 dp tall, between `VectorShapes.sm` (12 dp, a
     * small control) and `VectorShapes.md` (16 dp, a card) — it is a small
     * floating object, and the design system's own rule is that a surface's
     * radius is a function of its size. The drawn corner is the capsule, half
     * the box height, which is what this number describes to MapLibre.
     */
    const val PILL_RADIUS_DP = 14f

    /**
     * The pill bitmap's exact dimensions and corner radius, in image pixels.
     *
     * One function, because [pill], [pillStretch] and [pillContent] must agree
     * to the pixel. MapLibre validates the content box against the bitmap it is
     * given and throws `java.lang.Error: content area is invalid` out of JNI if
     * it does not fit — which, since images are registered during style load,
     * is a hard crash on the first frame and on every theme switch. Deriving
     * the three from one place is what stops a rounding difference between
     * `2 * r` as a float and `(2 * r).toInt()` from being a crash.
     */
    private fun pillBox(metrics: DisplayMetrics): Triple<Int, Int, Float> {
        val r = PILL_RADIUS_DP * metrics.density
        val stretch = (2f * metrics.density).coerceAtLeast(2f)
        val w = (2 * r + stretch).toInt().coerceAtLeast(8)
        val h = (2 * r).toInt().coerceAtLeast(8)
        return Triple(w, h, r)
    }

    /**
     * The stretchable band in the middle of [pill], in image pixels.
     *
     * Applied on BOTH axes by the caller. Vertically it never fires — the style
     * uses `icon-text-fit: width`, so the capsule's height is fixed — but the
     * axis cannot be left empty: MapLibre's JNI reads one float pair per axis
     * without checking that there is one, and an empty list crashes with
     * `ArrayIndexOutOfBoundsException: float[] offset=0 length=1 src.length=0`.
     */
    fun pillStretch(metrics: DisplayMetrics): Pair<Float, Float> {
        val (w, _, r) = pillBox(metrics)
        return r.coerceAtMost(w - 2f) to (r + 1f).coerceAtMost(w - 1f)
    }

    /**
     * Where the words go inside [pill], in image pixels: left, top, right, bottom.
     *
     * NOT the stretchable band, which is the mistake that was made first. The
     * content box is the area MapLibre has to fit the text into, so setting it
     * to the one-pixel repeat meant the label had to fit in one pixel and the
     * capsule was blown up around it — pills three hundred pixels wide with
     * "Left" floating in the middle, which reads as a design decision rather
     * than as a geometry error.
     *
     * The text area is the whole bitmap less half a corner radius at each end,
     * so the words never ride up onto the curve, and inset by a pixel top and
     * bottom so the box is strictly inside the image.
     */
    fun pillContent(metrics: DisplayMetrics): FloatArray {
        val (w, h, r) = pillBox(metrics)
        return floatArrayOf(r * 0.5f, 1f, w - r * 0.5f, h - 1f)
    }

    /**
     * The background of a world-space warning, as a stretchable capsule.
     *
     * ## Why this is a bitmap and not a halo
     *
     * A `symbol` layer can only draw a shape behind its text with `icon-image`,
     * and `icon-image` needs a registered image — this document has no sprite
     * and the glyph store serves one Latin range, so there is nothing to draw a
     * pill with unless the app makes one. The alternative, a thick `text-halo`,
     * gives a legible blob rather than an object, and a warning that does not
     * read as an object does not read as attached to the place it names.
     *
     * Stretched rather than scaled. MapLibre is told the one-pixel band in the
     * middle may repeat (`pillStretch`) and the rest may not, so "Left" and
     * "Exit Q3;Q5" get the same corner radius instead of one looking like a
     * squashed version of the other.
     *
     * ## Why it is the same in both themes
     *
     * §4.4's doctrine: what the driver ACTS on holds its meaning across day and
     * night. A warning about a bend is exactly that, so it is one object with
     * one appearance — dark with a light rim, which reads against a near-black
     * night ground and against a pale daylight one without either palette
     * having an opinion.
     *
     * ## Why the colours are still parameters
     *
     * `fill` and `border` stay arguments because the label that sits ON this
     * bitmap is coloured by the style document, not by this file, and the two
     * ends have to agree — a pill whose text is `CALLOUT_FILL` and whose plate
     * is a different charcoal is visibly two objects. `PillImageTest` pins the
     * drawn fill to the value the caller passed, so this is the one mark whose
     * pair the caller owns. The palette's values for it are
     * `LightColors.guidanceSurface` with `LightColors.onGuidanceMuted`, and the
     * `DarkColors` pair of the same two roles; both are dark with a light rim in
     * both themes, which is what the doctrine above asks for.
     *
     * What this file does own is the geometry the identity changed: the corner
     * radius ([PILL_RADIUS_DP], 14 dp rather than 13) and the rim, which is now
     * the design system's 1 dp hairline rather than a 1.2 dp stroke that read as
     * a border.
     */
    fun pill(metrics: DisplayMetrics, fill: Int, border: Int): Bitmap {
        val d = metrics.density
        val (w, h, _) = pillBox(metrics)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val inset = 1f * d
        val rect = android.graphics.RectF(inset, inset, w - inset, h - inset)
        val rr = (h / 2f) - inset
        cv.drawRoundRect(rect, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fill; style = Paint.Style.FILL
        })
        cv.drawRoundRect(rect, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = border
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
        })
        return bmp
    }

    /**
     * The vehicle: a cloud chevron on a cerulean disc, pointing up at rotation 0.
     *
     * Up, because `icon-rotate` takes the bearing directly and a bearing of 0
     * is north. Any other convention would need a fudge factor at the call
     * site, and a fudge factor in a heading is how a marker ends up 90 degrees
     * out on one code path and right on the other. The chevron's geometry is
     * unchanged from the rounded arrowhead this marker has always been, for the
     * reason the class KDoc gives.
     *
     * ## The three treatments, and what each is for
     *
     *  - The **halo** is the disc's own colour, blurred: it separates the puck
     *    from a route line that is the same hue without drawing a ring around
     *    it.
     *  - The **disc** takes the theme's `primary` role, so the puck is the
     *    deeper cerulean of a daylight map and the lifted one of a night map.
     *  - The **outline** is charcoal and the chevron is cloud. A heavy outline
     *    is the only treatment that survives a night map, a daylight map, a
     *    park, a motorway and the marker's own route line (Waze's marker has
     *    the same border for the same reason), and it is what keeps a pale
     *    chevron legible on the lighter night disc.
     *
     * Geometry, in dp: a 52 dp canvas, a 15 dp disc radius, a 20 dp halo radius
     * blurred by 5 dp, and an 11 dp arrowhead inside it — 40 dp of arrow inside
     * 52 dp of canvas, with the margin left for the halo.
     *
     * [fill] and [stroke] are retained because the call sites pass them
     * (`MainActivity`'s marker constants) and this file cannot change those;
     * the palette supersedes both, and they can go once the call sites stop
     * passing them.
     */
    fun vehicle(metrics: DisplayMetrics, fill: Int, stroke: Int, dark: Boolean = false): Bitmap {
        val d = metrics.density
        val size = (VEHICLE_DP * d).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val c = size / 2f
        val brand = theme(dark).primary.toArgb()

        cv.drawCircle(c, c, VEHICLE_HALO_DP * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brand
            alpha = HALO_VEIL
            setShadowLayer(VEHICLE_GLOW_DP * d, 0f, 0f, veiled(brand, GLOW_VEIL))
        })
        cv.drawCircle(c, c, VEHICLE_DISC_DP * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brand
            style = Paint.Style.FILL
        })

        // A rounded triangle: apex at the top, base tucked in so the trailing
        // edge is concave. The concavity is what makes it read as an arrowhead
        // rather than as a triangle, and it is the only part of the shape that
        // is doing symbolic work.
        val r = CHEVRON_DP * d
        val path = Path().apply {
            moveTo(c, c - r)
            quadTo(c + r * 0.20f, c - r * 0.62f, c + r * 0.78f, c + r * 0.72f)
            quadTo(c + r * 0.30f, c + r * 0.30f, c, c + r * 0.42f)
            quadTo(c - r * 0.30f, c + r * 0.30f, c - r * 0.78f, c + r * 0.72f)
            quadTo(c - r * 0.20f, c - r * 0.62f, c, c - r)
            close()
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CHARCOAL
            style = Paint.Style.STROKE
            strokeWidth = CHEVRON_STROKE_DP * d
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        })
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CLOUD
            style = Paint.Style.FILL
        })
        return bmp
    }

    /**
     * Where the route starts: a small solid ring with a cloud-white core.
     *
     * Small and quiet. The origin is where the driver already is, so it is
     * context rather than information — and while navigating the vehicle marker
     * sits on top of it anyway.
     *
     * The ring takes the theme's `primary` role rather than an ink role,
     * because it is the one shape here that sits directly on the map: charcoal
     * would vanish into a night ground at the moment the ring stopped being
     * distinguishable from the daylight one. The brand role is a deep cerulean
     * against a pale map and a lifted one against a dark map, and it is the same
     * hue as the route line this point terminates — the origin and the
     * destination are the two ends of one blue line, and they are drawn in its
     * colour.
     *
     * Geometry, in dp: a 22 dp circle, a 9.5 dp ring radius and a 6 dp core, so
     * the annulus is 3.5 dp thick and the core is a 12 dp pinprick. It reads as
     * a ring rather than a dot down to about half zoom — below that the core
     * and its surrounding ink average into one small dark mark, which is still
     * the right thing for a point the driver is standing on.
     *
     * [fill] and [ring] are retained because the call sites pass them; the
     * palette supersedes them.
     */
    fun origin(metrics: DisplayMetrics, fill: Int, ring: Int, dark: Boolean = false): Bitmap {
        val d = metrics.density
        val size = (ORIGIN_DP * d).toInt().coerceAtLeast(12)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val c = size / 2f
        cv.drawCircle(c, c, ORIGIN_RING_DP * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme(dark).primary.toArgb(); style = Paint.Style.FILL
        })
        // The core is drawn rather than punched out: a transparent hole would
        // show the route line through the mark, and the route starts underneath
        // it.
        cv.drawCircle(c, c, ORIGIN_CORE_DP * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CLOUD; style = Paint.Style.FILL
        })
        return bmp
    }

    /**
     * Where the route ends: a circular badge on a short pointer.
     *
     * **Vector drew nothing here at all.** `drawRoute` put a bare LineString on
     * the map and that was the whole of it, so in
     * `v4-evidence/vector/before-03-preview.png` the driver is offered three
     * routes with no marker at either end — which end is the destination was
     * left to inference from the shape of the line.
     *
     * ## Why a badge and not a teardrop
     *
     * The teardrop was the reference products' shape and it was doing a
     * shape's worth of work to say a word: it read as a pin because every map
     * has drawn pins, not because anything about Vector's identity was in it.
     * The badge is the same idea in this product's language — a cloud surface,
     * a hairline, a soft shadow, a brand mark in the middle — with a short
     * tapered nub under it so the anchor point is still unmistakable. The nub
     * is doing the work the teardrop's whole silhouette was doing, and the
     * surface above it is free to be a surface.
     *
     * ## Geometry, in dp
     *
     * A 30×40 dp canvas. The badge is a 13.5 dp radius circle centred at
     * (15, 15), so it occupies the top 30 dp square with 1.5 dp of margin for
     * the shadow; the nub runs from the circle's lower arc to a point at
     * (15, 39), which is 1 dp above the bottom edge. The teardrop's point sat
     * 1.2 dp above that edge, so the style's `icon-anchor: "bottom"` puts the
     * coordinate in the same place it always has: the nub's tip is the anchor,
     * and a badge whose centre was on the destination would be pointing at a
     * building half a block away.
     *
     * The hairline is the design system's 1 dp, the shadow is the theme's spot
     * colour blurred by 1.8 dp and offset 0.9 dp, and the mark in the middle is
     * a 4.8 dp brand disc. That disc is [BRAND] rather than the theme's
     * `primary`: it sits on the cloud plate, which is white in both themes, so
     * it has to hold 4.9:1 against white in both.
     *
     * The shadow is cut off below the nub by the bitmap's own edge, which is
     * deliberate: a shadow that spilled past the anchor would put the mark's
     * weight below the coordinate it marks, and the anchor is the one thing
     * about a pin that has to be exact.
     *
     * [fill] and [ring] are retained because the call sites pass them; the
     * palette supersedes them.
     */
    fun destination(metrics: DisplayMetrics, fill: Int, ring: Int, dark: Boolean = false): Bitmap {
        val d = metrics.density
        val w = (DEST_W_DP * d).toInt().coerceAtLeast(16)
        val h = (DEST_H_DP * d).toInt().coerceAtLeast(20)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val cx = w / 2f
        val cy = DEST_CY_DP * d
        val rBadge = DEST_RADIUS_DP * d
        val tip = (DEST_H_DP - DEST_TIP_INSET_DP) * d

        // The nub is traced clockwise on screen, the direction `addCircle`
        // winds an oval, and then UNIONed with it rather than left as a second
        // contour in the same path. Two contours of opposite winding cancel
        // under the non-zero fill rule, which punches a hole exactly where the
        // neck has to be solid, and which way Skia winds an oval is not a thing
        // to leave a pin's neck resting on.
        val nubBaseY = cy + rBadge * 0.82f
        val badge = Path().apply { addCircle(cx, cy, rBadge, Path.Direction.CW) }
        val nub = Path().apply {
            moveTo(cx + DEST_NUB_HALF_DP * d, nubBaseY)
            quadTo(cx + DEST_NUB_CTRL_DP * d, tip - DEST_NUB_LIFT_DP * d, cx, tip)
            quadTo(cx - DEST_NUB_CTRL_DP * d, tip - DEST_NUB_LIFT_DP * d, cx - DEST_NUB_HALF_DP * d, nubBaseY)
            close()
        }
        val pin = Path().apply { op(badge, nub, Path.Op.UNION) }

        cv.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CLOUD
            style = Paint.Style.FILL
            setShadowLayer(DEST_SHADOW_DP * d, 0f, DEST_SHADOW_DY_DP * d, depth(dark).spot.toArgb())
        })
        cv.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme(dark).borderStrong.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            strokeJoin = Paint.Join.ROUND
        })
        cv.drawCircle(cx, cy, DEST_MARK_DP * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BRAND; style = Paint.Style.FILL
        })
        return bmp
    }

    // ---- geometry, in dp --------------------------------------------------

    /** Vehicle canvas: 40 dp of arrowhead with the margin the halo needs. */
    private const val VEHICLE_DP = 52f

    /** The disc the chevron sits on, and the halo that separates it. */
    private const val VEHICLE_DISC_DP = 15f
    private const val VEHICLE_HALO_DP = 20f

    /** The halo's blur radius. Soft, wide, low-opacity — the audit's shadow. */
    private const val VEHICLE_GLOW_DP = 5f

    /** The arrowhead's circumradius and its outline. */
    private const val CHEVRON_DP = 11f
    private const val CHEVRON_STROKE_DP = 2.5f

    /** The origin: a 22 dp circle, a 9.5 dp ring, a 6 dp core. */
    private const val ORIGIN_DP = 22f
    private const val ORIGIN_RING_DP = 9.5f
    private const val ORIGIN_CORE_DP = 6f

    /** The destination badge: a 30×40 dp canvas, a 13.5 dp circle at y = 15. */
    private const val DEST_W_DP = 30f
    private const val DEST_H_DP = 40f
    private const val DEST_RADIUS_DP = 13.5f
    private const val DEST_CY_DP = 15f

    /** How far the nub's tip stops short of the bottom edge, for the shadow. */
    private const val DEST_TIP_INSET_DP = 1f

    /**
     * The nub: 9 dp across where it leaves the badge, tapering to the tip.
     *
     * It starts 0.82 of the badge's radius below its centre — 1.7 dp inside the
     * circle's lower edge — so the union has real overlap to work with instead
     * of a tangent, which is where a boolean union of two curves is least
     * reliable.
     */
    private const val DEST_NUB_HALF_DP = 4.5f
    private const val DEST_NUB_CTRL_DP = 1.5f
    private const val DEST_NUB_LIFT_DP = 3.6f

    /** The brand mark on the badge, and the badge's own soft shadow. */
    private const val DEST_MARK_DP = 4.8f
    private const val DEST_SHADOW_DP = 1.8f
    private const val DEST_SHADOW_DY_DP = 0.9f

    /** The halo's veil and its glow, 0-255. A halo that reads as a ring is a
     *  border, and a border around a puck over its own route line is noise. */
    private const val HALO_VEIL = 0x30
    private const val GLOW_VEIL = 0x59

    // ---- warning glyphs (V8 §7.6) -----------------------------------------

    /** Glyph canvas size, in dp. Big enough for three lamps, small enough to sit
     *  on a road without hiding it. */
    private const val WARN_SIZE_DP = 24f

    /**
     * The traffic-light lamps.
     *
     * Symbolic rather than semantic: a traffic light is red/amber/green
     * everywhere, and the point of the icon is that it is recognised before it
     * is read. They are taken from the accent ramps at the step that matches
     * the real object — coral for the red lens, sunny for the amber, leaf for
     * the green — so they are the design system's colours and still the colours
     * of a traffic light.
     */
    private val LAMP_RED = Ramp.coral500.toArgb()
    private val LAMP_AMBER = Ramp.sunny500.toArgb()
    private val LAMP_GREEN = Ramp.leaf500.toArgb()

    /**
     * A camera's aperture, and its housing's rim.
     *
     * Sunny for the speed camera and coral for the one that watches the signal:
     * the two objects differ by what they enforce, and where they differ has to
     * be the most visible part of the picture rather than a 3 px hole. Both come
     * from the accent ramp's vivid step rather than from a theme role, because a
     * warning is the same object at noon and at midnight — see [pill].
     */
    val LENS_SPEED: Int = Ramp.sunny500.toArgb()
    val LENS_RED_LIGHT: Int = Ramp.coral500.toArgb()

    private fun glyphCanvas(metrics: DisplayMetrics): Pair<Bitmap, Canvas> {
        val size = (WARN_SIZE_DP * metrics.density).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    /**
     * A traffic signal: the three-lamp head, in a housing.
     *
     * Read top to bottom the way the real object is, which is the whole reason
     * it is a picture rather than the word "Signal": a driver sees the SHAPE of
     * a signal head before they see anything about it.
     *
     * The housing is the theme's `guidanceSurface` — the role the design system
     * reserves for furniture that is dark in both themes because it is read
     * through a windscreen — so the glyph keeps the pill's own plate and the two
     * still read as one object. Its rim is the accent, which is the same
     * arrangement the camera glyph uses: on this glyph the rim is lilac, on a
     * camera's it is the colour of the lens.
     */
    fun warnSignal(metrics: DisplayMetrics, dark: Boolean = false): Bitmap {
        val (bmp, cv) = glyphCanvas(metrics)
        val w = bmp.width.toFloat()
        val plate = theme(dark).guidanceSurface.toArgb()
        val housing = android.graphics.RectF(w * 0.26f, w * 0.10f, w * 0.74f, w * 0.90f)
        val r = w * 0.10f
        cv.drawRoundRect(housing, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = plate; style = Paint.Style.FILL
        })
        // 0.06 of the glyph rather than 0.045: the rim is carrying the signal's
        // identity now, and a hairline of it is not an identity at 24 dp.
        cv.drawRoundRect(housing, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ramp.lilac500.toArgb(); style = Paint.Style.STROKE; strokeWidth = w * 0.06f
        })
        val lampR = w * 0.105f
        val cx = w / 2f
        val top = w * 0.245f
        val gap = w * 0.245f
        val lamps = listOf(LAMP_RED, LAMP_AMBER, LAMP_GREEN)
        lamps.forEachIndexed { i, colour ->
            cv.drawCircle(cx, top + i * gap, lampR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colour; style = Paint.Style.FILL
            })
        }
        return bmp
    }

    /**
     * A fixed speed camera: a lens housing on a post.
     *
     * The lens is the identifying part — an empty box could be anything, and
     * the reference products all draw the camera with its aperture visible.
     *
     * [accent] tints the aperture AND the housing's rim. For a camera that
     * watches a signal both turn coral, for one that watches the road both turn
     * sunny, which is what makes the two camera glyphs tell apart at a glance
     * rather than at a squint: they are the same object doing different jobs,
     * and a difference confined to a 3 px aperture is not a difference a driver
     * in traffic can use.
     */
    fun warnCamera(metrics: DisplayMetrics, accent: Int, dark: Boolean = false): Bitmap {
        val (bmp, cv) = glyphCanvas(metrics)
        val w = bmp.width.toFloat()
        // The post.
        cv.drawRoundRect(
            android.graphics.RectF(w * 0.45f, w * 0.62f, w * 0.55f, w * 0.92f),
            w * 0.05f, w * 0.05f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = theme(dark).onGuidanceMuted.toArgb(); style = Paint.Style.FILL
            },
        )
        // The housing: a surface floating over the map, in the role the design
        // system gives exactly that, with the accent for a rim.
        val body = android.graphics.RectF(w * 0.18f, w * 0.20f, w * 0.82f, w * 0.66f)
        cv.drawRoundRect(body, w * 0.13f, w * 0.13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme(dark).surfaceFloating.toArgb(); style = Paint.Style.FILL
        })
        cv.drawRoundRect(body, w * 0.13f, w * 0.13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; style = Paint.Style.STROKE; strokeWidth = w * 0.07f
        })
        // The aperture.
        cv.drawCircle(w * 0.62f, w * 0.43f, w * 0.145f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; style = Paint.Style.FILL
        })
        cv.drawCircle(w * 0.62f, w * 0.43f, w * 0.145f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme(dark).ink.toArgb(); style = Paint.Style.STROKE; strokeWidth = w * 0.04f
        })
        return bmp
    }
}
