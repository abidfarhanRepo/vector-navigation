/*
 * GPS + CPU consumption during a simulated drive (tickets 48 / 54).
 *
 * Requires the stack running. Run:
 *   node --test vector-web/tests/e2e/perf.spec.mjs
 *   PERF_REPORT=1 node --test vector-web/tests/e2e/perf.spec.mjs   # print numbers
 *
 * The budgets below are DELIBERATELY generous first values. No performance
 * numbers existed for this app before this file, so a tight budget would just
 * be a number someone made up. They are here to catch a regression of the
 * "10x worse" kind; tighten them once a few runs have established the spread.
 *
 * A headless SwiftShader renderer is slower than a phone GPU at drawing and
 * faster than a phone CPU at scripting, so treat FPS as a relative signal
 * between runs, not as a phone prediction. ScriptDuration is the portable one.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import {
  launch, driveContext, openViewer, startMetrics, readMetrics,
  fetchRoute, trackAlongRoute, driveTrack, installGpsSimulator, gpsStats,
} from "./harness.mjs";

const REPORT = !!process.env.PERF_REPORT;
const DOHA = { from: "25.2854,51.5310", to: "25.3548,51.4830" };
const FIXES = 240; // a 4-minute drive at 1 Hz

let browser, ctx, page, cdp, metrics, track, pageErrors, gps;

before(async () => {
  const route = await fetchRoute(DOHA.from, DOHA.to);
  track = trackAlongRoute(route.geometry.coordinates, { speedKmh: 60, hz: 1 });

  browser = await launch();
  ctx = await driveContext(browser, { lng: track[0].lng, lat: track[0].lat });
  page = await ctx.newPage();
  cdp = await ctx.newCDPSession(page);
  await cdp.send("Performance.enable");

  // Must precede openViewer: the viewer registers its watchPosition during
  // load, so a simulator installed afterwards would never see it.
  await installGpsSimulator(page);
  ({ errors: pageErrors } = await openViewer(page));

  await startMetrics(page);
  // Do NOT click #gps-toggle. The viewer already starts a watch on load, so a
  // click takes the "full stop" branch and CLEARS it — which is how the first
  // version of this file measured an idle map and reported it as a drive.
  await driveTrack(ctx, page, track.slice(0, FIXES), { hz: 1, speedup: 12 });
  gps = await gpsStats(page);
  metrics = await readMetrics(page, cdp);
  if (REPORT) {
    console.log("\n--- drive metrics (" + gps.fired + " GPS fixes delivered) ---");
    console.log(JSON.stringify({
      wallSeconds: +metrics.seconds.toFixed(1),
      fps: +metrics.fps.toFixed(1),
      taskDurationS: +metrics.taskDurationS.toFixed(2),
      scriptDurationS: +metrics.scriptDurationS.toFixed(2),
      layoutDurationS: +metrics.layoutDurationS.toFixed(3),
      recalcStyleS: +metrics.recalcStyleDurationS.toFixed(3),
      jsHeapUsedMB: +metrics.jsHeapUsedMB.toFixed(1),
      longTasks: metrics.longTasks,
      longTaskMs: Math.round(metrics.longTaskMs),
      domNodes: metrics.nodes,
    }, null, 2));
  }
});

after(async () => {
  await browser?.close();
});

test("the simulated drive actually reached the app", () => {
  // The guard on every number below. Without it this suite happily measures an
  // idle map: Playwright's own setGeolocation does not re-fire watchPosition,
  // so the first version of these tests reported 56 fps and 0.1 s of script for
  // a "drive" during which the app received exactly one position.
  assert.equal(gps.watchers, 1, "the viewer should hold exactly one GPS watch");
  assert.equal(gps.fired, FIXES, `only ${gps.fired}/${FIXES} fixes were delivered`);
  assert.deepEqual(gps.errors, []);
});

test("a drive produces no page errors", () => {
  assert.deepEqual(pageErrors, [], `page errors: ${pageErrors.join(" | ")}`);
});

test("the render loop keeps up during a drive", () => {
  // Measured baseline 2026-09-08, headless SwiftShader (software rasteriser):
  //   idle map                 ~56 fps
  //   real-time 1 Hz drive     ~15 fps
  //   12x compressed drive     ~13 fps
  // A software rasteriser is far slower at drawing than any phone GPU, so this
  // is a REGRESSION FLOOR, not a phone prediction. The interesting part of the
  // measurement is the ratio: driving costs ~4x the idle frame budget, and
  // scripting accounts for under 10% of it — the cost is continuous map
  // re-render driven by a 600 ms easeTo per fix, which is what ticket 37
  // (snap + dead-reckon) is meant to remove.
  assert.ok(metrics.fps > 8, `fps ${metrics.fps.toFixed(1)} — worse than the measured floor`);
});

test("CPU per GPS fix stays within budget", () => {
  // The portable number — unlike fps, this is not dominated by the software
  // rasteriser. Measured 2026-09-08: 67 ms/fix at real-time 1 Hz, i.e. a ~7%
  // duty cycle. Script is only ~0.2 s of it; the rest is render.
  const msPerFix = (metrics.taskDurationS * 1000) / Math.max(1, gps.fired);
  assert.ok(msPerFix < 200, `${msPerFix.toFixed(0)} ms of CPU per GPS fix`);
});

test("script time stays a minority of wall time", () => {
  // The battery proxy: how much of the drive was spent running our JS. ADR-0070
  // is blocked on a measured battery cost; this is the closest stand-in that
  // does not need a phone.
  const share = metrics.scriptDurationS / metrics.seconds;
  assert.ok(share < 0.5, `script used ${(share * 100).toFixed(0)}% of wall time`);
});

test("no single task blocks the main thread for long", () => {
  const worst = metrics.longTaskMs / Math.max(1, metrics.longTasks);
  assert.ok(
    metrics.longTasks === 0 || worst < 500,
    `${metrics.longTasks} long tasks, mean ${worst.toFixed(0)} ms — the UI will feel frozen`
  );
});

test("the DOM does not grow without bound during a drive", () => {
  // The step list and the maneuver bar re-render every fix. A leak here is the
  // classic cause of a nav app that degrades over a long journey.
  assert.ok(metrics.nodes < 5000, `${metrics.nodes} DOM nodes after a 4-minute drive`);
});

test("the JS heap stays within a phone-plausible budget", () => {
  assert.ok(metrics.jsHeapUsedMB < 150, `heap ${metrics.jsHeapUsedMB.toFixed(1)} MB`);
});
