import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BusService } from '../src/bus.js';
import { ChannelRouter, route } from '../src/channel-router.js';
import { createEnvelope } from '../src/envelope.js';

// In-memory scheduler: creates NO real OS timers, so tests are deterministic and
// the process always exits. `fireAll()` triggers every scheduled callback on demand.
function fakeScheduler() {
  const timers = new Map();
  let seq = 0;
  return {
    setTimeout: (fn) => { seq += 1; timers.set(seq, fn); return seq; },
    clearTimeout: (id) => { timers.delete(id); },
    fireAll() {
      const entries = [...timers.entries()];
      timers.clear();
      for (const [, fn] of entries) fn();
    },
    size() { return timers.size; },
  };
}

test('routing matrix maps each intent to the correct channel', () => {
  const cases = [
    [{ intent: 'TASK' }, '#tasks'],
    [{ intent: 'ASSIGN' }, '#tasks'],
    [{ intent: 'REVIEW' }, '#contracts'],
    [{ intent: 'CONTRACT' }, '#contracts'],
    [{ intent: 'ESCALATE' }, '#escalations'],
    [{ intent: 'NOTIFY' }, '#broadcast'],
    [{ intent: 'OTHER' }, '#events'],
    [{}, '#events'],
    [undefined, '#events'],
  ];
  for (const [env, ch] of cases) assert.strictEqual(route(env), ch);
});

test('ChannelRouter.route is exposed as a static method', () => {
  assert.strictEqual(ChannelRouter.route({ intent: 'NOTIFY' }), '#broadcast');
});

test('SLA breach raises #escalations with correct payload and fires onEscalation', () => {
  const routerSched = fakeScheduler();
  const busSched = fakeScheduler();
  const bus = new BusService({ scheduler: busSched, deliveryTimeout: 100000, maxRetries: 0 });
  const router = new ChannelRouter(bus, { scheduler: routerSched });

  const escalations = [];
  bus.subscribe('#escalations', 'coa', (msg, ack) => { escalations.push(msg); ack(); });
  const received = [];
  router.onEscalation((esc) => received.push(esc));

  const env = createEnvelope({
    id: 'msg-1',
    correlation_id: 'corr-1',
    from: 'agent://worker.sec-12',
    to: 'agent://sla.auth-01',
    intent: 'TASK',
    priority: 'P1',
    sla_ms: 50,
  });
  const { channel, backpressure } = router.publish(env);
  assert.strictEqual(channel, '#tasks');
  assert.strictEqual(routerSched.size(), 1, 'one SLA timer pending');
  assert.strictEqual(backpressure, undefined);

  routerSched.fireAll(); // trigger the SLA breach

  assert.strictEqual(received.length, 1, 'onEscalation called once');
  const esc = received[0];
  assert.strictEqual(esc.intent, 'ESCALATE');
  assert.strictEqual(esc.priority, 'P0');
  assert.strictEqual(esc.correlation_id, 'corr-1');
  assert.strictEqual(esc.payload.level, 'L0');
  assert.match(esc.payload.reason, /SLA breach/);
  assert.strictEqual(esc.to, 'agent://worker.sec-12', 'L0 -> source DMA (from)');
  assert.strictEqual(escalations.length, 1, 'escalation published to #escalations');
});

test('no escalation when the message is acked before the SLA fires', () => {
  const routerSched = fakeScheduler();
  const busSched = fakeScheduler();
  const bus = new BusService({ scheduler: busSched, deliveryTimeout: 100000, maxRetries: 0 });
  const router = new ChannelRouter(bus, { scheduler: routerSched });

  const received = [];
  router.onEscalation((esc) => received.push(esc));

  // Subscribe through the router: ack() cancels the SLA timer.
  router.subscribe('#tasks', 'c1', (msg, ack) => { ack(); });

  const env = createEnvelope({
    id: 'msg-2',
    from: 'agent://w',
    to: 'agent://s',
    intent: 'TASK',
    priority: 'P1',
    sla_ms: 50,
  });
  router.publish(env);
  // The consumer acked synchronously, so the SLA timer was already cancelled.
  assert.strictEqual(routerSched.size(), 0, 'SLA timer cancelled on ack');

  routerSched.fireAll();
  assert.strictEqual(received.length, 0, 'no escalation when acked in time');
});

test('router.ack() cancels the pending SLA timer (manual cancel)', () => {
  const routerSched = fakeScheduler();
  const busSched = fakeScheduler();
  const bus = new BusService({ scheduler: busSched, deliveryTimeout: 100000, maxRetries: 0 });
  const router = new ChannelRouter(bus, { scheduler: routerSched });

  const received = [];
  router.onEscalation((esc) => received.push(esc));
  bus.subscribe('#tasks', 'c1', () => { /* never acks */ });

  const env = createEnvelope({
    id: 'msg-3',
    from: 'agent://w',
    to: 'agent://s',
    intent: 'TASK',
    priority: 'P1',
    sla_ms: 50,
  });
  router.publish(env);
  assert.strictEqual(routerSched.size(), 1);
  router.ack(env.id);
  assert.strictEqual(routerSched.size(), 0, 'manual ack clears the timer');

  routerSched.fireAll();
  assert.strictEqual(received.length, 0, 'no escalation after manual ack');
});
