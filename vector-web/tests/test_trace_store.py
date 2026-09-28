"""Unit tests for the SQLite + R\\*Tree GPS-trace quarantine store (issues 01/02).

Guards the data contract: points are pseudonymised per-trip, stored sorted and
indexed, spatially queryable via the R\\*Tree (not a full scan), and expired +
vacuumed after the 72 h TTL. The privacy gate itself is tested in
``vector-privacy``; here we assert the store persists only gated points.
"""

import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in ("src", "vendor"):
    path = os.path.join(ROOT, p)
    if path not in sys.path:
        sys.path.insert(0, path)

from vector_web.trace_store import (  # noqa: E402
    IMPORT_MAX_AGE_DAYS, SOURCES, validate_trace, TraceStore, _iso_day, TTL_HOURS,
)


class ValidateTraceTest(unittest.TestCase):
    def test_rejects_non_object(self):
        pts, err = validate_trace([1, 2, 3])
        self.assertIsNone(pts)
        self.assertIn("object", err)

    def test_rejects_bad_kind(self):
        pts, err = validate_trace({"kind": "bogus", "points": [{"lng": 1, "lat": 1}]})
        self.assertIsNone(pts)
        self.assertIn("kind", err)

    def test_rejects_empty_points(self):
        pts, err = validate_trace({"kind": "track", "points": []})
        self.assertIsNone(pts)
        self.assertIn("non-empty", err)

    def test_admits_valid_track(self):
        body = {"kind": "track", "points": [
            {"lng": 51.5, "lat": 25.2, "t": 1700000000000, "s": 12.3, "a": 5.0, "h": 90},
            {"lng": 51.501, "lat": 25.201},
        ]}
        pts, err = validate_trace(body)
        self.assertIsNone(err)
        self.assertEqual(len(pts), 2)


