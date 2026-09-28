"""Learned-POI layer for vector-geocoder (issue 09, stdlib only).

Trips that repeatedly end in the same place are evidence of a place the map is
missing. ``vector-learning`` emits those as k-anonymous ``poi_candidate`` facts;
once promoted (issue 06) they land here and search improves from use.

Two design constraints shape this module, both non-obvious:

**Learned POIs have no name.** The detector infers that *a place exists*, never
what it is or whose it is — naming requires a non-trace source. An unnamed
entry cannot participate in name search at all, so learned POIs are kept in a
**separate structure** from ``GeocodeIndex._hits`` rather than being given a
placeholder name. That is a stronger guarantee than filtering: the existing
search path (including Arabic/Latin transliteration) cannot regress because it
never sees these entries. They surface through proximity lookup, and become
searchable only if :meth:`LearnedPoiIndex.name_poi` is later called with a name
from a real source.

**This is the highest privacy-risk consumer in the effort.** A frequent
destination cluster sits close to "where someone spends time". The two defences
live upstream and are relied on here rather than re-implemented (Bible D6):
endpoint truncation at ingest (adr-0065) means these coordinates are already
200 m short of any true destination, and the K floor in
``detect_poi_candidates`` means no candidate exists without >= 5 distinct trips.
A single person's home is unlearnable by construction, not by policy.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Tuple

# Learned POIs are tagged with this kind so a client can style them apart from
# OSM-sourced entries (issue 09: keep them distinguishable).
LEARNED_KIND = "learned_poi"

_EARTH_R_M = 6_371_000.0


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * _EARTH_R_M * math.asin(math.sqrt(a))


@dataclass
class LearnedPoi:
    """A place inferred from movement. Carries no name and no identity."""

    fact_key: str
    lon: float
    lat: float
    evidence_count: int
    confidence: float
    name: Optional[str] = None          # only ever set from a non-trace source
    source: str = "learning"

    @property
    def searchable(self) -> bool:
        """A learned POI joins name search only once genuinely named."""
        return bool(self.name)

    def to_geojson(self) -> Dict[str, Any]:
        return {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [self.lon, self.lat]},
            "properties": {
                "kind": LEARNED_KIND,
                "learned": True,
                "name": self.name,
                "label": self.name or "Unnamed place",
                "confidence": round(self.confidence, 4),
                "evidence_count": self.evidence_count,
                "fact_key": self.fact_key,
            },
        }


def pois_from_facts(facts: Iterable[Dict[str, Any]]) -> List[LearnedPoi]:
    """Build learned POIs from promoted ``poi_candidate`` facts."""
    out: List[LearnedPoi] = []
    for fact in facts:
        if fact.get("fact_type") != "poi_candidate":
            continue
        lng, lat = fact.get("lng"), fact.get("lat")
        if not isinstance(lng, (int, float)) or not isinstance(lat, (int, float)):
            continue
        out.append(
            LearnedPoi(
                fact_key=str(fact.get("fact_key") or f"poi:{lng}:{lat}"),
                lon=float(lng),
                lat=float(lat),
                evidence_count=int(fact.get("evidence_count", 0)),
                confidence=float(fact.get("confidence", 0.0)),
            )
        )
    return out


class LearnedPoiIndex:
    """Proximity-searchable set of learned POIs, held apart from the OSM index."""

    def __init__(self, pois: Optional[Iterable[LearnedPoi]] = None) -> None:
        self._by_key: Dict[str, LearnedPoi] = {}
        for poi in pois or []:
            self._by_key[poi.fact_key] = poi

    def __len__(self) -> int:
        return len(self._by_key)

    def all(self) -> List[LearnedPoi]:
        return [self._by_key[k] for k in sorted(self._by_key)]

    def add(self, poi: LearnedPoi) -> None:
        self._by_key[poi.fact_key] = poi

    def withdraw(self, fact_key: str) -> bool:
        """Remove a promoted POI and rebuild without it (issue 09 rollback)."""
        return self._by_key.pop(fact_key, None) is not None

    def name_poi(self, fact_key: str, name: str) -> bool:
        """Attach a name from a **non-trace** source.

        Naming never comes from movement — that is the line between inferring
        that a place exists and inferring what somebody does there. Once named,
        the POI becomes searchable.
        """
        poi = self._by_key.get(fact_key)
        if poi is None or not name.strip():
            return False
        poi.name = name.strip()
        return True

    def near(self, lat: float, lon: float, radius_m: float = 250.0,
             limit: int = 5) -> List[Tuple[LearnedPoi, float]]:
        """Learned POIs within ``radius_m``, nearest first."""
        scored = []
        for poi in self._by_key.values():
            d = _haversine_m(lat, lon, poi.lat, poi.lon)
            if d <= radius_m:
                scored.append((poi, d))
        scored.sort(key=lambda pair: (pair[1], pair[0].fact_key))
        return scored[:limit]

    def searchable_features(self) -> List[Dict[str, Any]]:
        """GeoJSON for the named subset only — safe to merge into the OSM index.

        Unnamed learned POIs are deliberately excluded: merging a nameless
        feature would either be dropped by ``GeocodeIndex.from_geojson`` or, if
        given a placeholder, would pollute name search. Neither is acceptable,
        so they stay in this index and surface through :meth:`near`.
        """
        return [p.to_geojson() for p in self.all() if p.searchable]

    def to_geojson(self) -> Dict[str, Any]:
        """The full learned layer, named or not — for map display and audit."""
        return {"type": "FeatureCollection", "features": [p.to_geojson() for p in self.all()]}
