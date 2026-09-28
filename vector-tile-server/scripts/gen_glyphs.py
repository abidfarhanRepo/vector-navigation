"""Generate self-hosted MapLibre SDF glyph PBFs from a TTF (no third-party runtime deps).

Why: the web + mobile viewers need font glyphs to draw place labels. MapLibre's
public demo font endpoint (demotiles.maplibre.org/font/...) is a THIRD-PARTY
service, which violates Vector's "no third-party" rule. This script bakes SDF
glyph PBFs from a local TTF (e.g. DejaVuSans, BSD-licensed) into
``glyphs/<fontstack>/<start>-<end>.pbf`` so the tile server can serve them from
our own origin (``GET /glyphs/{fontstack}/{range}.pbf``).

Output format = MapLibre glyph PBF (protobuf), mirrored by maplibre-gl.js.

Usage:
  python scripts/gen_glyphs.py --ttf /usr/share/fonts/.../DejaVuSans.ttf \
      --out glyphs --fontstack "Open Sans Regular"
"""
from __future__ import annotations

import argparse
import os
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageFont, ImageDraw


# --------------------------------------------------------------------------
# Protobuf (manual, minimal) — MapLibre glyph stack format.
# GlyphStack { repeated GlyphStack stacks = 1; }
#   GlyphStack { string name = 1; repeated Glyph glyphs = 2; }
# Glyph { uint32 id = 1; bytes bitmap = 2; uint32 width = 3;
#         uint32 height = 4; sint32 left = 5; sint32 top = 6; uint32 advance = 7; }
# --------------------------------------------------------------------------
def _wire_varint(n: int) -> bytes:
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def _wire_tag(field: int, wire: int) -> bytes:
    return _wire_varint((field << 3) | wire)


def _encode_string(field: int, s: str) -> bytes:
    b = s.encode("utf-8")
    return _wire_tag(field, 2) + _wire_varint(len(b)) + b


def _encode_bytes(field: int, b: bytes) -> bytes:
    return _wire_tag(field, 2) + _wire_varint(len(b)) + b


def _encode_uint(field: int, n: int) -> bytes:
    return _wire_tag(field, 0) + _wire_varint(n)


def _encode_sint(field: int, n: int) -> bytes:
    # zigzag (protobuf sint32/sint64): (n << 1) ^ (n >> 63), masked to 64 bits.
    # NOTE: Python's >> on negatives is arithmetic, so use the 64-bit form and
    # mask; this yields the canonical zigzag value for both sint32 and sint64.
    zz = ((n << 1) ^ (n >> 63)) & ((1 << 64) - 1)
    return _wire_tag(field, 0) + _wire_varint(zz)


@dataclass
class Glyph:
    codepoint: int
    bitmap: bytes
    width: int
    height: int
    left: int
    top: int
    advance: int


def encode_glyph(g: Glyph) -> bytes:
    parts = [
        _encode_uint(1, g.codepoint),
        _encode_bytes(2, g.bitmap),
        _encode_uint(3, g.width),
        _encode_uint(4, g.height),
        _encode_sint(5, g.left),
        _encode_sint(6, g.top),
        _encode_uint(7, g.advance),
    ]
    return b"".join(parts)


def encode_stack(name: str, glyphs: list[Glyph]) -> bytes:
    # MapLibre expects the top-level message to BE a GlyphStack
    # (field 1 = name string, field 2 = repeated Glyph). Do NOT wrap it in an
    # extra outer message: that makes MapLibre misparse `name` as a nested
    # message and silently drop every glyph (no labels render).
    return _encode_string(1, name) + b"".join(_encode_tag_glyphs(glyphs))


def _encode_tag_glyphs(glyphs: list[Glyph]) -> list[bytes]:
    out = []
    for g in glyphs:
        enc = encode_glyph(g)
        out.append(_wire_tag(2, 2) + _wire_varint(len(enc)) + enc)
    return out


# --------------------------------------------------------------------------
# SDF generation
# --------------------------------------------------------------------------
# SDF generation (fixed cell)
# --------------------------------------------------------------------------
# MapLibre expects each glyph as an SDF bitmap in a FIXED square cell (the
# glyph atlas stride). Glyphs must NOT be sized to their raw bounding box:
# some Arabic presentation-form codepoints report enormous getbbox() values
# which produce multi-hundred-KB bitmaps and corrupt the range file. We
# render every glyph into a CELL x CELL canvas, baseline-aligned, so the
# bitmap is always CELL*CELL bytes and metrics are well-defined.
from scipy.ndimage import distance_transform_edt

CELL = 64          # glyph atlas cell (matches typical MapLibre stride)
PAD = 8            # SDF margin inside the cell
FONT_PX = CELL - 2 * PAD


