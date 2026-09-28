package dev.vector.geo

import dev.vector.geo.camera.CameraType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Warnings attached to the place they apply to, rather than to the top of the
 * screen.
 *
 * ## The rule this file exists to enforce
 *
 * **Nothing is emitted without a source.** Every callout carries the geometry
 * or the tag it was derived from, in [Callout.source], and `CalloutsTest` walks
 * every callout the module can produce and fails any with an empty one. That is
 * not bookkeeping: a world-space pill on the road reads as a *fact about that
 * road*, which is a much stronger claim than a line on a map, and Vector's
 * whole position is that it does not make claims it cannot support.
 *
 * ## What is here, and what is deliberately not
 *
 * V8 §7.5 lists four honest sources. Two of them are pure route geometry and
 * are produced here, with 100% coverage:
 *
 *  - **[Kind.JUNCTION]** — the router told us where the maneuvers are.
 *  - **[Kind.BEND]** — a curve sharp enough to matter that the router did NOT
 *    call a maneuver, which is the only kind worth drawing: a bend that is
 *    already a "turn left" is described by the banner.
 *
 * The third, **[Kind.SPEED]**, is a fact about a road rather than about the
 * route, so it cannot be derived here — the caller supplies it, from a source
 * that knows, and this module only places it. See [SpeedChange].
 *
 * The fourth — bridge and tunnel entry — is **not built**, and that is a
 * decision rather than an omission. `bridge` and `tunnel` are on the phone:
 * they are whitelisted at ingestion and they reach the z15 tiles (V8 §3.2). But
 * they are on the *tiles*, and a callout has to be positioned along the
 * *route*, which arrives from `/navigate` as a bare `LineString` with no tags
 * on it at all. There is no join between the two on the client. Reading them
 * back out of whatever the renderer happens to have on screen is not a source —
 * it is a guess about the camera — so the honest position is that this one
 * needs a field on the routing response, and until it has one it is absent.
 *
 * ## [Kind.SIGNAL] (V7 Stage 5)
 *
 * A fifth source, and the first that had to be ADDED to the data rather than
 * derived from what was already there: `/navigate` now carries the signals the
 * route passes, with their along-route positions. The pill carries the
 * LOCATION fact only — "there is a signalized junction here", which is what
 * OSM evidence actually supports. A phase claim is a per-tick prediction and
 * lives in the live HUD slot; baking it into route geometry drawn once would
 * freeze a guess into the map. See [SignalText] and `signal/` for the model.
 *
 * ## [Kind.CAMERA] (V7.3)
 *
 * A sixth source, the same shape as the signal: `/navigate` carries the
 * cameras the route passes, and the pill carries the LOCATION fact only —
 * "there is a speed camera at this point on the route", which is what OSM
 * evidence actually supports. Nothing on the pill claims the camera is
 * active, enforcing, or that anyone will be fined; the words come from
 * [dev.vector.geo.camera.CameraText], which tests pin. The live distance
 * lives in the HUD line, the same split as signals.
 */
object Callouts {

    enum class Kind { JUNCTION, BEND, SPEED, SIGNAL, CAMERA }

    /**
     * The picture that goes at a callout's anchor.
     *
     * ## Why the vocabulary is here and not in the client
     *
     * A callout is built in this module and drawn in `vector-android`, and the
     * value that crosses between them is a string the style matches on. If the
     * client owned the strings, this module would be emitting them without
     * knowing whether anything can draw them; if the strings lived in both
     * places, the day one list gains an entry is the day the other silently
     * stops matching — and an `icon-image` that matches nothing renders NOTHING,
     * with no warning ([dev.vector.android.VectorMarkers.WARN_DOT] is the
     * fallback that exists for exactly that failure).
     *
     * So the vocabulary is defined once, here, next to the code that chooses it,
     * and `CalloutStyleTest` asserts that the style knows every value in it.
     *
     * ## What each one is for
     *
     * A warning used to be a dot and a pill of words, so "signalized junction"
     * and "speed camera" differed only by reading them. Both reference products
     * put a PICTURE at the place instead, and a driver matches a shape in
     * peripheral vision sooner than a word — the same argument the vehicle
     * marker's arrowhead is built on.
     */
    object Icon {
        /** A traffic-signal head. */
        const val SIGNAL = "signal"

