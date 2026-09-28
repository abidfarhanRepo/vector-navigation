package dev.vector.android

import dev.vector.geo.walk.WalkRoute
import dev.vector.geo.walk.WalkShade
import dev.vector.geo.walk.WalkShadeBand
import dev.vector.geo.walk.WalkShadeFacts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The walking shade fact, over REAL Qatar walks (V7.4 shade).
 *
 * `WalkShadeTest` states the rules on synthetic geometry where the orientation
 * is exactly what the test chose. This file is the evidence they survive
 * contact with OSM: the same model, run over the six captured `/foot` payloads
 * from the real 260912 bake, at FIXED timestamps.
 *
 * ## Why the timestamps are fixed, and why they are Qatar local
 *
 * The whole fact is a function of (geometry, tags, instant), so a test without
 * a pinned instant measures the machine clock rather than the code. Every
 * instant here is UTC+3 — Doha's actual offset, which the model never needs
 * because it works from UTC and a longitude correction, but which a reader
 * does need to make sense of "12:00".
 *
 * ## What is actually claimed
 *
 * Nothing about a percentage. The model is street orientation plus an assumed
 * facade; the route-level claim is a category, and the quantitative facts
 * underneath it (shaded metres, answered metres, coverage) are asserted here
 * so the category can be checked against them rather than believed.
 */
class WalkRealQatarShadeTest {

    private val QATAR: ZoneOffset = ZoneOffset.ofHours(3)

