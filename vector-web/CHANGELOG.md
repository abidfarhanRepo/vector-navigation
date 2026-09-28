# Changelog — Vector Navigation UX Overhaul

## 2026-08-06 — A speedometer that is either right or blank

The app had a "speedometer" only in the sense that a number appeared in a corner. It
was hidden behind the GPS-follow toggle, it was 30px of status-line text, and — the
part that made the app feel unfinished — **it printed `0` whenever it did not know**.
`coords.speed` is null indoors, on the first fix, and on entire classes of device, and
the old code answered that by differencing successive positions, which at ordinary
urban fix noise invents 10-20 km/h for a parked car.

`static/index.html`, display-only. Nothing about trace capture, the privacy gate, trip
tokens or the upload queue is touched; the value was already in hand on every fix.

- **Large-numeral readout in the nav HUD** (46px, tabular figures), bottom-right in the
  same card language as `#navhead`. It is now part of the navigation lifecycle: planning
  a route puts it on screen, exiting takes it away, and it clears the bottom dock,
  the pins drawer and the voice panel on a phone.
- **`--`, never a fabricated zero.** No `speed` field, a fix worse than 50 m, or no fix
  for 5 s all render `--` in grey — visually unmistakable from a live green reading —
  with the reason on the meta line. The position-differencing fallback is gone.
  (50 m, not the trace gate's 25 m floor: a *stored* point's position must be precise,
  a Doppler speed degrades more gracefully. Past 50 m we decline rather than guess.)
- **Stationary reads a hard 0.** Below 0.6 m/s the receiver is inside its own noise
  floor, so the filter is reset instead of letting a parked car drift at 2 km/h.
- **Smoothed without lagging.** One-pole exponential filter, ~1.2 s time constant, with
  the coefficient computed from the actual gap between fixes so a 1 Hz and a 0.2 Hz
  stream behave alike. Steps over 8 km/h — braking, pulling away, a slip road — bypass
  the filter and snap, because those are the moments a driver is watching.
- **Speed limit from real data only.** The badge reads the geocoder's `/speed`
  nearest-road lookup (already proxied on this origin, already network-only in the
  service worker). A surveyed OSM `maxspeed` is drawn as the solid red-ringed sign; a
  limit *inferred* from the highway class (`source: "default"`) is drawn dashed and
  amber and labelled as typical rather than posted. No road within 60 m, no service, or
  a failed request hides the badge entirely — nothing is invented client-side.
  Over-limit (numerals red, red halo) fires only when both numbers are real.

No service-worker cache bump: navigations are network-first with a per-path cache put,
so an online client picks the new shell up on its next load, and the documented bump
rule exists for ingest-contract changes, which this is not.

## 2026-08-05 — Getting data off a real phone (tickets 19/20/22/23)

### Tier 1: import a recorded track — the tier that needs nothing from the platform

`src/vector_web/track_import.py` plus `POST /import/preview` and
`POST /import/commit`. A PWA cannot record in the background; OsmAnd, Organic Maps,
Strava, Komoot and Garmin can, because the OS granted them the permission. So they
record, and we read the file.

- GPX as real exporters emit it: namespaced 1.0/1.1, `<extensions>`, several
  `<trkseg>` per `<trk>` and several `<trk>` per file, `<rtept>` fallback. Plus
  GeoJSON LineString, which is what our own tooling emits, so a round trip is
  testable without a phone in the loop.
- **One trip per `<trkseg>`, split again on a >5 min gap.** A segment is where the
  recorder itself judged the track broke. One token per file would make a month of
  commuting one "trip" that never clears K; one per point manufactures trips.
- **`<hdop>` is read and thrown away.** Converting it to metres needs the receiver's
  UERE, which the file does not carry — it would manufacture a precise-looking number
  and then test it against a real 25 m threshold. Missing accuracy is accepted as
  *unknown*, counted as `accuracy_unknown`, and screened kinematically instead:
  anything implying >200 km/h or movement with no elapsed time is a bad fix.
- **365 days, on validity grounds** — a track recorded before the road was resurfaced
  is wrong in a way that looks identical to right. Not a privacy bound: consent,
  truncation, the TTL and the K floor already do that work.
- **Idempotent.** A truncated HMAC of gated geometry per detected trip, so uploading
  one file twice is refused instead of manufacturing two trips from one journey —
  the accident a careful person makes when unsure the upload worked.
