"""Unit tests for vector-tile-server's TileSource cache-layer guard.

These verify the "streets vanish" runtime guard (G3): a cached tile that is
empty or carries the wrong layer name must be regenerated, never served.
No network — uses a tiny in-memory GeoJSON fixture.
"""
import os
import tempfile
import unittest

# Allow running as a standalone module and inside the package.
_HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (os.path.join(_HERE, "..", "src"),
           os.path.join(_HERE, "..", "..", "vector-tile-gen", "src"),
           os.path.join(_HERE, "..", "..", "vector-ingestion", "src")):
    _p = os.path.normpath(_p)
    if os.path.isdir(_p) and _p not in os.sys.path:
        os.sys.path.insert(0, _p)

from vector_tile_server.serve import TileSource, _cached_tile_ok  # noqa: E402
from vector_tile_gen.encode import encode_tile  # noqa: E402
from vector_tile_gen.tiles import lonlat_to_tile  # noqa: E402


def _write_geojson(path, feats):
    import json
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f)


def _pt(lon, lat, props=None):
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [lon, lat]},
        "properties": props or {"kind": "road", "name": "Rd"},
    }


class TestCacheGuard(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="tilesrv-test-")
        geo = os.path.join(self.tmp, "src.geojson")
        # A single road point in Doha so z13/14 tiles around it generate.
        self._geojson = geo
        _write_geojson(geo, [_pt(51.51, 25.29)])

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _cache_path(self, src, z, x, y):
        return os.path.join(src.cache_dir, str(z), str(x), f"{y}.mvt")

    def _xy(self, z):
        return lonlat_to_tile(z, 51.51, 25.29)

    def test_fresh_generation_caches_valid_basemap_tile(self):
        src = TileSource(self._geojson, cache_dir=self.tmp)
        z = 13
        x, y = self._xy(z)
        data = src.cached_generate(z, x, y)
        self.assertTrue(len(data) > 0)
        self.assertTrue(_cached_tile_ok(data))

    def test_stale_empty_marker_is_regenerated(self):
        src = TileSource(self._geojson, cache_dir=self.tmp)
        z = 13
        x, y = self._xy(z)
        # Pre-seed a 0-byte stale marker (as a previous no-geojson launch would).
        cpath = self._cache_path(src, z, x, y)
        os.makedirs(os.path.dirname(cpath), exist_ok=True)
        with open(cpath, "wb") as f:
            f.write(b"")
        # cached_generate must ignore the stale marker and regenerate a real tile.
        data = src.cached_generate(z, x, y)
        self.assertTrue(_cached_tile_ok(data))
        self.assertTrue(len(data) > 0)

    def test_wrong_layer_marker_is_regenerated(self):
        src = TileSource(self._geojson, cache_dir=self.tmp)
        z = 13
        x, y = self._xy(z)
        # Pre-seed a tile with the OLD/buggy "vector" layer name.
        cpath = self._cache_path(src, z, x, y)
        os.makedirs(os.path.dirname(cpath), exist_ok=True)
        bad = encode_tile([("vector", [])])
        with open(cpath, "wb") as f:
            f.write(bad)
        # _cached_tile_ok must reject it; cached_generate must regenerate a basemap tile.
        self.assertFalse(_cached_tile_ok(bad))
        data = src.cached_generate(z, x, y)
        self.assertTrue(_cached_tile_ok(data))

    def test_valid_cached_tile_is_served_untouched(self):
        src = TileSource(self._geojson, cache_dir=self.tmp)
        z = 13
        x, y = self._xy(z)
        first = src.cached_generate(z, x, y)
        # Second call should hit the cache and return the same bytes (no regen).
        second = src.cached_generate(z, x, y)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
