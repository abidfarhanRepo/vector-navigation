package dev.vector.android

import dev.vector.geo.signal.SignalMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signals[] parsing contract (V7 Stage 5), pinned at the wire.
 *
 * The three cases that must all be true:
 *
 *  1. an OLD backend answer (no `signals` key) parses to an empty list — the
 *     exact shape every old client saw, and the client must keep behaving
 *     exactly as it did;
 *  2. a NEW answer with projected entries parses id/lon/lat/along_m/bearing;
 *  3. an answer with a malformed entry drops that entry and keeps the rest —
 *     never crashes, never guesses (the `org.json` null trap discipline).
 */
class VectorApiSignalParseTest {

    private val api = VectorApi(base = "http://unused", token = "")

    private fun routeFeature(
        signals: String = "",
        steps: String = "[]",
    ): String = """
        {"type":"Feature",
         "geometry":{"type":"LineString","coordinates":[[51.53,25.285],[51.54,25.285]]},
         "properties":{
           "distance_km": 1.0, "duration_s": 60.0,
           "snap_max_m": 2.0, "snap":[
             {"requested":[51.53,25.285],"snapped":[51.53,25.285],"distance_m":0.0,"routable":true},
             {"requested":[51.54,25.285],"snapped":[51.54,25.285],"distance_m":0.0,"routable":true}],
           "steps": $steps,
           $signals
         }}
    """.trimIndent()

    @Test
    fun `an old answer without the signals key parses to an empty list`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature()))
        assertEquals(0, r.signals.size)
    }

    @Test
    fun `projected entries parse fully`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature(
            signals = """"signals":[{"id":"n123","lon":51.531,"lat":25.2851,
                 "along_m":100.5,"approach_bearing":90.0}]""",
        )))
        assertEquals(1, r.signals.size)
        val s = r.signals[0]
        assertEquals("n123", s.id)
        assertEquals(51.531, s.position.lng, 1e-6)
        assertEquals(25.2851, s.position.lat, 1e-6)
        assertEquals(100.5, s.alongM!!, 1e-6)
        assertEquals(90.0, s.approachBearingDeg!!, 1e-6)
    }

    @Test
    fun `a bare-point entry leaves along null for client projection`() {
        // A backend that projects nothing sends id/lon/lat only; the client
        // must be able to project the position itself for these.
        val r = api.parseRouteFeature(JSONObject(routeFeature(
            signals = """"signals":[{"id":"n7","lon":51.531,"lat":25.2851}]""",
        )))
        assertEquals(1, r.signals.size)
        assertNull(r.signals[0].alongM)
        assertNull(r.signals[0].approachBearingDeg)
    }

    @Test
    fun `a malformed entry is dropped rather than guessed`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature(
            signals = """"signals":[
                 {"id":"nOk","lon":51.531,"lat":25.2851,"along_m":10.0},
                 {"id":"nBad","lat":25.2851},
                 {"id":"nNoCoords"}]""",
        )))
        assertEquals(1, r.signals.size)
        assertEquals("nOk", r.signals[0].id)
    }

    @Test
    fun `alternatives carry each option's own signals`() {
        // navigateAlternatives parses per-feature, so the chosen option keeps
        // the signals of the route it actually is.
        val fc = """{"type":"FeatureCollection","features":[
            {"type":"Feature",
             "geometry":{"type":"LineString","coordinates":[[51.53,25.285],[51.54,25.285]]},
             "properties":{"distance_km":1.0,"duration_s":60.0,"snap_max_m":1.0,
                           "label":"via A","route_id":1,"primary":true,
                           "steps":[],"signals":[{"id":"n1","lon":51.531,"lat":25.2851,"along_m":50.0}]}},
            {"type":"Feature",
             "geometry":{"type":"LineString","coordinates":[[51.53,25.285],[51.54,25.285]]},
             "properties":{"distance_km":1.2,"duration_s":70.0,"snap_max_m":1.0,
                           "label":"via B","route_id":2,"primary":false,
                           "steps":[]}}
        ]}"""
        val options = api.parseRouteOptions(JSONObject(fc))
        assertEquals(2, options.size)
        assertEquals(1, options[0].signals.size)
        assertEquals(0, options[1].signals.size)
    }
}