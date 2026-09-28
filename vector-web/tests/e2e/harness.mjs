/*
 * Headless-browser harness for the Vector viewer (ticket 48).
 *
 * PROGRESS.md flags the absence of this three separate times — the offline
 * round trip, the durable-queue round trip and the blank-map check were all
 * recorded as "needs a headless-browser harness the suite does not yet have".
 * Everything the client does that matters (does the map render, does the turn
 * countdown move, what does a drive cost in CPU) is invisible to a suite that
 * greps index.html as text.
 *
 * Exports the primitives; the specs beside this file do the asserting.
 */
import { chromium } from "playwright";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const REPO = join(here, "..", "..", "..");

/** Load geo.js the same way the unit tests do, so fixtures share the maths. */
export function loadGeo() {
  const sb = { self: {}, module: { exports: {} } };
  sb.globalThis = sb;
  vm.createContext(sb);
  vm.runInContext(readFileSync(join(here, "..", "..", "static", "js", "geo.js"), "utf8"), sb);
  return sb.module.exports;
}

/** The web token bootstrap generated, read from the repo .env. */
export function webToken() {
  const env = readFileSync(join(REPO, ".env"), "utf8");
  const m = env.match(/^VECTOR_WEB_TOKEN=(.+)$/m);
  if (!m) throw new Error("VECTOR_WEB_TOKEN missing from .env — run bootstrap.sh");
  return m[1].trim();
}

export const BASE = process.env.VECTOR_BASE || "http://localhost:9003";

/**
 * Launch a browser that can actually render MapLibre.
 *
 * Headless Chromium has no GPU, so WebGL must be forced onto SwiftShader or
 * every map test silently reports a blank canvas and we would "discover" a
 * rendering bug that is really a harness bug.
 */
export async function launch({ headless = true } = {}) {
  return chromium.launch({
    headless,
    args: [
      "--use-gl=angle",
      "--use-angle=swiftshader",
      "--enable-unsafe-swiftshader",
      "--ignore-gpu-blocklist",
    ],
  });
}

/** A context with geolocation granted and pinned to a start point. */
export async function driveContext(browser, { lng, lat }) {
  const ctx = await browser.newContext({
    permissions: ["geolocation"],
    geolocation: { longitude: lng, latitude: lat, accuracy: 5 },
    viewport: { width: 412, height: 915 }, // a real phone viewport (S24-ish)
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  });
  return ctx;
}

/**
 * Open the viewer and wait until MapLibre reports the style is loaded AND the
 * first tiles have rendered. Waiting on `load` alone races the tile fetch and
 * makes the blank-map check flaky in the direction that hides bugs.
 */
export async function openViewer(page, { token = webToken(), from, to } = {}) {
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });

  // The viewer keeps its map in a top-level `const map`, which is not reachable
  // from window and is not stashed on the container element either. Rather than
  // reach into MapLibre internals, intercept the constructor: install a setter
  // for `window.maplibregl` that fires when the vendor bundle assigns it, and
  // wrap `.Map` so every instance registers itself. addInitScript runs before
  // any page script, so this is in place before the bundle loads.
  await page.addInitScript(() => {
    let real;
    Object.defineProperty(window, "maplibregl", {
      configurable: true,
      get: () => real,
      set: (v) => {
        real = v;
        if (v && v.Map && !v.Map.__wrapped) {
          const Orig = v.Map;
          const Wrapped = function (...args) {
            const inst = new Orig(...args);
            window.__map = inst;
            return inst;
          };
          Wrapped.__wrapped = true;
          Wrapped.prototype = Orig.prototype;
          Object.setPrototypeOf(Wrapped, Orig);
          v.Map = Wrapped;
        }
      },
    });
  });

  // ?from/?to deep-link the route, so an automated drive exercises the ON-ROUTE
  // path (snapping, dead reckoning, maneuver advance) rather than free roam.
  const qs = new URLSearchParams({ token });
  if (from) qs.set("from", from);
  if (to) qs.set("to", to);
  await page.goto(`${BASE}/?${qs}`, { waitUntil: "domcontentloaded" });

  await page.waitForFunction(() => !!window.__map, { timeout: 30000 });

  // Wait for the real `idle` event, not just `areTilesLoaded()`.
  //
  // `loaded() && areTilesLoaded()` can both be true BEFORE the first render pass
  // has painted, so `queryRenderedFeatures` immediately afterwards sometimes
  // returned zero and the suite reported "no major roads rendered" on roughly
  // one run in three. A flaky blank-map check is the worst kind of flake: it
  // erodes trust in exactly the assertion that matters most, and the instinct
  // is to relax it rather than fix the race.
  //
  // `idle` fires only once no transition is in progress and every requested
  // tile has been loaded AND rendered — which is the condition the assertions
  // actually depend on.
  await page.evaluate(() => new Promise((resolve) => {
    // A cap so a permanently-busy map fails on the assertion (with a useful
    // message) rather than hanging the suite with none.
    const cap = setTimeout(resolve, 30000);
    window.__map.once("idle", () => { clearTimeout(cap); resolve(); });
  }));
  return { errors };
}

