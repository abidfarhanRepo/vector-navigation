package dev.vector.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How MANY labels the driver is shown, and which.
 *
 * `VectorStyleTest` pins what the POI layers ask for. This file pins what the
 * answer COSTS: on a real production tile over a dense residential street in Al
 * Mansoura there are 125 POIs inside 400 m, and before this
 * the style drew 66 names of them on one 480x1040 dp screenful at z16 and 38 at
 * `MapCamera.NAV_ZOOM`. Collision detection was the only thing limiting the
 * count, and collision detection draws everything that fits.
 *
 * ## These tests EVALUATE the expressions, they do not grep them
 *
 * A `symbol-sort-key` that mentions "gas_station" is not the same claim as a
 * gas station outranking a laundry, and the previous round of this work could
 * only assert the first. [evalExpr] below is a small interpreter for the subset
 * of the MapLibre expression language this style uses, so every assertion here
 * is made against the real compiled expression, with a real feature's
 * properties, at a stated zoom — the same three inputs MapLibre has.
 *
 * The numbers in the assertions are not invented either. They come from the
 * same expressions run over four decoded production tiles
 * (`15/21074/14005` and neighbours) through MapLibre's own style-spec
 * expression engine and a greedy placement identical to the renderer's:
 *
 * ```text
 *              before   after     after + V7 source split
 *   z16          66       23               18
 *   z16.5        38       13                9    <- the zoom the app navigates at
 *   z17          15       15                5
 *   z18           2        2                0
 * ```
 *
 * The third column is the one that matters, and it is why the rank ceiling is
 * 3 and not 2. This file's cap and `vector_ingestion/poi/visibility.py` are two
 * reductions applied to the same screenful, and they MULTIPLY: with Overture
 * Places demoted to search-only the 125 POIs here become 36 OSM-primary and 17
 * after the quality rules, and a ceiling of 2 on top of that drew SIX labels at
 * z16 and TWO at navigation zoom. Bounded had become empty.
 *
 * So when either side changes, re-measure rather than trusting these tests:
 * they pin the SHAPE of the rule — a schedule by rank, ties broken by quality,
 * nothing deleted — and a shape can be perfectly correct over a bake that has
 * nothing left in it. `scratchpad/density/sweep.mjs` is the measurement.
 */
class VectorStyleDensityTest {

    // ---------------------------------------------------------------- harness

