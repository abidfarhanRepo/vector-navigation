"""End-to-end test of the learned-geometry re-bake script (issue 08).

Runs ``scripts/rebake_learned.py`` for real against a small basemap, then decodes
the tiles it wrote. This is the highest-risk promotion in the effort — a bad one
writes a wrong road into the tiles every user sees — so the properties that make
it safe are asserted against actual output rather than the module API:

* the learned road is present in the tile after a promotion,
* it is **gone** after a withdrawal, and the OSM roads are still there,
* the OSM source file is byte-identical throughout,
* every published tile still has exactly one ``basemap`` layer, and
* the tile epoch advances so clients cannot serve the old road from cache.
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE)
sys.path.insert(0, os.path.join(_REPO, "src"))

from vector_tile_gen.encode import decode_tile, encode_tile  # noqa: E402
from vector_tile_gen.layers import raw_layer_names  # noqa: E402
from vector_tile_gen.learned_layer import (  # noqa: E402
    affected_tiles,
    features_from_facts,
)
from vector_tile_gen.tile_version import read_version  # noqa: E402

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



SCRIPT = os.path.join(_REPO, "scripts", "rebake_learned.py")
ZOOM = 14

# Two OSM roads in central Doha.
BASEMAP = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature", "id": "osm-1",
         "properties": {"kind": "road", "highway": "primary", "name": "Existing Road A"},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.5300, 25.2860], [51.5340, 25.2870]]}},
        {"type": "Feature", "id": "osm-2",
         "properties": {"kind": "road", "highway": "residential", "name": "Existing Road B"},
         "geometry": {"type": "LineString",
                      "coordinates": [[51.5305, 25.2880], [51.5345, 25.2890]]}},
    ],
}

LEARNED_KEY = "road_candidate:51.532:25.287"
LEARNED_EXPORT = {
    "count": 1,
    "facts": [{
        "fact_key": LEARNED_KEY,
        "fact_type": "road_candidate",
        "evidence_count": 12,
        "confidence": 0.93,
        "lng": 51.5320,
        "lat": 25.2870,
        "payload": {
            "point_count": 12,
            "geometry": [[51.5310, 25.2865], [51.5330, 25.2875]],
        },
    }],
}


def python_exe():
    return sys.executable


def sha256(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def decode_all(tiles_dir):
    """Every tile on disk, decoded: ``{(z,x,y): [layer names], ...}`` + features."""
    out = {}
    for root, _dirs, files in os.walk(tiles_dir):
        for name in files:
            if not name.endswith(".mvt"):
                continue
            path = os.path.join(root, name)
            rel = os.path.relpath(path, tiles_dir).replace("\\", "/").split("/")
            if len(rel) != 3:
                continue
            key = (int(rel[0]), int(rel[1]), int(rel[2][:-4]))
            with open(path, "rb") as fh:
                data = fh.read()
            out[key] = decode_tile(data) if data else {"layers": []}
    return out


def feature_names(decoded):
    """All feature property dicts across all layers of a decoded tile."""
    props = []
    for layer in decoded.get("layers", []):
        for feature in layer.get("features", []):
            props.append(feature.get("properties", {}) or {})
    return props


@unittest.skipUnless(os.path.exists(SCRIPT), "rebake script missing")
@_needs_mvt
class RebakeLearnedScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = os.path.join(self.tmp.name, "basemap.geojson")
        self.export = os.path.join(self.tmp.name, "learned_geometry.json")
        self.tiles = os.path.join(self.tmp.name, "tiles")
        os.makedirs(self.tiles, exist_ok=True)
        with open(self.base, "w", encoding="utf-8") as fh:
            json.dump(BASEMAP, fh)
        with open(self.export, "w", encoding="utf-8") as fh:
            json.dump(LEARNED_EXPORT, fh)
        self.base_digest = sha256(self.base)

    def tearDown(self):
        self.tmp.cleanup()

    def run_script(self, *extra):
        cmd = [python_exe(), SCRIPT,
               "--learned-facts", self.export,
               "--base-geojson", self.base,
               "--tiles-dir", self.tiles,
               "--zooms", str(ZOOM), *extra]
        env = dict(os.environ)
        # This repo's src only. Under per-repo CI isolation no sibling repo is
        # checked out, so a script that reaches for one passes here and fails in
        # the gate — which is exactly what happened the first time.
        env["PYTHONPATH"] = os.path.join(_REPO, "src")
        proc = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=300)
        # Surface the script's own output on failure; an exit code alone makes a
        # subprocess test almost impossible to diagnose from a CI log.
        if proc.returncode not in (0, 3):
            print(proc.stdout)
            print(proc.stderr)
        return proc

    # ---- promotion -------------------------------------------------------

    def test_promotion_writes_the_learned_road_into_the_tiles(self):
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        tiles = decode_all(self.tiles)
        self.assertTrue(tiles, "no tiles were written")
        learned = [p for decoded in tiles.values()
                   for p in feature_names(decoded)
                   if p.get("learned") in (True, "true", 1)]
        self.assertTrue(learned, "the learned road never reached a tile")

    def test_learned_features_are_marked_for_the_style_to_distinguish(self):
        self.run_script()
        props = [p for decoded in decode_all(self.tiles).values()
                 for p in feature_names(decoded)
                 if p.get("learned") in (True, "true", 1)]
        self.assertTrue(props)
        one = props[0]
        self.assertEqual(one.get("fact_key"), LEARNED_KEY)
        # confidence 0.93 < 0.95 => provisional, so the style paints it dashed.
        self.assertIn(one.get("provisional"), (True, "true", 1))

    def test_osm_roads_survive_the_promotion(self):
        self.run_script()
        names = {p.get("name") for decoded in decode_all(self.tiles).values()
                 for p in feature_names(decoded)}
        self.assertIn("Existing Road A", names)
        self.assertIn("Existing Road B", names)

    def test_every_published_tile_has_exactly_one_basemap_layer(self):
        """Session 50's rule. A second layer is the 'streets vanish' bug."""
        self.run_script()
        for key, decoded in decode_all(self.tiles).items():
            layers = [ly.get("name") for ly in decoded.get("layers", [])]
            self.assertEqual(layers, ["basemap"], f"tile {key} has layers {layers}")

    def test_the_osm_source_is_never_modified(self):
        self.run_script()
        self.assertEqual(sha256(self.base), self.base_digest,
                         "the OSM source was edited in place")

    def test_the_epoch_advances_so_clients_refetch(self):
        before = read_version(self.tiles)["epoch"]
        self.run_script()
        self.assertEqual(read_version(self.tiles)["epoch"], before + 1)

    def test_provenance_overlay_is_written(self):
        self.run_script()
        overlay = os.path.join(self.tiles, "learned-overlay.geojson")
        self.assertTrue(os.path.exists(overlay))
        with open(overlay, encoding="utf-8") as fh:
            doc = json.load(fh)
        self.assertEqual(len(doc["features"]), 1)
        self.assertEqual(doc["features"][0]["properties"]["fact_key"], LEARNED_KEY)

    # ---- rollback --------------------------------------------------------

    def test_withdraw_removes_the_road_and_keeps_the_osm_ones(self):
        self.run_script()
        proc = self.run_script("--withdraw", LEARNED_KEY)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        tiles = decode_all(self.tiles)
        learned = [p for decoded in tiles.values()
                   for p in feature_names(decoded)
                   if p.get("learned") in (True, "true", 1)]
        self.assertFalse(learned, "a withdrawn road is still in the tiles")
        names = {p.get("name") for decoded in tiles.values() for p in feature_names(decoded)}
        self.assertIn("Existing Road A", names)

    def test_withdraw_all_is_a_full_rollback(self):
        self.run_script()
        proc = self.run_script("--withdraw-all")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        learned = [p for decoded in decode_all(self.tiles).values()
                   for p in feature_names(decoded)
                   if p.get("learned") in (True, "true", 1)]
        self.assertFalse(learned)

    def test_withdrawal_also_bumps_the_epoch(self):
        self.run_script()
        after_promote = read_version(self.tiles)["epoch"]
        self.run_script("--withdraw-all")
        self.assertEqual(read_version(self.tiles)["epoch"], after_promote + 1,
                         "a rollback clients cannot see is not a rollback")

    # ---- degenerate inputs ----------------------------------------------

    def test_dry_run_writes_nothing(self):
        proc = self.run_script("--dry-run")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(decode_all(self.tiles), {})
        self.assertEqual(read_version(self.tiles)["epoch"], 0)

    def test_empty_export_is_a_no_op_not_a_crash(self):
        with open(self.export, "w", encoding="utf-8") as fh:
            json.dump({"facts": []}, fh)
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("nothing to re-bake", proc.stdout)
        # No tiles touched means no epoch bump: an epoch change is a promise
        # that something changed.
        self.assertEqual(read_version(self.tiles)["epoch"], 0)

    def test_missing_export_is_a_no_op(self):
        os.remove(self.export)
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)


