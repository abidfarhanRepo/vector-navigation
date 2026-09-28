"""Turn-by-turn quality: maneuvers, not a geometry dump.

`_build_steps` used to emit ONE STEP PER VERTEX. Measured against the live Doha
stack, a 10.4 km route returned **315 steps, 305 of them "continue"**, 35 of them
under five metres, with instructions like "Continue on trunk road" and "Turn
right onto footway road". A driver would be told to continue three hundred times.

After merging: 14 steps, 5 "continue", 1 short step, and real street names.

These tests pin that, because the regression is invisible to every other test in
this suite — the old behaviour satisfied "steps exist and are monotonic".
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor"))

from vector_geo.graph import RoutingGraph
from vector_routing.service import RoutingService
from vector_routing.router import _classify_turn, _road_label, normalize_angle


M_PER_DEG = 111_320.0


def _straight_way(graph, lat, lon0, n, spacing_m, name=None, highway="primary"):
    """A straight east-running way sampled every `spacing_m`."""
    kx = math.cos(math.radians(lat)) * M_PER_DEG
    coords = [(lon0 + (i * spacing_m) / kx, lat) for i in range(n)]
    props = {"highway": highway, "maxspeed": 60}
    if name:
        props["name"] = name
    graph.add_way(coords, props)
    return coords


class ManeuverMergingTest(unittest.TestCase):
    LAT = 25.2854
    LON = 51.5310

    def test_a_straight_road_is_one_maneuver_not_one_per_vertex(self):
        """THE regression. A 2 km straight road sampled every 20 m is 100
        vertices and exactly zero decisions for the driver."""
        g = RoutingGraph()
        coords = _straight_way(g, self.LAT, self.LON, 101, 20.0, name="Corniche Street")
        svc = RoutingService(g)
        res = svc.navigate(coords[0], coords[-1])
        steps = res["steps"]
        self.assertEqual(
            len(steps), 2,
            "a straight road must be depart + arrive, got %d steps" % len(steps),
        )
        self.assertEqual(steps[0]["type"], "depart")
        self.assertEqual(steps[-1]["type"], "arrive")

    def test_step_distance_is_the_distance_to_the_NEXT_maneuver(self):
        """What a navigator announces ("in 400 m, turn right") and what the
        client's countdown consumes. Previously it was one segment length."""
        g = RoutingGraph()
        coords = _straight_way(g, self.LAT, self.LON, 101, 20.0, name="Corniche Street")
        svc = RoutingService(g)
        res = svc.navigate(coords[0], coords[-1])
        depart = res["steps"][0]
        self.assertAlmostEqual(depart["distance_m"], res["distance_m"], delta=1.0)

    def test_a_real_turn_is_kept(self):
        g = RoutingGraph()
        kx = math.cos(math.radians(self.LAT)) * M_PER_DEG
        east = [(self.LON + (i * 50.0) / kx, self.LAT) for i in range(11)]
        g.add_way(east, {"highway": "primary", "name": "East Road", "maxspeed": 60})
        corner = east[-1]
        north = [(corner[0], self.LAT + (i * 50.0) / M_PER_DEG) for i in range(11)]
        g.add_way(north, {"highway": "primary", "name": "North Road", "maxspeed": 60})

        svc = RoutingService(g)
        res = svc.navigate(east[0], north[-1])
        types = [s["type"] for s in res["steps"]]
        self.assertIn("turn-left", types, "the 90 degree corner must produce a turn: %s" % types)
        self.assertEqual(len(res["steps"]), 3, "expected depart + turn + arrive, got %s" % types)

    def test_a_road_name_change_without_a_turn_is_announced(self):
        """Straight on, but the street becomes another street — a driver needs
        to hear it, and it is how "continue onto X" exists at all."""
        g = RoutingGraph()
        kx = math.cos(math.radians(self.LAT)) * M_PER_DEG
        a = [(self.LON + (i * 50.0) / kx, self.LAT) for i in range(6)]
        b = [(self.LON + ((5 + i) * 50.0) / kx, self.LAT) for i in range(6)]
        g.add_way(a, {"highway": "primary", "name": "First Street", "maxspeed": 60})
        g.add_way(b, {"highway": "primary", "name": "Second Street", "maxspeed": 60})
        svc = RoutingService(g)
        res = svc.navigate(a[0], b[-1])
        instructions = " | ".join(s["instruction"] for s in res["steps"])
        self.assertIn("Second Street", instructions, instructions)
        self.assertEqual(len(res["steps"]), 3, instructions)

    def test_cumulative_distance_stays_monotonic_after_merging(self):
        g = RoutingGraph()
        coords = _straight_way(g, self.LAT, self.LON, 51, 40.0, name="Long Road")
        svc = RoutingService(g)
        res = svc.navigate(coords[0], coords[-1])
        cum = [s["cumulative_distance_m"] for s in res["steps"]]
        self.assertEqual(cum, sorted(cum))
        self.assertAlmostEqual(cum[-1], res["distance_m"], delta=1.0)

    def test_step_distances_sum_to_the_route_distance(self):
        """If merging drops or double-counts a leg this is where it shows."""
        g = RoutingGraph()
        kx = math.cos(math.radians(self.LAT)) * M_PER_DEG
        east = [(self.LON + (i * 50.0) / kx, self.LAT) for i in range(11)]
        g.add_way(east, {"highway": "primary", "name": "East Road", "maxspeed": 60})
        corner = east[-1]
        north = [(corner[0], self.LAT + (i * 50.0) / M_PER_DEG) for i in range(11)]
        g.add_way(north, {"highway": "primary", "name": "North Road", "maxspeed": 60})
        svc = RoutingService(g)
        res = svc.navigate(east[0], north[-1])
        total = sum(s["distance_m"] for s in res["steps"])
        self.assertAlmostEqual(total, res["distance_m"], delta=1.0)

    def test_first_step_departs_and_last_step_arrives(self):
        g = RoutingGraph()
        coords = _straight_way(g, self.LAT, self.LON, 21, 30.0, name="Some Road")
        svc = RoutingService(g)
        res = svc.navigate(coords[0], coords[-1])
        self.assertEqual(res["steps"][0]["type"], "depart")
        self.assertEqual(res["steps"][-1]["type"], "arrive")
        self.assertEqual(res["steps"][-1]["distance_m"], 0.0)


