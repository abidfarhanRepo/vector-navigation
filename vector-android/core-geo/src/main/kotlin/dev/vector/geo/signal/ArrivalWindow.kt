package dev.vector.geo.signal

/**
 * When the vehicle is expected to reach the next signal, WITH an honest
 * uncertainty around it.
 *
 * ## What the window is for
 *
 * A phase prediction that depends on "the vehicle arrives at exactly T" is
 * fiction: arrival depends on lights, traffic and GPS timing, all of which
 * this model is deliberately not trying to predict (Stage 5 says don't
 * over-engineer traffic prediction). So [ArrivalWindow] separates the two
 * things it can honestly say:
 *
 *  * [etaS] — the best expected seconds-to-arrival, from the route's OWN
 *    planned average (the same number the HUD's ETA uses, so the two never
 *    disagree about how fast the journey is expected to go);
 *  * [uncertaintyS] — a half-width that GROWS with travel time and with a
 *    stop, and that never collapses below [FLOOR_S], because "expected" is
 *    not "exact" and the model refuses to imply exactness.
 *
 * The window is what feeds [SignalTiming.predict]'s `BOUNDARY` test: a wide
 * window near a phase transition correctly says "uncertain" where a
 * point-arrival model would guess a colour.
 *
 * ## The rules pinned by the fixtures
 *
 * * eta and uncertainty are never negative ([earliestS] clamps to 0);
 * * a stop widens the window ([STOP_PENALTY_S]); steady movement at a
 *   reported speed may narrow it toward the floor ([MOVING_CAP_S]);
 * * uncertainty never drops below [FLOOR_S] — no exact arrival, ever.
 */
data class ArrivalWindow(
    val etaS: Double,
    val uncertaintyS: Double,
) {
    init {
        require(etaS >= 0.0) { "eta must be non-negative" }
        require(uncertaintyS >= 0.0) { "uncertainty must be non-negative" }
    }

    /** The earliest honest arrival, clamped at 0: no negative minutes. */
    val earliestS: Double get() = (etaS - uncertaintyS).coerceAtLeast(0.0)

    /** The latest honest arrival. */
    val latestS: Double get() = etaS + uncertaintyS

    companion object {
        /** Minutes-per-hour of travel time that is not predictable. Closed: a
         * window that never widened with distance would claim more precision
         * the longer the run, which is backwards. */
        const val UNCERTAINTY_BASE_S = 12.0
        const val UNCERTAINTY_TRAVEL_FRACTION = 0.12

        /** A stop (red light, traffic) adds this much doubt. Stop-and-go
         * keeps re-adding it, which is how the window "widens correctly"
         * under stop-and-go. */
        const val STOP_PENALTY_S = 15.0

        /** Steady movement caps the window at this. Mirrors the idea behind
         * NavState.MOVING_MS (the app module's own constant); the equality is
         * pinned by an app-side test so the two cannot drift. */
        const val SIGNAL_MOVING_MS = 1.5

        /** Above this width, steady movement narrows the window. */
        const val MOVING_CAP_S = 25.0

        /** The floor. "Expected in 5 ± 5 s" is the honest limit of a model
         * that makes no traffic prediction. */
        const val FLOOR_S = 5.0

        /**
         * The arrival window to the next signal, from the navigation state
         * the loop already keeps — deliberately NOT a second position model.
         *
         * @param remainingM distance along the route to the signal.
         * @param plannedAvgMs the route's own planned average speed
         *   (routeDistanceM / routeDurationS), the same number the HUD ETA
         *   derives from, so this estimate and the ETA can never disagree.
         * @param effectiveMs the current measured speed, or null when the
         *   receiver did not report one (then the window may not be narrowed
         *   by movement — an unknown speed is not evidence of steady travel).
         * @param stoppedRecent true when the vehicle was at a standstill on
         *   the last navigation tick.
         */
        fun of(
            remainingM: Double,
            plannedAvgMs: Double,
            effectiveMs: Double?,
            stoppedRecent: Boolean,
        ): ArrivalWindow {
            val avg = plannedAvgMs.coerceAtLeast(1.0)
            val eta = remainingM / avg
            var u = UNCERTAINTY_BASE_S + UNCERTAINTY_TRAVEL_FRACTION * eta
            if (stoppedRecent) {
                u += STOP_PENALTY_S
            } else if (effectiveMs != null && effectiveMs >= SIGNAL_MOVING_MS) {
                u = minOf(u, MOVING_CAP_S)
            }
            return ArrivalWindow(eta, maxOf(u, FLOOR_S))
        }
    }
}