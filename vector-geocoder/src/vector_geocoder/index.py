"""In-memory geocoding index over basemap features.

Features with a ``name`` (place labels and named roads) are indexed by a
normalized key so that substring/prefix queries match regardless of case or
diacritics. Roads are represented by a Point at their first vertex (good
enough to drop a map pin / route target); place labels use their own Point.
"""

from __future__ import annotations

import math
import unicodedata
from typing import Any, Dict, List, Optional, Tuple

# Lightweight Arabic -> Latin transliteration for search keying. Not a full
# standard (Buckwalter/SAMI) — just enough that Latin-typing users can find
# Arabic-named streets/places. Self-hosted; no third-party transliteration.
_AR_TRANS = {
    "ا": "a", "أ": "a", "إ": "i", "آ": "a", "ب": "b", "ت": "t", "ث": "th",
    "ج": "j", "ح": "h", "خ": "kh", "د": "d", "ذ": "dh", "ر": "r", "ز": "z",
    "س": "s", "ش": "sh", "ص": "s", "ض": "d", "ط": "t", "ظ": "z", "ع": "a",
    "غ": "gh", "ف": "f", "ق": "q", "ك": "k", "ل": "l", "م": "m", "ن": "n",
    "ه": "h", "و": "w", "ي": "y", "ى": "a", "ئ": "y", "ؤ": "w", "ء": "",
    "ة": "h", "ﻻ": "la", "ﺓ": "h",
}


def _transliterate(s: str) -> str:
    out = []
    for ch in s:
        out.append(_AR_TRANS.get(ch, ch))
    return "".join(out)


