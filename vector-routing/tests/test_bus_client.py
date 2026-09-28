import unittest
from pathlib import Path

from vector_bus_client import (
    LocalBus,
    NetworkBusClient,
    create_bus,
)
from vector_routing.service import RoutingService
from vector_routing.bus_envelope import make_navigate_request

SAMPLE = Path(__file__).resolve().parents[1] / "routing-data" / "sample_network.geojson"


class LocalBusRoundTripTest(unittest.TestCase):
    def test_publish_delivers_to_subscriber_and_acks(self):
        bus = LocalBus()
        received = []
        delivery = bus.publish("#tasks", {"hello": "world"})
        # nothing subscribed yet
        self.assertEqual(received, [])

        def handler(env, ack):
            received.append(env)
            ack()

        bus.subscribe("#tasks", "c1", handler)
        delivery = bus.publish("#tasks", {"hello": "world"})
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0], {"hello": "world"})
        self.assertTrue(bus.acked(delivery))

    def test_subscribers_are_channel_scoped(self):
        bus = LocalBus()
        tasks = []
        events = []

        bus.subscribe("#tasks", "c1", lambda e, a: tasks.append(e))
        bus.subscribe("#events", "c1", lambda e, a: events.append(e))
        bus.publish("#tasks", {"n": 1})
        bus.publish("#events", {"n": 2})
        self.assertEqual(len(tasks), 1)
        self.assertEqual(len(events), 1)
        self.assertEqual(tasks[0]["n"], 1)
        self.assertEqual(events[0]["n"], 2)

    def test_multiple_subscribers_all_receive(self):
        bus = LocalBus()
        a, b = [], []
        bus.subscribe("#tasks", "c1", lambda e, ack: a.append(e))
        bus.subscribe("#tasks", "c2", lambda e, ack: b.append(e))
        bus.publish("#tasks", {"x": 1})
        self.assertEqual(len(a), 1)
        self.assertEqual(len(b), 1)

    def test_ack_records_even_with_noop_ack(self):
        bus = LocalBus()
        delivery = bus.publish("#tasks", {"x": 1})
        # never subscribed -> delivery id still generated and ackable
        bus.ack(delivery, "c1", channel="#tasks")
        self.assertTrue(bus.acked(delivery))


class CreateBusFactoryTest(unittest.TestCase):
    def test_create_bus_none_returns_local(self):
        bus = create_bus(None)
        self.assertIsInstance(bus, LocalBus)

    def test_create_bus_url_returns_network(self):
        bus = create_bus("http://example:8090")
        self.assertIsInstance(bus, NetworkBusClient)
        self.assertEqual(bus.url, "http://example:8090")

    def test_network_client_is_lazy(self):
        # No network call should happen at construction time.
        client = NetworkBusClient("http://localhost:8090")
        self.assertEqual(client.url, "http://localhost:8090")


class BusResultChannelRegressionTest(unittest.TestCase):
    """Regression: navigation results must be delivered on #events (not #broadcast).

    The bus router maps intent NOTIFY -> #broadcast, so a result envelope published
    without an explicit channel lands on #broadcast and is silently lost by every
    #events subscriber (the viewer / requesting agent). This test pins the
    consumer to publish on #events explicitly.
    """

    def test_navigation_result_reaches_events_subscriber(self):
        bus = LocalBus()
        service = RoutingService.from_geojson(str(SAMPLE), bus=bus)
        received = []
        bus.subscribe("#events", "collector", lambda e, a: received.append(e))
        service.start_bus_consumer()

        req = make_navigate_request(
            "agent://test/x",
            [52.52, 13.40],
            [52.51, 13.41],
            profile="car",
            correlation_id="cid-regr-1",
        )
        bus.publish("#tasks", req)
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["payload"]["kind"], "navigation_result")
        self.assertEqual(received[0]["correlation_id"], "cid-regr-1")

    def test_navigation_result_does_not_reach_broadcast(self):
        bus = LocalBus()
        service = RoutingService.from_geojson(str(SAMPLE), bus=bus)
        broadcast = []
        bus.subscribe("#broadcast", "collector-b", lambda e, a: broadcast.append(e))
        service.start_bus_consumer()

        req = make_navigate_request(
            "agent://test/y",
            [52.52, 13.40],
            [52.51, 13.41],
            profile="car",
            correlation_id="cid-regr-2",
        )
        bus.publish("#tasks", req)
        self.assertEqual(broadcast, [])


if __name__ == "__main__":
    unittest.main()
