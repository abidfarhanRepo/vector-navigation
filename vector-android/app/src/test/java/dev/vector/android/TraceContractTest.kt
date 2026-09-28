package dev.vector.android

import dev.vector.geo.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a replayable trace has to be true of.
 *
 * ## Why this is a test and not a comment
 *
 * `scripts/simulate_drive.sh` pushes one of these CSV files into a handset's
 * fused-location provider and the app navigates from it. Everything a device run
 * concludes is therefore a claim about *this file*, and the file is generated
 * code output that nobody reads. A trace that quietly acquired an impossible
 * step, a duplicated timestamp, a coordinate in the Gulf, or a bearing pointing
 * backwards would produce a drive that runs to completion, reports events, and
 * measures nothing — and the failure would be attributed to the app.
 *
 * The repository has already been bitten by the near-miss version of this: the
 * script's own header records that an 8x `SPEEDUP` smoke run implied 112 m/s,
 * tripped the plausibility gate on 90 of 466 fixes, and produced a deviation and
 * a reroute the 1x run does not have. The bound below is what would have caught
 * it at generation time instead of at interpretation time.
 *
 * ## The two directions
 *
 * A nominal trace must be physically plausible. A **faulted** trace must
 * actually contain its fault — an adversarial scenario that stopped violating
 * physics is a test that no longer tests anything, and it reports green while
 * doing it. Both directions are asserted, per trace, against the same list the
 * exporter writes ([TraceCorpus]).
 */
class TraceContractTest {

    private val corpus = TraceCorpus.all()

    /** Qatar, generously. A fix outside this is a projection or axis mistake. */
    private val latRange = 24.3..26.3
    private val lngRange = 50.5..51.8

    /**
     * The fastest step a road vehicle may imply, in m/s.
     *
     * 45 m/s is 162 km/h — above the 110–120 km/h the corpus sustains on the
     * expressway, and far below the 112 m/s an accidental time compression
     * produces. It is deliberately a bound on the *implied* speed rather than on
     * the reported one: the reported field is data the simulator chose to write,
     * while the implied speed is what the app's own plausibility gate will
     * compute from two consecutive fixes.
     */
    private val maxImpliedMs = 45.0

    /** The longest gap between fixes, for a trace that did not inject one. */
    private val maxGapS = 2.0

