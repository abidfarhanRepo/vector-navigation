package dev.vector.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3D road: is it drawn at the width the road actually is?
 *
 * ## Why this file evaluates expressions instead of reading them
 *
 * Every other claim in the V8 cartography work is checkable by looking at a
 * screenshot. This one is not, and that is exactly what makes it dangerous.
 * The width arithmetic rests on MapLibre's 512-logical-pixel convention, and
 * if that assumption is wrong it is wrong **by exactly a factor of two** —
 * every road in Qatar half or double its true width, which looks entirely
 * plausible either way. Nobody would catch it by eye; a demo would ship with
 * a lane-true road that is not lane-true.
 *
 * So the widths are asserted against arithmetic derived independently of the
 * style, at the zooms V8 §5.3 tabulates, with a real feature's `lanes` value.
 * [evalExpr] is the same trick `VectorStyleDensityTest` uses for the label
 * rules, extended with the operators the width expressions need.
 *
 * ## The other thing this file catches
 *
 * `["zoom"]` may only be the input to a TOP-LEVEL `step` or `interpolate` —
 * MapLibre says so in as many words (the string is in `libmaplibre.so`) and
 * enforces it by refusing to parse the document. A style that does not parse
 * renders a blank map and logs nothing useful, which is the same silent
 * failure mode as the missing font stack recorded in `VectorStyle`'s KDoc.
 *
 * V8 §5.3 proposes the width expression in precisely the illegal form
 * (`["*", lanes, ["interpolate", ..., ["zoom"], ...]]`). `no expression buries
 * a zoom curve` below is the regression test for taking the document's word
 * for it.
 */
class CarriagewayTest {

    // ---------------------------------------------------------------- harness
    //
    // The expression interpreter, the feature builder and the L* conversion all
    // live in [StyleProbe] — this file is the second caller and `CalloutTest`
    // is the third, so keeping a private copy per file would mean three
    // interpreters disagreeing about what MapLibre does.

    private val dark = StyleProbe(VectorStyle.MapTheme.DARK)

    private fun layer(id: String) = dark.layer(id)
    private fun ids() = dark.ids
    private fun road(
        lanes: Int? = null,
        highway: String = "primary",
        bridge: String? = null,
        tunnel: String? = null,
        car: Boolean = true,
    ) = StyleProbe.road(lanes, highway, bridge, tunnel, car)

    private fun evalExpr(e: Any?, f: Map<String, Any?>, zoom: Double) = dark.eval(e, f, zoom)
    private fun width(id: String, f: Map<String, Any?>, zoom: Double) = dark.width(id, f, zoom)
    private fun offset(id: String, f: Map<String, Any?>, zoom: Double) = dark.offset(id, f, zoom)
    private fun drawn(id: String, f: Map<String, Any?>, zoom: Double) = dark.drawn(id, f, zoom)
    private fun lstar(hex: String) = StyleProbe.lstar(hex)
    private fun layers(s: org.json.JSONObject): List<org.json.JSONObject> =
        s.getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun style(theme: VectorStyle.MapTheme = VectorStyle.MapTheme.DARK) =
        StyleProbe(theme).doc

    // ------------------------------------------------- the interpreter itself

    @Test
    fun `the interpolation this file judges widths with matches MapLibre's`() {
        // If this is wrong every assertion below is worthless, and the failure
        // would look like a passing suite over a wrong map.
        val e = JSONArray(
            """["interpolate", ["exponential", 2], ["zoom"], 14, 1.0, 20, 64.0]"""
        )
        val f = emptyMap<String, Any?>()
        // Base 2 through stops that are 2^6 apart is exactly a doubling per
        // step, so every intermediate zoom has a closed form to check against.
        for (z in 14..20) {
            assertEquals(
                "exponential-2 interpolation is not a doubling per zoom step at z$z",
                Math.pow(2.0, (z - 14).toDouble()), evalExpr(e, f, z.toDouble()) as Double, 1e-9,
            )
        }
    }

    // ------------------------------------------------------ the width, in metres

    @Test
    fun `the derivation agrees with the table it was derived from`() {
        // V8 §5.3, at Doha. One lane, in logical pixels, at each zoom the
        // document tabulates. This asserts the ARITHMETIC — `laneWidthPx` is
        // the single point where a real-world width becomes a screen width.
        // The document tabulates metres-per-pixel to two decimals and derives
        // the lane figures from the ROUNDED value, so it reads 9.2 px at z17.5
        // where the exact arithmetic gives 9.17. The tolerance is set to cover
        // that rounding and nothing wider: it is about a fiftieth of a pixel,
        // and the error this test exists to catch is a factor of two.
        val expected = mapOf(
            15.0 to 1.6, 16.5 to 4.6, 17.0 to 6.5, 17.5 to 9.2, 18.0 to 13.0,
        )
        for ((zoom, px) in expected) {
            assertEquals(
                "a ${VectorStyle.LANE_WIDTH_M} m lane is not $px logical px at z$zoom",
                px, VectorStyle.laneWidthPx(zoom), 0.05,
            )
        }
    }

