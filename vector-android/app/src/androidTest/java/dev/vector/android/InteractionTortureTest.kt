package dev.vector.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app, driven the way a bored, hurried or drunk thumb drives it.
 *
 * ## The bugs these catch
 *
 * A navigation app is not used carefully. It is stabbed at one-handed, in a
 * cradle, at a red light, often twice because the first press did not visibly
 * take. Every test here is that behaviour pointed at a specific piece of state
 * that can be left inconsistent by a second press arriving before the first one
 * has finished:
 *
 * * **Two presses where one was expected.** The search affordance is replaced by
 *   the search field during the same gesture that opened it. A press landing on
 *   the transition used to be able to leave both composed, or neither — the
 *   "stuck modal" this suite exists to catch, and the one a screenshot cannot
 *   distinguish from a slow frame.
 * * **A control that counts presses.** `zoomStep` moves the camera a whole zoom
 *   level and writes a camera assertion each time. Twenty presses is twenty
 *   camera moves and twenty style-state updates; anything unguarded in that path
 *   (a division, a null map reference after a teardown race) fails here.
 * * **A sheet that can be opened but not closed.** Ten open/dismiss cycles is
 *   enough for a leaked scrim to accumulate; the settings sheet draws a
 *   full-size clickable behind itself precisely so a mis-tap cannot drop a pin
 *   under it, and that same scrim is what stays behind if `onClose` is missed.
 * * **Text that only works when it is well formed.** A 2000-character query, an
 *   emoji-only query and a whitespace-only query are the three shapes that reach
 *   a debounce, a `trim()` and a `length < MIN_QUERY_CHARS` check respectively —
 *   all three are ordinary driver behaviour (`onQueryChanged` also fires when
 *   the box is emptied by deleting).
 */
@RunWith(AndroidJUnit4::class)
class InteractionTortureTest : ChromeTortureTest() {

    /**
     * Twenty presses on the primary search affordance, at one position.
     *
     * The position is captured once, deliberately: after the first press the
     * affordance is gone and the field is in its place, so re-resolving the node
     * between presses would test something no finger can do.
     */
    @Test
    fun twentyPressesOnTheSearchAffordanceLeaveNoModal() {
        awaitExplore()
        compose.mashCenterOf(Torture.SEARCH_AFFORDANCE, times = 20)

        // Whatever the burst left, the driver must be able to get back to the map
        // and to the search box: both directions are exercised, not just the one
        // the state happened to be in.
        compose.awaitOneOf(Torture.SEARCH_FIELD, Torture.SEARCH_AFFORDANCE, "the search chrome")
        compose.assertNoStuckScrim("after twenty presses on the search affordance")
        if (compose.absent(Torture.SEARCH_FIELD)) openSearch() else closeSearch()
        awaitExplore()
    }

    /** Twenty presses on a map control, which is twenty camera moves. */
    @Test
    fun twentyPressesOnZoomInLeaveTheChromeUsable() {
        awaitExplore()
        compose.mashCenterOf(Torture.ZOOM_IN, times = 20)

        // The camera moved; the chrome did not. Nothing is asserted about the
        // zoom level itself — `MapCameraTest` owns that arithmetic on the JVM —
        // only that the app absorbed the burst and still works.
        compose.assertNoStuckScrim("after twenty presses on Zoom in")
        compose.onNode(Torture.ZOOM_IN).assertIsDisplayed()
        compose.onNode(Torture.ZOOM_OUT).assertIsDisplayed()
        openSettings()
        closeSettings()
        awaitExplore()
    }

    /** The settings sheet opens and dismisses ten times, and the map comes back every time. */
    @Test
    fun settingsSheetOpensAndDismissesTenTimes() {
        awaitExplore()
        repeat(10) { round ->
            openSettings()
            closeSettings()
            compose.assertNoStuckScrim("after settings round ${round + 1}")
            compose.onNode(Torture.ZOOM_IN).assertIsDisplayed()
        }
        awaitExplore()
    }

