package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.walk.WalkCameraDecision
import dev.vector.geo.walk.WalkCameraInputs
import dev.vector.geo.walk.WalkCameraPolicy
import dev.vector.geo.walk.WalkCameraRegime
import dev.vector.geo.walk.WalkFollowState
import dev.vector.geo.walk.WalkFollower
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkPuck
import dev.vector.geo.walk.WalkRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The walking camera, puck and follower, driven over REAL Qatar walks
 * (V7.4 4C.2).
 *
 * `WalkCameraTest` and `WalkFollowerTest` in core-geo state the rules on
 * synthetic geometry where every distance is exact. This file is the evidence
 * they survive contact with OSM: the same classes, walked end to end over the
 * six captured `/foot` payloads from the real 260912 bake — 53 m to 25.5 km,
 * 182 maneuvers, 18 crossings, one staircase, one barrier detour, and one
 * route that doubles back to within 20.8 m of itself.
 *
 * Each walk is simulated at 1 Hz and 1.35 m/s (the backend's own
 * `walk_speed_ms`), which is what makes "how often does the camera write"
 * a real number rather than a property of the sampling.
 */
class WalkRealQatarCameraTest {

    private fun contract(name: String) = WalkContractParser.parse(
        JSONObject(
            javaClass.getResourceAsStream("/vector-contract/$name")
                ?.readBytes()?.decodeToString()
                ?: throw AssertionError("missing contract snapshot $name")
        ).getJSONArray("features").getJSONObject(0)
    )

    private fun route(name: String) = WalkRoute.of(contract(name))

    /** The backend's own walking pace. */
    private val paceMs = 1.35

    /**
     * Walk a real route at 1 Hz, feeding the follower actual positions taken
     * from the route geometry, and collect every camera decision.
     *
     * [lateralM] supplies a per-fix offset, for the jitter cases.
     */
    private fun simulate(
        name: String,
        lateralM: (Int) -> Double = { 0.0 },
        stopAtM: Double? = null,
    ): Sim {
        val r = route(name)
        val follower = WalkFollower(r)
        val policy = WalkCameraPolicy()
        val sim = Sim(r)
        var t = 0L
        var d = 0.0
        val end = stopAtM ?: r.totalM
        var i = 0
        while (d <= end) {
            t += 1000L
            val at = r.index!!.pointAt(d)!!
            val off = lateralM(i)
            val p = if (off == 0.0) at.position
            else RouteGeometry.offsetPoint(at.position, at.bearing, off)

            val fix = follower.onFix(p, t)
            sim.fixes.add(fix)
            val progress = fix.progress
            if (progress != null) {
                val decision = policy.decide(WalkCameraInputs.of(progress, fix.state))
                sim.regimes.add(policy.currentRegime)
                if (decision is WalkCameraDecision.Transition) sim.writes.add(decision)
            }
            WalkPuck.of(fix)?.let { sim.pucks.add(it) }
            d += paceMs
            i++
        }
        return sim
    }

    private class Sim(val route: WalkRoute) {
        val fixes = ArrayList<dev.vector.geo.walk.WalkFix>()
        val regimes = ArrayList<WalkCameraRegime>()
        val writes = ArrayList<WalkCameraDecision.Transition>()
        val pucks = ArrayList<dev.vector.geo.walk.WalkPuckState>()
    }

    // ------------------------------------------------------------- the walks

    @Test
    fun `every real walk stays matched from start to finish`() {
        for (name in FIXTURES) {
            val sim = simulate(name)
            val off = sim.fixes.count { it.state != WalkFollowState.ON_ROUTE }
            assertEquals(
                "$name: a clean walk down its own geometry left the route $off times",
                0, off,
            )
            assertEquals(
                "$name: not every fix produced a puck",
                sim.fixes.size, sim.pucks.size,
            )
            assertTrue("$name: every puck must be confident on a clean walk",
                sim.pucks.all { it.confident })
        }
    }

    @Test
    fun `the camera settles on every real walk instead of pumping`() {
        // The property that matters most. A camera recomputing a zoom from the
        // distance to the next event would write on most of these fixes; the
        // regime model writes a handful of times on a 25 km walk.
        //
        // Measured against FIXES rather than per kilometre. A per-km rate is
        // meaningless on a short walk — the 52.9 m fixture produces exactly
        // ONE write, which is the fewest possible, and that reads as 18.9/km.
        // A metric that calls the minimum a failure is the wrong metric.
        for (name in FIXTURES) {
            val sim = simulate(name)
            val fixes = sim.fixes.size
            val writes = sim.writes.size
            assertTrue("$name: the camera never wrote at all", writes >= 1)
            assertTrue(
                "$name: $writes camera writes over $fixes fixes is not settled",
                writes <= 1 + fixes / 20,
            )
        }
    }

