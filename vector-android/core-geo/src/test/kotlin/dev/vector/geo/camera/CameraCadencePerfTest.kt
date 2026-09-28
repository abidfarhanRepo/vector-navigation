package dev.vector.geo.camera

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex
import kotlin.math.cos
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The per-tick cost of the camera model (V7.3).
 *
 * The cadence rule mirrors Stage 5: camera computation happens ONCE PER ROUTE
 * (profile construction at route apply) and once per navigation TICK
 * (next-camera + distance), never per display frame. These budgets pin the
 * LOGIC side of that claim with wide margins; frame rate and rendering cost
 * are the device step's job ([VectorCameras] telemetry on the emulator).
 */
class CameraCadencePerfTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    private fun lonAt(eastM: Double) = lng0 + eastM / kx

    private fun longRoute(meters: Double = 20_000.0, vertices: Int = 1001): RouteIndex {
        return RouteGeometry.index(
            (0 until vertices).map { i -> LngLat(lonAt(meters * i / (vertices - 1)), lat) },
        )!!
    }

    private fun profileOf(route: RouteIndex, n: Int): CameraProfile {
        val refs = (0 until n).map { i ->
            CameraRef("n$i", LngLat(lonAt(100.0 + i * 300.0), lat), CameraType.SPEED)
        }
        return CameraMatcher.match(route, refs)
    }

    private fun tickCost(profile: CameraProfile): Double {
        // The two calls NavSession.withCamera performs per tick, timed as a
        // unit because they are a unit.
        val ns = measureNanoTime {
            repeat(50_000) {
                val next = profile.next(12_345.0 + (it % 100))
                if (next != null) {
                    // remainingM is the distance fact the HUD shows; nothing
                    // else is computed — there is no phase for a camera.
                    @Suppress("UNUSED_VARIABLE")
                    val remaining = next.approach.alongM - 12_345.0
                }
            }
        }
        return ns.toDouble() / 50_000.0
    }

    @Test
    fun `a navigation tick with a sixty-camera route costs microseconds`() {
        val route = longRoute()
        val profile = profileOf(route, 60)
        // At 1 Hz there is 1,000,000 ns per tick; the camera half of the tick
        // should be a rounding error.
        val perTick = tickCost(profile)
        assertTrue(perTick < 50_000.0, // 50 us
            "per-tick camera cost ${perTick / 1e3} us — must stay far under the 1 Hz budget")
    }

    @Test
    fun `profile construction is once per route and scales`() {
        val route = longRoute()
        repeat(10) { profileOf(route, 60) }  // warm the JIT
        val tiny = measureNanoTime { profileOf(route, 2) }
        val many = measureNanoTime { profileOf(route, 60) }
        assertTrue(many < 5_000_000.0, // 5 ms
            "profile build cost ${many / 1e6} ms for 60 cameras")
        assertTrue(many < tiny * 300, "profiles must scale sub-linearly-ish")
    }
}