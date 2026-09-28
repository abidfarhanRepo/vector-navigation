package dev.vector.geo.walk

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a walker is spoken to (V7.4 4C final).
 *
 * The questions this file exists to answer are the ones that cannot be
 * answered by walking around with a phone: did it say the same thing twice,
 * did it keep announcing the old route's turns after a reroute, did it go on
 * giving instructions after the walker left the route. Every one of those is a
 * sequence over many fixes, which is exactly what a unit test is for and what
 * a device walk is worst at.
 */
class WalkVoiceTest {

    // ---------------------------------------------------------------- helpers

    private fun straight(lengthM: Double, stepM: Double = 10.0): List<LngLat> {
        val pts = ArrayList<LngLat>()
        var d = 0.0
        while (d < lengthM) {
            pts.add(LngLat(0.0, d / RouteGeometry.M_PER_DEG_LAT))
            d += stepM
        }
        pts.add(LngLat(0.0, lengthM / RouteGeometry.M_PER_DEG_LAT))
        return pts
    }

    private fun maneuver(
        kind: WalkManeuverKind,
        atM: Double,
        road: WalkRoadId? = null,
        spanM: Double = 0.0,
    ) = WalkManeuver(
        kind = kind,
        index = (atM / 10.0).toInt(),
        distanceM = atM,
        distanceToNextM = null,
        road = road,
        crossing = if (kind == WalkManeuverKind.CROSS) WalkCrossing(
            type = null, typeSource = null, markings = null, kerb = null,
            tactilePaving = null, distanceM = spanM, distanceToCrossingM = null,
            approachIndex = null, approachDistanceM = null, enterIndex = null,
            leaveIndex = null, road = null, crossedRoadSource = null,
        ) else null,
        stairs = if (kind == WalkManeuverKind.STAIRS)
            WalkStairs(distanceM = spanM) else null,
    )

