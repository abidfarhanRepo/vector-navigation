"""Tests for the tile release manifest — V7.7's first artifact.

WHAT THESE TESTS ARE ACTUALLY DEFENDING
---------------------------------------
Production has served a basemap for months whose advertised version is a
filesystem mtime (V7.7 reconnaissance, `recon/04-version-is-an-mtime.txt`).
That number cannot survive a copy, advances on a partial write, and is attached
to a `?v=` that binds nothing — production returns byte-identical responses for
`?v=1` and `?v=2`.

So the tests below are mostly about **detection of things that previously went
undetected**, not about arithmetic:

  * a tree that lost a tile must fail, because that is what a died-halfway
    `cp -r` looks like and `max(mtime)` could not see it;
  * a tree that GAINED a tile must also fail, because `cp -r` MERGES and a
    stale tile from the previous bake survives forever;
  * a zoom that lost tiles must fail by name, because a partial copy landing
    only z6..z13 silently re-advertises `maxzoom: 13` and the driving view
    degrades with nothing reporting it;
  * a sample must be reproducible across processes, or the manifest is not a
    description of the release but of the run that happened to produce it.

The census assertions use the real encoder, so a tile in these tests is a real
MVT tile and not a stand-in. Where the reference decoder is unavailable the
census tests skip rather than pretend.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_tile_gen.release import (  # noqa: E402
    DIGESTS_FILENAME,
    RELEASE_FILENAME,
    SCHEMA_VERSION,
    build_manifest,
    canonical_json,
    choose_sample,
    format_release_id,
    git_head,
    is_publishable_release_id,
    is_valid_release_id,
    iter_tile_paths,
    manifest_digest,
    parse_release_id,
    read_manifest,
    scan_tree,
    tree_digest,
    verify_manifest,
    write_digests,
    write_manifest,
)

try:
    from vector_tile_gen.encode import encode_tile
    from vector_tile_gen.tiles import lonlat_to_tile
    import mapbox_vector_tile  # noqa: F401
    _HAVE_MVT = True
except Exception:  # pragma: no cover - exercised only without the dep
    _HAVE_MVT = False

# A fixed instant, so release ids in these tests are stable strings.
# This is the V7.6 fabric bake's own mtime, kept as the fixture instant so the
# ids in a failure message look like the real thing. UTC, verified:
#   1789819647 -> 2026-09-19T1207Z
T0 = 1_789_819_647.0  # 2026-09-19T12:07:27Z
SHA = "8f4f2b4"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _feat(lon, lat, z, props=None):
    """A real feature dict in the encode pipeline's contract."""
    x, y = lonlat_to_tile(z, lon, lat)
    return {
        "id": f"f-{lon},{lat}",
        "geometry_type": "Point",
        "coordinates": [lon, lat],
        "properties": props or {"kind": "road", "name": "Test Rd"},
        "_z": z, "_x": x, "_y": y,
    }


def _write_tile(root, z, x, y, data):
    d = os.path.join(root, str(z), str(x))
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, f"{y}.mvt")
    with open(path, "wb") as fh:
        fh.write(data)
    return path


def _bake(root, spec):
    """Build a small but REAL tile tree. spec: {z: [(lon, lat, props), ...]}."""
    for z, feats in spec.items():
        by_tile = {}
        for lon, lat, props in feats:
            x, y = lonlat_to_tile(z, lon, lat)
            by_tile.setdefault((x, y), []).append(_feat(lon, lat, z, props))
        for (x, y), fds in by_tile.items():
            _write_tile(root, z, x, y, encode_tile([("basemap", fds)]))
    return root


# Doha, so the numbers in a failure message look like the real thing.
DOHA = [
    (51.5310, 25.2854, {"kind": "road", "name": "Al Corniche"}),
    (51.5333, 25.2867, {"kind": "poi", "name": "Souq Waqif"}),
    (51.5290, 25.2840, {"kind": "building", "height_m": 42.0}),
    (51.5301, 25.2849, {"kind": "building"}),  # fabric: no stated height
]


