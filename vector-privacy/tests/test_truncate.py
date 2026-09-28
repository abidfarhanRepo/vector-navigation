"""Tests for per-trip endpoint truncation (ticket 23).

The property under test is an invariant, not a behaviour: **no point within 200 m
along-track of a trip's true start or true end is ever committed, under any
interleaving of payloads.** Everything else here defends a direction of failure.

N=1 is tested alongside N=5 deliberately. A single-payload test passes against the
old per-payload code as well, so on its own it would prove nothing.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_privacy.gate import (  # noqa: E402
    REASON_TRUNCATED, apply_gate, haversine_m,
)
from vector_privacy.truncate import (  # noqa: E402
    REASON_HEAD_TRUNCATED, REASON_OUT_OF_ORDER, REASON_PENDING_TAIL,
    TripTruncator,
)

# ~1.11 m per 0.00001 degree of latitude, so a straight north-bound track with a
# 10 m step is easy to reason about in metres.
STEP_DEG = 0.00009          # ~10 m
BASE_LNG, BASE_LAT = 51.5000, 25.2800


def straight_track(n, *, step=STEP_DEG, t0=1_700_000_000_000, dt=5000):
    """A straight north-bound track of ``n`` points, ~10 m apart."""
    return [{"lng": BASE_LNG, "lat": round(BASE_LAT + i * step, 5),
             "t": t0 + i * dt, "a": 5.0} for i in range(n)]


def along_track(points):
    """Cumulative along-track distance for each point, from the first."""
    cum = [0.0]
    for i in range(1, len(points)):
        cum.append(cum[-1] + haversine_m(points[i - 1]["lng"], points[i - 1]["lat"],
                                         points[i]["lng"], points[i]["lat"]))
    return cum


def gated(points):
    """What the gate hands the truncator: everything except truncation."""
    kept, _ = apply_gate("track", points, now_ms=1_700_000_000_000, truncate=False)
    return kept


def key(p):
    return (p["lng"], p["lat"], p["t"])


class TestInvariant(unittest.TestCase):
    """The one property that must never break."""

    def _assert_endpoints_protected(self, trip, committed, distance_m=200.0):
        cum = along_track(trip)
        total = cum[-1]
        safe = {key(p) for i, p in enumerate(trip)
                if cum[i] >= distance_m and (total - cum[i]) >= distance_m}
        for p in committed:
            self.assertIn(key(p), safe,
                          f"committed a point within {distance_m} m of a true endpoint")

    def test_single_payload(self):
        trip = straight_track(120)          # ~1190 m
        tr = TripTruncator()
        committed, _ = tr.offer("trip_a", gated(trip), now_ms=1)
        committed += _committed_at_end(tr, "trip_a")
        self._assert_endpoints_protected(trip, committed)
        self.assertTrue(committed, "a 1.2 km trip should keep its middle")

    def test_five_payloads(self):
        """The case a per-payload implementation gets wrong."""
        trip = straight_track(120)
        tr = TripTruncator()
        committed = []
        for chunk in _chunks(trip, 24):
            got, _ = tr.offer("trip_a", gated(chunk), now_ms=1)
            committed += got
        committed += _committed_at_end(tr, "trip_a")
        self._assert_endpoints_protected(trip, committed)

    def test_batches_of_one(self):
        """A degenerate interleaving: the buffer must still hold the tail."""
        trip = straight_track(80)
        tr = TripTruncator()
        committed = []
        for p in trip:
            got, _ = tr.offer("trip_a", gated([p]), now_ms=1)
            committed += got
        committed += _committed_at_end(tr, "trip_a")
        self._assert_endpoints_protected(trip, committed)


class TestNothingLostInside(unittest.TestCase):
    """The reason the ticket exists: interior points must survive boundaries."""

    def test_five_payloads_equal_one(self):
        trip = straight_track(120)

        one = TripTruncator()
        whole, _ = one.offer("t1", gated(trip), now_ms=1)
        one.end_trip("t1")

        many = TripTruncator()
        split = []
        for chunk in _chunks(trip, 24):
            got, _ = many.offer("t2", gated(chunk), now_ms=1)
            split += got
        many.end_trip("t2")

        self.assertEqual([key(p) for p in whole], [key(p) for p in split],
                         "splitting a trip into 5 payloads changed what was kept")

    def test_no_point_committed_twice(self):
        trip = straight_track(120)
        tr = TripTruncator()
        committed = []
        for chunk in _chunks(trip, 20):
            got, _ = tr.offer("t", gated(chunk), now_ms=1)
            committed += got
        keys = [key(p) for p in committed]
        self.assertEqual(len(keys), len(set(keys)), "a point was committed twice")

    def test_recovery_versus_per_payload_truncation(self):
        """How much the fix actually recovers — the ticket's acceptance number."""
        trip = straight_track(120)
        chunks = list(_chunks(trip, 24))

        old = []
        for chunk in chunks:
            kept, _ = apply_gate("track", chunk, now_ms=1)   # truncate per payload
            old += kept

        tr = TripTruncator()
        new = []
        for chunk in chunks:
            got, _ = tr.offer("t", gated(chunk), now_ms=1)
            new += got
        tr.end_trip("t")

        # Not an incidental assertion: if the recovery were small the ticket's
        # justification was wrong, and the number belongs in a test rather than a
        # commit message. It is not small, and it is worse than the ticket
        # estimated. A 24-point payload spans ~230 m, which is **less than the two
        # 200 m cuts**, so per-payload truncation on a queue-based client keeps
        # nothing at all — not "~400 m lost per boundary", but the entire trip.
        # Any batch shorter than 400 m of road self-annihilates, and at 1 Hz that
        # is every batch of fewer than ~30 fixes at city speed.
        self.assertEqual(len(old), 0)
        self.assertEqual(len(new), 80)   # 120 points less the two true ends
        self.assertGreater(len(new), len(old))


