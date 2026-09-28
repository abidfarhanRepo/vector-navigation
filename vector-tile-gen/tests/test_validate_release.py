"""Tests for the pre-publication gate.

WHAT A GATE HAS TO GET RIGHT, AND WHAT IT IS EASY TO GET WRONG
--------------------------------------------------------------
A gate that has only ever passed is not a gate. So most of this file is about
the failure paths, and specifically about the three failures V7.7 exists to
stop, none of which a per-tile checker can see because every tile involved is
perfectly valid:

  * the transfer that died halfway (tiles MISSING);
  * `cp -r` MERGING, so a tile from the previous bake survives (tiles EXTRA);
  * the copy that landed only the low zooms, which silently re-advertises a
    smaller `maxzoom` and degrades the driving view with nothing reporting it.

Three further properties are pinned because getting them wrong would quietly
destroy the gate's usefulness rather than break it loudly:

  * **exit 2 is not exit 1.** A failed release is a decision; an unrunnable
    gate is an outage. Conflating them is how a broken check reads as a
    passing one.
  * **the gate never touches the network.** A pre-publication gate that can
    reach production is a gate production can satisfy, and the entire question
    is whether to go there at all.
  * **the gate never modifies the artifact it judges.** A release is immutable
    by definition, and a checker that edits its subject cannot be trusted about
    the subject it passed.
"""

import importlib.util
import json
import os
import shutil
import socket
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(_HERE), "src"))

from vector_tile_gen.release import (  # noqa: E402
    build_manifest,
    format_release_id,
    iter_tile_paths,
    scan_tree,
    tree_digest,
    write_digests,
    write_manifest,
)

try:
    from vector_tile_gen.encode import encode_tile
    from vector_tile_gen.tiles import lonlat_to_tile
    import mapbox_vector_tile  # noqa: F401
    _HAVE_MVT = True
except Exception:  # pragma: no cover
    _HAVE_MVT = False


def _load_gate():
    """Import the gate script (it lives in scripts/, not the package)."""
    path = os.path.normpath(os.path.join(_HERE, "..", "scripts",
                                         "validate_release.py"))
    spec = importlib.util.spec_from_file_location("validate_release", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


GATE = _load_gate()

T0 = 1_789_819_647.0
SHA = "8f4f2b4"

DOHA = [
    (51.5310, 25.2854, {"kind": "road", "name": "Al Corniche"}),
    (51.5333, 25.2867, {"kind": "poi", "name": "Souq Waqif"}),
    (51.5290, 25.2840, {"kind": "building", "height_m": 42.0}),
    (51.5301, 25.2849, {"kind": "building"}),
]

NO_BUILDINGS = [
    (51.5310, 25.2854, {"kind": "road", "name": "Al Corniche"}),
    (51.5333, 25.2867, {"kind": "poi", "name": "Souq Waqif"}),
]

FABRIC_ONLY = [
    (51.5310, 25.2854, {"kind": "road", "name": "Al Corniche"}),
    (51.5290, 25.2840, {"kind": "building"}),          # no stated height
    (51.5301, 25.2849, {"kind": "building"}),
]


def _feat(lon, lat, z, props):
    x, y = lonlat_to_tile(z, lon, lat)
    return {
        "id": f"f-{lon},{lat}",
        "geometry_type": "Point",
        "coordinates": [lon, lat],
        "properties": props,
        "_z": z, "_x": x, "_y": y,
    }


def _write_tile(root, z, x, y, data):
    d = os.path.join(root, str(z), str(x))
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, f"{y}.mvt"), "wb") as fh:
        fh.write(data)


def _bake(root, spec):
    for z, feats in spec.items():
        by_tile = {}
        for lon, lat, props in feats:
            x, y = lonlat_to_tile(z, lon, lat)
            by_tile.setdefault((x, y), []).append(_feat(lon, lat, z, props))
        for (x, y), fds in by_tile.items():
            _write_tile(root, z, x, y, encode_tile([("basemap", fds)]))
    return root


