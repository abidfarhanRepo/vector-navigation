package dev.vector.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Does every warning on the road have something behind it?
 *
 * A pill drawn in the world reads as a fact about the place it is stuck to, and
 * that is a much stronger claim than a line on a map. V8 §8 asks for exactly
 * one new test here — "every callout carries the tag or geometry it came from;
 * none is emitted without a source" — and it is the first one below, applied to
 * everything the module can produce rather than to a chosen example.
 *
 * The rest are about the two ways a geometric warning goes wrong: warning about
 * a bend the router already called a turn, and warning about a bend that is not
 * one.
 */
class CalloutsTest {

    private val lat0 = 25.2854
    private val lng0 = 51.5310

    private fun m(a: LngLat, b: LngLat) = RouteGeometry.haversineM(a.lng, a.lat, b.lng, b.lat)

    /** A straight run [n] points long, [stepM] apart, heading [brgDeg]. */
    private fun line(n: Int, stepM: Double, brgDeg: Double): RouteIndex {
        val b = Math.toRadians(brgDeg)
        return RouteGeometry.index((0 until n).map { i ->
            val d = stepM * i
            LngLat(
                lng0 + d * Math.sin(b) / (111_320.0 * Math.cos(Math.toRadians(lat0))),
                lat0 + d * Math.cos(b) / 111_320.0,
            )
        })!!
    }

    /** A straight run, then an arc of [sweepDeg] at [radiusM], then straight again. */
    private fun withBend(radiusM: Double, sweepDeg: Int, runInM: Double = 300.0): RouteIndex {
        val pts = ArrayList<LngLat>()
        fun add(x: Double, y: Double) = pts.add(
            LngLat(lng0 + x / (111_320.0 * Math.cos(Math.toRadians(lat0))), lat0 + y / 111_320.0)
        )
        var i = 0.0
        while (i <= runInM) { add(0.0, i); i += 10.0 }
        for (deg in 0..sweepDeg step 2) {
            val a = Math.toRadians(deg.toDouble())
            add(radiusM * (1 - Math.cos(a)), runInM + radiusM * Math.sin(a))
        }
        val a = Math.toRadians(sweepDeg.toDouble())
        val ex = radiusM * (1 - Math.cos(a))
        val ey = runInM + radiusM * Math.sin(a)
        var k = 10.0
        while (k <= 300.0) { add(ex + k * Math.sin(a), ey + k * Math.cos(a)); k += 10.0 }
        return RouteGeometry.index(pts)!!
    }

    // ------------------------------------------------------------- provenance

    @Test
    fun `every callout carries the thing it came from`() {
        // The V8 §8 test, applied to every kind the module can emit rather than
        // to one example of one of them.
        val route = withBend(45.0, 90)
        val js = listOf(
            Callouts.Junction(120.0, "turn-left"),
            Callouts.Junction(200.0, "off-ramp", exitRef = "Q3"),
            Callouts.Junction(260.0, "roundabout"),
        )
        val all = Callouts.build(route, js, Callouts.SpeedChange(150.0, 80))
        assertTrue(all.isNotEmpty(), "no callouts at all, so this proves nothing")
        val kinds = all.map { it.kind }.toSet()
        assertTrue(Callouts.Kind.JUNCTION in kinds, "no junction callout to check")
        assertTrue(Callouts.Kind.BEND in kinds, "no bend callout to check")
        assertTrue(Callouts.Kind.SPEED in kinds, "no speed callout to check")
        for (c in all) {
            assertTrue(c.source.isNotBlank(), "${c.kind} '${c.text}' has no source")
            assertTrue(c.text.isNotBlank(), "${c.kind} has a source but nothing to say")
            // The source has to identify the thing, not merely name a category.
            assertTrue(
                c.source.contains(':'),
                "${c.kind} source '${c.source}' names a category, not a fact",
            )
        }
    }

    @Test
    fun `every maneuver type the router emits is either labelled or refused on purpose`() {
        // The vocabulary is `ManeuverAnnouncer.shortAction`'s — the two read the
        // same `Step.type` off the same response, and a type one of them knows
        // and the other does not is a pill that silently never appears. Which
        // is exactly the failure mode this file exists to make loud: an
        // unrecognised type produces NO callout rather than a guessed word, so
        // the only way to notice the gap is to enumerate the vocabulary.
        val route = line(120, 20.0, 40.0)
        val labelled = listOf(
            "turn-left", "turn-right", "slight-left", "slight-right",
            "sharp-left", "sharp-right", "uturn", "roundabout",
        )
        val refused = listOf("depart", "arrive", "continue")
        for (t in labelled) {
            val out = Callouts.build(route, listOf(Callouts.Junction(800.0, t)))
            assertEquals(1, out.count { it.kind == Callouts.Kind.JUNCTION },
                         "`$t` is a maneuver the announcer speaks and no pill is drawn for")
            assertTrue(out[0].text.isNotBlank(), "`$t` produced an empty pill")
        }
        for (t in refused) {
            assertEquals(0,
                Callouts.build(route, listOf(Callouts.Junction(800.0, t)))
                    .count { it.kind == Callouts.Kind.JUNCTION },
                "`$t` is not a decision and should carry no pill")
        }
    }

