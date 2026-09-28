package dev.vector.android.benchmark

import android.view.KeyEvent
import android.widget.EditText
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the four interactions this product is actually used for.
 *
 * ## What the numbers mean
 *
 * `FrameTimingMetric` reports, per measured block, the distribution of the
 * frames drawn while it ran:
 *
 *  - **`frameDurationCpuMs`** — how long the CPU spent producing and submitting
 *    each frame, at p50/p90/p95/p99. On a 60 Hz display a frame has 16.7 ms;
 *    the p99 is the one to read, because one 40 ms frame in a scroll is what a
 *    driver sees as a stutter.
 *  - **`frameOverrunMs`** — how far past the frame's deadline the frame
 *    finished. Negative means it made the deadline comfortably, positive means
 *    it was late by that much. This is the number that corresponds to jank.
 *
 * ## What would make it regress
 *
 * Per frame: work on the main thread during composition (the results rows
 * recompose on every keystroke via `highlightMatch`), MapLibre tile decode and
 * label placement on the map surface, and anything that adds a frame of latency
 * to a gesture — an extra layout pass, or a new `animate*AsState` on a
 * frequently-invalidated value. A regression here is a change in the SHAPE of
 * the distribution: a p99 that grows while the p50 does not is the signature of
 * a jank source being added, not of the device being slower.
 *
 * ## UiAutomator, not Compose test APIs
 *
 * Every interaction below is driven by `UiDevice`/`UiObject2`. A macrobenchmark
 * module is a separate APK in a separate process, so Compose's test APIs —
 * which locate nodes through the app's semantics tree, inside the app's own
 * process — cannot see the app at all. UiAutomator drives it the way a user
 * does: through the accessibility tree and injected input.
 *
 * ## What it does NOT do
 *
 * It asserts nothing about the frame numbers. A benchmark measures; it does not
 * gate. See `benchmark/README.md` for why numbers from an emulator are
 * indicative rather than a release criterion.
 *
 * ## Compilation mode
 *
 * `CompilationMode.Ignore()` — measure the release APK exactly as the platform
 * installed it, baseline profile included. `CompilationMode.Partial()` is the
 * conventional choice for frame metrics and cannot be used here: it makes
 * macrobenchmark install the profile with the `profileinstaller` broadcast, and
 * this app resolves `androidx.profileinstaller` 1.3.1, which the broadcast
 * refuses on API 34+ ("The device SDK version (35) isn't supported by the
 * target app's copy of profileinstaller"). See `StartupBenchmark`, which
 * carries the full account; the app-side fix is to declare
 * `androidx.profileinstaller:1.4.0` or newer.
 */
@OptIn(ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class InteractionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUpDevice() = BenchmarkConfig.setUpDevice(device)

    /**
     * Typing a destination: open the search field, then type the query.
     *
     * One key event per character, which is what a driver does. The measured
     * work per keystroke is a text-field recomposition plus the results panel
     * re-highlighting the match — the most frequently repeated piece of UI work
     * in the app.
     */
    @Test
    fun openSearchAndTypeQuery() = benchmarkRule.measureRepeated(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { launchExplore() },
    ) {
        device.openSearchAndType(BenchmarkConfig.SEARCH_QUERY)
        // The journey ends when the answer is on screen, not when the last key
        // is injected: the results panel layout plus the match highlighting is
        // the expensive half, and it happens after the debounce, so a block
        // that stopped at the keystroke would end before the work it is meant
        // to measure. The wait itself is UiAutomator polling outside the app's
        // process; it adds no frames of the app's making.
        device.awaitSearchResults()
    }

    /**
     * Scrolling the results list.
     *
     * Scrolled down and back up inside the measured block rather than in one
     * direction. `StartupMode.WARM` does not kill the process between
     * iterations, so a one-way scroll would leave iteration 2 starting where
     * iteration 1 finished: by iteration 5 the list would be at its end and
     * unable to scroll at all, which reports as a *better* frame distribution
     * for a block that does less work. Two swipes restore the position, so all
     * five iterations measure the same scroll.
     */
    @Test
    fun scrollSearchResults() = benchmarkRule.measureRepeated(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            launchExplore()
            device.openSearchAndType(BenchmarkConfig.SEARCH_QUERY)
            device.awaitSearchResults()
        },
    ) {
        val x = device.displayWidth / 2
        // Confined to the results card: it starts below the search field and is
        // bounded by the on-screen keyboard, so this band never lands on the
        // category chips above it or on the IME below it.
        val top = device.displayHeight * 28 / 100
        val bottom = device.displayHeight * 45 / 100
        device.swipe(x, bottom, x, top, SWIPE_STEPS)
        device.swipe(x, top, x, bottom, SWIPE_STEPS)
    }

    /**
     * Opening and closing the settings sheet.
     *
     * Both halves are in the measured block because they are one interaction:
     * the cost of the sheet is its transition plus the recomposition of what is
     * underneath it, and measuring only the open would miss the half that runs
     * while the map is already busy.
     */
    @Test
    fun openAndCloseSettingsSheet() = benchmarkRule.measureRepeated(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { launchExplore() },
    ) {
        device.await("the settings control", By.desc(SETTINGS_CONTROL)).click()
        // Waiting for "Done" before clicking it is also what proves the sheet
        // opened: without the wait the block could time a sheet that had not
        // appeared yet.
        device.await("the settings sheet's Done control", By.text(SETTINGS_DONE)).click()
    }

    /**
     * Panning and zooming the map itself — the gesture the whole product is.
     *
     * ## Why the pan is a swipe and the zoom is a control
     *
     * The pan is a `UiDevice.swipe`, which is the map's own drag gesture: it
     * moves the camera by the drag distance and MapLibre re-renders the
     * surface, which is the frame cost being measured. It is performed out and
     * back for the same reason the results list is scrolled in both directions
     * — five one-way pans would leave the camera five swipes from where it
     * started, fetching a different set of tiles on every iteration.
     *
     * The zoom uses the app's own ±1 controls rather than MapLibre's double-tap
     * or a pinch, and that is a determinism argument rather than a preference.
     * A double-tap zooms in one level and has no inverse gesture, and a pinch is
     * continuous with no exact inverse either, so five iterations would measure
     * five different zooms — and the frame cost of the map is a function of
     * zoom, because tile selection and label density change with it. The two
     * controls are discrete: `ZOOM_BUTTON_STEP` is exactly 1.0, so in followed
     * by out returns the camera to the zoom it started at, and iteration 5
     * measures what iteration 1 measured.
     */
    @Test
    fun panAndZoomMap() = benchmarkRule.measureRepeated(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = { launchExplore() },
    ) {
        val x = device.displayWidth / 2
        // The middle of the screen is bare map: the search field is at the top
        // and every control is in a column on the right edge.
        val upper = device.displayHeight * 42 / 100
        val lower = device.displayHeight * 62 / 100
        device.swipe(x, lower, x, upper, SWIPE_STEPS)
        device.swipe(x, upper, x, lower, SWIPE_STEPS)

        device.await("the zoom-in control", By.desc(ZOOM_IN)).click()
        device.await("the zoom-out control", By.desc(ZOOM_OUT)).click()
    }
}

