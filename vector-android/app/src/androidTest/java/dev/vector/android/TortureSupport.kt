package dev.vector.android

import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import java.io.FileInputStream

/**
 * Shared scaffolding for the emulator torture suite.
 *
 * ## Why these tests exist at all
 *
 * `app/src/test/.../NavUiTest.kt` composes the real chrome under Robolectric and
 * is genuinely good at what it does — overlap, layout, which control is on
 * screen in which phase. What no JVM test can see is everything that only
 * exists once the app is a window on a real display: MapLibre's GL surface
 * actually mounting, the real status bar and cutout insets, the system font
 * scale, a real Activity `recreate()` under a live composition, and the shapes
 * of a screen a finger can actually hit. Those are the failures the emulator
 * run is for: every one of them has been reported from a handset at least once
 * in this project's history ("overlapping bubbles", the status-bar gradient, the
 * search bar under a cutout, a control that could not be tapped).
 *
 * ## Why a base class rather than a helper each class opts into
 *
 * Two rules have to be installed in a specific order, and both are easy to get
 * subtly wrong in a way that produces a green-but-meaningless run:
 *
 *  1. **The permission grant comes first.** `MainActivity` asks for
 *     `ACCESS_FINE_LOCATION` on its first pass through the map-ready callback.
 *     A permission dialog over the map would make every screenshot a picture of
 *     a dialog and would swallow the first touch of every torture test — so the
 *     grant is registered at order 0 and the activity is launched at order 1.
 *     `connectedAndroidTest` installs the APKs itself and does NOT pass
 *     `adb install -g`, so relying on the install flags would work in a manual
 *     run and fail in CI.
 *  2. **The activity is the real `MainActivity`**, not a test activity with the
 *     chrome pasted in. The whole point is the real window, the real
 *     `MapView` interop view and the real lifecycle.
 *
 * [Torture] holds the constants and matchers; the extension functions below are
 * the two things every class needs — "wait for this to be on screen" and "what
 * is on screen right now" for failure messages.
 */
abstract class ChromeTortureTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule(order = 1)
    val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule<MainActivity>()

    /** The live activity, used for insets, rotation and the scenario handle. */
    protected val activity: MainActivity get() = compose.activity

    /**
     * Start from a known screen.
     *
     * The activity is launched by the rule before every test, and the first
     * composition is the resting explore chrome — but "the rule launched an
     * Activity" is not the same fact as "the chrome is on screen", and the
     * difference is several hundred milliseconds of splash and compose. Every
     * test therefore begins by waiting for the search affordance, which is the
     * one control that is present in the resting chrome no matter what the
     * backend, the location stack or the theme are doing.
     */
    protected fun awaitExplore() {
        compose.awaitDisplayed(Torture.SEARCH_AFFORDANCE, "the resting search affordance")
    }

    /** Open the search box and wait for the text field to take the affordance's place. */
    protected fun openSearch() {
        awaitExplore()
        compose.onNode(Torture.SEARCH_AFFORDANCE).performClick()
        compose.awaitDisplayed(Torture.SEARCH_FIELD, "the active search field")
    }

    /** Dismiss the search box the way the driver does, through its own close control. */
    protected fun closeSearch() {
        compose.awaitDisplayed(Torture.SEARCH_CLOSE, "the close-the-search control").performClick()
        compose.awaitDisplayed(Torture.SEARCH_AFFORDANCE, "the resting search affordance")
    }

    /** Open the settings sheet from the map controls. */
    protected fun openSettings() {
        awaitExplore()
        compose.onNode(Torture.SETTINGS_CONTROL).performClick()
        compose.awaitDisplayed(Torture.SETTINGS_SHEET, "the settings sheet")
    }

    /**
     * Dismiss the settings sheet through its own `Done` control.
     *
     * Deliberately not the scrim: tapping outside to dismiss is a different
     * code path (`onClose` wired to a full-size clickable), and the two are
     * asserted separately by the tests that care.
     */
    protected fun closeSettings() {
        compose.awaitDisplayed(Torture.SETTINGS_DONE, "the settings sheet's Done control")
            .performClick()
        compose.awaitDisplayed(Torture.SEARCH_AFFORDANCE, "the resting search affordance")
    }

    /** Recreate the Activity, exactly as the system does on a configuration change. */
    protected fun recreate() {
        compose.activityRule.scenario.recreate()
    }
}

/** Constants and matchers shared by the suite. */
internal object Torture {

    /**
     * How long a cold start is given to put its chrome on screen.
     *
     * Generous on purpose. MapLibre loads a style document, glyphs and MVT tiles
     * over a software-rendered GL surface, and on the `swiftshader_indirect`
     * emulator the default GPU mode crashes the host outright. A tight timeout
     * here would fail for the emulator's slowness and say nothing about the app;
     * a generous one still catches the real defect — a launch that never
     * becomes interactive at all.
     */
    const val LAUNCH_MS = 60_000L

