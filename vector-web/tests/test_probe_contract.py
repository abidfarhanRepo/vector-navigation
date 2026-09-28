"""The `POST /traces` point schema, pinned as an executable contract.

The privacy gate reads SHORT keys — `lng`, `lat`, `t` (ms), `a` (accuracy m),
`s` (speed m/s), `h` (heading). A client that sends the long forms `ts` and
`accuracy` gets **HTTP 200 with a success body** and has every point discarded
as `no_timestamp`.

That is exactly what happened when the native Android collector was first wired
up. The upload "worked", the client incremented its sent counter, and nothing
was stored:

    {"status": "ok", "stored": 3,
     "dropped": {"no_timestamp": 3, ...}}

A silent-success failure is the worst kind for a data pipeline: the traffic
layer would simply never fill up and there would be no error anywhere to explain
why. These tests make the schema discoverable without reading gate.py.
"""

import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# `vendor/` carries vector_auth / vector_privacy, which the composition root
# imports at module load; without it this test cannot even import make_server.
for _p in (os.path.join(_ROOT, "src"), os.path.join(_ROOT, "vendor")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from vector_web import make_server  # noqa: E402


def _free_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class ProbeSchemaTest(unittest.TestCase):
    web = None
    port = None

    @classmethod
    def setUpClass(cls):
        cls.port = _free_port()
        os.environ.pop("VECTOR_WEB_TOKEN", None)   # unauthenticated dev mode
        cls.web = make_server(cls.port)
        threading.Thread(target=cls.web.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        if cls.web:
            cls.web.shutdown()

    def _post(self, payload):
        req = urllib.request.Request(
            "http://127.0.0.1:%d/traces" % self.port,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    @staticmethod
    def _pts(**overrides):
        """Three points a short distance apart, in the gate's schema."""
        base = [
            {"lng": 51.5310, "lat": 25.2854, "t": 1788870000000, "a": 5, "s": 14},
            {"lng": 51.5330, "lat": 25.2858, "t": 1788870010000, "a": 5, "s": 14},
            {"lng": 51.5350, "lat": 25.2862, "t": 1788870020000, "a": 5, "s": 14},
        ]
        if not overrides:
            return base
        out = []
        for p in base:
            q = dict(p)
            for old, new in overrides.items():
                if old in q:
                    q[new] = q.pop(old)
            out.append(q)
        return out

    def test_the_short_key_schema_is_accepted(self):
        status, body = self._post({
            "kind": "probe", "source": "native", "trip": "contract-short",
            "points": self._pts(),
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["dropped"]["no_timestamp"], 0, body)
        self.assertEqual(body["dropped"]["malformed"], 0, body)

    def test_sending_ts_instead_of_t_is_silently_discarded(self):
        """The exact defect. A 200 that stores nothing."""
        status, body = self._post({
            "kind": "probe", "source": "native", "trip": "contract-ts",
            "points": self._pts(t="ts"),
        })
        self.assertEqual(status, 200, "note: it does NOT fail loudly")
        self.assertEqual(
            body["dropped"]["no_timestamp"], 3,
            "if this is ever 0, the gate learned to read `ts` and this test "
            "should be updated rather than deleted",
        )

    def test_a_point_without_coordinates_is_malformed(self):
        status, body = self._post({
            "kind": "probe", "source": "native", "trip": "contract-nocoord",
            "points": [{"t": 1788870000000}],
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["dropped"]["malformed"], 1)

    def test_coordinates_outside_the_world_are_rejected(self):
        status, body = self._post({
            "kind": "probe", "source": "native", "trip": "contract-bounds",
            "points": [{"lng": 999.0, "lat": 25.2854, "t": 1788870000000}],
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["dropped"]["bounds"], 1)

    def test_an_inaccurate_point_is_rejected_by_the_gate(self):
        status, body = self._post({
            "kind": "probe", "source": "native", "trip": "contract-acc",
            "points": [{"lng": 51.5310, "lat": 25.2854, "t": 1788870000000, "a": 5000}],
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["dropped"]["accuracy"], 1)

    def test_source_is_recorded_as_declared_not_derived(self):
        # adr-0070 §5: the server cannot tell a native app from a browser on a
        # shared endpoint, so it records what the client claims AND labels the
        # claim. A client must never be able to launder provenance.
        _, body = self._post({
            "kind": "probe", "source": "native", "trip": "contract-src",
            "points": self._pts(),
        })
        self.assertEqual(body["source"], "native")
        self.assertEqual(body["source_provenance"], "declared")

    def test_an_unknown_kind_is_refused(self):
        status, _ = self._post({
            "kind": "telemetry", "trip": "x",
            "points": [{"lng": 51.5, "lat": 25.2, "t": 1}],
        })
        self.assertEqual(status, 400)

    def test_an_empty_point_list_is_refused(self):
        status, _ = self._post({"kind": "probe", "trip": "x", "points": []})
        self.assertEqual(status, 400)


if __name__ == "__main__":
    unittest.main()
