"""Tests for the learned-geometry tile layer (issue 08)."""

import json
import os
import sys
import tempfile
import unittest

_SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src")
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

from vector_tile_gen.learned_layer import (  # noqa: E402



    BASEMAP_LAYER,
    LearnedFeature,
    affected_tiles,
    features_from_facts,
    merge_sources,
    rebake_tiles,
    verify_tile_bytes,
    withdraw_tiles,
    write_learned_geojson,
)


# The MVT encoder is an OPTIONAL dependency (`mapbox-vector-tile`, declared in
# pyproject.toml). CI installs it and the bootstrap falls back to a container
# that has it, but a developer's host is often PEP-668 managed and cannot. When
# it is absent these tests ERRORED rather than skipped, so the suite was red on
# a working checkout for a reason that has nothing to do with the code — which
# is how people learn to ignore a red suite.
#
# `test_tile_integrity.py` already guarded itself this way; this is the same
# pattern, applied to the files that never adopted it. Nothing is skipped where
# the dependency exists.
try:
    import mapbox_vector_tile as _mvt  # noqa: F401
    _HAVE_MVT = True
except ImportError:  # pragma: no cover - environment-dependent
    _HAVE_MVT = False

_needs_mvt = unittest.skipUnless(_HAVE_MVT, "mapbox-vector-tile not installed")


DOHA = (51.5310, 25.2854)


def road_candidate_fact(confidence=0.95, geometry=None, key="road_candidate:51.531:25.285"):
    return {
        "fact_type": "road_candidate",
        "fact_key": key,
        "confidence": confidence,
        "evidence_count": 9,
        "lng": DOHA[0],
        "lat": DOHA[1],
        "payload": {"geometry": geometry} if geometry else {},
    }


class BaseFeature:
    """Stand-in for a normalized OSM feature from vector-map-store."""

    def __init__(self, fid, coords):
        self.id = fid
        self.geometry_type = "LineString"
        self.coordinates = coords
        self.properties = {"highway": "residential"}
        xs = [c[0] for c in coords]
        ys = [c[1] for c in coords]
        self.bbox = (min(xs), min(ys), max(xs), max(ys))


def base_road():
    return BaseFeature("osm-1", [[51.5300, 25.2850], [51.5320, 25.2860]])


class FeatureBuildTest(unittest.TestCase):
    def test_line_geometry_becomes_a_linestring(self):
        geom = [[51.5300, 25.2850], [51.5320, 25.2860]]
        feats = features_from_facts([road_candidate_fact(geometry=geom)])
        self.assertEqual(len(feats), 1)
        self.assertEqual(feats[0].geometry_type, "LineString")

    def test_untraced_candidate_falls_back_to_a_point(self):
        feats = features_from_facts([road_candidate_fact()])
        self.assertEqual(feats[0].geometry_type, "Point")
        self.assertEqual(feats[0].coordinates, [DOHA[0], DOHA[1]])

    def test_learned_features_are_visually_distinguishable(self):
        """A wrong promotion must be obvious, not invisible."""
        props = features_from_facts([road_candidate_fact(confidence=0.91)])[0].properties
        self.assertIs(props["learned"], True)
        self.assertTrue(props["provisional"], "below 0.95 should render provisionally")
        self.assertEqual(props["confidence"], 0.91)

    def test_high_confidence_drops_the_provisional_flag(self):
        props = features_from_facts([road_candidate_fact(confidence=0.99)])[0].properties
        self.assertFalse(props["provisional"])

    def test_features_carry_provenance_back_to_the_fact(self):
        props = features_from_facts([road_candidate_fact()])[0].properties
        self.assertEqual(props["fact_key"], "road_candidate:51.531:25.285")
        self.assertEqual(props["evidence_count"], 9)

    def test_non_geometry_facts_are_ignored(self):
        speed = dict(road_candidate_fact(), fact_type="speed_profile")
        self.assertEqual(features_from_facts([speed]), [])


class MergeTest(unittest.TestCase):
    def test_base_is_never_mutated(self):
        """The OSM source is never edited in place — that is the rollback path."""
        base = [base_road()]
        before = len(base)
        merged = merge_sources(base, features_from_facts([road_candidate_fact()]))
        self.assertEqual(len(base), before)
        self.assertEqual(len(merged), before + 1)

    def test_merged_keeps_both_sources(self):
        merged = merge_sources([base_road()], features_from_facts([road_candidate_fact()]))
        learned_flags = [getattr(f, "properties", {}).get("learned") for f in merged]
        self.assertEqual(learned_flags.count(True), 1)
        self.assertEqual(learned_flags.count(None), 1)


class AffectedTilesTest(unittest.TestCase):
    def test_only_touched_tiles_are_returned(self):
        feats = features_from_facts([road_candidate_fact()])
        tiles = affected_tiles(feats, zooms=[12])
        self.assertEqual(len(tiles), 1, "a point touches exactly one tile per zoom")
        z, x, y = next(iter(tiles))
        self.assertEqual(z, 12)

    def test_multiple_zooms_produce_one_tile_each(self):
        feats = features_from_facts([road_candidate_fact()])
        self.assertEqual(len(affected_tiles(feats, zooms=[10, 11, 12])), 3)

    def test_no_features_touches_nothing(self):
        self.assertEqual(affected_tiles([], zooms=[12]), set())


