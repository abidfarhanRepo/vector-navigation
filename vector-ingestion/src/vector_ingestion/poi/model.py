"""Canonical POI record + exclusion taxonomy.

A [CanonicalPoi] is the V1 internal representation every downstream consumer
(map labels, tiles, search) is built from. Fields are limited to what the
source data actually carries — no invented phone/website fields (neither
source pipeline exposes them today).

Never silently delete: every record that does not become a canonical POI is
classified out with exactly one EXCLUDED_* reason, which the audit report
counts.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

# -- exclusion reasons ---------------------------------------------------------

EXCLUDED_NON_DESTINATION = "EXCLUDED_NON_DESTINATION"
EXCLUDED_LOW_QUALITY_NAME = "EXCLUDED_LOW_QUALITY_NAME"
EXCLUDED_INVALID_GEOMETRY = "EXCLUDED_INVALID_GEOMETRY"
EXCLUDED_LOW_CONFIDENCE = "EXCLUDED_LOW_CONFIDENCE"
EXCLUDED_DUPLICATE = "EXCLUDED_DUPLICATE"
#: A POI with no name at all (map furniture, unnamed infrastructure). It can
#: never be a destination — there is nothing to search for or label — so it is
#: counted here rather than silently dropped.
EXCLUDED_UNNAMED = "EXCLUDED_UNNAMED"

EXCLUSION_REASONS = (
    EXCLUDED_NON_DESTINATION,
    EXCLUDED_LOW_QUALITY_NAME,
    EXCLUDED_INVALID_GEOMETRY,
    EXCLUDED_LOW_CONFIDENCE,
    EXCLUDED_DUPLICATE,
    EXCLUDED_UNNAMED,
)

#: Qatar bounds with a generous margin, for geometry sanity (lon/lat).
QATAR_BBOX = (50.5, 24.2, 52.0, 26.6)


def inside_qatar(lon: float, lat: float, bbox: tuple = QATAR_BBOX) -> bool:
    min_lon, min_lat, max_lon, max_lat = bbox
    if not all(map(lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
                   (lon, lat))):
        return False
    return (min_lon <= float(lon) <= max_lon
            and min_lat <= float(lat) <= max_lat)


@dataclass
class CanonicalPoi:
    """One canonical destination POI.

    ``osm_ids`` / ``overture_ids`` retain the underlying source references
    even after cross-source reconciliation — provenance is never discarded.
    """

    canonical_id: str
    name: str
    lon: float
    lat: float
    family: str
    category: "Optional[str]" = None        # Overture category / OSM poi_class / None
    name_en: "Optional[str]" = None
    #: Arabic display name, split out of a jammed bilingual label or carried
    #: from the source. Emitted as ``name:ar`` so an Arabic-speaking driver
    #: gets an Arabic label from the same record that leads in English.
    name_ar: "Optional[str]" = None
    aliases: List[str] = field(default_factory=list)
    brand: "Optional[str]" = None
    address: "Optional[str]" = None
    locality: "Optional[str]" = None
    confidence: "Optional[float]" = None     # Overture conflation confidence
    source: "Optional[str]" = None           # "osm" | "overture" | "both"
    osm_ids: List[str] = field(default_factory=list)
    overture_ids: List[str] = field(default_factory=list)
    quality_score: float = 0.0
    usefulness: str = "UNKNOWN"
    cluster_id: "Optional[str]" = None
    exclusion: "Optional[str]" = None        # None for canonical members
    exclusion_detail: "Optional[str]" = None
    #: MAP VISIBILITY — does this record earn a VISIBLE MAP LABEL?
    #:
    #: Orthogonal to ``exclusion``. An excluded record is not a canonical POI
    #: at all. A canonical POI is always SEARCHABLE; ``map_visible`` says only
    #: whether the driver map draws it. Overture Places is a directory-derived
    #: dataset: searching "AC repair" and getting Overture rows is the point,
    #: painting those businesses along Al Mansoura is not.
    #:
    #: The policy lives in ONE place — ``poi/visibility.py`` — and is stamped
    #: here by ``visibility.assign_map_visibility`` so the decision travels
    #: with the record into every output file instead of hiding in a writer.
    #: Default True so a record built outside the pipeline is not silently
    #: dropped from the map; the pipeline stamps every record explicitly.
    map_visible: bool = True
    map_hidden_reason: "Optional[str]" = None   # one MAP_HIDDEN_* reason

    # -- source helpers -------------------------------------------------------

    @property
    def raw_key(self) -> str:
        return self.source_id or self.canonical_id

    @property
    def source_id(self) -> "Optional[str]":
        if self.source == "both":
            return None
        if self.osm_ids:
            return self.osm_ids[0]
        if self.overture_ids:
            return self.overture_ids[0]
        return self.canonical_id

    def to_dict(self) -> Dict[str, Any]:
        """Flat summary used by the audit report."""
        return {
            "canonical_id": self.canonical_id,
            "name": self.name,
            "family": self.family,
            "category": self.category,
            "source": self.source,
            "quality_score": self.quality_score,
            "usefulness": self.usefulness,
            "cross_source": self.source == "both",
            "exclusion": self.exclusion,
            "cluster_id": self.cluster_id,
            "map_visible": self.map_visible,
            "map_hidden_reason": self.map_hidden_reason,
        }

    def to_geojson(self, include_provenance: bool = True) -> Dict[str, Any]:
        """GeoJSON Feature; ``kind=poi`` so tiles and the geocoder reuse their
        existing consumers unchanged.

        BOTH vocabularies are emitted when the category is known: ``category``
        (Overture) and ``poi_class`` (OSM). Tiles carry both fields today and
        the renderer/geocoder read either; emitting both for one record stops
        a canonical POI from becoming unreachable by a consumer that only
        reads one. ``/along?kinds=fuel`` must still match a canonical fuel
        station whatever source it was reconciled from.
        """
        props: Dict[str, Any] = {
            "kind": "poi",
            "name": self.name,
            "poi_family": self.family,
            "quality_score": round(self.quality_score, 4),
            "usefulness": self.usefulness,
            "source": self.source or "",
            # Emitted in EVERY output file, including the search file, so the
            # split is inspectable in the data and not only in the audit.
            "map_visible": bool(self.map_visible),
        }
        if self.map_hidden_reason:
            props["map_hidden_reason"] = self.map_hidden_reason
        if self.category:
            props["category"] = self.category
            props["poi_class"] = self.category
        if self.name_en:
            props["name:en"] = self.name_en
        if self.name_ar:
            props["name:ar"] = self.name_ar
        if self.aliases:
            props["alt_names"] = self.aliases
        if self.brand:
            props["brand"] = self.brand
        if self.address:
            props["address"] = self.address
        if self.locality:
            props["locality"] = self.locality
        if self.confidence is not None:
            props["confidence"] = round(self.confidence, 4)
        if include_provenance:
            props["canonical_id"] = self.canonical_id
            if self.cluster_id:
                props["cluster_id"] = self.cluster_id
            if self.osm_ids:
                props["osm_ids"] = self.osm_ids
            if self.overture_ids:
                props["overture_ids"] = self.overture_ids
            if self.exclusion:
                props["excluded"] = self.exclusion
                if self.exclusion_detail:
                    props["exclusion_detail"] = self.exclusion_detail
        if self.exclusion:
            # An excluded record is still a record for the AUDIT, but it must
            # never reach tiles/search as an ordinary destination.
            props["kind"] = "excluded_poi"
        return {
            "type": "Feature",
            "id": self.canonical_id,
            "geometry": {"type": "Point",
                         "coordinates": [round(self.lon, 7), round(self.lat, 7)]},
            "properties": props,
        }


def make_canonical_id(prefix: str, *parts: "Optional[str]") -> str:
    """Deterministic canonical id: ``poi:<slug>`` built from stable parts."""
    import hashlib
    joined = "|".join((p or "") for p in parts).strip("|")
    digest = hashlib.blake2b(joined.encode("utf-8"), digest_size=6).hexdigest()
    return f"poi:{prefix}:{digest}"