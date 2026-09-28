package dev.vector.geo

/**
 * Pure policy for how the camera reacts to the maneuver ahead (V7 Stage 2).
 *
 * The driving experience the product brief asks for — normal navigation →
 * early awareness → subtle camera framing → exact lane guidance when
 * trustworthy → maneuver → smooth recovery — falls out of one per-maneuver,
 * monotonic state machine:
 *
 * ```
 * CRUISE → ANTICIPATE → FRAME → COMMIT → RECOVER → CRUISE
 * ```
 *
 * ...except where junctions come faster than recovery fits, which in Doha is
 * most of them. There the ladder is:
 *
 * ```
 * … FRAME A → COMMIT A → HANDOFF → FRAME B → COMMIT B → HANDOFF → … → RECOVER
 * ```
 *
 * one continuous envelope across the whole chain, ending in a single recovery
 * when the chain does.
 *
 * ## The rules this exists to hold
 *
 * 1. **Fix-path only.** The caller runs [update] where the speed-band
 *    auto-zoom (`MapCamera.maybeAutoZoom`) runs — on GPS fixes, never from the
 *    frame loop. A followed frame never asserts a zoom (the V3 invariant), and
 *    a per-frame zoom would fight both pinch-to-zoom and the speed bands.
 * 2. **One eased write per stage.** A stage is a discrete event exactly like a
 *    band change. [Decision.Transition] carries a duration; COMMIT carries no
 *    write at all because the zoom is already where it should be and writing at
 *    the moment the driver acts is how cameras pump.
 * 3. **Monotonic within a maneuver, keyed on `maneuver.index`.** Stages only
 *    advance; retreating needs the driver to move back more than
 *    [HYSTERESIS_M], which is what stops a floaty threshold flip.
 * 4. **Latched complexity.** The complexity score is computed once when the
 *    maneuver becomes current, because `approachLanes`/`laneUseful` cannot
 *    change mid-approach anyway.
 * 5. **Driver owns zoom.** A pinch suspends the maneuver camera for the
 *    remainder of that maneuver only. What counts as a pinch is the CALLER's
 *    judgement (`CameraGate.isDriverZoom`), measured against the zoom Vector
 *    itself last asserted — never against the speed band, which cannot tell a
 *    driver's finger from this class's own ANTICIPATE/FRAME write.
 * 6. **Dense junctions hand off; they do not pump.** Recovery is worth doing
 *    only if it can finish and be seen: [recoverAt] is the next maneuver's
 *    anticipation window plus a runway of open road. Inside that, the camera
 *    [Stage.HANDOFF]s — it carries the envelope it is already holding into the
 *    next maneuver instead of easing out to the band and straight back in.
 * 7. **The envelope never opens up mid-chain.** A handed-off maneuver may ask
 *    the camera to tighten further; it may never ask it to back off. So the
 *    zoom through a dense sequence is monotonically non-decreasing relative to
 *    the speed band, and bounded by the tightest FRAME any maneuver in the
 *    chain asks for — which is what makes a sequence of junctions read as one
 *    look-ahead rather than a series of lunges.
 * 8. **Recovery returns to the speed-band baseline, and every chain ends in
 *    one.** RECOVER targets [baseZoom] exactly, so the band auto-zoom can pick
 *    up from where it left off; and the first index change with runway ends
 *    the chain, however long it was.
 *
 * The caller gates the camera MODES ([Stage]-independent facts like FREE /
 * OVERVIEW / PREVIEW / manual bearing / off-route) before calling [update];
 * this class is about the maneuver lifecycle, not about where the driver is
 * looking.
 */
class ManeuverCamera {

    /**
     * The life of one maneuver, in order.
     *
     * [HANDOFF] is declared FIRST, ahead of [CRUISE], for a mechanical reason
     * worth stating: `onSameIndex` advances when `want.ordinal > cur.ordinal`,
     * so a stage that must always be advanced out of has to sort below every
     * stage a distance can ask for. It is handled explicitly before that
     * comparison anyway ([fromHandoff]); ordering it first is the fail-safe,
     * not the mechanism. Nothing persists an ordinal.
     */
    enum class Stage { HANDOFF, CRUISE, ANTICIPATE, FRAME, COMMIT, RECOVER }

