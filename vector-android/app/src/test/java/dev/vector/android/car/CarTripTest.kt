package dev.vector.android.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import dev.vector.android.NavBridge
import dev.vector.android.Phase
import dev.vector.android.Settings
import dev.vector.android.UiState
import dev.vector.geo.Units
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The car translation layer, tested without a car.
 *
 * This is the whole reason [CarTrip] is a pure object rather than logic inside
 * the screens: none of it needs a head unit, a phone or the Desktop Head Unit
 * to check, and the parts a driver would most notice being wrong — the wrong
 * arrow, the wrong units, a screen that redraws sixty times a second — are
 * exactly the parts that are checkable here.
 *
 * Robolectric because the car library's model classes are Android types even
 * though they hold no framework state; the project already runs its entire
 * suite this way.
 */
@RunWith(RobolectricTestRunner::class)
class CarTripTest {

    private val now: ZonedDateTime =
        ZonedDateTime.of(2026, 9, 9, 14, 0, 0, 0, ZoneId.of("Asia/Qatar"))

    private fun maneuver(
        index: Int = 0,
        type: String = "turn-right",
        instruction: String = "Turn right onto Al Waab Street",
        legM: Double = 800.0,
    ) = dev.vector.android.Maneuver(
        index = index, type = type, instruction = instruction,
        legM = legM, cumulativeM = 0.0, road = "Al Waab Street",
    )

    private fun driving(
        remainingM: Double = 4_200.0,
        remainingS: Double = 480.0,
        toManeuverM: Double = 300.0,
        rerouting: Boolean = false,
        units: Units = Units.METRIC,
    ) = UiState(
        phase = Phase.NAVIGATING,
        destinationName = "Villaggio Mall",
        remainingM = remainingM,
        remainingS = remainingS,
        distanceToManeuverM = toManeuverM,
        currentManeuver = maneuver(),
        nextManeuver = maneuver(index = 1, type = "arrive", instruction = "Arrive"),
        rerouting = rerouting,
        settings = Settings(units = units),
    )

    // --- maneuver vocabulary -------------------------------------------------

    @Test
    fun `every maneuver Vector emits maps to a real host arrow`() {
        // The complete vocabulary the backend produces, as handled by
        // maneuverGlyph and ManeuverAnnouncer.phrase. A type missing here is a
        // driver shown a straight-ahead arrow at a junction.
        val vocabulary = listOf(
            "turn-left", "turn-right", "slight-left", "slight-right",
            "sharp-left", "sharp-right", "uturn", "roundabout",
            "arrive", "depart", "continue",
        )
        vocabulary.forEach { type ->
            val mapped = CarTrip.maneuverType(type)
            assertNotEquals("$type fell through to UNKNOWN", Maneuver.TYPE_UNKNOWN, mapped)
            // Must actually build: some host types (the roundabout family with
            // an exit number, for one) throw from build() when under-specified,
            // and that would be a crash in the car rather than a wrong arrow.
            Maneuver.Builder(mapped).build()
        }
    }

    @Test
    fun `an unknown type degrades to straight rather than to a blank tile`() {
        assertEquals(Maneuver.TYPE_STRAIGHT, CarTrip.maneuverType("merge-left"))
    }

