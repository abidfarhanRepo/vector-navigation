package dev.vector.geo.walk

import dev.vector.geo.Units

/**
 * When a walker is spoken to, and what is said (V7.4 4C final).
 *
 * ## What this is
 *
 * The walking counterpart of [dev.vector.geo.ManeuverAnnouncer], and pure for
 * the same reason: "did it announce the crossing twice?" and "did it go on
 * announcing the old route's turns after a reroute?" are exactly the questions
 * that cannot be answered by hand on a device and are trivial to answer in a
 * unit test.
 *
 * **It builds no wording of its own.** Every sentence it returns comes from a
 * [WalkInstruction], which is the same object the banner renders, so the voice
 * and the screen cannot describe different maneuvers. That is the structural
 * form of the brief's "banner and voice share one instruction source": there is
 * no second vocabulary here to drift out of step.
 *
 * ## Why the car announcer could not be reused unchanged
 *
 * It is not a walking/driving style preference — its constants are wrong on
 * foot by an order of magnitude, and its inputs do not exist:
 *
 *  * `minPrepareM = 250` is over **three minutes** at 1.35 m/s, and further
 *    than the median gap between two walking maneuvers (139.7 m on the real
 *    fixtures). The earliest stage would routinely fire before the previous
 *    maneuver was finished.
 *  * `TURN_M = 120` is 89 s of walking.
 *  * It scales its far stage with `speedMs`, and walking has no trustworthy
 *    speed — at 1.35 m/s a receiver's speed estimate is the same size as its
 *    own error, which is why [WalkFollower] measures movement as along-route
 *    progress instead.
 *  * It takes `Upcoming(index, type, instruction)` where `instruction` is the
 *    backend's localised sentence. The walking contract publishes **no prose**
 *    by design (4B.2), so there is no sentence to pass.
 *
 * The car announcer is **not modified and not subclassed**. Two loops, one
 * vocabulary each, and [VoiceGuide] — the platform engine — is shared.
 *
 * ## Deduplication is on maneuver IDENTITY, never on GPS fixes
 *
 * A stage fires at most once per plan entry, and stages never run backwards: a
 * walker already 5 m from a crossing is not then told about it at 30 m because
 * a noisy fix moved them back. The identity is the plan index, and
 * [onRouteReplaced] forgets every one of them — a rerouted walk renumbers its
 * plan from zero, so a surviving set would suppress the new route's first
 * instructions and announce nothing at all.
 */