        /** A fixed road-side or overhead speed camera. */
        const val CAMERA_SPEED = "camera-speed"

        /**
         * A camera enforcing a traffic signal.
         *
         * Reached through [dev.vector.geo.camera.CameraType.RED_LIGHT], which
         * `/navigate` can already send (`enforcement=traffic_signals`, the
         * documented OSM spelling). **Qatar has no such device**: `enforcement`
         * appears nowhere in the country's OSM data, and all 176 catalogued
         * cameras are `highway=speed_camera`. The glyph is here because the
         * wire contract carries the type, not because the road does.
         */
        const val CAMERA_RED_LIGHT = "camera-red-light"

        /**
         * Every value above, so the tests, the registration and the style
         * cannot disagree about the vocabulary.
         *
         * There is deliberately no `camera-mobile` entry. Enforcement that is
         * not bolted to a post is a real thing on Qatar's roads and **none of
         * it is in the data**: `enforcement=*` is absent nationally, every
         * catalogued camera is `highway=speed_camera`, and — unlike red-light —
         * the client's wire contract has no value for it either
         * ([dev.vector.geo.camera.CameraType.fromWire] maps anything unknown to
         * [dev.vector.geo.camera.CameraType.UNKNOWN], which is silent on
         * purpose). An icon that no backend can ask for is a picture nothing
         * can draw, so it is absent rather than dead. Adding it means a tag,
         * a `camera_catalog.py` rule and a wire value — in that order.
         */
        val ALL = listOf(SIGNAL, CAMERA_SPEED, CAMERA_RED_LIGHT)
    }

    /**
     * One pill, and the geometry that hangs it off the road.
     *
     * @property anchor the point on the route the warning is about.
     * @property label where the pill itself sits — off to one side, so it does
     *   not cover the road it is describing. The leader line runs between them.
     * @property source what this was derived from. Never blank.
     */
    data class Callout(
        val kind: Kind,
        val alongM: Double,
        val anchor: LngLat,
        val label: LngLat,
        val text: String,
        val source: String,
        /**
         * Which picture belongs at [anchor], from [Icon.ALL], or null for the
         * kinds whose meaning the text already carries ([Kind.JUNCTION] says
         * "Left", which is not a picture; [Kind.SPEED] names a number).
         *
         * Not derived from [kind] at the renderer, because the camera types
         * differ by a fact that only exists here: [CameraType.RED_LIGHT] and a
         * plain fixed camera both arrive as [Kind.CAMERA], and telling a driver
         * they are the same device is the sort of claim this module's own
         * "nothing without a source" rule exists to prevent.
         */
        val icon: String? = null,
    )

    /**
     * A maneuver, reduced to what a callout needs.
     *
     * [type] is the router's own maneuver vocabulary — `turn-left`,
     * `roundabout`, `arrive` and so on — and is used for two things: deciding
     * whether the maneuver is a decision at all, and deciding which side of the
     * road to hang the pill on.
     */
    data class Junction(
        val cumulativeM: Double,
        val type: String,
        val exitRef: String? = null,
        /**
         * The road the maneuver joins, carried through from
         * `Maneuver.road`.
         *
         * **Currently unused by this module.** Nothing in [build] or
         * [junctionText] reads it: a world-space pill deliberately says only
         * the decision ("Right", "Exit Q3") and leaves the road name to the
         * turn banner and the turn list, which have the room for it. It is
         * kept rather than removed because it mirrors `Maneuver.road`
         * one-for-one and is the field a future road-name callout — or a
         * diagnostic that wants to name a maneuver's target — would need;
         * deleting it would only mean plumbing it again. It is not dead by
         * accident, and it is documented here so the next reader does not
         * mistake it for live.
         */
        val road: String? = null,
    )

