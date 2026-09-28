package dev.vector.geo.journey

import dev.vector.geo.sun.RouteShade
import dev.vector.geo.sun.ShadeEstimator
import dev.vector.geo.sun.SunState

/**
 * Sun exposure as a [LegAnnotator].
 *
 * Annotates walking legs and nothing else: a drive's exposure is a question
 * about a car's air conditioning, not about the route, and answering it would
 * be inventing a number because a field existed to put one in.
 */
object ShadeAnnotator : LegAnnotator {
    override fun annotate(leg: Leg, atMs: Long): List<Annotation> {
        if (leg !is WalkLeg || leg.segments.isEmpty()) return emptyList()
        return listOf(ShadeEstimator.route(leg.segments, atMs).annotation())
    }

    /** The full estimate, for a caller that needs more than the annotation. */
    fun shade(leg: WalkLeg, atMs: Long): RouteShade =
        ShadeEstimator.route(leg.segments, atMs)
}

/**
 * Choosing between two walking routes on shade.
 *
 * ## Why this is not just "pick the lower number"
 *
 * A cooler route costs the walker time — it is a detour, or it would be the
 * route already — so offering one is a trade, and a trade is only worth
 * presenting when both sides are real. Three things have to hold, and each
 * rules out a different way of offering a bad one:
 *
 *  1. The shade difference has to be **big enough to survive the model's own
 *     error**. The facade height is assumed; a four-point difference is the
 *     assumption talking, not the street.
 *  2. The detour has to be **small enough to be worth it**. Ten minutes of
 *     shade instead of six minutes of sun is not an improvement anyone asked
 *     for, and a "cooler route" that doubles the walk will be taken once.
 *  3. There has to **be** sun. At night, and while the sun is too low to
 *     model, there is nothing to prefer — see [SunState].
 *
 * When any of them fails the answer is [CoolerRoute.none], and the UI shows no
 * toggle at all rather than a disabled one. A control that is present but
 * refuses is worse than an absent control: it advertises a feature and then
 * declines to perform it.
 */
object CoolerRoute {

    /** The most a cooler route may lengthen the walk, as a fraction. */
    const val MAX_DETOUR_FRACTION = 0.6

    /**
     * Is [alternative] a cooler walk worth offering instead of [direct]?
     *
     * @return the offer, or [Offer.none] when it is not worth making.
     */
    fun consider(
        direct: WalkLeg,
        directShade: RouteShade,
        alternative: WalkLeg,
        alternativeShade: RouteShade,
    ): Offer {
        val comparison = alternativeShade.comparedTo(directShade)
        if (!comparison.meaningful || comparison.cooler !== alternativeShade) return Offer.none

        val extraS = alternative.durationS - direct.durationS
        if (direct.durationS > 0 && extraS / direct.durationS > MAX_DETOUR_FRACTION) {
            return Offer.none
        }
        return Offer(
            available = true,
            deltaPoints = comparison.deltaPoints,
            extraDurationS = extraS,
        )
    }

    /**
     * The result of considering a cooler route.
     *
     * @property deltaPoints how many points of exposure the cooler route saves.
     * @property extraDurationS how much longer it takes. May be negative, when
     *   the cooler route also happens to be the quicker one.
     */
    data class Offer(
        val available: Boolean,
        val deltaPoints: Double = 0.0,
        val extraDurationS: Double = 0.0,
    ) {
        companion object {
            val none = Offer(available = false)
        }
    }
}
