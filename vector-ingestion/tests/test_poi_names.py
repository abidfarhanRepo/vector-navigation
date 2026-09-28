"""POI display-name quality — regression tests.

Every case below is a real string from the deployed 26,096-record canonical
corpus (``qatar_places.geojson``, pulled from the ``vector-geocoder``
container on 2026-09-14), or a measured corpus-wide count. They pin the two
things a driver actually experiences:

* the label is **readable** — one language leads, no jammed bilingual string,
  no invisible bidi marks, no phone number, no shouting, no doubled spaces;
* nothing that used to be **findable** stopped being findable — every name a
  record is rewritten away from survives as an alias, and the safety set
  (Woqod, Starbucks, Shater Abbas, the Rosary church cluster) is unharmed.

The corpus-wide defect these tests exist for, and which all of them failed
before the fix:

===================================  =========  ========
defect                                  before     after
===================================  =========  ========
jammed bilingual display name              790        12
   ... with Arabic leading under en        204         4
records whose normalize_name() == ''     1,687        38
Arabic-only records in DESTINATION         376     1,037
doubled whitespace                         107         0
ALL-CAPS multiword names                   100         2
phone numbers inside a name                 10         0
invisible bidi / zero-width marks            4         0
identical classless "Administration"        26         1
===================================  =========  ========
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_ingestion.poi.display_names import (  # noqa: E402
    clean_text, is_classless_anonymous, repair_feature_names, resolve_names,
    repeated_label_groups, split_bilingual, strip_marketing_tail,
)
from vector_ingestion.poi.names import (  # noqa: E402
    name_quality, normalize_name, short_name, tokens,
)
from vector_ingestion.poi.pipeline import run_pipeline  # noqa: E402


def osm_feature(name, poi_class=None, fid="n1", lon=51.4, lat=25.25, **extra):
    props = {"kind": "poi", "name": name}
    if poi_class:
        props["poi_class"] = poi_class
    props.update(extra)
    return {"type": "Feature", "id": fid,
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": props}


def overture_feature(name, category=None, oid="o1", lon=51.4, lat=25.25,
                     confidence=0.8, **extra):
    props = {"name": name, "overture_id": oid, "confidence": confidence}
    if category:
        props["category"] = category
    props.update(extra)
    return {"type": "Feature", "id": oid,
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": props}


# ---------------------------------------------------------------------------
# 1. normalize_name must understand Arabic
# ---------------------------------------------------------------------------

class ArabicNormalisationTest(unittest.TestCase):
    """1,687 of 26,096 deployed records (6.5%) normalised to "".

    The consequence was not cosmetic: ``name_quality`` returned 0.0 for every
    Arabic name, so Arabic-named destinations scored a mean quality of 0.465
    against 0.670 for Latin-named ones, and only 22.5% of them reached the
    DESTINATION bucket against 82.9% of the Latin ones.
    """

    def test_arabic_name_normalises_to_something(self):
        for name in ("مطعم شاطر عباس", "ستاربكس", "وزارة التجارة والصناعة",
                     "مسجد الدوحة", "سوق واقف"):
            self.assertNotEqual(normalize_name(name), "",
                                f"{name!r} normalised away entirely")

    def test_arabic_name_scores_like_a_latin_one(self):
        # "Shater Abbas Restaurant" and its Arabic spelling name the same
        # restaurant and must be equally worth showing.
        self.assertGreater(name_quality("مطعم شاطر عباس", "FOOD"), 0.9)
        self.assertGreater(name_quality("وزارة التجارة والصناعة", "GOVERNMENT"), 0.9)

    def test_arabic_multiword_name_is_not_a_short_name(self):
        self.assertFalse(short_name("مطعم شاطر عباس"))
        self.assertFalse(short_name("وزارة التجارة والصناعة"))

    def test_arabic_orthographic_variants_fold_together(self):
        # teh marbuta / alef maksura / hamza-carrying alefs / tatweel /
        # harakat / Arabic-Indic digits are all spelling noise.
        self.assertEqual(normalize_name("مسجد الدوحة"),
                         normalize_name("مَسْجِدُ الدَّوْحَه"))
        self.assertEqual(normalize_name("مسجـــد الدوحة"),
                         normalize_name("مسجد الدوحة"))
        self.assertEqual(normalize_name("احمد"), normalize_name("أحمد"))
        self.assertEqual(normalize_name("مبني ٣١٧"), normalize_name("مبنى 317"))

    def test_arabic_generic_class_word_alone_scores_low(self):
        # The Arabic twin of the bare "Mosque"/"Pharmacy" label.
        self.assertLess(name_quality("مسجد", "RELIGIOUS"), 0.3)
        self.assertLess(name_quality("صيدلية", "HEALTHCARE"), 0.3)
        # ... but a real name containing the class word is fine.
        self.assertGreater(name_quality("مسجد ناصر بن راشد المسند", "RELIGIOUS"), 0.9)

    def test_arabic_geographic_filler_is_a_stopword(self):
        self.assertEqual(tokens("مسجد الدوحة"), ("مسجد",))

    def test_latin_normalisation_is_unchanged(self):
        self.assertEqual(normalize_name("Shater Abbas Restaurant"),
                         "shater abbas restaurant")
        self.assertEqual(normalize_name("Holy Rosary Catholic Church"),
                         "holy rosary catholic church")
        self.assertEqual(normalize_name("Café Lëon"), "cafe leon")


# ---------------------------------------------------------------------------
# 2. bilingual labels
# ---------------------------------------------------------------------------

class BilingualSplitTest(unittest.TestCase):
    """790 deployed records jam both languages into ``name``; 204 lead in
    Arabic, which is what the driver saw with ``lang=en``."""

    CASES = [
        # (raw, english, arabic) — all real corpus rows
        ("وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar",
         "Ministry of Commerce and Industry of Qatar", "وزارة التجارة والصناعة"),
        ("Al Meera | الميرة", "Al Meera", "الميرة"),
        ("حلويات العكر | Al Aker Sweets", "Al Aker Sweets", "حلويات العكر"),
        ("Sheikh Al Burger - شيخ البرجر", "Sheikh Al Burger", "شيخ البرجر"),
        ("Läderach (لاديراخ)", "Läderach", "لاديراخ"),
        ("North Feild Petrol Stationمحطة بترول حقل الشمال",
         "North Feild Petrol Station", "محطة بترول حقل الشمال"),
        ("مطعم المنقل AlMankal Restaurant", "AlMankal Restaurant", "مطعم المنقل"),
        ("مواقف المرخية | Markiya Parking", "Markiya Parking", "مواقف المرخية"),
        ("QIB - Airport Branch / مصرف قطر الاسلامي - فرع المطار",
         "QIB - Airport Branch", "مصرف قطر الاسلامي - فرع المطار"),
    ]

    def test_split_recovers_both_languages(self):
        for raw, en, ar in self.CASES:
            got_en, got_ar = split_bilingual(raw)
            self.assertEqual(got_en, en, f"english side of {raw!r}")
            self.assertEqual(got_ar, ar, f"arabic side of {raw!r}")

    def test_english_leads_the_display_name(self):
        for raw, en, _ar in self.CASES:
            self.assertEqual(resolve_names(raw).name, en)

    def test_the_original_label_survives_as_an_alias(self):
        # Arabic search for the ministry must still reach it.
        choice = resolve_names(
            "وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar")
        self.assertIn("وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar",
                      choice.aliases)
        self.assertEqual(choice.name_ar, "وزارة التجارة والصناعة")

    def test_interleaved_label_is_left_alone(self):
        # Two Arabic runs and two Latin runs: any split would invent a name.
        raw = "Almandarin المندرين سوق واقف souq waqif"
        self.assertEqual(split_bilingual(raw), (None, None))
        self.assertEqual(resolve_names(raw).name, raw)

    def test_arabic_name_with_english_name_en_leads_in_english(self):
        # The OSM shape: 480 deployed records carry name=Arabic, name:en=Latin.
        choice = resolve_names("سوق واقف", "Souq Waqif")
        self.assertEqual(choice.name, "Souq Waqif")
        self.assertEqual(choice.name_ar, "سوق واقف")
        self.assertIn("سوق واقف", choice.aliases)

    def test_a_weak_english_stub_never_replaces_a_good_arabic_name(self):
        # Measured regression: two records whose name:en was a two-letter stub
        # ("إل بي" -> "lp", "مي" -> "Me") lost a usable name and were then
        # dropped outright by the short-id rule. A language swap must never
        # make the record worse.
        self.assertEqual(resolve_names("إل بي", "lp").name, "إل بي")
        self.assertEqual(resolve_names("مي", "Me").name, "مي")

    def test_monolingual_names_are_untouched(self):
        for n in ("Shater Abbas Restaurant", "Starbucks", "Woqod",
                  "Holy Rosary Catholic Church", "ستاربكس"):
            self.assertEqual(resolve_names(n).name, n)


# ---------------------------------------------------------------------------
# 3. cosmetic repair
# ---------------------------------------------------------------------------

class CleanTextTest(unittest.TestCase):

    def test_doubled_whitespace_collapses(self):
        self.assertEqual(clean_text("Souq Al Baladi  Butchery"),
                         "Souq Al Baladi Butchery")
        self.assertEqual(clean_text("المندرين   Al-Mandarin"), "المندرين Al-Mandarin")

    def test_invisible_bidi_and_zero_width_marks_are_removed(self):
        self.assertEqual(clean_text("Care n Cure pharmacy صيدلية‎"),
                         "Care n Cure pharmacy صيدلية")
        self.assertEqual(
            clean_text("‏Benaya Marble & Granite Factory"),
            "Benaya Marble & Granite Factory")
        self.assertEqual(clean_text("Specialized Qatar Windows‎"),
                         "Specialized Qatar Windows")

    def test_compatibility_forms_become_plain_text(self):
        self.assertEqual(clean_text("𝗥𝗲𝗽𝗮𝗶𝗿 & 𝗥𝗲𝘀𝗮𝗹𝗲 𝗤𝗮𝘁𝗮𝗿-"), "Repair & Resale Qatar")

    def test_phone_numbers_are_cut_out_of_names(self):
        self.assertEqual(clean_text("Mykco Office 44980300"), "Mykco Office")
        self.assertEqual(clean_text("Mobile Tire Workshop Qatar.Call 50621568"),
                         "Mobile Tire Workshop Qatar")
        self.assertEqual(
            clean_text("Washing Machine and AC Repair Doha Qatar "
                       "Please Call me Whats app 30278195"),
            "Washing Machine and AC Repair Doha Qatar")

    def test_a_number_that_is_not_a_phone_number_stays(self):
        for n in ("BFQ 974", "Lusail Marina 19", "Katara - Building 19",
                  "Ashghal Tower 3", "Al Furjan Markets 13"):
            self.assertEqual(clean_text(n), n)

    def test_stray_edge_punctuation_and_doubled_dots(self):
        self.assertEqual(clean_text("Mesaieed Sealine Beach.."),
                         "Mesaieed Sealine Beach")
        self.assertEqual(clean_text("Al Attiyah Mosque,"), "Al Attiyah Mosque")
        self.assertEqual(clean_text("Qatar Professional Movers,"),
                         "Qatar Professional Movers")

    def test_shouted_multiword_names_are_recased(self):
        self.assertEqual(clean_text("DOHA SC FESTIVAL CITY"), "Doha SC Festival City")
        self.assertEqual(clean_text("HAMAD INTERNATIONAL AIRPORT"),
                         "Hamad International Airport")
        self.assertEqual(clean_text("GWC WAREHOUSE"), "GWC Warehouse")

    def test_deliberate_branding_is_not_recased(self):
        # One word, or too short to be a sentence: leave the brand alone.
        for n in ("BVLGARI", "PROVOK", "VIP GYM", "FISH WORLD", "KFC", "QNB"):
            self.assertEqual(clean_text(n), n)

    def test_underscores_become_spaces(self):
        self.assertEqual(clean_text("Chocoloco_qr"), "Chocoloco qr")


class MarketingTailTest(unittest.TestCase):
    """80 deployed records end in an advertising geography tail."""

    def test_tail_is_trimmed_when_a_real_name_remains(self):
        self.assertEqual(strip_marketing_tail("Interior Design Company in Qatar"),
                         "Interior Design Company")
        self.assertEqual(strip_marketing_tail("Ukrainian Embassy in Qatar"),
                         "Ukrainian Embassy")
        self.assertEqual(
            strip_marketing_tail("Plastic surgery in Qatar Doha Dubai Sharjah Ajman"),
            "Plastic surgery")

    def test_tail_is_kept_when_nothing_would_be_left(self):
        # "Yoga" alone names no place a driver can drive to.
        self.assertEqual(strip_marketing_tail("Yoga in Doha"), "Yoga in Doha")

    def test_a_name_that_merely_contains_qatar_is_untouched(self):
        for n in ("Community College of Qatar", "Qatar Foundation",
                  "Mada Center Qatar", "University of Doha for Science"):
            self.assertEqual(strip_marketing_tail(n), n)


# ---------------------------------------------------------------------------
# 4. repeated classless labels ("five Lusail polygons")
# ---------------------------------------------------------------------------

class RepeatedClasslessLabelTest(unittest.TestCase):
    """26 identical "Administration" rows answer no search and label nothing.

    Deployed counts among classless anonymous records: Administration 26,
    Mechanical Draft Cooling 14, Hangar 6, Barwa Commercial 5, District
    Cooling 5, Ezdan Villa 5.
    """

    @staticmethod
    def _rec(name, **kw):
        rec = {"name": name, "family": "UNKNOWN", "raw_category": None,
               "address": None, "locality": None, "brand": None}
        rec.update(kw)
        return rec

    def test_a_classed_record_is_never_a_repeated_label(self):
        self.assertFalse(is_classless_anonymous(
            self._rec("Starbucks", family="FOOD", raw_category="coffee_shop")))
        self.assertFalse(is_classless_anonymous(
            self._rec("Woqod", family="TRANSPORT", raw_category="gas_station")))
        self.assertFalse(is_classless_anonymous(
            self._rec("Administration", locality="Al Sadd")))

    def test_five_identical_classless_labels_form_one_group(self):
        recs = [self._rec("Administration") for _ in range(26)]
        groups = repeated_label_groups(recs)
        self.assertEqual(list(groups), ["administration"])
        self.assertEqual(len(groups["administration"]), 26)

    def test_four_copies_are_below_the_bar(self):
        self.assertEqual(repeated_label_groups([self._rec("Hangar")] * 4), {})

    def test_a_named_chain_can_never_qualify(self):
        recs = [self._rec("Starbucks", family="FOOD",
                          raw_category="coffee_shop") for _ in range(93)]
        self.assertEqual(repeated_label_groups(recs), {})

    def test_pipeline_collapses_them_to_one_and_keeps_the_ledger(self):
        feats = [osm_feature("Administration", fid=f"n{i}",
                             lon=51.40 + i * 0.02, lat=25.25 + i * 0.02)
                 for i in range(8)]
        out = run_pipeline_to_dicts(feats, [])
        survivors = [f for f in out["canonical"]
                     if f["properties"]["name"] == "Administration"]
        self.assertEqual(len(survivors), 1)
        collapsed = [f for f in out["excluded"]
                     if "repeated classless label"
                     in (f["properties"].get("exclusion_detail") or "")]
        self.assertEqual(len(collapsed), 7)
        # a collapse, not a deletion: every copy is still in the ledger
        self.assertEqual(out["audit"]["excluded"]["by_reason"]["EXCLUDED_DUPLICATE"], 7)


# ---------------------------------------------------------------------------
# 5. end-to-end through the pipeline, incl. the safety set
# ---------------------------------------------------------------------------

def run_pipeline_to_dicts(osm_feats, overture_feats):
    """Run the real pipeline over two in-memory feature lists."""
    import json
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        osm_p = os.path.join(tmp, "osm.geojson")
        ov_p = os.path.join(tmp, "ov.geojson")
        for path, feats in ((osm_p, osm_feats), (ov_p, overture_feats)):
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"type": "FeatureCollection", "features": feats}, fh)
        canon_p = os.path.join(tmp, "c.geojson")
        excl_p = os.path.join(tmp, "x.geojson")
        audit = run_pipeline(osm_p, ov_p, out_canonical=canon_p,
                             out_excluded=excl_p)
        with open(canon_p, encoding="utf-8") as fh:
            canonical = json.load(fh)["features"]
        with open(excl_p, encoding="utf-8") as fh:
            excluded = json.load(fh)["features"]
    return {"canonical": canonical, "excluded": excluded, "audit": audit}


class PipelineNameRepairTest(unittest.TestCase):

    def test_the_moci_lusail_label_leads_in_english(self):
        """The exact defect from the 2026-09-14 drive: with ``lang=en`` the
        ministry still rendered as "وزارة التجارة والصناعة Ministry of ..."."""
        feats = [overture_feature(
            "وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar",
            category="central_government_office", oid="o-moci",
            lon=51.4900, lat=25.4200)]
        out = run_pipeline_to_dicts([], feats)
        rows = [f for f in out["canonical"]
                if "Ministry of Commerce" in f["properties"]["name"]]
        self.assertEqual(len(rows), 1)
        props = rows[0]["properties"]
        self.assertEqual(props["name"],
                         "Ministry of Commerce and Industry of Qatar")
        self.assertEqual(props["name:en"],
                         "Ministry of Commerce and Industry of Qatar")
        self.assertEqual(props["name:ar"], "وزارة التجارة والصناعة")
        # Arabic search must still reach it.
        self.assertIn("وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar",
                      props["alt_names"])
        # and the curated acronym is still attached
        self.assertIn("MOCI", props["alt_names"])

    def test_an_arabic_only_name_reaches_the_destination_bucket(self):
        feats = [overture_feature("مطعم شاطر عباس", category="restaurant",
                                  oid="o-sa", confidence=0.79)]
        out = run_pipeline_to_dicts([], feats)
        self.assertEqual(len(out["canonical"]), 1)
        props = out["canonical"][0]["properties"]
        self.assertEqual(props["usefulness"], "DESTINATION")
        self.assertGreater(props["quality_score"], 0.6)

    def test_shater_abbas_survives(self):
        feats = [overture_feature("Shater Abbas Restaurant",
                                  category="restaurant", oid="o-sa2",
                                  confidence=0.4922)]
        out = run_pipeline_to_dicts([], feats)
        self.assertEqual([f["properties"]["name"] for f in out["canonical"]],
                         ["Shater Abbas Restaurant"])

    def test_chain_outlets_are_not_collapsed_by_name_repair(self):
        """117 Woqod / 93 Starbucks: far-apart outlets stay separate."""
        feats = []
        for i in range(20):
            feats.append(overture_feature(
                "Starbucks", category="coffee_shop", oid=f"sb{i}",
                lon=51.30 + i * 0.02, lat=25.20 + i * 0.01))
            feats.append(overture_feature(
                "Woqod", category="gas_station", oid=f"wq{i}",
                lon=51.31 + i * 0.02, lat=25.60 - i * 0.01))
        out = run_pipeline_to_dicts([], feats)
        names = [f["properties"]["name"] for f in out["canonical"]]
        self.assertEqual(names.count("Starbucks"), 20)
        self.assertEqual(names.count("Woqod"), 20)

    def test_an_arabic_chain_node_merges_onto_its_english_twin(self):
        """An OSM node tagged ``name=ستاربكس, name:en=Starbucks`` 30 m from the
        Overture "Starbucks" is ONE shop. Before the fix its display name was
        the Arabic one, which normalised to "" and could never group, so the
        map showed the same coffee shop twice. It must now resolve to the
        English label while staying searchable in Arabic.

        NOTE the merge comes from the repaired *name*, not from matching a
        transliteration: nothing here claims ستاربكس == Starbucks as strings.
        """
        out = run_pipeline_to_dicts(
            [osm_feature("ستاربكس", poi_class="cafe", fid="n-sb",
                         lon=51.5000, lat=25.3000,
                         **{"name:en": "Starbucks"})],
            [overture_feature("Starbucks", category="coffee_shop", oid="o-sb",
                              lon=51.50025, lat=25.30010)])
        self.assertEqual(len(out["canonical"]), 1)
        props = out["canonical"][0]["properties"]
        self.assertEqual(props["name"], "Starbucks")
        self.assertIn("ستاربكس", props.get("alt_names") or [])

    def test_two_churches_sixty_metres_apart_stay_separate(self):
        out = run_pipeline_to_dicts([], [
            overture_feature("Anglican Church Centre", category="church",
                             oid="c1", lon=51.4000, lat=25.2000),
            overture_feature("Mar Thoma Church", category="church",
                             oid="c2", lon=51.40060, lat=25.20000),
        ])
        self.assertEqual(
            sorted(f["properties"]["name"] for f in out["canonical"]),
            ["Anglican Church Centre", "Mar Thoma Church"])

    def test_the_rosary_typo_cluster_canonicalises_to_one(self):
        out = run_pipeline_to_dicts([], [
            overture_feature("Holy Rosary Catholic Church",
                             category="catholic_church", oid="r1",
                             lon=51.4000, lat=25.2000, confidence=0.9),
            overture_feature("Churh Of The Holy Rosary",
                             category="catholic_church", oid="r2",
                             lon=51.4400, lat=25.2100, confidence=0.5353),
            overture_feature("Holy Rosary Church of Doha Qatar..",
                             category="catholic_church", oid="r3",
                             lon=51.4010, lat=25.2005, confidence=0.6),
        ])
        rosary = [f for f in out["canonical"]
                  if "rosary" in f["properties"]["name"].lower()]
        self.assertEqual(len(rosary), 1, [f["properties"]["name"] for f in rosary])
        self.assertEqual(rosary[0]["properties"]["name"],
                         "Holy Rosary Catholic Church")
        alts = rosary[0]["properties"].get("alt_names") or []
        self.assertIn("Churh Of The Holy Rosary", alts)

    def test_nothing_findable_becomes_unfindable(self):
        """Every spelling a record carried before is still on the record."""
        raw = [
            "Atyab Al Marshoud - Mall of Qatar | أطياب المرشود - مول قطر",
            "حلويات العكر | Al Aker Sweets",
            "Mykco Office 44980300",
            "Interior Design Company in Qatar",
            "DOHA SC FESTIVAL CITY",
        ]
        feats = [overture_feature(n, category="shopping", oid=f"a{i}",
                                  lon=51.3 + i * 0.05, lat=25.2 + i * 0.05)
                 for i, n in enumerate(raw)]
        out = run_pipeline_to_dicts([], feats)
        self.assertEqual(len(out["canonical"]), len(raw))
        for f in out["canonical"]:
            props = f["properties"]
            forms = {props["name"], props.get("name:en"), props.get("name:ar")}
            forms.update(props.get("alt_names") or [])
            forms.discard(None)
            normed = {normalize_name(x) for x in forms}
            self.assertTrue(
                any(normalize_name(r) in normed for r in raw),
                f"{props['name']!r} lost every original spelling")


class RepairIsNonDestructiveTest(unittest.TestCase):

    def test_repair_does_not_mutate_the_input_features(self):
        feats = [overture_feature("Al Meera | الميرة", category="grocery_store")]
        before = feats[0]["properties"]["name"]
        repair_feature_names(feats)
        self.assertEqual(feats[0]["properties"]["name"], before)

    def test_a_feature_with_no_name_passes_through(self):
        feats = [{"type": "Feature", "id": "x",
                  "geometry": {"type": "Point", "coordinates": [51.4, 25.2]},
                  "properties": {"kind": "poi"}}]
        self.assertEqual(repair_feature_names(feats), feats)


if __name__ == "__main__":
    unittest.main()
