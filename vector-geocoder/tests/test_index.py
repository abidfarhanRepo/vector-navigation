import json
import os
import unittest

from vector_geocoder.index import GeocodeIndex, GeocodeHit
from vector_geocoder.serve import GeocodeService, make_server

HERE = os.path.dirname(__file__)
SAMPLE = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.50, 25.29]},
         "properties": {"name": "Mishireb", "kind": "label"}},
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.51, 25.30], [51.52, 25.31]]},
         "properties": {"name": "Corniche Street", "kind": "road", "maxspeed": "60"}},
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.53, 25.28], [51.54, 25.29]]},
         "properties": {"name": "Salwa Road", "kind": "road", "maxspeed": "80"}},
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.40, 25.20], [51.41, 25.21]]},
         "properties": {"name": "Side Street", "kind": "road", "highway": "residential"}},
        # A bilingual, ref-carrying road, shaped like the real extract: the
        # local name is Arabic and `name:en` carries the Latin one. 56,357 of
        # the indexed Qatar features look like this and nothing read the
        # second field until V4.
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.60, 25.40], [51.61, 25.41]]},
         "properties": {"name": "\u0634\u0627\u0631\u0639 \u0627\u0644\u0643\u0648\u0631\u0646\u064a\u0634",
                        "name:en": "Al Corniche Street",
                        "ref": "C Ring", "highway": "trunk",
                        "kind": "road", "maxspeed": "80"}},
    ],
}


