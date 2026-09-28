"""Overture places must reach the tiles, and nothing else.

A clean `bootstrap.sh` run produced tiles with no Overture in them at all, while
the live deployment's tiles are mostly Overture — measured on live z14
10537/7003: 617 POIs, 509 of them Overture. The POI a driver could see in Google
Maps and not in Vector, "Turkish Grill House", is one of them.

The cause was not a regression in this repo; the step never existed. Overture
reaches the stack by two routes and bootstrap fed neither:

  * the GEOCODER auto-detects `<index>_places.geojson` beside its index
    (`vector_geocoder.serve.default_places_path`), so the file only has to be
    staged into the basemap volume;
  * the TILES need it merged into the bake input, because
    `build_qatar_tiles.py --geojson` takes exactly one file.

These pin the merge, which is the part with a sharp edge.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(os.path.dirname(_HERE))
_BOOTSTRAP = os.path.join(_ROOT, "bootstrap.sh")


def _merge_script():
    """The merge, lifted verbatim out of bootstrap.sh's heredoc."""
    out, keep = [], False
    with open(_BOOTSTRAP, encoding="utf-8") as fh:
        for line in fh:
            if line.rstrip() == "MERGE_PLACES":
                break
            if keep:
                out.append(line)
            if line.rstrip().endswith("<<'MERGE_PLACES'"):
                keep = True
    assert out, "could not find the bake-input merge in bootstrap.sh"
    return "".join(out)


def _run_merge(base, places):
    d = tempfile.mkdtemp()
    try:
        bp = os.path.join(d, "base.geojson")
        pp = os.path.join(d, "places.geojson")
        op = os.path.join(d, "out.geojson")
        sp = os.path.join(d, "merge.py")
        with open(bp, "w", encoding="utf-8") as fh:
            json.dump(base, fh, ensure_ascii=False)
        with open(pp, "w", encoding="utf-8") as fh:
            json.dump(places, fh, ensure_ascii=False)
        with open(sp, "w", encoding="utf-8") as fh:
            fh.write(_merge_script())
        p = subprocess.run([sys.executable, sp, bp, pp, op],
                           capture_output=True, text=True, timeout=120)
        if not os.path.isfile(op):
            return None, p.stdout + p.stderr
        with open(op, encoding="utf-8") as fh:
            return json.load(fh), p.stdout + p.stderr
    finally:
        import shutil
        shutil.rmtree(d, ignore_errors=True)


def _osm_feature(name, poi_class="restaurant"):
    return {"type": "Feature", "id": "n1",
            "geometry": {"type": "Point", "coordinates": [51.5, 25.3]},
            "properties": {"kind": "poi", "poi_class": poi_class, "name": name}}


def _overture_feature(name, oid="ov-1"):
    return {"type": "Feature",
            "geometry": {"type": "Point", "coordinates": [51.5351511, 25.2589245]},
            "properties": {"kind": "poi", "name": name, "source": "overture",
                           "overture_id": oid, "confidence": 0.9,
                           "category": "turkish_restaurant"}}


