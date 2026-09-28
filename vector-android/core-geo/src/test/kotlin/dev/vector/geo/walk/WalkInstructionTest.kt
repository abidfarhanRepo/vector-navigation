package dev.vector.geo.walk

import dev.vector.geo.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The walking instruction mapper (V7.4 4C final).
 *
 * The rule under test throughout is the one the whole stage exists to serve:
 *
 * > Never turn an uncertain backend fact into a confident user instruction.
 *
 * So most of this file is about what is NOT said. A road that the backend did
 * not name produces no road clause; a crossing whose crossed road is
 * unattributed produces no road clause; an absent `handrail` produces no
 * handrail phrase and specifically not a negative one. Those are the
 * assertions that would fail if someone later added a "sensible default".
 */
class WalkInstructionTest {

    // ---------------------------------------------------------------- helpers

    private fun maneuver(
        kind: WalkManeuverKind,
        road: WalkRoadId? = null,
        crossing: WalkCrossing? = null,
        stairs: WalkStairs? = null,
    ) = WalkManeuver(
        kind = kind,
        index = 0,
        distanceM = 100.0,
        distanceToNextM = null,
        road = road,
        crossing = crossing,
        stairs = stairs,
    )

    private fun crossing(
        type: String? = null,
        typeSource: String? = null,
        markings: String? = null,
        kerb: String? = null,
        tactile: String? = null,
        road: WalkRoadId? = null,
        crossedRoadSource: String? = null,
    ) = WalkCrossing(
        type = type, typeSource = typeSource, markings = markings, kerb = kerb,
        tactilePaving = tactile, distanceM = 20.0, distanceToCrossingM = null,
        approachIndex = null, approachDistanceM = null, enterIndex = null,
        leaveIndex = null, road = road, crossedRoadSource = crossedRoadSource,
    )

    private fun of(m: WalkManeuver, distanceM: Double = 100.0) =
        WalkInstructions.of(m, planIndex = 0, status = WalkEventStatus.AHEAD, distanceM = distanceM)

    // ------------------------------------------------------- every kind maps

    @Test
    fun `every maneuver kind in the closed vocabulary produces an action`() {
        // The 4B.4 vocabulary is closed and this walks ALL of it, so adding a
        // kind to the enum without giving it words fails here rather than
        // shipping as a silent fallback.
        for (kind in WalkManeuverKind.entries) {
            val action = WalkInstructions.actionFor(kind)
            assertTrue(action.isNotBlank(), "$kind has no action phrase")
            // No internal vocabulary reaches a person.
            assertFalse(action.contains("_"), "$kind leaked a wire token: $action")
            assertTrue(action[0].isUpperCase(), "$kind is not a sentence: $action")
        }
    }

    @Test
    fun `the action vocabulary is the expected human wording`() {
        assertEquals("Turn left", WalkInstructions.actionFor(WalkManeuverKind.TURN_LEFT))
        assertEquals("Turn right", WalkInstructions.actionFor(WalkManeuverKind.TURN_RIGHT))
        assertEquals("Bear left", WalkInstructions.actionFor(WalkManeuverKind.SLIGHT_LEFT))
        assertEquals("Bear right", WalkInstructions.actionFor(WalkManeuverKind.SLIGHT_RIGHT))
        assertEquals("U-turn", WalkInstructions.actionFor(WalkManeuverKind.UTURN))
        assertEquals("Continue", WalkInstructions.actionFor(WalkManeuverKind.CONTINUE))
        assertEquals("Cross the road", WalkInstructions.actionFor(WalkManeuverKind.CROSS))
        assertEquals("Use the stairs", WalkInstructions.actionFor(WalkManeuverKind.STAIRS))
        assertEquals("Arriving", WalkInstructions.actionFor(WalkManeuverKind.ARRIVE))
    }

