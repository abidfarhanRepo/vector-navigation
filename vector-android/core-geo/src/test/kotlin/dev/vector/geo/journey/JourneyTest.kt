package dev.vector.geo.journey

import dev.vector.geo.LngLat
import dev.vector.geo.sun.ShadeEstimator
import dev.vector.geo.sun.SunState
import dev.vector.geo.sun.WalkSegment
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The journey: composing a drive and a walk into one answer, and deciding
 * whether a cooler walk is worth offering.
 */
class JourneyTest {

    private companion object {
        val QATAR: ZoneOffset = ZoneOffset.ofHours(3)
        fun at(hour: Int, minute: Int = 0): Long =
            ZonedDateTime.of(2026, 9, 13, hour, minute, 0, 0, QATAR).toInstant().toEpochMilli()

        val A = LngLat(51.5310, 25.2850)
        val NORTH = LngLat(51.5310, 25.2880)   // ~333 m due north
        val EAST = LngLat(51.5340, 25.2850)    // ~302 m due east

        fun walk(to: LngLat, highway: String = "residential", durationS: Double = 240.0) =
            WalkLeg(
                segments = listOf(WalkSegment(A, to, highway = highway)),
                distanceM = 300.0,
                durationS = durationS,
            )

        fun drive(durationS: Double = 840.0) =
            DriveLeg(geometry = listOf(A, EAST), distanceM = 9_000.0, durationS = durationS)
    }

    // ---- the one line ------------------------------------------------------

    @Test
    fun `the summary line is the whole product`() {
        val j = Journey(
            destinationName = "Villaggio Mall",
            destination = EAST,
            drive = drive(durationS = 840.0),
            walk = walk(NORTH, durationS = 360.0),
        )
        assertEquals("14 min drive · 6 min walk", j.summaryLine())
    }

    @Test
    fun `a journey with no walk reads exactly as Vector always did`() {
        val j = Journey("Somewhere", EAST, drive = drive(840.0))
        assertEquals("14 min drive", j.summaryLine())
        assertFalse(j.hasWalk)
    }

    @Test
    fun `the summary line never claims a temperature`() {
        // The V7 plan's version ends "· 42 °C". Vector has no weather source,
        // and a fabricated number on the most prominent line in the app is
        // worse than an absent one.
        val j = Journey("Villaggio Mall", EAST, drive = drive(), walk = walk(NORTH))
        assertFalse(j.summaryLine().contains("°"))
        assertFalse(j.summaryLine().contains("C"))
    }

    @Test
    fun `a leg that exists never rounds away to zero minutes`() {
        assertEquals(1, Journey.minutes(20.0))
        assertEquals(0, Journey.minutes(0.0))
        assertEquals(6, Journey.minutes(360.0))
    }

    @Test
    fun `totals add the legs up`() {
        val j = Journey("X", EAST, drive = drive(840.0), walk = walk(NORTH, durationS = 360.0))
        assertEquals(1_200.0, j.totalDurationS, 1e-9)
        assertEquals(9_300.0, j.totalDistanceM, 1e-9)
        assertEquals(2, j.legs.size)
    }

    // ---- what "no parking" means ------------------------------------------

    @Test
    fun `parking not found is a stated fact, not a silent assumption`() {
        val found = Journey("X", EAST, walk = walk(NORTH),
                            park = ParkSpot(A, "P3"), parkingKnown = true)
        val notFound = Journey("X", EAST, walk = walk(NORTH), parkingKnown = false)
        assertTrue(found.parkingKnown)
        assertFalse(notFound.parkingKnown)
        // The distinction must be visible in the model, not only in the copy:
        // a UI cannot report what the data does not carry.
        assertEquals(null, notFound.park)
    }

    // ---- the walk leg's own geometry --------------------------------------

    @Test
    fun `a walk leg can hand back the polyline it was built from`() {
        val leg = WalkLeg(
            segments = listOf(WalkSegment(A, NORTH), WalkSegment(NORTH, EAST)),
            distanceM = 600.0, durationS = 440.0,
        )
        assertEquals(listOf(A, NORTH, EAST), leg.geometry)
    }

    @Test
    fun `an empty walk leg has no geometry rather than a fabricated point`() {
        assertEquals(emptyList(), WalkLeg(emptyList(), 0.0, 0.0).geometry)
    }

