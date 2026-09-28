package dev.vector.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.vector.android.design.drawIcon
import dev.vector.android.design.VectorIcon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Vector's drawn icon set.
 *
 * ## Why not text glyphs
 *
 * Every icon in the client used to be a character in a string: `"←"` for a left
 * turn, `"↻"` for a roundabout, `"⚙"` for settings, `"N"` for north-up, `"⤢"`
 * for route overview, `"●"` for the sending indicator. That is fast to write
 * and it fails in four distinct ways, all of them visible in
 * `v4-evidence/vector/before-*.png`:
 *
 * 1. **The weight is whatever the system font decides.** A `←` at
 *    `FontWeight.Bold` is a hairline next to the ~9 dp stroke both reference
 *    products draw their maneuver arrows with. The single most important
 *    element on the driving screen was rendered in the thinnest ink on it.
 * 2. **Some of them are effectively invisible.** `⤢` at 20 sp is four pixels of
 *    diagonal line; on the S24 the route-overview control reads as an empty
 *    circle. A control the driver cannot identify is a control that does not
 *    exist.
 * 3. **The glyph is not guaranteed to be present.** A missing codepoint renders
 *    as tofu, and there is no build-time check that would catch it — nor any
 *    reason the set available on this handset is the set available on the next.
 * 4. **A turn arrow is not a piece of text.** It has a shaft the vehicle
 *    arrives along and a head pointing where it goes. `←` has neither; it is a
 *    symmetric bidirectional mark. The reference arrows encode the *geometry*
 *    of the maneuver — Waze's left turn comes up from the bottom of the box,
 *    rounds an elbow and points left, which is the movement, drawn.
 *
 * So these are paths. About 250 lines to replace ten characters, and the
 * exchange is that they have a stroke weight, a viewport, and a shape chosen
 * for the job.
 *
 * ## The viewport
 *
 * Everything is authored in a 24×24 space and scaled to the requested [Dp], so
 * a maneuver arrow at 58 dp in the banner and the same arrow at 26 dp in the
 * step list are one definition. Stroke weight scales with it, which is what
 * keeps the small one from looking like a different icon rather than a smaller
 * one.
 *
 * Maneuvers are drawn with the vehicle **entering from the bottom**, which is
 * where the driver is. Straight ahead is up.
 *
 * ## One grid, one corner language, two weights
 *
 * The glyph set — [Glyph] for the map and HUD controls, [Extra] for everything
 * the redesigned screens need on top of them — is one drawing family, and the
 * rules that make it one are structural rather than a matter of taste:
 *
 * 1. **A 20×20 live area inside the 24×24 viewport.** Two units of padding on
 *    every side. Paths are authored against a nominal 4..20 box ([EDGE]) rather
 *    than against the live area itself, because a stroke is centred on its
 *    path: at the regular weight (0.105 × 24 = 2.52 units) the ink lands at
 *    4 − 1.26 = 2.74, and at the light weight at 3.22. The live area is the
 *    limit, not the target — a glyph that touches its limit looks larger than
 *    the box it is in. Nothing in this file puts ink outside it, so a glyph at
 *    16 dp, at 22 dp and at 58 dp all sit in the same optical square and a row
 *    of them needs no per-icon nudging.
 * 2. **Round caps and round joins, everywhere.** One [Stroke] is built per
 *    drawing and every path in it is stroked with that instance, so a corner is
 *    a corner of the same radius in a chevron, a map fold and a coffee cup. The
 *    radius at a stroke end is the half-width of the stroke, which is why the
 *    weights have to be exactly two: a third one would be a third corner
 *    radius.
 * 3. **Exactly two weights, chosen by rendered size.** [STROKE] is the regular
 *    weight, used above 16 dp — maneuvers included, since the banner arrow is
 *    26-58 dp and a differently weighted control glyph beside it would read as
 *    a different family. [STROKE_LIGHT] is for glyphs drawn at 16 dp and below,
 *    where the regular weight's 1.7 dp of ink in a 16 dp box closes the counter
 *    of a clock face and the notch of a star. The choice is made in
 *    [glyphWeight] from the DrawScope's own size, so a 16 dp chip icon and a
 *    24 dp button icon are the same drawing at two weights without either call
 *    site knowing.
 * 4. **Filled ink only where the mark is a solid.** Maneuver arrowheads are
 *    filled: the head, not the shaft, is what carries a turn at 58 dp, which is
 *    why it is drawn 16 dp long and left unchanged when the shaft weight moved
 *    to the one shared [STROKE]. The glyph set is open outline: its arrows end
 *    in stroked Vs, not triangles. The deliberate exceptions carry meaning that
 *    outline cannot — the compass's north half, the speaker's cone, a map pin's
 *    hole, a warning's dot, and the two states that *are* solidity,
 *    [Extra.SAVE_FILLED] and [Extra.STAR_FILLED].
 *
 * ## Tint
 *
 * Every drawing here takes the colour it is drawn in as an argument and uses
 * nothing else. There is no hex in this file and no second tone anywhere: a
 * glyph stays readable in one colour at 16 dp, which is what lets a screen tint
 * a chip's leading icon with `VectorTheme.colors.*` and get the right answer in
 * both themes without the icon set knowing either of them. The one alpha in the
 * file is [Glyph.EXIT]'s carriageway — the *tint*, thinned, to say "the road
 * you are not taking" — and the glyph still reads as a fork without it.
 */
object VectorIcons {

    /** Authoring viewport. All coordinates below are in this space. */
    private const val VB = 24f

    /**
     * The live area: the square the ink is allowed to touch.
     *
     * 20 of the 24 units, centred. The same optical margin the reference
     * products' icon sets reserve, and the reason an icon is never drawn edge
     * to edge: a glyph whose ink touches its box looks larger than the box and
     * collides with whatever is beside it.
     */
    private const val LIVE = 20f

    /**
     * The nominal box paths are authored in: 4..20.
     *
     * A 16-unit drawing inside the 20-unit live area, which leaves the ink a
     * comfortable margin at both weights (2.74 at the regular one, 3.22 at the
     * light one) instead of pressing on the limit. A few paths reach 3.9 or
     * 20.1 where a cap needs the room; all of them stay inside the live area,
     * which is the property the whole set is authored against and the one thing
     * to check when adding a glyph.
     */
    private const val EDGE = 4f

    /**
     * The regular weight: every glyph rendered above 16 dp, and the maneuvers.
     *
     * 0.105 × 24 dp = 2.5 dp, which is the weight the control glyphs in this
     * file have always been drawn at and the weight the reference products draw
     * a 24 dp outline icon at. Two things follow from it being *the* regular
     * weight rather than the maneuver's private one:
     *
     * 1. **The maneuvers share it.** A banner arrow at 58 dp is 6.1 dp of shaft
     *    under a 16 dp-long filled head, which is the arrow both reference
     *    products draw; the previous constant was 0.155 (9 dp at 58 dp, measured
     *    off Waze) and it made the banner arrow half again as heavy as every
     *    control beside it, which is exactly the incoherence a single icon
     *    family exists to prevent. The head is what carries an arrow at
     *    distance, and the head is unchanged.
     * 2. **The glyphs were re-cut to fit it.** At 0.155 a 20-unit live area
     *    holds about five strokes before they merge: a clock's hands, a
     *    warning's exclamation, a bin's ribs and a sun's rays all disappeared
     *    into the ring or the body they sit in. The geometry in this file is
     *    authored against this number.
     */
    private const val STROKE = 0.105f

    /**
     * The weight of the MANEUVER arrow, as a fraction of the viewport.
     *
     * 0.155 × 58 dp ≈ 9 dp, which is the weight measured off Waze's banner arrow
     * (26 px at 450 dpi) and the value this file used before the set was
     * normalised. Heavy for an icon, and correct for this one: the maneuver
     * arrow is read in peripheral vision at 100 km/h, and it is the only mark in
     * the product whose weight came from a measurement rather than a judgement.
     *
     * It is deliberately NOT [STROKE]. Normalising the family to one regular
     * weight is right for the 20-24 dp controls, whose smaller viewport makes
     * 0.155 read as a blob — but applying that same weight to the banner
     * silently thinned the driving arrow by a third, which is a legibility
     * regression in the one place legibility was actually measured. Two
     * weights, two jobs, each named for its job.
     */
    private const val MANEUVER_STROKE = 0.155f

    /**
     * The light weight: glyphs drawn at 16 dp and below.
     *
     * Not a taste call, and not the same fraction: stroke width here is a
     * fraction of the viewport, so a fraction chosen for a 24 dp glyph occupies
     * a *larger share* of a 16 dp one. At 0.085 a 16 dp glyph carries 1.4 dp of
     * ink — what the reference products draw a 16 dp icon at — against 1.7 dp
     * at the regular weight, and the 0.3 dp is what keeps the counter of a
     * clock face, the notch of a star and the fold of a map open in a chip.
     */
    private const val STROKE_LIGHT = 0.085f

