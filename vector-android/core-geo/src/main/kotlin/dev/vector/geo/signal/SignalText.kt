package dev.vector.geo.signal

/**
 * The exact words the driver may be shown about a signal.
 *
 * ## Why the strings live here and not in the UI
 *
 * One rule decides them — a phase claim requires actual timing provenance —
 * and that rule must be the same wherever a signal is rendered: the map
 * callout, the trip bar, and (later) any other surface. Putting the strings
 * beside the model keeps "no fabricated phase" a property of the model
 * rather than a discipline each composable re-implements.
 *
 * ## What is deliberately absent
 *
 * No countdown, ever. "Green in 8 s" is the shape a model with real timing
 * could arguably produce, but it is also the shape that reads as measurement
 * rather than estimate, and the project's answer is that confidence and
 * uncertainty belong in the copy or not at all. There is no string in this
 * file containing a number of seconds, and [SignalTextTest] walks the whole
 * vocabulary to say so.
 */
object SignalText {

    /**
     * The location fact. Allowed with today's data — the signal EXISTS, which
     * is what OSM evidence actually supports.
     */
    const val LOCATION = "Signal ahead"

    /**
     * What to say about the phase, or null when there is nothing honest to say.
     *
     * Null for [Basis.LOCATION] (no timing) and [Basis.STALE] (timing expired):
     * the caller then shows the location fact or nothing, never a colour.
     * Non-null only for [Basis.TIMING] — a valid, unexpired model produced the
     * phase, which is the single condition under which a colour may be named.
     */
    fun phaseText(prediction: SignalPrediction): String? = when {
        prediction.basis != Basis.TIMING -> null
        prediction.phase == Phase.GREEN -> "Likely green"
        prediction.phase == Phase.RED -> "Likely red"
        prediction.phase == Phase.BOUNDARY -> "Timing uncertain"
        // A TIMING-basis UNKNOWN is not produced by the model, but if a future
        // timing source ever yields one, the answer is to say nothing rather
        // than to guess a colour.
        else -> null
    }
}