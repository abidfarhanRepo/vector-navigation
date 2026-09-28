"""Tests for trip-scoped pseudonyms (ticket 13, adr-0068).

The K floor counts DISTINCT TRIP pseudonyms, so it means nothing unless one
pseudonym is exactly one trip. Both errors are real and opposite: a pseudonym
spanning several trips links a person's journeys (defeating endpoint truncation),
and a pseudonym covering part of a trip manufactures fake distinct trips
(defeating the floor itself).

The second was live — the web edge minted per request while the client uploads a
batch every 30 fixes, so one drive arrived as five trips and satisfied K=5 alone.
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_privacy.trip import (
    REASON_NO_TRIP_TOKEN,
    load_or_create_salt,
    mint_client_token,
    trip_pseudonym,
    valid_client_token,
)

SALT = b"a-fixed-test-salt"


class TokenShapeTest(unittest.TestCase):
    def test_minted_tokens_are_acceptable(self):
        for _ in range(20):
            self.assertTrue(valid_client_token(mint_client_token()))

    def test_minted_tokens_are_unique(self):
        self.assertEqual(len({mint_client_token() for _ in range(200)}), 200)

    def test_rejects_shapes_that_could_carry_identity(self):
        # A careless client build must not be able to use an email or device name
        # as its "token" and have it hashed into the store as if opaque.
        for bad in ("", "short", "user@example.com", "Ahmed's iPhone",
                    "a" * 200, None, 12345, True, {"t": "x"}):
            self.assertFalse(valid_client_token(bad), repr(bad))


class PseudonymTest(unittest.TestCase):
    def test_same_token_yields_the_same_pseudonym(self):
        """The property K needs: every batch of one trip agrees."""
        token = mint_client_token()
        first, r1 = trip_pseudonym(token, SALT)
        second, r2 = trip_pseudonym(token, SALT)
        self.assertEqual(first, second)
        self.assertIsNone(r1)
        self.assertIsNone(r2)

    def test_different_tokens_yield_different_pseudonyms(self):
        a, _ = trip_pseudonym(mint_client_token(), SALT)
        b, _ = trip_pseudonym(mint_client_token(), SALT)
        self.assertNotEqual(a, b)

    def test_the_token_is_not_recoverable_from_the_pseudonym(self):
        token = mint_client_token()
        pseudonym, _ = trip_pseudonym(token, SALT)
        self.assertNotIn(token, pseudonym)
        self.assertTrue(pseudonym.startswith("trip_"))

    def test_a_different_salt_gives_a_different_pseudonym(self):
        """Rotating the salt must make historical pseudonyms unlinkable."""
        token = mint_client_token()
        a, _ = trip_pseudonym(token, SALT)
        b, _ = trip_pseudonym(token, b"a-different-salt")
        self.assertNotEqual(a, b)

    def test_missing_token_is_reported_not_silently_accepted(self):
        pseudonym, reason = trip_pseudonym(None, SALT)
        self.assertEqual(reason, REASON_NO_TRIP_TOKEN)
        self.assertTrue(pseudonym.startswith("trip_"))

    def test_missing_tokens_do_not_collide_with_each_other(self):
        a, _ = trip_pseudonym(None, SALT)
        b, _ = trip_pseudonym(None, SALT)
        self.assertNotEqual(a, b)

    def test_a_rejected_shape_falls_back_and_is_reported(self):
        _, reason = trip_pseudonym("user@example.com", SALT)
        self.assertEqual(reason, REASON_NO_TRIP_TOKEN)


class SaltPersistenceTest(unittest.TestCase):
    def test_salt_is_stable_across_calls(self):
        """A salt that changed on restart would re-split in-flight trips."""
        with tempfile.TemporaryDirectory() as tmp:
            os.environ.pop("VECTOR_TRIP_SALT", None)
            first = load_or_create_salt(tmp)
            second = load_or_create_salt(tmp)
            self.assertEqual(first, second)

    def test_env_salt_wins(self):
        with tempfile.TemporaryDirectory() as tmp:
            os.environ["VECTOR_TRIP_SALT"] = "from-the-environment"
            try:
                self.assertEqual(load_or_create_salt(tmp), b"from-the-environment")
            finally:
                os.environ.pop("VECTOR_TRIP_SALT", None)

    def test_unwritable_dir_still_returns_a_salt(self):
        os.environ.pop("VECTOR_TRIP_SALT", None)
        salt = load_or_create_salt(os.path.join(tempfile.gettempdir(), "no\x00such"))
        self.assertTrue(salt)


class OneTripDoesNotClearTheFloorTest(unittest.TestCase):
    """The regression, stated as the property that was violated.

    One continuous drive, uploaded in batches, must produce exactly ONE distinct
    pseudonym — so it contributes 1 toward K=5, not 5.
    """

    def test_a_batched_trip_is_one_pseudonym(self):
        token = mint_client_token()
        batches = 5
        pseudonyms = {trip_pseudonym(token, SALT)[0] for _ in range(batches)}
        self.assertEqual(len(pseudonyms), 1,
                         "a batched trip still looks like several distinct trips")

    def test_five_real_trips_are_five_pseudonyms(self):
        pseudonyms = {trip_pseudonym(mint_client_token(), SALT)[0] for _ in range(5)}
        self.assertEqual(len(pseudonyms), 5)

    def test_a_trip_that_rotates_is_unlinkable_to_the_previous_one(self):
        first, _ = trip_pseudonym(mint_client_token(), SALT)
        second, _ = trip_pseudonym(mint_client_token(), SALT)
        self.assertNotEqual(first, second)


if __name__ == "__main__":
    unittest.main()
