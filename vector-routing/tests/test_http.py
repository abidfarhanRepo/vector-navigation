"""HTTP-layer tests for Wave 30 traffic-aware routing.

Exercises the HTTP service (serve.py): POST /traffic populates the congestion
overlay and GET /route?traffic=1 returns a penalized, traffic-aware path.
"""

import json
import threading
import unittest

from vector_routing.algorithms import build_graph_from_features
from vector_routing.service import RoutingService
from vector_routing.serve import make_server


def _build_graph():
    features = [
        {"type": "Feature", "properties": {"highway": "primary", "maxspeed_kmh": 50},
         "geometry": {"type": "LineString", "coordinates": [[13.000, 52.000], [13.010, 52.000]]}},
        {"type": "Feature", "properties": {"highway": "primary", "maxspeed_kmh": 50},
         "geometry": {"type": "LineString", "coordinates": [[13.010, 52.000], [13.010, 52.010]]}},
        {"type": "Feature", "properties": {"highway": "primary", "maxspeed_kmh": 50},
         "geometry": {"type": "LineString", "coordinates": [[13.010, 52.010], [13.000, 52.010]]}},
        {"type": "Feature", "properties": {"highway": "secondary", "maxspeed_kmh": 30},
         "geometry": {"type": "LineString", "coordinates": [[13.000, 52.010], [13.000, 52.000]]}},
    ]
    return build_graph_from_features(features)


class HttpTrafficRouteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        svc = RoutingService(graph=_build_graph())
        cls.server = make_server(0, svc)
        cls.server_port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def _url(self, path):
        return f"http://127.0.0.1:{self.server_port}{path}"

    def test_route_traffic_param_returns_path(self):
        import urllib.request
        with urllib.request.urlopen(self._url("/route?from=52.000,13.000&to=52.000,13.010")) as r:
            self.assertEqual(r.status, 200)
            data = json.loads(r.read())
        self.assertIn("features", data)

    def test_post_traffic_then_route_traffic(self):
        import urllib.request
        seg = {"geometry": [[52.000, 13.000], [52.000, 13.010]],
               "free_flow_kmh": 50, "mean_speed_kmh": 3}
        req = urllib.request.Request(
            self._url("/traffic"),
            data=json.dumps({"segments": [seg]}).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req) as r:
            self.assertEqual(r.status, 200)
            body = json.loads(r.read())
        self.assertEqual(body["status"], "ok")
        self.assertGreaterEqual(body["ingested_edges"], 1)

        # The overlay is now populated; /route?traffic=1 must still succeed and
        # reflect the penalty (longer duration than the free-flow nominal).
        with urllib.request.urlopen(self._url("/route?from=52.000,13.000&to=52.000,13.010&traffic=1")) as r:
            self.assertEqual(r.status, 200)
            aware = json.loads(r.read())
        nominal_url = self._url("/route?from=52.000,13.000&to=52.000,13.010")
        with urllib.request.urlopen(nominal_url) as r:
            nominal = json.loads(r.read())
        self.assertGreaterEqual(
            aware["features"][0]["properties"]["duration_s"],
            nominal["features"][0]["properties"]["duration_s"])

    def test_post_traffic_bad_body_400(self):
        import urllib.request
        req = urllib.request.Request(
            self._url("/traffic"),
            data=json.dumps({"segments": "not-a-list"}).encode(),
            headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req)
        self.assertEqual(ctx.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
