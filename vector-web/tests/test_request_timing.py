"""The `-b <n>ms` field must measure the request, not the idle socket.

`protocol_version = "HTTP/1.1"` makes every connection persistent, and
`handle_one_request` -- where the clock used to start -- begins by BLOCKING on
`readline()` for the client's next request. On a reused connection the gap
between two polls was therefore billed to the second one.

That misreading has already cost real investigation time. A phone polling
`/speed` every 6 s logged a flat `6000ms` for a call the geocoder answers in
9-11 ms; a cancelled tile logged 81,719 ms of "work" that was a held-open
socket. Both were read as latency in the 2026-09-14 drive analysis and sent
the investigation after an edge saturation that never happened.

These tests pin the clock to the request by driving a real server over one
kept-alive connection with a deliberate idle gap in the middle.
"""
import http.client
import os
import re
import sys
import threading
import time
import unittest

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "src"))
sys.path.insert(0, os.path.join(HERE, "..", "vendor"))

import vector_web  # noqa: E402


_LINE = re.compile(r"\[req\] (\w+) (\S+) (\d+) \S+ (\d+)ms")


class _Capture:
    """Collects the `[req]` lines the handler prints to stdout."""

    def __init__(self):
        self.lines = []

    def write(self, s):
        if "[req]" in s:
            self.lines.append(s)

    def flush(self):
        pass

    def parsed(self):
        out = []
        for ln in self.lines:
            m = _LINE.search(ln)
            if m:
                out.append((m.group(1), m.group(2), int(m.group(3)), int(m.group(4))))
        return out


class RequestTimingTest(unittest.TestCase):
    def setUp(self):
        self.server = vector_web.make_server(0)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.cap = _Capture()
        self._stdout = sys.stdout
        sys.stdout = self.cap

    def tearDown(self):
        sys.stdout = self._stdout
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def test_an_idle_keepalive_gap_is_not_billed_to_the_next_request(self):
        """The regression itself: two polls, one connection, a gap between."""
        IDLE_S = 1.5
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        for i in range(2):
            if i:
                time.sleep(IDLE_S)          # the client sitting idle, as a poller does
            conn.request("GET", "/healthz")
            conn.getresponse().read()       # must drain, or the next write blocks
        conn.close()

        reqs = [r for r in self.cap.parsed() if r[1] == "/healthz"]
        self.assertEqual(2, len(reqs), f"expected two logged requests, got {reqs}")
        second_ms = reqs[1][3]
        self.assertLess(
            second_ms, IDLE_S * 1000 * 0.5,
            "the second request on a kept-alive connection was billed the idle "
            f"gap before it ({second_ms} ms against a {IDLE_S}s wait) -- the "
            "clock is starting when the socket is ready, not when the request "
            "arrives")

    def test_healthz_is_reported_as_the_fast_call_it_is(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/healthz")
        conn.getresponse().read()
        conn.close()
        reqs = [r for r in self.cap.parsed() if r[1] == "/healthz"]
        self.assertEqual(1, len(reqs))
        self.assertLess(reqs[0][3], 250,
                        "a static health check must not log as hundreds of ms")

    def test_the_connection_really_was_reused(self):
        """Guards the test above: prove HTTP/1.1 keep-alive is in play.

        If the server closed the connection between requests the regression
        could not reproduce and the first test would pass vacuously.
        """
        self.assertEqual("HTTP/1.1", vector_web.WebRequestHandler.protocol_version)
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/healthz")
        r1 = conn.getresponse()
        r1.read()
        self.assertNotEqual(
            "close", (r1.getheader("Connection") or "").lower(),
            "server asked to close; the keep-alive path is not being exercised")
        sock_before = conn.sock
        conn.request("GET", "/healthz")
        conn.getresponse().read()
        self.assertIs(sock_before, conn.sock, "a new socket means no reuse")
        conn.close()


if __name__ == "__main__":
    unittest.main()
