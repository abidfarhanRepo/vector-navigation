package dev.vector.geo.walk

import dev.vector.geo.RouteGeometry
import dev.vector.geo.sun.SegmentExposure
import dev.vector.geo.sun.ShadeBasis
import dev.vector.geo.sun.ShadeEstimator
import dev.vector.geo.sun.WalkSegment

/**
 * How much of a walk the sun model puts in shade, as a category (V7.4 shade).
 *
 * ## Why a category and not a percentage
 *
 * [dev.vector.geo.sun.RouteShade.shadeLabel] renders "~65 % estimated shade",
 * and on the journey card that is right: the number moves as the sun slider
 * moves, and watching it move is the entire point of that surface.
 *
 * This surface answers a different question — *is this walk going to be hot?* —
 * and the model behind both is street orientation plus an ASSUMED facade
 * height. A 65 % and a 71 % are not two findings; they are the same finding
 * with two different guesses about the buildings. Quoting one on the screen a
 * person reads while walking would be manufacturing precision out of an
 * assumption, which is the thing this stage exists not to do.
 *
 * So the route-level claim is categorical, the quantitative facts stay
 * underneath it on [WalkShade] where they can be read and tested, and
 * [WalkShade.stripLine] is the one sanctioned way to put either in front of a
 * person.
 */
enum class WalkShadeBand(val label: String) {
    /** The model puts most of the modelled walk in shade. */
    MOSTLY_SHADED("Mostly shaded"),

    /** Roughly a balance of shade and sun. */
    MIXED_SHADE("Mixed shade"),

    /** The model puts most of the modelled walk in direct sun. */
    MOSTLY_EXPOSED("Mostly exposed"),
}

/**
 * What the sun model says about one walk, over the walk's OWN timeline
 * (V7.4 shade).
 *
 * ## What is new here, and what is not
 *
 * Not new: the per-segment calculation, which is [ShadeEstimator] unchanged —
 * real solar position from [dev.vector.geo.sun.SolarPosition], real segment
 * bearings, and the one assumption this model has ever had, a facade of a
 * height typical for the road class.
 *
 * New, and the whole of this type:
 *
 *  1. **The route is scored along its own timeline.** [ShadeEstimator.route]
 *     scores every segment at ONE instant, which is correct for a preview
 *     ("what if I leave at 16:00") and wrong for a walk: two hours of it are
 *     two hours of sun. Here each segment is scored at the moment the walker
 *     is expected to be standing in the middle of it, from
 *     [startedAtMs] plus the contract's own pace time. That is a real
 *     change in the answer, and on the real 22.4 km fixture it is a large
 *     one — see `.scratch/vector-product/V7.4-SHADE.md`.
 *  2. **Declined questions and darkness are counted as themselves.** The
 *     length-weighted [dev.vector.geo.sun.RouteShade.exposure] folds a plaza
 *     the model refused to model into "exposed" at 1.0, and folds a segment
 *     after sunset into "shaded" at 0.0. Both are the conservative choice for
 *     a preview and both are unusable for a claim: "shaded" because the sun
 *     had set is not shade, and a refusal to guess is not a finding. Every
 *     segment here is bucketed by [ShadeBasis] and by whether the sun was up,
 *     and [coverage] reports how much of the walk the model actually answered
 *     for.
 *
 * [dev.vector.geo.sun.RouteShade] is left exactly as it was, and the journey
 * card, the sun slider and the cooler-route offer still use it, because their
 * question — *when should I leave?* — is the one it answers.
 *
 * ## Determinism
 *
 * A pure function of (segments, [startedAtMs], [durationS]). There is no clock
 * read anywhere in this file and no I/O: the caller supplies the instant, as
 * `WalkNavSession` supplies it to every other walking decision. The same walk
 * and the same instant produce the same answer on every machine, which is what
 * makes a fixture with a fixed timestamp worth anything.
 */
