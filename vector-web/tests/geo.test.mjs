/*
 * Executable unit tests for static/js/geo.js (ticket 36).
 *
 * Run: node --test vector-web/tests/geo.test.mjs
 *
 * Why this file exists: every other vector-web test asserts against
 * index.html as TEXT. That is how an off-route threshold wrong by 31x lived in
 * the tree next to a comment stating the correct value -- no test could execute
 * it. These tests run the math.
 *
 * geo.js is a UMD classic script (it must be a blocking non-module script in
 * the page), so it is loaded here through node:vm rather than imported.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, "..", "static", "js", "geo.js"), "utf8");
const sandbox = { self: {}, module: { exports: {} } };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(src, sandbox, { filename: "geo.js" });
const G = sandbox.module.exports;

// A straight 1 km due-east run at Doha's latitude, as two shape points.
// 1 deg of longitude at 25.2854 degN is ~100,700 m, so ~0.009931 deg is ~1 km.
const MPD = G.M_PER_DEG_LAT;
const LAT = 25.2854;
const LNG0 = 51.531;
const straight = [
  [LNG0, LAT],
  [LNG0 + 0.009931, LAT],
];

test("haversineM is symmetric and matches a known east-west span", () => {
  const d = G.haversineM(straight[0][0], straight[0][1], straight[1][0], straight[1][1]);
  assert.ok(Math.abs(d - 1000) < 5, `expected ~1000 m, got ${d}`);
  const back = G.haversineM(straight[1][0], straight[1][1], straight[0][0], straight[0][1]);
  assert.ok(Math.abs(d - back) < 1e-6);
});

test("indexRoute refuses a degenerate geometry instead of half-building one", () => {
  assert.equal(G.indexRoute([]), null);
  assert.equal(G.indexRoute([[LNG0, LAT]]), null);
  assert.equal(G.indexRoute(null), null);
});

test("indexRoute cumulative distances are monotonic and end at totalM", () => {
  const idx = G.indexRoute(straight);
  assert.equal(idx.cum[0], 0);
  assert.ok(idx.cum[1] > 0);
  assert.equal(idx.totalM, idx.cum[idx.cum.length - 1]);
});

// --- the defect this ticket exists to fix -----------------------------------

test("a point ON the line between two distant shape points reads ~0 m off-route", () => {
  // THE BUG: nearest-VERTEX search would report ~500 m here (half the segment),
  // because the midpoint of a 1 km segment is 500 m from either endpoint.
  const idx = G.indexRoute(straight);
  const mid = [LNG0 + 0.009931 / 2, LAT];
  const p = G.projectToRoute(mid[0], mid[1], idx);
  assert.ok(p.offsetM < 1, `on-line point should be ~0 m off-route, got ${p.offsetM}`);
  assert.ok(Math.abs(p.alongM - 500) < 5, `expected ~500 m along, got ${p.alongM}`);
});

test("offsetM is a true perpendicular distance in metres", () => {
  const idx = G.indexRoute(straight);
  // 100 m north of the midpoint. 100 m of latitude is 100/MPD deg.
  const p = G.projectToRoute(LNG0 + 0.009931 / 2, LAT + 100 / MPD, idx);
  assert.ok(Math.abs(p.offsetM - 100) < 2, `expected ~100 m offset, got ${p.offsetM}`);
});

test("offsetM is isotropic: 100 m east of the end reads the same as 100 m north", () => {
  // Raw degree distance is NOT isotropic -- a longitude degree at 25 degN is
  // ~9.4% shorter than a latitude degree, so an unscaled threshold is
  // direction-dependent. This test fails if the cos(lat) scale is dropped.
  const idx = G.indexRoute(straight);
  const north = G.projectToRoute(LNG0 + 0.009931 / 2, LAT + 100 / MPD, idx);
  const east = G.projectToRoute(LNG0 + 0.009931 + 100 / (Math.cos((LAT * Math.PI) / 180) * MPD), LAT, idx);
  assert.ok(Math.abs(north.offsetM - east.offsetM) < 2, `${north.offsetM} vs ${east.offsetM}`);
});

test("alongM advances continuously, not in vertex-sized jumps", () => {
  // THE OTHER BUG: cum[nearestVertex] makes the turn countdown hold, then jump.
  // Walk the 1 km segment in 50 m steps; every step must advance ~50 m.
  const idx = G.indexRoute(straight);
  const kx = Math.cos((LAT * Math.PI) / 180) * MPD;
  let prev = 0;
  for (let m = 50; m <= 950; m += 50) {
    const p = G.projectToRoute(LNG0 + m / kx, LAT, idx);
    const step = p.alongM - prev;
    assert.ok(Math.abs(step - 50) < 3, `step at ${m} m was ${step}, expected ~50`);
    prev = p.alongM;
  }
});

test("projection clamps to the segment rather than running past its ends", () => {
  const idx = G.indexRoute(straight);
  const before = G.projectToRoute(LNG0 - 0.01, LAT, idx);
  assert.equal(before.alongM, 0);
  assert.equal(before.t, 0);
  const after = G.projectToRoute(LNG0 + 0.02, LAT, idx);
  assert.ok(Math.abs(after.alongM - idx.totalM) < 1e-6);
  assert.equal(after.t, 1);
});

test("the nearest segment wins on a route that doubles back", () => {
  // An out-and-back: the return leg passes 200 m south of the outbound leg.
  const south = LAT - 200 / MPD;
  const idx = G.indexRoute([
    [LNG0, LAT],
    [LNG0 + 0.009931, LAT],
    [LNG0 + 0.009931, south],
    [LNG0, south],
  ]);
  // A point 10 m north of the RETURN leg must project onto the return leg.
  const p = G.projectToRoute(LNG0 + 0.005, south + 10 / MPD, idx);
  assert.equal(p.segIdx, 2, "should snap to the return leg, not the outbound one");
  assert.ok(p.offsetM < 12, `expected ~10 m, got ${p.offsetM}`);
});

test("projectToRoute returns null for a degenerate index instead of throwing", () => {
  assert.equal(G.projectToRoute(LNG0, LAT, null), null);
  assert.equal(G.projectToRoute(LNG0, LAT, { coords: [[LNG0, LAT]], cum: [0] }), null);
});

test("a zero-length segment does not produce NaN", () => {
  const idx = G.indexRoute([
    [LNG0, LAT],
    [LNG0, LAT],
    [LNG0 + 0.009931, LAT],
  ]);
  const p = G.projectToRoute(LNG0 + 0.005, LAT, idx);
  assert.ok(Number.isFinite(p.offsetM) && Number.isFinite(p.alongM));
});

// --- round-tripping, for the dead-reckoning work in ticket 37 ---------------

test("pointAtDistance round-trips with projectToRoute", () => {
  const idx = G.indexRoute(straight);
  // Targets must lie WITHIN the route: totalM is ~998.7 m, not the 1000 m the
  // fixture's degree offset suggests, and pointAtDistance clamps past the end.
  for (const target of [0, 137, 500, idx.totalM - 0.5]) {
    const pt = G.pointAtDistance(target, idx);
    const back = G.projectToRoute(pt.lng, pt.lat, idx);
    assert.ok(Math.abs(back.alongM - target) < 1, `${target} -> ${back.alongM}`);
  }
});

test("pointAtDistance clamps at both ends", () => {
  const idx = G.indexRoute(straight);
  const start = G.pointAtDistance(-50, idx);
  assert.ok(Math.abs(start.lng - LNG0) < 1e-9);
  const end = G.pointAtDistance(idx.totalM + 5000, idx);
  assert.ok(Math.abs(end.lng - straight[1][0]) < 1e-9);
});

test("pointAtDistance finds the right segment on a many-vertex route", () => {
  // 100 vertices, 10 m apart.
  const kx = Math.cos((LAT * Math.PI) / 180) * MPD;
  const coords = [];
  for (let i = 0; i < 100; i++) coords.push([LNG0 + (i * 10) / kx, LAT]);
  const idx = G.indexRoute(coords);
  const pt = G.pointAtDistance(455, idx);
  assert.equal(pt.segIdx, 45, `expected segment 45, got ${pt.segIdx}`);
  assert.ok(Math.abs(pt.t - 0.5) < 0.05);
});

test("bearingDeg reports compass degrees", () => {
  assert.ok(Math.abs(G.bearingDeg([LNG0, LAT], [LNG0 + 0.01, LAT]) - 90) < 0.5, "east");
  assert.ok(Math.abs(G.bearingDeg([LNG0, LAT], [LNG0, LAT + 0.01]) - 0) < 0.5, "north");
  const west = G.bearingDeg([LNG0, LAT], [LNG0 - 0.01, LAT]);
  assert.ok(Math.abs(west - 270) < 0.5, `west, got ${west}`);
});

test("angDiffDeg takes the short way around the wrap", () => {
  assert.equal(G.angDiffDeg(10, 350), 20);
  assert.equal(G.angDiffDeg(350, 10), -20);
  assert.equal(G.angDiffDeg(0, 0), 0);
  assert.ok(Math.abs(G.angDiffDeg(180, 0)) === 180);
});

// --- the regression guard for the actual shipped threshold ------------------

test("100 m expressed as a projected offset is 100 m, not 3.1 km", () => {
  // index.html used `minD > 0.0008` on SQUARED degrees and called it "~100m".
  // sqrt(0.0008) deg = 0.02828 deg = ~3,149 m at this latitude -- 31x too loose,
  // so a missed turn did not trigger a reroute until 3 km off route. With
  // offsetM in metres the threshold is simply the number of metres.
  const wrongThresholdM = Math.sqrt(0.0008) * G.M_PER_DEG_LAT;
  assert.ok(wrongThresholdM > 3000, `the old threshold really was ${Math.round(wrongThresholdM)} m`);

  const idx = G.indexRoute(straight);
  // A car 150 m off the road: must be off-route under a 100 m rule...
  const off = G.projectToRoute(LNG0 + 0.005, LAT + 150 / MPD, idx);
  assert.ok(off.offsetM > 100, `150 m away should exceed a 100 m rule, got ${off.offsetM}`);
  // ...and would NOT have been under the old one.
  assert.ok(off.offsetM < wrongThresholdM, "which the old 3.1 km threshold would have missed");
});
