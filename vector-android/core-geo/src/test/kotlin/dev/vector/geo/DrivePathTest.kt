package dev.vector.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The truth paths the scenarios drive.
 *
 * These matter more than they look. A "wrong road" path that is really 300 m
 * away tests a teleport; a "parallel road" that joins with a corner tests a
 * manoeuvre no junction performs; a slice that snaps to the nearest vertex
 * turns a deviation-latency measurement into a measurement of the route's shape
 * point spacing. Each of those would produce a green scenario suite describing
 * behaviour no car would ever provoke.
 */
class DrivePathTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    /** 2 km east, shape points every 200 m — motorway-like spacing. */
    private val route = (0..10).map { LngLat(lng0 + (it * 200.0) / kx, lat) }

    @Test
    fun `a slice ends exactly where it was asked to, not at a vertex`() {
        // 1 300 m is deliberately between two 200 m vertices.
        val s = DrivePath.slice(route, 0.0, 1_300.0)
        assertTrue(abs(DrivePath.lengthM(s) - 1_300.0) < 1.0,
            "slice was ${"%.1f".format(DrivePath.lengthM(s))} m, not 1 300")
    }

    @Test
    fun `offset moves the requested number of metres in the requested direction`() {
        val p = LngLat(lng0, lat)
        val north = DrivePath.offset(p, 0.0, 100.0)
        val east = DrivePath.offset(p, 90.0, 100.0)
        assertTrue(abs(DrivePath.metresBetween(p, north) - 100.0) < 0.1)
        assertTrue(abs(DrivePath.metresBetween(p, east) - 100.0) < 0.1)
        assertTrue(north.lat > p.lat && abs(north.lng - p.lng) < 1e-9, "north was not north")
        assertTrue(east.lng > p.lng && abs(east.lat - p.lat) < 1e-9, "east was not east")
    }

    @Test
    fun `a parallel road reaches its offset gradually and then holds it`() {
        val speedMs = 50.0 / 3.6
        val taper = DrivePath.taperFor(offsetM = 30.0, speedMs = speedMs)
        val p = DrivePath.parallel(route, fromM = 400.0, lengthM = 800.0,
                                   offsetM = 30.0, transitionM = taper, side = 1)
        val idx = RouteGeometry.index(route)!!
        val offsets = p.map { idx.project(it)!!.offsetM }
        assertTrue(offsets.first() < 3.0, "the parallel road did not start on the route")
        assertTrue(abs(offsets.last() - 30.0) < 2.0,
            "it settled at ${"%.1f".format(offsets.last())} m, not 30")

        // Drivability, not a metre count. The second derivative of the lateral
        // offset with respect to distance IS the curvature the driver steers
        // through, and v² times it is what they feel. Asserting a lateral step
        // instead would pass or fail on the sampling interval.
        val h = 20.0
        val worstLateral = (1 until offsets.size - 1).maxOf { i ->
            val curvature = (offsets[i + 1] - 2 * offsets[i] + offsets[i - 1]) / (h * h)
            abs(curvature) * speedMs * speedMs
        }
        assertTrue(worstLateral < 1.8,
            "peak lateral acceleration ${"%.2f".format(worstLateral)} m/s² — that is a swerve, not a drift")
    }

    @Test
    fun `the taper scales with speed`() {
        // The same divergence at motorway speed must be drawn out, or the
        // scenario asks the navigator to react to a manoeuvre no car performs.
        val slow = DrivePath.taperFor(30.0, 50.0 / 3.6)
        val fast = DrivePath.taperFor(30.0, 110.0 / 3.6)
        assertTrue(fast > slow * 2.0, "taper barely changed: $slow -> $fast")
        // And it must actually deliver the comfort limit it promises.
        for (kmh in listOf(30.0, 50.0, 80.0, 110.0)) {
            val v = kmh / 3.6
            val l = DrivePath.taperFor(30.0, v)
            val peak = v * v * 0.5 * 30.0 * (Math.PI / l) * (Math.PI / l)
            assertTrue(abs(peak - 1.2) < 0.05, "at $kmh km/h the taper yields ${"%.2f".format(peak)} m/s²")
        }
    }

    @Test
    fun `a u-turn retraces its own geometry and inverts the heading`() {
        val p = DrivePath.uTurn(route, alongM = 1_000.0, overshootM = 200.0)
        val idx = RouteGeometry.index(route)!!
        // Every point is still ON the route — a U-turn on a road does not leave it.
        assertTrue(p.all { idx.project(it)!!.offsetM < 1.0 }, "the u-turn left the road")
        val apex = p.maxByOrNull { idx.project(it)!!.alongM }!!
        assertTrue(abs(idx.project(apex)!!.alongM - 1_200.0) < 5.0, "the apex was in the wrong place")
        val outBearing = RouteGeometry.bearingDeg(p[0], p[1])
        val backBearing = RouteGeometry.bearingDeg(p[p.size - 2], p[p.size - 1])
        assertTrue(abs(abs(RouteGeometry.angDiffDeg(outBearing, backBearing)) - 180.0) < 5.0,
            "the return leg was not the reverse heading")
    }

    @Test
    fun `forking onto a real alternative starts at the divergence`() {
        // A second route that leaves the first at 45 degrees from its 800 m mark.
        val branchStart = RouteGeometry.index(route)!!.pointAt(800.0)!!.position
        val other = (0..10).map { DrivePath.offset(branchStart, 45.0, it * 150.0) }
        val p = DrivePath.forkTo(route, alongM = 800.0, other = other)
        val idx = RouteGeometry.index(route)!!
        val onRoute = p.takeWhile { idx.project(it)!!.offsetM < 5.0 }
        assertTrue(DrivePath.lengthM(onRoute) > 700.0, "the fork happened too early")
        assertTrue(idx.project(p.last())!!.offsetM > 500.0, "the fork never went anywhere")
        // Continuous: no gap in the path where the two were stitched.
        val steps = p.zipWithNext { a, b -> DrivePath.metresBetween(a, b) }
        assertTrue(steps.max() < 260.0, "a ${"%.0f".format(steps.max())} m gap at the join")
    }

    @Test
    fun `overshooting the end keeps going on the same heading`() {
        val p = DrivePath.overshootEnd(route, extraM = 150.0)
        assertTrue(abs(DrivePath.lengthM(p) - 2_150.0) < 5.0)
        val end = RouteGeometry.bearingDeg(route[route.size - 2], route.last())
        val after = RouteGeometry.bearingDeg(p[p.size - 2], p.last())
        assertTrue(abs(RouteGeometry.angDiffDeg(end, after)) < 2.0, "the overshoot turned")
    }

    @Test
    fun `an angled approach ends at the destination but not along the route`() {
        val p = DrivePath.approachFrom(route, bearingDeg = 0.0, fromM = 200.0)
        assertTrue(DrivePath.metresBetween(p.last(), route.last()) < 0.5,
            "the approach did not finish at the destination")
        val idx = RouteGeometry.index(route)!!
        assertTrue(idx.project(p.first())!!.offsetM > 150.0,
            "an approach from the north was still on an east-west route")
    }
}
