package dev.vector.geo.walk

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * The walking camera and puck (V7.4 4C.2).
 *
 * ## What replaced the 4C.1 stub, and on what evidence
 *
 * 4C.1 shipped a policy that asserted nothing, because no evidence had been
 * gathered about what a walking camera should do. This stage gathers it. Every
 * number below is derived from one of two measured sources — never from a
 * reference product's screenshot:
 *
 *  1. **Real Qatar walking geometry** (`V7.4-EVIDENCE/walking_geometry_evidence.py`,
 *     over the six captured `/foot` payloads from the 260912 bake):
 *
 *     ```
 *     maneuver gaps (182 real)      p10  33.5 m   p25  66.1 m   p50 139.7 m
 *     crossing spans (18 real)      p50  63.7-70.9 m          max 156.7 m
 *     per-vertex bearing change     p50  6-10 deg   p90 21-91 deg  max 145 deg
 *     ```
 *
 *  2. **The app's own screen arithmetic**, `VectorStyle.metresPx` — MapLibre
 *     zoom is defined against 512-logical-pixel tiles, so at Doha's latitude
 *     one logical pixel covers `70772 / 2^z` metres. That makes "how far ahead
 *     can the walker see" a calculation rather than a preference, which is
 *     what [zoomForLookAhead] performs.
 *
 * ## Why the car camera could not simply be pointed at a walk
 *
 * Restated from 4C.1 because this file is where the alternative is built:
 *
 *  * `MapCamera.ZOOM_BANDS` is a SPEED ladder (18.0 under 25 km/h down to 15.6
 *    over 115) meaning "about fifteen seconds of travel". Every walk sits in
 *    its bottom rung forever, and fifteen seconds on foot is 20 m — not a
 *    framing distance.
 *  * `ManeuverCamera.TURN_M` is 120 m: four seconds at 110 km/h, **ninety
 *    seconds** on foot.
 *  * `ManeuverCamera`'s stage entries (ANTICIPATE 300 m, FRAME 180 m) are
 *    further out than the p50 gap between two walking maneuvers, so a walk
 *    would be permanently inside the approach of something.
 *
 * None of those are modified. The car path is untouched.
 */

/**
 * Everything the walking camera reads. Derived from [WalkProgress] plus the
 * follow state; nothing else may reach the policy.
 *
 * Note what is still absent: **GPS speed**. At 1.35 m/s the receiver's speed
 * estimate is the same size as its own error, so a camera framed on it would
 * breathe with the noise. Movement, where it is needed, is measured as
 * along-route progress by [WalkFollower].
 */
data class WalkCameraInputs(
    /** Along-route position, the same number the state model reports. */
    val alongM: Double,

    /**
     * The route's bearing under the walker, or null on degenerate geometry.
     *
     * The ROUTE's bearing, never the receiver's heading. See
     * [WalkCameraPolicy] for the evidence behind that choice.
     */
    val bearingDeg: Double?,

    /** Metres to the next event, or null when the plan is exhausted. */
    val distanceToNextM: Double?,

    /** The kind of the next event, when there is one. */
    val nextKind: WalkManeuverKind?,

    /** Metres of route left to walk. */
    val remainingM: Double,

    /**
     * True while the walker is inside a crossing.
     *
     * The one walking state with no driving equivalent: a person is on a
     * crossing for 8.8–156.7 m of the real fixtures, up to two minutes.
     */
    val onCrossing: Boolean,

    /** True while the walker is on stairs. */
    val onStairs: Boolean,

    /**
     * Length of the span the walker is currently inside, in metres; 0 when
     * they are not inside one. Read from the backend's own sourced span
     * length, so the camera frames the crossing the map actually describes.
     */
    val activeSpanM: Double = 0.0,

    /** How the follower classifies this fix. */
    val followState: WalkFollowState = WalkFollowState.ON_ROUTE,
) {
    companion object {
        /**
         * Inputs for a walker the follower could not match.
         *
         * Needed because a CONFIRMED departure drops the route lock, so there
         * is no [WalkProgress] to derive from — and without this the
         * [WalkCameraRegime.OFF_ROUTE] regime could never be evaluated at all.
         * Every positional field is honestly absent: nothing is carried over
         * from the last match, because the whole point of the state is that
         * the walker is no longer demonstrably there.
         */
        fun unmatched(followState: WalkFollowState): WalkCameraInputs = WalkCameraInputs(
            alongM = 0.0,
            bearingDeg = null,
            distanceToNextM = null,
            nextKind = null,
            // NOT zero: a remaining distance of 0 would read as arrival, and
            // an unmatched walker has not arrived anywhere.
            remainingM = Double.MAX_VALUE,
            onCrossing = false,
            onStairs = false,
            activeSpanM = 0.0,
            followState = followState,
        )

        /** Derive the camera inputs from a walking state. */
        fun of(
            progress: WalkProgress,
            followState: WalkFollowState = WalkFollowState.ON_ROUTE,
        ): WalkCameraInputs = WalkCameraInputs(
            alongM = progress.alongM,
            bearingDeg = progress.bearingDeg,
            distanceToNextM = progress.distanceToNextM,
            nextKind = progress.next?.kind,
            remainingM = progress.remainingM,
            onCrossing = progress.onCrossing,
            onStairs = progress.onStairs,
            activeSpanM = progress.active?.maneuver?.spanM ?: 0.0,
            followState = followState,
        )
    }
}