# The refusal exit code, mirrored from scripts/rebake_learned.py so a change to
# it fails a test rather than passing silently.
EXIT_LANES_UNSUPPORTED = 4


@unittest.skipUnless(os.path.exists(SCRIPT), "rebake script missing")
@_needs_mvt
class RebakeRefusesLaneReleaseTest(RebakeLearnedScriptTest):
    """A V8 lane release must survive this basemap-only path untouched.

    ``rebake_learned.py`` writes basemap-only tiles, so re-baking a lanes
    release's z15 tiles through it would drop their ``lanes`` layer. The safe
    behaviour chosen for V8 is to REFUSE, clearly and before writing anything.
    These tests prove the refusal and prove the lanes tile is preserved — the
    regression the review requires: a tile carrying basemap + lanes is either
    preserved or rejected explicitly and safely, never silently stripped.
    """

    def run_script(self, *extra):
        # Lanes live at z15, so this path must scan z15 to see them. The parent
        # harness runs a single zoom (14); widen it to the two zooms these
        # fixtures use.
        return super().run_script("--zooms", "14,15", *extra)

    def _affected_z15_tile(self):
        """A z15 tile this promotion would actually overwrite."""
        feats = features_from_facts(LEARNED_EXPORT["facts"])
        z15 = sorted(t for t in affected_tiles(feats, [15]) if t[0] == 15)
        self.assertTrue(z15, "the fixture learned road touches no z15 tile")
        return z15[0]

    def _write_basemap_plus_lanes(self, z, x, y):
        """Encode a real basemap + lanes tile and place it on disk."""
        basemap = [{"id": None, "geometry_type": "LineString",
                    "coordinates": [[51.5310, 25.2865], [51.5330, 25.2875]],
                    "properties": {"kind": "road", "highway": "primary"},
                    "_z": z, "_x": x, "_y": y}]
        lanes = [{"id": None, "geometry_type": "LineString",
                  "coordinates": [[51.5312, 25.2866], [51.5328, 25.2874]],
                  "properties": {"cls": "lane", "n": 3, "i": 1},
                  "_z": z, "_x": x, "_y": y}]
        data = encode_tile([("basemap", basemap), ("lanes", lanes)])
        self.assertIn("lanes", raw_layer_names(data), "fixture tile has no lanes layer")
        path = os.path.join(self.tiles, str(z), str(x), f"{y}.mvt")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write(data)
        return path, data

    def test_promotion_over_a_lanes_tile_is_refused_and_preserves_lanes(self):
        z, x, y = self._affected_z15_tile()
        path, before = self._write_basemap_plus_lanes(z, x, y)

        proc = self.run_script()

        self.assertEqual(proc.returncode, EXIT_LANES_UNSUPPORTED,
                         f"expected a clean refusal, got:\n{proc.stdout}\n{proc.stderr}")
        self.assertIn("lanes", proc.stderr.lower())
        # The message must name WHY, not just fail.
        self.assertIn("not supported", proc.stderr.lower())
        # The lanes tile is byte-identical: nothing was dropped.
        with open(path, "rb") as fh:
            self.assertEqual(fh.read(), before, "the lanes tile was overwritten")
        self.assertIn("lanes", raw_layer_names(before))
        # No epoch bump: a refusal changed nothing, so it must not claim it did.
        self.assertEqual(read_version(self.tiles)["epoch"], 0)

    def test_a_release_declaring_lanes_is_refused_even_without_a_lane_tile(self):
        # No lane-bearing tile on the affected paths, but the descriptor says
        # this is a lane release — its lane tiles simply fall elsewhere.
        with open(os.path.join(self.tiles, "RELEASE.json"), "w", encoding="utf-8") as fh:
            json.dump({"input_config": {"lanes": True}}, fh)
        proc = self.run_script()
        self.assertEqual(proc.returncode, EXIT_LANES_UNSUPPORTED,
                         f"{proc.stdout}\n{proc.stderr}")
        self.assertIn("RELEASE.json", proc.stderr)
        self.assertEqual(read_version(self.tiles)["epoch"], 0)

    def test_withdraw_over_a_lanes_release_is_also_refused(self):
        # The rollback path is just as destructive: it re-bakes the same tiles
        # basemap-only. It must refuse too.
        z, x, y = self._affected_z15_tile()
        path, before = self._write_basemap_plus_lanes(z, x, y)
        proc = self.run_script("--withdraw-all")
        self.assertEqual(proc.returncode, EXIT_LANES_UNSUPPORTED,
                         f"{proc.stdout}\n{proc.stderr}")
        with open(path, "rb") as fh:
            self.assertEqual(fh.read(), before)

    def test_dry_run_over_a_lanes_release_still_refuses(self):
        # Dry-run writes nothing anyway, but the operator must still be told the
        # operation is unsupported rather than shown a green plan.
        z, x, y = self._affected_z15_tile()
        self._write_basemap_plus_lanes(z, x, y)
        proc = self.run_script("--dry-run")
        self.assertEqual(proc.returncode, EXIT_LANES_UNSUPPORTED,
                         f"{proc.stdout}\n{proc.stderr}")

    def test_a_basemap_only_release_is_unaffected(self):
        # The guard must not break the ordinary case: no lanes anywhere means a
        # normal promotion still runs and writes the learned road.
        proc = self.run_script()
        self.assertEqual(proc.returncode, 0, proc.stderr)
        learned = [p for decoded in decode_all(self.tiles).values()
                   for p in feature_names(decoded)
                   if p.get("learned") in (True, "true", 1)]
        self.assertTrue(learned, "the guard blocked an ordinary basemap-only promotion")


if __name__ == "__main__":
    unittest.main()
