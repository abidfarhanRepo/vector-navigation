package dev.vector.android

import dev.vector.geo.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera policy.
 *
 * Two of these tests exist because the behaviour they pin was a defect a driver
 * would hit within a minute of setting off, and no test could have caught it
 * while the policy was four ternaries inside an Activity that needs a device to
 * instantiate:
 *
 * * `a followed frame never asserts a zoom` — the old rule was
 *   `zoom.coerceAtLeast(16.5)`, evaluated on EVERY followed frame. Pinching out
 *   during navigation was undone within ~8 ms, so a driver could not look ahead.
 * * `a rotate gesture keeps position following` — a rotate was not observed at
 *   all, so the follow camera overwrote the bearing on the next frame and the
 *   gesture appeared broken.
 */
class MapCameraTest {

    private val here = LngLat(51.5310, 25.2854)

    /** An arbitrary wall clock. Only differences from it are ever meaningful. */
    private val T0 = 1_757_780_000_000L

    private fun nav(
        orientation: MapOrientation = MapOrientation.HEADING_UP,
        perspective: MapPerspective = MapPerspective.TILTED,
        mode: CameraMode = CameraMode.FOLLOW,
        manual: Double? = null,
        takenOverAtMs: Long? = null,
    ) = CameraState(mode, orientation, perspective, manual, takenOverAtMs)

    // ---- zoom --------------------------------------------------------------

    @Test
    fun `a followed frame never asserts a zoom`() {
        // The whole point of CameraTarget.zoom being nullable. If this ever
        // returns a number, pinch-to-zoom during navigation stops working and
        // nothing else in the app will report it.
        val t = MapCamera.follow(nav(), Phase.NAVIGATING, here, 90.0)
        assertNotNull(t)
        assertNull("a follow frame must leave the driver's zoom alone", t!!.zoom)
    }

    @Test
    fun `recentering does assert a zoom, because it is a command`() {
        val z = MapCamera.transitionZoom(Phase.NAVIGATING, null, autoZoomEnabled = false)
        val t = MapCamera.recenter(nav(), Phase.NAVIGATING, here, 90.0, zoom = z)
        assertEquals(MapCamera.NAV_ZOOM, t.zoom!!, 1e-9)
    }

    @Test
    fun `recentering outside navigation uses the explore zoom`() {
        val z = MapCamera.transitionZoom(Phase.EXPLORE, null, autoZoomEnabled = true)
        val t = MapCamera.recenter(nav(), Phase.EXPLORE, here, 90.0, zoom = z)
        assertEquals(MapCamera.EXPLORE_ZOOM, t.zoom!!, 1e-9)
    }

    @Test
    fun `an app-decided recenter leaves the driver's zoom alone`() {
        // The 2026-09-14 regression. `autoResume` fires every AUTO_RESUME_MS
        // while the camera is taken; when it routed through a recenter that
        // hardcoded NAV_ZOOM, a deliberate pinch was undone 10 s later, and
        // again 10 s after that, for the whole journey. Auto-resume owns the
        // POSITION -- putting the map back in front of the car -- and nothing
        // else.
        val t = MapCamera.recenter(nav(), Phase.NAVIGATING, here, 90.0, zoom = null)
        assertNull("auto-resume must not assert a zoom the driver did not ask for",
                   t.zoom)
    }

    @Test
    fun `a driver-asked recenter at speed lands on the band, not on NAV_ZOOM`() {
        // Second half of the same regression. Restoring 16.5 while doing
        // 110 km/h put the camera 1.3 steps from the band `maybeAutoZoom`
        // wanted; that exceeds its 0.8 tolerance, so it concluded the driver
        // owned the zoom and stopped correcting for the rest of the drive.
        val band = MapCamera.transitionZoom(Phase.NAVIGATING, 110, autoZoomEnabled = true)
        val t = MapCamera.recenter(nav(), Phase.NAVIGATING, here, 90.0, zoom = band)
        assertEquals(MapCamera.autoZoom(110)!!, t.zoom!!, 1e-9)
        assertTrue("a motorway recenter should not snap back to the default nav zoom",
                   Math.abs(t.zoom!! - MapCamera.NAV_ZOOM) > 0.8)
    }

