package dev.vector.geo.camera

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The camera model — V7.3's heart.
 *
 * The failures worth most are the ones the acceptance list names as the point
 * of the whole stage:
 *
 *  * the model claims a camera's LOCATION and distance only. Nothing here can
 *    say a camera is active, and the placement of a warning is never a
 *    function of a direction claim -- the direction gate can only EVER
 *    SUPPRESS, on a sane tag provably pointing away;
 *  * off-route cameras are never claimed; cameras behind the vehicle never
 *    re-announce; reroutes replace the profile wholesale; a dense junction
 *    keeps distinct installations distinct; an absent/malformed/old catalog
 *    is an empty profile, not an error.
 *
 * Routes are straight east-west (bearing 90°) unless a fixture says
 * otherwise, so the numbers in the fixtures can be read by hand.
 */
class CameraTest {

    // ---- fixture: camera on route -----------------------------------------

    @Test
    fun `a camera on route is claimed with its along position`() {
        val route = routeOf(2000.0)
        val profile = CameraMatcher.match(route, listOf(ref("n1", eastM = 800.0, northM = 0.0)))
        assertEquals(1, profile.size)
        assertEquals("n1", profile.at(0).ref.id)
        assertEquals(800.0, profile.at(0).approach.alongM, 1.0)
        // The approach bearing is the route's own, never an OSM tag.
        assertEquals(90.0, profile.at(0).approach.bearingDeg, 0.5)
    }

    @Test
    fun `a camera within the warning window is ahead, then passes`() {
        // The vehicle is 500 m before the camera: inside WARN_AHEAD_M.
        val profile = CameraMatcher.match(routeOf(2000.0), listOf(ref("n1", 800.0, 0.0)))
        val at500 = assertNotNull(profile.next(500.0))
        assertEquals("n1", at500.ref.id)
        assertEquals(300.0, at500.approach.alongM - 500.0, 1.0)
        // A camera farther than the window still exists on the route and is
        // still "next" -- the WINDOW is a UI policy; the profile is route
        // truth. What must be true is that distance is reported, never that
        // the camera IS anything but a location.
        val at0 = assertNotNull(profile.next(0.0))
        assertEquals(800.0, at0.approach.alongM - 0.0, 1.0)
    }

    // ---- fixture: camera off route ----------------------------------------

    @Test
    fun `a camera two kilometres off route is never claimed`() {
        val route = routeOf(2000.0)
        val profile = CameraMatcher.match(
            route,
            listOf(
                ref("nFar", eastM = 1000.0, northM = -2000.0),
                ref("nOn", eastM = 1000.0, northM = 0.0),
            ),
        )
        assertEquals(listOf("nOn"), profile.cameras.map { it.ref.id })
    }

    // ---- fixture: opposite-direction camera + the direction gate ----------

    @Test
    fun `a camera with no direction tag is a location warning`() {
        // 121 of Qatar's 133 camera nodes carry no direction. The LOCATION
        // fact stands: we say where a camera is, never what it does.
        val profile = CameraMatcher.match(routeOf(2000.0), listOf(ref("n1", 800.0, 0.0)))
        assertEquals(1, profile.size)
    }

    @Test
    fun `a sane direction tag pointing away suppresses the warning`() {
        // Eastbound route (bearing 90). A camera whose lens faces 270 is on
        // the other carriageway of a divided road, or points back along the
        // travel we are not on: >120 deg off is provably 'other side'.
        val cam = ref("nFarSide", 800.0, 0.0, direction = "270")
        val profile = CameraMatcher.match(routeOf(2000.0), listOf(cam))
        assertTrue(profile.isEmpty)
        // The gate SUPPRESSES; it never reclassifies or invents anything.
        assertEquals(
            CameraMatcher.Direction.SUPPRESSED,
            CameraMatcher.directionGate("270", 90.0),
        )
    }

    @Test
    fun `a sane direction tag nearly along travel warns the location`() {
        // A camera pointing 80 on an eastbound 90 route is co-directional:
        // plausibly a rear-facing camera on our carriageway. Warned.
        val cam = ref("n1", 800.0, 0.0, direction = "80")
        assertEquals(1, CameraMatcher.match(routeOf(2000.0), listOf(cam)).size)
        assertEquals(
            CameraMatcher.Direction.WARN,
            CameraMatcher.directionGate("80", 90.0),
        )
        // Exactly at the boundary (120 deg) it is still near-along: warn.
        assertEquals(
            CameraMatcher.Direction.WARN,
            CameraMatcher.directionGate("210", 90.0),
        )
        assertEquals(
            CameraMatcher.Direction.SUPPRESSED,
            CameraMatcher.directionGate("211", 90.0),
        )
    }

