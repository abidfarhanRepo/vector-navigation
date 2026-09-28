#!/usr/bin/env python3
"""Debug-only telemetry sink for real-device Vector validation.

Listens on 0.0.0.0:8099, accepts CORS POST /telemetry (JSON) from the Vector
PWA running on a phone, appends each reading (timestamped, one JSON object per
line) to /tmp/vec-telemetry.log, and serves GET /telemetry (last 200 lines)
plus /healthz.

This is NOT part of the product. It exists only so the engineer can watch the
?debug=1 fluidity counters live during a drive without the user copying them
by hand. Kill it when the validation pass is over. It performs no auth and
holds no location data beyond what the PWA already computes for its own panel.
"""
import datetime
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = os.environ.get("VEC_TELEMETRY_LOG", "/tmp/vec-telemetry.log")
PORT = int(os.environ.get("VEC_TELEMETRY_PORT", "8099"))


def _ts():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        if self.path.startswith("/healthz"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"ok")
            return
        if self.path.startswith("/telemetry"):
            try:
                with open(LOG) as fh:
                    lines = fh.read().splitlines()[-200:]
            except FileNotFoundError:
                lines = []
            body = ("\n".join(lines)).encode("utf-8")
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if not self.path.startswith("/telemetry"):
            self.send_response(404)
            self.end_headers()
            return
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
            raw = self.rfile.read(n) if n else b"{}"
            obj = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            self.send_response(400)
            self._cors()
            self.end_headers()
            return
        line = _ts() + " " + json.dumps(obj, separators=(",", ":"), ensure_ascii=False)
        try:
            with open(LOG, "a") as fh:
                fh.write(line + "\n")
        except Exception:
            pass
        print(line, flush=True)
        self.send_response(204)
        self._cors()
        self.end_headers()

    def log_message(self, *args):
        pass


def main():
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"telemetry sink on :{PORT} -> {LOG}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
