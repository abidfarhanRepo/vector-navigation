"""The publisher, proven against a temporary root before it ever sees a remote.

WHY THIS SUITE IS SHAPED THE WAY IT IS
--------------------------------------
Almost every test here kills the publisher somewhere and then asks the
filesystem what survived. That is deliberate. The happy path of an activation
scheme is easy and proves very little: the failures V7.7 exists to stop are all
interruptions, and every one of them leaves behind something that looks
perfectly valid.

  * a transfer that died after 3,000 of 18,311 tiles leaves 3,000 *valid* tiles;
  * ``cp -r`` MERGES, so a tile from a previous bake survives into a release
    and is also *valid*;
  * a copy that landed only z6-z13 contains nothing malformed at all.

So the assertions below are mostly about what did NOT happen: `current` did not
move, no partial tree became reachable, no previously-served release lost a
byte. The invariant helper at the top captures the whole root before and after
each injected failure, because "current is fine" is not a claim you can make by
looking at `current`.

THE CENSUS IS NOT UNDER TEST HERE
---------------------------------
These tests run the publisher with ``census=False``. Decoding tiles is
``validate_release``'s job and is covered by ``test_validate_release.py``; the
publisher's job is the *activation model*, and mixing the two would make this
suite depend on an optional decoder to prove something about ``rename(2)``.
One test pins that the publisher passes the flag through rather than deciding
for itself.

WHAT IS NOT SIMULATED
---------------------
A real interruption is a ``SIGKILL``, which no ``except`` can observe. These
tests raise ``InjectedFailure`` at the instants a kill would land and assert on
the state left behind, never on the exception — nothing in the publisher
catches it, so nothing here passes because a handler tidied up. What that does
not cover is a kill *inside* a single syscall, which is precisely why the
design puts every state change in one ``rename(2)``.
"""
import importlib.util
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_PKG = os.path.join(os.path.dirname(_HERE), "src")
if _PKG not in sys.path:
    sys.path.insert(0, _PKG)

_SPEC = importlib.util.spec_from_file_location(
    "vector_publish_release",
    os.path.join(os.path.dirname(_HERE), "scripts", "publish_release.py"))
pub = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(pub)

from vector_tile_gen.release import (  # noqa: E402
    build_manifest,
    format_release_id,
    read_manifest,
    scan_tree,
    tree_digest,
    write_digests,
    write_manifest,
)

# A fixed instant so release ids in failure messages look like the real thing.
T0 = 1_789_819_647.0  # 2026-09-19T12:07Z
SHA_A = "4d627cd"
SHA_B = "9757c54"
SHA_C = "8f4f2b4"


def _write_tile(root, z, x, y, data):
    d = os.path.join(root, str(z), str(x))
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, f"{y}.mvt")
    with open(path, "wb") as fh:
        fh.write(data)
    return path


def make_release(path, *, release_id, zooms=(11, 12, 13), marker=b"A"):
    """A complete, self-describing release tree.

    Built through the `scan=` seam so it needs no MVT decoder: the tiles are
    opaque bytes, which is correct for a suite about the activation model. The
    manifest, digests table, tree digest and probe sample are all real and are
    what the publisher will check.
    """
    os.makedirs(path, exist_ok=True)
    for z in zooms:
        for x in (21074, 21075):
            _write_tile(path, z, x, 14003, marker * 8 + bytes([z, x % 251]))
    scan = dict(scan_tree(path, census=False), censused=True)
    manifest = build_manifest(
        path,
        release_id=release_id,
        source={"kind": "osm-pbf", "extract_sha256": "9e0a7680", "region": "qatar"},
        generator={"repo_sha": release_id.split("-")[-1], "repo_dirty": False},
        input_config={"zooms": list(zooms)},
        scan=scan,
    )
    write_digests(path, scan["entries"])
    write_manifest(path, manifest)
    return manifest


def root_state(publisher):
    """Everything about a root that an interruption could damage.

    Captured as one comparable value so a test can assert "nothing moved"
    without enumerating what "nothing" means at every call site — which is how
    an invariant quietly stops covering the case it was written for.
    """
    releases = {}
    if os.path.isdir(publisher.releases_path):
        for name in sorted(os.listdir(publisher.releases_path)):
            full = os.path.join(publisher.releases_path, name)
            if os.path.isdir(full):
                releases[name] = tree_digest(scan_tree(full, census=False)["entries"])
    staging = sorted(os.listdir(publisher.staging_path)) \
        if os.path.isdir(publisher.staging_path) else []
    return {
        "current": publisher.active_release(),
        "previous": publisher.previous_release(),
        "releases": releases,
        "staging": staging,
        "orphan_pointers": publisher._orphan_pointers(),
    }


