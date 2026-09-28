package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.ManeuverAnnouncer
import dev.vector.geo.RouteTracker
import dev.vector.geo.Units

/**
 * What the app is doing, as one value.
 *
 * A navigator has three genuinely different screens and the previous version
 * had none of them — it could render a map and nothing else, with no way to
 * choose a destination. Modelling the phase explicitly is also what keeps the
 * UI from stacking a search bar, a route card and a turn banner on top of each
 * other, which is the "overlapping bubbles" problem: every element below is
 * owned by exactly one phase.
 */
enum class Phase {
    /** Free look. Search bar, my-location button. */
    EXPLORE,

    /** A destination is chosen and a route is drawn, but we are not driving it. */
    PREVIEW,

    /** Driving. Maneuver card, speed, ETA, exit. */
    NAVIGATING,
}

/**
 * What the receiver is doing, as a thing the driver can be told.
 *
 * ## The defect this exists because of
 *
 * Vector had no representation of GPS health at all, and the consequence was
 * the worst possible one: **when the fixes stopped, nothing on the screen
 * changed.** [RouteTracker] correctly stops dead-reckoning after three seconds
 * without a fix — sailing a vehicle confidently down a road on no evidence is
 * worse than freezing it — but `NavSession.onFrame` then returned no actions,
 * so the HUD went on showing the last maneuver, the last countdown and an ETA
 * that was quietly becoming fiction. The vehicle simply stopped moving while
 * every number around it stayed authoritative-looking.
 *
 * A driver in a tunnel or a multi-storey car park cannot distinguish that from
 * "the app thinks I have stopped", and the difference decides whether they
 * trust the next instruction. Both reference products say so explicitly: Google
 * Maps greys the vehicle and prints "Searching for GPS", Waze says "GPS signal
 * lost".
 *
 * ## Why four states and not a boolean
 *
 * [WEAK] is a different fact from [LOST] and needs a different response. A fix
 * with 40 m of claimed accuracy is still a fix — the map should keep following
 * it — but it is not something to reroute on, and telling the driver the signal
 * is poor explains a puck that is wandering. Collapsing the two would either
 * hide the degradation or declare a loss that has not happened.
 *
 * [ACQUIRING] is separate from [LOST] for the same reason: "not yet" and "not
 * any more" are different, and only one of them is worth an apology.
 */
enum class GpsHealth {
    /** No fix has arrived yet this session. */
    ACQUIRING,

    /** Fixes are arriving and the receiver's own accuracy is usable. */
    GOOD,

    /** Fixes are arriving, but the receiver says they are poor. */
    WEAK,

    /** No fix for longer than [GPS_STALE_MS]. The vehicle is frozen. */
    LOST,

    /**
     * No fix has EVER arrived, for long enough that one is not coming.
     *
     * ## The defect this exists because of, reported from the S24
     *
     * *"There is this issue with it always showing searching for GPS."*
     *
     * [ACQUIRING] is the honest state for the first few seconds of a launch,
     * and it had no time limit — so a device that was never going to produce a
     * position showed **"Searching for GPS / Waiting for the first position
     * fix" forever**, with a search box that refused every destination
     * ("Waiting for a GPS fix before routing") and no explanation of why. The
     * app was, correctly, waiting; it just had no way to say that the waiting
     * had stopped being reasonable.
     *
     * On the handset the cause was outside the app — a previous replay had left
     * GMS's fused provider stuck in mock mode, so it produced nothing for any
     * app on the device (see `MockDrive.releaseStaleMockMode`). But the class
     * of cause is not the point and neither is that particular one: location
     * turned off system-wide, a revoked permission, a GMS that will not
     * service the request, an emulator with no location configured, all reach
     * the same place. §3.B asks that recovery not require an unnecessary
     * restart, and the previous behaviour did not even tell the driver a
     * restart was worth trying.
     *
     * Distinct from [LOST], which means fixes were arriving and stopped — a
     * tunnel. This means none ever came, which is a different sentence and a
     * different thing for the driver to do about it.
     */
    UNAVAILABLE,
    ;

    /** True when the driver should be told something is wrong with positioning. */
    val degraded: Boolean get() = this != GOOD

    /**
     * What to put on screen, or null when there is nothing to say.
     *
     * Short and in the driver's terms. "Searching for GPS" rather than
     * "ACQUIRING", and nothing at all for [GOOD] — a permanent "GPS OK"
     * indicator is chrome competing with the instruction band for the same
     * attention, which V5 §9 rules out.
     */
    val message: String?
        get() = when (this) {
            GOOD -> null
            ACQUIRING -> "Searching for GPS"
            WEAK -> "Weak GPS signal"
            LOST -> "GPS signal lost"
            UNAVAILABLE -> "No position available"
        }
}

/**
 * How long without a fix before positioning counts as lost.
 *
 * Chosen against [RouteTracker]'s `reckonMaxS` of 3 s, which is when the puck
 * actually freezes, plus enough margin that an ordinary 1 Hz stream dropping
 * one or two updates — which happens constantly — does not raise a warning. A
 * warning that appears every few minutes on a healthy drive is one the driver
 * learns to ignore, and then it is not there when the tunnel comes.
 */
const val GPS_STALE_MS = 5_000L

/**
 * Claimed accuracy past which a fix is [GpsHealth.WEAK].
 *
 * The receiver's own 68% radius. 30 m is roughly where a fix stops being able
 * to tell which of two parallel Doha carriageways you are on, which is the
 * point at which saying so is useful rather than pedantic.
 */
const val GPS_WEAK_ACCURACY_M = 30.0

/**
 * How long to wait for the FIRST fix before saying one is not coming.
 *
 * Twenty seconds. Measured against this handset's own numbers: `dumpsys
 * location` reports a mean time-to-first-fix of 1.28 s with a standard
 * deviation of 0.42 s over 116 reports, so twenty seconds is roughly forty
 * standard deviations out — long enough that it cannot fire on a cold start
 * indoors, short enough that a driver has not yet decided the app is broken.
 *
 * Only ever applies before the first fix. Once positioning has worked,
 * [GPS_STALE_MS] and [GpsHealth.LOST] own the "it stopped" case, which is a
 * different fact.
 */
