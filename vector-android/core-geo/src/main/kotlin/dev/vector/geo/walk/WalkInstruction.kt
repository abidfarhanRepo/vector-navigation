package dev.vector.geo.walk

import dev.vector.geo.Units

/**
 * What a walker is told, derived from the normalized 4B.2 plan (V7.4 4C final).
 *
 * ## What this file is
 *
 * The ONE place a walking maneuver becomes words. The banner reads it and the
 * voice reads it, so the two cannot diverge — which is the failure this type
 * exists to make structurally impossible, not merely unlikely. A screen saying
 * "Cross Al Rayyan Road" while a voice says "cross the road" is two features
 * disagreeing about the same fact, and the only reliable cure is that there is
 * one fact.
 *
 * The layer stack it terminates:
 *
 * ```
 * OSM facts -> pedestrian graph -> 4B.1 facts -> 4B.2 plan -> 4B.3 cost
 *   -> 4B.4 /foot -> 4C.1 state -> 4C.2 follower -> [this file] -> banner + voice
 * ```
 *
 * ## The rule every line here follows
 *
 * **Never turn an uncertain backend fact into a confident user instruction.**
 *
 * Concretely, and each of these is pinned by a test:
 *
 *  1. **A road is named only when the backend named it.** [roadName] is null
 *     whenever the contract's road identity is absent or unnamed, and the
 *     rendered text then carries no "onto X" clause at all. There is no
 *     fallback to a highway class, no backfill from a neighbouring maneuver,
 *     and no placeholder.
 *  2. **A crossing's road is the road being CROSSED**, and it is used only
 *     when [WalkCrossing.roadKnown] — i.e. the road is present AND attributed.
 *     An unattributed crossing says "Cross the road", which is true of every
 *     crossing, rather than naming one Vector cannot establish.
 *  3. **Absent attributes produce no words.** A staircase with no `handrail`
 *     tag produces no handrail phrase; it does not produce "no handrail",
 *     because the contract records absence of a survey rather than absence of
 *     a handrail. The same for `step_count`, `incline`, `kerb`,
 *     `tactile_paving` and the crossing type.
 *  4. **No provenance reaches a user.** `type_source`, `crossed_road_source`
 *     and `source_facts` decide WHETHER something may be said; none of them is
 *     ever said. "catalog_node" is a fact about Vector's map, not about the
 *     road in front of a person.
 *  5. **No accessibility, safety, priority or quality claim is made anywhere.**
 *     There is no vocabulary in this file for "safe", "accessible", "step-free"
 *     or "pedestrian priority", because the data cannot support one — 14
 *     handrail edges and 398 incline edges on the entire Qatar bake — and a
 *     phrase that does not exist cannot be shipped by accident.
 *
 * ## Facts and rendering are separate fields, deliberately
 *
 * [kind], [roadName], [roadKnown], [crossing] and [stairs] are FACTS; [action],
 * [banner] and [detail] are RENDERING. A caller that wants to make a different
 * presentation decision reads the facts; a caller that wants the product's
 * wording reads the strings. Mixing them is how a UI ends up parsing its own
 * sentences back into facts, which is exactly what
 * [dev.vector.geo.ManeuverAnnouncer.shortAction] documents as the mistake to
 * avoid on the car side.
 */
