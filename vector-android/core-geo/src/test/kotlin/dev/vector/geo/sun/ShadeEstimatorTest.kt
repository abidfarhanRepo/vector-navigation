package dev.vector.geo.sun

import dev.vector.geo.LngLat
import dev.vector.geo.journey.AnnotationKind
import dev.vector.geo.journey.Provenance
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shade model, on real Doha orientations.
 *
 * The assertions here are about BEHAVIOUR — ordering, direction of change,
 * bounds — rather than about the exact number a particular facade assumption
 * happens to produce, because the facade assumption is the part of this model
 * most likely to be retuned. Pinning 0.291 would turn a deliberate improvement
 * into a test failure; pinning "an east-west street is worse in the late
 * afternoon than at midday" would not.
 */
class ShadeEstimatorTest {

    private companion object {
        val QATAR: ZoneOffset = ZoneOffset.ofHours(3)
        fun at(hour: Int, minute: Int = 0, day: Int = 13, month: Int = 9): Long =
            ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, QATAR).toInstant().toEpochMilli()

        // Two streets in Msheireb, one of each orientation.
        val NS_A = LngLat(51.5310, 25.2850)
        val NS_B = LngLat(51.5310, 25.2880)   // due north, ~333 m
        val EW_A = LngLat(51.5310, 25.2850)
        val EW_B = LngLat(51.5340, 25.2850)   // due east, ~302 m

