#!/usr/bin/env python3
"""Fast Qatar/Doha MVT tile builder — one-pass feature binning.

The stock build_m1_tiles.py rescans ALL features for EVERY tile
(O(features * tiles)), which is impractical for a 100k-feature Doha extract.
This builder inverts the loop: it computes the tile set each feature touches
once, bins features into those tiles, then encodes each tile from its own small
feature bucket. Same MVT output, orders of magnitude faster.

Usage:
  python build_qatar_tiles.py --geojson <qatar.geojson> --out <dir> --zooms 11,12,13
Writes <out>/tiles/{z}/{x}/{y}.mvt

Learned geometry (issue 08): pass ``--learned-facts <learned_geometry.json>`` to
merge promoted ``road_candidate``/``geometry_correction`` facts into the bake.
The OSM source is never modified — learned features are a separate overlay that
is merged here, so provenance stays recoverable and dropping the flag is a full
rollback. Every learned feature carries ``learned=true`` for the style to paint
differently.

The bake always bumps the tile epoch on completion so clients refetch rather
than serving the previous bake from cache (see ``tile_version``).
"""
import argparse
import hashlib
import json
import os
import sys
import time

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
    _src = os.path.join(os.path.dirname(_ROOT), _repo, "src")
    if os.path.isdir(_src) and _src not in sys.path:
        sys.path.insert(0, _src)
# also allow running from workspace root layout
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
    _src = os.path.join(_ROOT, "..", _repo, "src")
    _src = os.path.normpath(_src)
    if os.path.isdir(_src) and _src not in sys.path:
        sys.path.insert(0, _src)

from vector_ingestion.geojson import load_geojson  # noqa: E402
from vector_tile_gen.encode import clip_box, clip_geometry, encode_tile, tile_bbox  # noqa: E402
from vector_tile_gen.learned_layer import (  # noqa: E402
    features_from_facts,
    verify_tile_bytes,
    write_learned_geojson,
)
from vector_tile_gen.pipeline import _as_feature_dict  # noqa: E402
from vector_tile_gen.simplify import simplify_geometry  # noqa: E402
from vector_tile_gen.tile_version import bump_version  # noqa: E402
from vector_tile_gen.tiles import lonlat_to_tile  # noqa: E402


def roads_kept_after_clip(fds, z, x, y):
    """Ids of the road features that survive this tile's clip.

    Lane markings are only written for these: a road that ``encode_tile`` will
    drop for not reaching the tile + buffer must not leave its paint behind.
    """
    box = clip_box(tile_bbox(z, x, y))
    return {fd["id"] for fd in fds
            if (fd["properties"] or {}).get("kind") == "road"
            and clip_geometry(fd["geometry_type"], fd["coordinates"], box) is not None}


def tiles_for_feature(f, zoom):
    if not f.bbox:
        return []
    min_lon, min_lat, max_lon, max_lat = f.bbox
    x0, y0 = lonlat_to_tile(zoom, min_lon, max_lat)
    x1, y1 = lonlat_to_tile(zoom, max_lon, min_lat)
    out = []
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            out.append((x, y))
    return out


# Truncation priority (see the sorted(...) call in main): when a tile holds
# more than --max-per-tile features, keep roads — especially major classes —
# then water/parks/labels, and drop the low-priority tail first. Dataset-order
# truncation cut mid-network road segments and produced visible line breaks
# at tile boundaries in dense areas.
_MAJOR_HW = {"motorway", "trunk", "primary", "secondary"}
# `coastline` ranks with water because it IS the water edge. Without an entry
# here it fell to the default 6 — the LOWEST priority — so in any tile over
# budget the shoreline was the first thing truncated away, which is exactly
# backwards: on a peninsula it is the most important non-road feature on the
# map, and it is what gives the country a shape at all.
#
# `building: 9` is the LOWEST rank, below even the default (6) that unnamed
# POIs take, and that is the V7 3D decision. Buildings are the one kind that is
# pure decoration: nothing routes to a footprint, nothing reads one, and a tile
# with no buildings in it is still a usable map. Roads, water, parks, labels and
# POIs are all things a driver acts on. So when a tile is over budget — central
# Doha at z14 and z15 always is — buildings yield first, and a building never
# removes a road from the map.
#
# Measured, because the first attempt got this wrong: with buildings at rank 5
# AND a 15% reserved floor (both inherited from a layer that had never rendered
# a single feature, since the bake emitted no `kind=building` at all), adding
# them to 51 over-budget z14 tiles cost **265 road features, 221 barriers and 26
# parks**. A reserved floor is the wrong instrument for a decorative kind: it
# does not fill leftover space, it TAKES space in pass 1 from exactly the kinds
# §8 of this stage's brief says must stay readable.
_KIND_ORDER = {"water": 1, "coastline": 1, "park": 2, "natural": 2, "landuse": 3,
               "label": 4, "building": 9}


