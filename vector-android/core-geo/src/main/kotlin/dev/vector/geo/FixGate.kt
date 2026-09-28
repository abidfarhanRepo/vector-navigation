package dev.vector.geo

/**
 * Decides whether a GPS fix is physically possible, and drops the ones that are
 * not.
 *
 * ## The defect this exists because of
 *
 * Vector accepted every fix it was handed. A reflected signal in an urban
 * canyon routinely puts a fix a few hundred metres away for two or three
 * seconds, and the driving scenario suite reproduced what Vector then did: the
 * 300 m displacement was treated as the vehicle's position, the driver was
 * declared off route, and a new route was requested from the middle of Doha
 * Bay while the car was still on Al Corniche doing 50. That reroute then landed
 * — so the app was following a route from a place the car had never been, was
 * permanently off it, and asked again every eight seconds for the rest of the
 * journey. **One bad fix cost the whole drive: 26 reroutes, and a remaining
 * distance that moved 3 032 m between two samples.**
 *
 * A 300 m step between two 1 Hz fixes implies 1 080 km/h. Nothing on a road
 * does that, and the check is one subtraction.
 *
 * ## Why "reject" is not enough on its own
 *
 * A gate that only ever rejects can lock the app out of its own position
 * forever: if the fix it is comparing against is the wrong one — the *first*
 * bad fix, or a stale one from before a tunnel — then every subsequent good fix
 * looks impossible and none is ever accepted. So two escapes, and both are
 * necessary:
 *
 *  1. **A bounded rejection run.** After [maxRejections] consecutive rejections
 *     the next fix is accepted whatever it says. The receiver gets the benefit
 *     of the doubt, because a receiver that has disagreed with us five times
 *     running is more likely to be right than the reference we are holding.
 *  2. **Time.** The implied speed is computed against the elapsed interval, so
 *     a fix arriving after a 25-second outage is allowed to be 700 m away at
 *     100 km/h. This is what makes the gate compatible with the tunnel case:
 *     an outage does not need a special exemption, it gets one from the
 *     arithmetic.
 *
 * ## Why not the accuracy field
 *
 * Because it lies. A receiver reporting 5 m while delivering a 300 m multipath
 * error is the normal urban case; that is exactly what
 * [DriveSimulator.Fault.Jump] models and why the simulator's claimed accuracy
 * and delivered error are separate parameters. Implied speed is a measurement
 * the app makes for itself out of two things it has, which is the only kind of
 * evidence available here.
 *
 * Pure and JVM-tested. It sits in front of [RouteTracker] rather than inside
 * it, because "is this fix real" and "where am I on the route" are different
 * questions and the first one has to be answered before probe collection, the
 * speed-limit lookup and the camera see the fix at all.
 */
class FixGate(
    /**
     * Fastest a vehicle is allowed to have travelled between two fixes, m/s.
     *
     * 70 m/s is 252 km/h — above anything Qatar's roads permit and above
     * anything a car on them will do, while leaving room for a genuinely fast
     * vehicle plus the error in two fixes. Set from the road network rather
     * than from a car's top speed: the question is not what is possible in
     * principle, it is what is possible *here*.
     */
    private val maxSpeedMs: Double = 70.0,
    /**
     * Consecutive rejections after which the next fix is accepted regardless.
     *
     * Three, at 1 Hz, is three seconds of holding a position the receiver
     * disagrees with — long enough to ride out the multipath bursts that
     * actually occur, short enough that a genuinely relocated device is
     * followed almost immediately.
     */
    private val maxRejections: Int = 3,
    /**
     * Displacement below which a fix is never questioned, in metres.
     *
     * Two fixes arriving in the same millisecond would otherwise imply an
     * infinite speed. Rather than special-casing a zero interval, anything
     * inside a normal urban jitter radius is simply accepted: no reflected fix
     * is 15 m away, and no navigation decision turns on 15 m.
     */
    private val alwaysAcceptM: Double = 15.0,
) {
    /** Why a fix was or was not accepted. Reported so a harness can count. */
    enum class Verdict {
        /** Nothing to compare against; the first fix of a session. */
        FIRST,
        ACCEPTED,
        /** Implied an impossible speed and was dropped. */
        REJECTED_IMPLAUSIBLE,
        /** Implausible, but the rejection run is over — believed anyway. */
        ACCEPTED_AFTER_RUN,
    }

    data class Result(val verdict: Verdict, val impliedMs: Double) {
        val accept: Boolean get() = verdict != Verdict.REJECTED_IMPLAUSIBLE
    }

    private var lastPos: LngLat? = null
    private var lastMs: Long = 0L
    private var rejected = 0

    /** Total fixes dropped since [reset]. For the device harness's report. */
    var droppedCount = 0
        private set

    fun reset() {
        lastPos = null
        rejected = 0
        droppedCount = 0
    }

    /**
     * @param nowMs the fix's own arrival time, wall clock.
     * @return whether the caller should use this fix at all.
     */
    fun accept(fix: LngLat, nowMs: Long): Result {
        val prev = lastPos
        if (prev == null) {
            lastPos = fix
            lastMs = nowMs
            return Result(Verdict.FIRST, 0.0)
        }
        val d = RouteGeometry.haversineM(prev.lng, prev.lat, fix.lng, fix.lat)
        val dt = (nowMs - lastMs) / 1000.0
        // A fix stamped at or before the last one carries no interval to divide
        // by. Treated as "no evidence against it" rather than as infinitely
        // fast: out-of-order delivery is a transport problem, not a teleport.
        val implied = if (dt > 0.001) d / dt else 0.0

        if (d <= alwaysAcceptM || implied <= maxSpeedMs) {
            lastPos = fix
            lastMs = nowMs
            rejected = 0
            return Result(Verdict.ACCEPTED, implied)
        }

        rejected++
        if (rejected > maxRejections) {
            // The receiver has out-voted us. Adopt its position and start
            // measuring from there.
            lastPos = fix
            lastMs = nowMs
            rejected = 0
            return Result(Verdict.ACCEPTED_AFTER_RUN, implied)
        }
        droppedCount++
        return Result(Verdict.REJECTED_IMPLAUSIBLE, implied)
    }
}