class IndexTest(unittest.TestCase):
    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(SAMPLE)

    def test_indexes_named_features(self):
        # 5 since V4 added a bilingual road to the fixture. This counts the
        # fixture, not a behaviour.
        self.assertEqual(self.idx.size(), 5)

    def test_prefix_match(self):
        hits = self.idx.search("Co")
        names = [h.name for h in hits]
        self.assertIn("Corniche Street", names)

    def test_substring_match(self):
        hits = self.idx.search("road")
        self.assertTrue(any(h.name == "Salwa Road" for h in hits))

    def test_case_insensitive(self):
        hits = self.idx.search("corniche")
        self.assertTrue(any(h.name == "Corniche Street" for h in hits))

    def test_limit(self):
        hits = self.idx.search("a", limit=2)
        self.assertLessEqual(len(hits), 2)

    def test_empty_query(self):
        self.assertEqual(self.idx.search(""), [])

    def test_speed_nearest_road(self):
        # Point near Corniche Street's first vertex (51.51, 25.30).
        sp = self.idx.speed(25.3001, 51.5101)
        self.assertEqual(sp["maxspeed_kmh"], 60)
        self.assertEqual(sp["name"], "Corniche Street")
        self.assertEqual(sp["source"], "tag")

    def test_speed_carries_the_english_name_and_the_ref(self):
        """The road-you-are-on readout is a by-product of this lookup (V4).

        Both fields were already in `raw` for every road this index has ever
        answered about, and neither was returned. The client polls /speed once
        every 150 m for the limit sign, so naming the road costs nothing — and
        `ref` ("C Ring") is what the driver matches against the gantry.
        """
        sp = self.idx.speed(25.4001, 51.6001)
        self.assertEqual(sp["name"], "\u0634\u0627\u0631\u0639 \u0627\u0644\u0643\u0648\u0631\u0646\u064a\u0634")
        self.assertEqual(sp["name_en"], "Al Corniche Street")
        self.assertEqual(sp["ref"], "C Ring")
        self.assertEqual(sp["highway"], "trunk")

    def test_speed_reports_none_rather_than_empty_for_absent_names(self):
        """A road with no `name:en` must not answer with "".

        An empty string is truthy-adjacent in enough client languages that it
        ends up rendered as a blank road label; None is the honest value and
        the client already knows what to do with it.
        """
        sp = self.idx.speed(25.2001, 51.4001)   # Side Street: no name:en, no ref
        self.assertIsNone(sp["name_en"])
        self.assertIsNone(sp["ref"])

    def test_speed_default_by_class(self):
        # Side Street has highway=residential and no maxspeed, so the class
        # default applies. 50, not the 30 this test used to pin: 30 was an
        # invented "conventional" value, while 50 is the observed median of
        # 3,375 tagged residential ways in the Qatar extract and is what the
        # router costs the same road at. The two numbers disagreeing is how a
        # driver ends up shown a limit the product itself does not believe.
        sp = self.idx.speed(25.2001, 51.4001)
        self.assertEqual(sp["maxspeed_kmh"], 50)
        self.assertEqual(sp["source"], "default")

    def test_the_class_defaults_match_the_routing_speed_model(self):
        """Pins the deliberate duplicate of `vector_routing.speeds`.

        ADR-0003 keeps the engines from importing one another, so this table is
        copied. A copy that drifts is worse than no copy: the badge would show
        one limit while the ETA was computed from another, and nothing would
        fail. These are the values in `CLASS_DEFAULT_KMH` over there.
        """
        from vector_geocoder.index import CLASS_DEFAULT_KMH
        self.assertEqual(CLASS_DEFAULT_KMH, {
            "motorway": 120, "trunk": 80, "primary": 80, "secondary": 80,
            "tertiary": 50, "unclassified": 50, "residential": 50,
            "living_street": 20, "service": 40, "track": 30, "road": 40,
            "motorway_link": 60, "trunk_link": 80, "primary_link": 60,
            "secondary_link": 60, "tertiary_link": 50,
        })

    def test_speed_no_road(self):
        # Far empty point -> no limit.
        sp = self.idx.speed(0.0, 0.0)
        self.assertIsNone(sp["maxspeed_kmh"])

    def test_hit_geojson_shape(self):
        h = self.idx.search("Mishireb")[0]
        gj = h.to_geojson()
        self.assertEqual(gj["geometry"]["type"], "Point")
        self.assertEqual(gj["properties"]["name"], "Mishireb")


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.svc = GeocodeService(self.idx_build())

    @staticmethod
    def idx_build():
        return GeocodeIndex.from_geojson(SAMPLE)

    def test_search(self):
        res = self.svc.search("Salwa", limit=5)
        self.assertEqual(res["type"], "FeatureCollection")
        self.assertTrue(any(f["properties"]["name"] == "Salwa Road"
                            for f in res["features"]))

    def test_health(self):
        h = self.svc.health()
        self.assertEqual(h["status"], "ok")
        self.assertEqual(h["service"], "vector-geocoder")

    def test_speed(self):
        res = self.svc.speed(25.3001, 51.5101)
        self.assertEqual(res["maxspeed_kmh"], 60)
        self.assertEqual(res["name"], "Corniche Street")

    def test_speed_lang_en_resolves_name_the_way_navigate_does(self):
        """`/speed?lang=en` must agree with `/navigate?lang=en` (V4).

        The maneuver banner and the road-you-are-on readout are four
        millimetres apart on the same screen. One saying "Al Corniche Street"
        while the other says the Arabic name is worse than either language
        used throughout, so this endpoint copies
        `vector_routing.router.display_name`'s rule: prefer `name:en`, fall
        back to the local name.
        """
        res = self.svc.speed(25.4001, 51.6001, lang="en")
        self.assertEqual(res["name"], "Al Corniche Street")
        # The unresolved value stays available, so a caller can still choose.
        self.assertEqual(res["name_en"], "Al Corniche Street")

    def test_speed_without_lang_is_unchanged(self):
        """Additive: the web client passes no `lang` and must see no change."""
        res = self.svc.speed(25.4001, 51.6001)
        self.assertEqual(res["name"], "\u0634\u0627\u0631\u0639 \u0627\u0644\u0643\u0648\u0631\u0646\u064a\u0634")

    def test_speed_lang_en_falls_back_when_there_is_no_english_name(self):
        res = self.svc.speed(25.2001, 51.4001, lang="en")   # Side Street
        self.assertEqual(res["name"], "Side Street")


AR_SAMPLE = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.51, 25.30], [51.52, 25.31]]},
         "properties": {"name": "طريق سلوى", "kind": "road"}},
    ],
}


class ArabicTranslitTest(unittest.TestCase):
    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(AR_SAMPLE)

    def test_latin_query_finds_arabic_name(self):
        # "salwa" (Latin) should match the Arabic "طريق سلوى" via transliteration.
        hits = self.idx.search("salwa")
        self.assertTrue(any(h.name == "طريق سلوى" for h in hits))

    def test_arabic_query_still_works(self):
        hits = self.idx.search("سلوى")
        self.assertTrue(any(h.name == "طريق سلوى" for h in hits))


