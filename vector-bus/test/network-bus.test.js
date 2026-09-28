import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createBusServer, BusServer } from '../src/server.js';
import { NetworkBusClient } from '../src/client.js';
import { createEnvelope } from '../src/envelope.js';

// A promise that resolves after being triggered `n` times, collecting the args.
function counter(n) {
  const items = [];
  let resolve;
  const promise = new Promise((res) => { resolve = res; });
  const push = (item) => {
    items.push(item);
    if (items.length >= n) resolve(items);
  };
  return { promise, push, items };
}

function deferred() {
  let resolve;
  const promise = new Promise((res) => { resolve = res; });
  return { promise, resolve };
}

// Raw JSON request helper (for endpoints the client does not expose, e.g. /healthz, /ack).
function rawRequest(method, port, path, bodyObj) {
  return new Promise((resolve, reject) => {
    const body = bodyObj === undefined ? '' : JSON.stringify(bodyObj);
    const headers = {};
    if (body) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(body);
    }
    const req = http.request({ host: '127.0.0.1', port, path, method, headers }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

// Boot a server + track clients; guarantees full teardown via t.after (no dangling handles).
async function boot(t, options) {
  const server = createBusServer(options);
  const { port, host } = await server.listen(0, '127.0.0.1');
  const clients = [];
  const makeClient = () => {
    const c = new NetworkBusClient({ host, port });
    clients.push(c);
    return c;
  };
  t.after(async () => {
    for (const c of clients) await c.close();
    await server.close();
  });
  return { server, port, host, makeClient };
}

test('createBusServer returns a BusServer instance', () => {
  const s = createBusServer();
  assert.ok(s instanceof BusServer);
});

test('GET /healthz returns 200 "ok"', async (t) => {
  const { port } = await boot(t);
  const res = await rawRequest('GET', port, '/healthz');
  assert.strictEqual(res.status, 200);
  assert.strictEqual(res.body, 'ok');
});

test('unknown path returns 404', async (t) => {
  const { port } = await boot(t);
  const res = await rawRequest('GET', port, '/nope');
  assert.strictEqual(res.status, 404);
});

test('publish returns 202 with accepted/id/channel', async (t) => {
  const { makeClient } = await boot(t);
  const client = makeClient();
  const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b' });
  const result = await client.publish('#tasks', env);
  assert.strictEqual(result.accepted, true);
  assert.strictEqual(result.id, env.id);
  assert.strictEqual(result.channel, '#tasks');
});

test('publish + subscribe round trip delivers the same message and acks', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 200 });
  const sub = makeClient();
  const pub = makeClient();
  const got = deferred();

  const unsub = sub.subscribe('#tasks', 'worker-1', (message, ack) => {
    ack();
    got.resolve(message);
  });
  await unsub.ready;

  const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b', payload: { k: 42 } });
  await pub.publish('#tasks', env);

  const received = await got.promise;
  assert.strictEqual(received.id, env.id);
  assert.strictEqual(received.payload.k, 42);
  unsub();
});

test('publish with omitted channel routes by intent (TASK -> #tasks)', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 200 });
  const sub = makeClient();
  const pub = makeClient();
  const got = deferred();

  const unsub = sub.subscribe('#tasks', 'worker-r', (message, ack) => { ack(); got.resolve(message); });
  await unsub.ready;

  const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b' });
  const result = await pub.publish(null, env); // no channel -> server routes
  assert.strictEqual(result.channel, '#tasks');

  const received = await got.promise;
  assert.strictEqual(received.id, env.id);
  unsub();
});

test('invalid envelope -> publish rejects (400)', async (t) => {
  const { makeClient } = await boot(t);
  const client = makeClient();
  await assert.rejects(
    () => client.publish('#events', { from: 'agent://a' }), // missing required fields
    /400/,
  );
});

test('unknown channel -> publish rejects (400)', async (t) => {
  const { makeClient } = await boot(t);
  const client = makeClient();
  const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b' });
  await assert.rejects(
    () => client.publish('#nope', env),
    /400/,
  );
});

test('multi-consumer fan-out: two subscribers both receive a message', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 300 });
  const a = makeClient();
  const b = makeClient();
  const pub = makeClient();
  const both = counter(2);

  const unsubA = a.subscribe('#events', 'consumer-a', (message, ack) => { ack(); both.push(message.id); });
  const unsubB = b.subscribe('#events', 'consumer-b', (message, ack) => { ack(); both.push(message.id); });
  await Promise.all([unsubA.ready, unsubB.ready]);

  const env = createEnvelope({ intent: 'NOTIFY', from: 'agent://a', to: 'agent://b' });
  await pub.publish('#events', env);

  const ids = await both.promise;
  assert.strictEqual(ids.length, 2);
  assert.deepEqual(ids, [env.id, env.id]);
  unsubA();
  unsubB();
});

