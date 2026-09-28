package dev.vector.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice guidance timing.
 *
 * These are the questions you cannot answer by driving around with a phone —
 * "did it say that twice?", "did it announce the 300 m warning after I was
 * already 40 m away?", "is 500 m enough warning at 120 km/h?" — and that are
 * trivial to answer here.
 */
class ManeuverAnnouncerTest {

    private fun turn(distanceM: Double, index: Int = 1, type: String = "turn-left") =
        ManeuverAnnouncer.Upcoming(index, type, "Turn left onto Al Corniche Street", distanceM)

    private val urban = 14.0    // ~50 km/h
    private val motorway = 33.0 // ~120 km/h

    @Test
    fun `nothing is said while the maneuver is far away`() {
        val a = ManeuverAnnouncer()
        assertNull(a.update(turn(5000.0), urban))
    }

    @Test
    fun `each stage fires exactly once`() {
        val a = ManeuverAnnouncer()
        assertNotNull(a.update(turn(400.0), urban), "prepare")
        assertNull(a.update(turn(380.0), urban), "prepare must not repeat")
        assertNull(a.update(turn(300.0), urban), "prepare must not repeat")
        assertNotNull(a.update(turn(110.0), urban), "turn")
        assertNull(a.update(turn(90.0), urban), "turn must not repeat")
        assertNotNull(a.update(turn(20.0), urban), "now")
        assertNull(a.update(turn(10.0), urban), "now must not repeat")
    }

    @Test
    fun `stages come in order`() {
        val a = ManeuverAnnouncer()
        val seen = mutableListOf<ManeuverAnnouncer.Stage>()
        for (d in listOf(600.0, 400.0, 200.0, 110.0, 60.0, 20.0)) {
            a.update(turn(d), urban)?.let { seen.add(it.stage) }
        }
        assertEquals(
            listOf(
                ManeuverAnnouncer.Stage.PREPARE,
                ManeuverAnnouncer.Stage.TURN,
                ManeuverAnnouncer.Stage.NOW,
            ),
            seen,
        )
    }

    @Test
    fun `a stage that was skipped past is not announced late`() {
        // Poor GPS can jump the driver from 500 m to 40 m. Announcing "in 300
        // metres, turn left" at that point is worse than saying nothing.
        val a = ManeuverAnnouncer()
        val first = assertNotNull(a.update(turn(25.0), urban))
        assertEquals(ManeuverAnnouncer.Stage.NOW, first.stage)
        assertNull(a.update(turn(20.0), urban))
    }

    @Test
    fun `a new maneuver resets the stages`() {
        val a = ManeuverAnnouncer()
        a.update(turn(400.0, index = 1), urban)
        a.update(turn(100.0, index = 1), urban)
        assertNotNull(a.update(turn(400.0, index = 2), urban), "the NEXT turn must be announced too")
    }

    @Test
    fun `warning distance scales with speed`() {
        // 30 s at 120 km/h is a kilometre; at 50 km/h it is ~420 m. A fixed
        // distance is either useless in town or dangerously late on a motorway.
        val slow = ManeuverAnnouncer()
        assertNull(slow.update(turn(900.0), urban), "900 m is too early at 50 km/h")

        val fast = ManeuverAnnouncer()
        assertNotNull(fast.update(turn(900.0), motorway), "900 m is right at 120 km/h")
    }

    @Test
    fun `the warning distance is clamped at both ends`() {
        // Stationary must not collapse the warning to zero...
        val stopped = ManeuverAnnouncer()
        assertNotNull(stopped.update(turn(240.0), 0.0), "min prepare distance must still apply")
        // ...and an implausible speed must not push it to the horizon.
        val absurd = ManeuverAnnouncer()
        assertNull(absurd.update(turn(3000.0), 200.0), "max prepare distance must cap it")
    }

    @Test
    fun `departure is never announced`() {
        val a = ManeuverAnnouncer()
        assertNull(a.update(turn(50.0, type = "depart"), urban))
    }

    @Test
    fun `arrival is announced as arrival, not as an instruction`() {
        val a = ManeuverAnnouncer()
        val said = assertNotNull(
            a.update(ManeuverAnnouncer.Upcoming(9, "arrive", "Arrive at destination", 15.0), urban)
        )
        assertTrue(said.text.contains("arrived", ignoreCase = true), said.text)
    }

    @Test
    fun `the prepare phrase names the distance and the maneuver`() {
        val a = ManeuverAnnouncer()
        val said = assertNotNull(a.update(turn(400.0), urban))
        assertTrue(said.text.startsWith("In "), said.text)
        assertTrue(said.text.contains("Al Corniche Street"), said.text)
    }

    @Test
    fun `arabic street names survive into speech`() {
        val a = ManeuverAnnouncer()
        val up = ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left onto شارع الكورنيش", 400.0)
        val said = assertNotNull(a.update(up, urban))
        assertTrue(said.text.contains("شارع الكورنيش"), said.text)
    }

    @Test
    fun `reset clears everything`() {
        val a = ManeuverAnnouncer()
        a.update(turn(400.0), urban)
        a.reset()
        assertNotNull(a.update(turn(400.0), urban))
    }

