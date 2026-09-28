package dev.vector.android

import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkingProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing the V7.4 4B.4 `/foot` contract (V7.4 4C.1).
 *
 * Every payload here is a REAL response captured from the real Qatar bake
 * (release 260912) — see `V7.4-EVIDENCE/make_walk_ux_fixtures.py`, whose first
 * act is to regenerate the two 4B.4 snapshots and refuse to proceed unless
 * they come back fact-for-fact identical. So these assertions are about what
 * the backend actually sends, not about an idealised fixture someone wrote to
 * make a parser pass.
 *
 * The tests are organised around the four things that are easy to break
 * quietly: null preservation, provenance preservation, the three-way ETA
 * split, and the profile boundary.
 */
class WalkContractParseTest {

    private fun feature(name: String): JSONObject =
        JSONObject(
            javaClass.getResourceAsStream("/vector-contract/$name")
                ?.readBytes()?.decodeToString()
                ?: throw AssertionError("missing contract snapshot $name")
        ).getJSONArray("features").getJSONObject(0)

    private fun parse(name: String) = WalkContractParser.parse(feature(name))

    // ------------------------------------------------------------- the shape

    @Test
    fun `a real ordinary walk parses into the frozen contract`() {
        val c = parse("foot-ordinary-4b4.json")
        assertEquals(1, c.contractVersion)
        assertEquals(WalkingProfile.GENERAL, c.walkingProfile)
        assertEquals("general", c.walkingProfileRaw)
        assertEquals("foot", c.mode)
        assertEquals(583.2, c.distanceM!!, 0.05)
        assertEquals(432.0, c.durationS!!, 0.05)
        assertEquals(0.0, c.stepsM!!, 1e-9)
        assertEquals(0.0, c.crossingM!!, 1e-9)
        assertEquals(33, c.nodes)
        assertEquals(33, c.geometry.size)
        assertEquals(10, c.plan.size)
        assertEquals(WalkManeuverKind.DEPART, c.plan.first().kind)
        assertEquals(WalkManeuverKind.ARRIVE, c.plan.last().kind)
        // The 4B.1 fact stream rides along beside the 4B.2 plan.
        assertTrue(c.facts.isNotEmpty())
        assertNotNull(c.cost)
    }

    @Test
    fun `segment arrays are parsed aligned to the geometry`() {
        val c = parse("foot-crossing-4b4.json")
        val expected = c.nodes!! - 1
        assertTrue("segment arrays must align", c.segments.alignedTo(expected))
        assertEquals(expected, c.segments.classes.size)
        assertEquals(expected, c.segments.costS.size)
        // `segment_cost_s` is WEIGHTED cost, so on a crossing walk it exceeds
        // the pace time of the same segments. Parsed as its own array, never
        // conflated with duration.
        assertTrue(c.segments.costS.filterNotNull().sum() > 0.0)
    }

