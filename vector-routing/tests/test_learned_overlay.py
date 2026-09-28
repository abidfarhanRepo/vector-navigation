"""Tests for the learned speed-profile overlay + ETA error log (issue 07)."""

import os
import tempfile
import unittest

from vector_routing.eta_log import LEARNED_COVERAGE_THRESHOLD, EtaErrorLog
from vector_routing.learned_overlay import (
    DEFAULT_BASELINE_KMH,
    LearnedSpeedOverlay,
    LearnedSpeedView,
)

A = (51.5300, 25.2800)
B = (51.5310, 25.2800)
C = (51.5320, 25.2800)

MORNING = 0  # band used across these tests


def speed_profile_fact(band=MORNING, speed=60.0, geometry=None, segment="seg-1"):
    return {
        "fact_type": "speed_profile",
        "payload": {
            "segment_id": segment,
            "band": band,
            "median_speed_kmh": speed,
            "geometry": geometry if geometry is not None else [list(A), list(B)],
        },
    }


class FakeGraph:
    """Minimal graph exposing the surface the views rely on."""

    def __init__(self, coords, edges):
        self._coords = coords          # node key -> (lng, lat)
        self._edges = edges            # node key -> [(to, weight_m, props)]

    def nodes(self):
        return dict(self._coords)

    def has_node(self, k):
        return k in self._coords

    def node_coord(self, k):
        return self._coords[k]

    def nearest_node(self, lon, lat):
        return min(self._coords, key=lambda k: (self._coords[k][0] - lon) ** 2)

    def nearest_routable_node(self, lon, lat, max_m=800.0):
        return self.nearest_node(lon, lat)

    def neighbors_iter(self, u):
        return iter(self._edges.get(u, []))


def fake_graph():
    return FakeGraph(
        coords={"a": A, "b": B, "c": C},
        edges={"a": [("b", 100.0, {})], "b": [("c", 100.0, {})]},
    )


class OverlayBuildTest(unittest.TestCase):
    def test_builds_from_facts(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact()])
        self.assertEqual(overlay.speed_for(A, B, MORNING), 60.0)

    def test_edge_is_undirected(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact()])
        self.assertEqual(overlay.speed_for(B, A, MORNING), 60.0)

    def test_other_bands_are_not_covered(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(band=MORNING)])
        self.assertIsNone(overlay.speed_for(A, B, MORNING + 1))

    def test_fact_without_geometry_is_skipped_not_guessed(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(geometry=[])])
        self.assertEqual(len(overlay), 0)

    def test_non_speed_facts_are_ignored(self):
        fact = speed_profile_fact()
        fact["fact_type"] = "road_candidate"
        self.assertEqual(len(LearnedSpeedOverlay.from_facts([fact])), 0)

    def test_nonsense_speeds_are_rejected(self):
        for bad in (0, -5, "fast", None):
            self.assertEqual(len(LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=bad)])), 0)

    def test_coverage_reports_edges_and_buckets(self):
        overlay = LearnedSpeedOverlay.from_facts(
            [speed_profile_fact(band=1), speed_profile_fact(band=2)]
        )
        self.assertEqual(overlay.coverage(), {"edges": 1, "edge_bands": 2})


class FactorTest(unittest.TestCase):
    def test_unlearned_edge_is_exactly_neutral(self):
        """0% coverage must behave identically to having no overlay at all."""
        overlay = LearnedSpeedOverlay.from_facts([])
        self.assertEqual(overlay.factor_for(A, B, MORNING), 1.0)

    def test_faster_than_baseline_is_a_bonus(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=60.0)])
        factor = overlay.factor_for(A, B, MORNING, baseline_kmh=30.0)
        self.assertAlmostEqual(factor, 0.5, places=4)

    def test_slower_than_baseline_is_a_penalty(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=15.0)])
        factor = overlay.factor_for(A, B, MORNING, baseline_kmh=30.0)
        self.assertAlmostEqual(factor, 2.0, places=4)

    def test_factor_is_clamped_against_a_bad_aggregate(self):
        wild = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=0.001)])
        self.assertLessEqual(wild.factor_for(A, B, MORNING), 10.0)
        fast = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=99999.0)])
        self.assertGreaterEqual(fast.factor_for(A, B, MORNING), 0.25)


