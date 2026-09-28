package dev.vector.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The style document must actually parse, and must ask for what exists.
 *
 * Both failures this pins were found on an S24 Ultra and neither produced a
 * single error line anywhere — a broken style renders a blank screen and says
 * nothing:
 *
 * 1. **A `//` comment inside the JSON.** The style is a JSON document written
 *    inside a Kotlin raw string, and JSON has no comments. Annotating a layer
 *    the way you would annotate code makes MapLibre reject the whole document.
 * 2. **A zoom range the tile set does not have.** MapLibre only overzooms ABOVE
 *    the declared maximum, so declaring `maxzoom: 14` against a z11-13 bake
 *    made it request z14 tiles that 404 and draw nothing past z13 — while
 *    navigation sets the camera to zoom 16.5.
 */
class VectorStyleTest {

    private fun style(minZoom: Int = 11, maxZoom: Int = 13): JSONObject =
        JSONObject(VectorStyle.json("http://host:9003", 42L, minZoom, maxZoom))

    private fun layers(s: JSONObject): List<JSONObject> {
        val a: JSONArray = s.getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test
    fun `the style is valid JSON`() {
        // The whole point: a strict parser, on the real string, with nothing
        // stripped out first.
        assertEquals(8, style().getInt("version"))
    }

    @Test
    fun `the source declares the zoom range it was given`() {
        val src = style(minZoom = 11, maxZoom = 13)
            .getJSONObject("sources").getJSONObject("vector")
        assertEquals(11, src.getInt("minzoom"))
        assertEquals(13, src.getInt("maxzoom"))
    }

    @Test
    fun `the zoom range is not hardcoded`() {
        val src = style(minZoom = 9, maxZoom = 16)
            .getJSONObject("sources").getJSONObject("vector")
        assertEquals(9, src.getInt("minzoom"))
        assertEquals(16, src.getInt("maxzoom"))
    }

    @Test
    fun `every layer has an id and a type`() {
        for (l in layers(style())) {
            assertTrue("layer without an id: $l", l.has("id"))
            assertTrue("layer ${l.getString("id")} has no type", l.has("type"))
        }
    }

    @Test
    fun `layer ids are unique`() {
        val ids = layers(style()).map { it.getString("id") }
        assertEquals("duplicate layer ids: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `points of interest are drawn`() {
        // 8,735 POIs are in the extract and nothing drew them.
        val ids = layers(style()).map { it.getString("id") }
        assertTrue("no POI layer: $ids", ids.any { it.startsWith("poi") })
    }

    @Test
    fun `every basemap layer asks for a font the glyph store actually serves`() {
        // The tile server answers a missing font stack with a 2-byte body and
        // HTTP 200, so a wrong name renders blank labels and reports nothing.
        //
        // A `text-font` entry may be a comma-joined STACK since V4, resolved
        // per codepoint by the server. Each component still has to be a font
        // that exists.
        val served = setOf("Open Sans Regular", "Noto Kufi Arabic")
        for (l in layers(style())) {
            val fonts = l.optJSONObject("layout")?.optJSONArray("text-font") ?: continue
            for (i in 0 until fonts.length()) {
                for (font in fonts.getString(i).split(",").map { it.trim() }) {
                    assertTrue(
                        "layer ${l.getString("id")} wants font $font, " +
                            "which the glyph store does not serve",
                        font in served,
                    )
                }
            }
        }
    }

    @Test
    fun `every label layer can render both scripts`() {
        // This is the invariant the test above did NOT check, and it is why a
        // green suite coexisted with a map that drew no road names.
        //
        // Every font in the glyph directory is single-script: `Open Sans
        // Regular` ships Latin (0-255, 32-126) and `Noto Kufi Arabic` ships
        // Arabic (1536-1791 and friends). Pinning ONE font per layer therefore
        // makes half the possible labels unrenderable in that layer — road
        // labels could only ever draw Arabic, place and POI labels only ever
        // Latin — and MapLibre draws nothing rather than complaining.
        //
        // Qatar's roads carry BOTH an Arabic `name` and an English `name:en`
        // (99.5% coverage), so no layer may be limited to one script.
        val latin = "Open Sans Regular"
        val arabic = "Noto Kufi Arabic"
        var checked = 0
        for (l in layers(style())) {
            val fonts = l.optJSONObject("layout")?.optJSONArray("text-font") ?: continue
            val stack = (0 until fonts.length())
                .flatMap { fonts.getString(it).split(",") }
                .map { it.trim() }
            val id = l.getString("id")
            assertTrue("$id cannot render Latin: $stack", latin in stack)
            assertTrue("$id cannot render Arabic: $stack", arabic in stack)
            checked++
        }
        assertTrue("no label layers found — the test is checking nothing", checked >= 3)
    }

    @Test
    fun `route labels are split per role, because the property cannot be data-driven`() {
        // `text-allow-overlap` and `text-ignore-placement` are layout
        // properties the style spec does not permit to be data-driven. Using
        // `["get","chosen"]` for them does not fall back to a default —
        // MapLibre fails to parse the layer and draws NO labels, silently. So
        // the roles have to be two layers split by a filter, and this pins
        // that rather than the implementation drifting back to one.
        val ids = layers(style()).map { it.getString("id") }
        val labelLayers = ids.filter { it.startsWith("route-labels") }
        assertEquals(
            "route labels must be one layer per role: $labelLayers",
            VectorStyle.ROUTE_LABEL_LAYERS, labelLayers.size,
        )
        for (l in layers(style()).filter { it.getString("id").startsWith("route-labels") }) {
            val layout = l.getJSONObject("layout")
            // Literals, not expressions. A JSONArray here is the bug.
            assertTrue(
                "${l.getString("id")}: text-allow-overlap must be a literal",
                layout.get("text-allow-overlap") is Boolean,
            )
            assertTrue(
                "${l.getString("id")}: text-ignore-placement must be a literal",
                layout.get("text-ignore-placement") is Boolean,
            )
            // Every route label must draw. One that the collision detector can
            // evict is a route the card describes and the map does not.
            assertEquals(true, layout.get("text-allow-overlap"))
            assertTrue("${l.getString("id")} has no filter on `chosen`", l.has("filter"))
        }
    }

    @Test
    fun `the chosen route label is drawn above the alternatives`() {
        val ids = layers(style()).map { it.getString("id") }
        assertTrue(
            "the chosen route's label must paint over an alternative's: $ids",
            ids.indexOf("route-labels") > ids.indexOf("route-labels-alt"),
        )
    }

    @Test
    fun `road labels prefer the English name when the driver asks for English`() {
        // The tiles have carried `name:en` all along — a z13 tile over Doha
        // lists `name`, `name:en` and `ref` among its property keys — and the
        // style read `["get","name"]` only. So V3's router returned "via Al
        // Urouba Street" while the map underneath it said the Arabic name,
        // four millimetres apart on the same screen.
        val en = JSONObject(VectorStyle.json("http://h", 1L, 6, 14, lang = "en"))
        val labels = layers(en).first { it.getString("id") == "road-labels" }
        val field = labels.getJSONObject("layout").get("text-field").toString()
        assertTrue("English labels must prefer name:en, got $field", "name:en" in field)
        // And still fall back, because coverage is 99.5% rather than 100%.
        assertTrue("no fallback to the local name in $field", "coalesce" in field)
    }

    @Test
    fun `an Arabic device keeps the local road names`() {
        // The signs beside the road are in Arabic. A driver whose phone is in
        // Arabic should read the same words on the map.
        val ar = JSONObject(VectorStyle.json("http://h", 1L, 6, 14, lang = "ar"))
        val labels = layers(ar).first { it.getString("id") == "road-labels" }
        val field = labels.getJSONObject("layout").get("text-field").toString()
        assertTrue("Arabic labels must use the local name, got $field", "name:en" !in field)
    }

    @Test
    fun `the api base reaches the tiles and the glyphs`() {
        val s = style()
        assertTrue(s.getString("glyphs").startsWith("http://host:9003/glyphs/"))
        val tiles = s.getJSONObject("sources").getJSONObject("vector").getJSONArray("tiles")
        assertTrue(tiles.getString(0).startsWith("http://host:9003/tiles/"))
    }

    // ---- themes ------------------------------------------------------------

    @Test
    fun `both themes are valid JSON`() {
        // The light theme is a second full document, so it can be broken
        // independently of the dark one — and a broken style renders a blank
        // screen with no error anywhere in the app.
        for (t in VectorStyle.MapTheme.entries) {
            val doc = JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 14, t))
            assertEquals("$t is not a valid style", 8, doc.getInt("version"))
        }
    }

