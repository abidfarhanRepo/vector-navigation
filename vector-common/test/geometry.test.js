import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BoundingBox } from '../src/geometry.js';
import { Coordinate } from '../src/coords.js';

test('BoundingBox constructs valid box', () => {
  const b = new BoundingBox(52.3, 13.2, 52.7, 13.6);
  assert.equal(b.minLat, 52.3);
});

test('BoundingBox rejects inverted lat', () => {
  assert.throws(() => new BoundingBox(10, 0, 5, 1), RangeError);
});

test('contains works on corners and interior', () => {
  const b = new BoundingBox(0, 0, 10, 10);
  assert.ok(b.contains(new Coordinate(5, 5)));
  assert.ok(b.contains(new Coordinate(0, 0)));
  assert.ok(!b.contains(new Coordinate(11, 5)));
});

test('intersects detects overlap and separation', () => {
  const a = new BoundingBox(0, 0, 10, 10);
  const overlap = new BoundingBox(5, 5, 15, 15);
  const separate = new BoundingBox(20, 20, 30, 30);
  assert.ok(a.intersects(overlap));
  assert.ok(!a.intersects(separate));
});

test('areaMetersSquared is positive', () => {
  const b = new BoundingBox(52.3, 13.2, 52.7, 13.6);
  assert.ok(b.areaMetersSquared() > 0);
});
