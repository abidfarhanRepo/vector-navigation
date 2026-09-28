package dev.vector.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.endsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a cold start has to reach, and what a warm start has to keep.
 *
 * ## The bugs these catch
 *
 * * **A launch that never becomes interactive.** The map is the last thing to
 *   arrive — style document, glyphs, tiles, then the GL surface — and the splash
 *   is installed before `super.onCreate` and held until the first composition.
 *   Any of those steps can fail in a way that leaves the app on its splash mark,
 *   or on a bare field colour with no chrome over it, and from the outside that
 *   looks like "still starting" rather than "broken". The assertion is the
 *   search affordance on screen with a generous timeout, plus the real `MapView`
 *   mounted underneath it: the chrome can be drawn over a map that never
 *   mounted, and the map can mount with no chrome over it.
 *
 * * **A recreation that takes the driver's work away.** `ActivityScenario
 *   .recreate()` is exactly what the system does for a configuration change not
 *   listed in `android:configChanges` — a font-size change from accessibility
 *   settings, a locale change, or the developer option "don't keep activities".
 *   `MainActivity` restores a journey from preferences, but the search query,
 *   the open search box and the open settings sheet live in a plain field that
 *   is rebuilt from defaults, so a driver halfway through typing a destination
 *   loses it to a system font change. That is asserted here rather than assumed
 *   away, and the query half of the assertion is deliberately not softened: if
 *   it fails, the lesson is that the query belongs in saved state, not that the
 *   test is too strict.
 *
 * ## Why the test names are camelCase and not backticked sentences
 *
 * The JVM suite uses backticked names with spaces and they read beautifully
 * there. Here they cannot: an instrumented test is dexed, and D8 refuses a
 * method name containing a space below DEX version 040 (`minSdk` 30), so
 * `dexBuilderDebugAndroidTest` fails the build outright — a red build with no
 * test ever having run, which is a worse failure than a slightly uglier name.
 *
 * * **A crash on recreate.** The map's native objects are torn down in
 *   `onDestroy` (`mapAlive = false`, `maplibre = null`) while a Choreographer
 *   frame callback and several in-flight coroutines are still live, and a
 *   recreation runs `onCreate` again with the previous instance's teardown
 *   barely finished. Searching, with the settings sheet open, and in the
 *   navigation preview are the three states where the most is happening at once.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTortureTest : ChromeTortureTest() {

    /** A cold start reaches an interactive map. */
    @Test
    fun coldStartReachesAnInteractiveMap() {
        awaitExplore()
        onView(withClassName(endsWith("MapView"))).check(matches(isDisplayed()))
        // Present is not the same as usable: a node can be laid out at zero
        // size or covered by whatever is drawn after it.
        compose.onNode(Torture.SEARCH_AFFORDANCE).assertIsDisplayed()
        compose.onNode(Torture.ZOOM_IN).assertIsDisplayed()
    }

    /**
     * A warm start keeps the query and the phase.
     *
     * The regression this pins: `UiState.query` / `searching` are fields on the
     * Activity, nothing writes either into `onSaveInstanceState`, and the chrome
     * uses no `rememberSaveable`, so the whole chain of them is reconstructed
     * from defaults.
     */
    @Test
    fun warmStartKeepsTheQueryAndThePhase() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("west")
        compose.awaitDisplayed(Torture.RESULTS, "the results for the typed query")

        recreate()

        // Wait for whichever of the two search controls came back, so the failure
        // below is about *which* one rather than about a timeout.
        compose.awaitDisplayed(
            hasSetTextAction().or(Torture.SEARCH_AFFORDANCE),
            "the search chrome after recreating",
        )
        val field = runCatching { compose.onNode(hasSetTextAction()).fetchSemanticsNode() }
            .getOrNull()
        assertNotNull(
            "a recreation closed the open search box: the driver was left with the " +
                "resting affordance and the query gone. On screen: ${compose.labels()}",
            field,
        )
        assertEquals(
            "the query typed before a recreation was lost",
            "west",
            field!!.config.getOrNull(SemanticsProperties.EditableText)?.text,
        )
    }

    /** A recreation while the driver is searching must not take the process down. */
    @Test
    fun recreateDuringSearchDoesNotCrash() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("west")
        compose.awaitDisplayed(Torture.RESULTS, "the results for the typed query")

        recreate()

        // Alive and interactive, WHICHEVER search state came back.
        //
        // This test's name is its whole purpose: a recreation must not kill the
        // app or leave it unusable. It used to also assume the resting
        // affordance returned — i.e. that a recreation closes the search box —
        // and that assumption is now false, because
        // `warmStartKeepsTheQueryAndThePhase` requires the box to survive.
        //
        // Two tests cannot pin opposite outcomes of the same event, and the one
        // whose NAME states a behavioural requirement is the one to keep. So
        // this one asserts only what it is named for: the chrome is there, it
        // answers, and a new query can be typed and read back.
        compose.awaitDisplayed(
            hasSetTextAction().or(Torture.SEARCH_AFFORDANCE),
            "the search chrome after recreating",
        )
        if (compose.onAllNodes(Torture.SEARCH_AFFORDANCE).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(Torture.SEARCH_AFFORDANCE).performClick()
        }
        compose.awaitDisplayed(Torture.SEARCH_FIELD, "the search field after recreating")
        compose.onNode(hasSetTextAction()).performTextReplacement("fuel")
        compose.awaitDisplayed(hasText("fuel"), "the text typed after recreating")
    }

    /** A recreation with the settings sheet open must not take the process down. */
    @Test
    fun recreateWithTheSettingsSheetOpenDoesNotCrash() {
        openSettings()

        recreate()

        awaitExplore()
        compose.assertNoStuckScrim("after recreating with the settings sheet open")
        // Still reachable afterwards: a sheet that cannot be reopened is a worse
        // outcome than one that closed.
        openSettings()
        closeSettings()
        compose.assertNoStuckScrim("after reopening the settings sheet")
    }

    /**
     * A recreation during the navigation preview must not take the process down.
     *
     * The preview is reached through the app's own journey resume: a destination
     * stored by `Journey.save` is re-picked as soon as there is a fix after
     * `onCreate`. That is the same path a driver gets when the process is
     * reclaimed mid-journey, so what is under test really is a destination chip,
     * a route request and a chooser — not a synthetic preview state.
     *
     * Seeding the preference rather than tapping through search keeps this test
     * off the results list, so a failure here is about the lifecycle and nothing
     * else.
     */
    @Test
    fun recreateDuringNavigationPreviewDoesNotCrash() {
        val prefs = Settings.prefs(InstrumentationRegistry.getInstrumentation().targetContext)
        Journey.save(prefs, DESTINATION, 51.5310, 25.2854, System.currentTimeMillis())
        try {
            recreate()
            compose.awaitDisplayed(hasText(DESTINATION), "the resumed destination chip")
            // The chooser is what makes this PREVIEW rather than a destination
            // that never got a route.
            compose.onNode(hasText("Start")).assertIsDisplayed()

            recreate()

            compose.awaitDisplayed(hasText(DESTINATION), "the destination after recreating")
        } finally {
            // A journey left behind would be resumed by the next test in this
            // class — which is exactly the "yesterday's commute waiting on the
            // screen" state the resume window exists to prevent.
            Journey.clear(prefs)
        }
    }
}

/**
 * A destination name no fixture and no recents row can produce.
 *
 * If this were "Doha" the assertion could pass on a result row or a map label
 * while the resume path was broken.
 */
private const val DESTINATION = "Torture Destination"