    @Test
    fun `the navigation zoom is one a lane divider can actually be drawn at`() {
        // The reason NAV_ZOOM moved from 16.5. At 16.5 a lane is 4.6 px and
        // five dividers inside a three-lane road land within a couple of
        // pixels of each other — they alias into a grey smear rather than
        // reading as lanes. This is the floor the camera constant respects.
        assertTrue(
            "a lane is only ${"%.1f".format(VectorStyle.laneWidthPx(MapCamera.NAV_ZOOM))} px " +
                "at the navigation zoom — lane markings will not resolve",
            VectorStyle.laneWidthPx(MapCamera.NAV_ZOOM) >= 8.0,
        )
    }

    @Test
    fun `a three lane road is drawn three lanes wide`() {
        // The whole claim of the phase, in one assertion.
        for (z in listOf(15.0, 16.5, 17.5, 18.0, 19.0)) {
            val lane = VectorStyle.laneWidthPx(z)
            for (lanes in 1..6) {
                assertEquals(
                    "a $lanes-lane road is not $lanes lanes wide at z$z",
                    lane * lanes, width("carriageway", road(lanes = lanes), z), 0.01,
                )
            }
        }
    }

    @Test
    fun `an untagged road is drawn by its class rather than by one number`() {
        // 8% of the arterial network has no `lanes`, and every residential
        // street is in the same position. Something has to be drawn; a 7 m
        // parking aisle is as wrong as a 3.5 m arterial.
        val z = 17.5
        val lane = VectorStyle.laneWidthPx(z)
        assertEquals("a service road is not drawn as a single lane",
                     lane * 1, width("carriageway", road(highway = "service"), z), 0.01)
        assertEquals("a residential street is not drawn as two lanes",
                     lane * 2, width("carriageway", road(highway = "residential"), z), 0.01)
        assertEquals("an untagged trunk road is not drawn as three lanes",
                     lane * 3, width("carriageway", road(highway = "trunk"), z), 0.01)
        // And a real tag always beats the guess.
        assertEquals("a tagged lane count was overridden by the class fallback",
                     lane * 5, width("carriageway", road(lanes = 5, highway = "service"), z), 0.01)
    }

    @Test
    fun `the skirt overhangs the deck on both sides, by the same amount at every zoom`() {
        // The skirt is what gives the surface mass at pitch. If it tracked the
        // deck exactly it would be invisible; if it scaled with lane count the
        // overhang would grow with the road, which is not what a kerb does.
        for (z in listOf(15.0, 17.5, 19.0)) {
            for (lanes in listOf(1, 2, 4, 6)) {
                val over = width("carriageway-skirt", road(lanes = lanes), z) -
                    width("carriageway", road(lanes = lanes), z)
                assertEquals(
                    "the kerb overhang is not 1.2 m across a $lanes-lane road at z$z",
                    VectorStyle.metresPx(1.2, z), over, 0.01,
                )
            }
        }
    }

    @Test
    fun `the skirt is lifted in screen space, so it reads as a height`() {
        // A wider dark line under a narrower one is an outline, not a face.
        // The lip comes from shifting the skirt down the SCREEN, which is why
        // this is the one measurement in the style that is not in metres — and
        // why it must not be anchored to the map, where it would slide with
        // the bearing and scale with the zoom.
        for (id in listOf("carriageway-skirt", "carriageway-bridge-skirt")) {
            val paint = layer(id).getJSONObject("paint")
            assertEquals("$id's lip is anchored to the map, not the viewport",
                         "viewport", paint.getString("line-translate-anchor"))
            val t = paint.getJSONArray("line-translate")
            assertEquals("$id is lifted sideways rather than down the screen",
                         0.0, t.getDouble(0), 1e-9)
            assertTrue("$id has no lip at all", t.getDouble(1) > 0)
        }
        // And the deck itself is not translated, or the road would slide.
        assertFalse("the deck is offset from the road it is drawing",
                    layer("carriageway").getJSONObject("paint").has("line-translate"))
    }

    @Test
    fun `a bridge deck is thicker than the road it flies over`() {
        val z = 17.5
        val ground = width("carriageway-skirt", road(lanes = 3), z) -
            width("carriageway", road(lanes = 3), z)
        val deck = width("carriageway-bridge-skirt", road(lanes = 3, bridge = "yes"), z) -
            width("carriageway-bridge", road(lanes = 3, bridge = "yes"), z)
        assertTrue("a flyover's deck edge is no heavier than a kerb ($deck vs $ground)",
                   deck > ground)
    }

