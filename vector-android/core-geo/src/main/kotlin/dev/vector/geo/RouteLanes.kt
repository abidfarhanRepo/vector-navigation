package dev.vector.geo

import kotlin.math.max
import kotlin.math.min

/**
 * Where the route sits ACROSS the road, as one fact.
 *
 * ## The lie this exists to stop telling
 *
 * Until V7 Stage 4 every route object — ribbon, chevrons, puck — was drawn on
 * the way CENTRELINE, and the ribbon was sized from `approach_lanes`. On a
 * one-way carriageway that is right, because the way centreline is the
 * carriageway centreline and `lanes` counts the one direction that exists.
 *
 * On a **two-way** way it is neither. OSM counts both directions in `lanes`, so
 * Qatar's 5,728 two-way `lanes=2` ways are one lane each way — and a 5.6 m
 * ribbon centred on the centreline of a 7 m road covers 2.8 m of the ONCOMING
 * lane, with the puck sitting on the paint. The driver is shown driving into
 * traffic. That is not polish; it is the single biggest spatial untruth in the
 * navigation view, and it is on more journeys than lane guidance ever touches.
 *
 * ## What this model does and does not claim
 *
 * Three different things get confused the moment anyone says "which lane":
 *
 *  1. **Route lane band** — what the route and map data say the driver should
 *     use: the driven carriageway, or the lanes of it that serve the next
 *     maneuver. This model computes that, from [Approach] data the backend
 *     already sends.
 *  2. **Vehicle measured position** — what GPS knows. At 1 Hz and several
 *     metres of error, against a 3.5 m lane, **it does not know the lane**.
 *     This model therefore has no input for it and no output describing it.
 *  3. **Rendered vehicle position** — where the puck is drawn. It is drawn
 *     inside (1), because the route's own carriageway is the best available
 *     statement of where the vehicle is, and being on the correct side of a
 *     two-way road is strictly more truthful than being on its centreline.
 *
 * Nothing here may be read as "the vehicle is measured to be in lane N". The
 * vocabulary is deliberate: [Lateral.offsetM] is a RENDERED offset, its
 * [Lateral.basis] names the evidence, and [Basis.UNKNOWN] means the model
 * declines — in which case the offset is zero and the view behaves exactly as
 * it did before Stage 4.
 *
 * ## Sign convention
 *
 * **Positive is to the RIGHT of the direction of travel.** This is not a free
 * choice; it is the only value that agrees with the two things that consume it:
 *
 *  * MapLibre's `line-offset` — "a positive value offsets the line to the
 *    right, relative to the direction of the line", which is what
 *    `VectorStyle.dividerOffset` already relies on to put near-side lane
 *    dividers at negative offsets;
 *  * [RouteGeometry.offsetPoint], the app's single lateral normal, which
 *    [RouteChevrons] now draws its wings with as well.
 *
 * There is deliberately no offset arithmetic in this file. Placing the route
 * across the road and placing a chevron's wings across the route are the same
 * operation, and the day they are written twice is the day one of them is
 * rewritten with the opposite sign.
 *
 * A model with the opposite sign would place the route in the oncoming lane
 * with total confidence, which is the failure it exists to prevent, so
 * [RouteLanesTest] asserts the sign against both of those independently.
 *
 * Pure Kotlin, on purpose and for the usual reason: which side of a road the
 * route is drawn on is a decision that must not need a device to check.
 */
object RouteLanes {

    /**
     * One lane, in metres.
     *
     * The same 3.5 m `VectorStyle.LANE_WIDTH_M` sizes the basemap carriageway
     * from, restated here because `core-geo` cannot see the app module and the
     * two MUST agree — an offset computed against 3.5 m lanes and rendered
     * between 3.65 m dividers would put the route on the paint. `CarriagewayTest`
     * asserts the equality from the side that can see both.
     */
    const val LANE_WIDTH_M = 3.5

    /**
     * Which side of the road traffic drives on, as a sign on [LANE_WIDTH_M].
     *
     * Qatar drives on the right, so the driven carriageway of a two-way way is
     * the right half of it and the offset is positive. Named rather than
     * inlined because it is an assumption about a COUNTRY, not about geometry,
     * and the day Vector ships somewhere left-hand this is the one line that
     * changes — as opposed to a `+` buried in an expression that nobody can
     * find and everybody would get wrong.
     */
    const val DRIVE_SIDE = 1.0

