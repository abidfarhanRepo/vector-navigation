"""The bake's release identity — the producer V7.7 was missing.

WHAT THIS SUITE IS DEFENDING
----------------------------
V7.7 shipped four consumers of ``RELEASE.json`` and no producer for a bake.
``manifest_bake.py`` is that producer, and the property it has to hold is not
"it writes a file" — it is that **every field in the manifest is measured, and
a field that cannot be measured is a refusal rather than a blank**.

That distinction is the whole reason the manifest exists. V7.7 §3 found
production advertising a filesystem mtime as a version; the failure was not
that the number was wrong, it was that it *looked* like an identity while being
a function of nothing. A manifest with an empty ``extract_sha256`` would
reproduce that failure exactly: a release that appears to name its source and
does not.

So the tests below are mostly about refusal and about measurement:

  * a declared source file that is absent refuses, rather than recording "";
  * a dirty tree refuses unless the caller says the bake is for a bench;
  * an existing manifest refuses, because a release is immutable and
    re-identifying bytes that may already have been staged is undetectable
    afterwards;
  * the digests recorded are the digests of the files on disk, verified by
    hashing them independently here;
  * the manifest that comes out verifies against the tree it describes, with
    the census on — which is what the publication gate will later demand.

THE LEGACY IMPORTER'S CENSUS CLAIM
----------------------------------
The last class in this file is not about ``manifest_bake`` at all. It pins a
defect found while writing it: ``import_legacy_tree.py`` passed a
census-free scan to ``build_manifest`` with ``censused=True``, so every
imported release asserted ``building_features: 0`` without having decoded a
single tile. On production's tree that claim is true by accident (V7.7 §8
censused it exhaustively: zero buildings). On a developer's volume after a
V7.6 bake — which is the other caller, ``bootstrap.sh`` — it would be false,
and it would be false in the one field the 3D milestone depends on.
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_PKG = os.path.join(os.path.dirname(_HERE), "src")
if _PKG not in sys.path:
    sys.path.insert(0, _PKG)

from vector_tile_gen.release import (  # noqa: E402
    RELEASE_FILENAME,
    parse_release_id,
    read_manifest,
    scan_tree,
    verify_manifest,
)

try:
    from vector_tile_gen.encode import encode_tile
    from vector_tile_gen.tiles import lonlat_to_tile

    _HAVE_MVT = True
except Exception:  # pragma: no cover - the decoder is optional in CI
    _HAVE_MVT = False


def _load(name, filename):
    path = os.path.normpath(os.path.join(_HERE, "..", "scripts", filename))
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


MB = _load("vector_manifest_bake", "manifest_bake.py")
IMPORTER = _load("vector_import_legacy_tree", "import_legacy_tree.py")

T0 = 1_789_819_647.0  # 2026-09-19T12:07Z
SHA = "8f4f2b4"

# One measured tower, one fabric block, and enough non-building kinds that a
# census which silently counted nothing would still look plausible.
DOHA = [
    (51.5310, 25.2854, {"kind": "road", "name": "Al Corniche"}),
    (51.5333, 25.2867, {"kind": "poi", "name": "Souq Waqif"}),
    (51.5290, 25.2840, {"kind": "building", "height_m": 42.0}),
    (51.5301, 25.2849, {"kind": "building"}),
]


def _feat(lon, lat, z, props):
    x, y = lonlat_to_tile(z, lon, lat)
    return {"id": f"f-{lon},{lat}", "geometry_type": "Point",
            "coordinates": [lon, lat], "properties": props,
            "_z": z, "_x": x, "_y": y}


def _bake(root, spec):
    """A real MVT tree — encoded by the encoder the bake uses, not a stand-in."""
    for z, feats in spec.items():
        by_tile = {}
        for lon, lat, props in feats:
            x, y = lonlat_to_tile(z, lon, lat)
            by_tile.setdefault((x, y), []).append(_feat(lon, lat, z, props))
        for (x, y), fds in by_tile.items():
            d = os.path.join(root, str(z), str(x))
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, f"{y}.mvt"), "wb") as fh:
                fh.write(encode_tile([("basemap", fds)]))
    return root


def _sources(parent):
    """Three stand-in source artifacts with distinguishable contents."""
    paths = {}
    for label, blob in (("pbf", b"PBF-BYTES"), ("extract", b"OSM-EXTRACT-BYTES"),
                        ("geojson", b'{"type":"FeatureCollection"}')):
        path = os.path.join(parent, f"{label}.bin")
        with open(path, "wb") as fh:
            fh.write(blob)
        paths[label] = path
    return paths


@unittest.skipUnless(_HAVE_MVT, "mapbox_vector_tile not installed")
class ManifestBakeTest(unittest.TestCase):

    def _tree(self, parent, spec=None):
        root = os.path.join(parent, "tiles")
        os.makedirs(root, exist_ok=True)
        return _bake(root, spec or {14: DOHA})

    def test_a_baked_tree_becomes_a_release_that_verifies_itself(self):
        """The whole point: what comes out passes the gate's own check."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            src = _sources(tmp)

            report = MB.manifest_bake(
                root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}",
                zooms=[14], max_per_tile=1500,
                bbox="24.4,50.7,26.2,51.8", geofabrik_release="260912", **src)

            self.assertTrue(report["ok"], report["failures"])
            manifest = read_manifest(root)
            self.assertIsNotNone(manifest)
            verdict = verify_manifest(root, manifest, census=True)
            self.assertTrue(verdict["ok"], verdict["failures"])

    def test_the_source_digests_are_the_files_not_the_operators_word(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            src = _sources(tmp)

            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}",
                             **src)

            source = read_manifest(root)["source"]
            for label, path in src.items():
                self.assertEqual(source[f"{label}_sha256"], MB.sha256_file(path))
                self.assertEqual(source[f"{label}_bytes"], os.path.getsize(path))

    def test_a_declared_source_that_is_absent_is_a_refusal_not_a_blank_field(self):
        """A blank digest reads as 'not relevant'. That is a different claim."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)

            report = MB.manifest_bake(
                root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}",
                extract=os.path.join(tmp, "does-not-exist.osm"))

            self.assertFalse(report["ok"])
            self.assertEqual([f["code"] for f in report["failures"]],
                             ["source_missing"])
            self.assertFalse(os.path.exists(os.path.join(root, RELEASE_FILENAME)))

    def test_it_refuses_to_re_identify_a_tree_that_is_already_a_release(self):
        """A release is immutable; re-identifying staged bytes is undetectable."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            first = MB.manifest_bake(
                root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}")
            self.assertTrue(first["ok"], first["failures"])

            second = MB.manifest_bake(
                root, release_id="vector-tiles-2026-09-19T1300Z-9757c54")

            self.assertFalse(second["ok"])
            self.assertEqual([f["code"] for f in second["failures"]],
                             ["manifest_exists"])
            self.assertEqual(read_manifest(root)["release_id"],
                             f"vector-tiles-2026-09-19T1207Z-{SHA}")

    def test_a_dirty_tree_refuses_unless_the_caller_says_it_is_a_bench_bake(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            # A directory that is not a git work tree at all: `git_head` is
            # conservative and reports {"sha": "", "dirty": True}, which is the
            # same shape as a dirty checkout and must be refused the same way.
            report = MB.manifest_bake(root, repo_root=tmp, now_s=T0)

            self.assertFalse(report["ok"])
            self.assertIn(report["failures"][0]["code"], ("dirty_tree", "unidentified"))
            self.assertFalse(os.path.exists(os.path.join(root, RELEASE_FILENAME)))

    def test_an_empty_directory_is_refused_rather_than_described(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = os.path.join(tmp, "empty")
            os.makedirs(root)

            report = MB.manifest_bake(
                root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}")

            self.assertFalse(report["ok"])
            self.assertEqual([f["code"] for f in report["failures"]], ["no_tiles"])

    def test_the_building_census_is_measured_from_the_tiles(self):
        """The field the 3D milestone is published on. One tower, two blocks."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)

            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}")

            manifest = read_manifest(root)
            layers = manifest["layers"]
            self.assertEqual(layers["building_features"], 2)
            self.assertEqual(layers["building_features_with_height_m"], 1)
            # `layer_names` counts TILES carrying a layer, not features in it.
            self.assertEqual(layers["layer_names"],
                             {"basemap": manifest["coverage"]["tiles_total"]})

    def test_the_pipeline_scripts_are_hashed_individually(self):
        """`-dirty` says the tree was dirty. It does not say WHICH file moved."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)

            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}")

            gen = read_manifest(root)["generator"]
            scripts = os.path.normpath(os.path.join(_HERE, "..", "scripts"))
            for role, name in MB.PIPELINE_SCRIPTS.items():
                self.assertEqual(gen[role], name)
                self.assertEqual(gen[f"{role}_sha256"],
                                 MB.sha256_file(os.path.join(scripts, name)))

    def test_a_lanes_bake_records_its_sidecar_and_its_module(self):
        """V8: the lane layer is decided by lanes.py and fed by a sidecar;
        both are hashed like the three scripts, and neither appears for a
        bake that did not use them."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            side = os.path.join(tmp, "lane_attrs.json")
            with open(side, "w") as fh:
                fh.write('{"schema": "vector-lane-attrs-1", "keys": [], "ways": {}}')
            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}",
                             lane_attrs=side)
            m = read_manifest(root)
            self.assertIs(m["input_config"]["lanes"], True)
            self.assertEqual(m["source"]["lane_attrs_sha256"], MB.sha256_file(side))
            lanes_py = os.path.normpath(os.path.join(_HERE, "..", "src", "vector_tile_gen", "lanes.py"))
            self.assertEqual(m["generator"]["lanes_sha256"], MB.sha256_file(lanes_py))
            # the tiles hold no lanes layer, and the census says so
            self.assertFalse(m["layers"]["lanes"]["lane_enabled"])
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}")
            m = read_manifest(root)
            self.assertNotIn("lanes", m["input_config"])
            self.assertNotIn("lanes_sha256", m["generator"])
            self.assertNotIn("lane_attrs_sha256", m["source"])

    def test_a_taper_bake_records_its_flag_and_its_module(self):
        """The carriageway tapers are decided by taper.py; a bake that used
        them says so and hashes it, and one that did not says nothing."""
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}",
                             taper=True)
            m = read_manifest(root)
            self.assertIs(m["input_config"]["taper"], True)
            taper_py = os.path.normpath(os.path.join(_HERE, "..", "src", "vector_tile_gen", "taper.py"))
            self.assertEqual(m["generator"]["taper_sha256"], MB.sha256_file(taper_py))
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}")
            m = read_manifest(root)
            self.assertNotIn("taper", m["input_config"])
            self.assertNotIn("taper_sha256", m["generator"])

    def test_a_missing_lane_sidecar_is_a_refusal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            report = MB.manifest_bake(root, release_id=f"vector-tiles-2026-09-19T1207Z-{SHA}",
                                      lane_attrs=os.path.join(tmp, "absent.json"))
            self.assertFalse(report["ok"])
            self.assertEqual({f["code"] for f in report["failures"]}, {"source_missing"})

    def test_the_derived_id_names_the_commit_it_was_baked_from(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self._tree(tmp)
            repo = os.path.dirname(os.path.dirname(_HERE))

            report = MB.manifest_bake(root, repo_root=repo, allow_dirty=True,
                                      now_s=T0)

            self.assertTrue(report["ok"], report["failures"])
            parsed = parse_release_id(report["release_id"])
            self.assertEqual(parsed["kind"], "bake")
            self.assertEqual(parsed["stamp"], "2026-09-19T1207Z")
            self.assertEqual(parsed["provenance"], "commit")

    def test_the_cli_reports_a_refusal_as_a_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = os.path.join(tmp, "empty")
            os.makedirs(root)

            code = MB.main([root, "--release-id",
                            f"vector-tiles-2026-09-19T1207Z-{SHA}", "--json"])

            self.assertEqual(code, 1)


@unittest.skipUnless(_HAVE_MVT, "mapbox_vector_tile not installed")
class LegacyImportCensusTest(unittest.TestCase):
    """The defect ``manifest_bake`` was written beside.

    ``import_legacy_tree`` scanned with ``census=False`` and then handed
    ``build_manifest`` a ``dict(scan, censused=True)``. ``build_manifest``'s own
    docstring says why that matters: *"a manifest that claims layers it never
    looked at is worse than no manifest, because it is a claim that a gate will
    later trust."*

    The production import happens to be truthful — V7.7 §8 censused that exact
    tree (digest ``ca07394a…``) and found zero buildings. The other caller is
    ``bootstrap.sh``, which imports a **developer's freshly baked volume**, and
    there the claim is simply wrong.
    """

    def test_an_imported_tree_reports_the_buildings_it_actually_contains(self):
        with tempfile.TemporaryDirectory() as tmp:
            vol = os.path.join(tmp, "vol")
            os.makedirs(vol)
            _bake(vol, {14: DOHA})

            report = IMPORTER.import_legacy_tree(vol, activate=False)
            self.assertTrue(report["ok"], report["failures"])

            dest = os.path.join(vol, "releases", report["release_id"])
            layers = read_manifest(dest)["layers"]
            observed = scan_tree(dest, census=True)

            self.assertEqual(layers["building_features"],
                             observed["building_features"])
            self.assertEqual(layers["building_features_with_height_m"],
                             observed["building_features_with_height_m"])
            self.assertEqual(layers["layer_names"], observed["layer_names"])

    def test_an_imported_release_survives_a_censused_verification(self):
        """The check the publisher runs before it will activate anything."""
        with tempfile.TemporaryDirectory() as tmp:
            vol = os.path.join(tmp, "vol")
            os.makedirs(vol)
            _bake(vol, {14: DOHA})

            report = IMPORTER.import_legacy_tree(vol, activate=False)
            dest = os.path.join(vol, "releases", report["release_id"])

            verdict = verify_manifest(dest, read_manifest(dest), census=True)

            self.assertTrue(verdict["ok"], json.dumps(verdict["failures"]))


if __name__ == "__main__":
    unittest.main()
