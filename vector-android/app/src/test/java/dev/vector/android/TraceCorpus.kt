package dev.vector.android

import dev.vector.geo.SimFix

/**
 * The device-replay corpus, defined once.
 *
 * ## Why this file exists
 *
 * The same traces have two consumers with two different jobs:
 *
 *  * [TraceExportTest] writes each one to `build/traces/<name>.csv` so
 *    `scripts/simulate_drive.sh` can push it to a handset and replay it through
 *    the real fused-location provider;
 *  * [TraceContractTest] asserts the physical and structural properties the
 *    replay depends on.
 *
 * When both held their own copy of the list, adding a scenario to one and not
 * the other produced a trace that was exported but never checked, or worse,
 * checked against a scenario nobody replays. That is the same class of drift
 * `ScenarioTraces` itself was created to remove — one definition of "scenario C"
 * for the simulation and for the device — so the list lives here and the two
 * tests read it.
 *
 * ## [faulted] is not decoration
 *
 * Two scenarios inject a deliberate physics violation: `scenario-g-jump` a
 * reflected 300 m fix, `scenario-h-outage` a 25-second silence. Every other
 * trace must be physically plausible — a nominal trace that acquires an
 * impossible step has silently stopped being a nominal trace, and the device run
 * would measure something nobody asked about.
 *
 * The reverse matters just as much: an adversarial trace that *stops* violating
 * physics is a test that no longer tests its fault, and it would keep reporting
 * green. [TraceContractTest] asserts both directions.
 */
object TraceCorpus {

    /**
     * One replayable trace.
     *
     * @param name the CSV basename, and the argument to `simulate_drive.sh`.
     * @param note written into the CSV header so a file on a handset says what
     *   it is without the shell script's help.
     * @param fixes the fix stream, from [ScenarioTraces].
     * @param destination `lat,lng` of the route's end, for `--es vectorDest`.
     * @param faulted true when the scenario deliberately violates physics.
     */
    data class Trace(
        val name: String,
        val note: String,
        val fixes: List<SimFix>,
        val destination: String,
        val faulted: Boolean = false,
    )

    fun all(): List<Trace> {
        val city = ScenarioTraces.city()
        val highway = ScenarioTraces.highway()
        val dense = ScenarioTraces.dense()
        val long = ScenarioTraces.long()
        val slip = ScenarioTraces.slipSplit()
        // V7 Stage 4. These two are the only fixtures in the corpus that carry
        // `lane_data.forward_lanes`, so they are the only traces on which a
        // device run can see the lateral model do anything at all — the other
        // thirteen exercise the decline path, which looks exactly like the
        // pre-Stage-4 behaviour by design.
        val twoWay = ScenarioTraces.twoWay()
        val roundabout = ScenarioTraces.roundabout()

        return listOf(
            Trace("scenario-a-city", "normal city drive, Souq Waqif to West Bay",
                  ScenarioTraces.a(city), destOf(city)),
            Trace("scenario-b-stopgo", "stop-and-go through 0/5/15/30/50/80 km/h",
                  ScenarioTraces.b(city), destOf(city)),
            Trace("scenario-c-missed-turn", "carries straight on past a left turn, then follows the reroute",
                  ScenarioTraces.c(city).first, destOf(city)),
            Trace("scenario-d-wrong-road", "service road 28 m out, then diverges properly",
                  ScenarioTraces.d(city).first, destOf(city)),
            Trace("scenario-e-uturn", "drives past a turn, stops, reverses",
                  ScenarioTraces.e(city).first, destOf(city)),
            Trace("scenario-f-drift", "urban-canyon correlated drift throughout",
                  ScenarioTraces.f(city), destOf(city)),
            Trace("scenario-g-jump", "300 m reflected fix for 3 s at t=60",
                  ScenarioTraces.g(city), destOf(city), faulted = true),
            Trace("scenario-h-outage", "25 s with no fixes at all from t=90",
                  ScenarioTraces.h(city), destOf(city), faulted = true),
            Trace("scenario-j-highway", "sustained 110 km/h on the expressway",
                  ScenarioTraces.j(highway), destOf(highway)),
            Trace("scenario-k-dense", "dense Msheireb streets, short legs, repeated stops",
                  ScenarioTraces.k(dense), destOf(dense)),
            Trace("scenario-m-slip", "C Ring / Al Corniche slip split at 50 km/h — the one useful lane strip",
                  ScenarioTraces.slip(slip), destOf(slip)),
            Trace("scenario-l-arrival", "drives past the destination",
                  ScenarioTraces.arrival(city, kmh = 50.0, overshootM = 120.0), destOf(city)),
            Trace("long-run", "27 km, a real missed exit at 23 km, canyon noise",
                  ScenarioTraces.longRun(long), destOf(long)),
            Trace("scenario-n-twoway", "V7 Stage 4 — four one-lane-each-way legs, the carriageway the ribbon must move onto",
                  ScenarioTraces.twoWayDrive(twoWay), destOf(twoWay)),
            Trace("scenario-p-roundabout", "V7 Stage 4 — two roundabouts; the approach is placed, the ring is not",
                  ScenarioTraces.roundaboutDrive(roundabout), destOf(roundabout)),
        )
    }

    /** The destination as `am start --es vectorDest` wants it. */
    private fun destOf(plan: DriveHarness.Plan): String =
        "%.6f,%.6f".format(plan.geometry.last().lat, plan.geometry.last().lng)
}