    /**
     * A change of posted limit ahead, supplied by whoever asked the road.
     *
     * There is no `inferred` flag here on purpose: an inferred limit is a class
     * median and must never reach this module at all. The caller decides, using
     * the same doctrine the speed disc already follows — "presenting an
     * inferred number as a posted sign is the kind of confident wrong answer a
     * driver acts on" — and passes null when it cannot tell.
     */
    data class SpeedChange(val alongM: Double, val limitKmh: Int)

    // ---- tuning ------------------------------------------------------------

    /** How far to the side of the road the pill floats. */
    const val LEADER_M = 34.0

    /**
     * How much a road has to turn, over [BEND_WINDOW_M], to be worth a warning.
     *
     * 40 degrees over 40 m is a radius of about **57 m**, and the radius is the
     * number that matters: lateral acceleration is `v^2 / r`, so 57 m at
     * 60 km/h is 4.8 m/s^2 — about half a g, which is a bend a driver
     * physically notices — while an ordinary Doha ring-road sweep at 300 m is
     * 0.9 m/s^2 and needs no telling.
     *
     * Set deliberately tighter than any arterial curve. A warning that fires on
     * roads a driver takes at speed every day is a warning they stop reading,
     * and then it is worse than not being there: it has spent attention and
     * taught them to spend none.
     */
    const val BEND_TURN_DEG = 40.0
    const val BEND_WINDOW_M = 40.0

    /** Two bends closer together than this are one bend. */
    const val BEND_MERGE_M = 220.0

    /**
     * A bend this close to a maneuver is the maneuver.
     *
     * Every junction is geometrically a bend. Drawing both would put a "Sharp
     * bend" pill on top of every "Turn left" pill, which is the failure mode of
     * deriving a warning from geometry without asking what the router already
     * said about the same place.
     */
    const val BEND_CLEAR_OF_JUNCTION_M = 70.0

    /**
     * A signal this close to a junction callout is the junction.
     *
     * Same reasoning as [BEND_CLEAR_OF_JUNCTION_M]: at a signalized
     * intersection the turn pill already tells the driver there is a decision
     * there, and a second full-size pill reading "Signal ahead" beside it is
     * clutter competing for the same glance. The signal pill earns its place at
     * the crossings a driver rolls straight through, which is the majority of
     * them. 80 m is a little over the distance at which two symbols collide at
     * navigation zoom.
     */
    const val SIGNAL_CLEAR_OF_JUNCTION_M = 80.0

    /** Metres per degree of latitude. Spherical, ample at these distances. */
    private const val M_PER_DEG_LAT = 111_320.0

    /**
     * Maneuver types that are not decisions.
     *
     * `depart` is where the driver already is, `arrive` is where the
     * destination pin already is, and a `continue` is by definition not a
     * choice. Hanging a pill on any of them spends the driver's attention on
     * something they do not have to do.
     */
    private val NOT_A_DECISION = setOf(
        "depart", "arrive", "continue", "straight", "new-name", "notification",
    )

    // ---- the build ---------------------------------------------------------

