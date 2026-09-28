package dev.vector.android

import dev.vector.geo.DrivePath
import dev.vector.geo.RouteGeometry
import dev.vector.geo.SimFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * V5's driving scenarios: does Vector behave like a navigator when the vehicle
 * is moving?
 *
 * V3 and V4 both closed with "needs a moving car" as a release blocker, and
 * both were right: every claim about navigation behaviour up to here was made
 * from a parked handset. What was missing was not a car, it was a *position
 * stream* — everything Vector does while navigating is a function of one, and a
 * stream is a thing that can be constructed. [dev.vector.geo.DriveSimulator]
 * constructs realistic ones and [DriveHarness] runs the production navigation
 * loop against them, frame by frame, at 120 Hz, with a backend that takes time
 * to answer.
 *
 * ## What these tests are worth, and what they are not
 *
 * They are simulation. They cannot say anything about GPS hardware, MapLibre's
 * renderer, or how hot the phone gets. What they CAN say is the part that was
 * unassertable before: that a deviation is detected within a bounded time, that
 * drift does not cause a reroute, that arrival is not declared early, that a
 * tunnel does not corrupt the route state. V5 §19 requires the distinction be
 * kept, so these are "VERIFIED IN SIMULATION"; `scripts/simulate_drive.sh`
 * pushes the same traces through the device's real fused-location pipeline and
 * is the "VERIFIED ON DEVICE" half.
 *
 * ## The rule the assertions follow
 *
 * Not "it did not crash". Every case states a number a driver would notice —
 * seconds to detect a deviation, metres of premature arrival, count of
 * unnecessary reroutes — and fails on it. Where Vector's behaviour turned out
 * to be wrong the test was written first and the behaviour changed second; the
 * four cases that did that are marked **THE DEFECT** and name what shipped.
 */
class DriveScenarioTest {

    // ---- shared machinery ---------------------------------------------------

    /**
     * A backend that answers with a route the live router really returned.
     *
     * The origin is checked, not ignored. A fixture is a reply to a request
     * made from one particular place, so if the trace geometry ever moves the
     * deviation somewhere else this fails loudly instead of quietly navigating
     * a route from a different junction — which would look like a pass.
     */
    private fun fixtureRouter(
        plan: DriveHarness.Plan,
        withinM: Double = 150.0,
        onRequest: (dev.vector.geo.LngLat) -> Unit = {},
    ) = DriveHarness.Router { from ->
        onRequest(from)
        val fixtureOrigin = plan.geometry.first()
        val d = DrivePath.metresBetween(from, fixtureOrigin)
        assertTrue(
            "the reroute was requested ${"%.0f".format(d)} m from where this fixture was " +
                "captured; re-capture it or fix the trace",
            d <= withinM,
        )
        plan
    }

    private fun drive(
        plan: DriveHarness.Plan,
        fixes: List<SimFix>,
        router: DriveHarness.Router = DriveHarness.deadRouter(),
    ): DriveHarness {
        val h = DriveHarness(router)
        h.preview(plan, plan.geometry.last(), "West Bay")
        h.start()
        h.drive(fixes)
        return h
    }

    /** Percentile of a sorted-able list, for reporting rather than for luck. */
    private fun pct(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    private fun DriveHarness.reroutesRequested() =
        events.filterIsInstance<DriveHarness.Event.RerouteRequested>()

    private fun DriveHarness.arrivals() =
        events.filterIsInstance<DriveHarness.Event.Arrived>()

    // =======================================================================
    // Scenario A — a normal city drive
    // =======================================================================

    @Test
    fun `A - a normal city drive reaches the destination`() {
        val plan = ScenarioTraces.city()
        val fixes = ScenarioTraces.a(plan)
        val h = drive(plan, fixes)

        // Before any other number is trusted: did the drive actually reach the
        // app? V4's web harness reported 56 fps for a "drive" during which the
        // app received exactly one position, and this is the assertion that
        // would have caught it.
        assertTrue("only ${h.fixesDelivered} fixes reached the app", h.fixesDelivered > 250)
        assertTrue("only ${h.framesRun} frames ran", h.framesRun > 40_000)

        assertEquals("a clean drive must not reroute", 0, h.reroutesRequested().size)
        assertTrue("a clean drive must never read as off route",
            h.samples.none { it.offRoute })
        assertEquals("arrival must fire exactly once", 1, h.arrivals().size)
        assertEquals("the journey must end in EXPLORE", Phase.EXPLORE, h.ui.phase)
        assertTrue("the arrival status must name the destination",
            h.ui.status.contains("West Bay"))
    }

    @Test
    fun `A - the maneuvers advance in order and none is skipped`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.a(plan))
        val seen = h.events.filterIsInstance<DriveHarness.Event.ManeuverChanged>().map { it.index }

