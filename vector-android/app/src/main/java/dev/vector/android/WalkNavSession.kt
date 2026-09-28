package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.Units
import dev.vector.geo.walk.WalkCameraDecision
import dev.vector.geo.walk.WalkCameraInputs
import dev.vector.geo.walk.WalkCameraPolicy
import dev.vector.geo.walk.WalkCameraRegime
import dev.vector.geo.walk.WalkFix
import dev.vector.geo.walk.WalkFollowState
import dev.vector.geo.walk.WalkFollower
import dev.vector.geo.walk.WalkInstruction
import dev.vector.geo.walk.WalkInstructions
import dev.vector.geo.walk.WalkProgress
import dev.vector.geo.walk.WalkPuck
import dev.vector.geo.walk.WalkPuckState
import dev.vector.geo.walk.WalkRoute
import dev.vector.geo.walk.WalkVoice

/**
 * The walking navigation loop (V7.4 4C.2, completed by 4C final).
 *
 * ## What this is
 *
 * The walking counterpart of [NavSession], and deliberately the same shape:
 * it takes a fix and the current camera state, and returns the next walking
 * state plus a list of [Action]s for the Activity to perform. No Android
 * types, no MapLibre, no I/O — so the decisions that are hard to check on a
 * device are checkable on the JVM.
 *
 * [NavSession] is **not modified and not subclassed**. The two loops share the
 * camera AUTHORITY (`MapCamera`, `CameraState`, `CameraGate`) and the platform
 * voice engine ([VoiceGuide]), and nothing else, because almost everything else
 * about them differs: speed bands, heading gates, reroute cooldowns, lane
 * offsets and speed alerts are all either meaningless or wrong at 1.35 m/s.
 * Folding walking into the car loop would have meant a mode conditional at
 * every one of those, which is how a loop that works becomes a loop nobody can
 * reason about.
 *
 * ## Where the camera authority is reused rather than rebuilt
 *
 * Three existing mechanisms are used exactly as the car uses them, and the
 * brief's "do not create a second camera authority" is what they are for:
 *
 *  1. **[CameraState] / [CameraMode]** — FOLLOW/FREE/OVERVIEW, the manual
 *     bearing, and `takenOverAtMs` are the same values the car path sets, so
 *     `MapCamera.onPan` / `onRotate` / `onRecenter` / `autoResume` all work on
 *     a walk with no walking-specific code at all.
 *  2. **[MapCamera.bearingFor]** — decides north-up versus heading-up and
 *     honours a hand-rotated map. The walking policy supplies the direction of
 *     TRAVEL; this function decides what the camera does with it, so a walker
 *     who prefers north-up keeps it.
 *  3. **[CameraGate]** — the Activity's existing transition/follow interlock.
 *     A walking camera write is an ordinary eased transition and goes through
 *     `applyCamera` like every other one.
 *
 * ## What 4C final added, and what it still refuses to do
 *
 * Added: the human-facing [WalkInstruction] on the state (one object, read by
 * both the banner and the voice), [Action.Speak], and [Action.Reroute] with
 * the gating described on [rerouteCouldHelp].
 *
 * Still refused: this class draws nothing, speaks nothing itself, and makes no
 * network call. It returns decisions; the Activity performs them.
 */
