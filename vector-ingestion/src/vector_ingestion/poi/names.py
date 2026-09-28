"""Name-quality detection.

Two layers, deliberately separated:

**Hard signals** (``hard_name_signals``) are high-precision, census-verified
patterns that mark a record as NOT a driver destination regardless of its
category — worker housing, bare building identifiers. Every term below was
counted against the actual Qatar corpus before being added; the bar is "no
restaurant, hotel, clinic or school carries this term".

**Soft quality** (``name_quality``) feeds the usefulness score. It never
excludes anything by itself; it measures how searchable/specific a name is.

Contextualisation: "house", "villa", "building", "centre", "mall" alone must
NEVER mark a POI bad — "Turkish Grill House" is a restaurant, "Holiday Villa"
is a hotel. The hard signals are therefore narrow, and the accommodation term
is exempted for LODGING-family records (a legitimately named hotel is not
worker housing).
"""

from __future__ import annotations

import re
import unicodedata
from typing import List, Optional, Tuple

#: Stopwords stripped when comparing names for duplicate pairing/typo
#: detection. Geographical filler ("doha", "qatar") is deliberately included:
#: "Holy Rosary Church of Doha Qatar.." differs from the canonical church only
#: by filler.
STOPWORDS = frozenset({
    "of", "the", "and", "for", "in", "at", "to", "on", "de", "la", "el",
    "le", "del", "doha", "qatar", "city", "st", "saint", "near", "by",
    "along", "wll", "ltd", "llc", "co", "est", "center", "centre",
    # religious-institution filler: "Our Lady of the Rosary" = "Holy Rosary"
    "our", "lady",
    # Arabic filler, the direct parallel of "of/the/doha/qatar" above. Kept
    # deliberately short: only words that carry no identity of their own.
    "\u0641\u064a", "\u0645\u0646", "\u0639\u0644\u064a", "\u0627\u0644\u062f\u0648\u062d\u0647",
    "\u0642\u0637\u0631", "\u062f\u0648\u0644\u0647", "\u0648", "\u0627\u0644",
})

#: Accommodation spellings seen in the corpus (29 OSM + 31 Overture records),
#: Latin only. The AUDIT (V1.1) removed two false-positive sources from this
#: family: "quarters" caught "Dukhan Bank (Head Quarters)", and the Arabic
#: markers were substring matches that fired inside transliterations ("The
#: Skin Art Medical Center مركز ذَسكنآرت" contains the letters س-ك-ن for
#: "skin"). Latin accommodation words are precise; Arabic is handled
#: separately with proper word boundaries, and only for non-destination
#: families.
_ACCOMMODATION = re.compile(
    r"\baccom+m?odat+ions?\b|\bacc?omodat+ions?\b|"
    r"\baccm?odat+ions?\b|\bworkers?[' ]?(?:accommodation|camp|housing)\b|"
    r"\blabou?r[- ]?camp\b|\bstaff[- ]?housing\b|"
    r"\bbachelor(?:s)?[- ]?(?:accommodation|housing)\b",
    re.IGNORECASE,
)

#: Arabic residence markers, with ARABIC WORD BOUNDARIES. The audit defect:
#: a bare "سكن" substring matched inside the transliteration "ذَسكنآرت"
#: ("The SkinArt"). A marker must be its own Arabic word — not preceded or
#: followed by an Arabic letter — so transliterations that merely contain the
#: letters never fire. Applied only to non-destination families (see
#: ``hard_name_signals``).
_ARABIC_LETTER = "[\u0621-\u064A]"
_ARABIC_ACCOMMODATION = re.compile(
    rf"(?<!{_ARABIC_LETTER})(?:سكن|مسكن|مساكن|إقامة|إسكان)(?!{_ARABIC_LETTER})"
    rf"|(?<!{_ARABIC_LETTER})سكن عمال(?!{_ARABIC_LETTER})",
    re.IGNORECASE,
)