class PublisherCase(unittest.TestCase):
    """A root, and two baked releases waiting to be published into it."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="vector-publish-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.root = os.path.join(self.tmp, "vector-data-tiles")
        self.rid_a = format_release_id(now_s=T0, git_sha=SHA_A)
        self.rid_b = format_release_id(now_s=T0 + 3600, git_sha=SHA_B)
        self.rid_c = format_release_id(now_s=T0 + 7200, git_sha=SHA_C)
        self.src_a = os.path.join(self.tmp, "bake-a")
        self.src_b = os.path.join(self.tmp, "bake-b")
        make_release(self.src_a, release_id=self.rid_a, marker=b"A")
        make_release(self.src_b, release_id=self.rid_b, marker=b"B")

    def publisher(self, **kw):
        return pub.Publisher(self.root, **kw)

    def publish(self, source, *, publisher=None, **kw):
        p = publisher or self.publisher()
        kw.setdefault("census", False)
        kw.setdefault("verify_served", False)
        return p.publish(source, **kw)

    def live_a(self):
        """Release A published and active. The starting point for most tests."""
        report = self.publish(self.src_a)
        self.assertTrue(report["ok"], report["failures"])
        return self.publisher()

    # -- shared assertions ------------------------------------------------

    def assert_current_serves_a_whole_release(self, p, expected=None):
        """`current` names an installed, internally consistent release.

        The claim requirement 6 makes and the one that is easiest to satisfy
        accidentally: a pointer that resolves is not the same as a pointer that
        resolves to a complete release, which is why this re-verifies the tree
        rather than calling `os.path.isdir`.
        """
        active = p.active_release()
        self.assertIsNotNone(active, "current names nothing")
        path = p.release_path(active)
        self.assertTrue(os.path.isdir(path), f"current -> {active}, which is absent")
        manifest = read_manifest(path)
        self.assertIsNotNone(manifest, f"{active} has no readable manifest")
        self.assertEqual(active, str(manifest["release_id"]),
                         "the manifest in current names a different release")
        verdict = pub.verify_manifest(path, manifest, census=False)
        self.assertTrue(verdict["ok"],
                        f"current points at a release that does not verify: "
                        f"{verdict['failures']}")
        if expected:
            self.assertEqual(expected, active)
        return active


# ---------------------------------------------------------------------------
# The happy paths, which exist mostly to give the failure tests something real
# ---------------------------------------------------------------------------

class FirstReleaseTest(PublisherCase):

    def test_the_first_release_becomes_current(self):
        report = self.publish(self.src_a)

        self.assertTrue(report["ok"], report["failures"])
        p = self.publisher()
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)
        self.assertIsNone(p.previous_release(),
                          "a first release has nothing to roll back to")

    def test_the_first_release_leaves_no_debris(self):
        self.publish(self.src_a)
        state = root_state(self.publisher())

        self.assertEqual([], state["staging"],
                         "the staged copy is consumed by the install rename")
        self.assertEqual([], state["orphan_pointers"])

    def test_current_is_a_relative_symlink(self):
        """So the root can be moved, or mounted at another path in a container.

        Production mounts this root into the tiles container at a different
        path than it has on the host. An absolute pointer would dangle on one
        side of that mount, and the symptom would be a container serving
        nothing while the host looks healthy.
        """
        self.live_a()
        target = os.readlink(os.path.join(self.root, "current"))

        self.assertFalse(os.path.isabs(target), target)
        self.assertEqual(os.path.join("releases", self.rid_a), target)

    def test_the_install_is_a_rename_not_a_copy(self):
        """Which is what makes a partial release unreachable under releases/.

        Asserted by inode: a copy would produce a new one. A release directory
        that is renamed into place cannot be observed half-written, because
        there is no moment at which it is partially present under its final
        name.
        """
        p = self.publisher()
        staged = p.stage(self.src_a)
        self.assertTrue(staged["ok"], staged["failures"])
        probe = os.path.join(p.staged_path(self.rid_a), "11", "21074", "14003.mvt")
        before = os.stat(probe).st_ino

        p.install(self.rid_a)

        after = os.stat(os.path.join(
            p.release_path(self.rid_a), "11", "21074", "14003.mvt")).st_ino
        self.assertEqual(before, after, "install copied instead of renaming")


class SecondReleaseTest(PublisherCase):

    def test_a_second_release_becomes_current_and_the_first_becomes_previous(self):
        p = self.live_a()
        report = self.publish(self.src_b)

        self.assertTrue(report["ok"], report["failures"])
        p = self.publisher()
        self.assert_current_serves_a_whole_release(p, expected=self.rid_b)
        self.assertEqual(self.rid_a, p.previous_release())

    def test_the_first_release_is_not_touched_by_the_second(self):
        """No data loss to the release that was being served.

        The tree digest before and after, which catches a deletion, a
        truncation and the `cp -r` merge that puts a tile from one bake into
        another's directory.
        """
        p = self.live_a()
        before = root_state(p)["releases"][self.rid_a]

        self.publish(self.src_b)

        after = root_state(self.publisher())["releases"][self.rid_a]
        self.assertEqual(before, after)


class RollbackTest(PublisherCase):

    def test_rollback_returns_to_the_intended_release(self):
        self.live_a()
        self.publish(self.src_b)
        p = self.publisher()

        report = p.rollback()

        self.assertTrue(report["ok"], report["failures"])
        self.assertEqual(self.rid_a, report["release_id"])
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_a_rollback_is_itself_reversible(self):
        """The release left behind becomes the way back.

        Without this a rollback would be a one-way door: an operator who rolls
        back to investigate could not return to the release they were on, and
        would have to re-publish it — which would mint a new id for identical
        bytes and lose the thread.
        """
        self.live_a()
        self.publish(self.src_b)
        p = self.publisher()

        p.rollback()
        self.assertEqual(self.rid_b, p.previous_release())

        p.rollback()
        self.assert_current_serves_a_whole_release(p, expected=self.rid_b)

    def test_rollback_deletes_nothing(self):
        self.live_a()
        self.publish(self.src_b)
        p = self.publisher()
        before = root_state(p)["releases"]

        p.rollback()

        self.assertEqual(before, root_state(p)["releases"])

    def test_rollback_is_refused_when_there_is_nowhere_to_go(self):
        p = self.live_a()
        report = p.rollback()

        self.assertFalse(report["ok"])
        self.assertEqual({"no_previous"}, {f["code"] for f in report["failures"]})
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_rollback_is_refused_when_previous_was_pruned_away(self):
        """A dangling rollback target is refused, not followed.

        Following it would leave `current` naming a directory that is not
        there, which is the one state from which the tile server has nothing
        at all to serve.
        """
        self.live_a()
        self.publish(self.src_b)
        p = self.publisher()
        shutil.rmtree(p.release_path(self.rid_a))

        report = p.rollback()

        self.assertFalse(report["ok"])
        self.assertEqual({"previous_missing"},
                         {f["code"] for f in report["failures"]})
        self.assert_current_serves_a_whole_release(p, expected=self.rid_b)


# ---------------------------------------------------------------------------
# Refusals: the release never becomes active
# ---------------------------------------------------------------------------

class RefusalTest(PublisherCase):

    def test_a_duplicate_release_id_is_refused_before_anything_is_copied(self):
        """AC-1. Uniqueness is enforced here, never inferred from the clock.

        The id is minute-resolution, so two bakes from one commit inside one
        minute produce the same id. The publisher refusing to write into an
        existing `releases/<id>` is the only check that actually holds under a
        re-run.
        """
        p = self.live_a()
        again = os.path.join(self.tmp, "bake-a-again")
        make_release(again, release_id=self.rid_a, marker=b"X")

        report = p.stage(again)

        self.assertFalse(report["ok"])
        self.assertEqual({"duplicate_release_id"},
                         {f["code"] for f in report["failures"]})
        self.assertEqual([], root_state(p)["staging"],
                         "refused before the copy, so nothing was staged")
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_a_duplicate_is_refused_at_install_too(self):
        """The stage-time check is not the only one.

        `install` is separately callable, and an operator mid-incident calls
        the steps individually. A check that only existed in the pipeline would
        be absent exactly when someone is working by hand under pressure.
        """
        p = self.live_a()
        staged = os.path.join(p.staging_path, self.rid_a)
        shutil.copytree(p.release_path(self.rid_a), staged)

        report = p.install(self.rid_a)

        self.assertFalse(report["ok"])
        self.assertEqual({"duplicate_release_id"},
                         {f["code"] for f in report["failures"]})

    def test_a_tree_with_no_manifest_is_not_a_release(self):
        nameless = os.path.join(self.tmp, "just-tiles")
        _write_tile(nameless, 11, 21074, 14003, b"tiles-but-no-identity")

        report = self.publisher().stage(nameless)

        self.assertFalse(report["ok"])
        self.assertEqual({"source_not_a_release"},
                         {f["code"] for f in report["failures"]})

    def test_a_corrupt_manifest_never_reaches_current(self):
        p = self.live_a()
        corrupt = os.path.join(self.tmp, "bake-corrupt")
        make_release(corrupt, release_id=self.rid_c, marker=b"C")
        with open(os.path.join(corrupt, "RELEASE.json"), "w") as fh:
            fh.write('{"release_id": "vector-tiles-2026')  # truncated

        report = self.publish(corrupt, publisher=p)

        self.assertFalse(report["ok"])
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_a_mutated_tile_is_caught_by_validation_not_by_activation(self):
        """The gate runs on the staged copy, before anything is installed.

        Catching it at activation would be correct and too late: the release
        would already be on the volume, and someone would have to decide
        whether to remove it.
        """
        p = self.publisher()
        tampered = os.path.join(self.tmp, "bake-tampered")
        make_release(tampered, release_id=self.rid_c, marker=b"C")
        staged = p.stage(tampered)
        self.assertTrue(staged["ok"], staged["failures"])
        with open(os.path.join(p.staged_path(self.rid_c), "11", "21074",
                               "14003.mvt"), "wb") as fh:
            fh.write(b"not the bytes the manifest describes")

        report = p.validate(self.rid_c, census=False)

        self.assertFalse(report["ok"])
        codes = {f["code"] for f in report["failures"]}
        self.assertIn("tree_digest_mismatch", codes)
        self.assertFalse(os.path.exists(p.release_path(self.rid_c)))

    def test_activation_re_verifies_and_refuses_a_damaged_release(self):
        """Damage between install and activate is still caught.

        The tree was validated as a staged copy and then renamed. Trusting that
        earlier judgement is the reasoning that produced a production basemap
        with no release identity at all — "it was correct when we checked it
        somewhere else".
        """
        p = self.live_a()
        staged = p.stage(self.src_b)
        self.assertTrue(staged["ok"], staged["failures"])
        self.assertTrue(p.install(self.rid_b)["ok"])
        os.remove(os.path.join(p.release_path(self.rid_b), "12", "21074", "14003.mvt"))

        report = p.activate(self.rid_b, census=False)

        self.assertFalse(report["ok"])
        self.assertIn("refused_activation", {f["code"] for f in report["failures"]})
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_insufficient_space_is_refused_before_destructive_work(self):
        """Requirement 7. The copy is the only step that can fill a disk.

        Filling it mid-copy takes down the release currently being served from
        the same volume — a publisher making a healthy system unhealthy while
        doing nothing else wrong.
        """
        p = self.live_a()

        report = p.stage(self.src_b, require_free_bytes=1 << 62)

        self.assertFalse(report["ok"])
        self.assertEqual({"insufficient_space"},
                         {f["code"] for f in report["failures"]})
        self.assertEqual([], root_state(p)["staging"], "nothing was copied")
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_the_space_requirement_includes_headroom(self):
        p = self.publisher()
        p.init()
        report = p.stage(self.src_a)

        self.assertGreater(report["bytes_required"], report["bytes_staged"],
                           "the requirement must exceed the payload itself")


# ---------------------------------------------------------------------------
# The failure-injection matrix
# ---------------------------------------------------------------------------

class FailureInjectionTest(PublisherCase):
    """Kill the publisher at each agreed point; ask what survived.

    Every test here asserts the same four things requirement 6 names, through
    `assert_intact`: `current` is valid or recoverable, no partial release is
    active, the active release is internally consistent, and the previously
    active release lost nothing.
    """

    def assert_intact(self, p, before, *, expect_active):
        after = root_state(p)
        self.assert_current_serves_a_whole_release(p, expected=expect_active)
        for rid, digest in before["releases"].items():
            self.assertIn(rid, after["releases"],
                          f"release {rid} disappeared during the failure")
            self.assertEqual(digest, after["releases"][rid],
                             f"release {rid} lost or gained bytes")

    def test_1_interrupted_after_staging(self):
        p = self.live_a()
        before = root_state(p)
        killed = self.publisher(fail_at="after_stage")

        with self.assertRaises(pub.InjectedFailure):
            killed.stage(self.src_b)

        self.assertIn(self.rid_b, root_state(p)["staging"],
                      "the staged tree is left where recovery can find it")
        self.assertFalse(os.path.exists(p.release_path(self.rid_b)),
                         "nothing was installed")
        self.assert_intact(p, before, expect_active=self.rid_a)

    def test_2_interrupted_during_transfer(self):
        p = self.live_a()
        before = root_state(p)
        killed = self.publisher(fail_at="during_transfer")

        with self.assertRaises(pub.InjectedFailure):
            killed.stage(self.src_b)

        staged = p.staged_path(self.rid_b)
        self.assertTrue(os.path.isdir(staged), "a partial staging tree is expected")
        complete = len(scan_tree(self.src_b, census=False)["entries"])
        partial = len(scan_tree(staged, census=False)["entries"])
        self.assertLess(partial, complete, "the transfer should have been cut short")
        self.assertFalse(os.path.exists(p.release_path(self.rid_b)))
        self.assert_intact(p, before, expect_active=self.rid_a)

    def test_2b_a_partial_transfer_is_refused_by_the_gate(self):
        """The partial tree is not merely unreachable — it also cannot pass.

        Two independent defences, which matters because the first one is a
        property of where the tree is and the second of what it contains. A
        future change that staged somewhere else would still be stopped.
        """
        p = self.live_a()
        killed = self.publisher(fail_at="during_transfer")
        with self.assertRaises(pub.InjectedFailure):
            killed.stage(self.src_b)

        report = p.validate(self.rid_b, census=False)

        self.assertFalse(report["ok"])
        codes = {f["code"] for f in report["failures"]}
        self.assertTrue(codes & {"tile_missing", "tree_digest_mismatch",
                                 "tiles_total_mismatch", "manifest_missing"},
                        codes)

    def test_3_interrupted_after_install_before_verification(self):
        p = self.live_a()
        before = root_state(p)
        killed = self.publisher(fail_at="after_install")
        self.assertTrue(killed.stage(self.src_b)["ok"])

        with self.assertRaises(pub.InjectedFailure):
            killed.install(self.rid_b)

        self.assertTrue(os.path.isdir(p.release_path(self.rid_b)),
                        "the install rename completed; it is one step")
        self.assert_intact(p, before, expect_active=self.rid_a)
        self.assertNotEqual(self.rid_b, p.active_release(),
                            "installed is not activated")

    def test_4_interrupted_after_verification_before_activation(self):
        p = self.live_a()
        before = root_state(p)
        killed = self.publisher(fail_at="after_verify")
        self.assertTrue(killed.stage(self.src_b)["ok"])
        self.assertTrue(killed.install(self.rid_b)["ok"])

        with self.assertRaises(pub.InjectedFailure):
            killed.activate(self.rid_b, census=False)

        self.assert_intact(p, before, expect_active=self.rid_a)
        self.assertEqual([], root_state(p)["orphan_pointers"],
                         "the pointer had not been created yet")

    def test_5_interrupted_after_the_pointer_swap(self):
        """The far side of the atomic step: B is live and fully consistent."""
        p = self.live_a()
        before = root_state(p)
        killed = self.publisher(fail_at="after_swap")
        self.assertTrue(killed.stage(self.src_b)["ok"])
        self.assertTrue(killed.install(self.rid_b)["ok"])

        with self.assertRaises(pub.InjectedFailure):
            killed.activate(self.rid_b, census=False)

        self.assert_intact(p, before, expect_active=self.rid_b)
        self.assertEqual(self.rid_a, p.previous_release(),
                         "previous was written BEFORE current moved")

    def test_6_interrupted_during_rollback(self):
        p = self.live_a()
        self.publish(self.src_b)
        p = self.publisher()
        before = root_state(p)
        killed = self.publisher(fail_at="during_rollback")

        with self.assertRaises(pub.InjectedFailure):
            killed.rollback()

        self.assert_intact(p, before, expect_active=self.rid_b)
        self.assertEqual([f".current.{self.rid_a}.tmp"],
                         root_state(p)["orphan_pointers"],
                         "an identifiable, inert orphan")
        self.assertEqual(self.rid_a, p.previous_release(),
                         "the rollback target is untouched; retrying works")

    def test_6b_a_rollback_interrupted_after_the_swap_has_still_rolled_back(self):
        """The order argument, as a test.

        `rollback` moves `current` first because its purpose is to stop serving
        the current release. An interruption after the swap has achieved that
        and merely lacks a rollback target — reported by `recover`, not
        invented.
        """
        p = self.live_a()
        self.publish(self.src_b)
        p = self.publisher()
        before = root_state(p)
        killed = self.publisher(fail_at="rollback_after_swap")

        with self.assertRaises(pub.InjectedFailure):
            killed.rollback()

        self.assert_intact(p, before, expect_active=self.rid_a)
        findings = {f["code"] for f in p.recover()["findings"]}
        self.assertIn("previous_equals_current", findings)

    def test_interrupted_activation_leaves_an_inert_orphan(self):
        """Requirement 9's "interrupted activation": tmp made, rename not run."""
        p = self.live_a()
        before = root_state(p)
        killed = self.publisher(fail_at="before_swap")
        self.assertTrue(killed.stage(self.src_b)["ok"])
        self.assertTrue(killed.install(self.rid_b)["ok"])

        with self.assertRaises(pub.InjectedFailure):
            killed.activate(self.rid_b, census=False)

        self.assert_intact(p, before, expect_active=self.rid_a)
        self.assertEqual([f".current.{self.rid_b}.tmp"],
                         root_state(p)["orphan_pointers"])

        # The residue of writing `previous` before `current` moves: the
        # rollback target is lost. Pinned rather than tolerated, because the
        # alternative order loses something worse — a `previous` naming the
        # release from two generations back, which is an actionable
        # instruction to roll back to something nobody chose.
        self.assertEqual(self.rid_a, p.previous_release())
        findings = {f["code"] for f in p.recover()["findings"]}
        self.assertIn("previous_equals_current", findings,
                      "a lost rollback target must be reported, not hidden")

    def test_every_injected_failure_leaves_a_recoverable_root(self):
        """AC-23, as one sweep over the whole matrix.

        After each interruption: `recover` restores a serving state without
        anybody touching the filesystem by hand, and the release that was
        active before the failure still has every byte it had.
        """
        for point in pub.FAIL_POINTS:
            with self.subTest(point=point):
                case = PublisherCase("run")
                case.setUp()
                self.addCleanup(shutil.rmtree, case.tmp, ignore_errors=True)
                case.publish(case.src_a)
                if point in ("during_rollback", "rollback_after_swap"):
                    case.publish(case.src_b)
                p = case.publisher()
                before = root_state(p)
                killed = case.publisher(fail_at=point)

                try:
                    if point in ("after_stage", "during_transfer"):
                        killed.stage(case.src_b)
                    elif point == "after_install":
                        killed.stage(case.src_b)
                        killed.install(case.rid_b)
                    elif point in ("after_verify", "before_swap", "after_swap"):
                        killed.stage(case.src_b)
                        killed.install(case.rid_b)
                        killed.activate(case.rid_b, census=False)
                    else:
                        killed.rollback()
                except pub.InjectedFailure:
                    pass

                report = p.recover()
                self.assertTrue(report["ok"], report["failures"])
                case.assert_current_serves_a_whole_release(p)
                after = root_state(p)
                for rid, digest in before["releases"].items():
                    self.assertEqual(digest, after["releases"].get(rid),
                                     f"{point}: release {rid} lost bytes")
                self.assertEqual([], after["orphan_pointers"],
                                 f"{point}: recover left an orphan pointer")


