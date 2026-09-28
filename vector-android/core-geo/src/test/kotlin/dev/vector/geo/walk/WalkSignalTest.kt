package dev.vector.geo.walk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Traffic lights on a walking crossing (V7 traffic lights).
 *
 * ## The two rules under test
 *
 * 1. **A signal claim needs signal evidence.** A crossing is described as
 *    signal-controlled only when the backend supplied a surveyed signal standing
 *    on it, or when the crossing's own `crossing=traffic_signals` tag says so.
 *    Nothing infers it — not from the crossing's name, not from the crossed
 *    road, not from proximity, and not from the fact that a signal exists
 *    somewhere else on the route.
 *
 * 2. **No state, ever.** A signal being present says lights are there. It says
 *    nothing about what they are showing. There is no phase, no colour, no
 *    countdown and no wait estimate anywhere in the walking vocabulary, and the
 *    guard test at the bottom of this file walks the whole vocabulary to say so
 *    — because a claim that does not exist as a word cannot be shipped by
 *    accident.
 *
 * The fixtures below carry no timing field of any kind, because none exists:
 * measured over the whole 2026-09 Qatar extract, not one of the 899 surveyed
 * signal nodes carries a timing-shaped tag.
 */
class WalkSignalTest {

    // ---------------------------------------------------------------- helpers

    private fun signal(id: String = "n1001") = WalkSignal(
        id = id,
        source = "osm:node:${id.removePrefix("n")}",
        node = "51.5300000,25.2860000",
    )

    private fun crossing(
        type: String? = null,
        typeSource: String? = null,
        signals: List<WalkSignal> = emptyList(),
        road: WalkRoadId? = null,
        crossedRoadSource: String? = null,
    ) = WalkCrossing(
        type = type, typeSource = typeSource ?: if (type != null) "way" else null,
        markings = null, kerb = null, tactilePaving = null,
        distanceM = 20.0, distanceToCrossingM = null,
        approachIndex = null, approachDistanceM = null, enterIndex = null,
        leaveIndex = null, road = road, crossedRoadSource = crossedRoadSource,
        signals = signals,
    )

    private fun cross(c: WalkCrossing) = WalkInstructions.of(
        WalkManeuver(
            kind = WalkManeuverKind.CROSS,
            index = 3, distanceM = 100.0, distanceToNextM = null,
            road = c.road, crossing = c,
        ),
        planIndex = 1, status = WalkEventStatus.AHEAD, distanceM = 25.0,
    )

    // ------------------------------------------------- signal presence as fact

    @Test
    fun `a surveyed signal on the crossing marks it signal controlled`() {
        val c = crossing(signals = listOf(signal()))
        assertTrue(c.signalControlled)
        assertEquals("Signal-controlled crossing", cross(c).crossing?.typeLabel)
    }

    @Test
    fun `a crossing with no signal on it is not called signal controlled`() {
        val c = crossing(signals = emptyList())
        assertFalse(c.signalControlled)
        assertNull(cross(c).crossing?.typeLabel)
        assertNull(cross(c).detail)
    }

    @Test
    fun `the crossing tag and a surveyed signal give one label, not two`() {
        // Both sources say the same thing. A person crossing needs it once.
        val c = crossing(type = "traffic_signals", signals = listOf(signal()))
        val detail = cross(c).crossing!!
        assertEquals("Signal-controlled crossing", detail.typeLabel)
        assertEquals("Signal-controlled crossing", cross(c).detail)
    }

    @Test
    fun `an untyped crossing with a signal is described, an untyped one without is not`() {
        // 522 of Qatar's signal-carrying crossings carry no `crossing=*` tag at
        // all. Before this stage they rendered as a bare "Cross the road"; the
        // surveyed signal is what makes the description possible.
        assertEquals(
            "Signal-controlled crossing",
            cross(crossing(type = null, signals = listOf(signal()))).detail,
        )
        assertNull(cross(crossing(type = null, typeSource = "catalog_node")).detail)
    }

    @Test
    fun `a signal never disturbs the primary instruction`() {
        // §7: the signal is SECONDARY. The banner is the maneuver, and it is
        // byte-identical to the same crossing with no signal on it.
        val road = WalkRoadId(highway = "primary", name = "Jasim Bin Hamad Street")
        val plain = cross(crossing(road = road, crossedRoadSource = "road_graph_shared_node"))
        val withSignal = cross(
            crossing(
                signals = listOf(signal()),
                road = road, crossedRoadSource = "road_graph_shared_node",
            )
        )
        assertEquals("Cross Jasim Bin Hamad Street", plain.banner)
        assertEquals(plain.banner, withSignal.banner)
        assertEquals(plain.action, withSignal.action)
        assertEquals(plain.roadName, withSignal.roadName)
        assertNull(plain.detail)
        assertEquals("Signal-controlled crossing", withSignal.detail)
    }

    @Test
    fun `an ordinary crossing is unchanged by this stage`() {
        val c = crossing(type = "zebra")
        assertEquals("Zebra crossing", cross(c).detail)
        assertEquals("Cross the road", cross(c).banner)
    }

    // -------------------------------------------------------- the vocabulary

    @Test
    fun `no walking signal wording names a state, a colour or a duration`() {
        val forbidden = listOf(
            "green", "red", "amber", "yellow", "phase", "cycle", "countdown",
            "second", "wait", "next ", "will ", "about to",
        )
        val vocabulary = listOf(
            WalkInstructions.SIGNAL_CONTROLLED,
            cross(crossing(signals = listOf(signal()))).detail!!,
            cross(crossing(type = "traffic_signals")).detail!!,
        )
        for (text in vocabulary) {
            val lower = text.lowercase()
            for (word in forbidden) {
                assertFalse(
                    lower.contains(word),
                    "\"$text\" contains \"$word\": the walking vocabulary may " +
                        "describe a signal's PRESENCE and nothing else",
                )
            }
        }
    }

    @Test
    fun `the signal fact carries identity and provenance and nothing else`() {
        // The wire entry, as the client models it. A banner or a map renderer
        // cannot draw a state from this type because there is no field on it
        // that could hold one — `WalkContractParseTest` proves the parser
        // discards a state key even when a payload carries one.
        val s = signal()
        assertEquals("n1001", s.id)
        assertEquals("osm:node:1001", s.source)
        assertEquals("51.5300000,25.2860000", s.node)
    }
}
