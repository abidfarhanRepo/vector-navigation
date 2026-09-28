"""Tests for the tile epoch — issue 08's cache invalidation.

The failure this closes is not a crash, it is an *absence*: a promoted road is
verified, written to disk, and never seen, because every cache in front of the
tile server is entitled to keep the old bytes at the same URL. So the tests here
are mostly about ordering and totality rather than arithmetic:

* the epoch must only advance after new tiles exist, and
* nothing about reading it may ever fail, because a served tile set is never
  worth taking down over a version string.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_tile_gen.tile_version import (
    INITIAL_EPOCH,
    VERSION_FILENAME,
    bump_version,
    read_version,
    tile_url_template,
    version_path,
)

T0 = 1_754_000_000_000


class ReadVersionTest(unittest.TestCase):
    def test_never_versioned_reads_as_the_initial_epoch(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(read_version(tmp)["epoch"], INITIAL_EPOCH)

    def test_missing_directory_is_not_an_error(self):
        missing = os.path.join(tempfile.gettempdir(), "vector-no-such-tiles-xyz")
        self.assertEqual(read_version(missing)["epoch"], INITIAL_EPOCH)

    def test_corrupt_file_reads_as_initial_rather_than_raising(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(version_path(tmp), "w", encoding="utf-8") as fh:
                fh.write("{{{ not json")
            record = read_version(tmp)
            self.assertEqual(record["epoch"], INITIAL_EPOCH)
            self.assertEqual(record["reason"], "unreadable")

    def test_non_integer_epoch_reads_as_initial(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(version_path(tmp), "w", encoding="utf-8") as fh:
                json.dump({"epoch": "seven"}, fh)
            self.assertEqual(read_version(tmp)["epoch"], INITIAL_EPOCH)

    def test_a_json_list_is_not_mistaken_for_a_record(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(version_path(tmp), "w", encoding="utf-8") as fh:
                json.dump([1, 2, 3], fh)
            self.assertEqual(read_version(tmp)["epoch"], INITIAL_EPOCH)


class BumpVersionTest(unittest.TestCase):
    def test_bump_increments_and_persists(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = bump_version(tmp, now_ms=T0, reason="promote:2", tiles_changed=17)
            self.assertEqual(first["epoch"], INITIAL_EPOCH + 1)
            self.assertEqual(read_version(tmp)["epoch"], INITIAL_EPOCH + 1)
            self.assertEqual(read_version(tmp)["tiles_changed"], 17)

    def test_repeated_bumps_are_monotonic(self):
        with tempfile.TemporaryDirectory() as tmp:
            epochs = [bump_version(tmp, now_ms=T0 + i)["epoch"] for i in range(5)]
            self.assertEqual(epochs, sorted(epochs))
            self.assertEqual(len(set(epochs)), 5)

    def test_bump_creates_the_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            nested = os.path.join(tmp, "tiles")
            bump_version(nested, now_ms=T0)
            self.assertTrue(os.path.exists(os.path.join(nested, VERSION_FILENAME)))

    def test_reason_and_timestamp_survive_the_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            bump_version(tmp, now_ms=T0, reason="withdraw-all")
            record = read_version(tmp)
            self.assertEqual(record["reason"], "withdraw-all")
            self.assertEqual(record["updated_at"], T0)

    def test_no_temp_file_is_left_behind(self):
        with tempfile.TemporaryDirectory() as tmp:
            bump_version(tmp, now_ms=T0)
            leftovers = [n for n in os.listdir(tmp) if n.endswith(".tmp")]
            self.assertEqual(leftovers, [])


class TileUrlTemplateTest(unittest.TestCase):
    def test_epoch_is_in_the_url(self):
        url = tile_url_template("http://localhost:8080", epoch=4)
        self.assertEqual(url, "http://localhost:8080/tiles/{z}/{x}/{y}.mvt?v=4")

    def test_url_shape_is_stable_at_epoch_zero(self):
        """A template that changes shape between installs is its own cache bug."""
        fresh = tile_url_template("http://x", epoch=0)
        baked = tile_url_template("http://x", epoch=9)
        self.assertEqual(fresh.replace("v=0", "v=N"), baked.replace("v=9", "v=N"))

    def test_trailing_slash_does_not_double(self):
        self.assertNotIn("//tiles", tile_url_template("http://x/", epoch=1))

    def test_epoch_read_from_disk_when_not_supplied(self):
        with tempfile.TemporaryDirectory() as tmp:
            bump_version(tmp, now_ms=T0)
            bump_version(tmp, now_ms=T0)
            self.assertIn("?v=2", tile_url_template("http://x", tiles_dir=tmp))


if __name__ == "__main__":
    unittest.main()
