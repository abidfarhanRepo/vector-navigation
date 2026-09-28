package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.walk.NavMode
import dev.vector.geo.walk.WalkContract
import dev.vector.geo.walk.WalkCost
import dev.vector.geo.walk.WalkCrossing
import dev.vector.geo.walk.WalkCrossingExposure
import dev.vector.geo.walk.WalkDiagnostics
import dev.vector.geo.walk.WalkExposure
import dev.vector.geo.walk.WalkFact
import dev.vector.geo.walk.WalkInclineExposure
import dev.vector.geo.walk.WalkManeuver
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkRoadId
import dev.vector.geo.walk.WalkSegments
import dev.vector.geo.walk.WalkSignal
import dev.vector.geo.walk.WalkStairs
import dev.vector.geo.walk.WalkStairsExposure
import dev.vector.geo.walk.WalkTurn
import dev.vector.geo.walk.WalkingProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parse the V7.4 4B.4 `/foot` contract into [WalkContract] (V7.4 4C.1).
 *
 * ## Why this is a separate parser and not a change to `parseFootFeature`
 *
 * [VectorApi.parseFootFeature] produces a `WalkLeg`: geometry, distance,
 * duration, steps and per-segment classes — exactly what the shade overlay and
 * the journey card need, and nothing more. It is deliberately untouched. The
 * 4B.4 audit established that every 4B-era addition is ADDITIVE and that the
 * existing parser needs no change for any of them, and changing it now would
 * put the walking-UX work on the critical path of the journey card that
 * already ships.
 *
 * So this is a second, wider read of the SAME payload for the walking
 * navigation path. Two parsers over one wire is a real cost; it is paid
 * because the alternative is widening a type that four shipping features
 * depend on.
 *
 * ## The one rule every line here follows
 *
 * **Null in, null out.** `org.json` makes that harder than it sounds:
 * `optString(key, "")` returns the four-character string `"null"` on a JSON
 * null on Android (see [VectorApiParseTest], where this reached a handset and
 * printed "null · Ibn Katheer Street"). Every string read below therefore goes
 * through a null-checked helper, and every numeric read distinguishes absent
 * from zero — because on this contract `step_count: null` means nobody counted
 * the steps and `0` would be a claim.
 */
object WalkContractParser {

    /** A string field, or null. Never the word "null". See the file KDoc. */
    private fun JSONObject.str(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    /** A double field, or null when absent — distinct from 0.0. */
    private fun JSONObject.dbl(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { !it.isNaN() }
    }

    /** An int field, or null when absent — distinct from 0. */
    private fun JSONObject.int(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toInt()
    }

    /** A boolean field, or null when absent — distinct from false. */
    private fun JSONObject.bool(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return optBoolean(key)
    }

    /** A nullable-element string array. `[null, null]` stays `[null, null]`. */
    private fun JSONObject.strArray(key: String): List<String?> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { i ->
            if (a.isNull(i)) null else a.optString(i, "").takeIf { it.isNotBlank() }
        }
    }