    /**
     * How far a lateral change is spread along the route, in metres.
     *
     * A lane change is not a step. A driver crossing one 3.5 m lane at arterial
     * speed covers 40–60 m doing it, and the ribbon has to move the way a car
     * moves or it reads as a glitch rather than as an instruction. 50 m is the
     * middle of that, and it is also short enough that two maneuvers 120 m
     * apart in Msheireb still get a settled section between their tapers.
     *
     * The same length is used to leave a junction, where it does a second job:
     * the route's own geometry through a turn is a curve of unknown radius, and
     * tapering the offset across the first 50 m of the new leg keeps the ribbon
     * from swinging sideways at the exact moment the driver is looking at it.
     */
    const val TAPER_M = 50.0

    /**
     * How far before a maneuver the route may claim a specific LANE band.
     *
     * Deliberately equal to `LaneGuidance.MIN_LANE_LEAD_M`, the FLOOR of the
     * window the lane strip uses, and deliberately not the strip's own
     * speed-dependent window: the map's sections are built once when a route
     * arrives, where no speed is known, and inventing one would be a guess
     * baked into geometry.
     *
     * Taking the floor rather than the cap means the map band can never open
     * EARLIER than the strip that explains it — at 100 km/h the strip appears
     * at 694 m and the map follows at 300 m, which reads as the view
     * confirming the instruction. The reverse, a ribbon sliding into a turn
     * lane with nothing on screen saying why, is the case worth preventing.
     */
    const val BAND_LEAD_M = LaneGuidance.MIN_LANE_LEAD_M

    /** What the lateral offset is derived FROM. Never "where the vehicle is". */
    enum class Basis {
        /**
         * No usable statement. Offset is 0 — the pre-Stage-4 centreline
         * behaviour — because a centreline is a known approximation and a
         * guessed side of the road is a fabrication.
         */
        UNKNOWN,

        /**
         * The driven carriageway's centre, from the direction-specific lane
         * count. This is the case that fixes two-way roads.
         */
        CARRIAGEWAY,

        /**
         * The centre of the lanes that serve the upcoming maneuver, from
         * `turn:lanes` in driver order.
         */
        TURN_LANES,

        /**
         * The outer lane on the side the maneuver turns to — the INFERENCE made
         * when a turn carries no `turn:lanes` at all.
         *
         * ## Why this is not [TURN_LANES]
         *
         * Every other basis in this enum names the evidence it was read from.
         * This one names a RULE, and separating it is the whole point: on a
         * right-hand-drive road a right turn is made from the right-hand lane,
         * which is true of almost every junction and is still a statement about
         * traffic law rather than about this road. A renderer, a test or a
         * future telemeter that needs to know which placements were measured
         * and which were reasoned can ask.
         *
         * ## Why it beats the alternative
         *
         * The fallback it replaces is [CARRIAGEWAY] — the middle of the driven
         * carriageway — and on a two-lane carriageway that is the paint between
         * the lanes. For a THROUGH maneuver the centre is right and stays; for
         * a turn it is the one place the driver certainly is not, which is what
         * "exits start from the middle lane rather than the last lane on the
         * right" reported from a drive.
         */
        OUTER_LANE,

        /** Between two of the above: a lane change in progress, as geometry. */
        TAPER,
    }

    /**
     * The lane facts about ONE leg, taken from the maneuver it leads into.
     *
     * Mirrors what `/navigate` already sends per step — nothing here is new
     * data — but as plain numbers, so the model stays testable without the
     * client's types.
     */
    data class Approach(
        /** Distance from the route start to the maneuver this leg ends at. */
        val atM: Double,
        /**
         * Lanes in the direction DRIVEN (`lane_data.forward_lanes`), or null
         * when the backend could not say. Null is an answer, not a gap: it
         * means stay on the centreline.
         */
        val forwardLanes: Int? = null,
        /**
         * The raw `lanes` tag (`approach_lanes`), which on a two-way way counts
         * BOTH directions. Its disagreement with [forwardLanes] is exactly what
         * tells this model the way is two-way.
         */
        val totalLanes: Int? = null,
        /**
         * The maneuver's useful lanes in driver order, already marked by
         * [LaneGuidance.usefulLanes] — empty when lane guidance narrows
         * nothing, which is also when no lane band may be claimed.
         */
        val lanes: List<LaneGuidance.Lane> = emptyList(),
        /**
         * Which way the maneuver turns, or [Turn.NONE].
         *
         * Used for ONE thing: placing the ribbon when the road carries no
         * `turn:lanes` at all. See [outerLaneOf].
         */
        val turn: Turn = Turn.NONE,
    )

