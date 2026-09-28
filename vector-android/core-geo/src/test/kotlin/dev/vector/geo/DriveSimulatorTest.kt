package dev.vector.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator has to be trusted before anything can be judged with it.
 *
 * V5 §18's instruction is that a test must be "capable of exposing a bug that
 * would occur in a real car". A generator that teleports, that produces
 * impossible accelerations, or whose noise is not actually there would produce
 * a scenario suite full of tests that pass for the wrong reason — so this file
 * asserts the physical plausibility of the stream itself, separately from any
 * assertion about how Vector responds to it.
 */
class DriveSimulatorTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    /** A straight 4 km east run with shape points every 100 m. */
    private val straight = (0..40).map { LngLat(lng0 + (it * 100.0) / kx, lat) }

    private fun plan(
        legs: List<DriveSimulator.Leg>,
        noise: DriveSimulator.Noise = DriveSimulator.Noise.CLEAN,
        faults: List<DriveSimulator.Fault> = emptyList(),
        hz: Double = 1.0,
        jitter: Double = 0.0,
        seed: Long = 7L,
        path: List<LngLat> = straight,
    ) = DriveSimulator.DrivePlan(
        path = path, legs = legs, hz = hz, intervalJitter = jitter,
        noise = noise, faults = faults, seed = seed,
    )

    @Test
    fun `a drive produces fixes at the requested rate`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.cruise(60.0)))
        assertTrue(fixes.size > 200, "4 km at 60 km/h is ~240 s; got ${fixes.size} fixes")
        val gaps = fixes.zipWithNext { a, b -> b.tMs - a.tMs }
        // No jitter requested, so every gap is the integration step's rounding
        // of one second and nothing else.
        assertTrue(gaps.all { it in 950..1100 }, "gaps out of range: ${gaps.distinct().sorted()}")
    }

    @Test
    fun `acceleration stays inside what a car can do`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.stopAndGo(cycles = 2)))
        var worst = 0.0
        fixes.zipWithNext { a, b ->
            val dt = (b.tMs - a.tMs) / 1000.0
            val va = a.speedMs ?: 0.0
            val vb = b.speedMs ?: 0.0
            if (dt > 0) worst = maxOf(worst, abs(vb - va) / dt)
        }
        // BRAKE_MS2 is the limit, with headroom for the fact that a fix
        // straddles the boundary between two legs.
        assertTrue(worst <= DriveSimulator.BRAKE_MS2 + 0.5,
            "worst acceleration ${"%.2f".format(worst)} m/s² exceeds a car's")
    }

    @Test
    fun `the stop-and-go ladder actually visits every band`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.stopAndGo(cycles = 1)))
        val kmh = fixes.mapNotNull { it.speedMs }.map { it * 3.6 }
        for (band in listOf(0.0, 5.0, 15.0, 30.0, 50.0, 80.0)) {
            assertTrue(kmh.any { abs(it - band) < 2.0 } || (band == 0.0 && fixes.any { it.speedMs == null }),
                "never reached $band km/h; range ${kmh.minOrNull()}..${kmh.maxOrNull()}")
        }
    }

    @Test
    fun `a stationary vehicle reports no speed and no bearing`() {
        // The floor is what an S24 does at a standstill, and Vector had a defect
        // that only exists when it happens.
        val fixes = DriveSimulator.run(plan(listOf(
            DriveSimulator.Leg.Cruise(200.0, 40.0 / 3.6),
            DriveSimulator.Leg.Stop(20.0),
            DriveSimulator.Leg.Remainder(40.0 / 3.6),
        )))
        assertTrue(fixes.any { it.speedMs == null }, "no fix omitted its speed during the stop")
        assertTrue(fixes.filter { it.speedMs == null }.all { it.bearingDeg == null },
            "a fix with no speed still carried a bearing")
    }

    @Test
    fun `clean noise means the fix is the truth`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.cruise(50.0)))
        val worst = fixes.maxOf { DrivePath.metresBetween(it.position, it.truth) }
        assertTrue(worst < 0.01, "CLEAN produced ${"%.2f".format(worst)} m of error")
    }

    @Test
    fun `urban-canyon drift is correlated, not white`() {
        // This is the property that makes the false-reroute scenario meaningful.
        // White noise of the same magnitude averages out over a few fixes and an
        // off-route threshold tested against it is tested against nothing.
        val fixes = DriveSimulator.run(
            plan(DriveSimulator.cruise(50.0), noise = DriveSimulator.Noise.URBAN_CANYON)
        )
        val err = fixes.map { fix ->
            // Signed across the direction of travel, so sign changes are real.
            val d = DrivePath.metresBetween(fix.position, fix.truth)
            val b = RouteGeometry.bearingDeg(fix.truth, fix.position)
            if (RouteGeometry.angDiffDeg(b, 90.0) > 0) d else -d
        }
        val flips = err.zipWithNext { a, b -> if ((a < 0) != (b < 0)) 1 else 0 }.sum()
        assertTrue(flips < err.size / 3,
            "error changed side $flips times in ${err.size} fixes — that is white noise, not drift")
        assertTrue(err.maxOf { abs(it) } > 6.0, "urban canyon produced no meaningful error")
    }

    @Test
    fun `an outage is a gap in the stream, not a run of stale fixes`() {
        val fixes = DriveSimulator.run(plan(
            DriveSimulator.cruise(50.0),
            faults = listOf(DriveSimulator.Fault.Outage(atS = 30.0, forS = 20.0)),
        ))
        val gap = fixes.zipWithNext { a, b -> b.tMs - a.tMs }.max()
        assertTrue(gap >= 19_000, "largest gap was ${gap} ms; the outage did not happen")
        assertTrue(fixes.none { it.tMs in 31_000..48_000 }, "fixes were delivered during the outage")
    }

    @Test
    fun `a jump displaces the fix and marks it`() {
        val fixes = DriveSimulator.run(plan(
            DriveSimulator.cruise(50.0),
            faults = listOf(DriveSimulator.Fault.Jump(atS = 40.0, forS = 4.0, offsetM = 300.0, towardDeg = 0.0)),
        ))
        val jumped = fixes.filter { it.faulted }
        assertTrue(jumped.isNotEmpty(), "no fix was faulted")
        assertTrue(jumped.all { DrivePath.metresBetween(it.position, it.truth) > 250.0 },
            "a faulted fix was not actually displaced")
        assertTrue(fixes.filterNot { it.faulted }.all { DrivePath.metresBetween(it.position, it.truth) < 5.0 },
            "the jump leaked outside its window")
    }

    @Test
    fun `degradation raises both the error and the reported accuracy`() {
        val fixes = DriveSimulator.run(plan(
            DriveSimulator.cruise(50.0),
            noise = DriveSimulator.Noise.GOOD,
            faults = listOf(DriveSimulator.Fault.Degraded(atS = 20.0, forS = 30.0)),
        ))
        val inside = fixes.filter { it.tMs in 21_000..49_000 }
        val outside = fixes.filter { it.tMs < 19_000 }
        assertTrue(inside.isNotEmpty() && outside.isNotEmpty())
        assertTrue(inside.minOf { it.accuracyM } > outside.maxOf { it.accuracyM },
            "the accuracy field did not degrade")
        assertTrue(inside.maxOf { DrivePath.metresBetween(it.position, it.truth) } >
                   outside.maxOf { DrivePath.metresBetween(it.position, it.truth) },
            "the error did not degrade")
    }

    @Test
    fun `delayed fixes arrive sparsely rather than not at all`() {
        val fixes = DriveSimulator.run(plan(
            DriveSimulator.cruise(50.0),
            faults = listOf(DriveSimulator.Fault.Delay(atS = 30.0, forS = 30.0, everyS = 6.0)),
        ))
        val inside = fixes.filter { it.tMs in 30_000..59_000 }
        assertTrue(inside.size in 3..7, "expected ~5 fixes in the delayed window, got ${inside.size}")
    }

    @Test
    fun `the same seed produces the same drive and a different seed does not`() {
        val a = DriveSimulator.run(plan(DriveSimulator.urban(), noise = DriveSimulator.Noise.URBAN_CANYON, seed = 11))
        val b = DriveSimulator.run(plan(DriveSimulator.urban(), noise = DriveSimulator.Noise.URBAN_CANYON, seed = 11))
        val c = DriveSimulator.run(plan(DriveSimulator.urban(), noise = DriveSimulator.Noise.URBAN_CANYON, seed = 12))
        assertEquals(a, b, "the same seed produced a different drive")
        assertTrue(a != c, "two seeds produced the identical drive — the noise is not seeded")
    }

    @Test
    fun `interval jitter moves the sampling off the metronome`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.cruise(50.0), jitter = 0.2))
        val gaps = fixes.zipWithNext { a, b -> b.tMs - a.tMs }.distinct()
        assertTrue(gaps.size > 5, "only ${gaps.size} distinct gaps — jitter did nothing")
        assertTrue(gaps.all { it in 700..1350 }, "jitter left the plausible range: ${gaps.sorted()}")
    }

    @Test
    fun `a drive ends when the road does`() {
        // The assertion this file was missing. Every case here checked a LOWER
        // bound on the fix count, and a simulator whose speed programme never
        // completes satisfies every lower bound there is — it ran to
        // `maxDurationS` and emitted fifty minutes of fixes parked on the last
        // coordinate. The scenario suite found it as "411 reroute requests for
        // one wrong road", which is a long way from the cause.
        val fixes = DriveSimulator.run(plan(DriveSimulator.cruise(60.0)))
        val expectedS = 4_000.0 / (60.0 / 3.6)
        val actualS = fixes.last().tMs / 1000.0
        assertTrue(actualS < expectedS * 1.3,
            "4 km at 60 km/h took ${"%.0f".format(actualS)} s; expected about ${"%.0f".format(expectedS)}")
        assertTrue(actualS > expectedS * 0.9, "the trace stopped early at ${"%.0f".format(actualS)} s")
    }

    @Test
    fun `a stop-and-go programme also terminates`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.stopAndGo(cycles = 2)))
        // 4 km of road plus two 12 s dwells, nowhere near the hour-long cap.
        assertTrue(fixes.last().tMs < 900_000, "took ${fixes.last().tMs / 1000} s")
    }

    @Test
    fun `the trace reaches the end of the path`() {
        val fixes = DriveSimulator.run(plan(DriveSimulator.urban()))
        val total = DrivePath.lengthM(straight)
        assertTrue(fixes.last().truthAlongM >= total - 1.0,
            "stopped ${"%.0f".format(total - fixes.last().truthAlongM)} m short of the end")
    }

    @Test
    fun `a highway programme sustains motorway speed`() {
        val long = (0..120).map { LngLat(lng0 + (it * 100.0) / kx, lat) }
        val fixes = DriveSimulator.run(plan(DriveSimulator.highway(), path = long))
        val top = fixes.mapNotNull { it.speedMs }.max() * 3.6
        assertTrue(top > 95.0, "top speed was only ${"%.0f".format(top)} km/h")
        val fast = fixes.count { (it.speedMs ?: 0.0) * 3.6 > 90 }
        assertTrue(fast > 60, "only $fast fixes above 90 km/h — that is not sustained")
    }
}
