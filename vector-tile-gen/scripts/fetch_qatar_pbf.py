#!/usr/bin/env python3
"""Build Vector's OSM extract from a DATED Geofabrik PBF instead of Overpass.

    uv run --with osmium python scripts/fetch_qatar_pbf.py \
        --release 260912 -o .bootstrap-cache/qatar.osm

Emits a `.osm` XML file in exactly the shape `osm_to_geojson.py` already reads.
Nothing downstream changes: same converter, same GeoJSON schema, same tile
generator, same styles.

## Why this exists

`bootstrap.sh` acquires the basemap from the public Overpass mirrors, and the
mirrors do not agree on what year it is. Measured 2026-09-13, one identical
count query, three mirrors, minutes apart:

    overpass-api.de           osm_base 2026-09-13   (fresh)
    overpass.private.coffee   osm_base 2026-07-15
    overpass.kumi.systems     osm_base 2026-05-31   <- tried FIRST

`kumi.systems` is the first fallback in `OVERPASS_ENDPOINTS`, so a default
bootstrap most likely bakes data that is three and a half months old. Worse, it
served 2026-05-31 on one request and 2026-07-15 twenty minutes later: the
mirrors are load-balanced clusters whose backends differ, so pinning a HOST
does not pin a SNAPSHOT and no retry policy can.

It is also not one vintage per bake. `bootstrap.sh` makes five separate
requests (main extract, restrictions, coastline, POI areas, POI nodes) and only
the main one honours `LAST_EP`; the other four restart from the top of the
mirror list every time. One `qatar.geojson` can therefore contain roads from
one month and shops from another, and the `OSM_VINTAGES` check that exists to
catch exactly this runs BEFORE the four supplementary parts are appended, so it
never sees them.

A dated PBF has one timestamp for every byte in it, by construction.

## Why Geofabrik gcc-states

There is no standalone Qatar extract: `asia/qatar-latest.osm.pbf` is a
9,609-byte HTML error page (verified). Qatar lives inside `asia/gcc-states`,
252,854,870 bytes as of the 260912 release.

## Why --release, and why the file must be ARCHIVED

Geofabrik keeps only SEVEN days of dated snapshots: verified 2026-09-13,
`gcc-states-260906` through `-260912` return 200 and `-260905` is a 404. So a
dated URL is not a durable pin — it is a pin that expires in a week. To make a
bake reproducible months later the downloaded file must be kept, which is why
this script caches it and verifies the published `.md5` on every run rather
than re-fetching. Record the release date and the md5 next to the tile epoch
and the bake is reproducible for as long as the file is kept.

## What it selects

Exactly the union of what `bootstrap.sh` asks Overpass for today — steps 1, 1b,
1c, 1d and 1e — plus, since V7.6, every `building` way rather than only the
named and height-bearing ones. A PBF contains every object in six countries and
this script is not a licence to widen the diet; the buildings are the one
deliberate widening, and `want_way` carries the measurement that justifies it.
An unnamed footprint is not a place and never becomes one — `_attrs` still
drops it as a POI (`test_an_unnamed_building_is_still_not_a_place`). It becomes
a POLYGON, which is a different thing the converter emits on a different
branch.

## Measured on the 260912 release

    download            252,854,870 bytes, md5 verified, 17 s
    extract             2,970,581 nodes in bbox -> 1,590,825 nodes,
                        199,529 ways, 3,898 restrictions, 395 s, qatar.osm 263 MB
    convert (existing)  208,706 features, 86 MB, 28 s
    bake z14+z15        3,045 + 13,195 tiles, 18 + 65 MB, 120 s

Not importable from the package and not in `pyproject.toml`: it borrows osmium
via `uv run --with osmium`, so `src/` stays stdlib-only. Same arrangement as
`vector-geocoder/scripts/fetch_overture_places.py`.
"""

import argparse
import datetime
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE_URL = "https://download.geofabrik.de/asia"
EXTRACT = "gcc-states"

# lon_min, lat_min, lon_max, lat_max — the same box as $VECTOR_BBOX
# (.env.example: 24.4,50.7,26.2,51.8, which is lat,lon ordered).
DEFAULT_BBOX = (50.7, 24.4, 51.8, 26.2)