# ---------------------------------------------------------------------------
# Recovery
# ---------------------------------------------------------------------------

class RecoveryTest(PublisherCase):

    def test_a_stale_pointer_is_swept_and_reported(self):
        p = self.live_a()
        stale = p.tmp_pointer(self.rid_b)
        os.symlink(os.path.join("releases", self.rid_b), stale)

        self.assertEqual([os.path.basename(stale)],
                         p.list()["orphan_pointers"],
                         "list must surface it before recover removes it")

        report = p.recover()

        self.assertIn(os.path.basename(stale), report["swept"])
        self.assertFalse(os.path.lexists(stale))
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_a_stale_pointer_never_affects_what_is_served(self):
        """Inert, which is the property that makes sweeping it safe.

        A dangling `.current.*.tmp` naming a release that does not even exist
        must not change what `current` resolves to.
        """
        p = self.live_a()
        os.symlink(os.path.join("releases", "vector-tiles-2026-01-01T0000Z-deadbee"),
                   p.tmp_pointer("vector-tiles-2026-01-01T0000Z-deadbee"))

        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_staging_debris_is_swept(self):
        p = self.live_a()
        killed = self.publisher(fail_at="during_transfer")
        with self.assertRaises(pub.InjectedFailure):
            killed.stage(self.src_b)

        report = p.recover()

        self.assertIn(f".staging/{self.rid_b}", report["swept"])
        self.assertEqual([], root_state(p)["staging"])

    def test_a_staged_tree_whose_release_is_installed_is_left_alone(self):
        """Not debris. Someone may still be working with it.

        Sweeping it would be recovery deleting a thing a person put there, and
        recovery that destroys work is recovery people stop running.
        """
        p = self.live_a()
        shutil.copytree(p.release_path(self.rid_a), p.staged_path(self.rid_a))

        report = p.recover()

        self.assertTrue(os.path.isdir(p.staged_path(self.rid_a)))
        self.assertIn("staged_and_installed",
                      {f["code"] for f in report["findings"]})

    def test_a_dangling_current_is_repaired_from_previous(self):
        """The one case where recovery writes a pointer.

        Without it, an operator faced with `current -> a release that is gone`
        has to work out by hand what used to be running — the manual
        filesystem surgery requirement 4 forbids.
        """
        self.live_a()
        self.publish(self.src_b)
        p = self.publisher()
        shutil.rmtree(p.release_path(self.rid_b))

        report = p.recover()

        self.assertTrue(report["ok"], report["failures"])
        self.assertIn("current_restored", {r["code"] for r in report["repaired"]})
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_a_dangling_current_falls_back_to_the_newest_valid_release(self):
        p = self.live_a()
        self.publish(self.src_b)
        p = self.publisher()
        os.unlink(p.previous_path)
        shutil.rmtree(p.release_path(self.rid_b))

        report = p.recover()

        self.assertTrue(report["ok"], report["failures"])
        self.assert_current_serves_a_whole_release(p, expected=self.rid_a)

    def test_recovery_reports_when_there_is_nothing_to_serve(self):
        """An honest failure rather than a repaired-looking root.

        `ok: False` is the correct answer when no valid release is installed.
        Reporting success here would be the publisher telling an operator the
        problem was fixed while the map stayed blank.
        """
        p = self.live_a()
        shutil.rmtree(p.release_path(self.rid_a))

        report = p.recover()

        self.assertFalse(report["ok"])
        self.assertEqual({"nothing_to_serve"},
                         {f["code"] for f in report["failures"]})

    def test_recovery_is_idempotent(self):
        p = self.live_a()
        killed = self.publisher(fail_at="before_swap")
        killed.stage(self.src_b)
        killed.install(self.rid_b)
        with self.assertRaises(pub.InjectedFailure):
            killed.activate(self.rid_b, census=False)

        first = p.recover()
        state_after_first = root_state(p)
        second = p.recover()

        self.assertTrue(first["swept"], "the first run had work to do")
        self.assertEqual([], second["swept"], "the second run had none")
        self.assertEqual([], second["repaired"])
        self.assertEqual(state_after_first, root_state(p),
                         "a second recovery changed the root")

    def test_recovery_on_an_untouched_root_does_nothing(self):
        p = self.live_a()
        before = root_state(p)

        report = p.recover()

        self.assertTrue(report["ok"])
        self.assertEqual([], report["swept"])
        self.assertEqual([], report["repaired"])
        self.assertEqual(before, root_state(p))

    def test_a_dry_run_recovery_changes_nothing(self):
        p = self.live_a()
        os.symlink(os.path.join("releases", self.rid_b), p.tmp_pointer(self.rid_b))
        before = root_state(p)

        report = p.recover(dry_run=True)

        self.assertIn(f".current.{self.rid_b}.tmp", report["swept"])
        self.assertEqual(before, root_state(p))

    def test_an_orphan_release_directory_is_reported_and_kept(self):
        """Reported, never deleted. Recovery does not destroy data.

        A directory under `releases/` with no manifest naming itself is the
        residue of a hand-made directory or a rename that went wrong. Deciding
        it is worthless is `prune`'s job, under an operator's hand.
        """
        p = self.live_a()
        orphan = p.release_path("vector-tiles-2026-01-01T0000Z-deadbee")
        os.makedirs(os.path.join(orphan, "11", "21074"))
        with open(os.path.join(orphan, "11", "21074", "14003.mvt"), "wb") as fh:
            fh.write(b"who put this here")

        report = p.recover()

        self.assertIn("orphan_release", {f["code"] for f in report["findings"]})
        self.assertTrue(os.path.isdir(orphan), "recovery deleted data")

    def test_list_marks_an_orphan(self):
        p = self.live_a()
        orphan = p.release_path("vector-tiles-2026-01-01T0000Z-deadbee")
        os.makedirs(orphan)

        entries = {e["release_id"]: e for e in p.list()["releases"]}

        self.assertTrue(entries["vector-tiles-2026-01-01T0000Z-deadbee"]["orphan"])
        self.assertFalse(entries[self.rid_a]["orphan"])