    /** Everything the state machine needs to know on one GPS fix. */
    data class Input(
        val maneuverIndex: Int = -1,
        val maneuverType: String = "",
        val distanceToManeuverM: Double = 0.0,
        val speedMs: Double = 0.0,
        /** Lane count under the vehicle, when the map has one. */
        val approachLanes: Int? = null,
        /** True when `LaneGuidance` found a lane choice worth showing. */
        val laneUseful: Boolean = false,
        /** Distance to the maneuver AFTER this one, when there is one. */
        val nextManeuverGapM: Double? = null,
    )

    /** What this fix produced. */
    sealed class Decision {
        /** Nothing to write: CRUISE, COMMIT holding, suspended, or no maneuver. */
        object Noop : Decision()

        /** One eased camera write. */
        data class Transition(
            val stage: Stage,
            val targetZoom: Double,
            val durationMs: Int,
        ) : Decision()
    }

    companion object {
        /** Ease for ANTICIPATE/FRAME: the same nudge as the auto-zoom band change. */
        const val ANTICIPATE_DURATION_MS = 380

        /** Ease for recovery: longer, because the driver is not looking at it. */
        const val RECOVER_DURATION_MS = 900

        /** Backtracking margin before a stage retreats (see `update`). */
        const val HYSTERESIS_M = 80.0

        /** Same threshold as [ManeuverAnnouncer.TURN_M]: "we are committed". */
        const val TURN_M = 120.0

        // ---- Stage entry distances (plan §6.3, amended after Stage 3) ----
        //
        // DISTANCE FIRST, time as an expansion. The original formulation was a
        // pure time budget with a distance clamp underneath
        // (`speed * (3 + complexity)` clamped to `[120, 450]`), and its floor
        // was TURN_M itself — so whenever the budget floored out, FRAME's
        // window was `(120, 120]`, i.e. empty, and `entryStage` went straight
        // from ANTICIPATE to COMMIT. FRAME needed 144 km/h at complexity 0 and
        // 86 km/h at complexity 2; the Stage 3 device run measured it firing
        // twice in five Doha drives, both on the motorway, one GPS fix each.
        //
        // A junction is a place, not a duration. These distances hold at every
        // speed; speed and complexity may push them further OUT (see earlier at
        // 110 km/h than at 30) but can never pull them in past [FRAME_MIN_M],
        // which is strictly above TURN_M. That inequality is the invariant the
        // defect broke and `frameWindowIsNeverEmpty` pins.

        /** Where ANTICIPATE begins for an ordinary turn. */
        const val ANTICIPATE_BASE_M = 300.0

        /** Extra anticipation per complexity point: a roundabout wants warning. */
        const val ANTICIPATE_COMPLEXITY_M = 40.0

        /** Time budget that takes over above ~98 km/h, where 300 m is under 11 s. */
        const val ANTICIPATE_SECONDS = 11.0

        /** Ceiling: anticipating a turn a kilometre out is not anticipation. */
        const val ANTICIPATE_MAX_M = 900.0

        /** Where FRAME begins for an ordinary turn. */
        const val FRAME_BASE_M = 180.0

        /**
         * Extra framing distance per complexity point. **Zero, deliberately.**
         *
         * This was 20 m when the distance-first entries were written, so a
         * complexity-3 junction was FRAMEd from 240 m rather than 180 m. It
         * reads well as an intention and does not survive contact with the
         * screen: at 60 deg of pitch, with the lane strip taking the top ~15%
         * of the map, 240 m is simply beyond the distance at which a junction
         * can be shown at a size worth showing. The device sweep that settled
         * it is unambiguous — same junction, same zoom, two distances:
         *
         *   199 m  z18.594  junction at the very top, callout clipped
         *   245 m  z18.594  junction entirely off the map. Carriageway only.
         *
         * (`V7-STAGE2-TUNING-EVIDENCE/sweep-c3/frames/`, 028 and 027.) And it
         * cannot be paid for with the zoom, because the lift that WOULD keep a
         * 240 m junction framed is below [ANTICIPATE_LIFT] at complexity 3 —
         * a FRAME wider than the ANTICIPATE before it, which is not a ladder.
         *
         * A junction is a place. So is the distance you can frame one from:
         * it belongs to the screen and the pitch, not to how hard the junction
         * is. Complexity's earliness lives in [ANTICIPATE_COMPLEXITY_M], which
         * is the stage whose whole job is early awareness and which is 40 m per
         * level — twice what this ever was — and it is untouched.
         *
         * Kept as a named constant at zero rather than deleted: it is a design
         * decision with a measurement behind it, not an absent feature.
         */
        const val FRAME_COMPLEXITY_M = 0.0

        /** Time budget that takes over above ~108 km/h. */
        const val FRAME_SECONDS = 6.0

        /**
         * Floor for the FRAME entry, and the whole point of the amendment.
         *
         * **Must stay strictly greater than [TURN_M].** `entryStage` tests
         * COMMIT first, so a FRAME entry at or below TURN_M is a stage that can
         * never be entered.
         */
        const val FRAME_MIN_M = 160.0

        /** Ceiling: framing a junction 320 m out is not framing it. */
        const val FRAME_MAX_M = 320.0

        /** ANTICIPATE always precedes FRAME by a real distance, never a rounding. */
        const val MIN_STAGE_GAP_M = 60.0

        /** Compound "then" window, mirroring [ManeuverAnnouncer]. */
        const val COMPOUND_SECONDS = 4.0
        const val MIN_COMPOUND_M = 120.0

        // ---- Zoom above the speed-band baseline each stage aims for ----
        //
        // COMPLEXITY BUYS EARLINESS, NOT TIGHTNESS. The distances above already
        // widen with complexity: a complexity-3 junction is FRAMEd at 240 m
        // where a complexity-0 one waits until 180 m. So a complexity term that
        // ALSO tightens the zoom is spending the same budget twice — the camera
        // is asked to show a junction a third further away through a lens 1.6x
        // longer, and what the driver gets is a carriageway with the junction
        // jammed against the top of the map.
        //
        // That is measured, not asserted. On the same slip split at the same
        // ~199 m, from two Stage 3 device runs (see V7-STAGE3-RERUN-D1-D2 §2):
        //
        //   z18.344  the whole split: divergence node, callout, road beyond  OK
        //   z18.800  (complexity 2 at 193 m) junction in the top third       OK
        //   z19.050  (complexity 3) carriageway only, callout gone           NO
        //
        // Scaled to a common measure — the ground distance to the junction
        // times the lens, `frameAt x 2^lift`, which is the junction's size on
        // screen — those read 357 and 475 (both fine) against 583 (not). The
        // budget therefore sits somewhere in (475, 583); `ManeuverCameraTest`
        // pins it at 520 and asserts no complexity exceeds it.
        //
        // A third factor points the same way and is easy to miss: complexity 3
        // is usually complexity 3 BECAUSE the lane strip is up (`laneUseful`
        // +1, four lanes +1), and the strip costs ~15% of the map's height.
        // The junctions that get the most zoom get the least map to put it in.
        //
// Hence 0.10 per level rather than 0.25. With [FRAME_COMPLEXITY_M] at
        // zero for the reason given there, the four budgets come out 313, 336,
        // 360 and 386 — all inside the measured-good band, against a
        // complexity 3 that used to sit at 703. It stays perceptible: +0.30
        // from complexity 0 to 3, a quarter of a zoom level, on top of 120 m
        // of extra anticipation.
        //
        // ANTICIPATE's complexity term is deliberately NOT touched. It is not
        // framing anything; it is the gentle awareness tighten, it was measured
        // reading correctly at +0.85 / 245 m, and widening this pass's scope to
        // a stage nobody reported a defect in is how tuning passes turn into
        // rewrites.
        const val ANTICIPATE_LIFT = 0.4
        const val ANTICIPATE_COMPLEXITY_LIFT = 0.15
        const val FRAME_LIFT = 0.8
        const val FRAME_COMPLEXITY_LIFT = 0.10

        // ---- Dense-junction handoff (the RECOVER/HANDOFF boundary) ----
        //
        // RECOVER used to be skipped whenever the next maneuver's ANTICIPATE
        // window was already open at the index change. That rule was calibrated
        // against a 250 m window; the distance-first entries widened it to
        // 300-420 m, and Msheireb's legs are 22-250 m — so it was ALWAYS open,
        // RECOVER was never reached, and the camera walked
        // 18.8 -> 18.2 -> 18.8 -> 19.3 -> ... for sixteen maneuvers without once
        // returning to the band (k-dense: RECOVER 0, mean |zoom - band| 1.188,
        // max 2.701).
        //
        // The rule was not wrong about WHEN to skip; it was wrong about what to
        // do instead. Entering the next maneuver's stage cold writes that
        // stage's own zoom, and a fresh ANTICIPATE (+0.4) is BELOW the FRAME
        // (+1.1) it follows — so the camera backs off exactly as the driver
        // reaches the next junction. Out, in, out, in.
        //
        // So: two named outcomes with an explicit boundary between them.
        //
        //   distance > recoverAt   ->  RECOVER  (ease to the band, once)
        //   distance <= recoverAt  ->  HANDOFF  (carry the envelope across)
        //
        // and [recoverAt] is "far enough that a recovery can finish and be
        // seen": the next maneuver's anticipation window, plus a runway.

        /**
         * Open road a recovery needs beyond the next anticipation window.
         *
         * [RECOVER_DURATION_MS] of ease plus about three seconds of the driver
         * actually seeing the road back. Below this the recovery would still be
         * easing out when the next tightening began, which is the pump.
         */
        const val RECOVER_RUNWAY_SECONDS = 4.0

        /** Floor for the runway, so it survives a stop-start junction. */
        const val RECOVER_RUNWAY_MIN_M = 60.0

        /**
         * How far a handoff must move the camera to be worth a write.
         *
         * A handoff that needs nothing new writes NOTHING — that is what makes
         * a dense sequence one envelope rather than a series of nudges. Below
         * the smallest band gap (0.5) so a real band change still re-anchors
         * the envelope onto the speed the driver is now doing, and far enough
         * above zero that float noise cannot produce a write.
         */
        const val HANDOFF_REANCHOR = 0.15

        /** Maneuvers that never get a camera at all. */
        fun isManeuver(type: String): Boolean = type != "continue" && type != "depart"
    }

