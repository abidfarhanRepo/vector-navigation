package dev.vector.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One GPS fix as a receiver would deliver it, plus the truth it was made from.
 *
 * [truth] and [truthAlongM] are the reason this type exists rather than
 * `Location`. An assertion like "the puck never drew more than 15 m from where
 * the car was" is only possible if the harness knows where the car was, and a
 * test that compares the app's output against the app's *input* is comparing a
 * noisy measurement with itself. The noise is the thing under test.
 *
 * [speedMs] and [bearingDeg] are nullable because receivers genuinely omit
 * them. Android's `Location.hasSpeed()` is false at a standstill on most
 * chipsets and false for every network-provider fix, and Vector had a defect
 * that only appears when they are absent — see `DriveScenarioTest`'s
 * `a_receiver_that_never_reports_speed_still_reroutes`.
 */
data class SimFix(
    val tMs: Long,
    val position: LngLat,
    val speedMs: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double,
    val truth: LngLat,
    val truthAlongM: Double,
    /** True while the fix is inside a [DriveSimulator.Fault.Jump] window. */
    val faulted: Boolean = false,
)

/**
 * Synthesises a realistic GPS stream for a path a vehicle drove.
 *
 * ## Why this is a product component and not a test fixture
 *
 * Everything Vector does while navigating is a function of a position stream:
 * whether it reroutes, when it speaks, how the camera moves, whether it says
 * you have arrived. Until V5 that stream could only come from a real car, so
 * every claim about moving behaviour was untested — V3 and V4 both closed with
 * "needs a moving car" as a release blocker, and V4's frame numbers are from a
 * parked phone and say so.
 *
 * A simulator does not remove the need for a real drive. It removes the need
 * for a real drive *per assertion*. Deviation detected within 3 s, no reroute
 * from 8 m of drift, arrival not declared 400 m early on a route that doubles
 * back — those are properties of the navigation logic given a stream, and a
 * stream is a thing that can be constructed.
 *
 * ## What is modelled, and why each part is there
 *
 * Every one of these exists because leaving it out makes a test that passes
 * against behaviour that would fail in a car:
 *
 *  * **Acceleration limits.** A stream that steps from 0 to 80 km/h between two
 *    fixes gives the tracker a 22 m/s dead-reckoning speed while the car has
 *    covered 3 m, so the puck sails up the road and the countdown runs early.
 *    Legs are integrated at [DT_S] with real accel/brake limits instead.
 *  * **White jitter.** Per-fix independent error. Small, always present.
 *  * **Correlated drift.** The error that actually causes false reroutes: an
 *    Ornstein–Uhlenbeck walk with a time constant, because multipath in an
 *    urban canyon pushes the fix to the *same wrong side* for twenty seconds
 *    at a time. White noise of the same magnitude averages out and a threshold
 *    tuned against it is tuned against the wrong signal.
 *  * **Interval jitter.** Fixes do not arrive on a metronome. A frame loop that
 *    happens to be sampled in phase with them behaves differently from one
 *    that is not.
 *  * **Omitted speed and bearing** below a floor, as receivers do.
 *  * **Faults**: outage, jump, degradation, delay. See [Fault].
 *
 * Deterministic for a given [DrivePlan.seed] — every scenario in the suite
 * pins one — while still producing a different realistic stream for every other
 * seed, which is what makes a seed sweep able to find the deviation the tuned
 * case misses.
 */
object DriveSimulator {

    /** Integration step. Fine enough that 3.5 m/s² braking resolves to ~0.2 m. */
    const val DT_S = 0.05

    /** Comfortable acceleration, m/s². About what a saloon does unhurried. */
    const val ACCEL_MS2 = 2.2

    /** Comfortable braking, m/s². Well short of an emergency stop. */
    const val BRAKE_MS2 = 3.2

    /** What the vehicle does, in order. */
    sealed interface Leg {
        /** Cover [distanceM] metres aiming for [targetMs], accelerating into it. */
        data class Cruise(val distanceM: Double, val targetMs: Double) : Leg

        /** Brake to a halt and wait [seconds]. A red light, or a queue. */
        data class Stop(val seconds: Double) : Leg

        /** Drive whatever is left of the path at [targetMs]. */
        data class Remainder(val targetMs: Double) : Leg

        /** Brake from the current speed to [targetMs] over [distanceM]. */
        data class SlowTo(val distanceM: Double, val targetMs: Double) : Leg
    }

