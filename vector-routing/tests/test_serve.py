import json
import os
import socket
import threading
import time
import unittest
import urllib.error
import urllib.request

from http.server import ThreadingHTTPServer

from vector_routing.serve import (
    RouteRequestHandler,
    parse_endpoint,
    route_to_geojson,
    navigate_to_geojson,
    Auth,
)
from vector_routing.graph import RoutingGraph
from vector_routing.service import RoutingService


def _connected_grid() -> RoutingGraph:
    """Build a small 3x3 connected grid graph around Berlin."""
    g = RoutingGraph()
    lats = [52.51, 52.52, 52.53]
    lons = [13.39, 13.40, 13.41]
    for lat in lats:
        g.add_way([(lon, lat) for lon in lons], {"highway": "primary", "maxspeed_kmh": 50})
    for lon in lons:
        g.add_way([(lon, lat) for lat in lats], {"highway": "primary", "maxspeed_kmh": 50})
    return g


def _disconnected_graph() -> RoutingGraph:
    """Build a graph with two components that cannot reach each other."""
    g = RoutingGraph()
    g.add_way([(0.0, 0.0), (0.001, 0.0)], {"maxspeed_kmh": 50})
    g.add_way([(9.0, 9.0), (9.001, 9.0)], {"maxspeed_kmh": 50})
    return g


class ParseEndpointTest(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(parse_endpoint("52.5,13.4"), (52.5, 13.4))

    def test_empty_raises(self):
        with self.assertRaises(ValueError):
            parse_endpoint("")

    def test_one_part_raises(self):
        with self.assertRaises(ValueError):
            parse_endpoint("13.4")

    def test_non_numeric_raises(self):
        with self.assertRaises(ValueError):
            parse_endpoint("abc,13.4")

    def test_too_many_parts_raises(self):
        with self.assertRaises(ValueError):
            parse_endpoint("1,2,3")


class RouteToGeoJSONTest(unittest.TestCase):
    def test_route_to_geojson_shape(self):
        g = RoutingGraph()
        g.add_way([(13.40, 52.50), (13.41, 52.50)], {"maxspeed_kmh": 50})
        g.add_way([(13.41, 52.50), (13.41, 52.51)], {"maxspeed_kmh": 50})
        svc = RoutingService(g)
        route = svc.route((13.40, 52.50), (13.41, 52.50))
        payload = route_to_geojson(route, (52.50, 13.40), (52.50, 13.41), "shortest")

        self.assertEqual(payload["type"], "FeatureCollection")
        self.assertEqual(len(payload["features"]), 1)
        feature = payload["features"][0]
        self.assertEqual(feature["geometry"]["type"], "LineString")
        coords = feature["geometry"]["coordinates"]
        for c in coords:
            self.assertIsInstance(c, list)
            self.assertEqual(len(c), 2)
            self.assertIsInstance(c[0], float)
            self.assertIsInstance(c[1], float)
        # lon first
        self.assertEqual(coords[0], [13.40, 52.50])

    def test_route_to_geojson_properties(self):
        g = RoutingGraph()
        g.add_way([(13.40, 52.50), (13.41, 52.50)], {"maxspeed_kmh": 50})
        g.add_way([(13.41, 52.50), (13.41, 52.51)], {"maxspeed_kmh": 50})
        svc = RoutingService(g)
        route = svc.route((13.40, 52.50), (13.41, 52.50))
        payload = route_to_geojson(route, (52.50, 13.40), (52.50, 13.41), "shortest")
        props = payload["features"][0]["properties"]

        self.assertEqual(props["profile"], "shortest")
        self.assertIsInstance(props["distance_km"], float)
        self.assertGreater(props["distance_km"], 0.0)
        self.assertIsInstance(props["nodes"], int)
        self.assertEqual(props["from"], [52.50, 13.40])
        self.assertEqual(props["to"], [52.50, 13.41])
        self.assertIn("duration_s", props)


class _LiveServerTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), RouteRequestHandler)
        RouteRequestHandler.routing_service = RoutingService(self.graph())
        RouteRequestHandler.auth = Auth()
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self._wait_ready()

    def graph(self):
        return _connected_grid()

    def _wait_ready(self, timeout: float = 5.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.02)
        raise RuntimeError("route server did not start in time")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def _get(self, path):
        url = f"http://127.0.0.1:{self.port}{path}"
        try:
            with urllib.request.urlopen(url, timeout=2) as resp:
                return resp.status, dict(resp.getheaders()), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()


