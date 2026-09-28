package dev.vector.geo

/**
 * Measures how smoothly Vector is actually drawing.
 *
 * ## Why the app has to measure itself
 *
 * ADR-0075 justified replacing the web client with a native one on the premise
 * that MapLibre Native would render the drive at the panel's refresh rate. That
 * premise has never been checked, and it is release blocker 2 in the V3
 * handover, for a specific and annoying reason:
 *
 * > `dumpsys gfxinfo` cannot see a `SurfaceView`.
 *
 * `gfxinfo` reports the frames the **Android view hierarchy** drew. MapLibre
 * renders onto its own `SurfaceView`, which is composited by SurfaceFlinger
 * outside that hierarchy — so `gfxinfo` faithfully reports the timings of
 * Vector's Compose overlays and says nothing whatsoever about the map. A green
 * jank figure from it is not evidence of a smooth drive; it is evidence that
 * some text was cheap to lay out.
 *
 * `dumpsys SurfaceFlinger --latency` can see the layer, and
 * `scripts/verify_on_device.sh` reads it. But it is awkward: the layer name
 * changes between Android versions, the buffer holds only the last 127 frames,
 * and on a phone it is a snapshot of whatever happened to be on screen.
 *
 * So Vector measures itself. The client already advances the vehicle on a
 * `Choreographer.FrameCallback` — `MainActivity.startFrameLoop` — which fires
 * once per **display** frame with the frame's presentation timestamp. That is
 * exactly the signal needed, it is already there, and it costs an `add` per
 * frame to record.
 *
 * ## What it deliberately does not claim
 *
 * This measures **the frame callback's cadence**, which is the rate the display
 * is presenting and the rate at which Vector gets to move the vehicle. It does
 * NOT prove the map's GL work finished inside the budget: MapLibre can drop its
 * own render while the Choreographer keeps ticking. The two together — this,
 * plus SurfaceFlinger's per-layer timings — are what an honest claim needs, and
 * §29's instruction is not to say "120 Hz" without evidence.
 *
 * Pure and JVM-tested. No Android types: the Activity hands it nanosecond
 * timestamps.
 */
class FrameMeter(
    /**
     * How many intervals to keep.
     *
     * 600 is ten seconds at 60 Hz and five at 120 Hz — long enough for a
     * percentile to mean something and short enough that a report describes the
     * road the driver is on rather than the whole journey.
     */
    private val capacity: Int = 600,
) {
    private val intervalsNs = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var lastFrameNs = 0L

    /** Frames seen since the last [reset], including ones outside the window. */
    var framesSeen: Long = 0
        private set

    /**
     * Record a frame.
     *
     * @param frameTimeNanos the Choreographer's frame time.
     *
     * The first frame after a [reset] establishes a baseline and is not an
     * interval. A gap longer than [MAX_PLAUSIBLE_GAP_NS] is **discarded rather
     * than recorded as a stutter**: the app was paused, the screen was off, or
     * the process was frozen, and folding a four-second gap into a percentile
     * would report the phone being in a pocket as dropped frames.
     */
    fun onFrame(frameTimeNanos: Long) {
        val prev = lastFrameNs
        lastFrameNs = frameTimeNanos
        if (prev == 0L) return
        val dt = frameTimeNanos - prev
        if (dt <= 0L || dt > MAX_PLAUSIBLE_GAP_NS) return
        framesSeen++
        intervalsNs[next] = dt
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun reset() {
        count = 0
        next = 0
        lastFrameNs = 0L
        framesSeen = 0
    }

    /** How many intervals the window currently holds. */
    val samples: Int get() = count

    /**
     * A snapshot, or null when there is not enough to say anything.
     *
     * Null below [MIN_SAMPLES] on purpose. A percentile over eleven frames is
     * a number with no meaning attached, and printing one would be the
     * unevidenced performance claim this class exists to avoid.
     */
    fun report(): Report? {
        if (count < MIN_SAMPLES) return null
        val sorted = intervalsNs.copyOf(count).also { it.sort() }
        fun pct(p: Double): Double {
            val i = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[i] / 1e6
        }
        val medianMs = pct(0.50)
        // The refresh period is INFERRED from the median rather than read from
        // the display, because the display's advertised mode and what a
        // SurfaceView actually gets are not the same thing on a variable
        // refresh-rate panel — the S24 drops to 60 Hz on its own when it feels
        // like it, and a budget derived from "the panel says 120" would then
        // report every frame as late.
        val hz = if (medianMs > 0) 1000.0 / medianMs else 0.0
        // A frame is late if it took more than 1.5x the observed median. A
        // multiple, not a constant: 8.3 ms is on time at 120 Hz and early at
        // 60, and a fixed threshold would grade the same smoothness
        // differently on two phones.
        val lateBudgetMs = medianMs * 1.5
        var late = 0
        var worstMs = 0.0
        for (v in sorted) {
            val ms = v / 1e6
            if (ms > lateBudgetMs) late++
            if (ms > worstMs) worstMs = ms
        }
        return Report(
            samples = count,
            medianMs = medianMs,
            p95Ms = pct(0.95),
            p99Ms = pct(0.99),
            worstMs = worstMs,
            observedHz = hz,
            lateFrames = late,
            latePercent = 100.0 * late / count,
        )
    }

    data class Report(
        val samples: Int,
        val medianMs: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val worstMs: Double,
        /** Frames per second implied by the median interval. */
        val observedHz: Double,
        val lateFrames: Int,
        val latePercent: Double,
    ) {
        /**
         * One line, for logcat, in a shape a script can parse.
         *
         * Read by `scripts/verify_on_device.sh`, which is why the field order
         * and separators are stable rather than pretty.
         */
        fun oneLine(): String = String.format(
            "frames=%d hz=%.1f p50=%.2fms p95=%.2fms p99=%.2fms worst=%.2fms late=%d(%.1f%%)",
            samples, observedHz, medianMs, p95Ms, p99Ms, worstMs, lateFrames, latePercent,
        )
    }

    companion object {
        /**
         * Longer than this is not a dropped frame, it is an absence.
         *
         * 250 ms. At 120 Hz a genuinely terrible frame is a few tens of
         * milliseconds; a quarter of a second means the app was not being
         * asked to draw at all.
         */
        const val MAX_PLAUSIBLE_GAP_NS = 250_000_000L

        /** Below this a percentile is not worth printing. */
        const val MIN_SAMPLES = 60
    }
}
