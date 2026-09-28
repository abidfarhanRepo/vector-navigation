/*
 * "The map is not blank" (ticket 49) + basemap content coverage.
 *
 * Requires the stack running (bash bootstrap.sh && docker compose up -d).
 * Run: node --test vector-web/tests/e2e/render.spec.mjs
 *
 * This is the test that would have caught, on the day it happened:
 *   - tiles baked one directory too deep, so every basemap tile 404'd and the
 *     viewer rendered ONE feature over central Doha;
 *   - a per-tile budget consumed entirely by roads, so water/parks/labels were
 *     cut to zero in dense tiles;
 *   - an origin-bound tile URL in a packaged WebView (the Shipaton risk).
 *
 * None of those are visible to an HTTP-status check: every request 200s.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import {
  launch, driveContext, openViewer, renderedLayers, installGpsSimulator, BASE,
} from "./harness.mjs";

let browser, ctx, page, pageErrors;

before(async () => {
  browser = await launch();
  ctx = await driveContext(browser, { lng: 51.531, lat: 25.2854 }); // central Doha
  page = await ctx.newPage();
  // Install the GPS simulator and never push a fix.
  //
  // The viewer auto-starts GPS follow, so a real fix arrives asynchronously
  // AFTER the map goes idle, recentres the camera, and the tiles for the new
  // viewport reload underneath the query. That is what made this suite report
  // "the map is blank" on roughly one run in three: the feature count reached
  // 200 and then dropped to 32 mid-assertion.
  //
  // These tests are about whether the basemap RENDERS, not about following a
  // vehicle, so a silent geolocation keeps the camera where the test put it.
  await installGpsSimulator(page);
  ({ errors: pageErrors } = await openViewer(page));

  // Put the camera where the assertions expect it, explicitly.
  //
  // The viewer auto-starts GPS follow and only falls back to a default centre
  // when geolocation is ABSENT — so with the simulator installed (and silent)
  // it never recentres, and with a real fix it recentres asynchronously AFTER
  // the map goes idle, reloading tiles underneath the query. Either way the
  // viewport was decided by a race. Setting it here makes the test deterministic
  // and independent of whether a fix ever arrives.
  await page.evaluate(() => {
    window.__map.jumpTo({ center: [51.5310, 25.2854], zoom: 12 });
  });
});

after(async () => {
  await browser?.close();
});

test("the viewer loads with no page errors", () => {
  assert.deepEqual(pageErrors, [], `page errors: ${pageErrors.join(" | ")}`);
});

test("the map is not blank over central Doha", async () => {
  const r = await renderedLayers(page, { minFeatures: 200 });
  assert.ok(
    r.total > 200,
    `only ${r.total} features rendered at z${r.zoom.toFixed(1)} — the map is blank. ` +
      `Check the tile path depth and that /tiles/{z}/{x}/{y}.mvt is not 404ing.`
  );
});

test("roads render, and major classes are present", async () => {
  const { byLayer } = await renderedLayers(page, { minFeatures: 200 });
  assert.ok(byLayer["roads-major"] > 0, "no major roads rendered");
  assert.ok(byLayer["roads-hi"] > 0, "no motorway/trunk rendered");
});

test("the basemap is more than roads", async () => {
  // The regression: a per-tile cap filled entirely by roads left water, parks
  // and labels at zero, so the city rendered as lines on a void. Every one of
  // these kinds exists in the source GeoJSON for Doha.
  const { byLayer } = await renderedLayers(page, { minFeatures: 200 });
  const nonRoad = ["water", "park", "landuse", "natural", "labels", "road-labels"]
    .filter((id) => (byLayer[id] || 0) > 0);
  assert.ok(
    nonRoad.length >= 2,
    `only [${nonRoad.join(", ")}] of the non-road layers rendered — ` +
      `the per-tile budget is being eaten by roads again. Rendered: ${JSON.stringify(byLayer)}`
  );
});

test("road labels render, so the map is legible", async () => {
  const { byLayer } = await renderedLayers(page, { minFeatures: 200 });
  assert.ok((byLayer["road-labels"] || 0) > 0, "no road labels — the map is unreadable");
});

test("tiles are served for the viewport, not just the centre", async () => {
  // Pan a full viewport east and confirm the new tiles resolve too. A partially
  // baked tree renders at the origin and blanks the moment you move.
  await page.evaluate(() => {
    window.__map.panBy([400, 0], { duration: 0 });
  });
  const r = await renderedLayers(page, { minFeatures: 200 });
  assert.ok(r.total > 200, `after panning, only ${r.total} features rendered`);
});
