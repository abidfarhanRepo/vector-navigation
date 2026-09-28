import java.io.File

/**
 * The macrobenchmark module.
 *
 * ## Why this is a module and not an `androidTest` source set
 *
 * A macrobenchmark must not run in the process it is measuring. Every number in
 * this module — startup, frame timing — is a property of `:app` as installed on
 * the device, and an instrumented test that shares the app's process shares its
 * heap, its threads and its class loader, which is exactly the interference the
 * measurement cannot have. `com.android.test` builds a SEPARATE test APK that
 * AGP installs alongside `:app` and starts in its own process; the target is
 * named by `targetProjectPath` below, and instrumentation drives it through
 * `am`, not through a shared runtime.
 *
 * ## It cannot ship
 *
 * Nothing here is consumed by `:app`. `com.android.test` modules are absent from
 * `assembleRelease`/`bundleRelease` of the app entirely — the release artefact
 * is byte-for-byte the same whether this module exists or not — so the
 * "benchmarks must not ship" constraint is structural rather than a matter of
 * remembering to exclude them from a packaging step.
 *
 * ## Which variant is measured
 *
 * The `benchmark` build type declared below, which `matchingFallbacks` pairs
 * with `:app`'s **release** variant. That is the variant worth reporting: it is
 * minified, not debuggable, and — the reason `StartupBenchmark`'s primary metric
 * is `CompilationMode.Partial` — its APK carries a baseline profile
 * (`assets/dexopt/baseline.prof`, merged by AGP from the dependency AARs). The
 * debug APK carries none, so `Partial` refuses to run against it.
 */
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vector.android.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // The runner the benchmark sources execute under. `MacrobenchmarkRule`
        // is a JUnit4 `TestRule`, and the default runner AGP synthesises
        // (`android.test.InstrumentationTestRunner`) discovers JUnit4 classes
        // badly and refuses rules outright — a failure mode that reports as
        // "no tests found", which is the least useful thing a benchmark run
        // can say. Same runner as `:app`'s instrumented tests.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The app under test. AGP installs `:app`'s APK for the matching variant
    // and starts it from here.
    targetProjectPath = ":app"

    // This module instruments ITSELF (the test APK is also the APK under test)
    // rather than attaching to an `androidTest` source set of `:app`. Without
    // it AGP expects a variant of the target that has an instrumented test
    // component and configuration fails outright.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    /**
     * The build type the benchmarks run under.
     *
     * A `com.android.test` module gets a `debug` build type and nothing else,
     * and a test variant is paired with the target variant of the SAME name.
     * `:app` has no `benchmark` build type — and this module does not own the
     * app's build file, so it cannot add one — which is what `matchingFallbacks`
     * is for: the pairing falls back to the app's `release` variant.
     *
     * That is the variant worth measuring, not a workaround. `StartupBenchmark`'s
     * primary metric is `CompilationMode.Partial`, and only the release APK
     * carries a baseline profile to compile against (`assets/dexopt/baseline.prof`,
     * merged by AGP from the dependency AARs; the debug APK has none, so
     * `Partial` refuses to run against it). The release APK is also minified and
     * not debuggable, which is the build a user installs.
     *
     * [isDebuggable] applies to the TEST apk only — the app under test is
     * whatever `:app` produced for the paired variant, so this does not make
     * the measurement debug-shaped.
     */
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // `:app` is Java 17 (`compileOptions` there), and the test APK is loaded by
    // the same ART, so the class file level has to match.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // `MacrobenchmarkRule`, `BaselineProfileRule`, `StartupTimingMetric`,
    // `FrameTimingMetric`, `CompilationMode`, `StartupMode`.
    //
    // 1.4.1 and not 1.5.0 (the newest stable) for one reason: the POM. 1.5.0
    // declares `org.jetbrains.kotlin:kotlin-stdlib:2.1.20`, above this
    // project's Kotlin 2.0.21 compiler — the same inconsistent-runtime-version
    // trap that pins `androidx.car.app` to 1.7.0 in `app/build.gradle.kts`.
    // 1.4.1 declares `kotlin-stdlib:2.0.21`, this project's version exactly.
    // Verified by reading both POMs from Google's Maven:
    //   https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-macro-junit4/1.4.1/benchmark-macro-junit4-1.4.1.pom
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")

    // `AndroidJUnit4` — the `@RunWith` every test class here carries.
    // 1.2.1 is the version `:app` already resolves for its instrumented tests,
    // so the two test APKs share one `androidx.test` graph instead of drifting
    // apart. Confirmed present in this build's dependency graph and in the
    // Gradle module cache.
    implementation("androidx.test.ext:junit:1.2.1")

    // `UiDevice`, `By`, `Until`, the gestures. Macrobenchmarks drive the app
    // from OUTSIDE its process, so Compose test APIs — which run inside the
    // app's process and are invisible to a `com.android.test` module — are not
    // available and not usable here.
    //
    // 2.3.0 is the version `benchmark-macro` 1.4.1 itself compiles against, so
    // declaring it explicitly pins what is already on the classpath rather than
    // requesting a second opinion.
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")

    // JUnit4 itself. `benchmark-macro-junit4` 1.4.1 declares 4.13.2; `:app`'s
    // unit tests use 4.13.2. Declared rather than leaned on transitively
    // because these sources use `@Rule`/`@Test`/`@Before` directly.
    implementation("junit:junit:4.13.2")

    // `AndroidJUnitRunner`, named in `testInstrumentationRunner` above, and
    // the one artifact on the classpath whose version actually matters to the
    // platform: the runner is loaded by ART before any of this code. Declared
    // at the same version `:app` uses (1.6.2) so the instrumentation stack is
    // internally consistent — `benchmark-macro` 1.4.1 asks for runner 1.5.2,
    // which is older than the 1.6.1 `androidx.test:core`/`monitor` that
    // `androidx.test.ext:junit` 1.2.1 brings, and Gradle resolving a runner
    // against a newer monitor than it was built for is how a benchmark run
    // fails before it prints a single number.
    implementation("androidx.test:runner:1.6.2")
}