    @Test
    fun `left and right are not swapped`() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, CarTrip.maneuverType("turn-left"))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, CarTrip.maneuverType("turn-right"))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_LEFT, CarTrip.maneuverType("slight-left"))
        assertEquals(Maneuver.TYPE_TURN_SHARP_RIGHT, CarTrip.maneuverType("sharp-right"))
    }

    // --- units ---------------------------------------------------------------

    @Test
    fun `metric distances use the right unit at each scale`() {
        assertEquals(Distance.UNIT_METERS, CarTrip.distance(300.0, Units.METRIC).displayUnit)
        assertEquals(Distance.UNIT_KILOMETERS_P1, CarTrip.distance(4_200.0, Units.METRIC).displayUnit)
        assertEquals(Distance.UNIT_KILOMETERS, CarTrip.distance(42_000.0, Units.METRIC).displayUnit)
        assertEquals(4.2, CarTrip.distance(4_200.0, Units.METRIC).displayDistance, 0.001)
    }

    @Test
    fun `imperial distances are converted, not relabelled`() {
        val d = CarTrip.distance(1609.344, Units.IMPERIAL)
        assertEquals(Distance.UNIT_MILES_P1, d.displayUnit)
        assertEquals(1.0, d.displayDistance, 0.001)
        assertEquals(Distance.UNIT_FEET, CarTrip.distance(30.0, Units.IMPERIAL).displayUnit)
    }

    @Test
    fun `the car follows Vector's own units setting`() {
        assertEquals(
            Distance.UNIT_MILES_P1,
            CarTrip.travelEstimate(driving(units = Units.IMPERIAL), now).remainingDistance!!.displayUnit,
        )
        assertEquals(
            Distance.UNIT_KILOMETERS_P1,
            CarTrip.travelEstimate(driving(units = Units.METRIC), now).remainingDistance!!.displayUnit,
        )
    }

    @Test
    fun `a negative remaining distance cannot reach the host`() {
        // The host throws on a negative distance. Remaining can go slightly
        // negative past the arrival radius before Arrived is emitted.
        assertEquals(0.0, CarTrip.distance(-5.0, Units.METRIC).displayDistance, 0.0)
    }

    // --- estimates and trip --------------------------------------------------

    @Test
    fun `arrival time is remaining seconds from now, not a stored eta`() {
        val est = CarTrip.travelEstimate(driving(remainingS = 480.0), now)
        assertEquals(480L, est.remainingTimeSeconds)
    }

    @Test
    fun `a reroute publishes a loading trip rather than a stale one`() {
        val trip = CarTrip.trip(driving(rerouting = true), now)
        assertTrue(trip.isLoading)
        assertTrue(trip.steps.isEmpty())
    }

    @Test
    fun `a normal trip carries the destination and both steps`() {
        val trip = CarTrip.trip(driving(), now)
        assertFalse(trip.isLoading)
        assertEquals(1, trip.destinations.size)
        assertEquals("Villaggio Mall", trip.destinations[0].name.toString())
        assertEquals(2, trip.steps.size)
    }

    @Test
    fun `the second step is further away than the first`() {
        // The distance to the step after next is the current countdown plus the
        // CURRENT maneuver's leg. Reading the leg off the wrong maneuver made
        // the two steps report the same distance, which is what this pins.
        // Both distances deliberately stay under a kilometre so the comparison
        // is between two figures in the SAME unit — displayDistance is 300 for
        // 300 m and 1.1 for 1100 m, and comparing those two numbers directly
        // would be the units bug this test exists to catch.
        val ui = driving(toManeuverM = 100.0)
            .let { it.copy(currentManeuver = maneuver(legM = 200.0)) }
        val trip = CarTrip.trip(ui, now)
        val first = trip.stepTravelEstimates[0].remainingDistance!!
        val second = trip.stepTravelEstimates[1].remainingDistance!!
        assertEquals(Distance.UNIT_METERS, first.displayUnit)
        assertEquals(Distance.UNIT_METERS, second.displayUnit)
        assertEquals(100.0, first.displayDistance, 0.001)
        assertEquals(300.0, second.displayDistance, 0.001)
    }

    // --- redraw throttling ---------------------------------------------------

    @Test
    fun `frame noise the car does not draw does not trigger a redraw`() {
        val a = driving()
        // Everything here changes on almost every frame and none of it is on
        // the car screen. If any of it entered the key the app would make
        // sixty IPC calls a second and the host would throttle it.
        val b = a.copy(
            probesSent = a.probesSent + 40,
            speedKmh = 63,
            gpsAccuracyM = 4.2,
            myLocation = dev.vector.geo.LngLat(51.49, 25.26),
            roadName = "Al Waab Street",
            distanceToManeuverM = a.distanceToManeuverM - 3.0,
        )
        assertEquals(CarTrip.renderKey(a), CarTrip.renderKey(b))
    }

    @Test
    fun `a new maneuver does trigger a redraw`() {
        val a = driving()
        val b = a.copy(currentManeuver = maneuver(index = 1, type = "turn-left"))
        assertNotEquals(CarTrip.renderKey(a), CarTrip.renderKey(b))
    }

    @Test
    fun `crossing a hundred metres of progress triggers a redraw`() {
        val a = driving(remainingM = 4_200.0)
        assertNotEquals(CarTrip.renderKey(a), CarTrip.renderKey(driving(remainingM = 4_000.0)))
    }

    @Test
    fun `muting triggers a redraw because the strip label changes`() {
        val a = driving()
        val b = a.copy(settings = a.settings.copy(voice = dev.vector.android.VoiceMode.OFF))
        assertNotEquals(CarTrip.renderKey(a), CarTrip.renderKey(b))
    }

    // --- the bridge itself ---------------------------------------------------

    /**
     * Both halves of "nobody is listening".
     *
     * ## Why this test was failing
     *
     * It asserted that a published `UiState` alone was enough for `request` to
     * succeed. That stopped being true in `e03dc4e` ("a destination tapped in
     * the car silently did nothing"), which added the SECOND condition to
     * `NavBridge.request`: an Activity existing is not the same as an Activity
     * *collecting*, because `collectCarCommands` only starts inside MapLibre's
     * `getMapAsync` callback. A destination tapped in the car during that
     * window was accepted by the bridge and dropped on the floor — the exact
     * defect that commit fixed.
     *
     * The test was not updated with it, and then could not be seen failing: the
     * whole `:app` suite was erroring out on `UnsupportedClassVersionError`
     * before any assertion ran (see `core-geo/build.gradle.kts`). So it asserts
     * the current contract now, which is the one a driver depends on.
     */
    @Test
    fun `a command with no phone attached is refused rather than dropped`() = runBlocking {
        NavBridge.detach()
        assertFalse("no Activity at all", NavBridge.request(NavBridge.Command.Stop))

        NavBridge.publish(driving())
        assertFalse(
            "an Activity that exists but is not yet collecting must still refuse",
            NavBridge.request(NavBridge.Command.Stop),
        )

        // `onSubscription` fires once the collector is actually registered,
        // which is the moment `request` starts succeeding. Waiting on it rather
        // than on a sleep or a fixed number of `yield`s keeps the test
        // deterministic: `launch` has not run a single line at this point.
        val attached = CompletableDeferred<Unit>()
        val collector = launch {
            NavBridge.commands.onSubscription { attached.complete(Unit) }.collect { }
        }
        attached.await()

        assertTrue("attached and collecting", NavBridge.request(NavBridge.Command.Stop))

        collector.cancel()
        NavBridge.detach()
    }

    @Test
    fun `navigating is false while the phone is only previewing`() {
        assertFalse(CarTrip.navigating(null))
        assertFalse(CarTrip.navigating(UiState(phase = Phase.PREVIEW)))
        assertTrue(CarTrip.navigating(driving()))
    }
}
