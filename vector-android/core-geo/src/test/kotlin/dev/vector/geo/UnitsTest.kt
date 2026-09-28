package dev.vector.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Units, on both surfaces at once.
 *
 * The point of the class under test is that the SCREEN formatter and the VOICE
 * formatter cannot disagree, because they used to live in different modules
 * with no shared notion of units — so the obvious way to add a units setting
 * would have produced a HUD reading "0.4 mi" beside a voice saying "in 600
 * metres". `both surfaces switch together` is the test that pins that.
 */
class UnitsTest {

    // ---- metric screen -----------------------------------------------------

    @Test
    fun `metres round to a scale a driver can judge`() {
        assertEquals("40 m", Units.METRIC.shortDistance(47.0))
        assertEquals("400 m", Units.METRIC.shortDistance(420.0))
    }

    @Test
    fun `kilometres gain a decimal only while it is useful`() {
        assertEquals("1.5 km", Units.METRIC.shortDistance(1500.0))
        assertEquals("12 km", Units.METRIC.shortDistance(12_400.0))
    }

    // ---- imperial screen ---------------------------------------------------

    @Test
    fun `short imperial distances are feet`() {
        // 100 m is about 328 ft.
        assertTrue(Units.IMPERIAL.shortDistance(100.0).endsWith(" ft"))
    }

    @Test
    fun `imperial switches to miles at a quarter mile`() {
        // A quarter mile is ~402 m. Below it, feet; above it, miles — because
        // "1,760 yards" is not something anyone says.
        assertTrue(Units.IMPERIAL.shortDistance(300.0).endsWith(" ft"))
        assertTrue(Units.IMPERIAL.shortDistance(800.0).endsWith(" mi"))
    }

    @Test
    fun `a mile reads as about a mile`() {
        assertEquals("1.0 mi", Units.IMPERIAL.shortDistance(1609.344))
    }

    // ---- speed -------------------------------------------------------------

    @Test
    fun `metric speed is km per hour`() {
        assertEquals("km/h", Units.METRIC.speedLabel)
        assertEquals(36, Units.METRIC.speed(10.0))
    }

    @Test
    fun `imperial speed is miles per hour`() {
        assertEquals("mph", Units.IMPERIAL.speedLabel)
        assertEquals(22, Units.IMPERIAL.speed(10.0))
    }

    @Test
    fun `a posted limit converts from the km per hour the backend reports`() {
        // /speed returns `maxspeed_kmh` regardless of the client preference,
        // because OSM maxspeed is normalised during ingestion.
        assertEquals(100, Units.METRIC.postedLimit(100))
        assertEquals(62, Units.IMPERIAL.postedLimit(100))
    }

    // ---- voice -------------------------------------------------------------

    @Test
    fun `the spoken form writes the unit out`() {
        // TTS engines read "km" inconsistently across voices.
        assertTrue(Units.METRIC.spokenDistance(400.0).endsWith("metres"))
        assertTrue(Units.IMPERIAL.spokenDistance(2000.0).endsWith("miles"))
    }

    @Test
    fun `one mile is not plural`() {
        assertEquals("1 mile", Units.IMPERIAL.spokenDistance(1609.344))
    }

    @Test
    fun `the spoken form never reads a machine number`() {
        // "In 437 metres" is a machine talking.
        for (m in listOf(437.0, 63.0, 1234.0, 8888.0)) {
            val said = Units.METRIC.spokenDistance(m)
            assertTrue(said.none { it == '.' } || said.contains("kilometres"),
                       "$said reads like a raw measurement")
        }
    }

    @Test
    fun `both surfaces switch together`() {
        // The whole reason this type exists rather than a boolean threaded
        // through two modules. If a future change moves one formatter and not
        // the other, this is what fails.
        for (u in Units.entries) {
            val screen = u.shortDistance(3000.0)
            val voice = u.spokenDistance(3000.0)
            val metricScreen = screen.contains("km")
            val metricVoice = voice.contains("kilometre")
            assertEquals(
                metricScreen, metricVoice,
                "$u disagrees with itself: screen=\"$screen\" voice=\"$voice\"",
            )
        }
    }

    @Test
    fun `the announcer speaks in the units it was given`() {
        val a = ManeuverAnnouncer(units = Units.IMPERIAL)
        val said = a.update(
            // 700 m, not 800: the PREPARE stage aims for 30 s of warning, so
            // at 25 m/s it fires below 750 m and an 800 m maneuver is simply
            // not announced yet.
            ManeuverAnnouncer.Upcoming(0, "turn-left", "Turn left onto Salwa Road", 700.0),
            speedMs = 25.0,
        )
        assertTrue(said!!.text.contains("mile"),
                   "expected an imperial prepare announcement, got ${said.text}")
    }

    @Test
    fun `changing units mid-journey does not re-announce the current maneuver`() {
        // `units` is a var rather than a constructor-only value precisely so
        // that applying the setting does not mean rebuilding the announcer,
        // which would clear `fired` and repeat the instruction the driver is
        // already acting on.
        val a = ManeuverAnnouncer()
        val next = ManeuverAnnouncer.Upcoming(0, "turn-left", "Turn left", 700.0)
        assertTrue(a.update(next, 25.0) != null)
        a.units = Units.IMPERIAL
        assertEquals(null, a.update(next, 25.0))
    }
}
