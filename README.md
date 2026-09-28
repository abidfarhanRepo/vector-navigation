<div align="center">

# Vector

**Self-hosted, privacy-first navigation for Qatar — that gets you from the car to the door.**

Free gets you there. Pro gets you from the car to the door.

</div>

---

## The problem

Navigation apps end at a pin on a road. In Doha that is the wrong place to stop.

Doha is above 40 °C for five months of the year. The part of a journey that
decides your day is not the drive — it is the walk from wherever you parked to
the door you actually need. Mainstream navigation treats that walk as a rounding
error, and treats *where the sun is* as no part of the problem at all.

The second problem is quieter. Navigation is the most intimate telemetry a
person emits: every place you go, when, how often. The mainstream answer is to
send it all to an advertising company. Vector's answer is that the server can be
yours.

## What Vector is

A native Android navigation client and a self-hostable backend. No account, no
tracking, no ads, no third-party analytics SDK. The app talks to a Vector server
whose address is compiled into the build — which for a self-hosted stack is
whichever machine you pointed it at.

It is built for Qatar specifically: Arabic and English street names, a 3D basemap
of the country, surveyed traffic-signal and speed-camera locations, and a
walking model that knows where the sun is.

### Who it is for

- **Drivers in Doha** who want turn-by-turn that says road *names*, not road classes.
- **People walking the last few hundred metres** in heat that makes shade a decision, not a preference.
- **Anyone who would rather their journeys not be a product.** The whole stack runs on hardware you control.

---

## Features

