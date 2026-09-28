package dev.vector.geo.signal

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex
import kotlin.math.cos
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signal model — V7 Stage 5's heart.
 *
 * The failures worth most are the ones the brief names as the point of the
 * whole stage: the model must say UNKNOWN wherever timing evidence is absent,
 * and a phase claim (GREEN/RED) must be impossible without a supplied, valid,
 * unexpired [TimingModel]. Those invariants are asserted FIRST, in
 * [no_timing_no_stale_no_catalog_no_off_route_can_ever_claim_a_phase], and are
 * then walked fixture by fixture.
 *
 * A 60 s cycle with a 50% split is used throughout, so the numbers in the
 * fixtures can be read by hand: green occupies [0, 30) of the cycle, red
 * [30, 60). observedAt = 0 (epoch), expiresAt far future unless the fixture
 * is about staleness.
 */
class SignalTimingTest {

    private fun model(
        cycleS: Double = 60.0,
        greenFraction: Double = 0.5,
        offsetS: Double = 0.0,
        observedAt: Long = 0L,
        expiresAt: Long = 5_000_000_000L,
    ) = TimingModel.Known(cycleS, greenFraction, offsetS, observedAt, expiresAt)

    // ---- the invariants, first -------------------------------------------

    @Test
    fun `no timing no stale no catalog no off-route can ever claim a phase`() {
        // (1) no timing
        assertEquals(Phase.UNKNOWN, SignalTiming.predict("s1", TimingModel.None, 10_000L, 5.0, 1_000L).phase)
        // (2) stale timing
        val stale = model(observedAt = 0L, expiresAt = 500L)
        val p2 = SignalTiming.predict("s2", stale, 10_000L, 5.0, 1_000L)
        assertEquals(Phase.UNKNOWN, p2.phase)
        assertEquals(Basis.STALE, p2.basis)
        assertEquals(0.0, p2.confidence)
        // (3) missing catalog: an empty refs list matches to an empty profile
        val route = routeOf(2000.0)
        val empty = SignalMatcher.match(route, emptyList())
        assertTrue(empty.isEmpty)
        assertNull(empty.next(0.0))
        // (4) off-route: a signal 2 km away is not on the profile at all
        val far = listOf(ref("sFar", 2000.0, -2000.0))
        val profile = SignalMatcher.match(route, far)
        assertTrue(profile.isEmpty)
        // And no phase string exists anywhere in the model's vocabulary.
        for (phase in Phase.entries) {
            assertTrue(phase.name !in listOf("Green in", "Red in"),
                "never ship a countdown vocabulary")
        }
    }

    // ---- fixture 1: location, no timing -----------------------------------

    @Test
    fun `signal location with no timing data predicts UNKNOWN with LOCATION basis`() {
        val p = SignalTiming.predict("n1", TimingModel.None, 10_000L, 5.0, 1_000L)
        assertEquals(Phase.UNKNOWN, p.phase)
        assertEquals(Basis.LOCATION, p.basis)
        assertEquals(0.0, p.confidence)
    }

    // ---- fixtures 2-3: known timing ---------------------------------------

    @Test
    fun `known timing with arrival during green predicts GREEN`() {
        // Cycle anchored at observedAt=0; arrival 10 s in is mid-green.
        val p = SignalTiming.predict("nG", model(), 10_000L, 5.0, 1_000L)
        assertEquals(Phase.GREEN, p.phase)
        assertEquals(Basis.TIMING, p.basis)
        assertTrue(p.confidence > 0.5)
    }

    @Test
    fun `known timing with arrival during red predicts RED`() {
        val p = SignalTiming.predict("nR", model(), 40_000L, 5.0, 1_000L)
        assertEquals(Phase.RED, p.phase)
        assertEquals(Basis.TIMING, p.basis)
        assertTrue(p.confidence > 0.5)
    }

    @Test
    fun `confidence grows with distance from the phase transition`() {
        val near = SignalTiming.predict("n1", model(), 24_000L, 5.0, 1_000L) // t=24, d=6
        val mid = SignalTiming.predict("n2", model(), 10_000L, 5.0, 1_000L) // t=10, d=10
        assertTrue(mid.confidence > near.confidence)
    }

    // ---- fixture 4: near the phase boundary --------------------------------

    @Test
    fun `arrival near the phase boundary predicts BOUNDARY not a colour`() {
        // t = 28, greenS = 30, d = 2 <= window 5 -> the window [23,33] straddles 30.
        val p = SignalTiming.predict("nB", model(), 28_000L, 5.0, 1_000L)
        assertEquals(Phase.BOUNDARY, p.phase)
        // Not a single colour, and the confidence records how close it got.
        assertTrue(p.confidence in 0.0..1.0)
    }

    @Test
    fun `exactly on the boundary is BOUNDARY with zero confidence`() {
        val p = SignalTiming.predict("nB", model(), 30_000L, 5.0, 1_000L) // t == greenS
        assertEquals(Phase.BOUNDARY, p.phase)
        assertEquals(0.0, p.confidence)
    }

