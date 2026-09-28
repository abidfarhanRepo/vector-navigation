"""Generate Vector PWA icons — pure stdlib, no third-party dependencies.

Rasterizes a simple Vector "V" mark in the app's dark-navy / cyan palette and
encodes PNGs with a from-scratch zlib writer (no Pillow, no freetype). Also
emits an SVG master. This keeps the icon pipeline dependency-free and
CI-friendly (consistency with the project's stdlib-only utility convention).

Outputs into ../static/icons:
  icon-192.png           192x192  any-purpose
  icon-512.png           512x512  any-purpose
  icon-maskable-512.png  512x512  maskable (full-bleed bg, central safe-zone V)
  icon.svg               scalable master
"""

from __future__ import annotations

import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "static", "icons")
os.makedirs(OUT_DIR, exist_ok=True)

# Palette (matches vector-web dark theme).
NAVY_TOP = (24, 30, 42)    # #181e2a
NAVY_BOT = (18, 22, 30)    # #12161e
CYAN = (90, 200, 250)      # #5ac8fa


def _lerp(a, b, t):
    return int(a + (b - a) * t)


def _bg_color(y, size):
    t = y / max(1, size - 1)
    return (
        _lerp(NAVY_TOP[0], NAVY_BOT[0], t),
        _lerp(NAVY_TOP[1], NAVY_BOT[1], t),
        _lerp(NAVY_TOP[2], NAVY_BOT[2], t),
    )


def _point_in_quad(px, py, quad):
    """Ray-cast point-in-polygon for a convex quad (list of (x,y))."""
    inside = False
    n = len(quad)
    for i in range(n):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % n]
        if ((y1 > py) != (y2 > py)) and (
            px < (x2 - x1) * (py - y1) / (y2 - y1) + x1
        ):
            inside = not inside
    return inside


def _v_quads(size, safe):
    """Two leg quads for the 'V' centered in a safe box."""
    s = size * safe
    x0 = (size - s) / 2.0
    x1 = x0 + s
    y0 = (size - s) / 2.0
    y1 = y0 + s
    cx = size / 2.0
    thk = s * 0.16
    left = [(x0, y0), (x0 + thk, y0), (cx, y1 - thk), (cx - thk, y1)]
    right = [(x1, y0), (x1 - thk, y0), (cx, y1 - thk), (cx + thk, y1)]
    return left, right


def render(size, safe):
    left, right = _v_quads(size, safe)
    # RGBA buffer
    buf = bytearray()
    for y in range(size):
        buf.append(0)  # PNG filter type 0 (none) for this scanline
        bg = _bg_color(y, size)
        for x in range(size):
            if _point_in_quad(x + 0.5, y + 0.5, left) or _point_in_quad(x + 0.5, y + 0.5, right):
                buf += bytes(CYAN) + b"\xff"
            else:
                buf += bytes(bg) + b"\xff"
    return buf


def _png_chunk(tag, data):
    out = struct.pack(">I", len(data)) + tag + data
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    out += struct.pack(">I", crc)
    return out


def write_png(path, size, rgba_buf):
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    idat = zlib.compress(bytes(rgba_buf), 9)
    with open(path, "wb") as fh:
        fh.write(sig)
        fh.write(_png_chunk(b"IHDR", ihdr))
        fh.write(_png_chunk(b"IDAT", idat))
        fh.write(_png_chunk(b"IEND", b""))


def make_icon(size, safe, filename):
    buf = render(size, safe)
    path = os.path.join(OUT_DIR, filename)
    write_png(path, size, buf)
    return path


def make_svg():
    safe = 0.78
    path = os.path.join(OUT_DIR, "icon.svg")
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" '
        'viewBox="0 0 512 512">\n'
        '  <defs>\n'
        '    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">\n'
        '      <stop offset="0%" stop-color="#181e2a"/>\n'
        '      <stop offset="100%" stop-color="#12161e"/>\n'
        "    </linearGradient>\n"
        "  </defs>\n"
        '  <rect width="512" height="512" fill="url(#bg)"/>\n'
        '  <g fill="#5ac8fa">\n'
        '    <polygon points="120,120 188,120 256,372 324,120 392,120 256,420"/>\n'
        "  </g>\n"
        "</svg>\n"
    )
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(svg)
    return path


def main():
    created = []
    created.append(make_icon(192, 0.78, "icon-192.png"))
    created.append(make_icon(512, 0.78, "icon-512.png"))
    created.append(make_icon(512, 0.58, "icon-maskable-512.png"))
    created.append(make_svg())
    for c in created:
        print("wrote", os.path.relpath(c, os.path.join(HERE, "..")))


if __name__ == "__main__":
    main()
