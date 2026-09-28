# ADR-0059 — Self-hosted glyphs (eliminate third-party font CDN)

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** Vector architecture (d3), Web/frontend (d2-web)

## Context
Vector's goal is a **fully self-hosted** navigation stack with **no third-party
services**. During the M1 web audit we found `vector-web/static/index.html` pointed
its MapLibre style `glyphs` at `https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf`
— a public MapLibre demo font CDN. That is a runtime third-party dependency (the
browser fetches fonts from an external host on every map load), violating the
"no third-party" principle even though the map *tiles* were already self-hosted.

## Decision
- Add a `GET /glyphs/{fontstack}/{range}.pbf` endpoint to `vector-tile-server`
  (Rust/axum) that serves SDF glyph PBFs from a local `VECTOR_GLYPH_DIR` (default
  `./glyphs`). CORS `*` + 1-day cache so the web viewer can load them.
- Generate those PBFs offline with `vector-tile-server/scripts/gen_glyphs.py`, a
  pure-Python generator (Pillow rasterize + numpy SDF + protobuf encode) driven by
  a **local TTF** (DejaVuSans, BSD-licensed). The produced PBFs are committed into the
  repo, so the running app makes **zero external calls** for fonts.
- Update `vector-web/static/index.html` to point
  `glyphs` at `<origin>/glyphs/{fontstack}/{range}.pbf`.

## Consequences
- The web viewer is now 100% self-hosted (tiles + glyphs + routing + traffic
  from one Vector origin). No third-party map/search/font service is contacted at runtime.
- Glyph coverage is ASCII (codepoints 32–126) by default; extend by running
  `gen_glyphs.py` with a broader `first`/`last` range and re-committing.
- The tile server gains a `glyph_dir` in `AppState` and two tests
  (`glyph_route_serves_pbf`, `glyph_route_missing_returns_404`).
- Build-time only, the source font is a local system TTF — no download at runtime.