    private fun style(theme: VectorStyle.MapTheme = VectorStyle.MapTheme.DARK): JSONObject =
        JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 15, theme))

    private fun layer(id: String, theme: VectorStyle.MapTheme = VectorStyle.MapTheme.DARK): JSONObject {
        val a = style(theme).getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .first { it.getString("id") == id }
    }

    private fun labelFilter() = layer("poi-labels").getJSONArray("filter")
    private fun sortKey() =
        layer("poi-labels").getJSONObject("layout").getJSONArray("symbol-sort-key")

    /**
     * A POI as the tiles carry one. Named arguments so a test reads as the
     * sentence it is testing.
     */
    private fun poi(
        name: String = "A Place",
        category: String? = null,
        poiClass: String? = null,
        family: String? = null,
        quality: Double? = null,
        confidence: Double? = null,
    ): Map<String, Any?> = buildMap {
        put("kind", "poi")
        put("name", name)
        category?.let { put("category", it) }
        poiClass?.let { put("poi_class", it) }
        family?.let { put("poi_family", it) }
        quality?.let { put("quality_score", it) }
        confidence?.let { put("confidence", it) }
    }

    private fun labelled(f: Map<String, Any?>, zoom: Double): Boolean =
        evalExpr(labelFilter(), f, zoom) == true

    private fun rank(f: Map<String, Any?>): Double =
        (evalExpr(sortKey(), f, 16.0) as Number).toDouble()

    /**
     * The subset of the MapLibre expression language the POI rules use.
     *
     * Small on purpose: an interpreter that guesses at operators it has not
     * been taught would make these tests agree with themselves rather than with
     * the renderer, so an unknown operator throws.
     */
    private fun evalExpr(e: Any?, f: Map<String, Any?>, zoom: Double): Any? {
        if (e !is JSONArray) return if (e == JSONObject.NULL) null else e
        val args = (1 until e.length()).map { e.get(it) }
        fun ev(x: Any?) = evalExpr(x, f, zoom)
        fun num(x: Any?) = (ev(x) as Number).toDouble()
        return when (val op = e.getString(0)) {
            "zoom" -> zoom
            "get" -> f[ev(args[0]) as String]
            "has" -> f.containsKey(ev(args[0]) as String)
            "literal" -> args[0]
            "all" -> args.all { ev(it) == true }
            "any" -> args.any { ev(it) == true }
            "!" -> ev(args[0]) != true
            "==" -> ev(args[0]) == ev(args[1])
            "<=" -> num(args[0]) <= num(args[1])
            ">=" -> num(args[0]) >= num(args[1])
            "+" -> args.sumOf { num(it) }
            "-" -> num(args[0]) - num(args[1])
            "to-number" -> num(args[0])
            "downcase" -> (ev(args[0]) as String).lowercase()
            "concat" -> args.joinToString("") { ev(it)?.toString() ?: "" }
            "index-of" -> (ev(args[1]) as String).indexOf(ev(args[0]) as String)
            "coalesce" -> args.firstNotNullOfOrNull { ev(it) }
            "case" -> {
                var i = 0
                var out: Any? = null
                while (i + 1 < args.size) {
                    if (ev(args[i]) == true) { out = ev(args[i + 1]); break }
                    i += 2
                }
                out ?: ev(args.last())
            }
            "step" -> {
                val input = num(args[0])
                var out = ev(args[1])
                var i = 2
                while (i + 1 < args.size) {
                    if (input >= num(args[i])) out = ev(args[i + 1])
                    i += 2
                }
                out
            }
            "match" -> {
                val input = ev(args[0])
                var i = 1
                var out: Any? = null
                while (i + 1 < args.size) {
                    val labels = args[i]
                    val hit = if (labels is JSONArray)
                        (0 until labels.length()).any { labels.get(it) == input }
                    else labels == input
                    if (hit) { out = ev(args[i + 1]); break }
                    i += 2
                }
                out ?: ev(args.last())
            }
            else -> throw IllegalArgumentException("evalExpr does not know $op")
        }
    }

    // -------------------------------------------------- the interpreter itself

    @Test
    fun `the expression interpreter agrees with the rules it is about to judge`() {
        // If this ever fails, every other assertion in the file is worthless.
        assertTrue("an office registration is still refused outright",
                   !labelled(poi(category = "corporate_office"), 18.0))
        assertTrue("somebody else's home is still refused outright",
                   !labelled(poi(name = "Barwa Workers Accommodation"), 18.0))
        assertFalse("a weakly conflated Overture record is still refused",
                    labelled(poi(category = "restaurant", confidence = 0.3), 18.0))
        assertTrue("a feature with no confidence is not demoted for lacking one",
                   labelled(poi(category = "restaurant"), 18.0))
    }

    // ----------------------------------------------- rank drives which survive

    @Test
    fun `two places of the same kind are separated by their quality, not by tile order`() {
        // THE stability property. MapLibre breaks a tie in `symbol-sort-key` by
        // the order features happen to sit in the tile, and that order is not
        // stable across the tiles loading and unloading around a moving car —
        // so a tie is a label that flickers. Before this, EVERY restaurant in
        // Doha shared the key 2, and which one you got was whichever the bucket
        // happened to emit first.
        val good = poi(name = "Well surveyed", category = "restaurant", quality = 0.91)
        val poor = poi(name = "Barely conflated", category = "restaurant", quality = 0.43)
        assertNotEquals("same key means the tile decides, not the data",
                        rank(good), rank(poor))
        assertTrue("the better record must win the contested slot: ${rank(good)} vs ${rank(poor)}",
                   rank(good) < rank(poor))
    }

    @Test
    fun `quality can never promote a laundry over a pharmacy`() {
        // quality_score measures how well the RECORD is known, not how much the
        // driver wants it. It is the minor key and has to stay inside its rank:
        // a perfectly surveyed dry cleaner is still not what you look for on a
        // running tank.
        val perfectLaundry = poi(category = "laundry", family = "SERVICES", quality = 1.0)
        val doubtfulPharmacy = poi(category = "pharmacy", quality = 0.0)
        assertTrue("rank must dominate quality", rank(doubtfulPharmacy) < rank(perfectLaundry))
    }

    @Test
    fun `a record with no quality score is ranked in the middle, not last`() {
        // `["to-number", null]` is 0, which would put every feature from a
        // pre-canonical bake — and every learned POI — at the BACK of its own
        // rank. 0.5 is the neutral value the ingestion pipeline itself uses for
        // a missing signal.
        val unscored = poi(category = "restaurant")
        val worst = poi(category = "restaurant", quality = 0.05)
        val best = poi(category = "restaurant", quality = 0.95)
        assertTrue("a missing score must beat a known-bad one",
                   rank(unscored) < rank(worst))
        assertTrue("and must not beat a known-good one",
                   rank(best) < rank(unscored))
    }

    @Test
    fun `an unlisted category is ranked by its family rather than dumped with the offices`() {
        // 781 Overture categories and 312 OSM classes cannot be enumerated by
        // hand. `asian_restaurant`, `filipino_restaurant`, `turkish_restaurant`
        // and `sri_lankan_restaurant` are all real food, all absent from every
        // list in the style, and all ranked identically with a hairdresser
        // before the canonical pipeline started shipping `poi_family`.
        val food = poi(category = "sri_lankan_restaurant", family = "FOOD", quality = 0.6)
        val service = poi(category = "hairdresser", family = "SERVICES", quality = 0.6)
        assertTrue("a restaurant nobody typed out is still a restaurant: " +
                   "${rank(food)} vs ${rank(service)}", rank(food) < rank(service))
    }

    @Test
    fun `an unknown family still gets a label, it just gets it last`() {
        // Being wrong about a category has always had to cost a POI its
        // priority and never its existence.
        val mystery = poi(name = "Something", category = "no_such_category")
        assertTrue("an unclassifiable POI must still reach the map",
                   labelled(mystery, 17.0))
    }

    // ------------------------------------------------- the cap itself, by zoom

    @Test
    fun `at driving zoom the map spends its labels on destinations, not on services`() {
        // The ceiling was 2 -- landmarks only -- while the tiles still carried
        // Overture Places. V7 moved Overture to search-only, and re-measuring
        // through MapLibre's own expression engine against the same four
        // production tiles with the split applied gave 6 labels at z16 and
        // TWO at NAV_ZOOM. That is not a bounded map, it is an empty one.
        //
        // At ceiling 3 the same measurement gives 18 and 9. So an everyday
        // destination is labelled while driving: once the directory listings
        // are gone, a restaurant IS somewhere a driver is going -- which is
        // what 854481e asked for when it said to spend the labels on places a
        // driver could actually be going to.
        //
        // Rank 4 -- the services a driver does not navigate to at speed --
        // still waits. Note most of these no longer reach the map at all after
        // the split (`usefulness=SUPPORT`), so this asserts the SCHEDULE, not
        // the contents of any particular bake.
        for (c in listOf("gas_station", "pharmacy", "mosque", "hospital")) {
            assertTrue("$c must be labelled at driving zoom", labelled(poi(category = c), 16.0))
        }
        for (c in listOf("hotel", "shopping_center", "supermarket", "school", "park")) {
            assertTrue("$c must be labelled at driving zoom", labelled(poi(category = c), 16.0))
        }
        for (c in listOf("restaurant", "cafe", "bakery", "convenience_store")) {
            assertTrue("$c is an everyday destination and belongs at driving zoom",
                       labelled(poi(category = c), 16.0))
        }
        for (c in listOf("hairdresser", "laundry", "beauty_salon")) {
            assertFalse("$c is a service errand and must wait for z17",
                        labelled(poi(category = c, family = "SERVICES"), 16.0))
        }
    }

    @Test
    fun `the cap is a schedule, not a second denylist`() {
        // Everything `poi-dots` draws must get a name at SOME zoom, or the rank
        // has quietly become another way to delete a place — which is the one
        // thing the POI rules have never been allowed to do.
        val everything = listOf(
            poi(category = "gas_station"), poi(category = "hotel"),
            poi(category = "restaurant"), poi(category = "cafe"),
            poi(category = "hairdresser", family = "SERVICES"),
            poi(category = "no_such_category"),
            poi(poiClass = "fuel"), poi(poiClass = "supermarket"),
            poi(name = "unclassified thing"),
        )
        for (p in everything) {
            assertTrue("${p["category"] ?: p["poi_class"] ?: p["name"]} must be named by z17",
                       labelled(p, 17.0))
            assertTrue("and still at z18", labelled(p, 18.0))
        }
    }

    @Test
    fun `the shopfront a driver is arriving at is on the map when they arrive`() {
        // "Green Tea Garden Restaurant is right in front of my destination and
        // not on the map" was a real complaint and a real fix, and no cap may
        // undo it. MapCamera.ZOOM_BANDS puts the camera at 17.0 below 25 km/h
        // -- the arrival -- and this record sits 9 m from where the driver
        // parked on 2026-09-14.
        //
        // At ceiling 3 it is labelled at driving zoom too, which is strictly
        // better for the complaint that produced this test. The assertion that
        // matters is the arrival, so that is what is pinned; the z16 case is
        // covered by the schedule test above and left free to move with the
        // ceiling.
        val greenTeaGarden = poi(name = "Green Tea Garden Restaurant",
                                 category = "restaurant", quality = 0.811,
                                 confidence = 0.9)
        assertTrue("the destination's own shopfront must be named on arrival",
                   labelled(greenTeaGarden, 17.0))
        // A service errand is the thing the cap holds back, and even it must
        // arrive by z17 -- the cap is a schedule, never a delete.
        val laundry = poi(name = "Al Rayes Laundry", category = "laundry",
                          family = "SERVICES", quality = 0.5)
        assertFalse("a laundry is not worth a label at 60 km/h",
                    labelled(laundry, 16.0))
        assertTrue("but it is named once the car has slowed",
                   labelled(laundry, 17.0))
    }

    @Test
    fun `the rank ceiling is read from the live zoom, not baked into the layer`() {
        // MapLibre evaluates a layer filter once per tile against that tile's
        // overscaledZ, so the same z15 data is laid out again at z16, z17 and
        // z18 with the filter re-run each time. A `minzoom` on the layer could
        // not express this — it is one threshold, and this needs two.
        val f = labelFilter().toString()
        assertTrue("the cap must be a zoom expression: $f", f.contains("\"zoom\""))
        assertTrue("and a step, so it opens at a stated zoom", f.contains("step"))
        assertEquals("the layer floor stays where the speed argument put it",
                     16, layer("poi-labels").getInt("minzoom"))
        assertEquals("and the dots still say something is there",
                     14, layer("poi-dots").getInt("minzoom"))
    }

    @Test
    fun `the dots are never gated by the rank the labels are gated by`() {
        // A dot is cheap and says "something is here". Only the NAME costs the
        // driver reading time, so only the name is rationed.
        val dots = layer("poi-dots").getJSONArray("filter").toString()
        assertFalse("a dot must not depend on the camera zoom: $dots", dots.contains("\"zoom\""))
        assertTrue("and the labels must still ask the dots' question first",
                   labelFilter().getJSONArray(1).toString() == dots)
    }

    // --------------------------------------------------------- both themes

    @Test
    fun `the density rules do not change with the time of day`() {
        // A night drive and a day drive must show the same places. Only the
        // colours are allowed to differ.
        for (id in listOf("poi-labels", "poi-dots")) {
            assertEquals("$id filter differs between themes",
                         layer(id, VectorStyle.MapTheme.DARK).getJSONArray("filter").toString(),
                         layer(id, VectorStyle.MapTheme.LIGHT).getJSONArray("filter").toString())
        }
        assertEquals("the ranking differs between themes",
                     layer("poi-labels", VectorStyle.MapTheme.DARK)
                         .getJSONObject("layout").toString(),
                     layer("poi-labels", VectorStyle.MapTheme.LIGHT)
                         .getJSONObject("layout").toString())
    }

    @Test
    fun `the road and place labels keep the rules they had`() {
        // The POI cap must not have been paid for out of the street names.
        val road = layer("road-labels")
        assertEquals(13, road.getInt("minzoom"))
        assertEquals("line", road.getJSONObject("layout").getString("symbol-placement"))
        assertEquals(300, road.getJSONObject("layout").getInt("symbol-spacing"))
        assertFalse("a road label must not be rationed by POI rank",
                    road.getJSONArray("filter").toString().contains("quality_score"))
        val place = layer("place-labels")
        assertEquals(4, place.getInt("minzoom"))
        assertTrue("places stay ranked by place rank",
                   place.getJSONObject("layout").getJSONArray("symbol-sort-key")
                       .toString().contains("country"))
    }

    @Test
    fun `no two POIs of the same kind can tie for the same slot`() {
        // The stability property, stated as a property rather than a pair. Nine
        // hotels sit inside 400 m of the driver; if any two of them tie, which
        // one the driver sees changes with the tile load order.
        val hotels = (1..9).map {
            poi(name = "Hotel $it", category = "hotel", quality = 0.60 + it * 0.013)
        }
        val keys = hotels.map { rank(it) }
        assertEquals("every hotel must have its own place in the queue: $keys",
                     keys.size, keys.toSet().size)
    }
}
