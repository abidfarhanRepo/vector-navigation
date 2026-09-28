"""Import recorded tracks — collection tier 1 (ticket 19, adr-0069).

A PWA cannot record a drive: both iOS Safari and Android Chrome stop delivering
positions to a backgrounded tab, and the web has no Background Geolocation API.
OsmAnd, Organic Maps, Strava, Komoot, Garmin and every built-in recorder *can*,
because the OS granted them the permission. So let them record, and accept the
file. This is the only collection tier that needs **no platform cooperation
whatsoever**, and the only one that can deliver data today — or reach a driver's
existing archive, which is months of the same commute that live capture would take
weeks to accumulate.

What this module is careful about, and why:

* **Every point goes through ``apply_gate``.** An import is not more trustworthy
  for arriving as a file. Truncation here is *naturally correct*, unlike live
  capture: a whole trip arrives in one payload, so the endpoints trimmed are its
  true ends (adr-0069 §Consequences, ticket 23).
* **One trip per ``<trkseg>``, split further on a >5 min gap.** A segment is where
  the recorder itself judged the track broke, which is better evidence than any
  reconstruction of ours. One token per *file* would make a month of commuting a
  single "trip" that never clears K; one per *point* manufactures trips. Both
  degenerate options fail, in opposite directions.
* **Missing accuracy is unknown, never fabricated.** GPX rarely carries an
  accuracy figure, and the gate skips its check when the field is absent — so
  unknown is already accepted today, by omission. Synthesising a value above the
  25 m floor would drop every imported point and return this tier to the no-op it
  just stopped being; below it would invent a measurement. **HDOP is never
  converted to metres**: that conversion needs the receiver's UERE, which the file
  does not carry, so it manufactures a precise-looking number and then tests it
  against a real threshold. The accuracy check is *replaced* rather than skipped —
  a kinematic screen on what the track itself reveals — and the unverified share
  is counted as ``accuracy_unknown``.
* **365 days, on validity grounds.** Not privacy: consent, truncation, the 72 h
  TTL and the K floor already do that work, and 90 days would be a number chosen
  to look careful. What a bound protects against is a track recorded before the
  road was resurfaced or re-signed — evidence that is wrong in a way that looks
  identical to right.
* **A re-uploaded track is refused.** Uploading one file twice manufactures two
  trips from one journey, and one journey uploaded five times clears K=5 alone.
  That is an accident a careful person makes, not an attack, so ADR-0068's "it
  costs the attacker their own privacy floor" argument does not cover it.

Deviation from adr-0069 §2 worth stating: the ADR asks for an age bucket recorded
**per observation**. The store already keeps the coarsened original timestamp
``t``, from which age at any moment is exact — so a per-row bucket would be
redundant state derived from a column already present, and this module records the
buckets as *counters* instead (``imported_age_*``). That satisfies the stated
purpose — the share of evidence resting on old data is readable rather than
inferred — without adding a second, drift-prone copy of the same fact.

Stdlib only. The parser is a new attack surface, so: no external entities, no
DOCTYPE, a point ceiling, and a byte ceiling enforced by the caller.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional, Sequence, Tuple

from vector_privacy.gate import apply_gate, haversine_m

from vector_web.trace_store import IMPORT_MAX_AGE_DAYS

# ---- policy constants (adr-0069 is the authority) ------------------------

MAX_AGE_DAYS = IMPORT_MAX_AGE_DAYS      # §Decision 2 — validity, not privacy
GAP_SPLIT_MS = 5 * 60 * 1000            # §Decision 4 — same rule as live capture
MAX_PLAUSIBLE_SPEED_MS = 200_000 / 3600.0   # 200 km/h — above this is not a drive
MAX_SPARSE_INTERVAL_S = 60              # a trip sampled sparser than this cannot
                                        # be map-matched honestly
MIN_TRIP_POINTS = 4                     # below this there is no trip to speak of
MAX_POINTS_PER_FILE = 500_000           # a ceiling, so a hostile file cannot OOM us

# ---- reasons (counted; see privacy_counters) ----------------------------

REASON_AGE = "age_too_old"
REASON_IMPLAUSIBLE = "implausible_speed"
REASON_SPARSE = "sparse_sampling"
QUALITY_ACCURACY_UNKNOWN = "accuracy_unknown"

# A duplicate is refused, but it is **not a data loss** and it is not counted in
# points. The data is already in the store; refusing the second copy is idempotency
# working. Counting its points as gate drops was actively misleading: a file
# uploaded twice reported 230 points dropped out of 400 "seen" when 200 points had
# arrived and 170 were kept, which wrecks the per-source drop rate — the one number
# ticket 22 exists to produce. So it lives in the quality bucket and is counted in
# TRIPS, and the unit is in the name.
QUALITY_DUPLICATE_TRIPS = "duplicate_trips_refused"

# Kept for callers and tests that name the refusal itself (``trip["refused"]``).
REASON_DUPLICATE = "duplicate_trip"

AGE_BUCKETS = (
    ("imported_age_0_7d", 7),
    ("imported_age_7_30d", 30),
    ("imported_age_30_90d", 90),
    ("imported_age_90_365d", 365),
)


class ImportError_(ValueError):
    """A file we will not parse. The message is shown to the person uploading."""


# ---- parsing -------------------------------------------------------------

# ElementTree expands internal entities, so a "billion laughs" document can be
# turned into gigabytes of strings before any of our own limits are reached.
# Refusing a DOCTYPE outright is the cheapest complete defence and costs nothing:
# no GPX exporter in the world emits one.
_DOCTYPE_RE = re.compile(rb"<!DOCTYPE", re.IGNORECASE)


def _localname(tag: str) -> str:
    """Tag name without its namespace. GPX 1.0 and 1.1 differ only there, and
    several exporters emit no namespace at all."""
    return tag.rsplit("}", 1)[-1].lower()


def _parse_iso_ms(value: str) -> Optional[int]:
    """GPX ``<time>`` to epoch milliseconds. Total: returns None on anything odd."""
    if not value:
        return None
    text = value.strip().replace("Z", "+00:00")
    # Fractional seconds appear with 1-7 digits in the wild; fromisoformat on
    # 3.11 accepts most but not all, so normalise to microseconds.
    m = re.match(r"^(.*?)(\.\d+)?([+-]\d{2}:?\d{2})?$", text)
    if not m:
        return None
    base, frac, offset = m.group(1), m.group(2) or "", m.group(3) or "+00:00"
    if len(offset) == 5:                      # +0000 -> +00:00
        offset = offset[:3] + ":" + offset[3:]
    if frac:
        frac = (frac + "000000")[:7]          # .123456
    from datetime import datetime
    try:
        dt = datetime.fromisoformat(base + frac + offset)
    except ValueError:
        return None
    return int(dt.timestamp() * 1000)


def parse_gpx(data: bytes) -> List[List[Dict[str, Any]]]:
    """Parse GPX into one list of raw points per ``<trkseg>``.

    Handles what real exporters emit: ``<trkpt lat lon>`` with ``<time>``,
    ``<ele>``, an optional ``<speed>`` (GPX 1.0, and inside ``<extensions>`` for
    Garmin/OsmAnd), several ``<trkseg>`` per ``<trk>`` and several ``<trk>`` per
    file. ``<hdop>`` is read and **discarded** — see the module docstring.

    Also accepts ``<rtept>``/``<wpt>`` sequences, because some recorders export a
    drive as a route. Those carry no segmentation, so they become one segment and
    the gap split does the rest.
    """
    if _DOCTYPE_RE.search(data[:4096]):
        raise ImportError_(
            "this file declares a DOCTYPE, which Vector will not parse. "
            "Re-export it from your recorder without one.")
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        raise ImportError_(f"not valid XML: {exc}") from exc

    segments: List[List[Dict[str, Any]]] = []
    total = 0

    def read_point(el: ET.Element) -> Optional[Dict[str, Any]]:
        try:
            lat = float(el.get("lat"))
            lng = float(el.get("lon"))
        except (TypeError, ValueError):
            return None
        p: Dict[str, Any] = {"lat": lat, "lng": lng}
        for child in el.iter():
            name = _localname(child.tag)
            text = (child.text or "").strip()
            if not text:
                continue
            if name == "time":
                ms = _parse_iso_ms(text)
                if ms is not None:
                    p["t"] = ms
            elif name == "speed":
                # GPX speed is metres per second, which is also what the live
                # client sends (``coords.speed``). No conversion.
                try:
                    p["s"] = float(text)
                except ValueError:
                    pass
            elif name in ("accuracy", "hacc", "horizontalaccuracy"):
                # An explicit accuracy extension is a real measurement in metres.
                try:
                    p["a"] = float(text)
                except ValueError:
                    pass
            # `hdop`, `vdop`, `pdop`, `ele`, `sat` deliberately ignored: none of
            # them is an accuracy in metres, and converting one into a distance
            # requires a receiver constant this file does not carry.
        return p

    for trkseg in root.iter():
        if _localname(trkseg.tag) != "trkseg":
            continue
        seg = []
        for child in trkseg:
            if _localname(child.tag) != "trkpt":
                continue
            p = read_point(child)
            if p is not None:
                seg.append(p)
                total += 1
                if total > MAX_POINTS_PER_FILE:
                    raise ImportError_(
                        f"file holds more than {MAX_POINTS_PER_FILE:,} points; "
                        "export a shorter date range and upload it in parts")
        if seg:
            segments.append(seg)

    if not segments:
        # No <trkseg>: try route/waypoint sequences before giving up.
        loose = []
        for el in root.iter():
            if _localname(el.tag) in ("rtept", "wpt"):
                p = read_point(el)
                if p is not None:
                    loose.append(p)
        if loose:
            segments.append(loose)

    if not segments:
        raise ImportError_(
            "no track points found. Vector reads GPX tracks (<trkpt>) and routes "
            "(<rtept>); this file appears to hold neither.")
    return segments


def parse_geojson(data: bytes) -> List[List[Dict[str, Any]]]:
    """Parse GeoJSON LineString / MultiLineString / point features into segments.

    This is the format Vector's own tooling emits, which makes a round trip
    testable without a third-party recorder in the loop.

    Timestamps come from a ``coordTimes`` array (the convention Mapbox and
    togeojson use) or from a per-coordinate 4th ordinate. Without either, the
    track has no time and is refused later by the sparse/kinematic screen rather
    than silently given ``now`` — a track stamped ``now`` would claim to be a
    drive that happened during the upload.
    """
    try:
        doc = json.loads(data.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise ImportError_(f"not valid GeoJSON: {exc}") from exc
    if not isinstance(doc, dict):
        raise ImportError_("GeoJSON must be an object")

    features = []
    if doc.get("type") == "FeatureCollection":
        features = [f for f in (doc.get("features") or []) if isinstance(f, dict)]
    elif doc.get("type") == "Feature":
        features = [doc]
    elif doc.get("type") in ("LineString", "MultiLineString"):
        features = [{"type": "Feature", "geometry": doc, "properties": {}}]
    else:
        raise ImportError_("GeoJSON must be a Feature, FeatureCollection or LineString")

    segments: List[List[Dict[str, Any]]] = []
    total = 0
    for feat in features:
        geom = feat.get("geometry") or {}
        props = feat.get("properties") or {}
        gtype = geom.get("type")
        lines: List[List[Any]] = []
        if gtype == "LineString":
            lines = [geom.get("coordinates") or []]
        elif gtype == "MultiLineString":
            lines = [c for c in (geom.get("coordinates") or []) if isinstance(c, list)]
        else:
            continue
        times = props.get("coordTimes")
        for line in lines:
            seg = []
            for i, coord in enumerate(line):
                if not isinstance(coord, (list, tuple)) or len(coord) < 2:
                    continue
                try:
                    lng, lat = float(coord[0]), float(coord[1])
                except (TypeError, ValueError):
                    continue
                p: Dict[str, Any] = {"lng": lng, "lat": lat}
                t = None
                if isinstance(times, list) and i < len(times):
                    t = _parse_iso_ms(str(times[i]))
                elif len(coord) >= 4:
                    try:
                        t = int(float(coord[3]))
                    except (TypeError, ValueError):
                        t = None
                if t is not None:
                    p["t"] = t
                seg.append(p)
                total += 1
                if total > MAX_POINTS_PER_FILE:
                    raise ImportError_(
                        f"file holds more than {MAX_POINTS_PER_FILE:,} points; "
                        "upload it in parts")
            if seg:
                segments.append(seg)
    if not segments:
        raise ImportError_("no LineString geometry found in this GeoJSON")
    return segments


def sniff_and_parse(data: bytes, filename: str = "") -> Tuple[List[List[Dict[str, Any]]], str]:
    """Parse by content, falling back to the file extension.

    Content first, because a file's name is the least reliable thing about it —
    phones rename downloads, and a ``.txt`` from a mail client is still GPX.
    """
    head = data.lstrip()[:64].lower()
    name = (filename or "").lower()
    if head.startswith(b"<"):
        return parse_gpx(data), "gpx"
    if head.startswith(b"{"):
        return parse_geojson(data), "geojson"
    if name.endswith(".gpx"):
        return parse_gpx(data), "gpx"
    if name.endswith((".json", ".geojson")):
        return parse_geojson(data), "geojson"
    raise ImportError_(
        "unrecognised file. Vector reads GPX (.gpx) and GeoJSON (.geojson) tracks. "
        "If your recorder exports a zip or a database, export a GPX from it first.")


# ---- trip detection and screening ---------------------------------------

def split_on_gaps(points: List[Dict[str, Any]],
                  gap_ms: int = GAP_SPLIT_MS) -> List[List[Dict[str, Any]]]:
    """Split one recorded segment wherever the recorder stopped for a while.

    Some recorders emit a whole day as one segment. At road speed a five-minute
    gap is kilometres of unknown track, and map-matching would interpolate
    straight through it — inventing a road that may not exist and a speed nobody
    drove. The same 5 minutes is used by live capture (ticket 20) and by the
    truncator's idle eviction (ticket 23), so all three agree on what a break is.
    """
    timed = [p for p in points if isinstance(p.get("t"), (int, float))]
    if len(timed) < 2:
        return [points] if points else []
    timed.sort(key=lambda p: p["t"])
    out: List[List[Dict[str, Any]]] = [[timed[0]]]
    for prev, cur in zip(timed, timed[1:]):
        if (cur["t"] - prev["t"]) > gap_ms:
            out.append([cur])
        else:
            out[-1].append(cur)
    return out


def screen_kinematic(points: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    """Replace the accuracy check with one the data can actually answer.

    Where accuracy is unknown (the normal case for GPX) the gate's 25 m floor has
    nothing to test, so the screen becomes what the track itself reveals: a point
    whose implied speed from its predecessor is impossible for a road vehicle is a
    bad fix, whatever the file claims. A single bad fix becomes a wrong learned
    road speed, which is exactly the failure that looks identical to success.

    Speed is also *derived* here when the file omits it, which most exports do.
    Metres per second, matching the live client, so both tiers mean the same thing
    by ``s``.
    """
    counts = {REASON_IMPLAUSIBLE: 0, QUALITY_ACCURACY_UNKNOWN: 0}
    kept: List[Dict[str, Any]] = []
    for p in points:
        if not kept:
            if p.get("a") is None:
                counts[QUALITY_ACCURACY_UNKNOWN] += 1
            kept.append(dict(p))
            continue
        prev = kept[-1]
        dt_s = (p.get("t", 0) - prev.get("t", 0)) / 1000.0
        dist = haversine_m(prev["lng"], prev["lat"], p["lng"], p["lat"])
        if dt_s > 0:
            implied = dist / dt_s
            if implied > MAX_PLAUSIBLE_SPEED_MS:
                # Drop the later point, not the earlier one: the predecessor is
                # corroborated by the point before it, and this one is not.
                counts[REASON_IMPLAUSIBLE] += 1
                continue
        elif dt_s <= 0 and dist > 0:
            # Movement with no time between fixes is not a measurement.
            counts[REASON_IMPLAUSIBLE] += 1
            continue
        q = dict(p)
        if q.get("a") is None:
            counts[QUALITY_ACCURACY_UNKNOWN] += 1
        if q.get("s") is None and dt_s > 0:
            q["s"] = round(dist / dt_s, 2)
        kept.append(q)
    return kept, counts


def filter_by_age(points: List[Dict[str, Any]], *, now_ms: int,
                  max_age_days: int = MAX_AGE_DAYS) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    """Drop observations older than the age bound, and bucket the survivors.

    The bound is about **validity, not privacy** (adr-0069 §2), and it is stated
    that way here so nobody later "tightens" it to 30 days believing they are
    improving the privacy posture when they are only discarding evidence.
    """
    counts: Dict[str, int] = {REASON_AGE: 0}
    buckets: Dict[str, int] = {}
    kept = []
    cutoff = now_ms - max_age_days * 86_400_000
    for p in points:
        t = p.get("t")
        if not isinstance(t, (int, float)):
            # No timestamp: the gate stamps it `now`, which for an import would
            # claim the drive happened during the upload. Refused as too old is
            # the wrong reason; refused as implausible is the right one, and the
            # sparse screen below catches a whole trip of them.
            kept.append(p)
            continue
        if t < cutoff:
            counts[REASON_AGE] += 1
            continue
        age_days = max(0.0, (now_ms - t) / 86_400_000)
        for name, limit in AGE_BUCKETS:
            if age_days <= limit:
                buckets[name] = buckets.get(name, 0) + 1
                break
        kept.append(p)
    counts.update(buckets)
    return kept, counts


def median_interval_s(points: List[Dict[str, Any]]) -> Optional[float]:
    """Median seconds between fixes, or None when the track carries no time."""
    ts = sorted(p["t"] for p in points if isinstance(p.get("t"), (int, float)))
    if len(ts) < 2:
        return None
    gaps = sorted((b - a) / 1000.0 for a, b in zip(ts, ts[1:]))
    mid = len(gaps) // 2
    return gaps[mid] if len(gaps) % 2 else (gaps[mid - 1] + gaps[mid]) / 2.0


def trip_digest(points: Sequence[Dict[str, Any]], salt: bytes) -> str:
    """A content digest of one detected trip, for idempotency (adr-0069 §6).

    Computed over **gated** geometry and coarsened timestamps, so it identifies
    what would actually be stored. A consequence worth knowing: changing a gate
    threshold changes every digest and makes re-upload possible again. Accepted
    and noted in the ADR.

    Keyed with the trip salt and truncated, so it is irreversible and identifies
    nobody on its own. It is still a confirmation oracle — someone already holding
    an exact track could test whether it was uploaded — and it outlives the 72 h
    raw-data TTL. That trade is accepted in §6 because the alternative is a K
    floor a routine double-click quietly voids.
    """
    body = "|".join(f"{p['lat']:.5f},{p['lng']:.5f},{int(p['t'])}" for p in points)
    return hmac.new(salt, body.encode("utf-8"), hashlib.sha256).hexdigest()[:32]


# ---- the plan ------------------------------------------------------------

def analyze(data: bytes, *, filename: str = "", salt: bytes, now_ms: int,
            seen_digests: Optional[set] = None,
            max_age_days: int = MAX_AGE_DAYS) -> Dict[str, Any]:
    """Parse, screen and gate a file **without storing anything**.

    This is the dry run adr-0069 §Decision 5 requires, and the commit path runs
    exactly the same function — so what the preview promises is what the commit
    does. Uploading a year of movement must not be one click whose consequences
    are only visible afterwards.

    Returns a plan: per-trip point counts, date range, what the gate would drop
    and why, which trips are already present, and totals. Geometry is **not**
    returned: the caller already holds the file, so echoing it back would add a
    copy of someone's movements to a response body for no gain.
    """
    seen = seen_digests or set()
    segments, fmt = sniff_and_parse(data, filename)

    trips: List[Dict[str, Any]] = []
    totals = {"points_in_file": 0, "points_acceptable": 0}
    reasons: Dict[str, int] = {}
    quality: Dict[str, int] = {}

    def bump(target: Dict[str, int], counts: Dict[str, int]) -> None:
        for key, value in counts.items():
            if value:
                target[key] = target.get(key, 0) + value

    for seg_index, segment in enumerate(segments):
        totals["points_in_file"] += len(segment)
        for trip_points in split_on_gaps(segment):
            raw_count = len(trip_points)
            aged, age_counts = filter_by_age(trip_points, now_ms=now_ms,
                                             max_age_days=max_age_days)
            screened, kin_counts = screen_kinematic(aged)
            trip_reasons = {REASON_AGE: age_counts.get(REASON_AGE, 0),
                            REASON_IMPLAUSIBLE: kin_counts[REASON_IMPLAUSIBLE]}
            trip_quality = {QUALITY_ACCURACY_UNKNOWN: kin_counts[QUALITY_ACCURACY_UNKNOWN]}
            for name, _limit in AGE_BUCKETS:
                if age_counts.get(name):
                    trip_quality[name] = age_counts[name]

            refused: Optional[str] = None
            if len(screened) < MIN_TRIP_POINTS:
                refused = REASON_AGE if trip_reasons[REASON_AGE] else "too_short"
            else:
                interval = median_interval_s(screened)
                if interval is None:
                    refused = "no_timestamps"
                elif interval > MAX_SPARSE_INTERVAL_S:
                    refused = REASON_SPARSE

            gated: List[Dict[str, Any]] = []
            gate_counts: Dict[str, int] = {}
            if refused is None:
                # Truncation ON: a whole trip is present, so the 200 m cuts land on
                # its true ends. This is the asymmetry with live capture (ticket 23)
                # and the reason import needs no truncator.
                gated, gate_counts = apply_gate("track", screened, now_ms=now_ms)
                if len(gated) < MIN_TRIP_POINTS:
                    # Everything survived screening but the trip is shorter than the
                    # two 200 m cuts. A privacy win, not a bug — but say so, because
                    # "0 points accepted" with no reason reads as a broken importer.
                    refused = "shorter_than_truncation"

            digest = trip_digest(gated, salt) if gated else None
            if digest and digest in seen:
                refused = REASON_DUPLICATE

            times = [p["t"] for p in screened if isinstance(p.get("t"), (int, float))]
            accepted = 0 if refused else len(gated)
            totals["points_acceptable"] += accepted

            # Per-point losses that happened *before* any refusal decision are always
            # counted: they are real, whatever becomes of the rest of the trip.
            bump(reasons, {k: v for k, v in trip_reasons.items() if v})
            if refused == REASON_DUPLICATE:
                quality[QUALITY_DUPLICATE_TRIPS] = quality.get(QUALITY_DUPLICATE_TRIPS, 0) + 1
            elif refused:
                # A trip refused as a whole loses the points that had survived
                # screening, counted under the refusal's own name so the reason is
                # readable rather than inferred from a gap in the totals.
                if len(screened):
                    reasons[refused] = reasons.get(refused, 0) + len(screened)
            if refused != REASON_DUPLICATE:
                # The gate's drops are real losses even when the trip is then refused
                # as a whole — a trip refused *because* every point failed the accuracy
                # floor lost those points to the gate, and that is exactly what the
                # counter is for. The one exception is a duplicate: those points are
                # already in the store, so counting the gate work again would report
                # enforcement on data that never arrived twice.
                bump(reasons, gate_counts)
            if not refused:
                # Quality describes the STORED corpus: `accuracy_unknown` means "kept
                # without a measured accuracy". A refused trip was not kept, so
                # counting it would overstate the unverified share of what we hold.
                bump(quality, trip_quality)

            trips.append({
                "segment": seg_index,
                "points_in_file": raw_count,
                "points_accepted": accepted,
                "first_ms": min(times) if times else None,
                "last_ms": max(times) if times else None,
                "age_days": (round((now_ms - min(times)) / 86_400_000, 1)
                             if times else None),
                "median_interval_s": median_interval_s(screened),
                "digest": digest,
                "refused": refused,
                "dropped": {k: v for k, v in trip_reasons.items() if v},
                "gate_dropped": {k: v for k, v in gate_counts.items() if v},
                "quality": {k: v for k, v in trip_quality.items() if v},
                # The gated points travel with the plan so ``commit`` does not
                # re-run the parse. They never leave the process: the route
                # serialises the plan *without* this key.
                "_points": gated if not refused else [],
            })

    accepted_trips = [t for t in trips if not t["refused"]]
    return {
        "format": fmt,
        "trips": trips,
        "trips_detected": len(trips),
        "trips_acceptable": len(accepted_trips),
        "trips_duplicate": sum(1 for t in trips if t["refused"] == REASON_DUPLICATE),
        "points_in_file": totals["points_in_file"],
        "points_acceptable": totals["points_acceptable"],
        "would_drop": reasons,
        "quality": quality,
        "max_age_days": max_age_days,
    }


def mark_duplicates(plan: Dict[str, Any], already: set) -> Dict[str, Any]:
    """Refuse trips whose digest is already present, and fix up the totals.

    Separate from ``analyze`` because the digest lookup is one query against the
    store rather than a per-trip round trip, and because keeping the bookkeeping in
    one function is the only way the totals and the per-trip flags cannot disagree
    — a preview that says "3 trips, 900 points" over a list showing two refusals is
    worse than no preview.
    """
    if not already:
        return plan
    for trip in plan["trips"]:
        digest = trip.get("digest")
        if not digest or digest not in already or trip["refused"]:
            continue
        trip["refused"] = REASON_DUPLICATE
        trip["_points"] = []
        plan["points_acceptable"] -= trip["points_accepted"]
        trip["points_accepted"] = 0
        plan["trips_duplicate"] += 1
        plan["trips_acceptable"] -= 1
        # This trip was counted as acceptable by ``analyze``, which had no way to
        # know it was already present. Take its gate work and its quality figures
        # back out, or a second upload of one file would report a second trip's
        # worth of truncation and unknown accuracies that never entered the store.
        for key, value in (trip.get("gate_dropped") or {}).items():
            plan["would_drop"][key] = max(0, plan["would_drop"].get(key, 0) - value)
        for key, value in (trip.get("quality") or {}).items():
            plan["quality"][key] = max(0, plan["quality"].get(key, 0) - value)
        plan["quality"][QUALITY_DUPLICATE_TRIPS] = (
            plan["quality"].get(QUALITY_DUPLICATE_TRIPS, 0) + 1)
    # Drop the keys that fell to zero, so a preview does not list work it will not do.
    plan["would_drop"] = {k: v for k, v in plan["would_drop"].items() if v}
    plan["quality"] = {k: v for k, v in plan["quality"].items() if v}
    return plan


def public_plan(plan: Dict[str, Any]) -> Dict[str, Any]:
    """The plan minus geometry — what a response body may contain."""
    return {
        **plan,
        "trips": [{k: v for k, v in t.items() if k != "_points"} for t in plan["trips"]],
    }
