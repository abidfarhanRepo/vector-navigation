package dev.vector.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lateral-position model — V7 Stage 4's heart.
 *
 * Two failures are worth more than all the others put together and both are
 * asserted first:
 *
 *  * **the sign.** A model with the offset inverted places the route in the
 *    ONCOMING lane with complete confidence, which is worse than the
 *    centreline it replaces. So the sign is checked against the two things
 *    that actually consume it — [RouteChevrons]' own normal and MapLibre's
 *    `line-offset` convention — rather than against this file's opinion.
 *  * **the claim.** The model must decline wherever the data does not support
 *    a lateral statement. Every decline case below corresponds to a real
 *    tagging state in the Qatar extract.
 */
class RouteLanesTest {

    private fun lane(vararg indications: String, valid: Boolean = false) =
        LaneGuidance.Lane(indications.toList(), valid = valid)

    /** `turn:lanes` marked for a maneuver, exactly as the client does it. */
    private fun marked(spec: String, type: String) =
        LaneGuidance.forManeuver(LaneGuidance.parse(spec), type)

    // ---- the sign convention -----------------------------------------------

    @Test
    fun `positive offset is to the right of travel, as RouteChevrons draws it`() {
        // Travelling due north from Doha. The chevron's right wing is the
        // authority here: RouteChevrons.mark puts it at +(dLng, dLat), and its
        // KDoc states "right of travel is bearing + 90".
        val at = LngLat(51.5310, 25.2854)
        val moved = RouteGeometry.offsetPoint(at, bearingDeg = 0.0, offsetM = 10.0)
        // East of a northbound vehicle is its right.
        assertTrue(moved.lng > at.lng, "positive offset must move EAST of a northbound route")
        assertEquals(at.lat, moved.lat, 1e-9, "a lateral offset must not move the vehicle along the route")
    }

    @Test
    fun `the offset agrees with RouteChevrons' own wing arithmetic`() {
        // Not a restatement of the formula: the chevron is built by the other
        // module and the right wing is read back out of it. If either side is
        // rewritten with the opposite normal, this fails.
        val coords = listOf(LngLat(51.5310, 25.2854), LngLat(51.5310, 25.2954))
        val index = assertNotNull(RouteGeometry.index(coords))
        val marks = RouteChevrons.marks(index, spacingM = 200.0, lengthM = 3.0, halfWidthM = 5.0)
        assertTrue(marks.isNotEmpty(), "fixture must produce at least one chevron")
        val mk = marks.first()
        val leftWing = mk[0]
        val apex = mk[1]
        val rightWing = mk[2]
        // The chevron's own back centre, recovered from the mark rather than
        // assumed: the two wings are equal and opposite offsets of it, so their
        // midpoint is it exactly. (Reading `mk[0]` as the centre is the mistake
        // this line exists to have made once — it is the LEFT wing, and
        // offsetting it again lands on the centreline while looking plausible.)
        val back = LngLat((leftWing.lng + rightWing.lng) / 2, (leftWing.lat + rightWing.lat) / 2)
        val bearing = RouteGeometry.bearingDeg(back, apex)
        val mine = RouteGeometry.offsetPoint(back, bearing, 5.0)
        assertEquals(rightWing.lng, mine.lng, 1e-9)
        assertEquals(rightWing.lat, mine.lat, 1e-9)
        // And the other way, so a symmetric sign error cannot pass both.
        val mineLeft = RouteGeometry.offsetPoint(back, bearing, -5.0)
        assertEquals(leftWing.lng, mineLeft.lng, 1e-9)
        assertEquals(leftWing.lat, mineLeft.lat, 1e-9)
    }

