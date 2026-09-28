# `:benchmark` — macrobenchmarks for Vector

Test-only. This module builds an instrumentation APK that drives `:app` from
another process, and **nothing here is part of a release artefact**: it is a
`com.android.test` module, so `:app:assembleRelease` and `:app:bundleRelease` do
not depend on it and its code cannot reach the APK a user installs.

| class | what it measures |
|---|---|
| `StartupBenchmark` | cold start, with and without the baseline profile |
| `InteractionBenchmark` | frame timing for search, results scrolling, the settings sheet, and the map |
| `BaselineProfileGenerator` | generates the baseline profile — a manual/CI step, not a measurement |
| `BenchmarkConfig` | the device setup every measurement above needs (animations off, location granted) |

## Running it

Toolchain first, as in the root `README.md`:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

An emulator or device must be up, and the deterministic fixture backend must be
serving on port 9003 (see `scripts/qa-fixture-server.py`). The emulator reaches
the host at `10.0.2.2`.

```bash
VECTOR_TOKEN="$(grep '^VECTOR_WEB_TOKEN=' ../.env | cut -d= -f2-)"

./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -PvectorBase=http://10.0.2.2:9003 \
  -PvectorToken="$VECTOR_TOKEN"
```

### Why the two `-P` properties are not optional here

`connectedBenchmarkAndroidTest` is paired with `:app`'s **release** variant (the
`benchmark` build type in `build.gradle.kts` uses `matchingFallbacks = listOf("release")`),
because only the release APK carries a baseline profile to compile against and
only the release APK is minified and non-debuggable. Building that variant runs
`:app`'s `verifyReleaseConfiguration`, which refuses to compile a release
without a backend and a token — deliberately, because both have shipped
unconfigured before. A benchmark run is not a reason to waive it: pass the same
properties a release build takes.

### One class, or one method

```bash
# one class
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -PvectorBase=http://10.0.2.2:9003 -PvectorToken="$VECTOR_TOKEN" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.vector.android.benchmark.StartupBenchmark

# one method
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -PvectorBase=http://10.0.2.2:9003 -PvectorToken="$VECTOR_TOKEN" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.vector.android.benchmark.StartupBenchmark#startupColdWithoutCompilation
```

### The debug variant

`connectedDebugAndroidTest` exists too, but it is not a substitute.
`CompilationMode.Partial` requires a baseline profile in the APK and the debug
APK has none, so `StartupBenchmark`'s primary method fails there; and the app
under test is debuggable, which macrobenchmark refuses to report results for
without `suppressErrors=DEBUGGABLE`. Use it only with `CompilationMode.None` and
for shape, never for a number anyone quotes.

## Reading the output

Each test prints one block per iteration and a summary like this:

```
StartupBenchmark_startupColdWithBaselineProfile
    timeToInitialDisplayMs   min 1391.3,  median 1486.5,  max 1574.3
```

Those three numbers are a real run of this class on the emulator described
below, kept verbatim because on an emulator they are the honest order of
magnitude (about 1.4-1.5 s to first frame for the release build on this host).
They are NOT a budget: see the emulator caveats.

- **`StartupTimingMetric`** — `timeToInitialDisplayMs` is launch-intent-received
  to the app's first frame. Vector never calls `reportFullyDrawn()`, so
  `timeToFullDisplayMs` is not populated.
- **`FrameTimingMetric`** — `frameDurationCpuMs` (how long the CPU took per
  frame) and `frameOverrunMs` (how far past the deadline each frame finished),
  at p50/p90/p95/p99. **Read the p99**: one 40 ms frame in a scroll is what a
  driver sees as a stutter, and a p99 that grows while the p50 holds is the
  signature of a jank source being added.

### What this app's release APK actually reports (observed)

A full `InteractionBenchmark` run on the emulator (4/4 tests passing) produced
only `frameCount`, with **no** `frameDurationCpuMs` and no `frameOverrunMs`:

```
openAndCloseSettingsSheet   frameCount p50 3
openSearchAndTypeQuery      frameCount p50 19
panAndZoomMap               frameCount p50 5
scrollSearchResults         frameCount p50 39
```

They are counted frames and nothing more, and that is worth reading as a
finding rather than a formatting quirk. `FrameTimingMetric` derives the
durations from the platform's frame timeline for the target process, and this
release APK is neither debuggable nor `<profileable android:shell="true"/>`, so
macrobenchmark cannot attribute frames to it — the same class of gap as the
`profileinstaller` one in `StartupBenchmark`. [INFERENCE] on the cause; the
absence of the two timing metrics is verbatim from the run's
`benchmarkData.json`. The fix would be a `<profileable android:shell="true"/>`
element in the app's manifest (or benchmarking a debuggable variant with
`DEBUGGABLE` suppressed) — both outside this module.