- **Its own consent, enforced server-side.** `commit` without `consent=granted` is a
  400. A consent that exists only as a screen is a screen, not a control. The
  preview stores nothing, returns no geometry, and can be cancelled.

### `/collect` — the collection surface is a page, not another button

`static/collect.html`. Collection was a toolbar button and a modal, which can express
"record" and "stop" and nothing else. The page presents the three tiers **in the order
they actually work**, states the browser's background limit *before* offering the
record control, and offers **no download link** for the native app, because ADR-0070
is Proposed and gated on a battery number nobody has taken. It is also the screen
ADR-0070's Capacitor shell loads from the live origin, so the shell gets a home
without a second implementation.

### Tier 2: live capture, honestly (ticket 20, in part)

- **The render buffer and the upload queue are now two objects.** They were one array,
  and `traceUpload` sent all of it every batch: points 1-30, then 1-60, then 1-90 —
  11x amplification on a ten-minute drive, 83x at the cap. K was unaffected (same trip
  token, so they collapsed to one pseudonym) which is why nobody noticed; storage,
  bandwidth and every downstream scan were not.
- **Each queued point remembers its trip.** Without that, a gap split while an upload
  was in flight would send the finished trip's points under the *next* trip's token.
- Screen Wake Lock with re-acquisition on `visibilitychange`; gap splitting into
  separate trips with a **broken drawn line**, so a hole is never drawn as a journey;
  queue depth and a stalled state on screen instead of `catch {}`; and the 5,000-point
  cap now says so instead of silently stopping after ~83 minutes.
- Still missing: IndexedDB persistence. The queue survives a tunnel, not a closed tab.

### Provenance (ticket 22, ingest half)

`source` on `observation` with a guarded `ALTER TABLE` — `CREATE TABLE IF NOT EXISTS`
never adds a column to an existing database, and the store is built on first use, so a
missing migration would have surfaced as a read error far from its cause. Closed
three-value enum, coerced at the edge *and* in `append`. `import` is **derived** (the
route parsed the file) and unreachable from a client claim; `live`/`native` are
**declared** and labelled as declared, because the server cannot tell a Capacitor
shell from a browser. Per-source counters carry a drop rate — a tier dropping far more
points is producing worse data, and that is worth knowing before its evidence moves a
speed profile.

### Fixed by actually running it

A live smoke run of the whole flow — real server, real upload, then look at the
rows — showed the per-source **drop rate was wrong for a re-uploaded file**: one
file uploaded twice reported *230 points dropped of 400 seen*, when 200 points had
arrived and 170 were kept. Two causes, both in the accounting rather than the data:

- A refused duplicate was counted as a **point loss**. Its points are in the store —
  that is *why* it was refused. It is now `duplicate_trips_refused` in the quality
  bucket, counted in **trips**, with the unit in the name.
- A duplicate trip still contributed its truncation and unknown-accuracy figures to
  the totals, describing a corpus that was never stored. `mark_duplicates` now takes
  both back out.

The rule that came out of it: `reasons` are permanent **point** losses, `quality`
describes the corpus that *will be* stored. So a trip refused because the gate
dropped every point still reports those drops — a real loss — while a duplicate
reports none. Same file, after the fix: 200 seen, 170 stored, 30 dropped, rate 0.15.

Whole-trip refusals are counted in points now (`sparse_sampling`, `too_short`,
`no_timestamps`, `shorter_than_truncation`), so a refused trip is a number rather
than a silent gap in the totals.

Worth more than the fix: **both errors were in the counter, not the data, and the
tests were green throughout.** Every unit test asserted the numbers it was written to
expect. What caught it was printing 400 "points seen" next to a 200-point file and
noticing the two could not both be true.


### Fixed while building the above

- **`haversineM` was declared twice in `index.html`, with the arguments in opposite
  orders.** Two `function` declarations of one name in one sloppy-mode script are
  legal and the last wins for the whole file, so `checkArrival` passed `(lat, lng)`
  into `(lng, lat)` and measured its 120 m arrival radius on mirrored coordinates: a
  plausible number, wrong by tens of percent, biased by heading, deciding whether an
  ETA sample is recorded at all. Worse than the error: editing the shadowed copy would
  have changed nothing.
