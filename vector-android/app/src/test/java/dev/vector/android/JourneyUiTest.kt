package dev.vector.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.vector.geo.LngLat
import dev.vector.geo.journey.CoolerRoute
import dev.vector.geo.journey.DriveLeg
import dev.vector.geo.journey.Journey
import dev.vector.geo.journey.ParkSpot
import dev.vector.geo.journey.ShadeAnnotator
import dev.vector.geo.journey.WalkLeg
import dev.vector.geo.sun.WalkSegment
import dev.vector.android.pro.ProFeature
import dev.vector.android.pro.ProStatus
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Last Mile, actually composed.
 *
 * The journey card is the one surface in Vector that renders a number nobody
 * measured. Most of this file is therefore about what it is NOT allowed to
 * say: no bare percentage, no invented temperature, no silent assumption that
 * parking was found, and no cooler-route control when there is no cooler route.
 *
 * Follows [NavUiTest]'s conventions — real Compose, real Android framework, on
 * the JVM, because the emulator SIGSEGVs on this host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class JourneyUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = mutableStateOf(UiState())
    private var mounted = false
    private var journeyToggles = 0
    private var coolerToggles = 0
    private var scrubs = mutableListOf<Long?>()

    private val ANIMATION_SETTLE_MS = 600L

    private companion object {
        val QATAR: ZoneOffset = ZoneOffset.ofHours(3)
        fun at(hour: Int, minute: Int = 0): Long =
            ZonedDateTime.of(2026, 9, 13, hour, minute, 0, 0, QATAR).toInstant().toEpochMilli()

        val PARK = LngLat(51.5310, 25.2850)
        val NORTH = LngLat(51.5310, 25.2880)
        val EAST = LngLat(51.5340, 25.2850)

        fun walkTo(to: LngLat, highway: String = "residential", durationS: Double = 360.0) =
            WalkLeg(
                segments = listOf(WalkSegment(PARK, to, highway = highway)),
                distanceM = 300.0,
                durationS = durationS,
            )
    }

    private fun show(s: UiState) {
        state.value = s
        if (!mounted) {
            mounted = true
            compose.setContent {
                VectorChrome(
                    ui = state.value,
                    onQueryChange = {}, onSearch = {}, onPick = {}, onStart = {},
                    onCancel = {}, onRecenter = {}, onToggleVoice = {},
                    onToggleSteps = {}, onToggleContributing = {},
                    onOpenSearch = {}, onCloseSearch = {},
                    onToggleJourney = { journeyToggles++ },
                    onScrubSunTime = { scrubs.add(it) },
                    onToggleCooler = { coolerToggles++ },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ANIMATION_SETTLE_MS)
        compose.waitForIdle()
    }

    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out.add(it.text) }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    private fun screenSays(fragment: String): Boolean =
        texts().any { it.contains(fragment, ignoreCase = true) }

    /** A journey to somewhere, with parking found and a walk at 16:00. */
    private fun journey(
        parkingKnown: Boolean = true,
        walk: WalkLeg = walkTo(NORTH),
        atMs: Long = at(16),
    ): Journey {
        val shade = ShadeAnnotator.shade(walk, atMs)
        return Journey(
            destinationName = "Villaggio Mall",
            destination = EAST,
            drive = DriveLeg(listOf(PARK, EAST), 9_000.0, 840.0),
            park = if (parkingKnown) ParkSpot(PARK, "Villaggio Parking P3") else null,
            walk = walk,
            walkShade = shade,
            parkingKnown = parkingKnown,
            annotations = ShadeAnnotator.annotate(walk, atMs),
        )
    }

    private fun preview(j: Journey?, expanded: Boolean = false, atMs: Long? = at(16)) =
        UiState(
            phase = Phase.PREVIEW,
            destination = EAST,
            destinationName = "Villaggio Mall",
            journey = j,
            journeyExpanded = expanded,
            journeyTimeMs = atMs,
        )

    // ---- the one line ------------------------------------------------------

    @Test
    fun `the destination chip gains the rest of the trip`() {
        show(preview(journey()))
        assertTrue("the summary line is the product", screenSays("14 min drive"))
        assertTrue(screenSays("6 min walk"))
    }

    @Test
    fun `no journey means the chip looks exactly as it always did`() {
        show(preview(null))
        assertTrue("the destination still shows", screenSays("Villaggio Mall"))
        assertFalse(screenSays("min walk"))
        assertFalse(screenSays("estimated"))
    }

    @Test
    fun `the summary line never shows a temperature`() {
        // The V7 plan asks for "· 42 °C". Vector has no weather source.
        show(preview(journey()))
        assertFalse("no fabricated temperature", texts().any { it.contains("°") })
    }

    // ---- the invariant: nothing is presented as measured -------------------

    @Test
    fun `a shade percentage never appears without the word estimated`() {
        show(preview(journey(), expanded = true))
        for (t in texts()) {
            if (t.contains("%")) {
                assertTrue(
                    "'$t' shows a percentage without saying it is estimated",
                    t.contains("estimated", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the expanded card states how the shade was arrived at`() {
        show(preview(journey(), expanded = true))
        assertTrue(
            "provenance must be rendered alongside the estimate",
            screenSays("estimated from street orientation"),
        )
        assertTrue(
            "and the model's limits stated",
            screenSays("no building or tree data"),
        )
    }

    // ---- parking, and the absence of it ------------------------------------

    @Test
    fun `a destination with no indexed parking says so`() {
        show(preview(journey(parkingKnown = false), expanded = true))
        assertTrue(
            "the card must not silently imply a car park was found",
            screenSays("No parking found nearby"),
        )
        // ...and the walk is still shown, measured from where the drive ends.
        assertTrue(screenSays("Walk"))
    }

    @Test
    fun `a destination with parking names it`() {
        show(preview(journey(parkingKnown = true), expanded = true))
        assertTrue(screenSays("Villaggio Parking P3"))
        assertFalse(screenSays("No parking found"))
    }

    // ---- the legs ----------------------------------------------------------

    @Test
    fun `collapsed shows the line only, expanded shows the legs`() {
        // Matched EXACTLY rather than by substring: the collapsed summary line
        // already reads "14 min drive · 6 min walk", so a case-insensitive
        // `contains("Drive")` is true before the card is even opened.
        fun hasLegRow(title: String) = texts().any { it == title }

        show(preview(journey(), expanded = false))
        assertFalse("legs are hidden until asked for", hasLegRow("Drive"))
        assertFalse(hasLegRow("Park"))

        show(preview(journey(), expanded = true))
        assertTrue(hasLegRow("Drive"))
        assertTrue(hasLegRow("Park"))
        assertTrue(hasLegRow("Walk"))
    }

    @Test
    fun `stairs on the walk are declared before setting off`() {
        val stepped = WalkLeg(
            segments = listOf(WalkSegment(PARK, NORTH, highway = "steps")),
            distanceM = 300.0, durationS = 400.0, stepsM = 40.0,
        )
        show(preview(journey(walk = stepped), expanded = true))
        assertTrue("someone with a suitcase needs to know", screenSays("of steps"))
    }

    @Test
    fun `a walk with no stairs does not mention stairs`() {
        show(preview(journey(), expanded = true))
        assertFalse(screenSays("of steps"))
    }

    // ---- the sun slider ----------------------------------------------------

    @Test
    fun `the expanded card carries a time control for the sun`() {
        show(preview(journey(), expanded = true))
        assertTrue(screenSays("Sun at"))
    }

    @Test
    fun `scrubbing away from now offers a way back to now`() {
        show(preview(journey(), expanded = true, atMs = at(16)))
        assertTrue("a scrubbed slider must offer 'Now'", screenSays("Now"))
    }

    // ---- the cooler route --------------------------------------------------

    @Test
    fun `the cooler route control is absent when there is no cooler route`() {
        // Absent, not disabled. A control that is present and refuses
        // advertises a feature and then does not perform it.
        show(preview(journey(), expanded = true).copy(coolerOffer = CoolerRoute.Offer.none))
        assertFalse(screenSays("Cooler route"))
    }

    @Test
    fun `the cooler route is offered when one exists, with what it costs`() {
        show(
            preview(journey(), expanded = true).copy(
                pro = ProStatus.PRO,
                coolerWalk = walkTo(EAST),
                coolerOffer = CoolerRoute.Offer(
                    available = true, deltaPoints = 44.0, extraDurationS = 120.0,
                ),
            )
        )
        assertTrue(screenSays("Cooler route"))
        assertTrue("the shade gain is hedged", screenSays("estimated"))
        assertTrue("the time cost is stated", screenSays("2 min longer"))
    }

    @Test
    fun `a free driver is offered the last mile rather than shown it`() {
        // This asserted that a free driver saw "Cooler route (Pro)" INSIDE the
        // journey card. They no longer see the card at all: the whole last mile
        // is what `ProCatalogue` sells as "The walk after the car", and a locked
        // row inside a locked card was gating the same thing twice while giving
        // away the parking, the walk and the sun slider around it.
        //
        // What is left in its place is one line where the answer would have
        // been. `CoolerRouteRow`'s own lock still exists and is still correct;
        // it is simply unreachable while the catalogue is non-empty, because
        // the card that carries it is.
        show(
            preview(journey(), expanded = true).copy(
                pro = ProStatus.FREE,
                proFeatures = listOf(ProFeature("The walk after the car", "x")),
                coolerWalk = walkTo(EAST),
                coolerOffer = CoolerRoute.Offer(true, 44.0, 120.0),
            )
        )
        assertFalse("the card is the paid surface", screenSays("Cooler route"))
        assertFalse("...and so is the walk it describes", screenSays("min walk"))
        assertTrue("the offer stands where the answer would have been",
                   screenSays("The walk after the car"))
        assertTrue("the drive is never gated", screenSays("Villaggio Mall"))
    }

    @Test
    fun `a free driver in a build that sells nothing keeps the whole journey`() {
        // `offersPro` is false with an empty catalogue, so this is the
        // self-hosted-with-a-key case: nothing to buy, nothing withheld.
        show(
            preview(journey(), expanded = true).copy(
                pro = ProStatus.FREE,
                proFeatures = emptyList(),
                coolerWalk = walkTo(EAST),
                coolerOffer = CoolerRoute.Offer(true, 44.0, 120.0),
            )
        )
        assertTrue(screenSays("min walk"))
        assertTrue(screenSays("Cooler route"))
    }

    @Test
    fun `a subscriber gets the walk the paywall sold them`() {
        show(
            preview(journey()).copy(
                pro = ProStatus.PRO,
                proFeatures = listOf(ProFeature("The walk after the car", "x")),
            )
        )
        assertTrue(screenSays("min walk"))
        assertFalse("nothing is sold to someone who already bought",
                    screenSays("Included with Vector Pro"))
    }

    @Test
    fun `a subscriber sees no Pro marking on the cooler route`() {
        show(
            preview(journey(), expanded = true).copy(
                pro = ProStatus.PRO,
                coolerWalk = walkTo(EAST),
                coolerOffer = CoolerRoute.Offer(true, 44.0, 120.0),
            )
        )
        assertTrue(screenSays("Cooler route"))
        assertFalse(screenSays("(Pro)"))
    }

    @Test
    fun `a self-hosted build is never told the cooler route is paid for`() {
        // UNCONFIGURED has full access and no tier at all.
        show(
            preview(journey(), expanded = true).copy(
                pro = ProStatus.UNCONFIGURED,
                coolerWalk = walkTo(EAST),
                coolerOffer = CoolerRoute.Offer(true, 44.0, 120.0),
            )
        )
        assertTrue(screenSays("Cooler route"))
        assertFalse(screenSays("(Pro)"))
    }

    // ---- the overlay -------------------------------------------------------

    @Test
    fun `the walk overlay splits the line into shaded and exposed runs`() {
        val mixed = WalkLeg(
            segments = listOf(
                WalkSegment(PARK, NORTH, highway = "residential"),  // shaded at 16:00
                WalkSegment(NORTH, EAST, highway = "primary"),      // always exposed
            ),
            distanceM = 600.0, durationS = 440.0,
        )
        val shade = ShadeAnnotator.shade(mixed, at(16))
        val json = VectorStyle.walkGeoJson(mixed, shade)
        assertTrue("a shaded run must be drawn", json.contains("\"shaded\":true"))
        assertTrue("an exposed run must be drawn", json.contains("\"shaded\":false"))
    }

    @Test
    fun `the walk overlay is empty rather than wrong when the two disagree`() {
        // Index-aligned by contract. If the exposure list is not the walk's,
        // drawing anything would colour one route with another's sun.
        val walk = walkTo(NORTH)
        val otherShade = ShadeAnnotator.shade(
            WalkLeg(
                segments = listOf(WalkSegment(PARK, NORTH), WalkSegment(NORTH, EAST)),
                distanceM = 600.0, durationS = 440.0,
            ),
            at(16),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[]}""",
            VectorStyle.walkGeoJson(walk, otherShade),
        )
    }

    @Test
    fun `an empty walk draws nothing`() {
        val empty = WalkLeg(emptyList(), 0.0, 0.0)
        assertEquals(
            """{"type":"FeatureCollection","features":[]}""",
            VectorStyle.walkGeoJson(empty, ShadeAnnotator.shade(empty, at(16))),
        )
    }

    // ---- the cooler route, switched ON -------------------------------------
    //
    // Everything above this point exercises the OFF state. The On state was
    // uncovered, and that is how the card came to name one car park while the
    // map drew the walk from another. These four pin the whole selection —
    // park, distance, duration and shade — to the walk actually on screen.

    /** A second car park, further away, with a different name and a longer walk. */
    private fun coolerState(chosen: Boolean): UiState {
        val direct = walkTo(NORTH, highway = "residential", durationS = 360.0)
        val cooler = walkTo(EAST, highway = "residential", durationS = 480.0)
        val atMs = at(16)
        return preview(journey(walk = direct, atMs = atMs), expanded = true).copy(
            pro = ProStatus.PRO,
            coolerWalk = cooler,
            coolerPark = ParkSpot(EAST, "Shaded Basement P1"),
            coolerShade = ShadeAnnotator.shade(cooler, atMs),
            coolerOffer = CoolerRoute.consider(
                direct = direct,
                directShade = ShadeAnnotator.shade(direct, atMs),
                alternative = cooler,
                alternativeShade = ShadeAnnotator.shade(cooler, atMs),
            ),
            coolerChosen = chosen,
        )
    }

    @Test
    fun `with the cooler route off the card names the direct car park`() {
        show(coolerState(chosen = false))
        assertTrue("direct park expected", screenSays("Villaggio Parking P3"))
        assertFalse("cooler park must not appear", screenSays("Shaded Basement P1"))
    }

    @Test
    fun `choosing the cooler route moves the car park with it`() {
        show(coolerState(chosen = true))
        assertTrue("cooler park expected", screenSays("Shaded Basement P1"))
        assertFalse(
            "the direct car park must not still be named while the map draws the cooler walk",
            screenSays("Villaggio Parking P3"),
        )
    }

    @Test
    fun `choosing the cooler route moves the walk duration with it`() {
        // The direct walk is 6 min, the cooler one 8 min. Showing the cooler
        // line under the direct walk's duration is the same contradiction in
        // a different column.
        show(coolerState(chosen = false))
        assertTrue("direct duration expected", screenSays("6 min"))
        show(coolerState(chosen = true))
        assertTrue("cooler duration expected", screenSays("8 min"))
    }

    @Test
    fun `an unavailable cooler walk leaves the card on the direct one`() {
        // coolerChosen can survive a journey recompose that found no
        // alternative. The card must fall back rather than render nothing.
        show(coolerState(chosen = true).copy(coolerWalk = null, coolerPark = null))
        assertTrue("direct park expected", screenSays("Villaggio Parking P3"))
        assertTrue("direct duration expected", screenSays("6 min"))
    }

}
