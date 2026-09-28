"""Display-name repair: what a driver actually reads on the map and in search.

``names.py`` answers *is this record a destination?* (the usefulness axis).
This module answers a different question about records we have already decided
to KEEP: **is the string we are about to show fit to read and to search?**

Nothing here deletes a record. Every transformation either rewrites the
display name or splits it into per-language fields, and the original spelling
is always retained as an alias so search keeps matching what it matched
before. The one structural operation — ``collapse_repeated_labels`` — marks
copies as EXCLUDED_DUPLICATE of a surviving sibling, which is a collapse, not
a deletion.

Measured against the deployed 26,096-record canonical corpus (2026-09-14):

=====================================  ======  =========================
defect                                  count   handled by
=====================================  ======  =========================
bilingual string, one field                790  ``split_bilingual``
  ... of which Arabic leads                204  (English now leads)
Arabic ``name`` + English ``name:en``      480  ``resolve_names``
doubled whitespace                         107  ``clean_text``
ALL-CAPS multiword                         207  ``clean_text`` (>=12 chars)
"... in Doha/Qatar" marketing tail          80  ``strip_marketing_tail``
phone number / URL inside the name          10  ``clean_text``
stray edge punctuation, doubled quotes      14  ``clean_text``
bidi / zero-width control characters         3  ``clean_text``
repeated classless building label           61  ``collapse_repeated_labels``
=====================================  ======  =========================

The corpus-wide Arabic defect is handled in ``names.normalize_name`` instead:
1,687 records (6.5%) normalised to the EMPTY STRING because the normaliser
kept only ``[a-z0-9]``, so every Arabic-script name scored ``name_quality``
0.0 and could never group for dedup. See that function's note.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# -- character classes ---------------------------------------------------------

#: Arabic letters only. Deliberately excludes Arabic punctuation (U+060C ،,
#: U+061F ؟) and the Arabic-Indic digits, which are folded separately.
ARABIC_LETTER = "ء-ي٠-٩ٮ-ۓۺ-ۿ"
_ARABIC_LETTERS_ONLY = "ء-يٮ-ۓۺ-ۿ"
_AR = re.compile(f"[{_ARABIC_LETTERS_ONLY}]")
_LATIN = re.compile(r"[A-Za-z]")

#: Format/control characters: RLM, LRM, ZWJ, ZWNJ, BOM, the isolate marks.
#: Three corpus records carry these invisibly and they break both the bidi
#: rendering of a label and any exact-string search.
_INVISIBLE = re.compile(
    r"[­​-‏‪-‮⁠-⁤⁦-⁩﻿￼�]")

#: Qatar telephone numbers pasted into a name. Mobile numbers begin 3/5/6/7,
#: landlines 44; the country code may or may not be present. A bare 8-digit
#: run is only stripped when it carries one of these prefixes, so "BFQ 974"
#: and "Lusail Marina 19" are untouched.
_PHONE = re.compile(
    r"(?:\bcall(?:\s*me)?\b|\bwhat'?s\s*app\b|\bwhatsapp\b|\btel\.?\b|\bmob\.?\b)?"
    r"\s*(?:\+|00)?\s*974[\s.-]*[3567]\d{7}\b"
    r"|(?:\bcall(?:\s*me)?\b|\bwhat'?s\s*app\b|\bwhatsapp\b|\btel\.?\b|\bmob\.?\b)"
    r"[\s.:-]*\d{6,9}(?:\s*,\s*\d{6,9})*"
    r"|\b(?:44|[3567]\d)\d{6}\b(?:\s*,\s*\d{8}\b)*",
    re.IGNORECASE,
)
_URL = re.compile(r"\b(?:https?://|www\.)\S+|\b\S+@\S+\.[a-z]{2,}\b", re.IGNORECASE)

#: Leftover connective words once a phone number has been cut out.
_ORPHAN_TAIL = re.compile(
    r"[\s.,:;-]*\b(?:please\s+)?(?:call(?:\s*me)?|what'?s\s*app|whatsapp|tel|mob)\b[\s.,:;-]*$",
    re.IGNORECASE)

#: Separators a bilingual label is normally built with.
_SPLITTERS = ("||", "|", " - ", " – ", " — ", " / ", "/", " , ", ",", " ، ", "،")

#: Trailing marketing/SEO geography: "Interior Design Company in Qatar".
#: Only trimmed when a real name is left behind (see ``strip_marketing_tail``).
_MARKETING_TAIL = re.compile(
    r"\s+in\s+(?:doha|qatar|the\s+)?(?:doha|qatar)?"
    r"(?:[\s,/-]+(?:doha|qatar|dubai|sharjah|ajman|abu\s*dhabi|uae|iraq|kuwait|"
    r"bahrain|oman|saudi|ksa|middle\s*east))*\s*$",
    re.IGNORECASE,
)

#: Words that stay upper-case when an ALL-CAPS name is recased. A short token
#: with no vowel is an acronym by construction ("GWC", "QNB", "SC", "LLC");
#: a short token WITH vowels is usually a word ("JOE", "THE", "FISH"), so the
#: vowel-carrying acronyms a POI corpus actually contains are listed.
_KEEP_UPPER_ALWAYS = frozenset({
    "VIP", "ATM", "UAE", "KSA", "QSC", "WLL", "LLC", "LTD", "BBQ", "KFC",
    "IKEA", "HSBC", "QNB", "QIB", "QIIB", "QAR", "AC", "TV", "IT", "HQ",
    "USA", "UK", "CEO", "DIY", "SPA", "GYM", "OK",
})
_VOWELS = re.compile(r"[AEIOU]")
_LOWER_PARTICLES = frozenset({"and", "of", "the", "for", "in", "at", "to", "on",
                              "by", "de", "la", "le", "el", "al", "bin", "bint"})


def _keep_upper(word: str) -> bool:
    core = word.strip(".,&'")
    if not core or not core.isalnum():
        return True
    if core in _KEEP_UPPER_ALWAYS:
        return True
    return len(core) <= 4 and not _VOWELS.search(core)

#: Minimum length before an ALL-CAPS multiword name is recased. Below it the
#: name is far more likely to be deliberate branding ("VIP GYM", "BVLGARI").
ALLCAPS_MIN_LEN = 12


@dataclass
class NameChoice:
    """The names a canonical record should carry.

    ``name`` is what a driver reads when no language is requested, and is
    English whenever an English form could be recovered. ``aliases`` always
    contains every spelling the record used to be findable by.
    """

    name: str
    name_en: "Optional[str]" = None
    name_ar: "Optional[str]" = None
    aliases: List[str] = field(default_factory=list)
    changes: List[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return bool(self.changes)


# -- primitives ----------------------------------------------------------------

def has_arabic(s: "Optional[str]") -> bool:
    return bool(s) and bool(_AR.search(s))


def has_latin(s: "Optional[str]") -> bool:
    return bool(s) and bool(_LATIN.search(s))


def _strip_edges(s: str) -> str:
    """Trim whitespace and decorative punctuation from both ends.

    Sentence-ending punctuation that belongs to the name is not a thing in
    POI names; a leading "- " or a trailing "," is always noise.
    """
    prev = None
    while prev != s:
        prev = s
        s = s.strip()
        s = s.strip(" ")
        s = re.sub(r'^[\s\-–—|,.:;*#&/\\·]+', "", s)
        s = re.sub(r'[\s\-–—|,:;*#&/\\·]+$', "", s)
        s = re.sub(r"\.+$", "", s)
        # Balanced wrapping quotes, and the doubled-quote artefact
        # '"ASIANA "The Curry House""'.
        if len(s) >= 2 and s[0] in "\"'“„" and s[-1] in "\"'”“":
            s = s[1:-1]
    return s


def _recase_allcaps(s: str) -> str:
    """Title-case a shouted multiword name, preserving acronyms."""
    letters = [c for c in s if c.isascii() and c.isalpha()]
    if not letters or not all(c.isupper() for c in letters):
        return s
    words = s.split()
    if len(words) < 2 or len(s) < ALLCAPS_MIN_LEN:
        return s
    out = []
    for i, w in enumerate(words):
        if _keep_upper(w):
            out.append(w)
        elif (i and words[i - 1] not in ("&", "-", "|")
                and w.lower().strip(".,") in _LOWER_PARTICLES):
            out.append(w.lower())
        else:
            out.append(w[:1] + w[1:].lower())
    return " ".join(out)


def clean_text(s: "Optional[str]") -> str:
    """Repair a raw name string without changing which place it names.

    Compatibility-folds presentation forms (so the corpus's
    ``𝗥𝗲𝗽𝗮𝗶𝗿 & 𝗥𝗲𝘀𝗮𝗹𝗲`` becomes searchable text), removes invisible bidi and
    zero-width marks, deletes pasted phone numbers and URLs, normalises
    underscores and repeated dots to spaces, collapses whitespace, trims
    decorative edge punctuation, and recases shouted multiword names.
    """
    if not s:
        return ""
    s = unicodedata.normalize("NFKC", s)
    s = _INVISIBLE.sub("", s)
    s = _URL.sub(" ", s)
    s = _PHONE.sub(" ", s)
    s = _ORPHAN_TAIL.sub("", s)
    s = s.replace("_", " ")
    s = re.sub(r"\.{2,}", " ", s)
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"([|/,\-–—])\s*\1+", r"\1", s)
    s = _strip_edges(s)
    s = re.sub(r"\s+([,;:])", r"\1", s)
    s = _recase_allcaps(s)
    return s.strip()


def strip_marketing_tail(s: str) -> str:
    """Drop a trailing "... in Doha/Qatar[/Dubai/...]" advertising tail.

    Only when a usable name survives: at least two words, and not a bare
    class word. "Ukrainian Embassy in Qatar" -> "Ukrainian Embassy";
    "Yoga in Doha" keeps its tail, because "Yoga" alone names nothing.
    """
    if not s:
        return s
    trimmed = _MARKETING_TAIL.sub("", s).strip()
    if trimmed == s or not trimmed:
        return s
    if len(trimmed.split()) < 2:
        return s
    return _strip_edges(trimmed)


# -- bilingual splitting -------------------------------------------------------

def _side_script(seg: str) -> "Optional[str]":
    ar, lat = has_arabic(seg), has_latin(seg)
    if ar and not lat:
        return "ar"
    if lat and not ar:
        return "en"
    return None


def _letters_of(seg: str, script: str) -> int:
    pat = _AR if script == "ar" else _LATIN
    return len(pat.findall(seg))


#: An English form must carry at least this many letters before it is allowed
#: to become the display name. Measured: without the floor, two OSM records
#: whose ``name:en`` was a two-letter stub ("إل بي" -> "lp", "مي" -> "Me")
#: had a good Arabic name replaced by an unsearchable one, and were then
#: excluded outright by the existing short-id rule. A language swap must never
#: make a record WORSE than the name it replaced.
MIN_LEADING_LATIN_LETTERS = 3


def _strong_english(s: "Optional[str]") -> bool:
    return bool(s) and _letters_of(s, "en") >= MIN_LEADING_LATIN_LETTERS


def _accept(en: str, ar: str) -> "Optional[Tuple[str, str]]":
    """A split is only worth taking when both halves are substantial."""
    en, ar = _strip_edges(en), _strip_edges(ar)
    if _letters_of(en, "en") < 2 or _letters_of(ar, "ar") < 2:
        return None
    return en, ar


def split_bilingual(s: str) -> "Tuple[Optional[str], Optional[str]]":
    """Split a jammed bilingual label into ``(english, arabic)``.

    Three strategies, most conservative first:

    1. **Parenthesis** — ``Läderach (لاديراخ)``: one side is wholly inside
       brackets.
    2. **Separator** — ``Al Meera | الميرة``, ``Sheikh Al Burger - شيخ البرجر``:
       a separator splits the string into exactly two single-script halves.
    3. **Script boundary** — ``وزارة التجارة والصناعة Ministry of Commerce``,
       ``North Feild Petrol Stationمحطة بترول حقل الشمال``: the string
       contains exactly ONE run of each script.

    Returns ``(None, None)`` when the label interleaves the two languages more
    than once (``Almandarin المندرين سوق واقف souq waqif``) — guessing there
    would produce a worse name than the original, so the original is kept.
    """
    if not s or not (has_arabic(s) and has_latin(s)):
        return (s if has_latin(s) else None, s if has_arabic(s) else None)

    # 1. bracketed other-language gloss
    m = re.match(r"^(.*?)[\s]*[\(\[]([^\)\]]+)[\)\]]\s*$", s)
    if m:
        outer, inner = m.group(1), m.group(2)
        so, si = _side_script(outer), _side_script(inner)
        if so and si and so != si:
            pair = (outer, inner) if so == "en" else (inner, outer)
            got = _accept(*pair)
            if got:
                return got

    # 2. explicit separator
    for sep in _SPLITTERS:
        if sep not in s:
            continue
        parts = [p for p in s.split(sep) if p.strip()]
        if len(parts) != 2:
            continue
        a, b = parts
        sa, sb = _side_script(a), _side_script(b)
        if sa and sb and sa != sb:
            pair = (a, b) if sa == "en" else (b, a)
            got = _accept(*pair)
            if got:
                return got

    # 3. single script boundary
    runs: List[Tuple[str, int, int]] = []
    for m2 in re.finditer(f"[{_ARABIC_LETTERS_ONLY}]+|[A-Za-z]+", s):
        script = "ar" if _AR.match(m2.group(0)) else "en"
        if runs and runs[-1][0] == script:
            runs[-1] = (script, runs[-1][1], m2.end())
        else:
            runs.append((script, m2.start(), m2.end()))
    if len(runs) == 2:
        (s1, _a1, e1), (s2, b2, _e2) = runs
        cut = (e1 + b2) // 2
        left, right = s[:cut], s[cut:]
        pair = (left, right) if s1 == "en" else (right, left)
        got = _accept(*pair)
        if got:
            return got

    return (None, None)


# -- the whole decision --------------------------------------------------------

def resolve_names(name: "Optional[str]",
                  name_en: "Optional[str]" = None,
                  name_ar: "Optional[str]" = None,
                  extra_aliases: "Optional[Sequence[str]]" = None) -> "Optional[NameChoice]":
    """Pick the display name and per-language fields for one record.

    Rules, in order:

    * Clean every candidate string (see ``clean_text``).
    * If ``name`` is a jammed bilingual label, split it; the English half
      becomes the display name and ``name:en``, the Arabic half ``name:ar``.
    * If ``name`` is Arabic and a separate Latin ``name:en`` exists (the OSM
      shape, 480 records), the English form leads and the Arabic becomes
      ``name:ar``. The geocoder's ``lang=en`` path already prefers
      ``name:en``; the map style already does ``coalesce(name:en, name)``.
      Leading with English additionally fixes the *default* label.
    * The original raw spelling, and every discarded form, is kept as an
      alias so nothing that used to be searchable stops being searchable.

    Returns ``None`` when there is no usable name at all.
    """
    raw = (name or "").strip()
    cleaned = clean_text(raw)
    en_in = clean_text(name_en or "")
    ar_in = clean_text(name_ar or "")

    changes: List[str] = []
    aliases: List[str] = []

    def remember(*vals: "Optional[str]") -> None:
        for v in vals:
            v = (v or "").strip()
            if v and v not in aliases:
                aliases.append(v)

    if not cleaned and not en_in and not ar_in:
        return None
    if cleaned != raw and raw:
        changes.append("cleaned")

    display = cleaned or en_in or ar_in
    out_en: "Optional[str]" = en_in or None
    out_ar: "Optional[str]" = ar_in or None

    # Jammed bilingual display string -> split.
    if has_arabic(display) and has_latin(display):
        split_en, split_ar = split_bilingual(display)
        if split_en and split_ar:
            remember(display)
            out_en = out_en or split_en
            out_ar = out_ar or split_ar
            # The per-language fields are always worth recording; the DISPLAY
            # name only leads in English when the English form is substantial
            # enough to be read and searched (``MIN_LEADING_LATIN_LETTERS``).
            if _strong_english(split_en):
                display = split_en
                changes.append("bilingual-split")
            else:
                changes.append("bilingual-tagged")
    # Arabic display with a separate English field -> English leads.
    elif (has_arabic(display) and not has_latin(display)
            and _strong_english(out_en)):
        remember(display)
        out_ar = out_ar or display
        display = out_en
        changes.append("english-leads")

    trimmed = strip_marketing_tail(display)
    if trimmed != display:
        remember(display)
        display = trimmed
        changes.append("marketing-tail")
        if out_en and not has_arabic(out_en):
            out_en = strip_marketing_tail(out_en)

    if has_latin(display) and not has_arabic(display):
        out_en = out_en or display
    if has_arabic(display) and not has_latin(display):
        out_ar = out_ar or display

    remember(raw, name_en, name_ar, *(extra_aliases or ()))
    # An alias that normalises to the display name adds nothing to search —
    # "Mesaieed Sealine Beach.." and "Mesaieed Sealine Beach" are one string
    # to every consumer — so only genuinely different spellings are kept.
    from .names import normalize_name as _norm
    display_norm = _norm(display)
    seen = {display_norm}
    keep: List[str] = []
    for a in aliases:
        an = _norm(a)
        if not an or an in seen:
            continue
        seen.add(an)
        keep.append(a)
    aliases = keep

    if not display:
        return None
    return NameChoice(name=display, name_en=out_en, name_ar=out_ar,
                      aliases=aliases, changes=changes)


def repair_feature_names(features: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Apply ``resolve_names`` across a raw GeoJSON feature list.

    Returns NEW feature dicts with repaired ``name`` / ``name:en`` /
    ``name:ar`` / ``alt_names`` properties; the input is never mutated, so a
    caller that also bakes the untouched source layer is unaffected. Features
    with no usable name pass through unchanged (they are counted as
    EXCLUDED_UNNAMED downstream, exactly as before).
    """
    out: List[Dict[str, Any]] = []
    for f in features:
        props = (f.get("properties") or {})
        choice = resolve_names(props.get("name"), props.get("name:en"),
                               props.get("name:ar"),
                               extra_aliases=props.get("alt_names") or ())
        if choice is None:
            out.append(f)
            continue
        new_props = dict(props)
        new_props["name"] = choice.name
        if choice.name_en:
            new_props["name:en"] = choice.name_en
        if choice.name_ar:
            new_props["name:ar"] = choice.name_ar
        if choice.aliases:
            new_props["alt_names"] = choice.aliases
        nf = dict(f)
        nf["properties"] = new_props
        out.append(nf)
    return out


