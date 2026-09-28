"""Duplicate detection + cross-source reconciliation.

The goal is ONE canonical POI per real place, with provenance retained —
without ever touching a legitimate chain. Measured constraints that shaped
these rules (Qatar corpus, 2026-09-13):

* Overture has 1,002 same-normalized-name groups (3,296 records) but only 27
  same-name pairs within 250 m, and those are dominated by chains: Starbucks 6,
  KFC 3, Subway 2, H&M 2, Papa John's, Carrefour. A radius-only rule deletes
  real outlets, so distance alone is never enough.
* 795 Overture records have an exact same-name OSM POI within 250 m — the
  cross-source agreement signal; these are the safest merges in the corpus.
* Singleton institutions (churches, embassies, museums, universities,
  airports) exist ONCE per name: "Churh Of The Holy Rosary" (conf .5353) sits
  5.7 km from the real church while "Seraphic Hall, Catholic Church of Our
  Lady of the Rosary" is 20 km NW and may be a genuinely different building —
  the system must tell those apart from the records themselves.
* Chains are protected by construction: the far-copy rule only applies to
  singleton families, brand rules stop at 100 m, same-name merges stop at
  40 m within a source.

Every merged-away record becomes EXCLUDED_DUPLICATE and its names become
aliases on the canonical record, so search still finds "Churh Of The Holy
Rosary" — it just resolves to the real church.
"""

from __future__ import annotations

import difflib
from collections import defaultdict
from typing import Any, Dict, List, Optional, Tuple

from .families import families_compatible, is_singleton_family, refine_category
from .names import normalize_name, tokens

try:  # vendored shared implementation (ADR-0007)
    from vector_geo.haversine import haversine_meters
except Exception:  # pragma: no cover - slim container without the vendored tree
    import math as _math

    def haversine_meters(a, b):
        r = 6371008.8
        lon1, lat1, lon2, lat2 = map(float, (*a, *b))
        phi1, phi2 = _math.radians(lat1), _math.radians(lat2)
        dphi = _math.radians(lat2 - lat1)
        dlmb = _math.radians(lon2 - lon1)
        h = (_math.sin(dphi / 2.0) ** 2
             + _math.cos(phi1) * _math.cos(phi2) * _math.sin(dlmb / 2.0) ** 2)
        return 2 * r * _math.asin(_math.sqrt(h))


#: Same-source same-name pairs closer than this are the same place (node +
#: polygon duplicates, double-mapped POIs).
SAME_SOURCE_SAME_NAME_M = 40.0

#: Same-name pairs between DIFFERENT sources within this radius agree on a
#: real place — the geocoder already uses 250 m for exactly this.
CROSS_SOURCE_RADIUS_M = 250.0

#: Same-brand outlets closer than this are one complex.
SAME_BRAND_RADIUS_M = 100.0

#: Records within this distance of the canonical member of a singleton-family
#: cluster are the same location, whatever they are named.
SINGLETON_MERGE_RADIUS_M = 2000.0

#: A far singleton member keeps its own record only when it carries a
#: substantive extra token beyond the canonical's name — "Seraphic Hall" is a
#: different building, "Churh Of The Holy Rosary" is a typo pin.
EXCLUDE_STOPWORDS = frozenset(tokens("of the and our lady saint mary de la el",
                                     drop_stopwords=False))

#: Token count under which a token is rare (distinctive). "rosary" appears
#: 6 times in the corpus; "masjid" thousands of times.
_RARE_TOKEN_CAP = 12

#: Words that never make a token "distinctive" — generic institution words
#: that dozens of different places share. "chapel", "fellowship" and
#: "grace" are each shared by several DIFFERENT churches in Qatar; "rosary"
#: is shared only by copies of one church. Only non-generic shared tokens may
#: justify a merge. V1.1 added the audit-evidenced words: "workers" (three
#: different workers' hospitals 101 km apart), "tamil" (distinct Tamil
#: congregations in one complex), "prayer"/"room" (different prayer rooms),
#: "branch"/"building". "anglican" is deliberately ABSENT: it is the only
#: evidence the 14 m Anglican Centre pair shares, and the ≤100 m same-spot
#: rule needs it — the 11 km Epiphany church is kept apart by the ≥2-shared
#: evidence bar in ``_name_contained`` instead.
_GENERIC_TOKENS = frozenset({
    "church", "churh", "chapel", "chapels", "fellowship", "fellowships",
    "ministry", "ministries", "gospel", "god", "christian", "catholic",
    "orthodox", "evangelical", "pentecostal", "coptic", "syrian", "mar",
    "apostolic", "adventist", "baptist", "assembly", "revival", "grace",
    "covenant", "living", "life", "city", "glory", "kingdom",
    "community", "international", "centre", "center", "hall", "complex",
    "religious", "mosque", "masjid", "abu", "hamour", "church_center",
    "workers", "tamil", "prayer", "room", "branch", "building",
    "methodist", "presbyterian", "episcopal", "protestant", "saints",
    "brothers", "convention", "fellowship_international", "anglican",
})


