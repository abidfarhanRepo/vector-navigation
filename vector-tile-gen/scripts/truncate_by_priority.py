"""Fix: prioritize roads (and labels/water) before truncation.

Root cause of disconnected lines at some zooms: build_qatar_tiles.py
truncates each tile to the FIRST 1500 features in dataset order. In dense
central-Doha tiles that cut removes up to 841 road segments per tile —
while the same roads remain in neighboring tiles, so lines visibly stop
at tile boundaries. 3,367 road features are cut at z14 alone.

Fix: rank features within each bucket before truncation — roads first
(major classes first), then water/parks/labels, then POIs/buildings.
Truncation then only ever drops low-priority features, never the middle
of a road network.
"""
import argparse
import json
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
    _src = os.path.join(os.path.dirname(_ROOT), _repo, "src")
    if os.path.isdir(_src) and _src not in sys.path:
        sys.path.insert(0, _src)
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
    _src = os.path.normpath(os.path.join(_ROOT, "..", _repo, "src"))
    if os.path.isdir(_src) and _src not in sys.path:
        sys.path.insert(0, _src)

from vector_ingestion.geojson import load_geojson  # noqa: E402

MAJOR = {"motorway", "trunk", "primary", "secondary"}


def feature_rank(f):
    """Lower sorts first (kept when truncating)."""
    p = f.properties or {}
    kind = p.get("kind") or ""
    if kind == "road":
        hw = (p.get("highway") or "").lower()
        return (0, 0) if hw in MAJOR else (0, 1)
    order = {"water": 1, "park": 2, "natural": 2, "landuse": 3,
             "label": 4, "building": 5}
    return (order.get(kind, 6), 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--geojson", required=True)
    ap.add_argument("--report-only", action="store_true")
    args = ap.parse_args()

    feats = load_geojson(args.geojson)

    from vector_tile_gen.tiles import lonlat_to_tile

    def tiles_for_feature(f, zoom=14):
        if not f.bbox:
            return []
        min_lon, min_lat, max_lon, max_lat = f.bbox
        x0, y0 = lonlat_to_tile(zoom, min_lon, max_lat)
        x1, y1 = lonlat_to_tile(zoom, max_lon, min_lat)
        return [(x, y) for x in range(min(x0, x1), max(x0, x1) + 1)
                for y in range(min(y0, y1), max(y0, y1) + 1)]

    LIMIT = 1500
    buckets = {}
    for i, f in enumerate(feats):
        for t in tiles_for_feature(f):
            buckets.setdefault(t, []).append(i)

    cut_before = cut_after = 0
    for t, ids in buckets.items():
        if len(ids) <= LIMIT:
            continue
        # BEFORE: first-LIMIT in dataset order
        kept_before = set(ids[:LIMIT])
        cut_roads_before = sum(
            1 for i in ids[LIMIT:] if (feats[i].properties or {}).get("kind") == "road")
        cut_before += cut_roads_before
        if args.report_only:
            continue
        # AFTER: sort by rank, keep highest-priority LIMIT
        ranked = sorted(ids, key=lambda i: feature_rank(feats[i]))
        kept_ranked = set(ranked[:LIMIT])
        cut_roads_after = sum(
            1 for i in ranked[LIMIT:] if (feats[i].properties or {}).get("kind") == "road")
        cut_after += cut_roads_after

    print(f"dense z14 tiles: {sum(1 for ids in buckets.values() if len(ids) > LIMIT)}")
    print(f"roads cut BEFORE fix (dataset-order truncation): {cut_before}")
    if not args.report_only:
        print(f"roads cut AFTER fix (priority-ranked truncation):  {cut_after}")


if __name__ == "__main__":
    main()
