package dev.vector.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.vector.geo.LngLat
import dev.vector.geo.Units
import dev.vector.geo.walk.WalkCameraRegime
import dev.vector.geo.walk.WalkContract
import dev.vector.geo.walk.WalkCost
import dev.vector.geo.walk.WalkCrossing
import dev.vector.geo.walk.WalkCrossingExposure
import dev.vector.geo.walk.WalkDiagnostics
import dev.vector.geo.walk.WalkEventStatus
import dev.vector.geo.walk.WalkFollowState
import dev.vector.geo.walk.WalkInstructions
import dev.vector.geo.walk.WalkManeuver
import dev.vector.geo.walk.WalkManeuverKind
import dev.vector.geo.walk.WalkRefusalKind
import dev.vector.geo.walk.WalkRoadId
import dev.vector.geo.walk.WalkRoute
import dev.vector.geo.walk.WalkSegments
import dev.vector.geo.walk.WalkShade
import dev.vector.geo.walk.WalkShadeBand
import dev.vector.geo.walk.WalkStairs
import dev.vector.geo.walk.WalkingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The walking navigation surface, actually composed (V7.4 4C final).
 *
 * The same harness `NavUiTest` uses and for the same reason: the emulator
 * SIGSEGVs on this host, so Robolectric running the real Compose runtime is
 * the only place this UI has been rendered at all. For the specific question
 * of **what is on screen in walking mode**, node bounds and node text are a
 * stricter test than looking at a screenshot.
 *
 * Two things this file is really about:
 *
 *  1. **The walking band says what the instruction says** — the banner is
 *     rendered from the same object the voice speaks from, so a text
 *     assertion here is an assertion about both.
 *  2. **Car chrome is not composed on a walk, and walking chrome is not
 *     composed on a drive.** That is the isolation guarantee stated as a
 *     property of the render tree rather than as a promise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class WalkUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = mutableStateOf(UiState())
    private var mounted = false

    /** Comfortably past the longest strip transition. See `NavUiTest`. */
    private val ANIMATION_SETTLE_MS = 600L

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
                    onWalk = { walked++ },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ANIMATION_SETTLE_MS)
        compose.waitForIdle()
    }

    private var walked = 0

    private companion object {
        /**
         * 2026-09-17 15:00 Doha (UTC+3), which is the instant the shade
         * fixtures above and the session tests below are all pinned to.
         *
         * Fixed, and passed in rather than read from a clock: the whole shade
         * fact is a function of (geometry, tags, instant), so a test without a
         * pinned instant measures the machine the test ran on.
         */
        const val AT_1500 = 1_789_646_400_000L
    }

    private fun texts(): List<String> = rects().map { it.first }

    private fun rects(): List<Pair<String, Rect>> {
        val out = mutableListOf<Pair<String, Rect>>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
            if (!text.isNullOrBlank()) out.add(text to n.boundsInRoot)
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    private fun overlaps(a: Rect, b: Rect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    // ---------------------------------------------------------------- fixtures

    private val lat = 25.2854
    private val lng0 = 51.531

    private fun pt(northM: Double) =
        LngLat(lng0, lat + northM / dev.vector.geo.RouteGeometry.M_PER_DEG_LAT)

    private fun maneuver(
        kind: WalkManeuverKind,
        atM: Double,
        road: WalkRoadId? = null,
        crossing: WalkCrossing? = null,
        stairs: WalkStairs? = null,
    ) = WalkManeuver(
        kind = kind, index = (atM / 10.0).toInt(), distanceM = atM,
        distanceToNextM = null, road = road, crossing = crossing, stairs = stairs,
    )

    private fun route(
        plan: List<WalkManeuver>,
        lengthM: Double = 600.0,
        cost: WalkCost? = null,
    ): WalkRoute {
        val geom = ArrayList<LngLat>()
        var d = 0.0
        while (d < lengthM) { geom.add(pt(d)); d += 10.0 }
        geom.add(pt(lengthM))
        return WalkRoute.of(
            WalkContract(
                contractVersion = 1,
                walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general",
                mode = "foot",
                distanceM = lengthM,
                durationS = lengthM / 1.35,
                stepsM = 0.0, crossingM = 0.0, nodes = geom.size,
                geometry = geom, segments = WalkSegments(),
                facts = emptyList(), plan = plan, cost = cost,
                diagnostics = WalkDiagnostics(),
            )
        )
    }

    /** A walking state with [kind] as the live instruction. */
    private fun walkState(
        kind: WalkManeuverKind = WalkManeuverKind.TURN_LEFT,
        road: WalkRoadId? = null,
        crossing: WalkCrossing? = null,
        stairs: WalkStairs? = null,
        distanceM: Double = 120.0,
        status: WalkEventStatus = WalkEventStatus.AHEAD,
        followState: WalkFollowState = WalkFollowState.ON_ROUTE,
        arrived: Boolean = false,
        rerouting: Boolean = false,
        cost: WalkCost? = null,
        shade: WalkShade? = null,
    ): WalkNavState {
        val m = maneuver(kind, 300.0, road, crossing, stairs)
        val r = route(
            listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                m,
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
            cost = cost,
        )
        val progress = r.progressAt(300.0 - distanceM)
        val suppressed = followState == WalkFollowState.OFF_ROUTE || arrived
        return WalkNavState(
            followState = followState,
            progress = progress,
            regime = WalkCameraRegime.CRUISE,
            arrived = arrived,
            instruction = if (suppressed) null
            else WalkInstructions.of(m, 1, status, distanceM),
            rerouting = rerouting,
            shade = shade,
        )
    }

    /**
     * A shade fact with the given metres, built directly.
     *
     * Built rather than measured so the strip's wording can be exercised on
     * every band and every nothing-to-assert state — the real-Qatar accuracy of
     * the model is `WalkRealQatarShadeTest`'s job, and this file's job is what
     * reaches the screen.
     */
    private fun shade(
        shadedM: Double,
        exposedM: Double,
        nightM: Double = 0.0,
        lowSunM: Double = 0.0,
        noEvidenceM: Double = 0.0,
    ): WalkShade {
        val answered = shadedM + exposedM
        return WalkShade(
            startedAtMs = AT_1500,
            durationS = 444.4,
            totalM = answered + nightM + lowSunM + noEvidenceM,
            answeredM = answered,
            shadedM = shadedM,
            exposedM = exposedM,
            nightM = nightM,
            lowSunM = lowSunM,
            noEvidenceM = noEvidenceM,
            band = WalkShade.bandFor(if (answered <= 0.0) 0.0 else shadedM / answered),
            segments = emptyList(),
        )
    }

    private fun navWalk(walk: WalkNavState) = UiState(
        phase = Phase.NAVIGATING,
        gps = GpsHealth.GOOD,
        destinationName = "Souq Waqif",
        walk = walk,
    )

    // ------------------------------------------------------------ the banner

    @Test
    fun `the walking banner shows the instruction and its distance`() {
        show(navWalk(walkState(road = WalkRoadId(name = "Al Waab Street"))))
        val t = texts()
        assertTrue("banner text missing: $t", t.any { it == "Turn left onto Al Waab Street" })
        // "100 m", not "120 m": `Units.shortDistance` rounds to 50 m above
        // 100 m, which is the SHARED ladder the car banner, the trip bar and
        // the voice all use. Rounding to a value a person can judge against
        // what they can see is the point of it — "in 437 metres" is a machine
        // talking — and walking deliberately does not get its own ladder,
        // because two ladders are how a banner and a voice come apart.
        assertTrue("distance missing: $t", t.any { it == "100 m" })
    }

    @Test
    fun `the walking banner honours the units setting`() {
        // The defect this pins: the banner read `Units.METRIC` directly, so a
        // user on imperial would have seen feet everywhere in the product
        // except the one number they look at most while walking.
        show(
            navWalk(walkState(road = WalkRoadId(name = "Al Waab Street")))
                .copy(settings = Settings(units = Units.IMPERIAL))
        )
        val t = texts()
        assertTrue("the banner ignored the units setting: $t", t.any { it.contains("ft") })
        assertTrue("metres rendered under an imperial setting: $t", t.none { it == "100 m" })
    }

    @Test
    fun `an unknown road produces the bare action and no placeholder`() {
        show(navWalk(walkState(road = null)))
        val t = texts()
        assertTrue("banner text missing: $t", t.any { it == "Turn left" })
        assertTrue("invented a road: $t", t.none { it.contains(" onto ") })
        assertTrue("leaked null: $t", t.none { it.contains("null", ignoreCase = true) })
    }

    @Test
    fun `a crossing with known attributes draws the detail strip`() {
        show(
            navWalk(
                walkState(
                    kind = WalkManeuverKind.CROSS,
                    crossing = WalkCrossing(
                        type = "zebra", typeSource = "way", markings = null,
                        kerb = "lowered", tactilePaving = null, distanceM = 20.0,
                        distanceToCrossingM = null, approachIndex = null,
                        approachDistanceM = null, enterIndex = null, leaveIndex = null,
                        road = null, crossedRoadSource = null,
                    ),
                )
            )
        )
        val t = texts()
        assertTrue("crossing banner missing: $t", t.any { it == "Cross the road" })
        assertTrue("detail strip missing: $t", t.any { it.contains("Zebra crossing") })
        assertTrue(t.any { it.contains("Lowered kerb") })
    }

    @Test
    fun `a crossing with no known attributes draws no detail strip at all`() {
        // The common real case. An empty strip must not be reserved.
        show(
            navWalk(
                walkState(
                    kind = WalkManeuverKind.CROSS,
                    crossing = WalkCrossing(
                        type = null, typeSource = "catalog_node", markings = null,
                        kerb = null, tactilePaving = null, distanceM = 20.0,
                        distanceToCrossingM = null, approachIndex = null,
                        approachDistanceM = null, enterIndex = null, leaveIndex = null,
                        road = null, crossedRoadSource = null,
                    ),
                )
            )
        )
        val t = texts()
        assertTrue(t.any { it == "Cross the road" })
        assertTrue(
            "a detail strip was drawn with nothing known: $t",
            t.none { it.contains("crossing", ignoreCase = true) && it != "Cross the road" },
        )
    }

    @Test
    fun `a staircase nobody surveyed says only use the stairs`() {
        show(
            navWalk(
                walkState(
                    kind = WalkManeuverKind.STAIRS,
                    stairs = WalkStairs(distanceM = 6.0),
                )
            )
        )
        val t = texts()
        assertTrue(t.any { it == "Use the stairs" })
        assertTrue("invented a handrail claim: $t", t.none { it.contains("handrail", true) })
        assertTrue("invented a step count: $t", t.none { it.contains("steps", true) })
    }

    @Test
    fun `no walking screen renders an accessibility or safety claim`() {
        // Neither of these states carries a shade fact, so the assertion also
        // pins that a screen with no modelled sun says nothing about the sun.
        // Shade is NOT globally forbidden on a walking screen — from V7.4 it
        // appears when `WalkNavState.shade` exists and `stripLine` has
        // something honest to say, which the tests above cover. What is
        // forbidden is a shade word with no fact behind it, and that is what
        // this list is checking here.
        val states = listOf(
            walkState(kind = WalkManeuverKind.CROSS, crossing = WalkCrossing(
                type = "traffic_signals", typeSource = "way", markings = "zebra",
                kerb = "flush", tactilePaving = "yes", distanceM = 20.0,
                distanceToCrossingM = null, approachIndex = null,
                approachDistanceM = null, enterIndex = null, leaveIndex = null,
                road = null, crossedRoadSource = null,
            )),
            walkState(kind = WalkManeuverKind.STAIRS, stairs = WalkStairs(
                distanceM = 6.0, stepCount = 12, handrail = "yes", incline = "up",
            )),
        )
        for (s in states) {
            show(navWalk(s))
            val all = texts().joinToString(" ")
            for (claim in listOf(
                "accessible", "step-free", "wheelchair", "safe", "priority",
                "green", "red light", "shade",
            )) {
                assertFalse("\"$claim\" rendered in: $all", all.contains(claim, ignoreCase = true))
            }
        }
    }

    // -------------------------------------------------------- off route / arrival

    @Test
    fun `leaving the route replaces the instruction rather than keeping it stale`() {
        show(navWalk(walkState(followState = WalkFollowState.OFF_ROUTE)))
        val t = texts()
        assertTrue("off-route banner missing: $t", t.any { it == "Off the walking route" })
        assertTrue("a stale turn survived: $t", t.none { it.contains("Turn left") })
    }

    @Test
    fun `rerouting says so`() {
        show(
            navWalk(
                walkState(followState = WalkFollowState.OFF_ROUTE, rerouting = true)
            )
        )
        val t = texts()
        assertTrue("rerouting banner missing: $t", t.any { it == "Finding a new route" })
    }

    @Test
    fun `an uncertain fix keeps the instruction on screen`() {
        // The honest "I cannot tell" band: taking guidance away here would
        // remove the only guidance available at the moment it is least sure.
        show(navWalk(walkState(followState = WalkFollowState.UNCERTAIN)))
        val t = texts()
        assertTrue("uncertain lost the instruction: $t", t.any { it == "Turn left" })
        assertTrue("uncertain accused the walker: $t", t.none { it.contains("Off the walking") })
    }

    @Test
    fun `arrival claims the route ended, not that a destination was reached`() {
        show(navWalk(walkState(arrived = true)))
        val t = texts()
        assertTrue("arrival banner missing: $t", t.any { it == "Arrived" })
        assertTrue(
            "arrival did not say what it actually knows: $t",
            t.any { it.contains("end of the walking route") },
        )
        // The walk ends where the pedestrian network ends, which may not be
        // the door — so the destination is NOT claimed as reached.
        assertTrue(
            "claimed the destination: $t",
            t.none { it.contains("Arrived at Souq Waqif") },
        )
    }

    // ------------------------------------------------------------- the trip bar

    @Test
    fun `the walking trip bar shows distance and pace time`() {
        show(navWalk(walkState()))
        val t = texts()
        assertTrue("distance left missing: $t", t.any { it.contains("left") })
        // 600 m at 1.35 m/s is 444 s -> "7 min".
        assertTrue("walk time missing: $t", t.any { it == "7 min" })
    }

    @Test
    fun `the crossing wait is shown separately and never folded into the walk time`() {
        val cost = WalkCost(
            profile = "general", baseTimeS = 444.4, paceS = 444.4,
            costS = 576.4, penaltyS = 132.0,
            selectionFactors = listOf("crossing_wait"),
            factorS = mapOf("crossing_wait" to 132.0),
            crossing = WalkCrossingExposure(22, 208.0, 132.0, 6, 16),
        )
        show(navWalk(walkState(cost = cost)))
        val t = texts()
        // The walk time is still pure pace time...
        assertTrue("the walk time changed: $t", t.any { it == "7 min" })
        // ...and the delay is an explicit, hedged addition beside it.
        assertTrue(
            "the crossing delay was not shown additively: $t",
            t.any { it.startsWith("plus about") && it.contains("crossing") },
        )
        // Never a combined total (444 + 132 = 576 s -> would read "10 min").
        assertTrue("a combined ETA was rendered: $t", t.none { it == "10 min" })
    }

    @Test
    fun `no arrival clock is shown on a walk`() {
        // The driving bar leads with "arrive 14:32". It would be dishonest
        // here: the walk time excludes an expected crossing delay that is
        // itself a per-edge model, so a clock would assert a precision the
        // contract does not have.
        show(navWalk(walkState()))
        val clock = Regex("^\\d{2}:\\d{2}$")
        assertTrue(
            "a walk showed an arrival clock: ${texts()}",
            texts().none { clock.matches(it) },
        )
    }

    // --------------------------------------------------------- the shade line

    @Test
    fun `the route's modelled shade is shown on the detail strip, hedged`() {
        show(navWalk(walkState(shade = shade(shadedM = 400.0, exposedM = 100.0))))
        val t = texts()
        assertTrue("no shade line: $t", t.any { it == "Mostly shaded (estimated)" })
        // The hedge travels with the claim, and never as a number.
        assertTrue("a bare percentage reached the strip: $t", t.none { it.contains("%") })
    }

    @Test
    fun `each shade band renders its own words`() {
        val expected = mapOf(
            Triple(450.0, 50.0, "Mostly shaded (estimated)") to 1,
            Triple(250.0, 250.0, "Mixed shade (estimated)") to 1,
            Triple(50.0, 450.0, "Mostly exposed (estimated)") to 1,
        )
        for ((spec, _) in expected) {
            val (shaded, exposed, line) = spec
            show(navWalk(walkState(shade = shade(shadedM = shaded, exposedM = exposed))))
            assertTrue("expected \"$line\" in ${texts()}", texts().any { it == line })
        }
    }

    @Test
    fun `a walk the model could not answer for says so instead of nothing`() {
        // "Limited shade data" rather than an empty strip: a walker has to be
        // able to tell "the model has nothing" from "I scrolled past it".
        show(
            navWalk(
                walkState(
                    shade = shade(shadedM = 100.0, exposedM = 100.0, noEvidenceM = 800.0),
                )
            )
        )
        assertTrue(texts().any { it == "Limited shade data" })
    }

    @Test
    fun `a walk after sunset makes no shade claim of any kind`() {
        // Night is not shade, and the strip must not say it is. Nothing here
        // may contain a band word.
        show(navWalk(walkState(shade = shade(shadedM = 0.0, exposedM = 0.0, nightM = 600.0))))
        val t = texts()
        assertTrue("no night line: $t", t.any { it == "No direct sun" })
        for (b in dev.vector.geo.walk.WalkShadeBand.entries) {
            assertTrue("\"${b.label}\" claimed at night: $t", t.none { it.contains(b.label) })
        }
    }

    @Test
    fun `a walk in low sun declines to estimate rather than claiming shade`() {
        show(navWalk(walkState(shade = shade(shadedM = 0.0, exposedM = 0.0, lowSunM = 600.0))))
        assertTrue(texts().any { it == "Low sun; shade not estimated" })
    }

    @Test
    fun `no shade line is drawn when there is no shade fact`() {
        // A payload from a client path that produced no fact at all. The strip
        // is simply not there — no placeholder, no "unknown shade".
        show(navWalk(walkState(shade = null)))
        val t = texts()
        for (word in listOf("shade", "shaded", "sun", "exposed", "estimated")) {
            assertTrue("\"$word\" rendered with no shade fact: $t", t.none { it.contains(word, true) })
        }
    }

    @Test
    fun `shade shares the strip with the crossing attributes without merging into them`() {
        // Two lines, two different kinds of fact: the crossing's attributes are
        // SURVEYED and the sun exposure is MODELLED. They must both be legible
        // and neither may absorb the other's certainty.
        show(
            navWalk(
                walkState(
                    kind = WalkManeuverKind.CROSS,
                    crossing = WalkCrossing(
                        type = "zebra", typeSource = "way", markings = null,
                        kerb = "lowered", tactilePaving = null, distanceM = 20.0,
                        distanceToCrossingM = null, approachIndex = null,
                        approachDistanceM = null, enterIndex = null, leaveIndex = null,
                        road = null, crossedRoadSource = null,
                    ),
                    shade = shade(shadedM = 400.0, exposedM = 100.0),
                )
            )
        )
        val t = texts()
        assertTrue("the crossing attributes went missing: $t", t.any { it.contains("Zebra crossing") })
        assertTrue("the shade line went missing: $t", t.any { it == "Mostly shaded (estimated)" })
        // Joined into one sentence they would read as surveyed shade.
        assertTrue(
            "the two facts were merged into one line: $t",
            t.none { it.contains("Zebra crossing") && it.contains("shaded") },
        )
    }

    @Test
    fun `shade never displaces the instruction, the off-route state or the arrival`() {
        // Priority: navigation first. The strip below the band may carry the
        // sun; the band itself is the maneuver's and nobody else's.
        val withShade = shade(shadedM = 400.0, exposedM = 100.0)
        show(navWalk(walkState(road = WalkRoadId(name = "Al Waab Street"), shade = withShade)))
        assertTrue("the instruction was displaced", texts().any { it == "Turn left onto Al Waab Street" })

        // Off-route: the strip is withheld entirely, because the shade of a
        // route the walker is not on is not information about the ground they
        // are standing on.
        show(navWalk(walkState(followState = WalkFollowState.OFF_ROUTE, shade = withShade)))
        val off = texts()
        assertTrue("off-route banner missing: $off", off.any { it == "Off the walking route" })
        assertTrue("shade shown while off-route: $off", off.none { it.contains("shaded") })

        // Rerouting: same reasoning, and the band still says what it is doing.
        show(
            navWalk(
                walkState(
                    followState = WalkFollowState.OFF_ROUTE,
                    rerouting = true,
                    shade = withShade,
                )
            )
        )
        val re = texts()
        assertTrue("reroute banner missing: $re", re.any { it == "Finding a new route" })
        assertTrue("shade shown while rerouting: $re", re.none { it.contains("shaded") })

        // Arrived: there is no walk left to describe.
        show(navWalk(walkState(arrived = true, shade = withShade)))
        val done = texts()
        assertTrue("arrival banner missing: $done", done.any { it == "Arrived" })
        assertTrue("shade shown after arrival: $done", done.none { it.contains("shaded") })
    }

    @Test
    fun `the session puts the route's shade on the walking state`() {
        // The wiring, end to end: a real route with real classes, driven
        // through the session at a fixed instant, must publish the fact the
        // strip renders — computed from the FIRST fix and kept, so the
        // sentence cannot change while the walker stands still.
        val geom = ArrayList<LngLat>()
        var d = 0.0
        while (d < 600.0) { geom.add(pt(d)); d += 10.0 }
        geom.add(pt(600.0))
        val r = WalkRoute.of(
            WalkContract(
                contractVersion = 1,
                walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general",
                mode = "foot",
                distanceM = 600.0,
                durationS = 444.4,
                stepsM = 0.0, crossingM = 0.0, nodes = geom.size,
                geometry = geom,
                // Every segment a residential footway, so the model has
                // something to answer with.
                segments = WalkSegments(
                    classes = List(geom.size - 1) { "residential" },
                    enclosed = List(geom.size - 1) { false },
                    area = List(geom.size - 1) { false },
                ),
                facts = emptyList(),
                plan = listOf(
                    maneuver(WalkManeuverKind.DEPART, 0.0),
                    maneuver(WalkManeuverKind.ARRIVE, 600.0),
                ),
                cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )
        val s = WalkNavSession(r)
        val first = s.onFix(pt(0.0), AT_1500, CameraState(mode = CameraMode.FOLLOW), Phase.NAVIGATING)
        val shade = first.state.shade
        assertNotNull("no shade fact on the walking state", shade)
        assertEquals(AT_1500, shade!!.startedAtMs)
        assertNotNull("no sentence for a modelled walk", shade.stripLine())
        // The pinned instant really is 15:00 Doha (UTC+3), which is what the
        // constant's KDoc claims — otherwise every assertion above is pinned to
        // an hour nobody checked.
        assertEquals(
            java.time.ZonedDateTime.of(
                2026, 9, 17, 15, 0, 0, 0, java.time.ZoneOffset.ofHours(3),
            ).toInstant().toEpochMilli(),
            AT_1500,
        )

        // Kept, not recomputed: the same fact object survives later fixes, so
        // the strip cannot change while the walker stands still.
        val later = s.onFix(pt(0.0), AT_1500 + 600_000L, CameraState(mode = CameraMode.FOLLOW), Phase.NAVIGATING)
        assertTrue("the shade fact was recomputed mid-walk", later.state.shade === shade)
    }

    @Test
    fun `the session rescores shade after a reroute instead of inheriting it`() {
        val geom = ArrayList<LngLat>()
        var d = 0.0
        while (d < 600.0) { geom.add(pt(d)); d += 10.0 }
        geom.add(pt(600.0))
        fun contract(highway: String) = WalkContract(
            contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
            walkingProfileRaw = "general", mode = "foot",
            distanceM = 600.0, durationS = 444.4, stepsM = 0.0, crossingM = 0.0,
            nodes = geom.size, geometry = geom,
            segments = WalkSegments(
                classes = List(geom.size - 1) { highway },
                enclosed = List(geom.size - 1) { false },
                area = List(geom.size - 1) { false },
            ),
            facts = emptyList(),
            plan = listOf(
                maneuver(WalkManeuverKind.DEPART, 0.0),
                maneuver(WalkManeuverKind.ARRIVE, 600.0),
            ),
            cost = null, diagnostics = WalkDiagnostics(),
        )
        val s = WalkNavSession(WalkRoute.of(contract("residential")))
        val cam = CameraState(mode = CameraMode.FOLLOW)
        val before = s.onFix(pt(0.0), AT_1500, cam, Phase.NAVIGATING).state.shade!!
        // A replacement route of a different class, which the model answers
        // differently at the same instant.
        s.replaceRoute(WalkRoute.of(contract("primary")))
        assertNull("the old route's shade survived a replacement", s.state.shade)
        val after = s.onFix(pt(0.0), AT_1500, cam, Phase.NAVIGATING).state.shade!!
        assertTrue(
            "the new route inherited the old route's answer",
            after.modelledShadeFraction != before.modelledShadeFraction,
        )
        assertEquals(0.0, after.shadedM, 1e-9)
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `a pedestrian network split is explained as a map fact`() {
        show(
            UiState(
                phase = Phase.PREVIEW,
                destinationName = "Festival City",
                walkRefusal = WalkRefusalKind.NETWORK_SPLIT,
            )
        )
        val t = texts()
        assertTrue("split banner missing: $t", t.any { it == "No continuous walking route" })
        assertTrue(t.any { it.contains("don't connect") })
        // And it is not dressed up as a crash.
        assertTrue("leaked diagnostics: $t", t.none { it.contains("snap", ignoreCase = true) })
    }

    @Test
    fun `the three refusals render three different sentences`() {
        val seen = mutableSetOf<String>()
        for (kind in WalkRefusalKind.entries) {
            show(UiState(phase = Phase.PREVIEW, walkRefusal = kind))
            val line = texts().firstOrNull {
                it == dev.vector.geo.walk.WalkRefusalText.title(kind)
            }
            assertTrue("no banner for $kind: ${texts()}", line != null)
            seen.add(line!!)
        }
        assertEquals("two refusals rendered the same headline", 3, seen.size)
    }

    // ------------------------------------------------------- the entry point

    @Test
    fun `preview offers a walk control beside start`() {
        show(
            UiState(
                phase = Phase.PREVIEW,
                destinationName = "Souq Waqif",
                routeDistanceM = 2_400.0,
                routeDurationS = 300.0,
            )
        )
        val t = texts()
        assertTrue("no Walk control: $t", t.contains("Walk"))
        assertTrue("Start went missing: $t", t.contains("Start"))
    }

    @Test
    fun `the walk control is offered without any paywall`() {
        // Walking navigation is a baseline capability. The journey card's walk
        // LEG is a sold feature and stays so; this is not it.
        show(
            UiState(
                phase = Phase.PREVIEW,
                destinationName = "Souq Waqif",
                routeDistanceM = 2_400.0,
                routeDurationS = 300.0,
                pro = dev.vector.android.pro.ProStatus.FREE,
                proFeatures = dev.vector.android.pro.ProCatalogue.features,
            )
        )
        assertTrue("a free user was not offered the walk: ${texts()}", texts().contains("Walk"))
    }

    // ----------------------------------------------------- car/foot exclusivity

    @Test
    fun `driving chrome is not composed on a walk`() {
        show(navWalk(walkState()))
        val t = texts()
        // The speedometer's unit label, the turn-list control and the car
        // trip bar's clock all belong to driving.
        assertTrue("a speed readout appeared on a walk: $t", t.none { it == "km/h" })
    }

    @Test
    fun `walking chrome is not composed on a drive`() {
        show(
            UiState(
                phase = Phase.NAVIGATING,
                gps = GpsHealth.GOOD,
                speedKmh = 58,
                remainingM = 8_400.0,
                remainingS = 540.0,
                distanceToManeuverM = 400.0,
                currentManeuver = Maneuver(1, "turn-left", "Turn left onto Al Waab", 2500.0, 300.0),
                maneuvers = listOf(
                    Maneuver(0, "depart", "Head north", 300.0, 0.0),
                    Maneuver(1, "turn-left", "Turn left onto Al Waab", 2500.0, 300.0),
                ),
            )
        )
        val t = texts()
        assertTrue("walking wording on a drive: $t", t.none { it.contains("walking route") })
        assertTrue(t.none { it.startsWith("plus about") })
        assertTrue(t.none { it == "Use the stairs" || it == "Cross the road" })
    }

    // ----------------------------------------------------------- the overlap rule

    @Test
    fun `no two text elements overlap in any walking state`() {
        // The same structural guarantee `NavUiTest` holds for the three car
        // phases, extended to every walking state — because the walking band
        // occupies the same slot the maneuver band does.
        for (candidate in listOf(
            navWalk(walkState()),
            navWalk(walkState(road = WalkRoadId(name = "شارع الوعب"))),
            navWalk(
                walkState(
                    kind = WalkManeuverKind.CROSS,
                    crossing = WalkCrossing(
                        type = "zebra", typeSource = "way", markings = "zebra",
                        kerb = "lowered", tactilePaving = "yes", distanceM = 20.0,
                        distanceToCrossingM = null, approachIndex = null,
                        approachDistanceM = null, enterIndex = null, leaveIndex = null,
                        road = null, crossedRoadSource = null,
                    ),
                )
            ),
            navWalk(walkState(followState = WalkFollowState.OFF_ROUTE)),
            navWalk(walkState(followState = WalkFollowState.OFF_ROUTE, rerouting = true)),
            navWalk(walkState(followState = WalkFollowState.UNCERTAIN)),
            navWalk(walkState(arrived = true)),
            // The shade line, in each of its shapes, because it occupies the
            // strip the crossing attributes also use and the two can be on
            // screen together.
            navWalk(walkState(shade = shade(shadedM = 400.0, exposedM = 100.0))),
            navWalk(
                walkState(
                    kind = WalkManeuverKind.CROSS,
                    crossing = WalkCrossing(
                        type = "zebra", typeSource = "way", markings = "zebra",
                        kerb = "lowered", tactilePaving = "yes", distanceM = 20.0,
                        distanceToCrossingM = null, approachIndex = null,
                        approachDistanceM = null, enterIndex = null, leaveIndex = null,
                        road = null, crossedRoadSource = null,
                    ),
                    shade = shade(shadedM = 100.0, exposedM = 500.0),
                )
            ),
            navWalk(walkState(shade = shade(shadedM = 100.0, exposedM = 100.0, noEvidenceM = 800.0))),
            navWalk(walkState(shade = shade(shadedM = 0.0, exposedM = 0.0, nightM = 600.0))),
            navWalk(walkState(road = WalkRoadId(name = "شارع الوعب"), shade = shade(200.0, 400.0))),
            navWalk(walkState()).copy(gps = GpsHealth.WEAK),
            navWalk(walkState()).copy(snapWarningM = 420.0),
            navWalk(walkState()).copy(walkRefusal = WalkRefusalKind.BACKEND_FAILURE),
            UiState(phase = Phase.PREVIEW, walkRefusal = WalkRefusalKind.NETWORK_SPLIT),
            navWalk(walkState()).copy(
                settings = Settings(theme = VectorStyle.MapTheme.LIGHT),
            ),
        )) {
            show(candidate)
            val rs = rects()
            for (i in rs.indices) {
                for (j in i + 1 until rs.size) {
                    val (ta, ra) = rs[i]
                    val (tb, rb) = rs[j]
                    assertTrue(
                        "walking: \"$ta\" $ra overlaps \"$tb\" $rb",
                        !overlaps(ra, rb),
                    )
                }
            }
        }
    }

    // ---- the trip bar's remaining distance -------------------------------
    //
    // `WalkNavState.remainingM` is null while the route lock is dropped — a
    // confirmed departure, before a reroute lands. The bar used to coalesce
    // that to 0.0 and render "0 m left" NEXT TO an off-route banner. A walker
    // who has just been told they are off route, and is then told they have
    // arrived, has been given a stronger and wronger claim than silence.
    //
    // Recorded as a known defect through two stages (V7.4-STAGE-4C-FINAL and
    // V7.4-SHADE) without a test, which is why it survived both.

    @Test
    fun `the trip bar states the remaining distance when it knows it`() {
        // The fixture route totals 600 m and the default walkState() sits
        // 200 m along it, so 400 m remain on the shared distance ladder.
        show(navWalk(walkState()))
        val t = texts()
        assertTrue("expected a remaining distance: $t", t.any { it == "400 m left" })
    }

    @Test
    fun `an unknown remaining distance is not rendered as zero`() {
        // `remainingM` is derived from `progress`, so a dropped route lock IS
        // a null progress. This is the state the walker is in between a
        // confirmed departure and a reroute landing.
        show(
            navWalk(
                walkState(followState = WalkFollowState.OFF_ROUTE).copy(progress = null)
            )
        )
        val t = texts()
        assertTrue(
            "a null remaining distance was rendered as a claim: $t",
            t.none { it.contains("0 m left") },
        )
        assertTrue(
            "no distance may be claimed at all while it is unknown: $t",
            t.none { it.endsWith(" left") },
        )
    }

}