@_needs_mvt
class VerifyTest(unittest.TestCase):
    def test_empty_tile_is_publishable(self):
        ok, reason = verify_tile_bytes(b"")
        self.assertTrue(ok)
        self.assertEqual(reason, "empty")

    def test_undecodable_tile_is_rejected(self):
        ok, reason = verify_tile_bytes(b"\xff\xff not an mvt \x00")
        self.assertFalse(ok)

    def test_wrong_layer_name_is_rejected(self):
        """The exact Session 50 'streets vanish' failure mode."""
        from vector_tile_gen.pipeline import generate_tile

        feats = features_from_facts([road_candidate_fact()])
        # Use the tile the feature actually falls in — otherwise the tile
        # encodes empty and is legitimately publishable, testing nothing.
        (z, x, y) = next(iter(affected_tiles(feats, zooms=[12])))
        bad = generate_tile(feats, z, x, y, layer_name="vector")
        ok, reason = verify_tile_bytes(bad)
        self.assertFalse(ok)
        self.assertIn("basemap", reason)

    def test_same_tile_with_the_right_layer_passes(self):
        """Control for the test above: only the layer name differs."""
        from vector_tile_gen.pipeline import generate_tile

        feats = features_from_facts([road_candidate_fact()])
        (z, x, y) = next(iter(affected_tiles(feats, zooms=[12])))
        good = generate_tile(feats, z, x, y, layer_name=BASEMAP_LAYER)
        ok, _reason = verify_tile_bytes(good)
        self.assertTrue(ok)


@_needs_mvt
class RebakeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.tiles_dir = os.path.join(self.tmp.name, "tiles")

    def tearDown(self):
        self.tmp.cleanup()

    def test_rebake_writes_only_verified_tiles(self):
        learned = features_from_facts([road_candidate_fact()])
        tiles = affected_tiles(learned, zooms=[12])
        result = rebake_tiles(self.tiles_dir, [base_road()], learned, tiles)
        self.assertTrue(result.ok)
        for (z, x, y) in result.written:
            path = os.path.join(self.tiles_dir, str(z), str(x), f"{y}.mvt")
            self.assertTrue(os.path.exists(path))
            with open(path, "rb") as fh:
                ok, _ = verify_tile_bytes(fh.read())
            self.assertTrue(ok, "every published tile passes the verifier")

    def test_rebaked_tile_uses_the_basemap_layer(self):
        from vector_tile_gen.encode import decode_tile

        learned = features_from_facts([road_candidate_fact()])
        tiles = affected_tiles(learned, zooms=[12])
        rebake_tiles(self.tiles_dir, [base_road()], learned, tiles)
        (z, x, y) = next(iter(tiles))
        with open(os.path.join(self.tiles_dir, str(z), str(x), f"{y}.mvt"), "rb") as fh:
            decoded = decode_tile(fh.read())
        self.assertEqual([ly["name"] for ly in decoded["layers"]], [BASEMAP_LAYER])

    def test_dry_run_writes_nothing(self):
        learned = features_from_facts([road_candidate_fact()])
        tiles = affected_tiles(learned, zooms=[12])
        result = rebake_tiles(self.tiles_dir, [base_road()], learned, tiles, dry_run=True)
        self.assertTrue(result.written)
        self.assertFalse(os.path.exists(self.tiles_dir))

    def test_withdraw_rebakes_without_the_learned_feature(self):
        """One command rolls the promotion back; the base was never edited."""
        from vector_tile_gen.encode import decode_tile

        learned = features_from_facts([road_candidate_fact()])
        tiles = affected_tiles(learned, zooms=[12])
        rebake_tiles(self.tiles_dir, [base_road()], learned, tiles)
        (z, x, y) = next(iter(tiles))
        path = os.path.join(self.tiles_dir, str(z), str(x), f"{y}.mvt")
        with open(path, "rb") as fh:
            before = len(decode_tile(fh.read())["layers"][0]["features"])

        result = withdraw_tiles(self.tiles_dir, [base_road()], tiles)
        self.assertTrue(result.ok)
        with open(path, "rb") as fh:
            after_layers = decode_tile(fh.read())["layers"]
        after = len(after_layers[0]["features"]) if after_layers else 0
        self.assertLess(after, before, "the learned feature is gone after withdrawal")

    def test_rebake_result_serializes(self):
        learned = features_from_facts([road_candidate_fact()])
        tiles = affected_tiles(learned, zooms=[12])
        payload = rebake_tiles(self.tiles_dir, [base_road()], learned, tiles).to_dict()
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["rejected"], [])


class GeoJsonOverlayTest(unittest.TestCase):
    def test_overlay_is_written_separately_from_the_osm_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "learned.geojson")
            write_learned_geojson(path, features_from_facts([road_candidate_fact()]))
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
            self.assertEqual(doc["type"], "FeatureCollection")
            self.assertIs(doc["features"][0]["properties"]["learned"], True)


if __name__ == "__main__":
    unittest.main()
