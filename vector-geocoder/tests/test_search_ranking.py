"""Ranking regressions from the Lusail drive: "moci lusail".

Reproduced against production on 2026-09-14. A driver approaching Lusail
searched for the Ministry of Commerce and Industry and was shown this
(``/search?q=moci lusail&lat=25.3548&lon=51.4894``):

    1. Lusail                                           [road]
    2. Lusail                                           [road]
    3. Lusail                                           [road]
    4. Lusail                                           [road]
    5. Lusail                                           [road]
    6. QNB ATM Ministry of Commerce & Industry Lusail   [cabin]
    7. وزارة التجارة والصناعة Ministry of Commerce ...  [central_government_office]

The search panel shows about five rows before it scrolls, so the ministry was
off the bottom of the screen and the driver used Google Maps instead.

Three separate faults produced those seven rows, and the fixture below is the
REAL production data for all of them (properties copied verbatim from
``qatar.geojson`` / ``qatar_places.geojson`` on the production host):

1. Proximity outranked match quality. The ministry answers BOTH words — "moci"
   through its curated alias, "lusail" through ``address="lusail City"`` —
   while the road answers one of the two. Being nearer won anyway.
2. The road called لوسيل reaches the index as SEVEN drivable ways (OSM splits
   a road at every junction), and all seven were offered as separate answers.
3. Overture files the QNB cash machine in the ministry's lobby under
   ``category="cabin"``, which the ingestion pipeline read as LODGING and
   scored 0.799 — above the ministry's 0.7358. That half is fixed in
   ``vector-ingestion`` (see ``tests/test_poi_atm_classification.py``) and
   needs a re-bake; the class below pins what the re-baked data then ranks.
"""

import unittest

from vector_geocoder.index import GeocodeIndex

DOHA_CENTRE = (25.2854, 51.5310)
LUSAIL_APPROACH = (25.3548, 51.4894)
AT_THE_MINISTRY = (25.3932, 51.5219)

MINISTRY = "وزارة التجارة والصناعة Ministry of Commerce and Industry of Qatar"
ATM = "QNB ATM Ministry of Commerce & Industry Lusail"

#: The seven ways of the road called لوسيل, first vertex of each, as measured.
#: They span 1.34 km; the eighth way with that name is a residential street
#: 91 km north near Al Ruwais and is a DIFFERENT road.
_LUSAIL_WAYS = [
    (51.51974, 25.35778), (51.51989, 25.36427), (51.52062, 25.36278),
    (51.52062, 25.36278), (51.52056, 25.36401), (51.52441, 25.36986),
    (51.52508, 25.36976),
]
_LUSAIL_FAR_WAY = (51.19570, 26.12591)


def _road(lon, lat, highway="unclassified"):
    return {
        "type": "Feature",
        "geometry": {"type": "LineString",
                     "coordinates": [[lon, lat], [lon + 0.0005, lat + 0.0005]]},
        "properties": {"kind": "road", "name": "لوسيل", "name:en": "Lusail",
                       "highway": highway, "car": True},
    }


def _poi(name, lon, lat, **props):
    p = {"kind": "poi", "name": name, "source": "overture"}
    p.update(props)
    return {"type": "Feature",
            "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": p}


def _ministry():
    return _poi(MINISTRY, 51.52191, 25.39321,
                poi_family="GOVERNMENT", quality_score=0.7358,
                usefulness="DESTINATION", category="central_government_office",
                poi_class="central_government_office", alt_names=["MOCI"],
                address="lusail City", locality="الظعاين", confidence=0.6682)


def _atm_as_shipped():
    """The ATM exactly as production has it today — misfiled and overscored."""
    return _poi(ATM, 51.52187, 25.39358,
                poi_family="LODGING", quality_score=0.799,
                usefulness="DESTINATION", category="cabin", poi_class="cabin",
                alt_names=["MOCI"],
                address=("Ministry of Commerce & Industry, lusail, Zone No 69, "
                         "Ghar Thuaileb, St No 305"),
                locality="Al Daayen", confidence=0.85)


def _atm_rebaked():
    """The same record after the ingestion fix: an ATM, scored as one, and
    without the ministry's curated acronym riding on it."""
    return _poi(ATM, 51.52187, 25.39358,
                poi_family="FINANCE", quality_score=0.5875,
                usefulness="DESTINATION", category="atm", poi_class="atm",
                address=("Ministry of Commerce & Industry, lusail, Zone No 69, "
                         "Ghar Thuaileb, St No 305"),
                locality="Al Daayen", confidence=0.85)