/**
 * What did the map actually draw?
 *
 * Pixels cannot be read back: MapLibre creates its WebGL context without
 * preserveDrawingBuffer, so the buffer is cleared before any drawImage sees it
 * (the first version of this harness reported "1 distinct colour" for a map
 * that was rendering fine). queryRenderedFeatures is the better probe anyway —
 * it proves tiles were fetched, DECODED, and matched a style layer, which is
 * precisely the chain that breaks when the source-layer name is wrong or the
 * tile URL is origin-bound.
 */
export async function renderedLayers(page, { minFeatures = 0, timeoutMs = 30000 } = {}) {
  // Wait for a STABLE render, not merely a moment when the count was high.
  //
  // Three separate things move the ground under a naive query, and each one
  // produced the same misleading failure ("the map is blank") on roughly one run
  // in three:
  //
  //   1. `loaded() && areTilesLoaded()` — and even the `idle` event — can be
  //      true before the first paint.
  //   2. The viewer auto-starts GPS follow, so a fix recentres the camera
  //      asynchronously after idle.
  //   3. The viewer refreshes the TILE EPOCH on load, which rewrites the source
  //      URL and forces every tile to reload.
  //
  // In each case the count reaches the threshold and then collapses. Requiring
  // it to hold across two samples separated by a settle interval covers all
  // three without needing to know which one fired — and without weakening the
  // assertion, which is what a flaky blank-map check tempts you into.
  if (minFeatures > 0) {
    const deadline = Date.now() + timeoutMs;
    let stable = 0;
    while (Date.now() < deadline) {
      const n = await page.evaluate(() => {
        const m = window.__map;
        return m && m.areTilesLoaded() && !m.isMoving()
          ? m.queryRenderedFeatures().length : -1;
      });
      stable = n >= minFeatures ? stable + 1 : 0;
      if (stable >= 2) break;
      await page.waitForTimeout(500);
    }
  }
  return _renderedLayers(page);
}

async function _renderedLayers(page) {
  return page.evaluate(() => {
    const map = window.__map;
    const feats = map.queryRenderedFeatures();
    const byLayer = {};
    for (const f of feats) byLayer[f.layer.id] = (byLayer[f.layer.id] || 0) + 1;
    const declared = map.getStyle().layers.map((l) => l.id);
    return {
      total: feats.length,
      byLayer,
      declaredLayers: declared,
      emptyLayers: declared.filter((id) => !byLayer[id]),
      zoom: map.getZoom(),
      center: map.getCenter(),
    };
  });
}

/**
 * Is the map actually showing something?
 *
 * A blank map is the single failure the Shipaton packaging risks (an
 * origin-bound tile URL in a WebView returns a page that looks fine and renders
 * nothing), and it is invisible to any test that only checks HTTP status codes.
 * "Not blank" here means: the canvas contains more than a handful of distinct
 * colours, i.e. something was drawn on top of the background wash.
 */