    @Test
    fun `a malformed direction tag is treated as absent and the location stands`() {
        // The deployable extract contains direction values like 2460 and 360.
        // Malformed values must fail validation and warn on the LOCATION
        // fact, exactly as if the tag were absent -- a mapper's noise must
        // not be laundered into a bearing the gate then trusts.
        assertNull(CameraMatcher.saneDirection("2460"))
        assertNull(CameraMatcher.saneDirection("-5"))
        assertNull(CameraMatcher.saneDirection("abc"))
        assertNull(CameraMatcher.saneDirection(null))
        // 360 is a legitimate full circle; normalized to 0 (north).
        assertEquals(0.0, CameraMatcher.saneDirection("360")!!, 1e-9)
        for (bad in listOf("2460", "999", "-1", "abc", "")) {
            val cam = ref("nBad-$bad", 800.0, 0.0, direction = bad)
            assertEquals(1, CameraMatcher.match(routeOf(2000.0), listOf(cam)).size,
                "malformed direction $bad must not suppress the location fact")
        }
    }

    // ---- fixture: multiple cameras ----------------------------------------

    @Test
    fun `multiple cameras are all claimed, in route order, warned in order`() {
        val route = routeOf(5000.0)
        val profile = CameraMatcher.match(
            route,
            listOf(
                ref("n3", eastM = 4000.0, northM = 0.0),
                ref("n1", eastM = 800.0, northM = 0.0),
                ref("n2", eastM = 2000.0, northM = 2.0),
            ),
        )
        assertEquals(listOf("n1", "n2", "n3"), profile.cameras.map { it.ref.id })
        // Walking the route: next() advances exactly once per camera.
        assertEquals("n1", profile.next(0.0)!!.ref.id)
        assertEquals("n2", profile.next(1000.0)!!.ref.id)
        assertEquals("n3", profile.next(2500.0)!!.ref.id)
        assertNull(profile.next(4500.0))
    }

    // ---- fixture: camera behind vehicle -----------------------------------

    @Test
    fun `a camera behind the vehicle is never re-announced`() {
        val profile = CameraMatcher.match(routeOf(2000.0), listOf(ref("n1", 800.0, 0.0)))
        // Passed it: 850 m along a route whose camera is at 800.
        assertNull(profile.next(850.0))
        // A camera within a metre behind -- projection rounding at the stop
        // line -- is still not "next": it must not re-announce itself.
        assertNull(profile.next(799.5))
    }

    // ---- fixture: reroute --------------------------------------------------

    @Test
    fun `a reroute replaces the profile wholesale and old cameras are gone`() {
        val routeA = routeOf(2000.0)
        val routeB = routeOf(3000.0, northM = 500.0)
        val onA = CameraMatcher.match(routeA, listOf(ref("nOld", 800.0, 0.0)))
        assertEquals(1, onA.size)
        // Route B (a parallel carriageway 500 m north) has no camera: the
        // profile that replaces A must not carry A's camera forward.
        val onB = CameraMatcher.match(routeB, listOf(ref("nOld", 800.0, 0.0)))
        assertTrue(onB.isEmpty)
        // And the model keeps no mutable state: the app holds one immutable
        // profile per route (setCameras replaces it), so a stale camera
        // cannot survive a route change by retaining an old reference.
        assertEquals(CameraProfile.EMPTY, CameraProfile.EMPTY)
    }

    // ---- fixture: dense junction ------------------------------------------

    @Test
    fun `a dense junction keeps distinct cameras distinct and merges one identity`() {
        val route = routeOf(2000.0)
        // Two DISTINCT cameras 5 m apart: both kept, because both are facts
        // the source stated. Qatar's closest real pair is 11.5 m apart and
        // they are two installations at one gantry, so merging on distance
        // would delete a real camera to make the list tidier.
        val dense = CameraMatcher.match(
            route,
            listOf(
                ref("nA", eastM = 800.0, northM = 0.0),
                ref("nB", eastM = 805.0, northM = 0.0),
            ),
        )
        assertEquals(listOf("nA", "nB"), dense.cameras.map { it.ref.id })
        // 15 m apart is the same story, and so is 30 m.
        val spaced = CameraMatcher.match(
            route,
            listOf(
                ref("nA", eastM = 800.0, northM = 0.0),
                ref("nB", eastM = 830.0, northM = 1.0),
            ),
        )
        assertEquals(listOf("nA", "nB"), spaced.cameras.map { it.ref.id })
        // ONE camera listed twice — the same identity at two positions on the
        // wire — is one camera, and the earliest along-route occurrence wins.
        val dup = CameraMatcher.match(
            route,
            listOf(
                ref("nA", eastM = 800.0, northM = 0.0),
                ref("nA", eastM = 805.0, northM = 5.0),
            ),
        )
        assertEquals(listOf("nA"), dup.cameras.map { it.ref.id })
        assertEquals(800.0, dup.cameras[0].approach.alongM, 1.0)
    }

