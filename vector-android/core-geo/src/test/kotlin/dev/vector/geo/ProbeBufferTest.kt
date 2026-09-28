package dev.vector.geo

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Traffic probe collection.
 *
 * The learned-speed layer has consumed zero real trips because contributing was
 * a separate activity from driving — a `/collect` page you had to visit and a
 * button you had to press. This buffer makes it a by-product of navigation, and
 * these tests cover the ways that can silently go wrong: duplicated points, lost
 * tails, two journeys stitched into one, a dead network eating memory, and a
 * withdrawn consent that still uploads.
 */
class ProbeBufferTest {

    private val lat = 25.2854
    private val lng0 = 51.531
    private val kx = cos(Math.toRadians(lat)) * RouteGeometry.M_PER_DEG_LAT

    private fun p(eastM: Double, tsMs: Long, acc: Double? = 5.0) =
        ProbeBuffer.Probe(lng0 + eastM / kx, lat, tsMs, acc, 14.0, 90.0)

    @Test
    fun `points accumulate until the batch is full`() {
        val b = ProbeBuffer(batchSize = 5)
        for (i in 0 until 4) assertNull(b.offer(p(i * 20.0, i * 1000L)), "batch $i")
        val batch = assertNotNull(b.offer(p(80.0, 4000L)))
        assertEquals(5, batch.points.size)
        assertEquals(0, b.pendingCount, "flushing must empty the buffer")
    }

    @Test
    fun `a batch also goes out on age, not only on size`() {
        // A slow crawl in traffic is exactly when the data is most valuable and
        // least likely to reach the size threshold.
        val b = ProbeBuffer(batchSize = 100, maxBatchAgeMs = 10_000)
        b.offer(p(0.0, 0L))
        b.offer(p(20.0, 3_000L))
        val batch = assertNotNull(b.offer(p(40.0, 11_000L)), "should flush on age")
        assertEquals(3, batch.points.size)
    }

    @Test
    fun `fixes too close together are dropped`() {
        // 1 Hz at walking pace produces near-duplicate points that tell the
        // map-matcher nothing and cost bandwidth.
        val b = ProbeBuffer(batchSize = 100, minSpacingM = 8.0)
        b.offer(p(0.0, 0L))
        b.offer(p(2.0, 1000L))
        b.offer(p(4.0, 2000L))
        assertEquals(1, b.pendingCount)
        assertEquals(2, b.droppedCount)
    }

    @Test
    fun `an inaccurate fix is rejected`() {
        val b = ProbeBuffer(maxAccuracyM = 50.0)
        assertNull(b.offer(p(0.0, 0L, acc = 300.0)))
        assertEquals(0, b.pendingCount)
        assertEquals(1, b.droppedCount)
    }

    @Test
    fun `a fix with unknown accuracy is accepted`() {
        // Missing accuracy is normal on some receivers; treating it as bad would
        // discard whole devices' worth of data.
        val b = ProbeBuffer()
        b.offer(p(0.0, 0L, acc = null))
        assertEquals(1, b.pendingCount)
    }

    @Test
    fun `a fix that goes backwards in time is rejected`() {
        // A receiver can emit one after a cold start; accepting it would invert
        // the trace and produce a negative speed for that segment.
        val b = ProbeBuffer(batchSize = 100)
        b.offer(p(0.0, 5_000L))
        b.offer(p(50.0, 4_000L))
        assertEquals(1, b.pendingCount)
        assertEquals(1, b.droppedCount)
    }

    @Test
    fun `a long gap closes the trip and starts a new pseudonym`() {
        // Otherwise this morning's commute and this evening's are stitched into
        // one journey, and the map-matcher sees a vehicle teleport.
        val b = ProbeBuffer(batchSize = 100, tripGapMs = 60_000)
        b.offer(p(0.0, 0L))
        b.offer(p(50.0, 1_000L))
        val firstTrip = b.currentTrip

        val closing = assertNotNull(b.offer(p(100.0, 500_000L)), "the gap must flush the old trip")
        assertTrue(closing.end, "a gap-closed batch must be marked end")
        assertEquals(firstTrip, closing.trip)
        assertTrue(b.currentTrip != firstTrip, "a new trip needs a new pseudonym")
    }

