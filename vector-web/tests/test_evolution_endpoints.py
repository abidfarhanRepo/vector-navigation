"""Tests for the evolution/privacy endpoints and the proxy cache pass-through.

Three of these guard failures that are invisible rather than loud:

* the proxy dropping ``Cache-Control`` would silently break issue 08's tile
  invalidation at the edge — the tile server would be correct and the map would
  still never update;
* a 401 on a POST that does not drain the request body sends RST instead of the
  status code, so the client sees a connection abort and never learns it was
  unauthorized;
* privacy counters that never accumulate make issue 10's panel warn that the gate
  rejected nothing while it is in fact dropping thousands of points.
"""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_REPO, "src"))
sys.path.insert(0, os.path.join(_REPO, "vendor"))

from vector_web import make_server, privacy_counters  # noqa: E402


def free_port_server(handler_cls):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), handler_cls)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    return srv, thread


class PrivacyCountersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "counters.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_file_reads_as_all_zero(self):
        counters = privacy_counters.read_counters(self.path)
        self.assertEqual(sum(counters["reasons"].values()), 0)
        self.assertEqual(counters["requests"], 0)

    def test_record_accumulates_across_requests(self):
        privacy_counters.record({"accuracy": 3, "truncated_endpoint": 40}, stored=10, path=self.path)
        privacy_counters.record({"accuracy": 2, "truncated_endpoint": 8}, stored=5, path=self.path)
        counters = privacy_counters.read_counters(self.path)
        self.assertEqual(counters["reasons"]["accuracy"], 5)
        self.assertEqual(counters["reasons"]["truncated_endpoint"], 48)
        self.assertEqual(counters["requests"], 2)
        self.assertEqual(counters["points_stored"], 15)

    def test_summary_warns_only_when_nothing_was_dropped(self):
        blank = privacy_counters.summary(self.path)
        self.assertFalse(blank["active"])
        self.assertIn("no-op", blank["warning"])

        privacy_counters.record({"truncated_endpoint": 1}, path=self.path)
        active = privacy_counters.summary(self.path)
        self.assertTrue(active["active"])
        self.assertIsNone(active["warning"])

    def test_unknown_reason_is_kept_not_discarded(self):
        privacy_counters.record({"future_reason": 4}, path=self.path)
        self.assertEqual(privacy_counters.read_counters(self.path)["reasons"]["future_reason"], 4)

    def test_every_known_reason_reads_zero_on_a_fresh_deployment(self):
        """A counter that is absent until it first fires is invisible exactly when
        an operator most wants to have been watching it (ticket 23/24)."""
        counters = privacy_counters.read_counters(self.path)
        for key in ("no_trip_token", "head_truncated", "out_of_order_refused",
                    "age_too_old", "implausible_speed", "sparse_sampling"):
            self.assertIn(key, counters["reasons"])
            self.assertEqual(counters["reasons"][key], 0)
        for key in ("accuracy_unknown", "pending_tail_withheld",
                    "duplicate_trips_refused", "imported_age_0_7d"):
            self.assertIn(key, counters["quality"])
            self.assertEqual(counters["quality"][key], 0)

    def test_a_refused_duplicate_is_not_a_drop(self):
        """It is counted in TRIPS, in the quality bucket, and the unit is in the name:
        a duplicate's points are in the store, so calling them dropped would report
        data loss that did not happen — and would wreck the per-source drop rate."""
        self.assertNotIn("duplicate_trip", privacy_counters.KNOWN_REASONS)
        self.assertIn("duplicate_trips_refused", privacy_counters.KNOWN_QUALITY)

    def test_quality_counters_are_not_counted_as_drops(self):
        """A withheld tail is committed on the next payload; an unmeasured accuracy
        is not a rejection. Counting either as a drop would let the panel claim
        enforcement that never happened."""
        privacy_counters.record({}, stored=5, path=self.path,
                                quality={"pending_tail_withheld": 7,
                                         "accuracy_unknown": 12})
        summary = privacy_counters.summary(self.path)
        self.assertEqual(summary["quality"]["pending_tail_withheld"], 7)
        self.assertEqual(summary["quality"]["accuracy_unknown"], 12)
        self.assertEqual(summary["total_dropped"], 0)
        self.assertFalse(summary["active"])
        self.assertIn("no-op", summary["warning"])

    def test_per_source_split_and_drop_rate(self):
        privacy_counters.record({"accuracy": 10}, stored=90, path=self.path,
                                source="live")
        privacy_counters.record({"accuracy": 1}, stored=99, path=self.path,
                                source="import", trips=3)
        by_source = privacy_counters.summary(self.path)["by_source"]
        self.assertEqual(by_source["live"]["points_stored"], 90)
        self.assertEqual(by_source["live"]["drop_rate"], 0.1)
        self.assertEqual(by_source["import"]["drop_rate"], 0.01)
        self.assertEqual(by_source["import"]["trips"], 3)
        # adr-0070 §5: derived where derivable, declared where not.
        self.assertEqual(by_source["import"]["provenance"], "derived")
        self.assertEqual(by_source["native"]["provenance"], "declared")

    def test_a_tier_that_sent_nothing_has_no_drop_rate(self):
        """0% would read as 'this tier is clean' rather than 'no data yet'."""
        by_source = privacy_counters.summary(self.path)["by_source"]
        self.assertIsNone(by_source["native"]["drop_rate"])

    def test_unknown_source_is_attributed_to_live_not_invented(self):
        privacy_counters.record({}, stored=3, path=self.path, source="pigeon")
        by_source = privacy_counters.summary(self.path)["by_source"]
        self.assertEqual(by_source["live"]["points_stored"], 3)
        self.assertNotIn("pigeon", by_source)

    def test_counter_file_written_before_ticket_22_still_reads(self):
        """Old totals are not retroactively attributed to a tier."""
        import json as _json
        with open(self.path, "w", encoding="utf-8") as fh:
            _json.dump({"reasons": {"accuracy": 5}, "requests": 2,
                        "points_stored": 40}, fh)
        counters = privacy_counters.read_counters(self.path)
        self.assertEqual(counters["reasons"]["accuracy"], 5)
        self.assertEqual(counters["points_stored"], 40)
        self.assertEqual(counters["sources"]["live"]["points_stored"], 0)

    def test_corrupt_file_does_not_raise(self):
        with open(self.path, "w", encoding="utf-8") as fh:
            fh.write("{{{")
        self.assertEqual(privacy_counters.read_counters(self.path)["requests"], 0)
        privacy_counters.record({"accuracy": 1}, path=self.path)
        self.assertEqual(privacy_counters.read_counters(self.path)["reasons"]["accuracy"], 1)

    def test_non_numeric_counts_are_ignored_rather_than_crashing_ingest(self):
        privacy_counters.record({"accuracy": "many"}, path=self.path)
        self.assertEqual(privacy_counters.read_counters(self.path)["reasons"]["accuracy"], 0)

    def test_unwritable_path_does_not_fail_the_caller(self):
        # Losing a metric must never reject the data it measures.
        bad = os.path.join(self.tmp.name, "no-such-dir\x00", "x.json")
        try:
            privacy_counters.record({"accuracy": 1}, path=bad)
        except Exception as exc:  # noqa: BLE001
            self.fail(f"counter write raised into the ingest path: {exc!r}")


