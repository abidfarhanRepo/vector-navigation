package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route, once the road under it has a width of its own.
 *
 * ## What changed and why it needed tests
 *
 * The ribbon used to be a line whose width was tuned by eye against a map with
 * no roads on it, only coloured strokes — `["exponential", 1.4]` from 4.5 px at
 * z10 to 22 px at z18, which is a shape rather than a size. Against a
 * carriageway drawn at true metric width that stops being arbitrary and starts
 * being wrong: the same 22 px is 1.7 lanes on a Doha arterial at z18 and four
 * lanes at z16, so the ribbon grows and shrinks relative to the road it is
 * supposed to be sitting in.
 *
 * So the ribbon is metric now, and the two things worth asserting are that it
 * IS metric where it matters and that it stops being metric before it
 * disappears. A 5.6 m ribbon is four hundredths of a pixel at the zoom the
 * preview frames a 14 km drive at.
 *
 * The third thing is subtler and is the reason for `the ribbon does not bury
 * the markings underneath it`: the whole argument of V8 is that a driver can
 * read lane markings off the screen, and a route ribbon wide enough to cover
 * them takes that back on precisely the road the driver is on.
 *
 * ## V7 Stage 4: the ribbon has a lateral position now
 *
 * Every width assertion above measures the ribbon on the road it was tuned
 * for, and they all still hold, because the Stage-4 width is a generalisation
 * of that default rather than a replacement for it. What is new is
 * `line-offset`, and the cases worth asserting are the ones MapLibre fails
 * SILENTLY on:
 *
 *  * a dash on the same layer as a data-driven offset renders nothing at all,
 *    which reads as "no route" rather than as "broken style";
 *  * a feature carrying no `offset`/`width` has to fall back to exactly the
 *    pre-Stage-4 numbers, or every route without lane data changes appearance
 *    for no reason at all.
 */
class RouteRibbonTest {

    private val p = StyleProbe(VectorStyle.MapTheme.DARK)

    /** Logical pixels across one Doha lane. */
    private fun lane(z: Double) = VectorStyle.laneWidthPx(z)

    // ------------------------------------------------------------ metric width

    @Test
    fun `the ribbon is the same number of lanes wide at every driving zoom`() {
        // The claim: at any zoom a driver navigates at, the ribbon is the same
        // number of LANES wide, because it is the same number of metres wide.
        // The old curve was 1.7 lanes at z18 and four lanes at z16 — a shape
        // rather than a size.
        for (z in listOf(16.5, 17.0, MapCamera.NAV_ZOOM, 18.0, 19.0)) {
            val lanes = p.width("route", zoom = z) / lane(z)
            assertEquals(
                "at z$z the ribbon is ${"%.2f".format(lanes)} lanes wide",
                1.6, lanes, 0.02,
            )
        }
    }

    @Test
    fun `the ribbon does not bury the markings underneath it`() {
        // The failure this phase would be embarrassed by: a route drawn over
        // the lane lines it exists to make readable. On the narrowest road that
        // carries markings at all — two lanes — the ribbon has to leave both
        // edge lines showing.
        for (z in listOf(16.5, MapCamera.NAV_ZOOM, 18.5)) {
            for (lanes in 2..6) {
                val carriageway = lane(z) * lanes
                val ribbon = p.width("route", zoom = z)
                val casing = p.width("route-casing", zoom = z)
                assertTrue(
                    "at z$z the ribbon (${"%.1f".format(ribbon)} px) covers a $lanes-lane " +
                        "carriageway (${"%.1f".format(carriageway)} px)",
                    ribbon < carriageway,
                )
                assertTrue(
                    "at z$z the ribbon's own casing (${"%.1f".format(casing)} px) reaches " +
                        "the kerbs of a $lanes-lane road",
                    casing < carriageway,
                )
            }
        }
    }