const val GPS_ACQUIRE_PATIENCE_MS = 20_000L

data class Maneuver(
    val index: Int,
    val type: String,
    val instruction: String,
    /** Distance from this maneuver to the NEXT one. */
    val legM: Double,
    /** Distance from the route start to this maneuver. */
    val cumulativeM: Double,
    val turnLanes: String? = null,
    val exitRef: String? = null,
    val destination: String? = null,
    /**
     * The road this leg is driven on, on its own.
     *
     * `VectorApi.Step` has carried this since V1 — the backend's own KDoc
     * there says "a client cannot recover 'شارع الكورنيش' by parsing 'Continue
     * on شارع الكورنيش', and the backend has the name in hand" — and the
     * Step -> Maneuver mapping in `MainActivity` then dropped it on the floor.
     *
     * V4 needs it because the banner no longer shows a sentence. Both
     * reference products show `[distance] / [road name]` with the *action*
     * carried entirely by the arrow, and a sentence cannot be set at 31 sp: the
     * old card rendered "Turn left onto Suhaim Bin Hamad Street" at 17 sp over
     * two lines, which made the most important text on the driving screen the
     * smallest text on it.
     */
    val road: String? = null,
    /**
     * Lane count under the vehicle as it approaches, when the map has one.
     *
     * Carried from `VectorApi.Step` so the ribbon/highlight can be lane-true
     * without a tile join; absent means the map has no count.
     */
    val approachLanes: Int? = null,
    /**
     * Backend lane provenance + the lanes resolved for the direction driven
     * (V7 Stage 1). Null for backends that predate the field.
     */
    val laneData: VectorApi.LaneData? = null,
) {
    /**
     * The lane spec in the driver's left-to-right order.
     *
     * The backend resolves OSM's way-ordered tag into driver order in
     * `laneData.turnLanes`; older backends only ever sent the raw wire string,
     * which is correct for the overwhelmingly common one-way forward case.
     */
    val laneSpec: String?
        get() = laneData?.turnLanes ?: turnLanes

    /**
     * Lanes in the direction driven, when the backend could say (V7 Stage 4).
     *
     * Distinct from [approachLanes], which on a two-way way counts BOTH
     * directions — see [VectorApi.LaneData.forwardLanes]. Null is an answer:
     * it means the lateral position of this carriageway is unknown and the
     * route must stay on the centreline rather than guess a side.
     */
    val forwardLanes: Int?
        get() = laneData?.forwardLanes

    /**
     * Lanes to highlight for this maneuver, empty when there is no useful advice.
     *
     * Computed ONCE here, at route application, rather than in a getter that
     * re-parses the string on every recomposition — the strip reads this value
     * every frame.
     */
    val lanes: List<dev.vector.geo.LaneGuidance.Lane> =
        dev.vector.geo.LaneGuidance.usefulLanes(laneSpec, type)

    /**
     * Why the strip is empty, when it is: no data / no lane decision
     * ([UNKNOWN]) versus data that narrows nothing ([NONE_USEFUL]).
     * Distinguishing the two lets a lane layer on the map know whether it may
     * still draw the carriageway lane-true.
     */
    val laneStatus: dev.vector.geo.LaneGuidance.Status =
        dev.vector.geo.LaneGuidance.statusOf(laneSpec, type)
}

/**
 * One of several routes to the same destination.
 *
 * The backend has answered `/route?alternatives` since V1 and the web client
 * has shown them as chips since ADR-0062; the native client asked for a single
 * route and never mentioned that others existed. A driver who knows the city
 * often knows which way they want to go, and offering nothing is the difference
 * between a navigator and a route dispenser.
 *
 * [label] is what distinguishes it on screen. Distance and time alone do not:
 * "12 min · 11.0 km" against "13 min · 11.2 km" tells the driver nothing about
 * WHICH road, so the label carries the longest road the route uses that the
 * others do not.
 */
data class RouteOption(
    val geometry: List<LngLat>,
    val distanceM: Double,
    val durationS: Double,
    val maneuvers: List<Maneuver>,
    val snapMaxM: Double,
    val label: String,
    /** See [VectorApi.Route.signals]. The chosen route's own signal list. */
    val signals: List<VectorApi.Signal> = emptyList(),
    /** See [VectorApi.Route.cameras]. The chosen route's own camera list. */
    val cameras: List<VectorApi.Camera> = emptyList(),
    /** See [VectorApi.Route.originSnapM]. Not the same number as [snapMaxM]. */
    val originSnapM: Double = snapMaxM,
)

/**
 * What a completed journey amounts to.
 *
 * ## Why arrival needed more than a status line
 *
 * Before V5 arriving produced exactly one thing: the word "Arrived at
 * Villaggio Mall" in a 12 sp grey pill, on an otherwise empty map, and the
 * journey was gone. Reported from the S24 as needing work, and it is the
 * weakest moment in the product — the end of a drive is when a driver has
 * questions ("did that take as long as it said?", "where do I actually park?")
 * and Vector answered none of them while holding every number needed to.
 *
 * ## The honest form of the arrival-intelligence direction
 *
 * `VECTOR-PRO-RESCOPED.md` governs all Pro copy and forbids shipping arrival
 * intelligence as a claimed feature. Nothing here claims one. [parking] is a
 * real corridor query against the real index — the same `/along` the
 * search-along-route feature uses — and when the index has no parking near the
 * destination the card says so rather than inventing a suggestion. That is the
 * architectural seam V5 §14 asks to be kept open, filled with the one thing
 * that is actually true today.
 *
 * @property plannedS what the router predicted when the route was chosen.
 * @property actualS what the drive measured. The pair is also posted to
 *   `/eta`, which has existed since issue 07 for exactly this and which no
 *   client had ever called — the routing service's own comment reads "called
 *   on navigation completion".
 */
