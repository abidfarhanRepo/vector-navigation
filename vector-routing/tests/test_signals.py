"""The signal catalog and its projection onto a route (V7 Stage 5).

What these tests pin:

* the catalog is OPTIONAL: an absent, empty or unreadable file is an empty
  catalog, and a route with no catalog carries no ``signals`` key at all --
  which is exactly what an old backend always did;
* projection is route-relative and perpendicular (segment-based), ordered by
  along-route distance, deduplicated for same-position duplicates, and
  REFUSED beyond a snap gate -- a signal 2 km off route is never claimed;
* the wire entry is ``{id, lon, lat, along_m, approach_bearing}`` and carries
  **no timing field**, because the extract has no timing;
* the approach bearing is the route's own bearing at the signal, not an OSM
  direction tag.
"""

import json
import os
import tempfile
import unittest

from vector_routing.graph import RoutingGraph
from vector_routing.router import Router
from vector_routing.service import RoutingService
from vector_routing.signals import SignalCatalog, signals_on_path
from vector_routing.serve import navigate_feature, default_signals_path

EAST_KM = 111.32 * 0.608762  # km per degree of longitude at 52.5 N
M_PER_DEG_LON = EAST_KM * 1000.0


def _catalog(signals):
    return SignalCatalog(signals)


class SignalCatalogTest(unittest.TestCase):

    def test_absent_file_is_an_empty_catalog(self):
        self.assertEqual(len(SignalCatalog.from_path("/nonexistent/signals.geojson")), 0)

    def test_unreadable_file_is_an_empty_catalog(self):
        with tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False) as fh:
            fh.write("{not json")
            path = fh.name
        try:
            self.assertEqual(len(SignalCatalog.from_path(path)), 0)
        finally:
            os.unlink(path)

    def test_feature_collection_loads_kind_signal_only(self):
        fc = {
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature", "id": "n1",
                 "geometry": {"type": "Point", "coordinates": [13.40, 52.50]},
                 "properties": {"kind": "signal"}},
                {"type": "Feature", "id": "n2",
                 "geometry": {"type": "Point", "coordinates": [13.41, 52.50]},
                 "properties": {"kind": "signal", "traffic_signals:direction": "forward"}},
                # Not a signal: must be ignored.
                {"type": "Feature", "id": "w1",
                 "geometry": {"type": "Point", "coordinates": [13.42, 52.50]},
                 "properties": {"kind": "road"}},
                # Malformed geometry: must be ignored, never guessed.
                {"type": "Feature", "id": "n3",
                 "geometry": {"type": "Point", "coordinates": [13.43]},
                 "properties": {"kind": "signal"}},
            ],
        }
        cat = SignalCatalog.from_feature_collection(fc)
        self.assertEqual(len(cat), 2)
        self.assertEqual(cat._signals[1]["id"], "n2")
        self.assertEqual(cat._signals[1]["direction"], "forward")


class ProjectionTest(unittest.TestCase):

    def _east_path(self):
        # A straight eastward route 677.7 m long from (13.40, 52.50).
        return [(13.40, 52.50), (13.41, 52.50)]

    def test_along_route_position_is_perpendicular_and_ordered(self):
        path = self._east_path()
        total = 0.01 * M_PER_DEG_LON
        mid = 13.405
        cat = _catalog([{"id": "nA", "lon": mid, "lat": 52.50 + 0.000001, "direction": None},
                        {"id": "nB", "lon": 13.402, "lat": 52.500001, "direction": None}])
        out = signals_on_path(cat, path)
        self.assertEqual([s["id"] for s in out], ["nB", "nA"])
        self.assertAlmostEqual(out[0]["along_m"], 0.2 * total, delta=1.0)
        self.assertAlmostEqual(out[1]["along_m"], 0.5 * total, delta=1.0)
        # Bearing is the route's own eastward travel, not an OSM tag.
        self.assertAlmostEqual(out[0]["approach_bearing"], 90.0, delta=0.1)
        self.assertAlmostEqual(out[1]["approach_bearing"], 90.0, delta=0.1)

    def test_signal_two_kilometres_off_route_is_never_claimed(self):
        path = self._east_path()
        cat = _catalog([
            {"id": "nFar", "lon": 13.405, "lat": 52.50 - 0.018, "direction": None},  # ~2 km south
            {"id": "nOn", "lon": 13.405, "lat": 52.500001, "direction": None},
        ])
        out = signals_on_path(cat, path)
        self.assertEqual([s["id"] for s in out], ["nOn"])

    def test_same_position_duplicates_collapse_but_dense_signals_stay(self):
        path = self._east_path()
        cat = _catalog([
            # The same physical lights, mapped twice at the same junction.
            {"id": "nA", "lon": 13.405, "lat": 52.500001, "direction": None},
            {"id": "nA2", "lon": 13.405, "lat": 52.500002, "direction": None},
            # A genuinely different signal 30 m further east (well under 100 m).
            {"id": "nB", "lon": 13.405 + 30.0 / M_PER_DEG_LON,
             "lat": 52.500001, "direction": None},
        ])
        out = signals_on_path(cat, path)
        self.assertEqual([s["id"] for s in out], ["nA", "nB"])
        self.assertGreater(out[1]["along_m"] - out[0]["along_m"], 20.0)

    def test_wire_entry_carries_no_timing_field(self):
        path = self._east_path()
        cat = _catalog([{"id": "nA", "lon": 13.405, "lat": 52.500001, "direction": None}])
        out = signals_on_path(cat, path)
        self.assertEqual(len(out), 1)
        self.assertEqual(
            set(out[0].keys()), {"id", "lon", "lat", "along_m", "approach_bearing"})

    def test_rounding_maintains_route_relative_order_at_dense_junctions(self):
        """Two signals <100 m apart stay distinct and in route order."""
        path = self._east_path()
        cat = _catalog([
            {"id": "n1", "lon": 13.4050, "lat": 52.500001, "direction": None},
            {"id": "n2", "lon": 13.4058, "lat": 52.500001, "direction": None},
        ])
        out = signals_on_path(cat, path)
        self.assertEqual([s["id"] for s in out], ["n1", "n2"])
        self.assertLess(out[1]["along_m"] - out[0]["along_m"], 100.0)
        self.assertGreater(out[1]["along_m"], out[0]["along_m"])