#: Bare building identifiers: "Bldg 42", "A2 Bldg, Barwa City", "Sks 1",
#: "Building 1", "Y Building 2". V1.1: ONLY applied when the family is
#: UNKNOWN (classless named buildings), so a genuine institution building
#: with a number — "Katara Art Centre Building 5" (ATTRACTION), "Qatar
#: Petroleum IT Building 41" — is never destroyed by the rule.
_BUILDING_ID = re.compile(
    r"\bbldg\b|\bbldg\.|\bbuilding[ -]?\d+|\bblock[ -]?\d|\bflat[ -]?\d",
    re.IGNORECASE,
)

#: Names that are only a dimensionless unit: "B1", "Sks 1", "Khan1", or
#: single letters/digits ("b", "o", "696") — phantom/user-dropped pins.
_SHORT_ID = re.compile(r"^[a-z0-9]{1,2}$|^\d{1,3}$"
                       r"|^(?:[a-z]{1,4}[ -]?\d{1,3}|\d{1,3}[ -]?[a-z]{1,3})$",
                       re.IGNORECASE)

#: A bare nation/region name is never a POI. "Doha" as a motel name, "Qatar"
#: in front of nothing else, "Street 50" — measured across the corpus.
_BARE_PLACE = re.compile(r"^(?:doha|qatar)$|^street[ -]?\d+$", re.IGNORECASE)

#: Registration/office-shaped names (fix 7): OSM classless named buildings
#: whose name is a company-registration string — "Taleb Trading Company",
#: "High Level Trading & Contracting & Services", "Sana shark trading
#: company". These are the audit's ~330-400 surviving junk records. Applied
#: ONLY to UNKNOWN-family records, and never when the name carries a
#: destination keyword.
_TRADING = re.compile(
    r"\b(?:trading|contracting|enterprises?|industries?|maintenance|logistics|"
    r"cargo|shipping|freight|moving|cleaning|interiors?|interior design|"
    r"decor(?:ation)?|general cont|w\.?l\.?l|\bllc\b|\bltd\b|\best\.?\b)\b",
    re.IGNORECASE,
)
#: Strong legal-registration markers: when present, the registration pattern
#: applies to any family, not just UNKNOWN ("New Way Trading & Co. W.L.L -
#: Plumbing & Bldg. Materials" tagged hardware must still be excluded).
_REGISTRATION_MARKER = re.compile(r"w\.?l\.?l|\bllc\b|\bltd\b", re.IGNORECASE)
#: Destination words that override the registration shape (a "Motors & Trading"
#: company with a showroom is a real place to drive to).
_TRADING_EXEMPT = re.compile(
    r"motor|car|auto|showroom|garage|centre|center|restaurant|cafe|food|hotel|"
    r"school|clinic|hospital|mall|hypermarket|market|supermarket|salon|spa|gym|"
    r"station|travel|exchange|bank|souq|bakery|pharmacy|optic|medical|laboratory|"
    r"jewell|furniture|electronics|mobile|flower|gift|toys|sports|fitness|workshop|"
    r"tower|hotel", re.IGNORECASE,
)

#: Families where the Arabic residence markers are tested. The only measured
#: false positive was a TRANSLITERATION collision inside a HEALTHCARE name
#: ("The Skin Art Medical Center مركز ذَسكنآرت": سكن is the letters of
#: "skin"); every consumer-facing destination family is therefore exempt,
#: while classless, attraction-mis-tagged and non-destination records (a
#: memorial node named "سكن عين خالد" = Ain Khaled residence) are caught.
_ARABIC_MARKER_FAMILIES = frozenset({
    "UNKNOWN", "RESIDENTIAL", "OFFICE", "INDUSTRY", "MAP_FURNITURE",
    "INFRASTRUCTURE", "ATTRACTION", "SPORT", "PARKING", "DESTINATION",
})


