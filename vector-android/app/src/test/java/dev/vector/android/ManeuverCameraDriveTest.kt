package dev.vector.android

import dev.vector.geo.LaneGuidance
import dev.vector.geo.ManeuverCamera
import dev.vector.geo.SimFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V7 Stage 2 acceptance at the drive level: the RECORDED Doha traces replayed
 * through the pure camera policy.
 *
 * The camera is fed exactly the per-fix facts the app feeds it on the road —
 * maneuver index, type, distance to the maneuver, speed — sampled from traces
 * the live backend actually returned (nothing hand-drawn). The plan's
 * acceptance for the drive scenarios: stage order with no repeats, at most one
 * write per stage, ≤4 writes per maneuver, a real tighten on the motorway
 * trace, and a return to the band afterwards. This is the scenario half;
 * `ManeuverCameraTest` in core-geo pins the exact thresholds.
 *
 * ## Why the replay runs a CameraGate
 *
 * It did not, and that is exactly why it passed through the whole of V7 Stage 2
 * while the integrated camera was suspending itself on every Doha drive. The
 * replay used to hand `driverZoomed = false` on every fix, so the one fact the
 * defect lived in was stubbed out. It now models the Activity's fix loop: a
 * gate that learns the zoom each Vector write asserts, a map zoom that only
 * moves when something writes it, and `driverZoomed` derived from the two —
 * the same three lines `maybeManeuverCamera` runs. See `CameraOwnershipTest`
 * for the focused version, and D1 in V7-STAGE3-DEVICE-VALIDATION.md.
 */
class ManeuverCameraDriveTest {

    private fun drive(plan: DriveHarness.Plan, fixes: List<SimFix>): DriveHarness {
        val h = DriveHarness(DriveHarness.deadRouter())
        h.preview(plan, plan.geometry.last(), "West Bay")
        h.start()
        h.drive(fixes)
        return h
    }

