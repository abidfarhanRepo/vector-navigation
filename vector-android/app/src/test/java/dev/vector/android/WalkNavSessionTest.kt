package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.walk.WalkCameraRegime
import dev.vector.geo.walk.WalkContract
import dev.vector.geo.walk.WalkCrossing
import dev.vector.geo.walk.WalkDiagnostics
import dev.vector.geo.walk.WalkFollowState
import dev.vector.geo.walk.WalkManeuver
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkRoute
import dev.vector.geo.walk.WalkSegments
import dev.vector.geo.walk.WalkingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walking navigation loop, and the camera ownership it shares with the car
 * (V7.4 4C.2).
 *
 * `WalkCameraTest` and `WalkFollowerTest` cover the pure policy and the
 * projection. This file covers the part that could only go wrong once the two
 * meet a camera the user can also touch: who is allowed to move the map, what
 * keeps updating when they are not, and how the map is handed back.
 *
 * The rule under test throughout is that walking creates NO second camera
 * authority. `MapCamera` decides orientation and take-over; `CameraState`
 * carries it; this session only ever asks.
 */
class WalkNavSessionTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = Math.cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    /** A point [northM] along a due-north route, [eastM] to the side of it. */
    private fun pt(northM: Double, eastM: Double = 0.0) = LngLat(
        lng0 + eastM / kx,
        lat + northM / RouteGeometry.M_PER_DEG_LAT,
    )

    private fun maneuver(kind: WalkManeuverKind, atM: Double, span: Double = 0.0) =
        WalkManeuver(
            kind = kind,
            index = (atM / 10.0).toInt(),
            distanceM = atM,
            distanceToNextM = null,
            road = null,
            crossing = if (kind == WalkManeuverKind.CROSS) WalkCrossing(
                type = null, typeSource = null, markings = null, kerb = null,
                tactilePaving = null, distanceM = span, distanceToCrossingM = null,
                approachIndex = null, approachDistanceM = null, enterIndex = null,
                leaveIndex = null, road = null, crossedRoadSource = null,
            ) else null,
        )

    /** A 600 m walk north, with a left turn at 300 m. */
    private fun route(
        lengthM: Double = 600.0,
        plan: List<WalkManeuver> = listOf(
            maneuver(WalkManeuverKind.DEPART, 0.0),
            maneuver(WalkManeuverKind.TURN_LEFT, 300.0),
            maneuver(WalkManeuverKind.ARRIVE, lengthM),
        ),
    ): WalkRoute {
        val geom = ArrayList<LngLat>()
        var d = 0.0
        while (d < lengthM) {
            geom.add(pt(d)); d += 10.0
        }
        geom.add(pt(lengthM))
        return WalkRoute.of(
            WalkContract(
                contractVersion = 1,
                walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general",
                mode = "foot",
                distanceM = lengthM,
                durationS = lengthM / 1.35,
                stepsM = 0.0,
                crossingM = 0.0,
                nodes = geom.size,
                geometry = geom,
                segments = WalkSegments(),
                facts = emptyList(),
                plan = plan,
                cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )
    }

    private fun navCam() = CameraState(mode = CameraMode.FOLLOW)

    /** Walk from 0 to [toM] at walking pace, 1 Hz. */
    private fun walk(
        session: WalkNavSession,
        toM: Double,
        cam: CameraState = navCam(),
        phase: Phase = Phase.NAVIGATING,
        startMs: Long = 100_000L,
    ): List<WalkNavSession.Result> {
        val out = ArrayList<WalkNavSession.Result>()
        var t = startMs
        var d = 0.0
        while (d <= toM) {
            t += 1000
            out.add(session.onFix(pt(d), t, cam, phase))
            d += 1.35
        }
        return out
    }

    // ------------------------------------------------------------- the loop

    @Test
    fun `a walk produces a matched puck on every fix`() {
        val s = WalkNavSession(route())
        val results = walk(s, 200.0)
        assertTrue(results.isNotEmpty())
        for (r in results) {
            val puck = r.actions.filterIsInstance<WalkNavSession.Action.Puck>()
            assertEquals("every fix must place the walker exactly once", 1, puck.size)
            assertTrue(puck.single().confident)
        }
        assertEquals(WalkFollowState.ON_ROUTE, results.last().state.followState)
    }

    @Test
    fun `the camera writes rarely and the puck writes always`() {
        val s = WalkNavSession(route())
        val results = walk(s, 600.0)
        val pucks = results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Puck }
        }
        val cameras = results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Camera }
        }
        assertEquals(results.size, pucks)
        assertTrue("camera wrote $cameras times over ${results.size} fixes", cameras <= 8)
        assertTrue(cameras >= 1)
    }

    @Test
    fun `the camera regime is carried on the state for later stages`() {
        val s = WalkNavSession(route())
        val results = walk(s, 290.0)
        // 290 m: 10 m from the turn at 300 m.
        assertEquals(WalkCameraRegime.APPROACH, results.last().state.regime)
    }

    // ------------------------------------------------- user camera control

    @Test
    fun `panning away stops the camera but never stops the walking state`() {
        // The defect this pins is the one MapCamera.autoResume exists because
        // of: a map frozen by an accidental pan while every number on screen
        // kept counting down. Following must continue; only the CAMERA yields.
        val s = WalkNavSession(route())
        val free = CameraState(mode = CameraMode.FREE, takenOverAtMs = 100_000L)
        val results = walk(s, 400.0, cam = free)
        val cameras = results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Camera }
        }
        assertEquals("a FREE camera must not be moved", 0, cameras)
        // ...and the walker is still being placed and still progressing.
        assertEquals(results.size, results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Puck }
        })
        assertEquals(400.0, results.last().state.progress!!.alongM, 2.0)
        assertEquals(WalkFollowState.ON_ROUTE, results.last().state.followState)
    }

    @Test
    fun `a hand-rotated map is left alone`() {
        // A rotate is a question about the junction ahead. Answering it by
        // taking the map back is not an answer — the same argument
        // MapCamera.onRotate makes for the car.
        val s = WalkNavSession(route())
        val rotated = MapCamera.onRotate(navCam(), 217.0, 100_000L)
        val results = walk(s, 400.0, cam = rotated)
        assertEquals(0, results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Camera }
        })
    }

    @Test
    fun `route overview is a deliberate choice and is not overridden`() {
        val s = WalkNavSession(route())
        val overview = CameraState(mode = CameraMode.OVERVIEW)
        val results = walk(s, 400.0, cam = overview)
        assertEquals(0, results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Camera }
        })
    }

    @Test
    fun `outside navigation the map is the task and the camera is not driven`() {
        val s = WalkNavSession(route())
        for (phase in listOf(Phase.EXPLORE, Phase.PREVIEW)) {
            val results = walk(s, 200.0, phase = phase)
            assertEquals(
                "the camera must not be driven in $phase",
                0,
                results.sumOf { r -> r.actions.count { it is WalkNavSession.Action.Camera } },
            )
            s.reset()
        }
    }

    @Test
    fun `recentering restores follow and the camera moves again`() {
        // Deterministic hand-back, through the EXISTING mechanism: the walking
        // session has no recenter of its own, because MapCamera.onRecenter is
        // already the one authority and it is mode-agnostic.
        val s = WalkNavSession(route())
        val free = MapCamera.onPan(navCam(), 100_000L)
        walk(s, 100.0, cam = free)

        val restored = MapCamera.onRecenter(free)
        assertEquals(CameraMode.FOLLOW, restored.mode)
        assertNull(restored.manualBearing)
        val after = s.onFix(pt(110.0), 300_000L, restored, Phase.NAVIGATING)
        assertTrue(after.actions.any { it is WalkNavSession.Action.Camera })
    }

    @Test
    fun `the auto-resume timer is the shared one, not a walking copy`() {
        // Ten seconds of a hand-taken camera, then it heals itself — the same
        // policy, the same constant, unchanged and unreimplemented.
        val free = MapCamera.onPan(navCam(), 100_000L)
        assertNull(MapCamera.autoResume(free, Phase.NAVIGATING, 105_000L))
        val resumed = MapCamera.autoResume(
            free, Phase.NAVIGATING, 100_000L + MapCamera.AUTO_RESUME_MS,
        )
        assertNotNull(resumed)
        assertEquals(CameraMode.FOLLOW, resumed!!.mode)
    }

    @Test
    fun `a north-up walker keeps north-up`() {
        // The walking policy supplies the direction of TRAVEL; MapCamera
        // decides what the camera does with it. A preference set for the car
        // is honoured on foot with no walking-specific code.
        val s = WalkNavSession(route())
        val northUp = CameraState(
            mode = CameraMode.FOLLOW, orientation = MapOrientation.NORTH_UP,
        )
        val results = walk(s, 400.0, cam = northUp)
        val cameras = results.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Camera>()
        }
        assertTrue(cameras.isNotEmpty())
        assertTrue("north-up must stay north-up", cameras.all { it.bearingDeg == 0.0 })
    }

    @Test
    fun `a heading-up walker faces the route`() {
        val s = WalkNavSession(route())
        val cameras = walk(s, 400.0).flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Camera>()
        }
        assertTrue(cameras.isNotEmpty())
        // The synthetic route runs due north.
        assertTrue(cameras.all { it.bearingDeg != null && it.bearingDeg!! < 1.0 })
    }

    @Test
    fun `walking never tilts the map`() {
        val s = WalkNavSession(route())
        val cameras = walk(s, 400.0).flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Camera>()
        }
        assertTrue(cameras.isNotEmpty())
        assertTrue(cameras.all { it.tiltDeg == 0.0 })
    }

    // ------------------------------------------------------------ off-route

    @Test
    fun `an off-route walker is drawn at the raw fix, not on the route`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        // Walk perpendicular away from the route, past the radius, for longer
        // than the persistence window.
        var t = 400_000L
        var lateral = 0.0
        var last: WalkNavSession.Result? = null
        repeat(12) {
            t += 1000
            lateral += 8.0
            last = s.onFix(pt(200.0, lateral), t, navCam(), Phase.NAVIGATING)
        }
        assertEquals(WalkFollowState.OFF_ROUTE, last!!.state.followState)
        // No matched puck: the caller draws the raw fix.
        assertTrue(last!!.actions.none { it is WalkNavSession.Action.Puck })
        assertTrue(last!!.actions.any { it is WalkNavSession.Action.RawPuck })
        assertNull(last!!.state.puck)
    }

    @Test
    fun `a departure does not reroute before the action threshold`() {
        // ## Why this test changed in 4C-final
        //
        // It was `nothing reroutes in 4C2`, and it asserted that the session
        // could emit ONLY Puck/RawPuck/Camera — i.e. that walking rerouting
        // and walking voice did not exist. That assertion WAS the 4C.2
        // boundary ("Rerouting is 4C.4's"), and this is the stage chartered to
        // build it, so keeping it verbatim would be pinning the absence of
        // this stage's work. Same precedent, and same reasoning, as 4C.2
        // replacing 4C.1's `WalkCameraPolicy.Declining` stub assertion.
        //
        // It now asserts the property that SURVIVES, which is the one the
        // brief actually cares about: a reroute is not requested until the
        // established action threshold has elapsed. The 12 fixes below confirm
        // the departure (45 m offset, 6 s persistence) but do not clear the
        // further `rerouteAfterOffRouteMs`, so no route may be asked for yet.
        //
        // Asserted over the ACTIONS a real off-route walk produces, rather
        // than by reflecting over the sealed hierarchy: `app`'s unit tests run
        // without kotlin-reflect on the classpath, and a test that needs it
        // fails as an error rather than as a judgement about the code.
        val s = WalkNavSession(route())
        walk(s, 200.0)
        var t = 400_000L
        var lateral = 0.0
        val seen = LinkedHashSet<String>()
        repeat(12) {
            t += 1000
            lateral += 8.0
            s.onFix(pt(200.0, lateral), t, navCam(), Phase.NAVIGATING)
                .actions.forEach { seen.add(it.javaClass.simpleName) }
        }
        assertEquals(WalkFollowState.OFF_ROUTE, s.state.followState)
        // The departure is confirmed but young. No route request yet.
        assertTrue(
            "a reroute must wait for the action threshold: $seen",
            seen.none { it.contains("Reroute", ignoreCase = true) },
        )
        assertFalse(s.state.rerouting)
        // Place the walker (matched while still in the uncertain band, raw
        // once confirmed off-route), move the camera, and say the one thing a
        // confirmed departure warrants.
        assertEquals(setOf("Puck", "RawPuck", "Camera", "Speak"), seen)
    }

    @Test
    fun `the off-route camera regime actually reaches the map`() {
        // The defect this pins: the Camera action used to require a MATCHED
        // puck, which silently disabled the whole OFF_ROUTE regime — the
        // wider framing whose only job is to show someone who has left the
        // route the ground between them and it. It was built, unit-tested in
        // the policy, and unreachable in the loop.
        val s = WalkNavSession(route())
        // Collect from the WHOLE sequence, on-route phase included: the
        // comparison below needs the cruise framing this walk established.
        val cameras = ArrayList<WalkNavSession.Action.Camera>()
        walk(s, 200.0).forEach { r ->
            cameras.addAll(r.actions.filterIsInstance<WalkNavSession.Action.Camera>())
        }
        var t = 400_000L
        var lateral = 0.0
        repeat(12) {
            t += 1000
            lateral += 8.0
            s.onFix(pt(200.0, lateral), t, navCam(), Phase.NAVIGATING)
                .actions.filterIsInstance<WalkNavSession.Action.Camera>()
                .forEach { cameras.add(it) }
        }
        assertEquals(WalkFollowState.OFF_ROUTE, s.state.followState)
        val off = cameras.filter { it.regime == WalkCameraRegime.OFF_ROUTE }
        assertTrue("the off-route camera never moved", off.isNotEmpty())
        // It asserts no bearing: the route's direction has stopped describing
        // where this person is going.
        assertTrue(off.all { it.bearingDeg == null })
        // And it is WIDER than the cruise framing, not tighter.
        val cruise = cameras.first { it.regime == WalkCameraRegime.CRUISE }
        assertTrue(off.all { it.zoom < cruise.zoom })
    }

    @Test
    fun `the uncertain band keeps drawing, and says it is uncertain`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        // 35 m off: past the on-route radius, inside the off-route one.
        val r = s.onFix(pt(205.0, 35.0), 400_000L, navCam(), Phase.NAVIGATING)
        assertEquals(WalkFollowState.UNCERTAIN, r.state.followState)
        val puck = r.actions.filterIsInstance<WalkNavSession.Action.Puck>().single()
        assertFalse("an uncertain fix must not claim confidence", puck.confident)
    }

    // ------------------------------------------------------------- arrival

    @Test
    fun `arrival fires exactly once at the end of the route`() {
        val s = WalkNavSession(route())
        val results = walk(s, 600.0)
        val arrivals = results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Arrived }
        }
        assertEquals(1, arrivals)
        assertTrue(results.last().state.arrived)
    }

    @Test
    fun `arrival does not fire while off the route`() {
        // A walker 90 m to the side of the destination has not arrived at it,
        // however little route is nominally left.
        val s = WalkNavSession(route())
        walk(s, 400.0)
        var t = 900_000L
        var lateral = 0.0
        var last: WalkNavSession.Result? = null
        repeat(12) {
            t += 1000
            lateral += 8.0
            last = s.onFix(pt(595.0, lateral), t, navCam(), Phase.NAVIGATING)
        }
        assertEquals(WalkFollowState.OFF_ROUTE, last!!.state.followState)
        assertFalse(last!!.state.arrived)
    }

    @Test
    fun `arrival does not fire outside navigation`() {
        val s = WalkNavSession(route())
        val results = walk(s, 600.0, phase = Phase.PREVIEW)
        assertEquals(0, results.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Arrived }
        })
    }

    @Test
    fun `the camera reaches ARRIVAL before the session declares arrival`() {
        // ARRIVAL_M (30 m) sits above ARRIVAL_RADIUS_M (25 m) so the framing
        // has settled by the time the state changes, rather than both moving
        // on the same fix.
        val s = WalkNavSession(route())
        val results = walk(s, 600.0)
        val firstArrivalRegime = results.indexOfFirst {
            it.state.regime == WalkCameraRegime.ARRIVAL
        }
        val firstArrived = results.indexOfFirst { it.state.arrived }
        assertTrue(firstArrivalRegime >= 0)
        assertTrue(firstArrived >= 0)
        assertTrue(
            "the camera must settle before arrival is declared",
            firstArrivalRegime < firstArrived,
        )
    }

    @Test
    fun `reset clears arrival so a new route can arrive again`() {
        val s = WalkNavSession(route())
        walk(s, 600.0)
        assertTrue(s.state.arrived)
        s.reset()
        assertFalse(s.state.arrived)
        assertEquals(WalkFollowState.ACQUIRING, s.state.followState)
        val again = walk(s, 600.0, startMs = 900_000L)
        assertEquals(1, again.sumOf { r ->
            r.actions.count { it is WalkNavSession.Action.Arrived }
        })
    }

    // ----------------------------------------------------------------- ETA

    @Test
    fun `the three durations stay separate on the walking state`() {
        // 4C.2 exposes them and decides nothing. duration_s is pure pace time
        // and the crossing wait is NOT folded into it; whether it is ever
        // shown is 4C.3's decision.
        val s = WalkNavSession(route())
        val r = walk(s, 100.0).last()
        val eta = r.state.eta
        assertNotNull(eta)
        assertEquals(600.0 / 1.35, eta!!.displayDurationS!!, 0.1)
        // This synthetic contract carries no cost block, so the other two are
        // honestly absent rather than defaulted to the duration.
        assertNull(eta.selectionCostS)
        assertNull(eta.crossingWaitS)
    }

    // --------------------------------------------------------- determinism

    @Test
    fun `identical fix sequences produce identical actions`() {
        val r = route()
        val a = WalkNavSession(r)
        val b = WalkNavSession(r)
        val ra = walk(a, 600.0)
        val rb = walk(b, 600.0)
        assertEquals(ra.size, rb.size)
        for (i in ra.indices) {
            assertEquals("fix $i actions", ra[i].actions, rb[i].actions)
            assertEquals("fix $i state", ra[i].state.followState, rb[i].state.followState)
            assertEquals("fix $i regime", ra[i].state.regime, rb[i].state.regime)
        }
    }

    // ------------------------------------------------------ car isolation

    @Test
    fun `the walking session is not a car session and shares no state with one`() {
        // The two loops share the camera AUTHORITY and nothing else. A car
        // NavSession must be unaffected by anything a walk does, which is
        // structurally guaranteed here: there is no path between them.
        val walkRoute = route()
        val s = WalkNavSession(walkRoute)
        walk(s, 600.0)

        // A fresh car session behaves exactly as it always did.
        val tracker = dev.vector.geo.RouteTracker()
        val car = NavSession(tracker, dev.vector.geo.ManeuverAnnouncer())
        tracker.setRoute((0..20).map { pt(it * 100.0) })
        val ui = UiState(phase = Phase.NAVIGATING, routeDistanceM = 2000.0,
                         routeDurationS = 200.0)
        val out = car.onFix(ui, pt(100.0), 13.0, 0.0, 1_000_000_000L, 1_000L)
        assertEquals(Phase.NAVIGATING, out.ui.phase)
        assertFalse(out.ui.offRoute)
    }
}