def _bbox_area(f):
    """Rough extent of a feature, in square degrees. Zero for points/lines."""
    if not f.bbox:
        return 0.0
    min_lon, min_lat, max_lon, max_lat = f.bbox
    return abs(max_lon - min_lon) * abs(max_lat - min_lat)


def _tiebreak_key(f):
    """Something intrinsic to a feature, for tie-breaking.

    Not the `Feature.id`: `vector_ingestion.load_geojson` falls back to
    ``f"feature-{i}"`` — the array index — for any feature without one, and
    Overture's places carry no top-level `id`. Keying on that would be keying
    on file position again, one level down.
    """
    p = f.properties or {}
    oid = p.get("overture_id")
    if oid:
        return f"overture:{oid}"
    fid = getattr(f, "id", "") or ""
    if fid and not fid.startswith("feature-"):
        return f"osm:{fid}"
    name = p.get("name") or p.get("name:en") or ""
    bbox = f.bbox or (0.0, 0.0, 0.0, 0.0)
    return f"geo:{name}@{bbox[0]:.7f},{bbox[1]:.7f}"


def _stable_tiebreak(f):
    """A deterministic, source-neutral number in [0, 1) for one feature.

    ## Why this exists

    `select_for_tile` sorts by `feature_rank` and Python's sort is stable, so
    whenever the rank tuple ties the SOURCE FILE ORDER decides what survives
    truncation — and only in a tile that is over the cap, since an untruncated
    tile ships everything and never consults the tie.

    The Overture places layer is appended after the OSM basemap, so where the
    two interleave badly Overture lost. It is not uniform, which is what made
    it hard to see: across the ten truncated z14 tiles over central Doha the
    Overture share of shipped POIs ranged from 82% down to **0%**. Tile
    14/10537/7002 was the floor of that range — 1,831 Overture and 1,189 OSM
    POIs competed for 525 slots and it shipped 525 OSM and no Overture at all,
    which is why the restaurant a driver was navigating to was searchable and
    absent from the map. With this tie-break the same ten tiles go from 59% to
    76% Overture overall, and 14/10537/7002 from 0% to 69%.

    ## Why a hash, and why not `hash()`

    When two features are genuinely indistinguishable on every signal that
    matters, the honest thing is to pick between them in a way that knows
    nothing about where they came from. A hash of the feature's own identity
    does that: uniform, so each source is represented in proportion to how
    many candidates it has, and carrying no information about source or
    position.

    It must also be STABLE — the same tile must select the same POIs on every
    bake, or a re-bake silently reshuffles which shops a driver can see.
    Python's built-in `hash()` is seeded per process for `str` (PYTHONHASHSEED),
    so it is exactly the wrong tool: deterministic within one run and different
    on the next. `blake2b` is neither seeded nor version-dependent.
    """
    digest = hashlib.blake2b(_tiebreak_key(f).encode("utf-8"),
                             digest_size=8).digest()
    return int.from_bytes(digest, "big") / float(1 << 64)


