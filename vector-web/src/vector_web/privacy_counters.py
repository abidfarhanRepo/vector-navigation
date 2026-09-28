"""Cumulative privacy-gate counters for vector-web (issue 01 -> issue 10).

``apply_gate`` returns why each point was dropped, but per-request numbers
vanish with the response. Issue 10 needs the running totals, because they are
the only evidence the privacy layer is doing work:

    A k-anonymity floor that never rejects anything is not enforcing anything.

So the reasons are accumulated to a small JSON file that the evolution-metrics
collector reads. The file holds counts only — no coordinates, no timestamps, no
pseudonyms — so it is not itself location data, and it can be published on a
dashboard without a second thought.

Stdlib only, process-safe enough for the single-process web edge: an in-process
lock plus a read-modify-write with an atomic replace. If two edges ever share a
volume the counts could drift by a request or two, which is acceptable for a
monotonic trust indicator and is documented here rather than solved with a
database.
"""

from __future__ import annotations

import json
import os
import threading
from typing import Dict, Optional

# The gate's dropped-reason keys (vector_privacy.gate). Listed explicitly so a
# new reason has to be added here consciously rather than appearing unnoticed.
#
# Everything in this tuple is a **permanent loss**: the point was refused and will
# not arrive by another route. That is what makes ``total_dropped`` below a
# meaningful "the gate is doing work" signal, and it is why deferrals and quality
# observations live in KNOWN_QUALITY instead.
KNOWN_REASONS = (
    "malformed",
    "bounds",
    "accuracy",
    "truncated_endpoint",
    "no_timestamp",
    # Was missing, and accumulated invisibly: ``record()`` and ``read_counters()``
    # both preserve unlisted keys, so it worked — but it did not appear on the
    # panel until it first fired, which is precisely when an operator would want
    # to have been watching it (ticket 23/24).
    "no_trip_token",
    # Per-trip truncation (ticket 23). Both are permanent.
    "head_truncated",
    "out_of_order_refused",
    # Import (ticket 19). Refusals, counted in points.
    "age_too_old",
    "implausible_speed",
    "sparse_sampling",
    "no_timestamps",
    "too_short",
    "shorter_than_truncation",
)

# Observations that are **not** losses. Counted, and counted apart, because
# folding them into the drop total would let the privacy panel claim work the gate
# did not do — the exact failure mode this file exists to detect.
#
#   accuracy_unknown          — the point was kept; its accuracy was never measured
#                               (adr-0069 §3: accepted as unknown, never fabricated)
#   pending_tail_withheld     — held behind the frontier for one payload and usually
#                               committed on the next (ticket 23). A deferral.
#   duplicate_trips_refused   — an already-present trip was refused (adr-0069 §6).
#                               Counted in TRIPS, not points, and not a loss: the
#                               data is in the store, which is why it was refused.
#                               Counting its points as drops reported 230 dropped of
#                               400 "seen" for one file uploaded twice, wrecking the
#                               per-source drop rate that ticket 22 exists to give.
#   imported_age_*            — age buckets at ingest (adr-0069 §2 / DECISIONS D13),
#                               so the share of evidence resting on old data is
#                               readable rather than inferred.
KNOWN_QUALITY = (
    "accuracy_unknown",
    "pending_tail_withheld",
    "duplicate_trips_refused",
    "imported_age_0_7d",
    "imported_age_7_30d",
    "imported_age_30_90d",
    "imported_age_90_365d",
)

# Provenance (ticket 22 / adr-0070 §5). ``import`` is derived — a property of the
# route that parsed the file. ``live`` and ``native`` are **declared** by the
# client and recorded as declared, never as verified. Three constants, and it must
# never grow into a client id, device string or app version: that is the
# per-device identifier adr-0065 refuses, arriving through a side door.
KNOWN_SOURCES = ("live", "import", "native")

_LOCK = threading.Lock()


def default_path() -> str:
    """Where counters live: alongside the trace store, overridable by env."""
    env = os.environ.get("VECTOR_PRIVACY_COUNTERS")
    if env:
        return env
    return os.path.abspath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "data", "privacy_counters.json")
    )


def _blank() -> Dict[str, object]:
    return {
        "reasons": {r: 0 for r in KNOWN_REASONS},
        "quality": {q: 0 for q in KNOWN_QUALITY},
        "sources": {s: dict(_BLANK_SOURCE) for s in KNOWN_SOURCES},
        "requests": 0,
        "points_stored": 0,
    }


# Per-source figures (ticket 22). ``dropped`` is what makes the split worth
# having: **a tier dropping far more points is producing worse data**, and that is
# worth knowing before its evidence has moved a speed profile.
_BLANK_SOURCE = {"requests": 0, "points_stored": 0, "dropped": 0, "trips": 0}


def _merge_ints(doc_part: object, known: tuple) -> Dict[str, int]:
    """Known keys first (so they read 0 rather than absent), then any extras."""
    part = doc_part if isinstance(doc_part, dict) else {}
    merged = {k: int(part.get(k) or 0) for k in known}
    # Keep any key the pipeline has grown that this module has not been told
    # about, rather than silently discarding it on the next write.
    for key, value in part.items():
        if key not in merged:
            try:
                merged[key] = int(value)
            except (TypeError, ValueError):
                continue
    return merged


