"""Building heights reach the tiles only when the source states one.

V7 3D's hard line is "no building height, no invented building height". These
tests pin all three halves of it, at the place the rule lives:

  * a height the source STATES, in the form OSM actually uses, becomes metres
    (including the `"40 m"` suffix form);
  * anything that is not a defensible number is REFUSED rather than guessed —
    feet, `"400+"`, junk, zero, negatives, and values past the rejection
    bound, which is a refusal and not a clamp (clamping would invent a height
    the source did not state);
  * a building with only `building:levels` is NOT extruded.

That last one is the deliberate refusal, and it is the reason this file exists.
Re-measured for V7.6 on the full Qatar source (Geofabrik gcc-states 260912,
189,866 building ways with a node inside the bbox): 975 state `height`, 7,018
state `building:levels`, and 6,499 state levels alone. Converting those needs a
metres-per-level constant and there is none to justify — on the 519
dual-tagged buildings the ratio runs min 2.50, p25 3.97, median 4.29,
p75 5.00, max 60.00, which is a distribution rather than a constant.

## What V7.6 changed, and what it deliberately did not

V7.6 made every footprint a FEATURE — the flat city fabric — so a building
with no stated height is now emitted where before it was dropped. That is a
change to what is DRAWN and not to what is CLAIMED: the refusal above is
unchanged and these tests still pin it. A fabric building carries no
`height_m` at all, so `buildings-3d` (which filters on `["has", "height_m"]`)
cannot reach it, and there is no value in the tile for anything to mistake
for a measurement.

The emitter is tested end to end too: a fixture OSM is converted and the
resulting features are checked, so the rule cannot be bypassed between the
parser and the tile.
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


class HeightParserTest(unittest.TestCase):
    """`_building_height_m`: the accepted grammar, and every refusal."""

    def test_a_height_in_metres_is_read_as_metres(self):
        self.assertEqual(o2g._building_height_m({"height": "40"}), 40.0)
        self.assertEqual(o2g._building_height_m({"height": "40.5"}), 40.5)
        # OSM allows the unit suffix, and the data uses it.
        self.assertEqual(o2g._building_height_m({"height": "40 m"}), 40.0)
        self.assertEqual(o2g._building_height_m({"height": "40M"}), 40.0)
        # Qatar's tallest stated height, kept exactly.
        self.assertEqual(o2g._building_height_m({"height": "318"}), 318.0)

    def test_no_height_tag_yields_no_height(self):
        self.assertIsNone(o2g._building_height_m({}))
        self.assertIsNone(o2g._building_height_m({"building": "yes"}))

    def test_levels_alone_never_become_a_height(self):
        # THE refusal. A number here would be an invented building height.
        self.assertIsNone(o2g._building_height_m({"building:levels": "11"}))
        self.assertIsNone(o2g._building_height_m(
            {"building": "apartments", "building:levels": "25"}))
        # And the attrs rule agrees, which is what actually gates the tile:
        # the footprint is emitted (V7.6 fabric) with NO height on it.
        levels_only = o2g._building_attrs(
            {"building": "apartments", "building:levels": "11"})
        self.assertEqual(levels_only["kind"], "building")
        self.assertNotIn("height_m", levels_only)
        self.assertNotIn("min_height_m", levels_only)
        # The levels ride along as provenance, verbatim and unconverted.
        self.assertEqual(levels_only["building_levels"], "11")

    def test_a_malformed_height_is_refused_rather_than_guessed(self):
        # `height=30'` is present TWICE in the real Qatar source.
        for bad in ("30'", "", "abc", "400+", "~40", "40-50", "NaN", "Infinity",
                    "forty", "40ft", "40 cm", "1e999"):
            self.assertIsNone(o2g._building_height_m({"height": bad}), bad)

    def test_an_impossible_height_is_refused_not_clamped(self):
        for bad in ("0", "-5", "0.0", "401", "4000"):
            self.assertIsNone(o2g._building_height_m({"height": bad}), bad)
        # The bound is inclusive; refusing is the honest answer past it.
        self.assertEqual(o2g._building_height_m({"height": "400"}), 400.0)

    def test_min_height_is_used_only_when_the_source_states_a_usable_one(self):
        # A podium building: drawn from its own base, not from the ground.
        podium = o2g._building_attrs({"building": "yes", "height": "100",
                                      "min_height": "20"})
        self.assertEqual(podium["min_height_m"], 20.0)
        # Absent means the ground — the honest default, not an invented base.
        self.assertNotIn("min_height_m", o2g._building_attrs(
            {"building": "yes", "height": "100"}))
        # Nonsense is dropped rather than carried.
        for bad in ("abc", "0", "100", "150", "-5"):
            self.assertNotIn("min_height_m", o2g._building_attrs(
                {"building": "yes", "height": "100", "min_height": bad}), bad)
        # A base with no top is not a volume: fabric never carries one.
        self.assertNotIn("min_height_m", o2g._building_attrs(
            {"building": "yes", "min_height": "20"}))


class BuildingAttrsTest(unittest.TestCase):
    """`_building_attrs`: every footprint is a feature, only a sourced
    height is a VOLUME."""

    def test_only_a_sourced_height_produces_an_extrudable_building(self):
        yes = o2g._building_attrs({"building": "yes", "height": "195",
                                   "building:levels": "51"})
        self.assertEqual(yes["kind"], "building")
        self.assertEqual(yes["height_m"], 195.0)
        # `building:levels` rides as provenance, and is NOT the height.
        self.assertEqual(yes["building_levels"], "51")
        self.assertNotIn("min_height_m", yes)

    def test_a_building_without_a_height_is_fabric_not_a_volume(self):
        # 96% of Qatar. V7.6: emitted as a flat block, never as a volume.
        for tags in ({"building": "yes"},
                     {"building": "yes", "building:levels": "4"},
                     {"building": "yes", "height": "0"},
                     {"building": "yes", "height": "30'"},
                     {"building": "house", "height": "abc"}):
            attrs = o2g._building_attrs(tags)
            self.assertEqual(attrs["kind"], "building", tags)
            self.assertNotIn("height_m", attrs, tags)

    def test_a_refused_height_falls_back_to_fabric_and_not_to_nothing(self):
        # The distinction that matters: refusing a MEASUREMENT is not the same
        # as refusing a BUILDING. `height=30'` is present twice in the real
        # Qatar source; those two buildings exist and should be drawn as
        # blocks, just not as volumes of an unknown height.
        attrs = o2g._building_attrs({"building": "yes", "height": "30'"})
        self.assertNotIn("height_m", attrs)
        self.assertEqual(attrs["kind"], "building")

    def test_the_extrusion_height_is_the_source_value_never_derived(self):
        # A regression guard, not a tautology: if anyone later wires a level
        # multiplier in, the stated 195 becomes 195 * k and this fails while
        # every other test still passes.
        for h in ("2", "9.5", "40", "318"):
            self.assertEqual(o2g._building_attrs({"height": h})["height_m"],
                             float(h))
        self.assertFalse(hasattr(o2g, "METRES_PER_LEVEL"))


_TILE = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2850" lon="51.5310"/>
  <node id="3" lat="25.2860" lon="51.5310"/>
  <node id="4" lat="25.2860" lon="51.5300"/>
  <node id="10" lat="25.2900" lon="51.5300"/>
  <node id="11" lat="25.2900" lon="51.5310"/>
  <node id="12" lat="25.2910" lon="51.5310"/>
  <node id="13" lat="25.2910" lon="51.5300"/>
  <node id="20" lat="25.2950" lon="51.5300"/>
  <node id="21" lat="25.2950" lon="51.5310"/>
  <node id="22" lat="25.2960" lon="51.5310"/>
  <node id="23" lat="25.2960" lon="51.5300"/>
  <way id="100">
    <nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/>
    <tag k="building" v="yes"/>
    <tag k="height" v="195"/>
    <tag k="building:levels" v="51"/>
  </way>
  <way id="101">
    <nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="13"/><nd ref="10"/>
    <tag k="building" v="apartments"/>
    <tag k="building:levels" v="8"/>
  </way>
  <way id="102">
    <nd ref="20"/><nd ref="21"/><nd ref="22"/><nd ref="23"/><nd ref="20"/>
    <tag k="building" v="yes"/>
    <tag k="height" v="30'"/>
  </way>
</osm>
"""