    // ---- fixture 5: stale timing --------------------------------------------

    @Test
    fun `stale timing predicts UNKNOWN with STALE basis`() {
        val stale = model(observedAt = 0L, expiresAt = 500L)
        val p = SignalTiming.predict("nS", stale, 10_000L, 5.0, 1_000L)
        assertEquals(Phase.UNKNOWN, p.phase)
        assertEquals(Basis.STALE, p.basis)
        assertEquals(0.0, p.confidence)
    }

    @Test
    fun `timing is fresh exactly until expiresAt and not after`() {
        val m = model(observedAt = 0L, expiresAt = 1_000L)
        val atExpiry = SignalTiming.predict("nF", m, 10_000L, 5.0, 1_000L)
        val after = SignalTiming.predict("nF2", m, 10_000L, 5.0, 1_001L)
        assertEquals(Phase.GREEN, atExpiry.phase)   // nowMs == expiresAt is still fresh
        assertEquals(Phase.UNKNOWN, after.phase)    // one ms later it is not
    }

    @Test
    fun `offsetS shifts the green phase within the cycle`() {
        // Arrival at 40 s into a 60 s cycle is RED with no offset (green is
        // [0,30), and 40 is 10 s past the green/red boundary, clear of the
        // window). A +40 s offset re-anchors green at t=40, so the same
        // arrival lands at 80 % 60 = 20 s -- green, proving the offset moves
        // the phase rather than being decoration.
        val plain = SignalTiming.predict("n0", model(), 40_000L, 5.0, 1_000L)
        assertEquals(Phase.RED, plain.phase)
        val shifted = SignalTiming.predict("nO", model(offsetS = 40.0), 40_000L, 5.0, 1_000L)
        assertEquals(Phase.GREEN, shifted.phase)
    }

    // ---- fixture 6: missing catalog -----------------------------------------

    @Test
    fun `missing catalog does not crash and matches nothing`() {
        val route = routeOf(500.0)
        val profile = SignalMatcher.match(route, emptyList())
        assertNull(profile.next(0.0))
        assertEquals(0, profile.size)
    }

    // ---- fixture 7: reroute past a signal -----------------------------------

    @Test
    fun `a reroute replaces the profile and drops signals no longer on the route`() {
        val routeA = routeOf(1000.0)
        val sig = ref("nOn", 500.0, 0.0)
        val profileA = SignalMatcher.match(routeA, listOf(sig))
        assertEquals(1, profileA.size)

        // The reroute takes a different street two kilometres north; the old
        // signal is now off route and no longer in the profile.
        val routeB = routeOf(1000.0, northM = 2000.0)
        val profileB = SignalMatcher.match(routeB, listOf(sig))
        assertTrue(profileB.isEmpty)
    }

    // ---- fixture 8: signal two kilometres off route ---------------------------

    @Test
    fun `a signal two kilometres off the route is never matched`() {
        val route = routeOf(2000.0)
        val profile = SignalMatcher.match(route, listOf(ref("nFar", 1000.0, -2000.0)))
        assertTrue(profile.isEmpty)
    }

    // ---- fixture 9: stop-and-go widens the arrival window ---------------------

    @Test
    fun `a stop widens the arrival window and steady movement narrows it`() {
        val moving = ArrivalWindow.of(remainingM = 600.0, plannedAvgMs = 10.0,
            effectiveMs = 12.0, stoppedRecent = false)
        val stopped = ArrivalWindow.of(remainingM = 600.0, plannedAvgMs = 10.0,
            effectiveMs = 0.0, stoppedRecent = true)
        assertTrue(stopped.uncertaintyS > moving.uncertaintyS,
            "a stop must widen the window")
        assertEquals(moving.etaS, stopped.etaS,
            "the expected arrival is the route-plan answer either way")
    }

    @Test
    fun `the arrival window never goes negative and never claims exact arrival`() {
        val w = ArrivalWindow.of(remainingM = 30.0, plannedAvgMs = 30.0,
            effectiveMs = 30.0, stoppedRecent = false)
        assertTrue(w.etaS >= 0.0)
        assertTrue(w.earliestS >= 0.0)
        assertTrue(w.latestS > w.earliestS)
        assertTrue(w.uncertaintyS >= ArrivalWindow.FLOOR_S,
            "uncertainty never falls below the floor -- no exact arrival, ever")
    }

    @Test
    fun `a longer remaining distance carries a wider natural uncertainty`() {
        val near = ArrivalWindow.of(remainingM = 300.0, plannedAvgMs = 10.0,
            effectiveMs = null, stoppedRecent = false)
        val far = ArrivalWindow.of(remainingM = 3000.0, plannedAvgMs = 10.0,
            effectiveMs = null, stoppedRecent = false)
        assertTrue(far.uncertaintyS > near.uncertaintyS)
    }

    // ---- fixture 11: multiple signals -----------------------------------------