#: Unrelated filler, because ``_token_candidates`` measures how COMMON a word
#: is as a fraction of the whole index (``_COMMON_TOKEN_FRACTION``). Qatar's
#: index holds ~75,000 entries, where both "moci" and "lusail" are rare enough
#: to seed the candidate set; in a twelve-feature fixture every word looks
#: common and the ranking under test is never reached. The filler restores the
#: proportion, and matches nothing.
def _filler(n=1000):
    return [_poi(f"Filler Depot {i}", 51.40 + i * 0.0005, 25.10 + i * 0.0003)
            for i in range(n)]


def _lusail_corpus(atm):
    features = [_road(lon, lat) for lon, lat in _LUSAIL_WAYS]
    features.append(_road(*_LUSAIL_FAR_WAY, highway="residential"))
    features += [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.51972, 25.42289]},
         "properties": {"kind": "label", "name": "لوسيل", "name:en": "Lusail"}},
        _poi("Lusail", 51.52499, 25.39281, quality_score=0.4853),
        _poi("Lusail", 51.53133, 25.38862, category="hotel", quality_score=0.6392),
        _poi("Lusail", 51.48772, 25.41487, category="transportation",
             quality_score=0.7069),
        _ministry(),
        atm,
    ]
    features += _filler()
    return GeocodeIndex.from_geojson({"type": "FeatureCollection",
                                      "features": features})


def _names(hits):
    return [h.name for h in hits]


class MociLusailTest(unittest.TestCase):
    """The reproduced drive, against the data production actually holds."""

    def setUp(self):
        self.idx = _lusail_corpus(_atm_as_shipped())

    def test_the_ministry_is_in_the_top_three_from_anywhere_in_qatar(self):
        # The acceptance case. With proximity bias on — which the app always
        # sends while driving — the ministry was rank 7 from the Lusail
        # approach, below the fold of a five-row panel.
        for label, near in (("Doha centre", DOHA_CENTRE),
                            ("Lusail approach", LUSAIL_APPROACH),
                            ("at the ministry", AT_THE_MINISTRY),
                            ("no position", None)):
            with self.subTest(near=label):
                top = _names(self.idx.search("moci lusail", limit=3, near=near))
                self.assertIn(MINISTRY, top,
                              f"ministry missing from the top 3 near {label}: {top}")

    def test_matching_both_words_outranks_matching_one(self):
        # The ordering contract: coverage first, proximity only between
        # comparable matches. Every "Lusail" answers one of the two words; the
        # ministry answers both, and is 5.4 km further away.
        hits = self.idx.search("moci lusail", limit=0, near=LUSAIL_APPROACH)
        rank = _names(hits).index(MINISTRY)
        first_lusail = next(i for i, h in enumerate(hits) if h.name in ("لوسيل", "Lusail"))
        self.assertLess(rank, first_lusail,
                        "a record answering 2/2 words ranked below one answering 1/2")

    def test_one_road_is_one_row(self):
        # Seven ways of one road, and a genuinely different road 91 km away
        # that shares the name. The answer is two rows, not eight.
        hits = self.idx.search("moci lusail", limit=0, near=LUSAIL_APPROACH)
        roads = [h for h in hits if h.is_road]
        self.assertEqual(len(roads), 2, f"road rows: {[(h.lat, h.lon) for h in roads]}")
        self.assertEqual(1, sum(1 for h in roads if h.lat < 26.0))
        self.assertEqual(1, sum(1 for h in roads if h.lat > 26.0))

    def test_the_index_still_holds_every_way(self):
        # The collapse is a presentation rule. Reverse geocoding and /speed
        # must still see all seven ways, along the whole road.
        self.assertEqual(len(self.idx._roads), 8)
        here = self.idx.reverse(25.36986, 51.52441, limit=1)[0]
        self.assertAlmostEqual(here.lat, 25.36986, places=4)

    def test_no_row_is_repeated(self):
        rows = [(h.name, h.kind, h.raw.get("category")) for h
                in self.idx.search("moci lusail", limit=5, near=LUSAIL_APPROACH)]
        self.assertEqual(len(rows), len(set(rows)), f"repeated rows: {rows}")


