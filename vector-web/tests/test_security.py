"""Security hardening tests for the vector-web composition root.

Covers the production-grade security features added to the web edge:
  * Security response headers on every response (X-Content-Type-Options,
    X-Frame-Options, Referrer-Policy, Content-Security-Policy, X-XSS-Protection).
  * Content-Length on every response (HTTP/1.1 keep-alive correctness).
  * X-Request-ID correlation ID echoed on every response.
  * Path traversal protection in static file serving (GET /../etc/passwd style).
  * Request body size limit (413 on oversized POST).
  * Timing-safe token comparison (token still enforced, just not timing-leaky).
  * CORS preflight (OPTIONS) returns Allow header + CORS headers.
  * POST /traffic works (not just GET) and forwards to routing overlay.
  * do_POST for proxied paths (e.g. /incidents) works with auth.
"""

import os
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from vector_web import _csp_connect_src, make_server

DEAD_OSRM = "http://127.0.0.1:1"


class FakeBackend(BaseHTTPRequestHandler):
    received_auth = []
    received_body = None
    received_method = None

    def log_message(self, *a):
        return

    def do_GET(self):
        FakeBackend.received_auth.append(self.headers.get("Authorization", ""))
        FakeBackend.received_method = "GET"
        if self.path.startswith("/traffic"):
            body = b'{"features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[13.39,52.51],[13.40,52.52]]},"properties":{"congestion":"heavy","mean_speed_kmh":10}}]}'
            self._send(200, body)
        elif self.path.startswith("/route"):
            body = b'{"features":[{"geometry":{"type":"LineString","coordinates":[[13.39,52.51],[13.41,52.54]]},"properties":{"distance_km":2.0,"duration_s":120,"steps":[]}}]}'
            self._send(200, body)
        elif self.path.startswith("/incidents"):
            body = b'{"id":"inc-1","lon":13.40,"lat":52.52,"kind":"hazard"}'
            self._send(201, body)
        else:
            self._send(404, b'{}')

    def do_POST(self):
        FakeBackend.received_auth.append(self.headers.get("Authorization", ""))
        FakeBackend.received_method = "POST"
        length = int(self.headers.get("Content-Length", "0") or "0")
        FakeBackend.received_body = self.rfile.read(length) if length else b""
        if self.path.startswith("/traffic"):
            body = b'{"features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[13.39,52.51],[13.40,52.52]]},"properties":{"congestion":"heavy","mean_speed_kmh":10}}]}'
            self._send(200, body)
        elif self.path.startswith("/incidents"):
            body = b'{"id":"inc-1","lon":13.40,"lat":52.52,"kind":"hazard"}'
            self._send(201, body)
        else:
            self._send(404, b'{}')

    def _send(self, code, body, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _free_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class SecurityHeadersTest(unittest.TestCase):
    """Every response must carry the security baseline headers."""

    web = None
    web_port = None

    @classmethod
    def setUpClass(cls):
        cls.web_port = _free_port()
        os.environ["VECTOR_OSRM_URL"] = DEAD_OSRM
        os.environ["VECTOR_WEB_TOKEN"] = "sec-test-token"
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        if cls.web:
            cls.web.shutdown()

    def _get(self, path, headers=None):
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def test_healthz_has_security_headers(self):
        status, headers, _ = self._get("/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("X-Content-Type-Options"), "nosniff")
        self.assertEqual(headers.get("X-Frame-Options"), "DENY")
        self.assertIn("Referrer-Policy", headers)
        self.assertIn("Content-Security-Policy", headers)
        self.assertEqual(headers.get("X-XSS-Protection"), "1; mode=block")

    def test_security_headers_on_401(self):
        status, headers, _ = self._get("/search?q=test")
        self.assertEqual(status, 401)
        self.assertEqual(headers.get("X-Content-Type-Options"), "nosniff")
        self.assertEqual(headers.get("X-Frame-Options"), "DENY")

    def test_content_length_present(self):
        status, headers, body = self._get("/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Length"), str(len(body)))

    def test_request_id_echoed(self):
        status, headers, _ = self._get("/healthz", headers={"X-Request-ID": "abc123"})
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("X-Request-ID"), "abc123")

    def test_request_id_generated_when_absent(self):
        status, headers, _ = self._get("/healthz")
        self.assertEqual(status, 200)
        rid = headers.get("X-Request-ID", "")
        self.assertTrue(len(rid) > 0, "X-Request-ID should be generated")

    def test_path_traversal_blocked(self):
        # /../etc/passwd should not escape the static dir. With a valid token
        # (auth passes), the static handler must still refuse to serve files
        # outside STATIC_DIR.
        status, _, body = self._get("/../etc/passwd", headers={"Authorization": "Bearer sec-test-token"})
        # Should be 404 (not found / traversal blocked), not 200 with /etc/passwd.
        self.assertIn(status, (404, 400))
        self.assertNotIn(b"root:", body)

    def test_static_asset_served_with_content_length(self):
        status, headers, body = self._get("/manifest.webmanifest")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Length"), str(len(body)))
        self.assertEqual(headers.get("Content-Type"), "application/manifest+json")


