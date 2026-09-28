"""Deterministic geometry fixtures for the V8 lane layer (cases A-J).

Every fixture is built on a local metre grid and converted to lon/lat near
Doha's latitude, so a distance asserted here is a distance on the ground.
Each test states the expected behaviour in its name and checks it against the
production `build_lanes`, not a re-implementation.
"""
import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_tile_gen import lanes as L  # noqa: E402

LON0, LAT0 = 51.50, 25.30
# Spherical Web Mercator metres — the frame the style's lane width and the
# bake's offsets are both defined in (not WGS84 ellipsoidal ground metres,
# which differ north-south by ~0.7%).
KY = 6378137.0 * math.pi / 180.0
KX = KY * math.cos(math.radians(LAT0))


def ll(x, y):
    return (LON0 + x / KX, LAT0 + y / KY)


def xy(lon, lat):
    return ((lon - LON0) * KX, (lat - LAT0) * KY)


class F:
    def __init__(self, fid, coords, props):
        self.id = fid
        self.geometry_type = "LineString"
        self.coordinates = [list(c) for c in coords]
        self.properties = dict({"kind": "road", "car": True}, **props)


class Net:
    """A tiny network: named nodes on a metre grid, ways as node lists."""

    def __init__(self):
        self.nodes = {}
        self.ways = []
        self.attrs = {}
        self._next = 1000

    def node(self, name, x, y):
        self._next += 1
        self.nodes[name] = (self._next, ll(x, y))
        return name

    def way(self, wid, names, attrs=None, **props):
        props.setdefault("highway", "primary")
        coords = [self.nodes[n][1] for n in names]
        self.ways.append(F(f"w{wid}", coords, props))
        rec = {"nodes": [self.nodes[n][0] for n in names]}
        rec.update(attrs or {})
        self.attrs[f"w{wid}"] = rec

    def build(self):
        return L.build_lanes(self.ways, self.attrs)


def runs_of(res, wid):
    return [r for r in res.runs if r.way_osm_id == wid]


def total(res, wid):
    return sum(r.length_m for r in runs_of(res, wid))


def dist_to(r, pt):
    """Min ground distance from a run's vertices to a point (x, y metres)."""
    return min(math.hypot(xy(*c)[0] - pt[0], xy(*c)[1] - pt[1]) for c in r.coords)


def seg(res, wid):
    return [s for s in res.segments if res.ways[s.way].osm_id == wid]


class CaseA_StraightMultilane(unittest.TestCase):
    def test_dividers_run_the_full_length_at_lane_spacing(self):
        n = Net()
        n.node("a", 0, 0); n.node("b", 250, 0); n.node("c", 500, 0)
        n.way(1, ["a", "b", "c"], lanes="3", oneway="yes")
        res = n.build()
        rs = runs_of(res, 1)
        self.assertEqual([r.pos for r in rs], [1, 2])
        for r in rs:
            self.assertAlmostEqual(r.length_m, 500.0, delta=0.5)   # no clipping at all
            self.assertEqual(r.cls, "lane")
        y = sorted(xy(*r.coords[0])[1] for r in rs)
        self.assertAlmostEqual(y[1] - y[0], 3.5, delta=0.05)
        self.assertAlmostEqual(y[0], -1.75, delta=0.05)           # symmetric about the way

    def test_a_turn_lanes_way_is_solid_and_a_two_way_pair_has_a_centre(self):
        n = Net()
        n.node("a", 0, 0); n.node("b", 300, 0); n.node("c", 0, 50); n.node("d", 300, 50)
        n.way(1, ["a", "b"], lanes="3", oneway="yes", **{"turn:lanes": "left||right"})
        n.way(2, ["c", "d"], lanes="2", highway="residential")
        res = n.build()
        self.assertEqual({r.cls for r in runs_of(res, 1)}, {"solid"})
        self.assertEqual([(r.pos, r.cls) for r in runs_of(res, 2)], [(1, "centre")])