data class WalkInstruction(
    /** The normalized 4B.2 maneuver kind. Authoritative; never re-derived. */
    val kind: WalkManeuverKind,

    /** Position in [WalkRoute.plan]. The identity the voice dedups on. */
    val planIndex: Int,

    /** Ahead, under way, or behind. */
    val status: WalkEventStatus,

    /** Metres from the walker to the event's start. */
    val distanceM: Double,

    /** Metres the event occupies; 0 for a point maneuver. */
    val spanM: Double,

    /**
     * The road this instruction may name, or null.
     *
     * Null is the common case on Qatari walks and is rendered as the absence
     * of a clause, never as a placeholder. For a crossing this is the CROSSED
     * road and is populated only when the backend attributed it.
     */
    val roadName: String?,

    /** True when [roadName] is non-null. Kept explicit so a caller must handle it. */
    val roadKnown: Boolean,

    /** Crossing attributes that are actually known. Null for a non-crossing. */
    val crossing: WalkCrossingDetail? = null,

    /** Stair attributes that are actually known. Null for a non-staircase. */
    val stairs: WalkStairsDetail? = null,

    /**
     * The bare action, with no road and no distance. "Turn left".
     *
     * The shared root of the banner and the spoken form, which is what keeps
     * them from drifting: both are built from this string rather than from two
     * parallel vocabularies.
     */
    val action: String,

    /** The action plus the road when one is known. "Turn left onto X". */
    val banner: String,

    /**
     * A secondary line built ONLY from known attributes, or null.
     *
     * Null whenever nothing is known, which on the real Qatar bake is most
     * crossings and almost every staircase. A UI must render its absence as
     * absence.
     */
    val detail: String? = null,
) {
    /**
     * The spoken form for one announcement stage.
     *
     * Derived from [action] rather than from [banner] so a spoken sentence and
     * a printed one cannot describe different maneuvers. The road is included
     * when known, for the same reason the banner includes it: a walker at a
     * junction with two left turns needs it, and Vector either knows it or
     * says nothing.
     *
     * The distance prefix is spoken only at [WalkVoiceStage.APPROACH]. At
     * [WalkVoiceStage.NOW] the maneuver is where the walker is standing, and
     * "in ten metres, turn left" said at the corner is worse than "turn left".
     */
    fun spoken(stage: WalkVoiceStage, units: Units = Units.METRIC): String = when (stage) {
        WalkVoiceStage.APPROACH ->
            "In ${units.spokenDistance(distanceM.coerceAtLeast(0.0))}, ${decapitalise(banner)}"
        WalkVoiceStage.NOW -> banner
    }

    private fun decapitalise(s: String): String =
        if (s.isNotEmpty() && s[0].isUpperCase()) s[0].lowercaseChar() + s.substring(1) else s
}

/**
 * Crossing attributes a person can be told, and only those.
 *
 * Every field is null unless the contract supplied the underlying fact. The
 * type carries its own guard: 4B.1's `type_source` has a `catalog_node` state
 * meaning "a crossing node exists here but nobody recorded what kind", and in
 * that state [typeLabel] is null — an untyped crossing is still a crossing,
 * and saying only "Cross the road" is the whole of what is known.
 */
data class WalkCrossingDetail(
    /** "Zebra crossing", "Signal-controlled crossing", ... or null. */
    val typeLabel: String? = null,
    /** "Lowered kerb" / "Flush kerb" / "Raised kerb", or null. */
    val kerbLabel: String? = null,
    /**
     * True only when the mapper recorded `tactile_paving=yes`.
     *
     * Null covers both "no survey" and "surveyed as absent", and they are
     * rendered identically — as silence. Vector says tactile paving is THERE
     * or says nothing; it never tells a person one is missing, because on 20
     * surveyed nodes country-wide the absence of a tag is not evidence.
     */
    val tactilePaving: Boolean? = null,
    /** "Marked" when the mapper recorded markings, else null. */
    val markingsLabel: String? = null,
)

/**
 * Stair attributes a person can be told, and only those.
 *
 * On the real Qatar bake every one of these is absent on almost every
 * staircase — 3 `step_count` and 8 `handrail` ways in the whole extract — so
 * the overwhelmingly common rendering is "Use the stairs" and nothing else.
 * That is the correct output, not a degraded one.
 */
data class WalkStairsDetail(
    /** "12 steps", or null when nobody counted them. */
    val stepCountLabel: String? = null,
    /** True only when `handrail=yes`. Never rendered as a negative. */
    val handrail: Boolean? = null,
    /** "Up" / "Down", or null. */
    val inclineLabel: String? = null,
)