data class ArrivalSummary(
    val name: String,
    val atMs: Long,
    val distanceM: Double,
    val plannedS: Double,
    val actualS: Double,
    /** Parking found near the destination. Null while the query is in flight. */
    val parking: List<VectorApi.Place>? = null,
) {
    /**
     * How the drive compared with the plan, or null when there is nothing to
     * say.
     *
     * Withheld inside [ETA_HONEST_MARGIN_S] either way, because below that the
     * difference is GPS timing and the driver's own stop at a light rather
     * than the router being right or wrong — and a navigator that announces it
     * was "12 seconds out" is talking about itself.
     */
    val etaVerdict: String?
        get() {
            val d = actualS - plannedS
            if (kotlin.math.abs(d) < ETA_HONEST_MARGIN_S) return "as predicted"
            val mins = Math.round(kotlin.math.abs(d) / 60.0).toInt().coerceAtLeast(1)
            return if (d > 0) "$mins min longer than predicted"
                   else "$mins min quicker than predicted"
        }
}

/**
 * Difference between predicted and actual travel time worth mentioning.
 *
 * 90 seconds. Below it the gap is one traffic light and the moment the driver
 * happened to tap Start, not a statement about the routing engine.
 */
const val ETA_HONEST_MARGIN_S = 90.0

