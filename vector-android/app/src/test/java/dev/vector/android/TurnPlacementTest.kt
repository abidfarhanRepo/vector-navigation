package dev.vector.android

import dev.vector.geo.RouteLanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A turn with no `turn:lanes` is placed in the lane it is made from.
 *
 * ## The defect
 *
 * Reported from a drive as *"exits into certain lanes start from the middle lane
 * rather than the last lane on the right hand side"*. The lateral model's
 * fallback for a maneuver with no `turn:lanes` was the driven carriageway's
 * centre — which is correct for a through and, on any carriageway with more
 * than one lane, is the paint between the lanes for a turn.
 *
 * ## Why this runs on a captured route rather than a fixture I wrote
 *
 * `two-way-rabia.json` is a real `/navigate` answer, and it happens to contain
 * exactly the case: a `turn-left` at 1,744.745 m with `forward_lanes = 2`,
 * `approach_lanes = 2` and **no `turn:lanes`**. Everything below goes through
 * the same parse the app does — [DriveHarness.load] calls
 * [VectorApi.parseRouteFeature] — and then through the same `Approach` mapping
 * `MainActivity.lateralPlan` uses, so this fails if the wire field, the model or
 * the caller's mapping drifts.
 *
 * The assertion is on the OFFSET, in metres, signed: the failure being guarded
 * is a placement, and a distance cannot express it.
 */
class TurnPlacementTest {

    private fun planOf(name: String, routeId: Int = 0): Pair<DriveHarness.Plan, RouteLanes.Plan> {
        val p = DriveHarness.load(name, routeId)
        val lanes = RouteLanes.plan(
            p.maneuvers.map {
                RouteLanes.Approach(
                    atM = it.cumulativeM,
                    forwardLanes = it.forwardLanes,
                    totalLanes = it.approachLanes,
                    lanes = it.lanes,
                    turn = RouteLanes.Turn.of(it.type),
                )
            }
        )
        return p to lanes
    }

    @Test
    fun `a left turn on a two-lane one-way street sits in the left lane, not on the divider`() {
        val (p, lanes) = planOf("two-way-rabia")
        val turn = p.maneuvers.first {
            it.type == "turn-left" && it.forwardLanes == 2 && it.lanes.isEmpty()
        }
        // The leg is settled by the time the maneuver arrives; read it just
        // before, which is where the driver is when it matters.
        val l = lanes.lateralAt(turn.cumulativeM - 5.0)
        assertEquals(RouteLanes.Basis.OUTER_LANE, l.basis)
        // One-way, two lanes: the carriageway centre is 0 and the left lane's
        // centre is 1.75 m to the left of it.
        assertEquals(-1.75, l.offsetM, 0.01)
        assertEquals(3.5, l.widthM!!, 0.01)
    }

    @Test
    fun `the turn is not on the centreline it used to be drawn on`() {
        // The old behaviour, stated as a failure so the size of the change is
        // legible: this is a full lane to the left of where the ribbon was.
        val (p, lanes) = planOf("two-way-rabia")
        val turn = p.maneuvers.first {
            it.type == "turn-left" && it.forwardLanes == 2 && it.lanes.isEmpty()
        }
        val l = lanes.lateralAt(turn.cumulativeM - 5.0)
        assertTrue(
            "the left turn is still on the carriageway centre at ${l.offsetM} m",
            Math.abs(l.offsetM) > 1.0,
        )
    }

    @Test
    fun `a through maneuver on the same route keeps the carriageway centre`() {
        // The rule must not reach throughs, or every route in the country moves.
        // This route's `continue` at 2 forward lanes and 2 approach lanes is a
        // one-way carriageway, whose centre is exactly where it should be.
        val (p, lanes) = planOf("two-way-rabia")
        val through = p.maneuvers.first {
            it.type == "continue" && it.forwardLanes == 2 && it.approachLanes == 2
        }
        val l = lanes.lateralAt(through.cumulativeM - 5.0)
        assertEquals(RouteLanes.Basis.CARRIAGEWAY, l.basis)
        assertEquals(0.0, l.offsetM, 0.01)
    }

    @Test
    fun `a signed turn lane is never placed by the rule`() {
        // `slip-split-corniche` signs its slip from the OUTSIDE lane of four —
        // `through|through|through|through;slight_right`, its 4th step. Where a
        // road states its lanes, the rule must not be the reason for the
        // placement: the sign is evidence about THIS road and the rule is only a
        // statement about traffic law.
        //
        // The assertion is on the basis rather than on the offset because this
        // fixture's wire carries no `forward_lanes` on that step, so the
        // carriageway itself cannot be placed and the model correctly declines
        // to UNKNOWN — it does not fall through to the rule.
        val (p, lanes) = planOf("slip-split-corniche")
        val turn = p.maneuvers.first { it.type == "slight-right" && it.lanes.isNotEmpty() }
        assertEquals(RouteLanes.Turn.RIGHT, RouteLanes.Turn.of(turn.type))
        val l = lanes.lateralAt(turn.cumulativeM - 5.0)
        assertNotEquals(RouteLanes.Basis.OUTER_LANE, l.basis)
    }

    @Test
    fun `the profile only moves where a turn needs it`() {
        // A sanity bound on the whole route: no placement may leave the road it
        // is on. Two lanes each way is 14 m of asphalt, so anything beyond 7 m
        // is off the carriageway, and the rule cannot produce it.
        val (p, lanes) = planOf("two-way-rabia")
        for (m in 0..(p.distanceM.toInt()) step 25) {
            assertTrue(
                "the ribbon is ${lanes.offsetAt(m.toDouble())} m off centre at ${m} m",
                Math.abs(lanes.offsetAt(m.toDouble())) <= 7.0,
            )
        }
    }
}