class WalkNavSession(
    route: WalkRoute,
    follower: WalkFollower = WalkFollower(route),
    private val policy: WalkCameraPolicy = WalkCameraPolicy(),
    private val voice: WalkVoice = WalkVoice(),
    /**
     * Shortest gap between two reroute requests.
     *
     * 12 s, against the car's 8. A walker covers 16 m in it, so a second
     * request that soon would be asking about essentially the same place — and
     * the pedestrian graph's answer would be the same, because the failure
     * that produces repeated requests is a walker standing somewhere the foot
     * network cannot represent (the pedestrian form of the S24 defect
     * [NavSession.rerouteCouldHelp] documents). Longer than the car's because
     * the cost of waiting is proportionally smaller at walking pace.
     */
    private val rerouteCooldownMs: Long = 12_000L,
    /**
     * How long a CONFIRMED departure must persist before a reroute is asked
     * for, on top of the follower's own 6 s.
     *
     * 4 s, so ~10 s and ~13 m of travel from the first fix past the off-route
     * radius. The follower's window defends against a single reflected fix;
     * this one defends against the case the brief names separately — a walker
     * who steps off the route and comes straight back before a reroute would
     * have helped. Ten seconds is long enough to walk back to a path one is
     * standing beside, and short enough that someone who has genuinely gone
     * the wrong way has not yet gone far.
     */
    private val rerouteAfterOffRouteMs: Long = 4_000L,
) {
    /**
     * The active route.
     *
     * A `var` because a reroute REPLACES it. Everything derived from it — the
     * follower, the progress latch, the camera policy's regime, the voice's
     * per-maneuver dedup — is rebuilt together in [replaceRoute], so there is
     * no window in which the plan and the geometry disagree.
     */
    var route: WalkRoute = route
        private set

    private var follower: WalkFollower = follower

    /** Side effects the Activity owns. Returned rather than performed. */
    sealed interface Action {
        /**
         * Draw the walker at a MATCHED position on the route.
         *
         * [confident] is false while the fix is in the uncertain band — still
         * the best available answer, but not freshly confirmed. A renderer may
         * show that; it may not pretend the difference does not exist.
         */
        data class Puck(
            val position: LngLat,
            val bearingDeg: Double?,
            val confident: Boolean,
        ) : Action

        /**
         * Draw the walker at the RAW fix, unmatched.
         *
         * Emitted when the follower has no match — off-route, or before the
         * first lock. Moving an unmatched position onto a route the walker is
         * not demonstrably on would be fabrication, which is the same refusal
         * `NavSession` makes in its own OffRoute and Idle branches.
         */
        data class RawPuck(val position: LngLat) : Action

        /**
         * Move the map camera.
         *
         * Carries the parts rather than a [CameraTarget] because two of them
         * are legitimately absent: a null [bearingDeg] means "leave the
         * bearing alone" (the off-route regime asserts none), exactly as a
         * null [CameraTarget.zoom] already means "leave the zoom alone". The
         * Activity assembles the target from the live camera.
         */
        data class Camera(
            val position: LngLat,
            val bearingDeg: Double?,
            val tiltDeg: Double,
            val zoom: Double,
            val durationMs: Int,
            val regime: WalkCameraRegime,
        ) : Action

        /**
         * Say something.
         *
         * The text always comes from [WalkVoice], which builds it from the
         * same [WalkInstruction] the banner renders — so a spoken sentence and
         * a printed one cannot describe different maneuvers. The Activity
         * hands it to the existing [VoiceGuide]; there is no second TTS stack.
         */
        data class Speak(val text: String) : Action

        /**
         * Ask the backend for a new WALKING route from here.
         *
         * `/foot`, never the car's `/route`. The two answer different
         * questions over different graphs, and a driving line returned for a
         * walking request would claim a person can walk a carriageway.
         */
        data class Reroute(val from: LngLat) : Action

        /**
         * The route's endpoint has been reached. Emitted exactly once.
         *
         * A STATE, not a claim about the destination. It says the walker
         * reached the end of the geometry the backend returned — which is all
         * the route can support, and specifically not "you have arrived at the
         * place you were looking for", because the walk ends where the
         * pedestrian network ends and `snap_max_m` says how far that was from
         * what was asked for.
         */
        data object Arrived : Action
    }

    data class Result(val state: WalkNavState, val actions: List<Action>)

    /** Arrival fires once per route, not once per fix. */
    private var arrived = false

    /** When the current confirmed departure began, or null. */
    private var offRouteSinceMs: Long? = null

    /** When the last reroute was requested. Nullable, never a 0 sentinel. */
    private var lastRerouteAtMs: Long? = null

    /** True between asking for a replacement route and getting an answer. */
    private var rerouting = false

    /**
     * The route's shade fact, computed once per route.
     *
     * Null until the first fix, which is the walk's clock — see
     * [WalkRoute.shadeAt] for why it is not recomputed per fix. Nulled by
     * [replaceRoute] and [reset] so a new route is rescored against its own
     * timeline rather than inheriting the previous one's.
     */
    private var shade: dev.vector.geo.walk.WalkShade? = null

    private var lastState = WalkNavState()

    /** The state as of the last fix. */
    val state: WalkNavState get() = lastState

    /** Units for spoken distances. Follows the driver's setting mid-walk. */
    var units: Units
        get() = voice.units
        set(value) { voice.units = value }

    /** Reset for a new route or a new journey. */
    fun reset() {
        arrived = false
        offRouteSinceMs = null
        lastRerouteAtMs = null
        rerouting = false
        follower.reset()
        policy.reset()
        voice.reset()
        shade = null
        lastState = WalkNavState()
    }

    /**
     * A replacement route landed. **Not** a new journey.
     *
     * Every piece of route-derived state is rebuilt together, which is what
     * makes the swap atomic: there is no fix at which the new geometry is
     * being followed while the old plan is still being announced.
     *
     *  * the **follower** is rebuilt, because its lock, its progress latch and
     *    its off-route clock all refer to the old geometry;
     *  * the **camera policy** is reset, so the new route's first fix decides
     *    its own regime instead of inheriting `OFF_ROUTE` from the departure
     *    that caused the reroute;
     *  * the **voice** forgets every maneuver the old route announced, because
     *    a new plan renumbers from zero — a surviving index would suppress a
     *    different maneuver that happens to share it, and the walker would be
     *    told nothing at the first turn of the new route.
     *
     * The reroute **cooldown deliberately survives**, for the reason
     * [NavSession.onRouteReplaced] gives: clearing it lets a walker the router
     * cannot match deviate, reroute, deviate and reroute at the GPS rate,
     * which is the storm the cooldown exists to prevent and exactly when it is
     * most needed.
     *
     * Arrival is cleared: the new route has its own endpoint to reach.
     */
    fun replaceRoute(next: WalkRoute) {
        route = next
        follower = WalkFollower(next)
        policy.reset()
        voice.onRouteReplaced()
        arrived = false
        offRouteSinceMs = null
        rerouting = false
        // The new route has its own geometry, its own length and therefore its
        // own timeline against the same sun. It is scored on the next fix,
        // which is the first instant available to score it from.
        shade = null
        lastState = WalkNavState()
    }

    /**
     * A reroute request failed, and why.
     *
     * The walker keeps the route they have — a refusal is not a reason to
     * discard the only guidance available — but the request is over, so the
     * cooldown governs when the next one may be made. Returns the one thing to
     * say, or null when it has already been said.
     */
    fun onRerouteFailed(kind: dev.vector.geo.walk.WalkRefusalKind): Action.Speak? {
        rerouting = false
        val a = voice.refusal(dev.vector.geo.walk.WalkRefusalText.spoken(kind)) ?: return null
        return Action.Speak(a.text)
    }

    /**
     * A GPS fix arrived.
     *
     * @param cam the camera state, so the gates below can be answered without
     *   this class holding a second opinion about who owns the camera.
     * @param phase the app phase. A walking camera only drives the map while
     *   actually navigating, the same rule `maybeManeuverCamera` applies.
     * @param speaksManeuvers whether the walker's voice setting permits
     *   maneuver speech, and [speaksAlerts] whether it permits state changes
     *   (off route, rerouting, arrival). The same two properties the car path
     *   reads off [VoiceMode], so one setting governs both modes.
     *
     *   The announcer still RUNS when they are false and its sentence is
     *   discarded, for the reason [NavSession.onFrame] gives: the per-maneuver
     *   dedup is state, so skipping the call would leave a stage un-fired and
     *   someone turning the voice back on mid-walk would hear a stale
     *   announcement.
     */
    fun onFix(
        fix: LngLat,
        nowMs: Long,
        cam: CameraState,
        phase: Phase,
        accuracyM: Double? = null,
        speaksManeuvers: Boolean = true,
        speaksAlerts: Boolean = true,
    ): Result {
        val walkFix = follower.onFix(fix, nowMs, accuracyM)
        val actions = mutableListOf<Action>()

        // The route's shade, scored once against the walk's own timeline.
        //
        // Computed from the FIRST fix and kept, because the fact describes the
        // walk's plan and not the walker's position — see
        // [dev.vector.geo.walk.WalkRoute.shadeAt]. A reroute nulls it, and the
        // next fix rescores the new geometry, so a replaced route never
        // inherits the sentence its predecessor earned.
        if (shade == null) shade = route.shadeAt(nowMs)

        val puck = WalkPuck.of(walkFix)
        if (puck != null) {
            actions.add(Action.Puck(puck.position, puck.bearingDeg, puck.confident))
        } else {
            actions.add(Action.RawPuck(fix))
        }

        val progress = walkFix.progress
        // The camera is consulted on EVERY fix, matched or not.
        //
        // ## The defect this shape exists because of
        //
        // It used to run only when there was a [WalkProgress], and a CONFIRMED
        // departure drops the route lock — so `progress` is null exactly when
        // the walker is off-route. The OFF_ROUTE regime was therefore
        // unreachable in the loop: built, unit-tested in the policy, and dead
        // in practice. [WalkCameraInputs.unmatched] is what lets the policy
        // answer without a position to answer from.
        val inputs = if (progress != null) {
            WalkCameraInputs.of(progress, walkFix.state)
        } else {
            WalkCameraInputs.unmatched(walkFix.state)
        }
        // The ownership question is asked ONCE and handed to the policy,
        // rather than being used to discard its answer afterwards. See
        // [WalkCameraPolicy.decide]: a policy that records writes which never
        // reached the map cannot restore the framing on recenter.
        val owned = cameraMayMove(cam, phase)
        val decision = policy.decide(inputs, owned)
        val regime = policy.currentRegime
        if (decision is WalkCameraDecision.Transition) {
            actions.add(
                Action.Camera(
                        // The best available position: the MATCHED one while
                        // there is a match, the raw fix when there is not.
                        //
                        // ## The defect this line exists because of
                        //
                        // It used to require a matched puck, which silently
                        // disabled the entire OFF_ROUTE camera regime — the
                        // wider framing whose whole purpose is to show someone
                        // who has left the route the ground between them and
                        // it. The regime was built, tested in the policy, and
                        // unreachable in practice.
                        //
                        // The car path has always done it this way: its
                        // `OffRoute` branch draws the puck at the raw fix and
                        // then calls `MapCamera.follow` with that same raw
                        // fix. Following an unmatched position is not
                        // fabrication — what would be fabrication is moving it
                        // ONTO the route, which is what `WalkPuck.of` still
                        // refuses to do.
                    position = puck?.position ?: fix,
                    // `bearingFor` is the existing authority: it honours a
                    // hand-rotated map and the north-up preference, so a
                    // walker who chose north-up keeps it. Null stays null
                    // (the off-route regime asserts no bearing).
                    bearingDeg = decision.bearingDeg?.let { MapCamera.bearingFor(cam, it) },
                    tiltDeg = decision.tiltDeg,
                    zoom = decision.targetZoom,
                    durationMs = decision.durationMs,
                    regime = decision.regime,
                )
            )
        }

        // Arrival, measured along the ROUTE rather than as the crow flies to
        // the destination pin — the same reasoning `NavSession` gives: a route
        // whose last metres double back past the destination would otherwise
        // report arrival with a maneuver still to make. One real fixture ends
        // with a uturn 4.1 m before arriving, so this is not hypothetical.
        if (!arrived && phase == Phase.NAVIGATING && progress != null &&
            walkFix.state != WalkFollowState.OFF_ROUTE &&
            progress.remainingM <= ARRIVAL_RADIUS_M
        ) {
            arrived = true
            actions.add(Action.Arrived)
        }

        // The off-route clock, for the ACTION threshold only. The follower
        // owns the STATE and this does not second-guess it — see
        // [rerouteCouldHelp] for why the two live at different layers.
        if (walkFix.state == WalkFollowState.OFF_ROUTE) {
            if (offRouteSinceMs == null) offRouteSinceMs = nowMs
        } else {
            offRouteSinceMs = null
        }

        // The instruction, built ONCE per fix and handed to both consumers.
        //
        // Suppressed entirely while off-route: the plan describes a route the
        // walker is demonstrably not on, so its next turn is not an
        // instruction any more. That is the brief's "stop giving stale turn
        // instructions if they are no longer trustworthy", and doing it here
        // rather than in the banner is what stops the voice and the screen
        // from disagreeing about it.
        val instruction = if (walkFix.state == WalkFollowState.OFF_ROUTE || arrived) null
        else WalkInstructions.of(focusOf(progress))

        if (phase == Phase.NAVIGATING) {
            val said = voice.update(
                WalkVoice.Inputs(
                    progress = progress,
                    followState = walkFix.state,
                    arrived = arrived,
                    rerouting = rerouting,
                )
            )
            val permitted = said != null &&
                if (said.event.isManeuver) speaksManeuvers else speaksAlerts
            if (permitted) actions.add(Action.Speak(said!!.text))
        }

        if (phase == Phase.NAVIGATING && rerouteCouldHelp(walkFix, nowMs)) {
            lastRerouteAtMs = nowMs
            rerouting = true
            actions.add(Action.Reroute(fix))
        }

        lastState = WalkNavState(
            followState = walkFix.state,
            progress = progress,
            puck = puck,
            regime = regime,
            arrived = arrived,
            offsetM = walkFix.offsetM,
            walking = walkFix.walking,
            instruction = instruction,
            rerouting = rerouting,
            shade = shade,
        )
        return Result(lastState, actions)
    }

    /**
     * Which event the banner and the voice are about.
     *
     * A span the walker is INSIDE wins over whatever is next, because being on
     * a crossing is what they are doing — the same tie-break
     * [WalkRoute.progressAt] documents. Without it, stepping onto a 156 m
     * crossing would immediately start describing the maneuver beyond it,
     * while the walker is standing in a road.
     */
    private fun focusOf(progress: WalkProgress?): dev.vector.geo.walk.WalkEvent? {
        if (progress == null) return null
        val active = progress.active
        if (active != null && active.kind.isSpan) return active
        return progress.next ?: active
    }

    /**
     * Would asking for a new walking route actually change anything?
     *
     * Four conditions, and each one is a defect that has been paid for
     * somewhere in this codebase:
     *
     *  1. **The departure is CONFIRMED.** [WalkFollowState.UNCERTAIN] is the
     *     honest "I cannot tell" band — a foreign footpath is within 15 m of
     *     the route at about half of all sampled points and a fix is up to
     *     30 m wide — and rerouting out of it would replace a correct route
     *     because of noise. The brief names this explicitly and the follower
     *     already models it; this reads the state rather than re-deriving one.
     *  2. **Sustained past [rerouteAfterOffRouteMs]**, on top of the
     *     follower's own 6 s window. One noisy fix cannot reach here, and
     *     neither can a walker who steps off and comes straight back.
     *  3. **The walker is moving over the GROUND.** [WalkFix.movingGround],
     *     not [WalkFix.walking].
     *
     *     ## The defect this line exists because of
     *
     *     It read `walkFix.walking` first, which is progress ALONG the route —
     *     and that is exactly zero for a walker who steps **perpendicularly**
     *     off the route, because their projection onto it does not move. So
     *     rerouting was unreachable for the single most common way of leaving
     *     a walking route: built, unit-tested in isolation, and dead in
     *     practice. `WalkNavSessionTest.nothing reroutes in 4C2` caught it by
     *     driving precisely that departure.
     *
     *     A stationary deviation is still a person standing somewhere rather
     *     than a wrong turn — the same test [NavSession.rerouteCouldHelp]
     *     applies, at the same layer, for the same reason. **Note the layer:**
     *     the follower deliberately does NOT gate the STATE on movement,
     *     because a walker who steps perpendicular off a path is precisely the
     *     person who is off-route.
     *  4. **The cooldown has expired and no request is already in flight.**
     */
    private fun rerouteCouldHelp(walkFix: WalkFix, nowMs: Long): Boolean {
        if (arrived) return false
        if (rerouting) return false
        if (walkFix.state != WalkFollowState.OFF_ROUTE) return false
        if (!walkFix.movingGround) return false
        val since = offRouteSinceMs ?: return false
        if (nowMs - since < rerouteAfterOffRouteMs) return false
        val last = lastRerouteAtMs
        return last == null || nowMs - last >= rerouteCooldownMs
    }

    /**
     * May the walking camera move the map right now?
     *
     * Every one of these is a CAMERA fact rather than a walking fact, which is
     * why they are answered here and not inside the pure policy — the same
     * split `maybeManeuverCamera` makes for the car, and the same list:
     *
     *  * not navigating — the map is the task, not a guidance surface;
     *  * FREE — the walker has panned away and is looking at something;
     *  * OVERVIEW — a deliberate "show me the whole route";
     *  * a hand-rotated map — turning the map to look at a junction is a
     *    question, and answering it by taking the map back is not an answer.
     *
     * **Following continues regardless.** The puck, the instruction and the
     * whole walking state are produced on every fix whatever the camera is
     * doing, so a walker who pans away still has their position, progress and
     * events updating — the defect `MapCamera.autoResume` exists because of (a
     * frozen map that kept counting down) must not be reintroduced on foot.
     *
     * Handing the camera back is [MapCamera.autoResume]'s job, unchanged and
     * unreimplemented: it is already driven from the Activity's fix path for
     * both modes.
     */
    private fun cameraMayMove(cam: CameraState, phase: Phase): Boolean {
        if (phase != Phase.NAVIGATING) return false
        if (cam.mode != CameraMode.FOLLOW) return false
        if (cam.manualBearing != null) return false
        return true
    }
}

