package dev.vector.android

import dev.vector.geo.ManeuverCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who owns the zoom — the V7 Stage 3 D1 regression suite.
 *
 * ## The defect
 *
 * `maybeManeuverCamera` and `maybeAutoZoom` each answered "has the driver
 * pinched?" by comparing the live camera zoom against the SPEED-BAND zoom. That
 * comparison cannot distinguish a finger from Vector's own maneuver-camera
 * write: `ANTICIPATE` lifts the zoom by up to 0.85 and `FRAME` by 1.05–1.55,
 * all past the 0.8 tolerance. So on the GPS fix after the camera moved itself,
 * it read its own move as a takeover, latched `suspended`, and — because
 * `RECOVER` sits behind the same gate — never returned to the band. The Stage 3
 * device run measured it on all five Doha drives: the acceptance lane maneuver
 * got no framing at all and the zoom stayed parked 0.7–1.4 levels off the band
 * for the rest of every route.
 *
 * ## What is tested here rather than in `ManeuverCameraTest`
 *
 * `ManeuverCamera` is handed `driverZoomed` as a fact; it has always honoured
 * it correctly. The defect was in the *derivation* of that fact, which lives in
 * the Activity and is now owned by [CameraGate]. These tests therefore model
 * the Activity's fix loop — gate, camera, and a map whose zoom only changes
 * when something writes it — because that loop is the thing that was wrong.
 */
class CameraOwnershipTest {

    /**
     * The MainActivity fix path, minus MapLibre.
     *
     * `applyCamera` is the one choke point for every Vector-initiated write, so
     * the model has exactly one too. Fixes are 1 s apart, which is the real fix
     * rate and longer than any ease here — [inFlight] covers the other case.
     */
    private class Loop(private val baseZoom: Double = 17.5) {
        val gate = CameraGate()
        val cam = ManeuverCamera()
        var mapZoom = baseZoom
        var nowMs = 10_000L
        val writes = mutableListOf<ManeuverCamera.Decision.Transition>()

        /** Vector moves the camera: the gate learns the zoom Vector asserted. */
        fun vectorWrite(zoom: Double, durationMs: Int) {
            gate.beginTransition(nowMs, durationMs, zoom)
            mapZoom = zoom
        }

        /** The driver pinches: nobody tells the gate anything. */
        fun pinch(toZoom: Double) {
            mapZoom = toZoom
        }

        /** One GPS fix, exactly as `maybeManeuverCamera` runs it. */
        fun fix(input: ManeuverCamera.Input): ManeuverCamera.Decision {
            nowMs += 1_000
            val driverZoomed = gate.isDriverZoom(mapZoom, nowMs)
            val d = cam.update(input, baseZoom, driverZoomed)
            if (d is ManeuverCamera.Decision.Transition) {
                writes += d
                vectorWrite(d.targetZoom, d.durationMs)
            }
            return d
        }

        fun stages(): List<ManeuverCamera.Stage> = writes.map { it.stage }
    }

    /** A complexity-3 approach: the worst case, and the one that self-suspended. */
    private fun hard(distance: Double, index: Int = 1) = ManeuverCamera.Input(
        maneuverIndex = index,
        maneuverType = "slight-right",
        distanceToManeuverM = distance,
        speedMs = 50.0 / 3.6,
        approachLanes = 4,
        laneUseful = true,
        nextManeuverGapM = 45.0,
    )

    // ---- the five required proofs -----------------------------------------

