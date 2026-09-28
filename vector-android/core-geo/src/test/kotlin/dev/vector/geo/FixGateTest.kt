package dev.vector.geo

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plausibility gate (V5).
 *
 * The behaviour under test is small and the consequences of getting it wrong
 * are not symmetric, which is why each half has its own cases: a gate that is
 * too permissive costs a journey (one reflected fix produced 26 reroutes and a
 * remaining distance that moved 3 032 m between samples), and a gate that is
 * too strict costs the driver their position entirely, which is worse.
 */
class FixGateTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    private fun at(eastM: Double, northM: Double = 0.0) =
        LngLat(lng0 + eastM / kx, lat + northM / RouteGeometry.M_PER_DEG_LAT)

    @Test
    fun `the first fix of a session is always accepted`() {
        val g = FixGate()
        val r = g.accept(at(0.0), 0L)
        assertEquals(FixGate.Verdict.FIRST, r.verdict)
        assertTrue(r.accept)
    }

    @Test
    fun `ordinary driving passes`() {
        val g = FixGate()
        g.accept(at(0.0), 0L)
        // 25 m/s, one second apart. Ninety km/h.
        for (i in 1..20) {
            val r = g.accept(at(i * 25.0), i * 1_000L)
            assertEquals(FixGate.Verdict.ACCEPTED, r.verdict, "fix $i was questioned")
        }
        assertEquals(0, g.droppedCount)
    }

    @Test
    fun `a 300 m step in one second is rejected`() {
        // 1 080 km/h. This is the exact shape of the multipath fix that cost a
        // whole simulated journey.
        val g = FixGate()
        g.accept(at(0.0), 0L)
        val r = g.accept(at(300.0), 1_000L)
        assertEquals(FixGate.Verdict.REJECTED_IMPLAUSIBLE, r.verdict)
        assertFalse(r.accept)
        assertTrue(r.impliedMs > 250.0, "implied ${r.impliedMs} m/s")
        assertEquals(1, g.droppedCount)
    }

    @Test
    fun `a rejected fix does not become the new reference`() {
        // If it did, the gate would then reject every GOOD fix for disagreeing
        // with the bad one, which is how a plausibility check locks an app out
        // of its own position.
        val g = FixGate()
        g.accept(at(0.0), 0L)
        g.accept(at(300.0), 1_000L)
        val back = g.accept(at(25.0), 2_000L)
        assertTrue(back.accept, "the fix after a rejection must be judged against the good one")
        assertEquals(FixGate.Verdict.ACCEPTED, back.verdict)
    }

    @Test
    fun `a sustained disagreement is eventually believed`() {
        // The escape hatch. A receiver that has contradicted us three times
        // running is more likely to be right than the reference we are holding
        // — a device carried onto a train, or a first fix that was itself the
        // bad one.
        val g = FixGate()
        g.accept(at(0.0), 0L)
        val verdicts = (1..5).map { g.accept(at(300.0 + it, 200.0), it * 1_000L).verdict }
        assertTrue(verdicts.contains(FixGate.Verdict.ACCEPTED_AFTER_RUN),
            "never gave in: $verdicts")
        assertEquals(3, verdicts.count { it == FixGate.Verdict.REJECTED_IMPLAUSIBLE })
    }

    @Test
    fun `a long gap makes a distant fix plausible again`() {
        // The tunnel case, and the reason the gate needs no special exemption
        // for it: 700 m after 25 s is 100 km/h, which is just driving.
        val g = FixGate()
        g.accept(at(0.0), 0L)
        val r = g.accept(at(700.0), 25_000L)
        assertTrue(r.accept, "implied ${r.impliedMs} m/s after a 25 s outage")
        assertEquals(FixGate.Verdict.ACCEPTED, r.verdict)
    }

    @Test
    fun `jitter at a standstill is never questioned`() {
        // Two fixes in the same millisecond, or a metre apart in nothing flat,
        // must not produce an infinite implied speed and a rejection. A
        // stationary phone delivers exactly this.
        val g = FixGate()
        g.accept(at(0.0), 0L)
        assertTrue(g.accept(at(3.0, 2.0), 0L).accept, "same-millisecond fix rejected")
        assertTrue(g.accept(at(-2.0, 4.0), 10L).accept, "10 ms apart and 5 m away rejected")
        assertEquals(0, g.droppedCount)
    }

    @Test
    fun `an out-of-order fix is not treated as a teleport`() {
        // A negative interval carries no evidence about speed. Out-of-order
        // delivery is a transport problem, not a physics one.
        val g = FixGate()
        g.accept(at(0.0), 10_000L)
        assertTrue(g.accept(at(400.0), 9_000L).accept)
    }

    @Test
    fun `reset forgets the reference and the count`() {
        val g = FixGate()
        g.accept(at(0.0), 0L)
        g.accept(at(300.0), 1_000L)
        assertEquals(1, g.droppedCount)
        g.reset()
        assertEquals(0, g.droppedCount)
        assertEquals(FixGate.Verdict.FIRST, g.accept(at(5_000.0), 2_000L).verdict)
    }
}
