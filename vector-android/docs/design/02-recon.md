# Recon — the codebase before the redesign

Two read-only surveys were run before any production file was touched. This is
the consolidated record of what they found, kept because most of the constraints
below are still binding.

## 1. Shape of the client

* Single-activity, single-Compose-tree app. `MainActivity` is an **adapter only**:
  it owns the MapLibre `MapView`, the fused location client, the frame loop and
  one `MutableState<UiState>`, and performs the `Action`s returned by pure
  engines (`NavSession`, `WalkNavSession`, `MapCamera`, `Settings`,
  `RouteGeometry`).
* `setContent { VectorApp(mapView, ui, …27 callbacks…) }` → `VectorChrome(ui, c, …)`
  resolves the theme and composes every overlay in one `Box` over an
  `AndroidView(mapView)`.
* **Overlay ownership is the layout invariant**: every element belongs to exactly
  one `Phase` (EXPLORE / PREVIEW / NAVIGATING) or to a boolean flag (`searching`,
  `showSteps`, `showSettings`, `showPaywall`), and top- and bottom-anchored groups
  are each a single `Column`, so mutually exclusive surfaces cannot be composed
  together. `NavUiTest` holds it: *no two text elements overlap in any phase*.
* No navigation graph, no Fragment, no ViewModel, no `Dialog`/`Snackbar`/`Toast`,
  no `strings.xml`. All copy is Kotlin literals, all icons are `Canvas` paths, all
  markers are bitmaps registered into the MapLibre style, and both palettes lived
  in code.
* Walking is a fourth surface reached only through `ui.walk != null` (null on
  every car journey) — the whole isolation mechanism between the drive and foot
  paths.

| file | lines | what it is |
|---|---|---|
| `MainActivity.kt` | 4545 | entry point, permissions, frame loop, style/marker registration, status and error strings |
| `NavUi.kt` | 5196 | the entire overlay UI: ~45 private composables |
| `VectorStyle.kt` | 3189 | cartography: 28-role `Palette`, DARK/LIGHT, route and callout builders |
| `NavState.kt` | 1166 | `Phase`, `GpsHealth`, `UiState` (85 fields), pure rules |
| `VectorApi.kt` | 1115 | the HTTP client for `/tiles`, `/glyphs`, `/search`, `/navigate`, `/foot`, … |
| `VectorIcons.kt` | 683 | hand-drawn icon set |
| `Settings.kt` | 525 | settings, recents, Home/Work, journey persistence |
| `VectorMarkers.kt` | 430 | MapLibre marker bitmaps |
| `VectorTokens.kt` | 333 | the four original scales |

## 2. What the redesign had to keep

**Gradle/tooling contracts.** Task names (`:app:assembleDebug`,
`:app:testDebugUnitTest`, `:core-geo:test`, `:core-geo` stays
`jvmToolchain(17)` and Android-free), the `-P` properties (`vectorBase`,
`vectorToken`, `revenueCatKey`, the four signing ones,
`vectorAllowUnconfiguredRelease`), `BuildConfig.API_BASE/API_TOKEN/REVENUECAT_KEY`,
the per-ABI APK naming every device script looks for, `app/build/traces/`, the
generated `network_security_config`, and R8 keeps for `dev.vector.android.car.**`
and the three vendor packages.

**Test contracts.** `RestyleCallSiteTest` reads `MainActivity.kt` **as text**
(exactly five `applyStyle(` sites, none in `onResume`). Robolectric qualifiers
`sdk=34`, `w412dp-h915dp-xhdpi`. The semantics asserted by name:
`Where to?`, `Settings`, `Done`, `Start`, `Exit`, `End navigation`, `Rerouting…`,
`Dark`, `Sun at`, `Now`, `Clear the search`, `Close the search`, `Nothing found`,
`RECENT`, `Searching…` — and node **bounds**.

**Script contracts.** Six device harnesses drive the UI **by accessibility text**;
`uiprobe.py mapcolor` assumes no sheet is open in the 66–74 % height band;
`verify_persistence.sh` splits light/dark at luma 128 and read the old daylight
ground (`#e0e6ea`), which the cartography change updated.

## 3. What the redesign changed, and why it was safe to

* `Chrome` (20 roles, two literal instances) → deleted; the palette is
  `VectorColors` through `VectorTheme`. Every screen reads roles.
* `VectorTokens.Type` → deleted; the type scale is `VectorTypography` (families,
  weights, line heights and tracking, not bare sizes).
* `VectorTokens.Space`/`Radius` → numeric ladders (`s2`…`s48`, `r0`…`r32`),
  because a named ladder has no step to give a 20 dp gap.
* Cartography values → refreshed in the client **and both production viewers**,
  keeping every relationship the existing tests assert.

## 4. Demo/determinism affordances the QA loop used

* `-PvectorBase` → `BuildConfig.API_BASE`; `adb reverse` or `10.0.2.2` for the
  emulator.
* `MockDrive` (debug-only CSV replay through `setMockMode`) and the trace intent
  extras (`vectorTrace`, `vectorDest`, `vectorSpeedup`) — the device harness's
  deterministic route replay.
* `TraceExportTest` writes `app/build/traces/*.csv` + `destinations.txt`.
* `VECTOR_STYLE_DUMP` / `VECTOR_ROUTE_DUMP` for the cartography bench.
* `scripts/qa-fixture-server.py` (added by this work) — a stdlib-only backend
  serving the **real** pre-baked Doha MVT tiles from
  `.bootstrap-cache/v76/calib/z6-13/tiles` and the **real** glyph PBFs from
  `vector-tile-server/glyphs`, plus deterministic `/search`, `/navigate`,
  `/foot`, `/reverse`, `/traffic`, `/speed`, `/road` fixtures. Deterministic by
  construction: no randomness, no clock in any payload, and `--fail-rate` hashes
  the request line rather than rolling dice.
