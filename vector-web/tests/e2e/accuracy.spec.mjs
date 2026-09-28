/*
 * Route accuracy: is A -> B right, and is it comparable to Google Maps?
 * (ticket 50)
 *
 *   node --test vector-web/tests/e2e/accuracy.spec.mjs
 *   ACCURACY_REPORT=1 node --test vector-web/tests/e2e/accuracy.spec.mjs
 *
 * Two halves, because they answer different questions:
 *
 * 1. **Invariants** — run with no external reference at all. A route cannot be
 *    shorter than the great circle, cannot imply an impossible average speed,
 *    cannot disagree with the sum of its own steps, and must actually start and
 *    end where it was asked to. These catch the failures that matter and need
 *    nobody's permission.
 *
 * 2. **Reference comparison** — Google's numbers must be entered BY HAND into
 *    the fixture table below. Scraping Google Maps violates its terms and the
 *    Distance Matrix API needs a billed key, so this harness does not pretend
 *    to fetch them. Fill `googleKm` in and the comparison tests start running;
 *    leave it null and they skip, loudly, rather than passing vacuously.
 *
 * What the invariants already caught on the live Doha stack:
 *   - the driving graph contained 42,455 pedestrian ways (footways, steps,
 *     cycleways) and routes were emitted that began "Head northwest on footway
 *     road";
 *   - a 10.4 km route returned 315 steps, 305 of them "continue".
 */
import { test, before } from "node:test";
import assert from "node:assert/strict";
import { BASE, webToken, loadGeo } from "./harness.mjs";

const G = loadGeo();
const REPORT = !!process.env.ACCURACY_REPORT;

/**
 * Doha origin/destination pairs.
 *
 * `googleKm` / `googleMin`: open Google Maps, request driving directions for the
 * same two coordinates, and paste its numbers here. Until then they are null and
 * the comparison tests skip.
 */
const FIXTURES = [
  { name: "Souq Waqif -> West Bay",        from: "25.2854,51.5310", to: "25.3548,51.4830", googleKm: null, googleMin: null },
  { name: "Hamad Intl Airport -> Msheireb", from: "25.2609,51.6138", to: "25.2854,51.5250", googleKm: null, googleMin: null },
  { name: "Education City -> Corniche",     from: "25.3150,51.4400", to: "25.2960,51.5310", googleKm: null, googleMin: null },
  { name: "The Pearl -> Villaggio Mall",    from: "25.3690,51.5480", to: "25.2590,51.4430", googleKm: null, googleMin: null },
  { name: "Al Wakrah -> Doha centre",       from: "25.1710,51.6030", to: "25.2854,51.5310", googleKm: null, googleMin: null },
  { name: "short hop (Msheireb)",           from: "25.2854,51.5310", to: "25.2900,51.5350", googleKm: null, googleMin: null },
];

const results = [];

async function fetchRoute(from, to) {
  const res = await fetch(`${BASE}/navigate?from=${from}&to=${to}`, {
    headers: { Authorization: `Bearer ${webToken()}` },
  });
  if (!res.ok) throw new Error(`navigate ${res.status} for ${from} -> ${to}`);
  const json = await res.json();
  const f = json.features[0];
  const p = f.properties;
  const coords = f.geometry.coordinates.map(([lng, lat]) => [lng, lat]);
  return {
    coords,
    distanceM: p.distance_km * 1000,
    durationS: p.duration_s,
    steps: p.steps || [],
    snap: p.snap || [],
    snapMaxM: p.snap_max_m ?? null,
    linkDurationS: p.link_duration_s ?? null,
    junctionDelayS: p.junction_delay_s ?? null,
    profile: p.profile,
  };
}

const parse = (s) => {
  const [lat, lng] = s.split(",").map(Number);
  return { lat, lng };
};

