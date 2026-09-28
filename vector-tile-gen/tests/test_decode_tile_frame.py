import os
import sys

# Self-bootstrap so the test runs with a plain `python -m unittest` even without
# an externally supplied PYTHONPATH (mirrors the runner's src wiring).
_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
    _src = os.path.join(_ROOT, _repo, "src")
    if _src not in sys.path:
        sys.path.insert(0, _src)

import unittest

from vector_tile_gen.encode import EXTENT_DEFAULT, decode_tile, encode_tile
from vector_tile_gen.tiles import lonlat_to_tile
import math

try:
    import mapbox_vector_tile as _mvt  # noqa: F401
    _HAVE_MVT = True
except ImportError:  # pragma: no cover - environment-dependent
    _HAVE_MVT = False


@unittest.skipUnless(_HAVE_MVT, "mapbox-vector-tile not installed")
class DecodeTileCoordinateFrameTest(unittest.TestCase):
    """AC-L7 — pin the coordinate frame ``decode_tile`` actually returns.

    Its docstring used to promise geographic ``[lon, lat]``. It returns
    tile-local, y-DOWN values in ``0..extent``. The function was right and the
    prose was wrong, and the prose produced an incorrect measurement before
    anyone noticed.

    A docstring cannot be asserted, so this asserts the thing the docstring
    describes. If someone "corrects" ``decode_tile`` into returning lon/lat,
    or flips the y axis, this fails instead of the next measurement quietly
    being wrong.

    Doha, because that is the data this repository actually bakes.
    """

    Z = 14
    LON, LAT = 51.5310, 25.3145  # West Bay

    @staticmethod
    def _tile_bounds(z, x, y):
        """(west, south, east, north) for a slippy tile. Inlined: `tiles.py`
        exposes only the forward projection, and pulling a whole helper in for
        one test would be more code than the four lines of arithmetic."""
        n = 2.0 ** z
        west = x / n * 360.0 - 180.0
        east = (x + 1) / n * 360.0 - 180.0
        north = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
        south = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
        return west, south, east, north

    def _decode_point_at(self, lon, lat):
        """Encode one point into the tile that CONTAINS (self.LON, self.LAT)."""
        x, y = lonlat_to_tile(self.Z, self.LON, self.LAT)
        feat = {
            "id": "probe",
            "geometry_type": "Point",
            "coordinates": [lon, lat],
            "properties": {},
            "_z": self.Z, "_x": x, "_y": y,
        }
        layers = decode_tile(encode_tile([("probe", [feat])]))["layers"]
        self.assertEqual(len(layers), 1)
        feats = layers[0]["features"]
        self.assertEqual(len(feats), 1)
        return feats[0]["geometry"][0][0]

    def test_decode_tile_is_not_geographic(self):
        # The decisive negative. Doha is near lon 51, lat 25. Tile-local values
        # are 0..extent, so they cannot be mistaken for the input degrees.
        px, py = self._decode_point_at(self.LON, self.LAT)
        self.assertGreater(px, 180, "x is tile-local, not a longitude")
        self.assertGreater(py, 90, "y is tile-local, not a latitude")
        self.assertLessEqual(px, EXTENT_DEFAULT)
        self.assertLessEqual(py, EXTENT_DEFAULT)

    def test_decode_tile_y_increases_downward(self):
        # North is UP on the map, so a point nearer the tile's NORTH edge must
        # decode to a SMALLER y. Under the y-UP frame that mapbox-vector-tile
        # hands back natively, this assertion inverts.
        x, y = lonlat_to_tile(self.Z, self.LON, self.LAT)
        w, s, e, n = self._tile_bounds(self.Z, x, y)
        lon_mid = (w + e) / 2.0
        inset = (n - s) * 0.05

        _, y_north = self._decode_point_at(lon_mid, n - inset)
        _, y_south = self._decode_point_at(lon_mid, s + inset)

        self.assertLess(
            y_north, y_south,
            f"y must increase DOWNWARD: north={y_north} south={y_south}",
        )
        self.assertLess(y_north, EXTENT_DEFAULT * 0.25, "north edge should be a small y")
        self.assertGreater(y_south, EXTENT_DEFAULT * 0.75, "south edge should be a large y")

    def test_decode_tile_x_increases_eastward(self):
        x, y = lonlat_to_tile(self.Z, self.LON, self.LAT)
        w, s, e, n = self._tile_bounds(self.Z, x, y)
        lat_mid = (s + n) / 2.0
        inset = (e - w) * 0.05

        x_west, _ = self._decode_point_at(w + inset, lat_mid)
        x_east, _ = self._decode_point_at(e - inset, lat_mid)

        self.assertLess(x_west, x_east, "x must increase EASTWARD")


if __name__ == "__main__":
    unittest.main()
