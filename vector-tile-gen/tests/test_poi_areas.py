"""POIs that are mapped as AREAS, and buildings that only have a name.

Reported from the S24: *"names of places are entirely missing when compared to
Google Maps, such as business names in buildings."* Measured against the live
Overpass API for Qatar's bbox rather than assumed:

    POI *nodes*  (amenity/shop/tourism/office/healthcare)   8,657   <- fetched
    POI *ways*   (the same tags on an AREA)                 5,439   <- was not
    named buildings (`building` + `name`)                   2,077   <- was not

Two separate defects produced that gap:

1. **`bootstrap.sh` asked for POIs as nodes only.** But a mall, a hospital, a
   school or any large shop is normally mapped as the building POLYGON, so the
   biggest and most searchable places in Doha were exactly the ones missing.
   The converter already handled POI ways; nothing ever asked for them.
2. **A way tagged only `building=yes` + `name` had no branch at all** and
   returned no properties, so it was dropped. "Tornado Tower" could not be
   searched for, because it was never a feature.

Measured after: POIs 8,735 -> 15,231, named POIs 5,467 -> 7,924, and a live
search for "tornado" returns Tornado Tower.
"""

import importlib.util
import json
import os
import tempfile
import unittest

_SCRIPT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "scripts", "osm_to_geojson.py",
)
_spec = importlib.util.spec_from_file_location("osm_to_geojson", _SCRIPT)
o2g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(o2g)


def _convert(xml: str) -> list:
    """Run the real converter over one OSM document and return its features."""
    with tempfile.TemporaryDirectory() as d:
        src = os.path.join(d, "in.osm")
        dst = os.path.join(d, "out.geojson")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(xml)
        o2g.main([src, "-o", dst])
        with open(dst, encoding="utf-8") as fh:
            return json.load(fh)["features"]


def _square(wid, tags, lon=51.50, lat=25.30, size=0.002):
    """A closed square way with the given tags."""
    ids = [wid * 10 + i for i in range(4)]
    corners = [
        (lon, lat), (lon + size, lat), (lon + size, lat + size), (lon, lat + size),
    ]
    nodes = "".join(
        f'<node id="{i}" lat="{c[1]}" lon="{c[0]}"/>' for i, c in zip(ids, corners)
    )
    nds = "".join(f'<nd ref="{i}"/>' for i in ids + [ids[0]])
    tagxml = "".join(f'<tag k="{k}" v="{v}"/>' for k, v in tags.items())
    return f'<?xml version="1.0"?><osm version="0.6">{nodes}' \
           f'<way id="{wid}">{nds}{tagxml}</way></osm>'


