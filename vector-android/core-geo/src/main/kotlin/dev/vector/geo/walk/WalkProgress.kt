package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex

/**
 * Where a walker is on a walking route, and which maneuver is next (V7.4 4C.1).
 *
 * ## What this is
 *
 * State DERIVATION, and only that. It answers the questions a walking UI will
 * need — where am I, what is next, how far, have I passed it, what am I
 * walking on, how much is left — from the frozen 4B.4 contract plus one
 * along-route distance. It produces no sentence, no voice, no camera and no
 * banner; those are 4C.2+ decisions and this file deliberately does not make
 * them.
 *
 * ## Why along-route distance and not string matching
 *
 * The backend's `index` and `distance_m` are authoritative, and they are
 * exact: measured across the six committed real-Qatar fixtures (182
 * maneuvers, 53 m to 25.5 km), the cumulative geometry distance at
 * `maneuver.index` and the maneuver's own `distance_m` agree to within
 * **0.07 m**. So a maneuver's position is simply its `distance_m`, and
 * progression is a comparison between two numbers rather than a search for a
 * road name — which is the failure mode this avoids, because a Qatari walk's
 * roads are frequently unnamed (`primary_link`, `name: null`) and frequently
 * repeat.
 *
 * ## Spans: the thing driving never had
 *
 * A driver's turn happens AT a junction. A person is ON a crossing for a
 * while — 8.8 m to 156.7 m on the real fixtures, a median of 70.9 m, which at
 * walking pace is most of a minute. So crossings and stairs are modelled as
 * spans with a distinct [WalkEventStatus.ACTIVE] state, and "the next
 * maneuver" is not the only question the UI can ask.
 */
