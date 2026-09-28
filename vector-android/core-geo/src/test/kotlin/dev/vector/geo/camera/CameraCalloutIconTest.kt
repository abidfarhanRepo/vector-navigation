package dev.vector.geo.camera

import dev.vector.geo.Callouts
import dev.vector.geo.LngLat
import dev.vector.geo.RouteGeometry
import dev.vector.geo.RouteIndex
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which picture goes on a camera warning.
 *
 * The glyph is chosen from the camera's own type, so this is the one place that
 * decides whether a driver sees a road camera or a signal camera — and it is
 * chosen from what the SOURCE stated, never inferred. A camera whose source
 * states no type produces no callout at all (that refusal lives in
 * [Callouts.build] and is pinned by `CameraTextTest`), so nothing here can be
 * reached by a type-less device.
 *
 * Measured on Qatar's deployed catalogue on 2026-09-21: all 176 devices are
 * `highway=speed_camera`, and `enforcement=*` appears nowhere in the country's
 * OSM data, so [CameraType.RED_LIGHT] is exercised here as a wire case rather
 * than as a device standing on a road today.
 */
class CameraCalloutIconTest {

    private val lat0 = 25.2854
    private val lng0 = 51.5310

    private fun line(n: Int, stepM: Double): RouteIndex {
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat0))
        return RouteGeometry.index((0 until n).map { i -> LngLat(lng0 + stepM * i / kx, lat0) })!!
    }

    private fun cam(type: CameraType, alongM: Double = 500.0) = MatchedCamera(
        ref = CameraRef("c$alongM", LngLat(lng0, lat0), type),
        approach = Approach(90.0, alongM),
    )

    private fun iconFor(type: CameraType): String? =
        Callouts.build(line(21, 100.0), cameras = listOf(cam(type)))
            .single { it.kind == Callouts.Kind.CAMERA }
            .icon

    @Test
    fun `a fixed speed camera gets the camera glyph`() {
        assertEquals(Callouts.Icon.CAMERA_SPEED, iconFor(CameraType.SPEED))
    }

    @Test
    fun `a section-control camera is a camera, and its words say otherwise`() {
        // Both are a device on a post, which is the distinction a picture can
        // carry. The pill's text names the average-speed stretch, so the glyph
        // does not have to distinguish it.
        assertEquals(Callouts.Icon.CAMERA_SPEED, iconFor(CameraType.AVERAGE_SPEED))
        assertEquals(Callouts.Icon.CAMERA_SPEED, iconFor(CameraType.COMBINED))
    }

    @Test
    fun `a signal camera is drawn differently from a road camera`() {
        // What the driver acts on differs — one watches the light, the other
        // the road — so the two must not share a picture.
        assertEquals(Callouts.Icon.CAMERA_RED_LIGHT, iconFor(CameraType.RED_LIGHT))
        assertEquals(
            false,
            iconFor(CameraType.RED_LIGHT) == iconFor(CameraType.SPEED),
        )
    }

    @Test
    fun `a camera the source cannot type produces no warning at all`() {
        // Not "a warning with no picture": no callout. The refusal is what keeps
        // a location-only camera from being drawn as something it may not be.
        val out = Callouts.build(line(21, 100.0), cameras = listOf(cam(CameraType.UNKNOWN)))
        assertEquals(emptyList(), out.filter { it.kind == Callouts.Kind.CAMERA })
    }

    @Test
    fun `a junction and a bend carry no picture`() {
        // Their meaning is already in the words — "Left" is not a shape, and a
        // speed change is a number. An icon here would be decoration, and this
        // module's rule is that nothing is emitted without a source.
        val out = Callouts.build(
            line(21, 100.0),
            junctions = listOf(Callouts.Junction(600.0, "turn-left")),
            speed = Callouts.SpeedChange(700.0, 80),
        )
        assertNull(out.single { it.kind == Callouts.Kind.JUNCTION }.icon)
        assertNull(out.single { it.kind == Callouts.Kind.SPEED }.icon)
    }
}
