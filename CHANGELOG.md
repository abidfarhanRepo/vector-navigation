# Changelog — Vector Navigation UX Overhaul

All changes below are part of the Waze-primary navigation-UX overhaul
(ADR-0062). Every item is committed. Vision-inference spend this session: **$0.0075**
(hard $2 cap enforced).

## 2026-07-19 (overnight pass)

### Vector Web (`vector-web/static/index.html`) — map-style source
- **W2/W4 road hierarchy + emphasis:** layered roads (minor → major casing →
  major → motorway/trunk), widths **interpolate with zoom** (Waze-like fatten).
- **W4 label decluttering:** place labels `minzoom: 11`, street-name labels
  `minzoom: 14` — driving view stays clean.
- **Waze-matched dark palette:** deeper navy bg, cyan/blue road ramp, brighter
  halos.
- **3D / pitch toggle:** new "3D view" button eases to pitch 45° / bearing 25°
  (matches mobile driving perspective).
- **Route casing:** every route line now has a dark casing (bold ribbon).
- JS syntax validated with `node --check`.

### Documentation
- ADR-0062 updated through section (vi) recording all waves + honest limits.
- This CHANGELOG created.

### Known limits (honest)
- Mobile consumes the map style from the self-hosted vector-web backend; Web
  style improvements take effect when the backend serves the updated style.
- Final side-by-side visual sign-off pending Galaxy S24 tailnet reachability.
- Deferred (backend / separate workstreams): multi-route backend wiring,
  Favorites/Home/Work management UI, live autocomplete, stops-along-route,
  real lane guidance.
