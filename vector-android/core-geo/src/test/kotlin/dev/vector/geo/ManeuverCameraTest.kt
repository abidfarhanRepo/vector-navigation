package dev.vector.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * V7 Stage 2: the adaptive maneuver camera, pure and JVM-only.
 *
 * The whole state machine must be provable with no device, because the failure
 * modes are exactly the ones that are invisible on a bench and obvious on a
 * road: zoom pumping at junctions, a camera that never returns, a camera that
 * never tightens. Every rule in the plan maps to at least one test here.
 *
 * Numeric notes. The stage entries are DISTANCES (plan §6.3 as amended after
 * Stage 3), widened by complexity and, above ~100 km/h, by a time budget. The
 * default `input()` below is a complexity-2 turn at 110 km/h:
 *
 *   ANTICIPATE = max(300 + 40*2, 30.56 * 11) = 380 m
 *   FRAME      = max(180,         30.56 *  6) = 183 m
 *   COMMIT     = 120 m (fixed, = ManeuverAnnouncer.TURN_M)
 *
 * FRAME carries no complexity term (see [ManeuverCamera.FRAME_COMPLEXITY_M]);
 * complexity's earliness is all in ANTICIPATE.
 *
 * The previous formulation was a pure time budget floored at 120 m — the same
 * value as COMMIT — so FRAME's window was empty below ~86 km/h and the ladder
 * silently skipped it on every urban approach. `frame is reachable at every
 * urban speed and complexity` is the regression that pins the fix; see D2 in
 * V7-STAGE3-DEVICE-VALIDATION.md for the drive that found it.
 */
class ManeuverCameraTest {

    private val cam = ManeuverCamera()

    private fun input(
        index: Int = 1,
        type: String = "turn-left",
        distance: Double = 600.0,
        speedMs: Double = 110.0 / 3.6,
        lanes: Int? = null,
        laneUseful: Boolean = false,
        gap: Double? = null,
    ) = ManeuverCamera.Input(
        maneuverIndex = index,
        maneuverType = type,
        distanceToManeuverM = distance,
        speedMs = speedMs,
        approachLanes = lanes,
        laneUseful = laneUseful,
        nextManeuverGapM = gap,
    )

    private fun transitions(vararg d: ManeuverCamera.Decision): List<ManeuverCamera.Decision.Transition> =
        d.filterIsInstance<ManeuverCamera.Decision.Transition>()

    // ---- the pure helpers ------------------------------------------------

    @Test
    fun `complexity is latched from wire facts only`() {
        // turn-left is +2; a useful lane +1; four lanes +1; compound +1.
        assertEquals(2, cam.complexityOf(input(type = "turn-left")))
        assertEquals(3, cam.complexityOf(input(type = "turn-left", lanes = 4)))
        assertEquals(3, cam.complexityOf(input(type = "turn-left", laneUseful = true)))
        assertEquals(1, cam.complexityOf(input(type = "slight-left")))
        assertEquals(2, cam.complexityOf(input(type = "roundabout")))
        assertEquals(2, cam.complexityOf(input(type = "uturn")))
        assertEquals(0, cam.complexityOf(input(type = "arrive")))
        // The compound gap mirrors the announcer: max(4 s of travel, 120 m).
        assertEquals(3, cam.complexityOf(input(type = "turn-left", gap = 100.0)))
        assertEquals(2, cam.complexityOf(input(type = "turn-left", gap = 5000.0)))
        // Continue/depart are always 0 and never trigger.
        assertEquals(0, cam.complexityOf(input(type = "continue")))
        assertFalse(ManeuverCamera.isManeuver("continue"))
        assertFalse(ManeuverCamera.isManeuver("depart"))
        assertTrue(ManeuverCamera.isManeuver("arrive"))
        assertTrue(ManeuverCamera.isManeuver("roundabout"))
    }

