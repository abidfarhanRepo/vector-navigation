package dev.vector.geo

import kotlin.math.abs

/**
 * Decides which GPS fixes are worth uploading as traffic probes, and when.
 *
 * This is the "streamlined collection" half of the traffic loop. The web client
 * needed the driver to visit a separate `/collect` page and press record, which
 * is why the learned-speed layer has consumed **zero real trips**: contributing
 * was a separate activity from driving. Here it rides along with navigation —
 * the driver has already said where they are going, so the probe stream is a
 * by-product rather than a chore.
 *
 * Pure logic on purpose. Batching, thinning, trip splitting and back-pressure
 * are all decidable without a network or a device, and every one of them is a
 * way to silently lose or duplicate data if it goes wrong.
 *
 * ## What this deliberately does NOT do
 *
 * **It does not implement privacy.** The 200 m endpoint truncation and the k=5
 * promotion floor are server-side invariants (adr-0065 / adr-0066) and must stay
 * there: a client that truncated its own trace would be a client that could
 * choose not to. This buffer therefore uploads what it saw, and the server
 * remains the only thing that decides what persists. ADR-0075's compliance
 * section forbids a client path that bypasses those gates.
 */
class ProbeBuffer(
    /** Upload once this many points are held. */
    private val batchSize: Int = 30,
    /** ...or once this long has passed, whichever comes first. */
    private val maxBatchAgeMs: Long = 30_000,
    /** Drop fixes closer together than this: 1 Hz at 5 km/h is noise. */
    private val minSpacingM: Double = 8.0,
    /** Reject fixes worse than this; a 300 m fix tells the map-matcher nothing. */
    private val maxAccuracyM: Double = 50.0,
    /** A gap longer than this ends the trip and starts a new one. */
    private val tripGapMs: Long = 5 * 60_000,
    /** Hard cap on retained points, so a dead network cannot exhaust memory. */
    private val maxRetained: Int = 2_000,
) {
    data class Probe(
        val lng: Double,
        val lat: Double,
        val ts: Long,
        val accuracyM: Double?,
        val speedMs: Double?,
        val bearingDeg: Double?,
    )

    data class Batch(
        val trip: String,
        val points: List<Probe>,
        /** True when this batch closes the trip, so the server can release its
         *  pending tail instead of waiting out the idle timeout. */
        val end: Boolean,
    )

    private val pending = ArrayList<Probe>()
    private var lastAccepted: Probe? = null
    private var batchStartedMs: Long = 0
    private var tripToken: String = newTrip()
    private var dropped = 0

    /** Points discarded for accuracy, spacing, or overflow. Diagnostics only. */
    val droppedCount: Int get() = dropped
    val pendingCount: Int get() = pending.size
    val currentTrip: String get() = tripToken

    /**
     * Offer a fix.
     *
     * @return a batch to upload, or null. Returning the batch rather than
     *   uploading it keeps this class free of I/O and therefore testable.
     */
    fun offer(p: Probe): Batch? {
        // A long gap means the previous journey ended. Flush it as a CLOSED trip
        // before starting a new one, or the two get stitched together and the
        // map-matcher sees a vehicle teleport across the city.
        val last = lastAccepted
        var closing: Batch? = null
        if (last != null && p.ts - last.ts > tripGapMs) {
            closing = flush(end = true)
            tripToken = newTrip()
        }

        if (!accept(p)) {
            dropped++
            return closing
        }

        if (pending.isEmpty()) batchStartedMs = p.ts
        pending.add(p)
        lastAccepted = p

        // Overflow: drop the OLDEST, because for traffic the freshest data is
        // the useful data and a stale queue helps nobody.
        while (pending.size > maxRetained) {
            pending.removeAt(0)
            dropped++
        }

        val full = pending.size >= batchSize
        val old = p.ts - batchStartedMs >= maxBatchAgeMs
        if (full || old) {
            val batch = flush(end = false)
            // A trip-closing batch and a size-triggered batch can both be ready
            // on the same fix; the caller must receive both, so prefer the
            // closing one and let the next call deliver the rest.
            return closing ?: batch
        }
        return closing
    }

    private fun accept(p: Probe): Boolean {
        val acc = p.accuracyM
        if (acc != null && acc > maxAccuracyM) return false
        val last = lastAccepted ?: return true
        // Never accept a fix that goes backwards in time: a receiver can emit
        // one after a cold start, and it would invert the trace.
        if (p.ts <= last.ts) return false
        val moved = RouteGeometry.haversineM(last.lng, last.lat, p.lng, p.lat)
        return moved >= minSpacingM
    }

    /** Hand over what is held, if anything. */
    fun flush(end: Boolean): Batch? {
        if (pending.isEmpty()) {
            // An `end` with nothing pending still has to close the trip on the
            // server if points were sent earlier in it.
            return null
        }
        val batch = Batch(tripToken, ArrayList(pending), end)
        pending.clear()
        return batch
    }

    /** End the journey: flush whatever is left and rotate the pseudonym. */
    fun endTrip(): Batch? {
        val b = flush(end = true)
        tripToken = newTrip()
        lastAccepted = null
        return b
    }

    /**
     * Discard everything without uploading. Used when consent is withdrawn:
     * anything not yet sent must never be sent.
     */
    fun discard() {
        pending.clear()
        lastAccepted = null
        tripToken = newTrip()
    }

    private companion object {
        /**
         * A fresh pseudonym per trip, never per device (adr-0065). A stable
         * device id would make every journey linkable to every other, which is
         * exactly the property the design exists to prevent.
         */
        fun newTrip(): String =
            java.util.UUID.randomUUID().toString().replace("-", "").take(24)
    }
}
