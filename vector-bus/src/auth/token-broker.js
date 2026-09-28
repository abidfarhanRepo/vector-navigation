// Vendored TokenBroker — canonical source: vector-security/src/token-broker.js
// (ADR-0007 / E5 DI rule forbids cross-repo imports, so the bus vendors a copy
// of this stable, stdlib-only credential broker to validate tokens at the wire
// boundary). Keep this in sync with vector-security's copy; the bus test suite
// asserts the issue/validate contract so drift is caught locally.
import { createHmac, randomBytes } from 'node:crypto';

export class TokenBroker {
  constructor({ rootSecret, clock = Date.now, ttlMs = 10 * 60 * 1000, allowOverlap = true } = {}) {
    if (!rootSecret || typeof rootSecret !== 'string') throw new Error('rootSecret is required');
    this.rootSecret = rootSecret;
    this.prevSecret = null;
    this.clock = clock;
    this.ttlMs = ttlMs;
    this.allowOverlap = allowOverlap;
    this._grants = [];
    this._revoked = new Set();
  }

  _sign(secret, payload) {
    return createHmac('sha256', secret).update(payload).digest('hex');
  }

  issue(scope, opts = {}) {
    const jti = randomBytes(16).toString('hex');
    const ttl = opts.ttlMs ?? this.ttlMs;
    const expiresAt = this.clock() + ttl;
    const grant = { jti, scope, expiresAt, issuedAt: this.clock(), ttlMs: ttl };
    this._grants.push(grant);
    const signature = this._sign(this.rootSecret, `${jti}.${scope}.${expiresAt}`);
    const token = `${signature}.${jti}.${expiresAt}`;
    return { token, scope, jti, expiresAt };
  }

  validate(token) {
    if (typeof token !== 'string' || !token.includes('.')) return { valid: false, reason: 'malformed-token' };
    const parts = token.split('.');
    if (parts.length !== 3) return { valid: false, reason: 'malformed-token' };
    const [signature, jti, expiresAtStr] = parts;
    const expiresAt = Number(expiresAtStr);
    if (!Number.isFinite(expiresAt)) return { valid: false, reason: 'malformed-token' };
    const grant = this._grants.find((g) => g.jti === jti);
    if (!grant) return { valid: false, reason: 'unknown-jti' };
    if (this._revoked.has(jti)) return { valid: false, reason: 'revoked' };
    const expected = this._sign(this.rootSecret, `${jti}.${grant.scope}.${expiresAt}`);
    let ok = signature === expected;
    if (!ok && this.allowOverlap && this.prevSecret) {
      ok = signature === this._sign(this.prevSecret, `${jti}.${grant.scope}.${expiresAt}`);
    }
    if (!ok) return { valid: false, reason: 'bad-signature' };
    if (expiresAt <= this.clock()) return { valid: false, reason: 'expired' };
    return { valid: true, scope: grant.scope, expiresAt, jti };
  }

  revoke(jti) {
    this._revoked.add(jti);
    return this;
  }

  rotate(newRootSecret) {
    if (!newRootSecret || typeof newRootSecret !== 'string') throw new Error('newRootSecret is required');
    this.prevSecret = this.rootSecret;
    this.rootSecret = newRootSecret;
    return this;
  }

  listGrants() {
    return this._grants.map((g) => ({ ...g }));
  }
}