    /**
     * How long a request that goes to the fixture backend is given.
     *
     * `/search` answers from a local fixture in single-digit milliseconds; the
     * budget is for the emulator's networking, not for the server.
     */
    const val NETWORK_MS = 20_000L

    /**
     * A wait that is expected to EXPIRE.
     *
     * Used where the assertion is "this never appears" — the settle window after
     * a burst of taps, for instance. Long enough that a state which is merely
     * slow is not mistaken for one that is absent.
     */
    const val SETTLE_MS = 1_500L

    /** The resting search affordance: a button that says where the query goes. */
    val SEARCH_AFFORDANCE: SemanticsMatcher = hasTestTag("search:rest")

    /** The active search field. */
    val SEARCH_FIELD: SemanticsMatcher = hasTestTag("search:field")

    /** The close-the-search control inside the field. */
    val SEARCH_CLOSE: SemanticsMatcher = hasTestTag("search:close")

    /** The clear-the-query control inside the field; present only when it is non-empty. */
    val SEARCH_CLEAR: SemanticsMatcher = hasTestTag("search:clear")

    /** The category chips, which are what an empty search box offers. */
    val SEARCH_CHIPS: SemanticsMatcher = hasTestTag("chip:Fuel")

    /** The card the search results are drawn in. */
    val RESULTS: SemanticsMatcher = hasTestTag("results")

    /**
     * The card a query that found nothing is answered with.
     *
     * A distinct state from [RESULTS] and from the chips: "we looked and there is
     * nothing" is an answer, and a test that only knows about results and chips
     * would call it a failure — which is what happened the first time this suite
     * was run against an emoji query.
     */
    val NO_RESULTS: SemanticsMatcher = hasTestTag("no-results")

    /** The settings sheet's own heading. */
    val SETTINGS_SHEET: SemanticsMatcher = hasText("Settings")

    /** The settings sheet's dismiss control. */
    val SETTINGS_DONE: SemanticsMatcher = hasText("Done")

    /** The map control that opens the settings sheet. */
    val SETTINGS_CONTROL: SemanticsMatcher = hasContentDescription("Settings")

    /**
     * The primary map controls, by the label a screen reader is handed.
     *
     * Matched on the content description rather than on position or glyph,
     * because the description is the contract with accessibility and it cannot
     * be quietly lost by a restyle — see the same argument in `NavUiTest`.
     */
    val ZOOM_IN: SemanticsMatcher = hasContentDescription("Zoom in")
    val ZOOM_OUT: SemanticsMatcher = hasContentDescription("Zoom out")
    val COMPASS: SemanticsMatcher = hasContentDescription("Facing the direction of travel — tap for north up")
}

/**
 * Wait until [matcher] matches something in the tree, then hand back the node.
 *
 * Compose's `waitUntil` polls rather than going through the idling resources,
 * which is what makes it the right primitive here: the app posts a Choreographer
 * frame callback for the whole life of the map (see `MainActivity.startFrameLoop`)
 * and a test that waited on "the main thread has nothing queued" would be at the
 * mercy of how the platform accounts for that callback.
 *
 * The failure message carries the on-screen text, because "we never saw
 * `search:rest`" is not actionable and "we saw Where to?" is.
 */