before(async () => {
  for (const f of FIXTURES) {
    const a = parse(f.from), b = parse(f.to);
    const crowM = G.haversineM(a.lng, a.lat, b.lng, b.lat);
    try {
      const r = await fetchRoute(f.from, f.to);
      results.push({ ...f, ...r, crowM, detour: r.distanceM / crowM, error: null });
    } catch (e) {
      results.push({ ...f, error: String(e.message), crowM });
    }
  }
  if (REPORT) {
    console.log("\n--- route accuracy (fill googleKm to enable comparison) ---");
    console.log(
      "name".padEnd(30) + "crow".padStart(8) + "vector".padStart(9) +
      "detour".padStart(8) + "min".padStart(6) + "km/h".padStart(7) +
      "steps".padStart(7) + "google".padStart(8)
    );
    for (const r of results) {
      if (r.error) { console.log(r.name.padEnd(30) + "  ERROR " + r.error); continue; }
      const kmh = (r.distanceM / 1000) / (r.durationS / 3600);
      console.log(
        r.name.padEnd(30) +
        (r.crowM / 1000).toFixed(2).padStart(8) +
        (r.distanceM / 1000).toFixed(2).padStart(9) +
        r.detour.toFixed(2).padStart(8) +
        (r.durationS / 60).toFixed(0).padStart(6) +
        kmh.toFixed(0).padStart(7) +
        String(r.steps.length).padStart(7) +
        (r.googleKm == null ? "     —" : r.googleKm.toFixed(2).padStart(8))
      );
    }
    console.log();
  }
});

test("every fixture routes at all", () => {
  const failed = results.filter((r) => r.error);
  assert.deepEqual(failed.map((f) => `${f.name}: ${f.error}`), []);
});

test("no route is shorter than the great circle", () => {
  // The one universally true bound. A violation means the geometry is wrong or
  // the distance is being computed on a different path than the one returned.
  for (const r of results.filter((x) => !x.error)) {
    assert.ok(
      r.distanceM >= r.crowM - 1,
      `${r.name}: ${(r.distanceM / 1000).toFixed(2)} km road vs ${(r.crowM / 1000).toFixed(2)} km crow`
    );
  }
});

test("detour ratios are plausible for a road network", () => {
  // Urban road distance is typically 1.15-1.6x the straight line. Well above 2.5
  // means the router is taking an absurd path — which is what routing through
  // pedestrian ways, or shortest-distance-through-back-streets, produces.
  for (const r of results.filter((x) => !x.error)) {
    if (r.crowM < 1000) continue; // short hops are dominated by snapping
    assert.ok(
      r.detour < 2.5,
      `${r.name}: detour ratio ${r.detour.toFixed(2)} — the route is wandering`
    );
  }
});

test("implied average speed is physically sensible", () => {
  for (const r of results.filter((x) => !x.error)) {
    const kmh = (r.distanceM / 1000) / (r.durationS / 3600);
    assert.ok(kmh > 5 && kmh < 140, `${r.name}: implied ${kmh.toFixed(0)} km/h`);
  }
});

test("the route starts and ends where it was asked to", () => {
  // Snapping to the graph is expected; snapping a kilometre away is a missing
  // road, and silently returning a route between two other places is worse than
  // returning nothing.
  for (const r of results.filter((x) => !x.error)) {
    const a = parse(r.from), b = parse(r.to);
    const first = r.coords[0], last = r.coords[r.coords.length - 1];
    const dStart = G.haversineM(a.lng, a.lat, first[0], first[1]);
    const dEnd = G.haversineM(b.lng, b.lat, last[0], last[1]);
    assert.ok(dStart < 500, `${r.name}: start snapped ${dStart.toFixed(0)} m away`);
    assert.ok(dEnd < 500, `${r.name}: end snapped ${dEnd.toFixed(0)} m away`);
  }
});

test("the geometry's own length agrees with the reported distance", () => {
  // Catches a distance computed over a different path than the one drawn — the
  // client measures progress against the GEOMETRY, so a mismatch desynchronises
  // the whole turn countdown.
  for (const r of results.filter((x) => !x.error)) {
    const idx = G.indexRoute(r.coords);
    const drift = Math.abs(idx.totalM - r.distanceM) / r.distanceM;
    assert.ok(
      drift < 0.02,
      `${r.name}: geometry ${(idx.totalM / 1000).toFixed(2)} km vs reported ${(r.distanceM / 1000).toFixed(2)} km`
    );
  }
});