def _manifest_for(root, **kw):
    kw.setdefault("release_id", format_release_id(now_s=T0, git_sha=SHA))
    kw.setdefault("source", {"kind": "osm-pbf", "extract_sha256": "9e0a7680",
                             "region": "qatar"})
    kw.setdefault("generator", {"repo_sha": SHA, "repo_dirty": False})
    kw.setdefault("input_config", {"zooms": [14], "max_per_tile": 1500})
    return build_manifest(root, **kw)


# ---------------------------------------------------------------------------
# Release identity
# ---------------------------------------------------------------------------

class ReleaseIdTest(unittest.TestCase):
    def test_format_is_readable_and_parses_back(self):
        rid = format_release_id(now_s=T0, git_sha=SHA)
        self.assertEqual(rid, "vector-tiles-2026-09-19T1207Z-8f4f2b4")
        parts = parse_release_id(rid)
        self.assertEqual(parts["git_sha"], SHA)
        self.assertFalse(parts["dirty"])

    def test_dirty_tree_is_visible_in_the_id_itself(self):
        """Not hidden in a field: the id is what gets pasted into a deploy log."""
        rid = format_release_id(now_s=T0, git_sha=SHA, dirty=True)
        self.assertTrue(rid.endswith("-dirty"))
        self.assertTrue(parse_release_id(rid)["dirty"])

    def test_a_dirty_release_is_not_publishable(self):
        """A basemap that cannot be rebuilt from a commit is not auditable."""
        self.assertTrue(is_publishable_release_id(
            format_release_id(now_s=T0, git_sha=SHA)))
        self.assertFalse(is_publishable_release_id(
            format_release_id(now_s=T0, git_sha=SHA, dirty=True)))

    def test_a_non_sha_is_refused_rather_than_formatted(self):
        for bad in ("", "nothex!", "zzzzzzz", "abc"):
            with self.assertRaises(ValueError):
                format_release_id(now_s=T0, git_sha=bad)

    def test_hand_made_directory_names_are_not_release_ids(self):
        """Strictness here is what stops `mkdir tiles-new` becoming a release."""
        for bad in ("tiles", "vector-tiles", "vector-tiles-2026-09-19-8f4f2b4",
                    "vector-tiles-2026-09-19T1927Z", "release-B", ""):
            self.assertFalse(is_valid_release_id(bad), bad)
        with self.assertRaises(ValueError):
            parse_release_id("release-B")

    def test_the_timestamp_is_never_trusted_for_uniqueness(self):
        """Two bakes in one minute from one commit produce the SAME id.

        This is not a bug in the id — it is the reason the publisher must
        enforce uniqueness by refusing to write into an existing directory.
        Pinned here so nobody later 'fixes' it by adding seconds and assumes
        the collision is gone.
        """
        a = format_release_id(now_s=T0, git_sha=SHA)
        b = format_release_id(now_s=T0 + 30, git_sha=SHA)
        self.assertEqual(a, b)

    def test_git_head_is_total_and_conservative_off_a_worktree(self):
        with tempfile.TemporaryDirectory() as tmp:
            head = git_head(tmp)
            self.assertEqual(head["sha"], "")
            # Unidentifiable generator => dirty => not publishable.
            self.assertTrue(head["dirty"])

    def test_git_head_reads_a_real_repository(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))
        if not os.path.isdir(os.path.join(root, ".git")):
            self.skipTest("not a git work tree")
        head = git_head(root)
        self.assertRegex(head["sha"], r"^[0-9a-f]{7,40}$")


# ---------------------------------------------------------------------------
# Canonical serialisation and digests
# ---------------------------------------------------------------------------