    @Test
    fun `spoken distances are rounded to something a driver can judge`() {
        assertEquals("2.4 kilometres", ManeuverAnnouncer.spokenDistance(2380.0))
        assertEquals("12 kilometres", ManeuverAnnouncer.spokenDistance(12300.0))
        assertEquals("600 metres", ManeuverAnnouncer.spokenDistance(612.0))
        assertEquals("450 metres", ManeuverAnnouncer.spokenDistance(437.0))
        assertEquals("80 metres", ManeuverAnnouncer.spokenDistance(78.0))
        // Never a machine-precise number.
        assertTrue(!ManeuverAnnouncer.spokenDistance(437.0).contains("437"))
    }
    // ---- compound announcements (V5) ---------------------------------------
    //
    // A maneuver does not become "next" until the previous one is done, so on a
    // short leg its own announcement cannot be early. Measured on the live
    // router's Al Wakrah route, whose maneuvers 1, 2 and 3 sit at 178 m, 223 m
    // and 278 m: the driver was told to turn right 2.4 SECONDS before the
    // junction and never heard a PREPARE stage for it at all.

    @Test
    fun `two manoeuvres seconds apart are announced together`() {
        val a = ManeuverAnnouncer()
        val r = a.update(
            ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left onto Al Thumama Street", 100.0),
            14.0,
            ManeuverAnnouncer.Upcoming(2, "turn-right", "Turn right onto Al Salam Street", 145.0),
        )
        assertNotNull(r)
        assertEquals("Turn left onto Al Thumama Street, then turn right", r.text)
    }

    @Test
    fun `a distant following manoeuvre is not tacked on`() {
        // Compounding everything would make every announcement a list. The
        // second maneuver's own announcement will arrive in time.
        val a = ManeuverAnnouncer()
        val r = a.update(
            ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left onto Al Thumama Street", 100.0),
            14.0,
            ManeuverAnnouncer.Upcoming(2, "turn-right", "Turn right onto Al Salam Street", 900.0),
        )
        assertNotNull(r)
        assertEquals("Turn left onto Al Thumama Street", r.text)
    }

    @Test
    fun `the compound window is a distance in town and a time on a motorway`() {
        // `max(MIN_COMPOUND_M, COMPOUND_SECONDS x speed)`, asserted at its own
        // boundary at four speeds. Below about 30 m/s the floor dominates —
        // 120 m apart is nine seconds in town and the second maneuver's own
        // announcement arrives in time anyway, so the floor is there to match
        // what both reference products say rather than because it is needed.
        // Above it the time term takes over, which is the half that matters.
        for (v in listOf(10.0, 20.0, 30.0, 40.0)) {
            val window = maxOf(ManeuverAnnouncer.MIN_COMPOUND_M, ManeuverAnnouncer.COMPOUND_SECONDS * v)
            val inside = ManeuverAnnouncer().update(
                ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left", 100.0), v,
                ManeuverAnnouncer.Upcoming(2, "turn-right", "Turn right", 100.0 + window - 5),
            )
            val outside = ManeuverAnnouncer().update(
                ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left", 100.0), v,
                ManeuverAnnouncer.Upcoming(2, "turn-right", "Turn right", 100.0 + window + 5),
            )
            assertEquals("Turn left, then turn right", inside?.text, "at $v m/s, just inside")
            assertEquals("Turn left", outside?.text, "at $v m/s, just outside")
        }
    }

    @Test
    fun `the far stage compounds too`() {
        val a = ManeuverAnnouncer()
        val r = a.update(
            ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left onto Al Thumama Street", 200.0),
            14.0,
            ManeuverAnnouncer.Upcoming(2, "turn-right", "Turn right onto Al Salam Street", 245.0),
        )
        assertNotNull(r)
        assertEquals(ManeuverAnnouncer.Stage.PREPARE, r.stage)
        assertEquals("In 200 metres, turn left onto Al Thumama Street, then turn right", r.text)
    }

    @Test
    fun `the following action never carries its own road name`() {
        // A sentence naming two Doha street names takes longer to say than the
        // gap it is warning about.
        val r = ManeuverAnnouncer().update(
            ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left onto Al Thumama Street", 100.0),
            14.0,
            ManeuverAnnouncer.Upcoming(2, "turn-right", "Turn right onto Al Salam Street", 140.0),
        )
        assertNotNull(r)
        assertFalse(r.text.contains("Al Salam"), "the road name leaked into the 'then' clause")
    }

    @Test
    fun `an unknown maneuver type falls back to the router's own sentence`() {
        // The short forms are a fixed English vocabulary; a type this file has
        // never heard of must still produce something a driver can act on
        // rather than nothing.
        val r = ManeuverAnnouncer().update(
            ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left", 100.0),
            14.0,
            ManeuverAnnouncer.Upcoming(2, "merge-left", "Merge left onto the ramp", 140.0),
        )
        assertNotNull(r)
        assertEquals("Turn left, then merge left onto the ramp", r.text)
    }

    @Test
    fun `no following manoeuvre leaves the sentence alone`() {
        val r = ManeuverAnnouncer().update(
            ManeuverAnnouncer.Upcoming(1, "turn-left", "Turn left", 100.0), 14.0, null,
        )
        assertEquals("Turn left", r?.text)
    }

}
