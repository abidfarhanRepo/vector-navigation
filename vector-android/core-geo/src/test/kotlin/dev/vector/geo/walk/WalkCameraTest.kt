package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The walking camera policy (V7.4 4C.2).
 *
 * These assert the REGIMES and the settling behaviour, on synthetic geometry
 * where distances are exact. The same policy is driven over the real captured
 * Qatar payloads in the app module's `WalkRealQatarTest`.
 *
 * The property that matters most here is not any single zoom — it is that the
 * camera writes rarely. A camera that recomputed a zoom from the distance to
 * the next event on every fix would pump once a second, which is the defect
 * the car camera already learned (see `ManeuverCamera`'s KDoc on dense
 * junctions), and walking makes it worse because a walker spends far longer
 * inside any given distance band.
 */
class WalkCameraTest {

    // ---------------------------------------------------------------- helpers

    private fun straight(lengthM: Double, stepM: Double = 10.0): List<LngLat> {
        val pts = ArrayList<LngLat>()
        var d = 0.0
        while (d < lengthM) {
            pts.add(LngLat(0.0, d / RouteGeometry.M_PER_DEG_LAT))
            d += stepM
        }
        pts.add(LngLat(0.0, lengthM / RouteGeometry.M_PER_DEG_LAT))
        return pts
    }

    private fun maneuver(
        kind: WalkManeuverKind,
        atM: Double,
        crossing: WalkCrossing? = null,
        stairs: WalkStairs? = null,
    ) = WalkManeuver(
        kind = kind,
        index = (atM / 10.0).toInt(),
        distanceM = atM,
        distanceToNextM = null,
        road = null,
        crossing = crossing,
        stairs = stairs,
    )

    private fun crossingAt(atM: Double, spanM: Double) = maneuver(
        WalkManeuverKind.CROSS, atM,
        crossing = WalkCrossing(
            type = null, typeSource = null, markings = null, kerb = null,
            tactilePaving = null, distanceM = spanM, distanceToCrossingM = null,
            approachIndex = null, approachDistanceM = null,
            enterIndex = null, leaveIndex = null, road = null,
            crossedRoadSource = null,
        ),
    )