/**
 * Which announcement a spoken instruction belongs to.
 *
 * Two stages rather than the car's three. [dev.vector.geo.ManeuverAnnouncer]
 * has PREPARE/TURN/NOW because a driver needs warning measured in hundreds of
 * metres; its own PREPARE floor is 250 m, which at 1.35 m/s is over three
 * minutes and further than the median gap between two walking maneuvers
 * (139.7 m on the real fixtures). A third stage on foot would fire before the
 * previous maneuver was finished.
 */
enum class WalkVoiceStage {
    /** Close enough to act on, far enough to hear it first. See [WalkVoice]. */
    APPROACH,

    /** At the maneuver. */
    NOW,
}

/**
 * Turns normalized maneuvers into instructions (V7.4 4C final).
 *
 * Pure and total: every member of the closed 4B.2 vocabulary maps to exactly
 * one action phrase, and a test walks the enum to prove none is missing. There
 * is no `else ->` fallback producing a generic sentence, because a silent
 * fallback is how an unmapped kind ships as "Continue".
 */
object WalkInstructions {

    /**
     * The one phrasing for a crossing with lights (V7 traffic lights).
     *
     * A constant rather than two literals because two sources can establish it
     * — the crossing's own `crossing=traffic_signals` tag, or a surveyed signal
     * standing as a vertex of the crossing way — and they are the same fact to
     * a person about to cross. It states that lights EXIST. There is no string
     * in this file naming a colour, a phase or a number of seconds, and the
     * vocabulary guard test walks the object to say so.
     */
    const val SIGNAL_CONTROLLED = "Signal-controlled crossing"

    /** The instruction for one placed event, or null when there is none. */
    fun of(event: WalkEvent?): WalkInstruction? {
        if (event == null) return null
        return of(event.maneuver, event.planIndex, event.status, event.distanceM)
    }

    /** The instruction for one maneuver at a known position. */
    fun of(
        m: WalkManeuver,
        planIndex: Int,
        status: WalkEventStatus,
        distanceM: Double,
    ): WalkInstruction {
        val road = roadNameFor(m)
        val action = actionFor(m.kind)
        val crossing = m.crossing?.let { detailFor(it) }
        val stairs = m.stairs?.let { detailFor(it) }
        return WalkInstruction(
            kind = m.kind,
            planIndex = planIndex,
            status = status,
            distanceM = distanceM,
            spanM = m.spanM,
            roadName = road,
            roadKnown = road != null,
            crossing = crossing,
            stairs = stairs,
            action = action,
            banner = bannerFor(m.kind, action, road),
            detail = detailLine(crossing, stairs),
        )
    }

    /**
     * The drawn icon for a maneuver kind.
     *
     * Returns a CAR maneuver-type string, because the app's icon set is
     * indexed by those and drawing a second set of arrows for walking would be
     * two drawings of the same shape that could drift apart. This is the same
     * translation [dev.vector.geo.LaneGuidance.arrowType] performs for lane
     * indications, in the module that owns the walking vocabulary rather than
     * in the renderer.
     *
     * `cross` and `stairs` have no arrow of their own and fall to `depart`'s
     * straight-ahead glyph, which is honest: the walker continues forward,
     * onto a crossing or onto a staircase, and the words carry which. Inventing
     * a zebra or a staircase pictogram would be a new icon asserting a fact the
     * detail line already states only when it is known.
     */
    fun iconType(kind: WalkManeuverKind): String = when (kind) {
        WalkManeuverKind.TURN_LEFT -> "turn-left"
        WalkManeuverKind.TURN_RIGHT -> "turn-right"
        WalkManeuverKind.SLIGHT_LEFT -> "slight-left"
        WalkManeuverKind.SLIGHT_RIGHT -> "slight-right"
        WalkManeuverKind.UTURN -> "uturn"
        WalkManeuverKind.ARRIVE -> "arrive"
        WalkManeuverKind.DEPART, WalkManeuverKind.CONTINUE,
        WalkManeuverKind.CROSS, WalkManeuverKind.STAIRS,
        -> "depart"
    }