    @Test
    fun `the trip pseudonym is per trip, never per device`() {
        // adr-0065: a stable device id would make every journey linkable to
        // every other, which is the property the design exists to prevent.
        val b = ProbeBuffer()
        val t1 = b.currentTrip
        b.offer(p(0.0, 0L))
        b.endTrip()
        val t2 = b.currentTrip
        assertTrue(t1 != t2)
        assertTrue(t1.isNotEmpty() && t2.isNotEmpty())
    }

    @Test
    fun `ending a trip flushes the tail`() {
        val b = ProbeBuffer(batchSize = 100)
        b.offer(p(0.0, 0L))
        b.offer(p(50.0, 1_000L))
        val tail = assertNotNull(b.endTrip(), "the last few points must not be lost")
        assertEquals(2, tail.points.size)
        assertTrue(tail.end)
    }

    @Test
    fun `ending an empty trip produces nothing`() {
        assertNull(ProbeBuffer().endTrip())
    }

    @Test
    fun `a dead network cannot exhaust memory`() {
        // Nothing is being uploaded, so the buffer must bound itself.
        val b = ProbeBuffer(batchSize = Int.MAX_VALUE, maxBatchAgeMs = Long.MAX_VALUE, maxRetained = 100)
        for (i in 0 until 500) b.offer(p(i * 20.0, i * 1000L))
        assertTrue(b.pendingCount <= 100, "retained ${b.pendingCount}")
        assertTrue(b.droppedCount >= 400)
    }

    @Test
    fun `overflow drops the oldest, because fresh traffic data is the useful kind`() {
        val b = ProbeBuffer(batchSize = Int.MAX_VALUE, maxBatchAgeMs = Long.MAX_VALUE, maxRetained = 3)
        for (i in 0 until 6) b.offer(p(i * 20.0, i * 1000L))
        val batch = assertNotNull(b.flush(end = false))
        assertEquals(3, batch.points.size)
        assertEquals(5000L, batch.points.last().ts, "the newest point must survive")
        assertEquals(3000L, batch.points.first().ts, "the oldest must have been dropped")
    }

    @Test
    fun `withdrawing consent discards everything unsent`() {
        val b = ProbeBuffer(batchSize = 100)
        b.offer(p(0.0, 0L))
        b.offer(p(50.0, 1_000L))
        b.discard()
        assertEquals(0, b.pendingCount)
        assertNull(b.flush(end = true), "nothing held may be uploaded after a discard")
    }

    @Test
    fun `all points in a batch belong to one trip`() {
        // The server groups by trip token; a mixed batch would merge two
        // journeys under one pseudonym.
        val b = ProbeBuffer(batchSize = 100, tripGapMs = 60_000)
        for (i in 0 until 5) b.offer(p(i * 20.0, i * 1000L))
        val closing = assertNotNull(b.offer(p(200.0, 900_000L)))
        assertEquals(5, closing.points.size, "the new trip's point must not be in the old batch")
    }

    @Test
    fun `a realistic drive produces a steady stream of full batches`() {
        // 10 minutes at 1 Hz and 50 km/h: ~14 m between fixes, so nothing is
        // thinned, and batches of 30 should appear every 30 s.
        val b = ProbeBuffer(batchSize = 30, maxBatchAgeMs = 30_000, minSpacingM = 8.0)
        var batches = 0
        var points = 0
        for (i in 0 until 600) {
            b.offer(p(i * 14.0, i * 1000L))?.let { batches++; points += it.points.size }
        }
        assertEquals(20, batches, "expected one batch per 30 fixes")
        assertEquals(600, points, "no fix should be lost on a clean drive")
    }
}