    @Test
    fun `every kind maps to an icon the existing set can actually draw`() {
        // The icon set is indexed by CAR maneuver types (VectorIcons.Maneuver).
        // A walking kind that mapped to a name outside it would silently fall
        // through to the straight-ahead arrow while claiming otherwise.
        val drawable = setOf(
            "turn-left", "turn-right", "slight-left", "slight-right",
            "uturn", "arrive", "depart",
        )
        for (kind in WalkManeuverKind.entries) {
            assertTrue(
                WalkInstructions.iconType(kind) in drawable,
                "$kind maps to an undrawable icon: ${WalkInstructions.iconType(kind)}",
            )
        }
    }

    // --------------------------------------------------------- known road

    @Test
    fun `a known road is named, with the right preposition per kind`() {
        val road = WalkRoadId(highway = "residential", name = "Al Waab Street")
        assertEquals(
            "Turn left onto Al Waab Street",
            of(maneuver(WalkManeuverKind.TURN_LEFT, road = road)).banner,
        )
        assertEquals(
            "Continue on Al Waab Street",
            of(maneuver(WalkManeuverKind.CONTINUE, road = road)).banner,
        )
        assertEquals(
            "Bear right onto Al Waab Street",
            of(maneuver(WalkManeuverKind.SLIGHT_RIGHT, road = road)).banner,
        )
    }

    @Test
    fun `English is preferred when the map carries both names`() {
        // The same preference the router and the basemap already apply, so the
        // banner and the map label underneath it cannot disagree.
        val road = WalkRoadId(name = "شارع الوعب", nameEn = "Al Waab Street")
        assertEquals(
            "Turn left onto Al Waab Street",
            of(maneuver(WalkManeuverKind.TURN_LEFT, road = road)).banner,
        )
    }

