package dev.vector.android

import dev.vector.geo.DrivePath
import dev.vector.geo.LngLat
import dev.vector.geo.ManeuverAnnouncer
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteTracker
import dev.vector.geo.SimFix
import org.json.JSONObject

/**
 * Runs the real navigation loop against a simulated drive.
 *
 * ## What this is a copy of, and why that matters
 *
 * `MainActivity` is the adapter between Android and [NavSession]: a location
 * callback, a `Choreographer` frame callback, and a coroutine that asks the
 * backend for a route. This class is the same adapter with the Android removed
 * and a clock in its place. Everything it drives — [RouteTracker],
 * [ManeuverAnnouncer], [NavSession], [MapCamera], the `UiState` transitions —
 * is the production code, unmodified.
 *
 * The three things it reproduces that a simpler harness would not, and each of
 * which turned out to matter:
 *
 *  1. **The frame loop runs between fixes.** Vector's puck, ETA, voice and
 *     arrival decision are all made in `NavSession.onFrame`, at display rate,
 *     not in `onFix`. A harness that only calls `onFix` exercises none of them,
 *     and would report a route as navigated without ever having advanced the
 *     vehicle.
 *  2. **A reroute takes time.** `MainActivity.reroute` launches a coroutine; the
 *     new route lands hundreds of milliseconds later, and fixes keep arriving
 *     against the OLD route in the meantime. That window is where a stale
 *     navigation state would live, so the harness delivers routes late
 *     ([rerouteLatencyMs]) rather than instantly.
 *  3. **The router can fail or be slow**, which is a state the app must survive
 *     rather than a case to skip.
 *
 * ## What it deliberately does not claim
 *
 * This is simulation. It proves the navigation logic responds correctly to a
 * position stream; it proves nothing about GPS hardware, MapLibre's renderer,
 * or thermal behaviour. V5 §19 requires that distinction be kept in the
 * reporting, and `scripts/simulate_drive.sh` is the on-device counterpart that
 * pushes the same traces through the real fused-location pipeline.
 */
