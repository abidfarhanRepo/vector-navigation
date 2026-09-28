/*
 * Arrival: the banner, and the ETA sample that goes with it.
 *
 * Requires the stack running. Run:
 *   node --test vector-web/tests/e2e/arrival.spec.mjs
 *
 * This exists because of a defect that no unit test could have seen and no
 * screenshot would have shown. The viewer patches `checkArrival` late in the
 * file to add the arrival banner, and the wrapper re-implemented the original's
 * guards — including `etaTrip = null` — before calling it. The original's first
 * line is `if (!etaTrip) return`, so it returned immediately every time and the
 * POST to `/eta` never happened.
 *
 * `/eta` is the falsifiable claim behind the entire learned-speed programme
 * (issue 07 -> issue 10): predicted-versus-observed duration, split by learned
 * coverage. An empty distribution looks exactly like "nobody has finished a
 * journey yet", so the failure was invisible while every dashboard stayed
 * green. The only way to catch it is to drive a route to its end and watch the
 * network.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import {
  launch, driveContext, openViewer, fetchRoute, trackAlongRoute, driveTrack,
  installGpsSimulator,
} from "./harness.mjs";

// Deliberately SHORT. The viewer refuses to record a trip under 10 s of
// observed time ("someone tapping a destination they are already at"), and the
// drive is compressed by `speedup`, so the track has to be long enough in
// WALL-CLOCK terms to clear that floor while staying quick to run.
const DOHA = { from: "25.2854,51.5310", to: "25.2960,51.5250" };

let browser, ctx, page, etaPosts, pageErrors;

before(async () => {
  const route = await fetchRoute(DOHA.from, DOHA.to);
  const track = trackAlongRoute(route.geometry.coordinates, { speedKmh: 40, hz: 1 });

  browser = await launch();
  ctx = await driveContext(browser, { lng: track[0].lng, lat: track[0].lat });
  page = await ctx.newPage();

  // Capture the POST and ANSWER IT HERE. The request must not reach the real
  // service: this is a time-compressed simulated drive (`speedup` below), so
  // its observed duration is a quarter of the predicted one and recording it
  // would put a ~270% error into the very distribution the endpoint exists to
  // measure. A test that corrupts the metric it is testing is worse than no
  // test. Anyone adding another drive test needs this stub too.
  etaPosts = [];
  await page.route("**/eta", async (route, request) => {
    if (request.method() !== "POST") return route.continue();
    let body = null;
    try { body = JSON.parse(request.postData() || "null"); } catch { /* keep null */ }
    etaPosts.push(body);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ status: "ok", abs_pct_error: 0, is_learned: false }),
    });
  });

  await installGpsSimulator(page);
  ({ errors: pageErrors } = await openViewer(page, { from: DOHA.from, to: DOHA.to }));
  await page.waitForFunction(() => {
    const s = document.getElementById("navhead");
    return !!s;
  }, { timeout: 30000 });

  // `speedup: 4` keeps the whole drive over the 10 s observed-time floor while
  // still finishing in well under a minute.
  await driveTrack(ctx, page, track, { hz: 1, speedup: 4 });
  // The last fix lands ON the destination; give the arrival handler a moment.
  await page.waitForTimeout(1500);
});

after(async () => { await browser?.close(); });

test("driving to the end of the route shows the arrival banner", async () => {
  const shown = await page.evaluate(() => {
    const a = document.getElementById("arrival");
    return !!a && getComputedStyle(a).display !== "none";
  });
  assert.ok(shown, "the arrival banner never appeared after driving the whole route");
});

test("arriving posts the ETA sample that /eta exists to collect", async () => {
  assert.ok(etaPosts.length > 0,
    "no POST to /eta after a completed drive — the learned-speed claim is unfalsifiable");
});

test("the ETA sample carries what the distribution is split on", async () => {
  const s = etaPosts[0];
  assert.ok(s, "no ETA sample to inspect");
  assert.equal(typeof s.predicted_s, "number");
  assert.equal(typeof s.observed_s, "number");
  assert.ok(s.predicted_s > 0, `predicted_s must be positive, got ${s.predicted_s}`);
  assert.ok(s.observed_s > 0, `observed_s must be positive, got ${s.observed_s}`);
  // `coverage` is what splits learned from unlearned. Zero is a legitimate
  // value; ABSENT is the bug ADR-0072 was written about.
  assert.ok("coverage" in s, "the sample carries no learned coverage to split on");
});

test("arrival is reported exactly once", async () => {
  assert.equal(etaPosts.length, 1,
    `arrival must report one sample, got ${etaPosts.length}`);
});

test("the drive produced no page errors", async () => {
  assert.deepEqual(pageErrors, [], `page errors during the drive: ${pageErrors.join(" | ")}`);
});