    /**
     * Every callout for [route], in along-route order.
     *
     * The whole route at once, not a window around the vehicle: these are facts
     * about the road's shape and the router's plan, neither of which changes as
     * the driver moves, so the caller can build the source once per route and
     * never touch it again. A window would have to be rebuilt every few
     * seconds for no gain.
     */
    fun build(
        route: RouteIndex,
        junctions: List<Junction> = emptyList(),
        speed: SpeedChange? = null,
        /**
         * The signals this route passes (V7 Stage 5), in route order.
         *
         * The pill they produce carries the LOCATION fact only
         * ([SignalText.LOCATION]) — a phase claim is a per-tick prediction
         * and belongs in the live HUD slot, not baked into route geometry
         * drawn once. A signal is suppressed within
         * [SIGNAL_CLEAR_OF_JUNCTION_M] of a drawn maneuver callout, for the
         * same reason a bend is.
         */
        signals: List<dev.vector.geo.signal.MatchedSignal> = emptyList(),
        /**
         * The cameras this route passes (V7.3), in route order.
         *
         * Same rule as the signals: the pill carries the camera the SOURCE
         * says is there — [dev.vector.geo.camera.CameraText.line] of its type,
         * so a speed camera and an average-speed camera do not read the same;
         * the live distance is the HUD line's. A camera at a drawn maneuver is
         * suppressed within [SIGNAL_CLEAR_OF_JUNCTION_M], the same collision
         * doctrine.
         *
         * The pill is a LOCATION, never a state: it is drawn once with the
         * route and re-sliced as the vehicle moves, so nothing about it can
         * pulse, change colour, or claim the camera is working.
         */
        cameras: List<dev.vector.geo.camera.MatchedCamera> = emptyList(),
        leaderM: Double = LEADER_M,
        /**
         * Whether to derive bend warnings from the route's curvature.
         *
         * `true` for driving, which is what this was written for: a curve sharp
         * enough to matter at the speed a car takes it is a warning, and the
         * driving screenshot log shows them firing every few hundred metres on a
         * real Doha route.
         *
         * **`false` for walking** (V7 traffic lights), and the reason is that
         * the warning does not mean anything on foot. It is derived from
         * CURVATURE alone — no maneuver, no tag — and curvature at 1.35 m/s is
         * just the shape of the pavement. A walker following a footway around a
         * corner does nothing different, so the pill would spend attention on a
         * non-decision, and it would arrive carrying driving vocabulary ("Sharp
         * bend") for a situation walking has no word for. The walking map draws
         * the surveyed signals this stage adds and nothing else.
         *
         * A parameter rather than a second renderer: the vocabulary, the
         * collision doctrine and the source-attribution rule stay in one place.
         */
        curvatureWarnings: Boolean = true,
    ): List<Callout> {
        if (route.coords.size < 2) return emptyList()
        val out = ArrayList<Callout>()

        val decisions = junctions.filter {
            it.type.lowercase() !in NOT_A_DECISION &&
                it.cumulativeM > 0.0 && it.cumulativeM < route.totalM
        }
        for (j in decisions) {
            val text = junctionText(j) ?: continue
            place(route, j.cumulativeM, sideFor(j.type), leaderM)?.let { (a, l) ->
                out.add(
                    Callout(
                        Kind.JUNCTION, j.cumulativeM, a, l, text,
                        // The maneuver this came from, named precisely enough
                        // to find it again in the route response.
                        source = "maneuver:${j.type}@${j.cumulativeM.toInt()}m",
                    )
                )
            }
        }

        if (curvatureWarnings) for ((atM, turnDeg) in bends(route, decisions)) {
            // Outside of the bend, so the pill is never over the road the
            // driver is about to be on.
            place(route, atM, if (turnDeg > 0) -1 else 1, leaderM)?.let { (a, l) ->
                out.add(
                    Callout(
                        Kind.BEND, atM, a, l, "Sharp bend",
                        source = "curvature:${turnDeg.toInt()}deg/${BEND_WINDOW_M.toInt()}m",
                    )
                )
            }
        }

        speed?.let { s ->
            if (s.limitKmh > 0 && s.alongM > 0.0 && s.alongM < route.totalM) {
                place(route, s.alongM, 1, leaderM)?.let { (a, l) ->
                    out.add(
                        Callout(
                            Kind.SPEED, s.alongM, a, l, "${s.limitKmh} km/h",
                            source = "maxspeed:${s.limitKmh}",
                        )
                    )
                }
            }
        }

        for (sig in signals) {
            val atM = sig.approach.alongM
            if (atM < 0.0 || atM > route.totalM) continue
            // A signal at a drawn maneuver is the maneuver. Suppressed rather
            // than overlapped; see SIGNAL_CLEAR_OF_JUNCTION_M.
            if (decisions.any { kotlin.math.abs(it.cumulativeM - atM) < SIGNAL_CLEAR_OF_JUNCTION_M }) {
                continue
            }
            place(route, atM, 1, leaderM)?.let { (a, l) ->
                out.add(
                    Callout(
                        Kind.SIGNAL, atM, a, l, dev.vector.geo.signal.SignalText.LOCATION,
                        // Identifiable and traceable back to the catalog entry:
                        // signal:<osm/node id>@<along_m>m, the same id the
                        // /navigate wire and the telemetry use.
                        source = "signal:${sig.ref.id}@${atM.toInt()}m",
                        icon = Icon.SIGNAL,
                    )
                )
            }
        }

        for (cam in cameras) {
            val atM = cam.approach.alongM
            if (atM < 0.0 || atM > route.totalM) continue
            // The sentence for the type the SOURCE stated, or nothing at all.
            // A camera whose source states no type never reaches a profile
            // (CameraMatcher filters it), and this is the second refusal in the
            // same place: no honest sentence, no pill.
            val text = dev.vector.geo.camera.CameraText.line(cam.ref.type) ?: continue
            // A camera at a drawn maneuver is the maneuver, the same
            // suppression the signals use: one pill per decision point.
            if (decisions.any { kotlin.math.abs(it.cumulativeM - atM) < SIGNAL_CLEAR_OF_JUNCTION_M }) {
                continue
            }
            place(route, atM, 1, leaderM)?.let { (a, l) ->
                out.add(
                    Callout(
                        Kind.CAMERA, atM, a, l, text,
                        // camera:<osm/node id>@<along_m>m, the same id the
                        // /navigate wire and the telemetry use.
                        source = "camera:${cam.ref.id}@${atM.toInt()}m",
                        icon = cameraIcon(cam.ref.type),
                    )
                )
            }
        }

        return out.sortedBy { it.alongM }
    }