class CaseB_FourToOne(unittest.TestCase):
    def test_at_a_junction_the_wide_approach_is_drawn_and_stops_at_the_mouth(self):
        # 4 lanes meeting a crossing road and continuing as 1: the crossing
        # makes it a JUNCTION (lanes differ because traffic turns), so the
        # approach is an approach — drawn, and stopped before the conflict area.
        n = Net()
        n.node("a", -250, 0); n.node("j", 0, 0); n.node("b", 200, 0)
        n.node("n", 0, 150); n.node("s", 0, -150)
        n.way(1, ["a", "j"], lanes="4", oneway="yes")
        n.way(2, ["j", "b"], lanes="1", oneway="yes", highway="primary_link")
        n.way(3, ["n", "j", "s"], lanes="2", highway="secondary")
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 1)}, {"oneway"})
        self.assertEqual(runs_of(res, 2), [])                          # one lane
        trim = 2 * 3.5 / 2 + L.JUNCTION_MARGIN_M     # widest OTHER way there: the 2-lane crossing
        for r in runs_of(res, 1):
            end_x = max(xy(*c)[0] for c in r.coords)
            self.assertAlmostEqual(end_x, -trim, delta=0.05)       # a stop line, not a radius
        for r in runs_of(res, 3):
            self.assertGreaterEqual(dist_to(r, (0, 0)), 4 * 3.5 / 2 + L.JUNCTION_MARGIN_M - 0.3)

    def test_a_lane_drop_with_nothing_to_explain_it_is_a_step(self):
        # 4 -> 1 at a plain split, nothing leaving: three lanes vanish.
        n = Net()
        n.node("a", -250, 0); n.node("s", 0, 0); n.node("b", 200, 0)
        n.way(1, ["a", "s"], lanes="4", oneway="yes")
        n.way(2, ["s", "b"], lanes="1", oneway="yes")
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 1)}, {"lane_step_wider_side"})
        self.assertEqual(runs_of(res, 1), [])

    def test_a_diverge_withholds_the_wider_approach_near_the_split(self):
        # 4 lanes -> 3-lane mainline + 1-lane exit ramp at 12 degrees: the ramp
        # explains the lane, not where the 4-lane carriageway's centre is, so
        # the approach is the wider side of a step. Long (600 m): only the
        # taper next to the diverge is withheld; beyond it, drawn.
        n = Net()
        n.node("a", -600, 0); n.node("d", 0, 0); n.node("b", 600, 0)
        n.node("r", 400 * math.cos(math.radians(12)), -400 * math.sin(math.radians(12)))
        n.way(1, ["a", "d"], lanes="4", oneway="yes", highway="motorway")
        n.way(2, ["d", "b"], lanes="3", oneway="yes", highway="motorway")
        n.way(3, ["d", "r"], lanes="1", oneway="yes", highway="motorway_link")
        res = n.build()
        (a,) = seg(res, 1)
        self.assertEqual(a.reason, "oneway")
        self.assertAlmostEqual(a.step_taper_m, L.STEP_TAPER_M_PER_LANE * 1)
        for r in runs_of(res, 1):
            self.assertAlmostEqual(r.length_m, 600.0 - L.STEP_TAPER_M_PER_LANE, delta=0.5)

    def test_a_crossing_with_fewer_lanes_beyond_is_not_a_step(self):
        # 4 lanes meeting a perpendicular crossing and continuing as 2: lanes
        # differ because traffic turns there — an ordinary approach.
        n = Net()
        n.node("a", -600, 0); n.node("j", 0, 0); n.node("b", 600, 0)
        n.node("n", 0, 150); n.node("s", 0, -150)
        n.way(1, ["a", "j"], lanes="4", oneway="yes")
        n.way(2, ["j", "b"], lanes="2", oneway="yes")
        n.way(3, ["n", "j", "s"], lanes="2", highway="secondary")
        res = n.build()
        (a,) = seg(res, 1)
        self.assertEqual((a.reason, a.step_taper_m), ("oneway", 0.0))

    def test_without_a_continuation_the_approach_is_drawn_and_trimmed(self):
        n = Net()
        n.node("a", -300, 0); n.node("j", 0, 0); n.node("n", 0, 150); n.node("s", 0, -150)
        n.way(1, ["a", "j"], lanes="4", oneway="yes")
        n.way(3, ["n", "j", "s"], lanes="2", highway="secondary")
        res = n.build()
        rs = runs_of(res, 1)
        self.assertEqual(len(rs), 3)
        trim = 2 * 3.5 / 2 + L.JUNCTION_MARGIN_M          # the crossing road's half-width + margin
        for r in rs:
            # every divider stops on the SAME line across the carriageway, the
            # crossing road's edge plus the margin — like a stop line — not at
            # a radius from the node (which would cut outer dividers shorter)
            end_x = max(xy(*c)[0] for c in r.coords)
            self.assertAlmostEqual(end_x, -trim, delta=0.05)