def _hav(a: Dict[str, Any], b: Dict[str, Any]) -> float:
    return haversine_meters((a["lon"], a["lat"]), (b["lon"], b["lat"]))


def _normalised_props(props: Dict[str, Any]) -> Dict[str, Any]:
    """Project one raw GeoJSON POI property dict into the dedup record shape."""
    name = props.get("name")
    if isinstance(name, str) and name.strip():
        display = name.strip()
    else:
        ne = props.get("name:en")
        display = ne.strip() if isinstance(ne, str) and ne.strip() else ""
    n_en = props.get("name:en")
    n_ar = props.get("name:ar")
    brand = props.get("brand")
    raw_category = props.get("category") or props.get("poi_class")
    conf = props.get("confidence")
    try:
        confidence = float(conf) if conf is not None else None
    except (TypeError, ValueError):
        confidence = None
    return {
        "name": display,
        "name_en": (n_en.strip() if isinstance(n_en, str) and n_en.strip() else None),
        "name_ar": (n_ar.strip() if isinstance(n_ar, str) and n_ar.strip() else None),
        # Alternate spellings the name repair split off (``display_names``) or
        # the source already carried. They must survive into the canonical
        # record, or a name that used to be searchable stops being searchable.
        "aliases": [a.strip() for a in (props.get("alt_names") or [])
                    if isinstance(a, str) and a.strip()],
        "norm": normalize_name(display),
        "tokens": tokens(display),
        "raw_category": (raw_category or "").strip() or None,
        "brand": (brand.strip().casefold() if isinstance(brand, str) and brand.strip()
                  else None),
        "confidence": confidence,
        "address": props.get("address"),
        "locality": props.get("locality"),
    }


def build_records(osm_pois: List[Dict[str, Any]],
                  overture_places: List[Dict[str, Any]],
                  classify_osm, classify_overture) -> List[Dict[str, Any]]:
    """Turn raw GeoJSON POI features into the pipeline record list.

    ``osm_pois`` are the OSM basemap POIs (kind=poi), ``overture_places`` the
    Overture places layer. Records carry everything dedup and scoring need,
    plus ``base_score`` computed before reconciliation.
    """
    from .score import base_quality

    records: List[Dict[str, Any]] = []
    for side, source in ((osm_pois, "osm"), (overture_places, "overture")):
        classifier = classify_osm if source == "osm" else classify_overture
        for f in side:
            props = f.get("properties") or {}
            if source == "osm" and props.get("kind") != "poi":
                continue
            geom = f.get("geometry") or {}
            coords = geom.get("coordinates")
            if not coords or len(coords) < 2:
                continue
            try:
                lon, lat = float(coords[0]), float(coords[1])
            except (TypeError, ValueError):
                continue
            np_ = _normalised_props(props)
            if not np_["name"]:
                continue
            if source == "osm":
                raw_fid = f.get("id")
                if not raw_fid:
                    raw_fid = f"osm:{lon:.5f}:{lat:.5f}"
                sid = f"osm:{raw_fid}"
                family = classifier(np_["raw_category"], np_["name"])
            else:
                oid = props.get("overture_id") or f.get("id") or f"ov:{lon:.5f}:{lat:.5f}"
                sid = f"overture:{oid}"
                family = classifier(np_["raw_category"], np_["name"])
            # The category a record CARRIES can be corrected by its own name
            # (see `families.refine_category`): "QNB ATM ..." filed by Overture
            # under `cabin` is an ATM, and both its family and the category the
            # driver is shown have to say so.
            raw_category = refine_category(np_["raw_category"], np_["name"])
            rec = dict(np_)
            rec.update({
                "key": sid,
                "source": source,
                "family": family,
                "raw_category": raw_category,
                "lon": lon,
                "lat": lat,
                "base_score": base_quality(
                    family=family, name=np_["name"], confidence=np_["confidence"],
                    has_address=bool(np_["address"]), has_brand=bool(np_["brand"]),
                    category=raw_category),
            })
            records.append(rec)
    return records