class PoiCategoryTest(unittest.TestCase):
    """A search result has to say what a place IS, in words a person uses.

    `kind` is the basemap layer name, and for every one of the 8,735 POIs in the
    Qatar extract its value is the literal string "poi". So a search for "souq"
    answered with four rows reading "poi", and an area tagged `leisure` answered
    "park" whether or not it is one — developer terminology shipped to a driver.

    The category was in the index the whole time: vector-ingestion writes
    `poi_class` for every POI. Nothing read it.
    """

    def _index(self, props):
        return GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [51.53, 25.28]},
                "properties": props,
            }],
        })

    def _props(self, idx, q="test"):
        hits = idx.search(q)
        self.assertTrue(hits, "the fixture did not index")
        return hits[0].to_geojson()["properties"]

    def test_an_osm_poi_reports_its_category(self):
        props = self._props(self._index(
            {"kind": "poi", "name": "test cafe", "poi_class": "cafe"}))
        self.assertEqual(props["category"], "cafe")

    def test_building_yes_is_not_a_category(self):
        # `poi_class: yes` is what `building=yes` / `shop=yes` leave behind —
        # 860 of Qatar's POIs. It means "this exists". Saying nothing is better
        # than telling a driver a place is a "Yes".
        props = self._props(self._index(
            {"kind": "poi", "name": "test place", "poi_class": "yes"}))
        self.assertNotIn("category", props)

    def test_a_poi_with_no_class_says_nothing_rather_than_guessing(self):
        props = self._props(self._index({"kind": "poi", "name": "test place"}))
        self.assertNotIn("category", props)

    def test_an_overture_category_is_not_overwritten(self):
        # Overture POIs already populate `category`, and it is richer than the
        # OSM tag. The OSM fallback must not clobber it.
        props = self._props(self._index({
            "kind": "poi", "name": "test place",
            "category": "coffee_shop", "poi_class": "cafe",
        }))
        self.assertEqual(props["category"], "coffee_shop")

    def test_kind_is_still_reported(self):
        # Existing clients read `kind`; adding a field must not remove one.
        props = self._props(self._index(
            {"kind": "poi", "name": "test cafe", "poi_class": "cafe"}))
        self.assertEqual(props["kind"], "poi")