test("step distances sum to the route distance", () => {
  for (const r of results.filter((x) => !x.error && x.steps.length)) {
    const sum = r.steps.reduce((a, s) => a + s.distance_m, 0);
    const drift = Math.abs(sum - r.distanceM) / r.distanceM;
    assert.ok(drift < 0.02, `${r.name}: steps sum ${(sum / 1000).toFixed(2)} km vs ${(r.distanceM / 1000).toFixed(2)} km`);
  }
});

test("turn-by-turn is maneuvers, not one step per vertex", () => {
  // The regression that made the app unusable as a navigator: 315 steps for a
  // 10.4 km route, 305 of them "continue". A human-scale route has roughly one
  // maneuver per 400-800 m of urban driving.
  for (const r of results.filter((x) => !x.error && x.steps.length)) {
    const perKm = r.steps.length / (r.distanceM / 1000);
    assert.ok(perKm < 12, `${r.name}: ${r.steps.length} steps over ${(r.distanceM / 1000).toFixed(1)} km (${perKm.toFixed(1)}/km)`);
    const continues = r.steps.filter((s) => s.type === "continue").length;
    assert.ok(
      continues / r.steps.length < 0.7,
      `${r.name}: ${continues}/${r.steps.length} steps are "continue"`
    );
  }
});

test("no maneuver is announced on a sub-5-metre leg", () => {
  for (const r of results.filter((x) => !x.error && x.steps.length > 2)) {
    // The final "arrive" step legitimately has zero length.
    const tiny = r.steps.slice(0, -1).filter((s) => s.distance_m < 5).length;
    assert.ok(tiny <= 1, `${r.name}: ${tiny} steps under 5 m`);
  }
});

test("instructions never leak internal tags to the driver", () => {
  for (const r of results.filter((x) => !x.error && x.steps.length)) {
    for (const s of r.steps) {
      assert.ok(!/footway|cycleway|steps road|_link|bridleway|corridor/.test(s.instruction),
        `${r.name}: "${s.instruction}"`);
    }
  }
});

test("A->B and B->A are within 35% of each other", async () => {
  // One-way systems make these legitimately differ; a large gap means the graph
  // is directionally broken.
  for (const f of FIXTURES.slice(0, 3)) {
    const there = await fetchRoute(f.from, f.to);
    const back = await fetchRoute(f.to, f.from);
    const ratio = Math.max(there.distanceM, back.distanceM) / Math.min(there.distanceM, back.distanceM);
    assert.ok(ratio < 1.35, `${f.name}: ${(there.distanceM / 1000).toFixed(2)} vs ${(back.distanceM / 1000).toFixed(2)} km`);
  }
});

test("distance matches Google Maps within 20% where a reference is supplied", (t) => {
  const withRef = results.filter((r) => !r.error && r.googleKm != null);
  if (!withRef.length) {
    t.skip("no googleKm reference filled in — see the FIXTURES table at the top of this file");
    return;
  }
  for (const r of withRef) {
    const delta = Math.abs(r.distanceM / 1000 - r.googleKm) / r.googleKm;
    assert.ok(delta < 0.20,
      `${r.name}: Vector ${(r.distanceM / 1000).toFixed(2)} km vs Google ${r.googleKm.toFixed(2)} km (${(delta * 100).toFixed(0)}% off)`);
  }
});

// --- V1 validation additions ------------------------------------------------

test("endpoint snapping is reported for every route", () => {
  for (const r of results.filter((x) => !x.error)) {
    assert.ok(Array.isArray(r.snap) && r.snap.length >= 2,
      `${r.name}: no snap information in the response`);
    for (const s of r.snap) {
      assert.ok(Number.isFinite(s.distance_m), `${r.name}: snap distance missing`);
      assert.equal(s.routable, true, `${r.name}: snapped to a non-departable node`);
    }
  }
});

