"""The country-to-street zoom hierarchy.

These pin the pipeline half of the V3 P0. Probed against the live tile server
before the fix:

    z4..z10   404 Not Found      <- country, region and city views
    z11       200  83,719 bytes
    z14       200  68,199 bytes

`/tiles/version` reported ``{"minzoom": 11, "maxzoom": 14}`` and both clients
correctly declared that range, so the CLIENTS were right — nothing below z11 had
ever been baked, and MapLibre does not under-zoom the way it over-zooms above a
source's maximum: below `minzoom` it requests nothing and paints the background
colour. Zooming out to see Qatar showed an empty screen.

Baking the low zooms at the previous settings would not have fixed it. A single
z6 tile covers the whole country and would have received 5,951 motorway and
trunk features against a 1,500-feature cap with per-kind floors, so about 80% of
the national road network would have been truncated away — a broken lattice
rather than a blank screen.

Three things had to change first, and one test class each below covers them.
"""

import os
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "src"))
sys.path.insert(0, os.path.join(_ROOT, "scripts"))

from vector_tile_gen.simplify import (  # noqa: E402
    MIN_POLYGON_UNITS, simplify_geometry,
)

import build_qatar_tiles as bake  # noqa: E402


class Feat:
    """The minimal shape `visible_at_zoom` and `feature_rank` read."""

    def __init__(self, props, bbox=None):
        self.properties = props
        self.bbox = bbox


class RoadTierTest(unittest.TestCase):
    """Which road classes belong at which scale."""

    def test_the_national_skeleton_is_present_at_country_zoom(self):
        # This is the whole country view: without motorway and trunk at z6
        # there is nothing to draw and the fix is cosmetic only.
        for hw in ("motorway", "trunk"):
            self.assertTrue(bake.road_visible_at(hw, 6), f"{hw} missing at z6")

    def test_link_roads_are_not_country_scale_features(self):
        # The specific reason a naive low-zoom bake fails. A slip road is a few
        # hundred metres long and joins two roads that are both already drawn;
        # at z6 it is smaller than one tile pixel. Qatar has 914 motorway and
        # 1,587 trunk mainline ways against 1,920 motorway_link and 1,530
        # trunk_link — so tiering links with their parents gave 58% of a z6
        # tile's road budget to geometry nobody can see.
        for hw in ("motorway_link", "trunk_link", "primary_link"):
            self.assertFalse(bake.road_visible_at(hw, 6), f"{hw} should not be at z6")
            self.assertFalse(bake.road_visible_at(hw, 10), f"{hw} should not be at z10")

    def test_links_are_present_where_a_driver_needs_them(self):
        # They are how you get on and off a motorway, so they must appear well
        # before navigation zoom.
        for hw in ("motorway_link", "trunk_link", "primary_link"):
            self.assertTrue(bake.road_visible_at(hw, 11))

    def test_classes_appear_progressively(self):
        # A city drawn with every service road is an undifferentiated hairball,
        # and it is also what exhausted the per-tile budget with roads alone.
        self.assertFalse(bake.road_visible_at("residential", 12))
        self.assertTrue(bake.road_visible_at("residential", 14))
        self.assertFalse(bake.road_visible_at("service", 14))
        self.assertTrue(bake.road_visible_at("service", 15))

    def test_the_tiers_are_monotonic(self):
        # A class that appears at z_n must still be there at z_n+1. Anything
        # else makes roads flicker out as the driver zooms IN.
        classes = ["motorway", "trunk", "primary", "motorway_link", "secondary",
                   "tertiary", "residential", "service", "footway", "notaclass"]
        for hw in classes:
            seen = False
            for z in range(0, 19):
                v = bake.road_visible_at(hw, z)
                if seen:
                    self.assertTrue(v, f"{hw} disappears again at z{z}")
                seen = seen or v

    def test_an_unknown_class_is_treated_as_minor_rather_than_dropped(self):
        self.assertFalse(bake.road_visible_at("some_new_osm_value", 12))
        self.assertTrue(bake.road_visible_at("some_new_osm_value", 14))


