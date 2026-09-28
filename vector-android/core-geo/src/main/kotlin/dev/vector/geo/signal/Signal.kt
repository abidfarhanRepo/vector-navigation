package dev.vector.geo.signal

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex

/**
 * A signalized junction's identity and location, as a statement about the
 * MAP, not about any one route.
 *
 * ## What this is and is not
 *
 * [position] is where OSM says the lights are. That is a real fact (V7 Stage
 * 5's ordered data), and it is the ONLY thing this class claims: no phase, no
 * cycle, no approach. [directionTag] rides along as provenance when the
 * mapper recorded `traffic_signals:direction` (20 of Qatar's 899 nodes), but
 * it is never the primary approach definition — the approach of a SIGNAL ON A
 * ROUTE is derived from the route's own bearing at the signal (see
 * [Approach]), because what matters to a driver is the direction THEY are
 * travelling.
 *
 * The stop line is not here either. The OSM node sits at the crossing or the
 * junction centre, a proxy whose tolerance the arrival window accounts for.
 */
data class SignalRef(
    val id: String,
    val position: LngLat,
    /**
     * Where this signal came from. "osm:node:<id>". Never blank — the same
     * rule Callouts enforces: a world-space marker is a claim about a place,
     * and a claim without provenance is exactly the fabrication this project
     * refuses.
     */
    val source: String,
    /** OSM `traffic_signals:direction` when the mapper recorded one. Provenance only. */
    val directionTag: String? = null,
)

/**
 * How one route encounters a signal: the route's own bearing at the signal's
 * along-route position.
 *
 * Deliberately NOT read from an OSM direction tag. Two drivers on the same
 * junction face different signals depending on which way they are going; the
 * deterministic, testable source is the route geometry [RouteIndex] gives us.
 */
data class Approach(
    val bearingDeg: Double,
    val alongM: Double,
)

/** A signal placed on one specific route. */
data class MatchedSignal(
    val ref: SignalRef,
    val approach: Approach,
)

/**
 * The signals of one route, in route order, built ONCE per route.
 *
 * This is the Stage 4 lesson applied to signals: it describes the ROUTE, not
 * the vehicle, so nothing in it changes as the driver moves — which is why it
 * can be built once when a route arrives and never touched on a frame. The
 * frame loop reads it only through [next], a binary search over a list of a
 * few dozen entries.
 */