data class WalkShade(
    /** The instant the walk begins, as supplied by the caller. */
    val startedAtMs: Long,

    /** The walk's own pace time, in seconds. Zero when the backend gave none. */
    val durationS: Double,

    /**
     * Length of the walk the model could place, in metres.
     *
     * Sums only segments at least [ShadeEstimator.MIN_SEGMENT_M] long, exactly
     * as [dev.vector.geo.sun.RouteShade.lengthM] does: two coincident points
     * have no bearing and no question to answer.
     */
    val totalM: Double,

    /** Metres walked in daylight for which the model produced a shade answer. */
    val answeredM: Double,

    /** Of [answeredM], metres the model puts in shade. */
    val shadedM: Double,

    /** Of [answeredM], metres the model puts in direct sun. */
    val exposedM: Double,

    /** Metres walked after sunset or before sunrise. */
    val nightM: Double,

    /** Metres walked while the sun is up but below [ShadeEstimator.LOW_SUN_DEG]. */
    val lowSunM: Double,

    /**
     * Metres in daylight the model DECLINED to answer.
     *
     * An open plaza, an unrecognised road class, or a backend that sent no
     * `classes` at all. Not a shade finding in either direction.
     */
    val noEvidenceM: Double,

    /** The category, for the daylit and answered part of the walk. */
    val band: WalkShadeBand,

    /**
     * Every segment's own answer, computed along the walk's timeline.
     *
     * Kept because it is the evidence behind [band] and [stripLine] and the
     * input any map overlay would need. It is NOT a route-level fact and must
     * not be summarised by a caller that does not also carry `coverage` — the
     * mistake this whole type exists to prevent.
     */
    val segments: List<SegmentExposure>,
) {
    /**
     * Fraction of the DAYLIGHT walk the model actually answered for.
     *
     * The denominator excludes [nightM] deliberately. A walk that ends after
     * sunset has no shade question to answer for its last third, and dividing
     * by the whole route would report a model failure where there is only a
     * sun that has gone down. What remains — answered, declined, low sun — is
     * the ground over which a shade claim was available to be made.
     *
     * Zero when there is no daylight at all — "the sun had set", which
     * [stripLine] answers in its own words and which is not a coverage
     * failure.
     */
    val coverage: Double
        get() {
            val daylightM = answeredM + noEvidenceM + lowSunM
            if (daylightM <= 0.0) return 0.0
            return (answeredM / daylightM).coerceIn(0.0, 1.0)
        }

    /**
     * Shade as a fraction of what the model ANSWERED, not of the whole walk.
     *
     * This is the number the band is cut from, and it is a different number
     * from [dev.vector.geo.sun.RouteShade.shadeScore] on purpose — see the
     * class KDoc. Zero when nothing was answered.
     */
    val modelledShadeFraction: Double
        get() = if (answeredM <= 0.0) 0.0 else (shadedM / answeredM).coerceIn(0.0, 1.0)

    /**
     * The one sanctioned sentence for this fact, or null when there is nothing
     * to say at all.
     *
     * ## Why the hedge travels inside this function
     *
     * The same argument [dev.vector.geo.sun.RouteShade.shadeLabel] makes: the
     * claim and its qualifier are produced together, so a UI cannot print the
     * claim and forget the qualifier. "(estimated)" is attached to the three
     * band strings — the ones that assert something about the sun — and the
     * four states that have nothing to assert say so in their own words.
     *
     * Null is reserved for a route with no usable geometry, where there is no
     * walk to describe. **Every other state produces a sentence**, including
     * "Limited shade data": an absent estimate is information, and rendering
     * nothing would leave a walker unable to tell "no shade here" from "Vector
     * does not know".
     *
     * ## The vocabulary, and where it comes from
     *
     * | state | sentence |
     * |---|---|
     * | the whole walk is after sunset | `No direct sun` |
     * | the whole walk is in low sun | `Low sun; shade not estimated` |
     * | nothing in daylight was modelled | `Shade not estimated` |
     * | modelled, but under [MIN_COVERAGE] of the daylight walk | `Limited shade data` |
     * | modelled | the band, e.g. `Mostly shaded (estimated)` |
     *
     * The three non-band sentences are the same three conditions
     * [dev.vector.geo.sun.RouteShade.shadeLabel] reports, in a sentence-case
     * register, because the detail strip is a standalone line rather than a
     * fragment of a longer one. They are listed here together so the two
     * surfaces cannot acquire different meanings for one condition — the
     * strings differ in case, the conditions do not.
     */
    fun stripLine(): String? {
        if (totalM <= 0.0) return null
        if (answeredM > 0.0 && coverage >= MIN_COVERAGE) return "${band.label} (estimated)"
        if (answeredM > 0.0) return "Limited shade data"
        if (nightM >= totalM) return "No direct sun"
        if (lowSunM >= totalM) return "Low sun; shade not estimated"
        return "Shade not estimated"
    }

    companion object {
        /**
         * Where a segment stops being "exposed" and starts being "shaded".
         *
         * Half, and the same half the map overlay uses: VectorStyle's
         * `WALK_SHADE_SPLIT` now reads this constant rather than repeating the
         * number, so the route-level category and the per-segment colouring
         * cannot come to mean different things by "shaded".
         */
        const val SHADE_SPLIT = 0.5

        /**
         * Fraction of the daylit walk that must be modelled before a category
         * is offered at all.
         *
         * Half. Below it the category would be describing a minority of the
         * ground the walker covers and the sentence would be a claim about a
         * walk the model did not measure — the case `Limited shade data`
         * exists for. It is reachable on real data only when a backend sends
         * no per-segment `classes` (every segment then declines), or when most
         * of a walk is open plaza; on the six committed Qatar fixtures
         * coverage is 1.0.
         */
        const val MIN_COVERAGE = 0.5

        /** Fraction of the modelled walk at or above which it is "mostly shaded". */
        const val MIN_MOSTLY_SHADED = 0.7

        /** Fraction of the modelled walk below which it is "mostly exposed". */
        const val MIN_MIXED = 0.3

        /**
         * The longest walk whose timeline will be honoured, in seconds.
         *
         * 48 hours. A `duration_s` past this is malformed rather than long —
         * the longest real fixture is 5.3 hours — and clamping keeps a
         * nonsense value from walking the clock into another week. Clamping
         * rather than refusing, because the walk itself is still perfectly
         * usable and refusing to describe it would be a worse answer than
         * describing it at its first 48 hours.
         */
        const val MAX_TIMELINE_S = 172_800.0

        /** The category for a fraction of the answered walk. */
        fun bandFor(shadeFraction: Double): WalkShadeBand = when {
            shadeFraction >= MIN_MOSTLY_SHADED -> WalkShadeBand.MOSTLY_SHADED
            shadeFraction >= MIN_MIXED -> WalkShadeBand.MIXED_SHADE
            else -> WalkShadeBand.MOSTLY_EXPOSED
        }
    }
}