    private fun route(lengthM: Double, plan: List<WalkManeuver>): WalkRoute =
        WalkRoute.of(
            WalkContract(
                contractVersion = 1,
                walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general",
                mode = "foot",
                distanceM = lengthM,
                durationS = lengthM / 1.35,
                stepsM = 0.0,
                crossingM = 0.0,
                nodes = 0,
                geometry = straight(lengthM),
                segments = WalkSegments(),
                facts = emptyList(),
                plan = plan,
                cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )

    /** A 600 m walk with a left turn at 300 m. */
    private fun turnRoute(): WalkRoute = route(
        600.0,
        listOf(
            maneuver(WalkManeuverKind.DEPART, 0.0),
            maneuver(WalkManeuverKind.TURN_LEFT, 300.0),
            maneuver(WalkManeuverKind.ARRIVE, 600.0),
        ),
    )

    private fun inputsAt(
        r: WalkRoute,
        d: Double,
        state: WalkFollowState = WalkFollowState.ON_ROUTE,
    ) = WalkCameraInputs.of(r.progressAt(d), state)

    // ------------------------------------------------------------- the ladder

    @Test
    fun `the zoom ladder is derived from ground distance, not chosen`() {
        val p = WalkCameraPolicy()
        // The inverse of VectorStyle.metresPx: at zoom z one logical pixel is
        // METRES_PER_PX_Z0 / 2^z metres, and the look-ahead strip is 0.68 of
        // the viewport. Inside the zoom guards the round trip is exact — the
        // zoom is arithmetic, not taste.
        for (metres in listOf(90.0, 120.0, 200.0, 400.0, 1000.0)) {
            val z = p.zoomForLookAhead(metres)
            assertEquals(metres, p.lookAheadAt(z), 0.01)
        }
    }

    @Test
    fun `the zoom guards clamp, and that is visible rather than silent`() {
        // Outside the guards the round trip deliberately does NOT hold: the
        // clamp is doing its job. This is the other half of the property
        // above, and it is asserted rather than left implicit because a clamp
        // that engages unnoticed is exactly the defect that shaped this
        // ladder — see `no regime ever clamps`.
        val p = WalkCameraPolicy()
        // 40 m of look-ahead would want z20.25, past the basemap's useful
        // detail; the guard holds it at MAX_ZOOM.
        assertEquals(WalkCameraPolicy.MAX_ZOOM, p.zoomForLookAhead(40.0), 1e-9)
        assertTrue(p.lookAheadAt(WalkCameraPolicy.MAX_ZOOM) > 40.0)
        // A kilometres-wide ask is held at MIN_ZOOM.
        assertEquals(WalkCameraPolicy.MIN_ZOOM, p.zoomForLookAhead(10_000.0), 1e-9)
        // And a nonsensical ask does not produce a nonsensical zoom.
        assertEquals(WalkCameraPolicy.MAX_ZOOM, p.zoomForLookAhead(0.0), 1e-9)
        assertEquals(WalkCameraPolicy.MAX_ZOOM, p.zoomForLookAhead(-5.0), 1e-9)
    }

    @Test
    fun `no regime ever clamps against the zoom guards`() {
        // The defect this pins, found while deriving the ladder: the first
        // version asked for 70 m at APPROACH (z19.45) and 55 m at ARRIVAL
        // (z19.50), and BOTH clamped at the then-MAX of 19.5 — so three
        // regimes silently collapsed onto one zoom while their constants
        // claimed three different look-aheads. A constant whose stated value
        // is not the value that renders is worse than no constant.
        val p = WalkCameraPolicy()
        val asks = listOf(
            WalkCameraPolicy.CRUISE_LOOKAHEAD_M,
            WalkCameraPolicy.APPROACH_LOOKAHEAD_M,
            WalkCameraPolicy.ARRIVAL_LOOKAHEAD_M,
            WalkCameraPolicy.OFF_ROUTE_LOOKAHEAD_M,
            WalkCameraPolicy.SPAN_LOOKAHEAD_MIN_M,
            WalkCameraPolicy.SPAN_LOOKAHEAD_MAX_M,
        )
        for (m in asks) {
            val z = p.zoomForLookAhead(m)
            assertTrue(
                z > WalkCameraPolicy.MIN_ZOOM && z < WalkCameraPolicy.MAX_ZOOM,
                "look-ahead $m m clamps at z$z",
            )
            // And the clamp did not silently change the framing.
            assertEquals(m, p.lookAheadAt(z), 0.01)
        }
    }

    @Test
    fun `the regimes are ordered tight to wide in the way the argument claims`() {
        val p = WalkCameraPolicy()
        val approach = p.zoomForLookAhead(WalkCameraPolicy.APPROACH_LOOKAHEAD_M)
        val arrival = p.zoomForLookAhead(WalkCameraPolicy.ARRIVAL_LOOKAHEAD_M)
        val cruise = p.zoomForLookAhead(WalkCameraPolicy.CRUISE_LOOKAHEAD_M)
        val offRoute = p.zoomForLookAhead(WalkCameraPolicy.OFF_ROUTE_LOOKAHEAD_M)
        // Approach is the tightest; off-route is the widest — WIDER than
        // cruise, because the useful thing to show someone who has left the
        // route is the ground between them and it.
        assertTrue(approach > arrival)
        assertTrue(arrival > cruise)
        assertTrue(cruise > offRoute)
        // And each step is big enough to see.
        assertTrue(abs(approach - cruise) > WalkCameraPolicy.REANCHOR)
        assertTrue(abs(offRoute - cruise) > WalkCameraPolicy.REANCHOR)
    }

    @Test
    fun `walking is flat, never tilted`() {
        // The car tilts to 60 deg to buy back look-ahead a closer zoom spends.
        // A walk does not need that trade: 120 m of look-ahead is already 89
        // seconds of walking. See the WalkCameraPolicy KDoc.
        assertEquals(0.0, WalkCameraPolicy.WALK_TILT_DEG, 0.0)
        val p = WalkCameraPolicy()
        val d = p.decide(inputsAt(turnRoute(), 10.0))
        assertEquals(0.0, (d as WalkCameraDecision.Transition).tiltDeg, 0.0)
    }

    // ------------------------------------------------------------ the regimes

    @Test
    fun `an ordinary stretch is CRUISE`() {
        val p = WalkCameraPolicy()
        p.decide(inputsAt(turnRoute(), 50.0))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
    }

    @Test
    fun `approaching a turn enters APPROACH and tightens`() {
        val p = WalkCameraPolicy()
        val r = turnRoute()
        val cruise = p.decide(inputsAt(r, 100.0)) as WalkCameraDecision.Transition
        assertEquals(WalkCameraRegime.CRUISE, cruise.regime)
        // 250 m along: 50 m from the turn at 300 m, inside the 60 m entry.
        val approach = p.decide(inputsAt(r, 250.0)) as WalkCameraDecision.Transition
        assertEquals(WalkCameraRegime.APPROACH, approach.regime)
        assertTrue(approach.targetZoom > cruise.targetZoom)
    }

    @Test
    fun `a continue is not worth a camera move`() {
        // The same exclusion the car camera makes: `continue` is a way-identity
        // change the walker does nothing about, and `depart` has happened.
        assertFalse(WalkCameraPolicy.significant(WalkManeuverKind.CONTINUE))
        assertFalse(WalkCameraPolicy.significant(WalkManeuverKind.DEPART))
        assertFalse(WalkCameraPolicy.significant(null))
        assertTrue(WalkCameraPolicy.significant(WalkManeuverKind.TURN_LEFT))
        assertTrue(WalkCameraPolicy.significant(WalkManeuverKind.CROSS))
        assertTrue(WalkCameraPolicy.significant(WalkManeuverKind.STAIRS))

        val p = WalkCameraPolicy()
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.CONTINUE, 200.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        // 180 m: 20 m from the `continue`, well inside the approach distance.
        p.decide(inputsAt(r, 180.0))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
    }

    @Test
    fun `standing on a crossing is SPAN, and leaving it returns to cruise`() {
        val p = WalkCameraPolicy()
        val r = route(
            600.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(300.0, 70.9), // the real median crossing span
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
        )
        p.decide(inputsAt(r, 100.0))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
        // Approaching it.
        p.decide(inputsAt(r, 260.0))
        assertEquals(WalkCameraRegime.APPROACH, p.currentRegime)
        // On it.
        p.decide(inputsAt(r, 320.0))
        assertEquals(WalkCameraRegime.SPAN, p.currentRegime)
        // Still on it 60 m later — a 70.9 m crossing is most of a minute.
        p.decide(inputsAt(r, 365.0))
        assertEquals(WalkCameraRegime.SPAN, p.currentRegime)
        // Off the far side.
        p.decide(inputsAt(r, 420.0))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
    }

    @Test
    fun `standing on stairs is SPAN`() {
        val p = WalkCameraPolicy()
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(
                    WalkManeuverKind.STAIRS, 200.0,
                    stairs = WalkStairs(distanceM = 5.9), // the real staircase
                ),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        p.decide(inputsAt(r, 201.0))
        assertEquals(WalkCameraRegime.SPAN, p.currentRegime)
    }

    @Test
    fun `a span is framed from its own measured length`() {
        val p = WalkCameraPolicy()
        // The longest real span (156.7 m) must be framed WIDER than a short
        // one — the span zoom is read from the route, not from a constant.
        val longSpan = route(
            600.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(300.0, 156.7),
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
        )
        val wide = p.decide(inputsAt(longSpan, 320.0)) as WalkCameraDecision.Transition
        assertEquals(WalkCameraRegime.SPAN, wide.regime)

        val p2 = WalkCameraPolicy()
        val shortSpan = route(
            600.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(300.0, 8.8), // the shortest real crossing
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
        )
        val tight = p2.decide(inputsAt(shortSpan, 301.0)) as WalkCameraDecision.Transition
        assertTrue(tight.targetZoom > wide.targetZoom,
            "a 156.7 m crossing must be framed wider than an 8.8 m one")
    }

    @Test
    fun `a span zoom is latched on entry, not recomputed while crossing it`() {
        val p = WalkCameraPolicy()
        val r = route(
            600.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(300.0, 156.7),
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
        )
        p.decide(inputsAt(r, 290.0))
        val entry = p.decide(inputsAt(r, 305.0))
        assertTrue(entry is WalkCameraDecision.Transition)
        // Every metre of the next 150 m is still the same crossing.
        var writes = 0
        var d = 306.0
        while (d <= 455.0) {
            if (p.decide(inputsAt(r, d)) is WalkCameraDecision.Transition) writes++
            d += 1.0
        }
        assertEquals(0, writes, "the camera re-wrote while crossing one crossing")
    }

    @Test
    fun `arrival is its own regime and beats a span at the very end`() {
        val p = WalkCameraPolicy()
        // A crossing that runs to the destination: arrival must win, or the
        // camera would hold the crossing framing past the end of the route.
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(360.0, 40.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        p.decide(inputsAt(r, 365.0))
        assertEquals(WalkCameraRegime.SPAN, p.currentRegime)
        // Inside ARRIVAL_M of the end.
        p.decide(inputsAt(r, 385.0))
        assertEquals(WalkCameraRegime.ARRIVAL, p.currentRegime)
    }

    @Test
    fun `arrival framing settles before the navigation layer declares arrival`() {
        // ARRIVAL_M (30 m) is deliberately above the car path's
        // ARRIVAL_RADIUS_M (25 m), so the camera has already moved when the
        // state changes rather than moving at the same instant.
        assertTrue(WalkCameraPolicy.ARRIVAL_M > 25.0)
    }

    @Test
    fun `off-route widens the camera and asserts no bearing`() {
        val p = WalkCameraPolicy()
        val r = turnRoute()
        val cruise = p.decide(inputsAt(r, 100.0)) as WalkCameraDecision.Transition
        assertNotNull(cruise.bearingDeg)

        val off = p.decide(inputsAt(r, 120.0, WalkFollowState.OFF_ROUTE))
            as WalkCameraDecision.Transition
        assertEquals(WalkCameraRegime.OFF_ROUTE, off.regime)
        // WIDER, not tighter: show the ground between the walker and the route.
        assertTrue(off.targetZoom < cruise.targetZoom)
        // And no bearing: the route's direction has stopped describing them.
        assertNull(off.bearingDeg)
    }

    @Test
    fun `an unmatched walker gets no camera opinion at all`() {
        val p = WalkCameraPolicy()
        val d = p.decide(inputsAt(turnRoute(), 0.0, WalkFollowState.ACQUIRING))
        assertEquals(WalkCameraDecision.Unchanged, d)
        assertEquals(WalkCameraRegime.IDLE, p.currentRegime)
    }

    @Test
    fun `the uncertain band keeps following, it does not change the camera`() {
        // UNCERTAIN means "probably still on the route with a poor fix". The
        // camera must keep doing what it was doing; treating it as off-route
        // would flap the framing every time a fix got noisy.
        val p = WalkCameraPolicy()
        val r = turnRoute()
        p.decide(inputsAt(r, 100.0))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
        p.decide(inputsAt(r, 110.0, WalkFollowState.UNCERTAIN))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
    }

    // ------------------------------------------------------- hysteresis

    @Test
    fun `the approach boundary does not oscillate under GPS jitter`() {
        // The defect this prevents: a walker waiting at a kerb roughly 60 m
        // from a turn, with the ~15 m of urban-canyon error this codebase has
        // already measured, would flip APPROACH/CRUISE once a second without
        // the exit band.
        val p = WalkCameraPolicy()
        val r = turnRoute()
        // Walk in to 250 m (50 m from the turn): APPROACH.
        p.decide(inputsAt(r, 250.0))
        assertEquals(WalkCameraRegime.APPROACH, p.currentRegime)

        var writes = 0
        // Jitter across the 60 m entry threshold, both sides, repeatedly.
        for (d in listOf(238.0, 245.0, 232.0, 248.0, 236.0, 244.0)) {
            if (p.decide(inputsAt(r, d)) is WalkCameraDecision.Transition) writes++
            assertEquals(
                WalkCameraRegime.APPROACH, p.currentRegime,
                "jitter at $d m left the approach regime",
            )
        }
        assertEquals(0, writes, "jitter produced $writes camera writes")
    }

    @Test
    fun `leaving the approach needs the exit band, not just the entry`() {
        val p = WalkCameraPolicy()
        val r = turnRoute()
        p.decide(inputsAt(r, 250.0))
        assertEquals(WalkCameraRegime.APPROACH, p.currentRegime)
        // 225 m along = 75 m from the turn: past the 60 m entry, inside the
        // 90 m exit. Still APPROACH.
        p.decide(inputsAt(r, 225.0))
        assertEquals(WalkCameraRegime.APPROACH, p.currentRegime)
        // 205 m along = 95 m from the turn: past the exit band.
        p.decide(inputsAt(r, 205.0))
        assertEquals(WalkCameraRegime.CRUISE, p.currentRegime)
    }

    @Test
    fun `the exit band is wider than the measured GPS error`() {
        // 30 m of band against ~15 m of measured urban-canyon error.
        val band = WalkCameraPolicy.APPROACH_EXIT_M - WalkCameraPolicy.APPROACH_ENTER_M
        assertTrue(band >= 25.0, "hysteresis band is only $band m")
    }

    @Test
    fun `a settled walk writes the camera rarely`() {
        // The whole point of regimes over a continuous function of distance.
        val p = WalkCameraPolicy()
        val r = turnRoute()
        var writes = 0
        var d = 0.0
        while (d <= 600.0) {
            if (p.decide(inputsAt(r, d)) is WalkCameraDecision.Transition) writes++
            d += 1.0
        }
        // 601 fixes, one turn, one arrival: a handful of regime changes.
        assertTrue(writes in 1..8, "camera wrote $writes times over 601 fixes")
    }

    @Test
    fun `a regime change that does not move the scale writes nothing`() {
        // APPROACH and a typical real crossing span both ask for 90 m of
        // look-ahead, so stepping off the approach onto the crossing is a
        // regime change with no visible scale change — and easing the camera
        // from z19.085 to z19.085 would be a wasted write.
        val p = WalkCameraPolicy()
        val r = route(
            600.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(300.0, 49.1), // 49.1 + 25 margin < the 90 m floor
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
        )
        p.decide(inputsAt(r, 260.0))
        assertEquals(WalkCameraRegime.APPROACH, p.currentRegime)
        val onSpan = p.decide(inputsAt(r, 310.0))
        // The regime genuinely advanced...
        assertEquals(WalkCameraRegime.SPAN, p.currentRegime)
        // ...and the camera correctly stayed still.
        assertEquals(WalkCameraDecision.Unchanged, onSpan)
    }

    @Test
    fun `reset forgets the regime so a new route starts clean`() {
        val p = WalkCameraPolicy()
        p.decide(inputsAt(turnRoute(), 250.0))
        assertEquals(WalkCameraRegime.APPROACH, p.currentRegime)
        p.reset()
        assertEquals(WalkCameraRegime.IDLE, p.currentRegime)
    }

    @Test
    fun `identical inputs produce identical decisions`() {
        val r = turnRoute()
        val a = WalkCameraPolicy()
        val b = WalkCameraPolicy()
        var d = 0.0
        while (d <= 600.0) {
            assertEquals(a.decide(inputsAt(r, d)), b.decide(inputsAt(r, d)))
            d += 7.0
        }
    }

    // ------------------------------------------------------------ the bearing

    @Test
    fun `the camera faces the route bearing, never a device heading`() {
        // There is no heading input on WalkCameraInputs at all — the type
        // makes the decision unavailable rather than merely unused. See the
        // WalkCameraPolicy KDoc for why device heading is refused on foot.
        val fields = WalkCameraInputs::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.lowercase().contains("heading") },
            "a device heading must not be reachable by the walking camera")
        val p = WalkCameraPolicy()
        val r = turnRoute()
        val t = p.decide(inputsAt(r, 50.0)) as WalkCameraDecision.Transition
        // The synthetic route runs due north.
        assertEquals(0.0, t.bearingDeg!!, 0.5)
    }

