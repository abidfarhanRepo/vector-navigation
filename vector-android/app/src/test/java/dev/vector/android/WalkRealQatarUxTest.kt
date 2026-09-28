package dev.vector.android

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.walk.WalkEtaText
import dev.vector.geo.walk.WalkFollowState
import dev.vector.geo.walk.WalkInstruction
import dev.vector.geo.walk.WalkInstructions
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkRefusalKind
import dev.vector.geo.walk.WalkRefusalText
import dev.vector.geo.walk.WalkRoute
import dev.vector.geo.walk.WalkVoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walking UX, driven over REAL Qatar walks (V7.4 4C final).
 *
 * `WalkInstructionTest` and `WalkVoiceTest` state the rules on synthetic data
 * where every attribute is exactly what the test chose. This file is the
 * evidence they survive contact with OSM: the same mappers and the same
 * announcer, run over the six captured `/foot` payloads from the real 260912
 * bake plus the real `pedestrian_network_split` refusal body.
 *
 * Between them the fixtures cover every case the brief names — an ordinary
 * walk, a crossing-heavy walk (3 crossings), a stairs walk (1 staircase and 15
 * crossings), a barrier detour, a 52.9 m walk that is nothing but depart and
 * arrive, a 22.4 km walk with 60 maneuvers, a genuine split refusal, 24 turns,
 * 72 slight turns, and events as little as 4.1 m apart.
 *
 * The assertions are deliberately about what is NOT said. A synthetic test can
 * only check the attributes it invented; these payloads carry the real
 * distribution of absent ones — `road: null`, untyped crossings, staircases
 * nobody surveyed — and those are what a mapper invents facts about.
 */
class WalkRealQatarUxTest {

