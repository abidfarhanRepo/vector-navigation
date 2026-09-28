"""Tests for vector_privacy.gate (issue 01)."""

import unittest
from vector_privacy import gate
from vector_privacy.gate import apply_gate, mint_pseudonym


def _pt(lng, lat, t, **extra):
    p = {"lng": lng, "lat": lat, "t": t}
    p.update(extra)
    return p


def _track(line_to_lat):
    """Build a 'track' of N points along longitude 51 at increasing latitude."""
    return [_pt(51.0 + 0.00001 * i, line_to_lat(i), 1_700_000_000_000 + i * 1000) for i in range(line_to_lat)]


class ApplyGateTest(unittest.TestCase):
    def setUp(self):
        self.now = 1_700_000_000_000

    def test_malformed_points_are_dropped_not_raised(self):
        points = [{"lng": 51, "lat": 25}, "junk", None, {"lng": None, "lat": 25.0}, {}]
        kept, dropped = gate.apply_gate("probe", points, now_ms=self.now)
        self.assertEqual(len(kept), 1)          # only the first dict with valid numbers
        self.assertEqual(dropped[gate.REASON_MALFORMED], 4)

    def test_bounds_rejected(self):
        points = [{"lng": 200, "lat": 25}, {"lng": 51, "lat": 95}]
        kept, dropped = gate.apply_gate("probe", points, now_ms=self.now)
        self.assertEqual(len(kept), 0)
        self.assertEqual(dropped[gate.REASON_BOUNDS], 2)

    def test_accuracy_floor_drops_low_accuracy(self):
        points = [
            {"lng": 51, "lat": 25, "t": 1, "a": 9},       # good
            {"lng": 51, "lat": 25.01, "t": 2, "a": 60},   # drop (60 > 25)
            {"lng": 51, "lat": 25.02, "t": 3, "a": 25.0}, # good (exactly floor kept)
        ]
        kept, dropped = gate.apply_gate("probe", points, now_ms=self.now)
        self.assertEqual(dropped[gate.REASON_ACCURACY], 1)
        self.assertEqual(len(kept), 2)
        self.assertTrue(all(p["lng"] != 51 or p["lat"] != 25.01 for p in kept))

    def test_timestamps_rounded_to_5s(self):
        points = [{"lng": 51, "lat": 25, "t": 1_700_000_000_000 + 1234}]
        kept, _ = gate.apply_gate("probe", points, now_ms=self.now)
        self.assertEqual(kept[0]["t"], 1_700_000_000_000)

    def test_coordinates_capped_to_5_decimals(self):
        points = [{"lng": 51.123456789, "lat": 25.987654321, "t": 1}]
        kept, _ = gate.apply_gate("probe", points, now_ms=self.now)
        self.assertEqual(kept[0]["lng"], round(51.123456789, 5))
        self.assertEqual(kept[0]["lat"], round(25.987654321, 5))

    def test_track_endpoints_truncated(self):
        # ~ every 0.0011 deg lat change ~ 122 m; 10 points along a line
        # spanning > 400 m so a middle interval survives.
        pts = []
        for i in range(0, 10):
            pts.append(_pt(51.0, 25.0 + i * 0.0011, self.now + i * 1000))
        kept, dropped = gate.apply_gate("track", pts, now_ms=self.now)
        # first ~2 and last ~2 points dropped (each ≈122 m -> 200 m cut ~1.6 pts)
        self.assertGreater(dropped[gate.REASON_TRUNCATED], 0)
        self.assertGreaterEqual(len(kept), 4)
        # the extreme endpoints must be gone
        lats = [p["lat"] for p in kept]
        self.assertLess(min(lats), 25.009)
        self.assertGreater(max(lats), 25.002)

    def test_track_shorter_than_cuts_fully_dropped(self):
        # < 400 m total -> nothing survives (privacy win).
        pts = [_pt(51.0, 25.0 + i * 0.0001, self.now + i * 1000) for i in range(3)]
        kept, dropped = gate.apply_gate("track", pts, now_ms=self.now)
        self.assertEqual(kept, [])
        self.assertGreater(dropped[gate.REASON_TRUNCATED], 0)

    def test_probe_fixes_not_truncated(self):
        pts = [{"lng": 51.0, "lat": 25.0 + i * 0.001, "t": self.now + i * 1000} for i in range(10)]
        kept, dropped = gate.apply_gate("probe", pts, now_ms=self.now)
        self.assertEqual(dropped[gate.REASON_TRUNCATED], 0)
        self.assertEqual(len(kept), 10)

    def test_ordered_by_time(self):
        pts = [
            {"lng": 51 + 0.0, "lat": 25.0, "t": 11_000},
            {"lng": 51 + 0.001, "lat": 25.1, "t": 1_000},
            {"lng": 51 + 0.002, "lat": 25.2, "t": 6_000},
        ]
        kept, _ = gate.apply_gate("probe", pts, now_ms=self.now)
        times = [p["t"] for p in kept]
        self.assertEqual(times, sorted(times), "points must be emitted in time order")


