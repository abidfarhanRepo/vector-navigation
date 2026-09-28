package dev.vector.android

import dev.vector.geo.LaneGuidance
import dev.vector.geo.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Pure state helpers: the over-limit rule and the arrival clock. */
class NavStateTest {

    // ---- V7 Stage 1: the cached lane model on Maneuver ---------------------

    @Test
    fun `maneuver lanes prefer the backend-resolved driver-order string`() {
        // An older backend sends the raw way-ordered tag; a V7 backend sends
        // laneData.turnLanes resolved for the direction driven. The resolved
        // string wins so a backward traversal is not mirrored twice.
        val m = Maneuver(
            1, "turn-left", "Turn left", 250.0, 300.0,
            turnLanes = "left|through",
            laneData = VectorApi.LaneData(
                source = "turn:lanes", direction = "backward", turnLanes = "through|left",
            ),
        )
        assertEquals("through|left", m.laneSpec)
        assertEquals(listOf("through"), m.lanes[0].indications)
        assertEquals(listOf("left"), m.lanes[1].indications)
        assertTrue(m.lanes[1].valid)
    }

    @Test
    fun `maneuver falls back to the raw wire string when lane_data is absent`() {
        val m = Maneuver(
            1, "turn-right", "Turn right", 250.0, 300.0,
            turnLanes = "left|through|right",
        )
        assertEquals("left|through|right", m.laneSpec)
        assertTrue(m.lanes[2].valid)
        assertEquals(LaneGuidance.Status.USEFUL, m.laneStatus)
    }

    @Test
    fun `maneuver distinguishes unknown from no-useful-choice`() {
        val unknown = Maneuver(1, "continue", "Continue", 250.0, 300.0, turnLanes = "left|right")
        assertEquals(LaneGuidance.Status.UNKNOWN, unknown.laneStatus)
        assertTrue(unknown.lanes.isEmpty())

        val allValid = Maneuver(
            1, "turn-left", "Turn left", 250.0, 300.0,
            turnLanes = "left|left|left",
        )
        assertEquals(LaneGuidance.Status.NONE_USEFUL, allValid.laneStatus)
        assertTrue(allValid.lanes.isEmpty())
    }

    @Test
    fun `maneuver approachLanes and laneData ride along from the wire`() {
        val m = Maneuver(
            1, "slight-right", "Slight right", 250.0, 300.0,
            approachLanes = 4,
            laneData = VectorApi.LaneData(source = "none", direction = "unknown"),
        )
        assertEquals(4, m.approachLanes)
        assertEquals("none", m.laneData?.source)
        assertEquals(LaneGuidance.Status.UNKNOWN, m.laneStatus)
    }

    // ---- V7 Stage 4: the direction-specific lane count ---------------------

    @Test
    fun `maneuver forwardLanes is the driven direction, not the whole road`() {
        // A two-way `lanes=2` residential street. The two fields disagree by
        // design and the disagreement is the feature: `approachLanes` describes
        // the road, `forwardLanes` describes the carriageway being driven.
        val m = Maneuver(
            1, "turn-left", "Turn left", 250.0, 300.0,
            approachLanes = 2,
            laneData = VectorApi.LaneData(
                source = "none", direction = "forward", forwardLanes = 1,
            ),
        )
        assertEquals(2, m.approachLanes)
        assertEquals(1, m.forwardLanes)
    }

    @Test
    fun `maneuver forwardLanes is null when the backend could not say`() {
        // An odd two-way lane count, or a backend older than Stage 4. Both
        // must read as "unknown" so the lateral model stays on the centreline
        // rather than guessing which side of the road the driver is on.
        val odd = Maneuver(
            1, "turn-left", "Turn left", 250.0, 300.0,
            approachLanes = 3,
            laneData = VectorApi.LaneData(source = "none", direction = "forward"),
        )
        assertEquals(3, odd.approachLanes)
        assertNull(odd.forwardLanes)

        val old = Maneuver(1, "turn-left", "Turn left", 250.0, 300.0, turnLanes = "left|through")
        assertNull(old.forwardLanes)
    }