    @Test
    fun `an absent segment array degrades to empty rather than crashing`() {
        // An older backend that predates the 4A.4/4B.3 arrays.
        val bare = JSONObject(
            """{"properties":{"profile":"foot","distance_m":100.0,"duration_s":74.0,
               "walking_profile":"general","contract_version":1},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val c = WalkContractParser.parse(bare)
        assertTrue(c.segments.classes.isEmpty())
        assertTrue(c.segments.costS.isEmpty())
        assertTrue(c.plan.isEmpty())
        assertNull(c.cost)
        // Absent is absent, not zero.
        assertNull(c.stepsM)
        assertNull(c.crossingM)
        // And an empty array set is trivially "aligned" — a caller must not be
        // told the arrays disagree with the geometry when there are none.
        assertTrue(c.segments.alignedTo(1))
    }

    @Test
    fun `the maneuver plan uses only the documented vocabulary on every fixture`() {
        for (name in FIXTURES) {
            val c = parse(name)
            assertTrue("$name sent an unknown kind: ${c.unknownManeuverKinds}",
                c.unknownManeuverKinds.isEmpty())
            assertEquals("$name must start with depart",
                WalkManeuverKind.DEPART, c.plan.first().kind)
            assertEquals("$name must end with arrive",
                WalkManeuverKind.ARRIVE, c.plan.last().kind)
        }
    }

    @Test
    fun `an unknown maneuver kind is skipped and recorded, never guessed`() {
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"depart","index":0,"distance_m":0.0},
                 {"kind":"teleport","index":1,"distance_m":10.0},
                 {"kind":"arrive","index":2,"distance_m":20.0}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val c = WalkContractParser.parse(json)
        assertEquals(listOf(WalkManeuverKind.DEPART, WalkManeuverKind.ARRIVE),
            c.plan.map { it.kind })
        assertEquals(listOf("teleport"), c.unknownManeuverKinds)
    }

    // ---------------------------------------------------------------- profile

    @Test
    fun `every real fixture routed under walking_profile general`() {
        for (name in FIXTURES) {
            val c = parse(name)
            assertEquals(name, WalkingProfile.GENERAL, c.walkingProfile)
            assertTrue(name, c.profileSupported)
        }
    }

    @Test
    fun `an unknown profile is refused at the client boundary, not silently walked`() {
        // The mirror of the backend's HTTP 400 (4B.4 D2). A client that
        // rendered this walk would be presenting a route optimised for
        // something it cannot name.
        val json = JSONObject(
            """{"properties":{"profile":"foot","walking_profile":"comfort",
                 "distance_m":100.0,"duration_s":74.0},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val c = WalkContractParser.parse(json)
        assertNull(c.walkingProfile)
        assertFalse(c.profileSupported)
        // The raw value survives, so the refusal is diagnosable.
        assertEquals("comfort", c.walkingProfileRaw)
    }

    @Test
    fun `no accessibility or comfort profile exists to be selected`() {
        assertEquals(listOf(WalkingProfile.GENERAL), WalkingProfile.entries)
        for (claim in listOf("accessibility", "comfort", "pleasant", "wheelchair")) {
            assertNull("$claim must not be a profile", WalkingProfile.parse(claim))
        }
    }

    // ------------------------------------------------------------ null safety

    @Test
    fun `road null is preserved as null and never becomes the word null`() {
        // The `org.json` trap this codebase already shipped once: optString on
        // a JSON null yields the four-character string "null" on Android.
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"cross","index":1,"distance_m":10.0,
                  "road":null,"crossed_road_source":null,
                  "crossing":{"type":null,"type_source":"catalog_node","distance_m":8.0}}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val m = WalkContractParser.parse(json).plan.single()
        assertNull(m.road)
        assertNull(m.crossing!!.road)
        assertNull(m.crossing!!.crossedRoadSource)
        assertNull(m.crossing!!.type)
        assertFalse(m.crossing!!.roadKnown)
        // An untyped crossing is still a crossing: the provenance survives.
        assertEquals("catalog_node", m.crossing!!.typeSource)
    }

    @Test
    fun `an all-null road object is the absence of an identity, not an identity`() {
        // Real shape: a crossing over an unnamed slip road sends
        // {"highway":"primary_link","name":null,"name_en":null}; a fully null
        // object must not read as "a road we know about".
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"turn_left","index":1,"distance_m":10.0,
                  "road":{"highway":null,"name":null,"name_en":null}}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        assertNull(WalkContractParser.parse(json).plan.single().road)
    }

    @Test
    fun `absent stairs attributes stay absent rather than defaulting to zero`() {
        val c = parse("foot-stairs-4b4.json")
        val stairs = c.plan.single { it.kind == WalkManeuverKind.STAIRS }
        assertEquals(5.9, stairs.stairs!!.distanceM!!, 0.05)
        // Nobody counted the steps on this staircase. 0 would be a claim.
        assertNull(stairs.stairs!!.stepCount)
        assertNull(stairs.stairs!!.handrail)
        assertNull(stairs.stairs!!.incline)
        // 4B.2: a stairs maneuver's road is legitimately null.
        assertNull(stairs.road)
    }

