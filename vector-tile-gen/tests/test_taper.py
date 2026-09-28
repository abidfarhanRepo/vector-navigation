"""Carriageway width tapers at lane-count steps (V8 native acceptance §9.4).

Fixtures on a local metre grid near Doha, like test_lanes.py, so a length
asserted here is a length on the ground.
"""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from vector_tile_gen import taper as T  # noqa: E402

LON0, LAT0 = 51.50, 25.30
KY = 6378137.0 * math.pi / 180.0
KX = KY * math.cos(math.radians(LAT0))


def ll(x, y):
    return [LON0 + x / KX, LAT0 + y / KY]


class F:
    def __init__(self, fid, pts, **props):
        self.id = fid
        self.geometry_type = "LineString"
        self.coordinates = [ll(x, y) for x, y in pts]
        self.properties = dict({"kind": "road", "car": True, "highway": "primary"}, **props)
        self.bbox = None


def length(coords):
    return T._cum(coords)[-1]


def pieces(out, fid):
    return [g for g in out if g.id == fid]


def drawn(ps):
    """What a NEW client's carriageway layers draw: everything but the carrier."""
    return [g for g in ps if not g.properties.get(T.CARRIER)]


def _proj(way, pt):
    """(distance to the line in m, along-line distance) by exact projection."""
    cs = way.coordinates
    cum = T._cum(cs)
    k = math.cos(math.radians(LAT0))
    best = None
    for i, (a, b) in enumerate(zip(cs, cs[1:])):
        ax, ay = a[0] * k, a[1]; bx, by = b[0] * k, b[1]; px, py = pt[0] * k, pt[1]
        dx, dy = bx - ax, by - ay; L = dx * dx + dy * dy
        t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
        q = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        d = T._dist(q, pt)
        if best is None or d < best[0]:
            best = (d, cum[i] + (cum[i + 1] - cum[i]) * t)
    return best


def along(way, pt):
    """Along-line distance of `pt` on `way` (it lies on the line)."""
    return _proj(way, pt)[1]


def span(way, part):
    """(from, to) along the original line."""
    return along(way, part.coordinates[0]), along(way, part.coordinates[-1])


def drawn_lanes(way, ps, s):
    """What a new client paints at along-distance s: the widest part covering it."""
    wide = float(T.surface_lanes(way.properties))
    best = None
    for g in drawn(ps):
        a, b = span(way, g)
        if a - 1e-6 <= s <= b + 1e-6:
            w = g.properties.get("lw", wide)
            best = w if best is None else max(best, w)
    return best


def profile(way, ps, step=0.5):
    total = T._cum(way.coordinates)[-1]
    n = int(total / step)
    return [(i * step, drawn_lanes(way, ps, i * step)) for i in range(n + 1)]


def assert_no_exposed_joint(tc, way, ps):
    """Old clients draw every part at full width. Their union must be the whole
    way, the outermost ends must be the way's own, and every other part end must
    lie strictly inside some other part."""
    total = T._cum(way.coordinates)[-1]
    spans = [span(way, g) for g in drawn(ps)]
    tc.assertAlmostEqual(min(a for a, _ in spans), 0.0, delta=0.02)
    tc.assertAlmostEqual(max(b for _, b in spans), total, delta=0.02)
    for s_ in [i * 0.5 for i in range(int(total / 0.5) + 1)]:
        tc.assertTrue(any(a - 1e-6 <= s_ <= b + 1e-6 for a, b in spans), s_)
    for i, (a, b) in enumerate(spans):
        for e in (a, b):
            if abs(e) < 0.02 or abs(e - total) < 0.02:
                continue
            tc.assertTrue(any(j != i and a2 + 0.01 < e < b2 - 0.01 for j, (a2, b2) in enumerate(spans)),
                          (i, e))


