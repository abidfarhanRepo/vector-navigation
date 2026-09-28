package dev.vector.android.benchmark

import androidx.test.uiautomator.UiDevice

/**
 * The device state every measurement in this module depends on.
 *
 * Kept in one place because these are not per-test choices: a benchmark that
 * disabled animations but did not grant location, or one that grants location
 * but leaves a 1.0× transition scale, produces a number that is not comparable
 * with the number next to it in the same report.
 */
internal object BenchmarkConfig {

    /** The app under test. Paired with this module by `targetProjectPath = ":app"`. */
    const val TARGET_PACKAGE = "dev.vector.android"

    /**
     * The query `InteractionBenchmark` types.
     *
     * "al" is not arbitrary and not a placeholder. It has to clear
     * `MainActivity.MIN_QUERY_CHARS` (2) so the search actually runs — a
     * one-character query deliberately clears the panel instead of searching —
     * and it has to return MORE rows than the results card can show (16 from
     * the bundled fixture, in a list bounded at 380 dp) or the scroll test
     * would be measuring a list that cannot scroll, which is a green test that
     * proves nothing.
     */
    const val SEARCH_QUERY = "al"

    /** How long a UiAutomator lookup waits before it is a failure. */
    const val FIND_TIMEOUT_MS = 10_000L

    /**
     * Put the device into the state the metrics in this module assume.
     *
     * ## Animations off, and this is not a nicety
     *
     * `FrameTimingMetric` reports the distribution of frame durations for the
     * frames drawn while the measured block runs, and a frame produced by an
     * ANIMATOR is drawn by the platform's animation loop whatever the app code
     * does. Vector animates a lot on purpose (`VectorMotion`: sheet
     * transitions, a 0.99 press scale on every pressable, spinner and
     * cross-fade on the search field), so with animations on, the number would
     * be mostly a measurement of Choreographer's frame scheduling — and of how
     * many of the 5 iterations happened to catch a 300 ms sheet slide — rather
     * than of the work this app does to produce a frame. Two runs on the same
     * build would differ because the animations are asynchronous, which is the
     * definition of a metric that cannot be re-run.
     *
     * With the scales at 0 the animation frames collapse to their end state, so
     * every frame measured is a frame of the app's own work. That is also why
     * it is a DEVICE setting and not a build flag: the value a benchmark needs
     * is not a value any user should ship with.
     *
     * ## Location already granted
     *
     * `MainActivity.requestLocation()` launches the runtime permission dialog
     * when `ACCESS_FINE_LOCATION` is not granted, and it does so from the same
     * coroutine that applies the style and starts the frame loop — that is,
     * during the window `StartupTimingMetric` measures. The dialog is another
     * app's window (the system's), so leaving it to appear means every cold
     * start measurement includes a system dialog animation, and every
     * interaction test starts by fighting a modal.
     *
     * Granting here rather than relying on `adb install -g`: `pm grant` is what
     * this module can do for itself, it is idempotent, and it keeps a
     * `connectedAndroidTest` run correct without a hand-typed install command.
     */
    fun setUpDevice(device: UiDevice) {
        // 0 = "off". `settings put` rather than a UiAutomator toggle because the
        // developer-options UI is not present on every image.
        put(device, "animator_duration_scale")
        put(device, "transition_animation_scale")
        put(device, "window_animation_scale")

        // 1.0 = the type scale the design was measured at.
        //
        // Not hypothetical housekeeping: this device has been left at 2.0 by
        // another harness in this repository, and it is not a setting the app
        // can ignore — the type scale changes every text run's line count, the
        // height of the search field and of each result row, and therefore how
        // much layout and text shaping happens per frame. A benchmark that
        // inherits it measures a different app at a different size, and would
        // report the difference as a regression on the next run.
        put(device, "font_scale", "system", "1.0")

        // And no per-app locale, for the same reason. The device matrix's RTL
        // cells set `ar` for this package; an inherited `ar` mirrors the whole
        // layout, so the same swipes would travel over different widgets. No
        // `--locales` argument means "clear it": the app falls back to the
        // device locale, which is en-US on this image.
        device.executeShellCommand("cmd locale set-app-locales $TARGET_PACKAGE --user 0")

        // Harmless if the app is not installed yet or the permission is already
        // granted: `pm grant` prints a complaint on stderr and exits, and a
        // non-zero exit here is not a reason to fail a benchmark run.
        grant(device, "android.permission.ACCESS_FINE_LOCATION")
        grant(device, "android.permission.ACCESS_COARSE_LOCATION")
    }

    private fun put(
        device: UiDevice,
        key: String,
        namespace: String = "global",
        value: String = "0",
    ) {
        device.executeShellCommand("settings put $namespace $key $value")
    }

    private fun grant(device: UiDevice, permission: String) {
        device.executeShellCommand("pm grant $TARGET_PACKAGE $permission")
    }
}
