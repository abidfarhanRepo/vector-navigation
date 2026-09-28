"""The remote transport, proven without a remote.

WHAT IS AND IS NOT MOCKED
-------------------------
The *transport* is substituted — `LocalRemote` runs commands in a sandbox
directory on this machine — and everything else is real. The probe scripts
really execute, the tree digests are really computed over real files, and the
gate really runs on the "far side" from files really shipped there.

That choice is the point. A `unittest.mock` transport would prove the stager
calls the functions it calls. The failures this module exists to catch live in
the probes and the comparisons: a digest that disagrees with the local one for
an intact tree, or agrees for a broken one. Those are only catchable by running
them.

What remains uncovered by construction, and is named in the commit rather than
implied: SSH itself, rsync's own behaviour, and a `SIGKILL` on the far side.
The first two are exercised by the real dry run against the host; the third is
not possible without a production activation target and is deliberately not
attempted here.

THE PROPERTY EVERY TEST SHARES
------------------------------
Nothing in this file may reach the network. `LocalRemote` is a subprocess in a
temp directory, and the one test that asserts on `SshRemote` inspects the
argument vector it would run rather than running it.
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_PKG = os.path.join(os.path.dirname(_HERE), "src")
if _PKG not in sys.path:
    sys.path.insert(0, _PKG)

_SPEC = importlib.util.spec_from_file_location(
    "vector_remote_stage",
    os.path.join(os.path.dirname(_HERE), "scripts", "remote_stage.py"))
rs = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(rs)

from test_publish_release import make_release, T0  # noqa: E402
from vector_tile_gen.release import (  # noqa: E402
    format_release_id,
    scan_tree,
    tree_digest,
)


class _Sandbox(rs.LocalRemote):
    """A far side that can be made to misbehave, one fault at a time."""

    def __init__(self, sandbox, *, fail_push=False, disk_free=None,
                 ssh_down=False, corrupt=None):
        super().__init__(sandbox)
        self.fail_push = fail_push
        self.disk_free = disk_free
        self.ssh_down = ssh_down
        self.corrupt = corrupt          # callable(dest_dir) run after a push
        self.pushes = []
        self.commands = []

    def run(self, argv, *, stdin=None, timeout=300):
        self.commands.append(list(argv))
        if self.ssh_down:
            raise rs.RemoteError("ssh: connect to host port 22: Connection refused")
        if self.disk_free is not None and stdin and "disk_usage" in stdin:
            return 0, json.dumps({"checked": "/", "total": 1 << 40,
                                  "used": 0, "free": self.disk_free}), ""
        return super().run(argv, stdin=stdin, timeout=timeout)

    def push(self, local_dir, remote_dir, *, timeout=3600):
        self.pushes.append((local_dir, remote_dir))
        if self.fail_push:
            return 23, "", "rsync: some files could not be transferred"
        rc = super().push(local_dir, remote_dir, timeout=timeout)
        if self.corrupt and remote_dir.endswith("/release"):
            self.corrupt(remote_dir)
        return rc


class RemoteStageCase(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="vector-remote-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.base = os.path.join(self.tmp, "far", "home", "arm",
                                 "vector-tile-staging")
        self.rid = format_release_id(now_s=T0, git_sha="4d627cd")
        self.source = os.path.join(self.tmp, "bake")
        make_release(self.source, release_id=self.rid, zooms=(11, 12, 13))

    def stager(self, **kw):
        remote = kw.pop("remote", None) or _Sandbox(os.path.join(self.tmp, "sandbox"))
        return rs.RemoteStager(remote, base=self.base, **kw), remote

    def dry_run(self, **kw):
        stager, remote = self.stager(**{k: v for k, v in kw.items()
                                        if k in ("remote", "run_id")})
        report = stager.dry_run(self.source, **{k: v for k, v in kw.items()
                                                if k not in ("remote", "run_id")})
        return stager, remote, report


# ---------------------------------------------------------------------------
# The probe, pinned against the real implementation
# ---------------------------------------------------------------------------

class ProbeFidelityTest(RemoteStageCase):

    def _probe(self, path, *args):
        proc = subprocess.run([sys.executable, "-", path, *args],
                              input=rs.PROBE, capture_output=True, text=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        return json.loads(proc.stdout)

    def test_the_remote_digest_equals_the_local_one(self):
        """The duplicate, pinned.

        The remote has no package installed, so the probe carries its own copy
        of `iter_tile_paths` and `tree_digest`. A copy that drifts would report
        a mismatch for an intact transfer — or, far worse, agree with itself
        about a broken one.
        """
        out = self._probe(self.source)
        local = scan_tree(self.source, census=False)

        self.assertEqual(tree_digest(local["entries"]), out["tree_digest"])
        self.assertEqual(local["tiles_total"], out["tiles_total"])
        self.assertEqual(local["bytes_total"], out["bytes_total"])

    def test_the_probe_reads_the_manifest_and_the_sample(self):
        out = self._probe(self.source)

        self.assertEqual(self.rid, out["release_id"])
        self.assertTrue(out["manifest_sha256"])
        self.assertTrue(out["sample"])
        with open(os.path.join(self.source, "RELEASE.json"), encoding="utf-8") as fh:
            declared = json.load(fh)["integrity"]["sample"]
        for probe in declared:
            self.assertEqual(probe["sha256"], out["sample"][probe["path"]])

    def test_the_probe_notices_a_stray_file(self):
        """`cp -r` MERGES: a stray file is how a previous bake survives."""
        with open(os.path.join(self.source, "11", "21074", "stray.txt"), "w") as fh:
            fh.write("left over")

        out = self._probe(self.source)

        self.assertEqual(1, out["non_tile_count"])
        self.assertIn("11/21074/stray.txt", out["non_tile_files"])

    def test_the_probe_reports_an_absent_directory_rather_than_crashing(self):
        out = self._probe(os.path.join(self.tmp, "nowhere"))
        self.assertFalse(out["exists"])

    def test_detail_mode_lists_every_file(self):
        out = self._probe(self.source, "--detail")
        local = {rel: [d, n] for rel, d, n in scan_tree(self.source, census=False)["entries"]}
        self.assertEqual(local, out["entries"])


# ---------------------------------------------------------------------------
# Path safety
# ---------------------------------------------------------------------------

class PathSafetyTest(unittest.TestCase):

    def test_the_tile_volume_is_refused_as_a_staging_base(self):
        """The one path this must never write to, by its real name."""
        for bad in ("/var/lib/docker/volumes/vector-data-tiles/_data",
                    "/var/lib/docker", "/app/tiles", "/etc", "/"):
            with self.subTest(bad=bad):
                with self.assertRaises(rs.UnsafePath):
                    rs.assert_safe_base(bad)

    def test_a_bare_home_or_tmp_is_refused(self):
        for bad in ("/home", "/tmp", "/srv", "relative/path", ""):
            with self.subTest(bad=bad):
                with self.assertRaises(rs.UnsafePath):
                    rs.assert_safe_base(bad)

    def test_traversal_is_refused(self):
        with self.assertRaises(rs.UnsafePath):
            rs.assert_safe_base("/home/arm/../../var/lib/docker/volumes")

    def test_the_intended_base_is_accepted(self):
        self.assertEqual("/home/arm/vector-tile-staging",
                         rs.assert_safe_base("/home/arm/vector-tile-staging/"))

    def test_a_malformed_run_id_is_refused_before_it_reaches_a_command(self):
        """The check that bounds an `rm -rf`."""
        for bad in ("", "..", "/", "*", "run-x", "run-20260919T213000Z-ZZZZZZZZ",
                    "run-20260919T213000Z-1a2b3c4d/../..", None):
            with self.subTest(bad=bad):
                with self.assertRaises(rs.UnsafePath):
                    rs.assert_safe_run_id(bad)

    def test_a_generated_run_id_is_accepted_and_unique(self):
        ids = {rs.new_run_id() for _ in range(50)}
        self.assertEqual(50, len(ids))
        for rid in ids:
            rs.assert_safe_run_id(rid)

    def test_the_run_directory_is_rebuilt_from_a_validated_id(self):
        """A mutated attribute must not widen what gets deleted."""
        stager = rs.RemoteStager(rs.LocalRemote(tempfile.mkdtemp()),
                                 base="/home/arm/vector-tile-staging")
        stager.run_id = "../../../"
        with self.assertRaises(rs.UnsafePath):
            _ = stager.run_dir


class SshTransportTest(unittest.TestCase):
    """Asserted on the argument vector, never executed."""

    def test_only_an_alias_is_ever_used(self):
        remote = rs.SshRemote("prod")
        self.assertEqual(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "prod"],
            remote._ssh_base())

    def test_batch_mode_is_set_so_a_missing_key_fails_rather_than_prompts(self):
        self.assertIn("BatchMode=yes", rs.SshRemote("prod")._ssh_base())

    def test_arguments_are_quoted_for_the_remote_shell(self):
        """The defect this pins cost a whole operational snapshot.

        `ssh host a b c` joins the arguments with spaces and hands the result
        to the remote shell. A `docker --format {{.Id}}|{{.RestartCount}}`
        therefore arrived as a PIPELINE, and the container inspection returned
        nothing at all while reporting success — the worst kind of read-only
        check, one that cannot fail loudly because it never ran.

        `LocalRemote` executes the argv directly with no shell in between, so
        without quoting here the two transports would mean different things and
        every test in this file would be exercising semantics the real
        transport does not have.
        """
        captured = {}

        class _Result:
            returncode, stdout, stderr = 0, "", ""

        def spy(cmd, **kw):
            captured["cmd"] = cmd
            return _Result()

        real = rs.subprocess.run
        rs.subprocess.run = spy
        try:
            rs.SshRemote("prod").run([
                "docker", "inspect", "vector-tiles",
                "--format", "{{.Id}}|{{.RestartCount}}|{{.State.StartedAt}}"])
        finally:
            rs.subprocess.run = real

        cmd = captured["cmd"]
        self.assertEqual("prod", cmd[-2],
                         "the alias must be the last option before the command")
        self.assertEqual(
            "docker inspect vector-tiles --format "
            "'{{.Id}}|{{.RestartCount}}|{{.State.StartedAt}}'",
            cmd[-1])
        self.assertEqual(1, sum(1 for a in cmd if "docker" in a),
                         "the remote command must be ONE argument, or ssh "
                         "rejoins it and the quoting is undone")

    def test_an_implausible_alias_is_refused(self):
        for bad in ("", "-oProxyCommand=evil", "1.2.3.4 rm -rf /", "a;b"):
            with self.subTest(bad=bad):
                with self.assertRaises(rs.UnsafePath):
                    rs.SshRemote(bad)

    def test_the_module_contains_no_credentials_or_addresses(self):
        """No sshpass, no password, no IP, no hostname. Grepped, not assumed."""
        with open(os.path.join(os.path.dirname(_HERE), "scripts",
                               "remote_stage.py"), encoding="utf-8") as fh:
            text = fh.read()
        for forbidden in ("sshpass", "PasswordAuthentication", "password=",
                          "IdentityFile", "ProxyCommand"):
            self.assertNotIn(forbidden, text, f"found {forbidden!r}")
        import re
        addresses = re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", text)
        self.assertEqual(["127.0.0.1"], sorted(set(addresses)),
                         "only loopback may appear in this file")

    def test_rsync_carries_no_delete_flag(self):
        """The most expensive typo available here, absent by construction.

        Checked against the EXECUTABLE lines only. The comment above the
        command explains why `--delete` is absent, and a naive search of the
        source finds that explanation and calls it a violation.
        """
        import inspect
        src = inspect.getsource(rs.SshRemote.push)
        code = "\n".join(line for line in src.splitlines()
                         if not line.strip().startswith("#"))
        self.assertNotIn("--delete", code)
        self.assertIn("BatchMode=yes", code)


# ---------------------------------------------------------------------------
# The dry run
# ---------------------------------------------------------------------------

class SuccessfulTransferTest(RemoteStageCase):

    def test_a_clean_transfer_verifies_on_the_far_side(self):
        stager, remote, report = self.dry_run()

        self.assertTrue(report["ok"], report["failures"])
        self.assertEqual(self.rid, report["release_id"])
        self.assertEqual(report["local"]["tree_digest"],
                         report["remote"]["tree_digest"])
        self.assertEqual(report["local"]["tiles_total"],
                         report["remote"]["tiles_total"])
        self.assertEqual(report["local"]["bytes_total"],
                         report["remote"]["bytes_total"])

    def test_the_mode_is_stated_in_the_report(self):
        _s, _r, report = self.dry_run()
        self.assertIn("NO ACTIVATION", report["mode"])

    def test_nothing_resembling_activation_is_created(self):
        """The hard rule, checked against the far side's actual filesystem."""
        stager, _r, report = self.dry_run()

        present = []
        for dirpath, dirs, files in os.walk(self.base):
            for name in list(dirs) + list(files):
                if name in ("current", "previous", "releases"):
                    present.append(os.path.join(dirpath, name))
        self.assertEqual([], present, "a pointer or releases/ was created")

    def test_the_transfer_lands_only_inside_the_run_directory(self):
        stager, _r, report = self.dry_run()

        entries = os.listdir(self.base)
        self.assertEqual([stager.run_id], entries,
                         "something was written outside the run directory")

    def test_the_gate_runs_on_the_far_side_and_agrees(self):
        """The same four files, shipped and executed there."""
        _s, _r, report = self.dry_run()

        gate = report["remote_validation"]
        self.assertTrue(gate.get("ok"), gate)
        self.assertEqual(self.rid, gate["release_id"])
        self.assertEqual(0, gate["exit_code"])

    def test_the_far_side_gate_is_byte_identical_to_this_one(self):
        stager, _r, report = self.dry_run()

        for rel, _dst in rs.GATE_FILES:
            here = os.path.join(os.path.dirname(_HERE), rel)
            there = os.path.join(self.base, stager.run_id, "gate", rel)
            with open(here, "rb") as a, open(there, "rb") as b:
                self.assertEqual(a.read(), b.read(), rel)

    def test_a_completion_marker_distinguishes_a_finished_run(self):
        stager, _r, report = self.dry_run()
        marker = os.path.join(self.base, stager.run_id, "COMPLETE")
        self.assertTrue(os.path.isfile(marker))

    def test_the_comparison_is_report_only(self):
        """It cannot fail the run; the staged release is ALLOWED to differ."""
        _s, _r, report = self.dry_run()

        self.assertIn("comparison", report)
        self.assertTrue(report["ok"])


