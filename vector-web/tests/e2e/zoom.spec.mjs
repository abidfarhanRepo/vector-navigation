/*
 * The COUNTRY -> REGION -> CITY -> DISTRICT -> STREET -> NAVIGATION ladder.
 *
 * Requires the stack running (bash bootstrap.sh && docker compose up -d).
 * Run: node --test vector-web/tests/e2e/zoom.spec.mjs
 *
 * This is the regression guard for the V3 P0, reported by the user as "when
 * zoomed far out, the roads/tiles/map of Qatar become entirely invisible".
 * Probed against the live tile server at the start of the pass:
 *
 *     z4..z10   404 Not Found      <- the whole country -> city range
 *     z11       200  83,719 bytes
 *     z14       200  68,199 bytes
 *
 * Nothing below z11 had ever been baked. `/tiles/version` said so, and both
 * clients correctly declared the range it reported — so no HTTP check, no style
 * check and no client test could see anything wrong. The failure only exists
 * where the two meet: MapLibre does not UNDER-zoom the way it over-zooms above
 * a source's maximum. Below `minzoom` it requests nothing and paints the
 * background colour, silently, with every request in the log a success.
 *
 * `render.spec.mjs` asserts the map is not blank at ONE zoom (z12). That is why
 * it stayed green throughout. The whole point here is to walk the ladder.
 *
 * A second defect this covers, found while fixing the first: the viewer FETCHED
 * the server's zoom range and never applied it. `refreshTileEpoch` called
 * `src.setTiles()`, which re-points a source's URL and does not change its
 * minzoom/maxzoom — so the range was stored in a variable and the style kept
 * the 11/13 defaults for the life of the tab. With the bake at z11-z14 that
 * silently discarded the z14 POI tiles V2 had just baked.
 */
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import {
  launch, driveContext, openViewer, renderedLayers, installGpsSimulator, BASE, webToken,
} from "./harness.mjs";

/** Roughly the centre of Qatar, so a country-scale view is full of country. */
const QATAR = { lng: 51.18, lat: 25.35 };

/** Doha, for the city and street rungs. */
const DOHA = { lng: 51.531, lat: 25.2854 };

let browser, ctx, page, pageErrors, baked;

before(async () => {
  baked = await (await fetch(`${BASE}/tiles/version`, {
    headers: { Authorization: `Bearer ${webToken()}` },
  })).json();

  browser = await launch();
  ctx = await driveContext(browser, QATAR);
  page = await ctx.newPage();
  // Silent GPS: these tests move the camera themselves, and a real fix
  // recentring asynchronously after idle is what made the render suite flaky.
  await installGpsSimulator(page);
  ({ errors: pageErrors } = await openViewer(page));

  // Wait for the client to have APPLIED the range, not merely to have gone
  // idle. The map is built synchronously and re-styled once /tiles/version
  // answers, so `idle` fires on the pre-restyle document too — which made the
  // two assertions below pass or fail depending on which side of that the
  // browser happened to land. The client publishes `window.__tileZooms` when
  // it has read the range, which is the event these tests actually depend on.
  await page.waitForFunction(() => !!window.__tileZooms, { timeout: 30000 });
  await page.evaluate(() => new Promise((resolve) => {
    const cap = setTimeout(resolve, 15000);
    if (window.__map.isStyleLoaded()) { clearTimeout(cap); resolve(); return; }
    window.__map.once("idle", () => { clearTimeout(cap); resolve(); });
  }));
});

after(async () => {
  await browser?.close();
});

/**
 * How much ground the viewport covers, in square kilometres.
 *
 * Needed because feature COUNTS are not comparable across zooms: the whole
 * point of a zoom hierarchy is that a wide view shows fewer classes over more
 * ground, so the count can rise while the detail falls.
 */
async function groundAreaKm2() {
  return page.evaluate(() => {
    const b = window.__map.getBounds();
    const midLat = (b.getNorth() + b.getSouth()) / 2;
    const kmPerDegLat = 110.574;
    const kmPerDegLon = 111.320 * Math.cos((midLat * Math.PI) / 180);
    return (
      Math.abs(b.getNorth() - b.getSouth()) * kmPerDegLat *
      Math.abs(b.getEast() - b.getWest()) * kmPerDegLon
    );
  });
}

/** Put the camera somewhere and report what actually drew. */
async function at(zoom, centre = QATAR, minFeatures = 1) {
  await page.evaluate(
    ([z, lng, lat]) => window.__map.jumpTo({ center: [lng, lat], zoom: z }),
    [zoom, centre.lng, centre.lat],
  );
  return renderedLayers(page, { minFeatures });
}

// ---------------------------------------------------------------------------
// The bake
// ---------------------------------------------------------------------------

test("the bake reaches country scale", () => {
  // Qatar is ~160 km north to south. Fitting that on a phone screen is z7-z8,
  // so a bake starting at z11 (about 20 km across) has no country view at all.
  assert.ok(
    baked.minzoom <= 8,
    `the tile set starts at z${baked.minzoom}; the whole country does not fit on a ` +
      `phone screen until about z7, so every wider view renders nothing`,
  );
});

