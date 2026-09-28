package dev.vector.geo.camera

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex

/**
 * What the SOURCE says a camera is (V7.3 / V7.7).
 *
 * ## Why this exists
 *
 * A camera warning is a claim about a device, and "speed camera" is only one
 * of the things such a device can be. Before this the model had no type at
 * all, so every sourced camera was announced as a speed camera and every
 * unsourced one would have been too. This is the closed vocabulary that
 * replaces that: five values, no sixth, each one traceable to a tag the
 * mapper wrote.
 *
 * ## The one rule that matters
 *
 * [UNKNOWN] is NOT a speed camera and is NEVER announced. [announces] is the
 * only thing the rest of the app asks, and it is false for [UNKNOWN]. So a
 * camera whose source does not state a type is carried as a fact and produces
 * no banner, no pill and no voice — "no camera type, no invented type".
 *
 * ## Where the values come from
 *
 * The routing layer classifies from the baked source tags
 * (``highway=speed_camera``, ``enforcement=*``) and puts the result on the
 * wire; [fromWire] re-validates it here, because a malformed or hostile
 * backend must not be able to make the client claim a type either.
 */
enum class CameraType {
    /** A fixed road-side or overhead speed camera. */
    SPEED,

    /** Section control: speed averaged over a stretch. */
    AVERAGE_SPEED,

    /** Enforcement of a traffic signal. */
    RED_LIGHT,

    /** The source states more than one enforcement function for one device. */
    COMBINED,

    /** The source does not state a type. Carried, never announced. */
    UNKNOWN,
    ;

    /**
     * Whether Vector may tell the driver about a camera of this type.
     *
     * False only for [UNKNOWN]: the location may be real, but with no source
     * type there is no honest sentence to say about it.
     */
    val announces: Boolean get() = this != UNKNOWN

    companion object {
        /**
         * The wire value, or [UNKNOWN].
         *
         * Anything unrecognised — a null, a blank, a type a newer backend
         * invented, a type this build does not know — is [UNKNOWN], and
         * [UNKNOWN] is silent. A backend cannot add a claim this client will
         * make.
         */
        fun fromWire(raw: String?): CameraType = when (raw) {
            "speed" -> SPEED
            "average_speed" -> AVERAGE_SPEED
            "red_light" -> RED_LIGHT
            "combined" -> COMBINED
            else -> UNKNOWN
        }
    }
}

/**
 * A camera's identity, location and source-stated type, as a statement about
 * the MAP, not about any one route or driver (V7.3).
 *
 * ## What this is and is not
 *
 * [position] is where OSM says the camera is. [type] is what OSM says it is.
 * Those are real facts, and they are the ONLY things this class claims: no
 * "active", no "enforcing", no "you will be fined" — no OSM tag anywhere is
 * any of those, and nothing here pretends otherwise. [maxspeedTag] /
 * [directionTag] ride along as provenance when the mapper recorded them (33
 * and 12 of Qatar's 133 camera nodes respectively), and [directionTag] is
 * NEVER trusted without validation — see [CameraMatcher.directionGate], the
 * only place a warning can be suppressed.
 */
data class CameraRef(
    val id: String,
    val position: LngLat,
    /** What the source says this camera is. See [CameraType]. */
    val type: CameraType = CameraType.UNKNOWN,
    /** OSM `maxspeed`, the zone the camera is associated with. Provenance only. */
    val maxspeedTag: String? = null,
    /** OSM `direction`, the mapper-recorded bearing. Provenance only. */
    val directionTag: String? = null,
)

/**
 * How one route encounters a camera: the route's own bearing at the camera's
 * along-route position. Deliberately NOT read from an OSM direction tag —
 * the same doctrine the signals use — because what matters to a driver is
 * the direction THEY are travelling, and the route geometry is the
 * deterministic, testable source of that.
 */
data class Approach(
    val bearingDeg: Double,
    val alongM: Double,
)

/** A camera placed on one specific route. */
data class MatchedCamera(
    val ref: CameraRef,
    val approach: Approach,
)

/**
 * The cameras of one route, in route order, built ONCE per route.
 *
 * The Stage 4 lesson applied to cameras: it describes the ROUTE, not the
 * vehicle, so nothing in it changes as the driver moves — built once when a
 * route arrives and never touched on a frame. The frame loop reads it only
 * through [next], a binary search over a list that for Qatar is a handful of
 * entries.
 */