class RoadLabelTest(unittest.TestCase):
    def test_a_named_road_is_called_by_its_name(self):
        self.assertEqual(_road_label("trunk", "Al Corniche Street"), "Al Corniche Street")

    def test_an_unnamed_road_is_described_in_driver_vocabulary(self):
        # REWRITTEN in V6, and the behaviour it asserted was the defect.
        #
        # It required `_road_label("trunk", None) == "the trunk road"` and
        # `_road_label("living_street", None) == "the living street road"` —
        # the second of which is not English, and the first of which puts an
        # OSM classification into a sentence a driver hears while driving.
        # Observed live on the running stack as *"Bear left to stay on the
        # tertiary road"*.
        #
        # The class is now translated (see `_ROAD_WORDS`): every distinction a
        # driver can see from the windscreen is kept, and the ones they cannot
        # see are collapsed to "the road". No coverage is lost — the assertions
        # below pin more classes than the two this replaced.
        self.assertEqual(_road_label("motorway", None), "the motorway")
        self.assertEqual(_road_label("trunk", None), "the main road")
        self.assertEqual(_road_label("primary", None), "the main road")
        self.assertEqual(_road_label("tertiary", None), "the road")
        self.assertEqual(_road_label("living_street", None), "the road")
        self.assertEqual(_road_label("service", None), "the service road")

    def test_no_raw_class_or_underscore_ever_reaches_a_driver(self):
        # The property that matters, asserted over every class the Qatar
        # extract actually contains rather than over a chosen few. This is
        # what would have caught "the living street road" and "primary_link
        # road" without anyone having to think of them.
        classes = [
            "motorway", "trunk", "primary", "secondary", "tertiary",
            "unclassified", "residential", "living_street", "service", "track",
            "road", "motorway_link", "trunk_link", "primary_link",
            "secondary_link", "tertiary_link", "footway", "pedestrian",
            "busway", "corridor", "some_tag_nobody_has_seen",
        ]
        # Stated as a CLOSED SET rather than as "the class name must not
        # appear", which was the first version of this assertion and was wrong:
        # "the motorway" legitimately contains "motorway", because motorway is
        # the driver's word as well as OSM's. What actually matters is that the
        # output is always one of a handful of phrases a person would say — so
        # the set is the assertion, and adding a class without deciding what to
        # call it fails here.
        allowed = {
            "the motorway", "the main road", "the road",
            "the service road", "the track", "the slip road",
        }
        for hw in classes:
            label = _road_label(hw, None)
            self.assertNotIn("_", label, f"{hw} leaked an underscore: {label}")
            self.assertIn(label, allowed, f"{hw} -> {label!r} is not driver vocabulary")

    def test_a_link_is_called_a_slip_road(self):
        self.assertEqual(_road_label("motorway_link", None), "the slip road")
        self.assertEqual(_road_label("primary_link", None), "the slip road")

    def test_no_class_at_all_is_still_speakable(self):
        self.assertEqual(_road_label(None, None), "the road")