    /**
     * The size at which the weight changes over.
     *
     * 16 dp is the smallest size the set is drawn at in a chip or a badge, and
     * the largest at which the regular weight reads as heavy. Everything the
     * design system draws below it — a 12 dp badge icon — uses the same light
     * weight.
     */
    private const val SMALL_GLYPH_DP = 16f

    /**
     * Half the angle between an outline arrowhead's two arms, in degrees.
     *
     * 42° is what makes a head read as a head at 16 dp. A shaft arrow's head is
     * only as legible as the width it spans: at a narrower spread the arms are
     * shorter than the gap between them, and at 16 dp the arrow renders as a
     * bare bar with a nub on the end — the head has to be a third of the glyph
     * wide to survive the smallest size the set is drawn at.
     */
    private const val HEAD_SPREAD = 42f

    // -----------------------------------------------------------------------
    // Weight and corner language
    // -----------------------------------------------------------------------

    /**
     * The stroke width for a glyph occupying this DrawScope, **in pixels**.
     *
     * ## The unit bug this function shipped with
     *
     * It used to return the raw fraction (`STROKE` = 0.105) and every caller
     * passed it straight to `Stroke(width = …)` and to `drawLine(…, width = …)`,
     * which take PIXELS. So every stroked path in the set was drawn with a
     * 0.105-pixel stroke — invisible at any density — while every FILLED mark
     * (a slider's knob, a bookmark's body, a star) still rendered. The result
     * was an icon set that looked like a set of dots: the settings control drew
     * its two knobs and neither of its rails, the search magnifier drew a ghost
     * of a ring, and the fuel pump and coffee cup in the filter chips read as
     * faint smudges.
     *
     * It is a fraction of the VIEWPORT, so the pixel width is the fraction times
     * the viewport in pixels — and because `s = minDimension / VB`, that reduces
     * to the fraction times the rendered size. `drawManeuver` had it right
     * (`MANEUVER_STROKE * VB * s`) all along; this is the same arithmetic, in one
     * place, for the glyphs.
     *
     * Read from the DrawScope rather than passed in, so the size a component
     * draws an icon at *is* the size the set is authored for: a 16 dp chip icon
     * and a 24 dp button icon are one definition at two weights.
     */
    private fun DrawScope.glyphWeight(): Float {
        val fraction = if (size.minDimension <= SMALL_GLYPH_DP.dp.toPx()) STROKE_LIGHT else STROKE
        return fraction * size.minDimension
    }

    /**
     * The one stroke style in the file.
     *
     * Every path — a maneuver, a chevron, a map fold, a V arrowhead — is
     * stroked with an instance of this, which is what makes "rounded joins and
     * caps" a property of the set rather than a habit.
     */
    private fun stroke(w: Float) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // -----------------------------------------------------------------------
    // Public composables
    // -----------------------------------------------------------------------

    /**
     * A maneuver arrow.
     *
     * @param type the backend's maneuver type string. Unknown types fall
     *   through to "straight ahead", which is the safe default: an unrecognised
     *   instruction should not tell the driver to turn.
     */
    @Composable
    fun Maneuver(
        type: String,
        dim: Dp,
        color: Color,
        modifier: Modifier = Modifier,
        label: String? = null,
    ) {
        Canvas(
            modifier
                .size(dim)
                .then(
                    if (label != null) Modifier.semantics { contentDescription = label }
                    else Modifier
                )
        ) {
            val s = this.size.minDimension / VB
            drawManeuver(type, s, color)
        }
    }

    /**
     * One lane of a lane diagram.
     *
     * Drawn, not typed. The lane strip rendered `LaneGuidance.glyph()` — a
     * Unicode arrow at whatever weight the system font happened to supply —
     * which is the same defect V4 fixed for the maneuver banner and left in
     * place here: a hairline character where the reference products draw a
     * solid ~9 dp stroke. Reported from the S24 as wanting the exits and turns
     * shown "pictorially, using a graphic".
     *
     * Reuses [drawManeuver], so a lane's left-turn arrow and the banner's
     * left-turn arrow are the same drawing at different sizes. That is the
     * point of having an icon set: the diagram teaches the driver the shape
     * that the instruction above it will use.
     *
     * @param valid whether this lane serves the upcoming maneuver. An invalid
     *   lane is dimmed rather than omitted, because the driver counts lanes
     *   across the whole carriageway — showing only the usable ones makes
     *   "the second from the left" wrong.
     */
    @Composable
    fun LaneArrow(
        indication: String,
        dim: Dp,
        color: Color,
        valid: Boolean,
        modifier: Modifier = Modifier,
    ) {
        Canvas(modifier.size(dim)) {
            val s = this.size.minDimension / VB
            drawManeuver(
                dev.vector.geo.LaneGuidance.arrowType(indication),
                s,
                // Dimmed by ALPHA rather than by a second colour, so a theme
                // only has to define one lane colour and the two can never
                // drift apart.
                if (valid) color else color.copy(alpha = 0.28f),
            )
        }
    }

    /** A map or HUD control glyph. */
    @Composable
    fun Control(
        kind: Glyph,
        dim: Dp,
        color: Color,
        modifier: Modifier = Modifier,
        label: String? = null,
        /** For [Glyph.COMPASS_NORTH]: which way the map is currently facing. */
        rotationDeg: Float = 0f,
    ) {
        Canvas(
            modifier
                .size(dim)
                .then(
                    if (label != null) Modifier.semantics { contentDescription = label }
                    else Modifier
                )
        ) {
            val s = this.size.minDimension / VB
            if (rotationDeg != 0f) {
                rotate(rotationDeg) { drawGlyph(kind, s, color) }
            } else {
                drawGlyph(kind, s, color)
            }
        }
    }

    /**
     * The control glyphs. One entry per control that exists; no spares.
     *
     * This enum is the **map and HUD control** vocabulary and stays closed for
     * that reason: every entry answers "which control is this?", and the list is
     * reviewed against the controls that actually exist on the map. Marks the
     * redesigned screens need beyond it live in [Extra] — a different list with
     * a different review, and the reason `COFFEE` is not an entry in a list of
     * driving controls.
     */
    enum class Glyph {
        SETTINGS,
        /** Compass rose. Rotates with the map; the north half is emphasised. */
        COMPASS_NORTH,
        /** Map faces the direction of travel. */
        HEADING_UP,
        /** The driver rotated the map by hand. */
        ROTATED,
        /** Frame the whole route. */
        OVERVIEW,
        /** Leave overview, follow the vehicle. */
        FOLLOW,
        /** Put the camera back on the vehicle. */
        RECENTER,

        /**
         * Closer in / further out, one zoom level at a time.
         *
         * A bare plus and a bare minus, not a magnifier with a sign inside it.
         * The reason is the one [OVERVIEW] records: these are drawn at 22 dp,
         * and a magnifier body at that size leaves about six dp for whatever
         * goes in it — which is the sub-pixel detail that made "⤢" read as an
         * empty circle on the S24. Stripped to the sign alone, each glyph is
         * one or two strokes at the full control weight.
         *
         * A lone minus would be meaningless; the PAIR is what makes it
         * readable, which is why [MapControls] draws them adjacent and always
         * in this order. The generic add/remove marks are [Extra.PLUS] and
         * [Extra.MINUS], which draw this same pair.
         */
        ZOOM_IN,
        ZOOM_OUT,
        VOICE_FULL,
        VOICE_BRIEF,
        VOICE_ALERTS,
        VOICE_OFF,
        SEARCH,
        CLOSE,
        BACK,
        /** Switch between routes. */
        ROUTES,
        /** The step list. */
        STEPS,
        /** Report something on the road. */
        REPORT,
        /** A previously used destination. */
        HISTORY,
        /** A place. */
        PLACE,
        /** Destination. */
        FLAG,
        /** Home, as a saved place. */
        HOME,
        /** Work, as a saved place. */
        WORK,
        /**
         * A motorway exit: the carriageway continuing, and a slip road leaving
         * it.
         *
         * Drawn rather than lettered because it is the one thing on the exit
         * badge that a driver recognises before reading anything — the shape of
         * a road peeling away is the same shape on the gantry above them. Asked
         * for directly: *"the card at the top should also show pictorially when
         * exits need to be taken."*
         */
        EXIT,
    }

