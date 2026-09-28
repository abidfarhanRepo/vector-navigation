package dev.vector.geo

/**
 * Lane guidance: which lane should the driver actually be in.
 *
 * The last big feature gap against Google Maps and Waze. Being told "turn left
 * in 200 m" on a six-lane Doha arterial is not enough — the useful instruction
 * is *which* of those lanes turns left, and it is the difference between making
 * the turn and being carried through the junction.
 *
 * OSM encodes it as `turn:lanes`, pipe-separated left-to-right in the direction
 * of travel, with `;` for a lane that permits several movements:
 *
 *     turn:lanes = left|through|through          three lanes
 *     turn:lanes = reverse;left|left|left        leftmost also allows a U-turn
 *     turn:lanes = ||right                       two unmarked, then right-only
 *
 * Doha has 3,090 ways carrying it, so this is real data rather than a
 * hypothetical.
 *
 * Pure Kotlin: which lanes light up for a given maneuver is a decision that
 * should never require a device to check.
 */
object LaneGuidance {

    /**
     * Seconds of travel over which lane guidance is worth showing.
     *
     * Crossing two or three lanes of traffic takes roughly this long to look,
     * indicate and wait for a gap — twice — and it takes about as long at
     * 50 km/h as at 100, which is why the window is a time and not a distance.
     *
     * 25 s and not 20. The first version used 20, and `NavUiTest`'s existing
     * "lane guidance is shown when it narrows the choice" case failed on it:
     * that case describes a driver 400 m from a turn at 58 km/h, which is 25
     * seconds out, and the strip was being withheld. That is the wrong side of
     * the trade — the request this window exists to serve is *"so they don't
     * have to take hard swerves at the end to reach the turn through
     * traffic"*, and a diagram that arrives with 20 seconds to go on a
     * six-lane arterial is arriving late. Both reference products show lanes
     * at roughly 400 m in town.
     */
    const val LANE_LEAD_SECONDS = 25.0

    /**
     * Why the strip is (not) on screen.
     *
     * Stage 1 of V7 separates two facts the old empty-list never could: "the
     * map has no lane data for this approach" and "the map has data but it
     * narrows nothing". Both hide the strip today, but they are different
     * promises going forward — an UNKNOWN approach cannot be highlighted on the
     * map, while a NONE_USEFUL one can (every lane is legal, so none is
     * singled out).
     */
    enum class Status { UNKNOWN, NONE_USEFUL, USEFUL }

    /**
     * Floor for the lane-guidance window, so town driving still gets it.
     *
     * At 20 km/h, 25 s is 139 m — inside the junction itself, by which point
     * the lane is chosen. 300 m is about a block and a half of Doha.
     */
    const val MIN_LANE_LEAD_M = 300.0

    /** Cap, so a motorway leg does not carry a lane diagram for a mile. */
    const val MAX_LANE_LEAD_M = 700.0

    /** One lane, and what it permits. */
    data class Lane(
        /** The raw OSM indications this lane allows, e.g. ["reverse", "left"]. */
        val indications: List<String>,
        /** True when this lane can be used for the upcoming maneuver. */
        val valid: Boolean = false,
        /**
         * The indication that made this lane [valid], when one did.
         *
         * Set by [forManeuver], which is the only thing that knows which
         * indications the maneuver accepts. Without it [primary] could only
         * ever draw the FIRST indication, and OSM writes them in the order the
         * signs are painted rather than in order of usefulness: Doha's
         * commonest left-turn lane is tagged `reverse;left`, so the lane the
         * driver should be in was drawn as a **U-turn arrow**.
         */
        val matched: String? = null,
        /**
         * True when this lane exists ONLY for the maneuver: its every
         * indication is one the maneuver accepts, so it is *dedicated* to it,
         * not merely legal. A legal-but-shared lane (`left;through`) must not
         * be styled as dedicated, and a dedicated lane must not be worded as
         * "preferred" — that word is reserved for backend provenance
         * ([preferredIndices]).
         */
        val dedicated: Boolean = false,
    ) {
        /** The indication to draw: the valid one if this lane is usable. */
        val primary: String
            get() = matched ?: indications.firstOrNull() ?: "none"
    }