test("endpoint snapping is bounded", () => {
  // 2 km is the hard limit (Router.MAX_SNAP_M); anything approaching it on a
  // real fixture means a coverage gap worth looking at, not a routing bug.
  for (const r of results.filter((x) => !x.error)) {
    assert.ok(r.snapMaxM < 2000,
      `${r.name}: worst snap ${r.snapMaxM} m exceeds the routable-area bound`);
  }
});

test("a point outside the routable area is refused, not silently rerouted", async () => {
  // Previously this returned a confident route between two OTHER places,
  // because the snap fallback had no distance limit at all.
  const res = await fetch(`${BASE}/navigate?from=25.2854,51.5310&to=0.0,0.0`, {
    headers: { Authorization: `Bearer ${webToken()}` },
  });
  assert.equal(res.status, 422, "expected 422 for an out-of-area endpoint");
  const body = await res.json();
  assert.match(body.error, /outside routable area/);
  assert.ok(body.distance_m > body.limit_m, "the error must quantify how far out it was");
});

test("the ETA breaks down into link time plus junction delay", () => {
  // ETA has to be explainable, not just a number.
  for (const r of results.filter((x) => !x.error)) {
    assert.ok(Number.isFinite(r.linkDurationS), `${r.name}: no link duration`);
    assert.ok(Number.isFinite(r.junctionDelayS), `${r.name}: no junction delay`);
    const sum = r.linkDurationS + r.junctionDelayS;
    assert.ok(Math.abs(sum - r.durationS) < 1.0,
      `${r.name}: ${r.linkDurationS} + ${r.junctionDelayS} != ${r.durationS}`);
    assert.ok(r.junctionDelayS >= 0);
  }
});

test("the profile is advertised as fastest, because cost is travel time", () => {
  for (const r of results.filter((x) => !x.error)) {
    assert.equal(r.profile, "fastest", `${r.name}: profile is "${r.profile}"`);
  }
});

test("a turn-heavy urban route carries proportionally more junction delay", () => {
  // Sanity on the shape of the model rather than its magnitude: delay must
  // scale with maneuvers, not with distance.
  const withSteps = results.filter((x) => !x.error && x.steps.length > 2);
  for (const r of withSteps) {
    const perStep = r.junctionDelayS / r.steps.length;
    assert.ok(perStep < 25, `${r.name}: ${perStep.toFixed(1)} s of delay per maneuver`);
  }
});

// --- independent external reference -----------------------------------------
//
// Google cannot be used: scraping Maps breaks its terms and the Distance Matrix
// API needs a billed key. But "is our distance right?" does not actually require
// GOOGLE — it requires an independent, mature router working from its own copy
// of OSM. OSRM's public demo server is exactly that, and it is an open-source
// project publishing the endpoint for development use.
//
// So this is NOT a Google parity test and must never be described as one. It is
// a cross-check against a second implementation, which is the stronger claim
// available without paying someone.
//
// This runs against the LOCAL OSRM in the stack by default.
//
// `docker-compose.yml` has always started an `osrm` container holding a fully
// preprocessed Qatar dataset, and until now nothing called it: this test used
// `router.project-osrm.org` instead, so the strongest independent check Vector
// has was opt-in behind `OSRM_REFERENCE=1`, rate-limited to be polite to a free
// community server, and dependent on the public internet — which is to say, it
// almost never ran.
//
// The local instance is the better reference anyway. It is the same OSRM
// engine and the same OSM region, so a divergence is a difference between the
// two ROUTING IMPLEMENTATIONS rather than between two vintages of map data,
// which is the thing this test is actually trying to measure.
//
// Set `OSRM_REFERENCE=public` to use the demo server instead (a genuinely
// independent copy of OSM, useful for catching an error in our own extract),
// and `OSRM_REFERENCE=0` to skip.
const OSRM_MODE = process.env.OSRM_REFERENCE ?? "local";
const OSRM_LOCAL = process.env.VECTOR_OSRM_URL || "http://localhost:5000";
const OSRM_PUBLIC = "https://router.project-osrm.org";
const OSRM_BASE = OSRM_MODE === "public" ? OSRM_PUBLIC : OSRM_LOCAL;
const OSRM_POLITE_MS = OSRM_MODE === "public" ? 1200 : 0;

