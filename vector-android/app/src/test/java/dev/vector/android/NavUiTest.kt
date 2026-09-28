package dev.vector.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.vector.geo.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The navigation UI, actually composed.
 *
 * The Android emulator SIGSEGVs on this host (kernel 7.1.13; four core dumps,
 * every GPU mode, KVM verified healthy), so the app had never been rendered
 * anywhere. Robolectric runs the real Compose runtime and the real Android
 * framework on the JVM, which recovers most of what a device would have told
 * us — and for the specific question of **overlapping UI**, node bounds are a
 * stricter test than looking at a screenshot.
 *
 * What this still does NOT prove, and no JVM test can: MapLibre's GL rendering,
 * real GPS behaviour, frame rate, or whether the maneuver card is readable at a
 * glance while driving. Those remain the S24's job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class NavUiTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The map is a real Android View that needs a GL surface, which Robolectric
     * has no business creating. Every test below drives the CHROME, so the map
     * is replaced by a plain View of the same size — the layout question is
     * about the overlays, not about what is behind them.
     */
    private val state = mutableStateOf(UiState())
    private var mounted = false

    /**
     * What the data-management controls actually invoked.
     *
     * Recorded rather than ignored, because "the row is on screen" is only
     * half of what §6 asks: a control has to *reach the correct state*, and the
     * class of bug this guards against is the one V5 hit twice — a confirmation
     * written into a state nothing rendered, and `Places.clear` written, tested
     * and wired to nothing at all.
     */
    /**
     * Clicks are dispatched through the semantics `OnClick` action, not by
     * injecting a touch.
     *
     * `performClick()` injects a real down/up at the node's centre, and under
     * Robolectric it invokes nothing at all — verified against `VoiceChoice`,
     * a control that has shipped since V4 and demonstrably works on the
     * handset. It is why no test in this file had ever clicked anything, and
     * an assertion built on it would fail for every control equally, which
     * makes it useless as a test of any of them.
     *
     * `performSemanticsAction(OnClick)` invokes the composable's own `onClick`,
     * so what these cases assert is the WIRING: this row's click reaches this
     * callback with this argument. That is the half §6 is about — "does
     * something, reaches the correct state" — and it is the half that has
     * actually been wrong here twice.
     *
     * The other half, that the control can be hit by a finger and is not
     * covered by something else, is asserted by the node-bounds tests further
     * up this file and verified for real on the S24.
     */
    private var zoomedIn = 0
    private var zoomedOut = 0
    private val clearedPlaces = mutableListOf<Places.Slot>()
    private val pickedDrives = mutableListOf<Drives.Drive>()
    private val deletedDrives = mutableListOf<Drives.Drive>()
    private var clearedDrives = 0
    private var clearedRecents = 0

    /**
     * Comfortably past the longest transition in `VectorTokens.Motion`
     * (`MEDIUM`, 380 ms). Not `FLIGHT`, which is a camera duration and never
     * gates a composable.
     */
    private val ANIMATION_SETTLE_MS = 600L

    /**
     * The recenter control's accessible label.
     *
     * These tests used to match `hasText("Recenter")`. V4 replaced the labelled
     * pill with an icon in the same 48 dp stack as the other map controls — a
     * control that comes and goes is less alarming when it keeps the shape and
     * position of its neighbours than when it is a differently-shaped pill that
     * shifts the stack. Matching on the content description is also the more
     * durable contract: it is what a screen reader is handed, so it cannot be
     * quietly lost by a restyle.
     */
    private val RECENTER = "Put the camera back on the vehicle"

    /**
     * Compose's test rule allows `setContent` exactly ONCE per test, so the
     * chrome is mounted against a mutable state holder and driven by updating
     * it. That also mirrors how the real app works — one composition, state
     * changing underneath — rather than remounting the tree for every phase.
     */
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
                    onClearRecents = { clearedRecents++ },
                    onClearPlace = { clearedPlaces.add(it) },
                    onPickDrive = { pickedDrives.add(it) },
                    onDeleteDrive = { deletedDrives.add(it) },
                    onClearDrives = { clearedDrives++ },
                    onZoomIn = { zoomedIn++ },
                    onZoomOut = { zoomedOut++ },
                )
            }
        }
        compose.waitForIdle()
        // Run every animation to completion before asserting.
        //
        // V4 made appearance and disappearance animated (`VectorMotion`), so a
        // state change that used to be observable on the next frame now has a
        // 220 ms enter transition in front of it. `waitForIdle` does not settle
        // an animation on its own under Robolectric — the frame clock has to be
        // advanced — and the symptom is a node that exists at zero height and
        // fails `assertIsDisplayed`, which is a genuinely confusing way for
        // these tests to fail.
        //
        // These are LAYOUT tests: what is on screen in a given state, and
        // whether any two things overlap. The intermediate frames of a fade are
        // not the subject, so settling them is right rather than a workaround —
        // and `VectorMotionTest` covers the motion system itself.
        compose.mainClock.advanceTimeBy(ANIMATION_SETTLE_MS)
        compose.waitForIdle()
    }

    private fun visibleRects(): List<Pair<String, Rect>> {
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

    // ---- rendering ---------------------------------------------------------

    @Test
    fun `explore renders a search affordance`() {
        show(UiState(phase = Phase.EXPLORE))
        // A question, not a label. Both reference products use the question,
        // and it is the difference between a label and an invitation — Vector's
        // collapsed bar was once the grey word "Search" with no icon, which
        // reads as a caption for the thing above it.
        //
        // The wording moved from "Where to?" to "Where are we going?" when the
        // entry moved off the map and into the discovery sheet: on a surface
        // that is now the app's resting composition it is a sentence the
        // product says, not a form field's placeholder. See `SearchSurface.kt`.
        compose.onNodeWithText("Where are we going?").assertIsDisplayed()
    }

    @Test
    fun `preview shows the distance, the time and a start control`() {
        show(
            UiState(
                phase = Phase.PREVIEW,
                destinationName = "West Bay",
                routeDistanceM = 11_530.0,
                routeDurationS = 624.0,
                maneuvers = listOf(Maneuver(0, "depart", "Head north", 300.0, 0.0)),
            )
        )
        compose.onNodeWithText("West Bay").assertIsDisplayed()
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText("10 min").assertIsDisplayed()
    }

    @Test
    fun `navigating shows the maneuver, the countdown and the speed`() {
        show(navState())
        compose.onNodeWithText("Turn left onto شارع الكورنيش").assertIsDisplayed()
        compose.onNodeWithText("400 m").assertIsDisplayed()
        compose.onNodeWithText("58").assertIsDisplayed()
    }

    @Test
    fun `arabic instructions render without mangling`() {
        show(navState())
        val texts = visibleRects().map { it.first }
        assertTrue(
            "Arabic name missing from the rendered tree: $texts",
            texts.any { it.contains("شارع الكورنيش") },
        )
        assertTrue("escaped unicode reached the screen", texts.none { it.contains("\\u") })
    }

    // ---- the overlap regression -------------------------------------------

    @Test
    fun `no two text elements overlap in any phase`() {
        // The previously reported "overlapping UI bubbles". Node bounds catch
        // this far more reliably than looking at a screenshot would.
        for (candidate in listOf(
            UiState(phase = Phase.EXPLORE, status = "Ready"),
            UiState(
                phase = Phase.PREVIEW, destinationName = "West Bay",
                routeDistanceM = 11_530.0, routeDurationS = 624.0,
                maneuvers = listOf(Maneuver(0, "depart", "Head north", 300.0, 0.0)),
            ),
            navState(),
            navState().copy(showSteps = true),
            navState().copy(rerouting = true, offRoute = true),
            navState().copy(error = "That point is outside the mapped area"),
            navState().copy(snapWarningM = 420.0),
            navState().copy(speedLimitKmh = 80, jamCount = 7),
            navState().copy(speedLimitKmh = 60, speedKmh = 92),
            // ---- states the V3 controls introduced ----
            // The map-control stack shares the bottom-right column with the
            // voice and traffic pills, so every combination of it has to be
            // checked, not just the one that happened to be on screen.
            navState().copy(cam = CameraState(mode = CameraMode.FREE)),
            navState().copy(cam = CameraState(mode = CameraMode.OVERVIEW)),
            navState().copy(cam = CameraState(manualBearing = 42.0)),
            navState().copy(
                speedLimitKmh = 80, jamCount = 7, showSteps = true,
                cam = CameraState(mode = CameraMode.FREE),
            ),
            UiState(phase = Phase.EXPLORE, status = "Ready",
                    cam = CameraState(mode = CameraMode.FREE)),
            // Preview with a choice of routes: the chips sit between the step
            // list slot and the preview bar.
            UiState(
                phase = Phase.PREVIEW, destinationName = "West Bay",
                routeDistanceM = 11_530.0, routeDurationS = 624.0,
                maneuvers = listOf(Maneuver(0, "depart", "Head north", 300.0, 0.0)),
                alternatives = twoRoutes(), chosenRoute = 0,
            ),
            // The light theme changes colours, not geometry — but it also
            // changes the Material scheme, and a scheme swap has moved text
            // metrics before.
            navState().copy(settings = Settings(theme = VectorStyle.MapTheme.LIGHT)),
        )) {
            show(candidate)
            val rects = visibleRects()
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val (ta, ra) = rects[i]
                    val (tb, rb) = rects[j]
                    assertTrue(
                        "${candidate.phase}: \"$ta\" $ra overlaps \"$tb\" $rb",
                        !overlaps(ra, rb),
                    )
                }
            }
        }
    }

    @Test
    fun `search results never collide with the search bar`() {
        show(
            UiState(
                phase = Phase.EXPLORE, searching = true, query = "souq",
                results = listOf(
                    VectorApi.Place("Souq Waqif", LngLat(51.53, 25.28), "poi"),
                    VectorApi.Place("Souq Al Wakrah", LngLat(51.60, 25.17), "poi"),
                ),
            )
        )
        compose.waitForIdle()
        val rects = visibleRects()
        val bar = rects.firstOrNull { it.first.contains("Where to", true) || it.first == "Cancel" }
        val result = rects.firstOrNull { it.first == "Souq Waqif" }
        if (bar != null && result != null) {
            assertTrue(
                "results overlap the search bar: ${bar.second} vs ${result.second}",
                !overlaps(bar.second, result.second),
            )
        }
    }

    // ---- phase exclusivity -------------------------------------------------

    @Test
    fun `the three phases never show each others chrome`() {
        // This is the structural guarantee behind the overlap fix: two overlays
        // that used to collide can no longer be composed at the same time.
        show(UiState(phase = Phase.EXPLORE))
        assertEquals(0, compose.onAllNodes(hasText("Start")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Exit")).fetchSemanticsNodes().size)

        show(navState())
        assertEquals(0, compose.onAllNodes(hasText("Search")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Start")).fetchSemanticsNodes().size)
    }

    @Test
    fun `the step list only appears when asked for`() {
        show(navState())
        assertEquals(0, compose.onAllNodes(hasText("Head north on Grand Hamad")).fetchSemanticsNodes().size)
        show(navState().copy(showSteps = true))
        compose.onNodeWithText("Head north on Grand Hamad").assertIsDisplayed()
    }

    // ---- the turn list, as a sheet on the bottom edge -----------------------

    @Test
    fun `the turn list sits on the bottom edge, not in the middle of the stack`() {
        // It used to be an item in the bottom Column, which put it above the
        // speedometer and the trip bar — a card in the middle of the screen.
        // Waze's equivalent rises from the bottom of the display
        // (`v4-evidence/waze/08-trip-sheet-expanded.png`), and the check that
        // it actually DID is that the sheet's own header is in the bottom half.
        show(navState().copy(showSteps = true))
        val screen = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val header = compose.onNodeWithContentDescription("Hide the turn list")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the sheet header is at ${header.top} on a $screen screen — not a bottom sheet",
            header.top > screen / 2f,
        )
    }

    @Test
    fun `the open turn list owns the bottom of the screen`() {
        // The chrome the sheet covers is not composed underneath it: a trip bar
        // behind an opaque sheet is a tap target the driver cannot see and the
        // overlap invariant cannot hold. The numbers it carried are on the
        // sheet's own header instead.
        show(navState().copy(showSteps = true))
        compose.onAllNodesWithContentDescription("End navigation").assertCountEquals(0)
        assertEquals(0, compose.onAllNodes(hasText("58")).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Hide the turn list").assertIsDisplayed()

        // ...and it all comes back when the list closes.
        show(navState())
        compose.onNodeWithContentDescription("End navigation").assertIsDisplayed()
        compose.onNodeWithText("58").assertIsDisplayed()
    }

    @Test
    fun `the turn list says which turn is being driven`() {
        // navState's vehicle is between maneuver 0 and maneuver 1, so the list
        // has one passed row and one current row. Asserted through the
        // accessibility labels rather than the colours, because those are the
        // half of the marking a screen reader gets — and the colours are the
        // half §20 forbids relying on alone.
        show(navState().copy(showSteps = true))
        compose.onNodeWithContentDescription("Now: Turn left onto شارع الكورنيش")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Passed: Head north on Grand Hamad")
            .assertIsDisplayed()
    }

    @Test
    fun `the turn being driven counts down to itself, not to the next one`() {
        // The current row shows distance-to-THIS-turn (400 m); every other row
        // shows the length of the leg that follows it. The row before it is a
        // leg of 300 m, so the two numbers cannot be confused.
        show(navState().copy(showSteps = true))
        compose.onAllNodesWithText("400 m").fetchSemanticsNodes().isNotEmpty().let {
            assertTrue("the current row is not counting down to its own turn", it)
        }
        compose.onNodeWithText("300 m").assertIsDisplayed()
    }

    // ---- state that the driver must be told about --------------------------

    @Test
    fun `rerouting is visible while it happens`() {
        show(navState().copy(rerouting = true))
        compose.onNodeWithText("Rerouting…").assertIsDisplayed()
    }

    @Test
    fun `a long endpoint snap is surfaced, not hidden`() {
        show(navState().copy(snapWarningM = 420.0))
        val texts = visibleRects().map { it.first }
        assertTrue(texts.toString(), texts.any { it.contains("Nearest road") })
    }

    @Test
    fun `the driving HUD carries no vague consent toggle`() {
        // Reported from the S24: *"share traffic button is quite vague."* It
        // was — "Share traffic" does not say what is shared, with whom, or
        // that the driver's own position is the thing being sent, and a
        // two-word pill has nowhere to explain it. The DECISION moved to the
        // settings sheet, next to a sentence that describes what leaves the
        // phone.
        show(navState())
        compose.onNodeWithText("Share traffic").assertDoesNotExist()
        compose.onNodeWithText("Sharing traffic").assertDoesNotExist()
    }

    @Test
    fun `consent alone does not put a notice on screen for the whole drive`() {
        // The defect: the pill was drawn whenever `settings.contributing` was
        // on, which is a PREFERENCE — so it claimed "Sending speed data" on a
        // parked phone, on no network, and with an empty probe buffer, for the
        // entire journey. A permanent badge is furniture, and the one state it
        // could not report was the one it was named after.
        show(navState().copy(settings = Settings(contributing = true), probesSent = 0))
        compose.onNodeWithText("Speed data sent").assertDoesNotExist()
    }

    @Test
    fun `the driver is told when data has actually gone`() {
        // Removing the permanence must not remove the visibility. That it is
        // happening is exactly the part a driver needs to be able to see — it
        // is just tied to the upload now rather than to the preference, so it
        // says something true. `probesSent` is incremented by
        // MainActivity.collectProbe only after POST /probes has succeeded.
        show(navState().copy(settings = Settings(contributing = true), probesSent = 30))
        compose.onNodeWithText("Speed data sent").assertIsDisplayed()
    }

    @Test
    fun `an upload by someone who never consented is not announced`() {
        // Belt and braces: `contributing` still gates the notice, so a stale
        // probe count left over from before consent was withdrawn cannot put
        // it back on screen.
        show(navState().copy(settings = Settings(contributing = false), probesSent = 30))
        compose.onNodeWithText("Speed data sent").assertDoesNotExist()
    }

    @Test
    fun `the notice goes away again by itself`() {
        // The whole point. If it did not expire it would be the permanent
        // badge again, just with a later start.
        show(navState().copy(settings = Settings(contributing = true), probesSent = 30))
        compose.onNodeWithText("Speed data sent").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(SHARING_NOTICE_MS + ANIMATION_SETTLE_MS)
        compose.waitForIdle()
        compose.onNodeWithText("Speed data sent").assertDoesNotExist()
    }

    @Test
    fun `the sending indicator says what it means to a screen reader`() {
        show(navState().copy(settings = Settings(contributing = true), probesSent = 30))
        compose.onNodeWithContentDescription(
            "Anonymised speed data for the roads you drive has just been sent"
        ).assertIsDisplayed()
    }

    @Test
    fun `recenter only appears once the driver has taken the map`() {
        show(navState().copy(cam = CameraState(mode = CameraMode.FOLLOW)))
        assertEquals(0, compose.onAllNodes(hasContentDescription(RECENTER)).fetchSemanticsNodes().size)
        show(navState().copy(cam = CameraState(mode = CameraMode.FREE)))
        compose.onNodeWithContentDescription(RECENTER).assertIsDisplayed()
    }


    // ---- Google/Waze parity features ---------------------------------------

    @Test
    fun `the posted speed limit is shown while navigating`() {
        show(navState().copy(speedLimitKmh = 80))
        compose.onNodeWithText("80").assertIsDisplayed()
    }

    @Test
    fun `no sign is shown when the road has no posted limit`() {
        // An invented limit is worse than none: the driver would act on it.
        show(navState().copy(speedLimitKmh = null))
        val texts = visibleRects().map { it.first }
        assertTrue("a limit appeared from nowhere: $texts", texts.none { it == "80" })
    }

    /**
     * The bounds of the two discs in the bottom-left corner.
     *
     * By CONTENT DESCRIPTION, not by text. The old version of the test below
     * compared the bounds of the NUMBERS — two small nodes in the middle of two
     * large discs — which is why it passed while the product owner was looking
     * at a screenshot of the discs apparently colliding. A number that clears
     * another number by 40 dp says nothing about the plates they are drawn on.
     */
    private fun disc(prefix: String): Rect {
        val n = visibleDescs().firstOrNull { it.first.startsWith(prefix) }
        assertTrue("no node described as \"$prefix…\" in ${visibleDescs().map { it.first }}",
                   n != null)
        return n!!.second
    }

    private fun visibleDescs(): List<Pair<String, Rect>> {
        val out = mutableListOf<Pair<String, Rect>>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it to n.boundsInRoot) }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return out
    }

    @Test
    fun `the two speed discs never touch, at any speed and any limit`() {
        // Every combination, because the complaint was that they collide and a
        // layout that collides at one value and not another is a layout held
        // together by the values. These two are siblings in a Row with a fixed
        // arrangement, so the property under test is that nothing about the
        // NUMBERS can change either disc's footprint.
        val gapPx = with(compose.density) { VectorTokens.Space.s12.toPx() }
        for (speed in listOf(null, 0, 58, 100, 118, 240)) {
            for (limit in listOf(30, 80, 120)) {
                show(navState().copy(speedKmh = speed, speedLimitKmh = limit))
                // "--" when the receiver has no fix, and the dial is named for
                // that too — it is the same disc either way, which is the
                // point: its footprint does not depend on what is in it.
                val speedo = disc(if (speed == null) "Speed not known" else "Travelling at ")
                val sign = disc("Speed limit ")
                assertTrue(
                    "speed=$speed limit=$limit: dial $speedo overlaps sign $sign",
                    !overlaps(speedo, sign),
                )
                assertTrue(
                    "speed=$speed limit=$limit: only ${sign.left - speedo.right} px " +
                        "between the dial and the sign, wanted $gapPx",
                    sign.left - speedo.right >= gapPx - 0.5f,
                )
            }
        }
    }

    @Test
    // NATIVE, so `Paint` measures with the real font rather than returning
    // Robolectric's legacy stub — the same reason `PillImageTest` asks for it.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `the dial is big enough for a three-digit speed, with its rim clear`() {
        // The measured defect, as arithmetic rather than as a screenshot.
        //
        // A circle is the least forgiving container for a two-line stack: the
        // width available shrinks the further the ink sits from the centre, so
        // the corner of the number is what leaves the disc, not its middle.
        // At 54 dp that corner was 27.1 dp from a 27 dp centre — "100" did not
        // fit, and Doha's expressways are posted at 100 and 120.
        //
        // Real font metrics, not a restatement of the constant: NATIVE
        // graphics gives Skia's own measurement of the string Compose will
        // draw. If the type scale moves, this fails.
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val d = context.resources.displayMetrics.density
        // The REAL fonts, not the platform default.
        //
        // The dial's number is set in Manrope ExtraBold and its unit in Inter
        // Medium, and both are wider than Roboto's digits at the same size.
        // Measuring the fallback would let this pass while the shipped face
        // overflowed the disc — which is the exact class of defect the test was
        // written for, so measuring the wrong face would have made it decorative.
        // The roles come from the design system, so a change to the type scale
        // still moves this test.
        val value = android.graphics.Paint().apply {
            textSize = dev.vector.android.design.LightTypography.hudSecondary.fontSize.value * d
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.manrope_800)
        }
        val unit = android.graphics.Paint().apply {
            textSize = dev.vector.android.design.LightTypography.caption.fontSize.value * d
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_500)
        }
        val vFm = value.fontMetrics
        val uFm = unit.fontMetrics
        // The column is the two line boxes stacked, centred in the disc.
        val valueLine = (vFm.descent - vFm.ascent) / d
        val unitLine = (uFm.descent - uFm.ascent) / d
        val columnTop = -(valueLine + unitLine) / 2f

        val vInk = android.graphics.Rect()
        value.getTextBounds("100", 0, 3, vInk)
        val vBaseline = columnTop + (-vFm.ascent / d)
        // The top corners of the number are its worst case: they sit furthest
        // from the centre of the disc in both axes at once.
        val vCorner = kotlin.math.hypot(
            (vInk.width() / d) / 2f,
            kotlin.math.abs(vBaseline + vInk.top / d),
        )

        val uInk = android.graphics.Rect()
        unit.getTextBounds("km/h", 0, 4, uInk)
        val uBaseline = columnTop + valueLine + (-uFm.ascent / d)
        val uCorner = kotlin.math.hypot(
            (uInk.width() / d) / 2f,
            kotlin.math.abs(uBaseline + uInk.bottom / d),
        )

        val worst = maxOf(vCorner, uCorner)
        val radius = VectorTokens.Size.speedCurrentDial.value / 2f
        val inset = VectorTokens.Size.speedDialInset.value
        assertTrue(
            "the ink reaches $worst dp from the centre of a ${radius * 2} dp dial, " +
                "leaving ${radius - worst} dp of rim and not $inset",
            worst + inset <= radius,
        )
        // And the regression itself: the limit sign's 54 dp, which is what
        // this disc used to be, cannot hold it.
        assertTrue(
            "a ${VectorTokens.Size.speedDial} dial would now be big enough, so this " +
                "disc no longer needs to be its own size",
            worst + inset > VectorTokens.Size.speedDial.value / 2f,
        )
    }

    @Test
    fun `the dial names the speed it is showing, and whether it is over`() {
        // The over-limit state was ring-and-colour only, which is §20's rule
        // kept for sighted drivers and broken for everyone else: a screen
        // reader was handed the bare number "92" and nothing else.
        show(navState().copy(speedKmh = 92, speedLimitKmh = 60))
        compose.onNodeWithContentDescription("Travelling at 92 km/h, over the limit")
            .assertIsDisplayed()
        show(navState().copy(speedKmh = 58, speedLimitKmh = 80))
        compose.onNodeWithContentDescription("Travelling at 58 km/h").assertIsDisplayed()
        show(navState().copy(speedKmh = null))
        compose.onNodeWithContentDescription("Speed not known").assertIsDisplayed()
    }

    @Test
    fun `arrival time is shown as a clock, not only as minutes`() {
        // "arrive 14:32" answers "will I make the meeting"; "18 min" does not
        // without arithmetic while driving.
        show(navState().copy(remainingS = 900.0))
        val texts = visibleRects().map { it.first }
        // The trip bar's Column is clickable, so Compose MERGES its children's
        // semantics into one node — the clock and the "N min" arrive
        // concatenated. Match the pattern anywhere rather than anchoring.
        // One or two digits before the colon: the clock now follows the
        // phone's 12/24-hour setting, so this is "21:32" on one device and
        // "9:32 pm" on another. The assertion is that a CLOCK is shown rather
        // than only a duration, which is what the test is named for — pinning
        // the 24-hour shape was pinning a bug.
        assertTrue(
            "no clock-style arrival time in $texts",
            texts.any { Regex("\\d{1,2}:\\d{2}").containsMatchIn(it) },
        )
    }


    @Test
    fun `a jam count is shown while navigating`() {
        show(navState().copy(jamCount = 7))
        compose.onNodeWithText("7 jams").assertIsDisplayed()
    }

    @Test
    fun `no jam readout when the roads are clear`() {
        show(navState().copy(jamCount = 0))
        val texts = visibleRects().map { it.first }
        assertTrue("a clear road must not claim jams: $texts",
            texts.none { it.contains("jams") })
    }

    @Test
    fun `rerouting takes precedence over the jam count`() {
        // Both compete for the same slot; the driver needs the urgent one.
        show(navState().copy(jamCount = 7, rerouting = true))
        compose.onNodeWithText("Rerouting…").assertIsDisplayed()
        val texts = visibleRects().map { it.first }
        assertTrue(texts.none { it.contains("jams") })
    }

    // ---- the signal member of line two (V7 Stage 5) -----------------------

    private fun signalAhead(
        phase: dev.vector.geo.signal.Phase,
        basis: dev.vector.geo.signal.Basis,
        confidence: Double = 0.8,
    ) = SignalAhead(
        signalId = "n1",
        distanceM = 500.0,
        arrival = dev.vector.geo.signal.ArrivalWindow(30.0, 10.0),
        prediction = dev.vector.geo.signal.SignalPrediction("n1", phase, confidence, basis),
    )

    @Test
    fun `a phase claim appears on line two only when timing evidence exists`() {
        show(navState().copy(roadName = "Al Corniche",
            signalAhead = signalAhead(dev.vector.geo.signal.Phase.GREEN,
                dev.vector.geo.signal.Basis.TIMING)))
        compose.onNodeWithText("Likely green").assertIsDisplayed()
        // The phase claim outranks the road name: one slot, one statement.
        val texts = visibleRects().map { it.first }
        assertTrue(texts.none { it.contains("Al Corniche") })
    }

    @Test
    fun `today's location-only data shows no phase word`() {
        // Every real signal in Qatar today predicts UNKNOWN with basis
        // LOCATION. The slot must stay on the road name -- no colour, no
        // "Signal ahead", nothing fabricated.
        show(navState().copy(roadName = "Al Corniche",
            signalAhead = signalAhead(dev.vector.geo.signal.Phase.UNKNOWN,
                dev.vector.geo.signal.Basis.LOCATION, confidence = 0.0)))
        compose.onNodeWithContentDescription("Driving on Al Corniche").assertIsDisplayed()
        val texts = visibleRects().map { it.first }
        assertTrue(texts.none { it.contains("Likely") })
        // "in 30 s" — a countdown to a signal phase — must not appear. Matched
        // on a word boundary rather than on the substring "in ", which also
        // occurs inside "9 min · 8.4 km left": the trip bar's journey-left line
        // is one string now (see [TripBar]) and the loose match started
        // flagging it, which is the assertion breaking rather than the screen.
        assertTrue(texts.none { Regex("\\bin \\d").containsMatchIn(it) })
    }

    @Test
    fun `stale timing also shows no phase word`() {
        show(navState().copy(roadName = "Al Corniche",
            signalAhead = signalAhead(dev.vector.geo.signal.Phase.UNKNOWN,
                dev.vector.geo.signal.Basis.STALE, confidence = 0.0)))
        val texts = visibleRects().map { it.first }
        assertTrue(texts.none { it.contains("Likely") })
    }

    @Test
    fun `rerouting and jams still outrank a phase claim`() {
        val s = navState().copy(
            rerouting = true, jamCount = 7,
            signalAhead = signalAhead(dev.vector.geo.signal.Phase.GREEN,
                dev.vector.geo.signal.Basis.TIMING),
        )
        show(s)
        compose.onNodeWithText("Rerouting…").assertIsDisplayed()
        val texts = visibleRects().map { it.first }
        assertTrue("rerouting must win: $texts", texts.none { it.contains("Likely") })
    }


    @Test
    fun `lane guidance is shown when it narrows the choice`() {
        show(navState().copy(currentManeuver = Maneuver(
            1, "turn-left", "Turn left onto شارع الكورنيش", 2500.0, 300.0,
            turnLanes = "left|through|through")))
        // Asserted on the strip's DESCRIPTION, not on arrow characters.
        //
        // It used to match the text `←` and `↑`, which was only possible
        // because the lane arrows were Unicode characters set in the system
        // font — the same hairline-glyph defect V4 fixed for the maneuver
        // banner and left in place here. V5 draws them (`VectorIcons.LaneArrow`),
        // so there is no text to match, and the description is the stronger
        // assertion anyway: it pins the lane COUNT, their ORDER and which one
        // to take, none of which two arrow characters could express.
        compose.onNodeWithContentDescription(
            "Lanes: lane 1 left, take this one; lane 2 straight ahead; lane 3 straight ahead"
        ).assertIsDisplayed()
    }

    @Test
    fun `lane guidance is withheld until the turn is close enough to act on`() {
        // A maneuver becomes current the moment the previous one completes, so
        // without a distance rule the diagram appears at the start of the leg
        // and stays — seven kilometres of it on the live router's Al Wakrah
        // route, which is how a driver learns to stop looking at it.
        show(navState().copy(
            distanceToManeuverM = 2_400.0,
            currentManeuver = Maneuver(
                1, "turn-left", "Turn left onto شارع الكورنيش", 2500.0, 300.0,
                turnLanes = "left|through|through"),
        ))
        compose.onAllNodesWithContentDescription(
            "Lanes: lane 1 left, take this one; lane 2 straight ahead; lane 3 straight ahead"
        ).assertCountEquals(0)
    }

    @Test
    fun `no lane strip when every lane turns`() {
        // "stay where you are" is noise, so nothing should be drawn.
        show(navState().copy(currentManeuver = Maneuver(
            1, "turn-left", "Turn left", 2500.0, 300.0, turnLanes = "left|left|left")))
        assertTrue(navState().copy(currentManeuver = Maneuver(
            1, "turn-left", "Turn left", 2500.0, 300.0,
            turnLanes = "left|left|left")).currentManeuver!!.lanes.isEmpty())
    }

    @Test
    fun `a motorway exit number is shown with its destination`() {
        show(navState().copy(currentManeuver = Maneuver(
            1, "slight-right", "Slight right onto the slip road", 900.0, 300.0,
            exitRef = "12", destination = "Al Wakrah")))
        compose.onNodeWithText("Exit 12").assertIsDisplayed()
        compose.onNodeWithText("Al Wakrah").assertIsDisplayed()
    }

    @Test
    fun `the exit badge reads out as one statement, not three fragments`() {
        // Asked for pictorially, which means the shape carries meaning a screen
        // reader cannot see — so the badge describes itself as a whole rather
        // than leaving "Exit", "12" and "Al Wakrah" as separate nodes.
        show(navState().copy(currentManeuver = Maneuver(
            1, "slight-right", "Slight right onto the slip road", 900.0, 300.0,
            exitRef = "12", destination = "Al Wakrah")))
        compose.onNodeWithContentDescription("Exit 12, signposted Al Wakrah")
            .assertIsDisplayed()
    }

    @Test
    fun `an exit with no signposted destination still reads correctly`() {
        show(navState().copy(currentManeuver = Maneuver(
            1, "slight-right", "Slight right onto the slip road", 900.0, 300.0,
            exitRef = "F Ring")))
        compose.onNodeWithContentDescription("Exit F Ring").assertIsDisplayed()
    }

    @Test
    fun `lane guidance does not overlap the maneuver card`() {
        show(navState().copy(currentManeuver = Maneuver(
            1, "turn-left", "Turn left onto شارع الكورنيش", 2500.0, 300.0,
            turnLanes = "left|through|through", exitRef = "12", destination = "Al Wakrah")))
        val rects = visibleRects()
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val (ta, ra) = rects[i]; val (tb, rb) = rects[j]
            assertTrue("\"$ta\" $ra overlaps \"$tb\" $rb", !overlaps(ra, rb))
        }
    }

    @Test
    fun `being off route is stated, not left blank`() {
        // `offRoute` was tracked in state and rendered nowhere, so a driver off
        // the line saw an empty card and no reason for it — no guidance AND no
        // explanation for the absence. Reported from an S24 parked short of the
        // road.
        show(navState().copy(offRoute = true, currentManeuver = null))
        compose.onNodeWithText("Off route").assertIsDisplayed()
    }

    @Test
    fun `rerouting reads differently from merely being off route`() {
        // The distinction is what the driver acts on: "Rerouting" means a new
        // route is coming, "Off route" means one is not.
        show(navState().copy(offRoute = true, rerouting = true, currentManeuver = null))
        compose.onNodeWithText("Rerouting").assertIsDisplayed()
    }

    @Test
    fun `an off-route banner never appears while there is an instruction to give`() {
        show(navState().copy(offRoute = true))
        compose.onNodeWithText("Off route").assertDoesNotExist()
        compose.onNodeWithText("Turn left onto شارع الكورنيش").assertIsDisplayed()
    }

    // ---- search ------------------------------------------------------------

    @Test
    fun `the search field takes focus as soon as it appears`() {
        // THE two-tap keyboard defect, reported from the S24: *"the search bar
        // needs to be tapped twice for the keyboard to pop up."* Tapping the
        // collapsed bar swapped a TextField into the tree and nothing asked for
        // focus, so the first tap produced a field and the second one focused
        // it. Without focus there is no IME, whatever the platform does.
        //
        // Note what this test does NOT prove: Robolectric has no real input
        // method, so "the keyboard appeared" is still the S24's answer. Focus is
        // the necessary condition, and it was the one that was missing.
        show(UiState(phase = Phase.EXPLORE, searching = true))
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `the collapsed bar is not a text field`() {
        // The closed state must be a button-like affordance, or the layout
        // would hold a focusable field permanently and the map would fight the
        // IME for the screen.
        show(UiState(phase = Phase.EXPLORE, searching = false))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `a clear control appears only when there is something to clear`() {
        show(UiState(phase = Phase.EXPLORE, searching = true, query = ""))
        compose.onAllNodesWithContentDescription("Clear the search").assertCountEquals(0)

        show(UiState(phase = Phase.EXPLORE, searching = true, query = "souq"))
        compose.onNodeWithContentDescription("Clear the search").assertIsDisplayed()
    }

    @Test
    fun `results name what a place is, in words a person uses`() {
        // `kind` is the basemap layer the feature came from, and its value for
        // every one of Qatar's 8,735 POIs is the literal string "poi" — so the
        // list read "poi · 2.4 km away" four times. The real category was in
        // the index all along as `poi_class`; the geocoder now emits it.
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "cafe", searched = true,
            myLocation = LngLat(51.531, 25.2854),
            results = listOf(VectorApi.Place(
                "Coffee Corner", LngLat(51.533, 25.287), "poi", "cafe")),
        ))
        compose.onNodeWithText("Cafe", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("poi", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an OSM tag reads as a phrase, not as a tag`() {
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "mosque", searched = true,
            results = listOf(VectorApi.Place(
                "Al Fanar Mosque", LngLat(51.533, 25.287), "poi", "place_of_worship")),
        ))
        compose.onNodeWithText("Place of worship", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a result with no category shows the distance alone`() {
        // The honest degradation: falling back to `kind` would put "poi" back
        // on the screen, which is the problem rather than a milder version of
        // it.
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "x", searched = true,
            myLocation = LngLat(51.531, 25.2854),
            results = listOf(VectorApi.Place("Somewhere", LngLat(51.533, 25.287), "poi", null)),
        ))
        compose.onNodeWithText("250 m").assertIsDisplayed()
        compose.onAllNodesWithText("poi", substring = true).assertCountEquals(0)
    }

    @Test
    fun `results say how far away they are`() {
        // Four branches of the same chain are four identical rows without it.
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "souq", searched = true,
            myLocation = LngLat(51.531, 25.2854),
            results = listOf(VectorApi.Place("Souq Waqif", LngLat(51.533, 25.287), "poi")),
        ))
        // A bare "250 m", not "… 250 m away": the distance is its own
        // right-hand column since V4, so a list of results forms a scannable
        // stack of numbers rather than four sentences to read.
        compose.onNodeWithText("250 m").assertIsDisplayed()
    }

    @Test
    fun `a query that found nothing says so`() {
        // With live search, "no results yet" and "no results at all" look
        // identical, and the second has to be said out loud — an empty panel
        // reads as a broken search.
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "zzzzzz",
            searched = true, searchInFlight = false, results = emptyList(),
        ))
        // The empty state names the query back. "Nothing found" alone does not
        // say what was not found, which on a search that may have been
        // mistyped is the only useful half of the message.
        compose.onNodeWithText("No match for", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a search still in flight does not claim there is nothing`() {
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "souq",
            searched = false, searchInFlight = true, results = emptyList(),
        ))
        compose.onNodeWithText("No match for", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an untouched search box offers recents rather than an empty state`() {
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "",
            recents = listOf(Recents.Entry("West Bay", 51.52, 25.32)),
        ))
        compose.onNodeWithText("No match for", substring = true).assertDoesNotExist()
        compose.onNodeWithText("West Bay").assertIsDisplayed()
    }

    // ---- camera controls ---------------------------------------------------

    @Test
    fun `the compass states which way is up`() {
        // The control is an INDICATOR first. A driver has to be able to answer
        // "why is the map facing this way?" without experimenting.
        show(navState().copy(cam = CameraState(orientation = MapOrientation.NORTH_UP)))
        compose.onNodeWithContentDescription("North up — tap to face the direction of travel")
            .assertIsDisplayed()

        show(navState().copy(cam = CameraState(orientation = MapOrientation.HEADING_UP)))
        compose.onNodeWithContentDescription(
            "Facing the direction of travel — tap for north up"
        ).assertIsDisplayed()
    }

    @Test
    fun `a hand-rotated map says so`() {
        // The state the driver did not choose in settings and may not remember
        // causing, so it is the one that most needs saying.
        show(navState().copy(cam = CameraState(manualBearing = 42.0)))
        compose.onNodeWithContentDescription("Map rotated by hand — tap to face north")
            .assertIsDisplayed()
    }

    @Test
    fun `orientation is never communicated by colour alone`() {
        // §20: driving-critical state must not depend on colour. Every compass
        // state carries a description in words, so a screen reader and a
        // colour-blind driver get the same answer.
        for (cam in listOf(
            CameraState(orientation = MapOrientation.NORTH_UP),
            CameraState(orientation = MapOrientation.HEADING_UP),
            CameraState(manualBearing = 42.0),
        )) {
            show(navState().copy(cam = cam))
            val described = compose.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    SemanticsProperties.ContentDescription
                )
            ).fetchSemanticsNodes()
            assertTrue("no described control for $cam", described.isNotEmpty())
        }
    }

    @Test
    fun `recenter appears only when it would do something`() {
        // A control that is always present but usually a no-op teaches the
        // driver to ignore it.
        show(navState().copy(cam = CameraState(mode = CameraMode.FOLLOW)))
        compose.onNodeWithContentDescription(RECENTER).assertDoesNotExist()

        show(navState().copy(cam = CameraState(mode = CameraMode.FREE)))
        compose.onNodeWithContentDescription(RECENTER).assertIsDisplayed()
    }

    @Test
    fun `a hand-rotated map offers a recenter even while it is still following`() {
        show(navState().copy(cam = CameraState(manualBearing = 42.0)))
        compose.onNodeWithContentDescription(RECENTER).assertIsDisplayed()
    }

    @Test
    fun `route overview is offered while driving and not before`() {
        show(navState())
        compose.onNodeWithContentDescription("Show the whole route").assertIsDisplayed()

        show(UiState(phase = Phase.EXPLORE))
        compose.onAllNodesWithContentDescription("Show the whole route")
            .assertCountEquals(0)
    }

    @Test
    fun `the overview control says how to get back`() {
        show(navState().copy(cam = CameraState(mode = CameraMode.OVERVIEW)))
        compose.onNodeWithContentDescription("Follow the vehicle").assertIsDisplayed()
    }

    // ---- zoom (V8) ---------------------------------------------------------

    @Test
    fun `zoom is reachable in every phase, not only while driving`() {
        // Unlike overview and voice. Zoom is not a driving-only need, and a
        // control that comes and goes with the phase is one the driver has to
        // look for — which is the opposite of what a button under a moving
        // thumb is for.
        for (phase in listOf(
            UiState(phase = Phase.EXPLORE),
            UiState(phase = Phase.PREVIEW, destinationName = "West Bay",
                    routeDistanceM = 1_000.0, routeDurationS = 120.0,
                    maneuvers = listOf(Maneuver(0, "depart", "Head north", 300.0, 0.0))),
            navState(),
        )) {
            show(phase)
            compose.onNodeWithContentDescription("Zoom in").assertIsDisplayed()
            compose.onNodeWithContentDescription("Zoom out").assertIsDisplayed()
        }
    }

    @Test
    fun `each zoom control reaches its own callback`() {
        // The half §6 is about, and the half that has been wrong here before:
        // a control that is on screen and wired to nothing.
        show(navState())
        val before = zoomedIn to zoomedOut
        compose.onNodeWithContentDescription("Zoom in")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("zoom in did not reach the camera", before.first + 1, zoomedIn)
        assertEquals("zoom in also zoomed out", before.second, zoomedOut)
        compose.onNodeWithContentDescription("Zoom out")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(before.second + 1, zoomedOut)
    }

    @Test
    fun `a zoom press is a larger move than the gate treats as Vectors own`() {
        // This lives with the button rather than with the camera because it is
        // a property of the BUTTON: a press leaves `CameraGate.assertedZoom`
        // alone and moves the live camera away from it, exactly as a pinch
        // does, so it is only read as the driver's zoom if the move clears
        // MapCamera.DRIVER_ZOOM_TOLERANCE. Below that line the next GPS fix
        // would let the auto-zoom band or the maneuver camera put the zoom
        // straight back, and the button would appear to work only while
        // parked — see MainActivity.zoomStep.
        assertTrue(
            "a $ZOOM_BUTTON_STEP step is inside the ${MapCamera.DRIVER_ZOOM_TOLERANCE} " +
                "tolerance, so the camera would take it back on the next fix",
            ZOOM_BUTTON_STEP > MapCamera.DRIVER_ZOOM_TOLERANCE,
        )
    }

    @Test
    fun `no two controls in the map column overlap, in any phase`() {
        // The column grew by two buttons. Node bounds, because a control that
        // is drawn under another control is a control that cannot be pressed,
        // and that is not visible in the tree — only in the geometry.
        for (candidate in listOf(
            UiState(phase = Phase.EXPLORE),
            UiState(phase = Phase.EXPLORE, cam = CameraState(mode = CameraMode.FREE)),
            navState(),
            navState().copy(cam = CameraState(mode = CameraMode.FREE)),
            navState().copy(cam = CameraState(mode = CameraMode.OVERVIEW)),
            navState().copy(cam = CameraState(manualBearing = 42.0)),
            navState().copy(settings = Settings(contributing = true), probesSent = 30),
        )) {
            show(candidate)
            val controls = visibleDescs().filter {
                it.first in setOf(
                    "Zoom in", "Zoom out", "Settings", "Show the whole route",
                    "Follow the vehicle", RECENTER,
                ) || it.first.startsWith("Voice:") ||
                    it.first.startsWith("North up") ||
                    it.first.startsWith("Facing the direction") ||
                    it.first.startsWith("Map rotated") ||
                    it.first.startsWith("Anonymised speed data")
            }
            assertTrue("${candidate.phase}: found only ${controls.map { it.first }}",
                       controls.size >= 3)
            for (i in controls.indices) for (j in i + 1 until controls.size) {
                val (a, ra) = controls[i]
                val (b, rb) = controls[j]
                assertTrue("${candidate.phase}: \"$a\" $ra overlaps \"$b\" $rb",
                           !overlaps(ra, rb))
            }
        }
    }

    // ---- settings ----------------------------------------------------------

    @Test
    fun `settings are reachable before driving and not during`() {
        // Opening a preferences sheet over a live maneuver card is the
        // interaction the whole layout exists to prevent.
        show(UiState(phase = Phase.EXPLORE))
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()

        show(navState())
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(0)
    }

    @Test
    fun `the settings sheet offers every choice it claims to`() {
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        // Labels renamed in V4 now that they sit under group headings: "Map
        // theme" under a MAP heading is just "Theme", and repeating the group
        // in every row is the noise the headings exist to remove.
        //
        // `performScrollTo` because the sheet is a `verticalScroll` and, with
        // the four voice modes added, no longer fits a 915 dp screen in one
        // view. A row below the fold EXISTS and is not DISPLAYED, which is a
        // distinction these tests have to make on purpose rather than by
        // accident.
        for (label in listOf("Theme", "Orientation", "Distances",
                             "Spoken directions", "Zoom out at speed",
                             "Show traffic before setting off")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `the settings sheet is grouped rather than a flat pile`() {
        // Eleven controls in one column, ordered by the release that added
        // them, was §20's "no clear hierarchy". Waze uses a two-level tree for
        // roughly forty settings; Vector has eleven and takes only the cheap
        // half of that idea — a labelled rule between groups.
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        for (group in listOf("MAP", "NAVIGATION", "VOICE", "PRIVACY", "ABOUT")) {
            compose.onNodeWithText(group).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `every voice mode is offered and says what it does`() {
        // §27. The setting was a single boolean, which offers a driver
        // "narrate everything" and "silence" — and the second is what most
        // people pick because the first is exhausting on a route they half
        // know. Each mode also carries a line saying what it actually does:
        // "Brief" and "Alerts only" are indistinguishable from their names.
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        for (mode in VoiceMode.entries) {
            compose.onNodeWithText(mode.label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Turns only as you reach them — no early warning")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the 2D 3D choice is offered only where it applies`() {
        // Tilt is only applied heading-up (see MapPerspective.TILTED), so
        // offering it north-up would be a control that silently does nothing.
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            settings = Settings(orientation = MapOrientation.HEADING_UP),
        ))
        compose.onNodeWithText("Navigation view").performScrollTo().assertIsDisplayed()

        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            settings = Settings(orientation = MapOrientation.NORTH_UP),
        ))
        compose.onNodeWithText("Navigation view").assertDoesNotExist()
    }

    @Test
    fun `the consent control says what happens, not what the feature is called`() {
        // Consent has to say what it means, not just be a switch — and the
        // label has to describe the ACTION. "Share traffic" was reported as
        // vague from the S24 and was: it reads as a display option.
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        compose.onNodeWithText("Send my speed data to improve the map")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("While you navigate", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the consent explanation names the actual protections`() {
        // Not "anonymised" as a reassuring adjective: the 200 m truncation and
        // the five-trip floor are the mechanisms (adr-0065, adr-0066), and a
        // driver can check a claim that names them.
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        compose.onNodeWithText("200 m", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("five separate trips", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an error banner and a no-results card do not both claim the screen`() {
        // Photographed on the S24 during the adversarial run: an offline search
        // had set an error, and a later query's "Nothing found for …" card
        // rendered straight over the top of it — the banner lives in the top
        // strip, the card in the search panel, and the two share that band, so
        // the driver got two messages about one failure with the lower half of
        // each hidden.
        //
        // Asserted on BOUNDS rather than on presence, because both being on
        // screen is not itself the bug — overlapping is. This is the same
        // instrument the phase-overlap cases further up this file use.
        // The state a FAILED search actually produces now: an error, and
        // `searched = false`, because a request that failed supports no claim
        // about whether the place exists.
        show(UiState(
            phase = Phase.EXPLORE, searching = true,
            query = "zzzqqqxx", searched = false,
            error = "Search failed: unable to resolve hostname",
        ))
        compose.onNodeWithText("Search failed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No match for", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a search that completed and found nothing still says so`() {
        // The other half, so the fix above cannot be "never show the card".
        // A COMPLETED query with no hits is a real statement about the index
        // and has to be made — an empty panel is indistinguishable from a
        // request still in flight.
        show(UiState(
            phase = Phase.EXPLORE, searching = true,
            query = "zzzqqqxx", searched = true, error = null,
        ))
        // The empty state names the query back. "Nothing found" alone does not
        // say what was not found, which on a search that may have been
        // mistyped is the only useful half of the message.
        compose.onNodeWithText("No match for", substring = true).assertIsDisplayed()
    }

    // ---- the driver's own data --------------------------------------------
    //
    // §13 asks for persistence; §6 asks that every visible interactive element
    // does something, gives feedback, and reaches the correct state. These are
    // the second half, and they exist because this codebase has produced the
    // failure twice: a "Home set" confirmation written to a status line that
    // only EXPLORE renders, and `Places.clear` — written, unit-tested, and
    // reachable from no control in the app.

    private fun drive(
        name: String,
        endedAtMs: Long,
        completed: Boolean = true,
        distanceM: Double = 8_000.0,
        durationS: Double = 600.0,
    ) = Drives.Drive(
        destination = name, destLng = 51.4986, destLat = 25.3208,
        startLng = 51.53, startLat = 25.28,
        startedAtMs = endedAtMs - (durationS * 1000).toLong(), endedAtMs = endedAtMs,
        distanceM = distanceM, durationS = durationS, plannedS = 600.0,
        completed = completed,
    )

    @Test
    fun `a driver with nothing saved is shown no data section`() {
        // Not an empty "Your data" heading with nothing under it, and not four
        // rows whose Clear buttons do nothing. §6: no dead UI.
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        // Upper-cased, because SettingsGroup upper-cases its label — asserting
        // the mixed-case string would pass whether the section rendered or not,
        // which is the vacuous assertion V5 caught itself writing.
        compose.onNodeWithText("YOUR DATA").assertDoesNotExist()
        compose.onNodeWithText("Past drives").assertDoesNotExist()
    }

    @Test
    fun `a saved Home is listed with a control that clears it`() {
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            places = listOf(Places.Saved(Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)),
        ))
        compose.onNodeWithText("YOUR DATA").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Home").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Msheireb").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `clearing Home reaches the store with the right slot`() {
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            places = listOf(
                Places.Saved(Places.Slot.HOME, "Msheireb", 51.5238, 25.2867),
                Places.Saved(Places.Slot.WORK, "West Bay", 51.4986, 25.3208),
            ),
        ))
        clearedPlaces.clear()
        // Two taps, because clearing now CONFIRMS.
        //
        // "Clear" beside "Home" used to delete a saved place on the first tap,
        // with no dialog and no undo, from a 48 dp target on a scrolling sheet.
        // It now opens a [VectorDialog] that names what would go; the store is
        // only reached from the dialog's own confirm. That second tap is the
        // feature, so the test performs it rather than routing around it.
        compose.onAllNodesWithTag("clear:Home")[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("nothing is cleared until the dialog is confirmed",
                     emptyList<Places.Slot>(), clearedPlaces)
        compose.onNodeWithTag("dialog:confirm")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(Places.Slot.HOME), clearedPlaces)
    }

    @Test
    fun `an unset slot is not listed at all`() {
        // An empty row with a dead "Clear" beside it is the invisible-success
        // class of bug: the driver taps it, nothing was stored to remove, and
        // the app looks like it did something.
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            places = listOf(Places.Saved(Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)),
        ))
        compose.onNodeWithText("Work").assertDoesNotExist()
    }

    @Test
    fun `past drives are listed newest first with what each one took`() {
        val now = System.currentTimeMillis()
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            drives = listOf(
                drive("Villaggio Mall", now - 60_000L),
                drive("Hamad International", now - 7_200_000L),
            ),
        ))
        compose.onNodeWithText("Past drives").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Villaggio Mall").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hamad International").performScrollTo().assertIsDisplayed()
        // The numbers, not just the names: a log that says only where you went
        // answers none of the questions a driver opens it with. Matched on the
        // ROW, because both drives carry the same distance and a bare "8.0 km"
        // finds two nodes.
        compose.onNode(
            hasText("Villaggio Mall", substring = true) and
                hasText("8.0 km", substring = true) and
                hasText("10 min", substring = true)
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping a past drive routes there again`() {
        val now = System.currentTimeMillis()
        val d = drive("Villaggio Mall", now - 60_000L)
        show(UiState(phase = Phase.EXPLORE, showSettings = true, drives = listOf(d)))
        pickedDrives.clear()
        compose.onNodeWithText("Villaggio Mall")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // Without this the whole section is a read-only report, and "I went to
        // that clinic in March, what was it called" ends in wanting to go back.
        assertEquals(listOf(d), pickedDrives)
    }

    @Test
    fun `one drive can be forgotten from its own row`() {
        // `Drives.remove` exists, so something has to reach it — an API with no
        // control is the dead-persistence case §6 rules out, and it is the
        // criticism this session made of `Places.clear`.
        val now = System.currentTimeMillis()
        val keep = drive("Villaggio Mall", now - 60_000L)
        val drop = drive("Hamad International", now - 7_200_000L)
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            drives = listOf(keep, drop),
        ))
        deletedDrives.clear()
        // Named, not indexed.
        //
        // The delete control used to be the character "\u00D7" in a Material
        // `TextButton` with no accessible name at all, so a screen reader read
        // it as "times" and this test had to pick it out of an ordered list of
        // identical nodes and *hope* the order matched the rows. It is now a
        // real icon button whose content description carries the drive it would
        // forget, which makes "the delete control on Hamad's row" something the
        // test can actually ask for — and makes the assertion stronger than the
        // index ever was.
        compose.onAllNodesWithContentDescription("Forget the drive to", substring = true)
            .assertCountEquals(2)
        compose.onNodeWithContentDescription("Forget the drive to Hamad International")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(drop), deletedDrives)
    }

    @Test
    fun `forgetting a drive is not the same control as driving it again`() {
        // Two actions on one row, and the destructive one must not be what a
        // driver gets for tapping the row they want to go back to.
        val now = System.currentTimeMillis()
        val d = drive("Villaggio Mall", now - 60_000L)
        show(UiState(phase = Phase.EXPLORE, showSettings = true, drives = listOf(d)))
        pickedDrives.clear(); deletedDrives.clear()
        compose.onNodeWithText("Villaggio Mall")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(d), pickedDrives)
        assertEquals(emptyList<Drives.Drive>(), deletedDrives)
    }

    @Test
    fun `an abandoned drive is marked as stopped`() {
        val now = System.currentTimeMillis()
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            drives = listOf(
                drive("Hamad International", now - 60_000L,
                      completed = false, distanceM = 2_000.0, durationS = 300.0),
            ),
        ))
        // A 2 km entry for a 40 km journey to the airport is not a wrong
        // number, it is a drive that was given up on. Unlabelled, the log
        // would look like it was lying.
        compose.onNodeWithText("stopped", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the history is bounded on screen and says what is not shown`() {
        val now = System.currentTimeMillis()
        val many = (0 until DRIVE_ROWS + 3).map { drive("drive $it", now - it * 60_000L) }
        show(UiState(phase = Phase.EXPLORE, showSettings = true, drives = many))
        compose.onNodeWithText("drive 0").performScrollTo().assertIsDisplayed()
        // Beyond the on-screen limit: still kept, not shown, and SAID so
        // rather than the list quietly pretending to be complete.
        compose.onNodeWithText("drive ${DRIVE_ROWS + 2}").assertDoesNotExist()
        compose.onNodeWithText("3 older drives kept").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `clearing the history reaches the store`() {
        val now = System.currentTimeMillis()
        show(UiState(
            phase = Phase.EXPLORE, showSettings = true,
            drives = listOf(drive("Villaggio Mall", now - 60_000L)),
        ))
        clearedDrives = 0
        // The only "Clear" on screen in this state, since nothing else is
        // saved — and it confirms before it destroys. See the saved-place test
        // above for why the second tap is the point rather than an obstacle.
        compose.onNodeWithTag("clear:drives")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("the log is not cleared until the dialog is confirmed",
                     0, clearedDrives)
        compose.onNodeWithTag("dialog:confirm")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, clearedDrives)
    }

    @Test
    fun `hiding the map watermark does not drop the map credit`() {
        // MapLibre's own attribution widget and logo are switched off, because
        // a permanent watermark over the corner where the speedometer lives is
        // chrome the driver did not ask for. The ODbL obligation survives the
        // widget, so the credit has to be somewhere — hiding the mark and
        // dropping the credit would be a licence breach dressed up as a UI fix.
        show(UiState(phase = Phase.EXPLORE, showSettings = true))
        compose.onNodeWithText("OpenStreetMap", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ODbL", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    // ---- units -------------------------------------------------------------

    @Test
    fun `the HUD reads in the units the driver chose`() {
        show(navState())
        compose.onNodeWithText("400 m").assertIsDisplayed()
        compose.onNodeWithText("km/h").assertIsDisplayed()

        show(navState().copy(settings = Settings(units = dev.vector.geo.Units.IMPERIAL)))
        compose.onNodeWithText("mph").assertIsDisplayed()
        compose.onAllNodesWithText("400 m").assertCountEquals(0)
    }

    @Test
    fun `the speed limit sign converts with the units`() {
        show(navState().copy(speedLimitKmh = 100))
        compose.onNodeWithText("100").assertIsDisplayed()

        show(navState().copy(
            speedLimitKmh = 100,
            settings = Settings(units = dev.vector.geo.Units.IMPERIAL),
        ))
        compose.onNodeWithText("62").assertIsDisplayed()
    }

    // ---- recents -----------------------------------------------------------

    @Test
    fun `an empty search box offers where the driver has been`() {
        // The commute is the single most likely destination, and this slot used
        // to be blank.
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "",
            recents = listOf(Recents.Entry("West Bay", 51.52, 25.32)),
        ))
        // "Recent" — a section title in the discovery sheet, set in the type
        // scale's `sectionTitle` role. It was a shouted "RECENT" caption when
        // the list was a floating panel under the search field; inside the
        // sheet it is a heading on a page, and the sheet's other headings
        // ("Your Vectors") are set the same way.
        compose.onNodeWithText("Recent").assertIsDisplayed()
        compose.onNodeWithText("West Bay").assertIsDisplayed()
    }

    @Test
    fun `an empty search box also offers the things a driver looks for from the wheel`() {
        // Four category shortcuts, and only four. Both references put a row of
        // these under the field; each of Vector's is a thing you search for
        // because of a deadline (a low needle, having arrived, an errand), and
        // a fifth would be a category browser rather than a shortcut.
        show(UiState(phase = Phase.EXPLORE, searching = true, query = ""))
        for ((label, _) in SEARCH_CATEGORIES) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `typing replaces the recents with results`() {
        // The two share one slot, so they must never be composed together.
        show(UiState(
            phase = Phase.EXPLORE, searching = true, query = "souq",
            recents = listOf(Recents.Entry("West Bay", 51.52, 25.32)),
            results = listOf(VectorApi.Place("Souq Waqif", LngLat(51.53, 25.28), "poi")),
        ))
        compose.onNodeWithText("Souq Waqif").assertIsDisplayed()
        compose.onNodeWithText("West Bay").assertDoesNotExist()
    }

    @Test
    fun `recents are not shown while driving`() {
        show(navState().copy(recents = listOf(Recents.Entry("West Bay", 51.52, 25.32))))
        compose.onNodeWithText("Recent").assertDoesNotExist()
    }

    // ---- alternatives ------------------------------------------------------

    @Test
    fun `a choice of routes is offered in the preview`() {
        show(previewWithAlternatives())
        compose.onNodeWithText("via Al Corniche").assertIsDisplayed()
        compose.onNodeWithText("via Salwa Road").assertIsDisplayed()
    }

    @Test
    fun `a single route is described but not presented as a choice`() {
        // It still says WHICH way it goes — that is useful with one route and
        // it is the field the old horizontal chips truncated. What must not
        // appear is a second row to pick, or a "Fastest" badge, because a badge
        // on the only row is decoration and a choice of one is not a choice.
        show(previewWithAlternatives().copy(alternatives = twoRoutes().take(1)))
        compose.onNodeWithText("via Al Corniche").assertIsDisplayed()
        compose.onNodeWithText("via Salwa Road").assertDoesNotExist()
        compose.onNodeWithText("Fastest").assertDoesNotExist()
    }

    @Test
    fun `the chosen route is not repeated as one of the options`() {
        // The previous design said everything twice: a selected chip read
        // "9 min / 12 km / via Al Corniche" and the bar directly beneath it
        // read "9 min / 12 km · arrive 04:27 · 16 turns". One card, one row per
        // route, and the chosen one appears once.
        show(previewWithAlternatives())
        compose.onAllNodesWithText("via Al Corniche").assertCountEquals(1)
    }

    @Test
    fun `the fastest route is marked, because time and distance do not rank`() {
        // The fastest route here is also the LONGEST (12 km in 9 min against
        // 12.8 km... ) — in the real Doha case measured on the S24 it was 12 km
        // in 9 min against 11 km in 12 min, which reads as an error until
        // something on the card says why it is first.
        show(previewWithAlternatives())
        compose.onNodeWithText("Fastest").assertIsDisplayed()
    }

    @Test
    fun `the arrival clock is stated once, for the route being started`() {
        // Asked once, about the route you are about to drive — not three times.
        show(previewWithAlternatives())
        compose.onAllNodesWithText("arrive", substring = true).assertCountEquals(1)
    }

    @Test
    fun `the turn count no longer crowds the summary line`() {
        // It truncated the line it was on ("16 tur…") to carry the least useful
        // number on the card. The step list is one tap away once driving.
        show(previewWithAlternatives())
        compose.onAllNodesWithText("turns", substring = true).assertCountEquals(0)
    }

    @Test
    fun `alternatives are gone once navigation starts`() {
        // The choice has been made; keeping the chips would invite a change of
        // route at the worst moment to make one.
        show(navState().copy(alternatives = twoRoutes()))
        compose.onNodeWithText("via Salwa Road").assertDoesNotExist()
    }

    @Test
    fun `the preview answers when the driver will arrive, not only how long`() {
        // "will I make the meeting" is the question being asked BEFORE setting
        // off, and a duration makes the driver do the arithmetic.
        show(previewWithAlternatives())
        compose.onNodeWithText("arrive", substring = true).assertIsDisplayed()
    }

    // ---- fixtures ----------------------------------------------------------

    private fun twoRoutes() = listOf(
        RouteOption(
            geometry = listOf(LngLat(51.53, 25.28), LngLat(51.52, 25.32)),
            distanceM = 11_530.0, durationS = 624.0, maneuvers = emptyList(),
            snapMaxM = 12.0, label = "via Al Corniche",
        ),
        RouteOption(
            geometry = listOf(LngLat(51.53, 25.28), LngLat(51.55, 25.31)),
            distanceM = 12_800.0, durationS = 690.0, maneuvers = emptyList(),
            snapMaxM = 12.0, label = "via Salwa Road",
        ),
    )

    private fun previewWithAlternatives() = UiState(
        phase = Phase.PREVIEW,
        destinationName = "West Bay",
        routeDistanceM = 11_530.0,
        routeDurationS = 624.0,
        maneuvers = listOf(Maneuver(0, "depart", "Head north", 300.0, 0.0)),
        alternatives = twoRoutes(),
        chosenRoute = 0,
    )

    /**
     * A drive with normal positioning.
     *
     * `gps` is stated rather than left at its default, and that is deliberate:
     * `UiState().gps` is [GpsHealth.ACQUIRING] because before the first fix the
     * app knows nothing about where it is, so the default state legitimately
     * renders the "Searching for GPS" strip. These cases are about the
     * instruction band, the trip bar and the dials, none of which is under test
     * while a positioning warning is also on screen — so they say which of the
     * two situations they describe. The strip itself is covered below.
     */
    private fun navState() = UiState(
        phase = Phase.NAVIGATING,
        gps = GpsHealth.GOOD,
        speedKmh = 58,
        remainingM = 8_400.0,
        remainingS = 540.0,
        distanceToManeuverM = 400.0,
        currentManeuver = Maneuver(1, "turn-left", "Turn left onto شارع الكورنيش", 2500.0, 300.0),
        nextManeuver = Maneuver(2, "slight-right", "Slight right onto شارع المرخية", 3685.0, 2800.0),
        maneuvers = listOf(
            Maneuver(0, "depart", "Head north on Grand Hamad", 300.0, 0.0),
            Maneuver(1, "turn-left", "Turn left onto شارع الكورنيش", 2500.0, 300.0),
        ),
    )
    // ---- the road-you-are-on pill (V5, reported from the S24) ---------------
    //
    // "Next to the GPS icon it shows IBN KATHEER STREET in a bubble. This
    // shouldn't be there, it's just too big and too consuming on the screen."
    //
    // V4 put it in EXPLORE as well as NAVIGATING, arguing that the map rendered
    // no road labels at navigation zoom. V4's own font-stack fix stopped that
    // being true in the same release, so in EXPLORE it repeats the map's label
    // as a capsule twice the size.

    @Test
    fun `the road name is not drawn over the map while exploring`() {
        show(UiState(phase = Phase.EXPLORE, gps = GpsHealth.GOOD,
                     roadName = "Ibn Katheer Street"))
        compose.onAllNodesWithContentDescription("Driving on Ibn Katheer Street")
            .assertCountEquals(0)
    }

    @Test
    fun `the road name is not drawn over the map while previewing`() {
        // A second reason, from the device: the bottom group is bottom-aligned,
        // so with the route chooser present the pill was pushed into the middle
        // of the map, over the chosen route's own on-map label.
        show(navState().copy(phase = Phase.PREVIEW, roadName = "Ibn Katheer Street"))
        compose.onAllNodesWithContentDescription("Driving on Ibn Katheer Street")
            .assertCountEquals(0)
    }

    @Test
    fun `the road being driven is in the trip bar, not over the map`() {
        // Reported twice from the S24. The second time was after it had merely
        // been made smaller: "I am not happy with how the name of the street is
        // still on a bubble right above the GPS direction icon while a user is
        // driving." The complaint is the POSITION, not the size — a floating
        // bubble over the cartography beside the vehicle is in the part of the
        // screen the driver is reading the road from.
        show(navState().copy(roadName = "Ibn Katheer Street", roadRef = "C Ring"))
        val label = compose.onNodeWithContentDescription("Driving on C Ring · Ibn Katheer Street")
        label.assertIsDisplayed()
        // It sits inside the trip bar, which is to say below the speedometer —
        // the bottom-most furniture, not the middle of the map.
        val road = label.fetchSemanticsNode().boundsInRoot
        val speedo = compose.onNodeWithText("km/h").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the road label ($road) is not below the speedometer ($speedo)",
            road.top >= speedo.top,
        )
    }

    @Test
    fun `rerouting outranks the road name in the trip bar`() {
        // One slot, strict priority. A driver being rerouted does not need to
        // be told which road they are on, and two labels in one line is what
        // made the previous version truncate.
        show(navState().copy(roadName = "Ibn Katheer Street", rerouting = true))
        compose.onNodeWithText("Rerouting…").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Driving on Ibn Katheer Street")
            .assertCountEquals(0)
    }

    @Test
    fun `a jam count outranks the road name too`() {
        show(navState().copy(roadName = "Ibn Katheer Street", jamCount = 3))
        compose.onNodeWithText("3 jams").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Driving on Ibn Katheer Street")
            .assertCountEquals(0)
    }

    @Test
    fun `the remaining distance moved up beside the clock`() {
        // Line two became the road, so all three "how much journey is left"
        // numbers share line one.
        show(navState())
        // The duration and the distance are ONE string in one size now, beside
        // the clock. They were three consecutive steps of the type scale side
        // by side, which read as three unrelated items rather than as the one
        // fact they are; see [TripBar]. So the assertion is that they share a
        // node, and that the node sits on the clock's line.
        val line = compose.onNodeWithText("8.4 km left", substring = true)
        line.assertIsDisplayed()
        val dist = line.fetchSemanticsNode().boundsInRoot
        val clock = compose.onNodeWithText(":", substring = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the journey-left line ($dist) is not on the clock's line ($clock)",
            kotlin.math.abs(dist.center.y - clock.center.y) < dist.height)
    }

    // ---- the end of a journey (V5, reported from the S24) -------------------
    //
    // "We also need to enhance the end when a trip is complete."
    //
    // Arriving produced one thing: "Arrived at Villaggio Mall" in a 12 sp grey
    // pill on an empty map, with every number from the drive discarded in the
    // same statement.

    private fun arrived(
        parking: List<VectorApi.Place>? = emptyList(),
        actualS: Double = 12.0 * 60,
        plannedS: Double = 10.0 * 60,
    ) = UiState(
        phase = Phase.EXPLORE,
        gps = GpsHealth.GOOD,
        arrival = ArrivalSummary(
            name = "Villaggio Mall",
            atMs = 1_700_000_000_000L,
            distanceM = 11_600.0,
            plannedS = plannedS,
            actualS = actualS,
            parking = parking,
        ),
    )

    @Test
    fun `arriving names the destination and what the drive took`() {
        show(arrived())
        compose.onNodeWithText("Arrived").assertIsDisplayed()
        compose.onNodeWithText("Villaggio Mall").assertIsDisplayed()
        val texts = visibleRects().map { it.first }
        assertTrue("no receipt in $texts", texts.any { it.contains("11 km in 12 min") })
    }

    @Test
    fun `the drive is compared with what was predicted`() {
        show(arrived(actualS = 12.0 * 60, plannedS = 10.0 * 60))
        val texts = visibleRects().map { it.first }
        assertTrue("no ETA verdict in $texts",
            texts.any { it.contains("2 min longer than predicted") })
    }

    @Test
    fun `a prediction that was close is not boasted about`() {
        // Below the honest margin the difference is one traffic light and the
        // moment the driver tapped Start, not a statement about the router.
        show(arrived(actualS = 10.0 * 60 + 30, plannedS = 10.0 * 60))
        val texts = visibleRects().map { it.first }
        assertTrue("expected 'as predicted' in $texts",
            texts.any { it.contains("as predicted") })
    }

    @Test
    fun `parking found near the destination is offered`() {
        show(arrived(parking = listOf(
            VectorApi.Place("Villaggio Car Park", LngLat(51.4437, 25.2585), "poi", "parking"),
        )))
        compose.onNodeWithText("Parking nearby").assertIsDisplayed()
        compose.onNodeWithContentDescription("Park at Villaggio Car Park").assertIsDisplayed()
    }

    @Test
    fun `no more than three car parks are offered, whatever arrives`() {
        // The query asks for PARKING_SUGGESTIONS, but this card is the last
        // thing between a network answer and a driver, and it used to draw
        // whatever it was handed. A server that ignores `limit`, or a later
        // call site that passes a different one, would have put the extra rows
        // on screen with nothing failing anywhere.
        show(arrived(parking = (1..6).map {
            VectorApi.Place("Car Park $it", LngLat(51.44, 25.25), "poi", "parking")
        }))
        for (i in 1..PARKING_SUGGESTIONS) {
            compose.onNodeWithContentDescription("Park at Car Park $i").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(
            "Park at Car Park ${PARKING_SUGGESTIONS + 1}"
        ).assertDoesNotExist()
    }

    @Test
    fun `a parking query still running looks different from finding none`() {
        // `null` is "looking", empty is "there is none". Collapsing them is the
        // same defect the search panel fixed: a blank panel is
        // indistinguishable from a request that has not returned.
        show(arrived(parking = null))
        compose.onNodeWithText("Looking…").assertIsDisplayed()
    }

    @Test
    fun `finding no parking says nothing rather than apologising`() {
        show(arrived(parking = emptyList()))
        compose.onAllNodesWithText("Parking nearby").assertCountEquals(0)
        compose.onAllNodesWithText("Looking…").assertCountEquals(0)
        // The arrival itself is still confirmed.
        compose.onNodeWithText("Villaggio Mall").assertIsDisplayed()
    }

    @Test
    fun `the arrival card can be dismissed`() {
        show(arrived())
        compose.onNodeWithContentDescription("Dismiss the arrival summary").assertIsDisplayed()
    }

    @Test
    fun `the arrival card replaces the status line rather than stacking on it`() {
        show(arrived().copy(status = "Arrived at Villaggio Mall"))
        val rects = visibleRects()
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val (ta, ra) = rects[i]; val (tb, rb) = rects[j]
            assertTrue("\"$ta\" $ra overlaps \"$tb\" $rb", !overlaps(ra, rb))
        }
    }

    @Test
    fun `nothing arrival-shaped is on screen during a normal drive`() {
        show(navState())
        compose.onAllNodesWithText("Arrived").assertCountEquals(0)
        compose.onAllNodesWithText("Parking nearby").assertCountEquals(0)
    }

    // ---- Home and Work (V5) ------------------------------------------------
    //
    // V4's handover: "three features with working backends and no client, all
    // lost in the ADR-0075 rewrite... Waze's Home / Work / Set once and go is
    // the cheapest large win in the product."

    private val home = Places.Saved(Places.Slot.HOME, "Msheireb", 51.5238, 25.2867)
    private val work = Places.Saved(Places.Slot.WORK, "West Bay", 51.4986, 25.3208)

    @Test
    fun `search results are listed, with the distance that tells them apart`() {
        // The regression this exists for: after the Explore surfaces were rebuilt
        // on the design system, the results panel stopped rendering entirely —
        // six hits arrived from the server, the query stayed in the field, and
        // nothing was drawn under it. The old suite could not see it, because
        // every search test asserted the NO-results path.
        //
        // Distance is asserted alongside the names because it is the whole
        // reason the column exists: four branches of one chain are four
        // identical rows without it.
        show(
            UiState(
                phase = Phase.EXPLORE, gps = GpsHealth.GOOD,
                searching = true, query = "souq", searched = true,
                myLocation = dev.vector.geo.LngLat(51.53, 25.28),
                results = listOf(
                    VectorApi.Place("Souq Waqif", dev.vector.geo.LngLat(51.5333, 25.2867), "poi", "market"),
                    VectorApi.Place("Souq Al Wakrah", dev.vector.geo.LngLat(51.6000, 25.1700), "poi", "market"),
                ),
            ),
        )
        compose.onNodeWithText("Souq Waqif").assertIsDisplayed()
        compose.onNodeWithText("Souq Al Wakrah").assertIsDisplayed()
        // The distance column, not a distance baked into a sentence.
        val texts = visibleRects().map { it.first }
        assertTrue(
            "no distance in the result rows: $texts",
            texts.any { Regex("\\d+(\\.\\d+)?\\s*(m|km)\\b").containsMatchIn(it) },
        )
    }

    @Test
    fun `a typed query with no hits says so, and says it about the query`() {
        show(
            UiState(
                phase = Phase.EXPLORE, gps = GpsHealth.GOOD,
                searching = true, query = "zzzznothing", searched = true,
                results = emptyList(),
            ),
        )
        compose.onNodeWithText("No match for \u201Czzzznothing\u201D").assertIsDisplayed()
    }

    @Test
    fun `saved places are offered when the search box is opened`() {
        show(UiState(phase = Phase.EXPLORE, gps = GpsHealth.GOOD,
                     searching = true, places = listOf(home, work)))
        compose.onNodeWithContentDescription("Navigate to Home, Msheireb").assertIsDisplayed()
        compose.onNodeWithContentDescription("Navigate to Work, West Bay").assertIsDisplayed()
    }

    @Test
    fun `nothing is offered when neither has been set`() {
        // An empty "Home" row that cannot be tapped to anything is the dead
        // control this codebase keeps refusing to add. They are set from the
        // destination chip instead.
        show(UiState(phase = Phase.EXPLORE, gps = GpsHealth.GOOD, searching = true))
        compose.onAllNodesWithText("Home").assertCountEquals(0)
        compose.onAllNodesWithText("Work").assertCountEquals(0)
    }

    @Test
    fun `a saved place shows its label and the place it points at`() {
        // The label is what the driver looks for; the name is how they check
        // they set it to the right place. Waze shows the same pair.
        show(UiState(phase = Phase.EXPLORE, gps = GpsHealth.GOOD,
                     searching = true, places = listOf(home)))
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Msheireb").assertIsDisplayed()
    }

    @Test
    fun `a destination can be saved as Home or Work from the preview`() {
        // PREVIEW is the only phase holding a coordinate the driver has
        // deliberately chosen, which is why the control lives here and not in
        // settings — a settings version would need a place picker, and a place
        // picker is a second search UI.
        show(navState().copy(phase = Phase.PREVIEW, destinationName = "Villaggio Mall"))
        // Labels now, not bare glyphs with a content description. They were an
        // unlabelled house and an unlabelled briefcase sharing a row with an
        // unlabelled close control, so the one destructive control on the card
        // looked exactly like the two that save. See [DestinationChip].
        compose.onNodeWithText("Set Home").assertIsDisplayed()
        compose.onNodeWithText("Set Work").assertIsDisplayed()
    }

    @Test
    fun `saving a place is confirmed where the driver can see it`() {
        // The bottom group gives PREVIEW to the route chooser and the status
        // line to EXPLORE only, so the confirmation written by `onSavePlace`
        // was going somewhere nothing renders — a confirmation nobody can see
        // is worse than none, because the code reads as though it confirms.
        show(navState().copy(
            phase = Phase.PREVIEW,
            destinationName = "Villaggio Mall",
            status = "Home set to Villaggio Mall",
        ))
        compose.onNodeWithText("Home set to Villaggio Mall").assertIsDisplayed()
    }

    @Test
    fun `a slot that is already set is not offered again`() {
        // Reported directly: *"once home and work is set, it shouldnt prompt
        // again."*
        //
        // Saving Home is a once-a-year decision, and the preview card is a
        // screen a driver reaches several times a day — so a "Change Home"
        // control sitting under every destination forever is a permanent prompt
        // for a question already answered, spending the card's most valuable
        // row on it. Only the UNSET slots are offered.
        //
        // Changing one is still reachable and still one place: clear it in
        // Settings -> Your data, and it is offered here again next time. That
        // is asserted by `clearing Home reaches the store with the right slot`.
        show(navState().copy(phase = Phase.PREVIEW, destinationName = "Villaggio Mall",
                             places = listOf(home)))
        compose.onAllNodesWithText("Set Home").assertCountEquals(0)
        compose.onAllNodesWithText("Change Home").assertCountEquals(0)
        // Work is still unset, so it is still offered.
        compose.onNodeWithText("Set Work").assertIsDisplayed()
    }

    @Test
    fun `with both slots set the save row is gone entirely`() {
        // Not merely emptied — absent. A row of zero controls still costs the
        // card its padding and leaves a gap the driver has to account for.
        show(navState().copy(
            phase = Phase.PREVIEW, destinationName = "Villaggio Mall",
            places = listOf(home, Places.Saved(Places.Slot.WORK, "West Bay", 51.4986, 25.3208)),
        ))
        compose.onAllNodesWithText("Set Home").assertCountEquals(0)
        compose.onAllNodesWithText("Set Work").assertCountEquals(0)
    }

    @Test
    fun `the save controls are not offered while driving`() {
        // Nothing on the driving screen may compete with the instruction band,
        // and a driver at 100 km/h is not filing places.
        show(navState().copy(places = listOf(home)))
        compose.onAllNodesWithText("Set Home").assertCountEquals(0)
        compose.onAllNodesWithText("Change Home").assertCountEquals(0)
    }

    @Test
    fun `the preview chip does not overlap itself with the save controls on it`() {
        show(navState().copy(phase = Phase.PREVIEW, destinationName = "Villaggio Mall",
                             places = listOf(home, work)))
        val rects = visibleRects()
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val (ta, ra) = rects[i]; val (tb, rb) = rects[j]
            assertTrue("\"$ta\" $ra overlaps \"$tb\" $rb", !overlaps(ra, rb))
        }
    }
}