    /**
     * Parse a `turn:lanes` value into lanes, left to right.
     *
     * An empty entry means an unmarked lane, which is NOT the same as no lane —
     * `||right` is three lanes, and dropping the blanks would shift the driver's
     * count and point at the wrong one.
     */
    fun parse(spec: String?): List<Lane> {
        if (spec.isNullOrBlank()) return emptyList()
        return spec.split('|').map { cell ->
            Lane(
                cell.split(';')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            )
        }
    }

    /**
     * Mark the lanes that serve [maneuverType].
     *
     * The mapping is deliberately generous in one direction and strict in the
     * other. A `slight-left` maneuver accepts a `left` lane, because a lane
     * marked left will get you round a shallow bend; a `left` maneuver does NOT
     * accept a `through` lane, because going straight on will not.
     *
     * The same generosity admits `merge_to_left`/`merge_to_right`: the
     * backend's bearing classifier has NO merge maneuver type (router.py
     * `_classify_turn` emits `slight-left/right` for the 20-45 degree band a
     * merge occupies), so an on-ramp signed `merge_to_right` arrives under a
     * `slight-right` — and a lane whose ONLY use is merging right is exactly
     * the lane that serves it. This closes a real data gap: 77 ways in the
     * deployed Qatar graph carry `merge_to_*` cells, and before this change
     * none of them could ever make a lane valid, so the ribbon sat on the
     * carriageway centre instead of the marked merge lane.
     *
     * When NOTHING matches, every lane is returned invalid rather than every
     * lane valid. Highlighting all six lanes of an arterial is worse than
     * highlighting none — it looks like advice and is not.
     */
    fun forManeuver(lanes: List<Lane>, maneuverType: String): List<Lane> {
        if (lanes.isEmpty()) return emptyList()
        val accepted = acceptedIndications(maneuverType)
        if (accepted.isEmpty()) return lanes.map { it.copy(valid = false, matched = null) }
        return lanes.map { l ->
            // `firstOrNull { in accepted }` and not `any`: the indication that
            // matched is what should be DRAWN for this lane, and it is only
            // known here.
            val hit = l.indications.firstOrNull { it in accepted }
            val dedicated = hit != null && l.indications.isNotEmpty() &&
                l.indications.all { it in accepted }
            l.copy(valid = hit != null, matched = hit, dedicated = dedicated)
        }
    }

    private fun acceptedIndications(maneuverType: String): Set<String> = when (maneuverType) {
        "turn-left" -> setOf("left", "sharp_left", "merge_to_left")
        "slight-left" -> setOf("slight_left", "left", "merge_to_left")
        "turn-right" -> setOf("right", "sharp_right", "merge_to_right")
        "slight-right" -> setOf("slight_right", "right", "merge_to_right")
        "uturn" -> setOf("reverse")
        // "continue" and "arrive" are not lane decisions; "depart" has no
        // preceding lane context at all. Deliberately: a lane SIGNED as a
        // merge diverges from the mainline, and a driver who is continuing
        // must never be routed into it — claiming it would be the same
        // fabrication as guessing a lane.
        else -> emptySet()
    }

    /**
     * The lanes to show, or none when they narrow nothing.
     *
     * The single helper behind [Maneuver.lanes]: parse once, mark for the
     * maneuver, then suppress the all-valid/all-invalid/single-lane cases. It
     * exists so the client caches a maneuver's lanes at route application
     * instead of re-parsing on every recomposition.
     */
    fun usefulLanes(spec: String?, maneuverType: String): List<Lane> {
        val marked = forManeuver(parse(spec), maneuverType)
        return if (isUseful(marked)) marked else emptyList()
    }