class PseudonymTest(unittest.TestCase):
    def test_unique_and_opaque(self):
        a, b = mint_pseudonym(), mint_pseudonym()
        self.assertNotEqual(a, b)
        self.assertTrue(a.startswith("trip_"))
        self.assertGreater(len(a), 8)
        # hex only after prefix
        self.assertTrue(all(c in "0123456789abcdef" for c in a[5:]))


if __name__ == "__main__":
    unittest.main()

class TimestampUnitTest(unittest.TestCase):
    """A seconds timestamp must not be stored as if it were milliseconds.

    The browser client posted ``t`` in seconds while every consumer read it as
    milliseconds. Real captures therefore landed in January 1970, where the 72 h
    TTL deleted them on the next vacuum — accepted, stored, reported as stored,
    and silently gone before anything could aggregate them. The gate is the one
    place the rules are enforced and the client is never trusted, so the unit is
    normalized here as well as fixed at the source.
    """

    MS_2026 = 1_785_840_000_000
    S_2026 = 1_785_840_000

    def test_millisecond_timestamps_pass_through(self):
        kept, _ = apply_gate(
            "probe", [{"lng": 51.5, "lat": 25.2, "t": self.MS_2026, "a": 5.0}],
            now_ms=self.MS_2026)
        self.assertEqual(len(kept), 1)
        # Rounded to the 5 s boundary, still 2026 and still milliseconds.
        self.assertGreater(kept[0]["t"], 1_700_000_000_000)

    def test_second_timestamps_are_rescaled_not_stored_as_1970(self):
        kept, _ = apply_gate(
            "probe", [{"lng": 51.5, "lat": 25.2, "t": self.S_2026, "a": 5.0}],
            now_ms=self.MS_2026)
        self.assertEqual(len(kept), 1)
        self.assertAlmostEqual(kept[0]["t"], self.MS_2026, delta=5000)

    def test_rescaled_and_native_timestamps_agree(self):
        as_ms, _ = apply_gate("probe", [{"lng": 51.5, "lat": 25.2, "t": self.MS_2026}],
                              now_ms=self.MS_2026)
        as_s, _ = apply_gate("probe", [{"lng": 51.5, "lat": 25.2, "t": self.S_2026}],
                             now_ms=self.MS_2026)
        self.assertEqual(as_ms[0]["t"], as_s[0]["t"])

    def test_rescaling_also_applies_when_rounding_is_disabled(self):
        kept, _ = apply_gate(
            "probe", [{"lng": 51.5, "lat": 25.2, "t": self.S_2026}],
            now_ms=self.MS_2026, time_round_s=0)
        self.assertEqual(kept[0]["t"], self.MS_2026)

    def test_zero_and_tiny_timestamps_are_left_alone(self):
        # Below the plausible-seconds floor: not a unit error, so not rescaled.
        kept, _ = apply_gate("probe", [{"lng": 51.5, "lat": 25.2, "t": 0}],
                             now_ms=self.MS_2026)
        self.assertEqual(kept[0]["t"], 0)

    def test_a_full_track_of_second_timestamps_keeps_its_ordering_and_spacing(self):
        points = [{"lng": 51.5 + i * 0.001, "lat": 25.2, "t": self.S_2026 + i * 5, "a": 5.0}
                  for i in range(40)]
        kept, _ = apply_gate("track", points, now_ms=self.MS_2026)
        stamps = [p["t"] for p in kept]
        self.assertEqual(stamps, sorted(stamps))
        self.assertTrue(all(s > 1_700_000_000_000 for s in stamps))
        # 5 s spacing survives the rescale + rounding.
        gaps = {b - a for a, b in zip(stamps, stamps[1:])}
        self.assertTrue(gaps <= {5000}, f"unexpected gaps: {gaps}")