def _stage(parent, *, spec=None, dirty=False, release_id=None):
    """A complete, staged, manifest-bearing release directory."""
    rid = release_id or format_release_id(now_s=T0, git_sha=SHA, dirty=dirty)
    root = os.path.join(parent, rid)
    os.makedirs(root, exist_ok=True)
    _bake(root, spec or {14: DOHA})
    sc = scan_tree(root, census=True)
    m = build_manifest(
        root, release_id=rid,
        source={"kind": "osm-pbf", "extract_name": "qatar.osm", "region": "qatar"},
        generator={"repo_sha": SHA, "repo_dirty": dirty},
        input_config={"zooms": sorted(spec or {14: DOHA})},
        scan=sc,
    )
    write_digests(root, sc["entries"])
    write_manifest(root, m)
    return root


def _codes(report):
    return {f["code"] for f in report["failures"]}


class GateBaseTest(unittest.TestCase):
    def setUp(self):
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")
        self.parent = tempfile.mkdtemp(prefix="v77-gate-test-")
        self.addCleanup(shutil.rmtree, self.parent, ignore_errors=True)


class HappyPathTest(GateBaseTest):
    def test_a_complete_release_passes(self):
        root = _stage(self.parent)
        r = GATE.validate_release(root)
        self.assertTrue(r["ok"], r["failures"])
        self.assertEqual(r["failures"], [])

    def test_the_report_is_machine_readable(self):
        root = _stage(self.parent)
        r = GATE.validate_release(root)
        json.dumps(r)
        for key in ("gate", "gate_version", "tiles_dir", "checked_at", "census",
                    "ok", "failures", "warnings", "release_id", "observed",
                    "manifest", "duration_s"):
            self.assertIn(key, r)

    def test_the_report_carries_the_provenance_forward(self):
        """The publisher logs this; it must name the source and the generator."""
        root = _stage(self.parent)
        r = GATE.validate_release(root)
        self.assertEqual(r["manifest"]["source"]["region"], "qatar")
        self.assertEqual(r["manifest"]["generator"]["repo_sha"], SHA)
        self.assertIn("coverage", r["manifest"])


class FailurePathTest(GateBaseTest):
    def test_the_died_halfway_transfer_is_caught(self):
        root = _stage(self.parent, spec={14: DOHA, 15: DOHA})
        shutil.rmtree(os.path.join(root, "15"))
        r = GATE.validate_release(root, census=False)
        self.assertFalse(r["ok"])
        codes = _codes(r)
        self.assertIn("tree_digest_mismatch", codes)
        self.assertIn("tile_missing", codes)
        self.assertIn("zoom_range_mismatch", codes)

    def test_the_cp_r_merge_is_caught(self):
        """A tile from the PREVIOUS bake surviving into this release."""
        root = _stage(self.parent)
        _write_tile(root, 14, 99999, 99999, b"stale-from-the-last-bake")
        r = GATE.validate_release(root, census=False)
        self.assertFalse(r["ok"])
        self.assertIn("tile_extra", _codes(r))

    def test_a_mutated_tile_is_caught(self):
        root = _stage(self.parent)
        victim = next(iter_tile_paths(root))
        with open(os.path.join(root, victim), "wb") as fh:
            fh.write(b"corrupted")
        r = GATE.validate_release(root, census=False)
        self.assertFalse(r["ok"])
        self.assertIn("tile_digest_mismatch", _codes(r))

    def test_a_release_with_no_manifest_is_refused(self):
        root = _stage(self.parent)
        os.remove(os.path.join(root, "RELEASE.json"))
        r = GATE.validate_release(root, census=False)
        self.assertFalse(r["ok"])
        self.assertIn("manifest_missing", _codes(r))

    def test_a_corrupt_tile_is_caught_by_the_census(self):
        root = _stage(self.parent)
        victim = next(iter_tile_paths(root))
        with open(os.path.join(root, victim), "wb") as fh:
            fh.write(b"\xff\xfe\xfd not an mvt")
        r = GATE.validate_release(root, census=True)
        self.assertFalse(r["ok"])
        self.assertTrue({"undecodable_tile", "tile_digest_mismatch"} & _codes(r))


