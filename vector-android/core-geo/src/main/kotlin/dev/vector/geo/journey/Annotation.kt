package dev.vector.geo.journey

/**
 * Where a piece of intelligence came from.
 *
 * This is the load-bearing field of the whole journey architecture, and the
 * invariant attached to it is deliberately blunt:
 *
 * > **The UI may not render an annotation without rendering its provenance.**
 *
 * That is not decoration. It is the same rule the traffic engine already
 * follows by withholding low-evidence segments, that `etaVerdict` follows with
 * its honest margin, and that the arrival card follows by saying "no parking
 * found" rather than guessing. Making it a field on a data class turns a matter
 * of team discipline into something a compiler and a test can check.
 *
 * It is also the seam that stops each new intelligence source from becoming a
 * new subsystem: intersection delay arrives later as another value here, not as
 * another architecture.
 */
enum class Provenance {
    /** Computed from a published model and public geometry. Never observed. */
    MODELLED,

    /** Read directly from OpenStreetMap tags. */
    OSM,

    /** Derived from aggregated observations across many users. */
    OBSERVED_AGGREGATE,

    /** Reported by a person. */
    COMMUNITY,
}

/** What an [Annotation] is about. */
enum class AnnotationKind {
    /** Direct-sun exposure on a walking leg, in [0,1]. */
    EXPOSURE,
    SIGNAL_DELAY,
    WALK_DISTANCE,
    COST,
}

/**
 * One scored fact about a leg of a journey.
 *
 * [confidence] is the model's own opinion of itself, in [0,1]. It is separate
 * from [magnitude] on purpose: a street-orientation shade estimate on a narrow
 * residential street and the same estimate across an open car park can produce
 * the same number while deserving very different trust, and collapsing the two
 * into one figure is how "estimated" quietly becomes "measured".
 */
data class Annotation(
    val kind: AnnotationKind,
    val magnitude: Double,
    val confidence: Double,
    val provenance: Provenance,
)