export async function canvasStats(page) {
  return page.evaluate(() => {
    const c = document.querySelector("#map canvas");
    if (!c) return { ok: false, reason: "no canvas" };
    // preserveDrawingBuffer is off, so read through a 2D copy of the element.
    const off = document.createElement("canvas");
    off.width = Math.min(c.width, 400);
    off.height = Math.min(c.height, 400);
    const g = off.getContext("2d");
    g.drawImage(c, 0, 0, off.width, off.height);
    const { data } = g.getImageData(0, 0, off.width, off.height);
    const seen = new Set();
    let opaque = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] > 0) opaque++;
      // Quantise to 4 bits per channel: antialiasing otherwise inflates the
      // colour count and would let a one-colour wash pass as "content".
      seen.add(((data[i] >> 4) << 8) | ((data[i + 1] >> 4) << 4) | (data[i + 2] >> 4));
    }
    return { ok: true, distinctColors: seen.size, opaqueFraction: opaque / (data.length / 4) };
  });
}

/** Start counting frames and long tasks in the page. */
export async function startMetrics(page) {
  await page.evaluate(() => {
    window.__perf = { frames: 0, t0: performance.now(), longTasks: 0, longTaskMs: 0 };
    const tick = () => {
      window.__perf.frames++;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
    try {
      new PerformanceObserver((list) => {
        for (const e of list.getEntries()) {
          window.__perf.longTasks++;
          window.__perf.longTaskMs += e.duration;
        }
      }).observe({ entryTypes: ["longtask"] });
    } catch (e) {
      /* longtask unsupported: counts stay zero rather than throwing */
    }
  });
}

/**
 * Collect the numbers. TaskDuration comes from CDP and is the closest thing to
 * "CPU seconds this page burned" available without the OS; it is what a battery
 * estimate would be built on.
 */
export async function readMetrics(page, cdp) {
  const inPage = await page.evaluate(() => {
    const p = window.__perf;
    const secs = (performance.now() - p.t0) / 1000;
    return {
      seconds: secs,
      fps: p.frames / secs,
      longTasks: p.longTasks,
      longTaskMs: p.longTaskMs,
      heapMB: performance.memory ? performance.memory.usedJSHeapSize / 1048576 : null,
    };
  });
  const metrics = await cdp.send("Performance.getMetrics");
  const byName = Object.fromEntries(metrics.metrics.map((m) => [m.name, m.value]));
  return {
    ...inPage,
    taskDurationS: byName.TaskDuration,
    scriptDurationS: byName.ScriptDuration,
    layoutDurationS: byName.LayoutDuration,
    recalcStyleDurationS: byName.RecalcStyleDuration,
    jsHeapUsedMB: byName.JSHeapUsedSize / 1048576,
    nodes: byName.Nodes,
  };
}

/**
 * Install a controllable GPS simulator. MUST be called before openViewer.
 *
 * Playwright's own `context.setGeolocation()` is not usable for a drive: it
 * changes what getCurrentPosition returns but does NOT re-fire an active
 * watchPosition. Measured — the app's watch fired exactly once on load and then
 * never again across 20 position changes, while the camera drifted 90 m against
 * a 983 m track and the speedometer stayed "--". Any perf number collected that
 * way describes an IDLE MAP, not a drive.
 *
 * So we replace the geolocation API outright. That also buys what a GPS test
 * actually needs and emulation cannot give: exact control of rate, accuracy,
 * speed and heading, including the degenerate cases (no heading below walking
 * pace, an accuracy spike, a dropout).
 */
export async function installGpsSimulator(page) {
  await page.addInitScript(() => {
    const watchers = new Map();
    let nextId = 1;
    let last = null;
    const stats = { watchCalls: 0, fired: 0, getCurrent: 0, errors: [] };

    function makePosition(f) {
      return {
        coords: {
          latitude: f.lat,
          longitude: f.lng,
          accuracy: f.accuracy == null ? 5 : f.accuracy,
          altitude: null,
          altitudeAccuracy: null,
          heading: f.heading == null ? null : f.heading,
          speed: f.speed == null ? null : f.speed,
        },
        timestamp: f.timestamp == null ? Date.now() : f.timestamp,
      };
    }

    const geo = {
      getCurrentPosition(ok, err) {
        stats.getCurrent++;
        if (last) ok(makePosition(last));
        else if (err) err({ code: 2, message: "position unavailable" });
      },
      watchPosition(ok, err) {
        stats.watchCalls++;
        const id = nextId++;
        watchers.set(id, { ok, err });
        if (last) { stats.fired++; ok(makePosition(last)); }
        return id;
      },
      clearWatch(id) { watchers.delete(id); },
      __stats: stats,
      __watcherCount: () => watchers.size,
    };
    Object.defineProperty(navigator, "geolocation", { value: geo, configurable: true });

    // Emit one fix to every live watcher.
    window.__gpsPush = (f) => {
      last = f;
      for (const w of watchers.values()) { stats.fired++; w.ok(makePosition(f)); }
      return { watchers: watchers.size, fired: stats.fired };
    };
    // Simulate a dropout / permission failure.
    window.__gpsError = (code, message) => {
      for (const w of watchers.values()) if (w.err) w.err({ code, message });
    };
    window.__gpsStats = () => ({ ...stats, watchers: watchers.size });
  });
}

/**
 * Feed a GPS track to the page as if the phone were moving.
 *
 * `hz` is the emission rate the app sees; `speedup` compresses wall-clock so a
 * long drive can be measured quickly. CPU-per-fix stays comparable because the
 * work per fix is unchanged — only the idle gaps shrink.
 *
 * Fixes carry speed and heading derived from the track, because the viewer
 * gates camera bearing on a speed floor and falls back to a derived heading
 * otherwise; feeding bare lat/lng would silently exercise only one branch.
 */
export async function driveTrack(context, page, track, { hz = 1, speedup = 10 } = {}) {
  const stepMs = 1000 / hz / speedup;
  const speedMs = track.length > 1 && track[1].alongM != null
    ? (track[1].alongM - track[0].alongM) * hz
    : null;
  for (const p of track) {
    await page.evaluate(
      ([lng, lat, heading, speed]) => window.__gpsPush({ lng, lat, heading, speed, accuracy: 5 }),
      [p.lng, p.lat, p.bearing == null ? null : p.bearing, speedMs]
    );
    await page.waitForTimeout(stepMs);
  }
}

/** Read the simulator's counters — proof a drive actually reached the app. */
export async function gpsStats(page) {
  return page.evaluate(() => window.__gpsStats());
}

/**
 * Sample a route geometry into 1 Hz GPS fixes at a constant speed.
 * Uses the same projection maths the client uses, so the fixes land exactly on
 * the route — which is what makes "off-route must read ~0" a meaningful assert.
 */
export function trackAlongRoute(coords, { speedKmh = 60, hz = 1 } = {}) {
  const G = loadGeo();
  const idx = G.indexRoute(coords);
  if (!idx) return [];
  const stepM = (speedKmh * 1000) / 3600 / hz;
  const out = [];
  for (let d = 0; d <= idx.totalM; d += stepM) {
    const pt = G.pointAtDistance(d, idx);
    out.push({ lng: pt.lng, lat: pt.lat, bearing: pt.bearing, alongM: d });
  }
  return out;
}

/** Fetch a real route from the running stack. */
export async function fetchRoute(from, to) {
  const res = await fetch(`${BASE}/route?from=${from}&to=${to}`, {
    headers: { Authorization: `Bearer ${webToken()}` },
  });
  if (!res.ok) throw new Error(`route ${res.status}`);
  const json = await res.json();
  return json.features[0];
}

/** Wait until the viewer has drawn a route (deep-linked via openViewer). */
export async function waitForRoute(page, timeout = 45000) {
  await page.waitForFunction(
    () => !!(window.__map && window.__map.getSource && window.__map.getSource("route-line")),
    { timeout }
  );
  return page.evaluate(() => {
    const d = window.__map.getSource("route-line")._data;
    const f = d.type === "Feature" ? d : d.features[0];
    return {
      coords: f.geometry.coordinates.length,
      distanceKm: f.properties && f.properties.distance_km,
      durationS: f.properties && f.properties.duration_s,
    };
  });
}