    /**
     * The bare action phrase for a maneuver kind.
     *
     * Deliberately short and spatial. The car path's phrasing is the backend's
     * localised sentence; walking has no such sentence — 4B.2 publishes facts
     * and explicitly no prose — so the vocabulary is defined here, once.
     *
     * `uturn` is "U-turn" rather than "Turn around" because it is the term the
     * contract's own vocabulary uses and the one a map reader recognises; TTS
     * engines read it correctly in both scripts Vector speaks.
     */
    fun actionFor(kind: WalkManeuverKind): String = when (kind) {
        WalkManeuverKind.DEPART -> "Start walking"
        WalkManeuverKind.CROSS -> "Cross the road"
        WalkManeuverKind.STAIRS -> "Use the stairs"
        WalkManeuverKind.TURN_LEFT -> "Turn left"
        WalkManeuverKind.TURN_RIGHT -> "Turn right"
        WalkManeuverKind.SLIGHT_LEFT -> "Bear left"
        WalkManeuverKind.SLIGHT_RIGHT -> "Bear right"
        WalkManeuverKind.UTURN -> "U-turn"
        WalkManeuverKind.CONTINUE -> "Continue"
        WalkManeuverKind.ARRIVE -> "Arriving"
    }

    /**
     * The road a maneuver may name, or null.
     *
     * ## The two refusals that make this function worth having
     *
     *  1. **A crossing names the road it CROSSES, and only when attributed.**
     *     [WalkCrossing.roadKnown] requires both the identity and its
     *     `crossed_road_source`. Without the attribution the road is a guess
     *     from geometry, and "Cross Al Waab Street" is a claim about which
     *     road a person is standing at — the most dangerous sentence available
     *     in this contract to get wrong.
     *  2. **An unnamed way is not a road identity.** `{highway: "footway",
     *     name: null}` is extremely common on Qatari walks and carries no name
     *     to say. [WalkRoadId.named] is the test, and a highway class is never
     *     substituted for a name: "Turn left onto footway" is not something a
     *     person can act on.
     *
     * `arrive` and `stairs` never name a road even when one is present. The
     * arrive maneuver's road is the street the destination sits on, which is
     * not what someone at the door is looking for; the destination's own name
     * is the app's to supply and is not a backend fact.
     */
    fun roadNameFor(m: WalkManeuver): String? = when (m.kind) {
        WalkManeuverKind.CROSS -> {
            val c = m.crossing
            if (c != null && c.roadKnown) nameOf(c.road) else null
        }
        WalkManeuverKind.ARRIVE, WalkManeuverKind.STAIRS -> null
        else -> nameOf(m.road)
    }

    /**
     * A road's display name, preferring English.
     *
     * The same preference the rest of the product already applies: the client
     * asks the router for English and `VectorStyle.nameExpr` renders `name:en`
     * with a per-feature fallback, so a banner naming a road in one language
     * over a map labelling it in another is the inconsistency this avoids.
     * Blank is treated as absent — see `WalkContractParser`, where `org.json`
     * turns a JSON null into the four-character string "null" if not guarded.
     */
    private fun nameOf(road: WalkRoadId?): String? {
        if (road == null) return null
        val en = road.nameEn?.takeIf { it.isNotBlank() }
        if (en != null) return en
        return road.name?.takeIf { it.isNotBlank() }
    }

