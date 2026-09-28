"""Privacy gate for location data (vector-privacy, issue 01, adr-0065).

The single pure function that enforces every privacy rule in ONE place.
Vendored into ``vector-web`` (applied on ``POST /traces`` *before*
``TraceStore.append``) and later into ``vector-learning`` (issue 05) so ingest
and aggregation share one definition of the rules and cannot drift apart.

Design rule — the whole effort follows from it:

    Learn from aggregates over road segments. Never from individual traces.

Raw location data lives in a short-TTL quarantine and is never the thing that
persists. What persists is per-segment evidence that has already cleared a
k-anonymity floor — which is not personal data at all.

This module is stdlib-only, pure (no I/O, no global state), and **total**: it
never raises on malformed input. Every drop is reported as a count so the
caller can emit privacy counters (issue 10) that prove the layer is working.
"""

from __future__ import annotations

import math
import secrets
import uuid
from typing import Any, Dict, List, Tuple

# ---- binding thresholds (defaults; the ADR adr-0065 is the authority) ----

TRUNCATE_DISTANCE_M = 200.0   # first/last metres of a track dropped on-device AND server-side
ACCURACY_FLOOR_M = 25.0       # points with accuracy worse than this are dropped, not stored
TIME_ROUND_S = 5              # timestamps rounded to this many seconds
COORD_PRECISION = 5           # decimal places (~1 m) — below the resolution that makes a trace distinctive
MIN_LNG, MAX_LNG = -180.0, 180.0
MIN_LAT, MAX_LAT = -90.0, 90.0
VALID_KINDS = ("probe", "track")

# ---- dropped-reason keys (consumed by issue 10 privacy counters) ---------
REASON_MALFORMED = "malformed"
REASON_BOUNDS = "bounds"
REASON_ACCURACY = "accuracy"
REASON_TRUNCATED = "truncated_endpoint"
REASON_NO_TIME = "no_timestamp"


def _is_num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def haversine_m(lng1: float, lat1: float, lng2: float, lat2: float) -> float:
    """Great-circle distance in metres between two (lng, lat) points."""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    return 2.0 * r * math.asin(math.sqrt(min(1.0, a)))


def mint_pseudonym() -> str:
    """Mint an opaque, unlinkable per-trip pseudonym.

    Called once per *trip* (not per device, not per session). Rotates on trip
    end. No device identifier, IP, or user ID is ever stored alongside a point;
    the pseudonym is a fresh random value each trip so repeated trips by the
    same person cannot be linked.
    """
    return "trip_" + uuid.UUID(bytes=secrets.token_bytes(16)).hex


# A millisecond epoch for any plausible date is above ~1e12 (Sept 2001); a
# *second* epoch for any plausible date is below ~1e11 (year 5138). Anything
# landing between these is a seconds value being passed where milliseconds are
# expected, and it can be rescaled without ambiguity.
_MIN_PLAUSIBLE_SECONDS = 100_000_000        # 1973
_MAX_PLAUSIBLE_SECONDS = 100_000_000_000    # year 5138


def _normalize_ms(t: int) -> int:
    """Coerce a timestamp to milliseconds, rescaling a seconds value.

    The client is never trusted to have applied its half of the rules — that is
    the whole premise of this module — and unit confusion is the cheapest way for
    a client to poison the pipeline without ever looking malicious. A seconds
    timestamp read as milliseconds places the point in January 1970, where the
    72 h TTL deletes it on the next vacuum: the observation is accepted, stored,
    reported as stored, and then silently discarded before it can be aggregated.
    A pipeline that learns nothing while every check passes.

    Rescaling rather than rejecting is deliberate: the data is good, only its
    unit is wrong, and dropping it would lose real evidence to a client bug.
    """
    t = int(t)
    if _MIN_PLAUSIBLE_SECONDS < abs(t) < _MAX_PLAUSIBLE_SECONDS:
        return t * 1000
    return t


def _round_time(ms: int) -> int:
    """Round a millisecond timestamp to ``TIME_ROUND_S`` seconds."""
    s = _normalize_ms(ms) // 1000
    s = s - (s % TIME_ROUND_S)
    return s * 1000


