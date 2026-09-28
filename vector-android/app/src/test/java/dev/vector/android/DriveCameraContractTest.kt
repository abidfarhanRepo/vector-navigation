package dev.vector.android

import dev.vector.geo.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The camera, driven by a whole replay rather than by a table of cases.
 *
 * ## Why this is not another `MapCameraTest`
 *
 * [MapCameraTest] asks what the policy returns for a state. This asks what the
 * policy returns **for a drive** — 552 consecutive fixes along a real Doha route
 * from the live backend, with real speed changes, real stops and real turns —
 * and it exists because of a class of defect that only appears across a
 * sequence:
 *
 *  * "the camera follows the car", checked per call, is trivially true and says
 *    nothing about whether it *keeps* following, or whether it swings when the
 *    vehicle stops and the receiver's heading goes to noise;
 *  * "buildings look detached" is a camera complaint. A building layer is
 *    anchored to geography by construction (see the test below), so the only
 *    way it can appear to slide is a camera that jumps, lags, or stops
 *    tracking. That is a property of the sequence, not of a frame.
 *
 * ## The boundary this test does not cross, stated rather than implied
 *
 * `MapCamera` decides; MapLibre animates. Nothing here can observe the
 * interpolated pose the renderer actually draws between two calls — that is
 * `MapLibre`'s animation loop, and the honest evidence for it is the recorded
 * replay on a handset (see `SHIPATON-SUBMISSION/VIDEO-EDIT-PLAN.md`). What this
 * test establishes is that the input to that animation is correct and
 * continuous for every frame of the drive, which is the half that can be wrong
 * in this repository.
 */
class DriveCameraContractTest {

    private val city = ScenarioTraces.city()
    private val fixes = ScenarioTraces.a(city)

    /** The demo configuration: following, heading-up, 3D, driving. */
    private val driving = CameraState(
        mode = CameraMode.FOLLOW,
        orientation = MapOrientation.HEADING_UP,
        perspective = MapPerspective.TILTED,
    )

