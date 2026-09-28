"""Live (in-process) cross-engine bus integration: traffic publishes, routing consumes.

Uses the shared in-memory LocalBus (no network, no VECTOR_BUS_URL) so it runs
hermetically where BOTH repos are checked out (local dev), while still
exercising the real publish -> #broadcast -> routing traffic consumer -> overlay
path (Wave 26d).

NOTE: this test imports the sibling ``vector-traffic`` package to build a real
TRAFFIC_CONGESTION envelope. Under the per-repo CI gate the sibling is NOT
checked out (ADR-0003 isolation), so the import fails and the test SKIPS —
matching the check_vendor.py skip convention. It runs in local dev and the
manual live proof.
"""

import os
import unittest
from pathlib import Path

from vector_bus_client import LocalBus
from vector_routing.service import RoutingService
from vector_routing.traffic_overlay import TrafficOverlay

SAMPLE_ROUTING = Path(__file__).resolve().parents[1] / "routing-data" / "sample_network.geojson"


def _load_traffic_envelope():
    """Build a real TRAFFIC_CONGESTION envelope via the sibling traffic package."""
    from vector_traffic.serve import TrafficService
    from vector_traffic.bus_envelope import make_traffic_congestion

    segs = [
        type("S", (), {
            "segment_id": "seg-1",
            "geometry": [(13.39, 52.51), (13.40, 52.52)],
            "free_flow_kmh": 50.0,
            "mean_speed_kmh": 5.0,
            "probe_count": 3,
            "congestion": "heavy",
        })()
    ]
    return make_traffic_congestion(segs)


class TrafficRoutingBusIntegrationTest(unittest.TestCase):
    def test_traffic_publish_reaches_routing_overlay(self):
        try:
            env = _load_traffic_envelope()
        except ImportError:
            self.skipTest("vector-traffic sibling not checked out (per-repo isolation)")

        bus = LocalBus()
        routing = RoutingService.from_geojson(str(SAMPLE_ROUTING), bus=bus)
        routing.start_traffic_consumer()

        bus.publish("#broadcast", env)

        # Routing's consumer must have built an overlay from the envelope.
        self.assertIsNotNone(routing._overlay)
        self.assertIsInstance(routing._overlay, TrafficOverlay)
        self.assertGreater(len(routing._overlay._by_pair), 0)

    def test_routing_reroutes_with_live_overlay(self):
        bus = LocalBus()
        routing = RoutingService.from_geojson(str(SAMPLE_ROUTING), bus=bus)
        routing.start_traffic_consumer()

        overlay = TrafficOverlay.from_segments([
            {
                "geometry": [[52.51, 13.39], [52.52, 13.40]],
                "free_flow_kmh": 50.0,
                "mean_speed_kmh": 5.0,
                "probe_count": 3,
                "congestion": "heavy",
            }
        ])
        routing.set_traffic_overlay(overlay)
        res = routing.navigate((13.39, 52.51), (13.41, 52.54))
        self.assertIn("path", res)
        self.assertGreater(len(res["path"]), 0)


if __name__ == "__main__":
    unittest.main()
