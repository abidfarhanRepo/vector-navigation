package dev.vector.android

import dev.vector.geo.RouteGeometry
import dev.vector.geo.walk.NavMode
import dev.vector.geo.walk.WalkEventStatus
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkProgressTracker
import dev.vector.geo.walk.WalkRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walking state model, driven over REAL Qatar walks (V7.4 4C.1).
 *
 * `WalkProgressTest` in core-geo states the rules on synthetic geometry where
 * every distance is exact. This file is the evidence those rules survive
 * contact with OSM: the same model, walked end to end over the six captured
 * `/foot` payloads from the real 260912 bake — 53 m to 25.5 km, 182 maneuvers,
 * 18 crossings, one staircase, one barrier detour.
 *
 * The categories are the ones a walking UX actually faces, and each one is
 * here because it breaks a different assumption:
 *
 *  - **ordinary** — the plain case.
 *  - **crossing-heavy** — 3 crossings, spans of 49/71/88 m.
 *  - **stairs** — 95 maneuvers, 15 crossings, a 5.9 m staircase, and a
 *    `depart` and `cross` that share position 0.0.
 *  - **barrier** — a locked gate forces a 2.5 km walk, detour ratio 38.9.
 *  - **short** — 52.9 m, and a plan with nothing in it but depart and arrive.
 *  - **long** — 22.4 km, 60 maneuvers, no crossings at all.
 */
class WalkRealQatarTest {

    private fun contract(name: String) = WalkContractParser.parse(
        JSONObject(
            javaClass.getResourceAsStream("/vector-contract/$name")
                ?.readBytes()?.decodeToString()
                ?: throw AssertionError("missing contract snapshot $name")
        ).getJSONArray("features").getJSONObject(0)
    )

    private fun route(name: String) = WalkRoute.of(contract(name))

    /** Walk the whole route in [stepM] increments, collecting every state. */
    private fun walkThrough(route: WalkRoute, stepM: Double = 5.0):
        List<dev.vector.geo.walk.WalkProgress> {
        val tracker = WalkProgressTracker(route)
        val out = ArrayList<dev.vector.geo.walk.WalkProgress>()
        var d = 0.0
        while (d <= route.totalM) {
            out.add(tracker.update(d))
            d += stepM
        }
        out.add(tracker.update(route.totalM))
        return out
    }

    // ------------------------------------------------ the contract is real

    @Test
    fun `the adapter consumes the real backend geometry, not an idealised one`() {
        // The whole point of the fixture set: `distance_m` on a maneuver IS
        // along-route distance on the served geometry. Measured here against
        // the cumulative index the client builds from the served coordinates.
        for (name in WalkContractParseTest.FIXTURES) {
            val c = contract(name)
            val index = RouteGeometry.index(c.geometry)!!
            for (m in c.plan) {
                assertTrue("$name: maneuver index ${m.index} outside geometry",
                    m.index < c.geometry.size)
                val cum = index.cum[m.index]
                assertEquals(
                    "$name: ${m.kind} at index ${m.index} disagrees with distance_m",
                    m.distanceM, cum, 0.5,
                )
            }
            // And the route length the client measures matches the one the
            // backend reported.
            assertEquals("$name: route length", c.distanceM!!, index.totalM, 1.0)
        }
    }

    @Test
    fun `crossing spans line up with the geometry the backend sent`() {
        for (name in listOf("foot-crossing-4b4.json", "foot-stairs-4b4.json")) {
            val c = contract(name)
            val index = RouteGeometry.index(c.geometry)!!
            val crossings = c.plan.filter { it.kind == WalkManeuverKind.CROSS }
            assertTrue(name, crossings.isNotEmpty())
            for (x in crossings) {
                val cr = x.crossing!!
                val span = index.cum[cr.leaveIndex!!] - index.cum[cr.enterIndex!!]
                assertEquals(
                    "$name: crossing span disagrees with the geometry",
                    cr.distanceM!!, span, 0.5,
                )
                // The span the model uses for ACTIVE is the sourced one.
                assertEquals(cr.distanceM!!, x.spanM, 1e-9)
            }
        }
    }

    // ------------------------------------------------------- progression

