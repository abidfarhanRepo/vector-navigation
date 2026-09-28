import { readFileSync, readdirSync, statSync } from 'fs';
import { join } from 'path';

function readVarint(buf, pos) {
  let result = 0n;
  let shift = 0n;
  let p = pos;
  while (true) {
    const byte = buf[p];
    p += 1;
    result |= BigInt(byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) break;
    shift += 7n;
  }
  return [result, p];
}

function readTag(buf, pos) {
  const [tag, next] = readVarint(buf, pos);
  const fieldNumber = Number(tag >> 3n);
  const wireType = Number(tag & 7n);
  return [fieldNumber, wireType, next];
}

function readBytes(buf, pos) {
  const [len, next] = readVarint(buf, pos);
  const slice = buf.subarray(next, next + Number(len));
  return [slice, next + Number(len)];
}

function readString(buf, pos) {
  const [b, next] = readBytes(buf, pos);
  return [b.toString('utf8'), next];
}

function decodeFeature(buf) {
  let pos = 0;
  let geomType = null;
  while (pos < buf.length) {
    const [field, wire, next] = readTag(buf, pos);
    pos = next;
    if (field === 3 && wire === 0) {
      const [val, n] = readVarint(buf, pos);
      geomType = Number(val);
      pos = n;
    } else if (wire === 0) {
      const [, n] = readVarint(buf, pos);
      pos = n;
    } else if (wire === 2) {
      const [, n] = readBytes(buf, pos);
      pos = n;
    } else if (wire === 1) {
      pos += 8;
    } else if (wire === 5) {
      pos += 4;
    } else {
      throw new Error('unsupported wire type ' + wire);
    }
  }
  return { geomType };
}

function decodeLayer(buf) {
  let pos = 0;
  let name = null;
  const features = [];
  const fieldsSeen = {};
  while (pos < buf.length) {
    const [field, wire, next] = readTag(buf, pos);
    pos = next;
    const key = field + ':w' + wire;
    fieldsSeen[key] = (fieldsSeen[key] || 0) + 1;
    if (field === 1 && wire === 2) {
      const [s, n] = readString(buf, pos);
      name = s;
      pos = n;
    } else if (field === 5 && wire === 2) {
      const [fb, n] = readBytes(buf, pos);
      features.push(decodeFeature(fb));
      pos = n;
    } else if (wire === 0) {
      const [, n] = readVarint(buf, pos);
      pos = n;
    } else if (wire === 2) {
      const [, n] = readBytes(buf, pos);
      pos = n;
    } else if (wire === 1) {
      pos += 8;
    } else if (wire === 5) {
      pos += 4;
    } else {
      throw new Error('unsupported wire type ' + wire);
    }
  }
  return { name, features, fieldsSeen };
}

function verifyTile(path) {
  try {
    const buf = readFileSync(path);
    if (buf.length === 0) throw new Error('empty file');
    if (buf[0] !== 0x1A) {
      throw new Error('first byte is 0x' + buf[0].toString(16) + ', expected 0x1A (Tile.layers field 3, wire type 2)');
    }
    const [layerBuf, ] = readBytes(buf, 1);
    const layer = decodeLayer(layerBuf);
    if (typeof layer.name !== 'string' || layer.name.length === 0) {
      throw new Error('layer name missing or empty (fields seen: ' + JSON.stringify(layer.fieldsSeen) + ')');
    }
    if (!Array.isArray(layer.features) || layer.features.length < 1) {
      const hint = (layer.fieldsSeen['2:w2'] || 0) > 0
        ? '; features appear at field 2 (0x12) instead of spec field 5 (0x2A) -> Layer encoding is NOT MVT 2.1-spec-compliant'
        : '';
      throw new Error('layer has no features at spec field 5 (0x2A) (fields seen: ' + JSON.stringify(layer.fieldsSeen) + ')' + hint);
    }
    for (const f of layer.features) {
      if (typeof f.geomType !== 'number') {
        throw new Error('feature missing geomType');
      }
    }
    return { path, ok: true, layers: 1, features: layer.features.length, error: null };
  } catch (e) {
    return { path, ok: false, layers: 0, features: 0, error: e.message };
  }
}

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (name === '__pycache__' || name === 'node_modules') continue;
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      walk(full, out);
    } else if (name.endsWith('.mvt')) {
      out.push(full);
    }
  }
  return out;
}

const ROOT = 'C:\\Users\\PC\\Desktop\\Vector';
const dirs = [
  join(ROOT, 'vector-tile-server', 'tiles'),
  join(ROOT, 'vector-tile-server', 'tests', 'fixtures', 'tiles'),
];

let pass = 0;
let fail = 0;
const allFiles = [];
for (const d of dirs) allFiles.push(...walk(d));
allFiles.sort();

for (const f of allFiles) {
  const r = verifyTile(f);
  if (r.ok) {
    pass += 1;
    console.log(`PASS  ${f}  (layer="${r.layers}", features=${r.features})`);
  } else {
    fail += 1;
    console.log(`FAIL  ${f}  (${r.error})`);
  }
}

console.log('');
console.log(`PASS: ${pass}  FAIL: ${fail}`);

process.exit(fail > 0 ? 1 : 0);