class DuplicatePlaceTest(unittest.TestCase):
    """The same place mapped twice is offered once.

    OSM very often carries a business as a NODE and the building it occupies as
    a POLYGON, both named. Both are legitimate and both reach the index now that
    POI areas are fetched, so a search for "villaggio" answered with
    **VILLAGGIO MALL, 8.7 km away** twice — measured on an S24.

    Deduplicated by name AND POSITION, never by name alone: Qatar has seven
    Carrefours and every one of them is real.
    """

    def _index(self, *features):
        return GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {"type": "Point", "coordinates": [lon, lat]},
                    "properties": props,
                }
                for props, lon, lat in features
            ],
        })

    def test_the_same_name_at_the_same_spot_is_indexed_once(self):
        idx = self._index(
            ({"kind": "poi", "name": "Villaggio Mall"}, 51.4437, 25.2585),
            ({"kind": "poi", "name": "Villaggio Mall"}, 51.4438, 25.2586),
        )
        self.assertEqual(len(idx.search("villaggio")), 1)

    def test_the_real_villaggio_pair_collapses(self):
        # The actual coordinates from the live index: the mall's node and its
        # building centroid, 175 m apart because the building is enormous. The
        # first version of the dedupe compared coordinate BUCKETS and missed
        # this pair, because the two points rounded two cells apart rather than
        # one — so the duplicate stayed on screen and the fix looked done.
        idx = self._index(
            ({"kind": "poi", "name": "VILLAGGIO MALL"}, 51.443937, 25.260825),
            ({"kind": "poi", "name": "VILLAGGIO MALL"}, 51.443822, 25.259241),
        )
        self.assertEqual(len(idx.search("villaggio")), 1)

    def test_the_radius_is_a_distance_not_a_bucket_size(self):
        # Just inside and just outside DUP_RADIUS_M along a meridian, where a
        # degree of latitude is 110,574 m. Bucket alignment must not decide it.
        r = GeocodeIndex.DUP_RADIUS_M
        inside = (r * 0.8) / 110_574.0
        outside = (r * 1.5) / 110_574.0
        near = self._index(
            ({"kind": "poi", "name": "Same Place"}, 51.5, 25.3),
            ({"kind": "poi", "name": "Same Place"}, 51.5, 25.3 + inside),
        )
        self.assertEqual(len(near.search("same place")), 1)
        far = self._index(
            ({"kind": "poi", "name": "Same Place"}, 51.5, 25.3),
            ({"kind": "poi", "name": "Same Place"}, 51.5, 25.3 + outside),
        )
        self.assertEqual(len(far.search("same place")), 2)

    def test_the_same_name_far_apart_is_two_places(self):
        # Seven Carrefours. Collapsing them by name would leave one.
        idx = self._index(
            ({"kind": "poi", "name": "Carrefour"}, 51.44, 25.25),
            ({"kind": "poi", "name": "Carrefour"}, 51.53, 25.31),
        )
        self.assertEqual(len(idx.search("carrefour")), 2)

    def test_different_names_at_the_same_spot_both_survive(self):
        # A restaurant inside a mall is not the mall.
        idx = self._index(
            ({"kind": "poi", "name": "Villaggio Mall"}, 51.4437, 25.2585),
            ({"kind": "poi", "name": "T.G.I. Friday's"}, 51.4437, 25.2585),
        )
        self.assertEqual(len(idx.search("villaggio")), 1)
        self.assertEqual(len(idx.search("friday")), 1)

    def test_case_and_spacing_do_not_defeat_the_dedupe(self):
        # "VILLAGGIO MALL" and "Villaggio Mall" are the same place; the
        # comparison normalises before bucketing.
        idx = self._index(
            ({"kind": "poi", "name": "VILLAGGIO MALL"}, 51.4437, 25.2585),
            ({"kind": "poi", "name": "Villaggio Mall"}, 51.4437, 25.2585),
        )
        self.assertEqual(len(idx.search("villaggio")), 1)

    def test_roads_are_never_deduplicated_in_the_index(self):
        # A long road is legitimately many ways with the same name. Collapsing
        # them would leave one arbitrary segment of Al Corniche in the index and
        # break reverse geocoding along the rest of it.
        #
        # The INDEX is what this pins. The answer a driver reads is collapsed
        # to one row per road (see `SearchDuplicateCollapseTest`) — that is a
        # presentation rule, and it deliberately does not reach back into the
        # data these two entries are: `reverse` still finds whichever way is
        # nearest, which is the guarantee the comment above is about.
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {"type": "LineString",
                                 "coordinates": [[51.53, 25.28], [51.5301, 25.2801]]},
                    "properties": {"kind": "road", "name": "Al Corniche",
                                   "highway": "primary", "car": True},
                },
                {
                    "type": "Feature",
                    "geometry": {"type": "LineString",
                                 "coordinates": [[51.5301, 25.2801], [51.5302, 25.2802]]},
                    "properties": {"kind": "road", "name": "Al Corniche",
                                   "highway": "primary", "car": True},
                },
            ],
        })
        self.assertEqual(len(idx._hits), 2)
        self.assertEqual(len(idx._roads), 2)
        # Both ways remain reachable by position, along the whole road.
        self.assertEqual(idx.reverse(25.2802, 51.5302, limit=1)[0].lat, 25.2801)
        self.assertEqual(idx.reverse(25.2800, 51.5300, limit=1)[0].lat, 25.28)


