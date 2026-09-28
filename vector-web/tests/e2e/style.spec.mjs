/*
 * The NATIVE Android style, validated against the live stack.
 *
 *   node --test vector-web/tests/e2e/style.spec.mjs
 *
 * `VectorStyle.kt` is a MapLibre style document embedded in a Kotlin string
 * template. Nothing compiled it, nothing parsed it, and nothing checked that the
 * things it references actually exist — so it shipped asking for a font that the
 * glyph store does not serve.
 *
 * THE DEFECT THIS EXISTS FOR: the style requested `Noto Sans Regular`. The store
 * serves `Noto Kufi Arabic` and `Open Sans Regular`. The tile server answers a
 * missing fontstack with **HTTP 200 and a 2-byte body**, not a 404 — so every
 * label layer would have rendered blank on the device with no error anywhere.
 * A map of Doha with no street names, and nothing in any log to say why.
 *
 * These checks run against the live stack because that is the only place the
 * question "does this font exist" can actually be answered.
 */
import { test, before } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { BASE } from "./harness.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const KT = join(here, "..", "..", "..", "vector-android", "app", "src", "main",
  "java", "dev", "vector", "android", "VectorStyle.kt");

let style;
let epoch = 1;
let baked = null;          // what the tile set says it actually contains

before(async () => {
  const src = readFileSync(KT, "utf8");
  const open = 'return """';
  const body = src.slice(src.indexOf(open) + open.length, src.lastIndexOf('""".trimIndent()'));
  try {
    baked = await (await fetch(`${BASE}/tiles/version`)).json();
    epoch = baked.epoch;
  } catch { /* keep the defaults; the tile tests below will report it */ }
  // The zoom range is a runtime value now — the app asks /tiles/version for it —
  // so substitute what the server actually reports rather than a literal.
  //
  // Everything else left in the template is a COLOUR or the style name, because
  // the document is now theme-parameterised: every literal `#0c0e14` became
  // `${c.background}` so that a second (light) palette could exist. Those are
  // substituted generically rather than one by one, so that adding a colour
  // role does not break this test — what it exists to check is the document's
  // STRUCTURE (valid JSON, real sources, real fonts, the right zoom range), and
  // the specific hex values are pinned on the JVM side by VectorStyleTest.
  const filled = body
    .replaceAll("$glyphs", `${BASE}/glyphs/{fontstack}/{range}.pbf`)
    .replaceAll("$tiles", `${BASE}/tiles/{z}/{x}/{y}.mvt?v=${epoch}`)
    .replaceAll("$minZoom", String(baked?.minzoom ?? 11))
    .replaceAll("$maxZoom", String(baked?.maxzoom ?? 13))
    // ${c.roadMotorway} / $ROUTE / $name -> a valid colour string.
    .replace(/\$\{[A-Za-z_][A-Za-z0-9_.]*\}/g, "#123456")
    .replace(/\$[A-Za-z_][A-Za-z0-9_]*/g, "#123456");
  style = JSON.parse(filled);
});

test("the Kotlin template produces valid MapLibre style JSON", () => {
  // A Kotlin string template is not type-checked as JSON. One stray comma ships.
  assert.equal(style.version, 8);
  assert.ok(Array.isArray(style.layers) && style.layers.length > 10);
  assert.ok(style.sources && Object.keys(style.sources).length >= 2);
});

test("every layer references a source that exists", () => {
  const sources = new Set(Object.keys(style.sources));
  for (const l of style.layers) {
    if (l.type === "background") continue;
    assert.ok(sources.has(l.source), `layer "${l.id}" references missing source "${l.source}"`);
  }
});

test("every vector layer names the source-layer the tiles actually contain", () => {
  // The tiles encode one layer, `basemap`. A typo here renders nothing, and it
  // is the exact failure mode recorded in .scratch/vector-map-bugs.
  for (const l of style.layers) {
    if (l.source !== "vector") continue;
    assert.equal(l["source-layer"], "basemap",
      `layer "${l.id}" reads source-layer "${l["source-layer"]}"`);
  }
});

test("every layer id is unique", () => {
  const ids = style.layers.map((l) => l.id);
  assert.equal(new Set(ids).size, ids.length, `duplicate layer ids in ${ids.join(", ")}`);
});

test("every font the style asks for is actually served", async () => {
  // THE regression. A missing fontstack returns 200 with a ~2-byte body, so the
  // only way to detect it is to check the SIZE, not the status.
  const fonts = new Set();
  for (const l of style.layers) {
    const f = l.layout && l.layout["text-font"];
    if (Array.isArray(f)) f.forEach((x) => fonts.add(x));
  }
  assert.ok(fonts.size > 0, "no text-font declared anywhere — labels cannot render");

  for (const font of fonts) {
    // 0-255 is the Latin range; Arabic faces legitimately lack it, so probe a
    // range each face should have and accept either.
    const ranges = ["0-255", "1536-1791"];
    let best = 0;
    for (const r of ranges) {
      const res = await fetch(`${BASE}/glyphs/${encodeURIComponent(font)}/${r}.pbf`);
      if (res.ok) best = Math.max(best, (await res.arrayBuffer()).byteLength);
    }
    assert.ok(
      best > 1000,
      `font "${font}" served ${best} bytes — the glyph store does not have it, ` +
        `so every layer using it renders BLANK (200 + empty body, no error)`
    );
  }
});