class WalkRoute private constructor(
    val contract: WalkContract,

    /**
     * The 4B.2 plan, in route order.
     *
     * Sorted by `distance_m` defensively. The backend guarantees
     * non-decreasing positions (4B.4 D6) and every real fixture honours it;
     * sorting here means a future backend that did not could never make this
     * class report a maneuver twice or run the plan backwards.
     */
    val plan: List<WalkManeuver>,

    /** The geometry index, or null when the geometry is degenerate. */
    val index: RouteIndex?,

    /**
     * The route length used for progress, in metres.
     *
     * Taken from the geometry index when there is one, because that is the
     * same measure [alongM][WalkProgress.alongM] is expressed in and mixing
     * the two would make `remainingM` drift. Falls back to the contract's
     * `distance_m`, then to the last maneuver's position, then to zero.
     */
    val totalM: Double,
) {
    /** True when there is no usable geometry to place a walker on. */
    val degenerate: Boolean get() = index == null || totalM <= 0.0

    /**
     * The status of maneuver [m] at [alongM]. Pure; see [progressAt].
     */
    fun statusOf(m: WalkManeuver, alongM: Double): WalkEventStatus = when {
        m.distanceM > alongM + LOOK_BACK_M -> WalkEventStatus.AHEAD
        alongM <= m.distanceM + m.spanM -> WalkEventStatus.ACTIVE
        else -> WalkEventStatus.PASSED
    }

    /**
     * Derive the whole walking state at an along-route distance.
     *
     * **Pure and deterministic**: the same route and the same [alongM] always
     * produce the same [WalkProgress], with no history and no clock. That is
     * what makes the state assertable, and it is why the jitter latch lives
     * in [WalkProgressTracker] instead of here — mixing "what is true at this
     * position" with "what we have already been told" would make neither
     * testable.
     *
     * @param fromPlanIndex the first plan entry that may still be reported as
     *   next. Used only by [WalkProgressTracker] to hold a maneuver passed
     *   across GPS jitter; the default considers the whole plan.
     */
    fun progressAt(alongM: Double, fromPlanIndex: Int = 0): WalkProgress {
        val at = alongM.coerceIn(0.0, totalM.coerceAtLeast(0.0))

        var active: WalkEvent? = null
        var next: WalkEvent? = null
        var passed = 0
        for (i in plan.indices) {
            val m = plan[i]
            val status = statusOf(m, at)
            if (status == WalkEventStatus.PASSED) passed++
            if (i < fromPlanIndex) continue
            val event = WalkEvent(m, i, status, m.distanceM - at)
            when (status) {
                WalkEventStatus.ACTIVE -> {
                    // Several events can be active at one position — the real
                    // stairs fixture opens with a `depart` and a `cross` that
                    // BOTH sit at 0.0 m — so the tie-break is stated rather
                    // than left to iteration order:
                    //
                    //  1. a span beats a point event, because being ON a
                    //     crossing is what the walker is doing and the point
                    //     event at its mouth is not;
                    //  2. otherwise the later one wins, which keeps the answer
                    //     moving forward instead of sticking on the earlier.
                    val cur = active
                    active = when {
                        cur == null -> event
                        cur.kind.isSpan && !event.kind.isSpan -> cur
                        else -> event
                    }
                }
                WalkEventStatus.AHEAD ->
                    if (next == null) next = event
                WalkEventStatus.PASSED -> Unit
            }
        }

        val point = index?.pointAt(at)
        return WalkProgress(
            route = this,
            alongM = at,
            traversedM = at,
            remainingM = (totalM - at).coerceAtLeast(0.0),
            position = point?.position,
            bearingDeg = point?.bearing,
            segmentIndex = point?.segIdx,
            active = active,
            next = next,
            passedCount = passed,
            currentRoad = currentRoadAt(at),
            currentSegmentClass = segmentClassAt(point?.segIdx),
        )
    }

    /**
     * The identity of the way being WALKED ON, or null when unknown.
     *
     * Two refusals are the whole point of this function:
     *
     *  - **`cross` maneuvers are excluded.** Their `road` is the road being
     *    CROSSED, not the one being walked on. Reading it here would put the
     *    name of a road the walker is stepping over into "you are on X",
     *    which is the single most dangerous misreading available in this
     *    contract.
     *  - **Nothing is invented.** If no maneuver at or behind the walker
     *    carries a road, the answer is null. It is not backfilled from the
     *    next maneuver ahead, and it is not synthesised from the segment's
     *    highway class — a class is what a way IS, not which way it is.
     *    [WalkProgress.currentSegmentClass] carries that separately.
     */
    private fun currentRoadAt(alongM: Double): WalkRoadId? {
        for (i in plan.indices.reversed()) {
            val m = plan[i]
            if (m.distanceM > alongM + LOOK_BACK_M) continue
            if (m.kind == WalkManeuverKind.CROSS) continue
            val road = m.road ?: continue
            return road
        }
        return null
    }

    /**
     * The `classes` entry for the segment under the walker, or null.
     *
     * The segment's own `highway` value, straight from the geometry-aligned
     * array. A FACT about the segment, not a road identity — see
     * [currentRoadAt].
     */
    private fun segmentClassAt(segIdx: Int?): String? {
        val i = segIdx ?: return null
        val classes = contract.segments.classes
        return if (i in classes.indices) classes[i] else null
    }

    /**
     * What the sun model says about this walk, from [startMs].
     *
     * ## Why this lives on the route and takes the instant as a parameter
     *
     * The same shape as [progressAt], and for the same reason: it is a PURE
     * function of the route and one number, so it is assertable, and the
     * number is the caller's rather than a clock read inside a routing
     * calculation. `WalkNavSession` supplies the instant from the fix that
     * starts the walk, exactly as it supplies `nowMs` to every other walking
     * decision, and no part of this path ever calls `System.currentTimeMillis`.
     *
     * ## Why it is computed once and not per fix
     *
     * The fact is about the walk's own planned timeline: which parts of it
     * fall in the sun if the walker sets off at [startMs]. Re-deriving it on
     * every fix would restate it against a moving sun and make the strip's
     * sentence change while the walker stands still — the defect the crossing
     * detail line's fixed ordering exists to prevent, one surface over. A
     * walker who is twenty minutes late is walking a route whose modelled
     * timeline no longer describes them, and no per-fix recomputation fixes
     * that either, because Vector does not know their pace.
     *
     * Never throws and never returns null: a degenerate walk, a contract with
     * no geometry and a contract from a backend with no per-segment classes
     * all produce a [WalkShade] whose [WalkShade.stripLine] reports honestly
     * what is not known.
     */
    fun shadeAt(startMs: Long): WalkShade = WalkShadeFacts.of(contract, startMs)

    companion object {
        /**
         * How far behind the walker a maneuver may sit and still count as
         * "at" rather than "ahead".
         *
         * One metre, the same margin the car path's `upcomingManeuver` uses,
         * and safe on pedestrian geometry for a measured reason: the smallest
         * gap between two distinct maneuvers across the six real fixtures is
         * 2.3 m at the 1st percentile (the only 0.0 m gap is a depart and a
         * crossing that genuinely share a position). A one-metre margin
         * therefore cannot skip a real maneuver.
         */
        const val LOOK_BACK_M = 1.0

        /**
         * Build a route from a parsed contract.
         *
         * Never throws and never refuses: a zero-length walk, an empty plan
         * and an absent geometry are all real backend answers (4B.4
         * serialises a degenerate walk as empty arrays), and a walking UI has
         * to be able to hold one without crashing. The resulting route
         * reports [degenerate] and every progress query returns a coherent
         * all-zero state.
         */
        fun of(contract: WalkContract): WalkRoute {
            val plan = contract.plan.sortedBy { it.distanceM }
            val index = RouteGeometry.index(contract.geometry)
            val totalM = index?.totalM
                ?: contract.distanceM
                ?: plan.lastOrNull()?.distanceM
                ?: 0.0
            return WalkRoute(contract, plan, index, totalM.coerceAtLeast(0.0))
        }
    }
}

