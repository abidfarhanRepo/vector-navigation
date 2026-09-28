// Bus auth enforcement (WAVE 24). Runs in BOTH modes:
//  * dev mode (no secret) — existing anonymous tests in network-bus.test.js cover it.
//  * secured mode (authSecret set in-process) — this file proves 401/202/403 + CORS.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import { createBusServer } from '../src/server.js';
import { TokenBroker } from '../src/auth/token-broker.js';
import { createEnvelope } from '../src/envelope.js';

const SECRET = 'test-root-secret-wave24';

async function listen(server) {
  const addr = await server.listen(0, '127.0.0.1');
  return addr;
}

function req(addr, method, path, { token, body, origin } = {}) {
  return new Promise((resolve, reject) => {
    const data = body == null ? null : JSON.stringify(body);
    const headers = { Connection: 'close' };
    if (data) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(data);
    }
    if (token) headers['Authorization'] = `Bearer ${token}`;
    if (origin) headers['Origin'] = origin;
    const r = http.request(
      { hostname: addr.host, port: addr.port, method, path, headers },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString() }));
        res.on('error', reject);
      },
    );
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

test('secured bus: publish without token -> 401', async () => {
  const server = createBusServer({ authSecret: SECRET });
  const addr = await listen(server);
  try {
    const res = await req(addr, 'POST', '/publish', { body: { channel: '#events', message: createEnvelope() } });
    assert.equal(res.status, 401);
    assert.match(res.body, /missing-token/);
  } finally {
    await server.close();
  }
});

test('secured bus: publish with valid scoped token -> 202', async () => {
  const server = createBusServer({ authSecret: SECRET });
  const addr = await listen(server);
  // Mint a token from the SERVER's own broker (mirrors the security composition
  // root issuing tokens with the shared root secret).
  const { token } = server.auth.issueToken('publish');
  try {
    const res = await req(addr, 'POST', '/publish', { token, body: { channel: '#events', message: createEnvelope() } });
    assert.equal(res.status, 202);
  } finally {
    await server.close();
  }
});

test('secured bus: publish with bad token -> 401', async () => {
  const server = createBusServer({ authSecret: SECRET });
  const addr = await listen(server);
  try {
    const res = await req(addr, 'POST', '/publish', { token: 'deadbeef.notajti.9999999999999', body: { channel: '#events', message: createEnvelope() } });
    assert.equal(res.status, 401);
  } finally {
    await server.close();
  }
});

test('secured bus: publish with subscribe-scope token -> 403 (scope mismatch)', async () => {
  const server = createBusServer({ authSecret: SECRET });
  const addr = await listen(server);
  const { token } = server.auth.issueToken('subscribe');
  try {
    const res = await req(addr, 'POST', '/publish', { token, body: { channel: '#events', message: createEnvelope() } });
    assert.equal(res.status, 403);
  } finally {
    await server.close();
  }
});

test('secured bus: suscribe without token -> 401', async () => {
  const server = createBusServer({ authSecret: SECRET });
  const addr = await listen(server);
  try {
    const res = await req(addr, 'GET', '/subscribe?channel=%23events&consumerId=c1');
    assert.equal(res.status, 401);
  } finally {
    await server.close();
  }
});

test('secured bus: CORS restricts origin to configured allow-list', async () => {
  const server = createBusServer({ authSecret: SECRET, corsOrigins: ['https://app.vector.local'] });
  const addr = await listen(server);
  try {
    const ok = await req(addr, 'OPTIONS', '/publish', { origin: 'https://app.vector.local' });
    assert.equal(ok.headers['access-control-allow-origin'], 'https://app.vector.local');
    const bad = await req(addr, 'OPTIONS', '/publish', { origin: 'https://evil.example.com' });
    assert.notEqual(bad.headers['access-control-allow-origin'], 'https://evil.example.com');
  } finally {
    await server.close();
  }
});
