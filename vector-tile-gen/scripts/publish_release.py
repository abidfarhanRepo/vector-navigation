#!/usr/bin/env python3
"""The tile publisher: move a baked release into service, reversibly.

WHAT THIS IS, AND WHAT IT IS DELIBERATELY NOT
---------------------------------------------
This is the **only** thing that is allowed to change which tiles are served. It
is not a deployment script, and it is separate from ``deploy_prod_source.sh``,
``deploy_prod_arm.sh`` and ``bootstrap.sh`` on purpose: those ship *code*, and
a tile release is *data* with a completely different failure mode. Shipping the
wrong code produces an error; shipping the wrong tiles produces a map that
renders beautifully and is wrong, which nothing downstream detects. It is also
separate from the bake — a baker that could publish would be a baker that can
put an unvalidated tree in front of drivers.

V7.7 Commit 6 runs it against a **temporary local root**. No network, no SSH,
no production path. Every semantic below is the one intended for production, so
that the remote step later changes *where*, not *what*.

THE ACTIVATION MODEL
--------------------
::

    <root>/
      releases/<release-id>/          one immutable tree per release
        6/ … 15/  RELEASE.json  TILE_DIGESTS.tsv
      .staging/<release-id>/          candidates; never served, never trusted
      current   -> releases/<active>  the ONLY mutable byte-range
      previous  -> releases/<prior>   the explicit rollback target
      .current.<release-id>.tmp       transient, inert, identifiable
      publish.log                     append-only audit trail

Two renames carry the entire design:

1. **install** is ``rename(.staging/<id>, releases/<id>)``. A release directory
   therefore never exists in a partial state — it appears complete or not at
   all. This is what makes "``current`` must never point at a partial release"
   a structural property rather than a promise: there is no moment at which a
   half-copied tree is reachable under ``releases/``.
2. **activate** is ``rename(.current.<id>.tmp, current)``. `rename(2)` over an
   existing symlink is atomic, so a concurrent reader resolving ``current``
   observes the old release or the new one, never `ENOENT` and never a partial
   state. Measured: eight reader threads against 2,000 swaps, zero failures
   (V7.7 recon §4.2).

The temporary symlink is named **deterministically** — ``.current.<id>.tmp``,
never ``mkstemp`` — so an interrupted activation leaves an orphan that says
whose it is. An orphan is inert: nothing resolves through it, and the active
release is unchanged.

ORDERING, WHICH IS WHERE THE CORRECTNESS LIVES
----------------------------------------------
*Activate* writes ``previous`` **before** ``current`` moves. No filesystem
offers one atomic step for two symlinks, so one of the two orders has to be
chosen and its residue accepted. This order's residue is ``previous ==
current`` — the rollback target is *lost*, and ``recover`` reports it as
``previous_equals_current``.

The other order's residue is a ``previous`` naming the release from *two*
generations back, which is worse for a precise reason: a missing rollback
target is visibly missing, and a wrong one is an actionable instruction to roll
back to something nobody chose. Between an operator who is told there is no way
back and an operator who is confidently sent to the wrong release, the first is
recoverable and the second is an incident.

*Rollback* moves ``current`` **first**, then rewrites ``previous``, which is the
opposite order and for a reason: the goal of a rollback is to stop serving the
current release. A crash after the swap leaves ``current`` and ``previous``
both naming the good release — no rollback target, but off the bad one, which
is the direction that matters at 2am. The reverse order would leave a crashed
rollback still serving the release it was invoked to abandon.

WHAT AN INTERRUPTION CAN LEAVE, AND WHO CLEANS IT
-------------------------------------------------
Exhaustively: a partial ``.staging/<id>`` tree, an orphan ``.current.*.tmp``
symlink, or ``previous`` equal to ``current``. All three are inert, all three
are detected and repaired by ``recover``, and ``recover`` is idempotent. There
is no state requiring manual filesystem surgery, which is the claim AC-23 makes
and the reason the failure injection below exists.

SINGLE WRITER
-------------
One publisher at a time per root. There is no lock: two concurrent publishers
are an operational error, not a race to be arbitrated, and a lock would imply a
guarantee about concurrent *writers* that nothing here tests. Concurrent
**readers** are fully supported and are what the rename discipline is for.

EXIT CODES
----------
``0`` the operation succeeded
``1`` the operation failed — the release was refused, or the state was bad
``2`` the publisher could not run (bad arguments, missing root)

A refused release and an unrunnable publisher are different facts, exactly as
``validate_release.py`` distinguishes them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _base in (os.path.join(_ROOT, "src"),):
    if os.path.isdir(_base) and _base not in sys.path:
        sys.path.insert(0, _base)

_SCRIPTS = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS not in sys.path:
    sys.path.insert(0, _SCRIPTS)

from vector_tile_gen.release import (  # noqa: E402
    RELEASES_SUBDIR,
    RELEASE_FILENAME,
    is_valid_release_id,
    manifest_digest,
    read_manifest,
    scan_tree,
    verify_manifest,
)
from validate_release import validate_release  # noqa: E402

# The layout name comes from the package, so the publisher, the legacy
# importer and the migration preflight cannot disagree about where a release
# lives.
RELEASES_DIR = RELEASES_SUBDIR
STAGING_DIR = ".staging"
CURRENT = "current"
PREVIOUS = "previous"
TMP_PREFIX = ".current."
TMP_SUFFIX = ".tmp"
LOG_NAME = "publish.log"

# Headroom over the measured size of the tree being staged.
#
# Not superstition: the copy is the only step that can fill a disk, and filling
# it mid-copy is how a publisher turns a healthy release into an outage on a
# volume that also holds the release currently being served. 20% covers
# filesystem overhead and block rounding on ~18,000 small files, where the
# apparent size and the consumed size differ measurably.
FREE_SPACE_MARGIN = 1.2


class PublisherError(RuntimeError):
    """The publisher cannot run. Distinct from a release being refused."""


class InjectedFailure(RuntimeError):
    """A deliberate interruption, raised by the failure-injection harness.

    A real interruption is a `SIGKILL`, which no `except` can observe. This is
    the testable stand-in: it is raised at the same instants a kill would land,
    and the tests assert on the filesystem state it leaves behind rather than
    on the exception. Anything that only held because an exception handler ran
    would be a property of the handler, not of the design — so nothing in this
    module catches it.
    """


# The six interruption points of the agreed matrix, plus two that requirement 9
# needs in order to test "interrupted activation" and an interrupted rollback's
# far side without hand-building filesystem state. Constructing that state by
# hand would test the test's idea of the publisher rather than the publisher.
FAIL_POINTS = (
    "after_stage",            # 1. staged, nothing installed
    "during_transfer",        # 2. mid-copy into .staging
    "after_install",          # 3. installed, before manifest verification
    "after_verify",           # 4. verified, before activation
    "before_swap",            # (extra) tmp pointer created, rename not done
    "after_swap",             # 5. current moved, before the run reports
    "during_rollback",        # 6. rollback tmp created, rename not done
    "rollback_after_swap",    # (extra) rolled back, previous not yet rewritten
)


def _now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _tree_bytes(path: str) -> int:
    total = 0
    for dirpath, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(dirpath, f))
            except OSError:
                continue
    return total


def _sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Publisher:
    """Operations on one publication root.

    Every operation returns a machine-readable report and never raises for a
    *refused* release — a refusal is data, and a publisher that raises on bad
    input cannot report on it. `PublisherError` is reserved for the publisher
    itself being unable to run, and `InjectedFailure` for the test harness.
    """

    def __init__(self, root: str, *, fail_at: str | None = None,
                 tile_server: str | None = None):
        if fail_at is not None and fail_at not in FAIL_POINTS:
            raise PublisherError(
                f"unknown failure point {fail_at!r}; known: {', '.join(FAIL_POINTS)}")
        self.root = os.path.abspath(root)
        self.fail_at = fail_at
        self.tile_server = tile_server or os.path.join(
            os.path.dirname(_ROOT), "vector-web", "docker", "tileserver.py")

    # -- paths -----------------------------------------------------------

    @property
    def releases_path(self) -> str:
        return os.path.join(self.root, RELEASES_DIR)

    @property
    def staging_path(self) -> str:
        return os.path.join(self.root, STAGING_DIR)

    @property
    def current_path(self) -> str:
        return os.path.join(self.root, CURRENT)

    @property
    def previous_path(self) -> str:
        return os.path.join(self.root, PREVIOUS)

    def release_path(self, release_id: str) -> str:
        return os.path.join(self.releases_path, release_id)

    def staged_path(self, release_id: str) -> str:
        return os.path.join(self.staging_path, release_id)

    def tmp_pointer(self, release_id: str) -> str:
        return os.path.join(self.root, f"{TMP_PREFIX}{release_id}{TMP_SUFFIX}")

    def init(self) -> None:
        os.makedirs(self.releases_path, exist_ok=True)
        os.makedirs(self.staging_path, exist_ok=True)

    # -- pointers --------------------------------------------------------

    def _pointer_target(self, link: str) -> str | None:
        """The release id a pointer names, or None.

        Reads the LINK rather than resolving it, so a pointer naming a release
        that has been deleted still reports the id it names. "Points at a
        release that is gone" and "points at nothing" are different faults and
        `recover` has to tell them apart.
        """
        try:
            target = os.readlink(link)
        except OSError:
            return None
        return os.path.basename(os.path.normpath(target))

    def active_release(self) -> str | None:
        return self._pointer_target(self.current_path)

    def previous_release(self) -> str | None:
        return self._pointer_target(self.previous_path)

    def _set_pointer(self, link: str, release_id: str, *,
                     tmp: str | None = None) -> None:
        """Point `link` at `releases/<release_id>` atomically.

        The target is RELATIVE, so the whole root can be moved or bind-mounted
        without every pointer dangling — which matters because the production
        root is mounted into a container at a different path than it has on the
        host.

        `rename(2)` over an existing symlink replaces it in one step. The
        alternative, `unlink` then `symlink`, has a window in which the pointer
        does not exist at all, and a reader landing in that window gets
        `ENOENT` on a path that is supposed to be permanently valid.
        """
        tmp = tmp or f"{link}.{os.getpid()}{TMP_SUFFIX}"
        relative = os.path.join(RELEASES_DIR, release_id)
        if os.path.islink(tmp) or os.path.exists(tmp):
            os.unlink(tmp)
        os.symlink(relative, tmp)
        os.rename(tmp, link)

    # -- audit -----------------------------------------------------------

    def _audit(self, op: str, report: dict) -> None:
        """Append one line per operation. Never fails an operation.

        Append-only JSONL because the audit trail's job is to answer "what
        happened to this root, in what order" after something has gone wrong,
        and a log that can be rewritten in place cannot answer it.
        """
        entry = {
            "at": _now_iso(),
            "op": op,
            "ok": report.get("ok"),
            "release_id": report.get("release_id"),
            "active": self.active_release(),
            "previous": self.previous_release(),
        }
        for key in ("failures", "removed", "swept", "repaired", "would_remove"):
            if report.get(key):
                entry[key] = report[key]
        try:
            os.makedirs(self.root, exist_ok=True)
            with open(os.path.join(self.root, LOG_NAME), "a", encoding="utf-8") as fh:
                fh.write(json.dumps(entry, sort_keys=True) + "\n")
        except OSError:
            pass

    def _trip(self, point: str) -> None:
        if self.fail_at == point:
            raise InjectedFailure(point)

    # -- stage -----------------------------------------------------------

    def stage(self, source: str, *, require_free_bytes: int | None = None) -> dict:
        """Copy a baked tree into `.staging/<id>`. Never touches `current`.

        The release id comes from the tree's OWN manifest, never from an
        argument: a release is what it says it is, and letting the caller name
        it would allow a tree to be installed under an id it does not carry —
        the `release_id_mismatch` fault `verify_manifest` exists to catch,
        created by the publisher itself.
        """
        report = {"op": "stage", "ok": False, "release_id": None,
                  "failures": [], "swept": [], "source": os.path.abspath(source)}

        if not os.path.isdir(source):
            report["failures"].append(
                {"code": "source_missing", "detail": f"not a directory: {source}"})
            self._audit("stage", report)
            return report

        manifest = read_manifest(source)
        if manifest is None:
            report["failures"].append({
                "code": "source_not_a_release",
                "detail": f"no readable {RELEASE_FILENAME} in {source}; "
                          "bake it with a manifest before publishing"})
            self._audit("stage", report)
            return report

        release_id = str(manifest["release_id"])
        report["release_id"] = release_id
        self.init()

        # Refused BEFORE anything is copied. A duplicate id is the uniqueness
        # rule that actually holds: the timestamp is minute-resolution and two
        # bakes from one commit in one minute would collide, so uniqueness is
        # enforced by refusing to write into an existing release, never by
        # trusting the clock.
        if os.path.exists(self.release_path(release_id)):
            report["failures"].append({
                "code": "duplicate_release_id",
                "detail": f"releases/{release_id} already exists; "
                          "a release is immutable and is never overwritten"})
            self._audit("stage", report)
            return report

        need = int(_tree_bytes(source) * FREE_SPACE_MARGIN)
        report["bytes_required"] = need
        try:
            free = shutil.disk_usage(self.root).free
        except OSError as exc:
            raise PublisherError(f"cannot read free space at {self.root}: {exc}")
        report["bytes_free"] = free
        floor = require_free_bytes if require_free_bytes is not None else need
        if free < floor:
            # BEFORE destructive work, and before the copy in particular. A
            # disk filled mid-copy takes down the release currently being
            # served from the same volume — the publisher turning a healthy
            # system unhealthy while doing nothing wrong.
            report["failures"].append({
                "code": "insufficient_space",
                "detail": f"need {floor} bytes free, have {free}"})
            self._audit("stage", report)
            return report

        staged = self.staged_path(release_id)
        if os.path.exists(staged):
            # Debris from an interrupted earlier run. Removing it is safe by
            # construction: nothing is ever served from `.staging`, and the
            # release it was building is not installed or this stage would
            # have been refused as a duplicate above.
            shutil.rmtree(staged)
            report["swept"].append(f"{STAGING_DIR}/{release_id}")

        copied = self._copy_tree(source, staged)
        report["files_copied"] = copied
        report["bytes_staged"] = _tree_bytes(staged)
        report["staged_path"] = staged

        self._trip("after_stage")
        report["ok"] = True
        self._audit("stage", report)
        return report

    def _copy_tree(self, source: str, dest: str) -> int:
        """Copy file by file, so an interruption lands mid-tree.

        `shutil.copytree` would be shorter and would make the injected
        `during_transfer` failure meaningless — it would either complete or
        leave debris at a boundary the real transfer does not have. The real
        transfer is an rsync over a network that dies whenever it dies, and
        the property under test is that a half-copied `.staging` tree is
        harmless.
        """
        files = []
        for dirpath, _dirs, names in os.walk(source):
            for name in names:
                full = os.path.join(dirpath, name)
                files.append((full, os.path.relpath(full, source)))
        files.sort(key=lambda e: e[1])

        trip_at = len(files) // 2 if self.fail_at == "during_transfer" else None
        copied = 0
        for i, (full, rel) in enumerate(files):
            if trip_at is not None and i == trip_at:
                self._trip("during_transfer")
            target = os.path.join(dest, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(full, target)
            copied += 1
        return copied

    # -- validate --------------------------------------------------------

    def validate(self, release_id: str, *, staged: bool = True,
                 census: bool = True, **kw) -> dict:
        """Run the pre-publication gate over a staged or installed release.

        Delegates to `validate_release`, which is the gate this publisher is a
        caller of rather than a reimplementation of. A publisher carrying its
        own copy of the rules would eventually disagree with the gate, and the
        disagreement would be discovered by publishing something.
        """
        path = self.staged_path(release_id) if staged else self.release_path(release_id)
        report = validate_release(path, census=census, **kw)
        report["op"] = "validate"
        report["release_id"] = report.get("release_id") or release_id
        report["staged"] = staged
        self._audit("validate", report)
        return report

    # -- install ---------------------------------------------------------

    def install(self, release_id: str) -> dict:
        """`rename(.staging/<id>, releases/<id>)` — one step, or none.

        This is why `current` can never name a partial release: a release
        directory becomes visible under `releases/` in a single atomic
        operation, already complete. An interruption is either before the
        rename (nothing installed) or after it (fully installed). There is no
        during.
        """
        report = {"op": "install", "ok": False, "release_id": release_id,
                  "failures": []}
        staged = self.staged_path(release_id)
        installed = self.release_path(release_id)

        if not os.path.isdir(staged):
            report["failures"].append({
                "code": "not_staged",
                "detail": f"{STAGING_DIR}/{release_id} does not exist"})
            self._audit("install", report)
            return report

        if os.path.exists(installed):
            report["failures"].append({
                "code": "duplicate_release_id",
                "detail": f"releases/{release_id} already exists"})
            self._audit("install", report)
            return report

        # A cheap structural gate, not the census. The full judgement is
        # `validate`, and the publish pipeline runs it; this is here so that a
        # direct `install` cannot put a tree under an id it does not claim.
        manifest = read_manifest(staged)
        if manifest is None or str(manifest.get("release_id")) != release_id:
            report["failures"].append({
                "code": "manifest_mismatch",
                "detail": f"staged tree does not carry a manifest for {release_id}"})
            self._audit("install", report)
            return report

        os.makedirs(self.releases_path, exist_ok=True)
        os.rename(staged, installed)
        report["path"] = installed
        self._trip("after_install")

        report["ok"] = True
        self._audit("install", report)
        return report

    # -- activate --------------------------------------------------------

    def activate(self, release_id: str, *, census: bool = True) -> dict:
        """Make `releases/<id>` the served release, atomically.

        Re-verified here, immediately before the pointer is created, rather
        than trusting the validation that happened at staging time. Between the
        two the tree was copied and renamed, and "it was correct when we
        checked it somewhere else" is the reasoning that produced a production
        basemap with no release identity at all.
        """
        report = {"op": "activate", "ok": False, "release_id": release_id,
                  "failures": [], "previous": None, "was_active": self.active_release()}
        path = self.release_path(release_id)

        if not os.path.isdir(path):
            report["failures"].append({
                "code": "not_installed", "detail": f"releases/{release_id} missing"})
            self._audit("activate", report)
            return report

        verdict = verify_manifest(path, census=census)
        if not verdict["ok"]:
            report["failures"].extend(verdict["failures"])
            report["failures"].append({
                "code": "refused_activation",
                "detail": "the release did not verify; current is unchanged"})
            self._audit("activate", report)
            return report

        self._trip("after_verify")

        # `previous` BEFORE `current` moves. A crash between the two leaves a
        # rollback target that is correct rather than dangling, which is the
        # difference between a recoverable interruption and one that needs
        # someone to work out by hand what used to be running.
        was = report["was_active"]
        if was and was != release_id:
            self._set_pointer(self.previous_path, was)
            report["previous"] = was

        tmp = self.tmp_pointer(release_id)
        if os.path.islink(tmp) or os.path.exists(tmp):
            os.unlink(tmp)
        os.symlink(os.path.join(RELEASES_DIR, release_id), tmp)
        self._trip("before_swap")

        os.rename(tmp, self.current_path)
        self._trip("after_swap")

        report["ok"] = True
        report["active"] = self.active_release()
        self._audit("activate", report)
        return report

    # -- rollback --------------------------------------------------------

    def rollback(self) -> dict:
        """Return to the release `previous` names. One rename, reversible.

        `current` moves FIRST, then `previous` is rewritten — the opposite
        order to `activate`, deliberately. A rollback exists to stop serving
        the current release, so an interruption after the swap has achieved the
        point of the exercise and merely lacks a rollback target. The reverse
        order would leave a crashed rollback still serving exactly what it was
        invoked to abandon.
        """
        report = {"op": "rollback", "ok": False, "failures": [],
                  "from": self.active_release(), "release_id": None}

        target = self.previous_release()
        if not target:
            report["failures"].append({
                "code": "no_previous", "detail": "no rollback target recorded"})
            self._audit("rollback", report)
            return report
        if not os.path.isdir(self.release_path(target)):
            report["failures"].append({
                "code": "previous_missing",
                "detail": f"previous names {target}, which is not installed"})
            self._audit("rollback", report)
            return report

        report["release_id"] = target
        leaving = report["from"]

        tmp = self.tmp_pointer(target)
        if os.path.islink(tmp) or os.path.exists(tmp):
            os.unlink(tmp)
        os.symlink(os.path.join(RELEASES_DIR, target), tmp)
        self._trip("during_rollback")

        os.rename(tmp, self.current_path)
        self._trip("rollback_after_swap")

        # The release just left becomes the way back, so a rollback is itself
        # reversible. Nothing is deleted: both trees stay on disk and `prune`
        # is the only thing that removes data.
        if leaving and leaving != target:
            self._set_pointer(self.previous_path, leaving)

        report["ok"] = True
        report["active"] = self.active_release()
        report["previous"] = self.previous_release()
        self._audit("rollback", report)
        return report

    # -- verify ----------------------------------------------------------

    def verify(self, *, serve: bool = True, census: bool = False) -> dict:
        """Prove the active release is what it claims, beyond file existence.

        Six independent claims, because each of the failures V7.7 was created
        to stop satisfies some of the others perfectly:

        * the manifest hashes to what it says it does — tampering with the
          *claims* is as detectable as tampering with the tiles;
        * the tile count on disk is the count declared — a died-halfway
          transfer leaves perfectly valid tiles;
        * the tree digest matches — `cp -r` MERGES, so a tile from a previous
          bake can survive into a release and still be a valid tile;
        * the deterministic sample hashes to the declared bytes;
        * `current` resolves to a directory whose manifest names itself, and
          whose name is its own id — a manifest carried into the wrong
          directory is a real deploy accident;
        * and, when `serve` is set, the running tile server answers with that
          release and hands back sample bytes that hash to the manifest's
          value. That last one is the only check that tests the serving path
          rather than the filesystem, which is where AC-9 lives: a 200 at
          `?v=N` is not evidence that release N is being served.
        """
        report = {"op": "verify", "ok": False, "failures": [], "checks": {},
                  "release_id": None}

        active = self.active_release()
        if not active:
            report["failures"].append({
                "code": "no_current", "detail": "current does not name a release"})
            self._audit("verify", report)
            return report
        report["release_id"] = active

        path = self.release_path(active)
        if not os.path.isdir(path):
            report["failures"].append({
                "code": "current_dangling",
                "detail": f"current names {active}, which is not installed"})
            self._audit("verify", report)
            return report

        manifest = read_manifest(path)
        if manifest is None:
            report["failures"].append({
                "code": "manifest_unreadable",
                "detail": f"releases/{active}/{RELEASE_FILENAME}"})
            self._audit("verify", report)
            return report

        # 1. pointer identity
        declared_id = str(manifest.get("release_id"))
        ok_identity = declared_id == active == os.path.basename(path)
        report["checks"]["pointer_identity"] = ok_identity
        if not ok_identity:
            report["failures"].append({
                "code": "release_id_mismatch",
                "detail": f"current -> {active}, manifest says {declared_id}"})

        # 2. manifest hash
        declared_hash = str((manifest.get("integrity") or {}).get("manifest_sha256", ""))
        recomputed = manifest_digest(manifest)
        ok_hash = bool(declared_hash) and declared_hash == recomputed
        report["checks"]["manifest_sha256"] = ok_hash
        if not ok_hash:
            report["failures"].append({
                "code": "manifest_hash_mismatch",
                "detail": f"declared {declared_hash[:16]}…, recomputed {recomputed[:16]}…"})

        # 3 & 4. counts and tree digest, from one scan
        scan = scan_tree(path, census=census)
        verdict = verify_manifest(path, manifest, census=census, scan=scan)
        report["checks"]["tile_count"] = (
            scan["tiles_total"] == (manifest.get("coverage") or {}).get("tiles_total"))
        report["checks"]["tree_digest"] = not any(
            f["code"] == "tree_digest_mismatch" for f in verdict["failures"])
        report["observed"] = verdict.get("observed", {})
        report["failures"].extend(verdict["failures"])

        # 5. the deterministic sample, hashed off disk
        sample = (manifest.get("integrity") or {}).get("sample") or []
        bad = []
        for probe in sample:
            full = os.path.join(path, probe["path"])
            try:
                if _sha256_file(full) != probe["sha256"]:
                    bad.append(probe["path"])
            except OSError:
                bad.append(probe["path"])
        report["checks"]["sample_on_disk"] = not bad and bool(sample)
        report["sample_size"] = len(sample)
        if bad:
            report["failures"].append({
                "code": "sample_mismatch", "detail": ", ".join(bad[:5])})
        elif not sample:
            report["failures"].append({
                "code": "sample_missing",
                "detail": "the manifest declares no probe sample"})

        # 6. the serving path
        if serve:
            served = self._verify_served(path, manifest, active)
            report["served"] = served
            report["checks"]["served_release"] = served.get("ok", False)
            report["failures"].extend(served.get("failures", []))

        report["ok"] = not report["failures"]
        self._audit("verify", report)
        return report

    def _verify_served(self, path: str, manifest: dict, active: str) -> dict:
        """Ask the real tile server, over HTTP, what it is serving.

        The same `tileserver.py` that runs in production, pointed at this
        root's `current`, on an ephemeral port. Anything less — reading the
        files directly, or asserting on the publisher's own view — would prove
        the filesystem is right while saying nothing about the thing drivers
        actually talk to.
        """
        out = {"ok": False, "failures": []}
        if not os.path.isfile(self.tile_server):
            out["failures"].append({
                "code": "tile_server_missing", "detail": self.tile_server})
            return out

        import socket
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        sock.close()

        env = dict(os.environ, TILE_DIR=self.current_path, PORT=str(port))
        proc = subprocess.Popen([sys.executable, self.tile_server], env=env,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        base = f"http://127.0.0.1:{port}"
        try:
            deadline = time.time() + 10
            body = None
            while time.time() < deadline:
                try:
                    with urllib.request.urlopen(base + "/tiles/version", timeout=1) as r:
                        body = json.loads(r.read().decode())
                        out["version_headers"] = dict(r.headers)
                    break
                except Exception:
                    time.sleep(0.05)
            if body is None:
                out["failures"].append({
                    "code": "tile_server_unreachable",
                    "detail": f"no answer from {base}/tiles/version"})
                return out

            out["version"] = body
            if body.get("release") != active:
                out["failures"].append({
                    "code": "served_release_mismatch",
                    "detail": f"server reports {body.get('release')!r}, "
                              f"current is {active!r}"})

            sample = (manifest.get("integrity") or {}).get("sample") or []
            probed = []
            for probe in sample[:4]:
                url = f"{base}/tiles/{probe['path'][:-len('.mvt')]}.mvt?v={active}"
                try:
                    with urllib.request.urlopen(url, timeout=5) as r:
                        data = r.read()
                        header = r.headers.get("X-Vector-Release")
                except Exception as exc:
                    out["failures"].append({
                        "code": "sample_unreachable",
                        "detail": f"{probe['path']}: {exc}"})
                    continue
                digest = hashlib.sha256(data).hexdigest()
                if digest != probe["sha256"]:
                    # The claim AC-9 is about: not that a URL answered 200, but
                    # that the BYTES on the wire are the release's own.
                    out["failures"].append({
                        "code": "served_bytes_mismatch",
                        "detail": f"{probe['path']}: served {digest[:16]}…, "
                                  f"manifest {probe['sha256'][:16]}…"})
                if header != active:
                    out["failures"].append({
                        "code": "served_header_mismatch",
                        "detail": f"{probe['path']}: X-Vector-Release={header!r}"})
                probed.append(probe["path"])
            out["probed"] = probed
            out["ok"] = not out["failures"]
            return out
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()

    # -- list ------------------------------------------------------------

    def list(self) -> dict:
        """Every release on the root, and what each one is.

        `orphan` means the directory is under `releases/` but does not carry a
        manifest naming itself — the residue of a hand-made directory or a
        rename that went wrong. It is reported, never deleted: recovery does
        not destroy data, and `prune` is the only operation that removes
        anything.
        """
        report = {"op": "list", "ok": True, "releases": [],
                  "active": self.active_release(),
                  "previous": self.previous_release(),
                  "staging": [], "orphan_pointers": []}

        if os.path.isdir(self.releases_path):
            for name in sorted(os.listdir(self.releases_path)):
                full = os.path.join(self.releases_path, name)
                if not os.path.isdir(full):
                    continue
                manifest = read_manifest(full)
                entry = {
                    "release_id": name,
                    "active": name == report["active"],
                    "previous": name == report["previous"],
                    "bytes": _tree_bytes(full),
                    "manifest": manifest is not None,
                    "orphan": manifest is None or str(manifest.get("release_id")) != name,
                    "valid_id": is_valid_release_id(name),
                }
                if manifest:
                    entry["epoch"] = manifest.get("epoch")
                    entry["manifested_at"] = (manifest.get("bake") or {}).get("manifested_at")
                    entry["tiles_total"] = (manifest.get("coverage") or {}).get("tiles_total")
                report["releases"].append(entry)

        if os.path.isdir(self.staging_path):
            report["staging"] = sorted(os.listdir(self.staging_path))
        report["orphan_pointers"] = self._orphan_pointers()
        return report

    def _orphan_pointers(self) -> list:
        out = []
        try:
            for name in sorted(os.listdir(self.root)):
                if name.startswith(TMP_PREFIX) and name.endswith(TMP_SUFFIX):
                    out.append(name)
        except OSError:
            pass
        return out

    def _sort_key(self, entry: dict):
        """Order releases by what the manifest says, not by mtime.

        `cp -r` does not preserve mtimes — the defect that made the old tile
        version meaningless — so ordering by the filesystem would put a freshly
        copied old release ahead of a newer one. The declared epoch is a
        property of the release; the mtime is a property of the copy.
        """
        return (entry.get("epoch") or 0, entry.get("manifested_at") or "",
                entry["release_id"])

    # -- prune -----------------------------------------------------------

    def prune(self, *, keep: int = 3, dry_run: bool = True,
              allow_previous: bool = False) -> dict:
        """Remove old releases. Never the active one.

        Three protections, in order of how badly each would end:

        * `current` is never a candidate, under any flag. Deleting the release
          being served is an instant outage with no rollback target.
        * `previous` is never a candidate unless `allow_previous` is passed
          explicitly, because pruning it silently converts a reversible deploy
          into an irreversible one.
        * `dry_run` is the DEFAULT. A pruner whose default is to delete gets
          run once by someone finding out what it does.
        """
        listing = self.list()
        report = {"op": "prune", "ok": True, "keep": keep, "dry_run": dry_run,
                  "allow_previous": allow_previous, "failures": [],
                  "protected": [], "would_remove": [], "removed": [],
                  "release_id": None}

        candidates = []
        for entry in listing["releases"]:
            if entry["active"]:
                report["protected"].append(
                    {"release_id": entry["release_id"], "reason": "current"})
                continue
            if entry["previous"] and not allow_previous:
                report["protected"].append(
                    {"release_id": entry["release_id"], "reason": "previous"})
                continue
            candidates.append(entry)

        candidates.sort(key=self._sort_key, reverse=True)
        keepers = candidates[:max(0, keep)]
        doomed = candidates[max(0, keep):]
        for entry in keepers:
            report["protected"].append(
                {"release_id": entry["release_id"], "reason": "within keep window"})

        for entry in doomed:
            record = {"release_id": entry["release_id"], "bytes": entry["bytes"]}
            report["would_remove"].append(record)
            if not dry_run:
                shutil.rmtree(self.release_path(entry["release_id"]))
                report["removed"].append(record)

        report["bytes_reclaimed"] = sum(r["bytes"] for r in report["removed"])
        report["bytes_reclaimable"] = sum(r["bytes"] for r in report["would_remove"])
        self._audit("prune", report)
        return report

    # -- recover ---------------------------------------------------------

    def recover(self, *, dry_run: bool = False) -> dict:
        """Put an interrupted root back into a serving state. Idempotent.

        Every state an interruption can leave, and what is done about it:

        * an orphan `.current.*.tmp` — inert, nothing resolves through it;
          swept and reported.
        * a partial `.staging/<id>` — never served; swept and reported, unless
          that release is already installed, in which case it is left alone
          because it is not debris, it is a candidate someone may still want.
        * `current` naming a release that is not installed, or missing
          entirely — repaired by pointing it at `previous`, or at the newest
          valid installed release if there is no previous. This is the only
          case where recovery WRITES a pointer, and it is the one that would
          otherwise need someone to work out by hand what used to be running.
        * `previous` dangling or equal to `current` — reported. Not repaired:
          a missing rollback target is a fact about history, and inventing one
          would mean nominating a release nobody chose.
        * an orphaned release directory — reported, never deleted. Recovery
          does not destroy data.

        Running it twice changes nothing the second time, which is the property
        that lets it be the first thing anyone runs on a root they do not trust.
        """
        report = {"op": "recover", "ok": True, "dry_run": dry_run,
                  "swept": [], "repaired": [], "findings": [], "failures": [],
                  "release_id": None}
        self.init()

        for name in self._orphan_pointers():
            report["swept"].append(name)
            if not dry_run:
                try:
                    os.unlink(os.path.join(self.root, name))
                except OSError:
                    pass

        if os.path.isdir(self.staging_path):
            for name in sorted(os.listdir(self.staging_path)):
                if os.path.isdir(self.release_path(name)):
                    report["findings"].append({
                        "code": "staged_and_installed",
                        "detail": f"{STAGING_DIR}/{name} left alone: "
                                  f"releases/{name} exists"})
                    continue
                report["swept"].append(f"{STAGING_DIR}/{name}")
                if not dry_run:
                    shutil.rmtree(os.path.join(self.staging_path, name),
                                  ignore_errors=True)

        active = self.active_release()
        active_ok = bool(active) and os.path.isdir(self.release_path(active))
        if not active_ok:
            fallback = self.previous_release()
            if not (fallback and os.path.isdir(self.release_path(fallback))):
                installed = [e for e in self.list()["releases"]
                             if not e["orphan"] and e["manifest"]]
                installed.sort(key=self._sort_key, reverse=True)
                fallback = installed[0]["release_id"] if installed else None
            if fallback:
                report["repaired"].append({
                    "code": "current_restored",
                    "detail": f"current was {active or 'absent'}; "
                              f"pointed at {fallback}"})
                if not dry_run:
                    self._set_pointer(self.current_path, fallback)
            else:
                report["failures"].append({
                    "code": "nothing_to_serve",
                    "detail": "current is unusable and no valid release is installed"})
                report["ok"] = False

        prev = self.previous_release()
        if prev and not os.path.isdir(self.release_path(prev)):
            report["findings"].append({
                "code": "previous_dangling",
                "detail": f"previous names {prev}, which is not installed"})
        elif prev and prev == self.active_release():
            report["findings"].append({
                "code": "previous_equals_current",
                "detail": f"no rollback target: both name {prev}"})

        for entry in self.list()["releases"]:
            if entry["orphan"]:
                report["findings"].append({
                    "code": "orphan_release",
                    "detail": f"releases/{entry['release_id']} carries no "
                              "manifest naming itself; reported, not removed"})

        self._audit("recover", report)
        return report

    # -- the pipeline ----------------------------------------------------

    def publish(self, source: str, *, census: bool = True,
                verify_served: bool = True, **stage_kw) -> dict:
        """stage → validate → install → activate → verify.

        Stops at the first failure and leaves `current` exactly where it was.
        The steps are separately callable because an operator mid-incident
        needs to run one of them, not a pipeline.
        """
        report = {"op": "publish", "ok": False, "release_id": None, "steps": [],
                  "failures": [], "was_active": self.active_release()}

        staged = self.stage(source, **stage_kw)
        report["steps"].append(staged)
        report["release_id"] = staged.get("release_id")
        if not staged["ok"]:
            report["failures"] = staged["failures"]
            return report

        release_id = staged["release_id"]
        validated = self.validate(release_id, staged=True, census=census)
        report["steps"].append(validated)
        if not validated["ok"]:
            report["failures"] = validated["failures"]
            return report

        installed = self.install(release_id)
        report["steps"].append(installed)
        if not installed["ok"]:
            report["failures"] = installed["failures"]
            return report

        activated = self.activate(release_id, census=census)
        report["steps"].append(activated)
        if not activated["ok"]:
            report["failures"] = activated["failures"]
            return report

        verified = self.verify(serve=verify_served, census=False)
        report["steps"].append(verified)
        if not verified["ok"]:
            report["failures"] = verified["failures"]
            return report

        report["ok"] = True
        report["active"] = self.active_release()
        return report


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _print(report: dict, as_json: bool) -> None:
    if as_json:
        print(json.dumps(report, indent=2, sort_keys=True))
        return
    op = report.get("op", "?")
    status = "OK" if report.get("ok") else "FAILED"
    print(f"{op}: {status}" + (f"  {report['release_id']}"
                               if report.get("release_id") else ""))
    for key in ("swept", "repaired", "findings", "protected",
                "would_remove", "removed", "failures"):
        for item in report.get(key) or []:
            if isinstance(item, dict):
                text = item.get("detail") or item.get("release_id") or json.dumps(item)
                code = item.get("code") or item.get("reason") or ""
                print(f"  {key}: {code} {text}".rstrip())
            else:
                print(f"  {key}: {item}")
    if op == "list":
        for entry in report.get("releases", []):
            marks = "".join([
                "*" if entry["active"] else " ",
                "<" if entry["previous"] else " ",
                "!" if entry["orphan"] else " ",
            ])
            print(f"  {marks} {entry['release_id']}  "
                  f"{entry.get('tiles_total', '?')} tiles  {entry['bytes']} bytes")
        print("  legend: * current, < previous, ! orphan")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--root", required=True, help="publication root")
    p.add_argument("--json", action="store_true", help="machine-readable report")
    p.add_argument("--fail-at", choices=FAIL_POINTS,
                   help="inject an interruption (testing only)")
    p.add_argument("--no-census", action="store_true",
                   help="skip tile decoding (faster, proves less)")
    sub = p.add_subparsers(dest="cmd", required=True)

    for name in ("stage", "publish"):
        s = sub.add_parser(name)
        s.add_argument("source", help="a baked tile tree carrying RELEASE.json")
        s.add_argument("--require-free-bytes", type=int)
        if name == "publish":
            s.add_argument("--no-serve-check", action="store_true")

    for name in ("validate", "install", "activate"):
        s = sub.add_parser(name)
        s.add_argument("release_id")
        if name == "validate":
            s.add_argument("--installed", action="store_true",
                           help="judge releases/<id> rather than the staged copy")

    sub.add_parser("verify").add_argument("--no-serve-check", action="store_true")
    sub.add_parser("rollback")
    sub.add_parser("list")
    prune = sub.add_parser("prune")
    prune.add_argument("--keep", type=int, default=3)
    prune.add_argument("--apply", action="store_true",
                       help="actually delete (default is a dry run)")
    prune.add_argument("--allow-previous", action="store_true")
    sub.add_parser("recover").add_argument("--dry-run", action="store_true")

    args = p.parse_args(argv)
    census = not args.no_census

    try:
        pub = Publisher(args.root, fail_at=args.fail_at)
        if args.cmd == "stage":
            report = pub.stage(args.source, require_free_bytes=args.require_free_bytes)
        elif args.cmd == "publish":
            report = pub.publish(args.source, census=census,
                                 verify_served=not args.no_serve_check,
                                 require_free_bytes=args.require_free_bytes)
        elif args.cmd == "validate":
            report = pub.validate(args.release_id, staged=not args.installed,
                                  census=census)
        elif args.cmd == "install":
            report = pub.install(args.release_id)
        elif args.cmd == "activate":
            report = pub.activate(args.release_id, census=census)
        elif args.cmd == "verify":
            report = pub.verify(serve=not args.no_serve_check, census=census)
        elif args.cmd == "rollback":
            report = pub.rollback()
        elif args.cmd == "list":
            report = pub.list()
        elif args.cmd == "prune":
            report = pub.prune(keep=args.keep, dry_run=not args.apply,
                               allow_previous=args.allow_previous)
        elif args.cmd == "recover":
            report = pub.recover(dry_run=args.dry_run)
        else:  # pragma: no cover - argparse enforces this
            raise PublisherError(f"unknown command {args.cmd!r}")
    except PublisherError as exc:
        print(f"publisher cannot run: {exc}", file=sys.stderr)
        return 2
    except InjectedFailure as exc:
        # Printed, not swallowed: an injected failure is a simulated crash, and
        # the state it leaves behind is the thing under test.
        print(f"INJECTED FAILURE at {exc}", file=sys.stderr)
        return 1

    _print(report, args.json)
    return 0 if report.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
