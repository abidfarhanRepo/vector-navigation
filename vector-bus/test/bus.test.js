import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BusService, BackpressureError, CHANNELS } from '../src/bus.js';
import { createEnvelope } from '../src/envelope.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

// In-memory scheduler: creates NO real OS timers, so tests that withhold `ack()` do not
// hang the process. Timers only fire when explicitly invoked.
function fakeScheduler() {
  const timers = new Map();
  let seq = 0;
  return {
    setTimeout: (fn) => { seq += 1; timers.set(seq, fn); return seq; },
    clearTimeout: (id) => { timers.delete(id); },
    size() { return timers.size; },
  };
}

function collect(bus, channel, consumerId, store) {
  return bus.subscribe(channel, consumerId, (msg, ack) => { store.push(msg); ack(); });
}

test('exposes the 5 Blueprint §6 channels', () => {
  assert.deepEqual(CHANNELS.sort(), ['#broadcast', '#contracts', '#escalations', '#events', '#tasks'].sort());
  assert.deepEqual(BusService.channels.sort(), CHANNELS.slice().sort());
});

test('publish to an unknown channel throws', () => {
  const bus = new BusService();
  assert.throws(() => bus.publish('#nope', createEnvelope({ intent: 'NOTIFY' })), /Unknown channel/);
});

test('publish/receive works across ALL 5 channels', () => {
  const bus = new BusService();
  const stores = {};
  for (const ch of CHANNELS) stores[ch] = [];
  for (const ch of CHANNELS) collect(bus, ch, 'c-' + ch, stores[ch]);

  for (const ch of CHANNELS) {
    bus.publish(ch, createEnvelope({ intent: 'NOTIFY', from: 'agent://a', to: 'agent://b' }));
  }
  for (const ch of CHANNELS) assert.strictEqual(stores[ch].length, 1, 'channel ' + ch + ' should receive 1');
});

test('subscribe returns an unsubscribe() that stops delivery', () => {
  const bus = new BusService();
  const store = [];
  const unsub = collect(bus, '#events', 'c1', store);
  bus.publish('#events', createEnvelope({ intent: 'NOTIFY' }));
  assert.strictEqual(store.length, 1);
  unsub();
  bus.publish('#events', createEnvelope({ intent: 'NOTIFY' }));
  assert.strictEqual(store.length, 1);
});

test('at-least-once: handler fails first delivery then succeeds; processed exactly once', async () => {
  const bus = new BusService({ deliveryTimeout: 5000, baseBackoffMs: 5, maxRetries: 5 });
  let calls = 0;
  const done = deferred();
  const unsub = bus.subscribe('#tasks', 'c1', (msg, ack) => {
    calls += 1;
    if (calls === 1) throw new Error('transient failure');
    ack();
    done.resolve();
  });
  const env = createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s' });
  bus.publish('#tasks', env);
  await done.promise;
  assert.ok(calls >= 2, 'handler should have been retried');
  unsub();
});

test('idempotent consume: a duplicate redelivery of an acked id is a no-op', () => {
  const bus = new BusService();
  const store = [];
  const unsub = bus.subscribe('#tasks', 'c1', (msg, ack) => { store.push(msg.id); ack(); });
  const id = 'dup-42';
  bus.publish('#tasks', createEnvelope({ id, intent: 'TASK', from: 'agent://w', to: 'agent://s' }));
  bus.publish('#tasks', createEnvelope({ id, intent: 'TASK', from: 'agent://w', to: 'agent://s' }));
  assert.strictEqual(store.length, 1, 'handler must be called exactly once for a duplicate id');
  assert.deepEqual([...store], [id]);
  unsub();
});

test('backpressure engages when queue exceeds cap and resolves after drain', async () => {
  const bus = new BusService({ maxQueue: 2, highWaterMark: 1, deliveryTimeout: 100000, maxRetries: 100, baseBackoffMs: 5, scheduler: fakeScheduler() });
  const acks = [];
  const unsub = bus.subscribe('#tasks', 'c1', (msg, ack) => { acks.push(ack); });
  const promises = [];
  for (let i = 0; i < 5; i += 1) {
    const p = bus.publish('#tasks', createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s' }));
    if (p && typeof p.then === 'function') promises.push(p);
  }
  assert.strictEqual(promises.length, 3, 'publishes beyond maxQueue must return backpressure promises');
  // Drain: ack 4 messages to bring in-flight below the high-water mark.
  for (let i = 0; i < 4; i += 1) acks[i]();
  await Promise.all(promises);
  if (acks[4]) acks[4]();
  unsub();
});

test('hard cap throws BackpressureError', () => {
  const bus = new BusService({ maxQueue: 100, hardCap: 1, deliveryTimeout: 100000, scheduler: fakeScheduler() });
  const unsub = bus.subscribe('#tasks', 'c1', (msg, ack) => { /* withhold ack */ });
  bus.publish('#tasks', createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s' })); // pending=1, ok
  assert.throws(
    () => bus.publish('#tasks', createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s' })),
    (e) => e instanceof BackpressureError,
  );
  unsub();
});

test('dead-letter is invoked after max retries', async () => {
  const bus = new BusService({ deliveryTimeout: 5000, baseBackoffMs: 5, maxRetries: 3 });
  let calls = 0;
  const dl = deferred();
  const unsub = bus.subscribe('#tasks', 'c1', () => { calls += 1; throw new Error('always fail'); });
  bus.onDeadLetter((rec) => dl.resolve(rec));
  bus.publish('#tasks', createEnvelope({ intent: 'TASK', from: 'agent://w', to: 'agent://s' }));
  const rec = await dl.promise;
  assert.strictEqual(rec.channel, '#tasks');
  assert.ok(rec.message, 'dead-letter record carries the message');
  assert.ok(calls >= 4, 'handler should be attempted (maxRetries+1) times before dead-letter');
  unsub();
});

test('invalid envelope is rejected at the boundary', () => {
  const bus = new BusService();
  const unsub = bus.subscribe('#events', 'c1', () => {});
  assert.throws(
    () => bus.publish('#events', { from: 'agent://w' }), // missing required fields
    /Invalid envelope rejected at boundary/,
  );
  unsub();
});
