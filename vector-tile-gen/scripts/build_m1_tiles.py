#!/usr/bin/env python3
"""M1 slice demo: GeoJSON -> ingestion -> map-store -> tile-gen -> .mvt tiles.

Runs the full Vector M1 "Map Display" pipeline end-to-end with no external
services (adr-0005 product-first, adr-0015 M1 slice):

  1. vector-ingestion  loads + normalizes the sample GeoJSON.
  2. vector-map-store   persists the features (in-memory FeatureStore).
  3. vector-tile-gen    encodes an MVT tile per (z, x, y) touched by the data.

Output: <out>/tiles/{z}/{x}/{y}.mvt  (served by vector-tile-server).

Usage (from workspace root, with the three src/ dirs on PYTHONPATH):
  python vector-tile-gen/scripts/build_m1_tiles.py \
      --geojson vector-ingestion/tests/data/sample.geojson \
      --out vector-tile-server \
      --zoom 12

Emitting multiple zooms:
  python vector-tile-gen/scripts/build_m1_tiles.py \
      --geojson vector-ingestion/tests/data/sample.geojson \
      --out vector-tile-server \
      --zooms 10,11,12

``--zoom N`` still works (single zoom, backward compatible) and takes
precedence over ``--zooms`` when supplied. ``--zooms`` is a comma-separated
list of integer zooms and defaults to "12". For every requested zoom the tile
set touched by the data is computed and written to
``<out>/tiles/{z}/{x}/{y}.mvt``.
"""

import argparse
import os
import sys

# Allow running from the workspace root by wiring sibling src/ dirs.
_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.abspath(os.path.join(_HERE, "..", ".."))
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
    _src = os.path.join(_ROOT, _repo, "src")
    if _src not in sys.path:
        sys.path.insert(0, _src)

from vector_ingestion.geojson import load_geojson  # noqa: E402
from vector_map_store.store import FeatureStore  # noqa: E402
from vector_tile_gen.pipeline import generate_tile  # noqa: E402
from vector_tile_gen.tiles import lonlat_to_tile  # noqa: E402


def _tiles_for_features(features, zoom):
    tiles = set()
    for f in features:
        if not f.bbox:
            continue
        min_lon, min_lat, max_lon, max_lat = f.bbox
        x0, y0 = lonlat_to_tile(zoom, min_lon, max_lat)  # NW corner
        x1, y1 = lonlat_to_tile(zoom, max_lon, min_lat)  # SE corner
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                tiles.add((zoom, x, y))
    return sorted(tiles)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Build M1 MVT tiles.")
    ap.add_argument("--geojson", required=True)
    ap.add_argument("--out", required=True, help="output repo dir; tiles/ created under it")
    ap.add_argument("--zoom", type=int, default=None,
                    help="single zoom (backward compatible); overrides --zooms")
    ap.add_argument("--zooms", default="12",
                    help="comma-separated zooms, e.g. '10,11,12' (default '12')")
    args = ap.parse_args(argv)

    if args.zoom is not None:
        zooms = [args.zoom]
    else:
        zooms = [int(z) for z in args.zooms.split(",") if z.strip() != ""]

    features = load_geojson(args.geojson)
    store = FeatureStore()
    for f in features:
        store.insert(f)
    stored = store.all()
    print(f"[ingestion] loaded {len(features)} features")
    print(f"[map-store] stored  {store.count()} features")

    out_dir = os.path.join(args.out, "tiles")
    total_written = 0
    per_zoom = {}
    for z in zooms:
        tiles = _tiles_for_features(stored, z)
        written = 0
        for (zz, x, y) in tiles:
            data = generate_tile(stored, zz, x, y, layer_name="basemap")
            # skip empty tiles (no features intersect)
            tile_dir = os.path.join(out_dir, str(zz), str(x))
            os.makedirs(tile_dir, exist_ok=True)
            path = os.path.join(tile_dir, f"{y}.mvt")
            with open(path, "wb") as fh:
                fh.write(data)
            written += 1
            total_written += 1
            print(f"[tile-gen]  wrote {zz}/{x}/{y}.mvt ({len(data)} bytes)")
        per_zoom[z] = written
        print(f"[zoom {z}] {written} tiles")

    print(f"[summary] tiles per zoom: {per_zoom}")
    print(f"[done] {total_written} tiles under {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