    // ------------------------------------------------------- where the paint goes

    @Test
    fun `a kerb is a casing under the deck, exactly one marking wide`() {
        // The kerb used to be two offset lines, one per side, sitting exactly on
        // the carriageway edge. It is now ONE line drawn UNDER the deck whose
        // width is the deck plus a marking on each side, so the same white rim
        // shows either way — the difference is that a casing cannot be painted
        // across another road's carriageway, and the measured cost of the old
        // arrangement was 81,526 m of exactly that
        // (`JUNCTION-AND-CURVE-DATA.md`, 24 real z15 tiles).
        //
        // Exact at every stop on the casing's own half-zoom grid, and within a
        // tenth of a pixel at the zooms BETWEEN them — which is where the two
        // ramps' different curves would otherwise diverge (see [casingWidth];
        // the first version was off by a whole marking at z19).
        for (z in listOf(15.0, 15.5, 16.0, 17.0, 18.0, 19.0, 20.0)) {
            for (lanes in 1..6) {
                val r = road(lanes = lanes)
                val rim = width("carriageway-casing", r, z) - width("carriageway", r, z)
                assertEquals(
                    "a $lanes-lane road's casing is not its deck plus two markings at z$z",
                    2 * markingWidthPx(z), rim, 0.01,
                )
            }
        }
        for (z in listOf(15.25, 16.75, 17.5, 18.25, 19.75)) {
            for (lanes in 1..6) {
                val r = road(lanes = lanes)
                val rim = width("carriageway-casing", r, z) - width("carriageway", r, z)
                assertEquals(
                    "a $lanes-lane road's rim drifts more than a tenth of a marking at z$z",
                    2 * markingWidthPx(z), rim, 0.12,
                )
            }
        }
    }

    @Test
    fun `no kerb or casing is drawn after any deck`() {
        // THE invariant, and the one the old document broke: an edge-painting
        // layer may only ever be visible where no carriageway covers it. With
        // every casing before every deck, a road's kerb physically cannot cross
        // the surface of a road that meets it — which is what replaces the
        // roundabout-only ordering fix with something that holds at every
        // junction in the country.
        val order = ids()
        val casings = order.filter { it.startsWith("carriageway-") &&
            (it.contains("casing") || it.contains("skirt")) }
        val decks = listOf("carriageway", "carriageway-circular", "carriageway-bridge")
            .map { order.indexOf(it) }
        assertTrue("no casing layer exists at all", casings.isNotEmpty())
        for (c in casings) {
            // The bridges are the exception and they are the right one: a
            // flyover is over the road beneath it, so its skirt and casing are
            // legitimately drawn after that road's deck. Nothing on the GROUND
            // may be.
            if (c.startsWith("carriageway-bridge")) continue
            val i = order.indexOf(c)
            assertTrue("$c is drawn after a carriageway it would be painted across",
                       i < decks.min())
        }
    }

    /** [VectorStyle.MARKING_WIDTH] evaluated at [z]; the test's own arithmetic. */
    private fun markingWidthPx(z: Double): Double {
        val stops = listOf(15.0 to 0.4, 16.0 to 0.7, 17.0 to 1.1, 18.0 to 1.7, 20.0 to 4.4)
        return when {
            z <= stops.first().first -> stops.first().second
            z >= stops.last().first -> stops.last().second
            else -> {
                val i = stops.indexOfFirst { it.first >= z }
                val (z0, v0) = stops[i - 1]
                val (z1, v1) = stops[i]
                v0 + (v1 - v0) * (z - z0) / (z1 - z0)
            }
        }
    }

    /**
     * Every layer that paints a line INSIDE the carriageway, discovered from
     * the style rather than enumerated here.
     *
     * This used to spell the ids out — `lane-divider-$n-$i` for every lane
     * count — and it went stale the moment the set of markings grew: the
     * centreline and the solid junction-approach layers were invisible to it,
     * so a two-way road reported zero dividers while the renderer drew one.
     * A test that lists what it expects to exist cannot notice something new
     * that exists, which is the wrong way round for a regression suite.
     */
    private fun interiorMarkingIds(): List<String> =
        ids().filter { it.startsWith("lane-divider-") || it.startsWith("centreline-") }

    /** Every divider layer that draws [f], with the offset it draws at. */
    private fun dividersFor(f: Map<String, Any?>, z: Double): List<Double> =
        interiorMarkingIds()
            .filter { drawn(it, f, z) }
            .map { offset(it, f, z) }
            .sorted()

