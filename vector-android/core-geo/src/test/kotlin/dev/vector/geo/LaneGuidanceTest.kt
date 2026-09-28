package dev.vector.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lane guidance — the last big feature gap against Google Maps and Waze.
 *
 * The fixtures below are the actual most-common `turn:lanes` values in the Doha
 * extract (3,090 ways carry the tag): `left|left`, `left|left|left`, `right`,
 * `through|through|through`, `reverse;left|left|left`, `left|through|through`.
 *
 * The failure this guards against is not a crash — it is confidently pointing a
 * driver at the wrong lane on a six-lane arterial, which is worse than saying
 * nothing.
 */
class LaneGuidanceTest {

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `a simple three-lane spec parses left to right`() {
        val lanes = LaneGuidance.parse("left|through|through")
        assertEquals(3, lanes.size)
        assertEquals(listOf("left"), lanes[0].indications)
        assertEquals(listOf("through"), lanes[2].indications)
    }

    @Test
    fun `a lane permitting several movements keeps all of them`() {
        // `reverse;left` is one lane that allows both a U-turn and a left.
        val lanes = LaneGuidance.parse("reverse;left|left|left")
        assertEquals(3, lanes.size)
        assertEquals(listOf("reverse", "left"), lanes[0].indications)
    }

    @Test
    fun `unmarked lanes are still lanes`() {
        // `||right` is THREE lanes. Dropping the blanks would shift the count
        // and point the driver at the wrong one.
        val lanes = LaneGuidance.parse("||right")
        assertEquals(3, lanes.size)
        assertTrue(lanes[0].indications.isEmpty())
        assertEquals(listOf("right"), lanes[2].indications)
    }

    @Test
    fun `a single lane parses`() {
        assertEquals(1, LaneGuidance.parse("right").size)
    }

    @Test
    fun `absent or blank data yields no lanes, not a fake one`() {
        assertTrue(LaneGuidance.parse(null).isEmpty())
        assertTrue(LaneGuidance.parse("").isEmpty())
        assertTrue(LaneGuidance.parse("   ").isEmpty())
    }

    @Test
    fun `whitespace and case are tolerated`() {
        val lanes = LaneGuidance.parse(" Left ; Reverse | THROUGH ")
        assertEquals(listOf("left", "reverse"), lanes[0].indications)
        assertEquals(listOf("through"), lanes[1].indications)
    }

    // ---- which lane to use -------------------------------------------------

