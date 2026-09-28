package dev.vector.android

import dev.vector.geo.DrivePath
import dev.vector.geo.DriveSimulator
import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.SimFix

/**
 * The truth paths and fix streams V5's driving scenarios are built from.
 *
 * Separated from the assertions so that the same trace can be driven by a JVM
 * scenario test AND exported for `scripts/simulate_drive.sh` to push through
 * the device's real fused-location pipeline. One definition of "scenario C" is
 * the point: a simulation result and a device result that came from different
 * traces would not be comparable, and V5 §19 requires them to be reported side
 * by side.
 *
 * Every path here is built from a route the live backend actually returned —
 * the fixtures under `app/src/test/resources/routes/`, captured from the running stack on
 * 2026-09-09. Nothing is hand-drawn. Doha's roads have shapes that a synthetic
 * polyline does not: 200 m vertex spacing on Al Corniche, slip roads that
 * parallel the carriageway for half a kilometre, a U-turn 329 m into the first
 * route the router offers. Those are the shapes that break navigators.
 */
object ScenarioTraces {

    /** Souq Waqif → West Bay. 6.6 km, 21 maneuvers, urban. */
    fun city() = DriveHarness.load("city-souq-westbay", routeId = 0)

    /** The same origin and destination by a different road — a real fork. */
    fun cityAlternative() = DriveHarness.load("city-souq-westbay", routeId = 2)

    /** Al Wakrah → Doha. 16.5 km including expressway. */
    fun highway() = DriveHarness.load("highway-wakrah-doha", routeId = 0)

    /**
     * The C Ring / Al Corniche slip split — the ONE route in the Doha corpus
     * whose approach carries a lane string that actually narrows the choice
     * (`through|through|through|through;slight_right`, 4 lanes, 4th lit).
     *
     * Captured from the live stack over the V7 acceptance pair
     * `25.2737925,51.5425643 -> 25.285395,51.530978` (2 088 m) so the Stage 3
     * device run and the JVM suite drive the identical geometry.
     */
    fun slipSplit() = DriveHarness.load("slip-split-corniche", routeId = 0)

    /** A short dense-urban hop through Msheireb. */
    fun dense() = DriveHarness.load("dense-msheireb", routeId = 0)

    /**
     * Al Rabiya → Al Jood, through the Madinat Khalifa residential grid.
     * 3.2 km, 12 maneuvers, and the ONLY fixture that exercises V7 Stage 4's
     * central case.
     *
     * Four of its steps are `approach_lanes=2, forward_lanes=1` — a two-way
     * street with ONE lane each way, which is 5,728 ways in Qatar and the
     * shape on which centring the route ribbon on the way centreline draws it
     * straight through the oncoming lane.
     *
     * ## Why this is a new capture rather than a re-capture
     *
     * The eight fixtures above predate `lane_data.forward_lanes` — they were
     * taken on 2026-09-09, and the field landed in `c684a36`. Every one of
     * them therefore reads as "this backend does not know", which is a real
     * and well-covered case (the model declines, the route stays on the
     * centreline, nothing changes) but is not the case Stage 4 exists for.
     *
     * Re-capturing them was the obvious alternative and is the wrong one. The
     * geometry still reproduces exactly on three of the eight, but `4e933b6`
     * has since calibrated the driving ETA against 18 real drives — free-flow
     * durations are ~15% higher than the fixtures record — and the graph has
     * been re-baked, which moves shape points on five of them. Refreshing them
     * wholesale would quietly move numbers that Stage 1-3's camera and
     * maneuver assertions were tuned against, and "the fixtures changed" is not
     * a finding anyone can act on.
     *
     * Captured with `scripts/capture_route_fixture.py`, which is itself new:
     * there was no recorded way to make one of these until now.
     */
    fun twoWay() = DriveHarness.load("two-way-rabia", routeId = 0)

    /**
     * Al Eithar → Al Intisar, through two roundabouts. 773 m, 5 maneuvers.
     *
     * Here to prove a REFUSAL as much as a claim. Its two `roundabout` steps
     * describe their approaches (`approach_lanes` 1 and 2) and OSM records no
     * lane connectivity through the ring itself — so the lateral model must
     * place the approach and claim nothing about which ring lane leads to
     * which exit. A navigator that guessed there would be guessing on the one
     * junction type where being in the wrong lane is hardest to correct.
     *
     * It also carries one `approach_lanes=2, forward_lanes=1` step, so the
     * two-way correction is exercised on the way in.
     */
    fun roundabout() = DriveHarness.load("roundabout-saba", routeId = 0)