class TransferFaultTest(RemoteStageCase):
    """One fault at a time, each injected on the far side after the push."""

    def _fault(self, corrupt):
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), corrupt=corrupt)
        return self.dry_run(remote=remote)

    def test_a_missing_destination_file_is_detected(self):
        def drop(dest):
            os.remove(os.path.join(dest, "12", "21074", "14003.mvt"))

        _s, _r, report = self._fault(drop)

        self.assertFalse(report["ok"])
        codes = {f["code"] for f in report["failures"]}
        self.assertIn("transfer_file_count_mismatch", codes)
        self.assertIn("transfer_tree_digest_mismatch", codes)
        diff = [s for s in report["steps"] if s["step"] == "transfer_diff"][0]
        self.assertEqual(1, diff["missing_count"])
        self.assertIn("12/21074/14003.mvt", diff["missing"])

    def test_an_altered_destination_file_is_detected(self):
        """Same name, same count, different bytes — invisible to rsync's rc."""
        def alter(dest):
            with open(os.path.join(dest, "11", "21074", "14003.mvt"), "wb") as fh:
                fh.write(b"tampered but plausible")

        _s, _r, report = self._fault(alter)

        self.assertFalse(report["ok"])
        codes = {f["code"] for f in report["failures"]}
        self.assertIn("transfer_tree_digest_mismatch", codes)
        diff = [s for s in report["steps"] if s["step"] == "transfer_diff"][0]
        self.assertEqual(1, diff["altered_count"])
        self.assertEqual(0, diff["missing_count"])

    def test_an_extra_destination_file_is_detected(self):
        def add(dest):
            with open(os.path.join(dest, "13", "21074", "99999.mvt"), "wb") as fh:
                fh.write(b"a tile from the previous bake")

        _s, _r, report = self._fault(add)

        self.assertFalse(report["ok"])
        diff = [s for s in report["steps"] if s["step"] == "transfer_diff"][0]
        self.assertEqual(1, diff["extra_count"])
        self.assertIn("13/21074/99999.mvt", diff["extra"])

    def test_a_stray_non_tile_file_is_detected(self):
        def add(dest):
            with open(os.path.join(dest, "13", "21074", "notes.txt"), "w") as fh:
                fh.write("scratch")

        _s, _r, report = self._fault(add)

        self.assertFalse(report["ok"])
        self.assertIn("transfer_extra_files_mismatch",
                      {f["code"] for f in report["failures"]})

    def test_a_damaged_sample_tile_is_named(self):
        with open(os.path.join(self.source, "RELEASE.json"), encoding="utf-8") as fh:
            victim = json.load(fh)["integrity"]["sample"][0]["path"]

        def alter(dest):
            with open(os.path.join(dest, victim), "wb") as fh:
                fh.write(b"not the sampled bytes")

        _s, _r, report = self._fault(alter)

        self.assertFalse(report["ok"])
        self.assertIn("transfer_sample_mismatch",
                      {f["code"] for f in report["failures"]})
        check = [s for s in report["steps"] if s["step"] == "sample_check"][0]
        self.assertIn(victim, check["mismatched"])

    def test_an_interrupted_transfer_leaves_an_inert_staging_directory(self):
        """rsync exits non-zero; nothing outside the run directory is touched."""
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), fail_push=True)
        stager, _r, report = self.dry_run(remote=remote)

        self.assertFalse(report["ok"])
        self.assertIn("transfer_failed", {f["code"] for f in report["failures"]})
        self.assertEqual([stager.run_id], os.listdir(self.base))
        self.assertFalse(os.path.isfile(
            os.path.join(self.base, stager.run_id, "COMPLETE")),
            "an interrupted run must not look finished")

    def test_a_truncated_transfer_is_caught_even_though_every_file_is_valid(self):
        """3,000 of 18,311 tiles arriving is 3,000 PERFECTLY VALID tiles."""
        def truncate(dest):
            kept = 0
            for dirpath, _d, files in os.walk(dest):
                for f in sorted(files):
                    if f.endswith(".mvt"):
                        kept += 1
                        if kept > 2:
                            os.remove(os.path.join(dirpath, f))

        _s, _r, report = self._fault(truncate)

        self.assertFalse(report["ok"])
        self.assertIn("transfer_file_count_mismatch",
                      {f["code"] for f in report["failures"]})