def _road():
    return {"type": "Feature", "id": "w1",
            "geometry": {"type": "LineString",
                         "coordinates": [[51.5, 25.3], [51.51, 25.31]]},
            "properties": {"kind": "road", "highway": "primary", "name": "Al Adl"}}


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.places = {"type": "FeatureCollection",
                       "features": [_overture_feature("Turkish Grill House")]}

    def test_places_land_in_features_not_turn_restrictions(self):
        """THE DEFECT. `osm_to_geojson` writes a THIRD top-level key when there
        are turn restrictions, so the file's final `]}` closes
        turn_restrictions, not features. Appending there is valid JSON and
        silently wrong: the feature count never changes and nothing downstream
        complains. The first version of this merge did exactly that and put
        23,199 POIs into the turn-restriction list."""
        base = {"type": "FeatureCollection",
                "features": [_road(), _osm_feature("Kebab King")],
                "turn_restrictions": [{"id": "r1", "kind": "no_left_turn"}]}
        out, log = _run_merge(base, self.places)
        self.assertIsNotNone(out, log)
        self.assertEqual(len(out["features"]), 3, "places did not reach `features`")
        names = [f["properties"].get("name") for f in out["features"]]
        self.assertIn("Turkish Grill House", names)

    def test_turn_restrictions_are_dropped_from_the_bake_input(self):
        # build_qatar_tiles reads `features` only; $GEO keeps them for routing.
        base = {"type": "FeatureCollection", "features": [_road()],
                "turn_restrictions": [{"id": "r1"}]}
        out, log = _run_merge(base, self.places)
        self.assertIsNotNone(out, log)
        self.assertNotIn("turn_restrictions", out)
        self.assertEqual(out["type"], "FeatureCollection")

    def test_a_base_without_restrictions_still_merges(self):
        base = {"type": "FeatureCollection", "features": [_road()]}
        out, log = _run_merge(base, self.places)
        self.assertIsNotNone(out, log)
        self.assertEqual(len(out["features"]), 2)

    def test_an_empty_base_does_not_produce_a_stray_comma(self):
        base = {"type": "FeatureCollection", "features": []}
        out, log = _run_merge(base, self.places)
        self.assertIsNotNone(out, log)
        self.assertEqual(len(out["features"]), 1)

    def test_both_sources_survive_and_stay_distinguishable(self):
        # The tile pipeline tells them apart by `overture_id` vs `poi_class`,
        # and `_tiebreak_key` depends on that.
        base = {"type": "FeatureCollection",
                "features": [_osm_feature("Kebab King")],
                "turn_restrictions": [{"id": "r1"}]}
        out, _ = _run_merge(base, self.places)
        props = [f["properties"] for f in out["features"]]
        self.assertEqual(sum(1 for p in props if "overture_id" in p), 1)
        self.assertEqual(sum(1 for p in props if "poi_class" in p), 1)

    def test_overture_properties_are_carried_through_unchanged(self):
        # The normalization is fetch_overture_places.py's; the merge must not
        # reshape it.
        base = {"type": "FeatureCollection", "features": []}
        out, _ = _run_merge(base, self.places)
        p = out["features"][0]["properties"]
        self.assertEqual(p["source"], "overture")
        self.assertEqual(p["overture_id"], "ov-1")
        self.assertEqual(p["category"], "turkish_restaurant")
        self.assertEqual(p["confidence"], 0.9)
        self.assertEqual(p["kind"], "poi")

    def test_non_ascii_names_survive_the_byte_splice(self):
        # The splice writes bytes; `ensure_ascii=False` output must round-trip.
        base = {"type": "FeatureCollection", "features": []}
        places = {"type": "FeatureCollection",
                  "features": [_overture_feature("مطعم الشرق")]}
        out, _ = _run_merge(base, places)
        self.assertEqual(out["features"][0]["properties"]["name"], "مطعم الشرق")


class BootstrapWiringTest(unittest.TestCase):
    """Static: the two routes are fed, and the routing input is not."""

    def setUp(self):
        with open(_BOOTSTRAP, encoding="utf-8") as fh:
            self.text = fh.read()

    def test_places_are_staged_for_the_geocoder_by_convention(self):
        # vector_geocoder.serve.default_places_path looks for
        # <index>_places.geojson beside the index, and step 3 copies
        # $WORK/*.geojson into the basemap volume.
        self.assertIn('cp "$PLACES_SRC" "$WORK/${REGION}_places.geojson"', self.text)

    def test_the_bake_reads_the_merged_input(self):
        self.assertIn('--geojson "$BAKE_GEO"', self.text)

    def test_the_merged_input_is_not_copied_into_the_basemap_volume(self):
        # It lives under a subdirectory so `cp /src/*.geojson` cannot match it:
        # the basemap volume should hold the same two files the live one does.
        self.assertIn('BAKE_GEO="$WORK/bake-input/${REGION}.geojson"', self.text)

    def test_the_roads_graph_is_built_from_the_osm_only_geojson(self):
        # THE GUARANTEE: Overture must never reach routing.
        self.assertIn('python3 - "$GEO" "$ROADS"', self.text)
        self.assertNotIn('python3 - "$BAKE_GEO" "$ROADS"', self.text)

    def test_the_osrm_input_is_the_osm_snapshot(self):
        self.assertIn("cp '/data/${REGION}.osm'", self.text)

    def test_overture_is_allowed_to_fail(self):
        # Fewer POIs is a worse map, not a broken one — same contract as 1c/1d.
        self.assertIn("[2b] WARNING: no Overture places", self.text)

    def test_bake_falls_back_to_osm_only_when_places_are_missing(self):
        self.assertIn('BAKE_GEO="$GEO"', self.text)


if __name__ == "__main__":
    unittest.main()