    private var lastIndex: Int = -1
    private var stage: Stage = Stage.CRUISE
    private var complexity: Int = 0
    private var suspended: Boolean = false
    /**
     * Stages already WRITTEN per maneuver index.
     *
     * Per-index rather than a single set so that a maneuver re-approached after
     * a long reversal (a U-turn projection dip on a 27 km route) does not
     * re-fire stages that already wrote: its history survives whatever
     * intermediate manoeuvres relatched in between. That is the difference
     * between one easing into a junction, and an out-of-order FRAME-then-zoom-out
     * wobble when the route projection swings back.
     */
    private val writtenByIndex = HashMap<Int, MutableSet<Stage>>()

    /**
     * The largest maneuver index ever latched on this route segment.
     *
     * Crossing it is the ONLY thing that relatches: GPS flicker at a junction
     * makes `upcomingManeuver` skip between two adjacent indexes for a second
     * or two, and re-latching on every wiggle re-fires stages that already
     * wrote — the "zoom pumping at dense junctions" the plan forbids. See
     * [update].
     */
    private var maxLatchedIndex: Int = -1

    /**
     * The lift above the speed band the camera is currently holding.
     *
     * The envelope, in the one unit that survives a band change: a LIFT, not a
     * zoom. Every write re-anchors it onto whatever [baseZoom] the speed now
     * implies, so a chain of junctions driven while slowing down does not
     * accumulate the difference. Zero means Vector is holding nothing and the
     * zoom is the band's (or the driver's).
     */
    private var envelopeLift: Double = 0.0

