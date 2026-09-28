import os
import unittest

from vector_traffic.store import MemoryTrafficStateStore, TrafficStateStore
from vector_traffic.traffic import TrafficSegment


def _seg(segment_id, coords, congestion="free", mean=50.0, ff=50.0, probes=3):
    return TrafficSegment(
        segment_id=segment_id,
        geometry=[(float(lon), float(lat)) for lon, lat in coords],
        free_flow_kmh=ff,
        mean_speed_kmh=mean,
        probe_count=probes,
        congestion=congestion,
    )


class TestMemoryTrafficStateStore(unittest.TestCase):
    """In-memory store behavior (always runs; no DB needed)."""

    def setUp(self):
        # Ensure the factory returns the in-memory backend regardless of env.
        self._saved_dsn = os.environ.pop("VECTOR_PG_DSN", None)

    def tearDown(self):
        if self._saved_dsn is not None:
            os.environ["VECTOR_PG_DSN"] = self._saved_dsn

    def test_upsert_get_all(self):
        s = MemoryTrafficStateStore()
        s.upsert(_seg("s1", [(13.0, 52.0), (13.1, 52.1)], "free"))
        s.upsert(_seg("s2", [(13.2, 52.2), (13.3, 52.3)], "heavy"))
        self.assertEqual(s.get("s1").congestion, "free")
        self.assertEqual({st.segment_id for st in s.all()}, {"s1", "s2"})

    def test_query_bbox_spatial(self):
        s = MemoryTrafficStateStore()
        s.upsert(_seg("in", [(13.05, 52.05), (13.06, 52.06)], "free"))
        s.upsert(_seg("out", [(20.0, 20.0), (20.1, 20.1)], "free"))
        found = s.query_bbox((13.0, 52.0, 13.1, 52.1))
        self.assertEqual({st.segment_id for st in found}, {"in"})

    def test_factory_default_is_memory(self):
        self.assertIsInstance(TrafficStateStore(), MemoryTrafficStateStore)


@unittest.skipUnless(
    os.environ.get("VECTOR_PG_DSN"),
    "VECTOR_PG_DSN not set — skipping live PostGIS integration test",
)
class TestPostGISTrafficStateParity(unittest.TestCase):
    """Behavioral parity between PostGIS and in-memory stores (live PostGIS)."""

    def setUp(self):
        self.store = TrafficStateStore()  # resolves to PostGIS via VECTOR_PG_DSN
        # Isolate from other test methods sharing the live DB.
        import psycopg

        with psycopg.connect(os.environ["VECTOR_PG_DSN"], autocommit=True) as c, c.cursor() as cur:
            cur.execute("TRUNCATE traffic_state")

    def tearDown(self):
        if hasattr(self.store, "close"):
            self.store.close()

    def test_upsert_and_get(self):
        self.store.upsert(_seg("s1", [(13.0, 52.0), (13.1, 52.1)], "moderate", mean=30.0))
        got = self.store.get("s1")
        self.assertIsNotNone(got)
        self.assertEqual(got.congestion, "moderate")
        self.assertEqual(got.mean_speed_kmh, 30.0)

    def test_query_bbox(self):
        self.store.upsert(_seg("in", [(13.05, 52.05), (13.06, 52.06)], "free"))
        self.store.upsert(_seg("out", [(20.0, 20.0), (20.1, 20.1)], "free"))
        found = self.store.query_bbox((13.0, 52.0, 13.1, 52.1))
        self.assertEqual({st.segment_id for st in found}, {"in"})


if __name__ == "__main__":
    unittest.main()
