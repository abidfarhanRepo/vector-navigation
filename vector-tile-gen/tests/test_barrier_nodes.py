"""Barrier nodes get from the OSM extract into their own artifact (V7.4 4A).

Before this a ``barrier=*`` node (a gate, a lift gate, a bollard) matched no
branch in ``_attrs``, returned empty props, and was dropped in phase 3 — so
the tag that says a gate is ``access=private`` or ``locked=yes`` disappeared
before the routing graph. The recon's measurement on real Qatar data: 8,715
barrier nodes, 8,573 of them on foot-routable ways, 2,164 of them gates
tagged private/no or locked. Vector walked people through locked gates.

These tests pin the new contract, mirroring the signal/camera contracts:

* a barrier node is ``kind=barrier`` and is written to the SEPARATE artifact
  named by ``--barriers-out``, never into the main collection;
* the main collection's feature counts are untouched by barrier ingestion;
* identity is the OSM node id (feature ``id`` = ``n<id>``), the same
  reference the routing wire reuses;
* ``pedestrian_effect`` is the ingestion decision ("block"/"pass") and the
  raw OSM provenance tags (barrier/access/foot/locked) ride along;
* a gate with no access tag is ``pass`` (OSM's permissive default — deleting
  every barrier node would sever 5,692 legitimate Qatar gates);
* a gate tagged ``access=private`` is ``block`` (the 2,164);
* without ``--barriers-out`` (the old standalone contract) barrier features
  merge into the main collection rather than being lost;
* a way wrongly typed ``barrier=gate`` is skipped, never emitted as a line.
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

# NOTE: this module loads osm_to_geojson.py standalone, so the degraded
# in-script barrier classifier is what these tests exercise — the same
# arrangement the car/foot fallbacks have always had. The authoritative
# classifier lives in vector_ingestion.classify and is tested there; the
# bootstrap bake runs with PYTHONPATH=vector-ingestion/src so the real one
# decides.

_TILE = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2850" lon="51.5310"/>
  <node id="3" lat="25.2860" lon="51.5305">
    <tag k="barrier" v="gate"/>
    <tag k="access" v="private"/>
  </node>
  <node id="4" lat="25.2865" lon="51.5315">
    <tag k="barrier" v="gate"/>
  </node>
  <node id="5" lat="25.2870" lon="51.5320">
    <tag k="barrier" v="bollard"/>
    <tag k="foot" v="yes"/>
  </node>
  <node id="9" lat="25.2872" lon="51.5322">
    <tag k="amenity" v="cafe"/>
    <tag k="name" v="Test Cafe"/>
  </node>
  <way id="100">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="residential"/>
    <tag k="name" v="Seam Way A"/>
  </way>
  <way id="101">
    <nd ref="1"/><nd ref="3"/><nd ref="2"/>
    <tag k="highway" v="footway"/>
  </way>
  <way id="102">
    <nd ref="7"/><nd ref="8"/>
    <tag k="barrier" v="wall"/>
  </way>
  <node id="7" lat="25.2880" lon="51.5330"/>
  <node id="8" lat="25.2880" lon="51.5340"/>
</osm>
"""


class BarrierNodesTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.src = os.path.join(self.dir, "a.osm")
        with open(self.src, "w", encoding="utf-8") as fh:
            fh.write(_TILE)
        self.out = os.path.join(self.dir, "out.geojson")
        self.bar = os.path.join(self.dir, "out_barriers.geojson")

    def _convert(self, paths, barriers_out=None):
        return o2g.convert(paths, self.out, barriers_out=barriers_out)

    def _main(self):
        with open(self.out, encoding="utf-8") as fh:
            return json.load(fh)

    def _barriers(self):
        with open(self.bar, encoding="utf-8") as fh:
            return json.load(fh)

    def test_barriers_go_to_the_separate_artifact_only(self):
        main, bar = self._convert([self.src], barriers_out=self.bar), self._barriers()
        feats = bar["features"]
        self.assertEqual(len(feats), 3, feats)
        # OSM identity, the same reference the routing wire reuses.
        self.assertEqual({f["id"] for f in feats}, {"n3", "n4", "n5"})
        for f in feats:
            self.assertEqual(f["properties"]["kind"], "barrier")
            self.assertEqual(f["geometry"]["type"], "Point")
        # The main collection is untouched by barrier ingestion.
        main_feats = self._main()["features"]
        self.assertTrue(all(f["properties"].get("kind") != "barrier" for f in main_feats))
        self.assertEqual(
            [f["properties"]["highway"] for f in main_feats
             if f["properties"].get("kind") == "road"],
            ["residential", "footway"])

    def test_effect_and_provenance_are_carried(self):
        self._convert([self.src], barriers_out=self.bar)
        by_id = {f["id"]: f["properties"] for f in self._barriers()["features"]}
        # The headline: a gate tagged access=private is blocked.
        self.assertEqual(by_id["n3"]["pedestrian_effect"], "block")
        self.assertEqual(by_id["n3"]["barrier"], "gate")
        self.assertEqual(by_id["n3"]["access"], "private")
        # OSM's permissive default: a gate with no access tag is passable.
        self.assertEqual(by_id["n4"]["pedestrian_effect"], "pass")
        # A bollard tagged foot=yes is passable, provenance intact.
        self.assertEqual(by_id["n5"]["pedestrian_effect"], "pass")
        self.assertEqual(by_id["n5"]["foot"], "yes")

    def test_a_barrier_way_is_never_emitted(self):
        """A wall line is a boundary, not a traversal point; no honest point exists."""
        self._convert([self.src], barriers_out=self.bar)
        main = self._main()["features"]
        bar = self._barriers()["features"]
        self.assertFalse(any(f["id"] == "w102" for f in main))
        self.assertFalse(any(f["id"] == "w102" for f in bar))

    def test_a_barrier_node_with_a_poi_role_stays_a_poi(self):
        """A node that is also a named place keeps that role (4 real Qatar nodes)."""
        src = os.path.join(self.dir, "poi.osm")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write("""<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300">
    <tag k="barrier" v="gate"/>
    <tag k="emergency" v="fire_hydrant"/>
  </node>
</osm>
""")
        self._convert([src], barriers_out=self.bar)
        main = self._main()["features"]
        self.assertTrue(any(f["id"] == "n1" and f["properties"]["kind"] == "poi"
                           for f in main))
        self.assertTrue(all(f["id"] != "n1" for f in self._barriers()["features"]))

    def test_without_barriers_out_the_features_are_not_lost(self):
        """The old standalone contract: merged, not dropped (see signals)."""
        n = self._convert([self.src])
        main = self._main()["features"]
        # The two road ways stayed roads and the barrier points are present
        # as points, not dropped into the void.
        barrier_points = [f for f in main if f["properties"].get("kind") == "barrier"]
        self.assertGreater(n, 0)
        self.assertEqual(len(barrier_points), 3)
        self.assertTrue(all(f["geometry"]["type"] == "Point" for f in barrier_points))

    def test_standalone_and_way_gate_nodes_both_count(self):
        """The junction coordinate and the mid-way coordinate are both facts."""
        self._convert([self.src], barriers_out=self.bar)
        coords = {tuple(f["geometry"]["coordinates"]) for f in self._barriers()["features"]}
        self.assertIn((51.5305, 25.2860), coords, "the gate node on the footway")
        self.assertIn((51.5315, 25.2865), coords)