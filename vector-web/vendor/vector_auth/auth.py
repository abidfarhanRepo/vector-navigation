"""Bearer-token auth + scoped CORS for Python engine HTTP services.

Design mirrors the bus ``Auth`` layer (adr-0049) but at the individual engine
service boundary: when a shared secret is configured the service REQUIRES a
``Authorization: Bearer <token>`` header on every non-health endpoint and
returns 401 otherwise; when no secret is set the service runs in a
dev-anonymous fallback (no enforcement, CORS ``*``) so local development and the
CI gate stay green without secrets.

CORS is scoped to ``VECTOR_CORS_ORIGINS`` (comma-separated) in secured mode and
``*`` in dev mode, replacing the previous hard-coded ``*``.
"""

import os
from typing import Dict, List, Optional, Tuple


class Auth:
    """Per-service bearer-token enforcement and scoped CORS.

    Usage in a stdlib ``BaseHTTPRequestHandler``::

        def do_GET(self):
            if self.path == "/healthz":
                self._send(200, "text/plain", b"ok")
                return
            if not self.auth.enforce(self):
                return  # enforce() already sent a 401
            ...  # real handler

        def _send(self, code, ctype, body):
            ...
            for k, v in self.auth.cors_headers().items():
                self.send_header(k, v)
            ...
    """

    def __init__(
        self,
        token_env: str = "VECTOR_SERVICE_TOKEN",
        secret_env: str = "VECTOR_BUS_ROOT_SECRET",
        cors_env: str = "VECTOR_CORS_ORIGINS",
        default_cors: str = "http://localhost:3000",
    ) -> None:
        self.token_env = token_env
        self.secret_env = secret_env
        self.cors_env = cors_env
        self.default_cors = default_cors
        token = os.environ.get(token_env) or os.environ.get(secret_env)
        self.token: Optional[str] = token if token else None
        # Dev-anonymous when no secret is configured.
        self.enabled = self.token is not None

    # -- enforcement --------------------------------------------------------

    def enforce(self, handler) -> bool:
        """Enforce the bearer token on ``handler``.

        Returns ``True`` when the request is authorized (caller may continue).
        Returns ``False`` after having written a 401 response (caller must
        return). In dev-anonymous mode this always returns ``True``.
        """
        if not self.enabled:
            return True
        header = handler.headers.get("Authorization", "") if hasattr(handler, "headers") else ""
        if not header.startswith("Bearer "):
            self._send_401(handler, "missing or malformed Authorization header")
            return False
        presented = header[len("Bearer "):].strip()
        if not presented or presented != self.token:
            self._send_401(handler, "invalid token")
            return False
        return True

    def _send_401(self, handler, message: str) -> None:
        body = ('{"error":"unauthorized","message":"%s"}' % message).encode("utf-8")
        handler.send_response(401)
        handler.send_header("Content-Type", "application/json")
        for k, v in self.cors_headers().items():
            handler.send_header(k, v)
        handler.end_headers()
        handler.wfile.write(body)

    # -- CORS ---------------------------------------------------------------

    def cors_headers(self) -> Dict[str, str]:
        """Return the CORS headers to attach to every response.

        Dev-anonymous mode returns an open ``*`` origin (matches prior
        behavior). Secured mode returns the explicitly allowed origins.
        """
        if not self.enabled:
            return {"Access-Control-Allow-Origin": "*"}
        origins = self._allowed_origins()
        # Reflect a single allowed origin; for multi-origin, the caller's
        # Origin header should be echoed — engines may extend later.
        origin = origins[0] if origins else self.default_cors
        return {
            "Access-Control-Allow-Origin": origin,
            "Access-Control-Allow-Methods": "GET, OPTIONS",
            "Access-Control-Allow-Headers": "Authorization, Content-Type",
        }

    def _allowed_origins(self) -> List[str]:
        raw = os.environ.get(self.cors_env, "")
        return [o.strip() for o in raw.split(",") if o.strip()]
