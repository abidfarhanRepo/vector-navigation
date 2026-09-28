import os
import tempfile
import unittest

from vector_map_store.geometry import bbox_contains
from vector_map_store.store import FeatureStore, StoredFeature

# Minimal duck-typed feature matching the ingestion.Feature interface.
class _F:
    def __init__(self, id, geometry_type, coordinates, bbox, properties=None):
        self.id = id
        self.geometry_type = geometry_type
        self.coordinates = coordinates
        self.bbox = bbox
        self.properties = properties or {}


class TestFeatureStore(unittest.TestCase):
    def _populated(self) -> FeatureStore:
        s = FeatureStore()
        s.insert(_F("a", "Point", [13.3777, 52.5163], (13.3777, 52.5163, 13.3777, 52.5163)))
        s.insert(_F("b", "Point", [13.4094, 52.5208], (13.4094, 52.5208, 13.4094, 52.5208)))
        s.insert(
            _F(
                "park",
                "Polygon",
                [[[13.339, 52.506], [13.373, 52.526]]],
                (13.339, 52.506, 13.373, 52.526),
            )
        )
        return s

    def test_insert_and_count(self):
        s = self._populated()
        self.assertEqual(s.count(), 3)

    def test_query_bbox_berlin_returns_all(self):
        s = self._populated()
        found = s.query_bbox((13.0, 52.0, 14.0, 53.0))
        self.assertEqual({f.id for f in found}, {"a", "b", "park"})

    def test_query_bbox_outside_returns_none(self):
        s = self._populated()
        found = s.query_bbox((0.0, 0.0, 1.0, 1.0))
        self.assertEqual(found, [])

    def test_query_bbox_partial_returns_subset(self):
        s = self._populated()
        # A small box around the TV tower only.
        found = s.query_bbox((13.40, 52.51, 13.42, 52.53))
        self.assertEqual({f.id for f in found}, {"b"})

    def test_save_load_roundtrip(self):
        s = self._populated()
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "store.json")
            s.save(p)
            s2 = FeatureStore()
            s2.load(p)
        self.assertEqual(s2.count(), 3)
        ids = {f.id for f in s2.all()}
        self.assertEqual(ids, {"a", "b", "park"})
        park = next(f for f in s2.all() if f.id == "park")
        self.assertEqual(park.geometry_type, "Polygon")
        self.assertEqual(park.bbox, (13.339, 52.506, 13.373, 52.526))

    def test_bbox_contains_helper(self):
        self.assertTrue(bbox_contains((0, 0, 10, 10), 5, 5))
        self.assertFalse(bbox_contains((0, 0, 10, 10), 11, 5))


if __name__ == "__main__":
    unittest.main()
