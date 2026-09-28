package dev.vector.geo.journey

import dev.vector.geo.LngLat
import dev.vector.geo.sun.RouteShade
import dev.vector.geo.sun.WalkSegment
import kotlin.math.roundToInt

/**
 * One leg of a journey: a stretch covered in a single mode.
 *
 * Sealed because the set is closed for V7 — you drive, then you walk — and a
 * `when` over it should stop compiling on the day a third mode is added rather
 * than silently fall through a default branch.
 */
sealed interface Leg {
    val distanceM: Double
    val durationS: Double
}

/** The part you drive. */
data class DriveLeg(
    val geometry: List<LngLat>,
    override val distanceM: Double,
    override val durationS: Double,
) : Leg

/**
 * The part you walk — the Last Mile, and the whole point of this architecture.
 *
 * Carries [segments] rather than a bare polyline because the shade model reads
 * each segment's road class. A walk drawn as one undifferentiated line is a
 * walk whose exposure cannot be modelled.
 */
data class WalkLeg(
    val segments: List<WalkSegment>,
    override val distanceM: Double,
    override val durationS: Double,
    /** Metres of `highway=steps`. Worth warning someone with a suitcase about. */
    val stepsM: Double = 0.0,
    /** How far the endpoints moved to reach the pedestrian network. */
    val snapMaxM: Double = 0.0,
) : Leg {
    /** The walk as a polyline, for drawing. */
    val geometry: List<LngLat>
        get() = if (segments.isEmpty()) emptyList()
        else listOf(segments.first().from) + segments.map { it.to }
}

/** Where the car is left. */
data class ParkSpot(val position: LngLat, val name: String?)

/**
 * A destination, and everything between here and actually standing at it.
 *
 * ## Why this object exists at all
 *
 * Vector has always had a *route*: a line ending at a coordinate. A route ends
 * where the car stops, and the person does not. The gap between those two
 * facts is the product thesis — in Doha it is the difference between a 14
 * minute trip and a 14 minute trip plus six minutes across an unshaded car
 * park in August — and it cannot be expressed by a route object, however many
 * fields are added to it, because it is a sequence in two different modes.
 *
 * ## What [parkingKnown] false means
 *
 * That Vector found no parking in the index near this destination, so the walk
 * is measured from the destination's own kerbside rather than from a car park.
 * It is NOT a claim that there is nowhere to park. The UI must say which of the
 * two it is showing, for the same reason the arrival card says "no parking
 * found" instead of guessing.
 */
data class Journey(
    val destinationName: String,
    val destination: LngLat?,
    val drive: DriveLeg? = null,
    val park: ParkSpot? = null,
    val walk: WalkLeg? = null,
    /** The modelled exposure of [walk]. Always [Provenance.MODELLED]. */
    val walkShade: RouteShade? = null,
    /** False when no indexed parking was found; see the class docstring. */
    val parkingKnown: Boolean = false,
    val annotations: List<Annotation> = emptyList(),
) {
    val legs: List<Leg> get() = listOfNotNull(drive, walk)

    val totalDurationS: Double get() = legs.sumOf { it.durationS }
    val totalDistanceM: Double get() = legs.sumOf { it.distanceM }

    /** True once there is something worth showing a journey card for. */
    val hasWalk: Boolean get() = walk != null && walk.distanceM > 0.0

    /**
     * The one line that is the entire product.
     *
     * "14 min drive · 6 min walk". Each half appears only when that leg exists,
     * so a journey with no walk reads exactly as Vector always did.
     *
     * The V7 plan's version of this line ends "· 42 °C". It is not here: a
     * temperature needs a weather source, and Vector has none. Inventing one,
     * or quietly showing a seasonal average as though it were today, would put
     * a fabricated number on the most prominent line in the application.
     */
    fun summaryLine(): String = buildList {
        drive?.let { add("${minutes(it.durationS)} min drive") }
        walk?.let { add("${minutes(it.durationS)} min walk") }
    }.joinToString(" · ")

    companion object {
        /** Whole minutes, never zero for a leg that exists. */
        fun minutes(durationS: Double): Int =
            if (durationS <= 0.0) 0 else maxOf(1, (durationS / 60.0).roundToInt())
    }
}

/**
 * One source of intelligence about a leg.
 *
 * The seam the whole architecture exists for: adding intersection delay later
 * is another implementation of this interface with a different
 * [Provenance], not another subsystem. Deliberately takes the instant as a
 * parameter — an annotator that reads the clock itself cannot be tested, and
 * cannot be driven by a time slider.
 */
fun interface LegAnnotator {
    fun annotate(leg: Leg, atMs: Long): List<Annotation>
}