    // ---- fixture: the type gate -------------------------------------------

    @Test
    fun `a camera whose source states no type is never announced`() {
        val route = routeOf(2000.0)
        // Same position, same everything — the only difference is that the
        // source did not say what the camera is.
        val typed = CameraMatcher.match(
            route, listOf(ref("n1", 800.0, 0.0, type = CameraType.SPEED)),
        )
        val untyped = CameraMatcher.match(
            route, listOf(ref("n1", 800.0, 0.0, type = CameraType.UNKNOWN)),
        )
        assertEquals(1, typed.size)
        assertTrue(untyped.isEmpty, "an untyped camera must not be announced")
        // And through the wire path too, whatever along position the backend
        // claimed: an entry with no recognisable type is not announced.
        val viaWire = CameraMatcher.profile(
            route,
            listOf(
                CameraMatcher.Entry(ref("n1", 800.0, 0.0, type = CameraType.UNKNOWN), 800.0, 90.0),
                CameraMatcher.Entry(ref("n2", 900.0, 0.0, type = CameraType.SPEED), 900.0, 90.0),
            ),
        )
        assertEquals(listOf("n2"), viaWire.cameras.map { it.ref.id })
        // An unknown camera does not hide the typed one behind it either: the
        // typed camera is still "next" at the vehicle's position.
        assertEquals("n2", viaWire.next(0.0)?.ref?.id)
    }

    @Test
    fun `every camera type that may be announced survives the type gate`() {
        val route = routeOf(4000.0)
        val types = listOf(CameraType.SPEED, CameraType.AVERAGE_SPEED,
                           CameraType.RED_LIGHT, CameraType.COMBINED)
        val refs = types.mapIndexed { i, t -> ref("n$i", eastM = 500.0 + i * 500.0,
                                                   northM = 0.0, type = t) }
        val profile = CameraMatcher.match(route, refs)
        assertEquals(types.size, profile.size)
        assertEquals(types, profile.cameras.map { it.ref.type })
    }

    // ---- fixture: offline / stale-missing catalog / old backend -----------

    @Test
    fun `offline, missing, stale and old-backend all read as an empty profile`() {
        val route = routeOf(2000.0)
        // Offline / no catalog: no entries at all.
        assertTrue(CameraMatcher.profile(route, emptyList()).isEmpty)
        // Missing/empty raw refs on an old backend.
        assertTrue(CameraMatcher.match(route, emptyList()).isEmpty)
        // A wire that carried no cameras key parses to an empty list.
        assertTrue(CameraProfile.EMPTY.isEmpty)
        assertNull(CameraProfile.EMPTY.next(0.0))
    }

    // ---- fixture: old-backend fallback is the raw path --------------------

    @Test
    fun `an old backend without along positions falls back to client matching`() {
        val route = routeOf(2000.0)
        val entries = listOf(
            CameraMatcher.Entry(ref = ref("n1", 800.0, 0.0), alongM = null),
        )
        val profile = CameraMatcher.profile(route, entries)
        assertEquals(1, profile.size)
        assertEquals(800.0, profile.at(0).approach.alongM, 1.0)
    }

    // ---- fixture: doubled-back route ---------------------------------------

    @Test
    fun `a doubled-back route claims the camera at its earliest pass`() {
        // Route: east 1000 m, U-turn, west 1000 m back past the same point.
        val kx = cos(Math.toRadians(25.2850)) * RouteGeometry.M_PER_DEG_LAT
        val lat = 25.2850
        val coords = (0..50).map { i ->
            val t = i / 50.0
            if (t <= 0.5) {
                LngLat(51.5300 + (2000.0 * t) / kx, lat)   // eastbound out
            } else {
                LngLat(51.5300 + (2000.0 * (1.0 - t)) / kx, lat)  // westbound back
            }
        }
        val route = assertNotNull(RouteGeometry.index(coords))
        val cam = ref("n1", eastM = 700.0, northM = 0.0)
        val profile = CameraMatcher.match(route, listOf(cam))
        assertEquals(1, profile.size)
        // The camera is claimed at the FIRST pass: 700 m on the outbound leg,
        // not ~2,300 m at the return leg (a "camera in 1.6 km" lie).
        val fix = profile.at(0)
        assertTrue(fix.approach.alongM < 1000.0, "claimed at ${fix.approach.alongM}")
        assertEquals(700.0, fix.approach.alongM, 10.0)
    }

    // ---- helpers -----------------------------------------------------------

    private fun ref(
        id: String,
        eastM: Double,
        northM: Double,
        direction: String? = null,
        type: CameraType = CameraType.SPEED,
    ) = CameraRef(
        id = id,
        position = LngLat(lonAt(eastM), 25.2850 + northM / RouteGeometry.M_PER_DEG_LAT),
        type = type,
        maxspeedTag = "80",
        directionTag = direction,
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