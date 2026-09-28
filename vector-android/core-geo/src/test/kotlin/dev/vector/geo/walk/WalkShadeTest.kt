package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.sun.ShadeBasis
import dev.vector.geo.sun.ShadeEstimator
import dev.vector.geo.sun.SunState
import dev.vector.geo.sun.WalkSegment
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The walking shade fact (V7.4 shade), on real Doha orientations.
 *
 * ## What this file is really testing
 *
 * Not [ShadeEstimator], whose behaviour `ShadeEstimatorTest` already pins
 * against published solar geometry. This file tests the three things the
 * route-level fact adds on top of it, and each of them is a way a
 * presentation claim could become false:
 *
 *  1. **The timeline.** The route is scored along its own walk, not at one
 *     instant; a walk that starts in the morning must not be described with
 *     the morning's sun at its far end.
 *  2. **The provenance buckets.** A decline to model an open plaza, and a
 *     piece of walk after sunset, must never be summarised as shade.
 *  3. **The sentence.** [WalkShade.stripLine] is the only path from this fact
 *     to a person, and it must be null-or-honest for every state, including
 *     the ones with nothing to say.
 *
 * Assertions are about ORDERING, DIRECTION and BOUNDS rather than about the
 * exact exposure a particular facade assumption produces — the assumption is
 * the part most likely to be retuned, and pinning 0.291 today would turn a
 * deliberate improvement into a test failure tomorrow.
 */
class WalkShadeTest {

    private companion object {
        val QATAR: ZoneOffset = ZoneOffset.ofHours(3)

        /** A Doha instant, UTC+3, 2026-09-17 unless another day is given. */
        fun at(hour: Int, minute: Int = 0, day: Int = 17): Long =
            ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, QATAR).toInstant().toEpochMilli()

        // Streets in Msheireb, one of each orientation, ~330 m and ~300 m.
        val NS_A = LngLat(51.5310, 25.2850)
        val NS_B = LngLat(51.5310, 25.2880)
        val EW_A = LngLat(51.5310, 25.2850)
        val EW_B = LngLat(51.5340, 25.2850)

        fun ns(highway: String? = "residential") = WalkSegment(NS_A, NS_B, highway = highway)
        fun ew(highway: String? = "residential") = WalkSegment(EW_A, EW_B, highway = highway)

        /** Two E-W legs end to end, so a walk has a length worth a timeline. */
        fun ewPair() = listOf(ew(), ew())

