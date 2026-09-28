import unittest

from vector_routing.bus_envelope import (
    ROUTING_AGENT_ADDRESS,
    is_navigate_request,
    is_valid_envelope,
    make_navigate_request,
    make_navigation_result,
)


class EnvelopeBuilderTest(unittest.TestCase):
    def test_navigate_request_has_all_required_fields(self):
        env = make_navigate_request(
            "agent://test-requester-01",
            [52.51, 13.39],
            [52.53, 13.41],
            via=[[52.52, 13.40]],
            profile="car",
            correlation_id="cid-1",
        )
        for field in ("id", "correlation_id", "from", "to", "intent", "priority", "sla_ms", "timestamp", "payload"):
            self.assertIn(field, env)

    def test_navigate_request_shape(self):
        env = make_navigate_request(
            "agent://test-requester-01",
            [52.51, 13.39],
            [52.53, 13.41],
            profile="car",
            correlation_id="cid-1",
        )
        self.assertEqual(env["intent"], "TASK")
        self.assertEqual(env["to"], ROUTING_AGENT_ADDRESS)
        self.assertEqual(env["from"], "agent://test-requester-01")
        self.assertEqual(env["priority"], "P2")
        self.assertEqual(env["sla_ms"], 5000)
        self.assertEqual(env["correlation_id"], "cid-1")
        self.assertEqual(env["payload"]["kind"], "navigate")
        self.assertEqual(env["payload"]["from_ll"], [52.51, 13.39])
        self.assertEqual(env["payload"]["to_ll"], [52.53, 13.41])
        self.assertEqual(env["payload"]["profile"], "car")
        self.assertEqual(env["payload"]["reply_to"], "agent://test-requester-01")
        self.assertEqual(env["payload"]["via"], [])

    def test_navigate_request_generates_uuid_when_no_correlation(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        self.assertTrue(env["correlation_id"])
        self.assertTrue(env["id"])

    def test_is_valid_envelope_true(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        self.assertTrue(is_valid_envelope(env))

    def test_is_valid_envelope_false_when_field_missing(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        for field in ("id", "correlation_id", "from", "to", "intent", "priority", "sla_ms", "timestamp"):
            bad = dict(env)
            del bad[field]
            self.assertFalse(is_valid_envelope(bad), msg="missing %s should be invalid" % field)

    def test_is_valid_envelope_false_on_non_object(self):
        self.assertFalse(is_valid_envelope(None))
        self.assertFalse(is_valid_envelope([1, 2, 3]))
        self.assertFalse(is_valid_envelope("x"))

    def test_is_valid_envelope_false_on_bad_enum(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        bad = dict(env)
        bad["intent"] = "NOPE"
        self.assertFalse(is_valid_envelope(bad))
        bad2 = dict(env)
        bad2["priority"] = "P9"
        self.assertFalse(is_valid_envelope(bad2))

    def test_is_valid_envelope_false_on_bad_sla(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        bad = dict(env)
        bad["sla_ms"] = float("nan")
        self.assertFalse(is_valid_envelope(bad))
        bad2 = dict(env)
        bad2["sla_ms"] = "5000"
        self.assertFalse(is_valid_envelope(bad2))

    def test_is_navigate_request_true(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        self.assertTrue(is_navigate_request(env))

    def test_is_navigate_request_false_on_wrong_to(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        env["to"] = "agent://other"
        self.assertFalse(is_navigate_request(env))

    def test_is_navigate_request_false_on_wrong_kind(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        env["payload"]["kind"] = "something-else"
        self.assertFalse(is_navigate_request(env))

    def test_is_navigate_request_false_on_missing_payload(self):
        env = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        env["payload"] = None
        self.assertFalse(is_navigate_request(env))

    def test_navigation_result_echoes_correlation(self):
        req = make_navigate_request(
            "agent://r", [1.0, 2.0], [3.0, 4.0], correlation_id="cid-echo"
        )
        res = make_navigation_result(req, True, geojson={"x": 1})
        self.assertEqual(res["correlation_id"], "cid-echo")
        self.assertEqual(res["intent"], "NOTIFY")
        self.assertEqual(res["to"], "agent://r")
        self.assertEqual(res["from"], ROUTING_AGENT_ADDRESS)
        self.assertEqual(res["payload"]["kind"], "navigation_result")
        self.assertTrue(res["payload"]["ok"])
        self.assertEqual(res["payload"]["geojson"], {"x": 1})
        self.assertIsNone(res["payload"]["error"])

    def test_navigation_result_failure_shape(self):
        req = make_navigate_request("agent://r", [1.0, 2.0], [3.0, 4.0])
        res = make_navigation_result(req, False, error="boom")
        self.assertFalse(res["payload"]["ok"])
        self.assertIsNone(res["payload"]["geojson"])
        self.assertEqual(res["payload"]["error"], "boom")


if __name__ == "__main__":
    unittest.main()
