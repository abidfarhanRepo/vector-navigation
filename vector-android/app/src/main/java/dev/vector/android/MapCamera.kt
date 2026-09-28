package dev.vector.android

import dev.vector.geo.LngLat

/**
 * The camera policy, as data.
 *
 * Before this file the whole policy was four hardcoded ternaries inside
 * `MainActivity.camera()`:
 *
 * ```
 * .bearing(if (phase == NAVIGATING) bearing else map.cameraPosition.bearing)
 * .tilt(if (phase == NAVIGATING) 45.0 else 0.0)
 * .zoom(map.cameraPosition.zoom.coerceAtLeast(if (phase == NAVIGATING) 16.5 else 14.0))
 * ```
 *
 * That is not a small thing to have had no model for, because two of those
 * three lines fought the driver:
 *
 * 1. **`coerceAtLeast(16.5)` ran on every followed frame**, so pinching out
 *    during navigation was undone within ~8 ms. "Let me see what is coming"
 *    was not expressible. Zoom is now touched ONLY at discrete transitions —
 *    starting navigation, recentering, entering overview — and never by the
 *    frame loop. [CameraTarget.zoom] is null for a follow frame, meaning
 *    "leave the driver's zoom alone".
 * 2. **There was no north-up.** The map faced the direction of travel while
 *    navigating and north otherwise, with no way to choose. Drivers differ on
 *    this and it is the single most common map setting in the category.
 *
 * Rotation is modelled as a THIRD state rather than as a mode, which is what
 * the "temporary manual rotation" the map should communicate actually is: a
 * rotate gesture is a request about this moment, not a change of preference.
 * Position-following continues through it, and recentering clears it — so
 * turning the map to look at a junction does not silently cost the driver the
 * follow camera they will want back in four seconds.
 *
 * Pure: no MapLibre types, no Android types. `MapCameraTest` exercises every
 * combination on the JVM.
 */

/** Which way is up. */
enum class MapOrientation {
    /** The map faces north. Landmarks stay where the driver last saw them. */
    NORTH_UP,

    /** The map faces the direction of travel. Left on screen is left ahead. */
    HEADING_UP,
}

/** Flat or tilted. */
enum class MapPerspective {
    /** Straight down. Reads as a map. */
    FLAT,

    /**
     * Tilted toward the horizon, so more of the road ahead is on screen.
     *
     * Only applied in [MapOrientation.HEADING_UP]. A tilted map that does NOT
     * rotate puts the horizon at a fixed screen edge while the car turns
     * underneath it, so "ahead" and "up the screen" come apart — which is
     * worse than either flat-north-up or tilted-heading-up. The 2D/3D control
     * is therefore only offered while heading-up, rather than being offered and
     * then quietly ignored.
     */
    TILTED,
}

/**
 * What the camera is doing, as distinct from what the driver prefers.
 *
 * [FOLLOW] and [FREE] are the two the driver moves between constantly;
 * [OVERVIEW] is a deliberate "show me the whole route" that must not be
 * confused with having panned away, because leaving it should return to
 * following rather than leaving the camera wherever the route happened to fit.
 */
enum class CameraMode { FOLLOW, FREE, OVERVIEW }

/**
 * A camera position to move to.
 *
 * A null [zoom] means **do not change the zoom**. That is the whole point of
 * the type: the frame loop can express "put the camera here, facing this way"
 * without also asserting a zoom level sixty times a second.
 */
data class CameraTarget(
    val position: LngLat,
    val bearing: Double,
    val tilt: Double,
    val zoom: Double? = null,
)

/** Camera-relevant state, separated out so the policy has one input. */
data class CameraState(
    val mode: CameraMode = CameraMode.FOLLOW,
    val orientation: MapOrientation = MapOrientation.HEADING_UP,
    val perspective: MapPerspective = MapPerspective.TILTED,
    /**
     * A bearing the driver set by hand, or null.
     *
     * Survives until recenter or until [MapCamera.autoResume] expires it.
     * While set, the orientation preference is overridden but
     * position-following is not — see the file KDoc.
     */
    val manualBearing: Double? = null,
    /**
     * When the driver last took the camera, in wall-clock millis, or null.
     *
     * Set by [MapCamera.onPan] and [MapCamera.onRotate]; read only by
     * [MapCamera.autoResume]. Wall clock rather than `nanoTime` because it is
     * compared against a timeout a person can feel, and the comparison happens
     * on the fix path where a wall-clock stamp is already in hand.
     */
    val takenOverAtMs: Long? = null,
) {
    /** True when a control should offer to put the camera back on the vehicle. */
    val needsRecenter: Boolean
        get() = mode != CameraMode.FOLLOW || manualBearing != null
}

