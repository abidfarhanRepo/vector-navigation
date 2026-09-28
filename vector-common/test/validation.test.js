import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Coordinate, BoundingBox } from '../src/index.js';
import { assertCoordinate, assertBoundingBox, assertRange } from '../src/validation.js';
import { VectorError } from '../src/errors.js';

test('assertCoordinate accepts Coordinate instance', () => {
  const c = new Coordinate(1, 2);
  assert.strictEqual(assertCoordinate(c), c);
});

test('assertCoordinate coerces valid object', () => {
  const c = assertCoordinate({ lat: 1, lon: 2 });
  assert.ok(c instanceof Coordinate);
});

test('assertCoordinate throws VectorError on invalid', () => {
  assert.throws(
    () => assertCoordinate({ lat: 999, lon: 0 }),
    (err) => err instanceof VectorError && err.code === 'VEC-0001',
  );
});

test('assertBoundingBox coerces valid object', () => {
  const b = assertBoundingBox({ minLat: 0, minLon: 0, maxLat: 1, maxLon: 1 });
  assert.ok(b instanceof BoundingBox);
  assert.equal(b.minLat, 0);
});

test('assertRange throws VectorError out of bounds', () => {
  assert.throws(
    () => assertRange(5, 0, 3, 'zoom'),
    (err) => err instanceof VectorError && err.code === 'VEC-0001',
  );
});
