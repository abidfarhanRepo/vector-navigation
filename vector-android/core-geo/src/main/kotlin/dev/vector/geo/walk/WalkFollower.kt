package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.RouteFix
import dev.vector.geo.RouteGeometry
import kotlin.math.abs

/**
 * Where the walker is on the route, and whether they are still on it
 * (V7.4 4C.2).
 *
 * ## What this is, and what it deliberately is not
 *
 * It is the walking implementation of [WalkPositionSource], built on the SAME
 * mode-neutral projection the car path uses — `RouteIndex.project`, which is
 * perpendicular point-to-segment distance over a cumulative index. 4C.1
 * established that the geometry is correct on a footway and that only the
 * CONSTANTS are car-shaped; this class supplies walking constants and nothing
 * else. There is no second map-matching engine here, and there is no
 * reimplementation of any maths.
 *
 * It does **not** reroute. 4C.2 detects and reports [WalkFollowState];
 * deciding what to do about [WalkFollowState.OFF_ROUTE] is 4C.4's.
 *
 * ## The measurement that shapes every threshold below
 *
 * Measured on the real 260912 bake, over the six captured `/foot` payloads
 * (`V7.4-EVIDENCE/walking_geometry_evidence.py`):
 *
 * ```
 * nearest FOREIGN pedestrian edge to the route, sampled along each route:
 *   ordinary   p50  3.73 m   within 15 m at 21 of 33 sampled points
 *   crossing   p50  9.36 m   within 15 m at 62 of 97
 *   stairs     p50  9.71 m   within 15 m at 77 of 128
 *   long       p50 42.90 m   within 15 m at 41 of 148
 * ```
 *
 * **A different, genuinely walkable path is within 15 m of the route at
 * roughly half of all sampled points**, and `GPS_WEAK_ACCURACY_M` — the app's
 * own line for "this fix cannot tell which of two parallel Doha carriageways
 * you are on" — is 30 m.
 *
 * So the discrimination this class is being asked for **does not exist in the
 * data**. No threshold can separate "walking on the route" from "walking on
 * the pavement on the other side of the street", because the two are closer
 * together than one GPS fix is wide. That is not a gap to be closed by a
 * cleverer constant; it is a property of consumer GNSS in a dense footway
 * network.
 *
 * Everything below follows from accepting it:
 *
 *  * thresholds are sized against **GPS plausibility**, not against path
 *    separation, because only the first is measurable;
 *  * [WalkFollowState.UNCERTAIN] exists, because "I cannot tell" is the
 *    honest answer over a wide band and collapsing it into either neighbour
 *    would be a claim;
 *  * a poor fix **widens** the threshold rather than tripping it, so bad GPS
 *    can never manufacture a departure;
 *  * off-route needs persistence AND movement, so one reflected fix cannot
 *    produce it.
 */