data class UiState(
    val phase: Phase = Phase.EXPLORE,
    val status: String = "",

    /**
     * True while [status] describes something in flight.
     *
     * Separate from `status.isNotEmpty()` because the same line also carries
     * finished statements ("Arrived at Villaggio Mall"), and a spinner beside
     * one of those would say the app is still working when it has stopped.
     */
    val busy: Boolean = false,
    val error: String? = null,

    // Search
    val searching: Boolean = false,
    val query: String = "",
    val results: List<VectorApi.Place> = emptyList(),
    /**
     * A query is in flight.
     *
     * Needed once search became live: with results arriving as the driver
     * types, "no results yet" and "no results at all" look identical, and the
     * second one has to be said out loud.
     */
    val searchInFlight: Boolean = false,
    /** At least one query has completed for the current text. */
    val searched: Boolean = false,

    /**
     * Where the driver is, for the UI's own use.
     *
     * The Activity has always tracked this privately. It is in state so the
     * results list can say how far away each result is — the thing that
     * separates four branches of the same chain from each other, and which was
     * simply absent.
     */
    val myLocation: LngLat? = null,

    // Route
    val destination: LngLat? = null,
    val destinationName: String = "",
    val routeDistanceM: Double = 0.0,
    val routeDurationS: Double = 0.0,
    val maneuvers: List<Maneuver> = emptyList(),
    val snapWarningM: Double? = null,
    /**
     * How far the route's endpoints had to move to reach the road network.
     *
     * Distinct from [snapWarningM], which is only set past a display threshold:
     * this is always the measured value, because [NavSession] uses it to decide
     * whether a reroute could help at all.
     */
    val routeSnapMaxM: Double? = null,
    /**
     * How far the road network was from where the CURRENT route was planned.
     *
     * [routeSnapMaxM] is the worse of the two endpoints, so on most journeys it
     * describes the destination. That made it the wrong input to
     * [NavSession]'s "could a reroute help" test, which is a question about
     * where the *driver* is — and using it meant a destination in a mall car
     * park switched rerouting off for the whole drive. Kept as state rather
     * than only inside NavSession because a reroute replaces it, so it is part
     * of what describes the route currently being driven.
     */
    val routeOriginSnapM: Double? = null,

    // Live
    /**
     * Current speed, or null when the receiver has not reported one.
     *
     * Nullable since V4. It was `Int = 0`, so before the first fix with a
     * speed field the HUD read a confident **`0 km/h`** — see
     * `v4-evidence/vector/before-04-navigating.png`, where the app is parked
     * and says 0, and `gmaps/05-nav-hud.png`, where Google Maps in the same
     * situation says `--`.
     *
     * `0` and "not known" are different facts and only one of them was
     * representable. They matter differently to a driver: `0` beside a speed
     * limit means "you are stopped", which is information; a fabricated `0`
     * while rolling means the readout is broken and the driver has no way to
     * tell which they are looking at.
     */
    val speedKmh: Int? = null,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val currentManeuver: Maneuver? = null,
    val nextManeuver: Maneuver? = null,
    val distanceToManeuverM: Double = 0.0,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,

    /**
     * What the receiver is doing. See [GpsHealth] for the defect this closes.
     *
     * Starts at [GpsHealth.ACQUIRING] rather than [GpsHealth.GOOD]: before the
     * first fix the app knows nothing about where it is, and claiming a good
     * signal it has not received is the same class of fabrication as the
     * `0 km/h` speedometer V4 removed.
     */
    val gps: GpsHealth = GpsHealth.ACQUIRING,

    /**
     * The receiver's own claimed accuracy for the last fix, in metres.
     *
     * Kept because it is the only evidence available for [gps] being
     * [GpsHealth.WEAK], and because `Location.accuracy` has been on every fix
     * since V1 and was read by nothing.
     */
    val gpsAccuracyM: Double? = null,
    val showSteps: Boolean = false,
    val showSettings: Boolean = false,

    /**
     * The driver's persisted preferences.
     *
     * `voice`, `contributing` and `units` used to be loose fields on
     * this class, which meant the live value and the stored value were two
     * different things that had to be kept in step by hand. They are now
     * derived (below) from this one object, so "what the driver chose" has
     * exactly one representation.
     */
    val settings: Settings = Settings(),

    /** What the camera is doing. See [MapCamera]. */
    val cam: CameraState = CameraState(),

    /** Destinations from previous journeys, most recent first. */
    val recents: List<Recents.Entry> = emptyList(),

    /**
     * Drives the driver has actually driven, newest first. See [Drives].
     *
     * Distinct from [recents], which is a list of destinations that were
     * CHOSEN — including ones the driver looked at and backed out of. This is
     * the list of journeys that were driven, with what each one took.
     */
    val drives: List<Drives.Drive> = emptyList(),

    /**
     * Home and Work, when the driver has set them. See [Places].
     *
     * A list rather than a map so the order is fixed by the enum: two rows that
     * swap places when one is re-set is a list a driver has to read before
     * tapping.
     */
    val places: List<Places.Saved> = emptyList(),

    // ---- Vector Pro ------------------------------------------------------
    //
    // The default is UNCONFIGURED and that is deliberate: a `UiState()` built
    // in a test, or an app whose build carries no RevenueCat key, has FULL
    // access and NO upsell. Nothing has to be switched on for the app to work;
    // the paid tier is what switches on. See `pro/ProAccess.kt`.

    /** Whether this build has a paid tier, and whether this driver bought it. */
    val pro: dev.vector.android.pro.ProStatus = dev.vector.android.pro.ProStatus.UNCONFIGURED,

    /** What the store is selling, once RevenueCat has answered. */
    val proOffer: dev.vector.android.pro.ProOffer? = null,

    /**
     * Whether the store has answered at all, and whether it had anything.
     *
     * Defaults to [dev.vector.android.pro.ProOffering.READY] so that a state
     * built without a store in it is treated as *sellable* rather than
     * silently unsellable — the previews and unit tests construct `UiState`
     * directly and are asking about the tier's copy, not about a network.
     * `MainActivity` always overwrites this from
     * [dev.vector.android.pro.ProEntitlement.offering] before the first frame,
     * so no shipped surface ever sees the default.
     *
     * The opposite default would be worse than a footgun: `offering` gates
     * whether Pro is *advertised*, and defaulting it to "not sellable" would
     * make every un-updated test assert a product that has no paid tier.
     */
    val proOffering: dev.vector.android.pro.ProOffering =
        dev.vector.android.pro.ProOffering.READY,

    /**
     * What Pro actually unlocks in this binary.
     *
     * Defaulted from `ProCatalogue` — the real, shipped list — rather than read
     * from it at each use site, so that the empty-catalogue case and the
     * populated one are both reachable from a test without the catalogue
     * becoming mutable. The invariant it enforces is unchanged: the shipped
     * default is empty until a feature lands, and while it is empty
     * [offersPro] is false everywhere.
     */
    val proFeatures: List<dev.vector.android.pro.ProFeature> =
        dev.vector.android.pro.ProCatalogue.features,

    /**
     * The store this build sells through, once RevenueCat has been configured.
     *
     * RevenueCat's own type rather than one of Vector's: the SDK decides the
     * store from the API key prefix and exposes the answer, so a second enum
     * here would only be a way for the two to disagree. Null until `start()`
     * has run and in a build with no key — see `pro/ProStore.kt`, which is
     * written to say nothing specific rather than guess.
     */
    val proStore: com.revenuecat.purchases.Store? = null,

    /** The paywall is up. */
    val showPaywall: Boolean = false,

    /** A purchase or restore is in flight — the sheet's buttons are inert. */
    val proBusy: Boolean = false,

    /**
     * Something to tell the driver about the store, or null.
     *
     * Separate from [error], which is the navigation banner. A failed purchase
     * is not a navigation failure and must not be reported in the band that
     * says the route could not be planned.
     */
    val proNotice: String? = null,

    /**
     * Every route offered for the current destination, best first.
     *
     * Empty outside PREVIEW. The chosen one is always also the drawn one —
     * [routeDistanceM] and friends mirror `alternatives[chosenRoute]` — so the
     * HUD never has to know which list index it is describing.
     */
    val alternatives: List<RouteOption> = emptyList(),
    val chosenRoute: Int = 0,

    // Traffic contribution. OFF by default: adr-0068 makes collection opt-in,
    // and a default-on switch is not consent.
    // Waze's signature readout. Sourced from /speed, never from the route —
    // the limit applies to the road you are ON, which after a wrong turn is not
    // the road the route thinks you are on.
    val speedLimitKmh: Int? = null,

    /**
     * True when [speedLimitKmh] is the median for the road's CLASS rather than
     * a surveyed `maxspeed` tag.
     *
     * `/speed` has always reported `source: "tag" | "default"` and said in its
     * own docstring that "the client must honour it", and no client ever read
     * it — so a class default for a residential street has been drawn as a
     * posted sign since the sign existed. 90% of Qatar's ways carry no usable
     * `maxspeed`, so this is the common case, not the edge one.
     */
    val speedLimitInferred: Boolean = false,

    /**
     * The road the vehicle is on, and its route number.
     *
     * §13's "Context" tier, which Vector had no member of. Both reference
     * products put this under the vehicle as a map callout and it is the
     * cheapest possible answer to the question a driver asks most often. Comes
     * free with the speed-limit lookup — see [VectorApi.roadHere].
     */
    val roadName: String? = null,
    val roadRef: String? = null,
    /** Number of jammed segments currently on the map. Null = not yet loaded. */
    val jamCount: Int? = null,
    val probesSent: Int = 0,

    /**
     * The journey that just finished, or null.
     *
     * Not a [Phase]. The journey IS over — the route is cleared, the camera is
     * released, probe collection has stopped — and modelling arrival as a
     * fourth phase would have given every phase-owned element a fourth case to
     * answer for. It is a panel over EXPLORE, dismissed by the driver or by
     * starting anything else.
     */
    val arrival: ArrivalSummary? = null,

    // ---- signal-aware navigation (V7 Stage 5) -------------------------------

    /**
     * The next signal on the active route and what the model predicts for it,
     * evaluated on the ~1 Hz navigation tick — never on a display frame.
     *
     * Null when there is no route, no signal on it, or the last one was
     * passed. The [prediction]'s [Basis][dev.vector.geo.signal.Basis] is the
     * whole contract: with today's data it is always
     * [LOCATION][dev.vector.geo.signal.Basis.LOCATION] and the phase is
     * always [UNKNOWN][dev.vector.geo.signal.Phase.UNKNOWN] — the location
     * fact renders, the phase never does.
     */
    val signalAhead: SignalAhead? = null,

    // ---- speed-camera intelligence (V7.3) ----------------------------------

    /**
     * The next camera on the active route, evaluated on the ~1 Hz navigation
     * tick — never on a display frame.
     *
     * Null when there is no route, no camera on it, the vehicle is off route,
     * the last camera was passed, or the next camera is still further away
     * than the alert window ([CameraProfile.WARN_AHEAD_M]) — the alert is a
     * bounded window, so the HUD's second line goes back to the road name
     * outside it. The map still shows the camera's location; see Callouts.
     *
     * Carries the sentence and the remaining distance only; no field here can
     * claim the camera is active or that anyone will be fined — the words on
     * it come from [CameraText][dev.vector.geo.camera.CameraText], which
     * tests pin.
     */
    val cameraAhead: CameraAhead? = null,

    // ---- the Last Mile (V7 Phase 4) --------------------------------------

    /**
     * The whole trip, including the part after the car stops.
     *
     * Null until a destination is chosen and the walk has been planned, and
     * null again the moment the destination is cleared. A journey is composed
     * on destination select rather than on navigation start, because the
     * question it answers — "where will I actually end up, and how far is that
     * from the door" — is one a driver wants BEFORE committing to the route.
     */
    val journey: dev.vector.geo.journey.Journey? = null,

    /** True while the walk and the parking search are still in flight. */
    val journeyBusy: Boolean = false,

    /** The journey card is expanded to its three legs. */
    val journeyExpanded: Boolean = false,

    /**
     * The instant the shade is modelled for, or null to mean "now".
     *
     * This is the sun slider. It exists because the shade model is a pure
     * function of time, so letting the driver scrub it costs nothing and turns
     * a colour overlay into something visibly computed — and because a walk
     * planned at 09:00 for a 16:00 return is a real question in Doha.
     *
     * Held as an absolute instant rather than an offset so that a recomposition
     * an hour later does not silently move the answer.
     */
    val journeyTimeMs: Long? = null,

    /** The shadier walk, when one was found and is worth offering. */
    val coolerWalk: dev.vector.geo.journey.WalkLeg? = null,

    /**
     * The car park the cooler walk starts from.
     *
     * Carried beside [coolerWalk] because the cooler route is a different
     * WALK FROM A DIFFERENT CAR PARK, not a different path from the same one.
     * Without this the card kept naming the direct walk's car park while the
     * map drew the cooler one's, and the two disagreed on screen.
     */
    val coolerPark: dev.vector.geo.journey.ParkSpot? = null,

    /** Shade for [coolerWalk] at [journeyTimeMs]; recomputed when the slider moves. */
    val coolerShade: dev.vector.geo.sun.RouteShade? = null,

    /** What the cooler walk would buy, or an unavailable offer. */
    val coolerOffer: dev.vector.geo.journey.CoolerRoute.Offer =
        dev.vector.geo.journey.CoolerRoute.Offer.none,

    /** True when the driver has switched the walk leg to the cooler route. */
    val coolerChosen: Boolean = false,

    // ---- walking navigation (V7.4 4C final) --------------------------------

    /**
     * The live walking navigation state, or null.
     *
     * **Null for every car journey**, which is what keeps this stage off the
     * driving path: every walking surface below is composed only when this is
     * non-null, so a drive renders exactly the chrome it rendered before and
     * there is no path from the car loop into any of it.
     *
     * Produced by [WalkNavSession] and adopted whole on each fix. It carries
     * the [dev.vector.geo.walk.WalkInstruction] the banner renders and the
     * voice speaks — one object, so the two cannot disagree.
     */
    val walk: WalkNavState? = null,

    /**
     * Why there is no walking route, or null.
     *
     * Three distinct kinds, deliberately not collapsed into [error]: a
     * pedestrian network split is a statement about the MAP and is the COMMON
     * refusal on Qatar's foot graph, while a backend failure says nothing
     * about whether a walk exists. See
     * [dev.vector.geo.walk.WalkRefusalText].
     */
    val walkRefusal: dev.vector.geo.walk.WalkRefusalKind? = null,

    /** True while a walking route is being planned or replanned. */
    val walkBusy: Boolean = false,
) {
    // ---- derived from `settings` and `cam` -------------------------------
    //
    // Read-only on purpose. Changing a preference goes through
    // MainActivity.updateSettings, which persists it and rebuilds this state
    // from the stored value — so there is no path that changes the live value
    // and forgets to store it, which is what the previous loose fields allowed
    // (voice reset to ON at every launch for exactly that reason).

    /**
     * The road under the vehicle, as one label, or null.
     *
     * Combines [roadRef] and [roadName] the way a road sign does: "Q5 · Wadi
     * Mushaireb Street". The ref first because on a gantry it is the larger of
     * the two and the thing a driver matches at speed; the name second because
     * it is what a passenger says.
     *
     * Null when the geocoder found no road within its 60 m trust radius, which
     * is the honest answer off-road and in a car park — better than naming the
     * nearest street a hundred metres away.
     */
    val roadLabel: String?
        get() {
            val n = roadName?.takeIf { it.isNotBlank() }
            val r = roadRef?.takeIf { it.isNotBlank() }
            return when {
                r != null && n != null -> "$r · $n"
                n != null -> n
                r != null -> r
                else -> null
            }
        }

    val voice: VoiceMode get() = settings.voice
    val contributing: Boolean get() = settings.contributing
    val units: Units get() = settings.units

    /**
     * Which mode this journey is, as one value.
     *
     * Derived rather than stored, so it cannot disagree with [walk] — a mode
     * flag and a walking state that could contradict each other is exactly the
     * pair that ends up drawing a walking banner over a car route. Exactly two
     * modes, because `general` is the only routed walking profile (4B.4 D2).
     */
    val navMode: dev.vector.geo.walk.NavMode
        get() = if (walk != null) dev.vector.geo.walk.NavMode.FOOT
                else dev.vector.geo.walk.NavMode.CAR

    /** True while navigating on foot. */
    val walking: Boolean get() = walk != null

    /**
     * May this driver use a Pro feature?
     *
     * True for a subscriber AND for a build with no paid tier. Gate features on
     * this. Never gate on `pro == PRO`, which would lock a self-hosted build
     * out of its own features.
     */
    val hasPro: Boolean get() = dev.vector.android.pro.ProAccess.hasPro(pro)

    // ---- the walk currently being shown -----------------------------------
    //
    // The cooler route is a walk from a DIFFERENT car park, so choosing it
    // changes the park, the distance, the duration and the shade together.
    // Every surface that describes the walk must read the same one, or the
    // card contradicts the map. These three are that single source.
    //
    // [journey] stays the direct/default journey throughout: it is the
    // baseline the offer is computed against, so it must not be overwritten
    // when the driver toggles.

    /** True when the cooler walk is both chosen and actually available. */
    val coolerActive: Boolean get() = coolerChosen && coolerWalk != null

    /** The walk leg on screen right now. */
    val activeWalk: dev.vector.geo.journey.WalkLeg?
        get() = if (coolerActive) coolerWalk else journey?.walk

    /** The car park the walk on screen starts from. */
    val activePark: dev.vector.geo.journey.ParkSpot?
        get() = if (coolerActive) coolerPark else journey?.park

    /** Shade for the walk on screen, at the instant the sun slider is on. */
    val activeShade: dev.vector.geo.sun.RouteShade?
        get() = if (coolerActive) coolerShade else journey?.walkShade

    /**
     * Should any Pro upsell be drawn?
     *
     * Not the complement of [hasPro] — see `ProAccess.offersPro`. False while
     * `ProCatalogue` is empty, which is what keeps a paywall that sells nothing
     * out of the binary entirely.
     */
    val offersPro: Boolean
        get() = dev.vector.android.pro.ProAccess.offersPro(pro, proFeatures.size)

    /**
     * May a Pro surface carrying a price be drawn right now?
     *
     * Every upsell entry point and every "Included with Vector Pro" line keys
     * off this rather than off [offersPro], so an app whose store is
     * unreachable, or whose offering is empty, shows *no* Pro advertising
     * instead of advertising a purchase nobody can complete.
     *
     * It is deliberately NOT used for access. Feature locks stay on
     * `ProAccess.hasPro(pro)`: if this predicate ever gated a feature, a store
     * outage would hand the paid tier to every free driver. See
     * `ProAccess.sellable`.
     */
    val proSellable: Boolean
        get() = dev.vector.android.pro.ProAccess.sellable(
            pro, proFeatures.size, proOffering,
        )

    /**
     * The lanes to show right now, or empty.
     *
     * The distance rule lives here rather than on [Maneuver] because it needs
     * the driver's SPEED, which a maneuver does not carry. See
     * [dev.vector.geo.LaneGuidance.showAt] for why the window is a time budget
     * rather than a distance: it is how long it takes to cross two or three
     * lanes of traffic, which is what lane guidance is for.
     *
     * Before this the strip appeared as soon as the maneuver became current —
     * the moment the PREVIOUS one completed. On the 7.4 km leg of the live
     * router's Al Wakrah route that is seven kilometres of a lane diagram the
     * driver cannot act on, which is how a driver learns to stop looking at it.
     */
    val laneGuidance: List<dev.vector.geo.LaneGuidance.Lane>
        get() {
            val m = currentManeuver ?: return emptyList()
            val speed = (speedKmh ?: 0) / 3.6
            if (!dev.vector.geo.LaneGuidance.showAt(distanceToManeuverM, speed)) return emptyList()
            return m.lanes
        }

    /** True while the camera is locked to the vehicle. */
    val following: Boolean get() = cam.mode == CameraMode.FOLLOW

    val etaClockMinutes: Int get() = (remainingS / 60).toInt()

    /**
     * Time to go, as a driver reads it. Never "0 min".
     *
     * ## Three defects in one line of arithmetic
     *
     * The trip bar printed `"${etaClockMinutes} min"`, which is
     * `(remainingS / 60).toInt()`, and that is wrong in three ways:
     *
     * 1. **It says "0 min".** Reported from the S24. Under a minute out, the
     *    driver was told the journey takes no time, which is the one duration
     *    no journey has. Both reference products say "Arriving" here.
     * 2. **It truncates rather than rounds.** 119 seconds is "1 min", so a
     *    driver watching the number sees it hold for two minutes and then drop
     *    two. Rounding halves the worst error and is what the references do.
     * 3. **It has no hours.** A 27 km drive across Doha at 40 km/h reads
     *    "83 min", and nobody says that.
     *
     * "Arriving" is keyed off remaining DISTANCE rather than time, because
     * time is `distance / average speed` and the average comes from the
     * route's own plan — at a red light a hundred metres from the destination
     * the time is meaningless while the distance is exactly right.
     */
    val etaLabel: String
        get() {
            if (remainingM <= ARRIVING_LABEL_M) return "Arriving"
            val mins = Math.round(remainingS / 60.0).toInt()
            return when {
                mins < 1 -> "1 min"
                mins < 60 -> "$mins min"
                else -> {
                    val h = mins / 60
                    val m = mins % 60
                    if (m == 0) "$h h" else "$h h $m min"
                }
            }
        }

    /** True when the driver is over the posted limit by a real margin. */
    val overSpeedLimit: Boolean
        get() {
            val v = speedKmh ?: return false
            val limit = speedLimitKmh ?: return false
            // An INFERRED limit never raises the over-limit indicator. The
            // number is the median for the road's class, not a posted sign, so
            // flagging a driver for exceeding it would be accusing them of
            // breaking a limit nobody has surveyed. It is still SHOWN, marked
            // as unposted; it is just not enforced.
            if (speedLimitInferred) return false
            return v > limit + SPEED_TOLERANCE_KMH
        }
}

