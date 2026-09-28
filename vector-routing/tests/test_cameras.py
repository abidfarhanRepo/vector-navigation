"""The camera catalog and its projection onto a route (V7.3; typed V7.7).

What these tests pin:

* the catalog is OPTIONAL: an absent, empty or unreadable file is an empty
  catalog, and a route with no catalog carries no ``cameras`` key at all --
  which is exactly what an old backend always did;
* a malformed ENTRY is skipped on its own, and a feature with no source
  identity is skipped rather than invented into ``camera-<n>``;
* the TYPE vocabulary is closed and derived from the source: a stated type
  survives, and anything the source does not state is ``unknown`` -- never
  rounded to "speed camera";
* projection is route-relative and perpendicular (segment-based), ordered by
  along-route distance, REFUSED beyond a snap gate, and REFUSED when the
  perpendicular foot does not lie on the segment (so a camera past the end of
  a route is not claimed at the endpoint);
* the grid prefilter returns exactly what a scan of every segment returns;
* deduplication is by SOURCE IDENTITY: one camera listed twice is one camera,
  and two distinct cameras a few metres apart are two cameras;
* the wire entry is ``{id, lon, lat, along_m, approach_bearing, type,
  maxspeed, direction}`` and carries **no activity/fine claim** -- the extract
  has no tag for either;
* ``maxspeed``/``direction`` ride as provenance only; a missing value is
  null, never invented;
* the approach bearing is the route's own bearing at the camera.
"""

import json
import math
import os
import tempfile
import unittest

from vector_routing.graph import RoutingGraph
from vector_routing.service import RoutingService
from vector_routing.camera_catalog import (
    CAMERA_TYPES, TYPE_AVERAGE_SPEED, TYPE_COMBINED, TYPE_RED_LIGHT, TYPE_SPEED,
    TYPE_UNKNOWN, CameraCatalog, cameras_on_path, classify_camera, _point_segment,
)
from vector_routing.serve import navigate_feature, default_cameras_path

M_PER_DEG_LON = 111.32 * 0.608762 * 1000.0


def _catalog(cameras):
    return CameraCatalog(cameras)