class StraightStep(unittest.TestCase):
    """3 lanes continuing into 2: the wider way narrows over its last 30 m."""

    def setUp(self):
        self.wide = F("w1", [(0, 0), (200, 0)], lanes="3")
        self.narrow = F("w2", [(200, 0), (400, 0)], lanes="2")
        self.out, self.stats = T.apply_tapers([self.wide, self.narrow])
        self.ps = pieces(self.out, "w1")

    def test_only_the_wider_way_is_cut(self):
        self.assertIs(pieces(self.out, "w2")[0], self.narrow)
        self.assertEqual(len(pieces(self.out, "w2")), 1)
        # carrier + body + one part per step; a 1-lane step needs 1/MAX_STEP_LANES
        self.assertEqual(len(self.ps), 2 + math.ceil(1.0 / T.MAX_STEP_LANES - 1e-9))
        self.assertEqual(self.stats["steps"], 1)
        self.assertEqual(self.stats["step_3_to_2"], 1)

    def test_the_drawn_width_is_a_30_metre_staircase_down_to_the_narrow_road(self):
        prof = profile(self.wide, self.ps)
        narrowed = [s_ for s_, w in prof if w < 3.0 - 1e-9]
        self.assertAlmostEqual(min(narrowed), 170.0, delta=0.6)
        ws = [w for _, w in prof]
        self.assertEqual(ws, sorted(ws, reverse=True))      # never widens toward the node
        self.assertLess(ws[-1], 2.1)
        self.assertGreater(ws[-1], 2.0)
        steps = [a - b for a, b in zip(ws, ws[1:]) if a != b]
        self.assertLessEqual(max(steps), T.MAX_STEP_LANES + 1e-9)

    def test_lw_is_strictly_between_the_two_widths(self):
        for g in self.ps:
            if "lw" in g.properties:
                self.assertTrue(2.0 < g.properties["lw"] < 3.0)

    def test_old_clients_see_the_whole_road_with_no_exposed_joint(self):
        assert_no_exposed_joint(self, self.wide, self.ps)
        self.assertEqual(self.ps[-1].coordinates[-1], self.wide.coordinates[-1])

    def test_every_part_reaches_back_inside_the_full_width_road(self):
        for g in self.ps:
            if "lw" in g.properties:
                self.assertAlmostEqual(span(self.wide, g)[0], 200 - 30 - T.LEVER_M, delta=0.05)

    def test_tags_are_kept_and_only_lw_is_added(self):
        for g in self.ps:
            extra = set(g.properties) - set(self.wide.properties)
            self.assertLessEqual(extra, {"lw", T.CARRIER})
            self.assertEqual(g.properties["lanes"], "3")
        self.assertNotIn("lw", self.wide.properties)     # the input is not mutated

    def test_the_carrier_is_the_original_way_plus_one_flag(self):
        carrier = self.ps[0]
        self.assertIs(carrier.properties[T.CARRIER], True)
        self.assertEqual(carrier.coordinates, self.wide.coordinates)
        self.assertEqual({k: v for k, v in carrier.properties.items() if k != T.CARRIER},
                         self.wide.properties)
        self.assertEqual(sum(1 for g in self.ps if g.properties.get(T.CARRIER)), 1)

    def test_only_the_carrier_carries_the_label(self):
        wide = F("w1", [(0, 0), (200, 0)], lanes="3", name="شارع", **{"name:en": "Test St", "ref": "12"})
        narrow = F("w2", [(200, 0), (400, 0)], lanes="2")
        out, _ = T.apply_tapers([wide, narrow])
        ps = pieces(out, "w1")
        named = [g for g in ps if {"name", "name:en", "ref"} & set(g.properties)]
        self.assertEqual(len(named), 1)
        self.assertTrue(named[0].properties[T.CARRIER])
        self.assertEqual(named[0].properties["name:en"], "Test St")
        for g in drawn(ps):
            self.assertEqual(g.properties["highway"], "primary")

    def test_bbox_is_recomputed_for_each_piece(self):
        for g in self.ps:
            xs = [c[0] for c in g.coordinates]
            self.assertEqual(g.bbox[0], min(xs))
            self.assertEqual(g.bbox[2], max(xs))