class PlaceLabelTierTest(unittest.TestCase):
    """Labels are ranked, so the country view can carry them at all."""

    def test_the_country_and_its_regions_lead(self):
        self.assertTrue(bake.place_visible_at("country", 4))
        self.assertTrue(bake.place_visible_at("state", 6))

    def test_a_hamlet_is_not_a_country_scale_label(self):
        # Qatar's extract holds 320 localities and 115 hamlets against 3
        # cities. Unranked, MapLibre's symbol collision decided which survived
        # by FEATURE ORDER, so which town appeared at country zoom was an
        # accident of the source file.
        for place in ("hamlet", "locality", "isolated_dwelling"):
            self.assertFalse(bake.place_visible_at(place, 6))
            self.assertFalse(bake.place_visible_at(place, 11))
            self.assertTrue(bake.place_visible_at(place, 12))

    def test_cities_and_towns_arrive_before_suburbs(self):
        self.assertTrue(bake.place_visible_at("city", 7))
        self.assertFalse(bake.place_visible_at("town", 7))
        self.assertTrue(bake.place_visible_at("town", 9))
        self.assertFalse(bake.place_visible_at("suburb", 9))
        self.assertTrue(bake.place_visible_at("suburb", 11))

    def test_an_untagged_label_is_local_detail_rather_than_promoted(self):
        # Guessing upward would put an unranked label beside "QATAR".
        self.assertFalse(bake.place_visible_at(None, 6))
        self.assertTrue(bake.place_visible_at(None, 12))

    def test_the_tiers_are_monotonic(self):
        for place in ("country", "state", "city", "town", "suburb", "hamlet", None):
            seen = False
            for z in range(0, 19):
                v = bake.place_visible_at(place, z)
                if seen:
                    self.assertTrue(v, f"{place} disappears again at z{z}")
                seen = seen or v

    def test_visible_at_zoom_uses_the_ranking(self):
        # The wiring, not just the predicate: `visible_at_zoom` used to return
        # `z >= 10` for every label regardless of `place`.
        city = Feat({"kind": "label", "place": "city", "name": "Doha"})
        hamlet = Feat({"kind": "label", "place": "hamlet", "name": "Somewhere"})
        self.assertTrue(bake.visible_at_zoom(city, 7))
        self.assertFalse(bake.visible_at_zoom(hamlet, 7))


class ZoomCapTest(unittest.TestCase):
    """The per-tile budget is not one number across nine zooms."""

    def test_low_zoom_gets_a_larger_budget_than_street_zoom(self):
        # A feature's WEIGHT is not constant across zooms: after
        # simplify_geometry a motorway carrying 400 vertices at z14 carries
        # about 6 at z6. One cap cannot serve both ends.
        self.assertGreater(bake.cap_for_zoom(6, 1500), bake.cap_for_zoom(14, 1500))

    def test_a_z6_tile_can_hold_the_whole_national_network(self):
        # 914 motorway + 1,587 trunk = 2,501 road features land in one z6 tile,
        # and the 1,500 cap would have truncated over half of them.
        self.assertGreater(bake.cap_for_zoom(6, 1500), 2501)

    def test_the_street_zooms_keep_the_measured_default(self):
        for z in (12, 13, 14, 15):
            self.assertEqual(bake.cap_for_zoom(z, 1500), 1500)

    def test_raising_the_argument_can_only_raise_a_cap(self):
        # --max-per-tile is an override, not a competing definition.
        for z in range(0, 19):
            self.assertGreaterEqual(bake.cap_for_zoom(z, 50_000), 50_000)

    def test_the_caps_do_not_increase_with_zoom(self):
        caps = [bake.cap_for_zoom(z, 1500) for z in range(6, 16)]
        self.assertEqual(caps, sorted(caps, reverse=True))


