package dev.vector.geo.sun

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.journey.Annotation
import dev.vector.geo.journey.AnnotationKind
import dev.vector.geo.journey.Provenance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** Whether there is any direct sun worth modelling. */
enum class SunState {
    /** Sun below the horizon. Nothing is "shaded"; there is simply no sun. */
    NIGHT,

    /** Sun up but very low. Shadows are enormous and the model is unreliable. */
    LOW_SUN,

    /** Sun high enough for street orientation to mean something. */
    DAY,

    /** No geometry to evaluate. */
    UNKNOWN,
}

/**
 * One straight piece of a walking route, with the few OSM tags the model reads.
 *
 * Deliberately not a reference to a routing graph edge: `core-geo` is pure and
 * knows nothing about `vector-routing`'s wire format, so the caller adapts.
 */
data class WalkSegment(
    val from: LngLat,
    val to: LngLat,
    /** OSM `highway` value, e.g. `footway`, `residential`, `primary`. */
    val highway: String? = null,
    /** OSM `covered=yes`. */
    val covered: Boolean = false,
    /** OSM `tunnel=yes`. */
    val tunnel: Boolean = false,
    /** OSM `indoor=yes`. */
    val indoor: Boolean = false,
    /**
     * OSM `area=yes` — a plaza, a square, a surface car park.
     *
     * The single most important flag here, because it is the one case where the
     * model knows it does not apply: see [ShadeEstimator].
     */
    val area: Boolean = false,
)

/**
 * How the model reached one segment's answer (V7.4 shade).
 *
 * ## Why this is carried rather than inferred
 *
 * [SegmentExposure.exposure] alone cannot tell a caller WHICH of four very
 * different things it is looking at:
 *
 *  - a tag that says the way is enclosed — an OSM FACT, the strongest thing
 *    this model has;
 *  - the modelled facade assumption — an INFERENCE, the weakest;
 *  - a segment the model **declined** to answer (an open plaza, an
 *    unrecognised road class), whose `exposure` is a conservative 1.0 and
 *    NOT a finding;
 *  - a segment with no sun to be exposed to at all (night, low sun), whose
 *    `exposure` of 0.0 is an astronomical fact rather than a shade claim.
 *
 * Before this existed, a consumer that summed `exposure` length-weighted —
 * which is exactly what [RouteShade] does, and correctly, for a preview with a
 * sun slider — could not distinguish "we modelled this and it is shaded" from
 * "we declined to model this" or "the sun had set", and any presentation built
 * on that number could turn a declined question into a confident answer. The
 * provenance is computed HERE, by the code that made the decision, instead of
 * being re-derived downstream from a confidence constant.
 */
enum class ShadeBasis {
    /** An OSM tag said the way is enclosed: `covered`, `tunnel`, `indoor`, `corridor`. */
    ENCLOSED,

    /** The sun is below the horizon. There is no sun to be exposed to. */
    NIGHT,

    /** The sun is up but below [ShadeEstimator.LOW_SUN_DEG]; see [SunState.LOW_SUN]. */
    LOW_SUN,

    /**
     * The modelled facade: the one case where the model genuinely answers the
     * question. Also covers the two class-derived answers — a way too wide for
     * anything beside it to reach (`primary`, `trunk`), and a sun so close to
     * the street's own bearing that no facade is between it and the walker.
     * Those are the same assumption table being read, not a decline.
     */
    FACADE,

    /**
     * An open area — a plaza, a square, a surface car park.
     *
     * The model **declines**: there is no facade beside it to assume, so it
     * reports full exposure at [ShadeEstimator]'s lowest confidence rather
     * than crediting shade it cannot justify. The `exposure` on such a segment
     * is a refusal to guess, not a measurement.
     */
    DECLINED_AREA,

    /** An `highway` class the table does not know (or an absent one). Declined, as above. */
    DECLINED_CLASS,

    /** Two coincident points: no bearing and no length, so nothing to answer. */
    DEGENERATE,
    ;

    /**
     * True when this is a *daylight shade answer* — something the model is
     * willing to be held to about the sun on this piece of ground.
     *
     * [DECLINED_AREA], [DECLINED_CLASS] and [DEGENERATE] are not answers, and
     * neither are [NIGHT] or [LOW_SUN] shade claims — at night nothing is
     * "shaded", there is simply no sun. A caller that needs to know how much
     * of a walk has been modelled must ask this rather than read `exposure`.
     */
    val answersShade: Boolean get() = this == FACADE || this == ENCLOSED
}

