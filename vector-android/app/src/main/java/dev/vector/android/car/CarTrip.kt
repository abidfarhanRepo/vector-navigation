package dev.vector.android.car

import androidx.car.app.model.CarColor
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.compose.ui.graphics.toArgb
import dev.vector.android.Phase
import dev.vector.android.UiState
import dev.vector.android.design.DarkColors
import dev.vector.android.design.LightColors
import dev.vector.geo.Units
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Vector's [UiState] expressed in the car library's vocabulary.
 *
 * Every function here is pure and takes the state it needs as arguments, which
 * is deliberate: this is the only place where a Vector concept is translated
 * into a car concept, and translation is where a second, quietly divergent
 * definition of "the next turn" would otherwise appear. Keeping it pure means
 * the translation is unit-testable on the JVM without a car host, an emulator
 * or a phone — see `CarTripTest`.
 *
 * Nothing here computes anything. The distances, the durations, the maneuver
 * order and the arrival time all come from the engine that the phone HUD reads;
 * this file only changes their shape.
 */
object CarTrip {

    /**
     * Vector's maneuver vocabulary mapped onto the host's icon set.
     *
     * The strings are the backend's (`type` in `/navigate`'s steps, defaulting
     * to `continue`) and the same set `maneuverGlyph` and
     * `ManeuverAnnouncer.phrase` switch on. The car host draws its own artwork
     * from the returned constant, which is why no icon is attached: a bitmap of
     * Vector's own arrow would be the one element on the screen not matching
     * the vehicle's design language, and several head units scale it badly.
     *
     * Roundabouts resolve counter-clockwise because Vector's deployment drives
     * on the right. That is a genuine regional assumption rather than an
     * oversight, and it belongs with the rest of the localisation work — a
     * left-hand-traffic build wants the `_CW` constants.
     */
    fun maneuverType(type: String): Int = when (type) {
        "turn-left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
        "turn-right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        "slight-left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        "slight-right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        "sharp-left" -> Maneuver.TYPE_TURN_SHARP_LEFT
        "sharp-right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
        "uturn" -> Maneuver.TYPE_U_TURN_LEFT
        "roundabout" -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        "arrive" -> Maneuver.TYPE_DESTINATION
        "depart" -> Maneuver.TYPE_DEPART
        "continue" -> Maneuver.TYPE_STRAIGHT
        // Not TYPE_UNKNOWN: the host renders that as a blank tile, and a blank
        // tile beside a legible instruction reads as a rendering fault. An
        // arrow that is merely unspecific is the safer wrong answer.
        else -> Maneuver.TYPE_STRAIGHT
    }