class PolygonRankTest(unittest.TestCase):
    """Truncation keeps the shapes a viewer can see."""

    def test_the_largest_polygon_of_a_kind_ranks_first(self):
        # Qatar has 10,612 park polygons and most are traffic islands and
        # verges. Without this, a tile over budget kept whichever came first in
        # the source file, so Al Bidda could lose its slot to a verge.
        big = Feat({"kind": "park"}, bbox=(51.5, 25.2, 51.6, 25.3))
        small = Feat({"kind": "park"}, bbox=(51.50, 25.20, 51.5001, 25.2001))
        self.assertLess(bake.feature_rank(big), bake.feature_rank(small))

    def test_roads_still_beat_polygons(self):
        # The floors give other kinds a guaranteed share; the ordering must not
        # also change.
        road = Feat({"kind": "road", "highway": "motorway"})
        park = Feat({"kind": "park"}, bbox=(51.5, 25.2, 51.9, 25.9))
        self.assertLess(bake.feature_rank(road), bake.feature_rank(park))

    def test_major_roads_still_beat_minor_ones(self):
        major = Feat({"kind": "road", "highway": "primary"})
        minor = Feat({"kind": "road", "highway": "residential"})
        self.assertLess(bake.feature_rank(major), bake.feature_rank(minor))

    def test_a_feature_with_no_bbox_does_not_crash_the_rank(self):
        self.assertIsInstance(bake.feature_rank(Feat({"kind": "park"})), tuple)


class SimplifyTest(unittest.TestCase):
    """Vertices the tile grid cannot resolve, removed before encoding."""

    # A z6 tile over Qatar. One unit of its 4096 extent is about 138 m.
    Z, X, Y = 6, 41, 27

    def test_vertices_inside_one_grid_cell_collapse(self):
        # ~1 m apart at z6: the same point as far as any renderer is concerned,
        # and each one was still being written as a zero-delta LINE_TO.
        line = [[51.5300, 25.2900], [51.53001, 25.29001], [51.53002, 25.29002],
                [51.6000, 25.3500]]
        out = simplify_geometry("LineString", line, self.Z, self.X, self.Y)
        self.assertEqual(len(out), 2)

    def test_the_surviving_coordinates_are_unchanged(self):
        # This module decides WHICH vertices to keep; quantising them is the
        # encoder's job, and doing it in both places would round twice.
        line = [[51.5300, 25.2900], [51.53001, 25.29001], [51.6000, 25.3500]]
        out = simplify_geometry("LineString", line, self.Z, self.X, self.Y)
        self.assertEqual(out[0], [51.5300, 25.2900])
        self.assertEqual(out[-1], [51.6000, 25.3500])

    def test_detail_survives_at_street_zoom(self):
        # The same geometry at z16, where one unit is ~2 cm: nothing should go.
        line = [[51.5300, 25.2900], [51.53001, 25.29001], [51.53002, 25.29002]]
        z, x, y = 16, 42148, 28006
        out = simplify_geometry("LineString", line, z, x, y)
        self.assertEqual(len(out), 3)

    def test_a_line_shorter_than_one_grid_unit_is_dropped(self):
        line = [[51.5300, 25.2900], [51.53001, 25.29001]]
        self.assertIsNone(simplify_geometry("LineString", line, self.Z, self.X, self.Y))

    def test_a_ring_under_one_screen_pixel_is_dropped(self):
        # 8 grid units is ~1 logical pixel (4096 extent across ~512 px), which
        # at z6 is a park about 1.1 km across. Parks are 10,612 of the 14,026
        # features visible at z8 and almost all are verges: they were most of
        # the tile payload while being individually invisible.
        tiny = [[[51.5300, 25.2900], [51.5301, 25.2900],
                 [51.5301, 25.2901], [51.5300, 25.2901], [51.5300, 25.2900]]]
        self.assertIsNone(simplify_geometry("Polygon", tiny, self.Z, self.X, self.Y))

    def test_a_ring_a_viewer_can_see_is_kept(self):
        big = [[[51.40, 25.20], [51.70, 25.20],
                [51.70, 25.45], [51.40, 25.45], [51.40, 25.20]]]
        out = simplify_geometry("Polygon", big, self.Z, self.X, self.Y)
        self.assertIsNotNone(out)
        self.assertGreaterEqual(len(out[0]), 4)

    def test_a_kept_ring_is_still_closed(self):
        big = [[[51.40, 25.20], [51.70, 25.20],
                [51.70, 25.45], [51.40, 25.45], [51.40, 25.20]]]
        ring = simplify_geometry("Polygon", big, self.Z, self.X, self.Y)[0]
        self.assertEqual(ring[0], ring[-1], "thinning broke the ring")

    def test_a_polygon_whose_shell_collapses_takes_its_holes_with_it(self):
        # A surviving hole with no shell is not a shape.
        shell = [[51.5300, 25.2900], [51.5301, 25.2900],
                 [51.5301, 25.2901], [51.5300, 25.2900]]
        hole = [[51.53002, 25.29002], [51.53008, 25.29002],
                [51.53008, 25.29008], [51.53002, 25.29002]]
        self.assertIsNone(
            simplify_geometry("Polygon", [shell, hole], self.Z, self.X, self.Y)
        )

    def test_points_are_never_thinned(self):
        # Dropping a point because it shares a cell with another feature's
        # point would be label collision, decided in the wrong place.
        self.assertEqual(
            simplify_geometry("Point", [51.53, 25.29], self.Z, self.X, self.Y),
            [51.53, 25.29],
        )

    def test_an_unknown_geometry_type_passes_through(self):
        # A pipeline must not silently delete a shape it does not recognise.
        weird = {"anything": 1}
        self.assertIs(
            simplify_geometry("GeometryCollection", weird, self.Z, self.X, self.Y),
            weird,
        )

    def test_a_multilinestring_drops_only_its_degenerate_parts(self):
        keep = [[51.40, 25.20], [51.70, 25.45]]
        drop = [[51.5300, 25.2900], [51.53001, 25.29001]]
        out = simplify_geometry("MultiLineString", [keep, drop], self.Z, self.X, self.Y)
        self.assertEqual(len(out), 1)

    def test_a_multipolygon_with_nothing_left_becomes_none(self):
        tiny = [[[51.5300, 25.2900], [51.5301, 25.2900],
                 [51.5301, 25.2901], [51.5300, 25.2900]]]
        self.assertIsNone(
            simplify_geometry("MultiPolygon", [tiny], self.Z, self.X, self.Y)
        )

    def test_the_threshold_is_one_screen_pixel_not_a_tuned_constant(self):
        # Documented as derived from the render size (4096 extent across ~512
        # logical pixels). If someone raises it, that is a decision to discard
        # shapes a viewer can see, and it should not happen quietly.
        self.assertEqual(MIN_POLYGON_UNITS, 8)

    def test_a_pole_coordinate_does_not_take_the_bake_down(self):
        # Mercator is infinite at the pole. One bad vertex in a 204,663-feature
        # extract must not abort a nine-zoom bake.
        line = [[0.0, 90.0], [0.0, -90.0]]
        simplify_geometry("LineString", line, 6, 32, 20)


