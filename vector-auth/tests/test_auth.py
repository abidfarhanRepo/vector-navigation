"""Tests for vector_auth.Auth: dev-anonymous vs secured enforcement + scoped CORS."""

import os
import unittest
from types import SimpleNamespace

from vector_auth import Auth


def _fake_handler(authorization: str = ""):
    """Minimal stand-in for a BaseHTTPRequestHandler capturing sent responses."""
    sent = {}

    class FakeHeaders:
        def get(self, key, default=""):
            return authorization if key == "Authorization" else default

    def send_response(code):
        sent["code"] = code

    def send_header(k, v):
        sent.setdefault("headers", {})[k] = v

    def end_headers():
        sent["ended"] = True

    def wfile_write(body):
        sent["body"] = body

    return SimpleNamespace(
        headers=FakeHeaders(),
        send_response=send_response,
        send_header=send_header,
        end_headers=end_headers,
        wfile=SimpleNamespace(write=wfile_write),
    ), sent


class TestAuthDevAnonymous(unittest.TestCase):
    def setUp(self):
        os.environ.pop("VECTOR_SERVICE_TOKEN", None)
        os.environ.pop("VECTOR_BUS_ROOT_SECRET", None)

    def test_dev_mode_open_cors(self):
        auth = Auth()
        self.assertFalse(auth.enabled)
        headers = auth.cors_headers()
        self.assertEqual(headers["Access-Control-Allow-Origin"], "*")

    def test_dev_mode_always_authorizes(self):
        auth = Auth()
        handler, sent = _fake_handler()
        self.assertTrue(auth.enforce(handler))
        self.assertNotIn("code", sent)  # nothing sent


class TestAuthSecured(unittest.TestCase):
    def setUp(self):
        os.environ["VECTOR_SERVICE_TOKEN"] = "s3cr3t"

    def tearDown(self):
        os.environ.pop("VECTOR_SERVICE_TOKEN", None)

    def test_missing_header_401(self):
        auth = Auth()
        self.assertTrue(auth.enabled)
        handler, sent = _fake_handler()
        self.assertFalse(auth.enforce(handler))
        self.assertEqual(sent["code"], 401)

    def test_wrong_token_401(self):
        auth = Auth()
        handler, sent = _fake_handler("Bearer wrong")
        self.assertFalse(auth.enforce(handler))
        self.assertEqual(sent["code"], 401)

    def test_valid_token_ok(self):
        auth = Auth()
        handler, sent = _fake_handler("Bearer s3cr3t")
        self.assertTrue(auth.enforce(handler))
        self.assertNotIn("code", sent)

    def test_scoped_cors(self):
        os.environ["VECTOR_CORS_ORIGINS"] = "https://app.vector.dev,https://map.vector.dev"
        auth = Auth()
        headers = auth.cors_headers()
        self.assertEqual(headers["Access-Control-Allow-Origin"], "https://app.vector.dev")
        self.assertIn("Access-Control-Allow-Headers", headers)

    def test_falls_back_to_bus_secret(self):
        os.environ.pop("VECTOR_SERVICE_TOKEN", None)
        os.environ["VECTOR_BUS_ROOT_SECRET"] = "rootsecret"
        try:
            auth = Auth()
            self.assertTrue(auth.enabled)
            handler, sent = _fake_handler("Bearer rootsecret")
            self.assertTrue(auth.enforce(handler))
        finally:
            os.environ.pop("VECTOR_BUS_ROOT_SECRET", None)


if __name__ == "__main__":
    unittest.main()
