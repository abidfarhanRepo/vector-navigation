import unittest

# Self-contained: no cross-repo (vector-ingestion) dependency, so this test
# runs in the isolated CI container that only contains vector-tile-gen.
from vector_tile_gen.encode import (
    EXTENT_DEFAULT,
    decode_tile,
    encode_tile,
    lonlat_to_local,
    tile_bbox,
)
from vector_tile_gen.tiles import lonlat_to_tile
from vector_tile_gen.pipeline import generate_tile

# The MVT encoder is an OPTIONAL dependency (`mapbox-vector-tile`, declared in
# pyproject.toml). CI installs it and the bootstrap falls back to a container
# that has it, but a developer's host is often PEP-668 managed and cannot. When
# it is absent these tests ERRORED rather than skipped, so the suite was red on
# a working checkout for a reason that has nothing to do with the code — which
# is how people learn to ignore a red suite.
#
# `test_tile_integrity.py` already guarded itself this way; this is the same
# pattern, applied to the files that never adopted it. Nothing is skipped where
# the dependency exists.
try:
    import mapbox_vector_tile as _mvt  # noqa: F401
    _HAVE_MVT = True
except ImportError:  # pragma: no cover - environment-dependent
    _HAVE_MVT = False

_needs_mvt = unittest.skipUnless(_HAVE_MVT, "mapbox-vector-tile not installed")




def _fnv(s: str) -> int:
    h = 0xCBF29CE484222325
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def _feat(id, gtype, coords, props=None):
    x, y = lonlat_to_tile(12, coords[0] if gtype == "Point" else coords[0][0][0],
                          coords[1] if gtype == "Point" else coords[0][0][1])
    z = 12
    return {
        "id": id,
        "geometry_type": gtype,
        "coordinates": coords,
        "properties": props or {},
        "_z": z,
        "_x": x,
        "_y": y,
    }


class TestProjection(unittest.TestCase):
    def test_local_coords_within_extent(self):
        x, y = lonlat_to_tile(12, 13.3777, 52.5163); z = 12
        for lon, lat in [(13.3777, 52.5163), (13.4094, 52.5208)]:
            lx, ly = lonlat_to_local(lon, lat, z, x, y)
            self.assertTrue(0 <= lx <= EXTENT_DEFAULT, f"lx={lx}")
            self.assertTrue(0 <= ly <= EXTENT_DEFAULT, f"ly={ly}")

    def test_tile_bbox_monotonic(self):
        x, y = lonlat_to_tile(12, 13.3777, 52.5163); z = 12
        t = tile_bbox(z, x, y)
        self.assertLess(t[0], t[2])
        self.assertLess(t[1], t[3])


@_needs_mvt
class TestEncodeRoundTrip(unittest.TestCase):
    def test_point_roundtrip(self):
        feats = [_feat("poi-1", "Point", [13.3777, 52.5163], {"name": "Gate"})]
        data = encode_tile([("vector", feats)])
        dec = decode_tile(data)
        self.assertEqual(len(dec["layers"]), 1)
        layer = dec["layers"][0]
        self.assertEqual(layer["name"], "vector")
        self.assertEqual(len(layer["features"]), 1)
        f = layer["features"][0]
        self.assertEqual(f["type"], 1)  # POINT
        self.assertEqual(f["properties"]["name"], "Gate")
        ring = f["geometry"][0]
        self.assertEqual(len(ring), 1)
        lx, ly = ring[0]
        x, y = lonlat_to_tile(12, 13.3777, 52.5163); z = 12
        orig_lx, orig_ly = lonlat_to_local(13.3777, 52.5163, z, x, y)
        # MVT integer quantization introduces <=1 unit of error at extent 4096.
        self.assertAlmostEqual(lx, orig_lx, delta=1)
        self.assertAlmostEqual(ly, orig_ly, delta=1)

    def test_polygon_roundtrip(self):
        ring = [
            [13.3390, 52.5060],
            [13.3510, 52.5300],
            [13.3730, 52.5260],
            [13.3500, 52.5040],
            [13.3390, 52.5060],
        ]
        feats = [_feat("park", "Polygon", [ring], {"name": "Tiergarten"})]
        data = encode_tile([("vector", feats)])
        dec = decode_tile(data)
        f = dec["layers"][0]["features"][0]
        self.assertEqual(f["type"], 3)  # POLYGON
        # This ring runs ~636 tile units past the tile's east edge (lon 13.373
        # vs the edge at 13.359), so the encoder clips it at extent + 256
        # (layers.CLIP_BUFFER). The one vertex beyond the edge becomes TWO on
        # the clip line: closed ring of 6 points instead of the source's 5.
        ring_out = f["geometry"][0]
        self.assertEqual(len(ring_out), 6)
        self.assertEqual(max(x for x, _ in ring_out), 4096 + 256)
        self.assertEqual(sum(1 for x, _ in ring_out if x == 4096 + 256), 2)
        self.assertEqual(f["properties"]["name"], "Tiergarten")

    def test_multiple_features(self):
        feats = [
            _feat("a", "Point", [13.3777, 52.5163]),
            _feat("b", "Point", [13.4094, 52.5208]),
        ]
        data = encode_tile([("vector", feats)])
        dec = decode_tile(data)
        ids = {f["id"] for f in dec["layers"][0]["features"]}
        self.assertEqual(ids, {_fnv("a"), _fnv("b")})