# ---------------------------------------------------------------------------
# Prune
# ---------------------------------------------------------------------------

class PruneTest(PublisherCase):

    def _three_releases(self):
        self.live_a()
        self.publish(self.src_b)
        src_c = os.path.join(self.tmp, "bake-c")
        make_release(src_c, release_id=self.rid_c, marker=b"C")
        self.publish(src_c)
        return self.publisher()

    def test_pruning_is_a_dry_run_by_default(self):
        """A pruner whose default is to delete gets run once, by someone
        finding out what it does."""
        p = self._three_releases()

        report = p.prune(keep=0)

        self.assertTrue(report["dry_run"])
        self.assertEqual([], report["removed"])
        self.assertEqual(3, len(p.list()["releases"]))

    def test_a_dry_run_names_exactly_what_would_go(self):
        p = self._three_releases()

        planned = p.prune(keep=0, dry_run=True)
        applied = p.prune(keep=0, dry_run=False)

        self.assertEqual([r["release_id"] for r in planned["would_remove"]],
                         [r["release_id"] for r in applied["removed"]])

    def test_current_is_never_pruned(self):
        p = self._three_releases()

        report = p.prune(keep=0, dry_run=False, allow_previous=True)

        self.assertIn({"release_id": self.rid_c, "reason": "current"},
                      report["protected"])
        self.assert_current_serves_a_whole_release(p, expected=self.rid_c)

    def test_previous_is_protected_unless_explicitly_authorised(self):
        p = self._three_releases()

        report = p.prune(keep=0, dry_run=False)

        self.assertIn({"release_id": self.rid_b, "reason": "previous"},
                      report["protected"])
        self.assertTrue(os.path.isdir(p.release_path(self.rid_b)))
        self.assertEqual(self.rid_b, p.previous_release())

    def test_previous_goes_only_when_authorised(self):
        p = self._three_releases()

        report = p.prune(keep=0, dry_run=False, allow_previous=True)

        self.assertIn(self.rid_b, [r["release_id"] for r in report["removed"]])

    def test_the_keep_window_retains_the_newest(self):
        """Newest by the DECLARED epoch, not by mtime.

        `cp -r` does not preserve mtimes — the defect that made the old tile
        version meaningless — so ordering by the filesystem would put a freshly
        copied old release ahead of a newer one and prune the wrong thing.
        """
        p = self._three_releases()
        old = os.path.join(self.tmp, "bake-old")
        make_release(old, release_id=format_release_id(now_s=T0 - 86400,
                                                       git_sha="0000abc"))
        self.assertTrue(p.stage(old)["ok"])
        self.assertTrue(p.install(format_release_id(now_s=T0 - 86400,
                                                    git_sha="0000abc"))["ok"])
        # Touch the OLD release so the filesystem disagrees with the manifest.
        for dirpath, _d, files in os.walk(
                p.release_path(format_release_id(now_s=T0 - 86400, git_sha="0000abc"))):
            for f in files:
                os.utime(os.path.join(dirpath, f), None)

        report = p.prune(keep=1, dry_run=False)

        removed = [r["release_id"] for r in report["removed"]]
        self.assertIn(format_release_id(now_s=T0 - 86400, git_sha="0000abc"), removed)
        self.assertTrue(os.path.isdir(p.release_path(self.rid_a)),
                        "the newest prunable release was kept")

    def test_pruning_is_auditable(self):
        p = self._three_releases()
        p.prune(keep=0, dry_run=False)

        with open(os.path.join(self.root, "publish.log"), encoding="utf-8") as fh:
            entries = [json.loads(line) for line in fh if line.strip()]

        prunes = [e for e in entries if e["op"] == "prune"]
        self.assertTrue(prunes, "the prune left no audit record")
        self.assertIn("removed", prunes[-1])
        self.assertEqual(self.rid_c, prunes[-1]["active"],
                         "the record names what was live when it happened")

    def test_the_audit_log_is_append_only_across_operations(self):
        p = self._three_releases()
        p.rollback()
        p.prune(keep=1, dry_run=True)

        with open(os.path.join(self.root, "publish.log"), encoding="utf-8") as fh:
            ops = [json.loads(line)["op"] for line in fh if line.strip()]

        self.assertEqual(["stage", "validate", "install", "activate", "verify"],
                         ops[:5])
        self.assertIn("rollback", ops)
        self.assertEqual("prune", ops[-1])

    def test_pruning_never_strands_current(self):
        p = self._three_releases()

        p.prune(keep=0, dry_run=False, allow_previous=True)

        self.assert_current_serves_a_whole_release(p, expected=self.rid_c)
        self.assertEqual([self.rid_c],
                         sorted(e["release_id"] for e in p.list()["releases"]))


