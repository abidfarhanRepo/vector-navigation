"""A tile that clipping leaves empty is not written (the bake never writes an empty tile).

Features are binned into tiles by their BBOX. An L-shaped way's bbox covers a
corner its line never goes near; before clipping that tile carried the whole way
as invisible bytes, after clipping nothing is left, and `validate_release`'s
empty-tile alarm (0 empty tiles in every real release) must not start firing:
4,098 zero-byte tiles appeared in the first clipped Qatar bake, 22% of the tree.
"""
import json
import math
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(_HERE), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(_HERE), "scripts"))

LON0, LAT0 = 51.50, 25.30
KY = 6378137.0 * math.pi / 180.0
KX = KY * math.cos(math.radians(LAT0))


def ll(x, y):
    return [LON0 + x / KX, LAT0 + y / KY]


class ClipEmptyTilesAreNotWritten(unittest.TestCase):

    def test_an_l_shaped_way_writes_only_the_tiles_its_line_reaches(self):
        import build_qatar_tiles as bake
        from vector_tile_gen.tiles import lonlat_to_tile
        road = {"type": "Feature", "id": "w1",
                "geometry": {"type": "LineString",
                             "coordinates": [ll(0, 0), ll(4000, 0), ll(4000, 4000)]},
                "properties": {"kind": "road", "car": True, "highway": "primary", "lanes": "2"}}
        with tempfile.TemporaryDirectory() as tmp:
            gj = os.path.join(tmp, "in.geojson")
            with open(gj, "w") as fh:
                json.dump({"type": "FeatureCollection", "features": [road]}, fh)
            out = os.path.join(tmp, "out")
            self.assertEqual(bake.main(["--geojson", gj, "--out", out, "--zooms", "15",
                                        "--no-version-bump"]), 0)
            root = os.path.join(out, "tiles", "15")

            def exists(x_m, y_m):
                x, y = lonlat_to_tile(15, *ll(x_m, y_m))
                return os.path.exists(os.path.join(root, str(x), f"{y}.mvt"))

            # on the line: both legs are drawn
            self.assertTrue(exists(1000, 0))
            self.assertTrue(exists(4000, 2000))
            # the far corner of the bbox, ~2.8 km from the line: not written
            self.assertFalse(exists(300, 3700))
            # and nothing anywhere in the tree is a zero-byte tile
            sizes = [os.path.getsize(os.path.join(d, f))
                     for d, _, fs in os.walk(root) for f in fs if f.endswith(".mvt")]
            self.assertTrue(sizes)
            self.assertEqual([s for s in sizes if s == 0], [])


class LaneMarkingsFollowTheClippedRoad(unittest.TestCase):
    """Regression: 15/21018/14046 in the first clipped lanes bake. The road's
    centreline lay just beyond the buffer, so encode_tile dropped it, but its
    offset lane run still reached the tile: the tile shipped a `lanes` layer
    and no `basemap`, and the verifier rejected it."""

    def test_a_road_beyond_the_buffer_is_not_kept_for_its_markings(self):
        import build_qatar_tiles as bake
        from vector_tile_gen.encode import tile_bbox
        from vector_tile_gen.tiles import lonlat_to_tile
        x, y = lonlat_to_tile(15, *ll(0, 0))
        w, s, e, n = tile_bbox(15, x, y)
        span = e - w
        def road(fid, lon):
            return {"id": fid, "geometry_type": "LineString",
                    "coordinates": [[lon, s + (n - s) * 0.2], [lon, s + (n - s) * 0.8]],
                    "properties": {"kind": "road", "car": True, "highway": "primary"}}
        inside = road("w1", w + span * 0.5)
        in_buffer = road("w2", e + span * 100 / 4096)       # 100 units past the edge
        beyond = road("w3", e + span * 300 / 4096)          # 300 units: clipped away
        not_road = dict(road("w4", w + span * 0.5), properties={"kind": "park"})
        kept = bake.roads_kept_after_clip([inside, in_buffer, beyond, not_road], 15, x, y)
        self.assertEqual(kept, {"w1", "w2"})


if __name__ == "__main__":
    unittest.main()