    @Test
    fun `a two-way road puts the route on the driving side, not the centreline`() {
        // MapLibre: "a positive value offsets the line to the right, relative
        // to the direction of the line" — the same convention VectorStyle's
        // near-side lane dividers come out negative under. Qatar drives on the
        // right, so the driven carriageway is at a POSITIVE offset.
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 500.0, forwardLanes = 1, totalLanes = 2)
        )
        assertEquals(RouteLanes.Basis.CARRIAGEWAY, l.basis)
        assertTrue(l.offsetM > 0.0, "driving on the right means a positive offset")
        assertEquals(1.75, l.offsetM, 1e-9)
        assertEquals(3.5, l.widthM!!, 1e-9)
    }

    // ---- the carriageway ---------------------------------------------------

    @Test
    fun `a one-way carriageway is the centreline, which is why most roads were already right`() {
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 500.0, forwardLanes = 3, totalLanes = 3)
        )
        assertEquals(RouteLanes.Basis.CARRIAGEWAY, l.basis)
        assertEquals(0.0, l.offsetM, 1e-9)
        assertEquals(10.5, l.widthM!!, 1e-9)
    }

    @Test
    fun `the commonest two-way case in Qatar moves the route a full half-carriageway`() {
        // 5,728 two-way `lanes=2` ways: one lane each way. Before Stage 4 the
        // ribbon sat on the centre paint, which is 1.75 m into oncoming.
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 400.0, forwardLanes = 1, totalLanes = 2)
        )
        assertEquals(RouteLanes.LANE_WIDTH_M / 2.0, l.offsetM, 1e-9)
    }

    @Test
    fun `a four-lane two-way road offsets by its own half, not by a constant`() {
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 400.0, forwardLanes = 2, totalLanes = 4)
        )
        assertEquals(3.5, l.offsetM, 1e-9)
        assertEquals(7.0, l.widthM!!, 1e-9)
    }

    @Test
    fun `no forward lane count means no claim at all`() {
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 400.0, forwardLanes = null, totalLanes = 4)
        )
        assertEquals(RouteLanes.Basis.UNKNOWN, l.basis)
        assertEquals(0.0, l.offsetM, 1e-9)
        assertNull(l.widthM)
    }

    @Test
    fun `a forward count without a total is still unknown`() {
        // Without `lanes` there is no way to tell whether the centreline being
        // measured from is the road's or the carriageway's, and the two differ
        // by half a road.
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 400.0, forwardLanes = 2, totalLanes = null)
        )
        assertEquals(RouteLanes.Basis.UNKNOWN, l.basis)
    }

    @Test
    fun `counts that are neither equal nor double are declined rather than guessed`() {
        // e.g. forward 2 of 5 — a tagging state with no single reading. The
        // backend already declines the odd two-way case; this is the client
        // refusing to reconstruct what the backend would not say.
        val l = RouteLanes.carriagewayOf(
            RouteLanes.Approach(atM = 400.0, forwardLanes = 2, totalLanes = 5)
        )
        assertEquals(RouteLanes.Basis.UNKNOWN, l.basis)
    }

    @Test
    fun `a zero or negative forward count is not a carriageway`() {
        assertEquals(
            RouteLanes.Basis.UNKNOWN,
            RouteLanes.carriagewayOf(RouteLanes.Approach(400.0, forwardLanes = 0, totalLanes = 0)).basis
        )
    }

    // ---- the lane band -----------------------------------------------------

    @Test
    fun `the slip-split case puts the band on the rightmost lane`() {
        // `slip-split-corniche`'s own approach: four lanes, only the outside
        // one continues into the slip.
        val a = RouteLanes.Approach(
            atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
            lanes = marked("through|through|through|through;slight_right", "slight-right"),
        )
        val cw = RouteLanes.carriagewayOf(a)
        val band = assertNotNull(RouteLanes.bandOf(a, cw))
        assertEquals(RouteLanes.Basis.TURN_LANES, band.basis)
        // Carriageway spans -7..+7; lane 3 spans +3.5..+7, centre +5.25.
        assertEquals(5.25, band.offsetM, 1e-9)
        assertEquals(3.5, band.widthM!!, 1e-9)
    }

    // ---- a turn with no lane data at all -----------------------------------

    @Test
    fun `a right turn with no turn lanes is placed in the rightmost lane`() {
        // The reported defect: "exits into certain lanes start from the middle
        // lane rather than the last lane on the right hand side". Without
        // `turn:lanes` the band was the carriageway centre — on a two-lane
        // carriageway, the paint between the lanes.
        val a = RouteLanes.Approach(
            atM = 500.0, forwardLanes = 2, totalLanes = 2,
            turn = RouteLanes.Turn.RIGHT,
        )
        val cw = RouteLanes.carriagewayOf(a)
        assertEquals(0.0, cw.offsetM, 1e-9)
        val band = assertNotNull(RouteLanes.outerLaneOf(a, cw))
        // One-way two-lane: the carriageway spans -3.5..+3.5, so the rightmost
        // lane's centre is +1.75 — NOT the carriageway centre at 0.
        assertEquals(RouteLanes.Basis.OUTER_LANE, band.basis)
        assertEquals(1.75, band.offsetM, 1e-9)
        assertEquals(3.5, band.widthM!!, 1e-9)
    }

    @Test
    fun `a left turn with no turn lanes is placed in the leftmost lane`() {
        val a = RouteLanes.Approach(
            atM = 500.0, forwardLanes = 3, totalLanes = 3,
            turn = RouteLanes.Turn.LEFT,
        )
        val band = assertNotNull(RouteLanes.outerLaneOf(a, RouteLanes.carriagewayOf(a)))
        // Three lanes spanning -5.25..+5.25; the leftmost lane's centre is -3.5.
        assertEquals(-3.5, band.offsetM, 1e-9)
    }

    @Test
    fun `the outer lane is measured from the driven carriageway, not the road`() {
        // A two-way four-lane road spans -7..+7. Its driven half is +0..+7 and
        // holds two lanes: +0..+3.5 and +3.5..+7. So the carriageway centre is
        // +3.5 — where the old fallback put the ribbon for every maneuver —
        // and the right turn's lane is +5.25.
        val a = RouteLanes.Approach(
            atM = 500.0, forwardLanes = 2, totalLanes = 4,
            turn = RouteLanes.Turn.RIGHT,
        )
        val cw = RouteLanes.carriagewayOf(a)
        assertEquals(3.5, cw.offsetM, 1e-9)
        val band = assertNotNull(RouteLanes.outerLaneOf(a, cw))
        assertEquals(5.25, band.offsetM, 1e-9)
        assertTrue(band.offsetM > cw.offsetM,
                   "the right turn must be right of the carriageway centre")
    }

    @Test
    fun `a through maneuver keeps the carriageway centre`() {
        // The rule must not reach the case the old fallback got right. Most
        // maneuvers are throughs and they are unchanged, which is also what
        // keeps this from moving every route in the country.
        val a = RouteLanes.Approach(
            atM = 500.0, forwardLanes = 2, totalLanes = 2,
            turn = RouteLanes.Turn.NONE,
        )
        assertNull(RouteLanes.outerLaneOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `a roundabout is not a turn for this rule`() {
        // Which exit is being taken is not in the maneuver type, and a ring's
        // lanes are concentric: the outer lane on the way IN says nothing about
        // the way out. Claiming one would be the fabrication this file refuses.
        assertEquals(RouteLanes.Turn.NONE, RouteLanes.Turn.of("roundabout"))
        assertEquals(RouteLanes.Turn.NONE, RouteLanes.Turn.of("merge"))
        assertEquals(RouteLanes.Turn.NONE, RouteLanes.Turn.of("continue"))
        assertEquals(RouteLanes.Turn.NONE, RouteLanes.Turn.of("arrive"))
        assertEquals(RouteLanes.Turn.NONE, RouteLanes.Turn.of(""))
    }

    @Test
    fun `the maneuver vocabulary maps to the side a driver turns`() {
        assertEquals(RouteLanes.Turn.LEFT, RouteLanes.Turn.of("turn-left"))
        assertEquals(RouteLanes.Turn.LEFT, RouteLanes.Turn.of("slight-left"))
        assertEquals(RouteLanes.Turn.LEFT, RouteLanes.Turn.of("keep-left"))
        assertEquals(RouteLanes.Turn.RIGHT, RouteLanes.Turn.of("turn-right"))
        assertEquals(RouteLanes.Turn.RIGHT, RouteLanes.Turn.of("slight-right"))
        assertEquals(RouteLanes.Turn.RIGHT, RouteLanes.Turn.of("keep-right"))
    }

    @Test
    fun `a single-lane carriageway needs no rule to find its outer lane`() {
        val a = RouteLanes.Approach(
            atM = 500.0, forwardLanes = 1, totalLanes = 1,
            turn = RouteLanes.Turn.RIGHT,
        )
        assertNull(RouteLanes.outerLaneOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `an unplaced carriageway has no outer lane`() {
        // Lane N of a carriageway whose position is unknown is not a position.
        val a = RouteLanes.Approach(
            atM = 500.0, forwardLanes = 2, totalLanes = 3,
            turn = RouteLanes.Turn.RIGHT,
        )
        assertNull(RouteLanes.outerLaneOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `turn lanes win over the turn rule when the road states them`() {
        // `turn:lanes` is evidence about THIS road; the rule is a statement
        // about traffic law. Evidence wins, even when it says the right turn is
        // made from the middle lane (which some signed junctions do).
        val a = RouteLanes.Approach(
            atM = 1_000.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("through|right|through", "turn-right"),
            turn = RouteLanes.Turn.RIGHT,
        )
        val cw = RouteLanes.carriagewayOf(a)
        val band = assertNotNull(RouteLanes.bandOf(a, cw))
        assertEquals(RouteLanes.Basis.TURN_LANES, band.basis)
        assertEquals(0.0, band.offsetM, 1e-9)
    }

    // ---- merge lanes (Stage 4 corrective, post-close review) ----------------
    //
    // Qatar's extract marks merges two ways: the merge vocabulary proper
    // (`merge_to_left/right`, 77 ways in the deployed graph) and the slight
    // vocabulary (`slight_left`, e.g. the `slight_left;through|through|through`
    // approach of the m3000 road). Both must move the RIBBON into the merge
    // lane — the band, not just the arrow — because the backend expresses a
    // merge as a slight turn and a lane signed for the merge is the lane the
    // merge uses. Where OSM says nothing, the refusal behaviour (below, Cause A
    // untouched) still stands.

    @Test
    fun `the ribbon moves into a slight-left merge lane where OSM signs one`() {
        // The LITERAL m3000 lane string from the app fixture
        // reroute-wrong-road.json (step 2, the slip road):
        // `slight_left;through|through|through` — lane 0 serves the through
        // flow AND the left merge. Under the slight-left maneuver a merge
        // occupies (router._classify_turn has no merge type), that lane is
        // claimed and the ribbon moves off the carriageway centre.
        val a = RouteLanes.Approach(
            atM = 1_000.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("slight_left;through|through|through", "slight-left"),
        )
        val band = assertNotNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
        assertEquals(RouteLanes.Basis.TURN_LANES, band.basis)
        // A 3-lane one-way carriageway spans -5.25..+5.25; the left (merge)
        // lane spans -5.25..-1.75, centre -3.5.
        assertEquals(-3.5, band.offsetM, 1e-9)
    }

    @Test
    fun `the ribbon moves into a merge_to_left lane the extract actually carries`() {
        // 77 ways in the deployed Qatar graph carry merge_to_* cells; before
        // the acceptedIndications fix, ALL of them were invalid and the
        // ribbon sat on the carriageway centre (offset 0). Now the signed
        // merge lane is claimed.
        val a = RouteLanes.Approach(
            atM = 1_000.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("merge_to_left|through|through", "slight-left"),
        )
        val band = assertNotNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
        assertEquals(-3.5, band.offsetM, 1e-9)
    }

    @Test
    fun `the plan holds the merge lane at the junction, proving the ribbon moves`() {
        // A whole-route proof, the same shape as "a two-way leg holds its
        // offset for the whole leg": the offset AT the junction is the merge
        // lane's, so the ribbon is drawn into the merge rather than merely
        // changing the arrow or the spoken word.
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(
                    atM = 1_000.0, forwardLanes = 3, totalLanes = 3,
                    lanes = marked("merge_to_left|through|through", "slight-left"),
                ),
            ),
        )
        assertEquals(-3.5, plan.offsetAt(1_000.0), 1e-9)
        // And the merge lane must not leak backwards forever: the leading
        // taper starts only within BAND_LEAD_M of the junction, so 200 m
        // before it the ribbon is still the carriageway centre (offset 0).
        assertEquals(0.0, plan.offsetAt(400.0), 1e-9)
    }

    @Test
    fun `a two-lane band is centred across both of them`() {
        val a = RouteLanes.Approach(
            atM = 900.0, forwardLanes = 4, totalLanes = 4,
            lanes = marked("left|left|through|through", "turn-left"),
        )
        val band = assertNotNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
        // Lanes 0-1 span -7..0, centre -3.5. Negative: the left lanes are to
        // the LEFT, which is the sign convention doing its job.
        assertEquals(-3.5, band.offsetM, 1e-9)
        assertEquals(7.0, band.widthM!!, 1e-9)
    }

    @Test
    fun `a band on a two-way road is measured from the driven carriageway, not the road`() {
        // The two corrections compose: 2 forward of 4 total puts the
        // carriageway centre at +3.5, and the left-turn lane is the inner one
        // of that carriageway — NOT the inner one of the road, which is
        // oncoming.
        val a = RouteLanes.Approach(
            atM = 800.0, forwardLanes = 2, totalLanes = 4,
            lanes = marked("left|through", "turn-left"),
        )
        val cw = RouteLanes.carriagewayOf(a)
        assertEquals(3.5, cw.offsetM, 1e-9)
        val band = assertNotNull(RouteLanes.bandOf(a, cw))
        // Carriageway spans 0..+7; lane 0 spans 0..3.5, centre +1.75.
        assertEquals(1.75, band.offsetM, 1e-9)
        assertTrue(band.offsetM > 0.0, "a turn lane must never cross onto the oncoming carriageway")
    }

    @Test
    fun `a lane string that disagrees with the carriageway count claims nothing`() {
        // `left|through` against three forward lanes describes a different
        // road. Stretching it to fit moves the band by half a lane per missing
        // cell, in the direction nobody checked.
        val a = RouteLanes.Approach(
            atM = 800.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("left|through", "turn-left"),
        )
        assertNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `non-contiguous valid lanes have no honest single offset`() {
        // `left|through|left`: the average of the two is the one lane that does
        // NOT turn. The strip can show both; the ribbon cannot, so it declines.
        val a = RouteLanes.Approach(
            atM = 800.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("left|through|left", "turn-left"),
        )
        assertNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `every lane valid is not a band`() {
        // `reverse;left|left|left` on a left turn — the real Doha fixture from
        // `highway-wakrah-doha`. It narrows nothing, and LaneGuidance already
        // suppresses the strip for it.
        val a = RouteLanes.Approach(
            atM = 800.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("reverse;left|left|left", "turn-left"),
        )
        assertNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `no valid lane means no band`() {
        val a = RouteLanes.Approach(
            atM = 800.0, forwardLanes = 3, totalLanes = 3,
            lanes = marked("through|through|through", "turn-left"),
        )
        assertNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
    }

    @Test
    fun `a band needs a placed carriageway to be measured from`() {
        // Lane 4 of an unplaced carriageway is not a position.
        val a = RouteLanes.Approach(
            atM = 800.0, forwardLanes = null, totalLanes = null,
            lanes = marked("left|through|through", "turn-left"),
        )
        assertNull(RouteLanes.bandOf(a, RouteLanes.carriagewayOf(a)))
    }

    // ---- the profile -------------------------------------------------------

    @Test
    fun `a route with no lane data anywhere is the centreline, exactly as before Stage 4`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(atM = 500.0),
                RouteLanes.Approach(atM = 1_200.0),
            )
        )
        for (d in listOf(0.0, 100.0, 499.0, 500.0, 900.0, 1_200.0, 5_000.0)) {
            val l = plan.lateralAt(d)
            assertEquals(0.0, l.offsetM, 1e-9, "offset at ${d}m")
            assertEquals(RouteLanes.Basis.UNKNOWN, l.basis)
        }
    }

    @Test
    fun `a two-way leg holds its offset for the whole leg`() {
        val plan = RouteLanes.plan(
            listOf(RouteLanes.Approach(atM = 1_000.0, forwardLanes = 1, totalLanes = 2))
        )
        assertEquals(1.75, plan.offsetAt(0.0), 1e-9)
        assertEquals(1.75, plan.offsetAt(500.0), 1e-9)
        assertEquals(1.75, plan.offsetAt(1_000.0), 1e-9)
    }

    @Test
    fun `the band opens with a taper, not a step`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(
                    atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                    lanes = marked("through|through|through|through;slight_right", "slight-right"),
                )
            )
        )
        // Band claimed for the last BAND_LEAD_M; taper one TAPER_M before that.
        val bandStart = 1_000.0 - RouteLanes.BAND_LEAD_M
        assertEquals(0.0, plan.offsetAt(bandStart - RouteLanes.TAPER_M), 1e-9)
        assertEquals(5.25, plan.offsetAt(bandStart), 1e-9)
        assertEquals(5.25, plan.offsetAt(1_000.0), 1e-9)
        // Half way through the taper, half way across.
        assertEquals(2.625, plan.offsetAt(bandStart - RouteLanes.TAPER_M / 2), 1e-6)
        assertEquals(RouteLanes.Basis.TAPER, plan.lateralAt(bandStart - RouteLanes.TAPER_M / 2).basis)
    }

    @Test
    fun `the offset moves no faster than a car changes lane`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(
                    atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                    lanes = marked("left|left|through|through", "turn-left"),
                )
            )
        )
        var prev = plan.offsetAt(0.0)
        var worst = 0.0
        var d = 1.0
        while (d <= 1_000.0) {
            val now = plan.offsetAt(d)
            worst = maxOf(worst, abs(now - prev))
            prev = now
            d += 1.0
        }
        // One metre of travel may not move the route more than one lane width
        // over the taper length — i.e. no step changes anywhere.
        assertTrue(worst <= RouteLanes.LANE_WIDTH_M / RouteLanes.TAPER_M + 1e-6,
            "lateral rate $worst m/m is a jump, not a lane change")
    }

    @Test
    fun `a dense junction collapses to one taper instead of pumping`() {
        // Msheireb: maneuvers 120 m apart, each with its own turn lane. Without
        // collapse the ribbon would reach the band, settle, return to the
        // carriageway centre and leave again inside a block.
        val lanes = marked("left|through", "turn-left")
        val plan = RouteLanes.plan(
            (1..6).map {
                RouteLanes.Approach(
                    atM = 120.0 * it, forwardLanes = 2, totalLanes = 2,
                    lanes = lanes,
                )
            }
        )
        var prev = plan.offsetAt(0.0)
        var reversals = 0
        var lastDir = 0
        var d = 1.0
        while (d <= 720.0) {
            val now = plan.offsetAt(d)
            val dir = when {
                now > prev + 1e-9 -> 1
                now < prev - 1e-9 -> -1
                else -> 0
            }
            if (dir != 0 && lastDir != 0 && dir != lastDir) reversals++
            if (dir != 0) lastDir = dir
            prev = now
            d += 1.0
        }
        // One reversal per junction is the turn itself; more than that is the
        // ribbon oscillating between two representations of the same road.
        assertTrue(reversals <= 6, "lateral pumping: $reversals reversals over six close maneuvers")
    }

    @Test
    fun `leaving a junction tapers along the road rather than across it`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(atM = 500.0, forwardLanes = 3, totalLanes = 3),
                RouteLanes.Approach(atM = 2_000.0, forwardLanes = 1, totalLanes = 2),
            )
        )
        // At the junction the previous leg's offset still holds (0, a one-way
        // carriageway); a taper later the new two-way offset is established.
        assertEquals(0.0, plan.offsetAt(500.0), 1e-9)
        assertEquals(1.75, plan.offsetAt(500.0 + RouteLanes.TAPER_M), 1e-9)
        assertEquals(RouteLanes.Basis.TAPER, plan.lateralAt(500.0 + RouteLanes.TAPER_M / 2).basis)
    }

    @Test
    fun `a departure maneuver at zero metres contributes no degenerate section`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(atM = 0.0, forwardLanes = 2, totalLanes = 4),
                RouteLanes.Approach(atM = 800.0, forwardLanes = 2, totalLanes = 4),
            )
        )
        assertEquals(3.5, plan.offsetAt(0.0), 1e-9)
        assertEquals(3.5, plan.offsetAt(800.0), 1e-9)
    }

    @Test
    fun `maneuvers at the same vertex do not make the profile run backwards`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(atM = 300.0, forwardLanes = 1, totalLanes = 2),
                RouteLanes.Approach(atM = 300.0, forwardLanes = 3, totalLanes = 3),
                RouteLanes.Approach(atM = 900.0, forwardLanes = 3, totalLanes = 3),
            )
        )
        var prev = -1.0
        for (n in plan.nodes) {
            assertTrue(n.alongM >= prev, "profile ran backwards at ${n.alongM}")
            prev = n.alongM
        }
    }

    @Test
    fun `an empty route plans nothing and answers the centreline`() {
        val plan = RouteLanes.plan(emptyList())
        assertTrue(plan.isEmpty)
        assertEquals(0.0, plan.offsetAt(100.0), 1e-9)
        assertEquals(RouteLanes.Basis.UNKNOWN, plan.lateralAt(100.0).basis)
        assertTrue(plan.slices().isEmpty())
    }

    // ---- slices ------------------------------------------------------------

    @Test
    fun `a settled section is one slice and a taper is several`() {
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(
                    atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                    lanes = marked("through|through|through|through;slight_right", "slight-right"),
                )
            )
        )
        val slices = plan.slices(stepM = 0.25)
        assertTrue(slices.isNotEmpty())
        // Contiguous and ordered — a gap is a length of route with no ribbon.
        for (i in 1 until slices.size) {
            assertEquals(slices[i - 1].toM, slices[i].fromM, 1e-9, "gap before slice $i")
        }
        assertEquals(0.0, slices.first().fromM, 1e-9)
        assertEquals(1_000.0, slices.last().toM, 1e-9)
        // The taper is cut finely enough that no slice is more than stepM from
        // the true ramp.
        for (s in slices) {
            val mid = plan.offsetAt((s.fromM + s.toM) / 2)
            assertEquals(mid, s.offsetM, 1e-9)
            val err = maxOf(
                abs(plan.offsetAt(s.fromM) - s.offsetM),
                abs(plan.offsetAt(s.toM) - s.offsetM),
            )
            assertTrue(err <= 0.25 + 1e-6, "slice ${s.fromM}-${s.toM} is ${err}m off the ramp")
        }
    }

    @Test
    fun `a centreline route slices without claiming anything`() {
        val plan = RouteLanes.plan(listOf(RouteLanes.Approach(atM = 600.0)))
        val slices = plan.slices()
        assertTrue(slices.all { it.offsetM == 0.0 && it.basis == RouteLanes.Basis.UNKNOWN })
    }

    // ---- the vocabulary ----------------------------------------------------

    @Test
    fun `the model never reports a measured vehicle lane`() {
        // A guard on the API surface rather than on a value: the moment
        // something here is called `vehicleLane` or `currentLane`, a reader is
        // entitled to believe GPS measured it, and at 1 Hz against a 3.5 m lane
        // it did not. Basis names EVIDENCE, and none of its values is a
        // measurement.
        val names = RouteLanes.Basis.values().map { it.name }
        assertEquals(
            listOf("UNKNOWN", "CARRIAGEWAY", "TURN_LANES", "OUTER_LANE", "TAPER"),
            names,
            "Basis must describe evidence, never a measured vehicle lane",
        )
        // OUTER_LANE is the ONE value that is a rule rather than a reading, and
        // it is listed here so that stays visible: everything else can be traced
        // to a tag on this road, and a lane inferred from the side of a turn
        // cannot. A caller that needs "which placements were measured" has an
        // answer — every basis but this one.
        assertEquals(
            listOf("OUTER_LANE"),
            names.filter { it == "OUTER_LANE" },
            "the inferred basis must be the exception, not the pattern",
        )
    }

    @Test
    fun `an unknown width is never averaged into a known one`() {
        // Half of a carriageway that half exists is not a carriageway.
        val plan = RouteLanes.plan(
            listOf(
                RouteLanes.Approach(atM = 500.0, forwardLanes = 2, totalLanes = 2),
                RouteLanes.Approach(atM = 1_500.0),
            )
        )
        assertNull(plan.lateralAt(500.0 + RouteLanes.TAPER_M / 2).widthM)
    }

    @Test
    fun `the lane width matches the one the basemap draws carriageways with`() {
        // core-geo cannot see VectorStyle, so the value is restated there and
        // asserted from both sides. 3.5 m here and 3.65 m there would put the
        // route on the paint rather than in the lane.
        assertEquals(3.5, RouteLanes.LANE_WIDTH_M, 1e-9)
    }
}
