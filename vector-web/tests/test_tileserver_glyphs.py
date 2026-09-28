"""Composite font stacks in the deployed tile server (V4).

`docker/tileserver.py` is the glyph server that actually runs (see the `tiles`
service in docker-compose.yml, built from `docker/Dockerfile.tiles`). It is a
standalone, dependency-free file copied into its image with no package around
it, so it carries its own copy of the merge logic.

This suite pins that copy to the same behaviour as the reference
implementation in `vector-tile-server/src/vector_tile_server/glyphs.py`. A copy
that drifts is worse than no copy: labels would render in one deployment and
not the other, and nothing would fail.

The defect being pinned: every font in the glyph directory is single-script,
and the server treated a comma-joined stack as a literal directory name, so a
style asking for Latin-plus-Arabic in one layer got an empty 200 and drew
nothing at all.
"""
import importlib.util
import os
import shutil
import tempfile
import unittest

HERE = os.path.dirname(__file__)
_SPEC = importlib.util.spec_from_file_location(
    "vector_tileserver_standin",
    os.path.join(HERE, "..", "docker", "tileserver.py"),
)
ts = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(ts)


def _varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _delim(fnum, payload):
    return _varint((fnum << 3) | 2) + _varint(len(payload)) + payload


def _glyph(gid, advance=7):
    return (
        _varint((1 << 3) | 0) + _varint(gid)
        + _delim(2, b"\x01\x02")
        + _varint((3 << 3) | 0) + _varint(4)
        + _varint((4 << 3) | 0) + _varint(6)
        + _varint((5 << 3) | 0) + _varint(0)
        + _varint((6 << 3) | 0) + _varint(0)
        + _varint((7 << 3) | 0) + _varint(advance)
    )


def _pbf(name, rng, ids, advance=7):
    stack = (
        _delim(1, name.encode())
        + _delim(2, rng.encode())
        + b"".join(_delim(3, _glyph(i, advance)) for i in ids)
    )
    return _delim(1, stack)


class TileServerGlyphStackTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        ts._GLYPH_CACHE.clear()
        # The real shape of Vector's glyph directory: two single-script fonts
        # with disjoint ranges.
        os.makedirs(os.path.join(self.dir, "Open Sans Regular"))
        os.makedirs(os.path.join(self.dir, "Noto Kufi Arabic"))
        with open(os.path.join(self.dir, "Open Sans Regular", "0-255.pbf"), "wb") as f:
            f.write(_pbf("Open Sans Regular", "0-255", [65, 66, 67]))
        with open(os.path.join(self.dir, "Noto Kufi Arabic", "1536-1791.pbf"), "wb") as f:
            f.write(_pbf("Noto Kufi Arabic", "1536-1791", [1600, 1601]))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        ts._GLYPH_CACHE.clear()

    def _ids(self, data):
        return sorted(ts._g_id(g) for g in ts._g_glyphs(data))

    def test_single_font_is_byte_for_byte_unchanged(self):
        want = open(os.path.join(self.dir, "Open Sans Regular", "0-255.pbf"), "rb").read()
        self.assertEqual(ts.glyph_stack(self.dir, "Open Sans Regular", "0-255"), want)

    def test_composite_reaches_the_arabic_font(self):
        """The defect, directly.

        This is the request Vector's road labels have to make to draw a name in
        either script, and it used to resolve to a directory called
        "Open Sans Regular,Noto Kufi Arabic".
        """
        data = ts.glyph_stack(self.dir, "Open Sans Regular,Noto Kufi Arabic", "1536-1791")
        self.assertIsNotNone(data)
        self.assertEqual(self._ids(data), [1600, 1601])

    def test_composite_reaches_the_latin_font(self):
        data = ts.glyph_stack(self.dir, "Open Sans Regular,Noto Kufi Arabic", "0-255")
        self.assertIsNotNone(data)
        self.assertEqual(self._ids(data), [65, 66, 67])

    def test_overlapping_ranges_merge(self):
        with open(os.path.join(self.dir, "Noto Kufi Arabic", "0-255.pbf"), "wb") as f:
            f.write(_pbf("Noto Kufi Arabic", "0-255", [67, 68]))
        data = ts.glyph_stack(self.dir, "Open Sans Regular,Noto Kufi Arabic", "0-255")
        self.assertEqual(self._ids(data), [65, 66, 67, 68])

    def test_first_font_wins_a_shared_codepoint(self):
        """What makes the ORDER in the style meaningful."""
        with open(os.path.join(self.dir, "Noto Kufi Arabic", "0-255.pbf"), "wb") as f:
            f.write(_pbf("Noto Kufi Arabic", "0-255", [65], advance=99))
        data = ts.glyph_stack(self.dir, "Open Sans Regular,Noto Kufi Arabic", "0-255")
        got = [g for g in ts._g_glyphs(data) if ts._g_id(g) == 65]
        want = [g for g in ts._g_glyphs(
            open(os.path.join(self.dir, "Open Sans Regular", "0-255.pbf"), "rb").read()
        ) if ts._g_id(g) == 65]
        self.assertEqual(got, want)

    def test_merged_reply_names_the_requested_stack(self):
        """MapLibre matches the reply's fontstack name against its request."""
        data = ts.glyph_stack(self.dir, "Open Sans Regular,Noto Kufi Arabic", "0-255")
        self.assertIn(b"Open Sans Regular,Noto Kufi Arabic", data)

    def test_absent_everywhere_is_None(self):
        """None, so the handler still returns its empty-but-valid stack.

        MapLibre probes ranges nobody ships (PUA 65024-65279); the empty-stack
        reply for those is deliberate and must survive this change.
        """
        self.assertIsNone(
            ts.glyph_stack(self.dir, "Open Sans Regular,Noto Kufi Arabic", "8192-8447"))
        self.assertIsNone(ts.glyph_stack(self.dir, "No Such Font", "0-255"))
        self.assertIsNone(ts.glyph_stack(self.dir, "", "0-255"))

    def test_whitespace_after_the_comma_is_tolerated(self):
        data = ts.glyph_stack(self.dir, "Open Sans Regular, Noto Kufi Arabic", "1536-1791")
        self.assertEqual(self._ids(data), [1600, 1601])

    def test_a_corrupt_font_does_not_take_out_the_stack(self):
        os.makedirs(os.path.join(self.dir, "Broken"))
        with open(os.path.join(self.dir, "Broken", "0-255.pbf"), "wb") as f:
            f.write(b"\x0a\xff\xff")
        data = ts.glyph_stack(self.dir, "Broken,Open Sans Regular", "0-255")
        self.assertIsNotNone(data)
        self.assertEqual(self._ids(data), [65, 66, 67])

    def test_it_agrees_with_the_reference_implementation(self):
        """Pins the deliberate duplicate named in the module docstring.

        Skipped rather than failed when vector-tile-server is not on the path,
        because this repo must be testable on its own.
        """
        import sys
        ref_src = os.path.join(HERE, "..", "..", "vector-tile-server", "src")
        if not os.path.isdir(ref_src):
            self.skipTest("vector-tile-server not present")
        sys.path.insert(0, os.path.abspath(ref_src))
        try:
            from vector_tile_server import glyphs as ref
        except ImportError:
            self.skipTest("vector_tile_server not importable")
        ref._CACHE.clear()
        for stack, rng in (
            ("Open Sans Regular", "0-255"),
            ("Open Sans Regular,Noto Kufi Arabic", "0-255"),
            ("Open Sans Regular,Noto Kufi Arabic", "1536-1791"),
            ("Open Sans Regular,Noto Kufi Arabic", "8192-8447"),
        ):
            self.assertEqual(
                ts.glyph_stack(self.dir, stack, rng),
                ref.build(self.dir, stack, rng),
                "copies disagree for %s / %s" % (stack, rng),
            )
        ref._CACHE.clear()


if __name__ == "__main__":
    unittest.main()