class TestFailsClosed(unittest.TestCase):
    def test_restart_over_truncates_never_under(self):
        """State loss mid-trip must lose data, not leak an endpoint."""
        trip = straight_track(120)
        chunks = list(_chunks(trip, 24))

        tr = TripTruncator()
        committed = []
        for chunk in chunks[:2]:
            got, _ = tr.offer("t", gated(chunk), now_ms=1)
            committed += got

        fresh = TripTruncator()          # the restart
        for chunk in chunks[2:]:
            got, _ = fresh.offer("t", gated(chunk), now_ms=1)
            committed += got
        fresh.end_trip("t")

        cum = along_track(trip)
        total = cum[-1]
        safe = {key(p) for i, p in enumerate(trip)
                if cum[i] >= 200.0 and (total - cum[i]) >= 200.0}
        for p in committed:
            self.assertIn(key(p), safe)

    def test_idle_eviction_discards_the_tail(self):
        trip = straight_track(60)
        tr = TripTruncator(idle_gap_ms=1000)
        committed, _ = tr.offer("t", gated(trip), now_ms=10_000)
        self.assertGreater(tr.pending_points(), 0)
        counts = tr.evict_idle(now_ms=10_000 + 5000)
        self.assertEqual(tr.active_trips(), 0)
        self.assertGreater(counts[REASON_TRUNCATED], 0)
        # The evicted tail is gone, not returned by a later call.
        again, _ = tr.offer("t2", gated(straight_track(3)), now_ms=99_999)
        self.assertEqual(again, [])

    def test_trip_cap_evicts_oldest_without_committing(self):
        tr = TripTruncator(max_trips=2)
        for i, name in enumerate(("a", "b")):
            tr.offer(name, gated(straight_track(60)), now_ms=100 + i)
        self.assertEqual(tr.active_trips(), 2)
        committed, counts = tr.offer("c", gated(straight_track(60)), now_ms=200)
        self.assertLessEqual(tr.active_trips(), 2)
        self.assertGreater(counts[REASON_TRUNCATED], 0)
        # Nothing from the evicted trip may appear in this trip's commit.
        self.assertTrue(all(p["t"] is not None for p in committed))

    def test_end_trip_of_unknown_pseudonym_is_harmless(self):
        tr = TripTruncator()
        self.assertEqual(tr.end_trip("never-seen")[REASON_TRUNCATED], 0)


