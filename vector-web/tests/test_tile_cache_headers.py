"""A tile that exists must tell the phone it may keep it.

The 2026-09-14 Qatar drives measured 1,127 tile requests for 150 distinct
tiles -- 87% of all tile traffic was a re-download, the same tile fetched
again a median of 33 s later, ~48 MB of mobile data spent re-fetching bytes
the phone had just discarded. The cause was not the client: MapLibre caches
what HTTP tells it it may cache, and `_serve_file` sent no caching directive
at all for a real `.mvt`, while the *empty* 204 tile and the glyph ranges
both sent `max-age=86400`. The cheap answers were cacheable and the 48 KB
ones were not.

Caching here is safe by construction, which is why this is a header bug and
not a design question: the client puts the bake epoch in the query string
(`?v=<epoch>`, `VectorApi.TileSet`), so a re-bake changes every tile URL and
no stale tile can outlive one. These tests pin the header on, so a future
edit to `_serve_file` cannot quietly put the drives back on the slow path.
"""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(__file__)
_SPEC = importlib.util.spec_from_file_location(
    "vector_tileserver_cache_standin",
    os.path.join(HERE, "..", "docker", "tileserver.py"),
)
ts = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ts)


class _Recorder:
    """Captures what a Handler would put on the wire, without a socket."""

    def __init__(self, path):
        self.path = path
        self.status = None
        self.headers = {}
        self.body = b""

    # -- BaseHTTPRequestHandler surface used by do_GET --------------------
    def send_response(self, code):
        self.status = code

    def send_header(self, k, v):
        self.headers[k] = v

    def end_headers(self):
        pass

    @property
    def wfile(self):
        return self

    def write(self, b):
        self.body += b

    def log_message(self, *a):
        pass

    # `do_GET` dispatches real tiles through the Handler's own file-sending
    # helper, so borrow it unbound rather than reimplementing it -- the point
    # of this suite is to test the headers _serve_file actually emits.
    _serve_file = ts.Handler._serve_file


def _get(tmp, url_path):
    """Run tileserver's do_GET against a temp tile dir and record the reply."""
    rec = _Recorder(url_path)
    rec.send_error = lambda *a, **k: None
    prev_tiles, prev_glyphs = ts.TILE_DIR, ts.GLYPH_DIR
    ts.TILE_DIR = os.path.join(tmp, "tiles")
    ts.GLYPH_DIR = os.path.join(tmp, "glyphs")
    try:
        ts.Handler.do_GET(rec)
    finally:
        ts.TILE_DIR, ts.GLYPH_DIR = prev_tiles, prev_glyphs
    return rec


class TileCacheHeaderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        z15 = os.path.join(self.tmp, "tiles", "15", "21076")
        os.makedirs(z15)
        self.tile_bytes = b"\x1a\x0512345"
        with open(os.path.join(z15, "14006.mvt"), "wb") as fh:
            fh.write(self.tile_bytes)

    def test_a_real_tile_is_cacheable(self):
        """The 48 KB answer is the one worth keeping."""
        rec = _get(self.tmp, "/tiles/15/21076/14006.mvt?v=1789354701")
        self.assertEqual(200, rec.status)
        self.assertEqual(self.tile_bytes, rec.body)
        cc = rec.headers.get("Cache-Control")
        self.assertIsNotNone(
            cc, "a tile that exists must carry a caching directive; sending "
                "none is what made 87% of drive tile traffic a re-download")
        self.assertIn("public", cc)
        self.assertIn("max-age=", cc)

    def test_the_lifetime_outlives_a_drive(self):
        """A median 33 s re-fetch gap means a short max-age fixes nothing."""
        rec = _get(self.tmp, "/tiles/15/21076/14006.mvt?v=1789354701")
        cc = rec.headers["Cache-Control"]
        age = int(cc.split("max-age=")[1].split(",")[0].strip())
        self.assertGreaterEqual(
            age, 86400,
            "a tile URL is epoch-versioned and its bytes never change, so the "
            "lifetime should span drives, not minutes")

    def test_the_empty_tile_stays_cacheable_too(self):
        """The 204 path already had this right -- don't regress it."""
        rec = _get(self.tmp, "/tiles/15/21076/99999.mvt?v=1789354701")
        self.assertEqual(204, rec.status)
        self.assertIn("max-age=", rec.headers.get("Cache-Control", ""))

    def test_the_version_endpoint_is_never_cached(self):
        """`/tiles/version` is the response that INVALIDATES the others."""
        rec = _get(self.tmp, "/tiles/version")
        self.assertEqual(200, rec.status)
        self.assertEqual("no-store", rec.headers.get("Cache-Control"))


if __name__ == "__main__":
    unittest.main()