// ---------------------------------------------------------------------------
// The journeys, shared with `BaselineProfileGenerator`.
//
// Defined once, at file scope, because the profile generator has to walk the
// SAME journeys the benchmarks measure: a profile collected over different
// interactions optimises different code, and the two would then disagree about
// what "the search journey" is without anything failing. A shared definition
// makes that structural rather than a matter of keeping two files in step.

/**
 * Open the search field and type [query], one key event per character.
 *
 * Keys rather than [UiObject2.setText]: `setText` delivers the whole string as
 * a single `ACTION_SET_TEXT` edit, which collapses N keystrokes'
 * recompositions into one. That is a journey no user performs, and measuring it
 * would measure a cheaper thing than the app does in a driver's hand.
 */
internal fun UiDevice.openSearchAndType(query: String) {
    await("the search button (\"$SEARCH_REST\")", By.text(SEARCH_REST)).click()
    val field = await("the search field", By.clazz(EditText::class.java))
    // The app focuses this field itself when the search opens; clicking it
    // anyway makes the focus independent of that effect's timing, and a tap on
    // an already-focused field costs one frame.
    field.click()
    query.forEach { pressKeyCode(keyCodeOf(it)) }
    waitForIdle()
}

/**
 * Wait for the first result row for [BenchmarkConfig.SEARCH_QUERY].
 *
 * A row's own text is the only thing in the accessibility tree that proves the
 * search ran: the results card has no `contentDescription`, and the spinner is
 * a test tag, which UiAutomator — outside the app's process — cannot see. The
 * expected name is fixture-dependent on purpose. It comes from the
 * deterministic fixture backend this module is run against, and if that
 * fixture's data changes this constant has to change with it: a loud failure,
 * rather than a benchmark that quietly measures an empty panel.
 */