class WalkVoice(
    /**
     * Units for the spoken distance.
     *
     * A `var` for the same reason [dev.vector.geo.ManeuverAnnouncer.units] is:
     * the setting can change mid-walk, and rebuilding the announcer to apply it
     * would clear the fired set and re-announce the maneuver the walker is in
     * the middle of.
     */
    var units: Units = Units.METRIC,
) {
    /** What an announcement is about. */
    enum class Event {
        /** The walk is starting. */
        DEPART,

        /** A maneuver is close enough to act on. */
        APPROACH,

        /** The maneuver is here. */
        NOW,

        /** The route's endpoint was reached. */
        ARRIVED,

        /** A sustained departure from the route was confirmed. */
        OFF_ROUTE,

        /** A new walking route is being requested. */
        REROUTING,

        /** The walker is back on the route without a reroute having landed. */
        REJOINED,

        /** No walking route could be produced. */
        REFUSED,
        ;

        /**
         * True when this announcement is a MANEUVER rather than a change of
         * state.
         *
         * The split exists so the existing [dev.vector.geo.walk] consumer can
         * apply the driver's own voice setting without a second vocabulary:
         * `VoiceMode.speaksManeuvers` governs the first group and
         * `speaksAlerts` the second, exactly as they already do for driving.
         * `ALERTS` is documented as the mode for "a commute you know by heart,
         * where the only thing worth interrupting you for is that something
         * has changed", and that is as true of a familiar walk.
         */
        val isManeuver: Boolean
            get() = this == DEPART || this == APPROACH || this == NOW
    }

    /**
     * One thing to say.
     *
     * [instruction] is carried for maneuver events so a caller can assert that
     * the spoken sentence and the banner came from the same object rather than
     * merely reading the same. Null for the state events, which are not about a
     * maneuver.
     */
    data class Announcement(
        val event: Event,
        val text: String,
        val instruction: WalkInstruction? = null,
    )

    /** Everything the cadence rules read, as one value. */
    data class Inputs(
        /** The walking state, or null when nothing is matched. */
        val progress: WalkProgress?,
        val followState: WalkFollowState,
        /** True once the route's endpoint has been reached. */
        val arrived: Boolean,
        /** True while a replacement route has been requested. */
        val rerouting: Boolean,
    )

    /** Highest stage ordinal fired per plan entry. See the class KDoc. */
    private val fired = HashMap<Int, Int>()
    private var departed = false
    private var arrivalSpoken = false
    private var offRouteSpoken = false
    private var rerouteSpoken = false
    private var refusalSpoken = false

    /** Reset for a new journey. Forgets everything, including arrival. */
    fun reset() {
        fired.clear()
        departed = false
        arrivalSpoken = false
        offRouteSpoken = false
        rerouteSpoken = false
        refusalSpoken = false
    }

    /**
     * The route was replaced. **Not** a new journey.
     *
     * Forgets every maneuver the OLD route announced, because the new plan
     * renumbers from zero and a surviving index would suppress a different
     * maneuver that happens to share it — the walking form of the defect
     * [dev.vector.android.NavSession.onRouteReplaced] documents for the car.
     *
     * The off-route and rerouting latches are cleared too, so a second genuine
     * departure later in the walk can speak again.
     *
     * Arrival deliberately survives: a reroute that lands after the walker has
     * arrived must not re-announce the arrival.
     */
    fun onRouteReplaced() {
        fired.clear()
        departed = false
        offRouteSpoken = false
        rerouteSpoken = false
        refusalSpoken = false
    }

    /**
     * The walk could not be planned or replanned.
     *
     * Spoken once per refusal rather than once per attempt, so a walker whose
     * reroute keeps failing is told once and then left alone. Cleared by
     * [onRouteReplaced], i.e. by a route actually landing.
     */
    fun refusal(text: String): Announcement? {
        if (refusalSpoken) return null
        refusalSpoken = true
        return Announcement(Event.REFUSED, text)
    }

    /**
     * What to say now, or null.
     *
     * The order of the checks is the priority order, and it is stated rather
     * than emergent:
     *
     *  1. **Arrival** beats everything. The walk is over; a turn instruction
     *     after it would be describing a route nobody is on.
     *  2. **Rerouting** beats a departure, because it is the more recent and
     *     more actionable fact — the walker has already been told they left the
     *     route, and what matters now is that Vector is fixing it.
     *  3. **Off-route** beats every maneuver, and while it holds, **no maneuver
     *     is announced at all**. That is the brief's "stop giving stale turn
     *     instructions if they are no longer trustworthy": the plan describes a
     *     route this walker is demonstrably not on, so its turns are not
     *     instructions any more.
     *  4. **[WalkFollowState.UNCERTAIN] speaks nothing new and retracts
     *     nothing.** It is the honest "I cannot tell" band, and both announcing
     *     a departure and announcing the next turn would be claims the state
     *     exists precisely to withhold.
     */
    fun update(inputs: Inputs): Announcement? {
        if (inputs.arrived) {
            if (arrivalSpoken) return null
            arrivalSpoken = true
            return Announcement(Event.ARRIVED, ARRIVED)
        }
        // Nothing is said after arrival, whatever else happens.
        if (arrivalSpoken) return null

        if (inputs.rerouting) {
            if (rerouteSpoken) return null
            rerouteSpoken = true
            return Announcement(Event.REROUTING, REROUTING)
        }

        when (inputs.followState) {
            WalkFollowState.OFF_ROUTE -> {
                if (offRouteSpoken) return null
                offRouteSpoken = true
                return Announcement(Event.OFF_ROUTE, OFF_ROUTE)
            }
            WalkFollowState.UNCERTAIN -> return null
            WalkFollowState.ACQUIRING -> return null
            WalkFollowState.ON_ROUTE -> Unit
        }

        // Back on the route after a confirmed departure, without a reroute
        // having landed. Worth one sentence: the walker was told they had left
        // the route, and leaving that statement standing while they follow it
        // again is the app failing to notice they fixed it themselves.
        if (offRouteSpoken) {
            offRouteSpoken = false
            rerouteSpoken = false
            return Announcement(Event.REJOINED, REJOINED)
        }

        val progress = inputs.progress ?: return null

        // The departure line, once, at the start of the walk.
        if (!departed) {
            departed = true
            val depart = progress.active?.takeIf { it.kind == WalkManeuverKind.DEPART }
                ?: progress.next?.takeIf { it.kind == WalkManeuverKind.DEPART }
            val instruction = WalkInstructions.of(depart)
            // A route whose plan has no `depart` still gets a start line: the
            // short real fixture is 52.9 m of depart-and-arrive, and a walk
            // that begins in silence reads as a walk that failed to begin.
            return Announcement(
                Event.DEPART,
                instruction?.spoken(WalkVoiceStage.NOW, units) ?: DEPART,
                instruction,
            )
        }

        val focus = focusOf(progress) ?: return null
        if (!announceable(focus.kind)) return null

        val stage = when {
            focus.distanceM <= NOW_M -> WalkVoiceStage.NOW
            focus.distanceM <= APPROACH_M -> WalkVoiceStage.APPROACH
            else -> return null
        }

        // Once per stage, and never backwards: a walker 5 m from a crossing is
        // not told about it at 30 m because a fix wandered.
        val already = fired[focus.planIndex]
        if (already != null && already >= stage.ordinal) return null
        fired[focus.planIndex] = stage.ordinal

        val instruction = WalkInstructions.of(focus) ?: return null
        return Announcement(
            if (stage == WalkVoiceStage.NOW) Event.NOW else Event.APPROACH,
            instruction.spoken(stage, units),
            instruction,
        )
    }

    /**
     * Which event this fix is about.
     *
     * A span the walker is INSIDE wins over whatever is next, because being on
     * a crossing is what they are doing — the same tie-break
     * [WalkRoute.progressAt] documents, applied to speech. Without it, stepping
     * onto a 156 m crossing would immediately start announcing the maneuver on
     * the far side of it.
     */
    private fun focusOf(progress: WalkProgress): WalkEvent? {
        val active = progress.active
        if (active != null && active.kind.isSpan) return active
        return progress.next
    }

    /**
     * Is this kind worth speaking about?
     *
     * `continue` is excluded for the reason the camera excludes it
     * ([WalkCameraPolicy.significant]): it is a way-identity change the walker
     * does nothing about, and on a Qatari walk the plan is full of them.
     *
     * `depart` is handled above, once. `arrive` is excluded here and announced
     * solely from the session's own [Inputs.arrived] flag, so there is exactly
     * one place arrival can be spoken from and it cannot fire twice.
     */
    private fun announceable(kind: WalkManeuverKind): Boolean = when (kind) {
        WalkManeuverKind.CONTINUE,
        WalkManeuverKind.DEPART,
        WalkManeuverKind.ARRIVE,
        -> false
        else -> true
    }

    companion object {
        /**
         * Distance at which a maneuver is announced as happening now.
         *
         * 10 m, about 7 s of walking. A pedestrian acts on an instruction
         * within a pace or two — there is no lane to change and no traffic to
         * merge into — so the "now" stage is genuinely at the kerb.
         */
        const val NOW_M = 10.0

        /**
         * Distance at which a maneuver is first announced.
         *
         * 30 m, about 22 s of walking. Derived from the measured maneuver
         * spacing rather than chosen: the 10th percentile gap between two real
         * walking maneuvers is **33.5 m**, so a warning at 30 m fits inside
         * nine tenths of the real legs without firing before the previous
         * maneuver is finished. The car's equivalent floor is 250 m, which on
         * foot would sit two maneuvers back.
         *
         * Deliberately under the camera's [WalkCameraPolicy.APPROACH_ENTER_M]
         * of 60 m: the frame widens to show the junction first, and the voice
         * arrives once it is on screen.
         */
        const val APPROACH_M = 30.0

        /** Spoken when the plan carries no `depart` to render. */
        const val DEPART = "Starting your walk"

        /**
         * Spoken once when the route's endpoint is reached.
         *
         * "You have arrived" is deliberately **not** used: the walk ends where
         * the pedestrian network ends, and `snap_max_m` records how far that
         * was from what was asked for. Claiming arrival AT the destination is a
         * claim the route cannot support — see [WalkNavSession.Action.Arrived].
         */
        const val ARRIVED = "You have reached the end of the walking route"

        /**
         * Spoken once when a sustained departure is confirmed.
         *
         * States the fact and nothing else. It does not say "turn around",
         * because Vector does not know which way the walker is facing — the
         * follower refuses device heading on foot for measured reasons — and it
         * does not name a direction back to the route, because the route back
         * is a route, and asking for one is what rerouting is.
         */
        const val OFF_ROUTE = "You have left the walking route"

        /** Spoken once when a replacement route is requested. */
        const val REROUTING = "Finding a new walking route"

        /** Spoken once when the walker rejoins without a reroute landing. */
        const val REJOINED = "Back on the walking route"
    }
}