/** The modelled sun exposure of one [WalkSegment]. */
data class SegmentExposure(
    /** 0 = fully shaded, 1 = fully exposed. */
    val exposure: Double,
    val confidence: Double,
    val lengthM: Double,
    val sunState: SunState,
    val solar: SolarPos?,
    /** Compass bearing of the segment, or null when geometry is degenerate. */
    val bearingDeg: Double?,
    /** How this answer was reached. See [ShadeBasis]. */
    val basis: ShadeBasis,
) {
    val provenance: Provenance get() = Provenance.MODELLED
}

/**
 * Modelled sun exposure for a walk, from street orientation alone.
 *
 * ## What this computes
 *
 * For each segment: the sun's position ([SolarPosition]), the segment's compass
 * bearing, and one assumption — that a building of a height typical for the
 * road class stands along it. If the sun is low enough and close enough to
 * side-on, that assumed facade casts a shadow across the way and the walker is
 * in it.
 *
 *     shadowWidth = assumedHeight / tan(altitude)
 *     across      = shadowWidth * cos(angle between sun and street normal)
 *     shaded      = across / halfWidth, clamped to [0,1]
 *     exposure    = 1 - shaded
 *
 * The `cos` term is the part that makes the model behave: a shadow is cast
 * directly away from the sun, so only its component perpendicular to the street
 * actually crosses the street. When the sun is straight down the street
 * (`angle` near 90 deg) that component vanishes and the way is exposed no matter
 * how tall the buildings are, which is exactly why an east-west street in Doha
 * is punishing in the late afternoon.
 *
 * ## What this is NOT
 *
 * It is not a shadow calculation. There is no building footprint, no height
 * data, no ray tracing, no tree. It is a *typical-case* assumption about what
 * usually stands beside a given class of road, applied to real geometry and a
 * real sun position. It is therefore:
 *
 * - **right about the sun**, always, to well under a degree;
 * - **right about the street's orientation**, always, because that is measured;
 * - **a guess about the facade**, every time.
 *
 * ## Where it is confidently wrong, and what is done about it
 *
 * The two places this model fails in Doha are the open plazas of Msheireb and
 * the surface car parks at Aspire. Both are wide and unshaded, and a model that
 * assumes a facade would under-report exposure there — it would promise shade
 * that is not present, on exactly the walk where being wrong costs the most.
 *
 * So [WalkSegment.area] exists, and an area is treated as **fully exposed with
 * low confidence** rather than run through the facade model at all. The model
 * declines the question instead of answering it badly. The same applies to an
 * unrecognised `highway` class: no assumption is made, and the way is reported
 * exposed rather than credited with shade the model cannot justify.
 *
 * That is the general rule here — **never claim shade that cannot be
 * justified** — and it is why every failure mode in this file errs toward
 * saying a walk is hotter than it might be.
 *
 * ## Provenance
 *
 * Every result carries [Provenance.MODELLED], and [RouteShade.shadeLabel]
 * refuses to render a bare percentage. An estimate is never presented as an
 * observation.
 */
object ShadeEstimator {

    private const val DEG = Math.PI / 180.0

    /**
     * Below this altitude the sun is up but the model is not worth running:
     * `height / tan(altitude)` exceeds a kilometre under 0.5 deg, and at this
     * grazing angle the answer is dominated by whatever is on the horizon
     * rather than by the street. Reported as [SunState.LOW_SUN].
     */
    const val LOW_SUN_DEG = 3.0

    /**
     * How far off the street's normal the sun may be before the facade stops
     * mattering. From the V7 model; beyond it the sun is looking along the
     * street rather than across it.
     */
    const val NORMAL_GATE_DEG = 75.0

    /** Shorter than this and the bearing between the two points is noise. */
    const val MIN_SEGMENT_M = 1.0

    /** A difference smaller than this is not worth offering as a cooler route. */
    const val MEANINGFUL_DELTA_POINTS = 5.0

    // Confidence is the model's opinion of itself, not of the sun.
    private const val CONF_NIGHT = 0.95      // astronomy, not assumption
    private const val CONF_ENCLOSED = 0.90   // an OSM tag said so
    private const val CONF_STREET = 0.55     // the facade assumption
    private const val CONF_LOW_SUN = 0.30
    private const val CONF_OPEN = 0.15       // plaza / car park: model declines
    private const val CONF_NONE = 0.0