# -- repeated classless building labels ----------------------------------------

#: How many identical classless labels it takes before they are copies of one
#: label rather than N destinations. Measured: at 5, the rule catches exactly
#: "Administration" (26), "Mechanical Draft Cooling" (14), "Hangar" (6),
#: "Barwa Commercial" (5), "District Cooling" (5), "Ezdan Villa" (5) — every
#: one an OSM building-name promotion, none of them a place a driver drives
#: to by name. Nothing with a category, address or locality can qualify.
REPEATED_LABEL_MIN = 5


def is_classless_anonymous(rec: Dict[str, Any]) -> bool:
    """A record with a name and nothing else: no class, no address, no area."""
    return (rec.get("family") == "UNKNOWN"
            and not rec.get("raw_category")
            and not rec.get("address")
            and not rec.get("locality")
            and not rec.get("brand"))


def repeated_label_groups(records: Sequence[Dict[str, Any]],
                          keep: "Optional[Sequence[bool]]" = None,
                          min_count: int = REPEATED_LABEL_MIN,
                          ) -> Dict[str, List[int]]:
    """Indices of classless anonymous records sharing one normalised name.

    ``keep[i]`` (when given) restricts the scan to records that are still
    canonical, so a record already excluded for another reason is not
    double-counted.
    """
    from .names import normalize_name

    groups: Dict[str, List[int]] = {}
    for i, rec in enumerate(records):
        if keep is not None and not keep[i]:
            continue
        if not is_classless_anonymous(rec):
            continue
        key = normalize_name(rec.get("name"))
        if not key:
            continue
        groups.setdefault(key, []).append(i)
    return {k: v for k, v in groups.items() if len(v) >= min_count}
