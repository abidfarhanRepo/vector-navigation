// Async pub/sub message bus with at-least-once delivery, idempotent consume,
// dead-lettering, and promise-based backpressure. Zero runtime dependencies.
// Envelope validation is wired into `publish` (reject malformed at the boundary).
import { validate } from './envelope.js';

export const CHANNELS = ['#tasks', '#events', '#contracts', '#escalations', '#broadcast'];
const CHANNEL_SET = new Set(CHANNELS);

const defaultScheduler = {
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (id) => clearTimeout(id),
};

/**
 * Thrown when a configured hard cap is exceeded on `publish` (optional; the default
 * behavior is promise-based backpressure, see `maxQueue`/`hardCap`).
 */
export class BackpressureError extends Error {
  constructor(channel) {
    super('Backpressure hard cap exceeded on channel ' + channel);
    this.name = 'BackpressureError';
    this.channel = channel;
  }
}

export class BusService {
  constructor(options = {}) {
    this.maxQueue = options.maxQueue ?? 1000;
    this.highWaterMark = options.highWaterMark ?? Math.max(1, Math.floor(this.maxQueue * 0.8));
    this.hardCap = options.hardCap ?? Infinity;
    this.deliveryTimeout = options.deliveryTimeout ?? 1000;
    this.maxRetries = options.maxRetries ?? 5;
    this.baseBackoffMs = options.baseBackoffMs ?? 50;
    this.maxBackoffMs = options.maxBackoffMs ?? 2000;
    this.jitter = options.jitter ?? 0.2;
    this.validateEnvelopes = options.validateEnvelopes ?? true;
    this.scheduler = options.scheduler ?? defaultScheduler;

    this._subs = new Map(); // channel -> Map(consumerId -> { handler, processed:Set, maxProcessed })
    this._pending = new Map(); // channel -> number of in-flight deliveries
    this._drainWaiters = new Map(); // channel -> Set<resolve>
    this._deadLetterHandlers = [];
  }

  static get channels() {
    return [...CHANNEL_SET];
  }

  _ensureChannel(channel) {
    if (!this._subs.has(channel)) this._subs.set(channel, new Map());
  }

  /**
   * Subscribe a consumer to a channel. Returns an `unsubscribe()` function.
   * The handler receives `(message, ack)`; call `ack()` to mark the message processed.
   */
  subscribe(channel, consumerId, handler) {
    if (!CHANNEL_SET.has(channel)) throw new Error('Unknown channel: ' + channel + '. Must be one of: ' + [...CHANNEL_SET].join(', '));
    if (typeof handler !== 'function') throw new Error('handler MUST be a function');
    this._ensureChannel(channel);
    const existing = this._subs.get(channel).get(consumerId);
    const entry = existing || { handler, processed: new Set(), maxProcessed: 10000 };
    if (existing) entry.handler = handler;
    this._subs.get(channel).set(consumerId, entry);
    let unsubscribed = false;
    return () => {
      if (unsubscribed) return;
      unsubscribed = true;
      this._subs.get(channel)?.delete(consumerId);
    };
  }

  /** Register a dead-letter handler invoked after a message exhausts retries. */
  onDeadLetter(cb) {
    if (typeof cb === 'function') this._deadLetterHandlers.push(cb);
    return this;
  }

  _emitDeadLetter(channel, message, attempts, error) {
    const record = {
      channel,
      message,
      attempts,
      error: error instanceof Error ? error.message : String(error ?? ''),
    };
    for (const cb of this._deadLetterHandlers) {
      try { cb(record); } catch { /* swallow handler errors */ }
    }
  }

  _incPending(channel) {
    const n = (this._pending.get(channel) ?? 0) + 1;
    this._pending.set(channel, n);
    return n;
  }

  _decPending(channel) {
    const n = Math.max(0, (this._pending.get(channel) ?? 1) - 1);
    this._pending.set(channel, n);
    if (n <= this.highWaterMark) {
      const waiters = this._drainWaiters.get(channel);
      if (waiters && waiters.size) {
        this._drainWaiters.set(channel, new Set());
        for (const resolve of waiters) resolve();
      }
    }
  }