    /**
     * Assumed facade height and half-carriageway width, in metres, by OSM
     * `highway` class. Heights are the V7 model's; widths are the matching
     * assumption about how far the shadow has to reach.
     *
     * `primary`/`trunk`/`motorway` carry height 0 deliberately: they are too
     * wide for a facade to shade the footway, so they are always exposed.
     */
    private val CLASSES: Map<String, Pair<Double, Double>> = mapOf(
        // pedestrian ways: low buildings, narrow way, shadow reaches easily
        "footway" to (6.0 to 2.0),
        "path" to (6.0 to 2.0),
        "steps" to (6.0 to 2.0),
        "pedestrian" to (6.0 to 2.0),
        "corridor" to (6.0 to 2.0),
        "cycleway" to (6.0 to 2.0),
        "living_street" to (9.0 to 5.0),
        // ordinary streets
        "residential" to (9.0 to 5.0),
        "service" to (9.0 to 5.0),
        "unclassified" to (9.0 to 5.0),
        "track" to (9.0 to 5.0),
        // wider distributor roads, taller frontage
        "secondary" to (15.0 to 8.0),
        "secondary_link" to (15.0 to 8.0),
        "tertiary" to (15.0 to 8.0),
        "tertiary_link" to (15.0 to 8.0),
        // too wide to be shaded by anything beside them
        "primary" to (0.0 to 12.0),
        "primary_link" to (0.0 to 12.0),
        "trunk" to (0.0 to 12.0),
        "trunk_link" to (0.0 to 12.0),
        "motorway" to (0.0 to 12.0),
        "motorway_link" to (0.0 to 12.0),
    )

    /** Is this way enclosed, and therefore shaded whatever the sun is doing? */
    private fun enclosed(s: WalkSegment): Boolean =
        s.covered || s.tunnel || s.indoor ||
            (s.highway?.trim()?.lowercase() == "corridor")

    /**
     * Modelled exposure of one segment at one instant.
     *
     * Deterministic: the same segment and the same `epochMs` always produce the
     * same number, on any machine, with no clock read and no I/O.
     */
    fun segment(s: WalkSegment, epochMs: Long): SegmentExposure {
        val lengthM = RouteGeometry.haversineM(s.from.lng, s.from.lat, s.to.lng, s.to.lat)

        // Degenerate geometry: two coincident points have no bearing, and
        // inventing one would be fabrication. Make no claim.
        if (lengthM < MIN_SEGMENT_M) {
            return SegmentExposure(
                exposure = 1.0, confidence = CONF_NONE, lengthM = lengthM,
                sunState = SunState.UNKNOWN, solar = null, bearingDeg = null,
                basis = ShadeBasis.DEGENERATE,
            )
        }

        val mid = LngLat((s.from.lng + s.to.lng) / 2.0, (s.from.lat + s.to.lat) / 2.0)
        val solar = SolarPosition.at(mid.lat, mid.lng, epochMs)
        val bearing = RouteGeometry.bearingDeg(s.from, s.to)

        // Enclosed beats everything, including night: a tunnel is shaded at
        // noon and still a tunnel at midnight.
        if (enclosed(s)) {
            return SegmentExposure(
                0.0, CONF_ENCLOSED, lengthM, stateOf(solar), solar, bearing,
                ShadeBasis.ENCLOSED,
            )
        }

        // No sun to be exposed to.
        if (!solar.isDaylight) {
            return SegmentExposure(
                0.0, CONF_NIGHT, lengthM, SunState.NIGHT, solar, bearing,
                ShadeBasis.NIGHT,
            )
        }
        if (solar.altitudeDeg < LOW_SUN_DEG) {
            return SegmentExposure(
                0.0, CONF_LOW_SUN, lengthM, SunState.LOW_SUN, solar, bearing,
                ShadeBasis.LOW_SUN,
            )
        }

        // An open area has no facade to reason about. Decline, do not guess.
        if (s.area) {
            return SegmentExposure(
                1.0, CONF_OPEN, lengthM, SunState.DAY, solar, bearing,
                ShadeBasis.DECLINED_AREA,
            )
        }

        val cls = CLASSES[s.highway?.trim()?.lowercase()]
            ?: return SegmentExposure(
                1.0, CONF_OPEN, lengthM, SunState.DAY, solar, bearing,
                ShadeBasis.DECLINED_CLASS,
            )
        val (height, halfWidth) = cls
        if (height <= 0.0) {
            return SegmentExposure(
                1.0, CONF_STREET, lengthM, SunState.DAY, solar, bearing,
                ShadeBasis.FACADE,
            )
        }

        // How far off side-on is the sun? The street has two normals; the sun
        // faces one of them.
        val offNormal = min(
            abs(RouteGeometry.angDiffDeg(solar.azimuthDeg, bearing + 90.0)),
            abs(RouteGeometry.angDiffDeg(solar.azimuthDeg, bearing - 90.0)),
        )
        if (offNormal > NORMAL_GATE_DEG) {
            return SegmentExposure(
                1.0, CONF_STREET, lengthM, SunState.DAY, solar, bearing,
                ShadeBasis.FACADE,
            )
        }

        val shadowWidth = height / tan(solar.altitudeDeg * DEG)
        val across = shadowWidth * cos(offNormal * DEG)
        val shaded = (across / halfWidth).coerceIn(0.0, 1.0)

        return SegmentExposure(
            1.0 - shaded, CONF_STREET, lengthM, SunState.DAY, solar, bearing,
            ShadeBasis.FACADE,
        )
    }

