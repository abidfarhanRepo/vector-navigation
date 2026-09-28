package dev.vector.android

import dev.vector.geo.Callouts
import dev.vector.geo.LngLat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world-space warnings, as the renderer receives them.
 *
 * `CalloutsTest` in `core-geo` decides WHAT is warned about and asserts that
 * nothing is emitted without a source. This file is about the other half: that
 * the three pieces of a callout reach the map as one object, that the pill is
 * drawn in a way a 60-degree camera cannot destroy, and that a road name out of
 * OSM cannot take the whole style down with it.
 */
class CalloutStyleTest {

    private val p = StyleProbe(VectorStyle.MapTheme.DARK)

    private fun callout(
        kind: Callouts.Kind = Callouts.Kind.JUNCTION,
        text: String = "Left",
        source: String = "maneuver:turn-left@400m",
        icon: String? = null,
    ) = Callouts.Callout(
        kind = kind, alongM = 400.0,
        anchor = LngLat(51.5310, 25.2854),
        label = LngLat(51.5314, 25.2856),
        text = text, source = source, icon = icon,
    )

    private fun features(cs: List<Callouts.Callout>): JSONArray =
        JSONObject(VectorStyle.calloutGeoJson(cs)).getJSONArray("features")

    private fun part(fs: JSONArray, part: String): List<JSONObject> =
        (0 until fs.length()).map { fs.getJSONObject(it) }
            .filter { it.getJSONObject("properties").optString("part") == part }

    // ------------------------------------------------------- the three pieces

    @Test
    fun `a warning reaches the map as a dot, a leader and a pill`() {
        // §7.5's shape. All three in one source, so a single `setGeoJson` moves
        // them together and they can never disagree about where they are — a
        // pill that has moved on while its anchor has not is worse than no pill.
        val fs = features(listOf(callout()))
        assertEquals("a callout is not three features", 3, fs.length())
        assertEquals(1, part(fs, "anchor").size)
        assertEquals(1, part(fs, "leader").size)
        assertEquals(1, part(fs, "label").size)

        assertEquals("Point", part(fs, "anchor")[0].getJSONObject("geometry").getString("type"))
        assertEquals("Point", part(fs, "label")[0].getJSONObject("geometry").getString("type"))
        val leader = part(fs, "leader")[0].getJSONObject("geometry")
        assertEquals("LineString", leader.getString("type"))
        assertEquals("the leader does not join two points", 2,
                     leader.getJSONArray("coordinates").length())
    }

    @Test
    fun `the leader runs from the road to the pill and not somewhere else`() {
        val c = callout()
        val fs = features(listOf(c))
        val line = part(fs, "leader")[0].getJSONObject("geometry").getJSONArray("coordinates")
        val a = line.getJSONArray(0)
        val b = line.getJSONArray(1)
        assertEquals(c.anchor.lng, a.getDouble(0), 1e-12)
        assertEquals(c.anchor.lat, a.getDouble(1), 1e-12)
        assertEquals(c.label.lng, b.getDouble(0), 1e-12)
        assertEquals(c.label.lat, b.getDouble(1), 1e-12)
        // And GeoJSON is lng-then-lat, which is the mistake that puts Qatar in
        // Somalia and looks like the map failing to load.
        assertTrue("coordinates are lat,lng", a.getDouble(0) > 50.0 && a.getDouble(1) < 30.0)
    }

    @Test
    fun `each layer draws exactly its own piece`() {
        for ((id, want) in listOf("callout-anchor" to "anchor",
                                  "callout-leader" to "leader",
                                  "callout-pill" to "label")) {
            for (other in listOf("anchor", "leader", "label")) {
                val f = mapOf("part" to other)
                assertEquals(
                    "$id draws the '$other' piece as well as its own",
                    other == want, p.drawn(id, f, 17.5),
                )
            }
        }
    }

    @Test
    fun `no warning is drawn at the zooms a driver is reading the map at`() {
        val f = mapOf("part" to "label")
        assertFalse("pills clutter the explore map", p.drawn("callout-pill", f, 14.0))
        assertTrue(p.drawn("callout-pill", f, MapCamera.NAV_ZOOM))
    }