class WireTest(unittest.TestCase):

    def _service_with_grid(self):
        g = RoutingGraph()
        # A small east-west street with one crossing street, so a route has a
        # known path and signals can sit exactly on it.
        g.add_way([(13.40, 52.50), (13.41, 52.50)], {"highway": "residential", "maxspeed_kmh": 50})
        return RoutingService(g), g

    def test_no_catalog_means_no_signals_on_the_wire(self):
        svc, _g = self._service_with_grid()
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        # The raw answer always carries a (possibly empty) signals list; the
        # WIRE omits the key when empty -- that is what an old client sees.
        self.assertEqual(res["signals"], [])
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        self.assertNotIn("signals", feat["properties"])

    def test_empty_catalog_is_the_same_as_none(self):
        svc, _g = self._service_with_grid()
        self.assertEqual(svc.load_signals("/nonexistent/signals.geojson"), 0)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertEqual(res["signals"], [])
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        self.assertNotIn("signals", feat["properties"])

    def test_catalog_produces_route_relative_signal_list(self):
        svc, g = self._service_with_grid()
        cat = SignalCatalog([
            {"id": "nA", "lon": 13.405, "lat": 52.500001, "direction": "forward"},
        ])
        svc._router.set_signals(cat)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertIn("signals", res)
        self.assertEqual(len(res["signals"]), 1)
        self.assertEqual(res["signals"][0]["id"], "nA")
        self.assertAlmostEqual(
            res["signals"][0]["along_m"], res["distance_m"] / 2.0, delta=2.0)

    def test_wire_serializes_signals_only_when_present(self):
        svc, _g = self._service_with_grid()
        cat = SignalCatalog([{"id": "nA", "lon": 13.405, "lat": 52.500001, "direction": None}])
        svc._router.set_signals(cat)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertIn("signals", res)
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        sig = feat["properties"]["signals"][0]
        self.assertEqual(set(sig.keys()), {"id", "lon", "lat", "along_m", "approach_bearing"})

    def test_old_serializer_shape_is_unchanged(self):
        """A navigate result without signals freezes the old wire shape."""
        svc, _g = self._service_with_grid()
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        self.assertNotIn("signals", feat["properties"])
        self.assertIn("steps", feat["properties"])
        self.assertIn("snap_max_m", feat["properties"])
        self.assertIn("learned_coverage", feat["properties"])


class SiblingPathTest(unittest.TestCase):

    def test_default_signals_path_derives_beside_roads(self):
        with tempfile.TemporaryDirectory() as d:
            roads = os.path.join(d, "qatar_roads.geojson")
            signals = os.path.join(d, "qatar_signals.geojson")
            self.assertIsNone(default_signals_path(roads))  # sibling absent
            with open(signals, "w", encoding="utf-8") as fh:
                fh.write("{}")
            self.assertEqual(default_signals_path(roads), signals)
        self.assertIsNone(default_signals_path("/x/y.routing"))


if __name__ == "__main__":
    unittest.main()