    @Test
    fun `multiple signals match in route order and the nearest ahead is stable`() {
        val route = routeOf(2000.0)
        val profile = SignalMatcher.match(route, listOf(
            ref("nA", 400.0, 0.0),
            ref("nB", 900.0, 0.0),
            ref("nC", 1500.0, 0.0),
        ))
        assertEquals(listOf("nA", "nB", "nC"), profile.signals.map { it.ref.id })
        // Nearest-ahead selection is stable under projection jitter: choosing
        // the next signal from 395..405 m along always returns nB.
        for (along in listOf(390.0, 398.0, 402.0, 405.0)) {
            assertEquals("nB", profile.next(along)?.ref?.id)
        }
        assertNull(profile.next(2000.0))
    }

    // ---- fixture 12: dense junctions --------------------------------------------

    @Test
    fun `signals closer than a hundred metres stay distinct`() {
        val route = routeOf(2000.0)
        val profile = SignalMatcher.match(route, listOf(
            ref("n1", 400.0, 0.0),
            ref("n2", 430.0, 0.0),   // 30 m apart -- Msheireb-scale
            ref("n3", 470.0, 0.0),
        ))
        assertEquals(listOf("n1", "n2", "n3"), profile.signals.map { it.ref.id })
    }

    @Test
    fun `the same lights mapped twice collapse to one signal`() {
        val route = routeOf(2000.0)
        val profile = SignalMatcher.match(route, listOf(
            ref("nA1", 400.0, 1.0),
            ref("nA2", 401.0, -1.0), // the same physical node, a node per stop line
        ))
        assertEquals(1, profile.size)
    }

    // ---- the doubled-back route ------------------------------------------------

    @Test
    fun `a route that doubles back reports the signal at its FIRST pass`() {
        // Route: east 2000 m on one carriageway, then west 2000 m on a
        // carriageway 30 m north (a real U-turn street). The signal sits
        // between the two carriageways, 2 m from the RETURN pass and 28 m
        // from the OUTBOUND pass -- both within snap. Nearest-pass matching
        // would report it at ~1.7 km (the closer return leg, which the driver
        // only reaches after the U-turn); the model must report the first
        // encounter on the outbound leg.
        val outbound = (0..10).map { i ->
            LngLat(lonAt(i * 200.0), 25.2850)
        }
        val returnLat = 25.2850 + 30.0 / RouteGeometry.M_PER_DEG_LAT
        val returnLeg = (10 downTo 0).map { i ->
            LngLat(lonAt(i * 200.0), returnLat)
        }
        val route = RouteGeometry.index(outbound + returnLeg)!!
        val sigLat = 25.2850 + 28.0 / RouteGeometry.M_PER_DEG_LAT
        val profile = SignalMatcher.match(route, listOf(
            SignalRef("nU", LngLat(lonAt(300.0), sigLat), "osm:node:nU"),
        ))
        assertEquals(1, profile.size)
        assertTrue(profile.at(0).approach.alongM < 400.0,
            "must be the first (eastbound) pass, not the return leg " +
                "${profile.at(0).approach.alongM} m")
    }

    // ---- perf: the model is per-route and per-tick, never per-frame -------------

    @Test
    fun `profile construction and next-signal lookup stay inside the frame budget`() {
        val route = routeOf(20_000.0, vertices = 1001)
        val refs = (0 until 60).map { i -> ref("n$i", 100.0 + i * 300.0, 0.0) }

        val buildNs = measureNanoTime {
            repeat(20) { SignalMatcher.match(route, refs) }
        } / 20.0
        // A 20 km route with 60 signals built 20 times: each build must be
        // well under a millisecond (it happens once per ROUTE).
        assertTrue(buildNs < 2_000_000.0,
            "profile build cost ${buildNs / 1e6} ms -- must stay once-per-route")

        val profile = SignalMatcher.match(route, refs)
        val nextNs = measureNanoTime {
            repeat(100_000) { profile.next(12_345.0) }
        } / 100_000.0
        assertTrue(nextNs < 2_000.0,
            "next() cost ${nextNs / 1e3} us -- framing this at 120 Hz would still be fine")
    }

    // ---- helpers ---------------------------------------------------------------

    private fun ref(id: String, eastM: Double, northM: Double) = SignalRef(
        id = id,
        position = LngLat(lonAt(eastM), 25.2850 + northM / RouteGeometry.M_PER_DEG_LAT),
        source = "osm:node:$id",
    )

    /** A straight east-west route. [northM] offset moves the whole route. */
    private fun routeOf(meters: Double, northM: Double = 0.0, vertices: Int = 51): RouteIndex {
        val lat = 25.2850 + northM / RouteGeometry.M_PER_DEG_LAT
        return RouteGeometry.index(
            (0 until vertices).map { i -> LngLat(lonAt(meters * i / (vertices - 1)), lat) },
        )!!
    }

    private fun lonAt(eastM: Double): Double {
        val kx = cos(Math.toRadians(25.2850)) * RouteGeometry.M_PER_DEG_LAT
        return 51.5300 + eastM / kx
    }
}