class CaseC_TurnPocket(unittest.TestCase):
    def test_parent_keeps_markings_pocket_is_not_guessed_link_is_separate(self):
        n = Net()
        n.node("a", -400, 0); n.node("p", -100, 0); n.node("j", 0, 0); n.node("e", 300, 0)
        n.node("k", -80, -20)                       # the slip road leaves the pocket
        n.node("m", -20, -120)
        n.way(1, ["a", "p"], lanes="3", oneway="yes")                       # parent approach
        n.way(2, ["p", "j"], lanes="4", oneway="yes",                       # pocket: +1 lane
              **{"turn:lanes": "left|||right"})
        n.way(3, ["j", "e"], lanes="3", oneway="yes")
        n.way(4, ["k", "m"], lanes="1", oneway="yes", highway="primary_link")
        n.node("x", 0, 200); n.node("y", 0, -200)
        n.way(5, ["x", "j", "y"], lanes="4", oneway="no", highway="secondary",
              attrs={"lanes:forward": "2", "lanes:backward": "2"})
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 2)}, {"lane_step_wider_side"})
        self.assertEqual(runs_of(res, 2), [])
        self.assertAlmostEqual(total(res, 1), 2 * 300.0, delta=1.0)    # parent untouched
        self.assertEqual(runs_of(res, 4), [])                          # one-lane link: nothing
        # no marking claims a movement: the only classes are divider styles
        self.assertLessEqual({r.cls for r in res.runs}, {"lane", "solid", "centre"})


class CaseD_AsymmetricExpansion(unittest.TestCase):
    def test_two_to_four_draws_nothing_on_the_wider_side(self):
        n = Net()
        n.node("a", -300, 0); n.node("s", 0, 0); n.node("b", 250, 0)
        n.way(1, ["a", "s"], lanes="2", oneway="yes")
        n.way(2, ["s", "b"], lanes="4", oneway="yes")
        res = n.build()
        self.assertEqual(runs_of(res, 2), [])
        self.assertEqual({s.reason for s in seg(res, 2)}, {"lane_step_wider_side"})
        self.assertAlmostEqual(total(res, 1), 300.0, delta=0.5)

    def test_a_long_wider_section_withholds_only_the_taper(self):
        # 3 lanes for 2 km, dropping to 2 at a plain split: symmetric dividers
        # are right far from the step; only STEP_TAPER_M_PER_LANE per lane of
        # difference next to it is ambiguous.
        n = Net()
        n.node("a", -2000, 0); n.node("s", 0, 0); n.node("b", 300, 0)
        n.way(1, ["a", "s"], lanes="3", oneway="yes")
        n.way(2, ["s", "b"], lanes="2", oneway="yes")
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 1)}, {"oneway"})
        for r in runs_of(res, 1):
            self.assertAlmostEqual(r.length_m, 2000.0 - L.STEP_TAPER_M_PER_LANE, delta=0.5)
            self.assertLess(max(xy(*c)[0] for c in r.coords), -L.STEP_TAPER_M_PER_LANE + 0.5)

    def test_four_becoming_six_at_a_merge_is_not_drawn_symmetric(self):
        # the T4 topology: 4-lane primary + 2-lane road merging from the right
        # at 38 degrees -> 6-lane primary. Short (250 m): the 6-lane section
        # draws nothing; long (900 m): 2 x STEP_TAPER_M_PER_LANE withheld.
        for length, expect in ((250, 0.0), (900, 900 - 2 * L.STEP_TAPER_M_PER_LANE)):
            n = Net()
            n.node("a", -500, 0); n.node("m", 0, 0); n.node("b", length, 0)
            a = math.radians(38)
            n.node("r", -300 * math.cos(a), -300 * math.sin(a))
            n.way(1, ["a", "m"], lanes="4", oneway="yes")
            n.way(2, ["r", "m"], lanes="2", oneway="yes", highway="unclassified")
            n.way(3, ["m", "b"], lanes="6", oneway="yes", **{"turn:lanes": "||||right|right"})
            res = n.build()
            got = sum(r.length_m for r in runs_of(res, 3)) / 5
            self.assertAlmostEqual(got, expect, delta=1.0, msg=f"length {length}")

    def test_equal_lanes_continuing_is_not_a_step(self):
        n = Net()
        n.node("a", -300, 0); n.node("s", 0, 0); n.node("b", 300, 0)
        n.way(1, ["a", "s"], lanes="3", oneway="yes")
        n.way(2, ["s", "b"], lanes="3", oneway="yes")
        res = n.build()
        self.assertAlmostEqual(total(res, 1) + total(res, 2), 2 * 600.0, delta=1.0)

    def test_a_perpendicular_narrower_road_is_not_a_continuation(self):
        n = Net()
        n.node("a", -300, 0); n.node("s", 0, 0); n.node("b", 0, 300)
        n.way(1, ["a", "s"], lanes="4", oneway="yes")
        n.way(2, ["s", "b"], lanes="2", oneway="yes")
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 1)}, {"oneway"})


