package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.walk.WalkCameraRegime
import dev.vector.geo.walk.WalkContract
import dev.vector.geo.walk.WalkCost
import dev.vector.geo.walk.WalkCrossing
import dev.vector.geo.walk.WalkCrossingExposure
import dev.vector.geo.walk.WalkDiagnostics
import dev.vector.geo.walk.WalkFollowState
import dev.vector.geo.walk.WalkManeuver
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkRefusalKind
import dev.vector.geo.walk.WalkRoadId
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
 * The walking navigation loop as a PRODUCT: rerouting, off-route UX, arrival,
 * voice and the camera a user can also touch (V7.4 4C final).
 *
 * `WalkNavSessionTest` covers what 4C.2 built — projection, puck, camera
 * regimes, the loop's shape. This file covers what 4C-final added on top, and
 * every test in it is a sequence over many fixes: whether a reroute fired once
 * or three times, whether the old route's turns survived a replacement,
 * whether arrival happened twice. None of those can be checked by walking
 * around with a phone, which is exactly why they are here.
 */
class WalkNavFinalTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = Math.cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    /** A point [northM] along a due-north route, [eastM] to the side of it. */
    private fun pt(northM: Double, eastM: Double = 0.0) = LngLat(
        lng0 + eastM / kx,
        lat + northM / RouteGeometry.M_PER_DEG_LAT,
    )

    private fun maneuver(
        kind: WalkManeuverKind,
        atM: Double,
        road: WalkRoadId? = null,
        spanM: Double = 0.0,
    ) = WalkManeuver(
        kind = kind,
        index = (atM / 10.0).toInt(),
        distanceM = atM,
        distanceToNextM = null,
        road = road,
        crossing = if (kind == WalkManeuverKind.CROSS) WalkCrossing(
            type = null, typeSource = null, markings = null, kerb = null,
            tactilePaving = null, distanceM = spanM, distanceToCrossingM = null,
            approachIndex = null, approachDistanceM = null, enterIndex = null,
            leaveIndex = null, road = null, crossedRoadSource = null,
        ) else null,
    )

    private fun route(
        lengthM: Double = 600.0,
        plan: List<WalkManeuver> = listOf(
            maneuver(WalkManeuverKind.DEPART, 0.0),
            maneuver(WalkManeuverKind.TURN_LEFT, 300.0),
            maneuver(WalkManeuverKind.ARRIVE, lengthM),
        ),
        cost: WalkCost? = null,
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
                cost = cost,
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

    /**
     * Step sideways off the route at [perFixM] a second, from [atM] along.
     *
     * The perpendicular departure, which is the common way a person leaves a
     * walking route and the one that makes no along-route progress at all.
     */
    private fun departSideways(
        session: WalkNavSession,
        atM: Double,
        fixes: Int,
        startMs: Long,
        perFixM: Double = 8.0,
        cam: CameraState = navCam(),
    ): List<WalkNavSession.Result> {
        val out = ArrayList<WalkNavSession.Result>()
        var t = startMs
        var lateral = 0.0
        repeat(fixes) {
            t += 1000
            lateral += perFixM
            out.add(session.onFix(pt(atM, lateral), t, cam, Phase.NAVIGATING))
        }
        return out
    }

    // ------------------------------------------------------- the instruction

    @Test
    fun `the banner and the voice are handed the same instruction object`() {
        // The structural form of "one instruction source": the sentence the
        // session emits as speech is built from the very object it publishes
        // on the state for the banner to render. They cannot be made to
        // disagree without changing both at once.
        val s = WalkNavSession(route())
        val results = walk(s, 320.0)
        val spoken = results.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Speak>().map { it.text }
        }
        assertTrue(spoken.isNotEmpty())
        val withTurn = results.last { it.state.instruction?.kind == WalkManeuverKind.TURN_LEFT }
        val instruction = withTurn.state.instruction!!
        assertTrue(
            "no spoken line came from the banner's instruction: $spoken",
            spoken.any { it.contains(instruction.action, ignoreCase = true) },
        )
    }

    @Test
    fun `the instruction names no road when the plan establishes none`() {
        val s = WalkNavSession(route())
        val results = walk(s, 320.0)
        for (r in results) {
            val i = r.state.instruction ?: continue
            assertFalse("invented a road: ${i.banner}", i.roadKnown)
            assertFalse(i.banner.contains(" onto "))
        }
    }

    @Test
    fun `a known road reaches the banner unchanged`() {
        val s = WalkNavSession(
            route(
                plan = listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    maneuver(
                        WalkManeuverKind.TURN_LEFT, 300.0,
                        road = WalkRoadId(name = "Al Waab Street"),
                    ),
                    maneuver(WalkManeuverKind.ARRIVE, 600.0),
                )
            )
        )
        val results = walk(s, 320.0)
        val turn = results.last { it.state.instruction?.kind == WalkManeuverKind.TURN_LEFT }
        assertEquals("Turn left onto Al Waab Street", turn.state.instruction!!.banner)
    }

    @Test
    fun `a span the walker is inside beats whatever is next`() {
        // Being ON a crossing is what the walker is doing; the maneuver on the
        // far side of it is not. Without this the banner would describe the
        // next turn while the person is standing in a road.
        val s = WalkNavSession(
            route(
                plan = listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    maneuver(WalkManeuverKind.CROSS, 100.0, spanM = 60.0),
                    maneuver(WalkManeuverKind.TURN_LEFT, 300.0),
                    maneuver(WalkManeuverKind.ARRIVE, 600.0),
                )
            )
        )
        val results = walk(s, 140.0)
        val onCrossing = results.last()
        assertEquals(WalkManeuverKind.CROSS, onCrossing.state.instruction?.kind)
        assertEquals("Cross the road", onCrossing.state.instruction?.banner)
    }

    // ---------------------------------------------------------- off-route UX

    @Test
    fun `an uncertain fix keeps the instruction and claims no departure`() {
        // The honest "I cannot tell" band. Taking the instruction away here
        // would remove the only guidance available at the moment it is least
        // certain; announcing a departure would be an accusation.
        val s = WalkNavSession(route())
        walk(s, 200.0)
        var t = 400_000L
        var last: WalkNavSession.Result? = null
        // 32 m out: past the 25 m on-route radius, inside the 45 m off-route
        // radius. The follower calls this UNCERTAIN however long it persists.
        repeat(20) {
            t += 1000
            last = s.onFix(pt(200.0, 32.0), t, navCam(), Phase.NAVIGATING)
        }
        assertEquals(WalkFollowState.UNCERTAIN, last!!.state.followState)
        assertTrue(last!!.state.uncertain)
        assertFalse(last!!.state.offRoute)
        assertNotNull("an uncertain walker lost their instruction", last!!.state.instruction)
        // And the puck says so rather than claiming confidence.
        val puck = last!!.actions.filterIsInstance<WalkNavSession.Action.Puck>().single()
        assertFalse(puck.confident)
    }

    @Test
    fun `nothing is announced and nothing reroutes from uncertain`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        var t = 400_000L
        val seen = LinkedHashSet<String>()
        repeat(30) {
            t += 1000
            s.onFix(pt(200.0, 32.0), t, navCam(), Phase.NAVIGATING)
                .actions.forEach { seen.add(it.javaClass.simpleName) }
        }
        assertEquals(WalkFollowState.UNCERTAIN, s.state.followState)
        assertTrue("uncertain must not reroute: $seen", seen.none { it == "Reroute" })
        assertTrue("uncertain must not speak: $seen", seen.none { it == "Speak" })
    }

    @Test
    fun `a confirmed departure suppresses the stale instruction`() {
        // The plan describes a route the walker is demonstrably not on, so its
        // next turn has stopped being an instruction.
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val out = departSideways(s, 200.0, fixes = 14, startMs = 400_000L)
        assertEquals(WalkFollowState.OFF_ROUTE, s.state.followState)
        assertNull("a stale instruction survived a confirmed departure", s.state.instruction)
        assertTrue(out.last().state.offRoute)
    }

    @Test
    fun `a single noisy fix cannot produce a departure`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val r = s.onFix(pt(200.0, 120.0), 400_000L, navCam(), Phase.NAVIGATING)
        assertFalse(
            "one reflected fix declared a departure",
            r.state.followState == WalkFollowState.OFF_ROUTE,
        )
        assertTrue(r.actions.none { it is WalkNavSession.Action.Reroute })
    }

    @Test
    fun `the off-route line is spoken exactly once however long it lasts`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val out = departSideways(s, 200.0, fixes = 30, startMs = 400_000L, perFixM = 4.0)
        val spoken = out.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Speak>().map { it.text }
        }
        assertEquals(
            "off-route was announced ${spoken.count { it == dev.vector.geo.walk.WalkVoice.OFF_ROUTE }} times",
            1, spoken.count { it == dev.vector.geo.walk.WalkVoice.OFF_ROUTE },
        )
    }

    // ------------------------------------------------------------- rerouting

    @Test
    fun `a sustained perpendicular departure does eventually reroute`() {
        // ## The defect this test found and now pins
        //
        // The reroute gate first read `WalkFix.walking`, which is progress
        // ALONG the route — and that is exactly zero for a walker stepping
        // sideways off it, because their projection does not move. So
        // rerouting was unreachable for the single most common way of leaving
        // a walking route: built, unit-testable in isolation, dead in
        // practice. It now reads ground movement.
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val out = departSideways(s, 200.0, fixes = 22, startMs = 400_000L)
        val reroutes = out.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Reroute>()
        }
        assertEquals("expected exactly one reroute request", 1, reroutes.size)
        assertTrue(s.state.rerouting)
    }

    @Test
    fun `a reroute waits for the action threshold after the state confirms`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val out = departSideways(s, 200.0, fixes = 22, startMs = 400_000L)
        val firstOffRoute = out.indexOfFirst { it.state.offRoute }
        val firstReroute = out.indexOfFirst { r ->
            r.actions.any { it is WalkNavSession.Action.Reroute }
        }
        assertTrue("never went off-route", firstOffRoute >= 0)
        assertTrue("never rerouted", firstReroute >= 0)
        // The follower's own 6 s persistence, and then the session's further
        // action threshold on top of it.
        assertTrue(
            "rerouted $firstReroute fixes after going off-route at $firstOffRoute",
            firstReroute - firstOffRoute >= 4,
        )
    }

    @Test
    fun `a walker who rejoins before the threshold is never rerouted`() {
        // Stepping off the path and straight back on is not a wrong turn.
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val away = departSideways(s, 200.0, fixes = 13, startMs = 400_000L)
        assertEquals(WalkFollowState.OFF_ROUTE, s.state.followState)
        assertTrue(away.none { r -> r.actions.any { it is WalkNavSession.Action.Reroute } })
        // Back on the route, and walking on.
        var t = 500_000L
        var d = 200.0
        val back = ArrayList<WalkNavSession.Result>()
        repeat(20) {
            t += 1000
            d += 1.35
            back.add(s.onFix(pt(d), t, navCam(), Phase.NAVIGATING))
        }
        assertEquals(WalkFollowState.ON_ROUTE, s.state.followState)
        assertTrue(
            "rerouted after the walker had already rejoined",
            back.none { r -> r.actions.any { it is WalkNavSession.Action.Reroute } },
        )
        assertFalse(s.state.rerouting)
        // And the instruction is back.
        assertNotNull(s.state.instruction)
    }

    @Test
    fun `a stationary walker far from the route is not rerouted`() {
        // Standing somewhere is not a wrong turn — the same rule the car path
        // applies before rerouting, at the same layer, for the same reason.
        val s = WalkNavSession(route())
        walk(s, 200.0)
        var t = 400_000L
        val out = ArrayList<WalkNavSession.Result>()
        repeat(40) {
            t += 1000
            // Parked 80 m to the side, not moving.
            out.add(s.onFix(pt(200.0, 80.0), t, navCam(), Phase.NAVIGATING))
        }
        assertTrue(
            "a standing walker triggered a route request",
            out.none { r -> r.actions.any { it is WalkNavSession.Action.Reroute } },
        )
    }

    @Test
    fun `repeated departures do not produce a reroute storm`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val out = departSideways(s, 200.0, fixes = 60, startMs = 400_000L, perFixM = 3.0)
        val reroutes = out.count { r -> r.actions.any { it is WalkNavSession.Action.Reroute } }
        // One request, then the in-flight guard holds until it is answered.
        assertEquals("reroute storm: $reroutes requests over 60 fixes", 1, reroutes)
    }

    @Test
    fun `a failed reroute leaves the walker on the route they have`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        departSideways(s, 200.0, fixes = 22, startMs = 400_000L)
        assertTrue(s.state.rerouting)
        val spoken = s.onRerouteFailed(WalkRefusalKind.NETWORK_SPLIT)
        assertNotNull("a refusal said nothing", spoken)
        // Said once, not once per attempt.
        assertNull(s.onRerouteFailed(WalkRefusalKind.NETWORK_SPLIT))
        // The route is still there and still being followed.
        assertEquals(600.0, s.route.totalM, 1.0)
    }

    @Test
    fun `a replaced route resets progress, camera and maneuver state`() {
        val s = WalkNavSession(route())
        walk(s, 400.0)
        assertTrue(s.state.progress!!.alongM > 300.0)
        val replacement = route(
            lengthM = 500.0,
            plan = listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.TURN_RIGHT, 250.0),
                maneuver(WalkManeuverKind.ARRIVE, 500.0),
            ),
        )
        s.replaceRoute(replacement)
        // Nothing survives that described the old route.
        assertEquals(WalkFollowState.ACQUIRING, s.state.followState)
        assertNull(s.state.progress)
        assertNull(s.state.instruction)
        assertFalse(s.state.arrived)
        assertFalse(s.state.rerouting)
        assertEquals(WalkCameraRegime.IDLE, s.state.regime)
        assertEquals(500.0, s.route.totalM, 1.0)
    }

    @Test
    fun `no stale maneuver from the old route is announced after a reroute`() {
        // The old plan's TURN_LEFT at 300 m has been announced. The new plan
        // has a TURN_RIGHT at 250 m. If the voice's fired-set survived the
        // replacement, the new route's turn would share an index with an
        // already-announced maneuver and be silently suppressed.
        val s = WalkNavSession(route())
        walk(s, 320.0)
        val replacement = route(
            lengthM = 500.0,
            plan = listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.TURN_RIGHT, 250.0),
                maneuver(WalkManeuverKind.ARRIVE, 500.0),
            ),
        )
        s.replaceRoute(replacement)
        val after = walk(s, 300.0, startMs = 900_000L)
        val spoken = after.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Speak>().map { it.text }
        }
        assertTrue(
            "the new route's turn was never announced: $spoken",
            spoken.any { it.contains("Turn right", ignoreCase = true) },
        )
        assertTrue(
            "the OLD route's turn was announced after replacement: $spoken",
            spoken.none { it.contains("Turn left", ignoreCase = true) },
        )
    }

    @Test
    fun `a replaced route can arrive at its own endpoint`() {
        val s = WalkNavSession(route())
        walk(s, 600.0)
        assertTrue(s.state.arrived)
        s.replaceRoute(route(lengthM = 500.0))
        val after = walk(s, 500.0, startMs = 900_000L)
        assertEquals(
            1,
            after.sumOf { r -> r.actions.count { it is WalkNavSession.Action.Arrived } },
        )
    }

    // --------------------------------------------------------------- arrival

    @Test
    fun `arrival fires once and stops ordinary announcements`() {
        val s = WalkNavSession(route())
        val results = walk(s, 600.0)
        assertEquals(
            1,
            results.sumOf { r -> r.actions.count { it is WalkNavSession.Action.Arrived } },
        )
        val arrivedAt = results.indexOfFirst { it.state.arrived }
        assertTrue(arrivedAt >= 0)
        // After arrival there is nothing left to instruct.
        assertNull(results.last().state.instruction)
    }

    @Test
    fun `arrival is announced once and nothing is said afterwards`() {
        val s = WalkNavSession(route())
        val results = walk(s, 600.0)
        val spoken = results.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Speak>().map { it.text }
        }
        assertEquals(
            "arrival spoken ${spoken.count { it == dev.vector.geo.walk.WalkVoice.ARRIVED }} times",
            1, spoken.count { it == dev.vector.geo.walk.WalkVoice.ARRIVED },
        )
        // Keep feeding fixes past the end — the real loop does.
        var t = 800_000L
        val after = ArrayList<WalkNavSession.Result>()
        repeat(30) {
            t += 1000
            after.add(s.onFix(pt(600.0), t, navCam(), Phase.NAVIGATING))
        }
        assertTrue(
            "spoke after arrival",
            after.none { r -> r.actions.any { it is WalkNavSession.Action.Speak } },
        )
        assertEquals(
            0,
            after.sumOf { r -> r.actions.count { it is WalkNavSession.Action.Arrived } },
        )
    }

    @Test
    fun `the final puck stays at the end of the route`() {
        val s = WalkNavSession(route())
        walk(s, 600.0)
        var t = 800_000L
        var last: WalkNavSession.Result? = null
        repeat(10) {
            t += 1000
            last = s.onFix(pt(600.0), t, navCam(), Phase.NAVIGATING)
        }
        val puck = last!!.actions.filterIsInstance<WalkNavSession.Action.Puck>().single()
        assertEquals(600.0, last!!.state.progress!!.alongM, 1.0)
        assertTrue(puck.confident)
    }

    @Test
    fun `no reroute is requested after arrival`() {
        val s = WalkNavSession(route())
        walk(s, 600.0)
        assertTrue(s.state.arrived)
        val out = departSideways(s, 600.0, fixes = 30, startMs = 900_000L)
        assertTrue(
            "rerouted after the walk was over",
            out.none { r -> r.actions.any { it is WalkNavSession.Action.Reroute } },
        )
    }

    // ------------------------------------------------------ camera behaviour

    @Test
    fun `route state keeps updating while the user holds the camera`() {
        // The frozen-map defect `MapCamera.autoResume` exists because of must
        // not return on foot: following never stops, only the camera yields.
        val s = WalkNavSession(route())
        val free = CameraState(mode = CameraMode.FREE, takenOverAtMs = 1_000L)
        val results = walk(s, 320.0, cam = free)
        assertTrue(
            "the camera moved while the user held it",
            results.none { r -> r.actions.any { it is WalkNavSession.Action.Camera } },
        )
        // Everything else carried on.
        assertEquals(results.size, results.count { r ->
            r.actions.any { it is WalkNavSession.Action.Puck }
        })
        assertNotNull(s.state.instruction)
        assertTrue(s.state.progress!!.alongM > 300.0)
    }

    @Test
    fun `a hand-rotated map is not taken back`() {
        val s = WalkNavSession(route())
        val rotated = CameraState(mode = CameraMode.FOLLOW, manualBearing = 42.0)
        val results = walk(s, 200.0, cam = rotated)
        assertTrue(results.none { r -> r.actions.any { it is WalkNavSession.Action.Camera } })
        assertNotNull(s.state.instruction)
    }

    @Test
    fun `recentering restores the walking framing on the next fix`() {
        // The 4C.2 defect: a policy that recorded writes which never reached
        // the map compared against a zoom it believed it had written, found no
        // drift, and stayed silent — so the framing was never restored.
        val s = WalkNavSession(route())
        walk(s, 100.0)
        val free = CameraState(mode = CameraMode.FREE, takenOverAtMs = 1_000L)
        walk(s, 200.0, cam = free, startMs = 500_000L)
        // The user recenters; the camera is Vector's again.
        val back = s.onFix(pt(210.0), 900_000L, navCam(), Phase.NAVIGATING)
        assertTrue(
            "the walking framing was not restored after a recenter",
            back.actions.any { it is WalkNavSession.Action.Camera },
        )
    }

    @Test
    fun `the walking camera never asserts a tilt`() {
        // Flat, deliberately: the car's 60 degrees buys look-ahead a closer
        // zoom spends, and a walk at 120 m of look-ahead is already 89 seconds
        // ahead. A tilted map is also harder to read standing still.
        val s = WalkNavSession(route())
        val results = walk(s, 600.0)
        val cameras = results.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Camera>()
        }
        assertTrue(cameras.isNotEmpty())
        assertTrue(cameras.all { it.tiltDeg == 0.0 })
    }

    @Test
    fun `the camera widens rather than tightens when the route is left`() {
        val s = WalkNavSession(route())
        walk(s, 200.0)
        val out = departSideways(s, 200.0, fixes = 22, startMs = 400_000L)
        val offRouteCamera = out.flatMap { r ->
            r.actions.filterIsInstance<WalkNavSession.Action.Camera>()
        }.lastOrNull { it.regime == WalkCameraRegime.OFF_ROUTE }
        assertNotNull("the off-route camera regime never reached the map", offRouteCamera)
        // No bearing: the route's direction has stopped describing where this
        // person is going.
        assertNull(offRouteCamera!!.bearingDeg)
    }

    // --------------------------------------------------------- ETA behaviour

    @Test
    fun `the crossing wait is never folded into the shown walk time`() {
        // 4B.4 D1 held end to end: `duration_s` is pure pace time and the
        // expected crossing delay is a separate, additive number.
        val withWait = route(
            cost = WalkCost(
                profile = "general",
                baseTimeS = 444.4,
                paceS = 444.4,
                costS = 576.4,
                penaltyS = 132.0,
                selectionFactors = listOf("crossing_wait"),
                factorS = mapOf("crossing_wait" to 132.0),
                crossing = WalkCrossingExposure(
                    edges = 22, distanceM = 208.0, waitS = 132.0,
                    typedEdges = 6, untypedEdges = 16,
                ),
            )
        )
        val s = WalkNavSession(withWait)
        walk(s, 100.0)
        val eta = s.state.eta!!
        assertEquals(600.0 / 1.35, eta.displayDurationS!!, 0.1)
        assertEquals(132.0, eta.crossingWaitS!!, 0.01)
        // The shown time is the pace time, and the delay is rendered as an
        // explicit addition rather than a revised total.
        assertEquals("7 min", dev.vector.geo.walk.WalkEtaText.walkTime(eta))
        val delay = dev.vector.geo.walk.WalkEtaText.crossingDelay(eta)
        assertNotNull(delay)
        assertTrue(delay!!.startsWith("plus"))
    }

    // ---------------------------------------------------------- voice gating

    @Test
    fun `a muted voice suppresses the sentence but not the state machine`() {
        // The dedup is state, so skipping the announcer entirely would leave a
        // stage un-fired and someone turning the voice back on mid-walk would
        // hear a stale announcement.
        val muted = WalkNavSession(route())
        val out = ArrayList<WalkNavSession.Result>()
        var t = 100_000L
        var d = 0.0
        while (d <= 320.0) {
            t += 1000
            out.add(
                muted.onFix(
                    pt(d), t, navCam(), Phase.NAVIGATING,
                    speaksManeuvers = false, speaksAlerts = false,
                )
            )
            d += 1.35
        }
        assertTrue(
            "a muted walk spoke",
            out.none { r -> r.actions.any { it is WalkNavSession.Action.Speak } },
        )
        // The banner is unaffected: muting the voice is not muting the screen.
        assertNotNull(muted.state.instruction)
    }

    @Test
    fun `alerts-only still reports leaving the route`() {
        // `VoiceMode.ALERTS` is documented as the mode where "the only thing
        // worth interrupting you for is that something has changed", and that
        // is as true of a familiar walk as of a familiar commute.
        val s = WalkNavSession(route())
        var t = 100_000L
        var d = 0.0
        while (d <= 200.0) {
            t += 1000
            s.onFix(pt(d), t, navCam(), Phase.NAVIGATING, speaksManeuvers = false)
            d += 1.35
        }
        var lateral = 0.0
        val spoken = ArrayList<String>()
        repeat(14) {
            t += 1000
            lateral += 8.0
            s.onFix(
                pt(200.0, lateral), t, navCam(), Phase.NAVIGATING,
                speaksManeuvers = false, speaksAlerts = true,
            ).actions.filterIsInstance<WalkNavSession.Action.Speak>()
                .forEach { spoken.add(it.text) }
        }
        assertTrue(
            "alerts-only did not report the departure: $spoken",
            spoken.contains(dev.vector.geo.walk.WalkVoice.OFF_ROUTE),
        )
    }

    // ------------------------------------------------------- outside nav

    @Test
    fun `nothing is spoken or rerouted outside navigation`() {
        val s = WalkNavSession(route())
        val results = walk(s, 600.0, phase = Phase.PREVIEW)
        assertTrue(
            results.none { r ->
                r.actions.any {
                    it is WalkNavSession.Action.Speak || it is WalkNavSession.Action.Reroute
                }
            }
        )
    }

    // ------------------------------------------------------ car isolation

    @Test
    fun `a walking journey leaves a car session untouched`() {
        val s = WalkNavSession(route())
        walk(s, 600.0)
        departSideways(s, 600.0, fixes = 20, startMs = 900_000L)

        val tracker = dev.vector.geo.RouteTracker()
        val car = NavSession(tracker, dev.vector.geo.ManeuverAnnouncer())
        tracker.setRoute((0..20).map { pt(it * 100.0) })
        val ui = UiState(
            phase = Phase.NAVIGATING, routeDistanceM = 2000.0, routeDurationS = 200.0,
        )
        val out = car.onFix(ui, pt(100.0), 13.0, 0.0, 1_000_000_000L, 1_000L)
        assertEquals(Phase.NAVIGATING, out.ui.phase)
        assertFalse(out.ui.offRoute)
        // And the car state carries no walking fields.
        assertNull(out.ui.walk)
        assertNull(out.ui.walkRefusal)
        assertEquals(dev.vector.geo.walk.NavMode.CAR, out.ui.navMode)
    }

    @Test
    fun `the ui mode is derived from the walking state rather than stored`() {
        // A mode flag and a walking state that could contradict each other is
        // the pair that ends up drawing a walking banner over a car route.
        val car = UiState(phase = Phase.NAVIGATING)
        assertEquals(dev.vector.geo.walk.NavMode.CAR, car.navMode)
        assertFalse(car.walking)
        val s = WalkNavSession(route())
        walk(s, 100.0)
        val foot = UiState(phase = Phase.NAVIGATING, walk = s.state)
        assertEquals(dev.vector.geo.walk.NavMode.FOOT, foot.navMode)
        assertTrue(foot.walking)
    }
}
