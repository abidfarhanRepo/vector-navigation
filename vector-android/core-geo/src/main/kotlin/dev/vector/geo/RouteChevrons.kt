package dev.vector.geo

import kotlin.math.abs

/**
 * Direction marks along the route, as geometry.
 *
 * ## Why these are not text
 *
 * The obvious way to get chevrons onto a line in MapLibre is a `symbol` layer
 * with `symbol-placement: line` and `text-field: ">"`, and that was the first
 * version. Three things are wrong with it and only one of them is obvious.
 *
 * **It is not metric.** A glyph is sized in screen pixels, so a text chevron is
 * the same size on screen whatever the road is doing — which puts it at odds
 * with every other object in this phase, all of which are drawn at true size.
 *
 * **It does not lie on the road.** V8 §2.2 calls the marks "embossed on it",
 * and at 60 degrees of pitch that is most of the effect: the chevron has to
 * foreshorten with the carriageway the way real paint would. A glyph can be
 * pitch-aligned to get that, but then it is magnified by how near the camera it
 * is — on top of the display density, on top of `text-size` — and past roughly
 * twice its authored size a signed-distance field stops holding the shape it
 * was drawn as. The alternative, aligning it to the viewport, is crisp and
 * stands up off the road like a billboard.
 *
 * **It depends on a glyph range the store may not serve.** `Open Sans
 * Regular/0-255` is 76 kB and every other range — 8192-8447, 9472-9727, the
 * ones holding the arrows anyone would reach for first — answers **two bytes
 * with HTTP 200**, which is not an error the app can see. So the mark would
 * have to be the ASCII `>`, chosen for being available rather than for being
 * the right shape.
 *
 * Geometry has none of those problems: it is metric, it foreshortens with the
 * surface it is painted on, and it is the shape it is asked to be.
 *
 * **A correction, recorded because it cost a build cycle.** The symptom that
 * prompted this rewrite was chevrons rendering as solid white parallelograms,
 * and the first diagnosis was signed-distance-field saturation from exactly the
 * magnification described above. That was wrong. The emulator was running
 * Google SwiftShader, which renders **every** MapLibre glyph as a filled
 * quad — road names, place names and the warning pills all did the same thing,
 * at every size and at every pitch, while the glyph data coming off the wire
 * was verified intact (141 distinct distance levels in a clean `>`). Restarting
 * the emulator with the host GPU rendered all of it correctly. The reasons
 * above are the real ones and they stand on their own; the filled quads were
 * never evidence for any of them.
 *
 * ## The shape
 *
 * Three points — left wing, apex, right wing — drawn as one polyline with
 * round joins, so it renders as a `>` lying on the carriageway pointing the way
 * the driver is going.
 */
object RouteChevrons {

    /**
     * Chevrons along [route], one every [spacingM] metres.
     *
     * @param lengthM how far the apex sits ahead of the wings, along the route.
     * @param halfWidthM how far each wing sits to the side of the centreline.
     * @param limit stop after this many, so a very long route cannot make the
     *   source unbounded. 40 km at 22 m spacing is about 1,800 marks; the cap
     *   exists for the pathological case rather than the normal one.
     *
     * Marks are placed by along-route distance rather than per vertex, so their
     * spacing is even whatever the shape-point density of the geometry is —
     * OSM motorway geometry runs 200 m between vertices and a residential
     * street runs 10 m, and a per-vertex placement would cluster on the second.
     *
     * @param lateralM the route's own lateral offset at a given along-route
     *   distance — [RouteLanes.Plan.offsetAt], in practice. Chevrons describe
     *   where the ROUTE goes, so they have to move onto the driven carriageway
     *   with the ribbon: leaving them on the centreline of a two-way road would
     *   draw direction marks down the middle of the road while the ribbon they
     *   emboss sits beside them, which is the contradiction V7 Stage 4 exists
     *   to remove rather than one to introduce. Defaults to the centreline, so
     *   a caller with no lane data gets exactly the pre-Stage-4 marks.
     */
    fun marks(
        route: RouteIndex,
        spacingM: Double,
        lengthM: Double,
        halfWidthM: Double,
        limit: Int = 4_000,
        lateralM: (Double) -> Double = { 0.0 },
    ): List<List<LngLat>> {
        if (spacingM <= 0.0 || route.coords.size < 2) return emptyList()
        val total = route.totalM
        // Half a mark's length of clearance at each end, so a chevron is never
        // half-drawn off the start of the route or past the destination pin.
        val first = spacingM * 0.5
        val last = total - lengthM
        if (last <= first) return emptyList()
        val out = ArrayList<List<LngLat>>()
        var d = first
        while (d <= last && out.size < limit) {
            mark(route, d, lengthM, halfWidthM, lateralM)?.let { out.add(it) }
            d += spacingM
        }
        return out
    }

    /**
     * One chevron, with its apex [lengthM] further along than its wings.
     *
     * The bearing is taken from the route at the mark's own position rather
     * than from the segment endpoints, so a chevron on a curve points along the
     * curve instead of cutting the corner.
     */
    private fun mark(
        route: RouteIndex,
        atM: Double,
        lengthM: Double,
        halfWidthM: Double,
        lateralM: (Double) -> Double,
    ): List<LngLat>? {
        val backOn = route.pointAt(atM) ?: return null
        val tipOn = route.pointAt(atM + lengthM) ?: return null
        // A degenerate segment gives a meaningless bearing; skip the mark
        // rather than draw one pointing anywhere.
        if (abs(tipOn.position.lng - backOn.position.lng) < 1e-12 &&
            abs(tipOn.position.lat - backOn.position.lat) < 1e-12
        ) return null
        // Bearing from the CENTRELINE pair, before either end is moved sideways.
        //
        // Taking it from the offset pair instead would let the mark rotate
        // inside a taper — the two ends are 3 m apart and a lane change moves
        // them by different amounts, which on a 3 m chevron is several degrees
        // of yaw that has nothing to do with the road. A chevron points along
        // the route; the lane change moves it, it does not turn it.
        val brg = RouteGeometry.bearingDeg(backOn.position, tipOn.position)
        val back = RoutePoint(
            RouteGeometry.offsetPoint(backOn.position, brg, lateralM(atM)),
            backOn.bearing, backOn.segIdx, backOn.t,
        )
        val tip = RoutePoint(
            RouteGeometry.offsetPoint(tipOn.position, brg, lateralM(atM + lengthM)),
            tipOn.bearing, tipOn.segIdx, tipOn.t,
        )
        // Perpendicular, through the one lateral normal the app has: right of
        // travel is positive, the same sign the route ribbon is offset by and
        // the same sign MapLibre's `line-offset` uses. A wing is a lateral
        // offset like any other, so it goes through RouteGeometry.offsetPoint
        // rather than repeating the arithmetic on a sphere 0.11% larger than
        // the one the cumulative index is built on.
        val left = RouteGeometry.offsetPoint(back.position, brg, -halfWidthM)
        val right = RouteGeometry.offsetPoint(back.position, brg, halfWidthM)
        // The poles, where offsetPoint declines and a wing has no meaning.
        if (left === back.position) return null
        return listOf(left, tip.position, right)
    }
}
