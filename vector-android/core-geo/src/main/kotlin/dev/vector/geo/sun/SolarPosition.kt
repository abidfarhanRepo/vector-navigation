package dev.vector.geo.sun

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, as seen from a point on the ground.
 *
 * [altitudeDeg] is degrees above the horizon — negative when the sun is down.
 * [azimuthDeg] is the compass direction of the sun, degrees clockwise from
 * north, matching [dev.vector.geo.RouteGeometry.bearingDeg] exactly so the two
 * can be subtracted without a conversion step.
 */
data class SolarPos(
    val altitudeDeg: Double,
    val azimuthDeg: Double,
) {
    /** Is the sun above the horizon at all? */
    val isDaylight: Boolean get() = altitudeDeg > 0.0
}

/**
 * Solar position by the NOAA algorithm. Pure, deterministic, offline.
 *
 * A deterministic function of (lat, lng, epochMs) and nothing else: no clock,
 * no network, no almanac file. That is what lets the shade model run on a phone
 * with no signal, and it is also a privacy property — the server never learns
 * when you plan to walk where, because the server is never asked.
 *
 * ## Why the longitude correction is the part that matters
 *
 * The naive version of this calculation uses civil time and a timezone offset,
 * and it is wrong by however far the user sits from their timezone meridian.
 * Doha is at 51.53 degE; the UTC+3 meridian is 45 degE. That is 6.53 deg east, and at
 * four minutes per degree it puts local solar noon at about **12:22 civil
 * time, not 12:00**. A model that assumes noon is noon puts the sun 22 minutes
 * out of position all day, which is roughly 5.5 deg of azimuth — enough to flip
 * which side of a north-south street is claimed to be shaded.
 *
 * This implementation sidesteps timezones entirely: it works in UTC and applies
 * the longitude correction directly (`4 * lng` minutes), so there is no
 * timezone database to be stale and no offset for a caller to get wrong.
 *
 * ## Refraction
 *
 * The atmosphere bends sunlight, so the sun is visible slightly before it has
 * geometrically risen. The NOAA refraction correction is applied to
 * [SolarPos.altitudeDeg]; it is about 0.5 deg at the horizon and negligible
 * (<0.01 deg) above 60 deg. It is included because the low-sun cases are exactly
 * the ones the shade model is most sensitive to — `height / tan(altitude)`
 * diverges as altitude approaches zero — so the altitude fed into that division
 * should be the one an observer would actually measure.
 */
object SolarPosition {

    private const val DEG = Math.PI / 180.0
    private const val MS_PER_DAY = 86_400_000.0

    /** Julian Day of the Unix epoch, 1970-01-01T00:00:00Z. */
    private const val JD_UNIX_EPOCH = 2_440_587.5

    /**
     * Sun position for a point and an instant.
     *
     * @param lat degrees north, negative south.
     * @param lng degrees east, negative west.
     * @param epochMs milliseconds since the Unix epoch, UTC.
     */
    fun at(lat: Double, lng: Double, epochMs: Long): SolarPos {
        val jd = epochMs / MS_PER_DAY + JD_UNIX_EPOCH
        val t = (jd - 2_451_545.0) / 36_525.0          // Julian centuries since J2000.0

        // --- the sun's position in its own orbit -----------------------------
        val meanLong = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnom = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val eccent = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val centre = sin(meanAnom * DEG) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * meanAnom * DEG) * (0.019993 - 0.000101 * t) +
            sin(3 * meanAnom * DEG) * 0.000289
        val trueLong = meanLong + centre

        // Apparent longitude: the true longitude corrected for nutation and
        // aberration. `omega` is the ascending node of the Moon's orbit.
        val omega = 125.04 - 1_934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(omega * DEG)

        // --- the tilt of the Earth -------------------------------------------
        val meanObliq = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = meanObliq + 0.00256 * cos(omega * DEG)

        val declin = asin(sin(obliq * DEG) * sin(appLong * DEG)) / DEG

        // --- equation of time: true solar time minus mean solar time ---------
        // The Earth's orbit is elliptical and its axis is tilted, so a sundial
        // and a clock disagree by up to about 16 minutes over the year.
        val y = tan(obliq / 2 * DEG) * tan(obliq / 2 * DEG)
        val eqTimeMin = 4.0 / DEG * (
            y * sin(2 * meanLong * DEG) -
                2 * eccent * sin(meanAnom * DEG) +
                4 * eccent * y * sin(meanAnom * DEG) * cos(2 * meanLong * DEG) -
                0.5 * y * y * sin(4 * meanLong * DEG) -
                1.25 * eccent * eccent * sin(2 * meanAnom * DEG)
            )

        // --- from time to hour angle -----------------------------------------
        // UTC minutes past midnight, plus the equation of time, plus the
        // longitude correction at 4 minutes per degree east. No timezone.
        val utcMinutes = (epochMs.mod(86_400_000L)) / 60_000.0
        val trueSolarMin = (utcMinutes + eqTimeMin + 4.0 * lng).mod(1440.0)
        val hourAngle = if (trueSolarMin / 4.0 < 0) trueSolarMin / 4.0 + 180.0 else trueSolarMin / 4.0 - 180.0

        // --- to the observer's sky -------------------------------------------
        val cosZenith = (sin(lat * DEG) * sin(declin * DEG) +
            cos(lat * DEG) * cos(declin * DEG) * cos(hourAngle * DEG)).coerceIn(-1.0, 1.0)
        val zenith = acos(cosZenith) / DEG
        val geomAltitude = 90.0 - zenith

        val azimuth = azimuthDeg(lat, declin, zenith, hourAngle)

        return SolarPos(
            altitudeDeg = geomAltitude + refractionDeg(geomAltitude),
            azimuthDeg = azimuth,
        )
    }

    /**
     * Compass bearing of the sun, clockwise from north.
     *
     * Degenerate at the poles and when the sun is exactly overhead, where the
     * denominator vanishes and azimuth is genuinely undefined; both fall back to
     * due south, which is the limiting value everywhere this app is used and is
     * never reached in Qatar (the sun's maximum altitude at Doha is ~88 deg).
     */
    private fun azimuthDeg(lat: Double, declin: Double, zenith: Double, hourAngle: Double): Double {
        val denom = cos(lat * DEG) * sin(zenith * DEG)
        if (kotlin.math.abs(denom) < 1e-9) return 180.0
        val cosAz = ((sin(lat * DEG) * cos(zenith * DEG)) - sin(declin * DEG)) / denom
        val az = acos(cosAz.coerceIn(-1.0, 1.0)) / DEG
        return if (hourAngle > 0) (az + 180.0).mod(360.0) else (540.0 - az).mod(360.0)
    }

    /**
     * Atmospheric refraction in degrees, by the NOAA piecewise fit.
     *
     * Largest at the horizon (~0.57 deg) and effectively zero high in the sky.
     */
    private fun refractionDeg(altitudeDeg: Double): Double {
        if (altitudeDeg > 85.0) return 0.0
        val te = tan(altitudeDeg * DEG)
        val arcsec = when {
            altitudeDeg > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            altitudeDeg > -0.575 -> 1_735.0 + altitudeDeg *
                (-518.2 + altitudeDeg * (103.4 + altitudeDeg * (-12.79 + altitudeDeg * 0.711)))
            else -> -20.772 / te
        }
        return arcsec / 3_600.0
    }
}
