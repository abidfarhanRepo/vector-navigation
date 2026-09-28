"""The shared layer rule (V8): basemap, optionally + lanes, nothing else.

Every combination is pushed through EVERY gate that decides whether a tile may
ship — the in-bake verifier, the standalone tile validator, and the release
census/verify pair — because the point of a shared rule is that no gate can
be the odd one out.

Duplicate layers are built by concatenating two encoded tiles. Tile.layers is
a repeated field, so that is a well-formed protobuf with two layers of the same
name — exactly the case a name-keyed decoder cannot see.
"""
import importlib.util
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "src"))

from vector_tile_gen.encode import encode_tile  # noqa: E402
from vector_tile_gen.layers import (  # noqa: E402
    check_layer_names, raw_layer_names, raw_layers)
from vector_tile_gen.learned_layer import verify_tile_bytes  # noqa: E402
from vector_tile_gen.release import (  # noqa: E402
    build_manifest, scan_tree, verify_manifest, write_manifest)

_spec = importlib.util.spec_from_file_location(
    "validate_tiles", os.path.join(os.path.dirname(HERE), "scripts", "validate_tiles.py"))
validate_tiles = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(validate_tiles)

Z, X, Y = 15, 21071, 14003   # the tile LON, LAT falls in
LON, LAT = 51.5, 25.285


def _feat(props, dx=0.0):
    return {"id": None, "geometry_type": "LineString",
            "coordinates": [[LON + dx, LAT], [LON + dx + 0.0004, LAT + 0.0003]],
            "properties": props, "_z": Z, "_x": X, "_y": Y}


def _tile(*names):
    return encode_tile([(n, [_feat({"kind": "road", "highway": "primary"}
                                   if n == "basemap" else {"cls": "lane", "n": 3, "i": 1})])
                        for n in names])


BASEMAP = _tile("basemap")
CASES = {
    "basemap only": (BASEMAP, True),
    "basemap + lanes": (_tile("basemap", "lanes"), True),
    "lanes only": (_tile("lanes"), False),
    "basemap + unknown": (_tile("basemap", "traffic"), False),
    "basemap + lanes + unknown": (_tile("basemap", "lanes", "traffic"), False),
    "duplicate basemap": (BASEMAP + BASEMAP, False),
    "duplicate lanes": (_tile("basemap", "lanes") + _tile("lanes"), False),
}


class RuleTest(unittest.TestCase):
    def test_names_are_read_raw_in_order(self):
        self.assertEqual(raw_layer_names(CASES["basemap + lanes"][0]), ["basemap", "lanes"])
        self.assertEqual(raw_layer_names(CASES["duplicate basemap"][0]), ["basemap", "basemap"])

    def test_every_case_through_check_layer_names(self):
        for label, (data, want) in CASES.items():
            with self.subTest(label):
                self.assertEqual(check_layer_names(raw_layer_names(data))[0], want)

    def test_every_case_through_the_bake_verifier(self):
        for label, (data, want) in CASES.items():
            with self.subTest(label):
                self.assertEqual(verify_tile_bytes(data)[0], want, verify_tile_bytes(data))

    def test_every_case_through_validate_tiles(self):
        with tempfile.TemporaryDirectory() as d:
            for i, (label, (data, want)) in enumerate(CASES.items()):
                p = os.path.join(d, f"{i}.mvt")
                open(p, "wb").write(data)
                with self.subTest(label):
                    status, reason = validate_tiles._classify(p)
                    self.assertEqual(status == "valid", want, reason)

    def test_empty_and_garbage(self):
        self.assertEqual(check_layer_names([]), (False, "no layers"))
        self.assertTrue(verify_tile_bytes(b"")[0])          # ocean: renders nothing
        self.assertFalse(verify_tile_bytes(b"\x1a\xff\xff")[0])

    def test_the_decoder_really_does_collapse_duplicates(self):
        # Why the rule reads raw bytes: if this ever starts failing, the
        # decoder has changed, and the raw read is merely redundant.
        from vector_tile_gen.encode import decode_tile
        self.assertEqual([ly["name"] for ly in decode_tile(BASEMAP + BASEMAP)["layers"]],
                         ["basemap"])