/** Whether an event is ahead of the walker, under way, or behind them. */
enum class WalkEventStatus {
    /** Not reached. */
    AHEAD,

    /**
     * Under way.
     *
     * For a point maneuver this is the moment of the maneuver itself. For a
     * span ([WalkManeuverKind.isSpan]) it lasts the whole crossing or
     * staircase — the walker is ON it.
     */
    ACTIVE,

    /** Behind the walker. */
    PASSED,
}

/**
 * One maneuver, placed relative to the walker.
 *
 * The UI-facing event type. It exposes the FACTS — kind, distance, the
 * crossing/stairs/turn attributes, the source fact types, and whether each
 * uncertain thing is actually known — and deliberately exposes no wording.
 * "Turn left onto X" and "Cross Y" are 4C.2+ decisions; what this type
 * guarantees is that the decision can be made without inventing anything.
 */
data class WalkEvent(
    val maneuver: WalkManeuver,
    /** Position in [WalkRoute.plan]. */
    val planIndex: Int,
    val status: WalkEventStatus,
    /**
     * Metres from the walker to the event's START. Negative once the walker
     * is inside a span or past a point event.
     */
    val distanceM: Double,
) {
    val kind: WalkManeuverKind get() = maneuver.kind

    /** Metres remaining of a span the walker is inside; 0 for a point event. */
    val remainingSpanM: Double
        get() = (maneuver.spanM + distanceM).coerceIn(0.0, maneuver.spanM)

    /**
     * True when the road relevant to this event is actually established.
     *
     * For a crossing this is [WalkCrossing.roadKnown] — the crossed road AND
     * its attribution. For everything else it is a named way. False is a
     * legitimate, common answer and must be rendered as one.
     */
    val roadKnown: Boolean
        get() = when (kind) {
            WalkManeuverKind.CROSS -> maneuver.crossing?.roadKnown == true
            else -> maneuver.road?.named == true
        }
}

/**
 * The walking navigation state at one instant.
 *
 * Everything a 4C.2 camera, puck, banner or voice will need to ask, and
 * nothing it will need to be told. Produced only by [WalkRoute.progressAt]
 * and [WalkProgressTracker.update].
 */