        const val HOUR_S = 3600.0
    }

    // ------------------------------------------------------------- determinism

    @Test
    fun `the same walk and the same instant always produce the same fact`() {
        val t = at(15)
        val first = WalkShadeFacts.of(ewPair(), t, HOUR_S)
        repeat(10) {
            val again = WalkShadeFacts.of(ewPair(), t, HOUR_S)
            assertEquals(first.shadedM, again.shadedM, 0.0)
            assertEquals(first.exposedM, again.exposedM, 0.0)
            assertEquals(first.band, again.band)
            assertEquals(first.stripLine(), again.stripLine())
        }
    }

    @Test
    fun `the fact carries the instant it was given, not a clock`() {
        // Two instants nine hours apart on the same walk must differ. If the
        // implementation read the machine clock this would be a coin toss.
        val morning = WalkShadeFacts.of(ewPair(), at(8), HOUR_S)
        val afternoon = WalkShadeFacts.of(ewPair(), at(17), HOUR_S)
        assertEquals(at(8), morning.startedAtMs)
        assertEquals(at(17), afternoon.startedAtMs)
        assertTrue(
            morning.modelledShadeFraction != afternoon.modelledShadeFraction,
            "an east-west street must differ between morning and late afternoon",
        )
    }

    // ---------------------------------------------------------------- timeline

    @Test
    fun `a long walk is scored along its own timeline, not at its start`() {
        // Six hours of an east-west street from 11:00, which is the case where
        // the two models disagree while talking about the same statistic.
        //
        // Measured on this geometry: the street's near end is walked at 12:30
        // (exposure 0.24, the model puts it in shade) and its far end at 15:30
        // (exposure 1.00, the sun is far enough round that no assumed facade
        // reaches across). The instant model — which is what
        // `ShadeEstimator.route` computes and what the journey card's sun
        // slider shows — scores the whole walk at 11:00 and calls all of it
        // shaded.
        val t = at(11)
        val instant = WalkShadeFacts.of(ewPair(), t, 0.0)
        val timed = WalkShadeFacts.of(ewPair(), t, 6 * HOUR_S)

        assertEquals(1.0, instant.modelledShadeFraction, 1e-6)
        assertEquals(WalkShadeBand.MOSTLY_SHADED, instant.band)

        assertTrue(
            timed.modelledShadeFraction < 0.6,
            "the timeline reproduced the start instant: ${timed.modelledShadeFraction}",
        )
        assertEquals(WalkShadeBand.MIXED_SHADE, timed.band)
        // ...and the difference is exactly where the walker is at the time.
        assertEquals(2, timed.segments.size)
        assertTrue(
            timed.segments.first().exposure < WalkShade.SHADE_SPLIT,
            "the near end must be shaded: ${timed.segments.first().exposure}",
        )
        assertTrue(
            timed.segments.last().exposure > WalkShade.SHADE_SPLIT,
            "the far end must be exposed: ${timed.segments.last().exposure}",
        )
    }

    @Test
    fun `a walk whose far end is after sunset stops making a shade claim`() {
        // The sharpest version of the same point. At 16:00 the INSTANT model
        // says this east-west walk is entirely exposed — a confident, and for
        // the walk's own duration wrong, answer: by 17:30 the sun is under
        // 1 deg, and the walker is still walking.
        val t = at(16)
        val instant = WalkShadeFacts.of(ewPair(), t, 0.0)
        val timed = WalkShadeFacts.of(ewPair(), t, 6 * HOUR_S)

        assertEquals(WalkShadeBand.MOSTLY_EXPOSED, instant.band)
        assertEquals(0.0, instant.modelledShadeFraction, 1e-9)

        assertEquals(0.0, timed.answeredM, 1e-9)
        assertTrue(timed.nightM > 0.0, "the walk must end after sunset")
        assertTrue(timed.lowSunM > 0.0, "the walk must pass through low sun")
        assertEquals(timed.totalM, timed.nightM + timed.lowSunM, 1e-6)
        assertEquals("Shade not estimated", timed.stripLine())
    }

    @Test
    fun `a walk that ends after sunset puts its tail in the night bucket, not in shade`() {
        // 16:30 for three hours. The first half is walked in daylight (its
        // midpoint is 17:00, altitude 7.4 deg) and the second half after sunset
        // (midpoint 18:30, altitude -13 deg).
        val s = WalkShadeFacts.of(ewPair(), at(16, 30), 3 * HOUR_S)
        assertTrue(s.nightM > 0.0, "expected the tail of the walk to be after sunset")
        assertTrue(s.answeredM > 0.0, "expected the head of the walk to be daylit")
        // The buckets partition the walk: nothing is counted twice and nothing
        // is lost.
        assertEquals(s.totalM, s.answeredM + s.nightM + s.lowSunM + s.noEvidenceM, 1e-6)
        assertEquals(s.answeredM, s.shadedM + s.exposedM, 1e-6)
        // The dark half is not described as shade, in either direction.
        assertEquals(0.0, s.shadedM, 1e-9)
        // ...and the coverage denominator excludes it, so a walk that ran into
        // the evening does not look like a walk the model failed on.
        assertEquals(1.0, s.coverage, 1e-6)
    }

    @Test
    fun `a walk entirely at night makes no shade claim`() {
        val s = WalkShadeFacts.of(ewPair(), at(23), HOUR_S)
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals(s.totalM, s.nightM, 1e-6)
        assertEquals(0.0, s.modelledShadeFraction, 1e-9)
        assertEquals(0.0, s.coverage, 1e-9)
        assertEquals("No direct sun", s.stripLine())
    }

    @Test
    fun `a walk entirely in low sun says so rather than modelling it`() {
        // 17:20 is the window where the sun is up but under 3 deg; the real
        // sunset is between 17:30 and 18:00.
        val s = WalkShadeFacts.of(ewPair(), at(17, 20), 4 * 60.0)
        assertTrue(s.lowSunM > 0.0, "expected a low-sun bucket, got ${s.lowSunM}")
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals("Low sun; shade not estimated", s.stripLine())
    }

    @Test
    fun `a walk crossing midnight is bucketed hour by hour, not by its start`() {
        // 20:00 for twenty hours: dark until sunrise, then daylight. The walk
        // therefore has BOTH a night share and a daylit share, which is the
        // case a single-instant model cannot express at all — at 20:00 it would
        // say only "No direct sun", and it would say it about a walk that
        // finishes the next afternoon.
        val s = WalkShadeFacts.of(ewPair(), at(20), 20 * HOUR_S)
        assertTrue(s.nightM > 0.0, "expected night at the start")
        assertTrue(s.answeredM > 0.0, "expected the walk to reach daylight")
        // One bucket per segment here: the near end's midpoint is 01:00 (dark),
        // the far end's is 11:00 (the sun is up and the model answered).
        assertEquals(s.totalM, s.nightM + s.answeredM, 1e-6)
        assertEquals(1.0, s.coverage, 1e-6)
        // The category is decided by the DAYLIT half alone, which is the only
        // half a shade claim can be about.
        assertEquals(WalkShadeBand.MOSTLY_SHADED, s.band)
    }

    @Test
    fun `a walk with no duration is scored at one instant rather than invented`() {
        // `duration_s` absent is a real backend answer. The honest fallback is
        // the single-instant model, not a pace Vector does not know — so every
        // segment must carry exactly the exposure `ShadeEstimator` computes for
        // it at the given instant.
        val t = at(15)
        val noDuration = WalkShadeFacts.of(ewPair(), t, 0.0)
        val single = ShadeEstimator.route(ewPair(), t)
        assertEquals(0.0, noDuration.durationS, 1e-9)
        assertEquals(single.segments.size, noDuration.segments.size)
        for (i in single.segments.indices) {
            assertEquals(
                single.segments[i].exposure,
                noDuration.segments[i].exposure,
                1e-9,
                "segment $i left the single-instant model",
            )
            assertEquals(single.segments[i].basis, noDuration.segments[i].basis)
        }
    }

    @Test
    fun `a malformed duration is clamped rather than walked into next week`() {
        val t = at(9)
        val enormous = WalkShadeFacts.of(ewPair(), t, 4_000_000.0)
        assertEquals(WalkShade.MAX_TIMELINE_S, enormous.durationS, 1e-9)
        // A negative or NaN duration is treated as absent, not as a time warp.
        assertEquals(0.0, WalkShadeFacts.of(ewPair(), t, -60.0).durationS, 1e-9)
        assertEquals(0.0, WalkShadeFacts.of(ewPair(), t, Double.NaN).durationS, 1e-9)
    }

    // --------------------------------------------------------------- buckets

    @Test
    fun `an open plaza is counted as unanswered, not as shade and not as sun`() {
        // The Msheireb case: a wide square with no facade beside it. The model
        // reports full exposure at its lowest confidence — a refusal to guess —
        // and that refusal must not become a shade finding.
        val plaza = WalkSegment(NS_A, NS_B, highway = "pedestrian", area = true)
        val s = WalkShadeFacts.of(listOf(plaza), at(15), HOUR_S)
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals(s.totalM, s.noEvidenceM, 1e-6)
        assertEquals(0.0, s.shadedM, 1e-9)
        assertEquals(0.0, s.exposedM, 1e-9)
        assertEquals(0.0, s.coverage, 1e-9)
        assertEquals("Shade not estimated", s.stripLine())
    }

    @Test
    fun `an unknown road class is unanswered too`() {
        val s = WalkShadeFacts.of(listOf(ns(highway = "raceway")), at(15), HOUR_S)
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals(s.totalM, s.noEvidenceM, 1e-6)
        assertEquals("Shade not estimated", s.stripLine())
    }

    @Test
    fun `a walk from a backend with no per-segment classes reports no estimate`() {
        // Every real world case of this: `classes` absent means the client
        // knows the geometry and nothing about what it is. The bucket is a
        // refusal, and the sentence says so rather than filling the strip.
        val segments = listOf(WalkSegment(NS_A, NS_B, highway = null), WalkSegment(EW_A, EW_B, highway = null))
        val s = WalkShadeFacts.of(segments, at(15), HOUR_S)
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals(s.totalM, s.noEvidenceM, 1e-6)
        assertEquals("Shade not estimated", s.stripLine())
    }

    @Test
    fun `an enclosed way is answered, and the enclosure is the reason`() {
        val covered = WalkSegment(NS_A, NS_B, highway = "footway", covered = true)
        val s = WalkShadeFacts.of(listOf(covered), at(15), HOUR_S)
        assertEquals(s.totalM, s.answeredM, 1e-6)
        assertEquals(s.totalM, s.shadedM, 1e-6)
        assertEquals(WalkShadeBand.MOSTLY_SHADED, s.band)
        assertEquals(ShadeBasis.ENCLOSED, s.segments.single().basis)
    }

    @Test
    fun `an enclosed way walked at night is the night bucket, not a shade answer`() {
        // The tag fact is real, but "Mostly shaded" is the wrong sentence for a
        // walk taken after sunset however many of its ways are roofed. The
        // segment's own exposure still reports the tag — that is what the map
        // overlay and every per-segment consumer read.
        val covered = WalkSegment(NS_A, NS_B, highway = "footway", covered = true)
        val s = WalkShadeFacts.of(listOf(covered), at(23), HOUR_S)
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals(s.totalM, s.nightM, 1e-6)
        assertEquals("No direct sun", s.stripLine())
        assertEquals(0.0, s.segments.single().exposure, 1e-9)
    }

    @Test
    fun `a wide arterial is answered as exposed rather than declined`() {
        // `primary` carries an assumed facade height of zero: the model reached
        // a real conclusion (nothing beside this road shades its footway) and
        // that is a finding, not a refusal.
        val s = WalkShadeFacts.of(listOf(ns(highway = "primary")), at(15), HOUR_S)
        assertEquals(s.totalM, s.answeredM, 1e-6)
        assertEquals(0.0, s.shadedM, 1e-9)
        assertEquals(WalkShadeBand.MOSTLY_EXPOSED, s.band)
        assertEquals(ShadeBasis.FACADE, s.segments.single().basis)
    }

    @Test
    fun `a sun looking along the street is answered as exposed, not declined`() {
        // South of due south at solar noon: the sun is directly along a
        // north-south street, so no facade is between it and the walker. A
        // conclusion, not a refusal.
        val s = WalkShadeFacts.of(listOf(ns()), at(11, 30), HOUR_S)
        assertEquals(s.totalM, s.answeredM, 1e-6)
        assertEquals(ShadeBasis.FACADE, s.segments.single().basis)
        assertTrue(s.modelledShadeFraction < 0.2, "got ${s.modelledShadeFraction}")
    }

    // ----------------------------------------------------------- aggregation

    @Test
    fun `the buckets sum to the walk length exactly`() {
        for (hour in intArrayOf(6, 9, 12, 15, 18, 21)) {
            for (segs in listOf(listOf(ns()), listOf(ew()), listOf(ns(), ew()), ewPair())) {
                val s = WalkShadeFacts.of(segs, at(hour), HOUR_S)
                assertEquals(
                    s.totalM,
                    s.answeredM + s.nightM + s.lowSunM + s.noEvidenceM,
                    1e-6,
                    "buckets lost or duplicated metres at $hour:00",
                )
                assertEquals(s.answeredM, s.shadedM + s.exposedM, 1e-6)
            }
        }
    }

    @Test
    fun `no fraction leaves zero to one, at any hour`() {
        for (hour in 0..23) {
            val s = WalkShadeFacts.of(listOf(ns(), ew()), at(hour), 2 * HOUR_S)
            assertTrue(s.coverage in 0.0..1.0, "coverage ${s.coverage} at $hour:00")
            assertTrue(
                s.modelledShadeFraction in 0.0..1.0,
                "shade fraction ${s.modelledShadeFraction} at $hour:00",
            )
            assertTrue(s.totalM >= 0.0 && s.shadedM >= 0.0 && s.exposedM >= 0.0)
            for (v in listOf(s.totalM, s.answeredM, s.shadedM, s.exposedM, s.nightM, s.lowSunM, s.noEvidenceM)) {
                assertTrue(!v.isNaN(), "NaN in a shade fact at $hour:00")
            }
        }
    }

    @Test
    fun `a degenerate segment contributes nothing and drags nothing down`() {
        val real = WalkShadeFacts.of(listOf(ns()), at(15), HOUR_S)
        val padded = WalkShadeFacts.of(
            listOf(WalkSegment(NS_A, NS_A, "footway"), ns(), WalkSegment(EW_B, EW_B, "footway")),
            at(15), HOUR_S,
        )
        assertEquals(real.totalM, padded.totalM, 1e-6)
        assertEquals(real.shadedM, padded.shadedM, 1e-6)
        assertEquals(real.answeredM, padded.answeredM, 1e-6)
    }

    @Test
    fun `an empty walk is a coherent fact with nothing to say`() {
        val s = WalkShadeFacts.of(emptyList(), at(15), HOUR_S)
        assertEquals(0.0, s.totalM, 1e-9)
        assertEquals(0.0, s.answeredM, 1e-9)
        assertEquals(0.0, s.coverage, 1e-9)
        // No walk to describe, so no sentence. This is the ONE state that
        // renders nothing; every other state says something, including
        // "Shade not estimated".
        assertNull(s.stripLine())
    }

    @Test
    fun `aggregation is length weighted, not a plain mean of segments`() {
        // A long exposed leg and a short shaded one in the late afternoon.
        val longExposed = ew()                                        // ~302 m
        val shortShaded = WalkSegment(NS_A, LngLat(51.5310, 25.28527), "residential")  // ~30 m
        val s = WalkShadeFacts.of(listOf(longExposed, shortShaded), at(16), HOUR_S)
        assertEquals(s.totalM, s.shadedM + s.exposedM, 1e-6)
        assertTrue(
            s.modelledShadeFraction < 0.3,
            "the long exposed leg must dominate, got ${s.modelledShadeFraction}",
        )
    }

    // ---------------------------------------------------------------- coverage

    @Test
    fun `coverage counts only the daylight walk as the denominator`() {
        // A walk running into the evening: half daylit, half dark. The dark
        // half must not be reported as unmodelled ground, because there was no
        // shade question to answer over it — and if it were, a perfectly
        // well-modelled evening walk would read "Limited shade data".
        val s = WalkShadeFacts.of(ewPair(), at(16, 30), 3 * HOUR_S)
        assertTrue(s.nightM > 0.0)
        assertTrue(s.answeredM > 0.0)
        val daylightM = s.answeredM + s.noEvidenceM + s.lowSunM
        assertEquals(1.0, s.coverage, 1e-6)
        assertEquals(s.totalM, daylightM + s.nightM, 1e-6)
        // ...and the denominator is genuinely smaller than the walk, so this
        // assertion is about the exclusion rather than about a coincidence.
        assertTrue(daylightM < s.totalM - 1.0)
    }

    @Test
    fun `a mostly unmodelled daylight walk refuses a category`() {
        // Two thirds plaza, one third street: the model answered for a third of
        // the ground the walker covers, and a band word would be a claim about
        // a walk it did not measure.
        val street = ns()
        val plaza = WalkSegment(EW_A, LngLat(51.5340, 25.2853), highway = "pedestrian", area = true)
        val s = WalkShadeFacts.of(
            listOf(street, plaza, plaza.copy(from = plaza.to, to = EW_B)),
            at(15), HOUR_S,
        )
        assertTrue(s.coverage < WalkShade.MIN_COVERAGE, "got ${s.coverage}")
        assertEquals("Limited shade data", s.stripLine())
        // The facts are still there underneath the refusal.
        assertTrue(s.answeredM > 0.0)
        assertTrue(s.noEvidenceM > s.answeredM)
    }

    // ------------------------------------------------------------------ bands

    @Test
    fun `the band follows the modelled fraction and the thresholds are inclusive`() {
        assertEquals(WalkShadeBand.MOSTLY_SHADED, WalkShade.bandFor(1.0))
        assertEquals(WalkShadeBand.MOSTLY_SHADED, WalkShade.bandFor(WalkShade.MIN_MOSTLY_SHADED))
        assertEquals(WalkShadeBand.MIXED_SHADE, WalkShade.bandFor(0.5))
        assertEquals(WalkShadeBand.MIXED_SHADE, WalkShade.bandFor(WalkShade.MIN_MIXED))
        assertEquals(WalkShadeBand.MOSTLY_EXPOSED, WalkShade.bandFor(0.0))
    }

    @Test
    fun `the band thresholds are ordered so no fraction is unreachable`() {
        assertTrue(WalkShade.MIN_MIXED < WalkShade.MIN_MOSTLY_SHADED)
        val seen = (0..100).map { WalkShade.bandFor(it / 100.0) }.toSet()
        assertEquals(WalkShadeBand.entries.toSet(), seen, "a band is unreachable from any fraction")
    }

    @Test
    fun `the map overlay split is this model's split`() {
        // One number, two surfaces: the per-segment colouring on the map and
        // the route-level band must not come to disagree about "shaded".
        assertEquals(0.5, WalkShade.SHADE_SPLIT)
        val exposure = ShadeEstimator.segment(ns(), at(15))
        assertTrue(exposure.exposure < WalkShade.SHADE_SPLIT)
        val s = WalkShadeFacts.of(listOf(ns()), at(15), HOUR_S)
        assertEquals(s.totalM, s.shadedM, 1e-6)
        assertEquals(WalkShadeBand.MOSTLY_SHADED, s.band)
    }

    // ------------------------------------------------------------- the sentence

    @Test
    fun `every band string is hedged and none of them overclaims`() {
        for (b in WalkShadeBand.entries) {
            val line = WalkShadeFacts.of(listOf(ns(), ew()), at(12), HOUR_S).let {
                // Build the sentence for the band directly, since not every
                // band is reachable from one fixture at one hour.
                WalkShade(
                    startedAtMs = at(12), durationS = HOUR_S,
                    totalM = 100.0, answeredM = 100.0,
                    shadedM = when (b) {
                        WalkShadeBand.MOSTLY_SHADED -> 90.0
                        WalkShadeBand.MIXED_SHADE -> 50.0
                        WalkShadeBand.MOSTLY_EXPOSED -> 10.0
                    },
                    exposedM = when (b) {
                        WalkShadeBand.MOSTLY_SHADED -> 10.0
                        WalkShadeBand.MIXED_SHADE -> 50.0
                        WalkShadeBand.MOSTLY_EXPOSED -> 90.0
                    },
                    nightM = 0.0, lowSunM = 0.0, noEvidenceM = 0.0,
                    band = b, segments = emptyList(),
                ).stripLine()
            }
            val words = line!!.lowercase()
            assertTrue(
                words.contains("estimated"),
                "a band claim without the hedge: \"$line\"",
            )
            assertTrue(words.contains(b.label.lowercase()))
            for (forbidden in listOf(
                "guarantee", "guaranteed", "safe", "protected", "cool", "no sun",
                "fully shaded", "complete shade",
            )) {
                assertTrue(!words.contains(forbidden), "\"$forbidden\" in \"$line\"")
            }
        }
    }

    @Test
    fun `no state ever renders a bare percentage`() {
        // The journey card's sun slider owns the percentage. On a walking strip
        // the same number would be precision the model does not have.
        for (hour in 0..23) {
            for (segs in listOf(listOf(ns()), listOf(ew()), listOf(ns(highway = "raceway")))) {
                val line = WalkShadeFacts.of(segs, at(hour), 2 * HOUR_S).stripLine()
                assertNotNull(line, "no sentence for a walk at $hour:00")
                assertTrue(!line.contains("%"), "a bare percentage reached the strip: \"$line\"")
                assertTrue(!line.contains("null"), "null leaked: \"$line\"")
            }
        }
    }

    @Test
    fun `the four nothing-to-assert states each say something different`() {
        // Four conditions, four sentences, and none of them is a band word.
        // The point is that a walker can tell WHICH of the four they are in:
        // "the sun has set" and "Vector could not model this" are very
        // different things to be told, and one sentence for both would be the
        // collapse this function exists to prevent.
        val allNight = WalkShadeFacts.of(ewPair(), at(23), HOUR_S).stripLine()
        val allLowSun = WalkShadeFacts.of(ewPair(), at(17, 20), 4 * 60.0).stripLine()
        val allDeclined = WalkShadeFacts.of(
            listOf(ns(highway = "raceway")), at(15), HOUR_S,
        ).stripLine()
        val mostlyUnmodelled = WalkShadeFacts.of(
            listOf(
                ns(),
                WalkSegment(EW_A, LngLat(51.5340, 25.2853), highway = "pedestrian", area = true),
                WalkSegment(LngLat(51.5340, 25.2853), EW_B, highway = "pedestrian", area = true),
            ),
            at(15), HOUR_S,
        ).stripLine()

        for (line in listOf(allNight, allLowSun, allDeclined, mostlyUnmodelled)) {
            assertNotNull(line)
            // "estimated" appears legitimately in "shade not estimated"; what
            // must not appear is a BAND WORD, because a band is a claim about
            // a walk the model measured and this state is the one where it did
            // not.
            for (b in WalkShadeBand.entries) {
                assertTrue(
                    !line.contains(b.label, ignoreCase = true),
                    "a band word (\"${b.label}\") for a state with no band: \"$line\"",
                )
            }
        }
        val sentences = listOf(allNight, allLowSun, allDeclined, mostlyUnmodelled)
        assertTrue(
            sentences.toSet().size == 4,
            "two different states rendered the same sentence: $sentences",
        )
        assertEquals("No direct sun", allNight)
        assertEquals("Low sun; shade not estimated", allLowSun)
        assertEquals("Shade not estimated", allDeclined)
        assertEquals("Limited shade data", mostlyUnmodelled)
    }

    // ------------------------------------------- the route seam and the tags

    @Test
    fun `a route's shade comes from its contract geometry and segment tags`() {
        // The seam the app uses: WalkRoute.shadeAt. The classes drive the
        // answer, so a route of `primary` must not read the same as one of
        // `residential` on the same geometry at the same instant.
        val t = at(15)
        val wide = WalkRoute.of(contractOf(ns(highway = "primary")))
        val narrow = WalkRoute.of(contractOf(ns(highway = "residential")))
        assertEquals(WalkShadeBand.MOSTLY_EXPOSED, wide.shadeAt(t).band)
        assertEquals(WalkShadeBand.MOSTLY_SHADED, narrow.shadeAt(t).band)
        assertEquals(0.0, wide.shadeAt(t).shadedM, 1e-9)
    }

    @Test
    fun `a route with no geometry reports nothing rather than crashing`() {
        val route = WalkRoute.of(contractOf(null))
        assertEquals(0.0, route.shadeAt(at(15)).totalM, 1e-9)
        assertNull(route.shadeAt(at(15)).stripLine())
    }

    @Test
    fun `the wire's enclosed flag reaches the model as an enclosure`() {
        // The one lossy-looking mapping in `segmentsOf`: `enclosed` is one
        // boolean on the wire and three fields in WalkSegment. It must still
        // arrive as an OSM fact rather than as an assumption.
        val contract = contractOf(ns(highway = "footway"), enclosed = true)
        val segs = WalkShadeFacts.segmentsOf(contract)
        assertEquals(1, segs.size)
        assertTrue(segs.single().covered, "the enclosed flag did not reach the model")
        val s = WalkShadeFacts.of(contract, at(15))
        assertEquals(ShadeBasis.ENCLOSED, s.segments.single().basis)
    }

    @Test
    fun `segment arrays shorter than the geometry leave the uncovered ones unknown`() {
        // A backend that sent a short array is a real degradation path, and the
        // only acceptable direction for it is toward claiming less.
        val pts = listOf(NS_A, NS_B, EW_B)
        val contract = WalkContract(
            contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
            walkingProfileRaw = "general", mode = "foot",
            distanceM = 600.0, durationS = 400.0, stepsM = 0.0, crossingM = 0.0,
            nodes = 3, geometry = pts,
            // One class for two segments.
            segments = WalkSegments(classes = listOf("residential")),
            facts = emptyList(), plan = emptyList(), cost = null,
            diagnostics = WalkDiagnostics(),
        )
        val segs = WalkShadeFacts.segmentsOf(contract)
        assertEquals(2, segs.size)
        assertEquals("residential", segs[0].highway)
        assertNull(segs[1].highway)
        val s = WalkShadeFacts.of(contract, at(15))
        assertTrue(s.noEvidenceM > 0.0, "the untagged segment was credited with an answer")
    }

    // ---------------------------------------------------------------- helpers

    /** A one- or two-vertex contract whose tags are the point of the test. */
    private fun contractOf(
        segment: WalkSegment?,
        enclosed: Boolean = false,
    ): WalkContract {
        val pts = if (segment == null) emptyList() else listOf(segment.from, segment.to)
        return WalkContract(
            contractVersion = 1,
            walkingProfile = WalkingProfile.GENERAL,
            walkingProfileRaw = "general",
            mode = "foot",
            distanceM = null,
            durationS = pts.size.takeIf { it > 1 }?.let { if (enclosed) 300.0 else 300.0 },
            stepsM = 0.0,
            crossingM = 0.0,
            nodes = pts.size,
            geometry = pts,
            segments = WalkSegments(
                classes = segment?.let { listOf(it.highway) } ?: emptyList(),
                enclosed = segment?.let { listOf(enclosed) } ?: emptyList(),
                area = segment?.let { listOf(it.area) } ?: emptyList(),
            ),
            facts = emptyList(),
            plan = emptyList(),
            cost = null,
            diagnostics = WalkDiagnostics(),
        )
    }

    @Test
    fun `sun state and the bucket agree on what the sun was doing`() {
        // The invariant behind every bucket: a segment is bucketed by the sun
        // it was actually scored under. The single-instant walk is the case
        // where a segment and its own bucket are scored at the same instant,
        // so the two answers must match exactly — and the two ways of saying
        // "there is no sun" cannot disagree.
        for (hour in 0..23) {
            val e = ShadeEstimator.segment(ns(), at(hour))
            val sunUp = e.solar?.isDaylight == true
            if (e.sunState == SunState.NIGHT) {
                assertTrue(!sunUp, "NIGHT with the sun up at $hour:00")
            }
            if (e.sunState == SunState.UNKNOWN) assertNull(e.solar)
            if (!sunUp) assertNotNull(e.solar)

            val s = WalkShadeFacts.of(listOf(ns()), at(hour), 0.0)
            assertEquals(s.totalM, s.answeredM + s.nightM + s.lowSunM + s.noEvidenceM, 1e-6)
            when (e.sunState) {
                SunState.NIGHT -> {
                    assertEquals(s.totalM, s.nightM, 1e-6, "NIGHT segment not in the night bucket")
                    assertEquals(0.0, s.answeredM, 1e-9)
                }
                SunState.LOW_SUN -> {
                    assertEquals(s.totalM, s.lowSunM, 1e-6, "LOW_SUN segment not in the low-sun bucket")
                    assertEquals(0.0, s.answeredM, 1e-9)
                }
                SunState.UNKNOWN -> assertEquals(s.totalM, s.nightM, 1e-6)
                SunState.DAY -> {
                    // A degenerate segment is the only daylight case with no
                    // basis to bucket on; everything else is answered or
                    // declined, never both.
                    val answered = e.basis.answersShade
                    assertEquals(if (answered) 0.0 else s.totalM, s.noEvidenceM, 1e-6)
                }
            }
        }
    }
}