class CaseE_MultiApproach(unittest.TestCase):
    def test_every_approach_stops_and_the_centre_is_clean(self):
        n = Net()
        n.node("j", 0, 0)
        ends = []
        for k, ang in enumerate((0, 50, 100, 170, 230, 290, 330)):
            nm = f"e{k}"
            n.node(nm, 250 * math.cos(math.radians(ang)), 250 * math.sin(math.radians(ang)))
            n.way(10 + k, [nm, "j"], lanes=str(2 + k % 3), oneway="yes")
            ends.append(10 + k)
        res = n.build()
        widest = max(2 + k % 3 for k in range(7)) * 3.5 / 2
        for wid in ends:
            rs = runs_of(res, wid)
            self.assertTrue(rs, wid)
            for r in rs:
                # stops outside the widest OTHER carriageway it enters
                self.assertGreater(dist_to(r, (0, 0)), 2 * 3.5 / 2 + L.JUNCTION_MARGIN_M - 0.3)
                self.assertGreater(r.length_m, 200.0)          # no unexpected gaps far away
        self.assertEqual(res.node_class[n.nodes["j"][0]], "same_surface")


class CaseF_Roundabout(unittest.TestCase):
    def test_ring_is_bare_and_approaches_stop_at_it(self):
        n = Net()
        pts = []
        for k in range(12):
            a = math.radians(k * 30)
            pts.append(n.node(f"r{k}", 30 * math.cos(a), 30 * math.sin(a)))
        n.way(1, pts + [pts[0]], lanes="2", oneway="yes", junction="roundabout")
        for k, rn in ((2, "r0"), (3, "r3"), (4, "r6"), (5, "r9")):
            x, y = xy(*n.nodes[rn][1])
            n.node(f"o{k}", x * 8, y * 8)
            n.way(k, [f"o{k}", rn], lanes="2", oneway="yes")
        res = n.build()
        self.assertEqual(runs_of(res, 1), [])
        self.assertEqual({s.reason for s in seg(res, 1)}, {"roundabout"})
        for k, rn in ((2, "r0"), (3, "r3"), (4, "r6"), (5, "r9")):
            node_xy = xy(*n.nodes[rn][1])
            for r in runs_of(res, k):
                self.assertGreater(dist_to(r, node_xy), 3.5 + L.JUNCTION_MARGIN_M - 0.3)
                # nothing reaches inside the ring's circle
                self.assertGreater(min(math.hypot(*xy(*c)) for c in r.coords), 30.0 + 1.0)