/**
 * The walking shade fact, computed from a parsed `/foot` contract (V7.4 shade).
 *
 * The layer stack it terminates:
 *
 * ```
 * OSM tags + geometry -> 4B.4 /foot -> WalkContract -> [this file]
 *   -> WalkRoute.shadeAt(startMs) -> WalkNavState.shade -> the detail strip
 * ```
 *
 * It introduces no new source of data and no new model. Everything it knows
 * comes from the contract's geometry and its per-segment `classes` / `enclosed`
 * / `area` arrays, scored by [ShadeEstimator] — the same computation the
 * journey card has always used.
 */
object WalkShadeFacts {

    /**
     * The walk's shade, over its own timeline from [startMs].
     *
     * [startMs] is the instant the walk begins. The walker reaches the middle
     * of segment `i` at `startMs + durationS * (metres before it / metres in
     * total)`, and that is the instant the sun is evaluated for it — because
     * the sun at 09:00 is not the sun at 13:00, and a 22 km walk spans four
     * hours of it.
     */
    fun of(contract: WalkContract, startMs: Long): WalkShade =
        of(segmentsOf(contract), startMs, contract.durationS ?: 0.0)

    /**
     * The contract's geometry and per-segment tags as [WalkSegment]s.
     *
     * ## The one lossy-looking mapping, and why it is not
     *
     * The wire publishes ONE combined `enclosed` boolean per segment (from
     * `tunnel`, `covered` or `indoor`) where [WalkSegment] has three separate
     * fields. It is folded into `covered` here and the fold is lossless for
     * this model, because [ShadeEstimator]'s own `enclosed()` ORs the three —
     * it has never distinguished them, and it does not need to: everything
     * that reaches it through any of them is an OSM fact about the way being
     * roofed, and it scores them identically.
     *
     * Everything else is a straight read, and every absent array or short
     * array yields null / false for the segments it does not cover — which
     * [ShadeEstimator] treats as "no facade may be assumed" and reports as
     * exposed with low confidence. A walk from a backend that predates these
     * arrays therefore degrades toward claiming less, which is the only
     * direction degradation is allowed to go here.
     */
    fun segmentsOf(contract: WalkContract): List<WalkSegment> {
        val points = contract.geometry
        if (points.size < 2) return emptyList()
        val classes = contract.segments.classes
        val enclosed = contract.segments.enclosed
        val area = contract.segments.area
        return points.zipWithNext().mapIndexed { i, (a, b) ->
            WalkSegment(
                from = a,
                to = b,
                highway = classes.getOrNull(i),
                covered = enclosed.getOrNull(i) == true,
                area = area.getOrNull(i) == true,
            )
        }
    }