# ---------------------------------------------------------------------------
# Verification beyond file existence
# ---------------------------------------------------------------------------

class VerifyTest(PublisherCase):

    def test_verify_checks_six_independent_claims(self):
        p = self.live_a()

        report = p.verify(serve=False, census=False)

        self.assertTrue(report["ok"], report["failures"])
        for claim in ("pointer_identity", "manifest_sha256", "tile_count",
                      "tree_digest", "sample_on_disk"):
            self.assertTrue(report["checks"][claim], claim)
        self.assertGreater(report["sample_size"], 0)

    def test_a_tampered_manifest_claim_is_caught_by_its_own_hash(self):
        """Tampering with the CLAIMS is as detectable as tampering with tiles.

        The coverage numbers decide what the tile server advertises. A release
        whose declared maxzoom had been edited would be served with a zoom
        range nothing else disputes.
        """
        p = self.live_a()
        path = os.path.join(p.release_path(self.rid_a), "RELEASE.json")
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        doc["coverage"]["maxzoom"] = 19
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(doc, fh)

        report = p.verify(serve=False, census=False)

        self.assertFalse(report["ok"])
        self.assertFalse(report["checks"]["manifest_sha256"])

    def test_a_removed_tile_is_caught_by_the_count_and_the_digest(self):
        p = self.live_a()
        os.remove(os.path.join(p.release_path(self.rid_a), "13", "21075", "14003.mvt"))

        report = p.verify(serve=False, census=False)

        self.assertFalse(report["ok"])
        self.assertFalse(report["checks"]["tile_count"])
        self.assertFalse(report["checks"]["tree_digest"])

    def test_a_manifest_in_the_wrong_directory_is_caught(self):
        """A real deploy accident: the directory name is the only independent
        witness to what a release is supposed to be."""
        p = self.live_a()
        wrong = p.release_path(self.rid_b)
        shutil.copytree(p.release_path(self.rid_a), wrong)
        p._set_pointer(p.current_path, self.rid_b)

        report = p.verify(serve=False, census=False)

        self.assertFalse(report["ok"])
        self.assertFalse(report["checks"]["pointer_identity"])

    def test_verify_refuses_a_dangling_current(self):
        p = self.live_a()
        shutil.rmtree(p.release_path(self.rid_a))

        report = p.verify(serve=False, census=False)

        self.assertFalse(report["ok"])
        self.assertEqual({"current_dangling"},
                         {f["code"] for f in report["failures"]})

    def test_the_tile_server_serves_the_release_current_names(self):
        """The only check that tests the SERVING path rather than the disk.

        AC-9's shape, locally: not that a URL answered 200, but that the bytes
        on the wire hash to what the manifest says, and that the server names
        the same release `current` does. A 200 at `?v=N` is not evidence that
        release N is being served — that was measured on production.
        """
        p = self.live_a()

        report = p.verify(serve=True, census=False)

        self.assertTrue(report["ok"], report["failures"])
        served = report["served"]
        self.assertTrue(served["ok"], served["failures"])
        self.assertEqual(self.rid_a, served["version"]["release"])
        self.assertEqual("manifest", served["version"]["source"])
        self.assertTrue(served["probed"], "no sample tile was fetched")

    def test_the_served_release_follows_a_pointer_swap(self):
        """One server process, two releases, one rename between them.

        This is the local stand-in for the production claim that activation
        needs no restart: the same process answers with B after the swap
        without being told anything.
        """
        p = self.live_a()
        self.publish(self.src_b)
        p = self.publisher()

        report = p.verify(serve=True, census=False)

        self.assertTrue(report["ok"], report["failures"])
        self.assertEqual(self.rid_b, report["served"]["version"]["release"])

    def test_served_bytes_that_do_not_match_the_manifest_fail(self):
        """The check that would catch a merged tile from a previous bake.

        Mutating a sampled tile after activation leaves a release that is
        installed, active and serving 200s for every URL — and serving the
        wrong bytes.
        """
        p = self.live_a()
        manifest = read_manifest(p.release_path(self.rid_a))
        victim = manifest["integrity"]["sample"][0]["path"]
        with open(os.path.join(p.release_path(self.rid_a), victim), "wb") as fh:
            fh.write(b"different bytes, same URL")

        report = p.verify(serve=True, census=False)

        self.assertFalse(report["ok"])
        codes = {f["code"] for f in report["failures"]}
        self.assertIn("served_bytes_mismatch", codes)


