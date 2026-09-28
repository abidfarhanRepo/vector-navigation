package dev.vector.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The navigation maths, tested by running it (ADR-0075).
 *
 * Ported from vector-web/tests/geo.test.mjs, which exists because the web
 * client's navigation had NO executable coverage — its suite asserted against
 * index.html as text, so an off-route threshold wrong by 31x lived next to a
 * comment stating the correct value and no test could see it.
 */
class RouteGeometryTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val mpd = RouteGeometry.M_PER_DEG_LAT
    private val kx = cos(Math.toRadians(lat)) * mpd

    /** A straight ~1 km due-east run at Doha's latitude, as two shape points. */
    private val straight = listOf(LngLat(lng0, lat), LngLat(lng0 + 0.009931, lat))

    /** East for ~500 m, then north — a route with interior shape points. */
    private val bent = listOf(
        LngLat(lng0, lat),
        LngLat(lng0 + 0.002, lat),
        LngLat(lng0 + 0.004966, lat),
        LngLat(lng0 + 0.004966, lat + 0.0045),
    )

    @Test
    fun `haversine is symmetric and matches a known east-west span`() {
        val d = RouteGeometry.haversineM(lng0, lat, lng0 + 0.009931, lat)
        assertTrue(abs(d - 1000.0) < 5.0, "expected ~1000 m, got $d")
        val back = RouteGeometry.haversineM(lng0 + 0.009931, lat, lng0, lat)
        assertTrue(abs(d - back) < 1e-6)
    }

    @Test
    fun `a degenerate geometry yields no index rather than a half-built one`() {
        assertNull(RouteGeometry.index(emptyList()))
        assertNull(RouteGeometry.index(listOf(LngLat(lng0, lat))))
    }

    @Test
    fun `a point on the line between distant shape points reads zero off-route`() {
        // Nearest-VERTEX search would report ~500 m here: the midpoint of a 1 km
        // segment is 500 m from either endpoint while sitting on the road.
        val idx = assertNotNull(RouteGeometry.index(straight))
        val p = assertNotNull(idx.project(LngLat(lng0 + 0.009931 / 2, lat)))
        assertTrue(p.offsetM < 1.0, "on-line point should read ~0 m, got ${p.offsetM}")
        assertTrue(abs(p.alongM - 500.0) < 5.0, "expected ~500 m along, got ${p.alongM}")
    }

    @Test
    fun `offset is a true perpendicular distance in metres`() {
        val idx = assertNotNull(RouteGeometry.index(straight))
        val p = assertNotNull(idx.project(LngLat(lng0 + 0.009931 / 2, lat + 100.0 / mpd)))
        assertTrue(abs(p.offsetM - 100.0) < 2.0, "expected ~100 m, got ${p.offsetM}")
    }

    @Test
    fun `offset is isotropic - 100 m east reads the same as 100 m north`() {
        // Raw degree distance is NOT isotropic; this fails if the cos(lat) scale
        // is dropped from the projection frame.
        val idx = assertNotNull(RouteGeometry.index(straight))
        val north = assertNotNull(idx.project(LngLat(lng0 + 0.009931 / 2, lat + 100.0 / mpd)))
        val east = assertNotNull(idx.project(LngLat(lng0 + 0.009931 + 100.0 / kx, lat)))
        assertTrue(abs(north.offsetM - east.offsetM) < 2.0, "${north.offsetM} vs ${east.offsetM}")
    }

    @Test
    fun `along-route distance advances continuously, not in vertex-sized jumps`() {
        val idx = assertNotNull(RouteGeometry.index(straight))
        var prev = 0.0
        var m = 50.0
        while (m <= 950.0) {
            val p = assertNotNull(idx.project(LngLat(lng0 + m / kx, lat)))
            val step = p.alongM - prev
            assertTrue(abs(step - 50.0) < 3.0, "step at $m m was $step, expected ~50")
            prev = p.alongM
            m += 50.0
        }
    }

    @Test
    fun `projection clamps to the polyline rather than running past its ends`() {
        val idx = assertNotNull(RouteGeometry.index(straight))
        val before = assertNotNull(idx.project(LngLat(lng0 - 0.01, lat)))
        assertEquals(0.0, before.alongM)
        assertEquals(0.0, before.t)
        val after = assertNotNull(idx.project(LngLat(lng0 + 0.02, lat)))
        assertTrue(abs(after.alongM - idx.totalM) < 1e-6)
        assertEquals(1.0, after.t)
    }

    @Test
    fun `the nearest segment wins on a route that doubles back`() {
        val south = lat - 200.0 / mpd
        val idx = assertNotNull(
            RouteGeometry.index(
                listOf(
                    LngLat(lng0, lat),
                    LngLat(lng0 + 0.009931, lat),
                    LngLat(lng0 + 0.009931, south),
                    LngLat(lng0, south),
                )
            )
        )
        val p = assertNotNull(idx.project(LngLat(lng0 + 0.005, south + 10.0 / mpd)))
        assertEquals(2, p.segIdx, "should snap to the return leg, not the outbound one")
        assertTrue(p.offsetM < 12.0, "expected ~10 m, got ${p.offsetM}")
    }

    @Test
    fun `a zero-length segment does not produce NaN`() {
        val idx = assertNotNull(
            RouteGeometry.index(
                listOf(LngLat(lng0, lat), LngLat(lng0, lat), LngLat(lng0 + 0.009931, lat))
            )
        )
        val p = assertNotNull(idx.project(LngLat(lng0 + 0.005, lat)))
        assertTrue(p.offsetM.isFinite() && p.alongM.isFinite())
    }

    @Test
    fun `pointAt round-trips with project`() {
        val idx = assertNotNull(RouteGeometry.index(straight))
        for (target in listOf(0.0, 137.0, 500.0, idx.totalM - 0.5)) {
            val pt = assertNotNull(idx.pointAt(target))
            val back = assertNotNull(idx.project(pt.position))
            assertTrue(abs(back.alongM - target) < 1.0, "$target -> ${back.alongM}")
        }
    }

    @Test
    fun `pointAt clamps at both ends`() {
        val idx = assertNotNull(RouteGeometry.index(straight))
        val start = assertNotNull(idx.pointAt(-50.0))
        assertTrue(abs(start.position.lng - lng0) < 1e-9)
        val end = assertNotNull(idx.pointAt(idx.totalM + 5000.0))
        assertTrue(abs(end.position.lng - straight[1].lng) < 1e-9)
    }

    @Test
    fun `pointAt finds the right segment on a many-vertex route`() {
        val coords = (0 until 100).map { LngLat(lng0 + (it * 10.0) / kx, lat) }
        val idx = assertNotNull(RouteGeometry.index(coords))
        val pt = assertNotNull(idx.pointAt(455.0))
        assertEquals(45, pt.segIdx, "expected segment 45, got ${pt.segIdx}")
        assertTrue(abs(pt.t - 0.5) < 0.05)
    }

    @Test
    fun `bearing reports compass degrees`() {
        assertTrue(abs(RouteGeometry.bearingDeg(LngLat(lng0, lat), LngLat(lng0 + 0.01, lat)) - 90) < 0.5)
        assertTrue(abs(RouteGeometry.bearingDeg(LngLat(lng0, lat), LngLat(lng0, lat + 0.01)) - 0) < 0.5)
        val west = RouteGeometry.bearingDeg(LngLat(lng0, lat), LngLat(lng0 - 0.01, lat))
        assertTrue(abs(west - 270) < 0.5, "west, got $west")
    }

    @Test
    fun `angle difference takes the short way around the wrap`() {
        assertEquals(20.0, RouteGeometry.angDiffDeg(10.0, 350.0))
        assertEquals(-20.0, RouteGeometry.angDiffDeg(350.0, 10.0))
        assertEquals(0.0, RouteGeometry.angDiffDeg(0.0, 0.0))
    }

    // ---- cutting the route up (V7 Stage 4) ---------------------------------

    @Test
    fun `a cut interpolates its endpoints instead of snapping to a vertex`() {
        // The whole reason `between` exists rather than a vertex slice: OSM
        // motorway geometry runs 200 m between shape points, and this fixture
        // is the extreme case — ONE 1 km segment with no interior vertex at
        // all. A snapped cut would return the whole kilometre for every piece.
        val idx = assertNotNull(RouteGeometry.index(straight))
        val part = idx.between(400.0, 600.0)
        assertEquals(2, part.size)
        val cut = assertNotNull(RouteGeometry.index(part))
        assertTrue(abs(cut.totalM - 200.0) < 1.0, "cut is ${cut.totalM} m, expected ~200")
        // And it starts where it was asked to, not at the nearest shape point.
        val start = assertNotNull(idx.project(part.first()))
        assertTrue(abs(start.alongM - 400.0) < 1.0, "cut starts at ${start.alongM} m")
    }

    @Test
    fun `consecutive cuts tile the route with no gap and no overlap`() {
        // A gap is a length of route with no ribbon on it; an overlap is two
        // ribbons at two different lateral offsets drawn over each other. Both
        // are visible, and both look like a rendering bug rather than a data
        // one.
        val idx = assertNotNull(RouteGeometry.index(bent))
        val edges = listOf(0.0, 120.0, 330.0, 331.0, 800.0, idx.totalM)
        for (i in 1 until edges.size) {
            val a = idx.between(edges[i - 1], edges[i])
            assertTrue(a.size >= 2, "piece $i is degenerate")
            if (i + 1 < edges.size) {
                val b = idx.between(edges[i], edges[i + 1])
                assertEquals(a.last(), b.first(), "piece $i ends where piece ${i + 1} begins")
            }
        }
        // The pieces together are the route: same start, same end, same length.
        assertEquals(bent.first(), idx.between(0.0, idx.totalM).first())
        assertEquals(bent.last(), idx.between(0.0, idx.totalM).last())
    }

    @Test
    fun `a cut keeps every shape point inside it`() {
        // Dropping interior vertices would straighten the curve the cut covers
        // — a ribbon that cuts the corner of the road it is drawn on.
        val idx = assertNotNull(RouteGeometry.index(bent))
        val whole = idx.between(0.0, idx.totalM)
        for (v in bent) assertTrue(whole.contains(v), "vertex $v was dropped by the cut")
    }

    @Test
    fun `an empty or inverted span produces nothing rather than a degenerate line`() {
        val idx = assertNotNull(RouteGeometry.index(straight))
        assertTrue(idx.between(500.0, 500.0).isEmpty())
        assertTrue(idx.between(600.0, 400.0).isEmpty())
        // Clamped, not extrapolated: a route does not continue past its end.
        assertTrue(idx.between(idx.totalM, idx.totalM + 500.0).isEmpty())
        assertEquals(2, idx.between(-100.0, 50.0).size)
    }

    // ---- the lateral normal (V7 Stage 4) -----------------------------------

    @Test
    fun `a lateral offset moves sideways and not along`() {
        val at = LngLat(lng0, lat)
        val moved = RouteGeometry.offsetPoint(at, bearingDeg = 0.0, offsetM = 10.0)
        // Due north travel: right is east, and the latitude must not move.
        assertTrue(moved.lng > at.lng)
        assertEquals(at.lat, moved.lat, 1e-12)
        assertEquals(10.0, RouteGeometry.haversineM(at.lng, at.lat, moved.lng, moved.lat), 0.01)
    }

    @Test
    fun `offsetting by zero is the identity, and the sign is symmetric`() {
        val at = LngLat(lng0, lat)
        assertEquals(at, RouteGeometry.offsetPoint(at, 37.0, 0.0))
        val r = RouteGeometry.offsetPoint(at, 37.0, 6.0)
        val l = RouteGeometry.offsetPoint(at, 37.0, -6.0)
        assertEquals(at.lng, (r.lng + l.lng) / 2, 1e-12)
        assertEquals(at.lat, (r.lat + l.lat) / 2, 1e-12)
    }

    @Test
    fun `the regression this port exists to prevent`() {
        // vector-web compared a SQUARED degree distance to 0.0008 and called it
        // "~100m". sqrt(0.0008) deg is ~3,150 m at this latitude, so a missed turn
        // did not reroute until the driver was three kilometres away.
        val wrongThresholdM = Math.sqrt(0.0008) * mpd
        assertTrue(wrongThresholdM > 3000, "the old threshold really was ${wrongThresholdM.toInt()} m")

        val idx = assertNotNull(RouteGeometry.index(straight))
        val off = assertNotNull(idx.project(LngLat(lng0 + 0.005, lat + 150.0 / mpd)))
        assertTrue(off.offsetM > 100, "150 m away should exceed a 100 m rule, got ${off.offsetM}")
        assertTrue(off.offsetM < wrongThresholdM, "which the old 3.1 km threshold would have missed")
    }
}