class ReverseShapeDistanceTest(unittest.TestCase):
    """`reverse` ranks a road by its SHAPE, not by one of its vertices.

    Measured on production on 2026-09-21. A pin dropped at 25.265769, 51.530550
    — the point the driver's thumb landed on — came back naming `سكة عامر` at
    73.3 m, while the served tile puts `Al Urouba Street` **40.9 m** away. The
    street the pin is actually on was not in the top eight at all.

    The cause is not subtle once seen: a way is indexed at `ring[0]`, so
    `reverse` was ranking roads by where they BEGIN. A road passing 5 m from the
    point but starting 300 m away loses to a road that starts nearby and goes
    the other way.
    """

    def _index(self):
        return GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                # Passes ~5 m from the query point, and its first vertex is
                # ~350 m away: what a real Doha side street looks like.
                {
                    "type": "Feature",
                    "geometry": {"type": "LineString",
                                 "coordinates": [[51.5270, 25.26575],
                                                 [51.53055, 25.26575],
                                                 [51.53055, 25.2670]]},
                    "properties": {"kind": "road", "name": "Al Urouba Street",
                                   "name:en": "Al Urouba Street",
                                   "highway": "residential", "car": True},
                },
                # Starts 70 m from the query point and goes away from it.
                {
                    "type": "Feature",
                    "geometry": {"type": "LineString",
                                 "coordinates": [[51.5312, 25.2658],
                                                 [51.5330, 25.2658]]},
                    "properties": {"kind": "road", "name": "Sikkat Amer",
                                   "highway": "residential", "car": True},
                },
            ],
        })

    def test_the_road_passing_closest_wins(self):
        idx = self._index()
        got = idx.reverse(25.2658, 51.5305, limit=1)
        self.assertEqual(got[0].name, "Al Urouba Street")

    def test_the_handful_of_metres_is_not_swallowed_by_the_vertex(self):
        """The loser is 70 m by its start point and 14x further in reality."""
        idx = self._index()
        names = [h.name for h in idx.reverse(25.2658, 51.5305, limit=2)]
        self.assertEqual(names, ["Al Urouba Street", "Sikkat Amer"])

    def test_a_point_feature_still_ranks_by_its_own_position(self):
        """No polyline, no change: the bound must not move POIs."""
        idx = GeocodeIndex.from_geojson({
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature",
                 "geometry": {"type": "Point", "coordinates": [51.5306, 25.2659]},
                 "properties": {"kind": "poi", "name": "Green Tea Garden Restaurant"}},
            ],
        })
        self.assertEqual(idx.reverse(25.2658, 51.5305, limit=1)[0].name,
                         "Green Tea Garden Restaurant")

    def test_speed_agrees_with_reverse_about_which_road_is_nearest(self):
        """The two endpoints share one measurement now.

        `speed` already measured to the shape; `reverse` measured to a vertex.
        On this fixture the old `reverse` picked the wrong road and `speed`
        picked the right one — which is the driver being told two different
        streets a few millimetres apart on one screen.
        """
        idx = self._index()
        self.assertEqual(idx.speed(25.2658, 51.5305)["name"], "Al Urouba Street")
        self.assertEqual(idx.reverse(25.2658, 51.5305, limit=1)[0].name,
                         "Al Urouba Street")


if __name__ == "__main__":
    unittest.main()


PEDESTRIAN_SAMPLE = {
    "type": "FeatureCollection",
    "features": [
        # A pedestrian way running through a souq, and the drivable street
        # beside it. This is the Souq Waqif geometry that made the badge dark:
        # the footway was 0.7 m from the query and the street ~40 m, so the
        # nearest "road" had no class default and the viewer showed nothing.
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.5333, 25.2867], [51.5334, 25.2868]]},
         "properties": {"name": "Souq lane", "kind": "road",
                        "highway": "pedestrian", "car": False}},
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.5337, 25.2867], [51.5338, 25.2868]]},
         "properties": {"name": "Al Souq Street", "kind": "road",
                        "highway": "tertiary", "car": True}},
    ],
}


class SpeedIgnoresPedestrianWaysTest(unittest.TestCase):
    """The speed badge answers "what is the limit where I am DRIVING".

    A footway is a real map feature and stays searchable, but it must never be
    the road the limit is measured against.
    """

    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(PEDESTRIAN_SAMPLE)

    def test_the_nearest_footway_is_not_used_as_the_road(self):
        sp = self.idx.speed(25.28675, 51.53335)   # right on the souq lane
        self.assertEqual(sp["name"], "Al Souq Street")
        self.assertEqual(sp["maxspeed_kmh"], 50)   # tertiary class default

    def test_a_named_footway_is_still_searchable(self):
        self.assertTrue(any(h.name == "Souq lane" for h in self.idx.search("Souq lane")))

    def test_legacy_data_without_the_car_flag_still_excludes_footways(self):
        """Graphs baked before `vector_ingestion.classify` carry no `car`."""
        legacy = {"type": "FeatureCollection", "features": [
            dict(f, properties={k: v for k, v in f["properties"].items() if k != "car"})
            for f in PEDESTRIAN_SAMPLE["features"]
        ]}
        sp = GeocodeIndex.from_geojson(legacy).speed(25.28675, 51.53335)
        self.assertEqual(sp["name"], "Al Souq Street")

    def test_no_drivable_road_nearby_yields_no_limit_not_a_guess(self):
        sp = self.idx.speed(25.5000, 51.9000)
        self.assertIsNone(sp["maxspeed_kmh"])
        self.assertIsNone(sp["source"])