    // ------------------------------------------------------------- the pill

    @Test
    fun `the pill faces the viewer rather than lying on the road`() {
        // Unlike the chevrons, which lie flat on the carriageway because they
        // are paint, a pill is a sentence and a sentence has to be read. At 60
        // degrees a ground-aligned label is foreshortened into a band — §9
        // lists that as a risk of the new camera and names this as the fix.
        //
        // It also fixes the size: a viewport-aligned symbol is drawn at exactly
        // its style size however near the camera it is, so the pill is the same
        // object at the bottom of the screen and at the horizon. That is what
        // makes `MainActivity.calloutWindowFromM` necessary — see there.
        assertEquals("the pill lies flat on the road, where it cannot be read",
                     "viewport", p.layout("callout-pill", "text-pitch-alignment"))
        assertEquals("viewport", p.layout("callout-pill", "icon-pitch-alignment"))
        assertEquals("the pill rotates with the map and will read upside down",
                     "viewport", p.layout("callout-pill", "text-rotation-alignment"))
        assertEquals("viewport", p.layout("callout-pill", "icon-rotation-alignment"))
        // A fixed size, for the same reason. Nothing here interpolates.
        assertTrue("the pill's type size varies with zoom",
                   p.layout("callout-pill", "text-size") is Number)
    }

    @Test
    fun `the pill has a background, and it stretches to the words`() {
        assertEquals("the pill has no background, so it is text floating on a map",
                     VectorMarkers.CALLOUT_PILL, p.layout("callout-pill", "icon-image"))
        // WIDTH, not "both". The pill is a fixed height and a variable width,
        // and fitting both let a long label make a taller capsule than a short
        // one — three different pill heights on one screen.
        assertEquals("the background does not follow the text",
                     "width", p.layout("callout-pill", "icon-text-fit"))
        // Neither half may be dropped: an icon with no text is a blank capsule,
        // and text with no icon is unreadable over a road.
        assertEquals(false, p.layout("callout-pill", "icon-optional"))
        assertEquals(false, p.layout("callout-pill", "text-optional"))
    }

    @Test
    fun `the words come from the feature rather than from the style`() {
        val field = p.layout("callout-pill", "text-field")
        assertTrue("the pill says the same thing on every road: $field", field is JSONArray)
        assertEquals("get", (field as JSONArray).getString(0))
        assertEquals("text", field.getString(1))
    }

    @Test
    fun `the more irreversible the mistake, the harder the pill fights for space`() {
        // `symbol-sort-key` places lower values first, and a placed symbol wins
        // the collision. A speed limit outranks a maneuver, which outranks a
        // bend, which is the order of what it costs to miss one.
        assertEquals(JSONArray(listOf("get", "rank")).toString(),
                     p.layout("callout-pill", "symbol-sort-key").toString())
        fun rank(kind: Callouts.Kind): Int =
            part(features(listOf(callout(kind = kind))), "label")[0]
                .getJSONObject("properties").getInt("rank")
        assertTrue("a maneuver outranks a speed limit",
                   rank(Callouts.Kind.SPEED) < rank(Callouts.Kind.JUNCTION))
        assertTrue("a bend outranks a maneuver",
                   rank(Callouts.Kind.JUNCTION) < rank(Callouts.Kind.BEND))
        // V7 Stage 5: the signal pill is a location fact, the weakest claim of
        // the four, so it yields placement to every other pill.
        assertTrue("a signal outranks nothing -- it is the weakest claim",
                   rank(Callouts.Kind.BEND) < rank(Callouts.Kind.SIGNAL))
    }

    @Test
    fun `pills do not stack on top of one another`() {
        // Deliberately NOT `allow-overlap`, unlike the chevrons. A chevron is
        // one of six hundred and losing some is invisible; a pill is a sentence
        // and two of them overlapping is unreadable.
        assertNotEquals(true, p.layout("callout-pill", "icon-allow-overlap"))
        assertNotEquals(true, p.layout("callout-pill", "text-allow-overlap"))
    }

    // ------------------------------------------------------------- provenance

