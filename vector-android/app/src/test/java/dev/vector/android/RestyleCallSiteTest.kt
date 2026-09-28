package dev.vector.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to rebuild the map style, pinned by counting.
 *
 * ## Why a source-reading test, of all things
 *
 * [ReleaseWatchTest] proves that the release *decision* never authorises a
 * restyle during guidance. It cannot prove that nothing else does, because
 * "nothing else" is a property of the Activity's 4,000-odd lines rather than
 * of any object under test, and `applyStyle` needs MapLibre's native peer to
 * be exercised at all.
 *
 * The risk is concrete and it is the reason the V7.7 constraint was written
 * down: the tempting fix for "a rollback is invisible" is one line in
 * `onResume` that rebuilds the style. That line would be correct nine times
 * out of ten and, on the tenth, would replace the style under a driver being
 * guided — the `IllegalStateException: invalid native peer` window
 * `applyStyle`'s KDoc documents, whose recorded cause was a driver changing
 * the theme mid-drive.
 *
 * So this counts the call sites and makes adding one a deliberate act. It is
 * not a substitute for a device run; it is a tripwire on the specific edit
 * most likely to be made in a hurry.
 *
 * The repo already pins behaviour this way where the thing being protected is
 * a property of a file rather than of a value — see `test_pwa.py`, which
 * asserts on the service worker's source.
 */
class RestyleCallSiteTest {

    private val source: String by lazy {
        // Gradle runs unit tests with the MODULE directory as the working
        // directory, but that is a default rather than a guarantee, and a test
        // that silently cannot find its subject would pass by reading nothing.
        // The candidates cover being run from the module, the Android project
        // root, or the repository root.
        val rel = "src/main/java/dev/vector/android/MainActivity.kt"
        val candidates = listOf(
            File(rel),
            File("app/$rel"),
            File("vector-android/app/$rel"),
        )
        val f = candidates.firstOrNull { it.isFile }
        assertTrue(
            "MainActivity.kt not found from ${File(".").absolutePath}; tried " +
                candidates.joinToString { it.path },
            f != null)
        f!!.readText()
    }

    private fun callSites(): List<String> =
        source.lines()
            .filter { it.contains("applyStyle(") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.contains("private fun applyStyle(") }
            .map { it.trim() }

    @Test
    fun `the style is rebuilt from five places, and only five`() {
        val sites = callSites()

        assertEquals(
            "The number of applyStyle() call sites changed.\n" +
                "\n" +
                "A style rebuild destroys every source and re-acquires the " +
                "handles only in an async callback, while the frame loop runs " +
                "at up to 120 Hz across the gap — the `invalid native peer` " +
                "crash in applyStyle's KDoc.\n" +
                "\n" +
                "The five permitted sites are:\n" +
                "  1. cold start, once the tile set is known\n" +
                "  2. onConfigurationChanged, resolved theme actually changed\n" +
                "  3. updateSettings, theme changed\n" +
                "  4. updateSettings, 2D/3D extrusion changed\n" +
                "  5. ReleaseWatch.Step.Apply — and ONLY that step, which " +
                "cannot be produced while guidance is running\n" +
                "\n" +
                "If you are adding a sixth: say which of those it is not, and " +
                "what stops it firing during Phase.NAVIGATING.\n" +
                "Found:\n" + sites.joinToString("\n") { "  $it" },
            5, sites.size)
    }

    @Test
    fun `no lifecycle callback rebuilds the style by itself`() {
        /**
         * The specific edit this file exists to catch. `onResume` may ask
         * which release is active; it may not restyle. The apply decision is
         * ReleaseWatch's, and it is gated on guidance.
         */
        val resume = source.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")

        assertTrue(
            "onResume must not call applyStyle directly — the release path " +
                "goes through ReleaseWatch, which refuses during guidance:\n" +
                resume,
            !resume.contains("applyStyle("))
    }

    @Test
    fun `the release path reaches a restyle only through the Apply step`() {
        /**
         * The guard, read back from the source it protects. `applyReleaseStep(step)` must
         * return early for every step that is not `Step.Apply`, because that
         * is the one step `ReleaseWatch` will not produce during guidance.
         */
        val fn = source.substringAfter("private fun applyReleaseStep(step: ReleaseWatch.Step)")
            .substringBefore("private fun logRelease(")

        assertTrue(
            "applyReleaseStep() must bail out on any step that is not Step.Apply:\n$fn",
            fn.contains("if (step !is ReleaseWatch.Step.Apply) return"))
        assertTrue(
            "applyReleaseStep() must not touch a destroyed native peer:\n$fn",
            fn.contains("mapAlive"))
    }

    @Test
    fun `guidance is judged by the phase, which covers walking too`() {
        /**
         * `Phase.NAVIGATING` is set by `startNavigation` AND by `beginWalk`.
         * Judging guidance by `walkSession != null` would be narrower and
         * would leave a driver unprotected; judging it by a new flag would
         * create a second source of truth. A pedestrian under guidance is as
         * badly served by a style rebuilt underneath them as a driver.
         */
        val fn = source.substringAfter("private fun underGuidance()")
            .substringBefore("\n    /**")

        assertTrue("guidance must be read from the phase: $fn",
                   fn.contains("ui.phase == Phase.NAVIGATING"))
    }
}