    @Test
    fun `a degenerate route yields no bearing rather than a fabricated one`() {
        val r = WalkRoute.of(
            WalkContract(
                contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general", mode = "foot",
                distanceM = 0.0, durationS = 0.0, stepsM = 0.0, crossingM = 0.0,
                nodes = 0, geometry = emptyList(), segments = WalkSegments(),
                facts = emptyList(), plan = emptyList(), cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )
        assertTrue(r.degenerate)
        assertNull(WalkCameraInputs.of(r.progressAt(0.0)).bearingDeg)
    }

    // --------------------------------------------------------------- the puck

    @Test
    fun `the puck represents the matched position, with its facts`() {
        val r = route(
            600.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                crossingAt(300.0, 70.9),
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
        )
        val progress = r.progressAt(320.0)
        val fix = WalkFix(
            state = WalkFollowState.ON_ROUTE,
            progress = progress,
            position = progress.position,
            offsetM = 3.0,
            movingMs = 1.3,
        )
        val puck = WalkPuck.of(fix)!!
        assertEquals(progress.position, puck.position)
        assertEquals(progress.bearingDeg, puck.bearingDeg)
        assertEquals(progress.segmentIndex, puck.segmentIndex)
        assertTrue(puck.onCrossing)
        assertFalse(puck.onStairs)
        assertEquals(320.0, puck.traversedM, 0.5)
        assertEquals(280.0, puck.remainingM, 0.5)
        assertTrue(puck.confident)
    }

    @Test
    fun `the puck never jumps to the next maneuver`() {
        // The specific fabrication the brief names. The puck is the projection
        // and nothing else, so it must sit where the walker is even when a
        // maneuver is imminent.
        val r = turnRoute()
        val progress = r.progressAt(290.0) // 10 m short of the turn at 300 m
        val fix = WalkFix(
            WalkFollowState.ON_ROUTE, progress, progress.position, 2.0, 1.3,
        )
        val puck = WalkPuck.of(fix)!!
        assertEquals(290.0, puck.traversedM, 0.5)
        // The turn is 10 m ahead and the puck has not moved to it.
        assertEquals(WalkManeuverKind.TURN_LEFT, progress.next?.kind)
        val turnAt = r.index!!.pointAt(300.0)!!.position
        val d = RouteGeometry.haversineM(
            puck.position.lng, puck.position.lat, turnAt.lng, turnAt.lat,
        )
        assertTrue(d > 9.0, "the puck moved to the maneuver: only $d m short of it")
    }

    @Test
    fun `an uncertain fix still draws, but says it is not confident`() {
        val r = turnRoute()
        val progress = r.progressAt(100.0)
        val puck = WalkPuck.of(
            WalkFix(WalkFollowState.UNCERTAIN, progress, progress.position, 30.0, 1.0)
        )!!
        // Still the best available answer...
        assertEquals(progress.position, puck.position)
        // ...and honest about its own quality.
        assertFalse(puck.confident)
    }

    @Test
    fun `an off-route or unmatched fix draws no walking puck`() {
        val r = turnRoute()
        val progress = r.progressAt(100.0)
        // Off-route: the caller must draw the RAW fix, because moving an
        // unmatched position onto a route the walker has left is fabrication.
        assertNull(
            WalkPuck.of(
                WalkFix(WalkFollowState.OFF_ROUTE, progress, progress.position, 80.0, 1.3)
            )
        )
        assertNull(
            WalkPuck.of(WalkFix(WalkFollowState.ACQUIRING, null, null, null, null))
        )
    }

    @Test
    fun `the walking puck is never offset laterally`() {
        assertFalse(WalkPuck.wantsLateralOffset())
        assertEquals(0.0, WalkPuck.CENTRELINE_OFFSET_M, 0.0)
    }
}