# ---------------------------------------------------------------------------
# Concurrency
# ---------------------------------------------------------------------------

class ConcurrentReaderTest(PublisherCase):

    def test_a_reader_never_observes_a_missing_or_partial_current(self):
        """The property `rename(2)` is chosen for, exercised rather than argued.

        Four reader threads resolve a path through `current` while the
        publisher swaps the pointer back and forth underneath them. Every read
        must return one of the two releases' bytes. An `ENOENT` here would mean
        the pointer had a window in which it did not exist — which is exactly
        what `unlink`+`symlink` would produce, and why activation is a rename.
        """
        p = self.live_a()
        self.publish(self.src_b)
        p = self.publisher()

        probe = os.path.join("11", "21074", "14003.mvt")
        expected = set()
        for rid in (self.rid_a, self.rid_b):
            with open(os.path.join(p.release_path(rid), probe), "rb") as fh:
                expected.add(fh.read())
        self.assertEqual(2, len(expected), "the two releases must differ")

        stop = threading.Event()
        errors = []
        reads = [0]
        lock = threading.Lock()

        def reader():
            local = 0
            while not stop.is_set():
                try:
                    with open(os.path.join(p.current_path, probe), "rb") as fh:
                        data = fh.read()
                    if data not in expected:
                        errors.append(f"unknown bytes: {data!r}")
                except OSError as exc:
                    errors.append(f"{type(exc).__name__}: {exc}")
                local += 1
            with lock:
                reads[0] += local

        threads = [threading.Thread(target=reader) for _ in range(4)]
        for t in threads:
            t.start()
        try:
            for i in range(120):
                target = self.rid_b if i % 2 == 0 else self.rid_a
                p._set_pointer(p.current_path, target)
                time.sleep(0.001)
        finally:
            stop.set()
            for t in threads:
                t.join(timeout=10)

        self.assertEqual([], errors[:10], f"{len(errors)} failed reads")
        self.assertGreater(reads[0], 100, "the readers barely ran")
        self.assert_current_serves_a_whole_release(p)

    def test_repeated_activation_leaves_no_orphan_pointers(self):
        p = self.live_a()
        self.publish(self.src_b)
        p = self.publisher()

        for _ in range(5):
            p.activate(self.rid_a, census=False)
            p.activate(self.rid_b, census=False)

        self.assertEqual([], root_state(p)["orphan_pointers"])
        self.assert_current_serves_a_whole_release(p, expected=self.rid_b)


