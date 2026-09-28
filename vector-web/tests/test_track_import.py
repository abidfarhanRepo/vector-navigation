"""Tests for track import — collection tier 1 (ticket 19, adr-0069).

The fixtures here deliberately look like what recorders actually emit rather than
what is convenient to parse: an OsmAnd-shaped GPX with a namespace, extensions,
`<hdop>` but no accuracy, several `<trkseg>`, and a segment containing a
half-hour stop. A hand-written clean fixture would pass against a parser that
meets none of the real cases.
"""

import os
import sys
import time
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _p in ("src", "vendor"):
    _full = os.path.join(ROOT, _p)
    if _full not in sys.path:
        sys.path.insert(0, _full)

from vector_privacy.gate import ACCURACY_FLOOR_M  # noqa: E402
from vector_web import track_import  # noqa: E402

NOW = int(time.time() * 1000)
DAY = 86_400_000
SALT = b"test-salt-for-import"


def _iso(ms):
    from datetime import datetime, timezone
    return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def osmand_gpx(segments):
    """A GPX in the shape OsmAnd exports: namespaced, with extensions and hdop."""
    body = []
    for seg in segments:
        pts = []
        for lat, lng, t in seg:
            pts.append(
                f'   <trkpt lat="{lat:.6f}" lon="{lng:.6f}">\n'
                f'    <ele>12.4</ele>\n'
                f'    <time>{_iso(t)}</time>\n'
                f'    <hdop>1.2</hdop>\n'
                f'    <extensions><osmand:speed>13.4</osmand:speed></extensions>\n'
                f'   </trkpt>')
        body.append("  <trkseg>\n" + "\n".join(pts) + "\n  </trkseg>")
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<gpx version="1.1" creator="OsmAnd~" xmlns="http://www.topografix.com/GPX/1/1" '
        'xmlns:osmand="https://osmand.net">\n'
        ' <trk>\n  <name>2026-08-01_commute</name>\n'
        + "\n".join(body) +
        '\n </trk>\n</gpx>\n'
    ).encode("utf-8")


def straight(n, *, t0, dt_ms=2000, step=0.00009, lat0=25.2800, lng0=51.5000):
    """A straight ~10 m-per-fix track: ~5 m/s, an ordinary city speed."""
    return [(lat0 + i * step, lng0, t0 + i * dt_ms) for i in range(n)]


class TestGpxParsing(unittest.TestCase):
    def test_reads_osmand_shaped_file(self):
        data = osmand_gpx([straight(50, t0=NOW - 2 * DAY)])
        segs, fmt = track_import.sniff_and_parse(data, "track.gpx")
        self.assertEqual(fmt, "gpx")
        self.assertEqual(len(segs), 1)
        self.assertEqual(len(segs[0]), 50)
        self.assertIn("t", segs[0][0])

    def test_each_trkseg_is_its_own_trip(self):
        data = osmand_gpx([straight(40, t0=NOW - 3 * DAY),
                           straight(40, t0=NOW - 3 * DAY + 3600_000)])
        segs, _ = track_import.sniff_and_parse(data, "x.gpx")
        self.assertEqual(len(segs), 2)

    def test_hdop_is_never_read_as_accuracy(self):
        """The whole point of adr-0069 §3: no manufactured metres."""
        data = osmand_gpx([straight(10, t0=NOW - DAY)])
        segs, _ = track_import.sniff_and_parse(data, "x.gpx")
        for p in segs[0]:
            self.assertIsNone(p.get("a"),
                              "an accuracy appeared from a file that carries none")

    def test_explicit_accuracy_extension_is_read(self):
        data = (
            '<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>'
            f'<trkpt lat="25.28" lon="51.5"><time>{_iso(NOW - DAY)}</time>'
            '<accuracy>7.5</accuracy></trkpt>'
            '</trkseg></trk></gpx>'
        ).encode()
        segs, _ = track_import.sniff_and_parse(data, "x.gpx")
        self.assertEqual(segs[0][0]["a"], 7.5)

    def test_doctype_is_refused(self):
        data = (b'<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY a "aaaa">]>'
                b'<gpx><trk><trkseg></trkseg></trk></gpx>')
        with self.assertRaises(track_import.ImportError_):
            track_import.parse_gpx(data)

    def test_unparseable_file_gets_an_actionable_message(self):
        with self.assertRaises(track_import.ImportError_) as ctx:
            track_import.sniff_and_parse(b"PK\x03\x04binary zip", "archive.zip")
        self.assertIn("GPX", str(ctx.exception))

    def test_gpx_without_track_points_is_refused(self):
        data = b'<gpx version="1.1"><metadata><name>empty</name></metadata></gpx>'
        with self.assertRaises(track_import.ImportError_):
            track_import.parse_gpx(data)

    def test_route_points_accepted_as_one_segment(self):
        data = (
            '<gpx version="1.1"><rte>'
            + "".join(f'<rtept lat="{25.28 + i * 0.0001}" lon="51.5">'
                      f'<time>{_iso(NOW - DAY + i * 2000)}</time></rtept>'
                      for i in range(10))
            + '</rte></gpx>'
        ).encode()
        segs, _ = track_import.sniff_and_parse(data, "route.gpx")
        self.assertEqual(len(segs), 1)
        self.assertEqual(len(segs[0]), 10)