class WalkFollower(
    private val route: WalkRoute,

    /**
     * Offset within which the walker is considered on the route.
     *
     * 25 m. Derived from the two measurements that bracket it:
     *
     *  * **Below**, the ordinary urban-canyon fix error this codebase has
     *    already measured at ~15 m (see `RouteIndex.project`'s KDoc, where a
     *    15 m excursion put a vehicle on the wrong carriageway). A walker
     *    standing still on the route routinely reads this far off it, and a
     *    radius under it would report a departure that never happened.
     *  * **Above**, `GPS_WEAK_ACCURACY_M` = 30 m, the app's own line for a fix
     *    that can no longer distinguish parallel ways. A radius at or above it
     *    would be asserting on-route-ness out of fixes that carry no such
     *    information.
     *
     * 25 m sits inside that bracket. It is deliberately NOT justified by
     * footway separation — see the class KDoc for why no value could be.
     */
    private val onRouteM: Double = ON_ROUTE_M,

    /**
     * Offset beyond which a departure becomes plausible, subject to
     * [offRoutePersistMs] and movement.
     *
     * 45 m. The car's 60 m is a carriageway number: it assumes the vehicle is
     * on a road that is metres wide, with the next road a block away. A
     * pedestrian's alternatives are metres away, so a wider band buys nothing
     * and only delays the report.
     *
     * It is set ABOVE `GPS_WEAK_ACCURACY_M` (30 m) on purpose: inside that
     * radius the fix itself is admitting it cannot tell, so declaring a
     * departure there would be a claim built on a measurement that disclaims
     * it. 45 m is 1.5x the weak-accuracy line — far enough out that an
     * ordinary poor fix does not reach it, close enough that a walker who has
     * genuinely turned down the wrong street clears it within a few paces.
     */
    private val offRouteM: Double = OFF_ROUTE_M,

    /**
     * How long the offset must stay past [offRouteM] before it is reported.
     *
     * 6 s. At the backend's own 1.35 m/s walking pace that is ~8 m of travel,
     * so a walker who has genuinely left the route keeps leaving it and a
     * single reflected fix — the failure this exists to stop — cannot survive
     * it. Deliberately longer than the car path's reroute cooldown logic
     * needs, because nothing downstream of 4C.2 acts on this state yet: the
     * cost of being slow is a late label, and the cost of being fast is a
     * wrong one.
     */
    private val offRoutePersistMs: Long = OFF_ROUTE_PERSIST_MS,

    /**
     * How far BACK along the route one fix may move the locked projection.
     *
     * 20 m, against the car's 60. The bound is measured: the worst
     * self-approach of any real walking fixture, between points at least 50 m
     * apart along the route, is **16.1 m** (the stairs walk). A backward
     * window of 20 m therefore cannot reach a doubled-back section that is
     * 50 m or more away along the route, which is the jump this window exists
     * to prevent — the walking equivalent of the 152 m backwards jump
     * documented on `RouteIndex.project`.
     *
     * It still covers ordinary GPS error (~15 m) and a walker taking a step
     * back, which is all a pedestrian can do between two 1 Hz fixes.
     */
    private val searchBackM: Double = SEARCH_BACK_M,

    /**
     * How far FORWARD one fix may move the locked projection.
     *
     * 40 m, against the car's 250. The car's value is derived in its own KDoc
     * as "33 m/s for the 3 s the tracker will dead-reckon", i.e. 100 m of
     * vehicle travel with margin. The same derivation at walking pace is
     * **1.35 m/s x 3 s = 4.05 m**, so 250 m is not dangerous on foot, merely
     * meaningless — it is three minutes of walking, and it re-admits exactly
     * the doubled-back geometry the backward window is tightened to exclude.
     *
     * 40 m is ~30 s of walking. That covers a long run of dropped fixes
     * (the tracker gives up after 3 s regardless) with a wide margin, and it
     * stays under the 50 m separation at which the measured self-approach
     * (16.1 m) becomes reachable.
     *
     * Asymmetric for the same reason the car's is: forward has to cover
     * travel, backward only has to cover error, and the failure being
     * prevented is a backward one.
     */
    private val searchFwdM: Double = SEARCH_FWD_M,

    /**
     * Along-route progress per second that counts as walking.
     *
     * **This is the walking answer to the heading-gate problem 4C.1 handed
     * over, and it is deliberately not a GPS speed.**
     *
     * `NavSession` gates on `MOVING_MS = 1.5 m/s`, and the backend's own
     * walking pace is `walk_speed_ms = 1.35` — so a walker is below the car
     * path's "is it moving" floor essentially always. Lowering that constant
     * globally would change when a CAR reroutes, which is out of scope and
     * unsafe; lowering it only for foot would still be reading a signal that
     * is mostly noise, because at 1.35 m/s an instantaneous GPS speed is the
     * same size as its own error.
     *
     * So walking movement is measured as **progress along the route between
     * fixes**: differenced over the real interval between fixes, and already
     * map-matched, so it is far steadier than an instantaneous speed.
     *
     * 0.4 m/s is under a third of walking pace, so a dawdle still clears it.
     *
     * **Its known limit, stated rather than hidden:** at a 1 Hz fix rate,
     * along-route jitter of more than 0.4 m between two fixes is arithmetically
     * indistinguishable from a slow walk. A stationary receiver produces far
     * less along-route drift than lateral (lateral error projects onto the
     * route as only a fraction of itself), but the two are not perfectly
     * separable. That is one reason this is reported as EVIDENCE on
     * [WalkFix.movingMs] rather than used to decide [WalkFollowState] — a
     * quantity that cannot cleanly separate two cases must not be the thing
     * that chooses between them.
     */
    private val movingMs: Double = WALK_MOVING_MS,

    /**
     * How much faster than walking pace the held position may move BACKWARDS
     * in one fix.
     *
     * ## The defect this exists because of, measured on real Qatar geometry
     *
     * A walking route bends back toward itself at far shorter along-route
     * separations than a road does. Measured over the real fixtures, with a
     * 12 m lateral offset — ordinary urban-canyon error — the perpendicular
     * projection lands BEHIND the true position by up to:
     *
     * ```
     * foot-ordinary-4b4   19.8 m
     * foot-stairs-4b4     33.1 m
     * ```
     *
     * because at a corner the nearest point on the polyline is genuinely on
     * the leg already walked. [searchBackM] bounds how far ONE fix may reach
     * back, but inside that bound a hard snap hands the whole slip to the puck
     * and to the maneuver progression, and the walker visibly walks backwards
     * round every corner.
     *
     * ## Why a rate limit and not the car's gain
     *
     * `RouteTracker` damps the same problem with an exponential gain
     * (`alongM += (proj - alongM) * 0.25`). Copying it here was tried and is
     * wrong on foot, for a reason that is arithmetic rather than taste: a
     * symmetric gain lags a steadily-moving target permanently, by
     * `v * dt * (1-g)/g` — at 1.35 m/s and 1 Hz, **4.05 m that is never
     * recovered**. The car does not pay that because it dead-reckons forward
     * between fixes (`RouteTracker.onFrame`), which cancels the lag; this
     * follower has no such step, because at walking pace there is nothing
     * worth extrapolating between fixes.
     *
     * So the limit is asymmetric, which is also what the physics says:
     *
     *  * **forwards** the measurement is taken outright — no lag, ever;
     *  * **backwards** it may move at most `pace * dt * this`, because that is
     *    the furthest a person could actually have gone back.
     *
     * A 19.8 m corner slip is therefore clipped to ~2 m and recovered on the
     * next fix, while a walker who genuinely turns round is followed at full
     * speed. 1.5 is slack over the backend's own `walk_speed_ms` of 1.35, so
     * a brisk walker reversing is not held back.
     */
    private val backwardSlack: Double = BACKWARD_SLACK,
) {
    private var locked = false
    private var alongM = 0.0
    private var lastFixMs: Long? = null
    private var lastAlongM: Double? = null

    /**
     * Recent RAW fixes, for the ground-movement measurement.
     *
     * See [WalkFix.groundMs]. A ring rather than the last two fixes, for the
     * reason `NavSession.effectiveSpeed` gives: two consecutive fixes 3 m
     * apart could be 3 m of travel or 3 m of jitter, and at a standstill in an
     * urban canyon it is always the latter. Net displacement over a window
     * averages independent jitter down while real travel accumulates.
     */
    private val recentFixes = ArrayDeque<Pair<Long, LngLat>>()
    private var outsideSinceMs: Long? = null
    private var state: WalkFollowState = WalkFollowState.ACQUIRING
    private var lastBearingDeg: Double? = null
    private val progress = WalkProgressTracker(route)

    /** The last derived state. [WalkFollowState.ACQUIRING] before any fix. */
    val followState: WalkFollowState get() = state

    /** True once a projection has been accepted. */
    val isLocked: Boolean get() = locked

    /** Reset for a new route. */
    fun reset() {
        locked = false
        alongM = 0.0
        lastFixMs = null
        lastAlongM = null
        recentFixes.clear()
        outsideSinceMs = null
        state = WalkFollowState.ACQUIRING
        lastBearingDeg = null
        progress.reset()
    }

    /**
     * Along-route metres, or null when not matched. Satisfies
     * [WalkPositionSource] so the camera layer never sees this class.
     */
    fun alongM(): Double? = if (locked) alongM else null

    /**
     * Feed one GPS fix.
     *
     * @param accuracyM the receiver's own claimed accuracy, when it reports
     *   one. It only ever WIDENS the thresholds — see [effectiveOffRouteM].
     */
    fun onFix(fix: LngLat, nowMs: Long, accuracyM: Double? = null): WalkFix {
        // Recorded FIRST, so the ground measurement is continuous across the
        // early returns below. A walker on a degenerate route is still moving
        // or not moving, and that is the one question those branches do not
        // need the route to answer.
        val ground = groundSpeed(fix, nowMs)
        val index = route.index
        if (index == null) {
            state = WalkFollowState.ACQUIRING
            return WalkFix(
                state, null, null, null, null, false,
                groundMs = ground,
                movingGround = (ground ?: 0.0) >= GROUND_MOVING_MS,
            )
        }

        // Constrained to a window around where we already are, but ONLY while
        // locked: an unlocked follower has no trustworthy along-position to
        // constrain against. Heading is deliberately NOT passed — see
        // [movingMs] and the class KDoc: a walker's GPS heading is noise, and
        // handing it to the disambiguator would make the answer worse than
        // the nearest-segment rule it replaces.
        val proj: RouteFix = (
            if (locked) index.project(fix, alongM, searchBackM, searchFwdM)
            else index.project(fix)
            ) ?: run {
            state = WalkFollowState.ACQUIRING
            return WalkFix(
                state, null, null, null, null, false,
                groundMs = ground,
                movingGround = (ground ?: 0.0) >= GROUND_MOVING_MS,
            )
        }

        val onR = effectiveOnRouteM(accuracyM)
        val offR = effectiveOffRouteM(accuracyM)

        // Movement EVIDENCE, from along-route progress rather than GPS speed.
        // Reported, never used to decide the state — see the state `when`
        // below for why, and [movingMs] for why it is not a GPS speed.
        val dtS = lastFixMs?.let { (nowMs - it) / 1000.0 }?.takeIf { it > 0.0 }
        val prevAlong = lastAlongM
        val movedMs = if (dtS != null && prevAlong != null) {
            abs(proj.alongM - prevAlong) / dtS
        } else null

        // The persistence clock, updated in ONE place.
        //
        // It was originally updated in three branches alongside the lock, and
        // that arrangement had a real defect: the never-locked branch returned
        // early and declared OFF_ROUTE from a SINGLE fix, with no persistence
        // and no movement evidence — exactly the thing the rest of the class
        // is built to refuse. Deciding the state once, below, makes that
        // impossible to reintroduce.
        if (proj.offsetM >= offR) {
            if (outsideSinceMs == null) outsideSinceMs = nowMs
        } else {
            outsideSinceMs = null
        }

        // Accept the projection into the lock only inside the on-route radius.
        // In the uncertain band the lock is HELD but not corrected, so a
        // wandering fix cannot drag the walker up the path — the same
        // structure the car tracker uses, with walking radii.
        if (proj.offsetM <= onR) {
            if (!locked) {
                // First lock: take the measurement outright. Easing in from an
                // arbitrary starting value would slide the walker up the path.
                alongM = proj.alongM
                locked = true
            } else if (proj.alongM >= alongM) {
                // Forwards: take it. No lag on the common case.
                alongM = proj.alongM
            } else {
                // Backwards: only as far as a person could have walked back.
                // See [backwardSlack] for the 19.8-33.1 m of corner slip this
                // clips on real Qatar geometry.
                val maxBack = WALK_PACE_MS * (dtS ?: 1.0) * backwardSlack
                alongM = maxOf(proj.alongM, alongM - maxBack)
            }
        }

        lastFixMs = nowMs
        lastAlongM = proj.alongM

        val since = outsideSinceMs
        state = when {
            proj.offsetM <= onR -> WalkFollowState.ON_ROUTE
            // Distance AND persistence — deliberately NOT movement.
            //
            // ## The design error this comment exists because of
            //
            // The first version of this class also required [moving] here,
            // and it was wrong in a way that only showed up under test: a
            // walker who steps PERPENDICULARLY away from the route makes no
            // along-route progress at all, so their movement rate is zero and
            // they could never be reported off-route. That is precisely the
            // person who IS off-route.
            //
            // The car architecture already has this right and it is worth
            // stating where the two layers sit. `RouteTracker` declares
            // `OffRoute` on pure distance; the movement test lives one layer
            // up in `NavSession.rerouteCouldHelp`, where it gates the
            // ACTION — "would asking for a new route change anything?" — not
            // the state. A stationary deviation is still a deviation; it is
            // just not worth a reroute request.
            //
            // So this class reports what is true, [movingMs] rides along as
            // the evidence, and 4C.4 gates its rerouting on it exactly as the
            // car path does. The walking-specific addition is the persistence
            // window, which is what actually defends against the single
            // reflected fix.
            since != null && nowMs - since >= offRoutePersistMs ->
                WalkFollowState.OFF_ROUTE
            else -> WalkFollowState.UNCERTAIN
        }

        if (state == WalkFollowState.OFF_ROUTE) {
            // DROP THE LOCK on a confirmed departure, exactly as the car
            // tracker does.
            //
            // Not housekeeping — it is what makes rejoining work. While
            // locked, the projection is constrained to [-20 m, +40 m] around
            // the last known position; a walker who leaves the route, walks
            // 200 m, and rejoins further along would never be found inside
            // that window, and would stay off-route for the rest of the walk.
            // Unlocked, the next fix searches the whole route and re-locks
            // wherever they actually are.
            locked = false
        }

        if (!locked) {
            // Nothing matched: report the state and the offset, but no
            // position. Inventing one would be placing a walker on a route
            // they are not demonstrably on.
            return WalkFix(
                state = state,
                progress = null,
                position = null,
                offsetM = proj.offsetM,
                movingMs = movedMs,
                walking = (movedMs ?: 0.0) >= movingMs,
                groundMs = ground,
                movingGround = (ground ?: 0.0) >= GROUND_MOVING_MS,
            )
        }

        val p = progress.update(alongM)
        // Bearing comes from the ROUTE, never from the receiver. Held through
        // a standstill rather than recomputed, because the route bearing at a
        // position the walker has not left has not changed either.
        p.bearingDeg?.let { lastBearingDeg = it }
        return WalkFix(
            state = state,
            progress = p,
            position = p.position,
            offsetM = proj.offsetM,
            movingMs = movedMs,
            walking = (movedMs ?: 0.0) >= movingMs,
            groundMs = ground,
            movingGround = (ground ?: 0.0) >= GROUND_MOVING_MS,
        )
    }

    /**
     * Ground speed over [GROUND_WINDOW_MS], or null while the window is short.
     *
     * ## The defect this exists because of
     *
     * [WalkFix.walking] is ALONG-ROUTE progress, and that is the right signal
     * for what it is used for — but it is exactly zero for a walker who steps
     * **perpendicularly** off the route, because their projection onto the
     * route does not move. Gating a reroute on it therefore made rerouting
     * unreachable for the single most common way of leaving a walking route:
     * built, unit-testable in isolation, and dead in practice.
     *
     * This is the honest measurement for "is this person physically going
     * somewhere", which is the question the reroute ACTION layer asks — the
     * walking counterpart of the `speedMs >= MOVING_MS` test in
     * `NavSession.rerouteCouldHelp`, and deliberately a different quantity
     * from [WalkFix.walking] rather than a redefinition of it.
     *
     * Net displacement over a window, not between consecutive fixes, for the
     * reason `NavSession.effectiveSpeed` documents: at 1 Hz a stationary
     * receiver's jitter is the same size as a walking pace, and only the
     * window separates them.
     */
    private fun groundSpeed(fix: LngLat, nowMs: Long): Double? {
        recentFixes.addLast(nowMs to fix)
        while (recentFixes.size > 1 && nowMs - recentFixes.first().first > GROUND_WINDOW_MS) {
            recentFixes.removeFirst()
        }
        val oldest = recentFixes.first()
        val dt = (nowMs - oldest.first) / 1000.0
        if (dt < MIN_GROUND_WINDOW_S) return null
        val d = RouteGeometry.haversineM(
            oldest.second.lng, oldest.second.lat, fix.lng, fix.lat,
        )
        return d / dt
    }

    /**
     * The on-route radius for a fix of this accuracy.
     *
     * A poor fix widens the radius. It can never narrow it: a receiver
     * claiming 5 m does not license a tighter claim than the 25 m the geometry
     * evidence supports, because the error that matters here is the map's and
     * the walker's as well as the receiver's.
     */
    private fun effectiveOnRouteM(accuracyM: Double?): Double =
        maxOf(onRouteM, (accuracyM ?: 0.0).coerceAtMost(ACCURACY_CAP_M))

    /**
     * The off-route radius for a fix of this accuracy.
     *
     * Widened by poor accuracy, and capped: past [ACCURACY_CAP_M] a receiver
     * is no longer describing a position at all, and letting it widen without
     * bound would mean a phone reporting 500 m could never be off route.
     */
    private fun effectiveOffRouteM(accuracyM: Double?): Double =
        maxOf(offRouteM, (accuracyM ?: 0.0).coerceAtMost(ACCURACY_CAP_M) * ACCURACY_SLACK)

    companion object {
        /** See the constructor parameter. */
        const val ON_ROUTE_M = 25.0

        /** See the constructor parameter. */
        const val OFF_ROUTE_M = 45.0

        /** See the constructor parameter. */
        const val OFF_ROUTE_PERSIST_MS = 6_000L

        /** See the constructor parameter. */
        const val SEARCH_BACK_M = 20.0

        /** See the constructor parameter. */
        const val SEARCH_FWD_M = 40.0

        /** See the constructor parameter. */
        const val WALK_MOVING_MS = 0.4

        /** See the constructor parameter. */
        const val BACKWARD_SLACK = 1.5

        /**
         * Window the ground speed is measured over. See [groundSpeed].
         *
         * Five seconds, the same as the car's `SPEED_WINDOW_MS` and for the
         * same arithmetic: five seconds of 3 m independent jitter is under a
         * metre per second of apparent travel, which is below
         * [GROUND_MOVING_MS] and therefore cannot manufacture a reroute.
         */
        const val GROUND_WINDOW_MS = 5_000L

        /**
         * Shortest window a ground speed is trusted over.
         *
         * Below this the denominator is small enough that one noisy fix
         * dominates, so no speed is reported at all — the honest answer, and
         * the one the nullable [WalkFix.groundMs] exists to express.
         */
        const val MIN_GROUND_WINDOW_S = 2.0

        /**
         * Ground speed that counts as physically moving.
         *
         * 0.4 m/s, under a third of walking pace so a dawdle clears it, and
         * comfortably above what jitter survives the window above.
         */
        const val GROUND_MOVING_MS = 0.4

        /**
         * Walking pace, in m/s.
         *
         * The backend's own `walk_speed_ms`, which every `/foot` payload
         * reports and every duration is derived from. Restated here as the
         * rate the backward limit is measured against; a route that reports a
         * different pace does not change what a person can physically do in a
         * second.
         */
        const val WALK_PACE_MS = 1.35

        /**
         * Ceiling on how far a claimed accuracy may widen a threshold.
         *
         * 60 m. Past this the receiver is describing a neighbourhood rather
         * than a position; without the cap a phone reporting 500 m of accuracy
         * could never be off route, which turns a safety valve into a mute
         * button.
         */
        const val ACCURACY_CAP_M = 60.0

        /**
         * Multiplier applied to a capped accuracy for the off-route radius.
         *
         * 1.5, the same ratio [OFF_ROUTE_M] bears to `GPS_WEAK_ACCURACY_M`
         * (45/30), so a widened threshold keeps the shape of the fixed one.
         */
        const val ACCURACY_SLACK = 1.5
    }
}

