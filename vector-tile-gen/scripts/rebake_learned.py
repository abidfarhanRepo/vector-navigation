#!/usr/bin/env python3
"""Selectively re-bake tiles from promoted learned geometry (issue 08).

The riskiest promotion in the effort: a bad one writes a wrong road into the
tiles every user sees. Four safeguards, all enforced by this script rather than
left to the operator:

1. **The OSM source is never edited.** Learned features are merged at bake time
   from their own overlay, so provenance stays recoverable and a withdrawal is
   just a re-bake without them.
2. **Verify before publish.** Every tile is encoded to memory and classified
   with Session 50's rule before it can reach disk. A tile that fails is not
   written, is listed in ``rejected``, and makes this script exit non-zero.
3. **Selective.** Only tiles the learned features actually touch are re-baked —
   no full-tree rebuild for one road.
4. **Cache invalidation.** The tile epoch is bumped after the write so clients
   fetch a new URL and cannot serve the old road from cache.

Usage — promote:

    python scripts/rebake_learned.py \
      --learned-facts ../vector-learning/learning-data/promoted/learned_geometry.json \
      --base-geojson ../vector-tile-gen/qatar.geojson \
      --tiles-dir ../vector-tile-server/tiles \
      --zooms 12,13,14,15,16

Usage — roll one promotion back (the "assume you will need this" path):

    python scripts/rebake_learned.py ... --withdraw road_candidate:51.53:25.28

Usage — roll the whole learned layer back:

    python scripts/rebake_learned.py ... --withdraw-all

Exit codes: 0 clean, 3 some tiles rejected (promotion should be reverted in the
fact store — the rejected keys are written to ``rejected-facts.json``), 1 error,
4 refused: the target is a V8 lane release and this basemap-only path would drop
its ``lanes`` layer (``detect_lane_release``). Nothing is written on a refusal.
"""

import argparse
import json
import os
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SRC = os.path.join(_ROOT, "src")
if os.path.isdir(_SRC) and _SRC not in sys.path:
    sys.path.insert(0, _SRC)

# Only this repo's own package. Importing a sibling (vector-ingestion) makes this
# script fail under per-repo CI isolation, where siblings are not checked out —
# it passes locally and dies in the gate.
from vector_tile_gen.learned_layer import (  # noqa: E402
    affected_tiles,
    features_from_facts,
    load_base_features,
    rebake_tiles,
    write_learned_geojson,
)
from vector_tile_gen.layers import LANES_LAYER, raw_layer_names  # noqa: E402
from vector_tile_gen.tile_version import bump_version, read_version  # noqa: E402

# Exit code for the one thing this script is NOT allowed to do: silently strip
# the V8 `lanes` layer off a release by re-baking its z15 tiles basemap-only.
# `main` uses 0 clean / 3 rejected / 1 error; this is a distinct, self-describing
# fourth code so an operator (or a gate) can tell a refusal from a bad promotion.
EXIT_LANES_UNSUPPORTED = 4


def _tile_path(tiles_dir, z, x, y):
    return os.path.join(tiles_dir, str(z), str(x), f"{y}.mvt")