test("the tile server actually serves the low zooms it claims", () => {
  // Claiming a range the directories do not contain is the same class of defect
  // in the other direction.
  const checks = [];
  for (let z = baked.minzoom; z <= Math.min(baked.maxzoom, 14); z++) {
    const n = 2 ** z;
    const x = Math.floor(((DOHA.lng + 180) / 360) * n);
    const y = Math.floor(
      ((1 -
        Math.log(
          Math.tan((DOHA.lat * Math.PI) / 180) + 1 / Math.cos((DOHA.lat * Math.PI) / 180),
        ) / Math.PI) / 2) * n,
    );
    checks.push({ z, url: `${BASE}/tiles/${z}/${x}/${y}.mvt` });
  }
  return Promise.all(
    checks.map(async ({ z, url }) => {
      const r = await fetch(url, { headers: { Authorization: `Bearer ${webToken()}` } });
      assert.equal(r.status, 200, `z${z} over Doha returned ${r.status}`);
    }),
  );
});

test("an empty tile inside the baked range is not reported as missing", async () => {
  // A missing tile has two completely different meanings and 404 answered
  // both, which is what let the original defect hide in plain sight.
  //
  // The bake writes no file where there are no features, so Qatar's low zooms
  // legitimately have holes — open sea and the desert outside the region bbox.
  // Measured on an S24 at country zoom: 6/40/27, 6/41/26, 7/81/54, 7/81/55 and
  // 7/82/53 are all empty water, and all five logged as tile-loading errors.
  //
  // 204 for "nothing here", 404 for "this zoom does not exist". MapLibre
  // renders both as an empty tile, so no pixel changes; what changes is that a
  // log grep can now tell empty sea from half the zoom range being absent.
  // The empty tiles are FOUND rather than hardcoded. The first version of this
  // test pinned 6/40/27, which was empty water when it was written and stopped
  // being empty the moment the coastline was baked into it — a test that
  // depends on which square of sea happens to have no features in it is a test
  // that breaks on unrelated map improvements.
  //
  // Sweeping the neighbourhood is also the stronger assertion: it says NO tile
  // at a baked zoom answers 404, rather than that one particular tile does not.
  const z = baked.minzoom;
  const auth = { headers: { Authorization: `Bearer ${webToken()}` } };
  const statuses = [];
  for (let x = 38; x <= 45; x++) {
    for (let y = 24; y <= 30; y++) {
      const r = await fetch(`${BASE}/tiles/${z}/${x}/${y}.mvt`, auth);
      statuses.push({ x, y, status: r.status });
    }
  }
  const notFound = statuses.filter((s) => s.status === 404);
  assert.deepEqual(
    notFound, [],
    `these tiles are at a BAKED zoom (z${z}) and answered 404, which is the ` +
      `same answer a missing zoom range gives: ` +
      notFound.map((s) => `${z}/${s.x}/${s.y}`).join(", "),
  );
  // And the sweep has to have actually found some empties, or it proves nothing.
  const empties = statuses.filter((s) => s.status === 204);
  assert.ok(empties.length > 0,
    `every tile in the sweep had data, so the 204 path was never exercised`);
});

test("a zoom that was never baked is reported as missing", async () => {
  // The other half of the contract, and the direction that matters: this is
  // the answer the whole z4-z10 range gave while the map was blank.
  const z = baked.minzoom - 1;
  const r = await fetch(`${BASE}/tiles/${z}/1/1.mvt`, {
    headers: { Authorization: `Bearer ${webToken()}` },
  });
  assert.equal(r.status, 404,
    `z${z} is not baked but answered ${r.status}; a coverage gap must not look ` +
    `like empty ground`);
});

// ---------------------------------------------------------------------------
// The client declares what the server has
// ---------------------------------------------------------------------------

test("the viewer applies the zoom range the server reports", async () => {
  // Not "fetches" — APPLIES. The variable was being set correctly all along;
  // the style document never saw it, because there is no incremental way to
  // change a vector source's zoom range and `setTiles()` does not do it.
  const src = await page.evaluate(() => {
    const s = window.__map.getStyle().sources.vector;
    return { minzoom: s.minzoom, maxzoom: s.maxzoom };
  });
  assert.equal(src.minzoom, baked.minzoom,
    `the style declares minzoom ${src.minzoom} but the bake starts at z${baked.minzoom}; ` +
    `everything between renders nothing`);
  assert.equal(src.maxzoom, baked.maxzoom,
    `the style declares maxzoom ${src.maxzoom} but the bake contains up to ` +
    `z${baked.maxzoom}; anything higher 404s, anything lower is never used`);
});

test("the map cannot be zoomed out past the data", async () => {
  // The honest behaviour: there is nothing further out to show, so the gesture
  // stops rather than handing the driver a blank screen.
  const min = await page.evaluate(() => window.__map.getMinZoom());
  assert.ok(min >= baked.minzoom,
    `the map allows z${min} but the tiles start at z${baked.minzoom}`);
});