def feature_rank(f):
    p = f.properties or {}
    kind = p.get("kind") or ""
    if kind == "road":
        hw = (p.get("highway") or "").lower()
        # Uniform 3-tuple: mixing tuple lengths here works only because road
        # and non-road first elements never collide, which is a fact about
        # _KIND_ORDER rather than something this function guarantees.
        return (0, 0, 0.0) if hw in _MAJOR_HW else (0, 1, 0.0)
    if kind == "poi":
        # POIs had no order at all: every one returned (6, 0, 0.0), so a tile
        # over budget kept whichever came first in the source file.
        #
        # Two signals of whether a driver can USE the feature, then a
        # tie-break that is neither of those things:
        #
        #  * a name. An unnamed POI renders as an icon with nothing to read,
        #    which is no help to someone looking for a specific shopfront.
        #  * a category a person would search for. `poi_class`/`category` is
        #    "restaurant" or "fuel" or "pharmacy" for a destination and "yes"
        #    for the 860 features where `shop=yes` is all OSM knows.
        #
        # Both signals are coarse, so in a dense tile thousands of POIs land in
        # the SAME bucket and the tie decides what renders. See
        # `_stable_tiebreak` for why that tie may not be file order.
        p_name = p.get("name") or p.get("name:en")
        cat = (p.get("poi_class") or p.get("category") or "").lower()
        return (_KIND_ORDER.get(kind, 6),
                (0 if p_name else 2) + (0 if (cat and cat != "yes") else 1),
                _stable_tiebreak(f))
    if kind == "building":
        # V7.6. Inside the building kind, a MEASURED building outranks fabric.
        #
        # This element did not exist while `height_m` was the entry condition
        # for being a building at all — every building in a tile was a volume,
        # so there was nothing to order. V7.6 carries all 189,866 footprints
        # and that silently created a regression this guards against: a tile
        # over budget sheds buildings by footprint AREA (the third element
        # below), and Doha's towers are slim. Al Bidda Tower's footprint is
        # smaller than a warehouse's, so an over-budget West Bay tile would
        # have dropped the 215 m tower and kept the shed — removing 3D that
        # works in production today in exactly the district it works best.
        #
        # A measured building is strictly more valuable than a fabric one:
        # it carries everything the fabric one does (a block, a footprint)
        # plus the only height anybody surveyed. So it is never the one to
        # yield. Within each of the two groups, largest-first still applies.
        #
        # This does NOT give buildings budget they did not have — the kind is
        # still rank 9 with no floor (`_KIND_ORDER`, `_KIND_FLOOR`), so this
        # only decides WHICH buildings fill the leftover space, never HOW MANY.
        return (_KIND_ORDER["building"],
                0 if "height_m" in p else 1,
                -_bbox_area(f))

    # Third element: polygons sort LARGEST first inside their kind.
    #
    # Without this, a tile over its budget kept whichever parks happened to
    # come first in the source file. Qatar has 10,612 park polygons and most of
    # them are traffic islands and verges; at low zoom they encode to nothing
    # (the tile's own integer resolution collapses them — see
    # vector_tile_gen.simplify) while still consuming the park floor that Al Bidda
    # and Aspire needed. Biggest-first means the features a truncated tile
    # keeps are the ones a viewer at that zoom could actually see.
    return (_KIND_ORDER.get(kind, 6), 0, -_bbox_area(f))


# ---------------------------------------------------------------------------
# Zoom-appropriate road classes.
#
# Rendering every service road and driveway at z11 is both a quality problem
# (the city reads as an undifferentiated hairball, nothing like a real map) and
# the reason the per-tile budget was exhausted by roads alone. Real basemaps
# introduce road classes progressively; so do we.
# ---------------------------------------------------------------------------
_HW_TIERS = (
    # (minimum zoom at which this class appears, {classes})
    #
    # LINK roads are tiered separately from the mainlines they serve, and that
    # separation is what makes a country-scale tile possible at all. A slip road
    # is a few hundred metres long and exists to join two roads that are both
    # already drawn; at z6 it is smaller than one tile pixel. Qatar has 914
    # motorway and 1,587 trunk mainline ways against 1,920 motorway_link and
    # 1,530 trunk_link — so tiering the links with their parents meant 58% of a
    # z6 tile's road budget went to geometry nobody can see, and with a
    # 1,500-feature cap that pushed most of the actual motorway network out of
    # the tile.
    (0,  {"motorway", "trunk"}),
    (9,  {"primary"}),
    (11, {"motorway_link", "trunk_link", "primary_link"}),
    (12, {"secondary", "secondary_link"}),
    (13, {"tertiary", "tertiary_link"}),
    (14, {"residential", "unclassified", "living_street"}),
    (15, {"service", "track", "pedestrian", "footway", "path", "cycleway",
          "steps", "bridleway"}),
)