class SpeedSpatialIndexTest(unittest.TestCase):
    """The grid must be EXACT for the 60 m trust radius, not merely fast.

    A cell is ~505 m wide at Doha's latitude, so the 3x3 probe strictly contains
    every point within 60 m of the query. These tests pin that, including the
    cases a grid usually gets wrong: a query sitting on a cell boundary, and a
    long segment whose vertices are both in other cells.
    """

    def _idx(self, coords, props=None):
        return GeocodeIndex.from_geojson({"type": "FeatureCollection", "features": [
            {"type": "Feature",
             "geometry": {"type": "LineString", "coordinates": coords},
             "properties": dict({"name": "Test Road", "kind": "road",
                                 "highway": "primary", "car": True}, **(props or {}))},
        ]})

    def test_a_road_is_found_from_across_a_cell_boundary(self):
        # 51.5350 is an exact multiple of the 0.005 deg cell size, so the road
        # and the query land in adjacent cells.
        idx = self._idx([[51.53495, 25.2850], [51.53495, 25.2860]])
        sp = idx.speed(25.2855, 51.53505)
        self.assertEqual(sp["name"], "Test Road")

    def test_a_query_beside_the_middle_of_a_long_segment_finds_it(self):
        # A 3 km straight with vertices two cells away in both directions.
        # Registering only the vertices' cells would miss this entirely.
        idx = self._idx([[51.5200, 25.2850], [51.5500, 25.2850]])
        sp = idx.speed(25.28505, 51.5350)
        self.assertEqual(sp["name"], "Test Road")

    def test_a_road_added_after_the_first_query_is_still_found(self):
        """The grid is cached; adding a road must invalidate it."""
        idx = self._idx([[51.5200, 25.2850], [51.5201, 25.2851]])
        idx.speed(25.2850, 51.5200)          # builds the grid
        idx.extend_from_geojson({"type": "FeatureCollection", "features": [
            {"type": "Feature",
             "geometry": {"type": "LineString",
                          "coordinates": [[51.6000, 25.3000], [51.6001, 25.3001]]},
             "properties": {"name": "Later Road", "kind": "road",
                            "highway": "primary", "car": True}}]})
        self.assertEqual(idx.speed(25.30005, 51.60005)["name"], "Later Road")


# --------------------------------------------------------------------------
# V7: the search failures observed on two recorded Doha drives (2026-09-10).
#
# Every fixture below is a real feature from the Qatar extract, with the
# property shape it actually ships with. The drives produced four distinct
# complaints and they turned out to be four distinct defects, so each gets
# its own class rather than one "search" grab-bag.
# --------------------------------------------------------------------------

#: The real Woqod Hilal station: an Arabic `name` with the Latin one in
#: `name:en`. 49,434 Qatar features are shaped exactly like this.
BILINGUAL = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.53547, 25.25866]},
         "properties": {"kind": "poi", "poi_class": "fuel",
                        "name": "محطة وقود "
                                "الهلال",
                        "name:en": "Woqod Hilal Gas Station"}},
        # `name:en` and nothing else — 1,363 Qatar features, all previously
        # dropped by a loader that keyed on `name`.
        {"type": "Feature",
         "geometry": {"type": "LineString",
                      "coordinates": [[51.40, 25.20], [51.41, 25.21]]},
         "properties": {"kind": "road", "highway": "residential",
                        "name:en": "Palm Road"}},
    ],
}


class EnglishNameIsSearchableTest(unittest.TestCase):
    """`name:en` has to be indexed, or the map and the search box disagree.

    The tiles and `/navigate` have read `name:en` since V3, so the driver saw
    "Woqod Hilal Gas Station" on the map, typed it, and got nothing: the index
    knew the place only as its Arabic `name`.
    """

    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(BILINGUAL)

    def test_the_english_name_finds_the_arabic_feature(self):
        hits = self.idx.search("Woqod Hilal Gas Station")
        self.assertTrue(hits, "the English name found nothing")
        self.assertEqual(hits[0].name_en, "Woqod Hilal Gas Station")

    def test_the_arabic_name_still_finds_it(self):
        hits = self.idx.search("وقود")
        self.assertTrue(any(h.name_en == "Woqod Hilal Gas Station"
                            for h in hits))

    def test_a_feature_named_only_in_english_is_indexed_at_all(self):
        """Previously dropped outright: no `name`, so no entry."""
        hits = self.idx.search("Palm Road")
        self.assertTrue(any(h.name == "Palm Road" for h in hits))

    def test_lang_en_labels_the_result_in_english(self):
        """What put an Arabic row in an English Recents list."""
        hit = self.idx.search("Woqod Hilal")[0]
        gj = hit.to_geojson(lang="en")
        self.assertEqual(gj["properties"]["label"], "Woqod Hilal Gas Station")
        self.assertEqual(gj["properties"]["name"], "Woqod Hilal Gas Station")
        # The native name is kept, not discarded.
        self.assertTrue(gj["properties"]["name_local"].startswith("مح"))

    def test_without_lang_the_payload_is_unchanged(self):
        """Additive: the web client sends no `lang` and must see no change."""
        gj = self.idx.search("Woqod Hilal")[0].to_geojson()
        self.assertTrue(gj["properties"]["name"].startswith("مح"))
        self.assertNotIn("name_local", gj["properties"])