class CanonicalJsonTest(unittest.TestCase):
    def test_key_order_does_not_change_the_bytes(self):
        """Otherwise the digest measures Python insertion order, not the release."""
        self.assertEqual(canonical_json({"a": 1, "b": 2}),
                         canonical_json({"b": 2, "a": 1}))

    def test_manifest_digest_excludes_its_own_integrity_block(self):
        m = {"release_id": "x", "coverage": {"tiles_total": 3}}
        d1 = manifest_digest(m)
        m2 = dict(m, integrity={"tree_digest": "whatever"})
        self.assertEqual(d1, manifest_digest(m2))

    def test_manifest_digest_covers_the_claims(self):
        """Tampering with what the manifest SAYS must be detectable too."""
        m = {"release_id": "x", "coverage": {"tiles_total": 3}}
        m2 = {"release_id": "x", "coverage": {"tiles_total": 4}}
        self.assertNotEqual(manifest_digest(m), manifest_digest(m2))


class TreeDigestTest(unittest.TestCase):
    def test_walk_order_does_not_matter(self):
        a = [("14/1/1.mvt", "aa", 10), ("14/1/2.mvt", "bb", 20)]
        self.assertEqual(tree_digest(a), tree_digest(list(reversed(a))))

    def test_detects_removal_addition_mutation_and_truncation(self):
        base = [("14/1/1.mvt", "aa", 10), ("14/1/2.mvt", "bb", 20)]
        d = tree_digest(base)
        self.assertNotEqual(d, tree_digest(base[:1]), "removal undetected")
        self.assertNotEqual(d, tree_digest(base + [("14/1/3.mvt", "cc", 30)]),
                            "addition undetected")
        self.assertNotEqual(d, tree_digest([("14/1/1.mvt", "ZZ", 10), base[1]]),
                            "mutation undetected")
        self.assertNotEqual(d, tree_digest([("14/1/1.mvt", "aa", 9), base[1]]),
                            "truncation undetected")


# ---------------------------------------------------------------------------
# The deterministic probe sample
# ---------------------------------------------------------------------------

class SampleTest(unittest.TestCase):
    def _entries(self, n_per_zoom=5, zooms=(6, 14, 15)):
        return [(f"{z}/{i}/{i}.mvt", f"{z:02d}{i:02d}" * 8, 100 + i)
                for z in zooms for i in range(n_per_zoom)]

    def test_sample_spans_every_zoom(self):
        """A release that dropped a whole zoom must be caught by probing."""
        s = choose_sample(self._entries(), per_zoom=2)
        zooms = {int(item["path"].split("/", 1)[0]) for item in s}
        self.assertEqual(zooms, {6, 14, 15})

    def test_sample_is_stable_within_a_process(self):
        e = self._entries()
        self.assertEqual(choose_sample(e), choose_sample(e))

    def test_sample_is_stable_ACROSS_processes(self):
        """The bug this forbids: selection by `random` or by salted `hash()`.

        Python randomises str hashing per process, so a sample chosen with
        `hash()` differs on every run and the manifest stops being a
        description of the release. Run the selection in two fresh
        interpreters with different hash seeds and require identical output.
        """
        src = (
            "import sys, json;"
            f"sys.path.insert(0, {os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'src')!r});"
            "from vector_tile_gen.release import choose_sample;"
            "e=[(f'{z}/{i}/{i}.mvt', 'd'*64, 100+i) for z in (6,14,15) for i in range(5)];"
            "print(json.dumps([x['path'] for x in choose_sample(e, per_zoom=2)]))"
        )
        outs = []
        for seed in ("0", "12345"):
            env = dict(os.environ, PYTHONHASHSEED=seed)
            r = subprocess.run([sys.executable, "-c", src], capture_output=True,
                               text=True, env=env, timeout=60)
            self.assertEqual(r.returncode, 0, r.stderr)
            outs.append(r.stdout.strip())
        self.assertEqual(outs[0], outs[1],
                         "sample selection is not reproducible across processes")

    def test_zero_byte_tiles_are_never_sampled(self):
        """A 204 proves nothing about which release served it."""
        e = [("14/1/1.mvt", "a" * 64, 0), ("14/1/2.mvt", "b" * 64, 50)]
        s = choose_sample(e, per_zoom=5)
        self.assertEqual([x["path"] for x in s], ["14/1/2.mvt"])


