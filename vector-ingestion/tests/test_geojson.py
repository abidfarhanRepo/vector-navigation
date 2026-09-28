import os
import unittest

from vector_ingestion.geojson import Feature, load_geojson

HERE = os.path.dirname(__file__)
SAMPLE = os.path.join(HERE, "data", "sample.geojson")


def _flatten(coords):
    out = []

    def walk(c):
        if isinstance(c[0], (int, float)):
            out.append((c[0], c[1]))
        else:
            for s in c:
                walk(s)

    walk(coords)
    return out


class TestGeoJSONLoad(unittest.TestCase):
    def test_loads_all_sample_features(self):
        feats = load_geojson(SAMPLE)
        self.assertEqual(len(feats), 12)

    def test_feature_ids_preserved(self):
        feats = load_geojson(SAMPLE)
        ids = {f.id for f in feats}
        self.assertEqual(
            ids,
            {
                "poi-brandenburg-gate",
                "poi-tv-tower",
                "poi-charite",
                "park-tiergarten",
                "poi-reichstag",
                "poi-berlin-cathedral",
                "poi-checkpoint-charlie",
                "poi-potsdamer-platz",
                "poi-alexanderplatz",
                "park-treptower",
                "district-mitte",
                "route-stresemannstrasse",
            },
        )

    def test_coordinates_normalized_in_range(self):
        feats = load_geojson(SAMPLE)
        for f in feats:
            for lon, lat in _flatten(f.coordinates):
                self.assertTrue(-180.0 <= lon < 180.0, f"lon {lon} out of range")
                self.assertTrue(-90.0 <= lat <= 90.0, f"lat {lat} out of range")

    def test_longitude_wrapped_past_antimeridian(self):
        # -240.0 -> wrapped to 120.0
        feats = load_geojson(SAMPLE)
        charite = next(f for f in feats if f.id == "poi-charite")
        lon, _ = charite.coordinates
        self.assertAlmostEqual(lon, 120.0, places=6)

    def test_bbox_computed_for_every_feature(self):
        feats = load_geojson(SAMPLE)
        for f in feats:
            self.assertIsNotNone(f.bbox)
            min_lon, min_lat, max_lon, max_lat = f.bbox
            self.assertLessEqual(min_lon, max_lon)
            self.assertLessEqual(min_lat, max_lat)

    def test_geometry_types_preserved(self):
        feats = load_geojson(SAMPLE)
        by_id = {f.id: f.geometry_type for f in feats}
        self.assertEqual(by_id["poi-tv-tower"], "Point")
        self.assertEqual(by_id["park-tiergarten"], "Polygon")
        self.assertEqual(by_id["route-stresemannstrasse"], "LineString")

    def test_feature_is_dataclass(self):
        f = Feature(id="x", geometry_type="Point", coordinates=[13.4, 52.5])
        self.assertEqual(f.to_dict()["id"], "x")


if __name__ == "__main__":
    unittest.main()