/**
 * The next signal ahead, as the loop's last tick understood it.
 *
 * Everything here is derived from navigation state the loop already kept —
 * no second position model, no network call, no camera involvement. The
 * [prediction] is a pure function of the signal's timing model (none, on
 * today's data), the arrival window and the clock, so this is one object a
 * review can point at and the UI can render without interpreting anything.
 */
data class SignalAhead(
    val signalId: String,
    /** Remaining distance along the route to the signal's approach position. */
    val distanceM: Double,
    /** Expected seconds and honest uncertainty built from nav state. */
    val arrival: dev.vector.geo.signal.ArrivalWindow,
    /** The phase claim, its basis and its confidence. UNKNOWN is a claim too. */
    val prediction: dev.vector.geo.signal.SignalPrediction,
)

/**
 * The next camera on the active route (V7.3 / V7.7).
 *
 * A camera warning is a LOCATION fact, the type the SOURCE stated, and a
 * distance: "Speed camera ahead · 400 m". Nothing here claims the camera is
 * active, enforcing, or that a fine is coming — no such claim exists anywhere
 * in the model, which is exactly why this object carries no field for one.
 *
 * [line] is the finished sentence, taken once from
 * [dev.vector.geo.camera.CameraText.line] so the banner, the map pill and the
 * voice are the same string by construction. [type] rides beside it because
 * the pill and the debug telemetry both want to say WHICH camera this is
 * without re-deriving it from the sentence.
 */
