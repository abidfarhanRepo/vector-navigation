package dev.vector.geo.sun

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The astronomy, checked against Doha.
 *
 * These values were cross-checked against an independent formulation (Spencer's
 * Fourier series for declination and the equation of time) before being written
 * down, rather than taken from the V7 plan — which matters, because the plan's
 * two stated times are wrong and this suite is what caught it. See
 * `solar noon at Doha is before noon, not after`.
 */
class SolarPositionTest {

    private companion object {
        const val DOHA_LAT = 25.2854
        const val DOHA_LNG = 51.5310

        /** Qatar is UTC+3 all year; there is no DST to model. */
        val QATAR: ZoneOffset = ZoneOffset.ofHours(3)

        fun localMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, QATAR).toInstant().toEpochMilli()
    }

    // ---- determinism -------------------------------------------------------

    @Test
    fun `the same inputs always give the same answer`() {
        val t = localMs(2026, 9, 13, 15, 0)
        val first = SolarPosition.at(DOHA_LAT, DOHA_LNG, t)
        repeat(50) {
            val again = SolarPosition.at(DOHA_LAT, DOHA_LNG, t)
            assertEquals(first.altitudeDeg, again.altitudeDeg, 0.0)
            assertEquals(first.azimuthDeg, again.azimuthDeg, 0.0)
        }
    }

    @Test
    fun `the answer does not depend on the machine's timezone`() {
        // The calculation works in UTC and applies the longitude correction
        // itself. If it ever reached for a default timezone this would fail on
        // somebody's laptop and nowhere else.
        val t = localMs(2026, 9, 13, 15, 0)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = SolarPosition.at(DOHA_LAT, DOHA_LNG, t)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val la = SolarPosition.at(DOHA_LAT, DOHA_LNG, t)
            assertEquals(utc.altitudeDeg, la.altitudeDeg, 0.0)
            assertEquals(utc.azimuthDeg, la.azimuthDeg, 0.0)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ---- Doha, at latitude scale ------------------------------------------

    @Test
    fun `solar noon altitude at Doha matches the published figure`() {
        // The V7 plan's one correct number: 68.4 deg. Independently, the
        // solar-noon altitude is 90 - |lat - declination|, and the declination
        // on 13 Sep 2026 is about +4.1 deg, giving 68.8 deg. Both inside the
        // half-degree band.
        val noon = localMs(2026, 9, 13, 11, 30)
        val sun = SolarPosition.at(DOHA_LAT, DOHA_LNG, noon)
        assertEquals(68.4, sun.altitudeDeg, 0.5)
    }

    @Test
    fun `solar noon at Doha is before noon, not after`() {
        // The V7 plan says local solar noon is ~12:22 and warns that getting it
        // wrong shifts every shade estimate by 22 minutes. The sign is
        // backwards: Doha is 6.5 deg EAST of the UTC+3 meridian, and being east
        // means the sun arrives EARLIER. 6.53 deg x 4 min/deg = 26 min early,
        // less another 4 min for the equation of time, which puts solar noon at
        // about 11:30 -- 52 minutes from where the plan puts it.
        var bestMs = 0L
        var bestAlt = -90.0
        for (minute in 0 until 24 * 60) {
            val t = localMs(2026, 9, 13, 0, 0) + minute * 60_000L
            val alt = SolarPosition.at(DOHA_LAT, DOHA_LNG, t).altitudeDeg
            if (alt > bestAlt) { bestAlt = alt; bestMs = t }
        }
        val minutesLocal = ((bestMs / 60_000L) % 1440L + 180L) % 1440L
        assertEquals((11 * 60 + 30).toDouble(), minutesLocal.toDouble(), 5.0)
        assertTrue(minutesLocal < 12 * 60, "solar noon must fall before 12:00 local at Doha")
    }

    @Test
    fun `sunrise at Doha in September is soon after five, not after six`() {
        // The plan says ~06:15. Measured here and cross-checked against the
        // standard -0.833 deg hour-angle formula: ~05:21.
        var riseMinute = -1
        var previous = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 9, 13, 0, 0)).altitudeDeg
        for (minute in 1 until 24 * 60) {
            val alt = SolarPosition.at(
                DOHA_LAT, DOHA_LNG, localMs(2026, 9, 13, 0, 0) + minute * 60_000L
            ).altitudeDeg
            if (previous < 0.0 && alt >= 0.0) { riseMinute = minute; break }
            previous = alt
        }
        assertTrue(riseMinute > 0, "the sun must rise")
        assertEquals((5 * 60 + 21).toDouble(), riseMinute.toDouble(), 10.0)
    }

    @Test
    fun `the sun is east in the morning and west in the afternoon`() {
        val morning = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 9, 13, 9, 0))
        val afternoon = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 9, 13, 15, 0))
        assertEquals(114.7, morning.azimuthDeg, 1.0)
        assertEquals(255.8, afternoon.azimuthDeg, 1.0)
        assertTrue(morning.azimuthDeg < 180.0, "morning sun is east of south")
        assertTrue(afternoon.azimuthDeg > 180.0, "afternoon sun is west of south")
    }

    @Test
    fun `the sun passes close to due south at solar noon`() {
        val sun = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 9, 13, 11, 30))
        // Doha is north of the Tropic of Cancer, so in September the midday sun
        // is always in the southern sky.
        assertTrue(abs(sun.azimuthDeg - 180.0) < 12.0, "azimuth was ${sun.azimuthDeg}")
    }

    // ---- night and the seasons --------------------------------------------

    @Test
    fun `the sun is below the horizon at local midnight`() {
        val sun = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 9, 13, 0, 0))
        assertTrue(sun.altitudeDeg < 0.0, "altitude was ${sun.altitudeDeg}")
        assertTrue(!sun.isDaylight)
    }

    @Test
    fun `midsummer noon is higher than midwinter noon`() {
        val june = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 6, 21, 11, 30)).altitudeDeg
        val december = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(2026, 12, 21, 11, 45)).altitudeDeg
        // At 25.3 deg N: ~88 deg in June, ~41 deg in December.
        assertEquals(88.0, june, 1.5)
        assertEquals(41.2, december, 1.5)
        assertTrue(june > december)
    }

    @Test
    fun `the southern hemisphere sees the midday sun in the north`() {
        // Sanity that the azimuth formula is not quietly hardcoded for Qatar.
        val sydney = SolarPosition.at(-33.87, 151.21, localMs(2026, 12, 21, 4, 0))
        assertTrue(sydney.isDaylight)
        assertTrue(
            sydney.azimuthDeg < 60.0 || sydney.azimuthDeg > 300.0,
            "expected a northerly azimuth, got ${sydney.azimuthDeg}",
        )
    }

    @Test
    fun `altitude never leaves its range and azimuth never leaves the compass`() {
        var t = localMs(2026, 1, 1, 0, 0)
        repeat(365 * 4) {
            val sun = SolarPosition.at(DOHA_LAT, DOHA_LNG, t)
            assertTrue(sun.altitudeDeg in -91.0..91.0, "altitude ${sun.altitudeDeg}")
            assertTrue(sun.azimuthDeg in 0.0..360.0, "azimuth ${sun.azimuthDeg}")
            t += 6 * 3_600_000L
        }
    }

    @Test
    fun `an epoch before 1970 still resolves`() {
        // Negative epochMs must not fall through a truncating remainder.
        val sun = SolarPosition.at(DOHA_LAT, DOHA_LNG, localMs(1969, 7, 20, 12, 0))
        assertTrue(sun.altitudeDeg in -91.0..91.0)
        assertTrue(sun.azimuthDeg in 0.0..360.0)
    }
}
