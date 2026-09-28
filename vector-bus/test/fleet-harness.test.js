import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createBusServer } from '../src/server.js';
import { NetworkBusClient } from '../src/client.js';
import { createEnvelope } from '../src/envelope.js';

// THE fleet integration proof: a networked E1–E9 fleet behind the real E2 Bus.
// One BusServer, multiple NetworkBusClients acting as fleet roles, driving a realistic
// multi-hop flow across channels — all over the wire.

function deferred() {
  let resolve;
  const promise = new Promise((res) => { resolve = res; });
  return { promise, resolve };
}

async function bootFleet(t, options) {
  const server = createBusServer(options);
  const { port, host } = await server.listen(0, '127.0.0.1');
  const clients = [];
  const role = () => {
    const c = new NetworkBusClient({ host, port });
    clients.push(c);
    return c;
  };
  const unsubs = [];
  const track = (u) => { unsubs.push(u); return u; };
  t.after(async () => {
    for (const u of unsubs) { try { u(); } catch { /* ignore */ } }
    for (const c of clients) await c.close();
    await server.close();
  });
  return { server, port, host, role, track };
}

test('fleet: TASK -> #tasks -> worker -> REVIEW -> #contracts -> ci-gate -> event -> #events -> kg-ingest', async (t) => {
  const { role, track } = await bootFleet(t, { deliveryTimeout: 500, maxRetries: 2 });

  const scheduler = role(); // coa-scheduler
  const worker = role();
  const ciGate = role(); // ci-gate
  const kgIngest = role(); // kg-ingest

  const workerGot = deferred();
  const ciGateGot = deferred();
  const kgGot = deferred();

  // Hop 3: kg-ingest consumes an event off #events.
  const uKg = track(kgIngest.subscribe('#events', 'kg-ingest', (message, ack) => {
    ack();
    kgGot.resolve(message);
  }));

  // Hop 2: ci-gate consumes a REVIEW off #contracts, then emits an event to #events.
  const uCi = track(ciGate.subscribe('#contracts', 'ci-gate', (message, ack) => {
    ack();
    ciGateGot.resolve(message);
    const evt = createEnvelope({
      intent: 'NOTIFY',
      from: 'agent://ci-gate',
      to: 'agent://kg-ingest',
      correlation_id: message.correlation_id,
      payload: { kind: 'contract-approved', of: message.id },
    });
    // Explicit channel: NOTIFY routes to #broadcast by default, but the fleet's fact
    // stream is #events, so we target it directly. (catch guards teardown races.)
    ciGate.publish('#events', evt).catch(() => {});
  }));

  // Hop 1: worker consumes a TASK off #tasks, then emits a REVIEW to #contracts.
  const uWorker = track(worker.subscribe('#tasks', 'worker-1', (message, ack) => {
    ack();
    workerGot.resolve(message);
    const review = createEnvelope({
      intent: 'REVIEW',
      from: 'agent://worker-1',
      to: 'agent://ci-gate',
      correlation_id: message.correlation_id,
      payload: { kind: 'review-request', of: message.id },
    });
    worker.publish('#contracts', review).catch(() => {});
  }));

  await Promise.all([uKg.ready, uCi.ready, uWorker.ready]);

  // Kick off the flow: scheduler assigns a TASK (channel omitted -> routed to #tasks).
  const task = createEnvelope({ intent: 'TASK', from: 'agent://coa-scheduler', to: 'agent://worker-1', payload: { job: 'build' } });
  const pubResult = await scheduler.publish(null, task);
  assert.strictEqual(pubResult.channel, '#tasks');

  const gotTask = await workerGot.promise;
  assert.strictEqual(gotTask.id, task.id, 'worker received the TASK');

  const gotReview = await ciGateGot.promise;
  assert.strictEqual(gotReview.intent, 'REVIEW', 'ci-gate received the REVIEW');
  assert.strictEqual(gotReview.correlation_id, task.correlation_id);

  const gotEvent = await kgGot.promise;
  assert.strictEqual(gotEvent.payload.kind, 'contract-approved', 'kg-ingest received the event');
  assert.strictEqual(gotEvent.correlation_id, task.correlation_id, 'correlation preserved end-to-end');
});

