package dev.vector.geo.walk

import dev.vector.geo.Callouts
import dev.vector.geo.LngLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placing a walk's surveyed signals on the map (V7 traffic lights).
 *
 * ## The rules under test
 *
 * 1. **Placement is the backend's index, not a projection.** A signal attached
 *    to a crossing sits on a vertex of the walk geometry, and the wire says
 *    which. So the marker lands on that coordinate exactly — there is no snap
 *    gate to widen and nothing to get wrong when two junctions are 30 m apart.
 *
 * 2. **A signal that is not on this walk is not drawn.** No proximity: a signal
 *    20 m off the route is a different crossing.
 *
 * 3. **The marker is a location and can be nothing else.** The pill's words come
 *    from [dev.vector.geo.signal.SignalText.LOCATION], and the callout's
 *    `kind` is `SIGNAL` — the driving map's own vocabulary for this exact
 *    claim, so the walking map does not invent a second one.
 */
class WalkSignalsTest {

    // ---------------------------------------------------------------- helpers

    /**
     * A walk east along a pavement, then across a 15 m crossing, then on.
     *
     * Vertices in metres east/north of an origin at 25.286 N: v0 at 0 m, v1 at
     * 40 m (the near kerb), v2 at 40 m east and 15 m north (the far kerb), v3 at
     * 80 m east on the far pavement. So the crossing is 15 m — the p50 Qatari
     * crossing is 13.6 m — and the along-route position of v2 is ~55 m.
     */
    private fun geometry(crossNorthM: Double = 15.0): List<LngLat> {
        val lat0 = 25.286
        val mLon = 111_320.0 * Math.cos(Math.toRadians(lat0))
        fun p(east: Double, north: Double) =
            LngLat(51.53 + east / mLon, lat0 + north / 111_320.0)
        return listOf(p(0.0, 0.0), p(40.0, 0.0), p(40.0, crossNorthM), p(80.0, crossNorthM))
    }

    private fun signal(id: String, index: Int?) = WalkSignal(
        id = id, source = "osm:node:${id.removePrefix("n")}",
        node = "51.5300000,25.2860000", index = index,
    )

    private fun crossing(signals: List<WalkSignal>, type: String? = null) = WalkCrossing(
        type = type, typeSource = if (type != null) "way" else null,
        markings = null, kerb = null, tactilePaving = null,
        distanceM = 15.0, distanceToCrossingM = 0.0,
        approachIndex = 1, approachDistanceM = 40.0, enterIndex = 1, leaveIndex = 2,
        road = null, crossedRoadSource = null, signals = signals,
    )