# ---------------------------------------------------------------------------
# Scanning and manifest construction
# ---------------------------------------------------------------------------

class ScanTest(unittest.TestCase):
    def test_only_all_digit_zoom_directories_are_descended(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_tile(tmp, 14, 1, 1, b"x")
            os.makedirs(os.path.join(tmp, "releases", "9"), exist_ok=True)
            with open(os.path.join(tmp, "releases", "9", "1.mvt"), "wb") as fh:
                fh.write(b"y")
            self.assertEqual(list(iter_tile_paths(tmp)), ["14/1/1.mvt"])

    def test_missing_directory_scans_as_empty_rather_than_raising(self):
        sc = scan_tree(os.path.join(tempfile.gettempdir(), "vector-no-such-dir-xyz"),
                       census=False)
        self.assertEqual(sc["tiles_total"], 0)

    def test_zero_byte_tiles_are_counted_not_failed(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_tile(tmp, 14, 1, 1, b"")
            sc = scan_tree(tmp, census=False)
            self.assertEqual(sc["tiles_total"], 1)
            self.assertEqual(sc["empty_tiles"], 1)
            self.assertEqual(sc["undecodable"], [])


class ManifestBuildTest(unittest.TestCase):
    def setUp(self):
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")

    def test_manifest_describes_a_real_bake(self):
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            m = _manifest_for(tmp)
            self.assertEqual(m["schema_version"], SCHEMA_VERSION)
            self.assertEqual(m["coverage"]["minzoom"], 14)
            self.assertEqual(m["coverage"]["maxzoom"], 14)
            self.assertEqual(m["layers"]["source_layer"], "basemap")
            self.assertEqual(m["layers"]["building_features"], 2)
            self.assertEqual(m["layers"]["building_features_with_height_m"], 1)
            self.assertTrue(m["integrity"]["tree_digest"])
            self.assertTrue(m["integrity"]["sample"])

    def test_building_counts_are_TILE_INSTANCES_not_buildings_in_qatar(self):
        """The naming trap this pins, found by running the code at real scale.

        A footprint is emitted into every tile it touches and at every zoom it
        is visible at, so one building is counted many times. Measured on a
        real bake: 1,382 feature instances for 798 distinct ids.

        This matters because V7.6 reports SOURCE-level counts ("975 heights in,
        975 out"; 189,866 footprints). A field called `buildings_total` invites
        a reader to compare a tile-level number against a source-level one and
        conclude the pipeline lost or invented buildings when it did neither.

        Here the SAME two buildings are baked at two zooms: four instances,
        two distinct ids.
        """
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA, 15: DOHA})
            m = _manifest_for(tmp)
            self.assertEqual(m["layers"]["building_features"], 4)
            self.assertEqual(m["layers"]["distinct_building_ids"], 2)
            self.assertEqual(m["layers"]["building_features_with_height_m"], 2)

    def test_zoom_range_is_declared_from_what_was_actually_baked(self):
        """The whole point: the server must stop inferring it from dir names."""
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA, 15: DOHA})
            m = _manifest_for(tmp)
            self.assertEqual(m["coverage"]["minzoom"], 14)
            self.assertEqual(m["coverage"]["maxzoom"], 15)
            self.assertEqual(set(m["coverage"]["tiles_per_zoom"]), {"14", "15"})

    def test_an_empty_tree_cannot_be_described(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                _manifest_for(tmp)

    def test_a_manifest_without_a_census_is_refused(self):
        """A claim the builder never checked is worse than no claim."""
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            sc = scan_tree(tmp, census=False)
            with self.assertRaises(ValueError):
                _manifest_for(tmp, scan=sc)

    def test_an_invalid_release_id_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            with self.assertRaises(ValueError):
                _manifest_for(tmp, release_id="release-B")

    def test_write_then_read_round_trips(self):
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            m = _manifest_for(tmp)
            write_manifest(tmp, m)
            self.assertTrue(os.path.exists(os.path.join(tmp, RELEASE_FILENAME)))
            self.assertEqual(read_manifest(tmp)["release_id"], m["release_id"])

    def test_read_manifest_is_total(self):
        """A served tile set is never worth taking down over a version file."""
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(read_manifest(tmp))                      # absent
            with open(os.path.join(tmp, RELEASE_FILENAME), "w") as fh:
                fh.write("{not json")
            self.assertIsNone(read_manifest(tmp))                      # corrupt
            with open(os.path.join(tmp, RELEASE_FILENAME), "w") as fh:
                json.dump([1, 2, 3], fh)
            self.assertIsNone(read_manifest(tmp))                      # wrong type
            with open(os.path.join(tmp, RELEASE_FILENAME), "w") as fh:
                json.dump({"release_id": "release-B"}, fh)
            self.assertIsNone(read_manifest(tmp))                      # bad id


# ---------------------------------------------------------------------------
# Verification — the gate
# ---------------------------------------------------------------------------

class VerifyTest(unittest.TestCase):
    def setUp(self):
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")

    def _staged(self, tmp, spec=None):
        _bake(tmp, spec or {14: DOHA})
        m = _manifest_for(tmp)
        write_digests(tmp, scan_tree(tmp, census=False)["entries"])
        write_manifest(tmp, m)
        return m

    def _codes(self, report):
        return {f["code"] for f in report["failures"]}

    def test_a_good_release_verifies(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            r = verify_manifest(tmp)
            self.assertTrue(r["ok"], r["failures"])
            self.assertEqual(r["failures"], [])

    def test_report_is_machine_readable(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            r = verify_manifest(tmp)
            json.dumps(r)  # must serialise
            for key in ("ok", "release_id", "census", "checked_at",
                        "failures", "observed"):
                self.assertIn(key, r)

    def test_a_missing_manifest_fails_rather_than_passes_vacuously(self):
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("manifest_missing", self._codes(r))

    def test_a_removed_tile_is_caught_and_named(self):
        """The died-halfway transfer. `max(mtime)` could not see this."""
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            victim = next(iter_tile_paths(tmp))
            os.remove(os.path.join(tmp, victim))
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("tree_digest_mismatch", self._codes(r))
            self.assertIn("tile_missing", self._codes(r))
            self.assertTrue(any(victim in f["detail"] for f in r["failures"]))

    def test_a_STALE_tile_left_behind_by_cp_r_is_caught(self):
        """`cp -r` MERGES. A tile from the previous bake survives forever."""
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            _write_tile(tmp, 14, 99999, 99999, b"stale-from-the-last-bake")
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("tile_extra", self._codes(r))

    def test_a_mutated_tile_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            victim = next(iter_tile_paths(tmp))
            with open(os.path.join(tmp, victim), "wb") as fh:
                fh.write(b"corrupt")
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("tile_digest_mismatch", self._codes(r))

    def test_a_partial_copy_that_drops_a_zoom_is_caught_BY_ZOOM(self):
        """The silent-maxzoom regression, which has shipped before.

        Landing only the low zooms re-advertises a smaller maxzoom, MapLibre
        overzooms for a camera at 16.5, and the driving view degrades with
        nothing reporting a failure.
        """
        import shutil
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp, {14: DOHA, 15: DOHA})
            shutil.rmtree(os.path.join(tmp, "15"))
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            codes = self._codes(r)
            self.assertIn("zoom_range_mismatch", codes)
            self.assertIn("zoom_count_mismatch", codes)

    def test_a_truncated_zoom_is_caught_even_when_the_zoom_survives(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp, {14: DOHA, 15: DOHA})
            z15 = [p for p in iter_tile_paths(tmp) if p.startswith("15/")]
            if len(z15) < 2:
                self.skipTest("need >1 tile at z15 for this case")
            os.remove(os.path.join(tmp, z15[0]))
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("zoom_count_mismatch", self._codes(r))

    def test_a_tile_with_BYTES_BUT_NO_LAYERS_is_caught(self):
        """The gap this closes was found by measurement, not by reading code.

        A 4-byte payload carrying only an unknown protobuf field decodes
        successfully to ZERO layers. It is on disk, it is counted, it is
        digested, it is perfectly consistent with its own manifest — and it
        renders as nothing. `validate_tiles._classify` has always called this
        BAD ("no layers"); the census missed it until both were fed the same
        bytes and only one objected.

        This is the Session 50 "streets vanish" class exactly, so it must be a
        failure and not a statistic.
        """
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            victim = next(iter_tile_paths(tmp))
            with open(os.path.join(tmp, victim), "wb") as fh:
                fh.write(b"\x7a\x02\x00\x00")  # field 15, wire 2 — unknown to Tile
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("tile_no_layers", self._codes(r))
            self.assertTrue(any(victim in f["detail"] for f in r["failures"]))

    def test_a_zero_byte_tile_is_NOT_reported_as_a_no_layer_failure(self):
        """The counterpart: empty ocean is legitimate and must stay legitimate.

        A zero-byte tile is the bake's correct output for open sea and desert.
        Confusing it with the blank-tile defect above would fail every honest
        release that covers water — and Qatar is mostly coastline.
        """
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            _write_tile(tmp, 14, 99999, 99999, b"")
            sc = scan_tree(tmp, census=True)
            self.assertEqual(sc["no_layer_tiles"], [])
            self.assertEqual(sc["empty_tiles"], 1)

    def test_a_wrong_layer_name_is_caught(self):
        """The 'streets vanish' class: the style filters on `basemap`."""
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            victim = next(iter_tile_paths(tmp))
            z, x, _ = victim.split("/")
            with open(os.path.join(tmp, victim), "wb") as fh:
                fh.write(encode_tile([("vector", [_feat(51.531, 25.2854, int(z))])]))
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("layer_unexpected", self._codes(r))

    def test_an_overstated_3d_claim_is_caught(self):
        """A release claiming buildings its tiles do not contain."""
        with tempfile.TemporaryDirectory() as tmp:
            m = self._staged(tmp)
            m["layers"]["building_features_with_height_m"] = 975
            write_manifest(tmp, m)
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("buildings_mismatch", self._codes(r))

    def test_an_overstated_DISTINCT_building_claim_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = self._staged(tmp)
            m["layers"]["distinct_building_ids"] = 189866
            write_manifest(tmp, m)
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("buildings_mismatch", self._codes(r))

    def test_a_census_free_check_does_not_pretend_to_check_layers(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = self._staged(tmp)
            m["layers"]["building_features_with_height_m"] = 975
            write_manifest(tmp, m)
            r = verify_manifest(tmp, census=False)
            self.assertFalse(r["census"])
            self.assertNotIn("buildings_mismatch", self._codes(r))

    def test_a_manifest_in_the_wrong_release_directory_is_caught(self):
        with tempfile.TemporaryDirectory() as parent:
            other = format_release_id(now_s=T0 + 86400, git_sha="a1b2c3d")
            tmp = os.path.join(parent, other)
            os.makedirs(tmp)
            self._staged(tmp)
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("release_id_mismatch", self._codes(r))

    def test_an_unsupported_schema_is_refused_not_guessed_at(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = self._staged(tmp)
            m["schema_version"] = SCHEMA_VERSION + 1
            write_manifest(tmp, m)
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("schema_unsupported", self._codes(r))

    def test_a_tampered_headline_count_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = self._staged(tmp)
            m["coverage"]["tiles_total"] = 18311
            write_manifest(tmp, m)
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("tiles_total_mismatch", self._codes(r))

    def test_a_sample_pointing_at_a_missing_tile_is_caught(self):
        """A probe target that cannot prove anything is itself a defect."""
        with tempfile.TemporaryDirectory() as tmp:
            m = self._staged(tmp)
            m["integrity"]["sample"] = [
                {"path": "14/0/0.mvt", "sha256": "0" * 64, "bytes": 1}]
            write_manifest(tmp, m)
            r = verify_manifest(tmp)
            self.assertFalse(r["ok"])
            self.assertIn("sample_invalid", self._codes(r))

    def test_verification_needs_no_network_and_no_production(self):
        """Stated as a test so it cannot quietly acquire a dependency."""
        with tempfile.TemporaryDirectory() as tmp:
            self._staged(tmp)
            import socket
            real = socket.socket

            def deny(*a, **k):
                raise AssertionError("verification attempted a network connection")

            socket.socket = deny
            try:
                self.assertTrue(verify_manifest(tmp)["ok"])
            finally:
                socket.socket = real


class DigestsFileTest(unittest.TestCase):
    def setUp(self):
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")

    def test_digests_file_is_written_sorted_and_parseable(self):
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA, 15: DOHA})
            entries = scan_tree(tmp, census=False)["entries"]
            path = write_digests(tmp, entries)
            self.assertTrue(path.endswith(DIGESTS_FILENAME))
            with open(path, encoding="utf-8") as fh:
                lines = fh.read().splitlines()
            self.assertEqual(len(lines), len(entries))
            paths = [ln.split("\t")[0] for ln in lines]
            self.assertEqual(paths, sorted(paths))
            for ln in lines:
                relpath, digest, nbytes = ln.split("\t")
                self.assertRegex(digest, r"^[0-9a-f]{64}$")
                int(nbytes)

    def test_the_digests_file_is_not_mistaken_for_a_tile(self):
        with tempfile.TemporaryDirectory() as tmp:
            _bake(tmp, {14: DOHA})
            before = scan_tree(tmp, census=False)
            write_digests(tmp, before["entries"])
            write_manifest(tmp, _manifest_for(tmp))
            after = scan_tree(tmp, census=False)
            self.assertEqual(before["tiles_total"], after["tiles_total"])
            self.assertEqual(tree_digest(before["entries"]),
                             tree_digest(after["entries"]))


# ---------------------------------------------------------------------------
# The epoch — the integer an old client still reads
# ---------------------------------------------------------------------------

class EpochTest(unittest.TestCase):
    """`RELEASE.json` states the epoch instead of leaving it to be inferred.

    The V7.7 tile URL contract is `?v=<release_id>`, but a client built before
    it busts its cache on the `epoch` integer from `GET /tiles/version` and on
    nothing else. Those clients keep working only while the integer keeps
    changing per release. Today it does change, by accident: production derives
    it from `max(mtime)` over the tile tree, which is not a function of the
    content, advances on a partial write, and cannot survive a `cp -r`.

    Stating it here makes it a property of the RELEASE — one the manifest
    carries, the tile server reads, and the digest covers.

    These tests build manifests through the `scan=` seam rather than off a real
    bake, because the epoch is a property of how the manifest is SHAPED and not
    of what is in the tiles. That keeps them running where the optional MVT
    decoder is not installed, which is also where the rest of this file's
    manifest tests skip. Nothing here asserts on the layer census, which is
    empty in these manifests for exactly that reason.
    """

    def _scan(self, tmp):
        """A real scan of a real tree, marked censused.

        The tiles are opaque bytes: `build_manifest` refuses a manifest without
        a census, and this supplies the flag without requiring the decoder.
        """
        _write_tile(tmp, 14, 9765, 6551, b"\x1a\x0512345")
        _write_tile(tmp, 15, 19531, 13102, b"\x1a\x0567890")
        return dict(scan_tree(tmp, census=False), censused=True)

    def _manifest(self, tmp, **kw):
        kw.setdefault("scan", self._scan(tmp))
        kw.setdefault("release_id", format_release_id(now_s=T0, git_sha=SHA))
        kw.setdefault("source", {"kind": "osm-pbf", "extract_sha256": "9e0a7680",
                                 "region": "qatar"})
        kw.setdefault("generator", {"repo_sha": SHA, "repo_dirty": False})
        kw.setdefault("input_config", {"zooms": [14, 15]})
        return build_manifest(tmp, **kw)

    def test_the_manifest_states_an_epoch(self):
        with tempfile.TemporaryDirectory() as tmp:
            m = self._manifest(tmp)
            self.assertIn("epoch", m)
            self.assertIsInstance(m["epoch"], int)
            self.assertFalse(isinstance(m["epoch"], bool))
            self.assertGreater(m["epoch"], 1_700_000_000)

    def test_the_epoch_and_the_iso_stamp_are_the_same_instant(self):
        """Two representations of one moment, and they must not drift.

        They are rendered from a single `now_s` precisely so that a clock tick
        between two `time` calls cannot make a release's own manifest disagree
        with itself about when it was made — a disagreement nobody would notice
        until it was being used to decide which of two releases is older.
        """
        import calendar
        import time as _time
        with tempfile.TemporaryDirectory() as tmp:
            m = self._manifest(tmp)
            self.assertEqual(
                m["epoch"],
                calendar.timegm(_time.strptime(m["bake"]["manifested_at"],
                                               "%Y-%m-%dT%H:%M:%SZ")))

    def test_the_epoch_is_not_the_filesystems(self):
        """The whole defect, stated as a property.

        The tiles here carry an mtime far from the manifest's epoch. If these
        ever agree by construction, the epoch has gone back to naming the
        moment files were copied.
        """
        with tempfile.TemporaryDirectory() as tmp:
            scan = self._scan(tmp)
            stamped = 1_700_000_000
            for dirpath, _dirs, files in os.walk(tmp):
                for f in files:
                    os.utime(os.path.join(dirpath, f), (stamped, stamped))
            m = self._manifest(tmp, scan=scan)
            self.assertNotEqual(stamped, m["epoch"])

    def test_two_releases_do_not_share_an_epoch(self):
        """Distinct releases, distinct integers — the old client's contract.

        Uniqueness is enforced by the publisher refusing to write into an
        existing `releases/<id>`, never assumed from the clock. Since the id is
        minute-resolution, two releases that would share a second must first
        have failed to share a directory name.
        """
        from vector_tile_gen import release as rel
        real_time = rel.time.time
        try:
            with tempfile.TemporaryDirectory() as tmp:
                rel.time.time = lambda: T0
                first = self._manifest(tmp)
            with tempfile.TemporaryDirectory() as tmp:
                rel.time.time = lambda: T0 + 3600
                second = self._manifest(
                    tmp, release_id=format_release_id(now_s=T0 + 3600, git_sha=SHA))
        finally:
            rel.time.time = real_time

        self.assertNotEqual(first["release_id"], second["release_id"])
        self.assertNotEqual(first["epoch"], second["epoch"])
        self.assertLess(first["epoch"], second["epoch"])

    def test_the_epoch_is_covered_by_the_manifest_digest(self):
        """Tampering with the claim has to be as detectable as tampering with
        the tiles.

        The epoch decides what a whole generation of clients will and will not
        re-fetch. A field that steers cache behaviour for every old client on
        the road, sitting outside the digest that protects every other claim in
        the document, would be the one soft spot in it.
        """
        with tempfile.TemporaryDirectory() as tmp:
            m = self._manifest(tmp)
            self.assertEqual(m["integrity"]["manifest_sha256"],
                             manifest_digest(m))
            tampered = dict(m, epoch=m["epoch"] + 1)
            self.assertNotEqual(manifest_digest(tampered),
                                m["integrity"]["manifest_sha256"])

    def test_a_manifest_with_an_epoch_still_reads_back(self):
        """`read_manifest` is total and stays that way."""
        with tempfile.TemporaryDirectory() as tmp:
            m = self._manifest(tmp)
            write_manifest(tmp, m)
            back = read_manifest(tmp)
            self.assertIsNotNone(back)
            self.assertEqual(m["epoch"], back["epoch"])
            self.assertEqual(m["release_id"], back["release_id"])


if __name__ == "__main__":
    unittest.main()
