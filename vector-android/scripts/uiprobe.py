#!/usr/bin/env python3
"""Read things off the handset's screen, for the V6 device suite.

Three questions that `uiautomator dump` alone cannot answer, each of which a
V6 section asks directly:

* ``ticked`` — which row of a radio list carries the checkmark. Vector marks the
  chosen option with a tick *as well as* a tint, deliberately, "so the selection
  is not colour-only" — and the accessibility tree does **not** set
  ``selected="true"`` on those rows, because they are clickable Surfaces rather
  than selectables. So the tick's position is the honest thing to read, and it
  is also exactly what a screen-reader user is given.

* ``mapcolor`` — the average colour of a band of the screen. §13.A wants "the
  persisted value and the actual rendered map must agree", and a string in a
  preferences file proves only half of that. Sampling the pixels is the other
  half: a dark theme that did not survive a restart is a light map, whatever the
  file says.

* ``nearest`` — the control on the same ROW as a given label. The settings
  sheet's data section has one "Clear" per row, so "the first Clear" is not the
  same thing as "Home's Clear", and tapping the wrong one is precisely the class
  of mistake this whole instrument exists to stop making.

No third-party imports: the PNG decoder below is about forty lines and adding
Pillow to a validation path would be a dependency the repo does not otherwise
have.
"""
from __future__ import annotations

import re
import struct
import sys
import zlib

TICKS = ("✓", "✓")

NODE = re.compile(r"<node[^>]*>")
BOUNDS = re.compile(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
TEXT = re.compile(r'text="([^"]*)"')
DESC = re.compile(r'content-desc="([^"]*)"')


def rows(path: str) -> list[tuple[str, int, int, int]]:
    """Every labelled, non-empty node as (label, cx, cy, area)."""
    xml = open(path, encoding="utf-8", errors="replace").read()
    out: list[tuple[str, int, int, int]] = []
    for m in NODE.finditer(xml):
        tag = m.group(0)
        b = BOUNDS.search(tag)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        # A zero-size node is in the tree but not on the screen. Tapping its
        # "centre" lands somewhere arbitrary.
        if x2 <= x1 or y2 <= y1:
            continue
        for pat in (TEXT, DESC):
            got = pat.search(tag)
            if got and got.group(1).strip():
                out.append(
                    (got.group(1), (x1 + x2) // 2, (y1 + y2) // 2, (x2 - x1) * (y2 - y1))
                )
    return out


def cmd_ticked(path: str) -> None:
    all_rows = rows(path)
    labels = [r for r in all_rows if r[0].strip() not in TICKS]
    for tick in (r for r in all_rows if r[0].strip() in TICKS):
        near = sorted(labels, key=lambda r: abs(r[2] - tick[2]))
        # Within one row height. Further than that and the tick belongs to
        # something else, and guessing would be worse than saying nothing.
        if near and abs(near[0][2] - tick[2]) < 60:
            print(near[0][0])


def cmd_nearest(path: str, label: str, want: str) -> None:
    """The `want` control on the same row as `label`, as "x y"."""
    all_rows = rows(path)
    anchors = [r for r in all_rows if label.lower() in r[0].lower()]
    if not anchors:
        return
    anchor = min(anchors, key=lambda r: r[3])
    cands = [r for r in all_rows if want.lower() in r[0].lower()]
    if not cands:
        return
    best = min(cands, key=lambda r: abs(r[2] - anchor[2]))
    if abs(best[2] - anchor[2]) < 120:
        print(best[1], best[2])


def cmd_center(path: str, label: str) -> None:
    """The smallest node carrying `label`, as "x y".

    Smallest, because a merged parent and its child can both report the same
    text and the parent's bounds can span the whole row — so tapping the largest
    match hits the row when you meant the button inside it.
    """
    cands = [r for r in rows(path) if label.lower() in r[0].lower()]
    if cands:
        best = min(cands, key=lambda r: r[3])
        print(best[1], best[2])


# ---- PNG ------------------------------------------------------------------


def read_png(path: str):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    i = 8
    w = h = ch = 0
    idat = bytearray()
    while i + 8 <= len(data):
        ln = struct.unpack(">I", data[i : i + 4])[0]
        typ = data[i + 4 : i + 8]
        body = data[i + 8 : i + 8 + ln]
        if typ == b"IHDR":
            w, h, bit, col = struct.unpack(">IIBB", body[:10])
            if bit != 8 or col not in (2, 6):
                raise ValueError(f"unsupported PNG: bit={bit} colour={col}")
            ch = 3 if col == 2 else 4
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        i += 12 + ln
    raw = zlib.decompress(bytes(idat))
    stride = w * ch
    out = []
    prev = bytearray(stride)
    p = 0
    for _ in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p : p + stride])
        p += stride
        if f == 1:
            for k in range(ch, stride):
                line[k] = (line[k] + line[k - ch]) & 255
        elif f == 2:
            for k in range(stride):
                line[k] = (line[k] + prev[k]) & 255
        elif f == 3:
            for k in range(stride):
                a = line[k - ch] if k >= ch else 0
                line[k] = (line[k] + ((a + prev[k]) >> 1)) & 255
        elif f == 4:
            for k in range(stride):
                a = line[k - ch] if k >= ch else 0
                c = prev[k - ch] if k >= ch else 0
                b = prev[k]
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[k] = (line[k] + pr) & 255
        out.append(bytes(line))
        prev = line
    return w, h, ch, out


def cmd_mapcolor(path: str) -> None:
    """Average r/g/b of a band at 70% height across the middle 60%.

    Low on the screen and away from the edges: the top of the app carries panels
    that are dark in BOTH themes on purpose (the maneuver band, the search bar's
    scrim), so sampling there would report the same colour whatever the theme
    is. At 70% height there is nothing but cartography in every phase.
    """
    w, h, ch, px = read_png(path)
    y0, y1 = int(h * 0.66), int(h * 0.74)
    x0, x1 = int(w * 0.2), int(w * 0.8)
    r = g = b = n = 0
    for y in range(y0, y1, 3):
        row = px[y]
        for x in range(x0, x1, 3):
            o = x * ch
            r += row[o]
            g += row[o + 1]
            b += row[o + 2]
            n += 1
    if n:
        print(r // n, g // n, b // n)


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    cmd, path = sys.argv[1], sys.argv[2]
    rest = sys.argv[3:]
    if cmd == "ticked":
        cmd_ticked(path)
    elif cmd == "center":
        cmd_center(path, rest[0])
    elif cmd == "nearest":
        cmd_nearest(path, rest[0], rest[1] if len(rest) > 1 else "Clear")
    elif cmd == "mapcolor":
        cmd_mapcolor(path)
    else:
        print(f"unknown command {cmd}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