    @Test
    fun `the themes have the same layers in the same order`() {
        // A layer present in one theme and not the other is a feature that
        // silently disappears at dusk.
        val dark = layers(JSONObject(VectorStyle.json("http://h", 1L, 6, 14, VectorStyle.MapTheme.DARK)))
        val light = layers(JSONObject(VectorStyle.json("http://h", 1L, 6, 14, VectorStyle.MapTheme.LIGHT)))
        assertEquals(dark.map { it.getString("id") }, light.map { it.getString("id") })
    }

    @Test
    fun `the two themes actually look different`() {
        val dark = JSONObject(VectorStyle.json("http://h", 1L, 6, 14, VectorStyle.MapTheme.DARK))
        val light = JSONObject(VectorStyle.json("http://h", 1L, 6, 14, VectorStyle.MapTheme.LIGHT))
        // The ground is a zoom RAMP since V8 — night gets darker under the car
        // so the carriageway can read as a lit surface, daylight gets paler so
        // the carriageway can be the darkest thing on screen — so this compares
        // the whole expression rather than one colour. Both ends must differ.
        fun bg(s: JSONObject) = layers(s).first { it.getString("id") == "bg" }
            .getJSONObject("paint").get("background-color").toString()
        assertNotEquals(bg(dark), bg(light))
    }

    @Test
    fun `the route, the vehicle and the traffic ramp do not change with the theme`() {
        // These encode MEANING, not appearance. A driver who has learned that
        // blue is the route and red is a jam must not have to relearn it at
        // dusk.
        // `paint` OR `layout`: the vehicle became a `symbol` layer in V4 (it
        // was two circles and had no heading, which is a position marker and
        // not a vehicle), so its appearance now lives in `layout.icon-image`
        // plus the registered bitmap. Comparing the whole layer object covers
        // both without the test having to know which kind each one is.
        fun layerOf(theme: VectorStyle.MapTheme, id: String): String =
            layers(JSONObject(VectorStyle.json("http://h", 1L, 6, 14, theme)))
                .first { it.getString("id") == id }
                .toString()
        for (id in listOf("route", "route-casing", "puck", "puck-halo", "traffic",
                          "route-endpoints")) {
            assertEquals(
                "$id changes with the theme, but it carries meaning rather than style",
                layerOf(VectorStyle.MapTheme.DARK, id),
                layerOf(VectorStyle.MapTheme.LIGHT, id),
            )
        }
    }

    @Test
    fun `an unresolved system preference still renders a map`() {
        // palette() must be total. A mistake here should give the driver the
        // night theme, not an exception mid-drive.
        assertEquals(VectorStyle.DARK, VectorStyle.palette(VectorStyle.MapTheme.SYSTEM))
    }

    // ---- the zoom hierarchy ------------------------------------------------

    @Test
    fun `the country view has roads to draw`() {
        // The reported P0: z4-z10 returned 404 for every tile, because nothing
        // below z11 had ever been baked, and MapLibre does not under-zoom — it
        // requests nothing and draws the background. The bake now starts at z6,
        // and these layers are what has to render there.
        //
        // `motorway` and `trunk` are baked from z0 (build_qatar_tiles._HW_TIERS),
        // so the layers drawing them must not exclude the country view with a
        // minzoom of their own.
        val ids = listOf("roads-motorway", "roads-motorway-casing",
                         "roads-primary", "roads-primary-casing")
        for (id in ids) {
            val l = layers(style(6, 14)).first { it.getString("id") == id }
            val minzoom = l.optInt("minzoom", 0)
            assertTrue("$id is hidden at country zoom (minzoom $minzoom)", minzoom <= 6)
        }
    }

    @Test
    fun `road widths are specified at the country zoom, not extrapolated to it`() {
        // MapLibre CLAMPS an interpolate below its first stop, so stops that
        // began at z8 drew the z8 width at z6 — hairline roads at country
        // scale, invisible until the low zooms were baked.
        for (id in listOf("roads-motorway", "roads-primary")) {
            val w = layers(style(6, 14)).first { it.getString("id") == id }
                .getJSONObject("paint").getJSONArray("line-width")
            // ["interpolate", ["exponential", n], ["zoom"], z0, w0, ...]
            val firstStop = w.getDouble(3)
            assertTrue("$id interpolates from z$firstStop, above the country view",
                       firstStop <= 6.0)
        }
    }