class RefusalTest(RemoteStageCase):

    def test_insufficient_remote_disk_is_refused_before_the_transfer(self):
        # Zero, not a small number: the fixture release is a few hundred
        # bytes, so "1 KB free" is comfortably enough and the guard would
        # correctly not fire. A test that passes because the threshold was
        # never crossed proves nothing about the threshold.
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), disk_free=0)
        stager, remote, report = self.dry_run(remote=remote)

        self.assertFalse(report["ok"])
        self.assertIn("insufficient_remote_disk",
                      {f["code"] for f in report["failures"]})
        self.assertEqual([], remote.pushes, "nothing was transferred")
        self.assertFalse(os.path.exists(self.base),
                         "not even the run directory was created")

    def test_an_ssh_failure_is_reported_as_a_transport_failure(self):
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), ssh_down=True)
        stager, remote, report = self.dry_run(remote=remote)

        self.assertFalse(report["ok"])
        self.assertIn("ssh_failed", {f["code"] for f in report["failures"]})
        self.assertEqual([], remote.pushes)

    def test_a_local_validation_failure_transfers_nothing(self):
        """The gate runs HERE first, so a bad release never crosses the wire."""
        os.remove(os.path.join(self.source, "12", "21074", "14003.mvt"))
        stager, remote, report = self.dry_run()

        self.assertFalse(report["ok"])
        self.assertIn("local_validation_failed",
                      {f["code"] for f in report["failures"]})
        self.assertEqual([], remote.pushes)
        self.assertFalse(os.path.exists(self.base))

    def test_a_source_without_a_manifest_is_refused(self):
        nameless = os.path.join(self.tmp, "nameless")
        os.makedirs(os.path.join(nameless, "11", "21074"))
        with open(os.path.join(nameless, "11", "21074", "14003.mvt"), "wb") as fh:
            fh.write(b"tiles but no identity")
        stager, remote = self.stager()

        report = stager.dry_run(nameless)

        self.assertFalse(report["ok"])
        self.assertIn("source_not_a_release",
                      {f["code"] for f in report["failures"]})
        self.assertEqual([], remote.pushes)