class SignalProfile internal constructor(
    val signals: List<MatchedSignal>,
) {
    val isEmpty: Boolean get() = signals.isEmpty()
    val size: Int get() = signals.size

    companion object {
        /** No signals, no claim. The app uses it in place of a nullable. */
        val EMPTY = SignalProfile(emptyList())
    }

    fun at(i: Int): MatchedSignal = signals[i]

    /**
     * The first signal STRICTLY ahead of [afterAlongM].
     *
     * The look-back margin mirrors the tracker's: a signal whose along-position
     * sits a metre behind the vehicle — projection rounding at a stop line —
     * is not "the next one" and must not re-announce itself.
     */
    fun next(afterAlongM: Double): MatchedSignal? {
        if (signals.isEmpty()) return null
        var lo = 0
        var hi = signals.size - 1
        var ans: MatchedSignal? = null
        val from = afterAlongM + 1.0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (signals[mid].approach.alongM > from) {
                ans = signals[mid]
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }
}

/**
 * Place signals onto a route, deterministically.
 *
 * ## Route-relative, never nearest-point
 *
 * Each signal is projected PERPENDICULARLY onto the route via
 * [RouteIndex.project] — the same segment-based projection the tracker and
 * the callouts use — and rejected beyond [SNAP_MAX_M] unless it is genuinely
 * on the route. A signal 2 km away is a different junction the driver is not
 * approaching, and claiming it is how a marker "helpfully" points at the
 * wrong road; the profile must not do that (fixture: signal off route).
 *
 * ## The doubled-back route
 *
 * A route that U-turns and comes back up the same street passes each
 * junction TWICE, and [RouteIndex.project] returns the GLOBALLY NEAREST
 * segment — which can be the second pass, exactly the defect RouteTracker
 * documents for the vehicle. A signal reported at the far pass tells the
 * driver "signal in 800 m" while the junction is 100 m ahead, so each
 * signal is matched to its EARLIEST pass within snap (see [earliestPass]):
 * the encounter the driver actually reaches first. The fixture pins this
 * with a route that U-turns past the same signal twice.
 *
 * ## Deduplication
 *
 * One physical set of lights is sometimes mapped as two nodes (a node per
 * stop line, two crossings of one junction). Signals closer than [MERGE_M]
 * collapse to the first. Signals 10-40 m apart — which Qatar's real data
 * contains plenty of — are genuinely distinct and stay distinct.
 */
object SignalMatcher {

    /** Off this far and the signal is not on the route. Same order as the
     * tracker's snap: a signal at a junction the route passes is ~0 m away. */
    const val SNAP_MAX_M = 40.0

    /** Two signals this close are the same lights. */
    const val MERGE_M = 10.0

    /**
     * Build the profile for [route] from raw signal references.
     */
    fun match(
        route: RouteIndex,
        refs: List<SignalRef>,
        snapMaxM: Double = SNAP_MAX_M,
        mergeM: Double = MERGE_M,
    ): SignalProfile {
        if (route.coords.size < 2 || refs.isEmpty()) return SignalProfile(emptyList())

        // Client-side projection fallback + first-pass gate. If the backend
        // already projected (along_m on the wire), callers build the profile
        // via fromProjected and never reach here.
        val projected = ArrayList<Pair<SignalRef, dev.vector.geo.RouteFix>>(refs.size)
        for (ref in refs) {
            val fix = earliestPass(route, ref.position, snapMaxM) ?: continue
            projected.add(ref to fix)
        }
        if (projected.isEmpty()) return SignalProfile(emptyList())
        projected.sortWith(compareBy<Pair<SignalRef, dev.vector.geo.RouteFix>> { it.second.alongM }
            .thenBy { it.first.id })

        val accepted = ArrayList<MatchedSignal>(projected.size)
        for ((ref, fix) in projected) {
            val last = accepted.lastOrNull()
            if (last == null) {
                accepted.add(place(route, ref, fix.alongM) ?: continue)
                continue
            }
            // A candidate within merge distance of the last accepted one is
            // the same lights mapped twice (a node per stop line) -- or, if
            // it is genuinely 10-40 m away, distinct. The POSITION decides:
            // a duplicate is within mergeM of the same point; a dense
            // junction signal is not, whatever the along rounding says.
            val d = RouteGeometry.haversineM(
                ref.position.lng, ref.position.lat,
                last.ref.position.lng, last.ref.position.lat,
            )
            if (fix.alongM <= last.approach.alongM + mergeM && d <= mergeM) continue
            accepted.add(place(route, ref, fix.alongM) ?: continue)
        }
        return SignalProfile(accepted)
    }

    /**
     * The first pass of [p] along [route] whose perpendicular offset is
     * within [maxOffsetM], or null.
     *
     * Mirrors [RouteIndex.project]'s segment math (same local equirectangular
     * frame, same cumulative along-distance) except for one deliberate
     * difference: the scan is ASCENDING and stops at the first pass within
     * tolerance, where the tracker's projection takes the minimum-offset
     * segment. On a straight route the two agree; on a doubled-back route
     * they disagree exactly where it matters.
     */
    private fun earliestPass(
        route: RouteIndex,
        p: LngLat,
        maxOffsetM: Double,
    ): dev.vector.geo.RouteFix? {
        val kx = Math.cos(p.lat * Math.PI / 180.0) * RouteGeometry.M_PER_DEG_LAT
        val ky = RouteGeometry.M_PER_DEG_LAT
        val px = p.lng * kx
        val py = p.lat * ky
        for (i in 0 until route.coords.size - 1) {
            val ax = route.coords[i].lng * kx
            val ay = route.coords[i].lat * ky
            val bx = route.coords[i + 1].lng * kx
            val by = route.coords[i + 1].lat * ky
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            var t = 0.0
            if (len2 > 0) {
                t = ((px - ax) * vx + (py - ay) * vy) / len2
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
            }
            val qx = ax + t * vx
            val qy = ay + t * vy
            val dx = px - qx
            val dy = py - qy
            val offset = Math.sqrt(dx * dx + dy * dy)
            if (offset <= maxOffsetM) {
                val segLen = route.cum[i + 1] - route.cum[i]
                val along = route.cum[i] + t * segLen
                return dev.vector.geo.RouteFix(
                    offsetM = offset,
                    alongM = along,
                    segIdx = i,
                    t = t,
                    position = LngLat(
                        route.coords[i].lng + (route.coords[i + 1].lng - route.coords[i].lng) * t,
                        route.coords[i].lat + (route.coords[i + 1].lat - route.coords[i].lat) * t,
                    ),
                )
            }
        }
        return null
    }

    /**
     * Build the profile from a backend that already projected the signals.
     *
     * The /navigate wire may carry `along_m` and `approach_bearing`; trusting
     * the backend's own route-relative positions is the whole point of the
     * field. Still validated here — dropped when outside the route, and
     * re-ordered by along distance with duplicate positions merged — so a
     * malformed backend cannot misplace a marker.
     */
    fun fromProjected(
        route: RouteIndex,
        entries: List<Projected>,
        snapMaxM: Double = SNAP_MAX_M,
        mergeM: Double = MERGE_M,
    ): SignalProfile {
        val valid = entries.filter { it.alongM in 0.0..route.totalM }
            .sortedBy { it.alongM }
        if (valid.isEmpty()) return SignalProfile(emptyList())
        val accepted = ArrayList<MatchedSignal>(valid.size)
        for (e in valid) {
            val last = accepted.lastOrNull()
            if (last != null && e.alongM <= last.approach.alongM + mergeM) {
                val d = RouteGeometry.haversineM(
                    e.ref.position.lng, e.ref.position.lat,
                    last.ref.position.lng, last.ref.position.lat,
                )
                if (d <= mergeM) continue
            }
            val at = route.pointAt(e.alongM)
            val approach = Approach(
                bearingDeg = at?.bearing ?: e.approachBearingDeg,
                alongM = e.alongM.coerceIn(0.0, route.totalM),
            )
            accepted.add(MatchedSignal(e.ref, approach))
        }
        return SignalProfile(accepted)
    }

    /** A signal the backend already placed on this route. */
    data class Projected(
        val ref: SignalRef,
        val alongM: Double,
        val approachBearingDeg: Double,
    )

    /**
     * One signal as the app received it: the backend either projected it
     * ([alongM] non-null, the /navigate wire) or left it raw for the client to
     * match itself ([alongM] null, the old-backend fallback). The two never
     * mix on one answer, but the client handles both anyway.
     */
    data class Entry(
        val ref: SignalRef,
        val alongM: Double? = null,
        val approachBearingDeg: Double? = null,
    )

    /**
     * Build the profile from what the app actually received, whichever half
     * of the wire it used.
     */
    fun profile(route: RouteIndex, entries: List<Entry>, mergeM: Double = MERGE_M): SignalProfile {
        if (entries.isEmpty()) return SignalProfile.EMPTY
        val raw = entries.filter { it.alongM == null }
        val placed = entries.filter { it.alongM != null }
        val fromRaw = if (raw.isEmpty()) SignalProfile.EMPTY
                      else match(route, raw.map { it.ref }, mergeM = mergeM)
        val fromPlaced = if (placed.isEmpty()) SignalProfile.EMPTY
                         else fromProjected(
                             route,
                             placed.map { Projected(it.ref, it.alongM!!, it.approachBearingDeg ?: 0.0) },
                             mergeM = mergeM,
                         )
        if (fromRaw.isEmpty) return fromPlaced
        if (fromPlaced.isEmpty) return fromRaw
        // Both halves present (a mixed backend): union, sorted and deduped.
        val merged = ArrayList<MatchedSignal>(fromRaw.size + fromPlaced.size)
        merged.addAll(fromRaw.signals)
        merged.addAll(fromPlaced.signals)
        merged.sortBy { it.approach.alongM }
        val out = ArrayList<MatchedSignal>(merged.size)
        for (m in merged) {
            val last = out.lastOrNull()
            if (last != null && m.approach.alongM <= last.approach.alongM + mergeM) {
                val d = RouteGeometry.haversineM(
                    m.ref.position.lng, m.ref.position.lat,
                    last.ref.position.lng, last.ref.position.lat,
                )
                if (d <= mergeM) continue
            }
            out.add(m)
        }
        return SignalProfile(out)
    }

    private fun place(route: RouteIndex, ref: SignalRef, alongM: Double): MatchedSignal? {
        val at = route.pointAt(alongM) ?: return null
        return MatchedSignal(ref, Approach(at.bearing, alongM))
    }
}