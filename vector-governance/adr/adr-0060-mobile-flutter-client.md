# ADR-0060 — Android-first Flutter mobile client (self-hosted, zero third-party)

- **Status:** Superseded (by [adr-0063](adr-0063-web-pwa-first.md))
- **Date:** 2026-07-17
- **Deciders:** Vector architecture (d3), Web/frontend (d2-web), Product (d2-product)
- **Superseded:** 2026-07-20 — native Flutter/iOS apps deferred; Vector ships as an
  installable web PWA first (adr-0063). This ADR is retained for history; do not delete
  (ADRs are immutable once Accepted — reversal requires a superseding ADR, not deletion).

## Context
The product goal is a complete, self-hosted Waze-like navigation ecosystem with **no
third-party services**. The web vertical (vector-web + engines) proved the architecture
(map display, routing, live traffic reroute) at a single self-hosted origin. To reach
users on phones (the primary Waze surface), we need a mobile client that:

1. Renders the **same self-hosted vector tiles** and **self-hosted glyphs** (no Mapbox/
   MapTiler/Google tiles, no public font CDN) — see the glyph 3rd-party leak fixed in
   adr-0059.
2. Calls the **same backend endpoints** (`/route`, `/navigate`, `/traffic`, `/tiles`,
   `/glyphs`) over one origin — no external geocoder/search/traffic feed.
3. Submits **crowdsourced GPS probes** to `POST /traffic` so traffic is self-gathered.

## Decision
- Build `vector-mobile` as an **Android-first Flutter** app (Dart). Flutter gives one
  codebase for Android + (later) iOS, reusing the MapLibre GL renderer already proven on
  web via `maplibre_gl`.
- Android-first because the user has an Android test device (arm24) on the tailnet and a
  full Flutter + Android SDK is installed on the host to build real APKs here.
- The app depends **only** on the single Vector origin. Its `glyphs` URL points at the
  backend `/glyphs` endpoint (self-hosted SDF fonts), never a public font service.
- The backend contract is frozen in `vector-mobile/API.md` and must stay in sync with
  `vector-routing`/`vector-web`/the tile server.

## Consequences
- New repo `vector-mobile` (lang: dart), registered in `registry.yaml` (repo + `mobile-app-svc`).
- CI gates on `flutter analyze` + `flutter test` + `flutter build apk --release`.
- iOS build is deferred to the user's Mac (desktop-28f2vlt) — same Dart code, separate
  signing/SDK; no architectural change required.
- No new third-party runtime dependency is introduced; the only external fetch at build
  time is the pinned Flutter SDK archive (a dev tool, not a runtime service).