    @Test
    fun `every real walk progresses depart to arrive without skipping a maneuver`() {
        for (name in WalkContractParseTest.FIXTURES) {
            val route = route(name)
            val states = walkThrough(route, stepM = 2.0)

            // The plan is offered in order, and no entry is offered twice.
            val offered = ArrayList<Int>()
            for (s in states) {
                val i = s.next?.planIndex ?: continue
                if (offered.lastOrNull() != i) offered.add(i)
            }
            assertEquals("$name: maneuvers offered out of order",
                offered.sorted(), offered)
            assertEquals("$name: an entry was offered twice",
                offered.distinct(), offered)

            // No maneuver silently vanishes: each is observed either as the
            // NEXT event or as the ACTIVE one.
            //
            // "Every maneuver is offered as next" is the obvious assertion
            // and it is WRONG, which the real stairs walk proves twice over:
            //
            //  1. that walk BEGINS inside a 156.7 m crossing, so its `depart`
            //     and its `cross` both sit at 0.0 m. The crossing is ACTIVE
            //     from the first step and is never AHEAD, because the walker
            //     is already on it.
            //  2. the `depart` co-located with it is then SHADOWED: at 0.0 m
            //     both are active, the documented tie-break gives a span
            //     precedence over a point event, and being on a crossing is
            //     what the walker is actually doing.
            //
            // So a point maneuver sharing a position with a span is excluded
            // here. Demanding it be observed would mean inventing a moment
            // that never happened — exactly the fabrication this stage exists
            // to refuse.
            val active = states.mapNotNull { it.active?.planIndex }.toSet()
            val observed = (offered + active).toSet()
            val shadowed = route.plan.indices.filter { i ->
                val m = route.plan[i]
                m.spanM == 0.0 && route.plan.withIndex().any { (j, n) ->
                    j != i && n.spanM > 0.0 && n.distanceM == m.distanceM
                }
            }.toSet()
            assertEquals(
                "$name: a maneuver was never observed at all",
                route.plan.indices.toSet() - shadowed, observed - shadowed,
            )
        }
    }

    @Test
    fun `route progress runs monotonically from zero to the full length`() {
        for (name in WalkContractParseTest.FIXTURES) {
            val route = route(name)
            val states = walkThrough(route)
            assertEquals("$name: starts at zero", 0.0, states.first().traversedM, 1e-9)
            assertEquals("$name: ends at the end", 0.0, states.last().remainingM, 1.0)
            assertEquals("$name: fraction completes", 1.0, states.last().fraction, 0.01)
            for ((a, b) in states.zipWithNext()) {
                assertTrue("$name: traversed went backwards", b.traversedM >= a.traversedM)
                assertTrue("$name: remaining grew", b.remainingM <= a.remainingM + 1e-6)
                assertEquals("$name: distances must close",
                    route.totalM, a.traversedM + a.remainingM, 1.0)
            }
        }
    }

    @Test
    fun `the walker is placed on the route at every step of every walk`() {
        for (name in WalkContractParseTest.FIXTURES) {
            val route = route(name)
            for (s in walkThrough(route, stepM = 25.0)) {
                assertNotNull("$name: no position at ${s.alongM} m", s.position)
                assertNotNull("$name: no bearing at ${s.alongM} m", s.bearingDeg)
                assertNotNull("$name: no segment at ${s.alongM} m", s.segmentIndex)
            }
        }
    }

    // ------------------------------------------------------------- spans

    @Test
    fun `a real crossing is reported active for its whole span and no longer`() {
        val route = route("foot-crossing-4b4.json")
        // The first crossing: 49.1 m of traffic-signal crossing at 14.9 m in.
        val cross = route.plan.first { it.kind == WalkManeuverKind.CROSS }
        assertEquals(14.9, cross.distanceM, 0.05)
        assertEquals(49.1, cross.spanM, 0.05)

        assertEquals(WalkEventStatus.AHEAD, route.statusOf(cross, 10.0))
        assertEquals(WalkEventStatus.ACTIVE, route.statusOf(cross, 15.0))
        assertEquals(WalkEventStatus.ACTIVE, route.statusOf(cross, 40.0))
        assertEquals(WalkEventStatus.ACTIVE, route.statusOf(cross, 63.9))
        assertEquals(WalkEventStatus.PASSED, route.statusOf(cross, 70.0))

        // And through the tracker, the walker is told they are on it
        // continuously for ~36 seconds of walking at 1.35 m/s.
        val tracker = WalkProgressTracker(route)
        tracker.update(10.0)
        var onCrossingSamples = 0
        var d = 15.0
        while (d <= 63.0) {
            if (tracker.update(d).onCrossing) onCrossingSamples++
            d += 1.0
        }
        assertEquals(49, onCrossingSamples)
    }