class ReverseAndStartEnd(unittest.TestCase):
    def test_narrow_into_wide_tapers_the_start_of_the_wider_way(self):
        narrow = F("w1", [(0, 0), (200, 0)], lanes="1")
        wide = F("w2", [(200, 0), (400, 0)], lanes="3")
        out, _ = T.apply_tapers([narrow, wide])
        ps = pieces(out, "w2")
        prof = profile(wide, ps)
        narrowed = [s_ for s_, w in prof if w < 3.0 - 1e-9]
        self.assertAlmostEqual(max(narrowed), 60.0, delta=0.6)
        ws = [w for _, w in prof]
        self.assertEqual(ws, sorted(ws))                    # widening away from the node
        self.assertLess(ws[0], 1.1)
        self.assertEqual(ps[0].coordinates[0], wide.coordinates[0])
        assert_no_exposed_joint(self, wide, ps)

    def test_digitised_head_to_head_still_tapers_the_wider_way(self):
        wide = F("w1", [(0, 0), (200, 0)], lanes="4")
        narrow = F("w2", [(400, 0), (200, 0)], lanes="2")  # ends at the same node
        out, st = T.apply_tapers([wide, narrow])
        self.assertEqual(st["steps"], 1)
        self.assertTrue(any("lw" in g.properties for g in pieces(out, "w1")))


class NotASimpleContinuation(unittest.TestCase):
    def test_a_junction_node_is_left_alone(self):
        a = F("w1", [(0, 0), (200, 0)], lanes="3")
        b = F("w2", [(200, 0), (400, 0)], lanes="2")
        c = F("w3", [(200, 0), (200, 200)], lanes="2")
        out, st = T.apply_tapers([a, b, c])
        self.assertEqual(st.get("steps", 0), 0)
        self.assertEqual([g.id for g in out], ["w1", "w2", "w3"])

    def test_a_road_passing_through_the_node_makes_it_a_junction(self):
        a = F("w1", [(0, 0), (200, 0)], lanes="3")
        b = F("w2", [(200, 0), (400, 0)], lanes="2")
        through = F("w3", [(200, -100), (200, 0), (200, 100)], lanes="2")
        _, st = T.apply_tapers([a, b, through])
        self.assertEqual(st.get("steps", 0), 0)

    def test_equal_widths_are_not_touched(self):
        a = F("w1", [(0, 0), (200, 0)], lanes="2")
        b = F("w2", [(200, 0), (400, 0)], highway="secondary")   # fallback 2
        out, st = T.apply_tapers([a, b])
        self.assertIs(out[0], a)
        self.assertIs(out[1], b)

    def test_non_drivable_features_neither_count_nor_change(self):
        a = F("w1", [(0, 0), (200, 0)], lanes="3")
        b = F("w2", [(200, 0), (400, 0)], lanes="2")
        path = F("w3", [(200, 0), (200, 50)], lanes="1")
        path.properties["car"] = False
        out, st = T.apply_tapers([a, b, path])
        self.assertEqual(st["steps"], 1)
        self.assertIs(out[-1], path)

    def test_tunnels_are_not_tapered(self):
        a = F("w1", [(0, 0), (200, 0)], lanes="3", tunnel="yes")
        b = F("w2", [(200, 0), (400, 0)], lanes="2", tunnel="yes")
        out, st = T.apply_tapers([a, b])
        self.assertIs(out[0], a)
        self.assertEqual(st.get("steps", 0), 0)


