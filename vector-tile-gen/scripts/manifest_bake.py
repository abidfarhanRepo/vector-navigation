#!/usr/bin/env python3
"""Turn a freshly baked tile tree into a named release.

WHY THIS EXISTS
---------------
V7.7 built the release manifest (``vector_tile_gen.release.build_manifest``),
the gate that judges it (``validate_release.py``), the publisher that installs
it (``publish_release.py``) and the importer that retrofits it onto an
unprovenanced tree (``import_legacy_tree.py``). Every one of those *consumes*
``RELEASE.json``.

Nothing *produced* one from a bake. ``build_manifest`` had exactly two callers
in the repository — ``import_legacy_tree.py`` and the test suite — so the only
release identity Vector could create was one that claims no provenance at all.
A tile tree straight out of ``build_qatar_tiles.py`` carries ``VERSION.json``
(an epoch nobody reads, V7.7 §3) and nothing else, which means it cannot be
staged, cannot be validated and cannot be published. This closes that gap: the
bake stays a bake, and the release identity is applied to its output as a
separate, auditable step.

IT DOES NOT BAKE
----------------
Deliberately, and for the reason ``publish_release.py`` is not a baker: a step
that both produces bytes and certifies them is a step whose certificate means
nothing. This reads a tree that already exists, measures it, records where it
came from, and writes the manifest as the last act. If the bake was wrong, this
describes a wrong bake accurately — which is what lets the gate refuse it.

WHAT "PROVENANCE" MEANS HERE, CONCRETELY
----------------------------------------
Every field is measured from a file on disk or read from git. Nothing is
supplied by the operator except the *paths*, and each path is hashed rather
than trusted:

* the PBF, the extract and the converted GeoJSON, each by sha256 and byte count
  — so "which snapshot is this release made of" is answerable without the
  files still being present;
* the three pipeline scripts, each by sha256 — so a release built from an
  edited converter is distinguishable from one built from the committed one
  even when the commit is the same (which is what ``-dirty`` alone cannot tell
  you: it says the tree was dirty, not *where*);
* the commit and its dirty flag, from ``git_head``, which is conservative in
  both fields so a generator that cannot be identified cannot produce a
  publishable id.

A source path the operator names and that does not exist is a **refusal**, not
a blank field. A manifest with a blank ``extract_sha256`` looks like a release
whose extract was not relevant; a refusal looks like what it is.

REFUSALS
--------
``manifest_exists``   the tree already carries a ``RELEASE.json``; a release is
                      immutable and this will not overwrite one
``no_tiles``          nothing under the directory looks like a tile tree
``source_missing``    a declared source artifact is not on disk
``dirty_tree``        the working tree is dirty and ``--allow-dirty`` was not
                      given — the id would carry ``-dirty`` and the gate would
                      refuse it at ``--require-publishable`` anyway
``unidentified``      git could not name the commit, so no honest bake id exists
``verify_failed``     the manifest just written does not verify against the
                      tree it describes
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _base in (os.path.join(_ROOT, "src"),):
    if os.path.isdir(_base) and _base not in sys.path:
        sys.path.insert(0, _base)

from vector_tile_gen.release import (  # noqa: E402
    RELEASE_FILENAME,
    build_manifest,
    format_release_id,
    git_head,
    is_valid_release_id,
    scan_tree,
    tree_digest,
    verify_manifest,
    write_digests,
    write_manifest,
)

# The three scripts that decide what a tile contains. Hashed individually
# because "the tree was dirty" does not say WHICH of them was edited, and these
# are the ones where an edit changes the output rather than the ergonomics.
PIPELINE_SCRIPTS = {
    "extractor": "fetch_qatar_pbf.py",
    "converter": "osm_to_geojson.py",
    "builder": "build_qatar_tiles.py",
}


def sha256_file(path: str) -> str:
    """Streaming sha256. 1 MiB chunks: a 439 MB extract must not be resident."""
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _artifact(path: str | None) -> dict | None:
    """Name, size and digest of one source artifact, or None if not declared."""
    if not path:
        return None
    return {
        "name": os.path.basename(path),
        "path": os.path.abspath(path),
        "bytes": os.path.getsize(path),
        "sha256": sha256_file(path),
    }


def _dependency_versions() -> dict:
    """The encoder's version, which decides the bytes in every tile."""
    out = {"python": ".".join(str(p) for p in sys.version_info[:3])}
    try:
        import importlib.metadata as md

        out["mapbox_vector_tile"] = md.version("mapbox-vector-tile")
    except Exception:  # noqa: BLE001 - an unmeasurable version is recorded as such
        out["mapbox_vector_tile"] = ""
    return out


