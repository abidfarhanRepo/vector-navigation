# ADR-0062 — Waze-primary navigation UX overhaul (web viewer)

- **Status:** Accepted
- **Date:** 2026-07-19
- **Deciders:** Vector Web/frontend (d2-web), Product (d2-product)
- **Superseded by:** — (active; see adr-0063 for the client-distribution decision)

## Context

The M1 web viewer proved the self-hosted map + live-traffic-reroute architecture, but
its map styling and navigation chrome were generic/utility-grade rather than competitive
with a consumer Waze-class product. The `vector-web` CHANGELOG (2026-07-19 "overnight
pass", waves W50–W52) records a styling + UX overhaul to close that gap, prioritizing
**legibility at driving zoom** and a **Waze-matched dark palette** while keeping the
self-hosted, single-origin, no-third-party contract intact.

## Decision

Ship the UX overhaul as styling/behaviour changes to `vector-web/static/index.html`
(the MapLibre style source) plus supporting viewer chrome — no backend contract change:

- **Road hierarchy + emphasis (W2/W4):** layered roads (minor → major casing → major →
  motorway/trunk) with **zoom-interpolated widths** (Waze-like fatten at low zoom).
- **Label decluttering (W4):** place labels `minzoom: 11`, street-name labels
  `minzoom: 14` so the driving view stays clean.
- **Waze-matched dark palette:** deeper navy background, cyan/blue road ramp, brighter
  halos — consistent with `adr-0059` (self-hosted glyphs).
- **3D / pitch toggle:** a "3D view" button easing to `pitch: 45°, bearing: 25°` to
  match a mobile driving perspective.
- **Route casing:** every route line gets a dark casing (bold ribbon).
- JS validated with `node --check` (no behavioural test added to the engine tier).

## Consequences

- The Waze-matched styling is delivered via the self-hosted web backend; mobile (PWA,
  adr-0063) consumes the same style from the single origin.
- **Known limits (honest):** final side-by-side visual sign-off was pending device
  reachability (Galaxy S24 tailnet). Deferred backend work: multi-route backend wiring,
  Favorites/Home/Work management UI, live autocomplete, stops-along-route, real lane
  guidance.
- Vision-inference spend this wave: **$0.0075** (hard $2 cap enforced).

## Alternatives considered

- Forking a third-party style (e.g. a public dark style): rejected — violates the
  no-third-party principle (adr-0059) and the single-origin contract.
- Shipping native mobile first (adr-0060): deferred — see adr-0063.