    /**
     * The side a maneuver turns to, so a turn with no lane data can still be
     * placed in a lane rather than on the paint.
     *
     * A closed vocabulary of three, and the caller derives it from the router's
     * own maneuver type — the same mapping [LaneGuidance.acceptedIndications]
     * already owns, so there is one answer in this codebase to "which way does
     * `slight-left` go".
     */
    enum class Turn {
        NONE, LEFT, RIGHT;

        companion object {
            /**
             * The turn side of a router maneuver type.
             *
             * Only types that ARE a turn. `roundabout` is deliberately absent:
             * which exit is being taken is not in the type, and a roundabout's
             * circulating lanes are concentric, so the outer lane on the way IN
             * says nothing about the way OUT. `merge`, `continue` and `arrive`
             * are not lane decisions either.
             */
            fun of(maneuverType: String): Turn = when (maneuverType) {
                "turn-left", "slight-left", "sharp-left", "keep-left" -> LEFT
                "turn-right", "slight-right", "sharp-right", "keep-right" -> RIGHT
                else -> NONE
            }
        }
    }

    /** Where the route is drawn laterally at one point, and on what evidence. */
    data class Lateral(
        /** Metres right of the route centreline; negative is left. */
        val offsetM: Double,
        val basis: Basis,
        /**
         * The width the route may honestly occupy here — the driven carriageway,
         * or the claimed lane band — or null when unknown, in which case the
         * renderer keeps its own default.
         */
        val widthM: Double? = null,
    ) {
        /**
         * Zero offset, no claim. The literal pre-Stage-4 behaviour, returned
         * wherever the model declines, so "no lane data" is a supported state
         * rather than a special case every caller has to spell.
         */
        companion object {
            val NONE = Lateral(0.0, Basis.UNKNOWN, null)
        }
    }

    /** One control point of the lateral profile. */
    internal data class Node(
        val alongM: Double,
        val offsetM: Double,
        val basis: Basis,
        val widthM: Double?,
    )

    /**
     * The lateral profile of a whole route: offset as a piecewise-linear
     * function of distance along it.
     *
     * A profile rather than a list of sections because every transition in this
     * model is a taper, and a polyline of control points expresses "settled,
     * then moving, then settled" without a second concept. Collapse falls out
     * of it too: two maneuvers too close together simply produce control points
     * too close together, and the offset ramps straight from one band to the
     * next instead of pumping out to the carriageway centre and back.
     */
    class Plan internal constructor(internal val nodes: List<Node>) {

        /** True when nothing in this route could be placed laterally. */
        val isEmpty: Boolean get() = nodes.isEmpty()

        /**
         * The lateral fact at [alongM].
         *
         * Clamps at both ends: before the first control point and after the
         * last, the nearest one holds. A route does not become laterally
         * undefined because the driver is 3 m behind its first vertex.
         */
        fun lateralAt(alongM: Double): Lateral {
            if (nodes.isEmpty()) return Lateral.NONE
            if (alongM <= nodes.first().alongM) return nodes.first().toLateral()
            if (alongM >= nodes.last().alongM) return nodes.last().toLateral()
            // Linear scan from a binary search: profiles are a few nodes per
            // maneuver, so this is tens of entries on a city route and is read
            // once per frame at most.
            var lo = 0
            var hi = nodes.size - 1
            while (lo < hi - 1) {
                val mid = (lo + hi) ushr 1
                if (nodes[mid].alongM <= alongM) lo = mid else hi = mid
            }
            val a = nodes[lo]
            val b = nodes[lo + 1]
            val span = b.alongM - a.alongM
            if (span <= 0.0) return b.toLateral()
            val t = ((alongM - a.alongM) / span).coerceIn(0.0, 1.0)
            // Moving is a taper WHATEVER the two ends call themselves. Two
            // CARRIAGEWAY nodes at different offsets are a lane change between
            // two carriageways — a driver leaving a one-way street onto a
            // two-way one crosses 1.75 m of road, and reporting that as
            // "settled on the carriageway" is how a renderer ends up drawing a
            // moving ribbon as a static one.
            val basis = if (a.offsetM != b.offsetM) Basis.TAPER
                        else if (a.basis == b.basis) a.basis
                        else b.basis
            return Lateral(
                offsetM = a.offsetM + (b.offsetM - a.offsetM) * t,
                basis = basis,
                widthM = interpolateWidth(a.widthM, b.widthM, t),
            )
        }