    /**
     * The search field survives type, clear, type and submit.
     *
     * The clear control is what makes this non-trivial: it is composed only while
     * the field is non-empty (`VectorSearchField` switches it for a spacer), so
     * clearing swaps the layout of the row the driver is typing in. A focus or
     * IME regression there is invisible in a screenshot and obvious to a driver.
     */
    @Test
    fun searchFieldSurvivesTypeClearTypeAndSubmit() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("west")
        compose.awaitDisplayed(Torture.RESULTS, "the results for the first query")

        compose.awaitDisplayed(Torture.SEARCH_CLEAR, "the clear-the-query control").performClick()
        // Clearing must put the box back to its empty state, which is the chips
        // and the recents list rather than an empty panel.
        compose.awaitDisplayed(Torture.SEARCH_CHIPS, "the chips an empty search box offers")
        assertTrue(
            "the results stayed up after the query was cleared",
            compose.absent(Torture.RESULTS),
        )

        compose.onNode(hasSetTextAction()).performTextReplacement("west")
        compose.awaitDisplayed(hasText("west"), "the second query typed")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.awaitDisplayed(Torture.RESULTS, "the results after submitting")
    }

    /** A 2000-character query must not take the app down or wedge the field. */
    @Test
    fun aVeryLongQueryLeavesTheAppResponsive() {
        openSearch()
        val long = "a".repeat(2000)
        compose.onNode(hasSetTextAction()).performTextReplacement(long)
        compose.awaitDisplayed(hasText(long), "the long query in the field")

        // Still a working text box afterwards: clearing it must restore the panel
        // the driver started from.
        compose.awaitDisplayed(Torture.SEARCH_CLEAR, "the clear control after a long query")
            .performClick()
        compose.awaitDisplayed(Torture.SEARCH_CHIPS, "the chips after clearing the long query")
        closeSearch()
    }

    /** An emoji-only query must not take the app down or wedge the field. */
    @Test
    fun anEmojiOnlyQueryLeavesTheAppResponsive() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("🧭🚗")
        // Emoji are surrogate pairs in UTF-16, which is where a length check that
        // counts `Char`s instead of code points goes wrong.
        compose.awaitDisplayed(hasText("🧭🚗"), "the emoji query in the field")
        // Either the index has something for it or it says it does not; what must
        // not happen is the panel staying in its loading shape or the app dying on
        // a surrogate pair in a URL.
        compose.awaitOneOf(Torture.RESULTS, Torture.NO_RESULTS, "the panel after an emoji query")
        closeSearch()
        awaitExplore()
    }

    /** A whitespace-only query is an empty query, and must say so rather than search. */
    @Test
    fun aWhitespaceOnlyQueryLeavesTheAppResponsive() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("   ")

        // `onQueryChanged` trims, so this must behave exactly like an empty box:
        // chips and recents, and no results card. A search fired on whitespace
        // would come back empty and put "Nothing found for" over the panel the
        // driver is looking at.
        compose.awaitDisplayed(Torture.SEARCH_CHIPS, "the chips a blank query offers")
        assertTrue(
            "a whitespace-only query requested results",
            compose.absent(Torture.RESULTS),
        )
        closeSearch()
        awaitExplore()
    }
}

/**
 * Mash the centre of the node matching [matcher], at one fixed screen position.
 *
 * `performTouchInput` rather than a loop of `performClick`s, and the position is
 * resolved once: after the first press the node is often gone, and re-resolving it
 * between presses would drive the app in a way no finger can. This is the actual
 * behaviour being tested — the same spot, hit again before anything has settled.
 */
internal fun ComposeTestRule.mashCenterOf(matcher: SemanticsMatcher, times: Int) {
    val box = boundsInDp(matcher)
    val centreX = (box.left.value + box.right.value) / 2f
    val centreY = (box.top.value + box.bottom.value) / 2f
    val at = with(density) { Offset(centreX.dp.toPx(), centreY.dp.toPx()) }
    onRoot().performTouchInput { repeat(times) { click(at) } }
}