    /** Hamad Airport → Education City. 27 km, 28 maneuvers, for the long run. */
    fun long() = DriveHarness.load("long-airport-educity", routeId = 0)

    /**
     * The routes the LIVE router answers with from each scenario's deviation.
     *
     * Captured by computing where the deviation actually crosses the off-route
     * threshold and asking `/navigate` from that coordinate — not from the end
     * of the wrong road, which is where the first attempt captured them and is
     * 258 m from where Vector asks. A fixture that answers a different question
     * from the one the app asks is worse than no fixture, because the test
     * still passes.
     */
    fun missedTurnReroute() = DriveHarness.load("reroute-missed-turn", routeId = 0)
    fun wrongRoadReroute() = DriveHarness.load("reroute-wrong-road", routeId = 0)
    fun longRunReroute() = DriveHarness.load("reroute-long-run", routeId = 0)

    /**
     * Miss a manoeuvre and then drive the route the router offers instead.
     *
     * A deviation trace that simply stops 400 m down the wrong road tests
     * detection and nothing after it. The interesting half is what happens
     * once the new route arrives: does the tracker lock onto it, do its
     * maneuvers advance, does the journey still end in an arrival. So the truth
     * path continues onto the rerouted geometry, joined at the point on it
     * nearest where the driver had got to.
     */
    fun deviateThenFollow(
        route: List<LngLat>,
        atAlongM: Double,
        straightM: Double,
        reroute: List<LngLat>,
    ): List<LngLat> {
        val dev = straightOnPast(route, atAlongM, straightM)
        return DrivePath.forkTo(dev, DrivePath.lengthM(dev), reroute)
    }

    // ---- deviation geometry -------------------------------------------------

    /**
     * Drive to [atAlongM] and then carry straight on for [forM] instead.
     *
     * A missed turn is not a decision to go somewhere else, it is the absence
     * of a decision: the driver keeps doing what they were already doing. So
     * the continuation takes the bearing of the road *approaching* the
     * maneuver, sampled 40 m back so the sample is on the approach rather than
     * inside the junction geometry.
     */
    fun straightOnPast(route: List<LngLat>, atAlongM: Double, forM: Double): List<LngLat> {
        val idx = RouteGeometry.index(route)!!
        val approach = idx.pointAt((atAlongM - 40.0).coerceAtLeast(0.0))!!.bearing
        val from = idx.pointAt(atAlongM)!!.position
        val onward = ArrayList<LngLat>()
        var d = 20.0
        while (d <= forM) {
            onward.add(DrivePath.offset(from, approach, d))
            d += 20.0
        }
        return DrivePath.divert(route, atAlongM, onward)
    }

    /**
     * The along-route distance of the first maneuver of [types] past [afterM].
     *
     * Scenarios name the manoeuvre they want to miss rather than a distance, so
     * that re-capturing the fixtures against a changed road graph moves the
     * deviation to the equivalent junction instead of into the middle of a
     * different one.
     */
    fun maneuverAt(plan: DriveHarness.Plan, afterM: Double, vararg types: String): Double =
        plan.maneuvers.first { m ->
            m.cumulativeM > afterM && types.any { m.type.startsWith(it) }
        }.cumulativeM

    // ---- fix streams --------------------------------------------------------

    private fun sim(
        path: List<LngLat>,
        legs: List<DriveSimulator.Leg>,
        noise: DriveSimulator.Noise = DriveSimulator.Noise.GOOD,
        faults: List<DriveSimulator.Fault> = emptyList(),
        hz: Double = 1.0,
        seed: Long = 5L,
    ): List<SimFix> = DriveSimulator.run(
        DriveSimulator.DrivePlan(
            path = path, legs = legs, noise = noise, faults = faults, hz = hz, seed = seed,
        )
    )