The machine-readable results and the perfetto traces from the run are written
under `benchmark/build/outputs/`; the additional test output AGP pulls back from
the device (including a generated baseline profile, if the generator ran) is
under `benchmark/build/outputs/androidTest-results/connected/`.

## Emulator caveats, and the errors that are suppressed

Every run begins by putting the device into the state the metrics assume — see
`BenchmarkConfig`: animation scales at 0, type scale at 1.0, location already
granted. That is not tidiness. An animation drawn during a measured block is a
frame the platform produced, not the app; a type scale left at 2.0 by another
harness (the device matrix in `scripts/run-device-matrix.sh` sets one) changes
every line count and row height, so the run measures a differently-shaped app;
and an ungranted location permission puts a system dialog inside every cold
start.

Macrobenchmark marks a run's results with errors that mean "these numbers are
not representative", and by default it FAILS the run rather than print them.
`androidx.benchmark.suppressErrors` is the documented way to accept a
non-representative host, and it is passed as an instrumentation argument:

```bash
-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Suppressing is honest here because of what each suppressed error actually
asserts — and only these are suppressed:

| error | what it asserts | why suppressing it is not hiding a defect |
|---|---|---|
| `EMULATOR` | The device is an emulator, so its timings are not a phone's. | Nothing about the app is wrong; the host is. These numbers are quoted as indicative, never as a release gate. |
| `LOW-BATTERY` | Battery was below the threshold the library wants for stable clocks. | The emulator's battery is virtual and its clocks are the host's; there is no thermal/power state being reported. |
| `NOT-AOT-COMPILED`, `JIT-ENABLED` | The app under test is not ahead-of-time compiled. | Raised by `startupColdWithoutCompilation` **on purpose**: `CompilationMode.None` exists to show what the baseline profile is worth. The primary method (`CompilationMode.Partial`) is AOT-compiled, and if that ever stopped being true the metric would still be labelled — this pair of methods exists to make the difference visible. |

`DEBUGGABLE` is deliberately NOT in the list. It would only be raised if the app
under test were a debug build, and the only reason to benchmark one is to avoid
fixing the release build. If a run starts failing with `DEBUGGABLE`, the pairing
with `:app:release` has broken — that is a real defect in the module, not a
caveat to suppress.

**Frame timings on this host are indicative only.** The emulator runs with
`-gpu swiftshader_indirect` (the default GPU mode crashes this host), so every
frame is produced by a software rasteriser on an emulated CPU. That distorts
`FrameTimingMetric` far more than it distorts cold start: it inflates the CPU
cost per frame and removes the real GPU's async behaviour. Use the frame numbers
to compare a change against a baseline measured on the SAME host and settings,
never as an absolute budget.

## Generating a baseline profile

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -PvectorBase=http://10.0.2.2:9003 -PvectorToken="$VECTOR_TOKEN" \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.vector.android.benchmark.BaselineProfileGenerator
```

It walks the same journeys the benchmarks measure (it calls the same helpers, so
the two cannot drift) and repeats them until the profile stops growing. It
**passes** against this project's release APK on API 35 (observed: `Finished 1
tests`, 0 failed, `BUILD SUCCESSFUL`), and it writes the collected profile on the
device at:

```
/sdcard/Android/media/dev.vector.android.benchmark/additional_test_output/\
    BaselineProfileGenerator_generate-baseline-prof.txt
```

**AGP does not pull that file back for a connected run** — observed, not assumed:
nothing matching `*baseline*` appears anywhere under `benchmark/build` after the
run, and the device copy is gone by the time a host-side task could look for it
(AGP removes the additional-test-output directory). So the profile has to be taken from the device DURING the run, which
is exactly what the `androidx.baselineprofile` plugin's `baselineProfile {
saveInSrc = true }` does; that plugin was left out here deliberately (it drags
`com.google.testing.platform:core-proto` and a protobuf runtime into the build,
and it also applies consumer-side wiring to `:app`, which this module does not
own). Two honest options, neither of which this module can decide for you:

1. apply `androidx.baselineprofile` 1.4.1 to this module and set
   `saveInSrc = true`, which materialises the file below automatically; or
2. keep this module as it is (nothing is wired for the pull - an earlier
   `syncBaselineProfile` task was removed for the reasons in
   `benchmark/build.gradle.kts`) and pull the file by hand while the run is
   still on the device, then place it at:

That is this module's copy. To make the profile part of the shipped binary it
must be committed into the app's own source set —
`app/src/main/generated/baselineProfiles/baseline-prof.txt` — which AGP compiles
into `assets/dexopt/baseline.prof`. This module does **not** do that copy: it
would mean a benchmark run silently rewriting the sources of the released app.

This is a manual/CI step and is not wired into any build. It needs a device, it
is slow and converges by repetition, and it writes a source file — a build that
rewrites its own inputs is not reproducible.