# ---------------------------------------------------------------------------
# Place-label tiers.
#
# The extract carries `place=` on all 685 labels — 1 country, 9 state, 3 city,
# 20 town, 87 suburb, 320 locality, 115 hamlet, 103 village — and nothing read
# it, so every label was emitted from z10 with a hamlet ranked exactly as
# importantly as Doha. At country scale that is unreadable, and MapLibre's
# symbol collision resolves it by dropping labels ARBITRARILY: which town
# survives is an accident of feature order.
#
# Ranking them is also what lets the country view carry labels at all. Without
# a rank the only safe choice is to show none.
# ---------------------------------------------------------------------------
_PLACE_TIERS = (
    (4,  {"country"}),
    (6,  {"state", "region", "province", "island", "archipelago"}),
    (7,  {"city"}),
    (9,  {"town"}),
    (11, {"suburb", "borough", "district", "quarter", "neighbourhood",
          "village", "square"}),
    (12, {"hamlet", "locality", "isolated_dwelling", "farm", "allotments"}),
)


def place_visible_at(place, z):
    """Should this place label be baked into a tile at zoom `z`?"""
    pl = (place or "").lower()
    for minz, kinds in _PLACE_TIERS:
        if pl in kinds:
            return z >= minz
    # No `place` tag, or one we do not rank: treat it as local detail rather
    # than promoting it to the country view.
    return z >= 12


def road_visible_at(highway, z):
    """Should this road class be baked into a tile at zoom `z`?"""
    hw = (highway or "").lower()
    for minz, classes in _HW_TIERS:
        if hw in classes:
            return z >= minz
    # Unknown class: treat as a minor road rather than dropping it outright.
    return z >= 14


def visible_at_zoom(f, z):
    p = f.properties or {}
    kind = p.get("kind") or ""
    # Learned geometry is never zoom-filtered. A promoted road is the entire
    # output of the evolution loop, and it is emitted by learned_layer.py with
    # `learned=true` and often WITHOUT a `highway` tag — which the unknown-class
    # default below would treat as a service road and hide under z14. That would
    # silently discard the one thing the loop exists to produce.
    if p.get("learned"):
        return True
    if kind == "road":
        return road_visible_at(p.get("highway"), z)
    if kind == "building":
        return z >= 14          # buildings are noise below street level
    if kind == "poi":
        return z >= 14
    if kind == "label":
        return place_visible_at(p.get("place"), z)
    return True                  # water / park / landuse / natural: always


# Guaranteed share of a tile's budget per kind. Ranked truncation alone
# (Session 50) fixed broken road lines and created a new defect: roads sort
# first, so in a dense central-Doha tile they consumed the entire 1500-feature
# budget and water, parks and labels were cut to ZERO. A decoded z12 tile over
# Doha held 1500 features, every one of them a road, while the source GeoJSON
# had 893 water and 10,612 park features. The map rendered as roads on a void.
#
# Floors, not fixed allocations: a kind that has fewer features than its floor
# gives the remainder back, and roads absorb whatever nobody else claims.
_KIND_FLOOR = {
    "coastline": 0.06,
    "water": 0.10,
    "park": 0.10,
    "natural": 0.05,
    "landuse": 0.08,
    "label": 0.04,
    "poi": 0.06,
    # "building" is deliberately ABSENT (V7 3D). See `_KIND_ORDER`: a reserved
    # floor for a decorative kind takes budget in pass 1 from roads and parks
    # rather than filling what is left, which is the opposite of what a floor is
    # for. Buildings fill leftover space in pass 2 and nothing else.
}