class TurnClassificationTest(unittest.TestCase):
    def test_small_deviations_are_not_turns(self):
        for d in (-19.0, -5.0, 0.0, 5.0, 19.0):
            self.assertEqual(_classify_turn(d), "continue", "delta %s" % d)

    def test_turn_directions_follow_the_sign(self):
        self.assertEqual(_classify_turn(-30.0), "slight-left")
        self.assertEqual(_classify_turn(30.0), "slight-right")
        self.assertEqual(_classify_turn(-90.0), "turn-left")
        self.assertEqual(_classify_turn(90.0), "turn-right")

    def test_a_reversal_is_a_uturn(self):
        self.assertEqual(_classify_turn(170.0), "uturn")
        self.assertEqual(_classify_turn(-170.0), "uturn")

    def test_angle_normalisation_is_half_open(self):
        self.assertEqual(normalize_angle(180.0), 180.0)
        self.assertEqual(normalize_angle(-180.0), 180.0)
        self.assertEqual(normalize_angle(370.0), 10.0)


class BilingualNameTest(unittest.TestCase):
    """Road names in the language the driver reads.

    Reported from the S24: *"the names of the roads came in arabic, it has to be
    in english too."*

    Measured on the live Qatar graph: **48,427 of 48,680 named roads carry
    `name:en`** — 99.5% — and nothing read it. Every instruction and every route
    label was built from `name` alone, so an English-language phone was told
    "Continue on شارع الكورنيش" and offered a route "via العروبة". Qatar's road
    signs are bilingual and the data has been bilingual the whole time.
    """

    AR_1 = "شارع العروبة"
    AR_2 = "شارع حالول"

    def _graph(self):
        g = RoutingGraph()
        a, b, c = pt(0, 0), pt(600, 0), pt(600, 600)
        g.add_way(line(a, b, 7), {"highway": "primary", "name": self.AR_1,
                                  "name:en": "Al Arouba Street", "maxspeed": 60})
        g.add_way(line(b, c, 7), {"highway": "primary", "name": self.AR_2,
                                  "name:en": "Halul Street", "maxspeed": 60})
        return g, a, c

    def test_english_instructions_when_asked_for(self):
        g, a, c = self._graph()
        steps = RoutingService(g).navigate(a, c, lang="en")["steps"]
        text = " ".join(s["instruction"] for s in steps)
        self.assertIn("Al Arouba Street", text)
        self.assertNotIn(self.AR_1, text)

    def test_the_local_name_remains_the_default(self):
        # Omitting `lang` must keep the previous behaviour exactly: this is a
        # live service with an existing client.
        g, a, c = self._graph()
        steps = RoutingService(g).navigate(a, c)["steps"]
        text = " ".join(s["instruction"] for s in steps)
        self.assertIn(self.AR_1, text)

    def test_both_raw_names_travel_on_every_step(self):
        # So a client can relabel without another round trip, and nothing has to
        # guess which language `road` happens to hold.
        g, a, c = self._graph()
        steps = RoutingService(g).navigate(a, c, lang="en")["steps"]
        named = [s for s in steps if s.get("road_local")]
        self.assertTrue(named)
        self.assertIn(self.AR_1, [s["road_local"] for s in named])
        self.assertIn("Al Arouba Street", [s["road_en"] for s in named])

    def test_a_road_with_no_english_name_falls_back_rather_than_going_blank(self):
        # 253 of Qatar's named roads have no `name:en`. "Continue on شارع X" is
        # useful to everyone; "Continue on the primary road" is useful to nobody.
        g = RoutingGraph()
        a, b = pt(0, 0), pt(600, 0)
        g.add_way(line(a, b, 7), {"highway": "primary", "name": self.AR_2,
                                  "maxspeed": 60})
        steps = RoutingService(g).navigate(a, b, lang="en")["steps"]
        text = " ".join(s["instruction"] for s in steps)
        self.assertIn(self.AR_2, text)

    def test_the_route_label_agrees_with_the_instructions(self):
        # A route offered "via Al Arouba" whose instructions then say
        # "شارع العروبة" is worse than either language used consistently.
        g, a, c = self._graph()
        answers = RoutingService(g).navigate_alternatives(a, c, wanted=2, lang="en")
        label = answers[0]["label"]
        if label:
            self.assertNotIn("شارع", label, f"label {label!r} is not in English")

    def test_road_identity_does_not_change_with_language(self):
        # `identity()` decides "am I still on the same road?", which drives the
        # "bear right to stay on" phrasing and the maneuver merging. It must
        # give the same answer in every language, or one route would merge
        # differently for an English and an Arabic client.
        g, a, c = self._graph()
        svc = RoutingService(g)
        local = svc.navigate(a, c)["steps"]
        english = svc.navigate(a, c, lang="en")["steps"]
        self.assertEqual(len(local), len(english))
        self.assertEqual([s["type"] for s in local], [s["type"] for s in english])