class TraceStoreTest(unittest.TestCase):
    def setUp(self):
        self.fd, self.path = tempfile.mkstemp(suffix=".db")
        os.close(self.fd)
        if os.path.exists(self.path):
            os.remove(self.path)
        self.store = TraceStore(self.path)

    def tearDown(self):
        try:
            self.store.close()
        except Exception:
            pass
        for p in (self.path, self.path + "-wal", self.path + "-shm"):
            if os.path.exists(p):
                os.remove(p)

    def _pts(self, n, t0=1_700_000_000_000):
        return [{"lng": 51.0 + i * 0.001, "lat": 25.2, "t": t0 + i * 1000} for i in range(n)]

    def test_append_and_count(self):
        n = self.store.append("track", self._pts(2))
        self.assertEqual(n, 2)
        self.assertEqual(self.store.count(), 2)

    def test_append_mints_pseudonym_and_sorts_schema(self):
        n = self.store.append("probe", self._pts(3), pseudonym="trip_abc")
        self.assertEqual(n, 3)
        recent = self.store.recent(10)
        self.assertEqual(len(recent), 3)
        # Every stored observation carries a trip pseudonym, not a device id.
        for r in recent:
            self.assertIn(r["trip_pseudonym"], ("trip_abc",))
            self.assertIsNotNone(r["day"])

    def test_day_partition(self):
        # mix two days: 1_700_000_000_000 and +10 days
        a = self.store.append("probe", [{"lng": 51, "lat": 25, "t": 1_700_000_000_000}])
        b = self.store.append("probe", [{"lng": 51, "lat": 25, "t": 1_700_000_000_000 + 10 * 86400 * 1000}])
        self.assertEqual(a + b, 2)
        days = {r["day"] for r in self.store.recent(10)}
        self.assertGreaterEqual(len(days), 2)

    def test_recent_is_bounded_and_newest_first(self):
        for i in range(10):
            self.store.append("track", [{"lng": 51.0 + i * 0.001, "lat": 25.2, "t": 1_700_000_000_000 + i * 1000}])
        recent = self.store.recent(3)
        self.assertEqual(len(recent), 3)
        # newest first (id DESC)
        self.assertGreater(recent[0]["id"], recent[2]["id"])
        self.assertEqual(self.store.count(), 10)

    def test_nearby_uses_rtree_and_meters(self):
        # seed a far cluster + a near point
        self.store.append("track", [{"lng": 51.0, "lat": 25.2, "t": 1}])
        for i in range(5):
            self.store.append("track", [{"lng": 51.0 + 0.0005 * i, "lat": 25.2 + 0.0005 * i, "t": i}])
        # query near (51.0, 25.2) within 50 m -> only the seed + nearest
        near = self.store.nearby(51.0, 25.2, radius_m=50)
        self.assertGreaterEqual(len(near), 1)
        # A very far point must not appear.
        self.store.append("track", [{"lng": 51.5, "lat": 25.5, "t": 99}])
        near = self.store.nearby(51.0, 25.2, radius_m=50)
        for r in near:
            self.assertLess(abs(r["lng"] - 51.0), 0.01)

    def test_expire_deletes_and_vacuum(self):
        old_t = 1_000_000_000_000  # ~1970-ish but old relative to now
        self.store.append("probe", [{"lng": 51, "lat": 25, "t": old_t}])
        self.store.append("probe", [{"lng": 51, "lat": 25, "t": 1_700_000_000_000}])
        self.assertEqual(self.store.count(), 2)
        deleted = self.store.expire(older_than_ms=1_700_000_000_000)
        self.assertEqual(deleted, 1)
        self.assertEqual(self.store.count(), 1)

    def test_nearby_query_plan_uses_rtree_not_full_scan(self):
        # Seed a synthetic table and assert the nearby query is routed through
        # the R*Tree index (the candidate-set search issue 05 relies on), not a
        # linear scan of the base table.
        for i in range(2000):
            self.store.append("track", [{
                "lng": 51.0 + (i % 50) * 0.001,
                "lat": 25.0 + (i // 50) * 0.001,
                "t": 1_700_000_000_000 + i,
            }], pseudonym="bulk")
        plan_rows = self.store._conn.execute(
            "EXPLAIN QUERY PLAN "
            "SELECT o.* FROM observation_rt r JOIN observation o ON o.id = r.oid "
            "WHERE r.minx <= 51.1 AND r.maxx >= 51.0 AND r.miny <= 25.1 AND r.maxy >= 25.0 "
            "ORDER BY o.id DESC LIMIT 100"
        ).fetchall()
        plan = " ".join(str(r[3]) for r in plan_rows)
        self.assertIn("VIRTUAL TABLE", plan)
        self.assertNotIn("SCAN observation", plan)


class ProvenanceTest(unittest.TestCase):
    """Ticket 22: which collection tier produced this row."""

    def setUp(self):
        self.path = tempfile.mktemp(suffix=".db")
        self.store = TraceStore(self.path)

    def tearDown(self):
        self.store.close()

    def test_default_is_live(self):
        self.store.append("track", [{"lng": 51.0, "lat": 25.0, "t": 1_700_000_000_000}])
        self.assertEqual(self.store.recent(1)[0]["source"], "live")

    def test_unknown_source_is_coerced_not_stored(self):
        """An unknown value must not reach the column, from any caller."""
        self.store.append("track", [{"lng": 51.0, "lat": 25.0, "t": 1_700_000_000_000}],
                          source="app-version-3.1.4")
        self.assertEqual(self.store.recent(1)[0]["source"], "live")

    def test_allowed_values_only(self):
        """A later 'let us include the device model' change fails here."""
        self.assertEqual(SOURCES, ("live", "import", "native"))
        for src in ("live", "import", "native"):
            self.store.append("track", [{"lng": 51.0, "lat": 25.0,
                                         "t": 1_700_000_000_000}], source=src)
        stored = {r["source"] for r in self.store.recent(10)}
        self.assertTrue(stored <= set(SOURCES))

    def test_counts_and_trips_by_source(self):
        self.store.append("track", [{"lng": 51.0, "lat": 25.0, "t": 1}],
                          pseudonym="a", source="live")
        self.store.append("track", [{"lng": 51.0, "lat": 25.0, "t": 2}],
                          pseudonym="b", source="import")
        self.store.append("track", [{"lng": 51.0, "lat": 25.0, "t": 3}],
                          pseudonym="c", source="import")
        self.assertEqual(self.store.counts_by_source(), {"live": 1, "import": 2})
        self.assertEqual(self.store.trips_by_source(), {"live": 1, "import": 2})

    def test_pre_ticket_database_migrates(self):
        """The CREATE TABLE IF NOT EXISTS trap, exercised directly.

        A ``traces.db`` written before this column existed never picks it up from
        ``_SCHEMA``, and the store is built on first use — so a missing migration
        would surface as a read error in production, far from its cause.
        """
        import sqlite3
        old_path = tempfile.mktemp(suffix=".db")
        conn = sqlite3.connect(old_path)
        conn.executescript("""
            CREATE TABLE observation(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              trip_pseudonym TEXT NOT NULL, kind TEXT NOT NULL,
              lng REAL NOT NULL, lat REAL NOT NULL, t INTEGER NOT NULL,
              day TEXT NOT NULL, speed REAL, accuracy REAL, heading TEXT);
            CREATE VIRTUAL TABLE observation_rt USING rtree(oid, minx, maxx, miny, maxy);
        """)
        conn.execute("INSERT INTO observation(trip_pseudonym, kind, lng, lat, t, day) "
                     "VALUES ('trip_old', 'track', 51.0, 25.0, 1700000000000, '2023-11-14')")
        conn.commit()
        conn.close()

        store = TraceStore(old_path)         # must migrate, not raise
        try:
            rows = store.recent(10)
            self.assertEqual(len(rows), 1)
            # The pre-existing row is `live` by DEFAULT rather than being
            # attributed to a tier that did not exist when it was written.
            self.assertEqual(rows[0]["source"], "live")
            store.append("track", [{"lng": 51.0, "lat": 25.1, "t": 1_700_000_001_000}],
                         source="import")
            self.assertEqual(store.counts_by_source(), {"live": 1, "import": 1})
        finally:
            store.close()

    def test_migration_is_idempotent(self):
        self.store.close()
        again = TraceStore(self.path)        # second open, same file
        try:
            again.append("track", [{"lng": 51.0, "lat": 25.0, "t": 1}], source="native")
            self.assertEqual(again.recent(1)[0]["source"], "native")
        finally:
            again.close()
        self.store = TraceStore(self.path)   # so tearDown has something to close


class ImportDigestTest(unittest.TestCase):
    """Ticket 19 / adr-0069 §6: a re-uploaded track is refused, not counted twice."""

    def setUp(self):
        self.path = tempfile.mktemp(suffix=".db")
        self.store = TraceStore(self.path)

    def tearDown(self):
        self.store.close()

    def test_unseen_digest_is_not_seen(self):
        self.assertFalse(self.store.digest_seen("deadbeef"))

    def test_recorded_digest_is_seen(self):
        self.store.record_digest("deadbeef", seen_ms=1_700_000_000_000)
        self.assertTrue(self.store.digest_seen("deadbeef"))

    def test_bulk_lookup(self):
        self.store.record_digest("aa", seen_ms=1)
        self.store.record_digest("bb", seen_ms=1)
        self.assertEqual(self.store.digests_seen(["aa", "cc", "bb"]), {"aa", "bb"})
        self.assertEqual(self.store.digests_seen([]), set())

    def test_recording_twice_does_not_duplicate(self):
        self.store.record_digest("aa", seen_ms=1)
        self.store.record_digest("aa", seen_ms=2)
        self.assertEqual(self.store.digests_seen(["aa"]), {"aa"})

    def test_digests_expire_on_the_import_window_not_the_ttl(self):
        """The digest deliberately outlives the 72 h raw-data TTL (adr-0069 §6),
        because it must refuse a re-upload of a track that is itself a year old."""
        now = 1_700_000_000_000
        self.store.record_digest("old", seen_ms=now - 400 * 86_400_000)
        self.store.record_digest("recent", seen_ms=now - 10 * 86_400_000)
        removed = self.store.expire_digests(
            older_than_ms=now - IMPORT_MAX_AGE_DAYS * 86_400_000)
        self.assertEqual(removed, 1)
        self.assertEqual(self.store.digests_seen(["old", "recent"]), {"recent"})

    def test_digest_survives_the_observation_vacuum(self):
        """The raw points go; the memory that they were uploaded stays."""
        self.store.append("track", [{"lng": 51.0, "lat": 25.0, "t": 1_000}],
                          source="import")
        self.store.record_digest("kept", seen_ms=1_700_000_000_000)
        self.store.expire(older_than_ms=1_700_000_000_000)
        self.assertEqual(self.store.count(), 0)
        self.assertTrue(self.store.digest_seen("kept"))


if __name__ == "__main__":
    unittest.main()