    @Test
    fun `no posted limit means never flagged as speeding`() {
        assertFalse(UiState(speedKmh = 140, speedLimitKmh = null).overSpeedLimit)
    }

    @Test
    fun `a small margin over the limit is tolerated`() {
        // GPS speed is noisy and speedometers read high by design. Flagging 51
        // in a 50 would cry wolf until the driver ignores the indicator.
        assertFalse(UiState(speedKmh = 51, speedLimitKmh = 50).overSpeedLimit)
        assertFalse(UiState(speedKmh = 55, speedLimitKmh = 50).overSpeedLimit)
    }

    @Test
    fun `a real overspeed is flagged`() {
        assertTrue(UiState(speedKmh = 62, speedLimitKmh = 50).overSpeedLimit)
    }

    @Test
    fun `at or under the limit is never flagged`() {
        assertFalse(UiState(speedKmh = 50, speedLimitKmh = 50).overSpeedLimit)
        assertFalse(UiState(speedKmh = 30, speedLimitKmh = 50).overSpeedLimit)
    }

    @Test
    fun `arrival clock adds the remaining time to now`() {
        val utc = TimeZone.getTimeZone("UTC")
        // 1970-01-01 12:00:00 UTC + 30 min
        assertEquals("12:30", arrivalClock(1800.0, 12 * 3600 * 1000L, utc))
    }

