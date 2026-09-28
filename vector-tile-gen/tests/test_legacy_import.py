"""The imported legacy snapshot: its name, and the import that creates it.

WHY THE NAME NEEDED ITS OWN GRAMMAR
-----------------------------------
A bake release id embeds the commit it was built from. A pre-V7.7 tile tree has
no commit — production's was `cp -r`'d into a Docker volume from an unknown
source at an unknown time, and its mtimes name the copy rather than the bake.

The first proposal was to reuse the bake grammar and put a digest prefix in the
sha slot: `vector-tiles-<stamp>-ca07394`. That is well-formed, deterministic,
and *false*. It asserts "baked from commit ca07394", and the assertion would be
false permanently, in a string that ends up in deploy logs, screenshots and bug
reports long after anybody remembers it was a stand-in.

So the import gets a grammar that says what it is, and the tests below hold the
three properties that matter: it is recognisably an import, it collides only
when the content is identical, and it claims no provenance it does not have.

WHAT THE IMPORTER MUST NEVER DO
-------------------------------
Touch the flat tree. Production is serving it, and the entire rollback for the
migration is "the old TILE_DIR still finds it, byte for byte". Several tests
below assert that on the failure paths specifically, because that is where a
cleanup routine would be tempted to tidy up the wrong directory.
"""
import importlib.util
import json
import os
import shutil
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_PKG = os.path.join(os.path.dirname(_HERE), "src")
if _PKG not in sys.path:
    sys.path.insert(0, _PKG)

_SPEC = importlib.util.spec_from_file_location(
    "vector_import_legacy_tree",
    os.path.join(os.path.dirname(_HERE), "scripts", "import_legacy_tree.py"))
imp = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(imp)

from vector_tile_gen.release import (  # noqa: E402
    LEGACY_DIGEST_CHARS,
    LEGACY_ID_RE,
    format_legacy_id,
    format_release_id,
    is_legacy_release_id,
    is_publishable_release_id,
    is_valid_release_id,
    parse_release_id,
    read_manifest,
    scan_tree,
    tree_digest,
    verify_manifest,
)

T0 = 1_789_819_647.0  # 2026-09-19T12:07:27Z
DIGEST = "ca07394aeab5d58a01627f1d05bbb06e88571022c1cf4a9519d83bd20a8a29ce"