class BuildingPriorityTest(unittest.TestCase):
    """Where a 3D building sits in a tile's budget (V7 3D).

    Buildings are the one purely decorative kind: nothing routes to a footprint
    and nothing reads one, so a tile with none is still a usable map. Every
    other kind is something a driver acts on.

    Measured before this rule, on 51 over-budget z14 tiles over central Doha:
    a 15% reserved floor for buildings cost **265 road features, 221 barriers
    and 26 parks**. So the rule is not "give buildings a share" but "buildings
    take only what nobody else wanted".
    """

    @staticmethod
    def _tile(n_roads, n_buildings, n_parks=0):
        feats = [Feat({"kind": "road", "highway": "residential"})
                 for _ in range(n_roads)]
        feats += [Feat({"kind": "park"}) for _ in range(n_parks)]
        feats += [Feat({"kind": "building", "height_m": 40.0})
                  for _ in range(n_buildings)]
        return feats

    def _counts(self, feats, cap, z):
        out = {}
        for f in bake.select_for_tile(feats, cap, z):
            k = f.properties.get("kind")
            out[k] = out.get(k, 0) + 1
        return out

    def test_buildings_never_displace_a_road(self):
        # The acceptance condition, as arithmetic: an over-budget tile of roads
        # and buildings keeps EVERY road and only then spends what is left.
        feats = self._tile(n_roads=1200, n_buildings=3000)
        got = self._counts(feats, 1500, 15)
        self.assertEqual(got.get("road"), 1200)
        self.assertEqual(got.get("building"), 300)

    def test_buildings_never_displace_a_park_either(self):
        feats = self._tile(n_roads=1000, n_buildings=3000, n_parks=400)
        got = self._counts(feats, 1500, 15)
        self.assertEqual(got.get("road"), 1000)
        self.assertEqual(got.get("park"), 400)
        self.assertEqual(got.get("building"), 100)

    def test_buildings_take_the_leftover_space_and_no_more(self):
        # An UNDER-budget tile is unaffected: everything goes in.
        feats = self._tile(n_roads=100, n_buildings=50)
        got = self._counts(feats, 1500, 15)
        self.assertEqual(got.get("road"), 100)
        self.assertEqual(got.get("building"), 50)

    def test_buildings_hold_no_reserved_floor_at_any_zoom(self):
        # A floor is the wrong instrument for a decorative kind: it reserves
        # budget in pass 1 rather than consuming what pass 2 leaves.
        for z in (None, 11, 12, 13, 14, 15):
            floors = bake.kind_floors(z) if z is not None else bake._KIND_FLOOR
            self.assertNotIn("building", floors, f"z={z}")

    def test_a_decoration_can_never_outrank_something_a_driver_uses(self):
        # `poi` is not in `_KIND_ORDER` — POIs take the default rank, which is
        # 6 — so the comparison is made through `feature_rank`, the function
        # that actually decides, rather than through the table.
        building = bake.feature_rank(Feat({"kind": "building", "height_m": 40.0}))
        for used in ("water", "coastline", "park", "natural", "landuse", "label"):
            self.assertLess(bake.feature_rank(Feat({"kind": used})), building, used)
        self.assertLess(
            bake.feature_rank(Feat({"kind": "poi", "name": "Cafe",
                                    "poi_class": "cafe"})),
            building, "poi",
        )

    def test_the_budget_itself_is_unchanged(self):
        feats = self._tile(n_roads=1200, n_buildings=3000)
        self.assertEqual(len(bake.select_for_tile(feats, 1500, 15)), 1500)