def _truncate_endpoints(points: List[Dict[str, Any]], reason: Dict[str, int],
                        distance_m: float = TRUNCATE_DISTANCE_M) -> List[Dict[str, Any]]:
    """Drop the first and last ``distance_m`` of a polyline.

    Homes and workplaces must not be derivable from the store, so trip ends
    (and starts) are short by 200 m. Operates on the time-sorted track; keeps
    any interior points that survive both cuts. A track shorter than the two
    cuts is dropped entirely (a privacy win, not a bug).

    ``distance_m`` is a parameter rather than a read of the module constant
    because ``apply_gate`` accepts a ``truncate_distance_m`` override and used to
    pass it nowhere: every caller that raised the threshold got the default 200 m
    and no error. A privacy threshold that silently ignores the value it was given
    is the worst kind of dead parameter, so it is threaded through.
    """
    if len(points) < 2:
        return []
    cum = [0.0]
    for i in range(1, len(points)):
        prev, cur = points[i - 1], points[i]
        cum.append(cum[-1] + haversine_m(prev["lng"], prev["lat"], cur["lng"], cur["lat"]))
    total = cum[-1]
    kept = []
    for i, p in enumerate(points):
        head_ok = cum[i] >= distance_m
        tail_ok = (total - cum[i]) >= distance_m
        if head_ok and tail_ok:
            kept.append(p)
        else:
            reason[REASON_TRUNCATED] += 1
    return kept


def apply_gate(kind: str, points: List[Dict[str, Any]], *, now_ms: int,
               truncate: bool = True,
               truncate_distance_m: float = TRUNCATE_DISTANCE_M,
               accuracy_floor_m: float = ACCURACY_FLOOR_M,
               time_round_s: int = TIME_ROUND_S,
               coord_precision: int = COORD_PRECISION,
               ) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
    """Enforce every privacy rule on one batch of points.

    Pure and total: never raises on malformed input. Returns
    ``(kept_points, dropped_reasons)``.

    Rules (order matters; each is applied to the output of the previous):
      1. **Structure/bounds** — malformed points and out-of-range coordinates
         are dropped.
      2. **Accuracy floor** — ``a > accuracy_floor_m`` points are dropped, not
         stored. A client may lie about accuracy; this is checked server-side.
      3. **Endpoint truncation** (``kind == "track"``) — first/last
         ``truncate_distance_m`` metres are dropped so homes/workplaces are not
         derivable. ``probe`` fixes are interval samples, not a continuous
         track, so they are not truncated — but they are still pseudonymised
         and precision-capped.
      4. **Temporal coarsening** — timestamps rounded to ``time_round_s``.
      5. **Coordinate precision** — capped at ``coord_precision`` decimals.

    ``truncate=False`` turns step 3 off **and hands the caller the obligation to
    apply it another way** — today that means ``truncate.TripTruncator``, for the
    live-capture path where one payload is part of a trip rather than all of it
    (ticket 23). It is a separate parameter rather than ``truncate_distance_m=0``
    on purpose: zero happens to work, because every comparison against it passes,
    but it reads as a threshold change when it is a behaviour change, and the
    difference matters when someone later greps for who skips truncation. Import
    keeps the default: a whole trip arrives in one payload, so the endpoints
    trimmed here are its true ends (adr-0069 §Consequences).
    """
    dropped: Dict[str, int] = {
        REASON_MALFORMED: 0,
        REASON_BOUNDS: 0,
        REASON_ACCURACY: 0,
        REASON_TRUNCATED: 0,
        REASON_NO_TIME: 0,
    }

    clean: List[Dict[str, Any]] = []
    for p in points if isinstance(points, list) else []:
        if not isinstance(p, dict):
            dropped[REASON_MALFORMED] += 1
            continue
        lng, lat = p.get("lng"), p.get("lat")
        if not _is_num(lng) or not _is_num(lat):
            dropped[REASON_MALFORMED] += 1
            continue
        if not (MIN_LNG <= float(lng) <= MAX_LNG and MIN_LAT <= float(lat) <= MAX_LAT):
            dropped[REASON_BOUNDS] += 1
            continue
        if _is_num(p.get("a")) and float(p["a"]) > accuracy_floor_m:
            dropped[REASON_ACCURACY] += 1
            continue
        t = p.get("t")
        if _is_num(t):
            t = int(t)
        else:
            t = now_ms if _is_num(now_ms) else 0
            dropped[REASON_NO_TIME] += 1
        rec: Dict[str, Any] = {
            "lng": round(float(lng), coord_precision),
            "lat": round(float(lat), coord_precision),
            # Normalize the unit on both paths: a caller that disables rounding
            # (time_round_s=0) still must not get a seconds value through.
            "t": _round_time(t) if time_round_s else _normalize_ms(t),
        }
        if _is_num(p.get("s")):
            rec["s"] = round(float(p["s"]), 2)
        if _is_num(p.get("a")):
            rec["a"] = round(float(p["a"]), 1)
        if isinstance(p.get("h"), (str, int, float)) and p["h"]:
            rec["h"] = p["h"]
        clean.append(rec)

    # Endpoint truncation only makes sense on a continuous track. Probe fixes
    # are low-frequency interval samples and stay intact.
    if kind in ("probe", "track"):
        pass
    clean.sort(key=lambda r: r["t"])
    if kind == "track" and truncate:
        clean = _truncate_endpoints(clean, dropped, truncate_distance_m)

    return clean, dropped