    @Test
    fun `stage entries are distances, and anticipation widens with complexity`() {
        // Town and urban: the DISTANCE is what holds. 50 km/h would be 83 m of
        // FRAME on a time budget; the junction is still 180 m away either way.
        val town = input(type = "turn-left", speedMs = 30.0 / 3.6)
        assertEquals(380.0, cam.anticipateAt(town, 2), 1e-9)
        assertEquals(180.0, cam.frameAt(town, 2), 1e-9)
        val fifty = input(speedMs = 50.0 / 3.6)
        assertEquals(380.0, cam.anticipateAt(fifty, 2), 1e-9)
        assertEquals(180.0, cam.frameAt(fifty, 2), 1e-9)
        // Complexity widens ANTICIPATE — 40 m a level, the earliness — and
        // leaves FRAME alone. FRAME is a fixed place because the distance you
        // can frame a junction from belongs to the screen and the 60 deg
        // pitch, not to how hard the junction is: the tuning-pass sweep put a
        // complexity-3 junction entirely off the map at its old 240 m entry.
        assertEquals(300.0, cam.anticipateAt(fifty, 0), 1e-9)
        assertEquals(420.0, cam.anticipateAt(fifty, 3), 1e-9)
        for (c in 0..3) assertEquals(180.0, cam.frameAt(fifty, c), 1e-9)
        // Above ~100 km/h the time budget takes over and pushes them OUT.
        val motorway = input(speedMs = 140.0 / 3.6)
        assertEquals(140.0 / 3.6 * 11, cam.anticipateAt(motorway, 0), 0.1)
        assertEquals(140.0 / 3.6 * 6, cam.frameAt(motorway, 0), 0.1)
        // Speed still pushes FRAME out, at every complexity, identically.
        for (c in 0..3) assertEquals(140.0 / 3.6 * 6, cam.frameAt(motorway, c), 0.1)
        // Ceilings still bind at absurd speeds, and ANTICIPATE still leads.
        val extreme = input(laneUseful = true, speedMs = 100.0, distance = 2000.0)
        assertEquals(3, cam.complexityOf(extreme))
        assertEquals(ManeuverCamera.ANTICIPATE_MAX_M, cam.anticipateAt(extreme))
        assertEquals(ManeuverCamera.FRAME_MAX_M, cam.frameAt(extreme))
    }

    @Test
    fun `the FRAME window is never empty, at any speed or complexity`() {
        // The D2 invariant, stated directly: FRAME's entry must sit strictly
        // above COMMIT's, or the ladder skips the stage entirely. The old
        // formulation failed this for every complexity below 80 km/h.
        assertTrue(
            ManeuverCamera.FRAME_MIN_M > ManeuverCamera.TURN_M,
            "FRAME_MIN_M must be above TURN_M or FRAME can never be entered",
        )
        for (kmh in listOf(5, 20, 30, 50, 60, 80, 100, 110, 140, 200)) {
            for (c in 0..3) {
                val i = input(speedMs = kmh / 3.6, distance = 0.0)
                val frame = cam.frameAt(i, c)
                val anticipate = cam.anticipateAt(i, c)
                assertTrue(
                    frame > ManeuverCamera.TURN_M,
                    "FRAME window empty at $kmh km/h c$c: frameAt=$frame",
                )
                assertTrue(
                    anticipate >= frame + ManeuverCamera.MIN_STAGE_GAP_M,
                    "ANTICIPATE does not lead FRAME at $kmh km/h c$c: " +
                        "$anticipate vs $frame",
                )
            }
        }
    }

    @Test
    fun `frame is reachable at every urban speed and complexity`() {
        // The acceptance the Stage 3 rerun exists to confirm: walk a real
        // approach in 10 m steps and assert the ladder actually visits FRAME
        // before the 120 m COMMIT boundary.
        for ((kmh, type, lanes) in listOf(
            Triple(30.0, "turn-right", null),          // town, complexity 2
            Triple(50.0, "turn-left", null),           // urban arterial, 2
            Triple(50.0, "slight-right", 4),           // the Doha lane case, 3
            Triple(50.0, "arrive", null),              // complexity 0
            Triple(80.0, "turn-left", null),           // arterial, 2
            Triple(110.0, "turn-right", null),         // motorway, 2
        )) {
            val c = ManeuverCamera()
            val seen = mutableListOf<ManeuverCamera.Stage>()
            var d = 600.0
            while (d >= 20.0) {
                c.update(
                    input(type = type, distance = d, speedMs = kmh / 3.6, lanes = lanes,
                          laneUseful = lanes != null),
                    17.5, false,
                )
                if (seen.lastOrNull() != c.stage()) seen += c.stage()
                d -= 10.0
            }
            assertEquals(
                listOf(
                    ManeuverCamera.Stage.CRUISE,
                    ManeuverCamera.Stage.ANTICIPATE,
                    ManeuverCamera.Stage.FRAME,
                    ManeuverCamera.Stage.COMMIT,
                ),
                seen,
                "$type at $kmh km/h did not walk the full ladder",
            )
        }
    }