/**
 * Stops the follow loop from destroying a deliberate camera move.
 *
 * ## The defect, and why nothing found it until V5
 *
 * Vector has two kinds of camera write. A **transition** — starting
 * navigation, recentering, fitting a route, an auto-zoom band change — is an
 * animated move that asserts a zoom and takes hundreds of milliseconds. A
 * **follow frame** is a cheap `moveCamera` issued once per display frame that
 * asserts position and bearing and deliberately leaves the zoom alone.
 *
 * `moveCamera` cancels whatever animation is running. So the first follow frame
 * after a transition begins — 8 ms later on a 120 Hz panel — killed it, and
 * re-supplied the zoom it read back from the camera *mid-flight*, which is
 * still the zoom the transition was moving away from.
 *
 * The consequences, all of them silent:
 *
 *  * The 1 800 ms nav-start flight V4 built and measured never ran. Navigation
 *    started at whatever zoom the route preview had been fitted to — about
 *    z12.5 for a 6 km route, which is **six kilometres of Doha on screen with
 *    the vehicle as a speck**. Reported from the S24 as *"the zoom is
 *    completely wrong; it should focus more on the current GPS icon as it
 *    moves."*
 *  * Auto-zoom never applied either, and then made it permanent: its
 *    "the driver's own zoom wins" rule saw the camera four levels from the
 *    band it wanted, concluded the driver had pinched out, and handed them the
 *    zoom for the rest of the journey.
 *  * Recentering moved the position and not the zoom.
 *
 * **It cannot happen while the car is parked.** A follow frame is only emitted
 * when [RouteTracker] holds a lock and is interpolating, which needs a moving
 * vehicle. So every stationary check — including V4's, which measured the
 * flight frame by frame and found it correct — sees the transition complete
 * perfectly. This is the exact class of defect V5 exists to find, and it took a
 * replayed drive on the device to see it.
 *
 * Pure and unit-tested. The Activity owns one of these and asks it before every
 * follow write.
 */
class CameraGate {

    private var busyUntilMs = 0L

    /**
     * The zoom the last Vector-initiated transition asserted, if any.
     *
     * This is the ownership half of the gate, and it exists because of a
     * measured defect (V7 Stage 3, D1). "Has the driver pinched?" used to be
     * inferred by comparing the live camera zoom against the speed-band zoom —
     * which cannot distinguish a finger from Vector's OWN maneuver-camera
     * write. `ANTICIPATE` lifts the zoom by up to 0.85 and `FRAME` by up to
     * 1.55, both past the 0.8 pinch tolerance, so on the next GPS fix the
     * camera read its own move as the driver taking over, suspended itself for
     * the rest of the maneuver, and — because recovery sits behind the same
     * gate — never came back. Measured on all five Doha acceptance drives.
     *
     * A transition that asserts a zoom claims it; a followed frame asserts no
     * zoom and claims nothing. Null means Vector has never asserted a zoom, in
     * which case there is no baseline to have departed from.
     */
    private var assertedZoom: Double? = null

    /**
     * A transition has begun and will animate for [durationMs].
     *
     * @param zoom the zoom this transition asserts, or null when it moves the
     *   camera without asserting one (a position/bearing-only move leaves the
     *   existing ownership fact alone rather than discarding it).
     */
    fun beginTransition(nowMs: Long, durationMs: Int, zoom: Double? = null) {
        if (zoom != null) assertedZoom = zoom
        if (durationMs <= 0) return
        // The LATER of the two, not the newest: two transitions can overlap
        // (a recenter during a nav-start flight), and the follow loop must
        // stay out of the way until both are done.
        busyUntilMs = maxOf(busyUntilMs, nowMs + durationMs)
    }

    /** May a follow frame write the camera now? */
    fun mayFollow(nowMs: Long): Boolean = nowMs >= busyUntilMs

    /** Is a Vector-initiated eased move still animating? */
    fun transitionInFlight(nowMs: Long): Boolean = !mayFollow(nowMs)