class BuildingFabricPriorityTest(unittest.TestCase):
    """The city fabric competes for budget on exactly the building's terms
    (V7.6).

    V7.6 carries every footprint — 189,866 for Qatar, 170,216 of them in the
    Doha core — where V7 carried only the 975 that state a height. That is a
    195x change nationally, and a measured 104x inside the central-Doha bake
    window (720 buildings became 75,168), so every guarantee the V7 stage
    measured has to survive at the new density, and one NEW failure becomes
    possible that could not exist before.
    """

    @staticmethod
    def _measured(n, area=1.0):
        # A tower: a real height, and a SMALL footprint. Both are true of
        # Doha's skyline and together they are the trap this class exists for.
        return [Feat({"kind": "building", "height_m": 195.0},
                     bbox=(0.0, 0.0, area, area)) for _ in range(n)]

    @staticmethod
    def _fabric(n, area=4.0):
        # A shed, a villa, a warehouse: no height, bigger footprint.
        return [Feat({"kind": "building"}, bbox=(0.0, 0.0, area, area))
                for _ in range(n)]

    def _counts(self, feats, cap, z):
        out = {}
        for f in bake.select_for_tile(feats, cap, z):
            p = f.properties
            k = p.get("kind")
            if k == "building":
                k = "measured" if "height_m" in p else "fabric"
            out[k] = out.get(k, 0) + 1
        return out

    def test_a_dense_fabric_tile_still_displaces_nothing_a_driver_uses(self):
        # The V7 guarantee, re-measured at V7.6 density. A West Bay z15 tile
        # carries roughly 470 roads and 150 POIs; the fabric adds hundreds of
        # footprints on top. Everything a driver acts on must survive intact.
        feats = ([Feat({"kind": "road", "highway": "residential"})
                  for _ in range(470)]
                 + [Feat({"kind": "poi", "name": "Cafe", "poi_class": "cafe"})
                    for _ in range(150)]
                 + [Feat({"kind": "park"}) for _ in range(60)]
                 + [Feat({"kind": "water"}) for _ in range(20)]
                 + self._measured(30) + self._fabric(2000))
        got = self._counts(feats, 1500, 15)
        self.assertEqual(got.get("road"), 470)
        self.assertEqual(got.get("poi"), 150)
        self.assertEqual(got.get("park"), 60)
        self.assertEqual(got.get("water"), 20)
        # And the tile is exactly full, with buildings holding the remainder.
        self.assertEqual(sum(got.values()), 1500)

    def test_fabric_never_evicts_a_measured_tower(self):
        # THE new failure mode, and the reason `feature_rank` grew a building
        # branch. Buildings are shed by footprint AREA, and Doha's towers are
        # slim — so without the rank, 2,000 warehouses would push all 30
        # towers out of an over-budget West Bay tile and the extrusion layer
        # would go dark in the one district it exists for.
        feats = ([Feat({"kind": "road", "highway": "residential"})
                  for _ in range(1400)]
                 + self._measured(30) + self._fabric(2000))
        got = self._counts(feats, 1500, 15)
        self.assertEqual(got.get("road"), 1400)
        self.assertEqual(got.get("measured"), 30, "every tower survives")
        self.assertEqual(got.get("fabric"), 70, "fabric takes what is left")

    def test_a_measured_building_outranks_fabric_and_both_outrank_nothing(self):
        measured = bake.feature_rank(self._measured(1)[0])
        fabric = bake.feature_rank(self._fabric(1)[0])
        self.assertLess(measured, fabric)
        # Both still yield to every kind a driver acts on — the V7 rule is
        # untouched by the new tie-break.
        for used in ("water", "coastline", "park", "natural", "landuse",
                     "label"):
            self.assertLess(bake.feature_rank(Feat({"kind": used})), fabric,
                            used)
            self.assertLess(bake.feature_rank(Feat({"kind": used})), measured,
                            used)
        poi = bake.feature_rank(Feat({"kind": "poi", "name": "Cafe",
                                      "poi_class": "cafe"}))
        self.assertLess(poi, measured)

    def test_fabric_is_still_ordered_largest_first_within_its_group(self):
        # The V3 rule survives inside the new group: a traffic-island-sized
        # footprint must not evict a city block.
        big = Feat({"kind": "building"}, bbox=(0.0, 0.0, 10.0, 10.0))
        small = Feat({"kind": "building"}, bbox=(0.0, 0.0, 0.1, 0.1))
        self.assertLess(bake.feature_rank(big), bake.feature_rank(small))

    def test_the_fabric_holds_no_reserved_floor_either(self):
        # A heightless building is no less decorative than a height-bearing
        # one, so V7.6 gives it no budget of its own at any zoom.
        for z in (None, 11, 12, 13, 14, 15, 16):
            floors = bake.kind_floors(z) if z is not None else bake._KIND_FLOOR
            self.assertNotIn("building", floors, f"z={z}")

    def test_fabric_is_not_drawn_below_street_level(self):
        # 170,216 footprints at z13 would be a grey smear over the whole
        # country, and no driver reads a building at that zoom.
        fabric = self._fabric(1)[0]
        for z in (6, 10, 11, 12, 13):
            self.assertFalse(bake.visible_at_zoom(fabric, z), f"z={z}")
        for z in (14, 15, 16):
            self.assertTrue(bake.visible_at_zoom(fabric, z), f"z={z}")


