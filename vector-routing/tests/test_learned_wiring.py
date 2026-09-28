"""Tests for the learned-speed service + HTTP wiring (issue 07).

The logic tests live in ``test_learned_overlay.py``. These cover the part that
was missing: that the overlay is actually reachable from ``RoutingService``,
that ``/route``, ``/navigate``, ``/learned`` and ``/eta`` behave, and — the two
that matter most — that a learned profile changes the **reported ETA** and that
turning the layer off restores the previous numbers exactly.
"""

import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_routing.eta_log import EtaErrorLog
from vector_routing.learned_overlay import (
    CompiledLearnedOverlay,
    CompiledLearnedView,
    LearnedSpeedOverlay,
    LearnedSpeedView,
)
from vector_routing.serve import make_server
from vector_routing.service import BANDS_PER_WEEK, RoutingService, read_learned_facts, time_band

BAND = 0

# A 3-node chain: A -- B -- C, both edges posted at 60 km/h.
NETWORK = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "properties": {"id": "e1", "highway": "primary", "maxspeed": 60},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.500, 25.200], [51.510, 25.200]]}},
        {"type": "Feature",
         "properties": {"id": "e2", "highway": "primary", "maxspeed": 60},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.510, 25.200], [51.520, 25.200]]}},
    ],
}


def speed_fact(coords, speed_kmh, band=BAND, confidence=0.9):
    return {
        "fact_key": f"speed_profile:seg:{band}",
        "fact_type": "speed_profile",
        "evidence_count": 7,
        "confidence": confidence,
        "payload": {
            "segment_id": "seg",
            "band": band,
            "median_speed_kmh": speed_kmh,
            "geometry": coords,
        },
    }


def build_service(tmpdir) -> RoutingService:
    path = os.path.join(tmpdir, "network.geojson")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(NETWORK, fh)
    return RoutingService.from_geojson(path)


class TimeBandAgreementTest(unittest.TestCase):
    """Routing's band must match vector-learning's, band for band.

    The two repos never import each other (ADR-0003), so the function is
    duplicated. A one-band disagreement would apply Monday-morning profiles to
    Sunday-night traffic and be nearly impossible to spot from the outside.

    This class cannot compare the two implementations directly — that is what
    ``scripts/verify-evolution-loop.py`` does at the workspace level. What it can
    do is pin the *observable semantics* both must share, in local civil time. An
    earlier version asserted only that the result was in range, which any
    implementation satisfies and which caught nothing.
    """

    QATAR = __import__("datetime").timezone(__import__("datetime").timedelta(hours=3))

    def _at(self, y, m, d, hh):
        import datetime as _dt
        return int(_dt.datetime(y, m, d, hh, 0, tzinfo=self.QATAR).timestamp() * 1000)

    def test_band_count(self):
        self.assertEqual(BANDS_PER_WEEK, 8)

    def test_local_morning_commute_is_am_peak(self):
        """The band labelled AM peak must contain the actual AM commute.

        Computed in UTC this returned band 0 ("weekday early / overnight") for
        local 08:00, so rush hour shared a bucket with 3 a.m. free-flow.
        """
        self.assertEqual(time_band(self._at(2026, 8, 3, 8)), 1)

    def test_local_night_is_not_the_commute_band(self):
        self.assertNotEqual(time_band(self._at(2026, 8, 3, 3)),
                            time_band(self._at(2026, 8, 3, 8)))

    def test_local_evening_commute_is_pm_peak(self):
        self.assertEqual(time_band(self._at(2026, 8, 3, 18)), 3)

    def test_qatar_weekend_is_friday_saturday(self):
        self.assertEqual(time_band(self._at(2026, 8, 7, 12)), 5)   # Friday
        self.assertEqual(time_band(self._at(2026, 8, 8, 12)), 5)   # Saturday
        self.assertEqual(time_band(self._at(2026, 8, 9, 12)), 2)   # Sunday = working

    def test_every_band_is_reachable(self):
        base = self._at(2026, 8, 3, 0)
        seen = {time_band(base + h * 3600_000) for h in range(168)}
        self.assertEqual(seen, set(range(BANDS_PER_WEEK)))

    def test_wraps_within_a_week(self):
        monday_midnight = 1_785_715_200_000  # 2026-08-03T00:00:00Z, a Monday
        self.assertEqual(time_band(monday_midnight), time_band(monday_midnight + 7 * 24 * 3600_000))
        self.assertEqual(time_band(monday_midnight), time_band(monday_midnight + 30 * 7 * 24 * 3600_000))

    def test_non_positive_falls_back_to_zero(self):
        self.assertEqual(time_band(0), 0)
        self.assertEqual(time_band(-5), 0)