@_needs_mvt
class TestPipeline(unittest.TestCase):
    def test_generate_tile_selects_only_intersecting(self):
        x, y = lonlat_to_tile(12, 13.3777, 52.5163); z = 12
        berlin = _make_feature("poi-berlin", "Point", [13.3777, 52.5163], z, x, y)
        tokyo = _make_feature("poi-tokyo", "Point", [139.69, 35.68], z + 100, x, y)
        data = generate_tile([berlin, tokyo], z, x, y)
        dec = decode_tile(data)
        ids = {f["id"] for f in dec["layers"][0]["features"]}
        self.assertIn(_fnv("poi-berlin"), ids)
        self.assertNotIn(_fnv("poi-tokyo"), ids)

    def test_generate_tile_produces_nonempty_bytes(self):
        x, y = lonlat_to_tile(12, 13.3777, 52.5163); z = 12
        f = _make_feature("p", "Point", [13.3777, 52.5163], z, x, y)
        data = generate_tile([f], z, x, y)
        self.assertTrue(len(data) > 0)
        # First byte of a Tile message is field 3 (layers) tag: (3<<3)|2 = 0x1A
        # (MVT 2.1 spec: `repeated Layer layers = 3;`). 0x1A is the standard
        # wire-format a real MVT decoder/MapLibre expects.
        self.assertEqual(data[0], 0x1A)


def _make_feature(id, gtype, coords, z, x, y):
    class _F:
        pass
    f = _F()
    f.id = id
    f.geometry_type = gtype
    f.coordinates = coords
    f.bbox = (coords[0], coords[1], coords[0], coords[1]) if gtype == "Point" else None
    f.properties = {}
    f._z = z
    f._x = x
    f._y = y
    return f


@_needs_mvt
class TestPipelineMultiZoom(unittest.TestCase):
    def test_generate_tile_at_non_12_zoom(self):
        # Exercise the pipeline at a NON-12 zoom and prove round-trip, using a
        # self-built feature (no cross-repo GeoJSON load) so it runs in CI.
        z = 10
        # Brandenburg Gate
        lon, lat = 13.3777, 52.5163
        x, y = lonlat_to_tile(z, lon, lat)
        feat = _make_feature("poi-brandenburg-gate", "Point", [lon, lat], z, x, y)
        data = generate_tile([feat], z, x, y)
        self.assertTrue(len(data) > 0)
        self.assertEqual(data[0], 0x1A)
        dec = decode_tile(data)
        self.assertEqual(len(dec["layers"]), 1)
        layer = dec["layers"][0]
        self.assertEqual(layer["name"], "vector")
        ids = {f["id"] for f in layer["features"]}
        self.assertIn(_fnv("poi-brandenburg-gate"), ids)
        self.assertGreaterEqual(len(layer["features"]), 1)


