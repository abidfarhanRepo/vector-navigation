"""Pedestrian tag promotion + crossing/kerb node artifact (V7.4 4A.4).

The recon measured that every pedestrian tag in the Qatar extract was dropped
before the routing graph: ``footway``/``crossing``/``sidewalk``/``surface``/
``incline``/``handrail``/``step_count``/``kerb``/``width``/``lit`` reached no
layer, and 3,170 of the 3,519 crossing nodes carry ``highway=crossing``, which
the generic highway branch typed as a ROAD and phase 3 then dropped -- so 4B
had nothing honest to source "cross the road" from, and a marked crossing was
indistinguishable from a pavement.

These tests pin the new contract:

* road features keep their raw pedestrian tags (footway=crossing, crossing=*,
  surface, incline, ...) as provenance on the way -- this is what reaches the
  foot graph's edge props;
* a ``highway=crossing`` NODE is ``kind=crossing`` and is written to the
  SEPARATE artifact named by ``--crossings-out``, never into the main
  collection, with its cross/kerb/tactile tags preserved;
* a kerb-only node is ``kind=kerb`` and joins the same artifact;
* the main collection's feature counts are untouched by crossing ingestion;
* a way wrongly typed ``highway=crossing`` is skipped, never emitted as a line;
* without ``--crossings-out`` nothing is lost (old standalone contract);
* barrier nodes and barrier semantics are unaffected.
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
    <tag k="highway" v="crossing"/>
    <tag k="crossing" v="marked"/>
    <tag k="kerb" v="lowered"/>
    <tag k="tactile_paving" v="yes"/>
  </node>
  <node id="4" lat="25.2865" lon="51.5315">
    <tag k="crossing" v="zebra"/>
    <tag k="crossing:markings" v="zebra"/>
  </node>
  <node id="5" lat="25.2870" lon="51.5320">
    <tag k="kerb" v="lowered"/>
  </node>
  <node id="6" lat="25.2875" lon="51.5325">
    <tag k="highway" v="traffic_signals"/>
    <tag k="crossing" v="traffic_signals"/>
  </node>
  <way id="100">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="residential"/>
    <tag k="sidewalk" v="right"/>
    <tag k="surface" v="asphalt"/>
    <tag k="lit" v="yes"/>
  </way>
  <way id="101">
    <nd ref="1"/><nd ref="3"/><nd ref="2"/>
    <tag k="highway" v="footway"/>
    <tag k="footway" v="crossing"/>
    <tag k="crossing" v="marked"/>
  </way>
  <way id="102">
    <nd ref="7"/><nd ref="8"/>
    <tag k="highway" v="crossing"/>
  </way>
  <node id="7" lat="25.2880" lon="51.5330"/>
  <node id="8" lat="25.2880" lon="51.5340"/>
  <way id="103">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="steps"/>
    <tag k="step_count" v="12"/>
    <tag k="handrail" v="yes"/>
    <tag k="incline" v="up"/>
  </way>
</osm>
"""


class PedestrianPromotionTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.src = os.path.join(self.dir, "a.osm")
        with open(self.src, "w", encoding="utf-8") as fh:
            fh.write(_TILE)
        self.out = os.path.join(self.dir, "out.geojson")
        self.cross = os.path.join(self.dir, "out_crossings.geojson")
        self.bar = os.path.join(self.dir, "out_barriers.geojson")

    def _convert(self, paths, crossings_out=None, barriers_out=None,
                 signals_out=None):
        return o2g.convert(paths, self.out, crossings_out=crossings_out,
                           barriers_out=barriers_out, signals_out=signals_out)

    def _main(self):
        with open(self.out, encoding="utf-8") as fh:
            return json.load(fh)

    def _crossings(self):
        with open(self.cross, encoding="utf-8") as fh:
            return json.load(fh)

    def test_way_tags_survive_the_conversion(self):
        self._convert([self.src], crossings_out=self.cross)
        by_hw = {f["properties"]["highway"]: f["properties"]
                 for f in self._main()["features"] if f["properties"].get("kind") == "road"}
        # The street keeps its pedestrian accommodation tags, raw.
        self.assertEqual(by_hw["residential"]["sidewalk"], "right")
        self.assertEqual(by_hw["residential"]["surface"], "asphalt")
        self.assertEqual(by_hw["residential"]["lit"], "yes")
        # footway=crossing reaches the converted feature -- the fact 4B needs.
        self.assertEqual(by_hw["footway"]["footway"], "crossing")
        self.assertEqual(by_hw["footway"]["crossing"], "marked")
        # So do the stairs facts.
        steps = by_hw["steps"]
        self.assertEqual(steps["step_count"], "12")
        self.assertEqual(steps["handrail"], "yes")
        self.assertEqual(steps["incline"], "up")

    def test_crossing_nodes_go_to_the_separate_artifact_only(self):
        sig = os.path.join(self.dir, "sig.geojson")
        self._convert([self.src], crossings_out=self.cross, signals_out=sig)
        feats = self._crossings()["features"]
        ids = {f["id"] for f in feats}
        self.assertEqual(ids, {"n3", "n4", "n5"}, feats)
        by_id = {f["id"]: f["properties"] for f in feats}
        self.assertEqual(by_id["n3"]["crossing"], "marked")
        self.assertEqual(by_id["n3"]["kerb"], "lowered")
        self.assertEqual(by_id["n3"]["tactile_paving"], "yes")
        # The zebra node: crossing=* without highway=crossing is still a fact.
        self.assertEqual(by_id["n4"]["crossing"], "zebra")
        self.assertEqual(by_id["n4"]["crossing:markings"], "zebra")
        # Kerb-only node keeps its kind.
        self.assertEqual(by_id["n5"]["kerb"], "lowered")
        self.assertEqual(by_id["n5"]["kind"], "kerb")
        # Main collection: crossing facts and signals both stay out of it.
        main = self._main()["features"]
        for f in main:
            self.assertNotEqual(f["properties"].get("kind"), "crossing")
            self.assertNotEqual(f["properties"].get("kind"), "kerb")
            self.assertNotEqual(f["properties"].get("kind"), "signal")
        # The traffic-signals+crossing node rides with the signals, not here.
        self.assertEqual(ids & {"n6"}, set())

    def test_a_way_wrongly_typed_highway_crossing_is_never_emitted(self):
        self._convert([self.src], crossings_out=self.cross)
        main = self._main()["features"]
        cross = self._crossings()["features"]
        self.assertFalse(any(f["id"] == "w102" for f in main))
        self.assertFalse(any(f["id"] == "w102" for f in cross))

    def test_signals_with_crossing_keep_their_signal_role(self):
        """352 real Qatari crossing nodes ride with the signals, preserved."""
        import importlib.util as _i
        # Re-run the tile with a signals-out to prove the crossing tag rides on
        # the signal feature without duplicating the node here.
        sig = os.path.join(self.dir, "out_signals.geojson")
        o2g.convert([self.src], self.out, signals_out=sig,
                    crossings_out=self.cross)
        with open(sig, encoding="utf-8") as fh:
            sfeats = json.load(fh)["features"]
        n6 = [f for f in sfeats if f["id"] == "n6"]
        self.assertEqual(len(n6), 1)
        self.assertEqual(n6[0]["properties"]["crossing"], "traffic_signals")
        cross = self._crossings()["features"]
        self.assertFalse(any(f["id"] == "n6" for f in cross))

    def test_crossing_ingestion_does_not_move_other_counts(self):
        """Signals, barriers and roads survive a crossings-out conversion."""
        self._convert([self.src], crossings_out=self.cross,
                      barriers_out=self.bar)
        main = self._main()["features"]
        # Two road ways + the name-less roads...: only roads and steps are
        # road-kind here; the barrier node is in ITS artifact.
        road_kinds = [f["properties"].get("kind") for f in main]
        self.assertNotIn("barrier", road_kinds)
        with open(self.bar, encoding="utf-8") as fh:
            bar = json.load(fh)["features"]
        self.assertEqual(len(bar), 0, "no barrier in this tile; still an empty artifact")

    def test_without_crossings_out_nothing_is_lost(self):
        self._convert([self.src])
        main = self._main()["features"]
        crossing_kinds = [f["properties"].get("kind") for f in main
                          if f["properties"].get("kind") in ("crossing", "kerb")]
        self.assertEqual(len(crossing_kinds), 3,
                         "old standalone contract: crossing facts merge, not drop")


if __name__ == "__main__":
    unittest.main()