class MociLusailAfterTheRebakeTest(unittest.TestCase):
    """What the same query answers once the POI pipeline has been re-run.

    The geocoder fix alone puts the ministry at rank 2 — a cash machine named
    after the building still outscores the building. Correcting the ATM in
    ingestion is what makes the ministry the first answer.
    """

    def setUp(self):
        self.idx = _lusail_corpus(_atm_rebaked())

    def test_the_ministry_is_the_first_answer(self):
        for label, near in (("Doha centre", DOHA_CENTRE),
                            ("Lusail approach", LUSAIL_APPROACH),
                            ("at the ministry", AT_THE_MINISTRY),
                            ("no position", None)):
            with self.subTest(near=label):
                hits = self.idx.search("moci lusail", limit=5, near=near)
                self.assertEqual(hits[0].name, MINISTRY, _names(hits))

    def test_the_atm_no_longer_answers_to_the_ministrys_acronym(self):
        # Without the leaked "MOCI" alias the cash machine matches one word of
        # two, like every other Lusail thing, and stops impersonating the
        # ministry.
        hits = self.idx.search("moci lusail", limit=0, near=LUSAIL_APPROACH)
        self.assertNotIn(ATM, _names(hits)[:3])


class CoverageOrderingTest(unittest.TestCase):
    """The rule, on the smallest data that can express it."""

    def _idx(self, *features):
        return GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": list(features) + _filler()})

    def test_distance_cannot_overturn_coverage(self):
        # "Pearl" is 2 km away and answers one of the two words. "Blue" is
        # 15 km away and answers both — one in its name, one in its address.
        near_one_word = _poi("Pearl", 51.5300, 25.2850)
        far_both_words = _poi("Blue", 51.6000, 25.4000, address="Pearl Qatar")
        idx = self._idx(near_one_word, far_both_words)
        top = _names(idx.search("blue pearl", limit=2, near=DOHA_CENTRE))
        self.assertEqual(top[0], "Blue", top)

    def test_an_address_word_does_not_by_itself_make_a_hit(self):
        # Coverage measures how much of the query a MATCH explains. It never
        # promotes a record that the query never matched in the first place.
        idx = self._idx(_poi("Blue Salon", 51.6000, 25.4000, address="Pearl Qatar"))
        self.assertEqual(_names(idx.search("pearl", limit=5)), [])

    def test_an_exact_name_still_beats_a_broader_word_match(self):
        # The pre-existing contract: a contiguous whole-query match counts as
        # full coverage, so it can never be demoted under a word-level one.
        exact = _poi("Villaggio Mall", 51.4437, 25.2585)
        broader = _poi("Villaggio Mall Gate 1 Car Park", 51.6000, 25.4000)
        idx = self._idx(broader, exact)
        top = _names(idx.search("villaggio mall", limit=2, near=DOHA_CENTRE))
        self.assertEqual(top[0], "Villaggio Mall", top)

    def test_a_repeated_query_word_cannot_inflate_coverage(self):
        # Typing a word twice must not buy a second point of coverage: the
        # near "Lusail" answers one distinct word, the far "Moci" answers two.
        near_one_word = _poi("Lusail", 51.5300, 25.2850)
        far_both_words = _poi("Moci", 51.6000, 25.4000, address="Lusail City")
        idx = self._idx(near_one_word, far_both_words)
        top = _names(idx.search("moci lusail lusail", limit=2, near=DOHA_CENTRE))
        self.assertEqual(top[0], "Moci", top)


class SearchDuplicateCollapseTest(unittest.TestCase):
    """One place, one row — over the RANKED list, before ``limit`` is applied."""

    def _idx(self, *features):
        return GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": list(features) + _filler()})

    def test_the_kept_row_is_the_best_ranked_one(self):
        # Two ways of one road, the second of them the one the driver is
        # nearer to. The row that survives is the row that ranked best.
        idx = self._idx(_road(51.5300, 25.3600), _road(51.5250, 25.3560))
        hits = idx.search("lusail", limit=5, near=LUSAIL_APPROACH)
        self.assertEqual(len(hits), 1)
        self.assertAlmostEqual(hits[0].lat, 25.3560, places=4)

    def test_a_chain_keeps_all_of_its_outlets(self):
        idx = self._idx(
            _poi("Carrefour", 51.4400, 25.2500),
            _poi("Carrefour", 51.5300, 25.3100),
        )
        self.assertEqual(len(idx.search("carrefour", limit=5)), 2)

    def test_the_collapse_happens_before_the_limit(self):
        # The rows a duplicate was occupying are given back to real answers.
        features = [_road(lon, lat) for lon, lat in _LUSAIL_WAYS]
        features.append(_poi("Lusail Boulevard", 51.5250, 25.3700))
        idx = GeocodeIndex.from_geojson({"type": "FeatureCollection",
                                         "features": features + _filler()})
        top = _names(idx.search("lusail", limit=2, near=LUSAIL_APPROACH))
        self.assertIn("Lusail Boulevard", top, top)


if __name__ == "__main__":
    unittest.main()
