#!/usr/bin/env python3
"""Read-only preflight for the one-time TILE_DIR migration.

THE CHANGE THIS PREPARES FOR
----------------------------
``TILE_DIR: /app/tiles`` becomes ``TILE_DIR: /app/tiles/current``. One line in
one compose file, and one controlled container recreate. Everything V7.7 has
built — the manifest, the release-aware server, the publisher, the remote
transport — is unreachable in production until that line changes, because the
volume the tile server reads is a flat tree with no ``current`` in it.

It is also the only step in V7.7 that cannot be done without a brief outage,
and the only one that can leave production serving nothing. Hence a preflight
that runs first, changes nothing, and is allowed to say no.

WHAT THIS DOES NOT DO
---------------------
It does not migrate. It creates no directory, no symlink and no release inside
the tile volume; it does not edit compose; it does not recreate, restart or
otherwise touch ``vector-tiles`` or any other service; it publishes no tile
and bakes nothing. The only bytes it writes anywhere are in a temporary probe
directory **outside** the volume, which it removes.

WHY THE RENAME PROBE IS NOT IN THE VOLUME
-----------------------------------------
Atomic ``rename(2)`` is a property of a **filesystem**, not of a directory. The
volume, ``/home/arm`` and ``/var/tmp`` are all on device 64512 —
``/dev/mapper/ubuntu--vg-ubuntu--lv``, ext4 — so a probe run on that device
outside the volume is evidence about the same filesystem the migration will
use. :meth:`Preflight.check_device` asserts that equality **first**, and the
probe is refused if the devices differ, because then it would be evidence about
something else.

What this cannot prove read-only is that the *container* traverses a symlinked
``TILE_DIR``. That was production-verified in V7.7 reconnaissance §4.1 (a
symlink swap under the running container, 2,000 swaps, eight reader threads,
zero ``ENOENT``), and the runbook re-verifies it during preparation, before the
compose change, while the old server is still serving the flat tree.

THE REFUSALS
------------
``insufficient_disk``          the copy would not fit, with margin
``unexpected_mounts``          the mount topology is not what the plan assumes
``container_mismatch``         image, compose project or service is not expected
``digest_drift``               the active tree changed WHILE being inspected
``target_paths_exist``         ``releases``/``current``/``previous`` already there
``ssh_unavailable``            the host cannot be reached or inspected
``rename_probe_failed``        atomic rename could not be demonstrated
``compose_not_restorable``     the current compose could not be captured to restore

Any one of them is a NO-GO. There is no ``--force``: a preflight that can be
overridden is a preflight that will be.

HISTORICAL PREFLIGHT IS NOT CURRENT STATE
-----------------------------------------
Everything above is written in the future tense, because it was written before
the migration. The migration has since been executed — 2026-09-20, 05:25Z to
05:27Z — and that changes what a run of this tool MEANS without changing a
single one of its checks.

Run against the migrated host it returns two refusals, and both of them are
the migration reporting itself done rather than anything being wrong:

    container_mismatch    TILE_DIR is already '/app/tiles/current'
    target_paths_exist    the volume already contains current, releases

Reporting that as an undifferentiated ``NO-GO`` is accurate and useless. It is
the same word this tool uses for a read-write tile mount and for a tree being
written to underneath it, and an operator who sees ``NO-GO`` on a healthy host
learns to stop reading the refusals — which is the precise habit a preflight
exists to prevent. So there is a third verdict, ``ALREADY-MIGRATED``, and what
matters about it is what it is NOT:

* it is **not a pass**. ``report["ok"]`` stays false and nothing here
  authorises anything. Its exit code is 3, distinct from both 0 and 1, so a
  caller must opt into treating it as acceptable.
* it is **not inferred from the refusal list**. It is asserted from the host by
  :meth:`Preflight.check_migration_state`, which resolves ``current``, lists
  ``releases/`` and reads ``release_id`` out of ``RELEASE.json``, and requires
  the three to name one release. A ``TILE_DIR`` pointing at a ``current`` that
  does not resolve is ``indeterminate``, and indeterminate is a ``NO-GO``.
* it does **not absorb any other refusal**. Every refusal records whether it is
  a consequence of the migration having happened, and the verdict is
  ``ALREADY-MIGRATED`` only when every refusal in the report is one. A stopped
  container, a read-write mount or a drifting digest on a migrated host is
  still ``NO-GO``, and still names its own reason.

No check was relaxed, removed, reordered or given an escape hatch to make this
verdict reachable, and the conditions under which this tool says ``GO`` are
exactly what they were. The only thing that changed is that it can now tell
"this migration must not start" apart from "this migration is over".
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _base in (os.path.join(_ROOT, "src"),):
    if os.path.isdir(_base) and _base not in sys.path:
        sys.path.insert(0, _base)

_SCRIPTS = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS not in sys.path:
    sys.path.insert(0, _SCRIPTS)

from remote_stage import (  # noqa: E402
    PROBE,
    RemoteError,
    SshRemote,
)
# Imported rather than duplicated: unlike the tree digest in `remote_stage`,
# this string is only ever used on THIS side of the ssh connection, so there is
# no self-contained-remote-script reason to keep a second copy of it.
from vector_tile_gen.release import RELEASE_FILENAME  # noqa: E402

# What the plan assumes. Every one of these was read off the live host before
# the plan was written; they are restated here so that a host which has drifted
# since is a refusal rather than a surprise halfway through a migration.
EXPECTED_CONTAINER = "vector-tiles"
EXPECTED_IMAGE = "vector-tiles:latest"
EXPECTED_PROJECT = "vector"
EXPECTED_SERVICE = "tiles"
EXPECTED_TILE_DIR = "/app/tiles"
EXPECTED_VOLUME = "vector-data-tiles"
EXPECTED_COMPOSE = "/home/arm/vector-prod/docker-compose.yml"
TARGET_TILE_DIR = "/app/tiles/current"

# The three states this tool can establish about the change it prepares for.
# `indeterminate` is not a softer `complete`: it is "somebody has been here and
# the evidence does not add up", which is the most dangerous of the three and
# is treated as a refusal.
MIGRATION_STATES = ("pre-migration", "complete", "indeterminate", "unknown")

# volume name -> (destination, must be read-only)
EXPECTED_MOUNTS = {
    "vector-data-tiles": ("/app/tiles", True),
    "vector-data-glyphs": ("/app/glyphs", True),
}

# The names the migration will create. Any of them already existing means
# somebody has been here before and the plan does not know what they did.
TARGET_PATHS = ("releases", "current", "previous")

# Headroom over the tree being duplicated. The migration copies the flat tree
# rather than moving it (see the runbook's Option B), so the volume must hold
# both at once.
DISK_MARGIN = 2.5

REFUSALS = (
    "insufficient_disk",
    "unexpected_mounts",
    "container_mismatch",
    "digest_drift",
    "target_paths_exist",
    "ssh_unavailable",
    "rename_probe_failed",
    "compose_not_restorable",
)

# Proves symlink creation, rename(2) over an existing symlink, and that a
# reader resolving through the pointer sees one whole side or the other. Run in
# a temporary directory on the same device as the volume, never in it.
RENAME_PROBE = r'''
import json, os, sys, tempfile

base = None
out = {}
try:
    # Inside the try: a probe directory that does not exist, or that this
    # user cannot write to, is a FINDING. Creating it above the handler
    # would make it a traceback, and a preflight that crashes tells the
    # operator less than one that says which check failed and why.
    base = tempfile.mkdtemp(prefix="vector-preflight-", dir=sys.argv[1])
    out["base"] = base
    out["device"] = os.stat(base).st_dev

    a = os.path.join(base, "releases", "A")
    b = os.path.join(base, "releases", "B")
    for d, payload in ((a, b"AAAA"), (b, b"BBBB")):
        os.makedirs(os.path.join(d, "15", "21074"))
        with open(os.path.join(d, "15", "21074", "14003.mvt"), "wb") as fh:
            fh.write(payload)

    current = os.path.join(base, "current")
    os.symlink(os.path.join("releases", "A"), current)
    out["symlink_supported"] = os.path.islink(current)
    probe = os.path.join(current, "15", "21074", "14003.mvt")
    with open(probe, "rb") as fh:
        out["before"] = fh.read().decode()

    # The activation step, exactly as the publisher performs it.
    tmp = os.path.join(base, ".current.B.tmp")
    os.symlink(os.path.join("releases", "B"), tmp)
    os.rename(tmp, current)
    out["rename_over_symlink"] = True
    with open(probe, "rb") as fh:
        out["after"] = fh.read().decode()

    # A reader must never observe the pointer missing. 200 swaps, read between
    # each; anything other than a whole side of the swap is a failure.
    seen, errors = set(), []
    for i in range(200):
        target = "B" if i % 2 == 0 else "A"
        t = os.path.join(base, ".current.%s.tmp" % target)
        if os.path.islink(t):
            os.unlink(t)
        os.symlink(os.path.join("releases", target), t)
        os.rename(t, current)
        try:
            with open(probe, "rb") as fh:
                seen.add(fh.read().decode())
        except OSError as exc:
            errors.append(str(exc))
    out["swaps"] = 200
    out["observed"] = sorted(seen)
    out["errors"] = errors[:5]
    out["atomic"] = not errors and seen <= {"AAAA", "BBBB"}
    out["ok"] = bool(out.get("symlink_supported") and out.get("rename_over_symlink")
                     and out["atomic"] and out["before"] == "AAAA"
                     and out["after"] == "BBBB")
except Exception as exc:          # noqa: BLE001 - reported, not raised
    out["ok"] = False
    out["error"] = "%s: %s" % (type(exc).__name__, exc)
finally:
    if base:
        import shutil
        shutil.rmtree(base, ignore_errors=True)
        out["cleaned_up"] = not os.path.exists(base)

print(json.dumps(out))
'''


class Preflight:
    """Inspect the host. Change nothing. Be willing to say no."""

    def __init__(self, remote, *, container=EXPECTED_CONTAINER,
                 tile_dir=EXPECTED_TILE_DIR, volume=EXPECTED_VOLUME,
                 compose=EXPECTED_COMPOSE, probe_dir="/home/arm"):
        self.remote = remote
        self.container = container
        self.tile_dir = tile_dir
        self.volume = volume
        self.compose = compose
        self.probe_dir = probe_dir
        # Filled by check_tile_dir and check_target_paths, read by
        # check_migration_state. Initialised to the pre-migration answer so
        # that a run which dies before those checks cannot be mistaken for a
        # migrated host.
        self._observed_tile_dir = tile_dir
        self._target_clashes = []
        self.report = {
            "op": "migration_preflight",
            "mode": "READ-ONLY — NO MIGRATION",
            "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "from_tile_dir": tile_dir,
            "to_tile_dir": TARGET_TILE_DIR,
            "checks": [],
            "refusals": [],
            "verdict": "NO-GO",
            "migration_state": "unknown",
        }

    # -- plumbing ---------------------------------------------------------

    def _check(self, name, ok, detail="", **observed):
        entry = {"check": name, "ok": bool(ok), "detail": detail}
        if observed:
            entry["observed"] = observed
        self.report["checks"].append(entry)
        return entry

    def _refuse(self, code, detail, *, post_migration=False):
        """Record a refusal, and whether it is a CONSEQUENCE of the migration.

        `post_migration` is not a severity and not an excuse. It marks the
        refusals that a completed migration necessarily produces, so that the
        verdict can distinguish "the host is not ready" from "the host is past
        ready" without any refusal being suppressed: every one of them is still
        in the report, still named, still printed.
        """
        if code not in REFUSALS:
            raise ValueError(f"unknown refusal code {code!r}")
        self.report["refusals"].append({"code": code, "detail": detail,
                                        "post_migration": bool(post_migration)})

    def _run(self, argv, **kw):
        return self.remote.run(argv, **kw)

    def _json(self, script, *args, timeout=900):
        rc, out, err = self._run(["python3", "-", *args], stdin=script,
                                 timeout=timeout)
        if rc != 0:
            raise RemoteError(f"probe failed (rc={rc}): {err.strip()[:200]}")
        return json.loads(out)

    # -- 1. current production state --------------------------------------

    def check_container(self):
        fmt = ("{{.Id}}\n{{.Config.Image}}\n{{.Image}}\n{{.RestartCount}}\n"
               "{{.State.StartedAt}}\n{{.State.Status}}\n"
               '{{index .Config.Labels "com.docker.compose.project"}}\n'
               '{{index .Config.Labels "com.docker.compose.service"}}\n'
               '{{index .Config.Labels "com.docker.compose.project.config_files"}}')
        rc, out, err = self._run(["docker", "inspect", self.container,
                                  "--format", fmt])
        if rc != 0:
            self._check("container", False, err.strip()[:200])
            self._refuse("ssh_unavailable",
                         f"cannot inspect {self.container}: {err.strip()[:120]}")
            return None
        fields = out.strip().split("\n")
        if len(fields) < 9:
            self._check("container", False, f"unexpected inspect output: {out[:120]!r}")
            self._refuse("container_mismatch", "inspect returned too few fields")
            return None
        (cid, image, image_id, restarts, started, status,
         project, service, config_files) = fields[:9]
        observed = {"id": cid, "image": image, "image_id": image_id,
                    "restart_count": restarts, "started_at": started,
                    "status": status, "compose_project": project,
                    "compose_service": service, "compose_config": config_files}

        problems = []
        if image != EXPECTED_IMAGE:
            problems.append(f"image {image!r} != {EXPECTED_IMAGE!r}")
        if project != EXPECTED_PROJECT:
            problems.append(f"compose project {project!r} != {EXPECTED_PROJECT!r}")
        if service != EXPECTED_SERVICE:
            problems.append(f"compose service {service!r} != {EXPECTED_SERVICE!r}")
        if status != "running":
            problems.append(f"status {status!r} != 'running'")
        self._check("container", not problems, "; ".join(problems), **observed)
        if problems:
            # The plan is written against a specific container. A different one
            # may be fine and may be a disaster, and the preflight cannot tell
            # which, so it declines to guess.
            self._refuse("container_mismatch", "; ".join(problems))
        return observed

    def check_mounts(self):
        rc, out, _ = self._run([
            "docker", "inspect", self.container, "--format",
            "{{range .Mounts}}{{.Type}}|{{.Name}}|{{.Destination}}|{{.RW}}\n{{end}}"])
        mounts = {}
        for line in out.strip().splitlines():
            parts = line.split("|")
            if len(parts) == 4:
                mounts[parts[1]] = {"type": parts[0], "destination": parts[2],
                                    "rw": parts[3] == "true"}
        problems = []
        for name, (dest, must_be_ro) in EXPECTED_MOUNTS.items():
            got = mounts.get(name)
            if not got:
                problems.append(f"missing mount {name}")
                continue
            if got["destination"] != dest:
                problems.append(f"{name} -> {got['destination']}, expected {dest}")
            if must_be_ro and got["rw"]:
                # Not pedantry: a read-only mount is what guarantees the
                # running server cannot itself alter the tree the migration is
                # about to restructure underneath it.
                problems.append(f"{name} is READ-WRITE, expected read-only")
        for name in mounts:
            if name not in EXPECTED_MOUNTS:
                problems.append(f"unexpected mount {name}")
        self._check("mounts", not problems, "; ".join(problems), mounts=mounts)
        if problems:
            self._refuse("unexpected_mounts", "; ".join(problems))
        return mounts

    def check_tile_dir(self):
        rc, out, _ = self._run([
            "docker", "inspect", self.container, "--format",
            "{{range .Config.Env}}{{println .}}{{end}}"])
        env = dict(line.split("=", 1) for line in out.strip().splitlines()
                   if "=" in line)
        current = env.get("TILE_DIR")
        ok = current == self.tile_dir
        self._check("tile_dir", ok,
                    "" if ok else f"TILE_DIR={current!r}, expected {self.tile_dir!r}",
                    tile_dir=current, glyph_dir=env.get("GLYPH_DIR"),
                    port=env.get("PORT"))
        self._observed_tile_dir = current
        if not ok:
            self._refuse("container_mismatch",
                         f"TILE_DIR is {current!r}; the migration assumes "
                         f"{self.tile_dir!r} and may already have happened",
                         post_migration=current == TARGET_TILE_DIR)
        return current

    def check_volume(self):
        rc, out, _ = self._run(["docker", "volume", "inspect", self.volume,
                                "--format", "{{.Mountpoint}}|{{.Driver}}"])
        if rc != 0 or "|" not in out:
            self._check("volume", False, f"cannot inspect volume {self.volume}")
            self._refuse("ssh_unavailable", f"volume {self.volume} not inspectable")
            return None
        mountpoint, driver = out.strip().split("|", 1)
        self._check("volume", True, "", mountpoint=mountpoint, driver=driver)
        return {"mountpoint": mountpoint, "driver": driver}

    def check_edge(self):
        rc, out, _ = self._run([
            "docker", "exec", self.container, "python3", "-c",
            "import json,time,urllib.request;"
            "r={};"
            "t=time.time();"
            "r['healthz']=urllib.request.urlopen('http://127.0.0.1:3000/healthz',"
            "timeout=10).read().decode()[:80];"
            "r['healthz_s']=round(time.time()-t,3);"
            "t=time.time();"
            "r['version']=urllib.request.urlopen('http://127.0.0.1:3000/tiles/version',"
            "timeout=30).read().decode()[:300];"
            "r['version_s']=round(time.time()-t,3);"
            "print(json.dumps(r))"], timeout=120)
        try:
            edge = json.loads(out)
        except ValueError:
            self._check("edge", False, f"no JSON from the edge: {out[:120]!r}")
            return None
        # Recorded, not judged. A server that predates Commit 4 reports no
        # `release` field and that is the correct answer today; it is also the
        # single clearest signal that the migration has not happened yet.
        has_release = '"release"' in edge.get("version", "")
        self._check("edge", True, "", release_aware=has_release, **edge)
        return edge

    def check_tree(self, label):
        script = PROBE.replace("sys.argv[1]", "os.environ['PROBE_ROOT']")
        rc, out, err = self._run([
            "docker", "exec", "-i", "-e", f"PROBE_ROOT={self.tile_dir}",
            self.container, "python3", "-"], stdin=script, timeout=1800)
        if rc != 0:
            self._check(f"tree_{label}", False, err.strip()[:200])
            self._refuse("ssh_unavailable", f"cannot census {self.tile_dir}")
            return None
        summary = json.loads(out)
        summary.pop("entries", None)
        summary.pop("sample", None)
        zooms = self._zoom_census()
        self._check(f"tree_{label}", True, "",
                    tiles_total=summary.get("tiles_total"),
                    bytes_total=summary.get("bytes_total"),
                    tree_digest=summary.get("tree_digest"),
                    zooms=zooms)
        summary["zooms"] = zooms
        return summary

    def _zoom_census(self):
        rc, out, _ = self._run([
            "docker", "exec", self.container, "sh", "-c",
            f"ls -1 {self.tile_dir}"], timeout=120)
        return sorted((e for e in out.split() if e.isdigit()), key=int)

    # -- 2. can the target be created safely ------------------------------

    def check_target_paths(self):
        rc, out, _ = self._run([
            "docker", "exec", self.container, "sh", "-c",
            f"ls -1 {self.tile_dir}"], timeout=120)
        entries = set(out.split())
        clashes = sorted(entries & set(TARGET_PATHS))
        self._check("target_paths", not clashes,
                    "already present: " + ", ".join(clashes) if clashes else "",
                    top_level=sorted(entries), reserved=list(TARGET_PATHS))
        self._target_clashes = clashes
        if clashes:
            self._refuse("target_paths_exist",
                         "the volume already contains " + ", ".join(clashes)
                         + "; someone has been here and the plan does not know what they did",
                         # A `current` beside a TILE_DIR that still reads
                         # `/app/tiles` is NOT the migration: it is half of one,
                         # or somebody else's directory. Only the pair counts.
                         post_migration=self._observed_tile_dir == TARGET_TILE_DIR)
        return clashes

    def check_migration_state(self):
        """Is the change this preflight prepares for already done?

        ASSERTED FROM THE HOST, never inferred from the refusal list. Two
        refusals that look like a completed migration are not a completed
        migration: `container_mismatch` also fires on a stopped container and
        on the wrong image, and a stray `current` left behind by a half-run
        attempt raises `target_paths_exist` over a volume that holds no release
        at all. Either would let a wrong host talk this tool into calling
        itself historical.

        So the pointer is resolved, `releases/` is listed, and the manifest's
        own `release_id` is read, and the migration is called complete only
        when all three name the same release. Anything else that has touched
        the volume is `indeterminate` — which is not a weaker `complete` but
        the worst of the three states, and is refused exactly as before.

        Read-only: three `docker exec` reads, no write, no `sudo`.
        """
        observed, clashes = self._observed_tile_dir, self._target_clashes

        if observed == self.tile_dir and not clashes:
            self.report["migration_state"] = "pre-migration"
            self._check("migration_state", True,
                        "not migrated; this preflight is live",
                        state="pre-migration")
            return "pre-migration"

        def read(cmd):
            try:
                _rc, out, _err = self._run(
                    ["docker", "exec", self.container, "sh", "-c", cmd],
                    timeout=120)
                return out
            except RemoteError:
                # A read that fails leaves the state indeterminate, which is
                # already the safe answer. It must not abort the run and turn
                # a migrated host into `ssh_unavailable`.
                return ""

        pointer = read(f"readlink {self.tile_dir}/current 2>/dev/null").strip()
        releases = sorted(
            x for x in read(f"ls -1 {self.tile_dir}/releases 2>/dev/null").split() if x)
        raw = read(f"cat {self.tile_dir}/current/{RELEASE_FILENAME} 2>/dev/null")
        try:
            declared = json.loads(raw).get("release_id")
        except (ValueError, TypeError, AttributeError):
            declared = None

        named = os.path.basename(pointer.rstrip("/")) if pointer else ""
        complete = bool(observed == TARGET_TILE_DIR and named
                        and named in releases and declared == named)
        state = "complete" if complete else "indeterminate"

        self.report["migration_state"] = state
        if complete:
            self.report["active_release"] = declared
        self._check(
            "migration_state", complete,
            "the migration is complete; this preflight is a historical document"
            if complete else
            "the volume has been changed and what is there is not a complete "
            "migration",
            state=state, tile_dir=observed, target_paths_present=list(clashes),
            current_points_to=pointer or None, releases=releases,
            release_id=declared)
        return state

    def check_disk(self, tree):
        rc, out, _ = self._run(["df", "-B1", "--output=size,used,avail,target",
                                self.probe_dir], timeout=60)
        lines = [line for line in out.strip().splitlines() if line]
        if len(lines) < 2:
            self._check("disk", False, f"unreadable df output: {out[:120]!r}")
            self._refuse("insufficient_disk", "could not read free space")
            return None
        size, used, avail, target = lines[-1].split()
        avail = int(avail)
        need = int((tree or {}).get("bytes_total", 0) * DISK_MARGIN)
        ok = avail >= need
        self._check("disk", ok,
                    "" if ok else f"{avail} bytes free, need {need}",
                    free=avail, required=need, margin=DISK_MARGIN, target=target)
        if not ok:
            self._refuse("insufficient_disk", f"{avail} free, need {need}")
        return {"free": avail, "required": need}

    def check_device(self, volume):
        """The probe is only evidence if it runs on the same filesystem.

        Atomic `rename(2)` is a property of a filesystem, so a probe elsewhere
        on the same device says something about the volume. A probe on a
        *different* device says nothing at all, and is refused rather than
        reported — a reassuring result from the wrong filesystem is worse than
        no result.
        """
        rc, out, _ = self._run([
            "sudo", "-n", "stat", "-c", "%d",
            (volume or {}).get("mountpoint", "/var/lib/docker")], timeout=60)
        volume_dev = out.strip()
        rc2, out2, _ = self._run(["stat", "-c", "%d", self.probe_dir], timeout=60)
        probe_dev = out2.strip()
        rc3, fstype, _ = self._run(["sh", "-c",
                                    f"df -T {self.probe_dir} | tail -1"], timeout=60)
        ok = bool(volume_dev) and volume_dev == probe_dev
        self._check("device", ok,
                    "" if ok else f"volume dev {volume_dev!r} != probe dev {probe_dev!r}",
                    volume_device=volume_dev, probe_device=probe_dev,
                    probe_dir=self.probe_dir, df=fstype.strip()[:160])
        if not ok:
            self._refuse("rename_probe_failed",
                         "the probe directory is not on the volume's filesystem, "
                         "so a rename probe there would prove nothing about it")
        return ok

    def check_rename(self, same_device):
        if not same_device:
            self._check("rename_probe", False, "skipped: probe is on another device")
            return None
        try:
            out = self._json(RENAME_PROBE, self.probe_dir, timeout=300)
        except (RemoteError, ValueError) as exc:
            self._check("rename_probe", False, str(exc)[:200])
            self._refuse("rename_probe_failed", str(exc)[:200])
            return None
        ok = bool(out.get("ok"))
        self._check("rename_probe", ok, out.get("error", ""),
                    symlink_supported=out.get("symlink_supported"),
                    rename_over_symlink=out.get("rename_over_symlink"),
                    swaps=out.get("swaps"), observed=out.get("observed"),
                    errors=out.get("errors"), cleaned_up=out.get("cleaned_up"))
        if not ok:
            self._refuse("rename_probe_failed",
                         out.get("error") or "the probe did not demonstrate "
                                             "an atomic swap")
        return out

    def check_ownership(self, volume):
        """Who owns the tree, and how the migration would get write access.

        The volume is mounted READ-ONLY into the running container, so the
        migration cannot write through it. Two routes exist and the runbook has
        to pick one deliberately rather than discover it mid-migration.
        """
        mountpoint = (volume or {}).get("mountpoint", "")
        rc, out, _ = self._run(["sudo", "-n", "stat", "-c", "%u %g %a",
                                mountpoint], timeout=60)
        uid = gid = mode = None
        if rc == 0 and out.strip():
            uid, gid, mode = out.strip().split()
        rc_sudo, _o, _e = self._run(["sudo", "-n", "true"], timeout=60)
        rc_docker, docker_out, _ = self._run(
            ["sh", "-c", "id -nG"], timeout=60)
        in_docker_group = "docker" in docker_out.split()
        routes = []
        if rc_sudo == 0:
            routes.append("sudo on the host path")
        if in_docker_group:
            routes.append("a throwaway container with the volume mounted rw")
        self._check("write_route", bool(routes),
                    "no way to write into the volume" if not routes else "",
                    volume_uid=uid, volume_gid=gid, volume_mode=mode,
                    passwordless_sudo=rc_sudo == 0,
                    docker_group=in_docker_group, routes=routes)
        return routes

    def check_compose(self):
        """Can the current compose be captured, and restored if it must be?

        Read-only: the file is read and hashed, its directory is checked
        writable, and the exact line that would change is located. Nothing is
        written. A migration whose compose cannot be put back is a migration
        with no rollback, which is the one property this whole plan is for.
        """
        rc, out, _ = self._run(["sh", "-c",
                                f"test -r {self.compose} && sha256sum {self.compose}"],
                               timeout=60)
        if rc != 0 or not out.strip():
            self._check("compose", False, f"cannot read {self.compose}")
            self._refuse("compose_not_restorable",
                         f"{self.compose} is not readable")
            return None
        digest = out.split()[0]

        directory = os.path.dirname(self.compose)
        rc_w, _o, _e = self._run(["test", "-w", directory], timeout=60)
        rc_line, lines, _ = self._run([
            "sh", "-c",
            f"grep -n 'TILE_DIR' {self.compose} || true"], timeout=60)
        tile_dir_lines = [line for line in lines.strip().splitlines() if line]

        problems = []
        if rc_w != 0:
            problems.append(f"{directory} is not writable; a backup cannot be taken")
        if len(tile_dir_lines) != 1:
            problems.append(f"expected exactly one TILE_DIR line, found "
                            f"{len(tile_dir_lines)}")
        elif self.tile_dir not in tile_dir_lines[0]:
            problems.append(f"the TILE_DIR line does not name {self.tile_dir}")

        self._check("compose", not problems, "; ".join(problems),
                    path=self.compose, sha256=digest,
                    tile_dir_lines=tile_dir_lines,
                    directory_writable=rc_w == 0)
        if problems:
            self._refuse("compose_not_restorable", "; ".join(problems))
        return {"sha256": digest, "tile_dir_lines": tile_dir_lines}

    def check_serving_during_preparation(self, tree):
        """What the OLD server does while the new layout is being staged.

        It keeps serving: it resolves `<TILE_DIR>/<z>/<x>/<y>.mvt`, and a new
        `releases/` sibling is not an all-digit directory so it is ignored by
        the zoom census. Two consequences are worth stating rather than
        discovering:

        * `/tiles/version` walks the WHOLE tree for `max(mtime)`, so it will
          walk roughly twice the files and take roughly twice as long while
          both trees are present.
        * that walk's answer will change, because the copied files are newer.
          Old clients will re-fetch once. Harmless, and not silent.
        """
        version_s = None
        for check in self.report["checks"]:
            if check["check"] == "edge":
                version_s = (check.get("observed") or {}).get("version_s")
        tiles = (tree or {}).get("tiles_total") or 0
        self._check("serving_during_preparation", True,
                    "the flat tree stays in place and keeps serving",
                    version_endpoint_seconds_now=version_s,
                    version_endpoint_seconds_estimated_during=(
                        round(version_s * 2, 3) if version_s else None),
                    tiles_now=tiles, tiles_while_both_present=tiles * 2,
                    epoch_will_change=True)

    # -- the run ----------------------------------------------------------

    def run(self):
        try:
            container = self.check_container()
            volume = self.check_volume()
            self.check_mounts()
            self.check_tile_dir()
            self.check_edge()

            # Twice, around everything else. If the tree changed while it was
            # being inspected then something else is writing to the volume, and
            # every number gathered here describes a tree that no longer exists.
            first = self.check_tree("before")
            self.check_target_paths()
            self.check_migration_state()
            self.check_disk(first)
            same_device = self.check_device(volume)
            self.check_rename(same_device)
            self.check_ownership(volume)
            self.check_compose()
            self.check_serving_during_preparation(first)
            second = self.check_tree("after")

            drifted = (first or {}).get("tree_digest") != (second or {}).get("tree_digest")
            self._check("digest_stable", not drifted,
                        "" if not drifted else "the active tree changed during inspection",
                        before=(first or {}).get("tree_digest"),
                        after=(second or {}).get("tree_digest"))
            if drifted:
                self._refuse("digest_drift",
                             "the active tile tree changed while it was being "
                             "inspected; something else is writing to the volume")

            self.report["container"] = container
            self.report["volume"] = volume
            self.report["tree"] = second or first
        except RemoteError as exc:
            self._check("transport", False, str(exc)[:200])
            self._refuse("ssh_unavailable", str(exc)[:200])

        # Three answers, and the order of these branches is the safety
        # property: GO still requires an EMPTY refusal list, and
        # ALREADY-MIGRATED requires both that the host proved the migration
        # complete and that every refusal present is one the completed
        # migration itself causes. Anything else — including a migrated host
        # with a real problem on it — falls through to NO-GO.
        refusals = self.report["refusals"]
        if not refusals:
            verdict = "GO"
        elif (self.report.get("migration_state") == "complete"
              and all(r.get("post_migration") for r in refusals)):
            verdict = "ALREADY-MIGRATED"
        else:
            verdict = "NO-GO"
        self.report["verdict"] = verdict
        # `ok` means "the migration this tool prepares for may proceed". A
        # completed migration is not that, so it is not ok, and no caller that
        # branches on `ok` changes behaviour because of this commit.
        self.report["ok"] = verdict == "GO"
        return self.report


def print_report(report):
    print(f"{report['op']}: {report['verdict']}   ({report['mode']})")
    print(f"  {report['from_tile_dir']}  ->  {report['to_tile_dir']}")
    for check in report["checks"]:
        mark = "ok  " if check["ok"] else "FAIL"
        print(f"  [{mark}] {check['check']}"
              + (f" — {check['detail']}" if check["detail"] else ""))
    for refusal in report["refusals"]:
        tail = "  (expected: the migration is done)" if refusal.get("post_migration") else ""
        print(f"  REFUSAL {refusal['code']}: {refusal['detail']}{tail}")
    if report["verdict"] == "GO":
        print("  no refusal condition triggered; migration may proceed per the runbook")
    elif report["verdict"] == "ALREADY-MIGRATED":
        print(f"  the migration is COMPLETE; active release "
              f"{report.get('active_release')!r}")
        print("  every refusal above is that completion reporting itself, and this "
              "runbook step is closed")
        print("  this is NOT a pass: it authorises no publish and no activation")
    else:
        print(f"  migration state: {report.get('migration_state')} — "
              "the migration must not start")


def build_parser() -> argparse.ArgumentParser:
    """The CLI surface, exposed so a test can enumerate it.

    Every flag here is an input to a read-only inspection. There is
    deliberately no way to skip a check or override a refusal, and the test
    that holds that line reads THIS rather than grepping the source — the
    docstring above discusses `--force` at length, and a source grep would
    find the discussion and call it the flag.
    """
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--alias", default="prod")
    p.add_argument("--container", default=EXPECTED_CONTAINER)
    p.add_argument("--compose", default=EXPECTED_COMPOSE)
    p.add_argument("--probe-dir", default="/home/arm")
    p.add_argument("--json", action="store_true")
    return p


def exit_code_for(report) -> int:
    """0 may migrate, 1 must not, 3 already did. (2 is "could not look", and
    is returned by :func:`main` before a report exists.)

    Separate from :func:`main` so the mapping can be asserted directly. An
    exit code is the only part of this tool a shell script can read, and
    ``ALREADY-MIGRATED`` collapsing into 0 is the one mistake here that would
    let a runbook step believe it had permission to run.
    """
    if report["ok"]:
        return 0
    return 3 if report["verdict"] == "ALREADY-MIGRATED" else 1


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)

    try:
        remote = SshRemote(args.alias)
    except Exception as exc:                      # noqa: BLE001
        print(f"cannot reach the host: {exc}", file=sys.stderr)
        return 2

    report = Preflight(remote, container=args.container, compose=args.compose,
                       probe_dir=args.probe_dir).run()
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_report(report)
    return exit_code_for(report)


if __name__ == "__main__":
    sys.exit(main())