    // ---- the annotator seam ------------------------------------------------

    @Test
    fun `the shade annotator annotates a walk and marks it modelled`() {
        val annotations = ShadeAnnotator.annotate(walk(NORTH), at(16))
        assertEquals(1, annotations.size)
        assertEquals(AnnotationKind.EXPOSURE, annotations[0].kind)
        assertEquals(Provenance.MODELLED, annotations[0].provenance)
    }

    @Test
    fun `the shade annotator says nothing about a drive`() {
        // A drive's sun exposure is a question about air conditioning, not
        // about the route. Answering it would be inventing a number because a
        // field existed to put one in.
        assertTrue(ShadeAnnotator.annotate(drive(), at(16)).isEmpty())
    }

    @Test
    fun `the shade annotator says nothing about a walk with no segments`() {
        assertTrue(ShadeAnnotator.annotate(WalkLeg(emptyList(), 0.0, 0.0), at(16)).isEmpty())
    }

    // ---- offering a cooler route -------------------------------------------

    @Test
    fun `a meaningfully shadier walk of similar length is offered`() {
        val t = at(16)
        // At 16:00 the sun is in the west: the north-south walk is shaded by
        // its assumed facade and the east-west one is not.
        val sunny = walk(EAST, durationS = 300.0)
        val shady = walk(NORTH, durationS = 330.0)
        val offer = CoolerRoute.consider(
            direct = sunny, directShade = ShadeEstimator.route(sunny.segments, t),
            alternative = shady, alternativeShade = ShadeEstimator.route(shady.segments, t),
        )
        assertTrue(offer.available, "a much shadier walk for 30 s more should be offered")
        assertTrue(offer.deltaPoints > 20.0)
        assertEquals(30.0, offer.extraDurationS, 1e-9)
    }

    @Test
    fun `a cooler route is not offered when it is not cooler`() {
        val t = at(16)
        val a = walk(NORTH, durationS = 300.0)
        val b = walk(NORTH, durationS = 320.0)
        val offer = CoolerRoute.consider(
            a, ShadeEstimator.route(a.segments, t),
            b, ShadeEstimator.route(b.segments, t),
        )
        assertFalse(offer.available)
    }

    @Test
    fun `a shadier walk that takes far longer is not offered`() {
        val t = at(16)
        val sunny = walk(EAST, durationS = 300.0)
        val shadyButLong = walk(NORTH, durationS = 900.0)   // three times as long
        val offer = CoolerRoute.consider(
            sunny, ShadeEstimator.route(sunny.segments, t),
            shadyButLong, ShadeEstimator.route(shadyButLong.segments, t),
        )
        assertFalse(offer.available, "shade is not worth tripling the walk")
    }

    @Test
    fun `nothing is offered at night`() {
        val t = at(2)
        val a = walk(EAST, durationS = 300.0)
        val b = walk(NORTH, durationS = 310.0)
        assertEquals(SunState.NIGHT, ShadeEstimator.route(a.segments, t).sunState)
        val offer = CoolerRoute.consider(
            a, ShadeEstimator.route(a.segments, t),
            b, ShadeEstimator.route(b.segments, t),
        )
        assertFalse(offer.available, "there is no shade to prefer in the dark")
    }

    @Test
    fun `a cooler route that is also quicker reports a negative cost`() {
        val t = at(16)
        val sunny = walk(EAST, durationS = 400.0)
        val shady = walk(NORTH, durationS = 300.0)
        val offer = CoolerRoute.consider(
            sunny, ShadeEstimator.route(sunny.segments, t),
            shady, ShadeEstimator.route(shady.segments, t),
        )
        assertTrue(offer.available)
        assertTrue(offer.extraDurationS < 0.0, "a shorter cooler walk costs negative time")
    }

    @Test
    fun `the offer depends on the hour, because the sun does`() {
        val sunny = walk(EAST, durationS = 300.0)
        val shady = walk(NORTH, durationS = 320.0)
        fun offerAt(t: Long) = CoolerRoute.consider(
            sunny, ShadeEstimator.route(sunny.segments, t),
            shady, ShadeEstimator.route(shady.segments, t),
        ).available
        // Offered late in the afternoon; at solar noon the north-south street
        // is the exposed one, so the same pair is not an improvement.
        assertTrue(offerAt(at(16)))
        assertFalse(offerAt(at(11, 30)))
    }
}
