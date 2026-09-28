"""`natural=coastline` must survive the converter.

Qatar is a peninsula. Without a shoreline the country view is roads floating in
a void, because sea and land are both just the style's background colour.

The defect was a SECOND CLASSIFIER. `vector_ingestion.osm._basemap_kind` knows
about coastline and documents at length why it must be handled before the
generic area mapping — but `bootstrap.sh` runs `osm_to_geojson.py`, whose
`_attrs` is an independent implementation that never got the branch. So
`_attrs({"natural": "coastline"}) == {}`, phase 2 skipped the way on `if not
props`, and 478 Qatar coastline ways fetched by their own dedicated Overpass
request (step 1c) were discarded without a word.

Everything downstream was already waiting: `build_qatar_tiles` ranks
`coastline` with water (`_KIND_ORDER`) and reserves 6% of every tile for it
(`_KIND_FLOOR`), and the Android and web styles both carry a `line` layer
filtering this exact kind. The converter was the only missing link.
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
sys.path.insert(0, os.path.join(_ROOT, "src"))
sys.path.insert(0, os.path.join(_ROOT, "scripts"))

_SCRIPT = os.path.join(_ROOT, "scripts", "osm_to_geojson.py")
_spec = importlib.util.spec_from_file_location("osm_to_geojson_coast", _SCRIPT)
o2g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(o2g)

import build_qatar_tiles as bake  # noqa: E402


def _convert(xml):
    with tempfile.TemporaryDirectory() as d:
        src, dst = os.path.join(d, "in.osm"), os.path.join(d, "out.geojson")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(xml)
        o2g.main([src, "-o", dst])
        with open(dst, encoding="utf-8") as fh:
            return json.load(fh)["features"]


def _way(wid, tags, coords):
    nodes = "".join(f'<node id="{wid*100+i}" lat="{y}" lon="{x}"/>'
                    for i, (x, y) in enumerate(coords))
    nds = "".join(f'<nd ref="{wid*100+i}"/>' for i in range(len(coords)))
    tagxml = "".join(f'<tag k="{k}" v="{v}"/>' for k, v in tags.items())
    return (f'<?xml version="1.0"?><osm version="0.6">{nodes}'
            f'<way id="{wid}">{nds}{tagxml}</way></osm>')


_OPEN = [(51.50, 25.30), (51.51, 25.31), (51.52, 25.30)]


class AttributeTest(unittest.TestCase):
    def test_coastline_keeps_the_kind_the_classifier_downstream_filters_on(self):
        # THE DEFECT: this returned {} and the way was dropped on `if not props`.
        self.assertEqual(o2g._attrs({"natural": "coastline"}),
                         {"kind": "coastline"})

    def test_a_named_coastline_keeps_its_names(self):
        got = o2g._attrs({"natural": "coastline", "name": "خليج الدوحة",
                          "name:en": "Doha Bay"})
        self.assertEqual(got["kind"], "coastline")
        self.assertEqual(got["name:en"], "Doha Bay")

    def test_coastline_is_decided_before_water(self):
        # A coastline way carrying a waterway tag must still be a coastline:
        # the water branch closes rings, and a closed coastline is a spurious
        # fill spanning its two loose ends.
        self.assertEqual(o2g._attrs({"natural": "coastline",
                                     "waterway": "riverbank"})["kind"],
                         "coastline")


class ConversionTest(unittest.TestCase):
    def test_a_coastline_way_becomes_a_feature(self):
        feats = _convert(_way(1, {"natural": "coastline"}, _OPEN))
        coast = [f for f in feats if f["properties"].get("kind") == "coastline"]
        self.assertEqual(len(coast), 1, "the coastline way was discarded")

    def test_a_coastline_is_a_linestring(self):
        feats = _convert(_way(2, {"natural": "coastline"}, _OPEN))
        coast = [f for f in feats if f["properties"].get("kind") == "coastline"][0]
        self.assertEqual(coast["geometry"]["type"], "LineString")
        self.assertEqual(len(coast["geometry"]["coordinates"]), 3)

    def test_a_closed_coastline_is_still_a_line_not_a_polygon(self):
        # An island is a closed way. It must not become a filled polygon: the
        # style draws coastline as a `line` layer, and land/sea fill is not
        # what this kind is for.
        ring = _OPEN + [_OPEN[0]]
        feats = _convert(_way(3, {"natural": "coastline"}, ring))
        coast = [f for f in feats if f["properties"].get("kind") == "coastline"][0]
        self.assertEqual(coast["geometry"]["type"], "LineString")

    def test_a_coastline_is_not_a_poi(self):
        # It is basemap geometry, not a destination; a Point here would put a
        # label and a dot on the shoreline and put it in search results.
        feats = _convert(_way(4, {"natural": "coastline"}, _OPEN))
        self.assertEqual([f for f in feats
                          if f["properties"].get("kind") == "poi"], [])


class UnchangedBehaviourTest(unittest.TestCase):
    """The neighbouring branches must be exactly as they were."""

    def test_water_is_still_water(self):
        ring = _OPEN + [_OPEN[0]]
        feats = _convert(_way(5, {"natural": "water", "name": "Lake"}, ring))
        f = [x for x in feats if x["properties"].get("kind") == "water"][0]
        self.assertEqual(f["geometry"]["type"], "Polygon")

    def test_a_river_is_still_water(self):
        feats = _convert(_way(6, {"waterway": "river"}, _OPEN))
        self.assertEqual(feats[0]["properties"]["kind"], "water")

    def test_a_highway_is_still_a_road(self):
        feats = _convert(_way(7, {"highway": "primary", "name": "Al Adl"}, _OPEN))
        f = feats[0]
        self.assertEqual(f["properties"]["kind"], "road")
        self.assertEqual(f["geometry"]["type"], "LineString")

    def test_landuse_is_still_park(self):
        ring = _OPEN + [_OPEN[0]]
        feats = _convert(_way(8, {"landuse": "grass"}, ring))
        self.assertEqual(feats[0]["properties"]["kind"], "park")

    def test_a_poi_way_is_still_a_centroid_point(self):
        ring = _OPEN + [_OPEN[0]]
        feats = _convert(_way(9, {"shop": "mall", "name": "Mall"}, ring))
        f = [x for x in feats if x["properties"].get("kind") == "poi"][0]
        self.assertEqual(f["geometry"]["type"], "Point")

    def test_a_poi_node_is_still_a_poi(self):
        xml = ('<?xml version="1.0"?><osm version="0.6">'
               '<node id="900" lat="25.3" lon="51.5">'
               '<tag k="amenity" v="cafe"/><tag k="name" v="Cafe"/>'
               '</node></osm>')
        feats = _convert(xml)
        self.assertEqual(feats[0]["properties"]["poi_class"], "cafe")


class TilePathTest(unittest.TestCase):
    """The kind the converter now emits is the kind the bake already expects."""

    class _F:
        def __init__(self, props, bbox=None):
            self.properties = props
            self.bbox = bbox

    def test_coastline_is_visible_at_every_zoom(self):
        for z in (6, 10, 14, 15):
            self.assertTrue(bake.visible_at_zoom(self._F({"kind": "coastline"}), z))

    def test_coastline_ranks_with_water(self):
        # It IS the water edge. Ranked below it, the shoreline is the first
        # thing a dense tile truncates away.
        coast = bake.feature_rank(self._F({"kind": "coastline"}))
        water = bake.feature_rank(self._F({"kind": "water"}))
        park = bake.feature_rank(self._F({"kind": "park"}))
        self.assertEqual(coast[0], water[0])
        self.assertLess(coast[0], park[0])

    def test_coastline_has_a_reserved_share_of_every_tile(self):
        self.assertIn("coastline", bake.kind_floors(15))
        self.assertGreater(bake.kind_floors(15)["coastline"], 0)


if __name__ == "__main__":
    unittest.main()
