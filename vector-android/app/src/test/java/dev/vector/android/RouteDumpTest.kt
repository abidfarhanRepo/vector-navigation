package dev.vector.android

import org.junit.Test
import java.io.File

/**
 * Writes a real route — geometry, lateral profile, chevrons — as the GeoJSON the
 * app would hand MapLibre, so the cartography bench can draw it.
 *
 * The point is to SEE a lateral change rather than only assert its number: the
 * turn placement is a 1.75 m shift on a real road, and what a driver reads is
 * the ribbon ramping into the lane, which no unit test can show.
 *
 * Inert unless `VECTOR_ROUTE_DUMP` is set, like [StyleDumpTest].
 */
class RouteDumpTest {

    @Test
    fun `dump a captured route's ribbon for the bench`() {
        val dir = System.getenv("VECTOR_ROUTE_DUMP") ?: return
        val name = System.getenv("VECTOR_ROUTE_NAME") ?: "two-way-rabia"
        val out = File(dir)
        out.mkdirs()

        val plan = DriveHarness.load(name, routeId = 0)
        val lanes = dev.vector.geo.RouteLanes.plan(
            plan.maneuvers.map {
                dev.vector.geo.RouteLanes.Approach(
                    atM = it.cumulativeM,
                    forwardLanes = it.forwardLanes,
                    totalLanes = it.approachLanes,
                    lanes = it.lanes,
                    turn = dev.vector.geo.RouteLanes.Turn.of(it.type),
                )
            }
        )
        val route = dev.vector.geo.RouteGeometry.index(plan.geometry)!!
        File(out, "route-$name.json").writeText(VectorStyle.routeGeoJson(route, lanes))
        File(out, "route-$name-chevrons.json").writeText(
            VectorStyle.chevronGeoJson(route, lanes),
        )
        // The maneuvers, so the bench can mark where the profile was told to
        // move — the turn this change is about is one of these points.
        File(out, "route-$name-maneuvers.json").writeText(
            """{"type":"FeatureCollection","features":[""" +
                plan.maneuvers.joinToString(",") { m ->
                    val p = route.pointAt(m.cumulativeM)!!.position
                    """{"type":"Feature","properties":{"type":"${m.type}",""" +
                        """"along":${m.cumulativeM},"turn":""" +
                        """"${dev.vector.geo.RouteLanes.Turn.of(m.type)}"},""" +
                        """"geometry":{"type":"Point","coordinates":""" +
                        """[${p.lng},${p.lat}]}}"""
                } + "]}",
        )
    }
}
