"""Pipeline orchestration: raw sources -> canonical POI dataset + audit.

Consumes exactly the two files the current pipeline produces today (so
bootstrap step 2b can swap it in place):

* OSM basemap GeoJSON — ``osm_to_geojson.py`` output (``$GEO``)
* Overture places GeoJSON — ``fetch_overture_places.py`` output

and emits:

* canonical POI dataset (GeoJSON, ``kind=poi`` + provenance fields)
* search places file (same canonical set, geocoder shape)
* bake input (OSM basemap features + the MAP-VISIBLE canonical POIs, one file
  for ``build_qatar_tiles.py``) — see ``poi/visibility.py``
* search basemap (OSM basemap features + EVERY canonical POI) — what the
  geocoder indexes, so search never loses a record the map declines to draw
* audit report (JSON)

THE SPLIT (V1.2):

    OSM  ->  quality/rank  ->  DRIVER MAP (visible labels)
    Overture Places  ->  SEARCH / ENRICHMENT ONLY  (never a map label)

The canonical set is unchanged by it: every record still reaches search. Only
the bake input is filtered, by the per-record ``map_visible`` boolean that
``poi/visibility.py`` defines and this module stamps.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Optional, Tuple

from .audit import build_audit
from .dedup import build_records, cluster_and_reconcile
from .families import (
    EXCLUDED_FAMILIES, GOVERNMENT, INFRASTRUCTURE, MAP_FURNITURE, RESIDENTIAL,
    classify_overture, classify_osm,
)
from .model import (
    CanonicalPoi, inside_qatar, make_canonical_id,
    EXCLUDED_DUPLICATE, EXCLUDED_INVALID_GEOMETRY, EXCLUDED_LOW_CONFIDENCE,
    EXCLUDED_LOW_QUALITY_NAME, EXCLUDED_NON_DESTINATION,
)
from .display_names import (
    is_classless_anonymous, repair_feature_names, repeated_label_groups,
)
from .names import hard_name_signals, normalize_name
from .score import final_score, usefulness_bucket
from .visibility import assign_map_visibility

#: Overture conflation-confidence floor. V1.1: the floor is GONE as a
#: deletion rule. Measured: 0.5 removed Shater Abbas Restaurant (conf 0.4922)
#: and 0.4 removed hundreds of plausible real places (Hardee's at 0.3441,
#: Signature by Sanjeev Kapoor at 0.236). Confidence now contributes only to
#: the quality score — a weak record ranks low, it is never deleted by it.
#: Kept as documentation; nothing references it any more.
CONFIDENCE_FLOOR = 0.4

#: Curated search aliases for well-known institutions whose acronym is how a
#: driver actually searches. Tiny and conservative on purpose — every entry
#: must name the place a driver is looking for, and the alias must not
#: collide with another destination. The ministry's data name is
#: "وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar"
#: (Lusail) — no source row spells it "MOCI", so the acronym found nothing.
#:
#: Each entry carries the FAMILY the institution belongs to, and that is not
#: decoration. The name fragment alone matched two records: the ministry, and
#: "QNB ATM Ministry of Commerce & Industry Lusail" — a cash machine in its
#: lobby, which is named after the building it stands in. Both were given the
#: acronym, so "moci" resolved to the ATM as strongly as to the ministry, and
#: with the ATM's higher score it took the row above it. An acronym names ONE
#: institution; a business that merely mentions that institution in its name
#: is not that institution.
KNOWN_ALIASES = (
    ("ministry of commerce", GOVERNMENT, ["MOCI"]),
)

#: Families NEVER rescued, even under cross-source agreement: worker housing,
#: benches and power poles are not destinations no matter who maps them.
NEVER_RESCUE = frozenset({RESIDENTIAL, MAP_FURNITURE, INFRASTRUCTURE})


def load_features(path: "str | None") -> List[Dict[str, Any]]:
    if not path or not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    if not isinstance(doc, dict):
        return []
    return doc.get("features") or []


def _osm_pois(geo_features: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [f for f in geo_features
            if (f.get("properties") or {}).get("kind") == "poi"]


def _excluded_poi(rec: Dict[str, Any], reason: str, detail: str,
                  cluster_key: "Optional[str]" = None) -> CanonicalPoi:
    return CanonicalPoi(
        canonical_id=make_canonical_id("x", rec["key"]),
        name=rec["name"],
        lon=rec["lon"],
        lat=rec["lat"],
        family=rec["family"],
        category=rec["raw_category"],
        name_en=rec.get("name_en"),
        name_ar=rec.get("name_ar"),
        brand=rec.get("brand"),
        address=rec.get("address"),
        locality=rec.get("locality"),
        confidence=rec.get("confidence"),
        source=rec["source"],
        osm_ids=[rec["key"][len("osm:"):]] if rec["source"] == "osm" else [],
        overture_ids=([rec["key"][len("overture:"):]]
                      if rec["source"] == "overture" else []),
        quality_score=rec["base_score"],
        usefulness="NONE",
        cluster_id=cluster_key,
        exclusion=reason,
        exclusion_detail=detail,
    )


def _named_forms(rec: Dict[str, Any]) -> List[str]:
    out = [rec["name"]]
    if rec.get("name_en") and rec["name_en"] != rec["name"]:
        out.append(rec["name_en"])
    for a in rec.get("aliases") or []:
        out.append(a)
    return [a for a in out if a and a.strip()]


def _add_alias(canonical: CanonicalPoi, alias: str) -> None:
    if normalize_name(alias) and normalize_name(alias) != normalize_name(canonical.name):
        if alias not in canonical.aliases:
            canonical.aliases.append(alias)


def _add_known_aliases(rec: Dict[str, Any], canonical: CanonicalPoi) -> None:
    """Attach curated acronym aliases when the record IS the institution.

    Name fragment AND family, because the fragment on its own is just words
    that other businesses put in their names (see ``KNOWN_ALIASES``).
    """
    n = normalize_name(rec.get("name") or "")
    family = rec.get("family")
    for fragment, want_family, aliases in KNOWN_ALIASES:
        if fragment not in n or family != want_family:
            continue
        for a in aliases:
            if a not in canonical.aliases:
                canonical.aliases.append(a)


def _canonical_poi(rec: Dict[str, Any], out: Dict[str, Any]) -> CanonicalPoi:
    cross = bool(out.get("cross_source"))
    score = final_score(rec["base_score"], cross)
    canonical = CanonicalPoi(
        canonical_id=make_canonical_id(
            "p", rec["name"], f"{rec['lon']:.6f}", f"{rec['lat']:.6f}"),
        name=rec["name"],
        lon=rec["lon"],
        lat=rec["lat"],
        family=rec["family"],
        category=rec["raw_category"],
        name_en=rec.get("name_en"),
        name_ar=rec.get("name_ar"),
        brand=rec.get("brand"),
        address=rec.get("address"),
        locality=rec.get("locality"),
        confidence=rec.get("confidence"),
        source="both" if cross else rec["source"],
        osm_ids=[rec["key"][len("osm:"):]] if rec["source"] == "osm" else [],
        overture_ids=([rec["key"][len("overture:"):]]
                      if rec["source"] == "overture" else []),
        quality_score=score,
        usefulness=usefulness_bucket(rec["family"], score),
        cluster_id=out.get("cluster_key") or rec["key"],
    )
    # Spellings the name repair replaced (the raw bilingual label, the Arabic
    # half, an "... in Qatar" marketing form) ride along as aliases so the
    # record stays findable by everything it used to be findable by.
    for alias in rec.get("aliases") or ():
        _add_alias(canonical, alias)
    _add_known_aliases(rec, canonical)
    return canonical


def _collapse_repeated_labels(records: List[Dict[str, Any]],
                              outcomes: List[Dict[str, Any]]) -> int:
    """Show ONE "Administration", not twenty-six.

    The driver's complaint about "five Lusail polygons" has a general shape:
    OSM promotes a named building into a POI, and a name that describes a
    *part* of many complexes — "Administration", "Hangar", "Mechanical Draft
    Cooling", "District Cooling" — lands on one record per complex. Twenty-six
    identical rows answer no search and label nothing.

    The rule is deliberately narrow: the record must be CLASSLESS and
    ANONYMOUS (UNKNOWN family, no category, no address, no locality, no
    brand — see ``is_classless_anonymous``) and the identical normalised name
    must occur at least ``REPEATED_LABEL_MIN`` times among such records.
    Anything with a category, a street or a district attached is a real place
    and is never touched; "Starbucks" and "Woqod" cannot qualify because they
    are classified and branded.

    Survivors are the highest-scoring member of each group. The rest become
    EXCLUDED_DUPLICATE of it — a collapse, not a deletion: they keep their
    provenance, they appear in the audit ledger, and their spellings ride onto
    the survivor as aliases through the ordinary duplicate pass.

    Returns the number of records collapsed.
    """
    keep = [bool(o.get("canonical")) for o in outcomes]
    collapsed = 0
    for _key, idxs in repeated_label_groups(records, keep).items():
        winner = max(idxs, key=lambda i: (records[i]["base_score"],
                                          records[i]["key"]))
        cluster = outcomes[winner].get("cluster_key") or records[winner]["key"]
        outcomes[winner]["cluster_key"] = cluster
        for i in idxs:
            if i == winner:
                continue
            outcomes[i]["canonical"] = False
            outcomes[i]["exclusion"] = EXCLUDED_DUPLICATE
            outcomes[i]["exclusion_detail"] = (
                f"repeated classless label ({len(idxs)} copies of "
                f"{records[winner]['name']!r})")
            outcomes[i]["canonical_key"] = records[winner]["key"]
            outcomes[i]["cluster_key"] = cluster
            collapsed += 1
    return collapsed


def _run(records: List[Dict[str, Any]]) -> Tuple[List[CanonicalPoi],
                                                  List[CanonicalPoi]]:
    """Score, dedup/reconcile, then classify every record canonical|excluded.

    Returns ``(canonical, excluded)`` as CanonicalPoi objects. Excluded
    records retain provenance and carry their single EXCLUDED_* reason.
    """
    outcomes, _uf = cluster_and_reconcile(records)
    _collapse_repeated_labels(records, outcomes)

    canon_objs: "Dict[str, CanonicalPoi]" = {}
    excluded: List[CanonicalPoi] = []

    # Pass 1: records that survive as canonical members.
    for rec, out in zip(records, outcomes):
        if not out.get("canonical"):
            continue
        if not inside_qatar(rec["lon"], rec["lat"]):
            excluded.append(_excluded_poi(
                rec, EXCLUDED_INVALID_GEOMETRY,
                f"coords ({rec['lon']:.5f},{rec['lat']:.5f}) outside Qatar bbox"))
            continue
        hard = hard_name_signals(rec["name"], rec["family"])
        if hard:
            excluded.append(_excluded_poi(
                rec, EXCLUDED_LOW_QUALITY_NAME, ";".join(hard)))
            continue
        if rec["family"] in NEVER_RESCUE:
            excluded.append(_excluded_poi(
                rec, EXCLUDED_NON_DESTINATION,
                f"family {rec['family']} is never a destination"))
            continue
        if rec["family"] in EXCLUDED_FAMILIES and not out.get("cross_source"):
            excluded.append(_excluded_poi(
                rec, EXCLUDED_NON_DESTINATION,
                f"family {rec['family']} is not a destination"))
            continue
        # Overture `confidence` is CONFLATION confidence, not freshness and not
        # a junk signal. V1.1: it never deletes. A weak record (e.g. conf 0.24
        # "Signature by Sanjeev Kapoor") is retained and simply SCORES low
        # (0.10 * conf in the quality score) so it ranks under everything a
        # driver is more likely to be looking for. Only an independent rule
        # (family, name, geometry) may exclude a record.
        canon_objs[rec["key"]] = _canonical_poi(rec, out)

    # Pass 2: merged-away duplicates become EXCLUDED_DUPLICATE and their names
    # ride on the canonical record as aliases — never silently deleted.
    for rec, out in zip(records, outcomes):
        if out.get("canonical") or out.get("exclusion") != EXCLUDED_DUPLICATE:
            continue
        canonical = canon_objs.get(out.get("canonical_key"))
        detail = out.get("exclusion_detail") or "duplicate of canonical record"
        if canonical is not None:
            for alias in _named_forms(rec):
                _add_alias(canonical, alias)
        else:
            # The cluster's canonical member was itself excluded (e.g. four
            # identical "Ezdan Accomodation-1" worker-housing blocks). The
            # duplicates must still be accounted for — never silently dropped.
            detail = "duplicate of an excluded record"
        excluded.append(_excluded_poi(rec, EXCLUDED_DUPLICATE, detail,
                                      cluster_key=out.get("cluster_key")))

    return list(canon_objs.values()), excluded


def _merge_provenance(canonical: List[CanonicalPoi],
                      excluded: List[CanonicalPoi]) -> None:
    by_cluster: Dict[str, CanonicalPoi] = {}
    for c in canonical:
        if c.cluster_id:
            by_cluster.setdefault(c.cluster_id, c)
    for e in excluded:
        if e.exclusion == EXCLUDED_DUPLICATE and e.cluster_id:
            target = by_cluster.get(e.cluster_id)
            if target is None:
                continue
            target.overture_ids = _uniq(target.overture_ids + e.overture_ids)
            target.osm_ids = _uniq(target.osm_ids + e.osm_ids)


def _uniq(xs: List[str]) -> List[str]:
    seen = set()
    out = []
    for x in xs:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


def run_pipeline(osm_path: "Optional[str]", places_path: "Optional[str]",
                 out_canonical: "Optional[str]" = None,
                 out_audit: "Optional[str]" = None,
                 out_bake_input: "Optional[str]" = None,
                 out_places: "Optional[str]" = None,
                 out_excluded: "Optional[str]" = None,
                 out_search_basemap: "Optional[str]" = None) -> Dict[str, Any]:
    """Run the full V1 pipeline over the two source files.

    Returns the audit dict. Writes the requested output files.
    """
    geo = load_features(osm_path)
    places = load_features(places_path)
    osm_pois = _osm_pois(geo)
    # Name repair runs FIRST, on the raw features, so that classification,
    # scoring and duplicate detection all see the repaired, English-leading
    # name rather than a jammed bilingual label. It returns new feature dicts;
    # ``geo`` itself is untouched, so the basemap layers bake unchanged.
    osm_pois = repair_feature_names(osm_pois)
    places = repair_feature_names(places)
    records = build_records(
        osm_pois, places, classify_osm=classify_osm,
        classify_overture=classify_overture)

    canonical, excluded = _run(records)

    # provenance: merged duplicates contribute their ids to the canonical
    _merge_provenance(canonical, excluded)

    # MAP VISIBILITY. Stamped on the record, not applied in a writer, so the
    # decision is visible in every output file and countable in the audit.
    # Runs AFTER reconciliation because rule R1 reads the reconciled `source`
    # ("both" only exists once cross-source records have been merged).
    map_stats = assign_map_visibility(canonical)

    audit = build_audit(records, [c.to_dict() for c in canonical],
                        [c.to_dict() for c in excluded])
    audit["map_layer"] = map_stats
    audit["map_layer"]["searchable"] = len(canonical)
    audit["map_layer"]["policy"] = (
        "OSM -> map labels; Overture Places -> search/enrichment only. "
        "See vector_ingestion/poi/visibility.py. `map_hidden_by_first_reason` "
        "sums to the hidden total; `map_hidden_by_rule_standalone` counts each "
        "rule independently (they overlap) and "
        "`map_hidden_quality_cost_over_osm_primary` is what each quality rule "
        "costs among records that already passed the source rule."
    )

    # The audit ledger must reconcile over the FULL raw count: unnamed OSM
    # POIs (map furniture, shelter, gates ...) never enter `records` — there
    # is no name to score or dedupe — but they are real excluded POIs and are
    # accounted for under EXCLUDED_UNNAMED.
    named_osm = sum(1 for r in records if r["source"] == "osm")
    unnamed = len(osm_pois) - named_osm
    audit["total_raw"]["osm"] = len(osm_pois)
    audit["total_raw"]["total"] = (len(osm_pois)
                                    + audit["total_raw"]["overture"])
    if unnamed:
        audit["excluded"]["by_reason"]["EXCLUDED_UNNAMED"] = unnamed
        audit["excluded"]["total"] += unnamed

    if out_canonical:
        _write_json(out_canonical,
                    _collection_doc([c.to_geojson(True) for c in canonical]))
    if out_places:
        _write_json(out_places,
                    _collection_doc([c.to_geojson(False) for c in canonical]))
    if out_excluded:
        _write_json(out_excluded,
                    _collection_doc([e.to_geojson(True) for e in excluded]))
    if out_bake_input:
        # MAP: map-visible POIs only.
        _write_json(out_bake_input, _bake_input(geo, canonical))
    if out_search_basemap:
        # SEARCH: the same basemap with EVERY canonical POI. bootstrap step 2c
        # points the geocoder at this, so replacing the raw OSM POIs with the
        # canonical set never costs search a record the map merely hid.
        _write_json(out_search_basemap,
                    _bake_input(geo, canonical, map_visible_only=False))
    if out_audit:
        _write_json(out_audit, audit)
    return audit


def _collection_doc(features: List[Dict[str, Any]]) -> Dict[str, Any]:
    return {
        "type": "FeatureCollection",
        "properties": {
            "source": "vector-poi-quality-pipeline-v1",
            "generated": "canonical POI dataset",
        },
        "features": features,
    }


def _bake_input(geo: List[Dict[str, Any]],
                canonical: List[CanonicalPoi],
                map_visible_only: bool = True) -> Dict[str, Any]:
    """OSM basemap features (all kinds) + canonical POIs — one merged file.

    ``map_visible_only=True`` (the tile bake input) keeps only the POIs that
    earn a VISIBLE LABEL under ``poi/visibility.py``. This is the ONLY place
    the Overture-only records are dropped, and it drops them from the MAP
    alone: ``<region>_places.geojson`` and ``canonical_pois.geojson`` are
    written from the same unfiltered ``canonical`` list.

    ``map_visible_only=False`` (the geocoder basemap) keeps every canonical
    POI — the raw OSM POIs are still replaced by canonical ones, which is what
    bootstrap step 2c wants, without narrowing search.

    Raw OSM POIs are dropped either way: they are superseded by the canonical
    layer, and re-admitting them would put the worker housing, building ids
    and map furniture the pipeline just excluded straight back on the map.
    """
    out = []
    for f in geo:
        props = f.get("properties") or {}
        if props.get("kind") == "poi":
            continue  # replaced by the canonical layer below
        out.append(f)
    for c in canonical:
        if map_visible_only and not c.map_visible:
            continue
        out.append(c.to_geojson(True))
    return {"type": "FeatureCollection", "features": out}


def _write_json(path: str, doc: Any) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False)