    @Test
    fun `the real staircase is reported as stairs while it is underfoot`() {
        val route = route("foot-stairs-4b4.json")
        val stairs = route.plan.single { it.kind == WalkManeuverKind.STAIRS }
        assertEquals(25425.4, stairs.distanceM, 0.05)
        assertEquals(5.9, stairs.spanM, 0.05)

        val tracker = WalkProgressTracker(route)
        tracker.update(25420.0)
        val on = tracker.update(25428.0)
        assertTrue(on.onStairs)
        assertFalse(on.onCrossing)
        assertEquals(WalkManeuverKind.STAIRS, on.active?.kind)
        // Nothing about this staircase is known beyond its length.
        assertNull(on.active!!.maneuver.stairs!!.stepCount)
        assertNull(on.active!!.maneuver.stairs!!.handrail)
    }

    @Test
    fun `a depart and a crossing sharing position zero resolve to the crossing`() {
        // The real stairs walk begins ON a 156.7 m crossing: both the depart
        // and the cross sit at 0.0 m.
        val route = route("foot-stairs-4b4.json")
        assertEquals(WalkManeuverKind.DEPART, route.plan[0].kind)
        assertEquals(WalkManeuverKind.CROSS, route.plan[1].kind)
        assertEquals(0.0, route.plan[0].distanceM, 1e-9)
        assertEquals(0.0, route.plan[1].distanceM, 1e-9)

        val start = route.progressAt(0.0)
        // Being ON a crossing is what the walker is doing; the depart is not.
        assertEquals(WalkManeuverKind.CROSS, start.active?.kind)
        assertTrue(start.onCrossing)
    }

    @Test
    fun `closely spaced real maneuvers are each reported`() {
        // The ordinary walk ends uturn (579.1 m) then arrive (583.2 m): 4.1 m
        // apart, the tightest real gap in the fixture set outside a span.
        val route = route("foot-ordinary-4b4.json")
        val tracker = WalkProgressTracker(route)
        val seen = LinkedHashSet<WalkManeuverKind>()
        var d = 560.0
        while (d <= 583.2) {
            tracker.update(d).next?.kind?.let(seen::add)
            d += 0.5
        }
        assertEquals(
            listOf(WalkManeuverKind.UTURN, WalkManeuverKind.ARRIVE),
            seen.toList(),
        )
    }

    // -------------------------------------------------------- degenerate

    @Test
    fun `the shortest real walk has nothing to say and says nothing`() {
        val route = route("foot-short-4b4.json")
        assertEquals(52.9, route.totalM, 0.5)
        assertEquals(2, route.plan.size)
        assertFalse(route.degenerate)

        val start = route.progressAt(0.0)
        assertEquals(WalkManeuverKind.DEPART, start.active?.kind)
        assertEquals(WalkManeuverKind.ARRIVE, start.next?.kind)
        assertEquals(52.9, start.distanceToNextM!!, 0.5)
        // No turn is invented to fill the gap.
        assertTrue(route.plan.none { it.kind.isTurn })
    }

    @Test
    fun `the barrier detour is walked as the honest long route it is`() {
        // 4A.3 case A: a locked gate 64 m away forces a 2.5 km walk. The model
        // must present it as an ordinary walk — the refusal already happened
        // in the graph — while the diagnostics keep the reason visible.
        val route = route("foot-barrier-4b4.json")
        assertEquals(2504.2, route.totalM, 1.0)
        assertEquals(38.9, route.contract.diagnostics.detourRatio!!, 0.05)
        val states = walkThrough(route, stepM = 50.0)
        assertEquals(WalkManeuverKind.ARRIVE, states.last().active?.kind)
        assertEquals(0.0, states.last().remainingM, 1.0)
    }