    /**
     * The same fact from explicit segments.
     *
     * [durationS] is the walk's pace time and is what turns the route into a
     * timeline. Zero, negative or non-finite means "no timeline is known" and
     * every segment is scored at [startMs] — the single-instant behaviour
     * [ShadeEstimator.route] has, kept as the honest fallback rather than
     * invented from a pace this code does not know.
     */
    fun of(segments: List<WalkSegment>, startMs: Long, durationS: Double): WalkShade {
        val lengths = DoubleArray(segments.size)
        var geometryM = 0.0
        for (i in segments.indices) {
            val s = segments[i]
            lengths[i] = RouteGeometry.haversineM(s.from.lng, s.from.lat, s.to.lng, s.to.lat)
            geometryM += lengths[i]
        }

        val paceS = if (durationS.isFinite() && durationS > 0.0) {
            durationS.coerceAtMost(WalkShade.MAX_TIMELINE_S)
        } else {
            null
        }

        val scored = ArrayList<SegmentExposure>(segments.size)
        var cumulativeM = 0.0
        for (i in segments.indices) {
            val atMs = if (paceS == null || geometryM <= 0.0) startMs else {
                val midM = cumulativeM + lengths[i] / 2.0
                startMs + (midM / geometryM * paceS * 1000.0).toLong()
            }
            cumulativeM += lengths[i]
            scored.add(ShadeEstimator.segment(segments[i], atMs))
        }
        return aggregate(startMs, paceS ?: 0.0, scored)
    }

    /**
     * The length-weighted buckets, and the category they support.
     *
     * ## The order of the classification is the whole of the honesty
     *
     * A segment is put in exactly one bucket, and never in two:
     *
     *  1. **No sun.** The sun is down, or the segment is degenerate. Nothing
     *     is "shaded" here; the sun has set. [ShadeEstimator] reaches this
     *     before it looks at any facade, and the bucket follows it.
     *  2. **Low sun.** The sun is up but under 3 deg, where `height /
     *     tan(altitude)` runs away and the answer would be decided by the
     *     horizon rather than by the street. Modelled as low sun, not as
     *     shade.
     *  3. **Answered.** The model produced a shade finding: a facade that
     *     reaches across the way, a way too wide for one to, or an OSM tag
     *     saying the way is enclosed. Split at [WalkShade.SHADE_SPLIT].
     *  4. **Declined.** Open areas and unknown road classes. Reported full
     *     exposure at the model's lowest confidence, and deliberately counted
     *     HERE rather than as shade or sun, because a refusal is not a
     *     finding in either direction.
     *
     * The one ordering choice worth stating: an ENCLOSED way at night falls in
     * the night bucket, not the answered one. The tag fact is real, but the
     * route-level question is about the sun, and "Mostly shaded" is the wrong
     * sentence for a walk taken after sunset however many of its segments are
     * roofed. The tag still governs that segment's own exposure, which is what
     * the map overlay and [SegmentExposure] report.
     */
    private fun aggregate(
        startedAtMs: Long,
        durationS: Double,
        exposures: List<SegmentExposure>,
    ): WalkShade {
        var totalM = 0.0
        var answeredM = 0.0
        var shadedM = 0.0
        var nightM = 0.0
        var lowSunM = 0.0
        var noEvidenceM = 0.0

        for (e in exposures) {
            val lengthM = e.lengthM
            // NaN-safe and degenerate-safe: `!(x >= 1.0)` is false for NaN,
            // for a negative length and for a coincident pair alike.
            if (!(lengthM >= ShadeEstimator.MIN_SEGMENT_M)) continue
            totalM += lengthM
            val solar = e.solar
            when {
                solar == null || !solar.isDaylight -> nightM += lengthM
                solar.altitudeDeg < ShadeEstimator.LOW_SUN_DEG -> lowSunM += lengthM
                e.basis.answersShade -> {
                    answeredM += lengthM
                    if (e.exposure < WalkShade.SHADE_SPLIT) shadedM += lengthM
                }
                else -> noEvidenceM += lengthM
            }
        }

        val exposedM = (answeredM - shadedM).coerceAtLeast(0.0)
        val fraction = if (answeredM <= 0.0) 0.0 else shadedM / answeredM
        return WalkShade(
            startedAtMs = startedAtMs,
            durationS = durationS,
            totalM = totalM,
            answeredM = answeredM,
            shadedM = shadedM,
            exposedM = exposedM,
            nightM = nightM,
            lowSunM = lowSunM,
            noEvidenceM = noEvidenceM,
            band = WalkShade.bandFor(fraction),
            segments = exposures,
        )
    }
}