    private fun stateOf(solar: SolarPos): SunState = when {
        !solar.isDaylight -> SunState.NIGHT
        solar.altitudeDeg < LOW_SUN_DEG -> SunState.LOW_SUN
        else -> SunState.DAY
    }

    /**
     * Modelled exposure of a whole walk, length-weighted.
     *
     * Length weighting rather than a plain mean because a 200 m exposed stretch
     * and a 5 m shaded one are not two equal votes; what matters is how much of
     * the walk is in the sun.
     */
    fun route(segments: List<WalkSegment>, epochMs: Long): RouteShade {
        val scored = segments.map { segment(it, epochMs) }
        val usable = scored.filter { it.lengthM >= MIN_SEGMENT_M }
        val totalM = usable.sumOf { it.lengthM }
        if (totalM <= 0.0) {
            return RouteShade(1.0, 0.0, 0.0, SunState.UNKNOWN, scored)
        }
        val exposure = usable.sumOf { it.exposure * it.lengthM } / totalM
        val confidence = usable.sumOf { it.confidence * it.lengthM } / totalM
        val state = when {
            usable.any { it.sunState == SunState.DAY } -> SunState.DAY
            usable.any { it.sunState == SunState.LOW_SUN } -> SunState.LOW_SUN
            usable.any { it.sunState == SunState.NIGHT } -> SunState.NIGHT
            else -> SunState.UNKNOWN
        }
        return RouteShade(exposure, confidence, totalM, state, scored)
    }

    /** Convenience for a raw polyline where every segment shares the same tags. */
    fun route(
        coords: List<LngLat>,
        epochMs: Long,
        highway: String? = null,
        covered: Boolean = false,
        area: Boolean = false,
    ): RouteShade = route(
        coords.zipWithNext { a, b ->
            WalkSegment(a, b, highway = highway, covered = covered, area = area)
        },
        epochMs,
    )
}

/** The modelled sun exposure of a whole walking route. */
data class RouteShade(
    /** Length-weighted mean exposure, 0 = fully shaded, 1 = fully in the sun. */
    val exposure: Double,
    val confidence: Double,
    val lengthM: Double,
    val sunState: SunState,
    val segments: List<SegmentExposure>,
) {
    val provenance: Provenance get() = Provenance.MODELLED

    /** The complement of [exposure]: how much of the walk is out of the sun. */
    val shadeScore: Double get() = 1.0 - exposure

    /** Metres of the walk the model puts in shade. */
    val shadedM: Double get() = lengthM * shadeScore

    /** Metres of the walk the model puts in direct sun. */
    val exposedM: Double get() = lengthM * exposure

    /** This route's exposure as a journey [Annotation], provenance included. */
    fun annotation(): Annotation = Annotation(
        kind = AnnotationKind.EXPOSURE,
        magnitude = exposure,
        confidence = confidence,
        provenance = provenance,
    )

    /**
     * The only sanctioned way to put this number in front of a person.
     *
     * Always hedged, because it is always an estimate: "~65 % estimated shade",
     * never "65 % shade". A UI that wants a bare percentage has to reach past
     * this function to get one, which is the point — the hedge is not left to
     * whoever writes the copy.
     */
    fun shadeLabel(): String = when (sunState) {
        SunState.NIGHT -> "no direct sun"
        SunState.UNKNOWN -> "shade not estimated"
        SunState.LOW_SUN -> "low sun, shade not estimated"
        SunState.DAY -> "~${(shadeScore * 100).roundToInt()} % estimated shade"
    }

    /**
     * Compare two candidate walks.
     *
     * Returns which is cooler and by how many points of exposure, and whether
     * the difference is worth acting on — a route two points cooler is noise
     * from a facade assumption, not a reason to walk a different way.
     */
    fun comparedTo(other: RouteShade): ShadeComparison {
        val deltaPoints = (other.exposure - exposure) * 100.0
        val meaningful = abs(deltaPoints) >= ShadeEstimator.MEANINGFUL_DELTA_POINTS &&
            sunState == SunState.DAY && other.sunState == SunState.DAY
        return ShadeComparison(
            cooler = if (!meaningful) null else if (deltaPoints > 0) this else other,
            deltaPoints = deltaPoints,
            meaningful = meaningful,
        )
    }
}

/**
 * The result of comparing two walks.
 *
 * [cooler] is null when neither is meaningfully cooler, which is the case a
 * "cooler route" affordance must not be offered in.
 */
data class ShadeComparison(
    val cooler: RouteShade?,
    /** Positive when the receiver of [RouteShade.comparedTo] is the cooler one. */
    val deltaPoints: Double,
    val meaningful: Boolean,
)
