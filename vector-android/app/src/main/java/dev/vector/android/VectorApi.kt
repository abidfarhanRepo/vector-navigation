package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.journey.WalkLeg
import dev.vector.geo.sun.WalkSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the Vector backend (ADR-0075).
 *
 * Deliberately thin and dependency-free: the services already speak plain JSON
 * over HTTP, and adding Retrofit/OkHttp here would buy nothing but a larger APK
 * and another version to keep current.
 *
 * Auth note: `/tiles` and `/glyphs` are public (MapLibre cannot attach an
 * Authorization header to its tile fetches, adr-0059), while `/navigate`,
 * `/search` and `/along` are bearer-gated. So the style needs no token and this
 * class does.
 */
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

class VectorApi(
    private val base: String = BuildConfig.API_BASE,
    private val token: String = BuildConfig.API_TOKEN,
) {

    data class Step(
        /** Maneuver type: turn-left, roundabout, arrive, ... Drives the icon and
         *  the voice-guidance rules, so it must not be dropped. */
        val type: String,
        val instruction: String,
        val distanceM: Double,
        val cumulativeM: Double,
        /** Raw OSM `turn:lanes` of the APPROACH, e.g. "left|through|through". */
        val turnLanes: String? = null,
        /**
         * Lane count under the vehicle as it approaches, when the map has one.
         *
         * Lets the ribbon/highlight be lane-true without a tile join. Absent
         * means the map has no count — never a default.
         */
        val approachLanes: Int? = null,
        /**
         * Lane provenance + the `turn:lanes` RESOLVED for the direction driven.
         *
         * The backend resolves OSM's way-ordered string into driver order
         * (`laneData.turnLanes`), so the client does not mirror strings, and
         * says whether any lane data existed at all (`source`), so "no data" is
         * distinguishable from "no lane choice". `preferred` is deliberately
         * always null today: the backend only fills it with real provenance.
         */
        val laneData: LaneData? = null,
        /** Motorway exit number, when the junction carries one. */
        val exitRef: String? = null,
        /** Signposted destination, e.g. "Al Wakrah". */
        val destination: String? = null,
        /**
         * The road this leg is driven on.
         *
         * Separate from [instruction], which is a sentence that happens to
         * mention it: a client cannot recover "شارع الكورنيش" by parsing
         * "Continue on شارع الكورنيش", and the backend has the name in hand.
         */
        val road: String? = null,
    )

    /**
     * Backend lane provenance (V7 Stage 1).
     *
     * Every step with an approach carries this. [source] is `"turn:lanes"`
     * when the map had a usable string for the direction driven and `"none"`
     * when it did not — the two drive different behaviour downstream. All
     * fields parse defensively: an absent object or missing key is null, never
     * a guessed value.
     */
    data class LaneData(
        /** `"turn:lanes"` when the approach had a usable string, else `"none"`. */
        val source: String? = null,
        /** Traversal relative to the OSM way: "forward" / "backward" / "unknown". */
        val direction: String? = null,
        /**
         * The lane string in the DRIVER's left-to-right order.
         *
         * Prefer this over [Step.turnLanes], which is the raw way-ordered tag
         * kept on the wire for compatibility with older clients.
         */
        val turnLanes: String? = null,
        /**
         * Lanes in the direction being DRIVEN, when the map knows (V7 Stage 4).
         *
         * Not [Step.approachLanes], and the difference is the whole point: OSM
         * counts both directions in `lanes`, so a two-way `lanes=2` residential
         * street is ONE lane each way. Sizing the route from `approachLanes` and
         * centring it on the way centreline draws it across the oncoming lane —
         * on 5,728 Qatari ways.
         *
         * Null means the backend could not say, which is a real answer and not
         * a missing one: an odd two-way lane count has a lane whose direction
         * OSM never recorded. Treat null as "stay on the centreline", never as
         * a count to guess at.
         */
        val forwardLanes: Int? = null,
        /**
         * Backend-provenanced preferred lane indices. Always empty today; see
         * [dev.vector.geo.LaneGuidance.preferredIndices] for why preference is
         * never inferred.
         */
        val preferred: List<Int>? = null,
        /** Why the backend preferred those lanes, when it did. */
        val preferredReason: String? = null,
    )

    data class Route(
        val geometry: List<LngLat>,
        val distanceM: Double,
        val durationS: Double,
        val steps: List<Step>,
        /** Worst endpoint snap distance, so the UI can warn when it is large. */
        val snapMaxM: Double,
        /**
         * The signals this route passes (V7 Stage 5), in route order.
         *
         * Empty for a backend without a baked catalog, which is a VALID state
         * and exactly what an old client always saw: no signal markers, no
         * claims. Each entry is either already projected ([Signal.alongM])
         * or a bare point the client projects itself.
         */
        val signals: List<Signal> = emptyList(),
        /**
         * The cameras this route passes (V7.3), in route order.
         *
         * Same rule as [signals]: empty for a backend without a baked
         * catalog, which is a VALID state and exactly what an old client
         * always saw. Each entry carries location + provenance only
         * (maxspeed/direction) — never an activity claim.
         */
        val cameras: List<Camera> = emptyList(),
        /**
         * How far the ORIGIN had to move to reach the road network.
         *
         * Separate from [snapMaxM], which is the worse of the two endpoints and
         * is therefore usually the destination's. The two are not
         * interchangeable and V5 found a defect in treating them as though they
         * were: [NavSession.rerouteCouldHelp] used `snapMaxM` as evidence about
         * where the *driver* was, so a journey planned to a mall car park 40 m
         * from the nearest road disabled rerouting for its entire length. See
         * `DriveScenarioTest.a_far_destination_does_not_disable_rerouting`.
         *
         * The origin snap is the honest measurement of "could the network
         * represent where this request came from", which is the question being
         * asked. It is also re-measured on every reroute, so it describes the
         * driver's current position rather than the journey's first one.
         */
        val originSnapM: Double,
        /** How far the DESTINATION had to move to reach the road network. */
        val destSnapM: Double,
    )

    /**
     * One signal the route passes, as the backend placed it (V7 Stage 5).
     *
     * @property alongM null when the backend sent a bare point; the client
     *   then projects it onto the route itself (the old-backend fallback).
     */
    data class Signal(
        val id: String,
        val position: LngLat,
        val alongM: Double? = null,
        val approachBearingDeg: Double? = null,
    )

    /**
     * One camera the route passes, as the backend placed it (V7.3).
     *
     * @property type what the SOURCE says this camera is, as the backend
     *   classified it. Unknown/unrecognised reads as
     *   [dev.vector.geo.camera.CameraType.UNKNOWN], which is never announced:
     *   an absent type is not a licence to assume "speed camera".
     * @property alongM null when the backend sent a bare point; the client
     *   then projects it onto the route itself (the old-backend fallback).
     * @property maxspeed null when the mapper did not record one. Provenance
     *   only — it is never a claim that the camera is enforcing.
     * @property direction raw OSM provenance; the client's gate validates it.
     */
    data class Camera(
        val id: String,
        val position: LngLat,
        val type: dev.vector.geo.camera.CameraType = dev.vector.geo.camera.CameraType.UNKNOWN,
        val alongM: Double? = null,
        val approachBearingDeg: Double? = null,
        val maxspeed: String? = null,
        val direction: String? = null,
    )

    /**
     * A search result.
     *
     * [kind] is the basemap LAYER the feature came from — "poi", "park",
     * "road" — and is kept because callers already use it, but it is not
     * something to show a driver: every one of Qatar's 8,735 POIs has
     * `kind == "poi"`.
     *
     * [category] is the real one: the OSM tag (`restaurant`, `cafe`,
     * `supermarket`, `place_of_worship`). It was in the index all along as
     * `poi_class` and the geocoder never emitted it, so a search for "souq"
     * came back as four rows reading "poi".
     */
    /**
     * One name the geocoder offers for a coordinate, and how far off it is.
     *
     * [kind] is the server's own layer vocabulary — `poi`, `road`, `label`,
     * `park` — and is what lets [PinName] tell "the street this pin is on" from
     * "a shop next to it".
     */
    data class ReverseHit(
        val name: String,
        val kind: String?,
        val distanceM: Double,
    )

    data class Place(
        val name: String,
        val position: LngLat,
        val kind: String?,
        val category: String? = null,
    ) {
        /**
         * The category as a person would write it, or null.
         *
         * OSM tags are snake_case, so `place_of_worship` becomes "Place of
         * worship". Returns null rather than falling back to [kind]: "poi" and
         * "label" are internal layer names, and showing one is the
         * developer-terminology problem this exists to fix, not a degradation
         * of it.
         */
        val categoryLabel: String?
            get() {
                val c = category?.takeIf { it.isNotBlank() } ?: return null
                val words = c.replace('_', ' ').trim()
                return words.replaceFirstChar { it.uppercase() }
            }
    }


    /**
     * A string field, or null — treating JSON `null` as absent.
     *
     * `JSONObject.optString(key, "")` returns the four-character string
     * **`"null"`** when the value is JSON null, not the fallback. It is a
     * documented quirk of `org.json` and it is a trap every codebase using it
     * falls into exactly once.
     *
     * Vector fell into it in V4 and it reached the S24: `/speed` answers
     * `{"ref": null}` for a road with no route number, so the
     * road-you-are-on readout rendered **"null · Ibn Katheer Street"** —
     * caught in `v4-evidence/vector/after-01-explore.png`, which is the
     * screenshot this helper exists because of.
     *
     * `isNull` is checked FIRST, so a real JSON null is absent rather than the
     * word. The blank check stays for a field present as `""`.
     */
    internal fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    /** An int field, or null when absent — `optInt`'s 0 default would read as a real lane count. */
    internal fun JSONObject.intOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key, -1).takeIf { it >= 0 }
    }

    /**
     * Parse `lane_data` defensively. A missing object, a missing key, or a
     * JSON null all read as null/empty — never as a guess (e.g. a 0 lane
     * count underneath an absent `approach_lanes` would silently widen the
     * ribbon to nothing).
     */
    internal fun JSONObject.laneDataOrNull(key: String): LaneData? {
        if (!has(key) || isNull(key)) return null
        val o = optJSONObject(key) ?: return null
        val preferred = o.optJSONArray("preferred")?.let { arr ->
            (0 until arr.length()).map { arr.optInt(it, -1) }
                .filter { it >= 0 }
        }
        return LaneData(
            source = o.stringOrNull("source"),
            direction = o.stringOrNull("direction"),
            turnLanes = o.stringOrNull("turn_lanes"),
            // Present-and-null on every modern step, so `intOrNull`'s
            // absent-vs-zero care matters here as much as for `approach_lanes`:
            // a 0 read as a count would collapse the route to no width at all.
            forwardLanes = o.intOrNull("forward_lanes"),
            preferred = preferred,
            preferredReason = o.stringOrNull("preferred_reason"),
        )
    }

    /**
     * The language road names should come back in.
     *
     * Reported from the S24: *"the names of the roads came in arabic, it has to
     * be in english too."* 48,427 of Qatar's 48,680 named roads carry
     * `name:en` — 99.5% — and the backend read `name` only, so an
     * English-language phone was told "Continue on شارع الكورنيش" and offered a
     * route "via العروبة".
     *
     * Taken from the DEVICE LOCALE rather than hardcoded to English: a driver
     * whose phone is in Arabic should keep the Arabic names, which are what is
     * painted on the signs beside them. The server falls back to the local name
     * when a road has no translation, so this can never blank a name out.
     */
    private fun langParam(): String =
        "&lang=" + java.util.Locale.getDefault().language.ifEmpty { "en" }

    /** Turn-by-turn route. Uses /navigate, not /route: only /navigate returns steps. */
    suspend fun navigate(from: LngLat, to: LngLat): Route = withContext(Dispatchers.IO) {
        val url = "$base/navigate?from=${from.lat},${from.lng}&to=${to.lat},${to.lng}" +
            langParam()
        parseRouteFeature(getJson(url).getJSONArray("features").getJSONObject(0))
    }

    /**
     * Several full routes to the same destination, best first.
     *
     * `/navigate?alternatives=1`, NOT `/route?alternatives=1`. The distinction
     * is the whole reason this method can exist: `/route` returned several
     * geometries and no `steps`, so a client could draw a choice it had no way
     * to drive — and re-requesting `/navigate` for the same endpoints returns
     * the primary route, because nothing identified the option the driver
     * picked. Every feature returned here carries its own steps and a
     * `route_id`.
     */
    suspend fun navigateAlternatives(
        from: LngLat,
        to: LngLat,
        wanted: Int = 3,
    ): List<RouteOption> = withContext(Dispatchers.IO) {
        val url = "$base/navigate?from=${from.lat},${from.lng}&to=${to.lat},${to.lng}" +
            "&alternatives=1&count=$wanted" + langParam()
        parseRouteOptions(getJson(url))
    }

    /**
     * The parse half of [navigateAlternatives], split out so a fixture can be
     * driven through the PRODUCTION parser (the same arrangement as
     * [parseRouteFeature] — V5 §20's instruction against test-only behaviour
     * applies to inputs as much as to outputs).
     */
    internal fun parseRouteOptions(root: JSONObject): List<RouteOption> {
        val feats = root.optJSONArray("features") ?: return emptyList()
        return (0 until feats.length()).map { i ->
            val f = feats.getJSONObject(i)
            val r = parseRouteFeature(f)
            val props = f.optJSONObject("properties") ?: JSONObject()
            RouteOption(
                geometry = r.geometry,
                distanceM = r.distanceM,
                durationS = r.durationS,
                maneuvers = r.steps.mapIndexed { j, s ->
                    Maneuver(j, s.type, s.instruction, s.distanceM, s.cumulativeM,
                             s.turnLanes, s.exitRef, s.destination, s.road,
                             s.approachLanes, s.laneData)
                },
                snapMaxM = r.snapMaxM,
                originSnapM = r.originSnapM,
                label = props.optString("label", ""),
                signals = r.signals,
                cameras = r.cameras,
            )
        }
    }

    /**
     * `internal`, not private, so the drive-scenario suite parses fixtures with
     * the PRODUCTION parser.
     *
     * The alternative is a test-side reader of the same JSON, and V5 §20's
     * instruction against test-only behaviour applies to inputs as much as to
     * outputs: a hand-rolled fixture loader would have quietly not reproduced
     * the `org.json` null trap, the kilometres-to-metres conversion or the
     * `snap` array's ordering, and the scenarios would then be driving routes
     * the app cannot actually build.
     */
    internal fun parseRouteFeature(feature: JSONObject): Route {
        val props = feature.optJSONObject("properties") ?: JSONObject()
        val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")

        val geometry = ArrayList<LngLat>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            geometry.add(LngLat(c.getDouble(0), c.getDouble(1)))
        }

        val steps = ArrayList<Step>()
        val stepsArr = props.optJSONArray("steps")
        if (stepsArr != null) {
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                steps.add(
                    Step(
                        type = s.optString("type", "continue"),
                        instruction = s.optString("instruction", ""),
                        distanceM = s.optDouble("distance_m", 0.0),
                        cumulativeM = s.optDouble("cumulative_distance_m", 0.0),
                        turnLanes = s.stringOrNull("turn_lanes"),
                        approachLanes = s.intOrNull("approach_lanes"),
                        laneData = s.laneDataOrNull("lane_data"),
                        exitRef = s.stringOrNull("exit_ref"),
                        destination = s.stringOrNull("destination"),
                        road = s.stringOrNull("road"),
                    )
                )
            }
        }

        // `snap` is ordered [origin, destination] and has been in the wire
        // format since V1; nothing read it, so only the max was available and
        // the two got conflated. Absent entries fall back to snapMaxM rather
        // than to zero: a missing measurement must not read as "on the road".
        val snapMax = props.optDouble("snap_max_m", 0.0)
        val snap = props.optJSONArray("snap")
        fun snapAt(i: Int): Double =
            snap?.optJSONObject(i)?.let {
                if (it.isNull("distance_m")) null else it.optDouble("distance_m", snapMax)
            } ?: snapMax

        // V7 Stage 5: the signals this route passes, defensively. An absent
        // array, a malformed entry, or a backend predating the field all read
        // as empty -- and empty is a valid state, not an error, because it is
        // exactly what the old wire always produced.
        val signals = ArrayList<Signal>()
        props.optJSONArray("signals")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val lon = s.optDouble("lon", Double.NaN)
                val lat = s.optDouble("lat", Double.NaN)
                if (lon.isNaN() || lat.isNaN()) continue
                signals.add(
                    Signal(
                        id = s.optString("id", "").ifBlank { "signal-$i" },
                        position = LngLat(lon, lat),
                        alongM = s.optDouble("along_m", Double.NaN)
                            .takeIf { !it.isNaN() },
                        approachBearingDeg = s.optDouble("approach_bearing", Double.NaN)
                            .takeIf { !it.isNaN() },
                    )
                )
            }
        }

        // V7.3: the cameras, defensively — an absent array, a malformed
        // entry, or a backend predating the field all read as empty, which is
        // a valid state (exactly what the old wire always produced).
        //
        // The `type` is parsed through the same null-safe helper as every
        // other optional string, and an absent or unrecognised value reads as
        // UNKNOWN — which the profile filters out. A backend that says nothing
        // about a camera's type gets no announcement for it: the one direction
        // this may fail in is silence.
        val cameras = ArrayList<Camera>()
        props.optJSONArray("cameras")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val lon = s.optDouble("lon", Double.NaN)
                val lat = s.optDouble("lat", Double.NaN)
                if (lon.isNaN() || lat.isNaN()) continue
                cameras.add(
                    Camera(
                        id = s.stringOrNull("id") ?: "camera-$i",
                        position = LngLat(lon, lat),
                        type = dev.vector.geo.camera.CameraType.fromWire(s.stringOrNull("type")),
                        alongM = s.optDouble("along_m", Double.NaN)
                            .takeIf { !it.isNaN() },
                        approachBearingDeg = s.optDouble("approach_bearing", Double.NaN)
                            .takeIf { !it.isNaN() },
                        maxspeed = s.stringOrNull("maxspeed"),
                        direction = s.stringOrNull("direction"),
                    )
                )
            }
        }

        return Route(
            geometry = geometry,
            // The service reports kilometres; everything inside this app is metres,
            // so convert once here rather than at each call site.
            distanceM = props.optDouble("distance_km", 0.0) * 1000.0,
            durationS = props.optDouble("duration_s", 0.0),
            steps = steps,
            snapMaxM = snapMax,
            signals = signals,
            cameras = cameras,
            originSnapM = snapAt(0),
            destSnapM = snapAt(1),
        )
    }

    /**
     * The walk, from `/foot`.
     *
     * Returns a [WalkLeg] rather than a `Route` because a walk is not a small
     * drive. It has no turn-by-turn steps (nobody needs "in 20 metres, turn
     * left" crossing a car park), it is measured in metres rather than
     * kilometres, and it carries the two things a drive never has to report:
     * how much of it is stairs, and what each segment is made of.
     *
     * ## Why the per-segment classes matter enough to parse
     *
     * The shade model runs ON DEVICE and assumes a facade whose height depends
     * on the road class. Without `classes` the client would have to assume one
     * class for the entire walk, and the safe assumption — a narrow footway
     * between low buildings — is exactly the one that would promise shade
     * along an arterial with nothing beside it.
     *
     * A backend that predates those arrays is handled rather than required:
     * the segments come back with a null class, which [dev.vector.geo.sun.ShadeEstimator]
     * treats as "no facade may be assumed" and reports as exposed with low
     * confidence. The walk still routes, still draws and still times; only the
     * shade estimate degrades, and it degrades toward claiming less.
     */
    suspend fun foot(from: LngLat, to: LngLat): WalkLeg = withContext(Dispatchers.IO) {
        val url = "$base/foot?from=${from.lat},${from.lng}&to=${to.lat},${to.lng}"
        parseFootFeature(getJson(url).getJSONArray("features").getJSONObject(0))
    }

    /**
     * The walk, as the full 4B.4 navigation contract (V7.4 4C final).
     *
     * ## Why this exists beside [foot] rather than replacing it
     *
     * [foot] returns a `WalkLeg` — geometry, distance, duration, steps and
     * per-segment classes — which is exactly what the journey card and the
     * shade overlay need, and four shipping features depend on its shape. This
     * returns the whole [dev.vector.geo.walk.WalkContract]: the maneuver plan,
     * the cost decomposition, the crossing and stairs facts and the snap
     * diagnostics, which is what walking NAVIGATION needs and what the journey
     * card has no use for. Widening the shipping type was the larger risk, so
     * the two parsers over one wire are a deliberate, documented cost (4C.1
     * §Unresolved 7).
     *
     * ## Why the refusal is a return value and not an exception
     *
     * Because the distinction is the product behaviour. On Qatar's pedestrian
     * graph — 2,811 components, the largest holding 32% of nodes — a
     * `pedestrian_network_split` is the COMMON refusal, and 4A.1 went to the
     * trouble of giving it its own `reason` on a 404 precisely so a client
     * could tell it from a generic failure. [getJson] throws on any non-2xx
     * and keeps only 200 characters of the body in a message string, so
     * routing this through it would reduce a structured, deliberate refusal to
     * a substring match on an exception. This reads the body.
     *
     * Never returns a route it did not receive, and never falls back to car
     * routing: the three outcomes are a walk, a refusal, or a transport
     * failure, and [WalkRouteResult] has exactly those three cases.
     */
    suspend fun footRoute(from: LngLat, to: LngLat): WalkRouteResult =
        withContext(Dispatchers.IO) {
            val url = "$base/foot?from=${from.lat},${from.lng}&to=${to.lat},${to.lng}" +
                "&profile=${dev.vector.geo.walk.WalkingProfile.GENERAL.wire}"
            val body = runCatching { getJsonAllowingError(url) }.getOrElse {
                // The request never reached an answer. Deliberately NOT
                // collapsed into NO_ROUTE: it says nothing about whether a
                // walk exists, and it is the one case where retrying is
                // sensible.
                return@withContext WalkRouteResult.Failed(
                    dev.vector.geo.walk.WalkRefusalKind.BACKEND_FAILURE, it,
                )
            }
            WalkRefusal.of(body)?.let {
                return@withContext WalkRouteResult.Refused(
                    when (it) {
                        WalkRefusal.NETWORK_SPLIT ->
                            dev.vector.geo.walk.WalkRefusalKind.NETWORK_SPLIT
                        WalkRefusal.NO_ROUTE ->
                            dev.vector.geo.walk.WalkRefusalKind.NO_ROUTE
                    }
                )
            }
            val contract = WalkContractParser.parseCollection(body)
                ?: return@withContext WalkRouteResult.Refused(
                    dev.vector.geo.walk.WalkRefusalKind.NO_ROUTE,
                )
            // The client-side mirror of the backend's own HTTP 400 on an
            // unknown profile (4B.4 D2). A walk routed under a profile this
            // client cannot name is not rendered, because this client could
            // not say what it was optimised for — see
            // `WalkContract.profileSupported`.
            if (!contract.profileSupported) {
                return@withContext WalkRouteResult.Refused(
                    dev.vector.geo.walk.WalkRefusalKind.NO_ROUTE,
                )
            }
            WalkRouteResult.Ok(contract)
        }

    /**
     * Like [getJson], but returns the body of an error response instead of
     * throwing.
     *
     * Only for endpoints whose refusals are STRUCTURED and meaningful — today
     * that is `/foot`, whose 404 carries the `reason` that separates a
     * pedestrian network split from a generic no-route. A transport failure
     * (no connection, a timeout, an unparseable body) still throws, because
     * that genuinely is an exception rather than an answer.
     */
    private fun getJsonAllowingError(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            // A 5xx or an auth failure is not a statement about the pedestrian
            // network, so it stays an exception and reaches BACKEND_FAILURE.
            // Only the refusals the contract actually defines come back as a
            // body to classify.
            if (code !in 200..299 && code != 404 && code != 400) {
                throw java.io.IOException("HTTP $code from $url: ${body.take(200)}")
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseFootFeature(feature: JSONObject): WalkLeg {
        val props = feature.optJSONObject("properties") ?: JSONObject()
        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
        val points = buildList {
            if (coords != null) for (i in 0 until coords.length()) {
                val c = coords.optJSONArray(i) ?: continue
                add(LngLat(c.getDouble(0), c.getDouble(1)))
            }
        }
        val classes = props.optJSONArray("classes")
        val enclosed = props.optJSONArray("enclosed")
        val area = props.optJSONArray("area")
        val segments = points.zipWithNext().mapIndexed { i, (a, b) ->
            WalkSegment(
                from = a,
                to = b,
                highway = classes?.takeIf { i < it.length() }?.optString(i)?.takeIf { it.isNotBlank() },
                covered = enclosed?.takeIf { i < it.length() }?.optBoolean(i) ?: false,
                area = area?.takeIf { i < it.length() }?.optBoolean(i) ?: false,
            )
        }
        return WalkLeg(
            segments = segments,
            distanceM = props.optDouble("distance_m", 0.0),
            durationS = props.optDouble("duration_s", 0.0),
            stepsM = props.optDouble("steps_m", 0.0),
            snapMaxM = props.optDouble("snap_max_m", 0.0),
        )
    }

    /**
     * What the geocoder can call a coordinate — every candidate, nearest first,
     * each with the distance it was actually ranked on.
     *
     * Used to name a dropped pin. A long-press previously produced the literal
     * string "Dropped pin", which then followed the driver into the destination
     * chip, the recents list and the arrival announcement — three places where
     * the geocoder already knew the answer and was never asked.
     *
     * ## Why this returns a list rather than the first name
     *
     * It used to return `features[0].name` and nothing else, which handed the
     * server's nearest-neighbour ranking the whole product decision. Measured
     * against production, that ranking is unbounded and kind-blind:
     *
     * * a pin dropped in the desert south of Doha was named **"Umm Hotta"**, a
     *   village **3,528 m** away, and the returned list contained nothing
     *   closer — so the destination chip and the arrival announcement both
     *   claimed a place 3.5 km from the coordinate the driver chose;
     * * a pin dropped on a random residential street in Mansoura was named
     *   after **"Princess Ladies Saloon"**, a shop 13.5 m away, rather than
     *   `ابن درهم`, the street it is actually on 27.7 m away — reported from
     *   the S24 as *"I cannot drop a pin in a random address, it just selects
     *   the nearest POI"*.
     *
     * The distance is normally read from the response, because `/reverse` sends
     * the one it RANKED on: for a way that is measured to its polyline, while
     * the feature's own `geometry` is a Point at the way's first vertex. Using
     * the vertex would reject the road the pin is standing on — a Doha side
     * street can pass 5 m from the pin with its first node 350 m away. The
     * local haversine is the fallback for a backend that predates the field,
     * and for the point features (`poi`, `label`, `park`) where the two agree.
     *
     * `limit` sits above the server's default of 3 on purpose. `PinName` needs
     * the nearest ROAD as well as the nearest POI, and a dense tile holds
     * several shops closer to the pin than the street outside them.
     */
    suspend fun reverseNear(at: LngLat, limit: Int = 8): List<ReverseHit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$base/reverse?lat=${at.lat}&lon=${at.lng}&limit=$limit" +
                    langParam()
                val feats = getJson(url).optJSONArray("features")
                    ?: return@runCatching emptyList()
                (0 until feats.length()).mapNotNull { i ->
                    val f = feats.getJSONObject(i)
                    val c = f.optJSONObject("geometry")?.optJSONArray("coordinates")
                        ?: return@mapNotNull null
                    val p = f.optJSONObject("properties") ?: JSONObject()
                    val name = p.stringOrNull("name") ?: p.stringOrNull("label")
                        ?: return@mapNotNull null
                    val lng = c.optDouble(0, Double.NaN)
                    val lat = c.optDouble(1, Double.NaN)
                    if (lng.isNaN() || lat.isNaN()) return@mapNotNull null
                    val serverM = p.optDouble("distance_m", Double.NaN)
                    ReverseHit(
                        name = name,
                        kind = p.stringOrNull("kind"),
                        distanceM = if (serverM.isFinite()) serverM
                        else RouteGeometry.haversineM(at.lng, at.lat, lng, lat),
                    )
                }.sortedBy { it.distanceM }
            }.getOrDefault(emptyList())
        }

    suspend fun search(query: String, near: LngLat?): List<Place> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildString {
            append("$base/search?q=$q")
            if (near != null) append("&lat=${near.lat}&lon=${near.lng}")
            // Same reason `navigate` and `roadHere` send it: a result row and
            // the map label beside it must not disagree about which language
            // Qatar's places are named in. Without this the geocoder answers
            // with the native `name`, which is how an Arabic row reached an
            // English Recents list.
            append(langParam())
        }
        val root = getJson(url)
        val feats = root.optJSONArray("features") ?: return@withContext emptyList()
        (0 until feats.length()).mapNotNull { i ->
            val f = feats.getJSONObject(i)
            val c = f.getJSONObject("geometry").optJSONArray("coordinates") ?: return@mapNotNull null
            val p = f.optJSONObject("properties") ?: JSONObject()
            Place(
                name = p.optString("name", p.optString("label", "?")),
                position = LngLat(c.getDouble(0), c.getDouble(1)),
                kind = p.stringOrNull("kind"),
                category = p.stringOrNull("category"),
            )
        }
    }

    /**
     * Upload one batch of traffic probes.
     *
     * The server owns every privacy rule: 200 m endpoint truncation and the k=5
     * promotion floor are applied there (adr-0065 / adr-0066), and this client
     * deliberately does NOT pre-truncate. A client that trimmed its own trace
     * would be a client that could choose not to, and ADR-0075 forbids a
     * collection path that bypasses the server gates.
     *
     * `source` is DECLARED, not derived: the server cannot tell a native app
     * from a browser on a shared endpoint, so it records what we say and labels
     * it as declared (adr-0070 §5).
     */
    suspend fun postProbes(
        trip: String,
        points: List<dev.vector.geo.ProbeBuffer.Probe>,
        end: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext true
        val arr = org.json.JSONArray()
        for (p in points) {
            // The privacy gate's schema is SHORT-KEYED: lng, lat, t (ms), a
            // (accuracy m), s (speed m/s), h (heading). Sending "ts"/"accuracy"
            // is accepted with HTTP 200 and then silently dropped as
            // `no_timestamp` — a 200 that stores nothing, which is exactly the
            // failure worth pinning (see vector_privacy/gate.py).
            val o = JSONObject()
                .put("lng", p.lng).put("lat", p.lat).put("t", p.ts)
            p.accuracyM?.let { o.put("a", it) }
            p.speedMs?.let { o.put("s", it) }
            p.bearingDeg?.let { o.put("h", it) }
            arr.put(o)
        }
        val body = JSONObject()
            .put("kind", "probe")
            .put("points", arr)
            .put("trip", trip)
            .put("source", "native")
            .apply { if (end) put("end", true) }
            .toString()
        postJson("$base/traces", body)
    }

    /**
     * Places of the given kinds near a point.
     *
     * Uses `/along` — the corridor search — with a two-point line straddling
     * [at], rather than a second endpoint. `/along` is already the one place
     * that knows how to filter the index by a driver-facing kind ("parking",
     * "fuel", "food"), and `/search` has no kind filter at all, so asking a
     * corridor question about a very short corridor reuses the right code
     * instead of adding a near-duplicate of it to the geocoder.
     *
     * V5 note: every kind returned zero features before this release. The
     * corridor filter compared the index's raw `kind` — the basemap LAYER
     * name, `"poi"` for all 8,735 of Qatar's POIs — against the user's kind,
     * and the category table it consulted was written for an Overture import
     * that has never been loaded. Both halves are fixed in
     * `vector_geocoder.kinds`.
     */
    suspend fun nearby(
        at: LngLat,
        kinds: String,
        radiusM: Int = 400,
        limit: Int = 5,
    ): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            // Two points 40 m apart, centred on the destination. `/along`
            // requires at least two coordinates because it is a corridor
            // search; a degenerate line would be rejected as malformed.
            val dLat = 20.0 / 111_320.0
            val body = """{"coordinates":[[${at.lng},${at.lat - dLat}],[${at.lng},${at.lat + dLat}]]}"""
            val root = postJsonForObject(
                "$base/along?radius=$radiusM&limit=$limit&kinds=$kinds", body
            ) ?: return@runCatching emptyList()
            val feats = root.optJSONArray("features") ?: return@runCatching emptyList()
            (0 until feats.length()).mapNotNull { i ->
                val f = feats.getJSONObject(i)
                val props = f.optJSONObject("properties") ?: return@mapNotNull null
                val c = f.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: return@mapNotNull null
                Place(
                    name = props.stringOrNull("name") ?: return@mapNotNull null,
                    position = LngLat(c.getDouble(0), c.getDouble(1)),
                    kind = props.stringOrNull("kind"),
                    category = props.stringOrNull("category"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Report what the drive actually took against what was predicted.
     *
     * `POST /eta` has existed in `vector-routing` since issue 07 and its own
     * comment says it is "called on navigation completion". Nothing called it.
     * The ETA-error distribution the evolution dashboard reads back from
     * `GET /eta` was therefore always empty, and the honest reason was not
     * that the loop was unbuilt — it was that its first step had no caller.
     *
     * Fire and forget: an arrival must never wait on, or be spoilt by, a
     * telemetry POST.
     */
    suspend fun postEta(predictedS: Double, observedS: Double, coverage: Double) {
        withContext(Dispatchers.IO) {
            runCatching {
                postJson(
                    "$base/eta",
                    """{"predicted_s":$predictedS,"observed_s":$observedS,"coverage":$coverage}""",
                )
            }
        }
    }

    /** POST that returns the parsed body, for the endpoints that answer with one. */
    private fun postJsonForObject(url: String, body: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(url: String, body: String): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The road under the vehicle: its limit, its name, and its route number.
     *
     * @property limitKmh posted or class-default limit, or null when neither is
     *   known. Null rather than a guess: showing an invented limit is worse
     *   than showing none, because the driver would act on it.
     * @property inferred true when [limitKmh] came from the road's class rather
     *   than a surveyed `maxspeed` tag. The geocoder is explicit that a caller
     *   must honour this — "presenting an inferred number as a posted sign is
     *   the kind of confident wrong answer a driver acts on".
     * @property name the road's name in the device's language.
     * @property ref its route number — "C Ring", "Q5". What is painted on the
     *   gantry, and often the only thing a driver reads at speed.
     */
    data class RoadHere(
        val limitKmh: Int?,
        val inferred: Boolean,
        val name: String?,
        val ref: String?,
    )

    /**
     * What road are we on?
     *
     * **The same request that already fetched the speed limit.** This used to
     * be `speedLimit(): Int?`, which threw away everything except the number —
     * and `/speed` has always answered with the road's `name` as well, because
     * it has to find the road in order to know its limit. `name:en` and `ref`
     * were sitting in the geocoder's `raw` dict unread; V4 returns them (see
     * `vector_geocoder.index.speed`).
     *
     * So the "which road am I on" readout that both reference products show
     * under the vehicle — and that Vector had no equivalent of at all — costs
     * **zero extra requests**. It is a field on a reply the client was already
     * receiving once every 150 m.
     *
     * `lang` is sent for the same reason [navigate] sends it: the banner and
     * this readout sit on the same screen and must not disagree about which
     * language Qatar's roads are named in.
     */
    suspend fun roadHere(at: LngLat): RoadHere? = withContext(Dispatchers.IO) {
        runCatching {
            val o = getJson("$base/speed?lat=${at.lat}&lon=${at.lng}" + langParam())
            val v = o.optInt("maxspeed_kmh", -1)
            RoadHere(
                limitKmh = if (v > 0) v else null,
                // "default" is the class median; anything else that carries a
                // number is a surveyed tag.
                inferred = o.optString("source", "") == "default",
                name = o.stringOrNull("name"),
                ref = o.stringOrNull("ref"),
            )
        }.getOrNull()
    }

    data class Traffic(val geoJson: String, val jamCount: Int)

    /**
     * Live congestion. Returns the raw GeoJSON so it can go straight into the
     * map source without a parse/re-serialise round trip, plus the jam count for
     * the HUD.
     *
     * Free-flowing segments are stripped here rather than in the style: they are
     * ~92% of the payload and the map never draws them.
     */
    suspend fun traffic(): Traffic? = withContext(Dispatchers.IO) {
        runCatching {
            val root = getJson("$base/traffic")
            val feats = root.optJSONArray("features") ?: return@runCatching Traffic(EMPTY_FC, 0)
            val kept = org.json.JSONArray()
            var jams = 0
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val c = f.optJSONObject("properties")?.optString("congestion") ?: continue
                if (c == "free") continue
                if (c == "jammed") jams++
                kept.put(f)
            }
            Traffic(
                JSONObject().put("type", "FeatureCollection").put("features", kept).toString(),
                jams,
            )
        }.getOrNull()
    }

    /**
     * What the tile set actually contains.
     *
     * `epoch` rides in the tile URL so a re-bake busts every cache. `minZoom`
     * and `maxZoom` are the zooms that were BAKED, and the style must declare
     * exactly those: MapLibre only overzooms ABOVE the declared maximum, so a
     * style claiming a zoom the bake does not have requests tiles that 404 and
     * draws nothing. Verified on an S24 Ultra — declaring 14 against a z11-13
     * bake made the map go completely black past z13, and navigation sets the
     * camera to 16.5.
     *
     * The defaults match the current bake and only apply when the server is too
     * old to report a range; a WRONG range is the failure being fixed, so the
     * fallback is the conservative one rather than the optimistic one.
     */
    data class TileSet(
        val epoch: Long,
        val minZoom: Int,
        val maxZoom: Int,
        /**
         * The release this tile set declares itself to be, or "" .
         *
         * V7.7. Empty is a first-class state, not a missing value: it means
         * the server reported no release, either because it predates the
         * contract or because the volume it serves has no `RELEASE.json` —
         * which production's does not, deliberately, until the `TILE_DIR`
         * migration happens. The server distinguishes those two by sending
         * `release` present-and-empty rather than omitting it, but this client
         * needs no such distinction: both mean "fall back to the epoch".
         */
        val release: String = "",
    ) {
        /**
         * The identity this tile set is known by — in the URL, and in every
         * comparison.
         *
         * ONE rule, used for both, on purpose. The `?v=` parameter's whole job
         * is to give each release its own URL space in the client's cache
         * (AC-19 measured this on production: two releases coexisted without
         * colliding precisely because their URLs differed). If the app
         * compared releases by one rule and wrote URLs by another, the two
         * would eventually disagree, and the symptom would be a cache serving
         * one release's bytes for another — correctly per HTTP, wrongly per
         * product.
         *
         * The release id is preferred because it is collision-free by
         * construction and self-describing: a tile URL in a proxy log or a HAR
         * names its release with no lookup table. The epoch remains the
         * fallback so a server that has not been upgraded still busts caches
         * exactly as it does today.
         */
        val token: String get() = if (release.isNotEmpty()) release else epoch.toString()
    }

    /**
     * The tile set, or the conservative default if anything at all goes wrong.
     *
     * TOTAL BY DESIGN, and it must stay that way: this is the cold-start path,
     * and a client that cannot reach the server still has to build some style
     * rather than no style. The z11-13 default is the conservative guess, for
     * the reason [TileSet]'s KDoc gives.
     *
     * **Do not use this to check for a release change.** Its failure mode is a
     * valid-looking value, so a timeout would read as "the release changed to
     * epoch 0" and trigger a restyle on a network blip. Use [tileSetOrNull],
     * which distinguishes the two.
     */
    suspend fun tileSet(): TileSet = tileSetOrNull() ?: TileSet(0L, 11, 13)

    /**
     * The tile set, or **null** if the server could not be asked or understood.
     *
     * The distinction [tileSet] cannot make, and the one a release check needs.
     * A failed check must preserve the running release: adopting anything on a
     * failure would mean a driver's style could be rebuilt by a dropped
     * connection.
     *
     * `getJson` already throws on a non-2xx, so a 404, a 500, a timeout, a
     * refused connection and a body that is not JSON all arrive here as null.
     * A body that IS JSON but lacks fields is not a failure — the `opt*`
     * defaults apply, exactly as they did before V7.7.
     */
    suspend fun tileSetOrNull(): TileSet? = withContext(Dispatchers.IO) {
        runCatching {
            val j = getJson("$base/tiles/version")
            TileSet(
                j.optLong("epoch", 0L),
                j.optInt("minzoom", 11),
                j.optInt("maxzoom", 13),
                // `stringOrNull`, NOT `optString`: `optString(key, "")` returns
                // the four-character string "null" for a JSON null rather than
                // the fallback, which is the `org.json` trap that put the word
                // "null" on an S24 screen in V4. Here it would be worse than
                // cosmetic — "null" is a perfectly good cache key, so every
                // tile URL would carry `?v=null` and two genuinely different
                // releases would share one URL space.
                j.stringOrNull("release") ?: "",
            )
        }.getOrNull()
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code from $url: ${body.take(200)}")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
