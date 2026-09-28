package dev.vector.android

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chrome against the parts of a phone the app does not draw.
 *
 * ## The bugs these catch
 *
 * The reported defect this class exists for is the search bar sitting under the
 * camera hole, and its cousins: a map control under the gesture bar, the bottom
 * of a sheet under the three-button navigation bar, a rotation that pushes the
 * primary control off a short landscape window. Nothing in a screenshot taken on
 * a device with no cutout would show any of them, and nothing in a JVM test can
 * either — insets are supplied by the window, and Robolectric's window is a stub.
 * So each test here changes a real system input and asserts on real bounds.
 *
 * The assertion is bounds, not presence, because presence is exactly what a
 * control squeezed off the top of the display, or shrunk to nothing, still has: a
 * node laid out at `[0,-40][360,12]` is present, is drawn, and cannot be tapped.
 *
 * ## Two coordinate spaces, and why that is not a detail
 *
 * `getUnclippedBoundsInRoot()` is in dp relative to the COMPOSE ROOT. Window
 * insets are in px relative to the WINDOW. On this device those two origins are
 * NOT the same point — the compose view sits inside the window content — and an
 * assertion that mixes them fails by exactly the offset between them, which is
 * how a correct layout gets reported as a defect. So:
 *
 *  * "inside the window" compares `getUnclippedBoundsInRoot()` (dp) against the
 *    root's own size (dp) — one space, root-relative;
 *  * "clear of the status bar / navigation bar" compares the control's
 *    `boundsInWindow` (px) against the window's own insets (px) — one space,
 *    window-relative.
 *
 * Every failure message prints both spaces and the decor view's position on
 * screen, so the next person to see a red test here does not have to reconstruct
 * which space was which.
 *
 * ## Why the stimuli are asserted, not assumed
 *
 * A test that changes a system setting and then asserts the layout is fine passes
 * whenever the setting silently failed to apply — it runs the default
 * configuration twice and reports green. Both inset tests here therefore check
 * that the inset they are about actually moved, and the cutout one skips with a
 * message rather than passing when the emulator cannot emulate a cutout at all.
 */
@RunWith(AndroidJUnit4::class)
class InsetsAndConfigurationTest : ChromeTortureTest() {

    /** At rest, in the configuration the phone boots in. */
    @Test
    fun thePrimaryControlSitsInsideTheWindowAtRest() {
        awaitExplore()
        assertPrimaryControlOnScreen("in the default configuration")
    }

    /**
     * The chrome stays clear of the navigation bar when gesture navigation changes.
     *
     * Switching between gesture navigation and three-button navigation is the
     * inset change a driver hits without doing anything: an Android upgrade, or
     * the setting itself, moves the bottom inset by 63 px on this device. The map
     * controls sit in the bottom-right corner — directly over that region — and
     * `navigationBarsPadding()` is the only thing keeping the compass and the
     * zoom buttons out from under the system's own bar.
     *
     * The inset is asserted to have moved before anything else is, so this cannot
     * pass by having failed to change the device.
     */
    @Test
    fun theChromeStaysClearOfTheNavigationBarWhenGestureNavigationChanges() {
        awaitExplore()
        val before = navigationBarBottomPx()

        shell("cmd overlay enable-exclusive --category $NAV_BUTTONS_OVERLAY")
        try {
            awaitInsetAbove(before, "three-button navigation") { navigationBarBottomPx() }
            assertPrimaryControlOnScreen("with a three-button navigation bar")
            assertBottomControlsClearOfNavigationBar()
        } finally {
            shell("cmd overlay enable-exclusive --category $NAV_GESTURE_OVERLAY")
            awaitInsetBelow(before, "gesture navigation being restored") { navigationBarBottomPx() }
            awaitExplore()
        }
    }

