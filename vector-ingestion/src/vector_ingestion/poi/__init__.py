"""Qatar POI Quality Pipeline V1.

Turns raw OSM + Overture POI locations into a canonical, driver-useful POI
dataset BEFORE map/search/tile layers consume them.

Modules
-------
model      canonical POI record + exclusion reasons
families   category/class -> semantic family
names      name-quality signals (contextualised)
score      deterministic driver-usefulness score
dedup      cross-source reconciliation + duplicate clustering
visibility MAP-VISIBILITY POLICY: what earns a visible map label
audit      exclusion accounting + audit report
pipeline   stage orchestration + file I/O

The package is stdlib-only and lives inside `vector-ingestion` (its bounded
context: "normalizes raw geographic source data", ADR-0011). It is invoked by
``scripts/build_canonical_pois.py`` and by ``bootstrap.sh`` step 2b.

Design constraints (see `.scratch/vector-poi-quality/spec.md`):

* NO giant allowlist. Unknown categories fall to family UNKNOWN, are RETAINED
  and ranked low — never deleted.
* NO naive radius dedup. Chains (Woqod, Starbucks, Lulu) must survive.
* `confidence` is conflation confidence, not freshness. No threshold here
  detects a closed business; that is a separate freshness pipeline.
* Every excluded POI carries an explainable EXCLUDED_* reason (audit).
* SEARCH and MAP are different sets. Every canonical POI is searchable; only
  the map-visible ones get a label. OSM feeds the map, Overture Places feeds
  search/enrichment only — see `visibility.py`, which owns that policy.
"""

from .families import (
    classify_overture,
    classify_osm,
    families_compatible,
    is_singleton_family,
    family_name,
)
from .model import (
    CanonicalPoi,
    EXCLUDED_DUPLICATE,
    EXCLUDED_INVALID_GEOMETRY,
    EXCLUDED_LOW_CONFIDENCE,
    EXCLUDED_LOW_QUALITY_NAME,
    EXCLUDED_NON_DESTINATION,
    EXCLUSION_REASONS,
)
from .names import name_quality, hard_name_signals
from .score import base_quality, final_score
from .dedup import cluster_and_reconcile
from .visibility import (
    MAP_HIDDEN_NOT_A_DESTINATION,
    MAP_HIDDEN_NO_CATEGORY,
    MAP_HIDDEN_OVERTURE_ONLY,
    MAP_HIDDEN_PHONE_THEM_CATEGORY,
    MAP_HIDDEN_REASONS,
    MAP_VISIBLE_SOURCES,
    PHONE_THEM_CATEGORIES,
    assign_map_visibility,
    is_map_visible,
    map_hidden_reason,
)
from .audit import build_audit
from .pipeline import run_pipeline

__all__ = [
    "classify_overture",
    "classify_osm",
    "families_compatible",
    "is_singleton_family",
    "family_name",
    "CanonicalPoi",
    "EXCLUDED_DUPLICATE",
    "EXCLUDED_INVALID_GEOMETRY",
    "EXCLUDED_LOW_CONFIDENCE",
    "EXCLUDED_LOW_QUALITY_NAME",
    "EXCLUDED_NON_DESTINATION",
    "EXCLUSION_REASONS",
    "name_quality",
    "hard_name_signals",
    "base_quality",
    "final_score",
    "cluster_and_reconcile",
    "MAP_HIDDEN_NOT_A_DESTINATION",
    "MAP_HIDDEN_NO_CATEGORY",
    "MAP_HIDDEN_OVERTURE_ONLY",
    "MAP_HIDDEN_PHONE_THEM_CATEGORY",
    "MAP_HIDDEN_REASONS",
    "MAP_VISIBLE_SOURCES",
    "PHONE_THEM_CATEGORIES",
    "assign_map_visibility",
    "is_map_visible",
    "map_hidden_reason",
    "build_audit",
    "run_pipeline",
]