    @Test
    fun `the casing is a rim on the ribbon, not a second ribbon`() {
        // It was 30 px against the route's 22 at z18 — 8 px of dark on a road
        // that is now only ~39 px across, which reads as a black gutter either
        // side of the route rather than as an edge on it.
        for (z in listOf(16.5, MapCamera.NAV_ZOOM, 19.0)) {
            val over = p.width("route-casing", zoom = z) - p.width("route", zoom = z)
            assertTrue("at z$z the casing does not sit outside the ribbon", over > 0)
            assertTrue(
                "at z$z the casing adds ${"%.1f".format(over)} px, more than a third of a lane",
                over < lane(z) * 0.7,
            )
        }
    }

    @Test
    fun `an alternative is narrower than the route that was chosen`() {
        // Hue carries "this is a route" and saturation carries "you have not
        // picked it" — `VectorStyle.routeAlt` says so. Width is the third
        // signal and it was the wrong way round at high zoom before this,
        // because the two used different curves.
        for (z in listOf(14.0, 16.0, MapCamera.NAV_ZOOM, 19.0)) {
            assertTrue(
                "at z$z an unchosen route is drawn at least as wide as the chosen one",
                p.width("route-alt", zoom = z) < p.width("route", zoom = z),
            )
        }
    }

    // --------------------------------------------------------------- the floor

    @Test
    fun `the preview of a whole city still has a route in it`() {
        // 5.6 m is 0.04 logical pixels at z10. Metric truth and a visible line
        // are different requirements at different zooms, and this is the zoom
        // where the second one wins.
        val floors = mapOf("route" to 4.5, "route-casing" to 7.0,
                           "route-alt" to 3.5, "route-alt-casing" to 6.0)
        for ((id, floor) in floors) {
            for (z in listOf(6.0, 8.0, 10.0, 12.0)) {
                assertEquals(
                    "$id is ${"%.2f".format(p.width(id, zoom = z))} px at z$z — " +
                        "the route preview would be a hairline",
                    floor, p.width(id, zoom = z), 0.01,
                )
            }
        }
    }

    @Test
    fun `the z10 crossover width is exactly what it was before the ribbon went metric`() {
        // The ONE zoom where the new metric-plus-floor curve and the old
        // hand-tuned curve agree: the old curve's z10 stop (4.5 px) is the same
        // value as the floor. Nothing is claimed for z6-9 (wider under the
        // floor) or for z11 and up (thinner, because the metric implementation
        // holds the floor where the old exponential had already grown). See
        // V8 §7.4; the earlier "unchanged below the crossover" claim was false.
        assertEquals(4.5, p.width("route", zoom = 10.0), 1e-9)
        assertEquals(7.0, p.width("route-casing", zoom = 10.0), 1e-9)
        assertEquals(3.5, p.width("route-alt", zoom = 10.0), 1e-9)
        assertEquals(6.0, p.width("route-alt-casing", zoom = 10.0), 1e-9)
    }

    @Test
    fun `the width never steps, so a zoom gesture does not make the route jump`() {
        // The floor is a stop and not a `max`, so the two meet continuously.
        // A discontinuity here would be a visible snap mid-pinch, which is the
        // kind of thing that reads as a bug rather than as a design.
        for (id in listOf("route", "route-casing", "route-alt", "route-alt-casing")) {
            var prev = p.width(id, zoom = 6.0)
            var z = 6.05
            while (z <= 20.0) {
                val w = p.width(id, zoom = z)
                assertTrue("$id jumps from $prev to $w between z${z - 0.05} and z$z",
                           w - prev < Math.max(0.35, prev * 0.06))
                assertTrue("$id narrows as the map zooms in, at z$z", w >= prev - 1e-9)
                prev = w
                z += 0.05
            }
        }
    }

    // ------------------------------------------------------------- the chevrons

    @Test
    fun `the chevrons are geometry rather than type`() {
        // A `symbol` layer with `text-field: ">"` was the first version, and
        // `RouteChevrons`'s KDoc carries the three reasons it is wrong. The one
        // this file can assert is the KIND of layer, and it is the one that
        // matters most: a glyph is sized in screen pixels, so a text chevron
        // would be the same size on screen whatever the road was doing — at
        // odds with every other object in this phase, all of which are drawn at
        // true metric size and foreshorten with the carriageway they are
        // painted on.
        assertEquals("the chevrons are type again, and type is not metric",
                     "line", p.layer("route-chevrons").getString("type"))
        assertEquals("the chevrons are drawn from the route line rather than their own marks",
                     "route-chevrons", p.layer("route-chevrons").getString("source"))
    }