    /**
     * A road as the tile carries it, with the tags this pass reads.
     *
     * [StyleProbe.road] is shared with two other test files and is not extended
     * here; these are plain map entries on top of what it builds, which is all
     * a feature is.
     */
    private fun roadWith(
        lanes: Int? = null,
        highway: String = "primary",
        oneway: Boolean = false,
        turnLanes: String? = null,
        junction: String? = null,
    ): Map<String, Any?> = road(lanes, highway) + buildMap {
        if (oneway) put("oneway", "yes")
        if (turnLanes != null) put("turn:lanes", turnLanes)
        if (junction != null) put("junction", junction)
    }

    private fun dash(id: String): String? = dark.paint(id, "line-dasharray")?.toString()







    @Test
    fun `the road surface costs the layers it is budgeted`() {
        // A number rather than a bound, because the failure this guards is
        // somebody adding a marking the obvious way — copying the dashed set,
        // which is one layer per (lane count, divider) pair and fans out to
        // fifteen. This style renders at 60 Hz on an emulator today and the
        // road is the largest single group in it.
        //
        //   ground    skirt + casing + deck                         3
        //   ring      skirt + casing + deck                         3
        //   bridge    skirt + casing + deck                         3
        //
        // It was 32. The eighteen dashed layers and the five solid ones are
        // gone: every one of them was placed with `line-offset`, which
        // MapLibre evaluates per feature, so each approach painted its lane
        // lines straight across every carriageway it met. See
        // `VectorStyle.MARKINGS_NEED_TRIMMED_GEOMETRY` for why that cannot be
        // fixed in the style and what brings them back.
        val n = ids().count {
            it.startsWith("carriageway") || it.startsWith("lane-divider") ||
                it.startsWith("centreline")
        }
        assertEquals("the road surface layer budget moved", 9, n)
    }

    @Test
    fun `no marking is drawn with an offset the renderer applies per feature`() {
        // The regression guard for the defect that removed them.
        //
        // Reported from a drive: a turn lane appearing to leave from the middle
        // lanes of C Ring Road, and a lattice of hairlines ruled across the
        // Rawdat Al Khail interchange with stubs ending in mid-air. Every one
        // of those lines was a lane divider drawn along a way that runs into a
        // junction node, composed after every carriageway deck.
        //
        // This asserts the absence rather than the arrangement, because the
        // arrangement has no correct form: a divider must be above its own deck
        // and below every other one, and MapLibre draws a layer at a time
        // across all features. Adding any offset marking back into this style
        // reintroduces the defect, so the test is on the whole class.
        val markings = ids().filter {
            it.startsWith("lane-divider") || it.startsWith("centreline")
        }
        assertEquals("an offset lane marking is back in the style: $markings",
                     emptyList<String>(), markings)
    }


    @Test
    fun `no layer asks MapLibre for a dash and a data-driven offset at once`() {
        // The general form of the defect `dividerOffset`'s KDoc records: a
        // layer with both renders NOTHING in MapLibre Native 11.13.5 — no
        // warning, no log line — and every other part of the road keeps
        // drawing, so it reads as missing data rather than as a broken style.
        // It was found once by bisection on a handset. This is the assertion
        // that means it cannot be found that way twice.
        for (theme in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            for (l in layers(style(theme))) {
                val paint = l.optJSONObject("paint") ?: continue
                if (paint.opt("line-dasharray") == null) continue
                val off = paint.opt("line-offset")?.toString() ?: continue
                assertFalse(
                    "${l.getString("id")} ($theme) carries a dash AND an offset that reads " +
                        "the feature ($off) — MapLibre draws nothing at all for that pair",
                    off.contains("\"get\"") || off.contains("\"has\""),
                )
            }
        }
    }

    @Test
    fun `a roundabout keeps its kerbs and loses the lane guess`() {
        // Two reasons, and the measurement is the first of them. A divider is
        // placed by offsetting the centreline, and a ring is the only geometry
        // in the network where that offset approaches the radius it is taken
        // around: over 49 z15 tiles the median local curve radius on a
        // `junction=roundabout` way is 12.1 m against 95.6 m for the network,
        // and the three worst offset folds in the whole sample are on
        // roundabouts.
        //
        // The second reason is the one that would matter even if the geometry
        // were perfect: a roundabout's lanes are concentric and which lane
        // leads to which exit IS the content of its markings. Vector has no
        // data for that. A dashed circle offset off the ring's centreline is a
        // guess drawn in the place a driver can least afford one.
        // The whole network now follows the rule this test was written for: no
        // marking is offset off a centreline anywhere. See
        // `no marking is drawn with an offset the renderer applies per feature`.
        val z = 17.5
        val ring = roadWith(lanes = 2, oneway = true, junction = "roundabout")
        // The surface and the kerbs are the half that is NOT a guess, and they
        // stay — drawn by the ring's own layers rather than the ground group's.
        assertFalse("a roundabout is still drawn by the ground deck",
                    drawn("carriageway", ring, z))
        assertTrue("a roundabout lost its surface", drawn("carriageway-circular", ring, z))
        assertTrue("a roundabout lost its kerb",
                   drawn("carriageway-circular-casing", ring, z))
        assertTrue("a roundabout lost the mass under its deck",
                   drawn("carriageway-circular-skirt", ring, z))
        // `junction=circular` is the same object under a different tag and is
        // on 3 ways in the sample; it must not fall through to the ground group.
        assertTrue("a circular junction is not treated as a ring",
                   drawn("carriageway-circular", roadWith(lanes = 2, junction = "circular"), z))
    }