def _haversine_m(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    """Great-circle metres between two points, for ranking by nearness."""
    r = 6371000.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dphi = math.radians(b_lat - a_lat)
    dl = math.radians(b_lon - a_lon)
    x = (math.sin(dphi / 2) ** 2
         + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2)
    return 2 * r * math.asin(min(1.0, math.sqrt(x)))


def _pt_seg_m(p_lat: float, p_lon: float,
              a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    """Metres from point P to segment AB (great-circle, planar projection).

    Extracted from ``GeocodeIndex.speed``, which had the only copy. ``reverse``
    needs the same measurement for the same reason `speed` does, and the two
    disagreeing about what "near this road" means is how a destination chip ends
    up naming a street the driver is not on.
    """
    if a_lat == b_lat and a_lon == b_lon:
        return _haversine_m(p_lat, p_lon, a_lat, a_lon)
    dx = b_lon - a_lon
    dy = b_lat - a_lat
    t = ((p_lon - a_lon) * dx + (p_lat - a_lat) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return _haversine_m(p_lat, p_lon, a_lat + t * dy, a_lon + t * dx)


def _hit_distance_m(lat: float, lon: float, h: "GeocodeHit") -> float:
    """How far a hit is from a point — to the SHAPE, not to one vertex.

    A way is indexed at one vertex (``ring[0]``), deliberately: the module
    docstring calls that *"good enough to drop a map pin / route target"*. For
    RANKING a reverse geocode it is not, and the difference is measurable. At a
    point in Mansoura the served tile puts `Al Urouba Street` **40.9 m** away,
    and `/reverse` returned `سكة عامر` first at **73.3 m**: the road that is
    genuinely nearest starts 200 m away, while a road that is merely nearby
    happens to begin around the corner. The nearest street was not in the top
    eight at all — and `/reverse` exists to answer "what is here".

    Ranks on the polyline when the hit carries one, and falls back to the point
    for features that are points.
    """
    geom = h.geom
    if geom and len(geom) >= 2:
        return min(_pt_seg_m(lat, lon, a[1], a[0], b[1], b[0])
                   for a, b in zip(geom, geom[1:]))
    return _haversine_m(lat, lon, h.lat, h.lon)


def _strip_vowels(s: str) -> str:
    """Drop a/e/i/o/u so Arabic romanizations with inserted vowels still match
    (e.g. user 'salwa' vs transliteration 'slwa')."""
    return "".join(c for c in s if c not in "aeiou")


# Free-flow default limit (km/h) by OSM highway class, applied ONLY when a way
# carries no usable `maxspeed`. In Qatar that is 90% of ways, so this table is
# what the speed badge shows almost everywhere — it is not an edge case.
#
# These are the OBSERVED MEDIANS of the Qatar extract, sample sizes attached,
# and they are the same numbers `vector_routing.speeds.CLASS_DEFAULT_KMH` costs
# routes with. They used to be a separate set of invented "conventional" values,
# and the two disagreed by up to 20 km/h in both directions: the badge showed
# 100 on an untagged trunk the router was costing at 80, and 30 on a residential
# street the router was costing at 50. Showing a driver a limit 20 km/h above
# the one the product itself believes is the wrong direction to be wrong in.
#
# Duplicated rather than imported: ADR-0003 keeps the engines from importing one
# another. `SpeedDefaultsAgreementTest` in both repos pins the two copies
# together, in the same way `TimeBandAgreementTest` pins the time bands.
CLASS_DEFAULT_KMH = {
    "motorway": 120,        # n=745
    "trunk": 80,            # n=1215
    "primary": 80,          # n=1724
    "secondary": 80,        # n=1819
    "tertiary": 50,         # n=2261
    "unclassified": 50,     # n=852
    "residential": 50,      # n=3375
    "living_street": 20,    # n=120
    "service": 40,          # n=319
    "track": 30,            # n=8 (median 70; a track is an unsealed desert road)
    "road": 40,             # no tagged samples at all
    "motorway_link": 60,    # n=363
    "trunk_link": 80,       # n=442
    "primary_link": 60,     # n=456
    "secondary_link": 60,   # n=169
    "tertiary_link": 50,    # n=116
}

# Highway classes a car cannot drive, used only when a feature predates the
# `car` flag that `vector_ingestion.classify` now writes at ingestion. Kept
# deliberately short: it is a fallback for old data, not a second classifier.
_NOT_DRIVABLE = frozenset({
    "footway", "path", "steps", "pedestrian", "cycleway", "bridleway",
    "corridor", "construction", "proposed", "platform", "raceway",
})


def _is_drivable(props) -> bool:
    """Can a car be on this way?

    The speed badge exists to answer "what is the limit where I am DRIVING", so
    a footpath must never be the road it measures against. Before this filter,
    a query at Souq Waqif matched a `pedestrian` way 0.7 m away, which has no
    class default — so the badge went dark in exactly the dense urban places a
    driver most wants it, and would have named a footpath as the current road.

    `car` is precomputed at ingestion (`vector_ingestion.classify`), which is
    the single source of truth; the class list is only for data baked before
    that flag existed.
    """
    car = props.get("car")
    if car is not None:
        return bool(car)
    return (props.get("highway") or "").strip().lower() not in _NOT_DRIVABLE


def _default_maxspeed(highway: str) -> "int | None":
    """Free-flow default limit (km/h) for an OSM highway class, or None.

    None means "we do not know", and the caller must show nothing rather than a
    number: a wrong limit on a speed badge is worse than an empty one.
    """
    return CLASS_DEFAULT_KMH.get((highway or "").strip().lower())


def _parse_maxspeed(s: "str | int | float | None") -> "int | None":
    """Parse an OSM `maxspeed` tag into km/h.

    Accepts a string ("50", "50 mph", "30 km/h", "signals") or an
    already-numeric value (the Doha basemap stores it as an int). Returns
    None for implicit/'signals'/'walk' limits.
    """
    if s is None:
        return None
    if isinstance(s, (int, float)):
        return int(s)
    s = str(s).strip().lower()
    if s in ("", "signals", "none", "walk", "variable"):
        return None
    import re

    m = re.search(r"(\d+)", s)
    if not m:
        return None
    val = int(m.group(1))
    if "mph" in s:
        return int(round(val * 1.60934))
    return val  # assume km/h (OSM default)


def _normalize(s: str) -> str:
    """Lowercase + strip Arabic/Latin diacritics for fuzzy substring match."""
    if not s:
        return ""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return s.lower().strip()


#: OSM name fields that are alternate spellings of the SAME place, in the
#: order they are worth showing. ``name:en`` first because it is the one the
#: rest of the product already prefers (``vector_routing.router.display_name``).
_OSM_NAME_FIELDS = ("name:en", "int_name", "official_name", "alt_name",
                    "short_name")


def _alt_names(props: Dict[str, Any]) -> List[str]:
    """Extra searchable spellings carried on a feature.

    Two sources, both of which are the same idea — one place, several written
    forms — and both of which have to be searchable or the place is invisible
    to whoever spells it the other way.

    **Overture** ships ``names.common``; the ingest script lands it in
    ``alt_names``.

    **OSM** ships ``name:en`` and friends, and this function used to ignore
    every one of them. That was not a small gap. In the Qatar extract 49,434
    features carry an Arabic ``name`` alongside a Latin ``name:en``, and only
    ``name`` was indexed — so "Khalifa Street", "Al Corniche Street", "Al Bidda
    Street" and "Woqod Hilal Gas Station" all returned NOTHING to a driver
    typing Latin, while the map beside the search box was labelling them in
    Latin the whole time (the tiles and ``/navigate`` both read ``name:en``).
    A further 1,363 features carry ``name:en`` and no ``name`` at all and were
    dropped from the index entirely, because the loader keys on ``name``.

    ``alt_name`` is semicolon-separated by OSM convention, so it is split.
    """
    out: List[str] = []
    alt = props.get("alt_names")
    if isinstance(alt, str):
        if alt.strip():
            out.append(alt)
    elif isinstance(alt, (list, tuple)):
        out.extend(a for a in alt if isinstance(a, str) and a.strip())
    for field in _OSM_NAME_FIELDS:
        val = props.get(field)
        if isinstance(val, str) and val.strip():
            # `alt_name` (and occasionally `official_name`) pack several
            # spellings into one tag: "Doha Corniche;Al Corniche".
            out.extend(part for part in (p.strip() for p in val.split(";"))
                       if part)
    # Preserve order, drop repeats: `name:en` and `int_name` are frequently
    # identical and there is no value in scoring the same string twice.
    seen = set()
    uniq = []
    for a in out:
        k = a.casefold()
        if k not in seen:
            seen.add(k)
            uniq.append(a)
    return uniq


def primary_name(props: Dict[str, Any]) -> Optional[str]:
    """The name to INDEX a feature under.

    ``name`` when it has one. Otherwise the best alternate — which is what
    rescues the 1,363 Qatar features tagged only ``name:en`` (Palm Road,
    Jasmine Road, Rawdat Rashed Interchange). They are real, named, drivable
    places; they were simply invisible to a loader that asked for one field.
    """
    name = props.get("name")
    if isinstance(name, str) and name.strip():
        return name
    for field in _OSM_NAME_FIELDS:
        val = props.get(field)
        if isinstance(val, str) and val.strip():
            return val.split(";")[0].strip() or None
    return None


# Match tiers, best first. The ordering is the ranking contract: an EXACT name
# match always outranks a prefix match, which always outranks a substring
# match. That is what stops "Villaggio Mall Access Road" from burying the mall
# itself when the user types the mall's name.
_T_EXACT = 0        # normalized name == query
_T_EXACT_LATIN = 1  # transliterated name == query
_T_PREFIX = 2       # name starts with query
_T_PREFIX_LATIN = 3
_T_SUB = 4          # query appears inside name
_T_SUB_LATIN = 5
_T_VOWELLESS = 6    # query matches after dropping vowels from both sides
# Token tiers. Everything above matches the query as ONE contiguous string,
# which is why "Woqod Hilal" found nothing while "Woqod" and "Hilal" each
# found plenty: the indexed name is "Woqod Hilal Gas Station" and no single
# substring of the query lines up with a user who types the words in a
# different order, or types one word too many.
#
# These three tiers rank strictly BELOW every contiguous match, so the
# existing ranking contract is untouched — an exact name still wins — and
# they only ever add results where there were none.
_T_TOK_ALL = 7      # every query word matched a word of the name
_T_TOK_NAME = 8     # every word of the NAME was matched (query is a superset)
_T_TOK_MOST = 9     # most query words matched
_T_MISS = 99

#: A query word shorter than this is not allowed to carry a token match on its
#: own — "a"/"al" would otherwise pull in half of Qatar.
_MIN_TOKEN = 2

#: Fraction of the query's USABLE words that must match for `_T_TOK_MOST`.
#:
#: Half, not 0.6, and measured against `usable` rather than against every word
#: the driver typed. Both were wrong in the same direction and the same query
#: showed it: "the station car wash" matched "Car Wash Al Zaaim" on two of four
#: words, 0.5, which fell under the old bar — so the one kind of place the
#: driver was asking for was the one kind the search refused to return, while
#: `_T_TOK_NAME` let "Carrier" and "Thejus" through on a single prefix. Two of
#: four substantive words is a real match; the tier below every contiguous one
#: is where it belongs, not off the end of the list.
_MOST_TOKEN_RATIO = 0.5

#: A word appearing in more than this share of the index is treated as common
#: and is not used to SEED the candidate set (it is still scored). "al",
#: "street" and "road" are each in roughly a third of Qatar's names.
_COMMON_TOKEN_FRACTION = 0.02

#: Hard ceiling on candidates, so a pathological query cannot turn the token
#: pass back into a full scan.
_MAX_TOKEN_CANDIDATES = 20000


def _tokens(s: str) -> Tuple[str, ...]:
    """Split a normalized name/query into searchable words.

    Punctuation is a separator, not a character: OSM writes "Al-Hilal
    Pharmacy", "T.G.I. Friday's @ Villaggio Mall" and "Al Baker Gardens - Al
    Hilal", and a driver types none of that.
    """
    if not s:
        return ()
    out = []
    cur = []
    for ch in s:
        if ch.isalnum():
            cur.append(ch)
        elif cur:
            out.append("".join(cur))
            cur = []
    if cur:
        out.append("".join(cur))
    return tuple(out)


def _token_match(q_tok: str, n_toks: Tuple[str, ...]) -> bool:
    """Does one query word match any word of a name?

    Prefix, not equality, so the match survives a half-typed last word — which
    is what makes an as-you-type box feel alive rather than binary.
    """
    if len(q_tok) < _MIN_TOKEN:
        return False
    for t in n_toks:
        if t.startswith(q_tok):
            return True
    return False


def _token_tier(q_toks: Tuple[str, ...], n_toks: Tuple[str, ...]) -> int:
    """Best token tier of one query against one name's words."""
    if not q_toks or not n_toks:
        return _T_MISS
    usable = [qt for qt in q_toks if len(qt) >= _MIN_TOKEN]
    if not usable:
        return _T_MISS
    matched = 0
    exact = 0
    covered = set()
    for qt in usable:
        hit = False
        hit_exact = False
        for k, nt in enumerate(n_toks):
            if nt == qt:
                hit = hit_exact = True
                covered.add(k)
            elif nt.startswith(qt):
                hit = True
                covered.add(k)
        if hit:
            matched += 1
            if hit_exact:
                exact += 1
    if matched == 0:
        return _T_MISS
    if matched == len(q_toks):
        return _T_TOK_ALL
    # The name is fully contained in the query. This is the "Green Tea Garden
    # Restaurant" case: the place is signed with four words, OSM recorded two
    # of them, and every word OSM has is one the driver typed.
    #
    # `len(covered) == len(n_toks)` alone is DEGENERATE for a one-word name,
    # and that is what made search unusable as soon as a driver typed a real
    # phrase. A single-word name needs exactly one query word to prefix-match
    # it to satisfy "every word of the name was matched" — so "the station car
    # wash" handed tier 8, the tier that means *the query is a superset of this
    # name*, to "Carrier", "Cartier", "Carrefour", "Caravan" and "Carbono" on
    # the three letters of "car", and to "Thejus" on "the". Tier 8 outranks
    # `_T_TOK_MOST`, so matching FEWER of the driver's words ranked higher:
    # searching "car wash" found car washes and adding two words that should
    # have narrowed it returned none at all.
    #
    # So the superset claim now has to be carried by something: either two
    # words of the query landed, or the one that did landed WHOLE. An exact
    # single-word match is the "starbucks city center" case and is real
    # evidence; a prefix of one short word is not evidence of anything.
    if len(covered) == len(n_toks) and (matched >= 2 or exact >= 1):
        return _T_TOK_NAME
    # Against `usable`, not `q_toks`: `matched` only ever counts usable words,
    # so dividing by every word the driver typed measured the fraction against
    # a denominator the numerator could not reach.
    if matched >= 2 and matched / len(usable) >= _MOST_TOKEN_RATIO:
        return _T_TOK_MOST
    return _T_MISS


def _coverage(q_toks: Tuple[str, ...], ctx: "frozenset") -> int:
    """How many of the driver's words this feature can account for.

    Counted over EVERY searchable word the feature carries — all its written
    names, its aliases, and the words of its address/locality/brand (see
    ``GeocodeHit.ctx_tokens``) — and counted per distinct QUERY word, so a
    record does not earn coverage twice for repeating one of them.

    This is the answer to "how much of what the driver typed does this record
    explain", and it is the FIRST ranking key. It has to be, because match
    tier cannot express it: the tiers are computed against one written form at
    a time, so a single-word street called "Lusail" claims ``_T_TOK_NAME``
    ("the query is a superset of my name") on one word of "moci lusail", while
    a ministry that answers BOTH words — one through its curated alias, one
    through its address — claims the same tier. The two then went to
    proximity, and proximity put the street the driver was ALREADY DRIVING ON
    above the place they were driving to, five times over.

    Prefix matching, like ``_token_match``: a half-typed last word must still
    count, or the ranking would lurch on every keystroke.
    """
    if not q_toks or not ctx:
        return 0
    n = 0
    for qt in q_toks:
        if len(qt) < _MIN_TOKEN:
            continue
        for t in ctx:
            if t.startswith(qt):
                n += 1
                break
    return n


def _tier(q: str, qv: str, key: str, latin: str,
          latin_v: Optional[str] = None) -> int:
    """Best (lowest) match tier of one query against one name/transliteration.

    ``latin_v`` is ``_strip_vowels(latin)``, precomputed by the caller. It used
    to be computed here, which meant building one throwaway string per indexed
    name per keystroke — 689,000 of them for a single query against Qatar, and
    the single largest cost in the scan.
    """
    if key == q:
        return _T_EXACT
    if latin == q:
        return _T_EXACT_LATIN
    if key.startswith(q):
        return _T_PREFIX
    if latin.startswith(q):
        return _T_PREFIX_LATIN
    if q in key:
        return _T_SUB
    if q in latin:
        return _T_SUB_LATIN
    if qv:
        if latin_v is None:
            latin_v = _strip_vowels(latin)
        if qv in latin_v:
            return _T_VOWELLESS
    return _T_MISS


class GeocodeHit:
    """A single geocoding result.

    Carries a precomputed ``key`` (normalized native name) and ``latin``
    (normalized transliteration) so :meth:`GeocodeIndex.search` never
    re-normalizes during a scan. With the Overture places layer the index is
    several times larger than the OSM-only one, and per-query normalization of
    every entry was the whole cost of a search.

    ``aliases`` holds extra searchable spellings — Overture's alternate-language
    names — as ``(native_key, latin_key)`` pairs. They match queries but never
    replace ``name`` in the response: the user sees the primary label.
    """

    __slots__ = ("name", "kind", "lon", "lat", "raw", "latin", "geom",
                 "key", "aliases", "conf", "is_road", "name_en", "token_sets",
                 "latin_v", "alias_v", "quality", "ctx_tokens", "dedupe_key")

    def __init__(self, name: str, kind: str, lon: float, lat: float,
                 raw: Optional[Dict[str, Any]] = None, latin: str = "",
                 geom: Optional[List[Tuple[float, float]]] = None,
                 aliases: Optional[List[str]] = None) -> None:
        self.name = name
        self.kind = kind
        self.lon = lon
        self.lat = lat
        self.raw = raw or {}
        # Latin transliteration of the name so Latin-typing users can match.
        self.latin = latin or _normalize(_transliterate(name))
        # Road polyline as [(lon, lat), ...] for point-to-segment distance.
        self.geom = geom
        self.key = _normalize(name)
        self.aliases = [(_normalize(a), _normalize(_transliterate(a)))
                        for a in (aliases or []) if a]
        # Vowel-stripped transliterations, precomputed once here rather than
        # rebuilt for every name on every keystroke. See `_tier`.
        self.latin_v = _strip_vowels(self.latin)
        self.alias_v = tuple(_strip_vowels(al) for _, al in self.aliases)
        self.is_road = kind == "road"
        # The Latin label, when the feature carries one. Kept separate from
        # `name` so the response can answer in the language the caller asked
        # for without the index having to hold two copies of every feature.
        en = self.raw.get("name:en")
        self.name_en = en.strip() if isinstance(en, str) and en.strip() else None
        # Every written form this feature can be matched by, pre-split into
        # words. Precomputed for the same reason `key` and `latin` are: the
        # scan must not re-tokenize 58,000 names on every keystroke.
        sets = {_tokens(self.key), _tokens(self.latin)}
        for akey, alatin in self.aliases:
            sets.add(_tokens(akey))
            sets.add(_tokens(alatin))
        self.token_sets = tuple(t for t in sets if t)
        # Every word this feature can be ACCOUNTED FOR by — the words of all
        # its written names PLUS the words of its address, locality and brand.
        #
        # This set is never used to MATCH (a query word in an address does not
        # make a feature a hit); it is used to measure COVERAGE, i.e. how much
        # of what the driver typed this record explains. The Ministry of
        # Commerce and Industry is indexed under an Arabic name, a curated
        # "MOCI" alias and `address="lusail City"`, so "moci lusail" is fully
        # accounted for by the record — while the street called Lusail
        # accounts for exactly one of the two words. Without the address the
        # ministry looked like a half match and lost to the street on
        # proximity. See `_coverage` and `search`.
        ctx = set()
        for toks in self.token_sets:
            ctx.update(toks)
        for field in ("address", "locality", "brand"):
            val = self.raw.get(field)
            if isinstance(val, str) and val.strip():
                ctx.update(_tokens(_normalize(val)))
                ctx.update(_tokens(_normalize(_transliterate(val))))
        self.ctx_tokens = frozenset(t for t in ctx if len(t) >= _MIN_TOKEN)
        # Identity for RESULT-LIST de-duplication (see
        # `GeocodeIndex._collapse_duplicates`). The Latin label when there is
        # one, so the seven ways tagged `name=لوسيل, name:en=Lusail` that make
        # up the Lusail approach are recognised as one road and not seven.
        self.dedupe_key = _normalize(self.name_en) if self.name_en else self.key
        try:
            self.conf = float(self.raw.get("confidence"))
        except (TypeError, ValueError):
            # No confidence (OSM basemap, learned names). Treat as fully
            # trusted: a surveyed OSM feature should never rank below a
            # low-confidence conflated one purely for lacking the field.
            self.conf = 1.0
        # The V1.1 canonical pipeline's driver-usefulness score (0..1). It is
        # a RANKING component: search tiers (relevance) always win, and this
        # breaks ties within a tier by distance, quality and confidence.
        # Records without one (pre-V1.1 raw basemaps) stay neutral at 0.5,
        # never penalized for lacking the field.
        try:
            self.quality = float(self.raw.get("quality_score"))
            if not (0.0 <= self.quality <= 1.0):
                self.quality = 0.5
        except (TypeError, ValueError):
            self.quality = 0.5

    def to_geojson(self, lang: Optional[str] = None) -> Dict[str, Any]:
        """GeoJSON for one hit, labelled for ``lang``.

        ``lang="en"`` prefers ``name:en`` for the DISPLAYED name and keeps the
        native one in ``name_local``, which is exactly what
        ``vector_routing.router.display_name`` does for the guidance banner.
        The two sit on the same screen: a search result that reads
        "محطة وقود الهلال" next to a map labelled "Woqod Hilal Gas Station" is
        the product disagreeing with itself, and it is what put an Arabic row
        in an English Recents list.

        Any other ``lang`` (including None) keeps the previous payload exactly,
        so existing callers are unaffected.
        """
        name = self.name
        local = None
        if lang == "en" and self.name_en:
            name, local = self.name_en, self.name
        props = {
            "name": name,
            "kind": self.kind,
            "label": name,
        }
        if local and local != name:
            props["name_local"] = local
        # Provenance and disambiguation for POI layers (Overture). Absent for
        # OSM basemap features, so existing clients see an unchanged payload.
        for field in ("source", "category", "brand", "locality"):
            val = self.raw.get(field)
            if val:
                props[field] = val
        # OSM POIs carry their category too, and nothing was reading it.
        #
        # `poi_class` is written by vector-ingestion for all 8,735 POIs in the
        # Qatar extract — restaurant, cafe, supermarket, pharmacy, hotel,
        # place_of_worship — and the response carried only `kind`, whose value
        # for every one of them is the literal string "poi". So a client had
        # nothing to show but jargon: a search for "souq" answered with four
        # rows reading "poi", and an area tagged `leisure` answered "park"
        # whether or not it is one.
        #
        # Emitted as `category`, reusing the field Overture POIs already use,
        # so a client needs one code path rather than two.
        #
        # `yes` is skipped deliberately: it is what `building=yes` and
        # `shop=yes` leave behind (860 of the POIs), and it means "this exists",
        # which is not a category. Saying nothing is better than saying "Yes".
        if "category" not in props:
            poi_class = self.raw.get("poi_class")
            if poi_class and poi_class != "yes":
                props["category"] = poi_class
        if self.raw.get("confidence") is not None:
            props["confidence"] = self.raw["confidence"]
        if self.raw.get("quality_score") is not None:
            props["quality_score"] = self.raw["quality_score"]
        if self.raw.get("usefulness") is not None:
            props["usefulness"] = self.raw["usefulness"]
        return {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [self.lon, self.lat]},
            "properties": props,
        }


def unnamed_road_label(highway: "str | None") -> str:
    """What to call a road that has no name.

    The index needs a label for every drivable way so it can be found at all,
    and the label it used was the OSM class: an unnamed `primary_link` became
    **"primary_link road"**, which then reached the driver through `/speed` and
    through search results.

    The vocabulary here deliberately matches ``vector_routing.router._road_label``
    word for word — "the slip road" is what that function says, and the two are
    describing the same road four millimetres apart on the same screen. They
    cannot import each other (separate services, no shared package), so
    ``tests/test_kinds.py`` pins them to the same strings instead, which is the
    same guard the two tile servers use for their glyph handling.
    """
    hw = (highway or "").strip().lower()
    if not hw:
        return "unnamed road"
    if hw.endswith("_link"):
        return "slip road"
    if hw in ("service", "track"):
        return "service road"
    return f"unnamed {hw.replace('_', ' ')} road"


class GeocodeIndex:
    """Substring-searchable index of named map features, plus a road index
    for speed-limit lookups."""

    def __init__(self) -> None:
        self._hits: List[GeocodeHit] = []      # named features (search/reverse)
        self._roads: List[GeocodeHit] = []      # drivable roads only (speed)
        # Lazy spatial index over `_roads`: cell (ix, iy) -> [road index, ...].
        # Built on the first speed() call, dropped whenever a road is added.
        self._road_grid: "Optional[Dict[Tuple[int, int], List[int]]]" = None
        # (normalised name, cell x, cell y) -> [(lon, lat), ...] for every
        # searchable entry already indexed, so the same place mapped twice is
        # only offered once. See _is_duplicate.
        self._dup_pts: "Dict[Tuple[str, int, int], List[Tuple[float, float]]]" = {}
        # Lazy inverted index over the words of `_hits`, for the token tiers.
        # `_tok_sorted` is every distinct word in sorted order so a prefix can
        # be resolved with two bisects instead of a scan; `_tok_postings` maps
        # a word to the hits carrying it. Rebuilt whenever `_hits` grows —
        # `load_places` appends after the first search in a warm process.
        self._tok_postings: "Optional[Dict[str, List[int]]]" = None
        self._tok_sorted: "Optional[List[str]]" = None
        self._tok_built_for = -1

    @staticmethod
    def _line(f) -> "Optional[List[Tuple[float, float]]]":
        geom = f.get("geometry") or {}
        gtype = geom.get("type")
        coords = geom.get("coordinates")
        if gtype == "LineString" and coords:
            return [(float(c[0]), float(c[1])) for c in coords]
        if gtype == "Polygon" and coords:
            ring = coords[0]
            return [(float(c[0]), float(c[1])) for c in ring]
        return None

    @classmethod
    def from_geojson(cls, fc: Dict[str, Any]) -> "GeocodeIndex":
        idx = cls()
        idx.extend_from_geojson(fc)
        return idx

    #: How close two same-named places must be to count as one place.
    #:
    #: 250 m, and it is a real distance rather than a side effect of bucket
    #: alignment. Villaggio Mall's node and its building centroid are **175 m**
    #: apart (measured), because the building is enormous — so a radius chosen
    #: to look tidy at 100 m would have left the duplicate on screen, which is
    #: exactly what the first version of this did.
    #:
    #: The trade-off is stated rather than hidden: two genuinely different
    #: businesses with the SAME name within 250 m of each other — two outlets of
    #: one chain inside one airport, say — will be merged. That is rare, and it
    #: is the better error: a driver who sees one entry for a place that has two
    #: doors has lost nothing, while a driver who sees the same mall listed
    #: twice has been told the search is unreliable.
    DUP_RADIUS_M = 250.0

    #: Bucket size for the duplicate hash, in decimal degrees (~110 m).
    _DUP_CELL = 0.001

    def _is_duplicate(self, name_key: str, lon: float, lat: float) -> bool:
        """Has this same name already been indexed at essentially this spot?

        The same real place is very often mapped twice in OSM: once as a node
        for the business and once as the building polygon it occupies. Both are
        legitimate map data and both now reach the index, so a search for
        "villaggio" answered with **VILLAGGIO MALL, 8.7 km away** twice —
        measured on the S24 after POI areas were added.

        Deduplicated by name AND POSITION, never by name alone: Qatar has seven
        Carrefours and they are all real.

        Bucketed, then measured. The index holds ~58,000 entries and an O(n^2)
        scan is 3.4 billion comparisons at load, so candidates come from a
        coordinate hash; but the ACCEPTANCE test is a real distance against
        [DUP_RADIUS_M], not bucket membership. The first version compared
        buckets alone and missed the 175 m Villaggio pair because the two points
        rounded two cells apart rather than one — a radius that depends on where
        a point happens to fall inside its cell is not a radius.
        """
        if not name_key:
            return False
        cell = self._DUP_CELL
        ix, iy = int(lon / cell), int(lat / cell)
        # cos(lat) scales longitude to metres; at Qatar's latitude a degree of
        # longitude is ~100 km, not 111.
        kx = 111_320.0 * math.cos(math.radians(lat))
        # +/-3 cells covers DUP_RADIUS_M with margin at this latitude, so the
        # radius is what decides and the bucket size is only an index.
        for dx in range(-3, 4):
            for dy in range(-3, 4):
                for (plon, plat) in self._dup_pts.get((name_key, ix + dx, iy + dy), ()):
                    mx = (lon - plon) * kx
                    my = (lat - plat) * 110_574.0
                    if (mx * mx + my * my) <= self.DUP_RADIUS_M ** 2:
                        return True
        self._dup_pts.setdefault((name_key, ix, iy), []).append((lon, lat))
        return False

    def extend_from_geojson(self, fc: Dict[str, Any]) -> int:
        """Index another FeatureCollection **into this index**.

        This is how the Overture places layer joins the OSM basemap: it ADDS
        entries, it does not replace them. Returns how many searchable entries
        were added, so a caller can log the before/after (the whole point of
        the exercise is that the number goes up).
        """
        idx = self
        cls = type(self)
        before = len(idx._hits)
        for f in fc.get("features", []):
            props = f.get("properties") or {}
            kind = props.get("kind")
            geom = f.get("geometry") or {}
            gtype = geom.get("type")
            coords = geom.get("coordinates")
            if gtype == "Point" and coords:
                lon, lat = float(coords[0]), float(coords[1])
            elif gtype in ("LineString", "Polygon") and coords:
                ring = coords[0] if gtype == "Polygon" else coords
                lon, lat = float(ring[0][0]), float(ring[0][1])
            else:
                continue
            # `primary_name`, not `props["name"]`: a feature tagged only
            # `name:en` is still a named place and belongs in the index.
            name = primary_name(props)
            if kind == "road":
                # Index every DRIVABLE road (named or not) for speed lookups.
                # Unnamed roads get a DRIVER'S description, not their OSM class
                # — see `unnamed_road_label`. Pedestrian ways are excluded: see
                # `_is_drivable`. They are still indexed for search below when
                # they have a name — a footpath is a real place, it is just not
                # a road you drive.
                if _is_drivable(props):
                    rname = name or unnamed_road_label(props.get("highway"))
                    # One polyline object, shared by both hits for the same way.
                    # `reverse` needs it to rank a road by its shape rather than
                    # by one of its vertices (`_hit_distance_m`), and building a
                    # second copy per named road would double the memory the
                    # road index already pays.
                    line = cls._line(f)
                    idx._roads.append(GeocodeHit(
                        rname, "road", lon, lat, props, geom=line))
                    idx._road_grid = None   # invalidate the spatial index
                else:
                    line = None
                # Named roads also show up in search/reverse. NOT deduplicated:
                # a long road is legitimately many ways with the same name, and
                # collapsing them would leave one arbitrary segment of Al
                # Corniche in the index and break reverse geocoding along the
                # rest of it.
                if name:
                    idx._hits.append(GeocodeHit(name, kind or "feature", lon, lat,
                                                props, geom=line,
                                                aliases=_alt_names(props)))
            else:
                if not name:
                    continue
                if idx._is_duplicate(_normalize(name), lon, lat):
                    continue
                idx._hits.append(GeocodeHit(name, kind or "feature", lon, lat,
                                            props, aliases=_alt_names(props)))
        return len(idx._hits) - before

    def size(self) -> int:
        return len(self._hits)

    def reverse(self, lat: float, lon: float, limit: int = 3) -> List["GeocodeHit"]:
        """Nearest named features to a point ("What's here?").

        Ranked by distance to the feature's SHAPE where the index holds one —
        see :func:`_hit_distance_m`, which is here because ranking ways by a
        single vertex put the wrong street in front of the driver.
        """
        ranked = [(_hit_distance_m(lat, lon, h), h) for h in self._hits]
        ranked.sort(key=lambda t: t[0])
        return [h for _, h in ranked[:limit]]

    # Spatial-index cell size in degrees. ~0.005 deg is ~505 m of longitude at
    # Doha's latitude and ~556 m of latitude, so every road within the 60 m
    # trust radius below is guaranteed to be in the query cell or one of its
    # eight neighbours. The 3x3 probe is therefore EXACT for this query, not an
    # approximation that trades correctness for speed.
    _ROAD_CELL = 0.005

    def _build_road_grid(self) -> None:
        cell = self._ROAD_CELL
        grid: "Dict[Tuple[int, int], List[int]]" = {}
        for i, h in enumerate(self._roads):
            geom = h.geom
            pts = geom if geom and len(geom) >= 2 else [(h.lon, h.lat)]
            # A road is registered in every cell its BOUNDING BOX touches, so a
            # long motorway segment crossing several cells is found from any of
            # them. Registering only its vertices would miss a query beside the
            # middle of a 3 km straight.
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            for gx in range(int(min(xs) / cell), int(max(xs) / cell) + 1):
                for gy in range(int(min(ys) / cell), int(max(ys) / cell) + 1):
                    grid.setdefault((gx, gy), []).append(i)
        self._road_grid = grid

    def speed(self, lat: float, lon: float) -> "dict":
        """Nearest drivable road's posted/known speed limit ("What's the limit here?").

        Returns ``{"maxspeed_kmh": int|None, "name": str|None,
        "distance_m": float, "source": "tag"|"default"}``.

        ``source`` matters and the client must honour it: ``"tag"`` is a
        surveyed limit from OSM, ``"default"`` is the free-flow median for the
        road's class. The viewer renders the second one differently, because
        presenting an inferred number as a posted sign is the kind of confident
        wrong answer a driver acts on.

        This used to scan all 183,738 indexed ways per call and took **1.7 s**,
        which the viewer polls once per GPS fix — a queue that never drains at
        1 Hz, so the badge lagged the car by seconds. It now consults a 3x3
        block of a lazily built spatial grid, which is exact for the 60 m trust
        radius rather than an approximation.
        """
        # The two measurements below are the module-level helpers, not copies:
        # `reverse` ranks by the same rule (`_hit_distance_m`), and a second
        # definition of "near this road" is how the two endpoints start
        # disagreeing about which street the driver is on.
        hav = _haversine_m
        pt_seg = _pt_seg_m

        if self._road_grid is None:
            self._build_road_grid()
        cell = self._ROAD_CELL
        cx, cy = int(lon / cell), int(lat / cell)
        candidates: "set[int]" = set()
        for gx in range(cx - 1, cx + 2):
            for gy in range(cy - 1, cy + 2):
                candidates.update(self._road_grid.get((gx, gy), ()))

        best = None
        best_d = float("inf")
        for i in candidates:
            h = self._roads[i]
            geom = h.geom
            if geom and len(geom) >= 2:
                d = min(pt_seg(lat, lon, a[1], a[0], b[1], b[0])
                       for a, b in zip(geom, geom[1:]))
            else:
                d = hav(lat, lon, h.lat, h.lon)
            if d < best_d:
                best_d, best = d, h
        # Only trust a limit if we are actually near that road.
        if best is None or best_d > 60.0:
            return {"maxspeed_kmh": None, "name": None,
                    "distance_m": None, "source": None}
        raw = best.raw or {}
        src = "tag"
        ms = _parse_maxspeed(raw.get("maxspeed", ""))
        if ms is None:
            ms = _default_maxspeed(raw.get("highway", ""))
            src = "default"
        if ms is None:
            # Neither a tag nor a class default. Say we do not know rather than
            # naming a road with a blank limit beside it.
            src = None
        # The road's REAL name, from the raw OSM properties, or nothing.
        #
        # `best.name` is the INDEX's name for the hit, and for an unnamed road
        # that is a placeholder this module invented so the road could be
        # indexed at all. Returning it made the placeholder a driver-facing
        # answer: reported from the S24, the road-you-are-on readout across the
        # bottom of the map said **"primary_link road"**.
        #
        # "Which road am I on?" has three possible answers and only two of them
        # are a name: the road's name, its route number, or nothing. The client
        # already combines `name` and `ref` and already renders nothing when
        # both are absent (`UiState.roadLabel`), so a null here produces "C
        # Ring" on a numbered slip road and silence on an unnamed one — both of
        # which are true, unlike a database class.
        road_name = (raw.get("name") or "").strip() or None
        return {
            "maxspeed_kmh": ms,
            "name": road_name,
            # `name:en` and `ref` were already sitting in `raw` for every road
            # this function has ever answered about, and nothing read them.
            #
            # They are here because the answer to "which road am I on?" is the
            # by-product of a lookup the client ALREADY makes once every 150 m
            # for the speed-limit sign. 56,357 of the indexed features carry
            # `name:en`, so the readout can be in the driver's language for
            # free, and `ref` ("C Ring", "Q3") is what a driver actually
            # matches against the sign above the carriageway.
            #
            # Both are additive: a caller that does not ask for them sees the
            # response it saw before, so the web client needs no change.
            "name_en": raw.get("name:en") or None,
            "ref": raw.get("ref") or None,
            "highway": raw.get("highway") or None,
            "distance_m": round(best_d, 1),
            "source": src,
        }

    def _ensure_tokens(self) -> None:
        """Build (or rebuild) the inverted word index over `_hits`."""
        if self._tok_postings is not None and self._tok_built_for == len(self._hits):
            return
        postings: Dict[str, List[int]] = {}
        for i, h in enumerate(self._hits):
            seen = set()
            for toks in h.token_sets:
                for t in toks:
                    if len(t) >= _MIN_TOKEN and t not in seen:
                        seen.add(t)
                        postings.setdefault(t, []).append(i)
        self._tok_postings = postings
        self._tok_sorted = sorted(postings)
        self._tok_built_for = len(self._hits)

    def _postings_for_prefix(self, prefix: str) -> List[int]:
        """Hits whose words begin with ``prefix``, via two bisects."""
        import bisect
        toks = self._tok_sorted or []
        postings = self._tok_postings or {}
        lo = bisect.bisect_left(toks, prefix)
        hi = bisect.bisect_left(toks, prefix[:-1] + chr(ord(prefix[-1]) + 1))
        out: List[int] = []
        for t in toks[lo:hi]:
            out.extend(postings.get(t, ()))
        return out

    def _token_candidates(self, q_toks: Tuple[str, ...]) -> "set":
        """Hits worth scoring at the token tiers.

        Seeded from the query's RAREST words, not all of them. "Al Bidda
        Street" is three words, two of which ("al", "street") begin a third of
        every name in Qatar: taking the union of all three produced 144,000
        candidates and a 700 ms search. "bidda" alone produces a handful and
        finds exactly the same street.

        A word is only skipped for being common if a rarer one is carrying the
        query, so a query made entirely of common words ("the road") still
        answers rather than returning nothing.
        """
        self._ensure_tokens()
        usable = [qt for qt in q_toks if len(qt) >= _MIN_TOKEN]
        if not usable:
            return set()
        ranked = sorted(((len(self._postings_for_prefix(qt)), qt)
                         for qt in usable), key=lambda t: t[0])
        cap = max(1, int(len(self._hits) * _COMMON_TOKEN_FRACTION))
        out: set = set()
        for n, qt in ranked:
            if out and n > cap:
                # Already seeded by something rarer; this word would only add
                # noise it cannot outrank.
                break
            out.update(self._postings_for_prefix(qt))
            if len(out) > _MAX_TOKEN_CANDIDATES:
                break
        return out

    #: How far apart two same-named RESULTS may be and still be one answer.
    #:
    #: Two numbers, because a POI is a point and a road is not. 250 m matches
    #: [DUP_RADIUS_M], the radius the loader already uses for places. Roads get
    #: 1500 m and are chained (a segment merges if it is within the radius of
    #: ANY segment already merged into the group), because a named road reaches
    #: the index as many ways: "لوسيل" is SEVEN drivable ways spread over
    #: 1.34 km (measured), and they are one road.
    #:
    #: Chaining at 1500 m is what separates "one road, many ways" from "two
    #: roads that share a name": the eighth way called لوسيل is a residential
    #: street **91 km** north near Al Ruwais (measured), and it is a different
    #: road that keeps its own row.
    _RESULT_DUP_M = 250.0
    _RESULT_DUP_ROAD_M = 1500.0

    def _collapse_duplicates(self, hits: List[GeocodeHit],
                             limit: int = 0) -> List[GeocodeHit]:
        """One row per place, over an ALREADY-RANKED list.

        A search for "moci lusail" from the Lusail approach answered with five
        rows that all read "Lusail" and all pointed at the same road, and the
        panel shows about five rows before it scrolls — so the ministry the
        driver was actually asking for was off the bottom of the screen, and
        they used Google Maps instead (reported from the drive).

        This is a RESULT-LIST collapse, not a data deletion. Nothing leaves the
        index: reverse geocoding and `/speed` still see every way of the road,
        which is exactly why the loader refuses to deduplicate roads in the
        first place ("a long road is legitimately many ways with the same
        name"). What is removed is the REPETITION in an answer — a driver told
        the same thing five times has been told the search is broken.

        Grouped by (display name, kind, category) and separated by distance, so
        Qatar's seven Carrefours, and two genuinely different roads that share a
        name, all keep their own rows. The list arrives ranked, so the entry
        kept is always the best-ranked member of its group.
        """
        kept: List[GeocodeHit] = []
        # group key -> points of every hit already folded into that group
        seen: "Dict[Tuple[str, str, str], List[Tuple[float, float]]]" = {}
        for h in hits:
            # The list is ranked, so once `limit` rows have survived nothing
            # further can reach the answer. A one-letter query matches most of
            # Qatar, and measuring the whole tail of it would be work spent on
            # rows no one will ever see.
            if limit > 0 and len(kept) >= limit:
                break
            cat = h.raw.get("category") or h.raw.get("poi_class") or ""
            gkey = (h.dedupe_key, h.kind, str(cat))
            if not h.dedupe_key:
                kept.append(h)
                continue
            radius = self._RESULT_DUP_ROAD_M if h.is_road else self._RESULT_DUP_M
            pts = seen.get(gkey)
            if pts is None:
                seen[gkey] = [(h.lat, h.lon)]
                kept.append(h)
                continue
            if any(_haversine_m(h.lat, h.lon, plat, plon) <= radius
                   for plat, plon in pts):
                # Same name, same kind, same spot: already answered. Fold the
                # position in so a chain of road segments stays one group.
                pts.append((h.lat, h.lon))
                continue
            pts.append((h.lat, h.lon))
            kept.append(h)
        return kept

    def search(self, query: str, limit: int = 20,
               near: "Optional[Tuple[float, float]]" = None) -> List[GeocodeHit]:
        """Rank named features against ``query``.

        Ordering, strongest signal first:

        0. **Token coverage** — how many of the driver's words the record can
           account for, across its names, aliases AND its address/locality
           (see ``_coverage``). A record that explains two words of a two-word
           query can never rank below one that explains a single word, however
           close the second one is. Proximity breaks ties between comparable
           matches; it must not overturn match quality, which is what put five
           rows reading "Lusail" above the Ministry of Commerce and Industry
           for a driver who typed "moci lusail" on the Lusail approach.
        1. **Match tier** — exact beats prefix beats substring, on the native
           name or its Latin transliteration (see ``_tier``), and every one of
           those beats a word-level match (see ``_token_tier``). This is the
           rule that makes a POI named exactly what the user typed outrank a
           road that merely contains the string.
        2. **Distance from ``near``** — of two equally good matches, the one
           the driver could reach is the answer. Qatar has a Woqod station in
           most districts; ranking them by name length served a driver in Doha
           the one in Dukhan, 30 km west. Omitted (all zero) when the caller
           sends no position, so ordering is unchanged for callers that don't.
        3. **Name length** — the shorter of two equally-good matches is the
           more specific answer ("City Center" over "City Center Car Park B").
        4. **Roads last** — a tie between a place and a road goes to the place.
           Someone typing a business name wants the business; someone typing a
           road name usually matches the road at a better tier anyway, so this
           only fires on genuine ties.
        5. **Confidence, then name** — Overture ships a per-place confidence;
           features without one (OSM, learned) count as 1.0 so they are never
           demoted for lacking the field. Name is the final tie-break purely so
           results are deterministic.

        The ranked list is then collapsed so one place occupies one row (see
        ``_collapse_duplicates``), BEFORE ``limit`` is applied — the rows a
        duplicate was using are given back to real answers rather than lost.
        """
        q = _normalize(query)
        if not q:
            return []
        qv = _strip_vowels(q)
        q_toks = _tokens(q)
        best: Dict[int, int] = {}

        # Pass 1: contiguous match over every hit. Unchanged, and still the
        # only pass that can produce the top tiers.
        for i, h in enumerate(self._hits):
            tier = _tier(q, qv, h.key, h.latin, h.latin_v)
            if tier == _T_MISS and h.aliases:
                # An alternate-language spelling counts as a match, but never
                # better than the primary name would have scored.
                av = h.alias_v
                for k, (akey, alatin) in enumerate(h.aliases):
                    tier = min(tier, _tier(q, qv, akey, alatin, av[k]))
                    if tier == _T_EXACT:
                        break
            if tier != _T_MISS:
                best[i] = tier

        # Pass 2: word-level match, over candidates only. A hit already matched
        # contiguously keeps its better tier.
        if len(q_toks) > 1 or (q_toks and len(q_toks[0]) >= _MIN_TOKEN):
            for i in self._token_candidates(q_toks):
                if best.get(i, _T_MISS) <= _T_VOWELLESS:
                    continue
                h = self._hits[i]
                tier = _T_MISS
                for toks in h.token_sets:
                    tier = min(tier, _token_tier(q_toks, toks))
                    if tier == _T_TOK_ALL:
                        break
                if tier < best.get(i, _T_MISS):
                    best[i] = tier

        # Distinct usable query words, for coverage. Deduplicated so a driver
        # who types a word twice cannot inflate a record's coverage with it.
        q_usable: Tuple[str, ...] = tuple(dict.fromkeys(
            qt for qt in q_toks if len(qt) >= _MIN_TOKEN))

        scored: List[Tuple[int, float, float, float, int, int, float, str,
                           GeocodeHit]] = []
        for i, tier in best.items():
            h = self._hits[i]
            # A contiguous match carries the WHOLE query as one string, so by
            # construction it accounts for every word in it. Saying so
            # explicitly keeps the old contract intact: an exact/prefix/
            # substring match can never be demoted beneath a word-level one
            # for "missing" a word it plainly contains.
            if tier <= _T_VOWELLESS:
                cov = len(q_usable)
            else:
                cov = _coverage(q_usable, h.ctx_tokens)
            dist = _haversine_m(near[0], near[1], h.lat, h.lon) if near else 0.0
            # Quality blends into EFFECTIVE distance: a q=1.0 destination is
            # treated as 4x closer than a q=0 one at the same spot, so
            # relevance stays dominant (tier first) while a weak generic
            # match loses to a strong destination near the driver. Never a
            # hard filter — a low-quality exact match still outranks a
            # high-quality substring match, because tier precedes it.
            eff_dist = dist * (0.25 + 0.75 * (1.0 - max(0.0, min(1.0, h.quality))))
            scored.append((-cov, tier, eff_dist, -h.quality, len(h.key),
                           1 if h.is_road else 0, -h.conf, h.key, h))
        scored.sort(key=lambda t: t[:8])
        hits = self._collapse_duplicates([t[-1] for t in scored], limit=limit)
        return hits[:limit] if limit > 0 else hits
