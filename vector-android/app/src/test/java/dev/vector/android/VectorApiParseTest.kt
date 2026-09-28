package dev.vector.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `org.json` null trap.
 *
 * `JSONObject.optString(key, "")` returns the four-character string **"null"**
 * when the value is JSON null — not the fallback. It is documented behaviour
 * and it is a trap every codebase using `org.json` falls into exactly once.
 *
 * Vector fell into it in V4 and it reached the S24. `/speed` answers
 * `{"ref": null}` for a road with no route number, so the road-you-are-on
 * readout rendered **"null · Ibn Katheer Street"**, caught in
 * `v4-evidence/vector/after-01-explore.png`.
 *
 * Worth pinning rather than just fixing, because four other fields were read
 * the same way — `turn_lanes`, `exit_ref`, `destination`, `road` — and each of
 * them would have put the word "null" somewhere a driver could read it. The
 * `destination` one in particular would have printed "Exit 12 null" on a
 * motorway sign badge.
 */
class VectorApiParseTest {

    private val api = VectorApi(base = "http://unused", token = "")

    private fun read(json: String, key: String): String? =
        with(api) { JSONObject(json).stringOrNull(key) }

    @Test
    fun `a JSON null is absent, not the word null`() {
        assertNull(read("""{"ref": null}""", "ref"))
    }

    @Test
    fun `no unit test could have caught this, and here is why`() {
        // The tests run against `org.json:json:20240303` from
        // `testImplementation`. The DEVICE runs Android's built-in `org.json`,
        // which shadows it. The two do not agree:
        //
        //   dependency jar :  optString("ref", "") on JSON null -> ""
        //   Android framework: optString("ref", "") on JSON null -> "null"
        //
        // So `optString(k, "").ifBlank { null }` is correct on the JVM and
        // wrong on a handset, and the defect was invisible to a green suite. It
        // was found by looking at a screenshot
        // (`v4-evidence/vector/after-01-explore.png`), which is the part of §30
        // that says not to rely solely on automated assertions.
        //
        // This test therefore pins the PROPERTY rather than either
        // implementation's behaviour: whatever `optString` does underneath,
        // `stringOrNull` must not hand the word "null" to the UI. It passes on
        // both, and it is the only form of this test that is worth having.
        val onJsonNull = read("""{"ref": null}""", "ref")
        assertNull("stringOrNull must treat JSON null as absent, got $onJsonNull", onJsonNull)
        // And the same for every other nullable field read from the wire —
        // `destination` would otherwise have printed "Exit 12 null" on a
        // motorway badge.
        for (key in listOf("turn_lanes", "exit_ref", "destination", "road", "name", "category")) {
            assertNull(key, read("""{"$key": null}""", key))
        }
    }

    @Test
    fun `a missing key is absent`() {
        assertNull(read("""{}""", "ref"))
    }

    @Test
    fun `an empty string is absent`() {
        // A road with `"ref": ""` has no route number either.
        assertNull(read("""{"ref": ""}""", "ref"))
    }

    @Test
    fun `whitespace only is absent`() {
        assertNull(read("""{"ref": "   "}""", "ref"))
    }

    @Test
    fun `a real value survives`() {
        assertEquals("C Ring", read("""{"ref": "C Ring"}""", "ref"))
    }

    // ---- V7 Stage 1: the lane contract fields -----------------------------

    @Test
    fun `an absent approach_lanes is null, never zero`() {
        // optInt's 0 default would read as a one-lane carriageway under the
        // vehicle and quietly narrow the ribbon.
        with(api) {
            assertNull(JSONObject("""{}""").intOrNull("approach_lanes"))
            assertNull(JSONObject("""{"approach_lanes": null}""").intOrNull("approach_lanes"))
        }
    }

    @Test
    fun `a real approach_lanes parses`() {
        with(api) {
            assertEquals(4, JSONObject("""{"approach_lanes": 4}""").intOrNull("approach_lanes"))
        }
    }

    @Test
    fun `a missing lane_data object is null, not an empty guess`() {
        with(api) {
            assertNull(JSONObject("""{}""").laneDataOrNull("lane_data"))
            assertNull(JSONObject("""{"lane_data": null}""").laneDataOrNull("lane_data"))
        }
    }

    @Test
    fun `lane_data parses defensively`() {
        with(api) {
            val none = JSONObject("""{"lane_data": {"source": "none", "direction": "unknown"}}""")
            val ld = none.laneDataOrNull("lane_data")
            assertEquals("none", ld?.source)
            assertEquals("unknown", ld?.direction)
            assertNull(ld?.turnLanes)
            assertNull(ld?.preferred)
            assertNull(ld?.forwardLanes)
        }
    }

    // ---- V7 Stage 4: the direction-specific lane count --------------------

    @Test
    fun `lane_data carries the forward lane count when the backend knows it`() {
        with(api) {
            val o = JSONObject("""{"lane_data": {
                "source": "none", "direction": "forward", "forward_lanes": 1
            }}""")
            // A two-way `lanes=2` residential street: one lane each way. The
            // whole point of the field — `approach_lanes` would say 2.
            assertEquals(1, o.laneDataOrNull("lane_data")?.forwardLanes)
        }
    }