# ---------------------------------------------------------------------------
# Wiring
# ---------------------------------------------------------------------------

class WiringTest(PublisherCase):

    def test_the_publisher_delegates_to_the_gate_rather_than_reimplementing_it(self):
        """A publisher carrying its own copy of the rules would drift from the
        gate, and the disagreement would be discovered by publishing."""
        seen = {}
        real = pub.validate_release

        def spy(path, **kw):
            seen.update(kw, path=path)
            return real(path, **kw)

        pub.validate_release = spy
        try:
            self.publish(self.src_a, census=False)
        finally:
            pub.validate_release = real

        self.assertEqual(False, seen["census"],
                         "the census flag must reach the gate, not be decided here")
        self.assertTrue(seen["path"].endswith(self.rid_a))

    def test_the_pipeline_stops_at_the_first_failure(self):
        p = self.live_a()
        corrupt = os.path.join(self.tmp, "bake-corrupt")
        make_release(corrupt, release_id=self.rid_c)
        os.remove(os.path.join(corrupt, "12", "21074", "14003.mvt"))

        report = self.publish(corrupt, publisher=p)

        self.assertFalse(report["ok"])
        self.assertEqual(["stage", "validate"], [s["op"] for s in report["steps"]])
        self.assertFalse(os.path.exists(p.release_path(self.rid_c)),
                         "a failed validation must not install")

    def test_an_unknown_failure_point_is_rejected_loudly(self):
        with self.assertRaises(pub.PublisherError):
            self.publisher(fail_at="whenever")

    def test_the_cli_reports_a_refusal_as_exit_1(self):
        self.live_a()
        code = pub.main(["--root", self.root, "--no-census", "rollback"])
        self.assertEqual(1, code)

    def test_the_cli_reports_an_unrunnable_publisher_as_exit_2(self):
        code = pub.main(["--root", self.root, "--fail-at", "after_stage",
                         "--no-census", "list"])
        self.assertEqual(0, code)
        with self.assertRaises(SystemExit):
            pub.main(["--root", self.root, "nonsense"])

    def test_the_cli_publishes_and_lists(self):
        self.assertEqual(0, pub.main([
            "--root", self.root, "--no-census", "publish", self.src_a,
            "--no-serve-check"]))
        self.assertEqual(0, pub.main(["--root", self.root, "list"]))
        self.assertEqual(self.rid_a, self.publisher().active_release())


if __name__ == "__main__":
    unittest.main()
