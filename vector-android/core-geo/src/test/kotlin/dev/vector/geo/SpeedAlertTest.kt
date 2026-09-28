package dev.vector.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Over-limit alerts (V5).
 *
 * Every failure mode of a speed alert is about repetition, and each one makes
 * the driver switch it off. None of them is answerable by driving around with
 * a phone, which is why the decision is a pure state machine.
 */
class SpeedAlertTest {

    private fun alert() = SpeedAlert(toleranceKmh = 5, rearmMarginKmh = 2, minRepeatMs = 60_000L)

    @Test
    fun `under the limit says nothing`() {
        assertNull(alert().update(78, 80, inferred = false, nowMs = 0L))
    }

    @Test
    fun `inside the tolerance says nothing`() {
        // Speedometers read a few km/h high by design, and GPS speed is noisy.
        // Flagging 83 in an 80 would cry wolf.
        val a = alert()
        assertNull(a.update(83, 80, inferred = false, nowMs = 0L))
        assertNull(a.update(85, 80, inferred = false, nowMs = 1_000L))
    }

    @Test
    fun `past the tolerance the limit is spoken`() {
        assertEquals("Speed limit 80", alert().update(92, 80, inferred = false, nowMs = 0L))
    }

    @Test
    fun `it says the limit rather than accusing the driver`() {
        // They already know how fast they are going; the limit is the piece
        // they are missing. Both reference products do the same.
        val said = alert().update(92, 80, inferred = false, nowMs = 0L)!!
        assertEquals("Speed limit 80", said)
    }

    @Test
    fun `an unposted limit is never alerted on`() {
        // A class default is the median for the road type, not a surveyed sign.
        // Accusing a driver of breaking a limit nobody has surveyed is worse
        // than saying nothing, and the visual indicator already refuses to.
        assertNull(alert().update(120, 60, inferred = true, nowMs = 0L))
    }

    @Test
    fun `it fires once, not once per frame`() {
        val a = alert()
        assertNotNull(a.update(92, 80, inferred = false, nowMs = 0L))
        for (t in 1..50) {
            assertNull(a.update(92, 80, inferred = false, nowMs = t * 100L),
                "spoke again at ${t * 100} ms")
        }
    }

    @Test
    fun `a sustained excursion is mentioned again after a minute`() {
        // A driver twenty over for five minutes should hear about it more than
        // once.
        val a = alert()
        assertNotNull(a.update(100, 80, inferred = false, nowMs = 0L))
        assertNull(a.update(100, 80, inferred = false, nowMs = 59_000L))
        assertNotNull(a.update(100, 80, inferred = false, nowMs = 61_000L))
    }

    @Test
    fun `hovering at the threshold does not chatter`() {
        // The whole reason firing and re-arming use different speeds. With one
        // threshold, a driver holding a genuine 85 in an 80 triggers an alert
        // every few seconds — the same oscillation the auto-zoom bands needed
        // hysteresis for.
        val a = alert()
        assertNotNull(a.update(86, 80, inferred = false, nowMs = 0L))
        val wobble = listOf(84, 86, 85, 87, 84, 86, 85, 87)
        for ((i, v) in wobble.withIndex()) {
            assertNull(a.update(v, 80, inferred = false, nowMs = 1_000L + i * 1_000L),
                "chattered at $v km/h")
        }
    }

    @Test
    fun `coming genuinely back under re-arms it`() {
        val a = alert()
        assertNotNull(a.update(92, 80, inferred = false, nowMs = 0L))
        assertNull(a.update(70, 80, inferred = false, nowMs = 5_000L))
        // Argument order matters and it bit me here: kotlin.test's signature is
        // assertNotNull(actual, message), so passing the message first
        // type-checks — the message becomes the non-null "actual" — and the
        // assertion passes without ever looking at the result.
        assertNotNull(
            a.update(92, 80, inferred = false, nowMs = 10_000L),
            "a second real excursion must be announced",
        )
    }

    @Test
    fun `a new limit is a new fact`() {
        // Dropping from an 80 into a 50 and staying at 70 is a new excursion,
        // and the previous road's cooldown must not swallow it.
        val a = alert()
        assertNotNull(a.update(92, 80, inferred = false, nowMs = 0L))
        assertEquals("Speed limit 50", a.update(70, 50, inferred = false, nowMs = 2_000L))
    }

    @Test
    fun `no limit known means nothing to say`() {
        assertNull(alert().update(120, null, inferred = false, nowMs = 0L))
    }

    @Test
    fun `no speed known means nothing to say`() {
        // The receiver reports no speed at a standstill on many chipsets, and
        // "unknown" must not be read as "fast".
        assertNull(alert().update(null, 50, inferred = false, nowMs = 0L))
    }

    @Test
    fun `reset forgets the excursion`() {
        val a = alert()
        assertNotNull(a.update(92, 80, inferred = false, nowMs = 0L))
        a.reset()
        assertNotNull(a.update(92, 80, inferred = false, nowMs = 1_000L))
    }
}
