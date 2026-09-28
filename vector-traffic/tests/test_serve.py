"""Tests for the vector-traffic HTTP serve layer.

Mirrors the vector-reconstruction serve tests but exercises the traffic
endpoint (``/traffic``) and the ``parse_probes`` / ``traffic_to_geojson``
helpers. The live server tests hit the real handler over a loopback socket
using the bundled sample data in ``traffic-data``.
"""

import http.client
import os
import json
import socket
import threading
import unittest

from vector_traffic.segments import RoadSegment
from vector_traffic.probe import Probe
from vector_traffic.store import TrafficState
from vector_traffic.serve import (
    TrafficRequestHandler,
    TrafficService,
    parse_bbox,
    parse_probes,
    traffic_to_geojson,
)


def traffic_to_geojson_inline(segments, probes) -> dict:
    return traffic_to_geojson(segments, probes)


class ParseProbesTest(unittest.TestCase):
    def test_valid_three_part(self):
        out = parse_probes("13.40,52.52,45.0;13.41,52.52,30.0")
        self.assertEqual(len(out), 2)
        self.assertIsInstance(out[0], Probe)
        self.assertEqual(out[0].lon, 13.40)
        self.assertEqual(out[0].lat, 52.52)
        self.assertEqual(out[0].speed_kmh, 45.0)
        self.assertEqual(out[1].speed_kmh, 30.0)

    def test_valid_two_part_no_speed(self):
        out = parse_probes("13.40,52.52;13.41,52.52")
        self.assertEqual(len(out), 2)
        self.assertIsNone(out[0].speed_kmh)
        self.assertIsNone(out[1].speed_kmh)

    def test_empty_returns_none(self):
        self.assertIsNone(parse_probes(""))
        self.assertIsNone(parse_probes(None))

    def test_one_part_raises(self):
        with self.assertRaises(ValueError):
            parse_probes("13.40")

    def test_non_numeric_raises(self):
        with self.assertRaises(ValueError):
            parse_probes("abc,def")

    def test_wrong_part_count_raises(self):
        with self.assertRaises(ValueError):
            parse_probes("13.40,52.52,45.0,extra")


class TrafficToGeoJSONTest(unittest.TestCase):
    def _sample(self):
        segments = [
            RoadSegment(id="s1", geometry=[(13.40, 52.52), (13.41, 52.52)], free_flow_kmh=50.0),
            RoadSegment(id="s2", geometry=[(13.41, 52.52), (13.42, 52.52)], free_flow_kmh=50.0),
            RoadSegment(id="s3", geometry=[(13.42, 52.52), (13.43, 52.52)], free_flow_kmh=50.0),
        ]
        probes = [
            Probe(lon=13.4005, lat=52.5200, speed_kmh=45.0),
            Probe(lon=13.4105, lat=52.5200, speed_kmh=20.0),
        ]
        return segments, probes

    def test_feature_collection_shape(self):
        segments, probes = self._sample()
        fc = traffic_to_geojson_inline(segments, probes)
        self.assertEqual(fc["type"], "FeatureCollection")
        self.assertIsInstance(fc["features"], list)
        self.assertEqual(len(fc["features"]), 3)

    def test_linestrings_with_congestion(self):
        segments, probes = self._sample()
        fc = traffic_to_geojson_inline(segments, probes)
        for feat in fc["features"]:
            self.assertEqual(feat["geometry"]["type"], "LineString")
            props = feat["properties"]
            self.assertIn("segment_id", props)
            self.assertIn("free_flow_kmh", props)
            self.assertIn("mean_speed_kmh", props)
            self.assertIn("probe_count", props)
            self.assertIn(props["congestion"],
                          ["free", "light", "moderate", "heavy", "jammed", "unknown"])


class ParseBboxTest(unittest.TestCase):
    def test_empty_returns_none(self):
        self.assertIsNone(parse_bbox(""))
        self.assertIsNone(parse_bbox(None))

    def test_valid_bbox(self):
        self.assertEqual(
            parse_bbox("13.39,52.51,13.41,52.53"),
            (13.39, 52.51, 13.41, 52.53),
        )

    def test_wrong_part_count_raises(self):
        with self.assertRaises(ValueError):
            parse_bbox("1,2,3")

    def test_non_numeric_raises(self):
        with self.assertRaises(ValueError):
            parse_bbox("bad")

    def test_out_of_range_raises(self):
        with self.assertRaises(ValueError):
            parse_bbox("13.39,52.51,200.0,52.53")

    def test_inverted_bounds_raises(self):
        with self.assertRaises(ValueError):
            parse_bbox("13.41,52.51,13.39,52.53")