class Widths(unittest.TestCase):
    def test_a_missing_lanes_tag_uses_the_style_class_fallback(self):
        a = F("w1", [(0, 0), (200, 0)], highway="primary")
        b = F("w2", [(200, 0), (400, 0)], highway="secondary")
        _, st = T.apply_tapers([a, b])
        self.assertEqual(st["step_3_to_2"], 1)

    def test_a_short_wider_way_tapers_at_most_45_percent_of_itself(self):
        a = F("w1", [(0, 0), (40, 0)], lanes="5")
        b = F("w2", [(40, 0), (140, 0)], lanes="2")
        out, _ = T.apply_tapers([a, b])
        ps = pieces(out, "w1")
        narrowed = [s_ for s_, w in profile(a, ps, 0.25) if w < 5.0 - 1e-9]
        self.assertAlmostEqual(min(narrowed), 22.0, delta=0.3)
        assert_no_exposed_joint(self, a, ps)

    def test_a_big_step_on_a_short_way_still_moves_at_most_max_step(self):
        # 6 -> 2 wants 134 steps; MAX_PIECES caps it, so the bound is the larger
        # of MAX_STEP_LANES and delta / MAX_PIECES (plus lw's 3-decimal rounding)
        a = F("w1", [(0, 0), (40, 0)], lanes="6")
        b = F("w2", [(40, 0), (140, 0)], lanes="2")
        out, _ = T.apply_tapers([a, b])
        ws = [w for _, w in profile(a, pieces(out, "w1"), 0.05)]
        steps = [x - y for x, y in zip(ws, ws[1:]) if x != y]
        bound = max(T.MAX_STEP_LANES, 4.0 / T.MAX_PIECES) + 0.001
        self.assertLessEqual(max(steps), bound)

    def test_a_way_stepping_at_both_ends_tapers_both(self):
        a = F("w1", [(0, 0), (100, 0)], lanes="2")
        mid = F("w2", [(100, 0), (160, 0)], lanes="3")
        c = F("w3", [(160, 0), (300, 0)], lanes="2")
        out, st = T.apply_tapers([a, mid, c])
        self.assertEqual(st["steps"], 2)
        ps = pieces(out, "w2")
        prof = profile(mid, ps)
        self.assertLess(prof[0][1], 2.1)
        self.assertLess(prof[-1][1], 2.1)
        self.assertEqual(max(w for _, w in prof), 3.0)       # full width in the middle
        assert_no_exposed_joint(self, mid, ps)

    def test_a_short_way_tapered_at_both_ends_keeps_each_lever_out_of_the_other_taper(self):
        # 56.5 m, 3 lanes, 1 lane at both ends (w619559071 in the real data):
        # a 30 m lever from either taper would reach into the other one and
        # paint its near-full width over the narrowing
        a = F("w1", [(-100, 0), (0, 0)], lanes="1")
        mid = F("w2", [(0, 0), (56.5, 0)], lanes="3")
        c = F("w3", [(56.5, 0), (156.5, 0)], lanes="1")
        out, _ = T.apply_tapers([a, mid, c])
        ps = pieces(out, "w2")
        prof = profile(mid, ps, 0.05)
        ws = [w for _, w in prof]
        peak = ws.index(max(ws))
        self.assertEqual(ws[:peak + 1], sorted(ws[:peak + 1]))
        self.assertEqual(ws[peak:], sorted(ws[peak:], reverse=True))
        steps = [abs(x - y) for x, y in zip(ws, ws[1:]) if x != y]
        self.assertLessEqual(max(steps), max(T.MAX_STEP_LANES, 2.0 / T.MAX_PIECES) + 0.001)
        assert_no_exposed_joint(self, mid, ps)
        keys = [(tuple(map(tuple, g.coordinates)), g.properties.get("lw")) for g in drawn(ps)]
        self.assertEqual(len(keys), len(set(keys)))          # no duplicate parts

    def test_a_taper_under_six_metres_is_not_cut(self):
        a = F("w1", [(0, 0), (10, 0)], lanes="3")
        b = F("w2", [(10, 0), (100, 0)], lanes="2")
        out, _ = T.apply_tapers([a, b])
        self.assertIs(out[0], a)


