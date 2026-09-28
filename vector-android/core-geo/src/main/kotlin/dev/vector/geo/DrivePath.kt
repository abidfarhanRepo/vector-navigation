package dev.vector.geo

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The paths a vehicle actually drives, as distinct from the route it was given.
 *
 * A navigation test that only ever drives the route it was handed can prove
 * that nothing breaks when nothing goes wrong, which is the least interesting
 * property a navigator has. Every scenario worth testing — a missed turn, a
 * service road taken by mistake, a U-turn, an overshoot past the destination —
 * is a *different polyline* from the route, and the difference has to look like
 * a car did it rather than like a coordinate was edited.
 *
 * So this is the geometry half of the driving harness: it builds the truth path
 * the vehicle travels. [DriveSimulator] then turns a truth path into the fix
 * stream a receiver would produce for it. Keeping the two apart is deliberate —
 * "where did the car go" and "what did the GPS say about it" are independently
 * wrong in real life and have to be independently expressible here.
 *
 * Everything measures in metres via the same local equirectangular frame
 * [RouteIndex.project] uses, on the same sphere radius, so an offset of 30 m
 * here and an off-route reading of 30 m there are the same 30 m. Two frames
 * would make every threshold assertion in the scenario suite off by the
 * ~9.4% longitude foreshortening at Doha's latitude.
 */
object DrivePath {

    /** Metres-per-degree scale factors at [lat]: (x, y). */
    private fun frame(lat: Double): Pair<Double, Double> =
        cos(lat * Math.PI / 180.0) * RouteGeometry.M_PER_DEG_LAT to RouteGeometry.M_PER_DEG_LAT

    /**
     * The part of [route] between two along-route distances, inclusive of
     * interpolated endpoints.
     *
     * Interpolated rather than vertex-snapped: a scenario that says "leave the
     * route 1 200 m in" must leave it at 1 200 m, not at whichever shape point
     * happens to be nearest, or the deviation-detection latency it measures is
     * really measuring the route's vertex spacing.
     */
    fun slice(route: List<LngLat>, fromM: Double, toM: Double): List<LngLat> {
        val idx = RouteGeometry.index(route) ?: return route
        val a = fromM.coerceIn(0.0, idx.totalM)
        val b = toM.coerceIn(a, idx.totalM)
        val out = ArrayList<LngLat>()
        idx.pointAt(a)?.let { out.add(it.position) }
        for (i in route.indices) {
            if (idx.cum[i] > a && idx.cum[i] < b) out.add(route[i])
        }
        idx.pointAt(b)?.let { if (out.isEmpty() || it.position != out.last()) out.add(it.position) }
        return out
    }

    /**
     * Follow [route] for [alongM] metres, then take [onward] instead.
     *
     * The join is not smoothed. A fork in a road IS a discontinuity in heading
     * and pretending otherwise would test a manoeuvre no junction performs;
     * what must not be discontinuous is *position*, and [onward] is expected to
     * start at or near the divergence point (see [forkTo], which guarantees it).
     */
    fun divert(route: List<LngLat>, alongM: Double, onward: List<LngLat>): List<LngLat> =
        slice(route, 0.0, alongM) + onward

    /**
     * Leave [route] at [alongM] and continue along [other] from the point on
     * [other] nearest the divergence.
     *
     * This is the honest way to build a wrong-road scenario, because [other] is
     * a real route the backend returned: the roads exist, they connect, and the
     * driver's mistake is one a driver could make. Synthesising a plausible
     * alternative by hand tends to produce geometry no road follows, and a test
     * built on it is really testing the synthesiser.
     */
    fun forkTo(
        route: List<LngLat>,
        alongM: Double,
        other: List<LngLat>,
        stepM: Double = 20.0,
    ): List<LngLat> {
        val head = slice(route, 0.0, alongM)
        val at = head.lastOrNull() ?: return other
        val oIdx = RouteGeometry.index(other) ?: return head
        val proj = oIdx.project(at) ?: return head
        // Bridge the gap between where the driver actually is and where the new
        // road begins, rather than stitching the two ends together.
        //
        // This is not cosmetic. A router answers from the SNAPPED origin, so
        // its geometry can start seventy metres from the car — the
        // `reroute-wrong-road` fixture's own origin snap is 70.2 m. Joining the
        // paths directly put a seventy-metre teleport in the middle of the
        // truth path, and every scenario built on it then measured Vector's
        // response to a teleport: the driver never reached the new route, so
        // they were permanently off it and were rerouted every eight seconds
        // for the rest of the drive. Ninety-three requests on the long run,
        // from one missing interpolation.
        val bridge = ArrayList<LngLat>()
        val gap = metresBetween(at, proj.position)
        if (gap > stepM) {
            val brg = RouteGeometry.bearingDeg(at, proj.position)
            var d = stepM
            while (d < gap) {
                bridge.add(offset(at, brg, d))
                d += stepM
            }
        }
        return head + bridge + slice(other, proj.alongM, oIdx.totalM)
    }