class CameraProfile internal constructor(
    val cameras: List<MatchedCamera>,
) {
    val isEmpty: Boolean get() = cameras.isEmpty()
    val size: Int get() = cameras.size

    companion object {
        /** No cameras, no claim. The app uses it in place of a nullable. */
        val EMPTY = CameraProfile(emptyList())

        /**
         * The distance at which a camera becomes "ahead" for warning: the one
         * window the banner AND the voice share (V7.3, unified V7.7).
         *
         * Six seconds of highway lead time at 100 km/h is not enough to react
         * to; 420 m at 100 km/h is 15 s, which is a deliberate choice for a
         * warning whose only job is to make the driver aware a camera EXISTS
         * at a location — it never claims the camera will flash, which is
         * exactly why a longer horizon is affordable.
         *
         * It is deliberately NOT the map's window: the pill carries the
         * location fact and is drawn from further out. What this bounds is the
         * ALERT, because the HUD's second line is shared with the road name,
         * and a camera several kilometres away must not hold it.
         */
        const val WARN_AHEAD_M = 420.0
    }

    fun at(i: Int): MatchedCamera = cameras[i]

    /**
     * The first camera STRICTLY ahead of [afterAlongM].
     *
     * The look-back margin mirrors the tracker's and the signals': a camera
     * whose along-position sits a metre behind the vehicle — projection
     * rounding — is not "the next one" and must not re-announce itself.
     */
    fun next(afterAlongM: Double): MatchedCamera? {
        if (cameras.isEmpty()) return null
        var lo = 0
        var hi = cameras.size - 1
        var ans: MatchedCamera? = null
        val from = afterAlongM + 1.0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cameras[mid].approach.alongM > from) {
                ans = cameras[mid]
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }
}

/**
 * Place cameras onto a route, deterministically (V7.3).
 *
 * ## Route-relative, never nearest-point
 *
 * Each camera is projected PERPENDICULARLY onto the route via
 * [RouteIndex.project]'s segment math (the same doctrine [SignalMatcher]
 * uses) and rejected beyond [SNAP_MAX_M]. A camera 2 km off route is a
 * different stretch the driver is not approaching, and claiming it is how a
 * warning "helpfully" points at the wrong road.
 *
 * ## The direction gate — the one thing cameras have that signals do not
 *
 * A signal's warning is about arriving at a junction, which is true for any
 * approach. A camera's warning is about passing a location, and on a divided
 * road the SAME location can host a camera that enforces the OTHER
 * carriageway. When OSM records a sane `direction` (the bearing the lens
 * faces, 0..360) that lies more than [OPPOSITE_DEG] off the route's own
 * bearing at the camera, the camera provably faces the other side and the
 * warning is SUPPRESSED — failing safe (fewer warnings, never a wrong one).
 * The gate is deliberately conservative:
 *
 *  * no tag / malformed tag (121 of Qatar's 133 nodes): the LOCATION fact is
 *    warned. We say where a camera is, never what it does;
 *  * sane tag within [OPPOSITE_DEG]: warned (co-directional — the camera can
 *    plausibly image this carriageway);
 *  * sane tag beyond [OPPOSITE_DEG]: a far-side or front-onto-our-flow
 *    camera we cannot confidently assign to this carriageway, so it is not
 *    announced. A front-facing camera on OUR side is a miss in the SAFE
 *    direction: the driver is never told to expect trouble that is not
 *    theirs, which is the asymmetry a driver trusts.
 *
 * The gate can never ADD a camera the projection did not claim, and it never
 * claims which direction a camera enforces — it only ever refuses to warn.
 */
object CameraMatcher {

    /** Off this far and the camera is not on the route. Same as the signals. */
    const val SNAP_MAX_M = 40.0

    /**
     * Deduplication is by SOURCE IDENTITY, and there is no distance tolerance.
     *
     * Two distinct OSM camera nodes a few metres apart are two real cameras —
     * Qatar has 26 such pairs, the closest 11.5 m: at one gantry, front and
     * rear facing. A distance-merge would delete one of them, which is
     * deleting a fact the source stated in order to make the list tidier.
     * The only merge is a repeated id (one camera listed twice); the first
     * occurrence in route order wins.
     */

    /** A sane `direction` tag more than this off the route bearing means the
     * camera faces away and the warning is suppressed. 120 deg is a wide
     * enough band to keep every co-directional reading while excluding the
     * 180-degree far-side case that divided Qatar's highways actually
     * produce. */
    const val OPPOSITE_DEG = 120.0

    /**
     * Validate an OSM `direction` value as a camera bearing, or null.
     *
     * Accepts a finite number in [0, 360]. The deployed extract contains
     * values like "2460" and "360" — mapper noise — and those must fail
     * HERE, treated exactly like an absent tag (the location fact stands),
     * rather than being laundered into a bearing the gate then trusts.
     */
    fun saneDirection(raw: String?): Double? {
        if (raw == null) return null
        val d = raw.toDoubleOrNull() ?: return null
        if (!d.isFinite() || d < 0.0 || d > 360.0) return null
        return d % 360.0
    }