    @Test
    fun `zoom targets sit above the speed band and scale with complexity`() {
        assertEquals(0.4, cam.zoomForStage(ManeuverCamera.Stage.ANTICIPATE, 17.5, 0) - 17.5, 1e-9)
        assertEquals(0.8, cam.zoomForStage(ManeuverCamera.Stage.FRAME, 17.5, 0) - 17.5, 1e-9)
        assertEquals(
            0.4 + 0.15 * 3,
            cam.zoomForStage(ManeuverCamera.Stage.ANTICIPATE, 17.5, 3) - 17.5,
            1e-9,
        )
        assertEquals(
            0.8 + 0.10 * 3,
            cam.zoomForStage(ManeuverCamera.Stage.FRAME, 17.5, 3) - 17.5,
            1e-9,
        )
        // CRUISE and RECOVER are the baseline; COMMIT holds FRAME.
        assertEquals(17.5, cam.zoomForStage(ManeuverCamera.Stage.CRUISE, 17.5, 2))
        assertEquals(17.5, cam.zoomForStage(ManeuverCamera.Stage.RECOVER, 17.5, 2))
        assertEquals(
            cam.zoomForStage(ManeuverCamera.Stage.FRAME, 17.5, 2),
            cam.zoomForStage(ManeuverCamera.Stage.COMMIT, 17.5, 2),
        )
    }

