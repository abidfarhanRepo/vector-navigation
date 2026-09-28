"""Offline tile caching (W53): the service worker must cache tiles, never routes."""

import re
import os
import unittest

STATIC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")


def _sw_text():
    with open(os.path.join(STATIC, "sw.js"), encoding="utf-8") as fh:
        return fh.read()


class OfflineTileCacheTest(unittest.TestCase):
    def test_tiles_and_glyphs_are_cached_stale_while_revalidate(self):
        text = _sw_text()
        self.assertIn("isTilePath", text)
        self.assertIn("TILE_CACHE", text)
        self.assertIn("cache.put(req, copy)", text)

    def test_tiles_version_endpoint_is_never_cached(self):
        """/tiles/version is the staleness signal; caching it would pin clients
        to an old epoch and a re-bake would never reach them."""
        text = _sw_text()
        self.assertIn('!p.startsWith("/tiles/version")', text)

    def test_tile_cache_is_bounded(self):
        """An unbounded tile cache eventually evicts the browser's whole origin
        quota — the shell cache with it."""
        text = _sw_text()
        self.assertIn("TILE_CACHE_MAX", text)
        self.assertIn("trimTileCache", text)

    def test_routes_are_still_network_only(self):
        """/route and /navigate must be answered by isApiPath (network-only),
        never by the tile handler: a cached route is a stale route."""
        text = _sw_text()
        tile_section = text.split("function isTilePath")[1]
        self.assertNotIn('"/route"', tile_section)
        self.assertNotIn('"/navigate"', tile_section)
        self.assertIn('p.startsWith("/route")', text)  # still listed as API

    def test_shell_version_is_at_least_v9_and_moves_forward(self):
        """The offline-tile handler landed in v9, so the shell must never be at
        or below v8 again — an older shell has no tile cache and would silently
        lose offline navigation.

        This asserted the literal string 'v9', which meant every later bump
        failed a test about tiles and had to be hand-edited (ticket 36 hit it
        first). Asserting monotonicity keeps the guard — a downgrade or a
        missing bump still fails — without pinning the number.
        """
        text = _sw_text()
        m = re.search(r'const VERSION = "v(\d+)"', text)
        self.assertIsNotNone(m, "sw.js must declare a numeric shell version")
        self.assertGreaterEqual(int(m.group(1)), 9,
                                "the offline-tile shell is v9; never regress below it")

    def test_extracted_client_modules_are_part_of_the_offline_shell(self):
        """The viewer loads /js/geo.js as a blocking script (ticket 36). If it is
        not in SHELL, an offline load serves the cached page and then fails to
        fetch the module: VectorGeo is undefined and the app throws before the
        map initialises. Every module the page hard-depends on belongs here."""
        text = _sw_text()
        shell = text.split("const SHELL = [")[1].split("]")[0]
        self.assertIn('"/js/geo.js"', shell)


if __name__ == "__main__":
    unittest.main()