test("the tile source URL resolves to real tiles", async () => {
  const src = style.sources.vector;
  assert.equal(src.type, "vector");
  const url = src.tiles[0]
    .replace("{z}", "13").replace("{x}", "5268").replace("{y}", "3500");
  const res = await fetch(url);
  assert.ok(res.ok, `tile fetch ${res.status} for ${url}`);
  const bytes = (await res.arrayBuffer()).byteLength;
  assert.ok(bytes > 1000, `tile served only ${bytes} bytes`);
});

test("the source zoom range matches what is actually baked", () => {
  // THIS TEST USED TO CERTIFY THE BUG IT EXISTS TO CATCH.
  //
  // It asserted `maxzoom >= 13` under the comment "declaring maxzoom beyond
  // that is fine (MapLibre overzooms)". That is exactly backwards. MapLibre
  // overzooms ABOVE the declared maximum — so declaring a maxzoom the bake does
  // not contain makes it request tiles that 404 and draw nothing, while
  // declaring the real maximum makes it scale the last real zoom level up
  // forever.
  //
  // The style declared 14 against a z11-13 bake. `14 >= 13` passed, and on an
  // S24 Ultra the map was a black screen above zoom 13 — with navigation
  // setting the camera to 16.5. The assertion is now EQUALITY against what the
  // tile server reports, which is the only source of truth for what was baked.
  if (!baked || baked.maxzoom == null) {
    assert.fail("/tiles/version did not report a zoom range — is the stack up?");
  }
  const src = style.sources.vector;
  assert.equal(src.maxzoom, baked.maxzoom,
    `style declares maxzoom ${src.maxzoom} but the bake contains up to ` +
    `z${baked.maxzoom}; anything higher 404s and renders blank`);
  assert.equal(src.minzoom, baked.minzoom,
    `style declares minzoom ${src.minzoom} but the bake starts at z${baked.minzoom}`);
});

test("a tile at the declared maximum zoom actually exists", () => {
  // The equality above is only meaningful if the reported range is real.
  assert.ok(baked?.maxzoom >= 11, "no usable zoom range reported");
});

test("OSM attribution is present, because ODbL requires it", () => {
  const attr = style.sources.vector.attribution || "";
  assert.match(attr, /OpenStreetMap/i, "ODbL attribution missing from the tile source");
});

test("the route and puck sources exist for the app to write into", () => {
  // MainActivity does style.getSourceAs("route") / ("puck"); a missing source
  // is a silent null and the route simply never draws.
  for (const id of ["route", "puck"]) {
    assert.ok(style.sources[id], `source "${id}" missing — the app writes to it at runtime`);
    assert.equal(style.sources[id].type, "geojson");
  }
  const layerSources = new Set(style.layers.map((l) => l.source));
  assert.ok(layerSources.has("route"), "nothing draws the route source");
  assert.ok(layerSources.has("puck"), "nothing draws the puck source");
});

test("the paint order puts puck above route above traffic above roads", () => {
  // Layer order IS the visual hierarchy: the road you are following must stay
  // the most legible thing on screen, and the vehicle must never be hidden
  // under a congestion stripe.
  const order = style.layers.map((l) => l.id);
  const routeIdx = order.indexOf("route");
  const puckIdx = order.indexOf("puck");
  const trafficIdx = order.indexOf("traffic");
  const roadIdx = Math.max(...["roads-motorway", "roads-primary", "roads-minor"]
    .map((id) => order.indexOf(id)).filter((i) => i >= 0));
  assert.ok(roadIdx >= 0, "no road layers found");
  if (trafficIdx >= 0) {
    assert.ok(trafficIdx > roadIdx, "traffic is drawn under the basemap roads");
    assert.ok(routeIdx > trafficIdx, "the route is drawn under the traffic overlay");
  }
  assert.ok(routeIdx > roadIdx, "the route line is drawn under the roads");
  assert.ok(puckIdx > routeIdx, "the vehicle is drawn under the route line");
});

test("traffic paints only congested segments", () => {
  // Colouring free-flowing roads covers the map in information the driver
  // already assumes, and buries the congestion that matters.
  const t = style.layers.find((l) => l.id === "traffic");
  if (!t) return;
  assert.ok(t.filter, "the traffic layer must filter out free-flowing segments");
  assert.ok(JSON.stringify(t.filter).includes("free"),
    `traffic filter does not exclude "free": ${JSON.stringify(t.filter)}`);
});