class BodySizeLimitTest(unittest.TestCase):
    """POST requests exceeding _MAX_BODY_BYTES must get 413."""

    web = None
    web_port = None
    backend = None
    backend_port = None

    @classmethod
    def setUpClass(cls):
        cls.backend_port = _free_port()
        cls.backend = ThreadingHTTPServer(("127.0.0.1", cls.backend_port), FakeBackend)
        threading.Thread(target=cls.backend.serve_forever, daemon=True).start()

        cls.web_port = _free_port()
        os.environ["VECTOR_ROUTING_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TRAFFIC_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_GEOCODER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TILE_SERVER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_OSRM_URL"] = DEAD_OSRM
        os.environ["VECTOR_WEB_TOKEN"] = "sec-test-token"
        os.environ["VECTOR_MAX_BODY_BYTES"] = "1024"
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        os.environ.pop("VECTOR_MAX_BODY_BYTES", None)
        if cls.web:
            cls.web.shutdown()
        if cls.backend:
            cls.backend.shutdown()

    def _post(self, path, body, headers=None):
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, data=body, method="POST",
                                     headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_oversized_body_rejected_413(self):
        big = b"x" * 2048  # exceeds 1024 limit
        status, body = self._post(
            "/incidents", big,
            headers={"Authorization": "Bearer sec-test-token",
                     "Content-Type": "application/json"},
        )
        self.assertEqual(status, 413)
        self.assertIn(b"too large", body)

    def test_small_body_accepted(self):
        status, body = self._post(
            "/incidents", b'{"lon":13.4,"lat":52.5,"kind":"hazard"}',
            headers={"Authorization": "Bearer sec-test-token",
                     "Content-Type": "application/json"},
        )
        self.assertEqual(status, 201)


class OptionsPreflightTest(unittest.TestCase):
    """CORS preflight (OPTIONS) must return Allow + CORS headers."""

    web = None
    web_port = None

    @classmethod
    def setUpClass(cls):
        cls.web_port = _free_port()
        os.environ["VECTOR_OSRM_URL"] = DEAD_OSRM
        os.environ["VECTOR_WEB_TOKEN"] = "sec-test-token"
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        if cls.web:
            cls.web.shutdown()

    def test_options_returns_204_and_allow(self):
        url = "http://127.0.0.1:%d/search" % self.web_port
        req = urllib.request.Request(url, method="OPTIONS")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                status = resp.status
                headers = dict(resp.headers)
        except urllib.error.HTTPError as e:
            status = e.code
            headers = dict(e.headers)
        self.assertEqual(status, 204)
        self.assertIn("Allow", headers)
        self.assertIn("GET", headers["Allow"])
        self.assertIn("POST", headers["Allow"])
        self.assertIn("OPTIONS", headers["Allow"])
        self.assertEqual(headers.get("X-Content-Type-Options"), "nosniff")