    private fun haversineM(a: LngLat, b: LngLat): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** Compass course from a to b, in degrees, matching the app's convention. */
    private fun courseDeg(a: LngLat, b: LngLat): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)

    /** The largest speed any pair of consecutive fixes implies. */
    private fun worstImplied(t: TraceCorpus.Trace): Double =
        (1 until t.fixes.size).maxOf { i ->
            val dt = (t.fixes[i].tMs - t.fixes[i - 1].tMs) / 1000.0
            if (dt <= 0) Double.MAX_VALUE
            else haversineM(t.fixes[i - 1].position, t.fixes[i].position) / dt
        }

    private fun longestGapS(t: TraceCorpus.Trace): Double =
        (1 until t.fixes.size).maxOf { i ->
            (t.fixes[i].tMs - t.fixes[i - 1].tMs) / 1000.0
        }

    // ---- the corpus itself --------------------------------------------------

    @Test
    fun `the corpus is not empty and every name is distinct`() {
        assertTrue("no traces to replay", corpus.size >= 10)
        assertEquals(
            "two traces share a name, so one silently overwrites the other",
            corpus.size, corpus.map { it.name }.toSet().size,
        )
    }

    @Test
    fun `every trace names its destination`() {
        for (t in corpus) {
            val p = t.destination.split(",")
            assertEquals("${t.name}: destination is not `lat,lng`", 2, p.size)
            val lat = p[0].toDouble(); val lng = p[1].toDouble()
            assertTrue("${t.name}: destination lat $lat is outside Qatar", lat in latRange)
            assertTrue("${t.name}: destination lng $lng is outside Qatar", lng in lngRange)
        }
    }

    // ---- structure, for every trace ----------------------------------------

    @Test
    fun `every trace is long enough to be worth replaying`() {
        for (t in corpus) {
            assertTrue("${t.name} has only ${t.fixes.size} fixes", t.fixes.size >= 20)
            assertTrue("${t.name} lasts only ${t.fixes.last().tMs / 1000}s",
                       t.fixes.last().tMs >= 20_000)
        }
    }

    @Test
    fun `time only ever moves forward, and starts at zero`() {
        for (t in corpus) {
            assertEquals("${t.name} does not start at t=0", 0L, t.fixes.first().tMs)
            for (i in 1 until t.fixes.size) {
                assertTrue(
                    "${t.name}: fix $i goes backwards, ${t.fixes[i - 1].tMs} -> ${t.fixes[i].tMs}",
                    t.fixes[i].tMs > t.fixes[i - 1].tMs,
                )
            }
        }
    }

    @Test
    fun `every fix is a finite position inside Qatar`() {
        for (t in corpus) {
            for ((i, f) in t.fixes.withIndex()) {
                for ((what, v) in listOf("lat" to f.position.lat, "lng" to f.position.lng,
                                         "truthLat" to f.truth.lat, "truthLng" to f.truth.lng)) {
                    assertTrue("${t.name} fix $i: $what is not finite ($v)", v.isFinite())
                }
                assertTrue("${t.name} fix $i: lat ${f.position.lat} outside Qatar",
                           f.position.lat in latRange)
                assertTrue("${t.name} fix $i: lng ${f.position.lng} outside Qatar",
                           f.position.lng in lngRange)
                assertTrue("${t.name} fix $i: accuracy ${f.accuracyM} is not positive",
                           f.accuracyM > 0.0 && f.accuracyM.isFinite())
            }
        }
    }

    @Test
    fun `a reported bearing is a compass bearing, or absent`() {
        for (t in corpus) {
            for ((i, f) in t.fixes.withIndex()) {
                f.bearingDeg?.let {
                    assertTrue("${t.name} fix $i: bearing $it is not in [0,360)", it >= 0 && it < 360)
                }
                f.speedMs?.let {
                    assertTrue("${t.name} fix $i: speed $it is negative", it >= 0)
                    assertTrue("${t.name} fix $i: speed $it m/s is not a road vehicle", it < 60.0)
                }
            }
        }
    }

    /**
     * The exporter's format, read back the way the device reads it.
     *
     * `MockDrive` splits on `,`, needs six fields, and treats an empty speed or
     * bearing as "the receiver reported none" — a different fact from zero, and
     * the one the format exists to be able to express. This asserts the writer
     * and that reader agree, without a device: a trace whose empty fields became
     * `0.0` would make the device run silently unable to reproduce the
     * has-speed and has-bearing paths.
     */
    @Test
    fun `the written format is the format the device parses`() {
        for (t in corpus) {
            for ((i, f) in t.fixes.withIndex()) {
                val line = "%d,%.7f,%.7f,%s,%s,%.1f".format(
                    f.tMs, f.position.lng, f.position.lat,
                    f.speedMs?.let { "%.2f".format(it) } ?: "",
                    f.bearingDeg?.let { "%.1f".format(it) } ?: "",
                    f.accuracyM,
                )
                val p = line.split(',')
                assertEquals("${t.name} fix $i: not six fields", 6, p.size)
                assertEquals("${t.name} fix $i: time did not survive the round trip",
                             f.tMs, p[0].toLong())
                // Seven decimals of a longitude is ~1 cm; the wire format is a
                // deliberate rounding, so compare at that scale rather than
                // exactly.
                assertTrue("${t.name} fix $i: lng moved by more than a centimetre",
                           abs(p[1].toDouble() - f.position.lng) < 1e-6)
                assertTrue("${t.name} fix $i: lat moved by more than a centimetre",
                           abs(p[2].toDouble() - f.position.lat) < 1e-6)
                assertEquals("${t.name} fix $i: an absent speed became a number",
                             f.speedMs == null, p[3].isBlank())
                assertEquals("${t.name} fix $i: an absent bearing became a number",
                             f.bearingDeg == null, p[4].isBlank())
                assertEquals("${t.name} fix $i: accuracy did not survive",
                             f.accuracyM, p[5].toDouble(), 0.05)
            }
        }
    }

    // ---- physics, for the traces that are supposed to be physical -----------

    @Test
    fun `a nominal trace never implies a speed no car could reach`() {
        for (t in corpus.filterNot { it.faulted }) {
            val worst = worstImplied(t)
            assertTrue(
                "${t.name} implies %.1f m/s (%.0f km/h) between two fixes — a nominal " +
                    "trace has become a faulted one, or it was generated under SPEEDUP"
                        .format(worst, worst * 3.6),
                worst <= maxImpliedMs,
            )
        }
    }

    @Test
    fun `a nominal trace never has a gap that is not a gap`() {
        for (t in corpus.filterNot { it.faulted }) {
            val gap = longestGapS(t)
            assertTrue(
                "${t.name} has a ${"%.1f".format(gap)}s gap between fixes; only the " +
                    "outage scenario may contain one",
                gap <= maxGapS,
            )
        }
    }

    @Test
    fun `reported speed agrees with the distance actually covered`() {
        for (t in corpus.filterNot { it.faulted }) {
            val diffs = (1 until t.fixes.size).mapNotNull { i ->
                val sp = t.fixes[i].speedMs ?: return@mapNotNull null
                val dt = (t.fixes[i].tMs - t.fixes[i - 1].tMs) / 1000.0
                if (sp <= 2.0 || dt <= 0) return@mapNotNull null
                abs(sp - haversineM(t.fixes[i - 1].position, t.fixes[i].position) / dt)
            }.sorted()
            if (diffs.size < 20) continue
            val p90 = diffs[(diffs.size * 0.9).toInt()]
            // The simulator adds urban-canyon drift on purpose, so agreement is
            // statistical and not exact. Measured across the corpus the 90th
            // percentile is ~3 m/s; 8 m/s is the bound that catches a trace whose
            // speed column has stopped describing the movement.
            assertTrue("${t.name}: p90 speed error is %.1f m/s".format(p90), p90 <= 8.0)
        }
    }

    @Test
    fun `a reported bearing points where the vehicle went`() {
        for (t in corpus.filterNot { t -> t.faulted }) {
            val diffs = (1 until t.fixes.size).mapNotNull { i ->
                val br = t.fixes[i].bearingDeg ?: return@mapNotNull null
                val sp = t.fixes[i].speedMs ?: return@mapNotNull null
                if (sp <= 3.0) return@mapNotNull null
                val a = t.fixes[i - 1].position; val b = t.fixes[i].position
                if (haversineM(a, b) <= 5.0) return@mapNotNull null
                angleDiff(br, courseDeg(a, b))
            }.sorted()
            if (diffs.size < 20) continue
            val p90 = diffs[(diffs.size * 0.9).toInt()]
            // Drift and the turn-in-progress samples both live in the tail; the
            // measured 90th percentile across the corpus is ~15 degrees.
            assertTrue("${t.name}: p90 bearing error is %.1f deg".format(p90), p90 <= 60.0)
        }
    }

    // ---- the faults, which must still be faults ----------------------------

    @Test
    fun `the jump scenario still contains its jump`() {
        val t = corpus.single { it.name == "scenario-g-jump" }
        val worst = worstImplied(t)
        // 300 m reflected for 3 s at 1 Hz. If this ever stops being true, the
        // plausibility-gate path on the device is no longer being exercised and
        // the run would report a clean drive for a scenario that has no fault.
        assertTrue(
            "scenario-g-jump no longer contains an impossible step (worst %.1f m/s)".format(worst),
            worst > 100.0,
        )
    }

    @Test
    fun `the outage scenario still contains its outage`() {
        val t = corpus.single { it.name == "scenario-h-outage" }
        val gap = longestGapS(t)
        assertTrue(
            "scenario-h-outage no longer contains a silence (longest gap %.1fs)".format(gap),
            gap >= 20.0,
        )
    }

    // ---- the demo replay ---------------------------------------------------

    @Test
    fun `the city drive covers the whole route, in order`() {
        // `truthAlongM` is the distance along the route the vehicle is really
        // at, computed from the truth path rather than from the fixes, so it is
        // the fixture's own statement of progress. This is the trace the demo
        // recording replays; if it stops reaching the end, the recording shows a
        // drive that never arrives.
        val t = corpus.single { it.name == "scenario-a-city" }
        val along = t.fixes.map { it.truthAlongM }
        assertTrue("scenario-a-city starts away from the origin", along.first() < 50.0)
        for (i in 1 until along.size) {
            assertTrue(
                "scenario-a-city goes backwards at fix $i: ${along[i - 1]} -> ${along[i]}",
                along[i] >= along[i - 1] - 1.0,
            )
        }
        val end = along.last()
        assertTrue("scenario-a-city stops ${end.toInt()} m short of the destination", end > 5_000.0)
    }

    @Test
    fun `the same corpus is produced twice, identically`() {
        // Determinism is what makes a device result comparable to a JVM result
        // and to the next device result. Seeded simulation is only deterministic
        // if nothing in it reads a clock or a hash iteration order, and that is
        // exactly the kind of thing a refactor breaks silently.
        val a = TraceCorpus.all()
        val b = TraceCorpus.all()
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("${a[i].name}: different fix count", a[i].fixes.size, b[i].fixes.size)
            for (j in a[i].fixes.indices) {
                assertEquals("${a[i].name} fix $j: time differs", a[i].fixes[j].tMs, b[i].fixes[j].tMs)
                assertEquals("${a[i].name} fix $j: position differs",
                             a[i].fixes[j].position, b[i].fixes[j].position)
                assertEquals("${a[i].name} fix $j: bearing differs",
                             a[i].fixes[j].bearingDeg, b[i].fixes[j].bearingDeg)
            }
        }
    }
}
