"""vector-web — web composition root (Wave 26e).

Serves the MapLibre viewer at ``/`` and proxies backend traffic to the routing
and traffic engines at a SINGLE origin, so the browser never hits CORS and the
viewer's ``/route``/``/navigate``/``/traffic`` calls resolve locally.

Security (ADR-0050): the web edge enforces a bearer token on non-``/healthz``
endpoints when ``VECTOR_WEB_TOKEN`` is set, and forwards that token to the
backends (so the engines' own auth also passes). Dev-anonymous when no token is
set. Backend base URLs come from ``VECTOR_ROUTING_URL`` /
``VECTOR_TRAFFIC_URL`` (defaulting to localhost:8081 / :8084).

Production hardening:
- Timing-safe token comparison (hmac.compare_digest) to prevent timing attacks.
- Security response headers (X-Content-Type-Options, X-Frame-Options,
  Referrer-Policy, Content-Security-Policy) on every response.
- Request body size limit (10 MB) to prevent resource exhaustion.
- Correlation ID (X-Request-ID) on every request for distributed tracing.
- Graceful shutdown on SIGTERM/SIGINT.
- Path traversal protection in static file serving.

This module is stdlib-only. Auth is the vendored ``vector_auth`` package
(ADR-0007); the bus client is vendored for symmetry/future use. The proxy never
imports the engines directly — cross-repo integration is via HTTP only
(ADR-0003).
"""

import argparse
import hmac
import json
import os
import re
import secrets
import signal
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, Optional, Tuple

from vector_auth import Auth

# Endpoints we proxy to a backend.
# Exact-path proxies use a dict; prefix proxies (e.g. /tiles/...) are listed
# separately so the viewer can fetch MVT tiles from the same origin.
PROXIES = {
    "/route": "VECTOR_ROUTING_URL",
    "/navigate": "VECTOR_ROUTING_URL",
    "/overlay": "VECTOR_ROUTING_URL",
    # V7 Phase 2. The pedestrian graph was built, tested and deployed, and the
    # walk was still unreachable from outside: vector-routing answered /foot on
    # 8081 while this table did not list it, so the edge returned 404 for an
    # endpoint that existed. Listed here rather than special-cased in the
    # dispatch because these need exactly what the generic proxy already does —
    # and that includes the token gate, which runs before this lookup, so the
    # walk is authenticated on the same terms as the drive.
    "/foot": "VECTOR_ROUTING_URL",
    "/footz": "VECTOR_ROUTING_URL",
    "/traffic": "VECTOR_TRAFFIC_URL",
    "/search": "VECTOR_GEOCODER_URL",
    "/reverse": "VECTOR_GEOCODER_URL",
    "/speed": "VECTOR_GEOCODER_URL",
    "/along": "VECTOR_GEOCODER_URL",
    "/incidents": "VECTOR_TRAFFIC_URL",
    "/traffic/probe": "VECTOR_TRAFFIC_URL",
    "/traces": "VECTOR_TRACE_STORE_URL",
    # Issue 07: the client posts predicted-vs-observed travel time here on
    # arrival, and issue 10's collector reads the distribution back from it.
    "/eta": "VECTOR_ROUTING_URL",
    "/learned": "VECTOR_ROUTING_URL",
}

# Where the web proxy forwards congestion segments so routing can reroute
# (Wave 30). Defaults to the routing service's ingest endpoint.
ROUTING_TRAFFIC_URL = "VECTOR_ROUTING_TRAFFIC_URL"

# Prefix proxies: request path startswith key -> proxy to backend env var.
PREFIX_PROXIES = {
    "/tiles/": "VECTOR_TILE_SERVER_URL",
    "/glyphs/": "VECTOR_TILE_SERVER_URL",
}

DEFAULTS = {
    "VECTOR_ROUTING_URL": "http://localhost:8081",
    "VECTOR_TRAFFIC_URL": "http://localhost:8084",
    "VECTOR_TILE_SERVER_URL": "http://localhost:3000",
    "VECTOR_GEOCODER_URL": "http://localhost:8085",
    "VECTOR_ROUTING_TRAFFIC_URL": "http://localhost:8081",
    # OSRM is the production routing engine, but as of adr-0072 this edge no
    # longer calls it: vector-routing owns that call, because only vector-routing
    # holds the graph and the learned overlay needed to keep the learned layer
    # alive on the result. Kept here so `docker-compose.yml` can keep setting it
    # in one place and so an operator can still see where OSRM lives.
    "VECTOR_OSRM_URL": "http://localhost:5000",
# Wave 47d: where /traces persists captured GPS. When set, the web edge
    # writes traces to a local store instead of proxying; when unset,
    # /traces proxies to VECTOR_TRACE_STORE_URL (a separate processor).
    # Issue 02: the flat JSONL is replaced by a SQLite db (traces.db).
    "VECTOR_TRACE_DB": os.environ.get(
        "VECTOR_TRACE_DB",
        os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "data", "traces.db")),
    # Honored for one release (issue 02); VECTOR_TRACE_DB wins if both set.
    "VECTOR_TRACE_FILE": os.environ.get(
        "VECTOR_TRACE_FILE",
        os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "data", "traces.jsonl")),
}

# OSRM probe timeout (seconds). OSRM is an optional accelerator with a
# guaranteed pure-Python fallback, so keep this short: when OSRM is down the
# request should fail over quickly instead of stalling. Override via env.
_OSRM_TIMEOUT_S = float(os.environ.get("VECTOR_OSRM_TIMEOUT_S", "2.5"))

# Maximum request body size (10 MB). Prevents resource-exhaustion attacks
# via oversized POST/PUT/PATCH payloads (e.g. incident reports, probe uploads).
# Read at request time so tests can override via env without reimporting.
def _max_body_bytes() -> int:
    try:
        return int(os.environ.get("VECTOR_MAX_BODY_BYTES", str(10 * 1024 * 1024)))
    except (ValueError, TypeError):
        return 10 * 1024 * 1024


# Conservative origin form: scheme://host[:port] only. No path, no wildcard, no
# non-http(s) scheme, and none of the characters (";" above all) that would let
# a value close the directive and append a policy of its own.
_ORIGIN_RE = re.compile(r"^https?://[A-Za-z0-9.\-]+(:[0-9]{1,5})?$")