    /**
     * A distance in the driver's units, rounded the way the host expects.
     *
     * The unit follows Vector's own [Units] setting rather than the head
     * unit's, because that setting is the one the driver chose and it is
     * already what the phone HUD and the spoken guidance obey. Two surfaces of
     * one product disagreeing about miles and kilometres is the kind of detail
     * that makes an app feel like two apps.
     *
     * `_P1` means one decimal place, which is what the host uses below the
     * threshold where a whole unit is too coarse — "0.4 km" rather than "0 km".
     */
    fun distance(meters: Double, units: Units): Distance {
        val m = meters.coerceAtLeast(0.0)
        return if (units == Units.IMPERIAL) {
            val miles = m / 1609.344
            if (miles < 0.1) Distance.create((m * 3.28084).coerceAtLeast(0.0), Distance.UNIT_FEET)
            else if (miles < 10.0) Distance.create(miles, Distance.UNIT_MILES_P1)
            else Distance.create(miles, Distance.UNIT_MILES)
        } else {
            if (m < 1000.0) Distance.create(m, Distance.UNIT_METERS)
            else if (m < 10_000.0) Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
            else Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS)
        }
    }

    /**
     * One step of the route.
     *
     * The cue is the backend's own instruction text, which is the same string
     * the phone shows and — decapitalised — the same one the voice speaks.
     * `setRoad` is what the host puts in the smaller line under the cue, and
     * Vector already knows the road name for the maneuver.
     */
    fun step(m: dev.vector.android.Maneuver): Step {
        val b = Step.Builder(m.instruction.ifBlank { "Continue" })
            .setManeuver(Maneuver.Builder(maneuverType(m.type)).build())
        m.road?.takeIf { it.isNotBlank() }?.let { b.setRoad(it) }
        return b.build()
    }

    /**
     * Remaining distance and arrival clock for the whole journey.
     *
     * The arrival time is computed from `remainingS` and the clock passed in
     * rather than from a stored ETA, so it stays correct across a reroute
     * without anything having to remember to update it — the same property the
     * phone's `etaLabel` has, for the same reason.
     */
    fun travelEstimate(ui: UiState, now: ZonedDateTime): TravelEstimate {
        val seconds = ui.remainingS.coerceAtLeast(0.0).toLong()
        return TravelEstimate.Builder(distance(ui.remainingM, ui.units), now.plusSeconds(seconds))
            .setRemainingTimeSeconds(seconds)
            // Amber while a reroute is in flight. The estimate on screen is
            // still the OLD route's, and saying so is better than either
            // freezing it or blanking it: the driver keeps a number to plan
            // with and can see that it is provisional.
            //
            // [CarPalette.hostWarning] and not [CarPalette.warning]: this
            // setter is constrained to the host's standard colours and throws
            // on a custom one. See [CarPalette].
            .setRemainingTimeColor(if (ui.rerouting) CarPalette.hostWarning else CarColor.DEFAULT)
            .build()
    }

    /**
     * The trip handed to the host's `NavigationManager`.
     *
     * This is what feeds the cluster display, the notification the head unit
     * shows over other apps, and any voice readout the host does itself — so
     * it carries the destination and the next two steps, not just the one on
     * screen. Loading while a reroute is in flight, which is how the host
     * renders "recalculating" in its own idiom.
     */
    fun trip(ui: UiState, now: ZonedDateTime): Trip {
        val b = Trip.Builder()
        if (ui.rerouting) return b.setLoading(true).build()

        val estimate = travelEstimate(ui, now)
        b.addDestination(
            Destination.Builder()
                .setName(ui.destinationName.ifBlank { "Destination" })
                .build(),
            estimate,
        )
        ui.currentManeuver?.let { cur ->
            b.addStep(step(cur), TravelEstimate.Builder(
                distance(ui.distanceToManeuverM, ui.units), now,
            ).setRemainingTimeSeconds(TravelEstimate.REMAINING_TIME_UNKNOWN).build())
        }
        ui.nextManeuver?.let { nxt ->
            // Distance to the step AFTER the current one: how far the current
            // one still is, plus the CURRENT maneuver's leg — `legM` is the
            // run from a maneuver to the one after it (NavState.Maneuver), so
            // reading it off `nxt` would skip a leg and under-report by a
            // whole block. Taken from the engine rather than recomputed.
            val after = ui.distanceToManeuverM + (ui.currentManeuver?.legM ?: 0.0)
            b.addStep(step(nxt), TravelEstimate.Builder(
                distance(after, ui.units), now.plusSeconds(0),
            ).setRemainingTimeSeconds(TravelEstimate.REMAINING_TIME_UNKNOWN).build())
        }
        return b.build()
    }

    /**
     * Everything on the car screen that could make it look different.
     *
     * The frame loop republishes [UiState] up to sixty times a second and
     * almost every one of those differs in some field the car does not draw —
     * a puck position, a camera bearing, a probe count. Calling the host's
     * `invalidate()` on each would be sixty IPC round trips a second for a
     * screen whose text changes about twice a minute, and the host throttles
     * apps that do it.
     *
     * So the screens compare this instead, and the rounding is the point:
     * distances to the tenth of a unit and time to the minute, which is the
     * precision actually rendered. Anything finer would defeat the check.
     */
    fun renderKey(ui: UiState): String = buildString {
        append(ui.phase).append('|')
        append(ui.rerouting).append('|')
        append(ui.offRoute).append('|')
        append(ui.currentManeuver?.index ?: -1).append('|')
        append(ui.nextManeuver?.index ?: -1).append('|')
        append(Math.round(ui.distanceToManeuverM / 10.0)).append('|')
        append(Math.round(ui.remainingM / 100.0)).append('|')
        append(Math.round(ui.remainingS / 60.0)).append('|')
        append(ui.destinationName).append('|')
        append(ui.units).append('|')
        append(ui.voice)
    }

    /** The host's zone, so the arrival clock reads as the car's clock. */
    fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

    /** Is there a live journey for the car to render? */
    fun navigating(ui: UiState?): Boolean = ui != null && ui.phase == Phase.NAVIGATING
}