class ConcurrencyGuardTest(RemoteStageCase):

    def test_an_unfinished_run_refuses_a_second_dry_run(self):
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), fail_push=True)
        first, _r, report = self.dry_run(remote=remote)
        self.assertFalse(report["ok"])

        second, remote2, report2 = self.dry_run()

        self.assertFalse(report2["ok"])
        self.assertIn("concurrent_run", {f["code"] for f in report2["failures"]})
        self.assertEqual([], remote2.pushes)

    def test_a_finished_run_does_not_block_the_next_one(self):
        """A completed run is evidence, not an obstruction."""
        first, _r, report = self.dry_run()
        self.assertTrue(report["ok"], report["failures"])

        second, _r2, report2 = self.dry_run()

        self.assertTrue(report2["ok"], report2["failures"])
        self.assertNotEqual(first.run_id, second.run_id)

    def test_the_guard_can_be_overridden_deliberately(self):
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), fail_push=True)
        self.dry_run(remote=remote)

        _s, _r, report = self.dry_run(allow_concurrent=True)

        self.assertTrue(report["ok"], report["failures"])

    def test_two_runs_cannot_share_a_staging_directory(self):
        """`mkdir` without -p on the final component is the guard."""
        run_id = rs.new_run_id()
        first, _r, report = self.dry_run(run_id=run_id)
        self.assertTrue(report["ok"], report["failures"])

        second, _r2, report2 = self.dry_run(run_id=run_id, allow_concurrent=True)

        self.assertFalse(report2["ok"])
        self.assertIn("run_dir_failed", {f["code"] for f in report2["failures"]})


