"""Composite font stacks (V4).

The defect these pin: every font in the glyph directory is single-script, and
the server treated a comma-joined stack as a literal directory name — so a
style that asked for Latin-plus-Arabic got an empty 200 and drew no labels at
all. See `vector_tile_server.glyphs` for the full account.
"""
import os
import shutil
import tempfile
import unittest

from vector_tile_server import glyphs


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
    """A minimal but structurally valid glyph submessage."""
    return (
        _varint((1 << 3) | 0) + _varint(gid)          # id
        + _delim(2, b"\x01\x02")                      # bitmap
        + _varint((3 << 3) | 0) + _varint(4)          # width
        + _varint((4 << 3) | 0) + _varint(6)          # height
        + _varint((5 << 3) | 0) + _varint(0)          # left
        + _varint((6 << 3) | 0) + _varint(0)          # top
        + _varint((7 << 3) | 0) + _varint(advance)    # advance
    )


def _pbf(name, rng, ids, advance=7):
    stack = (
        _delim(1, name.encode())
        + _delim(2, rng.encode())
        + b"".join(_delim(3, _glyph(i, advance)) for i in ids)
    )
    return _delim(1, stack)


class GlyphStackTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        glyphs._CACHE.clear()
        os.makedirs(os.path.join(self.dir, "Latin Font"))
        os.makedirs(os.path.join(self.dir, "Arabic Font"))
        # Latin has 0-255 only; Arabic has 1536-1791 only. This is the real
        # shape of Vector's glyph directory.
        with open(os.path.join(self.dir, "Latin Font", "0-255.pbf"), "wb") as f:
            f.write(_pbf("Latin Font", "0-255", [65, 66, 67]))
        with open(os.path.join(self.dir, "Arabic Font", "1536-1791.pbf"), "wb") as f:
            f.write(_pbf("Arabic Font", "1536-1791", [1600, 1601]))

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        glyphs._CACHE.clear()

    def _ids(self, data):
        return sorted(glyphs._glyph_id(g) for g in glyphs._glyphs_in(data))

    def test_parse_stack_splits_and_trims(self):
        self.assertEqual(glyphs.parse_stack("A,B"), ["A", "B"])
        self.assertEqual(glyphs.parse_stack("A, B"), ["A", "B"])
        self.assertEqual(glyphs.parse_stack("A,,B"), ["A", "B"])
        self.assertEqual(glyphs.parse_stack(""), [])

    def test_single_font_is_served_byte_for_byte(self):
        """The overwhelmingly common case must not change at all."""
        want = open(os.path.join(self.dir, "Latin Font", "0-255.pbf"), "rb").read()
        self.assertEqual(glyphs.build(self.dir, "Latin Font", "0-255"), want)

    def test_composite_serves_the_font_that_has_the_range(self):
        """The bug, directly.

        A stack asking for Latin-then-Arabic over an Arabic range used to
        resolve to a directory named "Latin Font,Arabic Font" and return
        nothing. It must return the Arabic glyphs.
        """
        data = glyphs.build(self.dir, "Latin Font,Arabic Font", "1536-1791")
        self.assertIsNotNone(data)
        self.assertEqual(self._ids(data), [1600, 1601])

    def test_composite_serves_latin_from_the_first_font(self):
        data = glyphs.build(self.dir, "Latin Font,Arabic Font", "0-255")
        self.assertIsNotNone(data)
        self.assertEqual(self._ids(data), [65, 66, 67])

    def test_composite_merges_when_both_fonts_have_the_range(self):
        with open(os.path.join(self.dir, "Arabic Font", "0-255.pbf"), "wb") as f:
            f.write(_pbf("Arabic Font", "0-255", [67, 68], advance=9))
        data = glyphs.build(self.dir, "Latin Font,Arabic Font", "0-255")
        self.assertEqual(self._ids(data), [65, 66, 67, 68])

    def test_first_font_in_the_stack_wins_a_shared_codepoint(self):
        """This is what makes the ORDER in the style mean something.

        `["Open Sans Regular", "Noto Kufi Arabic"]` has to mean "Latin from
        Open Sans"; if the later font won, adding a fallback would silently
        restyle every label that already rendered.
        """
        with open(os.path.join(self.dir, "Arabic Font", "0-255.pbf"), "wb") as f:
            f.write(_pbf("Arabic Font", "0-255", [65], advance=99))
        data = glyphs.build(self.dir, "Latin Font,Arabic Font", "0-255")
        merged = [g for g in glyphs._glyphs_in(data) if glyphs._glyph_id(g) == 65]
        self.assertEqual(len(merged), 1)
        # advance 7 is Latin Font's; 99 would mean the fallback overwrote it.
        original = [g for g in glyphs._glyphs_in(
            open(os.path.join(self.dir, "Latin Font", "0-255.pbf"), "rb").read()
        ) if glyphs._glyph_id(g) == 65]
        self.assertEqual(merged[0], original[0])

    def test_the_merged_stack_reports_the_requested_name(self):
        """MapLibre matches the reply's fontstack name against what it asked for."""
        data = glyphs.build(self.dir, "Latin Font,Arabic Font", "0-255")
        self.assertIn(b"Latin Font,Arabic Font", data)

    def test_missing_range_everywhere_is_None_not_empty(self):
        """None so the handler can 404.

        An empty 200 is indistinguishable from a range that genuinely has no
        glyphs, which is exactly why this defect was invisible.
        """
        self.assertIsNone(glyphs.build(self.dir, "Latin Font,Arabic Font", "8192-8447"))
        self.assertIsNone(glyphs.build(self.dir, "No Such Font", "0-255"))

    def test_a_corrupt_font_does_not_take_out_the_rest_of_the_stack(self):
        os.makedirs(os.path.join(self.dir, "Broken Font"))
        with open(os.path.join(self.dir, "Broken Font", "0-255.pbf"), "wb") as f:
            f.write(b"\x0a\xff\xff")     # truncated length-delimited field
        data = glyphs.build(self.dir, "Broken Font,Latin Font", "0-255")
        self.assertIsNotNone(data)
        self.assertEqual(self._ids(data), [65, 66, 67])

    def test_result_is_cached(self):
        a = glyphs.build(self.dir, "Latin Font,Arabic Font", "0-255")
        os.remove(os.path.join(self.dir, "Latin Font", "0-255.pbf"))
        b = glyphs.build(self.dir, "Latin Font,Arabic Font", "0-255")
        self.assertEqual(a, b)


if __name__ == "__main__":
    unittest.main()
