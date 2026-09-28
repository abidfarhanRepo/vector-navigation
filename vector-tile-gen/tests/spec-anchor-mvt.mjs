#!/usr/bin/env node
/**
 * Independent MVT spec-anchor guard.
 *
 * Verifies the Mapbox Vector Tile (MVT 2.1) wire format WITHOUT the project's
 * Python `decode_tile` (no Python interpreter is required to run this file).
 *
 * Two independent checks, both using a tiny self-contained protobuf reader:
 *   1. SPEC ANCHOR — a hand-built, spec-compliant Tile whose `layers` live at
 *      protobuf field 3 (tag byte `(3<<3)|2 = 0x1A`) is decoded correctly by
 *      the independent reader. This pins the spec: MVT 2.1 `Tile.layers = 3`
 *      (the authoritative `vector_tile.proto`), independent of our own decoder.
 *   2. REAL TILES — every *.mvt file under the scanned directories must (a)
 *      start with `0x1A` (field-3 tag) and (b) decode, via the independent
 *      reader, to at least one Layer. Any tile failing is reported and causes a
 *      non-zero exit so CI can never bless a regressed (field-1 / `0x0A`)
 *      encoder the way the Session-41 round-trip tests did.
 *
 * Usage:
 *   node spec-anchor-mvt.mjs                 # scan default dirs
 *   node spec-anchor-mvt.mjs dir1 dir2 ...   # scan given dirs
 *   node spec-anchor-mvt.mjs --inspect ...   # also dump per-tile field keys
 */

import { readFileSync, readdirSync, statSync, writeFileSync } from 'fs';
import { join, dirname, extname, sep } from 'path';
import { fileURLToPath } from 'url';

const ROOT = dirname(dirname(dirname(fileURLToPath(import.meta.url)))); // workspace root (Vector)
const DEFAULT_DIRS = [
  join(ROOT, 'vector-tile-server', 'tiles'),
  join(ROOT, 'vector-infra', 'terraform', 'docker', 'site', 'tiles'),
];

const inspect = process.argv.includes('--inspect');
const dirs = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const scanDirs = dirs.length ? dirs.map((d) => (d.startsWith('/') || /^[A-Za-z]:/.test(d) ? d : join(ROOT, d))) : DEFAULT_DIRS;

// ---- minimal protobuf reader (zero dependencies) ----
function readVarint(buf, pos) {
  let shift = 0n;
  let result = 0n;
  while (true) {
    const b = buf[pos++];
    result |= BigInt(b & 0x7f) << shift;
    if ((b & 0x80) === 0) break;
    shift += 7n;
  }
  return [Number(result), pos];
}

function parseMessage(buf) {
  const fields = {};
  let pos = 0;
  while (pos < buf.length) {
    const [tag, afterTag] = readVarint(buf, pos);
    pos = afterTag;
    const fieldNo = tag >> 3;
    const wireType = tag & 0x7;
    if (wireType === 0) {
      const [v, np] = readVarint(buf, pos);
      pos = np;
      (fields[fieldNo] ||= []).push(v);
    } else if (wireType === 2) {
      const [len, np] = readVarint(buf, pos);
      pos = np;
      const data = buf.subarray(pos, pos + len);
      pos += len;
      (fields[fieldNo] ||= []).push(data);
    } else if (wireType === 1) {
      pos += 8;
    } else if (wireType === 5) {
      pos += 4;
    } else {
      throw new Error(`unsupported wire type ${wireType} at offset ${afterTag}`);
    }
  }
  return fields;
}

function decodeTile(data) {
  const tile = parseMessage(data);
  const layers = [];
  for (const lb of tile[3] || []) {
    const lf = parseMessage(lb);
    layers.push({
      name: lf[1] && lf[1][0] ? lf[1][0].toString('utf8') : '',
      extent: lf[4] ? lf[4][0] : null,
      featureCount: (lf[5] || []).length,
    });
  }
  return { fieldKeys: Object.keys(tile).map(Number).sort((a, b) => a - b), layers };
}

// ---- 1. SPEC ANCHOR: hand-built compliant Tile (layers at field 3) ----
// Layer{ name = "anchor" }  ->  field1 (name) string
const anchorName = Buffer.from('anchor'); // 6 bytes
const anchorLayer = Buffer.concat([Buffer.from([0x0a, anchorName.length]), anchorName]);
// Tile{ layers = [anchorLayer] }  ->  field3 (layers) bytes
const anchorTile = Buffer.concat([Buffer.from([0x1a, anchorLayer.length]), anchorLayer]);

function assertAnchor() {
  if (anchorTile[0] !== 0x1a) {
    throw new Error(`spec-anchor buffer first byte is 0x${anchorTile[0].toString(16)}, expected 0x1a (field 3)`);
  }
  const dec = decodeTile(anchorTile);
  if (!dec.fieldKeys.includes(3)) {
    throw new Error(`spec-anchor Tile does not expose field 3 (got keys ${JSON.stringify(dec.fieldKeys)})`);
  }
  if (dec.layers.length !== 1 || dec.layers[0].name !== 'anchor') {
    throw new Error(`spec-anchor decoded incorrectly: ${JSON.stringify(dec.layers)}`);
  }
  console.log('  [spec-anchor] field-3 Tile decodes to 1 layer named "anchor" — MVT 2.1 `Tile.layers = 3` anchored.');
}

// ---- 2. REAL TILES ----
function walk(dir, out) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const e of entries) {
    const p = join(dir, e);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (extname(e).toLowerCase() === '.mvt') out.push(p);
  }
}

function main() {
  console.log('Independent MVT spec-anchor guard');
  assertAnchor();

  const tiles = [];
  for (const d of scanDirs) walk(d, tiles);
  tiles.sort();

  if (tiles.length === 0) {
    console.log('  [warn] no *.mvt files found under:', scanDirs.join(', '));
  }

  let failures = 0;
  for (const t of tiles) {
    const data = readFileSync(t);
    const first = data[0];
    const rel = t.replace(ROOT + sep, '');
    let note = '';
    let ok = true;
    if (first !== 0x1a) {
      ok = false;
      note = `first byte 0x${first.toString(16)} (NOT 0x1a / field 3)`;
    }
    let layerCount = 0;
    try {
      const dec = decodeTile(data);
      layerCount = dec.layers.length;
      if (inspect) note += ` keys=${JSON.stringify(dec.fieldKeys)} layers=${layerCount}`;
      if (layerCount < 1) {
        ok = false;
        note += (note ? '; ' : '') + 'decoded 0 layers via independent reader';
      }
    } catch (err) {
      ok = false;
      note += (note ? '; ' : '') + 'decode error: ' + err.message;
    }
    if (!ok) {
      failures++;
      console.log(`  [FAIL] ${rel} — ${note}`);
    } else {
      console.log(`  [ok]   ${rel} — 0x${first.toString(16)} / ${layerCount} layer(s)${inspect ? ' /' + note : ''}`);
    }
  }

  console.log(`\nSpec anchor: PASS. Real tiles scanned: ${tiles.length}, failures: ${failures}.`);
  if (failures > 0) {
    console.error('MVT guard FAILED — at least one tile is not spec-compliant (field-3 / 0x1a).');
    process.exit(1);
  }
  console.log('MVT guard PASSED.');
}

main();
