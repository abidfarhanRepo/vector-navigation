#!/usr/bin/env python3
"""Tile-integrity verifier + repair for Vector MVT basemap tiles.

THE PROBLEM THIS SOLVES
-----------------------
"Streets vanish" on the map is almost never a live-code bug anymore — it is a
*poisoned-cache / mis-baked-tile* bug. Two concrete failure modes have shipped:

  1. A tile baked with the WRONG layer name (the style filters on
     ``source-layer: 'basemap'``, but the tile was written with layer ``"vector"``)
     is completely invisible — every feature is dropped by the filter.
  2. A stale 0-byte tile (e.g. an empty-marker left by a previous no-``--geojson``
     tile-server launch, or a half-written file) is served as a blank tile.

This tool makes that class of failure self-healing:
  - ``--tiles <dir>`` walks the tree, decodes every ``*.mvt``, and classifies each
    as VALID / EMPTY (legitimately empty ocean/desert — a zero-byte MVT, expected
    and renders correctly as "nothing here") / BAD (wrong layer name or corrupt).
    Exits non-zero if any BAD tile is found.
  - ``--repair <geojson>`` regenerates every BAD tile from the source GeoJSON
    (reusing vector-tile-server's ``TileSource``) and rewrites it in place.
    Correct tiles are never touched.

Usage:
  python validate_tiles.py --tiles C:/path/to/tiles
  python validate_tiles.py --tiles C:/path/to/tiles --repair C:/path/to/qatar.geojson
  python validate_tiles.py --tiles ... --repair ... --max-static-z 14 --dry-run
"""
import argparse
import os
import sys

# Make sibling repos importable (vector-ingestion for load_geojson,
# vector-tile-server for TileSource, vector-tile-gen for decode/encode).
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for _repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen", "vector-tile-server"):
    for _base in (os.path.join(os.path.dirname(_ROOT), _repo, "src"),
                  os.path.join(_ROOT, "..", _repo, "src")):
        _src = os.path.normpath(_base)
        if os.path.isdir(_src) and _src not in sys.path:
            sys.path.insert(0, _src)

from vector_tile_gen.encode import decode_tile  # noqa: E402
from vector_tile_gen.layers import (  # noqa: E402
    BASEMAP_LAYER, check_layer_names, raw_layer_names)

# The canonical layer name the live MapLibre style filters on. A tile without
# it is invisible to the viewer; the full rule (basemap, optionally + the V8
# `lanes` layer, nothing else) lives in vector_tile_gen.layers.
EXPECTED_LAYER = BASEMAP_LAYER


class TileReport:
    def __init__(self):
        self.valid = 0
        self.empty = 0
        self.bad = 0
        self.bad_paths = []  # (abs_path, reason)
        self.scanned = 0

    def record(self, path, status, reason=""):
        self.scanned += 1
        if status == "valid":
            self.valid += 1
        elif status == "empty":
            self.empty += 1
        else:
            self.bad += 1
            self.bad_paths.append((path, reason))

    @property
    def ok(self):
        return self.bad == 0


def _classify(path):
    """Return (status, reason). status in {valid, empty, bad}.

    A zero-byte tile is EMPTY (acceptable): the viewer renders "nothing here",
    which is correct for ocean/desert. The definitive "streets vanish" bug is a
    tile with the WRONG layer name (the style filters on ``basemap``, so a
    ``"vector"`` layer is silently dropped) — that is BAD. Corrupt/undecodable
    tiles are also BAD.
    """
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        return ("bad", "unreadable: %s" % e)
    if len(data) == 0:
        # Empty ocean/desert tile — valid, renders as nothing. The runtime cache
        # guard (vector-tile-server G3) handles the rare land-tile-that-should-
        # have-features case by regenerating; this verifier targets the
        # invisible-layer class of bug.
        return ("empty", "")
    try:
        dec = decode_tile(data)
    except Exception as e:
        return ("bad", "decode error: %s" % e)
    try:
        names = raw_layer_names(data)
    except ValueError as e:
        return ("bad", "decode error: %s" % e)
    ok, reason = check_layer_names(names)
    if not ok:
        return ("bad", reason)
    # Valid basemap tile (optionally with the allowlisted V8 `lanes` layer).
    return ("valid", "")