    /** The zoom Vector last asserted, for tests and diagnostics. */
    fun assertedZoom(): Double? = assertedZoom

    /**
     * Does the live camera zoom belong to the DRIVER rather than to Vector?
     *
     * The single ownership question, asked by both zoom policies
     * (`maybeManeuverCamera` and `maybeAutoZoom`) so they cannot disagree about
     * who owns the zoom — and asked against the right baseline:
     *
     * * a transition still in flight is Vector's own animation, mid-ease, and
     *   is never the driver;
     * * with no asserted zoom there is nothing to have departed from;
     * * otherwise the driver owns it exactly when the live zoom has moved more
     *   than [MapCamera.DRIVER_ZOOM_TOLERANCE] away from what Vector put there.
     *
     * Note this is deliberately NOT the same as raising the tolerance: a pinch
     * of 0.9 still counts, at any complexity, because the comparison is now
     * against Vector's own value rather than against a band the maneuver camera
     * is legitimately sitting above.
     */
    fun isDriverZoom(
        liveZoom: Double,
        nowMs: Long,
        tolerance: Double = MapCamera.DRIVER_ZOOM_TOLERANCE,
    ): Boolean {
        if (transitionInFlight(nowMs)) return false
        val owned = assertedZoom ?: return false
        return kotlin.math.abs(liveZoom - owned) > tolerance
    }

    /**
     * The driver touched the map.
     *
     * A gesture wins immediately and completely. Waiting out the rest of an
     * animation before honouring it is the "camera fights the driver" failure
     * the whole of [MapCamera] exists to avoid, and it is worse than the
     * clobbering this class prevents.
     *
     * [assertedZoom] deliberately survives: pan and rotate are the gestures
     * that call this, and neither changes the zoom, so the last zoom Vector
     * asserted is still the true baseline for judging a later pinch. Forgetting
     * it here would make the next pinch invisible.
     */
    fun cancel() {
        busyUntilMs = 0L
    }
}

object MapCamera {

    /**
     * Zoom set when navigation starts.
     *
     * Applied once, as a transition, not as a floor. Close enough to read lane
     * markings and street names; far enough to see the next junction.
     *
     * ## Why this moved from 16.5, and why [TILT_DEG] had to move with it
     *
     * The sentence above was the intent from the start and the style could
     * never honour it, because until V8 there were no lane markings in the
     * document to read. There are now, and they are drawn at true metric width
     * — which puts a hard floor under this number. At 16.5 a Doha lane is
     * 4.6 logical pixels across (`VectorStyle.laneWidthPx`); dividers at that
     * scale alias into a grey smear. Lane rendering needs roughly z17.5-18.
     *
     * But zoom is bought with look-ahead, and look-ahead is the half of the
     * sentence this constant was protecting. 17.5 alone would halve the view
     * of the next junction, which is a strictly worse camera however good the
     * road looks.
     *
     * Pitch is what pays for it. Going from 45 to 60 degrees compresses the far
     * field into the top of the frame and returns roughly the ground distance
     * the zoom step took away, which is why these two constants move together
     * or not at all. Neither is independently defensible.
     *
     * Note that MapLibre reports zoom at the CENTRE of the viewport, so at 60
     * degrees the near field where the car is renders at a meaningfully higher
     * effective scale than 17.5 — the lane markings under the car are wider
     * than the arithmetic above suggests, and the far ones narrower.
     */
    const val NAV_ZOOM = 17.5

    /** Zoom set when recentering outside navigation. */
    const val EXPLORE_ZOOM = 15.0

    /**
     * Degrees of tilt in [MapPerspective.TILTED].
     *
     * 60 is not a preference, it is the ceiling: `MapLibreConstants
     * .MAXIMUM_PITCH` is 60.0f in the SDK, and `setMaxPitchPreference` clamps
     * to it. So this is as far as the camera can be pitched without a forked
     * renderer, and V8 §5.4's "60-70, tune on device" resolves to 60.
     *
     * Raised from 45 with [NAV_ZOOM] — see there for why one without the other
     * makes the camera worse rather than better.
     *
     * [tiltFor]'s gating is untouched: still only while navigating, still only
     * heading-up, still only when the driver has asked for a tilted map. This
     * changes the constant, not the policy.
     */
    const val TILT_DEG = 60.0