data class CameraAhead(
    val cameraId: String,
    /** What the source says this camera is. Never [CameraType.UNKNOWN] here. */
    val type: dev.vector.geo.camera.CameraType,
    /** The sentence for [type]: the one the voice says and the HUD shows. */
    val line: String,
    /** Remaining distance along the route to the camera's approach position. */
    val distanceM: Double,
    /** OSM `maxspeed` provenance, never rendered. See CameraText. */
    val maxspeedTag: String? = null,
)

/**
 * Is a route reply still the one the driver is waiting for?
 *
 * ## The race this closes
 *
 * `pickPoint` fires a route request and applies whatever comes back. Two
 * destinations chosen in quick succession — a mis-tap on a results list and an
 * immediate correction, which is the ordinary way to use a search box — put two
 * requests in flight, and the router answers in whatever order it answers. So
 * the FIRST destination's routes could land last and win: the map drew A's
 * route and A's alternatives while the destination chip, the recents entry and
 * the Home/Work save all said B.
 *
 * ## Why this is a function here and not an `if` in the Activity
 *
 * Because the `if` in the Activity is what was missing, and an `if` in the
 * Activity cannot be tested — `MainActivity` needs a GL surface and a fused
 * location provider. Every other async reply in that class already guards
 * itself, each with its own hand-written comparison, and this is the one that
 * did not. One named rule, asserted directly, is how it stays closed.
 *
 * @param current the destination the app is showing NOW.
 * @param requested the destination this reply was requested for.
 */
