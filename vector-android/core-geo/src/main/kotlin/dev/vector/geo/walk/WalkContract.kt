package dev.vector.geo.walk

import dev.vector.geo.LngLat

/**
 * The V7.4 4B.4 `/foot` contract, as one explicit Android value (V7.4 4C.1).
 *
 * ## What this file is
 *
 * The client's single representation of what the backend said about a walk.
 * Every field below has a documented source in the `/foot` payload, and
 * nothing in this file derives, strengthens or re-phrases one. The backend
 * layer stack it terminates is:
 *
 * ```
 * OSM/source facts -> pedestrian graph -> 4B.1 facts -> 4B.2 plan
 *   -> 4B.3 cost -> 4B.4 public contract -> [this file] -> walking presentation
 * ```
 *
 * ## The rules it exists to hold
 *
 * 1. **Unknown stays unknown.** Every quantity the backend can answer with
 *    `null` is nullable HERE. A crossing whose road the map cannot establish
 *    arrives as [WalkCrossing.road] `null` and stays `null`; it is never
 *    defaulted to a placeholder, an empty string, or the road of a
 *    neighbouring maneuver.
 * 2. **Absent stays absent.** An OSM attribute the mapper never recorded
 *    (`step_count`, `handrail`, `lit`, …) is `null`, which is a different
 *    fact from zero. [WalkStairs.stepCount] `null` means "nobody counted the
 *    steps"; `0` would be a claim.
 * 3. **No prose.** There is not one human-facing sentence in this file, and
 *    there must not be. "Turn left onto X", "Cross Y", "Use the stairs" are
 *    presentation decisions that belong to a later stage; 4C.1 carries the
 *    normalized FACTS those decisions will be made from. A UI can already
 *    read [WalkManeuver.kind] and [WalkManeuver.road] — it just may not find
 *    a pre-written sentence here, because writing one is how a phrase the
 *    graph cannot source gets shipped.
 * 4. **Provenance rides along.** [WalkCrossing.typeSource] and
 *    [WalkCrossing.crossedRoadSource] are preserved verbatim. A crossing
 *    typed by a nearby catalog node is a weaker statement than one typed by
 *    the way itself, and a consumer that cannot tell them apart will
 *    eventually present the weak one as strong.
 * 5. **Meanings are frozen.** [durationS] is pure pace time. [WalkCost.costS]
 *    is route-selection cost. [WalkCost.crossing] `waitS` is expected crossing
 *    delay and is NOT in [durationS]. See [WalkEta], which exists so a caller
 *    has to name which of the three it wants.
 */
data class WalkContract(
    /**
     * `contract_version` — a marker of the response SHAPE (4B.4 D3).
     *
     * Informational. Nothing branches on it yet, and the contract is designed
     * additively so that nothing ever has to. Carried because the day a
     * client DOES need to branch, the alternative is inferring the shape from
     * example responses.
     */
    val contractVersion: Int?,

    /**
     * `walking_profile`, parsed. Null means the backend named a profile this
     * client does not know — see [walkingProfileRaw] for what it actually
     * said, and [WalkingProfile.parse] for why that is not an exception.
     */
    val walkingProfile: WalkingProfile?,

    /** `walking_profile`, verbatim. Kept so an unknown value is never lost. */
    val walkingProfileRaw: String?,

    /** `profile` — the MODE, always `"foot"`. Distinct from [walkingProfile]. */
    val mode: String?,

    /** `distance_m`. */
    val distanceM: Double?,

    /**
     * `duration_s` — **pure walking-pace time**, and nothing else.
     *
     * Equal to `cost.pace_s` by construction (4B.4 D1). This is the value the
     * existing client already shows as "N min walk" and already uses to pick
     * the best of several candidate parking walks, and its meaning is frozen:
     * it EXCLUDES expected crossing delay. Whether that delay is ever
     * surfaced is a later presentation decision, and it must be made from
     * [WalkCost.crossing] rather than by quietly changing this number.
     */
    val durationS: Double?,

    /** `steps_m` — metres of `highway=steps` on the walk. */
    val stepsM: Double?,

    /** `crossing_m` — metres walked ON crossings (4A.4). */
    val crossingM: Double?,

    /** `nodes` — geometry vertex count. Segment arrays are `nodes - 1` long. */
    val nodes: Int?,

    /** The walk polyline, in GeoJSON (lng, lat) order. */
    val geometry: List<LngLat>,

    /** Per-segment arrays, each aligned one-per-geometry-pair. */
    val segments: WalkSegments,

    /** `maneuvers` — the 4B.1 fact stream. See [WalkFact]. */
    val facts: List<WalkFact>,

    /** `maneuver_plan` — the 4B.2 interpretation. The UI-facing event list. */
    val plan: List<WalkManeuver>,

    /** `cost` — the 4B.3 decomposition. Null on a backend that predates it. */
    val cost: WalkCost?,

    /** Snap / component diagnostics (4A.1, 4A.2). */
    val diagnostics: WalkDiagnostics,

    /**
     * Maneuver kinds the backend sent that this client does not know.
     *
     * Empty on every 4B.4 payload. Non-empty means the backend added a kind
     * additively and this client skipped it rather than guessing — the
     * maneuver is absent from [plan] and its count is here so the omission is
     * visible instead of silent.
     */
    val unknownManeuverKinds: List<String> = emptyList(),
) {
    /** The three different "how long is this walk" numbers, named. */
    val eta: WalkEta
        get() = WalkEta(
            displayDurationS = durationS,
            selectionCostS = cost?.costS,
            penaltyS = cost?.penaltyS,
            crossingWaitS = cost?.crossing?.waitS,
        )

    /**
     * True when the backend routed under a profile this client understands.
     *
     * The client boundary's check. A payload that fails it is not rendered as
     * a walk — not because the geometry is wrong, but because this client
     * cannot say what the route was optimised for, and a walk presented under
     * an unknown profile is a claim nobody can support.
     */
    val profileSupported: Boolean get() = walkingProfile != null
}

