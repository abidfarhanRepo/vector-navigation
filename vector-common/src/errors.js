// Error model. Canonical error codes live in vector-contracts/errors/error-codes.json (ADR-0001).
// This module is the runtime mirror for the Node/TS tier; keep the two in sync (future: generate).
import { EntityId } from './ids.js';

export const ERROR_CODES = [
  { code: 'VEC-0001', http: 400, category: 'validation', message: 'Invalid request payload', retryable: false },
  { code: 'VEC-0002', http: 401, category: 'auth', message: 'Missing or invalid credential', retryable: false },
  { code: 'VEC-0003', http: 403, category: 'auth', message: 'Insufficient scope', retryable: false },
  { code: 'VEC-0004', http: 404, category: 'not_found', message: 'Resource not found', retryable: false },
  { code: 'VEC-0005', http: 409, category: 'conflict', message: 'Resource conflict', retryable: false },
  { code: 'VEC-0006', http: 422, category: 'validation', message: 'Semantic validation failed', retryable: false },
  { code: 'VEC-0500', http: 500, category: 'internal', message: 'Internal error', retryable: true },
  { code: 'VEC-0501', http: 503, category: 'unavailable', message: 'Service unavailable', retryable: true },
  { code: 'VEC-0502', http: 504, category: 'timeout', message: 'Upstream timeout', retryable: true },
];

const CODE_MAP = new Map(ERROR_CODES.map((c) => [c.code, c]));

/**
 * Base error type carrying a VEC-#### code (Bible §16 typed errors).
 */
export class VectorError extends Error {
  /**
   * @param {string} code VEC-#### code
   * @param {string} [message] override message
   * @param {Object} [opts]
   * @param {string} [opts.detail]
   * @param {string} [opts.correlationId]
   * @param {Error} [opts.cause]
   */
  constructor(code, message, opts = {}) {
    const meta = CODE_MAP.get(code);
    if (!meta) throw new TypeError(`unknown error code: ${code}`);
    super(message || meta.message);
    this.name = 'VectorError';
    this.code = code;
    this.httpStatus = meta.http;
    this.category = meta.category;
    this.retryable = meta.retryable;
    this.detail = opts.detail;
    this.correlationId = opts.correlationId;
    if (opts.cause) this.cause = opts.cause;
  }

  /** @returns {{code:string, message:string, detail?:string, correlation_id?:string}} */
  toEnvelope() {
    const env = { code: this.code, message: this.message };
    if (this.detail) env.detail = this.detail;
    if (this.correlationId) env.correlation_id = this.correlationId;
    return env;
  }
}

/**
 * Build a VectorError from a code.
 * @param {string} code
 * @param {Object} [opts]
 * @returns {VectorError}
 */
export function errorFromCode(code, opts = {}) {
  return new VectorError(code, undefined, opts);
}