data class WalkProgress(
    val route: WalkRoute,

    /** The mode this state belongs to. Always [NavMode.FOOT]. */
    val mode: NavMode = NavMode.FOOT,

    /** Along-route position, clamped to the route. */
    val alongM: Double,

    /** Route distance already walked. Same number as [alongM], named for use. */
    val traversedM: Double,

    /** Route distance left. */
    val remainingM: Double,

    /** Where that is on the geometry, or null on a degenerate route. */
    val position: LngLat?,

    /** The bearing of the geometry segment under the walker, or null. */
    val bearingDeg: Double?,

    /** Which geometry segment the walker is on; indexes the segment arrays. */
    val segmentIndex: Int?,

    /**
     * The event under way, or null.
     *
     * Usually a span the walker is INSIDE — on a crossing, on a staircase.
     * A point maneuver is briefly active too, within [WalkRoute.LOOK_BACK_M]
     * of its position, which is the instant a UI would call "you are at the
     * turn". Where both apply at one position the span wins; see
     * [WalkRoute.progressAt].
     */
    val active: WalkEvent?,

    /** The next event ahead, or null once the plan is exhausted. */
    val next: WalkEvent?,

    /** How many plan entries are behind the walker. */
    val passedCount: Int,

    /**
     * The way being walked on, when the plan actually establishes one.
     *
     * Null is honest and common. Never the crossed road of a crossing, never
     * backfilled from ahead, never synthesised from a highway class.
     */
    val currentRoad: WalkRoadId?,

    /**
     * The `classes` value of the segment under the walker, or null.
     *
     * A fact about the segment (`footway`, `residential`, …). NOT a road
     * identity — see [currentRoad].
     */
    val currentSegmentClass: String?,
) {
    /** Metres to [next], or null when there is none. */
    val distanceToNextM: Double? get() = next?.distanceM?.coerceAtLeast(0.0)

    /** Fraction of the route walked, 0..1. Zero on a degenerate route. */
    val fraction: Double
        get() = if (route.totalM <= 0.0) 0.0 else (alongM / route.totalM).coerceIn(0.0, 1.0)

    /** True while the walker is on a crossing. */
    val onCrossing: Boolean get() = active?.kind == WalkManeuverKind.CROSS

    /** True while the walker is on stairs. */
    val onStairs: Boolean get() = active?.kind == WalkManeuverKind.STAIRS
}

/**
 * Holds walking progress across GPS fixes, so jitter cannot un-pass a
 * maneuver (V7.4 4C.1).
 *
 * ## Why this is separate from [WalkRoute.progressAt]
 *
 * `progressAt` is a pure function of position: the same input always gives
 * the same answer, which is what makes the state assertable. But position is
 * not monotonic in the real world — a walker standing at a crossing produces
 * fixes that wander a few metres either side of the maneuver — and a pure
 * function evaluated on a wandering input will report the crossing as ahead,
 * then passed, then ahead again.
 *
 * So the latch lives here, in the smallest possible amount of state: the
 * highest plan entry reached. `next` can never move backwards through the
 * plan while the latch holds, and everything else — position, distances,
 * remaining — always reflects the CURRENT fix, because those are
 * measurements and must not be latched.
 *
 * ## The one case that releases the latch
 *
 * Genuine backtracking. A walker who turns around is not jittering, and a
 * plan frozen ahead of them would be useless. [backtrackToleranceM] is the
 * line between the two, and it is a deliberate trade: below it, a real short
 * backtrack is not recognised; above it, jitter would leak through. Ten
 * metres sits under the ~15 m of urban-canyon drift this codebase has already
 * measured (see `RouteIndex.project`) and well above the residual left by
 * `RouteTracker`'s own smoothing, and a walker who has genuinely turned round
 * clears it within a few seconds at 1.35 m/s.
 */
