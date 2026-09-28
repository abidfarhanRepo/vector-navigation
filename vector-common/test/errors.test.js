import { test } from 'node:test';
import assert from 'node:assert/strict';
import { VectorError, errorFromCode, ERROR_CODES } from '../src/errors.js';

test('VectorError carries code + http status + category', () => {
  const e = errorFromCode('VEC-0404'.replace('0404', '0004'));
  assert.equal(e.code, 'VEC-0004');
  assert.equal(e.httpStatus, 404);
  assert.equal(e.retryable, false);
});

test('errorFromCode maps known codes', () => {
  const e = errorFromCode('VEC-0500', { detail: 'boom' });
  assert.equal(e.httpStatus, 500);
  assert.equal(e.retryable, true);
  assert.equal(e.detail, 'boom');
});

test('toEnvelope emits standard shape', () => {
  const e = errorFromCode('VEC-0001', { correlationId: 'cid-1' });
  assert.deepEqual(e.toEnvelope(), {
    code: 'VEC-0001',
    message: 'Invalid request payload',
    correlation_id: 'cid-1',
  });
});

test('unknown code throws', () => {
  assert.throws(() => new VectorError('VEC-9999'), TypeError);
});

test('all codes have required fields', () => {
  for (const c of ERROR_CODES) {
    assert.match(c.code, /^VEC-\d{4}$/);
    assert.ok(c.http >= 400 && c.http < 600);
    assert.ok(['validation', 'auth', 'not_found', 'conflict', 'internal', 'unavailable', 'timeout'].includes(c.category));
    assert.equal(typeof c.retryable, 'boolean');
  }
});
