// Networked E2 Event Bus — HTTP + Server-Sent-Events transport over the existing
// in-process BusService engine. Zero runtime dependencies (node stdlib only).
//
// Wire protocol (fixed contract other repos depend on):
//   GET  /healthz                                  -> 200 text/plain "ok"
//   POST /publish   { channel?, message }          -> 202 { accepted, id, channel }
//   GET  /subscribe?channel=..&consumerId=..       -> text/event-stream (SSE)
//   POST /ack       { deliveryId, consumerId?, channel? } -> 200 { acked }
//
// Real at-least-once + dead-letter OVER THE WIRE is achieved by reusing the existing
// engine: each SSE delivery returns a Promise to the BusService that resolves when a
// matching /ack arrives (BusService retry/dead-letter drive the rest).
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { BusService } from './bus.js';
import { route } from './channel-router.js';
import { Auth } from './auth.js';

// CORS headers are produced per-request by `this.auth.corsHeaders()` (which
// restricts origins in secured mode and opens '*' in dev mode). No static
// constant is needed.
// safety measure: unref'd timers cannot make `node --test` hang.
const unrefScheduler = {
  setTimeout: (fn, ms) => {
    const t = setTimeout(fn, ms);
    if (t && typeof t.unref === 'function') t.unref();
    return t;
  },
  clearTimeout: (id) => clearTimeout(id),
};

function writeEvent(res, event, dataObj) {
  if (!res || res.writableEnded || res.destroyed) return;
  try {
    res.write('event: ' + event + '\n');
    res.write('data: ' + JSON.stringify(dataObj) + '\n\n');
  } catch {
    // Socket may have gone away between the check and the write; ignore.
  }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => { data += chunk; });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

export class BusServer {
  constructor(options = {}) {
    // Test-friendly bus defaults; every field is overridable via options.
    this.bus = options.bus ?? new BusService({
      deliveryTimeout: options.deliveryTimeout ?? 1000,
      maxRetries: options.maxRetries ?? 3,
      baseBackoffMs: options.baseBackoffMs ?? 20,
      maxBackoffMs: options.maxBackoffMs ?? 500,
      maxQueue: options.maxQueue ?? 1000,
      hardCap: options.hardCap ?? Infinity,
      validateEnvelopes: options.validateEnvelopes ?? true,
      // Unref'd scheduler so in-flight bus timers never hang the process.
      scheduler: options.scheduler ?? unrefScheduler,
    });

    // Auth boundary: enforces scoped bearer tokens when VECTOR_BUS_ROOT_SECRET
    // (or options.authSecret) is set; dev-anonymous otherwise. CORS is scoped in
    // secured mode. See src/auth.js.
    this.auth = new Auth(options);

    this.deliveryTimeout = options.deliveryTimeout ?? 1000;
    this.maxRetries = options.maxRetries ?? 3;
    // Backstop that cleans up abandoned pending entries well AFTER the bus has fully
    // driven retry/dead-letter, so it never interferes with delivery semantics.
    this.ackTimeout = options.ackTimeout ?? (this.deliveryTimeout * (this.maxRetries + 2) + 1000);
    this.pingIntervalMs = options.pingIntervalMs ?? 15000;

    this._subs = new Set(); // active SSE subscriptions
    this._pending = new Map(); // deliveryId -> { timer, settle }
    this._sockets = new Set(); // all open server sockets (for hard teardown)
    this._address = null;

    // Fan dead-letter records out to every SSE subscriber on the affected channel.
    this.bus.onDeadLetter((record) => {
      for (const sub of this._subs) {
        if (sub.channel === record.channel) {
          writeEvent(sub.res, 'deadletter', {
            channel: record.channel,
            message: record.message,
            error: record.error,
            attempts: record.attempts,
          });
        }
      }
    });

    this.server = http.createServer((req, res) => this._onRequest(req, res));
    this.server.on('connection', (socket) => {
      this._sockets.add(socket);
      socket.on('close', () => this._sockets.delete(socket));
    });
    // Do not let the http server's own keep-alive machinery hang the gate.
    this.server.on('clientError', (_err, socket) => {
      try { socket.destroy(); } catch { /* ignore */ }
    });
  }

  address() {
    return this._address;
  }

  listen(port = 0, host = '127.0.0.1') {
    return new Promise((resolve, reject) => {
      const onError = (err) => reject(err);
      this.server.once('error', onError);
      this.server.listen(port, host, () => {
        this.server.removeListener('error', onError);
        const addr = this.server.address();
        this._address = { port: addr.port, host: addr.address };
        resolve(this._address);
      });
    });
  }

  close() {
    return new Promise((resolve) => {
      // Clear pending ack timers WITHOUT rejecting: rejecting would re-enter the bus
      // and could schedule retries mid-teardown. We simply drop them.
      for (const entry of this._pending.values()) {
        try { clearTimeout(entry.timer); } catch { /* ignore */ }
      }
      this._pending.clear();

      // Tear down every SSE subscription.
      for (const sub of this._subs) {
        try { clearInterval(sub.ping); } catch { /* ignore */ }
        try { sub.unsub(); } catch { /* ignore */ }
        try { if (!sub.res.writableEnded) sub.res.end(); } catch { /* ignore */ }
      }
      this._subs.clear();

      // Destroy any lingering sockets so http.Server.close() can complete.
      for (const socket of this._sockets) {
        try { socket.destroy(); } catch { /* ignore */ }
      }
      this._sockets.clear();

      this.server.close(() => resolve());
    });
  }

