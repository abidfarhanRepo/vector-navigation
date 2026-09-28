"""Road classification, class by class.

Regression cover for the defect that put 42,455 pedestrian ways into the CAR
routing graph — 15,256 footways, 380 flights of steps, 1,349 roads under
construction — and produced a Doha route beginning "Head northwest on footway
road".

The tests are deliberately written per highway class, because the failure was
not an algorithm bug: it was a single whitelist answering three different
questions at once.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_ingestion.classify import (  # noqa: E402
    barrier_pedestrian_effect, car_access_denied, classify, foot_access_denied,
    is_car_routable, is_pedestrian_routable, is_renderable,
)


def hw(value, **extra):
    d = {"highway": value}
    d.update(extra)
    return d


class CarRoutableClassTest(unittest.TestCase):
    DRIVABLE = [
        "motorway", "motorway_link", "trunk", "trunk_link",
        "primary", "primary_link", "secondary", "secondary_link",
        "tertiary", "tertiary_link", "unclassified", "residential",
        "living_street", "service", "track", "road",
    ]
    NOT_DRIVABLE = [
        "footway", "pedestrian", "steps", "path", "cycleway",
        "bridleway", "corridor", "construction", "proposed",
    ]

    def test_drivable_classes_are_car_routable(self):
        for c in self.DRIVABLE:
            self.assertTrue(is_car_routable(hw(c)), f"{c} should be car-routable")

    def test_pedestrian_and_nonexistent_classes_are_not(self):
        for c in self.NOT_DRIVABLE:
            self.assertFalse(is_car_routable(hw(c)), f"{c} must NOT be car-routable")

    def test_a_staircase_is_never_a_car_edge(self):
        """The headline bug: 380 flights of steps were in the driving graph."""
        self.assertFalse(is_car_routable(hw("steps")))
        self.assertFalse(is_car_routable(hw("steps", access="yes")))

    def test_a_road_under_construction_is_not_routable(self):
        self.assertFalse(is_car_routable(hw("construction")))
        # ...including the other tagging form, where the class looks normal.
        self.assertFalse(is_car_routable(hw("primary", construction="widening")))

    def test_service_roads_and_parking_aisles_stay_routable(self):
        # These reach real destinations. Excluding "anything unusual" would make
        # every car park and drive-through unreachable.
        self.assertTrue(is_car_routable(hw("service")))
        self.assertTrue(is_car_routable(hw("service", service="parking_aisle")))
        self.assertTrue(is_car_routable(hw("service", service="driveway")))

    def test_tracks_stay_routable(self):
        # Unsealed but drivable, and common in Qatar outside the city.
        self.assertTrue(is_car_routable(hw("track")))

    def test_a_way_with_no_highway_tag_is_not_a_road(self):
        self.assertFalse(is_car_routable({}))
        self.assertFalse(is_car_routable({"building": "yes"}))


class CarAccessTest(unittest.TestCase):
    def test_private_and_no_are_excluded(self):
        self.assertFalse(is_car_routable(hw("service", access="private")))
        self.assertFalse(is_car_routable(hw("residential", access="no")))
        self.assertFalse(is_car_routable(hw("service", motor_vehicle="no")))
        self.assertFalse(is_car_routable(hw("residential", vehicle="no")))

    def test_destination_access_is_ALLOWED(self):
        # "only if you are going there" is precisely what a navigation
        # destination is. Excluding it makes addresses unreachable.
        self.assertTrue(is_car_routable(hw("residential", access="destination")))
        self.assertTrue(is_car_routable(hw("service", motor_vehicle="destination")))

    def test_a_specific_permission_overrides_a_general_denial(self):
        # access=private + motor_vehicle=yes means a car MAY use it. This is how
        # OSM access tagging is specified, and getting it backwards silently
        # deletes legitimate roads.
        self.assertTrue(is_car_routable(hw("service", access="private", motor_vehicle="yes")))
        self.assertTrue(is_car_routable(hw("service", access="no", motorcar="permissive")))

    def test_a_general_denial_still_applies_without_a_specific_permission(self):
        self.assertTrue(car_access_denied(hw("service", access="private")))
        self.assertFalse(car_access_denied(hw("service", access="destination")))
        self.assertFalse(car_access_denied(hw("service")))

    def test_permissive_and_designated_count_as_allowed(self):
        self.assertTrue(is_car_routable(hw("track", access="permissive")))
        self.assertTrue(is_car_routable(hw("service", motor_vehicle="designated")))


class PedestrianRoutableTest(unittest.TestCase):
    def test_pedestrian_infrastructure_is_walkable(self):
        for c in ("footway", "path", "steps", "pedestrian", "corridor", "bridleway"):
            self.assertTrue(is_pedestrian_routable(hw(c)), c)

    def test_motorways_are_not_walkable_by_default(self):
        self.assertFalse(is_pedestrian_routable(hw("motorway")))
        self.assertFalse(is_pedestrian_routable(hw("trunk")))

    def test_a_motorway_explicitly_tagged_foot_yes_is_walkable(self):
        # Happens on causeways and some bridges.
        self.assertTrue(is_pedestrian_routable(hw("trunk", foot="yes")))

    def test_shared_classes_are_both(self):
        for c in ("residential", "living_street", "service", "track"):
            self.assertTrue(is_car_routable(hw(c)), c)
            self.assertTrue(is_pedestrian_routable(hw(c)), c)

    def test_foot_no_excludes_a_footpath(self):
        self.assertFalse(is_pedestrian_routable(hw("footway", foot="no")))
        self.assertTrue(foot_access_denied(hw("footway", access="private")))

    def test_a_staircase_is_walkable_and_not_drivable(self):
        """The inverse of ``test_a_staircase_is_never_a_car_edge``, stated once.

        The car half of this pair was the headline bug — 380 flights of steps in
        the driving graph. The walking half is what the pedestrian graph is
        built on: steps have to be routable for a footbridge to be anything
        other than a wall. Both halves in one place so neither can be "fixed"
        into agreeing with the other.
        """
        self.assertTrue(is_pedestrian_routable(hw("steps")))
        self.assertFalse(is_car_routable(hw("steps")))

    def test_an_arterial_is_walkable_only_where_the_survey_says_so(self):
        """Why 543 Qatar arterials are in the pedestrian graph, and the rest are not.

        A bare `primary` is not walkable: no pavement is implied by the class.
        A `primary` that a surveyor tagged `foot=yes` — typically alongside
        `sidewalk=right` — is, and Qatar has 543 such ways across the arterial
        classes (135 primary, 109 secondary_link, 99 primary_link, 87 secondary,
        68 trunk, 42 trunk_link, 3 motorway_link). Al Corniche Street is one of
        them. Refusing them would overrule the survey with a guess and leave no
        walking route along the Corniche at all.

        A motorway stays out either way in this extract: none carries the tag.
        """
        self.assertFalse(is_pedestrian_routable(hw("primary")))
        self.assertFalse(is_pedestrian_routable(hw("primary", sidewalk="right")))
        self.assertTrue(is_pedestrian_routable(hw("primary", foot="yes", sidewalk="right")))
        self.assertTrue(is_pedestrian_routable(hw("trunk", foot="yes")))
        self.assertFalse(is_pedestrian_routable(hw("motorway")))


class RenderableTest(unittest.TestCase):
    def test_everything_with_a_highway_tag_is_drawn(self):
        # Including things no car may use. Removing footpaths from the MAP
        # because cars cannot drive them would be a different kind of wrong.
        for c in ("footway", "steps", "construction", "motorway", "service"):
            self.assertTrue(is_renderable(hw(c)), c)

    def test_a_non_highway_is_not_a_road_feature(self):
        self.assertFalse(is_renderable({"building": "yes"}))

    def test_pedestrian_ways_are_renderable_but_not_car_routable(self):
        """The separation, stated as one assertion."""
        for c in ("footway", "steps", "path", "cycleway", "pedestrian"):
            self.assertTrue(is_renderable(hw(c)), c)
            self.assertFalse(is_car_routable(hw(c)), c)


class BarrierPedestrianEffectTest(unittest.TestCase):
    """The barrier node -> walking-graph statement (V7.4 4A).

    The recon's anchor measurement is on real Qatar data: 2,164 gate nodes
    tagged ``access=private/no`` or ``locked=yes`` sit on foot-routable ways,
    and their tag was dropped before the routing graph, so Vector confidently
    walked people through locked gates. These tests pin the classification
    that fixes it -- and, just as importantly, pin what is NOT blocked, because
    the task's warning is about the mirror-image failure: deleting every
    barrier node would sever 5,692 legitimate gates and turn correct routes
    into false "not connected" refusals.
    """

    def test_an_unmarked_gate_is_passable_by_default(self):
        """OSM's default is permissive: a gate exists to stop vehicles."""
        self.assertEqual(barrier_pedestrian_effect({"barrier": "gate"}), "pass")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "lift_gate"}), "pass")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "swing_gate"}), "pass")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "sliding_gate"}), "pass")

    def test_the_qatar_headline_a_private_or_locked_gate_blocks(self):
        """The 2,164. access=private/no or locked=yes on a gate is a BLOCK."""
        self.assertEqual(barrier_pedestrian_effect({"barrier": "gate", "access": "private"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "gate", "access": "no"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "lift_gate", "access": "private"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "sliding_gate", "locked": "yes"}), "block")

    def test_access_denial_blocks_any_barrier_kind(self):
        """The kind only supplies the default; a denial on any kind is real."""
        self.assertEqual(barrier_pedestrian_effect({"barrier": "bollard", "access": "private"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "block", "foot": "no"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "gate", "foot": "private"}), "block")

    def test_explicit_foot_permission_beats_an_access_denial(self):
        """Specificity order: foot=yes settles it, exactly like way access."""
        self.assertEqual(
            barrier_pedestrian_effect({"barrier": "gate", "access": "private", "foot": "yes"}),
            "pass")
        self.assertEqual(
            barrier_pedestrian_effect({"barrier": "gate", "access": "private", "foot": "designated"}),
            "pass")

    def test_vehicle_only_barriers_are_passable_on_foot(self):
        """The 463 (bollard, block, cattle_grid, kerb, turnstile, cycle_barrier)."""
        for kind in ("bollard", "block", "cattle_grid", "kerb", "turnstile",
                     "cycle_barrier", "entrance", "stile", "jersey_barrier", "chain"):
            self.assertEqual(barrier_pedestrian_effect({"barrier": kind}), "pass", kind)

    def test_a_solid_barrier_blocks_without_an_access_tag(self):
        """A wall is a wall. Rare on foot ways, but blocking is the honest read."""
        self.assertEqual(barrier_pedestrian_effect({"barrier": "fence"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "wall"}), "block")

    def test_customers_and_permit_count_as_denied(self):
        """The shared _DENIED vocabulary, applied to a gate."""
        self.assertEqual(barrier_pedestrian_effect({"barrier": "gate", "access": "customers"}), "block")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "gate", "access": "permit"}), "block")

    def test_unknown_kinds_default_to_pass(self):
        """A barrier kind nobody has seen is not a wall until proven one."""
        self.assertEqual(barrier_pedestrian_effect({"barrier": "hampshire_gate"}), "pass")
        self.assertEqual(barrier_pedestrian_effect({"barrier": "yes"}), "pass")