    @Test
    fun `a Vector ANTICIPATE write is not read as a driver zoom`() {
        val l = Loop()
        // Complexity 3 lifts by 0.85 — past the 0.8 tolerance, which is exactly
        // what used to suspend the camera on the following fix.
        l.fix(hard(500.0))
        val a = l.fix(hard(400.0)) as ManeuverCamera.Decision.Transition
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, a.stage)
        assertTrue(
            "the lift must exceed the tolerance or this test proves nothing",
            a.targetZoom - 17.5 > MapCamera.DRIVER_ZOOM_TOLERANCE,
        )
        // The very next fix: the gate must not call Vector's own move a pinch.
        l.nowMs += 1_000
        assertFalse(l.gate.isDriverZoom(l.mapZoom, l.nowMs))
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, l.cam.stage())
    }

    @Test
    fun `a Vector FRAME write is not read as a driver zoom`() {
        val l = Loop()
        l.fix(hard(500.0))
        l.fix(hard(400.0))                       // ANTICIPATE
        // 170 m, not 200: FRAME no longer widens with complexity, so a
        // complexity-3 junction is framed from the same 180 m as any other.
        val f = l.fix(hard(170.0)) as ManeuverCamera.Decision.Transition
        assertEquals(ManeuverCamera.Stage.FRAME, f.stage)
        assertTrue(
            "the FRAME lift must exceed the tolerance or this test proves nothing",
            f.targetZoom - 17.5 > MapCamera.DRIVER_ZOOM_TOLERANCE,
        )
        l.nowMs += 1_000
        assertFalse(l.gate.isDriverZoom(l.mapZoom, l.nowMs))
        assertEquals(ManeuverCamera.Stage.FRAME, l.cam.stage())
    }

    @Test
    fun `a genuine pinch still suspends the maneuver camera`() {
        val l = Loop()
        l.fix(hard(500.0))
        l.fix(hard(400.0))                       // ANTICIPATE, zoom 18.35
        val before = l.writes.size
        // The driver pinches OUT from where Vector left it, by more than the
        // tolerance. Nothing told the gate, which is the whole point.
        l.pinch(l.mapZoom - 1.2)
        l.nowMs += 1_000
        assertTrue(l.gate.isDriverZoom(l.mapZoom, l.nowMs))
        l.fix(hard(200.0))
        assertEquals("a suspended camera must not write", before, l.writes.size)
        assertEquals(ManeuverCamera.Stage.CRUISE, l.cam.stage())
    }

    @Test
    fun `RECOVER still runs after Vector-owned ANTICIPATE and FRAME writes`() {
        val l = Loop()
        l.fix(hard(500.0))
        l.fix(hard(400.0))                       // ANTICIPATE
        l.fix(hard(170.0))                       // FRAME
        l.fix(hard(100.0))                       // COMMIT, no write
        assertEquals(ManeuverCamera.Stage.COMMIT, l.cam.stage())
        // The maneuver completes; the next one is far away.
        val r = l.fix(hard(900.0, index = 2)) as ManeuverCamera.Decision.Transition
        assertEquals(ManeuverCamera.Stage.RECOVER, r.stage)
        assertEquals(17.5, r.targetZoom, 1e-9)
        assertEquals(
            listOf(
                ManeuverCamera.Stage.ANTICIPATE,
                ManeuverCamera.Stage.FRAME,
                ManeuverCamera.Stage.RECOVER,
            ),
            l.stages(),
        )
        // And the camera is back on the band, which is what D1 prevented.
        assertEquals(17.5, l.mapZoom, 1e-9)
    }

    @Test
    fun `suspension stays latched for the rest of a maneuver after a pinch`() {
        val l = Loop()
        l.fix(hard(500.0))
        l.fix(hard(400.0))                       // ANTICIPATE
        l.pinch(l.mapZoom - 1.2)
        l.fix(hard(300.0))                       // suspends
        val after = l.writes.size
        // Every remaining fix of THIS maneuver stays silent, even though the
        // distance keeps demanding later stages.
        for (d in listOf(250.0, 200.0, 150.0, 100.0, 40.0)) l.fix(hard(d))
        assertEquals("the pinch owns the zoom for the whole maneuver", after, l.writes.size)
        // The driver's zoom is untouched.
        assertEquals(18.35 - 1.2, l.mapZoom, 1e-9)
        // Vector never wrote again, so the asserted zoom is still the
        // ANTICIPATE value and the departure from it is still the driver's.
        // Ownership persists until Vector legitimately asserts a zoom again.
        assertTrue(l.gate.isDriverZoom(l.mapZoom, l.nowMs + 1_000))
    }

    // ---- the supporting facts ---------------------------------------------

    @Test
    fun `a transition still in flight is never a driver zoom`() {
        val g = CameraGate()
        g.beginTransition(1_000L, 380, zoom = 19.0)
        // Mid-ease the live zoom is somewhere between old and new; it belongs
        // to Vector's animation, not to anyone's finger.
        assertFalse(g.isDriverZoom(17.9, 1_100L))
        assertFalse(g.isDriverZoom(17.5, 1_379L))
        // Once it lands, the asserted value is the baseline again.
        assertFalse(g.isDriverZoom(19.0, 1_380L))
        assertTrue(g.isDriverZoom(17.5, 1_380L))
    }

    @Test
    fun `with nothing asserted there is no baseline to have departed from`() {
        val g = CameraGate()
        assertFalse(g.isDriverZoom(13.0, 5_000L))
        assertNull(g.assertedZoom())
    }

    @Test
    fun `a position-only transition leaves the zoom ownership alone`() {
        val g = CameraGate()
        g.beginTransition(1_000L, 300, zoom = 18.2)
        g.beginTransition(2_000L, 300, zoom = null)   // pan/bearing move
        assertEquals(18.2, g.assertedZoom()!!, 1e-9)
        assertTrue(g.isDriverZoom(17.0, 3_000L))
    }

    @Test
    fun `a pan gesture clears the follow gate but keeps the zoom baseline`() {
        val g = CameraGate()
        g.beginTransition(1_000L, 900, zoom = 18.2)
        assertFalse(g.mayFollow(1_100L))
        g.cancel()
        assertTrue(g.mayFollow(1_100L))
        // Forgetting the baseline here would make the NEXT pinch invisible.
        assertEquals(18.2, g.assertedZoom()!!, 1e-9)
        assertTrue(g.isDriverZoom(17.0, 1_100L))
    }

    @Test
    fun `the tolerance itself is unchanged - the baseline is what moved`() {
        assertEquals(0.8, MapCamera.DRIVER_ZOOM_TOLERANCE, 1e-9)
        val g = CameraGate()
        g.beginTransition(1_000L, 300, zoom = 18.0)
        // Comfortably either side rather than exactly on it: 0.8 has no exact
        // binary representation, so an "exactly 0.8" assertion tests IEEE 754
        // rather than the policy.
        assertFalse("a 0.7 drift is not a pinch", g.isDriverZoom(17.3, 2_000L))
        assertTrue("a 0.9 drift is", g.isDriverZoom(17.1, 2_000L))
        assertTrue("and it is symmetric", g.isDriverZoom(18.9, 2_000L))
    }
}
