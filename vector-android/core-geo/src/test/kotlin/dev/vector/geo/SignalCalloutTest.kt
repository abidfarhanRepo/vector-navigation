package dev.vector.geo

import dev.vector.geo.signal.Approach
import dev.vector.geo.signal.MatchedSignal
import dev.vector.geo.signal.SignalRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signal pill (V7 Stage 5) on the map.
 *
 * The pill is the LOCATION fact only — the map is drawn once per route, and a
 * phase claim is a per-tick prediction that has no business baked into route
 * geometry. What these tests pin:
 *
 *  * a matched signal produces one pill at its along-route position, titled
 *    "Signal ahead", with an identifiable source;
 *  * a signal at a drawn maneuver junction is SUPPRESSED (the turn pill
 *    already says there is a decision there), the same clearance rule bends
 *    use; the pill earns its place at the crossings the driver rolls
 *    straight through;
 *  * an empty signal list changes nothing — the pre-Stage-5 route exactly.
 */
class SignalCalloutTest {

    private val lat0 = 25.2854
    private val lng0 = 51.5310

    private fun line(n: Int, stepM: Double): RouteIndex {
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat0))
        return RouteGeometry.index((0 until n).map { i ->
            LngLat(lng0 + stepM * i / kx, lat0)
        })!!
    }

    private fun sig(id: String, alongM: Double) = MatchedSignal(
        ref = SignalRef(id, LngLat(lng0, lat0), "osm:node:$id"),
        approach = Approach(90.0, alongM),
    )

    @Test
    fun `a signal pill renders the location fact with a traceable source`() {
        val route = line(21, 100.0) // 2 km
        val out = Callouts.build(route, signals = listOf(sig("n500", 500.0)))
        assertEquals(1, out.size)
        val c = out.single()
        assertEquals(Callouts.Kind.SIGNAL, c.kind)
        assertEquals("Signal ahead", c.text)
        assertEquals("signal:n500@500m", c.source)
        assertEquals(500.0, c.alongM, 1.0)
    }

    @Test
    fun `a signal carries the traffic-light glyph`() {
        // The picture is the whole point of the icon: without it the driver has
        // to read the words to learn this is a signal, which is the thing both
        // reference products decided was too slow.
        val out = Callouts.build(line(21, 100.0), signals = listOf(sig("n500", 500.0)))
        assertEquals(Callouts.Icon.SIGNAL, out.single().icon)
    }

    @Test
    fun `empty signals leave the route exactly as before`() {
        val route = line(21, 100.0)
        val with = Callouts.build(route, signals = emptyList())
        val without = Callouts.build(route)
        assertEquals(without, with)
    }

    @Test
    fun `a signal at a drawn maneuver junction is the maneuver and is suppressed`() {
        val route = line(21, 100.0)
        val out = Callouts.build(
            route,
            junctions = listOf(Callouts.Junction(500.0, "turn-left")),
            signals = listOf(sig("n500", 510.0)), // 10 m from the turn
        )
        assertEquals(1, out.size)
        assertEquals(Callouts.Kind.JUNCTION, out.single().kind)
    }

    @Test
    fun `the signal survived at a straight-through crossing`() {
        val route = line(21, 100.0)
        val out = Callouts.build(
            route,
            junctions = listOf(Callouts.Junction(500.0, "turn-left")),
            signals = listOf(sig("n900", 900.0)), // 400 m clear of the turn
        )
        val kinds = out.map { it.kind }.toSet()
        assertTrue(Callouts.Kind.SIGNAL in kinds,
            "a signal clear of a maneuver must still be drawn: $kinds")
    }

    @Test
    fun `multiple signals each get their own pill in route order`() {
        val route = line(31, 100.0) // 3 km
        val out = Callouts.build(route, signals = listOf(
            sig("n1000", 1000.0),
            sig("n300", 300.0),
            sig("n2000", 2000.0),
        ))
        val signalPills = out.filter { it.kind == Callouts.Kind.SIGNAL }
        assertEquals(3, signalPills.size)
        assertEquals(listOf(300.0, 1000.0, 2000.0), signalPills.map { it.alongM }.map { it.toInt() }.map { it.toDouble() })
        assertTrue(signalPills.zipWithNext().all { it.first.alongM <= it.second.alongM })
    }

    @Test
    fun `a signal off the end of the route is not claimed`() {
        val route = line(21, 100.0) // 2 km
        val out = Callouts.build(route, signals = listOf(sig("n2500", 2500.0)))
        assertEquals(0, out.size)
    }
}