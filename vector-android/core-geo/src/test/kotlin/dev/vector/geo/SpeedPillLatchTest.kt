package dev.vector.geo

import dev.vector.geo.Callouts.SpeedChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The speed-limit-change pill is decided once per maneuver and latched.
 *
 * The defect these tests pin down: the "is this a change?" comparison used to
 * be made against the LIVE `UiState.speedLimitKmh` when the `/speed` reply
 * landed. On a maneuver close to the change point the reply lands after the car
 * has crossed it, `refreshRoadHere` has already moved the live baseline to the
 * new limit, the comparison reads "no change", and the pill is withdrawn —
 * then a later maneuver's reply puts one back. Appear → disappear → reappear.
 */
class SpeedPillLatchTest {

    // The flicker, reproduced exactly: the pill is discovered against the old
    // baseline, then the base flips underneath it.

    @Test
    fun `the live baseline catching up does not withdraw the pill`() {
        val latch = SpeedPillLatch()
        // Approaching the maneuver: the road ahead is 80, the road underneath
        // is 60. The probe is sent now and the reply says 80.
        assertTrue(latch.beginProbe(3))
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 60, alongM = 1045.0)
        assertEquals(SpeedChange(1045.0, 80), latch.pill)

        // The car crosses the change point; roadHere moves the live limit to
        // 80. If the decision were re-made against that baseline the pill would
        // vanish. It must not.
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 80, alongM = 1045.0)
        assertEquals(
            SpeedChange(1045.0, 80), latch.pill,
            "the pill flickered when the baseline caught up",
        )
    }

    @Test
    fun `a maneuver is queried exactly once, however often it is approached`() {
        val latch = SpeedPillLatch()
        assertTrue(latch.beginProbe(3))
        // A projection that jitters across the maneuver boundary re-enters
        // refreshSpeedAhead for the same maneuver. It must not ask again.
        assertFalse(latch.beginProbe(3))
        assertFalse(latch.beginProbe(3))
    }

    @Test
    fun `re-resolving the same maneuver cannot bring a withdrawn pill back`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(3)
        // Discovered as a change.
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 60, alongM = 1045.0)
        assertEquals(SpeedChange(1045.0, 80), latch.pill)
        // A replayed reply with a flipped baseline does not re-decide.
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 80, alongM = 1045.0)
        assertEquals(SpeedChange(1045.0, 80), latch.pill)
        // Nor does a late reply carrying a different number.
        latch.resolve(3, limitKmh = 100, inferred = false, baselineKmh = 60, alongM = 1045.0)
        assertEquals(SpeedChange(1045.0, 80), latch.pill)
    }

    // What is eligible.

    @Test
    fun `an inferred class median is never a pill`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(3)
        latch.resolve(3, limitKmh = 80, inferred = true, baselineKmh = 60, alongM = 1045.0)
        assertNull(latch.pill, "a class median was presented as a sign")
    }

    @Test
    fun `no limit or a zero limit is never a pill`() {
        for (limit in listOf(null, 0, -1)) {
            val latch = SpeedPillLatch()
            latch.beginProbe(3)
            latch.resolve(3, limitKmh = limit, inferred = false, baselineKmh = 60, alongM = 1045.0)
            assertNull(latch.pill, "a limit of $limit produced a pill")
        }
    }

    @Test
    fun `a limit already in force is not a change`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(3)
        latch.resolve(3, limitKmh = 60, inferred = false, baselineKmh = 60, alongM = 1045.0)
        assertNull(latch.pill, "the pill restated the limit already on the disc")
    }

    @Test
    fun `a first pill and a changed pill both carry the surveyed number`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(1)
        latch.resolve(1, limitKmh = 100, inferred = false, baselineKmh = null, alongM = 500.0)
        assertEquals(SpeedChange(500.0, 100), latch.pill)
        assertEquals(1, latch.maneuver)
    }

    // Release, replacement and teardown.

    @Test
    fun `a later maneuver replaces the pill rather than adding a second`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(3)
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 60, alongM = 1045.0)
        // The maneuver is passed; the next one's reply lands.
        assertTrue(latch.beginProbe(4))
        latch.resolve(4, limitKmh = 100, inferred = false, baselineKmh = 80, alongM = 1600.0)
        assertEquals(SpeedChange(1600.0, 100), latch.pill)
        assertEquals(4, latch.maneuver)
    }

    @Test
    fun `a later maneuver with no change clears the earlier pill`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(3)
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 60, alongM = 1045.0)
        assertEquals(3, latch.maneuver)
        // The next maneuver joins a road at the same limit, so nothing to say.
        latch.beginProbe(4)
        latch.resolve(4, limitKmh = 80, inferred = false, baselineKmh = 80, alongM = 1600.0)
        assertNull(latch.pill, "a passed maneuver's pill was left on the map")
        assertEquals(-1, latch.maneuver)
    }

    @Test
    fun `teardown forgets the pill and the maneuvers already queried`() {
        val latch = SpeedPillLatch()
        latch.beginProbe(3)
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 60, alongM = 1045.0)
        latch.clear()
        assertNull(latch.pill)
        assertEquals(-1, latch.maneuver)
        // A new journey may legitimately reuse an index, so the query memory
        // must go with the pill.
        assertTrue(latch.beginProbe(3))
    }

    // The whole arc, end to end.

    @Test
    fun `the pill appears once, holds while the car crosses, then yields to the next`() {
        val latch = SpeedPillLatch()
        val seen = ArrayList<SpeedChange?>()

        // Approaching maneuver 3: change to 80 discovered.
        latch.beginProbe(3)
        latch.resolve(3, limitKmh = 80, inferred = false, baselineKmh = 60, alongM = 1045.0)
        seen += latch.pill
        // More fixes arrive as the car approaches; no new query, no change.
        seen += latch.pill
        // The car crosses the change point and the live baseline catches up.
        // (A replayed reply, or simply the roadHere update — neither touches
        // the latched pill.)
        seen += latch.pill
        // Maneuver 3 is passed and maneuver 4's probe lands with a change.
        latch.beginProbe(4)
        latch.resolve(4, limitKmh = 100, inferred = false, baselineKmh = 80, alongM = 1600.0)
        seen += latch.pill

        assertEquals(
            listOf<SpeedChange?>(
                SpeedChange(1045.0, 80),
                SpeedChange(1045.0, 80),
                SpeedChange(1045.0, 80),
                SpeedChange(1600.0, 100),
            ),
            seen,
        )
    }
}