        fun ns(highway: String = "residential") = WalkSegment(NS_A, NS_B, highway = highway)
        fun ew(highway: String = "residential") = WalkSegment(EW_A, EW_B, highway = highway)
    }

    // ---- determinism -------------------------------------------------------

    @Test
    fun `the same segment at the same instant always scores the same`() {
        val t = at(15)
        val first = ShadeEstimator.segment(ns(), t)
        repeat(25) {
            assertEquals(first.exposure, ShadeEstimator.segment(ns(), t).exposure, 0.0)
        }
    }

    // ---- orientation -------------------------------------------------------

    @Test
    fun `a north-south street is exposed around solar noon`() {
        // The sun is nearly due south, so it looks straight ALONG a north-south
        // street rather than across it, and no facade is between it and the
        // walker. This is the V7 plan's own stated case.
        val e = ShadeEstimator.segment(ns(), at(11, 30))
        assertEquals(SunState.DAY, e.sunState)
        assertTrue(e.exposure > 0.7, "expected exposed, got ${e.exposure}")
    }

    @Test
    fun `the same north-south street is shaded in the late afternoon`() {
        // By 16:00 the sun is in the west, side-on to the street, and the
        // assumed facade reaches across. Also the plan's stated case.
        val e = ShadeEstimator.segment(ns(), at(16))
        assertEquals(SunState.DAY, e.sunState)
        assertTrue(e.exposure < 0.1, "expected shaded, got ${e.exposure}")
    }

    @Test
    fun `an east-west street behaves the opposite way round`() {
        val middayEw = ShadeEstimator.segment(ew(), at(11, 30)).exposure
        val afternoonEw = ShadeEstimator.segment(ew(), at(16)).exposure
        val middayNs = ShadeEstimator.segment(ns(), at(11, 30)).exposure
        val afternoonNs = ShadeEstimator.segment(ns(), at(16)).exposure

        assertTrue(afternoonEw > middayEw, "east-west should worsen into the afternoon")
        assertTrue(afternoonNs < middayNs, "north-south should improve into the afternoon")
    }

    @Test
    fun `two orientations at the same instant do not score the same`() {
        val t = at(16)
        assertTrue(
            abs(ShadeEstimator.segment(ns(), t).exposure - ShadeEstimator.segment(ew(), t).exposure) > 0.5,
            "orientation must matter",
        )
    }

    // ---- time of day -------------------------------------------------------

    @Test
    fun `exposure changes over the course of a day`() {
        val readings = (6..17).map { ShadeEstimator.segment(ns(), at(it)).exposure }
        assertTrue(readings.distinct().size > 1, "a static curve would mean time is ignored")
        assertTrue(readings.all { it in 0.0..1.0 })
    }

    @Test
    fun `an east-west walk is least exposed near solar noon`() {
        // The V7 acceptance criterion, restricted to daylight: sweeping a time
        // slider across a fixed route puts the exposure extremum within 30
        // minutes of solar noon, which at Doha is 11:30 local (NOT the 12:22
        // the plan states -- see SolarPositionTest).
        var bestMinute = -1
        var best = Double.MAX_VALUE
        for (m in 6 * 60..18 * 60 step 15) {
            val e = ShadeEstimator.segment(ew(), at(m / 60, m % 60))
            if (e.sunState != SunState.DAY) continue
            if (e.exposure < best) { best = e.exposure; bestMinute = m }
        }
        assertTrue(bestMinute > 0)
        assertTrue(
            abs(bestMinute - (11 * 60 + 30)) <= 30,
            "minimum at ${bestMinute / 60}:${bestMinute % 60}, expected within 30 min of 11:30",
        )
    }

    // ---- night and low sun -------------------------------------------------

    @Test
    fun `at night there is no sun to be exposed to`() {
        val e = ShadeEstimator.segment(ns(), at(2))
        assertEquals(SunState.NIGHT, e.sunState)
        assertEquals(0.0, e.exposure, 1e-9)
    }

    @Test
    fun `a low sun is reported as such rather than modelled`() {
        // Just after sunrise (~05:21) the sun is up but under 3 deg, where
        // height/tan(altitude) runs away and the answer would be decided by the
        // horizon rather than the street.
        val e = ShadeEstimator.segment(ns(), at(5, 30))
        assertEquals(SunState.LOW_SUN, e.sunState)
        assertTrue(e.confidence < 0.5, "a low-sun answer must not be confident")
    }

    @Test
    fun `night is stated honestly rather than as full shade`() {
        val route = ShadeEstimator.route(listOf(ns()), at(2))
        assertEquals("no direct sun", route.shadeLabel())
    }

    // ---- the tags that override the model ---------------------------------

    @Test
    fun `a covered way is shaded at every hour of every day`() {
        // The plan's third stated case.
        for (hour in 0..23) {
            for (month in intArrayOf(1, 6, 9, 12)) {
                val e = ShadeEstimator.segment(
                    WalkSegment(NS_A, NS_B, highway = "footway", covered = true),
                    at(hour, day = 21, month = month),
                )
                assertEquals(0.0, e.exposure, 1e-9, "covered way exposed at $hour:00 in month $month")
            }
        }
    }

    @Test
    fun `tunnels indoor ways and corridors are shaded too`() {
        val t = at(11, 30)
        assertEquals(0.0, ShadeEstimator.segment(WalkSegment(NS_A, NS_B, "footway", tunnel = true), t).exposure, 1e-9)
        assertEquals(0.0, ShadeEstimator.segment(WalkSegment(NS_A, NS_B, "footway", indoor = true), t).exposure, 1e-9)
        assertEquals(0.0, ShadeEstimator.segment(WalkSegment(NS_A, NS_B, "corridor"), t).exposure, 1e-9)
    }

    // ---- where the model declines to answer -------------------------------

    @Test
    fun `an open plaza is called exposed with low confidence, not shaded`() {
        // The honest case. Msheireb's squares and Aspire's surface car parks
        // have no facade beside them, so running the facade model there would
        // promise shade that is not present. The model declines instead.
        val plaza = WalkSegment(NS_A, NS_B, highway = "pedestrian", area = true)
        val e = ShadeEstimator.segment(plaza, at(16))
        assertEquals(1.0, e.exposure, 1e-9)
        assertTrue(e.confidence < 0.3, "an open area must not be a confident answer")

        // ... and the same geometry NOT flagged as an area is modelled normally,
        // which is what makes the flag load-bearing rather than decorative.
        val street = WalkSegment(NS_A, NS_B, highway = "pedestrian")
        assertTrue(ShadeEstimator.segment(street, at(16)).exposure < 0.5)
    }

    @Test
    fun `an unrecognised highway class is never credited with shade`() {
        val e = ShadeEstimator.segment(WalkSegment(NS_A, NS_B, highway = "raceway"), at(16))
        assertEquals(1.0, e.exposure, 1e-9)
        assertTrue(e.confidence < 0.3)
    }

    @Test
    fun `a missing highway class is never credited with shade`() {
        val e = ShadeEstimator.segment(WalkSegment(NS_A, NS_B, highway = null), at(16))
        assertEquals(1.0, e.exposure, 1e-9)
    }

    @Test
    fun `a wide arterial is exposed even when the sun is side-on`() {
        // primary/trunk carry an assumed facade height of zero: they are too
        // wide for anything beside them to shade the footway.
        val e = ShadeEstimator.segment(ns(highway = "primary"), at(16))
        assertEquals(1.0, e.exposure, 1e-9)
        // The same orientation and instant on a residential street IS shaded,
        // so this is the road class talking, not the geometry.
        assertTrue(ShadeEstimator.segment(ns(highway = "residential"), at(16)).exposure < 0.1)
    }

    // ---- degenerate geometry ----------------------------------------------

    @Test
    fun `a zero-length segment makes no claim at all`() {
        val e = ShadeEstimator.segment(WalkSegment(NS_A, NS_A, highway = "footway"), at(16))
        assertEquals(SunState.UNKNOWN, e.sunState)
        assertEquals(0.0, e.confidence, 1e-9)
        assertNull(e.bearingDeg, "a point has no bearing and none must be invented")
    }

    @Test
    fun `a sub-metre segment is treated as degenerate rather than given a bearing`() {
        val nudged = LngLat(NS_A.lng + 0.000002, NS_A.lat)
        val e = ShadeEstimator.segment(WalkSegment(NS_A, nudged, highway = "footway"), at(16))
        assertEquals(SunState.UNKNOWN, e.sunState)
    }

    @Test
    fun `an empty route is reported as not estimated`() {
        val route = ShadeEstimator.route(emptyList<WalkSegment>(), at(16))
        assertEquals(0.0, route.lengthM, 1e-9)
        assertEquals(SunState.UNKNOWN, route.sunState)
        assertEquals("shade not estimated", route.shadeLabel())
    }

    @Test
    fun `a route of only degenerate segments is reported as not estimated`() {
        val route = ShadeEstimator.route(
            listOf(WalkSegment(NS_A, NS_A, "footway"), WalkSegment(EW_A, EW_A, "footway")),
            at(16),
        )
        assertEquals(SunState.UNKNOWN, route.sunState)
        assertEquals(0.0, route.lengthM, 1e-9)
    }

    @Test
    fun `a degenerate segment does not drag down a real route`() {
        val real = ShadeEstimator.route(listOf(ns()), at(16))
        val padded = ShadeEstimator.route(
            listOf(WalkSegment(NS_A, NS_A, "footway"), ns(), WalkSegment(EW_B, EW_B, "footway")),
            at(16),
        )
        assertEquals(real.exposure, padded.exposure, 1e-9)
        assertEquals(real.lengthM, padded.lengthM, 1e-6)
    }

    // ---- route aggregation -------------------------------------------------

    @Test
    fun `a route's exposure is length-weighted, not a plain mean`() {
        // One long exposed leg and one short shaded leg at 16:00. A plain mean
        // would say 0.5; the answer must sit near the long leg.
        val longExposed = WalkSegment(EW_A, EW_B, highway = "residential")          // ~302 m, exposed
        val shortShaded = WalkSegment(NS_A, LngLat(51.5310, 25.28527), "residential") // ~30 m, shaded
        val route = ShadeEstimator.route(listOf(longExposed, shortShaded), at(16))

        assertTrue(route.exposure > 0.8, "expected the long exposed leg to dominate, got ${route.exposure}")
        assertEquals(route.lengthM, longExposed.lengthMetres() + shortShaded.lengthMetres(), 1.0)
    }

    @Test
    fun `shaded and exposed metres add up to the route length`() {
        val route = ShadeEstimator.route(listOf(ns(), ew()), at(16))
        assertEquals(route.lengthM, route.shadedM + route.exposedM, 1e-6)
        assertEquals(1.0, route.exposure + route.shadeScore, 1e-9)
    }

    @Test
    fun `a mixed route lands between its two legs`() {
        val t = at(16)
        val nsOnly = ShadeEstimator.route(listOf(ns()), t).exposure
        val ewOnly = ShadeEstimator.route(listOf(ew()), t).exposure
        val mixed = ShadeEstimator.route(listOf(ns(), ew()), t).exposure
        assertTrue(mixed > minOf(nsOnly, ewOnly) && mixed < maxOf(nsOnly, ewOnly), "got $mixed")
    }

    @Test
    fun `the polyline convenience builds the same answer as explicit segments`() {
        val t = at(16)
        val viaCoords = ShadeEstimator.route(listOf(NS_A, NS_B, EW_B), t, highway = "residential")
        val viaSegments = ShadeEstimator.route(
            listOf(
                WalkSegment(NS_A, NS_B, "residential"),
                WalkSegment(NS_B, EW_B, "residential"),
            ),
            t,
        )
        assertEquals(viaSegments.exposure, viaCoords.exposure, 1e-9)
    }

    // ---- comparing two candidate walks ------------------------------------

    @Test
    fun `the cooler of two walks is identified`() {
        val t = at(16)
        val shaded = ShadeEstimator.route(listOf(ns()), t)
        val sunny = ShadeEstimator.route(listOf(ew()), t)

        val comparison = shaded.comparedTo(sunny)
        assertTrue(comparison.meaningful)
        assertEquals(shaded, comparison.cooler)
        assertTrue(comparison.deltaPoints > 20.0, "got ${comparison.deltaPoints} points")
    }

    @Test
    fun `comparison is symmetric in sign`() {
        val t = at(16)
        val shaded = ShadeEstimator.route(listOf(ns()), t)
        val sunny = ShadeEstimator.route(listOf(ew()), t)
        assertEquals(shaded.comparedTo(sunny).deltaPoints, -sunny.comparedTo(shaded).deltaPoints, 1e-9)
        assertEquals(shaded, sunny.comparedTo(shaded).cooler)
    }

    @Test
    fun `a cooler route is not offered when it is not cooler`() {
        val t = at(16)
        val a = ShadeEstimator.route(listOf(ns()), t)
        val b = ShadeEstimator.route(listOf(ns()), t)
        val comparison = a.comparedTo(b)
        assertTrue(!comparison.meaningful)
        assertNull(comparison.cooler, "an identical route must not be offered as cooler")
    }

    @Test
    fun `no cooler route is offered at night`() {
        val t = at(2)
        val a = ShadeEstimator.route(listOf(ns()), t)
        val b = ShadeEstimator.route(listOf(ew()), t)
        assertNull(a.comparedTo(b).cooler, "there is no shade to prefer in the dark")
    }

    // ---- provenance --------------------------------------------------------

    @Test
    fun `every estimate is marked modelled`() {
        val route = ShadeEstimator.route(listOf(ns(), ew()), at(16))
        assertEquals(Provenance.MODELLED, route.provenance)
        assertTrue(route.segments.all { it.provenance == Provenance.MODELLED })

        val annotation = route.annotation()
        assertEquals(Provenance.MODELLED, annotation.provenance)
        assertEquals(AnnotationKind.EXPOSURE, annotation.kind)
        assertEquals(route.exposure, annotation.magnitude, 1e-9)
        assertNotNull(annotation.confidence)
    }

    @Test
    fun `the only sanctioned label always says estimated`() {
        val label = ShadeEstimator.route(listOf(ns()), at(16)).shadeLabel()
        assertTrue(label.contains("estimated"), "got '$label'")
        assertTrue(label.startsWith("~"), "a modelled figure must read as approximate: '$label'")
    }

    @Test
    fun `a daytime label never states a bare percentage`() {
        for (hour in 6..17) {
            val label = ShadeEstimator.route(listOf(ns()), at(hour)).shadeLabel()
            if (label.contains("%")) {
                assertTrue(label.contains("estimated"), "bare percentage at $hour:00: '$label'")
            }
        }
    }

    @Test
    fun `confidence never claims certainty about a facade it assumed`() {
        // The facade is the guess in this model, and no answer that rests on it
        // may present as certain.
        val e = ShadeEstimator.segment(ns(), at(16))
        assertTrue(e.confidence < 0.8, "a modelled facade must not be near-certain: ${e.confidence}")
        assertTrue(e.confidence > 0.0)
    }

    // ---- bounds ------------------------------------------------------------

    @Test
    fun `exposure stays inside zero and one across a whole year`() {
        val classes = listOf("footway", "residential", "secondary", "primary", "steps", "service")
        var t = at(0, day = 1, month = 1)
        repeat(365 * 2) {
            for (cls in classes) {
                for (seg in listOf(ns(cls), ew(cls))) {
                    val e = ShadeEstimator.segment(seg, t)
                    assertTrue(e.exposure in 0.0..1.0, "${e.exposure} for $cls at $t")
                    assertTrue(e.confidence in 0.0..1.0)
                }
            }
            t += 12 * 3_600_000L
        }
    }
}

/** Test-local length helper, so the expectations do not re-import the estimator's maths. */
private fun WalkSegment.lengthMetres(): Double =
    dev.vector.geo.RouteGeometry.haversineM(from.lng, from.lat, to.lng, to.lat)
