"""What the PBF extractor takes out of a six-country file.

`fetch_qatar_pbf.py` replaces the Overpass acquisition with a dated Geofabrik
snapshot. The converter, the tile generator and the styles are untouched, so
the ONLY thing that can change the resulting map is which objects the extractor
selects — and a PBF contains every object in six countries, so over-selecting
is as much a defect as under-selecting.

These pin the selection to exactly what `bootstrap.sh` asks Overpass for today
(steps 1, 1b, 1c, 1d, 1e). They are pure dict predicates, so they run without
osmium and belong in the normal suite.

Buildings are the one selection that has deliberately WIDENED, and it has done
so twice. Through V7 the rule was "named, or stating a height", which on this
source is 2,752 of 189,866 footprints — 1.4%. V7.6 takes all of them, because
the consumer changed: the flat `buildings` fill layer draws blocks and needs no
height, and starving it is what made dense Doha render as empty ground.

Widening a selection is still a defect unless the far end can absorb it, so
that is pinned too, in `test_building_fabric.py`: buildings rank last in
`build_qatar_tiles._KIND_ORDER` with no reserved floor, so they take only
leftover tile budget and can never displace a road. An unnamed footprint still
never becomes a PLACE — `osm_to_geojson._attrs` drops it as a POI, see
`test_poi_areas.NamedBuildingTest`. It becomes a polygon, which is a different
branch and a different thing.
"""

import importlib.util
import os
import unittest

_SCRIPT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "scripts", "fetch_qatar_pbf.py",
)
_spec = importlib.util.spec_from_file_location("fetch_qatar_pbf", _SCRIPT)
pbf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pbf)


class WaySelectionTest(unittest.TestCase):
    def test_highways_are_selected(self):
        for hw in ("motorway", "residential", "service", "footway"):
            self.assertTrue(pbf.want_way({"highway": hw}), hw)

    def test_basemap_areas_are_selected(self):
        self.assertTrue(pbf.want_way({"natural": "water"}))
        self.assertTrue(pbf.want_way({"leisure": "park"}))
        self.assertTrue(pbf.want_way({"landuse": "residential"}))

    def test_coastline_is_selected(self):
        # Step 1c fetches it as its own request; Qatar is a peninsula and
        # without it the country view has no land/sea edge.
        self.assertTrue(pbf.want_way({"natural": "coastline"}))

    def test_poi_areas_are_selected(self):
        # Step 1d. A mall, hospital or school is normally the building polygon.
        for k, v in (("amenity", "hospital"), ("shop", "mall"),
                     ("tourism", "hotel"), ("office", "company"),
                     ("healthcare", "clinic"), ("craft", "carpenter"),
                     ("historic", "fort")):
            self.assertTrue(pbf.want_way({k: v, "name": "X"}), k)

    def test_a_named_building_is_selected(self):
        self.assertTrue(pbf.want_way({"building": "yes", "name": "Tornado Tower"}))

    def test_an_unnamed_building_is_selected_for_the_fabric(self):
        # V7.6, and the inverse of what this test asserted through V7. The
        # old rule kept 2,752 of 189,866 footprints; the 187,114 it discarded
        # are the city itself, and they were discarded at the EXTRACT, so no
        # downstream change could ever have recovered them.
        self.assertTrue(pbf.want_way({"building": "yes"}))
        self.assertTrue(pbf.want_way({"building": "residential"}))
        self.assertTrue(pbf.want_way({"building": "house"}))
        # Still selected for the reasons they always were.
        self.assertTrue(pbf.want_way({"building": "yes", "height": "195"}))
        self.assertTrue(pbf.want_way({"building": "yes", "name": "Tornado Tower"}))

    def test_widening_buildings_did_not_widen_anything_else(self):
        # The guard on the guard: `building` is the only key that became
        # sufficient on its own. A way carrying some OTHER unselected tag is
        # still not selected just because this rule moved.
        for tags in ({"barrier": "wall"}, {"power": "line"},
                     {"man_made": "pier"}, {"boundary": "administrative"},
                     {"railway": "rail"}, {"waterway": "drain"}):
            self.assertFalse(pbf.want_way(tags), tags)

    def test_untagged_and_irrelevant_ways_are_not_selected(self):
        self.assertFalse(pbf.want_way({}))
        self.assertFalse(pbf.want_way({"barrier": "fence"}))
        self.assertFalse(pbf.want_way({"power": "line"}))


class NodeSelectionTest(unittest.TestCase):
    def test_the_main_query_classes_are_selected(self):
        for k in ("amenity", "shop", "tourism", "place", "aeroway"):
            self.assertTrue(pbf.want_node({k: "x"}), k)

    def test_the_step_1e_classes_are_selected(self):
        # The node/way asymmetry step 1e closes: these tags are fetched as
        # ways by 1d, so a dentist mapped as a polygon was found and the same
        # dentist mapped as a node was not.
        for k in ("office", "healthcare", "craft", "historic"):
            self.assertTrue(pbf.want_node({k: "x"}), k)

    def test_map_furniture_is_not_selected(self):
        # Measured: fetching all thirteen POI_KEYS as nodes returns 1,777
        # objects of which only 46% carry a name, the largest classes being
        # stop_position, level_crossing, switch, fire_hydrant and buffer_stop.
        # Those are infrastructure, not destinations, and they would compete
        # for the per-tile POI budget against the shops a driver searches for.
        for k in ("emergency", "railway", "public_transport", "information"):
            self.assertFalse(pbf.want_node({k: "x"}), k)

    def test_an_untagged_node_is_not_selected(self):
        self.assertFalse(pbf.want_node({}))


class RelationSelectionTest(unittest.TestCase):
    def test_turn_restrictions_are_selected(self):
        self.assertTrue(pbf.want_relation({"type": "restriction"}))

    def test_other_relations_are_not(self):
        # The converter reads no other relation type — `osm_to_geojson`
        # keeps a relation only when type == "restriction". Selecting
        # multipolygons here would put objects in the file that nothing
        # downstream turns into features.
        self.assertFalse(pbf.want_relation({"type": "multipolygon"}))
        self.assertFalse(pbf.want_relation({"type": "route"}))
        self.assertFalse(pbf.want_relation({}))


class BboxTest(unittest.TestCase):
    def test_the_default_bbox_is_the_bootstrap_bbox(self):
        # .env.example sets VECTOR_BBOX=24.4,50.7,26.2,51.8 as
        # lat_min,lon_min,lat_max,lon_max; this script uses lon/lat order.
        self.assertEqual(pbf.DEFAULT_BBOX, (50.7, 24.4, 51.8, 26.2))


if __name__ == "__main__":
    unittest.main()