    /**
     * The glyph for a camera [type].
     *
     * ## What is drawn, and what the source actually contains
     *
     * Measured on the deployed catalogues (`qatar_cameras.geojson`, built by
     * `vector_routing.camera_catalog`): **all 176 devices in Qatar are
     * `highway=speed_camera`**. `enforcement=*` is absent from the country's OSM
     * data entirely, so nothing is classified as red-light, average-speed or
     * non-fixed, and [CameraType.UNKNOWN] cannot reach here at all — a camera
     * with no stated type has no sentence and is dropped above.
     *
     * [CameraType.RED_LIGHT] is therefore reachable only from a backend that
     * classifies a tag Qatar does not currently carry, which is a fact about the
     * data and not about this branch.
     */
    private fun cameraIcon(type: CameraType): String = when (type) {
        CameraType.RED_LIGHT -> Icon.CAMERA_RED_LIGHT
        // Fixed, section-control and combined all reach the driver as "a camera
        // on a post", which is the distinction the picture is for. The pill's
        // words carry which kind it is — CameraText names the average-speed
        // stretch explicitly — so the glyph does not have to.
        CameraType.SPEED, CameraType.AVERAGE_SPEED, CameraType.COMBINED -> Icon.CAMERA_SPEED
        CameraType.UNKNOWN -> Icon.CAMERA_SPEED
    }