    /**
     * The zoom from which extruded buildings are drawn (V7 3D).
     *
     * 15 because that is where the data is: the tile bake covers z11-z15, so a
     * layer demanding z14 would render nothing at any zoom the map reaches
     * (MapLibre requests no tiles past a source's maxzoom and simply scales
     * the z15 tile, but the LAYER's own minzoom is what decides whether it
     * draws). z15 is also the first zoom at which a Doha footprint is big
     * enough on screen to read as a volume rather than as a spike.
     *
     * Kept here rather than in the style so the two cannot drift: the style
     * interpolates this exact constant into the layer, and the test suite
     * asserts a layer exists at this zoom in style JSON.
     */
    const val BUILDINGS_3D_MINZOOM = 15.0

    /**
     * Where the camera should be for this frame, or null to leave it alone.
     *
     * @param travelBearing the direction of travel, from the route geometry
     *   when on-route and from the GPS receiver otherwise.
     */
    fun follow(
        cam: CameraState,
        phase: Phase,
        position: LngLat,
        travelBearing: Double,
    ): CameraTarget? {
        // FREE means the driver is looking somewhere; OVERVIEW means the camera
        // is framing the route. Moving it either way would be taking the map
        // away from them.
        if (cam.mode != CameraMode.FOLLOW) return null
        // In PREVIEW the camera is framing the whole route. Yanking it to the
        // vehicle would undo the fit the driver is reading.
        if (phase == Phase.PREVIEW) return null

        return CameraTarget(
            position = position,
            bearing = bearingFor(cam, travelBearing),
            tilt = tiltFor(cam, phase),
            // Never on a follow frame. See the file KDoc.
            zoom = null,
        )
    }

    /**
     * The camera for a discrete transition: start, recenter, resume follow.
     *
     * This is the ONLY place a zoom is asserted, and it is also why recentering
     * feels like a command rather than a nudge — it restores the whole camera,
     * not just the position. Whose command it is decides whether the zoom is
     * restored at all: see the [zoom] parameter.
     */
    fun recenter(
        cam: CameraState,
        phase: Phase,
        position: LngLat,
        travelBearing: Double,
        /**
         * What to do about zoom, which is the part a recenter must be careful
         * with because it is the only camera property the driver also owns.
         *
         * Pass [transitionZoom] for a recenter the driver ASKED for (the
         * button, starting navigation, the first fix): restoring the whole
         * camera is the point, and going through [transitionZoom] rather than
         * a hardcoded [NAV_ZOOM] means the restore lands on the speed band the
         * map would have chosen anyway.
         *
         * Pass **null** for a recenter the app decided on by itself
         * ([autoResume]), which must put the map back in front of the car
         * without touching a zoom the driver chose. Hardcoding [NAV_ZOOM] here
         * meant a deliberate pinch was undone 10 s later, every 10 s
         * (2026-09-14 drives); worse, at speed the resulting 1.3-step jump
         * exceeded the 0.8 tolerance in `maybeAutoZoom`, which then concluded
         * the driver owned the zoom and stopped correcting for the rest of the
         * journey. A null zoom leaves [CameraTarget.zoom] null, and
         * `applyCamera` re-supplies the map's current zoom unchanged.
         */
        zoom: Double?,
    ): CameraTarget = CameraTarget(
        position = position,
        // A recenter clears a manual rotation, so it must not honour one.
        bearing = bearingFor(cam.copy(manualBearing = null), travelBearing),
        tilt = tiltFor(cam.copy(manualBearing = null), phase),
        zoom = zoom,
    )

    /**
     * Which way up, for a given state.
     *
     * A manual rotation wins over the preference, and NORTH_UP is used outside
     * navigation whatever the preference says: heading-up while parked means
     * the map spins with GPS heading noise, which at a standstill is close to
     * random. Every consumer navigator does this.
     */
    fun bearingFor(cam: CameraState, travelBearing: Double): Double {
        cam.manualBearing?.let { return it }
        return when (cam.orientation) {
            MapOrientation.NORTH_UP -> 0.0
            MapOrientation.HEADING_UP -> travelBearing
        }
    }