class _UnionFind:
    def __init__(self, n: int) -> None:
        self.parent = list(range(n))

    def find(self, i: int) -> int:
        while self.parent[i] != i:
            self.parent[i] = self.parent[self.parent[i]]
            i = self.parent[i]
        return i

    def union(self, a: int, b: int) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def _name_groups(records: List[Dict[str, Any]]) -> Dict[str, List[int]]:
    groups: Dict[str, List[int]] = defaultdict(list)
    for i, r in enumerate(records):
        if r["norm"]:
            groups[r["norm"]].append(i)
    return groups


def _rare_token_index(records: List[Dict[str, Any]]) -> Dict[str, List[int]]:
    """token -> record indices, for rare DISTINCTIVE tokens only.

    Generic institution words (church, chapel, fellowship ...) never seed a
    candidate set — they are shared by many different real places."""
    counts: Dict[str, int] = defaultdict(int)
    for r in records:
        for t in set(r["tokens"]):
            counts[t] += 1
    idx: Dict[str, List[int]] = defaultdict(list)
    for i, r in enumerate(records):
        for t in set(r["tokens"]):
            if t in _GENERIC_TOKENS:
                continue
            if counts[t] <= _RARE_TOKEN_CAP and len(t) >= 4:
                idx[t].append(i)
    return idx


def _same_church(a: str, b: str) -> bool:
    """Both raw categories are the CATHOLIC church — the only institution
    type whose records pair at one position in different languages (the Rosary
    church's Italian alias sits 36 m from its English name; there is exactly
    one such close catholic pair in Qatar). A broad "both are churches" test
    is wrong: the Religious Complex packs ~50 churches within 60 m."""
    return a == "catholic_church" and b == "catholic_church"


def _fold(token: str, against: Tuple[str, ...]) -> bool:
    """Is ``token`` equal to (or a typo of) any token in ``against``?"""
    if token in against:
        return True
    return any(difflib.SequenceMatcher(None, token, c).ratio() >= 0.75
               for c in against)


def _name_contained(other_tokens: Tuple[str, ...], canon_tokens: Tuple[str, ...]) -> bool:
    """Is the variant's name contained in the canonical's, with evidence?

    Every token of the variant must appear in the canonical (typo-folded) —
    AND the shared evidence must not be flimsy. V1.1 bar: at least TWO shared
    tokens, at least one of which is DISTINCTIVE (non-generic). That is what
    allows "Churh Of The Holy Rosary" -> "Holy Rosary Catholic Church"
    (3 shared, distinctive "rosary") while refusing one-token brand
    containment such as "Aster" -> "Aster Medical Centre" (1 shared) and
    "Workers Hospital" -> "Workers Health Centre" ("hospital" does not fold
    into "health")."""
    if not other_tokens:
        return False
    if not all(_fold(t, canon_tokens) for t in other_tokens):
        return False
    shared = [t for t in other_tokens if _fold(t, canon_tokens)]
    distinctive = [t for t in shared
                   if t not in _GENERIC_TOKENS and len(t) >= 4]
    return len(shared) >= 2 and len(distinctive) >= 1


def _same_significant_tokens(a: Tuple[str, ...], b: Tuple[str, ...]) -> bool:
    """Same words modulo filler: "Anglican Centre in Qatar" and "Anglican
    Church Centre" both reduce to {anglican}. Used only for the ≤100 m
    same-spot rule, never across distance."""
    if not a or not b:
        return False
    return all(_fold(t, b) for t in a) and all(_fold(t, a) for t in b)


def _pair_compatible(a: Dict[str, Any], b: Dict[str, Any]) -> bool:
    return families_compatible(a["family"], b["family"])


