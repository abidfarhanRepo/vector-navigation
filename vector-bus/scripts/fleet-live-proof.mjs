import { randomUUID } from 'node:crypto';
import { NetworkBusClient } from '../src/client.js';
import { createEnvelope, validate } from '../src/envelope.js';

const BUS_URL =
  process.env.BUS_URL || process.env.VECTOR_BUS_URL || 'http://localhost:8090';

const results = [];

function withTimeout(promise, ms, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error((label || 'operation') + ' timed out after ' + ms + 'ms'));
    }, ms);
    promise.then(
      (v) => { clearTimeout(timer); resolve(v); },
      (e) => { clearTimeout(timer); reject(e); },
    );
  });
}

async function closeAll(items) {
  await Promise.allSettled(items.map((x) => (x ? x.close() : Promise.resolve())));
}

async function runTest(name, fn) {
  try {
    await fn();
    results.push({ name, pass: true });
    console.log('PASS: ' + name);
  } catch (err) {
    const reason = err && err.message ? err.message : String(err);
    results.push({ name, pass: false, reason });
    console.log('FAIL: ' + name + ' (' + reason + ')');
  }
}

async function main() {
  await runTest('dual-client-roundtrip', async () => {
    const clientA = new NetworkBusClient(BUS_URL);
    const clientB = new NetworkBusClient(BUS_URL);
    try {
      const correlation_id = randomUUID();
      let resolveB;
      const p = new Promise((res) => { resolveB = res; });
      const unsubB = clientB.subscribe('#events', 'client-b', (msg, ack) => {
        ack();
        resolveB(msg);
      });
      await unsubB.ready;

      const env = createEnvelope({
        id: 'a1',
        intent: 'NOTIFY',
        from: 'agent://a',
        to: 'agent://b',
        correlation_id,
        payload: { hello: 'world' },
      });
      const v = validate(env);
      if (!v.valid) throw new Error('envelope invalid: ' + v.errors.join('; '));

      await clientA.publish('#events', env);
      const msg = await withTimeout(p, 10000, 'dual-client delivery');

      if (msg.correlation_id !== correlation_id) {
        throw new Error('correlation_id not preserved (got ' + JSON.stringify(msg.correlation_id) + ')');
      }
      if (!msg.payload || msg.payload.hello !== 'world') {
        throw new Error('payload.hello not preserved (got ' + JSON.stringify(msg && msg.payload) + ')');
      }
      unsubB();
    } finally {
      await closeAll([clientA, clientB]);
    }
  });

  await runTest('fleet-multihop', async () => {
    const coa = new NetworkBusClient(BUS_URL);
    const worker = new NetworkBusClient(BUS_URL);
    const ciGate = new NetworkBusClient(BUS_URL);
    const kgIngest = new NetworkBusClient(BUS_URL);
    try {
      const correlation_id = randomUUID();
      let resolveFinal;
      const pFinal = new Promise((res) => { resolveFinal = res; });
      let resolveReview;
      const pReview = new Promise((res) => { resolveReview = res; });
      let resolveTask;
      const pTask = new Promise((res) => { resolveTask = res; });

      const unsubWorker = worker.subscribe('#tasks', 'worker', (msg, ack) => {
        ack();
        resolveTask(msg);
        const review = createEnvelope({
          correlation_id,
          intent: 'REVIEW',
          from: 'agent://worker',
          to: 'agent://ci-gate',
          payload: { stage: 'review', original: msg.payload },
        });
        ciGate.publish('#contracts', review).catch(() => {});
        resolveReview(msg);
      });

      const unsubCi = ciGate.subscribe('#contracts', 'ci-gate', (msg, ack) => {
        ack();
        const notify = createEnvelope({
          correlation_id,
          intent: 'NOTIFY',
          from: 'agent://ci-gate',
          to: 'agent://kg-ingest',
          payload: { stage: 'event', original: msg.payload },
        });
        kgIngest.publish('#events', notify).catch(() => {});
      });

      const unsubKg = kgIngest.subscribe('#events', 'kg-ingest', (msg, ack) => {
        ack();
        resolveFinal(msg);
      });

      await Promise.all([unsubWorker.ready, unsubCi.ready, unsubKg.ready]);

      const task = createEnvelope({
        correlation_id,
        intent: 'TASK',
        from: 'agent://coa-scheduler',
        to: 'agent://worker',
        payload: { task: 'do-x' },
      });
      const v = validate(task);
      if (!v.valid) throw new Error('task envelope invalid: ' + v.errors.join('; '));

      await coa.publish(null, task);

      const final = await withTimeout(pFinal, 10000, 'fleet multihop final event');
      if (final.correlation_id !== correlation_id) {
        throw new Error('correlation_id lost in multihop (got ' + JSON.stringify(final.correlation_id) + ')');
      }

      unsubWorker();
      unsubCi();
      unsubKg();
    } finally {
      await closeAll([coa, worker, ciGate, kgIngest]);
    }
  });

  await runTest('fleet-broadcast', async () => {
    const names = ['coa-scheduler', 'worker', 'ci-gate', 'kg-ingest'];
    const clients = names.map((n) => new NetworkBusClient(BUS_URL));
    try {
      const correlation_id = randomUUID();
      const receivedBy = new Set();
      let resolveAll;
      const pAll = new Promise((res) => { resolveAll = res; });

      const uns = clients.map((client, i) => {
        const name = names[i];
        return client.subscribe('#broadcast', 'bc-' + name, (msg, ack) => {
          ack();
          if (msg && msg.correlation_id === correlation_id) {
            receivedBy.add(name);
            if (receivedBy.size === names.length) resolveAll();
          }
        });
      });

      await Promise.all(uns.map((u) => u.ready));

      const env = createEnvelope({
        correlation_id,
        intent: 'NOTIFY',
        from: 'agent://coa-scheduler',
        to: 'agent://broadcast',
        payload: { broadcast: true },
      });
      await clients[0].publish('#broadcast', env);

      await withTimeout(pAll, 10000, 'broadcast to all 4 clients');
      if (receivedBy.size !== names.length) {
        throw new Error('only ' + receivedBy.size + '/' + names.length + ' clients received broadcast');
      }
      uns.forEach((u) => u());
    } finally {
      await closeAll(clients);
    }
  });

  await runTest('deadletter', async () => {
    const client = new NetworkBusClient(BUS_URL);
    try {
      let resolveDl;
      const pDl = new Promise((res) => { resolveDl = res; });
      client.onDeadLetter((payload) => { resolveDl(payload); });

      let delivered = false;
      const unsub = client.subscribe('#escalations', 'dl-consumer-' + randomUUID(), (msg, ack) => {
        delivered = true;
      });

      await unsub.ready;

      const env = createEnvelope({
        correlation_id: randomUUID(),
        intent: 'NOTIFY',
        from: 'agent://x',
        to: 'agent://y',
        payload: { dl: true },
      });
      await client.publish('#escalations', env);

      const payload = await withTimeout(pDl, 20000, 'deadletter frame');
      if (!payload) throw new Error('deadletter frame arrived empty');
      unsub();
    } finally {
      await closeAll([client]);
    }
  });

  const allPass = results.every((r) => r.pass);
  process.exit(allPass ? 0 : 1);
}

main().catch((err) => {
  console.error('FATAL: ' + (err && err.stack ? err.stack : String(err)));
  process.exit(1);
});