if __name__ == "__main__":
    unittest.main()


class PoiFloorTest(unittest.TestCase):
    """How much of a street-level tile a driver's destinations may occupy.

    Observed on a recorded Doha drive: tile 14/10537/7002 carries 990 named
    POIs in the source and shipped 82. The flat 6% floor is 90 features, POIs
    rank last, and pass 2 fills what is left in rank order — so in any dense
    tile POIs got their floor and not one feature more. The destination the
    driver was navigating to was on the street in front of them and not on
    the map.
    """

    @staticmethod
    def _tile(n_roads, n_pois):
        feats = [Feat({"kind": "road", "highway": "residential"})
                 for _ in range(n_roads)]
        feats += [Feat({"kind": "poi"}) for _ in range(n_pois)]
        return feats

    def _poi_count(self, feats, cap, z):
        chosen = bake.select_for_tile(feats, cap, z)
        return sum(1 for f in chosen if f.properties.get("kind") == "poi")

    def test_a_dense_street_tile_keeps_far_more_pois_than_the_flat_floor(self):
        feats = self._tile(2000, 990)
        self.assertEqual(self._poi_count(feats, 1500, None), 90)   # was
        self.assertGreaterEqual(self._poi_count(feats, 1500, 14), 500)

    def test_the_budget_itself_is_unchanged(self):
        """Redistribution, not inflation — tile weight must not grow."""
        feats = self._tile(2000, 990)
        self.assertEqual(len(bake.select_for_tile(feats, 1500, 14)), 1500)

    def test_the_zooms_below_street_level_are_untouched(self):
        # POIs are not even visible below z14, so nothing should change there.
        for z in (6, 10, 12, 13):
            self.assertEqual(bake.kind_floors(z)["poi"], bake._KIND_FLOOR["poi"])

    def test_a_sparse_tile_still_gives_the_remainder_back(self):
        """A floor is a floor, not an allocation: unused space returns to roads."""
        feats = self._tile(2000, 10)
        chosen = bake.select_for_tile(feats, 1500, 14)
        self.assertEqual(sum(1 for f in chosen
                             if f.properties.get("kind") == "poi"), 10)
        self.assertEqual(len(chosen), 1500)

    def test_roads_still_get_the_larger_share(self):
        """POIs must not crowd the street network off a navigation map."""
        chosen = bake.select_for_tile(self._tile(2000, 990), 1500, 14)
        roads = sum(1 for f in chosen if f.properties.get("kind") == "road")
        pois = sum(1 for f in chosen if f.properties.get("kind") == "poi")
        self.assertGreater(roads, pois)