def _union_all(records: List[Dict[str, Any]]) -> _UnionFind:
    """Pair candidates into edges, then union-find closure."""
    n = len(records)
    uf = _UnionFind(n)
    groups = _name_groups(records)
    rare_idx = _rare_token_index(records)

    def link(i: int, j: int) -> None:
        if _pair_compatible(records[i], records[j]):
            uf.union(i, j)

    # Rule 1/2/4: same normalized name, distance-bounded.
    for idxs in groups.values():
        if len(idxs) < 2:
            continue
        for i in range(len(idxs)):
            for j in range(i + 1, len(idxs)):
                a, b = records[idxs[i]], records[idxs[j]]
                d = _hav(a, b)
                if a["source"] != b["source"]:
                    # cross-source agreement — the safest signal in the corpus
                    if d <= CROSS_SOURCE_RADIUS_M:
                        link(idxs[i], idxs[j])
                elif d <= SAME_SOURCE_SAME_NAME_M:
                    link(idxs[i], idxs[j])
                elif d <= CROSS_SOURCE_RADIUS_M:
                    # Same source, 40-250 m. Two paths:
                    #  * merge if one side is a classless named building
                    #    (node+polygon duplicate, the OSM double-mapping
                    #    signature);
                    #  * SINGLETON institutions exist once per name, so an
                    #    identical name 40-250 m away is the same place —
                    #    "Al Wakrah Hospital" had three records (one 60 m
                    #    from another) that all survived under the old rule.
                    if ((not a["raw_category"]) != (not b["raw_category"])
                            or (is_singleton_family(a["family"], a["raw_category"])
                                and is_singleton_family(b["family"], b["raw_category"]))):
                        link(idxs[i], idxs[j])

    # Rule 3: same brand, close (chain outlets in one complex).
    by_brand = defaultdict(list)
    for i, r in enumerate(records):
        if r["brand"]:
            by_brand[r["brand"]].append(i)
    for idxs in by_brand.values():
        for i in range(len(idxs)):
            for j in range(i + 1, len(idxs)):
                if _hav(records[idxs[i]], records[idxs[j]]) <= SAME_BRAND_RADIUS_M:
                    link(idxs[i], idxs[j])

    # Rule 5: singleton institutions — same-position different-language
    # aliases (Rosary / Chiesa di Nostra Signora del Rosario).
    for i in range(n):
        a = records[i]
        if not is_singleton_family(a["family"], a["raw_category"]):
            continue
        for j in range(i + 1, n):
            b = records[j]
            if not is_singleton_family(b["family"], b["raw_category"]):
                continue
            if _hav(a, b) <= 60.0 and (
                _same_church(a["raw_category"] or "", b["raw_category"] or "")
                or (a["norm"] and a["norm"] == b["norm"])):
                link(i, j)

    # Rule 6: singleton institutions — far copies found through a shared
    # DISTINCTIVE token, then confirmed by NAME CONTAINMENT: one record's
    # significant tokens must all appear in the other's (typo-folded).
    # "rosary" is distinctive (6 records, one church); "chapel"/"fellowship"
    # are generic (many different churches) and never justify a merge.
    seen = set()
    for idxs in rare_idx.values():
        for i in range(len(idxs)):
            for j in range(i + 1, len(idxs)):
                lo, hi = min(idxs[i], idxs[j]), max(idxs[i], idxs[j])
                if (lo, hi) in seen:
                    continue
                seen.add((lo, hi))
                a, b = records[lo], records[hi]
                if not (is_singleton_family(a["family"], a["raw_category"])
                        and is_singleton_family(b["family"], b["raw_category"])):
                    continue
                if not _pair_compatible(a, b):
                    continue
                # Hospitals are CHAINS in Qatar (Aster has five Doha branches,
                # First Dental Center two sites 10 km apart): name containment
                # must never merge them across kilometres. Only a close
                # same-place pair (the OSM/Cuban twin 150 m away) may link.
                if (a["family"] == "HEALTHCARE"
                        and b["family"] == "HEALTHCARE"
                        and _hav(a, b) > 1000.0):
                    continue
                if _name_contained(a["tokens"], b["tokens"]) or \
                   _name_contained(b["tokens"], a["tokens"]):
                    link(lo, hi)

    return uf