    @Test
    fun `a null forward_lanes is unknown, not a lane count`() {
        with(api) {
            // Present-and-null is the backend saying "I cannot tell" — an odd
            // two-way lane count, or no `lanes` tag at all. Reading optInt's 0
            // here would collapse the route to no width; reading it as 1 would
            // put the route on a carriageway nobody established exists.
            val o = JSONObject("""{"lane_data": {
                "source": "none", "direction": "unknown", "forward_lanes": null
            }}""")
            assertNull(o.laneDataOrNull("lane_data")?.forwardLanes)
        }
    }

    @Test
    fun `a backend with no forward_lanes at all parses as unknown`() {
        with(api) {
            // Backward compatibility: a pre-Stage-4 backend omits the key
            // entirely, and that must read exactly as "unknown" rather than
            // throwing or defaulting.
            val o = JSONObject("""{"lane_data": {"source": "turn:lanes",
                "direction": "forward", "turn_lanes": "left|through"}}""")
            val ld = o.laneDataOrNull("lane_data")
            assertEquals("left|through", ld?.turnLanes)
            assertNull(ld?.forwardLanes)
        }
    }

    @Test
    fun `lane_data carries the resolved driver-order lanes and provenance`() {
        with(api) {
            val o = JSONObject("""{"lane_data": {
                "source": "turn:lanes", "direction": "backward",
                "turn_lanes": "through|left",
                "preferred": [0], "preferred_reason": "destination_lanes"
            }}""")
            val ld = o.laneDataOrNull("lane_data")
            assertEquals("turn:lanes", ld?.source)
            assertEquals("backward", ld?.direction)
            assertEquals("through|left", ld?.turnLanes)
            assertEquals(listOf(0), ld?.preferred)
            assertEquals("destination_lanes", ld?.preferredReason)
        }
    }

    private fun parseFeatures(json: String): List<VectorApi.Step> =
        with(api) {
            parseRouteFeature(JSONObject(json)).steps
        }