    @Test
    fun `no road falls between the groups and goes undrawn`() {
        // The ring group is an EXCLUSION from the ground and bridge groups, and
        // an exclusion is how a road stops being drawn by anything. The first
        // version of it also filtered out tunnels, so a ring in a tunnel
        // matched the ground group (excluded: it is a ring), the bridge group
        // (excluded: it is not a bridge, and a ring), and its own group
        // (excluded: it is a tunnel) — and simply was not on the map.
        //
        // Every combination, against every deck in the style. A road being
        // absent is the one failure this whole file exists to make loud.
        val z = 17.5
        val decks = listOf("carriageway", "carriageway-circular", "carriageway-bridge")
        for (junction in listOf(null, "roundabout", "circular")) {
            for (bridge in listOf(false, true)) {
                for (tunnel in listOf(false, true)) {
                    if (bridge && tunnel) continue
                    val f = road(lanes = 2, bridge = if (bridge) "yes" else null,
                                 tunnel = if (tunnel) "yes" else null) +
                        buildMap { if (junction != null) put("junction", junction) }
                    val n = decks.count { drawn(it, f, z) }
                    assertEquals(
                        "a road with junction=$junction bridge=$bridge tunnel=$tunnel " +
                            "is drawn by $n decks", 1, n,
                    )
                }
            }
        }
    }

    @Test
    fun `the ring is painted over the fill of the roads that meet it`() {
        // The ring group is still an ORDER rather than a colour, but what it
        // does now is only about the FILL: the circulating carriageway covers
        // the approaches' deck where they meet it, so a roundabout reads as one
        // surface. The kerbs are handled for every junction in the country by
        // `no kerb or casing is drawn after any deck`.
        val order = ids()
        val ringDeck = order.indexOf("carriageway-circular")
        assertTrue("the ring's surface is drawn before the ground it circulates on",
                   ringDeck > order.indexOf("carriageway"))
        assertTrue("the ring's casing is drawn over its own fill",
                   order.indexOf("carriageway-circular-casing") < ringDeck)
        // And BEFORE the bridges: a flyover over a roundabout is still over it.
        assertTrue("a roundabout is painted over the flyover that crosses it",
                   ringDeck < order.indexOf("carriageway-bridge"))
        // The skirt stays under the deck it carries, as everywhere else.
        assertTrue("the ring's skirt is drawn over its own deck",
                   order.indexOf("carriageway-circular-skirt") < ringDeck)
    }

    @Test
    fun `a road whose lane count is a guess is not given lane markings`() {
        // §5.6's rule, applied to paint. The surface has to be drawn at SOME
        // width and a class guess is the least wrong one available — but a
        // divider states "there are exactly this many lanes here", and only
        // the tag supports that. The residential street where Vector is
        // guessing must visibly not claim lanes.
        val z = 17.5
        for (highway in listOf("residential", "primary", "trunk", "service")) {
            val n = dividersFor(road(highway = highway), z).size
            assertEquals("an untagged $highway road claims $n lane boundaries it cannot know",
                         0, n)
        }
        // The surface and its edges are still drawn, because a road does have
        // a width and an edge whatever we guess them to be.
        assertTrue(drawn("carriageway", road(highway = "residential"), z))
        assertTrue(drawn("carriageway-casing", road(highway = "residential"), z))
    }

    @Test
    fun `a road wider than the marked range keeps its surface and loses its lines`() {
        // Above MAX_MARKED_LANES no layer matches the lane count at all, so the
        // markings are absent rather than partial. The surface and the edges
        // are still exactly right, which is the failure worth having: a missing
        // line, not a wrong road.
        val z = 17.5
        val wide = VectorStyle.MAX_MARKED_LANES + 2
        val f = road(lanes = wide)
        assertEquals("a road past the marked range is drawn at the wrong width",
                     VectorStyle.laneWidthPx(z) * wide, width("carriageway", f, z), 0.01)
        assertTrue("a road past the marked range lost its kerb",
                   drawn("carriageway-casing", f, z))
        assertEquals("a road past the marked range drew markings for a different width",
                     0, dividersFor(f, z).size)
    }