class CameraCatalogTest(unittest.TestCase):

    def test_absent_file_is_an_empty_catalog(self):
        self.assertEqual(len(CameraCatalog.from_path("/nonexistent/cameras.geojson")), 0)

    def test_unreadable_file_is_an_empty_catalog(self):
        with tempfile.NamedTemporaryFile("w", suffix=".geojson", delete=False) as fh:
            fh.write("{not json")
            path = fh.name
        try:
            self.assertEqual(len(CameraCatalog.from_path(path)), 0)
        finally:
            os.unlink(path)

    def test_feature_collection_loads_kind_camera_only(self):
        fc = {
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature", "id": "n1",
                 "geometry": {"type": "Point", "coordinates": [13.40, 52.50]},
                 "properties": {"kind": "camera", "maxspeed": "80",
                                "highway": "speed_camera"}},
                {"type": "Feature", "id": "n2",
                 "geometry": {"type": "Point", "coordinates": [13.41, 52.50]},
                 "properties": {"kind": "camera", "direction": "270",
                                "highway": "speed_camera"}},
                # Not a camera: must be ignored.
                {"type": "Feature", "id": "w1",
                 "geometry": {"type": "Point", "coordinates": [13.42, 52.50]},
                 "properties": {"kind": "road"}},
                # Malformed geometry: must be ignored, never guessed.
                {"type": "Feature", "id": "n3",
                 "geometry": {"type": "Point", "coordinates": [13.43]},
                 "properties": {"kind": "camera"}},
            ],
        }
        cat = CameraCatalog.from_feature_collection(fc)
        self.assertEqual(len(cat), 2)
        self.assertEqual(cat._cameras[0]["id"], "n1")
        self.assertEqual(cat._cameras[0]["maxspeed"], "80")
        self.assertEqual(cat._cameras[1]["direction"], "270")

    def test_camera_direction_falls_back_to_camera_direction_tag(self):
        fc = {
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature", "id": "n9",
                 "geometry": {"type": "Point", "coordinates": [13.40, 52.50]},
                 "properties": {"kind": "camera", "camera:direction": "310"}},
            ],
        }
        cat = CameraCatalog.from_feature_collection(fc)
        self.assertEqual(cat._cameras[0]["direction"], "310")

    # ---- the type vocabulary --------------------------------------------

    def test_every_type_the_source_can_state_is_classified(self):
        # Synthetic tag sets, labelled as such: no Qatar feature carries the
        # enforcement tags yet, and the classifier is what makes the vocabulary
        # closed for the day one does.
        self.assertEqual(classify_camera({"highway": "speed_camera"}), TYPE_SPEED)
        self.assertEqual(
            classify_camera({"highway": "speed_camera", "enforcement": "maxspeed"}),
            TYPE_SPEED)
        self.assertEqual(classify_camera({"enforcement": "maxspeed"}), TYPE_SPEED)
        self.assertEqual(classify_camera({"enforcement": "average_speed"}),
                         TYPE_AVERAGE_SPEED)
        self.assertEqual(
            classify_camera({"highway": "speed_camera", "enforcement": "average_speed"}),
            TYPE_AVERAGE_SPEED)
        self.assertEqual(classify_camera({"enforcement": "traffic_signals"}),
                         TYPE_RED_LIGHT)
        self.assertEqual(
            classify_camera({"highway": "speed_camera", "enforcement": "traffic_signals"}),
            TYPE_COMBINED)

    def test_a_type_the_source_does_not_state_is_unknown_not_a_guess(self):
        # A generic surveillance camera is NOT a speed camera: man_made is the
        # wiki's own tagging-mistake value for one, and `surveillance=red_light`
        # is not a documented Key:surveillance value either.
        for props in (
            {"man_made": "surveillance", "surveillance": "outdoor"},
            {"man_made": "surveillance", "surveillance": "red_light"},
            {"kind": "camera", "maxspeed": "60"},
            {"enforcement": "toll"},
            {"enforcement": "maxweight"},
            {"enforcement": "check"},
            {},
        ):
            self.assertEqual(classify_camera(props), TYPE_UNKNOWN, props)

    def test_the_vocabulary_is_closed_and_no_type_collapses(self):
        self.assertEqual(
            set(CAMERA_TYPES),
            {TYPE_SPEED, TYPE_AVERAGE_SPEED, TYPE_RED_LIGHT, TYPE_COMBINED, TYPE_UNKNOWN})
        for props in ({}, {"highway": "speed_camera"},
                      {"enforcement": "average_speed"},
                      {"enforcement": "traffic_signals"},
                      {"highway": "speed_camera", "enforcement": "traffic_signals"}):
            self.assertIn(classify_camera(props), CAMERA_TYPES)

    def test_catalog_type_census_counts_what_the_source_stated(self):
        cat = _catalog([
            {"id": "n1", "lon": 13.40, "lat": 52.50, "type": TYPE_SPEED},
            {"id": "n2", "lon": 13.41, "lat": 52.50, "type": TYPE_SPEED},
            {"id": "n3", "lon": 13.42, "lat": 52.50, "type": TYPE_UNKNOWN},
        ])
        self.assertEqual(cat.type_census(), {TYPE_SPEED: 2, TYPE_UNKNOWN: 1})

    def test_a_type_outside_the_vocabulary_reads_as_unknown(self):
        cat = _catalog([{"id": "n1", "lon": 13.40, "lat": 52.50,
                         "type": "mobile_phone"}])
        self.assertEqual(cat._cameras[0]["type"], TYPE_UNKNOWN)
        self.assertEqual(cat.type_census(), {TYPE_UNKNOWN: 1})

    # ---- robustness: one bad entry cannot empty a sound catalog -----------

    def test_one_bad_coordinate_does_not_empty_the_catalog(self):
        fc = {"type": "FeatureCollection", "features": [
            {"type": "Feature", "id": "n1",
             "geometry": {"type": "Point", "coordinates": ["east", 52.50]},
             "properties": {"kind": "camera", "highway": "speed_camera"}},
            {"type": "Feature", "id": "n2",
             "geometry": {"type": "Point", "coordinates": [13.41, 52.50]},
             "properties": {"kind": "camera", "highway": "speed_camera"}},
        ]}
        cat = CameraCatalog.from_feature_collection(fc)
        self.assertEqual([c["id"] for c in cat], ["n2"])

    def test_a_wrong_shaped_document_is_an_empty_catalog_not_a_crash(self):
        # `features` is not a list, and an entry is not an object. Both used to
        # raise past `from_path`'s OSError/ValueError catch, into the service's
        # startup path.
        for doc in ({"type": "FeatureCollection", "features": 5},
                    {"type": "FeatureCollection", "features": [1, "x", None]},
                    {"type": "FeatureCollection"},
                    {"features": [{"id": "n1", "properties": "not an object"}]}):
            with tempfile.NamedTemporaryFile("w", suffix=".geojson",
                                             delete=False) as fh:
                json.dump(doc, fh)
                path = fh.name
            try:
                self.assertEqual(len(CameraCatalog.from_path(path)), 0, doc)
            finally:
                os.unlink(path)

    def test_a_camera_with_no_source_identity_is_skipped_not_invented(self):
        fc = {"type": "FeatureCollection", "features": [
            {"type": "Feature",
             "geometry": {"type": "Point", "coordinates": [13.40, 52.50]},
             "properties": {"kind": "camera", "highway": "speed_camera"}},
            {"type": "Feature", "id": "  ",
             "geometry": {"type": "Point", "coordinates": [13.41, 52.50]},
             "properties": {"kind": "camera", "highway": "speed_camera"}},
            {"type": "Feature", "id": "n5",
             "geometry": {"type": "Point", "coordinates": [13.42, 52.50]},
             "properties": {"kind": "camera", "highway": "speed_camera"}},
        ]}
        cat = CameraCatalog.from_feature_collection(fc)
        self.assertEqual([c["id"] for c in cat], ["n5"])