    /** A — a normal city drive, start to destination. */
    fun a(plan: DriveHarness.Plan = city(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.urban(stops = 5, blockM = 900.0), seed = seed)

    /** B — stop-and-go through 0, 5, 15, 30, 50 and 80 km/h. */
    fun b(plan: DriveHarness.Plan = city(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.stopAndGo(cycles = 3, legM = 250.0), seed = seed)

    /**
     * C — the driver carries straight on past a left turn, then follows the
     * route the router gives them.
     *
     * 150 m of wrong road, not 400: the offset crosses the 60 m threshold about
     * 70 m past the junction, so 150 m is a driver who noticed roughly when
     * Vector did. Driving 400 m past would make the reroute a request from
     * somewhere the fixture cannot answer for.
     */
    fun c(plan: DriveHarness.Plan = city(), seed: Long = 5L): Pair<List<SimFix>, Double> {
        val turn = maneuverAt(plan, 1_200.0, "turn")
        val path = deviateThenFollow(plan.geometry, turn, 150.0, missedTurnReroute().geometry)
        return sim(path, DriveSimulator.cruise(50.0), seed = seed) to turn
    }

    /**
     * D — the driver gradually takes the wrong road.
     *
     * Two stages, because the interesting behaviour is at the boundary between
     * them. First a service road 28 m from the carriageway, which is Doha's
     * commonest wrong-road mistake and is INSIDE the 60 m off-route threshold —
     * so Vector should not, and cannot, call it a deviation. Then the service
     * road diverges properly, and it should.
     *
     * The taper is computed from the speed rather than chosen, so the lateral
     * acceleration is that of a deliberate lane change. See [DrivePath.taperFor].
     */
    fun d(plan: DriveHarness.Plan = city(), seed: Long = 5L): Pair<List<SimFix>, Double> {
        val speedMs = 50.0 / 3.6
        val leaveAt = 1_800.0
        val service = DrivePath.parallel(
            plan.geometry, fromM = leaveAt, lengthM = 500.0, offsetM = 28.0,
            transitionM = DrivePath.taperFor(28.0, speedMs), side = 1,
        )
        // Where the service road ends, keep pulling away to 200 m.
        val idx = RouteGeometry.index(plan.geometry)!!
        val endBearing = idx.pointAt(leaveAt + 500.0)!!.bearing
        val tail = ArrayList<LngLat>()
        var d = 25.0
        while (d <= 125.0) {
            tail.add(DrivePath.offset(service.last(), endBearing + 35.0, d))
            d += 25.0
        }
        val wrong = DrivePath.slice(plan.geometry, 0.0, leaveAt) + service + tail
        // And then the route the live router answers with from there.
        val path = DrivePath.forkTo(wrong, DrivePath.lengthM(wrong), wrongRoadReroute().geometry)
        return sim(path, DriveSimulator.cruise(50.0), seed = seed) to leaveAt
    }

    /** E — drive past a turn, stop, and U-turn back. */
    fun e(plan: DriveHarness.Plan = city(), seed: Long = 5L): Pair<List<SimFix>, Double> {
        val turn = maneuverAt(plan, 1_200.0, "turn")
        val path = DrivePath.uTurn(plan.geometry, turn, overshootM = 260.0)
        val legs = listOf(
            DriveSimulator.Leg.Cruise(turn + 200.0, 50.0 / 3.6),
            DriveSimulator.Leg.Stop(4.0),
            DriveSimulator.Leg.Remainder(40.0 / 3.6),
        )
        return sim(path, legs, seed = seed) to turn
    }

    /** F — realistic urban-canyon drift, on the route the whole way. */
    fun f(plan: DriveHarness.Plan = city(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.urban(stops = 4, blockM = 1_000.0),
            noise = DriveSimulator.Noise.URBAN_CANYON, seed = seed)

    /** G — a 300 m reflected fix for three seconds, mid-drive. */
    fun g(plan: DriveHarness.Plan = city(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.cruise(50.0),
            faults = listOf(DriveSimulator.Fault.Jump(atS = 60.0, forS = 3.0,
                                                      offsetM = 300.0, towardDeg = 45.0)),
            seed = seed)

    /** H and I — 25 s with no fixes at all, then a normal fix again. */
    fun h(plan: DriveHarness.Plan = city(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.cruise(60.0),
            faults = listOf(DriveSimulator.Fault.Outage(atS = 90.0, forS = 25.0)),
            seed = seed)

    /** J — sustained motorway speed on the expressway route. */
    fun j(plan: DriveHarness.Plan = highway(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.highway(cruiseKmh = 110.0, exitAfterM = 12_000.0),
            seed = seed)

    /** M — the slip split at a steady 50 km/h, the useful-lane acceptance. */
    fun slip(plan: DriveHarness.Plan = slipSplit(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.cruise(50.0), seed = seed)

    /**
     * N — the two-way residential grid at residential speed (V7 Stage 4).
     *
     * `Noise.GOOD` deliberately, not `URBAN_CANYON`. The lateral claim being
     * measured is 1.75 m and canyon drift is tens of metres, so the noise
     * would swamp the very quantity under test — and the tracker's snapping
     * already has its own coverage against drift in scenario F. What this
     * trace asks is "does the vehicle sit in its own carriageway", not "does
     * it survive bad GPS", and one trace answering both would answer neither.
     */
    fun twoWayDrive(plan: DriveHarness.Plan = twoWay(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.urban(stops = 3, blockM = 500.0), seed = seed)

    /** P — the roundabout hop at 40 km/h (V7 Stage 4). */
    fun roundaboutDrive(plan: DriveHarness.Plan = roundabout(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.cruise(40.0), seed = seed)

    /** K — dense urban with repeated stops and short legs. */
    fun k(plan: DriveHarness.Plan = dense(), seed: Long = 5L) =
        sim(plan.geometry, DriveSimulator.urban(stops = 6, blockM = 180.0),
            noise = DriveSimulator.Noise.URBAN_CANYON, seed = seed)

    /** L — arrival at [kmh], optionally continuing past the destination. */
    fun arrival(
        plan: DriveHarness.Plan = city(),
        kmh: Double = 50.0,
        overshootM: Double = 0.0,
        seed: Long = 5L,
    ): List<SimFix> {
        val path = if (overshootM > 0) DrivePath.overshootEnd(plan.geometry, overshootM)
                   else plan.geometry
        return sim(path, DriveSimulator.cruise(kmh), seed = seed)
    }

    /** L — arrival across a car park, from a bearing the route never used. */
    fun arrivalFromAngle(
        plan: DriveHarness.Plan = city(),
        bearingDeg: Double = 200.0,
        seed: Long = 5L,
    ): List<SimFix> {
        val approach = DrivePath.approachFrom(plan.geometry, bearingDeg, fromM = 220.0)
        val path = DrivePath.slice(plan.geometry, 0.0, plan.geometryLengthM - 260.0) + approach
        return sim(path, DriveSimulator.cruise(30.0), seed = seed)
    }

    /**
     * The long run: 27 km, a real missed exit, canyon noise throughout.
     *
     * The deviation is at the `slight-left` off the slip road at 23 km, and it
     * is there because it is the only manoeuvre on this route where carrying
     * straight on actually leaves the road — measured: 40 m of offset at 60 m
     * past the junction, 299 m at 350 m past. The first version deviated at an
     * arbitrary 12 km, where the route is a straight corridor, so "carrying
     * straight on" stayed ON it and the scenario tested nothing. That is the
     * failure mode of picking a distance instead of a junction.
     */
    fun longRun(plan: DriveHarness.Plan = long(), seed: Long = 9L): List<SimFix> {
        val deviateAt = maneuverAt(plan, 20_000.0, "slight", "turn", "roundabout")
        val path = deviateThenFollow(plan.geometry, deviateAt, 150.0, longRunReroute().geometry)
        val legs = listOf(
            DriveSimulator.Leg.Cruise(1_500.0, 60.0 / 3.6),
            DriveSimulator.Leg.Stop(15.0),
            DriveSimulator.Leg.Cruise(12_000.0, 100.0 / 3.6),
            DriveSimulator.Leg.SlowTo(400.0, 60.0 / 3.6),
            DriveSimulator.Leg.Cruise(8_000.0, 90.0 / 3.6),
            DriveSimulator.Leg.SlowTo(600.0, 50.0 / 3.6),
            DriveSimulator.Leg.Stop(20.0),
            DriveSimulator.Leg.Remainder(45.0 / 3.6),
        )
        return sim(path, legs, noise = DriveSimulator.Noise.URBAN_CANYON, seed = seed)
    }
}