class CaseG_MissingTags(unittest.TestCase):
    def test_no_lanes_tag_draws_nothing_and_does_not_crash(self):
        n = Net()
        n.node("a", 0, 0); n.node("b", 300, 0); n.node("c", 0, 40); n.node("d", 300, 40)
        n.way(1, ["a", "b"], highway="primary", oneway="yes")                 # wide class, no tag
        n.way(2, ["c", "d"], lanes="two", highway="primary", oneway="yes")    # malformed
        res = n.build()
        self.assertEqual(res.runs, [])
        self.assertEqual({s.reason for s in seg(res, 1)}, {"no_lanes"})
        self.assertEqual({s.reason for s in seg(res, 2)}, {"malformed_lanes"})

    def test_a_way_without_a_sidecar_record_is_skipped_not_guessed(self):
        n = Net()
        n.node("a", 0, 0); n.node("b", 300, 0)
        n.way(1, ["a", "b"], lanes="3", oneway="yes")
        del n.attrs["w1"]
        res = n.build()
        self.assertEqual(res.runs, [])
        self.assertEqual(res.stats["ways_without_aligned_sidecar"], 1)


class CaseH_Elevation(unittest.TestCase):
    def _bridge_over(self, shared_node):
        n = Net()
        n.node("ga", -300, 0); n.node("gj", 0, 0); n.node("gb", 300, 0)
        n.node("sa", 0, -200)
        n.node("ba", 0, -300); n.node("bb", 0, 300)
        n.way(1, ["ga", "gj", "gb"], lanes="3", oneway="yes")              # ground road
        n.way(2, ["sa", "gj"], lanes="2", oneway="yes", highway="secondary")  # side road, T at gj
        if shared_node:
            n.way(3, ["ba", "gj", "bb"], lanes="3", oneway="yes", bridge="yes",
                  attrs={"layer": "1"})
        else:
            n.node("bx", 0, 0.0001)
            n.way(3, ["ba", "bx", "bb"], lanes="3", oneway="yes", bridge="yes",
                  attrs={"layer": "1"})
        return n, n.build()

    def test_a_bridge_over_a_junction_is_not_cut_by_it(self):
        n, res = self._bridge_over(shared_node=False)
        self.assertAlmostEqual(total(res, 3), 2 * 600.0, delta=1.0)      # continuous over the top
        self.assertTrue(all(r.level == 1 for r in runs_of(res, 3)))
        # drawn in the style's BRIDGE group, above the bridge decks
        self.assertEqual({(L.run_properties(r).get("lvl"), L.run_properties(r).get("brg"))
                          for r in runs_of(res, 3)}, {(1, 1)})
        self.assertEqual({L.run_properties(r).get("brg") for r in runs_of(res, 1)}, {None})
        self.assertLess(total(res, 1), 2 * 600.0 - 5)                    # the ground road IS trimmed

    def test_a_shared_node_between_explicit_levels_passing_through_is_different_level(self):
        n, res = self._bridge_over(shared_node=True)
        # a ground T-junction with a bridge wrongly sharing its node: a
        # same-surface junction for the ground ways, the bridge left alone
        self.assertEqual(res.node_class[n.nodes["gj"][0]], "same_surface")
        self.assertEqual(res.stats["way_node_trim_decisions"]
                         ["same_surface_nodes_with_level_pass_through"], 1)
        self.assertLess(total(res, 1), 2 * 600.0 - 5)
        # the bridge meets only a DIFFERENT level there, so it is not trimmed...
        self.assertAlmostEqual(total(res, 3), 2 * 600.0, delta=1.0)
        # ...and a node where ONLY the two levels meet is different_level
        n2 = Net()
        n2.node("a", -300, 0); n2.node("x", 0, 0); n2.node("b", 300, 0)
        n2.node("c", 0, -300); n2.node("d", 0, 300)
        n2.way(1, ["a", "x", "b"], lanes="3", oneway="yes")
        n2.way(2, ["c", "x", "d"], lanes="3", oneway="yes", bridge="yes", attrs={"layer": "1"})
        r2 = n2.build()
        self.assertEqual(r2.node_class[n2.nodes["x"][0]], "different_level")
        self.assertAlmostEqual(total(r2, 1), 2 * 600.0, delta=1.0)
        self.assertAlmostEqual(total(r2, 2), 2 * 600.0, delta=1.0)

    def test_a_tunnel_beneath_draws_nothing_and_cuts_nothing(self):
        n = Net()
        n.node("a", -300, 0); n.node("b", 300, 0); n.node("c", 0, -300); n.node("x", 0, 0.0001)
        n.node("d", 0, 300)
        n.way(1, ["a", "b"], lanes="3", oneway="yes")
        n.way(2, ["c", "x", "d"], lanes="3", oneway="yes", tunnel="yes", attrs={"layer": "-1"})
        res = n.build()
        self.assertEqual(runs_of(res, 2), [])
        self.assertEqual({s.reason for s in seg(res, 2)}, {"tunnel"})
        self.assertAlmostEqual(total(res, 1), 2 * 600.0, delta=1.0)

    def test_a_ramp_joining_a_bridge_is_uncertain_and_trimmed(self):
        n = Net()
        n.node("a", -300, 0); n.node("m", 0, 0); n.node("b", 300, 0); n.node("r", -200, -60)
        n.way(1, ["a", "m", "b"], lanes="3", oneway="yes", bridge="yes", attrs={"layer": "1"})
        n.way(2, ["r", "m"], lanes="2", oneway="yes", highway="motorway_link")  # no layer: ground-tagged
        res = n.build()
        self.assertEqual(res.node_class[n.nodes["m"][0]], "uncertain")
        self.assertLess(total(res, 1), 2 * 600.0 - 5)                    # conservative: trimmed
        for r in runs_of(res, 2):
            self.assertGreater(dist_to(r, (0, 0)), 3 * 3.5 / 2 + L.JUNCTION_MARGIN_M - 0.3)

    def test_a_bridge_without_a_layer_is_suppressed_and_its_nodes_uncertain(self):
        n = Net()
        n.node("a", -300, 0); n.node("m", 0, 0); n.node("b", 300, 0); n.node("s", 0, -200)
        n.way(1, ["a", "m", "b"], lanes="3", oneway="yes", bridge="yes")
        n.way(2, ["s", "m"], lanes="2", oneway="yes")
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 1)}, {"bridge_level_unknown"})
        self.assertEqual(res.node_class[n.nodes["m"][0]], "uncertain")

    def test_a_stacked_interchange_cuts_no_level_by_another(self):
        n = Net()
        for lv, ang in ((0, 0), (1, 60), (2, 120)):
            c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
            n.node(f"a{lv}", -300 * c, -300 * s); n.node(f"b{lv}", 300 * c, 300 * s)
            n.node(f"m{lv}", 0.00001 * lv, 0)
            n.way(20 + lv, [f"a{lv}", f"m{lv}", f"b{lv}"], lanes="3", oneway="yes",
                  attrs={"layer": str(lv)} if lv else None,
                  **({"bridge": "yes"} if lv else {}))
        res = n.build()
        for lv in range(3):
            self.assertAlmostEqual(total(res, 20 + lv), 2 * 600.0, delta=1.0)