    @Test
    fun `real crossings put the camera in the SPAN regime`() {
        val sim = simulate("foot-crossing-4b4.json")
        // Three real crossings, spans 49.1 / 70.9 / 88.1 m.
        assertTrue("no SPAN regime on a crossing-heavy walk",
            sim.regimes.contains(WalkCameraRegime.SPAN))
        // And the walker is reported ON the crossing for its whole length:
        // 208.1 m of crossing at 1.35 m/s is ~154 fixes.
        val onCrossing = sim.pucks.count { it.onCrossing }
        assertTrue("only $onCrossing fixes on 208.1 m of crossing",
            onCrossing >= 140)
    }

    @Test
    fun `the real staircase puts the camera in the SPAN regime`() {
        val sim = simulate("foot-stairs-4b4.json")
        val onStairs = sim.pucks.count { it.onStairs }
        // A 5.9 m staircase at 1.35 m/s is ~4 fixes. Short, but it must exist.
        assertTrue("the 5.9 m staircase was never reported", onStairs >= 3)
        assertTrue(sim.regimes.contains(WalkCameraRegime.SPAN))
    }

    @Test
    fun `approaching a real turn enters APPROACH before it, not after`() {
        val r = route("foot-ordinary-4b4.json")
        val policy = WalkCameraPolicy()
        // The ordinary walk's first turn is a turn_right at 33.5 m; take a
        // later, isolated one: turn_left at 209.5 m, 66.8 m after the one
        // before it, so there is a genuine cruise phase in between.
        val turn = r.plan.first { it.distanceM == 440.3 }
        assertEquals(WalkManeuverKind.TURN_LEFT, turn.kind)

        // The maneuver spacing on this real walk is tight — eight of its ten
        // events are inside 60 m of another — so the cruise sample has to be
        // chosen from the geometry rather than assumed. At 350 m the next
        // event is 90.3 m away (and 233 m of route remain, so not arrival):
        // genuinely cruise.
        policy.decide(WalkCameraInputs.of(r.progressAt(350.0)))
        assertEquals(WalkCameraRegime.CRUISE, policy.currentRegime)
        // At 400 m it is 40.3 m away: inside the 60 m entry.
        policy.decide(WalkCameraInputs.of(r.progressAt(400.0)))
        assertEquals(WalkCameraRegime.APPROACH, policy.currentRegime)
    }

    @Test
    fun `every real walk ends in the ARRIVAL regime`() {
        for (name in FIXTURES) {
            val sim = simulate(name)
            assertEquals(
                "$name did not finish in ARRIVAL",
                WalkCameraRegime.ARRIVAL, sim.regimes.last(),
            )
            // And the last puck is at the end of the route.
            assertEquals("$name: route not completed", 0.0, sim.pucks.last().remainingM, 2.0)
            assertEquals(1.0, sim.pucks.last().fraction, 0.01)
        }
    }

    @Test
    fun `the shortest real walk still gets a camera and an arrival`() {
        // 52.9 m, plan = depart + arrive, nothing to say. The camera must
        // still place the walker rather than declining because the walk is
        // trivial.
        val sim = simulate("foot-short-4b4.json")
        assertTrue(sim.writes.isNotEmpty())
        assertEquals(WalkCameraRegime.ARRIVAL, sim.regimes.last())
        // The whole walk is inside ARRIVAL_M of the end, so it is arrival
        // from the first fix — which is correct, not a bug.
        assertTrue(sim.regimes.all {
            it == WalkCameraRegime.ARRIVAL || it == WalkCameraRegime.APPROACH
        })
    }

    @Test
    fun `the barrier detour walks its full honest length`() {
        // 4A.3 case A: a locked gate 64 m away forces a 2,504 m walk. Nothing
        // in the camera or follower may shortcut it.
        val sim = simulate("foot-barrier-4b4.json")
        assertEquals(2504.2, sim.pucks.last().traversedM, 5.0)
        assertEquals(38.9, sim.route.contract.diagnostics.detourRatio!!, 0.05)
    }