# POIs are the exception to a fixed floor, because their value is not fixed
# across zooms — it is nearly all concentrated at the zooms a driver actually
# navigates at.
#
# `kind_visible_at` admits POIs only from z14, and at z14 the 6% floor is 90
# features. Central Doha has far more than that: tile 14/10537/7002 holds 990
# named POIs in the source and shipped 82 of them. Everything else was cut,
# because POIs rank last (`_KIND_ORDER` defaults them to 6) and pass 2 fills
# in rank order, so a dense tile gives them exactly their floor and nothing
# more. A driver looking at the street their destination is on saw one shop in
# twelve — and "Green Tea Garden Restaurant is right in front of my
# destination and not on the map" is what that looks like from the car.
#
# This REDISTRIBUTES the tile's existing budget rather than enlarging it. The
# obvious alternative — a bigger cap at z14 — is wrong: `cap_for_zoom` is
# deliberately non-increasing with zoom because a feature's weight rises with
# zoom (a motorway is 6 vertices at z6 and 400 at z14), and `ZoomCapTest` pins
# that. What this does instead is spend a bigger share of the same budget on
# the cheapest features in the tile — a POI is a Point, one coordinate pair
# and a name, with no geometry to simplify and no vertices to thin — at the
# expense of the tail of minor roads that pass 2 would otherwise absorb. At
# z14 the route line is drawn from the route geometry, not from the basemap,
# so that tail is the least costly thing in the tile to give up.
#
# One value for every street zoom, deliberately. The temptation at z15/z16 is
# to raise it further, but a tile covers a QUARTER of the area at each step up
# while the cap stays flat — so the same 35% already buys four times the POI
# coverage per step, without spending another byte of anyone's budget. Raise
# this only if a measured tile is still truncating POIs at z16, never to make
# one particular feature appear.
_POI_FLOOR_BY_ZOOM = {14: 0.35, 15: 0.35, 16: 0.35}


def kind_floors(z):
    """Guaranteed budget shares for one zoom."""
    floors = dict(_KIND_FLOOR)
    poi = _POI_FLOOR_BY_ZOOM.get(z)
    if poi is not None:
        floors["poi"] = poi
    return floors


# ---------------------------------------------------------------------------
# The per-tile feature cap, by zoom.
#
# The cap exists to bound tile WEIGHT, and a feature's weight is not constant
# across zooms: after `simplify_geometry` a motorway that carries 400 vertices
# at z14 carries about 6 at z6, because that is all the tile grid can express.
# So one number cannot serve both ends of the range — 1,500 is roughly right at
# z12-z14 and badly wrong at z6, where the whole country lands in 7 tiles and
# 1,500 would truncate over half of the national motorway network away. The
# country view would then be a broken lattice, which is not an improvement on
# the blank screen it replaced.
#
# Measured tile sizes at these caps are recorded in the V3 report; the
# constraint that actually matters is that no tile exceeds a few hundred KB,
# and at low zoom the simplified features do not come close.
_ZOOM_CAP = {6: 12000, 7: 12000, 8: 10000, 9: 8000, 10: 6000, 11: 4000}


def cap_for_zoom(z, base):
    """Feature budget for one tile at zoom `z`.

    `base` (the --max-per-tile argument) governs z12 and above, where the
    original 1,500 was measured. Lower zooms get a larger budget because their
    features are cheaper, never a smaller one, so raising --max-per-tile can
    only ever raise a cap.
    """
    return max(base, _ZOOM_CAP.get(z, 0))