/**
 * Whether the walker is on the route, and how confidently (V7.4 4C.2).
 *
 * Three states rather than two, because the data supports three. See
 * [WalkFollower]'s KDoc: a foreign pedestrian path is within 15 m of the route
 * at about half of all sampled points and a GPS fix is up to 30 m wide, so
 * there is a broad band in which the honest answer is that Vector does not
 * know. [UNCERTAIN] is that band. Collapsing it into [ON_ROUTE] would claim
 * a walker is following a route they may have left; collapsing it into
 * [OFF_ROUTE] would accuse them of leaving one they are on.
 */
enum class WalkFollowState {
    /** No fix has been matched to the route yet. */
    ACQUIRING,

    /** Matched within the on-route radius. The position is authoritative. */
    ON_ROUTE,

    /**
     * Matched, but outside the on-route radius.
     *
     * The last good along-position is HELD and still reported — the walker is
     * probably still on the route with a poor fix — but it is not corrected
     * toward the measurement, and nothing downstream should treat the
     * position as freshly confirmed.
     */
    UNCERTAIN,

    /**
     * Sustained departure: past the off-route radius, for longer than the
     * persistence window, with movement evidence.
     *
     * **Movement is required**, which has one documented consequence: a
     * walker standing still far from the route is reported [UNCERTAIN]
     * indefinitely rather than [OFF_ROUTE]. That is the honest reading —
     * a stationary receiver reading 100 m out is equally consistent with a
     * bad fix and with a person who has stopped somewhere else — and it is
     * the same rule the car path applies before rerouting (`rerouteCouldHelp`
     * refuses below `MOVING_MS`). It matters only once something acts on the
     * state, which is 4C.4's.
     *
     * 4C.2 reports this and stops there. Rerouting is 4C.4's.
     */
    OFF_ROUTE,
}