    @Test
    fun `the long walk holds one settled camera over 22 kilometres`() {
        val sim = simulate("foot-long-4b4.json")
        assertTrue(sim.fixes.size > 16_000)
        // 60 maneuvers over 22.4 km, and the camera writes a few dozen times
        // at most — not once per maneuver approach and exit.
        assertTrue("22 km produced ${sim.writes.size} camera writes",
            sim.writes.size < 150)
    }

    // --------------------------------------------------- doubled-back geometry

    @Test
    fun `a real doubled-back walk never jumps to the return leg`() {
        // The ordinary fixture's closest self-approach is 20.8 m (measured at
        // >=50 m of along-route separation), and it ends with a uturn at
        // 579.1 m — 4.1 m before arrival. Both passes are inside the 25 m
        // on-route radius, so the ONLY thing preventing a jump is the search
        // window derived from walking pace.
        val sim = simulate("foot-ordinary-4b4.json")
        var last = -1.0
        for (f in sim.fixes) {
            val a = f.progress!!.alongM
            assertTrue(
                "along-route position jumped backwards from $last to $a",
                a >= last - 1.0,
            )
            last = a
        }
    }

    @Test
    fun `lateral drift toward a doubled-back leg does not relocate the walker`() {
        // Same walk, now with 8 m of lateral drift on every fix — enough to
        // reach toward the 20.8 m-distant return leg, and well inside the
        // on-route radius so the projection will happily accept it.
        val sim = simulate("foot-ordinary-4b4.json", lateralM = { i ->
            if (i % 2 == 0) 8.0 else -8.0
        })
        var last = -1.0
        for (f in sim.fixes) {
            val a = f.progress!!.alongM
            // The tolerance IS the documented backward rate limit — a walker
            // may go back at most `pace * dt * slack` = 1.35 * 1 * 1.5 m in
            // one fix. Asserting anything tighter would be asserting against
            // the constant rather than against the behaviour, and the first
            // version of this test did exactly that: it failed on a backward
            // step of 2.025000 m, which is the limit to six decimal places.
            assertTrue("drift moved the walker backwards: $last -> $a", a >= last - BACK_STEP_M)
            last = a
        }
        assertEquals(583.2, sim.pucks.last().traversedM, 5.0)
    }

    // ------------------------------------------------------------ GPS jitter

    @Test
    fun `simulated GPS jitter does not make the camera oscillate`() {
        // 12 m of random lateral jitter, which is the ordinary urban-canyon
        // error this codebase has already measured (~15 m). The camera must
        // not respond to it at all.
        val rng = Random(13)
        val clean = simulate("foot-crossing-4b4.json")
        val noisy = simulate("foot-crossing-4b4.json", lateralM = {
            rng.nextDouble(-12.0, 12.0)
        })
        // Jitter may legitimately change a write or two at a boundary; it must
        // not multiply them.
        assertTrue(
            "jitter took camera writes from ${clean.writes.size} to ${noisy.writes.size}",
            noisy.writes.size <= clean.writes.size + 4,
        )
        // And the walker stays on the route throughout.
        assertTrue(noisy.fixes.all { it.state != WalkFollowState.OFF_ROUTE })
    }

    @Test
    fun `jitter never drives the walker backwards along a real route`() {
        val rng = Random(29)
        val sim = simulate("foot-stairs-4b4.json", lateralM = {
            rng.nextDouble(-12.0, 12.0)
        })
        var last = -1.0
        for (f in sim.fixes) {
            val a = f.progress!!.alongM
            assertTrue("jitter moved the walker backwards: $last -> $a", a >= last - BACK_STEP_M)
            last = a
        }
        // And the whole 25.5 km is still walked: the limit clips corner slip
        // without ever stalling forward progress.
        assertEquals(25_509.6, sim.pucks.last().traversedM, 20.0)
    }

    // ------------------------------------------------------------- off-route

    @Test
    fun `leaving a real route is detected, and rejoining recovers`() {
        val r = route("foot-crossing-4b4.json")
        val follower = WalkFollower(r)
        var t = 0L
        // Walk the first 200 m honestly.
        var d = 0.0
        while (d < 200.0) {
            t += 1000
            follower.onFix(r.index!!.pointAt(d)!!.position, t)
            d += paceMs
        }
        assertEquals(WalkFollowState.ON_ROUTE, follower.followState)

        // Now walk away from the route, perpendicular — the case that has no
        // along-route progress at all.
        val at200 = r.index!!.pointAt(200.0)!!
        var lateral = 0.0
        var state = follower.followState
        repeat(12) {
            t += 1000
            lateral += 8.0
            state = follower.onFix(
                RouteGeometry.offsetPoint(at200.position, at200.bearing, lateral), t,
            ).state
        }
        assertEquals(WalkFollowState.OFF_ROUTE, state)

        // And rejoin, 300 m further along than where they left.
        t += 1000
        val back = follower.onFix(r.index!!.pointAt(500.0)!!.position, t)
        assertEquals(WalkFollowState.ON_ROUTE, back.state)
        assertEquals(500.0, follower.alongM()!!, 2.0)
    }