class PostTrafficTest(unittest.TestCase):
    """POST /traffic must work (not just GET) and forward to routing overlay."""

    web = None
    web_port = None
    backend = None
    backend_port = None

    @classmethod
    def setUpClass(cls):
        cls.backend_port = _free_port()
        cls.backend = ThreadingHTTPServer(("127.0.0.1", cls.backend_port), FakeBackend)
        threading.Thread(target=cls.backend.serve_forever, daemon=True).start()

        cls.web_port = _free_port()
        os.environ["VECTOR_ROUTING_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TRAFFIC_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_GEOCODER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TILE_SERVER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_OSRM_URL"] = DEAD_OSRM
        os.environ["VECTOR_WEB_TOKEN"] = "sec-test-token"
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        if cls.web:
            cls.web.shutdown()
        if cls.backend:
            cls.backend.shutdown()

    def _post(self, path, body, headers=None):
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, data=body, method="POST",
                                     headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_post_traffic_works(self):
        status, body = self._post(
            "/traffic", b'{"features":[]}',
            headers={"Authorization": "Bearer sec-test-token",
                     "Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)

    def test_post_traffic_requires_token(self):
        status, _ = self._post(
            "/traffic", b'{"features":[]}',
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 401)


class CspConnectSrcTest(unittest.TestCase):
    """connect-src must open for the packaged (cross-origin) client only when
    the deployment says so, and must never be rewritable by a hostile value.

    A Capacitor WebView's page origin is capacitor:// or http://localhost, so
    every call to the public host is cross-origin. CORS at the proxy is not
    enough: CSP is an independent gate, and a closed connect-src blocks the
    fetch before it leaves the browser while every endpoint still returns 200.
    """

    def setUp(self):
        self._prev = os.environ.get("VECTOR_PUBLIC_ORIGIN")

    def tearDown(self):
        if self._prev is None:
            os.environ.pop("VECTOR_PUBLIC_ORIGIN", None)
        else:
            os.environ["VECTOR_PUBLIC_ORIGIN"] = self._prev

    def _with(self, value):
        if value is None:
            os.environ.pop("VECTOR_PUBLIC_ORIGIN", None)
        else:
            os.environ["VECTOR_PUBLIC_ORIGIN"] = value
        return _csp_connect_src()

    def test_default_is_self_only(self):
        self.assertEqual(self._with(None), "connect-src 'self'; ")

    def test_empty_value_is_self_only(self):
        self.assertEqual(self._with(""), "connect-src 'self'; ")

    def test_public_origin_appended(self):
        self.assertEqual(
            self._with("https://your-host.example.com"),
            "connect-src 'self' https://your-host.example.com; ",
        )

    def test_multiple_origins_space_separated(self):
        self.assertEqual(
            self._with("https://a.example https://b.example:8443"),
            "connect-src 'self' https://a.example https://b.example:8443; ",
        )

    def test_directive_injection_is_dropped(self):
        # A value carrying ';' must not be able to terminate connect-src and
        # append a policy of its own.
        out = self._with("https://evil.example; script-src *")
        self.assertEqual(out, "connect-src 'self'; ")
        self.assertNotIn("script-src", out)

    def test_wildcard_rejected(self):
        self.assertEqual(self._with("*"), "connect-src 'self'; ")

    def test_non_http_scheme_rejected(self):
        self.assertEqual(self._with("javascript:alert(1)"), "connect-src 'self'; ")
        self.assertEqual(self._with("data:"), "connect-src 'self'; ")

    def test_origin_with_path_rejected(self):
        # connect-src takes origins, not URLs with paths.
        self.assertEqual(
            self._with("https://your-host.example.com/route"), "connect-src 'self'; "
        )


if __name__ == "__main__":
    unittest.main()


class AccessLogRedactionTest(unittest.TestCase):
    """What an access log is allowed to say.

    The edge logged nothing, so four search failures from two recorded drives
    had to be reproduced by hand against production days later. Turning
    logging on is only safe if it cannot become a second, unregulated store of
    the two things that must not be in one: the bearer token, and the driver's
    position (ADR-0066 admits location to a store only after an accuracy
    floor, coordinate precision and temporal coarsening, none of which a log
    line applies).
    """

    def _safe(self, path):
        from vector_web import _safe_path
        return _safe_path(path)

    def test_the_query_text_survives(self):
        """A log that cannot say what was asked cannot explain the answer."""
        self.assertIn("q=Woqod+Hilal", self._safe("/search?q=Woqod+Hilal"))

    def test_the_position_is_removed(self):
        out = self._safe("/search?q=Woqod&lat=25.2860&lon=51.5310")
        self.assertNotIn("25.28", out)
        self.assertNotIn("51.53", out)
        self.assertIn("q=Woqod", out)

    def test_that_a_position_was_sent_is_still_visible(self):
        """Enough to diagnose a proximity bug, without being location."""
        self.assertIn("redacted=lat+lon",
                      self._safe("/search?q=Woqod&lat=25.28&lon=51.53"))

    def test_the_token_is_removed(self):
        out = self._safe("/search?q=x&token=s3cret")
        self.assertNotIn("s3cret", out)
        self.assertIn("redacted=token", out)

    def test_route_endpoints_do_not_log_their_endpoints(self):
        """`from`/`to` on /navigate are a trip's origin and destination."""
        out = self._safe("/navigate?from=25.1,51.2&to=25.3,51.4&lang=en")
        self.assertNotIn("25.1", out)
        self.assertNotIn("51.4", out)
        self.assertIn("lang=en", out)

    def test_a_path_without_a_query_is_unchanged(self):
        self.assertEqual("/healthz", self._safe("/healthz"))

    def test_tile_coordinates_are_kept(self):
        """A tile path is a map address, not a person's location."""
        self.assertEqual("/tiles/14/10537/7002.mvt",
                         self._safe("/tiles/14/10537/7002.mvt"))
