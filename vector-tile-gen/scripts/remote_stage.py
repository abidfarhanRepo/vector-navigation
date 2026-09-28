#!/usr/bin/env python3
"""Remote staging dry run: get a real release onto the host, activate nothing.

WHAT THIS IS FOR
----------------
Commit 6 proved the activation model against a temporary local root. Everything
it proved is still true and still local. This closes the one gap that cannot be
closed locally at all: whether a **real** release — 18,273 files, ~100 MB of
small writes — survives a real transfer to the real host with every byte
intact, and whether the gate that judged it here reaches the same verdict
there.

It is a **dry run**. It transfers, it verifies, it compares, and it stops. It
does not activate, and it is not capable of activating: there is no pointer
code in this file and the staging path is not on the volume the tile server
reads. Remote staging is preparation for activation testing, never evidence of
it.

WHY THE TRANSPORT IS ITS OWN MODULE
-----------------------------------
``publish_release.py`` operates on a root through ordinary filesystem calls and
is exhaustively tested against a temporary directory. Giving it an SSH mode
would mean every one of those 66 tests was exercising a code path that now had
a remote branch in it. The transport is therefore separate, and it is separate
from ``deploy_prod_source.sh`` and ``bootstrap.sh`` for the reason the
publisher already is: those ship code, this ships data, and the failure modes
have nothing in common.

WHAT MAKES THIS SAFE, CONCRETELY
--------------------------------
Not care, and not the reviewer's attention. Four structural properties, each
verified by reconnaissance before a line of this ran:

1. **The active tiles are a Docker named volume**, at
   ``/var/lib/docker/volumes/vector-data-tiles/_data``, mounted into
   ``vector-tiles`` at ``/app/tiles`` **read-only** (``rw=false``). The staging
   base is under ``/home/arm``, which is a different filesystem path entirely,
   and no container bind-mounts anything under it.
2. **The staging base is refused unless it passes** :func:`assert_safe_base` —
   absolute, under an allowed prefix, and provably outside the tile volume,
   the container's ``TILE_DIR`` and every other path this must never reach.
3. **Every destructive remote path is built from a validated run id.** The id
   must match :data:`RUN_ID_RE` before it is interpolated into any remote
   command, so a malformed or injected id cannot widen the blast radius of an
   ``rm -rf``. Cleanup removes exactly ``<base>/<run_id>`` and refuses
   anything else, including ``<base>`` itself.
4. **Nothing here writes outside that directory.** The operational checks are
   reads; the comparison against the live tree is a read; the transfer target
   is the run directory. There is no code path that writes to the volume,
   creates ``releases/``, ``current`` or ``previous``, or touches compose.

WHY EXIT CODES ARE NOT EVIDENCE
-------------------------------
``rsync`` exiting 0 means rsync believed it finished. It does not mean the
bytes on the far side are the bytes that left, and a transfer that silently
drops files is precisely the failure V7.7 exists to catch — 3,000 of 18,311
tiles arriving is 3,000 *valid* tiles. So the far side is measured
independently: file count, byte count, a Merkle tree digest over every tile,
the manifest's own hash, and the deterministic probe sample. A mismatch then
escalates to a full per-file listing so the answer is "these four files" rather
than "the hashes differ".

The digest algorithm here is a deliberate duplicate of
``vector_tile_gen.release.tree_digest`` — the remote has no package installed,
and the probe must be a single self-contained script. ``test_remote_stage.py``
pins the two to the same value on the same tree, exactly as the tile server's
glyph copy is pinned.

CONCURRENCY, STATED HONESTLY
----------------------------
One publisher per root remains the supported model and this commit does not
change it. What it does add is the cheapest correct guard available: the run
directory is created with ``mkdir`` (no ``-p`` on the final component), which
fails if it exists, so two runs cannot share a staging directory. A second run
whose base already holds an unfinished run is refused unless
``--allow-concurrent`` is passed.

That is a guard, not a lock. The intended mechanism, when it is needed, is an
``flock(2)`` on ``<root>/.publisher.lock`` held for the duration of an
activation — advisory, released by the kernel if the holder dies, and
observable with ``fuser``. It is deliberately not in this commit: a lock that
only ever protected a dry run would be untested where it matters.

EXIT CODES
----------
``0`` the dry run succeeded
``1`` the dry run failed — transfer, verification or validation
``2`` could not run (bad arguments, unsafe path, no transport)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _base in (os.path.join(_ROOT, "src"),):
    if os.path.isdir(_base) and _base not in sys.path:
        sys.path.insert(0, _base)

_SCRIPTS = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS not in sys.path:
    sys.path.insert(0, _SCRIPTS)

from vector_tile_gen.release import (  # noqa: E402
    RELEASE_FILENAME,
    is_valid_release_id,
    read_manifest,
    scan_tree,
    tree_digest,
)
from validate_release import validate_release  # noqa: E402

DEFAULT_BASE = "/home/arm/vector-tile-staging"
DEFAULT_ALIAS = "prod"

# run-20260919T213000Z-1a2b3c4d
RUN_ID_RE = re.compile(r"^run-\d{8}T\d{6}Z-[0-9a-f]{8}$")

# A staging base must live under one of these. Not a blocklist: a blocklist is
# a list of the mistakes someone already thought of, and the one that matters
# is always the one nobody did.
ALLOWED_BASE_PREFIXES = ("/home/", "/srv/staging/", "/tmp/")

# Paths this must never write to, checked anyway. Redundant with the allowlist
# above and kept because the cost of the redundancy is nothing and the cost of
# being wrong once is the production basemap.
FORBIDDEN_PREFIXES = (
    "/var/lib/docker",   # where the tile volume actually lives
    "/app",              # TILE_DIR inside the container
    "/etc",
    "/usr",
    "/boot",
)

GATE_FILES = (
    ("src/vector_tile_gen/__init__.py", "src/vector_tile_gen/__init__.py"),
    ("src/vector_tile_gen/health.py", "src/vector_tile_gen/health.py"),
    # The layer rule the gate applies (V8): release.py and validate_release.py
    # import it, so a far side without it cannot run the gate at all.
    ("src/vector_tile_gen/layers.py", "src/vector_tile_gen/layers.py"),
    ("src/vector_tile_gen/release.py", "src/vector_tile_gen/release.py"),
    ("scripts/validate_release.py", "scripts/validate_release.py"),
)


class RemoteError(RuntimeError):
    """The transport failed. Distinct from the release being refused."""


class UnsafePath(RuntimeError):
    """A path that this module refuses to touch."""


def assert_safe_base(base: str) -> str:
    """Refuse a staging base that could reach anything that matters.

    Called before every remote operation rather than once at startup, because
    the value can arrive from a flag, a config or a future caller, and a check
    that runs in only one of those places is a check that will be bypassed by
    the path nobody remembered.
    """
    if not base or not base.startswith("/"):
        raise UnsafePath(f"staging base must be an absolute path: {base!r}")
    normalised = os.path.normpath(base)
    if normalised in ("/", "/home", "/tmp", "/srv"):
        raise UnsafePath(f"refusing a staging base of {normalised!r}")
    if ".." in normalised.split("/"):
        raise UnsafePath(f"staging base must not traverse: {base!r}")
    for bad in FORBIDDEN_PREFIXES:
        if normalised == bad or normalised.startswith(bad + "/"):
            raise UnsafePath(
                f"{normalised!r} is inside {bad!r}, which holds the live tile "
                "volume or the system; staging must be outside it")
    if not any(normalised.startswith(p) for p in ALLOWED_BASE_PREFIXES):
        raise UnsafePath(
            f"{normalised!r} is not under an allowed prefix "
            f"({', '.join(ALLOWED_BASE_PREFIXES)})")
    return normalised


def assert_safe_run_id(run_id: str) -> str:
    """Refuse a run id before it is ever interpolated into a remote command.

    This is the check that bounds an ``rm -rf``. A run id is generated here and
    never comes from a release, a manifest or a filename, so the regex is not
    defending against an adversary so much as against a future caller passing
    something reasonable-looking — an empty string, a path, a glob — into a
    command that deletes a directory tree.
    """
    if not run_id or not RUN_ID_RE.match(run_id):
        raise UnsafePath(f"not a valid run id: {run_id!r}")
    return run_id


def new_run_id() -> str:
    return "run-%s-%s" % (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()),
                          secrets.token_hex(4))


# ---------------------------------------------------------------------------
# The far-side probe
# ---------------------------------------------------------------------------
#
# Self-contained: the remote has python3 and nothing of ours installed, so this
# goes over stdin and carries its own copy of `iter_tile_paths` and
# `tree_digest`. `test_remote_stage.py` pins it to the real implementations on
# the same tree — a copy that drifts would report a mismatch for an intact
# transfer, or worse, agree with itself about a broken one.

PROBE = r'''
import hashlib, json, os, sys

root = sys.argv[1]
detail = "--detail" in sys.argv[2:]


def iter_tile_paths(tiles_dir):
    try:
        zooms = sorted((e for e in os.listdir(tiles_dir)
                        if e.isdigit() and os.path.isdir(os.path.join(tiles_dir, e))),
                       key=int)
    except OSError:
        return
    for z in zooms:
        zp = os.path.join(tiles_dir, z)
        try:
            xs = sorted((e for e in os.listdir(zp) if e.isdigit()), key=int)
        except OSError:
            continue
        for x in xs:
            xp = os.path.join(zp, x)
            if not os.path.isdir(xp):
                continue
            try:
                ys = sorted(e for e in os.listdir(xp) if e.endswith(".mvt"))
            except OSError:
                continue
            for fn in ys:
                yield "%s/%s/%s" % (z, x, fn)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


out = {"root": root, "exists": os.path.isdir(root)}
if not out["exists"]:
    print(json.dumps(out))
    raise SystemExit(0)

entries = []
for rel in iter_tile_paths(root):
    full = os.path.join(root, rel)
    try:
        entries.append((rel, sha256_file(full), os.path.getsize(full)))
    except OSError as exc:
        out.setdefault("unreadable", []).append("%s: %s" % (rel, exc))

h = hashlib.sha256()
for rel, digest, nbytes in sorted(entries, key=lambda e: e[0]):
    h.update(("%s\t%s\t%d\n" % (rel, digest, nbytes)).encode("utf-8"))

out["tiles_total"] = len(entries)
out["bytes_total"] = sum(e[2] for e in entries)
out["tree_digest"] = h.hexdigest()

manifest_path = os.path.join(root, "RELEASE.json")
if os.path.isfile(manifest_path):
    out["manifest_sha256"] = sha256_file(manifest_path)
    out["manifest_bytes"] = os.path.getsize(manifest_path)
    try:
        with open(manifest_path, encoding="utf-8") as fh:
            doc = json.load(fh)
        out["release_id"] = doc.get("release_id")
        sample = (doc.get("integrity") or {}).get("sample") or []
        probed = {}
        for probe in sample:
            p = os.path.join(root, probe["path"])
            probed[probe["path"]] = sha256_file(p) if os.path.isfile(p) else None
        out["sample"] = probed
    except (OSError, ValueError) as exc:
        out["manifest_error"] = str(exc)
else:
    out["manifest_sha256"] = None

# Non-tile files that are not the two the manifest excludes. An extra file is
# as much a transfer fault as a missing one -- `cp -r` MERGES, and a stray file
# is how a previous bake survives into a new release.
extras = []
for dirpath, _dirs, files in os.walk(root):
    for f in files:
        full = os.path.join(dirpath, f)
        rel = os.path.relpath(full, root).replace(os.sep, "/")
        if rel in ("RELEASE.json", "TILE_DIGESTS.tsv"):
            continue
        if not rel.endswith(".mvt"):
            extras.append(rel)
out["non_tile_files"] = sorted(extras)[:50]
out["non_tile_count"] = len(extras)

if detail:
    out["entries"] = {rel: [d, n] for rel, d, n in entries}

print(json.dumps(out))
'''

DF_PROBE = r'''
import json, os, shutil, sys
path = sys.argv[1]
probe = path
while probe and not os.path.isdir(probe):
    parent = os.path.dirname(probe)
    if parent == probe:
        break
    probe = parent
u = shutil.disk_usage(probe or "/")
print(json.dumps({"checked": probe, "total": u.total, "used": u.used,
                  "free": u.free}))
'''


# ---------------------------------------------------------------------------
# Transports
# ---------------------------------------------------------------------------

class Remote:
    """What the stager needs from a far side. Two methods, on purpose.

    Keeping the surface this small is what lets the tests drive the real probe
    scripts against a real directory tree through :class:`LocalRemote`, instead
    of mocking out the thing most likely to be wrong.
    """

    name = "remote"

    def run(self, argv, *, stdin: str | None = None,
            timeout: int = 300) -> tuple[int, str, str]:
        raise NotImplementedError

    def push(self, local_dir: str, remote_dir: str,
             *, timeout: int = 3600) -> tuple[int, str, str]:
        raise NotImplementedError


class SshRemote(Remote):
    """SSH and rsync through a configured alias. No credentials, ever.

    The alias is the only host identity this module knows. There is no
    hostname, no IP and no user in this file: they live in ``~/.ssh/config``
    where they can be rotated without a commit, and ``BatchMode=yes`` means a
    missing or wrong key fails immediately instead of blocking on a password
    prompt — which is also what stops this hanging inside an automated run.
    """

    def __init__(self, alias: str = DEFAULT_ALIAS):
        if not alias or not re.match(r"^[A-Za-z0-9][A-Za-z0-9._-]*$", alias):
            raise UnsafePath(f"not a plausible ssh alias: {alias!r}")
        self.alias = alias
        self.name = alias

    def _ssh_base(self):
        return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
                self.alias]

    def run(self, argv, *, stdin=None, timeout=300):
        # Quoted, not appended. `ssh host a b c` joins the arguments with
        # spaces and hands the result to the REMOTE SHELL, so anything holding
        # a metacharacter is reinterpreted there. Measured: a
        # `--format {{.Id}}|{{.RestartCount}}` arrived as a pipeline and the
        # container inspection silently returned nothing — an operational
        # check that reported success by reporting almost nothing.
        #
        # `LocalRemote` executes the argv directly with no shell, so quoting
        # here is also what keeps the two transports meaning the same thing;
        # without it, every test would be exercising semantics the real
        # transport does not have.
        cmd = self._ssh_base() + [" ".join(shlex.quote(a) for a in argv)]
        try:
            proc = subprocess.run(cmd, input=stdin, capture_output=True,
                                  text=True, timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            raise RemoteError(f"timed out after {timeout}s: {' '.join(argv)[:120]}") from exc
        except OSError as exc:
            raise RemoteError(f"could not run ssh: {exc}") from exc
        return proc.returncode, proc.stdout, proc.stderr

    def push(self, local_dir, remote_dir, *, timeout=3600):
        # `-a` preserves times and permissions; there is deliberately no
        # `--delete`. Deleting on the far side is not this tool's job, the
        # destination is a fresh run directory, and a `--delete` aimed at the
        # wrong path is the single most expensive typo available here.
        cmd = ["rsync", "-a", "--no-owner", "--no-group",
               "-e", "ssh -o BatchMode=yes -o ConnectTimeout=20",
               local_dir.rstrip("/") + "/",
               f"{self.alias}:{remote_dir.rstrip('/')}/"]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True,
                                  timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            raise RemoteError(f"rsync timed out after {timeout}s") from exc
        except OSError as exc:
            raise RemoteError(f"could not run rsync: {exc}") from exc
        return proc.returncode, proc.stdout, proc.stderr


class LocalRemote(Remote):
    """A far side on this machine, for tests.

    Runs the same probe scripts, through the same interface, against a real
    directory tree. A `unittest.mock` would have proved that the stager calls
    the functions it calls; this proves the probes compute what the assertions
    say they compute, which is where a transport bug would actually live.
    """

    name = "local"

    def __init__(self, sandbox: str):
        self.sandbox = os.path.abspath(sandbox)
        os.makedirs(self.sandbox, exist_ok=True)

    def run(self, argv, *, stdin=None, timeout=300):
        try:
            proc = subprocess.run(list(argv), input=stdin, capture_output=True,
                                  text=True, timeout=timeout, cwd=self.sandbox)
        except OSError as exc:
            raise RemoteError(str(exc)) from exc
        return proc.returncode, proc.stdout, proc.stderr

    def push(self, local_dir, remote_dir, *, timeout=3600):
        os.makedirs(remote_dir, exist_ok=True)
        for dirpath, _dirs, files in os.walk(local_dir):
            for f in files:
                src = os.path.join(dirpath, f)
                rel = os.path.relpath(src, local_dir)
                dst = os.path.join(remote_dir, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
        return 0, "", ""


# ---------------------------------------------------------------------------
# The dry run
# ---------------------------------------------------------------------------

class RemoteStager:

    def __init__(self, remote: Remote, *, base: str = DEFAULT_BASE,
                 run_id: str | None = None, container: str = "vector-tiles",
                 tile_dir: str = "/app/tiles"):
        self.remote = remote
        self.base = assert_safe_base(base)
        self.run_id = assert_safe_run_id(run_id or new_run_id())
        self.container = container
        self.tile_dir = tile_dir

    @property
    def run_dir(self) -> str:
        """The ONE directory this run may write to."""
        return f"{self.base}/{assert_safe_run_id(self.run_id)}"

    @property
    def payload_dir(self) -> str:
        return f"{self.run_dir}/release"

    @property
    def gate_dir(self) -> str:
        return f"{self.run_dir}/gate"

    # -- remote helpers ---------------------------------------------------

    def _python(self, script: str, *args, timeout: int = 900) -> dict:
        rc, out, err = self.remote.run(["python3", "-", *args], stdin=script,
                                       timeout=timeout)
        if rc != 0:
            raise RemoteError(f"remote probe failed (rc={rc}): {err.strip()[:300]}")
        try:
            return json.loads(out)
        except ValueError as exc:
            raise RemoteError(f"remote probe returned non-JSON: {out[:200]!r}") from exc

    # -- 6. operational checks (read-only) --------------------------------

    def operational_checks(self) -> dict:
        """Everything about the host that must be the same afterwards.

        Captured before and after so "production was unchanged" is a diff of
        two observations rather than an assurance. Every command here reads:
        `docker inspect`, `docker ps`, `df`, and an HTTP GET inside the
        container. None of them can restart anything.
        """
        checks: dict = {"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}

        rc, out, err = self.remote.run([
            "docker", "inspect", self.container,
            "--format", "{{.Id}}|{{.RestartCount}}|{{.State.StartedAt}}|{{.State.Status}}"])
        if rc == 0 and "|" in out:
            cid, restarts, started, status = out.strip().split("|", 3)
            checks["container"] = {"id": cid, "restart_count": restarts,
                                   "started_at": started, "status": status}
        else:
            checks["container_error"] = err.strip()[:200]

        rc, out, _ = self.remote.run(
            ["docker", "ps", "--format", "{{.Names}}"])
        if rc == 0:
            names = [n for n in out.split() if n]
            checks["containers_running"] = len(names)
            checks["foreign_containers"] = sum(1 for n in names if "vector" not in n.lower())
            checks["vector_containers"] = sum(1 for n in names if "vector" in n.lower())

        rc, out, _ = self.remote.run([
            "docker", "inspect", self.container,
            "--format", "{{range .Mounts}}{{.Type}}:{{.Name}}->{{.Destination}}:rw={{.RW}} {{end}}"])
        if rc == 0:
            checks["mounts"] = out.strip()

        try:
            checks["disk"] = self._python(DF_PROBE, self.base, timeout=120)
        except RemoteError as exc:
            checks["disk_error"] = str(exc)

        rc, out, _ = self.remote.run([
            "docker", "exec", self.container, "python3", "-c",
            "import urllib.request,json;"
            "print(json.dumps({p: urllib.request.urlopen('http://127.0.0.1:3000'+p,"
            "timeout=10).read().decode()[:300] for p in ('/healthz','/tiles/version')}))"],
            timeout=120)
        if rc == 0:
            try:
                checks["edge"] = json.loads(out)
            except ValueError:
                checks["edge_raw"] = out.strip()[:300]
        return checks

    def active_tree_summary(self) -> dict:
        """A read-only census of the tiles production is serving right now.

        Report-only, and explicitly NOT a comparison the dry run can fail on.
        The staged release and the live tree are allowed to differ — that is
        the point of staging a new one. What this answers is "what would
        change, if this were ever activated", which is a question an operator
        asks before authorising the next commit, not a gate.
        """
        script = PROBE.replace("sys.argv[1]", "os.environ['PROBE_ROOT']")
        # `-i` is load-bearing: without it `docker exec` closes stdin and the
        # probe below is read as an empty program that prints nothing.
        rc, out, err = self.remote.run([
            "docker", "exec", "-i", "-e", f"PROBE_ROOT={self.tile_dir}",
            self.container, "python3", "-"], stdin=script, timeout=900)
        if rc != 0:
            return {"error": err.strip()[:300]}
        try:
            summary = json.loads(out)
        except ValueError:
            return {"error": f"non-JSON census: {out[:200]!r}"}
        summary.pop("entries", None)
        return summary

    # -- the flow ---------------------------------------------------------

    def dry_run(self, source: str, *, census: bool = False,
                allow_concurrent: bool = False,
                free_margin: float = 1.5) -> dict:
        """Transfer, verify, compare. Never activate.

        Every transport failure becomes a REPORTED failure rather than a raised
        one. A dry run whose whole purpose is to produce evidence must not
        answer a dropped SSH connection by producing none — the operator is
        left with a traceback and no record of how far it got, which is the
        state this commit exists to make impossible for a transfer.
        """
        report = self._new_report(source)
        try:
            return self._dry_run(report, source, census=census,
                                 allow_concurrent=allow_concurrent,
                                 free_margin=free_margin)
        except RemoteError as exc:
            report["failures"].append({"code": "ssh_failed", "detail": str(exc)})
            report["ok"] = False
            return report

    def _new_report(self, source: str) -> dict:
        return {
            "op": "remote_dry_run",
            "ok": False,
            "mode": "DRY RUN — NO ACTIVATION",
            "transport": self.remote.name,
            "run_id": self.run_id,
            "run_dir": self.run_dir,
            "payload_dir": self.payload_dir,
            "source": os.path.abspath(source),
            "release_id": None,
            "failures": [],
            "steps": [],
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

    def _dry_run(self, report: dict, source: str, *, census: bool,
                 allow_concurrent: bool, free_margin: float) -> dict:

        def fail(code, detail):
            report["failures"].append({"code": code, "detail": detail})
            return report

        def step(name, **kw):
            entry = {"step": name, **kw}
            report["steps"].append(entry)
            return entry

        # -- 2a. local source inspection ---------------------------------
        if not os.path.isdir(source):
            return fail("source_missing", f"not a directory: {source}")
        manifest = read_manifest(source)
        if manifest is None:
            return fail("source_not_a_release",
                        f"no readable {RELEASE_FILENAME} in {source}")
        release_id = str(manifest["release_id"])
        if not is_valid_release_id(release_id):
            return fail("release_id_invalid", release_id)
        report["release_id"] = release_id

        local_scan = scan_tree(source, census=False)
        local = {
            "tiles_total": local_scan["tiles_total"],
            "bytes_total": local_scan["bytes_total"],
            "tree_digest": tree_digest(local_scan["entries"]),
            "manifest_sha256": _sha256_file(os.path.join(source, RELEASE_FILENAME)),
        }
        report["local"] = local
        step("local_inspection", **local)

        # -- 2b. local manifest validation --------------------------------
        gate_local = validate_release(source, census=census)
        step("local_validation", ok=gate_local["ok"],
             failures=gate_local["failures"], census=census)
        if not gate_local["ok"]:
            report["failures"].extend(gate_local["failures"])
            return fail("local_validation_failed",
                        "the release does not pass the gate here; "
                        "nothing was transferred")

        # -- concurrency guard --------------------------------------------
        rc, out, _ = self.remote.run(["ls", "-1", self.base])
        siblings = [d for d in out.split() if d and RUN_ID_RE.match(d)]
        if siblings and not allow_concurrent:
            unfinished = []
            for sib in siblings:
                rc2, _o, _e = self.remote.run(
                    ["test", "-f", f"{self.base}/{sib}/COMPLETE"])
                if rc2 != 0:
                    unfinished.append(sib)
            if unfinished:
                return fail("concurrent_run",
                            "unfinished staging runs present: "
                            + ", ".join(unfinished[:5])
                            + "; pass --allow-concurrent if this is intended")
        step("concurrency_guard", existing_runs=siblings)

        # -- 2c. remote disk-space check ----------------------------------
        try:
            disk = self._python(DF_PROBE, self.base, timeout=120)
        except RemoteError as exc:
            return fail("ssh_failed", str(exc))
        need = int(local["bytes_total"] * free_margin)
        step("remote_disk", free=disk["free"], required=need, checked=disk["checked"])
        if disk["free"] < need:
            # Before the transfer, which is the only step that consumes space.
            return fail("insufficient_remote_disk",
                        f"need {need} bytes free at {self.base}, have {disk['free']}")

        # -- 2d. transfer --------------------------------------------------
        #
        # `mkdir` without -p on the final component, so a run id collision is
        # an error rather than two runs sharing a directory.
        rc, out, err = self.remote.run(
            ["sh", "-c",
             f"mkdir -p {_q(self.base)} && mkdir {_q(self.run_dir)} && "
             f"mkdir {_q(self.payload_dir)} {_q(self.gate_dir)}"])
        if rc != 0:
            return fail("run_dir_failed", err.strip()[:300] or f"rc={rc}")
        step("run_dir_created", path=self.run_dir)

        t0 = time.time()
        try:
            rc, out, err = self.remote.push(source, self.payload_dir)
        except RemoteError as exc:
            return fail("transfer_failed", str(exc))
        elapsed = round(time.time() - t0, 1)
        step("transfer", rsync_rc=rc, seconds=elapsed,
             note="exit code recorded, NOT trusted as proof")
        if rc != 0:
            return fail("transfer_failed",
                        f"rsync rc={rc}: {err.strip()[:300]}")

        # -- 2e/2f. independent far-side verification ----------------------
        #
        # The whole reason this tool exists. rsync exiting 0 means rsync
        # believed it finished.
        try:
            probe = self._python(PROBE, self.payload_dir, timeout=1800)
        except RemoteError as exc:
            return fail("ssh_failed", str(exc))
        report["remote"] = {
            "tiles_total": probe.get("tiles_total"),
            "bytes_total": probe.get("bytes_total"),
            "tree_digest": probe.get("tree_digest"),
            "manifest_sha256": probe.get("manifest_sha256"),
            "release_id": probe.get("release_id"),
            "non_tile_count": probe.get("non_tile_count"),
        }
        step("remote_probe", **report["remote"])

        mismatches = []
        if probe.get("tiles_total") != local["tiles_total"]:
            mismatches.append(("file_count",
                               f"{local['tiles_total']} sent, "
                               f"{probe.get('tiles_total')} arrived"))
        if probe.get("bytes_total") != local["bytes_total"]:
            mismatches.append(("byte_count",
                               f"{local['bytes_total']} sent, "
                               f"{probe.get('bytes_total')} arrived"))
        if probe.get("manifest_sha256") != local["manifest_sha256"]:
            mismatches.append(("manifest_hash",
                               f"{local['manifest_sha256'][:16]}… sent, "
                               f"{str(probe.get('manifest_sha256'))[:16]}… arrived"))
        if probe.get("release_id") != release_id:
            mismatches.append(("release_id",
                               f"{release_id} sent, {probe.get('release_id')} arrived"))
        if probe.get("non_tile_count"):
            mismatches.append(("extra_files",
                               f"{probe['non_tile_count']} non-tile files: "
                               + ", ".join(probe.get("non_tile_files", [])[:5])))
        if probe.get("tree_digest") != local["tree_digest"]:
            mismatches.append(("tree_digest",
                               f"{local['tree_digest'][:16]}… sent, "
                               f"{str(probe.get('tree_digest'))[:16]}… arrived"))

        # The deterministic sample, compared against the manifest's own claim
        # rather than against the local tree, so the check is meaningful even
        # if the local copy were the damaged one.
        sample = (manifest.get("integrity") or {}).get("sample") or []
        remote_sample = probe.get("sample") or {}
        bad_sample = [p["path"] for p in sample
                      if remote_sample.get(p["path"]) != p["sha256"]]
        step("sample_check", size=len(sample), mismatched=bad_sample[:10])
        if bad_sample:
            mismatches.append(("sample", ", ".join(bad_sample[:5])))

        if mismatches:
            # Escalate to per-file detail. "These four files" is actionable at
            # 2am; "the hashes differ" is not.
            detail = self._diff_trees(local_scan, source)
            step("transfer_diff", **detail)
            for code, text in mismatches:
                report["failures"].append(
                    {"code": f"transfer_{code}_mismatch", "detail": text})
            return fail("transfer_integrity_failed",
                        f"{len(mismatches)} independent check(s) disagreed")

        step("transfer_verified",
             note="counts, bytes, manifest hash, tree digest and sample all agree")

        # -- 2g. remote validation with the same gate ----------------------
        gate = self._remote_gate(census=False)
        step("remote_validation", **{k: gate.get(k) for k in ("ok", "shipped")})
        report["remote_validation"] = gate
        if gate.get("ok") is False:
            report["failures"].extend(gate.get("failures", []))
            return fail("remote_validation_failed",
                        "the gate refused the release on the far side")
        if gate.get("error"):
            step("remote_validation_skipped", reason=gate["error"])

        # -- 2h. report-only comparison ------------------------------------
        report["active_tree"] = self.active_tree_summary()
        report["comparison"] = _compare(local, report["active_tree"])
        step("comparison", note="REPORT ONLY — nothing was activated",
             **report["comparison"])

        # A marker, so a later run can tell a finished staging directory from
        # an interrupted one without guessing.
        self.remote.run(["sh", "-c",
                         f"printf '%s\\n' {_q(release_id)} > {_q(self.run_dir)}/COMPLETE"])

        report["ok"] = True
        report["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        return report

    def _diff_trees(self, local_scan, source) -> dict:
        """Name the files, once the digests have already disagreed."""
        try:
            probe = self._python(PROBE, self.payload_dir, "--detail", timeout=1800)
        except RemoteError as exc:
            return {"error": str(exc)}
        remote_entries = probe.get("entries") or {}
        local_entries = {rel: [d, n] for rel, d, n in local_scan["entries"]}
        missing = sorted(set(local_entries) - set(remote_entries))
        extra = sorted(set(remote_entries) - set(local_entries))
        altered = sorted(rel for rel in set(local_entries) & set(remote_entries)
                         if local_entries[rel] != remote_entries[rel])
        return {
            "missing_count": len(missing), "missing": missing[:20],
            "extra_count": len(extra), "extra": extra[:20],
            "altered_count": len(altered), "altered": altered[:20],
        }

    def _remote_gate(self, *, census: bool) -> dict:
        """Run the SAME gate on the far side, by shipping it there.

        Not a reimplementation and not a subset: the four files copied are
        byte-identical to the ones in this repository, so a verdict that
        differs between here and there is a fact about the transfer or the
        host, which is exactly what a staging dry run is for.

        `--no-census` by default because the census imports the MVT decoder,
        which is not installed on the host and is not something a staging run
        should install. The census remains the local gate's job.
        """
        out = {"shipped": [p for p, _ in GATE_FILES], "census": census}
        staging = None
        try:
            staging = _stage_gate_files()
            rc, _o, err = self.remote.push(staging, self.gate_dir)
            if rc != 0:
                out["error"] = f"could not ship the gate: {err.strip()[:200]}"
                return out
        except OSError as exc:
            out["error"] = f"could not assemble the gate: {exc}"
            return out
        finally:
            if staging:
                shutil.rmtree(staging, ignore_errors=True)

        argv = ["python3", f"{self.gate_dir}/scripts/validate_release.py",
                "--tiles", self.payload_dir, "--report", "-", "--quiet"]
        if not census:
            argv.append("--no-census")
        rc, stdout, stderr = self.remote.run(argv, timeout=1800)
        # 0 = publishable, 1 = refused, 2 = the gate could not run. The
        # distinction is the gate's own contract and is preserved here: an
        # unrunnable gate is an outage, not a failing release.
        out["exit_code"] = rc
        if rc == 2:
            out["error"] = f"gate unrunnable on the far side: {stderr.strip()[:200]}"
            return out
        # `raw_decode` from the first brace, NOT a slice to the last one.
        # `validate_release --report -` prints the JSON and then its
        # human-readable summary, and that summary contains braces; slicing to
        # the final `}` swallows both and parses as neither.
        try:
            payload, _end = json.JSONDecoder().raw_decode(
                stdout[stdout.index("{"):])
        except (ValueError, IndexError):
            out["error"] = f"gate produced no JSON report: {stdout[:200]!r}"
            return out
        out["ok"] = payload.get("ok")
        out["failures"] = payload.get("failures", [])
        out["observed"] = payload.get("observed", {})
        out["release_id"] = payload.get("release_id")
        return out

    # -- 5. cleanup --------------------------------------------------------

    def cleanup(self, *, confirm: bool = False) -> dict:
        """Remove exactly this run's directory. Nothing else, ever.

        `confirm` is required and defaults to False so that cleanup is a
        separate, deliberate act after the evidence has been captured — the
        alternative is a dry run that deletes its own evidence when something
        interesting happens.

        The path is rebuilt from a re-validated run id rather than from a
        stored string, so there is no way for a mutated attribute to widen what
        gets deleted.
        """
        report = {"op": "cleanup", "ok": False, "run_id": self.run_id,
                  "failures": [], "confirmed": confirm}
        target = f"{assert_safe_base(self.base)}/{assert_safe_run_id(self.run_id)}"
        report["target"] = target

        if target.rstrip("/") == self.base.rstrip("/"):
            raise UnsafePath("refusing to remove the staging base itself")
        if not confirm:
            report["failures"].append({
                "code": "not_confirmed",
                "detail": f"would remove {target}; pass --confirm"})
            return report

        rc, _out, err = self.remote.run(["rm", "-rf", "--", target])
        if rc != 0:
            report["failures"].append({"code": "cleanup_failed",
                                       "detail": err.strip()[:300]})
            return report
        rc, out, _ = self.remote.run(["sh", "-c",
                                      f"test -e {_q(target)} && echo PRESENT || echo GONE"])
        report["verified"] = out.strip()
        report["ok"] = out.strip() == "GONE"
        return report


def _q(path: str) -> str:
    """Single-quote a path for a remote `sh -c`.

    Only ever applied to paths this module built from a validated base and a
    validated run id, so this is the second line of defence rather than the
    first — but the first line is a regex, and a regex plus quoting is cheaper
    than finding out which one was insufficient.
    """
    return "'" + path.replace("'", "'\\''") + "'"


def _sha256_file(path: str) -> str:
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _stage_gate_files() -> str:
    """Assemble the gate's four files into a directory laid out as it expects.

    `validate_release.py` finds its package by walking up from its own path to
    `../src`, so the shipped layout has to mirror the repository's. Copied
    rather than rewritten: the point is that the remote runs the same bytes.
    """
    import tempfile
    staging = tempfile.mkdtemp(prefix="vector-gate-")
    for rel_src, rel_dst in GATE_FILES:
        src = os.path.join(_ROOT, rel_src)
        dst = os.path.join(staging, rel_dst)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
    return staging


def _compare(local: dict, active: dict) -> dict:
    """What would change if this release were ever activated. Report only."""
    if not active or active.get("error"):
        return {"available": False, "reason": (active or {}).get("error", "unknown")}
    return {
        "available": True,
        "staged_tiles": local["tiles_total"],
        "active_tiles": active.get("tiles_total"),
        "tile_delta": (local["tiles_total"] or 0) - (active.get("tiles_total") or 0),
        "staged_bytes": local["bytes_total"],
        "active_bytes": active.get("bytes_total"),
        "active_has_manifest": bool(active.get("manifest_sha256")),
        "active_release_id": active.get("release_id"),
        "identical": local["tree_digest"] == active.get("tree_digest"),
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--alias", default=DEFAULT_ALIAS,
                   help="ssh alias from ~/.ssh/config (never a host or IP)")
    p.add_argument("--base", default=DEFAULT_BASE)
    p.add_argument("--run-id", default=None)
    p.add_argument("--json", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("dry-run", help="transfer and verify; activate nothing")
    d.add_argument("source")
    d.add_argument("--census", action="store_true",
                   help="decode every tile in the LOCAL gate pass")
    d.add_argument("--allow-concurrent", action="store_true")

    sub.add_parser("checks", help="read-only operational snapshot")

    c = sub.add_parser("cleanup", help="remove one run's staging directory")
    c.add_argument("--confirm", action="store_true")

    args = p.parse_args(argv)

    try:
        remote = SshRemote(args.alias)
        stager = RemoteStager(remote, base=args.base, run_id=args.run_id)
        if args.cmd == "dry-run":
            report = stager.dry_run(args.source, census=args.census,
                                    allow_concurrent=args.allow_concurrent)
        elif args.cmd == "checks":
            report = {"op": "checks", "ok": True,
                      "checks": stager.operational_checks()}
        else:
            if not args.run_id:
                print("cleanup needs --run-id", file=sys.stderr)
                return 2
            report = stager.cleanup(confirm=args.confirm)
    except UnsafePath as exc:
        print(f"refused: {exc}", file=sys.stderr)
        return 2
    except RemoteError as exc:
        print(f"transport failure: {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(f"{report['op']}: {'OK' if report.get('ok') else 'FAILED'}")
        if report.get("run_dir"):
            print(f"  run dir     {report['run_dir']}")
        if report.get("release_id"):
            print(f"  release     {report['release_id']}")
        for s in report.get("steps", []):
            print(f"  step        {s['step']}")
        for f in report.get("failures", []):
            print(f"  FAILURE     {f['code']}: {f['detail']}")
    return 0 if report.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