        assertTrue("maneuver indices went backwards: $seen",
            seen.zipWithNext().all { (a, b) -> b > a })
        // Every maneuver on the route, in order, with none missed. A navigator
        // that skips a turn has told the driver nothing about it.
        val expected = plan.maneuvers.map { it.index }.filter { it > 0 }
        assertTrue("saw ${seen.size} of ${expected.size} maneuvers: $seen",
            seen.containsAll(expected))
    }

    @Test
    fun `A - remaining distance falls monotonically`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.a(plan))
        val navSamples = h.samples.filter { it.phase == Phase.NAVIGATING && it.remainingM > 0 }
        // Not exactly monotone: each fix nudges the along-route position by 25%
        // of its own error, so a fix landing slightly behind pulls the estimate
        // back. What must never happen is a driver watching the distance to go
        // GROW while they drive toward the destination by more than that
        // correction can account for.
        val worstIncrease = navSamples.zipWithNext { a, b -> b.remainingM - a.remainingM }.maxOrNull() ?: 0.0
        assertTrue("remaining distance grew by ${"%.1f".format(worstIncrease)} m between samples",
            worstIncrease < 12.0)
        assertTrue("the trip bar never counted down", navSamples.first().remainingM > navSamples.last().remainingM)
    }

    @Test
    fun `A - the drawn vehicle stays with the real one`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.a(plan))
        val err = h.puckErrorM()
        assertTrue("no puck was ever drawn", err.isNotEmpty())
        // The puck is snapped to the route and dead-reckoned, so it is not AT
        // the noisy fix — it is at the app's best estimate of the vehicle. The
        // number that matters is its distance from the TRUTH.
        val p50 = pct(err, 0.5)
        val p95 = pct(err, 0.95)
        assertTrue("puck error p50 ${"%.1f".format(p50)} m, p95 ${"%.1f".format(p95)} m",
            p50 < 12.0 && p95 < 35.0)
    }

    // =======================================================================
    // Scenario B — stop-and-go
    // =======================================================================

    @Test
    fun `B - stop-and-go traffic does not upset navigation`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.b(plan))

        assertEquals("stopping at lights is not a deviation", 0, h.reroutesRequested().size)
        assertTrue("a stop must not read as off route", h.samples.none { it.offRoute })
        assertEquals(1, h.arrivals().size)
        // The speedometer must not invent a number, and must not print one no
        // car reaches on a 6 km urban route.
        val speeds = h.samples.mapNotNull { it.speedKmh }
        assertTrue("speedometer read ${speeds.maxOrNull()} km/h", (speeds.maxOrNull() ?: 0) <= 95)
        assertTrue("speedometer read ${speeds.minOrNull()} km/h", (speeds.minOrNull() ?: 0) >= 0)
    }

    @Test
    fun `B - the turn countdown never runs backwards through a stop`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.b(plan))
        // Within one maneuver the distance to it must fall. Across a maneuver
        // boundary it jumps up, which is correct — that is the next turn.
        var worst = 0.0
        h.samples.zipWithNext { a, b ->
            if (a.maneuverIndex != null && a.maneuverIndex == b.maneuverIndex) {
                worst = maxOf(worst, b.distanceToManeuverM - a.distanceToManeuverM)
            }
        }
        assertTrue("the countdown to a single maneuver grew by ${"%.1f".format(worst)} m",
            worst < 12.0)
    }

    // =======================================================================
    // Scenario C — a missed turn
    // =======================================================================

    @Test
    fun `C - a missed turn is detected, rerouted and resumed`() {
        val plan = ScenarioTraces.city()
        val (fixes, turnAt) = ScenarioTraces.c(plan)
        val reroute = ScenarioTraces.missedTurnReroute()
        val h = drive(plan, fixes, fixtureRouter(reroute))

        val off = h.events.filterIsInstance<DriveHarness.Event.WentOffRoute>()
        assertTrue("driving 400 m past a turn was not noticed at all", off.isNotEmpty())

        // How long from crossing the threshold to asking for a new route. The
        // threshold is 60 m; at 50 km/h that is ~4 s of driving past the
        // junction, and the request must follow within a couple of fixes.
        val latency = h.deviationToRequestMs()
        assertTrue("no reroute was requested after the deviation", latency != null)
        assertTrue("took ${latency} ms to ask for a new route", latency!! <= 2_500)

        // Not a storm. One request, not one per fix — the cooldown's whole job.
        assertEquals("a single deviation must produce a single request",
            1, h.reroutesRequested().size)

        val applied = h.events.filterIsInstance<DriveHarness.Event.RerouteApplied>()
        assertEquals("the new route was never applied", 1, applied.size)
        // And navigation resumed: off-route cleared and stayed cleared.
        val after = h.samples.filter { it.tMs > applied.first().tMs + 3_000 }
        assertTrue("still off route ${after.count { it.offRoute }} samples after rerouting",
            after.count { it.offRoute } == 0)
        assertTrue("no maneuver after the reroute", after.any { it.maneuverIndex != null })
    }

    @Test
    fun `C - the deviation is noticed while the driver could still act on it`() {
        // The product question behind the latency number: how far past the
        // junction is the car when Vector says something? A navigator that
        // notices after 300 m has told the driver nothing useful.
        val plan = ScenarioTraces.city()
        val (fixes, turnAt) = ScenarioTraces.c(plan)
        val h = drive(plan, fixes, fixtureRouter(ScenarioTraces.missedTurnReroute()))
        val off = h.events.filterIsInstance<DriveHarness.Event.WentOffRoute>().first()
        val atDetection = h.samples.first { it.tMs >= off.tMs }
        // Along the DRIVEN path, whose first `turnAt` metres are the route, so
        // this is metres past the junction.
        val past = atDetection.truthAlongM - turnAt
        assertTrue("Vector noticed ${"%.0f".format(past)} m past the junction", past < 130.0)
    }

    // =======================================================================
    // Scenario D — the wrong road
    // =======================================================================

    @Test
    fun `D - a service road 28 m away is not called a deviation`() {
        // Doha's commonest wrong-road mistake, and the honest answer is that
        // Vector cannot tell: 28 m is inside the 60 m off-route threshold, and
        // it is also inside the width of a divided carriageway plus its slip
        // lane. Rerouting here would be wrong far more often than it was right.
        //
        // This is an ARCHITECTURAL limit, not a bug, and it is asserted so that
        // nobody later "fixes" it by tightening the threshold — which would
        // make every dual carriageway in the country a deviation. The real
        // answer is map matching against the road graph, which is noted in the
        // V5 report as open.
        val plan = ScenarioTraces.city()
        val (fixes, leaveAt) = ScenarioTraces.d(plan)
        val h = drive(plan, fixes, fixtureRouter(ScenarioTraces.wrongRoadReroute()))

        val onService = h.samples.filter { it.truthOffsetM in 20.0..40.0 }
        assertTrue("the trace never spent time on the service road", onService.size > 5)
        assertTrue("a 28 m offset was called a deviation",
            onService.none { it.offRoute })
    }

    @Test
    fun `D - once the roads genuinely separate, it reroutes`() {
        val plan = ScenarioTraces.city()
        val (fixes, _) = ScenarioTraces.d(plan)
        val h = drive(plan, fixes, fixtureRouter(ScenarioTraces.wrongRoadReroute()))
        assertTrue("the wrong road was never noticed",
            h.events.filterIsInstance<DriveHarness.Event.WentOffRoute>().isNotEmpty())
        assertTrue("no reroute after leaving the road entirely",
            h.reroutesRequested().isNotEmpty())
        // Still not a storm: a gradual divergence must not produce a request
        // per fix as the offset crosses and re-crosses the threshold.
        assertTrue("${h.reroutesRequested().size} requests for one wrong road",
            h.reroutesRequested().size <= 2)
    }

    // =======================================================================
    // Scenario E — a U-turn
    // =======================================================================

    @Test
    fun `E - driving past a turn and reversing is handled`() {
        val plan = ScenarioTraces.city()
        val (fixes, _) = ScenarioTraces.e(plan)
        // A dead router, deliberately: a U-turn on the same carriageway never
        // exceeds the off-route threshold, so nothing should ask for a route at
        // all, and a router that answers would hide it if something did.
        val h = drive(plan, fixes, DriveHarness.deadRouter())

        // A U-turn on the same carriageway never leaves the route line, so the
        // OFFSET never exceeds the threshold — Vector stays "on route" while
        // driving the wrong way up it. That is what a distance-to-line model
        // can see, and asserting it pins the real behaviour rather than a hoped
        // one: the along-ROUTE position simply runs backwards, while the driven
        // path keeps getting longer.
        val along = h.samples.map { it.truthRouteAlongM }
        assertTrue("the U-turn never reversed direction",
            along.zipWithNext().any { (a, b) -> b < a - 5.0 })
        // What must NOT happen: arrival, or a corrupted remaining distance.
        assertEquals("arrival must not fire while driving away from the destination",
            0, h.arrivals().size)
        assertTrue("remaining distance went negative",
            h.samples.all { it.remainingM >= 0.0 })
        assertTrue("navigation must survive a U-turn",
            h.ui.phase == Phase.NAVIGATING)
    }

    // =======================================================================
    // Scenario F — GPS drift
    // =======================================================================

    @Test
    fun `F - urban-canyon drift never causes a reroute`() {
        // The single most valuable assertion in this file. An off-route
        // threshold is only correct if it survives the error a real receiver
        // makes, and the error that matters is CORRELATED — multipath pushes
        // the fix to the same wrong side for twenty seconds at a time, so it
        // does not average out the way white noise would.
        val plan = ScenarioTraces.city()
        for (seed in 1L..8L) {
            val h = drive(plan, ScenarioTraces.f(plan, seed = seed))
            assertEquals("seed $seed: drift caused ${h.reroutesRequested().size} reroutes",
                0, h.reroutesRequested().size)
            assertEquals("seed $seed: arrival", 1, h.arrivals().size)
        }
    }

    @Test
    fun `F - a drive that starts part-way in still needs no reroute`() {
        // ## What this test does and does not guard
        //
        // It exercises the LATE START, which nothing else here did: every other
        // case begins the drive at the route's first coordinate, and the device
        // cannot — it receives fixes, asks for a route, waits, previews, and
        // starts several seconds in. That path is worth covering on its own.
        //
        // It is NOT the guard for the defect described below. I wrote it as
        // that guard, and then checked: with the fix disabled it still passes,
        // swept across 24 start timings and four noise seeds. So it cannot
        // fail for that reason, and a test that cannot fail is worse than no
        // test because it reads as coverage.
        //
        // The real guards are `RouteTrackerTest`'s five heading cases — which
        // DO bite: with the fix disabled, `a heading picks the outbound pass`
        // locks at 912 m instead of 100 m, the same half-kilometre error the
        // device showed — and `scripts/simulate_drive.sh scenario-f-drift`,
        // which is where it was found and where it is confirmed fixed.
        //
        // ## The defect
        //
        // Found on the S24, not by this suite.
        //
        // The first route the live router returns for Souq Waqif -> West Bay
        // U-turns 329 m in and comes back up the same street, so for the first
        // few hundred metres the route has TWO PASSES a few metres apart. An
        // unconstrained projection takes the nearest segment, and with
        // urban-canyon drift the nearest segment is sometimes the return leg.
        //
        // Measured from the device log: the tracker locked at roughly 550 m
        // along instead of 50 m, the search window then held the error because
        // the true position was far behind its edge, and the driver was
        // declared off route and rerouted ONE SECOND after pressing Start.
        //
        // Every other case here starts the drive at t=0, where the outbound leg
        // is unambiguously nearest. The device cannot: it receives fixes, asks
        // for a route, waits, and starts several seconds in — by which time the
        // first lock lands inside the ambiguous stretch. Fixed by giving the
        // projection the vehicle's heading, which is the one thing that differs
        // by 180 degrees between two passes of a U-turn.
        // SWEPT over the moment the route is set, because the defect is
        // timing-sensitive: it only bites while the vehicle is inside the
        // stretch where the two passes are within snapping distance of each
        // other, and exactly when that is depends on how long the route request
        // took. A single start time is a coin toss — the first version of this
        // test used one, passed with the fix disabled, and was worthless.
        val plan = ScenarioTraces.city()
        var offending = 0
        for (seed in 1L..4L) {
            for (setRouteAtMs in listOf(2_000L, 4_000L, 6_000L, 8_000L, 12_000L, 18_000L)) {
                val h = DriveHarness(DriveHarness.deadRouter())
                h.drive(
                    ScenarioTraces.f(plan, seed = seed),
                    startAfterMs = setRouteAtMs,
                    plan = plan,
                    destination = plan.geometry.last(),
                    name = "West Bay",
                )
                if (h.reroutesRequested().isNotEmpty()) {
                    offending++
                    println("seed $seed, route set at ${setRouteAtMs} ms: " +
                        "${h.reroutesRequested().size} reroute(s)")
                }
            }
        }
        assertEquals(
            "a late-started drive on clean-enough GPS rerouted in $offending of " +
                "24 timings",
            0, offending,
        )
    }

    @Test
    fun `F - drift does not make the vehicle jump about`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.f(plan))
        // Consecutive drawn positions, at 250 ms sampling. At 50 km/h that is
        // 3.5 m of real travel; anything much beyond it is the puck being
        // yanked by a noisy fix rather than moved by the vehicle.
        val hops = h.samples.mapNotNull { it.puck }.zipWithNext { a, b -> DrivePath.metresBetween(a, b) }
        assertTrue("worst puck hop ${"%.1f".format(hops.max())} m in 250 ms", hops.max() < 20.0)
    }

    // =======================================================================
    // Scenario G — a GPS jump
    // =======================================================================

    @Test
    fun `G - a 300 m reflected fix does not reroute the driver`() {
        // THE DEFECT. Vector accepted every fix it was handed. A 300 m
        // displacement between two 1 Hz fixes implies 1 080 km/h, and the app's
        // response was to declare the driver off route and ask the backend for
        // a new route from the middle of the bay — while the car was still on
        // Al Corniche doing 50.
        //
        // Fixed by dev.vector.geo.FixGate: a fix implying an impossible speed
        // is rejected, with a bounded number of consecutive rejections so a
        // receiver that has genuinely moved can always win.
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.g(plan))
        assertEquals("a GPS jump must not cost the driver a reroute",
            0, h.reroutesRequested().size)
        assertEquals(1, h.arrivals().size)
        // And the gate is WHY, not luck: the reflected fixes were dropped. A
        // version of this test that only counted reroutes would pass equally
        // well if the jump had simply landed inside the off-route threshold.
        assertTrue("no fix was rejected by the plausibility gate",
            h.fixesDropped >= 1)
    }

    @Test
    fun `G - navigation state survives a jump intact`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.g(plan))
        // No leap in the remaining distance, in either direction.
        val jumps = h.samples.filter { it.phase == Phase.NAVIGATING }
            .zipWithNext { a, b -> abs(b.remainingM - a.remainingM) }
        assertTrue("remaining distance moved ${"%.0f".format(jumps.max())} m between samples",
            jumps.max() < 40.0)
    }

    // =======================================================================
    // Scenarios H and I — a GPS outage and its recovery
    // =======================================================================

    @Test
    fun `H - a GPS outage is told to the driver`() {
        // THE DEFECT. The tracker correctly stops dead-reckoning after three
        // seconds without a fix, so the vehicle froze — and NOTHING ELSE ON THE
        // SCREEN CHANGED. The maneuver, the countdown and the ETA all stayed
        // authoritative-looking, and a driver in a tunnel had no way to tell a
        // stopped car from a stopped receiver.
        //
        // `NavSession.onFrame` returned `Result(ui, emptyList())` the moment the
        // tracker had nothing to draw, which is exactly the situation that
        // needed to reach the UI.
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.h(plan))

        val lost = h.samples.filter { it.gps == GpsHealth.LOST }
        assertTrue("the driver was never told the signal was lost", lost.isNotEmpty())
        assertTrue("the warning has driver-facing copy", GpsHealth.LOST.message != null)

        // Told promptly: the outage starts at 90 s and GPS_STALE_MS is 5 s.
        val firstLost = lost.first().tMs
        assertTrue("told at ${firstLost} ms for an outage starting at 90 000",
            firstLost in 94_000..97_000)
        // And not one moment before. A warning that fires on ordinary dropped
        // updates is one the driver learns to ignore.
        assertTrue("warned before the outage began",
            h.samples.none { it.tMs < 90_000 && it.gps == GpsHealth.LOST })
    }

    @Test
    fun `H - the vehicle freezes rather than sailing on without evidence`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.h(plan))
        val froze = h.events.filterIsInstance<DriveHarness.Event.PuckFroze>()
        assertTrue("the puck kept being redrawn during a 25 s outage", froze.isNotEmpty())
        val longest = froze.maxOf { it.forMs }
        assertTrue("the longest freeze was only ${longest} ms", longest > 15_000)
    }

    @Test
    fun `H - an outage does not trigger a reroute`() {
        // No position is not a wrong position. Asking the backend for a route
        // from a stale fix would burn the request at the moment it is least
        // likely to be right.
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.h(plan))
        assertTrue("an outage produced ${h.reroutesRequested().size} reroutes",
            h.reroutesRequested().isEmpty())
    }

    @Test
    fun `I - navigation recovers when the signal comes back`() {
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.h(plan))
        // 25 s of outage at 60 km/h is ~420 m of road driven blind. The fix
        // that ends it is a long way from the last one, and the tracker must
        // re-lock on it rather than treat it as a deviation.
        val after = h.samples.filter { it.tMs in 116_000..130_000 }
        assertTrue("no samples after the outage", after.isNotEmpty())
        assertTrue("positioning did not recover: ${after.map { it.gps }.distinct()}",
            after.any { it.gps == GpsHealth.GOOD })
        assertTrue("the app came back off-route", after.none { it.offRoute })
        assertEquals("the journey was torn down by an outage", 1, h.arrivals().size)
        assertTrue("the phase changed during the outage",
            h.samples.filter { it.tMs < 116_000 }.all { it.phase == Phase.NAVIGATING })
    }

    // =======================================================================
    // Scenario J — the motorway
    // =======================================================================

    @Test
    fun `J - a motorway drive keeps up and arrives`() {
        val plan = ScenarioTraces.highway()
        val h = drive(plan, ScenarioTraces.j(plan))
        assertTrue("only ${h.fixesDelivered} fixes", h.fixesDelivered > 400)
        assertEquals("a clean motorway drive must not reroute", 0, h.reroutesRequested().size)
        assertEquals(1, h.arrivals().size)
        val top = h.samples.mapNotNull { it.speedKmh }.max()
        assertTrue("top speed reported was only $top km/h", top > 100)
    }

    @Test
    fun `J - maneuvers are announced with time to act at 110 km-h`() {
        // 500 m of warning is generous in town and 16 seconds on an
        // expressway. The announcer scales its far stage with speed for exactly
        // this reason, and this is the test that the scaling is enough.
        val plan = ScenarioTraces.highway()
        val h = drive(plan, ScenarioTraces.j(plan))
        val spoke = h.events.filterIsInstance<DriveHarness.Event.Spoke>()
        assertTrue("nothing was said on a 16 km motorway drive", spoke.size > 5)

        // For each maneuver: how long between the first thing said about it and
        // reaching it. A maneuver only becomes "current" when the previous one
        // is done, so on a short leg its OWN announcement cannot be early — the
        // requirement is that the driver was warned, not that the warning was
        // dedicated. So a short dedicated lead is only acceptable if a compound
        // announcement about the previous maneuver mentioned this one.
        val changes = h.events.filterIsInstance<DriveHarness.Event.ManeuverChanged>()
        val bare = ArrayList<String>()
        var compounds = 0
        for ((i, change) in changes.withIndex()) {
            val reachedAt = changes.getOrNull(i + 1)?.tMs ?: continue
            val firstWord = spoke.firstOrNull { it.tMs in change.tMs..reachedAt } ?: continue
            val lead = (reachedAt - firstWord.tMs) / 1000.0
            if (lead >= 4.0) continue
            val warnedEarlier = spoke.any { it.tMs < change.tMs && ", then " in it.text }
            if (warnedEarlier) compounds++
            else bare.add("maneuver ${change.index} got ${"%.1f".format(lead)} s and no earlier mention")
        }
        assertTrue(bare.joinToString("; "), bare.isEmpty())
        assertTrue("no compound announcement was ever made on a route with 45 m legs",
            compounds > 0)
    }

    @Test
    fun `J - the puck keeps up at motorway speed`() {
        val plan = ScenarioTraces.highway()
        val h = drive(plan, ScenarioTraces.j(plan))
        val err = h.puckErrorM()
        // At 110 km/h the vehicle covers 30 m per fix, so the dead-reckoning
        // step is doing most of the work between them. If it were not, the puck
        // would sit tens of metres behind the car — which is the defect the
        // derived-speed work fixed for receivers that report no speed.
        assertTrue("puck error p95 ${"%.1f".format(pct(err, 0.95))} m at motorway speed",
            pct(err, 0.95) < 45.0)
    }

    // =======================================================================
    // Scenario K — dense urban
    // =======================================================================

    @Test
    fun `K - dense urban driving does not lose a maneuver`() {
        val plan = ScenarioTraces.dense()
        val h = drive(plan, ScenarioTraces.k(plan))
        assertEquals("a short dense route must not reroute", 0, h.reroutesRequested().size)
        val seen = h.events.filterIsInstance<DriveHarness.Event.ManeuverChanged>().map { it.index }
        val expected = plan.maneuvers.map { it.index }.filter { it > 0 }
        assertTrue("saw $seen of $expected on a ${"%.0f".format(plan.distanceM)} m route",
            seen.containsAll(expected))
        assertEquals(1, h.arrivals().size)
    }

    @Test
    fun `K - short legs still get spoken`() {
        // With legs of a hundred metres the announcer's stages compress: the
        // 120 m TURN stage of one maneuver and the 30 m NOW stage of the
        // previous one can fall in the same second. What must not happen is a
        // turn the driver is never told about.
        val plan = ScenarioTraces.dense()
        val h = drive(plan, ScenarioTraces.k(plan))
        val spoke = h.events.filterIsInstance<DriveHarness.Event.Spoke>()
        val turns = plan.maneuvers.count { it.type.startsWith("turn") || it.type == "roundabout" }
        assertTrue("$turns turns produced only ${spoke.size} announcements",
            spoke.size >= turns)
    }

    // =======================================================================
    // Scenario L — arrival
    // =======================================================================

    @Test
    fun `L - arrival fires once, at the destination, at every speed`() {
        val plan = ScenarioTraces.city()
        for (kmh in listOf(15.0, 30.0, 50.0, 80.0)) {
            val h = drive(plan, ScenarioTraces.arrival(plan, kmh = kmh))
            assertEquals("$kmh km/h: arrivals", 1, h.arrivals().size)
            val a = h.arrivals().first()
            // Not premature. The trigger is 25 m of REMAINING ROUTE, so the
            // driver must be within that of the end when it fires.
            assertTrue("$kmh km/h: arrived with ${"%.0f".format(plan.geometryLengthM - a.alongM)} m still to drive",
                plan.geometryLengthM - a.alongM < 60.0)
        }
    }

    @Test
    fun `L - driving past the destination still counts as arriving`() {
        // The one arrival a real driver never performs is stopping exactly on
        // the last vertex. They pass it.
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.arrival(plan, kmh = 50.0, overshootM = 120.0))
        assertEquals(1, h.arrivals().size)
        assertEquals(Phase.EXPLORE, h.ui.phase)
    }

    @Test
    fun `L - arriving across a car park is still an arrival`() {
        // V5 §L asks for arrival "at different angles", which is not a synonym
        // for different speeds: a destination reached across a car park is
        // metres from the route's end and hundreds of metres along it.
        val plan = ScenarioTraces.city()
        val h = drive(plan, ScenarioTraces.arrivalFromAngle(plan))
        assertTrue("no arrival, and no reroute either — the driver was abandoned " +
            "${h.reroutesRequested().size} m from the destination",
            h.arrivals().isNotEmpty() || h.reroutesRequested().isNotEmpty())
    }

    // =======================================================================
    // The navigation state machine
    // =======================================================================

    @Test
    fun `the phase sequence over a whole journey is EXPLORE PREVIEW NAVIGATING EXPLORE`() {
        val plan = ScenarioTraces.city()
        val h = DriveHarness(DriveHarness.deadRouter())
        assertEquals(Phase.EXPLORE, h.ui.phase)
        h.preview(plan, plan.geometry.last(), "West Bay")
        assertEquals(Phase.PREVIEW, h.ui.phase)
        h.start()
        assertEquals(Phase.NAVIGATING, h.ui.phase)
        h.drive(ScenarioTraces.a(plan))
        assertEquals(Phase.EXPLORE, h.ui.phase)
        // And the journey's state is gone with it: a new trip must not inherit
        // the last one's route.
        assertTrue(h.ui.maneuvers.isEmpty())
        assertEquals(0.0, h.ui.remainingM, 0.0)
    }

    @Test
    fun `the GPS state machine runs ACQUIRING to GOOD to LOST to GOOD`() {
        val plan = ScenarioTraces.city()
        val h = DriveHarness(DriveHarness.deadRouter())
        h.preview(plan, plan.geometry.last())
        assertEquals(GpsHealth.ACQUIRING, h.ui.gps)
        h.start()
        h.drive(ScenarioTraces.h(plan))
        val order = h.samples.map { it.gps }.zipWithNext().filter { (a, b) -> a != b }
        assertTrue("no GPS transitions at all", order.isNotEmpty())
        assertTrue("GPS never went GOOD -> LOST",
            order.any { (a, b) -> a == GpsHealth.GOOD && b == GpsHealth.LOST })
        assertTrue("GPS never recovered",
            order.any { (a, b) -> a == GpsHealth.LOST && b == GpsHealth.GOOD })
    }

    @Test
    fun `off route to rerouting to navigating is one cycle, not a loop`() {
        val plan = ScenarioTraces.city()
        val (fixes, _) = ScenarioTraces.c(plan)
        val h = drive(plan, fixes, fixtureRouter(ScenarioTraces.missedTurnReroute()))
        val order = h.events.mapNotNull {
            when (it) {
                is DriveHarness.Event.WentOffRoute -> "off"
                is DriveHarness.Event.RerouteRequested -> "request"
                is DriveHarness.Event.RerouteApplied -> "applied"
                is DriveHarness.Event.CameBackOnRoute -> "on"
                else -> null
            }
        }
        // "on" is not in the list, and that is correct rather than a gap: the
        // reroute's arrival sets `offRoute = false` as part of replacing the
        // route, so there is no separate moment of coming back on the OLD one.
        assertEquals("the deviation cycle ran as $order",
            listOf("off", "request", "applied"), order.take(3))
        assertEquals("more than one deviation cycle for one missed turn",
            1, order.count { it == "request" })
    }

    // =======================================================================
    // Failure injection
    // =======================================================================

    @Test
    fun `a reroute the backend refuses leaves navigation usable`() {
        val plan = ScenarioTraces.city()
        val (fixes, _) = ScenarioTraces.c(plan)
        val h = drive(plan, fixes, DriveHarness.deadRouter())
        assertTrue("the failure was never reported to the driver",
            h.samples.any { it.error != null })
        assertTrue("no reroute failure was recorded",
            h.events.filterIsInstance<DriveHarness.Event.RerouteFailed>().isNotEmpty())
        // The spinner must not be left running after a failure. Checked at the
        // moment the error was on screen, not at the end of the drive: this
        // trace rejoins the route it was given and legitimately arrives, which
        // rebuilds the state and clears both.
        val whileFailed = h.samples.first { it.error != null }
        assertTrue("the rerouting spinner was left running", !whileFailed.rerouting)
        assertTrue("the driver was not told they were off route", whileFailed.offRoute)
        // And it retries, rather than giving up on the journey silently. The
        // 8 s cooldown bounds how often.
        assertTrue("only ${h.reroutesRequested().size} attempts over the whole deviation",
            h.reroutesRequested().size >= 1)
    }

    @Test
    fun `a malformed route reply does not corrupt navigation`() {
        // A single-coordinate geometry. `RouteGeometry.index` returns null for
        // it rather than a half-built index, so the tracker goes Idle — the app
        // must survive that rather than draw a vehicle at a position it does
        // not have.
        val plan = ScenarioTraces.city()
        val (fixes, _) = ScenarioTraces.c(plan)
        val broken = plan.copy(geometry = listOf(plan.geometry.first()), distanceM = 0.0)
        val h = drive(plan, fixes, DriveHarness.Router { broken })
        assertEquals("a broken reply must not be treated as an arrival", 0, h.arrivals().size)
        assertTrue("the app left NAVIGATING on a malformed reply",
            h.ui.phase == Phase.NAVIGATING)
    }

    @Test
    fun `a route whose steps and geometry disagree does not run the countdown backwards`() {
        // The maneuvers' cumulative distances come from the backend and the
        // along-route position from the geometry. If a reply's steps described
        // a longer route than its own polyline, the countdown would go negative
        // near the end. Asserted because the two numbers have different
        // sources and nothing else checks they agree.
        val plan = ScenarioTraces.city()
        val stretched = plan.copy(
            maneuvers = plan.maneuvers.map { it.copy(cumulativeM = it.cumulativeM * 1.15) },
            distanceM = plan.distanceM * 1.15,
        )
        val h = drive(stretched, ScenarioTraces.a(plan))
        assertTrue("the countdown went negative",
            h.samples.all { it.distanceToManeuverM >= 0.0 })
        assertTrue("the remaining distance went negative",
            h.samples.all { it.remainingM >= 0.0 })
    }

    // =======================================================================
    // The long run
    // =======================================================================

    @Test
    fun `a 27 km drive with a deviation stays correct end to end`() {
        val plan = ScenarioTraces.long()
        val h = drive(plan, ScenarioTraces.longRun(plan),
                      fixtureRouter(ScenarioTraces.longRunReroute()))

        assertTrue("only ${h.fixesDelivered} fixes on a 27 km drive", h.fixesDelivered > 700)
        assertTrue("only ${h.framesRun} frames", h.framesRun > 100_000)

        // The deviation happened and was handled exactly once.
        assertEquals("one deviation, one request", 1, h.reroutesRequested().size)
        assertEquals(1, h.events.filterIsInstance<DriveHarness.Event.RerouteApplied>().size)

        // Nothing accumulated. An event per fix would be ~800; an event per
        // FRAME would be 100 000, and that is what a leak in the recording
        // path looks like.
        assertTrue("${h.events.size} events for one drive — something is accumulating",
            h.events.size < 400)

        // No route-state corruption over the whole length.
        assertTrue(h.samples.all { it.remainingM >= 0.0 })
        assertTrue(h.samples.all { it.distanceToManeuverM >= 0.0 })
        assertTrue("the maneuver index went out of range",
            h.samples.mapNotNull { it.maneuverIndex }.all { it >= 0 })
    }

    @Test
    fun `a long drive does not drift the vehicle away from the road`() {
        val plan = ScenarioTraces.long()
        val h = drive(plan, ScenarioTraces.longRun(plan),
                      fixtureRouter(ScenarioTraces.longRunReroute()))
        // Dead reckoning integrates speed, so an error in the integration
        // accumulates — which is exactly the failure a short test cannot see.
        // Compare the first and last thirds of the drive.
        val err = h.samples.mapNotNull { s -> s.puck?.let { DrivePath.metresBetween(it, s.truth) } }
        if (err.size > 30) {
            val third = err.size / 3
            val early = pct(err.take(third), 0.9)
            val late = pct(err.takeLast(third), 0.9)
            assertTrue("puck error grew from ${"%.1f".format(early)} m to ${"%.1f".format(late)} m",
                late < early + 25.0)
        }
    }
    // =======================================================================
    // Over the posted limit, while moving
    // =======================================================================
    //
    // Asked directly: "check what would happen when the user speeds more than
    // the speed limits". The indicator and the alert are both driven by
    // `speedKmh`, which only exists while the tracker is interpolating — so
    // this is a question that can only be answered by a moving vehicle.

    @Test
    fun `exceeding a posted limit raises the indicator while driving`() {
        val plan = ScenarioTraces.city()
        val h = DriveHarness(DriveHarness.deadRouter())
        h.preview(plan, plan.geometry.last(), "West Bay")
        // A posted 50 on a route the trace drives at up to 80.
        h.setSpeedLimit(50, inferred = false)
        h.start()
        h.drive(ScenarioTraces.b(plan))

        val over = h.samples.filter { it.overSpeedLimit }
        assertTrue("never flagged over a 50 limit on a trace reaching 80 km/h",
            over.isNotEmpty())
        // And only when actually over, with the 5 km/h tolerance honoured.
        assertTrue("flagged a driver inside the tolerance",
            over.all { (it.speedKmh ?: 0) > 55 })
    }

    @Test
    fun `an unposted limit is never enforced, however fast the drive`() {
        // A class default is the median for the road type, not a sign. The
        // number is still SHOWN — marked as unposted — it is just not enforced.
        val plan = ScenarioTraces.city()
        val h = DriveHarness(DriveHarness.deadRouter())
        h.preview(plan, plan.geometry.last(), "West Bay")
        h.setSpeedLimit(50, inferred = true)
        h.start()
        h.drive(ScenarioTraces.b(plan))
        assertTrue("an inferred limit was enforced",
            h.samples.none { it.overSpeedLimit })
    }

    @Test
    fun `the over-limit alert is spoken once per excursion, not per frame`() {
        // The frame loop runs at 120 Hz. An alert evaluated there without
        // repetition rules would say "speed limit 50" a hundred times a second.
        val plan = ScenarioTraces.city()
        val h = DriveHarness(DriveHarness.deadRouter())
        h.preview(plan, plan.geometry.last(), "West Bay")
        h.setSpeedLimit(50, inferred = false)
        h.start()
        h.drive(ScenarioTraces.b(plan))

        val alerts = h.events.filterIsInstance<DriveHarness.Event.Spoke>()
            .filter { it.text.startsWith("Speed limit") }
        assertTrue("no over-limit alert on a trace that reaches 80 in a 50",
            alerts.isNotEmpty())
        // Three stop-and-go cycles reaching 80, over about twenty minutes of
        // trace: a handful of alerts, not hundreds and not thousands.
        assertTrue("${alerts.size} alerts — that is nagging", alerts.size <= 12)
        // And never twice inside the repeat window.
        val gaps = alerts.zipWithNext { a, b -> b.tMs - a.tMs }
        assertTrue("two alerts ${gaps.minOrNull()} ms apart",
            gaps.all { it >= 55_000 })
    }

    // =======================================================================
    // Scenario N — the two-way carriageway (V7 Stage 4)
    //
    // The case Stage 4 exists for, driven end to end. `two-way-rabia` has four
    // steps tagged `approach_lanes=2, forward_lanes=1` — a street with ONE lane
    // each way, which is 5,728 ways in Qatar — and four more that are genuinely
    // one-way or have no count at all. One trace therefore exercises both the
    // claim and the refusal.
    //
    // Measured with [DriveHarness.puckLateralM], which is SIGNED. That matters
    // more than anything else here: `puckErrorM` is a distance, so a vehicle
    // drawn 1.75 m into the oncoming carriageway and one drawn correctly in its
    // own read as exactly the same number.
    // =======================================================================

    /** The lateral offset of a whole drive, as a sorted list. */
    private fun lateralOf(
        plan: DriveHarness.Plan,
        fixes: List<SimFix>,
    ): Pair<DriveHarness, List<Double>> {
        val h = drive(plan, fixes)
        val lat = h.puckLateralM()
        assertTrue("the drive produced no on-route samples at all", lat.size > 100)
        return h to lat
    }

    @Test
    fun `N - the vehicle is never drawn in the oncoming carriageway`() {
        // The headline claim of Stage 4, and the one a driver would report as a
        // bug. Qatar drives on the right, so on a TWO-WAY leg the offset is
        // either zero (nothing claimed) or positive (the driven carriageway)
        // and never negative.
        //
        // Scoped to two-way legs, and the scope is the claim rather than a
        // convenience: oncoming traffic exists only where a two-way way does,
        // and on a ONE-WAY carriageway `forward_lanes == lanes` means the way
        // centreline IS the carriageway centreline — a left turn there is
        // legitimately placed in the left lane, which is left of that
        // centreline and is still entirely on the driver's own road. What must
        // hold on those legs is the weaker, checkable fact below: the placement
        // stays inside the carriageway it claims.
        val plan = ScenarioTraces.twoWay()
        val (h, lat) = lateralOf(plan, ScenarioTraces.twoWayDrive(plan))
        val rows = h.samples.filter {
            it.phase == Phase.NAVIGATING && !it.offRoute && it.puck != null
        }
        var twoWayChecked = 0
        var oneWayChecked = 0
        for ((i, v) in lat.withIndex()) {
            val r = rows.getOrNull(i) ?: continue
            val m = plan.maneuvers.firstOrNull { it.cumulativeM >= r.truthRouteAlongM } ?: continue
            val fwd = m.forwardLanes ?: continue
            val total = m.approachLanes ?: continue
            if (total == 2 * fwd) {
                assertTrue(
                    "the vehicle was drawn ${"%.2f".format(-v)} m into oncoming traffic",
                    v > -0.01,
                )
                twoWayChecked++
            } else if (total == fwd) {
                // One-way: every lateral claim must be inside the carriageway.
                // Catches a sign or scale error, which is what the two-way half
                // catches for two-way legs.
                assertTrue(
                    "the vehicle was drawn ${"%.2f".format(v)} m off the centreline of a " +
                        "one-way ${fwd}-lane carriageway",
                    Math.abs(v) <= fwd * VectorStyle.LANE_WIDTH_M / 2 + 0.01,
                )
                oneWayChecked++
            }
        }
        assertTrue("no settled two-way samples were checked", twoWayChecked >= 20)
        assertTrue("no settled one-way samples were checked", oneWayChecked >= 20)
    }

    @Test
    fun `N - a one-lane-each-way street puts the vehicle a half-carriageway over`() {
        // Before Stage 4 every sample of this drive read 0.00: the vehicle sat
        // on the centre paint of a 7 m road with half the car in oncoming
        // traffic. The four `forward_lanes=1, approach_lanes=2` legs must now
        // read half of a 3.5 m carriageway.
        val plan = ScenarioTraces.twoWay()
        val (h, lat) = lateralOf(plan, ScenarioTraces.twoWayDrive(plan))
        val rows = h.samples.filter {
            it.phase == Phase.NAVIGATING && !it.offRoute && it.puck != null
        }
        // Samples on a leg whose approach is two-way, taken well clear of the
        // junctions at either end so a taper is not measured as a settled
        // offset. 60 m is over a taper length at this fixture's spacing.
        val twoWay = ArrayList<Double>()
        for ((i, v) in lat.withIndex()) {
            val r = rows.getOrNull(i) ?: continue
            val m = plan.maneuvers.firstOrNull { it.cumulativeM >= r.truthRouteAlongM } ?: continue
            val legStart = plan.maneuvers.lastOrNull { it.cumulativeM < m.cumulativeM }?.cumulativeM ?: 0.0
            val clear = r.truthRouteAlongM > legStart + 60.0 && r.truthRouteAlongM < m.cumulativeM - 60.0
            if (clear && m.forwardLanes == 1 && m.approachLanes == 2) twoWay.add(v)
        }
        assertTrue("no settled two-way samples in a trace with four such legs",
            twoWay.size >= 20)
        val median = twoWay.sorted()[twoWay.size / 2]
        assertEquals(
            "a one-lane-each-way street drew the vehicle ${"%.2f".format(median)} m " +
                "off the centreline, not a half-carriageway",
            VectorStyle.LANE_WIDTH_M / 2, median, 0.05,
        )
    }

    @Test
    fun `N - a one-way carriageway is left exactly where it was`() {
        // The other half of the same trace. On a one-way way `lanes` counts the
        // one direction that exists, so the way centreline IS the carriageway
        // centreline and there is nothing to correct. A model that offset these
        // too would be moving 96% of Qatar's arterial network off its own road.
        //
        // Scoped to maneuvers that claim no lane. A TURN on a multi-lane
        // carriageway now claims one — the lane it is made from, see
        // `RouteLanes.outerLaneOf` — and that placement is inside the
        // carriageway by construction, which the assertion below checks rather
        // than assumes. Everything the original guarantee was about (the
        // carriageway itself, and every through) is unchanged and still
        // asserted exactly.
        val plan = ScenarioTraces.twoWay()
        val (h, lat) = lateralOf(plan, ScenarioTraces.twoWayDrive(plan))
        val rows = h.samples.filter {
            it.phase == Phase.NAVIGATING && !it.offRoute && it.puck != null
        }
        var checked = 0
        var turns = 0
        for ((i, v) in lat.withIndex()) {
            val r = rows.getOrNull(i) ?: continue
            val m = plan.maneuvers.firstOrNull { it.cumulativeM >= r.truthRouteAlongM } ?: continue
            val legStart = plan.maneuvers.lastOrNull { it.cumulativeM < m.cumulativeM }?.cumulativeM ?: 0.0
            val clear = r.truthRouteAlongM > legStart + 60.0 && r.truthRouteAlongM < m.cumulativeM - 60.0
            val fwd = m.forwardLanes
            if (!clear || fwd == null || fwd != m.approachLanes) continue
            val claimsALane = dev.vector.geo.RouteLanes.Turn.of(m.type) !=
                dev.vector.geo.RouteLanes.Turn.NONE && fwd >= 2
            if (claimsALane) {
                assertTrue(
                    "a $fwd-lane one-way carriageway drew the vehicle ${"%.2f".format(v)} m " +
                        "outside its own ${fwd * VectorStyle.LANE_WIDTH_M} m",
                    Math.abs(v) <= fwd * VectorStyle.LANE_WIDTH_M / 2 + 0.01,
                )
                turns++
            } else {
                assertEquals(
                    "a one-way ${fwd}-lane carriageway moved the vehicle " +
                        "${"%.2f".format(v)} m sideways",
                    0.0, v, 0.05,
                )
            }
            checked++
        }
        assertTrue("no settled one-way samples were checked at all", checked >= 20)
        // The rule must be reached by real data on this trace, or the branch
        // above is a claim about a case that never occurs.
        assertTrue("no turning leg on a multi-lane one-way carriageway was sampled", turns > 0)
    }

    @Test
    fun `N - the offset moves like a lane change, not like a teleport`() {
        // Asserted against the MODEL's own profile along the driven distance
        // rather than against the puck's projection, and the difference is
        // worth recording. Projecting the puck onto the route is ill-conditioned
        // near a corner: on this trace two consecutive samples 250 ms apart sat
        // at the same along-route distance (the vehicle was mid-turn) while the
        // tracker was still converging from its first lock, and the projected
        // lateral therefore swung 1.26 m between them. Over the same pair the
        // model's offset moved 0.048 m and `puckErrorM` fell from 9.81 m to
        // 1.93 m — so the swing was the tracker settling, seen through a
        // measurement that loses resolution at a bend, and not the ribbon
        // jumping.
        //
        // What a driver would actually see jump is the OFFSET as a function of
        // distance travelled, which is exactly what this measures.
        val plan = ScenarioTraces.twoWay()
        val profile = dev.vector.geo.RouteLanes.plan(
            plan.maneuvers.map {
                dev.vector.geo.RouteLanes.Approach(
                    atM = it.cumulativeM,
                    forwardLanes = it.forwardLanes,
                    totalLanes = it.approachLanes,
                    lanes = it.lanes,
                )
            }
        )
        var prev = profile.offsetAt(0.0)
        var worst = 0.0
        var d = 1.0
        while (d <= plan.geometryLengthM) {
            val now = profile.offsetAt(d)
            worst = maxOf(worst, abs(now - prev))
            prev = now
            d += 1.0
        }
        // One metre of travel may move the route at most one lane width over a
        // taper length — i.e. there is no step anywhere on the real route.
        val perMetre = VectorStyle.LANE_WIDTH_M / dev.vector.geo.RouteLanes.TAPER_M
        assertTrue(
            "the route moves ${"%.3f".format(worst)} m sideways per metre driven, " +
                "which is a jump and not a lane change",
            worst <= perMetre + 1e-6,
        )
    }

    @Test
    fun `N - placing the vehicle laterally does not disturb navigation`() {
        // The regression half. Stage 4 moves the drawn vehicle, and everything
        // that decides WHERE it is on the route — snapping, the countdown, the
        // off-route test, arrival — must be untouched by that.
        val plan = ScenarioTraces.twoWay()
        val h = drive(plan, ScenarioTraces.twoWayDrive(plan))
        assertEquals("the drive did not arrive", 1, h.arrivals().size)
        assertEquals("a clean residential drive asked for a reroute",
            0, h.reroutesRequested().size)
        assertTrue("the vehicle went off route on its own route",
            h.samples.none { it.offRoute })
        // The puck error is measured against the TRUTH, so it now includes the
        // deliberate 1.75 m lateral placement — which is inside the noise this
        // budget already allows, and is the point: the offset is smaller than
        // the uncertainty in the position it is applied to.
        val err = h.puckErrorM()
        assertTrue("puck error p95 ${"%.1f".format(pct(err, 0.95))} m",
            pct(err, 0.95) < 35.0)
        // And the maneuvers still advance in order.
        val seen = h.events.filterIsInstance<DriveHarness.Event.ManeuverChanged>().map { it.index }
        assertEquals("the maneuvers did not advance in order", seen.sorted(), seen)
    }

    // =======================================================================
    // Scenario P — roundabouts (V7 Stage 4)
    //
    // Here to prove a REFUSAL as much as a claim. OSM records no lane
    // connectivity through a roundabout ring, so Vector must place the APPROACH
    // and say nothing about which ring lane leads to which exit — the one
    // junction type where being in the wrong lane is hardest to correct, and
    // therefore the worst possible place to guess.
    // =======================================================================

    @Test
    fun `P - a roundabout approach is placed and the ring is not`() {
        val plan = ScenarioTraces.roundabout()
        val (h, lat) = lateralOf(plan, ScenarioTraces.roundaboutDrive(plan))
        assertEquals("the roundabout drive did not arrive", 1, h.arrivals().size)
        assertEquals(0, h.reroutesRequested().size)

        // Never in oncoming, anywhere, including through both rings.
        assertTrue(
            "the vehicle was drawn ${"%.2f".format(-lat.min())} m into oncoming traffic",
            lat.min() > -0.01,
        )

        val rows = h.samples.filter {
            it.phase == Phase.NAVIGATING && !it.offRoute && it.puck != null
        }
        // The two-way approach (`forward_lanes=1, approach_lanes=2`) IS placed.
        val approach = ArrayList<Double>()
        // The ring steps are `roundabout`, and both have `forward_lanes ==
        // approach_lanes`, so the model's only honest answer is the carriageway
        // centre — never a lane within the ring.
        val ring = ArrayList<Double>()
        for ((i, v) in lat.withIndex()) {
            val r = rows.getOrNull(i) ?: continue
            val m = plan.maneuvers.firstOrNull { it.cumulativeM >= r.truthRouteAlongM } ?: continue
            val legStart = plan.maneuvers.lastOrNull { it.cumulativeM < m.cumulativeM }?.cumulativeM ?: 0.0
            if (r.truthRouteAlongM <= legStart + 40.0 || r.truthRouteAlongM >= m.cumulativeM - 40.0) continue
            if (m.type == "roundabout") ring.add(v)
            else if (m.forwardLanes == 1 && m.approachLanes == 2) approach.add(v)
        }
        assertTrue("no settled samples on the two-way approach", approach.size >= 10)
        assertEquals(
            "the roundabout approach was not placed on its own carriageway",
            VectorStyle.LANE_WIDTH_M / 2, approach.sorted()[approach.size / 2], 0.05,
        )
        assertTrue("no samples on a roundabout leg", ring.size >= 10)
        for (v in ring) {
            assertEquals(
                "the vehicle was placed ${"%.2f".format(v)} m across a roundabout — " +
                    "OSM has no ring-lane connectivity to support that",
                0.0, v, 0.05,
            )
        }
    }

    // =======================================================================
    // Stage 4's refusal, on the fixtures that predate it
    // =======================================================================

    @Test
    fun `a route the backend has no lane counts for is drawn exactly as before Stage 4`() {
        // The eight original fixtures were captured before
        // `lane_data.forward_lanes` existed, so every step reads "this backend
        // does not know". That is not a gap in coverage — it is the decline
        // path, on four real Doha routes, and it has to be pixel-identical to
        // the pre-Stage-4 behaviour or Stage 4 changed the appearance of every
        // journey in the corpus for nothing.
        for ((name, plan, fixes) in listOf(
            Triple("city", ScenarioTraces.city(), ScenarioTraces.a()),
            Triple("dense", ScenarioTraces.dense(), ScenarioTraces.k()),
            Triple("slip-split", ScenarioTraces.slipSplit(), ScenarioTraces.slip()),
            Triple("highway", ScenarioTraces.highway(), ScenarioTraces.j()),
        )) {
            val (_, lat) = lateralOf(plan, fixes)
            val worst = lat.maxOf { abs(it) }
            assertTrue(
                "$name has no forward lane counts, yet the vehicle moved " +
                    "${"%.3f".format(worst)} m off the centreline",
                worst < 0.01,
            )
        }
    }

    @Test
    fun `the slip-split lane strip and the map do not contradict each other`() {
        // `slip-split-corniche` is the one route in the corpus whose approach
        // narrows the lane choice, and it is the case V7 §4.6 was written
        // about. It has `approach_lanes=4` and NO `forward_lanes`, so the strip
        // shows four lanes with the outside one lit and the map claims nothing.
        //
        // That is the correct pairing and worth pinning: a map that placed the
        // ribbon in the slip lane from `approach_lanes` alone would be reading a
        // BOTH-DIRECTIONS count as a driven-carriageway count, which is the
        // precise error `forward_lanes` was added to prevent. Silence here is
        // the honest answer until the fixture is re-captured against a backend
        // that carries the field.
        val plan = ScenarioTraces.slipSplit()
        val slip = plan.maneuvers.first { it.type == "slight-right" }
        assertEquals("the fixture no longer carries the 4-lane approach",
            4, slip.approachLanes)
        assertEquals("the fixture unexpectedly has a forward lane count now",
            null, slip.forwardLanes)
        assertTrue("the lane strip stopped narrowing the choice",
            slip.lanes.count { it.valid } in 1 until slip.lanes.size)

        val (_, lat) = lateralOf(plan, ScenarioTraces.slip(plan))
        assertTrue(
            "the map placed the route laterally from a both-directions lane count",
            lat.maxOf { abs(it) } < 0.01,
        )
    }

}