    /**
     * The action with its road clause, when there is a road to name.
     *
     * The preposition is chosen per kind because English needs it to be: you
     * turn ONTO a street, continue ON one, and cross one with no preposition
     * at all. A single template would produce "Cross the road onto Al Waab
     * Street", which says something false about what the walker is doing.
     */
    private fun bannerFor(
        kind: WalkManeuverKind,
        action: String,
        road: String?,
    ): String {
        if (road == null) return action
        return when (kind) {
            // "Cross the road" becomes "Cross X" — the generic noun is
            // REPLACED rather than appended, so the sentence names one road
            // instead of implying two.
            WalkManeuverKind.CROSS -> "Cross $road"
            WalkManeuverKind.CONTINUE -> "$action on $road"
            WalkManeuverKind.DEPART -> "$action on $road"
            WalkManeuverKind.TURN_LEFT, WalkManeuverKind.TURN_RIGHT,
            WalkManeuverKind.SLIGHT_LEFT, WalkManeuverKind.SLIGHT_RIGHT,
            WalkManeuverKind.UTURN -> "$action onto $road"
            // Unreachable: `roadNameFor` returns null for both. Stated rather
            // than left to an else, so adding a kind is a compile error here.
            WalkManeuverKind.STAIRS, WalkManeuverKind.ARRIVE -> action
        }
    }

    /**
     * Crossing attributes, filtered to what is actually known.
     *
     * The type vocabulary is closed and an unrecognised value maps to null
     * rather than being echoed: OSM's `crossing` key has a long tail, and
     * passing an unknown token through to a banner is how "Cross the road /
     * traffic_signals;marked" reaches a person.
     */
    fun detailFor(c: WalkCrossing): WalkCrossingDetail = WalkCrossingDetail(
        typeLabel = when (c.type?.trim()?.lowercase()) {
            // A signal claim is permitted here and ONLY here: it is the
            // backend's own `crossing=traffic_signals`, which is a surveyed
            // fact about the crossing. Nothing in Vector claims anything about
            // the signal's STATE or timing — that is a separate workstream
            // with no data behind it.
            "traffic_signals" -> SIGNAL_CONTROLLED
            "zebra" -> "Zebra crossing"
            "marked" -> "Marked crossing"
            "unmarked" -> "Unmarked crossing"
            "uncontrolled" -> "Uncontrolled crossing"
            // V7 traffic lights: no type tag, but a SURVEYED SIGNAL stands on
            // this crossing — the backend attaches one only when the signal's
            // own coordinate is a vertex of the crossing way. That is a second,
            // independent piece of evidence for the same thing the tag says,
            // and it is the reason 522 of Qatar's signal-carrying crossings can
            // be described at all (they carry no `crossing=*` tag).
            //
            // The same words, deliberately: "Signal-controlled crossing" is one
            // fact with two possible sources, and giving the signal-node route
            // its own phrasing would invent a distinction a walker cannot act
            // on. Still no state, no colour and no timing — see [WalkSignal].
            else -> if (c.signalControlled) SIGNAL_CONTROLLED else null
        },
        kerbLabel = when (c.kerb?.trim()?.lowercase()) {
            "lowered" -> "Lowered kerb"
            "flush" -> "Flush kerb"
            "raised" -> "Raised kerb"
            else -> null
        },
        // `yes` only. "no" and absent are both rendered as silence — see
        // [WalkCrossingDetail.tactilePaving].
        tactilePaving = if (c.tactilePaving?.trim()?.lowercase() == "yes") true else null,
        markingsLabel = c.markings?.takeIf { it.isNotBlank() }?.let { "Marked" },
    )

    /** Stair attributes, filtered to what is actually known. */
    fun detailFor(s: WalkStairs): WalkStairsDetail = WalkStairsDetail(
        // A count of zero is not a staircase and is treated as no survey.
        stepCountLabel = s.stepCount?.takeIf { it > 0 }?.let { "$it steps" },
        handrail = if (s.handrail?.trim()?.lowercase() == "yes") true else null,
        inclineLabel = when (s.incline?.trim()?.lowercase()) {
            "up" -> "Up"
            "down" -> "Down"
            else -> null
        },
    )

