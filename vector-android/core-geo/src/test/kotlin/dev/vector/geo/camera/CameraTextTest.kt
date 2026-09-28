package dev.vector.geo.camera

import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.Units
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The camera vocabulary (V7.3; typed V7.7).
 *
 * Two rules decide every camera string, and this file walks the whole closed
 * vocabulary to pin both:
 *
 *  1. a warning claims WHERE a camera is and WHAT THE SOURCE SAYS IT IS —
 *     never what it does. No string may say "active", "fines", "enforces" or
 *     "flashes";
 *  2. a camera whose source states no type gets NO sentence at all. Silence is
 *     the honest output for [CameraType.UNKNOWN], never the word "speed".
 *
 * The distance is deliberately not here: it is a number, and the one place
 * numbers are formatted for a driver is [Units] (see UnitsTest).
 */
class CameraTextTest {

    private val vocabulary = CameraType.entries
        .mapNotNull { CameraText.line(it) }

    @Test
    fun `no camera string ever claims activity enforcement or a fine`() {
        val banned = listOf("active", "activ", "fined", "fine", "enforc", "flash", "caught")
        assertTrue(vocabulary.isNotEmpty())
        for (word in vocabulary) {
            val lower = word.lowercase()
            for (b in banned) {
                assertTrue(b !in lower, "vocabulary '$word' must never say '$b'")
            }
        }
    }

    @Test
    fun `an unknown camera gets no sentence at all`() {
        // The whole point of the type gate: no source type, no claim.
        assertNull(CameraText.line(CameraType.UNKNOWN))
        // ... and it is the ONLY type that is silent.
        assertEquals(1, CameraType.entries.count { CameraText.line(it) == null })
        assertTrue(CameraType.entries.filter { it != CameraType.UNKNOWN }.all { it.announces })
        assertTrue(!CameraType.UNKNOWN.announces)
    }

    @Test
    fun `each known type says which kind of camera it is`() {
        // Types are not collapsed into one another: a driver told there is a
        // red-light camera has been told something a "speed camera ahead"
        // would not have said.
        assertEquals("Speed camera ahead", CameraText.line(CameraType.SPEED))
        assertEquals("Average-speed camera ahead", CameraText.line(CameraType.AVERAGE_SPEED))
        assertEquals("Red-light camera ahead", CameraText.line(CameraType.RED_LIGHT))
        assertEquals("Speed and red-light camera ahead", CameraText.line(CameraType.COMBINED))
        assertEquals(vocabulary.size, vocabulary.toSet().size,
                     "two camera types share one sentence")
        assertNotEquals(CameraText.line(CameraType.SPEED),
                        CameraText.line(CameraType.AVERAGE_SPEED))
    }

    @Test
    fun `a wire type this build does not know is unknown, not a guess`() {
        assertEquals(CameraType.SPEED, CameraType.fromWire("speed"))
        assertEquals(CameraType.AVERAGE_SPEED, CameraType.fromWire("average_speed"))
        assertEquals(CameraType.RED_LIGHT, CameraType.fromWire("red_light"))
        assertEquals(CameraType.COMBINED, CameraType.fromWire("combined"))
        // Everything else — a missing key, a blank, a future backend's word.
        assertEquals(CameraType.UNKNOWN, CameraType.fromWire(null))
        assertEquals(CameraType.UNKNOWN, CameraType.fromWire(""))
        assertEquals(CameraType.UNKNOWN, CameraType.fromWire("mobile_phone"))
        assertEquals(CameraType.UNKNOWN, CameraType.fromWire("SPEED"))
    }

    @Test
    fun `camera callouts use the type's sentence and a traceable source`() {
        val route = RouteGeometry.index(
            listOf(LngLat(51.5300, 25.2850), LngLat(51.5400, 25.2850)),
        )!!
        val cam = MatchedCamera(
            ref = CameraRef("n105913857", LngLat(51.5310, 25.285001),
                            CameraType.SPEED, maxspeedTag = "80"),
            approach = Approach(90.0, 100.0),
        )
        val callouts = dev.vector.geo.Callouts.build(route, cameras = listOf(cam))
        assertEquals(1, callouts.size)
        assertEquals(dev.vector.geo.Callouts.Kind.CAMERA, callouts[0].kind)
        assertEquals(CameraText.line(CameraType.SPEED), callouts[0].text)
        assertEquals("camera:n105913857@100m", callouts[0].source)
        assertEquals(100.0, callouts[0].alongM, 1.0)
        // A camera at a drawn maneuver callout is suppressed in favour of the
        // maneuver, the same doctrine as signals.
        val junction = dev.vector.geo.Callouts.Junction(
            cumulativeM = 105.0, type = "turn-left", exitRef = null, road = "Test St",
        )
        val withJunction = dev.vector.geo.Callouts.build(
            route, junctions = listOf(junction), cameras = listOf(cam),
        )
        assertEquals(1, withJunction.size)
        assertEquals(dev.vector.geo.Callouts.Kind.JUNCTION, withJunction[0].kind)
    }

    @Test
    fun `each type reaches the map as its own sentence`() {
        val route = RouteGeometry.index(
            listOf(LngLat(51.5300, 25.2850), LngLat(51.5400, 25.2850)),
        )!!
        // Three distinct types on one route, far enough apart not to collide:
        // the pill text differs per camera, so the map never says "speed
        // camera" about a device the source called something else.
        val cams = listOf(
            MatchedCamera(CameraRef("n1", LngLat(51.5310, 25.285001), CameraType.SPEED),
                          Approach(90.0, 100.0)),
            MatchedCamera(CameraRef("n2", LngLat(51.5350, 25.285001), CameraType.AVERAGE_SPEED),
                          Approach(90.0, 500.0)),
            MatchedCamera(CameraRef("n3", LngLat(51.5390, 25.285001), CameraType.RED_LIGHT),
                          Approach(90.0, 900.0)),
        )
        val byId = dev.vector.geo.Callouts.build(route, cameras = cams)
            .associateBy { it.source.substringAfter("camera:").substringBefore("@") }
        assertEquals(CameraText.line(CameraType.SPEED), byId.getValue("n1").text)
        assertEquals(CameraText.line(CameraType.AVERAGE_SPEED), byId.getValue("n2").text)
        assertEquals(CameraText.line(CameraType.RED_LIGHT), byId.getValue("n3").text)
    }
}
