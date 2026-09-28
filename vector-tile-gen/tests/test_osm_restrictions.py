"""Turn restrictions survive the OSM -> GeoJSON conversion.

The whole feature was inert for one reason: the converter emitted a restriction
keyed by OSM **node id**, and everything downstream keys on **coordinates**.
Nothing could make that join, so the restrictions were present in the file and
invisible to the router. These tests pin the coordinate resolution, and pin the
skips — a restriction that cannot be placed must be DROPPED and COUNTED, never
guessed, because a guessed junction bans a movement somewhere a mapper never
asked for.
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


# A + junction. Way 10 runs west->centre, way 20 centre->north, way 30
# centre->east. Node 3 is the shared junction.
NODES = {
    1: (51.5300, 25.2850),   # west
    2: (51.5310, 25.2850),
    3: (51.5320, 25.2850),   # centre / via
    4: (51.5320, 25.2860),   # north
    5: (51.5330, 25.2850),   # east
}
WAY_NDS = {10: [1, 2, 3], 20: [3, 4], 30: [3, 5]}


def rel(rid, kind, frm="10", to="20", via=("node", "3")):
    members = [{"type": "way", "ref": frm, "role": "from"},
               {"type": via[0], "ref": via[1], "role": "via"},
               {"type": "way", "ref": to, "role": "to"}]
    return (rid, {"type": "restriction", "restriction": kind}, members)


class ResolveRestrictionsTest(unittest.TestCase):
    def test_a_via_node_restriction_resolves_to_coordinates(self):
        out, skipped = o2g.resolve_restrictions([rel("1", "no_left_turn")], WAY_NDS, NODES)
        self.assertEqual(len(out), 1)
        r = out[0]
        self.assertEqual(r["restriction"], "no_left_turn")
        self.assertEqual(r["via"], [51.5320, 25.2850])
        # The from-way window ends at the via, so the approach is node 2.
        self.assertEqual(r["from_nodes"], [[51.5310, 25.2850], [51.5320, 25.2850]])
        # The to-way window starts at the via, so the exit is node 4.
        self.assertEqual(r["to_nodes"], [[51.5320, 25.2850], [51.5320, 25.2860]])
        self.assertEqual(skipped, {"via_way": 0, "unresolved": 0, "unknown_kind": 0})

    def test_the_window_is_only_the_via_and_its_neighbours(self):
        """A via-node restriction cannot use more of the way than this.

        Way 10 has three nodes; only the last two carry information.
        """
        out, _ = o2g.resolve_restrictions([rel("1", "no_left_turn")], WAY_NDS, NODES)
        self.assertEqual(len(out[0]["from_nodes"]), 2)

    def test_a_via_way_restriction_is_skipped_and_counted(self):
        r = rel("1", "no_left_turn", via=("way", "99"))
        out, skipped = o2g.resolve_restrictions([r], WAY_NDS, NODES)
        self.assertEqual(out, [])
        self.assertEqual(skipped["via_way"], 1)

    def test_an_unknown_node_is_dropped_not_guessed(self):
        r = rel("1", "no_left_turn", via=("node", "404"))
        out, skipped = o2g.resolve_restrictions([r], WAY_NDS, NODES)
        self.assertEqual(out, [])
        self.assertEqual(skipped["unresolved"], 1)

    def test_a_restriction_on_a_way_we_did_not_keep_is_dropped(self):
        out, skipped = o2g.resolve_restrictions([rel("1", "no_left_turn", frm="777")],
                                                WAY_NDS, NODES)
        self.assertEqual(out, [])
        self.assertEqual(skipped["unresolved"], 1)

    def test_modal_and_conditional_restrictions_are_not_applied_to_cars(self):
        """`restriction:hgv` carries no plain `restriction` tag, so it never
        reaches the car graph. Applying a lorry-only rule to a car would refuse
        a legal movement."""
        members = [{"type": "way", "ref": "10", "role": "from"},
                   {"type": "node", "ref": "3", "role": "via"},
                   {"type": "way", "ref": "20", "role": "to"}]
        rels = [("1", {"type": "restriction", "restriction:hgv": "no_left_turn"}, members)]
        out, _ = o2g.resolve_restrictions(rels, WAY_NDS, NODES)
        self.assertEqual(out, [])

    def test_an_unrecognised_kind_is_counted_separately(self):
        out, skipped = o2g.resolve_restrictions([rel("1", "no_hopping")], WAY_NDS, NODES)
        self.assertEqual(out, [])
        self.assertEqual(skipped["unknown_kind"], 1)

    def test_only_turns_survive_resolution(self):
        out, _ = o2g.resolve_restrictions([rel("1", "only_straight_on")], WAY_NDS, NODES)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["restriction"], "only_straight_on")


_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6">
  <node id="1" lat="25.2850" lon="51.5300"/>
  <node id="2" lat="25.2850" lon="51.5310"/>
  <node id="3" lat="25.2850" lon="51.5320"/>
  <node id="4" lat="25.2860" lon="51.5320"/>
  <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/></way>
  <way id="20"><nd ref="3"/><nd ref="4"/></way>
  <relation id="500">
    <member type="way" ref="10" role="from"/>
    <member type="node" ref="3" role="via"/>
    <member type="way" ref="20" role="to"/>
    <tag k="restriction" v="no_left_turn"/>
    <tag k="type" v="restriction"/>
  </relation>
</osm>
"""


