import argparse
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from vector_tile_server import glyphs

TILE_PATH_RE = re.compile(r"^/tiles/(\d+)/(\d+)/(\d+)\.mvt$")
GLYPH_PATH_RE = re.compile(r"^/glyphs/(.+)/(\d+-\d+)\.pbf$")

MVT_CTYPE = "application/vnd.mapbox-vector-tile"
PBF_CTYPE = "application/x-protobuf"


def _tile_bbox(z, x, y):
    """Geographic (min_lon, min_lat, max_lon, max_lat) covered by a tile."""
    from vector_tile_gen.tiles import lonlat_to_tile  # noqa: F401 (keep import surface stable)
    n = 2 ** z
    lon_min = x / n * 360.0 - 180.0
    lon_max = (x + 1) / n * 360.0 - 180.0

    def merc_to_lat(y_merc):
        import math
        return math.degrees(math.atan(math.sinh((0.5 - y_merc) * 2.0 * math.pi)))

    lat_top = merc_to_lat(y / n)
    lat_bottom = merc_to_lat((y + 1) / n)
    return (lon_min, lat_bottom, lon_max, lat_top)


class TileSource:
    """Lazy in-memory source for on-demand tile generation.

    Static pre-baked tiles (z11-14) are served from disk by the handler. For
    zoom levels beyond the baked set (or any missing tile), we generate the
    MVT on the fly by selecting source features whose bbox intersects the tile
    and encoding them. A coarse lon/lat grid index makes feature lookup O(1)
    per tile instead of scanning 100k+ features on every request.

    The source GeoJSON is loaded once, lazily, on first dynamic request.
    """

    def __init__(self, geojson_path, grid_cell_deg=0.01, cache_dir=None):
        self.geojson_path = geojson_path
        self.grid_cell_deg = grid_cell_deg
        self.cache_dir = cache_dir  # generated tiles persist here (z/x/y.mvt)
        self._features = None
        self._grid = None
        self._loaded = False

    def _ensure_loaded(self):
        if self._loaded:
            return
        self._loaded = True
        if not self.geojson_path or not os.path.isfile(self.geojson_path):
            return
        from vector_ingestion.geojson import load_geojson
        feats = load_geojson(self.geojson_path)
        self._features = feats
        # Build a coarse grid: cell -> list of feature indices.
        cell = self.grid_cell_deg
        grid = {}
        for i, f in enumerate(feats):
            if not f.bbox:
                continue
            min_lon, min_lat, max_lon, max_lat = f.bbox
            ix0 = int(min_lon // cell)
            ix1 = int(max_lon // cell)
            iy0 = int(min_lat // cell)
            iy1 = int(max_lat // cell)
            for gx in range(ix0, ix1 + 1):
                for gy in range(iy0, iy1 + 1):
                    grid.setdefault((gx, gy), []).append(i)
        self._grid = grid

    def candidates(self, tbox):
        """Indices of features that may intersect tile bbox ``tbox``."""
        self._ensure_loaded()
        if not self._features:
            return []
        min_lon, min_lat, max_lon, max_lat = tbox
        cell = self.grid_cell_deg
        seen = set()
        out = []
        ix0 = int(min_lon // cell)
        ix1 = int(max_lon // cell)
        iy0 = int(min_lat // cell)
        iy1 = int(max_lat // cell)
        for gx in range(ix0, ix1 + 1):
            for gy in range(iy0, iy1 + 1):
                for idx in self._grid.get((gx, gy), ()):
                    if idx not in seen:
                        seen.add(idx)
                        out.append(idx)
        return out

    def generate(self, z, x, y):
        from vector_tile_gen import pipeline
        tbox = _tile_bbox(z, x, y)
        idxs = self.candidates(tbox)
        feats = [self._features[i] for i in idxs
                 if self._features[i].bbox and _intersects(self._features[i].bbox, tbox)]
        if not feats:
            return None
        # Encode to layer "basemap" to match the pre-baked static tiles and the
        # MapLibre style's source-layer filter (otherwise dynamic tiles are
        # invisible and streets vanish past the pre-baked zoom range).
        return pipeline.generate_tile(feats, z, x, y, layer_name="basemap")

    def cached_generate(self, z, x, y):
        """Generate a tile, persisting it to ``cache_dir`` for reuse.

        An empty tile (no features) is cached as a tiny 0-byte marker so we
        don't re-scan the source on every pan over empty ocean/desert. Returns
        the MVT bytes (possibly b'') or None if the source isn't loaded.

        A cached tile is validated before being served (``_cached_tile_ok``):
        a stale/empty/wrong-layer marker from a previous launch is treated as a
        miss and regenerated, so a poisoned cache can never silently blank the
        map.
        """
        if self.cache_dir:
            cpath = os.path.join(self.cache_dir, str(z), str(x), f"{y}.mvt")
            if os.path.isfile(cpath):
                with open(cpath, "rb") as f:
                    cached = f.read()
                if _cached_tile_ok(cached):
                    return cached
                # Stale/bad marker: fall through and regenerate below. The old
                # file is overwritten with the freshly-generated (or empty) tile.
        data = self.generate(z, x, y)
        if self.cache_dir:
            cpath = os.path.join(self.cache_dir, str(z), str(x), f"{y}.mvt")
            os.makedirs(os.path.dirname(cpath), exist_ok=True)
            with open(cpath, "wb") as f:
                f.write(data or b"")
        return data


def _intersects(a, b):
    """Do bboxes (min_lon, min_lat, max_lon, max_lat) intersect?"""
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


# The single canonical layer name the live MapLibre style filters on
# (``source-layer: 'basemap'``). A cached tile carrying any other layer name is
# invisible to the viewer and must be regenerated, never served.
_BAKED_LAYER = "basemap"


def _cached_tile_ok(data):
    """Return True only if ``data`` is a servable, visible basemap tile.

    A cached tile is rejected (and therefore regenerated) when it is empty OR
    its MVT layer name is not ``basemap``. This guards against the "streets
    vanish" failure mode where a stale cache (e.g. a 0-byte marker left by a
    previous no-``--geojson`` launch, or a mis-baked ``vector``-layer tile)
    silently blanks part of the map. Decoding is cheap relative to the ~2 s it
    can take to regenerate a tile, and a correct ``basemap`` tile is never
    touched, so this only adds cost on the error path.
    """
    if not data:
        return False
    try:
        from vector_tile_gen.encode import decode_tile
    except Exception:
        # Cannot decode (e.g. vector_tile_gen unavailable) — serve defensively.
        return True
    try:
        dec = decode_tile(data)
    except Exception:
        return False
    layers = dec.get("layers", [])
    return len(layers) == 1 and layers[0].get("name") == _BAKED_LAYER


class _Handler(BaseHTTPRequestHandler):
    tiles_dir: str = ""
    glyphs_dir: str = ""
    source: "TileSource" = None
    max_static_z: int = 14  # z <= this served from disk; deeper generated

    def log_message(self, *args):
        return

    def _send(self, code, ctype, body):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _serve_file(self, fpath, ctype):
        try:
            with open(fpath, "rb") as f:
                self._send(200, ctype, f.read())
        except (FileNotFoundError, OSError):
            self._send(404, "text/plain", b"Not found")

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, "text/plain", b"ok")
            return

        m = TILE_PATH_RE.match(self.path)
        if m:
            z, x, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
            # Fast path: pre-baked static tiles.
            if z <= self.max_static_z:
                tile_path = os.path.join(self.tiles_dir, str(z), str(x), f"{y}.mvt")
                if os.path.isfile(tile_path):
                    self._serve_file(tile_path, MVT_CTYPE)
                    return
            # Dynamic generation for any zoom (including missing static tiles).
            # Cached to disk so explored tiles persist across pans/sessions.
            if self.source is not None:
                try:
                    data = self.source.cached_generate(z, x, y)
                except Exception as e:  # generation must never 500 the map
                    print(f"[tile-server] generate {z}/{x}/{y} failed: {e}",
                          file=sys.stderr, flush=True)
                    data = None
                if data is not None:
                    self._send(200, MVT_CTYPE, data)
                    return
            # Fall back to a 204 (empty) so MapLibre doesn't retry forever.
            self._send(204, "text/plain", b"")
            return

        m = GLYPH_PATH_RE.match(self.path)
        if m:
            # MapLibre URL-encodes the fontstack (e.g. "Open%20Sans%20Regular")
            # but the on-disk directory has real spaces — decode before lookup.
            import urllib.parse as _up
            fontstack = _up.unquote(m.group(1))
            # Composite stacks ("A,B") are MERGED rather than looked up as a
            # directory name. Every font in the glyph directory is
            # single-script, so without this a style cannot mix Latin and
            # Arabic in one label layer — which in Qatar meant road labels
            # could only ever draw Arabic and place labels could only ever draw
            # Latin. See vector_tile_server.glyphs for the full account.
            data = glyphs.build(self.glyphs_dir, fontstack, m.group(2))
            if data is None:
                # 404, not an empty 200. An empty glyph range is
                # indistinguishable to a renderer from a range that genuinely
                # has no glyphs, which is precisely why the missing labels
                # produced no error anywhere for months.
                self._send(404, "text/plain", b"Not found")
                return
            self._send(200, PBF_CTYPE, data)
            return

        self._send(404, "text/plain", b"Not found")

    do_HEAD = do_GET


def make_server(port, host, tiles_dir, glyphs_dir, source=None, max_static_z=14):
    _Handler.tiles_dir = tiles_dir
    _Handler.glyphs_dir = glyphs_dir
    _Handler.source = source
    _Handler.max_static_z = max_static_z
    return ThreadingHTTPServer((host, port), _Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description="vector-tile-server (Python dev)")
    parser.add_argument("--port", type=int, default=3000)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--tiles-dir", default=None)
    parser.add_argument("--glyphs-dir", default=None)
    parser.add_argument("--geojson", default=None,
                        help="Source GeoJSON for on-demand tile generation (any zoom).")
    parser.add_argument("--cache-dir", default=None,
                        help="Directory to persist generated tiles (z/x/y.mvt).")
    parser.add_argument("--max-static-z", type=int, default=14,
                        help="Zoom levels <= this served from --tiles-dir; deeper generated.")
    args = parser.parse_args(argv)

    # Make sibling repos' src importable (vector-ingestion for load_geojson,
    # vector-tile-gen for pipeline/encode/tiles).
    here = Path(__file__).resolve().parent
    for repo in ("vector-ingestion", "vector-map-store", "vector-tile-gen"):
        src = str(here.parent.parent.parent / repo / "src")
        if os.path.isdir(src) and src not in sys.path:
            sys.path.insert(0, src)

    module_dir = Path(__file__).resolve().parent
    tiles_dir = args.tiles_dir or os.environ.get("TILES_DIR") or str(module_dir / ".." / ".." / "tiles")
    glyphs_dir = args.glyphs_dir or os.environ.get("GLYPHS_DIR") or str(module_dir / ".." / ".." / "glyphs")

    if not os.path.isdir(tiles_dir):
        print(f"[tile-server] tiles_dir not found: {tiles_dir}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isdir(glyphs_dir):
        print(f"[tile-server] glyphs_dir not found: {glyphs_dir}", file=sys.stderr)
        sys.exit(1)

    source = None
    if args.geojson and os.path.isfile(args.geojson):
        source = TileSource(args.geojson, cache_dir=args.cache_dir)
        print(f"[tile-server] dynamic tile source: {args.geojson}", file=sys.stderr)

    print(f"[tile-server] tiles: {tiles_dir}  glyphs: {glyphs_dir}", file=sys.stderr)
    print(f"[tile-server] max-static-z: {args.max_static_z}  dynamic: {'on' if source else 'off'}",
          file=sys.stderr)
    server = make_server(args.port, args.host, tiles_dir, glyphs_dir, source, args.max_static_z)
    print(f"[tile-server] listening on http://{args.host}:{args.port}", file=sys.stderr)
    sys.stderr.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
