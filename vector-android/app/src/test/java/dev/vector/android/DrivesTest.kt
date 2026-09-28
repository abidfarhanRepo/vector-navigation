package dev.vector.android

import androidx.test.core.app.ApplicationProvider
import dev.vector.geo.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The journey history, and the fact that it survives.
 *
 * [Drives] is the store V6 §13.D asks for: *"The user should be able to reopen
 * Vector later and still see their previous drives."* Before it, `arrive()`
 * built an [ArrivalSummary] out of every number the drive had produced, put it
 * on screen once, and dropped all of it the moment the card was dismissed.
 *
 * These cases are weighted towards two things rather than towards round trips:
 *
 * * **Ordering and retention**, because they are the properties a driver can
 *   actually see being wrong.
 * * **Malformed and partial records**, because §14 requires a persistence
 *   failure to degrade rather than cascade — and a history is the one store
 *   here whose records were written by older builds of the app.
 */
@RunWith(RobolectricTestRunner::class)
class DrivesTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = Settings.prefs(ApplicationProvider.getApplicationContext())
        prefs.edit().clear().commit()
    }

    private fun drive(
        name: String = "West Bay",
        endedAtMs: Long = 1_700_000_000_000L,
        startedAtMs: Long = endedAtMs - 600_000L,
        distanceM: Double = 8_000.0,
        durationS: Double = 600.0,
        plannedS: Double? = 600.0,
        completed: Boolean = true,
        startLng: Double? = 51.53,
        startLat: Double? = 25.28,
    ) = Drives.Drive(
        destination = name,
        destLng = 51.4986,
        destLat = 25.3208,
        startLng = startLng,
        startLat = startLat,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        distanceM = distanceM,
        durationS = durationS,
        plannedS = plannedS,
        completed = completed,
    )

    // ---- the basic promise -------------------------------------------------

    @Test
    fun `a fresh install has no history`() {
        assertEquals(emptyList<Drives.Drive>(), Drives.load(prefs))
    }

    @Test
    fun `a drive round-trips every field`() {
        val d = drive()
        Drives.add(prefs, d)
        val back = Drives.load(prefs).single()
        assertEquals(d.destination, back.destination)
        assertEquals(d.destLng, back.destLng, 1e-9)
        assertEquals(d.destLat, back.destLat, 1e-9)
        assertEquals(d.startLng!!, back.startLng!!, 1e-9)
        assertEquals(d.startLat!!, back.startLat!!, 1e-9)
        assertEquals(d.startedAtMs, back.startedAtMs)
        assertEquals(d.endedAtMs, back.endedAtMs)
        assertEquals(d.distanceM, back.distanceM, 1e-6)
        assertEquals(d.durationS, back.durationS, 1e-6)
        assertEquals(d.plannedS!!, back.plannedS!!, 1e-6)
        assertEquals(d.completed, back.completed)
    }

    @Test
    fun `coordinates survive at full precision`() {
        // Unlike Recents, which rounds to 5 dp deliberately. A history entry is
        // re-drivable — tapping it routes there — so it keeps what the router
        // was actually given rather than a re-rounded version of it.
        Drives.add(prefs, drive().copy(destLng = 51.498612345, destLat = 25.320787654))
        val back = Drives.load(prefs).single()
        assertEquals(51.498612345, back.destLng, 1e-9)
        assertEquals(25.320787654, back.destLat, 1e-9)
    }

    // ---- ordering and retention -------------------------------------------

    @Test
    fun `the newest drive is first`() {
        Drives.add(prefs, drive(name = "older", endedAtMs = 1_000L))
        Drives.add(prefs, drive(name = "newer", endedAtMs = 2_000L))
        assertEquals(listOf("newer", "older"), Drives.load(prefs).map { it.destination })
    }

    @Test
    fun `order comes from when the drive ended, not from the order it was written`() {
        // A history merged across an upgrade, or written either side of a clock
        // change, would otherwise present an order the driver cannot explain.
        // `load` sorts rather than trusting the stored sequence.
        Drives.add(prefs, drive(name = "second", endedAtMs = 2_000L))
        Drives.add(prefs, drive(name = "first", endedAtMs = 1_000L))
        assertEquals(listOf("second", "first"), Drives.load(prefs).map { it.destination })
    }

    @Test
    fun `the history is bounded`() {
        for (i in 0 until Drives.LIMIT + 5) {
            Drives.add(prefs, drive(name = "drive $i", endedAtMs = 1_000L + i))
        }
        assertEquals(Drives.LIMIT, Drives.load(prefs).size)
    }

    @Test
    fun `it is the oldest drives that fall off`() {
        for (i in 0 until Drives.LIMIT + 1) {
            Drives.add(prefs, drive(name = "drive $i", endedAtMs = 1_000L + i))
        }
        val names = Drives.load(prefs).map { it.destination }
        assertEquals("drive ${Drives.LIMIT}", names.first())
        assertTrue("the oldest drive should have aged out", "drive 0" !in names)
    }

    @Test
    fun `driving to the same place twice is two drives`() {
        // The opposite of Recents, which de-duplicates by position. A history
        // that collapsed a daily commute into one row would not be a history.
        Drives.add(prefs, drive(name = "Work", endedAtMs = 1_000L))
        Drives.add(prefs, drive(name = "Work", endedAtMs = 2_000L))
        assertEquals(2, Drives.load(prefs).size)
    }

    // ---- deletion ----------------------------------------------------------

    @Test
    fun `one drive can be forgotten without disturbing the others`() {
        Drives.add(prefs, drive(name = "keep me", endedAtMs = 1_000L))
        Drives.add(prefs, drive(name = "remove me", endedAtMs = 2_000L))
        Drives.add(prefs, drive(name = "keep me too", endedAtMs = 3_000L))
        Drives.remove(prefs, 2_000L)
        assertEquals(
            listOf("keep me too", "keep me"),
            Drives.load(prefs).map { it.destination },
        )
    }

    @Test
    fun `removing a drive that is not there changes nothing`() {
        Drives.add(prefs, drive(endedAtMs = 1_000L))
        Drives.remove(prefs, 9_999L)
        assertEquals(1, Drives.load(prefs).size)
    }

    @Test
    fun `clearing removes everything`() {
        Drives.add(prefs, drive(endedAtMs = 1_000L))
        Drives.add(prefs, drive(endedAtMs = 2_000L))
        Drives.clear(prefs)
        assertEquals(emptyList<Drives.Drive>(), Drives.load(prefs))
    }

    // ---- unavailable is a value -------------------------------------------

    @Test
    fun `a drive started before the first fix has no start position`() {
        // §13.D: "represent it as unavailable rather than inventing it". The
        // wrong answer here is (0, 0), which is a real place in the Atlantic.
        Drives.add(prefs, drive(startLng = null, startLat = null))
        val back = Drives.load(prefs).single()
        assertNull(back.startLng)
        assertNull(back.startLat)
    }

    @Test
    fun `a drive with no prediction withholds a verdict rather than comparing against zero`() {
        val d = drive(plannedS = null, durationS = 900.0)
        Drives.add(prefs, d)
        val back = Drives.load(prefs).single()
        assertNull(back.plannedS)
        // The bug this prevents: `durationS - 0` is the whole drive, so every
        // journey with no prediction would be reported as fifteen minutes over.
        assertNull(back.verdict)
    }

    @Test
    fun `an abandoned drive is never reported as quicker than predicted`() {
        // It is, arithmetically — it stopped early. Saying so would credit the
        // router for a journey nobody finished.
        val d = drive(completed = false, durationS = 120.0, plannedS = 900.0)
        assertNull(d.verdict)
    }

    @Test
    fun `a drive within the honest margin is as predicted`() {
        assertEquals("as predicted", drive(durationS = 630.0, plannedS = 600.0).verdict)
    }

    @Test
    fun `a drive well over its prediction says so`() {
        assertEquals("5 min over", drive(durationS = 900.0, plannedS = 600.0).verdict)
    }

    @Test
    fun `a drive well under its prediction says so`() {
        assertEquals("5 min under", drive(durationS = 600.0, plannedS = 900.0).verdict)
    }

    // ---- failing safely ----------------------------------------------------

    @Test
    fun `a preferences value that is not JSON reads as an empty history`() {
        // Not a crash. §14: a persistence failure must never mean a permanent
        // inability to launch, and this store is read during onCreate.
        prefs.edit().putString("drives", "this is not json").commit()
        assertEquals(emptyList<Drives.Drive>(), Drives.load(prefs))
    }

    @Test
    fun `a value that is valid JSON but not an array reads as empty`() {
        prefs.edit().putString("drives", """{"name":"West Bay"}""").commit()
        assertEquals(emptyList<Drives.Drive>(), Drives.load(prefs))
    }

    @Test
    fun `one malformed record costs that record and not the list`() {
        // The reason this store is JSON and not a delimited string. With a
        // positional format a single bad field shifts every field after it, so
        // one corrupt entry takes the whole history; here it takes itself.
        prefs.edit().putString(
            "drives",
            """[{"name":"good","dlng":51.5,"dlat":25.3,"t1":2000,"done":true},
                {"name":"no end time"},
                {"name":"also good","dlng":51.6,"dlat":25.4,"t1":1000,"done":true}]"""
        ).commit()
        assertEquals(listOf("good", "also good"), Drives.load(prefs).map { it.destination })
    }

    @Test
    fun `a record with no destination coordinate is dropped`() {
        // It could not be re-driven from the list, and a row that cannot be
        // tapped is the dead UI §6 rules out.
        prefs.edit().putString("drives", """[{"name":"nowhere","t1":2000}]""").commit()
        assertEquals(emptyList<Drives.Drive>(), Drives.load(prefs))
    }

    @Test
    fun `a record written by an older build keeps what it has`() {
        // Forward compatibility in the direction that actually happens: a
        // record from a build that did not yet store the start position or the
        // prediction. Those two read as unavailable; nothing else is lost.
        prefs.edit().putString(
            "drives",
            """[{"name":"Msheireb","dlng":51.52,"dlat":25.29,"t0":1000,"t1":2000,
                 "dist":1200.0,"dur":300.0,"done":true}]"""
        ).commit()
        val back = Drives.load(prefs).single()
        assertEquals("Msheireb", back.destination)
        assertEquals(1200.0, back.distanceM, 1e-6)
        assertNull(back.startLng)
        assertNull(back.plannedS)
    }

    @Test
    fun `a record with no name is still listed under something`() {
        prefs.edit().putString(
            "drives",
            """[{"dlng":51.52,"dlat":25.29,"t1":2000,"done":true}]"""
        ).commit()
        assertEquals("Unnamed destination", Drives.load(prefs).single().destination)
    }

    // ---- the row a driver reads -------------------------------------------

    @Test
    fun `the summary line carries when, how far, and how long`() {
        val now = 1_700_000_000_000L
        val line = driveSummaryLine(
            drive(endedAtMs = now, distanceM = 8_000.0, durationS = 600.0, plannedS = 600.0),
            Units.METRIC, nowMs = now, zone = java.util.TimeZone.getTimeZone("UTC"),
        )
        assertTrue(line, line.startsWith("Today "))
        assertTrue(line, "8.0 km" in line)
        assertTrue(line, "10 min" in line)
    }

    @Test
    fun `an abandoned drive says it was stopped`() {
        val now = 1_700_000_000_000L
        val line = driveSummaryLine(
            drive(endedAtMs = now, completed = false, distanceM = 2_000.0, durationS = 300.0),
            Units.METRIC, nowMs = now, zone = java.util.TimeZone.getTimeZone("UTC"),
        )
        // Without this the log would look like it was lying: a 2 km entry for a
        // 40 km journey to the airport is not a wrong number, it is a drive
        // that was given up on.
        assertTrue(line, line.endsWith("stopped"))
    }

    @Test
    fun `a drive that matched its prediction does not brag about it`() {
        val now = 1_700_000_000_000L
        val line = driveSummaryLine(
            drive(endedAtMs = now, durationS = 610.0, plannedS = 600.0),
            Units.METRIC, nowMs = now, zone = java.util.TimeZone.getTimeZone("UTC"),
        )
        assertTrue(line, "as predicted" !in line)
    }

    @Test
    fun `yesterday is a calendar day, not twenty-four hours`() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc)
        // Drove at 23:50, looking at the log at 00:10. Twenty minutes of
        // elapsed time; two different days, and "Today 23:50" would be wrong.
        cal.set(2026, 8, 8, 23, 50, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val drove = cal.timeInMillis
        cal.set(2026, 8, 9, 0, 10, 0)
        val now = cal.timeInMillis
        assertEquals("Yesterday 23:50", driveWhen(drove, now, utc))
    }

    @Test
    fun `today is today`() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc)
        cal.set(2026, 8, 9, 8, 5, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val drove = cal.timeInMillis
        cal.set(2026, 8, 9, 18, 30, 0)
        assertEquals("Today 08:05", driveWhen(drove, cal.timeInMillis, utc))
    }

    @Test
    fun `anything older is an absolute date`() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc)
        cal.set(2026, 8, 3, 14, 32, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val drove = cal.timeInMillis
        cal.set(2026, 8, 9, 9, 0, 0)
        val out = driveWhen(drove, cal.timeInMillis, utc)
        // "3 days ago" would make the driver do arithmetic to answer "was that
        // the trip on the Tuesday".
        assertTrue(out, out.startsWith("3 "))
        assertTrue(out, out.endsWith(" 14:32"))
    }

    @Test
    fun `a drive from last year is not yesterday`() {
        // The day-of-year comparison is only meaningful inside one year: 1 Jan
        // is day 1 and 31 Dec is day 365, so a naive difference would call a
        // drive from New Year's Eve "364 days" or, worse, land on 1.
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc)
        cal.set(2025, 11, 31, 23, 0, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        val drove = cal.timeInMillis
        cal.set(2026, 0, 1, 1, 0, 0)
        val out = driveWhen(drove, cal.timeInMillis, utc)
        assertTrue(out, "Yesterday" !in out && "Today" !in out)
    }

    @Test
    fun `a drive under half a minute says so rather than rounding up to one`() {
        assertEquals("under a minute", driveDuration(20.0))
    }

    @Test
    fun `a long drive is stated in hours`() {
        // "83 min" is what the trip bar used to say, and nobody says that.
        assertEquals("1 h 23 min", driveDuration(83.0 * 60))
        assertEquals("2 h", driveDuration(120.0 * 60))
    }

    // ---- durability --------------------------------------------------------

    @Test
    fun `a recorded drive is on disk before the call returns`() {
        // The §14 property, asserted against the actual file rather than
        // against the in-memory map.
        //
        // `apply()` and `commit()` are indistinguishable within one process:
        // both update the map immediately and every later `load` sees the new
        // value either way, which is exactly why this store used `apply()`
        // everywhere and why the gap was invisible. The difference only shows
        // when the process dies before the background write lands — "app killed
        // immediately after selecting a destination" — so the assertion has to
        // reach past the map to the bytes.
        Drives.add(prefs, drive(name = "Villaggio", endedAtMs = 1_234_000L))
        val file = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().dataDir,
            "shared_prefs/vector.xml",
        )
        assertTrue("preferences file should exist", file.exists())
        assertTrue("the drive should already be in the file", "Villaggio" in file.readText())
    }

    // ---- the recorded track (the road actually driven) ---------------------

    @Test
    fun `a drive round-trips the track it was recorded with`() {
        // The history's job is to answer "which way did I go", and it can only
        // do that if the shape survives the preferences file. See
        // `Drives.Drive.track`.
        val track = listOf(
            dev.vector.geo.LngLat(51.5310, 25.2866),
            dev.vector.geo.LngLat(51.5288, 25.2901),
            dev.vector.geo.LngLat(51.5240, 25.2955),
        )
        Drives.add(prefs, driveWith(track))
        val back = Drives.load(prefs).single().track
        assertEquals(3, back.size)
        assertEquals(51.5288, back[1].lng, 1e-9)
        assertEquals(25.2955, back[2].lat, 1e-9)
    }

    @Test
    fun `a drive recorded before tracks existed still loads`() {
        // Every drive already in a driver's history has no "trk" key. Reading
        // one must give an empty track and a complete drive, not a null row —
        // the row then falls back to the category tile and the list is intact.
        Drives.add(prefs, driveWith(emptyList()))
        val back = Drives.load(prefs).single()
        assertTrue(back.track.isEmpty())
        assertEquals("Villaggio Mall", back.destination)
    }

    @Test
    fun `a truncated track costs the broken point and not the drive`() {
        // The read path is tolerant everywhere else in this file for the same
        // reason: a storage failure must cost the smallest possible unit. An
        // odd-length array is a write cut mid-pair.
        Drives.add(prefs, driveWith(listOf(dev.vector.geo.LngLat(51.53, 25.28))))
        val raw = prefs.getString("drives", "")!!
        // Drop the final latitude, leaving a dangling longitude.
        val broken = raw.replace("[51.53,25.28]", "[51.53]")
        prefs.edit().putString("drives", broken).commit()
        val back = Drives.load(prefs)
        assertEquals("the drive survives", 1, back.size)
    }

    private fun driveWith(track: List<dev.vector.geo.LngLat>) = Drives.Drive(
        destination = "Villaggio Mall",
        destLng = 51.4986, destLat = 25.3208,
        startLng = 51.53, startLat = 25.28,
        startedAtMs = 1_000L, endedAtMs = 2_000L,
        distanceM = 8_000.0, durationS = 600.0, plannedS = 600.0,
        completed = true,
        track = track,
    )
}