    @Test
    fun `a maneuver the router did not describe produces no words at all`() {
        // An unknown maneuver type is not a reason to invent a word for it. The
        // callout is dropped rather than labelled "Turn" on the guess that most
        // maneuvers are turns.
        val route = line(60, 20.0, 0.0)
        val out = Callouts.build(route, listOf(Callouts.Junction(400.0, "wormhole")))
        assertEquals(0, out.count { it.kind == Callouts.Kind.JUNCTION },
                     "an unrecognised maneuver was given a label anyway")
    }

    // -------------------------------------------------------------- junctions

    @Test
    fun `the exit number wins, because it is what is on the gantry`() {
        val route = line(80, 20.0, 30.0)
        val out = Callouts.build(route, listOf(
            Callouts.Junction(500.0, "off-ramp", exitRef = "Q3;Q5", road = "Al Rayyan Road"),
        ))
        assertEquals(1, out.size)
        assertEquals("Exit Q3;Q5", out[0].text)
    }

    @Test
    fun `where the driver already is and where they are going get no pill`() {
        // `depart` is under the wheels and `arrive` already has a pin on it.
        val route = line(80, 20.0, 0.0)
        val out = Callouts.build(route, listOf(
            Callouts.Junction(0.0, "depart"),
            Callouts.Junction(500.0, "continue"),
            Callouts.Junction(700.0, "new-name"),
            Callouts.Junction(route.totalM, "arrive"),
        ))
        assertEquals(0, out.count { it.kind == Callouts.Kind.JUNCTION },
                     "a pill was hung on something the driver does not have to do")
    }

    @Test
    fun `a pill hangs on the side away from the turn`() {
        // So it is never over the road the driver is about to be on.
        val route = line(80, 20.0, 0.0)   // due north
        val right = Callouts.build(route, listOf(Callouts.Junction(500.0, "turn-right")))[0]
        val left = Callouts.build(route, listOf(Callouts.Junction(500.0, "turn-left")))[0]
        // Heading north: west is a smaller longitude, east a larger one.
        assertTrue(right.label.lng < right.anchor.lng,
                   "a right turn's pill sits over the road being turned onto")
        assertTrue(left.label.lng > left.anchor.lng,
                   "a left turn's pill sits over the road being turned onto")
    }

    @Test
    fun `the pill floats clear of the road and the leader reaches it`() {
        val route = line(80, 20.0, 115.0)
        for (c in Callouts.build(route, listOf(Callouts.Junction(500.0, "keep-left")))) {
            assertEquals(Callouts.LEADER_M, m(c.anchor, c.label), 1.0,
                         "the pill is not where the leader would put it")
            // And the anchor really is on the route, not near it.
            assertTrue(route.project(c.anchor)!!.offsetM < 0.5,
                       "the anchor floats off the road it is describing")
        }
    }

    @Test
    fun `a maneuver outside the route is not placed somewhere arbitrary`() {
        val route = line(40, 20.0, 0.0)
        val out = Callouts.build(route, listOf(
            Callouts.Junction(-50.0, "turn-left"),
            Callouts.Junction(route.totalM + 400.0, "turn-right"),
        ))
        assertEquals(0, out.size, "a maneuver off the end of the route was still drawn")
    }

    // ------------------------------------------------------------------ bends

    @Test
    fun `a slip road tight enough to surprise you is called out`() {
        val route = withBend(radiusM = 40.0, sweepDeg = 90)
        val bends = Callouts.build(route).filter { it.kind == Callouts.Kind.BEND }
        assertEquals(1, bends.size, "a 40 m radius 90-degree bend produced ${bends.size} pills")
        assertEquals("Sharp bend", bends[0].text)
        assertTrue(bends[0].source.startsWith("curvature:"),
                   "a bend's source does not say what was measured")
    }

    @Test
    fun `an arterial curve is not a warning`() {
        // Lateral acceleration is v^2/r, so the radius is the number that
        // matters. 300 m at 60 km/h is 0.9 m/s^2 and 120 m is 2.3 — an ordinary
        // ring-road sweep and an ordinary slip road. A product that warns about
        // either is one a driver learns to ignore, and then the warning has
        // spent attention and taught them to spend none.
        for (r in listOf(120.0, 300.0, 600.0)) {
            val route = withBend(radiusM = r, sweepDeg = 90)
            assertEquals(0, Callouts.build(route).count { it.kind == Callouts.Kind.BEND },
                         "a ${r.toInt()} m radius curve was called a sharp bend")
        }
    }

    @Test
    fun `a straight road produces no bends at all`() {
        for (brg in listOf(0.0, 47.0, 180.0, 300.0)) {
            val route = line(200, 20.0, brg)
            assertEquals(0, Callouts.build(route).count { it.kind == Callouts.Kind.BEND },
                         "a straight road heading $brg was called a bend")
        }
    }