    @Test
    fun `the source survives all the way to the map`() {
        // `CalloutsTest` proves nothing is emitted without a source. This proves
        // the source is still attached when it gets there, so a pill on a real
        // screen can always be traced back to the thing that justified it.
        val cs = listOf(
            callout(Callouts.Kind.JUNCTION, "Exit Q3", "maneuver:off-ramp@1200m"),
            callout(Callouts.Kind.BEND, "Sharp bend", "curvature:62deg/40m"),
            callout(Callouts.Kind.SPEED, "80 km/h", "maxspeed:80"),
        )
        val labels = part(features(cs), "label")
        assertEquals(3, labels.size)
        for ((i, l) in labels.withIndex()) {
            val props = l.getJSONObject("properties")
            assertEquals(cs[i].source, props.getString("source"))
            assertEquals(cs[i].text, props.getString("text"))
            assertEquals(cs[i].kind.name.lowercase(), props.getString("kind"))
        }
    }

    // ------------------------------------------------------------- robustness

    @Test
    fun `a road name with a quote in it cannot take the map down`() {
        // Exit refs and road names arrive from OSM unedited and are pasted into
        // a JSON document by hand. One unescaped quote makes the whole style
        // unparseable, which renders a blank map and reports nothing — the
        // failure mode `VectorStyle`'s KDoc opens with.
        val nasty = listOf(
            "Exit \"Q3\"", "back\\slash", "new\nline", "tab\there",
            "شارع الكورنيش", "controlchar",
        )
        for (text in nasty) {
            val json = VectorStyle.calloutGeoJson(listOf(callout(text = text, source = text)))
            val fs = JSONObject(json).getJSONArray("features")   // throws if malformed
            val props = (0 until fs.length()).map { fs.getJSONObject(it) }
                .first { it.getJSONObject("properties").optString("part") == "label" }
                .getJSONObject("properties")
            assertEquals("'$text' did not survive the round trip", text, props.getString("text"))
            assertEquals(text, props.getString("source"))
        }
    }

    @Test
    fun `no warnings produces an empty collection, not a stale one`() {
        // The source is written on every route change, including the change to
        // "no route". Anything other than a well-formed empty collection here
        // leaves the last journey's pills on the map.
        val o = JSONObject(VectorStyle.calloutGeoJson(emptyList()))
        assertEquals("FeatureCollection", o.getString("type"))
        assertEquals(0, o.getJSONArray("features").length())
    }