def select_for_tile(feats, cap, z=None):
    """Choose at most `cap` features, never starving a kind to zero.

    Within every kind the existing rank order still applies, so major roads
    still beat minor ones and the "lines stop at tile edges" regression the
    ranked truncation fixed stays fixed.

    `z` selects the floors: see `_POI_FLOOR_BY_ZOOM`. Omitting it keeps the
    flat floors, so the function is still callable from a test that does not
    care about zoom.
    """
    if len(feats) <= cap:
        return feats

    groups = {}
    for f in feats:
        kind = (f.properties or {}).get("kind") or "other"
        groups.setdefault(kind, []).append(f)
    for g in groups.values():
        g.sort(key=feature_rank)

    chosen = []
    # Pass 1: every non-road kind gets up to its floor.
    for kind, share in (kind_floors(z) if z is not None else _KIND_FLOOR).items():
        g = groups.get(kind)
        if not g:
            continue
        take = min(len(g), int(cap * share))
        chosen.extend(g[:take])
        groups[kind] = g[take:]

    # Pass 2: fill the remainder in global rank order (roads first), from what
    # each kind has left. Unclaimed floor space therefore returns to roads
    # rather than being wasted on kinds that do not exist in this tile.
    remaining = sorted(
        (f for g in groups.values() for f in g), key=feature_rank
    )
    chosen.extend(remaining[: max(0, cap - len(chosen))])
    return chosen


def read_learned_facts(path):
    """Read a ``learned_geometry.json`` export (wrapped doc or bare list)."""
    if not path:
        return []
    if not os.path.exists(path):
        print(f"[learned] no export at {path} — baking OSM only", flush=True)
        return []
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if isinstance(doc, list):
        return [f for f in doc if isinstance(f, dict)]
    if isinstance(doc, dict) and isinstance(doc.get("facts"), list):
        return [f for f in doc["facts"] if isinstance(f, dict)]
    return []