class CaseI_ParallelService(unittest.TestCase):
    def test_arterial_markings_do_not_jump_to_a_parallel_service_road(self):
        n = Net()
        n.node("a", -500, 0); n.node("j", 0, 0); n.node("b", 500, 0)
        n.node("s0", -400, -9); n.node("s1", -50, -9)
        n.way(1, ["a", "j", "b"], lanes="3", oneway="yes", highway="trunk")
        n.way(2, ["s0", "s1", "j"], highway="service", oneway="yes")        # joins at j
        n.way(3, ["s0", "s1"][:1] + ["s1"], lanes="2", highway="service")   # a tagged service road
        res = n.build()
        self.assertEqual(runs_of(res, 2) + runs_of(res, 3), [])
        for r in runs_of(res, 1):
            for c in r.coords:
                self.assertLess(abs(xy(*c)[1]), 3 * 3.5 / 2)                 # inside its own deck
        # only trimmed at the node it actually meets the service road, by the
        # SERVICE road's width, and nowhere along the 350 m they run side by side
        self.assertAlmostEqual(total(res, 1), 2 * (1000.0 - 2 * (1.75 + L.JUNCTION_MARGIN_M)), delta=1.0)


class S2_AcuteMouths(unittest.TestCase):
    def _diverge(self):
        # a 3-lane mainline with a 2-lane ramp leaving at 12 degrees
        n = Net()
        n.node("a", -600, 0); n.node("d", 0, 0); n.node("b", 600, 0)
        n.node("r", 400 * math.cos(math.radians(12)), -400 * math.sin(math.radians(12)))
        n.way(1, ["a", "d"], lanes="3", oneway="yes", highway="motorway")
        n.way(2, ["d", "b"], lanes="3", oneway="yes", highway="motorway")
        n.way(3, ["d", "r"], lanes="2", oneway="yes", highway="motorway_link")
        return n, n.build()

    def test_the_ramp_is_trimmed_until_clear_of_the_mainline_surface(self):
        n, res = self._diverge()
        s1 = 3 * 3.5 / 2 + L.JUNCTION_MARGIN_M
        (ramp,) = seg(res, 3)
        self.assertEqual(ramp.start_role, "branch")
        self.assertGreater(ramp.trim_start_m, 3 * s1)             # far beyond the perpendicular trim
        # every ramp divider is clear of the mainline's surface + margin
        for r in runs_of(res, 3):
            for c in r.coords:
                x, y = xy(*c)
                if x > 0:
                    self.assertGreater(abs(y), 3 * 3.5 / 2 + L.JUNCTION_MARGIN_M - 0.3)

    def test_the_mainline_is_through_and_keeps_its_markings(self):
        n, res = self._diverge()
        s1 = 2 * 3.5 / 2 + L.JUNCTION_MARGIN_M                   # the ramp's half-width + margin
        for wid in (1, 2):
            (sg,) = seg(res, wid)
            role = sg.end_role if wid == 1 else sg.start_role
            self.assertEqual(role, "through")
            self.assertAlmostEqual(total(res, wid), 2 * (600.0 - s1), delta=1.0)


