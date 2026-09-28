import unittest

from vector_traffic.errors import TrafficError, InputError, MapMatchError


class TestErrors(unittest.TestCase):
    def test_is_exception(self):
        self.assertIsInstance(TrafficError(), Exception)

    def test_subclasses(self):
        self.assertTrue(issubclass(InputError, TrafficError))
        self.assertTrue(issubclass(MapMatchError, TrafficError))

    def test_cause_stored(self):
        cause = ValueError("root")
        err = InputError("bad input", cause=cause)
        self.assertEqual(err.message, "bad input")
        self.assertIs(err.cause, cause)


if __name__ == "__main__":
    unittest.main()
