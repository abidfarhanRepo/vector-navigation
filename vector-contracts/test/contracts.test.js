import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import YAML from 'yaml';
import Ajv from 'ajv/dist/2020.js';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const readJson = (p) => JSON.parse(read(p));

test('coordinate schema validates its DTO example', () => {
  const schema = readJson('schemas/coordinate.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').Coordinate;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('bounding-box schema validates its DTO example', () => {
  const schema = readJson('schemas/bounding-box.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').BoundingBox;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('tile schema validates the TileResponse DTO example', () => {
  const schema = readJson('schemas/tile.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').TileResponse;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('logistics-request schema validates its DTO example', () => {
  const schema = readJson('schemas/logistics-request.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').LogisticsRequest;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('logistics-result schema validates its DTO example', () => {
  const schema = readJson('schemas/logistics-result.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').LogisticsResult;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('multi-vehicle logistics-request schema validates its DTO example', () => {
  const schema = readJson('schemas/logistics-request.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').LogisticsRequest;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('multi-vehicle logistics-result schema validates its DTO example', () => {
  const schema = readJson('schemas/logistics-result.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = readJson('dtos/dto.examples.json').LogisticsResult;
  assert.ok(validate(example), JSON.stringify(validate.errors));
});

test('logistics-request rejects undeclared fleet_size field', () => {
  const schema = readJson('schemas/logistics-request.schema.json');
  const validate = new Ajv({ strict: false }).compile(schema);
  const example = { ...readJson('dtos/dto.examples.json').LogisticsRequest, fleet_size: 3 };
  assert.ok(!validate(example), 'request allowed undeclared fleet_size');
});

test('VRP-lite request and result validate with new multi-vehicle fields', () => {
  const reqSchema = readJson('schemas/logistics-request.schema.json');
  const resSchema = readJson('schemas/logistics-result.schema.json');
  const vReq = new Ajv({ strict: false }).compile(reqSchema);
  const vRes = new Ajv({ strict: false }).compile(resSchema);

  const vrpRequest = {
    kind: 'logistics',
    stops: [[52.5200, 13.4050], [52.5300, 13.4100], [52.5250, 13.3980], [52.5350, 13.4150]],
    reply_to: 'agent://viewer.vector-01',
    vehicles: 2,
    depots: [[52.5200, 13.4050], [52.5250, 13.3980]],
    balanced: true
  };
  assert.ok(vReq(vrpRequest), JSON.stringify(vReq.errors));

  const vrpResult = {
    kind: 'logistics_result',
    ok: true,
    vehicles: 2,
    routes: [
      {
        vehicle: 0,
        depot: [52.5200, 13.4050],
        stops: [[52.5200, 13.4050], [52.5300, 13.4100]],
        order: [0, 1],
        distance_km: 5.1,
        duration_min: 6.0,
        geojson: { type: 'FeatureCollection', features: [] }
      },
      {
        vehicle: 1,
        depot: [52.5250, 13.3980],
        stops: [[52.5250, 13.3980], [52.5350, 13.4150]],
        order: [2, 3],
        distance_km: 7.2,
        duration_min: 8.1,
        geojson: { type: 'FeatureCollection', features: [] }
      }
    ]
  };
  assert.ok(vRes(vrpResult), JSON.stringify(vRes.errors));
});

test('VRPTW request and result validate with new time-window fields', () => {
  const reqSchema = readJson('schemas/logistics-request.schema.json');
  const resSchema = readJson('schemas/logistics-result.schema.json');
  const vReq = new Ajv({ strict: false }).compile(reqSchema);
  const vRes = new Ajv({ strict: false }).compile(resSchema);

  const vrpRequest = {
    kind: 'logistics',
    stops: [[52.5200, 13.4050], [52.5300, 13.4100], [52.5250, 13.3980], [52.5350, 13.4150], [52.5150, 13.3920]],
    reply_to: 'agent://viewer.vector-01',
    vehicles: 2,
    time_windows: [[0, 99999], [0, 99999], [0, 99999], [0, 99999], [0, 99999]],
    service_times: [0, 30, 45, 20, 10],
    start_time: 0
  };
  assert.ok(vReq(vrpRequest), JSON.stringify(vReq.errors));

  const vrpResult = {
    kind: 'logistics_result',
    ok: true,
    vehicles: 2,
    window_violations: 0,
    start_time: 0,
    routes: [
      {
        vehicle: 0,
        depot: [52.5200, 13.4050],
        stops: [[52.5200, 13.4050], [52.5300, 13.4100], [52.5350, 13.4150]],
        order: [0, 1, 3],
        distance_km: 5.1,
        duration_min: 6.0,
        arrivals: [0, 101.5, 260.2],
        violations: 0,
        geojson: { type: 'FeatureCollection', features: [] }
      },
      {
        vehicle: 1,
        depot: [52.5250, 13.3980],
        stops: [[52.5250, 13.3980], [52.5150, 13.3920]],
        order: [2, 4],
        distance_km: 7.2,
        duration_min: 8.1,
        arrivals: [0, 101.5, 260.2],
        violations: 0,
        geojson: { type: 'FeatureCollection', features: [] }
      }
    ]
  };
  assert.ok(vRes(vrpResult), JSON.stringify(vRes.errors));
});

test('VRPTW request validates without the new time-window fields (backward-compat)', () => {
  const reqSchema = readJson('schemas/logistics-request.schema.json');
  const vReq = new Ajv({ strict: false }).compile(reqSchema);
  const legacy = {
    kind: 'logistics',
    stops: [[52.5200, 13.4050], [52.5300, 13.4100], [52.5250, 13.3980]],
    reply_to: 'agent://viewer.vector-01',
    vehicles: 1
  };
  assert.ok(vReq(legacy), JSON.stringify(vReq.errors));
});

test('logistics schemas reject unknown properties (additionalProperties:false)', () => {
  const reqSchema = readJson('schemas/logistics-request.schema.json');
  const resSchema = readJson('schemas/logistics-result.schema.json');
  const vReq = new Ajv({ strict: false }).compile(reqSchema);
  const vRes = new Ajv({ strict: false }).compile(resSchema);
  assert.ok(!vReq({ ...readJson('dtos/dto.examples.json').LogisticsRequest, bogus: true }), 'request allowed unknown prop');
  assert.ok(!vRes({ ...readJson('dtos/dto.examples.json').LogisticsResult, bogus: true }), 'result allowed unknown prop');
});

test('error-codes are well-formed and unique', () => {
  const { codes } = readJson('errors/error-codes.json');
  assert.ok(Array.isArray(codes) && codes.length > 0);
  const seen = new Set();
  for (const c of codes) {
    assert.match(c.code, /^VEC-\d+$/);
    assert.ok(Number.isInteger(c.http) && c.http >= 400 && c.http <= 599);
    assert.equal(typeof c.category, 'string');
    assert.equal(typeof c.message, 'string');
    assert.equal(typeof c.retryable, 'boolean');
    assert.ok(!seen.has(c.code), `duplicate code ${c.code}`);
    seen.add(c.code);
  }
});

test('validation-rules reference existing schemas', () => {
  const doc = YAML.parse(read('validation/validation-rules.yaml'));
  assert.ok(Array.isArray(doc.rules) && doc.rules.length > 0);
  for (const r of doc.rules) {
    assert.equal(typeof r.target, 'string');
    assert.ok(read(r.schema).length > 0, `schema missing: ${r.schema}`);
  }
});

test('openapi document parses and exposes paths', () => {
  const doc = YAML.parse(read('api/vector-api.openapi.yaml'));
  assert.match(doc.openapi, /^3\./);
  assert.ok(doc.paths && typeof doc.paths === 'object');
});

test('protobuf defines the shared geometry messages', () => {
  const proto = read('proto/vector.proto');
  assert.match(proto, /syntax\s*=\s*"proto3"/);
  for (const msg of ['Coordinate', 'BoundingBox', 'TileRequest']) {
    assert.ok(proto.includes(`message ${msg}`), `missing message ${msg}`);
  }
});
