package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.ManeuverAnnouncer
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteTracker
import dev.vector.geo.signal.SignalMatcher
import dev.vector.geo.signal.SignalRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

/**
 * The GPS-fix → UI-state loop.
 *
 * This logic lived inside `MainActivity` next to MapLibre and FusedLocation,
 * which made it untestable — the riskiest code in the app sitting in the one
 * place a test could not reach. Lifting it into [NavSession] is what allows the
 * questions below to be asked at all:
 *
 *   * does a deviation reroute, and does the cooldown actually hold?
 *   * does the cooldown ever EXPIRE, or does the app silently stop rerouting?
 *   * does the ETA survive a red light, or divide by ~zero speed?
 *   * does the camera get yanked off a route preview by a stray fix?
 *
 * None of these need a handset, and every one of them is a bug a driver notices.
 */
class NavSessionTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    private fun pt(eastM: Double, northM: Double = 0.0) =
        LngLat(lng0 + eastM / kx, lat + northM / RouteGeometry.M_PER_DEG_LAT)

    /** A straight 2 km route east, shape points every 100 m. */
    private val route = (0..20).map { pt(it * 100.0) }

    private lateinit var tracker: RouteTracker
    private lateinit var session: NavSession

    @Before
    fun setUp() {
        tracker = RouteTracker()
        session = NavSession(tracker, ManeuverAnnouncer())
        tracker.setRoute(route)
    }

    private fun navigating() = UiState(
        phase = Phase.NAVIGATING,
        routeDistanceM = 2000.0,
        routeDurationS = 200.0,
        maneuvers = listOf(
            Maneuver(0, "depart", "Head east", 1000.0, 0.0),
            Maneuver(1, "turn-left", "Turn left onto شارع الكورنيش", 1000.0, 1000.0),
            Maneuver(2, "arrive", "Arrive at destination", 0.0, 2000.0),
        ),
    )

    private fun secs(s: Double) = (s * 1e9).toLong()

    // ---- rerouting ---------------------------------------------------------

    @Test
    fun `a deviation while navigating asks for a reroute`() {
        val r = session.onFix(navigating(), pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        assertTrue("expected a Reroute action, got ${r.actions}",
            r.actions.any { it is NavSession.Action.Reroute })
        assertTrue(r.ui.offRoute)
        assertTrue(r.ui.rerouting)
    }

    @Test
    fun `the cooldown stops a reroute storm`() {
        // A deviation persists across many fixes. Without a cooldown the app
        // would fire a reroute every single second.
        // Ten fixes spread over five seconds — INSIDE one 8 s cooldown window.
        // (Spanning ten seconds would legitimately produce two, since the
        // cooldown expires at eight; that is correct behaviour, not a storm.)
        var ui = navigating()
        var reroutes = 0
        for (i in 0 until 10) {
            val r = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(i * 0.5), i * 500L)
            ui = r.ui
            reroutes += r.actions.count { it is NavSession.Action.Reroute }
        }
        assertEquals("ten deviating fixes inside one cooldown must produce ONE reroute", 1, reroutes)
    }

    @Test
    fun `the cooldown expires, so a later deviation still reroutes`() {
        // The opposite failure, and the more dangerous one: a cooldown that
        // never lifts means the app silently stops rerouting for the whole trip.
        var ui = navigating()
        val first = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        ui = first.ui
        assertTrue(first.actions.any { it is NavSession.Action.Reroute })

        val later = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(20.0), 20_000L)
        assertTrue("a deviation 20 s later must reroute again",
            later.actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `a deviation during PREVIEW does not reroute`() {
        // The driver has not set off yet; wandering around before pressing Start
        // must not replan.
        val ui = navigating().copy(phase = Phase.PREVIEW)
        val r = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        assertTrue(r.actions.none { it is NavSession.Action.Reroute })
    }

    @Test
    fun `reset clears the cooldown between journeys`() {
        session.onFix(navigating(), pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        session.reset()
        val r = session.onFix(navigating(), pt(500.0, 200.0), 15.0, 90.0, secs(1.0), 1000L)
        assertTrue("a new journey must not inherit the previous cooldown",
            r.actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `returning to the route clears the off-route flag`() {
        var ui = session.onFix(navigating(), pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L).ui
        assertTrue(ui.offRoute)
        ui = session.onFix(ui, pt(600.0), 15.0, 90.0, secs(1.0), 1000L).ui
        assertTrue("back on the line, offRoute must clear", !ui.offRoute)
    }

    // ---- the frame loop ----------------------------------------------------

    @Test
    fun `a frame advances the puck and the camera while following`() {
        session.onFix(navigating(), pt(0.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val r = session.onFrame(navigating(), secs(0.5))
        assertTrue(r.actions.any { it is NavSession.Action.Puck })
        assertTrue(r.actions.any { it is NavSession.Action.Camera })
    }

    @Test
    fun `the camera is left alone once the driver grabs the map`() {
        session.onFix(navigating(), pt(0.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val r = session.onFrame(navigating().copy(cam = CameraState(mode = CameraMode.FREE)), secs(0.5))
        assertTrue("the puck must still move", r.actions.any { it is NavSession.Action.Puck })
        assertTrue("the camera must NOT be moved", r.actions.none { it is NavSession.Action.Camera })
    }

    @Test
    fun `the ETA survives a red light`() {
        // Derived from remaining DISTANCE at the route average, not from
        // instantaneous speed — which at a standstill divides by ~zero and
        // sends the ETA to infinity.
        session.onFix(navigating(), pt(1000.0), 0.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val r = session.onFrame(navigating(), secs(0.5))
        assertTrue("remainingS must be finite, got ${r.ui.remainingS}", r.ui.remainingS.isFinite())
        assertTrue("remainingS must be sane, got ${r.ui.remainingS}", r.ui.remainingS in 0.0..600.0)
        assertEquals(0, r.ui.speedKmh)
    }

    @Test
    fun `the upcoming maneuver and its countdown track progress`() {
        session.onFix(navigating(), pt(600.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val r = session.onFrame(navigating(), secs(0.1))
        assertEquals(1, r.ui.currentManeuver?.index)
        assertTrue("expected ~400 m to the turn, got ${r.ui.distanceToManeuverM}",
            r.ui.distanceToManeuverM in 350.0..450.0)
        assertEquals(2, r.ui.nextManeuver?.index)
    }

    @Test
    fun `voice announces the approaching turn`() {
        session.onFix(navigating(), pt(650.0), 20.0, 90.0, secs(0.0), 0L)
        // The announcement fires on the FIRST frame that sees the maneuver in
        // range, and each stage fires once — so collect across frames rather
        // than inspecting only the last one.
        val spoken = (0..3).flatMap {
            session.onFrame(navigating(), secs(it * 0.1)).actions
                .filterIsInstance<NavSession.Action.Speak>()
        }
        assertTrue("expected an announcement", spoken.isNotEmpty())
        assertTrue(spoken.first().text.contains("شارع الكورنيش"))
    }

    @Test
    fun `voice is silent when the driver has muted it`() {
        val muted = navigating().copy(settings = Settings(voice = VoiceMode.OFF))
        session.onFix(muted, pt(650.0), 20.0, 90.0, secs(0.0), 0L)
        val spoken = (0..3).flatMap {
            session.onFrame(muted, secs(it * 0.1)).actions
                .filterIsInstance<NavSession.Action.Speak>()
        }
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `nothing is drawn before a route lock exists`() {
        val fresh = NavSession(RouteTracker(), ManeuverAnnouncer())
        val r = fresh.onFrame(navigating(), secs(0.5))
        assertEquals(emptyList<NavSession.Action>(), r.actions)
    }

    @Test
    fun `an idle fix with no route still shows the vehicle`() {
        val fresh = NavSession(RouteTracker(), ManeuverAnnouncer())
        val r = fresh.onFix(UiState(phase = Phase.EXPLORE), pt(0.0), 10.0, 90.0, secs(0.0), 0L)
        assertTrue(r.actions.any { it is NavSession.Action.Puck })
        assertTrue(r.actions.any { it is NavSession.Action.Camera })
        assertEquals(36, r.ui.speedKmh)
    }

    @Test
    fun `a route preview is not yanked to the vehicle by a stray fix`() {
        // PREVIEW frames the whole route; following the car would undo that.
        val fresh = NavSession(RouteTracker(), ManeuverAnnouncer())
        val r = fresh.onFix(UiState(phase = Phase.PREVIEW), pt(0.0), 10.0, 90.0, secs(0.0), 0L)
        assertTrue(r.actions.none { it is NavSession.Action.Camera })
        assertTrue(r.actions.any { it is NavSession.Action.Puck })
    }

    // ---- arrival -----------------------------------------------------------
    //
    // Nothing detected arrival before this. The phases ran EXPLORE -> PREVIEW ->
    // NAVIGATING and only a manual tap left NAVIGATING, so a driver who arrived
    // kept a maneuver card reading "Arrive at destination, 0 m", a camera locked
    // at a navigation tilt, and a probe collection session still running after
    // the journey they consented to had ended.

    @Test
    fun `reaching the end of the route reports arrival`() {
        var ui = session.onFix(navigating(), pt(1990.0), 5.0, 90.0, secs(0.0), 0L).ui
        val r = session.onFrame(ui, secs(0.1))
        assertTrue("arriving must end the journey",
            r.actions.any { it is NavSession.Action.Arrived })
    }

    @Test
    fun `arrival is reported once, not on every frame`() {
        var ui = session.onFix(navigating(), pt(1990.0), 5.0, 90.0, secs(0.0), 0L).ui
        var arrivals = 0
        for (i in 1..10) {
            val r = session.onFrame(ui, secs(i * 0.1))
            ui = r.ui
            arrivals += r.actions.count { it is NavSession.Action.Arrived }
        }
        assertEquals("arrival must fire once per route, not once per frame", 1, arrivals)
    }

    @Test
    fun `mid-route is not arrival`() {
        val ui = session.onFix(navigating(), pt(1000.0), 15.0, 90.0, secs(0.0), 0L).ui
        val r = session.onFrame(ui, secs(0.1))
        assertTrue("half way along the route is not arrival",
            r.actions.none { it is NavSession.Action.Arrived })
    }

    @Test
    fun `a preview sitting at the destination does not report arrival`() {
        // The map is framing a route the driver has not started. Ending a
        // journey that never began would clear their route out from under them.
        val ui = session.onFix(
            navigating().copy(phase = Phase.PREVIEW), pt(1990.0), 0.0, 90.0, secs(0.0), 0L
        ).ui
        val r = session.onFrame(ui.copy(phase = Phase.PREVIEW), secs(0.1))
        assertTrue(r.actions.none { it is NavSession.Action.Arrived })
    }

    @Test
    fun `a new journey can arrive again`() {
        var ui = session.onFix(navigating(), pt(1990.0), 5.0, 90.0, secs(0.0), 0L).ui
        session.onFrame(ui, secs(0.1))
        session.reset()
        tracker.setRoute(route)
        ui = session.onFix(navigating(), pt(1990.0), 5.0, 90.0, secs(1.0), 1000L).ui
        val r = session.onFrame(ui, secs(1.1))
        assertTrue("the second journey must be able to arrive too",
            r.actions.any { it is NavSession.Action.Arrived })
    }

    // ---- a reroute replaces the route; it does not start a new journey ------

    @Test
    fun `a reroute does not clear the storm cooldown`() {
        // The regression this guards: `reroute()` used to call `session.reset()`
        // when the new route landed, which cleared the cooldown. A driver the
        // router cannot match — bad GPS, or a road the graph does not have —
        // would then deviate, reroute, deviate, reroute at the GPS rate, which
        // is exactly the storm the cooldown exists to prevent and exactly when
        // it is most needed.
        var ui = navigating()
        val first = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        ui = first.ui
        assertTrue(first.actions.any { it is NavSession.Action.Reroute })

        session.onRouteReplaced()          // the new route arrived

        val again = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(1.0), 1000L)
        assertTrue("a replaced route must not reset the reroute cooldown",
            again.actions.none { it is NavSession.Action.Reroute })
    }

    @Test
    fun `a reroute still lets the new route be announced`() {
        // The other half: the announcer MUST forget where it was, or the first
        // maneuver of the new route counts as already announced and the driver
        // is told nothing at all.
        var ui = navigating()
        session.onFix(ui, pt(0.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(ui, secs(0.1))     // primes the announcer
        session.onRouteReplaced()
        tracker.setRoute(route)
        ui = session.onFix(ui, pt(700.0), 20.0, 90.0, secs(1.0), 1000L).ui
        val spoken = mutableListOf<String>()
        for (i in 1..20) {
            val r = session.onFrame(ui, secs(1.0 + i * 0.1))
            ui = r.ui
            r.actions.filterIsInstance<NavSession.Action.Speak>().forEach { spoken.add(it.text) }
        }
        assertTrue("the replaced route's maneuver must still be announced: $spoken",
            spoken.isNotEmpty())
    }

    // ---- the reroute loop, reported from a real S24 Ultra -------------------
    //
    // "It keeps saying route updated through voice over." Parked ~80 m from the
    // nearest road, the app announced "Route updated" out loud every eight
    // seconds, indefinitely. The loop is STRUCTURAL, not a timing bug: the
    // driver is off-route, so it reroutes; the router snaps the new origin to
    // the same road 80 m away; the driver is still off-route; repeat. A
    // cooldown only sets the period of the loop — it cannot end it.

    @Test
    fun `a deviation while stationary does not reroute`() {
        // A deviation at a standstill is a car park, not a wrong turn.
        val ui = navigating().copy(routeSnapMaxM = 5.0)
        val r = session.onFix(ui, pt(500.0, 200.0), 0.0, 90.0, secs(0.0), 0L)
        assertTrue("a parked car must not trigger a reroute",
            r.actions.none { it is NavSession.Action.Reroute })
    }

    @Test
    fun `GPS jitter at a standstill does not reroute`() {
        val ui = navigating().copy(routeSnapMaxM = 5.0)
        val r = session.onFix(ui, pt(500.0, 200.0), 1.0, 90.0, secs(0.0), 0L)
        assertTrue(r.actions.none { it is NavSession.Action.Reroute })
    }

    @Test
    fun `a deviation while actually driving still reroutes`() {
        // The guard must not disable rerouting, which is the failure that
        // matters far more than the noise it is fixing.
        val ui = navigating().copy(routeSnapMaxM = 5.0)
        val r = session.onFix(ui, pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        assertTrue("a real deviation at speed must still reroute",
            r.actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `no reroute from a place the road network cannot represent`() {
        // The route was planned FROM here and its origin had to move 80 m to
        // reach the network, while off-route is 60 m. Being "off route" is the
        // permanent state of standing in this spot; a new route would snap to
        // the same place and be just as far away.
        //
        // Rewritten in V5. It used to assert this against `routeSnapMaxM`, the
        // worse of the two ENDPOINTS — see
        // `a far destination does not disable rerouting` for the defect that
        // encoded, and NavSession.rerouteCouldHelp for the reasoning. The
        // behaviour under test is unchanged; the evidence it is derived from is
        // now a measurement of where the driver is rather than of where they
        // are going.
        val here = pt(500.0, 200.0)
        session.noteRoutePlannedFrom(here, 80.0)
        val r = session.onFix(navigating(), here, 15.0, 90.0, secs(0.0), 0L)
        assertTrue("rerouting cannot help when the road is beyond the threshold",
            r.actions.none { it is NavSession.Action.Reroute })
    }

    @Test
    fun `driving away from an unmatchable place asks again`() {
        // The suppression is scoped to a PLACE, not to a journey. Otherwise a
        // driver who starts in a car park never gets a reroute for the rest of
        // the trip, which is the same defect in a different disguise.
        session.noteRoutePlannedFrom(pt(500.0, 200.0), 80.0)
        val r = session.onFix(navigating(), pt(900.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        assertTrue("400 m from the unmatchable spot is new information",
            r.actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `a far destination does not disable rerouting`() {
        // THE V5 DEFECT, as a regression test.
        //
        // `rerouteCouldHelp` compared `ui.routeSnapMaxM` — the WORSE of the two
        // endpoint snaps — against the off-route threshold. On almost every
        // journey the worse endpoint is the destination, because destinations
        // are malls, terminals and compound gates rather than kerbsides. So a
        // route to a car park 131 m from the nearest road switched rerouting
        // off for its ENTIRE length: a wrong turn twenty kilometres earlier at
        // 100 km/h produced no request at all.
        //
        // 131.8 m is not invented. It is `snap_max_m` from
        // `routes/long-airport-educity.json`, a real reply from the live stack,
        // and every reroute on that 27 km route was suppressed.
        val ui = navigating().copy(routeSnapMaxM = 131.8, routeOriginSnapM = 2.2)
        session.noteRoutePlannedFrom(pt(0.0), 2.2)
        val r = session.onFix(ui, pt(500.0, 200.0), 25.0, 90.0, secs(0.0), 0L)
        assertTrue("a distant destination must not stop the driver being rerouted",
            r.actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `the driver is still told they are off route`() {
        // What stops is the pointless request and the voice line, not the
        // indication. Hiding the deviation would be worse than the loop.
        val here = pt(500.0, 200.0)
        session.noteRoutePlannedFrom(here, 80.0)
        val r = session.onFix(navigating(), here, 0.0, 90.0, secs(0.0), 0L)
        assertTrue("offRoute must remain visible to the driver", r.ui.offRoute)
    }

    @Test
    fun `an unknown snap distance does not disable rerouting`() {
        // A route planned before the field existed, or a backend that does not
        // report it. Absence of evidence must not silently stop reroutes.
        session.noteRoutePlannedFrom(pt(0.0), null)
        val r = session.onFix(navigating(), pt(500.0, 200.0), 15.0, 90.0, secs(0.0), 0L)
        assertTrue(r.actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `coming back on route clears the unmatchable place`() {
        // Being back on the route proves the driver is not standing where the
        // network could not find them any more, whatever the distance says.
        val here = pt(500.0, 200.0)
        session.noteRoutePlannedFrom(here, 80.0)
        session.onFix(navigating(), pt(500.0), 15.0, 90.0, secs(0.0), 0L)
        val r = session.onFix(navigating(), here, 15.0, 90.0, secs(1.0), 1_000L)
        assertTrue("the suppression must not outlive the evidence for it",
            r.actions.any { it is NavSession.Action.Reroute })
    }

    // ---- speed the receiver did not report ---------------------------------
    //
    // `Location.hasSpeed()` is false for every network-provider fix and false
    // on some chipsets below walking pace. Vector read `speedMs ?: 0.0` and
    // compared it against MOVING_MS, so a receiver that never reports a speed
    // looked permanently parked and the app WOULD NEVER REROUTE — the most
    // user-visible navigation behaviour there is, disabled by a missing
    // optional field. See NavSession.effectiveSpeed.

    @Test
    fun `a receiver that never reports speed still reroutes`() {
        session.noteRoutePlannedFrom(pt(0.0), 2.0)
        // 25 m/s eastward, five fixes, no speed field on any of them.
        //
        // Every result is inspected, not only the last. The derived speed needs
        // MIN_SPEED_WINDOW_S of history before it is trusted — a two-second
        // wait, deliberately, because a shorter window is dominated by a single
        // noisy fix — so the reroute fires on the third fix and the cooldown
        // then correctly suppresses the fourth and fifth.
        val actions = (0..4).flatMap { i ->
            session.onFix(
                navigating(), pt(i * 25.0, 200.0), null, 90.0,
                secs(i.toDouble()), i * 1_000L,
            ).actions
        }
        assertTrue("a deviation at 25 m/s must reroute even with no speed field",
            actions.any { it is NavSession.Action.Reroute })
    }

    @Test
    fun `jitter at a standstill does not look like movement`() {
        // The reason the derived speed is a window and not a difference of two
        // fixes: 3 m between consecutive fixes is 3 m/s of apparent travel, and
        // at a standstill it is always noise. Over five seconds it averages
        // down below MOVING_MS.
        session.noteRoutePlannedFrom(pt(0.0), 2.0)
        val wobble = listOf(0.0, 2.5, -1.5, 2.0, -2.5, 1.0)
        val actions = wobble.withIndex().flatMap { (i, dx) ->
            session.onFix(
                navigating(), pt(500.0 + dx, 200.0), null, 90.0,
                secs(i.toDouble()), i * 1_000L,
            ).actions
        }
        assertTrue("GPS noise must not be read as driving",
            actions.none { it is NavSession.Action.Reroute })
    }

    @Test
    fun `a reported speed always wins over a derived one`() {
        // The derivation is a fallback, not a filter. A receiver saying 0 m/s
        // while its position wanders is reporting a stationary vehicle and must
        // be believed.
        session.noteRoutePlannedFrom(pt(0.0), 2.0)
        val actions = (0..4).flatMap { i ->
            session.onFix(
                navigating(), pt(i * 25.0, 200.0), 0.0, 90.0,
                secs(i.toDouble()), i * 1_000L,
            ).actions
        }
        assertTrue(actions.none { it is NavSession.Action.Reroute })
    }

    // ---- GPS health --------------------------------------------------------
    //
    // Before V5 there was no representation of this at all, and the effect was
    // that when the fixes stopped NOTHING ON THE SCREEN CHANGED. See GpsHealth.

    @Test
    fun `positioning starts as acquiring, not as good`() {
        assertEquals(GpsHealth.ACQUIRING, UiState().gps)
    }

    @Test
    fun `a fix makes positioning good`() {
        val r = session.onFix(navigating(), pt(100.0), 15.0, 90.0, secs(0.0), 0L, accuracyM = 6.0)
        assertEquals(GpsHealth.GOOD, r.ui.gps)
    }

    @Test
    fun `a poor accuracy is reported as weak, not as lost`() {
        val r = session.onFix(navigating(), pt(100.0), 15.0, 90.0, secs(0.0), 0L, accuracyM = 45.0)
        assertEquals(GpsHealth.WEAK, r.ui.gps)
        assertEquals(45.0, r.ui.gpsAccuracyM)
    }

    @Test
    fun `the frame loop declares the signal lost when the fixes dry up`() {
        // The defect, precisely: onFrame's early return meant an outage was
        // invisible. The tracker correctly stops advancing the puck; the HUD
        // went on showing an authoritative maneuver, countdown and ETA.
        var ui = session.onFix(navigating(), pt(100.0), 15.0, 90.0, secs(0.0), 0L, accuracyM = 5.0).ui
        assertEquals(GpsHealth.GOOD, ui.gps)
        ui = session.onFrame(ui, secs(2.0), 2_000L).ui
        assertEquals("2 s is a dropped update, not a loss", GpsHealth.GOOD, ui.gps)
        ui = session.onFrame(ui, secs(9.0), 9_000L).ui
        assertEquals(GpsHealth.LOST, ui.gps)
    }

    @Test
    fun `the signal recovers without restarting the journey`() {
        var ui = session.onFix(navigating(), pt(100.0), 15.0, 90.0, secs(0.0), 0L, accuracyM = 5.0).ui
        ui = session.onFrame(ui, secs(9.0), 9_000L).ui
        assertEquals(GpsHealth.LOST, ui.gps)
        val r = session.onFix(ui, pt(200.0), 15.0, 90.0, secs(10.0), 10_000L, accuracyM = 5.0)
        assertEquals(GpsHealth.GOOD, r.ui.gps)
        assertEquals("navigation must not have been torn down", Phase.NAVIGATING, r.ui.phase)
    }

    // ---- "always showing searching for GPS" -------------------------------
    //
    // Reported from the S24. `ACQUIRING` is the honest state for the first few
    // seconds of a launch and it had NO TIME LIMIT, so a device that was never
    // going to produce a position said "Searching for GPS / Waiting for the
    // first position fix" forever, with a search box that refused every
    // destination and no explanation. See GpsHealth.UNAVAILABLE.

    @Test
    fun `a short wait for the first fix is still acquiring`() {
        // It must not fire on a cold start. This handset's own mean
        // time-to-first-fix is 1.28 s (dumpsys location, 116 reports).
        var ui = session.onFrame(UiState(), secs(0.0), 0L).ui
        assertEquals(GpsHealth.ACQUIRING, ui.gps)
        ui = session.onFrame(ui, secs(5.0), 5_000L).ui
        assertEquals(GpsHealth.ACQUIRING, ui.gps)
        ui = session.onFrame(ui, secs(19.0), 19_000L).ui
        assertEquals(GpsHealth.ACQUIRING, ui.gps)
    }

    @Test
    fun `a first fix that never comes stops being called searching`() {
        var ui = session.onFrame(UiState(), secs(0.0), 0L).ui
        ui = session.onFrame(ui, secs(25.0), 25_000L).ui
        assertEquals(GpsHealth.UNAVAILABLE, ui.gps)
    }

    @Test
    fun `the driver is told something they can act on`() {
        // "Waiting for the first position fix" is true and useless: the cause
        // is almost never inside the app.
        assertEquals("No position available", GpsHealth.UNAVAILABLE.message)
        assertTrue(GpsHealth.UNAVAILABLE.degraded)
    }

    @Test
    fun `an unavailable receiver recovers the moment a fix lands`() {
        // Not sticky. On the handset the cause was a previous replay leaving
        // GMS's fused provider in mock mode, and the banner had to come down
        // the moment the provider was unstuck — without a restart, which is
        // what V6 3.B asks for.
        var ui = session.onFrame(UiState(), secs(0.0), 0L).ui
        ui = session.onFrame(ui, secs(25.0), 25_000L).ui
        assertEquals(GpsHealth.UNAVAILABLE, ui.gps)
        val r = session.onFix(ui, pt(100.0), 15.0, 90.0, secs(26.0), 26_000L, accuracyM = 5.0)
        assertEquals(GpsHealth.GOOD, r.ui.gps)
    }

    @Test
    fun `a frame after recovery does not fall back to unavailable`() {
        // The patience window is measured from when the session started
        // looking, so it is long expired by now. Once a fix has landed the
        // staleness rules own the question — a receiver that goes quiet later
        // is LOST, which is a different sentence.
        var ui = session.onFrame(UiState(), secs(0.0), 0L).ui
        ui = session.onFrame(ui, secs(25.0), 25_000L).ui
        ui = session.onFix(ui, pt(100.0), 15.0, 90.0, secs(26.0), 26_000L, accuracyM = 5.0).ui
        ui = session.onFrame(ui, secs(27.0), 27_000L).ui
        assertEquals(GpsHealth.GOOD, ui.gps)
        ui = session.onFrame(ui, secs(40.0), 40_000L).ui
        assertEquals(GpsHealth.LOST, ui.gps)
    }

    // ---- the missing maneuver card -----------------------------------------

    @Test
    fun `an on-route fix populates the maneuver card without a frame`() {
        // `currentManeuver` used to be set ONLY by onFrame, which returns
        // nothing unless the tracker holds a lock — and the lock drops after 3 s
        // without a fix. A stationary phone whose fixes arrive more slowly than
        // that showed NO MANEUVER CARD AT ALL: the driver taps Start and the
        // most important element on the screen is simply absent. Seen on an
        // S24 Ultra at a standstill.
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertTrue("an on-route fix must produce guidance on its own",
            r.ui.currentManeuver != null)
        assertTrue("and a countdown to it", r.ui.distanceToManeuverM > 0.0)
    }

    @Test
    fun `the countdown from a fix agrees with the countdown from a frame`() {
        // Two code paths producing the driver's instruction is how they drift.
        val fromFix = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L).ui
        val fromFrame = session.onFrame(fromFix, secs(0.0)).ui
        assertEquals(fromFix.currentManeuver?.index, fromFrame.currentManeuver?.index)
        assertEquals(fromFix.distanceToManeuverM, fromFrame.distanceToManeuverM, 1.0)
    }

    @Test
    fun `remaining distance is reported from an on-route fix`() {
        val r = session.onFix(navigating(), pt(500.0), 12.0, 90.0, secs(0.0), 0L)
        assertTrue("remaining distance must count down, got ${r.ui.remainingM}",
            r.ui.remainingM in 1400.0..1600.0)
    }

    // ---- where the vehicle is DRAWN (V7 Stage 4) ---------------------------
    //
    // The route runs due east, so right of travel is SOUTH — a positive lateral
    // offset lowers the latitude. Every assertion below measures that in metres
    // rather than in degrees, because a sign error here draws the vehicle in
    // the oncoming carriageway and a degree is not a distance.

    /** How far north (+) or south (-) of the route's latitude a point is. */
    private fun northingM(p: LngLat) = (p.lat - lat) * RouteGeometry.M_PER_DEG_LAT

    private fun puckOf(r: NavSession.Result): LngLat? =
        r.actions.filterIsInstance<NavSession.Action.Puck>().firstOrNull()?.position

    /** A two-way residential leg: `lanes=2` is ONE lane each way. */
    private fun twoWayLanes() = dev.vector.geo.RouteLanes.plan(
        listOf(dev.vector.geo.RouteLanes.Approach(atM = 2_000.0, forwardLanes = 1, totalLanes = 2))
    )

    @Test
    fun `the vehicle is drawn in the driven carriageway, not on the centre line`() {
        // The whole point of Stage 4, at the puck. Before it, a driver on a
        // two-way `lanes=2` street — 5,728 of them in Qatar — was drawn sitting
        // on the centre paint with half the car in oncoming traffic.
        session.setLanes(twoWayLanes())
        session.onFix(navigating(), pt(300.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val puck = puckOf(session.onFrame(navigating(), secs(0.5)))
        assertTrue("no puck was drawn", puck != null)
        // Half a 3.5 m carriageway, to the right of due east, which is south.
        assertEquals("the vehicle is not in the driven carriageway",
            -1.75, northingM(puck!!), 0.05)
    }

    @Test
    fun `the vehicle stays on the centre line when the model declines`() {
        // Most of Qatar's network has no direction-specific lane count, and a
        // decline has to be invisible: the pre-Stage-4 behaviour exactly.
        session.setLanes(
            dev.vector.geo.RouteLanes.plan(
                listOf(dev.vector.geo.RouteLanes.Approach(atM = 2_000.0))
            )
        )
        session.onFix(navigating(), pt(300.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val puck = puckOf(session.onFrame(navigating(), secs(0.5)))
        assertEquals("a route with no lane data moved the vehicle",
            0.0, northingM(puck!!), 1e-6)
    }

    @Test
    fun `a session with no plan at all draws exactly what it drew before Stage 4`() {
        // `setLanes` is never called on a journey whose route never reached
        // `drawRoute` — and null must mean centreline rather than crash.
        session.onFix(navigating(), pt(300.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val puck = puckOf(session.onFrame(navigating(), secs(0.5)))
        assertEquals(0.0, northingM(puck!!), 1e-6)
    }

    @Test
    fun `the camera follows the vehicle it drew, not the centre line`() {
        // Following the centreline while drawing the vehicle beside it would
        // put the puck permanently off-centre on screen — the same
        // contradiction Stage 4 removes from the map, moved into the viewport.
        session.setLanes(twoWayLanes())
        session.onFix(navigating(), pt(300.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        val r = session.onFrame(navigating(), secs(0.5))
        val cam = r.actions.filterIsInstance<NavSession.Action.Camera>().first()
        val puck = puckOf(r)!!
        assertEquals("the camera and the vehicle disagree about where the car is",
            puck.lat, cam.target.position.lat, 1e-12)
        assertEquals(puck.lng, cam.target.position.lng, 1e-12)
    }

    @Test
    fun `an off-route vehicle is drawn at the raw fix and never moved sideways`() {
        // Off the route the puck IS the measurement. Offsetting an unmatched
        // position by the geometry of a route the driver has left would be
        // fabrication rather than placement — and it would move the vehicle
        // away from the only honest thing on screen.
        session.setLanes(twoWayLanes())
        val away = pt(500.0, 200.0)
        val r = session.onFix(navigating(), away, 15.0, 90.0, secs(0.0), 0L)
        assertTrue(r.ui.offRoute)
        assertEquals("the off-route vehicle was moved off its own fix",
            away, puckOf(r))
    }

    @Test
    fun `an idle vehicle with no route is drawn at the raw fix`() {
        val fresh = NavSession(RouteTracker(), ManeuverAnnouncer())
        fresh.setLanes(twoWayLanes())
        val here = pt(0.0)
        val r = fresh.onFix(UiState(phase = Phase.EXPLORE), here, 10.0, 90.0, secs(0.0), 0L)
        assertEquals("a vehicle with no route was placed by a route", here, puckOf(r))
    }

    @Test
    fun `clearing the plan puts the vehicle back on the centre line`() {
        // `cancelRoute` clears it with the route. A profile that outlived its
        // journey would offset the NEXT one's vehicle by lane geometry that no
        // longer describes the road under it.
        session.setLanes(twoWayLanes())
        session.onFix(navigating(), pt(300.0), 20.0, 90.0, secs(0.0), 0L)
        session.onFrame(navigating(), secs(0.0))
        assertEquals(-1.75, northingM(puckOf(session.onFrame(navigating(), secs(0.5)))!!), 0.05)

        session.setLanes(null)
        val after = puckOf(session.onFrame(navigating(), secs(1.0)))
        assertEquals("a cleared plan still moved the vehicle", 0.0, northingM(after!!), 1e-6)
    }

    @Test
    fun `the vehicle moves into the turn lane as the maneuver approaches`() {
        // Four lanes, only the two on the left turn. The vehicle should be on
        // the carriageway centre early in the leg and in the left band by the
        // junction — which is the map agreeing with the lane strip rather than
        // contradicting it.
        session.setLanes(
            dev.vector.geo.RouteLanes.plan(
                listOf(
                    dev.vector.geo.RouteLanes.Approach(
                        atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                        lanes = dev.vector.geo.LaneGuidance.usefulLanes(
                            "left|left|through|through", "turn-left",
                        ),
                    ),
                    dev.vector.geo.RouteLanes.Approach(
                        atM = 2_000.0, forwardLanes = 4, totalLanes = 4,
                    ),
                )
            )
        )
        // DRIVEN there, one fix a second at 20 m/s, rather than teleported.
        //
        // Writing this as two `onFix` calls 690 m apart is how it was written
        // first, and it failed: `RouteTracker.searchFwdM` is 250 m, so the
        // second projection is clamped to the window and the vehicle never
        // reaches the junction at all. That window is correct — it is what
        // stops a doubled-back route placing the car half a kilometre ahead of
        // itself — so the test has to respect the thing it is testing through.
        var last = 0.0
        var atCentre: Double? = null
        for (i in 0..49) {
            val alongM = i * 20.0
            session.onFix(navigating(), pt(alongM), 20.0, 90.0, secs(i.toDouble()), i * 1000L)
            last = northingM(puckOf(session.onFrame(navigating(), secs(i.toDouble())))!!)
            // A one-way four-lane carriageway: its centre IS the centreline, so
            // early in the leg the vehicle has nothing to move for.
            if (alongM == 300.0) atCentre = last
        }
        assertEquals("the vehicle left the carriageway centre too early",
            0.0, atCentre!!, 0.1)
        // In the left band by the junction: lanes 0-1 of 4, centre -3.5 m,
        // which on a due-east route is 3.5 m NORTH.
        assertEquals("the vehicle is not in the turn lane at the junction",
            3.5, last, 0.2)
    }

    @Test
    fun `the vehicle never jumps sideways between frames`() {
        // A lateral step is a vehicle teleporting across a lane, which reads as
        // a rendering fault rather than as guidance. The model tapers; this is
        // the assertion that the taper survives the frame loop.
        session.setLanes(
            dev.vector.geo.RouteLanes.plan(
                listOf(
                    dev.vector.geo.RouteLanes.Approach(
                        atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                        lanes = dev.vector.geo.LaneGuidance.usefulLanes(
                            "left|left|through|through", "turn-left",
                        ),
                    ),
                    dev.vector.geo.RouteLanes.Approach(
                        atM = 2_000.0, forwardLanes = 1, totalLanes = 2,
                    ),
                )
            )
        )
        var prev: Double? = null
        var worst = 0.0
        // 20 m/s for 90 s, a fix a second with frames in between.
        for (i in 0..90) {
            session.onFix(navigating(), pt(i * 20.0), 20.0, 90.0, secs(i.toDouble()), i * 1000L)
            for (f in 0..3) {
                val t = i + f * 0.25
                val puck = puckOf(session.onFrame(navigating(), secs(t))) ?: continue
                val now = northingM(puck)
                prev?.let { worst = maxOf(worst, kotlin.math.abs(now - it)) }
                prev = now
            }
        }
        // A quarter-second at 20 m/s is 5 m of travel; the taper moves the
        // vehicle at most one lane per TAPER_M, so well under a metre.
        assertTrue("the vehicle jumped ${format(worst)} m sideways in one frame",
            worst < 1.0)
    }

    // ---- signal-aware navigation (V7 Stage 5) ----------------------------

    private fun signalProfile(vararg eastM: Double) =
        SignalMatcher.match(
            RouteGeometry.index(route)!!,
            eastM.map { i ->
                SignalRef(id = "n${i.toInt()}", position = pt(i), source = "osm:node:n${i.toInt()}")
            },
        )

    /**
     * Drive [ui] forward [seconds] seconds at [speed], starting from
     * [startM] along the route's own coordinate line, exactly like the real
     * loop: a fix a second to keep the lock alive (frames alone drop it after
     * `reckonMaxS`) with display frames in between to advance the vehicle.
     */
    private fun drive(
        session: NavSession, ui: UiState, seconds: Int,
        speed: Double = 12.0, startM: Double = 0.0, northM: Double = 0.0,
    ): UiState {
        var u = ui
        for (i in 0 until seconds) {
            u = session.onFix(u, pt(startM + speed * i, northM = northM),
                speed, 90.0, secs(i.toDouble()), i * 1000L).ui
            for (f in 0 until 3) {
                u = session.onFrame(u, secs(i + f * 0.25)).ui
            }
        }
        return u
    }

    @Test
    fun `the next signal is evaluated on the navigation tick, not on a frame`() {
        session.setSignals(signalProfile(500.0, 1000.0))
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        val ahead = r.ui.signalAhead
        assertNotNull("an on-route fix must populate the signal ahead", ahead)
        assertEquals("n500", ahead!!.signalId)
        assertEquals(200.0, ahead.distanceM, 5.0)

        // Display frames are for drawing: between fixes the prediction holds
        // exactly as the last tick left it, untouched by the frame loop.
        val frozen = session.onFrame(r.ui, secs(0.25)).ui
        assertTrue("frames must not recompute the signal", frozen.signalAhead === ahead)
        val frozen2 = session.onFrame(frozen, secs(0.5)).ui
        assertTrue("frames must not recompute the signal", frozen2.signalAhead === ahead)
    }

    @Test
    fun `the signal ahead advances past each junction and clears at the end`() {
        session.setSignals(signalProfile(500.0, 1000.0))
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertEquals("n500", r.ui.signalAhead!!.signalId)
        // 30 s at 12 m/s moves the vehicle from 300 m to ~660 m: past n500.
        val mid = drive(session, r.ui, 30, startM = 300.0)
        assertEquals("n1000", mid.signalAhead!!.signalId)
        // 90 more seconds takes it past the end of the 2 km route and the
        // last signal: no signal claim at all.
        val end = drive(session, mid, 90, startM = 300.0 + 12.0 * 30)
        assertNull(end.signalAhead)
    }

    @Test
    fun `no profile means no signal claim, exactly like before Stage 5`() {
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertNull(r.ui.signalAhead)
    }

    @Test
    fun `today's data predicts UNKNOWN with LOCATION basis and zero confidence`() {
        session.setSignals(signalProfile(500.0))
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        val p = r.ui.signalAhead!!.prediction
        assertEquals(dev.vector.geo.signal.Phase.UNKNOWN, p.phase)
        assertEquals(dev.vector.geo.signal.Basis.LOCATION, p.basis)
        assertEquals(0.0, p.confidence, 0.0)
    }

    @Test
    fun `a stop widens the next arrival window and steady movement narrows it`() {
        fun movingSession(): NavSession {
            val t = RouteTracker()
            t.setRoute(route)
            return NavSession(t, ManeuverAnnouncer())
        }
        // Session A: moving throughout.
        val a = movingSession()
        a.setSignals(signalProfile(500.0))
        val wA = a.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
            .ui.signalAhead!!.arrival

        // Session B: the same position, standing still at a red light -- the
        // widened window is the honest difference between "rolling toward the
        // signal" and "stuck in front of it right now".
        val b = movingSession()
        b.setSignals(signalProfile(500.0))
        val wB = b.onFix(navigating(), pt(300.0), 0.0, 90.0, secs(0.0), 0L)
            .ui.signalAhead!!.arrival
        // And a later moving tick narrows it back toward the moving floor.
        val wB2 = b.onFix(navigating(), pt(320.0), 12.0, 90.0, secs(1.0), 1_000L)
            .ui.signalAhead!!.arrival
        assertTrue("a stop must widen the window: ${wA.uncertaintyS} vs ${wB.uncertaintyS}",
            wB.uncertaintyS > wA.uncertaintyS)
        assertTrue("steady movement must narrow it back: ${wB2.uncertaintyS} < ${wB.uncertaintyS}",
            wB2.uncertaintyS < wB.uncertaintyS)
    }

    @Test
    fun `a reroute replaces the profile with the new route's signals`() {
        session.setSignals(signalProfile(500.0))
        // The reroute's route lives a kilometre north; its signals are new.
        val north = (0..20).map { i -> pt(i * 100.0, northM = 1000.0) }
        tracker.setRoute(north)
        val profileB = SignalMatcher.match(
            RouteGeometry.index(north)!!,
            listOf(SignalRef("nNew", pt(700.0, northM = 1000.0), "osm:node:nNew")),
        )
        session.setSignals(profileB)
        val r0 = session.onFix(navigating(), pt(600.0, northM = 1000.0), 12.0, 90.0,
            secs(0.0), 0L)
        assertNotNull(r0.ui.signalAhead)
        assertEquals("nNew", r0.ui.signalAhead!!.signalId)
        // Passing the last of the NEW route's signals clears the claim even
        // though the old route had another one still ahead of the vehicle.
        val end = drive(session, r0.ui, 100, startM = 600.0, northM = 1000.0)
        assertNull(end.signalAhead)
    }

    private fun format(v: Double) = String.format("%.2f", v)

    // ---- speed-camera intelligence (V73) ---------------------------------

    private fun cameraProfile(vararg eastM: Double) =
        dev.vector.geo.camera.CameraMatcher.match(
            RouteGeometry.index(route)!!,
            eastM.map { i ->
                dev.vector.geo.camera.CameraRef(
                    id = "n${i.toInt()}", position = pt(i),
                    type = dev.vector.geo.camera.CameraType.SPEED,
                )
            },
        )

    private fun spoken(result: NavSession.Result): List<String> =
        result.actions.filterIsInstance<NavSession.Action.Speak>().map { it.text }

    @Test
    fun `the next camera is evaluated on the navigation tick, not on a frame`() {
        session.setCameras(cameraProfile(500.0, 1000.0))
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        val ahead = r.ui.cameraAhead
        assertNotNull("an on-route fix must populate the camera ahead", ahead)
        assertEquals("n500", ahead!!.cameraId)
        assertEquals(200.0, ahead.distanceM, 5.0)
        // Display frames never recompute it.
        val frozen = session.onFrame(r.ui, secs(0.25)).ui
        assertTrue("frames must not recompute the camera", frozen.cameraAhead === ahead)
    }

    @Test
    fun `no camera profile means no camera claim, exactly like before the cameras arrived`() {
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertNull(r.ui.cameraAhead)
        assertTrue(spoken(r).isEmpty())
    }

    @Test
    fun `the banner and the voice are the same sentence from the same fact`() {
        // One function produces both (CameraText.line); this pins that the
        // session does not paraphrase one into the other.
        session.setCameras(cameraProfile(500.0))
        val r = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        val ahead = r.ui.cameraAhead
        assertNotNull(ahead)
        assertEquals(listOf(ahead!!.line), spoken(r))
        assertEquals(dev.vector.geo.camera.CameraText.line(ahead.type), ahead.line)
    }

    @Test
    fun `a camera inside the warning window is spoken exactly once per route`() {
        session.setCameras(cameraProfile(500.0))
        // 300 m in: the camera at 500 is 200 m ahead, inside WARN_AHEAD_M.
        val r1 = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertEquals(listOf("Speed camera ahead"), spoken(r1))
        assertEquals("n500", r1.ui.cameraAhead!!.cameraId)
        // The next tick: still ahead, still inside the window — but already
        // announced, so the voice stays silent. This is the dedup.
        val r2 = session.onFix(r1.ui, pt(312.0), 12.0, 90.0, secs(1.0), 1_000L)
        assertTrue("a camera must not be spoken twice", spoken(r2).isEmpty())
        val r3 = session.onFix(r2.ui, pt(324.0), 12.0, 90.0, secs(2.0), 2_000L)
        assertTrue(spoken(r3).isEmpty())
    }

    @Test
    fun `the alert is bounded by the warning window, banner included`() {
        // The camera at 1000 m is the next one, but 1000 m > WARN_AHEAD_M:
        // neither the banner nor the voice may appear yet, because the HUD's
        // second line belongs to the road name outside the window.
        session.setCameras(cameraProfile(1000.0))
        val r1 = session.onFix(navigating(), pt(100.0), 12.0, 90.0, secs(0.0), 0L)
        assertNull("900 m ahead is outside the 420 m window", r1.ui.cameraAhead)
        assertTrue(spoken(r1).isEmpty())
        // Drive into the window: the banner appears, and it speaks once, on
        // the tick that crosses the boundary.
        var u = r1.ui
        var spoke = 0
        var bannerTicks = 0
        var i = 1
        while (i <= 80) {
            val r = session.onFix(u, pt(100.0 + 12.0 * i), 12.0, 90.0, secs(i.toDouble()),
                i * 1000L)
            spoke += spoken(r).size
            if (r.ui.cameraAhead != null) bannerTicks += 1
            u = r.ui
            i += 1
        }
        assertEquals("exactly one camera utterance per route", 1, spoke)
        assertTrue("the banner appeared inside the window", bannerTicks > 0)
        // 420 m at 12 m/s is 35 ticks of a 1 Hz fix, and the camera is passed
        // after that: the banner cannot have been up for the whole run.
        assertTrue("the banner was up for $bannerTicks of 80 ticks", bannerTicks < 45)
    }

    @Test
    fun `a camera is never spoken with voice off, and the banner still shows`() {
        session.setCameras(cameraProfile(500.0))
        val silent = navigating().copy(settings = Settings(voice = VoiceMode.OFF))
        val r = session.onFix(silent, pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertTrue("voice OFF must say nothing", spoken(r).isEmpty())
        assertEquals("the visual warning is independent of the voice",
            "n500", r.ui.cameraAhead?.cameraId)
    }

    @Test
    fun `alerts-only speaks a camera warning, because that is what it is`() {
        session.setCameras(cameraProfile(500.0))
        val alerts = navigating().copy(settings = Settings(voice = VoiceMode.ALERTS))
        val r = session.onFix(alerts, pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertEquals(listOf("Speed camera ahead"), spoken(r))
    }

    @Test
    fun `a camera met while muted is still announced once voice returns`() {
        // The one-shot must not be consumed by a journey the driver was not
        // listening to: it is consumed when the sentence is actually spoken.
        session.setCameras(cameraProfile(1000.0))
        val muted = navigating().copy(settings = Settings(voice = VoiceMode.OFF))
        var u = muted
        var spokeWhileMuted = 0
        var i = 1
        while (i <= 45) {   // ticks inside the window with voice off
            val r = session.onFix(u, pt(100.0 + 12.0 * i), 12.0, 90.0, secs(i.toDouble()),
                i * 1000L)
            spokeWhileMuted += spoken(r).size
            u = r.ui
            i += 1
        }
        assertEquals(0, spokeWhileMuted)
        assertNotNull("still inside the window", u.cameraAhead)
        // Unmute on the next tick: the driver hears it, once.
        val on = session.onFix(u.copy(settings = Settings(voice = VoiceMode.FULL)),
            pt(100.0 + 12.0 * 46), 12.0, 90.0, secs(46.0), 46_000L)
        assertEquals(listOf("Speed camera ahead"), spoken(on))
        val again = session.onFix(on.ui, pt(100.0 + 12.0 * 47), 12.0, 90.0, secs(47.0), 47_000L)
        assertTrue("and not twice", spoken(again).isEmpty())
    }

    @Test
    fun `leaving the route clears the camera rather than freezing its distance`() {
        session.setCameras(cameraProfile(500.0))
        val r1 = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertNotNull(r1.ui.cameraAhead)
        // Far off the route: there is no next camera to be approaching, and a
        // frozen "400 m" would be a claim about a route the driver is not on.
        val off = session.onFix(r1.ui, pt(300.0, northM = 400.0), 12.0, 90.0,
                                secs(1.0), 1_000L)
        assertTrue(off.ui.offRoute)
        assertNull(off.ui.cameraAhead)
    }

    @Test
    fun `a camera is not announced while the route is only being previewed`() {
        // A route shown but not started is a proposal. The tracker reports
        // OnRoute for it (the route begins at the vehicle), so without a phase
        // gate the camera alert fires before the driver has pressed Start —
        // which is what a real Doha PREVIEW run did, six times.
        session.setCameras(cameraProfile(500.0))
        val preview = navigating().copy(phase = Phase.PREVIEW)
        val r = session.onFix(preview, pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertNull("a previewed route must not raise a camera alert", r.ui.cameraAhead)
        assertTrue(spoken(r).isEmpty())
        // Crossing into NAVIGATING on the same route announces it, once.
        val started = session.onFix(r.ui.copy(phase = Phase.NAVIGATING), pt(312.0), 12.0,
                                    90.0, secs(1.0), 1_000L)
        assertEquals(listOf("Speed camera ahead"), spoken(started))
        assertEquals("n500", started.ui.cameraAhead?.cameraId)
        // And a route that is cancelled back out of navigation clears it.
        val back = session.onFix(started.ui.copy(phase = Phase.EXPLORE), pt(324.0), 12.0,
                                 90.0, secs(2.0), 2_000L)
        assertNull(back.ui.cameraAhead)
    }

    @Test
    fun `a reroute forgets what the old route announced`() {
        session.setCameras(cameraProfile(500.0))
        val r1 = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
        assertEquals(1, spoken(r1).size)
        // A reroute replaces the profile wholesale. The same camera id on the
        // NEW route is a new encounter and may speak again; a camera that is
        // no longer on the route is simply gone.
        session.setCameras(cameraProfile(500.0))
        val r2 = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(3.0), 3_000L)
        assertEquals("a new route may announce its cameras afresh", 1, spoken(r2).size)
        session.setCameras(cameraProfile())
        val r3 = session.onFix(r2.ui, pt(300.0), 12.0, 90.0, secs(4.0), 4_000L)
        assertNull(r3.ui.cameraAhead)
        assertTrue(spoken(r3).isEmpty())
    }

    // ---- the 2026-09-24 audio (wrong limit, glitching voice) ----------------

    @Test
    fun `the limit lookup asks about the route's road, not the nearest one to the fix`() {
        // D Ring Road at 2:22 on the 2026-09-24 drive: the fix sat a few
        // metres toward Al Amir Street, a 60 km/h service road 12-33 m beside
        // the 80 km/h carriageway, and the nearest-road lookup answered for
        // it. A fix 18 m north of this route is the same geometry.
        val fix = pt(500.0, northM = 18.0)
        session.onFix(navigating(), fix, 22.0, 90.0, secs(0.0), 0L)
        val at = session.roadProbePoint(fix)
        val offM = RouteGeometry.haversineM(at.lng, at.lat, pt(500.0).lng, pt(500.0).lat)
        // On the route line, where the route's own way is at ~0 m and a
        // parallel road 12 m away can no longer be nearer.
        assertTrue("probe must sit on the route, was ${format(offM)} m from it", offM < 1.0)
    }

    @Test
    fun `without a route the limit lookup keeps the raw fix`() {
        val fresh = NavSession(RouteTracker(), ManeuverAnnouncer())
        val fix = pt(500.0, northM = 18.0)
        fresh.onFix(UiState(), fix, 22.0, 90.0, secs(0.0), 0L)
        assertEquals(fix, fresh.roadProbePoint(fix))
    }

    @Test
    fun `off the route the limit lookup keeps the raw fix`() {
        // 200 m off: the driver is on some other road, and the nearest road is
        // the honest answer about it.
        val fix = pt(500.0, northM = 200.0)
        session.onFix(navigating(), fix, 22.0, 90.0, secs(0.0), 0L)
        assertEquals(fix, session.roadProbePoint(fix))
    }

    @Test
    fun `alerts are spoken as alerts, a countdown as PREPARE`() {
        // The priority is what stops the camera line cutting the over-limit
        // line off mid-word (0:17 on the 2026-09-24 drive): an ALERT queues.
        session.setCameras(cameraProfile(500.0))
        val cam = session.onFix(navigating(), pt(300.0), 12.0, 90.0, secs(0.0), 0L)
            .actions.filterIsInstance<NavSession.Action.Speak>()
        assertEquals(1, cam.size)
        assertEquals(dev.vector.geo.SpeechArbiter.Kind.ALERT, cam.single().kind)

        val over = navigating().copy(speedLimitKmh = 50, speedLimitInferred = false)
        val fresh = NavSession(RouteTracker().also { it.setRoute(route) }, ManeuverAnnouncer())
        fresh.onFix(over, pt(650.0), 22.0, 90.0, secs(0.0), 0L)
        val spoken = (0..3).flatMap {
            fresh.onFrame(over, secs(it * 0.1)).actions
                .filterIsInstance<NavSession.Action.Speak>()
        }
        val limit = spoken.filter { it.text == "Speed limit 50" }
        assertEquals("expected one over-limit alert in $spoken", 1, limit.size)
        assertEquals(dev.vector.geo.SpeechArbiter.Kind.ALERT, limit.single().kind)
        val turn = spoken.filter { it.text.contains("شارع الكورنيش") }
        assertTrue("expected the turn to be announced in $spoken", turn.isNotEmpty())
        // 350 m out at 22 m/s is the PREPARE stage: a countdown, which a later
        // NOW may interrupt.
        assertTrue("$turn", turn.all { it.kind == dev.vector.geo.SpeechArbiter.Kind.PREPARE })
    }

    @Test
    fun `the maneuver itself is spoken as NOW, not as a countdown`() {
        // 100 m before the turn at 1000: inside TURN_M, so the TURN stage,
        // which says the instruction itself.
        session.onFix(navigating(), pt(900.0), 12.0, 90.0, secs(0.0), 0L)
        val spoken = (0..3).flatMap {
            session.onFrame(navigating(), secs(it * 0.1)).actions
                .filterIsInstance<NavSession.Action.Speak>()
        }
        assertTrue("expected the turn in $spoken", spoken.isNotEmpty())
        assertTrue("$spoken", spoken.all { it.kind == dev.vector.geo.SpeechArbiter.Kind.NOW })
    }
}