    @Test
    fun `turning auto-zoom off keeps a recenter on the default nav zoom`() {
        val z = MapCamera.transitionZoom(Phase.NAVIGATING, 110, autoZoomEnabled = false)
        val t = MapCamera.recenter(nav(), Phase.NAVIGATING, here, 90.0, zoom = z)
        assertEquals(MapCamera.NAV_ZOOM, t.zoom!!, 1e-9)
    }

    // ---- orientation -------------------------------------------------------

    @Test
    fun `heading up faces the direction of travel`() {
        val t = MapCamera.follow(nav(orientation = MapOrientation.HEADING_UP),
                                 Phase.NAVIGATING, here, 137.0)
        assertEquals(137.0, t!!.bearing, 1e-9)
    }

    @Test
    fun `north up faces north whatever the travel bearing`() {
        val t = MapCamera.follow(nav(orientation = MapOrientation.NORTH_UP),
                                 Phase.NAVIGATING, here, 137.0)
        assertEquals(0.0, t!!.bearing, 1e-9)
    }

    @Test
    fun `a manual rotation beats the orientation preference`() {
        val t = MapCamera.follow(nav(orientation = MapOrientation.HEADING_UP, manual = 42.0),
                                 Phase.NAVIGATING, here, 137.0)
        assertEquals(42.0, t!!.bearing, 1e-9)
    }

    @Test
    fun `recentering discards a manual rotation`() {
        val cam = nav(orientation = MapOrientation.NORTH_UP, manual = 42.0)
        val t = MapCamera.recenter(cam, Phase.NAVIGATING, here, 137.0, zoom = null)
        assertEquals("recenter must not honour the bearing it is clearing", 0.0, t.bearing, 1e-9)
    }

    // ---- tilt --------------------------------------------------------------

    @Test
    fun `3D applies while navigating heading up`() {
        val t = MapCamera.follow(nav(), Phase.NAVIGATING, here, 90.0)
        assertEquals(MapCamera.TILT_DEG, t!!.tilt, 1e-9)
    }

    @Test
    fun `2D is flat`() {
        val t = MapCamera.follow(nav(perspective = MapPerspective.FLAT),
                                 Phase.NAVIGATING, here, 90.0)
        assertEquals(0.0, t!!.tilt, 1e-9)
    }

    @Test
    fun `north up is never tilted`() {
        // A tilted map that does not rotate puts the horizon at a fixed screen
        // edge while the car turns underneath it, so "ahead" and "up the
        // screen" come apart. See MapPerspective.TILTED.
        val t = MapCamera.follow(
            nav(orientation = MapOrientation.NORTH_UP, perspective = MapPerspective.TILTED),
            Phase.NAVIGATING, here, 90.0,
        )
        assertEquals(0.0, t!!.tilt, 1e-9)
    }

    @Test
    fun `exploring is never tilted`() {
        // Outside navigation, reading the map AS a map is the whole task.
        val t = MapCamera.follow(nav(), Phase.EXPLORE, here, 90.0)
        assertEquals(0.0, t!!.tilt, 1e-9)
    }

    @Test
    fun `a hand-rotated map drops the tilt`() {
        val t = MapCamera.follow(nav(manual = 42.0), Phase.NAVIGATING, here, 90.0)
        assertEquals(0.0, t!!.tilt, 1e-9)
    }

    // ---- modes -------------------------------------------------------------

    @Test
    fun `a free camera is not moved at all`() {
        assertNull(MapCamera.follow(nav(mode = CameraMode.FREE), Phase.NAVIGATING, here, 90.0))
    }

    @Test
    fun `an overview camera is not moved at all`() {
        assertNull(MapCamera.follow(nav(mode = CameraMode.OVERVIEW), Phase.NAVIGATING, here, 90.0))
    }

    @Test
    fun `preview frames the route rather than the vehicle`() {
        assertNull(
            "yanking the camera to the car would undo the route fit the driver is reading",
            MapCamera.follow(nav(), Phase.PREVIEW, here, 90.0),
        )
    }

