"""Deterministic driver-usefulness scoring.

A raw location becomes a canonical POI by score, not by allowlist. The score
is the weighted sum of signals that exist in the data:

    score = 0.45 * family_usefulness
          + 0.20 * name_quality
          + 0.10 * confidence        (Overture; missing treated as neutral 0.5)
          + 0.10 * address_brand     (address and/or brand present)
          + 0.15 * cross_source      (OSM + Overture both describe it)

`confidence` is Overture's CONFLATION confidence — how sure they are the
record describes one real place — NOT freshness. Missing confidence never
demotes (neutral 0.5): OSM and learned records carry no field and are not
weaker for it.

Its ONLY effect is this 0.10 term. The V1.1 audit removed the deletion
threshold entirely: a record at conf 0.24 loses at most 0.076 points of
score, which is enough to rank it below proper destinations but never
enough to hide a searched place (search tiers still beat every secondary
signal). Do NOT reintroduce a hard floor — confidence is neither freshness
nor a junk signal (Signature by Sanjeev Kapoor carried 0.236).

`base_quality` is computed per record BEFORE dedup (cross_source=0 everywhere,
family=UNKNOWN for building-only/classless records) and is the evidence used
to pick the canonical member of a duplicate cluster. `final_score` adds the
cross-source signal after reconciliation.
"""

from __future__ import annotations

from typing import Optional

from .families import (
    ATTRACTION, EDUCATION, FINANCE, FOOD, GOVERNMENT, HEALTHCARE,
    INDUSTRY, INFRASTRUCTURE, LODGING, MAP_FURNITURE, OFFICE, PARKING,
    RELIGIOUS, RESIDENTIAL, SERVICES, SHOPPING, SPORT, TRANSPORT, UNKNOWN,
    EXCLUDED_FAMILIES,
)
from .names import name_quality

#: What a driver is most likely to be looking for at the end of a trip.
FAMILY_USEFULNESS = {
    HEALTHCARE: 1.00,    # emergency and medicine
    TRANSPORT: 0.95,     # airports, fuel, transit
    FOOD: 0.95,
    RELIGIOUS: 0.92,
    LODGING: 0.92,
    EDUCATION: 0.88,
    FINANCE: 0.85,       # banks and ATMs are reasons to stop
    PARKING: 0.85,
    GOVERNMENT: 0.82,
    ATTRACTION: 0.82,
    SHOPPING: 0.80,
    SPORT: 0.78,
    SERVICES: 0.55,      # storefronts you might drive to, but rarely plan a trip around
    UNKNOWN: 0.35,       # conservative: retained, low rank, never deleted
    RESIDENTIAL: 0.08,
    OFFICE: 0.05,
    INDUSTRY: 0.05,
    INFRASTRUCTURE: 0.15,
    MAP_FURNITURE: 0.0,
}

#: usefulness bucket for the output dataset.
USEFULNESS_DESTINATION = "DESTINATION"   # comfortably above the default
USEFULNESS_SUPPORT = "SUPPORT"           # real place, low priority
USEFULNESS_NONE = "NONE"                 # not a destination this data can defend


#: Usefulness for a handful of CATEGORIES whose family is right but whose
#: rank inside it is not.
#:
#: An ATM is a machine bolted to the wall of somewhere else. FINANCE is the
#: correct family for it — "banks and ATMs are reasons to stop" — but a bank
#: BRANCH is a destination you drive to and a cash machine is a fixture of the
#: building that houses it. Ranked at the family's 0.85, the QNB ATM standing
#: inside the Ministry of Commerce and Industry outscored the ministry (0.799
#: vs 0.7358) and took the row above it, so a driver who searched "moci
#: lusail" was offered the cash machine and not the ministry.
#:
#: 0.45 keeps it a findable DESTINATION (the bucket floor is 0.30, and the
#: measured record still scores 0.5875) while putting every place that has an
#: address of its own above it.
CATEGORY_USEFULNESS = {
    "atm": 0.45,
    "atms": 0.45,
}


def family_usefulness(family: str, category: "Optional[str]" = None) -> float:
    """Driver-usefulness of a record's family, refined by its category.

    ``category`` is optional so every existing caller keeps working; when it
    is given, a CATEGORY_USEFULNESS row wins over the family default.
    """
    if category:
        override = CATEGORY_USEFULNESS.get(category.strip().casefold())
        if override is not None:
            return override
    return FAMILY_USEFULNESS.get(family, 0.35)


def usefulness_bucket(family: str, score: float) -> str:
    if family in EXCLUDED_FAMILIES and family not in (UNKNOWN,):
        return USEFULNESS_NONE
    if score >= 0.55:
        return USEFULNESS_DESTINATION
    if score >= 0.30:
        return USEFULNESS_SUPPORT
    return USEFULNESS_NONE


def _confidence_component(confidence: "Optional[float]") -> float:
    if confidence is None:
        return 0.5  # neutral, never a demotion
    try:
        c = float(confidence)
    except (TypeError, ValueError):
        return 0.5
    return max(0.0, min(1.0, c))


def base_quality(*, family: str, name: "str | None", confidence: "Optional[float]",
                 has_address: bool = False, has_brand: bool = False,
                 cross_source: float = 0.0,
                 category: "Optional[str]" = None) -> float:
    """The deterministic usefulness score, before dedup."""
    fam_v = family_usefulness(family, category)
    name_v = name_quality(name, family)
    conf_v = _confidence_component(confidence)
    addr_v = 1.0 if (has_address or has_brand) else 0.0
    cross = cross_source if cross_source else 0.0
    score = (0.45 * fam_v + 0.20 * name_v + 0.10 * conf_v
             + 0.10 * addr_v + 0.15 * cross)
    return round(score, 4)


def final_score(record_base: float, cross_source: bool) -> float:
    """Add the cross-source signal after reconciliation.

    ``record_base`` is the base score of the surviving (canonical) record.
    """
    if cross_source:
        return round(min(1.0, record_base + 0.15), 4)
    return record_base