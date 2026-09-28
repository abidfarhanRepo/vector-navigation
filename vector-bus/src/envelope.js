// Standard message envelope (Blueprint §6) — validation, creation, escalation stamping.
// Zero runtime dependencies; uses only node:crypto for UUID generation.
import { randomUUID } from 'node:crypto';

export const INTENTS = ['TASK', 'ASSIGN', 'REVIEW', 'ESCALATE', 'NOTIFY', 'CONTRACT'];
export const PRIORITIES = ['P0', 'P1', 'P2', 'P3'];
export const ESCALATION_LEVELS = ['L0', 'L1', 'L2', 'L3', 'L4'];

const INTENT_SET = new Set(INTENTS);
const PRIORITY_SET = new Set(PRIORITIES);

const REQUIRED_FIELDS = [
  'id',
  'correlation_id',
  'from',
  'to',
  'intent',
  'priority',
  'sla_ms',
  'timestamp',
];

/**
 * Validate an envelope against the RFC2119 schema (Blueprint §6).
 * @param {object} envelope
 * @returns {{ valid: boolean, errors: string[] }}
 */
export function validate(envelope) {
  const errors = [];
  if (envelope === null || typeof envelope !== 'object' || Array.isArray(envelope)) {
    return { valid: false, errors: ['envelope MUST be a non-null object'] };
  }

  for (const field of REQUIRED_FIELDS) {
    if (!(field in envelope)) {
      errors.push('missing required field: ' + field);
    }
  }

  if ('id' in envelope && typeof envelope.id !== 'string') errors.push('id MUST be a string');
  if ('correlation_id' in envelope && typeof envelope.correlation_id !== 'string') {
    errors.push('correlation_id MUST be a string');
  }
  if ('from' in envelope && typeof envelope.from !== 'string') errors.push('from MUST be a string');
  if ('to' in envelope && typeof envelope.to !== 'string') errors.push('to MUST be a string');

  if ('intent' in envelope) {
    if (typeof envelope.intent !== 'string') errors.push('intent MUST be a string');
    else if (!INTENT_SET.has(envelope.intent)) errors.push('intent MUST be one of ' + INTENTS.join('/') + ' (got: ' + envelope.intent + ')');
  }

  if ('priority' in envelope) {
    if (typeof envelope.priority !== 'string') errors.push('priority MUST be a string');
    else if (!PRIORITY_SET.has(envelope.priority)) errors.push('priority MUST be one of ' + PRIORITIES.join('/') + ' (got: ' + envelope.priority + ')');
  }

  if ('sla_ms' in envelope) {
    if (typeof envelope.sla_ms !== 'number' || !Number.isFinite(envelope.sla_ms)) {
      errors.push('sla_ms MUST be a finite number');
    }
  }

  if ('timestamp' in envelope) {
    if (typeof envelope.timestamp !== 'string' || envelope.timestamp.length === 0) {
      errors.push('timestamp MUST be a non-empty string');
    }
  }

  return { valid: errors.length === 0, errors };
}

function pickString(value, fallback) {
  return typeof value === 'string' ? value : fallback;
}

/**
 * Create a valid envelope, filling defaults for omitted fields.
 * @param {object} [partial]
 */
export function createEnvelope(partial = {}) {
  const id = pickString(partial.id, randomUUID());
  const correlation_id = pickString(partial.correlation_id, id);
  const timestamp = pickString(partial.timestamp, new Date().toISOString());
  const payload = partial.payload && typeof partial.payload === 'object' && !Array.isArray(partial.payload)
    ? partial.payload
    : {};
  return {
    id,
    correlation_id,
    from: pickString(partial.from, ''),
    to: pickString(partial.to, ''),
    intent: pickString(partial.intent, 'NOTIFY'),
    priority: pickString(partial.priority, 'P2'),
    payload,
    sla_ms: typeof partial.sla_ms === 'number' && Number.isFinite(partial.sla_ms) ? partial.sla_ms : 3600000,
    timestamp,
  };
}

// Escalation routing targets (Blueprint §7). For L0/L1 the escalation returns to the
// source DMA (modelled as the original `from` agent); L2 -> COA; L3/L4 -> CTO.
function escalationTarget(envelope, level) {
  switch (level) {
    case 'L0':
    case 'L1':
      return envelope && typeof envelope.from === 'string' ? envelope.from : 'agent://dma';
    case 'L2':
      return 'agent://coa';
    case 'L3':
    case 'L4':
      return 'agent://cto';
    default:
      return envelope && typeof envelope.from === 'string' ? envelope.from : 'agent://dma';
  }
}

/**
 * Produce a NEW escalation envelope (does NOT mutate the input). The escalation is routed
 * per level and stamped as intent=ESCALATE, priority=P0, with level/reason in payload.
 * @param {object} envelope source envelope
 * @param {{ level?: string, reason?: string }} opts
 */
export function stampEscalation(envelope, opts = {}) {
  const level = ESCALATION_LEVELS.includes(opts.level) ? opts.level : 'L0';
  const reason = typeof opts.reason === 'string' ? opts.reason : '';
  const sourcePayload = envelope && envelope.payload && typeof envelope.payload === 'object' && !Array.isArray(envelope.payload)
    ? envelope.payload
    : {};
  return {
    id: randomUUID(),
    correlation_id: pickString(envelope && envelope.correlation_id, pickString(envelope && envelope.id, '')),
    from: pickString(envelope && envelope.from, pickString(envelope && envelope.to, 'agent://bus')),
    to: escalationTarget(envelope, level),
    intent: 'ESCALATE',
    priority: 'P0',
    payload: {
      ...sourcePayload,
      level,
      reason,
    },
    sla_ms: envelope && typeof envelope.sla_ms === 'number' ? envelope.sla_ms : 3600000,
    timestamp: new Date().toISOString(),
  };
}