class TrafficServiceBboxTest(unittest.TestCase):
    """Region-scoping behavior of ``TrafficService.traffic(bbox=...)``."""

    def test_berlin_bbox_keeps_segments(self):
        svc = TrafficService()
        default_fc = svc.traffic()
        # A Berlin-wide bbox enclosing every sample segment must not drop any
        # in-region data -> identical feature count to the default (no bbox).
        berlin_fc = svc.traffic(bbox=(13.39, 52.49, 13.47, 52.54))
        self.assertEqual(berlin_fc["type"], "FeatureCollection")
        self.assertGreaterEqual(len(berlin_fc["features"]), 1)
        self.assertEqual(len(berlin_fc["features"]), len(default_fc["features"]))

    def test_narrow_berlin_bbox_non_empty(self):
        # The Wave-22 example bbox overlaps at least one Berlin segment.
        svc = TrafficService()
        fc = svc.traffic(bbox=(13.39, 52.51, 13.41, 52.53))
        self.assertEqual(fc["type"], "FeatureCollection")
        self.assertGreaterEqual(len(fc["features"]), 1)

    def test_non_berlin_bbox_returns_empty(self):
        svc = TrafficService()
        paris_fc = svc.traffic(bbox=(2.22, 48.80, 2.47, 48.90))
        self.assertEqual(paris_fc, {"type": "FeatureCollection", "features": []})


def _free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class _LiveServerTest(unittest.TestCase):
    port = 8084

    def setUp(self):
        self.server = None
        for _ in range(20):
            try:
                self.server = self._make_server()
                break
            except OSError:
                self.port = _free_port()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self._wait_ready()
        self.addCleanup(self._stop)

    def _make_server(self):
        from vector_traffic.serve import make_server

        service = self._inline_service()
        return make_server(self.port, service, host="127.0.0.1")

    def _inline_service(self):
        return TrafficService()

    def _wait_ready(self):
        for _ in range(100):
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.2):
                    return
            except OSError:
                pass

    def _stop(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()

    def _get(self, path):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request("GET", path)
            resp = conn.getresponse()
            body = resp.read()
            return resp, body
        finally:
            conn.close()


class LiveTrafficTest(_LiveServerTest):
    def test_traffic_ok_and_cors(self):
        # Three probes deliberately, not one. `/traffic` withholds congestion
        # that rests on fewer than MIN_PROBES_FOR_CONGESTION probes (see
        # serve.apply_evidence_floor), and the point of THIS test is the
        # transport — 200, CORS, LineString geometry — so it supplies enough
        # evidence to get past the policy rather than asserting against it.
        resp, body = self._get("/traffic?probes=13.400,52.520,20.0;13.4005,52.5201,21.0;13.401,52.5202,19.0")
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.getheader("Access-Control-Allow-Origin"), "*")
        fc = json.loads(body)
        self.assertEqual(fc["type"], "FeatureCollection")
        features = fc["features"]
        self.assertGreaterEqual(len(features), 1)
        for feat in features:
            self.assertEqual(feat["geometry"]["type"], "LineString")

    def test_healthz(self):
        resp, body = self._get("/healthz")
        self.assertEqual(resp.status, 200)
        self.assertEqual(body, b"ok")

    def test_malformed_probes_400(self):
        resp, body = self._get("/traffic?probes=abc,def")
        self.assertEqual(resp.status, 400)
        obj = json.loads(body)
        self.assertIn("error", obj)

    def test_missing_segments_404(self):
        resp, body = self._get("/traffic?segments=does_not_exist.geojson")
        self.assertEqual(resp.status, 404)
        obj = json.loads(body)
        self.assertIn("error", obj)

    def test_unknown_path_404(self):
        resp, body = self._get("/nope")
        self.assertEqual(resp.status, 404)
        self.assertEqual(body, b"not found")

    def test_bbox_berlin_ok(self):
        resp, body = self._get(
            "/traffic?bbox=13.39,52.51,13.41,52.53&probes=13.400,52.520,20.0;13.4005,52.5201,21.0;13.401,52.5202,19.0")
        self.assertEqual(resp.status, 200)
        fc = json.loads(body)
        self.assertEqual(fc["type"], "FeatureCollection")
        self.assertGreaterEqual(len(fc["features"]), 1)

    def test_bbox_non_berlin_empty(self):
        resp, body = self._get("/traffic?bbox=2.22,48.80,2.47,48.90")
        self.assertEqual(resp.status, 200)
        fc = json.loads(body)
        self.assertEqual(fc["type"], "FeatureCollection")
        self.assertEqual(fc["features"], [])
        # The collection also carries the evidence-floor summary now. Asserting
        # the whole dict equals a two-key literal pinned the absence of that
        # summary, which is not what this test is about.
        self.assertEqual(fc["withheld_low_evidence"], 0)