fun routeReplyStillWanted(current: LngLat?, requested: LngLat): Boolean =
    current == requested

/**
 * Is a reroute reply still the one the journey is waiting for?
 *
 * Stricter than [routeReplyStillWanted] by one condition, because a reroute
 * belongs to a JOURNEY rather than to a destination: it is only meaningful
 * while one is being driven.
 *
 * ## The defect
 *
 * A reroute takes as long as the router takes, and the driver can arrive, or
 * tap out, while it is in flight. The reply then **resurrected the journey it
 * belonged to** — it set the route on the tracker, drew it, and repopulated the
 * maneuvers — so the app sat in EXPLORE with a route on the map, an ETA
 * counting down, and no way to leave, because the exit control belongs to
 * NAVIGATING. That is §4's "what happens if the app leaves this state while the
 * request is open", and the answer was silent corruption.
 *
 * The destination is checked as well, so a reroute for the last journey cannot
 * land on the next one.
 */
fun rerouteReplyStillWanted(phase: Phase, current: LngLat?, requested: LngLat): Boolean =
    phase == Phase.NAVIGATING && current == requested

/**
 * Should the STATUS BAR draw dark icons?
 *
 * ## The defect, reported from the S24
 *
 * *"I cannot see the phone's icons on the notification panel at all when the
 * app is open — the wifi, mobile data and battery status."*
 *
 * Vector draws edge to edge, so the system's own status-bar icons are composited
 * over whatever Vector painted at the top of the screen, and the app has to say
 * which way round they should be drawn. It did — from the MAP THEME alone, and
 * that was correct until V4 made the maneuver banner full-bleed.
 *
 * The banner is deliberately **dark in both themes** (see `LightChrome`'s
 * `bannerBg`: a white band at the top of a white map has no edge, and Google
 * Maps makes the same call with its deep-teal band). So on a light-theme phone
 * while navigating, the app asked for dark icons — over a near-black banner.
 * Black on black. The icons were not missing; they were painted in the one
 * colour that could not be seen.
 *
 * This is the whole rule, as a pure function, because it is the kind of thing
 * that is obvious once stated and invisible in a screenshot taken in the other
 * theme.
 */
fun statusBarWantsDarkIcons(theme: VectorStyle.MapTheme, phase: Phase): Boolean =
    theme == VectorStyle.MapTheme.LIGHT && phase != Phase.NAVIGATING

/**
 * Should the NAVIGATION BAR draw dark icons?
 *
 * Follows the theme with no phase exception, because what sits at the bottom of
 * the screen is a panel in every phase — the trip bar while navigating, the
 * route card in preview, the controls in explore — and panels are light in the
 * light theme. Only the top of the screen has an element that refuses to
 * follow the theme.
 */
fun navBarWantsDarkIcons(theme: VectorStyle.MapTheme): Boolean =
    theme == VectorStyle.MapTheme.LIGHT

/**
 * Slack before "over the limit" is shown. GPS speed is noisy and speedometers
 * read a few km/h high by design; flagging 51 in a 50 would cry wolf constantly
 * and the driver would learn to ignore the indicator entirely.
 */
const val SPEED_TOLERANCE_KMH = 5

/**
 * Below this the vehicle is not travelling, so a deviation is not a wrong turn.
 *
 * 1.5 m/s is about 5 km/h — brisk walking. Chosen above GPS jitter at a
 * standstill (which routinely reads a metre per second of nothing) and well
 * below any speed at which a driver could actually miss a turn.
 */
const val MOVING_MS = 1.5

/**
 * Window over which speed is derived when the receiver does not report one.
 *
 * See `NavSession.effectiveSpeed`. Long enough that independent jitter averages
 * down — five seconds of 3 m noise is under a metre per second of apparent
 * travel — and short enough to notice a car pulling away from a junction.
 */
const val SPEED_WINDOW_MS = 5_000L

/**
 * Shortest window a derived speed is trusted over.
 *
 * Below this the denominator is small enough that a single noisy fix dominates,
 * so no speed is reported at all — which is the honest answer and the one the
 * nullable [UiState.speedKmh] exists to express.
 */
const val MIN_SPEED_WINDOW_S = 2.0

/**
 * Furthest a fix may sit from the route for the speed-limit lookup to ask about
 * the ROUTE's road rather than the nearest one. See `NavSession.roadProbePoint`.
 *
 * The same 40 m as `RouteTracker`'s default snap radius, and for the same
 * reason: inside it the tracker corrects the vehicle's position from the fix,
 * so it is already treating the route as the road the vehicle is on; outside
 * it the tracker holds its lock without believing the fix, and the lookup
 * should not believe the route either.
 */
