import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Coordinate, LAT_MIN, LAT_MAX } from '../src/coords.js';

test('Coordinate constructs valid lat/lon', () => {
  const c = new Coordinate(52.52, 13.405);
  assert.equal(c.lat, 52.52);
  assert.equal(c.lon, 13.405);
});

test('Coordinate rejects out-of-range lat', () => {
  assert.throws(() => new Coordinate(91, 0), RangeError);
  assert.throws(() => new Coordinate(-91, 0), RangeError);
});

test('Coordinate rejects non-finite', () => {
  assert.throws(() => new Coordinate(NaN, 0), TypeError);
});

test('Coordinate.fromObject round-trips', () => {
  const c = Coordinate.fromObject({ lat: 1, lon: 2 });
  assert.deepEqual(c.toObject(), { lat: 1, lon: 2 });
});

test('Coordinate.fromObject validates shape', () => {
  assert.throws(() => Coordinate.fromObject({ lat: 1 }), TypeError);
});

test('distanceMeters is positive and sane for Berlin->Paris', () => {
  const berlin = new Coordinate(52.52, 13.405);
  const paris = new Coordinate(48.8566, 2.3522);
  const d = berlin.distanceMeters(paris);
  assert.ok(d > 800_000 && d < 900_000, `unexpected distance ${d}`);
});

test('constants are exported', () => {
  assert.equal(LAT_MIN, -90);
  assert.equal(LAT_MAX, 90);
});