class LiveNavigateTest(_LiveServerTest):
    def test_navigate_two_point(self):
        status, _headers, body = self._get("/navigate?from=52.51,13.39&to=52.53,13.41")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["type"], "FeatureCollection")
        feature = data["features"][0]
        self.assertEqual(feature["geometry"]["type"], "LineString")
        self.assertTrue(len(feature["geometry"]["coordinates"]) >= 2)
        props = feature["properties"]
        self.assertEqual(props["type"], "route")
        # The service relaxes on travel time, so the default label is "fastest".
        # It read "shortest" while doing a fastest-path search.
        self.assertEqual(props["profile"], "fastest")
        self.assertIsInstance(props["distance_km"], float)
        self.assertIsInstance(props["duration_s"], float)
        self.assertEqual(props["from"], "52.51,13.39")
        self.assertEqual(props["to"], "52.53,13.41")
        steps = props["steps"]
        self.assertGreaterEqual(len(steps), 2)
        self.assertEqual(steps[0]["type"], "depart")
        self.assertEqual(steps[-1]["type"], "arrive")
        # every step has the full schema
        for s in steps:
            self.assertIn("index", s)
            self.assertIn("instruction", s)
            self.assertIn("location", s)
            self.assertIn("distance_m", s)
            self.assertIn("duration_s", s)
            self.assertIn("cumulative_distance_m", s)
            self.assertIn("cumulative_duration_s", s)
            self.assertIn("bearing", s)

    def test_navigate_cumulative_monotonic(self):
        status, _headers, body = self._get("/navigate?from=52.51,13.39&to=52.53,13.41")
        self.assertEqual(status, 200)
        data = json.loads(body)
        cum = [s["cumulative_distance_m"] for s in data["features"][0]["properties"]["steps"]]
        for prev, cur in zip(cum, cum[1:]):
            self.assertGreaterEqual(cur, prev)

    def test_navigate_via_multi_leg(self):
        # waypoint is exactly a grid node, so its snapped coordinate is a vertex.
        via = "52.52,13.40"
        status, _headers, body = self._get(
            f"/navigate?from=52.51,13.39&to=52.53,13.41&via={via}"
        )
        self.assertEqual(status, 200)
        data = json.loads(body)
        steps = data["features"][0]["properties"]["steps"]
        locs = [s["location"] for s in steps]
        self.assertIn([13.40, 52.52], locs)
        # depart + arrive present and at least one intermediate maneuver
        self.assertEqual(steps[0]["type"], "depart")
        self.assertEqual(steps[-1]["type"], "arrive")
        self.assertTrue(any(s["type"] not in ("depart", "arrive") for s in steps))

    def test_navigate_400_missing_to(self):
        status, _headers, body = self._get("/navigate?from=52.5,13.4")
        self.assertEqual(status, 400)
        self.assertIn("error", json.loads(body))

    def test_navigate_400_bad_via(self):
        status, _headers, body = self._get("/navigate?from=52.51,13.39&to=52.53,13.41&via=abc")
        self.assertEqual(status, 400)
        self.assertIn("error", json.loads(body))