class CleanupTest(RemoteStageCase):

    def test_cleanup_requires_explicit_confirmation(self):
        stager, _r, report = self.dry_run()

        plan = stager.cleanup()

        self.assertFalse(plan["ok"])
        self.assertIn("not_confirmed", {f["code"] for f in plan["failures"]})
        self.assertTrue(os.path.isdir(os.path.join(self.base, stager.run_id)))

    def test_cleanup_removes_exactly_the_run_directory(self):
        stager, _r, report = self.dry_run()
        sibling = os.path.join(self.base, "run-20260101T000000Z-aaaaaaaa")
        os.makedirs(sibling)

        done = stager.cleanup(confirm=True)

        self.assertTrue(done["ok"], done["failures"])
        self.assertFalse(os.path.exists(os.path.join(self.base, stager.run_id)))
        self.assertTrue(os.path.isdir(sibling),
                        "cleanup removed something outside its own run")
        self.assertTrue(os.path.isdir(self.base), "the base itself survives")

    def test_cleanup_verifies_the_directory_is_actually_gone(self):
        stager, _r, _ = self.dry_run()
        done = stager.cleanup(confirm=True)
        self.assertEqual("GONE", done["verified"])

    def test_cleanup_refuses_to_remove_the_base(self):
        stager, _r, _ = self.dry_run()
        stager.run_id = ""
        with self.assertRaises(rs.UnsafePath):
            stager.cleanup(confirm=True)

    def test_cleanup_after_an_interrupted_transfer_still_works(self):
        remote = _Sandbox(os.path.join(self.tmp, "sandbox"), fail_push=True)
        stager, _r, report = self.dry_run(remote=remote)
        self.assertFalse(report["ok"])

        done = stager.cleanup(confirm=True)

        self.assertTrue(done["ok"], done["failures"])
        self.assertEqual([], os.listdir(self.base))