    @Test
    fun `the longest real walk progresses through sixty maneuvers in order`() {
        val route = route("foot-long-4b4.json")
        assertEquals(60, route.plan.size)
        assertEquals(22371.2, route.totalM, 2.0)
        // No crossings at all on this one: nothing may invent one.
        assertTrue(route.plan.none { it.kind == WalkManeuverKind.CROSS })
        assertEquals(0.0, route.contract.crossingM!!, 1e-9)

        val states = walkThrough(route, stepM = 10.0)
        assertEquals(WalkManeuverKind.ARRIVE, states.last().active?.kind)
    }

    // --------------------------------------------------------- the roads

    @Test
    fun `the crossed road never leaks into the road being walked on`() {
        // On the real crossing walk, all three crossings name a road via
        // `road_graph_shared_node`. None of those names may be presented as
        // the way the walker is on.
        val route = route("foot-crossing-4b4.json")
        val crossedNames = route.plan
            .filter { it.kind == WalkManeuverKind.CROSS }
            .mapNotNull { it.crossing?.road?.nameEn }
            .toSet()
        assertTrue(crossedNames.isNotEmpty())

        val tracker = WalkProgressTracker(route)
        var d = 0.0
        while (d <= route.totalM) {
            val p = tracker.update(d)
            if (p.onCrossing) {
                // While ON a crossing, the current road is whatever the walk
                // last established — never the arterial being stepped over.
                val road = p.currentRoad
                if (road?.nameEn != null) {
                    assertFalse(
                        "the crossed road leaked into currentRoad at $d m",
                        p.active!!.maneuver.crossing!!.road?.nameEn == road.nameEn &&
                            p.active!!.maneuver.crossing!!.road?.highway != road.highway,
                    )
                }
            }
            d += 5.0
        }
    }

    @Test
    fun `an unknown road stays unknown for the whole of every real walk`() {
        for (name in WalkContractParseTest.FIXTURES) {
            val route = route(name)
            for (s in walkThrough(route, stepM = 25.0)) {
                val road = s.currentRoad ?: continue
                // Whatever is reported must be a real identity from the plan,
                // never a fabricated placeholder.
                assertTrue(
                    "$name: currentRoad at ${s.alongM} m is not from the plan",
                    route.plan.any { it.road == road && it.kind != WalkManeuverKind.CROSS },
                )
            }
        }
    }

    @Test
    fun `the segment class under the walker is a fact, separate from the road`() {
        val route = route("foot-crossing-4b4.json")
        val classes = route.contract.segments.classes
        for (s in walkThrough(route, stepM = 20.0)) {
            val i = s.segmentIndex!!
            assertEquals(
                "segment class must come straight from the aligned array",
                classes.getOrNull(i), s.currentSegmentClass,
            )
        }
    }

    // ------------------------------------------------------- determinism

    @Test
    fun `identical inputs produce identical state on every real walk`() {
        for (name in WalkContractParseTest.FIXTURES) {
            val a = route(name)
            val b = route(name)
            // Two independently parsed copies of the same payload, walked the
            // same way, must agree at every step.
            val walkA = walkThrough(a, stepM = 37.0).map { it.copy(route = a) }
            val walkB = walkThrough(b, stepM = 37.0).map { it.copy(route = a) }
            assertEquals("$name is not deterministic", walkA.size, walkB.size)
            for (i in walkA.indices) {
                assertEquals("$name diverged at step $i", walkA[i], walkB[i])
            }
        }
    }

    @Test
    fun `GPS jitter on a real crossing threshold cannot re-offer the crossing`() {
        val route = route("foot-crossing-4b4.json")
        val tracker = WalkProgressTracker(route)
        // Approach and pass the second crossing (715.6 m, 70.9 m span).
        tracker.update(700.0)
        tracker.update(716.0)
        tracker.update(790.0)
        val afterKind = tracker.update(790.0).next?.kind
        // Jitter back and forth over the crossing's leave point.
        for (d in listOf(784.0, 792.0, 786.5, 789.0, 783.0)) {
            assertEquals(
                "jitter at $d re-offered a passed crossing",
                afterKind, tracker.update(d).next?.kind,
            )
        }
    }

    // -------------------------------------------------------------- mode

    @Test
    fun `every real walking state is foot`() {
        for (name in WalkContractParseTest.FIXTURES) {
            for (s in walkThrough(route(name), stepM = 100.0)) {
                assertEquals(NavMode.FOOT, s.mode)
            }
        }
    }
}