    @Test
    fun `a chevron cannot overhang the ribbon it is painted on`() {
        // Both are metric and both are derived from ROUTE_RIBBON_M, so this
        // holds at every zoom by construction rather than by tuning — which is
        // the point of deriving one from the other.
        val across = VectorStyle.CHEVRON_HALF_WIDTH_M * 2
        assertTrue(
            "a chevron is $across m across a ${VectorStyle.ROUTE_RIBBON_M} m ribbon",
            across < VectorStyle.ROUTE_RIBBON_M,
        )
        // And the stroke stays visible without becoming a second ribbon.
        for (z in listOf(15.0, 16.0, MapCamera.NAV_ZOOM, 19.0)) {
            val stroke = p.width("route-chevrons", zoom = z)
            assertTrue("at z$z the chevron stroke is $stroke px", stroke >= 1.0)
            assertTrue("at z$z the chevron stroke is as wide as the ribbon",
                       stroke < p.width("route", zoom = z) * 0.5)
        }
    }

    @Test
    fun `the chevrons appear only where the ribbon is wide enough to hold them`() {
        assertFalse("chevrons are drawn at zooms where the ribbon is a hairline",
                    p.drawn("route-chevrons", zoom = 13.0))
        assertTrue(p.drawn("route-chevrons", zoom = MapCamera.NAV_ZOOM))
    }