class ClassifyTest(unittest.TestCase):
    def test_classify_returns_all_three_answers(self):
        self.assertEqual(
            classify(hw("footway")),
            {"renderable": True, "car": False, "foot": True},
        )
        self.assertEqual(
            classify(hw("motorway")),
            {"renderable": True, "car": True, "foot": False},
        )
        self.assertEqual(
            classify(hw("residential")),
            {"renderable": True, "car": True, "foot": True},
        )
        self.assertEqual(
            classify(hw("construction")),
            {"renderable": True, "car": False, "foot": False},
        )




class CoastlineTest(unittest.TestCase):
    """Qatar is a peninsula, and without a coastline it has no shape.

    Verified on an S24 Ultra at country zoom once the low zooms were baked: the
    road network rendered correctly and floated in a black void, because the sea
    and the land are both the style's background colour.

    The classification has to happen BEFORE the generic `natural` mapping, and
    that ordering is the whole test. `natural=coastline` reaching the area
    branch closes an OPEN way into a ring, producing a polygon spanning
    whatever its two loose ends happen to be — a large spurious fill rather
    than a shoreline.
    """

    def test_a_coastline_is_its_own_kind(self):
        from vector_ingestion.osm import _basemap_kind
        self.assertEqual(_basemap_kind({"natural": "coastline"}), "coastline")

    def test_other_natural_features_are_unaffected(self):
        from vector_ingestion.osm import _basemap_kind
        self.assertEqual(_basemap_kind({"natural": "wood"}), "natural")
        self.assertEqual(_basemap_kind({"natural": "sand"}), "natural")

    def test_a_coastline_is_not_an_area(self):
        # The `is_area` tuple in parse_osm decides whether a way is closed into
        # a polygon. "coastline" must not be in it.
        from vector_ingestion.osm import parse_osm
        xml = """<?xml version="1.0"?>
        <osm version="0.6">
          <node id="1" lat="25.30" lon="51.50"/>
          <node id="2" lat="25.31" lon="51.52"/>
          <node id="3" lat="25.33" lon="51.53"/>
          <way id="10">
            <nd ref="1"/><nd ref="2"/><nd ref="3"/>
            <tag k="natural" v="coastline"/>
          </way>
        </osm>"""
        feats = parse_osm(xml)["features"]
        coast = [f for f in feats if (f["properties"] or {}).get("kind") == "coastline"]
        self.assertEqual(len(coast), 1, "the coastline way was not emitted")
        self.assertEqual(
            coast[0]["geometry"]["type"], "LineString",
            "a coastline was closed into a polygon; an open way became a spurious fill",
        )
        # And the geometry is not silently closed.
        coords = coast[0]["geometry"]["coordinates"]
        self.assertNotEqual(coords[0], coords[-1])


if __name__ == "__main__":
    unittest.main()