class CentroidTest(unittest.TestCase):
    """A POI area's position is the centre of its shape, not a corner of it."""

    def test_a_square_centroid_is_its_middle(self):
        cx, cy = o2g._ring_centroid(
            [[0.0, 0.0], [2.0, 0.0], [2.0, 2.0], [0.0, 2.0], [0.0, 0.0]]
        )
        self.assertAlmostEqual(cx, 1.0, places=9)
        self.assertAlmostEqual(cy, 1.0, places=9)

    def test_the_centroid_of_an_L_shape_can_fall_outside_it(self):
        # An honest limit, pinned rather than glossed. An L of side 3 with a
        # 2x2 notch has its centre of AREA at (1.1, 1.1), which is inside the
        # notch and therefore outside the building. A guaranteed-interior point
        # needs a pole-of-inaccessibility search (polylabel); this is not that,
        # and the docstring says so.
        #
        # It is still a large improvement on `coords[0]`, which was a corner.
        ring = [[0, 0], [3, 0], [3, 1], [1, 1], [1, 3], [0, 3], [0, 0]]
        cx, cy = o2g._ring_centroid([[float(a), float(b)] for a, b in ring])
        self.assertAlmostEqual(cx, 1.1, places=9)
        self.assertAlmostEqual(cy, 1.1, places=9)
        inside = (cx <= 1.0) or (cy <= 1.0)
        self.assertFalse(inside, "the L's centroid is expected to be in the notch")

    def test_the_centroid_is_not_the_vertex_mean(self):
        # The reason for the shoelace: OSM buildings are not evenly vertexed. A
        # long straight wall carries two points while a curved facade carries
        # thirty, so the vertex mean is dragged toward whichever side is mapped
        # in more detail. Here the bottom edge is subdivided and the true centre
        # of area stays put.
        dense = [[0.0, 0.0], [0.5, 0.0], [1.0, 0.0], [1.5, 0.0], [2.0, 0.0],
                 [2.0, 2.0], [0.0, 2.0], [0.0, 0.0]]
        cx, cy = o2g._ring_centroid(dense)
        self.assertAlmostEqual(cx, 1.0, places=9)
        self.assertAlmostEqual(cy, 1.0, places=9)
        ring = dense[:-1]
        mean_y = sum(p[1] for p in ring) / len(ring)
        self.assertNotAlmostEqual(mean_y, 1.0, places=2)

    def test_winding_order_does_not_flip_the_centroid(self):
        cw = [[0.0, 0.0], [0.0, 2.0], [2.0, 2.0], [2.0, 0.0], [0.0, 0.0]]
        ccw = list(reversed(cw))
        self.assertEqual(o2g._ring_centroid(cw), o2g._ring_centroid(ccw))

    def test_a_zero_area_ring_falls_back_to_the_vertex_mean(self):
        # A degenerate way must not divide by zero and take the bake down.
        collinear = [[0.0, 0.0], [1.0, 0.0], [2.0, 0.0], [0.0, 0.0]]
        cx, cy = o2g._ring_centroid(collinear)
        self.assertAlmostEqual(cy, 0.0, places=9)
        self.assertTrue(0.0 <= cx <= 2.0)

    def test_two_points_average(self):
        cx, cy = o2g._ring_centroid([[0.0, 0.0], [2.0, 4.0]])
        self.assertAlmostEqual(cx, 1.0, places=9)
        self.assertAlmostEqual(cy, 2.0, places=9)

    def test_no_points_does_not_raise(self):
        self.assertEqual(o2g._ring_centroid([]), (0.0, 0.0))

    def test_a_poi_area_lands_in_the_middle_of_the_building(self):
        # End to end, through the real converter. It used to emit coords[0] —
        # an arbitrary corner of the polygon, which for Villaggio Mall is a
        # car-park entrance several hundred metres from the door, and it is the
        # coordinate search returns, routes to, and measures "8.7 km away" from.
        feats = _convert(_square(1, {"shop": "mall", "name": "Test Mall"},
                                 lon=51.50, lat=25.30, size=0.002))
        pois = [f for f in feats if f["properties"].get("kind") == "poi"]
        self.assertEqual(len(pois), 1)
        lon, lat = pois[0]["geometry"]["coordinates"]
        # 5 dp is about a metre, which is the precision that means anything
        # for a POI; the OSM XML round-trip costs ~1e-6 of a degree.
        self.assertAlmostEqual(lon, 51.501, places=5)
        self.assertAlmostEqual(lat, 25.301, places=5)

    def test_a_poi_area_becomes_a_point_not_a_polygon(self):
        # Search, the poi-dots layer and the poi-labels layer all want a point.
        feats = _convert(_square(2, {"amenity": "hospital", "name": "Test Hospital"}))
        pois = [f for f in feats if f["properties"].get("kind") == "poi"]
        self.assertEqual(pois[0]["geometry"]["type"], "Point")


class NamedBuildingTest(unittest.TestCase):
    """A named building is a place, even with no other tag."""

    def test_a_named_building_becomes_a_searchable_place(self):
        # Qatar has 2,077 of these. There is no `building` branch in
        # classify_tags, so a way tagged only `building=yes` + `name` returned
        # empty properties and was dropped — "Tornado Tower" was never a
        # feature, so no amount of search tuning could have found it.
        feats = _convert(_square(3, {"building": "yes", "name": "Tornado Tower"}))
        pois = [f for f in feats if f["properties"].get("kind") == "poi"]
        self.assertEqual(len(pois), 1, "a named building was dropped")
        self.assertEqual(pois[0]["properties"]["name"], "Tornado Tower")

    def test_a_named_building_carries_no_invented_category(self):
        # `building=yes` means "this exists", not a category. The geocoder
        # already refuses to present that as one, so emitting
        # `poi_class: building` here would only push a useless word onto the
        # driver's screen. A name and a distance is the honest row.
        feats = _convert(_square(4, {"building": "yes", "name": "Some Tower"}))
        poi = [f for f in feats if f["properties"].get("kind") == "poi"][0]
        self.assertNotIn("poi_class", poi["properties"])

    def test_an_unnamed_building_is_still_not_a_place(self):
        # Doha has tens of thousands of them and none is searchable.
        feats = _convert(_square(5, {"building": "yes"}))
        pois = [f for f in feats if f["properties"].get("kind") == "poi"]
        self.assertEqual(pois, [])

    def test_a_real_category_beats_the_building_fallback(self):
        # A building tagged with what it IS must report that, not fall through
        # to the nameless-building branch. The POI branch is checked first.
        feats = _convert(_square(6, {"building": "yes", "shop": "supermarket",
                                     "name": "Test Market"}))
        poi = [f for f in feats if f["properties"].get("kind") == "poi"][0]
        self.assertEqual(poi["properties"]["poi_class"], "supermarket")

    def test_english_names_survive(self):
        feats = _convert(_square(7, {"building": "yes", "name": "برج تورنيدو",
                                     "name:en": "Tornado Tower"}))
        poi = [f for f in feats if f["properties"].get("kind") == "poi"][0]
        self.assertEqual(poi["properties"]["name:en"], "Tornado Tower")


if __name__ == "__main__":
    unittest.main()