    private fun haversineM(a: LngLat, b: LngLat): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) *
            kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    /**
     * The heading the camera is actually given while on route.
     *
     * `MapCamera.follow` takes `travelBearing`, and on route Vector supplies it
     * from **the route geometry at the vehicle's along-distance**, not from the
     * receiver. That is not an implementation detail — it is the reason a
     * heading-up camera is usable at all, and the next test measures what
     * happens if it is not done.
     */
    private val routeIndex = dev.vector.geo.RouteGeometry.index(city.geometry)

    private fun travelBearing(f: dev.vector.geo.SimFix): Double =
        routeIndex?.pointAt(f.truthAlongM)?.bearing
            ?: f.bearingDeg
            ?: 0.0

    private fun bearingOf(f: dev.vector.geo.SimFix): Double = travelBearing(f)

    @Test
    fun `the camera never rotates while the vehicle is not advancing`() {
        // The defect this catches is specific and it was measured on a handset:
        // a stationary vehicle reports a heading that is essentially random, and
        // a heading-up camera that honours it makes the whole map rotate under a
        // parked car.
        //
        // "Standing still" is measured as **distance along the route**, not as
        // straight-line distance between two fixes. That distinction is the whole
        // test: a fix whose position wanders by two metres while the vehicle is
        // parked is receiver noise, and `truthAlongM` is the fixture's own
        // statement that the vehicle has not moved. Using the straight-line
        // distance instead would count a 90-degree corner taken slowly as a
        // "standstill", which is how the first version of this test failed — on
        // eleven frames that were all real turns.
        var spins = 0
        var stationary = 0
        var worst = 0.0
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]; val b = fixes[i]
            if (b.truthAlongM - a.truthAlongM >= 0.5) continue
            stationary++
            val turn = abs(
                (MapCamera.bearingFor(driving, travelBearing(b)) -
                    MapCamera.bearingFor(driving, travelBearing(a)) + 540.0) % 360.0 - 180.0
            )
            if (turn > 0.0) { spins++; worst = maxOf(worst, turn) }
        }
        assertTrue("this drive never stops, so the test proves nothing", stationary > 10)
        assertEquals(
            "the camera rotated on $spins of $stationary stationary frames " +
                "(worst ${"%.0f".format(worst)} deg) — a spin, not a turn",
            0, spins,
        )
    }

    @Test
    fun `the camera invents no rotation of its own`() {
        // `bearingFor` in HEADING_UP is a pass-through, so while the vehicle is
        // following a route the camera's rotation between two fixes must be
        // EXACTLY the route's. Anything else — a smoothing filter, a lag term, a
        // manual bearing leaking in — is rotation the camera added, and added
        // rotation is one of the two ways a geographically anchored layer is made
        // to look like it is sliding.
        //
        // The route's bearing is piecewise constant with a step at each vertex,
        // so this drive does contain large camera turns; asserting one exists
        // keeps the equality from being vacuous.
        var large = 0
        for (i in 1 until fixes.size) {
            val routeTurn = abs(
                (travelBearing(fixes[i]) - travelBearing(fixes[i - 1]) + 540.0) % 360.0 - 180.0
            )
            val camTurn = abs(
                (MapCamera.bearingFor(driving, travelBearing(fixes[i])) -
                    MapCamera.bearingFor(driving, travelBearing(fixes[i - 1])) + 540.0) % 360.0 - 180.0
            )
            assertEquals("fix $i: the camera rotated by something other than the route",
                         routeTurn, camTurn, 1e-9)
            if (routeTurn >= 45.0) large++
        }
        assertTrue("this drive has no real corners, so the equality is vacuous", large >= 4)
    }

    @Test
    fun `the receiver's own heading is too noisy to aim a camera`() {
        // The measurement behind the choice above, pinned so it cannot be
        // quietly forgotten: the fused receiver really does report headings that
        // swing by nearly 180 degrees while the vehicle is barely moving. If a
        // future change feeds `SimFix.bearingDeg` into `bearingFor` instead of
        // the route bearing, the test above fails — and this one is the
        // explanation.
        var spins = 0
        var worst = 0.0
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]; val b = fixes[i]
            val ra = a.bearingDeg ?: continue
            val rb = b.bearingDeg ?: continue
            if (haversineM(a.position, b.position) >= 20.0) continue
            val turn = abs((rb - ra + 540.0) % 360.0 - 180.0)
            if (turn > 45.0) { spins++; worst = maxOf(worst, turn) }
        }
        assertTrue(
            "the fixture no longer demonstrates receiver heading noise " +
                "($spins frames, worst ${"%.0f".format(worst)} deg) — the reason Vector " +
                "does not aim the camera from the receiver has stopped being observable here",
            spins > 0,
        )
    }

    @Test
    fun `the drive is long enough to be called one`() {
        assertTrue("only ${fixes.size} fixes", fixes.size > 300)
        assertTrue("only ${fixes.last().tMs / 1000}s", fixes.last().tMs > 300_000)
    }

    @Test
    fun `every fix in the drive produces a camera, and it is the vehicle`() {
        for ((i, f) in fixes.withIndex()) {
            val t = MapCamera.follow(driving, Phase.NAVIGATING, f.position, bearingOf(f))
            assertNotNull("fix $i produced no follow target while navigating", t)
            // Not "close to": the follow policy sets the camera centre to the
            // position it is handed. Any lag or smoothing is the renderer's, and
            // exists nowhere in this decision — which is what makes a trailing
            // camera a renderer bug rather than a policy one.
            assertEquals("fix $i moved the camera off the vehicle", f.position, t!!.position)
            // A follow frame must never assert a zoom: the driver owns zoom in
            // navigation, and a follow frame that set it would fight their pinch
            // every second.
            assertEquals("fix $i asserted a zoom on a follow frame", null, t.zoom)
        }
    }

    @Test
    fun `the camera is tilted and 3D is on, for the whole drive`() {
        for ((i, f) in fixes.withIndex()) {
            val t = MapCamera.follow(driving, Phase.NAVIGATING, f.position, bearingOf(f))!!
            assertEquals("fix $i: tilt is not the navigation tilt", MapCamera.TILT_DEG, t.tilt, 1e-9)
            assertTrue(
                "fix $i: the extrusion layer is off while the camera is pitched — " +
                    "this is the half-built state the V7 3D fix closed",
                MapCamera.extrudesBuildings(driving, onFoot = false),
            )
        }
    }

    @Test
    fun `the navigation tilt never exceeds the documented cap`() {
        // The cap is a product decision, not a rendering limit: past 60 degrees
        // the route ribbon and the maneuver chevron start going under the
        // buildings they are drawn to be read against.
        assertTrue("the cap is not 60 degrees any more", MapCamera.TILT_DEG <= 60.0)
        for (perspective in MapPerspective.entries) {
            for (orientation in MapOrientation.entries) {
                for (phase in Phase.entries) {
                    val cam = driving.copy(perspective = perspective, orientation = orientation)
                    val tilt = MapCamera.tiltFor(cam, phase)
                    assertTrue(
                        "$phase/$orientation/$perspective tilts $tilt degrees",
                        tilt <= 60.0 && tilt >= 0.0,
                    )
                }
            }
        }
    }

    @Test
    fun `the camera stops being flat when the driver is no longer driving`() {
        val t0 = MapCamera.tiltFor(driving, Phase.NAVIGATING)
        val t1 = MapCamera.tiltFor(driving, Phase.PREVIEW)
        val t2 = MapCamera.tiltFor(driving, Phase.EXPLORE)
        assertTrue("navigation should be tilted", t0 > 0.0)
        assertEquals("preview must be flat — the route is being read, not driven", 0.0, t1, 1e-9)
        assertEquals("explore must be flat", 0.0, t2, 1e-9)
        // And a follow frame in preview must not yank the camera to the vehicle:
        // it would undo the route fit the driver is looking at.
        assertEquals(
            "preview must not follow the vehicle",
            null,
            MapCamera.follow(driving, Phase.PREVIEW, fixes[0].position, 0.0),
        )
    }

    @Test
    fun `the whole drive produces exactly the same camera twice`() {
        fun poses(): List<CameraTarget> = fixes.map { f ->
            MapCamera.follow(driving, Phase.NAVIGATING, f.position, bearingOf(f))!!
        }
        val a = poses(); val b = poses()
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("fix $i: bearing differs between runs", a[i].bearing, b[i].bearing, 0.0)
            assertEquals("fix $i: tilt differs between runs", a[i].tilt, b[i].tilt, 0.0)
            assertEquals("fix $i: position differs between runs", a[i].position, b[i].position)
        }
    }

    @Test
    fun `the route the demo replays really does pass through the towers`() {
        // The recording is only evidence about 3D if the drive goes somewhere
        // with buildings that have heights. West Bay is where the served tiles
        // carry them; a drive around the D Ring Road carries none, and a flat
        // render there would say nothing about the renderer.
        val last = fixes.last().position
        assertTrue(
            "the city drive ends at ${last.lat},${last.lng}, which is not West Bay",
            last.lat in 25.30..25.34 && last.lng in 51.49..51.53,
        )
        val westBay = fixes.count { it.position.lat in 25.31..25.34 && it.position.lng in 51.49..51.53 }
        assertTrue("the drive only spends $westBay fixes inside West Bay", westBay >= 20)
    }
}