def read_counters(path: Optional[str] = None) -> Dict[str, object]:
    """Current totals. A missing file reads as all-zero, which is truthful."""
    path = path or default_path()
    if not os.path.exists(path):
        return _blank()
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError):
        return _blank()
    if not isinstance(doc, dict):
        return _blank()
    sources = doc.get("sources") if isinstance(doc.get("sources"), dict) else {}
    merged_sources: Dict[str, Dict[str, int]] = {}
    for name in KNOWN_SOURCES:
        merged_sources[name] = _merge_ints(sources.get(name), tuple(_BLANK_SOURCE))
    # A file written before ticket 22 has no ``sources`` at all. Its totals are
    # not attributed to a tier retroactively — inventing that attribution would be
    # worse than admitting the split starts now.
    for name, part in sources.items():
        if name not in merged_sources:
            merged_sources[name] = _merge_ints(part, tuple(_BLANK_SOURCE))
    return {
        "reasons": _merge_ints(doc.get("reasons"), KNOWN_REASONS),
        "quality": _merge_ints(doc.get("quality"), KNOWN_QUALITY),
        "sources": merged_sources,
        "requests": int(doc.get("requests") or 0),
        "points_stored": int(doc.get("points_stored") or 0),
    }


def record(dropped: Dict[str, int], stored: int = 0,
           path: Optional[str] = None,
           quality: Optional[Dict[str, int]] = None,
           source: Optional[str] = None,
           trips: int = 0) -> Dict[str, object]:
    """Add one request's drop counts to the totals and persist them.

    ``quality`` carries the non-loss observations (see KNOWN_QUALITY) and is kept
    out of the drop total on purpose. ``source`` attributes this request to a
    collection tier; an unknown value is attributed to ``live`` rather than
    creating a column value nobody declared (ticket 22).

    Never raises: a counter write failing must not fail a trace ingest. Losing a
    metric is a much smaller problem than rejecting the data it measures, and the
    counters are a trust indicator rather than an accounting record.
    """
    path = path or default_path()
    with _LOCK:
        current = read_counters(path)
        request_dropped = 0
        for reason, count in (dropped or {}).items():
            try:
                delta = int(count)
            except (TypeError, ValueError):
                continue
            current["reasons"][reason] = int(current["reasons"].get(reason, 0)) + delta
            request_dropped += delta
        for name, count in (quality or {}).items():
            try:
                delta = int(count)
            except (TypeError, ValueError):
                continue
            current["quality"][name] = int(current["quality"].get(name, 0)) + delta
        current["requests"] = int(current["requests"]) + 1
        current["points_stored"] = int(current["points_stored"]) + int(stored or 0)
        bucket = current["sources"].setdefault(
            source if source in KNOWN_SOURCES else "live", dict(_BLANK_SOURCE))
        bucket["requests"] = int(bucket.get("requests", 0)) + 1
        bucket["points_stored"] = int(bucket.get("points_stored", 0)) + int(stored or 0)
        bucket["dropped"] = int(bucket.get("dropped", 0)) + request_dropped
        bucket["trips"] = int(bucket.get("trips", 0)) + int(trips or 0)
        try:
            parent = os.path.dirname(os.path.abspath(path))
            if parent:
                os.makedirs(parent, exist_ok=True)
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(current, fh, sort_keys=True)
            os.replace(tmp, path)
        except (OSError, ValueError):
            # OSError covers permissions and missing directories; ValueError
            # covers malformed paths (``os.makedirs`` raises it, not OSError,
            # for an embedded null byte). Both must stay inside this function:
            # the caller is in the middle of a trace ingest, and losing a
            # trust metric is a far smaller problem than rejecting the data it
            # measures. Programming errors in the counting logic above are
            # deliberately NOT caught here — those should fail loudly in tests.
            pass
        return current


def summary(path: Optional[str] = None) -> Dict[str, object]:
    """Counters plus the derived judgement issue 10's panel shows."""
    counters = read_counters(path)
    reasons = counters["reasons"]
    # Only permanent losses count towards "the gate is doing work". A deferred
    # tail point (ticket 23) is committed on the next payload, and an unmeasured
    # accuracy is not a rejection at all — counting either here would let the
    # panel report enforcement that never happened.
    total_dropped = sum(int(v) for v in reasons.values())
    per_source = {}
    for name, part in (counters.get("sources") or {}).items():
        ingested = int(part.get("points_stored", 0)) + int(part.get("dropped", 0))
        per_source[name] = {
            **part,
            "points_seen": ingested,
            # The quality signal. None rather than 0 when nothing arrived: a tier
            # that has sent nothing has no drop rate, and reporting 0% would read
            # as "this tier is clean".
            "drop_rate": (round(int(part.get("dropped", 0)) / ingested, 4)
                          if ingested else None),
            # adr-0070 §5: import is derived from the route that parsed the file;
            # live/native are declared by the client. Labelled here so nobody
            # reads a debugging aid as a verified property later.
            "provenance": "derived" if name == "import" else "declared",
        }
    return {
        **counters,
        "total_dropped": total_dropped,
        "active": total_dropped > 0,
        "by_source": per_source,
        "warning": (
            None if total_dropped > 0
            else "privacy gate has dropped nothing — verify it is wired in, not a no-op"
        ),
    }