    @Test
    fun `entry stage follows the distance ladder`() {
        assertEquals(ManeuverCamera.Stage.CRUISE, cam.entryStage(input(distance = 1000.0)))
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, cam.entryStage(input(distance = 300.0)))
        assertEquals(ManeuverCamera.Stage.FRAME, cam.entryStage(input(distance = 180.0)))
        assertEquals(ManeuverCamera.Stage.COMMIT, cam.entryStage(input(distance = 100.0)))
    }

    // ---- the lifecycle ----------------------------------------------------

    @Test
    fun `a full approach writes exactly three times and recovers to the band`() {
        val writes = mutableListOf<ManeuverCamera.Decision.Transition>()
        // 950 m out: CRUISE, nothing written.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(distance = 950.0), 17.5, false))
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(distance = 600.0), 17.5, false))
        // First fix inside the window: ANTICIPATE, one write, band + 0.7.
        writes += transitions(cam.update(input(distance = 300.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, writes.last().stage)
        assertEquals(17.5 + 0.4 + 0.15 * 2, writes.last().targetZoom, 1e-9)
        // Holding inside ANTICIPATE: no repeat.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(distance = 250.0), 17.5, false))
        // FRAME: one write, band + 1.0.
        writes += transitions(cam.update(input(distance = 130.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.FRAME, writes.last().stage)
        assertEquals(17.5 + 0.8 + 0.10 * 2, writes.last().targetZoom, 1e-9)
        // COMMIT holds — no write.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(distance = 110.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.COMMIT, cam.stage())
        // The maneuver completes: the NEXT index is current; RECOVER writes once
        // and returns to the band, with the long duration.
        writes += transitions(cam.update(input(index = 2, distance = 4000.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.RECOVER, writes.last().stage)
        assertEquals(17.5, writes.last().targetZoom, 1e-9)
        assertEquals(ManeuverCamera.RECOVER_DURATION_MS, writes.last().durationMs)
        // Recovery done: a far next maneuver coasts in CRUISE with no write.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(index = 2, distance = 3800.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.CRUISE, cam.stage())
        assertTrue(writes.size <= 3, "expected at most 3 writes, got $writes")
    }

    @Test
    fun `stages advance monotonically and one stage writes only once`() {
        cam.reset()
        assertIs<ManeuverCamera.Decision.Transition>(cam.update(input(distance = 300.0), 17.5, false))
        // The next fix is still the same stage: no repeat.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(distance = 290.0), 17.5, false))
        // Advance to FRAME: one write.
        assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(input(distance = 130.0), 17.5, false))
        // Holding inside FRAME: no further writes.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(distance = 125.0), 17.5, false))
    }

    @Test
    fun `continue and depart never move the camera themselves`() {
        cam.reset()
        val first = cam.update(input(distance = 300.0), 17.5, false)
        assertIs<ManeuverCamera.Decision.Transition>(first)
        // The leg completes as a continue: the TURN eases back to the band
        // once (that is the turn's recovery), and then the continue coast is
        // silent.
        val recover = cam.update(input(index = 2, type = "continue", distance = 1000.0), 17.5, false)
        assertEquals(
            ManeuverCamera.Stage.RECOVER,
            assertIs<ManeuverCamera.Decision.Transition>(recover).stage,
        )
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(index = 2, type = "continue", distance = 900.0), 17.5, false))
        // A continue approached from CRUISE never writes at all.
        cam.reset()
        assertEquals(
            ManeuverCamera.Decision.Noop,
            cam.update(input(index = 0, type = "continue", distance = 1000.0), 17.5, false),
        )
        // And the next REAL maneuver latches fresh.
        assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(input(index = 3, distance = 300.0), 17.5, false))
    }

    @Test
    fun `a driver pinch suspends maneuver zoom until the next maneuver`() {
        cam.reset()
        // Approaching, but the driver owns the zoom: never a write.
        for (d in listOf(300.0, 130.0, 110.0)) {
            assertEquals(
                ManeuverCamera.Decision.Noop,
                cam.update(input(distance = d), 17.5, driverZoomed = true),
            )
        }
        // The next maneuver lifts the suspension (the driver has not re-pinched).
        assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(input(index = 2, distance = 300.0), 17.5, driverZoomed = false))
    }

    @Test
    fun `hysteresis stops a threshold flip from retreating the camera`() {
        cam.reset()
        // ANTICIPATE and FRAME both fire on the way in (110 km/h, c2:
        // windows 380 and 183, COMMIT at 120).
        cam.update(input(distance = 300.0), 17.5, false)
        cam.update(input(distance = 150.0), 17.5, false)
        val before = cam.stage()
        assertEquals(ManeuverCamera.Stage.FRAME, before)
        // A fix past the threshold but inside the 80 m margin must NOT retreat.
        cam.update(input(distance = 240.0), 17.5, false)
        assertEquals(before, cam.stage())
        // Past the entry PLUS the margin (183 + 80): the STAGE retreats, but a
        // stage that already wrote never fires again — the write log is kept.
        val retreat = cam.update(input(distance = 290.0), 17.5, false)
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, cam.stage())
        assertEquals(
            ManeuverCamera.Decision.Noop, retreat,
            "a stage that already wrote must not re-fire on retreat",
        )
    }

    @Test
    fun `a distance oscillation across a junction never re-fires a stage`() {
        // The dense-Msheireb defect: `upcomingManeuver`'s distance wobbles as
        // the route projection passes a junction, and every wobble used to
        // retreat the stage, clear the write log and re-enter ANTICIPATE —
        // zoom breathing four times in four seconds. The whole oscillation
        // must produce exactly ONE ANTICIPATE write and ONE FRAME write.
        cam.reset()
        var writes = 0
        for (d in listOf(300.0, 250.0, 230.0, 260.0, 220.0, 240.0, 130.0, 110.0, 140.0, 125.0)) {
            if (cam.update(input(distance = d), 17.5, false) is ManeuverCamera.Decision.Transition) writes++
        }
        assertEquals(2, writes)
    }

    @Test
    fun `dense junctions skip recovery and hold through commit`() {
        cam.reset()
        cam.update(input(index = 0, distance = 300.0), 17.5, false)
        cam.update(input(index = 0, distance = 100.0), 17.5, false)  // COMMIT
        assertEquals(ManeuverCamera.Stage.COMMIT, cam.stage())
        // Maneuver 1 is 50 m later: no CRUISE reset, COMMIT holds without a write.
        assertEquals(
            ManeuverCamera.Decision.Noop,
            cam.update(input(index = 1, distance = 50.0), 17.5, false),
        )
        assertEquals(ManeuverCamera.Stage.COMMIT, cam.stage())
    }

    // ---- RECOVER vs HANDOFF ----------------------------------------------

    @Test
    fun `an isolated maneuver recovers to the speed band`() {
        cam.reset()
        val urban = { i: Int, d: Double ->
            input(index = i, distance = d, speedMs = 50.0 / 3.6)
        }
        // A complexity-2 turn at 50 km/h: ANTICIPATE 380, FRAME 220,
        // COMMIT 120, and a recovery needs 380 + max(4 s, 60 m) = 440 m of
        // separation. The next maneuver is 1.2 km away — isolated by any
        // measure.
        cam.update(urban(1, 300.0), 17.5, false)
        cam.update(urban(1, 150.0), 17.5, false)
        assertTrue(cam.envelopeLift() > 0.0, "nothing held after a FRAME")
        val r = assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(urban(2, 1_200.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.RECOVER, r.stage)
        assertEquals(17.5, r.targetZoom, 1e-9)
        assertEquals(ManeuverCamera.RECOVER_DURATION_MS, r.durationMs)
        assertEquals(0.0, cam.envelopeLift(), 1e-9)
        // And the recovery settles into CRUISE with nothing further written.
        assertEquals(
            ManeuverCamera.Decision.Noop, cam.update(urban(2, 1_150.0), 17.5, false))
        assertEquals(ManeuverCamera.Stage.CRUISE, cam.stage())
    }

    @Test
    fun `two close maneuvers hand off instead of recovering`() {
        cam.reset()
        val urban = { i: Int, d: Double ->
            input(index = i, distance = d, speedMs = 50.0 / 3.6)
        }
        // Maneuver 1 framed: the camera holds band + 1.0.
        cam.update(urban(1, 300.0), 17.5, false)
        val f = assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(urban(1, 150.0), 17.5, false))
        assertEquals(18.5, f.targetZoom, 1e-9)
        // Maneuver 2 is 300 m on — inside its own anticipation window and well
        // inside the 440 m a recovery needs. THE REGRESSION: entering
        // maneuver 2's ANTICIPATE cold would write band + 0.4 = 17.9, i.e. the
        // camera zooming OUT as the driver approaches the next junction. That
        // is the 18.8 -> 18.2 pair k-dense and j-highway both measured.
        val d = cam.update(urban(2, 300.0), 17.5, false)
        assertEquals(
            ManeuverCamera.Decision.Noop, d,
            "a handoff that needs nothing new must write nothing",
        )
        assertEquals(ManeuverCamera.Stage.HANDOFF, cam.stage())
        assertEquals(1.0, cam.envelopeLift(), 1e-9)
        // It is still a maneuver stage, so the band auto-zoom stays out of it.
        assertTrue(cam.isActive(), "a handoff must own the zoom like any stage")
        // And maneuver 2's own FRAME, being no tighter than what is held,
        // passes without a write either.
        assertEquals(
            ManeuverCamera.Decision.Noop, cam.update(urban(2, 200.0), 17.5, false))
        assertEquals(1.0, cam.envelopeLift(), 1e-9)
    }

    @Test
    fun `the RECOVER-HANDOFF boundary is the anticipation window plus a runway`() {
        // The criterion, stated once and pinned: at 50 km/h a complexity-2
        // turn anticipates from 380 m, and a recovery needs 60 m of open road
        // beyond that before it is worth easing out and back in.
        val i = input(distance = 0.0, speedMs = 50.0 / 3.6)
        assertEquals(440.0, cam.recoverAt(i, 2), 1e-9)
        // At 110 km/h the runway is the time, not the floor: 4 s of travel.
        val fast = input(distance = 0.0)
        assertEquals(
            cam.anticipateAt(fast, 2) + 110.0 / 3.6 * 4.0, cam.recoverAt(fast, 2), 1e-9)

        // A metre either side of the boundary picks the other outcome.
        for ((distance, wanted) in listOf(
            441.0 to ManeuverCamera.Stage.RECOVER,
            439.0 to ManeuverCamera.Stage.HANDOFF,
        )) {
            val c = ManeuverCamera()
            c.update(input(index = 1, distance = 300.0, speedMs = 50.0 / 3.6), 17.5, false)
            c.update(input(index = 1, distance = 150.0, speedMs = 50.0 / 3.6), 17.5, false)
            c.update(input(index = 2, distance = distance, speedMs = 50.0 / 3.6), 17.5, false)
            assertEquals(wanted, c.stage(), "at $distance m to the next maneuver")
        }
    }

    @Test
    fun `a long dense sequence stays inside one bounded envelope`() {
        // Msheireb: sixteen maneuvers, legs of 30-250 m, mixed complexity,
        // 50 km/h. Every leg is inside the 440 m a recovery needs, so the whole
        // run is one chain. What must hold across it: the camera never opens
        // up, never exceeds the tightest FRAME any maneuver in the chain asks
        // for, and never recovers in the middle.
        cam.reset()
        val base = 17.5
        val speed = 50.0 / 3.6
        val legs = listOf(180.0, 60.0, 220.0, 40.0, 150.0, 90.0, 250.0, 30.0,
                          120.0, 70.0, 200.0, 45.0, 160.0, 85.0, 110.0, 55.0)
        val types = listOf("turn-left", "slight-right", "turn-right", "slight-left")
        val writes = mutableListOf<ManeuverCamera.Decision.Transition>()
        val perManeuver = HashMap<Int, MutableList<ManeuverCamera.Stage>>()
        var maxLift = 0.0
        legs.forEachIndexed { n, leg ->
            val idx = n + 1
            val type = types[n % types.size]
            // Every third junction carries a four-lane strip: complexity 3.
            val lanes = if (n % 3 == 0) 4 else null
            var d = leg
            while (d >= 10.0) {
                val i = ManeuverCamera.Input(
                    maneuverIndex = idx,
                    maneuverType = type,
                    distanceToManeuverM = d,
                    speedMs = speed,
                    approachLanes = lanes,
                    laneUseful = lanes != null,
                )
                val dec = cam.update(i, base, false)
                if (dec is ManeuverCamera.Decision.Transition) {
                    writes += dec
                    perManeuver.getOrPut(idx) { mutableListOf() }.add(dec.stage)
                }
                maxLift = maxOf(maxLift, cam.envelopeLift())
                d -= 10.0
            }
        }
        // 1. Nothing recovered in the middle of the chain.
        assertTrue(
            writes.none { it.stage == ManeuverCamera.Stage.RECOVER },
            "the chain recovered mid-sequence: ${writes.map { it.stage }}",
        )
        // 2. The envelope never opened up: no write is below the one before it.
        writes.zipWithNext { a, b ->
            assertTrue(
                b.targetZoom >= a.targetZoom - 1e-9,
                "the camera zoomed out mid-chain: ${a.targetZoom} -> ${b.targetZoom}",
            )
        }
        // 3. And it is bounded by the tightest FRAME the chain asks for — NOT
        //    by a sum of sixteen maneuvers' worth of lift.
        val ceiling = ManeuverCamera.FRAME_LIFT + ManeuverCamera.FRAME_COMPLEXITY_LIFT * 3
        assertTrue(maxLift <= ceiling + 1e-9, "envelope reached $maxLift, ceiling $ceiling")
        writes.forEach {
            assertTrue(
                it.targetZoom - base <= ceiling + 1e-9,
                "a write reached ${it.targetZoom - base} above the band",
            )
        }
        // 4. Stage discipline survives: one write per stage per maneuver, at
        //    most four per maneuver.
        for ((idx, stages) in perManeuver) {
            assertEquals(stages.distinct(), stages, "maneuver $idx repeated a stage")
            assertTrue(stages.size <= 4, "maneuver $idx wrote ${stages.size} times")
        }
        // 5. Sixteen junctions did not cost sixteen camera moves.
        assertTrue(writes.size <= legs.size, "${writes.size} writes over ${legs.size} maneuvers")
    }

    @Test
    fun `a dense sequence returns to the band once the chain ends`() {
        cam.reset()
        val base = 17.5
        val speed = 50.0 / 3.6
        val dense = { i: Int, d: Double ->
            input(index = i, distance = d, speedMs = speed)
        }
        // Four junctions on top of one another, then the road opens up.
        for ((n, leg) in listOf(200.0, 80.0, 140.0, 60.0).withIndex()) {
            var d = leg
            while (d >= 10.0) {
                cam.update(dense(n + 1, d), base, false)
                d -= 10.0
            }
        }
        assertTrue(cam.envelopeLift() > 0.0, "the chain should still be holding a lift")
        // The next maneuver is 2 km away: the chain is over.
        val r = assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(dense(5, 2_000.0), base, false))
        assertEquals(ManeuverCamera.Stage.RECOVER, r.stage)
        assertEquals(base, r.targetZoom, 1e-9)
        assertEquals(0.0, cam.envelopeLift(), 1e-9)
        cam.update(dense(5, 1_950.0), base, false)
        assertEquals(ManeuverCamera.Stage.CRUISE, cam.stage())
        assertFalse(cam.isActive(), "the band auto-zoom must have the camera back")
    }

    @Test
    fun `a handoff re-anchors the envelope when the speed band moves under it`() {
        cam.reset()
        val speed = 50.0 / 3.6
        cam.update(input(index = 1, distance = 300.0, speedMs = speed), 17.5, false)
        cam.update(input(index = 1, distance = 150.0, speedMs = speed), 17.5, false)
        // Held at 17.5 + 1.0 = 18.5. The driver has now sped up and the band
        // underneath is 16.8; holding 18.5 would be 1.7 above it and climbing.
        // The envelope is a LIFT, so the handoff puts it back over the band the
        // driver is actually on — one write, at the junction, not a pump.
        val d = assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(input(index = 2, distance = 300.0, speedMs = speed), 16.8, false))
        assertEquals(ManeuverCamera.Stage.HANDOFF, d.stage)
        assertEquals(17.8, d.targetZoom, 1e-9)
        assertEquals(1.0, cam.envelopeLift(), 1e-9)
    }

    @Test
    fun `a pinch mid-chain hands the envelope back to the driver`() {
        cam.reset()
        val speed = 50.0 / 3.6
        cam.update(input(index = 1, distance = 300.0, speedMs = speed), 17.5, false)
        cam.update(input(index = 1, distance = 150.0, speedMs = speed), 17.5, false)
        assertEquals(1.0, cam.envelopeLift(), 1e-9)
        cam.update(input(index = 1, distance = 140.0, speedMs = speed), 17.5, true)
        assertEquals(
            0.0, cam.envelopeLift(),
            "a handoff must never carry a lift that is no longer Vector's",
        )
        // So the next maneuver, close enough to hand off to, starts clean
        // instead of inheriting a zoom the driver overrode.
        val d = assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(input(index = 2, distance = 300.0, speedMs = speed), 17.5, false))
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, d.stage)
    }

    // ---- framing (V7 Stage 2 camera tuning pass, issue 1) -----------------

    @Test
    fun `framing is never claustrophobic, at any complexity`() {
        // The measurement this pins, from two Stage 3 device runs on the same
        // slip split (V7-STAGE3-RERUN-D1-D2 §2). Scale a frame to one number —
        // the ground distance to the junction times the lens it is seen
        // through, `frameAt x 2^lift`, which is how big the junction lands on
        // the screen:
        //
        //   357  z18.344 at 199 m   whole split, callout, road beyond    OK
        //   475  z18.800 at 193 m   junction in the top third            OK
        //   583  z19.050 at 199 m   carriageway only, callout gone       NO
        //
        // ...and then the tuning-pass sweep re-measured it properly, on the
        // junction that matters (the slip split, lane strip up, which is what
        // MAKES a maneuver complexity 3) at four pinned complexities:
        //
        //   345  z18.295 at 199 m   node low, callout, road beyond        OK
        //   371  z18.398 at 199 m                                         OK
        //   396  z18.493 at 199 m   node in the top quarter, callout OK   OK
        //   425  z18.594 at 199 m   node at the very top, callout clipped  ~
        //   523  z18.594 at 245 m   junction entirely off the map         NO
        //
        // The strip costs ~15% of the map height, so a maneuver that HAS one
        // has a tighter budget than the 475 measured on a strip-less turn. 400
        // sits between the 396 that reads correctly and the 425 that does not.
        val budget = 400.0
        val i = input(distance = 0.0, speedMs = 50.0 / 3.6)
        val scaled = (0..3).map { c ->
            cam.frameAt(i, c) * Math.pow(2.0, cam.liftForStage(ManeuverCamera.Stage.FRAME, c))
        }
        scaled.forEachIndexed { c, v ->
            assertTrue(v <= budget, "complexity $c frames at $v, over the $budget budget")
        }
        // The defect in one line: complexity used to widen frameAt (180 ->
        // 240 m) AND tighten the lens (+0.8 -> +1.55), spending the same budget
        // twice, on exactly the junctions that can least afford it. That came
        // to 703 — nearly twice the budget, and half again past the 523 the
        // sweep measured with the junction completely off the map.
        assertTrue(
            240.0 * Math.pow(2.0, 0.8 + 0.25 * 3) > 523.0,
            "the regression this test exists for no longer reproduces",
        )
        // Both halves of the fix are load-bearing: 0.10 per level alone, on the
        // old 240 m entry, is still over budget.
        assertTrue(
            240.0 * Math.pow(2.0, 0.8 + 0.10 * 3) > budget,
            "the distance half of the fix is no longer needed",
        )
        // Restrained is not absent: complexity must still be visible in the
        // camera, a quarter of a zoom level between the easiest and the
        // hardest junction, on top of 120 m of extra anticipation.
        val spread = cam.liftForStage(ManeuverCamera.Stage.FRAME, 3) -
            cam.liftForStage(ManeuverCamera.Stage.FRAME, 0)
        assertTrue(spread >= 0.25, "complexity is no longer perceptible in the camera: $spread")
        assertTrue(spread <= 0.5, "complexity is back to dominating the camera: $spread")
        // ANTICIPATE is always the wider shot, at every complexity. This is
        // the invariant that rules out paying for a distant FRAME with a
        // smaller lift: below about +0.74 at complexity 3 the FRAME would be
        // WIDER than the anticipation before it, which is not a ladder.
        for (c in 0..3) {
            assertTrue(
                cam.liftForStage(ManeuverCamera.Stage.ANTICIPATE, c) <
                    cam.liftForStage(ManeuverCamera.Stage.FRAME, c),
                "ANTICIPATE is not wider than FRAME at complexity $c",
            )
        }
        // And it still points the right way: harder junction, tighter frame.
        for (c in 1..3) {
            assertTrue(
                cam.liftForStage(ManeuverCamera.Stage.FRAME, c) >
                    cam.liftForStage(ManeuverCamera.Stage.FRAME, c - 1),
                "complexity $c does not tighten on $c minus 1",
            )
        }
    }

    @Test
    fun `reset clears every latch`() {
        cam.reset()
        cam.update(input(distance = 300.0), 17.5, false)
        assertTrue(cam.isActive())
        cam.reset()
        assertFalse(cam.isActive())
        assertEquals(ManeuverCamera.Stage.CRUISE, cam.stage())
        assertIs<ManeuverCamera.Decision.Transition>(
            cam.update(input(distance = 300.0), 17.5, false))
    }

    @Test
    fun `arrive gets the gentle frame and nothing more`() {
        cam.reset()
        // A zero-complexity arrive at town speed: the gentlest rungs on the
        // ladder and no more. ANTICIPATE at 300 m is +0.4; FRAME at 180 m is
        // +0.8, the smallest FRAME there is; COMMIT writes nothing. The camera
        // must never over-react to the end of the journey.
        val arrive = { d: Double ->
            cam.update(input(type = "arrive", speedMs = 50.0 / 3.6, distance = d), 17.5, false)
        }
        val t = assertIs<ManeuverCamera.Decision.Transition>(arrive(200.0))
        assertEquals(ManeuverCamera.Stage.ANTICIPATE, t.stage)
        assertEquals(17.5 + 0.4, t.targetZoom, 1e-9)
        val f = assertIs<ManeuverCamera.Decision.Transition>(arrive(170.0))
        assertEquals(ManeuverCamera.Stage.FRAME, f.stage)
        assertEquals(17.5 + 0.8, f.targetZoom, 1e-9)
        assertEquals(ManeuverCamera.Decision.Noop, arrive(100.0))
        assertEquals(ManeuverCamera.Stage.COMMIT, cam.stage())
    }

    @Test
    fun `no maneuver index means noop`() {
        cam.reset()
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(ManeuverCamera.Input(), 17.5, false))
    }

    @Test
    fun `an index flicker at a junction never re-fires written stages`() {
        cam.reset()
        // Approach maneuver 5 fully.
        cam.update(input(index = 5, distance = 300.0), 17.5, false)
        cam.update(input(index = 5, distance = 130.0), 17.5, false)  // FRAME
        val writesBefore = cam.stage()
        // GPS flicker: 6 then back to 5, then 6 again — the intersection
        // wiggle a dense junction produces. None of it may write again.
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(index = 6, distance = 90.0), 17.5, false))
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(index = 5, distance = 60.0), 17.5, false))
        assertEquals(ManeuverCamera.Decision.Noop, cam.update(input(index = 6, distance = 40.0), 17.5, false))
        // And genuine progress past the flap zone still latches fresh.
        val next = cam.update(input(index = 7, distance = 900.0), 17.5, false)
        assertIs<ManeuverCamera.Decision.Transition>(next)
        assertTrue(writesBefore != cam.stage())
    }
}