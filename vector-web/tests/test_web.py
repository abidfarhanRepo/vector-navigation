"""Web composition-root tests (Wave 26e + expansion).

Runs the real vector-web proxy against a fake backend http server (no external
engines needed), proving:
  * /route and /navigate are gated by the web token (401 without bearer),
    and the bearer is forwarded to the backend (ADR-0050).
  * /search and /incidents are proxied to the geocoder/traffic engines with
    the token forwarded; POST /incidents is rejected without a token.
  * /healthz is open (no auth).
  * /tiles and /vendor assets are public.
"""

import os
import time
import threading
import unittest
import urllib.error
import urllib.request
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from vector_web import make_server

# A dead endpoint so the proxy's OSRM fast-path is skipped and /route falls
# back to the FakeBackend — keeps routing assertions deterministic regardless
# of whether a real OSRM is listening on :5000 in this environment.
DEAD_OSRM = "http://127.0.0.1:1"


class FakeBackend(BaseHTTPRequestHandler):
    received_auth = []
    last_route_query = None

    def log_message(self, *a):
        return

    def do_GET(self):
        FakeBackend.received_auth.append(self.headers.get("Authorization", ""))
        if self.path.startswith("/route") or self.path.startswith("/navigate"):
            FakeBackend.last_route_query = self.path.split("?", 1)[1] if "?" in self.path else ""
            if "fail=1" in self.path:
                self._send(404, b'{"error":"no route"}')
                return
            body = b'{"features":[{"geometry":{"type":"LineString","coordinates":[[13.39,52.51],[13.41,52.54]]},"properties":{"distance_km":2.0,"duration_s":120,"steps":[{"type":"depart","instruction":"Head north","location":[13.39,52.51],"cumulative_distance_m":0},{"type":"arrive","instruction":"Arrive","location":[13.41,52.54],"cumulative_distance_m":2000}]}}]}'
            self._send(200, body)
        elif self.path.startswith("/traffic"):
            body = b'{"segments":[{"geometry":[[52.51,13.39],[52.52,13.40]],"congestion":"heavy"}]}'
            self._send(200, body)
        elif self.path.startswith("/search"):
            body = b'{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[13.40,52.52]},"properties":{"name":"Doha Corniche","kind":"road"}}]}'
            self._send(200, body)
        elif self.path.startswith("/reverse"):
            body = b'{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[13.40,52.52]},"properties":{"name":"Salwa Road"}}]}'
            self._send(200, body)
        elif self.path.startswith("/tiles/"):
            body = b"\x1a\x04\x00\x00\x00"  # minimal MVT-ish protobuf body
            self._send(200, body, ctype="application/x-protobuf")
        else:
            self._send(404, b"{}")

    def do_POST(self):
        FakeBackend.received_auth.append(self.headers.get("Authorization", ""))
        if self.path.startswith("/incidents"):
            body = b'{"id":"inc-1","lon":13.40,"lat":52.52,"kind":"hazard","note":""}'
            self._send(201, body)
        else:
            self._send(404, b"{}")

    def _send(self, code, body, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body)


