package dev.vector.geo.signal

/**
 * What the model knows about one signal's timing, or the honest absence of it.
 *
 * ## The rule this type exists to enforce
 *
 * A signal's LOCATION does not tell us its phase, and a guessed cycle is a
 * fabrication. Every phase the UI may ever show must trace back to a
 * [Known] model somebody actually supplied — and on today's data there is
 * none, so the shipped answer for every signal in Qatar is [None] -> phase
 * `UNKNOWN`. This sealed type makes "no timing" a first-class, exhaustively
 * checked state instead of a null that some caller will forget to handle.
 *
 * The acquisition seam is deliberately OPEN but not implemented: a future
 * observation pipeline (privacy-reviewed, K=5 enough) would produce [Known]
 * instances with a real [observedAt]/[expiresAt]; nothing in this build does.
 */
sealed interface TimingModel {

    /**
     * A fixed-cycle model with an explicit freshness window.
     *
     * @property cycleS the full cycle length, green + red (all-red intervals
     *   are folded into the red part; this is a first-order model and says so).
     * @property greenFraction the fraction of the cycle that is green, in
     *   (0, 1]. >0 required: a cycle with no green is not a cycle.
     * @property offsetS seconds after [observedAt] at which a green phase
     *   starts. The cycle is anchored so that green occupies
     *   `[offsetS, offsetS + greenFraction * cycleS)`.
     * @property observedAt when this timing was measured, epoch ms.
     * @property expiresAt after which this timing is stale and the phase must
     *   not be claimed. No default: a timing without a freshness window is
     *   indistinguishable from a guess, so one is required.
     */
    data class Known(
        val cycleS: Double,
        val greenFraction: Double,
        val offsetS: Double = 0.0,
        val observedAt: Long,
        val expiresAt: Long,
    ) : TimingModel {
        init {
            require(cycleS > 0.0) { "cycle must be positive" }
            require(greenFraction > 0.0 && greenFraction <= 1.0) {
                "greenFraction must be in (0, 1]"
            }
            require(offsetS >= 0.0) { "offset must be non-negative" }
        }

        val greenS: Double get() = cycleS * greenFraction
    }

    /** No timing evidence exists. The phase is UNKNOWN and stays UNKNOWN. */
    data object None : TimingModel
}

/**
 * What a prediction claims the signal is doing at arrival.
 *
 * Deliberately not a boolean: `GREEN`/`RED` are phase claims that require
 * timing evidence, `UNKNOWN` is the honest answer when there is none, and
 * `BOUNDARY` is the explicit "the arrival window straddles a phase
 * transition" case — the model's way of saying it cannot pick a colour
 * rather than quietly picking the nearer one.
 */
enum class Phase { GREEN, RED, UNKNOWN, BOUNDARY }

/**
 * WHY the prediction is what it is. The UI renders a phase claim only when
 * the basis is [Basis.TIMING]; anything else renders the location fact.
 */
enum class Basis {
    /** Only the signal's location is known (OSM). Phase is UNKNOWN. */
    LOCATION,

    /** A valid, unexpired supplied timing model produced the phase. */
    TIMING,

    /** Timing existed at some point but is stale. Phase is UNKNOWN. */
    STALE,
}

/**
 * One signal's phase at arrival, plus how much the model believes it.
 *
 * ## The invariant
 *
 * `phase` is GREEN/RED **only** when [basis] is TIMING. LOCATION and STALE
 * always carry UNKNOWN with confidence 0.0 — a prediction that claims a
 * colour without timing evidence is the exact fabrication this stage exists
 * to refuse, and [dev.vector.geo.signal.SignalTimingTest] pins it three ways.
 */
data class SignalPrediction(
    val signalId: String,
    val phase: Phase,
    /** Belief in the claimed phase, in [0,1]. UNKNOWN is always 0.0. */
    val confidence: Double,
    val basis: Basis,
)

/**
 * The phase prediction, as a pure function of the timing model, the arrival
 * instant and the arrival window — and of nothing else. No clock, no network,
 * no device: [predict] is deterministic for given inputs, which is what makes
 * every fixture below testable and every run reproducible.
 */
object SignalTiming {

    /**
     * Predict the phase at [arrivalAtMs] given the arrival window half-width
     * [windowS]. [signalId] is carried through so callers get a complete
     * prediction without stitching the id on afterwards.
     *
     * Rules, in order:
     *
     *  1. [TimingModel.None] -> UNKNOWN / LOCATION / 0.0 — no timing, no claim.
     *  2. expired timing (`nowMs > expiresAt`) -> UNKNOWN / STALE / 0.0 —
     *     stale timing is as good as none, and the freshness window is what
     *     makes the difference expressible.
     *  3. the arrival window `[arrivalAtMs - windowS, arrivalAtMs + windowS]`
     *     overlaps a phase transition -> BOUNDARY. A driver whose arrival
     *     could land on either side of a change is not helped by being told
     *     the more likely colour; the answer is "uncertain".
     *  4. otherwise GREEN or RED, with a confidence that grows with distance
     *     from the nearest transition.
     *
     * A malformed-but-Known model (impossible via the constructor's
     * `require`s) would hit rule 1 and also say UNKNOWN.
     */
    fun predict(
        signalId: String,
        timing: TimingModel,
        arrivalAtMs: Long,
        windowS: Double,
        nowMs: Long,
    ): SignalPrediction {
        if (timing is TimingModel.None) {
            return SignalPrediction(signalId, Phase.UNKNOWN, 0.0, Basis.LOCATION)
        }
        val model = timing as TimingModel.Known
        if (nowMs > model.expiresAt) {
            return SignalPrediction(signalId, Phase.UNKNOWN, 0.0, Basis.STALE)
        }
        val w = if (windowS <= 0.0) 1e-6 else windowS
        val cycleS = model.cycleS
        val t = positiveModulo(
            (arrivalAtMs - model.observedAt) / 1000.0 + model.offsetS,
            cycleS,
        )
        // Transitions are at t == 0 (== cycleS) and t == greenS. Distance to
        // the nearest one is the smaller of the distances to either boundary
        // (the green/red boundary at greenS, and the cycle seam at 0/cycleS).
        val d = minOf(minOf(t, cycleS - t), kotlin.math.abs(t - model.greenS))
        if (d <= w) {
            // The window reaches a transition: BOUNDARY, with a confidence
            // that records how much of the window cleared the boundary.
            return SignalPrediction(
                signalId, Phase.BOUNDARY, (d / w).coerceIn(0.0, 1.0), Basis.TIMING,
            )
        }
        val phase = if (t < model.greenS) Phase.GREEN else Phase.RED
        // d > w here by construction; confidence reaches 1.0 when the phase
        // is at least two windows wide around t.
        val confidence = (d / (2.0 * w)).coerceAtMost(1.0)
        return SignalPrediction(signalId, phase, confidence, Basis.TIMING)
    }

    private fun positiveModulo(v: Double, mod: Double): Double {
        val r = v % mod
        return if (r < 0.0) r + mod else r
    }
}