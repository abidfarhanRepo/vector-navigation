#!/usr/bin/env python3
"""Turn a flat pre-V7.7 tile tree into a named release, without moving it.

WHY THIS EXISTS TWICE OVER
--------------------------
Two callers need exactly this operation and must not drift apart:

* the **production migration** (V7.7 runbook step 2), where an unprovenanced
  tree that has been serving for months becomes the first named release;
* **bootstrap.sh**, which populates ``vector-data-tiles`` with a flat
  ``cp -r`` and would otherwise produce a volume that the migrated
  ``TILE_DIR=/app/tiles/current`` cannot read at all — a fresh install
  rendering a blank map, which is precisely the class of silent failure V7.7
  exists to end.

One script, so the layout a developer bootstraps is the layout production runs.

COPY, NEVER MOVE
----------------
The flat tree is left exactly where it is. That is what makes the migration
reversible by reverting a single compose line: the old ``TILE_DIR=/app/tiles``
still finds ``6/``…``15/`` untouched, byte for byte. Moving would save ~62 MB
against 288 GiB free and would turn the rollback into "restore production by
hand while it is down".

WHAT IT REFUSES
---------------
``targets_exist``       ``releases``, ``current`` or ``previous`` already there
``no_tiles``            nothing that looks like a tile tree
``digest_mismatch``     the tree is not the one the caller expected
``copy_corrupt``        the copy does not verify against its own manifest
``not_writable``        the destination cannot be written

A refused import removes anything it had begun to create. It never touches the
source tree, on any path, including the failure ones.

PROVENANCE IS NOT INVENTED
--------------------------
The manifest records what is actually known — nothing about a commit, because
there is nothing to know — and the release id says ``legacy-import`` in it. See
``vector_tile_gen.release.LEGACY_ID_RE`` for why a borrowed sha would have been
worse than no sha.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _base in (os.path.join(_ROOT, "src"),):
    if os.path.isdir(_base) and _base not in sys.path:
        sys.path.insert(0, _base)

from vector_tile_gen.release import (  # noqa: E402
    RELEASE_FILENAME,
    RELEASES_SUBDIR,
    build_manifest,
    format_legacy_id,
    scan_tree,
    tree_digest,
    verify_manifest,
    write_digests,
    write_manifest,
)

CURRENT = "current"
PREVIOUS = "previous"
RESERVED = (RELEASES_SUBDIR, CURRENT, PREVIOUS)

# Files that live beside the zoom directories in a legacy volume and are not
# tiles. They are carried into the release so nothing is lost, and they are not
# part of the tile census either way.
LEGACY_SIDECARS = ("VERSION.json", "provenance.json")


def import_legacy_tree(volume: str, *, now_s: float | None = None,
                       expect_digest: str | None = None,
                       activate: bool = True) -> dict:
    """Build ``releases/<legacy-id>/`` from the flat tree in ``volume``.

    Returns a machine-readable report and never raises for a refused import —
    a refusal is data. Raises only for arguments that make the operation
    impossible to attempt.
    """
    report = {"op": "import_legacy_tree", "ok": False, "volume": volume,
              "release_id": None, "failures": [], "activated": False}

    if not os.path.isdir(volume):
        report["failures"].append(
            {"code": "not_writable", "detail": f"not a directory: {volume}"})
        return report

    present = [name for name in RESERVED
               if os.path.lexists(os.path.join(volume, name))]
    if present:
        # Not an error to recover from: somebody has already been here, and
        # this script does not know whether what they did was finished.
        report["failures"].append({
            "code": "targets_exist",
            "detail": "already present: " + ", ".join(present)})
        return report

    scan = scan_tree(volume, census=False)
    if scan["tiles_total"] == 0:
        report["failures"].append({
            "code": "no_tiles", "detail": f"no .mvt files under {volume}"})
        return report

    digest = tree_digest(scan["entries"])
    report["source"] = {"tiles_total": scan["tiles_total"],
                        "bytes_total": scan["bytes_total"],
                        "tree_digest": digest}

    if expect_digest and digest != expect_digest:
        # The caller measured this tree earlier — in the migration's case,
        # during the preflight. If it has changed since, every decision made
        # on the strength of that measurement is about a tree that no longer
        # exists.
        report["failures"].append({
            "code": "digest_mismatch",
            "detail": f"expected {expect_digest[:16]}…, found {digest[:16]}…"})
        return report

    release_id = format_legacy_id(now_s=now_s if now_s is not None else time.time(),
                                  tree_digest=digest)
    report["release_id"] = release_id
    dest = os.path.join(volume, RELEASES_SUBDIR, release_id)

    try:
        os.makedirs(dest, exist_ok=False)
        for zoom in sorted((e for e in os.listdir(volume)
                            if e.isdigit()
                            and os.path.isdir(os.path.join(volume, e))), key=int):
            shutil.copytree(os.path.join(volume, zoom),
                            os.path.join(dest, zoom))
        for sidecar in LEGACY_SIDECARS:
            src = os.path.join(volume, sidecar)
            if os.path.isfile(src):
                shutil.copy2(src, os.path.join(dest, sidecar))
                report.setdefault("sidecars", []).append(sidecar)
    except OSError as exc:
        shutil.rmtree(dest, ignore_errors=True)
        report["failures"].append({"code": "not_writable", "detail": str(exc)})
        return report

    # CENSUSED, and the cost is accepted. An earlier revision scanned with
    # `census=False` and handed `build_manifest` a `dict(copied, censused=True)`,
    # which made every imported release assert `building_features: 0` without
    # having decoded a tile. On production's tree that claim is true by
    # accident — V7.7 §8 censused digest `ca07394a…` exhaustively and found
    # zero buildings — but the other caller is `bootstrap.sh`, importing a
    # developer's freshly baked volume, where it is simply false. A censused
    # `verify_manifest` on such a release fails with three `buildings_mismatch`
    # findings, so the lie is not even stable: it breaks the gate that the
    # publisher runs before it will activate anything.
    #
    # Measured on the real 18,311-tile tree: 0.3 s without the census, 16.2 s
    # with it. Sixteen seconds, once, on an operation that copies 62 MB.
    copied = scan_tree(dest, census=True)
    manifest = build_manifest(
        dest,
        release_id=release_id,
        # Everything here is true, and nothing here is a guess. An imported
        # tree has no source extract and no generating commit; saying so is
        # the whole point of the legacy grammar.
        source={
            "kind": "imported-legacy",
            "imported_from": os.path.abspath(volume),
            "note": "pre-V7.7 flat tile tree; original OSM snapshot unknown",
            "observed_tree_digest": digest,
        },
        generator={
            "repo_sha": "",
            "repo_dirty": None,
            "note": "not produced by a known bake; imported by "
                    "import_legacy_tree.py",
        },
        input_config={"imported": True, "zooms": sorted(copied["zooms"])},
        scan=copied,
    )
    write_digests(dest, copied["entries"])
    write_manifest(dest, manifest)

    verdict = verify_manifest(dest, manifest, census=False)
    if not verdict["ok"]:
        # A copy that does not verify is worse than no copy: it would sit in
        # `releases/` looking like a release. Removed, and the flat tree — the
        # thing still being served — was never touched.
        shutil.rmtree(dest, ignore_errors=True)
        report["failures"].append({"code": "copy_corrupt",
                                   "detail": json.dumps(verdict["failures"])[:300]})
        return report

    report["copy"] = {"tiles_total": copied["tiles_total"],
                      "bytes_total": copied["bytes_total"],
                      "tree_digest": tree_digest(copied["entries"])}
    report["manifest_sha256"] = manifest["integrity"]["manifest_sha256"]

    if activate:
        # The same discipline `publish_release.activate` uses — relative
        # target, temporary symlink, rename(2) — so the migration does not
        # introduce a second way of pointing `current` at something.
        tmp = os.path.join(volume, f".current.{release_id}.tmp")
        if os.path.lexists(tmp):
            os.unlink(tmp)
        os.symlink(os.path.join(RELEASES_SUBDIR, release_id), tmp)
        os.rename(tmp, os.path.join(volume, CURRENT))
        report["activated"] = True
        report["current"] = os.readlink(os.path.join(volume, CURRENT))

    # `previous` is deliberately NOT created. There is one release, so there
    # is nothing to roll back to at the release level, and a `previous` naming
    # the same release would be a lie that `publish_release.recover` reports as
    # `previous_equals_current`. The rollback for a migration is the compose
    # line, not a pointer swap.
    report["ok"] = True
    return report


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("volume", help="the tile volume holding the flat tree")
    p.add_argument("--expect-digest", default=None,
                   help="refuse unless the tree digests to this")
    p.add_argument("--no-activate", action="store_true",
                   help="build the release but do not create `current`")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    report = import_legacy_tree(args.volume, expect_digest=args.expect_digest,
                                activate=not args.no_activate)
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(f"import: {'OK' if report['ok'] else 'FAILED'}"
              + (f"  {report['release_id']}" if report["release_id"] else ""))
        if report.get("copy"):
            print(f"  {report['copy']['tiles_total']} tiles, "
                  f"{report['copy']['bytes_total']} bytes")
        if report.get("activated"):
            print(f"  current -> {report['current']}")
        for f in report["failures"]:
            print(f"  FAILURE {f['code']}: {f['detail']}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
