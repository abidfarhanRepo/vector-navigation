# ADR-0063 — Web-first distribution via installable PWA (native apps deferred)

- **Status:** Accepted
- **Date:** 2026-07-20
- **Deciders:** Vector architecture (d3), Web/frontend (d2-web), Product (d2-product)
- **Supersedes:** [adr-0060](adr-0060-mobile-flutter-client.md) (Android-first Flutter client)

## Context

The long-term mission brief calls for native-quality **Android + iOS** apps. ADR-0060
(2026-07-17) accepted an Android-first Flutter client (`vector-mobile`) toward that goal,
and a later working session deleted adr-0060 and removed `vector-mobile` from the
registry — a governance violation (ADRs are immutable; reversal requires a *superseding*
ADR, which did not exist). The web vertical (`vector-web` + engines) is already the
proven, self-hosted, single-origin surface; the PWA primitives (service worker, web app
manifest) are standard and require no new runtime dependency.

## Decision

- **Ship Vector to phones/desktops first as an installable Progressive Web App** built on
  the existing `vector-web` origin. No new repo; the PWA assets live in `vector-web/static`
  (manifest, service worker, generated icons) and the web composition root serves them
  publicly (no token gate), consistent with `adr-0059` (self-hosted, no third-party).
- The service worker precaches the app shell for **offline launch**, but serves **all
  live APIs** (`/route`, `/navigate`, `/traffic`, `/tiles`, `/glyphs`, `/search`,
  `/incidents`, …) **network-only** — navigation and traffic must never be served stale
  from cache.
- Icons are **generated from source** (`vector-web/scripts/gen_icons.py`, pure stdlib) so
  the PWA ships **zero third-party icon/font assets** — preserving the self-hosted
  principle and avoiding the binary-blob dependency problem.
- **Native Android/iOS apps are explicitly deferred** to a later phase. When revived, the
  PWA's single-origin contract (`/route`, `/navigate`, `/traffic`, `/tiles`, `/glyphs`,
  `/search`) is the stable backend boundary the native clients consume.

## Consequences

- `vector-mobile` (dart) is **not** re-created; adr-0060 is retained as *Superseded* (not
  deleted) for historical record. The registry's `mobile-app-svc` / `vector-mobile` entries
  remain removed (the PWA needs no mobile-specific service).
- Mobile users install Vector from the browser (Add to Home Screen / Install) — no app
  store submission required for the first delivery.
- **Deviation from the brief:** the mission's "native Android + iOS" requirement is not
  met by this phase. This is a deliberate, user-approved scope decision (web-only PWA
  now, native deferred), recorded here so the brief's intent is preserved and
  re-activatable later.
- Test surface: `vector-web/tests/test_pwa.py` asserts the manifest/sw/icons are served
  publicly with correct content-types and that `index.html` wires the PWA.

## Alternatives considered

- **Reinstate Flutter (adr-0060):** highest cross-platform code reuse, but needs the
  Android SDK toolchain active and a separate iOS build on a Mac; more surface to build
  and gate before any user value. Deferred.
- **True native split (Kotlin + SwiftUI):** best per-platform quality, highest effort,
  iOS blocked without the user's Mac. Deferred.
- **Web + Capacitor wrap:** fast app-store shell, but adds a native build/runtime layer
  and store-submission overhead for marginal gain over a pure PWA at this stage.
  Deferred in favor of the simpler, dependency-free PWA.