class PoiRankTest(unittest.TestCase):
    """Which POIs a tile over budget keeps.

    Before this, `feature_rank` returned the same tuple for every POI, so a
    truncated tile kept whichever ones came first in the source file — and the
    restaurant a driver was navigating to lost its slot to an unnamed node.
    """

    @staticmethod
    def _poi(**props):
        props.setdefault("kind", "poi")
        return Feat(props)

    def test_a_named_poi_outranks_an_unnamed_one(self):
        named = self._poi(name="Green Tea Garden Restaurant", poi_class="restaurant")
        anon = self._poi(poi_class="shop")
        self.assertLess(bake.feature_rank(named), bake.feature_rank(anon))

    def test_an_english_only_name_counts_as_named(self):
        self.assertLess(bake.feature_rank(self._poi(**{"name:en": "Palm Cafe"})),
                        bake.feature_rank(self._poi()))

    def test_a_real_category_outranks_shop_equals_yes(self):
        # `yes` is what `shop=yes`/`building=yes` leave behind: it means "this
        # exists", which is not something a driver can search for.
        real = self._poi(name="Woqod Hilal Gas Station", poi_class="fuel")
        vague = self._poi(name="Al Mansoura", poi_class="yes")
        self.assertLess(bake.feature_rank(real), bake.feature_rank(vague))

    def test_pois_still_rank_below_every_other_kind(self):
        # The floor is what guarantees POIs a share; the rank must not let them
        # displace roads and water in pass 2.
        #
        # `building` was in this list and had to come out (V7 3D). It was a
        # claim about a kind the bake emitted NOTHING of — no `kind=building`
        # feature existed in any tile — so the assertion never ran against real
        # data and the relationship it named was never true. Now that buildings
        # render, the honest order is the reverse: a POI is a place a driver
        # searches for and routes to, a footprint is decoration, so a building
        # yields to a POI and not the other way round. See
        # `BuildingPriorityTest`.
        poi = self._poi(name="Cafe", poi_class="cafe")
        for kind in ("water", "park", "landuse", "label"):
            self.assertLess(bake.feature_rank(Feat({"kind": kind})),
                            bake.feature_rank(poi), kind)
        self.assertGreater(bake.feature_rank(Feat({"kind": "building"})),
                           bake.feature_rank(poi), "building")

    def test_the_order_is_total_and_deterministic(self):
        """Same input, same tile — a rebake must not reshuffle the map."""
        feats = [self._poi(name=f"P{i}", poi_class="cafe") for i in range(50)]
        self.assertEqual([bake.feature_rank(f) for f in feats],
                         [bake.feature_rank(f) for f in feats])


