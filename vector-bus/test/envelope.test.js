import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  validate,
  createEnvelope,
  stampEscalation,
  INTENTS,
  PRIORITIES,
} from '../src/envelope.js';

test('valid envelope passes validation', () => {
  const env = createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s', priority: 'P0' });
  const res = validate(env);
  assert.strictEqual(res.valid, true);
  assert.deepEqual(res.errors, []);
});

test('missing each required field is reported', () => {
  const base = createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s' });
  for (const field of ['id', 'correlation_id', 'from', 'to', 'intent', 'priority', 'sla_ms', 'timestamp']) {
    const env = { ...base };
    delete env[field];
    const res = validate(env);
    assert.strictEqual(res.valid, false);
    assert.ok(res.errors.some((e) => e.includes(field)), 'expected error mentioning ' + field);
  }
});

test('wrong types are rejected', () => {
  const env = createEnvelope({ intent: 'TASK' });
  const bad = { ...env, id: 123, correlation_id: 456, from: 7, to: 8, timestamp: 9 };
  const res = validate(bad);
  assert.strictEqual(res.valid, false);
  assert.ok(res.errors.some((e) => e.includes('id')));
  assert.ok(res.errors.some((e) => e.includes('from')));
  assert.ok(res.errors.some((e) => e.includes('timestamp')));
});

test('invalid intent is rejected', () => {
  const env = createEnvelope({ intent: 'BOGUS' });
  const res = validate(env);
  assert.strictEqual(res.valid, false);
  assert.ok(res.errors.some((e) => e.includes('intent')));
});

test('invalid priority is rejected', () => {
  const env = createEnvelope({ priority: 'P9' });
  const res = validate(env);
  assert.strictEqual(res.valid, false);
  assert.ok(res.errors.some((e) => e.includes('priority')));
});

test('non-numeric sla_ms is rejected', () => {
  // Build the bad envelope directly: createEnvelope() sanitizes sla_ms to a default,
  // so the invalid value must be injected after construction to exercise validate().
  const env = { ...createEnvelope({ intent: 'TASK' }), sla_ms: 'fast' };
  const res = validate(env);
  assert.strictEqual(res.valid, false);
  assert.ok(res.errors.some((e) => e.includes('sla_ms')));
});

test('empty timestamp is rejected', () => {
  const env = createEnvelope({ timestamp: '' });
  const res = validate(env);
  assert.strictEqual(res.valid, false);
  assert.ok(res.errors.some((e) => e.includes('timestamp')));
});

test('createEnvelope fills defaults', () => {
  const env = createEnvelope();
  assert.match(env.id, /^[0-9a-f-]{36}$/);
  assert.strictEqual(env.correlation_id, env.id);
  assert.strictEqual(env.intent, 'NOTIFY');
  assert.strictEqual(env.priority, 'P2');
  assert.strictEqual(env.sla_ms, 3600000);
  assert.match(env.timestamp, /^\d{4}-\d{2}-\d{2}T/);
  assert.deepEqual(env.payload, {});
});

test('createEnvelope keeps provided values', () => {
  const env = createEnvelope({
    id: 'x',
    correlation_id: 'c',
    from: 'agent://w',
    to: 'agent://s',
    intent: 'REVIEW',
    priority: 'P1',
    sla_ms: 123,
    timestamp: '2026-01-01T00:00:00Z',
    payload: { a: 1 },
  });
  assert.strictEqual(env.id, 'x');
  assert.strictEqual(env.correlation_id, 'c');
  assert.strictEqual(env.intent, 'REVIEW');
  assert.strictEqual(env.priority, 'P1');
  assert.strictEqual(env.sla_ms, 123);
  assert.deepEqual(env.payload, { a: 1 });
});

test('INTENTS and PRIORITIES are exported correctly', () => {
  assert.deepEqual(INTENTS, ['TASK', 'ASSIGN', 'REVIEW', 'ESCALATE', 'NOTIFY', 'CONTRACT']);
  assert.deepEqual(PRIORITIES, ['P0', 'P1', 'P2', 'P3']);
});

test('stampEscalation returns a NEW escalation envelope with correct fields', () => {
  const src = createEnvelope({
    id: 'm1',
    correlation_id: 'corr-1',
    from: 'agent://worker.sec-12',
    to: 'agent://sla.auth-01',
    intent: 'TASK',
    priority: 'P1',
    sla_ms: 5000,
  });
  const esc = stampEscalation(src, { level: 'L0', reason: 'no answer' });
  assert.notStrictEqual(esc, src, 'must not mutate input');
  assert.notStrictEqual(src.intent, 'ESCALATE', 'input must be untouched');
  assert.strictEqual(esc.intent, 'ESCALATE');
  assert.strictEqual(esc.priority, 'P0');
  assert.strictEqual(esc.correlation_id, 'corr-1');
  assert.strictEqual(esc.to, 'agent://worker.sec-12', 'L0/L1 -> source DMA (from)');
  assert.strictEqual(esc.payload.level, 'L0');
  assert.strictEqual(esc.payload.reason, 'no answer');
  assert.match(esc.id, /^[0-9a-f-]{36}$/);
});

test('stampEscalation routing per level', () => {
  const src = createEnvelope({ from: 'agent://worker.sec-12', to: 'agent://sla.auth-01' });
  const l0 = stampEscalation(src, { level: 'L0' });
  const l1 = stampEscalation(src, { level: 'L1' });
  const l2 = stampEscalation(src, { level: 'L2' });
  const l3 = stampEscalation(src, { level: 'L3' });
  const l4 = stampEscalation(src, { level: 'L4' });
  assert.strictEqual(l0.to, 'agent://worker.sec-12');
  assert.strictEqual(l1.to, 'agent://worker.sec-12');
  assert.strictEqual(l2.to, 'agent://coa');
  assert.strictEqual(l3.to, 'agent://cto');
  assert.strictEqual(l4.to, 'agent://cto');
});

test('stampEscalation default level is L0', () => {
  const src = createEnvelope({ from: 'agent://w', to: 'agent://s' });
  const esc = stampEscalation(src);
  assert.strictEqual(esc.payload.level, 'L0');
});