def manifest_bake(
    tiles_dir: str,
    *,
    pbf: str | None = None,
    extract: str | None = None,
    geojson: str | None = None,
    region: str = "qatar",
    bbox: str = "",
    geofabrik_release: str = "",
    zooms: list | None = None,
    max_per_tile: int | None = None,
    learned_facts: str | None = None,
    lane_attrs: str | None = None,
    taper: bool = False,
    bake_started_at: str = "",
    bake_finished_at: str = "",
    bake_host: str = "",
    release_id: str | None = None,
    allow_dirty: bool = False,
    census: bool = True,
    now_s: float | None = None,
    repo_root: str | None = None,
) -> dict:
    """Write ``RELEASE.json`` and ``TILE_DIGESTS.tsv`` into ``tiles_dir``.

    Returns a machine-readable report and never raises for a refusal — a
    refused bake is data. Raises only for arguments that make the operation
    impossible to attempt at all.
    """
    report: dict = {"op": "manifest_bake", "ok": False,
                    "tiles_dir": os.path.abspath(tiles_dir),
                    "release_id": None, "failures": []}

    if not os.path.isdir(tiles_dir):
        report["failures"].append(
            {"code": "no_tiles", "detail": f"not a directory: {tiles_dir}"})
        return report

    if os.path.exists(os.path.join(tiles_dir, RELEASE_FILENAME)):
        # A release is immutable by definition. Overwriting a manifest would
        # re-identify bytes that may already have been staged, transferred or
        # served under the old id — the one thing no part of this system can
        # detect afterwards.
        report["failures"].append({
            "code": "manifest_exists",
            "detail": f"{RELEASE_FILENAME} already present; a release is immutable"})
        return report

    # Declared-but-absent is a refusal, never a blank field. See the module
    # docstring: a blank digest reads as "not relevant", which is a different
    # claim from "the operator named a file that is not there".
    for label, path in (("pbf", pbf), ("extract", extract), ("geojson", geojson),
                        ("lane_attrs", lane_attrs)):
        if path and not os.path.isfile(path):
            report["failures"].append({
                "code": "source_missing", "detail": f"{label}: {path}"})
    if report["failures"]:
        return report

    head = git_head(repo_root or os.path.dirname(_ROOT))
    if release_id is None:
        if not head["sha"]:
            report["failures"].append({
                "code": "unidentified",
                "detail": "git could not name HEAD; there is no honest bake id"})
            return report
        if head["dirty"] and not allow_dirty:
            report["failures"].append({
                "code": "dirty_tree",
                "detail": ("the working tree is dirty; the id would carry "
                           "-dirty and --require-publishable would refuse it. "
                           "Pass --allow-dirty for a bench or emulator bake.")})
            return report
        release_id = format_release_id(
            now_s=now_s if now_s is not None else time.time(),
            git_sha=head["sha"], dirty=head["dirty"])
    elif not is_valid_release_id(release_id):
        report["failures"].append({
            "code": "unidentified", "detail": f"not a valid release id: {release_id!r}"})
        return report
    report["release_id"] = release_id

    scan = scan_tree(tiles_dir, census=census)
    if scan["tiles_total"] == 0:
        report["failures"].append(
            {"code": "no_tiles", "detail": f"no .mvt files under {tiles_dir}"})
        return report

    scripts_dir = os.path.join(_ROOT, "scripts")
    generator = {
        "repo_sha": head["sha"],
        "repo_dirty": head["dirty"],
        **_dependency_versions(),
    }
    for role, name in PIPELINE_SCRIPTS.items():
        path = os.path.join(scripts_dir, name)
        generator[role] = name
        generator[f"{role}_sha256"] = sha256_file(path) if os.path.isfile(path) else ""
    if lane_attrs:
        # V8: the lanes layer is decided by a module, not by one of the three
        # scripts, so it is hashed on its own for the same reason they are.
        lanes_py = os.path.join(_ROOT, "src", "vector_tile_gen", "lanes.py")
        generator["lanes"] = "vector_tile_gen/lanes.py"
        generator["lanes_sha256"] = sha256_file(lanes_py) if os.path.isfile(lanes_py) else ""
    if taper:
        # The carriageway width tapers (build_qatar_tiles.py --taper) are decided
        # by their own module too, so it is hashed the same way.
        taper_py = os.path.join(_ROOT, "src", "vector_tile_gen", "taper.py")
        generator["taper"] = "vector_tile_gen/taper.py"
        generator["taper_sha256"] = sha256_file(taper_py) if os.path.isfile(taper_py) else ""

    source: dict = {"kind": "osm-pbf", "region": region}
    if bbox:
        # Verbatim, in the project's own south,west,north,east order. A manifest
        # that silently normalises its input cannot be compared against it.
        source["bbox"] = bbox
    if geofabrik_release:
        source["geofabrik_release"] = geofabrik_release
    for label, path in (("pbf", pbf), ("extract", extract), ("geojson", geojson),
                        ("lane_attrs", lane_attrs)):
        art = _artifact(path)
        if art is None:
            continue
        source[f"{label}_name"] = art["name"]
        source[f"{label}_bytes"] = art["bytes"]
        source[f"{label}_sha256"] = art["sha256"]

    input_config: dict = {
        "zooms": list(zooms) if zooms else sorted(scan["zooms"]),
        "learned_facts": learned_facts,
    }
    if max_per_tile is not None:
        input_config["max_per_tile"] = max_per_tile
    if lane_attrs:
        input_config["lanes"] = True
    if taper:
        input_config["taper"] = True

    manifest = build_manifest(
        tiles_dir,
        release_id=release_id,
        source=source,
        generator=generator,
        input_config=input_config,
        bake_started_at=bake_started_at,
        bake_finished_at=bake_finished_at,
        scan=scan,
    )
    if bake_host:
        manifest["bake"]["host"] = bake_host
        # The host moved after the integrity block was computed, so the
        # manifest digest has to follow it. `build_manifest` hashes the
        # document minus its own integrity block, which is what makes this
        # safe to recompute rather than a second, divergent rule.
        from vector_tile_gen.release import manifest_digest  # noqa: PLC0415

        manifest["integrity"]["manifest_sha256"] = manifest_digest(manifest)

    write_digests(tiles_dir, scan["entries"])
    write_manifest(tiles_dir, manifest)

    # The manifest is a promise about bytes. Verifying it here, against the
    # tree it was just written into, is what turns the promise into a
    # measurement — and it is cheap, because the scan is reused.
    verdict = verify_manifest(tiles_dir, manifest, census=census, scan=scan)
    if not verdict["ok"]:
        os.remove(os.path.join(tiles_dir, RELEASE_FILENAME))
        report["failures"].append({
            "code": "verify_failed",
            "detail": json.dumps(verdict["failures"])[:400]})
        return report

    report["ok"] = True
    report["manifest_sha256"] = manifest["integrity"]["manifest_sha256"]
    report["tree_digest"] = tree_digest(scan["entries"])
    report["coverage"] = manifest["coverage"]
    report["layers"] = manifest["layers"]
    report["census"] = census
    return report


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("tiles_dir", help="the baked tree (the directory holding 6/ … 15/)")
    p.add_argument("--pbf", default=None, help="the source .osm.pbf")
    p.add_argument("--extract", default=None, help="the .osm extract fed to the converter")
    p.add_argument("--geojson", default=None, help="the converted GeoJSON fed to the baker")
    p.add_argument("--region", default="qatar")
    p.add_argument("--bbox", default="", help="verbatim, in the project's own order")
    p.add_argument("--geofabrik-release", default="")
    p.add_argument("--zooms", default="", help="as passed to build_qatar_tiles.py")
    p.add_argument("--max-per-tile", type=int, default=None)
    p.add_argument("--learned-facts", default=None)
    p.add_argument("--lane-attrs", default=None,
                   help="the lane sidecar passed to build_qatar_tiles.py --lanes (V8); "
                        "recorded and hashed as a source")
    p.add_argument("--taper", action="store_true",
                   help="the tree was baked with build_qatar_tiles.py --taper; "
                        "recorded in input_config and taper.py hashed")
    p.add_argument("--bake-started-at", default="")
    p.add_argument("--bake-finished-at", default="")
    p.add_argument("--bake-host", default="")
    p.add_argument("--release-id", default=None,
                   help="override the derived id (testing and re-manifesting)")
    p.add_argument("--allow-dirty", action="store_true",
                   help="permit a -dirty id; the gate still refuses it for production")
    p.add_argument("--no-census", action="store_true",
                   help="skip decoding. The manifest then makes no layer claims "
                        "and build_manifest will refuse it — present only so the "
                        "refusal is reachable from the CLI")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    zooms = [int(z) for z in args.zooms.split(",") if z.strip()] or None
    report = manifest_bake(
        args.tiles_dir,
        pbf=args.pbf, extract=args.extract, geojson=args.geojson,
        region=args.region, bbox=args.bbox,
        geofabrik_release=args.geofabrik_release,
        zooms=zooms, max_per_tile=args.max_per_tile,
        learned_facts=args.learned_facts,
        lane_attrs=args.lane_attrs,
        taper=args.taper,
        bake_started_at=args.bake_started_at,
        bake_finished_at=args.bake_finished_at,
        bake_host=args.bake_host,
        release_id=args.release_id,
        allow_dirty=args.allow_dirty,
        census=not args.no_census,
    )

    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(f"manifest: {'OK' if report['ok'] else 'FAILED'}"
              + (f"  {report['release_id']}" if report["release_id"] else ""))
        if report["ok"]:
            cov, lay = report["coverage"], report["layers"]
            print(f"  {cov['tiles_total']} tiles, {cov['bytes_total']} bytes, "
                  f"z{cov['minzoom']}-{cov['maxzoom']}")
            print(f"  building features={lay['building_features']} "
                  f"with height_m={lay['building_features_with_height_m']} "
                  f"distinct={lay['distinct_building_ids']}")
            print(f"  tree digest={report['tree_digest']}")
        for f in report["failures"]:
            print(f"  FAILURE {f['code']}: {f['detail']}")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
