"""Traffic-signal nodes get from the OSM extract into their own artifact.

Before V7 Stage 5 a ``highway=traffic_signals`` node was classified as a
ROAD by ``_attrs`` and then silently dropped in phase 3 (only poi/label
points are emitted), so Qatar's 899-1,934 signals reached no layer at all.
These tests pin the new contract:

* a signal node is ``kind=signal`` and is written to the SEPARATE artifact
  named by ``--signals-out``, never into the main collection;
* the main collection's road count is untouched by signal ingestion;
* identity is the OSM node id (feature ``id`` = ``n<id>``), the same
  reference the /navigate wire contract reuses;
* ``traffic_signals:direction`` and ``crossing`` are preserved -- as
  provenance, NOT as timing or as the primary approach definition;
* without ``--signals-out`` (the old standalone contract) signals merge into
  the main collection rather than being lost;
* a way wrongly typed ``highway=traffic_signals`` is skipped, never emitted
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


_TILE_A = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2850" lon="51.5310"/>
  <node id="3" lat="25.2860" lon="51.5305">
    <tag k="highway" v="traffic_signals"/>
    <tag k="traffic_signals:direction" v="forward"/>
  </node>
  <node id="4" lat="25.2865" lon="51.5315">
    <tag k="highway" v="traffic_signals"/>
    <tag k="crossing" v="traffic_signals"/>
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

_TILE_B = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="4" lat="25.2865" lon="51.5315">
    <tag k="highway" v="traffic_signals"/>
    <tag k="crossing" v="traffic_signals"/>
  </node>
  <way id="100">
    <nd ref="1"/><nd ref="2"/><nd ref="5"/>
    <tag k="highway" v="residential"/>
    <tag k="name" v="Seam Way A"/>
  </way>
  <node id="5" lat="25.2850" lon="51.5320"/>
</osm>
"""


class SignalNodesTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.a = os.path.join(self.dir, "a.osm")
        self.b = os.path.join(self.dir, "b.osm")
        self.out = os.path.join(self.dir, "out.geojson")
        self.sig = os.path.join(self.dir, "out_signals.geojson")
        with open(self.a, "w", encoding="utf-8") as fh:
            fh.write(_TILE_A)
        with open(self.b, "w", encoding="utf-8") as fh:
            fh.write(_TILE_B)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _convert(self, paths, signals_out=None):
        o2g.convert(paths, self.out, signals_out=signals_out)
        with open(self.out, encoding="utf-8") as fh:
            main = json.load(fh)
        sig_fc = None
        if signals_out and os.path.exists(signals_out):
            with open(signals_out, encoding="utf-8") as fh:
                sig_fc = json.load(fh)
        return main, sig_fc

    def test_signals_go_to_the_separate_artifact_only(self):
        main, sig = self._convert([self.a, self.b], signals_out=self.sig)
        self.assertIsNotNone(sig)
        kinds = {f["properties"]["kind"] for f in sig["features"]}
        self.assertEqual(kinds, {"signal"})
        # None in the main collection: signal ingestion must not touch the
        # basemap/the road network's feature count.
        self.assertFalse(any(
            f.get("properties", {}).get("kind") == "signal" for f in main["features"]
        ))

    def test_signal_count_and_coordinates(self):
        _main, sig = self._convert([self.a, self.b], signals_out=self.sig)
        # Node 4 appears in BOTH tiles; deduplication must yield it once.
        self.assertEqual(len(sig["features"]), 2)
        ids = {f["id"] for f in sig["features"]}
        self.assertEqual(ids, {"n3", "n4"})
        for f in sig["features"]:
            lon, lat = f["geometry"]["coordinates"]
            self.assertGreater(lon, 50.0)
            self.assertGreater(lat, 20.0)

    def test_relevant_tags_are_preserved_as_provenance(self):
        _main, sig = self._convert([self.a, self.b], signals_out=self.sig)
        by_id = {f["id"]: f["properties"] for f in sig["features"]}
        self.assertEqual(by_id["n3"]["traffic_signals:direction"], "forward")
        self.assertEqual(by_id["n4"]["crossing"], "traffic_signals")
        # None of these tags is timing. The property schema must not carry a
        # cycle/phase/offset key, because no OSM signal tag is one.
        for props in by_id.values():
            for key in props:
                self.assertNotIn("cycle", key)
                self.assertNotIn("offset", key)
                self.assertNotIn("phase", key)

    def test_road_feature_count_is_unchanged_by_signal_ingestion(self):
        with_a, _ = self._convert([self.a, self.b], signals_out=self.sig)
        roads_with = [f for f in with_a["features"]
                      if f.get("properties", {}).get("kind") == "road"]
        # Same inputs, no signals artifact: the roads must be identical.
        o2g.convert([self.a, self.b], self.out, signals_out=None)
        with open(self.out, encoding="utf-8") as fh:
            main_plain = json.load(fh)
        roads_plain = [f for f in main_plain["features"]
                       if f.get("properties", {}).get("kind") == "road"]
        self.assertEqual(roads_with, roads_plain)

    def test_old_contract_merges_signals_into_the_main_collection(self):
        main, sig = self._convert([self.a, self.b], signals_out=None)
        self.assertIsNone(sig)
        signals = [f for f in main["features"]
                   if f.get("properties", {}).get("kind") == "signal"]
        self.assertEqual(len(signals), 2)

    def test_a_way_typed_traffic_signals_is_not_emitted_as_a_line(self):
        only = os.path.join(self.dir, "way-signal.osm")
        with open(only, "w", encoding="utf-8") as fh:
            fh.write("""<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2860" lon="51.5310"/>
  <way id="50">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="traffic_signals"/>
  </way>
</osm>
""")
        main, sig = self._convert([only], signals_out=self.sig)
        self.assertEqual(sig["features"], [])
        self.assertEqual(main["features"], [])


if __name__ == "__main__":
    unittest.main()