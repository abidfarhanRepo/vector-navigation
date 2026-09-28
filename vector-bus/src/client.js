// Reference network client for the E2 Bus wire protocol. Uses node:http only (no global
// fetch) so SSE streaming and teardown are fully controllable. Zero runtime dependencies.
//
// Mirrors the in-process BusService surface over the wire:
//   publish(channel, message) -> Promise
//   subscribe(channel, consumerId, handler) -> unsubscribe()
//   onDeadLetter(cb) -> this
//   close() -> Promise
import http from 'node:http';

function parseTarget(baseUrlOrOptions) {
  if (typeof baseUrlOrOptions === 'string') {
    const u = new URL(baseUrlOrOptions);
    return { host: u.hostname, port: Number(u.port) || 80 };
  }
  const opts = baseUrlOrOptions || {};
  if (opts.baseUrl) {
    const u = new URL(opts.baseUrl);
    return { host: u.hostname, port: Number(u.port) || 80 };
  }
  return { host: opts.host || '127.0.0.1', port: Number(opts.port) };
}

export class NetworkBusClient {
  constructor(baseUrlOrOptions) {
    const { host, port } = parseTarget(baseUrlOrOptions);
    this.host = host;
    this.port = port;
    this._subs = new Set(); // active SSE subscriptions { req, ac }
    this._openReqs = new Set(); // in-flight one-shot requests (publish/ack)
    this._deadLetterHandlers = [];
  }

  /** Register a dead-letter callback. Returns `this` for chaining. */
  onDeadLetter(cb) {
    if (typeof cb === 'function') this._deadLetterHandlers.push(cb);
    return this;
  }

  _requestJson(method, path, bodyObj) {
    return new Promise((resolve, reject) => {
      const body = bodyObj === undefined ? '' : JSON.stringify(bodyObj);
      const headers = {};
      if (body) {
        headers['Content-Type'] = 'application/json';
        headers['Content-Length'] = Buffer.byteLength(body);
      }
      const req = http.request(
        { host: this.host, port: this.port, path, method, headers },
        (res) => {
          let data = '';
          res.setEncoding('utf8');
          res.on('data', (chunk) => { data += chunk; });
          res.on('end', () => {
            this._openReqs.delete(req);
            let parsed = null;
            try { parsed = data ? JSON.parse(data) : {}; } catch { parsed = null; }
            if (res.statusCode >= 200 && res.statusCode < 300) {
              resolve(parsed ?? {});
            } else {
              const detail = parsed && parsed.error ? parsed.error : data;
              reject(new Error(method + ' ' + path + ' failed (' + res.statusCode + '): ' + detail));
            }
          });
        },
      );
      req.on('error', (err) => {
        this._openReqs.delete(req);
        reject(err);
      });
      this._openReqs.add(req);
      if (body) req.write(body);
      req.end();
    });
  }

  /**
   * Publish an envelope. Matches BusService.publish(channel, message). `channel` may be
   * null/undefined to let the server route by intent. Resolves on 202, rejects otherwise.
   */
  publish(channel, message) {
    return this._requestJson('POST', '/publish', { channel, message });
  }

  _ack(deliveryId, consumerId, channel) {
    // Fire-and-forget from the handler's perspective, but tracked for clean teardown.
    return this._requestJson('POST', '/ack', { deliveryId, consumerId, channel })
      .catch(() => ({ acked: false }));
  }

  /**
   * Subscribe to a channel over SSE. Matches BusService.subscribe(channel, consumerId,
   * handler) -> unsubscribe(). The returned function also exposes a `.ready` Promise that
   * resolves once the stream is connected (useful to avoid publish/subscribe races).
   */
  subscribe(channel, consumerId, handler) {
    const ac = new AbortController();
    let readyResolve;
    const ready = new Promise((resolve) => { readyResolve = resolve; });

    const params = new URLSearchParams();
    params.set('channel', channel);
    if (consumerId) params.set('consumerId', consumerId);
    const path = '/subscribe?' + params.toString();

    const req = http.request(
      { host: this.host, port: this.port, path, method: 'GET', signal: ac.signal },
      (res) => {
        res.setEncoding('utf8');
        let buffer = '';
        readyResolve();
        res.on('data', (chunk) => {
          buffer += chunk;
          let idx;
          // SSE frames are separated by a blank line ("\n\n").
          while ((idx = buffer.indexOf('\n\n')) !== -1) {
            const frame = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            this._handleFrame(frame, handler, consumerId, channel);
          }
        });
        res.on('error', () => { /* aborted / socket closed */ });
      },
    );
    req.on('error', () => {
      // AbortError on unsubscribe/close is expected; other errors are non-fatal here.
      readyResolve();
    });
    req.end();

    const sub = { req, ac };
    this._subs.add(sub);

    let done = false;
    const unsubscribe = () => {
      if (done) return;
      done = true;
      this._subs.delete(sub);
      try { ac.abort(); } catch { /* ignore */ }
      try { req.destroy(); } catch { /* ignore */ }
    };
    unsubscribe.ready = ready;
    return unsubscribe;
  }

  _handleFrame(frame, handler, consumerId, channel) {
    const lines = frame.split('\n');
    let event = 'message';
    let dataStr = '';
    for (const line of lines) {
      if (line.length === 0 || line.startsWith(':')) continue; // blank / comment (ping)
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataStr += line.slice(5).replace(/^ /, '');
      }
    }
    if (!dataStr) return;
    let payload;
    try { payload = JSON.parse(dataStr); } catch { return; }

    if (event === 'message') {
      const ack = () => this._ack(payload.deliveryId, consumerId, payload.channel ?? channel);
      handler(payload.message, ack);
    } else if (event === 'deadletter') {
      for (const cb of this._deadLetterHandlers) {
        try { cb(payload); } catch { /* swallow handler errors */ }
      }
    }
  }

  /** Abort all open streams and in-flight requests. Leaves zero open handles. */
  close() {
    return new Promise((resolve) => {
      for (const sub of this._subs) {
        try { sub.ac.abort(); } catch { /* ignore */ }
        try { sub.req.destroy(); } catch { /* ignore */ }
      }
      this._subs.clear();
      for (const req of this._openReqs) {
        try { req.destroy(); } catch { /* ignore */ }
      }
      this._openReqs.clear();
      resolve();
    });
  }
}