test('subscribe with server-generated consumerId (omitted) still delivers', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 200 });
  const sub = makeClient();
  const pub = makeClient();
  const got = deferred();

  const unsub = sub.subscribe('#broadcast', undefined, (message, ack) => { ack(); got.resolve(message); });
  await unsub.ready;

  const env = createEnvelope({ intent: 'NOTIFY', from: 'agent://a', to: 'agent://b' });
  await pub.publish('#broadcast', env);
  const received = await got.promise;
  assert.strictEqual(received.id, env.id);
  unsub();
});

test('unsubscribe stops further delivery', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 150 });
  const sub = makeClient();
  const pub = makeClient();
  const first = deferred();
  let count = 0;

  const unsub = sub.subscribe('#events', 'c-unsub', (message, ack) => {
    count += 1;
    ack();
    if (count === 1) first.resolve(message);
  });
  await unsub.ready;

  await pub.publish('#events', createEnvelope({ intent: 'NOTIFY', from: 'agent://a', to: 'agent://b' }));
  await first.promise;
  assert.strictEqual(count, 1);

  unsub();
  // Give the server a moment to observe the closed stream (bounded, deterministic).
  await new Promise((r) => setTimeout(r, 50));

  await pub.publish('#events', createEnvelope({ intent: 'NOTIFY', from: 'agent://a', to: 'agent://b' }));
  await new Promise((r) => setTimeout(r, 100));
  assert.strictEqual(count, 1, 'no delivery after unsubscribe');
});

test('/ack with unknown deliveryId is idempotent (acked:false, never errors)', async (t) => {
  const { port } = await boot(t);
  const res = await rawRequest('POST', port, '/ack', { deliveryId: 'does-not-exist', consumerId: 'x', channel: '#tasks' });
  assert.strictEqual(res.status, 200);
  assert.deepEqual(JSON.parse(res.body), { acked: false });
});

test('dead-letter: a never-acked delivery reaches onDeadLetter over the wire', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 60, maxRetries: 0 });
  const sub = makeClient();
  const pub = makeClient();
  const dl = deferred();

  sub.onDeadLetter((record) => dl.resolve(record));
  const unsub = sub.subscribe('#tasks', 'lazy-worker', () => {
    // Never ack -> bus delivery times out -> dead-letter.
  });
  await unsub.ready;

  const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b' });
  await pub.publish('#tasks', env);

  const record = await dl.promise;
  assert.strictEqual(record.channel, '#tasks');
  assert.strictEqual(record.message.id, env.id);
  assert.ok(record.attempts >= 1);
  unsub();
});

test('multiple messages delivered to a single consumer', async (t) => {
  const { makeClient } = await boot(t, { deliveryTimeout: 300 });
  const sub = makeClient();
  const pub = makeClient();
  const three = counter(3);

  const unsub = sub.subscribe('#tasks', 'seq-worker', (message, ack) => { ack(); three.push(message.id); });
  await unsub.ready;

  const envs = [];
  for (let i = 0; i < 3; i += 1) {
    const env = createEnvelope({ intent: 'TASK', from: 'agent://a', to: 'agent://b', payload: { i } });
    envs.push(env);
    await pub.publish('#tasks', env);
  }

  const ids = await three.promise;
  assert.strictEqual(ids.length, 3);
  for (const env of envs) assert.ok(ids.includes(env.id));
  unsub();
});

test('close() tears everything down with no hang', async (t) => {
  // This test intentionally does NOT use boot()'s auto-teardown for the server; it
  // closes explicitly to prove close() resolves cleanly.
  const server = createBusServer({ deliveryTimeout: 100 });
  const { port, host } = await server.listen(0, '127.0.0.1');
  const client = new NetworkBusClient({ host, port });
  const got = deferred();
  const unsub = client.subscribe('#events', 'c-close', (message, ack) => { ack(); got.resolve(message); });
  await unsub.ready;
  await client.publish('#events', createEnvelope({ intent: 'NOTIFY', from: 'agent://a', to: 'agent://b' }));
  await got.promise;

  await client.close();
  await server.close();
  assert.ok(true, 'close resolved without hanging');
});