    // ---- gestures ----------------------------------------------------------

    @Test
    fun `a pan releases the camera`() {
        assertEquals(CameraMode.FREE, MapCamera.onPan(nav(), T0).mode)
    }

    @Test
    fun `a rotate gesture keeps position following`() {
        // Deliberately NOT a drop to FREE. A rotate is a question about the
        // junction ahead; answering it should not also cost the driver the
        // follow camera and charge them a recenter tap they did not ask to owe.
        val after = MapCamera.onRotate(nav(), 77.0, T0)
        assertEquals(CameraMode.FOLLOW, after.mode)
        assertEquals(77.0, after.manualBearing!!, 1e-9)
    }

    @Test
    fun `panning out of an overview lands in free, not back in follow`() {
        val after = MapCamera.onPan(nav(mode = CameraMode.OVERVIEW), T0)
        assertEquals(CameraMode.FREE, after.mode)
    }

    @Test
    fun `leaving an overview returns to following`() {
        val cam = MapCamera.toggleOverview(nav())
        assertEquals(CameraMode.OVERVIEW, cam.mode)
        assertEquals(CameraMode.FOLLOW, MapCamera.toggleOverview(cam).mode)
    }

    @Test
    fun `toggling the orientation clears a manual rotation`() {
        val after = MapCamera.toggleOrientation(nav(manual = 42.0))
        assertNull("an explicit choice must beat a stale gesture", after.manualBearing)
    }

    @Test
    fun `the orientation toggle round-trips`() {
        val a = nav(orientation = MapOrientation.NORTH_UP)
        val b = MapCamera.toggleOrientation(a)
        assertEquals(MapOrientation.HEADING_UP, b.orientation)
        assertEquals(MapOrientation.NORTH_UP, MapCamera.toggleOrientation(b).orientation)
    }

    @Test
    fun `the perspective toggle round-trips`() {
        val a = nav(perspective = MapPerspective.FLAT)
        val b = MapCamera.togglePerspective(a)
        assertEquals(MapPerspective.TILTED, b.perspective)
        assertEquals(MapPerspective.FLAT, MapCamera.togglePerspective(b).perspective)
    }

    // ---- what the control says --------------------------------------------

    @Test
    fun `a following camera facing its preference needs no recenter`() {
        assertFalse(nav().needsRecenter)
    }

    @Test
    fun `a released camera needs a recenter`() {
        assertTrue(MapCamera.onPan(nav(), T0).needsRecenter)
    }

    @Test
    fun `a hand-rotated camera needs a recenter even though it is still following`() {
        // This is the state the compass most needs to communicate: the driver
        // did not choose it in settings and may not remember causing it.
        val after = MapCamera.onRotate(nav(), 77.0, T0)
        assertEquals(CameraMode.FOLLOW, after.mode)
        assertTrue(after.needsRecenter)
    }

    // ---- the camera comes back ---------------------------------------------
    //
    // On 2026-09-13 a drive to Elite Training and Consultancy froze the map at
    // 18:07 and it was still frozen at 18:11:28 — screenshots four minutes
    // apart are pixel-identical apart from the chrome, while the trip bar
    // counted 2.6 km down to 1.6 km and the voice kept calling maneuvers.
    // CameraMode.FREE had no exit but the recenter button, and MapCamera.follow
    // returns null for every frame it is in. One brush of a mounted phone cost
    // the whole journey, silently.

    private val late = T0 + MapCamera.AUTO_RESUME_MS

    @Test
    fun `a camera taken by accident comes back on its own`() {
        val taken = MapCamera.onPan(nav(), T0)
        val back = MapCamera.autoResume(taken, Phase.NAVIGATING, late)
        assertNotNull("a frozen map during guidance is the defect this prevents", back)
        assertEquals(CameraMode.FOLLOW, back!!.mode)
        assertFalse(back.needsRecenter)
    }