    /**
     * An emulated notch must not push the primary control off the top.
     *
     * The app draws edge to edge and opts into `SHORT_EDGES`, which is a decision
     * with a consequence: the window may extend into a cutout, so the chrome's own
     * padding is the only thing keeping the search affordance out of it. This test
     * asserts the chrome is inside the window and clear of the safe top while a
     * real `DisplayCutout` is present.
     *
     * ## Why the cutout is applied OUTSIDE this process
     *
     * Turning the emulated cutout on from inside the test does not work on this
     * image: `cmd overlay enable-exclusive --category <cutout>` makes the overlay
     * manager force-stop the app under test — and the instrumentation runs in that
     * app's process, so the run dies with "Instrumentation run failed due to
     * Process crashed". The evidence is unambiguous: `am_kill … dev.vector.android
     * … from pid <the cmd process>` lands 0.5 s after that shell call, and the same
     * command from `adb shell` (where nothing is instrumenting the app) leaves it
     * running.
     *
     * So the cutout is a property of the DEVICE, applied before the suite:
     *
     *     adb shell cmd overlay enable-exclusive --category \
     *         com.android.internal.display.cutout.emulation.tall
     *     ./gradlew :app:connectedDebugAndroidTest
     *     adb shell cmd overlay disable \
     *         com.android.internal.display.cutout.emulation.tall
     *
     * When no cutout is present the test is SKIPPED with that instruction rather
     * than passed: an inert stimulus proves nothing, and reporting it as a pass is
     * how a suite ends up with coverage that does not exist.
     */
    @Test
    fun thePrimaryControlStaysClearOfAnEmulatedCutout() {
        awaitExplore()
        val cutout = cutoutTopPx()
        assumeTrue(
            "no display cutout is present (displayCutout top = ${cutout}px), so there is " +
                "nothing to assert here. Apply one before the suite:\n" +
                "  adb shell cmd overlay enable-exclusive --category $CUTOUT_OVERLAY",
            cutout > 0,
        )
        assertPrimaryControlOnScreen("under an emulated notch")
    }

    /**
     * A large system font scale must not push the primary control off the screen.
     *
     * This is the accessibility setting a driver actually turns on — text they
     * cannot read is a navigator they cannot use — and it is applied at the
     * platform level, so every `sp` in the chrome grows at once: the search
     * affordance, the destination chip, the map controls' labels. The Activity is
     * recreated, which is what the system does for a `fontScale` change, because
     * `fontScale` is deliberately not in this app's `android:configChanges`.
     */
    @Test
    fun aLargeFontScaleDoesNotPushThePrimaryControlOffScreen() {
        awaitExplore()
        val original = shell("settings get system font_scale").trim()

        shell("settings put system font_scale 2.0")
        try {
            recreate()
            awaitExplore()
            assertPrimaryControlOnScreen("at a 2.0× system font scale")
        } finally {
            // Restoring is not optional: the scale is a system-wide setting and
            // every later test in the run would be measured at 2.0×.
            shell("settings put system font_scale ${original.ifBlank { "1.0" }}")
            recreate()
            awaitExplore()
        }
    }

