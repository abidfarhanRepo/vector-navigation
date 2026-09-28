package dev.vector.geo.signal

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex
import kotlin.math.cos
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The per-tick cost of the signal model (V7 Stage 5).
 *
 * The cadence rule is that signal computation happens ONCE PER ROUTE (profile
 * construction) and once per navigation TICK (next-signal + arrival window +
 * phase prediction), never per display frame. These budgets pin the LOGIC side
 * of that claim the same way [NavLoopPerfTest] pins the loop's: wide margins,
 * JVM-only, catching the "someone made next() O(n)" regression class.
 *
 * What they deliberately do NOT claim: frame rate, battery or rendering cost —
 * those need the device, and the device step of this stage runs its own check.
 */
class SignalCadencePerfTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    private fun lonAt(eastM: Double) = lng0 + eastM / kx

    private fun longRoute(meters: Double = 20_000.0, vertices: Int = 1001): RouteIndex {
        return RouteGeometry.index(
            (0 until vertices).map { i -> LngLat(lonAt(meters * i / (vertices - 1)), lat) },
        )!!
    }

    private fun profileOf(route: RouteIndex, n: Int): SignalProfile {
        val refs = (0 until n).map { i ->
            SignalRef("n$i", LngLat(lonAt(100.0 + i * 300.0), lat), "osm:node:n$i")
        }
        return SignalMatcher.match(route, refs)
    }

    private fun tickCost(profile: SignalProfile, now: Long): Double {
        // The three calls NavSession.withSignal performs per tick, timed as a
        // unit because they are a unit.
        val ns = measureNanoTime {
            repeat(50_000) {
                val next = profile.next(12_345.0 + (it % 100))
                if (next != null) {
                    val remaining = next.approach.alongM - 12_345.0
                    val window = ArrivalWindow.of(remaining, 10.0, 12.0, false)
                    SignalTiming.predict(next.ref.id, TimingModel.None,
                        now + (window.etaS * 1000.0).toLong(), window.uncertaintyS, now)
                }
            }
        }
        return ns.toDouble() / 50_000.0
    }

    @Test
    fun `a navigation tick with a sixty-signal route costs microseconds`() {
        val route = longRoute()
        val profile = profileOf(route, 60)
        // At 1 Hz there is 1,000,000 ns per tick and the network + rendering
        // want nearly all of it; the signal half of the tick should be trivial.
        val perTick = tickCost(profile, 1_700_000_000_000L)
        assertTrue(perTick < 50_000.0, // 50 us
            "per-tick signal cost ${perTick / 1e3} us — must stay far under the 1 Hz budget")
    }

    @Test
    fun `profile construction scales with signals, built once per route`() {
        val route = longRoute()
        // Warm the JIT first: this work happens once per ROUTE (at apply
        // time, after a network round-trip), so a cold-interpreter number is
        // not the number that matters — the steady-state climb is.
        repeat(10) { profileOf(route, 60) }
        val tiny = measureNanoTime { profileOf(route, 2) }
        val many = measureNanoTime { profileOf(route, 60) }
        // 60 signals on a 1000-vertex route, built once per route at route
        // apply: a few milliseconds beside a route request that takes
        // hundreds, and nothing the frame loop ever sees.
        assertTrue(many < 5_000_000.0, // 5 ms
            "profile build cost ${many / 1e6} ms for 60 signals")
        // And it is per-ROUTE work, not per-frame: the ratio is a smell check
        // (a super-linear blow-up would flag an accidental nested scan).
        assertTrue(many < tiny * 300, "profiles must scale sub-linearly-ish")
    }
}