def _csp_connect_src() -> str:
    """Build the CSP ``connect-src`` directive.

    Defaults to ``'self'``, which is correct for the self-hosted viewer served
    from the same origin it calls. A *packaged* client is different: inside a
    Capacitor WebView the page origin is the webview's own scheme
    (``https://localhost`` on Android under Capacitor's default
    ``androidScheme``, ``capacitor://localhost`` on iOS), never the public host,
    so every API, tile and glyph fetch is cross-origin. CORS and CSP are
    independent gates — opening CORS at the proxy while ``connect-src`` stays
    ``'self'`` still blocks the request before it leaves the browser, and the
    symptom is the blank-map bug again: endpoints return 200, nothing renders.

    ``VECTOR_PUBLIC_ORIGIN`` names the origin(s) the deployment is reachable at
    (space-separated for more than one). Each is matched against ``_ORIGIN_RE``
    and silently dropped if it does not look like a bare origin, so a malformed
    or hostile value cannot rewrite the rest of the policy.
    """
    raw = os.environ.get("VECTOR_PUBLIC_ORIGIN", "")
    origins = [tok for tok in raw.split() if _ORIGIN_RE.match(tok)]
    return " ".join(["connect-src", "'self'", *origins]) + "; "


# Security headers applied to every response. These are the baseline set
# recommended by OWASP and complement the per-endpoint CORS headers from
# vector_auth. CSP is intentionally permissive for the self-hosted viewer
# (no remote script/style/font sources — everything is vendored).
#
# NOTE: read from the environment once, at import. The container receives its
# environment before the process starts, so this is the deployment's value;
# tests exercise _csp_connect_src() directly.
_SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Content-Security-Policy": (
        "default-src 'self'; "
        # worker-src blob: is REQUIRED by MapLibre GL: it decodes vector tiles
        # in a Web Worker instantiated from a blob: URL. Without it the CSP
        # fallback (script-src) blocks the worker, tile decoding never runs,
        # and the map renders as a blank canvas — with every tile endpoint
        # returning 200. This exact failure shipped once; keep the directive.
        "script-src 'self' 'unsafe-inline'; "
        "worker-src 'self' blob:; "
        "style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data: blob:; "
        "font-src 'self'; "
        + _csp_connect_src() +
        "frame-ancestors 'none'; "
        "base-uri 'self'"
    ),
    "X-XSS-Protection": "1; mode=block",
}


def _prefer_ipv4(url: str) -> str:
    """Rewrite a ``localhost`` backend URL to ``127.0.0.1``.

    Python's urllib resolves ``localhost`` to IPv6 ``::1`` first and only falls
    back to IPv4 after the connect attempt stalls (~2 s on Windows), while our
    backend HTTP servers bind IPv4 only. curl hides this with happy-eyeballs;
    urllib does not. Pinning IPv4 removes a multi-second per-request stall.
    """
    return url.replace("localhost", "127.0.0.1")


def _safe_join(base: str, relative: str) -> Optional[str]:
    """Join ``relative`` onto ``base`` and return the path only if it stays
    inside ``base`` (prevents path traversal via ``..`` sequences).

    Returns ``None`` if the resolved path escapes ``base``.
    """
    base_abs = os.path.abspath(base)
    candidate = os.path.normpath(os.path.join(base_abs, relative.lstrip("/")))
    if candidate.startswith(base_abs + os.sep) or candidate == base_abs:
        return candidate
    return None


STATIC_DIR = os.environ.get("VECTOR_WEB_STATIC_DIR") or os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "static")
)
# On Windows run under git-bash/MSYS, ``__file__`` can arrive as a
# ``/c/Users/...`` POSIX-style path that Python's ``os.path`` does NOT
# understand against the real ``C:\Users\...`` filesystem, so the static
# directory lookup fails and the viewer 404s. Normalize a leading
# ``/x/`` MSYS root to the real drive letter. (No-op on Linux/containers.)
if STATIC_DIR.startswith("/") and len(STATIC_DIR) > 2 and STATIC_DIR[0] == "/" and STATIC_DIR[2] == "/":
    # /c/Users/...  ->  C:/Users/...
    _drive = STATIC_DIR[1].upper()
    STATIC_DIR = _drive + ":" + STATIC_DIR[2:]
# In the container the package lives at /app/src/vector_web but static is at
# /app/static; fall back to that known layout when the relative path misses.
if not os.path.isdir(STATIC_DIR):
    _fallback = "/app/static"
    if os.path.isdir(_fallback):
        STATIC_DIR = _fallback
INDEX_HTML = os.path.join(STATIC_DIR, "index.html")

# Content-type map for static file extensions.
_CTYPE_BY_EXT = {
    # index.html is served by its own branch, which hard-codes text/html — so
    # until /collect existed no static .html was reached through here, and one
    # would have been sent as application/octet-stream and offered as a download.
    ".html": "text/html",
    ".js": "application/javascript",
    ".css": "text/css",
    ".webmanifest": "application/manifest+json",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".json": "application/json",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
}


def _ctype_for(path: str) -> str:
    """Return the Content-Type for a static file path based on its extension."""
    ext = os.path.splitext(path)[1].lower()
    return _CTYPE_BY_EXT.get(ext, "application/octet-stream")


#: Whether to write one access-log line per response. On by default.
LOG_REQUESTS = os.environ.get("VECTOR_LOG_REQUESTS", "1") not in ("0", "false", "no")

#: Query parameters that must never reach a log.
#:
#: ``token`` is a credential. ``lat``/``lon`` are the driver's position, and
#: ADR-0066 keeps location out of any store unless it has been through the
#: accuracy floor, coordinate precision and temporal coarsening — none of
#: which an access log applies. Everything else on these endpoints (``q``,
#: ``lang``, ``limit``, ``z/x/y``) is kept, because a log that cannot say what
#: was asked for cannot explain what came back.
_REDACTED_PARAMS = ("token", "lat", "lon", "from", "to")