    @Test
    fun `off-route widens the real camera and drops the walking puck`() {
        val r = route("foot-crossing-4b4.json")
        val policy = WalkCameraPolicy()
        val cruise = policy.decide(
            WalkCameraInputs.of(r.progressAt(400.0), WalkFollowState.ON_ROUTE)
        ) as WalkCameraDecision.Transition
        val off = policy.decide(
            WalkCameraInputs.of(r.progressAt(400.0), WalkFollowState.OFF_ROUTE)
        ) as WalkCameraDecision.Transition
        assertEquals(WalkCameraRegime.OFF_ROUTE, off.regime)
        assertTrue("off-route must widen, not tighten",
            off.targetZoom < cruise.targetZoom)
        assertNull("off-route must assert no bearing", off.bearingDeg)
        // And no walking puck is drawn: the caller shows the raw fix.
        assertNull(
            WalkPuck.of(
                dev.vector.geo.walk.WalkFix(
                    WalkFollowState.OFF_ROUTE, r.progressAt(400.0),
                    r.index!!.pointAt(400.0)!!.position, 90.0, 0.2, false,
                )
            )
        )
    }

    // ------------------------------------------------------------- the puck

    @Test
    fun `the puck carries the real segment facts under the walker`() {
        val sim = simulate("foot-crossing-4b4.json")
        val classes = sim.route.contract.segments.classes
        for (p in sim.pucks) {
            val i = p.segmentIndex!!
            assertEquals(
                "the puck's segment class must come from the aligned array",
                classes.getOrNull(i), p.segmentClass,
            )
        }
        // The crossing-heavy walk really does walk on footways and roads.
        assertTrue(sim.pucks.mapNotNull { it.segmentClass }.toSet().size >= 2)
    }

    @Test
    fun `the puck is never offset from the real route line`() {
        val sim = simulate("foot-crossing-4b4.json")
        val idx = sim.route.index!!
        for (p in sim.pucks) {
            val proj = idx.project(p.position)!!
            assertEquals(
                "the walking puck must sit ON the route line",
                0.0, proj.offsetM, 0.01,
            )
        }
    }

    @Test
    fun `the puck bearing follows the real route geometry`() {
        val sim = simulate("foot-ordinary-4b4.json")
        assertTrue(sim.pucks.all { it.bearingDeg != null })
        // The walk turns: the bearing genuinely varies rather than being a
        // constant nobody derived.
        val bearings = sim.pucks.mapNotNull { it.bearingDeg }
        assertTrue(bearings.max() - bearings.min() > 90.0)
    }

    // ---------------------------------------------------------- determinism

    @Test
    fun `identical real walks produce identical camera and puck output`() {
        for (name in listOf("foot-crossing-4b4.json", "foot-ordinary-4b4.json")) {
            val a = simulate(name)
            val b = simulate(name)
            assertEquals(a.writes.size, b.writes.size)
            for (i in a.writes.indices) assertEquals("$name write $i", a.writes[i], b.writes[i])
            assertEquals(a.regimes, b.regimes)
            for (i in a.pucks.indices) assertEquals("$name puck $i", a.pucks[i], b.pucks[i])
        }
    }

    companion object {
        /**
         * The furthest the held position may legitimately move backwards in
         * one 1 Hz fix: `WALK_PACE_MS * dt * BACKWARD_SLACK`.
         *
         * Derived from the follower's own constants rather than restated, so
         * a change to either is caught here instead of silently loosening
         * these assertions.
         */
        val BACK_STEP_M =
            WalkFollower.WALK_PACE_MS * 1.0 * WalkFollower.BACKWARD_SLACK + 0.01

        /** Every real Qatar walking payload captured for 4C.1/4C.2. */
        val FIXTURES = listOf(
            "foot-ordinary-4b4.json",
            "foot-crossing-4b4.json",
            "foot-stairs-4b4.json",
            "foot-barrier-4b4.json",
            "foot-short-4b4.json",
            "foot-long-4b4.json",
        )
    }
}