def _iter_tiles(tiles_dir):
    for z in sorted(os.listdir(tiles_dir)):
        zp = os.path.join(tiles_dir, z)
        if not os.path.isdir(zp) or not z.isdigit():
            continue
        for x in sorted(os.listdir(zp)):
            xp = os.path.join(zp, x)
            if not os.path.isdir(xp):
                continue
            for fn in sorted(os.listdir(xp)):
                if fn.endswith(".mvt"):
                    yield os.path.join(xp, fn)


def validate(tiles_dir):
    rep = TileReport()
    if not os.path.isdir(tiles_dir):
        print("[validate] tiles dir not found: %s" % tiles_dir, file=sys.stderr)
        sys.exit(2)
    for path in _iter_tiles(tiles_dir):
        status, reason = _classify(path)
        rep.record(path, status, reason)
        if status == "bad":
            print("  BAD   %s  (%s)" % (os.path.relpath(path, tiles_dir), reason))
    print("[validate] scanned=%d valid=%d empty=%d bad=%d"
          % (rep.scanned, rep.valid, rep.empty, rep.bad))
    return rep


def _repair_tile(path, source, max_static_z):
    import re
    # Derive z/x/y from the path components: <tiles>/<z>/<x>/<y>.mvt
    m = re.match(r".*/(\d+)/(\d+)/(\d+)\.mvt$", path.replace("\\", "/"))
    if not m:
        return ("bad", "cannot parse z/x/y from path")
    z, x, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
    try:
        data = source.cached_generate(z, x, y)
    except Exception as e:
        return ("bad", "regeneration failed: %s" % e)
    if data is None:
        # Tile genuinely has no features at this location (ocean/desert). Write a
        # correct empty tile (still 0 bytes is fine — the server treats None as 204).
        with open(path, "wb") as f:
            f.write(b"")
        return ("empty", "")
    with open(path, "wb") as f:
        f.write(data)
    # Re-classify the freshly written tile.
    return _classify(path)


def repair(tiles_dir, geojson, max_static_z, dry_run):
    rep = TileReport()
    if not geojson or not os.path.isfile(geojson):
        print("[repair] source geojson not found: %s" % geojson, file=sys.stderr)
        sys.exit(2)
    from vector_tile_server.serve import TileSource
    source = TileSource(geojson, cache_dir=None)
    if not os.path.isdir(tiles_dir):
        print("[repair] tiles dir not found: %s" % tiles_dir, file=sys.stderr)
        sys.exit(2)
    for path in _iter_tiles(tiles_dir):
        status, reason = _classify(path)
        if status != "bad":
            rep.record(path, status, reason)
            continue
        print("  REPAIR %s  (%s)" % (os.path.relpath(path, tiles_dir), reason))
        if dry_run:
            rep.record(path, "bad", reason)
            continue
        new_status, new_reason = _repair_tile(path, source, max_static_z)
        rep.record(path, new_status, new_reason)
        if new_status == "bad":
            print("         STILL BAD: %s" % new_reason)
    print("[repair] scanned=%d valid=%d empty=%d bad=%d"
          % (rep.scanned, rep.valid, rep.empty, rep.bad))
    return rep


def main(argv=None):
    ap = argparse.ArgumentParser(description="Validate/repair Vector MVT basemap tiles")
    ap.add_argument("--tiles", required=True,
                    help="Directory tree of baked tiles (z/x/y.mvt).")
    ap.add_argument("--repair", default=None,
                    help="Source GeoJSON to regenerate BAD tiles from (enables repair mode).")
    ap.add_argument("--max-static-z", type=int, default=14,
                    help="Passed to TileSource for regeneration (zooms <= this read disk first).")
    ap.add_argument("--dry-run", action="store_true",
                    help="Report what would be repaired without writing.")
    ap.add_argument("--quiet", action="store_true", help="Suppress per-tile BAD lines.")
    args = ap.parse_args(argv)

    if args.repair:
        rep = repair(args.tiles, args.repair, args.max_static_z, args.dry_run)
    else:
        rep = validate(args.tiles)
    sys.exit(0 if rep.ok else 1)


if __name__ == "__main__":
    raise SystemExit(main())
