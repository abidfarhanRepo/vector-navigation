# ADR-0064: vector-web Production Hardening

- **Status:** Accepted
- **Date:** 2026-07-22
- **Deciders:** Vector architecture & security (d2-web, d5-security)
- **Supersedes/Relates:** ADR-0052 (web composition root), ADR-0050 (service-layer
  auth), ADR-0007 (single source of truth / vendoring). Builds on the PWA-first
  decision in ADR-0063.

## Context

`vector-web` shipped as an M1/M2 functional slice (ADR-0052) behind a single
bearer-token edge. Before it can be called production-grade it needed the
security and resilience controls the Engineering Bible (§4 Security, §15
Observability, §16 Error Handling) requires of any user-facing service:

- Timing-safe token comparison (the previous `==` compare was a side-channel).
- Baseline security response headers (CSP, X-Frame-Options, X-Content-Type-Options,
  Referrer-Policy, X-XSS-Protection) on every response, including 401s.
- Request body size limit (resource-exhaustion protection).
- Correlation ID (`X-Request-ID`) on every request/response for distributed tracing.
- Graceful shutdown on SIGTERM/SIGINT.
- `Content-Length` on every response (HTTP/1.1 keep-alive correctness).
- Path-traversal protection in static file serving.

On the viewer side, several correctness and accessibility gaps were closed:
the `TOKEN` default of `'secret'` would send a fake bearer to backends in
dev-anonymous mode; `markers.pick` was referenced but never initialized; the
GPS speed readout could produce `NaN` on the first fix when GPS speed was
absent; theme choice was not persisted; ARIA/live-region attributes and a
reduced-motion media query were missing; and the PWA install/update lifecycle
was not wired (`beforeinstallprompt` captured, update notification shown).

## Decision

Apply the hardening above to `src/vector_web/__init__.py` and
`static/index.html`, with full test coverage (`tests/test_security.py`,
`tests/test_pwa.py`) asserting each control.

Specific choices:

- **Auth gate uses our own `_check_auth()`** (returns bool, writes a 401 via
  `_send_json` which includes security headers) rather than the vendored
  `Auth.enforce()`, because `Auth.enforce()` predates the security-header
  baseline and would emit a bare 401. The vendored `Auth` is still the source
  of truth for token storage/enablement (ADR-0007); only the response-writing
  path is localized so security headers are always present.
- **Security headers are conservative but self-hosted-friendly**: CSP allows
  only `self` (all map/style/font/script assets are vendored), `connect-src`
  is `self` (all APIs proxy to the same origin), and `frame-ancestors` is
  `none`. This matches the privacy/self-hosting principle (no third party).
- **Body limit defaults to 10 MB** and is overridable via
  `VECTOR_MAX_BODY_BYTES` (read per-request, not at import, so it can be tuned
  without a restart-class change).
- **Frontend `TOKEN` defaults to empty string**; when empty, no
  `Authorization` header is sent (dev-anonymous mode). This removes the
  misleading `'secret'` fallback that could cause backends to reject requests
  or, worse, leak a predictable token.
- **Accessibility**: `role="application"` + `aria-label` on the map; `role`/
  `aria-live` on toast/recalc/progress; a visually-hidden label on the search
  input; `aria-expanded` toggled on the search results; `prefers-reduced-motion`
  disables the spinner/map transition.
- **PWA lifecycle**: `beforeinstallprompt` is captured so the UI can offer
  install at a natural moment (`window.vectorInstall()`); a new service worker
  waiting/installed triggers an update toast.

## Consequences

**Positive**
- `vector-web` now meets the Bible's security/observability/error-handling
  bars for a user-facing edge: constant-time auth, security headers everywhere,
  body-size bound, correlation IDs, graceful shutdown.
- Frontend is more correct (no fake token, no `pick` crash, no GPS `NaN`),
  more accessible (ARIA + reduced motion), and the PWA install/update flow is
  real.
- 40 tests green (12 new: security headers, body limit, path traversal,
  OPTIONS preflight, POST /traffic, plus index.html/sw.js assertions).

**Negative / trade-offs**
- `_check_auth()` duplicates a thin slice of `Auth.enforce()`; this is
  intentional (security-header consistency) and documented inline. The
  vendored `Auth` remains the token source of truth.
- CSP `connect-src 'self'` assumes the viewer only ever calls the same origin.
  If a future feature calls an external endpoint, the CSP must be widened
  (tracked as an ADR change).

## Validation
- `python run_tests.py` → 40 passed (was 26 before this iteration).
- New `tests/test_security.py`: 12 tests covering headers, body limit, path
  traversal, OPTIONS, POST /traffic.
- New `tests/test_pwa.py` assertions: TOKEN default, ARIA, reduced motion,
  PWA install prompt, SW network-only APIs.
- Manual: `python -m vector_web --port 8080` serves the hardened viewer;
  `curl -i /healthz` shows `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Content-Security-Policy`, and `X-Request-ID`.

## Amendment (2026-09-02) — `connect-src` widened for cross-origin clients

The trade-off recorded above ("CSP `connect-src 'self'` assumes the viewer only
ever calls the same origin. If a future feature calls an external endpoint, the
CSP must be widened (tracked as an ADR change)") has now come due, so this is
that change.

**What forced it.** The packaged Capacitor client calls a public deployment
instead of `location.origin`. Inside a WebView the page origin is the webview's
own scheme (`https://localhost` on Android under Capacitor's default
`androidScheme`, `capacitor://localhost` on iOS) — never the public host. Every
API, tile and glyph request is therefore cross-origin, and `connect-src 'self'`
blocks all of them *in the browser*, before the request is sent.

**Why CORS at the proxy did not cover it.** CORS and CSP are independent gates.
The proxy can return a perfect `Access-Control-Allow-Origin` and the fetch still
never leaves. The observable symptom is indistinguishable from the MapLibre
`worker-src blob:` bug already documented above: every endpoint returns 200 and
the map renders blank.

**Decision.** `connect-src` is now `'self'` plus the origins named by the
`VECTOR_PUBLIC_ORIGIN` environment variable (space-separated), built by
`_csp_connect_src()`.

- **Unset (the default) is byte-for-byte the old header.** Existing self-hosted
  deployments are unaffected; the same-origin viewer needs nothing.
- Values are validated against `^https?://host[:port]$`. Anything with a path,
  a wildcard, a non-http(s) scheme, or a `;` is dropped — a malformed or
  attacker-supplied value must not be able to terminate the directive and append
  a policy of its own. Covered by `CspConnectSrcTest` in `tests/test_security.py`.
- Read once at import, unlike `VECTOR_MAX_BODY_BYTES`: the container receives its
  environment before the process starts, and a CSP that changes per-request would
  be harder to reason about than one fixed at boot.

**Consequence.** Widening the policy is now a deployment decision expressed in
one env var, not a source edit — so the self-hosted default stays maximally
strict and only an operator who publishes a host opts into a looser one.