    /**
     * Tilt, for a given state.
     *
     * Flat unless navigating AND heading-up AND the driver asked for it. The
     * heading-up condition is explained on [MapPerspective.TILTED]; the
     * navigating condition is because a tilted map is harder to read as a map,
     * and outside navigation reading it as a map is the entire task.
     */
    fun tiltFor(cam: CameraState, phase: Phase): Double {
        if (phase != Phase.NAVIGATING) return 0.0
        if (cam.perspective != MapPerspective.TILTED) return 0.0
        if (cam.manualBearing != null) return 0.0
        if (cam.orientation != MapOrientation.HEADING_UP) return 0.0
        return TILT_DEG
    }

    /**
     * Whether the extruded building layer belongs in the style (V7 3D).
     *
     * Defined on the driver's stated PREFERENCE — 3D chosen, and not on foot —
     * and deliberately NOT on the transient pitch.
     *
     * That distinction is the whole bug this version fixes. The first
     * implementation asked "is the map tilted right now", which reads as the
     * same question and is not: [tiltFor] requires `phase == NAVIGATING`, and
     * the style is built at LAUNCH, while the driver is in EXPLORE. So the
     * answer at style-apply time was always false, the layer was never
     * emitted, and the map pitched to 60 degrees with no volumes in it — the
     * exact half-built state this stage exists to close. The style is then
     * only rebuilt on a settings or theme change, so starting navigation did
     * not repair it either. Measured on the emulator: `pitch=60.0` in the
     * app's own telemetry, and a flat orthographic render.
     *
     * The camera and the style therefore key off DIFFERENT things, on purpose:
     * the camera asks "should this frame be tilted" (phase-dependent, and it
     * is), and the style asks "has this driver asked for 3D" (a preference,
     * and it survives the whole session). A layer present on a flat map is
     * harmless — a fill-extrusion at pitch 0 renders its footprint, which is
     * what the 2D `buildings` fill already draws — while a missing layer on a
     * pitched map is a broken feature.
     *
     * On foot it is always false: walking's flat pitch is a CONTRACT
     * (`WalkCameraPolicy.WALK_TILT_DEG`, pinned by three tests), not an
     * accident of the car policy, and 3D must not be the thing that quietly
     * ends it.
     */
    fun extrudesBuildings(cam: CameraState, onFoot: Boolean): Boolean =
        !onFoot && cam.perspective == MapPerspective.TILTED

    /**
     * The tilt a ZOOM-ONLY camera move must carry (V7 3D fix).
     *
     * A zoom button moves the map about where the driver is looking, so it
     * re-supplies position, bearing and tilt from whatever policy owns the
     * camera. On the car that is [tiltFor]. On foot it is NOT: the walking
     * camera's tilt is always flat, and taking the car's value here wrote a
     * 60-degree pitch onto a walking map — the HUD zoom buttons are shared,
     * and nothing gated them on `onFoot`.
     *
     * Returns the tilt to use, so the decision is testable as data rather than
     * living in an Android callback.
     */
    fun tiltForZoomStep(cam: CameraState, phase: Phase, onFoot: Boolean): Double =
        if (onFoot) 0.0 else tiltFor(cam, phase)

    /**
     * What a pan gesture means.
     *
     * The driver moved the map, so the camera stops being about the vehicle.
     * From [CameraMode.OVERVIEW] this also lands in [CameraMode.FREE]: panning
     * out of an overview is not a request to go back to following.
     */
    fun onPan(cam: CameraState, nowMs: Long): CameraState =
        cam.copy(mode = CameraMode.FREE, takenOverAtMs = nowMs)

    /**
     * What a rotate gesture means.
     *
     * Records the bearing and keeps following. Deliberately NOT a drop to
     * [CameraMode.FREE]: a rotate is a question about the junction ahead, and
     * answering it by also abandoning the follow camera means the driver pays
     * for a look with a recenter tap they did not ask to owe.
     */
    fun onRotate(cam: CameraState, bearingDeg: Double, nowMs: Long): CameraState =
        cam.copy(manualBearing = bearingDeg, takenOverAtMs = nowMs)

    /** What the recenter control means. */
    fun onRecenter(cam: CameraState): CameraState =
        cam.copy(mode = CameraMode.FOLLOW, manualBearing = null, takenOverAtMs = null)

    /**
     * How long a hand-taken camera is honoured during navigation.
     *
     * Ten seconds: long enough to look at the junction after next and read a
     * street name off it, short enough that a camera taken by ACCIDENT heals
     * itself before the driver needs it back.
     */
    const val AUTO_RESUME_MS = 10_000L

