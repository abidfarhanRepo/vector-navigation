"""MAP VISIBILITY POLICY — the one place that decides what earns a map label.

    OSM  -> POI quality/rank + density limits -> DRIVER MAP (visible labels)
    Overture Places -> SEARCH / ENRICHMENT ONLY (never a map label)

Why this module exists
----------------------
Every canonical POI is SEARCHABLE. Only some are MAP-VISIBLE. Until V1.2 that
distinction did not exist: ``bake-input/<region>.geojson`` received the whole
canonical set, so 17,850 Overture-only records became map labels. Measured on
the 2026-09-14 V1.1 bake: a map that had 8,272 named OSM POIs grew to 18,753
Overture-derived ones, a 3.15x increase, and within 400 m of a driver on Al
Mansoura there were 124 labels — 88 of them Overture-only, including
"Doha moving services", "Washing Machine and AC Repair Doha Qatar" and
"hand made aquarium 77693436".

The decision is a per-record boolean (``CanonicalPoi.map_visible``) rather than
an implicit filter inside a writer, so it is inspectable in the output files,
greppable in the source, and testable in isolation.

The policy
----------
R1  SOURCE. A record is a map-label candidate only if it is OSM-primary —
    ``source`` in {"osm", "both"}. Overture Places is a DIRECTORY-derived
    dataset: it is an excellent answer to "where can I get an AC repaired",
    and a bad answer to "what is worth printing on the road I am driving
    down". OSM presence means a human mapper decided the thing belongs on a
    map. That is the signal we do not otherwise have.

    Should a very high-quality Overture-only record be PROMOTED? No, and
    deliberately so. The obvious promotion signal is cross-source agreement,
    and that record is already ``source="both"`` — R1 admits it. The
    remaining signals (``confidence``, ``quality_score``) do not measure
    "belongs on a map": ``confidence`` is Overture's CONFLATION confidence
    (see score.py — Shater Abbas sits at 0.4922 and "Doha moving services"
    at 0.7502 after scoring), and ``quality_score`` is a destination-
    usefulness rank, which ranks a moving company above a real OSM grocery
    (0.7502 vs 0.6100 — measured). A score threshold would therefore admit
    exactly the records the owner objected to and reject ones he did not.
    There is no promotion rule here until a signal exists that actually
    measures map-worthiness.

R2..R4 QUALITY, applied to the OSM-primary survivors of R1. Being on OSM is
    necessary, not sufficient: OSM also carries 807 classless ``poi_class=yes``
    registrations and streets of laundries and saloons.

    R2  NO CATEGORY. A record whose category says nothing ("" / missing /
        ``yes``) cannot be given an icon, cannot be ranked against its
        neighbours, and tells the driver nothing. 1,583 such records
        corpus-wide.
    R3  PHONE-THEM CATEGORY. Categories whose members are businesses you
        RING, not places you DRIVE to: event planners, travel agents, moving
        companies, financial-services registrations. 829 corpus-wide.
    R4  NOT A DESTINATION. ``usefulness`` below DESTINATION — the SUPPORT and
        NONE buckets. A SUPPORT record is a real place that no one plans a
        trip around; it stays searchable and stops competing for label space.

Search is NEVER affected. ``<region>_places.geojson`` and
``canonical_pois.geojson`` carry every canonical record regardless of these
rules; only ``bake-input/<region>.geojson`` is filtered.
"""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional, Tuple

# -- hidden reasons -----------------------------------------------------------

#: Overture-only: searchable, never a map label (R1).
MAP_HIDDEN_OVERTURE_ONLY = "MAP_HIDDEN_OVERTURE_ONLY"
#: No usable category at all (R2).
MAP_HIDDEN_NO_CATEGORY = "MAP_HIDDEN_NO_CATEGORY"
#: A category you phone rather than drive to (R3).
MAP_HIDDEN_PHONE_THEM_CATEGORY = "MAP_HIDDEN_PHONE_THEM_CATEGORY"
#: usefulness is SUPPORT or NONE (R4).
MAP_HIDDEN_NOT_A_DESTINATION = "MAP_HIDDEN_NOT_A_DESTINATION"

MAP_HIDDEN_REASONS = (
    MAP_HIDDEN_OVERTURE_ONLY,
    MAP_HIDDEN_NO_CATEGORY,
    MAP_HIDDEN_PHONE_THEM_CATEGORY,
    MAP_HIDDEN_NOT_A_DESTINATION,
)

# -- policy inputs ------------------------------------------------------------

#: R1. Sources whose records a human mapper put on a map. ``both`` is included
#: because it IS an OSM record that Overture also describes.
MAP_VISIBLE_SOURCES = frozenset({"osm", "both"})

#: R2. Category values that carry no information. ``yes`` is the tag VALUE for
#: ``office=yes`` / ``building=yes`` and reaches us as ``poi_class="yes"``
#: (807 records): it says the key was present, not what the place is.
UNINFORMATIVE_CATEGORIES = frozenset({"yes", "no", "unknown", "other"})