/**
 * The pedestrian cost profile a walk was routed under.
 *
 * ## Why this is an enum with exactly one entry
 *
 * `general` is the only profile the backend wires (4B.4 D2), and an unknown
 * name there is a loud HTTP 400 rather than a silent general walk. This
 * mirrors that refusal on the client: there is no comfort mode, no
 * accessibility mode, no pleasant-walking mode, and no profile picker.
 * Vector makes **no accessibility claim** — the underlying data cannot
 * support one (398 incline edges and 14 handrail edges on the whole Qatar
 * bake), and an enum entry is exactly how a claim like that gets made by
 * accident.
 *
 * A future profile is a new entry here plus its backend wiring, not a string
 * that happens to arrive.
 */
enum class WalkingProfile(val wire: String) {
    GENERAL("general");

    companion object {
        /**
         * Parse a `walking_profile` value, or null if this client does not
         * know it.
         *
         * Null rather than an exception, deliberately. A backend that adds a
         * profile is making an ADDITIVE change and must not crash an old
         * client; the honest response is to refuse to present the walk (see
         * [WalkContract.profileSupported]) while keeping the raw value for
         * diagnosis. Throwing here would turn an additive backend change into
         * a client crash, which is the one outcome worse than refusing.
         */
        fun parse(raw: String?): WalkingProfile? {
            val v = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == v }
        }
    }
}

/**
 * Which mode the app is navigating in (V7.4 4C.1).
 *
 * Introduced here because walking is the first mode that is genuinely not
 * driving: it has its own graph, its own cost model, its own maneuver
 * vocabulary and — from 4C.2 — its own camera and puck behaviour. Modelling
 * the distinction explicitly is what keeps those from being expressed as
 * conditionals scattered through the car path.
 *
 * Exactly two entries, and there will not be more from walking: `general` is
 * the only routed walking profile, so "foot" is one mode and not a family.
 */
enum class NavMode {
    /** Driving. Every pre-4C.1 behaviour, unchanged. */
    CAR,

    /** Walking, under [WalkingProfile.GENERAL]. */
    FOOT,
}

/**
 * The three walk durations, each named, so a caller must choose (4B.4 D1).
 *
 * They are different numbers answering different questions, and the whole
 * point of this type is that no code can reach for "the duration" and get
 * whichever one happened to be in scope:
 *
 *  - [displayDurationS] is what a person is shown. Pure pace time.
 *  - [selectionCostS] is what the router MINIMISED. It includes penalties and
 *    is not a duration anyone experiences.
 *  - [crossingWaitS] is expected crossing delay, already inside
 *    [selectionCostS] and deliberately NOT inside [displayDurationS].
 *
 * 4C.1 does not decide whether crossing delay is ever surfaced. It only makes
 * both numbers available so that decision can be made explicitly.
 */