class Geometry(unittest.TestCase):
    """Every part lies on its own way: it is the way's own line, cut."""

    def test_a_bend_inside_the_taper_is_followed_not_cut_across(self):
        a = F("w1", [(0, 0), (188, 0), (199.3, 4.1)], lanes="3")
        b = F("w2", [(199.3, 4.1), (300, 40)], lanes="2")
        out, _ = T.apply_tapers([a, b])
        ps = pieces(out, "w1")
        bend = a.coordinates[1]
        for g in ps:
            s0, s1 = span(a, g)
            if s0 < 188 - 1e-6 and s1 > 188 + 1e-6:
                self.assertIn(bend, g.coordinates)
            for c in g.coordinates:
                self.assertLess(_proj(a, c)[0], 0.01)
        assert_no_exposed_joint(self, a, ps)


class Determinism(unittest.TestCase):
    def test_same_input_same_output(self):
        def run():
            a = F("w1", [(0, 0), (120, 30), (200, 30)], lanes="4")
            b = F("w2", [(200, 30), (400, 30)], lanes="2")
            out, st = T.apply_tapers([a, b])
            return [(g.id, g.coordinates, sorted(g.properties.items())) for g in out], st
        self.assertEqual(run(), run())


if __name__ == "__main__":
    unittest.main()


class BakeWithTaper(unittest.TestCase):
    """build_qatar_tiles.py --taper end to end: pieces at z14+, none below, none without the flag."""

    @classmethod
    def setUpClass(cls):
        import json
        import tempfile
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts"))
        import build_qatar_tiles as bake
        import mapbox_vector_tile as mvt
        from vector_tile_gen.tiles import lonlat_to_tile
        cls.tmp = tempfile.TemporaryDirectory()
        feats = [
            {"type": "Feature", "id": "w1", "geometry": {"type": "LineString", "coordinates": [ll(0, 0), ll(200, 0)]},
             "properties": {"kind": "road", "car": True, "highway": "primary", "lanes": "3", "name": "Test St"}},
            {"type": "Feature", "id": "w2", "geometry": {"type": "LineString", "coordinates": [ll(200, 0), ll(400, 0)]},
             "properties": {"kind": "road", "car": True, "highway": "primary", "lanes": "2", "name": "Test St"}},
        ]
        gj = os.path.join(cls.tmp.name, "in.geojson")
        with open(gj, "w") as fh:
            json.dump({"type": "FeatureCollection", "features": feats}, fh)
        cls.out = {}
        for flag in (True, False):
            out = os.path.join(cls.tmp.name, "on" if flag else "off")
            argv = ["--geojson", gj, "--out", out, "--zooms", "13,14,15", "--no-version-bump"]
            if flag:
                argv.append("--taper")
            assert bake.main(argv) == 0
            cls.out[flag] = out
        cls.mvt, cls.tile = mvt, staticmethod(lonlat_to_tile)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def _lw(self, flag, z):
        x, y = self.tile(z, *ll(199, 0))
        with open(os.path.join(self.out[flag], "tiles", str(z), str(x), f"{y}.mvt"), "rb") as fh:
            layers = self.mvt.decode(fh.read())
        return [f["properties"].get("lw") for f in layers["basemap"]["features"]]

    def test_z14_and_z15_carry_the_taper(self):
        for z in (14, 15):
            lws = [w for w in self._lw(True, z) if w is not None]
            self.assertEqual(len(lws), math.ceil(1.0 / T.MAX_STEP_LANES - 1e-9), z)
            self.assertTrue(all(2.0 < w < 3.0 for w in lws))

    def test_z13_keeps_the_untouched_way(self):
        self.assertEqual([w for w in self._lw(True, 13) if w is not None], [])
        self.assertEqual(len(self._lw(True, 13)), 2)

    def test_without_the_flag_no_tile_has_lw(self):
        for z in (13, 14, 15):
            self.assertEqual([w for w in self._lw(False, z) if w is not None], [])

    def test_the_bake_writes_a_taper_report_only_with_the_flag(self):
        self.assertTrue(os.path.exists(os.path.join(self.out[True], "taper-bake-report.json")))
        self.assertFalse(os.path.exists(os.path.join(self.out[False], "taper-bake-report.json")))