    @Test
    fun `arrival clock rolls past midnight`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("00:30", arrivalClock(3600.0, 23 * 3600 * 1000L + 30 * 60 * 1000L, utc))
    }

    @Test
    fun `arrival clock is zero padded`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("09:05", arrivalClock(300.0, 9 * 3600 * 1000L, utc))
    }

    @Test
    fun `a zero remaining time arrives now`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("08:00", arrivalClock(0.0, 8 * 3600 * 1000L, utc))
    }
    // ---- system bar icons (V5, reported from the S24) -----------------------
    //
    // "I cannot see the phone's icons on the notification panel at all when the
    // app is open — the wifi, mobile data and battery status."
    //
    // Vector draws edge to edge, so it has to tell the system which way round to
    // paint its own status-bar icons. It did — from the map theme alone, which
    // was right until V4 made the maneuver banner full-bleed. The banner is
    // dark in BOTH themes on purpose, so on a light-theme phone while
    // navigating the app asked for dark icons over a near-black band.

    @Test
    fun `the light theme wants dark status icons on the map`() {
        assertTrue(statusBarWantsDarkIcons(VectorStyle.MapTheme.LIGHT, Phase.EXPLORE))
        assertTrue(statusBarWantsDarkIcons(VectorStyle.MapTheme.LIGHT, Phase.PREVIEW))
    }

    @Test
    fun `the light theme wants LIGHT status icons over the driving banner`() {
        // THE DEFECT. The banner is #11161F in the light theme too, and dark
        // icons on it are invisible.
        assertFalse(statusBarWantsDarkIcons(VectorStyle.MapTheme.LIGHT, Phase.NAVIGATING))
    }

    @Test
    fun `the dark theme always wants light status icons`() {
        for (p in Phase.entries) {
            assertFalse("dark theme, $p", statusBarWantsDarkIcons(VectorStyle.MapTheme.DARK, p))
        }
    }

    // ---- keeping the screen awake (reported from a drive) -------------------
    //
    // "The screen sometimes dims and turns off when doing a drive. I don't
    // think the maps is actively trying to keep the display on at times."
    //
    // It was not trying at all: nothing in the app set FLAG_KEEP_SCREEN_ON or
    // took a wake lock, so the display followed the system timeout through a
    // whole drive. "Sometimes" is the tell — touching the map resets the
    // timeout, so the driver who fiddles keeps it alive and the one who just
    // drives does not.

    @Test
    fun `the screen is held awake while driving`() {
        assertTrue(screenShouldStayOn(Phase.NAVIGATING))
    }

    @Test
    fun `the screen is released when not driving`() {
        // Not merely the absence of the fix: a phone left on the map in a
        // pocket must be allowed to sleep. Holding the flag in EXPLORE would
        // be a worse bug than the one being fixed, and a harder one to blame
        // on the app.
        assertFalse(screenShouldStayOn(Phase.EXPLORE))
        assertFalse(screenShouldStayOn(Phase.PREVIEW))
    }

    @Test
    fun `exactly one phase holds the screen`() {
        // Pins the decision rather than the implementation: if a phase is
        // added later, this fails and forces an answer about the display.
        assertEquals(listOf(Phase.NAVIGATING), Phase.entries.filter { screenShouldStayOn(it) })
    }

    @Test
    fun `the navigation bar follows the theme in every phase`() {
        // Nothing at the bottom of the screen refuses to follow the theme, so
        // there is no phase exception here — and asserting that keeps someone
        // from copying the status bar's exception across.
        assertTrue(navBarWantsDarkIcons(VectorStyle.MapTheme.LIGHT))
        assertFalse(navBarWantsDarkIcons(VectorStyle.MapTheme.DARK))
    }

    @Test
    fun `SYSTEM is never asked about directly`() {
        // SYSTEM is a preference, not a palette. Both functions take a RESOLVED
        // theme, and the bug that motivated this pair was a caller comparing
        // the raw preference against LIGHT — SYSTEM is neither, so a light-mode
        // phone got a light map under dark panels.
        assertEquals(
            VectorStyle.MapTheme.LIGHT,
            Settings(theme = VectorStyle.MapTheme.SYSTEM).resolvedTheme(systemInDark = false),
        )
        assertEquals(
            VectorStyle.MapTheme.DARK,
            Settings(theme = VectorStyle.MapTheme.SYSTEM).resolvedTheme(systemInDark = true),
        )
    }

    // ---- time to go (V5, reported from the S24) -----------------------------
    //
    // "It shows 0 mins left for the drive. That shouldn't happen either."
    //
    // The trip bar printed `(remainingS / 60).toInt()` minutes, which says
    // "0 min" under a minute out, truncates instead of rounding, and has no
    // hours.

    private fun eta(remainingM: Double, remainingS: Double) =
        UiState(remainingM = remainingM, remainingS = remainingS).etaLabel

    @Test
    fun `the last hundred metres say Arriving, not a duration`() {
        // THE DEFECT.
        assertEquals("Arriving", eta(40.0, 4.0))
        assertEquals("Arriving", eta(119.0, 12.0))
    }

    @Test
    fun `no journey ever takes zero minutes`() {
        // Past the Arriving threshold but under a minute: 1 min, never 0.
        assertEquals("1 min", eta(500.0, 40.0))
        assertEquals("1 min", eta(500.0, 1.0))
    }

    @Test
    fun `minutes are rounded, not truncated`() {
        // 119 s truncates to 1 and rounds to 2. Truncating makes the number
        // hold for two minutes and then drop two.
        assertEquals("2 min", eta(1_500.0, 119.0))
        assertEquals("3 min", eta(1_500.0, 150.0))
        assertEquals("2 min", eta(1_500.0, 149.0))
    }

    @Test
    fun `an hour or more reads as hours and minutes`() {
        assertEquals("1 h 23 min", eta(40_000.0, 83.0 * 60))
        assertEquals("2 h", eta(90_000.0, 120.0 * 60))
        assertEquals("59 min", eta(40_000.0, 59.0 * 60))
        assertEquals("1 h", eta(40_000.0, 60.0 * 60))
    }

    @Test
    fun `the Arriving threshold is well clear of the arrival trigger`() {
        // They answer different questions: ARRIVAL_RADIUS_M is where the
        // journey has ended, ARRIVING_LABEL_M is where a duration has stopped
        // being worth reading. If the label switched at the trigger the driver
        // would never see it.
        assertTrue(ARRIVING_LABEL_M > ARRIVAL_RADIUS_M * 3)
    }


    // ---- async replies that arrive after the world moved on ---------------
    //
    // §4 asks, of every transition: "what happens if events arrive out of
    // order?" For the two route requests the answer used to be silent
    // corruption, and neither could be tested where the bug was — both guards
    // lived inside `MainActivity` coroutines, which need a GL surface and a
    // fused location provider. They are named rules now, so they can be.

    @Test
    fun `a route reply for the destination on screen is applied`() {
        val dest = LngLat(51.4986, 25.3208)
        assertTrue(routeReplyStillWanted(dest, dest))
    }

    @Test
    fun `a route reply for a destination the driver has replaced is discarded`() {
        // The ordinary way to use a search box: a mis-tap and an immediate
        // correction. Two requests in flight, no ordering between them, and
        // the first one landing last used to WIN — the map drew A's route
        // while the destination chip, the recents entry and the Home/Work save
        // all said B.
        val first = LngLat(51.4986, 25.3208)
        val second = LngLat(51.5238, 25.2867)
        assertFalse(routeReplyStillWanted(second, first))
    }

    @Test
    fun `a route reply that lands after the driver backed out is discarded`() {
        assertFalse(routeReplyStillWanted(null, LngLat(51.4986, 25.3208)))
    }

    @Test
    fun `a reroute reply is applied while the journey is still being driven`() {
        val dest = LngLat(51.4986, 25.3208)
        assertTrue(rerouteReplyStillWanted(Phase.NAVIGATING, dest, dest))
    }

    @Test
    fun `a reroute reply that lands after arrival is discarded`() {
        // The defect: it called `tracker.setRoute`, drew the route and
        // repopulated the maneuvers, so the app sat in EXPLORE with a route on
        // the map and an ETA counting down — and no way out, because the exit
        // control belongs to NAVIGATING.
        val dest = LngLat(51.4986, 25.3208)
        assertFalse(rerouteReplyStillWanted(Phase.EXPLORE, dest, dest))
    }

    @Test
    fun `a reroute reply that lands after the driver tapped out is discarded`() {
        val dest = LngLat(51.4986, 25.3208)
        assertFalse(rerouteReplyStillWanted(Phase.EXPLORE, null, dest))
    }

    @Test
    fun `a reroute reply cannot land on the next journey`() {
        // Still NAVIGATING, but a different journey. The phase alone would
        // have accepted this and replaced the new route with the old one's.
        assertFalse(
            rerouteReplyStillWanted(
                Phase.NAVIGATING,
                LngLat(51.5238, 25.2867),
                LngLat(51.4986, 25.3208),
            )
        )
    }

    @Test
    fun `a reroute reply is not applied during PREVIEW`() {
        // Reachable: a reroute in flight when the driver arrives, dismisses,
        // and picks a new destination fast enough to still be in PREVIEW.
        val dest = LngLat(51.4986, 25.3208)
        assertFalse(rerouteReplyStillWanted(Phase.PREVIEW, dest, dest))
    }

    // ---- the clock follows the phone, not the code -------------------------

    @Test
    fun `a 12-hour phone gets a 12-hour arrival clock`() {
        // Reported: "time estimate also should respect phone settings for 12 hr
        // and 24 hrs clocks". It was hardcoded to `%02d:%02d`, so a driver
        // whose phone shows 9:05 pm everywhere else read 21:05 here.
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("9:05 pm", arrivalClock(300.0, 21 * 3600 * 1000L, utc, use24h = false))
        assertEquals("9:05 am", arrivalClock(300.0, 9 * 3600 * 1000L, utc, use24h = false))
    }

    @Test
    fun `midnight and noon are twelve, not zero, on a 12-hour clock`() {
        // `Calendar.HOUR` is 0..11 and 0 means twelve o'clock in both halves of
        // the day. Printing it raw gives "0:30 am", which is not a time anybody
        // writes.
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("12:30 am", arrivalClock(1800.0, 0L, utc, use24h = false))
        assertEquals("12:30 pm", arrivalClock(1800.0, 12 * 3600 * 1000L, utc, use24h = false))
    }

    @Test
    fun `a 24-hour phone is unchanged, including the leading zero`() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("09:05", arrivalClock(300.0, 9 * 3600 * 1000L, utc, use24h = true))
        assertEquals("00:30", arrivalClock(1800.0, 0L, utc, use24h = true))
    }
}
