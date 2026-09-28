package dev.vector.geo

/**
 * Decides WHEN to speak a maneuver, and what to say.
 *
 * Pure logic, in core-geo rather than in the Android layer, because "did it
 * announce the turn twice?" and "did it announce it too late at 100 km/h?" are
 * exactly the questions that are impossible to answer by hand on a device and
 * trivial to answer in a unit test.
 *
 * The model is the one every navigator uses: announce a maneuver at decreasing
 * distances as you approach it, each stage at most once, and scale the far
 * stage with speed — 500 m of warning is generous in town and far too late on a
 * motorway at 120 km/h, where it is 15 seconds.
 */
class ManeuverAnnouncer(
    /** Seconds of warning the earliest announcement aims for. */
    private val prepareSeconds: Double = 30.0,
    private val minPrepareM: Double = 250.0,
    private val maxPrepareM: Double = 1500.0,
    /**
     * Units the spoken distance uses.
     *
     * A `var` rather than a constructor-only value: the driver can change the
     * setting mid-journey, and rebuilding the announcer to apply it would clear
     * `fired` and re-announce the maneuver they are currently in the middle of.
     */
    var units: Units = Units.METRIC,
) {
    /** A stage fires once per maneuver, in order. */
    enum class Stage { PREPARE, TURN, NOW }

    data class Announcement(val stage: Stage, val text: String)

    /** Maneuver identity + how far ahead it is. */
    data class Upcoming(
        val index: Int,
        val type: String,
        val instruction: String,
        val distanceM: Double,
    )

    private var lastIndex: Int = -1
    private val fired = HashSet<Stage>()

    fun reset() {
        lastIndex = -1
        fired.clear()
    }

    /**
     * @return the announcement to speak now, or null.
     *
     * `speedMs` scales only the PREPARE stage; TURN and NOW are fixed distances
     * because they correspond to physical positions (the junction is visible,
     * the junction is here) rather than to a time budget.
     */
    fun update(next: Upcoming?, speedMs: Double): Announcement? = update(next, speedMs, null)

    /**
     * @param following the maneuver AFTER [next], when there is one.
     *
     * ## The defect this parameter exists because of
     *
     * Vector announced one maneuver at a time, which means the warning a driver
     * gets for a turn can be no longer than the leg leading up to it — the
     * maneuver does not become "next" until the previous one is done. Measured
     * on the live router's Al Wakrah → Doha route, whose first kilometre is
     * seven turns in 1 154 m:
     *
     * ```
     *  1 turn-left   cum=  178  leg= 45
     *  2 turn-right  cum=  223  leg= 55
     *  3 turn-left   cum=  278  leg=263
     * ```
     *
     * Maneuver 2 became current 45 m before it, so the driver was told to turn
     * right **2.4 seconds** before the junction, and never heard a PREPARE
     * stage for it at all. At 110 km/h on the exit sequence at the far end of
     * the same route, that is not a warning.
     *
     * Both reference products solve it the same way and it is what a passenger
     * does: say both. "Turn left onto Al Thumama Street, then turn right." The
     * second half is deliberately the ACTION without its road name — the road
     * name is what the following maneuver's own announcement is for, and a
     * sentence naming two Doha street names is longer than the gap it is
     * warning about.
     */
    fun update(next: Upcoming?, speedMs: Double, following: Upcoming?): Announcement? {
        if (next == null) return null
        if (next.index != lastIndex) {
            lastIndex = next.index
            fired.clear()
        }
        // "Arrive" and "depart" are not turns; announcing "in 300 m, depart" is
        // noise. Arrival still gets its NOW stage below.
        if (next.type == "depart") return null

        val prepareAt = (speedMs * prepareSeconds).coerceIn(minPrepareM, maxPrepareM)

        val stage = when {
            next.distanceM <= NOW_M -> Stage.NOW
            next.distanceM <= TURN_M -> Stage.TURN
            next.distanceM <= prepareAt -> Stage.PREPARE
            else -> null
        } ?: return null

        // Never repeat a stage, and never go backwards: if the driver is already
        // 40 m from the turn, do not then announce the 300 m warning.
        if (stage in fired) return null
        if (fired.any { it.ordinal > stage.ordinal }) return null
        fired.add(stage)

        // Compound only when the two manoeuvres are close enough that the
        // second one's own announcement would be too late. Scaled with speed
        // for the same reason the PREPARE distance is: 120 m is four seconds at
        // 110 km/h and nine in town.
        val compoundAt = (speedMs * COMPOUND_SECONDS).coerceAtLeast(MIN_COMPOUND_M)
        val thenPart = following
            ?.takeIf { it.distanceM - next.distanceM in 0.0..compoundAt }
            ?.let { shortAction(it) }

        val base = phrase(stage, next)
        return Announcement(stage, if (thenPart != null) "$base, then $thenPart" else base)
    }

    /**
     * The following maneuver as an action, without its road.
     *
     * Derived from [Upcoming.type] rather than by cutting up the instruction:
     * the instruction is localised by the backend, so string surgery on it
     * would produce "شارع then" for an Arabic-language phone. The glue words
     * here are English for the same reason "In 300 metres" is — see the class
     * KDoc — and the fallback keeps the router's own sentence rather than
     * inventing one.
     */
    private fun shortAction(m: Upcoming): String = when (m.type) {
        "turn-left" -> "turn left"
        "turn-right" -> "turn right"
        "slight-left" -> "bear left"
        "slight-right" -> "bear right"
        "sharp-left" -> "turn sharp left"
        "sharp-right" -> "turn sharp right"
        "uturn" -> "make a U-turn"
        "roundabout" -> "take the roundabout"
        "arrive" -> "arrive"
        "continue", "depart" -> "continue"
        else -> decapitalise(m.instruction)
    }

    private fun phrase(stage: Stage, next: Upcoming): String = when (stage) {
        Stage.NOW -> if (next.type == "arrive") "You have arrived" else next.instruction
        Stage.TURN -> next.instruction
        Stage.PREPARE -> "In ${units.spokenDistance(next.distanceM)}, ${decapitalise(next.instruction)}"
    }

    private fun decapitalise(s: String): String =
        if (s.isNotEmpty() && s[0].isUpperCase() && !s.startsWith("At the")) {
            s[0].lowercaseChar() + s.substring(1)
        } else s

    companion object {
        const val TURN_M = 120.0
        const val NOW_M = 30.0

        /**
         * Seconds of travel within which a following maneuver is announced
         * together with this one.
         *
         * Four seconds is the point at which the second manoeuvre's own
         * announcement stops arriving in time to be acted on — it cannot fire
         * before the first one is complete, and a driver mid-turn is not
         * listening.
         */
        const val COMPOUND_SECONDS = 4.0

        /** Floor for the compound distance, so town driving still gets it. */
        const val MIN_COMPOUND_M = 120.0

        /**
         * Distances a person would say, in metric.
         *
         * Kept as the metric shorthand it always was. The rounding ladders for
         * both unit systems now live in [Units], because the screen formatter
         * needs the same ones and having two copies is how they came apart.
         */
        fun spokenDistance(m: Double): String = Units.METRIC.spokenDistance(m)
    }
}