- **The service worker cached every navigation under `"/"`.** Harmless with one page;
  with `/collect` added, visiting it would overwrite the cached map shell and the next
  offline visit to `/` would have served the collect page. Now keyed on the page's own
  path. `VERSION` bumped to v8 (the ingest payload gained `source` and `end`).
- **Static `.html` had no content type.** `index.html` is served by its own branch, so
  until `/collect` existed no static HTML reached `_ctype_for` — the first one would
  have been sent as `application/octet-stream` and offered as a download.

Tests: 76 → 168.

## 2026-08-04 — Loop wiring, privacy counters, evolution dashboard (Session 52)
- **Fixed: a 401 on POST aborted the connection.** Rejecting a POST without
  draining its body left unread bytes in the socket, so the client received RST
  instead of the status code (`ConnectionAbortedError` on Windows) and never
  learned it was unauthorized. `_drain_request_body()` now runs before any early
  error response. This was one of the two red `vector-web` CI jobs;
  `test_anon_post_is_401` passes and the suite is green.
- **Fixed: the proxy dropped the backend's `Cache-Control`.** `/tiles/version` is
  served `no-store` for a reason (issue 08); stripping it at the edge would let a
  browser cache the epoch, keep requesting the old `?v=`, and leave a promoted
  road invisible while every server-side check passed. Now forwarded.
- **Fixed: the client posted trace timestamps in SECONDS** while every consumer
  read milliseconds. Real captures would have landed on 1970-01-21 and been
  deleted by the next 72 h TTL vacuum — accepted, stored, reported as stored, and
  gone before aggregation. Fixed at the source; `vector-privacy` also rescales
  defensively, since the client is never trusted.
- **Added the S0 client-side privacy half** (adr-0065), which had never been
  implemented: on-device 25 m accuracy floor, 5-decimal coordinates, 5 s time
  rounding. The server still re-applies everything.
- `src/vector_web/privacy_counters.py`: cumulative gate drop counts, exposed at
  `GET /privacy-counters`. Per-request counts vanished with the response before
  this, so issue 10's privacy panel had nothing to show — and a k-anonymity floor
  that never reports rejecting anything is indistinguishable from a no-op.
- `GET /evolution` + `/evolution.json`: the "is the map improving?" dashboard,
  served from disk with `no-store` (a cached dashboard shows yesterday's verdict
  as though it were current). A missing snapshot returns 503 with an explanation,
  so "nobody ran the cycle" stays distinguishable from "the map is not improving".
- `/eta` and `/learned` proxied to routing; the client posts predicted-vs-observed
  travel time on arrival, carrying no coordinates, identity or route.
- Style: learned roads paint amber, dashed while provisional, drawn above the road
  layers — a wrong promotion must be obvious rather than invisible.
- Tiles are requested as `?v=<epoch>`, with a re-check on an interval and on tab
  focus so a long-lived tab picks up a re-bake without a reload.
- Tests: 59 (1 failing) -> 76, all green.

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

## 2026-07-20 — Installable PWA (ADR-0063)

Delivery decision: **web-first installable PWA**; native Android/iOS apps are
deferred (user-approved scope). `vector-web` now ships as an installable
progressive web app on the existing single self-hosted origin.

### PWA assets (all self-hosted, no third-party)
- `static/manifest.webmanifest` — name, `standalone` display, maskable icon,
  shortcuts (Navigate home / Report incident), `theme_color` #12161e.
- `static/sw.js` — service worker: precaches the app shell for **offline
  launch**, but serves **all live APIs** (`/route`, `/navigate`, `/traffic`,
  `/tiles`, `/glyphs`, `/search`, `/incidents`, …) **network-only** so
  navigation/traffic are never served stale.
- `static/icons/icon-192.png`, `icon-512.png`, `icon-maskable-512.png`,
  `icon.svg` — generated from source by `scripts/gen_icons.py` (pure stdlib
  PNG encoder + SVG; no Pillow/font dependency, no binary blob from the
  internet). Verified valid PNGs.
- `static/index.html` — wired: `<link rel="manifest">`, `theme-color`,
  apple-touch-icons, `viewport-fit=cover`, and service-worker registration.

### Backend (`src/vector_web/__init__.py`)
- Correct content-types for `.webmanifest` (`application/manifest+json`),
  `.png` (`image/png`), `.svg` (`image/svg+xml`).