#: "Green Tea Garden Restaurant" is signed with four words; OSM recorded two.
TOKENS = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.53799, 25.26688]},
         "properties": {"name": "Green Tea", "kind": "poi",
                        "poi_class": "restaurant"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.53547, 25.25866]},
         "properties": {"name": "Woqod Hilal Gas Station", "kind": "poi",
                        "poi_class": "fuel"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.50, 25.29]},
         "properties": {"name": "Al-Hilal Pharmacy", "kind": "poi",
                        "poi_class": "pharmacy"}},
    ],
}


class WordOrderTest(unittest.TestCase):
    """A driver types words, not substrings.

    Every tier above `_T_TOK_ALL` matches the query as one contiguous run,
    which is why "Woqod" and "Hilal" each found plenty while "Woqod Hilal"
    found nothing at all.
    """

    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(TOKENS)

    def _names(self, q):
        return [h.name for h in self.idx.search(q)]

    def test_the_words_in_order(self):
        self.assertEqual(self._names("Woqod Hilal")[0], "Woqod Hilal Gas Station")

    def test_the_words_out_of_order(self):
        self.assertEqual(self._names("Hilal Woqod")[0], "Woqod Hilal Gas Station")

    def test_more_words_than_the_name_carries(self):
        """The place is signed "Green Tea Garden Restaurant"; OSM says "Green Tea"."""
        self.assertIn("Green Tea", self._names("Green Tea Garden Restaurant"))

    def test_a_half_typed_last_word_still_matches(self):
        self.assertIn("Green Tea", self._names("green te"))

    def test_a_contiguous_match_still_outranks_a_word_match(self):
        """The ranking contract above the token tiers is untouched."""
        self.assertEqual(self._names("Green Tea")[0], "Green Tea")

    def test_one_stray_word_does_not_match_everything(self):
        self.assertNotIn("Al-Hilal Pharmacy", self._names("Woqod Hilal"))


#: The drive that found this. The driver searched "the station car wash" for a
#: place signed "THE STATION TOUCHLESS CAR WASH AND POLISHING" and was offered
#: Carrier, Cartier and Carrefour — none of which is a car wash and none of
#: which shares a word with the query. "car" is a prefix of all three.
PHRASE = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.531, 25.286]},
         "properties": {"name": "Carrier", "kind": "poi"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.532, 25.287]},
         "properties": {"name": "Cartier", "kind": "poi",
                        "poi_class": "jewelry_store"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.533, 25.288]},
         "properties": {"name": "Thejus", "kind": "poi"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.534, 25.289]},
         "properties": {"name": "Car Wash Al Zaaim", "kind": "poi",
                        "poi_class": "car_wash"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.535, 25.290]},
         "properties": {"name": "Starbucks", "kind": "poi",
                        "poi_class": "coffee_shop"}},
    ],
}


class PhraseSearchTest(unittest.TestCase):
    """Typing MORE words must narrow the search, never destroy it.

    `_T_TOK_NAME` means "every word of the name was matched, so the query is a
    superset of it". For a ONE-WORD name that condition is satisfied by any
    single query word that is a prefix of it — so a four-word query handed its
    strongest word-level tier to every short name beginning "car", and that
    tier outranks `_T_TOK_MOST`. Matching fewer of the driver's words ranked
    higher, and the places that matched two of them were cut entirely.
    """

    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(PHRASE)

    def _names(self, q):
        return [h.name for h in self.idx.search(q)]

    def test_a_prefix_of_one_word_is_not_a_match_for_a_phrase(self):
        for junk in ("Carrier", "Cartier", "Thejus"):
            self.assertNotIn(junk, self._names("the station car wash"))

    def test_the_thing_the_driver_asked_for_is_returned(self):
        """Two of four words, which the old ratio cut at 0.6."""
        self.assertIn("Car Wash Al Zaaim", self._names("the station car wash"))

    def test_the_short_query_was_never_broken_and_still_works(self):
        self.assertEqual(self._names("car wash")[0], "Car Wash Al Zaaim")

    def test_a_whole_word_still_carries_a_superset_query(self):
        """"starbucks city center" — one word, but matched entire, not by prefix."""
        self.assertEqual(self._names("starbucks city center")[0], "Starbucks")

    def test_a_single_prefix_still_answers_when_it_is_the_whole_query(self):
        """Typing "car" alone is a live search, not a phrase; it must answer."""
        self.assertIn("Carrier", self._names("car"))