    @Test
    fun `a left turn lights the left lane only`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through|through"), "turn-left"
        )
        assertEquals(listOf(true, false, false), lanes.map { it.valid })
    }

    @Test
    fun `a right turn lights the right lane only`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through|right"), "turn-right"
        )
        assertEquals(listOf(false, false, true), lanes.map { it.valid })
    }

    @Test
    fun `a lane permitting the movement among others still counts`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("reverse;left|left|through"), "turn-left"
        )
        assertEquals(listOf(true, true, false), lanes.map { it.valid })
    }

    @Test
    fun `a U-turn only accepts a reverse lane`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("reverse;left|left|left"), "uturn"
        )
        assertEquals(listOf(true, false, false), lanes.map { it.valid })
    }

    @Test
    fun `a slight left accepts a plain left lane`() {
        // Generous in this direction: a left-marked lane will take you round a
        // shallow bend.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through"), "slight-left"
        )
        assertTrue(lanes[0].valid)
    }

    @Test
    fun `a left turn does NOT accept a through lane`() {
        // Strict in this direction, and this is the one that matters: going
        // straight on will not make the turn.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("through|through|through"), "turn-left"
        )
        assertEquals(listOf(false, false, false), lanes.map { it.valid })
    }

    @Test
    fun `continuing is not a lane decision`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through|right"), "continue"
        )
        assertTrue(lanes.none { it.valid })
    }

    // ---- merge vocabulary (Stage 4 corrective, post-close review) ---------
    //
    // The backend has no merge maneuver type: router._classify_turn emits
    // slight-left/right for the 20-45 degree band a merge occupies. So a ramp
    // signed `merge_to_right` arrives under a `slight-right` maneuver, and the
    // lane whose ONLY use is merging right is exactly the lane that serves it.
    // Measured: 77 ways in the deployed Qatar graph carry merge_to_* cells.

    @Test
    fun `a right merge lane serves a slight right maneuver`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("through|through|merge_to_right"), "slight-right"
        )
        assertEquals(listOf(false, false, true), lanes.map { it.valid })
    }

    @Test
    fun `a left merge lane serves a slight left maneuver`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("merge_to_left|through|through"), "slight-left"
        )
        assertEquals(listOf(true, false, false), lanes.map { it.valid })
    }

    @Test
    fun `a merge lane serves a full turn on its own side`() {
        // A merge is also acceptable for the turn-shaped maneuver this backend
        // sometimes emits at an exit (a 45-135 degree delta): a lane whose only
        // use is merging right will take the right exit.
        val right = LaneGuidance.forManeuver(
            LaneGuidance.parse("through|through|merge_to_right"), "turn-right"
        )
        assertEquals(listOf(false, false, true), right.map { it.valid })
        val left = LaneGuidance.forManeuver(
            LaneGuidance.parse("merge_to_left|through|through"), "turn-left"
        )
        assertEquals(listOf(true, false, false), left.map { it.valid })
    }

    @Test
    fun `a continuing driver is never routed into the diverging merge lane`() {
        // The strict half of the same rule: the merge cell DIVERGES from the
        // mainline, so a driver who is continuing must not be pulled into it.
        // This is what keeps the m3000-style road (a mainline whose left lane
        // diverges) honest: continue marks nothing.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("merge_to_left|through|through"), "continue"
        )
        assertTrue(lanes.none { it.valid })
    }

    @Test
    fun `an unknown maneuver type marks nothing valid rather than everything`() {
        val lanes = LaneGuidance.forManeuver(LaneGuidance.parse("left|right"), "teleport")
        assertTrue(lanes.none { it.valid })
    }

    // ---- when to show it ---------------------------------------------------

    @Test
    fun `guidance is shown when it narrows the choice`() {
        assertTrue(LaneGuidance.isUseful(
            LaneGuidance.forManeuver(LaneGuidance.parse("left|through|through"), "turn-left")
        ))
    }

    @Test
    fun `guidance is hidden when every lane works`() {
        // "stay where you are" is noise.
        assertFalse(LaneGuidance.isUseful(
            LaneGuidance.forManeuver(LaneGuidance.parse("left|left|left"), "turn-left")
        ))
    }

    @Test
    fun `guidance is hidden when no lane works`() {
        // The data disagrees with the route; showing it would be misleading.
        assertFalse(LaneGuidance.isUseful(
            LaneGuidance.forManeuver(LaneGuidance.parse("through|through"), "turn-left")
        ))
    }

    @Test
    fun `a single lane needs no guidance`() {
        assertFalse(LaneGuidance.isUseful(
            LaneGuidance.forManeuver(LaneGuidance.parse("right"), "turn-right")
        ))
    }

    @Test
    fun `no data means no guidance`() {
        assertFalse(LaneGuidance.isUseful(emptyList()))
    }

    // ---- V7 Stage 1: dedicated vs preferred vs unknown ---------------------

    @Test
    fun `a lane that exists only for the maneuver is dedicated`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through|through"), "turn-left"
        )
        assertTrue(lanes[0].dedicated, "a `left` lane for a left turn is dedicated")
        assertFalse(lanes[1].dedicated)
    }

    @Test
    fun `a legal lane that also goes straight is not dedicated`() {
        // `left;through|through` — the left lane serves the turn but also
        // continues, so calling it dedicated would overclaim.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left;through|through"), "turn-left"
        )
        assertTrue(lanes[0].valid)
        assertFalse(lanes[0].dedicated)
    }

    @Test
    fun `a reverse left lane is valid for a left turn but not dedicated`() {
        // Doha's commonest left lane is `reverse;left`; the U-turn half means
        // it is not ONLY for the maneuver.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("reverse;left|left|left"), "turn-left"
        )
        assertTrue(lanes[0].valid)
        assertFalse(lanes[0].dedicated)
    }

    @Test
    fun `dedication survives the slight-left generosity`() {
        // A plain `left` lane is dedicated for a slight-left too: it exists
        // only for the movement the maneuver accepts.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through"), "slight-left"
        )
        assertTrue(lanes[0].dedicated)
    }

    @Test
    fun `preferred lanes come only from backend provenance`() {
        val lanes = LaneGuidance.parse("left|through|through")
        assertTrue(LaneGuidance.preferredIndices(lanes, preferred = null).isEmpty(),
            "no provenance must mean no preference")
        assertEquals(setOf(0), LaneGuidance.preferredIndices(lanes, listOf(0)))
        // Out-of-range indices are dropped, never extended.
        assertEquals(setOf(), LaneGuidance.preferredIndices(lanes, listOf(9)))
        assertEquals(setOf(1), LaneGuidance.preferredIndices(lanes, listOf(9, 1)))
    }

    @Test
    fun `unknown status means no data or no lane decision`() {
        assertEquals(LaneGuidance.Status.UNKNOWN, LaneGuidance.statusOf(null, "turn-left"))
        assertEquals(LaneGuidance.Status.UNKNOWN, LaneGuidance.statusOf("", "turn-left"))
        // continue/depart/arrive/roundabout never make a lane decision.
        assertEquals(LaneGuidance.Status.UNKNOWN,
            LaneGuidance.statusOf("left|right", "continue"))
        assertEquals(LaneGuidance.Status.UNKNOWN,
            LaneGuidance.statusOf("left|through", "roundabout"))
    }

    @Test
    fun `present data that narrows nothing is not unknown`() {
        assertEquals(LaneGuidance.Status.NONE_USEFUL,
            LaneGuidance.statusOf("left|left|left", "turn-left"))
        assertEquals(LaneGuidance.Status.NONE_USEFUL,
            LaneGuidance.statusOf("right", "turn-right"))
        assertEquals(LaneGuidance.Status.USEFUL,
            LaneGuidance.statusOf("left|through|through", "turn-left"))
    }

    // ---- the real Doha values ----------------------------------------------

    @Test
    fun `the six most common Doha specs all behave sensibly`() {
        val cases = listOf(
            "left|left" to "turn-left",
            "left|left|left" to "turn-left",
            "right" to "turn-right",
            "through|through|through" to "continue",
            "reverse;left|left|left" to "turn-left",
            "left|through|through" to "turn-left",
        )
        for ((spec, maneuver) in cases) {
            val parsed = LaneGuidance.parse(spec)
            assertTrue(parsed.isNotEmpty(), "$spec parsed to nothing")
            val marked = LaneGuidance.forManeuver(parsed, maneuver)
            assertEquals(parsed.size, marked.size, "$spec changed lane count")
            // Never claim a lane the spec does not permit.
            for ((i, l) in marked.withIndex()) {
                if (l.valid) {
                    assertTrue(l.indications.isNotEmpty(),
                        "$spec lane $i claimed valid with no indication")
                }
            }
        }
    }

    @Test
    fun `every indication renders as an arrow`() {
        for (ind in listOf("left", "right", "through", "reverse", "slight_left",
                           "slight_right", "sharp_left", "sharp_right", "", "wat")) {
        }
    }
    // ---- V5: which arrow, and when ------------------------------------------

    @Test
    fun `the lane to be in draws the movement it was chosen for`() {
        // THE DEFECT. OSM writes indications in the order the signs are
        // painted, not in order of usefulness, and Doha's commonest left-turn
        // lane is tagged `reverse;left`. `primary` returned the FIRST
        // indication, so the lane the driver should be in was drawn as a
        // U-turn arrow.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("reverse;left|left|through"), "turn-left"
        )
        assertEquals("left", lanes[0].primary)
        assertEquals("turn-left", LaneGuidance.arrowType(lanes[0].primary))
    }

    @Test
    fun `an unusable lane still draws its own first indication`() {
        // It is dimmed, not relabelled: the driver counts lanes across the
        // whole carriageway, and a through lane has to look like one.
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through|through"), "turn-left"
        )
        assertEquals("through", lanes[1].primary)
        assertFalse(lanes[1].valid)
    }

    @Test
    fun `nothing matched means nothing is claimed to have matched`() {
        val lanes = LaneGuidance.forManeuver(
            LaneGuidance.parse("left|through"), "continue"
        )
        assertTrue(lanes.all { it.matched == null })
        assertTrue(lanes.none { it.valid })
    }

    @Test
    fun `every indication has an arrow and none of them is a database word`() {
        for (ind in listOf("left", "right", "slight_left", "slight_right",
                           "sharp_left", "sharp_right", "through", "reverse",
                           "merge_to_left", "merge_to_right", "none", "")) {
            val t = LaneGuidance.arrowType(ind)
            assertTrue(t in setOf("turn-left", "turn-right",
                "slight-left", "slight-right", "uturn", "depart"), "$ind -> $t")
            assertFalse(LaneGuidance.spoken(ind).contains("_"), "$ind reads out as a tag")
        }
    }

    // ---- the approach window ------------------------------------------------
    //
    // A maneuver becomes current the moment the previous one completes, so
    // without a distance rule the diagram appeared at the start of the leg and
    // stayed. On the 7.4 km leg of the live router's Al Wakrah route that is
    // seven kilometres of advice the driver cannot act on.

    @Test
    fun `lane guidance is not shown for the whole leg`() {
        assertFalse(LaneGuidance.showAt(7_400.0, 50.0 / 3.6), "7 km out")
        assertFalse(LaneGuidance.showAt(1_000.0, 50.0 / 3.6), "1 km out in town")
    }

    @Test
    fun `lane guidance appears with time to cross traffic`() {
        // Twenty seconds of travel is the budget: look, indicate, wait for a
        // gap, twice.
        val urban = 50.0 / 3.6
        assertTrue(LaneGuidance.showAt(urban * LaneGuidance.LANE_LEAD_SECONDS - 5, urban))
        assertFalse(LaneGuidance.showAt(urban * LaneGuidance.LANE_LEAD_SECONDS + 60, urban))
    }

    @Test
    fun `the window has a floor so town driving still gets it`() {
        // At 20 km/h twenty seconds is 111 m, which is inside the junction.
        assertTrue(LaneGuidance.showAt(240.0, 20.0 / 3.6))
        assertTrue(LaneGuidance.showAt(200.0, 0.0), "a standstill must still show it")
    }

    @Test
    fun `the window has a cap so a motorway leg is not a billboard`() {
        // 20 s at 140 km/h is 778 m; the cap holds it at 700.
        assertFalse(LaneGuidance.showAt(760.0, 140.0 / 3.6))
        assertTrue(LaneGuidance.showAt(690.0, 140.0 / 3.6))
    }

    @Test
    fun `a maneuver already behind us shows nothing`() {
        assertFalse(LaneGuidance.showAt(0.0, 14.0))
        assertFalse(LaneGuidance.showAt(-30.0, 14.0))
    }

}