class LiveRouteTest(_LiveServerTest):
    def test_route_success(self):
        status, headers, body = self._get("/route?from=52.51,13.39&to=52.53,13.41")
        self.assertEqual(status, 200)
        data = json.loads(body)
        self.assertEqual(data["type"], "FeatureCollection")
        coords = data["features"][0]["geometry"]["coordinates"]
        self.assertTrue(len(coords) >= 2)
        self.assertEqual(headers.get("Access-Control-Allow-Origin"), "*")

    def test_404_no_route(self):
        RouteRequestHandler.routing_service = RoutingService(_disconnected_graph())
        status, _headers, body = self._get("/route?from=0,0&to=9,9")
        self.assertEqual(status, 404)
        data = json.loads(body)
        self.assertIn("error", data)

    def test_400_missing_from(self):
        status, _headers, body = self._get("/route?to=52.5,13.4")
        self.assertEqual(status, 400)
        data = json.loads(body)
        self.assertIn("error", data)

    def test_400_missing_to(self):
        status, _headers, body = self._get("/route?from=52.5,13.4")
        self.assertEqual(status, 400)
        data = json.loads(body)
        self.assertIn("error", data)

    def test_400_malformed_number(self):
        status, _headers, body = self._get("/route?from=abc,13.4&to=52.5,13.4")
        self.assertEqual(status, 400)
        data = json.loads(body)
        self.assertIn("error", data)

    def test_cors_header(self):
        status, headers, _body = self._get("/route?from=52.51,13.39&to=52.53,13.41")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("Access-Control-Allow-Origin"), "*")

    def test_healthz(self):
        status, _headers, body = self._get("/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"ok")

    def test_unknown_path_404(self):
        status, _headers, body = self._get("/does-not-exist")
        self.assertEqual(status, 404)
        self.assertEqual(body, b"not found")


class UnavailableServiceTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), RouteRequestHandler)
        RouteRequestHandler.routing_service = None
        RouteRequestHandler.auth = Auth()
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self._wait_ready()

    def _wait_ready(self, timeout: float = 5.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.2):
                    return
            except OSError:
                time.sleep(0.02)
        raise RuntimeError("route server did not start in time")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_unavailable_503(self):
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{self.port}/route?from=52.51,13.39&to=52.53,13.41", timeout=2
            ) as resp:
                self.fail("expected HTTPError 503")
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 503)
            data = json.loads(e.read())
            self.assertIn("error", data)


if __name__ == "__main__":
    unittest.main()


class SecuredRouteServeTest(_LiveServerTest):
    """End-to-end bearer-token enforcement through the live handler."""

    TOKEN = "test-vector-service-token"
    ENDPOINT = "/route"

    def setUp(self):
        import os
        os.environ["VECTOR_SERVICE_TOKEN"] = self.TOKEN
        super().setUp()
        self.addCleanup(lambda: os.environ.pop("VECTOR_SERVICE_TOKEN", None))

    def _get_auth(self, path, token=None):
        import http.client
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            conn.request("GET", path, headers=headers)
            resp = conn.getresponse()
            return resp.status, resp.read()
        finally:
            conn.close()

    def test_no_token_401(self):
        status, _ = self._get_auth(self.ENDPOINT)
        self.assertEqual(status, 401)

    def test_wrong_token_401(self):
        status, _ = self._get_auth(self.ENDPOINT, token="wrong")
        self.assertEqual(status, 401)

    def test_valid_token_not_401(self):
        status, _ = self._get_auth(self.ENDPOINT, token=self.TOKEN)
        self.assertNotEqual(status, 401)

    def test_healthz_open(self):
        status, _ = self._get_auth("/healthz")
        self.assertEqual(status, 200)


class ImpossibleCoordinateTest(unittest.TestCase):
    """Coordinates that cannot name a place are rejected at the boundary.

    Found by pointing an adversarial probe at the live service. Two failures,
    one root cause — ``float()`` accepts more than a coordinate can be:

      * ``inf`` reached the spatial index, where ``int(lon / cell)`` raises
        ``OverflowError``. The handler catches ``ValueError`` and ``KeyError``,
        so the server **closed the connection with no response at all** and the
        client saw a network failure rather than a bad request.
      * latitude 95 is not a place, but the router treated it as one and spent
        ~800 ms snapping across the whole graph before conceding 422.
    """

    def test_infinity_is_refused(self):
        for bad in ("inf,51.5", "51.5,inf", "-inf,51.5", "Infinity,51.5"):
            with self.assertRaises(ValueError, msg=bad):
                parse_endpoint(bad)

    def test_nan_is_refused(self):
        for bad in ("nan,51.5", "51.5,NaN"):
            with self.assertRaises(ValueError, msg=bad):
                parse_endpoint(bad)

    def test_latitude_beyond_the_poles_is_refused(self):
        for bad in ("95,51.5", "-90.5,51.5"):
            with self.assertRaises(ValueError, msg=bad):
                parse_endpoint(bad)

    def test_longitude_beyond_the_meridian_is_refused(self):
        for bad in ("25.2,200", "25.2,-180.1"):
            with self.assertRaises(ValueError, msg=bad):
                parse_endpoint(bad)

    def test_the_edges_of_the_range_are_still_valid(self):
        """A pole and the date line are real places; do not over-reject."""
        for good in ("90,180", "-90,-180", "0,0"):
            parse_endpoint(good)

    def test_an_ordinary_doha_coordinate_still_parses(self):
        self.assertEqual(parse_endpoint("25.2867,51.5333"), (25.2867, 51.5333))
