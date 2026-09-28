"""Every tile geometry is clipped to the tile + CLIP_BUFFER, and the gate proves it.

THE BUG THIS PINS
-----------------
Features are selected into a tile by bbox intersection and used to be encoded
WHOLE, so a long way crossing a tile was written with vertices up to +/-208,166
tile units. MapLibre GL JS copes; MapLibre Native (the Android client) drops any
feature beyond int16 ("paths outside valid range of coordinate_type": 18,422
features in 9,520 z14/z15 tiles) and, overzoomed, draws phantom straight roads.

``encode.encode_tile`` now clips every geometry in every layer to the tile grown
by ``layers.CLIP_BUFFER`` (256) tile units before quantization, and
``validate_release.py`` refuses a release with any vertex outside
``[-256, extent + 256]`` — with a stdlib-only check, because the production gate
runs in a container with no MVT library.
"""

import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_SRC = os.path.join(os.path.dirname(_HERE), "src")
sys.path.insert(0, _SRC)

from vector_tile_gen.layers import CLIP_BUFFER, geometry_out_of_range  # noqa: E402

try:
    import mapbox_vector_tile
    from vector_tile_gen.encode import (
        clip_box, decode_tile, encode_tile, lonlat_to_local, tile_bbox)
    from vector_tile_gen.release import (
        build_manifest, format_release_id, scan_tree, write_digests,
        write_manifest)
    _HAVE_MVT = True
except Exception:  # pragma: no cover
    _HAVE_MVT = False

EXTENT = 4096
LO, HI = -CLIP_BUFFER, EXTENT + CLIP_BUFFER

# A z15 tile in central Doha (the C Ring junction area).
Z, X, Y = 15, 21073, 14006