    /** The absolute zoom the last write asserted, for the handoff re-anchor. */
    private var assertedZoom: Double = 0.0

    /**
     * Force [complexityOf] to a fixed answer, for camera VALIDATION only.
     *
     * Complexity is a wire fact, so the only way to see complexity 0, 1, 2 and
     * 3 framing the SAME junction from the SAME distance — which is the only
     * comparison that says anything about whether the framing is right — is to
     * hold the junction and vary the score. That is what this is for and it is
     * the whole of what it is for.
     *
     * The caller sets it behind `BuildConfig.DEBUG` from a launch extra
     * (`--ei vectorCamComplexity 3`); nothing in the app ever writes it
     * otherwise, and null is "use the facts", which is every real drive.
     */
    var complexityPin: Int? = null

    /** Current lifecycle stage, for tests and the band-change suppressor. */
    fun stage(): Stage = stage

    /**
     * The lift above the speed band the camera is holding, for tests.
     *
     * The bound rule 7 promises: across any dense sequence this is
     * non-decreasing until the chain ends, and never exceeds the tightest
     * [liftForStage] any maneuver in the chain asked for.
     */
    fun envelopeLift(): Double = envelopeLift

    /**
     * True while a maneuver stage owns the zoom.
     *
     * The auto-zoom band change is suppressed during this window so the two
     * policies cannot fight over the same zoom on the same fix path.
     */
    fun isActive(): Boolean =
        stage == Stage.ANTICIPATE || stage == Stage.FRAME || stage == Stage.COMMIT ||
            stage == Stage.HANDOFF