class WalkProgressTracker(
    private val route: WalkRoute,
    private val backtrackToleranceM: Double = BACKTRACK_TOLERANCE_M,
) {
    private var reachedPlanIndex = 0
    private var highWaterM = 0.0

    /** Forget the latch. Call when the route is replaced. */
    fun reset() {
        reachedPlanIndex = 0
        highWaterM = 0.0
    }

    /** The plan entry the latch is currently holding at. For tests/telemetry. */
    val latchedPlanIndex: Int get() = reachedPlanIndex

    /**
     * Feed an along-route distance; get the state.
     *
     * [alongM] is expected to come from the existing route tracker (see
     * [WalkPositionSource]) — this class does no map matching of its own.
     */
    fun update(alongM: Double): WalkProgress {
        val at = alongM.coerceIn(0.0, route.totalM.coerceAtLeast(0.0))
        if (at < highWaterM - backtrackToleranceM) {
            // A real backtrack, not noise. Release the latch and re-derive
            // from scratch, so a walker who turned around is guided again.
            reachedPlanIndex = 0
            highWaterM = at
        } else if (at > highWaterM) {
            highWaterM = at
        }
        val progress = route.progressAt(at, fromPlanIndex = reachedPlanIndex)
        // Advance the latch to the EARLIEST event still in play — the active
        // one if there is one, otherwise the next.
        //
        // Latching to `next` alone is the bug this comment exists for: a
        // walker standing on a 156 m crossing has an ACTIVE event at plan
        // entry i and a next at i+1, so latching to i+1 would drop the
        // crossing they are currently on out of the state entirely, one fix
        // after they stepped onto it.
        reachedPlanIndex = listOfNotNull(
            progress.active?.planIndex,
            progress.next?.planIndex,
        ).minOrNull() ?: route.plan.size
        return progress
    }

    companion object {
        /** See the class KDoc for why ten metres. */
        const val BACKTRACK_TOLERANCE_M = 10.0
    }
}

/**
 * Where the walker is on the route, as the one thing walking UX needs
 * (V7.4 4C.1).
 *
 * ## The boundary this interface draws
 *
 * 4C.1 builds **no new map-matching engine**. Vector already has one —
 * `RouteTracker` over `RouteIndex.project` — and its geometry is mode-neutral:
 * perpendicular point-to-segment projection with a cumulative index, which is
 * as correct on a footway as on a motorway. Reimplementing it for walking
 * would be two copies of the hardest maths in the app.
 *
 * What is NOT mode-neutral is the set of constants `RouteTracker` is
 * configured with, and they are car-tuned by construction. Three are
 * documented here because 4C.2 owns the decision and must not inherit them by
 * accident:
 *
 *  1. **`offRouteM = 60` / `snapMaxM = 40`.** Sized for a vehicle on a
 *     carriageway. A person 60 m from their footpath is not slightly off it,
 *     they are somewhere else — but narrowing it changes when a reroute
 *     fires, and walking rerouting is explicitly outside 4C.1. Unchanged
 *     here, flagged for 4C.2.
 *  2. **`searchFwdM = 250`.** Chosen to cover 33 m/s for the 3 s the tracker
 *     dead-reckons. At 1.35 m/s a walker covers 4 m in the same window, so
 *     250 m is not dangerous, merely meaningless — it is 3 minutes of walking
 *     and should be re-derived from walking pace.
 *  3. **The heading gate is above walking pace, and this one bites.**
 *     `NavSession` withholds GPS heading from the tracker below
 *     `MOVING_MS = 1.5 m/s`; the backend's own walking pace is
 *     `walk_speed_ms = 1.35`. A walker is therefore below the "is it moving"
 *     floor essentially always, so the U-turn disambiguation that heading
 *     exists for never engages on foot. That is safe today — it degrades to
 *     nearest-segment, the pre-heading behaviour — but it means the car
 *     path's answer to doubled-back geometry is simply absent for walking,
 *     and walking routes double back (one real fixture plans a `uturn`
 *     2.3 m before arrival).
 *
 * So the adapter is an interface, not an implementation: 4C.2 can satisfy it
 * with the existing tracker, with the existing tracker reconfigured, or with
 * a walking-specific matcher, and nothing in the state model above changes.
 */
interface WalkPositionSource {
    /** Along-route metres, or null when the walker is not matched. */
    fun alongM(): Double?
}