    @Test
    fun `one bend is one pill, not thirty`() {
        // The detector walks in 10 m steps, so a long curve trips it at every
        // step. Without merging, a single motorway loop would carry a wall of
        // identical warnings.
        val route = withBend(radiusM = 45.0, sweepDeg = 180)
        val bends = Callouts.build(route).filter { it.kind == Callouts.Kind.BEND }
        assertTrue(bends.size in 1..2, "a single 180-degree loop produced ${bends.size} pills")
    }

    @Test
    fun `a bend the router already called a turn is left to the router`() {
        // Every junction is geometrically a bend. Emitting both would stack a
        // "Sharp bend" on top of every "Turn left".
        val route = withBend(radiusM = 40.0, sweepDeg = 90)
        val at = route.totalM / 2
        val out = Callouts.build(route, listOf(Callouts.Junction(at, "turn-right")))
        assertEquals(0, out.count { it.kind == Callouts.Kind.BEND },
                     "the same corner was warned about twice")
        assertEquals(1, out.count { it.kind == Callouts.Kind.JUNCTION })
    }

    @Test
    fun `a bend's pill sits on the outside of it`() {
        // A pill on the inside of a bend is over the road the driver is about
        // to occupy, and at 60 degrees of pitch it would be under the ribbon.
        val route = withBend(radiusM = 40.0, sweepDeg = 90)   // turns right
        val bend = Callouts.build(route).first { it.kind == Callouts.Kind.BEND }
        val fix = route.project(bend.label)!!
        assertTrue(fix.offsetM > Callouts.LEADER_M * 0.6,
                   "the pill fell inside the bend, ${"%.0f".format(fix.offsetM)} m from the route")
    }

    // ------------------------------------------------------------------ speed

    @Test
    fun `a supplied limit is placed, and an absent one is not invented`() {
        val route = line(100, 20.0, 45.0)
        assertEquals(0, Callouts.build(route, speed = null).count { it.kind == Callouts.Kind.SPEED },
                     "a speed pill appeared without a limit to show")
        val one = Callouts.build(route, speed = Callouts.SpeedChange(800.0, 80))
            .single { it.kind == Callouts.Kind.SPEED }
        assertEquals("80 km/h", one.text)
        assertEquals("maxspeed:80", one.source)
        assertEquals(800.0, one.alongM, 1.0)
    }

    @Test
    fun `a nonsense limit is dropped rather than drawn`() {
        val route = line(100, 20.0, 0.0)
        for (bad in listOf(0, -30)) {
            assertEquals(0,
                Callouts.build(route, speed = Callouts.SpeedChange(500.0, bad))
                    .count { it.kind == Callouts.Kind.SPEED },
                "a limit of $bad km/h was drawn on the road")
        }
    }

    // ----------------------------------------------------------------- shape

    @Test
    fun `callouts come back in the order they will be driven past`() {
        val route = withBend(40.0, 90)
        val out = Callouts.build(
            route,
            listOf(Callouts.Junction(700.0, "turn-left"), Callouts.Junction(60.0, "keep-right")),
            Callouts.SpeedChange(300.0, 60),
        )
        assertTrue(out.size >= 3)
        for (i in 1 until out.size) {
            assertTrue(out[i].alongM >= out[i - 1].alongM,
                       "callouts are not in along-route order")
        }
    }

    @Test
    fun `a degenerate route produces nothing rather than throwing`() {
        val same = RouteGeometry.index(List(6) { LngLat(lng0, lat0) })
        if (same != null) {
            assertEquals(0, Callouts.build(same, listOf(Callouts.Junction(1.0, "turn-left"))).size)
        }
        assertNull(RouteGeometry.index(emptyList()))
    }

    @Test
    fun `a whole Doha journey is worked out in about a millisecond`() {
        // This runs on the main thread when a route lands, next to the chevron
        // pass. Both together have to disappear inside one frame.
        val route = line(1400, 10.0, 210.0)      // 14 km
        val js = (1..25).map { Callouts.Junction(it * 550.0, "turn-left") }
        val t0 = System.nanoTime()
        val out = Callouts.build(route, js)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(25, out.size)
        assertTrue(ms < 60.0, "a 14 km route took ${"%.1f".format(ms)} ms to call out")
    }

    @Test
    fun `pills hold their offset wherever in Qatar the route is`() {
        for (lat in listOf(24.5, 25.2854, 26.2)) {
            val pts = (0..80).map { LngLat(51.0 + it * 0.0002, lat) }
            val r = RouteGeometry.index(pts)!!
            val c = Callouts.build(r, listOf(Callouts.Junction(r.totalM / 2, "turn-left"))).single()
            assertEquals(Callouts.LEADER_M, m(c.anchor, c.label), 1.0,
                         "a pill at latitude $lat is the wrong distance off the road")
            assertTrue(abs(c.label.lat - c.anchor.lat) > 1e-9,
                       "a pill at latitude $lat did not move off the road at all")
        }
    }
}