data class WalkEta(
    val displayDurationS: Double?,
    val selectionCostS: Double?,
    val penaltyS: Double?,
    val crossingWaitS: Double?,
)

/**
 * The per-segment arrays, one entry per pair of geometry vertices.
 *
 * Nullable entries throughout: a segment with no `footway` tag has `null`,
 * not `""`. Lists may be empty when the backend omitted an array, which is
 * how an older backend degrades — the same way the existing `WalkLeg` parser
 * already degrades on a missing `classes`.
 */
data class WalkSegments(
    val classes: List<String?> = emptyList(),
    val footway: List<String?> = emptyList(),
    val crossing: List<String?> = emptyList(),
    val lit: List<String?> = emptyList(),
    val enclosed: List<Boolean?> = emptyList(),
    val area: List<Boolean?> = emptyList(),
    /** `segment_cost_s` — per-segment WEIGHTED cost, not pace time. */
    val costS: List<Double?> = emptyList(),
) {
    /** True when every present array has [expected] entries. */
    fun alignedTo(expected: Int): Boolean =
        listOf(classes, footway, crossing, lit, enclosed, area, costS)
            .all { it.isEmpty() || it.size == expected }
}

/**
 * One 4B.1 fact, at its position on the walk.
 *
 * ## What is deliberately not modelled
 *
 * The full per-fact tag payload (bearings, the raw `source` map, the
 * crossing's own copies of its attributes). 4C.1 preserves fact IDENTITY and
 * POSITION so the plan's provenance can be followed back to the stream it
 * came from; it does not re-model the whole fact layer, because no 4C.1
 * consumer reads it and modelling it "in case" is how a client acquires a
 * second, drifting copy of a backend contract. It remains one parse away.
 */
data class WalkFact(
    val type: String,
    val index: Int,
    val distanceM: Double,
    val distanceToNextM: Double?,
)

/** The 4B.2 normalized maneuver vocabulary. Closed; see [from]. */
enum class WalkManeuverKind(val wire: String) {
    DEPART("depart"),
    CROSS("cross"),
    STAIRS("stairs"),
    TURN_LEFT("turn_left"),
    TURN_RIGHT("turn_right"),
    SLIGHT_LEFT("slight_left"),
    SLIGHT_RIGHT("slight_right"),
    UTURN("uturn"),
    CONTINUE("continue"),
    ARRIVE("arrive"),
    ;

    /** A direction change the walker performs. */
    val isTurn: Boolean
        get() = this == TURN_LEFT || this == TURN_RIGHT ||
            this == SLIGHT_LEFT || this == SLIGHT_RIGHT || this == UTURN

    /**
     * An event that occupies a STRETCH of the walk rather than a point.
     *
     * Crossings and stairs are the two, and they are why walking needs a span
     * model that driving never did: a driver's turn happens at a junction,
     * but a person is ON a 49 m crossing for most of a minute.
     */
    val isSpan: Boolean get() = this == CROSS || this == STAIRS

