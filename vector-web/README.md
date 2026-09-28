# vector-web

Web **composition root** for Vector. Serves the MapLibre viewer and proxies
routing/traffic/search/tiles to the engines behind a **single origin** with
**one bearer-token edge** (ADR-0050/0052, hardened per ADR-0064).

## What it does

- `GET /` → MapLibre viewer (installable PWA): search, tap-to-route, live
  traffic overlay, hazards/incidents, reverse-geocode, turn-by-turn steps,
  light/dark + 3D, GPS follow + speedometer, saved pins, Home/Work favorites.
- `GET /route`, `/navigate` → proxied to `VECTOR_OSRM_URL` (default
  `http://localhost:5000`) when set, falling back to `VECTOR_ROUTING_URL`
  (default `http://localhost:8081`). **Gated** by the web token (ADR-0050).
- `GET|POST /traffic` → proxied to `VECTOR_TRAFFIC_URL` (default
  `http://localhost:8084`) and fanned out to routing's congestion overlay so
  `/route?traffic=1` reroutes.
- `GET /search`, `/reverse`, `/speed` → proxied to `VECTOR_GEOCODER_URL`
  (default `http://localhost:8085`) — the self-hosted geocoder.
- `GET|POST /incidents` → proxied to `VECTOR_TRAFFIC_URL` (list/report hazards).
- `GET /tiles/{z}/{x}/{y}.mvt` → proxied to `VECTOR_TILE_SERVER_URL` (default
  `http://localhost:3000`, the Rust `vector-tile-server`).
- `GET /healthz` → open liveness probe.

Because the proxies resolve to the **same origin** the browser already uses,
there is no CORS breakage. The web edge enforces a bearer token (when
`VECTOR_WEB_TOKEN` is set) on every path except `/healthz`, `/vendor/*`,
`/tiles/*`, `/glyphs/*`, `/manifest.webmanifest`, `/sw.js`, `/icons/*` and
forwards it to the backends, so the engines' own auth also passes — one token,
one edge.

## Production hardening (ADR-0064)

The edge is built to the Engineering Bible's security/observability bars:

- **Constant-time token check** (`hmac.compare_digest`) — no timing side-channel.
- **Security headers on every response** (incl. 401s): `Content-Security-Policy`
  (self-only), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy`, `X-XSS-Protection`.
- **Request body size limit** (default 10 MB; `VECTOR_MAX_BODY_BYTES` to tune).
- **Correlation ID** (`X-Request-ID`) echoed on every request → propagated to
  backends for distributed tracing.
- **Graceful shutdown** on SIGTERM/SIGINT.
- **`Content-Length`** on every response (correct HTTP/1.1 keep-alive).
- **Path-traversal protection** in static file serving.

The viewer is accessible (ARIA roles/live regions, `prefers-reduced-motion`),
persists theme choice, and wires the PWA install/update lifecycle.

## Run

```bash
# dev (no auth, backends on defaults)
python3 -m vector_web

# production: same token at the edge and the engines
export VECTOR_WEB_TOKEN=*** VECTOR_SERVICE_TOKEN=***
export VECTOR_ROUTING_URL=http://routing:8081 VECTOR_TRAFFIC_URL=http://traffic:8084
python3 -m vector_web --port 8080
```

## Tests

```bash
# portable runner (works on Windows cmd.exe and POSIX bash)
python run_tests.py

# or directly (POSIX CI)
PYTHONPATH=src:vendor python -m unittest discover -s tests
```

40 tests cover the proxy contract, edge auth, PWA assets, security headers,
body-size limit, path traversal, CORS preflight, and POST /traffic.

## Dependencies

- `vector_auth` (vendored) — token storage + enablement (ADR-0007).
- `vector_bus_client` (vendored) — future live-bus UI wiring.

Both are single-source (ADR-0007); `scripts/check_vendor.py` fails CI if the
vendored copy drifts from the canonical repo.