    @Test
    fun `a route step carries approach_lanes and lane_data`() {
        val steps = parseFeatures(
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[51.5,25.2],[51.6,25.3]]},
              "properties":{"steps":[{"type":"turn-left","instruction":"Turn left",
                "distance_m":250.0,"cumulative_distance_m":300.0,
                "approach_lanes":4,"lane_data":{"source":"turn:lanes","direction":"forward",
                "turn_lanes":"left|through|through"}}]}}"""
        )
        assertEquals(1, steps.size)
        assertEquals(4, steps[0].approachLanes)
        assertEquals("turn:lanes", steps[0].laneData?.source)
        assertEquals("forward", steps[0].laneData?.direction)
        assertEquals("left|through|through", steps[0].laneData?.turnLanes)
        assertNull(steps[0].laneData?.preferred)
    }

    @Test
    fun `a route step carries the forward lane count into the model`() {
        val steps = parseFeatures(
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[51.5,25.2],[51.6,25.3]]},
              "properties":{"steps":[{"type":"turn-left","instruction":"Turn left",
                "distance_m":250.0,"cumulative_distance_m":300.0,
                "approach_lanes":2,"lane_data":{"source":"none","direction":"forward",
                "forward_lanes":1}}]}}"""
        )
        // The two numbers disagree deliberately: 2 lanes on the way, 1 in the
        // direction driven. A client that confuses them draws the route into
        // oncoming traffic.
        assertEquals(2, steps[0].approachLanes)
        assertEquals(1, steps[0].laneData?.forwardLanes)
    }

    @Test
    fun `a step without the new fields parses exactly as before`() {
        // Compatibility: an older backend sends no lane_data/approach_lanes.
        val steps = parseFeatures(
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[51.5,25.2],[51.6,25.3]]},
              "properties":{"steps":[{"type":"turn-left","instruction":"Turn left",
                "distance_m":250.0,"cumulative_distance_m":300.0,
                "turn_lanes":"left|through"}]}}"""
        )
        assertEquals("left|through", steps[0].turnLanes)
        assertNull(steps[0].approachLanes)
        assertNull(steps[0].laneData)
    }

    @Test
    fun `the literal string null is kept, because it is a value`() {
        // Distinct from JSON null. Nothing in Qatar is called "null", but the
        // helper must not start censoring strings — that would be a different
        // and worse bug.
        assertEquals("null", read("""{"ref": "null"}""", "ref"))
    }

    // ---- the readout the defect was visible in -----------------------------

    @Test
    fun `the road readout omits a missing route number`() {
        assertEquals(
            "Ibn Katheer Street",
            UiState(roadName = "Ibn Katheer Street", roadRef = null).roadLabel,
        )
    }

    @Test
    fun `the road readout leads with the route number when there is one`() {
        // The ref first because on a gantry it is the larger of the two and the
        // thing a driver matches at speed.
        assertEquals(
            "C Ring · Al Corniche Street",
            UiState(roadName = "Al Corniche Street", roadRef = "C Ring").roadLabel,
        )
    }

    @Test
    fun `a route number with no name still says something`() {
        assertEquals("Q5", UiState(roadName = null, roadRef = "Q5").roadLabel)
    }

    @Test
    fun `no road at all reads as nothing rather than as an empty pill`() {
        // Off-road and in car parks the geocoder finds nothing within its 60 m
        // trust radius, which is the honest answer.
        assertNull(UiState().roadLabel)
        assertNull(UiState(roadName = "  ", roadRef = "").roadLabel)
    }
}

/**
 * Parsing a walk from `/foot`.
 *
 * Separate class because these are about a different failure than the `null`
 * trap above: the per-segment arrays the on-device shade model reads. Getting
 * their alignment wrong would not crash anything — it would quietly colour one
 * part of a walk with another part's road class, which is the kind of defect
 * that only ever shows up as "the shade looks wrong sometimes".
 */
class FootParseTest {

    private val api = VectorApi(base = "http://unused", token = "")

    private val full = """
    {
      "type": "Feature",
      "geometry": { "type": "LineString", "coordinates":
        [[51.5310,25.2850],[51.5310,25.2870],[51.5340,25.2870]] },
      "properties": {
        "profile": "foot",
        "distance_m": 512.4,
        "duration_s": 379.6,
        "steps_m": 10.9,
        "snap_max_m": 23.1,
        "classes": ["footway", "primary"],
        "enclosed": [false, false],
        "area": [false, true]
      }
    }
    """

    @Test
    fun `a walk parses into segments the shade model can read`() {
        val leg = api.parseFootFeature(JSONObject(full))
        assertEquals(2, leg.segments.size)
        assertEquals(512.4, leg.distanceM, 1e-9)
        assertEquals(379.6, leg.durationS, 1e-9)
        assertEquals(10.9, leg.stepsM, 1e-9)
        assertEquals(23.1, leg.snapMaxM, 1e-9)
    }

    @Test
    fun `each segment keeps its own road class and flags`() {
        val leg = api.parseFootFeature(JSONObject(full))
        assertEquals("footway", leg.segments[0].highway)
        assertEquals("primary", leg.segments[1].highway)
        assertEquals(false, leg.segments[0].area)
        assertEquals(true, leg.segments[1].area)
    }

    @Test
    fun `the segments follow the geometry, in order`() {
        val leg = api.parseFootFeature(JSONObject(full))
        assertEquals(51.5310, leg.segments[0].from.lng, 1e-9)
        assertEquals(25.2850, leg.segments[0].from.lat, 1e-9)
        assertEquals(51.5340, leg.segments[1].to.lng, 1e-9)
        // The reconstructed polyline is the geometry that came in.
        assertEquals(3, leg.geometry.size)
    }

    @Test
    fun `a backend with no per-segment arrays still produces a usable walk`() {
        // The deployed backend predates `classes`. The walk must still route,
        // draw and time; only the shade estimate degrades — and it degrades
        // toward claiming LESS shade, because an unknown class is never
        // credited with a facade.
        val old = """
        {
          "type": "Feature",
          "geometry": { "type": "LineString", "coordinates":
            [[51.5310,25.2850],[51.5310,25.2870]] },
          "properties": { "distance_m": 220.0, "duration_s": 163.0 }
        }
        """
        val leg = api.parseFootFeature(JSONObject(old))
        assertEquals(1, leg.segments.size)
        assertNull("no class must be invented", leg.segments[0].highway)
        assertEquals(220.0, leg.distanceM, 1e-9)

        // ...and the shade model declines rather than guessing. Checked at a
        // daylight instant, because at night everything reads as unexposed for
        // the unrelated reason that there is no sun.
        val noon = java.time.ZonedDateTime
            .of(2026, 9, 13, 14, 0, 0, 0, java.time.ZoneOffset.ofHours(3))
            .toInstant().toEpochMilli()
        val shade = dev.vector.geo.journey.ShadeAnnotator.shade(leg, noon)
        assertEquals(dev.vector.geo.sun.SunState.DAY, shade.sunState)
        assertEquals(1.0, shade.exposure, 1e-9)
    }

    @Test
    fun `a walk with no geometry is empty rather than malformed`() {
        val leg = api.parseFootFeature(JSONObject("""{"properties":{"distance_m":0}}"""))
        assertEquals(0, leg.segments.size)
        assertEquals(emptyList<dev.vector.geo.LngLat>(), leg.geometry)
    }

    @Test
    fun `a classes array shorter than the geometry does not throw`() {
        // Defensive: a truncated array must degrade to "unknown class" for the
        // tail rather than taking the whole walk down.
        val ragged = """
        {
          "geometry": { "type": "LineString", "coordinates":
            [[51.531,25.285],[51.531,25.287],[51.534,25.287]] },
          "properties": { "classes": ["footway"] }
        }
        """
        val leg = api.parseFootFeature(JSONObject(ragged))
        assertEquals(2, leg.segments.size)
        assertEquals("footway", leg.segments[0].highway)
        assertNull(leg.segments[1].highway)
    }
}