NEAR = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5310, 25.2860]},
         "properties": {"name": "Woqod", "kind": "poi", "poi_class": "fuel"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.2326, 25.3753]},
         "properties": {"name": "Woqod", "kind": "poi", "poi_class": "fuel"}},
    ],
}


class ProximityTest(unittest.TestCase):
    """Of two equally good matches, the reachable one is the answer.

    The client has sent `lat`/`lon` on every search since V4; the server read
    neither, so a driver in Doha was offered the station in Dukhan, 30 km west.
    """

    def setUp(self):
        self.idx = GeocodeIndex.from_geojson(NEAR)

    def test_the_nearest_match_is_first(self):
        hits = self.idx.search("Woqod", near=(25.2860, 51.5310))
        self.assertAlmostEqual(hits[0].lon, 51.5310, places=3)

    def test_from_the_other_end_of_the_country_the_other_one_is_first(self):
        hits = self.idx.search("Woqod", near=(25.3753, 51.2326))
        self.assertAlmostEqual(hits[0].lon, 51.2326, places=3)

    def test_no_position_is_not_an_error(self):
        """A fix may not have arrived yet; the search still has to answer."""
        self.assertEqual(len(self.idx.search("Woqod")), 2)


class SearchQueryStringTest(unittest.TestCase):
    """The wire contract for `/search`.

    `lat`/`lon`/`lang` are what the Android client has been sending since V4.
    The handler parsed `q` and `limit` and dropped the rest on the floor, so
    this pins all five.
    """

    @classmethod
    def setUpClass(cls):
        import json as _json
        import os as _os
        import tempfile as _tempfile
        import threading as _threading
        cls.tmp = _tempfile.TemporaryDirectory()
        path = _os.path.join(cls.tmp.name, "basemap.geojson")
        with open(path, "w", encoding="utf-8") as fh:
            _json.dump(NEAR_BILINGUAL, fh)
        cls.service = GeocodeService.from_geojson(path)
        cls.server = make_server(port=0, service=cls.service, host="127.0.0.1")
        cls.port = cls.server.server_address[1]
        cls.thread = _threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)
        cls.tmp.cleanup()

    def _get(self, path):
        import urllib.request
        with urllib.request.urlopen(
                f"http://127.0.0.1:{self.port}{path}", timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))

    def _labels(self, path):
        status, body = self._get(path)
        self.assertEqual(status, 200)
        return [f["properties"]["label"] for f in body["features"]]

    def test_lat_lon_reorder_the_results(self):
        doha = self._labels("/search?q=Woqod&lat=25.2860&lon=51.5310")
        dukhan = self._labels("/search?q=Woqod&lat=25.3753&lon=51.2326")
        self.assertEqual(doha[0], "Woqod Doha")
        self.assertEqual(dukhan[0], "Woqod Dukhan")

    def test_lang_en_labels_in_english(self):
        self.assertIn("Woqod Hilal Gas Station",
                      self._labels("/search?q=Hilal&lang=en"))

    def test_a_missing_position_is_not_an_error(self):
        status, _ = self._get("/search?q=Woqod")
        self.assertEqual(status, 200)

    def test_a_malformed_position_still_answers(self):
        """A search that answers beats one that 400s on a half-written fix."""
        status, body = self._get("/search?q=Woqod&lat=&lon=abc")
        self.assertEqual(status, 200)
        self.assertTrue(body["features"])

    def test_an_empty_query_is_still_rejected(self):
        import urllib.error
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self._get("/search?q=%20")
        self.assertEqual(caught.exception.code, 400)


NEAR_BILINGUAL = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.5310, 25.2860]},
         "properties": {"name": "Woqod Doha", "kind": "poi", "poi_class": "fuel"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.2326, 25.3753]},
         "properties": {"name": "Woqod Dukhan", "kind": "poi", "poi_class": "fuel"}},
        {"type": "Feature",
         "geometry": {"type": "Point", "coordinates": [51.53547, 25.25866]},
         "properties": {"kind": "poi", "poi_class": "fuel",
                        "name": "محطة وقود الهلال",
                        "name:en": "Woqod Hilal Gas Station"}},
    ],
}
