package dev.vector.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The dead-reckoning state machine (ADR-0075).
 *
 * These are the tests that make "fluidity" an assertion instead of an opinion:
 * between two 1 Hz fixes the puck must keep moving, and it must stop moving when
 * the fixes stop arriving.
 */
class RouteTrackerTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val mpd = RouteGeometry.M_PER_DEG_LAT
    private val kx = cos(Math.toRadians(lat)) * mpd

    /** A straight 2 km east run, shape points every 100 m. */
    private val route = (0..20).map { LngLat(lng0 + (it * 100.0) / kx, lat) }

    private fun at(m: Double) = LngLat(lng0 + m / kx, lat)
    private fun secs(s: Double) = (s * 1e9).toLong()

    @Test
    fun `idle until a route is set`() {
        val t = RouteTracker()
        assertIs<RouteTracker.State.Idle>(t.onFix(at(0.0), 10.0, secs(0.0)))
        assertNull(t.onFrame(secs(0.1)))
    }

    @Test
    fun `a fix on the route locks and reports along-distance`() {
        val t = RouteTracker()
        t.setRoute(route)
        val s = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(250.0), 16.7, secs(0.0)))
        assertTrue(abs(s.alongM - 250.0) < 2.0, "alongM ${s.alongM}")
        assertTrue(t.isLocked)
        assertTrue(abs(s.remainingM - 1750.0) < 5.0, "remaining ${s.remainingM}")
    }

    @Test
    fun `the puck keeps moving BETWEEN fixes - this is the fluidity guarantee`() {
        val t = RouteTracker()
        t.setRoute(route)
        t.onFix(at(0.0), 20.0, secs(0.0))   // 20 m/s = 72 km/h
        t.onFrame(secs(0.0))                 // establish the frame clock

        val a = assertNotNull(t.onFrame(secs(0.25)))
        val b = assertNotNull(t.onFrame(secs(0.50)))
        val c = assertNotNull(t.onFrame(secs(0.75)))

        assertTrue(a.alongM > 0.0, "no motion after 250 ms")
        assertTrue(b.alongM > a.alongM, "puck stalled between frames")
        assertTrue(c.alongM > b.alongM, "puck stalled between frames")
        // 20 m/s for 0.75 s is 15 m, and each frame step is capped at 0.25 s.
        assertTrue(abs(c.alongM - 15.0) < 1.0, "expected ~15 m, got ${c.alongM}")
    }

    @Test
    fun `a correcting fix nudges rather than snapping`() {
        val t = RouteTracker(correctGain = 0.25)
        t.setRoute(route)
        t.onFix(at(100.0), 0.0, secs(0.0))
        // Next fix claims we are 100 m further on. A snap would jump the puck the
        // whole way and reintroduce the once-a-second lurch.
        val s = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(200.0), 0.0, secs(1.0)))
        assertTrue(s.alongM > 100.0 && s.alongM < 200.0, "expected a partial move, got ${s.alongM}")
        assertTrue(abs(s.alongM - 125.0) < 2.0, "expected ~125 m with gain 0.25, got ${s.alongM}")
    }

    @Test
    fun `repeated corrections converge on the measurement`() {
        val t = RouteTracker(correctGain = 0.25)
        t.setRoute(route)
        t.onFix(at(100.0), 0.0, secs(0.0))
        repeat(20) { t.onFix(at(200.0), 0.0, secs(1.0 + it)) }
        val s = assertIs<RouteTracker.State.OnRoute>(t.state)
        assertTrue(abs(s.alongM - 200.0) < 1.0, "should have converged, got ${s.alongM}")
    }

    @Test
    fun `a far fix reports off-route and drops the lock`() {
        val t = RouteTracker(offRouteM = 60.0)
        t.setRoute(route)
        t.onFix(at(500.0), 15.0, secs(0.0))
        assertTrue(t.isLocked)
        val s = assertIs<RouteTracker.State.OffRoute>(
            t.onFix(LngLat(lng0 + 500.0 / kx, lat + 150.0 / mpd), 15.0, secs(1.0))
        )
        assertTrue(s.offsetM > 60.0, "offset ${s.offsetM}")
        assertTrue(!t.isLocked, "lock must drop when off route")
        assertNull(t.onFrame(secs(1.1)), "must not dead-reckon while off route")
    }

    @Test
    fun `a 150 m deviation reroutes where the old web threshold would not`() {
        // The concrete regression: vector-web's rule did not fire until ~3.1 km.
        val t = RouteTracker(offRouteM = 60.0)
        t.setRoute(route)
        t.onFix(at(500.0), 15.0, secs(0.0))
        assertIs<RouteTracker.State.OffRoute>(
            t.onFix(LngLat(lng0 + 500.0 / kx, lat + 150.0 / mpd), 15.0, secs(1.0))
        )
    }

    @Test
    fun `a moderate GPS excursion holds the lock without correcting or rerouting`() {
        // Between snapMax and offRoute: an urban-canyon wobble should neither drag
        // the puck sideways nor trigger a reroute.
        val t = RouteTracker(snapMaxM = 40.0, offRouteM = 60.0)
        t.setRoute(route)
        val first = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(500.0), 0.0, secs(0.0)))
        // 50 m sideways, at the same point along the route.
        //
        // It used to be 50 m sideways and FOUR HUNDRED METRES further along, in
        // the same second — 1 440 km/h, which is not a wobble. The test passed
        // anyway, because the untrusted-fix branch leaves `alongM` alone
        // regardless of how far ahead the fix claims to be, so its assertion
        // could not tell the two situations apart. V5 added a projection search
        // window (see RouteIndex.project) and a plausibility gate
        // (see FixGate), both of which correctly refuse the old coordinate, and
        // that is what exposed it.
        val wobble = assertIs<RouteTracker.State.OnRoute>(
            t.onFix(LngLat(lng0 + 500.0 / kx, lat + 50.0 / mpd), 0.0, secs(1.0))
        )
        assertTrue(t.isLocked, "lock should survive a 50 m excursion")
        assertTrue(
            abs(wobble.alongM - first.alongM) < 1.0,
            "an untrusted fix must not move alongM: ${first.alongM} -> ${wobble.alongM}"
        )
    }

    @Test
    fun `dead reckoning stops when the fixes stop - a tunnel freezes the puck`() {
        val t = RouteTracker(reckonMaxS = 3.0)
        t.setRoute(route)
        t.onFix(at(0.0), 20.0, secs(0.0))
        assertNotNull(t.onFrame(secs(1.0)), "should still reckon 1 s after a fix")
        assertNotNull(t.onFrame(secs(2.9)), "should still reckon 2.9 s after a fix")
        assertNull(t.onFrame(secs(3.5)), "must stop reckoning past reckonMaxS")
        assertTrue(!t.isLocked)
    }

    @Test
    fun `a missing receiver speed retains the last known speed`() {
        // Receivers commonly omit speed below walking pace; null means "unknown",
        // not "stopped", and treating it as zero would stall the puck mid-drive.
        val t = RouteTracker()
        t.setRoute(route)
        t.onFix(at(0.0), 20.0, secs(0.0))
        val s = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(20.0), null, secs(1.0)))
        assertEquals(20.0, s.speedMs)
    }

    @Test
    fun `the puck never runs past the end of the route`() {
        val t = RouteTracker()
        t.setRoute(route)
        t.onFix(at(1990.0), 50.0, secs(0.0))
        t.onFrame(secs(0.0))
        repeat(40) { t.onFrame(secs(0.1 * (it + 1))) }
        val s = assertIs<RouteTracker.State.OnRoute>(t.state)
        assertTrue(s.alongM <= 2000.0 + 1e-6, "overran the route: ${s.alongM}")
        assertTrue(s.remainingM >= -1e-6)
    }

    @Test
    fun `clearing the route returns to idle`() {
        val t = RouteTracker()
        t.setRoute(route)
        t.onFix(at(100.0), 10.0, secs(0.0))
        t.clearRoute()
        assertIs<RouteTracker.State.Idle>(t.state)
        assertNull(t.onFrame(secs(0.1)))
    }
    // ---- the projection search window --------------------------------------
    //
    // V5. On a route that doubles back on itself, the globally nearest segment
    // is sometimes the opposite carriageway of a section already driven, and a
    // few metres of drift then teleports the along-route position backwards by
    // the length of the doubling. Measured on a real Doha route: 151.8 m
    // backwards, the distance remaining GREW by 152 m, and the instruction
    // reverted to a turn the driver had already made.

    /** Out 500 m east, U-turn, and back — the shape of the real defect. */
    private val doubled = (0..10).map { at(it * 50.0) } +
        (10 downTo 0).map { LngLat(lng0 + (it * 50.0) / kx, lat + 12.0 / mpd) }

    @Test
    fun `drift near a doubled-back section does not move the vehicle backwards`() {
        val t = RouteTracker()
        t.setRoute(doubled)
        // Drive the outbound leg to 400 m.
        var last: RouteTracker.State.OnRoute? = null
        for (m in 0..400 step 25) {
            last = assertIs(t.onFix(at(m.toDouble()), 12.0, secs(m / 12.0)))
        }
        val before = last!!.alongM
        // Short of 400 m by design: `correctGain` eases toward each measurement
        // rather than snapping to it, and this test feeds fixes without running
        // the frame loop that would dead-reckon between them.
        assertTrue(before > 300.0, "did not get down the outbound leg: $before")
        // Now one fix drifting 8 m north — toward the return carriageway, which
        // is 12 m away and about 700 m further along the route.
        val drifted = assertIs<RouteTracker.State.OnRoute>(
            t.onFix(LngLat(lng0 + 400.0 / kx, lat + 8.0 / mpd), 12.0, secs(400 / 12.0 + 1))
        )
        assertTrue(
            abs(drifted.alongM - before) < 30.0,
            "8 m of drift moved the along-route position from $before to ${drifted.alongM}"
        )
    }

    @Test
    fun `the window does not stop the vehicle advancing at motorway speed`() {
        // 33 m/s with a dropped update is ~66 m between fixes; the forward
        // window has to be comfortable with that or the lock drops on the
        // expressway, which would be a worse defect than the one it fixes.
        val t = RouteTracker()
        t.setRoute(route)
        var last = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(0.0), 33.0, secs(0.0)))
        for (i in 1..12) {
            last = assertIs(t.onFix(at(i * 66.0), 33.0, secs(i * 2.0)))
        }
        assertTrue(t.isLocked, "the lock dropped at motorway speed")
        // 792 m of measurements, less the easing lag of a gain-limited
        // correction with no frames in between.
        assertTrue(last.alongM > 550.0, "only reached ${last.alongM} m in 24 s at 33 m/s")
    }

    @Test
    fun `a jump along the route is not reported as a deviation`() {
        // The escape hatch. If the window says off-route but the whole route
        // says on-route, believe the route: a window must never be able to
        // invent a deviation out of a position that is plainly on the line.
        val t = RouteTracker()
        t.setRoute(route)
        t.onFix(at(0.0), 15.0, secs(0.0))
        val jumped = t.onFix(at(1500.0), 15.0, secs(1.0))
        assertIs<RouteTracker.State.OnRoute>(jumped)
        assertTrue(t.isLocked, "the lock was dropped by a jump the route can explain")
    }

    @Test
    fun `after the lock drops, a distant fix re-locks outright`() {
        // The real mechanism for a large legitimate jump: it comes with time.
        // An app backgrounded on the F Ring and resumed twenty kilometres later
        // has no fixes in between, so the lock is gone and the next fix is
        // accepted as a measurement rather than eased toward — which is why the
        // gain-limited correction above is not a problem in production.
        val t = RouteTracker(reckonMaxS = 3.0)
        t.setRoute(route)
        t.onFix(at(0.0), 15.0, secs(0.0))
        assertNull(t.onFrame(secs(10.0)), "the lock should be gone after 10 s of nothing")
        val back = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(1500.0), 15.0, secs(11.0)))
        assertTrue(abs(back.alongM - 1500.0) < 5.0,
            "a re-lock must take the measurement, not ease toward it: ${back.alongM}")
    }

    // ---- two passes of the same street --------------------------------------
    //
    // Found on the S24 in scenario F, and NOT by the simulation. The first
    // route the live router returns for Souq Waqif -> West Bay U-turns 329 m in
    // and comes back up the same street, so for the first few hundred metres
    // the route has two passes a few metres apart. An unconstrained projection
    // takes the nearest segment, and with urban-canyon drift the nearest
    // segment is sometimes the RETURN leg — which put the vehicle half a
    // kilometre ahead of itself and had the driver rerouted one second after
    // pressing Start.

    /** Out 500 m east, U-turn, back 500 m — 12 m apart, as a real U-turn is. */
    private val twoPass = (0..10).map { at(it * 50.0) } +
        (10 downTo 0).map { LngLat(lng0 + (it * 50.0) / kx, lat + 12.0 / mpd) }

    @Test
    fun `a heading picks the outbound pass`() {
        val t = RouteTracker()
        t.setRoute(twoPass)
        // 100 m along, drifted 7 m north — nearer the RETURN leg than the
        // outbound one, which is what the drift does.
        val fix = LngLat(lng0 + 100.0 / kx, lat + 7.0 / mpd)
        // Heading east: the outbound leg's direction.
        val s = assertIs<RouteTracker.State.OnRoute>(t.onFix(fix, 12.0, secs(0.0), 90.0))
        assertTrue(s.alongM < 200.0, "locked at ${s.alongM} m — that is the return leg")
    }

    @Test
    fun `a heading picks the return pass`() {
        val t = RouteTracker()
        t.setRoute(twoPass)
        val fix = LngLat(lng0 + 100.0 / kx, lat + 5.0 / mpd)
        // Heading west: only the return leg goes that way.
        val s = assertIs<RouteTracker.State.OnRoute>(t.onFix(fix, 12.0, secs(0.0), 270.0))
        assertTrue(s.alongM > 800.0, "locked at ${s.alongM} m — that is the outbound leg")
    }

    @Test
    fun `no heading falls back to the nearest segment`() {
        // A parked car's GPS heading is close to random, so the caller withholds
        // it — and the behaviour must be exactly what it was before.
        val t = RouteTracker()
        t.setRoute(twoPass)
        val fix = LngLat(lng0 + 100.0 / kx, lat + 11.0 / mpd)
        val s = assertIs<RouteTracker.State.OnRoute>(t.onFix(fix, 12.0, secs(0.0), null))
        // 11 m north of the outbound leg is 1 m from the return leg.
        assertTrue(s.alongM > 800.0, "nearest-segment should have chosen the return leg")
    }

    @Test
    fun `a heading cannot drag the lock somewhere far away`() {
        // The candidates are filtered to AMBIGUOUS_M first, so heading only ever
        // chooses between places the tracker would have snapped to anyway. A
        // fix 300 m off the route with a perfectly matching heading is still
        // off the route.
        val t = RouteTracker()
        t.setRoute(twoPass)
        val far = LngLat(lng0 + 100.0 / kx, lat + 300.0 / mpd)
        assertIs<RouteTracker.State.OffRoute>(t.onFix(far, 12.0, secs(0.0), 90.0))
    }

    @Test
    fun `a heading is ignored once a lock is held`() {
        // While locked the search window is the constraint and it is a stronger
        // one; a heading that disagreed with it would reintroduce the jump the
        // window exists to prevent.
        val t = RouteTracker()
        t.setRoute(twoPass)
        var last = assertIs<RouteTracker.State.OnRoute>(t.onFix(at(0.0), 12.0, secs(0.0), 90.0))
        for (i in 1..8) {
            last = assertIs(t.onFix(at(i * 25.0), 12.0, secs(i * 2.0), 90.0))
        }
        // Now a fix nearer the return leg, with the RETURN heading. The window
        // must win.
        val drifted = assertIs<RouteTracker.State.OnRoute>(
            t.onFix(LngLat(lng0 + 200.0 / kx, lat + 10.0 / mpd), 12.0, secs(18.0), 270.0)
        )
        assertTrue(drifted.alongM < 400.0,
            "the window was overridden by a heading: ${drifted.alongM}")
    }

}
