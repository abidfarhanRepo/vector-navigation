package dev.vector.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Route comparison, and the honesty of the traffic note.
 *
 * Both are V4 additions and both are pure, so they belong in a test rather than
 * only in a screenshot.
 */
class RouteComparisonTest {

    // ---- compare, do not enumerate -----------------------------------------

    @Test
    fun `only a difference that rounds to nothing reads as the same time`() {
        // The comparison still answers "is it worth switching?" rather than
        // making the driver subtract two durations at the wheel — that part of
        // the Waze lesson stands.
        //
        // What changed is where "the same" stops. It used to be any difference
        // under 90 s, which made the label useless in the common case: a
        // chooser offering 7, 7 and 8 minutes printed "Similar time" on BOTH
        // alternatives, so the one line that exists to tell two routes apart
        // said the same thing on every row. Reported exactly that way from a
        // real drive. A minute is a minute; the driver decides whether it
        // matters, and the app does not round it away for them.
        assertEquals("Same time", routeComparison(16 * 60.0, 16 * 60.0))
        assertEquals("+1 min", routeComparison(16 * 60.0 + 60, 16 * 60.0))
        assertEquals("1 min faster", routeComparison(16 * 60.0 - 60, 16 * 60.0))
    }

    @Test
    fun `a slower route says how much slower`() {
        assertEquals("+3 min", routeComparison(19 * 60.0, 16 * 60.0))
        assertEquals("+14 min", routeComparison(30 * 60.0, 16 * 60.0))
    }

    @Test
    fun `a faster route says so in words rather than with a minus sign`() {
        // "-3 min" is ambiguous about which way it helps; "3 min faster" is not.
        assertEquals("3 min faster", routeComparison(13 * 60.0, 16 * 60.0))
    }

    @Test
    fun `the boundary is where the rounded minutes stop being zero`() {
        // There is no separate threshold constant any more. "The same" is
        // exactly "rounds to zero minutes", so the rule the code follows and
        // the rule the label states cannot come apart — which is the defect the
        // previous version of this test was written to pin down, when a 90 s
        // boundary existed as an accident of Math.round against a comment
        // claiming 2 minutes.
        assertEquals("Same time", routeComparison(16 * 60.0 + 29, 16 * 60.0))
        assertEquals("+1 min", routeComparison(16 * 60.0 + 30, 16 * 60.0))
        assertEquals("Same time", routeComparison(16 * 60.0 - 29, 16 * 60.0))
        assertEquals("1 min faster", routeComparison(16 * 60.0 - 30, 16 * 60.0))
    }

    @Test
    fun `seconds are rounded rather than truncated`() {
        // 179 s is closer to 3 minutes than to 2. Truncating would under-report
        // every difference by up to a minute, which on a 2-minute threshold is
        // enough to hide a real one.
        assertEquals("+3 min", routeComparison(16 * 60.0 + 179, 16 * 60.0))
        assertEquals("3 min faster", routeComparison(16 * 60.0 - 179, 16 * 60.0))
    }

    @Test
    fun `a zero-length comparison does not divide by anything`() {
        assertEquals("Same time", routeComparison(0.0, 0.0))
    }

    // ---- the traffic note has to stay honest -------------------------------

    @Test
    fun `nothing is said when nothing is known`() {
        // Google writes "Fastest route, the usual traffic" here, and it is
        // doing real work — it tells the driver whether to believe the number.
        // Vector cannot say that: V3 established the feed is synthetic and put
        // a 3-probe evidence floor on /traffic, so `jamCount` is null when the
        // floor withholds everything. A sentence claiming normal conditions on
        // the strength of no data is the fake live-traffic product §25 forbids.
        assertNull(trafficNote(null))
    }

    @Test
    fun `zero measured jams is not a claim that the roads are clear`() {
        // This is the distinction that matters. "No congestion was measured"
        // and "there is no congestion" are different statements, and with a
        // synthetic feed behind an evidence floor Vector is only entitled to
        // the first. So it says nothing.
        assertNull(trafficNote(0))
    }

    @Test
    fun `a real count is reported as a count`() {
        assertEquals("1 jam reported on the network", trafficNote(1))
        assertEquals("6 jams reported on the network", trafficNote(6))
    }

    @Test
    fun `the wording is about the network, not about this route`() {
        // /traffic is a whole-city payload and nothing intersects it with the
        // chosen route, so "6 jams on your route" would be a fabrication. The
        // wording has to stay as weak as the data.
        val note = trafficNote(6)!!
        assertEquals(true, note.contains("network"))
        assertEquals(false, note.contains("your route"))
    }
}