    private fun JSONObject.boolArray(key: String): List<Boolean?> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { i -> if (a.isNull(i)) null else a.optBoolean(i) }
    }

    private fun JSONObject.dblArray(key: String): List<Double?> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { i ->
            if (a.isNull(i)) null else a.optDouble(i, Double.NaN).takeIf { !it.isNaN() }
        }
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { i ->
            if (isNull(i)) null else optString(i, "").takeIf { it.isNotBlank() }
        }

    /**
     * A road identity, or null.
     *
     * Null-when-the-object-is-absent AND null-when-every-field-is-absent are
     * both preserved as null: `{"highway": null, "name": null}` is not an
     * identity, it is the absence of one, and returning an all-null
     * [WalkRoadId] would let a caller believe a road was established.
     */
    private fun JSONObject.road(key: String): WalkRoadId? {
        if (!has(key) || isNull(key)) return null
        val o = optJSONObject(key) ?: return null
        val id = WalkRoadId(
            highway = o.str("highway"),
            name = o.str("name"),
            nameEn = o.str("name_en"),
        )
        return if (id.highway == null && id.name == null && id.nameEn == null) null else id
    }

    /**
     * Parse one `/foot` Feature.
     *
     * Never throws on a missing field: every quantity is optional and absent
     * reads as null, so an older backend (or a future one that drops an
     * optional block) degrades instead of crashing the walking path. A
     * maneuver whose `kind` this client does not know is SKIPPED and recorded
     * in [WalkContract.unknownManeuverKinds] — guessing at an unknown kind is
     * how a client invents a maneuver the backend never described.
     */
    fun parse(feature: JSONObject): WalkContract {
        val props = feature.optJSONObject("properties") ?: JSONObject()

        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
        val geometry = buildList {
            if (coords != null) for (i in 0 until coords.length()) {
                val c = coords.optJSONArray(i) ?: continue
                if (c.length() < 2) continue
                add(LngLat(c.optDouble(0), c.optDouble(1)))
            }
        }

        val unknownKinds = mutableListOf<String>()
        val plan = mutableListOf<WalkManeuver>()
        val planArr = props.optJSONArray("maneuver_plan")
        if (planArr != null) for (i in 0 until planArr.length()) {
            val o = planArr.optJSONObject(i) ?: continue
            val rawKind = o.str("kind")
            val kind = WalkManeuverKind.from(rawKind)
            if (kind == null) {
                rawKind?.let(unknownKinds::add)
                continue
            }
            plan.add(parseManeuver(o, kind))
        }

        val facts = mutableListOf<WalkFact>()
        val factArr = props.optJSONArray("maneuvers")
        if (factArr != null) for (i in 0 until factArr.length()) {
            val o = factArr.optJSONObject(i) ?: continue
            facts.add(
                WalkFact(
                    type = o.str("type") ?: continue,
                    index = o.int("index") ?: continue,
                    distanceM = o.dbl("distance_m") ?: continue,
                    distanceToNextM = o.dbl("distance_to_next_m"),
                )
            )
        }

        val profileRaw = props.str("walking_profile")
        return WalkContract(
            contractVersion = props.int("contract_version"),
            walkingProfile = WalkingProfile.parse(profileRaw),
            walkingProfileRaw = profileRaw,
            mode = props.str("profile"),
            distanceM = props.dbl("distance_m"),
            durationS = props.dbl("duration_s"),
            stepsM = props.dbl("steps_m"),
            crossingM = props.dbl("crossing_m"),
            nodes = props.int("nodes"),
            geometry = geometry,
            segments = WalkSegments(
                classes = props.strArray("classes"),
                footway = props.strArray("footway"),
                crossing = props.strArray("crossing"),
                lit = props.strArray("lit"),
                enclosed = props.boolArray("enclosed"),
                area = props.boolArray("area"),
                costS = props.dblArray("segment_cost_s"),
            ),
            facts = facts,
            plan = plan,
            cost = parseCost(props.optJSONObject("cost")),
            diagnostics = WalkDiagnostics(
                snapMaxM = props.dbl("snap_max_m"),
                snapWithinPreferred = props.bool("snap_within_preferred"),
                component = props.int("component"),
                componentNodes = props.int("component_nodes"),
                straightM = props.dbl("straight_m"),
                requestedStraightM = props.dbl("requested_straight_m"),
                snapStraightM = props.dbl("snap_straight_m"),
                routeM = props.dbl("route_m"),
                detourRatio = props.dbl("detour_ratio"),
                walkSpeedMs = props.dbl("walk_speed_ms"),
            ),
            unknownManeuverKinds = unknownKinds,
        )
    }

    private fun parseManeuver(o: JSONObject, kind: WalkManeuverKind): WalkManeuver {
        val crossingObj = o.optJSONObject("crossing")
        val stairsObj = o.optJSONObject("stairs")
        val turnObj = o.optJSONObject("turn")
        return WalkManeuver(
            kind = kind,
            index = o.int("index") ?: 0,
            distanceM = o.dbl("distance_m") ?: 0.0,
            distanceToNextM = o.dbl("distance_to_next_m"),
            road = o.road("road"),
            crossing = crossingObj?.let { c ->
                WalkCrossing(
                    type = c.str("type"),
                    typeSource = c.str("type_source"),
                    markings = c.str("markings"),
                    kerb = c.str("kerb"),
                    tactilePaving = c.str("tactile_paving"),
                    distanceM = c.dbl("distance_m"),
                    distanceToCrossingM = c.dbl("distance_to_crossing_m"),
                    approachIndex = c.int("approach_index"),
                    approachDistanceM = c.dbl("approach_distance_m"),
                    enterIndex = c.int("enter_index"),
                    leaveIndex = c.int("leave_index"),
                    // The crossed road and its attribution live on the
                    // MANEUVER, not inside the crossing block (4B.2's shape).
                    // Carried into the crossing here because that is where a
                    // consumer looks for it, and because keeping them together
                    // is what makes `roadKnown` a single check rather than two
                    // fields a caller has to remember to correlate.
                    road = o.road("road"),
                    crossedRoadSource = o.str("crossed_road_source"),
                    // V7 traffic lights: the surveyed signals standing on this
                    // crossing. Absent in a pre-V7 payload, which parses to the
                    // same empty list a signal-free crossing gives — the two
                    // are indistinguishable to a consumer and that is correct,
                    // because both mean "no signal to tell the user about".
                    signals = c.optJSONArray("signals")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val s = arr.optJSONObject(i) ?: return@mapNotNull null
                            val id = s.str("id") ?: return@mapNotNull null
                            val source = s.str("source") ?: return@mapNotNull null
                            WalkSignal(
                                id = id, source = source, node = s.str("node"),
                                index = s.int("index"),
                            )
                        }
                    } ?: emptyList(),
                )
            },
            stairs = stairsObj?.let { s ->
                WalkStairs(
                    distanceM = s.dbl("distance_m"),
                    stepCount = s.int("step_count"),
                    handrail = s.str("handrail"),
                    incline = s.str("incline"),
                )
            },
            turn = turnObj?.let { t ->
                WalkTurn(
                    classification = t.str("classification"),
                    deltaDeg = t.dbl("delta_deg"),
                )
            },
            from = o.road("from"),
            to = o.road("to"),
            sourceFactTypes = o.optJSONArray("source_facts")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.str("type")
                }
            } ?: emptyList(),
        )
    }

    private fun parseCost(o: JSONObject?): WalkCost? {
        if (o == null) return null
        val factors = mutableMapOf<String, Double>()
        o.optJSONObject("factor_s")?.let { f ->
            val keys = f.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                f.dbl(k)?.let { factors[k] = it }
            }
        }
        return WalkCost(
            profile = o.str("profile"),
            baseTimeS = o.dbl("base_time_s"),
            paceS = o.dbl("pace_s"),
            costS = o.dbl("cost_s"),
            penaltyS = o.dbl("penalty_s"),
            selectionFactors = o.optJSONArray("selection_factors")?.strings() ?: emptyList(),
            factorS = factors,
            stairs = o.optJSONObject("stairs")?.let {
                WalkStairsExposure(
                    edges = it.int("edges") ?: 0,
                    distanceM = it.dbl("distance_m") ?: 0.0,
                    stepCount = it.int("step_count") ?: 0,
                    handrailEdges = it.int("handrail_edges") ?: 0,
                )
            },
            incline = o.optJSONObject("incline")?.let {
                WalkInclineExposure(
                    edges = it.int("edges") ?: 0,
                    distanceM = it.dbl("distance_m") ?: 0.0,
                    upliftS = it.dbl("uplift_s") ?: 0.0,
                    maxGradient = it.dbl("max_gradient") ?: 0.0,
                )
            },
            crossing = o.optJSONObject("crossing")?.let {
                WalkCrossingExposure(
                    edges = it.int("edges") ?: 0,
                    distanceM = it.dbl("distance_m") ?: 0.0,
                    waitS = it.dbl("wait_s") ?: 0.0,
                    typedEdges = it.int("typed_edges") ?: 0,
                    untypedEdges = it.int("untyped_edges") ?: 0,
                )
            },
            surface = o.optJSONObject("surface")?.let {
                WalkExposure(it.int("poor_edges") ?: 0, it.dbl("poor_distance_m") ?: 0.0)
            },
            lit = o.optJSONObject("lit")?.let {
                WalkExposure(it.int("unlit_edges") ?: 0, it.dbl("unlit_distance_m") ?: 0.0)
            },
            width = o.optJSONObject("width")?.let {
                WalkExposure(it.int("narrow_edges") ?: 0, it.dbl("narrow_distance_m") ?: 0.0)
            },
            sidewalk = o.optJSONObject("sidewalk")?.let {
                WalkExposure(
                    it.int("no_sidewalk_edges") ?: 0,
                    it.dbl("no_sidewalk_distance_m") ?: 0.0,
                )
            },
        )
    }

    /**
     * Parse the first feature of a `/foot` FeatureCollection, or null.
     *
     * Null for a payload that is not a walk at all — notably the
     * `pedestrian_network_split` 404 body, which is a bare object with an
     * `error`/`reason` and no `features`. A caller must handle that: on
     * Qatar's pedestrian graph (2,811 components, the largest holding 32% of
     * nodes) a split is the COMMON refusal, not an exotic one.
     */
    fun parseCollection(root: JSONObject): WalkContract? {
        val feats = root.optJSONArray("features") ?: return null
        val first = feats.optJSONObject(0) ?: return null
        return parse(first)
    }
}