    companion object {
        /** Parse a `kind`, or null for a value this client does not know. */
        fun from(wire: String?): WalkManeuverKind? =
            entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A road or path identity, exactly as the backend gave it.
 *
 * Every field is nullable and every null is meaningful: a crossing over an
 * unnamed slip road arrives as `highway="primary_link", name=null` and must
 * stay that way. A UI that wants to name this road has to handle not knowing
 * it, which is the point.
 */
data class WalkRoadId(
    val highway: String? = null,
    val name: String? = null,
    val nameEn: String? = null,
) {
    /** True when the backend supplied a usable name in either language. */
    val named: Boolean get() = !name.isNullOrBlank() || !nameEn.isNullOrBlank()
}

/**
 * The crossing facts of a `cross` maneuver (4B.1 -> 4B.2).
 *
 * [road] is the road BEING CROSSED, not the path being walked on — the single
 * most dangerous field in this file to misread, and the reason
 * [dev.vector.geo.walk.WalkProgress.currentRoad] excludes cross maneuvers
 * when it answers "what am I walking on".
 */
data class WalkCrossing(
    /** `crossing.type` — marked / traffic_signals / zebra / …, or null. */
    val type: String?,
    /**
     * `crossing.type_source` — the four-state provenance (4B.1):
     * `way` (the crossing way's own tag), `catalog` (a nearby typed node),
     * `catalog_node` (a node exists but carries no type — an untyped crossing
     * is still a crossing), or null (no artifact nearby).
     */
    val typeSource: String?,
    val markings: String?,
    val kerb: String?,
    val tactilePaving: String?,
    /** Length of the crossing itself. */
    val distanceM: Double?,
    /** `distance_to_crossing_m` — approach point to the crossing. */
    val distanceToCrossingM: Double?,
    val approachIndex: Int?,
    val approachDistanceM: Double?,
    val enterIndex: Int?,
    val leaveIndex: Int?,
    /** The crossed road, or null when the map cannot establish one. */
    val road: WalkRoadId?,
    /** `crossed_road_source` — `road_graph_shared_node`, or null. */
    val crossedRoadSource: String?,
    /**
     * `crossing.signals` — the surveyed signals standing ON this crossing
     * (V7 traffic lights). Empty is the normal case and is NOT a claim that the
     * crossing is uncontrolled: it says the map places no signal on it.
     */
    val signals: List<WalkSignal> = emptyList(),
) {
    /** True only when the crossed road is both present and attributed. */
    val roadKnown: Boolean get() = road != null && crossedRoadSource != null

    /**
     * True when a SURVEYED signal stands on this crossing.
     *
     * The only condition under which anything may describe the crossing as
     * signal-controlled from the signal side. Never inferred from
     * [type] (`crossing=traffic_signals` is the crossing's own tag, a different
     * fact from a signal node on it), from the crossing's name, or from
     * proximity — the backend attaches a signal only by graph node identity.
     */
    val signalControlled: Boolean get() = signals.isNotEmpty()
}

/**
 * A surveyed traffic signal standing on a crossing (V7 traffic lights).
 *
 * ## What this carries, and what it deliberately cannot
 *
 * [id] and [source] are a LOCATION and its provenance, and that is the whole of
 * it — the same discipline [dev.vector.geo.signal.SignalRef] applies to the
 * driving catalog. Read the fields and the absence is the point: there is no
 * phase, no colour, no cycle, no countdown and no estimated wait, because the
 * source has no timing anywhere. Measured over the whole 2026-09 Qatar extract,
 * not one of the 899 signal nodes carries a timing-shaped tag.
 *
 * A signal being present says *there are lights here*. It says nothing about
 * what they are showing, and a type with no way to express "green" is how that
 * stays true through every future refactor rather than by everyone remembering.
 *
 * ## Why this is not [dev.vector.geo.signal.SignalRef]
 *
 * It is narrower on purpose. `SignalRef` is a routing concept: it belongs to a
 * route, an approach and a profile built once per drive. This is a survey fact
 * about a crossing, and it has no route, no approach and no profile because a
 * walker about to cross needs none of them. Sharing one type would mean this
 * case carrying fields that are meaningless on foot, and inviting the walking
 * side to start reading a route-relative bearing it does not have.
 */
data class WalkSignal(
    /** The catalog entry's stable OSM identity, e.g. `n1001`. */
    val id: String,
    /** `osm:node:<id>` — never blank; a placement without provenance is a fabrication. */
    val source: String,
    /** The graph node the signal stands on. The association's own evidence. */
    val node: String?,
    /**
     * The signal's own vertex on the walk geometry, or null.
     *
     * The backend's index, in the same index space as `enter_index`/
     * `leave_index`, so a map can place a marker at the surveyed position
     * without projecting, matching or rounding anything. Null when a payload
     * omits it — an older backend — and a map must then decline to place it
     * rather than guess.
     */
    val index: Int? = null,
)

/**
 * The stairs facts of a `stairs` maneuver.
 *
 * [stepCount], [handrail] and [incline] are null on almost every real Qatari
 * staircase — 6 and 14 edges respectively on the entire bake carry them — and
 * that absence is preserved rather than defaulted. None of these is evidence
 * of accessibility, and nothing may present them as such.
 */
data class WalkStairs(
    val distanceM: Double?,
    val stepCount: Int? = null,
    val handrail: String? = null,
    val incline: String? = null,
)

/** The turn facts of a turn/slight/uturn maneuver. */
data class WalkTurn(
    /** 4B.1's raw classification, e.g. `turn-left`. */
    val classification: String?,
    val deltaDeg: Double?,
)

/**
 * One planning maneuver from `maneuver_plan` (4B.2).
 *
 * [index] and [distanceM] are the backend's own and are authoritative: index
 * is a geometry vertex, distanceM the walked metres there. Measured across
 * the six committed real-Qatar fixtures (182 maneuvers), `cum[index]` and
 * `distanceM` agree to within **0.07 m**, so the client can use distanceM
 * directly as an along-route position without re-deriving anything.
 */
data class WalkManeuver(
    val kind: WalkManeuverKind,
    val index: Int,
    val distanceM: Double,
    val distanceToNextM: Double?,
    /**
     * The road most relevant to this maneuver, per 4B.2: the CROSSED road for
     * a cross, the way travelled on after a turn/continue, the on-way for
     * depart/arrive. Null is preserved and is common on stairs.
     */
    val road: WalkRoadId?,
    val crossing: WalkCrossing? = null,
    val stairs: WalkStairs? = null,
    val turn: WalkTurn? = null,
    /** `from`/`to` of a `continue` — the walkway/roadway boundary crossed. */
    val from: WalkRoadId? = null,
    val to: WalkRoadId? = null,
    /** The 4B.1 fact TYPES this maneuver was interpreted from. */
    val sourceFactTypes: List<String> = emptyList(),
) {
    /**
     * How long the event occupies the walk, in metres; 0 for a point event.
     *
     * Read from the backend's own span length ([WalkCrossing.distanceM] /
     * [WalkStairs.distanceM]) rather than measured off the geometry between
     * `enter_index` and `leave_index`. The two agree to within 0.07 m on the
     * real fixtures; using the backend's keeps the span a SOURCED fact and
     * keeps it available on a degenerate geometry.
     */
    val spanM: Double
        get() = when (kind) {
            WalkManeuverKind.CROSS -> crossing?.distanceM ?: 0.0
            WalkManeuverKind.STAIRS -> stairs?.distanceM ?: 0.0
            else -> 0.0
        }.coerceAtLeast(0.0)
}

/** The 4B.3 cost decomposition. Never a score: every part is named seconds. */
data class WalkCost(
    val profile: String?,
    /** Flat-pace time: the route's length in seconds. */
    val baseTimeS: Double?,
    /** Reported pace time. Equals [WalkContract.durationS] by construction. */
    val paceS: Double?,
    /** The weighted cost the search minimised. NOT a duration. */
    val costS: Double?,
    /** `cost_s - pace_s`. */
    val penaltyS: Double?,
    /** Factors that CAN affect selection under this profile. */
    val selectionFactors: List<String> = emptyList(),
    /** Per-factor seconds. Sparse: only non-zero factors appear. */
    val factorS: Map<String, Double> = emptyMap(),
    val stairs: WalkStairsExposure? = null,
    val incline: WalkInclineExposure? = null,
    val crossing: WalkCrossingExposure? = null,
    val surface: WalkExposure? = null,
    val lit: WalkExposure? = null,
    val width: WalkExposure? = null,
    val sidewalk: WalkExposure? = null,
)

/** Exposure measured regardless of whether it affected selection. */
data class WalkExposure(val edges: Int, val distanceM: Double)

data class WalkStairsExposure(
    val edges: Int,
    val distanceM: Double,
    val stepCount: Int,
    val handrailEdges: Int,
)

data class WalkInclineExposure(
    val edges: Int,
    val distanceM: Double,
    val upliftS: Double,
    val maxGradient: Double,
)

data class WalkCrossingExposure(
    val edges: Int,
    val distanceM: Double,
    /** Expected crossing delay. Deliberately NOT in [WalkContract.durationS]. */
    val waitS: Double,
    val typedEdges: Int,
    val untypedEdges: Int,
)

/**
 * Snap and connectivity diagnostics (4A.1/4A.2).
 *
 * These are what separate "this walk is genuinely long" from "this walk
 * crossed a gap it should have refused". Carried, never acted on: a high
 * [detourRatio] is frequently correct.
 */
data class WalkDiagnostics(
    val snapMaxM: Double? = null,
    val snapWithinPreferred: Boolean? = null,
    val component: Int? = null,
    val componentNodes: Int? = null,
    val straightM: Double? = null,
    val requestedStraightM: Double? = null,
    val snapStraightM: Double? = null,
    val routeM: Double? = null,
    val detourRatio: Double? = null,
    val walkSpeedMs: Double? = null,
)