class PublishabilityTest(GateBaseTest):
    def test_a_dirty_release_passes_normally_but_fails_the_publish_gate(self):
        """Fine on a bench and on the emulator; not reproducible in production."""
        root = _stage(self.parent, dirty=True)
        self.assertTrue(GATE.validate_release(root, census=False)["ok"])

        r = GATE.validate_release(root, census=False, require_publishable=True)
        self.assertFalse(r["ok"])
        self.assertIn("release_not_publishable", _codes(r))
        self.assertIn("UNCOMMITTED", r["failures"][0]["detail"])

    def test_a_clean_release_satisfies_the_publish_gate(self):
        root = _stage(self.parent, dirty=False)
        r = GATE.validate_release(root, census=False, require_publishable=True)
        self.assertTrue(r["ok"], r["failures"])


class BuildingClaimTest(GateBaseTest):
    def test_expect_buildings_fails_on_a_release_with_none(self):
        """Production's actual state today, and the mistake to make impossible.

        Manifest and tiles agree perfectly — they both say zero. Consistency
        alone would pass this, which is why the 3D claim needs its own check.
        """
        root = _stage(self.parent, spec={14: NO_BUILDINGS})
        self.assertTrue(GATE.validate_release(root)["ok"])

        r = GATE.validate_release(root, expect_buildings=True)
        self.assertFalse(r["ok"])
        self.assertIn("no_buildings_present", _codes(r))

    def test_expect_buildings_fails_when_nothing_can_be_extruded(self):
        """V7.6 fabric without a single stated height is not a 3D release."""
        root = _stage(self.parent, spec={14: FABRIC_ONLY})
        r = GATE.validate_release(root, expect_buildings=True)
        self.assertFalse(r["ok"])
        self.assertIn("no_extrudable_buildings", _codes(r))

    def test_expect_buildings_is_satisfied_by_a_real_3d_release(self):
        root = _stage(self.parent, spec={14: DOHA})
        r = GATE.validate_release(root, expect_buildings=True)
        self.assertTrue(r["ok"], r["failures"])

    def test_a_3d_claim_cannot_be_certified_without_the_census(self):
        """Refuse rather than pass: the check literally did not run."""
        root = _stage(self.parent, spec={14: DOHA})
        r = GATE.validate_release(root, census=False, expect_buildings=True)
        self.assertFalse(r["ok"])
        self.assertIn("buildings_unverifiable", _codes(r))


class EmptyTileTest(GateBaseTest):
    def test_empty_tiles_are_tolerated_below_the_ceiling(self):
        """Qatar is mostly coastline; empty ocean tiles are correct output."""
        root = _stage(self.parent)
        r = GATE.validate_release(root, census=False, max_empty_ratio=0.5)
        self.assertTrue(r["ok"], r["failures"])

    def test_a_tree_of_mostly_empty_tiles_is_refused(self):
        parent = self.parent
        rid = format_release_id(now_s=T0, git_sha=SHA)
        root = os.path.join(parent, rid)
        os.makedirs(root)
        _bake(root, {14: DOHA})
        for i in range(40):
            _write_tile(root, 14, 12345, i, b"")
        sc = scan_tree(root, census=True)
        m = build_manifest(root, release_id=rid, source={}, generator={},
                           input_config={}, scan=sc)
        write_digests(root, sc["entries"])
        write_manifest(root, m)

        r = GATE.validate_release(root, census=False, max_empty_ratio=0.10)
        self.assertFalse(r["ok"])
        self.assertIn("too_many_empty_tiles", _codes(r))