if __name__ == "__main__":
    unittest.main()


# Local metric helpers: the same construction test_maneuver_matrix.py uses, kept
# here so each file stands alone under `unittest discover`.
_LAT, _LON = 25.2854, 51.5310
_MPD = 111_320.0
_KX = math.cos(math.radians(_LAT)) * _MPD


def pt(east_m, north_m):
    return (_LON + east_m / _KX, _LAT + north_m / _MPD)


def line(a, b, n=6):
    """`n` points from a to b inclusive."""
    return [(a[0] + (b[0] - a[0]) * i / (n - 1), a[1] + (b[1] - a[1]) * i / (n - 1))
            for i in range(n)]


class SameRoadPhrasingTest(unittest.TestCase):
    """"Onto" means you are joining a different road.

    A bend in the carriageway announced as "Slight right onto Al Akhdari" while
    already on Al Akhdari tells the driver to leave the road they are on and
    rejoin it. They start looking for a side road that does not exist. Seen on
    the live Education City -> Corniche route, twice on one road.
    """

    def _bend(self, name_a, name_b, dy):
        """A bend AT A JUNCTION — a side road makes it a decision.

        Without the side road the bend is not announced at all (a driver cannot
        get it wrong), which is tested in `test_maneuver_matrix.py`.
        """
        g = RoutingGraph()
        a, b = pt(0, 0), pt(400, 0)
        c = pt(400 + 430, dy)
        g.add_way(line(a, b), {"highway": "primary", "name": name_a, "maxspeed": 60})
        g.add_way(line(b, c), {"highway": "primary", "name": name_b, "maxspeed": 60})
        g.add_way(line(b, pt(800, 0)), {"highway": "primary", "name": "Side", "maxspeed": 60})
        return RoutingService(g).navigate(a, c)

    def test_a_bend_on_the_same_road_says_stay_on_it(self):
        text = " | ".join(s["instruction"] for s in self._bend("Al Akhdari", "Al Akhdari", 250)["steps"])
        self.assertIn("stay on Al Akhdari", text, text)
        self.assertNotIn("onto Al Akhdari", text, text)

    def test_a_bend_onto_a_different_road_still_says_onto(self):
        text = " | ".join(s["instruction"] for s in self._bend("First", "Second", 250)["steps"])
        self.assertIn("onto Second", text, text)

    def test_a_real_turn_that_stays_on_the_road_reads_as_keep(self):
        text = " | ".join(s["instruction"] for s in self._bend("Corniche", "Corniche", 500)["steps"])
        self.assertIn("stay on Corniche", text, text)


class UturnSlotTest(unittest.TestCase):
    """A dual-carriageway turnaround is ONE maneuver.

    Doha's arterials are dual carriageways with dedicated U-turn slots, and the
    slot geometry is two ~90-degree bends about 20 m apart. The bearing change
    across each reading sits on the +/-180 wrap, so a same-SIGN grouping test
    saw +178 and -179 as opposite directions and emitted "Make a U-turn" twice,
    23 m apart — an instruction nobody can follow. Grouping compares the two
    readings as an ANGLE, which is wrap-safe.
    """

    def test_a_turnaround_slot_produces_one_uturn(self):
        g = RoutingGraph()
        a = pt(0, 0)
        # East along the northern carriageway, through the slot, back west along
        # the southern one 12 m away.
        slot = [pt(400, 0), pt(404, 0), pt(408, 4), pt(406, 10), pt(400, 12), pt(0, 12)]
        g.add_way(line(a, pt(400, 0)), {"highway": "primary", "name": "Khalifa", "maxspeed": 80})
        g.add_way(slot, {"highway": "primary", "name": "Khalifa", "maxspeed": 80})
        types = [s["type"] for s in RoutingService(g).navigate(a, slot[-1])["steps"]]
        self.assertEqual(types.count("uturn"), 1,
                         f"one turnaround became {types.count('uturn')} instructions: {types}")