# The POI tags the converter recognises, split the way bootstrap.sh splits them.
# `osm_to_geojson.POI_KEYS` lists thirteen; these are the ones actually asked
# for today. Widening this is a data-volume decision, not a free one — see the
# module docstring.
WAY_POI = ("amenity", "shop", "tourism", "office", "healthcare", "craft", "historic")
NODE_POI = ("amenity", "shop", "tourism", "place", "aeroway",
            "office", "healthcare", "craft", "historic")


def want_way(t):
    """Step 1 (highways, water, park, landuse), 1c (coastline), 1d (POI areas)."""
    if "highway" in t:
        return True
    if t.get("natural") in ("water", "coastline"):
        return True
    if t.get("leisure") == "park":
        return True
    if "landuse" in t:
        return True
    if any(k in t for k in WAY_POI):
        return True
    if "building" not in t:
        return False
    # V7.6: EVERY footprint, named or not, height or not.
    #
    # This rule has now been widened twice, and the reason is the same each
    # time: it was written for a narrower consumer than the one that exists.
    #
    #   * originally "named only" — a building could only become a POI point,
    #     so an unnamed one had no use;
    #   * then "named OR height" (V7 3D) — an unnamed tower is still a tower,
    #     and two thirds of the extrude-able footprints were named-filtered
    #     out before any 3D consumer could see them;
    #   * now "every building", because the consumer is no longer extrusion.
    #     It is the CITY FABRIC: the flat `buildings` fill layer, which has
    #     existed in the style since V3 and has never in its life been handed
    #     a feature to draw.
    #
    # Measured on this exact PBF (gcc-states 260912, Vector's own bbox,
    # `scripts/census_buildings.py` in the V7.6 evidence):
    #
    #     building ways in bbox            189,866
    #     with a source-stated `height`        975   (0.51%)
    #     with `building:levels` only        6,499   (3.4%)
    #     with neither                     182,392   (96.1%)
    #     kept by the PREVIOUS rule          2,752   (1.4%)
    #
    # So the old rule discarded 98.6% of Qatar's buildings at the extract, and
    # that is why the S24 run photographed Msheireb — one of the densest
    # districts in the country — as empty ground with roads on it.
    #
    # The previous comment justified the narrow rule with "96% state no
    # height, cannot be extruded, and would enter every tile's feature budget
    # to draw nothing." The first half is still true and the second half is
    # not: a footprint with no height draws the BLOCK, which is the thing that
    # makes a city read as a city. The budget worry is answered where budget
    # is actually spent — `build_qatar_tiles._KIND_ORDER` ranks `building`
    # last and gives it no reserved floor, so footprints consume only
    # leftover space and a building can never displace a road. That is
    # measured, not asserted: see `tests/test_building_fabric.py`.
    return True


def want_node(t):
    """Step 1 (amenity/shop/tourism/place/aeroway) and 1e (the rest).

    Plus ``highway=traffic_signals`` nodes (V7 Stage 5) and
    ``highway=speed_camera`` nodes (V7.3). A signal/camera node that is also
    a way vertex is retained for geometry anyway, but one that stands
alone (a crossing signal on a way that was filtered out, a camera at a
junction of low-traffic ways) is only kept when it is a recognised POI --
and before these lines it never was. The SIGNAL/CAMERA is the location
evidence for the warning features, so one dropped here is one no client can
ever know about, at no memory cost to the extract.
    """
    return any(k in t for k in NODE_POI) or t.get("highway") in (
        "traffic_signals", "speed_camera")


def want_relation(t):
    """Step 1b. Turn restrictions are the only relation the converter reads."""
    return t.get("type") == "restriction"


def resolve_release(release):
    """Turn ``latest`` into the DATED name the server is currently serving.

    `-latest` is a redirect, not a file: requesting it gives whatever is newest
    at that instant and records nothing, which is precisely the Overpass
    problem in a different coat. Following the redirect ourselves turns it into
    a name — measured 2026-09-13, `gcc-states-latest.osm.pbf` 302s to
    `gcc-states-260912.osm.pbf` — so even a caller who did not pin gets a build
    that can say exactly which snapshot it used.
    """
    if release != "latest":
        return release

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise urllib.error.HTTPError(req.full_url, code, newurl, headers, fp)

    opener = urllib.request.build_opener(_NoRedirect)
    try:
        opener.open(f"{BASE_URL}/{EXTRACT}-latest.osm.pbf", timeout=60)
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            name = str(e.reason).rsplit("/", 1)[-1]
            got = name[len(EXTRACT) + 1:].split(".")[0]
            print(f"[pbf] latest resolves to release {got}", flush=True)
            return got
        raise
    raise SystemExit("[pbf] -latest did not redirect; pass --release explicitly")