internal fun UiDevice.awaitSearchResults() {
    await("the first result (\"$FIRST_RESULT\")", By.text(FIRST_RESULT))
}

/**
 * Bring the app to its explore surface, whatever the previous iteration left
 * open.
 *
 * `StartupMode.WARM` keeps the process alive across iterations and
 * `measureRepeated` re-runs the setup block before each one, so the state
 * iteration N inherits is the state iteration N-1 finished in. Closing the two
 * overlays a previous iteration can leave behind is what makes five iterations
 * five measurements of the same journey rather than one descending staircase.
 */
internal fun MacrobenchmarkScope.launchExplore() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
    // The search field's own back control, present only while searching.
    device.findObject(By.desc(CLOSE_SEARCH))?.let {
        it.click()
        device.waitForIdle()
    }
    // The settings sheet's close control, present only while it is open.
    device.findObject(By.text(SETTINGS_DONE))?.let {
        it.click()
        device.waitForIdle()
    }
}

/** Wait for a node, or fail naming what was expected. */
internal fun UiDevice.await(what: String, selector: BySelector): UiObject2 =
    wait(Until.findObject(selector), BenchmarkConfig.FIND_TIMEOUT_MS)
        ?: error("$what never appeared within ${BenchmarkConfig.FIND_TIMEOUT_MS / 1000}s")

/**
 * Steps the swipes are injected in. Enough that MapLibre sees a drag rather
 * than a teleport: a one-step swipe arrives as a single large move event, which
 * the map handles as a jump and which no finger produces.
 */
internal const val SWIPE_STEPS = 20

/** The resting search control's label. */
internal const val SEARCH_REST = "Where to?"

/** The settings control's `contentDescription`, and the sheet's close control. */
internal const val SETTINGS_CONTROL = "Settings"
internal const val SETTINGS_DONE = "Done"

/** The search field's back control, present only while searching. */
internal const val CLOSE_SEARCH = "Close the search"

internal const val ZOOM_IN = "Zoom in"
internal const val ZOOM_OUT = "Zoom out"

/**
 * The first row the fixture returns for `BenchmarkConfig.SEARCH_QUERY`.
 * `Al Corniche Street` is deliberately mixed-case: the app title-cases
 * all-caps names, so a shouted fixture name would not match the text actually
 * rendered.
 */
internal const val FIRST_RESULT = "Al Corniche Street"

/**
 * A key event for [c]. Letters are contiguous from `KEYCODE_A`, which is the
 * only range this module's query needs; anything else is a mistake here rather
 * than a query to type.
 */
internal fun keyCodeOf(c: Char): Int = when (c) {
    in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
    ' ' -> KeyEvent.KEYCODE_SPACE
    else -> error(
        "no key event for '$c' — add the mapping rather than silently " +
            "typing a different character"
    )
}