    /** Clear every latch: route replaced, arrival, reroute. */
    fun reset() {
        lastIndex = -1
        maxLatchedIndex = -1
        stage = Stage.CRUISE
        complexity = 0
        suspended = false
        envelopeLift = 0.0
        assertedZoom = 0.0
        writtenByIndex.clear()
    }

    /**
     * Step the state machine on one GPS fix.
     *
     * @param baseZoom the speed-band baseline zoom the maneuver zooms sit
     *   relative to, and the zoom recovery returns to.
     * @param driverZoomed true when the driver has pinched more than a band
     *   from [baseZoom]; suspends the maneuver camera for this maneuver.
     */
    fun update(input: Input, baseZoom: Double, driverZoomed: Boolean): Decision {
        if (input.maneuverIndex < 0) return Decision.Noop
        if (!isManeuver(input.maneuverType)) {
            // continue/depart never move the camera, but they DO mark the
            // previous maneuver's completion. Two cases, exactly like the turn
            // branch below:
            //  * an index that never exceeded the max latched (the maneuver
            //    index flickered back across a continue boundary): HOLD — the
            //    turn's own latches must survive so it does not re-fire;
            //  * genuine forward progress onto a continue leg: the completed
            //    maneuver eases back to the band once, then coasts.
            if (input.maneuverIndex != lastIndex) {
                if (input.maneuverIndex <= maxLatchedIndex) {
                    lastIndex = input.maneuverIndex
                    return Decision.Noop
                }
                maxLatchedIndex = input.maneuverIndex
                lastIndex = input.maneuverIndex
                complexity = 0
                suspended = false
                if (driverZoomed) {
                    surrenderEnvelope()
                    return Decision.Noop
                }
                if (envelopeLift <= 0.0) {
                    stage = Stage.CRUISE
                    return Decision.Noop
                }
                // Holding an envelope from the turn that just completed. A
                // continue leg long enough to recover into gets the road back;
                // a short link between two junctions carries the envelope
                // across it, silently — a continue has nothing to frame, so a
                // HANDOFF here is a HOLD, never a write.
                if (recoveryHasRunway(input, complexity)) {
                    stage = Stage.RECOVER
                    return transition(Stage.RECOVER, baseZoom)
                }
                stage = Stage.HANDOFF
            }
            return Decision.Noop
        }
        if (input.maneuverIndex != lastIndex) {
            // Not forward progress past everything already latched: the
            // maneuver index flickered back (GPS noise at a junction) or the
            // driver briefly reversed. Either way, HOLD. Re-latching here —
            // and re-writing stages that already fired — is exactly the zoom
            // pumping the plan forbids in dense junctions.
            if (input.maneuverIndex <= maxLatchedIndex) {
                lastIndex = input.maneuverIndex
                if (driverZoomed) {
                    suspended = true
                    surrenderEnvelope()
                }
                return Decision.Noop
            }
            // New maneuver. The driver may ALREADY own the zoom (a pinch from
            // the previous maneuver): that suspends this whole approach,
            // recovery included — easing back to the band would undo their
            // pinch. The latch still moves on so a later unpinched maneuver
            // starts clean.
            maxLatchedIndex = input.maneuverIndex
            lastIndex = input.maneuverIndex
            complexity = complexityOf(input)
            if (driverZoomed) {
                suspended = true
                stage = Stage.CRUISE
                surrenderEnvelope()
                return Decision.Noop
            }
            suspended = false
            return onIndexChanged(input, baseZoom)
        }
        if (driverZoomed) {
            // The pinch landed mid-approach: the zoom is the driver's for the
            // rest of this maneuver. CRUISE so nothing resumes from a stale
            // stage when the next maneuver arrives, and drop the envelope —
            // carrying a lift across a junction is only defensible while the
            // lift is Vector's to carry.
            suspended = true
            stage = Stage.CRUISE
            surrenderEnvelope()
            return Decision.Noop
        }
        if (suspended) return Decision.Noop
        return onSameIndex(input, baseZoom)
    }