    /**
     * The marks the redesigned screens need, beyond the driving controls.
     *
     * ## Why this is a second enum and not more [Glyph] entries
     *
     * [Glyph] is documented as a closed vocabulary — "one entry per control
     * that exists; no spares" — because it is the *map and HUD control* set,
     * reviewed against the controls that exist on the map. A general mark set
     * grows with the screens instead, and folding `COFFEE` and `PARKING` into a
     * list of driving controls would make that list's own rule false. Renaming
     * [Glyph]'s entries is not an option either: `NavUi.kt`, `Settings.kt` and
     * the navigation tests name them.
     *
     * ## What is deliberately NOT here
     *
     * Where a mark already exists in [Glyph], the existing entry **is** the
     * mark and this enum does not restate it — two entries drawing one shape
     * drift apart the first time one of them is edited. So the redesigned
     * screens use:
     *
     * | wanted | entry |
     * |---|---|
     * | `search` | [Glyph.SEARCH] |
     * | `close` | [Glyph.CLOSE] |
     * | `settings` | [Glyph.SETTINGS] |
     * | `home` | [Glyph.HOME] |
     * | `work` | [Glyph.WORK] |
     * | `locate` | [Glyph.RECENTER] — the crosshair the map control already draws |
     * | `compass` | [Glyph.COMPASS_NORTH] |
     * | `volume` / `volumeOff` | [Glyph.VOICE_FULL] / [Glyph.VOICE_OFF] |
     *
     * Six wanted names *do* live here as entries that delegate to their [Glyph]
     * drawing — [PLUS], [MINUS], [LIST], [PIN], [WARNING], [CLOCK] — because on
     * the screen that needs them the control name would be a lie: `ZOOM_IN` is
     * not an "add a stop" button, `STEPS` is not a list/map toggle, `PLACE` is
     * a search result rather than a map pin, `REPORT` is a control rather than
     * a closure banner, and a route card's duration is a clock, not a control
     * that recalls where you have been.
     */
    enum class Extra {
        /** A right chevron: a row that opens something. */
        CHEVRON_RIGHT,
        /** A down chevron: a section that expands, a picker that drops. */
        CHEVRON_DOWN,
        /** Hand a place to someone else. */
        SHARE,
        /** Start guidance to a place: a road that turns and goes. */
        DIRECTIONS,
        /** Bookmark, empty. */
        SAVE,
        /** Bookmark, saved. Solid, because the state IS the solidity. */
        SAVE_FILLED,
        /** Narrow a list of results. */
        FILTER,
        /** Show results as a list. Delegates to [Glyph.STEPS]; see the note above. */
        LIST,
        /** Show results on a map: a folded map. */
        MAP,
        /** Map style: a stack of surfaces. */
        LAYERS,
        /** Add. Delegates to [Glyph.ZOOM_IN]; see the note above. */
        PLUS,
        /** Remove. Delegates to [Glyph.ZOOM_OUT]; see the note above. */
        MINUS,
        /** Travel on foot. */
        WALK,
        /** Travel by car. */
        CAR,
        /** A map pin. Delegates to [Glyph.PLACE]; see the note above. */
        PIN,
        /** Favourite, empty. */
        STAR,
        /** Favourite, set. Solid, because the state IS the solidity. */
        STAR_FILLED,
        /** A time or a duration. Delegates to [Glyph.HISTORY]; see the note above. */
        CLOCK,
        /** Something worth knowing, not something wrong. */
        INFO,
        /** Something wrong, or something closed. Delegates to [Glyph.REPORT]. */
        WARNING,
        /** Try the failed thing again: the two-headed sweep. */
        RETRY,
        /** Done, accepted, saved. */
        CHECK,
        /** Throw it away. */
        DELETE,
        /** No connection. A cloud with a line through it. */
        OFFLINE,
        /** Brighter, or day. */
        SUN,
        /** Dimmer, or night. */
        MOON,
        /** A cafe. */
        COFFEE,
        /** Fuel. */
        FUEL,
        /** Parking. */
        PARKING,
        /** Up, as a direction rather than a maneuver. */
        ARROW_UP,
        /** Down. */
        ARROW_DOWN,
        /** Left. */
        ARROW_LEFT,
        /** Right. */
        ARROW_RIGHT,
        /** A status dot: `● Open`. */
        DOT,

        // ---- place categories -------------------------------------------
        //
        // Added so a list of search results is scannable without reading it.
        // Before these, every family that had no glyph fell back to [PIN], so
        // a hotel, an office and a neighbourhood were three identical blue
        // pins — see `PlaceIdentity.kt` for why that mattered. Each one is
        // drawn on the same 24 dp grid, at the same stroke weight, in the same
        // rounded-outline manner as the rest of the set: a category symbol
        // that came from a different drawing hand is worse than no symbol.
        /** A shopping bag: mall, shop, supermarket. */
        BAG,
        /** A bed seen from the side: hotel, resort, guest house. */
        BED,
        /** A dome and a finial: mosque, church, place of worship. */
        DOME,
        /** A tree: park, garden, corniche, beach. */
        TREE,
        /** A tower block: office, civic building, anything built and named. */
        TOWER,
    }

    /**
     * A control as a value: its glyph, and the one piece of state that changes
     * the drawing.
     *
     * [Control] the composable takes the same pair. This is that pair as data,
     * for the screens that hand an icon to [VectorIconButton] instead of
     * drawing it themselves — and it exists because a `VectorIcon` cannot carry
     * a rotation, so a compass needle has to have its bearing baked into the
     * paint or it is the only control on the map that cannot be rotated.
     */
    @Immutable
    data class Control(val kind: Glyph, val rotationDeg: Float = 0f)

    // -----------------------------------------------------------------------
    // Icons for the design system
    // -----------------------------------------------------------------------

    /**
     * The set, built once.
     *
     * `VectorIcon` is immutable and its `key` is what a test tag and a
     * screenshot name are built from, so the instances are shared rather than
     * rebuilt: a screen that calls [glyphIcon] in a composable body does not
     * allocate one per frame, and the identity stays stable across
     * recompositions.
     */
    private val glyphIcons: List<VectorIcon> =
        Glyph.entries.map { glyph ->
            drawIcon("glyph." + glyph.name) { tint -> drawGlyph(glyph, size.minDimension / VB, tint) }
        }

    private val extraIcons: List<VectorIcon> =
        Extra.entries.map { extra ->
            drawIcon("extra." + extra.name) { tint -> drawExtra(extra, size.minDimension / VB, tint) }
        }

    /**
     * A control glyph as an icon, for `VectorButton` / `VectorIconButton` /
     * `VectorChip`.
     *
     * The drawing is scaled to the DrawScope the component paints in and takes
     * the component's own tint, so the weight follows the size the component
     * chose: 16 dp gets [STROKE_LIGHT], 20-24 dp gets [STROKE].
     */
    fun glyphIcon(glyph: Glyph): VectorIcon = glyphIcons[glyph.ordinal]

    /** [Extra] as an icon; the same contract as [glyphIcon]. */
    fun glyphIcon(extra: Extra): VectorIcon = extraIcons[extra.ordinal]

    /**
     * A control as an icon, including its orientation.
     *
     * An unrotated control *is* its glyph, so it shares the glyph's icon and
     * its stable key. A rotated one carries the angle into the paint, and the
     * key deliberately does not change with it: a compass needle that turns
     * with the map must not churn its test tag sixty times a second. A screen
     * that would rather rotate the whole control can pass `Control(kind)` and
     * put `Modifier.rotate` on it — for a round button that is the same
     * picture.
     */
    fun controlIcon(control: Control): VectorIcon {
        if (control.rotationDeg == 0f) return glyphIcon(control.kind)
        val kind = control.kind
        val degrees = control.rotationDeg
        return drawIcon("control." + kind.name) { tint ->
            rotate(degrees) { drawGlyph(kind, size.minDimension / VB, tint) }
        }
    }

    // -----------------------------------------------------------------------
    // Maneuvers
    // -----------------------------------------------------------------------