class TaperNeverTakesTheTileBudget(unittest.TestCase):
    """Regression: the first tapered bake ranked pieces on their own, and in a
    full z14 tile they evicted buildings, parks and other roads."""

    def test_a_capped_tile_keeps_exactly_the_same_non_road_features(self):
        import json
        import tempfile
        from collections import Counter
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts"))
        import build_qatar_tiles as bake
        import mapbox_vector_tile as mvt
        from vector_tile_gen.tiles import lonlat_to_tile
        feats = [
            {"type": "Feature", "id": "w1", "geometry": {"type": "LineString", "coordinates": [ll(0, 0), ll(200, 0)]},
             "properties": {"kind": "road", "car": True, "highway": "primary", "lanes": "4"}},
            {"type": "Feature", "id": "w2", "geometry": {"type": "LineString", "coordinates": [ll(200, 0), ll(400, 0)]},
             "properties": {"kind": "road", "car": True, "highway": "primary", "lanes": "2"}},
        ]
        for i in range(40):
            x, y = 20 + 9 * i, 30
            ring = [ll(x, y), ll(x + 6, y), ll(x + 6, y + 6), ll(x, y + 6), ll(x, y)]
            feats.append({"type": "Feature", "id": f"w{100 + i}",
                          "geometry": {"type": "Polygon", "coordinates": [ring]},
                          "properties": {"kind": "building"}})
        with tempfile.TemporaryDirectory() as tmp:
            gj = os.path.join(tmp, "in.geojson")
            with open(gj, "w") as fh:
                json.dump({"type": "FeatureCollection", "features": feats}, fh)
            got = {}
            for flag in (False, True):
                out = os.path.join(tmp, str(flag))
                argv = ["--geojson", gj, "--out", out, "--zooms", "15", "--no-version-bump",
                        "--max-per-tile", "20"]
                if flag:
                    argv.append("--taper")
                self.assertEqual(bake.main(argv), 0)
                x, y = lonlat_to_tile(15, *ll(199, 0))
                with open(os.path.join(out, "tiles", "15", str(x), f"{y}.mvt"), "rb") as fh:
                    got[flag] = mvt.decode(fh.read())["basemap"]["features"]
        kinds = {k: Counter(f["properties"].get("kind") for f in v) for k, v in got.items()}
        self.assertEqual(kinds[False]["building"], kinds[True]["building"])
        self.assertLess(kinds[False]["building"], 40)            # the cap really bit
        self.assertTrue(any("lw" in f["properties"] for f in got[True]))
        # every road way the untapered tile kept is still there
        self.assertEqual({f["id"] for f in got[False] if f["properties"].get("kind") == "road"},
                         {f["id"] for f in got[True] if f["properties"].get("kind") == "road"})


class ExpandSelected(unittest.TestCase):
    def test_parts_far_outside_the_tile_are_left_out_and_others_kept(self):
        a = F("w1", [(0, 0), (5000, 0)], lanes="3")
        b = F("w2", [(5000, 0), (5200, 0)], lanes="2")
        parts, _ = T.taper_parts([a, b])
        by_obj = {id(a): parts[0]}
        for g in parts[0]:
            xs = [c[0] for c in g.coordinates]; ys = [c[1] for c in g.coordinates]
            g.bbox = (min(xs), min(ys), max(xs), max(ys))
        # a tile around the START of w1, far from its tapered end
        tb = (ll(-100, -100)[0], ll(-100, -100)[1], ll(100, 100)[0], ll(100, 100)[1])
        out = T.expand_selected([a, b], by_obj, tb)
        kept = [g for g in out if g.id == "w1"]
        self.assertEqual([g.properties.get("lw") for g in kept], [None, None])   # carrier, body
        self.assertTrue(kept[0].properties[T.CARRIER])
        self.assertIs(out[-1], b)