class S2_Cap(unittest.TestCase):
    def test_a_branch_running_alongside_is_trimmed_only_at_its_mouth(self):
        # a 2-lane link joins a 3-lane road and runs beside it, 6 m apart —
        # closer than their painted half-widths (5.25 + 3.5) — for 300 m
        n = Net()
        n.node("a", -600, 0); n.node("j", 0, 0); n.node("b", 400, 0)
        n.node("l0", -300, -6); n.node("l1", -20, -6)
        n.way(1, ["a", "j", "b"], lanes="3", oneway="yes")
        n.way(2, ["l0", "l1", "j"], lanes="2", oneway="yes", highway="primary_link")
        res = n.build()
        (link,) = seg(res, 2)
        self.assertEqual(link.end_role, "branch")
        self.assertLessEqual(link.trim_end_m, L.S2_MAX_TRIM_M + 1e-6)
        self.assertGreater(link.trim_end_m, 2 * 3.5 / 2 + L.JUNCTION_MARGIN_M)   # S2 did act
        # the parallel stretch beyond the cap keeps its marking
        self.assertAlmostEqual(total(res, 2),
                               LineStringXY(n, ["l0", "l1", "j"]).length - L.S2_MAX_TRIM_M, delta=1.0)


class CaseJ_DividedCarriageway(unittest.TestCase):
    def test_each_carriageway_is_marked_on_itself_and_trimmed_at_its_own_node(self):
        n = Net()
        n.node("ea", -400, 10); n.node("ej", 0, 10); n.node("eb", 400, 10)
        n.node("wa", 400, -10); n.node("wj", 0, -10); n.node("wb", -400, -10)
        n.node("n", 0, 200); n.node("s", 0, -200)
        n.way(1, ["ea", "ej", "eb"], lanes="3", oneway="yes", name="Road")
        n.way(2, ["wa", "wj", "wb"], lanes="3", oneway="yes", name="Road")
        n.way(3, ["n", "ej", "wj", "s"], lanes="2", highway="secondary")
        res = n.build()
        for wid, y0 in ((1, 10), (2, -10)):
            for r in runs_of(res, wid):
                for c in r.coords:
                    self.assertLess(abs(xy(*c)[1] - y0), 3 * 3.5 / 2)       # never on the other one
        # the crossing road's middle piece, between the two carriageways, is
        # trimmed at both ends by the 3-lane carriageways (5.25 + 1.6 m each)
        mid = [r for r in runs_of(res, 3) if abs(xy(*r.coords[0])[1]) < 10]
        for r in mid:
            self.assertLessEqual(r.length_m, 20.0 - 2 * (5.25 + L.JUNCTION_MARGIN_M) + 0.5)