    private fun route(lengthM: Double, plan: List<WalkManeuver>): WalkRoute =
        WalkRoute.of(
            WalkContract(
                contractVersion = 1,
                walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general",
                mode = "foot",
                distanceM = lengthM,
                durationS = lengthM / 1.35,
                stepsM = 0.0,
                crossingM = 0.0,
                nodes = 0,
                geometry = straight(lengthM),
                segments = WalkSegments(),
                facts = emptyList(),
                plan = plan,
                cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )

    /** A 400 m walk with a left turn at 200 m. */
    private fun ordinary() = route(
        400.0,
        listOf(
            maneuver(WalkManeuverKind.DEPART, 0.0),
            maneuver(WalkManeuverKind.TURN_LEFT, 200.0),
            maneuver(WalkManeuverKind.ARRIVE, 400.0),
        ),
    )

    /** Walk the route, collecting everything said. */
    private fun walk(
        voice: WalkVoice,
        r: WalkRoute,
        toM: Double,
        stepM: Double = 1.35,
        arrivedAtM: Double? = null,
    ): List<WalkVoice.Announcement> {
        val out = ArrayList<WalkVoice.Announcement>()
        val tracker = WalkProgressTracker(r)
        var d = 0.0
        while (d <= toM) {
            val progress = tracker.update(d)
            voice.update(
                WalkVoice.Inputs(
                    progress = progress,
                    followState = WalkFollowState.ON_ROUTE,
                    arrived = arrivedAtM != null && d >= arrivedAtM,
                    rerouting = false,
                )
            )?.let { out.add(it) }
            d += stepM
        }
        return out
    }

    // ------------------------------------------------- one per maneuver

    @Test
    fun `a maneuver is announced at most once per stage`() {
        val said = walk(WalkVoice(), ordinary(), 400.0)
        val turn = said.filter { it.instruction?.kind == WalkManeuverKind.TURN_LEFT }
        // Approach and now — never more, however many fixes pass through each
        // band. At 1.35 m/s the walker spends ~15 fixes inside the approach
        // window alone, so a missing dedup would be loudly visible here.
        assertEquals(2, turn.size, "turn announcements: ${turn.map { it.text }}")
        assertEquals(WalkVoice.Event.APPROACH, turn[0].event)
        assertEquals(WalkVoice.Event.NOW, turn[1].event)
    }

    @Test
    fun `no two consecutive announcements repeat the same sentence`() {
        val said = walk(WalkVoice(), ordinary(), 400.0, arrivedAtM = 399.0)
        for (i in 1 until said.size) {
            assertFalse(
                said[i].text == said[i - 1].text,
                "repeated \"${said[i].text}\"",
            )
        }
    }

    @Test
    fun `a stationary walker is not spoken to repeatedly`() {
        // The failure this guards is voice spam at a kerb: a walker waiting to
        // cross produces dozens of fixes at one position.
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        walk(v, r, 195.0)
        var said = 0
        repeat(60) {
            v.update(
                WalkVoice.Inputs(tracker.update(195.0), WalkFollowState.ON_ROUTE, false, false)
            )?.let { said++ }
        }
        assertEquals(0, said, "a standstill produced $said announcements")
    }

    @Test
    fun `the walk opens with exactly one departure line`() {
        val said = walk(WalkVoice(), ordinary(), 400.0)
        val departs = said.filter { it.event == WalkVoice.Event.DEPART }
        assertEquals(1, departs.size)
        assertEquals(said.first(), departs.single())
    }

    @Test
    fun `a route whose plan has no depart still opens with a start line`() {
        // A walk that begins in silence reads as a walk that failed to begin.
        val r = route(
            100.0,
            listOf(maneuver(WalkManeuverKind.ARRIVE, 100.0)),
        )
        val said = walk(WalkVoice(), r, 50.0)
        assertEquals(WalkVoice.Event.DEPART, said.first().event)
        assertEquals(WalkVoice.DEPART, said.first().text)
    }

    @Test
    fun `continue is never announced`() {
        // A way-identity change the walker does nothing about, and Qatari
        // plans are full of them.
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.CONTINUE, 100.0),
                maneuver(WalkManeuverKind.CONTINUE, 200.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val said = walk(WalkVoice(), r, 400.0)
        assertTrue(said.none { it.instruction?.kind == WalkManeuverKind.CONTINUE })
    }

    // --------------------------------------------------------- crossings

    @Test
    fun `a crossing is announced`() {
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.CROSS, 200.0, spanM = 20.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val said = walk(WalkVoice(), r, 400.0)
        val cross = said.filter { it.instruction?.kind == WalkManeuverKind.CROSS }
        assertTrue(cross.isNotEmpty(), "the crossing was never announced")
        assertTrue(cross.all { it.text.contains("Cross", ignoreCase = true) })
        // And no road is named, because this crossing has none.
        //
        // Case-insensitively, deliberately: the approach stage renders
        // "In 30 metres, cross the road", and the lower-case "c" is correct
        // English mid-sentence rather than a different sentence. What is being
        // asserted is the absence of an INVENTED ROAD, not the capitalisation.
        assertTrue(
            cross.all { it.text.contains("cross the road", ignoreCase = true) },
            "a road was named on an unattributed crossing: ${cross.map { it.text }}",
        )
        assertTrue(cross.none { it.text.contains(" onto ") })
    }

    @Test
    fun `a long crossing is not re-announced while the walker is on it`() {
        // The real crossing-heavy fixture has spans up to 156.7 m — nearly two
        // minutes, and over a hundred fixes, spent inside one event.
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.CROSS, 150.0, spanM = 150.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val said = walk(WalkVoice(), r, 340.0)
        val cross = said.filter { it.instruction?.kind == WalkManeuverKind.CROSS }
        assertTrue(cross.size <= 2, "spam on a long crossing: ${cross.map { it.text }}")
    }

    // ------------------------------------------------- traffic lights (V7)

    /** The same crossing, with and without a surveyed signal standing on it. */
    private fun signalCrossingRoute(signals: List<WalkSignal>) = route(
        400.0,
        listOf(
            maneuver(WalkManeuverKind.DEPART, 0.0),
            WalkManeuver(
                kind = WalkManeuverKind.CROSS, index = 20, distanceM = 200.0,
                distanceToNextM = null, road = null,
                crossing = WalkCrossing(
                    type = "traffic_signals", typeSource = "catalog",
                    markings = null, kerb = null, tactilePaving = null,
                    distanceM = 15.0, distanceToCrossingM = null,
                    approachIndex = null, approachDistanceM = null,
                    enterIndex = null, leaveIndex = null,
                    road = null, crossedRoadSource = null, signals = signals,
                ),
            ),
            maneuver(WalkManeuverKind.ARRIVE, 400.0),
        ),
    )

    @Test
    fun `a signal on the crossing does not add an announcement`() {
        // The duplication rule: a signal-controlled crossing is the SAME event
        // the crossing maneuver already describes, so the voice says exactly
        // what it says about an unmarked crossing — no more. Anything else is
        // two sentences about one step off a kerb.
        val plain = walk(WalkVoice(), signalCrossingRoute(emptyList()), 400.0)
        val withSignal = walk(
            WalkVoice(),
            signalCrossingRoute(
                listOf(WalkSignal("n1001", "osm:node:1001", "51.53,25.286"))
            ),
            400.0,
        )
        assertEquals(
            plain.map { it.text },
            withSignal.map { it.text },
            "a surveyed signal changed what the voice said",
        )
    }

    @Test
    fun `no signal wording is ever spoken`() {
        // There is no signal string in the walking vocabulary to speak — the
        // label lives in the banner's detail line only. This walks every
        // announcement and asserts the absence, so a future contribution that
        // starts voicing the detail line fails here rather than shipping.
        val said = walk(
            WalkVoice(),
            signalCrossingRoute(
                listOf(WalkSignal("n1001", "osm:node:1001", "51.53,25.286"))
            ),
            400.0,
        )
        assertTrue(said.isNotEmpty())
        for (a in said) {
            val t = a.text.lowercase()
            for (word in listOf("signal", "light", "green", "red", "amber",
                                "phase", "cycle", "countdown", "second", "wait")) {
                assertFalse(
                    t.contains(word),
                    "the voice said \"$word\": ${a.text}",
                )
            }
        }
    }

    @Test
    fun `stairs are announced`() {
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.STAIRS, 200.0, spanM = 6.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val said = walk(WalkVoice(), r, 400.0)
        val stairs = said.filter { it.instruction?.kind == WalkManeuverKind.STAIRS }
        assertTrue(stairs.isNotEmpty(), "the stairs were never announced")
        assertTrue(stairs.all { it.text.contains("stairs", ignoreCase = true) })
    }

    @Test
    fun `a u-turn is announced`() {
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.UTURN, 200.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val said = walk(WalkVoice(), r, 400.0)
        assertTrue(said.any { it.text.contains("U-turn") })
    }

    // ----------------------------------------------------------- arrival

    @Test
    fun `arrival is announced exactly once`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        val said = ArrayList<WalkVoice.Announcement>()
        // Keep feeding fixes long after arrival — the real loop does.
        repeat(120) {
            v.update(
                WalkVoice.Inputs(tracker.update(400.0), WalkFollowState.ON_ROUTE, true, false)
            )?.let { said.add(it) }
        }
        val arrivals = said.filter { it.event == WalkVoice.Event.ARRIVED }
        assertEquals(1, arrivals.size)
        assertEquals(WalkVoice.ARRIVED, arrivals.single().text)
    }