    @Test
    fun `place labels are drawn at the country zoom`() {
        val l = layers(style(6, 14)).first { it.getString("id") == "place-labels" }
        assertTrue("labels start at z${l.optInt("minzoom", 0)}, above the country view",
                   l.optInt("minzoom", 0) <= 6)
    }

    @Test
    fun `place labels are ranked rather than uniform`() {
        // The extract carries `place=` on all 685 labels — 1 country, 3 city,
        // 20 town, 320 locality — and the style ignored it, so a hamlet was
        // drawn at the same size as Doha and MapLibre's symbol collision chose
        // between them by feature order.
        val layout = layers(style(6, 14))
            .first { it.getString("id") == "place-labels" }
            .getJSONObject("layout")
        assertTrue("label size does not consider `place`",
                   layout.getJSONArray("text-size").toString().contains("place"))
        assertTrue("label priority does not consider `place`",
                   layout.has("symbol-sort-key"))
    }

    @Test
    fun `every road layer declares the zoom its class is baked at`() {
        // Not an optimisation: the document should state the contract it
        // depends on, because a layer asking for a class the bake does not
        // carry renders nothing and reports nothing.
        val bakedFrom = mapOf(
            "roads-service" to 15,
            "roads-minor" to 14,
            "roads-tertiary" to 13,
            "roads-secondary" to 12,
            "roads-links" to 11,
        )
        for ((id, minz) in bakedFrom) {
            val l = layers(style(6, 14)).first { it.getString("id") == id }
            assertEquals("$id disagrees with the bake tier", minz, l.optInt("minzoom", -1))
        }
    }

    // ---- the land/sea edge -------------------------------------------------

    @Test
    fun `the coastline is drawn`() {
        // Qatar is a peninsula, and at country zoom the motorway network
        // rendered correctly into a black void — the sea and the land are both
        // the background colour. Verified on an S24 once the low zooms existed.
        val ids = layers(style(6, 14)).map { it.getString("id") }
        assertTrue("no coastline layer: $ids", ids.contains("coastline"))
    }

    @Test
    fun `the coastline is a line, not a fill`() {
        // `natural=coastline` is an OPEN way. Drawing it as a fill means
        // closing a ring across its two loose ends — a large spurious polygon
        // rather than a shoreline.
        val l = layers(style(6, 14)).first { it.getString("id") == "coastline" }
        assertEquals("line", l.getString("type"))
    }

    @Test
    fun `the coastline is visible at country zoom`() {
        val l = layers(style(6, 14)).first { it.getString("id") == "coastline" }
        assertTrue(l.optInt("minzoom", 0) <= 6)
        val w = l.getJSONObject("paint").getJSONArray("line-width")
        assertTrue("the coastline width is not specified at country zoom",
                   w.getDouble(3) <= 6.0)
    }

    @Test
    fun `the coastline is drawn under the roads`() {
        // It is ground, not a route. Painted over the road network it would cut
        // across the thing the driver is following.
        val ids = layers(style(6, 14)).map { it.getString("id") }
        assertTrue(ids.indexOf("coastline") < ids.indexOf("roads-motorway"))
    }

    // ---- alternative routes ------------------------------------------------

    @Test
    fun `unchosen routes are drawn`() {
        // The alternatives used to be described by chips and never drawn, so
        // the driver was asked to pick between routes they could not see.
        val ids = layers(style(6, 14)).map { it.getString("id") }
        assertTrue("no layer for unchosen routes: $ids", ids.contains("route-alt"))
        assertTrue(style(6, 14).getJSONObject("sources").has("route-alt"))
    }

    @Test
    fun `an unchosen route is drawn under the chosen one`() {
        val ids = layers(style(6, 14)).map { it.getString("id") }
        assertTrue(ids.indexOf("route-alt") < ids.indexOf("route"))
    }

    @Test
    fun `an unchosen route is drawn over the roads, so it reads as a route`() {
        val ids = layers(style(6, 14)).map { it.getString("id") }
        assertTrue(ids.indexOf("route-alt") > ids.indexOf("roads-motorway"))
    }