    /**
     * The gate's verdict for one camera on one route.
     *
     * [Direction.WARN] — claim the location (no tag, malformed tag, or a
     * sane tag plausibly co-directional). [Direction.SUPPRESSED] — a sane
     * tag provably facing away; never announce.
     */
    fun directionGate(
        directionTag: String?,
        routeBearingDeg: Double,
    ): Direction {
        val sane = saneDirection(directionTag) ?: return Direction.WARN
        var diff = kotlin.math.abs(sane - routeBearingDeg) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return if (diff > OPPOSITE_DEG) Direction.SUPPRESSED else Direction.WARN
    }

    enum class Direction { WARN, SUPPRESSED }

    /**
     * Build the profile for [route] from raw camera references (the
     * client-side projection fallback for old backends).
     *
     * Cameras whose type does not [CameraType.announces] never enter the
     * profile, and neither does a repeated identity — see the dedup note
     * above the constants.
     */
    fun match(
        route: RouteIndex,
        refs: List<CameraRef>,
        snapMaxM: Double = SNAP_MAX_M,
    ): CameraProfile {
        if (route.coords.size < 2 || refs.isEmpty()) return CameraProfile.EMPTY
        val projected = ArrayList<Pair<CameraRef, dev.vector.geo.RouteFix>>(refs.size)
        for (ref in refs) {
            if (!ref.type.announces) continue
            val fix = earliestPass(route, ref.position, snapMaxM) ?: continue
            projected.add(ref to fix)
        }
        if (projected.isEmpty()) return CameraProfile.EMPTY
        projected.sortWith(compareBy<Pair<CameraRef, dev.vector.geo.RouteFix>> { it.second.alongM }
            .thenBy { it.first.id })

        val accepted = ArrayList<MatchedCamera>(projected.size)
        val seen = HashSet<String>()
        for ((ref, fix) in projected) {
            if (!seen.add(ref.id)) continue
            val placed = place(route, ref, fix.alongM) ?: continue
            // Direction gate: only a SUPPLIED sane tag can suppress, and then
            // only by pointing AWAY. Nothing here can claim a camera catches
            // the driver; this is the mirror image of that refusal.
            if (directionGate(ref.directionTag, placed.approach.bearingDeg) ==
                Direction.SUPPRESSED
            ) {
                continue
            }
            accepted.add(placed)
        }
        return CameraProfile(accepted)
    }

    /**
     * The first pass of [p] along [route] whose perpendicular offset is
     * within [maxOffsetM], or null — the same ASCENDING earliest-pass scan
     * the signals use so a doubled-back route claims the encounter the
     * driver actually reaches first.
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
            if (len2 <= 0.0) continue
            val rawT = ((px - ax) * vx + (py - ay) * vy) / len2
            // A pass is a segment whose PERPENDICULAR FOOT lies ON the
            // segment. Clamping t to [0,1] and then measuring the offset at
            // the endpoint lets a point that is farther past the end than
            // the snap tolerance (but collinear with the segment) pass the
            // gate at exactly SNAP_MAX_M — a 40 m position error claimed as
            // the earlier pass. The foot must be interior for the segment to
            // claim the point at all; the first such segment ascending is
            // the pass the driver reaches first on a doubled-back route.
            if (rawT < 0.0 || rawT > 1.0) continue
            var t = rawT
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
     * Build the profile from a backend that already projected the cameras
     * (the /navigate wire). Two things still apply here, whatever the
     * backend's numbers said: the direction gate (a malformed backend must
     * not be able to force a warning either), and the type gate — a wire
     * entry with no recognisable type is [CameraType.UNKNOWN] and is not
     * announced.
     */
    fun fromProjected(
        route: RouteIndex,
        entries: List<Projected>,
    ): CameraProfile {
        val valid = entries.filter { it.ref.type.announces && it.alongM in 0.0..route.totalM }
            .sortedBy { it.alongM }
        if (valid.isEmpty()) return CameraProfile.EMPTY
        val accepted = ArrayList<MatchedCamera>(valid.size)
        val seen = HashSet<String>()
        for (e in valid) {
            if (!seen.add(e.ref.id)) continue
            if (directionGate(e.ref.directionTag, e.approachBearingDeg) ==
                Direction.SUPPRESSED
            ) {
                continue
            }
            val at = route.pointAt(e.alongM)
            accepted.add(
                MatchedCamera(
                    e.ref,
                    Approach(
                        bearingDeg = at?.bearing ?: e.approachBearingDeg,
                        alongM = e.alongM.coerceIn(0.0, route.totalM),
                    ),
                )
            )
        }
        return CameraProfile(accepted)
    }