    // ------------------------------------------------------------ what is drawn

    @Test
    fun `a road the car cannot drive on gets no carriageway`() {
        val z = 17.5
        assertFalse("a foot-only way was given a driving surface",
                    drawn("carriageway", road(car = false), z))
    }

    @Test
    fun `a tunnel is drawn under the road above it, and without a deck edge`() {
        val z = 17.5
        val t = road(lanes = 2, tunnel = "yes")
        assertTrue("a tunnel is not drawn at all", drawn("carriageway", t, z))
        assertFalse("a tunnel was given a kerb it cannot have",
                    drawn("carriageway-skirt", t, z))
        assertEquals("a tunnel was given lane markings nobody can see",
                     0, dividersFor(road(lanes = 2, tunnel = "yes"), z).size)
        val op = evalExpr(layer("carriageway").getJSONObject("paint").get("line-opacity"), t, z)
        assertTrue("a tunnel is drawn as solidly as the surface above it",
                   (op as Number).toDouble() < 1.0)
        // And it sorts below the surface it runs beneath, within the layer.
        val sort = { f: Map<String, Any?> ->
            (evalExpr(layer("carriageway").getJSONObject("layout").get("line-sort-key"), f, z)
                as Number).toDouble()
        }
        assertTrue("a tunnel does not sort under the road above it",
                   sort(t) < sort(road(lanes = 2)))
    }

    @Test
    fun `a flyover and everything painted on it is drawn over the road it crosses`() {
        // Grade separation without a metre of real Z. At 45 degrees of pitch a
        // flyover drawn at ground height was survivable; at 60 it reads as
        // broken, and the fix is document order rather than geometry.
        //
        // Keyed on `bridge` and NOT on `layer`, which V8 §5.5 assumed. `layer`
        // is absent from the ingestion whitelist (`osm_to_geojson.py:99-101`)
        // and from every production tile around Doha that was checked; `bridge`
        // is present on both counts.
        val order = ids()
        val groundTop = listOf("carriageway-skirt", "carriageway",
                               "carriageway-casing")
            .plus((2..VectorStyle.MAX_MARKED_LANES).flatMap { n ->
                (0 until n - 1).map { "lane-divider-$n-$it" }
            })
            .maxOf { order.indexOf(it) }
        for (id in listOf("carriageway-bridge-skirt", "carriageway-bridge-casing",
                          "carriageway-bridge")) {
            assertTrue("$id is drawn under the road it flies over",
                       order.indexOf(id) > groundTop)
        }
        // A bridge must be in exactly one group, or it is drawn twice.
        val b = road(lanes = 3, bridge = "yes")
        assertFalse("a bridge is drawn by the ground group as well",
                    drawn("carriageway", b, 17.5))
        assertTrue("a bridge is not drawn by the bridge group",
                   drawn("carriageway-bridge", b, 17.5))
    }

    @Test
    fun `the surface is under the class ramp, and takes over from it`() {
        // Both are needed and neither twice: the class ramp is what makes the
        // country view legible and is ranked by `highway`, which is right for
        // a map being read as a map and wrong for one being driven along.
        val order = ids()
        assertTrue("the carriageway is drawn over the class ramp it replaces",
                   order.indexOf("carriageway") < order.indexOf("roads-motorway"))
        assertTrue("the route is drawn under the carriageway",
                   order.indexOf("carriageway-bridge") < order.indexOf("route"))

        val fade = layer("roads-motorway").getJSONObject("paint").get("line-opacity")
        val f = road(highway = "motorway", lanes = 4)
        assertEquals("the class ramp is not at full strength at explore zoom",
                     1.0, (evalExpr(fade, f, 15.0) as Number).toDouble(), 1e-9)
        assertEquals("the class ramp is still drawn over the carriageway at driving zoom",
                     0.0, (evalExpr(fade, f, MapCamera.NAV_ZOOM) as Number).toDouble(), 1e-9)
    }

    // ----------------------------------------------------- the MapLibre rule

