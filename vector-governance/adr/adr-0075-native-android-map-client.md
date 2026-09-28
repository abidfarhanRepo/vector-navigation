# ADR-0075 — A native Android map client (supersedes the map-display half of ADR-0063)

- **Status:** Accepted
- **Date:** 2026-09-08
- **Deciders:** Product (user, directly), D3 Architecture, D2 Web-frontend
- **Supersedes:** [adr-0063](adr-0063-web-pwa-first.md) — **in part**: the deferral
  of native clients for **map display and navigation**. ADR-0063 remains in force
  for the PWA as a distribution channel, which is not withdrawn.
- **Supersedes:** [adr-0070](adr-0070-native-capture-shell.md) — the Capacitor
  shell, which was Proposed and never ratified. A Capacitor WebView wrapping the
  existing client is no longer the native strategy for either display or capture.
- **Relates to:** [adr-0060](adr-0060-mobile-flutter-client.md) (Flutter,
  superseded by 0063 and never built — no Flutter source has ever existed).

## Context

The product direction changed on 2026-09-08. The user's assessment of the
shipped client was that "the fluidity, design, responsiveness, map data,
navigation, everything needs more fixing", and the follow-on instruction was to
**build the mobile map infrastructure from the ground up rather than package the
web app**.

That instruction is supported by measurement taken the same day, not just
preference. Using the new headless harness (`vector-web/tests/e2e/`) against a
real 10.4 km Doha route with GPS simulated at 1 Hz:

| run | fps | CPU/fix | script |
|---|---|---|---|
| idle map | ~56 | — | 0.07 s |
| real-time 1 Hz drive | ~15 | 67 ms | 0.22 s |
| on-route navigation drive | ~13 | 59 ms | 0.51 s |

The decisive number is the split: **scripting is ~0.5 s inside ~3.6 s of task
time**. Roughly 95% of the per-fix cost is map re-render, not application logic.
Two consequences follow:

1. Optimising the JavaScript client buys almost nothing — the cost is not there.
2. A Capacitor shell keeps the same renderer and adds a WebView on top of it.
   It cannot fix the complaint; it inherits it.

Meanwhile the **backend is not the problem** and is already the right shape: it
serves standard MVT tiles, self-hosted SDF glyphs, an OSRM-backed `/route` and
`/navigate`, `/search`, `/along`, `/traffic` and `/speed`. Any client that speaks
those contracts gets the whole platform. The web client is one such client; it
does not need to be the only one, and nothing about replacing it touches the
routing, tile, geocoder or learning services.

## Decision

Build a **native Android client in Kotlin using MapLibre Native**, as a new
first-class repo `vector-android/`, consuming the existing service contracts
unchanged.

Specifically:

- **Rendering:** MapLibre Native Android (OpenGL/Vulkan), not a WebView. Same
  style specification and the same MVT tiles the web client already consumes, so
  the basemap work in `vector-tile-gen` is shared rather than duplicated.
- **Language/UI:** Kotlin, Jetpack Compose for chrome, MapLibre's `MapView` for
  the map surface.
- **Geometry:** a pure-JVM module `core-geo`, a direct port of
  `vector-web/static/js/geo.js` (ADR-less utility, ticket 36) — perpendicular
  projection onto route segments, along-route distance, dead reckoning. Pure
  Kotlin so it is unit-testable on the JVM with no device and no emulator.
- **Location:** the platform `FusedLocationProviderClient`, not the W3C
  geolocation shim, so accuracy, speed and bearing arrive as first-class fields.
- **Distribution:** unchanged by this ADR. The PWA continues to exist.

### Why MapLibre Native and not Flutter or React Native

- It is the **same renderer family and the same style spec** as the current web
  client, so the tile pipeline, glyph set and style semantics carry over. A
  Flutter or RN choice adds a binding layer over the identical native library.
- ADR-0060's Flutter decision was superseded and **never implemented** — there is
  no Flutter code, no `pubspec.yaml`, nothing to preserve. There is no sunk cost
  arguing for it.
- The immediate target is a single Android device (Galaxy S24 Ultra). Paying a
  cross-platform abstraction cost now, for an iOS build nobody has scheduled, is
  the wrong trade.

## Consequences

**Positive**

- GPU rendering with no WebView layer; the S24 Ultra's 120 Hz display becomes
  reachable, where the WebView measurement was ~13–15 fps under a software
  rasteriser.
- Native location, native lifecycle, and a real path to background capture —
  which is what ADR-0070 wanted and could not get cleanly from a WebView.
- Navigation logic gets unit tests on the JVM instead of the current situation,
  where the entire navigation surface is asserted by grepping `index.html` as
  text (which is how a 31×-wrong off-route threshold survived, ticket 36).

**Negative / accepted costs**

- **Two clients now exist.** The web client is not deleted, so a feature can
  diverge. Mitigated by the split: all map DATA and all routing/search logic stay
  server-side; only presentation is duplicated.
- **iOS is not addressed.** Explicitly out of scope; revisit with its own ADR.
- **The Shipaton Capacitor work is spent.** The APK built on 2026-09-08 (first in
  the project's history) is retained as a fallback distribution, not as the
  strategy. `vector-shipaton-2026` is unaffected and stays pinned.

**Neutral**

- No service contract changes. `vector-routing`, `vector-geocoder`,
  `vector-tile-server`, `vector-traffic` and `vector-learning` are untouched.

## Compliance

- OSM attribution (ODbL) MUST be visible in the native client, as it is in the
  web client. MapLibre's attribution control satisfies this and MUST NOT be
  disabled.
- The privacy invariants are unchanged and are **server-side** — 200 m endpoint
  truncation and the k=5 promotion floor (adr-0065, adr-0066) do not move to the
  client. A native client MUST NOT ship a collection path that bypasses them.
- No background location in the first release, consistent with the guardrail
  carried since ADR-0070.