    /**
     * Receiver error.
     *
     * Split into an independent part and a correlated part on purpose; see the
     * class KDoc. [accuracyM] is what the receiver *claims*, which is a
     * separate lie from what it delivers — a receiver reporting 5 m while
     * drifting 20 m is the normal urban case and the reason an app cannot
     * simply trust the accuracy field.
     */
    data class Noise(
        val jitterM: Double = 2.0,
        val driftM: Double = 0.0,
        val driftTauS: Double = 25.0,
        val accuracyM: Double = 6.0,
    ) {
        companion object {
            /** Clear sky, open road. */
            val CLEAN = Noise(jitterM = 0.0, driftM = 0.0, accuracyM = 4.0)

            /** A good fix on a normal road. */
            val GOOD = Noise(jitterM = 1.5, driftM = 2.5, accuracyM = 5.0)

            /** Between towers on Al Corniche. This is the false-reroute case. */
            val URBAN_CANYON = Noise(jitterM = 3.0, driftM = 9.0, driftTauS = 20.0, accuracyM = 12.0)
        }
    }

    /**
     * Something going wrong, in a window of trace time.
     *
     * Windows are in seconds from the start of the trace rather than in metres
     * along it, because that is how they happen: a tunnel lasts as long as it
     * takes to drive, and the same tunnel at 30 km/h and at 110 km/h is a
     * different number of lost fixes.
     */
    sealed interface Fault {
        val atS: Double
        val forS: Double

        /** No fixes at all. A tunnel, an underground car park, a dead chipset. */
        data class Outage(override val atS: Double, override val forS: Double) : Fault

        /**
         * A large, wrong position — the classic reflected-signal fix.
         *
         * Distinct from [Degraded] because it is not a bigger sigma: it is a
         * consistent displacement for a few seconds, which is what actually
         * arrives, and which a mean-reverting noise model cannot produce.
         */
        data class Jump(
            override val atS: Double,
            override val forS: Double,
            val offsetM: Double,
            val towardDeg: Double,
        ) : Fault

        /** Bigger error and an honest accuracy field to match. */
        data class Degraded(
            override val atS: Double,
            override val forS: Double,
            val jitterM: Double = 15.0,
            val driftM: Double = 25.0,
            val accuracyM: Double = 45.0,
        ) : Fault

        /** Fixes still arrive, but at [everyS] instead of the plan's rate. */
        data class Delay(
            override val atS: Double,
            override val forS: Double,
            val everyS: Double = 5.0,
        ) : Fault
    }

    /**
     * A drive to synthesise.
     *
     * @param path the truth the vehicle travelled. Build it with [DrivePath].
     * @param legs the speed programme. Consumed in order; when the path runs
     *   out the trace ends, and when the legs run out the vehicle coasts at its
     *   last target to the end of the path.
     * @param hz nominal fix rate. 1 Hz is what Android's fused provider gives
     *   at `PRIORITY_HIGH_ACCURACY` with a 1 000 ms interval, which is what
     *   `MainActivity.startLocation` asks for.
     * @param intervalJitter fraction of the interval to jitter by, uniformly.
     * @param speedFloorMs below this the receiver reports no speed and no
     *   bearing. 0.5 m/s matches an S24's behaviour at a standstill; 0 disables.
     */
    data class DrivePlan(
        val path: List<LngLat>,
        val legs: List<Leg>,
        val hz: Double = 1.0,
        val intervalJitter: Double = 0.12,
        val noise: Noise = Noise.GOOD,
        val faults: List<Fault> = emptyList(),
        val speedFloorMs: Double = 0.5,
        val seed: Long = 1L,
        /** Hard stop, so a plan that cannot finish cannot hang a test. */
        val maxDurationS: Double = 3_600.0,
    )