    /**
     * Landscape is a smaller, wider window and the chrome stays on it.
     *
     * `screenOrientation` is `unspecified` and `orientation|screenSize` are in
     * `configChanges`, so a rotation resizes the existing composition rather than
     * rebuilding it — the case where a top-anchored column can be pushed under the
     * status bar by a taller-than-expected first item, and the case a
     * portrait-only test would never reach.
     */
    @Test
    fun rotationKeepsThePrimaryControlOnScreen() {
        awaitExplore()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            awaitWindow(wantLandscape = true, what = "the display rotating to landscape")
            assertPrimaryControlOnScreen("in landscape")
        } finally {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            awaitWindow(wantLandscape = false, what = "the display rotating back to portrait")
            awaitExplore()
        }
    }

    // ---- assertions --------------------------------------------------------

    /**
     * The primary controls are inside the window and clear of the top safe area.
     *
     * The two halves fail differently: a control outside the window cannot be
     * reached at all, and one inside the window but behind the status bar or a
     * cutout can be seen and not tapped, which is worse because it looks like it
     * should work.
     *
     * ## Why this polls before it asserts
     *
     * Window insets are dispatched a frame AFTER the first composition, so for one
     * frame on a cold start or a recreation `statusBarsPadding()` reads zero and
     * the whole chrome is drawn at the very top of the window. Asserting on that
     * frame would report a defect that does not exist — and would report it only
     * sometimes, which is how a suite acquires a reputation for flakiness and then
     * gets ignored. The assertion is made against the SETTLED layout, and it still
     * fails if the control is never fully on screen within the timeout.
     */
    private fun assertPrimaryControlOnScreen(what: String) {
        val deadline = System.currentTimeMillis() + Torture.NETWORK_MS
        while (true) {
            val report = placement()
            if (report.ok) break
            if (System.currentTimeMillis() > deadline) {
                assertTrue(
                    "$what: the primary control never reached a settled, fully " +
                        "on-screen position in ${Torture.NETWORK_MS}ms.\n$report",
                    false,
                )
            }
            Thread.sleep(50)
        }
        compose.onNode(Torture.SEARCH_AFFORDANCE).assertIsDisplayed()
    }

    /** The bottom-most control of the map's control stack is above the system bar. */
    private fun assertBottomControlsClearOfNavigationBar() {
        val deadline = System.currentTimeMillis() + Torture.NETWORK_MS
        while (true) {
            val report = placement()
            if (report.bottomClear) break
            if (System.currentTimeMillis() > deadline) {
                assertTrue(
                    "the map controls are under the navigation bar.\n$report",
                    false,
                )
            }
            Thread.sleep(50)
        }
    }

    /** Everything the assertions need, measured once, in one snapshot. */
    private fun placement(): Placement {
        val decor = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        val location = IntArray(2)
        decor.getLocationOnScreen(location)

        val root = compose.onRoot().fetchSemanticsNode()
        val search = compose.onNode(Torture.SEARCH_AFFORDANCE).fetchSemanticsNode()
        val zoomIn = compose.onNode(Torture.ZOOM_IN).fetchSemanticsNode()
        val zoomOut = compose.onNode(Torture.ZOOM_OUT).fetchSemanticsNode()
        val compass = compose.onNode(Torture.COMPASS).fetchSemanticsNode()
        val unclipped = compose.boundsInDp(Torture.SEARCH_AFFORDANCE)

        val rootDp = with(compose.density) {
            Rect(0f, 0f, root.size.width.toDp().value, root.size.height.toDp().value)
        }
        val safeTopPx = maxOf(
            insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0,
            insets?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0,
        )

        return Placement(
            searchDp = unclipped,
            rootDp = rootDp,
            insideWindow = rootDp.contains(unclipped) &&
                rootDp.contains(compose.boundsInDp(Torture.ZOOM_IN)) &&
                rootDp.contains(compose.boundsInDp(Torture.ZOOM_OUT)) &&
                rootDp.contains(compose.boundsInDp(Torture.COMPASS)),
            searchInWindow = search.boundsInWindow,
            bottomControlInWindow = compass.boundsInWindow,
            zoomInInWindow = zoomIn.boundsInWindow,
            zoomOutInWindow = zoomOut.boundsInWindow,
            rootSize = root.size.width to root.size.height,
            decorOnScreen = location[0] to location[1],
            safeTopPx = safeTopPx,
            statusBarsPx = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0,
            cutoutPx = insets?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0,
            navigationBarsPx = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0,
            density = compose.density.density,
        )
    }

    private class Placement(
        val searchDp: androidx.compose.ui.unit.DpRect,
        val rootDp: Rect,
        val insideWindow: Boolean,
        val searchInWindow: Rect,
        val bottomControlInWindow: Rect,
        val zoomInInWindow: Rect,
        val zoomOutInWindow: Rect,
        val rootSize: Pair<Int, Int>,
        val decorOnScreen: Pair<Int, Int>,
        val safeTopPx: Int,
        val statusBarsPx: Int,
        val cutoutPx: Int,
        val navigationBarsPx: Int,
        val density: Float,
    ) {
        /**
         * The primary control is inside the window and below the status bar or
         * cutout. `safeTopPx > 0` is part of the settle condition: "no insets have
         * arrived yet" and "this device has no status bar" are not the same state,
         * and only the second one makes the assertion vacuous.
         */
        val ok: Boolean
            get() = insideWindow && safeTopPx > 0 && searchInWindow.top >= safeTopPx - 1f

        /** The bottom of the control stack clears the navigation bar. */
        val bottomClear: Boolean
            get() = navigationBarsPx > 0 &&
                bottomControlInWindow.bottom <= rootSize.second - navigationBarsPx + 1f

        override fun toString(): String = buildString {
            append("  search: unclipped=${searchDp.left.value},${searchDp.top.value}" +
                "[${searchDp.right.value},${searchDp.bottom.value}]dp " +
                "window=[${searchInWindow.left},${searchInWindow.top}]" +
                "[${searchInWindow.right},${searchInWindow.bottom}]px\n")
            append("  zoom in/out: [${zoomInInWindow.top},${zoomInInWindow.bottom}] / " +
                "[${zoomOutInWindow.top},${zoomOutInWindow.bottom}]px, " +
                "bottom control [${bottomControlInWindow.top}," +
                "${bottomControlInWindow.bottom}]px\n")
            append("  compose root: ${rootSize.first}x${rootSize.second}px " +
                "(=${rootDp.right}x${rootDp.bottom}dp), " +
                "decor view on screen at ${decorOnScreen.first},${decorOnScreen.second}\n")
            append("  insets: statusBars=${statusBarsPx}px displayCutout=${cutoutPx}px " +
                "navigationBars=${navigationBarsPx}px safeTop=${safeTopPx}px " +
                "(${safeTopPx / density}dp), density=$density\n")
            append("  insideWindow=$insideWindow bottomClear=$bottomClear")
        }
    }

    // ---- system levers -----------------------------------------------------

    private fun statusBarTopPx(): Int =
        ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0

    private fun cutoutTopPx(): Int =
        ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0

    private fun navigationBarBottomPx(): Int =
        ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0

    /** The safe top the app must keep clear: the status bar or the cutout, whichever is deeper. */
    private fun safeTopInsetPx(): Int = maxOf(statusBarTopPx(), cutoutTopPx())

    private fun awaitInsetAbove(threshold: Int, what: String, read: () -> Int) {
        val deadline = System.currentTimeMillis() + Torture.LAUNCH_MS
        while (read() <= threshold) {
            if (System.currentTimeMillis() > deadline) {
                assertTrue(
                    "$what did not change the inset (still ${read()}px, was ${threshold}px " +
                        "before), so this test proves nothing",
                    false,
                )
            }
            Thread.sleep(100)
        }
    }

    /**
     * Wait for an inset to come back to (or under) the value it had before.
     *
     * `<=`, not `<`: gesture navigation on this device leaves a 63 px inset of its
     * own — the pill's strip — so "restored" means back to that, not back to zero.
     * The first version of this waited for strictly less and failed on a restore
     * that had in fact worked.
     */
    private fun awaitInsetBelow(threshold: Int, what: String, read: () -> Int) {
        val deadline = System.currentTimeMillis() + Torture.LAUNCH_MS
        while (read() > threshold) {
            if (System.currentTimeMillis() > deadline) {
                assertTrue(
                    "$what did not restore the inset (still ${read()}px, was " +
                        "${threshold}px before)",
                    false,
                )
            }
            Thread.sleep(100)
        }
    }

    private fun awaitWindow(wantLandscape: Boolean, what: String) {
        val deadline = System.currentTimeMillis() + Torture.LAUNCH_MS
        while (true) {
            val window = compose.windowInDp()
            if ((window.width > window.height) == wantLandscape) return
            if (System.currentTimeMillis() > deadline) {
                assertTrue(
                    "$what did not happen within ${Torture.LAUNCH_MS}ms: the window is " +
                        "${window.width}x${window.height}dp",
                    false,
                )
            }
            Thread.sleep(100)
        }
    }

    private companion object {
        /**
         * A tall notch at the top of the display — the shape that puts a cutout
         * inside the region a top-anchored control wants to occupy.
         */
        const val CUTOUT_OVERLAY = "com.android.internal.display.cutout.emulation.tall"

        /**
         * The two navigation-bar modes. `--category` with the target name is the
         * documented way to switch between the members of an exclusive overlay
         * category; plain `disable` leaves the category EMPTY, which on this image
         * leaves the three-button insets applied — verified on the emulator, and
         * the reason the restore below enables gesture navigation rather than
         * disabling the buttons.
         */
        const val NAV_BUTTONS_OVERLAY = "com.android.internal.systemui.navbar.threebutton"
        const val NAV_GESTURE_OVERLAY = "com.android.internal.systemui.navbar.gestural"
    }
}