    private fun route(
        signals: List<WalkSignal>,
        type: String? = null,
        planExtra: List<WalkManeuver> = emptyList(),
    ): WalkRoute {
        val geom = geometry()
        val plan = buildList {
            add(
                WalkManeuver(
                    kind = WalkManeuverKind.DEPART, index = 0, distanceM = 0.0,
                    distanceToNextM = 40.0, road = null,
                )
            )
            if (signals.isNotEmpty() || type != null) {
                add(
                    WalkManeuver(
                        kind = WalkManeuverKind.CROSS, index = 1, distanceM = 40.0,
                        distanceToNextM = 45.0, road = null,
                        crossing = crossing(signals, type),
                    )
                )
            }
            addAll(planExtra)
            add(
                WalkManeuver(
                    kind = WalkManeuverKind.ARRIVE, index = 3, distanceM = 120.0,
                    distanceToNextM = null, road = null,
                )
            )
        }
        return WalkRoute.of(
            WalkContract(
                contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general", mode = "foot",
                distanceM = 120.0, durationS = 89.0, stepsM = 0.0, crossingM = 15.0,
                nodes = geom.size, geometry = geom,
                segments = WalkSegments(
                    classes = listOf("footway", "footway", "footway"),
                    footway = listOf("sidewalk", "crossing", "sidewalk"),
                    crossing = listOf(null, null, null),
                    lit = listOf(null, null, null),
                    enclosed = listOf(false, false, false),
                    area = listOf(false, false, false),
                    costS = emptyList(),
                ),
                facts = emptyList(), plan = plan, cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )
    }

    // ------------------------------------------------------------ placement

    @Test
    fun `a surveyed signal is placed at the vertex the backend named`() {
        val r = route(listOf(signal("n1001", index = 2)))
        val profile = WalkSignals.profile(r)
        assertEquals(1, profile.size)
        val placed = profile.at(0)
        assertEquals("n1001", placed.ref.id)
        assertEquals("osm:node:1001", placed.ref.source)
        // The vertex itself, not a projection: 55 m along is v2 by construction.
        assertEquals(55.0, placed.approach.alongM, 0.5)
        assertEquals(r.index!!.coords[2].lng, placed.ref.position.lng, 1e-12)
        assertEquals(r.index!!.coords[2].lat, placed.ref.position.lat, 1e-12)
    }

    @Test
    fun `a walk with no signal on its crossing draws nothing`() {
        val r = route(signals = emptyList(), type = "zebra")
        assertTrue(WalkSignals.profile(r).isEmpty)
        assertTrue(WalkSignals.callouts(r).isEmpty())
        assertTrue(WalkSignals.positions(r).isEmpty())
    }

    @Test
    fun `an unknown or out-of-range vertex places no marker`() {
        // The backend omitted the index (an older payload), and the coordinate
        // it named cannot exist. Both are nothing to draw, and neither is
        // guessed at from the crossing's other positions.
        val r = route(listOf(signal("n1001", index = null), signal("n2002", index = 99)))
        assertTrue(WalkSignals.profile(r).isEmpty)
        assertTrue(WalkSignals.positions(r).isEmpty())
    }

    @Test
    fun `several signals on one crossing all appear, in route order`() {
        // The near kerb and the far kerb of a 15 m crossing: two surveyed
        // signal nodes, two markers. Measured on the real Qatar bake this is
        // the normal shape — crossings carry one node per direction, ~16 m
        // apart (n1995429197 / n282991885 on the one this stage's evidence
        // walks over).
        val r = route(listOf(signal("n1001", index = 2), signal("n2002", index = 1)))
        val profile = WalkSignals.profile(r)
        assertEquals(listOf("n2002", "n1001"), profile.signals.map { it.ref.id })
        assertTrue(profile.at(0).approach.alongM < profile.at(1).approach.alongM)
    }

    @Test
    fun `two signal nodes under 10 m apart are one marker`() {
        // `SignalMatcher`'s existing merge doctrine, inherited rather than
        // re-implemented: OSM sometimes maps one physical set of lights as two
        // nodes, and two markers that overlap on screen are one marker with
        // twice the draw cost. 10 m is the driving map's threshold and it is
        // the right one on foot too — two signals a walker that close to each
        // other are looking at one set of lights.
        //
        // Pinned because it is behaviour a reader would otherwise have to
        // INFER from a missing marker, which is the wrong way to learn it.
        val geom = geometry(crossNorthM = 5.0)
        val plan = listOf(
            WalkManeuver(
                kind = WalkManeuverKind.CROSS, index = 1, distanceM = 40.0,
                distanceToNextM = null, road = null,
                crossing = crossing(listOf(signal("n1001", index = 2), signal("n2002", index = 1))),
            )
        )
        val r = WalkRoute.of(
            WalkContract(
                contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general", mode = "foot",
                distanceM = 85.0, durationS = 63.0, stepsM = 0.0, crossingM = 5.0,
                nodes = geom.size, geometry = geom,
                segments = WalkSegments(),
                facts = emptyList(), plan = plan, cost = null,
                diagnostics = WalkDiagnostics(),
            )
        )
        assertEquals(1, WalkSignals.profile(r).size)
    }

    @Test
    fun `a signal on a crossing the walk does not cross is absent`() {
        // The plan simply has no such crossing; the profile reads the plan, so
        // there is no route by which an unrelated signal could enter it.
        val r = route(signals = emptyList(), type = null)
        assertTrue(WalkSignals.profile(r).isEmpty)
    }

    // ----------------------------------------------------------- presentation

    @Test
    fun `the marker is a SIGNAL callout carrying the location fact`() {
        val r = route(listOf(signal("n1001", index = 2)))
        val callouts = WalkSignals.callouts(r)
        assertEquals(1, callouts.size)
        val c = callouts.single()
        assertEquals(Callouts.Kind.SIGNAL, c.kind)
        assertEquals(dev.vector.geo.signal.SignalText.LOCATION, c.text)
        // Traceable to the catalog entry, the same id shape the driving map uses.
        assertTrue(c.source.startsWith("signal:n1001@"))
    }

    @Test
    fun `no marker describes a state, a colour or a duration`() {
        // Walked over the whole vocabulary the marker can produce: one string.
        // A green/red marker or a countdown cannot be drawn from this type,
        // because there is no field and no other string.
        val r = route(listOf(signal("n1001", index = 2)))
        val text = WalkSignals.callouts(r).single().text.lowercase()
        for (word in listOf("green", "red", "amber", "phase", "cycle",
                            "countdown", "second", "wait")) {
            assertFalse(text.contains(word), "the marker said \"$word\": $text")
        }
        assertEquals("signal ahead", text)
    }

    @Test
    fun `an ordinary crossing's marker set is unchanged by the crossing type`() {
        // The map is driven by the SIGNAL list alone. A zebra crossing with a
        // signal on it draws the signal; one without draws nothing. The type
        // label is the banner's business, not the map's.
        assertTrue(WalkSignals.callouts(route(signals = emptyList(), type = "zebra")).isEmpty())
        assertEquals(
            1,
            WalkSignals.callouts(route(listOf(signal("n1001", index = 2)), type = "zebra")).size,
        )
    }

    @Test
    fun `a curving walk draws signal pills and no driving bend pills`() {
        // The defect this pins: `Callouts.build` derives bend warnings from
        // route CURVATURE for driving, and called unguarded from the walking
        // path it put "Sharp bend" pills on a walking map — observed on the
        // emulator as `walk signals 3` for a route with TWO signals. Curvature
        // at 1.35 m/s is the shape of the pavement, and the pill carried
        // driving words for a situation walking has no word for.
        //
        // This geometry turns ~90 degrees mid-route, which is exactly what the
        // driving bend detector looks for.
        val lat0 = 25.286
        val mLon = 111_320.0 * Math.cos(Math.toRadians(lat0))
        fun p(east: Double, north: Double) =
            LngLat(51.53 + east / mLon, lat0 + north / 111_320.0)
        val geom = listOf(p(0.0, 0.0), p(120.0, 0.0), p(120.0, 120.0))
        val r = WalkRoute.of(
            WalkContract(
                contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general", mode = "foot",
                distanceM = 240.0, durationS = 178.0, stepsM = 0.0, crossingM = 15.0,
                nodes = geom.size, geometry = geom,
                segments = WalkSegments(),
                facts = emptyList(),
                plan = listOf(
                    WalkManeuver(
                        kind = WalkManeuverKind.CROSS, index = 1, distanceM = 120.0,
                        distanceToNextM = null, road = null,
                        crossing = crossing(listOf(signal("n1001", index = 1))),
                    )
                ),
                cost = null, diagnostics = WalkDiagnostics(),
            )
        )
        val callouts = WalkSignals.callouts(r)
        assertEquals(1, callouts.size)
        assertEquals(Callouts.Kind.SIGNAL, callouts.single().kind)
        assertTrue(callouts.none { it.kind == Callouts.Kind.BEND })
    }

    @Test
    fun `a degenerate walk has nothing to place`() {
        val geom = listOf(LngLat(51.53, 25.286))
        val r = WalkRoute.of(
            WalkContract(
                contractVersion = 1, walkingProfile = WalkingProfile.GENERAL,
                walkingProfileRaw = "general", mode = "foot",
                distanceM = 0.0, durationS = 0.0, stepsM = 0.0, crossingM = 0.0,
                nodes = 1, geometry = geom,
                segments = WalkSegments(),
                facts = emptyList(),
                plan = listOf(
                    WalkManeuver(
                        kind = WalkManeuverKind.CROSS, index = 0, distanceM = 0.0,
                        distanceToNextM = null, road = null,
                        crossing = crossing(listOf(signal("n1001", index = 0))),
                    )
                ),
                cost = null, diagnostics = WalkDiagnostics(),
            )
        )
        assertNull(r.index)
        assertTrue(WalkSignals.profile(r).isEmpty)
        assertTrue(WalkSignals.positions(r).isEmpty())
    }
}