def _free_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class WebProxyTest(unittest.TestCase):
    backend = None
    web = None
    backend_port = None
    web_port = None

    @classmethod
    def setUpClass(cls):
        cls.backend_port = _free_port()
        cls.backend = ThreadingHTTPServer(("127.0.0.1", cls.backend_port), FakeBackend)
        t = threading.Thread(target=cls.backend.serve_forever, daemon=True)
        t.start()

        cls.web_port = _free_port()
        os.environ["VECTOR_ROUTING_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TRAFFIC_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_GEOCODER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TILE_SERVER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_OSRM_URL"] = DEAD_OSRM
        cls.web = make_server(cls.web_port)
        t2 = threading.Thread(target=cls.web.serve_forever, daemon=True)
        t2.start()

    @classmethod
    def tearDownClass(cls):
        if cls.web:
            cls.web.shutdown()
        if cls.backend:
            cls.backend.shutdown()

    def _get(self, path, headers=None):
        import urllib.request
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_healthz_open(self):
        status, _ = self._get("/healthz")
        self.assertEqual(status, 200)

    def test_route_proxied(self):
        status, body = self._get("/route?from=52.51,13.39&to=52.54,13.41")
        self.assertEqual(status, 200)
        self.assertIn(b"LineString", body)

    def test_traffic_proxied(self):
        status, body = self._get("/traffic")
        self.assertEqual(status, 200)
        self.assertIn(b"heavy", body)

    def test_tiles_proxied(self):
        import urllib.request
        url = "http://127.0.0.1:%d/tiles/12/2200/1343.mvt" % self.web_port
        with urllib.request.urlopen(url, timeout=5) as resp:
            status = resp.status
            ctype = resp.headers.get("Content-Type", "")
            body = resp.read()
        self.assertEqual(status, 200)
        self.assertEqual(ctype, "application/x-protobuf")
        self.assertEqual(body, b"\x1a\x04\x00\x00\x00")

    def test_index_served(self):
        status, body = self._get("/")
        self.assertEqual(status, 200)
        self.assertIn(b"maplibre", body)

    def test_search_proxied(self):
        # Dev-anonymous mode: token not required (WebProxyTest sets no token).
        status, body = self._get("/search?q=doha")
        self.assertEqual(status, 200)
        self.assertIn(b"Doha Corniche", body)

    def test_reverse_proxied(self):
        status, body = self._get("/reverse?lat=52.52&lon=13.40", headers={"Authorization": "Bearer secret-web-token"})
        self.assertEqual(status, 200)
        self.assertIn(b"Salwa Road", body)


class WebAuthTest(unittest.TestCase):
    """Edge auth: with VECTOR_WEB_TOKEN set, enforce 401 without bearer,
    forward token to backends, and gate /route+/navigate (ADR-0050)."""

    backend = None
    web = None
    backend_port = None
    web_port = None

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
        os.environ["VECTOR_WEB_TOKEN"] = "secret-web-token"
        os.environ["VECTOR_SERVICE_TOKEN"] = "secret-service-token"
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        os.environ.pop("VECTOR_SERVICE_TOKEN", None)
        if cls.web:
            cls.web.shutdown()
        if cls.backend:
            cls.backend.shutdown()

    def _get(self, path, headers=None):
        import urllib.request
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def _post(self, path, body=b"{}", headers=None):
        import urllib.request
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, data=body, method="POST", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_healthz_still_open_with_auth(self):
        status, _ = self._get("/healthz")
        self.assertEqual(status, 200)

    def test_route_gated_401_anonymous(self):
        status, _ = self._get("/route?from=52.51,13.39&to=52.54,13.41")
        self.assertEqual(status, 401)

    def test_navigate_gated_401_anonymous(self):
        status, _ = self._get("/navigate?from=52.51,13.39&to=52.54,13.41")
        self.assertEqual(status, 401)

    def test_bearer_passes_and_token_forwarded(self):
        FakeBackend.received_auth.clear()
        status, body = self._get(
            "/route?from=52.51,13.39&to=52.54,13.41",
            headers={"Authorization": "Bearer secret-web-token"},
        )
        self.assertEqual(status, 200)
        self.assertIn(b"LineString", body)
        # ADR-0050 two-secret contract: the edge authenticates upstream with
        # VECTOR_SERVICE_TOKEN, never the caller's web token.
        self.assertIn("Bearer secret-service-token", FakeBackend.received_auth)
        self.assertNotIn("Bearer secret-web-token", FakeBackend.received_auth)

    def test_index_open_via_query_token(self):
        status, body = self._get("/?token=secret-web-token")
        self.assertEqual(status, 200)
        self.assertIn(b"maplibre", body)

    def test_index_unauthenticated_401(self):
        status, _ = self._get("/")
        self.assertEqual(status, 401)

    def test_vendor_assets_public_without_token(self):
        status, body = self._get("/vendor/maplibre-gl.js")
        self.assertEqual(status, 200)
        self.assertIn(b"maplibregl", body)

    def test_tiles_public_without_token(self):
        status, _ = self._get("/tiles/12/2633/1750.mvt")
        self.assertEqual(status, 200)

    def test_search_requires_token(self):
        status, _ = self._get("/search?q=doha")
        self.assertEqual(status, 401)

    def test_reverse_requires_token(self):
        status, _ = self._get("/reverse?lat=52.52&lon=13.40")
        self.assertEqual(status, 401)

    def test_incidents_post_requires_token(self):
        status, _ = self._post("/incidents", body=b'{"lon":13.4,"lat":52.5,"kind":"hazard"}')
        self.assertEqual(status, 401)

    def test_incidents_post_forwards_token(self):
        FakeBackend.received_auth.clear()
        status, body = self._post(
            "/incidents",
            body=b'{"lon":13.40,"lat":52.52,"kind":"hazard"}',
            headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"},
        )
        self.assertEqual(status, 201)
        self.assertIn(b"inc-1", body)
        self.assertIn("Bearer secret-service-token", FakeBackend.received_auth)
        self.assertNotIn("Bearer secret-web-token", FakeBackend.received_auth)


