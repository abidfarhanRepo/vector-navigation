package dev.vector.android.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the app takes to show its first frame from a cold start.
 *
 * ## What the numbers mean
 *
 * `StartupTimingMetric` reports one value per iteration, in milliseconds:
 *
 *  - **`timeToInitialDisplayMs`** — from the launch intent reaching the process
 *    to the first frame of the app's own window being drawn. This is the number
 *    a driver experiences as "the app opened", and it is the primary number of
 *    this class.
 *  - **`timeToFullDisplayMs`** — the same start to the app calling
 *    `reportFullyDrawn()`. Vector never calls it (the map's first frame is the
 *    surface that matters and MapLibre does not report one), so in practice
 *    only the initial-display value appears. Reported here as a fact rather
 *    than papered over with a synthetic `reportFullyDrawn()` call: a fabricated
 *    "fully drawn" point would be a number about the call site, not the app.
 *
 * Macrobenchmark prints the minimum, median, and maximum of the iterations, so
 * the "median" is over the 5 iterations below.
 *
 * ## What would make it regress
 *
 * Anything on the launch path before the first frame: more work in
 * `MainActivity.onCreate` (style and tile-set fetches run before the map is
 * applied), MapLibre style parsing, the splash theme's exit, and — under
 * `CompilationMode.None` — the JIT warm-up that the baseline profile is
 * supposed to remove. This is why the class measures the same journey under two
 * compilation modes: the gap between them IS the baseline profile's
 * contribution, and a gap that closes means the profile stopped matching.
 *
 * ## What it does NOT do
 *
 * It asserts nothing about the timing. A benchmark measures; it does not gate.
 * Wall-clock startup on an emulator with software rendering is not a release
 * criterion (see `benchmark/README.md`), and a threshold in this file would
 * turn a slow machine into a red build without saying anything about the app.
 * Correctness is asserted by `:app`'s tests; this class exists to be read.
 */
@OptIn(ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUpDevice() = BenchmarkConfig.setUpDevice(device)

    /**
     * The primary number: cold start of the release APK, compiled against the
     * baseline profile it actually ships.
     *
     * ## Why `Partial` and not `None` or `Full`
     *
     * - **Not `None`.** `None` runs the app with no ahead-of-time compilation
     *   at all, so every launch re-JITs the launch path. That is not how any
     *   installed Android app behaves — the platform compiles apps on install
     *   and on idle — so a `None` number is a floor that describes a state no
     *   user is ever in. It is measured by [startupColdWithoutCompilation]
     *   precisely BECAUSE it is the floor: the difference between the two is
     *   the baseline profile's contribution made visible.
     *
     * - **Not `Full`.** `Full` AOT-compiles every method in the app. It is the
     *   best possible startup and the worst possible install: a multi-second
     *   `dex2oat` on install and a much larger odex, for a device that has to
     *   compile on battery. It also hides the thing this module exists to
     *   watch — with everything compiled, a baseline profile that has stopped
     *   matching the code still measures fast, so the metric stops being able
     *   to fail.
     *
     * - **`Partial`** is what the platform actually does for a real release
     *   install: `filter=speed-profile`, with the profile the APK ships
     *   (`assets/dexopt/baseline.prof` — merged from the dependency AARs by
     *   AGP; the app's own code covers itself once the profile from
     *   [BaselineProfileGenerator] is committed).
     *
     * ## Why this is `Ignore` and not `Partial`, and what that costs
     *
     * `Partial` cannot run against this app on API 34+, and the failure is the
     * app's, not this module's. In every mode `CompilationMode.Partial` forces
     * the profile onto the device with
     * `androidx.profileinstaller.action.INSTALL_PROFILE` before compiling, and
     * on API 34+ that broadcast needs `androidx.profileinstaller` 1.4.0 or
     * newer in the target APK. Vector resolves 1.3.1 transitively, so a run on
     * this emulator (API 35) fails with:
     *
     *     The device SDK version (35) isn't supported by the target app's copy
     *     of profileinstaller. Please use profileinstaller `1.4.0` or newer for
     *     API 34+ support
     *
     * (`UseIfAvailable` raises the same error: it too asks the app to install
     * the profile, and only tolerates a missing profile — not an installer it
     * cannot talk to.)
     *
     * The alternative was never "no profile": the APK's embedded
     * `assets/dexopt/baseline.prof` is installed by the PLATFORM at install
     * time, which is the path every real user's device takes. `Ignore` does not
     * recompile anything and measures exactly that install-time state — the
     * release APK, with the baseline profile its install produced, as a user
     * has it. That is the closest runnable equivalent to `Partial` here, and it
     * is why the second method still exists: the difference between `Ignore`
     * and `None` is what the profile and the install are worth.
     *
     * The clean fix is on the app side — declare
     * `androidx.profileinstaller:1.4.0` (or newer) explicitly in
     * `app/build.gradle.kts` — after which `CompilationMode.Partial()` can be
     * restored and this class measures the profile compiled from the app's own
     * code as well. Until then this MUST NOT be presented as a measurement of
     * `CompilationMode.Partial`.
     */
    @Test
    fun startupColdWithBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        // `COLD` makes macrobenchmark force-stop the process between
        // iterations; `pressHome()` puts the launcher in front so the launch
        // under measurement is a real one from the home screen rather than a
        // resume of an activity that is already on top.
        pressHome()
        startActivityAndWait()
    }

    /**
     * The floor, for contrast: the same cold start with no compilation at all.
     *
     * Read this ONLY against [startupColdWithBaselineProfile]. On its own it is
     * not a statement about the app — an app that is never AOT-compiled is a
     * state no install produces. Reported, not asserted, for the same reason as
     * above.
     */
    @Test
    fun startupColdWithoutCompilation() = benchmarkRule.measureRepeated(
        packageName = BenchmarkConfig.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