class ReleaseCensusTest(unittest.TestCase):
    RID = "vector-tiles-2026-09-22T1200Z-abc1234"

    def _tree(self, d, data):
        tdir = os.path.join(d, self.RID)
        os.makedirs(os.path.join(tdir, str(Z), str(X)))
        open(os.path.join(tdir, str(Z), str(X), f"{Y}.mvt"), "wb").write(data)
        return tdir

    def _manifest(self, tdir):
        m = build_manifest(tdir, release_id=self.RID, source={}, generator={},
                           input_config={"zooms": [Z]})
        write_manifest(tdir, m)
        return m

    def test_every_case_through_scan_and_verify(self):
        for label, (data, want) in CASES.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as d:
                tdir = self._tree(d, data)
                sc = scan_tree(tdir, census=True)
                self.assertEqual(not sc["bad_layer_tiles"], want, sc["bad_layer_tiles"])
                m = self._manifest(tdir)
                v = verify_manifest(tdir, m)
                codes = {f["code"] for f in v["failures"]}
                self.assertEqual("layer_unexpected" not in codes, want, v["failures"])

    def test_lanes_block_is_measured_and_verified(self):
        with tempfile.TemporaryDirectory() as d:
            tdir = self._tree(d, CASES["basemap + lanes"][0])
            m = self._manifest(tdir)
            ln = m["layers"]["lanes"]
            self.assertTrue(ln["lane_enabled"])
            self.assertEqual(ln["lane_zooms"], [Z])
            self.assertEqual(ln["tiles_with_lanes"], 1)
            self.assertEqual(ln["total_lane_features"], 1)
            lane_bytes = [len(b) for n, b in raw_layers(CASES["basemap + lanes"][0]) if n == "lanes"]
            self.assertEqual(ln["lane_layer_bytes"], lane_bytes[0])
            # ~0.0004 deg lon x 0.0003 deg lat at 25.3N: about 51 m
            self.assertAlmostEqual(ln["total_lane_geometry_length_m"], 51.0, delta=2.0)
            self.assertTrue(verify_manifest(tdir, m)["ok"])
            tampered = dict(m, layers=dict(m["layers"], lanes=dict(ln, total_lane_features=2)))
            codes = {f["code"] for f in verify_manifest(tdir, tampered)["failures"]}
            self.assertIn("lanes_mismatch", codes)

    def test_a_pre_v8_manifest_cannot_hide_a_lanes_layer(self):
        with tempfile.TemporaryDirectory() as d:
            tdir = self._tree(d, CASES["basemap + lanes"][0])
            m = self._manifest(tdir)
            old = dict(m, layers={k: v for k, v in m["layers"].items() if k != "lanes"})
            codes = {f["code"] for f in verify_manifest(tdir, old)["failures"]}
            self.assertIn("lanes_mismatch", codes)

    def test_a_pre_v8_manifest_still_verifies_a_basemap_only_tree(self):
        with tempfile.TemporaryDirectory() as d:
            tdir = self._tree(d, BASEMAP)
            m = self._manifest(tdir)
            self.assertFalse(m["layers"]["lanes"]["lane_enabled"])
            old = dict(m, layers={k: v for k, v in m["layers"].items() if k != "lanes"})
            codes = {f["code"] for f in verify_manifest(tdir, old)["failures"]}
            self.assertNotIn("lanes_mismatch", codes)

    def test_length_counts_only_the_part_inside_each_tile(self):
        # The same feature written into the NEIGHBOURING tile's file lies
        # wholly outside that tile's square, so it must add nothing.
        from vector_tile_gen.release import _clipped_length_m
        from vector_tile_gen.encode import decode_tile
        lanes = decode_tile(CASES["basemap + lanes"][0])["layers"][1]["features"]
        self.assertAlmostEqual(_clipped_length_m(lanes, Z, X, Y, 4096), 51.0, delta=2.0)
        self.assertEqual(_clipped_length_m([{"geometry": [[[-500, -500], [-10, -900]]]}],
                                           Z, X, Y, 4096), 0.0)
        half = _clipped_length_m([{"geometry": [[[-2048, 100], [2048, 100]]]}], Z, X, Y, 4096)
        full = _clipped_length_m([{"geometry": [[[0, 100], [4096, 100]]]}], Z, X, Y, 4096)
        self.assertAlmostEqual(half, full / 2, places=6)

    def test_basemap_kinds_are_not_polluted_by_lane_features(self):
        with tempfile.TemporaryDirectory() as d:
            sc = scan_tree(self._tree(d, CASES["basemap + lanes"][0]), census=True)
            self.assertEqual(sc["kinds"], {"road": 1})


class EncodeMultiLayerTest(unittest.TestCase):
    def test_a_single_layer_tile_is_the_bytes_it_always_was(self):
        import mapbox_vector_tile
        from vector_tile_gen.encode import tile_bbox
        f = _feat({"kind": "road", "highway": "primary"})
        direct = mapbox_vector_tile.encode(
            [{"name": "basemap", "features": [{
                "type": "Feature",
                "geometry": {"type": "LineString", "coordinates": f["coordinates"]},
                "properties": f["properties"]}]}],
            quantize_bounds=list(tile_bbox(Z, X, Y)), y_coord_down=False, extents=4096)
        self.assertEqual(encode_tile([("basemap", [f])]), direct)

    def test_empty_layers_are_skipped_not_encoded(self):
        self.assertEqual(encode_tile([("basemap", [_feat({"kind": "road"})]), ("lanes", [])]),
                         encode_tile([("basemap", [_feat({"kind": "road"})])]))

    def test_a_second_layer_is_encoded_and_the_first_is_untouched(self):
        both = CASES["basemap + lanes"][0]
        self.assertEqual(raw_layer_names(both), ["basemap", "lanes"])
        self.assertEqual(raw_layers(both)[0], raw_layers(BASEMAP)[0])

    def test_layers_from_different_tiles_are_refused(self):
        other = dict(_feat({"cls": "lane"}), _x=X + 1)
        with self.assertRaises(ValueError):
            encode_tile([("basemap", [_feat({"kind": "road"})]), ("lanes", [other])])


if __name__ == "__main__":
    unittest.main()