        /** Shorthand for the only number most callers want. */
        fun offsetAt(alongM: Double): Double = lateralAt(alongM).offsetM

        /**
         * The profile as constant-offset pieces, for renderers that cannot vary
         * an offset along one feature.
         *
         * MapLibre's `line-offset` is a paint property: it takes one value per
         * FEATURE, so a tapering ribbon has to be several features. [stepM]
         * caps how much the offset may change within a piece — a taper is cut
         * into as many pieces as it needs and a settled section stays one —
         * and each piece reports the offset at its own midpoint, so the
         * staircase straddles the true ramp instead of lagging it.
         *
         * @param stepM the largest lateral error a single piece may carry.
         *   0.25 m is a quarter of the puck's width and well under the ~0.5 m
         *   a 3.5 m lane at driving zoom resolves to on screen.
         */
        fun slices(stepM: Double = 0.25): List<Slice> {
            if (nodes.size < 2 || stepM <= 0.0) return emptyList()
            val out = ArrayList<Slice>(nodes.size * 2)
            for (i in 0 until nodes.size - 1) {
                val a = nodes[i]
                val b = nodes[i + 1]
                val span = b.alongM - a.alongM
                if (span <= 0.0) continue
                val drop = kotlin.math.abs(b.offsetM - a.offsetM)
                val parts = max(1, kotlin.math.ceil(drop / stepM).toInt())
                val partLen = span / parts
                for (p in 0 until parts) {
                    val from = a.alongM + partLen * p
                    val to = from + partLen
                    val mid = lateralAt((from + to) * 0.5)
                    out.add(Slice(from, to, mid.offsetM, mid.basis, mid.widthM))
                }
            }
            return out
        }

        private fun Node.toLateral() = Lateral(offsetM, basis, widthM)

        private fun interpolateWidth(a: Double?, b: Double?, t: Double): Double? {
            // A known width and an unknown one do not average. Unknown wins,
            // because the alternative is drawing a carriageway that half exists.
            if (a == null || b == null) return null
            return a + (b - a) * t
        }
    }

    /** A run of route with one constant lateral offset. */
    data class Slice(
        val fromM: Double,
        val toM: Double,
        val offsetM: Double,
        val basis: Basis,
        val widthM: Double?,
    )

    /**
     * Build the lateral profile for a route from its per-leg lane facts.
     *
     * [approaches] must be in route order; each describes the leg that ENDS at
     * its own [Approach.atM], which is how the backend resolves lane data
     * (`_lane_fields` reads the approach segment, not the departure one). A
     * zero-length leg — the departure maneuver at 0 m, or two maneuvers at the
     * same vertex — contributes nothing and is skipped rather than producing a
     * degenerate control point.
     */
    fun plan(approaches: List<Approach>): Plan {
        val nodes = ArrayList<Node>(approaches.size * 4)
        var legStart = 0.0
        var first = true
        for (a in approaches) {
            val end = a.atM
            val len = end - legStart
            if (len <= 0.0) {
                // Still advance: a maneuver at the same vertex as the last one
                // has no leg of its own, and pretending otherwise would let a
                // later leg start before an earlier one ended.
                legStart = max(legStart, end)
                continue
            }
            val carriageway = carriagewayOf(a)
            // The band the maneuver is made from: `turn:lanes` where the road
            // states them, and the outer lane on the side of the turn where it
            // does not. A through maneuver has neither and keeps the
            // carriageway centre, exactly as before.
            val band = bandOf(a, carriageway) ?: outerLaneOf(a, carriageway)

            // Where this leg's own carriageway offset is established. On every
            // leg but the first that is one taper past the junction, so the
            // ribbon leaves a turn along the road rather than across it.
            val settleAt = if (first) legStart else legStart + min(TAPER_M, len * 0.5)

            val room = end - settleAt
            if (band == null) {
                nodes.add(Node(settleAt, carriageway.offsetM, carriageway.basis, carriageway.widthM))
                nodes.add(Node(end, carriageway.offsetM, carriageway.basis, carriageway.widthM))
            } else if (room <= 2 * TAPER_M) {
                // Not long enough to reach the carriageway centre AND leave it
                // again. The carriageway node is dropped ENTIRELY rather than
                // squeezed in, so the route ramps from wherever it was straight
                // to the band.
                //
                // This is the dense-junction correction and it is worth being
                // precise about. Keeping the node and shortening the tapers
                // looks like the conservative choice and is the broken one: on
                // Msheireb's six maneuvers 120 m apart, each with its own turn
                // lane, it makes the ribbon arrive in the left lane, return to
                // the carriageway centre, and cross back — three times a block,
                // ten direction reversals over 720 m. A driver is not weaving;
                // they stay in the turn lane. Dropping the node says exactly
                // that, and it falls out as a flat profile rather than as a
                // smoothing rule.
                nodes.add(Node(end, band.offsetM, band.basis, band.widthM))
            } else {
                nodes.add(Node(settleAt, carriageway.offsetM, carriageway.basis, carriageway.widthM))
                val bandStart = max(end - BAND_LEAD_M, settleAt + TAPER_M)
                nodes.add(Node(bandStart - TAPER_M, carriageway.offsetM, carriageway.basis, carriageway.widthM))
                nodes.add(Node(bandStart, band.offsetM, band.basis, band.widthM))
                nodes.add(Node(end, band.offsetM, band.basis, band.widthM))
            }
            legStart = end
            first = false
        }
        return Plan(compact(nodes))
    }