    // -------------------------------------------------------------------
    // The machine
    // -------------------------------------------------------------------

    private fun onIndexChanged(input: Input, baseZoom: Double): Decision {
        lastIndex = input.maneuverIndex
        complexity = complexityOf(input)
        suspended = false
        return when {
            // Nothing held: this maneuver starts clean, at whatever rung the
            // distance puts it on.
            envelopeLift <= 0.0 ->
                if (input.distanceToManeuverM in 0.0..anticipateAt(input, complexity)) {
                    val s = entryStage(input, complexity)
                    stage = s
                    if (s == Stage.COMMIT) Decision.Noop else transition(s, baseZoom)
                } else {
                    stage = Stage.CRUISE
                    Decision.Noop
                }
            // Holding an envelope from the maneuver that just completed, with
            // room to give the road back before the next one wants attention.
            recoveryHasRunway(input, complexity) -> {
                stage = Stage.RECOVER
                transition(Stage.RECOVER, baseZoom)
            }
            // Holding an envelope, and the next junction is already upon us.
            else -> handoff(input, baseZoom)
        }
    }

    /**
     * Carry the camera from one maneuver into the next without letting go.
     *
     * The alternative — entering the next maneuver's stage cold — writes that
     * stage's own zoom, and a fresh ANTICIPATE sits BELOW the FRAME it follows.
     * The camera would back off just as the driver arrives at the junction.
     *
     * So a handoff takes the greater of what it is holding and what the new
     * maneuver asks for, and usually that is what it is already holding, in
     * which case it writes nothing at all. The only thing that makes it write
     * is a genuine change: a tighter maneuver than the one before it, or a
     * speed band that moved under the envelope ([HANDOFF_REANCHOR]).
     */
    private fun handoff(input: Input, baseZoom: Double): Decision {
        val want = entryStage(input, complexity)
        // Handed into a junction the driver is already committed to. COMMIT is
        // silent whichever way it was reached (rule 2): moving the camera at
        // the moment they are turning is the pump, and the envelope they are
        // holding was chosen for a junction of at least this complexity
        // anyway. Hold it, and let the NEXT index change decide.
        if (want == Stage.COMMIT) {
            stage = Stage.COMMIT
            return Decision.Noop
        }
        stage = Stage.HANDOFF
        val lift = maxOf(envelopeLift, liftForStage(want, complexity))
        val target = baseZoom + lift
        if (Math.abs(target - assertedZoom) < HANDOFF_REANCHOR) {
            // Nothing to move. Keep the envelope; the camera is already there.
            envelopeLift = lift
            return Decision.Noop
        }
        return transition(Stage.HANDOFF, baseZoom, lift)
    }

    /**
     * Step a maneuver the camera was HANDED into rather than approached.
     *
     * Same ladder, one extra rule: the envelope may tighten, never open. A
     * stage whose own zoom is below what is already held is simply held
     * through — which is why a chain of easy junctions after a hard one reads
     * as one steady look-ahead instead of four small lunges.
     */
    private fun fromHandoff(input: Input, baseZoom: Double): Decision {
        val want = entryStage(input, complexity)
        return when {
            // The junction receded past the point where a recovery would fit —
            // a projection swing, or a link the gap did not show. The chain is
            // over; give the road back. The hysteresis margin is what keeps a
            // fix sitting on the boundary from doing this.
            want == Stage.CRUISE &&
                input.distanceToManeuverM > recoverAt(input, complexity) + HYSTERESIS_M -> {
                stage = Stage.RECOVER
                transition(Stage.RECOVER, baseZoom)
            }
            // Still inside the handoff, or between the window and the runway:
            // hold, silently.
            want == Stage.CRUISE -> Decision.Noop
            want == Stage.COMMIT -> {
                stage = Stage.COMMIT
                Decision.Noop
            }
            liftForStage(want, complexity) > envelopeLift + HANDOFF_REANCHOR -> {
                stage = want
                transition(want, baseZoom)
            }
            else -> Decision.Noop
        }
    }

