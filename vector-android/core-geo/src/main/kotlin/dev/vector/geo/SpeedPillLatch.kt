package dev.vector.geo

import dev.vector.geo.Callouts.SpeedChange

/**
 * Remembers the surveyed speed-limit-change pill against the maneuver it was
 * discovered ahead of, so it cannot flicker on and off as the car moves.
 *
 * ## The defect this closes
 *
 * The pill is found by asking `/speed` for the limit on the road 45 m past the
 * maneuver the driver is approaching. The old decision — "is the probed limit
 * different from the one already in force?" — was made when the reply landed,
 * against the LIVE `UiState.speedLimitKmh`. On a maneuver whose change point is
 * close, the reply can land after the vehicle has crossed that point; by then
 * `refreshRoadHere` has already moved `speedLimitKmh` to the new limit, the
 * comparison reads "no change", and the pill is withdrawn. A later maneuver's
 * probe can then put a different pill back, which is the observed
 * appear → disappear → reappear flicker.
 *
 * ## The rule
 *
 * A maneuver gets **one** query and **one** decision. The baseline it is
 * compared against is captured when the query is SENT, not when the reply
 * lands, so crossing the change point cannot re-open the decision. The result
 * is latched against the maneuver's identity and held until a later maneuver's
 * query replaces it or [clear] is called at arrival, teardown or reroute.
 *
 * The caller owns the two facts this class does not know: whether the limit was
 * surveyed (an inferred class median is never eligible) and where the probe
 * point is (45 m past the maneuver).
 */
class SpeedPillLatch {

    /** The pill that should currently be drawn, or null. */
    var pill: SpeedChange? = null
        private set

    /** The maneuver [pill] belongs to, or -1 when there is none. */
    var maneuver: Int = -1
        private set

    /** Maneuvers whose single query has been sent. */
    private val queried = HashSet<Int>()

    /** Maneuvers whose single decision has been made. */
    private val decided = HashSet<Int>()

    /**
     * Claim the single query for [maneuverIndex].
     *
     * Returns false when the query has already been sent, so a re-approach (a
     * projection that jitters across the maneuver boundary, or the driver
     * oscillating around it) does not ask again for a maneuver that has already
     * been answered.
     */
    fun beginProbe(maneuverIndex: Int): Boolean = queried.add(maneuverIndex)

    /**
     * Record the reply to the single query for [maneuverIndex].
     *
     * [baselineKmh] MUST be the limit in force when the query was sent; see the
     * class KDoc. [inferred] is refused outright: a class median presented as a
     * posted sign is the confident wrong answer the speed disc exists to avoid.
     *
     * Idempotent per maneuver: a second reply (or a replayed one) never
     * re-decides, so the live baseline catching up cannot withdraw the pill.
     */
    fun resolve(
        maneuverIndex: Int,
        limitKmh: Int?,
        inferred: Boolean,
        baselineKmh: Int?,
        alongM: Double,
    ) {
        if (!decided.add(maneuverIndex)) return
        val limit = limitKmh
        // No surveyed sign at all, or a class median rather than a sign.
        if (limit == null || limit <= 0 || inferred) {
            pill = null
            maneuver = -1
            return
        }
        val change: SpeedChange? = if (limit != baselineKmh) SpeedChange(alongM, limit) else null
        pill = change
        maneuver = if (change != null) maneuverIndex else -1
    }

    /** Forget everything, on arrival, reroute or a replaced route. */
    fun clear() {
        pill = null
        maneuver = -1
        queried.clear()
        decided.clear()
    }
}
