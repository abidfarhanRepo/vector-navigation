"""The migration preflight: does it say no when it should?

WHAT THESE TESTS ARE ACTUALLY FOR
---------------------------------
A preflight that passes on a healthy host proves very little — a function that
returns "GO" unconditionally does that too. What has to be true is that it
REFUSES, specifically and for the right reason, on each of the eight conditions
that would make the migration unsafe. So one test fixes a healthy host and the
rest break exactly one thing about it and require the matching refusal.

The host is a scripted double: each remote command is matched against a table
of canned responses taken from the real host's actual output. That is the right
shape here, because the commands are `docker inspect`, `sudo stat` and `df` —
there is nothing to execute locally, and the thing under test is how their
output is interpreted.

The one exception is the rename probe, which is a real script doing real
filesystem work. `RenameProbeTest` runs it for real in a temp directory,
because "atomic rename works on this filesystem" is not a claim a mock can
support.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_PKG = os.path.join(os.path.dirname(_HERE), "src")
if _PKG not in sys.path:
    sys.path.insert(0, _PKG)
_SCRIPTS = os.path.join(os.path.dirname(_HERE), "scripts")
if _SCRIPTS not in sys.path:
    sys.path.insert(0, _SCRIPTS)

_SPEC = importlib.util.spec_from_file_location(
    "vector_migration_preflight",
    os.path.join(_SCRIPTS, "migration_preflight.py"))
mp = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(mp)


# Taken from the real host on 2026-09-20. Changing these means the plan's
# assumptions changed, which is exactly what `container_mismatch` is for.
REAL_ID = "efea8c31ef9e17f3689d8e6c894e0335a307d42a7949c8a25e65f66957041032"
REAL_IMAGE_ID = "sha256:abfc66a19255ac769566bf0ddcc4fc8484eecaf98501a753f13705b1aa068a22"
REAL_STARTED = "2026-09-14T08:07:23.07331472Z"
REAL_DIGEST = "ca07394aeab5d58a01627f1d05bbb06e88571022c1cf4a9519d83bd20a8a29ce"
REAL_TILES = 18311
REAL_BYTES = 62244848
REAL_DEV = "64512"
# The release the migration actually produced, read off the host 2026-09-20.
REAL_RELEASE = "legacy-import-20260920T041515Z-ca07394aeab5d58a"


class FakeHost:
    """A scripted far side. One knob per thing that can be wrong."""

    def __init__(self, **broken):
        self.broken = broken
        self.commands = []
        self.tree_calls = 0

    # -- the pieces a test can break ----------------------------------

    def _inspect_container(self):
        return "\n".join([
            REAL_ID,
            self.broken.get("image", "vector-tiles:latest"),
            REAL_IMAGE_ID,
            "0",
            REAL_STARTED,
            self.broken.get("status", "running"),
            self.broken.get("project", "vector"),
            self.broken.get("service", "tiles"),
            "/home/arm/vector-prod/docker-compose.yml",
        ])

    def _mounts(self):
        if "mounts" in self.broken:
            return self.broken["mounts"]
        return ("volume|vector-data-glyphs|/app/glyphs|false\n"
                "volume|vector-data-tiles|/app/tiles|false\n")

    def _env(self):
        default = "/app/tiles/current" if self.broken.get("migrated") else "/app/tiles"
        return ("PORT=3000\n"
                "TILE_DIR=%s\n"
                "GLYPH_DIR=/app/glyphs\n" % self.broken.get("tile_dir", default))

    def _tree(self):
        self.tree_calls += 1
        digest = REAL_DIGEST
        if self.broken.get("drift") and self.tree_calls > 1:
            digest = "d" * 64
        return json.dumps({
            "root": "/app/tiles", "exists": True,
            "tiles_total": REAL_TILES, "bytes_total": REAL_BYTES,
            "tree_digest": digest, "manifest_sha256": None,
            "non_tile_count": 1, "non_tile_files": ["VERSION.json"],
        })

    def _listing(self):
        entries = ["6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
                   "VERSION.json"]
        if self.broken.get("migrated"):
            entries += ["current", "releases"]
        entries += self.broken.get("extra_entries", [])
        return "\n".join(entries) + "\n"

    # The three reads that establish whether the migration is COMPLETE, as
    # opposed to merely having been started by somebody. `pointer_dangles` and
    # `manifest_disagrees` are the two ways a host can look migrated and not
    # be, and both must come out `indeterminate`.
    def _release_dir(self):
        return self.broken.get("release_id", REAL_RELEASE)

    def _readlink(self):
        if not self.broken.get("migrated") or self.broken.get("pointer_missing"):
            return ""
        return "releases/" + self._release_dir() + "\n"

    def _releases(self):
        if not self.broken.get("migrated") or self.broken.get("pointer_dangles"):
            return ""
        return self._release_dir() + "\n"

    def _manifest(self):
        if not self.broken.get("migrated"):
            return ""
        declared = ("some-other-release" if self.broken.get("manifest_disagrees")
                    else self._release_dir())
        return json.dumps({"release_id": declared, "schema_version": 1})

    # -- the transport surface ----------------------------------------

    def run(self, argv, *, stdin=None, timeout=300):
        self.commands.append(list(argv))
        joined = " ".join(argv)

        if self.broken.get("ssh_down"):
            raise mp.RemoteError("ssh: connect to host: Connection refused")

        if argv[:2] == ["docker", "inspect"] and "--format" in argv:
            fmt = argv[argv.index("--format") + 1]
            if ".RestartCount" in fmt:
                return 0, self._inspect_container(), ""
            if ".Mounts" in fmt:
                return 0, self._mounts(), ""
            if ".Config.Env" in fmt:
                return 0, self._env(), ""
        if argv[:3] == ["docker", "volume", "inspect"]:
            return 0, "/var/lib/docker/volumes/vector-data-tiles/_data|local", ""
        if argv[:2] == ["docker", "exec"] and stdin:
            return 0, self._tree(), ""
        if argv[:2] == ["docker", "exec"] and "urllib.request" in joined:
            return 0, json.dumps({
                "healthz": '{"status":"ok"}', "healthz_s": 0.02,
                "version": '{"epoch": 1789378960, "minzoom": 6, "maxzoom": 15}',
                "version_s": 0.42}), ""
        if argv[:2] == ["docker", "exec"] and "readlink" in joined:
            return 0, self._readlink(), ""
        if argv[:2] == ["docker", "exec"] and "/releases" in joined:
            return 0, self._releases(), ""
        if argv[:2] == ["docker", "exec"] and "RELEASE.json" in joined:
            return 0, self._manifest(), ""
        if argv[:2] == ["docker", "exec"] and "ls -1" in joined:
            return 0, self._listing(), ""
        if argv[0] == "df" or (argv[0] == "sh" and "df -T" in joined):
            if argv[0] == "df":
                avail = self.broken.get("disk_free", 309016510464)
                return 0, ("size used avail target\n"
                           "488510864 165775700 %d /\n" % avail), ""
            return 0, "/dev/mapper/ubuntu--vg-ubuntu--lv ext4 ... /\n", ""
        if argv[:3] == ["sudo", "-n", "stat"]:
            if "%d" in argv:
                return 0, self.broken.get("volume_dev", REAL_DEV) + "\n", ""
            return 0, "1000 1000 755\n", ""
        if argv[:3] == ["sudo", "-n", "true"]:
            return 0, "", ""
        if argv[:2] == ["stat", "-c"]:
            return 0, REAL_DEV + "\n", ""
        if argv[0] == "sh" and "sha256sum" in joined:
            if self.broken.get("compose_unreadable"):
                return 1, "", "cannot read"
            return 0, ("bfd0eaaa8460c3b69789be346ae71e221581e4882f9b573304867"
                       "cc31711d01c  /home/arm/vector-prod/docker-compose.yml\n"), ""
        if argv[0] == "sh" and "grep -n 'TILE_DIR'" in joined:
            return 0, self.broken.get(
                "tile_dir_lines", "134:      TILE_DIR: /app/tiles\n"), ""
        if argv[:2] == ["test", "-w"]:
            return (1 if self.broken.get("compose_dir_readonly") else 0), "", ""
        if argv[0] == "sh" and "id -nG" in joined:
            return 0, "arm sudo docker\n", ""
        if argv[:2] == ["python3", "-"] and stdin and "rename" in (stdin or ""):
            if self.broken.get("rename_fails"):
                return 0, json.dumps({"ok": False, "error": "EXDEV",
                                      "cleaned_up": True}), ""
            return 0, json.dumps({
                "ok": True, "symlink_supported": True,
                "rename_over_symlink": True, "swaps": 200,
                "observed": ["AAAA", "BBBB"], "errors": [],
                "before": "AAAA", "after": "BBBB", "cleaned_up": True}), ""
        return 0, "", ""


class PreflightCase(unittest.TestCase):

    def run_preflight(self, **broken):
        host = FakeHost(**broken)
        report = mp.Preflight(host).run()
        return host, report

    def codes(self, report):
        return {r["code"] for r in report["refusals"]}

    def check(self, report, name):
        for c in report["checks"]:
            if c["check"] == name:
                return c
        raise AssertionError(f"no check named {name!r} in "
                             f"{[c['check'] for c in report['checks']]}")


class HealthyHostTest(PreflightCase):

    def test_the_real_current_state_yields_GO(self):
        _host, report = self.run_preflight()

        self.assertEqual("GO", report["verdict"], report["refusals"])
        self.assertEqual([], report["refusals"])
        self.assertTrue(report["ok"])

    def test_every_required_fact_is_reported(self):
        """Requirement 1, item by item.

        A verdict without the evidence behind it is an opinion; each of these
        is a number somebody has to be able to compare against the host after
        the migration.
        """
        _host, report = self.run_preflight()

        container = self.check(report, "container")["observed"]
        self.assertEqual(REAL_ID, container["id"])
        self.assertEqual("vector-tiles:latest", container["image"])
        self.assertEqual("0", container["restart_count"])
        self.assertEqual(REAL_STARTED, container["started_at"])
        self.assertEqual("vector", container["compose_project"])

        self.assertEqual("/app/tiles", self.check(report, "tile_dir")["observed"]["tile_dir"])
        self.assertEqual("/var/lib/docker/volumes/vector-data-tiles/_data",
                         self.check(report, "volume")["observed"]["mountpoint"])
        mounts = self.check(report, "mounts")["observed"]["mounts"]
        self.assertFalse(mounts["vector-data-tiles"]["rw"])

        tree = self.check(report, "tree_before")["observed"]
        self.assertEqual(REAL_TILES, tree["tiles_total"])
        self.assertEqual(REAL_BYTES, tree["bytes_total"])
        self.assertEqual(REAL_DIGEST, tree["tree_digest"])
        self.assertEqual(["6", "7", "8", "9", "10", "11", "12", "13", "14", "15"],
                         tree["zooms"])

        edge = self.check(report, "edge")["observed"]
        self.assertIn("epoch", edge["version"])
        self.assertFalse(edge["release_aware"],
                         "production predates Commit 4 and must report no release")

    def test_the_target_layout_is_stated(self):
        _host, report = self.run_preflight()

        self.assertEqual("/app/tiles", report["from_tile_dir"])
        self.assertEqual("/app/tiles/current", report["to_tile_dir"])

    def test_the_serving_impact_of_preparation_is_stated_not_discovered(self):
        """The old server keeps serving, and `/tiles/version` gets slower.

        Both trees present means the mtime walk sees roughly twice the files,
        and its answer changes because the copies are newer. Harmless, and the
        kind of thing that is alarming at 3am if nobody wrote it down.
        """
        _host, report = self.run_preflight()

        obs = self.check(report, "serving_during_preparation")["observed"]
        self.assertEqual(0.42, obs["version_endpoint_seconds_now"])
        self.assertEqual(0.84, obs["version_endpoint_seconds_estimated_during"])
        self.assertEqual(REAL_TILES * 2, obs["tiles_while_both_present"])
        self.assertTrue(obs["epoch_will_change"])

    def test_a_write_route_into_the_volume_is_identified(self):
        """The volume is mounted READ-ONLY, so the migration needs a route in.

        Discovering that mid-migration would be a stop halfway through.
        """
        _host, report = self.run_preflight()

        route = self.check(report, "write_route")["observed"]
        self.assertTrue(route["passwordless_sudo"])
        self.assertTrue(route["docker_group"])
        self.assertEqual("1000", route["volume_uid"])
        self.assertEqual(2, len(route["routes"]))

    def test_the_preflight_writes_nothing_to_the_volume(self):
        """Requirement 8, asserted against every command it issued."""
        host, _report = self.run_preflight()

        # Matched on WORDS, not substrings. `df -T /home/arm | tail -1`
        # contains the characters "rm " and is perfectly harmless; a check
        # that flags it would be turned off rather than fixed.
        mutating = {"mkdir", "rmdir", "rm", "mv", "cp", "ln", "touch",
                    "truncate", "dd", "tee", "chmod", "chown", "install"}
        for argv in host.commands:
            words = []
            for token in argv:
                words.extend(token.replace("|", " ").replace(";", " ").split())
            hits = mutating & set(words)
            self.assertEqual(set(), hits,
                             f"preflight issued a mutating command: {argv}")
            joined = " ".join(argv)
            self.assertNotIn("docker compose", joined)
            self.assertNotIn("up -d", joined)
            if "/app/tiles" in joined:
                self.assertTrue(
                    any(k in joined for k in ("ls -1", "inspect", "PROBE_ROOT")),
                    f"unexpected command touching the volume: {joined}")


class RefusalTest(PreflightCase):
    """One broken thing per test, and the specific refusal it must produce."""

    def test_an_unexpected_mount_refuses(self):
        _host, report = self.run_preflight(
            mounts="volume|vector-data-tiles|/app/tiles|false\n"
                   "bind|/home/arm/somewhere|/app/extra|true\n")

        self.assertEqual("NO-GO", report["verdict"])
        self.assertIn("unexpected_mounts", self.codes(report))

    def test_a_read_write_tile_mount_refuses(self):
        """Read-only is what stops the running server altering the tree the
        migration is restructuring underneath it."""
        _host, report = self.run_preflight(
            mounts="volume|vector-data-tiles|/app/tiles|true\n"
                   "volume|vector-data-glyphs|/app/glyphs|false\n")

        self.assertIn("unexpected_mounts", self.codes(report))
        self.assertIn("READ-WRITE", self.check(report, "mounts")["detail"])

    def test_a_missing_glyph_mount_refuses(self):
        _host, report = self.run_preflight(
            mounts="volume|vector-data-tiles|/app/tiles|false\n")

        self.assertIn("unexpected_mounts", self.codes(report))

    def test_a_different_image_refuses(self):
        _host, report = self.run_preflight(image="vector-tiles:experimental")

        self.assertIn("container_mismatch", self.codes(report))

    def test_a_different_compose_project_refuses(self):
        _host, report = self.run_preflight(project="vector-prod")

        self.assertIn("container_mismatch", self.codes(report))

    def test_a_container_that_is_not_running_refuses(self):
        _host, report = self.run_preflight(status="restarting")

        self.assertIn("container_mismatch", self.codes(report))

    def test_an_already_migrated_tile_dir_refuses(self):
        """If TILE_DIR is already the target, this has been done before and
        the plan does not know what state it was left in."""
        _host, report = self.run_preflight(tile_dir="/app/tiles/current")

        self.assertIn("container_mismatch", self.codes(report))
        self.assertIn("may already have happened",
                      " ".join(r["detail"] for r in report["refusals"]))

    def test_insufficient_disk_refuses(self):
        _host, report = self.run_preflight(disk_free=1024)

        self.assertIn("insufficient_disk", self.codes(report))
        disk = self.check(report, "disk")["observed"]
        self.assertGreater(disk["required"], disk["free"])

    def test_the_disk_requirement_covers_both_trees(self):
        """The migration COPIES rather than moves, so both must fit at once."""
        _host, report = self.run_preflight()

        disk = self.check(report, "disk")["observed"]
        self.assertGreaterEqual(disk["required"], REAL_BYTES * 2)

    def test_digest_drift_during_inspection_refuses(self):
        """Something else is writing to the volume, and every number gathered
        describes a tree that no longer exists."""
        _host, report = self.run_preflight(drift=True)

        self.assertIn("digest_drift", self.codes(report))
        self.assertFalse(self.check(report, "digest_stable")["ok"])

    def test_existing_target_paths_refuse(self):
        for name in ("releases", "current", "previous"):
            with self.subTest(name=name):
                _host, report = self.run_preflight(extra_entries=[name])
                self.assertIn("target_paths_exist", self.codes(report))

    def test_a_failed_rename_probe_refuses(self):
        _host, report = self.run_preflight(rename_fails=True)

        self.assertIn("rename_probe_failed", self.codes(report))

    def test_a_probe_on_another_device_refuses_rather_than_reassures(self):
        """A reassuring result from the wrong filesystem is worse than none."""
        _host, report = self.run_preflight(volume_dev="66000")

        self.assertIn("rename_probe_failed", self.codes(report))
        self.assertFalse(self.check(report, "device")["ok"])
        self.assertFalse(self.check(report, "rename_probe")["ok"])

    def test_an_unreadable_compose_refuses(self):
        _host, report = self.run_preflight(compose_unreadable=True)

        self.assertIn("compose_not_restorable", self.codes(report))

    def test_a_compose_directory_that_cannot_hold_a_backup_refuses(self):
        """No backup means no restore, and no restore means no rollback."""
        _host, report = self.run_preflight(compose_dir_readonly=True)

        self.assertIn("compose_not_restorable", self.codes(report))

    def test_an_ambiguous_tile_dir_line_refuses(self):
        """Two TILE_DIR lines means the edit target is not unambiguous."""
        _host, report = self.run_preflight(
            tile_dir_lines="134:      TILE_DIR: /app/tiles\n"
                           "210:      TILE_DIR: /app/tiles\n")

        self.assertIn("compose_not_restorable", self.codes(report))

    def test_an_unreachable_host_refuses(self):
        _host, report = self.run_preflight(ssh_down=True)

        self.assertEqual("NO-GO", report["verdict"])
        self.assertIn("ssh_unavailable", self.codes(report))

    def test_every_refusal_code_is_a_known_one(self):
        """A typo in a refusal code would be a refusal nobody can grep for."""
        for broken in ({"disk_free": 1}, {"drift": True}, {"rename_fails": True},
                       {"image": "other"}, {"ssh_down": True},
                       {"extra_entries": ["current"]},
                       {"compose_unreadable": True},
                       {"mounts": "volume|x|/y|true\n"}):
            with self.subTest(broken=broken):
                _h, report = self.run_preflight(**broken)
                for refusal in report["refusals"]:
                    self.assertIn(refusal["code"], mp.REFUSALS)

    def test_there_is_no_way_to_override_a_refusal(self):
        """A preflight that can be overridden is a preflight that will be.

        Read off the PARSER, not the source: the module docstring discusses
        `--force` at length to explain its absence, and a source grep would
        find the explanation and call it the flag.
        """
        flags = set()
        for action in mp.build_parser()._actions:
            flags.update(action.option_strings)

        self.assertEqual(
            {"-h", "--help", "--alias", "--container", "--compose",
             "--probe-dir", "--json"},
            flags,
            "a new CLI flag appeared; if it can skip a check or override a "
            "refusal, it does not belong here")


class RenameProbeTest(unittest.TestCase):
    """The probe, run for real. A mock cannot support this claim."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="vector-preflight-test-")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _probe(self, where):
        proc = subprocess.run([sys.executable, "-", where],
                              input=mp.RENAME_PROBE, capture_output=True,
                              text=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        return json.loads(proc.stdout)

    def test_the_probe_demonstrates_an_atomic_swap(self):
        out = self._probe(self.tmp)

        self.assertTrue(out["ok"], out)
        self.assertTrue(out["symlink_supported"])
        self.assertTrue(out["rename_over_symlink"])
        self.assertEqual("AAAA", out["before"])
        self.assertEqual("BBBB", out["after"])

    def test_a_reader_never_sees_the_pointer_missing(self):
        out = self._probe(self.tmp)

        self.assertEqual(200, out["swaps"])
        self.assertEqual([], out["errors"])
        self.assertEqual(["AAAA", "BBBB"], out["observed"])

    def test_the_probe_cleans_up_after_itself(self):
        """It runs outside the volume, but it still leaves nothing behind."""
        out = self._probe(self.tmp)

        self.assertTrue(out["cleaned_up"])
        self.assertFalse(os.path.exists(out["base"]))
        self.assertEqual([], os.listdir(self.tmp))

    def test_the_probe_reports_rather_than_raises_on_a_bad_directory(self):
        out = self._probe(os.path.join(self.tmp, "does-not-exist"))

        self.assertFalse(out.get("ok"))
        self.assertIn("error", out)


if __name__ == "__main__":
    unittest.main()


class MigrationStateTest(PreflightCase):
    """The tool outlived the change it prepares for. Does it know that?

    The migration ran on 2026-09-20. From that moment this preflight cannot
    return GO and never will again, and the interesting question stopped being
    "does it refuse" and became "does it refuse for a reason an operator can
    act on". These tests hold the line that the second answer was added
    without moving the first: GO still needs an empty refusal list, every
    refusal is still recorded and printed, and a migrated host with anything
    else wrong with it is still a flat NO-GO.
    """

    def test_a_migrated_host_is_not_merely_a_refusal(self):
        _host, report = self.run_preflight(migrated=True)

        self.assertEqual("ALREADY-MIGRATED", report["verdict"])
        self.assertEqual("complete", report["migration_state"])
        self.assertEqual(REAL_RELEASE, report["active_release"])

    def test_already_migrated_is_not_a_pass(self):
        """The whole risk of adding a third verdict is that it reads as GO."""
        _host, report = self.run_preflight(migrated=True)

        self.assertFalse(report["ok"])
        self.assertNotEqual("GO", report["verdict"])

    def test_the_refusals_are_still_reported_in_full(self):
        """Distinguishing them is not the same as suppressing them."""
        _host, report = self.run_preflight(migrated=True)

        self.assertEqual({"container_mismatch", "target_paths_exist"},
                         self.codes(report))
        self.assertTrue(all(r["post_migration"] for r in report["refusals"]))

    def test_a_migrated_host_exits_3_not_0(self):
        report = {"ok": False, "verdict": "ALREADY-MIGRATED", "refusals": [],
                  "checks": [], "from_tile_dir": "/app/tiles",
                  "to_tile_dir": "/app/tiles/current", "op": "x", "mode": "y",
                  "migration_state": "complete"}
        self.assertEqual(3, mp.exit_code_for(report))
        self.assertEqual(0, mp.exit_code_for(dict(report, ok=True, verdict="GO")))
        self.assertEqual(1, mp.exit_code_for(dict(report, verdict="NO-GO")))

    def test_an_unmigrated_host_still_says_GO_and_says_so(self):
        _host, report = self.run_preflight()

        self.assertEqual("GO", report["verdict"])
        self.assertEqual("pre-migration", report["migration_state"])
        self.assertTrue(report["ok"])

    def test_a_real_problem_on_a_migrated_host_is_still_NO_GO(self):
        """The failure this verdict could plausibly introduce, one knob at a
        time: a migrated host that is ALSO broken must not be waved through."""
        for knob, code in (({"status": "restarting"}, "container_mismatch"),
                           ({"drift": True}, "digest_drift"),
                           ({"disk_free": 1024}, "insufficient_disk"),
                           ({"rename_fails": True}, "rename_probe_failed"),
                           ({"compose_unreadable": True}, "compose_not_restorable"),
                           ({"mounts": "volume|vector-data-tiles|/app/tiles|true\n"},
                            "unexpected_mounts")):
            with self.subTest(**knob):
                _host, report = self.run_preflight(migrated=True, **knob)
                self.assertEqual("NO-GO", report["verdict"])
                self.assertIn(code, self.codes(report))
                self.assertFalse(report["ok"])

    def test_a_dangling_pointer_is_indeterminate_not_complete(self):
        """`current` exists and names a release that is not in `releases/`.
        Something happened here; it was not this migration succeeding."""
        _host, report = self.run_preflight(migrated=True, pointer_dangles=True)

        self.assertEqual("indeterminate", report["migration_state"])
        self.assertEqual("NO-GO", report["verdict"])

    def test_a_manifest_naming_another_release_is_indeterminate(self):
        _host, report = self.run_preflight(migrated=True, manifest_disagrees=True)

        self.assertEqual("indeterminate", report["migration_state"])
        self.assertEqual("NO-GO", report["verdict"])

    def test_target_paths_without_the_tile_dir_change_is_not_a_migration(self):
        """Half a migration — a `current` in the volume while the container
        still reads the flat tree — is the state the original refusal was
        written for, and it must keep that refusal."""
        _host, report = self.run_preflight(extra_entries=["current"])

        self.assertEqual("NO-GO", report["verdict"])
        self.assertIn("target_paths_exist", self.codes(report))
        self.assertFalse(
            [r for r in report["refusals"] if r["post_migration"]])

    def test_the_migration_state_check_reads_and_nothing_else(self):
        """It inspects the volume the migration would have written to, so the
        verbs it uses there are the whole of its safety argument."""
        host, _report = self.run_preflight(migrated=True)

        probes = [a for a in host.commands
                  if a[:2] == ["docker", "exec"] and a[-1].startswith(
                      ("readlink ", "ls -1 /app/tiles/releases",
                       "cat /app/tiles/current/RELEASE.json"))]
        self.assertEqual(3, len(probes), probes)
        for argv in probes:
            self.assertNotIn("-w", argv)
            self.assertTrue(argv[-1].startswith(("readlink", "ls -1", "cat")), argv)