    @Test
    fun `a route produces marks, and no route produces an empty collection`() {
        // The source is set on every route change, including the change to "no
        // route". A stale set of chevrons left on the map after the driver taps
        // out is the kind of thing that reads as a ghost.
        val empty = org.json.JSONObject(VectorStyle.chevronGeoJson(null))
        assertEquals("FeatureCollection", empty.getString("type"))
        assertEquals("a null route produced marks", 0, empty.getJSONArray("features").length())

        // A kilometre of Corniche, at the real spacing.
        val pts = (0..100).map {
            dev.vector.geo.LngLat(51.5310 + it * 0.0001, 25.2854)
        }
        val fc = org.json.JSONObject(
            VectorStyle.chevronGeoJson(dev.vector.geo.RouteGeometry.index(pts))
        )
        val feats = fc.getJSONArray("features")
        assertTrue("a 1 km route produced ${feats.length()} marks", feats.length() > 30)
        val g = feats.getJSONObject(0).getJSONObject("geometry")
        assertEquals("LineString", g.getString("type"))
        assertEquals("a chevron is not three points", 3, g.getJSONArray("coordinates").length())
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `the route is drawn over the road it is on, and under the vehicle`() {
        val order = p.ids
        assertTrue("the route is drawn over the carriageway",
                   order.indexOf("carriageway") < order.indexOf("route"))
        assertTrue("the chevrons are drawn over the ribbon they emboss",
                   order.indexOf("route-chevrons") > order.indexOf("route"))
        assertTrue("the vehicle is drawn over the route",
                   order.indexOf("puck") > order.indexOf("route-chevrons"))
    }

    @Test
    fun `both themes agree about the route, because meaning does not move`() {
        val light = StyleProbe(VectorStyle.MapTheme.LIGHT)
        for (id in listOf("route", "route-casing", "route-chevrons")) {
            val key = if (id == "route-chevrons") "text-color" else "line-color"
            assertEquals(
                "$id changes colour with the time of day",
                p.paint(id, key), light.paint(id, key),
            )
        }
        for (z in listOf(10.0, MapCamera.NAV_ZOOM)) {
            assertEquals("the ribbon changes width with the time of day",
                         p.width("route", zoom = z), light.width("route", zoom = z), 1e-9)
        }
    }

    // ------------------------------------------------- V7 Stage 4: the offset

    @Test
    fun `the ribbon carries no dash, which is what makes its offset legal`() {
        // `line-dasharray` and a data-driven `line-offset` on one layer render
        // NOTHING in MapLibre Native 11.13.5 — no warning, no log line. That is
        // why `dividerOffset` spends fifteen layers avoiding the pair, and it
        // is the one thing about the Stage-4 ribbon that cannot be caught by
        // looking at the screen: the route would simply be absent, which reads
        // as "no route" rather than as "broken style".
        for (id in listOf("route", "route-casing")) {
            assertNull(
                "$id has both a dash and a data-driven offset — it will render nothing",
                p.paint(id, "line-dasharray"),
            )
            assertNotNull("$id has no lateral position", p.paint(id, "line-offset"))
            assertTrue(
                "$id's offset is a constant rather than a per-feature value",
                p.paint(id, "line-offset").toString().contains("offset"),
            )
        }
    }

    @Test
    fun `a route with no lane data is drawn exactly as it was before Stage 4`() {
        // The model declines on most of Qatar's network, and a decline must be
        // invisible: same width, same casing, no lateral movement. A feature
        // with NO properties is what `routeGeoJson` emits in that case.
        val none = emptyMap<String, Any?>()
        // The offset holds at EVERY zoom, floor zone included: a route with no
        // lateral claim does not move, however far out the map is.
        for (z in listOf(6.0, 10.0, 14.0, 16.5, MapCamera.NAV_ZOOM, 19.0)) {
            assertEquals(
                "the ribbon moved at z$z on a route with no lane data",
                0.0, p.offset("route", none, z), 1e-9,
            )
            assertEquals(0.0, p.offset("route-casing", none, z), 1e-9)
        }
        // The width holds at DRIVING zooms — below the crossover the floor owns
        // it, which is `the preview of a whole city still has a route in it`
        // and is asserted there rather than restated here. 1.6 lanes is what
        // the old hand-tuned curve was replaced by.
        for (z in listOf(16.5, MapCamera.NAV_ZOOM, 19.0)) {
            assertEquals(
                "the ribbon changed width at z$z on a route with no lane data",
                1.6, p.width("route", none, z) / lane(z), 0.02,
            )
        }
        // And the floor itself is untouched by the feature-driven expression.
        assertEquals(4.5, p.width("route", none, 10.0), 1e-9)
        assertEquals(7.0, p.width("route-casing", none, 10.0), 1e-9)
    }

    @Test
    fun `the offset is a true metric distance, like everything else on this map`() {
        // 1.75 m is the half-carriageway of the commonest two-way road in
        // Qatar — 5,728 ways — and the number the whole of Stage 4 turns on.
        for (z in listOf(15.0, 16.5, MapCamera.NAV_ZOOM, 19.0)) {
            val f = mapOf<String, Any?>("offset" to 1.75)
            assertEquals(
                "at z$z the ribbon is offset ${p.offset("route", f, z)} px, not 1.75 m",
                VectorStyle.metresPx(1.75, z), p.offset("route", f, z), 1e-6,
            )
            // The casing has to move with it or it becomes a second ribbon on
            // the centreline.
            assertEquals(
                p.offset("route", f, z), p.offset("route-casing", f, z), 1e-9,
            )
        }
    }

    @Test
    fun `a left-hand offset is negative, as MapLibre and the lane dividers agree`() {
        // The sign is the one thing that, inverted, puts the route in the
        // oncoming carriageway with total confidence. `dividerOffset` already
        // relies on the same convention for its near-side dividers.
        val z = MapCamera.NAV_ZOOM
        assertTrue(p.offset("route", mapOf<String, Any?>("offset" to -3.5), z) < 0.0)
        assertTrue(p.offset("route", mapOf<String, Any?>("offset" to 3.5), z) > 0.0)
    }

    @Test
    fun `the ribbon narrows to fit the carriageway it has moved onto`() {
        // Moving the ribbon onto the driven carriageway is only half the
        // correction: a 5.6 m ribbon centred in a single 3.5 m lane still
        // spills over the centre line, which is the same lie in a smaller size.
        assertEquals(VectorStyle.ROUTE_RIBBON_M, VectorStyle.ribbonWidthIn(null), 1e-9)
        // Two lanes is the road the default was tuned for, and it comes out
        // unchanged — the margin is a generalisation, not a competing number.
        assertEquals(VectorStyle.ROUTE_RIBBON_M, VectorStyle.ribbonWidthIn(7.0), 1e-9)
        assertEquals(2.1, VectorStyle.ribbonWidthIn(3.5), 1e-9)
        // Never wider than the tuned default, however wide the road.
        assertEquals(VectorStyle.ROUTE_RIBBON_M, VectorStyle.ribbonWidthIn(21.0), 1e-9)
        // And never so thin it stops being a ribbon.
        assertTrue(VectorStyle.ribbonWidthIn(0.5) >= 1.0)
    }

    @Test
    fun `a ribbon in a single lane keeps its casing inside that lane`() {
        // The assertion that catches the whole class of error: it is not enough
        // for the ribbon to fit, because the casing is what the driver sees
        // touching the paint.
        val f = mapOf<String, Any?>("width" to VectorStyle.ribbonWidthIn(3.5))
        for (z in listOf(16.5, MapCamera.NAV_ZOOM, 19.0)) {
            assertTrue(
                "at z$z a lane-width ribbon's casing (${p.width("route-casing", f, z)} px) " +
                    "reaches outside its ${lane(z)} px lane",
                p.width("route-casing", f, z) < lane(z),
            )
        }
    }

    // ------------------------------------------------ V7 Stage 4: the features

    /** ~1 km of straight Corniche, as the router would send it. */
    private val kilometre = (0..100).map { dev.vector.geo.LngLat(51.5310 + it * 0.0001, 25.2854) }

    private fun index() = dev.vector.geo.RouteGeometry.index(kilometre)

    /** A two-way residential leg: one lane each way, the M2 case. */
    private fun twoWayPlan() = dev.vector.geo.RouteLanes.plan(
        listOf(dev.vector.geo.RouteLanes.Approach(atM = 1_000.0, forwardLanes = 1, totalLanes = 2))
    )

    @Test
    fun `a route with no plan is one plain feature, as it always was`() {
        val fc = org.json.JSONObject(VectorStyle.routeGeoJson(index()))
        val feats = fc.getJSONArray("features")
        assertEquals("a route with no lane data was cut up for no reason", 1, feats.length())
        val props = feats.getJSONObject(0).getJSONObject("properties")
        assertEquals("a declining model still wrote properties", 0, props.length())
        assertEquals(
            "the geometry was resampled",
            kilometre.size,
            feats.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").length(),
        )
    }

    @Test
    fun `no route at all is an empty collection rather than a stale one`() {
        assertEquals(VectorStyle.EMPTY_FEATURES, VectorStyle.routeGeoJson(null))
        assertEquals(
            VectorStyle.EMPTY_FEATURES,
            VectorStyle.routeGeoJson(dev.vector.geo.RouteGeometry.index(kilometre.take(1))),
        )
    }

    @Test
    fun `a placed route carries its offset and width on every feature`() {
        val fc = org.json.JSONObject(VectorStyle.routeGeoJson(index(), twoWayPlan()))
        val feats = fc.getJSONArray("features")
        assertTrue("a placed route produced no features", feats.length() >= 1)
        for (i in 0 until feats.length()) {
            val props = feats.getJSONObject(i).getJSONObject("properties")
            assertEquals(
                "feature $i is not on the driven carriageway",
                1.75, props.getDouble("offset"), 1e-3,
            )
            assertEquals(
                "feature $i is wider than the lane it claims",
                VectorStyle.ribbonWidthIn(3.5), props.getDouble("width"), 1e-3,
            )
        }
    }

    @Test
    fun `the features tile the route without a gap or an overlap`() {
        // A gap is a length of route with no ribbon; an overlap is two ribbons
        // at different offsets drawn over each other. Both are visible and both
        // read as a rendering bug.
        val plan = dev.vector.geo.RouteLanes.plan(
            listOf(
                dev.vector.geo.RouteLanes.Approach(
                    atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                    lanes = dev.vector.geo.LaneGuidance.usefulLanes(
                        "through|through|through|through;slight_right", "slight-right",
                    ),
                )
            )
        )
        val feats = org.json.JSONObject(VectorStyle.routeGeoJson(index(), plan))
            .getJSONArray("features")
        assertTrue("a taper was not cut into pieces", feats.length() > 1)
        var prevEnd: org.json.JSONArray? = null
        for (i in 0 until feats.length()) {
            val c = feats.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
            assertTrue("feature $i is a single point", c.length() >= 2)
            val first = c.getJSONArray(0)
            if (prevEnd != null) {
                assertEquals(
                    "feature $i does not begin where feature ${i - 1} ended",
                    prevEnd.getDouble(0), first.getDouble(0), 1e-9,
                )
                assertEquals(prevEnd.getDouble(1), first.getDouble(1), 1e-9)
            }
            prevEnd = c.getJSONArray(c.length() - 1)
        }
        // The features cover the WHOLE route, not just as far as the last
        // maneuver. This fixture is 1,005 m long with its last approach at
        // 1,000 m, and the five metres between them are the ones in front of
        // the destination pin.
        val head = feats.getJSONObject(0).getJSONObject("geometry")
            .getJSONArray("coordinates").getJSONArray(0)
        assertEquals(kilometre.first().lng, head.getDouble(0), 1e-9)
        assertEquals(
            "the ribbon stops short of the destination",
            kilometre.last().lng, prevEnd!!.getDouble(0), 1e-9,
        )
    }

    @Test
    fun `the chevrons move onto the carriageway the ribbon moved onto`() {
        // Leaving the marks on the centreline would draw direction arrows down
        // the middle of the road while the ribbon they emboss sits beside
        // them — the exact contradiction Stage 4 exists to remove.
        val idx = index()
        val centre = org.json.JSONObject(VectorStyle.chevronGeoJson(idx))
            .getJSONArray("features")
        val placed = org.json.JSONObject(VectorStyle.chevronGeoJson(idx, twoWayPlan()))
            .getJSONArray("features")
        assertEquals("the plan changed how many marks there are",
                     centre.length(), placed.length())
        assertTrue(centre.length() > 10)
        fun apexLat(a: org.json.JSONArray, i: Int) = a.getJSONObject(i)
            .getJSONObject("geometry").getJSONArray("coordinates")
            .getJSONArray(1).getDouble(1)
        for (i in 0 until centre.length()) {
            // Travelling due east, so right of travel is SOUTH: the placed mark
            // must sit at a lower latitude, by half a carriageway.
            val moved = apexLat(centre, i) - apexLat(placed, i)
            val metres = moved * dev.vector.geo.RouteGeometry.M_PER_DEG_LAT
            assertEquals("mark $i moved $metres m instead of 1.75 m to the right",
                         1.75, metres, 0.05)
        }
    }

    @Test
    fun `a chevron does not turn inside a taper, it only moves`() {
        // The two ends of a mark are 3 m apart and a lane change moves them by
        // different amounts. Taking the bearing from the OFFSET pair would yaw
        // the mark by several degrees for reasons that have nothing to do with
        // the road.
        val plan = dev.vector.geo.RouteLanes.plan(
            listOf(
                dev.vector.geo.RouteLanes.Approach(
                    atM = 1_000.0, forwardLanes = 4, totalLanes = 4,
                    lanes = dev.vector.geo.LaneGuidance.usefulLanes(
                        "left|left|through|through", "turn-left",
                    ),
                )
            )
        )
        val feats = org.json.JSONObject(VectorStyle.chevronGeoJson(index(), plan))
            .getJSONArray("features")
        for (i in 0 until feats.length()) {
            val c = feats.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
            val left = c.getJSONArray(0)
            val right = c.getJSONArray(2)
            // The wings stay perpendicular to due east — same longitude —
            // whatever the offset is doing between them.
            assertEquals("mark $i yawed inside the taper",
                         left.getDouble(0), right.getDouble(0), 1e-9)
        }
    }
}
