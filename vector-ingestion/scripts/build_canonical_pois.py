#!/usr/bin/env python3
"""Build the canonical Qatar POI dataset from the OSM basemap + Overture places.

This is the entrypoint bootstrap.sh step 2b calls. It replaces the previous
raw byte-splice merge with the V1 quality pipeline:

    OSM basemap GeoJSON + Overture places GeoJSON
        -> normalize -> classify -> score -> reconcile -> dedup -> filter
        -> canonical POIs + bake input + search file + audit report

Usage (standalone; stdlib only):

    python vector-ingestion/scripts/build_canonical_pois.py \
        --osm   "$WORK/qatar.geojson" \
        --places "$WORK/qatar_places.geojson" \
        --region qatar \
        --out    "$WORK"

Writes, under --out:

    canonical_pois.geojson        the canonical dataset, provenance retained
    <region>_places.geojson       canonical POIs in the geocoder's shape (ALL)
    bake-input/<region>.geojson   OSM basemap + MAP-VISIBLE canonical POIs
    search-input/<region>.geojson OSM basemap + ALL canonical POIs (geocoder)
    poi-audit.json                the audit report (real counts, not estimates)

MAP vs SEARCH (V1.2). Every canonical POI is searchable; only the map-visible
ones are drawn. OSM feeds the map; Overture Places feeds search/enrichment
only. The policy is one documented module, `vector_ingestion/poi/visibility.py`,
and the decision rides on each record as `map_visible`.

Exit codes: 0 success, 2 usage error, 1 pipeline error. A missing --places
file is allowed (OSM-only canonical set) and is reported.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_SRC = os.path.join(os.path.dirname(_HERE), "src")
for _p in (_SRC, os.path.join(os.path.dirname(os.path.dirname(_HERE)),
                              "vector-ingestion", "src")):
    if os.path.isdir(_p) and _p not in sys.path:
        sys.path.insert(0, _p)

from vector_ingestion.poi.audit import format_report  # noqa: E402
from vector_ingestion.poi.pipeline import run_pipeline  # noqa: E402


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Build the canonical POI dataset (V1 quality pipeline).")
    ap.add_argument("--osm", required=True,
                    help="OSM basemap GeoJSON (osm_to_geojson.py output)")
    ap.add_argument("--places", default=None,
                    help="Overture places GeoJSON (optional)")
    ap.add_argument("--region", default="qatar",
                    help="region name, used for output filenames")
    ap.add_argument("--out", default=".",
                    help="output directory (default: cwd)")
    ap.add_argument("--canonical", default=None,
                    help="explicit path for canonical_pois.geojson")
    ap.add_argument("--audit", default=None,
                    help="explicit path for poi-audit.json")
    ap.add_argument("--bake-input", default=None,
                    help="explicit path for the tile bake input "
                         "(MAP-VISIBLE POIs only)")
    ap.add_argument("--search-basemap", default=None,
                    help="explicit path for the geocoder basemap "
                         "(basemap + ALL canonical POIs)")
    ap.add_argument("--search-places", default=None,
                    help="explicit path for the geocoder places file")
    ap.add_argument("--excluded", default=None,
                    help="explicit path for the excluded records (audit/debug)")
    ap.add_argument("--quiet", action="store_true",
                    help="do not print the human audit report")
    args = ap.parse_args(argv)

    if not os.path.exists(args.osm):
        print(f"error: --osm does not exist: {args.osm}", file=sys.stderr)
        return 2
    if args.places and not os.path.exists(args.places):
        print(f"[poi] warning: --places missing ({args.places}); OSM-only "
              f"canonical set", file=sys.stderr)

    out = os.path.abspath(args.out)
    canonical = args.canonical or os.path.join(out, "canonical_pois.geojson")
    audit = args.audit or os.path.join(out, "poi-audit.json")
    bake = args.bake_input or os.path.join(
        out, "bake-input", f"{args.region}.geojson")
    search = args.search_places or os.path.join(
        out, f"{args.region}_places.geojson")
    search_basemap = args.search_basemap or os.path.join(
        out, "search-input", f"{args.region}.geojson")

    result = run_pipeline(
        osm_path=args.osm,
        places_path=args.places,
        out_canonical=canonical,
        out_audit=audit,
        out_bake_input=bake,
        out_places=search,
        out_excluded=args.excluded,
        out_search_basemap=search_basemap,
    )
    if not args.quiet:
        print(format_report(result))
    print(f"[poi] canonical -> {canonical}", file=sys.stderr)
    print(f"[poi] geocoder  -> {search}", file=sys.stderr)
    print(f"[poi] bake in   -> {bake}   (map-visible POIs)", file=sys.stderr)
    print(f"[poi] search in -> {search_basemap}   (all canonical POIs)",
          file=sys.stderr)
    print(f"[poi] audit     -> {audit}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
