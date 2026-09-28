// Render the ANDROID style through MapLibre Native's C++ core.
//
// This is the same renderer core the Android app uses (different bindings, same
// engine), so it exercises the one thing a Compose/Robolectric test cannot: does
// the native renderer parse this style, fetch these tiles, and draw pixels.
const mbgl = require('@maplibre/maplibre-gl-native');
const fs = require('fs');
const http = require('http');

const STYLE = JSON.parse(fs.readFileSync('/work/style.json', 'utf8'));

function get(url) {
  return new Promise((resolve) => {
    http.get(url, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, data: Buffer.concat(chunks) }));
    }).on('error', () => resolve({ status: 0, data: Buffer.alloc(0) }));
  });
}

let tileReqs = 0, tileOk = 0, glyphReqs = 0, glyphOk = 0, other = 0;
const failures = [];

// The first run of this harness HUNG with no output. The cause was a style
// asking for a fontstack the server does not have: it answers 200 with a 2-byte
// body, and MapLibre Native waits forever for glyphs it will never get. A
// watchdog turns that class of failure into a diagnosis instead of a hang.
const watchdog = setTimeout(() => {
  console.error(JSON.stringify({
    ok: false, reason: 'render did not complete within 120s',
    tileReqs, tileOk, glyphReqs, glyphOk, other, failures: failures.slice(0, 10),
  }, null, 2));
  process.exit(3);
}, 120000);

const map = new mbgl.Map({
  request: async (req, callback) => {
    const u = req.url;
    if (u.includes('/tiles/')) tileReqs++;
    else if (u.includes('/glyphs/')) glyphReqs++;
    else other++;
    const r = await get(u);
    if (r.status === 200 && r.data.length) {
      if (u.includes('/tiles/')) tileOk++;
      if (u.includes('/glyphs/')) glyphOk++;
      callback(null, { data: r.data });
    } else {
      failures.push(`HTTP ${r.status} (${r.data.length}B) ${u}`);
      callback(new Error(`HTTP ${r.status} for ${u}`));
    }
  },
  ratio: 2,
});

map.load(STYLE);
map.render({ zoom: 13, center: [51.5310, 25.2854], width: 512, height: 512 }, (err, buffer) => {
  clearTimeout(watchdog);
  if (err) { console.error('RENDER ERROR:', err.message); process.exit(1); }
  map.release();

  // buffer is raw RGBA. Count distinct colours: a blank map is one colour.
  const seen = new Set();
  let nonBg = 0;
  const BG = [0x0c, 0x0e, 0x14];
  for (let i = 0; i < buffer.length; i += 4) {
    const r = buffer[i], g = buffer[i+1], b = buffer[i+2];
    seen.add((r >> 3 << 10) | (g >> 3 << 5) | (b >> 3));
    if (Math.abs(r-BG[0]) > 6 || Math.abs(g-BG[1]) > 6 || Math.abs(b-BG[2]) > 6) nonBg++;
  }
  const total = buffer.length / 4;
  fs.writeFileSync('/work/render.rgba', buffer);
  console.log(JSON.stringify({
    ok: true,
    pixels: total,
    distinctColors: seen.size,
    nonBackgroundPixels: nonBg,
    nonBackgroundPct: +(100 * nonBg / total).toFixed(2),
    tileRequests: tileReqs, tileOk,
    glyphRequests: glyphReqs, glyphOk,
    otherRequests: other,
    failures: failures.slice(0, 5),
  }, null, 2));
});