    @Test
    fun `no expression buries a zoom curve where MapLibre will not parse it`() {
        // "`zoom` expression may only be used as input to a top-level `step` or
        // `interpolate` expression" — MapLibre's own words, and it enforces
        // them by refusing the whole document. A style that does not parse
        // renders a blank map and reports nothing.
        //
        // V8 §5.3 proposes the width expression in exactly the illegal form,
        // `["*", lanes, ["interpolate", ..., ["zoom"], ...]]`. This walks every
        // paint and layout value in both themes rather than only the ones that
        // were changed, because the next person to write a data-driven width
        // will reach for the same shape.
        fun check(where: String, e: Any?, topLevel: Boolean) {
            if (e !is JSONArray || e.length() == 0) return
            val op = e.optString(0, "")
            if (op == "zoom") {
                assertTrue("$where: a zoom curve is nested below the top level, " +
                               "so MapLibre will refuse the whole document",
                           topLevel)
                return
            }
            val curve = op == "interpolate" || op == "step"
            for (i in 1 until e.length()) {
                // Only a curve's INPUT may be a zoom expression, and only when
                // the curve itself is at the top of the property value.
                check(where, e.get(i), topLevel && curve && i == curveInput(op))
            }
        }
        for (theme in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            for (l in layers(style(theme))) {
                for (block in listOf("paint", "layout")) {
                    val o = l.optJSONObject(block) ?: continue
                    for (k in o.keys()) {
                        check("${l.getString("id")}.$block.$k", o.get(k), true)
                    }
                }
                // Filters are deliberately NOT walked. The rule is a property
                // rule, not an expression rule: MapLibre splits a paint value
                // into a zoom-dependent part and a per-feature part so it can
                // bind the second to a vertex attribute, and that split is what
                // needs the curve on the outside. A filter has no such split —
                // it is evaluated per feature at a known tile zoom — and
                // `poi-labels` has shipped a zoom `step` buried inside an
                // `all` since V3 and renders correctly.
            }
        }
    }

    /** Which argument of a curve is its input: `interpolate` has an interpolation first. */
    private fun curveInput(op: String) = if (op == "interpolate") 2 else 1

    @Test
    fun `no dashed line also asks for a data-driven offset`() {
        // MapLibre Native 11.13.5 renders NOTHING for a line layer carrying
        // both `line-dasharray` and a `line-offset` that depends on the
        // feature. Not a warning, not a wrong offset — the layer is simply
        // absent, and on a map of roads that reads as "this road has no lanes
        // tagged" rather than as a bug. It cost a bisection on the emulator to
        // find: dividers missing with the dash, present without it, every other
        // property held constant. The edges are the control — data-driven
        // offset, no dash, drawn correctly from the first build.
        //
        // This is why the divider layers are generated per lane count with
        // their offset baked in (`VectorStyle.dividerOffset`). It is a fact
        // about the renderer rather than about this style, so it is asserted
        // over every layer in both themes rather than over the ones that
        // happen to be dashed today.
        fun usesFeature(e: Any?): Boolean = e is JSONArray && run {
            val op = e.optString(0, "")
            op == "get" || op == "has" || op == "properties" || op == "feature-state" ||
                (1 until e.length()).any { usesFeature(e.get(it)) }
        }
        for (theme in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            for (l in layers(style(theme))) {
                val paint = l.optJSONObject("paint") ?: continue
                if (!paint.has("line-dasharray")) continue
                val off = paint.opt("line-offset") ?: continue
                assertFalse(
                    "${l.getString("id")} is dashed AND offset by a feature property — " +
                        "MapLibre will draw nothing and say nothing",
                    usesFeature(off),
                )
            }
        }
    }

    // ---------------------------------------------------------- the two themes