    private fun DrawScope.drawManeuver(type: String, s: Float, color: Color) {
        val w = MANEUVER_STROKE * VB * s
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(build: Path.() -> Unit) = Path().apply(build)
        fun stroked(path: Path) = drawPath(path, color, style = stroke)

        when (type) {
            "turn-left" -> {
                stroked(p {
                    moveTo(15f * s, 22f * s)
                    lineTo(15f * s, 13f * s)
                    quadraticTo(15f * s, 8.5f * s, 10.5f * s, 8.5f * s)
                    lineTo(9f * s, 8.5f * s)
                })
                head(Offset(2.6f * s, 8.5f * s), 180f, s, color)
            }
            "turn-right" -> {
                stroked(p {
                    moveTo(9f * s, 22f * s)
                    lineTo(9f * s, 13f * s)
                    quadraticTo(9f * s, 8.5f * s, 13.5f * s, 8.5f * s)
                    lineTo(15f * s, 8.5f * s)
                })
                head(Offset(21.4f * s, 8.5f * s), 0f, s, color)
            }
            "slight-left" -> {
                stroked(p {
                    moveTo(15f * s, 22f * s)
                    lineTo(15f * s, 15f * s)
                    quadraticTo(15f * s, 11f * s, 12f * s, 8.5f * s)
                    lineTo(10.5f * s, 7.2f * s)
                })
                head(Offset(6.2f * s, 3.6f * s), 220f, s, color)
            }
            "slight-right" -> {
                stroked(p {
                    moveTo(9f * s, 22f * s)
                    lineTo(9f * s, 15f * s)
                    quadraticTo(9f * s, 11f * s, 12f * s, 8.5f * s)
                    lineTo(13.5f * s, 7.2f * s)
                })
                head(Offset(17.8f * s, 3.6f * s), 320f, s, color)
            }
            "uturn" -> {
                stroked(p {
                    moveTo(16.5f * s, 22f * s)
                    lineTo(16.5f * s, 11f * s)
                    quadraticTo(16.5f * s, 5f * s, 11.25f * s, 5f * s)
                    quadraticTo(6f * s, 5f * s, 6f * s, 11f * s)
                    lineTo(6f * s, 13.5f * s)
                })
                head(Offset(6f * s, 21.4f * s), 90f, s, color)
            }
            "roundabout" -> {
                // A full stroked circle plus an entry stub and an exit arrow.
                // A partial arc would have to encode WHICH exit, and the
                // backend does not tell us the ordinal — see the roundabout
                // row in the V4 gap matrix, which is one of the things a real
                // drive is needed to settle.
                drawCircle(
                    color, radius = 6f * s,
                    center = Offset(12f * s, 11f * s), style = stroke,
                )
                stroked(p {
                    moveTo(12f * s, 22.5f * s)
                    lineTo(12f * s, 17.6f * s)
                })
                stroked(p {
                    moveTo(17.6f * s, 8.4f * s)
                    lineTo(20f * s, 6f * s)
                })
                head(Offset(22.6f * s, 3.4f * s), 315f, s, color)
            }
            "arrive" -> {
                // A pin, not an arrow: there is no movement left to describe.
                // Stroked outline plus a solid centre, rather than a filled
                // teardrop with the middle punched out - a punch-out needs an
                // offscreen compositing layer to blend against, and a maneuver
                // icon is not worth one.
                stroked(p {
                    moveTo(12f * s, 22.5f * s)
                    cubicTo(12f * s, 22.5f * s, 4.8f * s, 14.6f * s, 4.8f * s, 9.6f * s)
                    cubicTo(4.8f * s, 5.6f * s, 8.0f * s, 2.4f * s, 12f * s, 2.4f * s)
                    cubicTo(16f * s, 2.4f * s, 19.2f * s, 5.6f * s, 19.2f * s, 9.6f * s)
                    cubicTo(19.2f * s, 14.6f * s, 12f * s, 22.5f * s, 12f * s, 22.5f * s)
                    close()
                })
                drawCircle(color, 2.7f * s, Offset(12f * s, 9.5f * s))
            }
            // "depart" and anything unrecognised.
            else -> {
                stroked(p {
                    moveTo(12f * s, 22f * s)
                    lineTo(12f * s, 8f * s)
                })
                head(Offset(12f * s, 1.6f * s), 270f, s, color)
            }
        }
    }