/**
 * What the walking camera is doing, as a named state.
 *
 * Discrete regimes rather than a continuous function of distance, for the
 * reason the car camera learned the hard way: a zoom recomputed from the
 * distance to the next event on every fix is a camera that pumps once a
 * second. Each regime has one zoom, and the boundaries between them carry
 * hysteresis ([WalkCameraPolicy.APPROACH_EXIT_M]).
 */
enum class WalkCameraRegime {
    /** No opinion: the walker is not being followed. */
    IDLE,

    /** Ordinary walking follow. */
    CRUISE,

    /** A maneuver is close enough to need visual scale around it. */
    APPROACH,

    /** The walker is ON a crossing or a staircase. */
    SPAN,

    /** The end of the route is close. */
    ARRIVAL,

    /**
     * The walker has left the route.
     *
     * The camera deliberately does NOT tighten here. It widens, because the
     * useful thing to show someone who has left the route is the ground
     * between them and it — and it asserts no bearing, because the route's
     * bearing is no longer a statement about where they are going.
     */
    OFF_ROUTE,
}

/** What a walking camera policy may answer. */
sealed interface WalkCameraDecision {
    /**
     * The walking layer asserts nothing; whatever the map is doing continues.
     *
     * Not a placeholder. It is the positive statement that no camera write is
     * warranted on this fix, which is the common case — a regime that has not
     * changed writes nothing at all.
     */
    data object Unchanged : WalkCameraDecision

    /** One eased camera write. */
    data class Transition(
        val regime: WalkCameraRegime,
        val targetZoom: Double,
        val durationMs: Int,
        /**
         * The bearing the camera should face, or null to leave it alone.
         *
         * Null in [WalkCameraRegime.OFF_ROUTE], where the route bearing has
         * stopped describing the walker's direction.
         */
        val bearingDeg: Double?,
        /** Degrees of tilt. See [WalkCameraPolicy.WALK_TILT_DEG]. */
        val tiltDeg: Double,
    ) : WalkCameraDecision
}