    @Test
    fun `many warnings stay one well-formed document`() {
        val many = (1..60).map { callout(text = "Exit Q$it", source = "maneuver:off-ramp@${it}00m") }
        val fs = features(many)
        assertEquals(180, fs.length())
        assertEquals(60, part(fs, "label").size)
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `a warning beats a shop name for the space it needs`() {
        // Layer order is placement order, and placement order decides
        // collisions. MapLibre places the TOP layer first, so a symbol layer
        // low in the document loses its slot to everything above it — which is
        // how, in the light theme over Msheirb, every pill was culled by POI
        // labels while its anchor dot and leader line drew perfectly. A dot on
        // the road pointing at nothing is worse than no callout at all.
        //
        // So the pill sits above every label in the document. The dot and the
        // leader stay down in the world where they belong: they are geometry,
        // they take part in no collision, and they should be under a road name
        // rather than over it.
        val order = p.ids
        for (label in listOf("poi-labels", "place-labels", "road-labels", "route-labels")) {
            assertTrue(
                "$label is placed before the warning pill and will cull it",
                order.indexOf("callout-pill") > order.indexOf(label),
            )
        }
        assertEquals("the pill is the last thing in the document",
                     order.size - 1, order.indexOf("callout-pill"))
        assertTrue("the route is drawn over the warning that describes it",
                   order.indexOf("callout-leader") > order.indexOf("route-chevrons"))
        assertTrue("the anchor dot is buried under its own leader",
                   order.indexOf("callout-anchor") > order.indexOf("callout-leader"))
        assertTrue("the anchor dot is drawn over the road labels",
                   order.indexOf("callout-anchor") < order.indexOf("road-labels"))
    }

    @Test
    fun `a warning looks the same by day and by night`() {
        // §4.4. A driver acts on this, so it must not change appearance with the
        // time of day — unlike every other surface in the document.
        val light = StyleProbe(VectorStyle.MapTheme.LIGHT)
        assertEquals(p.paint("callout-pill", "text-color"),
                     light.paint("callout-pill", "text-color"))
        assertEquals(p.paint("callout-leader", "line-color"),
                     light.paint("callout-leader", "line-color"))
        assertEquals(p.paint("callout-anchor", "circle-color"),
                     light.paint("callout-anchor", "circle-color"))
        assertEquals(p.layout("callout-pill", "text-size"),
                     light.layout("callout-pill", "text-size"))
    }

    @Test
    fun `the leader is a thread, not a road`() {
        // Half a metre of it, metric like everything else on the surface, with
        // a floor so it does not vanish. A leader as heavy as a lane line would
        // read as another marking on the carriageway.
        for (z in listOf(15.0, MapCamera.NAV_ZOOM, 19.0)) {
            val w = p.width("callout-leader", mapOf("part" to "leader"), z)
            assertTrue("at z$z the leader is $w px, too faint to follow", w >= 1.0)
            assertTrue("at z$z the leader is as wide as the route ribbon",
                       w < p.width("route", zoom = z) * 0.4)
        }
    }

    // ------------------------------------------------------------ the glyphs

    @Test
    fun `a warning with a picture carries it on its anchor, not its pill`() {
        // The picture IS the thing, so it belongs where the thing is; the words
        // hang off to one side on the leader, as they always have.
        val fs = features(listOf(callout(kind = Callouts.Kind.SIGNAL, icon = Callouts.Icon.SIGNAL)))
        val anchor = part(fs, "anchor").single().getJSONObject("properties")
        assertEquals(Callouts.Icon.SIGNAL, anchor.getString("icon"))
        assertFalse("the pill must not carry it as well",
                    part(fs, "label").single().getJSONObject("properties").has("icon"))
    }

    @Test
    fun `a glyph replaces the dot rather than sitting on top of it`() {
        val withIcon = mapOf("part" to "anchor", "icon" to Callouts.Icon.SIGNAL)
        val without = mapOf("part" to "anchor")
        assertTrue("the dot still draws for a warning with no picture",
                   p.drawn("callout-anchor", without, 16.0))
        assertFalse("a dot under a glyph is a smudge behind it",
                    p.drawn("callout-anchor", withIcon, 16.0))
        assertTrue(p.drawn("callout-icon", withIcon, 16.0))
        assertFalse(p.drawn("callout-icon", without, 16.0))
    }

    @Test
    fun `the icon layer draws whatever the callout names`() {
        // `["get", "icon"]`, so the vocabulary is written once — in
        // `Callouts.Icon`, asserted against `VectorMarkers.WARN_IMAGES` by
        // `WarnGlyphTest`. A `match` here would be a second copy of the same
        // list, and the half that is easier to forget is the half that renders
        // nothing at all.
        assertEquals(
            listOf("get", "icon"),
            (p.layout("callout-icon", "icon-image") as JSONArray).let { a ->
                (0 until a.length()).map { a.get(it) }
            },
        )
    }

    @Test
    fun `every glyph the vocabulary can emit is a value that reaches the map`() {
        // End to end through the GeoJSON the renderer is actually handed: a
        // vocabulary entry that no callout can carry is a picture nothing
        // draws, and the style would never say so.
        for (icon in Callouts.Icon.ALL) {
            val fs = features(listOf(callout(kind = Callouts.Kind.CAMERA, icon = icon)))
            assertEquals(icon, part(fs, "anchor").single()
                .getJSONObject("properties").getString("icon"))
        }
    }

    @Test
    fun `the glyph is anchored at the point and does not rotate with the map`() {
        // Viewport alignment, like the pill: a traffic light drawn at the
        // map's bearing would lie on its side.
        assertEquals("viewport", p.layout("callout-icon", "icon-pitch-alignment"))
        assertEquals("viewport", p.layout("callout-icon", "icon-rotation-alignment"))
        assertTrue("a glyph that yields placement is a glyph that is missing",
                   p.layout("callout-icon", "icon-allow-overlap") == true)
    }
}