def detect_lane_release(tiles_dir, tile_ids):
    """Would this re-bake destroy a V8 ``lanes`` layer? Look before writing.

    This path writes basemap-only tiles (``rebake_tiles`` calls
    ``generate_tile`` with a single ``basemap`` layer). Run against a V8 lane
    release, it would overwrite every affected z15 tile with a basemap-only one,
    silently dropping the ``lanes`` layer the release carries — the exact
    "streets/markings vanish" class the layer rule exists to prevent, only
    inflicted by the tool meant to be safe.

    Two independent signals, either of which refuses:

    * a tile this run would OVERWRITE already carries a ``lanes`` layer, read
      raw so a duplicate cannot hide (``layers.raw_layer_names``); or
    * the release descriptor says it was baked with lanes
      (``RELEASE.json`` ``input_config.lanes``), which catches a lane release
      whose lane-bearing tiles happen to fall outside this promotion's bbox.

    Returns ``(refuse: bool, reason: str)``.
    """
    lane_tiles = []
    for (z, x, y) in sorted(tile_ids):
        path = _tile_path(tiles_dir, z, x, y)
        if not os.path.exists(path):
            continue
        try:
            with open(path, "rb") as fh:
                data = fh.read()
            if data and LANES_LAYER in raw_layer_names(data):
                lane_tiles.append((z, x, y))
        except (OSError, ValueError):
            # An unreadable/undecodable existing tile is not this check's
            # concern — the re-bake's own verify-before-publish handles bad
            # bytes. Here we only refuse on a tile we can prove carries lanes.
            continue
        if len(lane_tiles) >= 5:
            break

    if lane_tiles:
        shown = ", ".join(f"{z}/{x}/{y}" for (z, x, y) in lane_tiles[:5])
        return True, (
            f"{len(lane_tiles)}+ tile(s) this run would overwrite already carry a "
            f"'{LANES_LAYER}' layer (e.g. {shown})")

    release_path = os.path.join(tiles_dir, "RELEASE.json")
    if os.path.exists(release_path):
        try:
            with open(release_path, encoding="utf-8") as fh:
                rel = json.load(fh)
            if (rel.get("input_config") or {}).get("lanes") is True:
                return True, ("RELEASE.json declares input_config.lanes=true "
                              "(a V8 lane release)")
        except (OSError, ValueError, AttributeError):
            pass

    return False, ""


def read_facts(path):
    """Read a ``learned_geometry.json`` export (wrapped doc or bare list)."""
    if not path or not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if isinstance(doc, list):
        return [f for f in doc if isinstance(f, dict)]
    if isinstance(doc, dict) and isinstance(doc.get("facts"), list):
        return [f for f in doc["facts"] if isinstance(f, dict)]
    return []


def union_bbox(features):
    """Bounding box covering every learned feature, or ``None`` if there are none."""
    boxes = [f.bbox for f in features if f.bbox]
    if not boxes:
        return None
    return (
        min(b[0] for b in boxes),
        min(b[1] for b in boxes),
        max(b[2] for b in boxes),
        max(b[3] for b in boxes),
    )