/**
 * The walking camera policy.
 *
 * Pure apart from the regime it is holding, and that state is the whole point:
 * a policy with no memory cannot have hysteresis, and without hysteresis the
 * camera oscillates at every threshold.
 *
 * ## Bearing: route bearing, and why not the other three options
 *
 * The brief asks this to be decided rather than assumed. The four candidates,
 * against the evidence:
 *
 *  1. **Device heading — rejected.** `NavSession` withholds GPS heading below
 *     `MOVING_MS` = 1.5 m/s precisely because a slow receiver's heading is
 *     close to random, and the backend's own walking pace is 1.35 m/s. A
 *     walker is below that floor essentially always, so device heading on foot
 *     is the signal the car path already refuses to trust, used in the one
 *     mode where it is worst. It is also wrong in a way a driver never is: a
 *     pedestrian rotates on the spot, and a map that spins when someone looks
 *     over their shoulder is unusable.
 *  2. **North-up always — rejected as a default, honoured as a preference.**
 *     It is genuinely good for orienting by landmarks, which is why the
 *     existing `MapOrientation.NORTH_UP` preference is respected unchanged and
 *     this policy never overrides it. But as the default it gives up
 *     "left on screen is left ahead" at exactly the moment a walker is
 *     deciding which way to turn.
 *  3. **Hybrid (route bearing, falling back to heading) — rejected.** The
 *     fallback would engage exactly when the route bearing is unavailable,
 *     which is when the walker is off-route or unmatched — the case where
 *     heading is least trustworthy and a wrong bearing is most confusing.
 *     [WalkCameraRegime.OFF_ROUTE] asserts no bearing instead.
 *  4. **Route bearing — chosen.** It is already map-matched, so lateral GPS
 *     jitter does not enter it; it is defined at a standstill, so it does not
 *     spin when the walker stops; and the measured curvature it has to ride is
 *     gentle (p50 6–10 deg per vertex). Smoothing remains the caller's, via
 *     the existing `MapCamera.smoothBearing`, which is shared and unmodified.
 *
 * ## Pitch: flat, deliberately
 *
 * The car tilts to 60 deg, and that tilt is bought for a specific reason
 * stated on `MapCamera.NAV_ZOOM`: it compresses the far field to buy back the
 * look-ahead that a closer zoom spends. **A walk does not need that trade.**
 * At 1.35 m/s, [CRUISE_LOOKAHEAD_M] of 120 m is already 89 seconds of
 * walking; the look-ahead pitch would buy is time the walker does not need.
 * Against that, tilt costs legibility of a map someone is reading while
 * standing still, and it interacts badly with the rotate-on-the-spot problem
 * above. So walking is flat, and the decision is a constant with a reason
 * rather than an omission.
 */