class RerunTest(RemoteStageCase):

    def test_the_same_dry_run_twice_touches_nothing_resembling_production(self):
        """Requirement 7's rerun case.

        Two complete runs, two independent staging directories, and at no point
        anything named `current`, `previous` or `releases`.
        """
        first, _r, r1 = self.dry_run()
        self.assertTrue(r1["ok"], r1["failures"])
        second, _r2, r2 = self.dry_run()
        self.assertTrue(r2["ok"], r2["failures"])

        self.assertEqual(sorted([first.run_id, second.run_id]),
                         sorted(os.listdir(self.base)))
        self.assertEqual(r1["local"]["tree_digest"], r2["local"]["tree_digest"])
        for dirpath, dirs, files in os.walk(self.base):
            for name in list(dirs) + list(files):
                self.assertNotIn(name, ("current", "previous", "releases"))

    def test_a_rerun_produces_the_same_digests(self):
        _s1, _r1, r1 = self.dry_run()
        _s2, _r2, r2 = self.dry_run()

        self.assertEqual(r1["remote"]["tree_digest"], r2["remote"]["tree_digest"])
        self.assertEqual(r1["remote"]["manifest_sha256"],
                         r2["remote"]["manifest_sha256"])


if __name__ == "__main__":
    unittest.main()