    @Test
    fun `it is left alone inside the timeout`() {
        // A glance at the junction after next must not be yanked away mid-look.
        val taken = MapCamera.onPan(nav(), T0)
        assertNull(MapCamera.autoResume(taken, Phase.NAVIGATING, late - 1))
    }

    @Test
    fun `a stale hand rotation expires too`() {
        // Still FOLLOW, so `follow` keeps drawing — but facing a bearing the
        // driver set once and may not remember setting. Same accident, quieter.
        val turned = MapCamera.onRotate(nav(), 77.0, T0)
        val back = MapCamera.autoResume(turned, Phase.NAVIGATING, late)
        assertNotNull(back)
        assertNull("a recenter clears a manual rotation", back!!.manualBearing)
    }

    @Test
    fun `outside navigation the map is the task and is never taken back`() {
        val taken = MapCamera.onPan(nav(), T0)
        for (p in listOf(Phase.EXPLORE, Phase.PREVIEW)) {
            assertNull("panning IS the task in $p", MapCamera.autoResume(taken, p, late))
        }
    }

    @Test
    fun `an overview is a decision, not an accident`() {
        // It has its own control to leave by, and leaving returns to FOLLOW.
        val over = MapCamera.toggleOverview(nav())
        assertNull(MapCamera.autoResume(over, Phase.NAVIGATING, late))
    }

    @Test
    fun `a camera nobody touched is not resumed`() {
        // No takeover, nothing to expire — and no camera command issued sixty
        // times a minute for a camera that is already following.
        assertNull(MapCamera.autoResume(nav(), Phase.NAVIGATING, late))
    }

    @Test
    fun `recentering by hand clears the takeover`() {
        val taken = MapCamera.onPan(nav(), T0)
        val back = MapCamera.onRecenter(taken)
        assertNull(back.takenOverAtMs)
        assertNull("nothing left to expire", MapCamera.autoResume(back, Phase.NAVIGATING, late))
    }

    // ---- V4: bearing continuity -------------------------------------------

    @Test
    fun `crossing north rotates two degrees and not three hundred and fifty eight`() {
        // §8 of the V4 brief names this defect specifically, and it was real:
        // the follow camera assigned the measured bearing straight onto the
        // MapLibre camera, so every crossing of north handed the renderer a
        // 359-degree difference to interpolate. Driving north up Al Corniche
        // was enough to trigger it.
        assertEquals(1.0, MapCamera.shortestAngle(359.0, 0.0), 1e-9)
        assertEquals(-1.0, MapCamera.shortestAngle(0.0, 359.0), 1e-9)
        assertEquals(2.0, MapCamera.shortestAngle(359.0, 1.0), 1e-9)
        assertEquals(-2.0, MapCamera.shortestAngle(1.0, 359.0), 1e-9)
    }

    @Test
    fun `the shortest angle is never more than half a turn`() {
        var d = 0.0
        while (d < 360.0) {
            var t = 0.0
            while (t < 360.0) {
                val a = MapCamera.shortestAngle(d, t)
                assertTrue("shortestAngle($d, $t) = $a", a > -180.0 - 1e-9 && a <= 180.0 + 1e-9)
                t += 7.0
            }
            d += 11.0
        }
    }

    @Test
    fun `a half turn is resolved consistently rather than jittering`() {
        // Exactly 180 degrees has two equally short answers. Whichever is
        // picked, it must be the SAME one every frame, or a camera pointed at
        // the antipode of its heading would flip direction on alternate frames.
        assertEquals(
            MapCamera.shortestAngle(0.0, 180.0),
            MapCamera.shortestAngle(0.0, 180.0),
            1e-12,
        )
        assertEquals(180.0, MapCamera.shortestAngle(0.0, 180.0), 1e-9)
    }

    @Test
    fun `bearings are wrapped into a single turn`() {
        assertEquals(10.0, MapCamera.norm360(370.0), 1e-9)
        assertEquals(350.0, MapCamera.norm360(-10.0), 1e-9)
        assertEquals(0.0, MapCamera.norm360(360.0), 1e-9)
        assertEquals(1.0, MapCamera.norm360(-719.0), 1e-9)
    }