#: R3. "You phone them, you don't drive there." Measured counts in the
#: 2026-09-14 corpus are in the comments; extend this set, not the call sites.
PHONE_THEM_CATEGORIES = frozenset({
    "party_and_event_planning",   # 440 — caterers, wedding planners
    "travel_services",            # 233 — travel agents, tour operators
    "transportation",             # 101 — movers, packers, courier desks
    "financial_service",          #  47 — brokerages, "Creative Solutions" LLCs
})

#: R4. The only usefulness bucket that earns a label. Imported by value rather
#: than from score.py to keep this module free of scoring dependencies; the
#: string is asserted equal to ``score.USEFULNESS_DESTINATION`` by the tests.
MAP_VISIBLE_USEFULNESS = "DESTINATION"


def _norm_category(category: "Optional[str]") -> str:
    return (category or "").strip().casefold()


def has_usable_category(category: "Optional[str]") -> bool:
    c = _norm_category(category)
    return bool(c) and c not in UNINFORMATIVE_CATEGORIES


def is_phone_them_category(category: "Optional[str]") -> bool:
    return _norm_category(category) in PHONE_THEM_CATEGORIES


def is_map_source(source: "Optional[str]") -> bool:
    return (source or "").strip().casefold() in MAP_VISIBLE_SOURCES


#: The policy, as data. Ordered: the FIRST failing rule is the record's
#: reported reason. Both the per-record decision and the per-rule audit
#: accounting are derived from this one tuple, so they can never disagree.
RULES: "Tuple[Tuple[str, str, Any], ...]" = (
    ("R1", MAP_HIDDEN_OVERTURE_ONLY,
     lambda source, category, usefulness: not is_map_source(source)),
    ("R2", MAP_HIDDEN_NO_CATEGORY,
     lambda source, category, usefulness: not has_usable_category(category)),
    ("R3", MAP_HIDDEN_PHONE_THEM_CATEGORY,
     lambda source, category, usefulness: is_phone_them_category(category)),
    ("R4", MAP_HIDDEN_NOT_A_DESTINATION,
     lambda source, category, usefulness: usefulness != MAP_VISIBLE_USEFULNESS),
)


def map_hidden_reason(*, source: "Optional[str]", category: "Optional[str]",
                      usefulness: "Optional[str]") -> "Optional[str]":
    """The FIRST rule this record fails, or None when it earns a map label."""
    for _rid, reason, predicate in RULES:
        if predicate(source, category, usefulness):
            return reason
    return None


def is_map_visible(*, source: "Optional[str]", category: "Optional[str]",
                   usefulness: "Optional[str]") -> bool:
    return map_hidden_reason(source=source, category=category,
                             usefulness=usefulness) is None


def failing_rules(*, source: "Optional[str]", category: "Optional[str]",
                  usefulness: "Optional[str]") -> List[str]:
    """EVERY rule this record fails, not just the first.

    Used by the audit so each rule's cost can be read independently: R4 and R2
    overlap heavily (a classless record is usually SUPPORT too), and attributing
    each record to one reason alone would understate both.
    """
    return [reason for _rid, reason, predicate in RULES
            if predicate(source, category, usefulness)]


def poi_map_hidden_reason(poi: Any) -> "Optional[str]":
    """``map_hidden_reason`` for a CanonicalPoi (or anything with the fields)."""
    return map_hidden_reason(source=getattr(poi, "source", None),
                             category=getattr(poi, "category", None),
                             usefulness=getattr(poi, "usefulness", None))


def assign_map_visibility(pois: Iterable[Any]) -> Dict[str, int]:
    """Stamp ``map_visible`` / ``map_hidden_reason`` on every canonical POI.

    Returns the audit counts: the map-visible total, the first-reason ledger
    (which sums to the hidden total) and the standalone per-rule ledger (which
    does not, because the rules overlap).
    """
    visible = 0
    first_reason: Dict[str, int] = {r: 0 for r in MAP_HIDDEN_REASONS}
    per_rule: Dict[str, int] = {r: 0 for r in MAP_HIDDEN_REASONS}
    by_source: Dict[str, int] = {}
    #: what each QUALITY rule costs the map ON TOP of the source rule — i.e.
    #: counted only over records that passed R1. This is the number the owner
    #: asked for: "measure each rule's effect separately".
    quality_cost: Dict[str, int] = {r: 0 for r in MAP_HIDDEN_REASONS
                                    if r != MAP_HIDDEN_OVERTURE_ONLY}
    for poi in pois:
        source = getattr(poi, "source", None)
        category = getattr(poi, "category", None)
        usefulness = getattr(poi, "usefulness", None)
        failed = failing_rules(source=source, category=category,
                               usefulness=usefulness)
        for reason in failed:
            per_rule[reason] += 1
        if is_map_source(source):
            for reason in failed:
                if reason in quality_cost:
                    quality_cost[reason] += 1
        if failed:
            poi.map_visible = False
            poi.map_hidden_reason = failed[0]
            first_reason[failed[0]] += 1
        else:
            poi.map_visible = True
            poi.map_hidden_reason = None
            visible += 1
            by_source[source or "?"] = by_source.get(source or "?", 0) + 1
    return {
        "map_visible": visible,
        "map_visible_by_source": by_source,
        "map_hidden_by_first_reason": first_reason,
        "map_hidden_by_rule_standalone": per_rule,
        "map_hidden_quality_cost_over_osm_primary": quality_cost,
    }