    /**
     * The secondary line, or null when nothing is known.
     *
     * Joined with a middot in a fixed order so the same facts always render
     * the same string — a banner whose word order depends on map iteration is
     * a banner that appears to change while a walker stands still.
     */
    private fun detailLine(
        crossing: WalkCrossingDetail?,
        stairs: WalkStairsDetail?,
    ): String? {
        val parts = buildList {
            crossing?.let { c ->
                c.typeLabel?.let(::add)
                c.markingsLabel?.let(::add)
                c.kerbLabel?.let(::add)
                if (c.tactilePaving == true) add("Tactile paving")
            }
            stairs?.let { s ->
                s.stepCountLabel?.let(::add)
                s.inclineLabel?.let(::add)
                if (s.handrail == true) add("Handrail")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

/**
 * How the three walk durations are shown (V7.4 4C final, honouring 4B.4 D1).
 *
 * ## The decision this object records
 *
 * The frozen contract publishes three different numbers, and 4B.4 D1 froze
 * their meanings:
 *
 * | quantity | field | what it is |
 * |---|---|---|
 * | pure pace time | `duration_s` | distance / walking pace |
 * | selection cost | `cost.cost_s` | what the search minimised |
 * | crossing delay | `cost.crossing.wait_s` | expected wait at crossings |
 *
 * **`duration_s` is what a walker is shown, unchanged.** It is already the
 * number the shipping journey card prints as "N min walk" and already the key
 * `MainActivity` uses to pick the best of several candidate parking walks, and
 * folding crossing delay into it would silently change an existing field's
 * meaning — the one thing 4B.4 exists to prevent.
 *
 * **Crossing delay is shown SEPARATELY or not at all.** [crossingDelay]
 * produces a clearly additive phrase, never a revised total, so the two
 * numbers can never be mistaken for one. It is withheld below
 * [MIN_CROSSING_DELAY_S] because "plus about 0 min of crossings" is noise, and
 * it is always hedged ("about") because the backend's own model is 6–15 s per
 * crossing EDGE rather than a measurement of any particular junction.
 *
 * Nothing here adds the two together. There is deliberately no function that
 * returns a combined duration, because the way a presentation layer silently
 * changes a contract is by offering one.
 */
object WalkEtaText {

    /**
     * Expected crossing delay below which nothing is said.
     *
     * 30 s. Under half a minute the phrase would round to "about 0 min" or
     * "about 1 min" for a delay of 31 s, and a hedge that granular is not
     * information. The real crossing-heavy Qatar walk carries 132 s, which
     * clears this comfortably; the ordinary walk carries 0.
     */
    const val MIN_CROSSING_DELAY_S = 30.0

    /**
     * The walk time a person is shown, from `duration_s` alone.
     *
     * Null when the backend reported no duration. Never derived from
     * [WalkEta.selectionCostS] — that number includes penalties nobody
     * experiences as elapsed time and is not a duration at all.
     */
    fun walkTime(eta: WalkEta?): String? {
        val s = eta?.displayDurationS ?: return null
        return minutes(s)
    }

    /**
     * The expected crossing delay, as an explicitly additive phrase, or null.
     *
     * "plus about 2 min of crossing waits". It reads as an addition to the
     * walk time rather than as a correction of it, which is the entire point:
     * a walker who sees "8 min walk · plus about 2 min of crossing waits" has
     * both numbers and can tell which is which, while "10 min walk" would have
     * quietly redefined a field four shipping features depend on.
     */
    fun crossingDelay(eta: WalkEta?): String? {
        val wait = eta?.crossingWaitS ?: return null
        if (wait < MIN_CROSSING_DELAY_S) return null
        return "plus about ${minutes(wait)} of crossing waits"
    }

    /** Minutes, never "0 min" — the one duration no walk has. */
    private fun minutes(seconds: Double): String {
        val mins = Math.round(seconds / 60.0).toInt()
        return when {
            mins < 1 -> "1 min"
            mins < 60 -> "$mins min"
            else -> {
                val h = mins / 60
                val m = mins % 60
                if (m == 0) "$h h" else "$h h $m min"
            }
        }
    }
}