class ProjectionTest(unittest.TestCase):

    def _east_path(self):
        return [(13.40, 52.50), (13.41, 52.50)]

    def test_along_route_position_is_perpendicular_and_ordered(self):
        path = self._east_path()
        total = 0.01 * M_PER_DEG_LON
        mid = 13.405
        cat = _catalog([
            {"id": "nA", "lon": mid, "lat": 52.50 + 0.000001,
             "maxspeed": None, "direction": None},
            {"id": "nB", "lon": 13.402, "lat": 52.500001,
             "maxspeed": "80", "direction": None},
        ])
        out = cameras_on_path(cat, path)
        self.assertEqual([c["id"] for c in out], ["nB", "nA"])
        self.assertAlmostEqual(out[0]["along_m"], 0.2 * total, delta=1.0)
        self.assertAlmostEqual(out[1]["along_m"], 0.5 * total, delta=1.0)
        self.assertAlmostEqual(out[0]["approach_bearing"], 90.0, delta=0.1)

    def test_camera_two_kilometres_off_route_is_never_claimed(self):
        path = self._east_path()
        cat = _catalog([
            {"id": "nFar", "lon": 13.405, "lat": 52.50 - 0.018,  # ~2 km south
             "maxspeed": None, "direction": None},
            {"id": "nOn", "lon": 13.405, "lat": 52.500001,
             "maxspeed": None, "direction": None},
        ])
        out = cameras_on_path(cat, path)
        self.assertEqual([c["id"] for c in out], ["nOn"])

    def test_a_camera_past_the_end_of_the_route_is_not_claimed_at_the_endpoint(self):
        # The perpendicular foot must lie ON a segment. Clamping t and
        # measuring at the endpoint would claim this camera at the route's end
        # with a 20 m "offset" -- a position the driver never passes.
        path = self._east_path()
        cat = _catalog([{"id": "nPast", "lon": 13.41 + 20.0 / M_PER_DEG_LON,
                         "lat": 52.50, "maxspeed": None, "direction": None}])
        self.assertEqual(cameras_on_path(cat, path), [])
        # Just inside the end, on the segment: claimed, as it should be.
        inside = _catalog([{"id": "nIn", "lon": 13.41 - 5.0 / M_PER_DEG_LON,
                            "lat": 52.50, "maxspeed": None, "direction": None}])
        self.assertEqual([c["id"] for c in cameras_on_path(inside, path)], ["nIn"])
        # A zero-length segment has no interior and cannot claim anything.
        dup = [(13.40, 52.50), (13.40, 52.50), (13.41, 52.50)]
        self.assertEqual([c["id"] for c in cameras_on_path(inside, dup)], ["nIn"])

    def test_the_projection_helper_refuses_a_foot_off_the_segment(self):
        t, off = _point_segment((13.405, 52.5002), (13.40, 52.50), (13.41, 52.50))
        self.assertAlmostEqual(t, 0.5, delta=1e-9)
        self.assertGreater(off, 20.0)
        self.assertIsNone(_point_segment((13.42, 52.50), (13.40, 52.50), (13.41, 52.50)))
        self.assertIsNone(_point_segment((13.40, 52.50), (13.40, 52.50), (13.40, 52.50)))

    def test_grid_prefilter_is_exactly_the_flat_scan(self):
        # A route with many vertices, cameras at every offset and spacing:
        # the grid narrows candidates, and must never change the answer.
        path = [(13.40 + i * 0.0005, 52.50 + (i % 3) * 0.0002) for i in range(60)]
        cams = []
        for i in range(40):
            cams.append({"id": f"n{i}",
                         "lon": 13.4001 + i * 0.0007,
                         "lat": 52.50 + (i % 5) * 0.00015,
                         "maxspeed": None, "direction": None, "type": TYPE_SPEED})
        # A couple of decoys: far outside the gate, and exactly at the gate.
        cams.append({"id": "nFar", "lon": 13.405, "lat": 52.60,
                     "maxspeed": None, "direction": None, "type": TYPE_SPEED})
        cat = _catalog(cams)
        got = cameras_on_path(cat, path)
        want = flat_scan(cat, path)
        self.assertEqual([c["id"] for c in got], [c["id"] for c in want])
        for a, b in zip(got, want):
            # The wire rounds along_m to a decimetre; compare at that
            # resolution, which is the resolution the client sees.
            self.assertAlmostEqual(a["along_m"], b["along_m"], delta=0.05)

    def test_deduplication_is_source_identity_not_proximity(self):
        path = self._east_path()
        # ONE camera listed twice, at two positions: one camera, the earliest
        # occurrence winning.
        twice = _catalog([
            {"id": "nA", "lon": 13.405, "lat": 52.500001,
             "maxspeed": None, "direction": None},
            {"id": "nA", "lon": 13.4055, "lat": 52.500001,
             "maxspeed": None, "direction": None},
        ])
        out = cameras_on_path(twice, path)
        self.assertEqual([c["id"] for c in out], ["nA"])
        self.assertLess(out[0]["along_m"], 0.6 * 0.01 * M_PER_DEG_LON)
        # TWO distinct cameras 5 m apart: two cameras. Qatar's closest real
        # pair is 11.5 m apart and they are two installations at one gantry, so
        # a distance merge would delete a real camera.
        dense = _catalog([
            {"id": "nA", "lon": 13.405, "lat": 52.500001,
             "maxspeed": None, "direction": None},
            {"id": "nB", "lon": 13.405 + 5.0 / M_PER_DEG_LON, "lat": 52.500001,
             "maxspeed": None, "direction": None},
            {"id": "nC", "lon": 13.405 + 30.0 / M_PER_DEG_LON, "lat": 52.500001,
             "maxspeed": None, "direction": None},
        ])
        out = cameras_on_path(dense, path)
        self.assertEqual([c["id"] for c in out], ["nA", "nB", "nC"])
        self.assertAlmostEqual(out[1]["along_m"] - out[0]["along_m"], 5.0, delta=1.0)

    def test_wire_entry_carries_no_activity_or_fine_field(self):
        path = self._east_path()
        cat = _catalog([{"id": "nA", "lon": 13.405, "lat": 52.500001,
                         "maxspeed": "100", "direction": None,
                         "type": TYPE_SPEED}])
        out = cameras_on_path(cat, path)
        self.assertEqual(len(out), 1)
        self.assertEqual(
            set(out[0].keys()),
            {"id", "lon", "lat", "along_m", "approach_bearing", "type",
             "maxspeed", "direction"})
        # Provenance may be absent, but is never fabricated.
        self.assertIsNone(out[0]["direction"])
        for key in out[0]:
            for banned in ("active", "enforc", "film", "flash"):
                self.assertNotIn(banned, key.lower())