    /**
     * The pill's words.
     *
     * Short, because a pill that needs reading is worse than no pill — this is
     * something a driver takes in peripherally. The exit number wins when there
     * is one: it is what is painted on the gantry and often the only thing
     * anyone reads at speed. Returns null when there is nothing worth saying,
     * which is not the same as an empty string.
     */
    private fun junctionText(j: Junction): String? {
        j.exitRef?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Exit $it" }
        return when (val t = j.type.lowercase()) {
            "turn-left", "turn_left", "left" -> "Left"
            "turn-right", "turn_right", "right" -> "Right"
            "turn-sharp-left", "sharp-left" -> "Sharp left"
            "turn-sharp-right", "sharp-right" -> "Sharp right"
            "turn-slight-left", "slight-left" -> "Slight left"
            "turn-slight-right", "slight-right" -> "Slight right"
            "keep-left", "fork-left" -> "Keep left"
            "keep-right", "fork-right" -> "Keep right"
            "roundabout", "rotary", "roundabout-turn" -> "Roundabout"
            "merge" -> "Merge"
            "on-ramp", "ramp" -> "Ramp"
            "off-ramp", "exit" -> "Exit"
            "uturn", "u-turn" -> "U-turn"
            // An unknown maneuver type is not a reason to invent a word for it.
            else -> if (t.isBlank()) null else null
        }
    }

    /** Which side of the road to hang a junction's pill on: away from the turn. */
    private fun sideFor(type: String): Int =
        if (type.lowercase().contains("right")) -1 else 1

    /**
     * Anchor and label positions for a callout [atM] along the route.
     *
     * [side] is +1 for the right of travel and -1 for the left. The bearing is
     * taken across a short span rather than from one segment so the leader is
     * square to the road even on a curve.
     */
    private fun place(
        route: RouteIndex,
        atM: Double,
        side: Int,
        leaderM: Double,
    ): Pair<LngLat, LngLat>? {
        val at = route.pointAt(atM) ?: return null
        val ahead = route.pointAt((atM + 12.0).coerceAtMost(route.totalM)) ?: return null
        val brgDeg =
            if (abs(ahead.position.lng - at.position.lng) < 1e-12 &&
                abs(ahead.position.lat - at.position.lat) < 1e-12
            ) at.bearing
            else RouteGeometry.bearingDeg(at.position, ahead.position)
        val perp = Math.toRadians(brgDeg + 90.0 * side)
        val mPerDegLng = M_PER_DEG_LAT * cos(Math.toRadians(at.position.lat))
        if (mPerDegLng <= 0.0) return null
        val label = LngLat(
            at.position.lng + leaderM * sin(perp) / mPerDegLng,
            at.position.lat + leaderM * cos(perp) / M_PER_DEG_LAT,
        )
        return at.position to label
    }

    /**
     * Bends the router did not call a maneuver at, as (alongM, signed degrees).
     *
     * Walked at a fixed step rather than per vertex for the same reason the
     * chevrons are: shape-point density varies by two orders of magnitude
     * across the network, and a per-vertex measure would report the curvature
     * of the mapping rather than of the road.
     */
    private fun bends(route: RouteIndex, junctions: List<Junction>): List<Pair<Double, Double>> {
        val step = 10.0
        val from = BEND_WINDOW_M
        val to = route.totalM - BEND_WINDOW_M
        if (to <= from) return emptyList()

        val hits = ArrayList<Pair<Double, Double>>()
        var d = from
        while (d <= to) {
            val a = route.pointAt(d - BEND_WINDOW_M / 2)
            val b = route.pointAt(d + BEND_WINDOW_M / 2)
            if (a != null && b != null) {
                val turn = RouteGeometry.angDiffDeg(b.bearing, a.bearing)
                if (abs(turn) >= BEND_TURN_DEG &&
                    junctions.none { abs(it.cumulativeM - d) < BEND_CLEAR_OF_JUNCTION_M }
                ) {
                    hits.add(d to turn)
                }
            }
            d += step
        }

        // Merge runs into their sharpest point, so one bend is one pill.
        val merged = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < hits.size) {
            var j = i
            var best = hits[i]
            while (j + 1 < hits.size && hits[j + 1].first - hits[j].first <= BEND_MERGE_M) {
                j++
                if (abs(hits[j].second) > abs(best.second)) best = hits[j]
            }
            merged.add(best)
            i = j + 1
        }
        return merged
    }
}