    @Test
    fun `a null-valued segment array entry stays null`() {
        val c = parse("foot-ordinary-4b4.json")
        // This walk is entirely on residential streets: no footway tags at all.
        assertTrue(c.segments.footway.all { it == null })
        assertTrue(c.segments.crossing.all { it == null })
        assertTrue(c.segments.classes.all { it != null })
    }

    // ---------------------------------------------------- traffic lights (V7)

    @Test
    fun `a surveyed signal on a crossing parses into the contract`() {
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"cross","index":1,"distance_m":10.0,
                  "crossed_road_source":"road_graph_shared_node",
                  "road":{"highway":"primary","name":"Jasim Bin Hamad Street"},
                  "crossing":{"type":null,"type_source":"catalog_node","distance_m":11.1,
                    "signals":[{"id":"n1001","source":"osm:node:1001",
                                "node":"51.5300000,25.2860000"}]}}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val cr = WalkContractParser.parse(json).plan.single().crossing!!
        assertEquals(listOf("n1001"), cr.signals.map { it.id })
        assertEquals("osm:node:1001", cr.signals.single().source)
        assertEquals("51.5300000,25.2860000", cr.signals.single().node)
        assertTrue(cr.signalControlled)
    }

    @Test
    fun `a crossing with no signal array is not signal controlled`() {
        // A pre-V7 payload and a genuinely signal-free crossing are the same
        // answer, and that is correct: neither has a signal to tell about.
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"cross","index":1,"distance_m":10.0,
                  "crossing":{"type":null,"type_source":"catalog_node","distance_m":8.0}}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val cr = WalkContractParser.parse(json).plan.single().crossing!!
        assertEquals(emptyList<String>(), cr.signals.map { it.id })
        assertFalse(cr.signalControlled)
    }

    @Test
    fun `a signal entry without provenance is dropped, and the rest survive`() {
        // Provenance is a requirement, not decoration: a placement Vector
        // cannot trace to a survey is a placement it may not render.
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"cross","index":1,"distance_m":10.0,
                  "crossing":{"type":null,"distance_m":8.0,
                    "signals":[{"id":"nNoSource","node":"x,y"},
                               {"id":"n1001","source":"osm:node:1001","node":"x,y"},
                               {"source":"osm:node:9999","node":"x,y"}]}}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val cr = WalkContractParser.parse(json).plan.single().crossing!!
        assertEquals(listOf("n1001"), cr.signals.map { it.id })
    }

    @Test
    fun `a state key on the wire is discarded, never surfaced`() {
        // The parser reads three keys and has no field for anything else, so a
        // payload that volunteered a phase cannot reach a banner or a map. This
        // is the client half of the invariant; the backend never sends one.
        val json = JSONObject(
            """{"properties":{"walking_profile":"general","maneuver_plan":[
                 {"kind":"cross","index":1,"distance_m":10.0,
                  "crossing":{"type":null,"distance_m":8.0,
                    "signals":[{"id":"n1001","source":"osm:node:1001","node":"x,y",
                                "phase":"green","seconds_remaining":12,
                                "cycle_s":90}]}}]},
               "geometry":{"type":"LineString","coordinates":[[51.0,25.0],[51.001,25.0]]}}"""
        )
        val sig = WalkContractParser.parse(json).plan.single().crossing!!.signals.single()
        assertEquals("n1001", sig.id)
        assertEquals("osm:node:1001", sig.source)
        // And the rendering built from it says nothing about state, because the
        // only string it can produce is the presence fact.
        val instruction = dev.vector.geo.walk.WalkInstructions.of(
            WalkContractParser.parse(json).plan.single(), 0,
            dev.vector.geo.walk.WalkEventStatus.AHEAD, 10.0,
        )
        assertEquals("Signal-controlled crossing", instruction.detail)
    }

    // ------------------------------------------------------------ provenance

    @Test
    fun `crossing provenance survives parsing on the real crossing walk`() {
        val c = parse("foot-crossing-4b4.json")
        val crossings = c.plan.filter { it.kind == WalkManeuverKind.CROSS }
        assertEquals(3, crossings.size)
        for (x in crossings) {
            val cr = x.crossing!!
            // Every crossing on this walk is typed by a nearby catalog node —
            // a weaker statement than the way's own tag, and one a consumer
            // must be able to tell apart.
            assertEquals("catalog", cr.typeSource)
            assertNotNull(cr.type)
            assertEquals("road_graph_shared_node", cr.crossedRoadSource)
            assertTrue(cr.roadKnown)
            // The span is a sourced fact, with its geometry positions.
            assertTrue(cr.distanceM!! > 0.0)
            assertNotNull(cr.enterIndex)
            assertNotNull(cr.leaveIndex)
        }
        assertEquals(
            listOf("traffic_signals", "traffic_signals", "marked"),
            crossings.map { it.crossing!!.type },
        )
    }

    @Test
    fun `all four crossing type provenances are representable`() {
        // The stairs fixture is the long real walk that exercises three of the
        // four states in one payload; `catalog_node` and a null source are
        // pinned by the synthetic case above.
        val c = parse("foot-stairs-4b4.json")
        val sources = c.plan.filter { it.kind == WalkManeuverKind.CROSS }
            .map { it.crossing!!.typeSource }.toSet()
        assertTrue("expected several provenances, got $sources", sources.size >= 2)
        assertTrue(sources.all { it in setOf("way", "catalog", "catalog_node", null) })
    }

    @Test
    fun `source fact types are carried so a maneuver can be traced back`() {
        val c = parse("foot-crossing-4b4.json")
        val cross = c.plan.first { it.kind == WalkManeuverKind.CROSS }
        assertEquals(listOf("cross"), cross.sourceFactTypes)
    }

    // ------------------------------------------------------------------- ETA

    @Test
    fun `duration is pure pace and cost is separate on a real crossing walk`() {
        val c = parse("foot-crossing-4b4.json")
        val cost = c.cost!!
        // 4B.4 D1: duration_s == cost.pace_s, by construction.
        assertEquals(c.durationS!!, cost.paceS!!, 0.06)
        // The weighted cost the SEARCH minimised is a different number.
        assertEquals(1500.97, cost.costS!!, 0.01)
        assertEquals(132.0, cost.penaltyS!!, 0.01)
        // And the whole penalty here is expected crossing delay, which is
        // deliberately NOT in the duration a user is shown.
        assertEquals(132.0, cost.crossing!!.waitS, 0.01)
        assertEquals(132.0, cost.factorS["crossing_wait"]!!, 0.01)
        assertTrue(cost.costS!! > c.durationS!!)

        val eta = c.eta
        assertEquals(c.durationS, eta.displayDurationS)
        assertEquals(cost.costS, eta.selectionCostS)
        assertEquals(cost.crossing!!.waitS, eta.crossingWaitS)
    }

    @Test
    fun `the shown duration excludes crossing wait on every real fixture`() {
        for (name in FIXTURES) {
            val c = parse(name)
            val cost = c.cost ?: continue
            assertEquals("$name: duration_s must be pure pace time",
                c.durationS!!, cost.paceS!!, 0.06)
            assertTrue("$name: cost must be >= pace", cost.costS!! >= cost.paceS!! - 0.01)
        }
    }

    @Test
    fun `selection factors are explicit and exposure is measured regardless`() {
        val c = parse("foot-ordinary-4b4.json")
        val cost = c.cost!!
        assertEquals(listOf("stairs", "incline", "crossing_wait"), cost.selectionFactors)
        // This walk triggered none of them: sparse factors, and honest zeros
        // in the exposure blocks. An OFF preference can never be mistaken for
        // having influenced the route.
        assertTrue(cost.factorS.isEmpty())
        assertEquals(0, cost.stairs!!.edges)
        assertEquals(0, cost.crossing!!.edges)
        assertEquals(0.0, cost.crossing!!.waitS, 1e-9)
        // Surface/lit/width/sidewalk are OFF by default and absent from the
        // selection factors, yet still measured.
        assertNotNull(cost.surface)
        assertNotNull(cost.lit)
        assertFalse(cost.selectionFactors.contains("surface"))
    }

    // ------------------------------------------------------------ diagnostics

    @Test
    fun `snap and component diagnostics are preserved`() {
        val c = parse("foot-crossing-4b4.json")
        val d = c.diagnostics
        assertEquals(73.5, d.snapMaxM!!, 0.05)
        assertEquals(0, d.component)
        assertEquals(353448, d.componentNodes)
        assertEquals(1.07, d.detourRatio!!, 0.01)
        assertEquals(1.35, d.walkSpeedMs!!, 1e-9)
    }

    @Test
    fun `the barrier-affected route reports its detour rather than hiding it`() {
        // 4A.3's case A: a locked gate in the middle of a walkable way. The
        // walk is real and 2.5 km long, and the wire says WHY.
        val c = parse("foot-barrier-4b4.json")
        assertEquals(2504.2, c.distanceM!!, 0.05)
        assertEquals(38.9, c.diagnostics.detourRatio!!, 0.05)
    }

    // -------------------------------------------------------------- refusals

    @Test
    fun `a network split is classified as a split, not a generic failure`() {
        val body = JSONObject(
            javaClass.getResourceAsStream("/vector-contract/foot-split-4b4.json")!!
                .readBytes().decodeToString()
        )
        assertEquals(WalkRefusal.NETWORK_SPLIT, WalkRefusal.of(body))
        // It is not a walk, so there is nothing to parse into a contract.
        assertNull(WalkContractParser.parseCollection(body))
    }

    @Test
    fun `a generic no-route refusal is told apart from a split`() {
        val body = JSONObject(
            """{"error":"no walking route found between the requested points"}"""
        )
        assertEquals(WalkRefusal.NO_ROUTE, WalkRefusal.of(body))
    }

    @Test
    fun `a successful walk is not a refusal`() {
        val ok = JSONObject(
            javaClass.getResourceAsStream("/vector-contract/foot-ordinary-4b4.json")!!
                .readBytes().decodeToString()
        )
        assertNull(WalkRefusal.of(ok))
        assertNotNull(WalkContractParser.parseCollection(ok))
    }

    // ------------------------------------------------- the existing car path

    @Test
    fun `the pre-4C1 walk parser still reads the same payload unchanged`() {
        // The journey card, the shade overlay and the parking-walk selection
        // all go through `parseFootFeature`, and 4C.1 must not disturb them.
        val api = VectorApi(base = "http://unused", token = "")
        val leg = api.parseFootFeature(feature("foot-ordinary-4b4.json"))
        assertEquals(583.2, leg.distanceM, 0.15)
        assertEquals(432.0, leg.durationS, 0.15)
        assertEquals(33, leg.geometry.size)
        // And the two parsers agree about the facts they both read.
        val c = parse("foot-ordinary-4b4.json")
        assertEquals(leg.distanceM, c.distanceM!!, 1e-9)
        assertEquals(leg.durationS, c.durationS!!, 1e-9)
        assertEquals(leg.geometry.size, c.geometry.size)
    }

    companion object {
        /** Every real Qatar walking payload captured for 4C.1. */
        val FIXTURES = listOf(
            "foot-ordinary-4b4.json",
            "foot-crossing-4b4.json",
            "foot-stairs-4b4.json",
            "foot-barrier-4b4.json",
            "foot-short-4b4.json",
            "foot-long-4b4.json",
        )
    }
}
