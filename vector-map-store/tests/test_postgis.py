import os
import tempfile
import unittest

from vector_map_store.store import FeatureStore, MemoryFeatureStore, StoredFeature

# Minimal duck-typed feature matching the ingestion.Feature interface.
class _F:
    def __init__(self, id, geometry_type, coordinates, bbox, properties=None):
        self.id = id
        self.geometry_type = geometry_type
        self.coordinates = coordinates
        self.bbox = bbox
        self.properties = properties or {}


def _populated(store) -> None:
    store.insert(_F("a", "Point", [13.3777, 52.5163], (13.3777, 52.5163, 13.3777, 52.5163)))
    store.insert(_F("b", "Point", [13.4094, 52.5208], (13.4094, 52.5208, 13.4094, 52.5208)))
    # Valid closed Polygon ring (first == last) within (13.339,52.506)-(13.373,52.526).
    ring = [
        [13.339, 52.506],
        [13.373, 52.506],
        [13.373, 52.526],
        [13.339, 52.526],
        [13.339, 52.506],
    ]
    store.insert(_F("park", "Polygon", [ring], (13.339, 52.506, 13.373, 52.526)))


@unittest.skipUnless(
    os.environ.get("VECTOR_PG_DSN"),
    "VECTOR_PG_DSN not set — skipping live PostGIS integration test",
)
class TestPostGISParity(unittest.TestCase):
    """Behavioral parity between the PostGIS backend and the in-memory backend.

    Only runs when a live PostGIS is reachable via VECTOR_PG_DSN (e.g. the
    repo's docker-compose). In isolated CI the DSN is absent, so this is
    skipped and the gate stays green without a database.
    """

    def _store(self):
        return FeatureStore()  # resolves to PostGIS via VECTOR_PG_DSN

    def tearDown(self):
        # Close the PostGIS connection opened per-test to avoid ResourceWarnings.
        s = getattr(self, "_s", None)
        if s is not None and hasattr(s, "close"):
            s.close()

    def test_insert_and_count(self):
        s = self._store()
        _populated(s)
        self.assertEqual(s.count(), 3)

    def test_query_bbox_returns_all(self):
        s = self._store()
        _populated(s)
        found = s.query_bbox((13.0, 52.0, 14.0, 53.0))
        self.assertEqual({f.id for f in found}, {"a", "b", "park"})

    def test_query_bbox_outside_returns_none(self):
        s = self._store()
        _populated(s)
        self.assertEqual(s.query_bbox((0.0, 0.0, 1.0, 1.0)), [])

    def test_query_bbox_partial_returns_subset(self):
        s = self._store()
        _populated(s)
        found = s.query_bbox((13.40, 52.51, 13.42, 52.53))
        self.assertEqual({f.id for f in found}, {"b"})

    def test_save_load_roundtrip_file_parity(self):
        # PostGIS.save/load must round-trip the same as memory.
        s = self._store()
        _populated(s)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "store.json")
            s.save(p)
            s2 = MemoryFeatureStore()
            s2.load(p)
        self.assertEqual(s2.count(), 3)
        ids = {f.id for f in s2.all()}
        self.assertEqual(ids, {"a", "b", "park"})


if __name__ == "__main__":
    unittest.main()