    /** Replay the trace through [ManeuverCamera] and report the invariants.
     *
     * @return the harness, so a scenario can make per-route assertions.
     */
    private fun DriveHarness.arrivals() =
        events.filterIsInstance<DriveHarness.Event.Arrived>()
    private fun replay(
        plan: DriveHarness.Plan,
        fixes: List<SimFix>,
        requireFrame: Boolean,
    ): DriveHarness {
        val h = drive(plan, fixes)
        val cam = ManeuverCamera()
        val byIndex = plan.maneuvers.associateBy { it.index }
        // The Activity's loop: one gate, one map zoom, ownership derived.
        val gate = CameraGate()
        val baseZoom = 17.5
        var mapZoom = baseZoom
        var nowMs = 0L
        var suspensions = 0
        var totalWrites = 0
        var maxPerManeuver = 0
        val perManeuver = HashMap<Int, Int>()
        val stagesPerManeuver = HashMap<Int, MutableList<ManeuverCamera.Stage>>()
        var sawFrame = false
        var sawAnticipate = false
        for (s in h.samples) {
            nowMs += 1_000
            val idx = s.maneuverIndex ?: continue
            val m = byIndex[idx] ?: continue
            val input = ManeuverCamera.Input(
                maneuverIndex = idx,
                maneuverType = m.type,
                distanceToManeuverM = s.distanceToManeuverM,
                speedMs = (s.speedKmh ?: 0) / 3.6,
                approachLanes = m.approachLanes,
                laneUseful = m.laneStatus == LaneGuidance.Status.USEFUL,
            )
            val driverZoomed = gate.isDriverZoom(mapZoom, nowMs)
            if (driverZoomed) suspensions++
            val d = cam.update(input, baseZoom, driverZoomed)
            if (d is ManeuverCamera.Decision.Transition) {
                totalWrites++
                gate.beginTransition(nowMs, d.durationMs, d.targetZoom)
                mapZoom = d.targetZoom
                val n = (perManeuver[idx] ?: 0) + 1
                perManeuver[idx] = n
                maxPerManeuver = maxOf(maxPerManeuver, n)
                stagesPerManeuver.getOrPut(idx) { mutableListOf() }.add(d.stage)
                if (d.stage == ManeuverCamera.Stage.FRAME) sawFrame = true
                if (d.stage == ManeuverCamera.Stage.ANTICIPATE ||
                    d.stage == ManeuverCamera.Stage.FRAME) sawAnticipate = true
            }
        }
        // Nobody pinched anything on a replayed trace, so every one of these
        // would be the camera mistaking its own write for a driver — D1.
        assertEquals(
            "the camera read its own zoom as a driver pinch on $suspensions fixes",
            0, suspensions,
        )
        // And when it is cruising it must be ON the band. Conditioned on the
        // stage because a trace that arrives mid-approach legitimately ends in
        // COMMIT holding the FRAME zoom — clearing that residue is the app's
        // arrival flow, as the assertion further down already records. What D1
        // produced was the other thing: CRUISE, parked 0.7-1.4 above the band,
        // with the machine suspended and no recovery coming.
        if (cam.stage() == ManeuverCamera.Stage.CRUISE) {
            assertEquals(
                "cruising, but the camera never returned to the band (zoom $mapZoom)",
                baseZoom, mapZoom, 0.001,
            )
        }
        // Stage discipline per maneuver: one eased write per STAGE, never a
        // repeat, and never more than four writes on a full approach. Exact
        // ordering is not asserted — FRAME can legitimately be a maneuver's
        // first write when the trace first touches it inside the frame window,
        // and a hysteresis retreat or a genuine re-approach restarts the
        // machine. What must never happen is the same stage firing twice.
        for ((idx, stages) in stagesPerManeuver) {
            assertEquals(
                "maneuver $idx repeated a stage write: $stages",
                stages.distinct(),
                stages,
            )
            assertTrue("maneuver $idx wrote more than 4 times: $stages", stages.size <= 4)
        }
        // The end of a drive may leave the machine mid-COMMIT on the arrive
        // maneuver; clearing that residue is the APP's arrival flow (which
        // resets the camera), not a camera defect. So: either it fully
        // cruised, or the drive actually arrived.
        assertTrue(
            "camera left mid-approach with no arrival to clear it: ${cam.stage()}" +
                " arrived=${h.arrivals().size}",
            cam.stage() == ManeuverCamera.Stage.CRUISE || h.arrivals().isNotEmpty(),
        )
        assertTrue("a maneuver wrote more than 4 times ($maxPerManeuver)", maxPerManeuver <= 4)
        assertTrue("the drive never produced an ANTICIPATE write (total $totalWrites)", sawAnticipate)
        if (requireFrame) {
            assertTrue("the motorway trace must tighten to FRAME", sawFrame)
        }
        return h
    }

    // =======================================================================
    // Scenario J — Al Wakrah → Doha expressway (the one useful lane strip)
    // =======================================================================

    @Test
    fun `the expressway trace tightens to FRAME and recovers, no repeats`() {
        replay(ScenarioTraces.highway(), ScenarioTraces.j(ScenarioTraces.highway()),
            requireFrame = true)
    }

    // =======================================================================
    // Even without a single lane of lane data the camera still behaves:
    // these traces carry no per-maneuver lane fields at all.
    // =======================================================================

    @Test
    fun `a dense urban trace never pumps and recovers between short legs`() {
        val plan = ScenarioTraces.dense()
        replay(plan, ScenarioTraces.k(plan), requireFrame = false)
    }

    @Test
    fun `a city trace frames its turns and recovers`() {
        // requireFrame, unlike before: with the distance-first stage entries a
        // 50 km/h city turn reaches FRAME at 180-240 m, well outside the 120 m
        // COMMIT boundary. Under the time-budget formulation this assertion
        // could not have held at any urban speed — that was D2.
        val plan = ScenarioTraces.city()
        replay(plan, ScenarioTraces.a(plan), requireFrame = true)
    }

    @Test
    fun `the slip-split trace reaches FRAME on the useful-lane approach`() {
        val plan = ScenarioTraces.slipSplit()
        replay(plan, ScenarioTraces.slip(plan), requireFrame = true)
    }

    @Test
    fun `a 27 km no-lane-data trace works end to end`() {
        val plan = ScenarioTraces.long()
        replay(plan, ScenarioTraces.longRun(plan), requireFrame = false)
    }
}