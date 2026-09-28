package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Walking projection and off-route semantics (V7.4 4C.2).
 *
 * The synthetic route runs due north along a meridian, where a degree of
 * latitude is exactly [RouteGeometry.M_PER_DEG_LAT] metres and — at the
 * equator, where these routes sit — a degree of longitude is the same. So a
 * lateral offset in metres is exact, which is what makes an off-route
 * threshold assertable to the metre.
 *
 * The thresholds themselves are derived from real Qatar geometry; see
 * [WalkFollower]'s KDoc and `V7.4-EVIDENCE/walking_geometry_evidence.py`.
 */
class WalkFollowerTest {

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

    /** A point [alongM] up the route and [offsetM] metres to the east of it. */
    private fun at(alongM: Double, offsetM: Double = 0.0) = LngLat(
        offsetM / RouteGeometry.M_PER_DEG_LAT,
        alongM / RouteGeometry.M_PER_DEG_LAT,
    )

    private fun maneuver(kind: WalkManeuverKind, atM: Double) = WalkManeuver(
        kind = kind, index = (atM / 10.0).toInt(), distanceM = atM,
        distanceToNextM = null, road = null,
    )

    private fun route(
        lengthM: Double,
        plan: List<WalkManeuver> = listOf(
            maneuver(WalkManeuverKind.DEPART, 0.0),
            maneuver(WalkManeuverKind.ARRIVE, lengthM),
        ),
        geometry: List<LngLat> = straight(lengthM),
    ): WalkRoute = WalkRoute.of(
        WalkContract(
            contractVersion = 1,
            walkingProfile = WalkingProfile.GENERAL,
            walkingProfileRaw = "general",
            mode = "foot",
            distanceM = lengthM,
            durationS = lengthM / 1.35,
            stepsM = 0.0,
            crossingM = 0.0,
            nodes = geometry.size,
            geometry = geometry,
            segments = WalkSegments(),
            facts = emptyList(),
            plan = plan,
            cost = null,
            diagnostics = WalkDiagnostics(),
        )
    )

    /** One second of wall clock per fix, which is the real GPS rate. */
    private class Clock(var ms: Long = 100_000L) {
        fun tick(seconds: Double = 1.0): Long {
            ms += (seconds * 1000).toLong()
            return ms
        }
    }

    // ------------------------------------------------------------- the basics

