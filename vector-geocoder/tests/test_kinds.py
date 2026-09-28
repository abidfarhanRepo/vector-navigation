"""Along-route kind resolution (V5).

The mapping in ``vector_geocoder.kinds`` translates OVERTURE Places
categories, and Overture has never been ingested. The index that is actually
loaded is built from OSM, whose categories are the bare tag values — ``fuel``,
``cafe``, ``parking``, ``pharmacy``, ``hospital``, ``hotel``. Six of those are
also user kinds, and the mapping shadowed them, so a corridor search for the
most obvious thing a driver ever asks for returned nothing at all.

Measured against the live stack before the fix: ``/along?kinds=fuel`` over the
Souq Waqif -> West Bay route returned 0 features, as did food, coffee, atm,
pharmacy and parking.
"""

import unittest

from vector_geocoder.index import unnamed_road_label
from vector_geocoder.kinds import KIND_MAP, poi_kind, resolve_kind


class ResolveKindTest(unittest.TestCase):

    def test_a_user_kind_always_matches_itself(self):
        # THE DEFECT. `fuel` is both a user kind and the OSM category, and the
        # table sent it somewhere else.
        self.assertIn("fuel", resolve_kind("fuel"))

    def test_every_osm_tag_value_that_is_also_a_user_kind_still_matches(self):
        # These are the six collisions. Each one is a real OSM `amenity` or
        # `shop` value that the index contains and that this table renames.
        for k in ("fuel", "parking", "pharmacy", "hotel", "hospital", "shop"):
            with self.subTest(kind=k):
                self.assertIn(k, resolve_kind(k), f"{k} does not match itself")

    def test_the_overture_categories_are_still_matched(self):
        # The fix adds to the mapping rather than replacing it: when Overture
        # is loaded, a place tagged fuel_station is still fuel.
        got = resolve_kind("fuel")
        for cat in KIND_MAP["fuel"]:
            self.assertIn(cat, got)

    def test_an_unknown_kind_matches_exactly(self):
        self.assertEqual({"dentist"}, resolve_kind("dentist"))

    def test_blank_matches_nothing(self):
        # An empty `kinds` parameter means "no filter", which the caller
        # expresses as an empty set — NOT as a set containing "".
        self.assertEqual(set(), resolve_kind(""))
        self.assertEqual(set(), resolve_kind("   "))

    def test_case_and_padding_are_ignored(self):
        self.assertIn("fuel", resolve_kind("  FUEL "))


if __name__ == "__main__":
    unittest.main()


class PoiKindTest(unittest.TestCase):
    """The effective category of a RAW indexed entry.

    The corridor filter reads the raw dict, which is upstream of the
    ``poi_class`` -> ``category`` rename that ``to_geojson`` performs. So every
    POI reported the basemap LAYER name, "poi", and could not match any user
    kind.
    """

    def test_an_osm_poi_reports_its_tag_value_not_its_layer(self):
        # THE DEFECT: this returned "poi" for all 8,735 of Qatar's POIs.
        raw = {"name": "Woqod", "kind": "poi", "poi_class": "fuel"}
        self.assertEqual("fuel", poi_kind(raw))

    def test_an_overture_place_still_reports_its_category(self):
        raw = {"name": "Costa", "kind": "poi", "category": "coffee_shop"}
        self.assertEqual("coffee_shop", poi_kind(raw))

    def test_category_wins_over_poi_class(self):
        raw = {"kind": "poi", "category": "coffee_shop", "poi_class": "cafe"}
        self.assertEqual("coffee_shop", poi_kind(raw))

    def test_a_road_has_no_category_and_falls_back_to_its_kind(self):
        # Roads are in the same index and legitimately carry no category;
        # falling back to the kind is what lets `kinds=road` still work.
        self.assertEqual("road", poi_kind({"name": "C Ring Road", "kind": "road"}))

    def test_the_useless_poi_class_yes_is_not_a_category(self):
        # `building=yes` and friends: `to_geojson` already refuses to emit
        # "yes" as a category, and this must agree with it.
        self.assertEqual("poi", poi_kind({"kind": "poi", "poi_class": "yes"}))

    def test_nothing_at_all(self):
        self.assertEqual("", poi_kind({}))
        self.assertEqual("", poi_kind(None))


class UnnamedRoadLabelTest(unittest.TestCase):
    """What to call a road with no name (V5).

    Reported from the S24: the road-you-are-on readout across the bottom of the
    map said **"primary_link road"**. The index needs a label for every drivable
    way so it can be found at all, and the label it used was the OSM class —
    which then reached the driver through /speed and through search results.
    """

    def test_a_link_is_a_slip_road(self):
        # THE DEFECT: this produced "primary_link road".
        self.assertEqual("slip road", unnamed_road_label("primary_link"))
        self.assertEqual("slip road", unnamed_road_label("motorway_link"))
        self.assertEqual("slip road", unnamed_road_label("trunk_link"))

    def test_service_and_track_are_service_roads(self):
        self.assertEqual("service road", unnamed_road_label("service"))
        self.assertEqual("service road", unnamed_road_label("track"))

    def test_anything_else_reads_as_a_sentence(self):
        self.assertEqual("unnamed residential road", unnamed_road_label("residential"))
        self.assertEqual("unnamed trunk road", unnamed_road_label("trunk"))

    def test_no_class_at_all(self):
        self.assertEqual("unnamed road", unnamed_road_label(None))
        self.assertEqual("unnamed road", unnamed_road_label(""))
        self.assertEqual("unnamed road", unnamed_road_label("   "))

    def test_no_underscore_ever_reaches_a_driver(self):
        # The whole class of defect, asserted as a class rather than case by
        # case: every OSM highway value this index can hold, and none of them
        # may produce a label with a database identifier in it.
        for hw in ("motorway", "trunk", "primary", "secondary", "tertiary",
                   "unclassified", "residential", "living_street", "service",
                   "track", "motorway_link", "trunk_link", "primary_link",
                   "secondary_link", "tertiary_link"):
            with self.subTest(highway=hw):
                self.assertNotIn("_", unnamed_road_label(hw))

    def test_the_vocabulary_matches_the_router_word_for_word(self):
        # `vector_routing.router._road_label` says "the slip road" for a link,
        # and the maneuver banner and this readout sit four millimetres apart
        # on the same screen. The two services cannot import each other, so
        # this is the pin.
        #
        # The article differs on purpose: the router's output is a sentence
        # ("Continue on the slip road") and this one is a label ("slip road").
        self.assertEqual("the " + unnamed_road_label("primary_link"), "the slip road")
        self.assertEqual("the " + unnamed_road_label("service"), "the service road")