class TwoWayRules(unittest.TestCase):
    def test_two_way_wider_than_two_needs_a_stated_split(self):
        n = Net()
        n.node("a", 0, 0); n.node("b", 300, 0); n.node("c", 0, 50); n.node("d", 300, 50)
        n.node("e", 0, 100); n.node("f", 300, 100)
        n.way(1, ["a", "b"], lanes="4")
        n.way(2, ["c", "d"], lanes="4", attrs={"lanes:forward": "3", "lanes:backward": "1"})
        n.way(3, ["e", "f"], lanes="4", attrs={"lanes:forward": "3", "lanes:backward": "2"})
        res = n.build()
        self.assertEqual({s.reason for s in seg(res, 1)}, {"two_way_split_unknown"})
        self.assertEqual([(r.pos, r.cls) for r in runs_of(res, 2)],
                         [(1, "centre"), (2, "lane"), (3, "lane")])
        # backward lane on the LEFT of the digitised direction (drive on the right)
        centre = [r for r in runs_of(res, 2) if r.cls == "centre"][0]
        self.assertAlmostEqual(xy(*centre.coords[0])[1] - 50, 3.5, delta=0.05)
        self.assertEqual({s.reason for s in seg(res, 3)}, {"direction_split_conflict"})


class OpposingCarriageway(unittest.TestCase):
    def test_where_a_divided_road_splits_no_marking_lies_on_the_oncoming_half(self):
        # a 2-lane two-way road splitting at s into two one-way carriageways
        # that diverge at 10 degrees each side and run 20 m apart
        n = Net()
        n.node("w", -300, 0); n.node("s", 0, 0)
        t = math.radians(10)
        n.node("e1", 57, 10); n.node("e2", 400, 10)
        n.node("f1", 57, -10); n.node("f2", 400, -10)
        n.way(1, ["w", "s"], lanes="2", highway="secondary")
        n.way(2, ["s", "e1", "e2"], lanes="2", oneway="yes", highway="secondary")     # outbound
        n.way(3, ["f2", "f1", "s"], lanes="2", oneway="yes", highway="secondary")     # inbound
        res = n.build()
        deck_opp = {2: -10, 3: 10}                                   # the OTHER half's centre y
        for wid in (2, 3):
            for r in runs_of(res, wid):
                for c in r.coords:
                    x, y = xy(*c)
                    if x > 57:                                       # the parallel part
                        self.assertGreater(abs(y - deck_opp[wid]), 3.5 + L.JUNCTION_MARGIN_M - 0.3)
                    # and near the split, never within the other half's surface
                    other = LineStringXY(n, {2: ["f2", "f1", "s"], 3: ["s", "e1", "e2"]}[wid])
                    self.assertGreater(other.distance(PointXY(x, y)), 3.5 - 0.3)


def LineStringXY(n, names):
    from shapely.geometry import LineString
    return LineString([xy(*n.nodes[m][1]) for m in names])


def PointXY(x, y):
    from shapely.geometry import Point
    return Point(x, y)


class Determinism(unittest.TestCase):
    def _net(self):
        n = Net()
        n.node("a", -300, 0); n.node("j", 0, 0); n.node("b", 300, 0)
        n.node("c", 0, -300); n.node("d", 0, 300)
        n.way(1, ["a", "j", "b"], lanes="3", oneway="yes")
        n.way(2, ["c", "j"], lanes="2", oneway="yes")
        n.way(3, ["j", "d"], lanes="4", oneway="yes")
        return n

    def test_input_order_does_not_matter(self):
        n = self._net()
        a = n.build()
        random.Random(7).shuffle(n.ways)
        b = n.build()
        key = lambda res: [(r.way_osm_id, r.part, r.pos, r.piece, r.cls, r.coords) for r in res.runs]
        self.assertEqual(key(a), key(b))
        self.assertEqual(a.stats, b.stats)


if __name__ == "__main__":
    unittest.main()