def _load_gate():
    path = os.path.normpath(os.path.join(_HERE, "..", "scripts", "validate_release.py"))
    spec = importlib.util.spec_from_file_location("validate_release_clip", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _fd(gtype, coords, fid="w1", props=None, z=Z, x=X, y=Y):
    return {"id": fid, "geometry_type": gtype, "coordinates": coords,
            "properties": dict(props or {"kind": "road"}),
            "_z": z, "_x": x, "_y": y}


def _box():
    return tile_bbox(Z, X, Y)


def _at(fx, fy):
    """lon/lat at fraction (fx, fy) of the tile, fy measured from the TOP.

    Linear in lat, which is the frame mapbox-vector-tile quantizes in.
    """
    minx, miny, maxx, maxy = _box()
    return [minx + fx * (maxx - minx), maxy - fy * (maxy - miny)]


def _all_vertices(dec):
    for layer in dec["layers"]:
        for f in layer["features"]:
            for ring in f["geometry"]:
                for x, y in ring:
                    yield layer["name"], x, y


def _unclipped(layers):
    """What the bake produced before clipping existed."""
    return encode_tile(layers, clip_buffer=10 ** 9)


@unittest.skipUnless(_HAVE_MVT, "mapbox-vector-tile not installed")
class EncodeClipTest(unittest.TestCase):
    def test_the_buffer_is_256_and_the_clip_box_is_linear_in_lonlat(self):
        self.assertEqual(CLIP_BUFFER, 256)
        minx, miny, maxx, maxy = _box()
        bx = clip_box(_box())
        self.assertAlmostEqual(minx - bx[0], (maxx - minx) * 256 / 4096)
        self.assertAlmostEqual(bx[3] - maxy, (maxy - miny) * 256 / 4096)

    def test_a_long_line_crossing_the_tile_stays_within_the_buffer(self):
        # 40 tiles either side: the shape of a motorway through a z15 tile.
        line = [_at(-40.0, 0.3), _at(0.5, 0.5), _at(41.0, 0.7)]
        before = decode_tile(_unclipped([("basemap", [_fd("LineString", line)])]))
        self.assertTrue(any(x < LO or x > HI for _, x, _ in _all_vertices(before)),
                        "fixture must reproduce the unclipped bug")
        dec = decode_tile(encode_tile([("basemap", [_fd("LineString", line)])]))
        verts = list(_all_vertices(dec))
        self.assertTrue(verts)
        for _, x, y in verts:
            self.assertGreaterEqual(x, LO)
            self.assertLessEqual(x, HI)
            self.assertGreaterEqual(y, LO)
            self.assertLessEqual(y, HI)
        # The clip lands exactly on the buffer edge, and the interior vertex
        # survives.
        xs = sorted(x for _, x, _ in verts)
        self.assertEqual(xs[0], LO)
        self.assertEqual(xs[-1], HI)
        mid = lonlat_to_local(*_at(0.5, 0.5), Z, X, Y)
        self.assertTrue(any(abs(x - mid[0]) <= 1 and abs(y - mid[1]) <= 2
                            for _, x, y in verts))

    def test_a_line_leaving_and_reentering_becomes_a_multilinestring(self):
        line = [_at(0.2, 0.2), _at(0.2, -5.0), _at(0.8, -5.0), _at(0.8, 0.2)]
        dec = decode_tile(encode_tile([("basemap", [_fd("LineString", line)])]))
        f = dec["layers"][0]["features"][0]
        self.assertEqual(f["type"], 2)
        self.assertEqual(len(f["geometry"]), 2)
        for ring in f["geometry"]:
            for x, y in ring:
                self.assertTrue(LO <= x <= HI and LO <= y <= HI)

    def test_a_polygon_larger_than_the_tile_is_clipped_to_the_buffer_square(self):
        ring = [_at(-3, -3), _at(4, -3), _at(4, 4), _at(-3, 4), _at(-3, -3)]
        dec = decode_tile(encode_tile(
            [("basemap", [_fd("Polygon", [ring], props={"kind": "park"})])]))
        f = dec["layers"][0]["features"][0]
        self.assertEqual(f["type"], 3)
        pts = {tuple(p) for p in f["geometry"][0]}
        self.assertEqual(pts, {(LO, LO), (HI, LO), (HI, HI), (LO, HI)})

    def test_a_clipped_polygon_keeps_mvt_winding_and_validity(self):
        # A polygon much larger than the tile, with a hole inside the tile:
        # the clipped exterior and the untouched interior ring must still form
        # one valid polygon, oriented as MVT requires.
        outer = [_at(-2, -2), _at(3, -2), _at(3, 3), _at(-2, 3), _at(-2, -2)]
        hole = [_at(0.4, 0.4), _at(0.4, 0.6), _at(0.6, 0.6), _at(0.6, 0.4), _at(0.4, 0.4)]
        data = encode_tile([("basemap", [_fd("Polygon", [outer, hole],
                                              props={"kind": "park"})])])
        raw = mapbox_vector_tile.decode(data)
        geom = raw["basemap"]["features"][0]["geometry"]
        self.assertEqual(geom["type"], "Polygon")
        self.assertEqual(len(geom["coordinates"]), 2)
        from shapely.geometry import LinearRing, shape
        self.assertTrue(shape(geom).is_valid)
        ext, hole_ring = (LinearRing(r) for r in geom["coordinates"])
        self.assertNotEqual(ext.is_ccw, hole_ring.is_ccw)

    def test_a_polygon_cut_into_two_pieces_becomes_a_multipolygon(self):
        # A U whose base lies outside the tile: inside the box only the two
        # arms remain.
        u = [_at(0.1, 0.1), _at(0.3, 0.1), _at(0.3, 3.0), _at(0.7, 3.0),
             _at(0.7, 0.1), _at(0.9, 0.1), _at(0.9, 4.0), _at(0.1, 4.0),
             _at(0.1, 0.1)]
        data = encode_tile([("basemap", [_fd("Polygon", [u], props={"kind": "park"})])])
        raw = mapbox_vector_tile.decode(data)
        geom = raw["basemap"]["features"][0]["geometry"]
        self.assertEqual(geom["type"], "MultiPolygon")
        self.assertEqual(len(geom["coordinates"]), 2)

    def test_a_feature_entirely_outside_the_buffered_tile_is_dropped(self):
        # Its bbox meets the tile (the bake's selection rule would take it),
        # but the line itself only cuts past the NW corner, ~1,450 units out.
        corner_miss = [_at(-1.0, 0.5), _at(0.5, -1.0)]
        far = [_at(3.0, 3.0), _at(5.0, 5.0)]
        keep = [_at(0.2, 0.2), _at(0.8, 0.8)]
        feats = [_fd("LineString", corner_miss, fid="w1"),
                 _fd("LineString", far, fid="w2"),
                 _fd("LineString", keep, fid="w3"),
                 _fd("Point", _at(2.0, 0.5), fid="n4", props={"kind": "poi"})]
        dec = decode_tile(encode_tile([("basemap", feats)]))
        self.assertEqual(len(dec["layers"][0]["features"]), 1)

    def test_a_tile_whose_every_feature_is_outside_encodes_to_nothing(self):
        data = encode_tile([("basemap", [_fd("LineString", [_at(3, 3), _at(5, 5)])])])
        self.assertEqual(data, b"")

    def test_a_point_inside_is_unchanged(self):
        p = _at(0.25, 0.75)
        a = encode_tile([("basemap", [_fd("Point", p, props={"kind": "poi"})])])
        self.assertEqual(a, _unclipped([("basemap", [_fd("Point", p, props={"kind": "poi"})])]))
        f = decode_tile(a)["layers"][0]["features"][0]
        self.assertEqual(f["type"], 1)
        lx, ly = f["geometry"][0][0]
        ex, ey = lonlat_to_local(*p, Z, X, Y)
        self.assertLessEqual(abs(lx - ex), 1)

    def test_a_point_in_the_buffer_is_kept(self):
        p = _at(1.03, 0.5)  # 123 units past the east edge, inside the 256 buffer
        dec = decode_tile(encode_tile([("basemap", [_fd("Point", p, props={"kind": "poi"})])]))
        self.assertEqual(len(dec["layers"][0]["features"]), 1)

    def test_features_fully_inside_encode_to_identical_bytes(self):
        feats = [
            _fd("LineString", [_at(0.1, 0.1), _at(0.5, 0.4), _at(0.9, 0.2)], fid="w1"),
            _fd("Polygon", [[_at(0.2, 0.2), _at(0.4, 0.2), _at(0.4, 0.4),
                             _at(0.2, 0.4), _at(0.2, 0.2)]], fid="w2",
                props={"kind": "building", "height_m": 12.0}),
            _fd("Point", _at(0.6, 0.6), fid="n3", props={"kind": "poi", "name": "x"}),
            # In the buffer, not the tile: still nothing to clip.
            _fd("LineString", [_at(0.5, 0.5), _at(1.05, 0.5)], fid="w4"),
        ]
        lanes = [_fd("LineString", [_at(0.1, 0.5), _at(0.9, 0.5)], fid=None,
                     props={"lanes": 3})]
        self.assertEqual(encode_tile([("basemap", feats), ("lanes", lanes)]),
                         _unclipped([("basemap", feats), ("lanes", lanes)]))

    def test_both_basemap_and_lanes_layers_are_clipped(self):
        long_line = [_at(-30, 0.5), _at(30, 0.5)]
        data = encode_tile([
            ("basemap", [_fd("LineString", long_line, fid="w9")]),
            ("lanes", [_fd("LineString", long_line, fid=None,
                           props={"lanes": 2, "kind": "lane"})]),
        ])
        dec = decode_tile(data)
        self.assertEqual([lay["name"] for lay in dec["layers"]], ["basemap", "lanes"])
        for name, x, y in _all_vertices(dec):
            self.assertTrue(LO <= x <= HI and LO <= y <= HI, (name, x, y))
        self.assertEqual(geometry_out_of_range(data), [])
        self.assertTrue(geometry_out_of_range(_unclipped([
            ("basemap", [_fd("LineString", long_line, fid="w9")]),
            ("lanes", [_fd("LineString", long_line, fid=None, props={"lanes": 2})]),
        ])))

    def test_ids_and_properties_survive_clipping(self):
        props = {"kind": "road", "name": "C Ring Road", "lanes": 4, "oneway": True}
        data = encode_tile([("basemap", [
            _fd("LineString", [_at(-9, 0.5), _at(9, 0.5)], fid=123456, props=props),
            _fd("Polygon", [[_at(-2, -2), _at(3, -2), _at(3, 3), _at(-2, 3), _at(-2, -2)]],
                fid="w777", props={"kind": "park", "name": "Aspire"}),
        ])])
        raw = mapbox_vector_tile.decode(data)["basemap"]["features"]
        self.assertEqual(raw[0]["id"], 123456)
        self.assertEqual(raw[0]["properties"], props)
        from vector_tile_gen.encode import _fnv1a_64
        self.assertEqual(raw[1]["id"], _fnv1a_64("w777"))
        self.assertEqual(raw[1]["properties"], {"kind": "park", "name": "Aspire"})

    def test_the_input_feature_dicts_are_not_mutated(self):
        line = [_at(-9, 0.5), _at(9, 0.5)]
        fd = _fd("LineString", [list(p) for p in line])
        encode_tile([("basemap", [fd])])
        self.assertEqual(fd["coordinates"], line)
        self.assertEqual(fd["geometry_type"], "LineString")


@unittest.skipUnless(_HAVE_MVT, "mapbox-vector-tile not installed")
class StdlibRangeCheckTest(unittest.TestCase):
    """layers.geometry_out_of_range agrees with the real decoder."""

    def test_reports_layer_count_and_worst_value(self):
        data = _unclipped([
            ("basemap", [_fd("LineString", [_at(0.5, 0.5), _at(12, 0.5)], fid="w1"),
                         _fd("LineString", [_at(0.1, 0.1), _at(0.2, 0.2)], fid="w2"),
                         _fd("LineString", [_at(0.5, 0.5), _at(-3, 0.5)], fid="w3")]),
        ])
        dec_max = max(x for _, x, _ in _all_vertices(decode_tile(data)))
        out = geometry_out_of_range(data)
        self.assertEqual(len(out), 1)
        name, nbad, worst = out[0]
        self.assertEqual((name, nbad), ("basemap", 2))
        self.assertEqual(worst, dec_max)

    def test_a_vertex_exactly_on_the_buffer_edge_passes(self):
        data = encode_tile([("basemap", [_fd("LineString", [_at(-5, 0.5), _at(5, 0.5)])])])
        self.assertEqual(geometry_out_of_range(data), [])
        self.assertTrue(geometry_out_of_range(data, buffer=CLIP_BUFFER - 1))

    def test_the_gate_path_imports_no_third_party_library(self):
        # The production gate runs in bare python:3.11-slim. Importing the
        # gate must not pull mapbox_vector_tile or shapely in, even when they
        # are installed here.
        code = (
            "import sys, importlib.util\n"
            f"sys.path.insert(0, {_SRC!r})\n"
            "import vector_tile_gen.layers, vector_tile_gen.release\n"
            f"spec = importlib.util.spec_from_file_location('g', {os.path.join(os.path.dirname(_HERE), 'scripts', 'validate_release.py')!r})\n"
            "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
            "bad = [n for n in ('mapbox_vector_tile', 'shapely', 'google.protobuf') if n in sys.modules]\n"
            "print(bad)\n"
            "sys.exit(1 if bad else 0)\n")
        r = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


@unittest.skipUnless(_HAVE_MVT, "mapbox-vector-tile not installed")
class GateRefusesUnclippedReleaseTest(unittest.TestCase):
    T0 = 1_789_819_647.0
    SHA = "8f4f2b4"

    def setUp(self):
        self.gate = _load_gate()
        self.parent = tempfile.mkdtemp(prefix="v8-clip-gate-")
        self.addCleanup(shutil.rmtree, self.parent, ignore_errors=True)

    def _stage(self, *, clip):
        rid = format_release_id(now_s=self.T0, git_sha=self.SHA, dirty=False)
        root = os.path.join(self.parent, rid)
        tiles = {
            (Z, X, Y): [("basemap", [_fd("LineString", [_at(-20, 0.4), _at(20, 0.6)],
                                         fid="w1"),
                                     _fd("Point", _at(0.5, 0.5), fid="n1",
                                         props={"kind": "poi"})]),
                        ("lanes", [_fd("LineString", [_at(-20, 0.4), _at(20, 0.6)],
                                       fid=None, props={"lanes": 2})])],
            (Z, X + 1, Y): [("basemap", [_fd("LineString", [_at(1.2, 0.2), _at(1.8, 0.8)],
                                             fid="w2", x=X + 1)])],
        }
        for (z, x, y), layers in tiles.items():
            layers = [(n, [dict(f, _x=x) for f in fs]) for n, fs in layers]
            data = encode_tile(layers) if clip else _unclipped(layers)
            d = os.path.join(root, str(z), str(x))
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, f"{y}.mvt"), "wb") as fh:
                fh.write(data)
        sc = scan_tree(root, census=True)
        m = build_manifest(
            root, release_id=rid,
            source={"kind": "osm-pbf", "extract_name": "qatar.osm", "region": "qatar"},
            generator={"repo_sha": self.SHA, "repo_dirty": False},
            input_config={"zooms": [Z]}, scan=sc)
        write_digests(root, sc["entries"])
        write_manifest(root, m)
        return root

    def _geo_failure(self, report):
        return [f for f in report["failures"] if f["code"] == "geometry_out_of_range"]

    def test_an_unclipped_release_fails_the_gate(self):
        root = self._stage(clip=False)
        r = self.gate.validate_release(root, require_publishable=True)
        self.assertFalse(r["ok"])
        fails = self._geo_failure(r)
        self.assertEqual(len(fails), 1, r["failures"])
        geo = r["observed"]["geometry_range"]
        self.assertEqual(geo["tiles_out_of_range"], 1)
        self.assertEqual(geo["features_out_of_range"], 2)
        self.assertEqual(geo["features_out_of_range_by_layer"],
                         {"basemap": 1, "lanes": 1})
        self.assertEqual(geo["worst"]["tile"], f"{Z}/{X}/{Y}.mvt")
        self.assertGreaterEqual(abs(geo["worst"]["value"]), 20 * 4096)
        self.assertIn(f"{Z}/{X}/{Y}.mvt", fails[0]["detail"])

    def test_the_check_runs_without_the_census_too(self):
        """The production host's gate runs --no-census (no MVT library)."""
        root = self._stage(clip=False)
        r = self.gate.validate_release(root, census=False)
        self.assertEqual(len(self._geo_failure(r)), 1, r["failures"])

    def test_the_cli_exits_1_on_an_unclipped_release(self):
        root = self._stage(clip=False)
        rc = self.gate.main(["--tiles", root, "--quiet", "--require-publishable"])
        self.assertEqual(rc, 1)

    def test_a_clipped_release_passes(self):
        root = self._stage(clip=True)
        r = self.gate.validate_release(root, require_publishable=True)
        self.assertTrue(r["ok"], r["failures"])
        geo = r["observed"]["geometry_range"]
        self.assertTrue(geo["checked"])
        self.assertEqual(geo["features_out_of_range"], 0)
        self.assertIsNone(geo["worst"])


if __name__ == "__main__":
    unittest.main()