    /**
     * Drop control points that say nothing.
     *
     * Three consecutive nodes on the same offset are a straight line with a
     * spare vertex in it, and every spare vertex is a slice the renderer pays
     * for. Also enforces monotonic distance, so a malformed maneuver list
     * cannot produce a profile that runs backwards.
     */
    private fun compact(raw: List<Node>): List<Node> {
        if (raw.isEmpty()) return raw
        val mono = ArrayList<Node>(raw.size)
        for (n in raw) {
            val last = mono.lastOrNull()
            if (last == null) { mono.add(n); continue }
            if (n.alongM < last.alongM) continue
            if (n.alongM == last.alongM) { mono[mono.size - 1] = n; continue }
            mono.add(n)
        }
        if (mono.size < 3) return mono
        val out = ArrayList<Node>(mono.size)
        out.add(mono.first())
        for (i in 1 until mono.size - 1) {
            val p = mono[i - 1]
            val c = mono[i]
            val n = mono[i + 1]
            val flat = p.offsetM == c.offsetM && c.offsetM == n.offsetM &&
                p.basis == c.basis && c.basis == n.basis && p.widthM == c.widthM && c.widthM == n.widthM
            if (!flat) out.add(c)
        }
        out.add(mono.last())
        return out
    }

    /**
     * The driven carriageway's centre, relative to the way centreline.
     *
     * The whole two-way correction is these six lines. `lanes` counting both
     * directions and `forward_lanes` counting one is what identifies a two-way
     * way without a separate flag on the wire, and the driven half of it is the
     * right half where traffic drives on the right.
     *
     * Declines — [Basis.UNKNOWN], offset 0 — in three cases, all of them real:
     *
     *  * no `forward_lanes`, so there is no carriageway to be the centre of;
     *  * no `lanes`, so it is unknown whether the centreline is the ROAD's or
     *    the CARRIAGEWAY's, and the two differ by half a road;
     *  * counts that are neither equal nor double, which is a tagging state
     *    this model has no reading of and must not invent one for.
     */
    internal fun carriagewayOf(a: Approach): Lateral {
        val fwd = a.forwardLanes ?: return Lateral.NONE
        if (fwd <= 0) return Lateral.NONE
        val total = a.totalLanes ?: return Lateral.NONE
        val width = fwd * LANE_WIDTH_M
        return when (total) {
            // One-way: the way centreline IS the carriageway centreline, which
            // is why 96% of Qatar's arterial network was already drawn right.
            fwd -> Lateral(0.0, Basis.CARRIAGEWAY, width)
            // Two-way: the driven carriageway is one half, so its centre is
            // half a carriageway off the centreline, on the driving side.
            2 * fwd -> Lateral(DRIVE_SIDE * width * 0.5, Basis.CARRIAGEWAY, width)
            else -> Lateral.NONE
        }
    }

