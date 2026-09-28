package dev.vector.geo

import kotlin.math.cos
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CPU cost of the navigation loop, measured on the JVM (no device required).
 *
 * The web client's equivalent measured **67 ms of CPU per GPS fix**, of which
 * ~95% was map re-render under overlapping `easeTo` animations. The native
 * design removes the animation entirely — [RouteTracker.onFrame] advances a
 * scalar and interpolates a point — so the *logic* cost should be negligible and
 * the remaining cost should be GPU work the renderer does anyway.
 *
 * These tests pin the logic side of that claim. They are budgets with a wide
 * margin, not benchmarks: the purpose is to catch a regression of the "someone
 * made projection O(n) per frame" kind, on a route long enough that such a
 * mistake actually shows.
 *
 * What they deliberately do NOT claim: frame rate, battery, or GPU cost on a
 * real handset. Those need the device.
 */
class NavLoopPerfTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    /** ~20 km of route at 20 m spacing — 1,000 vertices, a long real drive. */
    private val longRoute: List<LngLat> =
        (0 until 1000).map { LngLat(lng0 + (it * 20.0) / kx, lat) }

    private fun trackerOnLongRoute(): RouteTracker {
        val t = RouteTracker()
        t.setRoute(longRoute)
        t.onFix(LngLat(lng0, lat), 20.0, 0L)
        return t
    }

    private fun warmAndTime(iterations: Int, block: (Int) -> Unit): Double {
        repeat(iterations / 4) { block(it) }          // let the JIT settle
        val ns = measureNanoTime { repeat(iterations) { block(it) } }
        return ns.toDouble() / iterations
    }

    @Test
    fun `a frame update costs microseconds, not milliseconds`() {
        // At 120 Hz there are 8.3 ms per frame and the renderer needs nearly all
        // of it. Anything approaching a millisecond here would be a real problem.
        val t = trackerOnLongRoute()
        var now = 0L
        val perFrame = warmAndTime(20_000) {
            now += 8_300_000L                          // 120 Hz
            if (now / 1e9 > 2.5) {                     // keep the lock alive
                t.onFix(LngLat(lng0 + 100.0 / kx, lat), 20.0, now)
            }
            t.onFrame(now)
        }
        assertTrue(
            perFrame < 200_000,                        // 0.2 ms
            "onFrame cost ${perFrame / 1000} us per call on a 1000-vertex route",
        )
    }

    @Test
    fun `a GPS fix costs well under the 1 Hz budget`() {
        // The web client spent 67 ms per fix. Projection is O(vertices), so this
        // is the number that would regress if someone reintroduced a per-fix
        // full-route scan somewhere on top of it.
        val t = trackerOnLongRoute()
        var now = 0L
        val perFix = warmAndTime(4_000) { i ->
            now += 1_000_000_000L
            t.onFix(LngLat(lng0 + (i % 900) * 20.0 / kx, lat), 20.0, now)
        }
        assertTrue(
            perFix < 5_000_000,                        // 5 ms, vs 1000 ms available
            "onFix cost ${perFix / 1_000_000.0} ms per fix on a 1000-vertex route",
        )
    }

    @Test
    fun `projection scales linearly, not quadratically, with route length`() {
        // The guard against an accidental nested scan. A 10x longer route may
        // cost ~10x; it must not cost ~100x.
        fun costFor(n: Int): Double {
            val coords = (0 until n).map { LngLat(lng0 + (it * 20.0) / kx, lat) }
            val idx = assertNotNull(RouteGeometry.index(coords))
            val probe = LngLat(lng0 + (n / 2) * 20.0 / kx, lat + 10.0 / RouteGeometry.M_PER_DEG_LAT)
            return warmAndTime(2_000) { idx.project(probe) }
        }
        val small = costFor(200)
        val large = costFor(2_000)
        val ratio = large / small.coerceAtLeast(1.0)
        assertTrue(ratio < 40, "10x the vertices cost ${"%.1f".format(ratio)}x the time — superlinear")
    }

    @Test
    fun `pointAt is sublinear because it binary-searches`() {
        // Dead reckoning calls this every frame; a linear scan here would be the
        // easiest way to accidentally make long routes stutter.
        fun costFor(n: Int): Double {
            val coords = (0 until n).map { LngLat(lng0 + (it * 20.0) / kx, lat) }
            val idx = assertNotNull(RouteGeometry.index(coords))
            val target = idx.totalM * 0.73
            return warmAndTime(20_000) { idx.pointAt(target) }
        }
        val small = costFor(200)
        val large = costFor(20_000)
        assertTrue(
            large < small * 8,
            "100x the vertices cost ${"%.1f".format(large / small)}x the time — pointAt is not binary-searching",
        )
    }

    @Test
    fun `a full simulated drive stays within a sane total budget`() {
        // 30 minutes at 1 Hz with a 120 Hz frame loop: 1,800 fixes and 216,000
        // frames. This is the whole navigation loop's CPU cost for a long trip.
        val t = trackerOnLongRoute()
        var now = 0L
        val totalNs = measureNanoTime {
            repeat(1_800) { sec ->
                now += 1_000_000_000L
                t.onFix(LngLat(lng0 + (sec % 900) * 20.0 / kx, lat), 20.0, now)
                repeat(120) {
                    now += 8_300_000L
                    t.onFrame(now)
                }
            }
        }
        val seconds = totalNs / 1e9
        assertTrue(
            seconds < 6.0,
            "a 30-minute drive cost ${"%.2f".format(seconds)} s of CPU in the nav loop",
        )
    }

    @Test
    fun `the tracker holds no per-fix state that grows`() {
        // A leak here is the classic cause of a nav app that degrades over a long
        // journey. The tracker keeps scalars, so memory must be flat.
        val t = trackerOnLongRoute()
        val rt = Runtime.getRuntime()
        var now = 0L
        repeat(20_000) {
            now += 8_300_000L
            if (it % 120 == 0) t.onFix(LngLat(lng0 + (it % 900) * 20.0 / kx, lat), 20.0, now)
            t.onFrame(now)
        }
        System.gc()
        Thread.sleep(50)
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0
        assertTrue(usedMb < 256, "heap after 20k frames: ${"%.0f".format(usedMb)} MB")
    }
}