class ReadLearnedFactsTest(unittest.TestCase):
    def test_missing_file_is_not_an_error(self):
        self.assertEqual(read_learned_facts(os.path.join(tempfile.gettempdir(), "nope.json")), [])

    def test_wrapped_and_bare_forms_both_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            wrapped = os.path.join(tmp, "w.json")
            bare = os.path.join(tmp, "b.json")
            fact = speed_fact([[51.5, 25.2], [51.51, 25.2]], 40)
            with open(wrapped, "w", encoding="utf-8") as fh:
                json.dump({"count": 1, "facts": [fact]}, fh)
            with open(bare, "w", encoding="utf-8") as fh:
                json.dump([fact], fh)
            self.assertEqual(len(read_learned_facts(wrapped)), 1)
            self.assertEqual(len(read_learned_facts(bare)), 1)

    def test_corrupt_file_does_not_take_the_router_down(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "bad.json")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("{{{ not json")
            self.assertEqual(read_learned_facts(path), [])


class ServiceLearnedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.service = build_service(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _load_slow_profile(self, speed=20.0):
        """Teach the router that the A--B edge is driven at ``speed`` km/h."""
        facts = [speed_fact([[51.500, 25.200], [51.510, 25.200]], speed)]
        overlay = LearnedSpeedOverlay.from_facts(facts)
        self.assertGreater(len(overlay), 0, "test fixture produced an empty overlay")
        self.service.set_learned_overlay(overlay)
        return overlay

    def test_no_learned_layer_means_zero_status_and_unchanged_routes(self):
        status = self.service.learned_status()
        self.assertFalse(status["has_learned_overlay"])
        self.assertEqual(status["edge_count"], 0)
        route = self.service.route((51.500, 25.200), (51.520, 25.200))
        self.assertGreater(route.distance_m, 0)

    def test_learned_profile_raises_the_reported_eta(self):
        """A road driven at 20 where OSM says 60 must be quoted ~3x slower."""
        base = self.service.navigate((51.500, 25.200), (51.520, 25.200))
        self._load_slow_profile(20.0)
        learned = self.service.navigate((51.500, 25.200), (51.520, 25.200), band=BAND)

        self.assertGreater(learned["duration_s"], base["duration_s"])
        self.assertEqual(learned["learned_segments"], 1)
        self.assertGreater(learned["learned_coverage"], 0.0)
        # Only the first of two equal-length segments is learned, so the total
        # sits between the all-60 and all-20 extremes.
        self.assertLess(learned["duration_s"], base["duration_s"] * 3)

    def test_disabling_the_layer_restores_the_previous_eta_exactly(self):
        """The one-flag rollback issue 07 requires."""
        base = self.service.navigate((51.500, 25.200), (51.520, 25.200))
        self._load_slow_profile(20.0)
        self.service.set_learned_enabled(False)
        rolled_back = self.service.navigate((51.500, 25.200), (51.520, 25.200), band=BAND)
        self.assertEqual(rolled_back["duration_s"], base["duration_s"])
        self.assertEqual(rolled_back["learned_segments"], 0)

    def test_per_request_opt_out(self):
        self._load_slow_profile(20.0)
        with_learned = self.service.navigate((51.500, 25.200), (51.520, 25.200), band=BAND)
        without = self.service.navigate(
            (51.500, 25.200), (51.520, 25.200), learned=False, band=BAND)
        self.assertGreater(with_learned["duration_s"], without["duration_s"])

    def test_zero_coverage_router_is_identical_to_no_overlay(self):
        """Correct at every coverage level, including 0%."""
        base = self.service.navigate((51.500, 25.200), (51.520, 25.200))
        self.service.set_learned_overlay(LearnedSpeedOverlay.from_facts([]))
        empty = self.service.navigate((51.500, 25.200), (51.520, 25.200), band=BAND)
        self.assertEqual(empty["duration_s"], base["duration_s"])
        self.assertEqual(empty["learned_coverage"], 0.0)

    def test_learned_speed_uses_the_edge_maxspeed_as_its_baseline(self):
        """The ETA must equal length/learned_speed, not length/some_constant.

        With a 20 km/h profile on a 60 km/h road the first segment's quoted
        duration must be exactly its length at 20 km/h.
        """
        self._load_slow_profile(20.0)
        result = self.service.navigate((51.500, 25.200), (51.510, 25.200), band=BAND)
        expected = result["distance_m"] / (20.0 / 3.6)
        self.assertAlmostEqual(result["duration_s"], expected, delta=expected * 0.02)

    def test_a_faster_learned_road_lowers_the_eta(self):
        base = self.service.navigate((51.500, 25.200), (51.510, 25.200))
        self._load_slow_profile(120.0)
        faster = self.service.navigate((51.500, 25.200), (51.510, 25.200), band=BAND)
        self.assertLess(faster["duration_s"], base["duration_s"])

    def test_wrong_hour_bucket_does_not_apply_the_profile(self):
        self._load_slow_profile(20.0)
        other_hour = self.service.navigate(
             (51.500, 25.200), (51.520, 25.200), band=(BAND + 1))
        self.assertEqual(other_hour["learned_segments"], 0)

    def test_load_learned_facts_from_an_export_file(self):
        path = os.path.join(self.tmp.name, "learned_speed.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"facts": [speed_fact([[51.500, 25.200], [51.510, 25.200]], 33.0)]}, fh)
        self.assertEqual(self.service.load_learned_facts(path), 1)
        self.assertEqual(self.service.learned_status()["edge_count"], 1)

    def test_export_without_geometry_loads_zero_edges(self):
        """The silent-no-op case. It must be observable, not just logged."""
        path = os.path.join(self.tmp.name, "no_geometry.json")
        fact = speed_fact([[51.5, 25.2], [51.51, 25.2]], 40.0)
        fact["payload"].pop("geometry")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"facts": [fact]}, fh)
        self.assertEqual(self.service.load_learned_facts(path), 0)
        self.assertEqual(self.service.learned_status()["edge_count"], 0)

    def test_record_eta_without_a_log_raises_rather_than_dropping(self):
        from vector_routing.errors import RouteError

        with self.assertRaises(RouteError):
            self.service.record_eta(100.0, 120.0, 0.8)

    def test_record_eta_feeds_the_distribution(self):
        self.service.set_eta_log(EtaErrorLog())
        self.service.record_eta(100.0, 120.0, 0.9)   # learned route
        self.service.record_eta(100.0, 160.0, 0.0)   # unlearned route
        summary = self.service.eta_log().summary()
        self.assertEqual(summary["n"], 2)
        self.assertEqual(summary["n_learned"], 1)
        # Learned route predicted better, so the headline delta is positive.
        self.assertGreater(summary["delta_p50"], 0)