    private fun body(name: String): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/vector-contract/$name")
            ?.readBytes()?.decodeToString()
            ?: throw AssertionError("missing contract snapshot $name")
    )

    private fun contract(name: String) =
        WalkContractParser.parse(body(name).getJSONArray("features").getJSONObject(0))

    private fun route(name: String) = WalkRoute.of(contract(name))

    /** The backend's own walking pace. */
    private val paceMs = 1.35

    /** Every instruction a walk produces, one per plan entry. */
    private fun instructions(name: String): List<WalkInstruction> {
        val r = route(name)
        return r.plan.mapIndexed { i, m ->
            WalkInstructions.of(
                m, planIndex = i,
                status = dev.vector.geo.walk.WalkEventStatus.AHEAD,
                distanceM = m.distanceM,
            )
        }
    }

    /**
     * Walk a real route at 1 Hz and collect everything the voice says.
     *
     * The final fix is placed at EXACTLY `totalM` rather than wherever the
     * 1.35 m stride happens to land. Without it the last fix of the crossing
     * walk falls at 1846.8 m of 1848.1 — 1.3 m short of the arrival condition
     * — and the walk would appear never to arrive. That is an artefact of the
     * sampling, not of the route, and the same closing step
     * `WalkRealQatarTest.walkThrough` already takes for the same reason.
     */
    private fun speak(name: String): List<WalkVoice.Announcement> {
        val r = route(name)
        val voice = WalkVoice()
        val tracker = dev.vector.geo.walk.WalkProgressTracker(r)
        val out = ArrayList<WalkVoice.Announcement>()
        val positions = ArrayList<Double>()
        var d = 0.0
        while (d < r.totalM) {
            positions.add(d); d += paceMs
        }
        positions.add(r.totalM)
        for (at in positions) {
            voice.update(
                WalkVoice.Inputs(
                    progress = tracker.update(at),
                    followState = WalkFollowState.ON_ROUTE,
                    arrived = at >= r.totalM - ARRIVAL_RADIUS_M,
                    rerouting = false,
                )
            )?.let { out.add(it) }
        }
        return out
    }

    private companion object {
        val WALKS = listOf(
            "foot-ordinary-4b4.json",
            "foot-crossing-4b4.json",
            "foot-stairs-4b4.json",
            "foot-barrier-4b4.json",
            "foot-short-4b4.json",
            "foot-long-4b4.json",
        )

        /**
         * How far `duration_s` may differ from `cost.pace_s` on the wire.
         *
         * 0.05 s. The two are equal BY CONSTRUCTION (4B.4 D1) and the backend
         * asserts it exactly in-process — `vector-routing` is 600/600 green on
         * that test. What the captured payloads show is the SERIALIZER
         * rounding `duration_s` to one decimal place: 1369.0 against a
         * `pace_s` of 1368.97. Half of the last retained digit is 0.05, which
         * is what this tolerance is.
         *
         * Recorded rather than waved away, because "the ETA field equals pace
         * time" is the decision this whole stage is built on. It is a
         * presentation rounding of 0.03 s on a 23-minute walk, it is identical
         * in every fixture, and 4C.1's own evidence quotes the same pair as
         * equal — so it does not touch any claim made here. It is not a
         * contract defect and nothing was changed in the backend for it.
         */
        const val ETA_ROUNDING_S = 0.05

        /**
         * Words no walking instruction may ever contain.
         *
         * Each one is a claim the contract cannot support: Vector has no
         * accessibility data (14 handrail and 398 incline edges on the entire
         * bake), no safety model, no shade in this stage, and no signal
         * timing anywhere in the product.
         */
        val FORBIDDEN = listOf(
            "accessible", "accessibility", "step-free", "wheelchair",
            "safe", "safely", "danger", "priority", "right of way",
            "shade", "shaded", "sunny", "cooler",
            "green light", "red light", "wait for", "when clear",
            "well-lit", "smooth", "quality",
        )
    }

    // ------------------------------------------------ 1-6: all six walks

    @Test
    fun `every real walk produces an instruction for every planned maneuver`() {
        for (name in WALKS) {
            val instructions = instructions(name)
            val plan = route(name).plan
            assertEquals("$name: dropped a maneuver", plan.size, instructions.size)
            for (i in instructions) {
                assertTrue("$name: blank action for ${i.kind}", i.action.isNotBlank())
                assertTrue("$name: blank banner for ${i.kind}", i.banner.isNotBlank())
            }
        }
    }

    @Test
    fun `no real walk ever invents a road name`() {
        // The measurement that makes this worth asserting: Qatari walks are
        // full of unnamed ways, and a mapper that filled the gap with a
        // highway class or a placeholder would be caught here rather than in
        // front of a person.
        for (name in WALKS) {
            for (i in instructions(name)) {
                if (!i.roadKnown) {
                    assertNull("$name: named a road it does not know", i.roadName)
                    assertFalse(
                        "$name: road clause with no road — \"${i.banner}\"",
                        i.banner.contains(" onto ") || i.banner.contains(" on "),
                    )
                }
                assertFalse(
                    "$name: the literal word null reached a banner — \"${i.banner}\"",
                    i.banner.contains("null", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `no real walk makes an accessibility, safety or shade claim`() {
        for (name in WALKS) {
            for (i in instructions(name)) {
                val rendered = "${i.banner} ${i.detail ?: ""}"
                for (claim in FORBIDDEN) {
                    assertFalse(
                        "$name: forbidden claim \"$claim\" in \"$rendered\"",
                        rendered.contains(claim, ignoreCase = true),
                    )
                }
            }
        }
    }

    @Test
    fun `no provenance token reaches a rendered string on any real walk`() {
        for (name in WALKS) {
            for (i in instructions(name)) {
                val rendered = "${i.banner} ${i.detail ?: ""}"
                for (leak in listOf(
                    "catalog", "road_graph", "shared_node", "source",
                    "way", "_", "osm",
                )) {
                    assertFalse(
                        "$name: \"$leak\" leaked into \"$rendered\"",
                        rendered.contains(leak, ignoreCase = true),
                    )
                }
            }
        }
    }

    // ------------------------------------------------- 2: crossing-heavy

    @Test
    fun `the real crossing walk names a road only where the map established one`() {
        // ## What the real payload actually contains, and why it is the
        // ## single best piece of evidence in this file
        //
        // All three crossings carry `crossed_road_source:
        // road_graph_shared_node` — the strong provenance. But only ONE of
        // them also carries a usable name:
        //
        //   14.9 m   residential   "Jasim Bin Hamad Street"   -> nameable
        //   715.6 m  primary_link  name: null, name_en: null  -> NOT nameable
        //   1641.5 m primary_link  name: null, name_en: null  -> NOT nameable
        //
        // So this one fixture exercises both halves of the rule at once, on
        // real data: attribution alone is not enough to name a road, and an
        // unnamed slip road must not be described by its highway class.
        val crossings = instructions("foot-crossing-4b4.json")
            .filter { it.kind == WalkManeuverKind.CROSS }
        assertEquals("the crossing fixture should carry 3 crossings", 3, crossings.size)

        val named = crossings.filter { it.roadKnown }
        val unnamed = crossings.filter { !it.roadKnown }
        assertEquals("exactly one of these crossings is nameable", 1, named.size)
        assertEquals(2, unnamed.size)

        // The attributed, named one says which road — that is the whole point
        // of preserving provenance.
        assertEquals("Cross Jasim Bin Hamad Street", named.single().banner)

        // The unnamed ones say the generic true thing, and specifically do NOT
        // fall back to "primary_link".
        for (c in unnamed) {
            assertEquals("Cross the road", c.banner)
            assertNull(c.roadName)
            assertFalse(c.banner.contains("primary", ignoreCase = true))
            assertFalse(c.banner.contains("link", ignoreCase = true))
        }
    }

    @Test
    fun `real crossing provenance decides whether a type is spoken`() {
        // The four-state provenance is preserved end to end. Whatever the
        // backend said, the LABEL is present only when a type was recorded,
        // and never derived from the provenance itself.
        var typed = 0
        var untyped = 0
        for (name in WALKS) {
            val r = route(name)
            for (m in r.plan) {
                val c = m.crossing ?: continue
                val detail = WalkInstructions.detailFor(c)
                if (c.type == null) {
                    assertNull(
                        "$name: claimed a crossing type where the map recorded none",
                        detail.typeLabel,
                    )
                    untyped++
                } else {
                    typed++
                }
            }
        }
        assertTrue("no crossings at all in the fixture set", typed + untyped > 0)
    }

    @Test
    fun `the real crossing walk keeps its wait out of the shown duration`() {
        // 4B.4 D1, on real numbers: duration_s 1369.0 == cost.pace_s, and the
        // 132 s of expected crossing delay is reported separately.
        val c = contract("foot-crossing-4b4.json")
        val eta = c.eta
        assertEquals(c.cost!!.paceS!!, eta.displayDurationS!!, ETA_ROUNDING_S)
        assertEquals(132.0, eta.crossingWaitS!!, 0.01)
        // The shown time is the pace time.
        assertEquals("23 min", WalkEtaText.walkTime(eta))
        // And the delay is additive, hedged, and clearly not part of it.
        val delay = WalkEtaText.crossingDelay(eta)
        assertNotNull(delay)
        assertTrue(delay!!.startsWith("plus about"))
        assertTrue(delay.contains("crossing"))
    }

    // -------------------------------------------------------- 3: stairs

    @Test
    fun `the real stairs walk says only what was surveyed`() {
        val r = route("foot-stairs-4b4.json")
        val stairs = r.plan.filter { it.kind == WalkManeuverKind.STAIRS }
        assertEquals("the stairs fixture should carry 1 staircase", 1, stairs.size)
        val detail = WalkInstructions.detailFor(stairs.single().stairs!!)
        // Nobody counted these steps, recorded a handrail or an incline — the
        // overwhelmingly common real case. So nothing is said about any of
        // them, and specifically no negative claim is made.
        assertNull(detail.stepCountLabel)
        assertNull(detail.handrail)
        assertNull(detail.inclineLabel)
        val i = instructions("foot-stairs-4b4.json")
            .single { it.kind == WalkManeuverKind.STAIRS }
        assertEquals("Use the stairs", i.banner)
        assertNull("invented a stairs attribute line", i.detail)
    }

    // ----------------------------------------------------- 4: barrier

    @Test
    fun `the real barrier detour is presented as an ordinary walk`() {
        // The 2,504 m locked-gate detour, ratio 38.9. Nothing in the UX may
        // explain or excuse it: the route is the honest answer the backend
        // produced, and the diagnostics that say why are not user-facing.
        val c = contract("foot-barrier-4b4.json")
        assertEquals(2504.2, c.distanceM!!, 0.05)
        assertEquals(38.9, c.diagnostics.detourRatio!!, 0.05)
        for (i in instructions("foot-barrier-4b4.json")) {
            val rendered = "${i.banner} ${i.detail ?: ""}"
            for (word in listOf("gate", "barrier", "locked", "detour", "blocked")) {
                assertFalse(
                    "the barrier fixture explained itself: \"$rendered\"",
                    rendered.contains(word, ignoreCase = true),
                )
            }
        }
    }

    // ------------------------------------------------------- 5: short

    @Test
    fun `the 52 metre walk is depart and arrive, and still speaks`() {
        // Nothing to say, and a walk that begins in silence reads as a walk
        // that failed to begin.
        val said = speak("foot-short-4b4.json")
        assertTrue("the short walk said nothing at all", said.isNotEmpty())
        assertEquals(WalkVoice.Event.DEPART, said.first().event)
        assertTrue(
            "the short walk never announced arrival",
            said.any { it.event == WalkVoice.Event.ARRIVED },
        )
        // And no turn was invented on a route that has none.
        assertTrue(said.none { it.instruction?.kind?.isTurn == true })
    }

    // -------------------------------------------------------- 6: long

    @Test
    fun `the 22 kilometre walk announces each maneuver at most twice`() {
        // 60 maneuvers over 22.4 km, at 1 Hz, is ~16,500 fixes. This is the
        // test that would catch voice spam at scale.
        val said = speak("foot-long-4b4.json")
        val perManeuver = said
            .mapNotNull { it.instruction?.planIndex }
            .groupingBy { it }.eachCount()
        for ((index, count) in perManeuver) {
            assertTrue(
                "plan entry $index announced $count times on the long walk",
                count <= 2,
            )
        }
    }

    @Test
    fun `every announcement on every real walk is distinct from the last`() {
        for (name in WALKS) {
            val said = speak(name)
            for (i in 1 until said.size) {
                assertFalse(
                    "$name: repeated \"${said[i].text}\" back to back",
                    said[i].text == said[i - 1].text,
                )
            }
        }
    }

    @Test
    fun `arrival is announced exactly once on every real walk`() {
        for (name in WALKS) {
            val said = speak(name)
            assertEquals(
                "$name: arrival announcements",
                1, said.count { it.event == WalkVoice.Event.ARRIVED },
            )
            // And it is the LAST thing said: nothing follows the end of a walk.
            assertEquals(
                "$name: something was said after arrival",
                WalkVoice.Event.ARRIVED, said.last().event,
            )
        }
    }

    // ------------------------------------------------------- 7: the split

    @Test
    fun `the real split refusal is classified and explained without a route`() {
        val raw = body("foot-split-4b4.json")
        // The real 404 body, unchanged since 4A.1.
        assertEquals("pedestrian_network_split", raw.getString("reason"))
        assertEquals(WalkRefusal.NETWORK_SPLIT, WalkRefusal.of(raw))
        // There is no walk in it, so nothing can be drawn.
        assertNull(WalkContractParser.parseCollection(raw))

        val kind = WalkRefusalKind.NETWORK_SPLIT
        val title = WalkRefusalText.title(kind)
        val detail = WalkRefusalText.detail(kind)
        // It is told apart from the generic refusal in WORDS, not just in an
        // enum — which is the whole point of 4A.1 having preserved the reason.
        assertFalse(title == WalkRefusalText.title(WalkRefusalKind.NO_ROUTE))
        assertFalse(detail == WalkRefusalText.detail(WalkRefusalKind.NO_ROUTE))
        // And it describes the map rather than the world.
        assertTrue(detail.contains("connect"))
        assertFalse(detail.contains("cannot walk", ignoreCase = true))
    }

    @Test
    fun `no refusal text leaks the backend's diagnostics`() {
        val raw = body("foot-split-4b4.json")
        // The body carries origin_snap_m, radius_m and a long internal
        // message. None of it is for a person.
        assertTrue(raw.has("message"))
        for (kind in WalkRefusalKind.entries) {
            val all = WalkRefusalText.title(kind) + " " + WalkRefusalText.detail(kind) +
                " " + WalkRefusalText.spoken(kind)
            for (leak in listOf("snap", "component", "radius", "node", "graph", "_m")) {
                assertFalse("$kind leaked \"$leak\"", all.contains(leak, ignoreCase = true))
            }
        }
    }

    // ---------------------------------------- 8/9: turns and slight turns

    @Test
    fun `real turns and slight turns are worded differently`() {
        // 24 turns and 72 slight turns across the fixture set. A mapper that
        // collapsed them would be telling a walker to turn where the backend
        // said to bear.
        var turns = 0
        var slights = 0
        for (name in WALKS) {
            for (i in instructions(name)) {
                when (i.kind) {
                    WalkManeuverKind.TURN_LEFT -> {
                        turns++; assertTrue(i.banner.startsWith("Turn left"))
                    }
                    WalkManeuverKind.TURN_RIGHT -> {
                        turns++; assertTrue(i.banner.startsWith("Turn right"))
                    }
                    WalkManeuverKind.SLIGHT_LEFT -> {
                        slights++; assertTrue(i.banner.startsWith("Bear left"))
                    }
                    WalkManeuverKind.SLIGHT_RIGHT -> {
                        slights++; assertTrue(i.banner.startsWith("Bear right"))
                    }
                    else -> Unit
                }
            }
        }
        assertTrue("no turns in the fixture set", turns > 0)
        assertTrue("no slight turns in the fixture set", slights > 0)
    }

    @Test
    fun `the real u-turns are announced as u-turns`() {
        // The ordinary fixture plans one 4.1 m before arrival, and the stairs
        // fixture two.
        val uturns = WALKS.flatMap { instructions(it) }
            .filter { it.kind == WalkManeuverKind.UTURN }
        assertTrue("no u-turns in the fixture set", uturns.isNotEmpty())
        assertTrue(uturns.all { it.banner.startsWith("U-turn") })
    }

    // -------------------------------------------- 10: closely spaced events

    @Test
    fun `closely spaced real events are each announced`() {
        // The ordinary walk's uturn sits 4.1 m before its arrive, which is
        // three seconds apart at walking pace. The announcer must not let one
        // swallow the other.
        val r = route("foot-ordinary-4b4.json")
        val gaps = r.plan.zipWithNext { a, b -> b.distanceM - a.distanceM }
        assertTrue(
            "the ordinary fixture should contain a sub-5 m gap",
            gaps.any { it < 5.0 },
        )
        val said = speak("foot-ordinary-4b4.json")
        val announced = said.mapNotNull { it.instruction?.planIndex }.toSet()
        // Every significant maneuver reached the walker. `continue` and
        // `depart`/`arrive` are handled separately by design.
        val significant = r.plan.withIndex().filter { (_, m) ->
            m.kind != WalkManeuverKind.CONTINUE &&
                m.kind != WalkManeuverKind.DEPART &&
                m.kind != WalkManeuverKind.ARRIVE
        }.map { it.index }
        for (i in significant) {
            assertTrue("plan entry $i was never announced", i in announced)
        }
    }

    // ----------------------------------------------------- determinism

    @Test
    fun `the same real walk always produces the same words`() {
        for (name in WALKS) {
            val first = instructions(name).map { it.banner to it.detail }
            repeat(3) {
                assertEquals("$name: rendering is not deterministic", first,
                    instructions(name).map { it.banner to it.detail })
            }
        }
    }

    @Test
    fun `the same real walk always speaks the same sequence`() {
        for (name in WALKS) {
            val first = speak(name).map { it.event to it.text }
            assertEquals("$name: the voice is not deterministic", first,
                speak(name).map { it.event to it.text })
        }
    }

    // -------------------------------------------- the ETA decision, on all

    @Test
    fun `the shown duration equals pace time on every real walk`() {
        // Asserted across the whole fixture set, because this is the field
        // meaning four shipping features depend on.
        for (name in WALKS) {
            val c = contract(name)
            val eta = c.eta
            val cost = c.cost ?: continue
            assertEquals(
                "$name: duration_s drifted from cost.pace_s",
                cost.paceS!!, eta.displayDurationS!!, ETA_ROUNDING_S,
            )
            // And whatever crossing delay exists is NOT inside it.
            val wait = eta.crossingWaitS ?: 0.0
            if (wait > 0.0) {
                assertTrue(
                    "$name: the crossing wait appears to be inside duration_s",
                    eta.selectionCostS!! > eta.displayDurationS!!,
                )
            }
        }
    }

    @Test
    fun `a walk with no crossing delay says nothing about one`() {
        // The ordinary and long walks carry zero crossings, so the additive
        // phrase must be absent entirely rather than reading "plus about 0".
        for (name in listOf("foot-ordinary-4b4.json", "foot-long-4b4.json")) {
            val eta = contract(name).eta
            assertNull("$name: invented a crossing delay", WalkEtaText.crossingDelay(eta))
        }
    }

    // --------------------------------------------------- the whole surface

    @Test
    fun `every real walk is routed under the one supported profile`() {
        for (name in WALKS) {
            val c = contract(name)
            assertTrue("$name: profile not supported", c.profileSupported)
            assertEquals(
                dev.vector.geo.walk.WalkingProfile.GENERAL, c.walkingProfile,
            )
            // And no maneuver kind was skipped as unknown.
            assertTrue(
                "$name: unknown maneuver kinds ${c.unknownManeuverKinds}",
                c.unknownManeuverKinds.isEmpty(),
            )
        }
    }

    @Test
    fun `a real walk drives the whole session end to end`() {
        // The integration proof: the real geometry, through the real
        // follower, camera, instruction mapper and announcer, to arrival.
        val r = route("foot-crossing-4b4.json")
        val s = WalkNavSession(r)
        val cam = CameraState(mode = CameraMode.FOLLOW)
        var t = 100_000L
        var d = 0.0
        var arrivals = 0
        var speaks = 0
        var reroutes = 0
        while (d <= r.totalM) {
            val at = r.index!!.pointAt(d)!!.position
            t += 1000
            val res = s.onFix(at, t, cam, Phase.NAVIGATING)
            arrivals += res.actions.count { it is WalkNavSession.Action.Arrived }
            speaks += res.actions.count { it is WalkNavSession.Action.Speak }
            reroutes += res.actions.count { it is WalkNavSession.Action.Reroute }
            d += paceMs
        }
        assertEquals("arrival did not fire exactly once", 1, arrivals)
        assertTrue("the walk was silent", speaks > 0)
        assertEquals("a clean walk asked for a reroute", 0, reroutes)
        assertTrue(s.state.arrived)
    }
}
