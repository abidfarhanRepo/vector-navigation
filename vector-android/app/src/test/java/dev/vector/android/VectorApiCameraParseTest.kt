package dev.vector.android

import dev.vector.geo.camera.CameraType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cameras[] parsing contract (V7.3; typed V7.7), pinned at the wire.
 *
 * The cases that must all be true, mirroring the Stage 5 signal parse:
 *
 *  1. an OLD backend answer (no `cameras` key) parses to an empty list — the
 *     exact shape every old client saw, and the client must keep behaving
 *     exactly as it did;
 *  2. a NEW answer with projected entries parses id/lon/lat/along_m/bearing,
 *     the source-stated `type`, plus maxspeed/direction provenance;
 *  3. a wire type this build does not know reads as UNKNOWN, never as a
 *     speed camera: the backend cannot make the client claim a type, and the
 *     one direction this fails in is silence;
 *  4. an answer with a malformed entry drops that entry and keeps the rest —
 *     never crashes, never guesses (the `org.json` null trap discipline,
 *     including a JSON `null` id reading as absent rather than as the
 *     four-character word).
 */
class VectorApiCameraParseTest {

    private val api = VectorApi(base = "http://unused", token = "")

    private fun routeFeature(cameras: String = ""): String = """
        {"type":"Feature",
         "geometry":{"type":"LineString","coordinates":[[51.53,25.285],[51.54,25.285]]},
         "properties":{
           "distance_km": 1.0, "duration_s": 60.0,
           "snap_max_m": 2.0, "snap":[
             {"requested":[51.53,25.285],"snapped":[51.53,25.285],"distance_m":0.0,"routable":true},
             {"requested":[51.54,25.285],"snapped":[51.54,25.285],"distance_m":0.0,"routable":true}],
           "steps": [],
           $cameras
         }}
    """.trimIndent()

    @Test
    fun `an old answer without the cameras key parses to an empty list`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature()))
        assertEquals(0, r.cameras.size)
    }

    @Test
    fun `projected entries parse fully including type and provenance`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[{"id":"n105913857","lon":51.531,"lat":25.2851,
                 "along_m":100.5,"approach_bearing":90.0,"type":"speed",
                 "maxspeed":"80","direction":"270"}]""",
        )))
        assertEquals(1, r.cameras.size)
        val c = r.cameras[0]
        assertEquals("n105913857", c.id)
        assertEquals(51.531, c.position.lng, 1e-6)
        assertEquals(25.2851, c.position.lat, 1e-6)
        assertEquals(100.5, c.alongM!!, 1e-6)
        assertEquals(90.0, c.approachBearingDeg!!, 1e-6)
        assertEquals(CameraType.SPEED, c.type)
        assertEquals("80", c.maxspeed)
        assertEquals("270", c.direction)
    }

    @Test
    fun `every type the wire may carry is recognised`() {
        fun typeOf(raw: String) = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[{"id":"n1","lon":51.531,"lat":25.2851,"type":"$raw"}]""",
        ))).cameras[0].type
        assertEquals(CameraType.SPEED, typeOf("speed"))
        assertEquals(CameraType.AVERAGE_SPEED, typeOf("average_speed"))
        assertEquals(CameraType.RED_LIGHT, typeOf("red_light"))
        assertEquals(CameraType.COMBINED, typeOf("combined"))
        assertEquals(CameraType.UNKNOWN, typeOf("unknown"))
    }

    @Test
    fun `an absent or unrecognised type is unknown, never assumed to be a speed camera`() {
        // A V7.3-era backend: cameras, but no type key.
        val older = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[{"id":"n1","lon":51.531,"lat":25.2851,"along_m":10.0}]""",
        )))
        assertEquals(CameraType.UNKNOWN, older.cameras[0].type)
        // A backend with a word this build does not know.
        val future = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[{"id":"n1","lon":51.531,"lat":25.2851,"type":"average_speed_v2"}]""",
        )))
        assertEquals(CameraType.UNKNOWN, future.cameras[0].type)
        // And a JSON null is absent, not the word "null".
        val nulled = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[{"id":"n1","lon":51.531,"lat":25.2851,"type":null,
                 "maxspeed":null,"direction":null}]""",
        )))
        assertEquals(CameraType.UNKNOWN, nulled.cameras[0].type)
        assertNull(nulled.cameras[0].maxspeed)
        assertNull(nulled.cameras[0].direction)
    }

    @Test
    fun `a bare point without along_m parses for the client to project`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[{"id":"n1","lon":51.531,"lat":25.2851,"type":"speed"}]""",
        )))
        assertEquals(1, r.cameras.size)
        assertNull(r.cameras[0].alongM)
        assertNull(r.cameras[0].maxspeed)
        assertNull(r.cameras[0].direction)
        assertEquals(CameraType.SPEED, r.cameras[0].type)
    }

    @Test
    fun `a malformed entry is dropped and the rest survive`() {
        val r = api.parseRouteFeature(JSONObject(routeFeature(
            cameras = """"cameras":[
                 {"id":"nBad","lat":25.2851},
                 {"id":"nGood","lon":51.531,"lat":25.2851,"along_m":10.0,
                  "approach_bearing":90.0,"type":"speed"}]""",
        )))
        assertEquals(1, r.cameras.size)
        assertEquals("nGood", r.cameras[0].id)
    }
}