class TestGeoJson(unittest.TestCase):
    def test_linestring_with_coordtimes(self):
        pts = straight(30, t0=NOW - DAY)
        doc = {
            "type": "Feature",
            "geometry": {"type": "LineString",
                         "coordinates": [[lng, lat] for lat, lng, _ in pts]},
            "properties": {"coordTimes": [_iso(t) for _, _, t in pts]},
        }
        import json
        segs, fmt = track_import.sniff_and_parse(json.dumps(doc).encode(), "t.geojson")
        self.assertEqual(fmt, "geojson")
        self.assertEqual(len(segs[0]), 30)
        self.assertIn("t", segs[0][0])

    def test_round_trip_of_our_own_export_shape(self):
        import json
        pts = straight(60, t0=NOW - DAY)
        doc = {"type": "FeatureCollection", "features": [{
            "type": "Feature",
            "geometry": {"type": "LineString",
                         "coordinates": [[lng, lat, 0, t] for lat, lng, t in pts]},
            "properties": {}}]}
        plan = track_import.analyze(json.dumps(doc).encode(), filename="a.geojson",
                                    salt=SALT, now_ms=NOW)
        self.assertEqual(plan["trips_acceptable"], 1)
        self.assertGreater(plan["points_acceptable"], 0)


class TestTripDetection(unittest.TestCase):
    def test_gap_over_five_minutes_splits(self):
        t0 = NOW - 2 * DAY
        first = straight(40, t0=t0)
        # 30 minutes parked, then the drive home
        second = straight(40, t0=t0 + 40 * 2000 + 30 * 60_000, lat0=25.2900)
        data = osmand_gpx([first + second])
        plan = track_import.analyze(data, filename="day.gpx", salt=SALT, now_ms=NOW)
        self.assertEqual(plan["trips_detected"], 2,
                         "a half-hour stop inside one <trkseg> must split the trip")

    def test_gap_under_five_minutes_does_not_split(self):
        t0 = NOW - 2 * DAY
        pts = straight(40, t0=t0)
        pts += straight(40, t0=t0 + 40 * 2000 + 120_000, lat0=25.2836)
        plan = track_import.analyze(osmand_gpx([pts]), salt=SALT, now_ms=NOW)
        self.assertEqual(plan["trips_detected"], 1)

    def test_one_token_per_trip_not_per_file(self):
        """Two segments must be two trips with two digests, not one of either."""
        data = osmand_gpx([straight(60, t0=NOW - 2 * DAY),
                           straight(60, t0=NOW - 2 * DAY + 7200_000)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        digests = {t["digest"] for t in plan["trips"] if t["digest"]}
        self.assertEqual(len(digests), 2)


class TestAgePolicy(unittest.TestCase):
    def test_older_than_365_days_refused(self):
        data = osmand_gpx([straight(60, t0=NOW - 400 * DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(plan["points_acceptable"], 0)
        self.assertEqual(plan["would_drop"][track_import.REASON_AGE], 60)
        self.assertEqual(plan["trips"][0]["refused"], track_import.REASON_AGE)

    def test_within_the_window_accepted_and_bucketed(self):
        data = osmand_gpx([straight(60, t0=NOW - 200 * DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertGreater(plan["points_acceptable"], 0)
        self.assertIn("imported_age_90_365d", plan["quality"])

    def test_recent_track_lands_in_the_recent_bucket(self):
        data = osmand_gpx([straight(60, t0=NOW - 2 * DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertIn("imported_age_0_7d", plan["quality"])


class TestScreening(unittest.TestCase):
    def test_teleporting_fix_is_dropped(self):
        t0 = NOW - DAY
        pts = straight(30, t0=t0)
        # One fix 5 km away, 2 s later: 9,000 km/h.
        bad_lat, bad_lng, bad_t = pts[15]
        pts[15] = (bad_lat + 0.05, bad_lng, bad_t)
        plan = track_import.analyze(osmand_gpx([pts]), salt=SALT, now_ms=NOW)
        self.assertGreaterEqual(plan["would_drop"].get(track_import.REASON_IMPLAUSIBLE, 0), 1)

    def test_accuracy_unknown_is_counted_not_invented(self):
        data = osmand_gpx([straight(60, t0=NOW - DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertGreater(plan["quality"][track_import.QUALITY_ACCURACY_UNKNOWN], 0)

    def test_unknown_accuracy_does_not_drop_the_track(self):
        """The failure mode of synthesising a value above the 25 m floor."""
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertGreater(plan["points_acceptable"], 50)
        self.assertEqual(plan["would_drop"].get("accuracy", 0), 0)

    def test_declared_accuracy_above_the_floor_still_dropped(self):
        """An import is not privileged: a real bad measurement is still refused."""
        pts = straight(60, t0=NOW - DAY)
        body = "".join(
            f'<trkpt lat="{lat:.6f}" lon="{lng:.6f}"><time>{_iso(t)}</time>'
            f'<accuracy>{ACCURACY_FLOOR_M + 50}</accuracy></trkpt>'
            for lat, lng, t in pts)
        data = f'<gpx version="1.1"><trk><trkseg>{body}</trkseg></trk></gpx>'.encode()
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(plan["points_acceptable"], 0)
        self.assertEqual(plan["would_drop"].get("accuracy"), 60)

    def test_sparse_sampling_refused(self):
        """A fix every 5 minutes cannot be map-matched honestly."""
        pts = [(25.28 + i * 0.01, 51.5, NOW - DAY + i * 300_000) for i in range(10)]
        plan = track_import.analyze(osmand_gpx([pts]), salt=SALT, now_ms=NOW)
        refusals = {t["refused"] for t in plan["trips"]}
        self.assertIn(track_import.REASON_SPARSE, refusals)

    def test_speed_is_derived_when_absent(self):
        pts = straight(30, t0=NOW - DAY)
        body = "".join(f'<trkpt lat="{lat:.6f}" lon="{lng:.6f}">'
                       f'<time>{_iso(t)}</time></trkpt>' for lat, lng, t in pts)
        data = f'<gpx version="1.1"><trk><trkseg>{body}</trkseg></trk></gpx>'.encode()
        segs, _ = track_import.sniff_and_parse(data, "x.gpx")
        screened, _ = track_import.screen_kinematic(segs[0])
        self.assertTrue(any(p.get("s") for p in screened[1:]),
                        "no speed derived, so the aggregator would see none")
        # ~10 m per 2 s = ~5 m/s. A wrong unit here becomes a wrong road speed.
        self.assertAlmostEqual(screened[5]["s"], 5.0, delta=1.0)


class TestGateApplies(unittest.TestCase):
    def test_endpoints_are_truncated(self):
        """Truncation is naturally correct for an import: one payload, one trip."""
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(plan["trips"][0]["points_in_file"], 120)
        # ~200 m at each end of a ~1,190 m track: ~20 points per end.
        self.assertEqual(plan["points_acceptable"], 80)
        self.assertEqual(plan["trips"][0]["gate_dropped"]["truncated_endpoint"], 40)

    def test_trip_shorter_than_both_cuts_is_refused_with_a_reason(self):
        data = osmand_gpx([straight(20, t0=NOW - DAY)])   # ~190 m
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(plan["points_acceptable"], 0)
        self.assertEqual(plan["trips"][0]["refused"], "shorter_than_truncation")

    def test_coordinates_are_capped_at_five_decimals(self):
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        for p in plan["trips"][0]["_points"]:
            self.assertLessEqual(len(str(p["lat"]).split(".")[-1]), 5)


class TestIdempotency(unittest.TestCase):
    def test_same_file_yields_the_same_digest(self):
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        a = track_import.analyze(data, salt=SALT, now_ms=NOW)
        b = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(a["trips"][0]["digest"], b["trips"][0]["digest"])

    def test_different_salt_yields_a_different_digest(self):
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        a = track_import.analyze(data, salt=SALT, now_ms=NOW)
        b = track_import.analyze(data, salt=b"other-salt", now_ms=NOW)
        self.assertNotEqual(a["trips"][0]["digest"], b["trips"][0]["digest"])

    def test_marking_duplicates_keeps_the_totals_consistent(self):
        data = osmand_gpx([straight(120, t0=NOW - DAY),
                           straight(120, t0=NOW - DAY + 7200_000, lat0=25.31)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(plan["trips_acceptable"], 2)
        first = plan["trips"][0]["digest"]
        plan = track_import.mark_duplicates(plan, {first})
        self.assertEqual(plan["trips_acceptable"], 1)
        self.assertEqual(plan["trips_duplicate"], 1)
        self.assertEqual(plan["points_acceptable"],
                         sum(t["points_accepted"] for t in plan["trips"]))
        self.assertEqual(plan["trips"][0]["_points"], [])

    def test_a_duplicate_is_not_counted_as_dropped_points(self):
        """The drop rate is ticket 22's quality signal and must not be wrecked by an
        idempotency refusal. Uploading one file twice once reported 230 points
        dropped of 400 'seen', when 200 arrived and 170 were kept."""
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        digest = plan["trips"][0]["digest"]
        plan = track_import.mark_duplicates(plan, {digest})
        # No points reported dropped: they are in the store, which is the reason the
        # second copy was refused.
        self.assertEqual(plan["would_drop"], {})
        self.assertEqual(plan["quality"][track_import.QUALITY_DUPLICATE_TRIPS], 1)
        # And no phantom truncation or unknown-accuracy figures for a trip that will
        # not be stored.
        self.assertNotIn("truncated_endpoint", plan["would_drop"])
        self.assertNotIn(track_import.QUALITY_ACCURACY_UNKNOWN, plan["quality"])

    def test_a_genuinely_refused_trip_still_reports_its_losses(self):
        """The exception is duplicates only. A trip refused because the gate dropped
        everything really did lose those points, and that is what the counter is for."""
        pts = straight(60, t0=NOW - DAY)
        body = "".join(
            f'<trkpt lat="{lat:.6f}" lon="{lng:.6f}"><time>{_iso(t)}</time>'
            f'<accuracy>90</accuracy></trkpt>' for lat, lng, t in pts)
        data = f'<gpx version="1.1"><trk><trkseg>{body}</trkseg></trk></gpx>'.encode()
        plan = track_import.analyze(data, salt=SALT, now_ms=NOW)
        self.assertEqual(plan["points_acceptable"], 0)
        self.assertEqual(plan["would_drop"]["accuracy"], 60)

    def test_a_sparse_refusal_is_counted_in_points(self):
        """Otherwise a whole-trip refusal is a silent gap in the totals."""
        pts = [(25.28 + i * 0.01, 51.5, NOW - DAY + i * 300_000) for i in range(10)]
        plan = track_import.analyze(osmand_gpx([pts]), salt=SALT, now_ms=NOW)
        self.assertEqual(plan["would_drop"][track_import.REASON_SPARSE], 10)

    def test_quality_describes_only_what_will_be_stored(self):
        """`accuracy_unknown` means 'kept without a measured accuracy'."""
        good = straight(120, t0=NOW - DAY)
        stale = straight(120, t0=NOW - 400 * DAY, lat0=25.9)
        plan = track_import.analyze(osmand_gpx([good, stale]), salt=SALT, now_ms=NOW)
        self.assertEqual(plan["trips_acceptable"], 1)
        # Only the accepted trip's points count towards the unverified share.
        self.assertEqual(plan["quality"][track_import.QUALITY_ACCURACY_UNKNOWN], 120)

    def test_public_plan_carries_no_geometry(self):
        data = osmand_gpx([straight(120, t0=NOW - DAY)])
        plan = track_import.public_plan(
            track_import.analyze(data, salt=SALT, now_ms=NOW))
        for trip in plan["trips"]:
            self.assertNotIn("_points", trip)


if __name__ == "__main__":
    unittest.main()