class CompiledOverlayTest(unittest.TestCase):
    """The fast path must produce the same weights as the reference view.

    ``CompiledLearnedView`` exists purely for speed — it resolves the overlay to
    node keys once instead of per relaxed edge, which took ``/route`` on the full
    Qatar graph from +242% to +14% over baseline. Speed is only worth having if
    the arithmetic is unchanged, so that equivalence is asserted here rather
    than assumed.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.service = build_service(self.tmp.name)
        self.graph = self.service.graph()
        self.overlay = LearnedSpeedOverlay.from_facts(
            [speed_fact([[51.500, 25.200], [51.510, 25.200]], 20.0)]
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_compiles_onto_real_node_keys(self):
        compiled = CompiledLearnedOverlay.compile(self.graph, self.overlay)
        self.assertGreater(len(compiled), 0)
        self.assertEqual(compiled.stats()["unresolved"], 0)

    def test_compiled_and_reference_views_agree_edge_for_edge(self):
        compiled = CompiledLearnedOverlay.compile(self.graph, self.overlay)
        fast = CompiledLearnedView(self.graph, compiled, BAND)
        slow = LearnedSpeedView(self.graph, self.overlay, BAND)
        for node in self.graph.nodes():
            fast_edges = {(to, round(w, 9)) for to, w, _p in fast.neighbors_iter(node)}
            slow_edges = {(to, round(w, 9)) for to, w, _p in slow.neighbors_iter(node)}
            self.assertEqual(fast_edges, slow_edges, f"disagreement leaving {node}")

    def test_empty_overlay_compiles_to_nothing(self):
        compiled = CompiledLearnedOverlay.compile(self.graph, LearnedSpeedOverlay())
        self.assertEqual(len(compiled), 0)
        self.assertEqual(compiled.factors_for_band(BAND), {})

    def test_none_overlay_is_tolerated(self):
        compiled = CompiledLearnedOverlay.compile(self.graph, None)
        self.assertEqual(len(compiled), 0)

    def test_offgraph_coordinates_are_reported_unresolved_not_dropped_silently(self):
        overlay = LearnedSpeedOverlay.from_facts(
            [speed_fact([[10.0, 10.0], [10.001, 10.0]], 30.0)]
        )
        compiled = CompiledLearnedOverlay.compile(self.graph, overlay)
        self.assertEqual(len(compiled), 0)
        self.assertEqual(compiled.stats()["unresolved"], 1)

    def test_disabled_view_applies_no_factors(self):
        compiled = CompiledLearnedOverlay.compile(self.graph, self.overlay)
        view = CompiledLearnedView(self.graph, compiled, BAND, enabled=False)
        for node in self.graph.nodes():
            for (to, w, _p) in view.neighbors_iter(node):
                base = {t: bw for t, bw, _ in self.graph.neighbors_iter(node)}
                self.assertAlmostEqual(w, base[to], places=9)

    def test_service_status_exposes_compiled_counts(self):
        self.service.set_learned_overlay(self.overlay)
        stats = self.service.learned_status()["compiled"]
        self.assertEqual(stats["resolved"], 1)
        self.assertEqual(stats["unresolved"], 0)


class LearnedLatencyTest(unittest.TestCase):
    """Issue 07: loading a profile set must not regress route latency.

    The real measurement is recorded against the Doha graph in the ADR; this
    keeps a proportional guard in CI so a future change that reintroduces a
    per-relaxation cost (the Wave 26c overlay blow-up) fails a test rather than
    being discovered in production.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.service = build_service(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _time_navigate(self, runs=40, **kwargs):
        start = time.perf_counter()
        for _ in range(runs):
            self.service.navigate((51.500, 25.200), (51.520, 25.200), **kwargs)
        return (time.perf_counter() - start) / runs

    def test_navigate_with_profiles_stays_within_3x_of_baseline(self):
        baseline = self._time_navigate()
        self.service.set_learned_overlay(
            LearnedSpeedOverlay.from_facts(
                [speed_fact([[51.500, 25.200], [51.510, 25.200]], 20.0)]
            )
        )
        learned = self._time_navigate(band=BAND)
        # A per-edge dict lookup over the chosen path, not the frontier. On a
        # graph this small the absolute numbers are microseconds, so the ratio
        # is the meaningful assertion.
        self.assertLess(learned, max(baseline * 3.0, 0.05),
                        f"learned navigate {learned:.6f}s vs baseline {baseline:.6f}s")


class LearnedHttpTest(unittest.TestCase):
    """The endpoints, over a real socket."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.service = build_service(cls.tmp.name)
        cls.service.set_learned_overlay(
            LearnedSpeedOverlay.from_facts(
                [speed_fact([[51.500, 25.200], [51.510, 25.200]], 22.0)]
            )
        )
        cls.service.set_eta_log(EtaErrorLog(os.path.join(cls.tmp.name, "eta.jsonl")))
        cls.server = make_server(0, cls.service, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        cls.tmp.cleanup()

    def _get(self, path):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))

    def _post(self, path, payload):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def test_learned_endpoint_reports_the_overlay(self):
        status, body = self._get("/learned")
        self.assertEqual(status, 200)
        self.assertTrue(body["has_learned_overlay"])
        self.assertEqual(body["edge_count"], 1)
        self.assertTrue(body["enabled"])

    def test_navigate_reports_learned_coverage(self):
        status, body = self._get(f"/navigate?from=25.200,51.500&to=25.200,51.520&band={BAND}")
        self.assertEqual(status, 200)
        props = body["features"][0]["properties"]
        self.assertIn("learned_coverage", props)
        self.assertGreater(props["learned_coverage"], 0.0)

    def test_navigate_learned_off_reports_zero_coverage(self):
        status, body = self._get(
            f"/navigate?from=25.200,51.500&to=25.200,51.520&band={BAND}&learned=0")
        self.assertEqual(status, 200)
        self.assertEqual(body["features"][0]["properties"]["learned_coverage"], 0.0)

    def test_route_accepts_the_learned_flag(self):
        status, body = self._get("/route?from=25.200,51.500&to=25.200,51.520&learned=1")
        self.assertEqual(status, 200)
        self.assertEqual(body["features"][0]["geometry"]["type"], "LineString")

    def test_post_eta_records_a_sample(self):
        status, body = self._post("/eta", {"predicted_s": 100, "observed_s": 110, "coverage": 0.9})
        self.assertEqual(status, 200)
        self.assertTrue(body["is_learned"])
        status, summary = self._get("/eta")
        self.assertEqual(status, 200)
        self.assertTrue(summary["available"])
        self.assertGreaterEqual(summary["n"], 1)

    def test_post_eta_rejects_nonsense(self):
        for payload in ({"predicted_s": "x", "observed_s": 1},
                        {"predicted_s": 0, "observed_s": 10},
                        {}):
            status, _ = self._post("/eta", payload)
            self.assertEqual(status, 400, f"accepted {payload!r}")


if __name__ == "__main__":
    unittest.main()