/*
 * There is deliberately NO Gradle task here for pulling the generated baseline
 * profile into the source tree, and its absence is a finding rather than an
 * omission.
 *
 * The first version of this file had one (`syncBaselineProfile`, finalised onto
 * the connected tasks). It was removed because it could not work and did damage
 * while not working:
 *
 *  - For a CONNECTED run AGP does not copy the benchmark's additional test
 *    output back to the host at all, and it deletes the device copy when the run
 *    ends. The profile that a passing `BaselineProfileGenerator` run definitely
 *    produced was on the device at
 *    /sdcard/Android/media/dev.vector.android.benchmark/additional_test_output/
 *    during the run and was gone by the time any host-side task could look for
 *    it. The task therefore always reported "nothing generated".
 *  - Its copy source was `build/outputs`, which is inside `build/`; as a
 *    declared input that makes Gradle demand a dependency on every producer of
 *    that directory, and at execution time it copied build OUTPUT DIRECTORIES
 *    (`apk/`, `androidTest-results/`) into `src/main/generated/baselineProfiles/`,
 *    i.e. a benchmark quietly writing build artefacts into a source tree.
 *
 * What replaces it, in order of preference:
 *
 *  1. apply `androidx.baselineprofile` 1.4.1 to this module and set
 *     `baselineProfile { saveInSrc = true }`. That is what the plugin exists
 *     for and it does the pull correctly. It was left out because it also
 *     brings `com.google.testing.platform:core-proto` plus a protobuf runtime
 *     into the build and applies consumer-side wiring to `:app`, which this
 *     module does not own.
 *  2. `adb pull` the file from the path above WHILE the generator run is still
 *     on the device, then copy it to
 *     `app/src/main/generated/baselineProfiles/baseline-prof.txt`.
 *
 * See `benchmark/README.md`, which documents both.
 */