class WalkCameraPolicy(
    /**
     * Map viewport height in LOGICAL pixels, for the zoom derivation.
     *
     * Defaulted to the S24 Ultra's 1040 dp (3120 physical / 3.0 density),
     * which is the device V7 has been validated on throughout. It is a
     * parameter rather than a constant because the zoom that shows 120 m of
     * ground is genuinely a property of the screen, and baking one handset's
     * height into the policy would make the framing wrong everywhere else —
     * the same mistake `MapCamera.smoothBearing` avoids by deriving its blend
     * from real elapsed time rather than per-frame.
     */
    private val viewportHeightPx: Double = DEFAULT_VIEWPORT_H_PX,
) {
    private var regime: WalkCameraRegime = WalkCameraRegime.IDLE
    private var writtenZoom: Double? = null
    /** The zoom latched when the current span became active. See [SPAN]. */
    private var spanZoom: Double? = null

    /** The regime currently held. For tests and telemetry. */
    val currentRegime: WalkCameraRegime get() = regime

    /** Forget everything. Call when the route is replaced. */
    fun reset() {
        regime = WalkCameraRegime.IDLE
        writtenZoom = null
        spanZoom = null
    }

    /**
     * Decide the camera for one fix.
     *
     * Returns [WalkCameraDecision.Unchanged] whenever nothing needs to move,
     * which is most fixes: a write happens when the REGIME changes, or when
     * the zoom the regime wants has drifted from the one last written by more
     * than [REANCHOR]. That is what makes a walk one settled camera rather
     * than a sequence of nudges.
     *
     * @param cameraOwned whether a decision made here would actually reach
     *   the map. False while the user holds the camera — panned away, in
     *   overview, or on a hand-rotated map.
     *
     *   ## The defect this parameter exists because of
     *
     *   It was originally absent, and the gate lived only in the caller
     *   ([WalkNavSession.cameraMayMove]). So while the user held the camera
     *   this policy went on recording [writtenZoom] for decisions that were
     *   then DISCARDED — and when the user recentred, the policy compared the
     *   regime's zoom against a value it believed it had written, found no
     *   drift, and stayed silent. The map kept whatever zoom the user had left
     *   it at and the walking framing was never restored.
     *
     *   That is precisely the "recenter must restore follow deterministically"
     *   property, broken by bookkeeping. The fix is for the policy to stop
     *   claiming writes it did not make: while the camera is not its own it
     *   tracks the REGIME (so state stays correct) and forgets the zoom (so
     *   the first fix after the hand-back writes).
     */
    fun decide(
        inputs: WalkCameraInputs,
        cameraOwned: Boolean = true,
    ): WalkCameraDecision {
        val want = regimeFor(inputs)

        if (!cameraOwned) {
            // Track the regime; claim nothing about the map. Forgetting the
            // written zoom is what makes the hand-back deterministic.
            regime = want
            spanZoom = if (want == WalkCameraRegime.SPAN) spanZoomFor(inputs) else null
            writtenZoom = null
            return WalkCameraDecision.Unchanged
        }

        // Entering or leaving a span latches its zoom once, so a 156 m
        // crossing does not re-zoom on every metre walked across it.
        if (want == WalkCameraRegime.SPAN) {
            if (regime != WalkCameraRegime.SPAN) spanZoom = spanZoomFor(inputs)
        } else {
            spanZoom = null
        }

        if (want == WalkCameraRegime.IDLE) {
            regime = want
            writtenZoom = null
            return WalkCameraDecision.Unchanged
        }

        val zoom = zoomFor(want, inputs)
        val changedRegime = want != regime
        // ONE rule for whether to write, and it is about the SCALE rather than
        // about the regime: a camera move nobody can see is a wasted write and
        // a wasted ease.
        //
        // This matters because two regime pairs legitimately want almost the
        // same zoom — APPROACH asks for 90 m of look-ahead and a typical real
        // crossing span (49–64 m, so 90 m after the margin and floor) asks for
        // exactly the same. Writing on the regime change alone would ease the
        // camera from z19.085 to z19.085 every time a walker stepped off the
        // approach onto the crossing. The regime still updates — other
        // consumers read [currentRegime] — but the camera stays still.
        val drifted = writtenZoom?.let { abs(zoom - it) > REANCHOR } ?: true
        regime = want
        if (!drifted) return WalkCameraDecision.Unchanged

        writtenZoom = zoom
        return WalkCameraDecision.Transition(
            regime = want,
            targetZoom = zoom,
            durationMs = if (changedRegime) REGIME_DURATION_MS else REANCHOR_DURATION_MS,
            // Off-route asserts no bearing: the route's direction has stopped
            // being a statement about where this person is going.
            bearingDeg = if (want == WalkCameraRegime.OFF_ROUTE) null else inputs.bearingDeg,
            tiltDeg = WALK_TILT_DEG,
        )
    }

    /**
     * Which regime this fix falls in, with hysteresis on the APPROACH edge.
     *
     * Order matters and is stated rather than emergent: off-route beats
     * everything (the walker's relationship to the route is the most important
     * fact about them); arrival beats a span, because a crossing at the very
     * end of a route should not hold the camera past the destination; a span
     * beats an approach, because being ON a crossing is what the walker is
     * doing and the maneuver beyond it is not.
     */
    private fun regimeFor(inputs: WalkCameraInputs): WalkCameraRegime {
        if (inputs.followState == WalkFollowState.ACQUIRING) return WalkCameraRegime.IDLE
        if (inputs.followState == WalkFollowState.OFF_ROUTE) return WalkCameraRegime.OFF_ROUTE
        if (inputs.remainingM <= ARRIVAL_M) return WalkCameraRegime.ARRIVAL
        if (inputs.onCrossing || inputs.onStairs) return WalkCameraRegime.SPAN

        val d = inputs.distanceToNextM
        if (d != null && significant(inputs.nextKind)) {
            // Hysteresis: enter APPROACH at 60 m, leave it only past 90 m.
            // Without the band a walker hovering at 60 m — which at 1.35 m/s
            // and a few metres of jitter is an entirely ordinary thing to do
            // while waiting to cross — would flip the camera once a second.
            val threshold = if (regime == WalkCameraRegime.APPROACH) APPROACH_EXIT_M
                            else APPROACH_ENTER_M
            if (d <= threshold) return WalkCameraRegime.APPROACH
        }
        return WalkCameraRegime.CRUISE
    }

    private fun zoomFor(regime: WalkCameraRegime, inputs: WalkCameraInputs): Double =
        when (regime) {
            WalkCameraRegime.IDLE -> zoomForLookAhead(CRUISE_LOOKAHEAD_M)
            WalkCameraRegime.CRUISE -> zoomForLookAhead(CRUISE_LOOKAHEAD_M)
            WalkCameraRegime.APPROACH -> zoomForLookAhead(APPROACH_LOOKAHEAD_M)
            WalkCameraRegime.SPAN -> spanZoom ?: spanZoomFor(inputs)
            WalkCameraRegime.ARRIVAL -> zoomForLookAhead(ARRIVAL_LOOKAHEAD_M)
            WalkCameraRegime.OFF_ROUTE -> zoomForLookAhead(OFF_ROUTE_LOOKAHEAD_M)
        }

    /**
     * The zoom for a span, from the span's OWN length.
     *
     * This is the one regime whose framing is read from the route rather than
     * from a constant, and it is the reason [WalkCameraInputs.activeSpanM]
     * exists: real crossings run from 8.8 m to 156.7 m, an eighteen-fold
     * range, and a single zoom cannot frame both. The look-ahead is the span
     * itself plus a margin so the far kerb is not against the screen edge,
     * clamped so a freakish span cannot drive the camera somewhere useless.
     *
     * Latched on entry, never recomputed mid-span.
     */
    private fun spanZoomFor(inputs: WalkCameraInputs): Double {
        val span = inputs.activeSpanM.coerceAtLeast(0.0)
        val lookAhead = (span + SPAN_MARGIN_M)
            .coerceIn(SPAN_LOOKAHEAD_MIN_M, SPAN_LOOKAHEAD_MAX_M)
        return zoomForLookAhead(lookAhead)
    }

    /**
     * The zoom at which [metres] of ground fit in the look-ahead part of the
     * viewport.
     *
     * The inverse of `VectorStyle.metresPx`, and it is arithmetic rather than
     * taste: MapLibre's zoom is defined against 512-logical-pixel tiles, so
     * one logical pixel covers `METRES_PER_PX_Z0 / 2^z` metres at the
     * calibration latitude, and the look-ahead strip is
     * `viewportHeightPx * LOOK_AHEAD_FRACTION` pixels tall.
     *
     * Clamped to [MIN_ZOOM]..[MAX_ZOOM]. The upper clamp matters: without it a
     * 9 m crossing would ask for z21.5, past anything the basemap has detail
     * for, and the map would go blank at the moment the walker most needs it.
     */
    fun zoomForLookAhead(metres: Double): Double {
        if (metres <= 0.0) return MAX_ZOOM
        val strip = viewportHeightPx * LOOK_AHEAD_FRACTION
        val z = log2(METRES_PER_PX_Z0 * strip / metres)
        return z.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Ground metres visible in the look-ahead strip at [zoom]. For tests. */
    fun lookAheadAt(zoom: Double): Double =
        METRES_PER_PX_Z0 * viewportHeightPx * LOOK_AHEAD_FRACTION / 2.0.pow(zoom)

    companion object {
        /**
         * Is this event worth tightening the camera for?
         *
         * `continue` and `depart` are not: the first is a way-identity change
         * the walker does nothing about, and the second has already happened.
         * The same exclusion the car camera makes (`ManeuverCamera.isManeuver`),
         * for the same reason, stated here rather than shared because the two
         * vocabularies are different — this one is the 4B.2 plan's.
         */
        fun significant(kind: WalkManeuverKind?): Boolean = when (kind) {
            null, WalkManeuverKind.CONTINUE, WalkManeuverKind.DEPART -> false
            else -> true
        }

        /**
         * Metres per logical pixel at zoom 0, at Doha's latitude.
         *
         * `40_075_016.686 * cos(25.2854 deg) / 512`. The same derivation
         * `VectorStyle.METRES_PER_PX_Z0` performs; restated here because
         * `core-geo` cannot see the app module, and pinned against it by
         * `WalkCameraScreenTest` from the side that can see both.
         */
        const val METRES_PER_PX_Z0: Double = 70_772.0

        /**
         * Where the walker sits on screen, as a fraction from the top.
         *
         * 0.68, the same value the car uses (`VectorTokens.LOOK_AHEAD_FRACTION`),
         * and shared deliberately: it is a statement about how much of a
         * screen should be spent on ground already covered, which does not
         * depend on how fast the person is moving.
         */
        const val LOOK_AHEAD_FRACTION = 0.68

        /** S24 Ultra: 3120 physical pixels / 3.0 density. See the constructor. */
        const val DEFAULT_VIEWPORT_H_PX = 1040.0

        /**
         * Ordinary walking look-ahead.
         *
         * 120 m, which at the backend's 1.35 m/s is **89 seconds** of walking.
         * Bracketed by the measured maneuver spacing: p25 is 66.1 m and p50 is
         * 139.7 m, so 120 m shows the walker roughly to the next decision on a
         * typical stretch without spending the screen on ground beyond it.
         *
         * The car's equivalent rule is "about fifteen seconds of travel",
         * which on foot would be 20 m — closer than the shortest real gap
         * between two maneuvers (33.5 m at p10), i.e. a camera that could not
         * show the next event even when it was the nearest thing on the route.
         */
        const val CRUISE_LOOKAHEAD_M = 120.0

        /**
         * Look-ahead while approaching a maneuver.
         *
         * 90 m. Tighter than cruise by a third (z18.670 -> z19.085), which is
         * a visible change of scale without being a lurch, and it still
         * comfortably contains the p25 maneuver gap of 66.1 m — so the
         * junction being approached and the ground just past it are both on
         * screen.
         *
         * **This is also the tightest zoom the policy ever asks for**, and
         * that is deliberate rather than incidental. The first version of this
         * ladder asked for 70 m (z19.45) here and 55 m (z19.50) at arrival,
         * and both CLAMPED at [MAX_ZOOM] — three regimes collapsing onto one
         * zoom while their documented look-aheads said otherwise. A constant
         * whose stated value is not the value that renders is worse than no
         * constant, so the ladder was re-derived to sit inside the clamp with
         * headroom (+0.165 of a level) and [MAX_ZOOM] went back to being a
         * guard rather than a governor.
         */
        const val APPROACH_LOOKAHEAD_M = 90.0

        /**
         * Distance to a significant event at which APPROACH begins.
         *
         * 60 m, about 44 seconds of walking. Under the p25 maneuver gap, so a
         * typical leg has a cruise phase before its approach rather than being
         * permanently inside one — the failure the car's 180–300 m stage
         * entries would produce verbatim on a walk, where the p50 gap is
         * 139.7 m.
         */
        const val APPROACH_ENTER_M = 60.0

        /**
         * Distance at which APPROACH is left again — the hysteresis band.
         *
         * 90 m against an entry of 60 m: a 30 m band, about 22 seconds of
         * walking. Wide enough that GPS jitter (~15 m of urban-canyon error,
         * already measured in this codebase) cannot flip the regime, and wide
         * enough to cover a walker stepping back and forth at a kerb.
         */
        const val APPROACH_EXIT_M = 90.0

        /** Margin beyond a span's far end, so the kerb is not on the edge. */
        const val SPAN_MARGIN_M = 25.0

        /**
         * Floor for a span's look-ahead.
         *
         * 90 m, the same as [APPROACH_LOOKAHEAD_M]. The shortest real crossing
         * measured is 8.8 m, and framing 34 m of ground for it would ask for
         * z20.4 — past anything the basemap has detail for, on a map someone
         * is looking at while standing in a road. The floor means every span
         * shorter than ~65 m is framed exactly as its own approach was, so
         * stepping onto a typical crossing changes the camera not at all.
         */
        const val SPAN_LOOKAHEAD_MIN_M = 90.0

        /**
         * Ceiling for a span's look-ahead.
         *
         * 200 m. The longest real span measured is 156.7 m, which with the
         * margin asks for 181.7 m — inside this, so the ceiling is a guard
         * against a freak span rather than a value that shapes any real
         * crossing.
         */
        const val SPAN_LOOKAHEAD_MAX_M = 200.0

        /**
         * Remaining route distance at which the camera enters ARRIVAL.
         *
         * 30 m. Deliberately just above the car's `ARRIVAL_RADIUS_M` of 25 m,
         * so the camera has settled into its arrival framing BEFORE the
         * navigation layer declares arrival — a camera that changes at the
         * same instant as the state would read as a glitch on top of an event.
         */
        const val ARRIVAL_M = 30.0

        /**
         * Look-ahead at arrival.
         *
         * 100 m. The walker has at most [ARRIVAL_M] = 30 m of route left, so
         * this frames the whole remainder plus ~70 m of the surroundings the
         * destination sits in — which is the question at arrival ('where is
         * it from here?'), not the question on approach to a turn. Slightly
         * wider than [APPROACH_LOOKAHEAD_M] for that reason, and the step from
         * cruise (+0.263 of a level) clears [REANCHOR], so it is a write.
         */
        const val ARRIVAL_LOOKAHEAD_M = 100.0

        /**
         * Look-ahead when off-route: WIDER than cruise, not tighter.
         *
         * 200 m. The useful thing to show someone who has left the route is
         * the ground between them and it, and the measured off-route radius is
         * 45 m — so a frame that shows a couple of hundred metres contains
         * both the walker and the route they left. Tightening here would be
         * the camera zooming in on the one thing it is least sure about.
         */
        const val OFF_ROUTE_LOOKAHEAD_M = 200.0

        /**
         * Tilt for walking: flat. See the class KDoc for the full argument.
         */
        const val WALK_TILT_DEG = 0.0

        /**
         * How far the wanted zoom must drift from the written one to re-write
         * within the same regime.
         *
         * 0.25 of a zoom level. Only the SPAN regime can drift at all (its
         * zoom is read from the span length), so in practice this stops a
         * sequence of similar crossings from re-writing the camera for a
         * difference nobody can see. Well under the 0.8 `DRIVER_ZOOM_TOLERANCE`
         * the app uses to recognise a pinch, so a re-anchor can never be
         * mistaken for the user taking the zoom.
         */
        const val REANCHOR = 0.25

        /** Ease for a regime change: the app's standard camera nudge. */
        const val REGIME_DURATION_MS = 380

        /** Ease for a re-anchor within a regime: longer, because it is minor. */
        const val REANCHOR_DURATION_MS = 600

        /**
         * Zoom bounds. Guards, not targets — see [APPROACH_LOOKAHEAD_M].
         *
         * The upper bound is the one that matters. The basemap is baked to z13
         * and overzoomed from there, and the V7 Stage 2 device sweep found
         * z19.05 already at the edge of useful for framing a junction. The
         * tightest zoom this policy ever asks for is z19.085 (a 90 m
         * look-ahead), so 19.25 leaves a sixth of a level of headroom and
         * nothing in normal operation reaches it. If a future look-ahead
         * constant is lowered far enough to clamp here, that is a bug in the
         * constant rather than a limit doing its job, and
         * `WalkCameraTest.no regime ever clamps` will say so.
         */
        const val MIN_ZOOM = 15.0
        const val MAX_ZOOM = 19.25
    }
}

/**
 * Where and how the walking puck is drawn (V7.4 4C.2).
 *
 * ## What it represents
 *
 * The MATCHED walking position: the route projection supplied by
 * [WalkFollower], which is `RouteIndex.project`'s perpendicular foot on the
 * route line. It is never the position of the next maneuver, and
 * `puckNeverJumpsToTheNextManeuver` pins that — a puck that snapped forward to
 * whatever was coming next would be a position claim nobody made.
 *
 * ## The lateral decision, unchanged from 4C.1 and now load-bearing
 *
 * The car puck is offset into its carriageway by `RouteLanes`, because a
 * vehicle drawn on the centreline of a two-way road straddles the oncoming
 * lane. Walking declines that offset, explicitly, by [CENTRELINE_OFFSET_M]:
 *
 *  * a footway has no lanes and no carriageway, so the lane model has no
 *    input and would decline anyway — but a pavement beside a road carries
 *    the ROAD's `highway` class, so a future change to how the basis is chosen
 *    could silently start offsetting a pedestrian 1.75 m sideways, which on a
 *    2 m pavement is the difference between the pavement and the road;
 *  * and the measurement makes the point on its own: a foreign pedestrian edge
 *    is within 15 m of the route at about half of all sampled points, while a
 *    fix is up to 30 m wide. Vector cannot resolve which side of a path
 *    someone is on, so drawing them on one is a claim it cannot support.
 */
object WalkPuck {

    /**
     * The lateral offset applied to a walking puck: zero, deliberately.
     *
     * Not "not yet implemented". See the object KDoc.
     */
    const val CENTRELINE_OFFSET_M = 0.0

    /** True when the walking layer wants a lateral offset. Always false. */
    fun wantsLateralOffset(): Boolean = false

    /**
     * The puck for one matched fix, or null when there is nothing to draw.
     *
     * Null for [WalkFollowState.ACQUIRING] (no match yet) and for
     * [WalkFollowState.OFF_ROUTE] — in the second case the caller should draw
     * the RAW fix instead, because moving an unmatched position onto a route
     * the walker has left is exactly the fabrication the car path refuses in
     * the same situation.
     */
    fun of(fix: WalkFix): WalkPuckState? {
        if (!fix.matched) return null
        val p = fix.progress ?: return null
        val at = fix.position ?: return null
        return WalkPuckState(
            position = at,
            bearingDeg = p.bearingDeg,
            segmentIndex = p.segmentIndex,
            segmentClass = p.currentSegmentClass,
            onCrossing = p.onCrossing,
            onStairs = p.onStairs,
            traversedM = p.traversedM,
            remainingM = p.remainingM,
            fraction = p.fraction,
            confident = fix.state == WalkFollowState.ON_ROUTE,
        )
    }
}

/**
 * The walking puck, as facts a renderer can draw.
 *
 * Deliberately carries no colours, no sizes and no drawables: the existing
 * puck renderer owns those, and this is the adapter that feeds it rather than
 * a second renderer.
 */
data class WalkPuckState(
    /** The matched position on the route. */
    val position: dev.vector.geo.LngLat,

    /** The route's bearing there, or null on degenerate geometry. */
    val bearingDeg: Double?,

    /** Which geometry segment the walker is on; indexes the segment arrays. */
    val segmentIndex: Int?,

    /** The `classes` value of that segment — a fact, not a road identity. */
    val segmentClass: String?,

    val onCrossing: Boolean,
    val onStairs: Boolean,
    val traversedM: Double,
    val remainingM: Double,
    val fraction: Double,

    /**
     * True when the fix was inside the on-route radius.
     *
     * False means the position is a HELD one from a fix in the uncertain band
     * ([WalkFollowState.UNCERTAIN]) — still the best available answer, but not
     * freshly confirmed, and a renderer may choose to show that. It is the one
     * piece of honesty the puck carries about its own quality, and it exists
     * because the geometry evidence says the uncertain band is wide.
     */
    val confident: Boolean,
)