    /**
     * Give the camera back to the vehicle, or null to leave it alone.
     *
     * ## The defect
     *
     * [CameraMode.FREE] had no exit but the recenter button. On a drive on
     * 2026-09-13 the map froze at 18:07 and was still showing the same
     * hundred metres of Rawdat Al Khail at 18:11:28 — four minutes, over a
     * kilometre of driving — while the trip bar counted 2.6 km down to 1.6 km
     * and the voice kept calling maneuvers. Two screenshots four minutes apart
     * are pixel-identical apart from the chrome. It recovered at 18:11:34,
     * when the driver noticed and tapped recenter.
     *
     * Nothing about that needed a fault: [onPan] fires on any move gesture the
     * detector recognises, so one brush of a phone in a windscreen mount is
     * enough. The cost of the accident was the entire rest of the journey,
     * and it was SILENT — a frozen map and a moving map look the same for the
     * first few seconds, and after that the driver is reading geography they
     * have already driven past.
     *
     * ## Why this is safe to do to a deliberate gesture too
     *
     * Deliberately panning away during active guidance is a glance, not a
     * decision — the driver wants the road ahead back, and every navigator in
     * the category resumes on its own. Outside [Phase.NAVIGATING] it does
     * NOT resume, because there the map is the task and moving it is the
     * whole point of the screen.
     *
     * [CameraMode.OVERVIEW] is excluded for the same reason: it is a
     * deliberate "show me the whole route" with its own control to leave by,
     * not a camera that got taken by accident.
     */
    fun autoResume(cam: CameraState, phase: Phase, nowMs: Long): CameraState? {
        if (phase != Phase.NAVIGATING) return null
        if (cam.mode == CameraMode.OVERVIEW) return null
        if (cam.mode == CameraMode.FOLLOW && cam.manualBearing == null) return null
        val since = cam.takenOverAtMs ?: return null
        if (nowMs - since < AUTO_RESUME_MS) return null
        return onRecenter(cam)
    }

    /** Cycle the orientation preference. Clears a manual rotation. */
    fun toggleOrientation(cam: CameraState): CameraState = cam.copy(
        orientation = if (cam.orientation == MapOrientation.NORTH_UP) {
            MapOrientation.HEADING_UP
        } else {
            MapOrientation.NORTH_UP
        },
        manualBearing = null,
    )

    /** Cycle 2D/3D. */
    fun togglePerspective(cam: CameraState): CameraState = cam.copy(
        perspective = if (cam.perspective == MapPerspective.FLAT) {
            MapPerspective.TILTED
        } else {
            MapPerspective.FLAT
        },
    )

    /**
     * Enter or leave route overview.
     *
     * Leaving returns to FOLLOW rather than FREE, because the driver who taps
     * "overview" then taps it again is asking for their navigation camera back.
     */
    fun toggleOverview(cam: CameraState): CameraState = cam.copy(
        mode = if (cam.mode == CameraMode.OVERVIEW) CameraMode.FOLLOW else CameraMode.OVERVIEW,
        manualBearing = if (cam.mode == CameraMode.OVERVIEW) null else cam.manualBearing,
    )

    // -----------------------------------------------------------------------
    // V4: bearing continuity
    // -----------------------------------------------------------------------

