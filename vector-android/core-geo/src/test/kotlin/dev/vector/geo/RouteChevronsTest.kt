package dev.vector.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Do the direction marks point the way the driver is going, and stay on the road?
 *
 * Two things can go wrong here and neither is visible on a screenshot until it
 * is very wrong. A chevron can point backwards — the single worst thing a
 * direction mark can do, and what the text version of this got for free, since
 * `text-keep-upright` flips glyphs on exactly the legs that run right-to-left
 * across the screen. And it can drift off the ribbon on a curve, because the
 * obvious bearing — of the segment the mark happens to land on — cuts the
 * corner.
 */
class RouteChevronsTest {

    /** Metres between two positions, so assertions are in the units the spec is in. */
    private fun m(a: LngLat, b: LngLat) = RouteGeometry.haversineM(a.lng, a.lat, b.lng, b.lat)

    /** A straight run of [n] points [stepM] apart, heading [brgDeg] from Doha. */
    private fun line(n: Int, stepM: Double, brgDeg: Double): RouteIndex {
        val lat0 = 25.2854
        val lng0 = 51.5310
        val b = Math.toRadians(brgDeg)
        val pts = (0 until n).map { i ->
            val d = stepM * i
            LngLat(
                lng0 + d * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(lat0))),
                lat0 + d * Math.cos(b) / 111_320.0,
            )
        }
        return RouteGeometry.index(pts)!!
    }

    private fun mid(mk: List<LngLat>) =
        LngLat((mk[0].lng + mk[2].lng) / 2, (mk[0].lat + mk[2].lat) / 2)

    @Test
    fun `a chevron points along the route, not against it`() {
        // Every compass direction, because the failure this replaces was
        // direction-dependent: the glyph version reversed itself on exactly the
        // legs that ran right-to-left across the screen.
        for (brg in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)) {
            val r = line(40, 25.0, brg)
            val marks = RouteChevrons.marks(r, spacingM = 50.0, lengthM = 4.0, halfWidthM = 2.0)
            assertTrue(marks.size > 10, "no chevrons on a 975 m route heading $brg")
            for (mk in marks) {
                val apexBrg = RouteGeometry.bearingDeg(mid(mk), mk[1])
                assertEquals(
                    0.0, RouteGeometry.angDiffDeg(apexBrg, brg), 1.0,
                    "a chevron on a $brg leg points $apexBrg",
                )
            }
        }
    }

    @Test
    fun `a chevron is the size it was asked to be`() {
        val r = line(60, 20.0, 60.0)
        val marks = RouteChevrons.marks(r, spacingM = 40.0, lengthM = 3.0, halfWidthM = 1.8)
        assertTrue(marks.isNotEmpty())
        for (mk in marks) {
            assertEquals(3, mk.size, "a chevron has three points")
            assertEquals(3.0, m(mid(mk), mk[1]), 0.12, "the apex is not 3 m ahead of the wings")
            assertEquals(3.6, m(mk[0], mk[2]), 0.12, "the chevron is not 3.6 m across")
            assertEquals(m(mk[0], mid(mk)), m(mk[2], mid(mk)), 0.05,
                         "the wings are not the same distance out")
        }
    }

    @Test
    fun `the marks are evenly spaced however the geometry is shaped`() {
        // OSM motorway geometry runs 200 m between shape points and a
        // residential street runs 10 m. Placing a mark per vertex would put
        // twenty on the second for every one on the first.
        for (step in listOf(5.0, 25.0, 200.0)) {
            val r = line((2000.0 / step).toInt() + 1, step, 20.0)
            val apexes = RouteChevrons.marks(r, 22.0, 3.0, 1.8).map { it[1] }
            for (i in 1 until apexes.size) {
                assertEquals(22.0, m(apexes[i - 1], apexes[i]), 0.6,
                             "with $step m shape points the marks are unevenly spaced")
            }
        }
    }

    @Test
    fun `a chevron on a curve follows the curve rather than cutting it`() {
        // A quarter circle of radius 60 m, about as tight as a Doha slip road
        // gets. Taking the bearing from whichever segment the apex lands on
        // would swing the mark off the inside of the bend.
        val lat0 = 25.2854
        val lng0 = 51.5310
        val pts = (0..90 step 3).map { deg ->
            val a = Math.toRadians(deg.toDouble())
            LngLat(
                lng0 + 60.0 * Math.sin(a) / (111_320.0 * Math.cos(Math.toRadians(lat0))),
                lat0 + 60.0 * (1 - Math.cos(a)) / 111_320.0,
            )
        }
        val r = RouteGeometry.index(pts)!!
        val marks = RouteChevrons.marks(r, spacingM = 10.0, lengthM = 3.0, halfWidthM = 1.8)
        assertTrue(marks.size >= 5, "no chevrons on a 94 m curve")
        for (mk in marks) {
            for (pt in mk) {
                // 2.8 m is the ribbon's half-width. A mark that has left the
                // ribbon is worse than no mark.
                val off = r.project(pt)!!.offsetM
                assertTrue(off <= 2.8, "a chevron point sits ${"%.2f".format(off)} m off the route")
            }
        }
    }

    @Test
    fun `no chevron hangs off either end of the route`() {
        val r = line(20, 20.0, 100.0)
        val marks = RouteChevrons.marks(r, spacingM = 22.0, lengthM = 3.0, halfWidthM = 1.8)
        assertTrue(marks.isNotEmpty())
        for (mk in marks) {
            for (pt in mk) {
                val a = r.project(pt)!!.alongM
                assertTrue(a >= 0.0, "a chevron starts before the origin")
                assertTrue(a <= r.totalM, "a chevron runs past the destination")
            }
        }
    }

    @Test
    fun `a route too short to carry a mark carries none`() {
        // Not empty by accident — a 10 m hop to a car park entrance is a real
        // route, and half a chevron on it is worse than none.
        val r = line(3, 4.0, 0.0)
        assertEquals(0, RouteChevrons.marks(r, 22.0, 3.0, 1.8).size)
    }

    @Test
    fun `a degenerate route is refused rather than drawn somewhere arbitrary`() {
        val repeated = RouteGeometry.index(List(8) { LngLat(51.53, 25.28) })
        if (repeated != null) {
            assertEquals(0, RouteChevrons.marks(repeated, 22.0, 3.0, 1.8).size,
                         "a zero-length route produced chevrons")
        }
    }

    @Test
    fun `a very long route cannot make the source unbounded`() {
        val r = line(4000, 25.0, 10.0)   // 100 km
        val marks = RouteChevrons.marks(r, 22.0, 3.0, 1.8, limit = 500)
        assertEquals(500, marks.size, "the cap did not hold")
        assertTrue(r.project(marks.first()[1])!!.alongM < 100.0,
                   "a capped route lost its first chevrons rather than its last")
    }

    @Test
    fun `a nonsense spacing is refused rather than looping forever`() {
        val r = line(50, 20.0, 0.0)
        assertEquals(0, RouteChevrons.marks(r, 0.0, 3.0, 1.8).size)
        assertEquals(0, RouteChevrons.marks(r, -5.0, 3.0, 1.8).size)
    }

    @Test
    fun `the whole of a realistic Doha drive is marked in reasonable time`() {
        // 14 km, the length of the Souq Waqif to Villaggio route, at the real
        // spacing. This runs on the main thread when a route arrives, so it has
        // to be nearer a millisecond than a frame.
        val r = line(1400, 10.0, 210.0)
        val t0 = System.nanoTime()
        val marks = RouteChevrons.marks(r, 22.0, 3.0, 1.8)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(636.0, marks.size.toDouble(), 3.0, "a 14 km route did not produce ~636 marks")
        assertTrue(ms < 60.0, "marking a 14 km route took ${"%.1f".format(ms)} ms")
    }

    @Test
    fun `chevrons hold their shape wherever in Qatar the route is`() {
        // The perpendicular offset converts metres to degrees at the mark's own
        // latitude. Getting that wrong shows up as chevrons the right size in
        // Doha and the wrong size everywhere else, which is the kind of thing
        // nobody notices until a judge drives to Al Ruwais.
        for (lat in listOf(24.5, 25.2854, 26.2)) {
            val r = RouteGeometry.index((0..40).map { LngLat(51.0 + it * 0.0002, lat) })!!
            val marks = RouteChevrons.marks(r, 30.0, 3.0, 1.8)
            assertTrue(marks.isNotEmpty(), "no chevrons at latitude $lat")
            for (mk in marks) {
                assertEquals(3.6, m(mk[0], mk[2]), 0.12,
                             "a chevron at latitude $lat is not 3.6 m across")
            }
        }
    }

    @Test
    fun `the wings straddle the route rather than sitting to one side of it`() {
        val r = line(60, 20.0, 75.0)
        for (mk in RouteChevrons.marks(r, 40.0, 3.0, 1.8)) {
            val a = RouteGeometry.bearingDeg(r.project(mk[0])!!.position, mk[0])
            val b = RouteGeometry.bearingDeg(r.project(mk[2])!!.position, mk[2])
            assertEquals(180.0, abs(RouteGeometry.angDiffDeg(a, b)), 6.0,
                         "both wings are on the same side of the route")
        }
    }
}