    private fun onSameIndex(input: Input, baseZoom: Double): Decision {
        if (stage == Stage.HANDOFF) return fromHandoff(input, baseZoom)
        val want = entryStage(input, complexity)
        val cur = stage
        return when {
            // RECOVER's target IS the band, so once the recovery write has
            // fired, reaching CRUISE needs nothing.
            cur == Stage.RECOVER && want == Stage.CRUISE -> {
                stage = Stage.CRUISE
                Decision.Noop
            }
            want == cur -> Decision.Noop
            want.ordinal > cur.ordinal -> {
                if (want == Stage.COMMIT) {
                    // Hold: the FRAME write already put the zoom where it
                    // needs to be, and writing now is how cameras pump.
                    stage = Stage.COMMIT
                    Decision.Noop
                } else {
                    stage = want
                    transition(want, baseZoom)
                }
            }
            // Retreat — only over the hysteresis margin, so a fix at the
            // threshold cannot flip the camera. The retreat MOVES the stage
            // but never re-fires a write: the write log stays, which is what
            // stops a distance oscillation in a dense trace from re-firing
            // ANTICIPATE four times over one junction.
            else -> {
                if (input.distanceToManeuverM > entryDistance(cur, input) + HYSTERESIS_M) {
                    val back = prevStage(cur)
                    if (back != cur) {
                        stage = back
                        if (back == Stage.CRUISE) return Decision.Noop
                        return transition(back, baseZoom)
                    }
                }
                Decision.Noop
            }
        }
    }

    /**
     * Write one eased transition, once per stage per maneuver index.
     *
     * The single place the envelope moves: every write re-anchors it onto the
     * [baseZoom] this fix implies, so the lift is always a lift over the speed
     * the driver is actually doing rather than the one they were doing at the
     * top of the chain.
     */
    private fun transition(
        s: Stage,
        baseZoom: Double,
        lift: Double = liftForStage(s, complexity),
    ): Decision {
        val perManeuver = writtenByIndex.getOrPut(lastIndex) { mutableSetOf() }
        if (!perManeuver.add(s)) return Decision.Noop
        envelopeLift = lift
        assertedZoom = baseZoom + lift
        return Decision.Transition(
            stage = s,
            targetZoom = assertedZoom,
            durationMs = if (s == Stage.RECOVER) RECOVER_DURATION_MS else ANTICIPATE_DURATION_MS,
        )
    }

    /** The driver's finger, or a reset: Vector is holding nothing. */
    private fun surrenderEnvelope() {
        envelopeLift = 0.0
        assertedZoom = 0.0
    }

    // -------------------------------------------------------------------
    // Pure helpers (also the unit-test surface)
    // -------------------------------------------------------------------

    /** The 0..3 complexity score for a maneuver, latched at entry. */
    fun complexityOf(input: Input): Int {
        complexityPin?.let { return it.coerceIn(0, 3) }
        var c = 0
        when (input.maneuverType) {
            "roundabout", "uturn", "turn-left", "turn-right" -> c += 2
            "slight-left", "slight-right" -> c += 1
        }
        if (input.laneUseful) c += 1
        if ((input.approachLanes ?: 0) >= 4) c += 1
        val compoundAt = (input.speedMs * COMPOUND_SECONDS).coerceAtLeast(MIN_COMPOUND_M)
        val gap = input.nextManeuverGapM
        if (gap != null && gap <= compoundAt) c += 1
        return c.coerceIn(0, 3)
    }

    /**
     * Where FRAME begins: a DISTANCE, widened by complexity and by speed.
     *
     * Never below [FRAME_MIN_M], which is above [TURN_M] — so the window
     * `(TURN_M, frameAt]` always has real width at every speed. See the
     * constants' comment for the defect this shape exists to prevent.
     */
    fun frameAt(input: Input, complexity: Int): Double =
        maxOf(
            FRAME_BASE_M + FRAME_COMPLEXITY_M * complexity,
            input.speedMs * FRAME_SECONDS,
        ).coerceIn(FRAME_MIN_M, FRAME_MAX_M)