class WireTest(unittest.TestCase):

    def _service_with_grid(self):
        g = RoutingGraph()
        g.add_way([(13.40, 52.50), (13.41, 52.50)],
                  {"highway": "residential", "maxspeed_kmh": 50})
        return RoutingService(g), g

    def test_no_catalog_means_no_cameras_on_the_wire(self):
        svc, _g = self._service_with_grid()
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertEqual(res["cameras"], [])
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        self.assertNotIn("cameras", feat["properties"])

    def test_empty_catalog_is_the_same_as_none(self):
        svc, _g = self._service_with_grid()
        self.assertEqual(svc.load_cameras("/nonexistent/cameras.geojson"), 0)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertEqual(res["cameras"], [])
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        self.assertNotIn("cameras", feat["properties"])

    def test_catalog_produces_route_relative_camera_list(self):
        svc, _g = self._service_with_grid()
        cat = CameraCatalog([
            {"id": "nA", "lon": 13.405, "lat": 52.500001,
             "maxspeed": "80", "direction": None, "type": TYPE_SPEED},
        ])
        svc.load_cameras(os.path.join(tempfile.mkdtemp(), "c.geojson"))  # empty, clears
        svc._router.set_cameras(cat)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertIn("cameras", res)
        self.assertEqual(len(res["cameras"]), 1)
        self.assertEqual(res["cameras"][0]["id"], "nA")
        self.assertEqual(res["cameras"][0]["maxspeed"], "80")
        self.assertEqual(res["cameras"][0]["type"], TYPE_SPEED)
        self.assertAlmostEqual(
            res["cameras"][0]["along_m"], res["distance_m"] / 2.0, delta=2.0)

    def test_wire_serializes_cameras_only_when_present(self):
        svc, _g = self._service_with_grid()
        cat = CameraCatalog([{"id": "nA", "lon": 13.405, "lat": 52.500001,
                              "maxspeed": None, "direction": "270",
                              "type": TYPE_SPEED}])
        svc._router.set_cameras(cat)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        self.assertIn("cameras", res)
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        cam = feat["properties"]["cameras"][0]
        self.assertEqual(
            set(cam.keys()),
            {"id", "lon", "lat", "along_m", "approach_bearing", "type",
             "maxspeed", "direction"})
        # A camera the client will suppress on the direction gate is still ON
        # the wire: the wire reports facts, the client decides the warning.
        self.assertEqual(cam["direction"], "270")

    def test_the_wire_reports_every_type_including_unknown(self):
        svc, _g = self._service_with_grid()
        cat = _catalog([
            {"id": "n1", "lon": 13.403, "lat": 52.500001, "type": TYPE_SPEED},
            {"id": "n2", "lon": 13.405, "lat": 52.500001, "type": TYPE_UNKNOWN},
            {"id": "n3", "lon": 13.407, "lat": 52.500001, "type": TYPE_COMBINED},
        ])
        svc._router.set_cameras(cat)
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        by_id = {c["id"]: c["type"] for c in res["cameras"]}
        self.assertEqual(by_id, {"n1": TYPE_SPEED, "n2": TYPE_UNKNOWN,
                                 "n3": TYPE_COMBINED})
        # The census is what makes the claim auditable: two announced types
        # and one camera the client will carry without announcing.
        self.assertEqual(cat.type_census(),
                         {TYPE_SPEED: 1, TYPE_UNKNOWN: 1, TYPE_COMBINED: 1})

    def test_old_serializer_shape_is_unchanged(self):
        """A navigate result without cameras freezes the old wire shape."""
        svc, _g = self._service_with_grid()
        res = svc.navigate((13.40, 52.50), (13.41, 52.50))
        feat = navigate_feature(res, (13.40, 52.50), (13.41, 52.50), "fastest")
        self.assertNotIn("cameras", feat["properties"])
        self.assertIn("steps", feat["properties"])
        self.assertIn("snap_max_m", feat["properties"])
        self.assertIn("learned_coverage", feat["properties"])