def write_provenance(path, record):
    """Record WHICH SNAPSHOT produced this extract, next to the extract.

    A filename is not provenance: `qatar.osm` says nothing, and the release
    date alone does not survive the file being copied. This sidecar travels
    with the data and answers "exactly which OSM snapshot produced these
    tiles?" without a lookup.
    """
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(record, fh, indent=2, sort_keys=True)
        fh.write("\n")
    print(f"[pbf] provenance -> {path}", flush=True)


def download(release, cache_dir):
    """Fetch the dated PBF and its md5, verifying an existing copy rather than
    re-downloading. Returns the local path."""
    name = f"{EXTRACT}-{release}.osm.pbf"
    path = os.path.join(cache_dir, name)
    os.makedirs(cache_dir, exist_ok=True)

    with urllib.request.urlopen(f"{BASE_URL}/{name}.md5", timeout=60) as r:
        expected = r.read().decode().split()[0]

    if os.path.isfile(path) and _md5(path) == expected:
        print(f"[pbf] cached and verified: {path}", flush=True)
        return path

    print(f"[pbf] downloading {name} ...", flush=True)
    t0 = time.time()
    tmp = path + ".part"
    urllib.request.urlretrieve(f"{BASE_URL}/{name}", tmp)
    got = _md5(tmp)
    if got != expected:
        os.unlink(tmp)
        raise SystemExit(f"[pbf] md5 mismatch: expected {expected}, got {got}")
    os.replace(tmp, path)
    size = os.path.getsize(path)
    print(f"[pbf] {size} bytes, md5 {got} verified, {time.time() - t0:.0f}s", flush=True)
    return path


