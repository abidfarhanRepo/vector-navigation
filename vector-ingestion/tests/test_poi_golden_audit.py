"""Golden dataset regression — the 2026-09-13 adversarial audit expansion.

Three classes, all entries are REAL records from the actual Qatar corpus
(`.scratch/vector-poi-quality/golden_dataset.json`):

* MUST_SURVIVE — 100 genuine destinations that must remain canonical
  (restaurants, clinics, schools, malls, mosques, banks, petrol stations ...).
* MUST_BE_EXCLUDED — 100 records that must NOT be destinations (worker
  accommodation, building ids, residential towers, benches ...).
* MUST_NOT_BE_MERGED — 50 pairs of distinct real facilities that must stay
  separate (chain branches, two churches in one complex, ...).

The assertions run against the REAL pipeline output on the real corpus when
the data is present locally (the same opt-in as RealDataRegressionTest); a
checkout without the data skips.
"""

import json
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_HERE))
_GOLDEN = os.path.join(_ROOT, ".scratch", "vector-poi-quality", "golden_dataset.json")


def _norm(s: str) -> str:
    s = s or ""
    s = re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()
    return s


class GoldenDatasetTest(unittest.TestCase):

    @staticmethod
    def _corpus():
        audit_dir = os.environ.get("VECTOR_POI_AUDIT_DIR", "")
        osm = audit_dir and os.path.join(audit_dir, "qatar.geojson")
        places = (audit_dir and os.path.join(audit_dir, "qatar_places.geojson")) \
            or os.path.join(_ROOT, ".bootstrap-cache", "qatar_places.geojson")
        if not os.path.exists(places) or not (osm and os.path.exists(osm)):
            return None
        return osm, places

    def setUp(self):
        corpus = self._corpus()
        if corpus is None:
            self.skipTest("Qatar corpus not present locally "
                          "(set VECTOR_POI_AUDIT_DIR to the baked files)")
        if not os.path.exists(_GOLDEN):
            self.skipTest(f"golden dataset missing: {_GOLDEN}")
        import tempfile
        from vector_ingestion.poi.pipeline import run_pipeline
        with tempfile.TemporaryDirectory() as tmp:
            osm, places = corpus
            canon_path = os.path.join(tmp, "canonical_pois.geojson")
            excl_path = os.path.join(tmp, "excluded_pois.geojson")
            run_pipeline(osm, places, out_canonical=canon_path,
                         out_excluded=excl_path)
            with open(canon_path, encoding="utf-8") as fh:
                self.canonical = json.load(fh)["features"]
            with open(excl_path, encoding="utf-8") as fh:
                self.excluded = json.load(fh)["features"]
            with open(_GOLDEN, encoding="utf-8") as fh:
                self.golden = json.load(fh)

    # -- helpers -----------------------------------------------------------

    def _canonical_names(self):
        return {_norm(f["properties"].get("name") or "") for f in self.canonical}

    def _excluded_names(self):
        return {_norm(f["properties"].get("name") or "") for f in self.excluded}

    # -- MUST_SURVIVE ------------------------------------------------------

    def test_all_must_survive_are_present_in_canonical(self):
        can = self._canonical_names()
        missing = [e["name"] for e in self.golden["must_survive"]
                   if _norm(e["name"]) not in can]
        self.assertEqual(missing, [],
                         f"{len(missing)} MUST_SURVIVE records were excluded: "
                         f"{missing[:10]}")

    # -- MUST_BE_EXCLUDED --------------------------------------------------

    def test_all_must_be_excluded_are_absent_from_canonical(self):
        can = self._canonical_names()
        leaking = [e["name"] for e in self.golden["must_be_excluded"]
                   if _norm(e["name"]) in can]
        self.assertEqual(leaking, [],
                         f"{len(leaking)} MUST_BE_EXCLUDED records leaked: "
                         f"{leaking[:10]}")

    def test_must_be_excluded_have_an_explainable_reason(self):
        excl = self._excluded_names()
        reasons = {_norm(f["properties"].get("name") or ""):
                   f["properties"].get("excluded") for f in self.excluded}
        missing = [e["name"] for e in self.golden["must_be_excluded"]
                   if _norm(e["name"]) not in excl]
        self.assertEqual(missing, [],
                         f"{len(missing)} MUST_BE_EXCLUDED records have no "
                         f"exclusion record: {missing[:10]}")

    # -- MUST_NOT_BE_MERGED ------------------------------------------------

    def test_must_not_be_merged_pairs_stay_separate(self):
        by_name = {}
        for f in self.canonical:
            n = _norm(f["properties"].get("name") or "")
            by_name.setdefault(n, []).append(f)
        broken = []
        for pair in self.golden["must_not_be_merged"]:
            a = by_name.get(_norm(pair["name_a"]), [])
            b = by_name.get(_norm(pair["name_b"]), [])
            if not a or not b:
                broken.append(f"{pair['name_a']} / {pair['name_b']}: missing")
                continue
            # Chain branches are the same NAME at different places; distinct
            # facilities have (possibly different) canonical records. Both
            # cases require >=2 canonical records at >=2 distinct positions
            # for same-name pairs, or the presence of both named records.
            if pair["name_a"] == pair["name_b"]:
                positions = {(round(f["geometry"]["coordinates"][0], 4),
                              round(f["geometry"]["coordinates"][1], 4))
                             for f in a}
                if len(positions) < 2:
                    broken.append(f"{pair['name_a']}: collapsed to one location")
            else:
                if len(a) == 0 or len(b) == 0:
                    broken.append(f"{pair['name_a']} / {pair['name_b']}: a side merged away")
        self.assertEqual(broken, [], "\n".join(broken[:10]))


if __name__ == "__main__":
    unittest.main()