    @Test
    fun `arrival claims the route ended and not that a destination was reached`() {
        // The walk ends where the pedestrian network ends, and `snap_max_m`
        // records how far that was from what was asked for.
        assertTrue(WalkVoice.ARRIVED.contains("walking route"))
        assertFalse(WalkVoice.ARRIVED.contains("your destination"))
        assertFalse(WalkVoice.ARRIVED.contains("You have arrived"))
    }

    @Test
    fun `nothing is announced after arrival`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        v.update(WalkVoice.Inputs(tracker.update(400.0), WalkFollowState.ON_ROUTE, true, false))
        // A maneuver still nominally ahead must not be spoken once the walk is
        // over: it would describe a route nobody is on.
        val after = (0 until 30).mapNotNull {
            v.update(
                WalkVoice.Inputs(tracker.update(200.0), WalkFollowState.ON_ROUTE, false, false)
            )
        }
        assertTrue(after.isEmpty(), "spoke after arrival: ${after.map { it.text }}")
    }

    @Test
    fun `arrival beats an off-route state on the same fix`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        val a = v.update(
            WalkVoice.Inputs(tracker.update(400.0), WalkFollowState.OFF_ROUTE, true, false)
        )
        assertEquals(WalkVoice.Event.ARRIVED, a?.event)
    }

    // ---------------------------------------------------------- off route

    @Test
    fun `an uncertain fix announces nothing at all`() {
        // The honest "I cannot tell" band. Announcing a departure would be an
        // accusation; announcing the next turn would be a claim. Both are
        // exactly what the state exists to withhold.
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        v.update(WalkVoice.Inputs(tracker.update(10.0), WalkFollowState.ON_ROUTE, false, false))
        val said = (0 until 40).mapNotNull {
            v.update(
                WalkVoice.Inputs(tracker.update(190.0), WalkFollowState.UNCERTAIN, false, false)
            )
        }
        assertTrue(said.isEmpty(), "uncertain spoke: ${said.map { it.text }}")
    }

    @Test
    fun `a confirmed departure is announced once`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        val said = (0 until 40).mapNotNull {
            v.update(
                WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.OFF_ROUTE, false, false)
            )
        }
        assertEquals(1, said.size)
        assertEquals(WalkVoice.Event.OFF_ROUTE, said.single().event)
    }

    @Test
    fun `no maneuver is announced while off the route`() {
        // Stale turn suppression: the plan describes a route the walker is
        // demonstrably not on, so its turns are not instructions any more.
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        val said = ArrayList<WalkVoice.Announcement>()
        var d = 150.0
        repeat(60) {
            v.update(
                WalkVoice.Inputs(tracker.update(d), WalkFollowState.OFF_ROUTE, false, false)
            )?.let { said.add(it) }
            d += 1.35
        }
        assertTrue(
            said.none { it.event.isManeuver },
            "stale maneuvers spoken off-route: ${said.map { it.text }}",
        )
    }

    @Test
    fun `the off-route line invents no way back`() {
        // Vector does not know which way the walker is facing — the follower
        // refuses device heading on foot — and the route back IS a route.
        assertFalse(WalkVoice.OFF_ROUTE.contains("turn around", ignoreCase = true))
        assertFalse(WalkVoice.OFF_ROUTE.contains("go back", ignoreCase = true))
        assertFalse(WalkVoice.OFF_ROUTE.contains("north", ignoreCase = true))
    }

    @Test
    fun `rejoining is announced once and re-enables maneuvers`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        repeat(10) {
            v.update(WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.OFF_ROUTE, false, false))
        }
        val back = v.update(
            WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.ON_ROUTE, false, false)
        )
        assertEquals(WalkVoice.Event.REJOINED, back?.event)
        // Once, not on every subsequent fix.
        val again = (0 until 20).mapNotNull {
            v.update(
                WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.ON_ROUTE, false, false)
            )
        }
        assertTrue(again.none { it.event == WalkVoice.Event.REJOINED })
    }

    @Test
    fun `a second genuine departure can be announced again`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        repeat(5) {
            v.update(WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.OFF_ROUTE, false, false))
        }
        v.update(WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.ON_ROUTE, false, false))
        val second = (0 until 5).mapNotNull {
            v.update(
                WalkVoice.Inputs(tracker.update(120.0), WalkFollowState.OFF_ROUTE, false, false)
            )
        }
        assertEquals(1, second.size)
        assertEquals(WalkVoice.Event.OFF_ROUTE, second.single().event)
    }

    // ----------------------------------------------------------- reroute

    @Test
    fun `rerouting is announced once`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        val said = (0 until 30).mapNotNull {
            v.update(
                WalkVoice.Inputs(tracker.update(100.0), WalkFollowState.OFF_ROUTE, false, true)
            )
        }
        assertEquals(1, said.size)
        assertEquals(WalkVoice.Event.REROUTING, said.single().event)
    }

    @Test
    fun `a replaced route forgets what the old one announced`() {
        // A new plan renumbers from zero. A surviving fired-set would suppress
        // a DIFFERENT maneuver that happens to share an index, and the walker
        // would be told nothing at the first turn of the new route.
        val v = WalkVoice()
        val r = ordinary()
        walk(v, r, 400.0)
        v.onRouteReplaced()
        val said = walk(v, r, 400.0)
        assertTrue(
            said.any { it.instruction?.kind == WalkManeuverKind.TURN_LEFT },
            "the new route's turn was suppressed by the old route's state",
        )
        assertEquals(WalkVoice.Event.DEPART, said.first().event)
    }

    @Test
    fun `a replaced route does not re-announce arrival`() {
        val v = WalkVoice()
        val r = ordinary()
        val tracker = WalkProgressTracker(r)
        v.update(WalkVoice.Inputs(tracker.update(400.0), WalkFollowState.ON_ROUTE, true, false))
        v.onRouteReplaced()
        val after = v.update(
            WalkVoice.Inputs(tracker.update(400.0), WalkFollowState.ON_ROUTE, true, false)
        )
        assertNull(after, "arrival was announced twice across a route replacement")
    }

    @Test
    fun `a refusal is spoken once per refusal`() {
        val v = WalkVoice()
        val first = v.refusal("No walking route was found")
        assertNotNull(first)
        assertEquals(WalkVoice.Event.REFUSED, first!!.event)
        assertNull(v.refusal("No walking route was found"))
        // A route landing clears it, so a later failure can speak again.
        v.onRouteReplaced()
        assertNotNull(v.refusal("No walking route was found"))
    }

    @Test
    fun `reset forgets everything including arrival`() {
        val v = WalkVoice()
        val r = ordinary()
        walk(v, r, 400.0, arrivedAtM = 399.0)
        v.reset()
        val said = walk(v, r, 400.0, arrivedAtM = 399.0)
        assertTrue(said.any { it.event == WalkVoice.Event.DEPART })
        assertTrue(said.any { it.event == WalkVoice.Event.ARRIVED })
    }

    // -------------------------------------------------------- the wording

    @Test
    fun `no announcement invents a street name`() {
        val said = walk(WalkVoice(), ordinary(), 400.0, arrivedAtM = 399.0)
        // No road is known anywhere in this plan, so no sentence may contain a
        // road clause.
        assertTrue(
            said.none { it.text.contains(" onto ") || it.text.contains(" on ") },
            "a road clause appeared with no road: ${said.map { it.text }}",
        )
    }

    @Test
    fun `the spoken sentence is the instruction's own`() {
        // The structural form of "banner and voice share one source": the
        // announcement carries the very object the banner renders.
        val said = walk(WalkVoice(), ordinary(), 400.0)
        val turn = said.first { it.instruction?.kind == WalkManeuverKind.TURN_LEFT }
        val i = turn.instruction!!
        assertTrue(
            turn.text == i.spoken(WalkVoiceStage.APPROACH) ||
                turn.text == i.spoken(WalkVoiceStage.NOW),
        )
        assertTrue(turn.text.contains(i.action, ignoreCase = true))
    }

    @Test
    fun `state events are alerts and maneuvers are not`() {
        // The split the driver's existing VoiceMode reads: `speaksManeuvers`
        // governs the first group, `speaksAlerts` the second, so one setting
        // governs both modes and "alerts only" means the same thing on foot.
        assertTrue(WalkVoice.Event.DEPART.isManeuver)
        assertTrue(WalkVoice.Event.APPROACH.isManeuver)
        assertTrue(WalkVoice.Event.NOW.isManeuver)
        assertFalse(WalkVoice.Event.ARRIVED.isManeuver)
        assertFalse(WalkVoice.Event.OFF_ROUTE.isManeuver)
        assertFalse(WalkVoice.Event.REROUTING.isManeuver)
        assertFalse(WalkVoice.Event.REJOINED.isManeuver)
        assertFalse(WalkVoice.Event.REFUSED.isManeuver)
    }

    @Test
    fun `the approach window fits inside real walking maneuver spacing`() {
        // Derived rather than chosen: the 10th-percentile gap between two real
        // maneuvers is 33.5 m, so a 30 m warning fits inside nine tenths of
        // real legs without firing before the previous maneuver is finished.
        assertTrue(WalkVoice.APPROACH_M < 33.5)
        assertTrue(WalkVoice.NOW_M < WalkVoice.APPROACH_M)
        // And under the camera's approach entry, so the frame widens to show
        // the junction before the voice mentions it.
        assertTrue(WalkVoice.APPROACH_M < WalkCameraPolicy.APPROACH_ENTER_M)
    }

    @Test
    fun `closely spaced maneuvers each get their own announcement`() {
        // The real ordinary fixture plans a uturn 4.1 m before arrival.
        val r = route(
            400.0,
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.TURN_LEFT, 200.0),
                maneuver(WalkManeuverKind.TURN_RIGHT, 215.0),
                maneuver(WalkManeuverKind.ARRIVE, 400.0),
            ),
        )
        val said = walk(WalkVoice(), r, 400.0)
        assertTrue(said.any { it.instruction?.kind == WalkManeuverKind.TURN_LEFT })
        assertTrue(said.any { it.instruction?.kind == WalkManeuverKind.TURN_RIGHT })
    }
}