    @Test
    fun `a fix on the route locks and reports ON_ROUTE`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        val fix = f.onFix(at(100.0), c.tick())
        assertEquals(WalkFollowState.ON_ROUTE, fix.state)
        assertTrue(f.isLocked)
        assertEquals(100.0, f.alongM()!!, 0.5)
        assertNotNull(fix.position)
        assertTrue(fix.matched)
    }

    @Test
    fun `forward movement advances the along-route position`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(50.0), c.tick())
        // 1.35 m/s is the backend's own walking pace.
        f.onFix(at(51.35), c.tick())
        val third = f.onFix(at(52.70), c.tick())
        assertEquals(52.70, f.alongM()!!, 0.5)
        assertEquals(WalkFollowState.ON_ROUTE, third.state)
        assertEquals(1.35, third.movingMs!!, 0.1)
    }

    @Test
    fun `a stationary walker stays on route and is not moving`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        // Standing still. The jitter is LATERAL-dominant, which is what a
        // stationary receiver actually produces relative to a route: a fix
        // that wanders sideways projects onto the route as only a fraction of
        // its own error, so along-route drift is small even when the raw error
        // is metres.
        //
        // The honest limit, which this test is shaped by rather than hiding:
        // along-route jitter of more than 0.4 m between two 1 Hz fixes IS
        // arithmetically indistinguishable from a slow walk. That is why
        // `walking` is reported as evidence and never decides the state — see
        // WalkFollower's `movingMs` KDoc.
        for (lateral in listOf(6.0, -8.0, 4.0, -9.0, 3.0)) {
            val fix = f.onFix(at(100.0, lateral), c.tick())
            assertEquals(WalkFollowState.ON_ROUTE, fix.state)
            assertFalse(fix.walking, "lateral jitter of $lateral m read as walking")
        }
    }

    @Test
    fun `a slow walker is still walking`() {
        // 0.5 m/s is a dawdle — a third of the backend's pace — and must
        // clear the movement floor, or off-route detection would never engage
        // for exactly the people most likely to wander.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        val fix = f.onFix(at(100.5), c.tick())
        assertTrue(fix.movingMs!! >= WalkFollower.WALK_MOVING_MS)
    }

    @Test
    fun `movement is measured along the route, never from a GPS speed`() {
        // The walking answer to 4C.1's heading-gate problem: NavSession gates
        // on MOVING_MS = 1.5 m/s and walking pace is 1.35, so a walker is
        // below the car floor essentially always.
        //
        // The walking floor must therefore sit below walking pace, or the gate
        // would exclude the very people it is meant to describe.
        assertTrue(
            WalkFollower.WALK_MOVING_MS < 1.35,
            "the walking movement floor must be below walking pace",
        )

        // And the measurement is along-route progress, not a reported speed:
        // the follower is never given one. Demonstrated behaviourally rather
        // than by reflection (core-geo has no kotlin-reflect on the test
        // classpath, by design — it is a pure-JVM module): two fixes 1.35 m
        // apart along the route, one second apart, read as walking pace with
        // no speed ever supplied.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        val fix = f.onFix(at(101.35), c.tick())
        assertEquals(1.35, fix.movingMs!!, 0.05)
        assertTrue(fix.walking)
    }

    // ------------------------------------------------------------- off-route

    @Test
    fun `a small lateral offset is still on the route`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        // 20 m off: inside the 25 m radius, which is sized against the ~15 m
        // urban-canyon error this codebase has already measured.
        assertEquals(WalkFollowState.ON_ROUTE, f.onFix(at(100.0, 20.0), c.tick()).state)
    }

    @Test
    fun `the uncertain band is neither on nor off the route`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        // 35 m off: past the 25 m on-route radius, inside the 45 m off-route
        // one. Vector genuinely does not know, and says so.
        val fix = f.onFix(at(100.0, 35.0), c.tick())
        assertEquals(WalkFollowState.UNCERTAIN, fix.state)
        // The last good position is HELD and still drawable.
        assertTrue(fix.matched)
        assertNotNull(fix.position)
    }

    @Test
    fun `the uncertain band holds the lock without correcting it`() {
        // A wandering fix must not drag the walker up the path.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        f.onFix(at(160.0, 35.0), c.tick())
        assertEquals(100.0, f.alongM()!!, 0.5)
    }

    @Test
    fun `one noisy fix can never produce an off-route report`() {
        // The defect this exists to stop: a single reflected fix in an urban
        // canyon is the commonest failure in pedestrian GNSS.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        val spike = f.onFix(at(100.0, 120.0), c.tick())
        assertEquals(WalkFollowState.UNCERTAIN, spike.state)
        // And recovering immediately returns to ON_ROUTE.
        assertEquals(WalkFollowState.ON_ROUTE, f.onFix(at(101.4), c.tick()).state)
    }

    @Test
    fun `a sustained departure with movement becomes OFF_ROUTE`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        // Walking away from the route, past 45 m, for longer than 6 s.
        var offset = 50.0
        var last = WalkFollowState.ON_ROUTE
        repeat(8) {
            last = f.onFix(at(100.0, offset), c.tick()).state
            offset += 5.0
        }
        assertEquals(WalkFollowState.OFF_ROUTE, last)
    }

    @Test
    fun `the persistence window is honoured, not merely the distance`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        // Three seconds out: past the radius, inside the persistence window.
        var s: WalkFollowState = WalkFollowState.ON_ROUTE
        repeat(3) {
            s = f.onFix(at(100.0, 60.0 + it * 5), c.tick()).state
        }
        assertEquals(WalkFollowState.UNCERTAIN, s)
        // Past six seconds, still moving away: now it is real.
        repeat(5) {
            s = f.onFix(at(100.0, 80.0 + it * 5), c.tick()).state
        }
        assertEquals(WalkFollowState.OFF_ROUTE, s)
    }

    @Test
    fun `a standstill far from the route is off-route, but not walking`() {
        // ## The design error this test exists because of
        //
        // The first version of this class required movement evidence for the
        // OFF_ROUTE *state*, and this test asserted that — a standstill far
        // from the route stayed UNCERTAIN forever. It was wrong, and wrong in
        // the way that matters: a walker who steps PERPENDICULARLY off the
        // route makes no along-route progress at all, so their movement rate
        // is zero and they could never be reported off-route. That is exactly
        // the person who is off-route.
        //
        // The car architecture already draws the line in the right place and
        // this now matches it: `RouteTracker` declares OffRoute on pure
        // distance, and the movement test lives one layer up in
        // `NavSession.rerouteCouldHelp`, where it gates the ACTION rather than
        // the state. A stationary deviation is still a deviation; it is just
        // not worth a route request.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        var fix = f.onFix(at(100.0, 100.0), c.tick())
        repeat(20) { fix = f.onFix(at(100.0, 100.0), c.tick()) }

        // The STATE is the honest geometric fact: they are not on the route.
        assertEquals(WalkFollowState.OFF_ROUTE, fix.state)
        // And the EVIDENCE 4C.4 will gate its rerouting on says they are not
        // going anywhere, so a route request would be pointless.
        assertFalse(fix.walking, "a standstill must not read as walking")
    }

    @Test
    fun `stepping sideways off the route is detected, with no forward progress`() {
        // The concrete case the rule above protects: someone who walks off the
        // side of the path makes zero along-route progress the whole time.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        var fix = f.onFix(at(100.0, 60.0), c.tick())
        // Straight out to the side, same along-route position throughout.
        var lateral = 60.0
        repeat(8) {
            lateral += 8.0
            fix = f.onFix(at(100.0, lateral), c.tick())
        }
        assertEquals(WalkFollowState.OFF_ROUTE, fix.state)
        // Zero along-route movement, which is why it could never have been
        // the thing that decided the state.
        assertTrue(fix.movingMs!! < WalkFollower.WALK_MOVING_MS)
    }

    @Test
    fun `rejoining the route further along recovers the lock`() {
        // The defect this pins, found by review: a confirmed departure used to
        // KEEP the lock, and while locked the projection is constrained to
        // [-20, +40] m around the last known position. A walker who left the
        // route, walked 200 m and rejoined would never be found inside that
        // window and would stay off-route for the rest of the walk.
        val f = WalkFollower(route(600.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        var s: WalkFollowState = WalkFollowState.ON_ROUTE
        var offset = 50.0
        repeat(9) {
            s = f.onFix(at(100.0, offset), c.tick()).state
            offset += 6.0
        }
        assertEquals(WalkFollowState.OFF_ROUTE, s)
        assertFalse(f.isLocked)
        // Now rejoin 200 m further along — far outside any locked window.
        val back = f.onFix(at(300.0), c.tick())
        assertEquals(WalkFollowState.ON_ROUTE, back.state)
        assertEquals(300.0, f.alongM()!!, 1.0)
    }

    @Test
    fun `a poor fix widens the threshold rather than tripping it`() {
        // Bad GPS must never manufacture a departure.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick(), accuracyM = 5.0)
        // 50 m off with a 55 m claimed accuracy: the receiver is telling us it
        // cannot place this person, so it cannot be evidence that they left.
        var s: WalkFollowState = WalkFollowState.ON_ROUTE
        repeat(10) {
            s = f.onFix(at(100.0 + it * 2.0, 50.0), c.tick(), accuracyM = 55.0).state
        }
        assertFalse(
            s == WalkFollowState.OFF_ROUTE,
            "a 55 m-accuracy fix produced an off-route report",
        )
    }

    @Test
    fun `a claimed accuracy can never narrow the thresholds`() {
        // A receiver claiming 2 m does not license a tighter claim than the
        // geometry evidence supports: the error that matters is the map's and
        // the walker's too.
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick(), accuracyM = 2.0)
        // 20 m off, with an optimistic accuracy claim: still ON_ROUTE.
        assertEquals(
            WalkFollowState.ON_ROUTE,
            f.onFix(at(100.0, 20.0), c.tick(), accuracyM = 2.0).state,
        )
    }

    @Test
    fun `an absurd accuracy claim cannot mute off-route detection forever`() {
        // Without the cap, a phone reporting 500 m of accuracy could never be
        // off route — a safety valve turned into a mute button.
        val f = WalkFollower(route(2000.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        var s: WalkFollowState = WalkFollowState.ON_ROUTE
        var off = 200.0
        repeat(12) {
            s = f.onFix(at(100.0, off), c.tick(), accuracyM = 500.0).state
            off += 10.0
        }
        assertEquals(WalkFollowState.OFF_ROUTE, s)
    }

    // ------------------------------------------------ doubled-back geometry

    @Test
    fun `a doubled-back route does not jump to the return leg`() {
        // The walking form of the 152 m backward jump documented on
        // RouteIndex.project. The route goes 200 m north, then back south 16 m
        // to the east — the worst self-approach measured on any real Qatar
        // walking fixture (16.1 m, the stairs walk).
        val north = ArrayList<LngLat>()
        var d = 0.0
        while (d <= 200.0) {
            north.add(at(d))
            d += 10.0
        }
        val south = ArrayList<LngLat>()
        d = 200.0
        while (d >= 0.0) {
            south.add(at(d, 16.1))
            d -= 10.0
        }
        val r = route(
            400.0,
            plan = listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.UTURN, 200.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
            geometry = north + south,
        )
        val f = WalkFollower(r)
        val c = Clock()
        // Walking north up the outbound leg. The return leg is 16.1 m to the
        // east the whole way — well inside the 25 m on-route radius, so the
        // ONLY thing preventing a jump is the search window.
        f.onFix(at(50.0), c.tick())
        var lastAlong = f.alongM()!!
        d = 55.0
        while (d <= 190.0) {
            // Drifting east, toward the return leg, as an urban canyon does.
            f.onFix(at(d, 8.0), c.tick())
            val now = f.alongM()!!
            assertTrue(
                now >= lastAlong - 1.0,
                "position jumped backwards from $lastAlong to $now at $d m",
            )
            // And it must not have latched onto the return leg, which at this
            // point would read as an along-distance well past 200 m.
            assertTrue(now < 210.0, "jumped to the return leg: $now m at $d m")
            lastAlong = now
            d += 5.0
        }
    }

    @Test
    fun `the search window is derived from walking pace, not vehicle pace`() {
        // The car's 250 m forward window is "33 m/s for the 3 s of dead
        // reckoning". The same derivation at 1.35 m/s is 4.05 m, so 250 m on
        // foot is three minutes of walking — and it re-admits exactly the
        // doubled-back geometry the backward window is tightened to exclude.
        assertTrue(WalkFollower.SEARCH_FWD_M < 250.0)
        assertTrue(WalkFollower.SEARCH_BACK_M < 60.0)
        // Forward still covers far more than a walker can cover between fixes.
        assertTrue(WalkFollower.SEARCH_FWD_M > 1.35 * 3.0 * 5)
        // Backward stays under the 16.1 m worst measured self-approach plus a
        // margin, so it cannot reach a doubled-back section 50 m away along
        // the route.
        assertTrue(WalkFollower.SEARCH_BACK_M <= 20.0)
    }

    @Test
    fun `a GPS jump forward beyond the window does not drag the walker`() {
        val f = WalkFollower(route(600.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        // A 300 m jump in one second is not a person walking.
        f.onFix(at(400.0), c.tick())
        assertTrue(
            f.alongM()!! < 100.0 + WalkFollower.SEARCH_FWD_M + 1.0,
            "a GPS jump moved the walker ${f.alongM()} m",
        )
    }

    // ------------------------------------------------------------ degenerate

    @Test
    fun `a degenerate route never matches and never crashes`() {
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
        val f = WalkFollower(r)
        val fix = f.onFix(at(0.0), 1000L)
        assertEquals(WalkFollowState.ACQUIRING, fix.state)
        assertNull(fix.position)
        assertFalse(fix.matched)
        assertNull(f.alongM())
    }

    @Test
    fun `before any fix the follower is acquiring, not on route`() {
        val f = WalkFollower(route(400.0))
        assertEquals(WalkFollowState.ACQUIRING, f.followState)
        assertNull(f.alongM())
        assertFalse(f.isLocked)
    }

    @Test
    fun `a first fix far from the route matches nothing rather than inventing`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        val fix = f.onFix(at(100.0, 300.0), c.tick())
        assertNull(fix.position)
        assertFalse(fix.matched)
        // And it is not an accusation on the first fix either: persistence
        // and movement are still required.
        assertEquals(WalkFollowState.UNCERTAIN, fix.state)
    }

    @Test
    fun `reset returns the follower to acquiring`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(100.0), c.tick())
        assertTrue(f.isLocked)
        f.reset()
        assertFalse(f.isLocked)
        assertEquals(WalkFollowState.ACQUIRING, f.followState)
        assertNull(f.alongM())
    }

    // ------------------------------------------------------------ progression

    @Test
    fun `the follower drives the 4C1 progress model`() {
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.TURN_LEFT, 200.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val f = WalkFollower(r)
        val c = Clock()
        val early = f.onFix(at(50.0), c.tick())
        assertEquals(WalkManeuverKind.TURN_LEFT, early.progress!!.next?.kind)
        assertEquals(150.0, early.progress!!.distanceToNextM!!, 1.0)

        // Walked, not teleported. A 200 m jump in one fix is outside
        // SEARCH_FWD_M by design — the window is what stops a GPS jump
        // dragging the walker — so the route has to be covered at a pace a
        // person could produce.
        var d = 60.0
        var last = early
        while (d <= 250.0) {
            last = f.onFix(at(d), c.tick(seconds = 8.0))
            d += 10.0
        }
        assertEquals(WalkManeuverKind.ARRIVE, last.progress!!.next?.kind)
        assertEquals(250.0, f.alongM()!!, 1.0)
    }

    @Test
    fun `arrival is reached with nothing left`() {
        val f = WalkFollower(route(400.0))
        val c = Clock()
        f.onFix(at(390.0), c.tick())
        val end = f.onFix(at(400.0), c.tick())
        assertEquals(0.0, end.progress!!.remainingM, 1.0)
        assertEquals(WalkManeuverKind.ARRIVE, end.progress!!.active?.kind)
    }

    @Test
    fun `identical fix sequences produce identical state` () {
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.TURN_LEFT, 200.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val a = WalkFollower(r)
        val b = WalkFollower(r)
        val ca = Clock()
        val cb = Clock()
        var d = 0.0
        while (d <= 400.0) {
            val fa = a.onFix(at(d, 3.0), ca.tick())
            val fb = b.onFix(at(d, 3.0), cb.tick())
            assertEquals(fa.state, fb.state)
            assertEquals(fa.position, fb.position)
            assertEquals(fa.progress?.alongM, fb.progress?.alongM)
            d += 13.0
        }
    }
}
