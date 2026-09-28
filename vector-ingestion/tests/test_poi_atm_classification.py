"""A cash machine is not a cabin, and it is not the ministry it stands in.

From the Lusail drive (reproduced 2026-09-14): a driver searching "moci
lusail" for the Ministry of Commerce and Industry was offered, above the
ministry, the QNB ATM in its lobby. Three things in this pipeline put it
there, and all three are pinned below against the record as production
actually holds it:

* Overture files it under ``category="cabin"``, which this repo's family table
  reads as LODGING — the second most useful family there is (0.92) — so a cash
  machine scored 0.799 against the ministry's 0.7358, and the driver-facing
  category read "cabin".
* ``KNOWN_ALIASES`` attached the ministry's curated "MOCI" acronym to it,
  because its name contains "Ministry of Commerce". The acronym then resolved
  to the ATM exactly as strongly as to the institution it names.

All of this is DATA: the fix ships in a re-baked POI dataset, not in a
geocoder restart.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_ingestion.poi.families import (  # noqa: E402
    FINANCE, GOVERNMENT, LODGING, classify_osm, classify_overture,
    name_says_atm, refine_category,
)
from vector_ingestion.poi.pipeline import run_pipeline  # noqa: E402
from vector_ingestion.poi.score import base_quality, family_usefulness  # noqa: E402

ATM_NAME = "QNB ATM Ministry of Commerce & Industry Lusail"
MINISTRY_NAME = ("وزارة التجارة والصناعة Ministry of Commerce and Industry "
                 "of Qatar")

ATM_FEATURE = {
    "type": "Feature", "id": "65ce8389-5770-4ad1-898f-82613a72790f",
    "geometry": {"type": "Point", "coordinates": [51.52187, 25.39358]},
    "properties": {
        "kind": "poi", "name": ATM_NAME, "source": "overture",
        "category": "cabin", "confidence": 0.85, "locality": "Al Daayen",
        "address": ("Ministry of Commerce & Industry, lusail, Zone No 69, "
                    "Ghar Thuaileb, St No 305"),
    },
}

MINISTRY_FEATURE = {
    "type": "Feature", "id": "86c9f5a5-11d3-4b1f-8a63-5f4df81b84d4",
    "geometry": {"type": "Point", "coordinates": [51.52191, 25.39321]},
    "properties": {
        "kind": "poi", "name": MINISTRY_NAME, "source": "overture",
        "category": "central_government_office", "confidence": 0.6682,
        "locality": "الظعاين", "address": "lusail City",
    },
}


def _run(features):
    """Run the real pipeline over these Overture features.

    Returns ``(atm_props, ministry_props)``. The two are told apart by what
    they ARE rather than by an exact output name, because the display-name
    layer legitimately rewrites a jammed bilingual label into its English half
    plus an Arabic alias — which is a different concern from this one.
    """
    directory = tempfile.mkdtemp()
    places = os.path.join(directory, "places.geojson")
    out = os.path.join(directory, "canonical.geojson")
    with open(places, "w", encoding="utf-8") as fh:
        json.dump({"type": "FeatureCollection", "features": features}, fh)
    run_pipeline(None, places, out_canonical=out)
    with open(out, encoding="utf-8") as fh:
        doc = json.load(fh)
    props = [f["properties"] for f in doc["features"]]
    atm = [p for p in props if name_says_atm(p["name"])]
    ministry = [p for p in props
                if "ministry of commerce" in p["name"].casefold()
                and not name_says_atm(p["name"])]
    assert len(atm) == 1 and len(ministry) == 1, [p["name"] for p in props]
    return atm[0], ministry[0]


class AtmNameOverrideTest(unittest.TestCase):

    def test_a_name_that_says_atm_is_finance_whatever_the_category_says(self):
        self.assertEqual(classify_overture("cabin", ATM_NAME), FINANCE)
        self.assertEqual(classify_osm("cabin", ATM_NAME), FINANCE)

    def test_an_unnamed_cabin_is_still_lodging(self):
        # The override is a NAME rule, not a category deletion: Overture's
        # `cabin` is a real lodging category for the records that are one.
        self.assertEqual(classify_overture("cabin", "Zekreet Desert Cabin"),
                         LODGING)

    def test_atm_is_matched_as_a_word_not_a_substring(self):
        # "atm" sits inside plenty of names and transliterations. A substring
        # rule would refile every one of them as a cash machine.
        self.assertTrue(name_says_atm("Doha Bank ATM - C Ring Road"))
        self.assertTrue(name_says_atm("qnb atms lusail"))
        self.assertFalse(name_says_atm("Fatma Beauty Salon"))
        self.assertFalse(name_says_atm("Atmosphere Lounge"))
        self.assertFalse(name_says_atm("Batman Cafe"))

    def test_the_driver_is_shown_atm_and_not_cabin(self):
        self.assertEqual(refine_category("cabin", ATM_NAME), "atm")

    def test_a_category_already_filed_as_finance_is_left_alone(self):
        # A bank branch with an ATM in its name stays a bank.
        self.assertEqual(refine_category("bank", "QNB Bank ATM Lusail"), "bank")
        self.assertEqual(refine_category("atm", ATM_NAME), "atm")

    def test_a_record_with_no_category_is_not_given_an_invented_one(self):
        self.assertIsNone(refine_category(None, "Zekreet Desert Cabin"))


class AtmUsefulnessTest(unittest.TestCase):

    def test_an_atm_ranks_below_a_bank_branch(self):
        # FINANCE is the right family; an ATM is the fixture, not the branch.
        self.assertLess(family_usefulness(FINANCE, "atm"),
                        family_usefulness(FINANCE))

    def test_a_government_office_outscores_an_atm(self):
        # The heart of it: these are the two real records, scored.
        atm = base_quality(family=FINANCE, name=ATM_NAME, confidence=0.85,
                           has_address=True, category="atm")
        ministry = base_quality(family=GOVERNMENT, name=MINISTRY_NAME,
                                confidence=0.6682, has_address=True,
                                category="central_government_office")
        self.assertLess(atm, ministry, f"atm {atm} >= ministry {ministry}")

    def test_the_atm_is_still_a_findable_destination(self):
        # Never a deletion. A driver who searches for the ATM must find it.
        atm = base_quality(family=FINANCE, name=ATM_NAME, confidence=0.85,
                           has_address=True, category="atm")
        self.assertGreaterEqual(atm, 0.55)


class CuratedAliasTest(unittest.TestCase):

    def test_the_acronym_lands_only_on_the_institution(self):
        atm, ministry = _run([ATM_FEATURE, MINISTRY_FEATURE])
        self.assertIn("MOCI", ministry.get("alt_names") or [])
        self.assertNotIn("MOCI", atm.get("alt_names") or [],
                         "the ministry's acronym leaked onto the cash machine")

    def test_the_shipped_records_come_out_of_the_pipeline_corrected(self):
        atm, ministry = _run([ATM_FEATURE, MINISTRY_FEATURE])
        self.assertEqual(atm["poi_family"], FINANCE)
        self.assertEqual(atm["category"], "atm")
        self.assertEqual(atm["poi_class"], "atm")
        self.assertLess(atm["quality_score"], ministry["quality_score"])
        self.assertEqual(atm["usefulness"], "DESTINATION")

    def test_the_ministry_is_untouched(self):
        _atm, ministry = _run([ATM_FEATURE, MINISTRY_FEATURE])
        self.assertEqual(ministry["poi_family"], GOVERNMENT)
        self.assertEqual(ministry["category"], "central_government_office")
        self.assertEqual(ministry["quality_score"], 0.7358)


if __name__ == "__main__":
    unittest.main()