test('fleet: escalation path — ESCALATE routed to #escalations reaches the COA subscriber', async (t) => {
  const { role, track } = await bootFleet(t, { deliveryTimeout: 400 });
  const coa = role();
  const dma = role();
  const got = deferred();

  const u = track(coa.subscribe('#escalations', 'coa', (message, ack) => { ack(); got.resolve(message); }));
  await u.ready;

  const esc = createEnvelope({ intent: 'ESCALATE', from: 'agent://dma', to: 'agent://coa', priority: 'P0', payload: { level: 'L2', reason: 'SLA breach' } });
  const result = await dma.publish(null, esc); // routed by intent
  assert.strictEqual(result.channel, '#escalations');

  const received = await got.promise;
  assert.strictEqual(received.id, esc.id);
  assert.strictEqual(received.intent, 'ESCALATE');
});

test('fleet: broadcast fan-out — one NOTIFY to #broadcast reaches every role', async (t) => {
  const { role, track } = await bootFleet(t, { deliveryTimeout: 400 });
  const cto = role();
  const roleA = role();
  const roleB = role();

  const gotA = deferred();
  const gotB = deferred();
  const uA = track(roleA.subscribe('#broadcast', 'role-a', (m, ack) => { ack(); gotA.resolve(m); }));
  const uB = track(roleB.subscribe('#broadcast', 'role-b', (m, ack) => { ack(); gotB.resolve(m); }));
  await Promise.all([uA.ready, uB.ready]);

  const notice = createEnvelope({ intent: 'NOTIFY', from: 'agent://cto', to: 'agent://all', payload: { notice: 'all hands' } });
  const result = await cto.publish(null, notice); // NOTIFY -> #broadcast
  assert.strictEqual(result.channel, '#broadcast');

  const [a, b] = await Promise.all([gotA.promise, gotB.promise]);
  assert.strictEqual(a.id, notice.id);
  assert.strictEqual(b.id, notice.id);
});

test('fleet: SLA/dead-letter path — an unacked task surfaces a dead-letter to the fleet', async (t) => {
  const { role, track } = await bootFleet(t, { deliveryTimeout: 60, maxRetries: 0 });
  const worker = role();
  const scheduler = role();
  const dl = deferred();

  worker.onDeadLetter((record) => dl.resolve(record));
  const u = track(worker.subscribe('#tasks', 'stuck-worker', () => {
    // Simulate a stuck worker that never completes the task.
  }));
  await u.ready;

  const task = createEnvelope({ intent: 'TASK', from: 'agent://coa-scheduler', to: 'agent://stuck-worker', sla_ms: 50 });
  await scheduler.publish(null, task);

  const record = await dl.promise;
  assert.strictEqual(record.channel, '#tasks');
  assert.strictEqual(record.message.id, task.id);
});

test('fleet: healthz sanity across a running fleet server', async (t) => {
  const { role } = await bootFleet(t);
  const client = role();
  // A trivial publish proves the server is live and accepting fleet traffic.
  const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b' });
  const result = await client.publish('#tasks', env);
  assert.strictEqual(result.accepted, true);
});

test('fleet: two workers on #tasks both receive the same broadcast task (competing-consumer visibility)', async (t) => {
  const { role, track } = await bootFleet(t, { deliveryTimeout: 400 });
  const scheduler = role();
  const w1 = role();
  const w2 = role();
  const got1 = deferred();
  const got2 = deferred();

  const u1 = track(w1.subscribe('#tasks', 'worker-1', (m, ack) => { ack(); got1.resolve(m); }));
  const u2 = track(w2.subscribe('#tasks', 'worker-2', (m, ack) => { ack(); got2.resolve(m); }));
  await Promise.all([u1.ready, u2.ready]);

  const task = createEnvelope({ intent: 'TASK', from: 'agent://coa', to: 'agent://workers' });
  await scheduler.publish('#tasks', task);

  const [m1, m2] = await Promise.all([got1.promise, got2.promise]);
  assert.strictEqual(m1.id, task.id);
  assert.strictEqual(m2.id, task.id);
});