class WebRerouteTest(unittest.TestCase):
    """Proxy-level coverage for the Waze-class nav UX:
    traffic-aware reroute forwards `traffic=1`, and backend 404 is passed
    through so the viewer can show 'no route' instead of a blank line."""

    backend = None
    web = None
    backend_port = None
    web_port = None

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
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        if cls.web:
            cls.web.shutdown()
        if cls.backend:
            cls.backend.shutdown()

    def _get(self, path, headers=None):
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_traffic_param_forwarded(self):
        FakeBackend.last_route_query = None
        status, _ = self._get("/route?from=52.51,13.39&to=52.54,13.41&traffic=1")
        self.assertEqual(status, 200)
        self.assertIn("traffic=1", FakeBackend.last_route_query or "")

    def test_backend_404_passthrough(self):
        # The viewer shows a 'no route' toast on backend failure.
        status, body = self._get("/route?from=52.51,13.39&to=52.54,13.41&fail=1")
        self.assertEqual(status, 404)
        self.assertIn(b"no route", body)

    def test_viewer_has_nav_header_and_toast(self):
        # Waze-class nav header + user toast shipped in the UX wave.
        status, body = self._get("/")
        self.assertEqual(status, 200)
        self.assertIn(b"navhead", body)
        self.assertIn(b"recalc", body)
        self.assertIn(b"toast", body)

    def test_viewer_has_mobile_tool_dock_and_tracebar(self):
        # Mobile-first: the control panel becomes a bottom dock and the GPS
        # trace bar (dead stub removed) is present in the markup.
        status, body = self._get("/")
        self.assertEqual(status, 200)
        self.assertIn(b"tracebar", body)
        self.assertIn(b"trace-toggle", body)
        self.assertIn(b"trace-upload", body)
        self.assertIn(b"voice-toggle", body)
        # No blocking window.prompt() — pin naming is now an inline modal. The
        # JS comment may mention prompt(), so assert on the real mechanism:
        # the code must route through the askPinName() inline modal.
        self.assertIn(b"askPinName", body)
        self.assertIn(b"pin-modal", body)
        self.assertNotIn(b" = window.prompt(", body)
        self.assertIn(b"pin-modal", body)


