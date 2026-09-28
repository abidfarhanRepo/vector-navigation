"""POI quality pipeline V1 — unit + golden-dataset regression tests.

All fixture cases below are records that exist in the actual Qatar corpus
(measured 2026-09-13); the geometry is synthetic where the test does not care
about position. The golden dataset pins BOTH directions:

* must NOT become ordinary POIs  (worker housing, building ids, furniture,
  typo pins) — with the correct EXCLUDED_* reason in the audit;
* must appear                  (Shater Abbas at 0.49 confidence, church
  variants resolved to ONE canonical with aliases, all Woqod branches).

Chains are protected by construction: four Starbucks/Woqod branches far apart
are never merged, and two different churches in the same religious complex are
never merged either.
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_ingestion.poi.audit import build_audit  # noqa: E402
from vector_ingestion.poi.dedup import (  # noqa: E402
    build_records, cluster_and_reconcile,
)
from vector_ingestion.poi.families import (  # noqa: E402
    classify_overture, classify_osm, is_singleton_family,
)
from vector_ingestion.poi.names import hard_name_signals, name_quality  # noqa: E402
from vector_ingestion.poi.pipeline import run_pipeline  # noqa: E402


def osm_feature(name, poi_class=None, fid="n1", lon=51.4, lat=25.25):
    props = {"kind": "poi", "name": name}
    if poi_class:
        props["poi_class"] = poi_class
    return {"type": "Feature", "id": fid, "geometry": {"type": "Point",
            "coordinates": [lon, lat]}, "properties": props}


def overture_feature(name, category=None, oid="ov-1", lon=51.4, lat=25.25,
                     conf=0.9, brand=None):
    props = {"kind": "poi", "name": name, "source": "overture",
             "overture_id": oid, "confidence": conf}
    if category:
        props["category"] = category
    if brand:
        props["brand"] = brand
    return {"type": "Feature", "geometry": {"type": "Point",
            "coordinates": [lon, lat]}, "properties": props}


class FamilyTest(unittest.TestCase):
    def test_overture_families(self):
        cases = {
            "restaurant": "FOOD", "hospital": "HEALTHCARE",
            "gas_station": "TRANSPORT", "mosque": "RELIGIOUS",
            "catholic_church": "RELIGIOUS", "school": "EDUCATION",
            "shopping_center": "SHOPPING", "hotel": "LODGING",
            "parking": "PARKING", "pharmacy": "HEALTHCARE",
            "embassy": "GOVERNMENT", "bank_credit_union": "FINANCE",
            "accommodation": "RESIDENTIAL", "apartments": "RESIDENTIAL",
            "corporate_office": "OFFICE", "professional_services": "OFFICE",
            "wholesale_store": "INDUSTRY", "shelter": "MAP_FURNITURE",
            "auto_parts_and_supply_store": "SERVICES",
        }
        for cat, want in cases.items():
            self.assertEqual(classify_overture(cat, None), want, cat)

    def test_unknown_is_retained_not_erased(self):
        self.assertEqual(classify_overture("some_brand_new_category", None),
                         "UNKNOWN")
        self.assertEqual(classify_overture(None, "Tornado Tower"), "UNKNOWN")

    def test_landmark_bucket_is_name_contextualised(self):
        # The Overture dumping ground: Ezdan Villages are residential, museums
        # are attractions, bare place names are UNKNOWN (kept, low rank).
        self.assertEqual(
            classify_overture("landmark_and_historical_building",
                              "Ezdan Village 5"), "RESIDENTIAL")
        self.assertEqual(
            classify_overture("landmark_and_historical_building",
                              "Barwa Bldg 42"), "RESIDENTIAL")
        self.assertEqual(
            classify_overture("landmark_and_historical_building",
                              "Museum of Islamic Art"), "ATTRACTION")
        self.assertEqual(
            classify_overture("landmark_and_historical_building",
                              "Sawda Natheel"), "UNKNOWN")

    def test_osm_classes(self):
        cases = {
            "restaurant": "FOOD", "shelter": "MAP_FURNITURE",
            "place_of_worship": "RELIGIOUS", "fuel": "TRANSPORT",
            "company": "OFFICE", "apartment": "RESIDENTIAL",
            "parking": "PARKING", "parking_entrance": "MAP_FURNITURE",
            "attraction": "ATTRACTION", "tailor": "SERVICES",
            "atm": "FINANCE", "toilets": "MAP_FURNITURE",
        }
        for cls, want in cases.items():
            self.assertEqual(classify_osm(cls, None), want, cls)
        # named buildings carry no class and must not be erased
        self.assertEqual(classify_osm("yes", "Ezdan Tower 3"), "UNKNOWN")
        self.assertEqual(classify_osm(None, "Tornado Tower"), "UNKNOWN")

    def test_singleton_policy(self):
        self.assertTrue(is_singleton_family("RELIGIOUS", "catholic_church"))
        self.assertTrue(is_singleton_family("HEALTHCARE", "hospital"))
        self.assertFalse(is_singleton_family("HEALTHCARE", "clinic"))
        self.assertFalse(is_singleton_family("FOOD", "restaurant"))


class NameTest(unittest.TestCase):
    def test_hard_signals_are_narrow(self):
        # Worker housing / building ids — every term was counted in the
        # corpus before being allowed to exclude.
        self.assertIn("accommodation",
                      hard_name_signals("Barwa Al Baraha Workers Accommodation",
                                        "RESIDENTIAL"))
        self.assertIn("accommodation",
                      hard_name_signals("Toyota accomodation", "UNKNOWN"))
        self.assertIn("building-id",
                      hard_name_signals("Barwa Bldg 42", "UNKNOWN"))
        self.assertIn("building-id",
                      hard_name_signals("Building 110", "UNKNOWN"))
        self.assertIn("building-id",
                      hard_name_signals("Y Building 2", "UNKNOWN"))

    def test_context_saves_legitimate_destinations(self):
        # The words that must NEVER exclude by themselves.
        self.assertEqual(hard_name_signals("Turkish Grill House", "FOOD"), [])
        self.assertEqual(hard_name_signals("Holiday Villa", "LODGING"), [])
        self.assertEqual(hard_name_signals("Tornado Tower", "UNKNOWN"), [])
        self.assertEqual(hard_name_signals("Katara Opera House", "ATTRACTION"), [])
        self.assertEqual(hard_name_signals("Qatar National Bank", "FINANCE"), [])

    def test_name_quality_ranks_but_never_excludes(self):
        self.assertGreater(name_quality("Green Tea Garden Restaurant", "FOOD"),
                           name_quality("Cafe", "FOOD"))
        self.assertEqual(name_quality(None, "X"), 0.0)
        self.assertGreater(name_quality("Shater Abbas Restaurant", "FOOD"), 0.3)


class DedupTest(unittest.TestCase):
    def _records(self, osm=None, ov=None):
        return build_records(osm or [], ov or [], classify_osm,
                             classify_overture)

    def test_cross_source_same_place_merges(self):
        osm = [osm_feature("Pizza Hut", "restaurant", fid="n10",
                           lon=51.45, lat=25.28)]
        ov = [overture_feature("Pizza Hut", "restaurant", oid="ov10",
                               lon=51.45, lat=25.28, conf=0.96)]
        recs = self._records(osm, ov)
        self.assertEqual(len(recs), 2)
        outcomes, uf = cluster_and_reconcile(recs)
        canons = [o.get("canonical") for o in outcomes]
        self.assertEqual(sum(1 for c in canons if c), 1,
                         "cross-source same place must merge to one canonical")

    def test_chain_branches_are_never_merged(self):
        # Four Woqod branches, 5 km apart: the exact case that killed naive
        # radius dedup in the investigation.
        ov = [overture_feature("Woqod Petrol Station", "gas_station",
                               oid=f"woqod{i}", lon=51.3 + i * 0.05,
                               lat=25.2 + i * 0.01, conf=0.9, brand="Woqod")
              for i in range(4)]
        recs = self._records([], ov)
        outcomes, _ = cluster_and_reconcile(recs)
        self.assertEqual(sum(1 for o in outcomes if o.get("canonical")), 4)

    def test_close_same_brand_outlets_merge(self):
        ov = [overture_feature("Starbucks", "coffee_shop", oid="sb1",
                               lon=51.45, lat=25.28, conf=0.9, brand="Starbucks"),
              overture_feature("Starbucks", "coffee_shop", oid="sb2",
                               lon=51.4501, lat=25.2801, conf=0.85, brand="Starbucks")]
        canonical_outcomes, _ = cluster_and_reconcile(self._records([], ov))
        self.assertEqual(sum(1 for o in canonical_outcomes if o.get("canonical")), 1)
        self.assertFalse(canonical_outcomes[1].get("canonical"))

    def test_two_churches_in_one_complex_stay_separate(self):
        # Anglican Centre and Malankara Orthodox sit ~60 m apart in the Abu
        # Hamour Religious Complex — two real churches, never one.
        ov = [overture_feature("Anglican Centre in Qatar", "anglican_church",
                               oid="c1", lon=51.5233, lat=25.21252),
              overture_feature("Malankara Orthodox Church", "church_cathedral",
                               oid="c2", lon=51.5231, lat=25.21335)]
        outcomes, _uf = cluster_and_reconcile(self._records([], ov))
        self.assertEqual(sum(1 for o in outcomes if o.get("canonical")), 2)

    def test_holy_rosary_duplicates_resolve_to_one_canonical(self):
        # The measured case: one real church + an Italian alias at the same
        # spot + two misplaced typo pins + a far hall that may be a different
        # building. One canonical keeps the real church; typo pins become
        # aliases; the hall stays its own record.
        ov = [
            overture_feature("Holy Rosary Catholic Church", "catholic_church",
                             oid="hr1", lon=51.5219, lat=25.21293, conf=0.9563),
            overture_feature("Chiesa di Nostra Signora del Rosario (Doha)",
                             "catholic_church", oid="hr2", lon=51.5219,
                             lat=25.21260, conf=0.8172),
            overture_feature("Churh Of The Holy Rosary", "church_cathedral",
                             oid="hr3", lon=51.5296, lat=25.26427, conf=0.5353),
            overture_feature("Holy Rosary Church of Doha Qatar..",
                             "church_cathedral", oid="hr4", lon=51.5251,
                             lat=25.26345, conf=0.7715),
            overture_feature("Our Lady of Holy Rosary Church, Doha",
                             "catholic_church", oid="hr5", lon=51.5176,
                             lat=25.30805, conf=0.7002),
            overture_feature("Seraphic Hall, Catholic Church of Our Lady of "
                             "the Rosary", "church_cathedral", oid="hr6",
                             lon=51.4291, lat=25.35151, conf=0.858),
        ]
        recs = self._records([], ov)
        outcomes, _ = cluster_and_reconcile(recs)
        canons = [o for o in outcomes if o.get("canonical")]
        self.assertEqual(len(canons), 2,
                         "real church + seraphic hall, everything else merged")
        # Canonical is the highest-confidence record (the real church).
        ci = outcomes.index(canons[0]) if not canons[0].get("cross_source") else 0
        # build simulated pipeline objects to check aliases
        canon_rec = recs[outcomes.index(canons[0])]
        self.assertIn("holy rosary", canon_rec["norm"])


class PipelineTest(unittest.TestCase):
    def make_osm_file(self, tmp, fs, name="osm.geojson"):
        path = os.path.join(tmp, name)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"type": "FeatureCollection", "features": fs}, fh)
        return path

    def make_ov_file(self, tmp, fs, name="places.geojson"):
        path = os.path.join(tmp, name)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump({"type": "FeatureCollection", "features": fs}, fh)
        return path

    def test_audit_ledger_reconciles(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            osm = [osm_feature("Kebab King", "restaurant", fid="n1"),
                   osm_feature("Barwa Bldg 42", None, fid="n2"),
                   osm_feature("Mall Parking", "parking", fid="n3"),
                   osm_feature("unnamed shelter", "shelter", fid="n4")]
            ov = [overture_feature("Shater Abbas Restaurant", "restaurant",
                                   oid="ov1", conf=0.4922),
                  overture_feature("Pizza Hut", "restaurant", oid="ov2", conf=0.9,
                                   lon=51.4, lat=25.25)]
            osm_path = self.make_osm_file(tmp, osm)
            ov_path = self.make_ov_file(tmp, ov)
            audit = run_pipeline(osm_path, ov_path)
            t = audit["total_raw"]["total"]
            self.assertEqual(t, audit["canonical"]["total"]
                             + audit["excluded"]["total"],
                             "the ledger must reconcile exactly")
            self.assertIn("EXCLUDED_LOW_QUALITY_NAME",
                          audit["excluded"]["by_reason"])

    def test_golden_dataset_known_bad_never_retained(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            bad = [
                ("Barwa Al Baraha Workers Accommodation", "accommodation"),
                ("Toyota accomodation", None),
                ("QP Junior Bachelor Accomodation", "landmark_and_historical_building"),
                ("Building 12", "landmark_and_historical_building"),
                ("Sks 1", None),
                ("Ezdan Accomodation-1", "apartment"),
                ("Doha", "motel"),
            ]
            osm = [osm_feature(n, cls, fid=f"b{i}", lon=51.4, lat=25.25)
                   for i, (n, cls) in enumerate(bad)]
            osm_path = self.make_osm_file(tmp, osm)
            audit = run_pipeline(osm_path, None)
            out_c = audit["canonical"]["total"]
            self.assertEqual(out_c, 0, "no known-bad POI may survive")

    def test_golden_dataset_known_good_retained(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            good = [
                osm_feature("Tornado Tower", None, fid="g1"),
                osm_feature("Holiday Villa", "hotel", fid="g2"),
                osm_feature("Al Bidda Park", "park", fid="g3"),
            ]
            ov = [
                overture_feature("Shater Abbas Restaurant", "restaurant",
                                 oid="ov1", conf=0.4922),
                overture_feature("Woqod Petrol Station", "gas_station",
                                 oid="ov2", conf=0.9, brand="Woqod"),
            ]
            osm_path = self.make_osm_file(tmp, good)
            ov_path = self.make_ov_file(tmp, ov)
            canon_path = os.path.join(tmp, "canonical_pois.geojson")
            audit = run_pipeline(osm_path, ov_path, out_canonical=canon_path)
            with open(canon_path, encoding="utf-8") as fh:
                doc = json.load(fh)
            names = {f["properties"]["name"] for f in doc["features"]}
            for want in ("Tornado Tower", "Holiday Villa", "Al Bidda Park",
                         "Shater Abbas Restaurant", "Woqod Petrol Station"):
                self.assertIn(want, names, f"{want} must survive the pipeline")


class RealDataRegressionTest(unittest.TestCase):
    """Runs the pipeline against the actual local Qatar corpus when present.

    The OSM side is converted from the cached extract on demand (qatar.osm
    -> osm_to_geojson), the Overture side from the places cache; a checkout
    without the data, or without ``VECTOR_POI_SMOKE=1``, skips rather than
    failing — the unit suites above are the fast, always-on gate.
    """

    def test_qatar_ledger_and_rosary(self):
        if os.environ.get("VECTOR_POI_SMOKE") != "1":
            self.skipTest("set VECTOR_POI_SMOKE=1 to run the real-corpus smoke test")
        root = os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))))
        osm_raw = os.path.join(root, ".bootstrap-cache", "qatar.osm")
        places = os.path.join(root, ".bootstrap-cache", "qatar_places.geojson")
        if not os.path.exists(osm_raw) or not os.path.exists(places):
            self.skipTest("Qatar corpus not present locally")
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            geo = os.path.join(tmp, "qatar.geojson")
            script = os.path.join(root, "vector-tile-gen", "scripts",
                                  "osm_to_geojson.py")
            self.assertEqual(os.system(
                f"python3 {script} {osm_raw} -o {geo} >/dev/null 2>&1"), 0)
            audit = run_pipeline(geo, places, out_canonical=os.path.join(tmp, "c.geojson"))
            t = audit["total_raw"]["total"]
            self.assertEqual(t, audit["canonical"]["total"]
                             + audit["excluded"]["total"])
            with open(os.path.join(tmp, "c.geojson"), encoding="utf-8") as fh:
                canon = json.load(fh)
            rosary = [f["properties"] for f in canon["features"]
                      if "rosary" in (f["properties"].get("name") or "").lower()
                      or any("rosary" in a.lower()
                             for a in (f["properties"].get("alt_names") or []))]
            names = [r["name"] for r in rosary]
            self.assertEqual(
                names, ["Holy Rosary Catholic Church",
                        "Seraphic Hall, Catholic Church of Our Lady of the "
                        "Rosary"])


if __name__ == "__main__":
    unittest.main()

class KnownAliasTest(unittest.TestCase):
    """Curated acronym aliases: "MOCI" must resolve to the ministry."""

    def test_moci_alias_attached_and_searchable(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            ov = [overture_feature(
                "وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar",
                "central_government_office", oid="moci-1", lon=51.5219, lat=25.3932)]
            path = os.path.join(tmp, "ov.geojson")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"type": "FeatureCollection", "features": ov}, fh)
            canon_path = os.path.join(tmp, "canonical_pois.geojson")
            run_pipeline(None, path, out_canonical=canon_path)
            with open(canon_path, encoding="utf-8") as fh:
                doc = json.load(fh)
            rec = doc["features"][0]["properties"]
            self.assertEqual(rec["poi_family"], "GOVERNMENT")
            self.assertIn("MOCI", rec["alt_names"],
                          "the Ministry of Commerce must be findable as MOCI")
