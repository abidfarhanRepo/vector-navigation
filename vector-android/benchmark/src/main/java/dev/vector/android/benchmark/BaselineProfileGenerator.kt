package dev.vector.android.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces a baseline profile for `:app` by walking the journeys the benchmarks
 * measure.
 *
 * ## What it does
 *
 * `BaselineProfileRule` starts the target app, walks the block below, and reads
 * back ART's accumulated profile of the classes and methods that were actually
 * entered. It repeats the block (up to [MAX_ITERATIONS]) until the collected
 * profile stops growing, then writes the result on the device for AGP to pull
 * back.
 *
 * ## Where the profile lands, and how it is consumed
 *
 * The collected file is written on the DEVICE, at
 * `/sdcard/Android/media/dev.vector.android.benchmark/additional_test_output/`,
 * as `BaselineProfileGenerator_generate-baseline-prof.txt` (observed on a
 * passing run). A connected run does NOT pull it back to the host and removes
 * the device copy afterwards, so the file has to be taken from the device while
 * the run is live (`adb pull`), or `androidx.baselineprofile`'s
 * `saveInSrc = true` has to be applied. `benchmark/build.gradle.kts` explains
 * why no task is wired up for this. The intended destination is:
 *
 *     benchmark/src/main/generated/baselineProfiles/baseline-prof.txt
 *
 * That path is this module's, because the consumer side is the app's and this
 * module does not own the app's source tree. To actually make the profile part
 * of the shipped binary, the file is copied into the app's own source set:
 *
 *     app/src/main/generated/baselineProfiles/baseline-prof.txt
 *
 * which AGP picks up and compiles into `assets/dexopt/baseline.prof` — the same
 * artefact today's release APK already carries from the dependency AARs (see
 * `StartupBenchmark`). The copy is deliberately NOT done by this module: it is
 * a decision to change what ships, and a benchmark module that silently edited
 * the released app's sources would be a build that changes the artefact as a
 * side effect of measuring it.
 *
 * ## Why it is manual and not part of the build
 *
 * Three reasons, and any one of them is enough:
 *
 * - It needs a device. A benchmark-module task on a machine with no emulator
 *   fails, and nothing about `assembleRelease` should depend on that.
 * - It is slow and unbounded by nature — it repeats the journey until the
 *   profile converges — so it cannot live inside a build that a person waits
 *   for.
 * - It WRITES A SOURCE FILE. A build that rewrites its own inputs is not
 *   reproducible, and a profile is only useful when a human or CI job accepts
 *   the diff it produces.
 *
 * The intended flow is therefore a CI job (or a person) running the release
 * variant's connected task and committing the regenerated profile, which is
 * also the point at which a stale profile is noticed: a profile that no longer
 * matches the code stops covering the launch path, and
 * `StartupBenchmark.startupColdWithBaselineProfile` is where that shows up.
 *
 * ## Which variant to collect from
 *
 * The one the profile is FOR: the release variant, whose APK AGP compiles the
 * profile into. A profile collected from the debug variant describes a build
 * nobody ships, and R8's rewriting of the rules is not a substitute for
 * measuring the code that is actually installed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUpDevice() = BenchmarkConfig.setUpDevice(device)

    /**
     * The journeys, deliberately the same ones `InteractionBenchmark` measures.
     *
     * They are reached through the same file-scope helpers the benchmarks use,
     * so that "the search journey" cannot come to mean one thing here and
     * another thing there: a profile collected over different interactions
     * optimises different code, and nothing would fail — the profile would just
     * stop matching what the benchmarks exercise.
     */
    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        maxIterations = MAX_ITERATIONS,
    ) {
        launchExplore()

        // Search: open, type, and scroll the results — the three states the
        // search panel can be in, so their composition and layout paths are all
        // in the profile.
        device.openSearchAndType(BenchmarkConfig.SEARCH_QUERY)
        device.awaitSearchResults()
        val x = device.displayWidth / 2
        val resultsTop = device.displayHeight * 28 / 100
        val resultsBottom = device.displayHeight * 45 / 100
        device.swipe(x, resultsBottom, x, resultsTop, SWIPE_STEPS)
        device.swipe(x, resultsTop, x, resultsBottom, SWIPE_STEPS)

        // Close it here rather than relying on `launchExplore` at the top of
        // the next iteration, so this iteration ends in the state the next one
        // expects even if the collection stops after it.
        device.findObject(By.desc(CLOSE_SEARCH))?.let {
            it.click()
            device.waitForIdle()
        }

        // Settings: the sheet and the scrollable preferences inside it.
        device.await("the settings control", By.desc(SETTINGS_CONTROL)).click()
        device.await("the settings sheet's Done control", By.text(SETTINGS_DONE)).click()

        // The map itself.
        val upper = device.displayHeight * 42 / 100
        val lower = device.displayHeight * 62 / 100
        device.swipe(x, lower, x, upper, SWIPE_STEPS)
        device.swipe(x, upper, x, lower, SWIPE_STEPS)
        device.await("the zoom-in control", By.desc(ZOOM_IN)).click()
        device.await("the zoom-out control", By.desc(ZOOM_OUT)).click()
    }

    private companion object {
        /**
         * Collection stops as soon as an iteration adds nothing new, so this is
         * a ceiling and not a fixed cost. 3 rather than the library's default of
         * 15: on this app the first iteration covers the launch path and the
         * three journeys, and the emulator round trip per extra iteration is
         * minutes. Raise it if a run reports that it stopped on the cap with
         * the profile still growing.
         */
        const val MAX_ITERATIONS = 3
    }
}
