package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.ManeuverAnnouncer
import dev.vector.geo.RouteTracker
import kotlin.math.roundToInt

/**
 * The navigation loop's decision-making, lifted out of the Activity.
 *
 * This logic decides when to reroute, when to speak, when the camera follows,
 * and what the HUD says — and while it lived inside `MainActivity` alongside
 * MapLibre and FusedLocation it was untestable, because exercising it required
 * an Android device. That is the worst place for the riskiest code in the app:
 * a reroute that fires every second would hammer the backend, and a cooldown
 * that never expires would mean the app silently stops rerouting at all.
 *
 * Deliberately pure: it takes a fix and the current [UiState] and returns the
 * next [UiState] plus a list of [Action]s for the Activity to perform. No
 * Android types, no I/O, no MapLibre. The Activity becomes a thin adapter.
 */
class NavSession(
    private val tracker: RouteTracker,
    private val announcer: ManeuverAnnouncer,
    private val rerouteCooldownMs: Long = 8_000L,
    private val gate: dev.vector.geo.FixGate = dev.vector.geo.FixGate(),
    /**
     * Over-limit alerts. Constructed with the SAME tolerance the visual
     * indicator uses ([SPEED_TOLERANCE_KMH]) rather than its own copy of the
     * number: a driver hearing "speed limit 80" while the speedometer is still
     * showing a neutral colour, or the reverse, would be two features
     * disagreeing about the same fact.
     */
    private val speedAlert: dev.vector.geo.SpeedAlert =
        dev.vector.geo.SpeedAlert(toleranceKmh = SPEED_TOLERANCE_KMH),
) {
    /** Side effects the Activity owns. Returned rather than performed. */
    sealed interface Action {
        /**
         * Move the map camera.
         *
         * Carries a whole [CameraTarget] rather than a position and a bearing,
         * because the third thing a camera command has to be able to say is
         * "leave the zoom alone" — and it could not. The Activity used to
         * compute bearing, tilt and zoom itself from `phase`, and its zoom rule
         * was `coerceAtLeast(16.5)` applied on every followed frame, so a
         * driver pinching out during navigation was overridden within one
         * frame. [CameraTarget.zoom] is null here; only a deliberate transition
         * (start, recenter, overview) asserts one.
         */
        data class Camera(val target: CameraTarget) : Action
        /** Draw the vehicle. */
        data class Puck(val position: LngLat, val bearing: Double) : Action
        /**
         * Say something.
         *
         * [kind] is what the sentence is — a countdown (PREPARE), the
         * maneuver itself (NOW) or an alert — and it decides what the sentence
         * may do to one already playing. Given here, by the code that knows,
         * rather than guessed later from the words. Every Speak used to flush,
         * and both the 2026-09-24 drive and the S24 run of the first fix heard
         * sentences cut mid-word — see [dev.vector.geo.SpeechArbiter].
         */
        data class Speak(
            val text: String,
            val kind: dev.vector.geo.SpeechArbiter.Kind = dev.vector.geo.SpeechArbiter.Kind.NOW,
        ) : Action
        /** Ask the backend for a new route from here. */
        data class Reroute(val from: LngLat) : Action
        /**
         * The destination has been reached; the Activity should end the journey.
         *
         * Emitted exactly once per route. Nothing detected arrival before this:
         * the phase list ran EXPLORE -> PREVIEW -> NAVIGATING and only a manual
         * tap left it, so a driver who arrived stayed in NAVIGATING — the
         * maneuver card pinned at "Arrive at destination, 0 m", the camera kept
         * following at a 45-degree navigation tilt, and probe collection kept
         * running after the journey it consented to had ended.
         */
        data object Arrived : Action
    }

    data class Result(val ui: UiState, val actions: List<Action>)

    // Nullable, NOT 0L. With a 0 sentinel the cooldown is already "active" at
    // wall-clock 0 and the FIRST deviation of a session never reroutes. This is
    // the same defect RouteTracker had with its frame clock — a zero sentinel
    // colliding with a legitimate zero value — and it is invisible in production
    // (where currentTimeMillis is huge) while being exactly what a test hits.
    private var lastRerouteAtMs: Long? = null

    /** Arrival is announced once per route, not once per frame. */
    private var arrived = false

    /**
     * A place the road network demonstrably cannot represent, or null.
     *
     * See [rerouteCouldHelp]. Cleared by moving away from it or by getting back
     * on the route, because both make the evidence stale.
     */
    private var unmatchableAt: LngLat? = null

    /** When the last fix arrived, for [GpsHealth]. Nullable, not a 0 sentinel. */
    private var lastFixAtMs: Long? = null

    /**
     * Where the current route sits across the road (V7 Stage 4).
     *
     * Held here rather than in the Activity because the puck is drawn from
     * [onFrame], and a lateral offset the Activity applied afterwards would be
     * a second opinion about a fact this class already has — the along-route
     * distance. Null means no route or no lateral claim, and both draw on the
     * centreline, which is exactly the pre-Stage-4 behaviour.
     */
    private var lanes: dev.vector.geo.RouteLanes.Plan? = null

    /**
     * The active route's signals (V7 Stage 5), in route order.
     *
     * Built ONCE per route (in the Activity, from the /navigate reply) and
     * only replaced when the route is. Null means no route or no signals, and
     * the HUD then shows nothing signal-related — exactly the pre-Stage-5
     * behaviour. Read per navigation tick via [withSignal], never per frame.
     */
    private var signalProfile: dev.vector.geo.signal.SignalProfile? = null

    /**
     * The active route's cameras (V7.3), in route order.
     *
     * Built ONCE per route (in the Activity, from the /navigate reply) and
     * only replaced when the route is. Null means no route or no cameras, and
     * the HUD then shows nothing camera-related — exactly the pre-V7.3
     * behaviour. Read per navigation tick via [withCamera], never per frame.
     *
     * The set of already-announced camera ids lives HERE, keyed to this
     * profile: a camera is spoken once per route, and replacing the profile
     * (a reroute) forgets what the old route announced.
     */
    private var cameraProfile: dev.vector.geo.camera.CameraProfile? = null
    private val announcedCameras = HashSet<String>()

    /**
     * Whether the vehicle was at a standstill on the last fix — the input
     * that widens the next arrival window (stop-and-go). Updated per fix,
     * never per frame.
     */
    private var stoppedRecent = false

    /**
     * The timing model for a signal, or [None][dev.vector.geo.signal.TimingModel.None].
     *
     * ## The acquisition seam (V7 Stage 5)
     *
     * This is deliberately the ONLY place a phase claim can enter the loop.
     * It returns None unconditionally today, because the project possesses no
     * timing evidence for any Qatar signal — no SPaT feed, no permitted
     * observation source, zero probes big enough to model a cycle. A future
     * timing source slots in HERE and nowhere else: whatever it supplies must
     * carry its own observedAt/expiresAt (stale is then handled by the model
     * itself), and neither pruning nor UI copy needs to change. Until then
     * every prediction is UNKNOWN by construction, and the UI proves it by
     * rendering no phase text at all.
     */
    private fun timingFor(signalId: String): dev.vector.geo.signal.TimingModel =
        dev.vector.geo.signal.TimingModel.None

    /**
     * Set the active route's signal profile. Call it wherever
     * [RouteTracker.setRoute] is called, with the signals of the SAME route.
     * Null clears it — a profile that outlived its route would keep
     * announcing signals from a journey that is over.
     */
    fun setSignals(profile: dev.vector.geo.signal.SignalProfile?) {
        signalProfile = profile
    }

    /**
     * Set the active route's camera profile. Call it wherever
     * [setSignals] is called, with the cameras of the SAME route.
     *
     * Replacing the profile also forgets which cameras were announced: a new
     * route (or reroute) is a new journey for the voice dedup, and a camera
     * the driver legitimately passes again must be able to speak again.
     */
    fun setCameras(profile: dev.vector.geo.camera.CameraProfile?) {
        cameraProfile = profile
        announcedCameras.clear()
    }

    /**
     * Set the route's lateral profile. Call it wherever [RouteTracker.setRoute]
     * is called, and with the maneuvers of the SAME route.
     *
     * Null clears it. A profile that outlived its route would offset the puck
     * by the lane geometry of a journey that is over, which is the failure mode
     * worth naming: it is invisible on a route with no lane data and silently
     * wrong on one with it.
     */
    fun setLanes(plan: dev.vector.geo.RouteLanes.Plan?) {
        lanes = plan
        lastLateral = null
    }

    /**
     * The lateral offset last applied to the drawn vehicle, in metres right of
     * travel — or null when none was (no route lock, or the model declined).
     *
     * Exists for the device harness and for nothing else. The V7 Stage 3 run
     * established that the only claims worth making about rendering are the
     * ones a device measured, and a lateral model is exactly the kind of thing
     * that can be perfectly right in a unit test and invisible on a handset —
     * MapLibre renders NOTHING, silently, for several configurations this style
     * is one edit away from. A telemetry line that carries the offset lets a
     * drive say which of "the model declined" and "the renderer dropped it"
     * happened, and those have completely different fixes.
     */
    var lastLateral: Double? = null
        private set

    /**
     * When this session first looked for a position, for the patience window.
     *
     * Set on the first frame rather than in the constructor: the session is
     * built lazily and the clock it is measured against is the caller's, so
     * taking the time here keeps [GpsHealth.UNAVAILABLE] testable without a
     * clock injected into the constructor. Deliberately NOT cleared by
     * [reset] — see the comment at its only read.
     */
    private var startedAtMs: Long? = null

    /**
     * Recent fixes, for deriving speed when the receiver does not report one.
     *
     * A ring of (wall-clock ms, position). See [effectiveSpeed] for why an
     * instantaneous difference will not do.
     */
    private val recent = ArrayDeque<Pair<Long, LngLat>>()

    /**
     * Is this fix physically possible? Ask before doing anything with it.
     *
     * Separated from [onFix] rather than folded into it because the Activity
     * does three other things with a fix — probe collection, the speed-limit
     * lookup, the traffic refresh — and all of them happen BEFORE the
     * navigation loop sees it. A 300 m reflected fix must not reach any of
     * them: contributing it would put a fabricated position into the
     * learned-speed layer, which is the one place in this system that is
     * supposed to only ever see measurements.
     *
     * @return false when the fix should be discarded entirely.
     */
    fun acceptFix(fix: LngLat, nowMs: Long): Boolean = gate.accept(fix, nowMs).accept

    /** Fixes discarded as impossible since the last [reset]. For logging. */
    val droppedFixes: Int get() = gate.droppedCount

    /** Reset between JOURNEYS so a new trip does not inherit a cooldown. */
    fun reset() {
        lastRerouteAtMs = null
        arrived = false
        unmatchableAt = null
        announcer.reset()
        speedAlert.reset()
        stoppedRecent = false
    }

    /**
     * Record how far the road network was from where a route was planned.
     *
     * Called with the ORIGIN snap of every route the app starts driving,
     * including each rerouted one. That is the app's only measurement of
     * whether the network can represent where the *driver* is, and it is the
     * input [rerouteCouldHelp] needs.
     *
     * @param origin where the request was made from, or null if not known yet.
     */
    fun noteRoutePlannedFrom(origin: LngLat?, originSnapM: Double?) {
        unmatchableAt = if (origin != null && originSnapM != null &&
                            originSnapM >= tracker.offRouteM) origin else null
    }

    /**
     * A reroute landed and the route was replaced. NOT a new journey.
     *
     * The announcer must forget where it was, or the first maneuver of the new
     * route is treated as already announced and the driver is told nothing.
     *
     * The reroute cooldown deliberately SURVIVES. `reset()` used to be called
     * here, which cleared it — so a driver the router could not match (bad GPS,
     * a road the graph does not have) would deviate, reroute, deviate, reroute
     * at the GPS rate, exactly the storm the cooldown exists to prevent and
     * exactly when it is most needed. The cost of keeping it is that a genuine
     * second deviation within the cooldown waits a few seconds; the cost of
     * clearing it is an unbounded request loop that also drains the battery.
     */
    fun onRouteReplaced() {
        arrived = false
        announcer.reset()
    }

    /**
     * Where to ask "what road is this, and what is its limit?" for this fix.
     *
     * ## The defect this exists because of
     *
     * `/speed` answers for the drivable way NEAREST the point it is given, and
     * the client used to give it the raw GPS fix. On a Doha ring road that is
     * the wrong question: D Ring Road's 80 km/h carriageway runs with Al Amir
     * Street — a `secondary` service road, signed 60 on some ways and 40 on
     * others — 12 to 33 m beside it (measured from the bake input,
     * `qatar.geojson`), and a fix a few metres toward the kerb lands nearer
     * the service road. On the 2026-09-24 drive
     * (`Screen_Recording_20260924_115921.mp4`) the route never left D Ring
     * Road between 2:20 and 2:52, yet the road readout flipped to "Al Amir
     * Street" and the limit disc to 60 at 2:22, and at 2:23 — 70 km/h on the
     * disc — the voice said "Speed limit 60" about a road the driver was not
     * on. At 3:26–3:36 the same readout hopped Al Amir Street (40) → Al Mirqab
     * Al Jadeed Street (50) → Al Amir Street (40) while the car stayed on the
     * route, and every change re-armed [dev.vector.geo.SpeedAlert] and was
     * spoken.
     *
     * ## The answer
     *
     * While the tracker holds the vehicle on the route, ask about the point it
     * was matched to. The route's polyline IS the geometry of the ways the
     * router chose, so the nearest way to a point on it is the way the driver
     * is being guided along, at a distance of about zero — a parallel road ten
     * metres off can no longer win. It is the same move [refreshSpeedAhead]
     * already makes for the road after the next maneuver, which asks at a
     * point ON the route; the disc was the one reader still asking at the raw
     * fix.
     *
     * The raw fix is kept whenever the route is not a claim worth making: no
     * route, off-route, or a lock held on a fix further out than the snap
     * radius ([ROAD_PROBE_MAX_OFFSET_M]), where the tracker itself declines to
     * trust the projection. Being on a road the route does not follow is then
     * a real possibility, and the nearest road is the honest answer.
     */
    fun roadProbePoint(fix: LngLat): LngLat {
        val s = tracker.state as? RouteTracker.State.OnRoute ?: return fix
        return if (s.offsetM <= ROAD_PROBE_MAX_OFFSET_M) s.position else fix
    }

    /**
     * A GPS fix arrived.
     *
     * [rawBearing] is the receiver's own bearing, used only when we are not on
     * a route — on-route the bearing comes from the route geometry, which is
     * far steadier than a GPS heading at low speed.
     */
    fun onFix(
        ui: UiState,
        fix: LngLat,
        speedMs: Double?,
        rawBearing: Double,
        nowNanos: Long,
        nowMs: Long,
        /**
         * The receiver's claimed accuracy. Null when it did not report one,
         * which is not the same as a good fix — see [GpsHealth].
         */
        accuracyM: Double? = null,
    ): Result {
        val actions = mutableListOf<Action>()
        lastFixAtMs = nowMs
        val speed = effectiveSpeed(speedMs, fix, nowMs)
        // Stop-and-go is per navigation tick: a standstill on THIS fix widens
        // the next arrival window. An unknown speed is not evidence of a stop,
        // so only a reported-or-derived speed below walking pace counts.
        stoppedRecent = speed?.let { it < MOVING_MS } ?: stoppedRecent
        val health = if ((accuracyM ?: 0.0) > GPS_WEAK_ACCURACY_M) GpsHealth.WEAK else GpsHealth.GOOD
        // A fix has landed, so whatever the app was saying about not having one
        // is over — including UNAVAILABLE, which must not be sticky. The
        // handset that reported this recovered the moment the provider was
        // unstuck, and the banner has to come down with it.
        val base = ui.copy(gps = health, gpsAccuracyM = accuracyM)
        // The heading goes to the tracker only while the vehicle is MOVING.
        //
        // It is used to choose between two passes of the same street on a first
        // lock (see RouteIndex.project), and a stationary receiver's heading is
        // close to random — handing it over would make the disambiguation worse
        // than the nearest-segment rule it replaces. The same MOVING_MS floor
        // the reroute test uses, for the same reason.
        val heading = if ((speed ?: 0.0) >= MOVING_MS) rawBearing else null
        return when (val s = tracker.onFix(fix, speed, nowNanos, heading)) {
            is RouteTracker.State.OffRoute -> {
                actions.add(Action.Puck(fix, rawBearing))
                MapCamera.follow(base.cam, base.phase, fix, rawBearing)
                    ?.let { actions.add(Action.Camera(it)) }
                // Off the route there is no next camera to be approaching, so
                // the alert is cleared rather than held: a frozen distance on
                // the HUD while the vehicle is off-route is a claim about a
                // route the driver is no longer on. The profile itself stays —
                // the driver may still be put back on this route.
                var next = base.copy(offRoute = true, cameraAhead = null)
                val since = lastRerouteAtMs?.let { nowMs - it }
                if (base.phase == Phase.NAVIGATING &&
                    (since == null || since >= rerouteCooldownMs) &&
                    rerouteCouldHelp(fix, speed)) {
                    lastRerouteAtMs = nowMs
                    next = next.copy(rerouting = true)
                    actions.add(Action.Reroute(fix))
                }
                Result(next, actions)
            }

            is RouteTracker.State.OnRoute -> {
                // Derive the HUD here as well as in onFrame.
                //
                // `currentManeuver` used to be set ONLY by onFrame, which
                // returns nothing unless the tracker holds a lock — and the
                // lock drops after `reckonMaxS` (3 s) without a fix. So a
                // stationary phone whose fixes arrive slower than that showed
                // NO MANEUVER CARD AT ALL: the driver taps Start and the most
                // important element on the screen is simply absent. Seen on an
                // S24 Ultra at a standstill.
                //
                // Freezing the PUCK when the fixes dry up is correct and stays;
                // withholding the instruction is not. What the driver is told
                // and how smoothly the vehicle is drawn are different concerns.
                //
                // `unmatchableAt` is cleared here rather than only on movement:
                // being back on the route is proof that wherever the driver was
                // standing, they are not standing there now.
                unmatchableAt = null
                // A fix is the ~1 Hz navigation tick: the one place the signal
                // prediction is evaluated. Not onFrame — display frames are for
                // drawing, and a phase estimate that changed 100 times a second
                // would be noise the model has no data to justify.
                val guided = withGuidance(base.copy(offRoute = false), s.alongM)
                val sig = withSignal(guided, s.alongM, speed, nowMs)
                val cam = withCamera(sig, s.alongM)
                // The camera voice line, emitted at most once per camera per
                // route by [withCamera]: one Speak per crossing of the
                // warning horizon, never per frame.
                cam.speak?.let { actions.add(Action.Speak(it, dev.vector.geo.SpeechArbiter.Kind.ALERT)) }
                Result(cam.ui, actions)
            }

            RouteTracker.State.Idle -> {
                actions.add(Action.Puck(fix, rawBearing))
                // The "not during PREVIEW" rule now lives in MapCamera.follow,
                // with the rest of the camera policy, rather than being half
                // here and half in the Activity.
                MapCamera.follow(base.cam, base.phase, fix, rawBearing)
                    ?.let { actions.add(Action.Camera(it)) }
                // null in, null out. Coercing a missing speed to 0.0 here is
                // what put a fabricated "0 km/h" on the HUD; see UiState.speedKmh.
                // A DERIVED speed is shown, though — it is a measurement, just
                // not one the chipset made.
                // No route held: no camera is ahead of anything.
                Result(base.copy(speedKmh = speed?.let { (it * 3.6).roundToInt() },
                                 cameraAhead = null), actions)
            }
        }
    }

    /**
     * The next signal and its prediction, from an along-route distance.
     *
     * Evaluated ONLY on the ~1 Hz navigation tick ([onFix]). It is three
     * cheap pure calls — a binary search over a few dozen entries, an arrival
     * window, and the phase function — so the tick cost is microseconds
     * ([SignalCadencePerfTest] pins it), and it is deliberately NOT called
     * from [onFrame], which would make display frames signal-aware.
     */
    private fun withSignal(ui: UiState, alongM: Double, effectiveMs: Double?, nowMs: Long): UiState {
        val profile = signalProfile
        val next = profile?.next(alongM) ?: return ui.copy(signalAhead = null)
        val remainingM = (next.approach.alongM - alongM).coerceAtLeast(0.0)
        // The same planned average the HUD ETA derives from, so the signal
        // estimate and the ETA can never disagree about the journey's pace.
        val avgMs = if (ui.routeDurationS > 0) ui.routeDistanceM / ui.routeDurationS else 13.0
        val window = dev.vector.geo.signal.ArrivalWindow.of(
            remainingM, avgMs, effectiveMs, stoppedRecent,
        )
        val prediction = dev.vector.geo.signal.SignalTiming.predict(
            next.ref.id,
            timingFor(next.ref.id),
            nowMs + (window.etaS * 1000.0).toLong(),
            window.uncertaintyS,
            nowMs,
        )
        return ui.copy(
            signalAhead = SignalAhead(next.ref.id, remainingM, window, prediction),
        )
    }

    /**
     * The next camera and whether to speak about it, from an along-route
     * distance.
     *
     * Evaluated ONLY on the ~1 Hz navigation tick ([onFix]), the same rule as
     * signals: two cheap pure calls (a binary search and a distance), so the
     * tick cost is microseconds ([CameraCadencePerfTest] pins it), and it is
     * deliberately NOT called from [onFrame].
     *
     * ## One window for the alert, one for the map
     *
     * A camera enters the ALERT only inside [CameraProfile.WARN_AHEAD_M] —
     * both the banner and the voice, the same constant, so the two cannot
     * disagree about when a camera is "ahead". The map is different on
     * purpose: the pill carries the LOCATION fact and is drawn from further
     * out (see Callouts), because a location on a map is not a claim about
     * the driver's next fifteen seconds.
     *
     * The banner used to be unbounded while only the voice was windowed, which
     * meant "Speed camera ahead · 4.9 km" held the HUD's second line — the
     * line that otherwise shows the road name — for the whole approach to the
     * next camera. The window is what keeps a camera alert SECONDARY to
     * maneuvering, which is the priority doctrine the turn card already
     * enforces by owning the lines above it.
     *
     * ## Navigating only, like every other warning
     *
     * The alert requires [Phase.NAVIGATING]. A route shown but not started is
     * a proposal — the driver is still looking at the alternatives — and the
     * tracker reports `OnRoute` for it because the route begins at the vehicle.
     * Without this gate the camera voice fired during PREVIEW on a real Doha
     * run (six announcements before "Start" was ever pressed), and the banner
     * had no phase of its own to appear in because the trip bar is
     * NAVIGATING-only. Maneuvers, the over-limit alert and arrival are all
     * gated the same way; this is that rule applied to the one warning that
     * was missing it.
     *
     * ## The voice
     *
     * Spoken at most once per camera per route, when it first enters the
     * window ([announcedCameras] guards the repeat), and only when the driver
     * has asked to hear ALERTS — the same gate the speed-limit alert uses,
     * because a camera warning is an alert about the road and not an
     * instruction to maneuver. The sentence is [CameraText.line], the very
     * same one the banner shows; nothing here or in the text can claim the
     * camera is active.
     */
    private fun withCamera(ui: UiState, alongM: Double): CameraTick {
        val profile = cameraProfile
        if (ui.phase != Phase.NAVIGATING) {
            return CameraTick(ui.copy(cameraAhead = null), null)
        }
        val next = profile?.next(alongM) ?: return CameraTick(ui.copy(cameraAhead = null), null)
        val remainingM = (next.approach.alongM - alongM).coerceAtLeast(0.0)
        val within = remainingM <= dev.vector.geo.camera.CameraProfile.WARN_AHEAD_M
        if (!within) {
            // On the route but not yet in the alert window: the map may show
            // it, the HUD and the voice say nothing.
            return CameraTick(ui.copy(cameraAhead = null), null)
        }
        val line = dev.vector.geo.camera.CameraText.line(next.ref.type)
            ?: return CameraTick(ui.copy(cameraAhead = null), null)
        val ahead = CameraAhead(
            cameraId = next.ref.id,
            type = next.ref.type,
            line = line,
            distanceM = remainingM,
            maxspeedTag = next.ref.maxspeedTag,
        )
        // `.add` is what consumes the one-shot, so an ALERTS-off journey
        // re-arms the camera when the driver turns voice on and it is still
        // inside the window. The alternative — marking it announced while
        // muted — silently loses the announcement for the rest of the route.
        val speak = if (ui.voice.speaksAlerts && announcedCameras.add(next.ref.id)) {
            line
        } else null
        return CameraTick(ui.copy(cameraAhead = ahead), speak)
    }

    private data class CameraTick(val ui: UiState, val speak: String?)

    /**
     * The vehicle's speed, from the receiver when it reports one and from
     * consecutive fixes when it does not.
     *
     * ## Why this is not optional
     *
     * `Location.hasSpeed()` is false for every network-provider fix, false on
     * some chipsets below walking pace, and false for a fix synthesised by a
     * mock provider that does not set the field. Vector's behaviour when it is
     * false was, in two places, silently wrong:
     *
     *  * [rerouteCouldHelp] read `speedMs ?: 0.0` and compared it against
     *    [MOVING_MS]. A receiver that never reports speed therefore looked
     *    permanently stationary and **Vector would never reroute at all** — the
     *    single most user-visible navigation behaviour, disabled by a missing
     *    optional field.
     *  * [RouteTracker] retains its previous speed on a null, which is right,
     *    but its previous speed starts at zero. With no speed ever reported the
     *    dead-reckoning step advances by nothing, so the puck moves only by the
     *    25% correction each fix applies — it converges on the truth with a lag
     *    of several fixes, which at 60 km/h is tens of metres of the vehicle
     *    being drawn behind where it is.
     *
     * ## Why a window and not the last two fixes
     *
     * Two consecutive fixes 3 m apart could be 3 m of travel or 3 m of jitter,
     * and at a standstill in an urban canyon it is always the latter. Reading
     * that as ~3 m/s would clear [MOVING_MS] and reintroduce the reroute storm
     * the V4 work removed. Net displacement over [SPEED_WINDOW_MS] instead:
     * independent jitter averages down over the window, while real travel
     * accumulates. Correlated drift does not average down, which is why this is
     * a floor on the storm rather than a cure — the [unmatchableAt] suppression
     * is what bounds it.
     */
    private fun effectiveSpeed(reported: Double?, fix: LngLat, nowMs: Long): Double? {
        recent.addLast(nowMs to fix)
        while (recent.size > 1 && nowMs - recent.first().first > SPEED_WINDOW_MS) {
            recent.removeFirst()
        }
        if (reported != null && reported >= 0) return reported
        val oldest = recent.first()
        val dt = (nowMs - oldest.first) / 1000.0
        if (dt < MIN_SPEED_WINDOW_S) return null
        val d = dev.vector.geo.RouteGeometry.haversineM(
            oldest.second.lng, oldest.second.lat, fix.lng, fix.lat
        )
        return d / dt
    }

    /**
     * [GpsHealth] derived from how long ago the last fix was.
     *
     * Called from [onFrame], which is the only thing that runs when fixes are
     * NOT arriving — and therefore the only place this can be noticed. See the
     * defect described on [GpsHealth].
     */
    private fun gpsHealthAt(ui: UiState, nowMs: Long): GpsHealth {
        val last = lastFixAtMs ?: run {
            // No fix has EVER arrived. "Searching" is honest for a few seconds
            // and becomes a lie if it never stops — reported from the S24 as
            // "always showing searching for GPS". See GpsHealth.UNAVAILABLE.
            //
            // Measured from when the session started rather than from process
            // start, so ending a journey (which rebuilds UiState) does not
            // reset the patience and re-show "Searching" on a device that has
            // been failing to locate for ten minutes.
            val since = startedAtMs ?: nowMs.also { startedAtMs = it }
            return if (nowMs - since > GPS_ACQUIRE_PATIENCE_MS) GpsHealth.UNAVAILABLE
                   else GpsHealth.ACQUIRING
        }
        if (nowMs - last > GPS_STALE_MS) return GpsHealth.LOST
        // Not stale, so whatever the last fix said about its own accuracy still
        // stands — except ACQUIRING, which cannot be true once a fix has landed
        // and is reachable because ending a journey rebuilds UiState.
        return if (ui.gps == GpsHealth.ACQUIRING || ui.gps == GpsHealth.UNAVAILABLE) {
            GpsHealth.GOOD
        } else ui.gps
    }

    /**
     * The maneuver, the countdown and what is left — from an along-route
     * distance, whatever produced it.
     *
     * Shared by [onFix] and [onFrame] so the two can never disagree about what
     * the driver is being told.
     */
    private fun withGuidance(ui: UiState, alongM: Double): UiState {
        val (upcoming, distTo) = upcomingManeuver(ui.maneuvers, alongM)
        // Remaining time from remaining DISTANCE at the route's own average,
        // not from instantaneous GPS speed: at a red light the latter divides by
        // ~zero and the ETA leaps to infinity.
        val avgMs = if (ui.routeDurationS > 0) ui.routeDistanceM / ui.routeDurationS else 13.0
        val remainingM = (ui.routeDistanceM - alongM).coerceAtLeast(0.0)
        return ui.copy(
            remainingM = remainingM,
            remainingS = remainingM / avgMs.coerceAtLeast(1.0),
            currentManeuver = upcoming,
            nextManeuver = followingManeuver(ui.maneuvers, upcoming),
            distanceToManeuverM = distTo,
        )
    }

    /**
     * Would asking for a new route actually change anything?
     *
     * Reported from a real S24 Ultra: parked about 80 m from the nearest road,
     * the app said "Route updated" out loud every eight seconds, forever. The
     * loop is structural, not a timing bug — the driver is off-route, so it
     * reroutes; the router snaps the new origin to the same road 80 m away; the
     * driver is still off-route; repeat. A cooldown only sets the period of the
     * loop, it cannot end it.
     *
     * Two conditions under which a reroute cannot help, so it is not attempted:
     *
     * 1. **Stationary.** A deviation at walking pace is not a wrong turn, it is
     *    a car park. Nothing is being missed by waiting until the driver moves,
     *    and every consumer navigator behaves this way.
     * 2. **The driver is standing somewhere the road network cannot
     *    represent.** The evidence is [unmatchableAt]: the ORIGIN snap of the
     *    last route planned from here. When it exceeded
     *    [RouteTracker.offRouteM], being "off route" is the permanent state of
     *    standing in this spot, and a new route would snap to the same place
     *    and be just as far away.
     *
     * ## The defect V5 found in condition 2
     *
     * It used to read `ui.routeSnapMaxM`, the **worse of the two endpoints** —
     * which on almost every journey is the destination's. So a route to a mall
     * car park, an airport terminal or a compound gate 60 m from the nearest
     * road **switched rerouting off for the entire drive**, including a wrong
     * turn twenty kilometres earlier at 100 km/h. Measured on a real fixture:
     * `long-airport-educity` reports `snap_max_m` 131.8, and every reroute on
     * that 27 km route was suppressed.
     *
     * The endpoints of a route are not where the driver is once they are
     * driving. So the input is now the origin snap, re-measured on every
     * reroute, and it is scoped to a place rather than to a journey: move
     * further than the off-route threshold from the spot that could not be
     * matched, and Vector tries again. That keeps the S24 loop closed — nobody
     * standing still gets a request every eight seconds — while never
     * disabling the behaviour for a car that is moving.
     *
     * The driver still SEES `offRoute`; what stops is the pointless request and
     * the voice announcement that goes with it.
     */
    private fun rerouteCouldHelp(fix: LngLat, speedMs: Double?): Boolean {
        if ((speedMs ?: 0.0) < MOVING_MS) return false
        val bad = unmatchableAt ?: return true
        // Moved further from the unmatchable spot than the off-route threshold
        // itself? Then the evidence is about somewhere else and it is worth
        // asking again. Standing in the same place? Then the answer will be the
        // same as last time and the request is the loop, not the fix for it.
        return dev.vector.geo.RouteGeometry.haversineM(
            bad.lng, bad.lat, fix.lng, fix.lat
        ) > tracker.offRouteM
    }

    /**
     * A display frame. Advances the interpolated position and derives the HUD.
     * Returns the unchanged state and no actions when there is nothing to draw.
     *
     * [nowMs] is wall clock, for [GpsHealth]. It is separate from [nowNanos]
     * because the frame timestamp is a monotonic clock with an arbitrary epoch
     * and the fix timestamps are wall clock; subtracting one from the other is
     * how a "seconds since the last fix" check ends up comparing 1970 with
     * boot time. Defaulted so the existing callers and tests that only care
     * about geometry are unaffected.
     */
    fun onFrame(ui: UiState, nowNanos: Long, nowMs: Long = nowNanos / 1_000_000L): Result {
        val health = gpsHealthAt(ui, nowMs)
        val s = tracker.onFrame(nowNanos)
        if (s == null) {
            // The tracker has nothing to draw. Three different situations reach
            // here — no route, off-route, or the fixes dried up — and the last
            // of those is the one that used to be silent.
            //
            // `return Result(ui, emptyList())` was the whole body of this
            // branch, so during a GPS outage the vehicle froze while the
            // maneuver, the countdown and the ETA all stayed on screen looking
            // authoritative. The driver had no way to tell a stopped car from a
            // stopped receiver. See [GpsHealth].
            return if (health != ui.gps) Result(ui.copy(gps = health), emptyList())
                   else Result(ui, emptyList())
        }
        // The vehicle, in the carriageway the route says it is driving in.
        //
        // ## What this claims, and what it refuses to
        //
        // The puck consumes the SAME lateral fact as the ribbon and the
        // chevrons, because the alternative is worse in a specific way: a
        // ribbon on the correct side of a two-way road with the puck still on
        // the centreline draws the vehicle beside its own route, and the driver
        // has to reconcile that every second of the journey. One lateral fact,
        // three consumers, no contradiction to resolve.
        //
        // It is NOT a claim that GPS measured which lane the vehicle is in — it
        // cannot, at 1 Hz against a 3.5 m lane. It is the route's own
        // carriageway, which is a better statement about where the vehicle is
        // than the centreline of a road whose other half carries oncoming
        // traffic. `RouteLanes`' KDoc draws the distinction in full and declines
        // wherever the data does not support it.
        //
        // ## Why only here
        //
        // This is the only puck the tracker has a route lock behind. The two in
        // `onFix` are the OffRoute and Idle cases, and both must stay on the raw
        // fix: moving an unmatched position sideways by a route it is not on
        // would be fabrication rather than placement.
        //
        // The CAMERA follows the offset position too. Following the centreline
        // while drawing the vehicle beside it would put the puck permanently
        // off-centre on screen, which is the same contradiction moved from the
        // map into the viewport.
        val lateral = lanes?.offsetAt(s.alongM)
        lastLateral = lateral
        val at = if (lateral == null || lateral == 0.0) s.position
                 else dev.vector.geo.RouteGeometry.offsetPoint(s.position, s.bearing, lateral)
        val actions = mutableListOf<Action>(Action.Puck(at, s.bearing))
        MapCamera.follow(ui.cam, ui.phase, at, s.bearing)
            ?.let { actions.add(Action.Camera(it)) }

        val next = withGuidance(ui.copy(gps = health), s.alongM)
            .copy(speedKmh = (s.speedMs * 3.6).roundToInt())
        val upcoming = next.currentManeuver
        val distTo = next.distanceToManeuverM

        // Voice, filtered by the driver's mode (V4, §27).
        //
        // The announcer still runs in every mode except OFF, and its result is
        // then dropped rather than never produced. That is deliberate: `fired`
        // is per-stage-per-maneuver state, so skipping the call would leave the
        // PREPARE stage un-fired and a driver switching from BRIEF back to FULL
        // mid-maneuver would hear the far announcement late. Letting it fire
        // and discarding the sentence keeps the state machine honest.
        if (ui.phase == Phase.NAVIGATING && upcoming != null && ui.voice.speaksManeuvers) {
            val after = next.nextManeuver
            announcer.update(
                ManeuverAnnouncer.Upcoming(upcoming.index, upcoming.type, upcoming.instruction, distTo),
                s.speedMs,
                // The maneuver after the upcoming one, so the announcer can say
                // both when they are seconds apart. `nextManeuver` has been
                // derived for the "then" strip since V4 and the voice never saw
                // it — so the screen said "then turn right" while the driver
                // was told nothing about it until 45 m before the junction.
                after?.let {
                    ManeuverAnnouncer.Upcoming(
                        it.index, it.type, it.instruction,
                        distTo + (it.cumulativeM - upcoming.cumulativeM),
                    )
                },
            )?.let {
                if (it.stage != ManeuverAnnouncer.Stage.PREPARE || ui.voice.speaksPrepareStage) {
                    // The TURN and NOW stages both say the maneuver itself;
                    // only PREPARE is a countdown the maneuver makes stale.
                    actions.add(Action.Speak(
                        it.text,
                        if (it.stage == ManeuverAnnouncer.Stage.PREPARE) dev.vector.geo.SpeechArbiter.Kind.PREPARE
                        else dev.vector.geo.SpeechArbiter.Kind.NOW,
                    ))
                }
            }
        }

        // Over the posted limit, out loud.
        //
        // Gated on `speaksAlerts`, which is every voice mode except OFF —
        // `VoiceMode.ALERTS` is documented as existing "precisely for this and
        // nothing else" and until V5 nothing ever produced one. The decision is
        // in dev.vector.geo.SpeedAlert, which is where the repetition rules
        // that make or break a speed alert can be tested.
        if (ui.phase == Phase.NAVIGATING && ui.voice.speaksAlerts) {
            speedAlert.update(
                next.speedKmh, next.speedLimitKmh, next.speedLimitInferred, nowMs
            )?.let { actions.add(Action.Speak(it, dev.vector.geo.SpeechArbiter.Kind.ALERT)) }
        }

        // Arrival. Measured along the ROUTE, not as the crow flies to the
        // destination pin: a route whose last hundred metres double back past
        // the destination would otherwise report arrival while the driver still
        // has a turn to make.
        if (ui.phase == Phase.NAVIGATING && !arrived && s.remainingM <= ARRIVAL_RADIUS_M) {
            arrived = true
            actions.add(Action.Arrived)
        }
        return Result(next, actions)
    }
}
