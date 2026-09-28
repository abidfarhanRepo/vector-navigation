# vector-auth

Shared **service-layer HTTP auth** for Vector's Python engine services.

- **Bearer-token enforcement** — when a shared secret is configured, every
  non-`/healthz` request must carry `Authorization: Bearer <token>`, else `401`.
- **Scoped CORS** — replaces the previous hard-coded `Access-Control-Allow-Origin: *`
  with origins from `VECTOR_CORS_ORIGINS` in secured mode, `*` in dev mode.
- **Dev-anonymous fallback** — when no secret is set (`VECTOR_SERVICE_TOKEN` /
  `VECTOR_BUS_ROOT_SECRET`), enforcement is off and CORS is `*`, so local dev
  and the CI gate stay green without secrets.

Stdlib-only, zero sibling-repo imports (adr-0003). This is a **source package**:
consuming engines vendor it via `scripts/sync_vendor.py` so each stays
self-contained for its per-repo CI gate (adr-0007).

## Usage

```python
from vector_auth import Auth

class MyHandler(BaseHTTPRequestHandler):
    auth = Auth()  # resolves VECTOR_SERVICE_TOKEN / VECTOR_BUS_ROOT_SECRET

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, "text/plain", b"ok")
            return
        if not self.auth.enforce(self):
            return  # 401 already sent
        ...

    def _send(self, code, ctype, body):
        ...
        for k, v in self.auth.cors_headers().items():
            self.send_header(k, v)
        ...
```

## Authoring

Edit `src/vector_auth/auth.py`, run `PYTHONPATH=src python -m unittest discover -s tests`,
then re-sync every consuming engine: `python3 scripts/sync_vendor.py <engine-repo>`.
