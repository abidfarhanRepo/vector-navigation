#!/usr/bin/env python3
"""Dev tile server (test stand-in for the Rust vector-tile-server).

Serves MVT tiles from VECTOR_TILE_DIR (default ./tiles) at
GET /tiles/{z}/{x}/{y}.mvt, self-hosted glyph PBFs from VECTOR_GLYPH_DIR,
the MapLibre viewer at /, and /healthz. Mirrors the Rust server's contract
so the web viewer + proxies behave identically during local browser testing
when the Rust toolchain or Docker is unavailable.

This is NOT a production replacement for the Rust server — it exists so the
browser verification path (the user's "test it with the local browser"
directive) can run on a host where cargo/Docker cannot build the binary.
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TILE_DIR = os.environ.get("VECTOR_TILE_DIR", os.path.join(ROOT, "tiles"))
GLYPH_DIR = os.environ.get("VECTOR_GLYPH_DIR", os.path.join(ROOT, "glyphs"))
VIEWER_HTML = os.path.join(ROOT, "static", "index.html")

# Tile epoch (issue 08). Kept as a literal filename rather than importing
# vector-tile-gen: this server must stay a self-contained stdlib script.
VERSION_FILE = "VERSION.json"


def read_tile_epoch():
    """Current tile epoch, or 0 when the set has never been versioned."""
    path = os.path.join(TILE_DIR, VERSION_FILE)
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        return int(doc.get("epoch", 0)), doc
    except (OSError, ValueError, TypeError, AttributeError):
        return 0, {"epoch": 0, "reason": "never versioned"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        return

    def _send(self, code, ctype, body, cache=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Access-Control-Allow-Origin", "*")
        if cache:
            self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/healthz":
            self._send(200, "text/plain", b"ok")
            return
        if path == "/tiles/version":
            # The ONE response that must never be cached: it is what tells a
            # client its cached tiles are stale. Cache this and the whole
            # invalidation scheme silently stops working.
            epoch, doc = read_tile_epoch()
            self._send(200, "application/json",
                       json.dumps({"epoch": epoch,
                                   "reason": doc.get("reason", ""),
                                   "tiles_changed": doc.get("tiles_changed", 0)}),
                       cache="no-store")
            return
        if path == "/" or path == "/index.html":
            try:
                with open(VIEWER_HTML, "rb") as fh:
                    self._send(200, "text/html", fh.read())
            except FileNotFoundError:
                self._send(404, "text/plain", b"index.html not found")
            return
        if path.startswith("/tiles/") and path.endswith(".mvt"):
            parts = path[len("/tiles/"):].split("/")
            if len(parts) == 3 and parts[2].endswith(".mvt"):
                z, x, y = parts[0], parts[1], parts[2][:-4]
                tpath = os.path.join(TILE_DIR, z, x, f"{y}.mvt")
                if os.path.isfile(tpath):
                    with open(tpath, "rb") as fh:
                        # Long cache is correct: a tile is immutable for its
                        # epoch, and a re-bake changes the URL (?v=) rather than
                        # the bytes behind an unchanged one.
                        self._send(200, "application/x-protobuf", fh.read(),
                                   cache="public, max-age=86400")
                    return
            self._send(404, "text/plain", b"tile not found")
            return
        if path.startswith("/glyphs/") and path.endswith(".pbf"):
            gpath = os.path.join(GLYPH_DIR, path[len("/glyphs/"):])
            if os.path.isfile(gpath):
                with open(gpath, "rb") as fh:
                    self._send(200, "application/x-protobuf", fh.read())
                return
            # Missing glyph range: return empty but valid stack (mirrors Rust).
            self._send(200, "application/x-protobuf", b"\x1a\x00")
            return
        self._send(404, "text/plain", b"not found")


def main():
    port = int(os.environ.get("VECTOR_TILE_PORT", "3000"))
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    epoch, _ = read_tile_epoch()
    print(f"dev-tile-server on http://127.0.0.1:{port} "
          f"(tiles={TILE_DIR}, epoch={epoch})")
    srv.serve_forever()


if __name__ == "__main__":
    main()