class BuildingEmissionTest(unittest.TestCase):
    """End to end: what the converter actually writes for those tags."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.src = os.path.join(self.dir, "b.osm")
        self.out = os.path.join(self.dir, "out.geojson")
        with open(self.src, "w", encoding="utf-8") as fh:
            fh.write(_TILE)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _convert(self):
        o2g.convert([self.src], self.out)
        with open(self.out, encoding="utf-8") as fh:
            return json.load(fh)

    def _buildings(self):
        return {f["id"]: f for f in self._convert()["features"]
                if f["properties"].get("kind") == "building"}

    def test_every_footprint_is_emitted_as_a_closed_polygon(self):
        # V7.6. All three ways are real buildings and all three are drawn;
        # before this milestone only way 100 survived and the other two were
        # dropped from the map entirely.
        b = self._buildings()
        self.assertEqual(set(b), {"b100", "b101", "b102"})
        for fid, f in b.items():
            self.assertEqual(f["geometry"]["type"], "Polygon", fid)
            ring = f["geometry"]["coordinates"][0]
            # A closed ring, or nothing draws.
            self.assertEqual(ring[0], ring[-1], fid)
            self.assertGreaterEqual(len(ring), 4, fid)

    def test_only_the_sourced_building_becomes_a_volume(self):
        b = self._buildings()
        # Way 100 states `height=195`: a volume.
        self.assertEqual(b["b100"]["properties"]["height_m"], 195.0)
        self.assertEqual(b["b100"]["properties"]["building_levels"], "51")
        # Way 101 states levels alone; way 102's height is `30'` (feet).
        # Both are fabric, and neither carries a height of any kind.
        self.assertNotIn("height_m", b["b101"]["properties"])
        self.assertNotIn("height_m", b["b102"]["properties"])
        # 101's levels ride along unconverted, as provenance.
        self.assertEqual(b["b101"]["properties"]["building_levels"], "8")

    def test_no_feature_carries_a_height_the_source_did_not_state(self):
        # The invariant that survives V7.6 unchanged: a `height_m` in the
        # output is always a number a human put in OSM. There is no default,
        # no derivation and no fallback that produces one.
        doc = self._convert()
        for f in doc["features"]:
            p = f["properties"]
            if p.get("kind") != "building" or "height_m" not in p:
                continue
            self.assertGreater(p["height_m"], 0)
            self.assertLessEqual(p["height_m"], 400.0)

    def test_an_open_way_tagged_building_is_not_closed_into_a_polygon(self):
        # Broken source geometry stays broken rather than being invented into
        # a ring — the geometry equivalent of the height refusal.
        src = os.path.join(self.dir, "open.osm")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(_TILE.replace(
                '<nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="13"/>'
                '<nd ref="10"/>',
                '<nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="13"/>'))
        out = os.path.join(self.dir, "open.geojson")
        o2g.convert([src], out)
        with open(out, encoding="utf-8") as fh:
            doc = json.load(fh)
        ids = {f["id"] for f in doc["features"]
               if f["properties"].get("kind") == "building"}
        self.assertNotIn("b101", ids)
        self.assertIn("b100", ids)


if __name__ == "__main__":
    unittest.main()
