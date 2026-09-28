#!/usr/bin/env python3
"""Pre-publication gate: nothing moves until a release proves itself here.

WHY THIS IS A SEPARATE THING FROM validate_tiles.py
---------------------------------------------------
``validate_tiles.py`` answers "is each tile a well-formed ``basemap`` tile?"
That is necessary and it is not sufficient, because every failure V7.7 was
created to stop is invisible to a per-tile check:

* a transfer that died after 3,000 of 18,311 tiles leaves 3,000 *perfectly
  valid* tiles;
* ``cp -r`` MERGES, so a tile from the previous bake survives into the new
  release and is also *perfectly valid*;
* a copy that landed only z6–z13 contains nothing malformed at all, and
  silently re-advertises ``maxzoom: 13`` — MapLibre then overzooms z13 for a
  camera at 16.5 and the driving view degrades with nothing reporting it.

Those are **whole-release** properties. They are only checkable against a
statement of what the release was supposed to be, which is what ``RELEASE.json``
now provides. So this gate asks the question the per-tile checker cannot:
*is this tree exactly, completely, the release it claims to be?*

WHAT IT REFUSES TO DO
---------------------
**It never touches the network.** A pre-publication gate that can reach
production is a gate that can be satisfied by production, and the whole point is
to decide whether to go there at all. There is a test that makes
``socket.socket`` raise and requires this to still pass.

It also never repairs. ``validate_tiles.py --repair`` exists and is useful at a
bench; a release is immutable by definition, and a gate that edits the artifact
it is judging cannot be trusted about the artifact it passed.

EXIT CODES
----------
``0``  the release is publishable
``1``  the release FAILED validation — do not publish
``2``  the gate could not run (bad arguments, missing directory)

The distinction between 1 and 2 matters to the publisher: a failed release is a
decision, an unrunnable gate is an outage, and treating them alike is how a
broken check starts reading as a passing one.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time

# Make the sibling package importable when run as a bare script, exactly as
# validate_tiles.py does.
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _base in (os.path.join(_ROOT, "src"),):
    if os.path.isdir(_base) and _base not in sys.path:
        sys.path.insert(0, _base)

from vector_tile_gen.layers import OPTIONAL_LAYERS  # noqa: E402
from vector_tile_gen.release import (  # noqa: E402
    EXPECTED_LAYER,
    is_publishable_release_id,
    parse_release_id,
    read_manifest,
    scan_tree,
    verify_manifest,
)

# Measured on both real 18k-tile trees (production and the V7-era local bake):
# ZERO zero-byte tiles in either. The bake simply does not write a file where
# there are no features, so a release that is suddenly full of empty tiles has
# had something happen to it. The default is deliberately generous — this is a
# smoke alarm, not a spec — and it is a FAILURE rather than a warning because a
# gate whose findings are advisory is a gate nobody reads.
DEFAULT_MAX_EMPTY_RATIO = 0.10


def _load_manifest(tiles_dir, explicit_path):
    if not explicit_path:
        return read_manifest(tiles_dir), None
    try:
        with open(explicit_path, encoding="utf-8") as fh:
            return json.load(fh), None
    except (OSError, ValueError) as exc:
        return None, f"could not read manifest {explicit_path!r}: {exc}"


def validate_release(
    tiles_dir,
    *,
    manifest_path=None,
    census=True,
    require_publishable=False,
    expect_buildings=False,
    max_empty_ratio=DEFAULT_MAX_EMPTY_RATIO,
):
    """Judge a staged release directory. Returns a machine-readable report.

    Never raises for a bad release — a failed release is data, not an
    exception. Raises only for things that make the gate itself unrunnable,
    which the CLI turns into exit 2.
    """
    started = time.time()
    report = {
        "gate": "validate_release",
        "gate_version": 1,
        "tiles_dir": os.path.abspath(tiles_dir),
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "census": census,
        "ok": False,
        "failures": [],
        "warnings": [],
        "release_id": None,
        "observed": {},
        "manifest": {},
    }

    if not os.path.isdir(tiles_dir):
        report["failures"].append(
            {"code": "tiles_dir_missing", "detail": f"not a directory: {tiles_dir}"})
        report["unrunnable"] = True
        report["duration_s"] = round(time.time() - started, 3)
        return report

    manifest, load_err = _load_manifest(tiles_dir, manifest_path)
    if load_err:
        report["failures"].append({"code": "manifest_unreadable", "detail": load_err})
        report["unrunnable"] = True
        report["duration_s"] = round(time.time() - started, 3)
        return report

    # ONE scan, shared by every check below. Measured on the real production
    # tree: 16.2 s with the census, 0.3 s without. Re-walking 18,311 files per
    # question is how a gate becomes something people pass `--skip` to.
    #
    # geometry_range is ALWAYS on, census or not: it is stdlib-only, so it runs
    # on the production host's bare container, which is exactly where the
    # `--no-census` gate runs. ~13 s on the production tree.
    scan = scan_tree(tiles_dir, census=census, geometry_range=True)

    verdict = verify_manifest(tiles_dir, manifest, census=census, scan=scan)
    report["release_id"] = verdict.get("release_id")
    report["observed"] = verdict.get("observed", {})
    report["failures"].extend(verdict.get("failures", []))

    if manifest:
        report["manifest"] = {
            "schema_version": manifest.get("schema_version"),
            "source": manifest.get("source", {}),
            "generator": manifest.get("generator", {}),
            "input_config": manifest.get("input_config", {}),
            "bake": manifest.get("bake", {}),
            "coverage": manifest.get("coverage", {}),
            "layers": manifest.get("layers", {}),
            "compat": manifest.get("compat", {}),
        }

    # --- publishability -------------------------------------------------
    #
    # A release built from an uncommitted tree is fine on a bench and fine on
    # the emulator. It is not fine in production, because it cannot be rebuilt
    # from a commit and therefore cannot be audited or reproduced.
    rid = report["release_id"]
    if require_publishable:
        if not rid or not is_publishable_release_id(rid):
            detail = f"{rid!r} is not publishable"
            if rid:
                try:
                    if parse_release_id(rid)["dirty"]:
                        detail = (f"{rid} was baked from an UNCOMMITTED tree; "
                                  "it cannot be reproduced from a commit")
                except ValueError:
                    pass
            report["failures"].append({"code": "release_not_publishable",
                                       "detail": detail})

    # --- emptiness ------------------------------------------------------
    total = scan["tiles_total"]
    empty = scan["empty_tiles"]
    ratio = (empty / total) if total else 0.0
    report["observed"]["empty_ratio"] = round(ratio, 6)
    if total and ratio > max_empty_ratio:
        report["failures"].append({
            "code": "too_many_empty_tiles",
            "detail": (f"{empty}/{total} tiles are zero-byte ({ratio:.2%}), "
                       f"above the {max_empty_ratio:.0%} ceiling"),
        })

    # --- geometry inside the tile (+ buffer) ----------------------------
    #
    # Every vertex of every feature in every layer must lie within
    # [-CLIP_BUFFER, extent + CLIP_BUFFER]. The bake clips to exactly that
    # (encode.encode_tile). A release that does not is the unclipped-bake bug:
    # vertices up to +/-208,166 tile units, which MapLibre Native drops
    # ("paths outside valid range of coordinate_type") or, overzoomed, draws as
    # phantom straight roads. GL JS renders it fine, so nothing on the web
    # shows it; this is the only place it is caught before a phone is.
    geo = scan["geometry_range"]
    report["observed"]["geometry_range"] = {
        k: v for k, v in geo.items() if k != "unreadable"}
    report["observed"]["geometry_range"]["unreadable_tiles"] = len(geo["unreadable"])
    if geo["features_out_of_range"]:
        worst = geo["worst"] or {}
        by_layer = ", ".join(f"{k}={v}" for k, v in
                             geo["features_out_of_range_by_layer"].items())
        report["failures"].append({
            "code": "geometry_out_of_range",
            "detail": (f"{geo['features_out_of_range']} features in "
                       f"{geo['tiles_out_of_range']} tiles have a vertex outside "
                       f"[-{geo['buffer']}, extent+{geo['buffer']}] "
                       f"({by_layer}); worst {worst.get('value')} in "
                       f"{worst.get('tile')} layer {worst.get('layer')!r} — "
                       "the tiles were not clipped, and MapLibre Native will "
                       "drop or mis-draw these features"),
        })
    # Bytes the geometry walk cannot parse are NOT failed here: judging corrupt
    # tiles is the census's job (`undecodable_tile`), and a second, differently
    # worded failure for the same tile helps nobody. Without the census it is
    # still worth saying out loud, because then nothing else will.
    if geo["unreadable"] and not census:
        first, why = geo["unreadable"][0]
        report["warnings"].append({
            "code": "geometry_unreadable",
            "detail": (f"{len(geo['unreadable'])} tiles could not be parsed for "
                       f"the geometry-range check (first: {first}: {why})"),
        })

    # --- the 3D claim, when the caller says this release carries one ----
    #
    # Asserted here rather than only in the manifest because "the manifest
    # agrees with the tiles" is satisfied perfectly by a release where BOTH say
    # zero. That is exactly production's current state, and shipping it again
    # while believing 3D had been deployed is the specific mistake this flag
    # exists to make impossible.
    if expect_buildings:
        if not census:
            report["failures"].append({
                "code": "buildings_unverifiable",
                "detail": "--expect-buildings requires the census; "
                          "refusing to certify a 3D claim without decoding",
            })
        elif scan["building_features"] == 0:
            report["failures"].append({
                "code": "no_buildings_present",
                "detail": ("release claims 3D but contains ZERO building "
                           "features across all zooms"),
            })
        elif scan["building_features_with_height_m"] == 0:
            report["failures"].append({
                "code": "no_extrudable_buildings",
                "detail": (f"{scan['building_features']} building features, but "
                           "NONE carry height_m — nothing can be extruded"),
            })

    # --- warnings: real, but not grounds to block a release -------------
    if census and scan["layer_names"] and \
            set(scan["layer_names"]) <= {EXPECTED_LAYER} | OPTIONAL_LAYERS and total:
        covered = scan["layer_names"].get(EXPECTED_LAYER, 0)
        if covered < total - empty:
            report["warnings"].append({
                "code": "tiles_without_basemap_layer",
                "detail": f"{total - empty - covered} non-empty tiles carry no "
                          f"{EXPECTED_LAYER!r} layer",
            })
    if not census:
        report["warnings"].append({
            "code": "census_skipped",
            "detail": "layer, building and corruption checks did NOT run",
        })

    report["ok"] = not report["failures"]
    report["duration_s"] = round(time.time() - started, 3)
    return report


def print_report(report, *, verbose=True):
    rid = report.get("release_id") or "(no release id)"
    print(f"[gate] {rid}")
    print(f"[gate] {report['tiles_dir']}")
    obs = report.get("observed", {})
    if obs:
        print(f"[gate] tiles={obs.get('tiles_total')} "
              f"bytes={obs.get('bytes_total')} "
              f"empty={obs.get('empty_tiles')} "
              f"zooms={obs.get('zooms')}")
        if report.get("census"):
            print(f"[gate] building features={obs.get('building_features')} "
                  f"distinct={obs.get('distinct_building_ids')} "
                  f"with height={obs.get('building_features_with_height_m')}")
        print(f"[gate] tree digest={str(obs.get('tree_digest'))[:32]}")
        geo = obs.get("geometry_range") or {}
        if geo:
            print(f"[gate] geometry outside ±{geo.get('buffer')}: "
                  f"features={geo.get('features_out_of_range')} "
                  f"tiles={geo.get('tiles_out_of_range')} "
                  f"worst={geo.get('worst')}")
    for w in report.get("warnings", []):
        print(f"  WARN  {w['code']}: {w['detail']}")
    if verbose:
        for f in report.get("failures", []):
            print(f"  FAIL  {f['code']}: {f['detail']}")
    if report["ok"]:
        print(f"[gate] PASS in {report.get('duration_s')}s")
    else:
        print(f"[gate] FAIL — {len(report['failures'])} failure(s) "
              f"in {report.get('duration_s')}s")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Pre-publication gate for a Vector tile release")
    ap.add_argument("--tiles", required=True,
                    help="the staged release directory (contains z/ dirs and "
                         "RELEASE.json)")
    ap.add_argument("--manifest", default=None,
                    help="read the manifest from here instead of "
                         "<tiles>/RELEASE.json")
    ap.add_argument("--report", default=None,
                    help="write the machine-readable JSON report to this path "
                         "(use '-' for stdout)")
    ap.add_argument("--no-census", action="store_true",
                    help="skip decoding every tile. FAST and INCOMPLETE: no "
                         "layer, corruption or building checks run")
    ap.add_argument("--require-publishable", action="store_true",
                    help="fail a release baked from an uncommitted tree")
    ap.add_argument("--expect-buildings", action="store_true",
                    help="fail unless the release actually contains extrudable "
                         "buildings (for a 3D release)")
    ap.add_argument("--max-empty-ratio", type=float,
                    default=DEFAULT_MAX_EMPTY_RATIO,
                    help=f"ceiling on zero-byte tiles "
                         f"(default {DEFAULT_MAX_EMPTY_RATIO})")
    ap.add_argument("--quiet", action="store_true",
                    help="suppress the per-failure lines")
    args = ap.parse_args(argv)

    report = validate_release(
        args.tiles,
        manifest_path=args.manifest,
        census=not args.no_census,
        require_publishable=args.require_publishable,
        expect_buildings=args.expect_buildings,
        max_empty_ratio=args.max_empty_ratio,
    )

    if args.report:
        payload = json.dumps(report, indent=2, sort_keys=True)
        if args.report == "-":
            print(payload)
        else:
            os.makedirs(os.path.dirname(os.path.abspath(args.report)),
                        exist_ok=True)
            tmp = args.report + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                fh.write(payload + "\n")
            os.replace(tmp, args.report)

    print_report(report, verbose=not args.quiet)

    if report.get("unrunnable"):
        return 2
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
