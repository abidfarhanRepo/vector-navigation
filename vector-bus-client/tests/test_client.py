"""Unit tests for the shared vector_bus_client (LocalBus + NetworkBusClient URL selection)."""

import os
import unittest

from vector_bus_client import LocalBus, NetworkBusClient, create_bus


class LocalBusTest(unittest.TestCase):
    def test_publish_delivers_to_subscriber(self):
        bus = LocalBus()
        received = []

        def handler(env, ack):
            received.append(env)
            ack()

        bus.subscribe("#events", "c1", handler)
        env = {"id": "1", "payload": {"kind": "x"}}
        bus.publish("#events", env)
        self.assertEqual(received, [env])

    def test_ack_recorded(self):
        bus = LocalBus()
        bus.subscribe("#tasks", "c1", lambda env, ack: ack())
        did = bus.publish("#tasks", {"id": "a"})
        self.assertTrue(bus.acked(did))

    def test_channel_isolation(self):
        bus = LocalBus()
        hits = []
        bus.subscribe("#events", "c1", lambda env, ack: (hits.append(env), ack()))
        bus.publish("#tasks", {"id": "x"})
        self.assertEqual(hits, [])


class CreateBusTest(unittest.TestCase):
    def test_create_bus_returns_local_without_url(self):
        saved = os.environ.pop("VECTOR_BUS_URL", None)
        try:
            self.assertIsInstance(create_bus(), LocalBus)
            self.assertIsInstance(create_bus(None), LocalBus)
        finally:
            if saved is not None:
                os.environ["VECTOR_BUS_URL"] = saved

    def test_create_bus_returns_network_with_url(self):
        self.assertIsInstance(create_bus("http://localhost:8090"), NetworkBusClient)


if __name__ == "__main__":
    unittest.main()