/**
 * Everything the walking UI needs to render one moment, as one value.
 *
 * Reaches the screen through [UiState.walk], which is null for every car
 * journey — so a drive composes exactly the chrome it composed before this
 * stage, and the walking surface is unreachable from it.
 */
data class WalkNavState(
    val followState: WalkFollowState = WalkFollowState.ACQUIRING,
    val progress: WalkProgress? = null,
    val puck: WalkPuckState? = null,
    val regime: WalkCameraRegime = WalkCameraRegime.IDLE,
    val arrived: Boolean = false,
    /** Perpendicular distance from the route on the last fix. */
    val offsetM: Double? = null,
    /** Movement evidence. See [WalkFix.walking] — evidence, not a verdict. */
    val walking: Boolean = false,
    /**
     * What to tell the walker, or null when there is nothing trustworthy to
     * say.
     *
     * **The same object the voice speaks from.** Null while off-route and
     * after arrival, which is how the banner and the voice fall silent
     * together rather than one of them going on describing a route nobody is
     * following.
     */
    val instruction: WalkInstruction? = null,
    /** True between asking for a replacement route and getting an answer. */
    val rerouting: Boolean = false,
    /**
     * What the sun model says about this walk, or null before the walk has a
     * clock.
     *
     * ## Where it comes from, and why it is on the STATE rather than the
     * ## instruction
     *
     * [dev.vector.geo.walk.WalkRoute.shadeAt] — the route's own pure fact,
     * derived from the same 4B.4 contract as the progress above it and scored
     * along the walk's own timeline. It is deliberately NOT part of
     * [WalkInstruction]: the instruction vocabulary is closed and total, every
     * string in it comes from a backend fact, and the tests that grep every
     * rendered instruction for a shade claim (`WalkRealQatarUxTest.FORBIDDEN`,
     * `WalkUiTest`) are the structural guarantee that a maneuver can never
     * acquire one. Shade is a ROUTE property with a different provenance and a
     * different certainty, so it travels beside the instruction rather than
     * inside it, and the detail strip renders the two as separate lines.
     *
     * Null until the first fix, because the fact needs an instant and the fix
     * is where the session gets one — a walk that has not started has no
     * timeline to score.
     */
    val shade: dev.vector.geo.walk.WalkShade? = null,
) {
    /** Metres of route left, or null when nothing is matched. */
    val remainingM: Double? get() = progress?.remainingM

    /** True once a confirmed departure is in effect. */
    val offRoute: Boolean get() = followState == WalkFollowState.OFF_ROUTE

    /**
     * True while the follower cannot tell whether the walker is on the route.
     *
     * Surfaced as a quiet qualifier and never as an accusation — see
     * [WalkFollowState.UNCERTAIN]. The brief's rule is that this state must
     * not announce a departure, and the banner honours it by continuing to
     * show the instruction while marking the position not-confirmed.
     */
    val uncertain: Boolean get() = followState == WalkFollowState.UNCERTAIN

    /**
     * The three walk durations, straight from the frozen contract.
     *
     * `duration_s` remains pure pace time, `cost.cost_s` remains the weighted
     * selection cost, and `cost.crossing.wait_s` remains separate. **Nothing
     * is combined here or anywhere else** — see
     * [dev.vector.geo.walk.WalkEtaText], which renders the crossing wait as an
     * explicitly additive phrase rather than folding it into the walk time.
     */
    val eta: dev.vector.geo.walk.WalkEta?
        get() = progress?.route?.contract?.eta
}
