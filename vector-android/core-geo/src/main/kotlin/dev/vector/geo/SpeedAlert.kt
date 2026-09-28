package dev.vector.geo

/**
 * Decides when to tell a driver they are over the limit.
 *
 * ## What Vector did before, and what was missing
 *
 * The over-limit state existed and was drawn: the speedometer's value turns
 * amber and gains a ring, never colour alone, because about 8% of male drivers
 * cannot reliably separate the amber from the neutral foreground. That is a
 * good indicator and it stays.
 *
 * What it is not is a *notification*. A driver over the limit is, by
 * definition, looking at the road — the one place the indicator is not. Both
 * reference products say the limit out loud, and Vector already had the voice
 * mode for it: `VoiceMode.ALERTS` is documented as existing "precisely for
 * this and nothing else", and nothing ever spoke an over-limit alert.
 *
 * ## Why this is a state machine and not an `if`
 *
 * Because the failure modes of a speed alert are all about repetition, and
 * every one of them makes the driver switch it off:
 *
 *  * **Chatter at the boundary.** GPS speed is noisy and a speedometer reads a
 *    few km/h high by design, so a driver holding a genuine 80 in an 80 crosses
 *    the threshold constantly. Handled by the tolerance (which the indicator
 *    already had) plus a separate, tighter re-arm speed, so coming back under
 *    has to be meant.
 *  * **Announcing once and never again.** A driver twenty over for five minutes
 *    should hear about it more than once.
 *  * **Announcing every second.** Hence [minRepeatMs].
 *  * **Announcing an unposted number.** An inferred limit is the median for the
 *    road's class, not a surveyed sign, and accusing a driver of breaking a
 *    limit nobody has surveyed is worse than saying nothing. The indicator
 *    already refuses to; so does this.
 *  * **Announcing on a new road before the limit is known.** A change of limit
 *    re-arms, so entering a 50 from an 80 gets its own alert rather than being
 *    suppressed by the one from before.
 *
 * Pure and JVM-tested, like every other decision in this package: "did it
 * announce twice?" is not a question anybody can answer by driving around.
 */
class SpeedAlert(
    /**
     * Slack over the limit before an alert fires, km/h.
     *
     * The same 5 km/h the visual indicator uses, and for the same reason —
     * flagging 51 in a 50 would cry wolf and the driver would learn to ignore
     * it. Shared as a parameter rather than a constant so the two can be
     * pinned to each other by a test rather than by hope.
     */
    private val toleranceKmh: Int = 5,
    /**
     * How far back UNDER the limit the driver has to come before a new alert
     * can fire, km/h.
     *
     * Deliberately not the same boundary. If firing and re-arming shared a
     * threshold, a driver hovering at it would trigger an alert every few
     * seconds — which is the same oscillation the camera's auto-zoom bands
     * needed hysteresis for, and it has the same shape and the same fix.
     */
    private val rearmMarginKmh: Int = 2,
    /**
     * Shortest gap between two alerts about the same limit.
     *
     * A minute. Long enough not to nag, short enough that a sustained
     * excursion is mentioned more than once.
     */
    private val minRepeatMs: Long = 60_000L,
) {

    private var armed = true
    private var lastFiredAtMs: Long? = null
    private var lastLimit: Int? = null

    fun reset() {
        armed = true
        lastFiredAtMs = null
        lastLimit = null
    }

    /**
     * @param limitKmh the posted limit, or null when none is known.
     * @param inferred true when [limitKmh] is the median for the road's class
     *   rather than a surveyed sign. Never alerted on.
     * @return what to say, or null.
     */
    fun update(speedKmh: Int?, limitKmh: Int?, inferred: Boolean, nowMs: Long): String? {
        // A new limit is a new fact. Re-arm, and do not carry the previous
        // road's cooldown onto it: dropping from an 80 into a 50 and staying at
        // 70 is a new excursion and deserves to be said.
        if (limitKmh != lastLimit) {
            lastLimit = limitKmh
            armed = true
            lastFiredAtMs = null
        }
        if (limitKmh == null || inferred || speedKmh == null) return null

        val over = speedKmh > limitKmh + toleranceKmh
        if (!over) {
            // Re-arm only once genuinely back under, not merely at the
            // threshold. See [rearmMarginKmh].
            if (speedKmh <= limitKmh - rearmMarginKmh) armed = true
            return null
        }

        val since = lastFiredAtMs?.let { nowMs - it }
        val mayRepeat = since != null && since >= minRepeatMs
        if (!armed && !mayRepeat) return null

        armed = false
        lastFiredAtMs = nowMs
        // The LIMIT, not an accusation. "Speed limit 80" is what both reference
        // products say, and it is the piece of information the driver is
        // missing; they already know how fast they are going.
        return "Speed limit $limitKmh"
    }
}