- `/manifest.webmanifest`, `/sw.js`, `/icons/*` served **publicly** (no token
  gate) so install + offline restore work with no token context.

### Tests
- `tests/test_pwa.py` (4 tests): manifest served public + correctly typed +
  valid JSON + self-hosted icons (incl. maskable); sw.js served public +
  JS; icons served public + typed; index references the PWA wiring.
- Full web suite: **26 tests, green** (run twice to rule out a Windows socket
  flake seen on one invocation).

### Governance repair (this milestone)
- `adr-0060` (Flutter) was previously **deleted** (a governance violation —
  ADRs are immutable). Restored and marked **Superseded by adr-0063** (not
  deleted).
- Added missing `adr-0062` (Waze UX overhaul, referenced by the 07-19 changelog
  but never authored) and `adr-0063` (PWA-first, superseding 0060). ADR index
  + KG updated (new `kg://adr/0063` node + edges; restored `kg://repo/vector-web`
  and `kg://adr/0060` nodes).
- Note: the web app's viewer page (`/`) remains token-gated as before; PWA
  install/offline use the public manifest/sw/icons, and the deployed stack
  serves the shell via `?token=`.

### Known limits (honest)
- Native Android/iOS apps are NOT built this phase (deferred per adr-0063) —
  this is a deviation from the mission brief, recorded deliberately.
- Live deploy + `act`-based CI gate could not run this session (Docker down on
  the host); Node-side governance validator passes (0 errors). Python/Rust
  gates pending Docker.

## 2026-07-22 — Production hardening (ADR-0064)

Iterated `vector-web` to meet the Engineering Bible's security / observability
/ error-handling bars for a user-facing edge, and closed viewer correctness +
accessibility gaps. **Web suite: 26 → 40 tests, green.**

### Backend (`src/vector_web/__init__.py`)
- **Timing-safe token compare** (`hmac.compare_digest`) — closes a token
  timing side-channel in the edge gate.
- **Security headers on every response** (incl. 401s): `Content-Security-Policy`
  (self-only, no third party), `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy`,
  `X-XSS-Protection: 1; mode=block`.
- **Request body size limit** (default 10 MB; `VECTOR_MAX_BODY_BYTES`, read
  per-request) → 413 on oversized POST/PUT/PATCH.
- **Correlation ID** (`X-Request-ID`) echoed on every response and forwarded to
  backends for distributed tracing.
- **Graceful shutdown** on SIGTERM/SIGINT.
- **`Content-Length` on every response** (HTTP/1.1 keep-alive correctness).
- **Path-traversal protection** in static file serving (`_safe_join`).
- `_check_auth()` replaces the vendored `Auth.enforce()` for the edge gate so
  401s also carry security headers; `Auth` remains the token storage source
  (ADR-0007).
- `_serve_and_forward_traffic()` now handles POST (browser probe upload), not
  just GET; error path sends security headers + Content-Length.
- `do_OPTIONS` (CORS preflight) returns `Allow` + CORS + security headers.

### Frontend (`static/index.html`)
- **Security fix:** `TOKEN` defaults to empty string (was `'secret'`), so
  dev-anonymous mode sends NO `Authorization` header instead of a fake bearer.
- **`markers.pick` initialized** — the Pick-location flow no longer risks a
  crash referencing an undefined marker.
- **GPS speed:** `kmh` initialized to 0 — first fix with no GPS speed no longer
  renders `NaN`.
- **Theme persisted** to `localStorage` (`vector.theme`) and restored on load.
- **Accessibility:** `role="application"` + `aria-label` on the map; `role`/
  `aria-live` on toast/recalc/progress; visually-hidden `<label>` on search;
  `aria-expanded` toggled on search results; `prefers-reduced-motion` media
  query disables spinner/map transition.
- **PWA lifecycle:** `beforeinstallprompt` captured (`window.vectorInstall()`);
  a waiting/installed service worker triggers an update toast.

### Tests
- `tests/test_security.py` (12 tests): security headers on 200/401, body-size
  limit (413), path traversal blocked, OPTIONS preflight Allow header, POST
  /traffic works + requires token, request-ID echo/generate.
- `tests/test_pwa.py` (extended): TOKEN default, ARIA/reduced-motion, PWA
  install prompt, SW precache + network-only APIs.

### Governance
- New `adr-0064-web-production-hardening.md` records the decisions.
- README updated with the hardening summary + 40-test count.