    @Test
    fun `an Arabic-only name is used rather than dropped`() {
        val road = WalkRoadId(name = "شارع الوعب")
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT, road = road))
        assertEquals("Turn left onto شارع الوعب", i.banner)
        assertTrue(i.roadKnown)
    }

    // ------------------------------------------------------- unknown road

    @Test
    fun `an absent road produces no clause at all`() {
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT, road = null))
        assertEquals("Turn left", i.banner)
        assertNull(i.roadName)
        assertFalse(i.roadKnown)
        // The specific failure this guards: a placeholder standing in for a
        // name nobody recorded.
        assertFalse(i.banner.contains("onto"))
        assertFalse(i.banner.contains("null", ignoreCase = true))
    }

    @Test
    fun `an unnamed way is not a road identity and is never named`() {
        // Extremely common on Qatari walks: a footway with a highway class and
        // no name at all. The class must never be substituted for a name —
        // "Turn left onto footway" is not something a person can act on.
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT, road = WalkRoadId(highway = "footway")))
        assertEquals("Turn left", i.banner)
        assertFalse(i.roadKnown)
        assertFalse(i.banner.contains("footway"))
    }

    @Test
    fun `a blank name is treated as absent`() {
        // org.json turns a JSON null into the string "null" if unguarded, and
        // blanks have reached a handset before (VectorApiParseTest).
        val i = of(maneuver(WalkManeuverKind.TURN_RIGHT, road = WalkRoadId(name = "   ")))
        assertEquals("Turn right", i.banner)
        assertFalse(i.roadKnown)
    }

    // ------------------------------------------------- crossing provenance

    @Test
    fun `a crossing names the crossed road only when it is attributed`() {
        val road = WalkRoadId(name = "Al Rayyan Road")
        val attributed = of(
            maneuver(
                WalkManeuverKind.CROSS,
                crossing = crossing(road = road, crossedRoadSource = "road_graph_shared_node"),
            )
        )
        assertEquals("Cross Al Rayyan Road", attributed.banner)
        assertTrue(attributed.roadKnown)
    }

    @Test
    fun `a crossing with a road but no provenance does not name it`() {
        // The most dangerous sentence available in this contract to get wrong:
        // naming the road a person is standing at on geometry alone.
        val i = of(
            maneuver(
                WalkManeuverKind.CROSS,
                crossing = crossing(
                    road = WalkRoadId(name = "Al Rayyan Road"),
                    crossedRoadSource = null,
                ),
            )
        )
        assertEquals("Cross the road", i.banner)
        assertFalse(i.roadKnown)
        assertFalse(i.banner.contains("Rayyan"))
    }

    @Test
    fun `an unknown crossing road says the generic true thing`() {
        val i = of(maneuver(WalkManeuverKind.CROSS, crossing = crossing()))
        assertEquals("Cross the road", i.banner)
        // True of every crossing, and therefore safe.
        assertNull(i.roadName)
    }

    @Test
    fun `crossing type is surfaced only for the vocabulary Vector understands`() {
        assertEquals(
            "Signal-controlled crossing",
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(type = "traffic_signals")))
                .crossing?.typeLabel,
        )
        assertEquals(
            "Zebra crossing",
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(type = "zebra")))
                .crossing?.typeLabel,
        )
        assertEquals(
            "Marked crossing",
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(type = "marked")))
                .crossing?.typeLabel,
        )
    }

    @Test
    fun `an unknown crossing type is dropped rather than echoed`() {
        // OSM's `crossing` key has a long tail. Passing a token through would
        // put "Cross the road / traffic_signals;marked" in front of a person.
        val i = of(
            maneuver(WalkManeuverKind.CROSS, crossing = crossing(type = "traffic_signals;marked"))
        )
        assertNull(i.crossing?.typeLabel)
        assertNull(i.detail)
    }

    @Test
    fun `an untyped crossing claims no type`() {
        // 4B.1's `catalog_node` state: a crossing node exists but nobody
        // recorded what kind. It is still a crossing; it is not a zebra.
        val i = of(
            maneuver(
                WalkManeuverKind.CROSS,
                crossing = crossing(type = null, typeSource = "catalog_node"),
            )
        )
        assertEquals("Cross the road", i.banner)
        assertNull(i.crossing?.typeLabel)
        assertNull(i.detail)
    }

    @Test
    fun `no provenance string ever reaches the rendered text`() {
        val i = of(
            maneuver(
                WalkManeuverKind.CROSS,
                crossing = crossing(
                    type = "zebra", typeSource = "catalog_node",
                    road = WalkRoadId(name = "Al Waab Street"),
                    crossedRoadSource = "road_graph_shared_node",
                ),
            )
        )
        val rendered = listOfNotNull(i.banner, i.detail, i.action).joinToString(" ")
        for (leak in listOf("catalog", "road_graph", "shared_node", "source", "_")) {
            assertFalse(rendered.contains(leak), "provenance leaked into \"$rendered\"")
        }
    }

    @Test
    fun `tactile paving is claimed only when surveyed as present`() {
        assertEquals(
            true,
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(tactile = "yes")))
                .crossing?.tactilePaving,
        )
        // "no" and absent are both rendered as SILENCE. Vector never tells a
        // person tactile paving is missing — on 20 surveyed nodes country-wide
        // the absence of a tag is not evidence of absence in the world.
        assertNull(
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(tactile = "no")))
                .crossing?.tactilePaving,
        )
        assertNull(
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(tactile = null)))
                .crossing?.tactilePaving,
        )
        val no = of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(tactile = "no")))
        assertNull(no.detail)
    }

    @Test
    fun `kerb is surfaced when known and silent when not`() {
        assertEquals(
            "Lowered kerb",
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(kerb = "lowered")))
                .crossing?.kerbLabel,
        )
        assertNull(
            of(maneuver(WalkManeuverKind.CROSS, crossing = crossing(kerb = null)))
                .crossing?.kerbLabel,
        )
    }

    @Test
    fun `no crossing rendering makes a safety or accessibility claim`() {
        // The vocabulary simply does not contain these words, which is what
        // makes the guarantee structural rather than a matter of review.
        val i = of(
            maneuver(
                WalkManeuverKind.CROSS,
                crossing = crossing(
                    type = "traffic_signals", markings = "zebra", kerb = "flush",
                    tactile = "yes", road = WalkRoadId(name = "Al Waab Street"),
                    crossedRoadSource = "road_graph_shared_node",
                ),
            )
        )
        val rendered = "${i.banner} ${i.detail}"
        for (claim in listOf(
            "safe", "safely", "accessible", "accessibility", "step-free",
            "priority", "right of way", "wait for", "when clear", "green", "red",
        )) {
            assertFalse(
                rendered.contains(claim, ignoreCase = true),
                "forbidden claim \"$claim\" in \"$rendered\"",
            )
        }
    }

    // ------------------------------------------------------ stairs attributes

    @Test
    fun `stairs say only what was surveyed`() {
        val i = of(
            maneuver(
                WalkManeuverKind.STAIRS,
                stairs = WalkStairs(distanceM = 6.0, stepCount = 12, handrail = "yes", incline = "up"),
            )
        )
        assertEquals("Use the stairs", i.banner)
        assertEquals("12 steps", i.stairs?.stepCountLabel)
        assertEquals(true, i.stairs?.handrail)
        assertEquals("Up", i.stairs?.inclineLabel)
        assertEquals("12 steps · Up · Handrail", i.detail)
    }

    @Test
    fun `a staircase with no attributes produces no detail line at all`() {
        // The overwhelmingly common real case: 3 step_count and 8 handrail
        // ways exist in the entire Qatar extract.
        val i = of(maneuver(WalkManeuverKind.STAIRS, stairs = WalkStairs(distanceM = 6.0)))
        assertEquals("Use the stairs", i.banner)
        assertNull(i.detail)
        assertNull(i.stairs?.stepCountLabel)
        assertNull(i.stairs?.handrail)
        assertNull(i.stairs?.inclineLabel)
    }

    @Test
    fun `an absent handrail is never rendered as a negative`() {
        val absent = of(maneuver(WalkManeuverKind.STAIRS, stairs = WalkStairs(distanceM = 6.0)))
        assertNull(absent.stairs?.handrail)
        assertNull(absent.detail)
        // And a surveyed "no" is equally silent: Vector says a handrail is
        // THERE or says nothing. Telling someone a stairway lacks one, from a
        // tag on 8 ways country-wide, would be a claim about the world.
        val no = of(
            maneuver(
                WalkManeuverKind.STAIRS,
                stairs = WalkStairs(distanceM = 6.0, handrail = "no"),
            )
        )
        assertNull(no.stairs?.handrail)
        assertNull(no.detail)
    }

    @Test
    fun `a zero step count is treated as no survey`() {
        // `0` would be a claim that a staircase has no steps.
        val i = of(
            maneuver(WalkManeuverKind.STAIRS, stairs = WalkStairs(distanceM = 6.0, stepCount = 0))
        )
        assertNull(i.stairs?.stepCountLabel)
        assertNull(i.detail)
    }

    @Test
    fun `stairs never name a road even when the plan carries one`() {
        val i = of(
            maneuver(
                WalkManeuverKind.STAIRS,
                road = WalkRoadId(name = "Al Waab Street"),
                stairs = WalkStairs(distanceM = 6.0),
            )
        )
        assertEquals("Use the stairs", i.banner)
        assertFalse(i.roadKnown)
    }

    @Test
    fun `no stairs rendering makes an accessibility claim`() {
        val i = of(
            maneuver(
                WalkManeuverKind.STAIRS,
                stairs = WalkStairs(distanceM = 6.0, stepCount = 12, handrail = "yes", incline = "up"),
            )
        )
        val rendered = "${i.banner} ${i.detail}"
        for (claim in listOf("accessible", "accessibility", "step-free", "wheelchair", "easy", "safe")) {
            assertFalse(
                rendered.contains(claim, ignoreCase = true),
                "forbidden claim \"$claim\" in \"$rendered\"",
            )
        }
    }

    // --------------------------------------------------------- arrive/depart

    @Test
    fun `arrival never names the street the destination sits on`() {
        val i = of(
            maneuver(WalkManeuverKind.ARRIVE, road = WalkRoadId(name = "Al Waab Street"))
        )
        assertEquals("Arriving", i.banner)
        assertFalse(i.roadKnown)
    }

    @Test
    fun `depart names its road when one is known`() {
        assertEquals(
            "Start walking on Al Waab Street",
            of(maneuver(WalkManeuverKind.DEPART, road = WalkRoadId(name = "Al Waab Street"))).banner,
        )
        assertEquals("Start walking", of(maneuver(WalkManeuverKind.DEPART)).banner)
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `the same maneuver always renders the same text`() {
        val m = maneuver(
            WalkManeuverKind.CROSS,
            crossing = crossing(
                type = "zebra", markings = "zebra", kerb = "lowered", tactile = "yes",
                road = WalkRoadId(name = "Al Waab Street"),
                crossedRoadSource = "road_graph_shared_node",
            ),
        )
        val first = of(m)
        repeat(20) {
            val again = of(m)
            assertEquals(first.banner, again.banner)
            assertEquals(first.detail, again.detail)
            assertEquals(first.action, again.action)
        }
    }

    @Test
    fun `the detail line orders its parts deterministically`() {
        // Built from a map iteration it would reorder between runs, and a
        // banner that appears to change while a walker stands still is a bug
        // that only shows up in front of a person.
        val i = of(
            maneuver(
                WalkManeuverKind.CROSS,
                crossing = crossing(
                    type = "zebra", markings = "zebra", kerb = "lowered", tactile = "yes",
                ),
            )
        )
        assertEquals("Zebra crossing · Marked · Lowered kerb · Tactile paving", i.detail)
    }

    // ------------------------------------------------- facts vs presentation

    @Test
    fun `facts are carried beside the strings rather than encoded in them`() {
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT, road = WalkRoadId(name = "Al Waab Street")))
        // A consumer that wants a different presentation reads the facts; it
        // never has to parse the sentence back apart.
        assertEquals(WalkManeuverKind.TURN_LEFT, i.kind)
        assertEquals("Al Waab Street", i.roadName)
        assertEquals("Turn left", i.action)
        assertEquals(100.0, i.distanceM)
    }

    // ------------------------------------------------------------- the voice

    @Test
    fun `the spoken form is built from the same banner text`() {
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT, road = WalkRoadId(name = "Al Waab Street")))
        assertEquals("Turn left onto Al Waab Street", i.spoken(WalkVoiceStage.NOW))
        assertEquals(
            "In 100 metres, turn left onto Al Waab Street",
            i.spoken(WalkVoiceStage.APPROACH),
        )
    }

    @Test
    fun `the now stage speaks no distance`() {
        // "In ten metres, turn left" said at the corner is worse than "turn
        // left" — the maneuver is where the walker is standing.
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT), distanceM = 8.0)
        assertEquals("Turn left", i.spoken(WalkVoiceStage.NOW))
    }

    @Test
    fun `spoken distance honours the units setting`() {
        val i = of(maneuver(WalkManeuverKind.TURN_LEFT), distanceM = 100.0)
        assertTrue(i.spoken(WalkVoiceStage.APPROACH, Units.IMPERIAL).contains("feet"))
        assertTrue(i.spoken(WalkVoiceStage.APPROACH, Units.METRIC).contains("metres"))
    }

    // ----------------------------------------------------------------- ETA

    @Test
    fun `the shown walk time is pure pace time and nothing else`() {
        // 4B.4 D1, held at the presentation layer: `duration_s` is what a
        // person sees, and the crossing wait is NOT folded into it.
        val eta = WalkEta(
            displayDurationS = 480.0,      // 8 min of walking
            selectionCostS = 620.0,
            penaltyS = 132.0,
            crossingWaitS = 132.0,
        )
        assertEquals("8 min", WalkEtaText.walkTime(eta))
    }

    @Test
    fun `crossing delay is shown separately and explicitly as an addition`() {
        val eta = WalkEta(480.0, 620.0, 132.0, 132.0)
        val delay = WalkEtaText.crossingDelay(eta)
        assertNotNull(delay)
        // Additive wording, hedged, and never a revised total.
        assertTrue(delay!!.startsWith("plus"))
        assertTrue(delay.contains("about"))
        assertFalse(delay.contains("10 min"))   // 480+132 would be ~10 min
    }

    @Test
    fun `a small or absent crossing delay says nothing`() {
        assertNull(WalkEtaText.crossingDelay(WalkEta(480.0, 480.0, 0.0, 0.0)))
        assertNull(WalkEtaText.crossingDelay(WalkEta(480.0, 500.0, 20.0, 20.0)))
        assertNull(WalkEtaText.crossingDelay(null))
        assertNull(WalkEtaText.crossingDelay(WalkEta(480.0, null, null, null)))
    }

    @Test
    fun `there is no way to ask for a combined duration`() {
        // The way a presentation layer silently changes a contract is by
        // offering one. The walk time is unaffected by the crossing wait at
        // every magnitude.
        val without = WalkEta(480.0, 480.0, 0.0, 0.0)
        val with = WalkEta(480.0, 1200.0, 720.0, 720.0)
        assertEquals(WalkEtaText.walkTime(without), WalkEtaText.walkTime(with))
    }

    @Test
    fun `walk time never reads zero minutes`() {
        assertEquals("1 min", WalkEtaText.walkTime(WalkEta(20.0, null, null, null)))
        assertEquals("1 h", WalkEtaText.walkTime(WalkEta(3600.0, null, null, null)))
        assertEquals("1 h 5 min", WalkEtaText.walkTime(WalkEta(3900.0, null, null, null)))
        assertNull(WalkEtaText.walkTime(WalkEta(null, null, null, null)))
    }

    // -------------------------------------------------------------- refusals

    @Test
    fun `the three refusals are told apart in words`() {
        val titles = WalkRefusalKind.entries.map { WalkRefusalText.title(it) }
        assertEquals(titles.size, titles.toSet().size, "two refusals share a title")
        val details = WalkRefusalKind.entries.map { WalkRefusalText.detail(it) }
        assertEquals(details.size, details.toSet().size, "two refusals share a detail")
    }

    @Test
    fun `a network split describes the map and not the world`() {
        val detail = WalkRefusalText.detail(WalkRefusalKind.NETWORK_SPLIT)
        // What is true: the footpaths Vector knows about do not connect.
        assertTrue(detail.contains("connect"))
        // What is NOT claimed: that a person cannot walk there. They may well
        // be able to; the map cannot show a route, which is a different
        // statement, and asserting the first from the second would be
        // inventing knowledge about the world from missing data.
        assertFalse(detail.contains("cannot walk", ignoreCase = true))
        assertFalse(detail.contains("impossible", ignoreCase = true))
        assertFalse(detail.contains("no way", ignoreCase = true))
    }

    @Test
    fun `only a transport failure is worth retrying`() {
        assertTrue(WalkRefusalText.retryable(WalkRefusalKind.BACKEND_FAILURE))
        assertFalse(WalkRefusalText.retryable(WalkRefusalKind.NETWORK_SPLIT))
        assertFalse(WalkRefusalText.retryable(WalkRefusalKind.NO_ROUTE))
    }

    @Test
    fun `no refusal suggests driving instead`() {
        // Whether a car route exists is a different question with a different
        // answer, and offering one unasked is the silent fallback the brief
        // rules out.
        for (kind in WalkRefusalKind.entries) {
            val all = WalkRefusalText.title(kind) + " " +
                WalkRefusalText.detail(kind) + " " + WalkRefusalText.spoken(kind)
            assertFalse(all.contains("driv", ignoreCase = true), "$kind suggests driving")
            assertFalse(all.contains("car", ignoreCase = true), "$kind suggests a car")
        }
    }
}