    /**
     * The signed shortest rotation from [from] to [to], in (-180, 180].
     *
     * §8 of the V4 brief names the defect this exists to prevent:
     *
     * > Avoid `heading 359° → 0°` rotating the long way around.
     *
     * It was not hypothetical. Before V4 the follow camera assigned
     * `travelBearing` straight onto the MapLibre camera, and every crossing of
     * north handed MapLibre a 359-degree difference to interpolate. Driving
     * north on Al Corniche was enough to trigger it.
     *
     * This is the entire fix, and everything else about bearing continuity is
     * arithmetic arranged around it.
     */
    fun shortestAngle(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Wrap a bearing into `[0, 360)`. */
    fun norm360(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /** Interpolate a bearing the short way round. `t` is clamped to `0..1`. */
    fun lerpBearing(from: Double, to: Double, t: Double): Double =
        norm360(from + shortestAngle(from, to) * t.coerceIn(0.0, 1.0))

    /**
     * Advance a smoothed bearing one frame toward a measured one.
     *
     * GPS heading is close to random below walking pace and jitters by several
     * degrees at speed. Feeding it to the camera unfiltered is §8's "GPS noise
     * should not make the map shake", and it did: at a standstill in
     * heading-up the map used to rotate continuously in response to a heading
     * the receiver was guessing.
     *
     * Two properties worth stating because both were wrong by omission before:
     *
     * * **Frame-rate independent.** The blend factor is derived from the actual
     *   elapsed time, so a 120 Hz panel and a 60 Hz panel settle at the same
     *   real-world rate. A fixed per-frame fraction would have smoothed twice
     *   as hard on the S24 as on a cheaper phone, baking one handset's feel
     *   into the product.
     * * **Short way round**, via [lerpBearing].
     *
     * @param current the smoothed bearing being carried between frames.
     * @param measured what the receiver (or the route geometry) reports.
     * @param dtSeconds time since the previous frame.
     * @param stationary true when the vehicle is not moving, in which case the
     *   measurement is not trusted at all and the bearing is HELD. A parked car
     *   has no heading, and inventing one is worse than keeping the last one
     *   the driver saw.
     */
    fun smoothBearing(
        current: Double,
        measured: Double,
        dtSeconds: Double,
        stationary: Boolean,
    ): Double {
        if (stationary) return current
        val k = 1.0 - Math.exp(-BEARING_SMOOTH_PER_S * dtSeconds.coerceIn(0.0, 0.5))
        return lerpBearing(current, measured, k)
    }

    /** How much of the bearing gap is closed per second. See [smoothBearing]. */
    const val BEARING_SMOOTH_PER_S = 4.5

    // -----------------------------------------------------------------------
    // V4: look-ahead and auto-zoom
    // -----------------------------------------------------------------------

    /**
     * Where the vehicle should sit on screen, as a fraction from the top.
     *
     * Vector centred it. `v4-evidence/vector/before-04-navigating.png` is the
     * result: the vehicle is exactly halfway down a 2340 px display, so half
     * the screen is spent on road the driver has already driven past. Both
     * reference products place it at about two thirds down and give the road
     * ahead the upper two thirds — §10's "look-ahead", which is not a feeling
     * but a number.
     *
     * Only while navigating. In EXPLORE the vehicle is one thing on a map the
     * driver is reading, not the origin of a journey, and pushing it low would
     * just waste the top of the screen.
     */
    fun lookAheadFraction(phase: Phase, cam: CameraState): Double = when {
        phase != Phase.NAVIGATING -> 0.5
        cam.mode != CameraMode.FOLLOW -> 0.5
        else -> VectorTokens.Size.LOOK_AHEAD_FRACTION
    }

    /**
     * The zoom bands, as (exclusive upper speed bound in km/h, zoom).
     *
     * §10 asks the camera to "communicate where you are going"; Waze exposes
     * this as an `Auto zoom` preference that ships ON
     * (`v4-evidence/waze/12-map-display.png`). Vector had a single hardcoded
     * [NAV_ZOOM] of 16.5 for every situation — a reasonable city zoom, and much
     * too close at 120 km/h, where it shows about eight seconds of road.
     *
     * Chosen by **how far ahead the driver needs to see**, which is a time
     * rather than a distance: roughly fifteen seconds of travel. A zoom step
     * halves the ground covered, so each band is about a doubling of speed.
     *
     * 16.5 is deliberately the urban band, so auto-zoom is an addition for the
     * commonest case rather than a change of behaviour.
     */
    private val ZOOM_BANDS: List<Pair<Int, Double>> = listOf(
        25 to 18.0,                 // town, junctions close together
        55 to NAV_ZOOM,             // urban arterial
        85 to 16.8,                 // ring road
        115 to 16.2,                // motorway
        Int.MAX_VALUE to 15.6,      // fast motorway
    )

    // The whole ladder moved up by exactly 1.0 when [NAV_ZOOM] did, keeping
    // every gap and every band boundary where it was. That is deliberate: the
    // rung spacing encodes "about fifteen seconds of travel", and the thing
    // that pays for the extra step is the pitch change described on
    // [NAV_ZOOM] — which applies at every speed, not just at the urban band.
    // Shifting one rung and not the others would put the compensation and the
    // cost in different places.
    //
    // The caveat worth knowing: a driver who has set [MapPerspective.FLAT] gets
    // the zoom without the pitch that pays for it, and so a closer map than
    // before at every speed. Fixing that properly means making the zoom depend
    // on the perspective, which is a policy change this phase deliberately does
    // not make — see [TILT_DEG]. It is recorded here rather than discovered.

    /**
     * How far past a boundary the speed must go before the band changes.
     *
     * **This is hysteresis, and a magnitude threshold on the ZOOM cannot
     * replace it** — which is the mistake the first version of this code made.
     * That version compared the old and new zoom and moved the camera when
     * they differed by half a level; adjacent bands differ by 0.5–0.7, so a
     * driver holding 54–56 km/h crossed the boundary repeatedly and had the
     * map breathing in and out indefinitely. Raising the threshold above the
     * largest band gap would instead have blocked every band change there is.
     *
     * The oscillation is in the SPEED, so the damping has to be in the speed.
     * 8 km/h is comfortably wider than GPS speed noise and narrower than any
     * band, so every band is still reachable.
     */
    const val BAND_HYSTERESIS_KMH = 8

    /**
     * How far the zoom may drift from what Vector asserted before it is the
     * driver's.
     *
     * Wider than any band gap (0.5–0.7) so a legitimate band change is not
     * mistaken for a pinch, and narrow enough that a deliberate pinch is.
     * Unchanged from the value both zoom policies used before V7 Stage 3 — the
     * defect there was the BASELINE, not this number. See
     * [CameraGate.isDriverZoom].
     */
    const val DRIVER_ZOOM_TOLERANCE = 0.8

    /** Zoom for a band index. */
    fun zoomForBand(band: Int): Double =
        ZOOM_BANDS[band.coerceIn(0, ZOOM_BANDS.lastIndex)].second

    /** How many bands there are, so a caller can assert over all of them. */
    val zoomBandCount: Int get() = ZOOM_BANDS.size

    /** The band a speed falls in, ignoring hysteresis. */
    fun rawBand(speedKmh: Int): Int =
        ZOOM_BANDS.indexOfFirst { speedKmh < it.first }.let {
            if (it < 0) ZOOM_BANDS.lastIndex else it
        }

    /**
     * The band to use, given the one in use.
     *
     * @param current the band currently applied, or -1 when none is.
     * @return the band to apply. Equal to [current] when nothing should change,
     *   which is the signal for the caller to leave the camera alone.
     *
     * Asymmetric on purpose: speeding up has to clear the boundary above,
     * slowing down has to clear the boundary below. Requiring both to clear
     * the same point is what produces the flapping.
     */
    fun autoZoomBand(speedKmh: Int?, current: Int): Int {
        if (speedKmh == null) return current
        if (current < 0 || current > ZOOM_BANDS.lastIndex) return rawBand(speedKmh)
        val want = rawBand(speedKmh)
        if (want == current) return current
        return if (want > current) {
            // Speeding up: the boundary at the TOP of the current band.
            if (speedKmh >= ZOOM_BANDS[current].first + BAND_HYSTERESIS_KMH) want else current
        } else {
            // Slowing down: the boundary at the top of the band being entered.
            if (speedKmh < ZOOM_BANDS[want].first - BAND_HYSTERESIS_KMH) want else current
        }
    }

    /**
     * Zoom for the current speed, or null when the speed is unknown.
     *
     * Null means "do not assert a zoom" — the same contract
     * [CameraTarget.zoom] already has. A missing GPS speed must not silently
     * zoom the map to a default.
     */
    fun autoZoom(speedKmh: Int?): Double? =
        speedKmh?.let { zoomForBand(rawBand(it)) }

    /**
     * Zoom for a discrete transition, honouring the auto-zoom preference.
     *
     * Separate from [autoZoom] so the *policy* (should we?) and the *value*
     * (what is it?) are testable apart from one another, and so that the frame
     * loop can never reach the policy — zoom is still asserted only at
     * transitions, which is the V3 invariant that made pinching out during
     * navigation possible and must not be undone here.
     */
    fun transitionZoom(
        phase: Phase,
        speedKmh: Int?,
        autoZoomEnabled: Boolean,
    ): Double = when {
        phase != Phase.NAVIGATING -> EXPLORE_ZOOM
        !autoZoomEnabled -> NAV_ZOOM
        else -> autoZoom(speedKmh) ?: NAV_ZOOM
    }
}