    /** A Doha instant on 2026-09-17, the date the bake's fixtures were captured near. */
    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 9, 17, hour, minute, 0, 0, QATAR).toInstant().toEpochMilli()

    private fun body(name: String): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/vector-contract/$name")
            ?.readBytes()?.decodeToString()
            ?: throw AssertionError("missing contract snapshot $name")
    )

    private fun contract(name: String) =
        WalkContractParser.parse(body(name).getJSONArray("features").getJSONObject(0))

    private fun route(name: String) = WalkRoute.of(contract(name))

    private fun shade(name: String, hour: Int, minute: Int = 0) =
        route(name).shadeAt(at(hour, minute))

    // ------------------------------------------------ the six real walks

    @Test
    fun `every real walk partitions its own length at every hour of the day`() {
        // The invariant that makes the buckets trustworthy: each segment lands
        // in exactly one of the four, so nothing is counted twice and no metre
        // of a real walk disappears.
        for (name in WALKS) {
            for (hour in 0..23) {
                val s = shade(name, hour)
                assertEquals(
                    "$name at $hour:00: the buckets do not sum to the walk",
                    s.totalM,
                    s.answeredM + s.nightM + s.lowSunM + s.noEvidenceM,
                    1e-6,
                )
                assertEquals("$name at $hour:00", s.answeredM, s.shadedM + s.exposedM, 1e-6)
                assertTrue("$name at $hour:00: negative shade", s.shadedM >= 0.0)
                assertTrue("$name at $hour:00: negative exposure", s.exposedM >= 0.0)
                assertTrue("$name at $hour:00: NaN", !s.totalM.isNaN() && !s.coverage.isNaN())
                assertTrue("$name at $hour:00: coverage out of range", s.coverage in 0.0..1.0)
                assertTrue(
                    "$name at $hour:00: shade fraction out of range",
                    s.modelledShadeFraction in 0.0..1.0,
                )
            }
        }
    }

    @Test
    fun `every real walk is fully modelled in daylight`() {
        // Measured on this bake: all six fixtures carry a `classes` array with
        // no `area` and no `enclosed` segment, and every one of their `highway`
        // values is in the model's table. So coverage is 1.0 across the day and
        // a category is always available — which is also what makes the
        // `Limited shade data` path unreachable from this data and therefore
        // worth having as a unit test rather than a real-data one.
        for (name in WALKS) {
            val s = shade(name, 12)
            assertEquals("$name is not fully modelled at noon", 1.0, s.coverage, 1e-6)
            assertEquals("$name has unmodelled daylight ground", 0.0, s.noEvidenceM, 1e-9)
            assertTrue("$name has no answered metres", s.answeredM > 0.0)
        }
    }

    @Test
    fun `a real walk at night makes no shade claim however long it is`() {
        for (name in WALKS) {
            val s = shade(name, 23)
            assertEquals("$name at 23:00", 0.0, s.answeredM, 1e-9)
            assertEquals("$name at 23:00", s.totalM, s.nightM, 1e-6)
            assertEquals("No direct sun", s.stripLine())
        }
    }

    // ------------------------------- the morning / noon / afternoon change

    @Test
    fun `a real Doha walk changes materially between morning noon and afternoon`() {
        // The crossing-heavy walk, 1,848 m through Doha, on a fixed date. This
        // is the case the brief asks for: the route's own shade fraction must
        // move with the sun, not merely with the segment orientations.
        //
        // Measured on this bake: its two long legs run roughly north-south
        // (53 of its 96 segments fall in the 330-360 deg bin), so the sun being
        // east or west is side-on to it and the assumed facade reaches across,
        // while the noon sun looks along the street instead.
        val morning = shade("foot-crossing-4b4.json", 9)
        val noon = shade("foot-crossing-4b4.json", 12)
        val afternoon = shade("foot-crossing-4b4.json", 15)

        assertEquals(1.0, morning.coverage, 1e-6)
        assertEquals(1.0, noon.coverage, 1e-6)
        assertEquals(1.0, afternoon.coverage, 1e-6)

        // Midday is the exposed one, by a wide margin, and the category says so.
        assertTrue(
            "noon is not the most exposed: ${morning.modelledShadeFraction}, " +
                "${noon.modelledShadeFraction}, ${afternoon.modelledShadeFraction}",
            noon.modelledShadeFraction < morning.modelledShadeFraction - 0.3 &&
                noon.modelledShadeFraction < afternoon.modelledShadeFraction - 0.3,
        )
        assertEquals(WalkShadeBand.MOSTLY_EXPOSED, noon.band)
        assertEquals(WalkShadeBand.MOSTLY_SHADED, morning.band)
        assertEquals(WalkShadeBand.MOSTLY_SHADED, afternoon.band)
        assertEquals("Mostly exposed (estimated)", noon.stripLine())

        // ...and the morning and the afternoon agree on the category but NOT
        // bit-for-bit on the metres, because the sun is on a different side.
        assertTrue(
            "morning and afternoon are identical, which would mean azimuth is ignored",
            morning.shadedM != afternoon.shadedM,
        )
    }

    @Test
    fun `the ordinary real walk changes category across the day`() {
        // A second, independent real route, so the morning/noon/afternoon
        // result above is not a property of one payload. Measured: Mixed at
        // 06:00 and 12:00 and 15:00, Mostly shaded at 09:00, night after 18:00.
        val bands = listOf(6, 9, 12, 15).map { shade("foot-ordinary-4b4.json", it).band }
        assertEquals(
            "an ordinary walk must not have one band all day: $bands",
            true,
            bands.toSet().size > 1,
        )
        assertTrue("09:00 should be the shaded one", bands[1] != bands[0] || bands[1] != bands[2])
        // Never "no direct sun" in full daylight.
        for (hour in intArrayOf(6, 9, 12, 15)) {
            assertTrue(
                "$hour:00 did not produce a band",
                shade("foot-ordinary-4b4.json", hour).stripLine()!!.contains("estimated"),
            )
        }
    }

    // ------------------------------------------- the timeline on real data

    @Test
    fun `the longest real walk does not finish before dark, and the model says so`() {
        // ## The strongest timeline evidence in the fixture set
        //
        // The `long` walk is 22.4 km and takes 4 h 36 m of pure pace time. Start
        // it at 15:00 Doha and it does NOT finish in daylight: measured, 9,754 m
        // of it are walked after sunset and a further 1,242 m in low sun, on
        // 2026-09-17 where sunset is between 17:30 and 18:00.
        //
        // A single-instant model cannot see this at all. At 15:00 the whole
        // route scores as daylit, so a strip built from that number would
        // describe a walk whose last two hours have no sun in the sky.
        val s = shade("foot-long-4b4.json", 15)
        assertTrue("expected a dark tail on the 22.4 km walk", s.nightM > 5_000.0)
        assertTrue("expected a low-sun stretch", s.lowSunM > 0.0)
        assertEquals(
            "the buckets must still sum to the walk",
            s.totalM,
            s.answeredM + s.nightM + s.lowSunM + s.noEvidenceM,
            1e-6,
        )
        // Coverage stays honest: the dark share is excluded from the
        // denominator, so this reads as a modelled walk with an evening rather
        // than as a walk the model failed on.
        assertTrue(
            "coverage should be high on a modelled walk: ${s.coverage}",
            s.coverage > 0.85,
        )
        assertTrue("the daylit share must still be the majority", s.answeredM > s.nightM)
        assertTrue(s.stripLine()!!.contains("estimated"))

        // The same walk started at midnight is entirely dark, which is the
        // other end of the same property.
        assertEquals("No direct sun", shade("foot-long-4b4.json", 0).stripLine())
    }

    @Test
    fun `a real walk's segments are scored at different instants along its timeline`() {
        // The mechanism behind the previous test, stated directly: on the long
        // walk the modelled exposure of its earliest and latest segments must
        // differ, and the difference must be the sun moving rather than the
        // geometry — the segments are scored by the same model.
        val s = shade("foot-long-4b4.json", 15)
        val scored = s.segments.filter { it.solar != null }
        assertTrue("not enough scored segments", scored.size > 100)
        // The solar azimuth seen by the walk sweeps a long way over 4.6 hours.
        val early = scored.first().solar!!.azimuthDeg
        val late = scored.filter { it.solar!!.isDaylight }.lastOrNull()!!.solar!!.azimuthDeg
        // Measured on this fixture: the sun's azimuth as seen along the walk
        // moves from 253.8 deg at its near end to 272.6 deg at its far end —
        // 18.7 deg over 4 h 36 m of walking. That is the difference a
        // single-instant model cannot express.
        assertTrue(
            "the sun did not move along the walk: $early -> $late",
            Math.abs(dev.vector.geo.RouteGeometry.angDiffDeg(late, early)) > 15.0,
        )
        // And the sun is genuinely below the horizon at the walk's far end.
        assertTrue(s.segments.last().solar!!.altitudeDeg < 0.0)
    }

    // ---------------------------------------------- what reaches a person

    @Test
    fun `every real walk's strip line is one of the sanctioned sentences`() {
        // `stripLine` is the only path from this fact to a person, so the set
        // of strings it can produce on real data is the set of claims the
        // product can make. Pinning them here means a new sentence is a test
        // failure rather than a new claim shipped by accident.
        val bands = WalkShadeBand.entries.map { "${it.label} (estimated)" }
        val states = listOf(
            "No direct sun",
            "Low sun; shade not estimated",
            "Shade not estimated",
            "Limited shade data",
        )
        val allowed = (bands + states).toSet()
        for (name in WALKS) {
            for (hour in 0..23) {
                val line = shade(name, hour).stripLine()
                assertNotNull("$name at $hour:00 produced no sentence", line)
                assertTrue(
                    "$name at $hour:00 produced an unsanctioned sentence: \"$line\"",
                    line in allowed,
                )
            }
        }
    }

    @Test
    fun `no real walk ever renders a shade percentage or a certainty claim`() {
        // The journey card's sun slider owns the percentage; this surface does
        // not have the model to support one. And nothing here may promise.
        for (name in WALKS) {
            for (hour in 0..23) {
                val line = shade(name, hour).stripLine() ?: continue
                val lower = line.lowercase()
                assertFalse("$name at $hour:00 rendered a percentage: \"$line\"", line.contains("%"))
                for (claim in listOf(
                    "guarantee", "guaranteed", "safe", "protected", "cool",
                    "you will", "no shade", "full sun", "always", "never",
                )) {
                    assertFalse("$name at $hour:00: \"$claim\" in \"$line\"", lower.contains(claim))
                }
            }
        }
    }

    @Test
    fun `the shade fact never appears in a walking instruction`() {
        // The separation the presentation depends on, asserted over the real
        // fixtures rather than argued for. A maneuver's banner and detail come
        // from the backend's facts and nothing else; the sun is a route
        // property with a different provenance, so it cannot ride along in the
        // instruction vocabulary.
        for (name in WALKS) {
            val r = route(name)
            val rendered = r.plan.mapIndexed { i, m ->
                dev.vector.geo.walk.WalkInstructions.of(
                    m, planIndex = i,
                    status = dev.vector.geo.walk.WalkEventStatus.AHEAD,
                    distanceM = m.distanceM,
                )
            }.joinToString(" ") { "${it.banner} ${it.detail ?: ""}" }
            for (word in listOf("shade", "shaded", "sun", "estimated", "exposure")) {
                assertFalse(
                    "$name: \"$word\" reached an instruction: $rendered",
                    rendered.contains(word, ignoreCase = true),
                )
            }
        }
    }

    // -------------------------------------------------- real data, real tags

    @Test
    fun `the real fixtures' road classes are all in the model's table`() {
        // The measurement that makes `coverage == 1.0` a fact rather than an
        // assumption: every distinct `classes` value on this bake is one the
        // facade table knows. If a bake ever introduces one that is not, this
        // fails and the coverage path starts mattering — which is exactly the
        // signal worth having.
        val known = setOf(
            "footway", "path", "steps", "pedestrian", "corridor", "cycleway",
            "living_street", "residential", "service", "unclassified", "track",
            "secondary", "secondary_link", "tertiary", "tertiary_link",
            "primary", "primary_link", "trunk", "trunk_link", "motorway",
            "motorway_link",
        )
        val seen = mutableSetOf<String>()
        for (name in WALKS) {
            for (c in contract(name).segments.classes) {
                c?.let { seen.add(it) }
            }
        }
        assertTrue("no classes at all in the fixtures", seen.isNotEmpty())
        val unknown = seen - known
        assertTrue(
            "the bake carries a road class the facade model does not know: $unknown",
            unknown.isEmpty(),
        )
        // ...and none of the six walks is an open area or an enclosure, which
        // is why the DECLINED and ENCLOSED bases are unit-tested rather than
        // real-data tested here.
        for (name in WALKS) {
            val c = contract(name)
            assertFalse("$name has an area segment", c.segments.area.any { it == true })
            assertFalse("$name has an enclosed segment", c.segments.enclosed.any { it == true })
        }
    }

    @Test
    fun `a real walk's shaded metres are inside its own length`() {
        // The bounds a presentation bug would break, on real numbers rather
        // than synthetic ones.
        for (name in WALKS) {
            for (hour in intArrayOf(6, 9, 12, 15, 17)) {
                val s = shade(name, hour)
                val fa = WalkShadeFacts.of(contract(name), at(hour))
                assertEquals("$name at $hour:00", s.totalM, fa.totalM, 1e-6)
                assertTrue(
                    "$name at $hour:00: more shaded than answered",
                    s.shadedM <= s.answeredM + 1e-6,
                )
                assertTrue(
                    "$name at $hour:00: buckets exceed the walk",
                    s.answeredM + s.nightM + s.lowSunM + s.noEvidenceM <= s.totalM + 1e-6,
                )
                // The instant the fact carries is the one it was asked about.
                assertEquals(at(hour), s.startedAtMs)
                // A walk of this length has a real timeline, from the contract.
                assertEquals(contract(name).durationS!!, s.durationS, 1e-6)
            }
        }
    }

    @Test
    fun `a short real walk is well behaved at every hour`() {
        // 52.9 m, two segments. The smallest real input the model ever sees,
        // and the one most likely to produce a degenerate fraction.
        for (hour in 0..23) {
            val s = shade("foot-short-4b4.json", hour)
            assertEquals(52.9, s.totalM, 1.0)
            assertTrue(s.coverage in 0.0..1.0)
            assertTrue(s.modelledShadeFraction in 0.0..1.0)
            assertNotNull(s.stripLine())
            // The band must agree with the metres underneath it, at every
            // hour — the coherence a presentation bug would break.
            assertEquals(WalkShade.bandFor(s.modelledShadeFraction), s.band)
            // And no claim at night, whatever the segment tags say.
            if (hour >= 19 || hour <= 4) assertEquals("No direct sun", s.stripLine())
        }
    }

    private companion object {
        val WALKS = listOf(
            "foot-ordinary-4b4.json",
            "foot-crossing-4b4.json",
            "foot-stairs-4b4.json",
            "foot-barrier-4b4.json",
            "foot-short-4b4.json",
            "foot-long-4b4.json",
        )
    }
}
