// Channel router (Blueprint §6 / §7). Routes envelopes to channels by intent/to and
// starts an SLA timer per message. On breach it raises an escalation envelope to
// `#escalations`. Integrates with BusService; `ack()` cancels the pending SLA timer.
import { stampEscalation } from './envelope.js';

const defaultScheduler = {
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (id) => clearTimeout(id),
};

/**
 * Pure routing matrix (Blueprint §6 intent -> channel).
 * TASK/ASSIGN -> #tasks; REVIEW/CONTRACT -> #contracts; ESCALATE -> #escalations;
 * NOTIFY -> #broadcast; default/fallback -> #events.
 * @param {object} envelope
 * @returns {string}
 */
export function route(envelope) {
  const intent = envelope && envelope.intent;
  switch (intent) {
    case 'TASK':
    case 'ASSIGN':
      return '#tasks';
    case 'REVIEW':
    case 'CONTRACT':
      return '#contracts';
    case 'ESCALATE':
      return '#escalations';
    case 'NOTIFY':
      return '#broadcast';
    default:
      return '#events';
  }
}

export class ChannelRouter {
  /** Static convenience for the pure routing matrix (Blueprint §6). */
  static route(envelope) {
    return route(envelope);
  }

  constructor(bus, options = {}) {
    this.bus = bus;
    this.scheduler = options.scheduler ?? defaultScheduler;
    this.defaultChannel = options.defaultChannel ?? '#events';
    this.defaultLevel = options.defaultLevel ?? 'L0';
    this._timers = new Map(); // messageId -> { timer, acked }
    this._escalationHandlers = [];
  }

  /** Register a callback fired when an SLA breach escalates. */
  onEscalation(cb) {
    if (typeof cb === 'function') this._escalationHandlers.push(cb);
    return this;
  }

  /**
   * Subscribe through the router so that consumer `ack()` also cancels the SLA timer
   * for that message. Returns the bus unsubscribe function.
   */
  subscribe(channel, consumerId, handler) {
    return this.bus.subscribe(channel, consumerId, (msg, ack) => {
      const wrappedAck = () => {
        ack();
        this.ack(msg.id);
      };
      return handler(msg, wrappedAck);
    });
  }

  /**
   * Route and publish an envelope via the bus, starting an SLA timer of `sla_ms`.
   * @returns {{ channel: string, backpressure: (undefined | Promise<void>) }}
   */
  publish(envelope, options = {}) {
    const channel = route(envelope);
    const slaMs = typeof envelope.sla_ms === 'number' ? envelope.sla_ms : (options.sla_ms ?? 3600000);
    const level = options.level ?? this.defaultLevel;

    const timer = this.scheduler.setTimeout(() => {
      this._breach(envelope, channel, level);
    }, slaMs);
    this._timers.set(envelope.id, { timer, acked: false });

    const backpressure = this.bus.publish(channel, envelope);
    return { channel, backpressure };
  }

  /** Cancel the pending SLA timer for a message (safe to call when already cleared). */
  ack(messageId) {
    const entry = this._timers.get(messageId);
    if (!entry) return;
    this.scheduler.clearTimeout(entry.timer);
    entry.acked = true;
    this._timers.delete(messageId);
  }

  _breach(envelope, channel, level) {
    const entry = this._timers.get(envelope.id);
    if (!entry || entry.acked) return; // already acked -> no escalation
    this._timers.delete(envelope.id);

    const escalation = stampEscalation(envelope, {
      level,
      reason: 'SLA breach on channel ' + channel,
    });

    try {
      this.bus.publish('#escalations', escalation);
    } catch { /* boundary validation handled by bus; escalation envelope is well-formed */ }

    for (const cb of this._escalationHandlers) {
      try { cb(escalation); } catch { /* swallow handler errors */ }
    }
  }
}