class LegacyIdTest(unittest.TestCase):

    def test_the_id_says_it_is_an_import(self):
        rid = format_legacy_id(now_s=T0, tree_digest=DIGEST)

        self.assertTrue(rid.startswith("legacy-import-"), rid)
        self.assertTrue(is_legacy_release_id(rid))
        self.assertEqual("legacy-import", parse_release_id(rid)["kind"])
        self.assertEqual("imported", parse_release_id(rid)["provenance"])

    def test_the_id_claims_no_commit(self):
        """The whole reason the grammar exists."""
        parsed = parse_release_id(format_legacy_id(now_s=T0, tree_digest=DIGEST))

        self.assertEqual("", parsed["git_sha"])
        self.assertFalse(parsed["dirty"])

    def test_the_id_carries_the_tree_identity(self):
        rid = format_legacy_id(now_s=T0, tree_digest=DIGEST)

        self.assertEqual(DIGEST[:LEGACY_DIGEST_CHARS],
                         parse_release_id(rid)["tree_digest_prefix"])
        self.assertIn(DIGEST[:LEGACY_DIGEST_CHARS], rid)

    def test_the_id_is_timestamped_to_the_second(self):
        """An import is a one-off that can legitimately be retried at once.

        At minute resolution two attempts against the same tree would collide
        on BOTH components, and the publisher's "refuse an existing directory"
        rule would turn a retry into a confusing failure rather than a clear
        one.
        """
        a = format_legacy_id(now_s=T0, tree_digest=DIGEST)
        b = format_legacy_id(now_s=T0 + 1, tree_digest=DIGEST)

        self.assertNotEqual(a, b)
        self.assertRegex(a, r"-\d{8}T\d{6}Z-")

    def test_identical_trees_at_one_instant_share_an_id(self):
        """Which is correct, not a collision: the content IS the same."""
        self.assertEqual(format_legacy_id(now_s=T0, tree_digest=DIGEST),
                         format_legacy_id(now_s=T0, tree_digest=DIGEST))

    def test_different_trees_never_share_an_id(self):
        other = "f" * 64
        self.assertNotEqual(format_legacy_id(now_s=T0, tree_digest=DIGEST),
                            format_legacy_id(now_s=T0, tree_digest=other))

    def test_the_id_is_url_safe_with_no_escaping(self):
        """It is used verbatim as the `?v=` cache token."""
        rid = format_legacy_id(now_s=T0, tree_digest=DIGEST)

        # `[A-Za-z0-9-]`: the T and Z come from the timestamp, exactly as
        # they do in the bake grammar. Every character is RFC 3986
        # *unreserved*, which is the property that matters — quoting it must
        # be a no-op, or two spellings of one release would partition the
        # cache twice.
        self.assertRegex(rid, r"^[A-Za-z0-9-]+$")
        import urllib.parse
        self.assertEqual(rid, urllib.parse.quote(rid, safe="-"))

    def test_a_short_or_bogus_digest_is_refused(self):
        for bad in ("", "xyz", "abc123", "ca07394", None, "not-hex-at-all"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    format_legacy_id(now_s=T0, tree_digest=bad)

    def test_an_uppercase_digest_is_normalised_rather_than_refused(self):
        """Same value, one spelling.

        Hex case carries no information, and a release id that differed only
        in case would be two ids for one tree — and two `?v=` tokens for one
        set of bytes, which is the cache-partitioning failure the whole token
        design exists to avoid.
        """
        upper = format_legacy_id(now_s=T0, tree_digest=DIGEST.upper())
        lower = format_legacy_id(now_s=T0, tree_digest=DIGEST)

        self.assertEqual(lower, upper)
        self.assertTrue(is_legacy_release_id(upper))

    def test_the_grammar_accepts_only_what_it_should(self):
        good = ["legacy-import-20260920T041515Z-ca07394aeab5d58a",
                "legacy-import-20260920T041515Z-" + DIGEST]
        bad = ["legacy-import-20260920T041515Z-ca07394",          # too short
               "legacy-import-2026-09-20T0415Z-ca07394aeab5d58a",  # bake stamp
               "legacy-import-20260920T041515Z-CA07394AEAB5D58A",  # uppercase
               "legacy-20260920T041515Z-ca07394aeab5d58a",         # wrong prefix
               "legacy-import-20260920T041515Z-",                  # no digest
               "vector-tiles-2026-09-19T2004Z-4d627cd"]            # a bake
        for rid in good:
            with self.subTest(rid=rid):
                self.assertTrue(LEGACY_ID_RE.match(rid))
        for rid in bad:
            with self.subTest(rid=rid):
                self.assertFalse(LEGACY_ID_RE.match(rid))

    def test_both_grammars_are_valid_release_ids(self):
        bake = format_release_id(now_s=T0, git_sha="4d627cd")
        legacy = format_legacy_id(now_s=T0, tree_digest=DIGEST)

        for rid in (bake, legacy):
            with self.subTest(rid=rid):
                self.assertTrue(is_valid_release_id(rid))
        self.assertEqual("bake", parse_release_id(bake)["kind"])
        self.assertFalse(is_legacy_release_id(bake))

    def test_an_import_is_publishable_but_a_dirty_bake_is_not(self):
        """The rule is 'claim no provenance you do not have', not 'have one'.

        A `-dirty` bake names a commit whose tree it was not built from. An
        import names no commit at all. The first lies; the second declines to
        answer — and production has been serving exactly such a tree for
        months.
        """
        self.assertTrue(is_publishable_release_id(
            format_legacy_id(now_s=T0, tree_digest=DIGEST)))
        self.assertFalse(is_publishable_release_id(
            format_release_id(now_s=T0, git_sha="4d627cd", dirty=True)))

    def test_a_malformed_id_still_raises(self):
        for bad in ("", "todays-tiles-final-v2", "legacy-import", None):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    parse_release_id(bad)


class ImportLegacyTreeTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="vector-legacy-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.volume = os.path.join(self.tmp, "vector-data-tiles")
        self._flat(self.volume)

    def _flat(self, root, zooms=range(6, 16)):
        for z in zooms:
            d = os.path.join(root, str(z), "21074")
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, "14003.mvt"), "wb") as fh:
                fh.write(b"\x1a\x05legacy" + bytes([z]))
        with open(os.path.join(root, "VERSION.json"), "w") as fh:
            json.dump({"epoch": 1789378960}, fh)
        return root

    def _digest(self, root):
        return tree_digest(scan_tree(root, census=False)["entries"])

    # -- the happy path ---------------------------------------------------

    def test_a_flat_tree_becomes_a_named_active_release(self):
        before = self._digest(self.volume)

        report = imp.import_legacy_tree(self.volume)

        self.assertTrue(report["ok"], report["failures"])
        self.assertTrue(is_legacy_release_id(report["release_id"]))
        self.assertTrue(report["activated"])
        self.assertEqual(os.path.join("releases", report["release_id"]),
                         os.readlink(os.path.join(self.volume, "current")))
        self.assertEqual(before, report["source"]["tree_digest"])
        self.assertEqual(before, report["copy"]["tree_digest"],
                         "the copy must be the same tree")

    def test_the_release_verifies_through_the_gate(self):
        report = imp.import_legacy_tree(self.volume)
        release = os.path.join(self.volume, "releases", report["release_id"])

        verdict = verify_manifest(release, census=False)

        self.assertTrue(verdict["ok"], verdict["failures"])

    def test_the_manifest_states_that_provenance_is_unknown(self):
        """It must not look like a bake in a log six months from now."""
        report = imp.import_legacy_tree(self.volume)
        manifest = read_manifest(os.path.join(self.volume, "releases",
                                              report["release_id"]))

        self.assertEqual("imported-legacy", manifest["source"]["kind"])
        self.assertIn("unknown", manifest["source"]["note"])
        self.assertEqual("", manifest["generator"]["repo_sha"])
        self.assertIsNone(manifest["generator"]["repo_dirty"])
        self.assertTrue(manifest["input_config"]["imported"])

    def test_the_flat_tree_is_copied_not_moved(self):
        """The migration's rollback depends on this, entirely."""
        imp.import_legacy_tree(self.volume)

        for z in range(6, 16):
            path = os.path.join(self.volume, str(z), "21074", "14003.mvt")
            with self.subTest(zoom=z):
                self.assertTrue(os.path.isfile(path))
                with open(path, "rb") as fh:
                    self.assertEqual(b"\x1a\x05legacy" + bytes([z]), fh.read())

    def test_the_sidecar_travels_with_the_release(self):
        """`VERSION.json` is not a tile and is not lost either."""
        report = imp.import_legacy_tree(self.volume)
        release = os.path.join(self.volume, "releases", report["release_id"])

        self.assertIn("VERSION.json", report.get("sidecars", []))
        self.assertTrue(os.path.isfile(os.path.join(release, "VERSION.json")))

    def test_previous_is_not_created(self):
        """One release means no rollback target, and inventing one is a lie."""
        imp.import_legacy_tree(self.volume)

        self.assertFalse(os.path.lexists(os.path.join(self.volume, "previous")))

    def test_no_activate_builds_the_release_without_the_pointer(self):
        report = imp.import_legacy_tree(self.volume, activate=False)

        self.assertTrue(report["ok"], report["failures"])
        self.assertFalse(report["activated"])
        self.assertFalse(os.path.lexists(os.path.join(self.volume, "current")))
        self.assertTrue(os.path.isdir(os.path.join(self.volume, "releases",
                                                   report["release_id"])))

    def test_the_id_matches_the_tree_it_describes(self):
        report = imp.import_legacy_tree(self.volume, now_s=T0)

        prefix = parse_release_id(report["release_id"])["tree_digest_prefix"]
        self.assertTrue(report["source"]["tree_digest"].startswith(prefix))

    # -- refusals ---------------------------------------------------------

    def test_a_second_import_is_refused(self):
        first = imp.import_legacy_tree(self.volume)
        self.assertTrue(first["ok"])

        second = imp.import_legacy_tree(self.volume)

        self.assertFalse(second["ok"])
        self.assertEqual({"targets_exist"},
                         {f["code"] for f in second["failures"]})
        self.assertEqual(os.path.join("releases", first["release_id"]),
                         os.readlink(os.path.join(self.volume, "current")),
                         "the refused second import moved the pointer")

    def test_any_reserved_name_already_present_refuses(self):
        for name in ("releases", "current", "previous"):
            with self.subTest(name=name):
                volume = self._flat(os.path.join(self.tmp, "vol-" + name))
                os.makedirs(os.path.join(volume, name))

                report = imp.import_legacy_tree(volume)

                self.assertFalse(report["ok"])
                self.assertEqual({"targets_exist"},
                                 {f["code"] for f in report["failures"]})

    def test_an_empty_volume_is_refused(self):
        empty = os.path.join(self.tmp, "empty")
        os.makedirs(empty)

        report = imp.import_legacy_tree(empty)

        self.assertFalse(report["ok"])
        self.assertEqual({"no_tiles"}, {f["code"] for f in report["failures"]})

    def test_a_digest_that_does_not_match_the_expectation_refuses(self):
        """The migration passes the preflight's digest.

        If the tree has changed since it was measured, every decision made on
        the strength of that measurement is about a tree that no longer exists.
        """
        report = imp.import_legacy_tree(self.volume, expect_digest="0" * 64)

        self.assertFalse(report["ok"])
        self.assertEqual({"digest_mismatch"},
                         {f["code"] for f in report["failures"]})
        self.assertFalse(os.path.exists(os.path.join(self.volume, "releases")),
                         "a refused import must leave nothing behind")

    def test_the_matching_digest_is_accepted(self):
        report = imp.import_legacy_tree(self.volume,
                                        expect_digest=self._digest(self.volume))

        self.assertTrue(report["ok"], report["failures"])

    def test_a_refused_import_never_touches_the_flat_tree(self):
        """The failure paths are where a cleanup would tidy the wrong thing."""
        before = self._digest(self.volume)

        for kwargs in ({"expect_digest": "0" * 64},):
            with self.subTest(kwargs=kwargs):
                imp.import_legacy_tree(self.volume, **kwargs)
                self.assertEqual(before, self._digest(self.volume))

    def test_a_missing_volume_is_reported_not_raised(self):
        report = imp.import_legacy_tree(os.path.join(self.tmp, "nowhere"))

        self.assertFalse(report["ok"])
        self.assertEqual({"not_writable"},
                         {f["code"] for f in report["failures"]})

    # -- the CLI ----------------------------------------------------------

    def test_the_cli_reports_success_as_exit_0(self):
        self.assertEqual(0, imp.main([self.volume, "--json"]))

    def test_the_cli_reports_a_refusal_as_exit_1(self):
        imp.main([self.volume])
        self.assertEqual(1, imp.main([self.volume]))


if __name__ == "__main__":
    unittest.main()