    @Test
    fun `interpolating across north stays near north`() {
        // Halfway from 350 to 10 is 0, not 180.
        assertEquals(0.0, MapCamera.lerpBearing(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, MapCamera.lerpBearing(350.0, 10.0, 0.25), 1e-9)
        assertEquals(5.0, MapCamera.lerpBearing(350.0, 10.0, 0.75), 1e-9)
    }

    @Test
    fun `smoothing closes on the measurement without overshooting it`() {
        var b = 0.0
        repeat(60) { b = MapCamera.smoothBearing(b, 90.0, 1.0 / 60.0, stationary = false) }
        // One second at 4.5 per second closes ~99% of the gap.
        assertTrue("expected to approach 90, got $b", b in 85.0..90.0)
        // And it must never pass it.
        repeat(600) { b = MapCamera.smoothBearing(b, 90.0, 1.0 / 60.0, stationary = false) }
        assertTrue("overshot to $b", b <= 90.0 + 1e-6)
    }

    @Test
    fun `smoothing is frame rate independent`() {
        // A fixed per-frame blend would smooth twice as hard on a 120 Hz panel
        // as on a 60 Hz one, baking one handset's feel into the product.
        var at60 = 0.0
        repeat(30) { at60 = MapCamera.smoothBearing(at60, 100.0, 1.0 / 60.0, false) }
        var at120 = 0.0
        repeat(60) { at120 = MapCamera.smoothBearing(at120, 100.0, 1.0 / 120.0, false) }
        assertEquals("half a second of smoothing must agree", at60, at120, 0.5)
    }

    @Test
    fun `a stationary vehicle holds its bearing instead of inventing one`() {
        // GPS heading at a standstill is close to random, and §8 is explicit
        // that noise must not make the map shake. A parked car has no heading;
        // holding the last one the driver saw beats making one up.
        val held = MapCamera.smoothBearing(42.0, 300.0, 1.0 / 60.0, stationary = true)
        assertEquals(42.0, held, 1e-9)
    }

    @Test
    fun `a very long frame gap cannot overshoot the target`() {
        // A dropped frame or a resumed app can hand this an arbitrary dt, and
        // the clamp inside smoothBearing is what stops a 30-second gap being
        // treated as 135 turns of smoothing. It closes most of the way in one
        // step, which is the right behaviour for a resume — but it must never
        // pass the measurement, because a camera that overshoots its heading
        // and comes back reads as a wobble.
        val b = MapCamera.smoothBearing(0.0, 90.0, 30.0, stationary = false)
        assertTrue("overshot to $b", b <= 90.0 + 1e-9)
        assertTrue("barely moved: $b", b > 60.0)
    }

    // ---- V4: look-ahead ----------------------------------------------------

    @Test
    fun `the vehicle sits low on the screen while navigating`() {
        // Vector centred it, spending half a 2340 px display on road already
        // driven past. Both reference products place it about two thirds down.
        val f = MapCamera.lookAheadFraction(Phase.NAVIGATING, nav())
        assertTrue("expected look-ahead, got $f", f > 0.6)
    }

    @Test
    fun `the vehicle is centred when the driver is reading a map rather than driving`() {
        assertEquals(0.5, MapCamera.lookAheadFraction(Phase.EXPLORE, nav()), 1e-9)
        assertEquals(0.5, MapCamera.lookAheadFraction(Phase.PREVIEW, nav()), 1e-9)
    }

    @Test
    fun `a released or framed camera is centred`() {
        // In FREE the driver is looking somewhere of their own choosing, and in
        // OVERVIEW the route is being framed — pushing the target down would
        // offset both for no reason.
        assertEquals(0.5,
            MapCamera.lookAheadFraction(Phase.NAVIGATING, MapCamera.onPan(nav(), T0)), 1e-9)
        assertEquals(0.5,
            MapCamera.lookAheadFraction(Phase.NAVIGATING, MapCamera.toggleOverview(nav())), 1e-9)
    }

    // ---- V4: auto-zoom -----------------------------------------------------

    @Test
    fun `zoom falls as speed rises`() {
        val zooms = listOf(10, 40, 70, 100, 130).map { MapCamera.autoZoom(it)!! }
        for (i in 1 until zooms.size) {
            assertTrue(
                "zoom must not increase with speed: $zooms",
                zooms[i] < zooms[i - 1],
            )
        }
    }

    @Test
    fun `an unknown speed asserts no zoom at all`() {
        // Same contract CameraTarget.zoom already has: null means "leave the
        // driver's zoom alone". A missing GPS speed must not silently zoom the
        // map to a default.
        assertNull(MapCamera.autoZoom(null))
    }

    @Test
    fun `the urban band is the zoom Vector used before it had any bands`() {
        // 16.5 was the single hardcoded navigation zoom. It should still be
        // what a driver on an urban arterial gets, so auto-zoom is an addition
        // rather than a change of behaviour for the commonest case.
        assertEquals(MapCamera.NAV_ZOOM, MapCamera.autoZoom(50)!!, 1e-9)
    }

    @Test
    fun `auto-zoom is not consulted outside navigation`() {
        assertEquals(MapCamera.EXPLORE_ZOOM,
            MapCamera.transitionZoom(Phase.EXPLORE, 120, autoZoomEnabled = true), 1e-9)
    }

    @Test
    fun `turning auto-zoom off restores the single navigation zoom`() {
        assertEquals(MapCamera.NAV_ZOOM,
            MapCamera.transitionZoom(Phase.NAVIGATING, 130, autoZoomEnabled = false), 1e-9)
    }

    @Test
    fun `an unknown speed falls back to the navigation zoom rather than nothing`() {
        // transitionZoom is used at DISCRETE transitions, where a zoom has to
        // be asserted; only the frame loop is allowed to leave it alone.
        assertEquals(MapCamera.NAV_ZOOM,
            MapCamera.transitionZoom(Phase.NAVIGATING, null, autoZoomEnabled = true), 1e-9)
    }

    // ---- V4: auto-zoom hysteresis ------------------------------------------

    @Test
    fun `dithering across a boundary does not move the camera`() {
        // THE defect this hysteresis exists for, and the first version of the
        // code had it: bands change at 55 km/h, so a driver holding 54-56 had
        // the map breathing in and out indefinitely. A magnitude threshold on
        // the ZOOM cannot fix this — adjacent bands differ by 0.5-0.7, so a
        // threshold small enough to allow band changes is small enough to allow
        // the flapping, and one large enough to stop it blocks every change.
        // The oscillation is in the speed, so the damping is in the speed.
        var band = MapCamera.rawBand(54)
        val started = band
        for (v in listOf(54, 56, 54, 57, 53, 55, 56, 54)) {
            band = MapCamera.autoZoomBand(v, band)
        }
        assertEquals("the band must not have moved at all", started, band)
    }

    @Test
    fun `a genuine acceleration does change the band`() {
        var band = MapCamera.rawBand(30)
        band = MapCamera.autoZoomBand(70, band)
        assertNotEquals(MapCamera.rawBand(30), band)
        assertEquals(MapCamera.rawBand(70), band)
    }

    @Test
    fun `every band is reachable`() {
        // A hysteresis wider than a band would make one of them unreachable,
        // which is a quiet way for a feature to be half-missing.
        val reached = mutableSetOf<Int>()
        var band = -1
        for (v in 0..200 step 1) {
            band = MapCamera.autoZoomBand(v, band)
            reached.add(band)
        }
        assertEquals(
            "not every band is reachable by accelerating: $reached",
            (0 until MapCamera.zoomBandCount).toSet(), reached,
        )
    }

    @Test
    fun `slowing down comes back down the bands`() {
        var band = -1
        for (v in listOf(10, 40, 70, 100, 140)) band = MapCamera.autoZoomBand(v, band)
        val fast = band
        for (v in listOf(100, 70, 40, 10)) band = MapCamera.autoZoomBand(v, band)
        assertTrue("expected to descend from $fast, ended at $band", band < fast)
        assertEquals(MapCamera.rawBand(10), band)
    }

    @Test
    fun `an unknown speed holds the band rather than resetting it`() {
        // A dropped speed field must not zoom the map, in either direction.
        val band = MapCamera.rawBand(110)
        assertEquals(band, MapCamera.autoZoomBand(null, band))
    }

    @Test
    fun `the first band is adopted outright`() {
        // With no band in use there is nothing to be hysteretic about.
        assertEquals(MapCamera.rawBand(130), MapCamera.autoZoomBand(130, -1))
    }

    @Test
    fun `the follow camera still never asserts a zoom`() {
        // The V3 invariant that made pinching out during navigation possible.
        // Auto-zoom must not have reintroduced a per-frame zoom assertion.
        val t = MapCamera.follow(nav(), Phase.NAVIGATING, LngLat(51.5, 25.3), 90.0)
        assertNotNull(t)
        assertNull("a followed frame must leave the driver's zoom alone", t!!.zoom)
    }
    // ---- the camera gate (V5) ----------------------------------------------
    //
    // Found by replaying a drive on the S24 and looking at the screen: the
    // driving camera was showing six kilometres of Doha with the vehicle as a
    // speck. `moveCamera` cancels a running animation, so the first FOLLOWED
    // FRAME after a transition began — 8 ms later at 120 Hz — destroyed it and
    // re-supplied the zoom it read back mid-move. The 1 800 ms nav-start flight
    // and every auto-zoom band change did nothing at all.
    //
    // It cannot happen while parked: a follow frame is only emitted when the
    // tracker is interpolating, which needs a moving vehicle. V4 measured this
    // flight frame by frame on a stationary handset and found it perfect.

    @Test
    fun `a follow frame may write when nothing is animating`() {
        assertTrue(CameraGate().mayFollow(1_000L))
    }

    @Test
    fun `a follow frame is held off for the length of a transition`() {
        val g = CameraGate()
        g.beginTransition(1_000L, 1_800)
        assertFalse("8 ms in — this is the frame that killed the flight", g.mayFollow(1_008L))
        assertFalse(g.mayFollow(2_799L))
        assertTrue("the flight is over; following must resume", g.mayFollow(2_800L))
    }

    @Test
    fun `a zero-length transition holds nothing off`() {
        // `applyCamera(target)` with no duration IS the follow write. It must
        // not be able to lock the loop out of the camera.
        val g = CameraGate()
        g.beginTransition(1_000L, 0)
        assertTrue(g.mayFollow(1_000L))
    }

    @Test
    fun `overlapping transitions hold off until the later one ends`() {
        // A recenter during a nav-start flight. Taking the newest end time
        // would let the loop back in while the longer move was still running.
        val g = CameraGate()
        g.beginTransition(0L, 1_800)
        g.beginTransition(100L, 380)
        assertFalse("the 380 ms move must not shorten the 1 800 ms one", g.mayFollow(600L))
        assertTrue(g.mayFollow(1_800L))
    }

    @Test
    fun `a gesture gives the camera back immediately`() {
        val g = CameraGate()
        g.beginTransition(0L, 1_800)
        assertFalse(g.mayFollow(100L))
        g.cancel()
        assertTrue("a finger on the map wins over an animation", g.mayFollow(101L))
    }

    // ---- V7 3D: the extrusion state, and walking's flat pitch ---------------

    @Test
    fun `buildings extrude on the driver's 3D preference, not on the current pitch`() {
        // The rule, and the reason it is NOT "is the map tilted right now".
        //
        // The first version asked that, and it was wrong in a way the tests
        // could not see: `tiltFor` needs `phase == NAVIGATING`, the style is
        // built in EXPLORE, so the answer at style-apply time was always false
        // and the layer was never emitted. Measured on the emulator:
        // `pitch=60.0` in telemetry and a flat orthographic render.
        //
        // So this asserts the PREFERENCE drives the layer, and that the layer
        // is present in every phase — including the one the style is actually
        // built in.
        val want3d = CameraState(perspective = MapPerspective.TILTED)
        for (phase in listOf(Phase.EXPLORE, Phase.PREVIEW, Phase.NAVIGATING)) {
            assertTrue("3D must be in the style in $phase, which is where it is "
                       + "built", MapCamera.extrudesBuildings(want3d, onFoot = false))
        }
        // ... and the pitch is a separate question with a separate answer.
        assertFalse("a flat map is still not tilted",
                    MapCamera.tiltFor(want3d, Phase.EXPLORE) > 0.0)
        assertTrue("but it tilts once navigating",
                   MapCamera.tiltFor(want3d, Phase.NAVIGATING) > 0.0)

        val want2d = CameraState(perspective = MapPerspective.FLAT)
        for (phase in listOf(Phase.EXPLORE, Phase.PREVIEW, Phase.NAVIGATING)) {
            assertFalse("2D must remove the extrusion in $phase",
                        MapCamera.extrudesBuildings(want2d, onFoot = false))
        }
    }

    @Test
    fun `a walk never extrudes buildings, however the map is set up`() {
        // Walking's flat pitch is a contract, not an accident of the car
        // policy, and 3D must not be the thing that quietly ends it.
        for (perspective in MapPerspective.entries) {
            for (orientation in MapOrientation.entries) {
                val cam = CameraState(orientation = orientation, perspective = perspective)
                assertFalse(
                    "a walk must never raise 3D buildings "
                        + "(perspective=$perspective orientation=$orientation)",
                    MapCamera.extrudesBuildings(cam, onFoot = true),
                )
            }
        }
    }

    @Test
    fun `choosing 2D removes the extrusion and choosing 3D restores it`() {
        val three = CameraState()
        assertTrue(MapCamera.extrudesBuildings(three, onFoot = false))
        val flat = three.copy(perspective = MapPerspective.FLAT)
        assertFalse("choosing 2D must remove the extrusion",
                    MapCamera.extrudesBuildings(flat, onFoot = false))
        // The default is 3D, so a fresh install gets the layer — which is what
        // makes the bug above a visible one rather than a hidden one.
        assertTrue(MapCamera.extrudesBuildings(CameraState(), onFoot = false))
    }

    @Test
    fun `a zoom button never tilts a walking map`() {
        // The defect this pins: the HUD zoom controls are shared between the
        // car and the walk, and they took their tilt from the CAR's policy —
        // so pressing zoom while walking wrote a 60-degree pitch onto a map
        // whose policy is a flat constant. Modelled as data, because the bug
        // lived in an Android callback.
        val cam = CameraState()   // the driver's default: heading-up + tilted
        assertEquals("the car does tilt on a zoom press",
                     MapCamera.TILT_DEG,
                     MapCamera.tiltForZoomStep(cam, Phase.NAVIGATING, onFoot = false), 1e-9)
        assertEquals("the walk must not, whatever the car would do",
                     0.0, MapCamera.tiltForZoomStep(cam, Phase.NAVIGATING, onFoot = true), 1e-9)
        assertEquals("... in any orientation or perspective the driver chose",
                     0.0, MapCamera.tiltForZoomStep(
                         CameraState(orientation = MapOrientation.HEADING_UP,
                                     perspective = MapPerspective.TILTED),
                         Phase.NAVIGATING, onFoot = true), 1e-9)

        // THE REPRODUCTION, and it is what makes the two assertions above mean
        // something: this is the value `zoomStep` used to take, ungated, for a
        // walk. `follow` has no notion of a pedestrian, so it answers with the
        // car's pitch — which is exactly how a 60-degree map happened on foot.
        val whatTheOldPathTook = MapCamera.follow(cam, Phase.NAVIGATING, here, 90.0)!!.tilt
        assertEquals("the old zoom path really did carry the car's tilt for a walk",
                     MapCamera.TILT_DEG, whatTheOldPathTook, 1e-9)
    }

    @Test
    fun `the extrusion zoom is inside the range the tiles are baked for`() {
        // The tile bake covers z11-z15. A layer above that renders nothing, at
        // any zoom the map can reach.
        assertTrue(MapCamera.BUILDINGS_3D_MINZOOM in 11.0..15.0)
    }

}