    @Test
    fun `roads are legible against the ground they are drawn on`() {
        // The assertion whose absence let the same defect ship twice, in
        // opposite directions.
        //
        // V4 lifted the DARK ground off #0c0e14 because roads were grey
        // hairlines in a void. The LIGHT theme then turned out to have the
        // mirror image: ground #f2f4f7 at L* 96.1 with every road tier at
        // #ffffff, which is ΔL* 3.9 — the residential grid was a suggestion.
        // Both were visible on a real S24 and invisible to the suite, because
        // nothing compared the two numbers.
        //
        // 6 L* is deliberately a floor rather than a target. It is roughly
        // where a boundary stops being findable on a sunlit phone; the dark
        // theme clears it by 2x on its very lowest tier and by 8x at the top.
        val minDelta = 6.0
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val ground = lstar(p.background)
            for ((name, road) in listOf(
                "roadService" to p.roadService,
                "roadMinor" to p.roadMinor,
                "roadTertiary" to p.roadTertiary,
                "roadSecondary" to p.roadSecondary,
                "roadPrimary" to p.roadPrimary,
            )) {
                val d = Math.abs(lstar(road) - ground)
                assertTrue(
                    "$t: $name ($road, L* ${"%.1f".format(lstar(road))}) is only " +
                        "ΔL* ${"%.1f".format(d)} from the ground ($road vs ${p.background}) " +
                        "— it will not read on a sunlit phone",
                    d >= minDelta,
                )
            }
        }
    }

    @Test
    fun `the road ramp has a hierarchy in both themes`() {
        // The tier has to be in the FILL and not only in the line width.
        // The light theme used five identical whites, so the six-tier ramp
        // that makes the country view legible in dark had no light-theme
        // counterpart — and at z6 a country is drawn almost entirely by fill.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val ramp = listOf(p.roadService, p.roadMinor, p.roadTertiary,
                              p.roadSecondary, p.roadPrimary)
            val ground = lstar(p.background)
            // Each step must move AWAY from the ground, monotonically.
            val distances = ramp.map { Math.abs(lstar(it) - ground) }
            for (i in 1 until distances.size) {
                assertTrue(
                    "$t: tier $i (${ramp[i]}) is not further from the ground than " +
                        "tier ${i - 1} (${ramp[i - 1]}): $distances",
                    distances[i] >= distances[i - 1],
                )
            }
            assertTrue(
                "$t: the ramp is flat — first and last are the same distance from " +
                    "the ground: $distances",
                distances.last() > distances.first() + 1.0,
            )
        }
    }

    @Test
    fun `the driving surface is lighter than the ground in both cartographies`() {
        // The headline assertion of this file, and the one whose absence let
        // the light theme ship as a photographic negative.
        //
        // The two themes disagree about nearly everything, so it is worth
        // being precise about the one thing they agree on: a road is LIGHTER
        // than the ground beside it in both. At night because dark cartography
        // draws roads lighter than what they sit on; in daylight because light
        // cartography draws them white. What differs between the themes is
        // where the contrast is SPENT — on the surface at night, on the casing
        // in daylight — not which way round the surface and the ground go.
        //
        // The light theme had `carriageway` at L* 26.8 on a ground of 92.9,
        // i.e. the deck 66 units DARKER than the ground it sat on, which is
        // the night relationship painted onto a pale map. Every test in this
        // class passed, because every one of them was about the class ramp and
        // the class ramp was still white. The surface that REPLACES the ramp
        // at driving zoom was asserted about nowhere.
        //
        // Measured against `backgroundDriving`, not `background`: the deck
        // only exists from z14 and the ground is a zoom ramp, so the ground it
        // is actually seen against is the driving one.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val ground = lstar(p.backgroundDriving)
            val deck = lstar(p.carriageway)
            assertTrue(
                "$t: the carriageway ${p.carriageway} (L* ${"%.1f".format(deck)}) is DARKER " +
                    "than the ground under it ${p.backgroundDriving} " +
                    "(L* ${"%.1f".format(ground)}) — that is a negative of a map, " +
                    "not a map. See the file KDoc, paragraph three.",
                deck > ground,
            )
            // And by enough to be a surface rather than a tint of the ground.
            // 10 is the floor: Google Maps' light theme gives its white roads
            // ΔL* 7.4 over its own ground and gets away with it because it has
            // a casing, which is also true here — but 7 is thin enough that a
            // sunlit phone can lose it, and both themes clear 10 comfortably.
            assertTrue(
                "$t: the carriageway is only ΔL* ${"%.1f".format(deck - ground)} above the " +
                    "ground it is drawn on — it will not read as a surface",
                deck - ground >= 10.0,
            )
        }
    }

    @Test
    fun `lane markings read against the deck they are painted on`() {
        // The sign of this contrast belongs to the THEME, not to the role, and
        // writing it into the role is what produced #ffffff markings on a
        // charcoal daylight deck: the palette described the marking as "the
        // brightest thing on the map in both themes", which is a statement
        // about the dark theme generalised by assumption.
        //
        // Daylight draws the deck white, so a white marking on it is not a
        // faint line, it is no line at all. The marking has to come down off
        // the surface. What is held constant across the themes is only that
        // the driver can see it.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val deck = lstar(p.carriageway)
            val mark = lstar(p.laneMarking)
            // 30 rather than the 6 used for road-against-ground. A marking is
            // 0.4 px wide at z15 and antialiasing eats a thin line's contrast
            // before it reaches the eye, so the ink has to start much further
            // from the surface than a wide fill does.
            assertTrue(
                "$t: laneMarking ${p.laneMarking} (L* ${"%.1f".format(mark)}) is only " +
                    "ΔL* ${"%.1f".format(Math.abs(mark - deck))} from the carriageway " +
                    "${p.carriageway} it is painted on",
                Math.abs(mark - deck) >= 30.0,
            )
            // And the sign, per theme, which is the half that was wrong.
            if (t == VectorStyle.MapTheme.DARK) {
                assertTrue(
                    "DARK: the paint must be lighter than the asphalt", mark > deck,
                )
            } else {
                assertTrue(
                    "LIGHT: the deck is white, so a marking lighter than it is invisible — " +
                        "${p.laneMarking} (L* ${"%.1f".format(mark)}) vs deck " +
                        "${p.carriageway} (L* ${"%.1f".format(deck)})",
                    mark < deck,
                )
            }
        }
    }

    @Test
    fun `the kerb gives the deck an edge rather than a hole`() {
        // The skirt is the one role whose KDoc got the light theme right and
        // whose VALUE did not: it says the light theme keeps the skirt below
        // both the ground and the deck, "because there a dark rim on a pale
        // ground is a shadow and reads immediately", and the value shipped was
        // #2a2f36 at L* 19.2 — fifty units below the ground, which is not a
        // shadow, it is a trench.
        //
        // Each theme gets the relationship its own KDoc claims, so the prose
        // and the numbers cannot drift apart again.
        val dark = VectorStyle.palette(VectorStyle.MapTheme.DARK)
        val darkSkirt = lstar(dark.carriagewaySkirt)
        assertTrue(
            "DARK: the skirt ${dark.carriagewaySkirt} must sit BETWEEN the ground " +
                "${dark.backgroundDriving} and the deck ${dark.carriageway} — under a " +
                "near-black ground it is invisible and the deck has no edge",
            darkSkirt > lstar(dark.backgroundDriving) && darkSkirt < lstar(dark.carriageway),
        )

        val light = VectorStyle.palette(VectorStyle.MapTheme.LIGHT)
        val lightSkirt = lstar(light.carriagewaySkirt)
        val lightGround = lstar(light.backgroundDriving)
        assertTrue(
            "LIGHT: the skirt ${light.carriagewaySkirt} must sit BELOW both the ground " +
                "${light.backgroundDriving} and the deck ${light.carriageway}, because in " +
                "daylight the kerb is a shadow",
            lightSkirt < lightGround && lightSkirt < lstar(light.carriageway),
        )
        // A shadow and not a trench. Ten units clear of the ground so it reads,
        // and not more than thirty below it or the map goes back to being
        // outlined in near-black, which is the weight the owner complained of.
        val drop = lightGround - lightSkirt
        assertTrue(
            "LIGHT: the kerb is ΔL* ${"%.1f".format(drop)} below the ground — a shadow is " +
                "10 to 30, less than that is not visible and more than that is the " +
                "negative coming back in through the casing",
            drop in 10.0..30.0,
        )
    }

    @Test
    fun `the route ribbon is not the carriageway it is drawn on`() {
        // The ribbon sits directly on the deck, so "the route is the brightest
        // thing on the map" is a claim about the ROUTE AGAINST THE DECK and
        // not against the ground. Making the daylight deck white moves that
        // relationship, so it has to be asserted rather than assumed.
        //
        // The route colour is read out of the built style rather than from a
        // constant, because it is private to the style object — and reading it
        // from the document is the stronger test anyway: it checks the colour
        // the renderer will actually receive.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val s = JSONObject(VectorStyle.json("http://h", 1L, 6, 14, t))
            val route = layers(s).first { it.getString("id") == "route" }
                .getJSONObject("paint").getString("line-color")
            val deck = VectorStyle.palette(t).carriageway
            assertTrue(
                "$t: the route $route (L* ${"%.1f".format(lstar(route))}) is only ΔL* " +
                    "${"%.1f".format(Math.abs(lstar(route) - lstar(deck)))} from the " +
                    "carriageway $deck it is drawn on",
                Math.abs(lstar(route) - lstar(deck)) >= 20.0,
            )
            // And it is a colour, where the deck is neutral — which is what
            // says "route" at a glance before any luminance is judged.
            assertTrue(
                "$t: the route $route is as neutral as the carriageway $deck",
                spread(route) > spread(deck) + 0.3,
            )
        }
    }

    @Test
    fun `an unchosen route cannot be mistaken for an ordinary road`() {
        // The first attempt used a grey (#5c6b80) that sits between
        // roadSecondary (#4a5462) and roadPrimary (#6b7688) on the dark theme,
        // so an unchosen route was drawn and read as a road — very nearly as
        // useless as not drawing it. Hue is what says "route"; the muting says
        // "not chosen".
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val alt = hue(p.routeAlt)
            for (road in listOf(p.roadPrimary, p.roadSecondary, p.roadMinor, p.roadTertiary)) {
                // Channel SPREAD, not HSL saturation.
                //
                // This test used `saturation()` and its premise — "roads are
                // neutral greys, so any road's saturation is near zero" — was
                // true only while every light-theme road was pure #ffffff.
                // V4 gave the light ramp a fill hierarchy, and HSL saturation
                // reports #f7f9fc (an off-white five values wide) as **0.45**,
                // because at very high lightness a tiny channel difference
                // divides by a tiny denominator. The test then failed on a
                // colour that is perceptually neutral.
                //
                // Spread is max-minus-min in absolute terms: 0.02 for that
                // off-white, 0.41 for the light theme's routeAlt. It answers
                // "is this colourful" without the near-white artefact, and it
                // is the measure the assertion always meant.
                assertTrue(
                    "$t: routeAlt ${p.routeAlt} (spread ${"%.3f".format(spread(p.routeAlt))}) " +
                        "is as neutral as the road colour $road " +
                        "(spread ${"%.3f".format(spread(road))})",
                    spread(p.routeAlt) > spread(road) + 0.15,
                )
            }
            // And brighter than every grey road, which is the half the old
            // test did not check — and is why `#2f6296` (darker than a
            // primary road) survived a green suite.
            for ((name, road) in listOf(
                "roadPrimary" to p.roadPrimary, "roadSecondary" to p.roadSecondary,
                "roadTertiary" to p.roadTertiary, "roadMinor" to p.roadMinor,
            )) {
                val d = Math.abs(lstar(p.routeAlt) - lstar(road))
                assertTrue(
                    "$t: routeAlt ${p.routeAlt} (L* ${"%.1f".format(lstar(p.routeAlt))}) is " +
                        "only ΔL* ${"%.1f".format(d)} from $name ($road) — it will read as a road",
                    d >= 4.0,
                )
            }
            // And it must be the same FAMILY as the route it is an alternative
            // to, or it reads as an unrelated feature.
            assertTrue("$t: routeAlt is not a blue (hue $alt)", alt in 190.0..250.0)
        }
    }

    /**
     * CIE L* of "#rrggbb", 0..100.
     *
     * Perceptual lightness, not the naive average of the channels: #0000ff and
     * #ffff00 have the same mean and are nothing like each other to look at,
     * and "can a driver see this boundary" is a perceptual question.
     */
    private fun lstar(hex: String): Double {
        val (r, g, b) = rgb(hex)
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val y = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        return if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
    }

    /**
     * How colourful "#rrggbb" is: max channel minus min, 0..1.
     *
     * Used instead of HSL saturation for near-white and near-black colours,
     * where saturation's denominator collapses and reports an off-white as
     * highly saturated. See the routeAlt test for the case that found this.
     */
    private fun spread(hex: String): Double {
        val (r, g, b) = rgb(hex)
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** HSL saturation of "#rrggbb", 0..1. */
    private fun saturation(hex: String): Double {
        val (r, g, b) = rgb(hex)
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == mn) return 0.0
        val l = (mx + mn) / 2.0
        return if (l > 0.5) (mx - mn) / (2.0 - mx - mn) else (mx - mn) / (mx + mn)
    }

    /** HSL hue of "#rrggbb", in degrees. */
    private fun hue(hex: String): Double {
        val (r, g, b) = rgb(hex)
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == mn) return 0.0
        val d = mx - mn
        val h = when (mx) {
            r -> ((g - b) / d + if (g < b) 6 else 0)
            g -> ((b - r) / d + 2)
            else -> ((r - g) / d + 4)
        }
        return h * 60.0
    }

    private fun rgb(hex: String): Triple<Double, Double, Double> {
        val v = hex.removePrefix("#")
        return Triple(
            v.substring(0, 2).toInt(16) / 255.0,
            v.substring(2, 4).toInt(16) / 255.0,
            v.substring(4, 6).toInt(16) / 255.0,
        )
    }

    // ---- label prominence --------------------------------------------------

    @Test
    fun `POI names are quieter than road names, which are quieter than places`() {
        // The style's own KDoc said POIs are "deliberately quieter", and the
        // palette said the opposite: poiLabel #b9c6d6 against roadLabel
        // #9aa6b8, brighter on a dark ground. On a real S24 screenshot at
        // street zoom the shop names were the most prominent text on the map
        // and the street names sat behind them.
        //
        // Prominence is measured as contrast against the theme's own
        // background, which is the only definition that works in both themes:
        // in the dark theme a prominent label is lighter, in the light theme it
        // is darker, and "distance from the ground" is the same statement for
        // both.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val bg = luminance(p.background)
            val poi = kotlin.math.abs(luminance(p.poiLabel) - bg)
            val road = kotlin.math.abs(luminance(p.roadLabel) - bg)
            val place = kotlin.math.abs(luminance(p.placeLabel) - bg)
            assertTrue(
                "$t: POI labels (contrast $poi) are more prominent than road " +
                    "labels (contrast $road)",
                poi < road,
            )
            assertTrue(
                "$t: road labels (contrast $road) are more prominent than place " +
                    "labels (contrast $place)",
                road < place,
            )
        }
    }

    /** Relative luminance of a "#rrggbb" string, 0..1. */
    private fun luminance(hex: String): Double {
        val v = hex.removePrefix("#")
        val r = v.substring(0, 2).toInt(16) / 255.0
        val g = v.substring(2, 4).toInt(16) / 255.0
        val b = v.substring(4, 6).toInt(16) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    // ---- what the driver is shown ------------------------------------------
    //
    // "Too many points of interest are shown while driving making it messy",
    // and "a lot of POIs that don't exist right now in real life" — the S24,
    // 2026-09-13, on Rawdat Al Khail. A production tile over Doha carries 525
    // POIs out of 1,500 features, and until this the only question either POI
    // layer asked was `kind == "poi"`.

    private fun layer(id: String): JSONObject =
        layers(style()).first { it.getString("id") == id }

    @Test
    fun `both poi layers ask the same question`() {
        // A dot with no label is useful; a label with no dot is a floating
        // name. They must not be able to drift apart.
        //
        // Not byte-equal any more: the label layer asks the dot layer's whole
        // question and then one more, the zoom-stepped rank ceiling that caps
        // how many names a screenful carries (`VectorStyleDensityTest`). So the
        // relationship is containment, and this test pins the SHAPE of it — the
        // shared clause has to be the first thing the label filter asks, not a
        // paraphrase of it that can drift.
        val labels = layer("poi-labels").getJSONArray("filter")
        assertEquals("the label filter must be [all, <the dots' filter>, <cap>]",
                     "all", labels.getString(0))
        assertEquals(3, labels.length())
        assertEquals(
            layer("poi-dots").getJSONArray("filter").toString(),
            labels.getJSONArray(1).toString(),
        )
    }

    @Test
    fun `an office registration is not a place you drive to`() {
        val f = layer("poi-labels").getJSONArray("filter").toString()
        for (c in listOf("professional_services", "corporate_office",
                         "freight_and_cargo_service", "software_development")) {
            assertTrue("$c should never win a label on a driving map", f.contains(c))
        }
    }

    @Test
    fun `somebody else's home is not a destination`() {
        val f = layer("poi-labels").getJSONArray("filter").toString()
        // "accommodation" in Qatar is worker housing. The category covers the
        // Overture side; the name covers the OSM side, which carries none.
        assertTrue(f.contains("\"accommodation\""))
        assertTrue("Af Vincent Accommodation has no category at all",
                   f.contains("index-of") && f.contains("accomodation"))
    }

    @Test
    fun `the overture landmark bucket is not landmarks`() {
        // All 583 sampled are residential towers, compounds and place-names:
        // Ezdan Village 5, Viva Bahriya Tower 22, Barwa City Phase 1.
        val f = layer("poi-labels").getJSONArray("filter").toString()
        assertTrue(f.contains("landmark_and_historical_building"))
    }

    @Test
    fun `the osm side of the tile is filtered too`() {
        // A third of the POIs in a shipped tile carry poi_class and no
        // category. Reading only `category` left that third unreachable.
        val f = layer("poi-labels").getJSONArray("filter").toString()
        assertTrue("poi_class must be read", f.contains("poi_class"))
        for (c in listOf("shelter", "bench", "gate", "level_crossing", "apartment")) {
            assertTrue("$c is map furniture, not a destination", f.contains(c))
        }
    }

    @Test
    fun `what a driver actually wants is not filtered out`() {
        // These share the furniture signature (mostly unnamed) and are not
        // furniture: 1,818 parking, 717 mosques, 132 ATMs.
        //
        // Read off `poi-dots`, which carries the deny rules and nothing else.
        // The label filter now also carries the rank ceiling, and all three of
        // these names appear in it as the TOP rank — asking the label filter
        // whether it mentions "parking" stopped being the same question as
        // asking whether parking is denied.
        val f = layer("poi-dots").getJSONArray("filter").toString()
        for (c in listOf("\"parking\"", "\"place_of_worship\"", "\"atm\"")) {
            assertFalse("$c must still reach the map", f.contains(c))
        }
    }

    @Test
    fun `a feature with no confidence is not demoted for lacking one`() {
        // OSM and learned places carry no `confidence`; only Overture ships it.
        // Treating absence as zero would empty the map of everything OSM knows.
        val f = layer("poi-labels").getJSONArray("filter").toString()
        assertTrue("absence of the field must be an explicit pass: $f",
                   f.contains("has") && f.contains("confidence"))
    }

    @Test
    fun `labels are ranked so collision culling keeps the useful ones`() {
        val sort = layer("poi-labels").getJSONObject("layout")
            .getJSONArray("symbol-sort-key").toString()
        assertTrue("fuel outranks the unlisted default", sort.contains("gas_station"))
        assertTrue("so does a pharmacy", sort.contains("pharmacy"))
        // Both vocabularies, or a third of the tile ranks last by default.
        assertTrue("OSM names the same things differently", sort.contains("poi_class"))
        assertTrue(sort.contains("place_of_worship"))
    }

    @Test
    fun `shopfront names stop before the driver is going too fast to read them`() {
        // MapCamera.ZOOM_BANDS zooms out with speed: 16.5 urban arterial, 15.8
        // ring road, 14.6 motorway. A label floor of 16 puts names on screen in
        // the two slowest bands and nowhere else.
        assertEquals(16, layer("poi-labels").getInt("minzoom"))
        assertEquals("the dots still say something is there",
                     14, layer("poi-dots").getInt("minzoom"))
    }

    @Test
    fun `no text is laid flat on the ground, because nobody can read it at 60 degrees`() {
        // V8 §9 lists "label legibility at 60 degrees" as a medium risk and
        // names this as the mitigation, and on the emulator at the new camera
        // it is easy to see why: a label lying flat on the carriageway is
        // foreshortened by the same projection that makes the road look like a
        // road, so the further up the screen it is the more it is compressed
        // into an unreadable band. Standing it up costs nothing — the label
        // still follows the line's direction, it just faces the driver.
        //
        // `text-pitch-alignment` defaults to `auto`, which follows
        // `text-rotation-alignment`, which defaults to `auto`, which is `map`
        // for line placement and `viewport` for point. So a LINE-placed label
        // gets the flat-on-the-ground behaviour by saying nothing at all, which
        // is why this resolves the defaults rather than reading the property:
        // `road-labels` had been flat since it was written and nobody had put a
        // 60-degree camera behind it.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            for (l in layers(JSONObject(VectorStyle.json("http://host:9003", 42L, 6, 15, t)))) {
                if (l.getString("type") != "symbol") continue
                val layout = l.optJSONObject("layout") ?: JSONObject()
                if (!layout.has("text-field")) continue
                val placement = layout.optString("symbol-placement", "point")
                val rot = layout.optString("text-rotation-alignment", "auto").let {
                    if (it != "auto") it
                    else if (placement.startsWith("line")) "map" else "viewport"
                }
                val pitch = layout.optString("text-pitch-alignment", "auto").let {
                    if (it != "auto") it else rot
                }
                assertEquals(
                    "$t: ${l.getString("id")} lays its text flat on the ground " +
                        "(placement=$placement, rotation=$rot) — at 60 degrees of pitch " +
                        "a label on the ground is foreshortened into an unreadable band",
                    "viewport", pitch,
                )
            }
        }
    }

    @Test
    fun `the tile epoch rides in the tile url`() {
        val tiles = style().getJSONObject("sources").getJSONObject("vector").getJSONArray("tiles")
        assertTrue("a re-bake must bust the tile cache", tiles.getString(0).contains("v=42"))
    }

    // ---- V7.6: the city fabric ---------------------------------------------
    //
    // The fabric is the flat `buildings` fill. It has been in this style since
    // V3 and drew nothing until V7.6, because the extract kept 2,752 of
    // Qatar's 189,866 footprints. These pin what it must do now that it has
    // 170,216 of them in the Doha core to draw.

    private fun styleOf(theme: VectorStyle.MapTheme, extruded: Boolean): JSONObject =
        JSONObject(VectorStyle.json("http://host:9003", 42L, 11, 15,
                                    theme = theme, extruded = extruded))

    @Test
    fun `the fabric is present in 2D and in 3D alike`() {
        // A driver who chose the flat perspective did not ask for an empty
        // city. Unlike the extrusion, the fabric is not gated on `extruded`.
        for (theme in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            for (extruded in listOf(false, true)) {
                val f = layer(styleOf(theme, extruded), "buildings")
                assertNotNull("$theme extruded=$extruded must carry the fabric", f)
                assertEquals("fill", f!!.getString("type"))
                assertEquals("basemap", f.getString("source-layer"))
            }
        }
    }

    @Test
    fun `the fabric needs no height and cannot be extruded`() {
        // THE property that lets 96% of a country be drawn honestly: a fill
        // needs no height, so a footprint with none is still a block. The
        // filter must therefore be kind-only — the moment it mentions a
        // height, the fabric is gone again.
        val f = layer(style(extruded = true), "buildings")!!
        val filter = f.getJSONArray("filter").toString()
        assertTrue("the fabric filter must select on kind alone", filter.contains("building"))
        assertFalse("the fabric must not require a height", filter.contains("height"))
        assertFalse("a fill cannot carry an extrusion height",
                    f.getJSONObject("paint").toString().contains("extrusion"))
        // And the extrusion still demands one, so nothing crosses over.
        assertTrue(layer(style(extruded = true), "buildings-3d")!!
                       .getJSONArray("filter").toString().contains("height_m"))
    }

    @Test
    fun `the fabric draws under the extrusion and under every road and route`() {
        // Same acceptance condition as the extrusion, and it matters more for
        // the fabric because the fabric covers the whole city rather than
        // 0.5% of it. If this order inverts, Doha's streets disappear under
        // their own buildings.
        val ids = layers(style(extruded = true)).map { it.getString("id") }
        val fab = ids.indexOf("buildings")
        assertTrue("the fabric must exist", fab >= 0)
        assertTrue("the fabric must draw under the volumes that stand on it",
                   ids.indexOf("buildings-3d") > fab)
        for (id in listOf("roads-minor", "roads-major", "roads-service",
                          "route-line", "route-casing", "route", "puck")) {
            val i = ids.indexOf(id)
            if (i >= 0) {
                assertTrue("$id ($i) must draw AFTER the fabric ($fab)", i > fab)
            }
        }
    }

    @Test
    fun `the fabric starts at the zoom the bake emits it`() {
        // `build_qatar_tiles.visible_at_zoom` admits a building from z14. A
        // fill declaring less would ask for features no tile carries; one
        // declaring more would hide features every tile does.
        assertEquals(14, layer(style(extruded = true), "buildings")!!.getInt("minzoom"))
    }

    @Test
    fun `a building seam is a shadow line and never a highlight`() {
        // The defect V7.6 found by finally drawing the layer: the dark
        // theme's outline was L* 23.4 against a carriageway of 21.1, so every
        // building edge in Doha would have been BRIGHTER than the road
        // surface and a dense district would have read as a luminous mesh
        // with the streets as dark gaps. Buildings meet along a shadow.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            assertTrue("$t: the seam must be darker than the fabric it separates",
                       lstar(p.buildingOutline) < lstar(p.building))
            assertTrue("$t: no building tone may outrank the carriageway",
                       lstar(p.buildingOutline) < lstar(p.carriageway))
        }
        // Only the dark theme has a floor to clear: there the ground is
        // nearly black, so a seam driven too far down stops reading as an
        // edge and becomes a hole in the city. The light theme's ground is
        // ABOVE its fabric, so the same test there would assert the opposite
        // of what a light cartography does, which is how this test failed
        // when it was first written theme-agnostically.
        VectorStyle.palette(VectorStyle.MapTheme.DARK).let { p ->
            assertTrue("DARK: the seam must stay clear of the driving ground",
                       lstar(p.buildingOutline) > lstar(p.backgroundDriving))
        }
    }

    @Test
    fun `the fabric never outranks the road surface in either theme`() {
        // The hierarchy the whole map depends on: the road a driver is on is
        // the brightest surface under them. The fabric is context.
        for (t in listOf(VectorStyle.MapTheme.DARK, VectorStyle.MapTheme.LIGHT)) {
            val p = VectorStyle.palette(t)
            val dark = t == VectorStyle.MapTheme.DARK
            val fabric = lstar(p.building)
            val road = lstar(p.carriageway)
            if (dark) {
                assertTrue("DARK: the carriageway must be lighter than the fabric",
                           road > fabric)
            } else {
                // The light theme's carriageway is white; the fabric steps
                // DOWN from the ground. Same relationship, opposite direction.
                assertTrue("LIGHT: the carriageway must still separate",
                           road > fabric)
            }
        }
    }

    // ---- V7 3D: extruded buildings ----------------------------------------

    private fun style(extruded: Boolean): JSONObject =
        JSONObject(VectorStyle.json("http://host:9003", 42L, 11, 15,
                                    extruded = extruded))

    private fun layer(s: JSONObject, id: String): JSONObject? =
        layers(s).firstOrNull { it.getString("id") == id }

    @Test
    fun `the default style has no extrusion, so nothing changes for a 2D driver`() {
        // The pre-existing behaviour, byte-for-byte: a caller that does not ask
        // for 3D gets a style with no extrusion in it at all. That is what
        // makes this stage additive rather than a rewrite of the map.
        for (s in listOf(style(), style(extruded = false))) {
            assertNull("no extrusion may exist in a 2D style", layer(s, "buildings-3d"))
            assertTrue(layers(s).none { it.optString("type") == "fill-extrusion" })
        }
    }

    @Test
    fun `3D adds exactly one extrusion layer and removes nothing`() {
        val flat = style(extruded = false)
        val three = style(extruded = true)
        val ex = layer(three, "buildings-3d")!!
        assertEquals("fill-extrusion", ex.getString("type"))
        assertEquals("vector", ex.getString("source"))
        assertEquals("basemap", ex.getString("source-layer"))
        // One new layer, nothing lost: every id in the 2D style is still here.
        val flatIds = layers(flat).map { it.getString("id") }
        val threeIds = layers(three).map { it.getString("id") }
        assertEquals("3D may not drop a layer", flatIds.toSet() - threeIds.toSet(), emptySet<String>())
        assertEquals(flatIds.size + 1, threeIds.size)
    }

    @Test
    fun `the extrusion reads the baked height and never a default`() {
        val ex = layer(style(extruded = true), "buildings-3d")!!
        val paint = ex.getJSONObject("paint")
        // Height comes from the feature's own `height_m`. If a literal ever
        // appears here, every building in Doha becomes one invented height.
        assertEquals("""["get","height_m"]""",
                     paint.getJSONArray("fill-extrusion-height").toString().replace(" ", ""))
        // The filter REQUIRES the property, so a building the bake did not
        // measure cannot be drawn even if one reached a tile.
        assertTrue(ex.getJSONArray("filter").toString().contains("height_m"))
        // The base is the source's min_height, or the ground. Both stated.
        val base = paint.get("fill-extrusion-base").toString()
        assertTrue("base must read min_height_m or fall back to 0",
                   base.contains("min_height_m") && base.contains("0"))
        assertTrue("an extruded mass must be at least slightly transparent "
                   + "so the route behind it stays visible",
                   paint.getDouble("fill-extrusion-opacity") < 1.0)
    }

    @Test
    fun `buildings draw under the road and under the route`() {
        // THE acceptance condition: "3D buildings must not obscure the active
        // route". A fill-extrusion has no z-order of its own — it wins on
        // layer ORDER — so this asserts the order rather than trusting it.
        val ids = layers(style(extruded = true)).map { it.getString("id") }
        val ex = ids.indexOf("buildings-3d")
        assertTrue("the extrusion must exist", ex >= 0)
        for (id in listOf("roads-minor", "roads-major", "route-line", "route-casing")) {
            val i = ids.indexOf(id)
            if (i >= 0) {
                assertTrue("$id ($i) must draw AFTER the extrusion ($ex), or a "
                           + "tower hides it", i > ex)
            }
        }
    }

    @Test
    fun `the extrusion starts at the zoom the tiles actually reach`() {
        // The tile bake covers z11-z15. A layer demanding z14 would render
        // nothing at any zoom the map reaches; demanding above the bake's
        // maxzoom would render nothing ever.
        val ex = layer(style(extruded = true), "buildings-3d")!!
        assertTrue(ex.has("minzoom"))
        assertEquals(MapCamera.BUILDINGS_3D_MINZOOM, ex.getDouble("minzoom"), 1e-9)
        assertTrue("the extrusion must be reachable inside the baked range",
                   ex.getDouble("minzoom") <= 15.0)
    }

    @Test
    fun `3D declares the light that makes an extrusion read as a volume`() {
        // The defect this pins, measured on device: MapLibre fills the top face
        // and the walls with the SAME colour, so without a `light` block an
        // extrusion is a flat polygon. A diagnostic build coloured it pure red
        // and 3.64% of the screen turned red — present, and still flat.
        val three = style(extruded = true)
        val light = three.getJSONObject("light")
        assertEquals("viewport", light.getString("anchor"))
        assertTrue("lighting must be present but restrained",
                   light.getDouble("intensity") in 0.05..0.8)
        assertTrue(layer(three, "buildings-3d")!!.getJSONObject("paint")
                       .getBoolean("fill-extrusion-vertical-gradient"))
        // And 2D declares no light: there is nothing in it to light.
        assertFalse(style(extruded = false).has("light"))
    }

    @Test
    fun `3D keeps every label and POI layer that 2D has`() {
        // "No label collapse": the extrusion is additive, so anything a driver
        // could read in 2D is still declared in 3D.
        val flat = layers(style(extruded = false)).map { it.getString("id") }
        val three = layers(style(extruded = true)).map { it.getString("id") }
        for (id in flat) {
            assertTrue("$id vanished in 3D", id in three)
        }
    }
}
