import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EntityId } from '../src/ids.js';

test('EntityId builds with generated uuid', () => {
  const id = new EntityId('tile');
  assert.match(id.toString(), /^tile:/);
  assert.ok(id.id.length > 0);
});

test('EntityId rejects bad type', () => {
  assert.throws(() => new EntityId('Tile'), TypeError);
  assert.throws(() => new EntityId(''), TypeError);
});

test('EntityId round-trips via string', () => {
  const a = new EntityId('repo', 'vector-common');
  const b = EntityId.fromString(a.toString());
  assert.deepEqual(b.toObject(), a.toObject());
});

test('EntityId.create convenience', () => {
  const id = EntityId.create('svc', 'tile-server');
  assert.equal(id.toString(), 'svc:tile-server');
});