    /**
     * Run [plan] and return the fixes a receiver would have delivered.
     *
     * The trace ends when the path is exhausted, the legs are exhausted *and*
     * the vehicle has stopped, or [DrivePlan.maxDurationS] is reached —
     * whichever comes first.
     */
    fun run(plan: DrivePlan): List<SimFix> {
        val idx = RouteGeometry.index(plan.path) ?: return emptyList()
        val rnd = Random(plan.seed)
        val out = ArrayList<SimFix>()

        var alongM = 0.0
        var speed = 0.0
        var t = 0.0
        var legIdx = 0
        var legStartAlong = 0.0
        var dwellLeft = 0.0
        var nextFixAt = 0.0
        var drift = 0.0
        var driftDir = rnd.nextDouble() * 360.0
        var lastDelayEmit = -1e9

        // Coasting target once the programme is exhausted: keep the last
        // commanded speed rather than dropping to zero, so a plan that only
        // describes its first kilometre still finishes the path at a plausible
        // speed instead of crawling.
        var coastTarget = plan.legs.filterIsInstance<Leg.Cruise>().firstOrNull()?.targetMs
            ?: plan.legs.filterIsInstance<Leg.Remainder>().firstOrNull()?.targetMs
            ?: 13.0

        while (t < plan.maxDurationS) {
            // ---- decide this step's target speed -----------------------------
            val leg = plan.legs.getOrNull(legIdx)
            var target = coastTarget
            var braking = false
            when (leg) {
                is Leg.Cruise -> {
                    target = leg.targetMs
                    coastTarget = leg.targetMs
                    if (alongM - legStartAlong >= leg.distanceM) {
                        legIdx++; legStartAlong = alongM; continue
                    }
                }
                is Leg.SlowTo -> {
                    target = leg.targetMs
                    braking = true
                    if (alongM - legStartAlong >= leg.distanceM) {
                        coastTarget = leg.targetMs
                        legIdx++; legStartAlong = alongM; continue
                    }
                }
                is Leg.Stop -> {
                    target = 0.0
                    braking = true
                    if (speed <= 0.01) {
                        if (dwellLeft <= 0.0) dwellLeft = leg.seconds
                        dwellLeft -= DT_S
                        if (dwellLeft <= 0.0) {
                            dwellLeft = 0.0
                            legIdx++; legStartAlong = alongM
                        }
                    }
                }
                is Leg.Remainder -> { target = leg.targetMs; coastTarget = leg.targetMs }
                null -> target = coastTarget
            }

            // ---- integrate ---------------------------------------------------
            val rate = if (target < speed || braking) BRAKE_MS2 else ACCEL_MS2
            speed = if (target > speed) minOf(target, speed + rate * DT_S)
                    else maxOf(target, speed - rate * DT_S)
            alongM = (alongM + speed * DT_S).coerceAtMost(idx.totalM)

            // ---- drift walk (mean-reverting) ---------------------------------
            if (plan.noise.driftM > 0.0) {
                val tau = plan.noise.driftTauS.coerceAtLeast(DT_S)
                val decay = kotlin.math.exp(-DT_S / tau)
                val kick = plan.noise.driftM * sqrt(1 - decay * decay) * gauss(rnd)
                drift = drift * decay + kick
                // The direction wanders slowly too, so the error is not pinned
                // to one compass point for a whole trace.
                driftDir += gauss(rnd) * 4.0 * DT_S
            }

            // ---- emit a fix if one is due ------------------------------------
            if (t >= nextFixAt) {
                val degraded = plan.faults.filterIsInstance<Fault.Degraded>().firstOrNull { inWindow(it, t) }
                val outage = plan.faults.filterIsInstance<Fault.Outage>().any { inWindow(it, t) }
                val delay = plan.faults.filterIsInstance<Fault.Delay>().firstOrNull { inWindow(it, t) }
                val jump = plan.faults.filterIsInstance<Fault.Jump>().firstOrNull { inWindow(it, t) }

                val suppressedByDelay = delay != null && (t - lastDelayEmit) < delay.everyS
                if (!outage && !suppressedByDelay) {
                    if (delay != null) lastDelayEmit = t
                    val pt = idx.pointAt(alongM)
                    if (pt != null) {
                        val jitter = degraded?.jitterM ?: plan.noise.jitterM
                        val driftScale = if (degraded != null) degraded.driftM / plan.noise.driftM.coerceAtLeast(0.001) else 1.0
                        var p = pt.position
                        if (jitter > 0.0) {
                            p = DrivePath.offset(p, rnd.nextDouble() * 360.0, gauss(rnd) * jitter)
                        }
                        if (abs(drift) > 0.0) {
                            p = DrivePath.offset(p, driftDir, drift * driftScale)
                        }
                        if (jump != null) {
                            p = DrivePath.offset(p, jump.towardDeg, jump.offsetM)
                        }
                        val reports = speed >= plan.speedFloorMs
                        out.add(
                            SimFix(
                                tMs = (t * 1000.0).toLong(),
                                position = p,
                                speedMs = if (reports) speed else null,
                                bearingDeg = if (reports) pt.bearing else null,
                                accuracyM = degraded?.accuracyM ?: plan.noise.accuracyM,
                                truth = pt.position,
                                truthAlongM = alongM,
                                faulted = jump != null,
                            )
                        )
                    }
                }
                val interval = 1.0 / plan.hz
                val j = if (plan.intervalJitter > 0)
                    (rnd.nextDouble() * 2 - 1) * plan.intervalJitter * interval else 0.0
                nextFixAt = t + interval + j
            }

            t += DT_S

            // ---- termination -------------------------------------------------
            val pathDone = alongM >= idx.totalM - 0.01
            // A Remainder leg is "drive whatever is left", so once there is
            // nothing left it IS done. Without this clause it never completes,
            // `legsDone` stays false, and a cruising vehicle never satisfies
            // `speed <= 0.01` either — so the loop ran to `maxDurationS` and
            // emitted an hour of fixes parked on the last coordinate.
            //
            // Found by the scenario suite, not by this file's own tests, which
            // asserted a LOWER bound on the fix count and were satisfied by
            // fifteen times too many. `a drive ends when the road does` is the
            // assertion that was missing.
            val legsDone = legIdx >= plan.legs.size ||
                plan.legs.getOrNull(legIdx).let { it is Leg.Remainder || it is Leg.Cruise && pathDone }
            if (pathDone && (legsDone || speed <= 0.01)) {
                // Emit one last fix at the terminal position so a scenario that
                // asserts on arrival is not decided by where the sampler
                // happened to land.
                if (out.isEmpty() || out.last().truthAlongM < idx.totalM - 0.5) {
                    idx.pointAt(idx.totalM)?.let { pt ->
                        out.add(
                            SimFix(
                                tMs = (t * 1000.0).toLong(),
                                position = pt.position,
                                speedMs = if (speed >= plan.speedFloorMs) speed else null,
                                bearingDeg = if (speed >= plan.speedFloorMs) pt.bearing else null,
                                accuracyM = plan.noise.accuracyM,
                                truth = pt.position,
                                truthAlongM = idx.totalM,
                            )
                        )
                    }
                }
                break
            }
        }
        return out
    }