def hard_name_signals(name: "str | None", family: str) -> List[str]:
    """High-precision name signals that exclude a record from ordinary POIs.

    Returns human-readable signal names (empty list == name is fine).

    ``family`` contextualises the accommodation test: LODGING records can
    legitimately be named "..." without meaning worker housing (the beds-and-
    breakfast family is not the accommodation dump) — but the LODGING family
    itself is kept because it is a real destination family.
    """
    if not name:
        return []
    hits = []
    if _ACCOMMODATION.search(name):
        # "Executive Apartments" (a hotel) must not be killed; a LODGING
        # record with a hotel-style word in its name is a real hotel.
        if family == "LODGING" and re.search(r"\bhotel\b|\bresort\b|\binn\b|\bsuites?\b",
                                             name, re.IGNORECASE):
            pass
        else:
            hits.append("accommodation")
    if family in _ARABIC_MARKER_FAMILIES and _ARABIC_ACCOMMODATION.search(name):
        hits.append("accommodation")
    # building id / registration anti-patterns. A real institution named
    # "... Building 7" (Katara Art Centre Building 5, 25 chars) carries its
    # family and must survive; a BARE building id ("Sks 108 Bldg." tagged
    # hotel, "al rayyan bldg" tagged attraction, "Building 1") is junk
    # whatever its family — short names cannot be institutional buildings.
    if (_BUILDING_ID.search(name)
            and (family == "UNKNOWN" or len(name.strip()) <= 18)):
        hits.append("building-id")
    toks = normalize_name(name).split()
    if (len(toks) >= 3 and _TRADING.search(name)
            and not _TRADING_EXEMPT.search(name)
            and (family == "UNKNOWN" or _REGISTRATION_MARKER.search(name))):
        hits.append("registration")
    if _SHORT_ID.match((name or "").strip()) and len(name.strip()) <= 6:
        hits.append("short-id")
    if _BARE_PLACE.match((name or "").strip()):
        hits.append("bare-place-name")
    return hits


#: Arabic letters kept by ``normalize_name``. Arabic punctuation (،, ؟) and
#: the tatweel elongation mark are NOT here: they are separators or
#: decoration, never part of a word.
_ARABIC_KEEP = "\u0621-\u063a\u0641-\u064a\u066e-\u06d3\u06fa-\u06ff"

#: Arabic orthographic variants that spell the same word. NFKD + combining
#: strip already unifies the hamza-bearing alefs (أ إ آ -> ا, ؤ -> و, ئ -> ي);
#: these four do not decompose and must be folded by hand, along with the
#: Arabic-Indic digits.
_ARABIC_FOLD = {
    ord("\u0629"): "\u0647",   # teh marbuta   ة -> ه
    ord("\u0649"): "\u064a",   # alef maksura  ى -> ي
    ord("\u0640"): None,       # tatweel       ـ -> (removed)
    ord("\u06cc"): "\u064a",   # farsi yeh     ی -> ي
    ord("\u06a9"): "\u0643",   # keheh         ک -> ك
}
_ARABIC_FOLD.update({0x0660 + i: str(i) for i in range(10)})   # ٠-٩ -> 0-9
_ARABIC_FOLD.update({0x06f0 + i: str(i) for i in range(10)})   # ۰-۹ -> 0-9

_NORM_DROP = re.compile(f"[^a-z0-9{_ARABIC_KEEP}]+")


def normalize_name(name: "str | None") -> str:
    """Lowercase, strip diacritics/punctuation, collapse whitespace.

    **Arabic is a first-class script here.** The original implementation kept
    only ``[a-z0-9]``, which erased every Arabic-script name to the empty
    string. Measured on the deployed 26,096-record canonical corpus
    (2026-09-14) that hit **1,687 records (6.5%)**, and the consequences were
    systematic rather than cosmetic:

    * ``name_quality`` saw zero tokens and returned 0.0 for all of them, so
      Arabic-named places scored a mean ``quality_score`` of 0.465 against
      0.670 for Latin-named ones;
    * only 376 of 1,671 Arabic-only records (22.5%) reached the DESTINATION
      usefulness bucket, against 19,598 of 23,635 (82.9%) of the Latin ones —
      an Arabic-named restaurant was ranked below a Latin-named car park;
    * ``dedup._name_groups`` skips records whose ``norm`` is empty, so no two
      Arabic records could ever be recognised as the same place;
    * ``short_name`` reported True for every Arabic name, suppressing labels.

    Folding follows the usual Arabic search convention: NFKD plus the
    combining-mark strip already unifies أ/إ/آ -> ا, ؤ -> و and ئ -> ي (the
    hamza is a combining character after decomposition); ة -> ه, ى -> ي, the
    Persian ی/ک, the tatweel and the Arabic-Indic digits are folded here.
    """
    if not name:
        return ""
    s = unicodedata.normalize("NFKD", name)
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower().translate(_ARABIC_FOLD)
    s = _NORM_DROP.sub(" ", s)
    return " ".join(s.split())