class ScanRestrictionExtractTest(unittest.TestCase):
    """The second Overpass request is parsed on its own.

    It has to be self-contained: the public mirrors reject a query that asks for
    highways and relations together, so restrictions arrive in a separate file
    and must resolve without the main extract's node table.
    """

    def setUp(self):
        fd, self.path = tempfile.mkstemp(suffix=".osm")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(_OSM)

    def tearDown(self):
        os.unlink(self.path)

    def test_relations_ways_and_nodes_all_come_back(self):
        rels, ways, nodes = o2g.scan_restriction_extract(self.path)
        self.assertEqual(len(rels), 1)
        self.assertEqual(ways[10], [1, 2, 3])
        self.assertEqual(nodes[3], (51.5320, 25.2850))

    def test_the_file_alone_is_enough_to_resolve(self):
        rels, ways, nodes = o2g.scan_restriction_extract(self.path)
        out, skipped = o2g.resolve_restrictions(rels, ways, nodes)
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["via"], [51.5320, 25.2850])
        self.assertEqual(skipped["unresolved"], 0)


if __name__ == "__main__":
    unittest.main()


_TILE_A = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6">
  <node id="1" lat="25.2800" lon="51.5200"/>
  <node id="2" lat="25.2850" lon="51.5200"/>
  <node id="3" lat="25.2900" lon="51.5200"/>
  <node id="9" lat="25.2820" lon="51.5210"><tag k="amenity" v="cafe"/><tag k="name" v="Cafe A"/></node>
  <way id="100"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="name" v="Seam Road"/></way>
  <way id="101"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="name" v="Only In A"/></way>
</osm>
"""

# The SAME way 100 and the SAME POI node 9, because it straddles the seam and
# Overpass returns whole ways to every tile they intersect — plus one way only
# this tile sees.
_TILE_B = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6">
  <node id="1" lat="25.2800" lon="51.5200"/>
  <node id="2" lat="25.2850" lon="51.5200"/>
  <node id="3" lat="25.2900" lon="51.5200"/>
  <node id="4" lat="25.2950" lon="51.5200"/>
  <node id="9" lat="25.2820" lon="51.5210"><tag k="amenity" v="cafe"/><tag k="name" v="Cafe A"/></node>
  <way id="100"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="name" v="Seam Road"/></way>
  <way id="102"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/><tag k="name" v="Only In B"/></way>
</osm>
"""


class MergeMultipleExtractsTest(unittest.TestCase):
    """A tiled Overpass fetch is merged in the converter, not by an XML tool.

    The public mirrors will not serve Qatar in one query — measured 2026-09-08,
    the whole-region request was refused nine times running while the same query
    over one quadrant returned in 37 s — so the extract can be fetched as a grid
    and merged here, where the id-keyed tables make deduplication free.

    Tiles overlap at their seams and Overpass returns whole ways to every tile
    they intersect, so the same element arrives more than once. A duplicate that
    survived would become a duplicate FEATURE: drawn twice, and a second set of
    graph edges laid exactly on top of the first.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.a = os.path.join(self.dir, "a.osm")
        self.b = os.path.join(self.dir, "b.osm")
        self.out = os.path.join(self.dir, "out.geojson")
        with open(self.a, "w", encoding="utf-8") as fh:
            fh.write(_TILE_A)
        with open(self.b, "w", encoding="utf-8") as fh:
            fh.write(_TILE_B)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _convert(self, paths):
        o2g.convert(paths, self.out)
        with open(self.out, encoding="utf-8") as fh:
            return json.load(fh)

    def test_the_union_holds_every_way_from_both_tiles(self):
        ids = {f["id"] for f in self._convert([self.a, self.b])["features"]}
        self.assertIn("w100", ids)   # the seam way
        self.assertIn("w101", ids)   # only in A
        self.assertIn("w102", ids)   # only in B

    def test_a_way_on_the_seam_appears_exactly_once(self):
        feats = self._convert([self.a, self.b])["features"]
        self.assertEqual(sum(1 for f in feats if f["id"] == "w100"), 1)
        self.assertEqual(len(feats), len({f["id"] for f in feats}),
                         "the merged output contains duplicate feature ids")

    def test_a_poi_node_in_both_tiles_appears_exactly_once(self):
        feats = self._convert([self.a, self.b])["features"]
        self.assertEqual(sum(1 for f in feats if f["id"] == "n9"), 1)

    def test_the_seam_way_keeps_its_full_geometry(self):
        """Not a truncated copy from whichever tile happened to be second."""
        feats = self._convert([self.a, self.b])["features"]
        seam = next(f for f in feats if f["id"] == "w100")
        self.assertEqual(len(seam["geometry"]["coordinates"]), 3)

    def test_merge_order_does_not_change_the_result(self):
        ab = self._convert([self.a, self.b])
        ba = self._convert([self.b, self.a])
        self.assertEqual({f["id"] for f in ab["features"]},
                         {f["id"] for f in ba["features"]})

    def test_one_file_is_still_accepted_as_a_bare_string(self):
        """The single-extract path must not change shape for a list-taking API."""
        by_list = self._convert([self.a])
        o2g.convert(self.a, self.out)
        with open(self.out, encoding="utf-8") as fh:
            by_str = json.load(fh)
        self.assertEqual(by_list, by_str)

    def test_a_node_only_the_second_tile_has_is_usable(self):
        """Node 4 exists only in B; way 102 must resolve against the merged table."""
        feats = self._convert([self.a, self.b])["features"]
        only_b = next(f for f in feats if f["id"] == "w102")
        self.assertEqual(len(only_b["geometry"]["coordinates"]), 2)
