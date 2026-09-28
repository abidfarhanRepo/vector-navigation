"""PWA (installable web app) tests — ADR-0063.

Exercises the web composition root's PWA surface WITHOUT any backend:
  * GET /manifest.webmanifest -> 200, content-type application/manifest+json,
    valid JSON, lists the self-hosted icons (no third-party URLs).
  * GET /sw.js -> 200, application/javascript (service worker script).
  * GET /icons/icon-192.png, /icons/icon-512.png, /icons/icon-maskable-512.png,
    /icons/icon.svg -> 200 with correct image content-types.
  * PWA assets are PUBLIC: served 200 even when VECTOR_WEB_TOKEN is set and no
    Authorization header is presented (so install + offline restore work with
    no token context).
  * index.html references the manifest + registers the service worker.

No external services touched; the suite spins up the real make_server against
no backend (PWA asset paths never hit a proxy).
"""

import re
import json
import os
import threading
import unittest
import urllib.request

from vector_web import make_server

STATIC = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "static")
)


def _free_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class PWATest(unittest.TestCase):
    web = None
    web_port = None

    @classmethod
    def setUpClass(cls):
        cls.web_port = _free_port()
        # With a token set, PWA assets must still be public (no gate).
        os.environ["VECTOR_WEB_TOKEN"] = "pwa-test-token"
        cls.web = make_server(cls.web_port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        if cls.web:
            cls.web.shutdown()

    def _get(self, path):
        url = "http://127.0.0.1:%d%s" % (self.web_port, path)
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def test_manifest_served_public_and_typed(self):
        status, headers, body = self._get("/manifest.webmanifest")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Type"), "application/manifest+json")
        manifest = json.loads(body.decode("utf-8"))
        self.assertEqual(manifest["name"], "Vector — Navigation")
        self.assertIn("icons", manifest)
        # No third-party asset references (self-hosted principle, adr-0059/0063).
        for icon in manifest["icons"]:
            self.assertTrue(icon["src"].startswith("/"), icon["src"])
            self.assertNotIn("http", icon["src"])
        # Maskable icon present (install-on-Android safe zone).
        purposes = {i.get("purpose") for i in manifest["icons"]}
        self.assertIn("maskable", purposes)

    def test_sw_served_public_and_js(self):
        status, headers, body = self._get("/sw.js")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Type"), "application/javascript")
        self.assertIn(b"addEventListener", body)  # real SW, not an empty stub
        self.assertIn(b"CACHE", body)

    def test_icons_served_public_and_typed(self):
        cases = [
            ("/icons/icon-192.png", "image/png"),
            ("/icons/icon-512.png", "image/png"),
            ("/icons/icon-maskable-512.png", "image/png"),
            ("/icons/icon.svg", "image/svg+xml"),
        ]
        for path, expected_ct in cases:
            status, headers, body = self._get(path)
            self.assertEqual(status, 200, path)
            self.assertEqual(headers.get("Content-Type"), expected_ct, path)
            self.assertTrue(len(body) > 64, "%s too small" % path)

    def test_index_references_pwa(self):
        # The viewer page must declare the manifest + SW registration + theme.
        # (We assert on the served markup content; the gated page is reachable
        # in the deployed stack via ?token=, so we read the static source here.)
        path = os.path.join(STATIC, "index.html")
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("manifest.webmanifest", text)
        self.assertIn("navigator.serviceWorker", text)
        self.assertIn("theme-color", text)

    def test_index_security_and_accessibility_improvements(self):
        # Verify the production-grade hardening shipped in this iteration:
        #  - TOKEN defaults to empty (not 'secret'): avoids sending a fake
        #    bearer to backends in dev-anonymous mode.
        #  - aria-label on the map container (accessibility).
        #  - role="alert" on the toast (screen-reader live region).
        #  - visually-hidden helper class for screen-reader-only labels.
        #  - prefers-reduced-motion media query (accessibility).
        #  - beforeinstallprompt handler (PWA install affordance).
        path = os.path.join(STATIC, "index.html")
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        # TOKEN must NOT default to 'secret' as the old insecure fallback
        # (the comment explains the rationale; only the code matters).
        self.assertNotIn("'secret'", text.split("//")[0])  # code only, not comments
        # The actual assignment must default to empty string.
        self.assertIn("const TOKEN = new URLSearchParams(location.search).get('token') || '';", text)
        # Accessibility: map role/label + toast live region.
        self.assertIn('role="application"', text)
        self.assertIn('aria-label="Vector navigation map"', text)
        self.assertIn('id="toast" role="alert"', text)
        self.assertIn("visually-hidden", text)
        # Reduced motion.
        self.assertIn("prefers-reduced-motion", text)
        # PWA install prompt captured.
        self.assertIn("beforeinstallprompt", text)
        self.assertIn("window.vectorInstall", text)

    def test_sw_precaches_shell_and_is_network_only_for_apis(self):
        # The service worker must precache the app shell (offline launch) and
        # serve live APIs network-only (no stale routes/traffic).
        path = os.path.join(STATIC, "sw.js")
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        # Shell precache entries.
        self.assertIn('"/"', text)
        self.assertIn('"/manifest.webmanifest"', text)
        self.assertIn('"/vendor/maplibre-gl.js"', text)
        # Network-only enforcement for live APIs.
        self.assertIn('"/route"', text)
        self.assertIn('"/navigate"', text)
        self.assertIn('"/traffic"', text)
        # W53: tiles moved OUT of network-only into the dedicated tile cache
        # (stale-while-revalidate, immutable per tile-epoch). /tiles/version is
        # still excluded from the tile handler — it must never be cached.
        self.assertIn('p.startsWith("/tiles/") && !p.startsWith("/tiles/version")', text)
        self.assertIn("isTilePath", text)
        # Never serve a stale API from cache: isApiPath forces network fetch.
        self.assertIn("event.respondWith(fetch(req))", text)


class CollectPageTest(unittest.TestCase):
    """The collection surface (tickets 19-21).

    A page rather than another map button: it has to hold a file preview, honest
    platform limits stated before recording, and the per-tier readout. It is also
    the screen adr-0070's native shell loads from the live origin, so it is tested
    as a first-class surface rather than as static decoration.
    """

    web = None
    web_port = None

    @classmethod
    def setUpClass(cls):
        cls.web_port = _free_port()
        os.environ["VECTOR_WEB_TOKEN"] = "collect-test-token"
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

    def test_collect_requires_a_token(self):
        """It posts location data, so it is not more open than the map."""
        status, _, _ = self._get("/collect")
        self.assertEqual(status, 401)

    def test_collect_served_with_a_token(self):
        status, headers, body = self._get("/collect?token=collect-test-token")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Content-Type"), "text/html")
        self.assertIn(b"Contribute driving data", body)

    def test_collect_html_alias(self):
        status, _, body = self._get("/collect.html?token=collect-test-token")
        self.assertEqual(status, 200)
        self.assertIn(b"Tier 1", body)

    def test_page_leads_with_import_and_states_the_browser_limit(self):
        _, _, body = self._get("/collect?token=collect-test-token")
        # Copy is wrapped for readability in the source, so assert against the
        # flowed text a reader sees rather than against the line breaks.
        text = " ".join(body.decode("utf-8").split())
        self.assertLess(text.index("Upload a track you already recorded"),
                        text.index("Record now, in this browser"),
                        "the tier that works must come first")
        self.assertIn("cannot record in the background", text)
        self.assertIn("/import/preview", text)
        self.assertIn("/import/commit", text)

    def test_page_does_not_offer_a_download_that_does_not_exist(self):
        """adr-0070 is Proposed and gated on a battery number."""
        text = " ".join(
            self._get("/collect?token=collect-test-token")[2].decode("utf-8").split())
        self.assertIn("not built yet", text)
        self.assertNotIn(".apk", text.lower())

    def test_k_floor_is_described_as_trips_not_people(self):
        """The adr-0069 correction, in the copy a person actually reads."""
        text = " ".join(
            self._get("/collect?token=collect-test-token")[2].decode("utf-8").split())
        self.assertIn("counts <em>trips</em>, not people", text)
        self.assertIn("five separate trips", text)

    def test_map_links_to_the_collection_page(self):
        text = self._get("/?token=collect-test-token")[2].decode("utf-8")
        self.assertIn("consent-collect-link", text)
        self.assertIn("/collect", text)

    def test_shell_precaches_the_collect_page(self):
        with open(os.path.join(STATIC, "sw.js"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn('"/collect"', text)

    def test_service_worker_caches_each_navigation_under_its_own_path(self):
        """Caching every page under "/" served the collect page as the map offline."""
        with open(os.path.join(STATIC, "sw.js"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("c.put(key, copy)", text)
        self.assertNotIn('c.put("/", copy)', text)

    def test_ingest_contract_change_bumped_the_shell_version(self):
        with open(os.path.join(STATIC, "sw.js"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertNotIn('const VERSION = "v7"', text)

    def test_live_client_sends_only_unsent_points(self):
        """The ticket-20 defect, guarded in the shipped shell rather than in prose."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        # The render buffer and the upload queue are separate objects...
        self.assertIn("traceQueue", text)
        # ...the payload carries the queue, not the whole track...
        self.assertNotIn("points: tracePts.slice()", text)
        # ...and the queue only shrinks on a 200, by exactly what was sent.
        self.assertIn("traceQueue.splice(0, n);", text)
        # Each queued point remembers its trip, so a gap split mid-upload cannot
        # attribute one journey's points to the next.
        self.assertIn("traceQueue.push({ trip: tripToken, pt: p })", text)

    def test_live_client_splits_on_a_five_minute_gap(self):
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("TRACE_GAP_MS = 5 * 60 * 1000", text)
        self.assertIn("traceSplitTrip", text)
        # A gap must be drawn as a gap, not bridged by a straight line.
        self.assertIn("MultiLineString", text)

    def test_viewer_declares_haversine_exactly_once(self):
        """Two `function haversineM` declarations in one script are legal in sloppy
        mode and the last one wins for the whole file. This file had two, with the
        arguments in OPPOSITE orders, so `checkArrival` measured its 120 m arrival
        radius on mirrored coordinates — a plausible number, wrong by tens of
        percent — and editing the shadowed copy would have changed nothing."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            lines = [ln for ln in fh if ln.lstrip().startswith("function haversineM(")]
        self.assertEqual(len(lines), 1, "haversineM is declared more than once")
        self.assertIn("lng1, lat1, lng2, lat2", lines[0],
                      "the surviving definition must use the project's (lng, lat) order")

    def test_geo_module_loads_as_a_blocking_classic_script(self):
        """VectorGeo is called from the inline app script, which runs during
        parse. A `defer` or `type="module"` tag runs AFTER the document is
        parsed, so the call would hit an undefined global — the same failure
        shape as the Shipaton config.js tag, where a deferred script left
        window.__VECTOR_API_BASE__ undefined and API_BASE silently fell back to
        location.origin."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        tags = re.findall(r"<script[^>]*js/geo\.js[^>]*>", text)
        self.assertEqual(len(tags), 1, "geo.js must be referenced exactly once")
        self.assertNotIn("defer", tags[0])
        self.assertNotIn("async", tags[0])
        self.assertNotIn("module", tags[0])

    def test_off_route_threshold_is_a_distance_in_metres(self):
        """Ticket 36. The deviation test compared a SQUARED degree distance to
        0.0008 and the comment beside it said "~100m". sqrt(0.0008) deg is
        ~3,150 m at Doha's latitude — 31x too loose, so a missed turn never
        triggered a recalculation until the driver was 3 km off route. The
        threshold must now be a named distance in metres."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        # Scan CODE, not comments: the fix documents the old expression verbatim
        # so the next reader knows what was wrong, and a naive substring search
        # over the whole file therefore matches the explanation of the bug as
        # well as the bug. (This test caught exactly that on its first run.)
        code = "\n".join(
            ln for ln in text.splitlines() if not ln.lstrip().startswith("//")
        )
        self.assertNotIn("minD > 0.0008", code,
                         "the squared-degrees off-route threshold is back")
        m = re.search(r"const OFF_ROUTE_M = (\d+)", text)
        self.assertIsNotNone(m, "off-route distance must be a named metre constant")
        metres = int(m.group(1))
        self.assertTrue(20 <= metres <= 200,
                        f"OFF_ROUTE_M = {metres} is not a plausible lane offset")

    def test_deviation_is_measured_from_the_fix_not_the_camera(self):
        """It read map.getCenter() — the camera, not the car. The camera stops
        following when the driver pans the map, so panning could invent a
        deviation the vehicle never made."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        watch = text.split("function startDeviationWatch")[1].split("function stopDeviationWatch")[0]
        self.assertNotIn("map.getCenter()", watch,
                         "deviation must not be measured from the camera")
        self.assertIn("_projectFix(", watch)

    def test_route_progress_projects_onto_segments_not_vertices(self):
        """Nearest-VERTEX progress quantised the turn countdown to the shape-point
        spacing: on a motorway with ~200 m between points, "in 400 m turn right"
        held at 400 and then jumped to 200. Progress must come from a
        perpendicular projection."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        traveled = text.split("function _traveledM")[1].split("function ")[0]
        self.assertNotIn("bestD", traveled, "nearest-vertex progress search is back")
        self.assertIn("alongM", traveled)

    def test_live_client_requests_the_wake_lock_and_reacquires_it(self):
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("navigator.wakeLock.request('screen')", text)
        self.assertIn("visibilitychange", text)

    def test_live_client_persists_the_queue_to_indexeddb(self):
        """Ticket 20's durable queue: a closed tab, a crash or a flat battery
        must not lose a recorded drive. Points are written to IndexedDB as they
        arrive — not at upload time — so the upload queue survives the tab."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        # The store exists, is versioned, and is opened on demand.
        self.assertIn("indexedDB.open", text)
        self.assertIn("vector-trace-queue", text)
        self.assertIn("createObjectStore", text)
        # Persisted as the point arrives, from inside traceAddPoint.
        self.assertIn("traceIdbPersist(traceQueue[traceQueue.length - 1])", text)
        # Only a 200 removes it — the same splice that frees the in-memory
        # queue also drops the durable record, so an accepted point is never
        # re-sent by a later restore.
        self.assertIn("traceIdbDelete(sentIds)", text)

    def test_live_client_restores_the_queue_on_load(self):
        """A trip that could not upload must be drained on the NEXT load, still
        under its original trip tokens — that is the adr-0068 property (one
        pseudonym, one trip) made durable. Consent still gates the drain."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("async function traceQueueRestore()", text)
        self.assertIn("traceQueueRestore()", text)
        # The restored entry carries the trip it was recorded under.
        self.assertIn("records.map((r) => ({ id: r.id, kind: r.kind, trip: r.trip, pt: r.pt }))", text)
        # Consent is enforced at restore too: no explicit yes, no upload.
        self.assertIn("if (!contributingEnabled()) {", text)
        # Revocation clears the durable copy, so a restart cannot resurrect
        # points the driver withdrew.
        self.assertIn("traceIdbClear()", text)

    # ---- Speedometer (W51) ------------------------------------------------

    def test_viewer_has_a_speedometer_in_the_nav_hud(self):
        """The complaint was "no speedometer". There must be a real one: a
        large-numeral readout in the HUD, sized to be read at a glance from
        driving position, with its own unit label."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        # The readout element and its unit label exist.
        self.assertIn('id="gps-spd"', text)
        self.assertIn('<div class="unit">km/h</div>', text)
        # Car-glanceable numerals in the nav HUD, sized per the Figma v1.0
        # design system (speed is TERTIARY info: 20px value + 32px limit).
        self.assertIn("#gpsbox .spd{", text)
        self.assertIn("font-size:20px", text)
        # The speed-limit badge uses the Figma red ring at the smaller size.
        self.assertIn("width:32px; height:32px", text)
        # It is wired to the HUD's lifecycle, not only to the GPS toggle: a
        # planned route puts it on screen and exiting navigation takes it away.
        self.assertIn("function syncSpeedoVisibility()", text)
        self.assertIn("syncSpeedoVisibility();   // the speedometer is part of the nav HUD", text)

    def test_speedometer_renders_a_placeholder_not_zero_without_a_gps_speed(self):
        """`coords.speed` is null indoors, on the first fix, and on whole
        classes of device. The old readout printed `0` for all of them — a
        parked-car reading that is indistinguishable from a real one. It must
        render "--" instead, styled differently so it cannot be misread."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        # The resting markup is the placeholder, not a zero.
        self.assertIn('<div class="spd" id="gps-spd">--</div>', text)
        self.assertNotIn('id="gps-spd">0<small>', text)
        # The null branch clears the reading and says why.
        self.assertIn("gpsSpd.textContent = known ? String(Math.round(speedoKmh)) : '--';", text)
        self.assertIn("'No speed from GPS'", text)
        # Unknown is visually distinct from a live reading (never the green).
        self.assertIn("#gpsbox.unknown .spd", text)
        # The position-differencing fallback is GONE: it invented 10-20 km/h
        # for a stationary car out of ordinary urban fix noise.
        self.assertNotIn("_gpsLast", text)

    def test_speedometer_refuses_untrustworthy_and_stale_fixes(self):
        """Poor accuracy and a lost signal both fall back to "--" rather than a
        plausible number or a frozen one."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("const SPEEDO_ACC_MAX_M = 50;", text)
        self.assertIn("'Low GPS accuracy ('", text)
        self.assertIn("const SPEEDO_STALE_MS  = 5000;", text)
        self.assertIn("renderSpeedo('No GPS fix')", text)
        # Stationary reads a hard zero rather than drifting on the noise floor.
        self.assertIn("const SPEEDO_ZERO_MS   = 0.6;", text)
        self.assertIn("if (raw < SPEEDO_ZERO_MS){", text)
        # Smoothed, but a real manoeuvre bypasses the filter so it cannot lag.
        self.assertIn("const SPEEDO_TAU_MS    = 1200;", text)
        self.assertIn("Math.abs(kmh - speedoKmh) >= SPEEDO_SNAP_KMH", text)

    def test_speed_limit_badge_comes_from_the_speed_service_only(self):
        """The limit is read from the geocoder's /speed lookup — never guessed
        client-side. A limit inferred from the highway class (source
        "default") is drawn differently from a surveyed `maxspeed` tag."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("location.origin + '/speed'", text)
        self.assertIn("fetch(SPEED_URL + '?lat=' + lat + '&lon=' + lng", text)
        self.assertIn("typeof d.maxspeed_kmh === 'number'", text)
        # No service / no nearby road -> no badge, rather than a stale one.
        self.assertIn("speedoLimitKmh = null; speedoLimitSrc = null;", text)
        self.assertIn("gpsLimit.classList.remove('on')", text)
        # Inferred limits are marked as such, and only real numbers can raise
        # the over-limit state.
        self.assertIn("gpsLimit.classList.toggle('inferred', speedoLimitSrc !== 'tag')", text)
        self.assertIn("known && speedoLimitKmh != null && speedoKmh > speedoLimitKmh + SPEEDO_OVER_KMH", text)

    def test_speedometer_helpers_are_each_declared_exactly_once(self):
        """Same trap as `haversineM`: a second declaration of any of these in
        the one big sloppy-mode script would silently win for the whole file."""
        import re as _re
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        names = _re.findall(r"^\s*(?:async\s+)?function\s+([A-Za-z_$][\w$]*)",
                            text, _re.MULTILINE)
        dupes = sorted({n for n in names if names.count(n) > 1})
        self.assertEqual(dupes, [], "duplicate function declarations: %r" % (dupes,))
        for helper in ("renderSpeedo", "speedoClear", "updateSpeed",
                       "refreshSpeedLimit", "syncSpeedoVisibility"):
            self.assertIn(helper, names, "%s is missing" % helper)

    def test_speed_lookup_is_never_served_from_the_cache(self):
        """A speed limit for the road you were on ten minutes ago is worse than
        no badge at all."""
        with open(os.path.join(STATIC, "sw.js"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn('p.startsWith("/speed")', text)

    def test_live_client_recovers_after_idle_connection_loss(self):
        """The durable queue only helps if a drained queue uploads on reconnect.
        A stopped recording holding a queue must still retry when the network
        returns — the ticket-20 acceptance path."""
        with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("addEventListener('online'", text)


if __name__ == "__main__":
    unittest.main()
