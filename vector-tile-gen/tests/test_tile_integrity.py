"""Regression test: every baked Vector MVT tile MUST be a valid `basemap` layer.

This locks in the fix for the "streets vanish because the layer name is
`vector`, not `basemap`" class of bug. The live MapLibre style filters on
``source-layer: 'basemap'``, so a tile written with any other layer name is
silently invisible — every feature dropped.

The check uses ``mapbox-vector-tile`` (the reference decoder, same as the
spec-anchor in ``test_tilegen.py``) as an INDEPENDENT oracle. ``decode_tile``
in our own code is self-consistent and would NOT catch a wrong layer name, so
we assert against the reference decoder's interpretation.
"""
import os
import tempfile
import unittest

from vector_tile_gen.encode import encode_tile
from vector_tile_gen.tiles import lonlat_to_tile

try:
    import mapbox_vector_tile as _mvt  # reference decoder oracle
    _HAVE_MVT = True
except Exception:  # pragma: no cover - exercised only without the dep
    _HAVE_MVT = False


def _load_validate_tiles():
    """Import the standalone verifier script (it lives in scripts/, not a package)."""
    import importlib.util
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "scripts", "validate_tiles.py")
    path = os.path.normpath(path)
    spec = importlib.util.spec_from_file_location("validate_tiles", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _feat(lon, lat, props=None):
    """A minimal road-like feature dict in the encode pipeline's contract."""
    z = 12
    x, y = lonlat_to_tile(z, lon, lat)
    return {
        "id": "f-%s,%s" % (lon, lat),
        "geometry_type": "Point",
        "coordinates": [lon, lat],
        "properties": props or {"kind": "road", "name": "Test Rd"},
        "_z": z, "_x": x, "_y": y,
    }


def _encode_baked(layer_name, feats):
    """Encode exactly as the build pipeline does (encode_tile([(layer, feats)]))."""
    return encode_tile([(layer_name, feats)])


class TestBakedLayerName(unittest.TestCase):
    def test_basemap_layer_is_valid(self):
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")
        feats = [_feat(51.51, 25.29), _feat(51.52, 25.30, {"kind": "park"})]
        data = _encode_baked("basemap", feats)
        dec = _mvt.decode(data)
        self.assertIn("basemap", dec)
        self.assertEqual(len(dec["basemap"]["features"]), 2)

    def test_vector_layer_name_is_rejected(self):
        """The historical bug: a tile baked with layer 'vector' is invisible."""
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")
        feats = [_feat(51.51, 25.29)]
        # The OLD/buggy layer name — the verifier must treat this as BAD.
        data = _encode_baked("vector", feats)
        dec = _mvt.decode(data)
        # The bug: the style's filter `['==', ['get','kind'], ...]` is scoped to
        # the 'basemap' source-layer, so a 'vector' layer never matches anything.
        self.assertIn("vector", dec)
        self.assertNotIn("basemap", dec)

    def test_zero_byte_tile_classifies_empty_not_bad(self):
        """A 0-byte MVT is a legitimate empty (ocean/desert) tile, not a defect."""
        _vt = _load_validate_tiles()
        with tempfile.NamedTemporaryFile(suffix=".mvt", delete=False) as fh:
            fh.write(b"")  # intentionally empty
            path = fh.name
        try:
            status, _ = _vt._classify(path)
            self.assertEqual(status, "empty")
        finally:
            os.remove(path)

    def test_wrong_layer_tile_classifies_bad(self):
        """A baked tile with the wrong layer name must be flagged BAD by the verifier."""
        # The only method in this class that missed the guard its siblings all
        # carry, so it errored on a host without the optional MVT encoder.
        if not _HAVE_MVT:
            self.skipTest("mapbox-vector-tile not installed")
        _vt = _load_validate_tiles()
        feats = [_feat(51.51, 25.29)]
        data = _encode_baked("vector", feats)  # wrong layer name
        with tempfile.NamedTemporaryFile(suffix=".mvt", delete=False) as fh:
            fh.write(data)
            path = fh.name
        try:
            status, reason = _vt._classify(path)
            self.assertEqual(status, "bad")
            self.assertIn("basemap", reason)
        finally:
            os.remove(path)


if __name__ == "__main__":
    unittest.main()
