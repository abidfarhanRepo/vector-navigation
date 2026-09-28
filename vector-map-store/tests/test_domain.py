import unittest

from vector_map_store.geometry import bbox_contains


class BboxTest(unittest.TestCase):
    def test_inside(self):
        self.assertTrue(bbox_contains((-10, -10, 10, 10), 0.0, 0.0))

    def test_outside(self):
        self.assertFalse(bbox_contains((-10, -10, 10, 10), 20.0, 0.0))

    def test_boundary(self):
        self.assertTrue(bbox_contains((0, 0, 1, 1), 1.0, 1.0))


if __name__ == "__main__":
    unittest.main()
