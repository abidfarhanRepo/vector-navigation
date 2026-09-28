import unittest
from pathlib import Path

from vector_bus_client import LocalBus
from vector_routing.bus_envelope import (
    ROUTING_AGENT_ADDRESS,
    make_navigate_request,
)
from vector_routing.service import RoutingService

SAMPLE = Path(__file__).resolve().parents[1] / "routing-data" / "sample_network.geojson"


class NavBusIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.bus = LocalBus()
        self.service = RoutingService.from_geojson(str(SAMPLE), bus=self.bus)
        # Collector for #events results.
        self.results = []
        self.bus.subscribe("#events", "collector", lambda e, a: self.results.append(e))
        # Wire the routing consumer onto #tasks.
        self.service.start_bus_consumer()

    def _publish_navigate(self, from_ll, to_ll, via=None, requester="agent://test-requester-01"):
        req = make_navigate_request(
            requester,
            from_ll,
            to_ll,
            via=via,
            profile="car",
            correlation_id="cid-int-1",
        )
        self.bus.publish("#tasks", req)
        return req

    def test_success_publishes_one_navigation_result(self):
        req = self._publish_navigate([52.51, 13.39], [52.54, 13.41])
        self.assertEqual(len(self.results), 1)
        res = self.results[0]
        self.assertEqual(res["payload"]["kind"], "navigation_result")
        self.assertTrue(res["payload"]["ok"])
        self.assertEqual(res["correlation_id"], req["correlation_id"])
        self.assertIsNone(res["payload"]["error"])
        props = res["payload"]["geojson"]["features"][0]["properties"]
        self.assertIn("steps", props)
        self.assertIn("distance_km", props)
        self.assertGreater(len(props["steps"]), 0)
        self.assertIsInstance(props["distance_km"], float)

    def test_via_multi_leg_succeeds(self):
        req = self._publish_navigate(
            [52.51, 13.39], [52.54, 13.41], via=[[52.52, 13.40]]
        )
        self.assertEqual(len(self.results), 1)
        res = self.results[0]
        self.assertTrue(res["payload"]["ok"])
        self.assertEqual(res["correlation_id"], req["correlation_id"])

    def test_request_to_different_agent_is_ignored(self):
        req = make_navigate_request(
            "agent://r", [52.51, 13.39], [52.54, 13.41], correlation_id="cid-ign"
        )
        req["to"] = "agent://some-other-agent"
        self.bus.publish("#tasks", req)
        self.assertEqual(len(self.results), 0)

    def test_malformed_coords_yields_failure_envelope(self):
        req = make_navigate_request(
            "agent://r", [52.51, 13.39], [52.54, 13.41], correlation_id="cid-bad"
        )
        # Corrupt the coordinate payload so navigate() raises.
        req["payload"]["from_ll"] = "garbage"
        self.bus.publish("#tasks", req)
        self.assertEqual(len(self.results), 1)
        res = self.results[0]
        self.assertEqual(res["payload"]["kind"], "navigation_result")
        self.assertFalse(res["payload"]["ok"])
        self.assertIsNotNone(res["payload"]["error"])
        self.assertIsNone(res["payload"]["geojson"])
        self.assertEqual(res["correlation_id"], "cid-bad")


if __name__ == "__main__":
    unittest.main()