def _pick_canonical(cluster: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Best record in a cluster: base score, then confidence, then name.

    For SINGLETON institutions (one real place per name) the canonical name
    is the one the sources trust most, so confidence leads: the real church at
    0.9563 beats a far hall with a longer name, and a 0.8172 Italian alias
    never becomes the canonical label for Qatar. General clusters keep score
    first — a rich, well-addressed record beats a sparse one."""
    singleton = all(is_singleton_family(r["family"], r["raw_category"])
                    for r in cluster)

    def key(r: Dict[str, Any]) -> Tuple[float, float, int, str]:
        conf = r["confidence"] if r["confidence"] is not None else -1.0
        if singleton:
            return (conf, r["base_score"], -len(r["name"]), r["name"])
        return (r["base_score"], conf, -len(r["name"]), r["name"])
    return max(cluster, key=key)


#: Outcome dict keys every record receives.
def cluster_and_reconcile(records: List[Dict[str, Any]]
                          ) -> Tuple[List[Dict[str, Any]], _UnionFind]:
    """Assign every record to a canonical POI or a duplicate exclusion.

    Returns ``(outcomes, uf)`` where ``outcomes[i]`` is a dict that is either
    ``{"canonical": True, ...}`` or a duplicate exclusion referencing the
    canonical member.
    """
    uf = _union_all(records)
    comp: Dict[int, List[int]] = defaultdict(list)
    for i in range(len(records)):
        comp[uf.find(i)].append(i)

    outcomes: List[Optional[Dict[str, Any]]] = [None] * len(records)
    for members in comp.values():
        if len(members) == 1:
            outcomes[members[0]] = {"canonical": True}
            continue

        # V1.1 — the canonical representative must come from the LOCAL
        # cluster (audit rule A/D). A record tens of kilometres from every
        # other member of its component can never be the canonical label for
        # a clustered place: "The Cuban Hospital, Qatar" (a mis-placed Doha
        # copy 70 km from the real Dukhan hospital) and "Anglican Church Of
        # Epiphany Doha" (11 km from the Anglican Centre it was linked to
        # by the word "anglican") must not win the slot. Canonical candidates
        # are members with at least one other member within 2 km; if the
        # whole component is scattered, any member may be canonical.
        core = [i for i in members
                if any(_hav(records[i], records[j]) <= SINGLETON_MERGE_RADIUS_M
                       for j in members if j != i)]
        candidates = core or members
        cluster = [records[i] for i in candidates]
        canon = _pick_canonical(cluster)
        ci = candidates[cluster.index(canon)]
        cross_source = (any(records[i]["source"] == "osm" for i in members)
                        and any(records[i]["source"] == "overture" for i in members))
        outcomes[ci] = {"canonical": True, "canonical_key": canon["key"],
                        "cross_source": cross_source}
        for idx in members:
            if idx == ci:
                continue
            other = records[idx]
            singleton_pair = (
                is_singleton_family(other["family"], other["raw_category"])
                and is_singleton_family(canon["family"], canon["raw_category"]))
            if singleton_pair:
                # Evidence-based merge for one-place-per-name institutions.
                # (a) name containment (typo variants, any distance);
                # (b) same words modulo filler <=100 m (the 14 m Anglican
                #     Centre pair — and NOT the Malankara pair 30 m away,
                #     whose names share no significant token);
                # (c) same-position catholic alias (Rosary / Italian);
                # (d) identical name <=250 m (under-merged hospitals).
                contained = _name_contained(other["tokens"], canon["tokens"])
                dist = _hav(other, canon)
                # Hospitals are chains: name containment merges across
                # distance only for non-healthcare singletons (the Rosary
                # church's typo pins); a hospital variant 1000 m+ away is a
                # different branch (Aster at 9.6 km, First Dental at 10 km).
                containment_ok = (contained and (dist <= 1000.0
                                                 or canon["family"] != "HEALTHCARE"))
                if (containment_ok
                        or (dist <= 100.0 and _same_significant_tokens(
                            other["tokens"], canon["tokens"]))
                        or (dist <= 60.0 and _same_church(
                            other["raw_category"] or "", canon["raw_category"] or ""))
                        or (dist <= CROSS_SOURCE_RADIUS_M
                            and other["norm"] == canon["norm"])):
                    outcomes[idx] = {
                        "canonical": False,
                        "exclusion": "EXCLUDED_DUPLICATE",
                        "exclusion_detail":
                            f"singleton variant of '{canon['name']}'",
                        "canonical_key": canon["key"],
                        "cluster_key": canon["key"],
                        "alias_of": canon["name"],
                    }
                    continue
                # Not contained and far, or near without name evidence: a
                # genuinely different building ("Seraphic Hall ...", Al Khor
                # "Aster", a second Tamil church). Keep it as its own record.
                outcomes[idx] = {
                    "canonical": True,
                    "cross_source": False,
                    "canonical_key": other["key"],
                    "cluster_key": canon["key"],
                }
                continue
            # Non-singleton chain member (linked by name/brand at <=100 m):
            # one complex, one canonical.
            outcomes[idx] = {
                "canonical": False,
                "exclusion": "EXCLUDED_DUPLICATE",
                "exclusion_detail":
                    f"duplicate of '{canon['name']}' (quality {canon['base_score']:.3f})",
                "canonical_key": canon["key"],
                "cluster_key": canon["key"],
                "alias_of": canon["name"],
            }

    for i in range(len(records)):
        if outcomes[i] is None:  # defensive: every record has an outcome
            outcomes[i] = {"canonical": True}
    return outcomes, uf