class GateDisciplineTest(GateBaseTest):
    def test_the_gate_never_touches_the_network(self):
        """A gate production can satisfy is not a pre-publication gate."""
        root = _stage(self.parent)
        real = socket.socket

        def deny(*a, **k):
            raise AssertionError("the gate attempted a network connection")

        socket.socket = deny
        try:
            r = GATE.validate_release(root, census=True)
        finally:
            socket.socket = real
        self.assertTrue(r["ok"], r["failures"])

    def test_the_gate_never_modifies_the_release(self):
        """A release is immutable; a checker that edits its subject is not one."""
        root = _stage(self.parent)
        before = tree_digest(scan_tree(root, census=False)["entries"])
        listing_before = sorted(os.listdir(root))

        GATE.validate_release(root, census=True)
        GATE.validate_release(root, census=True, expect_buildings=True)
        GATE.validate_release(root, census=False, require_publishable=True)

        after = tree_digest(scan_tree(root, census=False)["entries"])
        self.assertEqual(before, after)
        self.assertEqual(listing_before, sorted(os.listdir(root)))

    def test_skipping_the_census_is_announced_as_a_warning(self):
        """A cheap check must never be mistaken for a complete one."""
        root = _stage(self.parent)
        r = GATE.validate_release(root, census=False)
        self.assertFalse(r["census"])
        self.assertIn("census_skipped", {w["code"] for w in r["warnings"]})

    def test_the_gate_is_deterministic(self):
        root = _stage(self.parent)
        a = GATE.validate_release(root, census=True)
        b = GATE.validate_release(root, census=True)
        self.assertEqual(a["ok"], b["ok"])
        self.assertEqual(a["observed"]["tree_digest"], b["observed"]["tree_digest"])
        self.assertEqual(_codes(a), _codes(b))


class ExitCodeTest(GateBaseTest):
    """exit 1 (the release failed) must never be confused with exit 2 (the
    gate could not run). The publisher branches on this, and a broken check
    that reports 'failed release' looks like a decision instead of an outage."""

    def test_a_good_release_exits_zero(self):
        root = _stage(self.parent)
        self.assertEqual(GATE.main(["--tiles", root, "--quiet"]), 0)

    def test_a_failed_release_exits_one(self):
        root = _stage(self.parent)
        _write_tile(root, 14, 99999, 99999, b"stale")
        self.assertEqual(
            GATE.main(["--tiles", root, "--no-census", "--quiet"]), 1)

    def test_an_unrunnable_gate_exits_TWO(self):
        missing = os.path.join(self.parent, "no-such-release")
        self.assertEqual(GATE.main(["--tiles", missing, "--quiet"]), 2)

    def test_an_unreadable_manifest_exits_TWO_not_one(self):
        root = _stage(self.parent)
        bad = os.path.join(self.parent, "bad.json")
        with open(bad, "w", encoding="utf-8") as fh:
            fh.write("{not json")
        self.assertEqual(
            GATE.main(["--tiles", root, "--manifest", bad, "--quiet"]), 2)

    def test_the_json_report_is_written_where_asked(self):
        root = _stage(self.parent)
        out = os.path.join(self.parent, "reports", "gate.json")
        GATE.main(["--tiles", root, "--report", out, "--quiet"])
        with open(out, encoding="utf-8") as fh:
            doc = json.load(fh)
        self.assertTrue(doc["ok"])
        self.assertEqual(doc["release_id"], os.path.basename(root))

    def test_the_report_is_written_even_when_the_release_FAILS(self):
        """The failure report is the artifact an operator needs most."""
        root = _stage(self.parent)
        _write_tile(root, 14, 99999, 99999, b"stale")
        out = os.path.join(self.parent, "fail.json")
        rc = GATE.main(["--tiles", root, "--no-census",
                        "--report", out, "--quiet"])
        self.assertEqual(rc, 1)
        with open(out, encoding="utf-8") as fh:
            doc = json.load(fh)
        self.assertFalse(doc["ok"])
        self.assertIn("tile_extra", {f["code"] for f in doc["failures"]})


if __name__ == "__main__":
    unittest.main()