  _backoff(attempt) {
    const exp = Math.min(this.maxBackoffMs, this.baseBackoffMs * 2 ** attempt);
    const factor = 1 + (Math.random() * 2 - 1) * this.jitter; // 1 ± jitter
    return Math.max(0, Math.round(exp * factor));
  }

  _trimProcessed(entry) {
    const max = entry.maxProcessed;
    if (entry.processed.size > max) {
      const arr = [...entry.processed];
      entry.processed = new Set(arr.slice(arr.length - Math.floor(max / 2)));
    }
  }

  /**
   * Publish a message (envelope) to a channel.
   * - Unknown channels are rejected (throw).
   * - Invalid envelopes are rejected at the boundary (throw) when `validateEnvelopes` is on.
   * - When the in-flight queue exceeds `maxQueue`, returns a Promise that resolves once the
   *   queue drains below `highWaterMark` (async backpressure).
   * - When the in-flight queue would exceed `hardCap`, throws `BackpressureError`.
   * @returns {undefined | Promise<void>}
   */
  publish(channel, message) {
    if (!CHANNEL_SET.has(channel)) {
      throw new Error('Unknown channel: ' + channel + '. Must be one of: ' + [...CHANNEL_SET].join(', '));
    }
    if (this.validateEnvelopes) {
      const { valid, errors } = validate(message);
      if (!valid) {
        throw new Error('Invalid envelope rejected at boundary: ' + errors.join('; '));
      }
    }

    const current = this._pending.get(channel) ?? 0;
    if (current + 1 > this.hardCap) {
      throw new BackpressureError(channel);
    }

    this._incPending(channel);

    let backpressure = undefined;
    if (current + 1 > this.maxQueue) {
      if (!this._drainWaiters.has(channel)) this._drainWaiters.set(channel, new Set());
      backpressure = new Promise((resolve) => {
        this._drainWaiters.get(channel).add(resolve);
      });
    }

    this._dispatch(channel, message);
    return backpressure;
  }

  _dispatch(channel, message) {
    const subs = this._subs.get(channel);
    if (!subs || subs.size === 0) {
      // No consumers: nothing to acknowledge; the message is immediately done.
      this._decPending(channel);
      return;
    }
    const delivery = { channel, message, pendingConsumers: subs.size, done: false };
    for (const [consumerId, entry] of subs) {
      this._deliverToConsumer(channel, consumerId, entry, delivery, 0);
    }
  }

  _consumerDone(delivery) {
    if (delivery.done) return;
    delivery.pendingConsumers -= 1;
    if (delivery.pendingConsumers <= 0) {
      delivery.done = true;
      this._decPending(delivery.channel);
    }
  }

  _deliverToConsumer(channel, consumerId, entry, delivery, attempt) {
    const message = delivery.message;
    const id = message.id;

    // Idempotent consume: a redelivery of an already-processed id is a no-op.
    if (entry.processed.has(id)) {
      this._consumerDone(delivery);
      return;
    }

    let done = false; // this attempt finished (acked, failed, or timed out)
    let acked = false;
    let timer = null;

    const clearTimer = () => {
      if (timer !== null) {
        this.scheduler.clearTimeout(timer);
        timer = null;
      }
    };

    const ack = () => {
      if (done) return;
      done = true;
      clearTimer();
      if (!acked) {
        acked = true;
        entry.processed.add(id);
        this._trimProcessed(entry);
      }
      this._consumerDone(delivery);
    };

    const fail = (err) => {
      if (done) return;
      done = true;
      clearTimer();
      if (attempt >= this.maxRetries) {
        this._emitDeadLetter(channel, message, attempt + 1, err);
        this._consumerDone(delivery);
        return;
      }
      const backoff = this._backoff(attempt);
      this.scheduler.setTimeout(() => {
        this._deliverToConsumer(channel, consumerId, entry, delivery, attempt + 1);
      }, backoff);
    };

    // Delivery timeout: if the consumer never acks within the window, treat as a failure.
    timer = this.scheduler.setTimeout(() => {
      fail(new Error('Delivery timeout (channel=' + channel + ', consumer=' + consumerId + ')'));
    }, this.deliveryTimeout);

    try {
      const result = entry.handler(message, ack);
      if (result && typeof result.then === 'function') {
        result.then(
          () => { if (!acked) ack(); },
          (err) => { fail(err); },
        );
      }
    } catch (err) {
      fail(err);
    }
  }
}
