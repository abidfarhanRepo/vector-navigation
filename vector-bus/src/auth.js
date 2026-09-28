// Auth boundary for the networked E2 Event Bus.
//
// Two modes, selected by whether a root secret is configured:
//   * SECURED  — VECTOR_BUS_ROOT_SECRET (or options.authSecret) is set. Every
//     /publish and /subscribe (and /ack) request must carry a valid scoped
//     bearer token; CORS is restricted to VECTOR_BUS_CORS_ORIGINS.
//   * DEV       — no secret. Requests are anonymous (backward compatible with
//     the prior unauthenticated bus) and CORS is '*'. This keeps CI/local-dev
//     green; production MUST set VECTOR_BUS_ROOT_SECRET.
//
// Token ISSUANCE is intentionally out of scope here: the bus only validates.
// Tokens are minted by the security composition root (vector-security
// TokenBroker.issue) and injected into clients (NetworkBusClient token option).
import { TokenBroker } from './auth/token-broker.js';

const SCOPE_FOR_PATH = {
  '/publish': 'publish',
  '/subscribe': 'subscribe',
  '/ack': 'subscribe',
};

export class Auth {
  constructor(options = {}) {
    const secret = options.authSecret ?? process.env.VECTOR_BUS_ROOT_SECRET;
    this._secured = typeof secret === 'string' && secret.length > 0;
    this._broker = this._secured ? new TokenBroker({ rootSecret: secret }) : null;
    // CORS allow-list (comma-separated origins) from options or env.
    const fromOpt = Array.isArray(options.corsOrigins)
      ? options.corsOrigins
      : (options.corsOrigins ? String(options.corsOrigins).split(',') : []);
    const fromEnv = (process.env.VECTOR_BUS_CORS_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean);
    this._corsOrigins = [...new Set([...fromOpt, ...fromEnv])].map((s) => s.trim()).filter(Boolean);
  }

  get secured() {
    return this._secured;
  }

  /** Issue a token for the given scope (used by tests / composition root). */
  issueToken(scope, opts) {
    if (!this._broker) throw new Error('auth is not enabled (no root secret)');
    return this._broker.issue(scope, opts);
  }

  _extractToken(req, url) {
    const header = req.headers['authorization'];
    if (header && header.toLowerCase().startsWith('bearer ')) {
      return header.slice(7).trim();
    }
    const q = url.searchParams.get('token');
    return q || null;
  }

  /** Enforce auth for a request; returns {ok:true,scope} or {ok:false,status,error}. */
  enforce(req, url, path) {
    if (!this._secured) return { ok: true, scope: null };
    const required = SCOPE_FOR_PATH[path];
    const token = this._extractToken(req, url);
    if (!token) return { ok: false, status: 401, error: 'missing-token' };
    const result = this._broker.validate(token);
    if (!result.valid) return { ok: false, status: 401, error: result.reason };
    if (required && result.scope !== required) {
      return { ok: false, status: 403, error: `scope '${result.scope}' cannot ${path}` };
    }
    return { ok: true, scope: result.scope };
  }

  /** CORS headers, honoring the configured allow-list in secured mode. */
  corsHeaders(requestOrigin) {
    if (!this._secured) {
      return {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      };
    }
    const allowed = this._corsOrigins;
    const origin = allowed.includes(requestOrigin) ? requestOrigin : (allowed[0] || 'null');
    return {
      'Access-Control-Allow-Origin': origin,
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    };
  }
}
