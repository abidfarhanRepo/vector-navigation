package dev.vector.android

import android.graphics.Bitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The visual baselines, produced rather than asserted.
 *
 * ## What this class is for
 *
 * Every other class in this suite asserts something about the tree. This one only
 * makes pictures, so that the four primary states can be diffed outside the test
 * process — against a previous release, against the design, or against the
 * handset screenshots in `v4-evidence/`. Pixel comparison inside a test would be
 * a trap on a project like this: the baselines would be re-blessed on every
 * change and would end up asserting nothing, and it would fail on the emulator's
 * software renderer for reasons that have nothing to do with the app.
 *
 * The one thing it does assert is that each file was actually written and is not
 * empty. A capture harness that silently writes nothing is worse than no harness:
 * the baselines go stale, the diff comes back "no change", and the release is
 * signed on the strength of a green run.
 *
 * ## Where the files are, and why not in `app/build` directly
 *
 * A test process cannot write to the host's `app/build` — it runs on the device.
 * The PNGs go to the app's own **media** directory,
 * `/sdcard/Android/media/dev.vector.android/screenshots/`, and there are two
 * things about that choice which are not incidental:
 *
 *  * it survives the uninstall that `connectedAndroidTest` performs when the run
 *    ends, so the host can still pull the files afterwards — the app's external
 *    *files* directory does not, and the first version of this harness wrote
 *    there and published nothing;
 *  * it is writable by the app with no permission and readable by `adb`, unlike
 *    `/sdcard/Download`, which an app's process cannot write to at all under
 *    scoped storage — including through `UiAutomation.executeShellCommand`, which
 *    runs in the app's own mount namespace.
 *
 * `scripts/run-device-matrix.sh` pulls that directory into
 * `app/build/screenshots/` after every cell, and by hand it is one command:
 *
 *     adb pull /sdcard/Android/media/dev.vector.android/screenshots \
 *         app/build/screenshots
 *
 * ## Why the theme is not set here
 *
 * It is read from the app's own preference (`shared_prefs/vector.xml`, key
 * `theme`), which the device scripts and the matrix runner set to `LIGHT` before
 * the primary captures. A test that wrote the preference itself would be
 * capturing a state the app was never launched in — MapLibre's style is applied
 * once, from the preference, on the first frame.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest : ChromeTortureTest() {

    /**
     * The composited framebuffer as a bitmap, or null if the shell refused.
     *
     * Falls back to null rather than throwing: a device that will not run
     * `screencap` should degrade to the accessibility frame and say so in the
     * log, not fail a run that is otherwise fine.
     */
    private fun compositedFrame(): Bitmap? = runCatching {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand("screencap -p")
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        if (bytes.size < 1024) return@runCatching null
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /**
     * The lowest number of distinct colours that still counts as "a screen".
     *
     * Deliberately low: this distinguishes a real frame from a black frame, a
     * window transition or a failed capture — nothing finer. See
     * [distinctColours] for why a higher threshold is a bug rather than extra
     * rigour.
     */
    private val MIN_DISTINCT_COLOURS = 3

    /**
     * A floor for a frame that is supposed to have a map on it.
     *
     * ## What this can and cannot catch — measured, not assumed
     *
     * The capture reads the **composited framebuffer** via `screencap` (see
     * [compositedFrame]), so it *can* see MapLibre's `SurfaceView`. That part is
     * verified: the provenance log names the source for every shot
     * (`adb logcat -s VectorShots`), and this threshold is what selects it.
     *
     * What was measured on the API 35 emulator:
     *
     *   | frame                                            | distinct colours |
     *   |--------------------------------------------------|------------------|
     *   | baseline states in the instrumented run           | **24 – 75** |
     *   | the same states with the frame absent/blank       | 5 – 8      |
     *
     * 16 separates those two with margin, and it is a floor rather than a
     * content assertion.
     *
     * ## The limitation this threshold does NOT paper over
     *
     * Inspecting the accepted baselines shows the map's **background** role
     * (`#e4e1db`) present and its `park`, `coastline` and `roadCasing` roles
     * absent — i.e. in the instrumented context the style's background layer
     * draws and its vector features do not. A hand-taken `adb exec-out screencap`
     * of the same build at the same size DOES show them, and the fixture backend
     * logs `/tiles/version` and glyph ranges but no `/tiles/z/x/y.mvt` requests
     * during an instrumented run.
     *
     * So the honest description of these baselines is: **real screenshots of the
     * real app, whose map area shows the cartography's ground layer rather than
     * its features.** They are a good regression baseline for the CHROME — which
     * is what this redesign changed and what no semantics test can see — and they
     * are not a baseline for the cartography. That is covered elsewhere and
     * deliberately: `VectorStyleTest` asserts the palette's layer structure and
     * its measured L* legibility, and `verify_on_device.sh` reads real map pixels
     * off a real device with `uiprobe.py mapcolor`.
     *
     * Raising this number until the capture shows features would not fix it — it
     * would only push every shot back onto the accessibility frame, which cannot
     * see the map surface at all.
     */
    private val MapBearing = 16

    /**
     * A screen the sheet covers is allowed to be quiet.
     *
     * The settings sheet is a large, near-white surface with well-spaced text,
     * and at 2.0x font scale a coarse sample of it can legitimately find very few
     * distinct colours. Its CONTENT is asserted semantically by `openSettings()`;
     * this only has to be sure the frame is not blank.
     */
    private val SheetBearing = 3

    /** EXPLORE at rest: the map, the primary affordance and the map controls. */
    @Test
    fun exploreResting() {
        awaitExplore()
        capture("explore-resting", MapBearing)
    }

    /** The search box open on an empty query: the category chips and the recents. */
    @Test
    fun searchOpenWithChips() {
        openSearch()
        compose.awaitDisplayed(Torture.SEARCH_CHIPS, "the category chips")
        capture("search-open-chips", MapBearing)
    }

    /** The results card, which is what the search box is for. */
    @Test
    fun searchResults() {
        openSearch()
        compose.onNode(hasSetTextAction()).performTextReplacement("west")
        compose.awaitDisplayed(Torture.RESULTS, "the results card")
        capture("search-results", MapBearing)
    }

    /** The settings sheet, scrim and all. */
    @Test
    fun settingsSheet() {
        openSettings()
        capture("settings-sheet", SheetBearing)
    }

    /**
     * A frame with something actually drawn on it.
     *
     * `UiAutomation.takeScreenshot()` can return the window's background before
     * anything has been composited onto it — the first version of this harness
     * published four PNGs of a bare field colour and a status bar, which is worse
     * than publishing nothing: a baseline of an empty screen diffs clean against
     * the next empty screen and says the release is unchanged. The platform also
     * rate-limits screenshots, so the retry is spaced rather than tight.
     *
     * "Drawn" is judged by counting distinct colours on a coarse grid. That is not
     * a pixel comparison — nothing here is compared against a stored image — it is
     * the same question a person asks when they open the file: is there a screen in
     * it?
     */
    /**
     * A screenshot of the COMPOSITED display, not of the accessibility window.
     *
     * ## Why this is `screencap` and not `UiAutomation.takeScreenshot()`
     *
     * `UiAutomation.takeScreenshot()` captures the main window's buffer, and
     * MapLibre renders the map into a `SurfaceView` with its OWN surface. The
     * SurfaceView's contents are composited by SurfaceFlinger, not drawn into the
     * window — so every baseline captured with the accessibility API was a picture
     * of the chrome over the background colour. That is exactly what the first
     * committed `explore-resting` baseline was, and it would have gone on passing
     * no matter what happened to the cartography.
     *
     * Raising a colour threshold cannot fix that: a flat field plus chrome easily
     * clears any threshold you would also want the settings sheet to clear. The
     * capture has to change.
     *
     * `screencap -p` on stdout through `executeShellCommand` reads the composited
     * framebuffer — the same bytes `adb exec-out screencap -p` produces, which is
     * how every hand-captured screenshot in this project was taken and why those
     * show the map and these did not. Nothing is written to the device, so there
     * is no path to clean up and no permission to negotiate.
     */
    private fun drawnFrame(state: String, minColours: Int): Bitmap {
        compositedFrame()?.let { shot ->
            if (distinctColours(shot) >= minColours) {
                android.util.Log.i(
                    "VectorShots",
                    "shot=$state source=screencap colours=${distinctColours(shot)} " +
                        "${shot.width}x${shot.height}",
                )
                return shot
            }
            android.util.Log.w(
                "VectorShots",
                "shot=$state screencap frame too flat (${distinctColours(shot)} < " +
                    "$minColours); falling back to the accessibility frame",
            )
        }
        android.util.Log.w(
            "VectorShots",
            "shot=$state could not capture the composited framebuffer; the baseline " +
                "will NOT contain the map surface",
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var last: Bitmap? = null
        // 20 x 500 ms. The map-bearing states now wait for tile content, and
        // an emulator on a software rasteriser fetching its first tiles over
        // the loopback is not fast — 6 s was occasionally not enough, which is
        // what produced the blank baseline in the first place.
        repeat(20) {
            val shot = automation.takeScreenshot()
            if (shot != null) {
                last = shot
                if (distinctColours(shot) >= minColours) return shot
            }
            Thread.sleep(500)
        }
        val blank = last
        assertNotNull(
            "UiAutomation.takeScreenshot() returned null for \"$state\", so no baseline " +
                "was produced for that state",
            blank,
        )
        assertTrue(
            "every frame captured for \"$state\" was too flat to be a baseline " +
                "(${distinctColours(blank!!)} distinct colours, needed $minColours). " +
                "A screen that should show the map must show the map: a baseline " +
                "captured before the tiles arrive is a picture of the background " +
                "colour, and it would pass forever.",
            false,
        )
        error("unreachable")
    }

    /**
     * How many distinct colours a coarse grid over [shot] samples.
     *
     * ## What this is for, and what it is NOT for
     *
     * It is a **sanity check that the capture is not blank** — a black frame, a
     * frame taken during a window transition, or a screenshot of nothing. It is
     * not a content check, and it cannot be one: a screen that is legitimately a
     * white sheet with large text is pixel-indistinguishable from a white screen
     * with nothing on it.
     *
     * That is exactly how it failed. At 2.0× font scale on a 393×852 dp phone the
     * settings sheet is a near-white surface with big, well-spaced text, and a
     * 16×16 grid over 1031×2236 (steps of 64 × 139 px) could land almost entirely
     * on the sheet's background — 5 distinct colours, reported as "an empty
     * screen". The sheet was on screen the whole time and is asserted as such by
     * `openSettings()`, which awaits `SETTINGS_SHEET` being *displayed* before
     * this is ever called. The product was fine; the heuristic was wrong.
     *
     * So the threshold is deliberately low — it catches a genuinely flat frame
     * and nothing else — and the grid is denser, so a screen with any real
     * content at all clears it. If you want to assert what is ON a screen, assert
     * it in the semantics tree; that is what every other test in this suite does.
     */
    private fun distinctColours(shot: Bitmap): Int {
        val seen = mutableSetOf<Int>()
        val stepX = (shot.width / 32).coerceAtLeast(1)
        val stepY = (shot.height / 32).coerceAtLeast(1)
        for (x in 0 until shot.width step stepX) {
            for (y in 0 until shot.height step stepY) seen.add(shot.getPixel(x, y))
        }
        return seen.size
    }

    /** Screenshot the current frame and write it where the host can collect it. */
    private fun capture(state: String, minColours: Int = MIN_DISTINCT_COLOURS) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shot: Bitmap = drawnFrame(state, minColours)

        val media = instrumentation.targetContext.externalMediaDirs.firstOrNull()
            ?: throw AssertionError(
                "the device has no app media directory, so there is nowhere that both " +
                    "this process can write and the host can pull from after the APKs " +
                    "are uninstalled"
            )
        val dir = File(media, "screenshots")
        assertTrue("could not create $dir", dir.isDirectory || dir.mkdirs())
        val file = File(dir, "$state.png")
        FileOutputStream(file).use { out ->
            assertTrue(
                "PNG encoding failed for \"$state\"",
                shot.compress(Bitmap.CompressFormat.PNG, 100, out),
            )
        }
        assertTrue("\"$state\" was written empty", file.length() > 0)

        // Readable by the host after the run: the media directory outlives the
        // uninstall that `connectedAndroidTest` performs, which the app's own
        // external files directory does not. Asserted rather than assumed — a
        // screenshot harness that silently publishes nothing leaves the release
        // diff running against stale baselines and reporting "no change".
        assertTrue(
            "\"$state\" is not readable by the host at ${file.absolutePath}",
            file.canRead(),
        )
    }
}