internal fun ComposeTestRule.awaitDisplayed(
    matcher: SemanticsMatcher,
    what: String,
    timeoutMs: Long = Torture.LAUNCH_MS,
): SemanticsNodeInteraction {
    val interaction = onNode(matcher)
    try {
        waitUntil(timeoutMs) {
            runCatching { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
    } catch (e: ComposeTimeoutException) {
        fail("$what never appeared within ${timeoutMs}ms. On screen: ${labels()}")
    }
    return interaction
}

/**
 * Run a shell command as the `shell` user and return its output.
 *
 * The test process reaches the platform through `UiAutomation`, which is how a
 * test can turn on an emulated cutout, change the system font scale or move the
 * display — things that are properties of the DEVICE rather than of the app, and
 * that therefore cannot be faked from inside the process.
 *
 * ## Why the read is on its own thread with a deadline
 *
 * `executeShellCommand` returns a pipe that stays open for as long as the command
 * runs, so reading it blocks. A command that does not exit — and `cmd overlay
 * enable-exclusive` can wait on a SystemUI restart — therefore hangs the test
 * process with no output and no timeout, which is exactly how a suite acquires a
 * twenty-minute cell that nobody can diagnose. The command is fired, its output
 * is collected if it arrives, and the pipe is closed either way.
 */
internal fun shell(command: String, timeoutMs: Long = 10_000L): String {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val descriptor = automation.executeShellCommand(command)
    val output = StringBuilder()
    val reader = Thread {
        runCatching {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { lines ->
                lines.forEachLine { output.appendLine(it) }
            }
        }
    }
    reader.isDaemon = true
    reader.start()
    reader.join(timeoutMs)
    runCatching { descriptor.close() }
    return output.toString()
}

/** Wait for [matcher] to stop matching — the assertion that a state has been left. */
internal fun ComposeTestRule.awaitGone(
    matcher: SemanticsMatcher,
    what: String,
    timeoutMs: Long = Torture.NETWORK_MS,
) {
    try {
        waitUntil(timeoutMs) {
            runCatching { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }.getOrDefault(false)
        }
    } catch (e: ComposeTimeoutException) {
        fail("$what was still on screen after ${timeoutMs}ms. On screen: ${labels()}")
    }
}

/**
 * Wait for one of [first] or [second] to be on screen.
 *
 * For the states where the app is allowed to be in either of two shapes — the
 * search box open or closed — and the assertion is about what is true of BOTH
 * (nothing stuck over the map). Waiting for one specific one would fail a
 * legitimate outcome.
 */
internal fun ComposeTestRule.awaitOneOf(
    first: SemanticsMatcher,
    second: SemanticsMatcher,
    what: String,
    timeoutMs: Long = Torture.LAUNCH_MS,
) = awaitDisplayed(first.or(second), what, timeoutMs)

/** True when nothing matching [matcher] is in the tree right now. */
internal fun ComposeTestRule.absent(matcher: SemanticsMatcher): Boolean =
    runCatching { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)

/** Every node in the merged tree, which is what a screen reader is handed. */
internal fun ComposeTestRule.nodes(): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        out.add(node)
        node.children.forEach { walk(it) }
    }
    walk(onRoot().fetchSemanticsNode())
    return out
}

/**
 * What a screen reader would announce for [this], or a description of why it
 * would announce nothing — used in failure messages so a failing assertion names
 * the node it is complaining about instead of dumping an index.
 */
internal fun SemanticsNode.label(): String {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    val editable = config.getOrNull(SemanticsProperties.EditableText)?.text
    val desc = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    val tag = config.getOrNull(SemanticsProperties.TestTag)
    return buildString {
        if (!text.isNullOrBlank()) append("text=\"$text\" ")
        if (!editable.isNullOrBlank()) append("editable=\"$editable\" ")
        if (!desc.isNullOrBlank()) append("desc=\"$desc\" ")
        if (!tag.isNullOrBlank()) append("tag=\"$tag\" ")
        if (isEmpty()) append("<unlabelled ${config.getOrNull(SemanticsProperties.Role) ?: "node"}>")
        append("@${boundsInRoot}")
    }.trim()
}

/** The labels of everything on screen, for a failure message. */
internal fun ComposeTestRule.labels(): String =
    runCatching { nodes().filter { it.isLabeled() }.joinToString("; ") { it.label() } }
        .getOrDefault("<no compose hierarchy>")

/** True when a screen reader would say something for [this]. */
internal fun SemanticsNode.isLabeled(): Boolean =
    !config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
        !config.getOrNull(SemanticsProperties.EditableText)?.text.isNullOrEmpty() ||
        !config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty()

/** The unclipped bounds of the node matching [matcher], in dp relative to the root. */
internal fun ComposeTestRule.boundsInDp(matcher: SemanticsMatcher): DpRect =
    onNode(matcher).getUnclippedBoundsInRoot()

/** The bounds of the root composition — "the window", for containment assertions. */
internal fun ComposeTestRule.windowInDp(): Rect {
    val root = onRoot().fetchSemanticsNode()
    val d = density
    return with(d) {
        Rect(0f, 0f, root.size.width.toDp().value, root.size.height.toDp().value)
    }
}

/** True when [this] is entirely inside [other]. */
internal fun Rect.contains(inner: DpRect, tolerance: Float = 1f): Boolean =
    inner.left.value >= left - tolerance &&
        inner.top.value >= top - tolerance &&
        inner.right.value <= right + tolerance &&
        inner.bottom.value <= bottom + tolerance

/**
 * Assert that nothing the size of the window is sitting over the app.
 *
 * A modal in this app is a full-size `Box` with a `clickable` on it (see
 * `SettingsSheet`'s scrim) and no label. It is the shape that makes a "the sheet
 * closed but nothing can be tapped any more" bug possible — the sheet disappears
 * from the screenshot and the scrim stays. Asserting on the semantics tree
 * catches it; a screenshot cannot.
 */
internal fun ComposeTestRule.assertNoStuckScrim(state: String) {
    val root = onRoot().fetchSemanticsNode()
    val stuck = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.layoutInfo.isPlaced && !it.isLabeled() }
        .filter { it.size.width >= root.size.width && it.size.height >= root.size.height }
    assertTrue(
        "$state: a window-sized unlabelled clickable is still over the app, so taps " +
            "are being swallowed by a modal that never went away: ${stuck.map { it.label() }}",
        stuck.isEmpty(),
    )
}