class PoiTiebreakTest(unittest.TestCase):
    """What decides between two POIs that rank identically.

    `select_for_tile` sorts by `feature_rank` and Python's sort is stable, so a
    tie is settled by position in the source file, in any tile over the cap.
    The Overture places layer is appended after the OSM basemap, so where the
    two interleave badly Overture loses. Measured across the ten truncated z14
    tiles over central Doha, its share of shipped POIs ranged from 82% to 0%;
    on 14/10537/7002, 1,831 Overture and 1,189 OSM POIs competed for 525 slots
    and the tile shipped 525 OSM and no Overture.
    """

    @staticmethod
    def _poi(name, *, overture=None, osm=None, category="restaurant"):
        props = {"kind": "poi", "name": name, "category": category}
        if overture:
            props["overture_id"] = overture
        f = Feat(props)
        f.id = osm or "feature-0"
        return f

    def test_the_tiebreak_is_stable_across_builds(self):
        """Pinned. A re-bake must not reshuffle which shops a driver sees.

        This is why `hash()` cannot be used: it is seeded per process for str,
        so it is deterministic within one run and different on the next.
        """
        f = self._poi("Green Tea Garden Restaurant",
                      overture="0abd9111-e7e6-43f1-858d-54e8f32d0708")
        self.assertAlmostEqual(bake._stable_tiebreak(f), 0.8065622757285105,
                               places=12)

    def test_identity_not_file_position_decides(self):
        """The same place ranks the same wherever it sits in the source."""
        a = self._poi("Cafe", overture="abc-123")
        b = self._poi("Cafe", overture="abc-123")
        b.id = "feature-99999"
        self.assertEqual(bake.feature_rank(a), bake.feature_rank(b))

    def test_two_different_places_do_not_tie(self):
        self.assertNotEqual(bake._stable_tiebreak(self._poi("A", overture="a")),
                            bake._stable_tiebreak(self._poi("B", overture="b")))

    def test_the_quality_signals_still_beat_the_tiebreak(self):
        """The tie-break is a LAST resort, not a competing ranking."""
        good = self._poi("Woqod Hilal", overture="zzz-last", category="fuel")
        vague = self._poi("Something", overture="aaa-first", category="yes")
        self.assertLess(bake.feature_rank(good), bake.feature_rank(vague))
        unnamed = Feat({"kind": "poi", "category": "fuel"})
        unnamed.id = "feature-0"
        self.assertLess(bake.feature_rank(good), bake.feature_rank(unnamed))

    def test_neither_source_is_systematically_starved(self):
        """The regression this class exists for.

        A 60/40 candidate split must produce roughly a 60/40 selection, not
        100/0. Tolerance is wide because a hash is uniform, not exact.
        """
        overture = [self._poi(f"O{i}", overture=f"ov-{i}") for i in range(1831)]
        osm = [self._poi(f"S{i}", osm=f"w{i}") for i in range(1189)]
        feats = osm + overture          # the real merge order: Overture last
        chosen = bake.select_for_tile(feats, 1500, 14)
        picked_ov = sum(1 for f in chosen
                        if (f.properties or {}).get("overture_id"))
        picked_osm = len(chosen) - picked_ov
        self.assertGreater(picked_ov, 0, "Overture starved to zero again")
        share = picked_ov / len(chosen)
        self.assertGreater(share, 0.45, f"Overture under-represented: {share:.2f}")
        self.assertLess(share, 0.75, f"Overture over-represented: {share:.2f}")
        self.assertGreater(picked_osm, 0, "OSM starved to zero")

    def test_selection_is_identical_on_a_rebake(self):
        overture = [self._poi(f"O{i}", overture=f"ov-{i}") for i in range(900)]
        osm = [self._poi(f"S{i}", osm=f"w{i}") for i in range(900)]
        first = [f.properties["name"] for f in
                 bake.select_for_tile(osm + overture, 1500, 14)]
        # Same features, different order in the file — same tile contents.
        second = [f.properties["name"] for f in
                  bake.select_for_tile(overture + osm, 1500, 14)]
        self.assertEqual(sorted(first), sorted(second))