def _md5(path):
    h = hashlib.md5()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract(src, dst, bbox):
    """PBF -> .osm XML, keeping only what bootstrap.sh asks Overpass for.

    Four passes, because a PBF is ordered nodes-then-ways-then-relations and
    each stage needs the previous stage's answer:

      1. which node ids are inside the bbox, and which of those are POI nodes
      2. which ways we keep (a way is in Qatar if ANY of its nodes is) and
         every node those ways reference — including nodes outside the box, or
         a road would be truncated at the border
      3. nodes referenced by ways pulled in as relation members, which are only
         known after the relations have been read
      4. write

    Streaming throughout: the only things held in memory are id sets.
    """
    import osmium

    lon0, lat0, lon1, lat1 = bbox
    t0 = time.time()

    class Scan(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.inbox = set()
            self.poi = set()

        def node(self, n):
            if not n.location.valid():
                return
            lon, lat = n.location.lon, n.location.lat
            if lon0 <= lon <= lon1 and lat0 <= lat <= lat1:
                self.inbox.add(n.id)
                if want_node(dict(n.tags)):
                    self.poi.add(n.id)

    scan = Scan()
    scan.apply_file(src)
    print(f"[extract] {len(scan.inbox)} nodes in bbox, {len(scan.poi)} POI nodes "
          f"({time.time() - t0:.0f}s)", flush=True)

    class Pick(osmium.SimpleHandler):
        def __init__(self, inbox):
            super().__init__()
            self.inbox = inbox
            self.ways, self.rels, self.need, self.relways = set(), set(), set(), set()

        def way(self, w):
            if not want_way(dict(w.tags)):
                return
            refs = [n.ref for n in w.nodes]
            if not any(r in self.inbox for r in refs):
                return
            self.ways.add(w.id)
            self.need.update(refs)

        def relation(self, r):
            if not want_relation(dict(r.tags)):
                return
            mw = [m.ref for m in r.members if m.type == "w"]
            mn = [m.ref for m in r.members if m.type == "n"]
            if not (any(m in self.ways for m in mw) or any(m in self.inbox for m in mn)):
                return
            self.rels.add(r.id)
            self.relways.update(mw)
            self.need.update(mn)

    pick = Pick(scan.inbox)
    pick.apply_file(src)
    keep_ways = pick.ways | pick.relways
    print(f"[extract] {len(pick.ways)} ways "
          f"(+{len(pick.relways - pick.ways)} as relation members), "
          f"{len(pick.rels)} restrictions ({time.time() - t0:.0f}s)", flush=True)

    class Fill(osmium.SimpleHandler):
        """Nodes of ways kept only because a restriction references them."""

        def __init__(self, keep):
            super().__init__()
            self.keep = keep
            self.extra = set()

        def way(self, w):
            if w.id in self.keep:
                self.extra.update(n.ref for n in w.nodes)

    fill = Fill(keep_ways)
    fill.apply_file(src)
    need_nodes = pick.need | fill.extra | scan.poi

    class Write(osmium.SimpleHandler):
        def __init__(self, writer):
            super().__init__()
            self.w = writer
            self.n = self.wy = self.r = 0

        def node(self, n):
            if n.id in need_nodes:
                self.w.add_node(n)
                self.n += 1

        def way(self, w):
            if w.id in keep_ways:
                self.w.add_way(w)
                self.wy += 1

        def relation(self, r):
            if r.id in pick.rels:
                self.w.add_relation(r)
                self.r += 1

    if os.path.exists(dst):
        os.unlink(dst)
    writer = osmium.SimpleWriter(dst)
    out = Write(writer)
    out.apply_file(src)
    writer.close()
    print(f"[extract] wrote {out.n} nodes, {out.wy} ways, {out.r} relations -> {dst} "
          f"({time.time() - t0:.0f}s)", flush=True)
    return {"nodes": out.n, "ways": out.wy, "relations": out.r,
            "seconds": round(time.time() - t0, 1)}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--release", default="latest",
                    help="Geofabrik dated release, e.g. 260912. 'latest' (the "
                         "default) is RESOLVED to the dated name before "
                         "downloading and recorded in the provenance, so an "
                         "unpinned run is still reproducible after the fact.")
    ap.add_argument("--pbf", default=None,
                    help="use this local PBF instead of downloading. Geofabrik "
                         "keeps only seven days of dated releases, so an "
                         "archived copy is the only durable pin; its md5 is "
                         "recorded but not checked against the server.")
    ap.add_argument("-o", "--out", required=True, help="destination .osm path")
    ap.add_argument("--cache", default=".bootstrap-cache",
                    help="where the PBF is kept (default .bootstrap-cache)")
    ap.add_argument("--bbox", default=None,
                    help="lon_min,lat_min,lon_max,lat_max (default: Qatar)")
    args = ap.parse_args(argv)

    bbox = DEFAULT_BBOX
    if args.bbox:
        bbox = tuple(float(v) for v in args.bbox.split(","))
        if len(bbox) != 4:
            raise SystemExit("--bbox needs four comma-separated numbers")

    try:
        import osmium  # noqa: F401
    except ImportError:
        raise SystemExit(
            "osmium is not installed. This script borrows it rather than adding "
            "it to the package:\n"
            "    uv run --with osmium python scripts/fetch_qatar_pbf.py ...")

    if args.pbf:
        pbf = args.pbf
        if not os.path.isfile(pbf):
            raise SystemExit(f"[pbf] no such file: {pbf}")
        release = args.release if args.release != "latest" else "archived"
        print(f"[pbf] using archived {pbf}", flush=True)
    else:
        release = resolve_release(args.release)
        pbf = download(release, args.cache)

    counts = extract(pbf, args.out, bbox)

    write_provenance(args.out + ".provenance.json", {
        "source": "geofabrik",
        "extract": EXTRACT,
        "release": release,
        "url": None if args.pbf else f"{BASE_URL}/{EXTRACT}-{release}.osm.pbf",
        "pbf_file": os.path.basename(pbf),
        "pbf_bytes": os.path.getsize(pbf),
        "pbf_md5": _md5(pbf),
        "bbox": list(bbox),
        "extracted_at": datetime.datetime.now(datetime.timezone.utc)
                                 .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "counts": counts,
        "tool": os.path.basename(__file__),
    })
    print(f"[pbf] release {release} -> {args.out}; "
          f"feed it to osm_to_geojson.py unchanged.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