def main(argv=None):
    ap = argparse.ArgumentParser(description="Fast Qatar MVT tile builder")
    ap.add_argument("--geojson", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--zooms", default="11,12,13")
    ap.add_argument("--max-per-tile", type=int, default=1500,
                    help="cap features per tile to keep tiles small")
    ap.add_argument("--learned-facts", default=None,
                    help="learned_geometry.json from vector-learning (issue 08); "
                         "omitting it is a full rollback to OSM-only tiles")
    ap.add_argument("--no-version-bump", action="store_true",
                    help="skip the tile-epoch bump (for reproducible test bakes)")
    ap.add_argument("--lanes", action="store_true",
                    help="V8: also write the junction-aware `lanes` layer into the "
                         "z15 tiles, next to `basemap`. OFF by default; without it "
                         "not one line of the lane code runs")
    ap.add_argument("--lane-attrs", default=None,
                    help="the converter's --lane-attrs-out sidecar (OSM node ids, "
                         "layer, lanes:forward/backward); required with --lanes")
    ap.add_argument("--taper", action="store_true",
                    help="taper the carriageway width over the wider way at every "
                         "lane-count step (z14+ tiles only; `lw` on the pieces). OFF "
                         "by default; without it not one line of the taper code runs")
    args = ap.parse_args(argv)
    if args.lanes and not args.lane_attrs:
        ap.error("--lanes requires --lane-attrs (the junction index is keyed on "
                 "OSM node ids and elevation, which only the sidecar carries)")

    zooms = [int(z) for z in args.zooms.split(",") if z.strip()]
    features = load_geojson(args.geojson)
    print(f"[load] {len(features)} features", flush=True)

    # V8 lanes, computed ONCE from the OSM features (never the learned
    # overlay, which has no node ids), before anything is appended. With the
    # flag off this block does not run and `lane_buckets` stays None, so the
    # tile loop below is the V7.6 loop exactly.
    lane_buckets = None
    lane_report = None
    if args.lanes:
        from vector_tile_gen.lanes import (
            LANES_ZOOM, bucket_runs, build_lanes, load_lane_attrs, run_properties)
        t_lanes = time.time()
        lane_res = build_lanes(features, load_lane_attrs(args.lane_attrs))
        lane_buckets = bucket_runs(lane_res.runs, LANES_ZOOM)
        lane_report = {"zoom": LANES_ZOOM, "stats": lane_res.stats,
                       "tiles_touched": len(lane_buckets),
                       "tiles_written_with_lanes": 0, "lane_features_written": 0,
                       "lane_features_dropped_road_not_in_tile": 0,
                       "lane_features_dropped_subpixel": 0}
        print(f"[lanes] {lane_res.stats['final_runs']} runs, "
              f"{lane_res.stats['final_length_km']} km, {len(lane_buckets)} z{LANES_ZOOM} "
              f"tiles, in {time.time() - t_lanes:.1f}s", flush=True)
        # Only the bucketed runs are needed from here; the junction index and
        # segments would otherwise stay resident through the whole tile loop.
        del lane_res

    # Width tapers at lane-count steps, from the same untouched OSM features the
    # lane stage read (the lane layer's trims are computed on whole ways, and
    # the learned overlay is not tapered). Only z14+ tiles are expanded; with
    # the flag off `taper_by_obj` stays None and no tile changes.
    taper_by_obj = None
    taper_report = None
    if args.taper:
        from vector_tile_gen.encode import tile_bbox
        from vector_tile_gen.taper import TAPER_MIN_ZOOM, expand_selected, taper_parts
        t_taper = time.time()
        parts, taper_report = taper_parts(features)
        # Keyed by the ORIGINAL object: selection below runs on whole ways, and
        # a chosen way is swapped for its parts only after the cap is applied.
        taper_by_obj = {id(features[i]): ps for i, ps in parts.items()}
        del parts
        print(f"[taper] {taper_report.get('steps', 0)} steps, "
              f"{taper_report['ways_tapered']} ways -> {taper_report['taper_pieces']} pieces "
              f"(z{TAPER_MIN_ZOOM}+), in {time.time() - t_taper:.1f}s", flush=True)

    # Learned geometry is appended, never merged into the OSM source in place,
    # so `features` below is base + overlay and the input file is untouched.
    learned = features_from_facts(read_learned_facts(args.learned_facts))
    if learned:
        print(f"[learned] merging {len(learned)} learned features "
              f"(learned=true, provisional below 0.95 confidence)", flush=True)
        features = list(features) + list(learned)

    out_dir = os.path.join(args.out, "tiles")
    total = 0
    rejected = []
    for z in zooms:
        buckets = {}
        # Zoom-appropriate filtering happens BEFORE bucketing: a service road
        # has no business in a z11 tile, and excluding it here is what frees the
        # per-tile budget for the water and parks that were being starved out.
        visible = [f for f in features if visible_at_zoom(f, z)]
        for f in visible:
            for (x, y) in tiles_for_feature(f, z):
                buckets.setdefault((x, y), []).append(f)
        print(f"[zoom {z}] {len(buckets)} tiles from {len(visible)}/{len(features)} "
              f"zoom-visible features", flush=True)
        written = 0
        cap = cap_for_zoom(z, args.max_per_tile)
        dropped_subpixel = 0
        clipped_empty = 0
        for (x, y), feats in buckets.items():
            if len(feats) > cap:
                # Ranked truncation with per-kind floors. Ranking alone kept
                # road lines continuous (Session 50) but let roads eat the whole
                # budget, cutting water/parks/labels to zero in dense tiles.
                feats = select_for_tile(feats, cap, z)
            if taper_by_obj is not None and z >= TAPER_MIN_ZOOM:
                feats = expand_selected(feats, taper_by_obj, tile_bbox(z, x, y))
            fds = []
            for f in feats:
                fd = _as_feature_dict(f, z, x, y)
                # Thin vertices this tile's grid cannot resolve, and drop the
                # features that thin away to nothing. Done per tile because the
                # grid is per tile: the same park is a shape at z14 and smaller
                # than one cell at z8.
                simple = simplify_geometry(fd["geometry_type"], fd["coordinates"],
                                           z, x, y)
                if simple is None:
                    dropped_subpixel += 1
                    continue
                fd["coordinates"] = simple
                fds.append(fd)
            if not fds:
                # Every feature in this tile was sub-pixel. An empty tile is a
                # 404 waiting to happen; not writing it is correct, and the
                # count below says how often it happened.
                continue
            layers = [("basemap", fds)]
            if lane_buckets is not None and z == LANES_ZOOM and (x, y) in lane_buckets:
                # Only markings for roads this tile's BASEMAP actually kept: a
                # road cut by the feature cap must not leave its paint behind
                # on bare ground. "Kept" is judged AFTER clipping, because
                # encode_tile drops a road that does not reach the tile +
                # buffer, while its lane run (offset from the centreline) may
                # still reach it — 15/21018/14046 in the first clipped lanes
                # bake shipped paint with no basemap at all, and the tile
                # verifier rejected it.
                kept = roads_kept_after_clip(fds, z, x, y)
                lane_fds = []
                for r in lane_buckets[(x, y)]:
                    if f"w{r.way_osm_id}" not in kept:
                        lane_report["lane_features_dropped_road_not_in_tile"] += 1
                        continue
                    coords = simplify_geometry("LineString", [list(c) for c in r.coords],
                                               z, x, y)
                    if coords is None:
                        lane_report["lane_features_dropped_subpixel"] += 1
                        continue
                    lane_fds.append({"id": None, "geometry_type": "LineString",
                                     "coordinates": coords,
                                     "properties": run_properties(r),
                                     "_z": z, "_x": x, "_y": y})
                if lane_fds:
                    layers.append(("lanes", lane_fds))
                    lane_report["tiles_written_with_lanes"] += 1
                    lane_report["lane_features_written"] += len(lane_fds)
            data = encode_tile(layers)
            if not data:
                # Every feature was chosen by its BBOX, and none of them comes
                # within the clip buffer of this tile: a long diagonal way's
                # bbox covers tiles its line never goes near. Before clipping
                # those tiles carried the whole way as invisible bytes; now
                # they are empty, and an empty tile is not written, exactly
                # like the all-sub-pixel case above.
                clipped_empty += 1
                continue
            # Verify-before-publish (Session 50's rule). A full bake is the same
            # risk as a re-bake: one malformed tile renders as blank streets.
            ok, reason = verify_tile_bytes(data)
            if not ok:
                rejected.append(((z, x, y), reason))
                continue
            tdir = os.path.join(out_dir, str(z), str(x))
            os.makedirs(tdir, exist_ok=True)
            with open(os.path.join(tdir, f"{y}.mvt"), "wb") as fh:
                fh.write(data)
            written += 1
            total += 1
        print(f"[zoom {z}] wrote {written} tiles "
              f"(cap {cap}/tile, {dropped_subpixel} sub-pixel features dropped, "
              f"{clipped_empty} tiles empty after clipping, not written)",
              flush=True)

    if learned:
        overlay_out = os.path.join(out_dir, "learned-overlay.geojson")
        write_learned_geojson(overlay_out, learned)
        print(f"[provenance] learned overlay -> {overlay_out}", flush=True)

    if lane_report is not None:
        # Beside the tile tree, not in it: the release census reads only tiles,
        # and this is the bake's account of itself, not a published artifact.
        lanes_out = os.path.join(args.out, "lanes-bake-report.json")
        with open(lanes_out, "w", encoding="utf-8") as fh:
            json.dump(lane_report, fh, indent=1, sort_keys=True)
        print(f"[lanes] {lane_report['lane_features_written']} lane features in "
              f"{lane_report['tiles_written_with_lanes']} tiles -> {lanes_out}", flush=True)

    if taper_report is not None:
        taper_out = os.path.join(args.out, "taper-bake-report.json")
        with open(taper_out, "w", encoding="utf-8") as fh:
            json.dump(taper_report, fh, indent=1, sort_keys=True)
        print(f"[taper] report -> {taper_out}", flush=True)

    for (tile, reason) in rejected:
        print(f"  REJECTED {tile}: {reason}", file=sys.stderr)
    print(f"[done] {total} tiles under {out_dir} "
          f"({len(rejected)} rejected)", flush=True)

    if not args.no_version_bump:
        record = bump_version(
            out_dir, now_ms=int(time.time() * 1000),
            reason=f"full bake ({'with' if learned else 'no'} learned overlay)",
            tiles_changed=total,
        )
        print(f"[epoch] tile version -> {record['epoch']} "
              f"(clients refetch ?v={record['epoch']})", flush=True)

    return 0 if not rejected else 3


if __name__ == "__main__":
    raise SystemExit(main())
