package dev.vector.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the chrome is to somebody who cannot see it.
 *
 * ## The bugs these catch
 *
 * Vector's map controls are drawn, not written — they are `Canvas` glyphs with no
 * text anywhere in the composed tree. The only thing that makes them usable with
 * a screen reader (and with Espresso, and with the device harness) is the
 * `contentDescription` each one is supposed to carry. Losing it is invisible in
 * every other kind of check this project runs: the control still draws, still
 * taps, and the screenshot still looks right. It is a silent loss, which is the
 * kind that reaches a driver who cannot see the icon.
 *
 * The touch-target walk catches the other silent one. A 48 dp control that is
 * restyled to 40 dp looks *tidier* — closer to its neighbours, less padding — and
 * the only thing it breaks is a driver stabbing at it from a cradle at a red
 * light. Android's own stated minimum is 48 dp and `VectorTokens.Size.control`
 * says so in as many words ("a control a driver stabs at without looking should
 * not be sized to the floor"), so a control under it is a defect against the
 * design system's own contract rather than a matter of taste.
 *
 * ## What is deliberately not checked, and why
 *
 * The scope is the **explore chrome**: the map controls and the primary search
 * affordance. Not the search panel's category chips, which are 40 dp tall by
 * design (`VectorChip`), and not the sheets. Holding a surface the brief does not
 * name to a rule it was never built for would produce a failing test that nobody
 * intends to fix, and a suite with one permanently-red test is a suite nobody
 * reads.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest : ChromeTortureTest() {

    /** 48 dp, in px at whatever density the device the matrix is running at has. */
    private val minTargetPx: Float
        get() = with(compose.density) { 48.dp.toPx() }

    /**
     * Every icon-only control says what it is.
     *
     * "Icon-only" is read off the merged tree: a node that is clickable and
     * carries no text, no editable text and no content description announces
     * itself to a screen reader as an unlabelled button, and to a voice-control
     * user as nothing at all.
     */
    @Test
    fun everyIconOnlyControlExposesAContentDescription() {
        awaitExplore()
        val silent = compose.nodes()
            .filter { it.layoutInfo.isPlaced }
            .filter { it.config.contains(SemanticsActions.OnClick) }
            .filter { !it.isLabeled() }
            .map { it.label() }
        assertTrue(
            "these clickable nodes would be announced as an unlabelled button: $silent",
            silent.isEmpty(),
        )
    }

    /**
     * The primary controls are reachable by the label a screen reader is handed.
     *
     * Asserted through `onNodeWithContentDescription` rather than through the
     * tree walk above, because that is the query a screen reader and an
     * automation harness both actually use — a description that is present but
     * not exposed on the clickable node would pass the walk and still be
     * unreachable.
     */
    @Test
    fun thePrimaryControlsAreReachableByContentDescription() {
        awaitExplore()
        for (control in listOf(Torture.SETTINGS_CONTROL, Torture.ZOOM_IN, Torture.ZOOM_OUT, Torture.COMPASS)) {
            compose.onNode(control and hasClickAction()).assertIsDisplayed()
        }
        // And the search affordance, which is labelled by its text rather than by
        // a description — the one control a driver reaches for first.
        compose.onNode(Torture.SEARCH_AFFORDANCE).assertIsDisplayed()
    }

    /**
     * Every clickable target in the explore chrome is at least 48x48 dp.
     *
     * Bounds are measured from the semantics tree, so this is the size the
     * accessibility framework and a finger both see. The failure names the node
     * and its measured size: "one of them is too small" is a test result nobody
     * can act on.
     */
    @Test
    fun everyClickableTargetIsAtLeast48Dp() {
        awaitExplore()
        val root = compose.onRoot().fetchSemanticsNode()
        val tooSmall = compose.nodes()
            .filter { it !== root }
            .filter { it.layoutInfo.isPlaced }
            .filter { it.config.contains(SemanticsActions.OnClick) }
            .filter { node ->
                node.boundsInRoot.width < minTargetPx || node.boundsInRoot.height < minTargetPx
            }
            .map { node ->
                val b = node.boundsInRoot
                val w = with(compose.density) { b.width.toDp().value }
                val h = with(compose.density) { b.height.toDp().value }
                "${node.label()} is ${w}x${h}dp"
            }
        assertTrue(
            "these targets are under the 48x48dp minimum: $tooSmall",
            tooSmall.isEmpty(),
        )
    }

    /**
     * An icon-only control's description is not only present but *meaningful*.
     *
     * `VectorIcons.Control(label = ...)` takes a String, and "" is a legal String.
     * An empty description passes a naive "has a content description" check while
     * announcing exactly as little as a missing one.
     */
    @Test
    fun iconOnlyControlsHaveNonEmptyDescriptions() {
        awaitExplore()
        val blank = compose.nodes()
            .filter { it.config.contains(SemanticsProperties.ContentDescription) }
            .filter { node ->
                val descriptions = node.config[SemanticsProperties.ContentDescription]
                descriptions.isEmpty() || descriptions.all { it.isBlank() }
            }
            .map { it.label() }
        assertTrue("these nodes carry a blank content description: $blank", blank.isEmpty())
    }

    /** The compass is the one control whose label is a sentence; keep it intact. */
    @Test
    fun theCompassAnnouncesItsStateAndItsAction() {
        awaitExplore()
        val description = compose.onNode(Torture.COMPASS).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue(
            "the compass's description no longer says what pressing it does: " +
                "\"$description\"",
            description.contains("tap for north up") || description.contains("tap for heading up"),
        )
    }
}
