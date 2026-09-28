package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The walking state model (V7.4 4C.1).
 *
 * These are the rules a walking camera, puck, banner or voice will be built
 * on in 4C.2, asserted here where they are cheap to assert. Nothing in this
 * file tests wording, because 4C.1 produces none.
 *
 * The synthetic routes below are straight lines along a meridian, where a
 * degree of latitude is exactly [RouteGeometry.M_PER_DEG_LAT] metres, so a
 * vertex can be placed at an exact along-route distance and the cumulative
 * index agrees with the maneuver positions to the millimetre. The REAL
 * geometry/plan pairs are exercised against the captured Qatar payloads in
 * the app module's `WalkRealQatarTest` — this file is the rule set, that one
 * is the evidence it survives contact with OSM.
 */
class WalkProgressTest {

    // ---------------------------------------------------------------- helpers

    /** A straight north-bound route of [lengthM], sampled every [stepM]. */
    private fun straight(lengthM: Double, stepM: Double = 10.0): List<LngLat> {
        val pts = ArrayList<LngLat>()
        var d = 0.0
        while (d < lengthM) {
            pts.add(LngLat(0.0, d / RouteGeometry.M_PER_DEG_LAT))
            d += stepM
        }
        pts.add(LngLat(0.0, lengthM / RouteGeometry.M_PER_DEG_LAT))
        return pts
    }

    private fun maneuver(
        kind: WalkManeuverKind,
        atM: Double,
        road: WalkRoadId? = null,
        crossing: WalkCrossing? = null,
        stairs: WalkStairs? = null,
    ) = WalkManeuver(
        kind = kind,
        index = (atM / 10.0).toInt(),
        distanceM = atM,
        distanceToNextM = null,
        road = road,
        crossing = crossing,
        stairs = stairs,
    )

    private fun crossingAt(
        atM: Double,
        spanM: Double,
        road: WalkRoadId? = null,
        source: String? = null,
        type: String? = null,
        typeSource: String? = null,
    ) = maneuver(
        WalkManeuverKind.CROSS, atM, road = road,
        crossing = WalkCrossing(
            type = type, typeSource = typeSource, markings = null, kerb = null,
            tactilePaving = null, distanceM = spanM, distanceToCrossingM = null,
            approachIndex = null, approachDistanceM = null,
            enterIndex = null, leaveIndex = null,
            road = road, crossedRoadSource = source,
        ),
    )

    private fun contract(
        lengthM: Double,
        plan: List<WalkManeuver>,
        geometry: List<LngLat> = straight(lengthM),
        durationS: Double? = lengthM / 1.35,
        cost: WalkCost? = null,
    ) = WalkContract(
        contractVersion = 1,
        walkingProfile = WalkingProfile.GENERAL,
        walkingProfileRaw = "general",
        mode = "foot",
        distanceM = lengthM,
        durationS = durationS,
        stepsM = 0.0,
        crossingM = 0.0,
        nodes = geometry.size,
        geometry = geometry,
        segments = WalkSegments(),
        facts = emptyList(),
        plan = plan,
        cost = cost,
        diagnostics = WalkDiagnostics(),
    )