class WebEndpointTest(unittest.TestCase):
    """The endpoints, over a real socket, with a real handler."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        os.environ["VECTOR_EVOLUTION_DIR"] = cls.tmp.name
        os.environ["VECTOR_PRIVACY_COUNTERS"] = os.path.join(cls.tmp.name, "counters.json")
        os.environ.pop("VECTOR_WEB_TOKEN", None)   # dev-anonymous
        cls.server = make_server(0, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        os.environ.pop("VECTOR_EVOLUTION_DIR", None)
        os.environ.pop("VECTOR_PRIVACY_COUNTERS", None)
        cls.tmp.cleanup()

    def _get(self, path):
        try:
            with urllib.request.urlopen(
                    f"http://127.0.0.1:{self.port}{path}", timeout=5) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers or {})

    def test_privacy_counters_endpoint(self):
        status, body, _ = self._get("/privacy-counters")
        self.assertEqual(status, 200)
        doc = json.loads(body)
        self.assertIn("reasons", doc)
        self.assertIn("active", doc)

    def test_evolution_missing_snapshot_is_503_with_an_explanation(self):
        """"Nobody ran the cycle" must be distinguishable from "not improving"."""
        status, body, _ = self._get("/evolution")
        self.assertEqual(status, 503)
        self.assertIn(b"No evolution snapshot", body)
        self.assertIn(b"run-evolution-cycle", body)

    def test_evolution_json_missing_snapshot_is_structured(self):
        status, body, _ = self._get("/evolution.json")
        self.assertEqual(status, 503)
        self.assertEqual(json.loads(body)["error"], "no evolution snapshot")

    def test_evolution_serves_the_generated_page_uncached(self):
        with open(os.path.join(self.tmp.name, "evolution.html"), "w", encoding="utf-8") as fh:
            fh.write("<h1>is the map improving?</h1>")
        status, body, headers = self._get("/evolution")
        self.assertEqual(status, 200)
        self.assertIn(b"is the map improving", body)
        # A cached dashboard shows yesterday's verdict as though it were current.
        self.assertEqual(headers.get("Cache-Control"), "no-store")

    def test_evolution_json_round_trips(self):
        payload = {"verdict": {"improving": False}, "coverage": {"latest": None}}
        with open(os.path.join(self.tmp.name, "evolution.json"), "w", encoding="utf-8") as fh:
            json.dump(payload, fh)
        status, body, _ = self._get("/evolution.json")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), payload)


class ProxyCacheControlTest(unittest.TestCase):
    """The proxy must forward the backend's caching directive.

    ``/tiles/version`` is sent ``no-store`` because it is the response that tells
    a client its cached tiles are stale. An edge that strips it would let the
    browser cache the epoch, keep asking for the old ``?v=``, and leave a
    promoted road invisible — with every server-side check passing.
    """

    @classmethod
    def setUpClass(cls):
        class Backend(BaseHTTPRequestHandler):
            def log_message(self, *args):
                return

            def do_GET(self):
                body = b'{"epoch":7}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header(
                    "Cache-Control",
                    "no-store" if self.path.endswith("/version") else "public, max-age=86400")
                self.end_headers()
                self.wfile.write(body)

        cls.backend, cls.backend_thread = free_port_server(Backend)
        backend_port = cls.backend.server_address[1]
        os.environ["VECTOR_TILE_SERVER_URL"] = f"http://127.0.0.1:{backend_port}"
        os.environ.pop("VECTOR_WEB_TOKEN", None)
        cls.server = make_server(0, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        for srv, thread in ((cls.server, cls.thread), (cls.backend, cls.backend_thread)):
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)
        os.environ.pop("VECTOR_TILE_SERVER_URL", None)

    def _headers(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=5) as resp:
            return dict(resp.headers)

    def test_no_store_survives_the_proxy(self):
        self.assertEqual(self._headers("/tiles/version").get("Cache-Control"), "no-store")

    def test_long_cache_survives_the_proxy(self):
        self.assertIn("max-age", self._headers("/tiles/12/1/1.mvt").get("Cache-Control", ""))


class UnauthorizedPostTest(unittest.TestCase):
    """A rejected POST must return its status, not abort the connection.

    Answering 401 without reading the request body leaves unread bytes in the
    socket; the client is still writing when we close, the OS sends RST, and the
    client raises a connection error instead of seeing the 401. The status code
    was set correctly and never arrived.
    """

    @classmethod
    def setUpClass(cls):
        os.environ["VECTOR_WEB_TOKEN"] = "test-token-not-a-secret"
        cls.server = make_server(0, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        os.environ.pop("VECTOR_WEB_TOKEN", None)

    def _post(self, path, body):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}", data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status
        except urllib.error.HTTPError as e:
            e.read()
            return e.code

    def test_anonymous_post_with_a_body_gets_401(self):
        body = json.dumps({"kind": "track", "points": [{"lng": 51.5, "lat": 25.2}]}).encode()
        self.assertEqual(self._post("/traces", body), 401)

    def test_anonymous_post_with_a_large_body_still_gets_401(self):
        points = [{"lng": 51.5, "lat": 25.2, "t": 1, "a": 5} for _ in range(2000)]
        body = json.dumps({"kind": "track", "points": points}).encode()
        self.assertEqual(self._post("/traces", body), 401)

    def test_authorized_post_still_works(self):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/traces",
            data=json.dumps({"kind": "track", "points": []}).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer test-token-not-a-secret"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                self.assertIn(resp.status, (200, 400))
        except urllib.error.HTTPError as e:
            self.assertIn(e.code, (200, 400))


if __name__ == "__main__":
    unittest.main()


class TripTokenRequiredTest(unittest.TestCase):
    """A track without a trip token must be REJECTED, not silently accepted.

    The fallback (one pseudonym per upload batch) is the defect adr-0068 fixed: it
    lets one journey satisfy K=5 alone, and the resulting facts look ordinary. The
    service worker caches the app shell, so a stale client can post under the old
    contract for as long as it takes to update — accepting that data corrupts the
    guarantee invisibly and permanently, where refusing it is loud and fixable.
    """

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        os.environ["VECTOR_TRACE_DB"] = os.path.join(cls.tmp.name, "traces.db")
        os.environ["VECTOR_PRIVACY_COUNTERS"] = os.path.join(cls.tmp.name, "counters.json")
        os.environ.pop("VECTOR_WEB_TOKEN", None)      # dev-anonymous
        cls.server = make_server(0, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        for key in ("VECTOR_TRACE_DB", "VECTOR_PRIVACY_COUNTERS"):
            os.environ.pop(key, None)
        # Windows keeps the sqlite handle open past our close(); a failed temp
        # cleanup must not turn a passing suite into an error.
        try:
            cls.tmp.cleanup()
        except OSError:
            pass

    def _post(self, body):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/traces",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def _points(self, n=40):
        # Long enough to survive 200 m endpoint truncation.
        return [{"lng": 51.500 + i * 0.0004, "lat": 25.200,
                 "t": 1_785_800_000_000 + i * 5_000, "s": 11.0, "a": 6.0}
                for i in range(n)]

    def test_track_without_a_token_is_rejected(self):
        status, body = self._post({"kind": "track", "points": self._points()})
        self.assertEqual(status, 400)
        self.assertTrue(body.get("reload_required"))
        self.assertEqual(body.get("reason"), "no_trip_token")

    def test_track_with_a_malformed_token_is_rejected(self):
        for bad in ("short", "user@example.com", 12345, ""):
            status, _ = self._post({"kind": "track", "points": self._points(), "trip": bad})
            self.assertEqual(status, 400, f"accepted {bad!r}")

    def test_the_rejection_is_counted_so_it_is_visible(self):
        from vector_web import privacy_counters

        path = os.environ["VECTOR_PRIVACY_COUNTERS"]
        before = privacy_counters.read_counters(path)["reasons"].get("no_trip_token", 0)
        self._post({"kind": "track", "points": self._points()})
        after = privacy_counters.read_counters(path)["reasons"].get("no_trip_token", 0)
        self.assertGreater(after, before,
                           "a turned-away client must be visible, or coverage just "
                           "stops growing for no apparent reason")

    def test_a_valid_token_is_accepted(self):
        from vector_privacy.trip import mint_client_token

        status, body = self._post({"kind": "track", "points": self._points(),
                                   "trip": mint_client_token()})
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "ok")
        self.assertGreater(body["stored"], 0)

    def test_probe_without_a_token_still_works(self):
        """A probe is an interval sample, not a trip — it is not trip-scoped."""
        status, body = self._post({"kind": "probe", "points": self._points(3)})
        self.assertEqual(status, 200, body)

    def test_the_same_token_across_batches_yields_one_trip(self):
        """The property K needs, over the real HTTP path.

        Asserted against the pseudonym this token derives to, not against the
        distinct count in the store: the store is shared across this class, so a
        bare count would also see other tests' trips and fail for the wrong reason.
        """
        from vector_privacy.trip import load_or_create_salt, mint_client_token, trip_pseudonym
        from vector_web.trace_store import _make_store

        token = mint_client_token()
        batches = 4
        for _ in range(batches):
            status, _ = self._post({"kind": "track", "points": self._points(), "trip": token})
            self.assertEqual(status, 200)

        db = os.environ["VECTOR_TRACE_DB"]
        expected, _ = trip_pseudonym(token, load_or_create_salt(os.path.dirname(db)))
        store = _make_store(db)
        try:
            rows = [r for r in store.recent(4000) if r["trip_pseudonym"] == expected]
        finally:
            store.close()
        # Every batch landed under ONE pseudonym...
        self.assertEqual(len({r["trip_pseudonym"] for r in rows}), 1)
        # ...and all of them are there, so nothing was split off under another.
        self.assertGreaterEqual(len(rows), batches * 2,
                                "batches of one trip were split across pseudonyms")