class ViewTest(unittest.TestCase):
    def test_learned_edge_weight_is_scaled(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=60.0)])
        view = LearnedSpeedView(fake_graph(), overlay, MORNING, baseline_kmh=30.0)
        (to, w, _props) = list(view.neighbors_iter("a"))[0]
        self.assertEqual(to, "b")
        self.assertAlmostEqual(w, 50.0, places=4)  # 100 m * 0.5

    def test_unlearned_edge_weight_is_untouched(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=60.0)])
        view = LearnedSpeedView(fake_graph(), overlay, MORNING, baseline_kmh=30.0)
        (to, w, _props) = list(view.neighbors_iter("b"))[0]
        self.assertEqual(to, "c")
        self.assertAlmostEqual(w, 100.0, places=4)

    def test_disabled_view_is_a_full_rollback(self):
        """One flag returns the router to exactly its pre-learning behaviour."""
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=60.0)])
        view = LearnedSpeedView(fake_graph(), overlay, MORNING, enabled=False)
        (_to, w, _props) = list(view.neighbors_iter("a"))[0]
        self.assertAlmostEqual(w, 100.0, places=4)

    def test_view_counts_learned_edges(self):
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=60.0)])
        view = LearnedSpeedView(fake_graph(), overlay, MORNING)
        list(view.neighbors_iter("a"))
        list(view.neighbors_iter("b"))
        self.assertEqual((view.edges_learned, view.edges_total), (1, 2))

    def test_composes_multiplicatively_with_a_traffic_view(self):
        """Learned baseline and live congestion stack, neither overwrites."""
        from vector_routing.traffic_overlay import OverlayView, TrafficOverlay

        traffic = TrafficOverlay()
        # Congestion doubles cost on a->b (geometry given as [lat, lon] pairs).
        traffic._by_pair[tuple(sorted([A, B]))] = 2.0

        base = OverlayView(fake_graph(), traffic)
        overlay = LearnedSpeedOverlay.from_facts([speed_profile_fact(speed=60.0)])
        stacked = LearnedSpeedView(base, overlay, MORNING, baseline_kmh=30.0)

        (_to, w, _props) = list(stacked.neighbors_iter("a"))[0]
        # 100 m * 2.0 (congestion) * 0.5 (typically fast) = 100.0
        self.assertAlmostEqual(w, 100.0, places=4)


class EtaLogTest(unittest.TestCase):
    def test_error_is_signed_and_percentage_scale_free(self):
        log = EtaErrorLog()
        s = log.record(predicted_s=100.0, observed_s=150.0, coverage=1.0)
        self.assertAlmostEqual(s.error_s, 50.0)
        self.assertAlmostEqual(s.abs_pct_error, 100 * 50 / 150, places=4)

    def test_coverage_decides_the_bucket(self):
        log = EtaErrorLog()
        below = log.record(100, 110, coverage=LEARNED_COVERAGE_THRESHOLD - 0.01)
        at = log.record(100, 110, coverage=LEARNED_COVERAGE_THRESHOLD)
        self.assertFalse(below.is_learned)
        self.assertTrue(at.is_learned)

    def test_optional_distance_round_trips_and_keeps_legacy_shape(self):
        log = EtaErrorLog()
        with_dist = log.record(100.0, 150.0, coverage=0.5, distance_m=1234.5)
        self.assertEqual(with_dist.to_row()["distance_m"], 1234.5)
        # without distance the row stays exactly the pre-V1.1 shape, so old
        # consumers of eta.jsonl are unaffected.
        legacy = log.record(100.0, 150.0, coverage=0.5)
        self.assertEqual(set(legacy.to_row()),
                         {"predicted_s", "observed_s", "coverage"})

    def test_summary_splits_learned_from_unlearned(self):
        log = EtaErrorLog()
        for _ in range(5):
            log.record(100, 105, coverage=1.0)   # learned: 4.8% error
        for _ in range(5):
            log.record(100, 160, coverage=0.0)   # unlearned: 37.5% error
        summary = log.summary()
        self.assertEqual((summary["n_learned"], summary["n_unlearned"]), (5, 5))
        self.assertLess(summary["learned"]["p50"], summary["unlearned"]["p50"])
        self.assertGreater(summary["delta_p50"], 0, "positive delta means learning works")

    def test_delta_is_none_without_both_buckets(self):
        log = EtaErrorLog()
        log.record(100, 105, coverage=1.0)
        self.assertIsNone(log.summary()["delta_p50"])

    def test_distribution_is_reported_not_just_a_mean(self):
        log = EtaErrorLog()
        for i in range(10):
            log.record(100, 100 + i, coverage=1.0)
        dist = log.summary()["learned"]
        for key in ("p50", "p90", "p99", "mean", "n"):
            self.assertIn(key, dist)

    def test_samples_persist_and_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "eta.jsonl")
            EtaErrorLog(path).record(100, 120, coverage=0.9)
            self.assertEqual(EtaErrorLog(path).summary()["n"], 1)

    def test_corrupt_line_does_not_take_the_log_down(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "eta.jsonl")
            log = EtaErrorLog(path)
            log.record(100, 120, coverage=0.9)
            with open(path, "a", encoding="utf-8") as fh:
                fh.write("{not json\n")
            self.assertEqual(EtaErrorLog(path).summary()["n"], 1)

    def test_no_location_or_identity_is_recorded(self):
        log = EtaErrorLog()
        sample = log.record(100, 120, coverage=0.5)
        self.assertEqual(set(sample.to_row()), {"predicted_s", "observed_s", "coverage"})


if __name__ == "__main__":
    unittest.main()
