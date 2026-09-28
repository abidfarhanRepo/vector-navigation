"""Composite font stacks for the glyph endpoint.

Why this module exists
----------------------

MapLibre asks for a *stack* of fonts, comma-joined, and expects the server to
resolve each codepoint against the fonts in order — the standard behaviour of
the Mapbox fonts API, and the only mechanism a vector style has for script
fallback. A style says::

    "text-font": ["Open Sans Regular", "Noto Kufi Arabic"]

and the renderer requests::

    /glyphs/Open%20Sans%20Regular,Noto%20Kufi%20Arabic/1536-1791.pbf

The tile server treated the whole comma-joined string as a **directory name**::

    os.path.join(glyphs_dir, fontstack, f"{range}.pbf")

so a composite request resolved to a path that does not exist, and the handler
answered 200 with an empty two-byte protobuf. MapLibre's honest response to an
empty glyph range is to draw nothing at all.

What that actually cost
-----------------------

Everything in the glyph directory is single-script:

===========================  ========================================
``Open Sans Regular``        ranges ``0-255``, ``32-126`` — Latin only
``Noto Kufi Arabic``         ranges ``1536-1791``, ``1872-1919``,
                             ``2208-2303``, ``64336+`` — Arabic only
===========================  ========================================

And Vector's style pinned one font per layer: road labels to
``Noto Kufi Arabic``, place and POI labels to ``Open Sans Regular``. So in a
country where 99.5% of named roads carry both an Arabic ``name`` and an English
``name:en``:

* a **Latin road name could not be drawn at all** — the only font the road
  label layer could reach has no Latin glyphs;
* an **Arabic POI or place name could not be drawn at all**, for the mirror
  reason;
* and switching the road layer's ``text-field`` to ``name:en`` — which the
  tiles have carried all along — would have produced a map with *no road labels
  whatsoever*, which is a worse bug than the one being fixed and is exactly the
  trap this module removes.

Fixing it in the style was impossible. It had to be fixed here.

How the merge works
-------------------

The glyphs protobuf is small and regular::

    message glyphs   { repeated fontstack stacks = 1; }
    message fontstack { required string name  = 1;
                        required string range = 2;
                        repeated glyph  glyphs = 3; }
    message glyph     { required uint32 id = 1;  optional bytes bitmap = 2;
                        required uint32 width = 3;   required uint32 height = 4;
                        required sint32 left = 5;    required sint32 top = 6;
                        required uint32 advance = 7; }

Each glyph is a length-delimited submessage, so merging needs no bitmap
decoding: collect every field-3 submessage from each font in stack order, read
only its ``id`` (field 1, a varint), keep the **first** font that provides a
given id, and re-emit one fontstack. First-wins is what makes the order in the
style meaningful — ``["Open Sans Regular", "Noto Kufi Arabic"]`` means "Latin
from Open Sans, and anything it lacks from Noto Kufi".

Bytes are copied verbatim, so a merged range is bit-identical to the source
range for every glyph it took.
"""

import os
from typing import Dict, Iterator, List, Optional, Tuple

# A single font stack, already merged, keyed by (stack, range). Glyph ranges are
# immutable on disk and there are at most a few dozen of them, so this is a
# permanent cache rather than an eviction policy.
_CACHE: Dict[Tuple[str, str], Optional[bytes]] = {}


def _read_varint(buf: bytes, i: int) -> Tuple[int, int]:
    """Return (value, next index)."""
    result = 0
    shift = 0
    while True:
        if i >= len(buf):
            raise ValueError("truncated varint")
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _fields(buf: bytes) -> Iterator[Tuple[int, int, bytes]]:
    """Yield (field number, wire type, payload) for each field in `buf`.

    For wire type 2 the payload is the delimited bytes; for the others it is the
    raw encoding, which is all this module needs (it never re-encodes a scalar
    it did not read).
    """
    i = 0
    n = len(buf)
    while i < n:
        tag, i = _read_varint(buf, i)
        fnum, wtype = tag >> 3, tag & 7
        if wtype == 0:
            start = i
            _, i = _read_varint(buf, i)
            yield fnum, wtype, buf[start:i]
        elif wtype == 2:
            ln, i = _read_varint(buf, i)
            yield fnum, wtype, buf[i:i + ln]
            i += ln
        elif wtype == 5:
            yield fnum, wtype, buf[i:i + 4]
            i += 4
        elif wtype == 1:
            yield fnum, wtype, buf[i:i + 8]
            i += 8
        else:
            raise ValueError(f"unsupported wire type {wtype}")


def _delimited(fnum: int, payload: bytes) -> bytes:
    return _varint((fnum << 3) | 2) + _varint(len(payload)) + payload


def _glyph_id(glyph: bytes) -> Optional[int]:
    for fnum, wtype, payload in _fields(glyph):
        if fnum == 1 and wtype == 0:
            return _read_varint(payload, 0)[0]
    return None


def _glyphs_in(pbf: bytes) -> Iterator[bytes]:
    """Every glyph submessage in every fontstack of a glyphs protobuf."""
    for fnum, wtype, stack in _fields(pbf):
        if fnum != 1 or wtype != 2:
            continue
        for sfnum, swtype, payload in _fields(stack):
            if sfnum == 3 and swtype == 2:
                yield payload


def parse_stack(fontstack: str) -> List[str]:
    """Split a comma-joined stack into font names, dropping blanks.

    MapLibre joins with a bare comma; some styles add a space. Both are
    tolerated because a style author should not be able to break label
    rendering with whitespace.
    """
    return [name.strip() for name in fontstack.split(",") if name.strip()]


def build(glyphs_dir: str, fontstack: str, rng: str) -> Optional[bytes]:
    """Merged glyph range for `fontstack`, or None when no font supplies it.

    Returns None — not an empty protobuf — when not one font in the stack has
    the range on disk, so the caller can answer 404 and say so. The previous
    behaviour was a 200 carrying two bytes, which is indistinguishable to a
    renderer from "this range is genuinely empty" and is why the missing labels
    produced no error anywhere.
    """
    key = (fontstack, rng)
    if key in _CACHE:
        return _CACHE[key]

    names = parse_stack(fontstack)
    if not names:
        _CACHE[key] = None
        return None

    # Single font: serve it straight off disk. The overwhelmingly common case,
    # and it must stay byte-for-byte what it was.
    if len(names) == 1:
        path = os.path.join(glyphs_dir, names[0], f"{rng}.pbf")
        try:
            with open(path, "rb") as f:
                data = f.read()
        except (FileNotFoundError, OSError):
            data = None
        _CACHE[key] = data
        return data

    seen = set()
    merged: List[bytes] = []
    found_any = False
    for name in names:
        path = os.path.join(glyphs_dir, name, f"{rng}.pbf")
        try:
            with open(path, "rb") as f:
                pbf = f.read()
        except (FileNotFoundError, OSError):
            continue
        found_any = True
        try:
            for glyph in _glyphs_in(pbf):
                gid = _glyph_id(glyph)
                # A glyph with no id is malformed; skip it rather than emit a
                # range the renderer will reject wholesale.
                if gid is None or gid in seen:
                    continue
                seen.add(gid)
                merged.append(glyph)
        except ValueError:
            # A corrupt file in the stack must not take out the fonts after it.
            continue

    if not found_any:
        _CACHE[key] = None
        return None

    stack = (
        _delimited(1, fontstack.encode("utf-8"))
        + _delimited(2, rng.encode("utf-8"))
        + b"".join(_delimited(3, g) for g in merged)
    )
    out = _delimited(1, stack)
    _CACHE[key] = out
    return out
