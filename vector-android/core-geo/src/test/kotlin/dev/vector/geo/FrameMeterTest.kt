package dev.vector.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The V3 release blocker this class exists for: `dumpsys gfxinfo` cannot see a
 * `SurfaceView`, so the 120 Hz premise the whole native rewrite was justified
 * by has never been checked. §29 says not to claim a frame rate without
 * evidence, so the app measures itself.
 */
class FrameMeterTest {

    private val ms = 1_000_000L

    private fun feed(m: FrameMeter, intervalsMs: List<Double>, startNs: Long = 1_000_000_000L) {
        var t = startNs
        m.onFrame(t)
        for (dt in intervalsMs) {
            t += (dt * ms).toLong()
            m.onFrame(t)
        }
    }

    @Test
    fun `a steady 120 Hz reads as 120 Hz`() {
        val m = FrameMeter()
        feed(m, List(300) { 8.333 })
        val r = m.report()!!
        assertEquals(120.0, r.observedHz, 1.0)
        assertEquals(0, r.lateFrames)
    }

    @Test
    fun `a steady 60 Hz reads as 60 Hz`() {
        val m = FrameMeter()
        feed(m, List(300) { 16.667 })
        assertEquals(60.0, m.report()!!.observedHz, 1.0)
    }

    @Test
    fun `nothing is reported before there is enough to say`() {
        // A percentile over eleven frames is a number with no meaning
        // attached, and printing one would be exactly the unevidenced
        // performance claim this class exists to avoid.
        val m = FrameMeter()
        feed(m, List(10) { 8.333 })
        assertNull(m.report())
        feed(m, List(FrameMeter.MIN_SAMPLES) { 8.333 })
        assertNotNull(m.report())
    }

    @Test
    fun `the late budget scales with the observed rate, not a fixed millisecond`() {
        // 8.3 ms is on time at 120 Hz and EARLY at 60. A fixed threshold would
        // grade the same smoothness differently on two phones, which is how a
        // performance number becomes a property of the handset rather than of
        // the software.
        val fast = FrameMeter().also { feed(it, List(200) { 8.333 } + List(20) { 14.0 }) }
        val slow = FrameMeter().also { feed(it, List(200) { 16.667 } + List(20) { 14.0 }) }
        assertTrue(fast.report()!!.lateFrames > 0, "14 ms is late at 120 Hz")
        assertEquals(0, slow.report()!!.lateFrames, "14 ms is not late at 60 Hz")
    }

    @Test
    fun `a stutter shows up in the tail and not in the median`() {
        val m = FrameMeter()
        // 5% of frames take four times as long.
        feed(m, (1..200).map { if (it % 20 == 0) 33.3 else 8.333 })
        val r = m.report()!!
        assertEquals(8.3, r.medianMs, 0.6, "the median must stay honest")
        assertTrue(r.p99Ms > 25.0, "the tail must show it: p99=${r.p99Ms}")
        assertTrue(r.lateFrames >= 9, "late frames: ${r.lateFrames}")
    }

    @Test
    fun `a pocket is not a dropped frame`() {
        // The screen went off, the process was frozen, the app was paused.
        // Folding a four-second gap into a percentile would report the phone
        // being in a pocket as catastrophic jank — which is a lie that makes
        // the whole measurement worthless.
        val m = FrameMeter()
        var t = 1_000_000_000L
        m.onFrame(t)
        repeat(100) { t += (8.333 * ms).toLong(); m.onFrame(t) }
        t += 4_000L * ms                       // four seconds of nothing
        m.onFrame(t)
        repeat(100) { t += (8.333 * ms).toLong(); m.onFrame(t) }
        val r = m.report()!!
        assertEquals(0, r.lateFrames)
        assertTrue(r.worstMs < 12.0, "worst=${r.worstMs}")
        assertEquals(200L, m.framesSeen, "the gap must not be counted as a frame")
    }

    @Test
    fun `a non-monotonic timestamp is ignored rather than recorded as negative`() {
        val m = FrameMeter()
        var t = 1_000_000_000L
        m.onFrame(t)
        repeat(80) { t += (8.333 * ms).toLong(); m.onFrame(t) }
        m.onFrame(t - 5_000_000L)              // went backwards
        val r = m.report()!!
        assertTrue(r.medianMs > 0.0)
        assertEquals(0, r.lateFrames)
    }

    @Test
    fun `the window is bounded, so a long drive still describes the road you are on`() {
        val m = FrameMeter(capacity = 100)
        feed(m, List(500) { 8.333 })
        assertEquals(100, m.samples)
        assertEquals(500L, m.framesSeen)
    }

    @Test
    fun `the window forgets a stutter that has passed`() {
        val m = FrameMeter(capacity = 120)
        feed(m, List(120) { 40.0 })            // a bad stretch
        assertTrue(m.report()!!.medianMs > 30.0)
        feed(m, List(120) { 8.333 })           // then a good one
        assertEquals(8.3, m.report()!!.medianMs, 0.6, "the old frames must have aged out")
    }

    @Test
    fun `reset clears everything`() {
        val m = FrameMeter()
        feed(m, List(200) { 8.333 })
        m.reset()
        assertEquals(0, m.samples)
        assertEquals(0L, m.framesSeen)
        assertNull(m.report())
    }

    @Test
    fun `the log line is parseable and stable`() {
        // Read by scripts/verify_on_device.sh, so the shape is a contract.
        val m = FrameMeter()
        feed(m, List(200) { 8.333 })
        val line = m.report()!!.oneLine()
        for (field in listOf("frames=", "hz=", "p50=", "p95=", "p99=", "worst=", "late=")) {
            assertTrue(line.contains(field), "missing $field in: $line")
        }
        assertTrue(
            Regex("""hz=(\d+\.\d)""").find(line)!!.groupValues[1].toDouble() > 100.0,
            "hz must be parseable from: $line",
        )
    }
}
