package dev.vector.geo

import kotlin.math.roundToInt

/**
 * The distance and speed units a driver reads and hears.
 *
 * This lives in core-geo, next to [ManeuverAnnouncer], for one reason: the
 * screen formatter and the voice formatter must agree. Before this they were in
 * different modules with no shared notion of units at all — `shortDistance()`
 * in the Android layer and `ManeuverAnnouncer.spokenDistance()` in core-geo,
 * both hardcoded metric, neither aware of the other. Adding a units setting to
 * only one of them would produce a HUD reading "0.4 mi" while the voice says
 * "in 600 metres", which is worse than having no setting.
 *
 * ## Rounding is part of the unit, not a detail
 *
 * Both formatters round to values a driver can judge against what they can see.
 * "In 437 metres" is a machine talking. The metric ladder rounds to 10 m, then
 * 50 m, then 100 m, then tenths of a kilometre; the imperial one rounds to
 * yards in the same proportions and switches to miles at a quarter mile,
 * because "in 1,760 yards" is not something anyone says.
 *
 * ## Why imperial exists here at all
 *
 * Qatar is metric and the current bake is Qatar, so this setting does nothing
 * for today's only user. It is included because `VECTOR_REGION` is a bootstrap
 * parameter rather than a constant — the stack is designed to be pointed at
 * another country — and because a units toggle that silently applies to the
 * screen but not the voice is the exact class of half-wired feature the V2
 * audit spent its length on. Doing it across both surfaces at once is cheaper
 * than doing it twice.
 */
enum class Units {
    METRIC,
    IMPERIAL,
    ;

    /** Short label for a speed readout. */
    val speedLabel: String get() = if (this == METRIC) "km/h" else "mph"

    /** Convert metres per second into the displayed speed unit. */
    fun speed(metresPerSecond: Double): Int =
        (metresPerSecond * (if (this == METRIC) 3.6 else 2.236936)).roundToInt()

    /**
     * Convert a posted limit, which the backend always reports in km/h.
     *
     * `/speed` returns `maxspeed_kmh` regardless of the client's preference —
     * OSM's `maxspeed` is normalised to km/h during ingestion — so the
     * conversion belongs here rather than in the API client.
     */
    fun postedLimit(kmh: Int): Int =
        if (this == METRIC) kmh else (kmh * 0.621371).roundToInt()

    /**
     * Distance as it appears on screen.
     *
     * Deliberately terse: this goes in the maneuver card at 26sp, where a
     * driver reads it in the time they can spare from the road.
     */
    fun shortDistance(metres: Double): String = when (this) {
        METRIC -> when {
            metres >= 10_000 -> "${(metres / 1000).toInt()} km"
            metres >= 1_000 -> String.format("%.1f km", metres / 1000.0)
            metres >= 100 -> "${(metres / 50).toInt() * 50} m"
            else -> "${(metres / 10).toInt() * 10} m"
        }
        IMPERIAL -> {
            val feet = metres * 3.28084
            val miles = metres / 1609.344
            when {
                miles >= 10 -> "${miles.toInt()} mi"
                miles >= 0.25 -> String.format("%.1f mi", miles)
                feet >= 500 -> "${(feet / 100).toInt() * 100} ft"
                else -> "${(feet / 50).toInt() * 50} ft"
            }
        }
    }

    /**
     * Distance as it is spoken.
     *
     * Different from [shortDistance] on purpose: a screen can afford "0.4 km"
     * but a voice saying "zero point four kilometres" is worse than "four
     * hundred metres", and the unit is written out because a TTS engine reads
     * "km" inconsistently across voices.
     */
    fun spokenDistance(metres: Double): String = when (this) {
        METRIC -> when {
            metres >= 1000 -> {
                val km = metres / 1000.0
                if (km >= 10) "${km.roundToInt()} kilometres"
                else "${(Math.round(km * 10) / 10.0)} kilometres"
            }
            metres >= 500 -> "${(metres / 100).roundToInt() * 100} metres"
            metres >= 100 -> "${(metres / 50).roundToInt() * 50} metres"
            else -> "${(metres / 10).roundToInt() * 10} metres"
        }
        IMPERIAL -> {
            val miles = metres / 1609.344
            val feet = metres * 3.28084
            when {
                miles >= 10 -> "${miles.roundToInt()} miles"
                miles >= 0.25 -> {
                    val m = Math.round(miles * 10) / 10.0
                    if (m == 1.0) "1 mile" else "$m miles"
                }
                feet >= 500 -> "${(feet / 100).roundToInt() * 100} feet"
                else -> "${(feet / 50).roundToInt() * 50} feet"
            }
        }
    }
}