    /**
     * A road running [offsetM] metres to one side of [route], joined gradually.
     *
     * This is Doha's most common wrong-road mistake and the one V5 §D singles
     * out: every Ring road and every expressway here carries a service road
     * within 20–40 m of it, and taking one instead of the main carriageway is
     * a manoeuvre of a few degrees, not a turn. So the offset ramps in over
     * [transitionM] — the vehicle drifts sideways at a shallow angle exactly as
     * it would through a slip lane, instead of teleporting into the next lane.
     *
     * The taper length is the parameter that decides whether the result is
     * drivable, and it depends on speed — see [taperFor], which is what
     * scenarios should use rather than guessing a number.
     *
     * @param side +1 for the left of the direction of travel, -1 for the right.
     */
    fun parallel(
        route: List<LngLat>,
        fromM: Double,
        lengthM: Double,
        offsetM: Double,
        transitionM: Double = 250.0,
        side: Int = 1,
        stepM: Double = 20.0,
    ): List<LngLat> {
        val idx = RouteGeometry.index(route) ?: return route
        val start = fromM.coerceIn(0.0, idx.totalM)
        val end = (start + lengthM).coerceAtMost(idx.totalM)
        val out = ArrayList<LngLat>()
        var d = start
        while (d <= end) {
            val pt = idx.pointAt(d) ?: break
            // Ramp with a cosine rather than linearly: a linear ramp has a
            // corner at each end, and a corner in the truth path shows up as a
            // heading step no vehicle produces.
            val ramp = ((d - start) / transitionM).coerceIn(0.0, 1.0)
            val eased = 0.5 - 0.5 * cos(ramp * Math.PI)
            out.add(offset(pt.position, pt.bearing + 90.0 * side, offsetM * eased))
            d += stepM
        }
        return out
    }

    /**
     * How long a [parallel] taper has to be to be drivable at [speedMs].
     *
     * The cosine ramp's peak curvature is `A/2 · (π/L)²`, so the lateral
     * acceleration a driver would feel at the middle of the manoeuvre is
     * `v² · A/2 · (π/L)²`. Solving for L at a comfort limit is the difference
     * between a wrong-road scenario a car could perform and one it could not:
     * the same 30 m divergence that is an easy drift at 50 km/h is 5 m/s² of
     * lateral acceleration at 100 km/h, which is a swerve, and a navigator
     * being asked to react to a swerve is not being asked the question V5 §D
     * poses.
     *
     * 1.2 m/s² is a deliberate lane change — noticeable, unremarkable. Well
     * inside the ~4 m/s² a dry road will give and outside the ~0.5 m/s² of
     * ordinary curve-following, so a taper built with it reads as a decision.
     */
    fun taperFor(offsetM: Double, speedMs: Double, lateralMs2: Double = 1.2): Double =
        (Math.PI * sqrt(offsetM.coerceAtLeast(0.1) * speedMs * speedMs / (2.0 * lateralMs2)))
            .coerceAtLeast(20.0)

    /**
     * Drive [overshootM] past [alongM], stop, and come back the way you came.
     *
     * The reversal repeats the same geometry backwards, which is what a U-turn
     * on a divided road actually is from a GPS receiver's point of view: the
     * position retraces, the heading inverts, and for one or two fixes in the
     * middle there is no heading at all because the vehicle is stationary. The
     * dwell at the apex is the simulator's job, not this one's — see
     * [DriveSimulator.Leg.Stop].
     */
    fun uTurn(route: List<LngLat>, alongM: Double, overshootM: Double): List<LngLat> {
        val idx = RouteGeometry.index(route) ?: return route
        val apex = (alongM + overshootM).coerceAtMost(idx.totalM)
        val out = slice(route, 0.0, apex)
        val back = slice(route, alongM, apex).reversed()
        return out + back
    }

    /**
     * Continue [extraM] metres past the end of [route] on the same heading.
     *
     * For the arrival scenarios: driving *to* the last vertex and stopping is
     * the one arrival a real driver never performs. They pass it, or they stop
     * short of it.
     */
    fun overshootEnd(route: List<LngLat>, extraM: Double, stepM: Double = 15.0): List<LngLat> {
        if (route.size < 2) return route
        val last = route.last()
        val bearing = RouteGeometry.bearingDeg(route[route.size - 2], last)
        val out = ArrayList(route)
        var d = stepM
        while (d <= extraM) {
            out.add(offset(last, bearing, d))
            d += stepM
        }
        return out
    }

    /**
     * Approach the end of [route] from [bearingDeg] instead of along it.
     *
     * V5 §L asks for arrival "at different angles", which is not a synonym for
     * "at different speeds": a destination reached across a car park, or from
     * the opposite carriageway, is metres from the route's end and hundreds of
     * metres along it. The two produce different arrival decisions and only one
     * of them is the happy path.
     */
    fun approachFrom(
        route: List<LngLat>,
        bearingDeg: Double,
        fromM: Double = 200.0,
        stepM: Double = 15.0,
    ): List<LngLat> {
        val dest = route.lastOrNull() ?: return route
        val out = ArrayList<LngLat>()
        var d = fromM
        while (d > 0) {
            out.add(offset(dest, bearingDeg, d))
            d -= stepM
        }
        out.add(dest)
        return out
    }

    /** [p] moved [distM] metres along [bearingDeg]. */
    fun offset(p: LngLat, bearingDeg: Double, distM: Double): LngLat {
        val (kx, ky) = frame(p.lat)
        val rad = bearingDeg * Math.PI / 180.0
        // Bearing is clockwise from north, so north is +y and east is +x.
        val dx = kotlin.math.sin(rad) * distM
        val dy = cos(rad) * distM
        return LngLat(p.lng + dx / kx, p.lat + dy / ky)
    }

    /** Straight-line metres between two points in the local frame. */
    fun metresBetween(a: LngLat, b: LngLat): Double {
        val (kx, ky) = frame((a.lat + b.lat) / 2)
        val dx = (b.lng - a.lng) * kx
        val dy = (b.lat - a.lat) * ky
        return sqrt(dx * dx + dy * dy)
    }

    /** Total length of a path in metres. */
    fun lengthM(path: List<LngLat>): Double =
        RouteGeometry.index(path)?.totalM ?: 0.0
}