def tokens(name: "str | None", drop_stopwords: bool = True) -> Tuple[str, ...]:
    n = normalize_name(name)
    out = []
    for t in n.split():
        if drop_stopwords and t in STOPWORDS:
            continue
        out.append(t)
    return tuple(out)


def token_set_similarity(a: Tuple[str, ...], b: Tuple[str, ...]) -> float:
    """Dice coefficient over token sets (order-insensitive)."""
    if not a or not b:
        return 0.0
    sa, sb = set(a), set(b)
    common = len(sa & sb)
    return 2.0 * common / (len(sa) + len(sb))


def extra_tokens(a: Tuple[str, ...], b: Tuple[str, ...]) -> List[str]:
    """Tokens in ``a`` that are not in ``b`` (kept in order, deduped)."""
    sb = set(b)
    seen = set()
    out = []
    for t in a:
        if t not in sb and t not in seen:
            seen.add(t)
            out.append(t)
    return out


#: Generic name shapes (bare descriptors) that score as low-quality names.
_GENERIC_SINGLE = frozenset({
    "cafe", "restaurant", "hotel", "mall", "market", "mosque", "masjid",
    "gym", "park", "beach", "hospital", "school", "bank", "atm", "shop",
    "store", "home", "centre", "center", "tower", "villa", "building",
    "accommodation", "doha", "qatar", "lobby", "lounge", "studio",
    "pharmacy", "clinic", "salon", "club",
    # Arabic class words. Before ``normalize_name`` understood Arabic these
    # could not be spelled here at all; a record named only "مسجد" (mosque)
    # or "صيدليه" (pharmacy) is exactly as unsearchable as the
    # Latin "Mosque" and must score the same.
    "مسجد", "جامع", "صيدليه", "مطعم", "مقهي", "بنك",
    "مستشفي", "عياده", "مدرسه", "سوق", "فندق", "موقف",
    "حديقه", "مركز", "محل", "شركه", "مبني", "برج",
})


def name_quality(name: "str | None", family: str) -> float:
    """A 0..1 component for the usefulness score.

    * Unnamed -> 0.0 (nothing to search for, nothing to label).
    * 0..1 by length: longer names are more specific.
    * Bare generic single-word descriptors ("Cafe", "Gym", "Mosque") drop the
      score hard — but only when the whole name is one generic word, so a
      genuinely generic "Park" label cannot outrank "Aspire Zone".
    * A name that is only stopwords/geography is worthless.

    Never excludes on its own.
    """
    if not name or not name.strip():
        return 0.0
    n = normalize_name(name)
    toks = n.split()
    if not toks:
        return 0.0
    if len(n) < 3:
        return 0.1
    base = min(1.0, 0.45 + 0.10 * len(n))
    if len(toks) == 1 and toks[0] in _GENERIC_SINGLE:
        base = max(base * 0.25, 0.2)
    if all(t in STOPWORDS for t in toks):
        return 0.1
    return base


def short_name(name: "str | None") -> bool:
    """A name too generic to carry a label (bare place/district name)."""
    if not name:
        return True
    return len(normalize_name(name).split()) <= 1


def display_name(props: dict) -> "Optional[str]":
    """The name shown to a driver: ``name`` else ``name:en`` (parity with the
    tile style's ``coalesce(name:en, name)`` for English)."""
    n = props.get("name")
    if isinstance(n, str) and n.strip():
        return n.strip()
    n = props.get("name:en")
    if isinstance(n, str) and n.strip():
        return n.strip()
    return None