def prefilter_base(base_features, box, pad_deg=0.05):
    """Keep only base features near the learned area.

    ``generate_tile`` rescans the whole feature list for every tile, so on a
    201 k-feature extract a handful of tiles would still cost millions of bbox
    tests. Restricting the base set to the neighbourhood of the change is what
    keeps a selective re-bake actually selective.
    """
    if box is None:
        return list(base_features)
    minx, miny, maxx, maxy = box
    minx, miny, maxx, maxy = minx - pad_deg, miny - pad_deg, maxx + pad_deg, maxy + pad_deg
    out = []
    for f in base_features:
        b = getattr(f, "bbox", None)
        if not b:
            continue
        if b[2] < minx or b[0] > maxx or b[3] < miny or b[1] > maxy:
            continue
        out.append(f)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="selective learned-geometry tile re-bake (issue 08)")
    ap.add_argument("--learned-facts", required=True,
                    help="learned_geometry.json export from vector-learning")
    ap.add_argument("--base-geojson", required=True,
                    help="the OSM basemap GeoJSON (never modified)")
    ap.add_argument("--tiles-dir", required=True, help="the served tiles/ directory")
    ap.add_argument("--zooms", default="12,13,14,15,16")
    ap.add_argument("--withdraw", action="append", default=[],
                    help="fact_key to withdraw (repeatable)")
    ap.add_argument("--withdraw-all", action="store_true",
                    help="re-bake the affected tiles from the OSM base only")
    ap.add_argument("--overlay-out", default=None,
                    help="where to write the learned overlay GeoJSON (provenance)")
    ap.add_argument("--dry-run", action="store_true",
                    help="verify and report, write nothing")
    args = ap.parse_args(argv)

    zooms = [int(z) for z in args.zooms.split(",") if z.strip()]
    now_ms = int(time.time() * 1000)

    facts = read_facts(args.learned_facts)
    all_features = features_from_facts(facts)
    print(f"[facts] {len(facts)} promoted facts -> {len(all_features)} bake-ready features")

    withdrawn = set(args.withdraw)
    if args.withdraw_all:
        keep = []
        print("[withdraw] ALL learned geometry — re-baking from the OSM base only")
    else:
        keep = [f for f in all_features if f.properties.get("fact_key") not in withdrawn]
        if withdrawn:
            print(f"[withdraw] {len(all_features) - len(keep)} of {len(all_features)} "
                  f"features withdrawn: {sorted(withdrawn)}")

    # Tiles to re-bake include the WITHDRAWN features' tiles: those are exactly
    # the tiles that still contain a road which must disappear.
    tile_ids = affected_tiles(all_features, zooms)
    if not tile_ids:
        print("[tiles] nothing to re-bake (no learned geometry with a bbox)")
        return 0
    print(f"[tiles] {len(tile_ids)} affected tiles across zooms {zooms}")

    # Refuse a V8 lane release BEFORE loading the base or writing a byte. This
    # path bakes basemap-only tiles, so re-baking a lanes release's z15 tiles
    # would drop their `lanes` layer with no warning. Detect and stop instead.
    refuse, reason = detect_lane_release(args.tiles_dir, tile_ids)
    if refuse:
        print(f"[refused] {reason}", file=sys.stderr)
        print("[refused] rebake_learned.py writes basemap-only tiles and would "
              "SILENTLY DROP the 'lanes' layer from this release. Re-baking a V8 "
              "lane release through the learned-geometry path is not supported.",
              file=sys.stderr)
        print("[refused] To promote or withdraw learned geometry against a lanes "
              "release, extend this path to preserve basemap + lanes with a test "
              "proving no layer loss, then remove this guard. Nothing was written.",
              file=sys.stderr)
        return EXIT_LANES_UNSUPPORTED

    print(f"[base] loading {args.base_geojson} ...", flush=True)
    base_features = load_base_features(args.base_geojson)
    near = prefilter_base(base_features, union_bbox(all_features))
    print(f"[base] {len(base_features)} features -> {len(near)} near the change")

    result = rebake_tiles(args.tiles_dir, near, keep, tile_ids, dry_run=args.dry_run)
    print(f"[rebake] written={len(result.written)} rejected={len(result.rejected)}"
          f"{' (DRY RUN — nothing on disk)' if args.dry_run else ''}")
    for (tile, reason) in result.rejected:
        print(f"  REJECTED {tile}: {reason}", file=sys.stderr)

    if args.dry_run:
        return 0 if result.ok else 3

    overlay_out = args.overlay_out or os.path.join(args.tiles_dir, "learned-overlay.geojson")
    write_learned_geojson(overlay_out, keep)
    print(f"[provenance] learned overlay -> {overlay_out} ({len(keep)} features)")

    if not result.ok:
        # The promotion must be reverted in the fact store. tile-gen cannot
        # reach it (separate repo, ADR-0003), so hand the keys to whoever can.
        rejected_path = os.path.join(args.tiles_dir, "rejected-facts.json")
        with open(rejected_path, "w", encoding="utf-8") as fh:
            json.dump({
                "generated_at": now_ms,
                "reason": "tile integrity verification failed after re-bake",
                "fact_keys": sorted({f.properties.get("fact_key") for f in keep
                                     if f.properties.get("fact_key")}),
                "tiles": [[list(t), r] for t, r in result.rejected],
            }, fh, indent=2)
        print(f"[revert] rejected fact keys -> {rejected_path}", file=sys.stderr)
        print("[revert] run: python -m vector_learning withdraw --facts <db> "
              f"--from-rejected {rejected_path}", file=sys.stderr)
        return 3

    # Only now, with verified tiles on disk, is it honest to advertise a new
    # epoch. The epoch is a promise that this version serves the new bytes.
    reason = "withdraw-all" if args.withdraw_all else (
        f"withdraw:{len(withdrawn)}" if withdrawn else f"promote:{len(keep)}")
    record = bump_version(args.tiles_dir, now_ms=now_ms, reason=reason,
                          tiles_changed=len(result.written))
    print(f"[epoch] tile version {read_version(args.tiles_dir)['epoch']} "
          f"(was {record['epoch'] - 1}) — clients will refetch ?v={record['epoch']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
