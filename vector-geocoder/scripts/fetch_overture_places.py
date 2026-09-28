#!/usr/bin/env python3
"""Download the Overture Maps "places" theme for a bbox and emit basemap GeoJSON.

Why this exists
---------------
``vector-geocoder`` indexes whatever named features it is handed. Today it is
handed an OSM-derived basemap, and OSM POI density in Qatar is a small fraction
of what a user sees in Google Maps or Waze. The code was never the problem --
the index was nearly empty. Overture Maps Foundation publishes a places theme
(53M+ POIs, CDLA Permissive 2.0) that fills exactly that hole.

This is an OFFLINE, ONE-OFF script. It is deliberately NOT importable from
``vector_geocoder`` and is NOT on the package's dependency list: the runtime
package stays stdlib-only. Run it with ``uv`` so DuckDB is borrowed, not
installed::

    uv run --with duckdb python scripts/fetch_overture_places.py \
        --out ../vector-osrm/data/qatar_places.geojson

Idempotence / resumability
--------------------------
The expensive step (streaming ~30 GB of remote GeoParquet and filtering it down
to one country) is cached to a local Parquet file keyed by release + bbox. A
second run reuses that cache and only re-projects to GeoJSON, so re-tuning the
confidence floor costs nothing. ``--force`` re-downloads.

Output shape
------------
A GeoJSON FeatureCollection in EXACTLY the shape ``GeocodeService.from_geojson``
already indexes: Point geometry, ``properties.name``, ``properties.kind``. The
extra properties (``category``, ``brand``, ``confidence``, ``source``,
``alt_names``) are additive -- an indexer that ignores them still works.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

S3_BUCKET_URL = "https://overturemaps-us-west-2.s3.us-west-2.amazonaws.com"
S3_PREFIX = "s3://overturemaps-us-west-2/release"

# Qatar, generous margin. lon 50.75..51.65, lat 24.45..26.20.
DEFAULT_BBOX = (50.75, 24.45, 51.65, 26.20)

# Overture ships a per-record `confidence` in 0..1 (how sure the conflation is
# that this place exists and is correctly described). The floor here is a
# RECALL choice, not a precision one: the user complaint is "half the names
# don't come up", so a low floor that admits a slightly stale cafe beats a high
# floor that silently drops it. The script prints the full histogram so the
# number below can be re-argued against data rather than taste.
DEFAULT_CONFIDENCE = 0.2

# Languages we lift out of `names.common` as extra searchable aliases. Arabic
# first: the index already transliterates Arabic to Latin, so an Arabic alias
# makes a place findable by BOTH scripts.
ALIAS_LANGS = ("ar", "en")


def latest_release(explicit=None):
    """Newest release directory in the public bucket (or ``explicit``)."""
    if explicit:
        return explicit
    url = f"{S3_BUCKET_URL}/?list-type=2&delimiter=/&prefix=release/"
    with urllib.request.urlopen(url, timeout=60) as resp:
        body = resp.read().decode("utf-8")
    releases = re.findall(r"<Prefix>release/([^<]+?)/</Prefix>", body)
    if not releases:
        raise RuntimeError(f"no releases found at {url}")
    releases.sort()
    return releases[-1]


def _connect(duckdb):
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs;")
    con.execute("INSTALL spatial; LOAD spatial;")
    con.execute("SET s3_region='us-west-2';")
    # The bucket is public; without this DuckDB tries (and fails) to sign.
    con.execute("SET s3_access_key_id=''; SET s3_secret_access_key='';")
    con.execute("SET enable_progress_bar=true;")
    return con


def download_subset(con, release, bbox, cache_path):
    """Stream the remote places theme, keep rows inside ``bbox``, cache locally.

    The bbox predicate hits Overture's ``bbox`` struct column, which is
    row-group-indexed, so DuckDB skips almost every row group instead of
    downloading the whole theme.
    """
    minlon, minlat, maxlon, maxlat = bbox
    src = f"{S3_PREFIX}/{release}/theme=places/type=place/*.parquet"
    sql = f"""
    COPY (
      SELECT
        id,
        names.primary                       AS name,
        names.common                        AS name_common,
        categories.primary                  AS category,
        brand.names.primary                 AS brand,
        confidence,
        operating_status,
        addresses[1].freeform               AS addr_freeform,
        addresses[1].locality               AS addr_locality,
        ST_X(ST_Centroid(geometry))         AS lon,
        ST_Y(ST_Centroid(geometry))         AS lat
      FROM read_parquet('{src}')
      WHERE bbox.xmin >= {minlon} AND bbox.xmax <= {maxlon}
        AND bbox.ymin >= {minlat} AND bbox.ymax <= {maxlat}
    ) TO '{cache_path}' (FORMAT PARQUET)
    """
    con.execute(sql)


def _aliases(name_common, primary):
    """Alternate-language names worth indexing, minus the primary itself."""
    out = []
    if not name_common:
        return out
    for lang in ALIAS_LANGS:
        val = name_common.get(lang) if hasattr(name_common, "get") else None
        if isinstance(val, str) and val.strip() and val.strip() != (primary or "").strip():
            out.append(val.strip())
    return out


def build_features(rows, confidence_floor):
    """Project cached rows into indexable GeoJSON features + a stats dict."""
    stats = {
        "rows_in_bbox": len(rows),
        "dropped_no_name": 0,
        "dropped_confidence": 0,
        "dropped_closed": 0,
        "dropped_no_geometry": 0,
        "written": 0,
        "with_alias": 0,
        "confidence_histogram": {},
    }
    features = []
    for r in rows:
        conf = r.get("confidence")
        bucket = "none" if conf is None else f"{int(float(conf) * 10) / 10:.1f}"
        stats["confidence_histogram"][bucket] = \
            stats["confidence_histogram"].get(bucket, 0) + 1

        name = (r.get("name") or "").strip()
        if not name:
            stats["dropped_no_name"] += 1
            continue
        if (r.get("operating_status") or "").lower() == "closed":
            stats["dropped_closed"] += 1
            continue
        if conf is None or float(conf) < confidence_floor:
            stats["dropped_confidence"] += 1
            continue
        lon, lat = r.get("lon"), r.get("lat")
        if lon is None or lat is None:
            stats["dropped_no_geometry"] += 1
            continue

        props = {
            "name": name,
            # `kind` drives the indexer: anything that is not "road" is indexed
            # as a searchable place. "poi" matches what the OSM basemap uses.
            "kind": "poi",
            "source": "overture",
            "overture_id": r.get("id"),
            "confidence": round(float(conf), 4),
        }
        if r.get("category"):
            props["category"] = r["category"]
        if r.get("brand"):
            props["brand"] = r["brand"]
        if r.get("addr_freeform"):
            props["address"] = r["addr_freeform"]
        if r.get("addr_locality"):
            props["locality"] = r["addr_locality"]
        alias = _aliases(r.get("name_common"), name)
        if alias:
            props["alt_names"] = alias
            stats["with_alias"] += 1

        features.append({
            "type": "Feature",
            "geometry": {"type": "Point",
                         "coordinates": [round(float(lon), 7), round(float(lat), 7)]},
            "properties": props,
        })
    stats["written"] = len(features)
    return features, stats


def write_atomic(path, doc):
    """Write then rename, so a killed run never leaves a half-parsed index."""
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=directory, suffix=".partial")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, ensure_ascii=False)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--release", default=None,
                    help="Overture release (default: newest in the bucket)")
    ap.add_argument("--bbox", default=None,
                    help="minlon,minlat,maxlon,maxlat (default: Qatar)")
    ap.add_argument("--confidence", type=float, default=DEFAULT_CONFIDENCE,
                    help=f"drop places below this confidence (default {DEFAULT_CONFIDENCE})")
    ap.add_argument("--out", default="qatar_places.geojson",
                    help="output GeoJSON FeatureCollection path")
    ap.add_argument("--cache-dir", default=None,
                    help="where the raw Parquet subset is cached "
                         "(default: <out dir>/.overture-cache)")
    ap.add_argument("--force", action="store_true",
                    help="re-download even if the cache is present")
    args = ap.parse_args(argv)

    try:
        import duckdb
    except ImportError:
        print("error: duckdb is required. Run this script as:\n"
              "  uv run --with duckdb python scripts/fetch_overture_places.py ...",
              file=sys.stderr)
        return 2

    bbox = DEFAULT_BBOX
    if args.bbox:
        parts = [float(x) for x in args.bbox.split(",")]
        if len(parts) != 4:
            print("error: --bbox needs minlon,minlat,maxlon,maxlat", file=sys.stderr)
            return 2
        bbox = tuple(parts)

    release = latest_release(args.release)
    cache_dir = args.cache_dir or os.path.join(
        os.path.dirname(os.path.abspath(args.out)) or ".", ".overture-cache")
    os.makedirs(cache_dir, exist_ok=True)
    tag = f"{release}_{'_'.join(str(b) for b in bbox)}".replace("/", "-")
    cache_path = os.path.join(cache_dir, f"places_{tag}.parquet").replace("\\", "/")

    print(f"[overture] release      : {release}")
    print(f"[overture] bbox         : {bbox}")
    print(f"[overture] confidence>= : {args.confidence}")
    print(f"[overture] cache        : {cache_path}")

    con = _connect(duckdb)

    if args.force and os.path.exists(cache_path):
        os.unlink(cache_path)
    if os.path.exists(cache_path):
        print("[overture] cache hit -- skipping download (use --force to refetch)")
    else:
        print("[overture] downloading subset from S3 (this streams remote parquet)...")
        download_subset(con, release, bbox, cache_path)
        print(f"[overture] cached {os.path.getsize(cache_path):,} bytes")

    cur = con.execute(f"SELECT * FROM read_parquet('{cache_path}')")
    cols = [d[0] for d in cur.description]
    rows = [dict(zip(cols, t)) for t in cur.fetchall()]

    features, stats = build_features(rows, args.confidence)

    print(f"[overture] rows in bbox            : {stats['rows_in_bbox']:,}")
    print(f"[overture]   dropped: no name      : {stats['dropped_no_name']:,}")
    print(f"[overture]   dropped: closed       : {stats['dropped_closed']:,}")
    print(f"[overture]   dropped: confidence   : {stats['dropped_confidence']:,}")
    print(f"[overture]   dropped: no geometry  : {stats['dropped_no_geometry']:,}")
    print(f"[overture] features written        : {stats['written']:,}")
    print(f"[overture]   with alt-language name: {stats['with_alias']:,}")
    print("[overture] confidence histogram (all rows in bbox):")
    for k in sorted(stats["confidence_histogram"]):
        print(f"[overture]   {k}: {stats['confidence_histogram'][k]:,}")

    doc = {
        "type": "FeatureCollection",
        "properties": {
            "source": "overture",
            "release": release,
            "bbox": list(bbox),
            "confidence_floor": args.confidence,
            "license": "CDLA-Permissive-2.0",
            "counts": {k: v for k, v in stats.items()
                       if k != "confidence_histogram"},
        },
        "features": features,
    }
    write_atomic(args.out, doc)
    print(f"[overture] wrote {args.out} ({os.path.getsize(args.out):,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