    /** A plain 400 m walk: depart, a left at 100, a right at 250, arrive. */
    private fun ordinaryRoute(): WalkRoute = WalkRoute.of(
        contract(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0, WalkRoadId(highway = "footway")),
                maneuver(WalkManeuverKind.TURN_LEFT, 100.0, WalkRoadId(highway = "residential", name = "A")),
                maneuver(WalkManeuverKind.TURN_RIGHT, 250.0, WalkRoadId(highway = "footway", name = "B")),
                maneuver(WalkManeuverKind.ARRIVE, 400.0, WalkRoadId(highway = "footway", name = "B")),
            ),
        )
    )

    // ------------------------------------------------------------ progression

    @Test
    fun `route start reports depart active and the first turn next`() {
        val p = ordinaryRoute().progressAt(0.0)
        assertEquals(WalkManeuverKind.DEPART, p.active?.kind)
        assertEquals(WalkManeuverKind.TURN_LEFT, p.next?.kind)
        assertEquals(100.0, p.distanceToNextM!!, 0.01)
        assertEquals(0.0, p.traversedM, 1e-9)
        assertEquals(400.0, p.remainingM, 0.5)
        assertEquals(0, p.passedCount)
        assertEquals(NavMode.FOOT, p.mode)
    }

    @Test
    fun `the next maneuver advances in order as the walk proceeds`() {
        val route = ordinaryRoute()
        assertEquals(WalkManeuverKind.TURN_LEFT, route.progressAt(50.0).next?.kind)
        assertEquals(WalkManeuverKind.TURN_RIGHT, route.progressAt(150.0).next?.kind)
        assertEquals(WalkManeuverKind.ARRIVE, route.progressAt(300.0).next?.kind)
    }

    @Test
    fun `distance to the next maneuver is the backend position minus the walker`() {
        val p = ordinaryRoute().progressAt(60.0)
        assertEquals(40.0, p.distanceToNextM!!, 0.01)
    }

    @Test
    fun `a passed maneuver is counted as passed and is not offered as next`() {
        val p = ordinaryRoute().progressAt(150.0)
        assertEquals(2, p.passedCount) // depart + the left
        assertEquals(WalkManeuverKind.TURN_RIGHT, p.next?.kind)
    }

    @Test
    fun `every plan kind is progressed through, in route order`() {
        // One of each of the ten documented kinds, 50 m apart.
        val kinds = WalkManeuverKind.entries
        val plan = kinds.mapIndexed { i, k ->
            when (k) {
                WalkManeuverKind.CROSS -> crossingAt(i * 50.0, 8.0)
                WalkManeuverKind.STAIRS ->
                    maneuver(k, i * 50.0, stairs = WalkStairs(distanceM = 6.0))
                else -> maneuver(k, i * 50.0)
            }
        }
        val route = WalkRoute.of(contract(kinds.size * 50.0, plan))
        val seen = ArrayList<WalkManeuverKind>()
        var d = 0.0
        while (d <= kinds.size * 50.0) {
            route.progressAt(d).next?.kind?.let { if (seen.lastOrNull() != it) seen.add(it) }
            d += 1.0
        }
        // Every kind after the first appears exactly once, in plan order.
        assertEquals(kinds.drop(1), seen)
    }

    // ------------------------------------------------------------------ spans

    @Test
    fun `a crossing is ahead, then active for its whole span, then passed`() {
        val cross = crossingAt(100.0, 49.1)
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    cross,
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                ),
            )
        )
        assertEquals(WalkEventStatus.AHEAD, route.statusOf(cross, 50.0))
        // The 49.1 m span is the real median crossing length on the Qatar
        // fixtures; a walker is inside it for most of a minute.
        assertEquals(WalkEventStatus.ACTIVE, route.statusOf(cross, 100.0))
        assertEquals(WalkEventStatus.ACTIVE, route.statusOf(cross, 130.0))
        assertEquals(WalkEventStatus.ACTIVE, route.statusOf(cross, 149.0))
        assertEquals(WalkEventStatus.PASSED, route.statusOf(cross, 150.0))

        val mid = route.progressAt(120.0)
        assertTrue(mid.onCrossing)
        assertFalse(mid.onStairs)
        assertEquals(WalkManeuverKind.CROSS, mid.active?.kind)
        assertEquals(29.1, mid.active!!.remainingSpanM, 0.01)
    }

    @Test
    fun `stairs are a span too, and their absent step facts stay absent`() {
        val stairs = maneuver(
            WalkManeuverKind.STAIRS, 100.0,
            stairs = WalkStairs(distanceM = 5.9), // the real fixture's stairs run
        )
        val route = WalkRoute.of(
            contract(200.0, listOf(maneuver(WalkManeuverKind.DEPART, 0.0), stairs,
                maneuver(WalkManeuverKind.ARRIVE, 200.0)))
        )
        val on = route.progressAt(103.0)
        assertTrue(on.onStairs)
        assertEquals(WalkManeuverKind.STAIRS, on.active?.kind)
        // No invented step count, handrail or incline.
        assertNull(on.active!!.maneuver.stairs!!.stepCount)
        assertNull(on.active!!.maneuver.stairs!!.handrail)
        assertNull(on.active!!.maneuver.stairs!!.incline)
    }

    @Test
    fun `a span beats a point event at the same position`() {
        // The real stairs fixture opens with a depart and a cross BOTH at 0.0.
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    crossingAt(0.0, 156.7),
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                ),
            )
        )
        assertEquals(WalkManeuverKind.CROSS, route.progressAt(0.0).active?.kind)
    }

    // --------------------------------------------------- closely spaced / edge

    @Test
    fun `closely spaced maneuvers are each reported, none skipped`() {
        // 4.1 m apart: the real ordinary fixture's uturn-then-arrive gap.
        val route = WalkRoute.of(
            contract(
                100.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    maneuver(WalkManeuverKind.UTURN, 95.9),
                    maneuver(WalkManeuverKind.ARRIVE, 100.0),
                ),
            )
        )
        assertEquals(WalkManeuverKind.UTURN, route.progressAt(90.0).next?.kind)
        assertEquals(WalkManeuverKind.ARRIVE, route.progressAt(97.0).next?.kind)
    }

    @Test
    fun `a zero-length walk is a coherent state, not a crash`() {
        val route = WalkRoute.of(contract(0.0, emptyList(), geometry = emptyList(),
            durationS = 0.0))
        assertTrue(route.degenerate)
        val p = route.progressAt(0.0)
        assertEquals(0.0, p.alongM, 1e-9)
        assertEquals(0.0, p.remainingM, 1e-9)
        assertEquals(0.0, p.fraction, 1e-9)
        assertNull(p.position)
        assertNull(p.next)
        assertNull(p.active)
    }

    @Test
    fun `a walk with no meaningful turn still reports depart and arrive`() {
        // The real short fixture: 52.9 m, plan = depart, arrive. Nothing to say.
        val route = WalkRoute.of(
            contract(
                52.9,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0, WalkRoadId(highway = "residential")),
                    maneuver(WalkManeuverKind.ARRIVE, 52.9, WalkRoadId(highway = "service")),
                ),
            )
        )
        assertEquals(WalkManeuverKind.ARRIVE, route.progressAt(0.0).next?.kind)
        assertEquals(2, route.plan.size)
    }

    @Test
    fun `arrival leaves nothing ahead and no distance remaining`() {
        val p = ordinaryRoute().progressAt(400.0)
        assertEquals(0.0, p.remainingM, 0.5)
        assertEquals(1.0, p.fraction, 0.01)
        assertNull(p.next)
        assertEquals(WalkManeuverKind.ARRIVE, p.active?.kind)
    }

    @Test
    fun `a position past the end clamps rather than running off the route`() {
        val p = ordinaryRoute().progressAt(10_000.0)
        assertTrue(p.alongM <= 400.5)
        assertEquals(0.0, p.remainingM, 0.5)
    }

    // ------------------------------------------------------- roads & unknowns

    @Test
    fun `the crossed road of a crossing never becomes the road walked on`() {
        // The most dangerous misread available in this contract: a crossing's
        // `road` is the road being CROSSED, not the one being walked along.
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0, WalkRoadId(highway = "footway")),
                    crossingAt(
                        100.0, 20.0,
                        road = WalkRoadId(highway = "primary", name = "Jasim Bin Hamad Street"),
                        source = "road_graph_shared_node",
                    ),
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                ),
            )
        )
        val on = route.progressAt(110.0)
        assertEquals(WalkManeuverKind.CROSS, on.active?.kind)
        // Walking ON the crossing, the road identity is still the footway we
        // departed on — never the arterial being stepped over.
        assertEquals("footway", on.currentRoad?.highway)
        assertNull(on.currentRoad?.name)
    }

    @Test
    fun `an unknown road stays unknown and is never backfilled from ahead`() {
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0, road = null),
                    maneuver(WalkManeuverKind.TURN_LEFT, 200.0, WalkRoadId(name = "Later Street")),
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                ),
            )
        )
        // Before the turn nothing establishes a road. The named road AHEAD
        // must not be borrowed to fill the gap.
        assertNull(route.progressAt(100.0).currentRoad)
        assertEquals("Later Street", route.progressAt(250.0).currentRoad?.name)
    }

    @Test
    fun `a crossing with a null road reports its road as not known`() {
        val cross = crossingAt(100.0, 20.0, road = null, source = null, typeSource = "catalog_node")
        val route = WalkRoute.of(
            contract(200.0, listOf(maneuver(WalkManeuverKind.DEPART, 0.0), cross,
                maneuver(WalkManeuverKind.ARRIVE, 200.0)))
        )
        val next = route.progressAt(50.0).next!!
        assertFalse(next.roadKnown)
        assertNull(next.maneuver.crossing!!.road)
        assertNull(next.maneuver.crossing!!.crossedRoadSource)
        // An untyped crossing is still a crossing.
        assertEquals("catalog_node", next.maneuver.crossing!!.typeSource)
        assertNull(next.maneuver.crossing!!.type)
    }

    @Test
    fun `a crossing with an attributed road reports it as known`() {
        val cross = crossingAt(
            100.0, 20.0,
            road = WalkRoadId(highway = "residential", nameEn = "Jasim Bin Hamad Street"),
            source = "road_graph_shared_node", type = "traffic_signals", typeSource = "catalog",
        )
        val route = WalkRoute.of(
            contract(200.0, listOf(maneuver(WalkManeuverKind.DEPART, 0.0), cross,
                maneuver(WalkManeuverKind.ARRIVE, 200.0)))
        )
        assertTrue(route.progressAt(50.0).next!!.roadKnown)
    }

    // ------------------------------------------------------------ the tracker

    @Test
    fun `GPS jitter around a maneuver cannot un-pass it`() {
        val route = ordinaryRoute()
        val tracker = WalkProgressTracker(route)
        // Walk up to the 100 m left turn and past it. 98 m, not 99: within
        // `LOOK_BACK_M` of a maneuver it is ACTIVE rather than AHEAD — the
        // walker is AT the turn — which is the same 1 m margin the car path's
        // `upcomingManeuver` has always used.
        tracker.update(95.0)
        assertEquals(WalkManeuverKind.TURN_LEFT, tracker.update(98.0).next?.kind)
        assertEquals(WalkManeuverKind.TURN_RIGHT, tracker.update(104.0).next?.kind)
        // Now jitter backwards across the threshold, repeatedly. Without the
        // latch the walker would be told to make the same turn again.
        for (d in listOf(99.0, 103.0, 97.5, 101.0, 98.0)) {
            assertEquals(
                WalkManeuverKind.TURN_RIGHT, tracker.update(d).next?.kind,
                "jitter at $d re-offered a passed maneuver",
            )
        }
    }

    @Test
    fun `measurements still follow the current fix while the latch holds`() {
        val tracker = WalkProgressTracker(ordinaryRoute())
        tracker.update(104.0)
        val back = tracker.update(98.0)
        // The maneuver is latched; the POSITION is not — it is a measurement.
        assertEquals(98.0, back.alongM, 0.01)
        assertEquals(302.0, back.remainingM, 0.5)
    }

    @Test
    fun `a genuine backtrack releases the latch and guides again`() {
        val tracker = WalkProgressTracker(ordinaryRoute())
        tracker.update(150.0)
        assertEquals(WalkManeuverKind.TURN_RIGHT, tracker.update(150.0).next?.kind)
        // Turned around and walked back well beyond the jitter tolerance.
        val turned = tracker.update(60.0)
        assertEquals(WalkManeuverKind.TURN_LEFT, turned.next?.kind)
    }

    @Test
    fun `the backtrack tolerance is the line between jitter and turning round`() {
        // Both walkers stand 4 m past the 100 m left turn, so the only thing
        // that decides whether they are told to make it again is the latch.
        // (Backtracking to somewhere still PAST a maneuver proves nothing —
        // the walker really has passed it, latch or no latch.)
        val route = ordinaryRoute()

        val justJitter = WalkProgressTracker(route)
        justJitter.update(104.0)
        // 9 m back, to 95 m — before the turn, but inside the tolerance. This
        // is the case the latch exists for: position says the turn is ahead,
        // history says it is done, and history wins.
        assertEquals(WalkManeuverKind.TURN_RIGHT, justJitter.update(95.0).next?.kind)

        val realBacktrack = WalkProgressTracker(route)
        realBacktrack.update(104.0)
        // 11 m back, to 93 m: outside the tolerance, so this is a walker who
        // turned around, and they are guided through the turn again.
        assertEquals(WalkManeuverKind.TURN_LEFT, realBacktrack.update(93.0).next?.kind)
    }

    @Test
    fun `the latch never drops the span the walker is standing on`() {
        // The bug this pins: latching to `next` alone would advance past an
        // ACTIVE crossing, so a walker who had just stepped onto a 49 m
        // crossing would stop being told they were on it.
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    crossingAt(100.0, 49.1),
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                ),
            )
        )
        val tracker = WalkProgressTracker(route)
        tracker.update(99.0)
        for (d in listOf(101.0, 120.0, 140.0, 148.0)) {
            val p = tracker.update(d)
            assertTrue(p.onCrossing, "lost the crossing at $d m")
        }
        assertFalse(tracker.update(160.0).onCrossing)
    }

    @Test
    fun `reset forgets the latch so a replaced route starts clean`() {
        val tracker = WalkProgressTracker(ordinaryRoute())
        tracker.update(300.0)
        assertTrue(tracker.latchedPlanIndex > 0)
        tracker.reset()
        assertEquals(0, tracker.latchedPlanIndex)
        assertEquals(WalkManeuverKind.TURN_LEFT, tracker.update(50.0).next?.kind)
    }

    // ---------------------------------------------------------- determinism

    @Test
    fun `identical route and position always produce identical state`() {
        val route = ordinaryRoute()
        for (d in listOf(0.0, 37.5, 100.0, 249.9, 400.0)) {
            assertEquals(route.progressAt(d), route.progressAt(d))
        }
        // And via the tracker, from two independently-walked histories.
        val a = WalkProgressTracker(route)
        val b = WalkProgressTracker(route)
        listOf(10.0, 50.0, 120.0, 260.0).forEach { a.update(it); b.update(it) }
        assertEquals(a.update(300.0), b.update(300.0))
    }

    @Test
    fun `a plan out of order is sorted rather than trusted`() {
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    maneuver(WalkManeuverKind.TURN_LEFT, 150.0),
                ),
            )
        )
        assertEquals(
            listOf(WalkManeuverKind.DEPART, WalkManeuverKind.TURN_LEFT, WalkManeuverKind.ARRIVE),
            route.plan.map { it.kind },
        )
    }

    // ----------------------------------------------------------------- ETA

    @Test
    fun `the three durations stay three different numbers`() {
        val c = contract(
            1848.1, emptyList(), durationS = 1369.0,
            cost = WalkCost(
                profile = "general", baseTimeS = 1368.97, paceS = 1368.97,
                costS = 1500.97, penaltyS = 132.0,
                selectionFactors = listOf("stairs", "incline", "crossing_wait"),
                factorS = mapOf("crossing_wait" to 132.0),
                crossing = WalkCrossingExposure(22, 208.1, 132.0, 0, 22),
            ),
        )
        val eta = c.eta
        // Display duration is PURE pace: it does not contain the crossing wait.
        assertEquals(1369.0, eta.displayDurationS!!, 0.01)
        assertEquals(1500.97, eta.selectionCostS!!, 0.01)
        assertEquals(132.0, eta.crossingWaitS!!, 0.01)
        assertEquals(132.0, eta.penaltyS!!, 0.01)
        assertTrue(eta.selectionCostS!! > eta.displayDurationS!!)
        assertEquals(
            eta.selectionCostS!! - eta.displayDurationS!!, eta.crossingWaitS!!, 0.1,
        )
    }

    // ---------------------------------------------------------------- mode

    @Test
    fun `walking state is foot and says so`() {
        assertEquals(NavMode.FOOT, ordinaryRoute().progressAt(10.0).mode)
        assertEquals(2, NavMode.entries.size)
    }

    @Test
    fun `general is the only walking profile this client knows`() {
        assertEquals(listOf(WalkingProfile.GENERAL), WalkingProfile.entries)
        assertEquals(WalkingProfile.GENERAL, WalkingProfile.parse("general"))
        assertEquals(WalkingProfile.GENERAL, WalkingProfile.parse(" GENERAL "))
        // No comfort, accessibility or pleasant mode exists to be selected.
        assertNull(WalkingProfile.parse("comfort"))
        assertNull(WalkingProfile.parse("accessibility"))
        assertNull(WalkingProfile.parse("pleasant"))
        assertNull(WalkingProfile.parse(null))
    }

    // -------------------------------------------------------- camera seam

    @Test
    fun `the walking camera policy now decides, and settles`() {
        // ## Why this test changed shape in 4C.2
        //
        // In 4C.1 this asserted `WalkCameraPolicy.Declining` produced
        // `Unchanged` for every position — the stub was the shipped behaviour
        // and pinning it stopped a camera drifting in unexamined. 4C.2 is the
        // stage chartered to replace that stub with a measured policy, so the
        // assertion that the policy declines is the one thing here that MUST
        // change; keeping it would be pinning the absence of this stage's
        // work.
        //
        // What replaces it is the property that survives the change: the
        // camera SETTLES. It writes when the regime changes and is silent
        // otherwise, which is the invariant that stops a walk becoming a
        // sequence of nudges. The regimes themselves are exercised in
        // WalkCameraTest.
        val route = ordinaryRoute()
        val policy = WalkCameraPolicy()
        var writes = 0
        var d = 0.0
        while (d <= 400.0) {
            val inputs = WalkCameraInputs.of(route.progressAt(d))
            if (policy.decide(inputs) is WalkCameraDecision.Transition) writes++
            d += 1.0
        }
        // 401 fixes over a 400 m walk with three maneuvers. A handful of
        // regime changes, not hundreds of writes.
        assertTrue(writes in 1..12, "camera wrote $writes times over 401 fixes")
    }

    @Test
    fun `camera inputs are derived from walking state and carry no speed`() {
        val route = WalkRoute.of(
            contract(
                300.0,
                listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    crossingAt(100.0, 49.1),
                    maneuver(WalkManeuverKind.ARRIVE, 300.0),
                ),
            )
        )
        val approaching = WalkCameraInputs.of(route.progressAt(50.0))
        assertEquals(50.0, approaching.alongM, 0.01)
        assertEquals(WalkManeuverKind.CROSS, approaching.nextKind)
        assertEquals(50.0, approaching.distanceToNextM!!, 0.01)
        assertFalse(approaching.onCrossing)
        assertNotNull(approaching.bearingDeg)

        val onIt = WalkCameraInputs.of(route.progressAt(120.0))
        assertTrue(onIt.onCrossing)
        assertFalse(onIt.onStairs)
    }

    @Test
    fun `the walking puck is drawn on the route line, never offset`() {
        // A pedestrian's position across a 2 m footway is not something GPS
        // knows, so the honest placement is the route itself — and the refusal
        // is explicit rather than incidental, so a future change to the car's
        // lane model cannot start offsetting a walker onto the carriageway.
        assertFalse(WalkPuck.wantsLateralOffset())
        assertEquals(0.0, WalkPuck.CENTRELINE_OFFSET_M, 0.0)
    }

    @Test
    fun `span and turn classification match the documented vocabulary`() {
        assertTrue(WalkManeuverKind.CROSS.isSpan)
        assertTrue(WalkManeuverKind.STAIRS.isSpan)
        assertFalse(WalkManeuverKind.TURN_LEFT.isSpan)
        assertTrue(WalkManeuverKind.UTURN.isTurn)
        assertTrue(WalkManeuverKind.SLIGHT_RIGHT.isTurn)
        assertFalse(WalkManeuverKind.CONTINUE.isTurn)
        assertFalse(WalkManeuverKind.ARRIVE.isTurn)
        assertEquals(10, WalkManeuverKind.entries.size)
        assertNull(WalkManeuverKind.from("teleport"))
        assertNull(WalkManeuverKind.from(null))
    }
}