class DriveHarness(
    private val router: Router,
    /**
     * Display rate. 120 Hz is the S24's panel and what `FrameMeter` measured on
     * the device, so the number of frames per fix here is the real one.
     */
    private val frameHz: Double = 120.0,
    /** How long the backend takes to answer a reroute. */
    private val rerouteLatencyMs: Long = 700L,
    private val tracker: RouteTracker = RouteTracker(),
    private val announcer: ManeuverAnnouncer = ManeuverAnnouncer(),
) {

    /** A route as the app would have received it, plus its maneuvers. */
    data class Plan(
        val geometry: List<LngLat>,
        val distanceM: Double,
        val durationS: Double,
        val maneuvers: List<Maneuver>,
        val snapMaxM: Double,
        val originSnapM: Double,
        val destSnapM: Double,
    ) {
        val geometryLengthM: Double get() = DrivePath.lengthM(geometry)
    }

    /**
     * Stands in for the backend.
     *
     * A function rather than a fixed reply so a scenario can decide what a
     * reroute *from a particular place* should return — which is the whole
     * content of the wrong-road and unmatchable-position cases.
     */
    fun interface Router {
        /** @return the new route, or null for a request that failed. */
        fun route(from: LngLat): Plan?
    }

    /** Everything that happened, with the trace time it happened at. */
    sealed interface Event {
        val tMs: Long

        data class WentOffRoute(override val tMs: Long, val offsetM: Double) : Event
        data class CameBackOnRoute(override val tMs: Long) : Event
        data class RerouteRequested(override val tMs: Long, val from: LngLat) : Event
        data class RerouteApplied(override val tMs: Long, val distanceM: Double) : Event
        data class RerouteFailed(override val tMs: Long) : Event
        data class Spoke(override val tMs: Long, val text: String) : Event
        data class Arrived(override val tMs: Long, val alongM: Double, val remainingM: Double) : Event
        data class PuckFroze(override val tMs: Long, val forMs: Long) : Event
        data class ManeuverChanged(override val tMs: Long, val index: Int, val instruction: String) : Event
    }

    /**
     * One frame's worth of what the driver would have been looking at.
     *
     * Sampled rather than kept for every frame: a 20-minute drive is 144 000
     * frames and nothing is learned from 119 of every 120. [SAMPLE_EVERY_MS]
     * is fine enough to see a countdown move and coarse enough to hold a long
     * drive in memory.
     */
    data class Sample(
        val tMs: Long,
        val phase: Phase,
        val offRoute: Boolean,
        val rerouting: Boolean,
        val puck: LngLat?,
        val truth: LngLat,
        /** Distance along the DRIVEN path — always increasing. */
        val truthAlongM: Double,
        /**
         * Where the truth projects onto the route being followed, and how far
         * off it the vehicle really is.
         *
         * Distinct from [truthAlongM], and the distinction matters: on a U-turn
         * the driven path keeps getting longer while the position along the
         * ROUTE runs backwards. An assertion about reversing direction written
         * against [truthAlongM] can never fail.
         */
        val truthRouteAlongM: Double,
        val truthOffsetM: Double,
        val remainingM: Double,
        val distanceToManeuverM: Double,
        val maneuverIndex: Int?,
        val speedKmh: Int?,
        val overSpeedLimit: Boolean,
        val gps: GpsHealth,
        /** What the driver was being shown as an error, if anything. */
        val error: String?,
    )

    val events = ArrayList<Event>()
    val samples = ArrayList<Sample>()
    var ui: UiState = UiState()
        private set

    /** Fixes the app was handed, for the "did the drive actually reach it" check. */
    var fixesDelivered = 0
        private set
    var framesRun = 0L
        private set

    // Mirrors of the Activity's own private carry-overs.
    private var lastFix: LngLat? = null
    private var lastBearing = 0.0
    private var truth: LngLat = LngLat(0.0, 0.0)
    private var truthAlong = 0.0
    private var nowMs = 0L
    private var nextSampleAtMs = 0L
    private var pendingRerouteAtMs: Long? = null
    private var pendingRerouteFrom: LngLat? = null
    private var lastPuckAtMs: Long? = null
    private var lastPuck: LngLat? = null
    private var puckFrozenSince: Long? = null
    /** The geometry the tracker is currently following. Replaced by a reroute. */
    private var routeGeometry: List<LngLat> = emptyList()

    private val session = NavSession(tracker, announcer)

    // ---- setting up a journey ----------------------------------------------

    /** Mirrors choosing a route in PREVIEW. */
    fun preview(plan: Plan, destination: LngLat, name: String = "Destination") {
        tracker.setRoute(plan.geometry)
        setLanes(plan)
        routeGeometry = plan.geometry
        ui = ui.copy(
            phase = Phase.PREVIEW,
            destination = destination,
            destinationName = name,
            routeDistanceM = plan.distanceM,
            routeDurationS = plan.durationS,
            maneuvers = plan.maneuvers,
            routeSnapMaxM = plan.snapMaxM,
            routeOriginSnapM = plan.originSnapM,
            // The route was planned from where the driver is, which is where
            // its geometry starts. Mirrors the Activity, where `lastFix` is
            // already set by the time a route comes back.
            myLocation = plan.geometry.firstOrNull(),
        )
    }

    /**
     * Set the posted limit for the whole drive.
     *
     * On the device this arrives from `/speed` every 150 m, which the harness
     * has no network for. Set once rather than faked per fix: the question
     * being asked of the app is what it does with a limit, not whether it can
     * fetch one, and a constant limit is exactly what a driver on one road has.
     */
    fun setSpeedLimit(kmh: Int?, inferred: Boolean) {
        ui = ui.copy(speedLimitKmh = kmh, speedLimitInferred = inferred)
    }

    /** Mirrors `MainActivity.startNavigation`. */
    fun start() {
        session.reset()
        session.noteRoutePlannedFrom(lastFix ?: ui.myLocation, ui.routeOriginSnapM)
        ui = ui.copy(phase = Phase.NAVIGATING, cam = MapCamera.onRecenter(ui.cam),
                     alternatives = emptyList())
    }

    // ---- driving ------------------------------------------------------------

    /**
     * Deliver [fixes] in trace order, running the frame loop in between.
     *
     * Fix timestamps are the schedule: a 20-second gap in the trace is 20
     * seconds of frames with no fix, which is exactly what a tunnel is and is
     * the only way to exercise the tracker's stale-fix branch.
     */
    fun drive(fixes: List<SimFix>) = drive(fixes, startAfterMs = 0L, plan = null, destination = null)

    /**
     * Deliver [fixes], starting navigation part-way in.
     *
     * ## Why this exists
     *
     * The device does not begin navigating at the route's first coordinate. It
     * receives fixes, asks the backend for a route, waits, shows a preview and
     * only then starts — several seconds into the drive. `drive(fixes)` starts
     * at t=0, and that difference hid a real defect: on a route that U-turns
     * 329 m in, the tracker's FIRST lock happens inside a stretch where two
     * passes of the same street are metres apart, and an unconstrained
     * projection picked the wrong one. Measured on the S24: the vehicle placed
     * half a kilometre ahead of itself and rerouted one second after Start.
     *
     * So a scenario that wants to reproduce the device's timing says so, and
     * passes the plan to load once the fixes have started flowing.
     */
    fun drive(
        fixes: List<SimFix>,
        startAfterMs: Long,
        plan: Plan?,
        destination: dev.vector.geo.LngLat?,
        name: String = "Destination",
        /**
         * How long the route preview is on screen before Start.
         *
         * Not zero, and this matters. `tracker.setRoute` happens at PREVIEW, so
         * the tracker's FIRST fix — the one that decides which pass of a
         * doubled-back street the vehicle is locked to — arrives during the
         * preview, not at Start. Measured on the S24: PREVIEW at 09:53:01.5,
         * NAVIGATING at 09:53:04.65, so about three seconds.
         */
        previewMs: Long = 3_000L,
    ) {
        val frameStepMs = (1000.0 / frameHz).toLong().coerceAtLeast(1L)
        var previewed = plan == null
        var started = plan == null
        for (fix in fixes) {
            // Frames up to this fix's timestamp.
            while (nowMs + frameStepMs <= fix.tMs) {
                nowMs += frameStepMs
                tick()
            }
            nowMs = fix.tMs
            if (!previewed && fix.tMs >= startAfterMs) {
                // The route is SET here, so this is where the tracker takes its
                // first lock — during the preview, with the vehicle already
                // moving, exactly as on the device.
                preview(plan!!, destination ?: plan.geometry.last(), name)
                previewed = true
            }
            if (!started && previewed && fix.tMs >= startAfterMs + previewMs) {
                start()
                started = true
            }
            deliver(fix)
        }
        // A trailing second of frames, so an arrival that the last fix made
        // inevitable is actually reached. On the device the frame loop does not
        // stop when the fixes do.
        val end = nowMs + 1_000
        while (nowMs < end) {
            nowMs += frameStepMs
            tick()
        }
    }

    /** Fixes the plausibility gate discarded. */
    var fixesDropped = 0
        private set

    /** Mirrors `LocationCallback.onLocationResult`. */
    private fun deliver(fix: SimFix) {
        // First thing the Activity does with a fix, and for the same reason:
        // an impossible position must not reach probe collection either.
        if (!session.acceptFix(fix.position, nowMs)) {
            fixesDropped++
            return
        }
        fixesDelivered++
        truth = fix.truth
        truthAlong = fix.truthAlongM
        val here = fix.position
        lastFix = here
        // Faithful to production: `Location.bearing` is a float that reads 0.0
        // when the receiver has not set it, so the app cannot tell "north" from
        // "unknown" without asking hasBearing(). Reproduced rather than fixed
        // here so the scenario suite sees what the app sees.
        lastBearing = fix.bearingDeg ?: 0.0
        if (ui.myLocation != here) ui = ui.copy(myLocation = here)

        val before = ui
        val r = session.onFix(ui, here, fix.speedMs, lastBearing,
                              nowMs * 1_000_000L, nowMs)
        ui = r.ui
        record(before, ui)
        perform(r.actions)
    }

    /** Mirrors `Choreographer.FrameCallback.doFrame`. */
    private fun tick() {
        framesRun++
        applyPendingReroute()
        val before = ui
        val r = session.onFrame(ui, nowMs * 1_000_000L, nowMs)
        // Mirrors the Activity exactly, including the reference compare: the
        // frame that has a new fact and no action is the frame that notices the
        // fixes have stopped, and `if (actions.isNotEmpty())` discarded it.
        if (r.ui !== ui) {
            ui = r.ui
            record(before, ui)
        }
        if (r.actions.isNotEmpty()) perform(r.actions)
        noteFrozenPuck()
        maybeSample()
    }

    /**
     * Mirrors the Activity's `drawRoute` handing the lateral profile to the
     * session (V7 Stage 4).
     *
     * In the Activity this is one line inside `drawRoute`, which is the single
     * point all three route-change paths converge on. The harness has no
     * renderer, so it has to reproduce the call at each of them — and it has to
     * reproduce it AT ALL, which is the point: a harness that set the route on
     * the tracker and withheld the profile would draw every fixture's puck on
     * the centreline whatever the fixture's lane data said, and the lateral
     * assertions below would pass against a world with no lanes in it. That is
     * the same defect as the dropped `approachLanes` in [load], found the same
     * way, one stage later.
     */
    private fun setLanes(plan: Plan) {
        session.setLanes(
            dev.vector.geo.RouteLanes.plan(
                plan.maneuvers.map {
                    dev.vector.geo.RouteLanes.Approach(
                        atM = it.cumulativeM,
                        forwardLanes = it.forwardLanes,
                        totalLanes = it.approachLanes,
                        lanes = it.lanes,
                        // The same line `MainActivity.lateralPlan` carries. A
                        // harness that builds the profile WITHOUT this would
                        // exercise a model the app no longer runs — and it
                        // would do it silently, because every assertion here
                        // would still pass on the fixtures whose turns decline
                        // the rule anyway.
                        turn = dev.vector.geo.RouteLanes.Turn.of(it.type),
                    )
                }
            )
        )
    }

    private fun perform(actions: List<NavSession.Action>) {
        for (a in actions) when (a) {
            is NavSession.Action.Puck -> {
                lastPuck = a.position
                lastPuckAtMs = nowMs
                // `puckFrozenSince` is NOT cleared here. It was, and that is
                // why the outage scenario reported no freeze at all: the fix
                // that ends an outage draws the puck, so the marker was wiped
                // one statement before noteFrozenPuck could emit the event for
                // the freeze that had just ended.
            }
            is NavSession.Action.Camera -> Unit
            is NavSession.Action.Speak -> events.add(Event.Spoke(nowMs, a.text))
            is NavSession.Action.Reroute -> {
                events.add(Event.RerouteRequested(nowMs, a.from))
                pendingRerouteAtMs = nowMs + rerouteLatencyMs
                pendingRerouteFrom = a.from
            }
            is NavSession.Action.Arrived -> {
                events.add(Event.Arrived(nowMs, truthAlong, ui.remainingM))
                arrive()
            }
        }
    }

    /** Mirrors `MainActivity.reroute`'s success and failure branches. */
    private fun applyPendingReroute() {
        val at = pendingRerouteAtMs ?: return
        if (nowMs < at) return
        val from = pendingRerouteFrom
        pendingRerouteAtMs = null
        pendingRerouteFrom = null
        val plan = from?.let { router.route(it) }
        if (plan == null) {
            ui = ui.copy(rerouting = false, error = "Could not plan a route")
            events.add(Event.RerouteFailed(nowMs))
            return
        }
        tracker.setRoute(plan.geometry)
        setLanes(plan)
        routeGeometry = plan.geometry
        session.onRouteReplaced()
        session.noteRoutePlannedFrom(from, plan.originSnapM)
        ui = ui.copy(
            rerouting = false, offRoute = false,
            routeDistanceM = plan.distanceM, routeDurationS = plan.durationS,
            routeSnapMaxM = plan.snapMaxM, routeOriginSnapM = plan.originSnapM,
            maneuvers = plan.maneuvers,
        )
        events.add(Event.RerouteApplied(nowMs, plan.distanceM))
    }

    /** Mirrors `MainActivity.arrive` -> `cancelRoute`. */
    private fun arrive() {
        val name = ui.destinationName
        tracker.clearRoute()
        announcer.reset()
        // With the route, as `cancelRoute` does it.
        session.setLanes(null)
        ui = UiState(
            phase = Phase.EXPLORE,
            settings = ui.settings,
            cam = CameraState(orientation = ui.settings.orientation,
                              perspective = ui.settings.perspective),
            recents = ui.recents,
            myLocation = lastFix,
            status = if (name.isNotBlank()) "Arrived at $name" else "Arrived",
        )
    }

    // ---- observation --------------------------------------------------------

    private fun record(before: UiState, after: UiState) {
        if (!before.offRoute && after.offRoute) {
            events.add(Event.WentOffRoute(nowMs, offsetNow()))
        }
        if (before.offRoute && !after.offRoute) {
            events.add(Event.CameBackOnRoute(nowMs))
        }
        val b = before.currentManeuver?.index
        val a = after.currentManeuver?.index
        if (a != null && a != b) {
            events.add(Event.ManeuverChanged(nowMs, a, after.currentManeuver!!.instruction))
        }
    }

    /**
     * A puck that has not been redrawn is a frozen vehicle, and a frozen
     * vehicle is what the driver sees during an outage. Recorded as one event
     * per freeze with its duration rather than per frame.
     */
    private fun noteFrozenPuck() {
        val last = lastPuckAtMs ?: return
        val stale = nowMs - last
        if (stale > FROZEN_MS) {
            if (puckFrozenSince == null) puckFrozenSince = last
        } else if (puckFrozenSince != null) {
            events.add(Event.PuckFroze(puckFrozenSince!!, last - puckFrozenSince!!))
            puckFrozenSince = null
        }
    }

    private fun maybeSample() {
        if (nowMs < nextSampleAtMs) return
        nextSampleAtMs = nowMs + SAMPLE_EVERY_MS
        samples.add(
            Sample(
                tMs = nowMs,
                phase = ui.phase,
                offRoute = ui.offRoute,
                rerouting = ui.rerouting,
                puck = lastPuck,
                truth = truth,
                truthAlongM = truthAlong,
                truthRouteAlongM = truthAlongRouteM(),
                truthOffsetM = truthOffsetM(),
                remainingM = ui.remainingM,
                distanceToManeuverM = ui.distanceToManeuverM,
                maneuverIndex = ui.currentManeuver?.index,
                speedKmh = ui.speedKmh,
                overSpeedLimit = ui.overSpeedLimit,
                gps = ui.gps,
                error = ui.error,
            )
        )
    }

    /** How far the last fix was from the route currently being tracked. */
    private fun offsetNow(): Double {
        val idx = RouteGeometry.index(routeGeometry) ?: return 0.0
        return idx.project(lastFix ?: return 0.0)?.offsetM ?: 0.0
    }

    /** Distance along the route currently being tracked, from the truth. */
    fun truthAlongRouteM(): Double =
        RouteGeometry.index(routeGeometry)?.project(truth)?.alongM ?: 0.0

    /** How far the truth is from the route currently being tracked. */
    fun truthOffsetM(): Double =
        RouteGeometry.index(routeGeometry)?.project(truth)?.offsetM ?: 0.0

    // ---- reading the result -------------------------------------------------

    /** Trace time between the first off-route reading and the first request. */
    fun deviationToRequestMs(): Long? {
        val off = events.filterIsInstance<Event.WentOffRoute>().firstOrNull() ?: return null
        val req = events.filterIsInstance<Event.RerouteRequested>()
            .firstOrNull { it.tMs >= off.tMs } ?: return null
        return req.tMs - off.tMs
    }

    /** How far the drawn vehicle was from the real one, over the whole drive. */
    fun puckErrorM(): List<Double> = samples.mapNotNull { s ->
        s.puck?.let { DrivePath.metresBetween(it, s.truth) }
    }

    /**
     * Which SIDE of the route the drawn vehicle was on, in metres, per sample
     * (V7 Stage 4). Positive is right of travel, the convention `RouteLanes`
     * and MapLibre's `line-offset` both use.
     *
     * [puckErrorM] cannot answer this and that is the whole reason for a second
     * measurement: it is a distance, so a vehicle drawn 1.75 m into the
     * ONCOMING carriageway and one drawn correctly in its own read as the same
     * number. The sign is the fact under test.
     *
     * Measured against the route's own bearing at the puck's projection rather
     * than against the segment endpoints, so a sample on a curve is not
     * reported as offset merely because the road is bending.
     *
     * ## Why only NAVIGATING and on-route samples count
     *
     * Outside those the puck is the RAW FIX by design — `NavSession.onFix`'s
     * OffRoute and Idle branches, and everything after `arrive()` clears the
     * route. A raw fix carries GPS noise, so measuring it against the route
     * yields a metre or so either side of the centreline that has nothing to
     * do with any lateral claim.
     *
     * Found by measuring rather than reasoned in advance: the first version of
     * this had no filter, and the roundabout trace reported eight samples up to
     * **1.35 m on the ONCOMING side** — which is exactly the failure Stage 4
     * exists to prevent, and was not one. All eight were `phase=EXPLORE`,
     * after arrival, on a cleared route. An unfiltered measurement would have
     * forced the real assertion ("the vehicle is never drawn in oncoming") to
     * be loosened to accommodate noise it should never have included.
     */
    fun puckLateralM(): List<Double> {
        val idx = RouteGeometry.index(routeGeometry) ?: return emptyList()
        return samples.mapNotNull { s ->
            if (s.phase != Phase.NAVIGATING || s.offRoute) return@mapNotNull null
            val puck = s.puck ?: return@mapNotNull null
            val fix = idx.project(puck) ?: return@mapNotNull null
            // Sign from the cross product of the route's direction with the
            // vector out to the puck, in a local planar frame. `project` gives
            // magnitude only.
            val seg = idx.coords.getOrNull(fix.segIdx) ?: return@mapNotNull null
            val next = idx.coords.getOrNull(fix.segIdx + 1) ?: return@mapNotNull null
            val kx = kotlin.math.cos(Math.toRadians(puck.lat)) * RouteGeometry.M_PER_DEG_LAT
            val ky = RouteGeometry.M_PER_DEG_LAT
            val dx = (next.lng - seg.lng) * kx
            val dy = (next.lat - seg.lat) * ky
            val px = (puck.lng - fix.position.lng) * kx
            val py = (puck.lat - fix.position.lat) * ky
            // Right of travel is a NEGATIVE z cross product in a y-up frame.
            val cross = dx * py - dy * px
            if (cross > 0) -fix.offsetM else fix.offsetM
        }
    }

    companion object {
        const val SAMPLE_EVERY_MS = 250L

        /**
         * A puck redrawn less recently than this is frozen.
         *
         * Three frames at 120 Hz is 25 ms; a fifth of a second is well past any
         * scheduling hiccup and well inside what a driver notices.
         */
        const val FROZEN_MS = 200L

        /**
         * Load a route fixture captured from the live stack.
         *
         * Parsed with `VectorApi.parseRouteFeature` — the production parser —
         * for the reason given in its KDoc.
         */
        fun load(name: String, routeId: Int = 0): Plan {
            val text = DriveHarness::class.java.classLoader!!
                .getResourceAsStream("routes/$name.json")!!
                .bufferedReader().readText()
            val feats = JSONObject(text).getJSONArray("features")
            val api = VectorApi(base = "http://127.0.0.1:1", token = "")
            val r = api.parseRouteFeature(feats.getJSONObject(routeId))
            return Plan(
                geometry = r.geometry,
                distanceM = r.distanceM,
                durationS = r.durationS,
                maneuvers = r.steps.mapIndexed { i, s ->
                    // Every field the Activity carries, including the lane
                    // ones. Dropping `approachLanes`/`laneData` here — which
                    // this did until V7 Stage 4 — makes every fixture drive
                    // measure a route with NO lane data whatever the fixture
                    // says, silently. That is the same shape of defect as the
                    // stubbed `driverZoomed = false` that hid D1 through the
                    // whole of Stage 2: a harness that quietly withholds the
                    // one fact under test cannot fail the test.
                    Maneuver(i, s.type, s.instruction, s.distanceM, s.cumulativeM,
                             s.turnLanes, s.exitRef, s.destination, s.road,
                             s.approachLanes, s.laneData)
                },
                snapMaxM = r.snapMaxM,
                originSnapM = r.originSnapM,
                destSnapM = r.destSnapM,
            )
        }

        /** A router that always answers with the same plan. */
        fun fixedRouter(plan: Plan) = Router { plan }

        /** A router that always fails, for the network-loss scenarios. */
        fun deadRouter() = Router { null }
    }
}