class WebTracesTest(unittest.TestCase):
    """Wave 47d: /traces GPS-capture sink — gated, validated, persisted."""
    backend = None
    web = None
    backend_port = None
    web_port = None
    _tmp = None

    @classmethod
    def setUpClass(cls):
        import tempfile
        cls._tmp = tempfile.mktemp(suffix=".jsonl")
        cls.backend_port = _free_port()
        cls.backend = ThreadingHTTPServer(("127.0.0.1", cls.backend_port), FakeBackend)
        threading.Thread(target=cls.backend.serve_forever, daemon=True).start()
        cls.web_port = _free_port()
        os.environ["VECTOR_ROUTING_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TRAFFIC_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_GEOCODER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_TILE_SERVER_URL"] = "http://127.0.0.1:%d" % cls.backend_port
        os.environ["VECTOR_OSRM_URL"] = DEAD_OSRM
        os.environ["VECTOR_WEB_TOKEN"] = "secret-web-token"
        os.environ["VECTOR_TRACE_FILE"] = cls._tmp
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        os.environ.pop("VECTOR_SERVICE_TOKEN", None)
        os.environ.pop("VECTOR_TRACE_FILE", None)
        if cls.web:
            cls.web.shutdown()
        if cls.backend:
            cls.backend.shutdown()

    def _post(self, path, body=b"{}", headers=None):
        import urllib.request
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, data=body, method="POST", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def _get(self, path, headers=None):
        import urllib.request
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        req = urllib.request.Request(url, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_anon_post_is_401(self):
        status, _ = self._post("/traces", body=b'{"kind":"track","points":[{"lng":51.5,"lat":25.2}]}')
        self.assertEqual(status, 401)

    def test_valid_trace_stored_and_counted(self):
        payload = json.dumps({
            "kind": "probe",
            "points": [
                {"lng": 51.5, "lat": 25.2, "t": 1700000000000, "s": 12.3, "a": 5.0},
                {"lng": 51.501, "lat": 25.201},
                {"lng": 999, "lat": 0},          # out of range -> dropped
                {"lng": "bad", "lat": 1},           # non-numeric -> dropped
            ],
        }).encode()
        status, body = self._post(
            "/traces", body=payload,
            headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["kind"], "probe")
        self.assertEqual(data["stored"], 2)   # 2 valid of 4
        self.assertEqual(data["dropped"]["bounds"], 1)
        self.assertEqual(data["dropped"]["malformed"], 1)
        self.assertGreaterEqual(data["total"], 2)

    def test_privacy_gate_raw_payload_cannot_reach_store(self):
        # A raw track with a 60 m-accuracy point and an untruncated short trip
        # must be gated by adr-0065 (accuracy floor + endpoint truncation), so
        # it can never be appended to the quarantine store.
        #
        # Carries a trip token because a `track` now requires one (adr-0068 /
        # ticket 24): without it the request is refused before the gate is
        # reached, which would make this test pass for the wrong reason.
        points = []
        for i in range(20):
            points.append({"lng": 51.5, "lat": 25.2 + i * 0.0009, "t": 1700000000000 + i * 1000, "a": 60.0})
        payload = json.dumps({
            "kind": "track", "points": points,
            "trip": "test-trip-token-for-gate-check",
        }).encode()
        status, body = self._post(
            "/traces", body=payload,
            headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        data = json.loads(body)
        # every point failed the accuracy floor -> 0 stored
        self.assertEqual(data["stored"], 0)
        self.assertEqual(data["dropped"]["accuracy"], 20)

    def test_bad_kind_rejected(self):
        body = json.dumps({"kind": "bogus", "points": [{"lng": 1, "lat": 1}]}).encode()
        status, _ = self._post(
            "/traces", body=body,
            headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)

    def test_empty_points_rejected(self):
        body = json.dumps({"kind": "probe", "points": []}).encode()
        status, _ = self._post(
            "/traces", body=body,
            headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)

    def test_invalid_json_rejected(self):
        status, _ = self._post(
            "/traces", body=b"not-json",
            headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)

    def test_get_recent_returns_stored(self):
        # Seed one trace first.
        payload = json.dumps({"kind": "probe", "points": [{"lng": 51.5, "lat": 25.2}]}).encode()
        self._post("/traces", body=payload,
                   headers={"Authorization": "Bearer secret-web-token", "Content-Type": "application/json"})
        status, body = self._get("/traces", headers={"Authorization": "Bearer secret-web-token"})
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertGreaterEqual(data["count"], 1)
        self.assertIn("points", data)


class WebCollectionTierTest(WebTracesTest):
    """Ticket 19/22/23 at the HTTP boundary.

    Inherits the fixture above rather than rebuilding it: ``_make_store`` is a
    process-wide singleton bound to the first path it sees, so a second server on
    a second database would silently assert against the first one's rows.
    """

    NOW = 1_770_000_000_000       # a fixed "now" so age policy is deterministic
    DAY = 86_400_000

    def _auth(self):
        return {"Authorization": "Bearer secret-web-token",
                "Content-Type": "application/json"}

    def _gpx(self, n=120, *, t0=None, lat0=25.4000, step=0.00009, dt=2000):
        from datetime import datetime, timezone
        t0 = t0 if t0 is not None else int(time.time() * 1000) - 2 * self.DAY
        pts = []
        for i in range(n):
            t = t0 + i * dt
            stamp = datetime.fromtimestamp(t / 1000.0, tz=timezone.utc).strftime(
                "%Y-%m-%dT%H:%M:%SZ")
            pts.append(f'<trkpt lat="{lat0 + i * step:.6f}" lon="51.6000">'
                       f'<time>{stamp}</time><hdop>1.1</hdop></trkpt>')
        return ('<gpx version="1.1" creator="OsmAnd~" '
                'xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>'
                + "".join(pts) + '</trkseg></trk></gpx>').encode()

    def _store(self):
        from vector_web.trace_store import _make_store
        return _make_store()

    # -- ticket 19: import ---------------------------------------------------
    def test_preview_stores_nothing(self):
        before = self._store().count()
        status, body = self._post("/import/preview?name=t.gpx", body=self._gpx(),
                                  headers=self._auth())
        self.assertEqual(status, 200)
        plan = json.loads(body)
        self.assertEqual(plan["stored"], 0)
        self.assertEqual(plan["trips_detected"], 1)
        self.assertGreater(plan["points_acceptable"], 0)
        self.assertEqual(self._store().count(), before,
                         "a dry run must not write to the store")

    def test_preview_returns_no_geometry(self):
        _, body = self._post("/import/preview", body=self._gpx(), headers=self._auth())
        text = body.decode()
        self.assertNotIn("_points", text)
        # A coordinate from the fixture must not appear anywhere in the response.
        self.assertNotIn("25.4", text)

    def test_commit_without_consent_is_refused(self):
        before = self._store().count()
        status, body = self._post("/import/commit", body=self._gpx(), headers=self._auth())
        self.assertEqual(status, 400)
        self.assertIn("consent", json.loads(body)["error"])
        self.assertEqual(self._store().count(), before)

    def test_commit_stores_with_derived_import_source(self):
        data = self._gpx(lat0=25.5000)
        status, body = self._post("/import/commit?consent=granted", body=data,
                                  headers=self._auth())
        self.assertEqual(status, 200)
        result = json.loads(body)
        self.assertEqual(result["source"], "import")
        self.assertEqual(result["source_provenance"], "derived")
        self.assertGreater(result["stored"], 0)
        self.assertEqual(result["trips_stored"], 1)
        rows = [r for r in self._store().recent(500) if r["source"] == "import"]
        self.assertTrue(rows, "no row carried the import provenance")

    def test_same_file_twice_is_refused_not_counted_twice(self):
        """One journey uploaded twice must not become two trips (adr-0069 §6)."""
        data = self._gpx(lat0=25.6000)
        first = json.loads(self._post("/import/commit?consent=granted", body=data,
                                      headers=self._auth())[1])
        self.assertGreater(first["stored"], 0)
        second = json.loads(self._post("/import/commit?consent=granted", body=data,
                                       headers=self._auth())[1])
        self.assertEqual(second["stored"], 0)
        self.assertEqual(second["trips_stored"], 0)
        self.assertEqual(second["trips_duplicate"], 1)

    def test_duplicate_is_visible_in_the_preview(self):
        data = self._gpx(lat0=25.7000)
        self._post("/import/commit?consent=granted", body=data, headers=self._auth())
        plan = json.loads(self._post("/import/preview", body=data,
                                     headers=self._auth())[1])
        self.assertEqual(plan["trips_duplicate"], 1)
        self.assertEqual(plan["points_acceptable"], 0)
        self.assertEqual(plan["trips"][0]["refused"], "duplicate_trip")

    def test_unreadable_file_explains_itself(self):
        status, body = self._post("/import/preview?name=x.zip", body=b"PK\x03\x04nope",
                                  headers=self._auth())
        self.assertEqual(status, 400)
        detail = json.loads(body)["detail"]
        self.assertIn("GPX", detail)

    def test_oversized_file_says_what_to_do(self):
        os.environ["VECTOR_MAX_IMPORT_BYTES"] = "500"
        try:
            status, body = self._post("/import/preview", body=self._gpx(),
                                      headers=self._auth())
        finally:
            os.environ.pop("VECTOR_MAX_IMPORT_BYTES", None)
        self.assertEqual(status, 413)
        detail = json.loads(body)["detail"]
        self.assertIn("split", detail.lower())

    def test_import_needs_auth(self):
        status, _ = self._post("/import/preview", body=self._gpx())
        self.assertEqual(status, 401)

    def test_track_older_than_the_age_bound_is_refused(self):
        old = self._gpx(lat0=25.8000, t0=int(time.time() * 1000) - 400 * self.DAY)
        result = json.loads(self._post("/import/commit?consent=granted", body=old,
                                       headers=self._auth())[1])
        self.assertEqual(result["stored"], 0)
        self.assertEqual(result["would_drop"]["age_too_old"], 120)

    # -- ticket 22: provenance ----------------------------------------------
    def test_client_cannot_declare_the_import_source(self):
        """`import` is reachable only from the route that parsed a file."""
        payload = json.dumps({
            "kind": "probe", "source": "import",
            "points": [{"lng": 51.5, "lat": 25.9, "t": 1_700_000_000_000, "a": 4.0}],
        }).encode()
        status, body = self._post("/traces", body=payload, headers=self._auth())
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["source"], "live")

    def test_unknown_source_falls_back_to_live(self):
        payload = json.dumps({
            "kind": "probe", "source": "pigeon-post",
            "points": [{"lng": 51.5, "lat": 25.91, "t": 1_700_000_000_000, "a": 4.0}],
        }).encode()
        _, body = self._post("/traces", body=payload, headers=self._auth())
        self.assertEqual(json.loads(body)["source"], "live")

    def test_native_is_declared_and_labelled_as_declared(self):
        payload = json.dumps({
            "kind": "probe", "source": "native",
            "points": [{"lng": 51.5, "lat": 25.92, "t": 1_700_000_000_000, "a": 4.0}],
        }).encode()
        _, body = self._post("/traces", body=payload, headers=self._auth())
        data = json.loads(body)
        self.assertEqual(data["source"], "native")
        self.assertEqual(data["source_provenance"], "declared")

    def test_column_only_ever_holds_allowed_values(self):
        from vector_web.trace_store import SOURCES
        rows = self._store().recent(1000)
        for row in rows:
            self.assertIn(row["source"], SOURCES)

    def test_privacy_counters_split_by_source(self):
        status, body = self._get("/privacy-counters")
        self.assertEqual(status, 200)
        summary = json.loads(body)
        self.assertIn("by_source", summary)
        self.assertIn("import", summary["by_source"])
        self.assertEqual(summary["by_source"]["import"]["provenance"], "derived")
        self.assertEqual(summary["by_source"]["live"]["provenance"], "declared")

    # -- ticket 23: per-trip truncation at the HTTP boundary ----------------
    def test_five_payload_trip_keeps_its_interior(self):
        """The property ticket 23 exists for, asserted through the real endpoint."""
        token = "trip-token-five-payload-integration"
        base_lat, step = 26.0000, 0.00009        # ~10 m per point
        made = [{"lng": 51.7, "lat": round(base_lat + i * step, 5),
                 "t": 1_700_000_100_000 + i * 2000, "a": 5.0} for i in range(120)]
        stored = 0
        for start in range(0, 120, 24):
            payload = json.dumps({"kind": "track", "trip": token,
                                  "points": made[start:start + 24],
                                  "end": start + 24 >= 120}).encode()
            status, body = self._post("/traces", body=payload, headers=self._auth())
            self.assertEqual(status, 200)
            stored += json.loads(body)["stored"]
        # Per-payload truncation would have stored 0 here: a 24-point payload spans
        # ~230 m, which is less than the two 200 m cuts. Per-trip truncation keeps
        # the interior and loses only the two true ends.
        self.assertEqual(stored, 80)

        # The invariant, in metres rather than in degrees: nothing committed may
        # lie within 200 m along-track of the trip's true first or last point.
        from vector_privacy.gate import haversine_m
        rows = [r for r in self._store().recent(1000)
                if abs(r["lat"] - base_lat) < 0.02 and abs(r["lng"] - 51.7) < 0.001]
        self.assertTrue(rows)
        # Amplification guard (ticket 20): a 120-point recording must not put more
        # than 120 rows in the store. The old client re-sent the whole track every
        # batch, which stored the same point up to 83 times.
        self.assertLessEqual(len(rows), 120)
        seen = [(r["lat"], r["t"]) for r in rows]
        self.assertEqual(len(seen), len(set(seen)), "a point was stored twice")
        first, last = made[0], made[-1]
        for row in rows:
            self.assertGreaterEqual(
                haversine_m(first["lng"], first["lat"], row["lng"], row["lat"]), 200.0,
                "a point within 200 m of the true start reached the store")
            self.assertGreaterEqual(
                haversine_m(last["lng"], last["lat"], row["lng"], row["lat"]), 200.0,
                "a point within 200 m of the true end reached the store")

    def test_withheld_tail_is_reported_apart_from_dropped(self):
        token = "trip-token-tail-reporting-check"
        made = [{"lng": 51.8, "lat": round(26.1 + i * 0.00009, 5),
                 "t": 1_700_000_200_000 + i * 2000, "a": 5.0} for i in range(60)]
        _, body = self._post("/traces", headers=self._auth(), body=json.dumps(
            {"kind": "track", "trip": token, "points": made}).encode())
        data = json.loads(body)
        # 60 points span ~590 m: 200 m head dropped, ~200 m tail withheld.
        self.assertGreater(data["withheld_pending_tail"], 0)
        self.assertGreater(data["dropped"]["head_truncated"], 0)
        self.assertNotIn("pending_tail_withheld", data["dropped"])

    def test_probe_fixes_are_not_truncated(self):
        """Probes are interval samples, not a track. They never were truncated."""
        made = [{"lng": 51.9, "lat": round(26.2 + i * 0.0009, 5),
                 "t": 1_700_000_300_000 + i * 60_000, "a": 5.0} for i in range(10)]
        _, body = self._post("/traces", headers=self._auth(), body=json.dumps(
            {"kind": "probe", "points": made}).encode())
        self.assertEqual(json.loads(body)["stored"], 10)


if __name__ == "__main__":
    unittest.main()