@_needs_mvt
class TestGeometryRoundTrip(unittest.TestCase):
    def test_point_geometry_survives_encode_tile_decode(self):
        # Geometry must survive the full generate_tile -> MVT -> decode path and
        # map back to the original lon/lat within quantization error.
        lon, lat = 13.3777, 52.5163  # Brandenburg Gate
        z = 12
        x, y = lonlat_to_tile(z, lon, lat)
        feat = _make_feature("poi-gate", "Point", [lon, lat], z, x, y)
        data = generate_tile([feat], z, x, y)

        dec = decode_tile(data)
        self.assertEqual(len(dec["layers"]), 1)
        layer = dec["layers"][0]
        f = layer["features"][0]
        self.assertEqual(f["type"], 1)  # POINT
        ring = f["geometry"][0]
        self.assertEqual(len(ring), 1)
        lx, ly = ring[0]  # integer tile-local coords recovered from the tile

        # Reconstruct lon/lat from tile_bbox. In tile-local space (0,0) is the
        # NW corner and (extent, extent) is the SE corner, so local y is
        # inverted vs. lat.
        min_lon, min_lat, max_lon, max_lat = tile_bbox(z, x, y)
        extent = layer["extent"]
        rec_lon = min_lon + (lx / extent) * (max_lon - min_lon)
        rec_lat = max_lat - (ly / extent) * (max_lat - min_lat)

        # Integer quantization error is < 0.5 EXTENT units; 1e-3 deg is ample.
        self.assertAlmostEqual(rec_lon, lon, delta=1e-3)
        self.assertAlmostEqual(rec_lat, lat, delta=1e-3)


def _rv(buf, pos):
    """Minimal unsigned varint reader (mirrors encode.py _read_varint)."""
    res = 0
    sh = 0
    while True:
        b = buf[pos]
        pos += 1
        res |= (b & 0x7F) << sh
        if not (b & 0x80):
            break
        sh += 7
    return res, pos


def _walk_fields(buf):
    """Return a list of (field_no, wire_type) tags in a protobuf message."""
    pos = 0
    n = len(buf)
    out = []
    while pos < n:
        key, pos = _rv(buf, pos)
        fld = key >> 3
        wt = key & 7
        if wt == 0:
            _, pos = _rv(buf, pos)
        elif wt == 2:
            ln, pos = _rv(buf, pos)
            pos += ln
        elif wt == 1:
            pos += 8
        elif wt == 5:
            pos += 4
        else:
            raise ValueError("unsupported wire type %d" % wt)
        out.append((fld, wt))
    return out


@_needs_mvt
class TestMvtSpecCompliance(unittest.TestCase):
    """Spec-anchor: the Layer body MUST use the MVT 2.1 field map
    (name=1, keys=2, values=3, extent=4, features=5). This is
    the independent check that caught the long-standing off-by-layout
    bug (features were emitted at field 2, so MapLibre rendered a
    BLANK map). `decode_tile` is self-consistent and would NOT
    catch it — only a field-tag assertion does."""

    def test_layer_fields_match_mvt_2_1(self):
        feats = [_feat("poi-x", "Point", [13.3777, 52.5163], {"name": "X"})]
        data = encode_tile([("vector", feats)])
        # Tile.layers = field 3, wire type 2 => tag 0x1A (a real MVT decoder /
        # MapLibre expects this as the first byte).
        self.assertEqual(data[0], 0x1A)

        # The authoritative check: a standard MVT decoder must parse the tile
        # and recover the layer + its feature with the original geometry. This
        # is what caught the long-standing off-by-layout bug (MapLibre rendered
        # a BLANK map). We verify with mapbox-vector-tile (the reference impl).
        import mapbox_vector_tile as mvt
        dec = mvt.decode(data)
        self.assertIn("vector", dec)
        layer = dec["vector"]
        self.assertEqual(len(layer["features"]), 1)
        f = layer["features"][0]
        self.assertEqual(f["geometry"]["type"], "Point")
        self.assertEqual(f["properties"]["name"], "X")


if __name__ == "__main__":
    unittest.main()
