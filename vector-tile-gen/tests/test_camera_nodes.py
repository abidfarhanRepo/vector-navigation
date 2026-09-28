"""Speed-camera nodes get from the OSM extract into their own artifact.

Before V7.3 a ``highway=speed_camera`` node was classified as a ROAD by
``_attrs`` and then silently dropped in phase 3 (only poi/label points are
emitted), so Qatar's 133 cameras reached no layer at all. These tests pin the
new contract, mirroring the V7 Stage 5 signal contract:

* a camera node is ``kind=camera`` and is written to the SEPARATE artifact
  named by ``--cameras-out``, never into the main collection;
* the main collection's road count is untouched by camera ingestion;
* identity is the OSM node id (feature ``id`` = ``n<id>``), the same
  reference the /navigate wire contract reuses;
* ``maxspeed`` and ``direction`` are preserved -- as provenance, NEVER as a
  claim that the camera is active/enforcing (there is no such tag in OSM);
* without ``--cameras-out`` (the old standalone contract) cameras merge into
  the main collection rather than being lost;
* a way wrongly typed ``highway=speed_camera`` is skipped, never emitted
  as a line.
"""

import importlib.util
import json
import os
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_SCRIPT = os.path.join(os.path.dirname(_HERE), "scripts", "osm_to_geojson.py")
_spec = importlib.util.spec_from_file_location("osm_to_geojson", _SCRIPT)
o2g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(o2g)


_TILE = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2850" lon="51.5310"/>
  <node id="3" lat="25.2860" lon="51.5305">
    <tag k="highway" v="speed_camera"/>
    <tag k="maxspeed" v="80"/>
  </node>
  <node id="4" lat="25.2865" lon="51.5315">
    <tag k="highway" v="speed_camera"/>
    <tag k="direction" v="270"/>
  </node>
  <node id="9" lat="25.2870" lon="51.5320">
    <tag k="amenity" v="cafe"/>
    <tag k="name" v="Test Cafe"/>
  </node>
  <way id="100">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="residential"/>
    <tag k="name" v="Seam Way A"/>
  </way>
</osm>
"""


class CameraNodesTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.src = os.path.join(self.dir, "cam.osm")
        self.out = os.path.join(self.dir, "out.geojson")
        self.cam = os.path.join(self.dir, "out_cameras.geojson")
        with open(self.src, "w", encoding="utf-8") as fh:
            fh.write(_TILE)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _convert(self, cameras_out=None, srcs=None):
        o2g.convert(srcs or [self.src], self.out, cameras_out=cameras_out)
        with open(self.out, encoding="utf-8") as fh:
            main = json.load(fh)
        cam_fc = None
        if cameras_out and os.path.exists(cameras_out):
            with open(cameras_out, encoding="utf-8") as fh:
                cam_fc = json.load(fh)
        return main, cam_fc

    def test_cameras_go_to_the_separate_artifact_only(self):
        main, cam = self._convert(cameras_out=self.cam)
        self.assertIsNotNone(cam)
        kinds = {f["properties"]["kind"] for f in cam["features"]}
        self.assertEqual(kinds, {"camera"})
        # None in the main collection: camera ingestion must not touch the
        # basemap/the road network's feature count.
        self.assertFalse(any(
            f.get("properties", {}).get("kind") == "camera" for f in main["features"]
        ))

    def test_camera_count_identity_and_coordinates(self):
        _main, cam = self._convert(cameras_out=self.cam)
        self.assertEqual(len(cam["features"]), 2)
        ids = {f["id"] for f in cam["features"]}
        self.assertEqual(ids, {"n3", "n4"})
        for f in cam["features"]:
            lon, lat = f["geometry"]["coordinates"]
            self.assertGreater(lon, 50.0)
            self.assertGreater(lat, 20.0)

    def test_relevant_tags_are_preserved_as_provenance_only(self):
        _main, cam = self._convert(cameras_out=self.cam)
        by_id = {f["id"]: f["properties"] for f in cam["features"]}
        self.assertEqual(by_id["n3"]["maxspeed"], "80")
        self.assertEqual(by_id["n4"]["direction"], "270")
        # No OSM tag can say a camera is active/enforcing/filming, and the
        # schema must not invent one: provenance is all this artifact carries.
        for props in by_id.values():
            for key in props:
                self.assertNotIn("active", key.lower())
                self.assertNotIn("enforc", key.lower())
                self.assertNotIn("film", key.lower())
                self.assertNotIn("flash", key.lower())

    def test_road_feature_count_is_unchanged_by_camera_ingestion(self):
        with_cam, _ = self._convert(cameras_out=self.cam)
        roads_with = [f for f in with_cam["features"]
                      if f.get("properties", {}).get("kind") == "road"]
        o2g.convert([self.src], self.out, cameras_out=None)
        with open(self.out, encoding="utf-8") as fh:
            main_plain = json.load(fh)
        roads_plain = [f for f in main_plain["features"]
                       if f.get("properties", {}).get("kind") == "road"]
        self.assertEqual(roads_with, roads_plain)

    def test_old_contract_merges_cameras_into_the_main_collection(self):
        main, cam = self._convert(cameras_out=None)
        self.assertIsNone(cam)
        cameras = [f for f in main["features"]
                   if f.get("properties", {}).get("kind") == "camera"]
        self.assertEqual(len(cameras), 2)

    def test_the_enforcement_tag_reaches_the_artifact(self):
        # The one legitimately-sourced camera TYPE evidence in OSM: a device
        # node that also states what it enforces. It must survive the bake,
        # because the routing classifier reads it there and nowhere else --
        # and a value the classifier does not recognise must arrive intact
        # rather than being rounded to the nearest type.
        only = os.path.join(self.dir, "enforced.osm")
        with open(only, "w", encoding="utf-8") as fh:
            fh.write("""<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="11" lat="25.2860" lon="51.5305">
    <tag k="highway" v="speed_camera"/>
    <tag k="enforcement" v="average_speed"/>
  </node>
  <node id="12" lat="25.2865" lon="51.5315">
    <tag k="highway" v="speed_camera"/>
    <tag k="enforcement" v="toll"/>
  </node>
  <node id="13" lat="25.2870" lon="51.5325">
    <tag k="highway" v="speed_camera"/>
  </node>
</osm>
""")
        _main, cam = self._convert(cameras_out=self.cam, srcs=[only])
        by_id = {f["id"]: f["properties"] for f in cam["features"]}
        self.assertEqual(by_id["n11"]["enforcement"], "average_speed")
        self.assertEqual(by_id["n12"]["enforcement"], "toll")
        self.assertNotIn("enforcement", by_id["n13"])

    def test_a_way_typed_speed_camera_is_not_emitted_as_a_line(self):
        only = os.path.join(self.dir, "way-camera.osm")
        with open(only, "w", encoding="utf-8") as fh:
            fh.write("""<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2860" lon="51.5310"/>
  <way id="50">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="speed_camera"/>
  </way>
</osm>
""")
        main, cam = self._convert(cameras_out=self.cam, srcs=[only])
        self.assertEqual(cam["features"], [])
        self.assertEqual(main["features"], [])


if __name__ == "__main__":
    unittest.main()