    private fun inWindow(f: Fault, t: Double) = t >= f.atS && t < f.atS + f.forS

    /** Standard normal, Box–Muller. [Random] has no nextGaussian. */
    private fun gauss(rnd: Random): Double {
        var u = rnd.nextDouble()
        while (u <= 1e-12) u = rnd.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * rnd.nextDouble())
    }

    // ---- speed programmes ---------------------------------------------------

    /** Steady [kmh] for the whole path, with a stop at the end. */
    fun cruise(kmh: Double): List<Leg> = listOf(Leg.Remainder(kmh / 3.6))

    /**
     * V5 §B: 0, 5, 15, 30, 50, 80 km/h and back down, repeatedly.
     *
     * The point is not the speeds, it is the *transitions*: the auto-zoom
     * hysteresis, the dead-reckoning speed and the ETA's average-speed divisor
     * all change behaviour across them, and the first version of Vector's
     * auto-zoom flapped between two bands at a steady 54–56 km/h.
     */
    fun stopAndGo(cycles: Int = 3, legM: Double = 180.0, dwellS: Double = 12.0): List<Leg> {
        val ladder = listOf(5.0, 15.0, 30.0, 50.0, 80.0, 50.0, 15.0)
        val legs = ArrayList<Leg>()
        repeat(cycles) {
            for (kmh in ladder) legs.add(Leg.Cruise(legM, kmh / 3.6))
            legs.add(Leg.Stop(dwellS))
        }
        legs.add(Leg.Remainder(40.0 / 3.6))
        return legs
    }

    /** Urban: 50 km/h with junction stops. */
    fun urban(stops: Int = 4, blockM: Double = 400.0): List<Leg> {
        val legs = ArrayList<Leg>()
        repeat(stops) {
            legs.add(Leg.Cruise(blockM, 50.0 / 3.6))
            legs.add(Leg.Stop(9.0))
        }
        legs.add(Leg.Remainder(45.0 / 3.6))
        return legs
    }

    /** Motorway: accelerate up a slip road, cruise, then exit. */
    fun highway(cruiseKmh: Double = 100.0, exitAfterM: Double = 8_000.0): List<Leg> = listOf(
        Leg.Cruise(400.0, 60.0 / 3.6),
        Leg.Cruise(exitAfterM, cruiseKmh / 3.6),
        Leg.SlowTo(300.0, 50.0 / 3.6),
        Leg.Remainder(50.0 / 3.6),
    )
}