/**
 * Why a `/foot` request produced no walk (V7.4 4C.1).
 *
 * Carried because the two refusals need different answers from a UI, and the
 * pre-4C.1 client could not tell them apart. [NETWORK_SPLIT] is a statement
 * about the MAP — these two points are on separate pedestrian networks — and
 * on Qatar's graph it is the common case; [NO_ROUTE] is the generic refusal.
 * 4C.1 only classifies. What a user is told is 4C.2's decision.
 */
enum class WalkRefusal {
    NETWORK_SPLIT,
    NO_ROUTE,
    ;

    companion object {
        fun of(root: JSONObject): WalkRefusal? {
            if (root.has("features")) return null
            if (!root.has("error")) return null
            val reason = if (root.isNull("reason")) null else root.optString("reason", "")
            return if (reason == "pedestrian_network_split") NETWORK_SPLIT else NO_ROUTE
        }
    }
}

/**
 * The walking half of the navigation mode split (V7.4 4C.1).
 *
 * Exists so `NavMode` has a home in the app module's vocabulary without the
 * app importing walking state everywhere. The car path never reads it: a
 * session with no walk is [NavMode.CAR] and behaves exactly as it did before
 * 4C.1.
 */
val WalkContract.navMode: NavMode get() = NavMode.FOOT

