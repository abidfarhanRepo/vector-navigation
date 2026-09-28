"""Learned-geometry layer for vector-tile-gen (issue 08, stdlib only).

The most literal form of "the map grows": a road people demonstrably drive on
but OSM does not have actually appears on the basemap. Promoted
``road_candidate`` and ``geometry_correction`` facts (issue 06) are merged into
the bake as a **separate source layer** and the affected tiles are selectively
re-baked.

This is also the riskiest promotion in the whole effort — a bad one writes a
wrong road into the tiles every user sees. Three safeguards, all enforced here:

1. **The OSM source is never edited in place.** Learned features live in their
   own GeoJSON overlay and are merged at bake time, so the provenance of every
   rendered feature stays recoverable and a withdrawal is just a re-bake
   without the overlay.
2. **Every re-baked tile is verified before it is published.** Tiles are
   encoded to memory, classified with the same rule Session 50's
   ``validate_tiles.py`` uses, and only written to disk if they pass. A tile
   that fails is not shipped and the promotion is reported as rejected.
3. **Learned features are visually distinguishable.** Each carries
   ``learned=true`` plus its confidence and fact key, so the MapLibre style can
   paint it differently (dashed//amber) until confidence is high — a wrong
   promotion is obvious rather than invisible.

A note on layering, because it is subtle. Session 50's verifier requires a tile
to expose an MVT layer named ``basemap`` — roads anywhere else are invisible to
the live style and are the "streets vanish" bug class. (V8 allows exactly one
optional companion, ``lanes``; see :mod:`vector_tile_gen.layers`. The re-bake
here still writes basemap-only tiles, so re-baking a lanes-enabled release's
z15 tiles through this path drops their ``lanes`` layer.) So learned
geometry is *not* a second MVT layer; it is separate at the **source** level
(its own GeoJSON overlay, merged at bake) and distinguished at the **feature**
level (the ``learned`` property). That keeps provenance and rollback intact
without reintroducing the exact failure mode the verifier exists to catch.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

from .encode import decode_tile
from .layers import BASEMAP_LAYER as _BASEMAP_LAYER, check_layer_names, raw_layer_names
from .pipeline import generate_tile
from .tiles import lonlat_to_tile

# The one canonical layer name the live MapLibre style filters on. Defined in
# vector_tile_gen.layers with the rest of the layer rule, so this, validate_tiles
# and the release census cannot disagree — a tile without it renders blank (the
# Session 50 "streets vanish" bug).
BASEMAP_LAYER = _BASEMAP_LAYER

# Fact types that carry geometry worth rendering.
GEOMETRY_FACT_TYPES = ("road_candidate", "geometry_correction")

TileId = Tuple[int, int, int]


@dataclass
class LearnedFeature:
    """A bake-ready feature, matching the surface ``generate_tile`` expects."""

    id: str
    geometry_type: str
    coordinates: Any
    properties: Dict[str, Any] = field(default_factory=dict)
    bbox: Optional[Tuple[float, float, float, float]] = None

    def to_geojson(self) -> Dict[str, Any]:
        return {
            "type": "Feature",
            "id": self.id,
            "geometry": {"type": self.geometry_type, "coordinates": self.coordinates},
            "properties": dict(self.properties),
        }


def _iter_positions(coordinates: Any):
    """Yield every ``(x, y)`` position in an arbitrarily nested coordinate array.

    Handles Point through MultiPolygon without caring which it is: a GeoJSON
    coordinate array is a tree whose leaves are positions, so walking it is both
    shorter and more general than a per-geometry-type branch.
    """
    if isinstance(coordinates, (list, tuple)):
        if len(coordinates) >= 2 and all(isinstance(c, (int, float)) for c in coordinates[:2]):
            yield (float(coordinates[0]), float(coordinates[1]))
            return
        for item in coordinates:
            yield from _iter_positions(item)


def _bbox_of(geometry_type: str, coordinates: Any) -> Optional[Tuple[float, float, float, float]]:
    """Bounding box of any GeoJSON geometry, or ``None`` when it has no positions."""
    if not geometry_type:
        return None
    xs: List[float] = []
    ys: List[float] = []
    for (x, y) in _iter_positions(coordinates):
        xs.append(x)
        ys.append(y)
    if not xs or not ys:
        return None
    return (min(xs), min(ys), max(xs), max(ys))


def load_base_features(path: str) -> List[LearnedFeature]:
    """Load a basemap GeoJSON into bake-ready features.

    Deliberately local rather than reusing ``vector_ingestion.load_geojson``,
    even though the two produce the same duck type. The re-bake path runs under
    per-repo CI isolation where sibling repos are **not** checked out, so
    importing one makes the script — and every test that invokes it — fail with
    ``ModuleNotFoundError`` in the gate while passing locally. That is precisely
    what the isolated gate exists to catch, and it caught it.

    Features are returned as :class:`LearnedFeature` so the whole bake works with
    one type; the ``learned`` property is what distinguishes an overlay feature
    from a base one, not its class.
    """
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    raw = doc.get("features", []) if isinstance(doc, dict) else (doc or [])
    out: List[LearnedFeature] = []
    for i, feature in enumerate(raw):
        if not isinstance(feature, dict):
            continue
        geometry = feature.get("geometry") or {}
        gtype = geometry.get("type")
        coords = geometry.get("coordinates")
        if not gtype or coords is None:
            continue
        props = dict(feature.get("properties") or {})
        # DEFENCE IN DEPTH for the map/search split. `map_visible=false` is
        # stamped by the POI quality pipeline on a canonical POI that is
        # SEARCHABLE but must never become a visible map label (Overture-only
        # businesses, classless records, "you phone them" categories). The
        # bake input is already filtered, but this re-bake path takes its
        # `--base-geojson` from an operator, and pointing it at the geocoder's
        # search basemap would silently put all 17,850 of them back on the map.
        # A feature with no such property is unaffected.
        if props.get("map_visible") is False:
            continue
        fid = feature.get("id") or props.get("id") or f"feature-{i}"
        out.append(
            LearnedFeature(
                id=str(fid),
                geometry_type=str(gtype),
                coordinates=coords,
                properties=props,
                bbox=_bbox_of(str(gtype), coords),
            )
        )
    return out


def features_from_facts(facts: Iterable[Dict[str, Any]]) -> List[LearnedFeature]:
    """Turn promoted geometry facts into bake-ready features.

    Every emitted feature is tagged ``learned=true`` with its confidence and
    fact key, so it is both visually distinguishable in the style and traceable
    back to the evidence that justified it.
    """
    out: List[LearnedFeature] = []
    for fact in facts:
        ftype = fact.get("fact_type")
        if ftype not in GEOMETRY_FACT_TYPES:
            continue
        payload = fact.get("payload") or {}
        geometry = payload.get("geometry")

        if isinstance(geometry, (list, tuple)) and len(geometry) >= 2:
            geometry_type, coordinates = "LineString", [list(p) for p in geometry]
        else:
            # A road candidate without a traced line is still worth showing as
            # a point marker — it is a real "something is here" signal.
            lng, lat = fact.get("lng"), fact.get("lat")
            if not isinstance(lng, (int, float)) or not isinstance(lat, (int, float)):
                continue
            geometry_type, coordinates = "Point", [float(lng), float(lat)]

        key = str(fact.get("fact_key") or f"{ftype}:{fact.get('id', '')}")
        out.append(
            LearnedFeature(
                id=key,
                geometry_type=geometry_type,
                coordinates=coordinates,
                properties={
                    "learned": True,
                    "fact_type": ftype,
                    "fact_key": key,
                    "confidence": round(float(fact.get("confidence", 0.0)), 4),
                    "evidence_count": int(fact.get("evidence_count", 0)),
                    # Give the style something to filter on for the "provisional"
                    # look while confidence is still building.
                    "provisional": float(fact.get("confidence", 0.0)) < 0.95,
                    "highway": "unclassified",
                },
                bbox=_bbox_of(geometry_type, coordinates),
            )
        )
    return out


def merge_sources(base_features: Sequence[Any], learned: Sequence[LearnedFeature]) -> List[Any]:
    """Merge learned features over the base set **without mutating the base**.

    Returns a new list; ``base_features`` is untouched, which is what makes a
    withdrawal a pure re-bake rather than a repair.
    """
    return list(base_features) + list(learned)


def affected_tiles(
    features: Sequence[LearnedFeature],
    zooms: Sequence[int],
) -> Set[TileId]:
    """The exact tile set touched by these features — no full-tree rebuild.

    Walks each feature's bbox corners at each zoom. For the short line segments
    a learned road is made of, corner coverage is the whole extent; the caller
    can widen with ``pad`` upstream if it ever bakes long ways.
    """
    out: Set[TileId] = set()
    for feat in features:
        box = feat.bbox or _bbox_of(feat.geometry_type, feat.coordinates)
        if box is None:
            continue
        minx, miny, maxx, maxy = box
        for z in zooms:
            for lon, lat in ((minx, miny), (minx, maxy), (maxx, miny), (maxx, maxy)):
                x, y = lonlat_to_tile(z, lon, lat)
                out.add((int(z), int(x), int(y)))
    return out


def verify_tile_bytes(data: bytes) -> Tuple[bool, str]:
    """Classify an encoded tile with Session 50's rule.

    A tile is publishable when it is empty (ocean/desert renders as nothing) or
    decodes and carries a ``basemap`` layer plus, optionally, the allowlisted
    V8 ``lanes`` layer (:mod:`vector_tile_gen.layers`). Anything else is the
    invisible-layer bug and must not reach disk.
    """
    if len(data) == 0:
        return True, "empty"
    try:
        decode_tile(data)
        names = raw_layer_names(data)
    except Exception as exc:  # noqa: BLE001 - any decode failure is a bad tile
        return False, f"decode error: {exc}"
    return check_layer_names(names)


@dataclass
class RebakeResult:
    written: List[TileId] = field(default_factory=list)
    rejected: List[Tuple[TileId, str]] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.rejected

    def to_dict(self) -> Dict[str, Any]:
        return {
            "written": [list(t) for t in self.written],
            "rejected": [[list(t), r] for t, r in self.rejected],
            "ok": self.ok,
        }


def rebake_tiles(
    tiles_dir: str,
    base_features: Sequence[Any],
    learned: Sequence[LearnedFeature],
    tile_ids: Iterable[TileId],
    *,
    dry_run: bool = False,
) -> RebakeResult:
    """Selectively re-bake ``tile_ids`` from base + learned features.

    **Verify-before-publish:** each tile is encoded in memory and classified
    first. Only tiles that pass are written; a failing tile leaves whatever was
    previously on disk untouched and is reported in ``rejected`` so the caller
    can revert the promotion (``FactStore.withdraw``).
    """
    merged = merge_sources(base_features, learned)
    result = RebakeResult()

    for (z, x, y) in sorted(tile_ids):
        data = generate_tile(merged, z, x, y, layer_name=BASEMAP_LAYER)
        ok, reason = verify_tile_bytes(data)
        if not ok:
            result.rejected.append(((z, x, y), reason))
            continue
        result.written.append((z, x, y))
        if dry_run:
            continue
        path = os.path.join(tiles_dir, str(z), str(x), f"{y}.mvt")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "wb") as fh:
            fh.write(data)
        os.replace(tmp, path)  # atomic: readers never see a half-written tile
    return result


def withdraw_tiles(
    tiles_dir: str,
    base_features: Sequence[Any],
    tile_ids: Iterable[TileId],
    *,
    dry_run: bool = False,
) -> RebakeResult:
    """Roll a geometry promotion back: re-bake the tiles from the base only.

    This is the one-command rollback issue 08 requires. Because the base source
    was never edited, withdrawing is exactly a re-bake with an empty learned
    set — there is no repair step and nothing to undo by hand.
    """
    return rebake_tiles(tiles_dir, base_features, [], tile_ids, dry_run=dry_run)


def write_learned_geojson(path: str, learned: Sequence[LearnedFeature]) -> None:
    """Persist the learned overlay as its own GeoJSON, separate from the OSM source."""
    parent = os.path.dirname(os.path.abspath(path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    doc = {
        "type": "FeatureCollection",
        "features": [f.to_geojson() for f in learned],
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