    /**
     * A filled arrowhead.
     *
     * @param tip where the point goes.
     * @param angleDeg the direction it points, screen degrees (0 = right,
     *   90 = down, matching the canvas's y-down axis).
     *
     * Filled rather than stroked, and slightly narrower than it is long, which
     * is what makes it read as a direction at 26 dp as well as at 58.
     */
    private fun DrawScope.head(tip: Offset, angleDeg: Float, s: Float, color: Color) {
        val len = 6.6f * s
        val half = 5.0f * s
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        // Back along the axis, then out to each side perpendicular to it.
        val bx = tip.x - dx * len
        val by = tip.y - dy * len
        val px = -dy
        val py = dx
        drawPath(
            Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(bx + px * half, by + py * half)
                lineTo(bx - px * half, by - py * half)
                close()
            },
            color,
        )
    }

    // -----------------------------------------------------------------------
    // Shared drawing pieces
    // -----------------------------------------------------------------------

    /**
     * An outline arrowhead: two strokes meeting at [tip], rounded, at
     * [HEAD_SPREAD] either side of the direction of travel.
     *
     * The glyph set's arrows end in this rather than in [head]'s filled
     * triangle. Two reasons, and the second is the one that decided it: a solid
     * head would be the only filled shape in an otherwise open set, and at the
     * regular weight a stroked V is *more* legible than a small solid triangle
     * because the V's arms stay separated where a triangle's corners would
     * round into a blob.
     *
     * @param arm length of each arm, in viewport units.
     */
    private fun DrawScope.arrowHead(
        tip: Offset,
        angleDeg: Float,
        arm: Float,
        s: Float,
        color: Color,
        w: Float,
    ) {
        fun back(sign: Float): Offset {
            val a = Math.toRadians((angleDeg + 180f + sign * HEAD_SPREAD).toDouble())
            return Offset(tip.x + arm * s * cos(a).toFloat(), tip.y + arm * s * sin(a).toFloat())
        }
        val left = back(-1f)
        val right = back(1f)
        drawPath(
            Path().apply {
                moveTo(left.x, left.y)
                lineTo(tip.x, tip.y)
                lineTo(right.x, right.y)
            },
            color,
            style = stroke(w),
        )
    }

    /**
     * A head for an arrow that runs round a circle: a tangential tip and one
     * barb turned inward.
     *
     * A circular arrow cannot use [arrowHead]. The V spreads across the ring, so
     * its outer arm leaves the live area unless the ring is shrunk to 5.6 units
     * — a ring two thirds the size of every other circle in the set, which is a
     * worse defect than the one it fixes. This head turns its second arm toward
     * the centre instead, so the whole head fits on a ring of 7.4 and
     * [Glyph.ROTATED] and [Extra.RETRY] are the same circle as a clock face.
     *
     * @param angleDeg the direction of travel at the tip.
     * @param inward +1 to turn the barb toward the centre of the ring, -1 away.
     */
    private fun DrawScope.sweepHead(
        tip: Offset,
        angleDeg: Float,
        arm: Float,
        inward: Float,
        s: Float,
        color: Color,
        w: Float,
    ) {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawPath(
            Path().apply {
                moveTo(tip.x - dx * arm * s, tip.y - dy * arm * s)
                lineTo(tip.x, tip.y)
                lineTo(tip.x - dy * inward * arm * s, tip.y + dx * inward * arm * s)
            },
            color,
            style = stroke(w),
        )
    }

    /** A stroked circle at the current weight. */
    private fun DrawScope.ring(cx: Float, cy: Float, r: Float, s: Float, color: Color, w: Float) {
        drawCircle(color, r * s, Offset(cx * s, cy * s), style = stroke(w))
    }

    /**
     * A dot exactly one stroke wide: a zero-length line with a round cap.
     *
     * Drawn as a line rather than as a filled circle so that it is the same
     * weight as the strokes beside it — the exclamation in [Glyph.REPORT] and
     * the tittle in [Extra.INFO] are the same mark as the rule they sit on,
     * not a smaller shape that happens to be nearby.
     */
    private fun DrawScope.dot(x: Float, y: Float, s: Float, color: Color, w: Float) {
        drawLine(color, Offset(x * s, y * s), Offset(x * s, y * s), w, StrokeCap.Round)
    }

    /** A rounded rectangle outline, for the glyphs that are boxes. */
    private fun DrawScope.roundedBox(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        radius: Float,
        s: Float,
        color: Color,
        w: Float,
    ) {
        drawPath(
            Path().apply {
                moveTo((l + radius) * s, t * s)
                lineTo((r - radius) * s, t * s)
                quadraticTo(r * s, t * s, r * s, (t + radius) * s)
                lineTo(r * s, (b - radius) * s)
                quadraticTo(r * s, b * s, (r - radius) * s, b * s)
                lineTo((l + radius) * s, b * s)
                quadraticTo(l * s, b * s, l * s, (b - radius) * s)
                lineTo(l * s, (t + radius) * s)
                quadraticTo(l * s, t * s, (l + radius) * s, t * s)
                close()
            },
            color,
            style = stroke(w),
        )
    }

    /** The bookmark [Extra.SAVE] and [Extra.SAVE_FILLED] share: one path, two fills. */
    private fun bookmark(s: Float) = Path().apply {
        moveTo(6f * s, 4f * s)
        lineTo(18f * s, 4f * s)
        lineTo(18f * s, 20f * s)
        lineTo(12f * s, 15.4f * s)
        lineTo(6f * s, 20f * s)
        close()
    }

    /** The star [Extra.STAR] and [Extra.STAR_FILLED] share. */
    private fun star(s: Float) = Path().apply {
        moveTo(12f * s, 4f * s)
        lineTo(14.7f * s, 8.28f * s)
        lineTo(19.61f * s, 9.53f * s)
        lineTo(16.37f * s, 13.42f * s)
        lineTo(16.7f * s, 18.47f * s)
        lineTo(12f * s, 16.6f * s)
        lineTo(7.3f * s, 18.47f * s)
        lineTo(7.63f * s, 13.42f * s)
        lineTo(4.39f * s, 9.53f * s)
        lineTo(9.3f * s, 8.28f * s)
        close()
    }

    // -----------------------------------------------------------------------
    // Glyphs
    // -----------------------------------------------------------------------

    /**
     * One control glyph, at the weight its rendered size calls for.
     *
     * Kept as the entry point every caller uses; the weight is derived inside
     * [glyphWeight] from the DrawScope, so `Control` and [glyphIcon] cannot
     * disagree about it.
     */
    private fun DrawScope.drawGlyph(kind: Glyph, s: Float, color: Color) {
        drawGlyph(kind, s, color, glyphWeight())
    }

    private fun DrawScope.drawGlyph(kind: Glyph, s: Float, color: Color, w: Float) {
        val stroke = stroke(w)
        fun p(build: Path.() -> Unit) = Path().apply(build)
        fun stroked(path: Path) = drawPath(path, color, style = stroke)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)

        when (kind) {
            Glyph.SETTINGS -> {
                // Two sliders. Chosen over a gear deliberately: a gear at 22 dp
                // is a ring of sub-pixel teeth, and "settings" is one of the
                // few concepts a driver never needs to identify at speed.
                // The rails run the full live area and the knobs are solid: a
                // slider's knob is a handle, and a handle drawn as a ring reads
                // as a bead threaded on the rail rather than as the thing the
                // finger moves.
                line(4f, 8f, 20f, 8f)
                line(4f, 16f, 20f, 16f)
                drawCircle(color, 3.6f * s, Offset(9f * s, 8f * s))
                drawCircle(color, 3.6f * s, Offset(15f * s, 16f * s))
            }
            Glyph.COMPASS_NORTH -> {
                // A ring with ONE solid needle in it.
                //
                // Two earlier versions failed at the size this is actually
                // drawn (20 dp on a 450 dpi phone). A bare two-tone needle read
                // as a droplet — reported as "a circle with a drop". Adding the
                // ring fixed that but keeping the hollow south half did not
                // survive either: north and south share a waist, and at 20 dp
                // the stroke of the south outline closes the 3.6-unit gap to
                // the north fill, so the pair merges into one solid diamond and
                // reads as a gem.
                //
                // So the south half is gone. One filled navigation needle
                // inside a dial is the compass convention every map product
                // uses, it is unambiguous at this size, and it still carries
                // the bearing when the whole glyph rotates — which was the only
                // reason the rose existed rather than the letter N.
                //
                // The ring is what separates this from [HEADING_UP], which is
                // the same arrow without one, two rows away in the same column.
                ring(12f, 12f, 8.6f, s, color, w)
                drawPath(
                    p {
                        moveTo(12f * s, 5.2f * s)
                        lineTo(16.6f * s, 15.6f * s)
                        lineTo(12f * s, 13f * s)
                        lineTo(7.4f * s, 15.6f * s)
                        close()
                    },
                    color,
                )
            }
            Glyph.HEADING_UP -> {
                drawPath(
                    p {
                        moveTo(12f * s, 3.2f * s)
                        lineTo(19.2f * s, 20.2f * s)
                        lineTo(12f * s, 16.4f * s)
                        lineTo(4.8f * s, 20.2f * s)
                        close()
                    },
                    color,
                )
            }
            Glyph.ROTATED -> {
                // A circular arrow: the map is at a bearing nobody chose in
                // settings, and tapping returns it to north.
                //
                // Three quarters of a ring of 7.4 — the same circle a clock
                // face is — open in the north-east, with the head at the top of
                // the sweep pointing right, which is the way the map turns.
                // [sweepHead] records why a ring arrow cannot wear the V the
                // rest of the arrows wear.
                stroked(p {
                    moveTo(19.4f * s, 12f * s)
                    cubicTo(19.4f * s, 16.09f * s, 16.09f * s, 19.4f * s, 12f * s, 19.4f * s)
                    cubicTo(7.91f * s, 19.4f * s, 4.6f * s, 16.09f * s, 4.6f * s, 12f * s)
                    cubicTo(4.6f * s, 7.91f * s, 7.91f * s, 4.6f * s, 12f * s, 4.6f * s)
                })
                sweepHead(Offset(16.4f * s, 4.6f * s), 0f, 5.4f, 1f, s, color, w)
            }
            Glyph.OVERVIEW -> {
                // Four corner brackets pointing OUT. Replaces "⤢", which at
                // 20 sp was four pixels of diagonal and read as an empty
                // control on the S24.
                corner(4f, 4f, 1f, 1f, s, w, color)
                corner(20f, 4f, -1f, 1f, s, w, color)
                corner(4f, 20f, 1f, -1f, s, w, color)
                corner(20f, 20f, -1f, -1f, s, w, color)
            }
            Glyph.FOLLOW -> {
                // The same brackets pointing IN.
                corner(9f, 9f, -1f, -1f, s, w, color)
                corner(15f, 9f, 1f, -1f, s, w, color)
                corner(9f, 15f, -1f, 1f, s, w, color)
                corner(15f, 15f, 1f, 1f, s, w, color)
            }
            Glyph.RECENTER -> {
                // A crosshair: ring, four spokes, solid centre — the same
                // "locate me" mark every map product uses, because it is the
                // one a driver does not have to learn.
                //
                // Redrawn at a weight that survives the size it is actually
                // used at. It was a 5.2 ring with 1.7-unit spokes, which on a
                // 450 dpi phone at 20 dp is a faint target with four bumps —
                // reported as "the gps button needs attention, it's not good
                // enough", and the capture agrees: it reads as a smudge. The
                // ring is now 6.6, the centre dot 2.4, and the spokes run from
                // the ring to the edge of the live area at 3.4 units each, so
                // the cross is a cross rather than a suggestion of one.
                ring(12f, 12f, 6.6f, s, color, w)
                drawCircle(color, 2.4f * s, Offset(12f * s, 12f * s))
                line(12f, 3.6f, 12f, 7f)
                line(12f, 17f, 12f, 20.4f)
                line(3.6f, 12f, 7f, 12f)
                line(17f, 12f, 20.4f, 12f)
            }
            // 15 of the 24-unit viewport, matching the span of the settings
            // sliders rather than filling the box. A plus drawn edge to edge
            // would be the largest mark in the control column, and these two
            // are the LEAST urgent things in it.
            Glyph.ZOOM_IN -> {
                line(4.5f, 12f, 19.5f, 12f)
                line(12f, 4.5f, 12f, 19.5f)
            }
            Glyph.ZOOM_OUT -> line(4.5f, 12f, 19.5f, 12f)
            // The four voice modes differ by HOW MUCH SOUND, and the glyphs say
            // so: three waves, one wave, an exclamation, a slash. §27's set,
            // and the shape carries the meaning so it is not colour-only.
            Glyph.VOICE_FULL -> {
                speaker(s, w, color)
                stroked(p {
                    moveTo(14.5f * s, 9f * s); quadraticTo(16.3f * s, 12f * s, 14.5f * s, 15f * s)
                })
                stroked(p {
                    moveTo(17f * s, 6.5f * s); quadraticTo(19.6f * s, 12f * s, 17f * s, 17.5f * s)
                })
            }
            Glyph.VOICE_BRIEF -> {
                speaker(s, w, color)
                stroked(p {
                    moveTo(14.5f * s, 9f * s); quadraticTo(16.3f * s, 12f * s, 14.5f * s, 15f * s)
                })
            }
            Glyph.VOICE_ALERTS -> {
                speaker(s, w, color)
                line(17.5f, 7f, 17.5f, 13f)
                drawCircle(color, 1.1f * s, Offset(17.5f * s, 16.6f * s))
            }
            Glyph.VOICE_OFF -> {
                speaker(s, w, color)
                line(14.8f, 8.4f, 19.8f, 15.6f)
                line(19.8f, 8.4f, 14.8f, 15.6f)
            }
            Glyph.SEARCH -> {
                // Circle and handle both stop short of the box: a magnifier's
                // handle leaves the ring at 45°, so its far end is the glyph's
                // only extreme point and it sets the optical size.
                ring(10.2f, 10.2f, 6.2f, s, color, w)
                line(14.6f, 14.6f, 20.1f, 20.1f)
            }
            Glyph.CLOSE -> {
                line(4.6f, 4.6f, 19.4f, 19.4f)
                line(19.4f, 4.6f, 4.6f, 19.4f)
            }
            Glyph.BACK -> {
                // The mirror of [Extra.CHEVRON_RIGHT], to the unit: a back
                // chevron and a forward chevron are one drawing seen from two
                // sides, and drawing them separately is how a set ends up with
                // two chevrons that do not match.
                stroked(p {
                    moveTo(15f * s, 5.5f * s)
                    lineTo(8.5f * s, 12f * s)
                    lineTo(15f * s, 18.5f * s)
                })
            }
            Glyph.ROUTES -> {
                // A road that forks. The two references use the same shape for
                // the same control, in the same corner.
                stroked(p {
                    moveTo(12f * s, 20.1f * s)
                    lineTo(12f * s, 14.2f * s)
                })
                stroked(p {
                    moveTo(12f * s, 14.2f * s)
                    quadraticTo(12f * s, 10.4f * s, 8.4f * s, 8.4f * s)
                    lineTo(7.2f * s, 7.8f * s)
                })
                stroked(p {
                    moveTo(12f * s, 14.2f * s)
                    quadraticTo(12f * s, 10.4f * s, 15.6f * s, 8.4f * s)
                    lineTo(16.8f * s, 7.8f * s)
                })
                arrowHead(Offset(4.61f * s, 6.5f * s), 206.6f, 5.4f, s, color, w)
                arrowHead(Offset(19.39f * s, 6.5f * s), 333.4f, 5.4f, s, color, w)
            }
            Glyph.STEPS -> {
                line(4f, 7f, 20f, 7f)
                line(4f, 12f, 20f, 12f)
                line(4f, 17f, 14f, 17f)
            }
            Glyph.REPORT -> {
                stroked(p {
                    moveTo(12f * s, 4f * s)
                    lineTo(20f * s, 19.8f * s)
                    lineTo(4f * s, 19.8f * s)
                    close()
                })
                line(12f, 9.4f, 12f, 14.4f)
                dot(12f, 17.2f, s, color, w)
            }
            Glyph.HISTORY -> {
                // A clock face. 7.4 units, not the 8.2 it used to be, and not
                // because of the padding: a circle of the same nominal size as
                // a square reads larger than it, so every circle in the set is
                // inset about a unit — which is also what puts this face on the
                // same circle as the compass ring in [Extra.RETRY] and the
                // information mark in [Extra.INFO].
                ring(12f, 12f, 7.4f, s, color, w)
                line(12f, 7.4f, 12f, 12f)
                line(12f, 12f, 15.6f, 14.2f)
            }
            Glyph.HOME -> {
                // A roof over a body. Two strokes, no door and no windows: at
                // 18 dp a detailed house is a smudge, and the silhouette is
                // what carries the meaning.
                stroked(p {
                    moveTo(4f * s, 11.6f * s)
                    lineTo(12f * s, 4.6f * s)
                    lineTo(20f * s, 11.6f * s)
                })
                stroked(p {
                    moveTo(6f * s, 11.2f * s)
                    lineTo(6f * s, 19.8f * s)
                    lineTo(18f * s, 19.8f * s)
                    lineTo(18f * s, 11.2f * s)
                })
            }
            Glyph.WORK -> {
                // A case with a handle. The handle is what separates it from a
                // plain rectangle at this size, so it is drawn at full weight
                // rather than as a detail.
                stroked(p {
                    moveTo(9f * s, 7.8f * s)
                    lineTo(9f * s, 5.6f * s)
                    lineTo(15f * s, 5.6f * s)
                    lineTo(15f * s, 7.8f * s)
                })
                stroked(p {
                    moveTo(4f * s, 7.8f * s)
                    lineTo(20f * s, 7.8f * s)
                    lineTo(20f * s, 19.2f * s)
                    lineTo(4f * s, 19.2f * s)
                    close()
                })
                line(4f, 12.6f, 20f, 12.6f)
            }
            Glyph.EXIT -> {
                // The through carriageway, dimmed: it is the road NOT being
                // taken, and drawing it at full weight would make the glyph a
                // fork with no advice in it. The dim is the caller's tint at
                // 35% — not a second colour — and the glyph reads as a fork
                // even if the tint arrives opaque.
                drawPath(
                    p {
                        moveTo(8f * s, 20.1f * s)
                        lineTo(8f * s, 4.2f * s)
                    },
                    color.copy(alpha = 0.35f),
                    style = stroke,
                )
                // The slip road, leaving to the right and up, with a head.
                stroked(p {
                    moveTo(8f * s, 15.6f * s)
                    quadraticTo(8f * s, 10.4f * s, 14.4f * s, 8.2f * s)
                    lineTo(15.6f * s, 7.5f * s)
                })
                arrowHead(Offset(18.37f * s, 5.9f * s), -30f, 5.4f, s, color, w)
            }
            Glyph.PLACE -> {
                stroked(p {
                    moveTo(12f * s, 19.9f * s)
                    cubicTo(12f * s, 19.9f * s, 5.6f * s, 13.6f * s, 5.6f * s, 9.8f * s)
                    cubicTo(5.6f * s, 6.4f * s, 8.5f * s, 4f * s, 12f * s, 4f * s)
                    cubicTo(15.5f * s, 4f * s, 18.4f * s, 6.4f * s, 18.4f * s, 9.8f * s)
                    cubicTo(18.4f * s, 13.6f * s, 12f * s, 19.9f * s, 12f * s, 19.9f * s)
                    close()
                })
                drawCircle(color, 2.2f * s, Offset(12f * s, 9.6f * s))
            }
            Glyph.FLAG -> {
                line(6f, 3.9f, 6f, 20.1f)
                drawPath(
                    p {
                        moveTo(6f * s, 4.6f * s)
                        lineTo(18.8f * s, 7.8f * s)
                        lineTo(6f * s, 13f * s)
                        close()
                    },
                    color,
                )
            }
        }
    }

    /** The cone-and-box body shared by the four voice glyphs. */
    private fun DrawScope.speaker(s: Float, w: Float, color: Color) {
        drawPath(
            Path().apply {
                moveTo(3f * s, 9.5f * s)
                lineTo(6.5f * s, 9.5f * s)
                lineTo(11.2f * s, 5f * s)
                lineTo(11.2f * s, 19f * s)
                lineTo(6.5f * s, 14.5f * s)
                lineTo(3f * s, 14.5f * s)
                close()
            },
            color,
        )
    }

    /** One L-shaped corner bracket, with the arms pointing [ax]/[ay]. */
    private fun DrawScope.corner(
        x: Float, y: Float, ax: Float, ay: Float, s: Float, w: Float, color: Color,
    ) {
        val arm = 5.2f
        drawPath(
            Path().apply {
                moveTo((x + ax * arm) * s, y * s)
                lineTo(x * s, y * s)
                lineTo(x * s, (y + ay * arm) * s)
            },
            color,
            style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // -----------------------------------------------------------------------
    // Extra marks
    // -----------------------------------------------------------------------

    /** One [Extra] mark, at the weight its rendered size calls for. */
    private fun DrawScope.drawExtra(kind: Extra, s: Float, color: Color) {
        drawExtra(kind, s, color, glyphWeight())
    }

    private fun DrawScope.drawExtra(kind: Extra, s: Float, color: Color, w: Float) {
        val stroke = stroke(w)
        fun p(build: Path.() -> Unit) = Path().apply(build)
        fun stroked(path: Path) = drawPath(path, color, style = stroke)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)

        when (kind) {
            Extra.CHEVRON_RIGHT -> stroked(p {
                // The mirror of [Glyph.BACK]. A chevron is tall and narrow on
                // purpose: it is a direction, and a direction drawn at the
                // width of a square reads as a bracket.
                moveTo(9f * s, 5.5f * s)
                lineTo(15.5f * s, 12f * s)
                lineTo(9f * s, 18.5f * s)
            })
            Extra.CHEVRON_DOWN -> stroked(p {
                moveTo(5.5f * s, 9f * s)
                lineTo(12f * s, 15.5f * s)
                lineTo(18.5f * s, 9f * s)
            })
            Extra.SHARE -> {
                // Three nodes and two edges. Outlined nodes, not filled ones:
                // three solid dots joined by hairlines is a filled shape
                // pretending to be a diagram, and at 16 dp the dots would be
                // the only thing left.
                ring(6.6f, 12f, 2.4f, s, color, w)
                ring(17.4f, 6.4f, 2.4f, s, color, w)
                ring(17.4f, 17.6f, 2.4f, s, color, w)
                line(8.9f, 10.9f, 15.2f, 7.4f)
                line(8.9f, 13.1f, 15.2f, 16.6f)
            }
            Extra.DIRECTIONS -> {
                // A road that turns and then goes: the same elbow the maneuver
                // arrows are built from, at the glyph weight, so "directions"
                // and the first instruction of the route look related.
                //
                // The elbow sits low enough for the head's upper arm to fit: an
                // arrow pointing right at the top of the box puts an arm above
                // the line it points along, and the live area is where that
                // lands.
                stroked(p {
                    moveTo(8f * s, 20.1f * s)
                    lineTo(8f * s, 11.6f * s)
                    quadraticTo(8f * s, 8.2f * s, 11.4f * s, 8.2f * s)
                    lineTo(15f * s, 8.2f * s)
                })
                arrowHead(Offset(19.4f * s, 8.2f * s), 0f, 6f, s, color, w)
            }
            Extra.SAVE -> stroked(bookmark(s))
            Extra.SAVE_FILLED -> drawPath(bookmark(s), color)
            Extra.FILTER -> stroked(p {
                // A funnel. The stem is where the glyph is read from: without
                // it the shape is a triangle, which is a warning.
                moveTo(4f * s, 5.2f * s)
                lineTo(20f * s, 5.2f * s)
                lineTo(13.6f * s, 12.6f * s)
                lineTo(13.6f * s, 19.6f * s)
                lineTo(10.4f * s, 17.6f * s)
                lineTo(10.4f * s, 12.6f * s)
                close()
            })
            Extra.LIST -> drawGlyph(Glyph.STEPS, s, color, w)
            Extra.MAP -> {
                // A folded map: three panels and two folds. The folds are what
                // say "map" rather than "flag" or "bookmark", so they are
                // drawn at full weight rather than as panel detail.
                stroked(p {
                    moveTo(9f * s, 4.5f * s)
                    lineTo(4f * s, 6.8f * s)
                    lineTo(4f * s, 19.5f * s)
                    lineTo(9f * s, 17.2f * s)
                    lineTo(15f * s, 19.5f * s)
                    lineTo(20f * s, 17.2f * s)
                    lineTo(20f * s, 4.5f * s)
                    lineTo(15f * s, 6.8f * s)
                    close()
                })
                line(9f, 4.5f, 9f, 17.2f)
                line(15f, 19.5f, 15f, 6.8f)
            }
            Extra.LAYERS -> {
                // Two tiers, not the three the reference uses. Three tiers in
                // a 20-unit live area leaves 5.3 units a tier, and a tier only
                // reads as a separate surface if the gap between it and the
                // next is wider than the stroke; two tiers keeps that gap.
                stroked(p {
                    moveTo(12f * s, 4f * s)
                    lineTo(20f * s, 9.2f * s)
                    lineTo(12f * s, 14.4f * s)
                    lineTo(4f * s, 9.2f * s)
                    close()
                })
                stroked(p {
                    moveTo(4f * s, 15f * s)
                    lineTo(12f * s, 20f * s)
                    lineTo(20f * s, 15f * s)
                })
            }
            Extra.PLUS -> drawGlyph(Glyph.ZOOM_IN, s, color, w)
            Extra.MINUS -> drawGlyph(Glyph.ZOOM_OUT, s, color, w)
            Extra.WALK -> {
                // A figure mid-stride: head, body, two legs. No arms, and the
                // reason is legibility at 16 dp rather than taste — an arm
                // hanging beside a torso runs parallel to it, and two parallel
                // strokes need a full stroke of daylight between them to read
                // as two. The stride is what says "walking", and the stride has
                // that daylight.
                ring(12.4f, 6f, 1.8f, s, color, w)
                stroked(p {
                    moveTo(12.2f * s, 8f * s)
                    lineTo(11.4f * s, 13.4f * s)
                })
                stroked(p {
                    moveTo(11.4f * s, 13.4f * s)
                    lineTo(8.6f * s, 16.4f * s)
                    lineTo(7.8f * s, 20.1f * s)
                })
                stroked(p {
                    moveTo(11.4f * s, 13.4f * s)
                    lineTo(14.8f * s, 16.4f * s)
                    lineTo(16.2f * s, 20.1f * s)
                })
            }
            Extra.CAR -> {
                // Side view: hull, cabin, two wheels. The wheels are rings, not
                // solid discs: a ring is what makes them wheels at the size
                // this is drawn at, and 2.1 units is the radius that keeps the
                // counter open at the light weight as well as the regular one.
                roundedBox(4f, 12f, 20f, 17.6f, 1.6f, s, color, w)
                stroked(p {
                    moveTo(7.2f * s, 12f * s)
                    lineTo(9.4f * s, 8f * s)
                    lineTo(14.6f * s, 8f * s)
                    lineTo(16.8f * s, 12f * s)
                })
                ring(7.6f, 17.6f, 2.1f, s, color, w)
                ring(16.4f, 17.6f, 2.1f, s, color, w)
            }
            Extra.PIN -> drawGlyph(Glyph.PLACE, s, color, w)
            Extra.STAR -> stroked(star(s))
            Extra.STAR_FILLED -> drawPath(star(s), color)
            Extra.CLOCK -> drawGlyph(Glyph.HISTORY, s, color, w)
            Extra.INFO -> {
                // A ring and an "i". The tittle is a [dot] rather than a small
                // filled circle so that it is exactly the stroke's width: an
                // "i" whose dot is thinner than its stem reads as a different
                // alphabet.
                ring(12f, 12f, 7.4f, s, color, w)
                dot(12f, 8.6f, s, color, w)
                line(12f, 11.4f, 12f, 15.6f)
            }
            Extra.WARNING -> drawGlyph(Glyph.REPORT, s, color, w)
            Extra.RETRY -> {
                // Two arcs, two heads: the sweep goes round and comes round
                // again. This is the one mark that says "again" rather than
                // "reorient" — [Glyph.ROTATED] is the single-headed circular
                // arrow and this is deliberately not it.
                //
                // Drawn with arcs rather than cubics because the two 50° gaps
                // are the drawing: narrow enough to read as one ring with two
                // ends, wide enough that the heads are not mistaken for a break
                // in it.
                drawArc(
                    color = color,
                    startAngle = 170f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(4.6f * s, 4.6f * s),
                    size = Size(14.8f * s, 14.8f * s),
                    style = stroke,
                )
                drawArc(
                    color = color,
                    startAngle = 350f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(4.6f * s, 4.6f * s),
                    size = Size(14.8f * s, 14.8f * s),
                    style = stroke,
                )
                sweepHead(Offset(18.64f * s, 7.29f * s), 30f, 5.4f, 1f, s, color, w)
                sweepHead(Offset(5.36f * s, 16.71f * s), 210f, 5.4f, 1f, s, color, w)
            }
            Extra.CHECK -> stroked(p {
                // A tick, deliberately smaller than the box: a tick drawn to
                // the live area reads as a bold X at a glance, and the whole
                // job of this mark is to be unambiguous at 16 dp.
                moveTo(5.4f * s, 12.6f * s)
                lineTo(9.8f * s, 17.2f * s)
                lineTo(18.6f * s, 6.6f * s)
            })
            Extra.DELETE -> {
                // Lid, handle, tapered body. No ribs: at the regular weight two
                // ribs inside a 10-unit bin merge into one bar, so the
                // silhouette carries it.
                line(4.4f, 7.4f, 19.6f, 7.4f)
                stroked(p {
                    moveTo(9.4f * s, 7.4f * s)
                    lineTo(9.4f * s, 4.6f * s)
                    lineTo(14.6f * s, 4.6f * s)
                    lineTo(14.6f * s, 7.4f * s)
                })
                stroked(p {
                    moveTo(6.4f * s, 7.4f * s)
                    lineTo(7.2f * s, 19.6f * s)
                    lineTo(16.8f * s, 19.6f * s)
                    lineTo(17.6f * s, 7.4f * s)
                })
            }
            Extra.OFFLINE -> {
                // A cloud with a line through it, not a struck-through wifi
                // fan: the fan's arcs are sub-pixel at 16 dp and the cloud is
                // the shape the offline state's own copy talks about.
                stroked(p {
                    moveTo(7.6f * s, 17.4f * s)
                    quadraticTo(4.4f * s, 17.4f * s, 4.4f * s, 14.6f * s)
                    quadraticTo(4.4f * s, 12f * s, 7.2f * s, 11.7f * s)
                    quadraticTo(7.6f * s, 8.4f * s, 11.4f * s, 8.4f * s)
                    quadraticTo(14.4f * s, 8.4f * s, 15.6f * s, 11f * s)
                    quadraticTo(19.2f * s, 11.3f * s, 19.2f * s, 14.4f * s)
                    quadraticTo(19.2f * s, 17.4f * s, 16f * s, 17.4f * s)
                    close()
                })
                line(5f, 4.8f, 19f, 19.2f)
            }
            Extra.SUN -> {
                // A disc with eight spokes, the spokes starting *on* the ring
                // so they emerge from it. Rays separated from the disc need a
                // full stroke of daylight between two inks, and inside 20 units
                // that leaves them shorter than the stroke is wide — eight
                // dots, which is a flower rather than a sun.
                ring(12f, 12f, 4.6f, s, color, w)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    line(
                        12f + 4.6f * dx, 12f + 4.6f * dy,
                        12f + 8.1f * dx, 12f + 8.1f * dy,
                    )
                }
            }
            Extra.MOON -> stroked(p {
                // A crescent: the outer arc of a disc and an inner arc that
                // cuts it. The inner arc's controls are what set the
                // thickness — pulled left it is a sliver, and a sliver at
                // 16 dp is a bracket.
                moveTo(14.8f * s, 4.4f * s)
                cubicTo(8.6f * s, 5.4f * s, 4.4f * s, 8.4f * s, 4.4f * s, 12f * s)
                cubicTo(4.4f * s, 15.6f * s, 8.6f * s, 18.6f * s, 14.8f * s, 19.6f * s)
                cubicTo(11f * s, 17.2f * s, 11f * s, 6.8f * s, 14.8f * s, 4.4f * s)
                close()
            })
            Extra.COFFEE -> {
                // A cup, a rim and a handle. The rim is a straight line across
                // the top because a cup seen from the side is open, and the
                // handle is the only thing that separates it from a bucket.
                line(4f, 7f, 16.4f, 7f)
                stroked(p {
                    moveTo(4.6f * s, 7f * s)
                    lineTo(5.8f * s, 16.2f * s)
                    quadraticTo(6f * s, 17.2f * s, 7f * s, 17.2f * s)
                    lineTo(13.4f * s, 17.2f * s)
                    quadraticTo(14.4f * s, 17.2f * s, 14.6f * s, 16.2f * s)
                    lineTo(15.8f * s, 7f * s)
                })
                stroked(p {
                    moveTo(15.8f * s, 9f * s)
                    cubicTo(19.6f * s, 9f * s, 19.6f * s, 14f * s, 15.8f * s, 14f * s)
                })
            }
            Extra.FUEL -> {
                // A pump: body and hose. The hose leaves the body's shoulder
                // and comes down beside it, which is the one detail that makes
                // the box a pump rather than a suitcase.
                roundedBox(5f, 7f, 14.6f, 19.6f, 1.6f, s, color, w)
                stroked(p {
                    moveTo(14.6f * s, 10.2f * s)
                    quadraticTo(18.6f * s, 10.2f * s, 18.6f * s, 13.6f * s)
                    lineTo(18.6f * s, 17.4f * s)
                })
            }
            Extra.PARKING -> {
                // The sign, not the letter alone: a bare "P" is a letter, and
                // a letter at 16 dp in a single colour is a word the driver has
                // to read. The rounded square is the sign they are looking for.
                roundedBox(4f, 4f, 20f, 20f, 2.6f, s, color, w)
                stroked(p {
                    moveTo(8.6f * s, 17.4f * s)
                    lineTo(8.6f * s, 6.2f * s)
                    lineTo(12.6f * s, 6.2f * s)
                    quadraticTo(15.4f * s, 6.2f * s, 15.4f * s, 9.9f * s)
                    quadraticTo(15.4f * s, 13.6f * s, 12.6f * s, 13.6f * s)
                    lineTo(8.6f * s, 13.6f * s)
                })
            }
            Extra.ARROW_UP -> {
                stroked(p {
                    moveTo(12f * s, 20.1f * s)
                    lineTo(12f * s, 8.6f * s)
                })
                arrowHead(Offset(12f * s, 4.6f * s), 270f, 6f, s, color, w)
            }
            Extra.ARROW_DOWN -> {
                stroked(p {
                    moveTo(12f * s, 3.9f * s)
                    lineTo(12f * s, 15.4f * s)
                })
                arrowHead(Offset(12f * s, 19.4f * s), 90f, 6f, s, color, w)
            }
            Extra.ARROW_LEFT -> {
                stroked(p {
                    moveTo(20.1f * s, 12f * s)
                    lineTo(8.6f * s, 12f * s)
                })
                arrowHead(Offset(4.6f * s, 12f * s), 180f, 6f, s, color, w)
            }
            Extra.ARROW_RIGHT -> {
                stroked(p {
                    moveTo(3.9f * s, 12f * s)
                    lineTo(15.4f * s, 12f * s)
                })
                arrowHead(Offset(19.4f * s, 12f * s), 0f, 6f, s, color, w)
            }
            // A solid disc, and one of the three marks allowed to be solid: a
            // dot with a hole in it is a ring, which is a different mark.
            Extra.DOT -> drawCircle(color, 4f * s, Offset(12f * s, 12f * s))

            Extra.BAG -> {
                // The body first, then the handle over it. A bag drawn without
                // the handle is a bucket, which is the same trap [COFFEE]
                // records.
                // Near-vertical sides. The first version tapered them hard
                // toward the base, which with a small handle reads as a bucket
                // — the same failure [COFFEE] records for a cup without its
                // handle.
                stroked(p {
                    moveTo(6f * s, 9f * s)
                    lineTo(18f * s, 9f * s)
                    lineTo(17.4f * s, 19f * s)
                    quadraticTo(17.3f * s, 20.2f * s, 16.1f * s, 20.2f * s)
                    lineTo(7.9f * s, 20.2f * s)
                    quadraticTo(6.7f * s, 20.2f * s, 6.6f * s, 19f * s)
                    close()
                })
                // A tall half-round handle that clears the mouth, so the
                // silhouette is unmistakably a carried bag.
                stroked(p {
                    moveTo(9f * s, 9f * s)
                    lineTo(9f * s, 7f * s)
                    cubicTo(9f * s, 2.9f * s, 15f * s, 2.9f * s, 15f * s, 7f * s)
                    lineTo(15f * s, 9f * s)
                })
            }

            Extra.BED -> {
                // Headboard, mattress, one pillow, two feet. The pillow is what
                // stops it reading as a sofa.
                line(4f, 7f, 4f, 18.5f)
                stroked(p {
                    moveTo(4f * s, 12.2f * s)
                    lineTo(18f * s, 12.2f * s)
                    quadraticTo(20f * s, 12.2f * s, 20f * s, 14.2f * s)
                    lineTo(20f * s, 17f * s)
                    lineTo(4f * s, 17f * s)
                })
                line(20f, 17f, 20f, 18.5f)
                stroked(p {
                    moveTo(7f * s, 12.2f * s)
                    lineTo(7f * s, 10.4f * s)
                    lineTo(11.6f * s, 10.4f * s)
                    lineTo(11.6f * s, 12.2f * s)
                })
            }

            Extra.DOME -> {
                // A half-round dome on a base, with a finial above it. Drawn
                // generically on purpose: it has to read for a mosque, a church
                // and a temple, because the tag this stands for is
                // `place_of_worship` and Vector does not know which.
                stroked(p {
                    moveTo(5.5f * s, 19f * s)
                    lineTo(5.5f * s, 13f * s)
                    cubicTo(5.5f * s, 7.5f * s, 18.5f * s, 7.5f * s, 18.5f * s, 13f * s)
                    lineTo(18.5f * s, 19f * s)
                })
                line(4f, 19f, 20f, 19f)
                line(12f, 7.6f, 12f, 4.6f)
            }

            Extra.TREE -> {
                // A round canopy and a short trunk.
                drawCircle(color, 5.4f * s, Offset(12f * s, 10f * s), style = stroke)
                line(12f, 15.4f, 12f, 20f)
            }

            Extra.TOWER -> {
                // A tall block with windows. Two columns of windows rather than
                // a grid: at 20 dp a grid fills in and reads as hatching.
                stroked(p {
                    moveTo(7f * s, 20f * s)
                    lineTo(7f * s, 5f * s)
                    lineTo(17f * s, 5f * s)
                    lineTo(17f * s, 20f * s)
                })
                line(4.5f, 20f, 19.5f, 20f)
                line(10f, 8.5f, 10f, 10f)
                line(14f, 8.5f, 14f, 10f)
                line(10f, 13f, 10f, 14.5f)
                line(14f, 13f, 14f, 14.5f)
            }
        }
    }
}