  async _onRequest(req, res) {
    let url;
    try {
      url = new URL(req.url, 'http://localhost');
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'bad request url' }));
      return;
    }
    const path = url.pathname;

    // CORS preflight: browsers send OPTIONS before cross-origin fetch. Respond 204
    // with the (per-request, possibly scoped) CORS headers and no body, before any
    // route matching.
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { ...this.auth.corsHeaders(req.headers.origin) });
      res.end();
      return;
    }

    // Enforce scoped auth on protected routes when secured. In dev mode (no secret)
    // this is a no-op and the bus stays backward-compatible (anonymous).
    const PROTECTED = { '/publish': 'publish', '/subscribe': 'subscribe', '/ack': 'subscribe' };
    if (PROTECTED[path]) {
      const auth = this.auth.enforce(req, url, path);
      if (!auth.ok) {
        res.writeHead(auth.status, { 'Content-Type': 'application/json', ...this.auth.corsHeaders(req.headers.origin) });
        res.end(JSON.stringify({ error: auth.error }));
        return;
      }
    }

    if (req.method === 'GET' && path === '/healthz') {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('ok');
      return;
    }

    if (req.method === 'POST' && path === '/publish') {
      await this._handlePublish(req, res);
      return;
    }

    if (req.method === 'GET' && path === '/subscribe') {
      this._handleSubscribe(req, res, url);
      return;
    }

    if (req.method === 'POST' && path === '/ack') {
      await this._handleAck(req, res);
      return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found' }));
  }

  async _handlePublish(req, res) {
    let body;
    try {
      const raw = await readBody(req);
      body = raw ? JSON.parse(raw) : {};
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json', ...this.auth.corsHeaders(req.headers.origin) });
      res.end(JSON.stringify({ error: 'invalid JSON body' }));
      return;
    }

    const message = body.message;
    const channel = body.channel || route(message);

    try {
      // BusService validates the envelope and rejects unknown channels at the boundary.
      this.bus.publish(channel, message);
    } catch (err) {
      res.writeHead(400, { 'Content-Type': 'application/json', ...this.auth.corsHeaders(req.headers.origin) });
      res.end(JSON.stringify({ error: err.message }));
      return;
    }

    res.writeHead(202, { 'Content-Type': 'application/json', ...this.auth.corsHeaders(req.headers.origin) });
    res.end(JSON.stringify({
      accepted: true,
      id: message && message.id,
      channel,
    }));
  }

  _handleSubscribe(req, res, url) {
    const channel = url.searchParams.get('channel');
    const consumerId = url.searchParams.get('consumerId') || randomUUID();

    if (!channel) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'channel query param required' }));
      return;
    }

    let unsub;
    try {
      unsub = this.bus.subscribe(channel, consumerId, (message, ack) => {
        const deliveryId = randomUUID();
        writeEvent(res, 'message', { deliveryId, channel, message });
        // The Promise resolves on a matching /ack and rejects on the backstop timeout,
        // giving BusService real at-least-once + dead-letter over the wire.
        return new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            this._pending.delete(deliveryId);
            reject(new Error('ack timeout for delivery ' + deliveryId));
          }, this.ackTimeout);
          if (timer && typeof timer.unref === 'function') timer.unref();
          this._pending.set(deliveryId, {
            timer,
            settle: (acked) => {
              clearTimeout(timer);
              this._pending.delete(deliveryId);
              if (acked) {
                try { ack(); } catch { /* idempotent */ }
                resolve();
              } else {
                reject(new Error('nacked delivery ' + deliveryId));
              }
            },
          });
        });
      });
    } catch (err) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
      return;
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      ...this.auth.corsHeaders(req.headers.origin),
    });
    if (typeof res.flushHeaders === 'function') res.flushHeaders();
    // Initial comment flushes headers immediately so clients know they are connected
    // (and, by extension, that the bus subscription is already registered).
    res.write(': connected\n\n');

    const ping = setInterval(() => {
      if (!res.writableEnded && !res.destroyed) {
        try { res.write(': ping\n\n'); } catch { /* ignore */ }
      }
    }, this.pingIntervalMs);
    if (ping && typeof ping.unref === 'function') ping.unref();

    const sub = { req, res, channel, consumerId, unsub, ping, closed: false };
    this._subs.add(sub);

    const cleanup = () => {
      if (sub.closed) return;
      sub.closed = true;
      clearInterval(ping);
      try { unsub(); } catch { /* ignore */ }
      this._subs.delete(sub);
      try { if (!res.writableEnded) res.end(); } catch { /* ignore */ }
    };

    req.on('close', cleanup);
    req.on('error', cleanup);
  }

  async _handleAck(req, res) {
    let body;
    try {
      const raw = await readBody(req);
      body = raw ? JSON.parse(raw) : {};
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json', ...this.auth.corsHeaders(req.headers.origin) });
      res.end(JSON.stringify({ error: 'invalid JSON body' }));
      return;
    }

    let acked = false;
    const deliveryId = body && body.deliveryId;
    if (deliveryId && this._pending.has(deliveryId)) {
      const entry = this._pending.get(deliveryId);
      entry.settle(true);
      acked = true;
    }

    // Idempotent: unknown/late deliveryId is not an error, just acked:false.
    res.writeHead(200, { 'Content-Type': 'application/json', ...this.auth.corsHeaders(req.headers.origin) });
    res.end(JSON.stringify({ acked }));
  }
}

/** Factory mirroring the repo's constructor+factory convention. */
export function createBusServer(options = {}) {
  return new BusServer(options);
}