/**
 * What asking for a walking route produced (V7.4 4C final).
 *
 * Three cases, because there are three genuinely different answers and the
 * brief forbids collapsing them. A sealed type rather than a nullable contract
 * plus an error string: a caller must handle the refusal, and the refusal
 * carries which KIND it was, so "these points are on separate pedestrian
 * networks" cannot be rendered with the same sentence as "Vector could not be
 * reached".
 *
 * **None of these carries a geometry except [Ok].** A refusal has no line to
 * draw, and that is the point — a straight line between two points on separate
 * pedestrian networks would be exactly the claim the backend refused to make.
 */
sealed interface WalkRouteResult {

    /** A real walk, routed under a profile this client understands. */
    data class Ok(val contract: WalkContract) : WalkRouteResult

    /**
     * The backend answered, and the answer was that there is no walk.
     *
     * A statement about the pedestrian network. Retrying will not change it.
     */
    data class Refused(
        val kind: dev.vector.geo.walk.WalkRefusalKind,
    ) : WalkRouteResult

    /**
     * The request never reached an answer.
     *
     * Says nothing about whether a walk exists. [cause] is kept for logging
     * and is never shown to a person — see
     * `MainActivity.friendlyRouteError` for why a stack trace is not an error
     * message.
     */
    data class Failed(
        val kind: dev.vector.geo.walk.WalkRefusalKind,
        val cause: Throwable,
    ) : WalkRouteResult

    /** The refusal kind, whatever the case. Null only for [Ok]. */
    val refusalKind: dev.vector.geo.walk.WalkRefusalKind?
        get() = when (this) {
            is Ok -> null
            is Refused -> kind
            is Failed -> kind
        }
}