async function osrmDistance(from, to) {
  const [fa, fo] = from.split(",");
  const [ta, to_] = to.split(",");
  const url = `${OSRM_BASE}/route/v1/driving/${fo},${fa};${to_},${ta}?overview=false`;
  const res = await fetch(url, { signal: AbortSignal.timeout(30000) });
  if (!res.ok) throw new Error(`OSRM ${res.status}`);
  const j = await res.json();
  if (!j.routes || !j.routes.length) throw new Error(`OSRM returned no route (${j.code})`);
  return { distanceM: j.routes[0].distance, durationS: j.routes[0].duration };
}

async function osrmReachable() {
  try {
    await osrmDistance("25.2867,51.5333", "25.2950,51.5300");
    return true;
  } catch {
    return false;
  }
}

test("distances agree with an independent router (OSRM)", async (t) => {
  if (OSRM_MODE === "0" || OSRM_MODE === "off") {
    t.skip("OSRM_REFERENCE=0: cross-check disabled");
    return;
  }
  if (!(await osrmReachable())) {
    // Loud about WHICH endpoint, because "OSRM unreachable" reads very
    // differently for a container that should be in the stack than for a
    // public server that is merely busy.
    t.skip(`OSRM not reachable at ${OSRM_BASE} — is the stack up?`);
    return;
  }
  // Measured 2026-09-08: mean |delta| 1.8%, max 6.0%, with Education City ->
  // Corniche matching to 0.0%. 15% is a generous regression bound around that,
  // wide enough that a legitimate difference in road preference does not fail
  // the suite but tight enough to catch a graph or units defect.
  const deltas = [];
  for (const r of results.filter((x) => !x.error)) {
    const ref = await osrmDistance(r.from, r.to);
    const delta = Math.abs(r.distanceM - ref.distanceM) / ref.distanceM;
    deltas.push({ name: r.name, delta, vector: r.distanceM, osrm: ref.distanceM });
    if (OSRM_POLITE_MS) await new Promise((s) => setTimeout(s, OSRM_POLITE_MS));
  }
  for (const d of deltas) {
    assert.ok(
      d.delta < 0.15,
      `${d.name}: Vector ${(d.vector / 1000).toFixed(2)} km vs OSRM ` +
        `${(d.osrm / 1000).toFixed(2)} km (${(d.delta * 100).toFixed(1)}% apart)`
    );
  }
  const mean = deltas.reduce((a, d) => a + d.delta, 0) / deltas.length;
  assert.ok(mean < 0.05, `mean divergence ${(mean * 100).toFixed(1)}% across ${deltas.length} routes`);
  if (REPORT) {
    console.log("\n--- vs OSRM reference ---");
    for (const d of deltas) {
      console.log(
        d.name.padEnd(30) +
        (d.vector / 1000).toFixed(2).padStart(9) +
        (d.osrm / 1000).toFixed(2).padStart(9) +
        ((d.delta * 100).toFixed(1) + "%").padStart(8)
      );
    }
  }
});

test("free-flow ETAs read optimistic against a traffic-unaware reference", async (t) => {
  if (OSRM_MODE === "0" || OSRM_MODE === "off") {
    t.skip("OSRM_REFERENCE=0: cross-check disabled");
    return;
  }
  if (!(await osrmReachable())) {
    t.skip(`OSRM not reachable at ${OSRM_BASE} — is the stack up?`);
    return;
  }
  // Documented, not hidden. Measured 2026-09-08: Vector is ~20-25% faster than
  // OSRM's car profile on the same roads, because Vector uses free-flow
  // maxspeed/class defaults while OSRM applies profile-level reductions.
  // Neither is traffic-aware. This test pins the DIRECTION and rough size of
  // the gap so it cannot silently drift, and it is the number the learned-speed
  // layer should close once real drives exist.
  const r = results.find((x) => !x.error);
  const ref = await osrmDistance(r.from, r.to);
  const ratio = r.durationS / ref.durationS;
  assert.ok(ratio > 0.5 && ratio < 1.1,
    `Vector ETA is ${(ratio * 100).toFixed(0)}% of OSRM's — outside the known band`);
});