class EvidenceFloorTest(_LiveServerTest):
    """Congestion has to rest on more than one vehicle.

    The stack's traffic is currently SYNTHETIC: `loaders.build_graph_probes`
    places exactly one probe per segment and marks every thirteenth edge jammed
    with `i % 13`. So `/traffic` answered with 2,000 segments of which 154 were
    "jammed", and the native HUD reported "154 jams" over Doha to the driver as
    live congestion. The V3 brief is explicit that traffic must not be presented
    as live if it is decorative.

    One probe is one vehicle, and one vehicle can be parked, turning, or a phone
    on a bus.
    """

    def test_a_single_probe_does_not_make_a_jam(self):
        resp, body = self._get("/traffic?probes=13.400,52.520,3.0")
        self.assertEqual(resp.status, 200)
        fc = json.loads(body)
        self.assertEqual(fc["features"], [],
                         "a jam was declared on the evidence of one vehicle")
        self.assertGreaterEqual(fc["withheld_low_evidence"], 1)

    def test_enough_probes_are_served(self):
        resp, body = self._get(
            "/traffic?probes=13.400,52.520,3.0;13.4005,52.5201,4.0;13.401,52.5202,3.5")
        fc = json.loads(body)
        self.assertGreaterEqual(len(fc["features"]), 1,
                                "corroborated congestion was withheld")

    def test_the_withholding_is_reported_rather_than_silent(self):
        # An empty result with a count says "we have no traffic yet". An empty
        # result with no explanation looks like a broken service — and this
        # codebase has already been bitten twice by telemetry failing invisibly.
        resp, body = self._get("/traffic?probes=13.400,52.520,3.0")
        fc = json.loads(body)
        self.assertIn("withheld_low_evidence", fc)
        self.assertIn("min_probes", fc)

    def test_the_floor_is_a_display_policy_and_says_so(self):
        from vector_traffic.aggregate import MIN_PROBES_FOR_CONGESTION
        resp, body = self._get("/traffic?probes=13.400,52.520,3.0")
        fc = json.loads(body)
        self.assertEqual(fc["min_probes"], MIN_PROBES_FOR_CONGESTION)

    def test_bbox_bad_400(self):
        resp, body = self._get("/traffic?bbox=bad")
        self.assertEqual(resp.status, 400)
        obj = json.loads(body)
        self.assertIn("error", obj)


class UnavailableServiceTest(_LiveServerTest):
    def _inline_service(self):
        return None

    def test_unavailable_service_503(self):
        resp, body = self._get("/traffic")
        self.assertEqual(resp.status, 503)
        obj = json.loads(body)
        self.assertIn("error", obj)


class SecuredTrafficServeTest(_LiveServerTest):
    """End-to-end bearer-token enforcement through the live handler."""

    TOKEN = "test-vector-service-token"
    ENDPOINT = "/traffic"

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


class FakeStore:
    """Records upserts and returns a deliberately-modified state on get(),
    so a test can prove the served payload is sourced FROM the store."""

    def __init__(self):
        self.upserted = []
        self._by_id = {}

    def upsert(self, segment):
        self.upserted.append(segment)
        # Persist a 'jammed' override to distinguish store-sourced values.
        self._by_id[segment.segment_id] = TrafficState(
            segment_id=segment.segment_id,
            geometry=segment.geometry,
            free_flow_kmh=segment.free_flow_kmh,
            mean_speed_kmh=segment.mean_speed_kmh,
            probe_count=segment.probe_count,
            congestion="jammed",
        )

    def get(self, segment_id):
        return self._by_id.get(segment_id)


class ServiceStoreConsumptionTest(unittest.TestCase):
    """Wave 26b: TrafficService persists estimates AND serves from the store."""

    def test_estimates_are_persisted(self):
        store = FakeStore()
        svc = TrafficService(store=store)
        svc.traffic()
        self.assertGreater(len(store.upserted), 0)

    def test_served_payload_comes_from_store(self):
        store = FakeStore()
        svc = TrafficService(store=store)
        fc = svc.traffic()
        # Every feature's congestion must reflect the stored 'jammed' override,
        # proving the response is read FROM the store, not the raw estimate.
        self.assertEqual(fc["type"], "FeatureCollection")
        self.assertGreater(len(fc["features"]), 0)
        for feat in fc["features"]:
            self.assertEqual(feat["properties"]["congestion"], "jammed")

    def test_bbox_request_scoped_to_store(self):
        store = FakeStore()
        svc = TrafficService(store=store)
        fc = svc.traffic(bbox=(13.39, 52.51, 13.41, 52.53))
        self.assertEqual(fc["type"], "FeatureCollection")
        for feat in fc["features"]:
            self.assertEqual(feat["properties"]["congestion"], "jammed")


class FakeBus:
    """Captures published envelopes so tests can assert bus output."""

    def __init__(self):
        self.published = []

    def publish(self, channel, envelope):
        self.published.append((channel, envelope))


class TrafficBusPublishTest(unittest.TestCase):
    """Wave 26c: TrafficService publishes a TRAFFIC_CONGESTION envelope."""

    def test_publishes_congestion_envelope(self):
        bus = FakeBus()
        svc = TrafficService(bus=bus)
        svc.traffic()
        self.assertEqual(len(bus.published), 1)
        channel, env = bus.published[0]
        self.assertEqual(channel, "#broadcast")
        self.assertEqual(env["intent"], "NOTIFY")
        self.assertEqual(env["payload"]["kind"], "traffic_congestion")
        self.assertGreater(len(env["payload"]["segments"]), 0)

    def test_no_bus_no_publish(self):
        svc = TrafficService()  # no bus
        fc = svc.traffic()
        self.assertEqual(fc["type"], "FeatureCollection")


if __name__ == "__main__":
    unittest.main()
