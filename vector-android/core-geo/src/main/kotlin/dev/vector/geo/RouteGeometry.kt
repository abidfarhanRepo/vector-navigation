package dev.vector.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route geometry primitives (ADR-0075; Kotlin port of vector-web's geo.js).
 *
 * Pure JVM on purpose. This is the maths that decides whether the app reroutes,
 * how the turn countdown behaves and where the puck is drawn — the part that has
 * to be right — and keeping it free of Android types means it is tested by
 * `./gradlew :core-geo:test` in about a second, with no device and no emulator.
 *
 * The web client's equivalent had no executable coverage at all: its 187 tests
 * asserted against index.html as TEXT, which is how an off-route threshold that
 * was wrong by 31x sat in the tree next to a comment stating the correct value.
 */
object RouteGeometry {

    const val EARTH_R_M = 6_371_000.0
    private const val DEG = Math.PI / 180.0

    /**
     * Metres per degree of latitude, DERIVED from [EARTH_R_M] rather than
     * hardcoded to the usual 111320.
     *
     * The cumulative index is built with haversine on a sphere of this radius,
     * and the planar projection frame must measure on the SAME sphere or the two
     * disagree by ~0.11% — about a metre per kilometre. Harmless for an
     * off-route test; not harmless for dead reckoning, where it accumulates as
     * drift between the snapped puck and its own route position.
     */
    const val M_PER_DEG_LAT = EARTH_R_M * DEG

    /** Great-circle distance in metres. */
    fun haversineM(lng1: Double, lat1: Double, lng2: Double, lat2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLng = (lng2 - lng1) * DEG
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R_M * asin(min(1.0, sqrt(a)))
    }

    /** Signed smallest angle from [b] to [a], in (-180, 180]. */
    fun angDiffDeg(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    /** Initial bearing in degrees (0 = north, clockwise) from [a] to [b]. */
    fun bearingDeg(a: LngLat, b: LngLat): Double {
        val y = sin((b.lng - a.lng) * DEG) * cos(b.lat * DEG)
        val x = cos(a.lat * DEG) * sin(b.lat * DEG) -
            sin(a.lat * DEG) * cos(b.lat * DEG) * cos((b.lng - a.lng) * DEG)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }

    /**
     * Move a point [offsetM] metres to the right of a heading.
     *
     * The single lateral normal in the app. Right of travel is bearing + 90,
     * which in lng/lat is `(cos b, -sin b)`, and the sign matters more than
     * anything else in this file: inverted, it places the route in the ONCOMING
     * carriageway with complete confidence.
     *
     * Shared rather than restated because three things now offset from a
     * centreline — [RouteChevrons]' wings, [RouteLanes]' ribbon and the puck —
     * and two copies of this are two chances for one of them to be rewritten
     * with the other normal. It also settles a 0.11% disagreement that was
     * already here: `RouteChevrons` carried its own `111_320.0` metres per
     * degree while the cumulative index below is built on [M_PER_DEG_LAT],
     * derived from [EARTH_R_M]. Sub-millimetre on a chevron wing and harmless
     * there, but the ribbon is offset by metres and measured against lane
     * dividers, so the two have to be on the same sphere.
     *
     * Returns [p] unchanged at the poles, where a longitude degree has no
     * length and a lateral offset has no meaning.
     */
    fun offsetPoint(p: LngLat, bearingDeg: Double, offsetM: Double): LngLat {
        if (offsetM == 0.0) return p
        val brg = bearingDeg * DEG
        val mPerDegLng = M_PER_DEG_LAT * cos(p.lat * DEG)
        if (mPerDegLng <= 0.0) return p
        return LngLat(
            p.lng + offsetM * cos(brg) / mPerDegLng,
            p.lat - offsetM * sin(brg) / M_PER_DEG_LAT,
        )
    }

    /**
     * Build the cumulative-distance index a route needs for projection.
     * Returns null for a degenerate geometry rather than a half-built index.
     */
    fun index(coords: List<LngLat>): RouteIndex? {
        if (coords.size < 2) return null
        val cum = DoubleArray(coords.size)
        for (i in 1 until coords.size) {
            cum[i] = cum[i - 1] + haversineM(
                coords[i - 1].lng, coords[i - 1].lat, coords[i].lng, coords[i].lat
            )
        }
        return RouteIndex(coords, cum)
    }
}

/** A longitude/latitude pair. Order is (lng, lat) throughout, as in GeoJSON. */
data class LngLat(val lng: Double, val lat: Double)

/** A route polyline with cumulative along-route distances at each vertex. */
class RouteIndex(val coords: List<LngLat>, val cum: DoubleArray) {
    val totalM: Double get() = cum[cum.size - 1]

    /**
     * Perpendicular projection of a point onto the route.
     *
     * This replaces nearest-VERTEX search, which is wrong in two ways that both
     * surface as user-visible navigation defects:
     *
     *  - **Off-route distance.** On a motorway with ~200 m between shape points
     *    you can sit dead-centre in the lane and still be 100 m from the nearest
     *    vertex. Only point-to-segment distance means "off the road". Measured on
     *    a real Doha route, a car driving EXACTLY on the line read as up to 177 m
     *    off under vertex search, and 0.00 m under this.
     *  - **Progress.** Snapping travelled distance to `cum[nearestVertex]` makes
     *    the turn countdown hold and then jump by the vertex spacing — up to
     *    164 m on the same route.
     *
     * Returns null only for a degenerate index.
     */
    fun project(p: LngLat): RouteFix? = project(p, null, 0.0, 0.0)

    /**
     * Perpendicular projection, optionally constrained to a window of the route
     * around [nearAlongM].
     *
     * ## The defect the window exists because of
     *
     * The unconstrained search takes the globally nearest segment, and on a
     * route that doubles back on itself the globally nearest segment is
     * sometimes the wrong carriageway of a section already driven. The first
     * route the live router returns for Souq Waqif → West Bay does exactly
     * that: a U-turn 329 m in, then back up the same street. Driving north past
     * it at 684 m along, with ordinary urban-canyon drift of about fifteen
     * metres, one fix projected onto the SOUTHBOUND side instead — 152 m
     * earlier along the route.
     *
     * What the driver saw, measured by `DriveScenarioTest`'s scenario F:
     *
     *  * the vehicle jumped **151.8 m** backwards in one frame;
     *  * the distance remaining **grew** from 5 988 m to 6 140 m;
     *  * the instruction reverted from maneuver 3 to maneuver 2 — a turn they
     *    had already made was given again.
     *
     * A window fixes it because along-route position is continuous in a way
     * that distance-to-line is not: a vehicle at 684 m was at 670 m a second
     * ago, and no amount of GPS error changes that. The asymmetry is deliberate
     * — [fwdM] has to cover the distance a car can cover between fixes at
     * motorway speed, while [backM] only has to cover GPS error and a car
     * reversing, so it can be tight enough to exclude a doubled-back
     * carriageway.
     *
     * @param nearAlongM the last known along-route distance, or null for an
     *   unconstrained search (the first fix, or a re-lock after a dropout).
     */
    fun project(
        p: LngLat,
        nearAlongM: Double?,
        backM: Double,
        fwdM: Double,
        /**
         * The vehicle's heading, when it is known and trustworthy.
         *
         * Used to disambiguate between two passes of the same street, and only
         * when there is no [nearAlongM] to constrain the search — which is the
         * first lock of a journey and the re-lock after a dropout.
         *
         * ## The defect this closes
         *
         * Found on the S24, in scenario F, and NOT by the simulation — which is
         * the interesting part. The first route the live router returns for Souq
         * Waqif → West Bay U-turns 329 m in and comes back up the same street,
         * so for the first few hundred metres the route has **two passes a few
         * metres apart**. An unconstrained projection takes the nearest
         * segment, and with urban-canyon drift the nearest segment is sometimes
         * the return leg.
         *
         * What that did, measured from the device log: the tracker locked at
         * roughly 550 m along instead of 50 m — the vehicle placed **half a
         * kilometre ahead of itself** — and the [backM] window then held the
         * error, because the true position was now far behind the window's edge.
         * One second after the driver pressed Start they were declared off
         * route and rerouted.
         *
         * The JVM scenario suite could not see it: its drives begin at the
         * route's own first coordinate, where the outbound leg is unambiguously
         * nearest. On the device navigation begins several seconds into the
         * trace, because the route request takes time — so the first fix the
         * tracker locks on is already inside the ambiguous stretch. That gap
         * between "where a test starts a drive" and "where a drive starts" is
         * the whole reason for running the traces on hardware as well.
         *
         * Heading resolves it because the two passes of a U-turn are the one
         * thing that differs by 180 degrees. Null when the receiver has no
         * heading or the vehicle is not moving — a parked car's GPS heading is
         * close to random, and guessing from it would be worse than taking the
         * nearest segment.
         */
        headingDeg: Double? = null,
    ): RouteFix? {
        if (coords.size < 2) return null
        val lo = nearAlongM?.let { it - backM }
        val hi = nearAlongM?.let { it + fwdM }
        // Local equirectangular frame centred on the query latitude. Over the few
        // hundred metres that matter for snapping this is sub-metre accurate, and
        // unlike raw degrees it is isotropic — a longitude degree at Doha's 25 degN
        // is ~9.4% shorter than a latitude degree, so an unscaled threshold would
        // be direction-dependent.
        val kx = cos(p.lat * Math.PI / 180.0) * RouteGeometry.M_PER_DEG_LAT
        val ky = RouteGeometry.M_PER_DEG_LAT
        val px = p.lng * kx
        val py = p.lat * ky

        var bestOffset = Double.MAX_VALUE
        var best: RouteFix? = null
        // Every candidate close enough to the best that heading has to break
        // the tie. Only collected when a heading is on offer and the search is
        // unconstrained, so the ordinary followed-frame path allocates nothing.
        val ambiguous = if (headingDeg != null && nearAlongM == null)
            ArrayList<RouteFix>(4) else null

        for (i in 0 until coords.size - 1) {
            // Skip segments entirely outside the window. Both bounds compared
            // against the segment's own extent, so a single long segment that
            // straddles the window is still considered.
            if (lo != null && hi != null && (cum[i + 1] < lo || cum[i] > hi)) continue
            val ax = coords[i].lng * kx
            val ay = coords[i].lat * ky
            val bx = coords[i + 1].lng * kx
            val by = coords[i + 1].lat * ky
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy

            var t = 0.0
            if (len2 > 0) {
                t = ((px - ax) * vx + (py - ay) * vy) / len2
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
            }
            // Clamp the projection to the part of THIS segment that is inside
            // the window, rather than only skipping segments wholly outside it.
            //
            // Segment-level skipping is not enough, and the doubled-back route
            // is what proves it: the return carriageway's segment from 562 m to
            // 612 m straddles a window ending at 576 m, so its nearest point —
            // at 612 m, well past the boundary — was still considered and the
            // vehicle still jumped. A window has to constrain the ANSWER, not
            // just the search.
            if (lo != null && hi != null) {
                val segLenHere = cum[i + 1] - cum[i]
                if (segLenHere > 0) {
                    val tLo = ((lo - cum[i]) / segLenHere).coerceIn(0.0, 1.0)
                    val tHi = ((hi - cum[i]) / segLenHere).coerceIn(0.0, 1.0)
                    if (tHi <= tLo) {
                        t = tLo
                    } else {
                        t = t.coerceIn(tLo, tHi)
                    }
                }
            }

            val qx = ax + t * vx
            val qy = ay + t * vy
            val dx = px - qx
            val dy = py - qy
            val offset = sqrt(dx * dx + dy * dy)

            if (ambiguous != null && offset <= AMBIGUOUS_M) {
                val segLen = cum[i + 1] - cum[i]
                ambiguous.add(
                    RouteFix(
                        offsetM = offset,
                        alongM = cum[i] + t * segLen,
                        segIdx = i,
                        t = t,
                        position = LngLat(
                            coords[i].lng + (coords[i + 1].lng - coords[i].lng) * t,
                            coords[i].lat + (coords[i + 1].lat - coords[i].lat) * t
                        )
                    )
                )
            }
            if (offset < bestOffset) {
                bestOffset = offset
                // Segment length from the cumulative index, not recomputed in the
                // planar frame, so alongM stays exactly consistent with totalM.
                val segLen = cum[i + 1] - cum[i]
                best = RouteFix(
                    offsetM = offset,
                    alongM = cum[i] + t * segLen,
                    segIdx = i,
                    t = t,
                    position = LngLat(
                        coords[i].lng + (coords[i + 1].lng - coords[i].lng) * t,
                        coords[i].lat + (coords[i + 1].lat - coords[i].lat) * t
                    )
                )
            }
        }
        if (ambiguous != null && headingDeg != null && ambiguous.size > 1) {
            // Two or more places on the route are within snapping distance of
            // this position — the two passes of a doubled-back street. Take the
            // one the vehicle is actually pointing along.
            //
            // Candidates are already filtered to AMBIGUOUS_M, so this can only
            // ever move the answer between places the plain nearest-segment
            // rule considered equally plausible; it cannot pick something far
            // away because a heading happened to match.
            val byHeading = ambiguous.minByOrNull { fix ->
                val segBearing = RouteGeometry.bearingDeg(coords[fix.segIdx], coords[fix.segIdx + 1])
                abs(RouteGeometry.angDiffDeg(segBearing, headingDeg))
            }
            if (byHeading != null) return byHeading
        }
        return best
    }

    private companion object {
        /**
         * How close two candidate projections have to be for heading to decide
         * between them.
         *
         * A divided carriageway's two sides, and the two passes of a U-turn,
         * are both within a few tens of metres. 35 m is inside
         * `RouteTracker.snapMaxM` (40 m), so heading can only ever choose
         * between positions the tracker would have snapped to anyway.
         */
        const val AMBIGUOUS_M = 35.0
    }

    /**
     * The part of the route between two along-route distances, as its own
     * polyline, with exact endpoints interpolated onto the segments they fall
     * in.
     *
     * MapLibre's `line-offset` is one value per FEATURE, so a route whose
     * lateral offset varies along it has to be several features — this is the
     * cut. Endpoints are interpolated rather than snapped to the nearest
     * vertex because a snapped cut leaves a gap or an overlap of up to the
     * vertex spacing, which on OSM motorway geometry is 200 m of ribbon that
     * is either missing or drawn twice at two different offsets.
     *
     * Returns fewer than two points for an empty or inverted span, which the
     * caller should skip rather than draw.
     */
    fun between(fromM: Double, toM: Double): List<LngLat> {
        if (coords.size < 2) return emptyList()
        val a = fromM.coerceIn(0.0, totalM)
        val b = toM.coerceIn(0.0, totalM)
        if (b <= a) return emptyList()
        val start = pointAt(a) ?: return emptyList()
        val end = pointAt(b) ?: return emptyList()
        val out = ArrayList<LngLat>(8)
        out.add(start.position)
        // Every vertex strictly inside the span, in order. `segIdx + 1` is the
        // first vertex past the start point; the loop stops before the vertex
        // the end point sits on, which `end.position` then supplies exactly.
        var i = start.segIdx + 1
        while (i <= end.segIdx && i < coords.size) {
            val v = coords[i]
            if (v != out.last()) out.add(v)
            i++
        }
        if (end.position != out.last()) out.add(end.position)
        return if (out.size < 2) emptyList() else out
    }

    /**
     * Position [alongM] metres into the route, plus the bearing of the segment it
     * lands on. This is what lets the puck be dead-reckoned forward between GPS
     * fixes instead of animating toward raw 1 Hz positions. Clamps at both ends.
     */
    fun pointAt(alongM: Double): RoutePoint? {
        if (coords.size < 2) return null
        val d = alongM.coerceIn(0.0, totalM)
        if (d >= totalM) {
            val n = coords.size - 1
            return RoutePoint(coords[n], RouteGeometry.bearingDeg(coords[n - 1], coords[n]), n - 1, 1.0)
        }
        // Binary search for the segment containing d.
        var lo = 0
        var hi = coords.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val segLen = cum[lo + 1] - cum[lo]
        val t = if (segLen > 0) (d - cum[lo]) / segLen else 0.0
        return RoutePoint(
            LngLat(
                coords[lo].lng + (coords[lo + 1].lng - coords[lo].lng) * t,
                coords[lo].lat + (coords[lo + 1].lat - coords[lo].lat) * t
            ),
            RouteGeometry.bearingDeg(coords[lo], coords[lo + 1]),
            lo,
            t
        )
    }
}

/** Where a raw position projects onto the route. */
data class RouteFix(
    val offsetM: Double,
    val alongM: Double,
    val segIdx: Int,
    val t: Double,
    val position: LngLat,
)

/** A position resolved from an along-route distance. */
data class RoutePoint(
    val position: LngLat,
    val bearing: Double,
    val segIdx: Int,
    val t: Double,
)