    /**
     * Classify a lane string for the strip's presence, honestly.
     *
     * [Status.UNKNOWN] covers both "no string on the wire" and "this maneuver
     * has no lane decision" (continue/depart/arrive/roundabout) — the two are
     * indistinguishable to the driver and both mean: no strip, no map
     * highlight. [Status.NONE_USEFUL] is a present string that narrows
     * nothing (all lanes legal, none legal, a single lane). [Status.USEFUL]
     * is the old [isUseful] true.
     */
    fun statusOf(spec: String?, maneuverType: String): Status {
        if (acceptedIndications(maneuverType).isEmpty()) return Status.UNKNOWN
        val parsed = parse(spec)
        if (parsed.isEmpty()) return Status.UNKNOWN
        return if (usefulLanes(spec, maneuverType).isNotEmpty()) Status.USEFUL else Status.NONE_USEFUL
    }

    /**
     * The backend-provenanced preferred lanes, and only those.
     *
     * Never infers: an empty set is returned unless the caller supplies the
     * backend's `preferred` array (per-lane destination tags or connectivity,
     * neither of which exists in the current extract). Rendering a
     * "preferred" lane from indications alone would be a routing claim OSM
     * never made.
     */
    fun preferredIndices(lanes: List<Lane>, preferred: List<Int>?): Set<Int> {
        if (preferred == null) return emptySet()
        return preferred.filter { it in lanes.indices }.toSet()
    }

    /**
     * Should guidance be shown at all?
     *
     * Only when it tells the driver something. If every lane is valid the
     * instruction is "stay where you are", which is noise; if none is, the data
     * disagrees with the route and showing it would be actively misleading.
     */
    fun isUseful(lanes: List<Lane>): Boolean {
        if (lanes.size < 2) return false
        val valid = lanes.count { it.valid }
        return valid in 1 until lanes.size
    }

    /**
     * Should lane guidance be on screen yet?
     *
     * A maneuver becomes "current" the moment the previous one is done, so
     * without a distance rule the lane strip appears at the start of the leg
     * and stays there — on the 7.4 km leg of the live router's Al Wakrah route
     * that is seven kilometres of a lane diagram the driver cannot act on yet,
     * which is exactly how a driver learns to stop looking at it.
     *
     * The window is what the request behind this asks for: *"if the user has to
     * take a turn from an intersection, they can keep the correct lane so they
     * don't have to take hard swerves at the end to reach the turn through
     * traffic."* That is a TIME budget, not a distance — crossing two or three
     * lanes of traffic takes roughly twenty seconds of looking, indicating and
     * waiting for a gap, whatever the speed. So: 20 s of travel, floored at
     * 250 m so it is useful in town, and capped so a motorway leg does not
     * show it for a mile and a half.
     */
    fun showAt(distanceToManeuverM: Double, speedMs: Double): Boolean {
        if (distanceToManeuverM <= 0.0) return false
        val window = (speedMs * LANE_LEAD_SECONDS)
            .coerceIn(MIN_LANE_LEAD_M, MAX_LANE_LEAD_M)
        return distanceToManeuverM <= window
    }

    /**
     * The maneuver arrow that draws a lane's indication.
     *
     * Lane indications and maneuver types are different vocabularies for the
     * same shapes, and the drawn icon set is indexed by maneuver type — so
     * this is the translation, in the module that owns both halves of the lane
     * model rather than in the renderer. `sharp_*` reuses the plain turn arrow
     * because a lane diagram is read at a glance and one more angle in it is
     * noise; `merge_to_*` reuses the slight turn, which is what a merge is.
     */
    fun arrowType(indication: String): String = when (indication) {
        "left" -> "turn-left"
        "sharp_left" -> "turn-left"
        "slight_left", "merge_to_left" -> "slight-left"
        "right" -> "turn-right"
        "sharp_right" -> "turn-right"
        "slight_right", "merge_to_right" -> "slight-right"
        "reverse" -> "uturn"
        else -> "depart"
    }

    /** How a lane reads out loud, for the accessibility description. */
    fun spoken(indication: String): String = when (indication) {
        "left" -> "left"
        "sharp_left" -> "sharp left"
        "slight_left" -> "slight left"
        "right" -> "right"
        "sharp_right" -> "sharp right"
        "slight_right" -> "slight right"
        "reverse" -> "U-turn"
        "merge_to_left" -> "merge left"
        "merge_to_right" -> "merge right"
        "through" -> "straight ahead"
        else -> "unmarked"
    }

}