const val ROAD_PROBE_MAX_OFFSET_M = 40.0

/**
 * How close to the end of the route counts as having arrived.
 *
 * Measured along the route, so this is "the last 25 metres of driving" rather
 * than a circle around the destination pin. Big enough to survive GPS jitter at
 * walking speed and to fire before the driver is past the door; small enough
 * that it cannot trigger while there is still a maneuver to perform.
 */
const val ARRIVAL_RADIUS_M = 25.0

/**
 * Remaining distance below which the trip bar says "Arriving".
 *
 * Comfortably more than [ARRIVAL_RADIUS_M], because the two answer different
 * questions: 25 m is where the journey has ENDED, and this is where telling
 * the driver a duration has stopped being useful. Both reference products
 * switch at roughly this distance, and it is about ten seconds at urban speed
 * — long enough to read.
 */
const val ARRIVING_LABEL_M = 120.0

/**
 * Arrival time as a clock reading.
 *
 * "arrive 14:32" is what a driver actually needs — it answers "will I make the
 * meeting", which "18 min" does not without doing arithmetic while driving.
 * Every consumer navigator leads with it.
 */
fun arrivalClock(
    remainingSeconds: Double,
    nowMillis: Long,
    zone: java.util.TimeZone,
    /**
     * Whether to write a 24-hour clock.
     *
     * The phone's setting, passed in rather than read here so this stays a pure
     * function the tests can drive without a Context. Every call site in the UI
     * resolves it from `DateFormat.is24HourFormat`; see `uses24HourClock()`.
     *
     * Defaults to true because that is what this function did before it had the
     * parameter, so a caller that has not been updated keeps its old output
     * rather than silently changing format.
     */
    use24h: Boolean = true,
): String {
    val cal = java.util.Calendar.getInstance(zone)
    cal.timeInMillis = nowMillis + (remainingSeconds * 1000).toLong()
    val minute = cal.get(java.util.Calendar.MINUTE)
    if (use24h) {
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), minute)
    }
    // `Calendar.HOUR` is 0..11, and 0 means twelve o'clock in both halves of
    // the day — midnight is "12:30 am" and noon is "12:30 pm", not "0:30".
    val hour12 = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
    val half = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "am" else "pm"
    // No leading zero on the hour in 12-hour form: "9:05 pm", not "09:05 pm".
    // That is the convention every 12-hour locale writes, and the leading zero
    // is what makes a 12-hour clock look like a mis-formatted 24-hour one.
    return String.format("%d:%02d %s", hour12, minute, half)
}

/**
 * Which maneuver is next, and how far away it is.
 *
 * Derived from along-route distance rather than from proximity to the maneuver
 * POINT: a route that passes near a later turn before reaching an earlier one
 * (a loop, a cloverleaf) would otherwise announce them out of order.
 */
fun upcomingManeuver(maneuvers: List<Maneuver>, alongM: Double): Pair<Maneuver?, Double> {
    for (m in maneuvers) {
        // The maneuver at cumulativeM is ahead of us while we are before it.
        if (m.cumulativeM > alongM + 1.0) {
            return m to (m.cumulativeM - alongM)
        }
    }
    val last = maneuvers.lastOrNull()
    return last to ((last?.cumulativeM ?: 0.0) - alongM).coerceAtLeast(0.0)
}

/** The one after the upcoming one, for the "then..." strip. */
fun followingManeuver(maneuvers: List<Maneuver>, upcoming: Maneuver?): Maneuver? {
    if (upcoming == null) return null
    val i = maneuvers.indexOfFirst { it.index == upcoming.index }
    return if (i >= 0 && i + 1 < maneuvers.size) maneuvers[i + 1] else null
}

/** A short glyph per maneuver type, for the turn card. */
fun maneuverGlyph(type: String): String = when (type) {
    "turn-left" -> "←"
    "turn-right" -> "→"
    "slight-left" -> "↖"
    "slight-right" -> "↗"
    "uturn" -> "↶"
    "roundabout" -> "↻"
    "arrive" -> "◉"
    "depart" -> "↑"
    else -> "↑"
}

/**
 * Distance as a driver reads it on a screen (not the spoken form).
 *
 * The rounding ladders moved to [Units], where the SPOKEN formatter also lives,
 * because the two must agree: a HUD reading "0.4 mi" beside a voice saying "in
 * 600 metres" is worse than having no units setting at all. This function stays
 * as the call-site shorthand and defaults to metric so a caller that has no
 * settings in hand still gets the old behaviour.
 */
fun shortDistance(m: Double, units: Units = Units.METRIC): String =
    units.shortDistance(m)

/**
 * Should the app hold the screen awake?
 *
 * ## The defect, reported from a drive
 *
 * *"The screen sometimes dims and turns off when doing a drive. I don't think
 * the maps is actively trying to keep the display on at times."*
 *
 * It was not trying at all. Nothing in the app ever set
 * `FLAG_KEEP_SCREEN_ON` or took a wake lock, so the display followed the
 * system timeout — typically 30 s — through an entire drive. Touching the
 * screen reset it, which is exactly why the report says *sometimes*: a driver
 * who adjusts the map keeps it alive by accident, and one who simply follows
 * the road watches it go dark on the approach to a turn. A navigator whose
 * screen is off at the moment it has something to say is not a navigator.
 *
 * ## Why [Phase.NAVIGATING] only
 *
 * The flag is a promise not to sleep, and the battery pays for it. It is worth
 * paying while the app is the reason the driver is looking at the phone at all,
 * and not otherwise: a phone left on the EXPLORE map in a pocket would never
 * sleep again, which is a worse bug than the one being fixed and harder to
 * attribute.
 *
 * [Phase.PREVIEW] is the genuine judgement call — a driver reading a route card
 * is stationary and may take longer than the timeout to decide. It is excluded
 * because the cost of being wrong is asymmetric: a screen that sleeps during
 * preview is an annoyance one tap undoes, and the driver is by definition not
 * yet moving.
 */
fun screenShouldStayOn(phase: Phase): Boolean = phase == Phase.NAVIGATING