    /**
     * The centre of the lanes that serve the maneuver, when one can be claimed.
     *
     * Requires all of:
     *
     *  * a carriageway to measure from — lane N of an unplaced carriageway is
     *    not a position;
     *  * a lane string whose cell count MATCHES the carriageway's lane count,
     *    because `left|through` against three forward lanes describes a road
     *    this one is not, and stretching it to fit would move the band by half
     *    a lane per missing cell;
     *  * valid lanes that are CONTIGUOUS. `left|through|left` is two separate
     *    places to be and their average is the one lane that does not turn.
     *    There is no honest single offset for it, so the model keeps the
     *    carriageway and lets the lane strip — which can show two — do the
     *    talking.
     */
    /**
     * The outer lane on the side a turn is made from — the answer when the road
     * carries no `turn:lanes` at all.
     *
     * ## The defect this exists for
     *
     * Without lane data the ribbon fell back to [carriagewayOf], the centre of
     * the driven carriageway, for EVERY maneuver including turns. On a two-lane
     * carriageway that centre is the paint between the lanes, so a right turn
     * was drawn from the middle of the road — reported from a drive as "exits
     * into certain lanes start from the middle lane rather than the last lane
     * on the right hand side". The through case is right and is unchanged; the
     * turn case was the one place the fallback was certainly wrong.
     *
     * ## What it claims, and what it does not
     *
     * It claims a LANE, from a rule rather than from data, and says so through
     * [Basis.OUTER_LANE]. That separation matters: everything else in this file
     * can be traced to a tag on this road, and this cannot. It is the same kind
     * of statement as the class-median lane count the style already guesses for
     * a road with no `lanes` tag, and it is made only where the alternative is
     * a position the vehicle is provably not in.
     *
     * Declines — null, so the caller keeps the carriageway centre — when:
     *
     *  * the maneuver is not a turn ([Turn.NONE]: a through, a merge, an
     *    arrival, or a roundabout, where the outer lane on the way in says
     *    nothing about the way out);
     *  * the carriageway is unknown, because a lane of an unplaced carriageway
     *    is not a position;
     *  * there is only ONE forward lane, which is its own outer lane and needs
     *    no rule to find.
     */
    internal fun outerLaneOf(a: Approach, carriageway: Lateral): Lateral? {
        if (carriageway.basis != Basis.CARRIAGEWAY) return null
        if (a.turn == Turn.NONE) return null
        val fwd = a.forwardLanes ?: return null
        if (fwd < 2) return null
        // The driven carriageway spans `fwd` lanes about its own centre, so the
        // outermost lane's centre is half of `fwd - 1` lanes either side of it.
        // Positive is right, which is the file's one sign convention.
        val step = LANE_WIDTH_M * (fwd - 1) * 0.5
        val offset = carriageway.offsetM + if (a.turn == Turn.RIGHT) step else -step
        return Lateral(offset, Basis.OUTER_LANE, LANE_WIDTH_M)
    }

    internal fun bandOf(a: Approach, carriageway: Lateral): Lateral? {
        if (carriageway.basis != Basis.CARRIAGEWAY) return null
        val fwd = a.forwardLanes ?: return null
        val lanes = a.lanes
        if (lanes.size != fwd || lanes.isEmpty()) return null
        val firstValid = lanes.indexOfFirst { it.valid }
        if (firstValid < 0) return null
        val lastValid = lanes.indexOfLast { it.valid }
        for (i in firstValid..lastValid) if (!lanes[i].valid) return null
        // Every lane valid is not a band: it narrows nothing, and moving the
        // ribbon to the centre of "all of them" is where it already is.
        if (firstValid == 0 && lastValid == lanes.size - 1) return null
        // Left edge of the driven carriageway, from its centre and its width.
        val leftEdge = carriageway.offsetM - fwd * LANE_WIDTH_M * 0.5
        val count = lastValid - firstValid + 1
        val centre = leftEdge + (firstValid + lastValid + 1) * 0.5 * LANE_WIDTH_M
        return Lateral(centre, Basis.TURN_LANES, count * LANE_WIDTH_M)
    }

    /**
     * The route's own geometry, moved onto the carriageway it describes.
     *
     * The puck and any renderer that cannot offset a line itself both need
     * this: a position on the centreline plus the profile gives a position in
     * the driven carriageway. [bearingDeg] is the route's bearing there, which
     * the caller already has from [RouteIndex.pointAt].
     */
    fun place(plan: Plan, p: LngLat, bearingDeg: Double, alongM: Double): LngLat =
        RouteGeometry.offsetPoint(p, bearingDeg, plan.offsetAt(alongM))
}