    @Test
    fun `the contrast budget is spent inside the road, in both themes`() {
        // §4.1's rule as an ordering that must hold in both themes:
        //   ground < carriageway < markings
        // measured as distance from the ground the road is drawn on, which is
        // the DRIVING ground and not the overview one.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val ground = lstar(p.backgroundDriving)
            // Measured between NEIGHBOURS in the ordering, not all against the
            // ground — which is the mistake the first version of this test made
            // and which only the light theme catches. There the carriageway is
            // the darkest object and the markings are white, so markings-
            // against-GROUND is a small number (ΔL* 7) while markings-against-
            // the-SURFACE-THEY-ARE-ON is a large one (ΔL* 74). The second is
            // the quantity §4.1 is about; the first is an artefact of the
            // daylight model and says nothing.
            val deck = Math.abs(lstar(p.carriageway) - ground)
            val paint = Math.abs(lstar(p.laneMarking) - lstar(p.carriageway))
            assertTrue("$t: the carriageway does not separate from the ground (ΔL* $deck)",
                       deck >= 6.0)
            assertTrue("$t: the hierarchy WITHIN the road does not dominate the " +
                           "hierarchy BETWEEN road and ground (ΔL* $paint vs $deck)",
                       paint > deck)
            // The skirt is the visible face under the deck, and a face catches
            // less light than a deck in both themes. It also has to be
            // separable from BOTH its neighbours, which is the assertion the
            // first night palette failed: the skirt was set below the ground
            // (L* 4 against 6.3), so on a near-black ground there was no lip at
            // all and the deck had no edge to sit on.
            val skirt = lstar(p.carriagewaySkirt)
            assertTrue("$t: the skirt is not darker than the deck it carries",
                       skirt < lstar(p.carriageway))
            assertTrue("$t: the lip is invisible against the ground (ΔL* " +
                           "${Math.abs(skirt - ground)})",
                       Math.abs(skirt - ground) >= 4.0)
            assertTrue("$t: the lip is invisible against the deck (ΔL* " +
                           "${Math.abs(lstar(p.carriageway) - skirt)})",
                       Math.abs(lstar(p.carriageway) - skirt) >= 4.0)
        }
    }

    @Test
    fun `both cartographies put the deck above the ground it sits on`() {
        // **This assertion was the other way round, and it was wrong.**
        //
        // Phase 6 asserted here that "in daylight the carriageway is the
        // DARKEST object on screen", and called it "not an inversion — the
        // reason there are two palettes rather than one and a negation". The
        // intent was right and the sign was not. VectorStyle's own file KDoc
        // had already written the rule down, before Phase 6 existed:
        //
        //   "Dark cartography draws roads LIGHTER than the ground they sit on;
        //    light cartography draws them WHITE with a darker casing... Inverting
        //    the dark theme would give dark roads on a light ground with light
        //    casings, which READS AS A NEGATIVE."
        //
        // Phase 6's surface layers were built and tuned on device in the dark
        // theme, and their daylight values were filled in afterwards by asking
        // what a lit asphalt deck looks like rather than what a daylight MAP
        // looks like. The answer to the first question is "dark", and painting
        // it onto a pale ground produces exactly the negative the KDoc warned
        // about: charcoal ribbons with white paint, on pale grey. Confirmed on
        // the emulator in the light theme, and reported as a defect.
        //
        // The suite did not catch it because every legibility assertion in
        // `VectorStyleTest` is about the CLASS RAMP, and the class ramp stops
        // being the map at `CLASS_RAMP_FADE` (z16.5) — which is where a driver
        // spends the entire drive. Nothing asserted anything about the surface
        // that replaces it. Hence this test, stated as the rule rather than as
        // one theme's sign: **the deck is lighter than its own ground in both
        // cartographies**, and what changes between them is the ground.
        val d = VectorStyle.palette(VectorStyle.MapTheme.DARK)
        val l = VectorStyle.palette(VectorStyle.MapTheme.LIGHT)
        assertTrue("at night the carriageway is not lighter than the ground",
                   lstar(d.carriageway) > lstar(d.backgroundDriving))
        assertTrue("in daylight the carriageway is not lighter than the ground",
                   lstar(l.carriageway) > lstar(l.backgroundDriving))
        // And in daylight the contrast moves to the casing, which is the half
        // of the rule that makes white roads legible rather than invisible.
        assertTrue("in daylight the kerb does not separate the deck from the ground",
                   lstar(l.carriagewaySkirt) < lstar(l.backgroundDriving) - 4.0)
        // Buildings and water sit BELOW the deck in daylight now, so a white
        // road crossing either still reads as a road rather than dissolving.
        assertTrue("in daylight the carriageway is not lighter than the buildings",
                   lstar(l.carriageway) > lstar(l.building))
        assertTrue("in daylight the carriageway is not lighter than the water",
                   lstar(l.carriageway) > lstar(l.water))
    }

    @Test
    fun `the ground moves with the zoom, and only the driving end moved`() {
        // V4 lifted the night ground off near-black because at country zoom the
        // map was hairlines in a void, and that finding is not being thrown
        // away — it is being scoped to the zoom it was measured at. Below the
        // carriageway there is no surface to look at and the ground has to BE
        // the surface; above it, the contrast belongs to the road.
        val d = VectorStyle.palette(VectorStyle.MapTheme.DARK)
        assertTrue("the night ground under the car is not darker than the overview ground",
                   lstar(d.backgroundDriving) < lstar(d.background))
        assertEquals("V4's overview ground moved — every road-ramp assertion " +
                         "in VectorStyleTest is measured against it",
                     "#1a1f2b", d.background)
        // The daylight end moved with the deck, and for the same reason. It
        // used to step UP at driving zoom, because a dark deck needed a pale
        // ground to sit on; a white deck needs the opposite. So both themes now
        // step the driving ground DOWN — different absolute values, one rule.
        val l = VectorStyle.palette(VectorStyle.MapTheme.LIGHT)
        assertTrue("the daylight ground under the car is not darker than the overview ground",
                   lstar(l.backgroundDriving) < lstar(l.background))
    }

}