def _safe_path(path: str) -> str:
    """A request path with credentials and positions removed."""
    parsed = urllib.parse.urlsplit(path)
    if not parsed.query:
        return parsed.path
    kept = [(k, v) for k, v in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
            if k.lower() not in _REDACTED_PARAMS]
    dropped = sorted({k.lower() for k, _ in
                      urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)}
                     & set(_REDACTED_PARAMS))
    q = urllib.parse.urlencode(kept)
    if dropped:
        q = (q + "&" if q else "") + "redacted=" + "+".join(dropped)
    return parsed.path + ("?" + q if q else "")


class WebRequestHandler(BaseHTTPRequestHandler):
    auth: Optional[Auth] = None

    # Use HTTP/1.1 with keep-alive for better performance, but we must
    # always send Content-Length so the client knows when the response ends.
    protocol_version = "HTTP/1.1"

    def log_message(self, *args) -> None:
        return

    def parse_request(self) -> bool:
        """Start the clock when the REQUEST arrives, not when the socket opens.

        This used to live in `handle_one_request`, which looks like the start
        of a request and is not: with `protocol_version = "HTTP/1.1"` the
        connection is persistent, so `handle_one_request` is called in a loop
        and its first act is to BLOCK on `readline()` waiting for the client's
        next request. Everything between two polls on the same connection was
        therefore billed to the second one.

        That is not a rounding error, it is most of the number. A phone polling
        `/speed` every 6 s produced a steady `6000ms` for a call the geocoder
        answers in 9-11 ms, and a tile the client cancelled logged 81,719 ms of
        "work" that was a held-open socket. Both were read as latency during
        the 2026-09-14 drive analysis and sent a whole investigation after a
        saturated edge that was never saturated.

        `parse_request` runs after the request line and headers have been read
        and before the handler dispatches, so the clock now covers the request
        and nothing else.
        """
        ok = super().parse_request()
        self._t0 = time.time()
        return ok

    def log_request(self, code="-", size="-") -> None:
        """One access-log line per response.

        The edge logged nothing at all, which is why four search failures
        reported from two recorded drives had to be reproduced by hand against
        production days afterwards rather than read off. `log_request` is the
        hook `send_response` already calls, so this covers every response —
        including the 401s and 502s a handler-level log would miss.

        Set ``VECTOR_LOG_REQUESTS=0`` to silence it.
        """
        if not LOG_REQUESTS:
            return
        try:
            ms = (time.time() - getattr(self, "_t0", time.time())) * 1000
            print(f'[req] {self.command} {_safe_path(self.path)} '
                  f'{code} {size}b {ms:.0f}ms rid={self._request_id()}',
                  flush=True)
        except Exception:      # logging must never break a response
            pass

    # -- helpers -----------------------------------------------------------
    def _request_id(self) -> str:
        """Return a correlation ID for this request.

        Uses the incoming ``X-Request-ID`` header if present (for trace
        continuity across proxies), otherwise generates a random one.
        """
        rid = self.headers.get("X-Request-ID", "") if hasattr(self, "headers") else ""
        if rid:
            return rid
        return secrets.token_hex(8)

    def _send(self, code: int, ctype: str, body, extra_headers=None) -> None:
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        for k, v in _SECURITY_HEADERS.items():
            self.send_header(k, v)
        for k, v in (self.auth.cors_headers() if self.auth else {}).items():
            self.send_header(k, v)
        self.send_header("X-Request-ID", self._request_id())
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, code: int, obj) -> None:
        self._send(code, "application/json", json.dumps(obj).encode("utf-8"))

    def _send_traffic_response(self, code: int, ctype: str, payload: bytes) -> None:
        """Echo a proxied traffic payload verbatim back to the caller."""
        self._send(code, ctype, payload)

    def _read_body(self) -> Optional[bytes]:
        """Read the request body, enforcing a size limit.

        Returns ``None`` if there is no body. Raises ``ValueError`` if the
        declared Content-Length exceeds ``_max_body_bytes()``.
        """
        length_str = self.headers.get("Content-Length", "0") if hasattr(self, "headers") else "0"
        try:
            length = int(length_str or "0")
        except ValueError:
            length = 0
        if length <= 0:
            return None
        if length > _max_body_bytes():
            raise ValueError("request body too large (%d bytes)" % length)
        return self.rfile.read(length)

    def _proxy_to(self, backend_url: str) -> None:
        target = _prefer_ipv4(backend_url.rstrip("/")) + self.path
        method = self.command or "GET"
        body = None
        try:
            body = self._read_body()
        except ValueError:
            self._send_json(413, {"error": "request entity too large",
                                  "max_bytes": _max_body_bytes()})
            return
        # Forward the correlation ID so backends can trace the full request.
        fwd_headers = self._forward_headers()
        fwd_headers["X-Request-ID"] = self._request_id()
        req = urllib.request.Request(target, data=body, method=method,
                                     headers=fwd_headers)
        cache_control = None
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                status = resp.status
                payload = resp.read()
                ctype = resp.headers.get("Content-Type", "application/json")
                cache_control = resp.headers.get("Cache-Control")
        except urllib.error.HTTPError as e:
            status = e.code
            payload = e.read() or b"{}"
            ctype = e.headers.get("Content-Type", "application/json") if e.headers else "application/json"
            cache_control = e.headers.get("Cache-Control") if e.headers else None
        except Exception as e:  # transport error to backend
            self._send_json(502, {"error": "backend unreachable", "detail": str(e)})
            return
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        # Forward the backend's caching directive. Dropping it is not neutral:
        # ``/tiles/version`` is sent ``no-store`` precisely because it is the
        # response that tells a client its cached tiles are stale (issue 08). If
        # the edge strips that, the browser can cache the epoch, keep requesting
        # the old ?v=, and a promoted road stays invisible — the tile-server side
        # of the invalidation would look correct while the map never updates.
        if cache_control:
            self.send_header("Cache-Control", cache_control)
        for k, v in _SECURITY_HEADERS.items():
            self.send_header(k, v)
        for k, v in (self.auth.cors_headers() if self.auth else {}).items():
            self.send_header(k, v)
        self.send_header("X-Request-ID", self._request_id())
        self.end_headers()
        self.wfile.write(payload)

    def _valid_token(self) -> bool:
        """Token accepted via header (ADR-0050) OR ?token= query param.

        The viewer opens at ``/`` with no Authorization header, so it carries
        the bearer token as ``?token=``; API calls then send it as a header.
        In dev-anonymous mode (no VECTOR_WEB_TOKEN) this is always True.

        Uses ``hmac.compare_digest`` for constant-time comparison to prevent
        timing side-channel attacks on the token.
        """
        if self.auth is None or not self.auth.enabled:
            return True
        header = self.headers.get("Authorization", "") if hasattr(self, "headers") else ""
        if header.startswith("Bearer "):
            presented = header[len("Bearer "):].strip()
            return bool(presented) and hmac.compare_digest(presented, self.auth.token)
        # Fall back to ?token= (viewer page load via browser URL).
        parsed = urllib.parse.urlparse(self.path)
        token = urllib.parse.parse_qs(parsed.query).get("token", [""])[0]
        return bool(token) and hmac.compare_digest(token, self.auth.token)

    def _drain_request_body(self) -> None:
        """Read and discard the request body before an early error response.

        Rejecting a POST without consuming its body leaves unread bytes in the
        socket. The client is still writing while we close, so the OS sends RST
        and the client sees a connection abort instead of our 401 — on Windows,
        ``ConnectionAbortedError``. The status code we carefully set never
        arrives. Any early return on a request with a body has to drain first.
        """
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except (TypeError, ValueError):
            return
        remaining = min(length, _max_body_bytes())
        while remaining > 0:
            chunk = self.rfile.read(min(65536, remaining))
            if not chunk:
                break
            remaining -= len(chunk)

    def _check_auth(self) -> bool:
        """Enforce the web-token gate (ADR-0050).

        Returns ``True`` when authorized (dev-anonymous or valid token).
        When unauthorized, writes a 401 response WITH security headers and
        returns ``False`` (caller must ``return``). We use our own response
        path (not ``Auth.enforce``) so the security baseline headers are
        always present on the 401.
        """
        if self._valid_token():
            return True
        self._drain_request_body()
        self._send_json(401, {
            "error": "unauthorized",
            "message": "missing or invalid bearer token",
        })
        return False

    def _serve_static(self) -> None:
        clean = urllib.parse.urlparse(self.path).path
        if clean == "/" or clean == "/index.html":
            try:
                with open(INDEX_HTML, "rb") as fh:
                    # no-cache: the browser MUST revalidate every load, so a new
                    # build is picked up immediately (PWA SW update can't serve a
                    # stale shell). Heuristic caching was serving last hour's HTML.
                    self._send(200, "text/html", fh.read(),
                               extra_headers={"Cache-Control": "no-cache"})
            except FileNotFoundError:
                self._send(404, "text/plain", b"index.html not found")
            return
        # Vendored MapLibre assets (css/js) + PWA assets (manifest, sw, icons)
        # must load for the viewer without auth. sw.js and manifest must be
        # revalidated every load so a SW bump takes effect; icons/vendor are
        # content-hashed-immutable in practice, a long cache is fine.
        candidate = _safe_join(STATIC_DIR, clean.lstrip("/"))
        if candidate and os.path.isfile(candidate):
            ctype = _ctype_for(candidate)
            cc = "no-cache" if clean in ("/sw.js", "/manifest.webmanifest") else "public, max-age=300"
            with open(candidate, "rb") as fh:
                self._send(200, ctype, fh.read(), extra_headers={"Cache-Control": cc})
            return
        self._send(404, "text/plain", b"not found")

    def _forward_headers(self) -> Dict[str, str]:
        """Authenticate upstream with the service token (ADR-0050).

        Backends enforce ``VECTOR_SERVICE_TOKEN`` while this edge enforces
        ``VECTOR_WEB_TOKEN``. bootstrap.sh generates them as two DIFFERENT
        secrets, so forwarding the caller's web token to a backend always
        401s on any secured deployment ("missing or invalid bearer token"
        from the engine, not the edge). When a service token is configured
        we authenticate to backends with IT — the caller's identity was
        already verified by _check_auth() before any proxy path runs. The
        caller's own Authorization header is never forwarded; leaking the
        web token to engines would let any engine compromise widen into
        the whole stack.
        """
        hdr = {}
        service_token = os.environ.get("VECTOR_SERVICE_TOKEN", "")
        if service_token:
            hdr["Authorization"] = "Bearer " + service_token
        return hdr

    def _serve_and_forward_traffic(self) -> None:
        """Proxy ``/traffic`` to the traffic engine AND feed routing's overlay.

        Wave 30: the viewer needs live congestion (from the traffic engine) and
        the routing engine needs those same segments to populate its
        congestion overlay so ``/route?traffic=1`` can reroute around jams. The
        web proxy fans the traffic response out to both consumers over HTTP
        (ADR-0003: no shared broker in the demo stack).

        Handles both GET (viewer fetches traffic) and POST (browser probe
        upload). For POST, the body is forwarded to the traffic backend and
        then fanned out to routing's overlay.
        """
        traffic_backend = os.environ.get(PROXIES["/traffic"], DEFAULTS[PROXIES["/traffic"]])
        method = self.command or "GET"

        # Read the request body for POST (probe uploads).
        body = None
        if method in ("POST", "PUT", "PATCH"):
            try:
                body = self._read_body()
            except ValueError:
                self._send_json(413, {"error": "request entity too large",
                                      "max_bytes": _max_body_bytes()})
                return

        # Build the upstream request to the traffic backend.
        fwd_headers = self._forward_headers()
        fwd_headers["X-Request-ID"] = self._request_id()
        if body is not None:
            fwd_headers["Content-Type"] = self.headers.get("Content-Type", "application/json")

        try:
            req = urllib.request.Request(traffic_backend.rstrip("/") + self.path,
                                         data=body, method=method,
                                         headers=fwd_headers)
            with urllib.request.urlopen(req, timeout=15) as resp:
                status = resp.status
                payload = resp.read()
                ctype = resp.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as e:
            status = e.code
            payload = e.read() or b"{}"
            ctype = e.headers.get("Content-Type", "application/json") if e.headers else "application/json"
            self._send_traffic_response(status, ctype, payload)
            return
        except Exception as e:  # pragma: no cover - transport error
            self._send_json(502, {"error": "traffic backend unreachable", "detail": str(e)})
            return

        # Fan the congestion segments out to the routing engine's overlay.
        # The traffic engine emits GeoJSON ``features`` with [lon,lat] coords;
        # the routing overlay's TrafficOverlay.from_segments expects the
        # bus-envelope [lat,lon] convention, so we translate here (ADR-0003:
        # the web proxy is the only place the two engines meet, keeping them
        # decoupled).
        try:
            data = json.loads(payload.decode("utf-8"))
            features = data.get("features") if isinstance(data, dict) else None
            if features:
                forwarded = []
                for feat in features:
                    geom = (feat.get("geometry") or {}).get("coordinates") or []
                    latlon = [[c[1], c[0]] for c in geom if isinstance(c, (list, tuple)) and len(c) >= 2]
                    props = feat.get("properties") or {}
                    forwarded.append({
                        "geometry": latlon,
                        "free_flow_kmh": props.get("free_flow_kmh"),
                        "mean_speed_kmh": props.get("mean_speed_kmh"),
                    })
                routing_traffic = os.environ.get(
                    ROUTING_TRAFFIC_URL, DEFAULTS[ROUTING_TRAFFIC_URL]).rstrip("/") + "/traffic"
                post = urllib.request.Request(
                    routing_traffic,
                    data=json.dumps({"segments": forwarded}).encode("utf-8"),
                    headers={**self._forward_headers(), "Content-Type": "application/json",
                             "X-Request-ID": self._request_id()},
                    method="POST",
                )
                with urllib.request.urlopen(post, timeout=15) as _r:
                    _body = _r.read()
                try:
                    _j = json.loads(_body.decode("utf-8"))
                    print("[traffic-fanout] routing overlay ingest ->", _j, file=sys.stderr, flush=True)
                except Exception:
                    pass
        except Exception as e:  # pragma: no cover - diagnostic
            # Traffic overlay population is best-effort; never fail the viewer
            # request because routing's ingest hiccuped. Surface the reason to
            # container logs so live wiring can be diagnosed.
            print("[traffic-fanout] routing overlay ingest failed:", repr(e), file=sys.stderr, flush=True)

        self._send_traffic_response(status, ctype, payload)

    def _serve_evolution(self, as_json: bool = False) -> None:
        """Serve the pre-generated evolution dashboard (issue 10).

        The page is produced by ``scripts/run-evolution-cycle.mjs`` and written
        to ``VECTOR_EVOLUTION_DIR``. Serving a file rather than computing on
        request keeps the dashboard off the request path entirely — it reads
        several stores and must never be able to slow down or break the map.

        A missing file is answered with a plain explanation and a 503, not a
        blank page or a stack trace: "nobody has run the cycle yet" is a
        different problem from "the map is not improving", and an operator has to
        be able to tell them apart.
        """
        base = os.environ.get(
            "VECTOR_EVOLUTION_DIR",
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data"),
        )
        name = "evolution.json" if as_json else "evolution.html"
        path = os.path.abspath(os.path.join(base, name))
        try:
            with open(path, "rb") as fh:
                body = fh.read()
        except OSError:
            if as_json:
                self._send_json(503, {
                    "error": "no evolution snapshot",
                    "detail": f"{name} not found; run scripts/run-evolution-cycle.mjs",
                })
            else:
                self._send(503, "text/html",
                           b"<!doctype html><meta charset=utf-8>"
                           b"<title>Vector - no evolution snapshot</title>"
                           b"<h1>No evolution snapshot yet</h1>"
                           b"<p>Run <code>node scripts/run-evolution-cycle.mjs</code> "
                           b"to generate one. This is not a verdict on whether the "
                           b"map is improving \xe2\x80\x94 it means nothing has measured it yet.</p>")
            return
        # The dashboard is regenerated by a scheduled job; a cached copy would
        # show yesterday's verdict as though it were current.
        self._send(200, "application/json" if as_json else "text/html", body,
                   extra_headers={"Cache-Control": "no-store"})

    def _serve_import(self, *, commit: bool, query: Dict[str, list]) -> None:
        """Import a recorded track file (ticket 19, adr-0069).

        ``POST /import/preview`` parses, screens and gates the file and reports
        what *would* be stored, storing nothing. ``POST /import/commit`` does the
        same work and then stores it. Both take the raw file as the request body,
        with an optional ``?name=`` for format sniffing.

        **The commit re-sends the file rather than referring to a preview.** That
        costs one upload of a local file and buys no server-side session state — no
        table of pending imports to secure, expire or leak. Because ``analyze`` is
        deterministic, the trips the commit accepts are exactly the ones the
        preview showed.

        Consent is enforced here, not only in the UI (``?consent=granted``). A bulk
        upload is a materially larger disclosure than one live trip (adr-0069
        §Decision 5), and a consent that exists only as a screen someone can skip
        by calling the endpoint directly is a screen, not a control.
        """
        import time as _time
        from vector_privacy.trip import load_or_create_salt, mint_client_token, trip_pseudonym
        from vector_web import privacy_counters
        from vector_web.trace_store import _make_store
        from vector_web import track_import

        # A GPX archive is routinely larger than the 10 MB default body cap, and
        # the generic 413 ("request entity too large") tells the person nothing
        # about what to do next.
        try:
            limit = int(os.environ.get("VECTOR_MAX_IMPORT_BYTES", str(64 * 1024 * 1024)))
        except (TypeError, ValueError):
            limit = 64 * 1024 * 1024
        length_str = self.headers.get("Content-Length", "0") or "0"
        try:
            length = int(length_str)
        except ValueError:
            length = 0
        if length <= 0:
            self._send_json(400, {"error": "empty body",
                                  "detail": "POST the track file itself as the request body"})
            return
        if length > limit:
            self._send_json(413, {
                "error": "file too large",
                "max_bytes": limit,
                "detail": ("Export a shorter date range, or split the file: most "
                           "recorders can export one day or one track at a time."),
            })
            return
        data = self.rfile.read(length)

        salt = load_or_create_salt(os.path.dirname(os.path.abspath(
            os.environ.get("VECTOR_TRACE_DB") or DEFAULTS["VECTOR_TRACE_DB"])))
        store = _make_store()
        now_ms = int(_time.time() * 1000)
        name = (query.get("name") or [""])[0]

        try:
            plan = track_import.analyze(data, filename=name, salt=salt, now_ms=now_ms,
                                        seen_digests=None)
        except track_import.ImportError_ as exc:
            # A parse failure is the person's problem to fix, so the message has to
            # be about their file rather than about our parser.
            self._send_json(400, {"error": "cannot read this file", "detail": str(exc)})
            return

        # Duplicate detection after the plan, in one query rather than per trip.
        plan = track_import.mark_duplicates(
            plan, store.digests_seen([t["digest"] for t in plan["trips"] if t.get("digest")]))

        if not commit:
            self._send_json(200, {
                "status": "preview",
                "stored": 0,
                **track_import.public_plan(plan),
                "consent_required": True,
                "note": ("Nothing has been stored. Confirm to import; the same file "
                         "will be re-read and these are the trips that will be kept."),
            })
            return

        if (query.get("consent") or [""])[0] != "granted":
            self._send_json(400, {
                "error": "import consent required",
                "detail": ("A bulk import is a separate disclosure from live capture "
                           "and needs its own explicit consent (adr-0069 §5). Call "
                           "the preview, show what it found, then commit with "
                           "consent=granted."),
                **track_import.public_plan(plan),
            })
            return

        stored_total = 0
        trips_stored = 0
        for trip in plan["trips"]:
            points = trip.get("_points") or []
            if trip["refused"] or not points:
                continue
            # One token per detected trip, minted server-side (adr-0069 §4). The
            # client does not supply it: for an import the server is the party that
            # knows where the trips are, because it did the detection.
            pseudonym, _reason = trip_pseudonym(mint_client_token(), salt)
            stored = store.append("track", points, pseudonym=pseudonym,
                                  source="import")
            stored_total += stored
            trips_stored += 1
            if trip.get("digest"):
                store.record_digest(trip["digest"], seen_ms=now_ms)

        # `import` is DERIVED here — a property of the route that parsed the file,
        # not a claim the client made (adr-0070 §5). It is the one provenance value
        # a client cannot ask for.
        privacy_counters.record(plan["would_drop"], stored=stored_total,
                                source="import", trips=trips_stored,
                                quality=plan["quality"])
        self._send_json(200, {
            "status": "ok",
            "source": "import",
            "source_provenance": "derived",
            "stored": stored_total,
            "trips_stored": trips_stored,
            **track_import.public_plan(plan),
        })

    def _serve_traces(self) -> None:
        """GET recent traces or POST a captured GPS trace (Wave 47d).

        GET  /traces            -> recent stored points (admin/debug view)
        GET  /traces?recent=1  -> same (bounded in-memory cache)
        POST /traces            -> {kind:'probe'|'track', points:[...]}
                              body is validated, sanitized, and appended to
                              the local JSONL store (VECTOR_TRACE_FILE).

        Auth: gated by the web token (caller runs _check_auth() first).
        """
        method = self.command or "GET"
        if method == "POST":
            try:
                body = self._read_body()
            except ValueError:
                self._send_json(413, {"error": "request entity too large",
                                      "max_bytes": _max_body_bytes()})
                return
            if body is None:
                self._send_json(400, {"error": "empty body"})
                return
            try:
                payload = json.loads(body.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                self._send_json(400, {"error": "invalid JSON body"})
                return
            from vector_web.trace_store import (
                normalize_source, validate_trace, _make_store,
            )
            points, err = validate_trace(payload)
            if err:
                self._send_json(400, {"error": err})
                return
            kind = payload.get("kind")
            # Provenance (ticket 22 / adr-0070 §5). `live` vs `native` cannot be
            # derived — the server cannot tell a Capacitor shell from a browser on
            # a shared endpoint — so it is **declared** and recorded as declared.
            # `import` is not reachable from here at all: it is derived by the
            # route that parsed the file, which is the only place that knows.
            declared = payload.get("source")
            source = normalize_source(declared if declared != "import" else None)
            # Privacy gate (adr-0065 / issue 01): re-apply every S0 rule
            # server-side BEFORE the sink. The client is never trusted to have
            # done its half. Dropped-reason counters feed issue 10.
            import time as _time
            from vector_privacy.gate import apply_gate
            from vector_privacy.trip import (
                REASON_NO_TRIP_TOKEN, load_or_create_salt, trip_pseudonym,
            )
            now_ms = int(_time.time() * 1000)
            # Truncation is NOT done here for live capture (ticket 23). One payload
            # is a part of a trip, not all of it, so cutting 200 m off each end of
            # each payload cuts the middle of the journey — and for a payload
            # shorter than 400 m of road it cuts everything. The TripTruncator
            # below applies the same rule across payloads instead. Nothing may
            # reach store.append from a path that used neither.
            gated, dropped = apply_gate(kind, payload.get("points", []),
                                        now_ms=now_ms, truncate=False)
            store = _make_store()

            # Trip-scoped pseudonym (adr-0068). The K floor counts DISTINCT trip
            # pseudonyms, so it only means anything if one pseudonym is exactly
            # one trip. Minting per request — which is what this did — turned one
            # continuous drive into five "trips", because the client uploads a
            # batch every 30 fixes: 150 fixes on one road emitted an evidence row
            # claiming n_trips=5 from a single journey. The client is the only
            # party that knows where a trip starts and ends, so it supplies an
            # opaque token; the server hashes it with a persisted salt and stores
            # only the digest, so the token itself never lands anywhere.
            _salt = load_or_create_salt(os.path.dirname(os.path.abspath(
                os.environ.get("VECTOR_TRACE_DB") or DEFAULTS["VECTOR_TRACE_DB"])))
            pseudonym, _trip_reason = trip_pseudonym(payload.get("trip"), _salt)
            if _trip_reason and kind == "track":
                # REJECT rather than fall back (ticket 24). The fallback is exactly
                # the defect above — one pseudonym per batch, so one journey clears
                # K=5 alone — and the resulting facts look completely ordinary.
                #
                # The window is real, not theoretical: the service worker caches the
                # app shell, so a returning phone can run the previous index.html
                # until it updates, inflating trip counts for that whole period.
                # Accepting data that silently corrupts the guarantee is worse than
                # refusing it: the corruption is invisible and permanent, the refusal
                # is loud and fixable.
                #
                # Still counted, so an operator sees old clients being turned away
                # rather than wondering why coverage stopped growing.
                from vector_web import privacy_counters as _pc
                _pc.record({REASON_NO_TRIP_TOKEN: 1}, stored=0)
                self._send_json(400, {
                    "error": "missing or invalid trip token",
                    "detail": "This client is too old to contribute safely: a track "
                              "without a per-trip token would let one journey satisfy "
                              "the k-anonymity floor on its own (adr-0068). Reload to "
                              "update the app.",
                    "reason": REASON_NO_TRIP_TOKEN,
                    "reload_required": True,
                })
                return
            if _trip_reason:
                # `probe` fixes are interval samples, not a continuous track, so they
                # are not trip-scoped and a missing token is not a correctness
                # problem for them. Counted anyway so the shape of traffic is visible.
                dropped[REASON_NO_TRIP_TOKEN] = dropped.get(REASON_NO_TRIP_TOKEN, 0) + 1
            meta = {}
            authz = self.headers.get("Authorization", "") if hasattr(self, "headers") else ""
            if authz.startswith("Bearer "):
                meta["token"] = "***"  # never log the raw token

            # Per-trip endpoint truncation (ticket 23). `probe` fixes are interval
            # samples rather than a continuous track and were never truncated, so
            # they bypass the truncator exactly as they bypassed `_truncate_endpoints`.
            withheld = 0
            pending = 0
            if kind == "track":
                from vector_privacy.truncate import (
                    REASON_PENDING_TAIL, shared_truncator,
                )
                truncator = shared_truncator()
                committable, tcounts = truncator.offer(pseudonym, gated, now_ms=now_ms)
                # An explicit trip end discards the pending tail — and that discard
                # IS the tail truncation. A client that never sends `end` is covered
                # by idle eviction, so the tail is discarded either way; `end` only
                # makes it prompt.
                if payload.get("end") is True:
                    end_counts = truncator.end_trip(pseudonym)
                    for key, value in end_counts.items():
                        tcounts[key] = tcounts.get(key, 0) + value
                withheld = tcounts.pop(REASON_PENDING_TAIL, 0)
                for key, value in tcounts.items():
                    dropped[key] = dropped.get(key, 0) + value
                pending = truncator.pending_points()
                gated = committable

            stored = store.append(kind, gated, pseudonym=pseudonym, meta=meta,
                                  source=source)
            # Accumulate the drop reasons. Per-request counts vanish with the
            # response, and issue 10's privacy panel needs the running totals —
            # they are the only evidence the gate is doing work rather than
            # passing everything through. The withheld tail goes in `quality`, not
            # `reasons`: it is a deferral, and counting it as a drop would credit
            # the gate with enforcement it did not perform.
            from vector_web import privacy_counters
            privacy_counters.record(
                dropped, stored=stored, source=source,
                quality={"pending_tail_withheld": withheld} if withheld else None)
            self._send_json(200, {
                "status": "ok",
                "kind": kind,
                "source": source,
                "source_provenance": "declared",
                "stored": stored,
                "dropped": dropped,
                # Reported so a client can tell "held back, arriving next payload"
                # apart from "refused". Without it the two look identical from
                # outside, which is the ambiguity ticket 20's UI has to resolve.
                "withheld_pending_tail": withheld,
                "pending_points": pending,
                "total": store.count(),
            })
            return
        # GET: return the bounded recent cache (default 100).
        try:
            limit = int(urllib.parse.parse_qs(
                urllib.parse.urlparse(self.path).query).get("limit", ["100"])[0])
        except (ValueError, TypeError):
            limit = 100
        limit = max(1, min(limit, 1000))
        from vector_web.trace_store import _make_store
        recent = _make_store().recent(limit)
        self._send_json(200, {"count": len(recent), "points": recent})

    # -- handlers ----------------------------------------------------------
    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/healthz":
            self._send(200, "text/plain", b"ok")
            return

        # W49: route/navigate via the OSRM engine (full-Qatar, real-time).
        # Parse from/to/via, translate to the GeoJSON contract, fall back to
        # the pure-Python engine on any OSRM failure.
        if path in ("/route", "/navigate"):
            # W49 route/navigate MUST pass the web-token gate (ADR-0050): the
            # viewer's routing calls carry the bearer token, so a missing/invalid
            # token is 401. Previously these paths fell through to OSRM before
            # any auth check, leaking routing to anonymous callers (and breaking
            # the web-token enforcement contract). Enforce, then route.
            if not self._check_auth():
                return
            # adr-0072: proxy straight to vector-routing, which now calls OSRM
            # itself and falls back to its own engine on failure.
            #
            # This used to call OSRM directly from here (W49) and translate the
            # response inline. That was fast and it silently deleted the
            # product: the hand-written translation emitted no
            # ``learned_coverage`` and no ``learned_segments``, so the learned
            # speed overlay -- this project's entire differentiator -- was OFF
            # whenever OSRM was UP. Worse, it was undetectably off. The viewer
            # (``static/index.html``) reads ``p.learned_coverage`` and posts it
            # back to ``/eta`` to split the ETA-error distribution
            # learned-vs-unlearned; with the field missing it read 0.0, which
            # looks exactly like "nothing learned here yet". Every navigation
            # was logged as unlearned and issue 07's falsifiable claim quietly
            # became unfalsifiable while every dashboard stayed green.
            #
            # The overlay cannot be applied at this layer -- it needs the
            # 1.32M-node graph and the compiled overlay, which live in
            # vector-routing. So the OSRM call belongs there, not here, and this
            # edge goes back to being a proxy.
            backend = os.environ.get(PROXIES[path], DEFAULTS[PROXIES[path]])
            self._proxy_to(backend)
            return

        if path == "/" or path == "/index.html":
            # The viewer page is open to the bearer token supplied as ?token=
            # (a browser cannot send an Authorization header on the initial
            # navigation). The vendored MapLibre assets it references are
            # served publicly below (sub-resource requests drop the query
            # string, so they cannot carry the token).
            if not self._valid_token():
                self._send_json(401, {"error": "unauthorized",
                                      "message": "missing or malformed Authorization header"})
                return
            self._serve_static()
            return

        if path in ("/collect", "/collect.html"):
            # The collection surface (tickets 19-21). Token-gated like the viewer:
            # it posts location data, and adr-0070 makes this the screen the native
            # shell loads, so it must not be more open than the map.
            if not self._valid_token():
                self._send_json(401, {"error": "unauthorized",
                                      "message": "missing or malformed Authorization header"})
                return
            self.path = "/collect.html"
            self._serve_static()
            return

        if path == "/manifest.webmanifest" or path == "/sw.js" or path.startswith("/icons/"):
            # PWA assets: manifest, service worker, and generated icons. These
            # must load without auth (the browser fetches them during install
            # and offline restore with no token context). Served from STATIC_DIR.
            self._serve_static()
            return

        if path.startswith("/vendor/") or path.startswith("/js/"):
            # Public static assets: /vendor/ is the vendored MapLibre css/js,
            # /js/ is our own extracted client modules (ticket 36's geo.js).
            # Both are referenced as sub-resources by the viewer, and a
            # sub-resource request cannot carry the ?token= the navigation had,
            # so they must be served without auth. They contain no secrets —
            # the API base and token are injected into the page, not these.
            self._serve_static()
            return

        if path.startswith("/tiles/"):
            # Map tiles are public static assets. MapLibre's tile XHR does not
            # reliably forward custom Authorization headers, so these must be
            # served without auth (the routing/traffic APIs stay protected).
            backend = os.environ.get(PREFIX_PROXIES["/tiles/"], DEFAULTS[PREFIX_PROXIES["/tiles/"]])
            self._proxy_to(backend)
            return

        if path.startswith("/glyphs/"):
            # Self-hosted SDF glyph (font) PBFs. Like tiles, these are fetched
            # by MapLibre without an Authorization header, so serve them
            # without auth (adr-0059). Proxied to the same tile server that
            # serves /tiles.
            backend = os.environ.get(PREFIX_PROXIES["/glyphs/"], DEFAULTS[PREFIX_PROXIES["/glyphs/"]])
            self._proxy_to(backend)
            return

        if path == "/traces":
            # Wave 47d: captured GPS sink. Gated by the web token (it
            # carries user location, so anonymous writes are not allowed).
            if not self._check_auth():
                return
            self._serve_traces()
            return

        if path == "/privacy-counters":
            # Cumulative gate drop counts (issue 01 -> issue 10). Counts only,
            # no location, so this is safe to expose without auth and is what
            # the evolution-metrics collector reads.
            from vector_web import privacy_counters
            self._send_json(200, privacy_counters.summary())
            return

        if path == "/evolution" or path == "/evolution.json":
            # The "is the map improving?" dashboard (issue 10). Generated
            # offline by the evolution cycle and served from disk here, so the
            # web edge stays a proxy and never links the metrics code in.
            self._serve_evolution(as_json=path.endswith(".json"))
            return

        if path == "/traffic":
            # Wave 30: serve live traffic to the viewer, AND forward the
            # congestion segments to the routing engine (POST /traffic) so its
            # congestion overlay is populated. The routing engine then returns
            # traffic-aware routes via /route?traffic=1. This keeps the two
            # engines decoupled (ADR-0003): the web proxy wires them over HTTP
            # rather than a shared broker in the demo stack.
            self._serve_and_forward_traffic()
            return

        if not self._check_auth():
            return

        if path in PROXIES:
            backend = os.environ.get(PROXIES[path], DEFAULTS[PROXIES[path]])
            self._proxy_to(backend)
            return

        # Prefix proxies (e.g. /tiles/{z}/{x}/{y}.mvt -> tile server) so the
        # viewer's base map also resolves at this single origin.
        for prefix, env_var in PREFIX_PROXIES.items():
            if path.startswith(prefix):
                backend = os.environ.get(env_var, DEFAULTS[env_var])
                self._proxy_to(backend)
                return

        self._send(404, "text/plain", b"not found")

    def do_OPTIONS(self) -> None:
        # CORS preflight. Include Allow-Methods and Allow-Headers so browsers
        # know which methods/headers are permitted.
        self.send_response(204)
        for k, v in _SECURITY_HEADERS.items():
            self.send_header(k, v)
        for k, v in (self.auth.cors_headers() if self.auth else {}).items():
            self.send_header(k, v)
        self.send_header("Allow", "GET, POST, OPTIONS")
        self.send_header("X-Request-ID", self._request_id())
        self.end_headers()

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path == "/healthz":
            self._send(200, "text/plain", b"ok")
            return
        if path == "/traces":
            # Wave 47d: gated GPS sink.
            if not self._check_auth():
                return
            self._serve_traces()
            return
        if path in ("/import/preview", "/import/commit"):
            # Tier 1 (ticket 19): a track recorded by an app that already holds
            # background location permission. Same gate, same store, same trip
            # semantics — only the way the points arrive differs.
            if not self._check_auth():
                return
            self._serve_import(commit=path.endswith("commit"),
                               query=urllib.parse.parse_qs(parsed.query))
            return
        if not self._check_auth():
            return
        # Wave 30 traffic fanout: accept the browser's probe POST, forward to
        # routing's overlay so it can reroute, and echo the congestion back.
        if path == "/traffic":
            self._serve_and_forward_traffic()
            return
        if path in PROXIES:
            backend = os.environ.get(PROXIES[path], DEFAULTS[PROXIES[path]])
            self._proxy_to(backend)
            return
        self._send(404, "text/plain", b"not found")


def make_server(port: int, host: str = "0.0.0.0") -> ThreadingHTTPServer:
    WebRequestHandler.auth = Auth(token_env="VECTOR_WEB_TOKEN")
    server = ThreadingHTTPServer((host, port), WebRequestHandler)
    # Graceful shutdown on SIGTERM/SIGINT so the container stops cleanly
    # without dropping in-flight requests mid-response.
    def _shutdown(signum, frame):
        print("[vector-web] received signal %d, shutting down..." % signum,
              file=sys.stderr, flush=True)
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT, _shutdown)
    return server


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description="vector-web composition root")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args(argv)
    server = make_server(args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