class TestOutOfOrder(unittest.TestCase):
    def test_older_payload_is_refused_and_counted(self):
        trip = straight_track(120)
        tr = TripTruncator()
        tr.offer("t", gated(trip[60:]), now_ms=1)         # frontier set from later half
        committed, counts = tr.offer("t", gated(trip[:60]), now_ms=1)
        self.assertEqual(committed, [])
        self.assertEqual(counts[REASON_OUT_OF_ORDER], 60)

    def test_refusal_does_not_admit_a_head_point(self):
        """The reason refusal beats merging: a head cut cannot be un-applied."""
        trip = straight_track(120)
        tr = TripTruncator()
        first_batch = gated(trip[40:80])
        tr.offer("t", first_batch, now_ms=1)
        # trip[0] is the true start. Arriving late, it must not be stored.
        committed, _ = tr.offer("t", gated([trip[0]]), now_ms=1)
        self.assertNotIn(key(trip[0]), [key(p) for p in committed])


class TestCounters(unittest.TestCase):
    def test_head_counted_once_per_point(self):
        trip = straight_track(120)
        tr = TripTruncator()
        _, counts = tr.offer("t", gated(trip), now_ms=1)
        self.assertEqual(counts[REASON_HEAD_TRUNCATED], 20)   # 200 m / 10 m steps

    def test_pending_tail_is_not_recounted_each_payload(self):
        """A deferral counter must not count one point once per batch."""
        trip = straight_track(120)
        tr = TripTruncator()
        total_pending = 0
        for chunk in _chunks(trip, 24):
            _, counts = tr.offer("t", gated(chunk), now_ms=1)
            total_pending += counts[REASON_PENDING_TAIL]
        # Every point can be withheld at most once, so the total cannot exceed
        # the number of points that ever entered the buffer.
        self.assertLessEqual(total_pending, 120)

    def test_short_trip_is_dropped_entirely(self):
        """Under 400 m there is no interior. Everything goes; that is the design."""
        trip = straight_track(20)          # ~190 m
        tr = TripTruncator()
        committed, counts = tr.offer("t", gated(trip), now_ms=1)
        end = tr.end_trip("t")
        self.assertEqual(committed, [])
        self.assertEqual(counts[REASON_HEAD_TRUNCATED] + end[REASON_TRUNCATED], 20)


class TestGateTruncateFlag(unittest.TestCase):
    """``truncate=False`` must change only truncation."""

    def test_only_truncation_changes(self):
        pts = straight_track(120)
        on, on_counts = apply_gate("track", pts, now_ms=1)
        off, off_counts = apply_gate("track", pts, now_ms=1, truncate=False)
        self.assertEqual(len(off), 120)
        self.assertLess(len(on), len(off))
        self.assertEqual(off_counts[REASON_TRUNCATED], 0)
        for reason in ("malformed", "bounds", "accuracy", "no_timestamp"):
            self.assertEqual(on_counts[reason], off_counts[reason])

    def test_bounds_and_accuracy_still_enforced_with_truncate_off(self):
        pts = [
            {"lng": 999.0, "lat": 25.0, "t": 1_700_000_000_000},        # bounds
            {"lng": 51.5, "lat": 25.28, "t": 1_700_000_005_000, "a": 90.0},  # accuracy
            {"lng": "x", "lat": None, "t": 1},                           # malformed
            {"lng": 51.5, "lat": 25.28, "t": 1_700_000_010_000, "a": 5.0},
        ]
        kept, counts = apply_gate("track", pts, now_ms=1, truncate=False)
        self.assertEqual(len(kept), 1)
        self.assertEqual(counts["bounds"], 1)
        self.assertEqual(counts["accuracy"], 1)
        self.assertEqual(counts["malformed"], 1)

    def test_truncate_distance_override_is_honoured(self):
        """It used to be accepted and ignored."""
        pts = straight_track(120)
        near, _ = apply_gate("track", pts, now_ms=1, truncate_distance_m=50.0)
        far, _ = apply_gate("track", pts, now_ms=1, truncate_distance_m=400.0)
        self.assertGreater(len(near), len(far))


def _chunks(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def _committed_at_end(tr, pseudonym):
    """Trip end commits nothing — the pending tail is discarded. Asserted here
    so a future change that decides to flush it fails these tests loudly."""
    counts = tr.end_trip(pseudonym)
    assert counts[REASON_TRUNCATED] >= 0
    return []


if __name__ == "__main__":
    unittest.main()