def rasterize_glyph(font, ch, size, pad=PAD):
    """Render a glyph into a fixed CELL x CELL alpha mask, baseline-aligned.

    Returns (mask, left, top, advance) or None if the glyph is blank.
    `left`/`top` are signed offsets MapLibre needs (pixels from the pen
    origin to the bitmap's left/top).
    """
    try:
        bm = font.getmask(ch)
    except OSError:
        return None
    # getmask returns a flat internal bitmap (ImagingCore); rebuild a clean 2D
    # "L" numpy array via an intermediate Image so the shape is well-defined.
    glyph = Image.new("L", bm.size)
    glyph.putdata(bm)
    m = np.array(glyph, dtype=np.float32)
    if m.size == 0:
        return None
    mh, mw = m.shape
    if mh == 0 or mw == 0:
        return None
    # scale to fit FONT_PX while keeping aspect
    scale = min(FONT_PX / mh, FONT_PX / mw, 1.0)
    if scale < 1.0:
        imm = Image.fromarray((m * 255).clip(0, 255).astype(np.uint8))
        imm = imm.resize((max(1, int(mw * scale)), max(1, int(mh * scale))),
                         Image.LANCZOS)
        m = np.array(imm, dtype=np.float32) / 255.0
        mh, mw = m.shape
    canvas = np.zeros((CELL, CELL), dtype=np.float32)
    ox = (CELL - mw) // 2
    oy = PAD
    canvas[oy:oy + mh, ox:ox + mw] = m
    # metrics: pen origin at x=0 on baseline; left = offset to bitmap left,
    # top = offset from baseline to bitmap top (negative = above baseline).
    left_off = ox
    top_off = oy - FONT_PX
    try:
        adv = int(round(font.getlength(ch)))
    except Exception:
        adv = mw
    return canvas, left_off, top_off, adv


def make_sdf(mask: np.ndarray, radius: float = 8.0) -> np.ndarray:
    """True signed distance field (Euclidean) from a 0/1 mask via EDT."""
    inside = mask >= 0.5
    outside = ~inside
    d_inside = distance_transform_edt(outside)   # dist from inside -> nearest outside
    d_outside = distance_transform_edt(inside)   # dist from outside pixels -> nearest inside
    sdf = (d_inside - d_outside) / radius         # signed, ~[-1,1] at +/- radius
    sdf = np.clip(sdf, -1.0, 1.0)
    # Map to 0..255 (MapLibre expects full-range alpha; 0.5 == edge).
    return np.clip((sdf * 0.5 + 0.5) * 255.0, 0, 255).astype(np.uint8)


def build_glyph(font, ch, size, advance):
    res = rasterize_glyph(font, ch, size)
    if res is None:
        return None
    mask, left_off, top_off, adv = res
    sdf = make_sdf(mask)
    h, w = sdf.shape
    return Glyph(
        codepoint=ord(ch),
        bitmap=sdf.tobytes(),
        width=w,
        height=h,
        left=int(round(left_off)),
        top=int(round(top_off)),
        advance=int(round(adv)),
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ttf", required=True)
    ap.add_argument("--out", default="glyphs")
    ap.add_argument("--fontstack", default="Open Sans Regular")
    ap.add_argument("--size", type=int, default=24)
    ap.add_argument("--range-size", type=int, default=256)
    ap.add_argument("--first", type=int, default=32)
    ap.add_argument("--last", type=int, default=126)
    args = ap.parse_args()

    font = ImageFont.truetype(args.ttf, args.size)
    # Advance width per glyph via getlength (Pillow >= 9).
    def adv(ch: str) -> int:
        try:
            return int(round(font.getlength(ch)))
        except Exception:
            return args.size

    os.makedirs(args.out, exist_ok=True)
    stack_dir = os.path.join(args.out, args.fontstack)
    os.makedirs(stack_dir, exist_ok=True)

    # Group codepoints into range buckets.
    start = args.first
    while start <= args.last:
        end = min(start + args.range_size - 1, args.last)
        glyphs: list[Glyph] = []
        for cp in range(start, end + 1):
            ch = chr(cp)
            g = build_glyph(font, ch, args.size, adv(ch))
            if g is not None:
                glyphs.append(g)
        if glyphs:
            pbf = encode_stack(args.fontstack, glyphs)
            with open(os.path.join(stack_dir, f"{start}-{end}.pbf"), "wb") as fh:
                fh.write(pbf)
            print(f"wrote {stack_dir}/{start}-{end}.pbf ({len(glyphs)} glyphs)")
        start = end + 1
    print("done.")


if __name__ == "__main__":
    main()