/**
 * Vector's palette in the one colour type a car host understands.
 *
 * It lives here for the reason the object above gives for existing: there is
 * exactly one place where a Vector concept becomes a car concept, and a colour
 * is a Vector concept. Two screens each reading [LightColors] would be two
 * places for the car's amber to drift from the phone's.
 *
 * ## Why there are two values and not one
 *
 * The library accepts a colour in one of two forms, and which form is legal
 * depends on the *setter*, not on taste — the constraint is checked in the
 * setter's own code and a violation throws as the template is built, which in a
 * car is a crash rather than a wrong hue:
 *
 *  * [CarColor.createCustom] carries a daylight value and a night value in one
 *    object, so the host selects between them itself. Any setter that takes it
 *    validates against `CarColorConstraints.UNCONSTRAINED` — the template
 *    background is one — and that is the form [warning] is built from.
 *  * `TravelEstimate.Builder.setRemainingTimeColor` validates against
 *    `CarColorConstraints.STANDARD_ONLY`, which is the host's seven named
 *    colours and nothing else. A custom value there is rejected outright, so
 *    [hostWarning] is the host's own amber: the closest true rendering of the
 *    same role that setter will accept.
 *
 * `CarContext.isDarkMode()` is deliberately not read anywhere in the car
 * package. A custom colour already travels as a day/night pair, so a mode read
 * could only ever pick the half the host was about to pick for itself, and
 * would be one frame late in doing it.
 */
internal object CarPalette {

    /**
     * The stale-instruction amber: off route, and the remaining-time estimate
     * while a reroute is in flight.
     *
     * The role is [dev.vector.android.design.VectorColors.guidanceWarn] rather
     * than the `warning` role, because this is the *driving* surface's own
     * warning treatment and that is the family the palette reserves for it. The
     * two arguments below are the same number twice, which is the design and
     * not a slip: the palette fixes the guidance family identically in both
     * themes, deliberately — it is read through a windscreen in daylight and at
     * night, and a warning that changes hue at dusk is a warning a driver has to
     * re-learn. The car's warning follows the phone's guidance band here rather
     * than the page furniture beside it.
     *
     * It is also the state the phone colours with the `warning` role — the
     * off-route banner and the "Rerouting…" line in `NavUi.kt` — so both
     * surfaces agree about what a stale route looks like.
     */
    val warning: CarColor = CarColor.createCustom(
        LightColors.guidanceWarn.toArgb(),
        DarkColors.guidanceWarn.toArgb(),
    )

    /**
     * [warning] for a setter that rejects a custom colour.
     *
     * See the object KDoc: `TravelEstimate.Builder.setRemainingTimeColor` is
     * `STANDARD_ONLY`, and YELLOW is that set's amber — the vehicle's own, which
     * the host resolves against its own theme and its own contrast rules. Named
     * for the constraint rather than for the hue, so that a setter which ever
     * loosens to `UNCONSTRAINED` is an obvious swap back to [warning].
     */
    val hostWarning: CarColor = CarColor.YELLOW
}
