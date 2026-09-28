"""Per-trip endpoint truncation for live capture (ticket 23, adr-0065/0068/0069).

``apply_gate`` truncates the first and last ``TRUNCATE_DISTANCE_M`` of **the
payload it is given**. That is correct when the payload is a whole trip — which is
true for an import, and true today for live capture only because the client
re-sends the entire accumulated track on every batch (the defect in ticket 20).
The moment a client sends only new points, per-payload truncation cuts 200 m at
both sides of every batch boundary: ~400 m of real road lost per boundary, in the
middle of the journey, where nothing needed protecting.

This module is the fix, and it is what lets ticket 20's upload queue be written.

**The seam.** The problem looks like it needs a whole trip before it can truncate,
and a whole trip cannot be had until the trip ends — which would defeat the point
of uploading during the drive. The way out is to stop needing the whole trip:

    the question is not "where does the trip end?"
    but "is this point more than 200 m behind the frontier?"

which is a *local* property, evaluable per payload:

* **Head — no buffer at all.** A point whose along-track distance from the trip's
  first observed point is under 200 m is dropped immediately and permanently. This
  needs only a running accumulator and the previous point, so **the home end is
  never written to disk, not even briefly** — the property the read-time
  alternative could not offer.
* **Tail — a bounded pending buffer.** The trailing 200 m of received track is
  withheld. When the next payload arrives, points now further than 200 m behind
  the new frontier are released and committed. At trip end — an explicit signal or
  an idle timeout — the buffer is **discarded**, and that discard *is* the tail
  truncation.

**The invariant, which is the thing that must never break:** no point within 200 m
along-track of a trip's true start or true end is ever committed to the store,
under any interleaving of payloads. Per-trip accuracy is an optimisation on top of
that invariant, never a hole in it.

Three properties worth stating out loud:

1. **No new persistent state, so no new privacy surface.** State is in memory and
   bounded by idle eviction and an absolute cap. There is no new table to TTL and
   nothing new for the vacuum to reach, which is exactly the objection that sank
   truncating at read time in ``vector-learning``.
2. **It fails closed.** A restart mid-trip loses the state, so head accumulation
   restarts from the next payload and drops another 200 m mid-journey. That is
   over-truncation — privacy-safe, data-lossy — the same direction of error the
   current code already makes, just far rarer.
3. **A payload older than the recorded frontier is refused, not merged.** Accepting
   it would retroactively invalidate a truncation already applied: the point the
   head was measured from would turn out not to have been first.

Stdlib-only, and the distance maths stays in ``gate.haversine_m`` so there is one
definition of distance in the privacy layer (adr-0065).
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from vector_privacy.gate import REASON_TRUNCATED, TRUNCATE_DISTANCE_M, haversine_m

# ---- counter keys --------------------------------------------------------
#
# Two of these are permanent losses and belong with the gate's drop reasons.
# ``REASON_PENDING_TAIL`` is **not** a loss — it is a deferral, and it is counted
# separately for that reason (see ``privacy_counters.KNOWN_QUALITY``). A point
# counted there is usually committed a payload later; counting it as a drop would
# make the privacy panel claim work it did not do.

REASON_HEAD_TRUNCATED = "head_truncated"       # permanent: inside the 200 m head cut
REASON_OUT_OF_ORDER = "out_of_order_refused"   # permanent: payload predates the frontier
REASON_PENDING_TAIL = "pending_tail_withheld"  # deferral: held behind the frontier

# A trip is considered over after this long without a payload. Same 5 minutes
# adr-0069 §Decision 4 uses to split an imported segment and ticket 20 uses to
# split a live one, so all three agree on what a break is.
IDLE_GAP_MS = 5 * 60 * 1000

# An absolute ceiling on tracked trips, so a buggy or hostile client minting a
# fresh token per batch cannot grow the state map without bound. Eviction
# discards the pending tail, which is the correct outcome — never a commit.
MAX_TRACKED_TRIPS = 2000


def _blank_counts() -> Dict[str, int]:
    return {REASON_HEAD_TRUNCATED: 0, REASON_OUT_OF_ORDER: 0,
            REASON_PENDING_TAIL: 0, REASON_TRUNCATED: 0}


class _TripState:
    """Per-pseudonym truncation state. In memory only, by design."""

    __slots__ = ("cum", "last_lng", "last_lat", "last_t", "pending", "last_seen_ms")

    def __init__(self) -> None:
        # Along-track distance of the most recent point from the trip's first
        # observed point. Monotonically non-decreasing, which is why the head cut
        # never needs to re-open once it has closed.
        self.cum: float = 0.0
        self.last_lng: Optional[float] = None
        self.last_lat: Optional[float] = None
        self.last_t: Optional[int] = None
        # (along-track distance, point) for points past the head cut that are not
        # yet 200 m behind the frontier. Ordered by distance, so releasing is a
        # prefix operation.
        self.pending: List[Tuple[float, Dict[str, Any]]] = []
        self.last_seen_ms: int = 0


class TripTruncator:
    """Stateful per-trip endpoint truncation across many payloads.

    One instance per process, keyed on the trip pseudonym the web edge already
    derives. Not thread-safe on its own; the web edge serialises ingest per
    request and holds no lock across payloads of different trips, so callers that
    add concurrency must add a lock (there is no shared state between trips).
    """

    def __init__(self, distance_m: float = TRUNCATE_DISTANCE_M,
                 idle_gap_ms: int = IDLE_GAP_MS,
                 max_trips: int = MAX_TRACKED_TRIPS) -> None:
        self.distance_m = float(distance_m)
        self.idle_gap_ms = int(idle_gap_ms)
        self.max_trips = int(max_trips)
        self._states: Dict[str, _TripState] = {}

    # -- introspection (observability only; holds no location data) ---------
    def active_trips(self) -> int:
        return len(self._states)

    def pending_points(self) -> int:
        return sum(len(s.pending) for s in self._states.values())

    # -- the ingest path ----------------------------------------------------
    def offer(self, pseudonym: str, points: List[Dict[str, Any]], *,
              now_ms: int) -> Tuple[List[Dict[str, Any]], Dict[str, int]]:
        """Feed one payload's gated points; return what may be committed.

        ``points`` must already have passed ``apply_gate(..., truncate=False)`` —
        this method assumes bounds, accuracy and coarsening are done and cares
        only about position along the track.

        Returns ``(committable, counts)``. Anything not in ``committable`` is
        either dropped permanently (head, out-of-order) or withheld pending the
        next payload (tail). Nothing is ever returned twice.
        """
        counts = _blank_counts()
        if not isinstance(points, list) or not points:
            return [], counts

        # Evict before admitting, so a long-idle trip's tail is discarded on the
        # next request rather than waiting for a sweep nobody schedules.
        self._evict_idle_into(now_ms, counts)

        st = self._states.get(pseudonym)
        if st is None:
            if len(self._states) >= self.max_trips:
                self._evict_oldest_into(counts)
            st = _TripState()
            self._states[pseudonym] = st
        st.last_seen_ms = int(now_ms)

        appended: List[int] = []
        for p in sorted(points, key=lambda r: r.get("t", 0)):
            t = p.get("t")
            if st.last_t is not None and isinstance(t, (int, float)) and t < st.last_t:
                # Refused rather than merged: the head cut was measured from a
                # point that this payload claims was not the first, and a
                # truncation already applied cannot be un-applied.
                counts[REASON_OUT_OF_ORDER] += 1
                continue
            if st.last_lng is None:
                st.cum = 0.0
            else:
                st.cum += haversine_m(st.last_lng, st.last_lat, p["lng"], p["lat"])
            st.last_lng, st.last_lat = p["lng"], p["lat"]
            if isinstance(t, (int, float)):
                st.last_t = int(t)
            if st.cum < self.distance_m:
                # Inside the head cut. Dropped here and never buffered anywhere,
                # so the home end does not exist in this process's memory either.
                counts[REASON_HEAD_TRUNCATED] += 1
                continue
            st.pending.append((st.cum, p))
            appended.append(id(p))

        committable = self._release(st)
        # Count only what *this* payload added and did not release. The pending
        # buffer is a gauge; adding it to a cumulative counter every payload would
        # count the same point once per batch for as long as it sat there.
        held = {id(p) for _, p in st.pending}
        counts[REASON_PENDING_TAIL] = sum(1 for i in appended if i in held)
        return committable, counts

    def _release(self, st: _TripState) -> List[Dict[str, Any]]:
        """Commit everything now more than ``distance_m`` behind the frontier."""
        frontier = st.cum
        out: List[Dict[str, Any]] = []
        keep: List[Tuple[float, Dict[str, Any]]] = []
        for cum, p in st.pending:
            if (frontier - cum) >= self.distance_m:
                out.append(p)
            else:
                keep.append((cum, p))
        st.pending = keep
        return out

    # -- trip end -----------------------------------------------------------
    def end_trip(self, pseudonym: str) -> Dict[str, int]:
        """End a trip: discard the pending tail. The discard *is* the tail cut."""
        counts = _blank_counts()
        st = self._states.pop(pseudonym, None)
        if st is not None:
            counts[REASON_TRUNCATED] += len(st.pending)
        return counts

    def evict_idle(self, now_ms: int) -> Dict[str, int]:
        """Discard trips idle longer than the gap rule. Never commits a tail."""
        counts = _blank_counts()
        self._evict_idle_into(now_ms, counts)
        return counts

    def _evict_idle_into(self, now_ms: int, counts: Dict[str, int]) -> None:
        cutoff = int(now_ms) - self.idle_gap_ms
        for key in [k for k, s in self._states.items() if s.last_seen_ms < cutoff]:
            st = self._states.pop(key)
            counts[REASON_TRUNCATED] += len(st.pending)

    def _evict_oldest_into(self, counts: Dict[str, int]) -> None:
        if not self._states:
            return
        key = min(self._states, key=lambda k: self._states[k].last_seen_ms)
        st = self._states.pop(key)
        counts[REASON_TRUNCATED] += len(st.pending)


# A process-wide instance for the web edge, which needs the state to survive
# between requests and has exactly one ingest path.
_TRUNCATOR: Optional[TripTruncator] = None


def shared_truncator() -> TripTruncator:
    """The process-wide truncator used by the live-capture ingest path."""
    global _TRUNCATOR
    if _TRUNCATOR is None:
        _TRUNCATOR = TripTruncator()
    return _TRUNCATOR