/** One matched walking fix. */
data class WalkFix(
    val state: WalkFollowState,
    /** The derived walking state, or null when nothing is matched. */
    val progress: WalkProgress?,
    /** Where to draw the walker, or null when nothing is matched. */
    val position: LngLat?,
    /** Perpendicular distance from the route, when a projection was made. */
    val offsetM: Double?,
    /** Along-route progress rate, or null on the first fix. See [WalkFollower]. */
    val movingMs: Double?,
    /**
     * Whether [movingMs] cleared the walking floor.
     *
     * **Evidence, not a verdict.** Nothing in 4C.2 decides anything with it:
     * [WalkFollowState] is distance-and-persistence only, for the reason given
     * at the state decision in [WalkFollower.onFix]. It is carried because
     * 4C.4 needs exactly this to gate rerouting, in the same place and for the
     * same reason the car path gates it (`NavSession.rerouteCouldHelp`): a
     * deviation by someone who is not moving is not worth a route request.
     *
     * Defaulted to false so a hand-built fix (a test of the puck, say) does
     * not have to assert a movement claim it has no opinion about. Every fix
     * [WalkFollower] produces supplies it explicitly.
     */
    val walking: Boolean = false,

    /**
     * Ground speed over the last few seconds, or null while the window is
     * short. See [WalkFollower.groundSpeed].
     *
     * **A different quantity from [movingMs]**, and the distinction is
     * load-bearing: [movingMs] is progress ALONG the route and is zero for a
     * walker stepping sideways off it, while this is displacement over the
     * ground and is not.
     */
    val groundMs: Double? = null,

    /**
     * Whether [groundMs] cleared the movement floor.
     *
     * The signal a reroute is gated on, one layer up. A deviation by someone
     * who is not moving is not worth a route request — the same rule the car
     * path applies in `NavSession.rerouteCouldHelp`, and applied at the same
     * layer, so the STATE stays a pure statement about distance while the
     * ACTION stays a judgement about whether asking would help.
     */
    val movingGround: Boolean = false,
) {
    /** True when the position may be drawn as a matched walking position. */
    val matched: Boolean
        get() = position != null &&
            (state == WalkFollowState.ON_ROUTE || state == WalkFollowState.UNCERTAIN)
}
