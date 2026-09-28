#!/usr/bin/env node
/*
 * Vector — glyph PBF generator (canonical)
 *
 * Produces MapLibre-compatible glyph PBF files (SDF font stacks) for the
 * basemap label layers. This uses `fontnik` (the same library MapLibre
 * itself uses) so the output byte format is guaranteed correct.
 *
 * WHY THIS EXISTS
 * -------------
 * The previous Python encoder (gen_glyphs.py) produced a structurally invalid
 * PBF: it wrapped the top-level GlyphStack in an extra outer message and used
 * an ad-hoc SDF/zigzag encoding, so MapLibre silently dropped every glyph and
 * NO map labels ever rendered. fontnik emits the exact format MapLibre expects
 * (top-level GlyphStack with `range` + `glyphs`, RLE-packed SDF bitmaps,
 * correct zigzag metrics).
 *
 * USAGE
 * -----
 *   cd vector-tile-server/scripts
 *   npm install            # installs fontnik (needs node >= 18)
 *   node generate_glyphs.mjs
 *
 * Output is written to ../glyphs/<Fontstack>/<start>-<end>.pbf
 * (i.e. vector-tile-server/glyphs/...), which the tile server serves at
 * /glyphs/{fontstack}/{range}.pbf.
 *
 * RANGES
 * ------
 * - "Open Sans Regular"  : 0-255  (Latin/ASCII — used by poi-labels)
 * - "Noto Kufi Arabic"   : Arabic Unicode blocks covering OSM Qatar road &
 *                           place names (0600-06FF, 0750-077F, 08A0-08FF,
 *                           FB50-FDFF, FE70-FEFF). Any codepoint not present in
 *                           a generated range simply renders with no glyph
 *                           (no crash).
 */

import fontnik from "fontnik";
import fs from "fs";
import path from "path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const OUT = path.join(ROOT, "glyphs");

// Stable system font paths (Noto). Adjust if your environment differs.
const FONTS = {
  "Open Sans Regular": "/usr/share/fonts/noto/NotoSans-Regular.ttf",
  "Noto Kufi Arabic": "/usr/share/fonts/noto/NotoKufiArabic-Bold.ttf",
};

// Glyph ranges (start, end inclusive). 256-wide blocks match what MapLibre
// requests ({range}.pbf).
const RANGES = {
  "Open Sans Regular": [[0, 255]],
  "Noto Kufi Arabic": [
    [1536, 1791],   // 0600-06FF Arabic
    [1872, 1919],   // 0750-077F Arabic Supplement
    [2208, 2303],   // 08A0-08FF Arabic Extended-A
    [64336, 64591], // FB50-FDFF Arabic Presentation Forms-A
    [64592, 64847],
    [64848, 65023],
    [65136, 65279], // FE70-FEFF Arabic Presentation Forms-B
  ],
};

function fileURLToPath(u) {
  return new URL(u).pathname;
}

function gen(fontPath, fontstack, ranges) {
  if (!fs.existsSync(fontPath)) {
    console.error(`SKIP ${fontstack}: font not found at ${fontPath}`);
    return;
  }
  const dir = path.join(OUT, fontstack);
  fs.mkdirSync(dir, { recursive: true });
  const font = fs.readFileSync(fontPath);
  for (const [s, e] of ranges) {
    fontnik.range({ font, start: s, end: e }, (err, pbf) => {
      if (err) {
        console.error(`ERR ${fontstack} ${s}-${e}: ${err.message}`);
        return;
      }
      fs.writeFileSync(path.join(dir, `${s}-${e}.pbf`), pbf);
      console.log(`wrote ${fontstack}/${s}-${e}.pbf (${pbf.length} bytes)`);
    });
  }
}

for (const [stack, fontPath] of Object.entries(FONTS)) {
  gen(fontPath, stack, RANGES[stack]);
}
console.log("glyph generation complete ->", OUT);