class SiblingPathTest(unittest.TestCase):

    def test_default_cameras_path_derives_beside_roads(self):
        with tempfile.TemporaryDirectory() as d:
            roads = os.path.join(d, "qatar_roads.geojson")
            cameras = os.path.join(d, "qatar_cameras.geojson")
            self.assertIsNone(default_cameras_path(roads))  # sibling absent
            with open(cameras, "w", encoding="utf-8") as fh:
                fh.write("{}")
            self.assertEqual(default_cameras_path(roads), cameras)
        self.assertIsNone(default_cameras_path("/x/y.routing"))


def flat_scan(catalog, path):
    """The reference implementation: every segment, no prefilter.

    Kept here as the oracle for the grid-prefilter equivalence test, so the
    optimisation is pinned against the thing it replaced rather than against
    its own behaviour.
    """
    seg_len = [0.0] * (len(path) - 1)
    for i in range(len(path) - 1):
        a, b = path[i], path[i + 1]
        dlat = math.radians(b[1] - a[1])
        dlon = math.radians(b[0] - a[0])
        x = dlon * math.cos(math.radians((a[1] + b[1]) / 2.0))
        seg_len[i] = math.hypot(x, dlat) * 6371008.8
    cum = [0.0]
    for s in seg_len:
        cum.append(cum[-1] + s)
    out = []
    for cam in catalog:
        best = None
        for i in range(len(path) - 1):
            seg = _point_segment((cam["lon"], cam["lat"]), path[i], path[i + 1])
            if seg is None:
                continue
            t, off = seg
            if best is None or off < best[1]:
                best = (i, off, t)
        if best is None or best[1] > 40.0:
            continue
        i, _off, t = best
        out.append({"id": cam["id"], "along_m": cum[i] + t * seg_len[i]})
    out.sort(key=lambda m: (round(m["along_m"], 1), m["id"]))
    return out


if __name__ == "__main__":
    unittest.main()