Everything listed here is implemented and exercised by tests. The
[Limitations](#limitations-read-this) section is not a footnote — it is the
other half of this list, and it is deliberately specific.

### Driving

- Turn-by-turn navigation with voice, in English or Arabic street names.
- **Road vocabulary that speaks like a person** — "Bear left to stay on the road", not "stay on the tertiary road".
- Three route alternatives, ranked, with the real time cost of each.
- **Surveyed traffic signals** on your route — 899 catalogued nationally, 430 associated to the graph.
- **Speed cameras** — 133 catalogued, announced once, inside a 420 m window.
- Speed and speed-limit display, lane guidance data, off-route detection and rerouting.
- A 3D navigation camera with a 60° pitch cap and speed-banded zoom.

### Walking

- A full second navigation mode: enter, follow, voice, off-route, reroute, arrive.
- A **pedestrian graph** distinct from the road graph — 1,108,269 nodes and
  2,374,052 edges, with 14,465 crossing facts and 8,707 barriers.
- **Barriers are honoured.** 2,247 locked or private gates remove 3,874 edges. A
  locked gate genuinely splits a pedestrian network, and Vector's graph says so.
- **Honest refusal.** If the footpath data cannot connect two points, Vector
  refuses and says why. It does not draw a straight line and it does not
  silently fall back to car routing.

### Shade (the differentiator)

- Solar position from a full **NOAA closed-form implementation** — Julian
  centuries, equation of centre, nutation and aberration, obliquity, the 5-term
  equation of time, and NOAA's piecewise refraction fit. Deterministic, offline,
  validated against published figures (Doha solar-noon altitude 68.4° ± 0.5).
- Per-segment sun exposure from segment bearing against sun azimuth, with an
  assumed façade height by road class.
- The walking line is drawn **amber where the model puts it in the sun and teal
  where it does not** — which is also where the app icon comes from.
- A sun slider that scrubs the day and recolours the route live, because the
  shade model is a pure function of (geometry, tags, instant).

### The last mile

- A **journey**: drive → park → walk, composed as one object rather than a route
  that ends at a kerb.
- Parking comes from a real POI corridor query, not invention. Candidates are
  ranked by the **walk**, not by distance to the car — a car park 80 m away on
  the far side of a compound wall is a 600 m walk.
- A **cooler-route** choice: the shadiest of the walks the destination's own car
  parks produce, with what it costs you in minutes stated plainly.

### 3D basemap

- A national Qatar 3D bake: **18,314 tiles, 228,872 building instances over
  152,029 distinct footprints**, z6–z15, published as an immutable, digest-verified
  release.
- Releases are switched by a single atomic pointer swap with a reversible
  rollback, an append-only audit log, and eight injected crash-recovery points
  under test.

---

## Monetization — how RevenueCat is used

Vector Pro is a real product boundary, not a feature flag on an artificial limit:

> **Free: Vector gets you there. Pro: Vector gets you from the car to the door.**

Free includes both navigation modes, the 3D map, and the shade estimate while
you walk. **Pro** sells the pre-trip last-mile surface: the drive→park→walk
journey card, the sun slider, the shaded/exposed map overlay, and the
cooler-route choice.

| | |
|---|---|
| SDK | `com.revenuecat.purchases:purchases:10.21.1` (native Kotlin) |
| Entitlement | `vector_pro`, pinned by test |
| Packages | `$rc_monthly`, `$rc_annual`, `$rc_lifetime` |
| Implementation | `vector-android/app/src/main/java/dev/vector/android/pro/` |
| Tests | 42 dedicated JVM tests, plus gating assertions in three more suites |

Design points worth a reviewer's time:

- **`ProAccess.kt` is pure.** The entitlement state machine has zero Android and
  zero SDK imports, so every branch — grace window, expiry precedence, relock —
  is a unit test rather than a device session.
- **A missing key grants everything.** An unconfigured build resolves to
  `ProStatus.UNCONFIGURED`, which unlocks every feature and compiles *no paywall
  at all*. A self-hosted user who never signs up for RevenueCat gets the whole
  app. This also makes the classic "paywall nobody can unlock" bug structurally
  unreachable.
- **A known expiry always beats the offline grace.** A subscription that ended
  on Tuesday does not get another week because the phone was in a tunnel.
- **The cache is seeded synchronously before the first frame**, so a paying user
  never sees an upsell flicker on cold start.
- **The release build refuses a Test Store key**, non-waivably, because a
  simulated purchase in a shipped binary sells nothing.

Purchases in this submission run through **RevenueCat's Test Store**. Vector has
no app-store listing — the Next Gen Award explicitly does not require one — so a
Test Store key is the honest configuration, and the build system enforces that
it can never be mistaken for a shipping one.

---

## Architecture

A polyrepo in one repository. The client is native Android; every backend
service is small, independently testable, and speaks HTTP.

```
Android client (Kotlin, Jetpack Compose, MapLibre GL)
  │
  └── HTTPS ──> vector-web ─┬─> vector-routing   /route /navigate /foot /footz /eta
                            ├─> vector-geocoder  /search /along
                            ├─> vector-traffic
                            └─> vector-tiles     /tiles/{z}/{x}/{y}.mvt
```

| component | what it is |
|---|---|
| `vector-android/` | the client. `app/` (Compose UI, navigation sessions) + `core-geo/` (pure Kotlin geometry, solar, walking, camera — no Android deps, so it is all unit-testable) |
| `vector-routing/` | road + pedestrian graphs, cost models, maneuver interpretation, signals, cameras, barriers |
| `vector-geocoder/` | search and corridor (`/along`) queries |
| `vector-tile-gen/` | the bake: OSM → GeoJSON → MVT, plus the release publisher and its rollback |
| `vector-web/` | the edge: auth, proxying, static assets |
| `vector-ingestion/` | the OSM classifier that owns the access model (car/foot/barrier decisions are made once, here) |
| `vector-governance/` | ADRs, the engineering constitution, the milestone roadmap |

Two decisions that shaped everything else:

1. **`core-geo` has no Android dependencies.** Solar position, shade estimation,
   route following, walking progress and camera policy are pure Kotlin. That is
   why 675 of the tests need no device and no emulator.
2. **Routability is decided once, at ingestion.** `vector-ingestion` owns the
   OSM access model; the router reads the result rather than re-deriving it. A
   second classifier is how a map and a router come to disagree about whether a
   gate is locked.

---

## Running it

### The backend

```bash
git clone https://github.com/abidfarhanRepo/vector-navigation.git && cd vector-navigation
cp .env.example .env        # set VECTOR_REGION / VECTOR_PBF_URL / VECTOR_BBOX
./bootstrap.sh              # downloads OSM, builds graph + tiles + search index
docker compose up -d        # web at http://localhost:8088
```

`bootstrap.sh` is the only thing that touches raw data and is idempotent. Every
secret is generated at first boot; the stack refuses to start with a default
token unless `VECTOR_DEV=1`.

Expect the first bake to take a while — it is converting a national OSM extract.

### The Android app

Requires JDK 21 and Android SDK 35. Point Gradle at the SDK with either
`ANDROID_HOME` or a `local.properties` containing `sdk.dir=/path/to/Android/Sdk`
(that file is machine-specific and deliberately not committed).

```bash
cd vector-android

# Debug, against a local stack (adb reverse), with the RevenueCat Test Store:
./gradlew :app:assembleDebug \
  -PvectorBase=http://127.0.0.1:8088 \
  -PvectorToken="$VECTOR_WEB_TOKEN" \
  -PrevenueCatKey="$REVENUECAT_TEST_KEY"

# Release, against a real backend:
./gradlew :app:assembleRelease \
  -PvectorBase=https://your-host.example.com \
  -PvectorToken="$VECTOR_WEB_TOKEN"
```

`-PvectorBase` is not optional for a release build and the build will refuse
without it: the default is the emulator loopback, which installs happily and
then renders a blank map on a handset. That refusal exists because it shipped
once.

### Tests

```bash
# Android — 1,790 tests, no device or emulator required
cd vector-android && ./gradlew :app:testDebugUnitTest :core-geo:test

# Routing — 625 tests
cd vector-routing && PYTHONPATH="$PWD/src:$PWD/vendor" python3 -m unittest discover -s tests
```

The Android UI tests run the real Compose runtime and the real Android framework
on the JVM under Robolectric, with the MapLibre surface stubbed. They assert the
semantics tree, not pixels.

> **One environment note that matters.** This project's Python services run on
> **3.11 in the container**. On a host running **Python 3.14**, PEP 649 makes
> annotations lazy, so a missing `typing` import passes every local test and then
> kills the service at import time in production. A green local Python suite is
> not evidence that a service will start. The deploy path smoke-imports the built
> image in the target interpreter before it replaces anything.

---

## Privacy and data handling

This is the product thesis, so it is specific:

- **No account, no login, no persistent user id.** There is no identity to
  attach a journey to.
- **No analytics, no crash-reporting SDK, no advertising identifier.** Grep for
  them; they are not there. The app's entire dependency list is 15 lines of
  `app/build.gradle.kts` — AndroidX, Compose, MapLibre, RevenueCat, and Google's
  fused location provider. There is no Firebase, no Crashlytics, no ad SDK.
- **Four permissions.** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`,
  `ACCESS_COARSE_LOCATION` — plus two Android Auto template permissions. There is
  **no background location permission**, no storage, no contacts, no camera.
- **One Google dependency, named honestly:** `play-services-location`, for the
  fused location provider. It supplies location fixes to the app; it is not
  analytics. A fully de-Googled build would need a different location source, and
  that is a legitimate thing to want.
- **Location is read while navigating and used on the device.** It is not
  uploaded to a Vector server for storage.
- **Searches, recent destinations and drive history are local**, and clearable
  in Settings.
- **RevenueCat receives an anonymous identifier** generated on the device. No
  `appUserID` is ever passed. The entitlement cache lives in its own prefs file,
  deliberately separate from app settings, so "forget my data" cannot revoke a
  purchase someone paid for.
- **The learned-map layer never learns from an individual.** Raw location sits
  in a 72-hour quarantine and is never what persists; what persists is
  per-segment evidence that has already cleared a k-anonymity floor of 5 distinct
  trips. Trip endpoints are truncated 200 m on-device *and again* server-side,
  and pseudonyms are per-trip, never per-device. The bindings are
  `vector-governance/adr/adr-0065` (ingest) and `adr-0066` (promotion).

Full policy: [`docs/PRIVACY.md`](docs/PRIVACY.md).

---

## Limitations (read this)

Vector's governing rule is that it does not claim what its data cannot support.
These are the current gaps, stated as precisely as the measurements allow:

| | |
|---|---|
| **Pedestrian connectivity is poor** | The foot graph is **2,811 components, the largest holding 32% of nodes**. Qatar has only 3,015 mapped crossing ways nationally, and arterials are the connective tissue. Vector mitigates with component-aware snapping and an honest refusal — it does **not** claim you can walk everywhere. |
| **Shade is an estimate, not shadow geometry** | It models street orientation against sun position with an assumed façade height by road class. There is no building, tree or obstruction geometry in the shade model. The UI says "estimated" and is tested to keep saying it. |
| **Shade does not choose the path** | The cooler route picks a shadier **car park** among the ones the destination already offers. It does not re-route the walk with an exposure penalty — that needs a shade-aware routing endpoint, which does not exist yet. |
| **This is a 3D basemap, not a height survey** | Only **1,929 of 228,872** building instances (0.84%) carry an explicit height. The rest derive from floor counts or a class default. |
| **Extrusion is unconfirmed on real GPU hardware** | The served tiles demonstrably contain tower heights (a West Bay z15 tile holds 58 buildings, 20 with explicit heights up to 245 m). A device session confirmed the 3D camera, the HUD and zero crashes — but a pitched camera over West Bay has not yet been photographed, so Vector does not claim it. |
| **No live traffic** | The learned-overlay engine reports `edge_count: 0`. It has consumed zero probes and will not pretend otherwise. |
| **No signal timing** | Qatar publishes no SPaT feed. Signals are surveyed **locations**. The timing model is a sealed type that cannot express a phase without a freshness window, so the app is structurally unable to invent one. |
| **Android only** | No iOS build exists. |

---

## Roadmap

1. **Confirm 3D extrusion on hardware** at the navigation camera, and settle
   whether the known z14/z15 tile-boundary discontinuities are visible or merely
   countable.
2. **Junction-aware camera framing**, ramps and bridges as meaningful 3D form,
   and cinematic approach and arrival.
3. **Pedestrian graph quality** — synthesised crossings and sidewalk-implied
   edges, which is the only lever that moves 32% meaningfully. This is a data
   programme, not an algorithm change.
4. **Genuinely shade-aware walking routes** — an exposure penalty in the
   pedestrian cost model, which means moving solar computation server-side.
5. Road landmarks from the 3D fabric (230 named towers are already available as
   a filter on height plus a name).

---

## Attribution and licence

Vector is released under the **MIT Licence** — see [`LICENSE`](LICENSE).
Map-data obligations that the MIT licence does not and cannot cover are set
out in [`NOTICE`](NOTICE).

Map data is **© OpenStreetMap contributors**, available under the
[Open Database Licence (ODbL)](https://www.openstreetmap.org/copyright). Place
data from [Overture Maps Foundation](https://overturemaps.org/). Rendering by
[MapLibre GL](https://maplibre.org/). Subscription infrastructure by
[RevenueCat](https://www.revenuecat.com/).

The MIT licence covers Vector's own source. It does not and cannot relicense
OpenStreetMap data, which remains ODbL; if you redistribute derived map data you
inherit ODbL's share-alike obligations. See [`docs/ATTRIBUTION.md`](docs/ATTRIBUTION.md).

## AI use

Development was assisted by Claude (Anthropic). See [`docs/AI-USE.md`](docs/AI-USE.md)
for what that covered and what it did not.