// ---------------------------------------------------------------------------
// Every rung of the ladder draws something
// ---------------------------------------------------------------------------

test("the country view is not empty", async () => {
  const r = await at(7, QATAR, 1);
  assert.ok(r.total > 0,
    `nothing rendered at z${r.zoom.toFixed(1)} over Qatar — this is the reported ` +
    `defect: zoom out and the country disappears`);
});

test("the country view shows the national road network", async () => {
  // Not merely "something drew". A country view whose only content is a water
  // polygon is still not a map of Qatar.
  const r = await at(7, QATAR, 1);
  const roads = (r.byLayer["roads-hi"] || 0) + (r.byLayer["roads-major"] || 0);
  assert.ok(roads > 0,
    `no motorway or trunk roads at country zoom. Rendered: ${JSON.stringify(r.byLayer)}`);
});

test("the country view is labelled", async () => {
  // The extract carries `place=` on all 685 labels and the style used to ignore
  // it while starting labels at z11, so the country and region views had none.
  const r = await at(7, QATAR, 1);
  assert.ok((r.byLayer["labels"] || 0) > 0,
    `no place labels at country zoom — an unlabelled outline is not a map. ` +
    `Rendered: ${JSON.stringify(r.byLayer)}`);
});

test("every rung from country to street draws something", async () => {
  // The ladder, walked. A gap anywhere in it is a zoom level at which the
  // driver's map goes blank, and a single-zoom check cannot see it — which is
  // exactly how a z11-only bake stayed green.
  const rungs = [
    ["country", 7, QATAR],
    ["region", 9, QATAR],
    ["city", 11, DOHA],
    ["district", 13, DOHA],
    ["street", 15, DOHA],
    ["navigation", 16.5, DOHA],
  ];
  const empty = [];
  for (const [name, z, centre] of rungs) {
    const r = await at(z, centre, 1);
    if (r.total === 0) empty.push(`${name} (z${z})`);
  }
  assert.deepEqual(empty, [],
    `these views render nothing at all: ${empty.join(", ")}`);
});

// ---------------------------------------------------------------------------
// The hierarchy is real, not the same map at different sizes
// ---------------------------------------------------------------------------

test("detail increases as the driver zooms in", async () => {
  // The pipeline emits road classes progressively (motorway/trunk from z0,
  // primary z9, links z11, secondary z12, tertiary z13, residential z14,
  // service z15). If a country tile carried the same features as a street tile
  // the map would be an unreadable hairball at every scale AND the per-tile
  // budget would be spent on invisible geometry — which is what pushed the
  // motorway network out of a z6 tile before the tiers existed.
  const country = await at(8, DOHA, 1);
  const countryArea = await groundAreaKm2();
  const street = await at(15, DOHA, 1);
  const streetArea = await groundAreaKm2();

  const countryClasses = Object.keys(country.byLayer).filter((k) => k.startsWith("roads-")).length;
  const streetClasses = Object.keys(street.byLayer).filter((k) => k.startsWith("roads-")).length;
  assert.ok(
    streetClasses >= countryClasses,
    `street zoom shows ${streetClasses} road layers against ${countryClasses} at ` +
      `country zoom — the hierarchy is inverted`,
  );

  // DENSITY, not the raw count. A z8 viewport covers about 2^14 times more
  // ground than a z15 one, so it legitimately renders far more features in
  // total — measured here, 8,497 against 796. Comparing totals therefore says
  // nothing about detail, and asserting `street.total > country.total` was
  // simply the wrong test (it failed against a map that is working correctly).
  // Features per square kilometre is the quantity that has to go UP.
  const countryDensity = country.total / countryArea;
  const streetDensity = street.total / streetArea;
  assert.ok(
    streetDensity > countryDensity * 10,
    `z15 renders ${streetDensity.toFixed(1)} features/km² against ` +
      `${countryDensity.toFixed(1)} at z8 (${street.total} in ${streetArea.toFixed(1)} km² ` +
      `vs ${country.total} in ${countryArea.toFixed(0)} km²); zooming in is not adding detail`,
  );
});

test("minor roads are absent at country zoom and present at street zoom", async () => {
  const country = await at(8, DOHA, 1);
  const street = await at(15, DOHA, 1);
  assert.ok((street.byLayer["roads-minor"] || 0) > 0,
    "no minor roads at street zoom — the residential grid is missing");
  assert.ok(
    (country.byLayer["roads-minor"] || 0) === 0,
    `${country.byLayer["roads-minor"]} minor roads reached the country view; the zoom ` +
      `tiers are not being applied at bake time`,
  );
});

test("no page errors while walking the whole ladder", () => {
  // A style expression MapLibre rejects (a `match` without a fallback, an
  // unsupported property) takes the entire document down and renders a blank
  // screen. The ranked label sizing added in this pass is exactly that shape of
  // risk, and it is only exercised by actually drawing at these zooms.
  assert.deepEqual(pageErrors, [], `page errors: ${pageErrors.join(" | ")}`);
});