    /**
     * Where ANTICIPATE begins: the same shape, further out, and always at least
     * [MIN_STAGE_GAP_M] beyond [frameAt] so the two stages cannot collapse.
     */
    fun anticipateAt(input: Input, complexity: Int): Double =
        maxOf(
            ANTICIPATE_BASE_M + ANTICIPATE_COMPLEXITY_M * complexity,
            input.speedMs * ANTICIPATE_SECONDS,
        )
            .coerceAtLeast(frameAt(input, complexity) + MIN_STAGE_GAP_M)
            .coerceAtMost(ANTICIPATE_MAX_M)

    /** [anticipateAt] for a maneuver whose complexity has not been latched yet. */
    fun anticipateAt(input: Input): Double = anticipateAt(input, complexityOf(input))

    /** [frameAt] for a maneuver whose complexity has not been latched yet. */
    fun frameAt(input: Input): Double = frameAt(input, complexityOf(input))

    /**
     * The stage the current distance demands, ignoring the state machine.
     *
     * Takes the LATCHED complexity rather than recomputing it: the compound
     * "then" term is `speedMs * 4`, so a recomputing ladder would move its own
     * boundaries as the driver slowed into the junction it is measuring.
     */
    fun entryStage(input: Input, complexity: Int): Stage = when {
        input.distanceToManeuverM <= TURN_M -> Stage.COMMIT
        input.distanceToManeuverM <= frameAt(input, complexity) -> Stage.FRAME
        input.distanceToManeuverM <= anticipateAt(input, complexity) -> Stage.ANTICIPATE
        else -> Stage.CRUISE
    }

    /** [entryStage] for a maneuver whose complexity has not been latched yet. */
    fun entryStage(input: Input): Stage = entryStage(input, complexityOf(input))

    /** The zoom each stage aims at, relative to the speed-band baseline. */
    fun zoomForStage(s: Stage, baseZoom: Double, complexity: Int): Double =
        baseZoom + liftForStage(s, complexity)

    /**
     * How far above the speed band each stage sits.
     *
     * Pure in the complexity for every stage the distance ladder can ask for.
     * [Stage.HANDOFF] is the exception and cannot be otherwise: a handoff has
     * no zoom of its own, it carries the one it inherited, so it answers with
     * the envelope this instance is holding.
     */
    fun liftForStage(s: Stage, complexity: Int): Double = when (s) {
        Stage.CRUISE, Stage.RECOVER -> 0.0
        Stage.ANTICIPATE -> ANTICIPATE_LIFT + ANTICIPATE_COMPLEXITY_LIFT * complexity
        Stage.FRAME, Stage.COMMIT -> FRAME_LIFT + FRAME_COMPLEXITY_LIFT * complexity
        Stage.HANDOFF -> envelopeLift
    }

    /**
     * How far out a completed maneuver has to leave the NEXT one for a
     * recovery to be worth writing.
     *
     * The whole of the next maneuver's anticipation window, plus a runway of
     * road the driver actually gets to see open up. Inside this the camera
     * hands off instead; see the constants for the measurement.
     */
    fun recoverAt(input: Input, complexity: Int): Double =
        anticipateAt(input, complexity) +
            maxOf(input.speedMs * RECOVER_RUNWAY_SECONDS, RECOVER_RUNWAY_MIN_M)

    /** True when a RECOVER fits before the next maneuver wants the camera. */
    fun recoveryHasRunway(input: Input, complexity: Int): Boolean =
        input.distanceToManeuverM > recoverAt(input, complexity)

    private fun entryDistance(s: Stage, input: Input): Double = when (s) {
        Stage.ANTICIPATE -> anticipateAt(input, complexity)
        Stage.FRAME -> frameAt(input, complexity)
        Stage.COMMIT -> TURN_M
        else -> 0.0
    }

    private fun prevStage(s: Stage): Stage = when (s) {
        Stage.COMMIT -> Stage.FRAME
        Stage.FRAME -> Stage.ANTICIPATE
        Stage.ANTICIPATE -> Stage.CRUISE
        else -> Stage.CRUISE
    }
}