    /** A camera the backend already placed on this route. */
    data class Projected(
        val ref: CameraRef,
        val alongM: Double,
        val approachBearingDeg: Double,
    )

    /** A camera as the app received it: projected (the /navigate wire) or
     * raw (old-backend fallback). The two never mix on one answer. */
    data class Entry(
        val ref: CameraRef,
        val alongM: Double? = null,
        val approachBearingDeg: Double? = null,
    )

    /** Build the profile from what the app actually received, either half. */
    fun profile(route: RouteIndex, entries: List<Entry>): CameraProfile {
        if (entries.isEmpty()) return CameraProfile.EMPTY
        val raw = entries.filter { it.alongM == null }
        val placed = entries.filter { it.alongM != null }
        val fromRaw = if (raw.isEmpty()) CameraProfile.EMPTY
                      else match(route, raw.map { it.ref })
        val fromPlaced = if (placed.isEmpty()) CameraProfile.EMPTY
                         else fromProjected(
                             route,
                             placed.map { Projected(it.ref, it.alongM!!, it.approachBearingDeg ?: 0.0) },
                         )
        if (fromRaw.isEmpty) return fromPlaced
        if (fromPlaced.isEmpty) return fromRaw
        val merged = ArrayList<MatchedCamera>(fromRaw.cameras.size + fromPlaced.cameras.size)
        merged.addAll(fromRaw.cameras)
        merged.addAll(fromPlaced.cameras)
        merged.sortWith(compareBy<MatchedCamera> { it.approach.alongM }.thenBy { it.ref.id })
        val out = ArrayList<MatchedCamera>(merged.size)
        val seen = HashSet<String>()
        for (m in merged) {
            if (!seen.add(m.ref.id)) continue
            out.add(m)
        }
        return CameraProfile(out)
    }

    private fun place(route: RouteIndex, ref: CameraRef, alongM: Double): MatchedCamera? {
        val at = route.pointAt(alongM) ?: return null
        return MatchedCamera(ref, Approach(at.bearing, alongM))
    }
}

/**
 * The exact words the driver may be shown about a camera.
 *
 * ## Why the strings live here and not in the UI
 *
 * One rule decides them — a camera warning is a LOCATION fact plus the type
 * the SOURCE stated, nothing more — and that rule must be the same wherever a
 * camera is rendered: the map callout, the HUD line, the voice. Putting the
 * strings beside the model keeps "no fabricated claim" a property of the
 * model rather than a discipline each composable re-implements.
 *
 * ## One sentence per type, and silence for [CameraType.UNKNOWN]
 *
 * | type | the sentence | why it is defensible |
 * |---|---|---|
 * | [CameraType.SPEED] | "Speed camera ahead" | `highway=speed_camera` is defined as a fixed speed camera |
 * | [CameraType.AVERAGE_SPEED] | "Average-speed camera ahead" | `enforcement=average_speed` states section control |
 * | [CameraType.RED_LIGHT] | "Red-light camera ahead" | `enforcement=traffic_signals` states it |
 * | [CameraType.COMBINED] | "Speed and red-light camera ahead" | the source states both functions for the device |
 * | [CameraType.UNKNOWN] | `null` — nothing is said | no source type means no honest sentence |
 *
 * [line] returns the same string the voice uses, by construction: it is one
 * function, so the banner and the utterance cannot drift apart.
 *
 * ## What is deliberately absent
 *
 * No word of activity or state: no "active", "enforcing", "fined", "flash".
 * No speed limit: the camera's `maxspeed` zone rides as provenance and is
 * never spoken or drawn, because a speed camera and a posted limit are two
 * separate facts and Vector has not established one from the other. Nothing
 * here says a camera is working, or that anyone will be caught.
 * [CameraTextTest] walks the whole vocabulary to say so.
 */
object CameraText {

    /**
     * The sentence for a camera of [type], or null when nothing may be said.
     *
     * Called for both the banner and the voice so the two are one fact.
     */
    fun line(type: CameraType): String? = when (type) {
        CameraType.SPEED -> "Speed camera ahead"
        CameraType.AVERAGE_SPEED -> "Average-speed camera ahead"
        CameraType.RED_LIGHT -> "Red-light camera ahead"
        CameraType.COMBINED -> "Speed and red-light camera ahead"
        CameraType.UNKNOWN -> null
    }
}